package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * StudioToolsIndex — dynamic tool index built from the GitHub Releases of
 * MobileStudio-AndroidIDE/StudioShellTools.
 *
 * Discovery is fully dynamic: tool names, checksums and versions are derived
 * from the release assets themselves — no tool catalog and no manifest.json
 * are needed anywhere:
 *  - every asset named "<tool>-android-arm64.tar.gz" becomes one tool
 *  - the release's "checksums.sha256" asset provides per-tarball SHA-256
 *  - the release tag doubles as the tool version (a new tag = an update)
 *
 * The index is cached at <filesDir>/tools/cache/tool-index.json with a 6h
 * TTL. A failed refresh falls back to the stale cache (network failure never
 * crashes); when even the cache is missing the index is empty and the shell
 * commands report it instead of guessing.
 */
object StudioToolsIndex {

    private const val TAG = "StudioToolsIndex"

    // ------------------------------------------------------------------
    // Central configuration — change these to move the repository.
    // ------------------------------------------------------------------
    const val GITHUB_OWNER = "MobileStudio-AndroidIDE"
    const val GITHUB_REPO = "StudioShellTools"
    const val RELEASES_API = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases"
    const val ASSET_SUFFIX = "-android-arm64.tar.gz"
    const val CHECKSUMS_ASSET = "checksums.sha256"

    /** The release index is refreshed at most every 6 hours. */
    const val INDEX_TTL_MS = 6L * 60L * 60L * 1000L

    private const val MAX_RELEASES = 30

    /** The checksums asset is fetched for at most this many newest releases. */
    private const val MAX_ENRICHED_RELEASES = 3

    /** One dynamically discovered tool. */
    data class ToolEntry(
        val name: String,
        val version: String,
        val size: Long,
        val url: String,
        val sha256: String,
        val releaseTag: String
    )

    /** Release index snapshot. [stale] = expired cache served after a failed refresh. */
    data class ToolIndex(
        val tools: List<ToolEntry>,
        val fetchedAt: Long,
        val stale: Boolean = false
    ) {
        val isEmpty: Boolean get() = tools.isEmpty()
        fun find(name: String): ToolEntry? = tools.firstOrNull { it.name == name }
    }

    /** One release's dynamically discovered tool entries + its checksums asset URL. */
    data class ReleaseInfo(
        val tag: String,
        val entries: List<ToolEntry>,
        val checksumsUrl: String
    )

    // ------------------------------------------------------------------
    // Pure parsing helpers (unit-testable)
    // ------------------------------------------------------------------

