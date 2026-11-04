package com.aide.mobilestudio

import java.util.Calendar
import java.util.TimeZone

/**
 * Pure, JVM-only version logic behind the app-update check.
 *
 * Release convention: builds are identified by a date token of the form
 * yy.M.d (e.g. "26.9.21" -> 2026-09-21) that may appear anywhere in the APK
 * asset name, the release tag, or the release name — the rest of the name is
 * free-form. A build without any date token is ordered by its ISO-8601
 * timestamp (asset updated_at / release published_at) instead.
 *
 * Deliberately free of Android imports so every rule is unit-testable on the
 * host JVM.
 */
object AppUpdateVersionLogic {

    /** yy.M.d anywhere in the text, e.g. "MobileStudio-26.9.21.apk" -> 26.9.21. */
    private val DATE_TOKEN = Regex("""(\d{2})\.(\d{1,2})\.(\d{1,2})""")

    /**
     * ISO-8601 timestamp as emitted by GitHub, e.g.
     * "2026-09-21T18:30:00Z", with optional fractional seconds and either
     * "Z" or a numeric UTC offset. The separator after the date may be
     * "T", "t" or a space.
     */
    private val ISO_TIMESTAMP = Regex(
        """(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d+)?(Z|z|[+-]\d{2}:?\d{2})?"""
    )

    /**
     * A build identity: an optional (year, month, day) date as the primary
     * key plus a timestamp used for tie-breaking (and as the only key when
     * no date token was found).
     */
    data class BuildVersion(
        val year: Int = NO_DATE,
        val month: Int = NO_DATE,
        val day: Int = NO_DATE,
        val timestampMs: Long = 0L
    ) : Comparable<BuildVersion> {
        val hasDate: Boolean get() = year != NO_DATE

        override fun compareTo(other: BuildVersion): Int = compare(this, other)

        companion object {
            /** Sentinel year/month/day meaning "no date token was present". */
            const val NO_DATE = -1
        }
    }

    /**
     * One downloadable APK asset plus every string a version can be derived
     * from. Plain data so tests can construct it without JSON or network.
     */
    data class ApkCandidate(
        val assetName: String,
        val downloadUrl: String,
        val sizeBytes: Long = 0,
        val tag: String = "",
        val releaseName: String = "",
        val assetUpdatedAtIso: String = "",
        val releasePublishedAtIso: String = "",
        val releaseBody: String = ""
    ) {
        val version: BuildVersion by lazy {
            deriveVersion(
                assetName = assetName,
                tag = tag,
                releaseName = releaseName,
                timestampMs = timestamp(assetUpdatedAtIso, releasePublishedAtIso)
            )
        }
    }

    // ---------------------------------------------------------------- date token

    /** 2-digit year pivot: 00-69 means 2000-2069, everything else 1900+. */
    fun expandYear(twoDigitYear: Int): Int =
        if (twoDigitYear in 0..69) 2000 + twoDigitYear else 1900 + twoDigitYear

    /**
     * First plausible yy.M.d token in [text] as (year, month, day) with the
     * year expanded. Month/day outside 1..12 / 1..31 are rejected (random
     * numbers in a file name are not dates) and scanning continues with the
     * next match. Returns null when nothing plausible is found.
     */
    fun parseDateToken(text: String?): IntArray? {
        if (text.isNullOrBlank()) return null
        for (match in DATE_TOKEN.findAll(text)) {
            val groups = match.groupValues
            val yy = groups[1].toIntOrNull() ?: continue
            val month = groups[2].toIntOrNull() ?: continue
            val day = groups[3].toIntOrNull() ?: continue
            if (month in 1..12 && day in 1..31) {
                return intArrayOf(expandYear(yy), month, day)
            }
        }
        return null
    }

    // ---------------------------------------------------------------- timestamps

