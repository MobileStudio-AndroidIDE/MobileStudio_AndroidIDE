package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * GradleSyncManager - Android Studio sync system
 * 1. Gradle Tooling API for project model collection
 * 2. JAR/AAR symbol indexing from Gradle cache
 * 3. Package-qualified symbol names
 * 4. Import auto-suggestion support
 */
class GradleSyncManager(private val context: Context) {

    companion object {
        private const val TAG = "GradleSyncManager"
        private const val MAX_JAR_SIZE = 80_000_000L
        private const val MAX_SYMBOLS_PER_JAR = 2000
        private const val MAX_TOTAL_SYMBOLS = 30000
    }

    data class IndexedSymbol(
        val simpleName: String,
        val fullyQualifiedName: String,
        val packageName: String,
        val sourceJar: String,
        val kind: SymbolKind,
        val members: List<MemberInfo> = emptyList()
    )

    data class MemberInfo(
        val name: String,
        val returnType: String,
        val parameters: List<String> = emptyList(),
        val isStatic: Boolean = false
    )

    enum class SymbolKind { CLASS, INTERFACE, OBJECT, FUNCTION, CONSTANT, ENUM }

    private val indexedSymbols = ConcurrentHashMap<String, IndexedSymbol>()
    private val indexedPackages = ConcurrentHashMap<String, MutableSet<String>>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile var isSyncing = false
        private set
    @Volatile var lastSyncTime = 0L
        private set
    @Volatile var symbolCount = 0
        private set

    // AGP project model
    @Volatile var projectModel: AndroidProjectModel? = null
        private set

    // Enhanced sync with progress tracking, error handling, and caching
    val syncProgress = GradleSyncProgress()
    val syncErrorHandler = GradleSyncErrorHandler()
    val cacheManager = GradleCacheManager(context)

