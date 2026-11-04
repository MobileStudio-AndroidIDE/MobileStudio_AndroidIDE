package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BuildToolCatalog — GitHub Releases 기반 동적 빌드 도구 카탈로그 검증.
 * 네트워크 없이 순수 파싱/정렬/병합/페이지네이션 로직만 검증한다.
 */
class BuildToolCatalogTest {

    /** Synthetic GitHub releases JSON mirroring the real repository layout. */
    private val releasesJson = """
        [
          {
            "tag_name": "cmake-android-v3.31.5",
            "draft": false,
            "prerelease": false,
            "assets": [
              {"name": "cmake-3.31.5-android-arm64.tar.gz", "browser_download_url": "https://example.com/cmake-3.31.5-android-arm64.tar.gz", "size": 900}
            ]
          },
          {
            "tag_name": "jdk21-android-21.0.4",
            "draft": false,
            "prerelease": false,
            "assets": [
              {"name": "jdk21-android.tar.gz", "browser_download_url": "https://example.com/jdk21-android.tar.gz", "size": 100}
            ]
          },
          {
            "tag_name": "MobileStudio-JDK17-Android-ARM64",
            "draft": false,
            "prerelease": false,
            "assets": [
              {"name": "jdk17-android.tar.gz", "browser_download_url": "https://example.com/jdk17-android.tar.gz", "size": 100}
            ]
          },
          {
            "tag_name": "NDK-arm64",
            "draft": false,
            "prerelease": false,
            "assets": [
              {"name": "android-ndk-29.0.14206865-arm64.tar.zst", "browser_download_url": "https://example.com/ndk-29.tar.zst", "size": 100},
              {"name": "android-ndk-29.0.14206865-arm64.tar.zst.sha256", "browser_download_url": "https://example.com/ndk-29.tar.zst.sha256", "size": 5},
              {"name": "unrelated-tool-android-arm64.tar.gz", "browser_download_url": "https://example.com/unrelated.tar.gz", "size": 7},
              {"name": "android-ndk-28.2.13676358-arm64.tar.zst", "browser_download_url": "https://example.com/ndk-28.tar.zst", "size": 100}
            ]
          },
          {
            "tag_name": "cmake-android-v3.22.1",
            "draft": false,
            "prerelease": true,
            "assets": [
              {"name": "cmake-3.22.1-android-arm64.tar.gz", "browser_download_url": "https://example.com/cmake-3.22.1-android-arm64.tar.gz", "size": 900}
            ]
          },
          {
            "tag_name": "jdk99-draft",
            "draft": true,
            "prerelease": false,
            "assets": [
              {"name": "jdk99-android.tar.gz", "browser_download_url": "https://example.com/jdk99-android.tar.gz", "size": 1}
            ]
          }
        ]
    """.trimIndent()

    // ------------------------------------------------------------------
    // Release JSON parsing
    // ------------------------------------------------------------------

    @Test
    fun releasesJsonParsedSkippingDraftsButKeepingPrereleases() {
        val releases = BuildToolCatalog.parseReleasesJson(releasesJson)
        assertEquals(5, releases.size)
        assertEquals("cmake-android-v3.31.5", releases.first().tag)
        assertTrue(
            "the flagged-prerelease CMake 3.22.1 release must be kept",
            releases.any { it.tag == "cmake-android-v3.22.1" }
        )
        assertTrue(
            "draft releases must be skipped",
            releases.none { it.tag == "jdk99-draft" }
        )
        assertTrue(BuildToolCatalog.parseReleasesJson("not json").isEmpty())
        assertTrue(BuildToolCatalog.parseReleasesJson("[]").isEmpty())
    }

    @Test
    fun jdkVersionsDerivedFromAssetNames() {
        val jdk = BuildToolCatalog.jdkReleasesFrom(BuildToolCatalog.parseReleasesJson(releasesJson))
        assertEquals(listOf("21", "17"), jdk.map { it.version })
        assertEquals("https://example.com/jdk21-android.tar.gz", jdk.first().url)
        assertEquals("jdk21-android-21.0.4", jdk.first().tag)
        assertEquals(100L, jdk.first().size)
        assertNull("no checksums asset exists for these releases", jdk.first().sha256)
    }

    @Test
    fun jdkMajorFallsBackToTagWhenAssetNameIsIrregular() {
        assertEquals("25", BuildToolCatalog.jdkVersionFor("openjdk.tar.gz", "jdk25-android-build"))
        assertEquals("21", BuildToolCatalog.jdkVersionFor("jdk21-android.tar.gz", "whatever-tag"))
        assertNull(BuildToolCatalog.jdkVersionFor("jdk-none.tar.gz", "unrelated"))
        assertNull(BuildToolCatalog.jdkMajorFromAsset("jdkX-android.tar.gz"))
    }

