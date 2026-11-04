package com.aide.mobilestudio.extension

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * MobileStudio Extension Manager.
 *
 * Extensions are .msext (ZIP) packages distributed via GitHub Releases.
 * The registry is the GitHub Releases list of the official
 * MobileStudio-AndroidIDE/MobileStudio-Extensions repository (Releases API
 * with ETag conditional requests). Downloads are always SHA-256 verified
 * before installation. No auto-update: the user picks.
 */
class ExtensionManager(private val context: Context) {

    companion object {
        private const val TAG = "ExtensionManager"
        /**
         * Registry source of truth: GitHub Releases of the official extensions
         * repository (one Release = one extension version, only .msext assets
         * count). Responses are ETag-cached, so an unchanged registry costs a
         * single 304 Not Modified request.
         */
        private const val RELEASES_API =
            "https://api.github.com/repos/MobileStudio-AndroidIDE/MobileStudio-Extensions/releases?per_page=100"
        /**
         * Community registry: every public repo tagged with
         * [GitHubPublisher.REGISTRY_TOPIC]. GitHub's Search API is readable
         * without a token, so any publisher's Releases become discoverable the
         * moment they are published — no marketplace server in between.
         */
        private const val SEARCH_API = "https://api.github.com/search/repositories"
        private const val API = "https://api.github.com"
        private const val REGISTRY_TOPIC = GitHubPublisher.REGISTRY_TOPIC
        private const val REGISTRY_REPO = GitHubPublisher.REGISTRY_REPO
        private const val MAX_COMMUNITY_REPOS = 20
        private const val MAX_COMMUNITY_RELEASES = 120
        private const val REPO_CACHE_FILE = "repo_registry.json"
        private const val KEY_REPO_CACHE_AT = "repo_cache_at"
        private const val KEY_TOPIC_STAMP_AT = "topic_stamp_at"
        private const val PREFS = "extensions"
        private const val KEY_INSTALLED = "installed"
        private const val KEY_DISABLED = "disabled"
        private const val KEY_CLOSED_BANNERS = "closed_banners"
        private const val KEY_REPO_ETAG = "repo_etag"
        private const val MANIFEST_NAME = "extension.json"

        /** Release tag: `<full-extension-id>-v<version>` (matches the publish flow). */
        private val TAG_RE = Regex("^(.+)-v(\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?)$")
        private val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")
        private val VERSION_RE = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$")

        /** Files treated as text even without an extension (`file(1)`-style). */
        private val HEURISTIC_TEXT_NAMES = setOf(
            "license", "licence", "readme", "changelog", "notice", "authors",
            "contributors", "install", "copying", "makefile", "dockerfile",
            "gradlew", "cmakelists.txt", "gradle.properties", ".gitignore",
            ".gitattributes", ".editorconfig", "proguard-rules.pro", "todo"
        )
        private val HEURISTIC_TEXT_PREFIXES = listOf("license", "readme", "notice", "authors", "changelog")

        @Volatile private var instance: ExtensionManager? = null
        fun get(context: Context): ExtensionManager =
            instance ?: synchronized(this) { instance ?: ExtensionManager(context.applicationContext).also { instance = it } }
        // ------------------------- registry parsing (pure, testable) ----------------
        // These are Context-free so the cross-repo merge rules can be unit-tested.

        internal fun parseReleases(text: String): List<RepoEntry> {
            val best = LinkedHashMap<String, RepoEntry>()
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val rel = arr.optJSONObject(i) ?: continue
                if (rel.optBoolean("draft") || rel.optBoolean("prerelease")) continue
                val tag = rel.optString("tag_name")
                val assets = rel.optJSONArray("assets") ?: continue
                var assetUrl = ""
                var assetSize = 0L
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    if (asset.optString("name").endsWith(".msext", ignoreCase = true)) {
                        assetUrl = asset.optString("browser_download_url")
                        assetSize = asset.optLong("size")
                        break
                    }
                }
                if (assetUrl.isBlank()) continue

                val meta = try { JSONObject(rel.optString("body")) } catch (_: Exception) { null }
                var id = meta?.optString("id") ?: ""
                var version = meta?.optString("version") ?: ""
                val fromTag = TAG_RE.matchEntire(tag)
                if (!ID_RE.matches(id) || !VERSION_RE.matches(version)) {
                    id = fromTag?.groupValues?.get(1) ?: ""
                    version = fromTag?.groupValues?.get(2) ?: ""
                }
                if (!ID_RE.matches(id) || !VERSION_RE.matches(version)) continue

                val entry = RepoEntry(
                    id = id,
                    name = (meta?.optString("name") ?: "").ifEmpty { tag },
                    version = version,
                    description = meta?.optString("description") ?: "",
                    author = (meta?.optString("author") ?: "").ifEmpty {
                        rel.optJSONObject("author")?.optString("login") ?: ""
                    },
                    download = assetUrl,
                    size = if (assetSize > 0) assetSize else meta?.optLong("size") ?: 0L,
                    sha256 = meta?.optString("sha256") ?: "",
                    minStudioVersion = (meta?.optString("minStudioVersion") ?: "").ifEmpty {
                        (meta?.optString("minMobileStudioVersion") ?: "").ifEmpty { "1.0.0" }
                    },
                    permissions = stringList(meta?.optJSONArray("permissions")),
                    languages = stringList(meta?.optJSONArray("languages"))
                )
                val prev = best[entry.id]
                if (prev == null || isNewerVersion(entry.version, prev.version)) best[entry.id] = entry
            }
            return best.values.toList()
        }

        private fun stringList(arr: JSONArray?): List<String> {
            val out = mutableListOf<String>()
            if (arr != null) for (j in 0 until arr.length()) out.add(arr.optString(j))
            return out
        }

        internal fun isNewerVersion(candidate: String, current: String): Boolean {
            val a = candidate.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** Merges entry lists, keeping the newest version per extension id.
         *  Order matters: earlier lists win version ties, so the official repo
         *  is passed first and a squatted community copy cannot shadow it. */
        internal fun mergeEntries(vararg lists: List<RepoEntry>): List<RepoEntry> {
            val best = LinkedHashMap<String, RepoEntry>()
            for (list in lists) for (e in list) {
                val prev = best[e.id]
                if (prev == null || isNewerVersion(e.version, prev.version)) best[e.id] = e
            }
            return best.values.toList()
        }

    }

    // ------------------------- installed state -------------------------

    data class InstalledExtension(
        val manifest: ExtensionManifest,
        val dir: File,
        val enabled: Boolean
    )

    fun installed(): List<InstalledExtension> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_INSTALLED, null) ?: return emptyList()
        val disabled = prefs.getStringSet(KEY_DISABLED, emptySet()) ?: emptySet()
        val out = mutableListOf<InstalledExtension>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val dir = File(obj.optString("dir"))
                val manifest = readManifest(dir) ?: continue
                out.add(InstalledExtension(manifest, dir, obj.optString("id") !in disabled))
            }
        } catch (e: Exception) {
            Log.w(TAG, "installed() parse failed", e)
        }
        return out
    }

    fun isEnabled(id: String): Boolean = installed().firstOrNull { it.manifest.id == id }?.enabled ?: false
    fun isInstalled(id: String): Boolean = installed().any { it.manifest.id == id }

    fun findInstalledForLanguage(languageId: String): InstalledExtension? =
        installed().firstOrNull { it.enabled && it.manifest.languages.any { l -> l.equals(languageId, ignoreCase = true) } }

    fun findInstalledForFile(file: File, contentSample: String? = null): InstalledExtension? {
        val name = file.name.lowercase()
        val ext = name.substringAfterLast('.', "")
        for (e in installed()) {
            if (!e.enabled) continue
            val langs = e.manifest.languages
            val matchesExt = langs.any { l ->
                when (l.lowercase()) {
                    "shell", "bash", "sh" -> ext == "sh" || ext == "bash" || ext == "zsh"
                    "javascript" -> ext == "js" || ext == "mjs"
                    "typescript" -> ext == "ts" || ext == "tsx"
                    "markdown" -> ext == "md" || ext == "markdown"
                    "kotlin" -> ext == "kt" || ext == "kts"
                    "cpp", "c++" -> ext == "cpp" || ext == "cc" || ext == "c" || ext == "h" || ext == "hpp"
                    else -> ext == l.lowercase()
                }
            }
            if (matchesExt) return e
        }
        // Heuristic: extension-less files whose content is text (shebang / known names)
        if (ext.isEmpty() || ext == file.name) {
            val shebang = contentSample?.lineSequence()?.firstOrNull()?.trim()
            if (shebang != null && shebang.startsWith("#!")) {
                return installed().firstOrNull { e ->
                    e.enabled && e.manifest.languages.any { l -> l.lowercase() in setOf("shell", "bash", "sh") }
                }
            }
            val base = name.substringBeforeLast('.')
            if (base in HEURISTIC_TEXT_NAMES || HEURISTIC_TEXT_PREFIXES.any { base.startsWith(it) }) {
                return null // text-based but no extension claims it; core opens as text
            }
        }
        return null
    }

    // ------------------------- enable / disable / uninstall -------------------------

    fun setEnabled(id: String, enabled: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val disabled = prefs.getStringSet(KEY_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val raw = prefs.getString(KEY_INSTALLED, null) ?: return false
        val arr = JSONArray(raw)
        val newArr = JSONArray()
        var found = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.optString("id") == id) {
                found = true
                if (enabled) disabled.remove(id) else disabled.add(id)
            }
            newArr.put(obj)
        }
        if (!found) return false
        // Ordering matters: dispatchDisable must run while the extension is still
        // marked enabled, otherwise ExtensionHost cannot load the instance it has
        // to notify. dispatchEnable runs after, since it needs the new flag.
        if (enabled) {
            // Drop whatever instance was cached while disabled (it may be stale or
            // crashed) so onEnable lands on a freshly loaded one.
            runCatching { ExtensionHost.restart(id) }
            prefs.edit().putString(KEY_INSTALLED, newArr.toString())
                .putStringSet(KEY_DISABLED, disabled).apply()
            runCatching { ExtensionHost.dispatchEnable(id) }
        } else {
            runCatching { ExtensionHost.dispatchDisable(id) }
            prefs.edit().putString(KEY_INSTALLED, newArr.toString())
                .putStringSet(KEY_DISABLED, disabled).apply()
        }
        return true
    }

    fun uninstall(id: String): Boolean {
        val entry = installed().firstOrNull { it.manifest.id == id } ?: return false
        // Give the extension a chance to clean up (isolated)
        try { ExtensionHost.dispatchUninstall(id) } catch (_: Exception) {}
        val ok = entry.dir.deleteRecursively()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_INSTALLED, null) ?: return ok
        val arr = JSONArray(raw)
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("id") != id) newArr.put(arr.getJSONObject(i))
        }
        val disabled = prefs.getStringSet(KEY_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        disabled.remove(id)
        prefs.edit().putString(KEY_INSTALLED, newArr.toString())
            .putStringSet(KEY_DISABLED, disabled).apply()
        return ok
    }

    // ------------------------- banner state -------------------------

    /** Marks a banner closed so it is not shown again for the same file while open. */
    fun markBannerClosed(extensionId: String, filePath: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val closed = prefs.getStringSet(KEY_CLOSED_BANNERS, emptySet())?.toMutableSet() ?: mutableSetOf()
        closed.add("$extensionId::$filePath")
        prefs.edit().putStringSet(KEY_CLOSED_BANNERS, closed).apply()
    }

    fun isBannerClosed(extensionId: String, filePath: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val closed = prefs.getStringSet(KEY_CLOSED_BANNERS, emptySet()) ?: emptySet()
        return "$extensionId::$filePath" in closed
    }

    // ------------------------- repository (Phase 3) -------------------------

    data class RepoEntry(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val author: String,
        val download: String,
        val size: Long,
        val sha256: String,
        val minStudioVersion: String,
        val permissions: List<String>,
        val languages: List<String>
    ) {
        fun versionCode(): List<Int> = version.split(".").map { it.toIntOrNull() ?: 0 }
    }

    private val repoCache = AtomicReference<List<RepoEntry>>(emptyList())
    private val fetching = AtomicBoolean(false)
    /** Official repo entries only, kept apart from the merged [repoCache]. */
    private val officialCache = AtomicReference<List<RepoEntry>>(emptyList())

    init {
        // Warm the offline cache in the background so a cold start is not blank.
        Thread {
            val cached = readRepoDiskCache()
            if (cached.isNotEmpty()) repoCache.compareAndSet(emptyList(), cached)
        }.start()
    }

    fun cachedRepo(): List<RepoEntry> = repoCache.get()

    /**
     * Fetches the Releases registry (cached; ETag conditional request; on
     * network failure the last known list is served instead of going blank).
     *
     * The registry is the union of
     *  1. the official repository's Releases, and
     *  2. every public repo tagged with [REGISTRY_TOPIC], discovered through
     *     GitHub Search — so a publisher's Release is listed the moment it is
     *     published, without any marketplace server in between.
     */
    fun refreshRepo(force: Boolean = false, onDone: (List<RepoEntry>?, String?) -> Unit) {
        if (!force && repoCache.get().isNotEmpty()) { onDone(repoCache.get(), null); return }
        if (!fetching.compareAndSet(false, true)) { onDone(null, "이미 가져오는 중"); return }
        Thread {
            var result: List<RepoEntry>? = null
            var error: String? = null
            try {
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                // Only send If-None-Match when the in-memory cache can answer a 304
                val etag = if (!force && officialCache.get().isNotEmpty())
                    prefs.getString(KEY_REPO_ETAG, null) else null

                var official: List<RepoEntry>? = null
                var officialError: String? = null
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(RELEASES_API).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.setRequestProperty("Accept", "application/vnd.github+json")
                    conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    conn.setRequestProperty("User-Agent", "MobileStudio")
                    if (etag != null) conn.setRequestProperty("If-None-Match", etag)
                    when (val code = conn.responseCode) {
                        200 -> {
                            val text = conn.inputStream.bufferedReader().readText()
                            official = parseReleases(text)
                            officialCache.set(official)
                            val newEtag = conn.getHeaderField("ETag")
                            prefs.edit().apply {
                                if (newEtag != null) putString(KEY_REPO_ETAG, newEtag)
                                else remove(KEY_REPO_ETAG)
                            }.apply()
                        }
                        304 -> official = officialCache.get()   // not modified
                        else -> officialError = "HTTP $code"
                    }
                } catch (e: Exception) {
                    officialError = e.message ?: "네트워크 오류"
                    Log.w(TAG, "refreshRepo failed", e)
                } finally {
                    conn?.disconnect()
                }

                // Community is best effort: a search failure must never blank the list.
                val community = runCatching { fetchCommunityReleases() }.getOrDefault(emptyList())
                val merged = mergeEntries(official ?: officialCache.get(), community)

                if (merged.isNotEmpty()) {
                    repoCache.set(merged)
                    result = merged
                    writeRepoDiskCache(merged)
                } else {
                    error = officialError
                }
                prefs.edit().putLong(KEY_REPO_CACHE_AT, System.currentTimeMillis()).apply()
                // Self-healing: make sure the signed-in publisher's own repo is
                // indexed, so their Releases show up without a re-publish.
                stampPublisherTopic(prefs)
            } catch (e: Exception) {
                error = e.message ?: "네트워크 오류"
                Log.w(TAG, "refreshRepo failed", e)
            }
            if (result == null) {
                // Network failure: serve the last known list instead of going blank
                val cached = repoCache.get()
                if (cached.isNotEmpty()) {
                    result = cached
                    error = null
                }
            }
            fetching.set(false)
            android.os.Handler(context.mainLooper).post { onDone(result, error) }
        }.start()
    }

    // ------------------------- community registry (GitHub as the database) ------

    /** Throttled to once a day: guarantees the signed-in publisher's repo carries
     *  the registry topic so their Releases are discoverable without re-publishing. */
    private fun stampPublisherTopic(prefs: android.content.SharedPreferences) {
        if (!GitHubPublisher.hasToken(context)) return
        val last = prefs.getLong(KEY_TOPIC_STAMP_AT, 0L)
        if (System.currentTimeMillis() - last < 24 * 60 * 60 * 1000L) return
        prefs.edit().putLong(KEY_TOPIC_STAMP_AT, System.currentTimeMillis()).apply()
        val login = GitHubAuth.login(context).ifBlank {
            runCatching { GitHubPublisher.whoAmI(context).message }.getOrDefault("")
        }
        if (login.isBlank()) return
        if (!GitHubPublisher.repoExists(context, login, GitHubPublisher.REGISTRY_REPO)) return
        GitHubPublisher.ensureTopic(context, login, GitHubPublisher.REGISTRY_REPO)
        Log.i(TAG, "registry topic ensured on $login/${GitHubPublisher.REGISTRY_REPO}")
    }

    /**
     * Collects Releases from every public repo carrying [REGISTRY_TOPIC].
     * The caller's PAT is sent when present (30/min search limit instead of
     * 10/min); without a token the Search API is still readable anonymously.
     */
    private fun fetchCommunityReleases(): List<RepoEntry> {
        val combined = JSONArray()
        for (fullName in searchRegistryRepos()) {
            val text = httpGet("$API/repos/$fullName/releases?per_page=30") ?: continue
            try {
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { combined.put(it) }
            } catch (_: Exception) { }
            if (combined.length() >= MAX_COMMUNITY_RELEASES) break
        }
        return if (combined.length() == 0) emptyList() else parseReleases(combined.toString())
    }

    private fun searchRegistryRepos(): List<String> {
        val q = URLEncoder.encode("topic:$REGISTRY_TOPIC", "UTF-8")
        val text = httpGet("$SEARCH_API?q=$q&per_page=$MAX_COMMUNITY_REPOS&sort=updated") ?: return emptyList()
        return try {
            val items = JSONObject(text).optJSONArray("items") ?: return emptyList()
            (0 until items.length())
                .mapNotNull { items.optJSONObject(it)?.optString("full_name")?.takeIf { n -> n.isNotBlank() } }
                // the official repo is fetched separately with ETag caching
                .filter { it.substringAfter('/') != REGISTRY_REPO }
        } catch (_: Exception) { emptyList() }
    }

    /** GET with GitHub headers; sends the publisher PAT when one exists. */
    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "MobileStudio")
            val token = if (GitHubPublisher.hasToken(context))
                runCatching { GitHubAuth.token(context) }.getOrNull() else null
            if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (_: Exception) { null }
        finally { conn?.disconnect() }
    }

    // ------------------------- registry disk cache -------------------------

    /** Persists the merged registry so a cold start still lists extensions offline. */
    private fun writeRepoDiskCache(entries: List<RepoEntry>) {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(JSONObject()
                    .put("id", e.id).put("name", e.name).put("version", e.version)
                    .put("description", e.description).put("author", e.author)
                    .put("download", e.download).put("size", e.size).put("sha256", e.sha256)
                    .put("minStudioVersion", e.minStudioVersion)
                    .put("permissions", JSONArray(e.permissions))
                    .put("languages", JSONArray(e.languages)))
            }
            File(context.filesDir, REPO_CACHE_FILE).writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "writeRepoDiskCache failed", e)
        }
    }

    private fun readRepoDiskCache(): List<RepoEntry> = try {
        val f = File(context.filesDir, REPO_CACHE_FILE)
        if (!f.isFile) emptyList() else parseReleases(f.readText())
    } catch (_: Exception) { emptyList() }

    /**
     * Builds registry entries from the GitHub Releases payload.
     *
     * Rules (kept in sync with the server registry):
     *  - draft/prerelease Releases are not listed
     *  - only .msext assets are extension packages
     *  - the Release body carries extension.json as JSON; when the body is
     *    plain text the tag `<id>-v<version>` is the fallback
     *  - duplicate ids collapse to the highest SemVer
     */
    /** Updates available: installed version < repo version for the same id. */
    fun updates(): List<Pair<InstalledExtension, RepoEntry>> {
        val repo = repoCache.get()
        if (repo.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<InstalledExtension, RepoEntry>>()
        for (inst in installed()) {
            val entry = repo.firstOrNull { it.id == inst.manifest.id } ?: continue
            val newV = entry.versionCode()
            val curV = inst.manifest.versionCode()
            val newer = newV.indices.any { i -> (newV[i] > (curV.getOrElse(i) { 0 })) }
            if (newer) out.add(inst to entry)
        }
        return out
    }

    // ------------------------- install (Phase 2) -------------------------

    /**
     * Downloads, validates and installs [entry].
     *
     * Everything that can invalidate a package is decided by
     * [ExtensionInstallGate] before anything is committed to the installed
     * extensions directory. When the extension asks for permissions the user
     * has not granted yet, [onPermissionsRequired] is invoked with a `proceed`
     * callback; nothing is installed until that answers `true`.
     */
    fun install(
        entry: RepoEntry,
        onProgress: (String) -> Unit,
        onDone: (Boolean, String) -> Unit,
        onPermissionsRequired: ((ExtensionManifest, List<String>, (Boolean) -> Unit) -> Unit)? = null,
    ) {
        Thread {
            val staging = File(context.cacheDir, "ext-stage-${entry.id}-${System.currentTimeMillis()}")
            try {
                onProgress("다운로드 중: ${entry.name}")
                val tmp = File(context.cacheDir, "ext-${entry.id}-${System.currentTimeMillis()}.msext")
                downloadFile(entry.download, tmp, onProgress)

                onProgress("SHA-256 계산 중...")
                val downloadedSha256 = sha256Of(tmp)

                onProgress("검증 중: ${entry.name}")
                staging.mkdirs()
                val extractedBytes = try {
                    extractMsext(tmp, staging)
                } catch (e: SecurityException) {
                    staging.deleteRecursively(); tmp.delete()
                    onDone(false, "패키지 거부: ${e.message}"); return@Thread
                }
                tmp.delete()

                val manifest = readManifest(staging)
                val hasDex = File(staging, "classes.dex").isFile

                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val granted = prefs.getStringSet(permissionKey(entry.id), emptySet())?.toSet()
                    ?: emptySet()

                val verdict = ExtensionInstallGate.evaluate(
                    host = ExtensionInstallGate.Host(appVersionName = appVersionName()),
                    manifest = manifest,
                    pkg = ExtensionInstallGate.Package(
                        downloadedSha256 = downloadedSha256,
                        sizeBytes = entry.size,
                        extractedBytes = extractedBytes,
                        hasDex = hasDex,
                    ),
                    registryId = entry.id,
                    registrySha256 = entry.sha256,
                    suspended = ExtensionMarketplace.get(context).isDownloadSuspended(entry.id),
                    suspendedReason = ExtensionMarketplace.get(context).suspensionReason(entry.id),
                    alreadyGranted = granted,
                )
                if (!verdict.ok) {
                    staging.deleteRecursively()
                    onDone(false, verdict.errors.joinToString("\n"))
                    return@Thread
                }
                if (manifest == null) {
                    staging.deleteRecursively()
                    onDone(false, "extension.json 없음 또는 손상")
                    return@Thread
                }

                val commit: (approved: Set<String>) -> Unit = { approved ->
                    Thread {
                        try {
                            commitInstall(entry.id, manifest, staging, approved)
                            onProgress("설치 완료: ${manifest.name} ${manifest.version}")
                            onDone(true, "${manifest.name} ${manifest.version} 설치됨")
                        } catch (e: Exception) {
                            Log.e(TAG, "install commit failed", e)
                            staging.deleteRecursively()
                            onDone(false, "설치 실패: ${e.message}")
                        }
                    }.start()
                }

                if (verdict.needsApproval && onPermissionsRequired != null) {
                    onProgress("권한 승인 필요: ${verdict.newPermissions.joinToString(", ")}")
                    onPermissionsRequired(manifest, verdict.newPermissions) { proceed ->
                        if (proceed) {
                            commit(granted + verdict.newPermissions)
                        } else {
                            staging.deleteRecursively()
                            onDone(false, "권한을 거부하여 설치를 취소했습니다")
                        }
                    }
                } else {
                    // No handler wired in (headless path): proceed, recording
                    // nothing the user did not already have.
                    commit(granted)
                }
            } catch (e: Exception) {
                Log.e(TAG, "install failed", e)
                staging.deleteRecursively()
                onDone(false, "설치 실패: ${e.message}")
            }
        }.start()
    }

    /** Moves a validated staging directory into place and records it. */
    private fun commitInstall(
        extensionId: String,
        manifest: ExtensionManifest,
        staging: File,
        approved: Set<String>,
    ) {
        val extDir = File(File(context.filesDir, "extensions"), extensionId)
        if (extDir.exists()) extDir.deleteRecursively()
        extDir.mkdirs()
        if (!staging.renameTo(extDir)) {
            // rename across mounts can fail; fall back to a copy
            staging.copyRecursively(extDir, overwrite = true)
            staging.deleteRecursively()
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString(KEY_INSTALLED, null) ?: "[]")
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("id") != extensionId) newArr.put(arr.getJSONObject(i))
        }
        newArr.put(
            JSONObject()
                .put("id", manifest.id)
                .put("dir", extDir.absolutePath)
                .put("version", manifest.version)
        )
        val disabled = prefs.getStringSet(KEY_DISABLED, emptySet())?.toMutableSet() ?: mutableSetOf()
        disabled.remove(manifest.id)
        prefs.edit()
            .putString(KEY_INSTALLED, newArr.toString())
            .putStringSet(KEY_DISABLED, disabled)
            .putStringSet(permissionKey(manifest.id), approved)
            .apply()
    }

    private fun permissionKey(id: String) = "granted_perms_$id"

    /** This build's versionName, or "" when it cannot be read. */
    private fun appVersionName(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun downloadFile(urlStr: String, target: File, onProgress: (String) -> Unit) {
        var connection = URL(urlStr).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        var redirects = 0
        while (redirects < 5) {
            val code = connection.responseCode
            if (code in 301..308) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                connection = URL(location).openConnection() as HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 120_000
                redirects++
            } else break
        }
        if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}: $urlStr")
        val total = connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(target).use { out ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var totalRead = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    totalRead += read
                    if (total > 0) onProgress("다운로드 중: ${totalRead * 100 / total}%")
                }
            }
        }
        connection.disconnect()
    }

    /**
     * Unzips [archive] into [dest], returning the total number of bytes written.
     *
     * Defence in depth on top of [extractMsext]'s zip-slip check: the total
     * written is capped so a decompression bomb aborts while unpacking instead
     * of filling the device's storage first and being noticed afterwards. Every
     * call site benefits, including the local-package install path.
     */
    @Throws(java.io.IOException::class, SecurityException::class)
    fun extractMsext(archive: File, dest: File): Long {
        var total = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val target = File(dest, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    if (!target.canonicalPath.startsWith(dest.canonicalPath)) {
                        throw SecurityException("Zip path escape: ${entry.name}")
                    }
                    if (entry.size > 0 && entry.size > ExtensionInstallGate.MAX_EXTRACTED_BYTES) {
                        throw SecurityException(
                            "Entry too large: ${entry.name} (${entry.size} bytes)")
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > ExtensionInstallGate.MAX_EXTRACTED_BYTES) {
                                throw SecurityException(
                                    "Package expands to more than ${ExtensionInstallGate.MAX_EXTRACTED_BYTES} bytes")
                            }
                            out.write(buf, 0, n)
                        }
                    }
                }
                entry = zip.nextEntry
            }
        }
        return total
    }

    /**
     * Packs a project folder (extension.json + sources/) into a .msext package
     * (ZIP) plus a companion .msextinf metadata file. Used by the in-app
     * "Build .msext" action on extension projects.
     */
    fun packMsext(projectDir: File, outputFile: File, onProgress: (String) -> Unit): Pair<File, File>? {
        val manifestFile = File(projectDir, "extension.json")
        if (!manifestFile.isFile) throw Exception("extension.json 없음 — 확장 프로젝트 형식이 아닙니다")
        val manifest = readManifest(projectDir) ?: throw Exception("extension.json 손상")
        onProgress("패키징 중: ${manifest.name}")

        // Copy extension.json + all non-build files into the package
        val packageDir = File(projectDir, "build/.msext-work")
        if (packageDir.exists()) packageDir.deleteRecursively()
        packageDir.mkdirs()
        manifestFile.copyTo(File(packageDir, "extension.json"), overwrite = true)
        // README.md is a required member on the publish side (server msext.py
        // REQUIRED_MEMBERS), so packing without it would build a package that
        // can never be submitted.
        File(projectDir, "README.md").takeIf { it.isFile }
            ?.copyTo(File(packageDir, "README.md"), overwrite = true)
        listOf("resources", "icons", "templates", "tools").forEach { sub ->
            val src = File(projectDir, sub)
            if (src.isDirectory) src.copyRecursively(File(packageDir, sub), overwrite = true)
        }
        // Include compiled dex if present. d8 emits to different folders
        // depending on how it was invoked, so probe the common ones instead of
        // only build/classes.dex — otherwise a code extension is silently
        // packaged without its implementation and fails to load at runtime.
        val dexCandidates = listOf(
            File(projectDir, "build/classes.dex"),
            File(projectDir, "classes.dex"),
            File(projectDir, "build/dex/classes.dex"),
            File(projectDir, "build/outputs/dex/debug/classes.dex")
        )
        val dex = dexCandidates.firstOrNull { it.isFile }
        if (dex == null) {
            // Metadata-only extensions (blank entryPoint) legitimately ship no code.
            if (manifest.entryPoint.isNotBlank()) {
                throw Exception("classes.dex 없음 — 먼저 확장을 빌드하세요 (build/classes.dex)")
            }
        } else {
            dex.copyTo(File(packageDir, "classes.dex"), overwrite = true)
        }

        // ZIP it
        // Package name follows the Release asset convention: <full-extension-id>-v<version>.msext
        outputFile.mkdirs()
        val msextFile = File(outputFile, "${manifest.id}-v${manifest.version}.msext")
        ZipOutputStream(FileOutputStream(msextFile).buffered()).use { zip ->
            packageDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val name = packageDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                val entry = java.util.zip.ZipEntry(name)
                zip.putNextEntry(entry)
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        packageDir.deleteRecursively()

        // .msextinf companion (text-based metadata: name + description ≤100 chars)
        val infFile = File(msextFile.parentFile, msextFile.name + "inf")
        val desc = if (manifest.description.length > 100) manifest.description.take(100) else manifest.description
        infFile.writeText(
            "# MobileStudio Extension Info (.msextinf)\n" +
                "id=${manifest.id}\n" +
                "name=${manifest.name}\n" +
                "version=${manifest.version}\n" +
                "author=${manifest.author}\n" +
                "description=$desc\n"
        )
        onProgress("패키징 완료: ${msextFile.name}")
        return msextFile to infFile
    }

    /**
     * The compile-time stub of the extension API, unpacked from assets on first
     * use. Extension sources are compiled against this; at runtime PathClassLoader
     * delegates to the app first, so the interface it was compiled against and
     * the one it is cast to are the same class. The stub itself is never loaded.
     */
    fun apiStubJar(): File {
        val out = File(context.cacheDir, "ext-api/extension-api.jar")
        if (out.isFile && out.length() > 0) return out
        out.parentFile?.mkdirs()
        context.assets.open("extensions/extension-api.jar").use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        }
        return out
    }

    /** Compiles an extension project into build/classes.dex. */
    fun buildExtension(
        projectDir: File,
        onProgress: (String) -> Unit,
    ): ExtensionBuilder.Result {
        val manifest = readManifest(projectDir)
        val builder = ExtensionBuilder(
            javac = ExtensionToolchain.javac(context),
            androidJar = ExtensionToolchain.androidJar(context),
            d8 = ExtensionToolchain.d8(context),
        )
        return builder.build(projectDir, apiStubJar(), manifest?.entryPoint, onProgress)
    }

    fun readManifest(dir: File): ExtensionManifest? {
        val f = File(dir, MANIFEST_NAME)
        if (!f.isFile) return null
        return try {
            val o = JSONObject(f.readText())
            val perms = mutableListOf<String>()
            o.optJSONArray("permissions")?.let { p -> for (j in 0 until p.length()) perms.add(p.getString(j)) }
            val langs = mutableListOf<String>()
            o.optJSONArray("languages")?.let { l -> for (j in 0 until l.length()) langs.add(l.getString(j)) }
            ExtensionManifest(
                id = o.getString("id"),
                name = o.getString("name"),
                version = o.getString("version"),
                author = o.optString("author"),
                description = o.optString("description"),
                minStudioVersion = o.optString("minStudioVersion", o.optString("minMobileStudioVersion", "1.0.0")),
                maxStudioVersion = o.optString("maxStudioVersion", "*"),
                entryPoint = o.optString("entryPoint"),
                permissions = perms,
                languages = langs,
                extensionApiVersion = o.optInt("extensionApiVersion", ExtensionApi.API_VERSION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "manifest parse failed: ${dir.name}", e)
            null
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) digest.update(buf, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------- local .msext packages -------------------------

    /** A .msext file found on the device: absolute path + size in bytes. */
    data class LocalPackage(val file: File, val size: Long) {
        val path: String get() = file.absolutePath
        val displayName: String get() = file.name.removeSuffix(".msext").removeSuffix(".MSEXT")
    }

    /**
     * Finds .msext files under every root the app can actually read, so the
     * user can install a package without leaving the app for a file manager.
     * Only the .msext extension counts; walk depth and result count are capped
     * so a pathological tree can never stall the caller.
     */
    fun scanLocalPackages(maxResults: Int = 200): List<LocalPackage> {
        val roots = linkedSetOf<File>()
        // App-private storage — Build .msext output and downloaded packages
        context.getExternalFilesDir(null)?.let { roots.add(it) }
        context.filesDir?.let { roots.add(it) }
        // Shared storage (MANAGE_EXTERNAL_STORAGE is requested in the manifest)
        val shared = runCatching { android.os.Environment.getExternalStorageDirectory() }.getOrNull()
        if (shared != null) {
            listOf("Download", "Documents", "MobileStudio", "ext", "extensions", "Download/MobileStudio")
                .forEach { sub -> roots.add(File(shared, sub)) }
            // Top-level of shared storage, files only (cheap)
            shared.listFiles()?.filter { it.isFile && it.name.endsWith(".msext", ignoreCase = true) }
                ?.forEach { roots.add(it) }
        }

        val skipDirs = setOf(".git", ".gradle", "build", ".cxx", "node_modules", ".idea", "captures")
        val out = ArrayList<LocalPackage>()
        val seen = HashSet<String>()
        for (root in roots) {
            if (out.size >= maxResults) break
            if (root.isFile) {
                addIfMsext(root, out, seen, maxResults)
            } else {
                collectMsext(root, skipDirs, out, seen, maxResults, 0)
            }
        }
        return out.sortedBy { it.path.lowercase() }
    }

    private fun collectMsext(
        dir: File,
        skipDirs: Set<String>,
        out: MutableList<LocalPackage>,
        seen: MutableSet<String>,
        maxResults: Int,
        depth: Int
    ) {
        if (depth > 6 || out.size >= maxResults || !dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (out.size >= maxResults) return
            if (f.isDirectory) {
                if (skipDirs.contains(f.name)) continue
                collectMsext(f, skipDirs, out, seen, maxResults, depth + 1)
            } else if (f.isFile) {
                addIfMsext(f, out, seen, maxResults)
            }
        }
    }

    private fun addIfMsext(f: File, out: MutableList<LocalPackage>, seen: MutableSet<String>, maxResults: Int) {
        if (out.size >= maxResults) return
        if (!f.name.endsWith(".msext", ignoreCase = true)) return
        if (!seen.add(f.absolutePath)) return
        val size = f.length()
        if (size <= 0L || size > 64L * 1024 * 1024) return
        out.add(LocalPackage(f, size))
    }

    /**
     * Installs a .msext package already present on the device.
     *
     * Runs the same [ExtensionInstallGate] as a registry download — byte limits,
     * decompression-bomb ratio, Extension API / studio version compatibility,
     * entryPoint<->classes.dex consistency, suspension and permission approval.
     * Without this, "기기에서 설치" would be a way to slip arbitrary code past
     * every check the download path enforces. The one thing a local file has no
     * counterpart for is the server's scan record, so that SHA-256 pin is blank.
     */
    fun installFromFile(
        file: File,
        onProgress: (String) -> Unit,
        onDone: (Boolean, String) -> Unit,
        onPermissionsRequired: ((ExtensionManifest, List<String>, (Boolean) -> Unit) -> Unit)? = null,
    ) {
        Thread {
            val staging = File(context.cacheDir, "ext-local-${System.currentTimeMillis()}")
            try {
                if (!file.isFile) { onDone(false, "파일 없음: ${file.name}"); return@Thread }
                if (!file.name.endsWith(".msext", ignoreCase = true)) {
                    onDone(false, ".msext 파일만 설치할 수 있습니다"); return@Thread
                }

                onProgress("패키지 검증 중: ${file.name}")
                val packageSha256 = sha256Of(file)
                val sizeBytes = file.length()

                staging.mkdirs()
                val extractedBytes = try {
                    extractMsext(file, staging)
                } catch (e: SecurityException) {
                    staging.deleteRecursively()
                    onDone(false, "패키지 거부: ${e.message}"); return@Thread
                }

                val manifest = readManifest(staging)
                if (manifest == null) {
                    staging.deleteRecursively()
                    onDone(false, "extension.json 없음 또는 손상"); return@Thread
                }
                val id = manifest.id
                val hasDex = File(staging, "classes.dex").isFile

                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val granted = prefs.getStringSet(permissionKey(id), emptySet())?.toSet() ?: emptySet()

                val verdict = ExtensionInstallGate.evaluate(
                    host = ExtensionInstallGate.Host(appVersionName = appVersionName()),
                    manifest = manifest,
                    pkg = ExtensionInstallGate.Package(
                        downloadedSha256 = packageSha256,
                        sizeBytes = sizeBytes,
                        extractedBytes = extractedBytes,
                        hasDex = hasDex,
                    ),
                    registryId = id,
                    // A local file was never scanned, so there is no hash to pin to.
                    registrySha256 = "",
                    suspended = ExtensionMarketplace.get(context).isDownloadSuspended(id),
                    suspendedReason = ExtensionMarketplace.get(context).suspensionReason(id),
                    alreadyGranted = granted,
                )
                if (!verdict.ok) {
                    staging.deleteRecursively()
                    onDone(false, verdict.errors.joinToString("\n")); return@Thread
                }

                val commit: (approved: Set<String>) -> Unit = { approved ->
                    Thread {
                        try {
                            onProgress("설치 중: ${manifest.name}")
                            commitInstall(id, manifest, staging, approved)
                            onProgress("설치 완료: ${manifest.name} ${manifest.version}")
                            onDone(true, "${manifest.name} ${manifest.version} 설치됨")
                        } catch (e: Exception) {
                            Log.e(TAG, "installFromFile commit failed", e)
                            staging.deleteRecursively()
                            onDone(false, "설치 실패: ${e.message}")
                        }
                    }.start()
                }

                if (verdict.needsApproval && onPermissionsRequired != null) {
                    onProgress("권한 승인 필요: ${verdict.newPermissions.joinToString(", ")}")
                    onPermissionsRequired(manifest, verdict.newPermissions) { proceed ->
                        if (proceed) {
                            commit(granted + verdict.newPermissions)
                        } else {
                            staging.deleteRecursively()
                            onDone(false, "권한을 거부하여 설치를 취소했습니다")
                        }
                    }
                } else {
                    commit(granted)
                }
            } catch (e: Exception) {
                Log.e(TAG, "installFromFile failed", e)
                staging.deleteRecursively()
                onDone(false, "설치 실패: ${e.message}")
            }
        }.start()
    }

    /** Reads the manifest out of a .msext package without installing it. */
    fun manifestOfPackage(file: File): ExtensionManifest? {
        val probe = File(context.cacheDir, "peek-${System.currentTimeMillis()}")
        return try {
            probe.mkdirs()
            extractMsext(file, probe)
            readManifest(probe)
        } catch (e: Exception) {
            Log.w(TAG, "manifest peek failed: ${file.name}", e)
            null
        } finally {
            probe.deleteRecursively()
        }
    }
}
