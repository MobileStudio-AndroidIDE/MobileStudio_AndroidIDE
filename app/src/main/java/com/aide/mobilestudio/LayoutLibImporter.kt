package com.aide.mobilestudio

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

class LayoutLibImporter(private val context: Context) {
    companion object {
        private const val BUNDLED_ROOT = "layoutlib"
        private const val JDK17_RUNTIME_URL =
            "https://dl.google.com/dl/android/maven2/com/android/tools/layoutlib/layoutlib-runtime/16.1.0-jdk17/layoutlib-runtime-16.1.0-jdk17.jar"
        private const val JDK17_RUNTIME_SIZE = 99_561_706L

        private val bundledFiles = listOf(
            "plugins/design-tools/lib/layoutlib.jar",
            "plugins/android/lib/layoutlib-api.jar",
            "plugins/android/lib/layoutlib-loader.jar",
            "plugins/android/lib/kxml2-2.3.0.jar",
            "plugins/android/lib/android-common.jar",
            "plugins/android/lib/sdk-common.jar",
            "plugins/design-tools/resources/layoutlib/data/layoutlib-extensions.jar",
            "lib/guava-31.1-jre.jar",
            "lib/guava-31.0.1-jre.jar",
            "lib/guava-30.1.1-jre.jar",
            "lib/guava-30.0-jre.jar",
            "lib/guava-29.0-jre.jar"
        )
    }

    data class Status(
        val installed: Boolean,
        val jarCount: Int,
        val sizeBytes: Long,
        val targetDir: File,
        val missing: List<String>
    )

    data class ImportResult(
        val success: Boolean,
        val copied: Int,
        val message: String
    )

    val targetDir: File = File(context.filesDir, "toolchain/layoutlib")

    fun status(): Status {
        val jars = targetDir.walkIfExists()
            .filter { it.isFile && it.extension.equals("jar", true) }
            .toList()
        val names = jars.map { it.name.lowercase(Locale.US) }
        val hasBridge = jars.any { jarContains(it, "com/android/layoutlib/bridge/Bridge.class") }
        val hasApi = jars.any { jarContains(it, "com/android/ide/common/rendering/api/ILayoutPullParser.class") }
        val hasXmlPull = jars.any { jarContains(it, "org/xmlpull/v1/XmlPullParser.class") }
        val hasKxml = names.any { it.startsWith("kxml2") } || jars.any { jarContains(it, "org/kxml2/io/KXmlParser.class") }
        val hasRuntimeData = findRuntimeDataDir(targetDir) != null
        val missing = buildList {
            if (!hasBridge) add("layoutlib bridge")
            if (!hasApi) add("layoutlib api")
            if (!hasKxml) add("kxml2 parser")
            if (!hasXmlPull) add("xmlpull parser")
            if (!hasRuntimeData) add("layoutlib runtime data")
        }
        return Status(
            installed = missing.isEmpty(),
            jarCount = jars.size,
            sizeBytes = jars.sumOf { it.length() },
            targetDir = targetDir,
            missing = missing
        )
    }

    fun clear(): Boolean {
        return targetDir.deleteRecursively().also { targetDir.mkdirs() }
    }

