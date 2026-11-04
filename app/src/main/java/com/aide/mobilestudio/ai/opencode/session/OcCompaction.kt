package com.aide.mobilestudio.ai.opencode.session

import org.json.JSONArray
import org.json.JSONObject

object OcCompaction {

    const val COMPACTION_BUFFER = 20_000
    const val PRUNE_MINIMUM = 20_000
    const val PRUNE_PROTECT = 40_000
    const val TOOL_OUTPUT_MAX_CHARS = 2_000
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000
    const val MAX_PRESERVE_RECENT_TOKENS = 15_000
    const val SUMMARY_OUTPUT_TOKENS = 4_096

    val PRUNE_PROTECTED_TOOLS = listOf("skill")

    const val SUMMARY_TEMPLATE = """Output exactly the Markdown structure shown inside <template> and keep the section order unchanged. Do not include the <template> tags in your response.
<template>
## Objective
- [one or two brief sentences describing what the user is trying to accomplish]

## Important Details
- [constraints/preferences, decisions and why, important facts/assumptions, exact context needed to continue, or "(none)"]

## Work State
### Completed
- [finished work, verified facts, or changes made; otherwise "(none)"]

### Active
- [current work, partial changes, or investigation state; otherwise "(none)"]

### Blocked
- [blockers, failing commands, or unknowns; otherwise "(none)"]

## Next Move
1. [immediate concrete action, or "(none)"]
2. [next action if known, or "(none)"]

## Relevant Files
- [file or directory path: why it matters, or "(none)"]
</template>

Rules:
- Keep every section, even when empty.
- Use terse bullets, not prose paragraphs.
- Preserve exact file paths, symbols, commands, error strings, URLs, and identifiers when known.
- Do not mention the summary process or that context was compacted."""

    const val SUMMARY_UPDATE_INSTRUCTIONS = """The <prior-summary> summarizes everything that happened before the <conversation>. Construct a new summary that combines both. The <prior-summary> is discarded after this: anything you do not carry into the new summary is lost.

When combining:
- Carry forward objectives, constraints, user directives, decisions, and parallel workstreams from the <prior-summary> even when the <conversation> does not mention them. Drop only what is finished and no longer needed.
- The <conversation> is more recent than the <prior-summary>. Where they conflict, the conversation wins: state the corrected fact and drop the old claim.
- Add new progress, decisions, constraints, and context from the conversation.
- Move completed work from "Active" to "Completed".
- If a blocker has been resolved, update the summary to reflect that while keeping any details still needed to continue the work.
- Update "Objective" and "Next Move" to reflect the current work state."""

    interface Llm {
        suspend fun generate(prompt: String, outputTokens: Int): String?
    }

    fun maxOutputTokens(outputTokenMax: Int?): Int = outputTokenMax ?: 4_096

    fun usable(
        reservedConfig: Int?,
        context: Int,
        inputLimit: Int?,
        outputTokenMax: Int?,
    ): Int {
        if (context == 0) return 0
        // One definition of the reserve, shared with the overflow check below.
        // These used to disagree: compaction reserved the configured 20k while
        // the overflow check reserved only the reply cap, so the two reported
        // different limits for the same model.
        val reserved = OcContextBudget.outputReserve(context, maxOutputTokens(outputTokenMax), reservedConfig)
        return if (inputLimit != null) maxOf(0, inputLimit - reserved)
        else maxOf(0, context - reserved)
    }

    fun isOverflow(
        autoEnabled: Boolean,
        tokens: JSONObject?,
        context: Int,
        inputLimit: Int?,
        outputTokenMax: Int?,
        reservedConfig: Int?,
    ): Boolean {
        if (!autoEnabled) return false
        if (context == 0) return false
        val total = tokens?.optInt("total", 0) ?: 0
        val count = if (total > 0) total
        else {
            val cache = tokens?.optJSONObject("cache") ?: JSONObject()
            (tokens?.optInt("input", 0) ?: 0) + (tokens?.optInt("output", 0) ?: 0) +
                cache.optInt("read", 0) + cache.optInt("write", 0)
        }
        return count >= usable(reservedConfig, context, inputLimit, outputTokenMax)
    }

    private fun truncate(value: String): String =
        if (value.length <= TOOL_OUTPUT_MAX_CHARS) value
        else value.slice(0 until TOOL_OUTPUT_MAX_CHARS) + "\n[truncated]"