    @Test
    fun sameMajorDedupedKeepingFirstNewestOccurrence() {
        val json = """
            [
              {"tag_name": "jdk21-android-21.0.9", "draft": false, "assets": [
                {"name": "jdk21-android.tar.gz", "browser_download_url": "https://example.com/new21.tar.gz", "size": 1}]},
              {"tag_name": "jdk21-android-21.0.4", "draft": false, "assets": [
                {"name": "jdk21-android.tar.gz", "browser_download_url": "https://example.com/old21.tar.gz", "size": 1}]}
            ]
        """.trimIndent()
        val jdk = BuildToolCatalog.jdkReleasesFrom(BuildToolCatalog.parseReleasesJson(json))
        assertEquals(1, jdk.size)
        assertEquals("https://example.com/new21.tar.gz", jdk.single().url)
    }

    @Test
    fun cmakeVersionsFromAssetsIncludingThePrerelease() {
        val cmake = BuildToolCatalog.cmakeReleasesFrom(BuildToolCatalog.parseReleasesJson(releasesJson))
        assertEquals(listOf("3.31.5", "3.22.1"), cmake.map { it.version })
        assertEquals("cmake-android-v3.31.5", cmake.first().tag)
        assertEquals("https://example.com/cmake-3.31.5-android-arm64.tar.gz", cmake.first().url)
    }

    @Test
    fun cmakeVersionRecognizedFromTagForm() {
        assertEquals("3.40.0", BuildToolCatalog.cmakeVersionFromTag("cmake-android-v3.40.0"))
        assertNull(BuildToolCatalog.cmakeVersionFromTag("other-v1.0"))
        assertNull(BuildToolCatalog.cmakeVersionFromAsset("cmake-android.tar.gz"))
    }

    @Test
    fun ndkVersionsFromAssetsIgnoringSidecarsAndForeignAssets() {
        val ndk = BuildToolCatalog.ndkReleasesFrom(BuildToolCatalog.parseReleasesJson(releasesJson))
        assertEquals(listOf("29.0.14206865", "28.2.13676358"), ndk.map { it.version })
        assertTrue("checksum sidecars are not NDK entries", ndk.none { it.url.endsWith(".sha256") })
        assertTrue("unrelated assets are not NDK entries", ndk.none { it.url.contains("unrelated") })
        assertEquals("NDK-arm64", ndk.first().tag)
    }

    // ------------------------------------------------------------------
    // Version comparison / sorting / merging
    // ------------------------------------------------------------------

    @Test
    fun versionComparisonIsNumericSegmentWise() {
        assertTrue("9.10 must be newer than 9.9", BuildToolCatalog.compareVersions("9.10", "9.9") > 0)
        assertTrue(BuildToolCatalog.compareVersions("9.9", "9.10") < 0)
        assertEquals(0, BuildToolCatalog.compareVersions("3.22.1", "3.22.1"))
        assertTrue(
            "29.0.14033849 must be newer than 28.2.13676358",
            BuildToolCatalog.compareVersions("29.0.14033849", "28.2.13676358") > 0
        )
        assertTrue(
            "same major: build number decides",
            BuildToolCatalog.compareVersions("27.0.11902837", "27.0.11718014") > 0
        )
        assertTrue("missing segment counts as 0", BuildToolCatalog.compareVersions("3.22", "3.22.1") < 0)
        assertEquals("1.0 == 1.0.0", 0, BuildToolCatalog.compareVersions("1.0", "1.0.0"))
        assertTrue(BuildToolCatalog.compareVersions("8.14.5", "8.9") > 0)
        assertTrue(BuildToolCatalog.compareVersions("3.31.5", "3.22.1") > 0)
    }

    @Test
    fun displayVersionComparisonStripsPrefixes() {
        assertTrue(BuildToolCatalog.compareDisplayVersions("jdk-21", "jdk-17") > 0)
        assertTrue(BuildToolCatalog.compareDisplayVersions("gradle-9.10.0", "gradle-9.9.1") > 0)
        assertTrue(BuildToolCatalog.compareDisplayVersions("python-3.14.6", "python-3.14.10") < 0)
        assertEquals(0, BuildToolCatalog.compareDisplayVersions("27.0.1", "27.0.1"))
        assertEquals("21", BuildToolCatalog.displayVersionValue("jdk-21"))
        assertEquals("9.7.0", BuildToolCatalog.displayVersionValue("gradle-9.7.0"))
        assertEquals("3.14.6", BuildToolCatalog.displayVersionValue("python-3.14.6"))
        assertEquals("29.0.1", BuildToolCatalog.displayVersionValue("29.0.1"))
    }

