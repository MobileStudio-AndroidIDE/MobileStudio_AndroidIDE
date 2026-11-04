package com.aide.mobilestudio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the app-update discovery and comparison rules.
 * No network, no Android classes: everything goes through
 * [AppUpdateVersionLogic] with injected values.
 */
class AppUpdateVersionLogicTest {

    private fun version(y: Int, m: Int, d: Int, ts: Long = 0) =
        AppUpdateVersionLogic.BuildVersion(y, m, d, ts)

    private fun undated(ts: Long) = AppUpdateVersionLogic.BuildVersion(timestampMs = ts)

    private fun candidate(
        assetName: String,
        tag: String = "",
        releaseName: String = "",
        updatedAt: String = "",
        publishedAt: String = "",
        url: String = "https://example.com/$assetName"
    ) = AppUpdateVersionLogic.ApkCandidate(
        assetName = assetName,
        downloadUrl = url,
        tag = tag,
        releaseName = releaseName,
        assetUpdatedAtIso = updatedAt,
        releasePublishedAtIso = publishedAt
    )

    // ------------------------------------------------------------ date tokens in names

    @Test
    fun `date token is found in an arbitrary apk file name`() {
        assertArrayEquals(intArrayOf(2026, 9, 21), AppUpdateVersionLogic.parseDateToken("MobileStudio-26.9.21.apk"))
        assertArrayEquals(intArrayOf(2026, 10, 1), AppUpdateVersionLogic.parseDateToken("whatever_build_26.10.1-arm64.apk"))
    }

    @Test
    fun `single digit months and days parse as integers not text`() {
        // tuple comparison, not lexicographic: day 2 vs day 21
        assertArrayEquals(intArrayOf(2026, 9, 2), AppUpdateVersionLogic.parseDateToken("app-26.9.2.apk"))
        assertTrue(AppUpdateVersionLogic.compare(version(2026, 9, 2), version(2026, 9, 21)) < 0)
    }

    @Test
    fun `four digit year name still yields the yyMd token`() {
        assertArrayEquals(intArrayOf(2026, 9, 21), AppUpdateVersionLogic.parseDateToken("app-2026.9.21.apk"))
    }

    @Test
    fun `implausible month or day is skipped in favour of the next match`() {
        assertArrayEquals(intArrayOf(2026, 9, 21), AppUpdateVersionLogic.parseDateToken("build-16.34.5_26.9.21.apk"))
        assertArrayEquals(intArrayOf(2025, 12, 31), AppUpdateVersionLogic.parseDateToken("26.9.32-and-25.12.31.apk"))
    }

    @Test
    fun `names without a date token yield null`() {
        assertNull(AppUpdateVersionLogic.parseDateToken("random-name.apk"))
        assertNull(AppUpdateVersionLogic.parseDateToken("MobileStudio-1.2.3.apk"))
        assertNull(AppUpdateVersionLogic.parseDateToken("6.9.21.apk")) // single-digit year: no match
        assertNull(AppUpdateVersionLogic.parseDateToken(""))
        assertNull(AppUpdateVersionLogic.parseDateToken(null))
    }

    @Test
    fun `date token is found in tag and release names`() {
        assertArrayEquals(intArrayOf(2026, 9, 21), AppUpdateVersionLogic.parseDateToken("v26.9.21"))
        assertArrayEquals(intArrayOf(2026, 1, 2), AppUpdateVersionLogic.parseDateToken("release-26.1.2"))
        assertArrayEquals(intArrayOf(2026, 10, 1), AppUpdateVersionLogic.parseDateToken("MobileStudio 26.10.1 Lavender"))
    }

    @Test
    fun `iso timestamps do not accidentally match the date token`() {
        assertNull(AppUpdateVersionLogic.parseDateToken("2026-09-21T18:30:00Z"))
    }

    // ------------------------------------------------------------ 2-digit year pivot

