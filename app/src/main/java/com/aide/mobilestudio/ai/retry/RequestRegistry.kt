package com.aide.mobilestudio.ai.retry

import java.util.UUID

/** Lifecycle of one user message's request. */
enum class RequestState { ACTIVE, RETRYING, COMPLETED, FAILED, CANCELLED }

/**
 * One in-flight request: the message it belongs to, which provider/model it
 * uses, how many attempts it has burned and why the last one failed.
 */
data class RequestRecord(
    val requestId: String,
    val messageId: String,
    val providerId: String,
    val modelId: String,
    val startedAtMs: Long,
    val attempt: Int = 1,
    val lastError: ApiErrorInfo? = null,
    val state: RequestState = RequestState.ACTIVE,
)

/**
 * Guards against duplicate requests.
 *
 * The engine already queues messages, but a retry loop adds a second place
 * where the same message could be launched twice (a double tap on "retry", or
 * a queued message while the previous attempt is still waiting out a
 * Retry-After). One messageId maps to at most one ACTIVE request; a second
 * [begin] for the same message returns null instead of starting a parallel
 * copy. Different messages are never merged - they get their own records.
 *
 * Pure JVM (injected clock), so it is unit-testable.
 */
class RequestRegistry(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private val records = LinkedHashMap<String, RequestRecord>()

    /** New id for a request that is about to start. */
    fun newId(): String = "req-" + UUID.randomUUID().toString().take(8)

    /**
     * Registers a request for [messageId].
     *
     * @return the new record, or null when a request for this message is
     *   already running (the caller must not send again).
     */
    @Synchronized
    fun begin(messageId: String, providerId: String, modelId: String): RequestRecord? {
        if (activeFor(messageId) != null) return null
        val record = RequestRecord(
            requestId = newId(),
            messageId = messageId,
            providerId = providerId,
            modelId = modelId,
            startedAtMs = clock(),
        )
        records[record.requestId] = record
        return record
    }

    @Synchronized
    fun activeFor(messageId: String): RequestRecord? =
        records.values.firstOrNull {
            it.messageId == messageId && (it.state == RequestState.ACTIVE || it.state == RequestState.RETRYING)
        }

    @Synchronized
    fun get(requestId: String): RequestRecord? = records[requestId]

    @Synchronized
    fun updateAttempt(requestId: String, attempt: Int, error: ApiErrorInfo?) {
        val current = records[requestId] ?: return
        records[requestId] = current.copy(
            attempt = attempt,
            lastError = error ?: current.lastError,
            state = if (attempt > 1) RequestState.RETRYING else current.state,
        )
    }

    @Synchronized
    fun finish(requestId: String, state: RequestState, error: ApiErrorInfo? = null) {
        val current = records[requestId] ?: return
        records[requestId] = current.copy(state = state, lastError = error ?: current.lastError)
    }

    /** True when the given message already has a live request. */
    @Synchronized
    fun isDuplicate(messageId: String): Boolean = activeFor(messageId) != null

    @Synchronized
    fun activeCount(): Int =
        records.values.count { it.state == RequestState.ACTIVE || it.state == RequestState.RETRYING }

    /** Copy of every record, oldest first - for diagnostics and tests. */
    @Synchronized
    fun snapshot(): List<RequestRecord> = records.values.toList()

    /** Keeps the table from growing without bound across a long session. */
    @Synchronized
    fun pruneFinished(keep: Int = 50) {
        val finished = records.values.filter {
            it.state == RequestState.COMPLETED || it.state == RequestState.FAILED || it.state == RequestState.CANCELLED
        }
        if (finished.size <= keep) return
        finished.take(finished.size - keep).forEach { records.remove(it.requestId) }
    }

    @Synchronized
    fun clear() = records.clear()
}
