package com.aide.mobilestudio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * BuildToolCatalog — one dynamically fetched catalog of downloadable build
 * tools (JDK / CMake / NDK), derived from the GitHub Releases of
 * MobileStudio-AndroidIDE/MobileStudio_AndroidIDE.
 *
 * Discovery is fully dynamic — the previously hardcoded version lists become
 * bundled FALLBACKS used only when the network is unavailable or parsing
 * fails, so the UI is never empty:
 *  - JDK   : assets named "jdk<N>-android.tar.gz" (tags are irregular; the
 *            asset name is the reliable key; major version from the asset,
 *            falling back to the tag)
 *  - CMake : assets named "cmake-<ver>-android-arm64.tar.gz" (tag form
 *            "cmake-android-v<ver>"; note the 3.22.1 release is flagged
 *            prerelease on GitHub and is still a valid port, so prereleases
 *            are NOT skipped here)
 *  - NDK   : assets named "android-ndk-<ver>-arm64.tar.zst" (historically all
 *            assets of the single "NDK-arm64" tag)
 *
 * All three lists are ordered NEWEST FIRST using a numeric segment-wise
 * version comparison (9.10 > 9.9, 29.0.14033849 > 28.2.13676358).
 *
 * The catalog is cached at <filesDir>/tools/cache/build-tool-catalog.json
 * with a 6h TTL (same pattern as StudioToolsIndex). A failed refresh serves
 * the stale cache; when even the cache is missing the bundled fallback lists
 * apply. Network failures never crash.
 *
 * Pure parsing/comparison/sorting logic lives in the testable functions below
 * (no Android imports outside the fetch/cache section).
 */
object BuildToolCatalog {

    // ------------------------------------------------------------------
    // Central configuration
    // ------------------------------------------------------------------
    const val CATALOG_OWNER = StudioToolsIndex.GITHUB_OWNER
    const val CATALOG_REPO = "MobileStudio_AndroidIDE"
    const val RELEASES_API =
        "https://api.github.com/repos/$CATALOG_OWNER/$CATALOG_REPO/releases"
    const val MAX_RELEASES_PER_PAGE = 100

    /** The catalog is refreshed at most every 6 hours. */
    const val CATALOG_TTL_MS = StudioToolsIndex.INDEX_TTL_MS

    /** At most this many checksums files are downloaded per refresh. */
    private const val MAX_ENRICHED_RELEASES = 3

    // ------------------------------------------------------------------
    // Data types
    // ------------------------------------------------------------------

    /** One downloadable build-tool release entry. */
    data class ToolRelease(
        val version: String,
        val url: String,
        val sha256: String? = null,
        val size: Long = 0L,
        val tag: String = ""
    )

    data class ReleaseAsset(val name: String, val url: String, val size: Long)

    /** One GitHub release (drafts skipped, prereleases kept — see class doc). */
    data class ReleaseAssets(val tag: String, val assets: List<ReleaseAsset>)

    /** A catalog snapshot. [stale] = expired cache served after a failed refresh. */
    data class Catalog(
        val jdk: List<ToolRelease>,
        val cmake: List<ToolRelease>,
        val ndk: List<ToolRelease>,
        val fetchedAt: Long,
        val stale: Boolean = false
    ) {
        val isEmpty: Boolean get() = jdk.isEmpty() && cmake.isEmpty() && ndk.isEmpty()
    }

    // ------------------------------------------------------------------
    // Bundled fallbacks — the previously hardcoded values. Used when the
    // network is unavailable or the releases JSON cannot be parsed.
    // ------------------------------------------------------------------

