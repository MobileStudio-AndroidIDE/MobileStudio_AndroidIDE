package com.aide.mobilestudio.ai.agent

import java.io.File

/**
 * Project file listing used by the @file mention picker. Applies the same
 * ignore rules the AI workspace search and file tree use (build/, .git/,
 * .gradle/, node_modules/, ...), so suggested paths never point into build
 * output. Pure java.io: unit-testable on the JVM.
 */
object ProjectFiles {

    /** Directory names excluded from listings. */
    val IGNORED_DIRS = setOf(
        ".git", ".gradle", ".idea", ".cxx", ".kotlin", ".opencode",
        "build", "out", "outputs", "node_modules",
    )

    /** Hard safety cap while walking very large projects. */
    private const val MAX_WALK = 5000

    const val DEFAULT_LIMIT = 500

    /** A directory is ignored when its name is hidden or in [IGNORED_DIRS]. */
    fun isIgnoredDir(name: String): Boolean =
        name.isEmpty() || name.startsWith(".") || name in IGNORED_DIRS

    /** A relative path is ignored when ANY of its directories is ignored. */
    fun isIgnored(relativePath: String): Boolean {
        val segments = relativePath.split('/')
        return segments.dropLast(1).any { isIgnoredDir(it) }
    }

    /**
     * Relative file paths under [root], shallowest first and alphabetical
     * within a depth, honoring the ignore rules. Dotfiles (e.g. .gitignore)
     * are listed; ignored directories are pruned entirely.
     */
    fun list(root: File, limit: Int = DEFAULT_LIMIT): List<String> {
        if (!root.isDirectory) return emptyList()
        val collected = mutableListOf<Pair<Int, String>>()
        root.walkTopDown().onEnter { dir -> dir == root || !isIgnoredDir(dir.name) }.forEach { f ->
            if (collected.size >= MAX_WALK) return@forEach
            if (!f.isFile) return@forEach
            val relative = f.relativeToOrNull(root)?.path ?: return@forEach
            if (isIgnored(relative)) return@forEach
            collected.add(relative.count { it == '/' } to relative)
        }
        return collected
            .sortedWith(compareBy({ it.first }, { it.second }))
            .take(limit)
            .map { it.second }
    }

    /**
     * Filter for the @-picker. Ranked by match strength - a FILENAME prefix
     * beats a filename substring, which beats a hit anywhere else in the
     * path - and within a rank the shallower path wins (root-level files are
     * usually what the user meant). Ties keep the caller's order.
     */
    fun filter(paths: List<String>, query: String, limit: Int = 30): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return paths.take(limit)
        return paths
            .mapIndexedNotNull { index, path ->
                val name = path.substringAfterLast('/').lowercase()
                val rank = when {
                    name.startsWith(q) -> 0
                    name.contains(q) -> 1
                    path.lowercase().contains(q) -> 2
                    else -> return@mapIndexedNotNull null
                }
                Triple(rank, path.count { it == '/' }, index) to path
            }
            .sortedWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
            .map { it.second }
            .take(limit)
    }
}
