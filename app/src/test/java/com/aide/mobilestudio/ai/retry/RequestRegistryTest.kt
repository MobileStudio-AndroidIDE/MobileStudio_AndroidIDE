package com.aide.mobilestudio.ai.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestRegistryTest {

    private var now = 1_000L
    private val registry = RequestRegistry(clock = { now })

    @Test
    fun `begin registers a request tied to message provider and model`() {
        val record = registry.begin("m1", "nvidia", "llama-3.3-70b")
        assertNotNull(record)
        assertEquals("m1", record!!.messageId)
        assertEquals("nvidia", record.providerId)
        assertEquals("llama-3.3-70b", record.modelId)
        assertEquals(1, record.attempt)
        assertEquals(1_000L, record.startedAtMs)
        assertEquals(RequestState.ACTIVE, record.state)
    }

    @Test
    fun `a second begin for the same message is refused`() {
        assertNotNull(registry.begin("m1", "nvidia", "m"))
        assertNull("duplicate send must be blocked", registry.begin("m1", "nvidia", "m"))
        assertEquals(1, registry.activeCount())
        assertTrue(registry.isDuplicate("m1"))
    }

    @Test
    fun `different messages are never merged`() {
        assertNotNull(registry.begin("m1", "nvidia", "m"))
        assertNotNull(registry.begin("m2", "gemini", "gemini-2.0-flash"))
        assertEquals(2, registry.activeCount())
        // Each message has its own live request; neither shadows the other.
        assertTrue(registry.isDuplicate("m1"))
        assertTrue(registry.isDuplicate("m2"))
        // A third, distinct message still gets its own record.
        assertNotNull(registry.begin("m3", "openai", "gpt-4o"))
        assertEquals(3, registry.activeCount())
    }

    @Test
    fun `finishing lets the same message be sent again`() {
        val first = registry.begin("m1", "nvidia", "m")!!
        registry.finish(first.requestId, RequestState.FAILED, ApiErrorInfo(ApiErrorKind.RATE_LIMIT, "slow", retryable = true))
        assertNull(registry.activeFor("m1"))
        assertNotNull(registry.begin("m1", "nvidia", "m"))
    }

    @Test
    fun `attempt updates record the retry count and last error`() {
        val record = registry.begin("m1", "nvidia", "m")!!
        val error = ApiErrorInfo(ApiErrorKind.RATE_LIMIT, "429", status = 429, retryable = true)
        registry.updateAttempt(record.requestId, 2, error)
        val updated = registry.get(record.requestId)!!
        assertEquals(2, updated.attempt)
        assertEquals(RequestState.RETRYING, updated.state)
        assertEquals(error, updated.lastError)
        // still blocks a duplicate while retrying
        assertNull(registry.begin("m1", "nvidia", "m"))
    }

    @Test
    fun `cancelled state is not active`() {
        val record = registry.begin("m1", "nvidia", "m")!!
        registry.finish(record.requestId, RequestState.CANCELLED)
        assertNull(registry.activeFor("m1"))
        assertEquals(RequestState.CANCELLED, registry.get(record.requestId)!!.state)
    }

    @Test
    fun `prune keeps recent history and drops the rest`() {
        repeat(60) { i ->
            val r = registry.begin("msg-$i", "nvidia", "m")!!
            registry.finish(r.requestId, RequestState.COMPLETED)
        }
        registry.pruneFinished(keep = 10)
        assertEquals(10, registry.snapshot().size)
    }

    @Test
    fun `prune does not touch running requests`() {
        val running = registry.begin("active", "nvidia", "m")!!
        repeat(5) { i ->
            val r = registry.begin("done-$i", "nvidia", "m")!!
            registry.finish(r.requestId, RequestState.COMPLETED)
        }
        registry.pruneFinished(keep = 0)
        assertNotNull(registry.get(running.requestId))
        assertEquals(1, registry.activeCount())
    }

    @Test
    fun `ids are unique`() {
        val ids = (1..20).map { registry.newId() }.toSet()
        assertEquals(20, ids.size)
    }
}
