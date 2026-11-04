package com.aide.mobilestudio.validation

import java.io.File

data class ParsedDependency(
    val kind: Kind,
    val raw: String,
    val projectPath: String? = null,
    val filePaths: List<String> = emptyList(),
    val coordinate: List<String> = emptyList(),
    val line: Int = 0
) {
    enum class Kind { COORDINATE, PROJECT, FILES, FILE_TREE }
}

data class IncludedModule(val path: String, val dir: File, val line: Int, val viaIncludeFlat: Boolean)

data class SettingsInfo(
    val file: File,
    val isKts: Boolean,
    val includes: List<IncludedModule>,
    val rootProjectName: String?,
    val rawText: String
)

data class ModuleBuildInfo(
    val dir: File,
    val buildFile: File,
    val isKts: Boolean,
    val pluginIds: List<String>,
    val usesAliasPlugins: Boolean,
    val hasAndroidBlock: Boolean,
    val androidProps: Map<String, String>,
    val composeEnabled: Boolean,
    val jvmTarget: String?,
    val javaCompat: String?,
    val dependencies: List<ParsedDependency>,
    val sourceSetSrcDirs: List<String>,
    val kotlinPluginText: Boolean,
    val androidPluginText: Boolean
) {
    fun intProp(name: String): Int? = androidProps[name]?.let { v ->
        v.removeSurrounding("\"").removeSurrounding("'").trim().toIntOrNull()
    }

    fun strProp(name: String): String? = androidProps[name]
        ?.removeSurrounding("\"")?.removeSurrounding("'")?.trim()?.takeIf { it.isNotEmpty() }
}

data class GradleProjectModel(
    val root: File,
    val settings: SettingsInfo?,
    val modules: List<ModuleBuildInfo>,
    val gradlePropertiesFile: File?
) {
    fun androidModules(): List<ModuleBuildInfo> =
        modules.filter { it.hasAndroidBlock || it.androidPluginText }

    fun moduleFor(file: File): ModuleBuildInfo? {
        var dir: File? = if (file.isDirectory) file else file.parentFile
        while (dir != null) {
            modules.firstOrNull { it.dir == dir }?.let { return it }
            if (dir == root) break
            dir = dir.parentFile
        }
        return null
    }
}

/**
 * Static scan of the Gradle project model: settings includes, build script
 * modules, android block properties, plugins and dependencies. Pure file/text
 * parsing - no Gradle is invoked (never disturb a running sync/build).
 */
object GradleProjectScanner {

    private val SKIP_DIRS = setOf(
        "build", ".gradle", ".git", ".idea", "node_modules", ".svn", "captures", "generated"
    )

    fun scan(root: File): GradleProjectModel {
        val settingsFile = listOf("settings.gradle.kts", "settings.gradle")
            .mapNotNull { name -> File(root, name).takeIf { it.isFile } }
            .firstOrNull()
        val settings = settingsFile?.let { parseSettings(it) }

        val moduleDirs = linkedSetOf<File>()
        moduleDirs.add(root)
        settings?.includes?.forEach { inc -> if (inc.dir.isDirectory) moduleDirs.add(inc.dir) }
        root.walkTopDown()
            .onEnter { dir -> dir.name !in SKIP_DIRS && !dir.name.startsWith(".") }
            .maxDepth(8)
            .filter { it.isDirectory }
            .forEach { dir ->
                if (listOf("build.gradle.kts", "build.gradle").any { File(dir, it).isFile }) {
                    moduleDirs.add(dir)
                }
            }

        val modules = moduleDirs.mapNotNull { dir ->
            val buildFile = listOf("build.gradle.kts", "build.gradle")
                .mapNotNull { name -> File(dir, name).takeIf { it.isFile } }
                .firstOrNull() ?: return@mapNotNull null
            parseBuildModule(dir, buildFile)
        }

        return GradleProjectModel(
            root = root,
            settings = settings,
            modules = modules,
            gradlePropertiesFile = File(root, "gradle.properties").takeIf { it.isFile }
        )
    }

    fun parseSettings(file: File): SettingsInfo? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val isKts = file.name.endsWith(".kts")
        val masked = GradleCodeScanner.maskComments(text)
        val includes = mutableListOf<IncludedModule>()

        // include(":a", ":b") / includeFlat(":sub")
        Regex("""(?<![\w.])include(?:Flat)?\s*\(([^)]*)\)""").findAll(masked).forEach { m ->
            val isFlat = masked.substring(m.range.first).startsWith("includeFlat")
            val args = m.groupValues[1]
            Regex("""["']([^"']+)["']""").findAll(args).forEach { sm ->
                val line = GradleCodeScanner.lineOfIndex(masked, m.range.first)
                addInclude(includes, sm.groupValues[1], isFlat, file.parentFile ?: File("."), line)
            }
        }
        // Groovy no-parens form: include ':app', ':lib'
        Regex("""(?m)^\s*(include(?:Flat)?)\s+(?!\()(.+)$""").findAll(masked).forEach { m ->
            val isFlat = m.groupValues[1].endsWith("Flat")
            val args = m.groupValues[2]
            Regex("""["']([^"']+)["']""").findAll(args).forEach { sm ->
                val line = GradleCodeScanner.lineOfIndex(masked, m.range.first)
                addInclude(includes, sm.groupValues[1], isFlat, file.parentFile ?: File("."), line)
            }
        }