    @Test
    fun `two digit year pivot follows the 00-69 window`() {
        assertEquals(2000, AppUpdateVersionLogic.expandYear(0))
        assertEquals(2026, AppUpdateVersionLogic.expandYear(26))
        assertEquals(2069, AppUpdateVersionLogic.expandYear(69))
        assertEquals(1970, AppUpdateVersionLogic.expandYear(70))
        assertEquals(1999, AppUpdateVersionLogic.expandYear(99))
    }

    // ------------------------------------------------------------ timestamp parsing

    @Test
    fun `timestamps one hour apart differ by exactly 3600000 ms`() {
        val earlier = AppUpdateVersionLogic.parseTimestampMs("2026-09-21T10:00:00Z")
        val later = AppUpdateVersionLogic.parseTimestampMs("2026-09-21T11:00:00Z")
        assertEquals(3_600_000L, later - earlier)
    }

    @Test
    fun `numeric offset is normalised to utc`() {
        val zulu = AppUpdateVersionLogic.parseTimestampMs("2026-09-21T10:00:00Z")
        val seoul = AppUpdateVersionLogic.parseTimestampMs("2026-09-21T19:00:00+09:00")
        assertEquals(0L, seoul - zulu)
    }

    @Test
    fun `fractional seconds are tolerated`() {
        val whole = AppUpdateVersionLogic.parseTimestampMs("2026-09-21T10:00:00Z")
        val frac = AppUpdateVersionLogic.parseTimestampMs("2026-09-21T10:00:00.500Z")
        assertEquals(500L, frac - whole)
    }

    @Test
    fun `space separator is accepted like T`() {
        assertEquals(
            AppUpdateVersionLogic.parseTimestampMs("2026-09-21T10:00:00Z"),
            AppUpdateVersionLogic.parseTimestampMs("2026-09-21 10:00:00Z")
        )
    }

    @Test
    fun `unparseable or missing timestamps map to zero`() {
        assertEquals(0L, AppUpdateVersionLogic.parseTimestampMs(null))
        assertEquals(0L, AppUpdateVersionLogic.parseTimestampMs(""))
        assertEquals(0L, AppUpdateVersionLogic.parseTimestampMs("not a timestamp"))
        assertEquals(0L, AppUpdateVersionLogic.parseTimestampMs("2026-09-21"))
    }

    @Test
    fun `primary timestamp wins over fallback only when parseable`() {
        val good = "2026-09-21T10:00:00Z"
        val better = "2026-09-21T12:00:00Z"
        assertEquals(
            AppUpdateVersionLogic.parseTimestampMs(good),
            AppUpdateVersionLogic.timestamp(good, better)
        )
        assertEquals(
            AppUpdateVersionLogic.parseTimestampMs(better),
            AppUpdateVersionLogic.timestamp("garbage", better)
        )
        assertEquals(0L, AppUpdateVersionLogic.timestamp(null, null))
    }

    // ------------------------------------------------------------ comparison semantics

    @Test
    fun `yyMd tuple comparison orders builds correctly`() {
        assertTrue(AppUpdateVersionLogic.isNewer(version(2026, 9, 20), version(2026, 9, 21)))
        assertTrue(AppUpdateVersionLogic.isNewer(version(2026, 9, 21), version(2026, 10, 1)))
        assertTrue(AppUpdateVersionLogic.isNewer(version(2026, 12, 31), version(2027, 1, 1)))
        assertFalse(AppUpdateVersionLogic.isNewer(version(2026, 9, 21), version(2026, 9, 21)))
    }

    @Test
    fun `equal dates fall back to the timestamp`() {
        val base = version(2026, 9, 21, ts = 1_000)
        assertTrue(AppUpdateVersionLogic.isNewer(base, version(2026, 9, 21, ts = 2_000)))
        assertFalse(AppUpdateVersionLogic.isNewer(base, version(2026, 9, 21, ts = 1_000)))
        assertFalse(AppUpdateVersionLogic.isNewer(base, version(2026, 9, 21, ts = 500)))
    }