    /**
     * Full sync process - Enhanced with progress, error handling, caching
     */
    fun syncProject(
        projectDir: File,
        gradleRunner: GradleRunner,
        installer: JreGradleInstaller,
        onProgress: (String) -> Unit = {},
        onComplete: (Boolean, String) -> Unit
    ) {
        if (isSyncing) {
            onComplete(false, "Sync already in progress")
            return
        }

        scope.launch {
            isSyncing = true
            val startTime = System.currentTimeMillis()
            syncProgress.beginSync()

            try {
                // Check cache first
                syncProgress.advanceTo(GradleSyncProgress.SyncPhase.INITIALIZING, "Checking project cache...")
                val fingerprint = cacheManager.generateFingerprint(projectDir)
                if (!cacheManager.isSyncNeeded(projectDir, fingerprint)) {
                    val cachedModel = cacheManager.loadModel(projectDir)
                    if (cachedModel != null) {
                        projectModel = cachedModel
                        lastSyncTime = System.currentTimeMillis()
                        symbolCount = 0
                        val summary = "Using cached project model (no changes detected)"
                        Log.i(TAG, summary)
                        syncProgress.completeSync(true, summary)
                        onProgress(summary)
                        onComplete(true, summary)
                        isSyncing = false
                        return@launch
                    }
                }

                syncProgress.advanceTo(GradleSyncProgress.SyncPhase.LOADING_SETTINGS, "Loading settings.gradle...")

                // Step 1: Gradle Tooling API for project model
                syncProgress.advanceTo(GradleSyncProgress.SyncPhase.CONFIGURING_PROJECT, "Configuring project via Gradle Tooling API...")
                val resolveSuccess = resolveGradleDependencies(projectDir, gradleRunner, installer, onProgress)

                syncProgress.advanceTo(GradleSyncProgress.SyncPhase.LOADING_SDK, "Loading Android SDK configuration...")

                syncProgress.advanceTo(GradleSyncProgress.SyncPhase.RESOLVING_DEPENDENCIES, "Scanning Gradle cache for dependencies...")

                // Step 2: Scan JAR/AAR from Gradle cache
                val cacheFiles = scanGradleCache(projectDir)

                syncProgress.advanceTo(GradleSyncProgress.SyncPhase.INDEXING_SYMBOLS, "Indexing symbols (${cacheFiles.size} JARs)...")

                // Step 3: Symbol indexing
                indexAllSymbols(cacheFiles, onProgress)

                syncProgress.advanceTo(GradleSyncProgress.SyncPhase.LOADING_SOURCE_SETS, "Indexing project source code...")

                // Step 4: Index project source code
                indexProjectSource(projectDir)

                syncProgress.advanceTo(GradleSyncProgress.SyncPhase.UPDATING_IDE, "Updating IDE model...")

                lastSyncTime = System.currentTimeMillis()
                symbolCount = indexedSymbols.size

                // Cache the model
                val model = projectModel
                if (model != null) {
                    cacheManager.saveModel(projectDir, model, fingerprint)
                }

                val duration = lastSyncTime - startTime
                val summary = buildString {
                    appendLine("Sync complete in ${formatDuration(duration)}")
                    appendLine("  Symbols indexed: $symbolCount")
                    appendLine("  Packages found: ${indexedPackages.size}")
                    appendLine("  JARs scanned: ${cacheFiles.size}")
                    if (model != null) {
                        appendLine("  Project: ${model.applicationId.ifEmpty { model.packageName }}")
                        appendLine("  Variants: ${model.availableVariants.joinToString { it.name }}")
                        appendLine("  Dependencies: ${model.dependencies.size}")
                        appendLine("  Plugins: ${model.plugins.joinToString()}")
                        val moduleCount = model.propertiesFromGradle["moduleCount"]?.toIntOrNull() ?: 1
                        if (moduleCount > 1) {
                            appendLine("  Modules: $moduleCount")
                        }
                    }
                }
                Log.d(TAG, summary)
                syncProgress.completeSync(true, summary)
                onProgress(summary)
                onComplete(true, summary)

            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
                val error = syncErrorHandler.analyzeError(
                    output = listOf(e.message ?: "Unknown error"),
                    exitCode = -1,
                    phase = syncProgress.currentPhase
                )
                syncProgress.reportError(syncProgress.currentPhase, error.message, error.suggestions.firstOrNull() ?: "")
                syncProgress.completeSync(false, error.message)
                onComplete(false, error.toDisplayString())
            } finally {
                isSyncing = false
            }
        }
    }

    private fun formatDuration(ms: Long): String = when {
        ms < 1000 -> "${ms}ms"
        ms < 60000 -> String.format("%.1fs", ms / 1000.0)
        else -> String.format("%dm %ds", ms / 60000, (ms % 60000) / 1000)
    }