    fun downloadJdk17Runtime(onProgress: (String) -> Unit = {}): ImportResult {
        val tempDir = File(context.cacheDir, "layoutlib_jdk17_download").apply {
            deleteRecursively()
            mkdirs()
        }
        val out = File(tempDir, "layoutlib-runtime-16.1.0-jdk17.jar")
        return try {
            out.parentFile?.mkdirs()
            downloadWithRetries(out, onProgress)

            val validation = validateLayoutLibRuntimeDataJar(out)
            if (validation != null) {
                error(validation)
            }

            onProgress("Extracting LayoutLib JDK17 runtime data...")
            val extractRoot = File(tempDir, "plugins/design-tools/resources/layoutlib").apply { mkdirs() }
            extractRuntimeDataJar(out, extractRoot)
            out.delete()

            targetDir.mkdirs()
            tempDir.copyRecursively(targetDir, overwrite = true)
            tempDir.deleteRecursively()

            val next = status()
            val missingBridgeParts = next.missing.filterNot { it == "layoutlib runtime data" }
            if (missingBridgeParts.isEmpty()) {
                ImportResult(true, 1, "LayoutLib JDK17 runtime data ready: ${next.sizeBytes / 1024 / 1024}MB.")
            } else {
                ImportResult(
                    true,
                    1,
                    "LayoutLib JDK17 data installed. Still missing bridge jars: ${missingBridgeParts.joinToString()}"
                )
            }
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            ImportResult(false, 0, "LayoutLib JDK17 download failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun downloadWithRetries(out: File, onProgress: (String) -> Unit) {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            runCatching {
                out.delete()
                onProgress("Downloading LayoutLib JDK17 runtime... attempt ${attempt + 1}/3")
                val connection = (URL(JDK17_RUNTIME_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 180_000
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                }
                val code = connection.responseCode
                if (code !in 200..299) error("HTTP $code")
                val total = connection.contentLengthLong.takeIf { it > 0L } ?: JDK17_RUNTIME_SIZE
                connection.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            val percent = ((copied * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent && percent % 2 == 0) {
                                lastPercent = percent
                                onProgress("Downloading LayoutLib JDK17 runtime... $percent% (${copied / 1024 / 1024}/${total / 1024 / 1024}MB)")
                            }
                        }
                    }
                }
                connection.disconnect()
                if (out.length() < JDK17_RUNTIME_SIZE) {
                    error("partial download: ${out.length()} / $JDK17_RUNTIME_SIZE bytes")
                }
                return
            }.onFailure {
                lastError = it
                out.delete()
            }
        }
        throw RuntimeException(lastError?.message ?: "download failed")
    }

    private fun validateLayoutLibRuntimeDataJar(file: File): String? {
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().asSequence().map { it.name.replace('\\', '/') }.toList()
                val hasBuildProp = entries.any { it == "build.prop" }
                val hasFrameworkRes = entries.any { it.startsWith("data/res/") }
                val hasFonts = entries.any { it.startsWith("data/fonts/") }
                when {
                    !hasBuildProp -> "Downloaded runtime jar does not contain build.prop"
                    !hasFrameworkRes -> "Downloaded runtime jar does not contain data/res"
                    !hasFonts -> "Downloaded runtime jar does not contain data/fonts"
                    else -> null
                }
            }
        } catch (e: Exception) {
            "Downloaded jar is incomplete or invalid: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun extractRuntimeDataJar(file: File, destination: File) {
        val destinationPath = destination.canonicalPath
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val name = entry.name.replace('\\', '/')
                val shouldExtract = name == "build.prop" ||
                    name.startsWith("data/") ||
                    name.startsWith("licenses/")
                if (!shouldExtract) return@forEach
                val out = File(destination, name).canonicalFile
                if (!out.absolutePath.startsWith(destinationPath)) {
                    error("Blocked unsafe LayoutLib runtime entry: $name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    fun ensureBundledInstalled(onProgress: (String) -> Unit = {}): ImportResult {
        val current = status()
        if (current.installed) {
            return ImportResult(true, 0, "LayoutLib runtime already installed: ${current.jarCount} jars.")
        }

        val available = bundledFiles.filter { AssetUtils.assetExists(context, "$BUNDLED_ROOT/$it") }
        if (available.isEmpty()) {
            return ImportResult(false, 0, "Bundled LayoutLib assets are missing.")
        }

        val tempDir = File(context.cacheDir, "layoutlib_bundled_install").apply {
            deleteRecursively()
            mkdirs()
        }
        var copied = 0
        return try {
            onProgress("Installing bundled LayoutLib runtime...")
            available.forEach { relative ->
                val out = File(tempDir, relative).canonicalFile
                if (!out.absolutePath.startsWith(tempDir.canonicalPath)) {
                    error("Blocked unsafe bundled asset: $relative")
                }
                if (AssetUtils.copyAsset(context, "$BUNDLED_ROOT/$relative", out, overwrite = true)) {
                    copied++
                }
            }

            targetDir.deleteRecursively()
            targetDir.mkdirs()
            tempDir.copyRecursively(targetDir, overwrite = true)
            tempDir.deleteRecursively()

            val next = status()
            if (next.installed) {
                ImportResult(true, copied, "Bundled LayoutLib ready: ${next.jarCount} jars.")
            } else {
                ImportResult(false, copied, "Bundled LayoutLib incomplete: ${next.missing.joinToString()}")
            }
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            ImportResult(false, copied, "Bundled LayoutLib install failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun importFromAndroidStudioZip(uri: Uri, onProgress: (String) -> Unit): ImportResult {
        val tempDir = File(context.cacheDir, "layoutlib_import").apply {
            deleteRecursively()
            mkdirs()
        }
        var copied = 0
        return try {
            onProgress("Opening Android Studio ZIP...")
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.replace('\\', '/')
                        if (!entry.isDirectory && shouldCopyJar(name)) {
                            val relative = targetRelativePath(name)
                            val out = File(tempDir, relative).canonicalFile
                            if (!out.absolutePath.startsWith(tempDir.canonicalPath)) {
                                error("Blocked unsafe ZIP entry: $name")
                            }
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                            copied++
                            if (copied == 1 || copied % 20 == 0) {
                                onProgress("Extracted $copied LayoutLib jar(s)...")
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: error("Cannot open selected ZIP")

            if (copied == 0) {
                tempDir.deleteRecursively()
                return ImportResult(false, 0, "No Android Studio LayoutLib jars found in this ZIP.")
            }

            onProgress("Installing LayoutLib runtime...")
            targetDir.deleteRecursively()
            targetDir.mkdirs()
            tempDir.copyRecursively(targetDir, overwrite = true)
            tempDir.deleteRecursively()

            val status = status()
            if (status.installed) {
                ImportResult(true, copied, "LayoutLib runtime ready: ${status.jarCount} jars.")
            } else {
                ImportResult(false, copied, "Imported $copied jars, but missing: ${status.missing.joinToString()}")
            }
        } catch (e: Exception) {
            tempDir.deleteRecursively()
            ImportResult(false, copied, "LayoutLib import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun shouldCopyJar(path: String): Boolean {
        val lower = path.lowercase(Locale.US)
        if (!lower.endsWith(".jar")) return false
        return lower.contains("/plugins/android/lib/") ||
            lower.contains("/plugins/design-tools/lib/") ||
            lower.endsWith("/layoutlib.jar") ||
            lower.endsWith("/layoutlib-api.jar") ||
            lower.endsWith("/kxml2-2.3.0.jar") ||
            lower.endsWith("/android-common.jar") ||
            lower.endsWith("/sdk-common.jar") ||
            lower.endsWith("/layoutlib-extensions.jar") ||
            lower.endsWith("/layoutlib-loader.jar") ||
            lower.contains("guava") ||
            lower.contains("annotations-java5") ||
            lower.contains("error_prone_annotations") ||
            lower.contains("checker-compat-qual") ||
            lower.contains("jsr305")
    }

    private fun targetRelativePath(path: String): String {
        val normalized = path.replace('\\', '/')
        val pluginIndex = normalized.indexOf("/plugins/")
        return if (pluginIndex >= 0) {
            normalized.substring(pluginIndex + 1)
        } else {
            "lib/${File(normalized).name}"
        }
    }

    private fun jarContains(jar: File, entryName: String): Boolean {
        return runCatching {
            java.util.jar.JarFile(jar).use { it.getEntry(entryName) != null }
        }.getOrDefault(false)
    }

    private fun findRuntimeDataDir(root: File): File? {
        val direct = listOf(
            File(root, "plugins/design-tools/resources/layoutlib/data"),
            File(root, "resources/layoutlib/data"),
            File(root, "data")
        )
        return (direct.asSequence() + root.walkIfExists().filter { it.isDirectory && it.name == "data" })
            .distinctBy { it.absolutePath }
            .firstOrNull { File(it, "res").isDirectory && File(it, "fonts").isDirectory }
    }

    private fun File.walkIfExists(): Sequence<File> {
        return if (exists()) walkTopDown() else emptySequence()
    }
}
