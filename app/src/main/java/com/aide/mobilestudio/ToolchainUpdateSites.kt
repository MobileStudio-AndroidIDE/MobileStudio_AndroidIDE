package com.aide.mobilestudio

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

class ToolchainUpdateSites(private val context: Context) {

    companion object {
        /** How long a scraped version cache is trusted before auto-refresh. */
        const val DEFAULT_REFRESH_TTL_MS: Long = 60L * 60L * 1000L
    }

    data class PackageInfo(
        val kind: ToolchainManager.Kind,
        val version: String,
        val url: String? = null
    )

    private val prefs = context.getSharedPreferences("toolchain_update_sites", Context.MODE_PRIVATE)

    fun sites(kind: ToolchainManager.Kind): List<String> {
        // 고정???데?트 ?이?만 ?용 (커스? ?이??비활?화)
        return defaultSites(kind)
    }

    fun addSite(kind: ToolchainManager.Kind, url: String) {
        // 커스? ?이??추? 비활?화
    }

    fun replaceSite(kind: ToolchainManager.Kind, oldUrl: String, newUrl: String) {
        // 커스? ?이??교체 비활?화
    }

    fun removeSite(kind: ToolchainManager.Kind, url: String) {
        prefs.edit().putStringSet(siteKey(kind), sites(kind).filterNot { it == url }.toSet()).apply()
    }

    fun resetSites(kind: ToolchainManager.Kind) {
        prefs.edit().remove(siteKey(kind)).apply()
    }

    fun cachedVersions(kind: ToolchainManager.Kind): List<String> =
        prefs.getStringSet(versionKey(kind), emptySet()).orEmpty().sorted()

    fun cachedDownloadUrl(kind: ToolchainManager.Kind, version: String): String? =
        prefs.getString(urlKey(kind, version), null)

    fun refresh(kind: ToolchainManager.Kind): List<PackageInfo> {
        val packages = sites(kind).flatMap { site ->
            val text = downloadText(site)
            parsePackages(kind, site, text)
        }.distinctBy { it.version }.sortedBy { it.version }

        val editor = prefs.edit()
        editor.putStringSet(versionKey(kind), packages.map { it.version }.toSet())
        packages.forEach { info ->
            if (info.url != null) editor.putString(urlKey(kind, info.version), info.url)
        }
        editor.putLong(lastRefreshKey(kind), System.currentTimeMillis())
        editor.apply()
        return packages
    }

    /**
     * Refreshes only when the cached scrape is older than [ttlMs] (per kind).
     * Returns null when the cache is fresh enough — used by UI entry points
     * so the newest versions appear automatically without hammering the
     * update site on every screen. The manual "Fetch" button uses [refresh]
     * (always forces).
     */
    fun refreshIfStale(
        kind: ToolchainManager.Kind,
        ttlMs: Long = DEFAULT_REFRESH_TTL_MS
    ): List<PackageInfo>? {
        val last = prefs.getLong(lastRefreshKey(kind), 0L)
        if (System.currentTimeMillis() - last < ttlMs) return null
        return runCatching { refresh(kind) }.getOrNull()
    }

    private fun lastRefreshKey(kind: ToolchainManager.Kind) = "last_refresh_${kind.name}"

    private fun defaultSites(kind: ToolchainManager.Kind): List<String> = when (kind) {
        ToolchainManager.Kind.JDK -> listOf(
            "https://api.bell-sw.com/v1/liberica/releases?architecture=aarch64&os=linux&package-type=jdk&bundle-type=jdk&bitness=64"
        )
        ToolchainManager.Kind.GRADLE -> listOf("https://services.gradle.org/distributions/")
        ToolchainManager.Kind.SDK,
        ToolchainManager.Kind.NDK,
        ToolchainManager.Kind.CMAKE -> listOf("https://dl.google.com/android/repository/repository2-1.xml")
        ToolchainManager.Kind.PYTHON -> emptyList()
    }

