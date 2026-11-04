package com.aide.mobilestudio.ai.retry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Stream-level retry behaviour. Fake events stand in for
 * AIProvider.StreamEvent so the engine stays provider-agnostic.
 */
class RetryEngineTest {

    private sealed class Ev {
        data class Text(val text: String) : Ev()
        data class Err(val info: ApiErrorInfo) : Ev()
        object Done : Ev()
    }

    private fun text(t: String) = Ev.Text(t)
    private fun done() = Ev.Done
    private fun retryable(kind: ApiErrorKind, retryAfterMs: Long? = null) = Ev.Err(
        ApiErrorInfo(kind, "boom", status = 429, retryAfterMs = retryAfterMs, retryable = true),
    )

    private val fastPolicy = RetryPolicy(
        maxRetries = 3,
        totalBudgetMs = 60_000,
        initialDelayMs = 15,
        maxDelayMs = 40,
        jitterMs = 0,
    )

    private class Harness(val policy: RetryPolicy, val clock: () -> Long = { System.currentTimeMillis() }) {
        private val eventList = java.util.Collections.synchronizedList(mutableListOf<Ev>())
        private val phaseList2 = java.util.Collections.synchronizedList(mutableListOf<RetryStatus>())
        private val attemptList = java.util.Collections.synchronizedList(mutableListOf<Int>())

        val events: List<Ev> get() = eventList.toList()
        val phases: List<RetryStatus> get() = phaseList2.toList()
        val attemptsSeen: List<Int> get() = attemptList.toList()

        @Volatile
        var attemptCount: Int = 0

        fun run(block: suspend (attempt: Int) -> Flow<Ev>): Flow<Ev> = RetryEngine.withRetry(
            policy = policy,
            requestId = "req-1",
            produced = { it is Ev.Text },
            errorOf = { (it as? Ev.Err)?.info },
            toFailure = { Ev.Err(it) },
            onStatus = { phaseList2.add(it) },
            clock = clock,
            producer = { attempt ->
                attemptCount++
                attemptList.add(attempt)
                block(attempt)
            },
        )

        suspend fun collect(flow: Flow<Ev>) = flow.collect { eventList.add(it) }

        fun phaseList(): List<RequestPhase> = phases.map { it.phase }
    }

    // -------------------------------------------------------------- happy path