    val FALLBACK_JDK = listOf(
        ToolRelease(
            version = "21",
            url = "https://github.com/$CATALOG_OWNER/$CATALOG_REPO/releases/download/jdk21-android-21.0.4/jdk21-android.tar.gz",
            sha256 = "e561d04f4f91714da934723d1ec3214380b90e62e5aee3844ba2854179bd5c77",
            tag = "jdk21-android-21.0.4"
        ),
        ToolRelease(
            version = "17",
            url = "https://github.com/$CATALOG_OWNER/$CATALOG_REPO/releases/download/MobileStudio-JDK17-Android-ARM64/jdk17-android.tar.gz",
            sha256 = "b7cdb041d902f84efca00a886502b93e07c064d80c7386ba572dc9e99033e8e3",
            tag = "MobileStudio-JDK17-Android-ARM64"
        )
    )

    val FALLBACK_CMAKE = listOf(
        ToolRelease(
            version = CmakeRuntimeManager.CMAKE_VERSION,
            url = CmakeRuntimeManager.DOWNLOAD_URL,
            sha256 = CmakeRuntimeManager.EXPECTED_SHA256,
            size = CmakeRuntimeManager.ESTIMATED_ARCHIVE_BYTES,
            tag = CmakeRuntimeManager.releaseTag(CmakeRuntimeManager.CMAKE_VERSION)
        )
    )

    val FALLBACK_NDK: List<ToolRelease> = NdkToolchain.GITHUB_NDK_VERSIONS.map { version ->
        ToolRelease(
            version = version,
            url = NdkToolchain.githubUrl(version),
            sha256 = null,
            tag = NdkToolchain.GITHUB_RELEASE
        )
    }

    // ------------------------------------------------------------------
    // Pure parsing helpers (unit-testable)
    // ------------------------------------------------------------------

    private val JDK_ASSET_REGEX = Regex("""^jdk(\d+)-android\.tar\.gz$""")
    private val CMAKE_ASSET_REGEX = Regex("""^cmake-(\d+(?:\.\d+)*)-android-arm64\.tar\.gz$""")
    private val CMAKE_TAG_REGEX = Regex("""^cmake-android-v(\d+(?:\.\d+)*)$""")
    private val NDK_ASSET_REGEX = Regex("""^android-ndk-(\d+(?:\.\d+)*)-arm64\.tar\.zst$""")

    /** "jdk21-android.tar.gz" -> 21; anything else -> null. */
    fun jdkMajorFromAsset(assetName: String): Int? =
        JDK_ASSET_REGEX.matchEntire(assetName)?.groupValues?.get(1)?.toIntOrNull()

    /** Major version parsed from any tag mentioning jdk<N> ("jdk21-android-21.0.4" -> 21). */
    fun jdkMajorFromTag(tag: String): Int? =
        Regex("""(?i)jdk(\d+)""").find(tag)?.groupValues?.get(1)?.toIntOrNull()

    /** JDK major version for an asset, asset name first, tag as fallback. */
    fun jdkVersionFor(assetName: String, tag: String): String? =
        (jdkMajorFromAsset(assetName) ?: jdkMajorFromTag(tag))?.toString()

    /** "cmake-3.31.5-android-arm64.tar.gz" -> "3.31.5". */
    fun cmakeVersionFromAsset(assetName: String): String? =
        CMAKE_ASSET_REGEX.matchEntire(assetName)?.groupValues?.get(1)

    /** "cmake-android-v3.31.5" -> "3.31.5". */
    fun cmakeVersionFromTag(tag: String): String? =
        CMAKE_TAG_REGEX.matchEntire(tag)?.groupValues?.get(1)

    /** "android-ndk-29.0.14206865-arm64.tar.zst" -> "29.0.14206865". */
    fun ndkVersionFromAsset(assetName: String): String? =
        NDK_ASSET_REGEX.matchEntire(assetName)?.groupValues?.get(1)

