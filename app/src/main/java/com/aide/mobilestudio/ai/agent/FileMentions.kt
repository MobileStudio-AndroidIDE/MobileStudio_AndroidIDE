package com.aide.mobilestudio.ai.agent

import java.io.File

/** One resolved @path mention. */
class ResolvedMention(
    val mention: String,
    /** Absolute path of the resolved file, or null when the token matched nothing. */
    val path: String?,
    /** File content, or null when missing / skipped / unreadable. */
    val content: String?,
    val skipped: Boolean = false,
    val reason: String? = null,
) {
    val bytes: Int get() = content?.toByteArray()?.size ?: 0
}

/**
 * @file mention parsing and resolution for the prompt.
 *
 * Typing "@app/src/main/MainActivity.kt" in either chat surface attaches the
 * file's content to the next request as context. Unknown mentions are reported
 * (inline notice) but never abort the send. Pure java.io: unit-testable.
 */
object FileMentions {

    /**
     * A mention token: "@" followed by path-ish characters, NOT preceded by a
     * word character (so emails like me@example.com are ignored). A trailing
     * "." is sentence punctuation, not part of the path, and is trimmed.
     */
    private val MENTION = Regex("""(?<![\w@.])@([A-Za-z0-9][A-Za-z0-9_\-./+]*)""")

    /** Files larger than this are skipped with a note instead of attached. */
    const val MAX_FILE_BYTES = 64 * 1024

    /** Distinct mention tokens in message order. */
    fun extract(text: String): List<String> =
        MENTION.findAll(text)
            .map { it.groupValues[1].trimEnd('.') }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    /** Resolves each mention against [root] (relative) or as-is (absolute). */
    fun resolve(root: File, mentions: List<String>, maxFileBytes: Int = MAX_FILE_BYTES): List<ResolvedMention> =
        mentions.map { resolveOne(root, it, maxFileBytes) }

    private fun resolveOne(root: File, mention: String, maxFileBytes: Int): ResolvedMention {
        val raw = File(mention)
        val file = if (raw.isAbsolute) raw else File(root, mention)
        if (!file.isFile) return ResolvedMention(mention, null, null, reason = "not found")
        val size = runCatching { file.length() }.getOrDefault(0L)
        if (size > maxFileBytes) {
            return ResolvedMention(
                mention, file.absolutePath, null,
                skipped = true,
                reason = "skipped: ${size / 1024} KB exceeds the ${maxFileBytes / 1024} KB limit",
            )
        }
        val content = runCatching { file.readText() }.getOrNull()
            ?: return ResolvedMention(mention, file.absolutePath, null, skipped = true, reason = "unreadable (binary?)")
        return ResolvedMention(mention, file.absolutePath, content)
    }

    /**
     * The model-facing context block. Resolved files appear as fenced code,
     * unknown and skipped mentions are noted inline so the model understands
     * why content is absent.
     */
    fun formatContextBlock(resolved: List<ResolvedMention>): String {
        if (resolved.isEmpty()) return ""
        val sb = StringBuilder("---ATTACHED FILE CONTEXT---\n")
        for (mention in resolved) {
            when {
                mention.path == null ->
                    sb.append("@${mention.mention}: not found — ignored\n")
                mention.skipped ->
                    sb.append("@${mention.mention}: ${mention.reason}\n")
                else ->
                    sb.append("@${mention.mention}:\n```\n${mention.content}\n```\n")
            }
        }
        sb.append("---END ATTACHED FILE CONTEXT---")
        return sb.toString()
    }
}