    private fun downloadText(site: String): String {
        val conn = (URL(site).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            connect()
        }
        val code = conn.responseCode
        if (code == 404) {
            conn.disconnect()
            error("404 Not Found: $site")
        }
        check(code in 200..299) { "HTTP $code: $site" }
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun parsePackages(
        kind: ToolchainManager.Kind,
        site: String,
        text: String
    ): List<PackageInfo> = when (kind) {
        ToolchainManager.Kind.JDK -> parseJdk(site, text)
        ToolchainManager.Kind.GRADLE -> parseGradle(site, text)
        ToolchainManager.Kind.SDK -> parseSdkPlatforms(text)
        ToolchainManager.Kind.NDK -> parseSdkTool(text, "ndk;")
        ToolchainManager.Kind.CMAKE -> parseSdkTool(text, "cmake;")
        ToolchainManager.Kind.PYTHON -> parsePython(site, text)
    }

    private fun parsePython(site: String, text: String): List<PackageInfo> =
        Regex("""https?://[^"' <>()]+(?:python|cpython)[^"' <>()]*(?:android|aarch64|arm64)[^"' <>()]*(?:\.tar\.gz|\.tgz|\.tar\.xz|\.zip)""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .mapNotNull { match ->
                val url = match.value
                val version = Regex("""(?:python|cpython)[-_]?(\d+\.\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { "python-$it" }
                    ?: return@mapNotNull null
                PackageInfo(ToolchainManager.Kind.PYTHON, version, url)
            }
            .toList()

    private fun parseJdk(site: String, text: String): List<PackageInfo> {
        val jsonUrls = Regex("\"downloadUrl\"\\s*:\\s*\"([^\"]+)\"")
            .findAll(text)
            .map { it.groupValues[1].replace("\\/", "/") }
        val htmlUrls = Regex("""https?://[^"' <>()]+(?:linux|Linux)[^"' <>()]*(?:aarch64|arm64)[^"' <>()]*(?:\.tar\.gz|\.zip)""")
            .findAll(text)
            .map { it.value }
        val parsed = (jsonUrls + htmlUrls)
            .filter { it.contains("aarch64", ignoreCase = true) || it.contains("arm64", ignoreCase = true) }
            .mapNotNull { url ->
                val feature = Regex("""(?:jdk|java|openjdk|bellsoft-jdk)[-_]?(\d{2})(?:[._-]|$)""", RegexOption.IGNORE_CASE)
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)
                val version = feature?.let { "jdk-$it" }
                    ?: Regex("""(\d{2}(?:\.\d+){0,3})""").find(url)?.groupValues?.getOrNull(1)?.let { "jdk-$it" }
                    ?: return@mapNotNull null
                PackageInfo(ToolchainManager.Kind.JDK, version, url)
            }
            .toList()
        return parsed.ifEmpty {
            Regex("\"featureVersion\"\\s*:\\s*(\\d+)")
                .findAll(text)
                .map { PackageInfo(ToolchainManager.Kind.JDK, "jdk-${it.groupValues[1]}") }
                .toList()
        }
    }

    private fun parseGradle(site: String, text: String): List<PackageInfo> =
        Regex("""gradle-(\d+(?:\.\d+){1,3})-bin\.zip""")
            .findAll(text)
            .map { match ->
                val fileName = match.value
                val url = if (site.endsWith("/")) site + fileName else "$site/$fileName"
                PackageInfo(ToolchainManager.Kind.GRADLE, "gradle-${match.groupValues[1]}", url)
            }
            .toList()

    private fun parseSdkPlatforms(text: String): List<PackageInfo> =
        Regex("path=\"platforms;(android-\\d+)\"")
            .findAll(text)
            .map { PackageInfo(ToolchainManager.Kind.SDK, it.groupValues[1]) }
            .toList()

    private fun parseSdkTool(text: String, prefix: String): List<PackageInfo> {
        val kind = if (prefix == "ndk;") ToolchainManager.Kind.NDK else ToolchainManager.Kind.CMAKE
        return Regex("<remotePackage\\b[^>]*\\bpath=\"${Regex.escape(prefix)}([^\"]+)\"[^>]*>[\\s\\S]*?</remotePackage>")
            .findAll(text)
            .map { match ->
                val version = match.groupValues[1]
                val block = match.value
                val archive = Regex("<archive>[\\s\\S]*?</archive>")
                    .findAll(block)
                    .map { it.value }
                    .firstOrNull { "<host-os>linux</host-os>" in it || "<host-os>" !in it }
                val relativeUrl = archive?.let { Regex("<url>([^<]+)</url>").find(it)?.groupValues?.get(1) }
                val url = relativeUrl?.let { "https://dl.google.com/android/repository/$it" }
                PackageInfo(kind, version, url)
            }
            .toList()
    }

    private fun siteKey(kind: ToolchainManager.Kind) = "sites_${kind.name}"
    private fun versionKey(kind: ToolchainManager.Kind) = "versions_${kind.name}"
    private fun urlKey(kind: ToolchainManager.Kind, version: String) =
        "url_${kind.name}_${version.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
}