    @Test
    fun `normal response runs once and completes`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { flow { emit(text("hello")); emit(done()) } })
        assertEquals(1, h.attemptCount)
        assertEquals(listOf(RequestPhase.CONNECTING, RequestPhase.STREAMING, RequestPhase.COMPLETED), h.phaseList())
        assertEquals(2, h.events.size)
        assertEquals("hello", (h.events[0] as Ev.Text).text)
    }

    // ------------------------------------------------------------- retry paths

    @Test
    fun `429 then success retries once and only shows the second answer`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { attempt ->
            flow {
                if (attempt == 1) {
                    emit(retryable(ApiErrorKind.RATE_LIMIT))
                    emit(done())
                } else {
                    emit(text("second try"))
                    emit(done())
                }
            }
        })
        assertEquals(2, h.attemptCount)
        assertEquals("second try", (h.events[0] as Ev.Text).text)
        assertTrue("the discarded attempt's error must not surface", h.events.filterIsInstance<Ev.Err>().isEmpty())
        assertTrue(h.phaseList().contains(RequestPhase.RETRY_WAITING))
        assertTrue(h.phaseList().contains(RequestPhase.RETRYING))
        val waiting = h.phases.first { it.phase == RequestPhase.RETRY_WAITING }
        assertNotNull(waiting.nextAttemptAtMs)
        assertEquals(1, waiting.attempt)
        assertEquals(ApiErrorKind.RATE_LIMIT, waiting.error?.kind)
    }

    @Test
    fun `503 then success retries`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { attempt ->
            flow {
                if (attempt == 1) {
                    emit(Ev.Err(ApiErrorInfo(ApiErrorKind.SERVER_ERROR, "unavailable", status = 503, retryable = true)))
                } else {
                    emit(text("ok"))
                }
                emit(done())
            }
        })
        assertEquals(2, h.attemptCount)
        assertTrue(h.events.first() is Ev.Text)
    }

    @Test
    fun `repeated failures give up after maxRetries and surface one error`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { flow { emit(retryable(ApiErrorKind.SERVER_ERROR)); emit(done()) } })
        // 1 original + 3 retries
        assertEquals(4, h.attemptCount)
        assertEquals(1, h.events.filterIsInstance<Ev.Err>().size)
        assertEquals(RequestPhase.FAILED, h.phases.last().phase)
        assertEquals("retries_exhausted", h.phases.last().giveUpReason)
    }

    @Test
    fun `auth failure is not retried`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run {
            flow { emit(Ev.Err(ApiErrorInfo(ApiErrorKind.AUTH, "bad key", status = 401, retryable = false))) }
        })
        assertEquals(1, h.attemptCount)
        assertEquals(1, h.events.filterIsInstance<Ev.Err>().size)
        assertEquals(RequestPhase.FAILED, h.phases.last().phase)
        assertEquals("AUTH", h.phases.last().giveUpReason)
    }

    @Test
    fun `usage quota failure is not retried`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run {
            flow {
                emit(
                    Ev.Err(
                        ApiErrorInfo(
                            ApiErrorKind.QUOTA_EXHAUSTED,
                            "insufficient_quota",
                            status = 429,
                            retryable = false,
                        ),
                    ),
                )
            }
        })
        assertEquals(1, h.attemptCount)
    }

    @Test
    fun `server retry-after replaces the local backoff`() = runBlocking {
        // The local schedule would wait 5s; the server says 60ms.
        val policy = fastPolicy.copy(initialDelayMs = 5_000, maxDelayMs = 5_000)
        val h = Harness(policy)
        val start = System.currentTimeMillis()
        h.collect(h.run { attempt ->
            flow {
                if (attempt == 1) {
                    emit(Ev.Err(ApiErrorInfo(ApiErrorKind.RATE_LIMIT, "slow down", status = 429, retryAfterMs = 60, retryable = true)))
                } else {
                    emit(text("ok"))
                }
            }
        })
        val elapsed = System.currentTimeMillis() - start
        assertEquals(2, h.attemptCount)
        assertTrue("elapsed=$elapsed", elapsed < 2_000)
    }

    @Test
    fun `retry-after longer than the budget fails immediately`() = runBlocking {
        val policy = RetryPolicy(maxRetries = 3, totalBudgetMs = 1_000, initialDelayMs = 10, jitterMs = 0, retryAfterBudgetCapMs = 500)
        val h = Harness(policy)
        h.collect(h.run {
            flow {
                emit(Ev.Err(ApiErrorInfo(ApiErrorKind.RATE_LIMIT, "later", status = 429, retryAfterMs = 120_000, retryable = true)))
            }
        })
        assertEquals(1, h.attemptCount)
        assertEquals("retry_after_too_long", h.phases.last().giveUpReason)
    }

    @Test
    fun `budget exhaustion stops the loop even with retries left`() = runBlocking {
        // Fake clock: every read advances 20s, so the moment the engine measures
        // the elapsed time the budget (1s) is already spent.
        var now = 0L
        val clock: () -> Long = { now += 20_000; now }
        val policy = RetryPolicy(maxRetries = 5, totalBudgetMs = 1_000, initialDelayMs = 1, jitterMs = 0)
        val h = Harness(policy, clock)
        h.collect(h.run { flow { emit(retryable(ApiErrorKind.SERVER_ERROR)) } })
        assertEquals(1, h.attemptCount)
        assertEquals("budget_exhausted", h.phases.last().giveUpReason)
    }

    // -------------------------------------------------------- thrown failures

    @Test
    fun `thrown network error is classified and retried`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { attempt ->
            flow {
                if (attempt == 1) throw IOException("Connection reset")
                emit(text("recovered"))
            }
        })
        assertEquals(2, h.attemptCount)
        assertTrue(h.events.first() is Ev.Text)
    }

    @Test
    fun `thrown timeout is retried`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { attempt ->
            flow {
                if (attempt == 1) throw SocketTimeoutException("read timed out")
                emit(text("recovered"))
            }
        })
        assertEquals(2, h.attemptCount)
    }

    @Test
    fun `thrown non-retryable failure stops at once`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { flow { throw IllegalStateException("bad state") } })
        assertEquals(1, h.attemptCount)
        val err = h.events.filterIsInstance<Ev.Err>().single()
        assertEquals(ApiErrorKind.UNKNOWN, err.info.kind)
        assertFalse(err.info.retryable)
    }

    // ----------------------------------------------------------- mid-stream

    @Test
    fun `failure after content is never replayed`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { flow {
            emit(text("partial "))
            emit(retryable(ApiErrorKind.NETWORK))
            emit(done())
        } })
        assertEquals(1, h.attemptCount)
        assertEquals(2, h.events.size)
        assertTrue(h.events[0] is Ev.Text)
        assertTrue(h.events[1] is Ev.Err)
        assertEquals("stream_interrupted", h.phases.last().giveUpReason)
        assertFalse(h.phaseList().contains(RequestPhase.RETRY_WAITING))
    }

    @Test
    fun `events of a discarded attempt never reach the collector`() = runBlocking {
        val h = Harness(fastPolicy)
        h.collect(h.run { attempt ->
            flow {
                if (attempt == 1) {
                    emit(retryable(ApiErrorKind.RATE_LIMIT))
                    emit(text("ghost"))
                    emit(done())
                } else {
                    emit(text("real"))
                    emit(done())
                }
            }
        })
        assertEquals(listOf("real"), h.events.filterIsInstance<Ev.Text>().map { it.text })
        assertEquals(1, h.events.filterIsInstance<Ev.Done>().size)
    }

    // ------------------------------------------------------------- cancellation

    @Test
    fun `cancelling during the wait aborts without another attempt`() = runBlocking {
        val policy = RetryPolicy(maxRetries = 3, totalBudgetMs = 60_000, initialDelayMs = 4_000, jitterMs = 0)
        val h = Harness(policy)
        val job = launch(Dispatchers.Default) {
            h.collect(h.run { flow { emit(retryable(ApiErrorKind.RATE_LIMIT)) } })
        }
        // Let it reach the wait, then cancel.
        while (h.phaseList().none { it == RequestPhase.RETRY_WAITING }) delay(5)
        job.cancelAndJoin()
        assertEquals(1, h.attemptCount)
        assertEquals(RequestPhase.CANCELLED, h.phases.last().phase)
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `cancellation is reported as cancellation not as an error`() = runBlocking {
        val policy = RetryPolicy(maxRetries = 3, totalBudgetMs = 60_000, initialDelayMs = 4_000, jitterMs = 0)
        val h = Harness(policy)
        val job = launch(Dispatchers.Default) {
            runCatching {
                withTimeout(10_000) {
                    h.collect(h.run { flow { emit(retryable(ApiErrorKind.RATE_LIMIT)) } })
                }
            }
        }
        while (h.phaseList().none { it == RequestPhase.RETRY_WAITING }) delay(5)
        job.cancelAndJoin()
        assertTrue(h.events.filterIsInstance<Ev.Err>().isEmpty())
    }

    // ---------------------------------------------------------------- policy

    @Test
    fun `default schedule is 2s 4s 8s with a cap`() {
        val policy = RetryPolicy.DEFAULT
        val random = Random(1)
        val first = policy.backoffDelayMs(0, random)
        val second = policy.backoffDelayMs(1, random)
        val third = policy.backoffDelayMs(2, random)
        assertTrue(first in 2_000..2_400)
        assertTrue(second in 4_000..4_400)
        assertTrue(third in 8_000..8_400)
        val tenth = policy.backoffDelayMs(9, random)
        assertTrue("cap expected, was $tenth", tenth <= policy.maxDelayMs + policy.jitterMs)
    }

    @Test
    fun `decide gives up when retries are used up`() {
        val policy = RetryPolicy.DEFAULT
        val err = ApiErrorInfo(ApiErrorKind.RATE_LIMIT, "x", retryable = true)
        assertTrue(policy.decide(3, err, 0, Random(1)) is RetryPolicy.Decision.GiveUp)
        assertTrue(policy.decide(0, err, 0, Random(1)) is RetryPolicy.Decision.Wait)
    }

    @Test
    fun `none policy never waits`() {
        val err = ApiErrorInfo(ApiErrorKind.SERVER_ERROR, "x", retryable = true)
        assertTrue(RetryPolicy.NONE.decide(0, err, 0) is RetryPolicy.Decision.GiveUp)
    }

    @Test
    fun `uncertain outcome can be switched off`() {
        val err = ApiErrorInfo(ApiErrorKind.NETWORK, "reset", retryable = true, uncertainOutcome = true)
        val off = RetryPolicy(retryOnUncertainOutcome = false)
        assertEquals("outcome_uncertain", (off.decide(0, err, 0) as RetryPolicy.Decision.GiveUp).reason)
        assertTrue(RetryPolicy.DEFAULT.decide(0, err, 0) is RetryPolicy.Decision.Wait)
    }

    @Test
    fun `status counts retries left`() {
        val s = RetryStatus("r", RequestPhase.RETRY_WAITING, attempt = 2, maxRetries = 3)
        assertEquals(2, s.retriesLeft())
        assertNull(s.nextAttemptAtMs)
        assertTrue(s.isRetrying())
        assertEquals(0, RetryStatus("r", RequestPhase.RETRY_WAITING, attempt = 4, maxRetries = 3).retriesLeft())
    }
}
