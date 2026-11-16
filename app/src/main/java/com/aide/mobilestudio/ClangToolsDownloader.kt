package com.aide.mobilestudio

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads arm64 Android native clang-family tools (clangd, clang-tidy, cling)
 * from GitHub releases.
 *
 * Repo: MobileStudio-AndroidIDE/MobileStudio_AndroidIDE
 * Release tags are like: clang-tools-r19, clang-tools-r20, etc.
 * Assets: clangd-android-arm64.tar.gz, clang-tidy-android-arm64.tar.gz, cling-android-arm64.tar.gz
 *
 * Supports parallel downloads (max 3) and real-time version listing from GitHub API.
 */
object ClangToolsDownloader {
    private const val TAG = "ClangToolsDownloader"

    /** GitHub repo for clang tool releases */
    private const val GITHUB_OWNER = "MobileStudio-AndroidIDE"
    private const val GITHUB_REPO = "MobileStudio_AndroidIDE"

    /** GitHub API for releases listing */
    private const val RELEASES_API =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases?per_page=100"

    /** Directory where clang tools are stored */
    fun toolsDir(context: Context): File =
        File(context.filesDir, "toolchain/clang-tools").apply { mkdirs() }

    /** A single clang-family tool definition */
    data class ClangToolDef(
        val name: String,           // "clangd", "clang-tidy", "cling"
        val assetName: String,      // asset filename in the release
        val fileName: String,       // output filename
        val isExecutable: Boolean = true
    )

    /** Supported tools */
    val TOOLS = listOf(
        ClangToolDef("clangd", "clangd-android-arm64.tar.gz", "clangd"),
        ClangToolDef("clang-tidy", "clang-tidy-android-arm64.tar.gz", "clang-tidy"),
        ClangToolDef("cling", "cling-android-arm64.tar.gz", "cling")
    )

    /** Parsed GitHub release info */
    data class ReleaseInfo(
        val tagName: String,
        val publishedAt: String,
        val assets: List<AssetInfo>
    ) {
        data class AssetInfo(val name: String, val url: String, val size: Long)
    }

