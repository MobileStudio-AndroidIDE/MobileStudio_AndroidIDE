package com.aide.mobilestudio.ai.opencode.skill

import android.content.Context
import com.aide.mobilestudio.ai.opencode.config.OpencodeConfig
import java.io.File

class OcSkill(private val context: Context, private val configProvider: () -> OpencodeConfig) {

    data class Skill(
        val name: String,
        val description: String?,
        val slash: Boolean,
        val location: String,
        val content: String,
    )

    fun list(): List<Skill> {
        val config = configProvider()
        val dirs = mutableListOf<File>()
        dirs.add(File(config.worktree, ".opencode/skills"))
        for (path in config.skillPaths) {
            val dir = if (File(path).isAbsolute) File(path) else File(config.worktree, path)
            dirs.add(dir)
        }
        val skills = LinkedHashMap<String, Skill>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            for (skill in loadDirectory(dir)) {
                skills[skill.name] = skill
            }
        }
        return skills.values.toList()
    }

    fun get(name: String): Skill? = list().firstOrNull { it.name == name }

    private fun loadDirectory(directory: File): List<Skill> {
        val skills = mutableListOf<Skill>()
        val files = mutableListOf<File>()
        directory.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.extension == "md") {
                files.add(entry)
            } else if (entry.isDirectory) {
                val skillMd = File(entry, "SKILL.md")
                if (skillMd.isFile) files.add(skillMd)
            }
        }
        for (file in files.sortedBy { it.name }) {
            val content = runCatching { file.readText() }.getOrNull() ?: continue
            val parsed = parseFrontmatter(content) ?: continue
            val name = parsed.name ?: run {
                if (file.parentFile == directory) file.nameWithoutExtension else null
            } ?: continue
            skills.add(Skill(name, parsed.description, parsed.slash, file.absolutePath, parsed.content))
        }
        return skills
    }

    private data class Parsed(val name: String?, val description: String?, val slash: Boolean, val content: String)

    private fun parseFrontmatter(text: String): Parsed? {
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", 3)
        if (end < 0) return null
        val frontmatterText = text.substring(3, end).trim()
        val content = text.substring(end + 4).trimStart('\n', '-').trim()
        var name: String? = null
        var description: String? = null
        var slash = false
        for (line in frontmatterText.split("\n")) {
            val idx = line.indexOf(':')
            if (idx < 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim().trim('"', '\'')
            when (key) {
                "name" -> name = value
                "description" -> description = value
                "slash" -> slash = value.equals("true", ignoreCase = true)
            }
        }
        if (name == null && description == null) return null
        return Parsed(name, description, slash, content)
    }

    fun availableFor(skills: List<Skill>, denied: Set<String>): List<Skill> =
        skills.filter { !denied.contains(it.name) }

    fun fmt(list: List<Skill>, verbose: Boolean): String {
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        if (verbose) {
            sb.append("<available_skills>\n")
            for (skill in list) {
                sb.append("<skill>\n")
                sb.append("  <name>${skill.name}</name>\n")
                if (skill.description != null) sb.append("  <description>${skill.description}</description>\n")
                sb.append("  <location>${skill.location}</location>\n")
                sb.append("</skill>\n")
            }
            sb.append("</available_skills>")
        } else {
            sb.append("<available_skills>\n")
            for (skill in list) {
                sb.append("  <name>${skill.name}</name>")
                if (skill.description != null) sb.append(" — ${skill.description}")
                sb.append("\n")
            }
            sb.append("</available_skills>")
        }
        return sb.toString()
    }

    fun loadForTool(skill: Skill): String {
        val dir = File(skill.location).parentFile
        return if (dir == null || dir.path == "") {
            skill.content
        } else {
            listOf(
                skill.content,
                "",
                "Base directory for this skill: ${dir.absolutePath}",
                "Relative paths in this skill (e.g., scripts/, references/) are relative to this base directory.",
            ).joinToString("\n")
        }
    }
}