    /**
     * Step 1: Gradle Tooling API for project model collection
     */
    private fun resolveGradleDependencies(
        projectDir: File,
        gradleRunner: GradleRunner,
        installer: JreGradleInstaller,
        onProgress: (String) -> Unit
    ): Boolean {
        return try {
            onProgress("Parsing build.gradle files...")
            val parseResult = BuildGradleParser.parseProject(projectDir)

            // Use Gradle Tooling API
            onProgress("Connecting to Gradle Tooling API...")
            val toolingConnector = GradleToolingConnector(context)
            val toolingResult = runBlocking {
                toolingConnector.fetchProjectModel(
                    projectDir = projectDir,
                    gradleRunner = gradleRunner,
                    onProgress = onProgress
                )
            }

            if (toolingResult.success && toolingResult.model != null) {
                onProgress("Tooling API connected (${toolingResult.durationMs}ms)")
                projectModel = toolingResult.model.copy(
                    lastSyncTime = System.currentTimeMillis(),
                    syncSuccess = true
                )
                true
            } else {
                onProgress("Tooling API failed: ${toolingResult.error}")
                onProgress("Falling back to local build.gradle parsing...")
                projectModel = parseResult.model.copy(
                    lastSyncTime = System.currentTimeMillis(),
                    syncSuccess = false
                )
                false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Dependency resolution failed", e)
            false
        }
    }

    /**
     * Step 2: Scan Gradle cache for JAR/AAR files
     */
    private fun scanGradleCache(projectDir: File): List<File> {
        val cacheRoots = mutableListOf<File>()

        // Gradle cache directories
        val gradleHome = File(context.filesDir, ".gradle")
        cacheRoots.add(File(gradleHome, "caches/modules-2/files-2.1"))
        cacheRoots.add(File(gradleHome, "caches/transforms-3"))
        cacheRoots.add(File(gradleHome, "caches/jars-3"))

        // Project-local cache
        val projectGradle = File(projectDir, ".gradle")
        cacheRoots.add(File(projectGradle, "caches/modules-2/files-2.1"))

        // Android SDK cache: platforms/android-XX/android.jar
        val sdkDir = resolveAndroidSdk()
        if (sdkDir != null) {
            cacheRoots.add(File(sdkDir, "platforms"))
            val buildTools = File(sdkDir, "build-tools")
            if (buildTools.exists()) {
                buildTools.listFiles()?.filter { it.isDirectory }?.forEach { bt ->
                    cacheRoots.add(bt)
                }
            }
        }

        val jarFiles = mutableListOf<File>()

        cacheRoots.forEach { root ->
            if (!root.exists()) return@forEach

            root.walkTopDown()
                .filter { file ->
                    file.isFile && file.length() in 1..MAX_JAR_SIZE && (
                        file.extension == "jar" ||
                        file.extension == "aar"
                    )
                }
                .forEach { file ->
                    jarFiles.add(file)
                }
        }

        Log.d(TAG, "Found ${jarFiles.size} JAR/AAR files in cache")
        return jarFiles
    }

    /**
     * Step 3: Full symbol indexing
     */
    private fun indexAllSymbols(cacheFiles: List<File>, onProgress: (String) -> Unit) {
        indexedSymbols.clear()
        indexedPackages.clear()

        var processed = 0
        cacheFiles.forEach { file ->
            processed++
            if (processed % 50 == 0) {
                onProgress("Indexing: ${processed}/${cacheFiles.size} JARs...")
            }

            try {
                if (file.extension == "jar") {
                    indexJarFile(file)
                } else if (file.extension == "aar") {
                    indexAarFile(file)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to index ${file.name}: ${e.message}")
            }

            if (indexedSymbols.size >= MAX_TOTAL_SYMBOLS) return
        }
    }

    /**
     * Index JAR file - extract member info from class bytecode
     */
    private fun indexJarFile(jarFile: File) {
        ZipFile(jarFile).use { zip ->
            zip.entries().asSequence()
                .filter { entry ->
                    entry.isDirectory.not() &&
                    entry.name.endsWith(".class") &&
                    !entry.name.endsWith("module-info.class") &&
                    !entry.name.endsWith("package-info.class")
                }
                .take(MAX_SYMBOLS_PER_JAR)
                .forEach { entry ->
                    val classPath = entry.name.removeSuffix(".class").replace('/', '.')
                    val simpleName = classPath.substringAfterLast('.')
                    val packageName = classPath.substringBeforeLast('.', "")

                    if (simpleName.firstOrNull()?.isUpperCase() == true) {
                        val kind = when {
                            simpleName.startsWith("I") && simpleName.length > 1 &&
                                simpleName[1].isUpperCase() -> SymbolKind.INTERFACE
                            simpleName.endsWith("Kt") -> SymbolKind.OBJECT
                            else -> SymbolKind.CLASS
                        }

                        val members = try {
                            extractMembersFromBytecode(zip, entry)
                        } catch (_: Exception) { emptyList() }

                        val symbol = IndexedSymbol(
                            simpleName = simpleName,
                            fullyQualifiedName = classPath,
                            packageName = packageName,
                            sourceJar = jarFile.name,
                            kind = kind,
                            members = members
                        )

                        val key = "${packageName}.${simpleName}"
                        indexedSymbols[key] = symbol

                        indexedPackages.getOrPut(packageName) { mutableSetOf() }.add(simpleName)
                    }
                }
        }
    }

    /**
     * Extract public method/field names from class bytecode
     */
    private fun extractMembersFromBytecode(zip: ZipFile, entry: java.util.zip.ZipEntry): List<MemberInfo> {
        val members = mutableListOf<MemberInfo>()
        val bytes = zip.getInputStream(entry).use { it.readBytes() }
        if (bytes.size < 10) return members

        try {
            var offset = 8
            val constantPoolCount = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            offset += 2

            val utf8Strings = mutableListOf<String>()
            var i = 1
            while (i < constantPoolCount && offset < bytes.size) {
                val tag = bytes[offset].toInt() and 0xFF
                offset++
                when (tag) {
                    1 -> {
                        val len = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                        offset += 2
                        if (offset + len <= bytes.size) {
                            utf8Strings.add(String(bytes, offset, len, Charsets.UTF_8))
                        } else {
                            utf8Strings.add("")
                        }
                        offset += len
                    }
                    3, 4 -> offset += 4
                    5, 6 -> { offset += 8; i++ }
                    7, 8, 16, 19, 20 -> offset += 2
                    9, 10, 11, 12, 15, 17, 18 -> offset += 4
                    else -> offset += 2
                }
                i++
            }

            val methodPattern = Regex("^[a-z][a-zA-Z0-9_]{1,40}$")
            val seen = mutableSetOf<String>()

            for (s in utf8Strings) {
                val trimmed = s.trim()
                if (trimmed.length < 2 || trimmed.length > 40) continue
                if (trimmed.startsWith("(")) continue
                if (methodPattern.matches(trimmed) && trimmed !in seen) {
                    seen.add(trimmed)
                    members.add(MemberInfo(trimmed, "unknown", emptyList(), false))
                }
            }
        } catch (_: Exception) { }

        return members.take(50)
    }

    /**
     * Index AAR file (extract classes.jar then index)
     */
    private fun indexAarFile(aarFile: File) {
        try {
            val tempDir = File(context.cacheDir, "aar_index_${aarFile.nameWithoutExtension}")
            tempDir.mkdirs()

            ZipFile(aarFile).use { zip ->
                val classesJar = zip.getEntry("classes.jar") ?: return
                zip.getInputStream(classesJar).use { input ->
                    val output = File(tempDir, "classes.jar")
                    output.outputStream().use { out -> input.copyTo(out) }
                    indexJarFile(output)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to index AAR ${aarFile.name}: ${e.message}")
        }
    }

    /**
     * Step 4: Index project source code
     */
    private fun indexProjectSource(projectDir: File) {
        val sourceExtensions = setOf("kt", "java")
        val srcDirs = listOf(
            "app/src/main/kotlin",
            "app/src/main/java",
            "app/src/main/java-gen"
        )

        srcDirs.forEach { srcPath ->
            val srcDir = File(projectDir, srcPath)
            if (!srcDir.exists()) return@forEach

            srcDir.walkTopDown()
                .filter { it.isFile && it.extension in sourceExtensions && it.length() <= 1_000_000 }
                .forEach { file ->
                    indexSourceFile(file, projectDir)
                }
        }
    }

    /**
     * Index individual source file
     */
    private fun indexSourceFile(file: File, projectDir: File) {
        val source = runCatching { file.readText() }.getOrNull() ?: return
        val pkg = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE)
            .find(source)?.groupValues?.get(1) ?: ""
        val relativePath = runCatching { file.relativeTo(projectDir).path }.getOrDefault(file.name)

        Regex("""(?:class|object|interface|enum\s+class|enum|data\s+class|sealed\s+class|abstract\s+class)\s+(\w+)""")
            .findAll(source).forEach { match ->
                val name = match.groupValues[1]
                val key = if (pkg.isNotEmpty()) "$pkg.$name" else name

                indexedSymbols[key] = IndexedSymbol(
                    simpleName = name,
                    fullyQualifiedName = key,
                    packageName = pkg,
                    sourceJar = relativePath,
                    kind = when {
                        match.value.contains("interface") -> SymbolKind.INTERFACE
                        match.value.contains("object") -> SymbolKind.OBJECT
                        match.value.contains("enum") -> SymbolKind.ENUM
                        else -> SymbolKind.CLASS
                    }
                )

                indexedPackages.getOrPut(pkg) { mutableSetOf() }.add(name)
            }

        Regex("""(?:fun|val|var)\s+(\w+)\s*[\(=]""").findAll(source).forEach { match ->
            val name = match.groupValues[1]
            if (name.firstOrNull()?.isLowerCase() == true || name.firstOrNull() == '_') {
                val key = if (pkg.isNotEmpty()) "$pkg.$name" else name
                indexedSymbols[key] = IndexedSymbol(
                    simpleName = name,
                    fullyQualifiedName = key,
                    packageName = pkg,
                    sourceJar = relativePath,
                    kind = if (match.value.startsWith("fun")) SymbolKind.FUNCTION else SymbolKind.CONSTANT
                )
            }
        }
    }

    /**
     * Search symbols by prefix
     */
    fun searchSymbols(prefix: String, maxResults: Int = 50): List<IndexedSymbol> {
        if (prefix.isEmpty()) return emptyList()

        val lowerPrefix = prefix.lowercase()
        return indexedSymbols.values
            .filter { symbol ->
                symbol.simpleName.lowercase().startsWith(lowerPrefix) ||
                symbol.fullyQualifiedName.lowercase().contains(lowerPrefix)
            }
            .sortedWith(compareByDescending<IndexedSymbol> {
                it.simpleName.lowercase().startsWith(lowerPrefix)
            }.thenBy { it.simpleName.length })
            .take(maxResults)
    }

    /**
     * Get all symbols in a package
     */
    fun getSymbolsInPackage(packageName: String): List<IndexedSymbol> {
        return indexedSymbols.values.filter { it.packageName == packageName }
    }

    /**
     * Generate import statement
     */
    fun generateImport(symbol: IndexedSymbol): String {
        return "import ${symbol.fullyQualifiedName}"
    }

    /**
     * Find missing imports in code
     */
    fun findMissingImports(code: String, currentPackage: String): List<String> {
        val existingImports = Regex("^import\\s+([\\w.]+)", RegexOption.MULTILINE)
            .findAll(code)
            .map { it.groupValues[1] }
            .toSet()

        val usedClassRefs = Regex("\\b([A-Z][a-zA-Z0-9_]*)\\b")
            .findAll(code)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it.length > 1 }

        val missing = mutableListOf<String>()

        usedClassRefs.forEach { className ->
            val candidates = indexedSymbols.values.filter { it.simpleName == className }
            val alreadyImported = existingImports.any { import ->
                import.endsWith(".$className")
            }

            if (candidates.isNotEmpty() && !alreadyImported) {
                val best = candidates.minByOrNull {
                    when {
                        it.packageName == currentPackage -> 0
                        it.packageName.startsWith("kotlin") -> 2
                        it.packageName.startsWith("java") -> 3
                        it.packageName.startsWith("android.") -> 1
                        else -> 4
                    }
                }
                if (best != null) {
                    missing.add("import ${best.fullyQualifiedName}")
                }
            }
        }

        return missing
    }

    /**
     * Resolve Android SDK path
     */
    private fun resolveAndroidSdk(): File? {
        val candidates = listOf(
            File(context.filesDir, "toolchain/sdk/active"),
            File(context.filesDir, "toolchain/sdk"),
            File(System.getenv("ANDROID_HOME") ?: ""),
            File(System.getenv("ANDROID_SDK_ROOT") ?: "")
        )
        return candidates.firstOrNull { it.exists() && File(it, "platforms").exists() }
    }

    /**
     * Get list of indexed packages
     */
    fun getPackages(): Set<String> = indexedPackages.keys.toSet()

    /**
     * Get all indexed symbols
     */
    fun getAllSymbols(): Map<String, IndexedSymbol> = indexedSymbols.toMap()
}