        val rootName = Regex("""rootProject\.name\s*=\s*["']([^"']+)["']""")
            .find(masked)?.groupValues?.get(1)

        return SettingsInfo(
            file = file,
            isKts = isKts,
            includes = includes,
            rootProjectName = rootName,
            rawText = text
        )
    }

    private fun addInclude(
        out: MutableList<IncludedModule>,
        raw: String,
        flat: Boolean,
        root: File,
        line: Int
    ) {
        val dir = if (flat) {
            File(root, raw.trimStart('/'))
        } else {
            val clean = raw.removePrefix(":").removeSuffix(":")
            File(root, clean.replace(':', '/'))
        }
        if (out.any { it.path == raw }) return
        out.add(IncludedModule(raw, dir, line, flat))
    }

    fun parseBuildModule(dir: File, buildFile: File): ModuleBuildInfo? {
        val text = runCatching { buildFile.readText() }.getOrNull() ?: return null
        val isKts = buildFile.name.endsWith(".kts")
        val maskedComments = GradleCodeScanner.maskComments(text)
        val maskedAll = GradleCodeScanner.maskAll(text)

        val plugins = linkedSetOf<String>()
        var alias = false
        for (blockRange in GradleCodeScanner.findBlocks(maskedAll, "plugins")) {
            val body = maskedComments.substring(blockRange)
            Regex("""id\s*\(\s*["']([^"']+)["']""").findAll(body).forEach { plugins.add(it.groupValues[1]) }
            Regex("""id\s+["']([^"']+)["']""").findAll(body).forEach { plugins.add(it.groupValues[1]) }
            if (Regex("""alias\s*\(""").containsMatchIn(body)) alias = true
        }
        Regex("""apply\s*\(?\s*(?:plugin\s*:\s*)?["']([^"']+)["']""").findAll(maskedComments)
            .forEach { plugins.add(it.groupValues[1]) }

        val androidProps = linkedMapOf<String, String>()
        val androidBlocks = GradleCodeScanner.findBlocks(maskedAll, "android")
        val hasAndroidBlock = androidBlocks.isNotEmpty()
        for (blockRange in androidBlocks) {
            val body = maskedComments.substring(blockRange)
            collectProps(body, androidProps)
            val bodyMaskedAll = GradleCodeScanner.maskAll(body)
            for (bf in GradleCodeScanner.findBlocks(bodyMaskedAll, "buildFeatures")) {
                val bfBody = body.substring(bf)
                Regex("""compose\s*=\s*(true|false)""").find(bfBody)?.let {
                    androidProps["compose"] = it.groupValues[1]
                }
            }
        }

        val dependencies = mutableListOf<ParsedDependency>()
        for (blockRange in GradleCodeScanner.findBlocks(maskedAll, "dependencies")) {
            val body = maskedComments.substring(blockRange)
            collectDependencies(body, maskedComments, blockRange.first, dependencies)
        }

        val sourceSetSrcDirs = mutableListOf<String>()
        for (blockRange in GradleCodeScanner.findBlocks(maskedAll, "sourceSets")) {
            val body = maskedComments.substring(blockRange)
            Regex("""srcDirs?\s*(?:=\s*)?\(?\s*["']([^"']+)["']""")
                .findAll(body).forEach { sourceSetSrcDirs.add(it.groupValues[1]) }
            Regex("""srcDirs?\s*(?:=\s*)?\[(.*?)\]""").findAll(body).forEach { m ->
                Regex("""["']([^"']+)["']""").findAll(m.groupValues[1])
                    .forEach { sourceSetSrcDirs.add(it.groupValues[1]) }
            }
        }

        val jvmTarget = Regex("""jvmTarget\s*=\s*["']?([\w.]+)["']?""")
            .find(maskedComments)?.groupValues?.get(1)
            ?: Regex("""jvmTarget\s+["']?([\w.]+)["']?""").find(maskedComments)?.groupValues?.get(1)
        val javaCompat = Regex("""sourceCompatibility\s*=\s*["']?([\w.]+)["']?""")
            .find(maskedComments)?.groupValues?.get(1)
            ?: Regex("""sourceCompatibility\s+["']?([\w.]+)["']?""").find(maskedComments)?.groupValues?.get(1)

        val androidPluginText = plugins.any {
            it.startsWith("com.android.") || it == "android" || it == "android-library"
        } || hasAndroidBlock
        val kotlinPluginText = plugins.any { it.contains("kotlin", ignoreCase = true) } ||
            "org.jetbrains.kotlin" in maskedComments

        return ModuleBuildInfo(
            dir = dir,
            buildFile = buildFile,
            isKts = isKts,
            pluginIds = plugins.toList(),
            usesAliasPlugins = alias,
            hasAndroidBlock = hasAndroidBlock,
            androidProps = androidProps,
            composeEnabled = androidProps["compose"] == "true",
            jvmTarget = jvmTarget,
            javaCompat = javaCompat,
            dependencies = dependencies,
            sourceSetSrcDirs = sourceSetSrcDirs,
            kotlinPluginText = kotlinPluginText,
            androidPluginText = androidPluginText
        )
    }

    private val KNOWN_KEYS = listOf(
        "namespace", "compileSdk", "compileSdkVersion", "minSdk", "minSdkVersion",
        "targetSdk", "targetSdkVersion", "versionCode", "versionName",
        "applicationId", "buildToolsVersion", "ndkVersion", "multiDexEnabled",
        "testInstrumentationRunner", "renderscriptTargetApi"
    )

    private fun collectProps(body: String, out: MutableMap<String, String>) {
        for (key in KNOWN_KEYS) {
            if (out.containsKey(key)) continue
            val eq = Regex("""(?<![\w.])${Regex.escape(key)}\s*=\s*([^\n]+)""").find(body)
            val space = Regex("""(?m)^\s*${Regex.escape(key)}\s+(?!=)([^\n]+)""").find(body)
            val raw = eq?.groupValues?.get(1) ?: space?.groupValues?.get(1) ?: continue
            var value = raw.trim().removeSuffix(",").trim()
            value = value.substringBefore("//").substringBefore("/*").trim()
            value = value.removeSurrounding("\"").removeSurrounding("'").trim()
            out[key] = value
        }
    }

    private fun collectDependencies(
        body: String,
        maskedComments: String,
        bodyOffset: Int,
        out: MutableList<ParsedDependency>
    ) {
        val consumedLines = mutableSetOf<Int>()
        Regex("""project\s*\(\s*["']([^"']+)["']""").findAll(body).forEach { m ->
            val line = GradleCodeScanner.lineOfIndex(maskedComments, bodyOffset + m.range.first)
            consumedLines.add(line)
            out.add(
                ParsedDependency(
                    kind = ParsedDependency.Kind.PROJECT,
                    raw = m.groupValues[1],
                    projectPath = m.groupValues[1],
                    line = line
                )
            )
        }
        Regex("""(?<![\w.])files\s*\(([^)]*)\)""").findAll(body).forEach { m ->
            val paths = Regex("""["']([^"']+)["']""").findAll(m.groupValues[1])
                .map { it.groupValues[1] }.toList()
            if (paths.isNotEmpty()) {
                val line = GradleCodeScanner.lineOfIndex(maskedComments, bodyOffset + m.range.first)
                consumedLines.add(line)
                out.add(
                    ParsedDependency(
                        kind = ParsedDependency.Kind.FILES,
                        raw = paths.joinToString(),
                        filePaths = paths,
                        line = line
                    )
                )
            }
        }
        Regex("""fileTree\s*\(([^)]*)\)""").findAll(body).forEach { m ->
            val dirInTree = Regex("""dir\s*[:=]\s*["']([^"']+)["']""").find(m.groupValues[1])
                ?: Regex("""["']([^"']+)["']""").find(m.groupValues[1])
            if (dirInTree != null) {
                val line = GradleCodeScanner.lineOfIndex(maskedComments, bodyOffset + m.range.first)
                consumedLines.add(line)
                out.add(
                    ParsedDependency(
                        kind = ParsedDependency.Kind.FILE_TREE,
                        raw = dirInTree.groupValues[1],
                        filePaths = listOf(dirInTree.groupValues[1]),
                        line = line
                    )
                )
            }
        }
        Regex("""["']([A-Za-z0-9._-]+:[A-Za-z0-9._-]+(?::[A-Za-z0-9._-]+)*)["']""").findAll(body)
            .forEach { m ->
                val line = GradleCodeScanner.lineOfIndex(maskedComments, bodyOffset + m.range.first)
                if (line in consumedLines) return@forEach
                val coord = m.groupValues[1].split(':')
                if (coord.size < 2) return@forEach
                if (coord[0].startsWith("kotlin")) return@forEach
                consumedLines.add(line)
                out.add(
                    ParsedDependency(
                        kind = ParsedDependency.Kind.COORDINATE,
                        raw = m.groupValues[1],
                        coordinate = coord,
                        line = line
                    )
                )
            }
    }
}
