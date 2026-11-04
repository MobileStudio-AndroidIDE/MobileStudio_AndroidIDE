package com.aide.mobilestudio.ai.opencode.session

/**
 * How one model's context window is divided up for a single request.
 *
 * The window has to hold the system prompt, the tool schemas, the conversation
 * so far and the tool results all at once, and still leave room for the reply.
 * Those parts are not interchangeable: the system prompt and the tool schemas
 * are the same on every turn, while the conversation grows until compaction
 * shortens it. Treating the whole window as a single number is what let a
 * 4096-token local model be handed more tool JSON than the window itself, so
 * this keeps the parts separate and reports whether a request actually fits.
 *
 * Token counts are estimates unless [isEstimate] says otherwise, and callers
 * are expected to say so rather than present them as measurements.
 */
data class OcContextBudget(
    val contextTokens: Int,
    val modelMaxTokens: Int?,
    val outputReserveTokens: Int,
    val systemTokens: Int,
    val toolTokens: Int,
    val historyTokens: Int,
    /** Characters each individual tool result is allowed to occupy. */
    val toolResultChars: Int,
) {
    val fixedTokens: Int get() = systemTokens + toolTokens
    val inputTokens: Int get() = fixedTokens + historyTokens
    val usedTokens: Int get() = inputTokens + outputReserveTokens
    val usableInputTokens: Int get() = (contextTokens - outputReserveTokens).coerceAtLeast(0)

    /** Positive when the request is larger than the window it has to fit in. */
    val overByTokens: Int get() = (usedTokens - contextTokens).coerceAtLeast(0)
    val fits: Boolean get() = usedTokens <= contextTokens

    /** True while the counts come from a heuristic instead of the tokenizer. */
    val isEstimate: Boolean get() = !OcTokenEstimate.IS_EXACT

    /** Set when the configured window is wider than the model itself supports. */
    val exceedsModelMax: Boolean get() = modelMaxTokens != null && contextTokens > modelMaxTokens

    /** One line suitable for a log or a status row. */
    fun describe(): String {
        val prefix = if (isEstimate) "~" else ""
        val parts = listOf(
            "ctx=$contextTokens",
            "out=$outputReserveTokens",
            "system=${prefix}$systemTokens",
            "tools=${prefix}$toolTokens",
            "history=${prefix}$historyTokens",
            "used=${prefix}$usedTokens",
            "fits=$fits",
        )
        return parts.joinToString(" ")
    }

    companion object {
        /** Never reserve less than this for the reply, or the model cannot answer. */
        const val MIN_OUTPUT_RESERVE = 256

        /** Used when a model does not declare an output cap. */
        const val DEFAULT_OUTPUT = 4_096

        /**
         * Ceiling on the window derived for a local model.
         *
         * Every prompt is prefilled before the first token appears, and that
         * cost is linear in the window, so this is bounded by patience rather
         * than by the model - most local files here declare far more. It caps
         * the derived default only: a window the user configured explicitly is
         * left alone, and the model's own limit still applies on top.
         */
        const val LOCAL_CONTEXT_CEILING = 8_192

        /** Smallest window worth running a chat model in. */
        const val MIN_CONTEXT = 512

        /**
         * Tokens held back for the reply.
         *
         * The configured reserve is sized for a frontier window; on a small
         * one it can exceed the whole context, which drove the compaction
         * threshold to zero or below and made every turn look over budget.
         * Clamping it to a quarter of the window keeps the large-context
         * behaviour (a 200k window still resolves to the same 20k) while
         * leaving a small one with somewhere to put the answer.
         */
        fun outputReserve(contextTokens: Int, declaredOutput: Int?, configuredReserve: Int?): Int {
            if (contextTokens <= 0) return declaredOutput ?: DEFAULT_OUTPUT
            val wanted = maxOf(declaredOutput ?: DEFAULT_OUTPUT, configuredReserve ?: 0)
            return wanted.coerceAtMost(contextTokens / 4).coerceAtLeast(MIN_OUTPUT_RESERVE)
        }

        /** Characters a rough token maps to when a budget is expressed in chars. */
        const val CHARS_PER_TOKEN = 4

        /**
         * Characters each tool result may keep when the window is small.
         *
         * A quarter of the input budget, so one verbose build log cannot crowd
         * out the rest of the conversation.
         */
        fun toolResultChars(contextTokens: Int, outputReserve: Int): Int {
            val input = (contextTokens - outputReserve).coerceAtLeast(0)
            return (input / 4) * CHARS_PER_TOKEN
        }

        fun of(
            contextTokens: Int,
            modelMaxTokens: Int?,
            declaredOutput: Int?,
            configuredReserve: Int?,
            systemText: String,
            toolsJson: String,
            historyText: String,
        ): OcContextBudget {
            val reserve = outputReserve(contextTokens, declaredOutput, configuredReserve)
            val toolResultChars = toolResultChars(contextTokens, reserve)
            return OcContextBudget(
                contextTokens = contextTokens,
                modelMaxTokens = modelMaxTokens,
                outputReserveTokens = reserve,
                systemTokens = OcTokenEstimate.tokens(systemText),
                toolTokens = OcTokenEstimate.tokens(toolsJson),
                historyTokens = OcTokenEstimate.tokens(historyText),
                toolResultChars = toolResultChars,
            )
        }

        /**
         * KV cache bytes per token for a model whose geometry is known.
         *
         * Returns null when any needed figure is missing. A guessed per-token
         * cost would turn into a confident-sounding memory number that is not
         * real, so callers that get null must not claim to have measured one.
         */
        fun kvBytesPerToken(
            blockCount: Int?,
            headCountKv: Int?,
            keyLength: Int?,
            valueLength: Int?,
            bytesPerElement: Int = 2,
        ): Long? {
            if (blockCount == null || blockCount <= 0) return null
            if (headCountKv == null || headCountKv <= 0) return null
            val k = keyLength?.takeIf { it > 0 } ?: return null
            val v = valueLength?.takeIf { it > 0 } ?: k
            return blockCount.toLong() * headCountKv * (k + v) * bytesPerElement
        }

        /** Head dimension, from the model or from hidden size divided by heads. */
        fun headLength(keyLength: Int?, embeddingLength: Int?, headCount: Int?): Int? {
            if (keyLength != null && keyLength > 0) return keyLength
            if (embeddingLength != null && headCount != null && headCount > 0) {
                val derived = embeddingLength / headCount
                if (derived > 0) return derived
            }
            return null
        }

        /**
         * Chooses the context to load a local model with.
         *
         * The configured value is a floor rather than a target: it is what the
         * user asked for, and every local model used to get the same one
         * regardless of what the file said it could hold. The model's own
         * declared limit is what the window may grow to, the ceiling keeps the
         * derived default from running away, and nothing ever exceeds what the
         * model was trained for.
         *
         * Memory is applied last and only when the per-token KV cost is known.
         * Without it the memory check is skipped rather than invented, and the
         * result says nothing about bytes.
         */
        fun chooseLocalContext(
            configured: Int,
            modelMax: Int?,
            availableBytes: Long? = null,
            kvBytesPerToken: Long? = null,
            memoryHeadroomBytes: Long = 0,
        ): LocalContextChoice {
            val requested = configured.coerceAtLeast(MIN_CONTEXT)
            // Read from the file. Absent means unknown, which keeps the
            // configured value instead of guessing a bigger one.
            val capability = modelMax?.takeIf { it >= MIN_CONTEXT }
            val derivedDefault = (capability ?: requested)
                .coerceAtMost(LOCAL_CONTEXT_CEILING)
                .coerceAtLeast(MIN_CONTEXT)

            var tokens = maxOf(requested, derivedDefault)
            if (capability != null) tokens = tokens.coerceAtMost(capability)
            tokens = tokens.coerceAtLeast(MIN_CONTEXT)

            if (availableBytes == null || kvBytesPerToken == null) {
                return LocalContextChoice(tokens, limitedByMemory = false, estimatedBytes = null, suggestion = null)
            }

            val usable = availableBytes - memoryHeadroomBytes
            var estimated = kvBytesPerToken * tokens
            if (usable <= 0 || estimated <= usable) {
                return LocalContextChoice(tokens, limitedByMemory = false, estimatedBytes = estimated, suggestion = null)
            }
            while (tokens > MIN_CONTEXT) {
                tokens = nextLowerStep(tokens)
                estimated = kvBytesPerToken * tokens
                if (estimated <= usable) break
            }
            if (kvBytesPerToken * tokens > usable) {
                return LocalContextChoice(
                    tokens = MIN_CONTEXT,
                    limitedByMemory = true,
                    estimatedBytes = kvBytesPerToken * MIN_CONTEXT,
                    suggestion = "Not enough free memory for this model; close other apps or use a smaller one.",
                )
            }
            return LocalContextChoice(
                tokens = tokens,
                limitedByMemory = true,
                estimatedBytes = estimated,
                suggestion = "Context reduced to $tokens tokens to fit available memory.",
            )
        }

        /** Halves a window, then moves through the familiar sizes below it. */
        private fun nextLowerStep(tokens: Int): Int {
            val halved = tokens / 2
            return if (halved >= 2048) halved
            else maxOf(MIN_CONTEXT, halved)
        }
    }
}

/** Outcome of [OcContextBudget.chooseLocalContext]. */
data class LocalContextChoice(
    val tokens: Int,
    val limitedByMemory: Boolean,
    /** Null when the per-token cost was unknown and no figure was computed. */
    val estimatedBytes: Long?,
    val suggestion: String?,
)
