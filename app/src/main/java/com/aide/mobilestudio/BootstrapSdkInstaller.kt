package com.aide.mobilestudio

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class BootstrapSdkInstaller(
    private val context: Context,
    private val onLog: (String) -> Unit,
    private val onProgress: (Int) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val toolchain = ToolchainManager(context)

    val sdkDir: File
        get() = File(toolchain.kindDir(ToolchainManager.Kind.SDK), "android-34")

    val isInstalled: Boolean
        get() = File(sdkDir, "platforms/android-34/android.jar").isFile &&
            File(sdkDir, "build-tools/34.0.0/aapt2").isFile

    fun install(callback: (Boolean) -> Unit) {
        Thread {
            val success = runCatching {
                sdkDir.mkdirs()
                log("sdk root: ${sdkDir.absolutePath}")
                log("fetch repository index")
                val repositoryXml = downloadText("https://dl.google.com/android/repository/repository2-1.xml")
                installSdkPackage(repositoryXml, "platform-tools")
                installSdkPackage(repositoryXml, "platforms;android-34")
                installSdkPackage(repositoryXml, "build-tools;34.0.0")
                listOf("platforms;android-36", "build-tools;36.1.0").forEach { packagePath ->
                    runCatching { installSdkPackage(repositoryXml, packagePath) }
                        .onFailure { log("optional $packagePath install failed: ${it.message}") }
                }
                writeAndroidSdkLicenses()
                toolchain.setActiveVersion(ToolchainManager.Kind.SDK, "android-34")
                runCatching { toolchain.ensureAssetOverridesInstalled() }
                log("sdk android-34 ready")
                true
            }.getOrElse {
                log("sdk install failed: ${it.message}")
                false
            }
            mainHandler.post { callback(success) }
        }.start()
    }

    private fun installSdkPackage(repositoryXml: String, packagePath: String) {
        if (isSdkPackageInstalled(packagePath)) {
            log("$packagePath already installed")
            return
        }
        val url = resolveSdkPackageUrl(repositoryXml, packagePath)
        val zip = File(context.cacheDir, "bootstrap-sdk-${packagePath.replace(Regex("[^A-Za-z0-9._-]"), "_")}.zip")
        val temp = File(context.cacheDir, "bootstrap-sdk-extract-${packagePath.hashCode()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            log("download $packagePath")
            downloadFile(url, zip)
            log("extract $packagePath")
            unzip(zip, temp)
            when (packagePath) {
                "platform-tools" -> {
                    val source = File(temp, "platform-tools").takeIf { it.exists() }
                        ?: temp.listFiles()?.firstOrNull { it.isDirectory && it.name.contains("platform-tools") }
                        ?: error("platform-tools archive layout not recognized")
                    replaceDirectory(source, File(sdkDir, "platform-tools"))
                }
                "platforms;android-34", "platforms;android-36" -> {
                    val version = packagePath.substringAfter(';')
                    val androidJar = temp.walkTopDown().firstOrNull { it.isFile && it.name == "android.jar" }
                        ?: error("$version platform archive does not contain android.jar")
                    replaceDirectory(androidJar.parentFile!!, File(sdkDir, "platforms/$version"))
                }
                "build-tools;34.0.0", "build-tools;36.1.0" -> {
                    val version = packagePath.substringAfter(';')
                    val aapt2 = temp.walkTopDown().firstOrNull { it.isFile && it.name == "aapt2" }
                        ?: error("build-tools archive does not contain aapt2")
                    replaceDirectory(aapt2.parentFile!!, File(sdkDir, "build-tools/$version"))
                    File(sdkDir, "build-tools/$version").walkTopDown()
                        .filter { it.isFile }
                        .forEach {
                            it.setReadable(true, false)
                            it.setExecutable(true, false)
                        }
                }
            }
            log("$packagePath installed")
        } finally {
            zip.delete()
            temp.deleteRecursively()
        }
    }

    private fun isSdkPackageInstalled(packagePath: String): Boolean {
        return when (packagePath) {
            "platform-tools" -> File(sdkDir, "platform-tools/adb").exists()
            "platforms;android-34" -> File(sdkDir, "platforms/android-34/android.jar").exists()
            "build-tools;34.0.0" -> File(sdkDir, "build-tools/34.0.0/aapt2").exists()
            "platforms;android-36" -> File(sdkDir, "platforms/android-36/android.jar").exists()
            "build-tools;36.1.0" -> File(sdkDir, "build-tools/36.1.0/aapt2").exists()
            else -> false
        }
    }

    private fun resolveSdkPackageUrl(repositoryXml: String, packagePath: String): String {
        val blocks = Regex(
            "<remotePackage\\b[^>]*\\bpath=\"${Regex.escape(packagePath)}\"[^>]*>[\\s\\S]*?</remotePackage>"
        ).findAll(repositoryXml).map { it.value }.toList()
        val block = when (packagePath) {
            "platforms;android-34" -> blocks.firstOrNull {
                "obsolete=\"true\"" !in it &&
                    Regex("<display-name>Android SDK Platform 34</display-name>").containsMatchIn(it)
            } ?: blocks.firstOrNull()
            "platforms;android-36" -> blocks.firstOrNull {
                "obsolete=\"true\"" !in it &&
                    Regex("<display-name>Android SDK Platform 36</display-name>").containsMatchIn(it)
            } ?: blocks.firstOrNull()
            else -> blocks.firstOrNull { "obsolete=\"true\"" !in it } ?: blocks.firstOrNull()
        } ?: error("SDK package not found: $packagePath")
        val archive = Regex("<archive>[\\s\\S]*?</archive>")
            .findAll(block)
            .map { it.value }
            .firstOrNull { "<host-os>linux</host-os>" in it || "<host-os>" !in it }
            ?: error("No Linux archive for $packagePath")
        val relativeUrl = Regex("<url>([^<]+)</url>").find(archive)?.groupValues?.get(1)
            ?: error("No URL for $packagePath")
        return "https://dl.google.com/android/repository/$relativeUrl"
    }

    private fun downloadText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            connect()
        }
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}: $url" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(url: String, destination: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            connect()
        }
        try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}: $url" }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastPercent = -1
            destination.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0L) {
                            val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                progress(percent)
                            }
                        }
                    }
                }
            }
            if (destination.length() == 0L) error("Downloaded file is empty")
            log("download complete: ${destination.name} (${destination.length() / 1024 / 1024}MB)")
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(zip: File, destinationDir: File) {
        val canonicalRoot = destinationDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val output = File(destinationDir, entry.name).canonicalFile
                check(output.absolutePath.startsWith(canonicalRoot)) { "Unsafe zip entry: ${entry.name}" }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { input.copyTo(it) }
                    output.setReadable(true, false)
                    if (entry.name.contains("/bin/")) output.setExecutable(true, false)
                }
                input.closeEntry()
                entry = input.nextEntry
            }
        }
    }

    private fun replaceDirectory(source: File, destination: File) {
        deleteRecursivelySafe(destination)
        destination.parentFile?.mkdirs()
        copyDirectory(source, destination)
        deleteRecursivelySafe(source)
    }

    private fun copyDirectory(source: File, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles()?.forEach { child -> copyDirectory(child, File(destination, child.name)) }
        } else {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
            destination.setReadable(true, false)
            if (source.canExecute()) destination.setExecutable(true, false)
        }
    }

    private fun deleteRecursivelySafe(file: File): Boolean {
        if (!file.exists()) return true
        file.walkBottomUp().forEach {
            runCatching {
                it.setWritable(true, false)
                it.setReadable(true, false)
                if (it.isDirectory) it.setExecutable(true, false)
            }
        }
        return file.deleteRecursively()
    }

    private fun writeAndroidSdkLicenses() {
        val licensesDir = File(sdkDir, "licenses").apply { mkdirs() }
        File(licensesDir, "android-sdk-license").writeText(
            listOf(
                "24333f8a63b6825ea9c5514f83c2829b004d1fee",
                "d56f5187479451eabf01fb78af6dfcb131a6481e",
                "8933bad161af4178b1185d1a37fbf41ea5269c55"
            ).joinToString("\n", postfix = "\n")
        )
    }

    private fun log(message: String) {
        mainHandler.post { onLog(message) }
    }

    private fun progress(percent: Int) {
        mainHandler.post { onProgress(percent) }
    }
}