    @Test
    fun `a dated build beats an undated build`() {
        // even though the undated build carries a much newer timestamp
        assertTrue(AppUpdateVersionLogic.isNewer(undated(ts = 9_999_999), version(2026, 9, 21, ts = 1)))
    }

    @Test
    fun `undated builds are ordered purely by timestamp`() {
        assertTrue(AppUpdateVersionLogic.isNewer(undated(1_000), undated(2_000)))
        assertFalse(AppUpdateVersionLogic.isNewer(undated(2_000), undated(2_000)))
    }

    // ------------------------------------------------------------ installed build (injected)

    @Test
    fun `installed versionName with a date prefix becomes a dated build`() {
        val installed = AppUpdateVersionLogic.buildVersion("26.8.17 Lavender", timestampMs = 123)
        assertEquals(version(2026, 8, 17, 123), installed)
    }

    @Test
    fun `installed versionName without a date falls back to lastUpdateTime`() {
        assertEquals(undated(555), AppUpdateVersionLogic.buildVersion("1.0", timestampMs = 555))
        assertEquals(undated(0), AppUpdateVersionLogic.buildVersion(null, timestampMs = 0))
    }

    @Test
    fun `installed build compares apples to apples against candidates`() {
        val installed = AppUpdateVersionLogic.buildVersion("26.8.17 Lavender", timestampMs = 1_000)
        assertTrue(AppUpdateVersionLogic.isNewer(installed, AppUpdateVersionLogic.buildVersion("26.9.21", timestampMs = 2_000)))
        assertFalse(AppUpdateVersionLogic.isNewer(installed, AppUpdateVersionLogic.buildVersion("26.8.16", timestampMs = 0)))
        assertFalse(AppUpdateVersionLogic.isNewer(installed, AppUpdateVersionLogic.buildVersion("26.8.17", timestampMs = 500)))
    }

    // ------------------------------------------------------------ version derivation cascade

    @Test
    fun `asset name date wins over tag and release name dates`() {
        val v = AppUpdateVersionLogic.deriveVersion(
            assetName = "MobileStudio-26.9.19.apk",
            tag = "v26.10.5",
            releaseName = "Release 26.11.1",
            timestampMs = 0
        )
        assertEquals(version(2026, 9, 19), v)
    }

    @Test
    fun `tag date is used when the asset name has none`() {
        val v = AppUpdateVersionLogic.deriveVersion(
            assetName = "app-release.apk",
            tag = "v26.10.5",
            releaseName = "Release 26.11.1",
            timestampMs = 0
        )
        assertEquals(version(2026, 10, 5), v)
    }

    @Test
    fun `release name date is used when tag and asset name have none`() {
        val v = AppUpdateVersionLogic.deriveVersion(
            assetName = "app-release.apk",
            tag = "lavender",
            releaseName = "MobileStudio 26.12.3",
            timestampMs = 0
        )
        assertEquals(version(2026, 12, 3), v)
    }

    @Test
    fun `no date anywhere leaves the timestamp as the only key`() {
        val v = AppUpdateVersionLogic.deriveVersion("app.apk", "v3", "misc", timestampMs = 777)
        assertEquals(undated(777), v)
    }

    @Test
    fun `candidate version prefers asset updated_at over release published_at`() {
        val c = candidate(
            assetName = "app.apk",
            updatedAt = "2026-09-21T10:00:00Z",
            publishedAt = "2026-09-22T10:00:00Z"
        )
        assertEquals(
            AppUpdateVersionLogic.parseTimestampMs("2026-09-21T10:00:00Z"),
            c.version.timestampMs
        )
    }

    @Test
    fun `candidate version falls back to published_at when asset timestamp is missing`() {
        val c = candidate(assetName = "app.apk", publishedAt = "2026-09-22T10:00:00Z")
        assertEquals(
            AppUpdateVersionLogic.parseTimestampMs("2026-09-22T10:00:00Z"),
            c.version.timestampMs
        )
    }

