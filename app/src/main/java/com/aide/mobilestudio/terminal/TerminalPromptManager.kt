package com.aide.mobilestudio.terminal

import java.io.File

/**
 * Manages dynamic terminal prompt with working directory display.
 * Format: ~/MobileStudio/KotlinApp >
 */
class TerminalPromptManager {

    private var homeDir: String = "/data/data/com.aide.mobilestudio/files"
    private var cwd: String = homeDir

    fun setHomeDir(path: String) {
        homeDir = path
    }

    fun setCwd(path: String) {
        cwd = path
    }

    fun getCwd(): String = cwd

    /**
     * Format the prompt string with path abbreviation.
     * Returns: "~/MobileStudio/KotlinApp >"
     */
    fun formatPrompt(): String = formatPrompt(Int.MAX_VALUE)

    /**
     * Format the prompt the way a normal terminal does and guarantee it fits.
     *
     * Rendering rules:
     * - "~" when the working directory IS home or under home, otherwise the
     *   ABSOLUTE path (no middle abbreviation): /sdcard/Download renders as
     *   "/sdcard/Download >".
     * - The prompt is drawn on a monospace grid, so [maxColumns] is measured
     *   in characters (columns), not pixels.
     * - When the rendered prompt would be wider than [maxColumns] columns,
     *   the FRONT of the path is truncated with "..." keeping the tail, e.g.
     *   ".../java/com/aide/mobilestudio >". The result never exceeds
     *   [maxColumns] characters.
     */
    fun formatPrompt(maxColumns: Int): String {
        if (maxColumns <= 0) return ""
        val path = displayPathFor(cwd, homeDir)
        val prompt = path + PROMPT_SUFFIX
        if (prompt.length <= maxColumns) return prompt

        // Room left for the tail of the path once "..." and the suffix fit.
        val keep = maxColumns - ELLIPSIS.length - PROMPT_SUFFIX.length
        return if (keep <= 0) {
            // Degenerate budget: cannot even fit "... >", keep the tail only.
            prompt.takeLast(maxColumns)
        } else {
            // We only get here when prompt.length > maxColumns, hence
            // path.length > keep and the substring is always valid.
            ELLIPSIS + path.substring(path.length - keep) + PROMPT_SUFFIX
        }
    }

    /**
     * Full display path for the prompt: "~" for home itself, "~/..." under
     * home, otherwise the absolute path unchanged.
     */
    private fun displayPathFor(path: String, home: String): String {
        // Normalize path
        val normalizedPath = if (path.endsWith("/") && path.length > 1) path.substring(0, path.length - 1) else path
        val normalizedHome = if (home.endsWith("/") && home.length > 1) home.substring(0, home.length - 1) else home

        // Replace home with ~
        return if (normalizedPath == normalizedHome) {
            "~"
        } else if (normalizedPath.startsWith("$normalizedHome/")) {
            "~" + normalizedPath.substring(normalizedHome.length)
        } else {
            normalizedPath
        }
    }

    /**
     * Abbreviate a path for display.
     * - Replaces homeDir with ~
     * - If the path is very long, keeps the first component (~) and the last two/three components.
     */
    fun abbreviatePath(path: String, home: String = homeDir): String {
        val displayPath = displayPathFor(path, home)

        // If short enough (e.g. < 40 chars), return as-is
        if (displayPath.length <= 40) return displayPath

        // Tokenize
        val parts = displayPath.split("/").filter { it.isNotEmpty() }
        if (parts.size <= 4) return displayPath

        // Abbreviate middle: e.g. ~/A/B/C/D -> ~/A/.../C/D
        val first = parts[0]
        val lastTwo = parts.takeLast(2).joinToString("/")
        
        return "$first/.../$lastTwo"
    }

    /**
     * Try to detect and update cwd from a command.
     * Returns true if cwd was updated.
     */
    fun updateFromCommand(command: String): Boolean {
        val trimmed = command.trim()
        val parts = trimmed.split("\\s+".toRegex())
        if (parts.isEmpty()) return false

        val cmd = parts[0]
        return when (cmd) {
            "cd" -> {
                val target = parts.getOrElse(1) { "~" }
                val newCwd = resolveCwd(target)
                if (newCwd != null) {
                    cwd = newCwd
                    true
                } else false
            }
            "pushd" -> {
                if (parts.size > 1) {
                    val newCwd = resolveCwd(parts[1])
                    if (newCwd != null) {
                        cwd = newCwd
                        true
                    } else false
                } else false
            }
            "popd" -> {
                // popd changes to previous dir in stack — we can't track the stack,
                // but at least try to go up one level
                val parent = File(cwd).parent
                if (parent != null) {
                    cwd = parent
                    true
                } else false
            }
            else -> false
        }
    }

    private fun resolveCwd(target: String): String? {
        val resolved = when {
            target == "~" || target.isEmpty() -> homeDir
            target.startsWith("~/") -> "$homeDir${target.removePrefix("~")}"
            target.startsWith("/") -> target
            target == ".." -> File(cwd).parent ?: cwd
            target == "." -> cwd
            else -> "$cwd/$target"
        }
        return resolved
    }

    private companion object {
        /** The prompt always ends with this marker for the user to type after. */
        const val PROMPT_SUFFIX = " >"

        /** Marker for the front-truncated part of a too-wide path. */
        const val ELLIPSIS = "..."
    }
}
