package com.aide.mobilestudio.ai.retry

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Where a request currently is, as the UI should show it. */
enum class RequestPhase {
    /** First attempt is being opened. */
    CONNECTING,

    /** The provider is answering and tokens are arriving. */
    STREAMING,

    /** Sitting in a backoff / Retry-After wait. */
    RETRY_WAITING,

    /** A repeat attempt is in flight. */
    RETRYING,

    /** Turn finished normally. */
    COMPLETED,

    /** The user cancelled it. */
    CANCELLED,

    /** No further attempt will be made. */
    FAILED,
}

/**
 * Snapshot handed to the UI while a request runs.
 *
 * @param attempt 1-based attempt number (1 = the original request).
 * @param maxRetries how many repeats are still permitted by the policy.
 * @param nextAttemptAtMs wall-clock time at which the wait ends; lets a UI
 *   count down without the engine pushing a tick every second.
 */
data class RetryStatus(
    val requestId: String,
    val phase: RequestPhase,
    val attempt: Int = 1,
    val maxRetries: Int = 0,
    val nextAttemptAtMs: Long? = null,
    val error: ApiErrorInfo? = null,
    val giveUpReason: String? = null,
) {
    /** Retries left for the current attempt number. */
    fun retriesLeft(): Int = (maxRetries - (attempt - 1)).coerceAtLeast(0)

    /** True while the caller may still see another attempt. */
    fun isRetrying(): Boolean = phase == RequestPhase.RETRY_WAITING || phase == RequestPhase.RETRYING
}

/**
 * Runs a stream-producing request and repeats it while the failure is
 * retryable AND no response content has been shown yet.
 *
 * Why "no content yet": once tokens are on screen a replay would duplicate
 * text the user already read, and no provider here can resume a stream.
 *
 * Everything is driven by [RetryPolicy]; this class holds no magic numbers.
 * Cancellation is propagated - `delay` is a suspending call, so a cancelled
 * collector aborts the wait and never starts another attempt.
 */
object RetryEngine {

    /**
     * @param policy schedule; [RetryPolicy.NONE] simply runs [producer] once.
     * @param requestId correlation id shown in status updates.
     * @param produced marks events that constitute visible response content.
     * @param errorOf extracts the classified failure from an event, if the
     *   event is a failure.
     * @param toFailure builds the terminal event emitted when giving up (used
     *   for exceptions thrown by [producer] as well).
     * @param onStatus phase changes for the UI. Called from the collecting
     *   coroutine; implementations must be cheap and non-suspending.
     * @param clock injectable millisecond clock so budget logic is testable.
     * @param producer builds the stream for the given attempt number.
     */
    fun <T> withRetry(
        policy: RetryPolicy,
        requestId: String,
        produced: (T) -> Boolean,
        errorOf: (T) -> ApiErrorInfo?,
        toFailure: (ApiErrorInfo) -> T,
        onStatus: (RetryStatus) -> Unit = {},
        clock: () -> Long = { System.currentTimeMillis() },
        producer: suspend (attempt: Int) -> Flow<T>,
    ): Flow<T> = flow {
        val startedAt = clock()
        var attempt = 0

        while (true) {
            attempt++
            onStatus(
                RetryStatus(
                    requestId = requestId,
                    phase = if (attempt == 1) RequestPhase.CONNECTING else RequestPhase.RETRYING,
                    attempt = attempt,
                    maxRetries = policy.maxRetries,
                ),
            )

            var contentSeen = false
            var failure: ApiErrorInfo? = null
            var streamingAnnounced = false
            var errorEmitted = false

            try {
                producer(attempt).collect { event ->
                    // A failed attempt's remaining events (including its Done)
                    // belong to the discarded attempt - never to the screen.
                    if (failure != null) return@collect
                    val error = errorOf(event)
                    if (error != null) {
                        // A pre-content failure belongs to the discarded
                        // attempt; once content is on screen the error itself
                        // is the terminal event the user must see.
                        failure = error
                        if (contentSeen) {
                            errorEmitted = true
                            emit(event)
                        }
                        return@collect
                    }
                    if (produced(event)) {
                        contentSeen = true
                        if (!streamingAnnounced) {
                            streamingAnnounced = true
                            onStatus(
                                RetryStatus(
                                    requestId = requestId,
                                    phase = RequestPhase.STREAMING,
                                    attempt = attempt,
                                    maxRetries = policy.maxRetries,
                                ),
                            )
                        }
                    }
                    emit(event)
                }
            } catch (c: CancellationException) {
                onStatus(
                    RetryStatus(
                        requestId = requestId,
                        phase = RequestPhase.CANCELLED,
                        attempt = attempt,
                        maxRetries = policy.maxRetries,
                    ),
                )
                throw c
            } catch (t: Throwable) {
                // Providers normally classify their own failures and emit them
                // as events; this path covers the ones that throw instead.
                failure = ApiErrorClassifier.fromThrowable(t, requestSent = true)
            }

            val error = failure
            if (error == null) {
                onStatus(
                    RetryStatus(
                        requestId = requestId,
                        phase = RequestPhase.COMPLETED,
                        attempt = attempt,
                        maxRetries = policy.maxRetries,
                    ),
                )
                return@flow
            }

            if (contentSeen) {
                // Mid-stream failure: the partial answer stays on screen and is
                // not replayed. The error event was emitted above when it came
                // as an event; only a thrown failure still needs one.
                onStatus(
                    RetryStatus(
                        requestId = requestId,
                        phase = RequestPhase.FAILED,
                        attempt = attempt,
                        maxRetries = policy.maxRetries,
                        error = error,
                        giveUpReason = "stream_interrupted",
                    ),
                )
                if (errorEmitted.not()) emit(toFailure(error))
                return@flow
            }

            val elapsed = clock() - startedAt
            when (val decision = policy.decide(attempt - 1, error, elapsed)) {
                is RetryPolicy.Decision.GiveUp -> {
                    onStatus(
                        RetryStatus(
                            requestId = requestId,
                            phase = RequestPhase.FAILED,
                            attempt = attempt,
                            maxRetries = policy.maxRetries,
                            error = error,
                            giveUpReason = decision.reason,
                        ),
                    )
                    emit(toFailure(error))
                    return@flow
                }
                is RetryPolicy.Decision.Wait -> {
                    val wakesAt = clock() + decision.ms
                    onStatus(
                        RetryStatus(
                            requestId = requestId,
                            phase = RequestPhase.RETRY_WAITING,
                            attempt = attempt,
                            maxRetries = policy.maxRetries,
                            nextAttemptAtMs = wakesAt,
                            error = error,
                        ),
                    )
                    try {
                        delay(decision.ms)
                    } catch (c: CancellationException) {
                        // The wait is cancellable too: a user who aborts
                        // during backoff must see CANCELLED, not a wait that
                        // silently stops updating.
                        onStatus(
                            RetryStatus(
                                requestId = requestId,
                                phase = RequestPhase.CANCELLED,
                                attempt = attempt,
                                maxRetries = policy.maxRetries,
                            ),
                        )
                        throw c
                    }
                }
            }
        }
    }
}