    /** Fetch available releases from GitHub API (real-time, not hardcoded). */
    fun fetchReleases(githubToken: String? = null): List<ReleaseInfo> {
        val releases = mutableListOf<ReleaseInfo>()
        try {
            val url = URL(RELEASES_API)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "MobileStudio")
            githubToken?.let { conn.setRequestProperty("Authorization", "Bearer $it") }

            val code = conn.responseCode
            if (code != 200) {
                Log.w(TAG, "GitHub API HTTP $code")
                return releases
            }

            conn.inputStream.use { input ->
                val json = input.bufferedReader().readText()
                val array = org.json.JSONArray(json)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft", false)) continue
                    val tagName = obj.optString("tag_name", "")
                    val publishedAt = obj.optString("published_at", "")
                    val assets = mutableListOf<ReleaseInfo.AssetInfo>()
                    val assetArray = obj.optJSONArray("assets")
                    if (assetArray != null) {
                        for (j in 0 until assetArray.length()) {
                            val a = assetArray.optJSONObject(j) ?: continue
                            assets.add(ReleaseInfo.AssetInfo(
                                a.optString("name", ""),
                                a.optString("browser_download_url", ""),
                                a.optLong("size", 0)
                            ))
                        }
                    }
                    if (assets.isNotEmpty()) {
                        releases.add(ReleaseInfo(tagName, publishedAt, assets))
                    }
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "fetchReleases failed: ${e.message}")
        }
        return releases
    }

    /** Find the download URL for a specific tool in a specific release. */
    fun findToolUrl(release: ReleaseInfo, toolName: String): String? {
        val tool = TOOLS.firstOrNull { it.name == toolName } ?: return null
        return release.assets.firstOrNull { it.name == tool.assetName }?.url
    }

    /** Download a specific tool from a specific URL. */
    fun downloadTool(
        url: String,
        toolName: String,
        context: Context,
        onProgress: ((Int, Int) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null
    ): File? {
        val tool = TOOLS.firstOrNull { it.name == toolName } ?: return null
        val dir = toolsDir(context)
        dir.mkdirs()
        val target = File(dir, tool.fileName)
        val archive = File(dir, "${tool.fileName}.tar.gz.part")
        val tmpDir = File(dir, "${tool.fileName}.extract")

        try {
            onLog?.invoke("[DOWNLOAD] $toolName from $url")
            downloadFile(url, archive, onLog)

            tmpDir.deleteRecursively()
            ToolchainArchiveInstaller.extractTarGz(archive, tmpDir) { }
            archive.delete()

            val extracted = File(tmpDir, "bin/${tool.fileName}").takeIf { it.isFile }
                ?: File(tmpDir, tool.fileName).takeIf { it.isFile }
                ?: throw Exception("$toolName not found in archive")

            if (!isArm64Elf(extracted)) {
                throw Exception("downloaded $toolName is not an arm64 ELF")
            }

            if (target.exists()) target.delete()
            if (!extracted.copyTo(target, overwrite = true).isFile) {
                throw Exception("could not place $toolName")
            }
            tmpDir.deleteRecursively()

            target.setExecutable(true, false)
            try { Os.chmod(target.absolutePath, 0b111_101_101) } catch (_: Throwable) {}

            onLog?.invoke("[OK] $toolName: ${target.length()} bytes")
            onProgress?.invoke(100, 100)
            return target
        } catch (e: Exception) {
            archive.delete()
            tmpDir.deleteRecursively()
            onLog?.invoke("[FAIL] $toolName: ${e.message}")
            return null
        }
    }

    /** Download multiple tools in parallel (max 3 at once). */
    fun downloadAllSelected(
        toolUrls: Map<String, String>,  // toolName -> downloadUrl
        context: Context,
        onProgress: ((String, Int, Int) -> Unit)? = null,
        onLog: ((String) -> Unit)? = null
    ): Map<String, Boolean> {
        val results = ConcurrentHashMap<String, Boolean>()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(3.coerceAtLeast(1))
        val futures = mutableMapOf<String, java.util.concurrent.Future<Boolean>>()

        for ((toolName, url) in toolUrls) {
            val callable = java.util.concurrent.Callable<Boolean> {
                val result = downloadTool(url, toolName, context, onLog = onLog) != null
                results[toolName] = result
                onProgress?.invoke(toolName, if (result) 100 else 0, 100)
                result
            }
            futures[toolName] = executor.submit(callable)
        }

        // Wait for all to complete
        for ((toolName, future) in futures) {
            try {
                future.get()
            } catch (e: Exception) {
                results[toolName] = false
                onLog?.invoke("[FAIL] $toolName: ${e.message}")
            }
        }

        executor.shutdown()
        return results
    }

    /** Check if a tool is already installed */
    fun isInstalled(context: Context, toolName: String): Boolean {
        val tool = TOOLS.firstOrNull { it.name == toolName } ?: return false
        val file = File(toolsDir(context), tool.fileName)
        return file.isFile && file.length() > 0L
    }

    /** Get path to an installed tool */
    fun toolPath(context: Context, toolName: String): File? {
        val tool = TOOLS.firstOrNull { it.name == toolName } ?: return null
        val file = File(toolsDir(context), tool.fileName)
        return if (file.isFile && file.length() > 0L) file else null
    }

    /** Remove a tool */
    fun removeTool(context: Context, toolName: String) {
        val tool = TOOLS.firstOrNull { it.name == toolName } ?: return
        File(toolsDir(context), tool.fileName).delete()
    }

    // ---------- private helpers ----------

    private fun downloadFile(urlStr: String, target: File, onLog: ((String) -> Unit)?) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true

        var connection = conn
        var redirects = 0
        while (redirects < 5) {
            val code = connection.responseCode
            if (code in 301..308) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                connection = URL(location).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                redirects++
            } else {
                break
            }
        }

        if (connection.responseCode != 200) {
            throw Exception("HTTP ${connection.responseCode} for $urlStr")
        }

        val totalBytes = connection.contentLength
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    onLog?.invoke("  ${totalRead / 1024}KB / ${totalBytes / 1024}KB")
                }
                output.flush()
            }
        }
        connection.disconnect()
    }

    private fun isArm64Elf(file: File): Boolean {
        if (!file.isFile || file.length() < 20) return false
        val hdr = ByteArray(20)
        val nread = runCatching { file.inputStream().use { it.read(hdr) } }.getOrDefault(0)
        if (nread < 20) return false
        if (hdr[0] != 0x7f.toByte() || hdr[1] != 'E'.code.toByte() ||
            hdr[2] != 'L'.code.toByte() || hdr[3] != 'F'.code.toByte()
        ) return false
        val le = hdr[5].toInt() == 1
        val machine = if (le) {
            (hdr[18].toInt() and 0xff) or ((hdr[19].toInt() and 0xff) shl 8)
        } else {
            ((hdr[18].toInt() and 0xff) shl 8) or (hdr[19].toInt() and 0xff)
        }
        return machine == 183 // EM_AARCH64
    }
}