    fun serialize(message: JSONObject): String {
        return when (message.optString("role")) {
            "user" -> {
                val parts = OcMessage.parts(message)
                val text = StringBuilder()
                val files = mutableListOf<String>()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    when (part.optString("type")) {
                        "text" -> if (!part.optBoolean("ignored") && part.optString("text").isNotEmpty()) {
                            if (text.isNotEmpty()) text.append("\n")
                            text.append(part.optString("text"))
                        }
                        "file" -> files.add("[Attached ${part.optString("mime")}: ${part.optString("filename", "file")}]")
                    }
                }
                (listOfNotNull(if (text.isNotEmpty()) "[User]: $text" else null) + files).joinToString("\n")
            }
            "assistant" -> {
                val parts = OcMessage.parts(message)
                val out = mutableListOf<String>()
                for (i in 0 until parts.length()) {
                    val part = parts.getJSONObject(i)
                    when (part.optString("type")) {
                        "text" -> if (part.optString("text").isNotEmpty()) out.add("[Assistant]: ${part.optString("text")}")
                        "reasoning" -> if (part.optString("text").isNotEmpty()) out.add("[Assistant reasoning]: ${part.optString("text")}")
                        "tool" -> {
                            val state = part.optJSONObject("state") ?: JSONObject()
                            val call = "[Assistant tool call]: ${part.optString("tool")}(${state.optJSONObject("input")})"
                            when (state.optString("status")) {
                                "completed" -> {
                                    val compactedAt = if (state.has("compactedAt")) state.optLong("compactedAt") else 0
                                    val output = if (compactedAt > 0) "[Old tool result content cleared]"
                                    else truncate(state.optString("output"))
                                    out.add(call)
                                    out.add("[Tool result]: $output")
                                }
                                "error" -> {
                                    out.add(call)
                                    out.add("[Tool error]: ${state.optString("error")}")
                                }
                                else -> out.add(call)
                            }
                        }
                    }
                }
                out.joinToString("\n")
            }
            else -> ""
        }
    }

    fun buildPrompt(previousSummary: String?, context: List<String>): String {
        val conversation = "Here is the conversation so far:\n\n<conversation>\n${context.joinToString("\n\n")}\n</conversation>"
        if (previousSummary == null) {
            return listOf(
                conversation,
                "Create a new anchored summary from the conversation history in the <conversation> tags above so another coding agent can continue the work.",
                SUMMARY_TEMPLATE,
            ).joinToString("\n\n")
        }
        return listOf(
            conversation,
            "Here is the summary of the conversation before the <conversation> above:\n\n<prior-summary>\n$previousSummary\n</prior-summary>",
            SUMMARY_UPDATE_INSTRUCTIONS,
            SUMMARY_TEMPLATE,
        ).joinToString("\n\n")
    }

    private data class CompletedCompaction(val userIndex: Int, val assistantIndex: Int, val summary: String?)

    private fun completedCompactions(messages: List<JSONObject>): List<CompletedCompaction> {
        val users = mutableMapOf<String, Int>()
        for (i in messages.indices) {
            val msg = messages[i]
            if (!OcMessage.isUser(msg)) continue
            if (!OcMessage.hasCompaction(msg)) continue
            users[msg.optString("id")] = i
        }
        val result = mutableListOf<CompletedCompaction>()
        for ((assistantIndex, msg) in messages.withIndex()) {
            if (!OcMessage.isAssistant(msg)) continue
            if (!msg.optBoolean("summary") || !msg.has("finish") || msg.has("error")) continue
            val userIndex = users[msg.optString("parentID")] ?: continue
            result.add(CompletedCompaction(userIndex, assistantIndex, OcMessage.summaryText(msg)))
        }
        return result
    }

    private fun turns(messages: List<JSONObject>): List<Pair<Int, Int>> {
        val starts = mutableListOf<Int>()
        for (i in messages.indices) {
            val msg = messages[i]
            if (!OcMessage.isUser(msg)) continue
            if (OcMessage.hasCompaction(msg)) continue
            starts.add(i)
        }
        val result = mutableListOf<Pair<Int, Int>>()
        for (i in starts.indices) {
            val end = if (i + 1 < starts.size) starts[i + 1] else messages.size
            result.add(starts[i] to end)
        }
        return result
    }

    fun select(
        messages: List<JSONObject>,
        cfg: JSONObject?,
        context: Int,
        inputLimit: Int?,
        outputTokenMax: Int?,
        reservedConfig: Int?,
    ): Pair<List<JSONObject>, String?> {
        val tailTurns = cfg?.let { if (it.has("tail_turns")) it.optInt("tail_turns") else null }
        if (tailTurns != null && tailTurns <= 0) return messages to null
        val budget = cfg?.let { if (it.has("preserve_recent_tokens")) it.optInt("preserve_recent_tokens") else null }
            ?: minOf(MAX_PRESERVE_RECENT_TOKENS, maxOf(MIN_PRESERVE_RECENT_TOKENS, (usable(reservedConfig, context, inputLimit, outputTokenMax) * 0.25).toInt()))
        val all = turns(messages)
        if (all.isEmpty()) return messages to null
        val recent = if (tailTurns == null) all else all.takeLast(tailTurns)
        var total = 0
        var keep: Int? = null
        for (i in recent.indices.reversed()) {
            val (start, end) = recent[i]
            val slice = messages.subList(start, end)
            val size = slice.sumOf { OcMessage.estimateTokens(serialize(it)) }
            if (total + size <= budget) {
                total += size
                keep = start
                continue
            }
            break
        }
        if (keep == null || keep == 0) return messages to null
        return messages.subList(0, keep) to messages[keep].optString("id")
    }

    fun compactIfNeeded(
        messages: List<JSONObject>,
        systemText: String,
        toolsJson: String,
        cfg: JSONObject?,
        autoEnabled: Boolean,
        context: Int,
        inputLimit: Int?,
        outputTokenMax: Int?,
        reservedConfig: Int?,
    ): Boolean {
        if (!autoEnabled || context == 0) return false
        val output = maxOutputTokens(outputTokenMax)
        val buffer = cfg?.optInt("reserved", COMPACTION_BUFFER) ?: COMPACTION_BUFFER
        val estimate = OcMessage.estimateTokens(systemText + toolsJson) +
            messages.sumOf { OcMessage.estimateTokens(serialize(it)) }
        val reserved = OcContextBudget.outputReserve(context, output, buffer)
        return estimate > maxOf(0, context - reserved)
    }

    /**
     * Auto-compaction trigger: only when the request genuinely cannot fit and
     * only once it is truly near the hard limit (~90% of the window).
     *
     * [compactIfNeeded] starts compaction at `context - reserve` (roughly 75%
     * of a default window), which for small local models fires on almost every
     * turn and costs an extra model call every time. Auto-compaction should be
     * a last resort: the conversation is compressed only when it really will
     * not fit, not preemptively. On-demand [compactNow] still uses
     * [compactIfNeeded] directly.
     */
    fun autoShouldCompact(
        messages: List<JSONObject>,
        systemText: String,
        toolsJson: String,
        cfg: JSONObject?,
        context: Int,
        inputLimit: Int?,
        outputTokenMax: Int?,
        reservedConfig: Int?,
    ): Boolean {
        if (context == 0) return false
        val output = maxOutputTokens(outputTokenMax)
        val reserved = OcContextBudget.outputReserve(context, output, reservedConfig ?: COMPACTION_BUFFER)
        val usable = maxOf(0, context - reserved)
        val estimate = OcMessage.estimateTokens(systemText + toolsJson) +
            messages.sumOf { OcMessage.estimateTokens(serialize(it)) }
        return estimate > usable && estimate > (context * 0.90).toInt()
    }

    fun prune(store: OcSessionStore, sessionId: String, enabled: Boolean) {
        if (!enabled) return
        val session = store.load(sessionId) ?: return
        val msgs = store.messages(session)
        var total = 0
        var pruned = 0
        val toPrune = mutableListOf<Pair<Int, JSONObject>>()
        var turnCount = 0
        loop@ for (msgIndex in msgs.indices.reversed()) {
            val msg = msgs[msgIndex]
            if (OcMessage.isUser(msg)) turnCount++
            if (turnCount < 2) continue
            if (OcMessage.isAssistant(msg) && msg.optBoolean("summary")) break@loop
            val parts = OcMessage.parts(msg)
            for (partIndex in parts.length() - 1 downTo 0) {
                val part = parts.getJSONObject(partIndex)
                if (part.optString("type") != "tool") continue
                val state = part.optJSONObject("state") ?: continue
                if (state.optString("status") != "completed") continue
                if (PRUNE_PROTECTED_TOOLS.contains(part.optString("tool"))) continue
                if (state.optLong("compactedAt", 0) > 0) break@loop
                val estimate = OcMessage.estimateTokens(state.optString("output"))
                total += estimate
                if (total <= PRUNE_PROTECT) continue
                pruned += estimate
                toPrune.add(msgIndex to part)
            }
        }
        if (pruned > PRUNE_MINIMUM) {
            for ((msgIndex, part) in toPrune) {
                val state = part.optJSONObject("state") ?: continue
                state.put("compactedAt", System.currentTimeMillis())
                val msg = msgs[msgIndex]
                val parts = OcMessage.parts(msg)
                val partId = part.optString("id")
                for (j in 0 until parts.length()) {
                    val existing = parts.optJSONObject(j)
                    if (existing != null && existing.optString("id") == partId) {
                        parts.put(j, part)
                        break
                    }
                }
                store.updateMessage(session, msg)
            }
        }
    }

    suspend fun compact(
        sessionId: String,
        session: OcSessionStore.Session,
        store: OcSessionStore,
        modelInfo: JSONObject,
        overflow: Boolean?,
        llm: Llm,
        agentModelOverride: JSONObject? = null,
    ): String? {
        val msgs = store.messages(session)
        val parent = msgs.lastOrNull { OcMessage.isUser(it) } ?: return null
        val existingCompaction = OcMessage.compactionPart(parent)
        var history = msgs
        if (existingCompaction != null && msgs.lastOrNull()?.optString("id") == parent.optString("id")) {
            history = msgs.subList(0, msgs.size - 1)
        }
        val prior = completedCompactions(history)
        val hidden = prior.flatMap { listOf(it.userIndex, it.assistantIndex) }.toSet()
        val previousSummary = prior.lastOrNull()?.summary
        val visible = history.filterIndexed { index, _ -> !hidden.contains(index) }
        val context = modelInfo.optJSONObject("limit")?.optInt("context", 0) ?: 0
        val inputLimit = modelInfo.optJSONObject("limit")?.let { if (it.has("input")) it.optInt("input") else null }
        val outputTokenMax = modelInfo.optJSONObject("limit")?.optInt("output", 4_096)
        val cfg = JSONObject()
        val (head, _) = select(visible, cfg, context, inputLimit, outputTokenMax, null)
        val conversation = head.map { serialize(it) }.filter { it.isNotEmpty() }.joinToString("\n\n")
        val prompt = buildPrompt(previousSummary, listOf(conversation))
        val summary = llm.generate(prompt, SUMMARY_OUTPUT_TOKENS)
        if (summary.isNullOrBlank()) return null

        val assistant = OcMessage.assistantMessage(
            sessionId, "compaction", agentModelOverride ?: modelInfo,
            parentID = parent.optString("id"), summary = true,
        )
        assistant.put("finish", "stop")
        OcMessage.addPart(assistant, OcMessage.textPart(summary))
        store.updateMessage(session, assistant)

        if (existingCompaction != null) {
            existingCompaction.put("summary", summary)
            store.updatePart(session, parent.optString("id"), existingCompaction)
        } else {
            val compactionPart = OcMessage.compactionPart(auto = true, overflow = overflow)
            compactionPart.put("summary", summary)
            store.updatePart(session, parent.optString("id"), compactionPart)
        }

        val continueMsg = OcMessage.userMessage(sessionId, parent.optString("agent", "build"), modelInfo)
        val text =
            (if (overflow == true)
                "The previous request exceeded the provider's size limit due to large media attachments. The conversation was compacted and media files were removed from context. If the user was asking about attached images or files, explain that the attachments were too large to process and suggest they try again with smaller or fewer files.\n\n"
            else "") +
                "Continue if you have next steps, or stop and ask for clarification if you are unsure how to proceed."
        OcMessage.addPart(continueMsg, OcMessage.textPart(text, synthetic = true))
        store.updateMessage(session, continueMsg)
        return summary
    }
}