    /**
     * GitHub releases JSON -> per-release asset lists. Draft releases are
     * skipped (their assets are not downloadable); prereleases are KEPT
     * (the distributed CMake 3.22.1 release is flagged prerelease).
     */
    fun parseReleasesJson(text: String): List<ReleaseAssets> = runCatching {
        val releases = JSONArray(text)
        val out = mutableListOf<ReleaseAssets>()
        for (r in 0 until releases.length()) {
            val release = releases.optJSONObject(r) ?: continue
            if (release.optBoolean("draft", false)) continue
            val tag = release.optString("tag_name", "")
            val assets = release.optJSONArray("assets") ?: continue
            val list = mutableListOf<ReleaseAsset>()
            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                val name = asset.optString("name", "")
                val url = asset.optString("browser_download_url", "")
                if (name.isBlank() || url.isBlank()) continue
                list += ReleaseAsset(name, url, asset.optLong("size", 0L))
            }
            out += ReleaseAssets(tag, list)
        }
        out
    }.getOrDefault(emptyList())

    /** JDK entries from parsed releases — newest first, deduped by version. */
    fun jdkReleasesFrom(releases: List<ReleaseAssets>): List<ToolRelease> =
        collectReleases(releases) { asset, tag ->
            jdkVersionFor(asset.name, tag)?.let { version ->
                ToolRelease(version, asset.url, null, asset.size, tag)
            }
        }

    /** CMake entries from parsed releases — newest first, deduped by version. */
    fun cmakeReleasesFrom(releases: List<ReleaseAssets>): List<ToolRelease> =
        collectReleases(releases) { asset, tag ->
            (cmakeVersionFromAsset(asset.name) ?: cmakeVersionFromTag(tag))?.let { version ->
                ToolRelease(version, asset.url, null, asset.size, tag)
            }
        }

    /** NDK entries from parsed releases — newest first, deduped by version. */
    fun ndkReleasesFrom(releases: List<ReleaseAssets>): List<ToolRelease> =
        collectReleases(releases) { asset, tag ->
            ndkVersionFromAsset(asset.name)?.let { version ->
                ToolRelease(version, asset.url, null, asset.size, tag)
            }
        }

    /** First (newest) occurrence of each version wins; result sorted newest first. */
    private fun collectReleases(
        releases: List<ReleaseAssets>,
        map: (ReleaseAsset, String) -> ToolRelease?
    ): List<ToolRelease> {
        val byVersion = linkedMapOf<String, ToolRelease>()
        for (release in releases) {
            for (asset in release.assets) {
                val entry = map(asset, release.tag) ?: continue
                if (entry.version.isBlank() || entry.url.isBlank()) continue
                byVersion.putIfAbsent(entry.version, entry)
            }
        }
        return sortNewestFirst(byVersion.values.toList())
    }

    // ------------------------------------------------------------------
    // Pure version comparison / sorting / merging (unit-testable)
    // ------------------------------------------------------------------

    /**
     * Numeric segment-wise comparison: 9.10 > 9.9, 29.0.14033849 >
     * 28.2.13676358, 3.22 < 3.22.1 (missing segments count as 0). Non-numeric
     * segment suffixes fall back to string comparison.
     */
    fun compareVersions(a: String, b: String): Int {
        if (a == b) return 0
        val segsA = a.split('.')
        val segsB = b.split('.')
        val n = maxOf(segsA.size, segsB.size)
        for (i in 0 until n) {
            val pa = segmentParts(segsA.getOrNull(i) ?: "0")
            val pb = segmentParts(segsB.getOrNull(i) ?: "0")
            if (pa.first != pb.first) return pa.first.compareTo(pb.first)
            if (pa.second != pb.second) return pa.second.compareTo(pb.second)
        }
        return 0
    }

    /** "123abc" -> (123, "abc"); "abc" -> (0, "abc"); "7" -> (7, ""). */
    private fun segmentParts(segment: String): Pair<Long, String> {
        val splitAt = segment.indexOfFirst { !it.isDigit() }
        val number = when {
            splitAt == 0 -> 0L
            splitAt < 0 -> segment.toLongOrNull() ?: 0L
            else -> segment.substring(0, splitAt).toLongOrNull() ?: 0L
        }
        val suffix = if (splitAt < 0) "" else segment.substring(splitAt)
        return number to suffix
    }

    /** Strips a non-numeric prefix ("jdk-21" -> "21", "gradle-9.7.0" -> "9.7.0"). */
    fun displayVersionValue(version: String): String =
        version.dropWhile { !it.isDigit() }.ifBlank { version }

    /** Version-aware comparison for display strings ("jdk-21" vs "jdk-17"). */
    fun compareDisplayVersions(a: String, b: String): Int {
        val cmp = compareVersions(displayVersionValue(a), displayVersionValue(b))
        return if (cmp != 0) cmp else a.compareTo(b)
    }

    /** Sorts tool releases newest first (deterministic tie-break on URL). */
    fun sortNewestFirst(releases: List<ToolRelease>): List<ToolRelease> =
        releases.sortedWith { x, y ->
            val cmp = compareVersions(y.version, x.version)
            if (cmp != 0) cmp else x.url.compareTo(y.url)
        }

    /**
     * Network results are authoritative: they win over fallback entries with
     * the same version, and a fallback sha256 is adopted for an identical URL
     * when the catalog could not enrich one. Fallback-only versions are
     * appended so the list is never smaller than the bundled baseline.
     */
    fun mergeWithFallback(network: List<ToolRelease>, fallback: List<ToolRelease>): List<ToolRelease> {
        if (network.isEmpty()) return sortNewestFirst(fallback)
        val fallbackByUrl = fallback.associateBy { it.url }
        val merged = network.map { entry ->
            val fb = if (entry.sha256 == null) fallbackByUrl[entry.url] else null
            if (fb?.sha256 != null) entry.copy(sha256 = fb.sha256) else entry
        }
        val known = merged.mapTo(HashSet()) { it.version }
        return sortNewestFirst(merged + fallback.filter { it.version !in known })
    }

    /**
     * Merges display version strings ("gradle-9.7.1", "jdk-21", ...) newest
     * first, deduplicated. [network] entries win on ties purely by position.
     */
    fun mergedVersionStringsNewestFirst(
        network: Collection<String>,
        fallback: Collection<String>
    ): List<String> =
        (network.asSequence() + fallback.asSequence())
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith { x, y ->
                val cmp = compareDisplayVersions(y, x)
                if (cmp != 0) cmp else x.compareTo(y)
            }
            .toList()

    /** Newest version among tool releases (defensive — works on any order). */
    fun newestOf(releases: List<ToolRelease>): String? =
        releases.maxWithOrNull { x, y -> compareVersions(x.version, y.version) }?.version

    /** Newest display version string (defensive — works on any order). */
    fun newestVersionString(values: Collection<String>): String? =
        values.filter { it.isNotBlank() }
            .maxWithOrNull { x, y -> compareDisplayVersions(x, y) }

    // ------------------------------------------------------------------
    // Merged views over a catalog (network + bundled fallback)
    // ------------------------------------------------------------------

    fun jdkEntries(catalog: Catalog): List<ToolRelease> =
        mergeWithFallback(catalog.jdk, FALLBACK_JDK)

    fun cmakeEntries(catalog: Catalog): List<ToolRelease> =
        mergeWithFallback(catalog.cmake, FALLBACK_CMAKE)

    fun ndkEntries(catalog: Catalog): List<ToolRelease> =
        mergeWithFallback(catalog.ndk, FALLBACK_NDK)

    fun findJdk(catalog: Catalog, major: Int): ToolRelease? =
        jdkEntries(catalog).firstOrNull { it.version == major.toString() }

    fun findCmake(catalog: Catalog, version: String): ToolRelease? =
        cmakeEntries(catalog).firstOrNull { it.version == version }

    fun findNdk(catalog: Catalog, version: String): ToolRelease? =
        ndkEntries(catalog).firstOrNull { it.version == version }

    /**
     * Newest CMake version seen by any fetch/load in this process (the memo
     * is empty until a Context-bearing call ran), used where no Context is
     * available. Falls back to the caller's bundled default when null.
     */
    fun newestCachedCmakeVersion(): String? = newestOf(memo?.cmake ?: emptyList())

    // ------------------------------------------------------------------
    // Cache (build-tool-catalog.json)
    // ------------------------------------------------------------------

    fun cacheFile(context: Context): File =
        File(StudioShellTools.toolsDir(context), "cache/build-tool-catalog.json")

    fun catalogToJson(catalog: Catalog): String {
        fun arr(list: List<ToolRelease>): JSONArray = JSONArray().apply {
            list.forEach { entry ->
                put(
                    JSONObject()
                        .put("version", entry.version)
                        .put("url", entry.url)
                        .put("sha256", entry.sha256 ?: "")
                        .put("size", entry.size)
                        .put("tag", entry.tag)
                )
            }
        }
        return JSONObject()
            .put("fetchedAt", catalog.fetchedAt)
            .put("jdk", arr(catalog.jdk))
            .put("cmake", arr(catalog.cmake))
            .put("ndk", arr(catalog.ndk))
            .toString(2)
    }

    fun parseCatalogJson(text: String): Catalog? = runCatching {
        val root = JSONObject(text)
        fun list(key: String): List<ToolRelease> {
            val arr = root.optJSONArray(key) ?: return emptyList()
            val out = mutableListOf<ToolRelease>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val version = obj.optString("version", "")
                val url = obj.optString("url", "")
                if (version.isBlank() || url.isBlank()) continue
                val sha = obj.optString("sha256", "")
                out += ToolRelease(
                    version = version,
                    url = url,
                    sha256 = sha.ifBlank { null },
                    size = obj.optLong("size", 0L),
                    tag = obj.optString("tag", "")
                )
            }
            return out
        }
        Catalog(list("jdk"), list("cmake"), list("ndk"), root.optLong("fetchedAt", 0L))
    }.getOrNull()

    // ------------------------------------------------------------------
    // Fetch (memo + file cache, TTL, never crashes on network failure)
    // ------------------------------------------------------------------

    @Volatile private var memo: Catalog? = null
    private val fetchLock = Any()

    private fun isFresh(catalog: Catalog): Boolean =
        catalog.fetchedAt > 0L && System.currentTimeMillis() - catalog.fetchedAt < CATALOG_TTL_MS

    /** Cache-only load (no network — safe on the UI thread). Never null. */
    fun loadCached(context: Context): Catalog {
        memo?.let { return it }
        val parsed = runCatching {
            cacheFile(context).takeIf { it.isFile }?.readText()
        }.getOrNull()?.let { parseCatalogJson(it) }
        return if (parsed != null && !parsed.isEmpty) {
            memo = parsed
            parsed
        } else {
            parsed ?: Catalog(emptyList(), emptyList(), emptyList(), 0L, stale = true)
        }
    }

    /**
     * Cache-first with TTL. A failed refresh falls back to the stale cache,
     * then to an empty catalog (callers merge their bundled fallbacks).
     */
    fun fetch(context: Context, forceRefresh: Boolean = false): Catalog {
        if (!forceRefresh) {
            memo?.takeIf { isFresh(it) }?.let { return it }
            readCacheFile(context)?.let { cached ->
                if (!cached.isEmpty) {
                    memo = cached
                    if (isFresh(cached)) return cached
                }
            }
        }
        synchronized(fetchLock) {
            if (!forceRefresh) {
                memo?.takeIf { isFresh(it) }?.let { return it }
            }
            val fresh = runCatching { fetchFromNetwork() }.getOrNull()
            if (fresh != null && !fresh.isEmpty) {
                memo = fresh
                runCatching {
                    val file = cacheFile(context)
                    file.parentFile?.mkdirs()
                    file.writeText(catalogToJson(fresh))
                }
                return fresh
            }
            readCacheFile(context)?.let { cached ->
                if (!cached.isEmpty) {
                    memo = cached
                    return cached.copy(stale = true)
                }
            }
            return Catalog(emptyList(), emptyList(), emptyList(), 0L, stale = true)
        }
    }

    private fun readCacheFile(context: Context): Catalog? = runCatching {
        cacheFile(context).takeIf { it.isFile }?.readText()
    }.getOrNull()?.let { parseCatalogJson(it) }

    private fun fetchFromNetwork(): Catalog? {
        val releasesJson =
            StudioToolsIndex.downloadText("$RELEASES_API?per_page=$MAX_RELEASES_PER_PAGE")
                ?: return null
        val releases = parseReleasesJson(releasesJson)
        if (releases.isEmpty()) return null
        var jdk = jdkReleasesFrom(releases)
        var cmake = cmakeReleasesFrom(releases)
        val ndk = ndkReleasesFrom(releases)
        if (jdk.isEmpty() && cmake.isEmpty() && ndk.isEmpty()) return null
        val enriched = enrichChecksums(releases, jdk, cmake)
        jdk = enriched.first
        cmake = enriched.second
        return Catalog(jdk, cmake, ndk, System.currentTimeMillis())
    }

    /**
     * Downloads at most [MAX_ENRICHED_RELEASES] "checksums.sha256" release
     * assets and fills in sha256 values matched by asset basename. Releases
     * without a checksums file keep sha256 = null (JDK/CMake releases carry
     * none today; the bundled fallback hashes are merged at read time).
     */
    private fun enrichChecksums(
        releases: List<ReleaseAssets>,
        jdk: List<ToolRelease>,
        cmake: List<ToolRelease>
    ): Pair<List<ToolRelease>, List<ToolRelease>> {
        var jdkOut = jdk
        var cmakeOut = cmake
        var enriched = 0
        for (release in releases) {
            if (enriched >= MAX_ENRICHED_RELEASES) break
            val checksumsAsset = release.assets
                .firstOrNull { it.name == StudioToolsIndex.CHECKSUMS_ASSET } ?: continue
            val hashes = StudioToolsIndex.downloadText(checksumsAsset.url)
                ?.let { StudioToolsIndex.parseChecksums(it) } ?: continue
            enriched++
            if (hashes.isEmpty()) continue
            jdkOut = jdkOut.map { it.copy(sha256 = it.sha256 ?: hashes[assetBaseName(it.url)]) }
            cmakeOut = cmakeOut.map { it.copy(sha256 = it.sha256 ?: hashes[assetBaseName(it.url)]) }
        }
        return jdkOut to cmakeOut
    }

    private fun assetBaseName(url: String): String = url.substringAfterLast('/')
}

