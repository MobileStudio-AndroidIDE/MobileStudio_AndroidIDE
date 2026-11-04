package com.aide.mobilestudio.ai.opencode.session

import android.content.Context
import com.aide.mobilestudio.ai.opencode.config.OpencodeConfig
import com.aide.mobilestudio.ai.opencode.tool.OcGlob
import java.io.File

class OcInstructions(private val context: Context) {

    fun collect(config: OpencodeConfig): List<String> {
        val result = mutableListOf<String>()
        for (candidate in DEFAULT_FILES) {
            val file = File(config.worktree, candidate)
            if (file.isFile) {
                val text = runCatching { file.readText() }.getOrNull()
                if (!text.isNullOrBlank()) result.add(text)
            }
        }
        for (pattern in config.instructions) {
            val resolved = resolve(pattern, config)
            for (file in resolved) {
                val text = runCatching { file.readText() }.getOrNull()
                if (!text.isNullOrBlank()) result.add(text)
            }
        }
        return result
    }

    private fun resolve(pattern: String, config: OpencodeConfig): List<File> {
        val cleaned = pattern.trim()
        if (cleaned.isEmpty()) return emptyList()
        if (!cleaned.contains("*") && !cleaned.contains("?")) {
            val file = if (File(cleaned).isAbsolute) File(cleaned) else File(config.worktree, cleaned)
            return if (file.isFile) listOf(file) else emptyList()
        }
        val base = config.worktree
        val results = mutableListOf<File>()
        base.walkTopDown().maxDepth(8).forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeToOrNull(base)?.path ?: return@forEach
            if (OcGlob.match(relative, cleaned)) results.add(file)
        }
        return results.take(20)
    }

    companion object {
        val DEFAULT_FILES = listOf("AGENTS.md", "CLAUDE.md")
    }
}