    // ------------------------------------------------------------ newest-asset selection

    @Test
    fun `newest dated asset wins regardless of file naming`() {
        val best = AppUpdateVersionLogic.selectBestApk(
            listOf(
                candidate(assetName = "MobileStudio-26.9.20.apk"),
                candidate(assetName = "totally_unrelated_final_v2.apk", tag = "v26.9.20"),
                candidate(assetName = "MobileStudio-26.9.21.apk"),
                candidate(assetName = "old-26.1.1.apk")
            )
        )
        assertEquals("MobileStudio-26.9.21.apk", best?.assetName)
    }

    @Test
    fun `month ordering beats day ordering across releases`() {
        val best = AppUpdateVersionLogic.selectBestApk(
            listOf(
                candidate(assetName = "a-26.9.21.apk"),
                candidate(assetName = "b-26.10.1.apk")
            )
        )
        assertEquals("b-26.10.1.apk", best?.assetName)
    }

    @Test
    fun `asset name date overrides the release tag when ranking assets`() {
        // both assets belong to the release tagged v26.10.5, but the date in
        // each asset's own name decides how new that asset is
        val best = AppUpdateVersionLogic.selectBestApk(
            listOf(
                candidate(assetName = "MobileStudio-26.9.19.apk", tag = "v26.10.5"),
                candidate(assetName = "MobileStudio-26.10.5.apk", tag = "v26.10.5")
            )
        )
        assertEquals("MobileStudio-26.10.5.apk", best?.assetName)
    }

    @Test
    fun `undated assets are picked by timestamp`() {
        val best = AppUpdateVersionLogic.selectBestApk(
            listOf(
                candidate(assetName = "first.apk", publishedAt = "2026-09-01T00:00:00Z"),
                candidate(assetName = "second.apk", publishedAt = "2026-09-30T00:00:00Z"),
                candidate(assetName = "third.apk", publishedAt = "2026-09-15T00:00:00Z")
            )
        )
        assertEquals("second.apk", best?.assetName)
    }

    @Test
    fun `dated asset beats a newer-timestamped undated asset`() {
        val best = AppUpdateVersionLogic.selectBestApk(
            listOf(
                candidate(assetName = "MobileStudio-26.9.21.apk", publishedAt = "2026-09-21T00:00:00Z"),
                candidate(assetName = "unsigned-master-build.apk", publishedAt = "2026-10-10T00:00:00Z")
            )
        )
        assertEquals("MobileStudio-26.9.21.apk", best?.assetName)
    }

    @Test
    fun `uppercase APK extension is accepted`() {
        val best = AppUpdateVersionLogic.selectBestApk(
            listOf(candidate(assetName = "MobileStudio-26.9.21.APK"))
        )
        assertEquals("MobileStudio-26.9.21.APK", best?.assetName)
    }

    @Test
    fun `non apk assets and blank urls are ignored`() {
        val best = AppUpdateVersionLogic.selectBestApk(
            listOf(
                candidate(assetName = "sources.zip", tag = "v26.11.1"),
                candidate(assetName = "MobileStudio-26.10.1.apk", url = ""),
                candidate(assetName = "MobileStudio-26.9.30.apk")
            )
        )
        assertEquals("MobileStudio-26.9.30.apk", best?.assetName)
    }

    @Test
    fun `no usable candidates yields null`() {
        assertNull(AppUpdateVersionLogic.selectBestApk(emptyList()))
        assertNull(AppUpdateVersionLogic.selectBestApk(listOf(candidate(assetName = "readme.txt"))))
    }

    // ------------------------------------------------------------ display

    @Test
    fun `display version renders yyMd`() {
        assertEquals("26.9.21", AppUpdateVersionLogic.displayVersion(version(2026, 9, 21)))
        assertEquals("26.10.1", AppUpdateVersionLogic.displayVersion(version(2026, 10, 1)))
        assertEquals("", AppUpdateVersionLogic.displayVersion(undated(123)))
    }
}
