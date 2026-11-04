package com.aide.mobilestudio.ai.retry

import kotlin.random.Random

/**
 * Retry schedule for one external API request.
 *
 * Every knob lives here on purpose: policy values must not be scattered across
 * providers, or two endpoints end up with two different - and undocumented -
 * notions of how long a user is expected to wait.
 *
 * Defaults (see [DEFAULT]):
 *  - 3 retries, the first attempt is not one of them
 *  - 60s budget for the whole operation, including time spent inside attempts
 *  - 2s, 4s, 8s exponential waits with a small random jitter
 *  - a 10s ceiling on the *local* backoff (a server hint may exceed it, see
 *    [decide])
 *
 * @param maxRetries how many times a failed attempt may be repeated. The
 *   original request is attempt 1 and is never counted as a retry.
 * @param totalBudgetMs wall-clock ceiling covering request execution AND
 *   waiting. When the budget is spent no further attempt starts.
 * @param initialDelayMs wait before the first retry.
 * @param maxDelayMs ceiling applied to the computed exponential backoff. The
 *   server's own Retry-After is deliberately not capped by this value.
 * @param backoffMultiplier Exponential growth factor.
 * @param jitterMs Random extra delay in `[0, jitterMs)` added to a backoff so a
 *   burst of requests does not retry in lockstep.
 * @param retryAfterBudgetCapMs A server hint longer than this is refused even
 *   if the overall budget could somehow absorb it - better to fail now than to
 *   hold the request open for half a minute on every attempt.
 * @param retryOnUncertainOutcome Whether to repeat a request whose outcome is
 *   unknown (the connection died after the body was sent). No provider here
 *   offers idempotency keys, so a repeat may be billed twice; the switch exists
 *   so the trade-off is a decision, not an accident.
 */
data class RetryPolicy(
    val maxRetries: Int = 3,
    val totalBudgetMs: Long = 60_000L,
    val initialDelayMs: Long = 2_000L,
    val maxDelayMs: Long = 10_000L,
    val backoffMultiplier: Double = 2.0,
    val jitterMs: Long = 400L,
    val retryAfterBudgetCapMs: Long = 30_000L,
    val retryOnUncertainOutcome: Boolean = true,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(totalBudgetMs > 0) { "totalBudgetMs must be positive" }
        require(initialDelayMs >= 0) { "initialDelayMs must not be negative" }
        require(maxDelayMs >= 0) { "maxDelayMs must not be negative" }
        require(backoffMultiplier >= 1.0) { "backoffMultiplier must be >= 1" }
        require(jitterMs >= 0) { "jitterMs must not be negative" }
    }

    /** What to do after a failed attempt. */
    sealed interface Decision {
        /** Wait [ms] (already including jitter and any Retry-After), then repeat. */
        data class Wait(val ms: Long) : Decision

        /** Stop: budget spent, retries exhausted, or the error cannot be helped. */
        data class GiveUp(val reason: String) : Decision
    }

    /**
     * Local exponential backoff for the retry numbered [retryIndex] (0 = the
     * first retry, so the plain schedule is 2s / 4s / 8s).
     */
    fun backoffDelayMs(retryIndex: Int, random: Random = Random.Default): Long {
        val exponent = retryIndex.coerceAtLeast(0)
        val raw = initialDelayMs * Math.pow(backoffMultiplier, exponent.toDouble())
        val capped = if (raw > maxDelayMs) maxDelayMs else raw.toLong()
        val jitter = if (jitterMs > 0) random.nextLong(0, jitterMs + 1) else 0L
        return capped + jitter
    }

    /**
     * Decides whether to wait after a failed attempt.
     *
     * @param retryIndex 0 for the wait before the first retry.
     * @param error the classified failure.
     * @param elapsedMs time already spent, including inside the failed attempt.
     * @param random source of jitter; injectable so tests are deterministic.
     */
    fun decide(
        retryIndex: Int,
        error: ApiErrorInfo,
        elapsedMs: Long,
        random: Random = Random.Default,
    ): Decision {
        if (error.kind == ApiErrorKind.CANCELLED) return Decision.GiveUp("cancelled")
        if (!error.retryable) return Decision.GiveUp(error.kind.name)
        if (retryIndex >= maxRetries) return Decision.GiveUp("retries_exhausted")
        if (elapsedMs >= totalBudgetMs) return Decision.GiveUp("budget_exhausted")
        if (error.uncertainOutcome && !retryOnUncertainOutcome) {
            return Decision.GiveUp("outcome_uncertain")
        }

        val backoff = backoffDelayMs(retryIndex, random)
        val serverWait = error.retryAfterMs
        val wait = when {
            serverWait != null -> {
                // The server named its limit: never come back earlier, and give
                // up instead of sleeping past the budget or the cap.
                if (serverWait > retryAfterBudgetCapMs) return Decision.GiveUp("retry_after_too_long")
                if (elapsedMs + serverWait > totalBudgetMs) return Decision.GiveUp("retry_after_exceeds_budget")
                serverWait
            }
            else -> backoff
        }
        if (elapsedMs + wait > totalBudgetMs) return Decision.GiveUp("budget_exhausted")
        return Decision.Wait(wait)
    }

    companion object {
        /** The schedule described at the top of this class. */
        val DEFAULT = RetryPolicy()

        /** Never retries: for local llama.cpp streams and for user-cancelled work. */
        val NONE = RetryPolicy(maxRetries = 0, totalBudgetMs = 1L)
    }
}
