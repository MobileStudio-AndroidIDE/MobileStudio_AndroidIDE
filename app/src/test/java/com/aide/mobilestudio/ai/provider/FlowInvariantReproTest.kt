package com.aide.mobilestudio.ai.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the Kotlin Flow invariant violation that the remote providers hit.
 *
 * `OcOpenAiCompatibleProvider` (NVIDIA NIM / DeepSeek / Ollama / any
 * OpenAI-compatible base URL), `OcGeminiProvider` and `OcAnthropicProvider`
 * all build their stream as:
 *
 * ```
 * flow {
 *     ...
 *     withContext(Dispatchers.IO) { emit(event) }   // <-- illegal
 * }.flowOn(Dispatchers.IO)
 * ```
 *
 * `withContext` starts a *child* coroutine, so `emit` runs under a different
 * `Job` than the one the flow was collected with, and the collector throws:
 *
 *   "Flow invariant is violated ... Please refer to 'flow' documentation or
 *    use 'flowOn' instead"
 *
 * The local llama provider uses `callbackFlow` (a channel), which is why it
 * keeps working while the remote API providers fail.
 */
class FlowInvariantReproTest {

    private fun providerLikeFlow(events: List<Int>): Flow<Int> = flow {
        withContext(Dispatchers.IO) {
            for (e in events) emit(e)
        }
    }.flowOn(Dispatchers.IO)

    @Test
    fun `emit inside withContext inside flow violates the flow invariant`() = runBlocking {
        var caught: Throwable? = null
        val received = mutableListOf<Int>()
        try {
            providerLikeFlow(listOf(1, 2, 3)).collect { received.add(it) }
        } catch (t: Throwable) {
            caught = t
        }
        println("=== REPRO CAUGHT === $caught")
        caught?.printStackTrace()

        assertTrue(
            "expected a Flow invariant violation, but nothing was thrown",
            caught != null,
        )
        val msg = caught!!.message ?: ""
        assertTrue(
            "unexpected message: $msg",
            msg.contains("Flow invariant is violated"),
        )
        assertTrue(
            "message should point at flowOn: $msg",
            msg.contains("flowOn"),
        )
        assertTrue(
            "no events must reach the collector: $received",
            received.isEmpty(),
        )
    }

    @Test
    fun `the same body without withContext works`() = runBlocking {
        val received = mutableListOf<Int>()
        flow {
            for (e in listOf(1, 2, 3)) emit(e)
        }.flowOn(Dispatchers.IO).collect { received.add(it) }
        assertTrue("expected all events", received == listOf(1, 2, 3))
    }
}
