package com.aide.mobilestudio.ai.agent

/**
 * Pure formatting helpers for the two request-context affordances:
 * the /status system-style message and the compact context badge shown
 * above the input ("what will the model see"). No Android classes.
 */
object StatusLine {

    /**
     * Short system-style status message: project, open file, model, session.
     * Any part may be missing; it is simply omitted.
     */
    fun format(
        project: String?,
        openFile: String?,
        model: String?,
        sessionId: String?,
        agent: String? = null,
    ): String {
        val lines = listOfNotNull(
            "Project: ${project?.ifBlank { null } ?: "none"}",
            openFile?.takeIf { it.isNotBlank() }?.let { "Open file: $it" },
            "Model: ${model?.ifBlank { null } ?: "none"}",
            agent?.takeIf { it.isNotBlank() }?.let { "Agent: $it" },
            sessionId?.takeIf { it.isNotBlank() }?.let { "Session: $it" },
        )
        return lines.joinToString("\n")
    }
}

object PromptContextBadge {

    /** Rough token estimate: ~4 characters per token, as elsewhere in the app. */
    private const val CHARS_PER_TOKEN = 4

    /**
     * One compact line describing what the next request will carry:
     * the open editor file, the number of @file mentions, and an approximate
     * token size of the prompt text. Empty input with no file → "".
     */
    fun format(currentFile: String?, input: String, mentions: List<String> = FileMentions.extract(input)): String {
        val parts = mutableListOf<String>()
        if (!currentFile.isNullOrBlank()) parts.add("📄 " + currentFile.substringAfterLast('/'))
        if (mentions.isNotEmpty()) parts.add("@${mentions.size} file${if (mentions.size == 1) "" else "s"}")
        if (input.isNotEmpty()) {
            val tokens = (input.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
            parts.add("~$tokens tok")
        }
        return parts.joinToString(" · ")
    }
}