    /**
     * Epoch milliseconds for an ISO-8601 timestamp, 0 when it cannot be
     * parsed. Only relative comparisons between parsed values are meaningful.
     */
    fun parseTimestampMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        val match = ISO_TIMESTAMP.find(iso) ?: return 0L
        val g = match.groupValues
        val year = g[1].toIntOrNull() ?: return 0L
        val month = g[2].toIntOrNull() ?: return 0L
        val day = g[3].toIntOrNull() ?: return 0L
        val hour = g[4].toIntOrNull() ?: return 0L
        val minute = g[5].toIntOrNull() ?: return 0L
        val second = g[6].toIntOrNull() ?: return 0L

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        var ms = cal.timeInMillis - offsetMillis(g[8])
        val fraction = g[7]
        if (fraction.length > 1) {
            ms += fraction.substring(1).padEnd(3, '0').take(3).toLong()
        }
        return ms
    }

    /** First usable timestamp: [primaryIso] when parseable, else [fallbackIso]. */
    fun timestamp(primaryIso: String?, fallbackIso: String?): Long {
        parseTimestampMs(primaryIso).takeIf { it != 0L }?.let { return it }
        return parseTimestampMs(fallbackIso)
    }

    private fun offsetMillis(zone: String): Long {
        if (zone.isBlank() || zone.equals("Z", ignoreCase = true)) return 0L
        val sign = if (zone.startsWith("-")) -1L else 1L
        val digits = zone.substring(1).replace(":", "")
        if (digits.length < 4) return 0L
        val hours = digits.substring(0, 2).toLongOrNull() ?: return 0L
        val minutes = digits.substring(2, 4).toLongOrNull() ?: return 0L
        return sign * (hours * 60 + minutes) * 60_000L
    }

    // ---------------------------------------------------------------- build versions

    /**
     * A build from a single version-ish text (an installed versionName, for
     * example) plus a timestamp baseline. The date token in [text], when
     * present, is the primary key; [timestampMs] only breaks ties.
     */
    fun buildVersion(text: String?, timestampMs: Long): BuildVersion {
        val date = parseDateToken(text) ?: return BuildVersion(timestampMs = timestampMs)
        return BuildVersion(date[0], date[1], date[2], timestampMs)
    }

    /**
     * A build from an APK asset and its surrounding release: the date token
     * is taken from the asset name first, then the tag, then the release
     * name; with no token anywhere the timestamp is the only key.
     */
    fun deriveVersion(
        assetName: String?,
        tag: String?,
        releaseName: String?,
        timestampMs: Long
    ): BuildVersion {
        parseDateToken(assetName)?.let { return BuildVersion(it[0], it[1], it[2], timestampMs) }
        parseDateToken(tag)?.let { return BuildVersion(it[0], it[1], it[2], timestampMs) }
        parseDateToken(releaseName)?.let { return BuildVersion(it[0], it[1], it[2], timestampMs) }
        return BuildVersion(timestampMs = timestampMs)
    }

    /**
     * Total order on builds: a dated build beats an undated one; two dated
     * builds compare by (year, month, day) tuple; equal tuples — and any two
     * undated builds — fall back to the timestamp.
     */
    fun compare(a: BuildVersion, b: BuildVersion): Int {
        if (a.hasDate != b.hasDate) return if (a.hasDate) 1 else -1
        if (a.hasDate) {
            var c = a.year.compareTo(b.year)
            if (c != 0) return c
            c = a.month.compareTo(b.month)
            if (c != 0) return c
            c = a.day.compareTo(b.day)
            if (c != 0) return c
        }
        return a.timestampMs.compareTo(b.timestampMs)
    }

    /** True when [candidate] is strictly newer than [installed]. */
    fun isNewer(installed: BuildVersion, candidate: BuildVersion): Boolean =
        compare(candidate, installed) > 0

    /** Human-readable yy.M.d form, or "" when the build has no date token. */
    fun displayVersion(version: BuildVersion): String {
        if (!version.hasDate) return ""
        val yy = if (version.year in 2000..2069) version.year - 2000 else version.year - 1900
        return "$yy.${version.month}.${version.day}"
    }

    // ---------------------------------------------------------------- discovery

    /** An asset is a candidate purely by its .apk extension, any naming. */
    fun isApkAsset(name: String): Boolean =
        name.isNotBlank() && name.endsWith(".apk", ignoreCase = true)

    /**
     * Newest APK among mixed-naming candidates. Ties keep the candidate that
     * appeared first (GitHub lists newest releases first). Returns null when
     * nothing usable is offered.
     */
    fun selectBestApk(candidates: List<ApkCandidate>): ApkCandidate? =
        candidates
            .filter { isApkAsset(it.assetName) && it.downloadUrl.isNotBlank() }
            .maxWithOrNull(compareBy { it.version })
}