    @Test
    fun sortNewestFirstOrdersNdkBuildNumbersCorrectly() {
        val sorted = BuildToolCatalog.sortNewestFirst(
            listOf(
                BuildToolCatalog.ToolRelease("27.0.11718014", "u1"),
                BuildToolCatalog.ToolRelease("29.0.14206865", "u2"),
                BuildToolCatalog.ToolRelease("28.2.13676358", "u3"),
                BuildToolCatalog.ToolRelease("29.0.14033849", "u4")
            )
        )
        assertEquals(
            listOf("29.0.14206865", "29.0.14033849", "28.2.13676358", "27.0.11718014"),
            sorted.map { it.version }
        )
    }

    @Test
    fun mergeWithFallbackPrefersNetworkAndAppendsMissingFallbacks() {
        val network = listOf(BuildToolCatalog.ToolRelease("3.31.5", "url-3315"))
        val fallback = listOf(
            BuildToolCatalog.ToolRelease("3.22.1", "url-3221", "sha-3221"),
            BuildToolCatalog.ToolRelease("3.31.5", "url-3315", "sha-3315")
        )
        val merged = BuildToolCatalog.mergeWithFallback(network, fallback)
        assertEquals(listOf("3.31.5", "3.22.1"), merged.map { it.version })
        assertEquals(
            "bundled sha adopted for the identical legacy URL",
            "sha-3315",
            merged.first { it.version == "3.31.5" }.sha256
        )
        assertEquals("sha-3221", merged.first { it.version == "3.22.1" }.sha256)
    }

    @Test
    fun mergeWithFallbackKeepsNetworkShaAndFallsBackWhenNetworkEmpty() {
        val network = listOf(BuildToolCatalog.ToolRelease("3.31.5", "url-3315", "net-sha"))
        val fallback = listOf(BuildToolCatalog.ToolRelease("3.22.1", "url-3221", "sha-3221"))
        assertEquals(
            "network sha is authoritative",
            "net-sha",
            BuildToolCatalog.mergeWithFallback(network, fallback).first().sha256
        )
        val merged = BuildToolCatalog.mergeWithFallback(emptyList(), fallback)
        assertEquals("empty network serves the fallback", listOf("3.22.1"), merged.map { it.version })
    }

    @Test
    fun mergedVersionStringsNewestFirstDedupesAndSorts() {
        val merged = BuildToolCatalog.mergedVersionStringsNewestFirst(
            network = listOf("gradle-9.7.1", "gradle-9.7.0"),
            fallback = listOf("gradle-9.7.0", "gradle-8.5", "gradle-9.10.0")
        )
        assertEquals(
            listOf("gradle-9.10.0", "gradle-9.7.1", "gradle-9.7.0", "gradle-8.5"),
            merged
        )
        assertNull(BuildToolCatalog.newestVersionString(emptyList()))
        assertEquals("gradle-9.10.0", BuildToolCatalog.newestVersionString(merged))
    }

    @Test
    fun bundledFallbacksAreCompleteAndNewestFirst() {
        assertEquals(listOf("21", "17"), BuildToolCatalog.FALLBACK_JDK.map { it.version })
        assertTrue(BuildToolCatalog.FALLBACK_JDK.all { it.sha256?.length == 64 })
        assertEquals(
            listOf("3.22.1"),
            BuildToolCatalog.FALLBACK_CMAKE.map { it.version }
        )
        assertEquals(
            CmakeRuntimeManager.DOWNLOAD_URL,
            BuildToolCatalog.FALLBACK_CMAKE.single().url
        )
        assertEquals(NdkToolchain.GITHUB_NDK_VERSIONS, BuildToolCatalog.FALLBACK_NDK.map { it.version })
        assertEquals(
            "fallback NDK list is newest first",
            1,
            BuildToolCatalog.compareVersions(
                BuildToolCatalog.FALLBACK_NDK.first().version,
                BuildToolCatalog.FALLBACK_NDK.last().version
            )
        )
    }

