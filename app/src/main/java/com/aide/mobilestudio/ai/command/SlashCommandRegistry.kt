package com.aide.mobilestudio.ai.command

/**
 * Local slash-command table used by the full-screen
 * [com.aide.mobilestudio.ai.ui.AiWorkspaceActivity].
 *
 * The table is pure data + pure functions (no Android classes, no engine
 * references) so it can be unit tested on the JVM. Each command carries a
 * [SlashCommandAction] token; UI code binds the token to an actual handler.
 * Template commands coming from [com.aide.mobilestudio.ai.opencode.command.OcCommandRegistry]
 * (init, review, skills, MCP prompts...) keep flowing through the engine; only
 * the local actions below are handled by the UI.
 */
enum class SlashCommandAction {
    HELP,
    CLEAR,
    NEW_SESSION,
    SHOW_SESSIONS,
    SHOW_MODELS,
    SHOW_TOOLS,
    TOGGLE_PLAN,
    UNDO,
    REDO,
    SHOW_CHANGES,
    STATUS,
    AI_EXPLAIN,
    AI_REFACTOR,
    AI_GENERATE,
    AI_TEST,
    AI_DOC,
}

/**
 * @param name       canonical name without the leading slash
 * @param action     handler token the UI dispatches on
 * @param needsArgument true when the command requires trailing text (UI inserts
 *   it into the input instead of executing immediately)
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val action: SlashCommandAction,
    val aliases: List<String> = emptyList(),
    val needsArgument: Boolean = false,
)

/** Result of parsing a raw input line. */
sealed class SlashInvocation {
    /** Input is not a command (no leading slash) - send it to the model. */
    data object NotACommand : SlashInvocation()

    /** A known local command with its (possibly empty) argument. */
    data class Known(val command: SlashCommand, val argument: String) : SlashInvocation()

    /** A slash token that matches nothing - surfaces show an inline notice. */
    data class Unknown(val name: String) : SlashInvocation()
}

class SlashCommandRegistry(commands: List<SlashCommand> = defaultCommands()) {

    private val commands: List<SlashCommand> = commands

    fun all(): List<SlashCommand> = commands

    /** Case-insensitive lookup by name or alias. */
    fun find(name: String): SlashCommand? {
        val key = name.trim().removePrefix("/")
        if (key.isEmpty()) return null
        return commands.firstOrNull {
            it.name.equals(key, ignoreCase = true) ||
                it.aliases.any { alias -> alias.equals(key, ignoreCase = true) }
        }
    }

    /**
     * Parses a raw input line. Never throws; anything starting with "/" that
     * does not resolve becomes [SlashInvocation.Unknown].
     */
    fun parse(raw: String): SlashInvocation {
        val text = raw.trim()
        if (!text.startsWith("/") || text.length < 2) return SlashInvocation.NotACommand
        // `/ model` is a common typo, and templates insert `/name ` with a
        // trailing space. Whitespace around the name is not part of it.
        val body = text.substring(1).trimStart()
        val name = body.takeWhile { !it.isWhitespace() }
        if (name.isEmpty()) return SlashInvocation.NotACommand
        // A message that starts with an absolute path (`/data/data/...`) is
        // prose, not a command. Treating every slash token as a command both
        // swallowed the message and showed "unknown command" for a file path.
        if (looksLikePath(name)) return SlashInvocation.NotACommand
        val command = find(name) ?: return SlashInvocation.Unknown(name)
        val argument = body.drop(name.length).trim()
        return SlashInvocation.Known(command, argument)
    }

    /** True when a slash token is more plausibly a filesystem path. */
    private fun looksLikePath(token: String): Boolean {
        val name = token.trim()
        if (name.isEmpty()) return false
        if (name.contains('/') || name.contains('\\')) return true
        if (name.startsWith("~") || name == "." || name == "..") return true
        // One-segment absolute paths are still paths. Keep the list to roots
        // that exist on Android/Linux so `/modle` remains a typo, not a file.
        return name.lowercase() in ABSOLUTE_PATH_ROOTS
    }

    /**
     * Suggestions for the "/" popup. Matches are tiered so a strong match
     * never gets diluted by a weak one: a name/alias PREFIX wins outright,
     * otherwise a name SUBSTRING, otherwise a mention in the description.
     * Each tier preserves the declared order, and the tiers are matched
     * case-insensitively.
     */
    fun filter(query: String): List<SlashCommand> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return commands
        val byPrefix = commands.filter { cmd ->
            cmd.name.lowercase().startsWith(q) || cmd.aliases.any { it.lowercase().startsWith(q) }
        }
        if (byPrefix.isNotEmpty()) return byPrefix
        val byName = commands.filter { cmd -> cmd.name.lowercase().contains(q) }
        if (byName.isNotEmpty()) return byName
        return commands.filter { cmd -> cmd.description.lowercase().contains(q) }
    }

    companion object {
        /**
         * Single-segment absolute paths that must go to the model, not the
         * command table. Multi-segment paths are caught by the slash check.
         */
        private val ABSOLUTE_PATH_ROOTS = setOf(
            "data", "sdcard", "storage", "system", "vendor", "product", "odm",
            "proc", "dev", "sys", "etc", "mnt", "tmp", "var", "usr", "home", "root",
        )

        /** The minimum command set both surfaces ship with. */
        fun defaultCommands(): List<SlashCommand> = listOf(
            SlashCommand("help", "list available commands", SlashCommandAction.HELP),
            SlashCommand("clear", "clear the conversation (asks before discarding)", SlashCommandAction.CLEAR),
            SlashCommand("new", "start a new session", SlashCommandAction.NEW_SESSION),
            SlashCommand("sessions", "open the session list", SlashCommandAction.SHOW_SESSIONS),
            SlashCommand("model", "choose a model", SlashCommandAction.SHOW_MODELS, aliases = listOf("models")),
            SlashCommand("tools", "list tool names, or show one tool's usage (/tools read)", SlashCommandAction.SHOW_TOOLS),
            SlashCommand("plan", "toggle plan (read-only) mode", SlashCommandAction.TOGGLE_PLAN),
            SlashCommand("undo", "undo the agent's last file changes", SlashCommandAction.UNDO),
            SlashCommand("redo", "re-apply the last undone change", SlashCommandAction.REDO),
            SlashCommand("files", "show files changed in this session", SlashCommandAction.SHOW_CHANGES),
            SlashCommand("status", "show project / model / session status", SlashCommandAction.STATUS),
            SlashCommand("explain", "ask AI to explain selected code", SlashCommandAction.AI_EXPLAIN),
            SlashCommand("refactor", "ask AI to refactor selected code", SlashCommandAction.AI_REFACTOR),
            SlashCommand("generate", "ask AI to generate code", SlashCommandAction.AI_GENERATE),
            SlashCommand("test", "ask AI to generate tests", SlashCommandAction.AI_TEST),
            SlashCommand("doc", "ask AI to generate documentation", SlashCommandAction.AI_DOC),
        )

        /** One-line-per-command help text rendered by /help. */
        fun helpText(commands: List<SlashCommand> = defaultCommands()): String =
            commands.joinToString("\n") { "/${it.name} — ${it.description}" }
    }
}