/**
 * Pure pagination windowing for version lists: the newest [PAGE_SIZE]
 * versions are visible initially; every expansion reveals the next
 * [PAGE_SIZE]; the "show more" row disappears once everything is visible.
 */
object VersionPagination {
    const val PAGE_SIZE = 5

    /** How many entries are visible after [expansions] "show more" taps. */
    fun visibleCount(total: Int, expansions: Int, pageSize: Int = PAGE_SIZE): Int =
        ((expansions.coerceAtLeast(0) + 1) * pageSize).coerceAtMost(total.coerceAtLeast(0))

    /** True when more entries exist beyond the currently visible window. */
    fun hasMore(total: Int, expansions: Int, pageSize: Int = PAGE_SIZE): Boolean =
        visibleCount(total, expansions, pageSize) < total

    /** The currently visible slice of [items] (newest first input). */
    fun <T> window(items: List<T>, expansions: Int, pageSize: Int = PAGE_SIZE): List<T> =
        items.take(visibleCount(items.size, expansions, pageSize))

    /** The next [pageSize] entries revealed by one more expansion. */
    fun <T> nextBatchAfter(items: List<T>, expansions: Int, pageSize: Int = PAGE_SIZE): List<T> =
        items.drop(visibleCount(items.size, expansions, pageSize)).take(pageSize)
}