    @Test
    fun lookupsOnEmptyCatalogServeTheBundledFallbacks() {
        val empty = BuildToolCatalog.Catalog(emptyList(), emptyList(), emptyList(), 0L)
        assertEquals("21", BuildToolCatalog.findJdk(empty, 21)?.version)
        assertEquals("17", BuildToolCatalog.findJdk(empty, 17)?.version)
        assertNull("unknown majors have no entry", BuildToolCatalog.findJdk(empty, 25))
        assertEquals("3.22.1", BuildToolCatalog.findCmake(empty, "3.22.1")?.version)
        assertNull(
            "an empty catalog only carries the bundled fallback (3.22.1), not network-only versions",
            BuildToolCatalog.findCmake(empty, "3.31.5")
        )
        assertNull("unknown versions have no entry", BuildToolCatalog.findNdk(empty, "99.0.00000000"))
        assertEquals("29.0.14206865", BuildToolCatalog.findNdk(empty, "29.0.14206865")?.version)
    }

    // ------------------------------------------------------------------
    // Cache JSON round trip
    // ------------------------------------------------------------------

    @Test
    fun catalogJsonRoundTrips() {
        val catalog = BuildToolCatalog.Catalog(
            jdk = listOf(BuildToolCatalog.ToolRelease("21", "u", "a".repeat(64), 5L, "t1")),
            cmake = listOf(BuildToolCatalog.ToolRelease("3.31.5", "c", null, 0L, "t2")),
            ndk = listOf(BuildToolCatalog.ToolRelease("29.0.1", "n", null, 1L, "t3")),
            fetchedAt = 42L
        )
        val parsed = BuildToolCatalog.parseCatalogJson(BuildToolCatalog.catalogToJson(catalog))
        assertEquals(catalog.jdk, parsed?.jdk)
        assertEquals(catalog.cmake, parsed?.cmake)
        assertEquals(catalog.ndk, parsed?.ndk)
        assertEquals(42L, parsed?.fetchedAt)
        assertNull(BuildToolCatalog.parseCatalogJson("not json"))
        assertTrue("an empty JSON object parses to an empty catalog", BuildToolCatalog.parseCatalogJson("{}")?.isEmpty == true)
    }

    // ------------------------------------------------------------------
    // Pagination windowing (pure logic used by the Tools UI)
    // ------------------------------------------------------------------

    @Test
    fun paginationWindowShowsFiveThenFiveMoreUntilExhausted() {
        val newestFirst = (17 downTo 1).map { "v$it" }
        assertEquals(
            listOf("v17", "v16", "v15", "v14", "v13"),
            VersionPagination.window(newestFirst, 0)
        )
        assertEquals(5, VersionPagination.visibleCount(newestFirst.size, 0))
        assertEquals(10, VersionPagination.visibleCount(newestFirst.size, 1))
        assertEquals(15, VersionPagination.visibleCount(newestFirst.size, 2))
        assertEquals(17, VersionPagination.visibleCount(newestFirst.size, 3))
        assertEquals("expansions beyond the total clamp to the total", 17, VersionPagination.visibleCount(newestFirst.size, 99))
        assertTrue(VersionPagination.hasMore(newestFirst.size, 0))
        assertTrue(VersionPagination.hasMore(newestFirst.size, 2))
        assertFalse("the row disappears when everything is visible", VersionPagination.hasMore(newestFirst.size, 3))
        assertEquals(
            listOf("v12", "v11", "v10", "v9", "v8"),
            VersionPagination.nextBatchAfter(newestFirst, 0)
        )
        assertEquals(
            listOf("v7", "v6", "v5", "v4", "v3"),
            VersionPagination.nextBatchAfter(newestFirst, 1)
        )
        assertEquals("the last batch may be partial", listOf("v2", "v1"), VersionPagination.nextBatchAfter(newestFirst, 2))
        assertTrue(VersionPagination.nextBatchAfter(newestFirst, 3).isEmpty())
    }

    @Test
    fun paginationHandlesSmallAndEmptyLists() {
        val three = listOf("a", "b", "c")
        assertEquals(three, VersionPagination.window(three, 0))
        assertFalse("no row when everything fits on the first page", VersionPagination.hasMore(three.size, 0))
        assertEquals(0, VersionPagination.visibleCount(0, 0))
        assertFalse(VersionPagination.hasMore(0, 0))
        assertTrue(VersionPagination.window(emptyList<String>(), 0).isEmpty())
        assertEquals("custom page size respected", 2, VersionPagination.visibleCount(10, 0, pageSize = 2))
        assertEquals("negative expansions treated as zero", 5, VersionPagination.visibleCount(10, -3))
    }
}