    /** "<tool>-android-arm64.tar.gz" -> tool name; non-tool assets -> null. */
    fun toolNameFromAsset(assetName: String): String? {
        if (!assetName.endsWith(ASSET_SUFFIX)) return null
        val name = assetName.removeSuffix(ASSET_SUFFIX)
        return name.takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("..") }
    }

    /** GitHub releases JSON -> per-release tool entries + metadata asset URLs. */
    fun parseReleasesJson(text: String): List<ReleaseInfo> = runCatching {
        val releases = JSONArray(text)
        val out = mutableListOf<ReleaseInfo>()
        for (r in 0 until releases.length()) {
            val release = releases.optJSONObject(r) ?: continue
            if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) continue
            val tag = release.optString("tag_name", "")
            val assets = release.optJSONArray("assets") ?: continue
            var checksumsUrl = ""
            val seen = linkedSetOf<String>()
            val entries = mutableListOf<ToolEntry>()
            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                val assetName = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)
                when {
                    assetName == CHECKSUMS_ASSET -> checksumsUrl = url
                    else -> toolNameFromAsset(assetName)?.let { name ->
                        if (url.isNotBlank() && seen.add(name)) {
                            entries += ToolEntry(name, tag, size, url, "", tag)
                        }
                    }
                }
            }
            out += ReleaseInfo(tag, entries, checksumsUrl)
        }
        out
    }.getOrDefault(emptyList())

    /** checksums.sha256 content -> asset name -> sha256 (invalid/traversal lines skipped). */
    fun parseChecksums(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val hash = line.substringBefore(' ').trim()
            val asset = line.substringAfter(hash).trim().removePrefix("*")
            val hexOk = hash.length == 64 && hash.all { it.isDigit() || it in 'a'..'f' }
            val nameOk = asset.isNotBlank() && !asset.contains('/') && !asset.contains("..")
            if (hexOk && nameOk) out[asset] = hash
        }
        return out
    }

    // ------------------------------------------------------------------
    // Cache (tool-index.json)
    // ------------------------------------------------------------------

    fun cacheFile(context: Context): File =
        File(StudioShellTools.toolsDir(context), "cache/tool-index.json")

    fun indexToJson(index: ToolIndex): String {
        val root = JSONObject()
        root.put("fetchedAt", index.fetchedAt)
        val arr = JSONArray()
        for (t in index.tools) {
            arr.put(
                JSONObject()
                    .put("name", t.name)
                    .put("version", t.version)
                    .put("size", t.size)
                    .put("url", t.url)
                    .put("sha256", t.sha256)
                    .put("releaseTag", t.releaseTag)
            )
        }
        root.put("tools", arr)
        return root.toString(2)
    }

    fun parseIndexJson(text: String): ToolIndex? = runCatching {
        val root = JSONObject(text)
        val fetchedAt = root.optLong("fetchedAt", 0L)
        val arr = root.optJSONArray("tools") ?: return@runCatching null
        val tools = mutableListOf<ToolEntry>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name", "")
            val url = obj.optString("url", "")
            if (name.isBlank() || url.isBlank()) continue
            tools += ToolEntry(
                name = name,
                version = obj.optString("version", ""),
                size = obj.optLong("size", 0L),
                url = url,
                sha256 = obj.optString("sha256", ""),
                releaseTag = obj.optString("releaseTag", "")
            )
        }
        ToolIndex(tools, fetchedAt)
    }.getOrNull()

    /** Cache-only load (no network — safe on the UI thread). Never null. */
    fun loadCached(context: Context): ToolIndex =
        runCatching {
            cacheFile(context).takeIf { it.isFile }?.let { parseIndexJson(it.readText()) }
        }.getOrNull() ?: ToolIndex(emptyList(), 0L, stale = true)

    // ------------------------------------------------------------------
    // Fetch (cache-first, TTL, never crashes on network failure)
    // ------------------------------------------------------------------

    fun fetch(context: Context, forceRefresh: Boolean = false): ToolIndex {
        val cache = cacheFile(context)
        val cached = runCatching {
            if (cache.isFile) parseIndexJson(cache.readText()) else null
        }.getOrNull()
        if (!forceRefresh && cached != null &&
            System.currentTimeMillis() - cached.fetchedAt < INDEX_TTL_MS
        ) {
            return cached
        }
        val fresh = runCatching { fetchFromNetwork() }.getOrNull()
        if (fresh != null && !fresh.isEmpty) {
            runCatching {
                cache.parentFile?.mkdirs()
                cache.writeText(indexToJson(fresh))
            }.onFailure { Log.w(TAG, "index cache write failed: ${it.message}") }
            return fresh
        }
        if (cached != null) return cached.copy(stale = true)
        return ToolIndex(emptyList(), 0L, stale = true)
    }

    private fun fetchFromNetwork(): ToolIndex? {
        val releasesJson = downloadText("$RELEASES_API?per_page=$MAX_RELEASES") ?: return null
        val releases = parseReleasesJson(releasesJson)
        if (releases.isEmpty()) return null

        val tools = mutableListOf<ToolEntry>()
        for ((i, release) in releases.withIndex()) {
            var entries = release.entries
            if (entries.isEmpty()) continue

            if (i < MAX_ENRICHED_RELEASES && release.checksumsUrl.isNotBlank()) {
                val hashes = downloadText(release.checksumsUrl)?.let { parseChecksums(it) } ?: emptyMap()
                if (hashes.isNotEmpty()) {
                    entries = entries.map { e ->
                        e.copy(sha256 = hashes["${e.name}$ASSET_SUFFIX"] ?: e.sha256)
                    }
                }
            }
            tools += entries
        }
        if (tools.isEmpty()) return null
        return ToolIndex(tools, System.currentTimeMillis())
    }

    /**
     * HTTP(S) GET returning the body as text, with the GitHub API headers.
     * Returns null on any failure. Shared with BuildToolCatalog so the whole
     * app uses one consistent fetch path.
     */
    fun downloadText(urlStr: String): String? = runCatching {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "MobileStudio-AndroidIDE")
        if (conn.responseCode != 200) {
            conn.disconnect()
            return@runCatching null
        }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        text
    }.getOrNull()
}
