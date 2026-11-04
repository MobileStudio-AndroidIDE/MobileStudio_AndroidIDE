package com.aide.mobilestudio.ai.api

import com.aide.mobilestudio.ai.retry.ApiErrorClassifier
import com.aide.mobilestudio.ai.retry.ApiErrorInfo
import com.aide.mobilestudio.ai.retry.RequestPhase
import com.aide.mobilestudio.ai.retry.RetryEngine
import com.aide.mobilestudio.ai.retry.RetryPolicy
import com.aide.mobilestudio.ai.retry.RetryStatus
import kotlinx.coroutines.flow.Flow

/**
 * Binds the generic [RetryEngine] to [AIProvider.StreamEvent].
 *
 * Keeping the mapping here means the retry package never learns what a chat
 * stream is, and providers never learn how backoff works.
 */
object StreamRetry {

    /** Events that already put text/tool calls on the screen. */
    fun isContent(event: AIProvider.StreamEvent): Boolean = when (event) {
        is AIProvider.StreamEvent.Text,
        is AIProvider.StreamEvent.Reasoning,
        is AIProvider.StreamEvent.Plan,
        is AIProvider.StreamEvent.ToolCall,
        -> true
        is AIProvider.StreamEvent.Error,
        AIProvider.StreamEvent.Done,
        is AIProvider.StreamEvent.Usage,
        -> false
    }

    /**
     * The classified failure behind an error event. Events without attached
     * classification (legacy paths) still get a best-effort reading of their
     * message - never an automatic "probably transient".
     */
    fun errorOf(event: AIProvider.StreamEvent): ApiErrorInfo? {
        if (event !is AIProvider.StreamEvent.Error) return null
        return event.info ?: ApiErrorClassifier.fromMessage(event.message)
    }

    fun toEvent(info: ApiErrorInfo): AIProvider.StreamEvent =
        AIProvider.StreamEvent.Error(info.detail(), info)

    /** Wraps one provider stream with the retry schedule. */
    fun wrap(
        stream: Flow<AIProvider.StreamEvent>,
        policy: RetryPolicy,
        requestId: String,
        onStatus: (RetryStatus) -> Unit = {},
    ): Flow<AIProvider.StreamEvent> = RetryEngine.withRetry(
        policy = policy,
        requestId = requestId,
        produced = ::isContent,
        errorOf = ::errorOf,
        toFailure = ::toEvent,
        onStatus = onStatus,
    ) { _ -> stream }
}
