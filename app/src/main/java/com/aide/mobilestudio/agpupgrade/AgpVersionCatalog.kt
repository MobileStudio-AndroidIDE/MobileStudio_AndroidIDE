package com.aide.mobilestudio.agpupgrade

import java.io.File
import java.util.LinkedHashMap
import java.util.regex.Pattern

/**
 * libs.versions.toml 자동 업데이트 (TOML 파싱/쓰기).
 * Android Gradle Plugin 버전 카탈로그 관리.
 */
class AgpVersionCatalog(private val tomlFile: File) {

    data class VersionEntry(
        val key: String,
        val value: String,
        val section: Section,
        val comment: String? = null,
    )

    enum class Section {
        VERSIONS, LIBRARIES, PLUGINS, BUNDLES
    }

    data class Catalog(
        val versions: LinkedHashMap<String, VersionEntry> = LinkedHashMap(),
        val libraries: LinkedHashMap<String, LibraryEntry> = LinkedHashMap(),
        val plugins: LinkedHashMap<String, PluginEntry> = LinkedHashMap(),
        val bundles: LinkedHashMap<String, BundleEntry> = LinkedHashMap(),
    )

    data class LibraryEntry(
        val name: String,
        val module: String,
        val versionRef: String?,
        val version: String?,
        val group: String?,
    )

    data class PluginEntry(
        val id: String,
        val versionRef: String?,
        val version: String?,
        val apply: Boolean = false,
    )

    data class BundleEntry(
        val name: String,
        val modules: List<String>,
    )

    private var catalog: Catalog? = null
    private var rawContent: String = ""

    /**
     * TOML 파일 파싱.
     */
    fun parse(): Catalog {
        if (!tomlFile.exists()) {
            return Catalog()
        }

        rawContent = tomlFile.readText()
        val lines = rawContent.lines().toList()
        val catalog = Catalog()
        var currentSection: Section? = null

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()

            // 섹션 헤더 감지
            when (line) {
                "[versions]" -> currentSection = Section.VERSIONS
                "[libraries]" -> currentSection = Section.LIBRARIES
                "[plugins]" -> currentSection = Section.PLUGINS
                "[bundles]" -> currentSection = Section.BUNDLES
                else -> {
                    if (line.startsWith("#") || line.isBlank()) {
                        // 주석 또는 빈 줄
                    } else if (line.contains("=")) {
                        parseEntry(line, currentSection, catalog)
                    }
                }
            }
            i++
        }

        this.catalog = catalog
        return catalog
    }

    private fun parseEntry(line: String, section: Section?, catalog: Catalog) {
        val parts = line.split("=", limit = 2)
        if (parts.size != 2) return

        val key = parts[0].trim()
        var value = parts[1].trim()

        // 따옴표 제거
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length - 1)
        }

        when (section) {
            Section.VERSIONS -> {
                catalog.versions[key] = VersionEntry(key, value, Section.VERSIONS)
            }
            Section.LIBRARIES -> {
                // library 형식: name = { module = "x", version.ref = "y" } 또는 name = "group:artifact:version"
                if (value.startsWith("{")) {
                    // 인라인 테이블 파싱
                    val module = extractTomlValue(value, "module")
                    val versionRef = extractTomlValue(value, "version.ref")
                    val version = extractTomlValue(value, "version")
                    val group = extractTomlValue(value, "group")
                    catalog.libraries[key] = LibraryEntry(key, module ?: "", versionRef, version, group)
                } else {
                    // 단순 문자열
                    catalog.libraries[key] = LibraryEntry(key, value, null, null, null)
                }
            }
            Section.PLUGINS -> {
                // plugin 형식: id = "plugin.id", version.ref = "x" 또는 version = "x"
                val id = extractTomlValue(value, "id") ?: value
                val versionRef = extractTomlValue(value, "version.ref")
                val version = extractTomlValue(value, "version")
                val apply = value.contains("apply = true")
                catalog.plugins[key] = PluginEntry(id, versionRef, version, apply)
            }
            Section.BUNDLES -> {
                // bundle 형식: name = ["lib1", "lib2"]
                val modules = extractArray(value)
                catalog.bundles[key] = BundleEntry(key, modules)
            }
            null -> {}
        }
    }

    private fun extractTomlValue(toml: String, key: String): String? {
        val pattern = Pattern.compile("$key\\s*=\\s*\"([^\"]+)\"")
        val matcher = pattern.matcher(toml)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractArray(toml: String): List<String> {
        val result = mutableListOf<String>()
        val content = toml.trim()
        if (content.startsWith("[") && content.endsWith("]")) {
            val inner = content.substring(1, content.length - 1).trim()
            if (inner.isNotEmpty()) {
                inner.split(",").forEach { item ->
                    val trimmed = item.trim()
                    if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
                        result.add(trimmed.substring(1, trimmed.length - 1))
                    } else {
                        result.add(trimmed)
                    }
                }
            }
        }
        return result
    }

    /**
     * 업데이트된 버전으로 TOML 파일 작성.
     */
    fun write(catalog: Catalog): Boolean {
        if (!tomlFile.exists()) {
            return false
        }

        val sb = StringBuilder()
        sb.append("[versions]")
        sb.append("\n")
        catalog.versions.forEach { (key, entry) ->
            sb.append("$key = \"$entry.value\"")
            sb.append("\n")
        }
        sb.append("\n")

        sb.append("[libraries]")
        sb.append("\n")
        catalog.libraries.forEach { (key, entry) ->
            if (entry.versionRef != null) {
                sb.append("$key = { module = \"${entry.module}\", version.ref = \"${entry.versionRef}\" }")
            } else if (entry.version != null) {
                sb.append("$key = \"${entry.version}\"")
            } else {
                sb.append("$key = \"${entry.module}\"")
            }
            sb.append("\n")
        }
        sb.append("\n")

        sb.append("[plugins]")
        sb.append("\n")
        catalog.plugins.forEach { (key, entry) ->
            if (entry.versionRef != null) {
                sb.append("$key = { id = \"${entry.id}\", version.ref = \"${entry.versionRef}\" }")
            } else if (entry.version != null) {
                sb.append("$key = \"${entry.version}\"")
            } else {
                sb.append("$key = \"${entry.id}\"")
            }
            sb.append("\n")
        }
        sb.append("\n")

        sb.append("[bundles]")
        sb.append("\n")
        catalog.bundles.forEach { (key, entry) ->
            val modules = entry.modules.joinToString(", ") { "\"$it\"" }
            sb.append("$key = [$modules]")
            sb.append("\n")
        }

        tomlFile.writeText(sb.toString())
        return true
    }

    /**
     * 특정 버전 키 업데이트.
     */
    fun updateVersion(key: String, newValue: String): Boolean {
        if (!tomlFile.exists()) return false

        val content = tomlFile.readText()
        val lines = content.lines().toList()
        val updated = mutableListOf<String>()
        var found = false

        lines.forEach { line ->
            if (line.trim().startsWith("$key =") || line.trim().startsWith("""$key =""")) {
                val indent = line.takeWhile { it == ' ' || it == '\t' }
                updated.add("$indent$key = \"$newValue\"")
                found = true
            } else {
                updated.add(line)
            }
        }

        if (found) {
            tomlFile.writeText(updated.joinToString("\n"))
        }
        return found
    }
}