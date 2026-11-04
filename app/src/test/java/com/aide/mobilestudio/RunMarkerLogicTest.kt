package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pure-logic tests for the run-state markers and freeze-duration formatting.
 * No Android runtime: everything goes through [RunMarkerLogic] and
 * [UiFreezeDetector.formatDuration] (companion function, no instance needed).
 */
class RunMarkerLogicTest {

    // ------------------------------------------------------------ encode/decode

    @Test
    fun encodeDecodeRoundTrip() {
        val encoded = RunMarkerLogic.encode(4242, 123_456L, 987_654_321L)
        assertEquals("pid=4242;uptime=123456;wall=987654321", encoded)
        assertEquals(
            RunMarkerLogic.HeartbeatMarker(4242, 123_456L, 987_654_321L),
            RunMarkerLogic.decode(encoded)
        )
    }

    @Test
    fun decodeRejectsMalformedInput() {
        assertNull(RunMarkerLogic.decode(""))
        assertNull(RunMarkerLogic.decode("garbage"))
        assertNull(RunMarkerLogic.decode("pid=abc;uptime=1;wall=2"))
        assertNull(RunMarkerLogic.decode("pid=1;uptime=2")) // missing wall
        assertNull(RunMarkerLogic.decode("uptime=2;wall=3")) // missing pid
        assertNull(RunMarkerLogic.decode("pid=1;uptime=x;wall=3"))
        assertNull(RunMarkerLogic.decode("pid=1;uptime=2;wall="))
    }

    @Test
    fun decodeToleratesWhitespaceAndExtraFields() {
        val decoded = RunMarkerLogic.decode(" pid=7 ; uptime=10 ; wall=20 ; extra=zz ")
        assertEquals(RunMarkerLogic.HeartbeatMarker(7, 10, 20), decoded)
    }

    @Test
    fun markerPathHelpersAreStable() {
        val filesDir = File("/tmp/fake-files-dir")
        assertEquals(
            File("/tmp/fake-files-dir/diagnostics/ui_heartbeat.marker"),
            RunMarkerLogic.heartbeatMarkerFile(filesDir)
        )
        assertEquals(
            File("/tmp/fake-files-dir/diagnostics/clean-exit.marker"),
            RunMarkerLogic.cleanExitMarkerFile(filesDir)
        )
    }

    // ------------------------------------------------------------ verdicts

    @Test
    fun noMarkerYieldsNoMarkerVerdict() {
        assertEquals(
            RunMarkerLogic.Verdict.NO_MARKER,
            RunMarkerLogic.evaluate(null, currentPid = 1, nowUptimeMs = 100, cleanExitMarkerPresent = false)
        )
    }

    @Test
    fun samePidMeansSameProcessRegardlessOfCleanMarker() {
        val marker = RunMarkerLogic.decode(RunMarkerLogic.encode(pid = 1, uptimeMs = 50, wallMs = 500))!!
        assertEquals(
            RunMarkerLogic.Verdict.SAME_PROCESS,
            RunMarkerLogic.evaluate(marker, currentPid = 1, nowUptimeMs = 100, cleanExitMarkerPresent = true)
        )
        assertEquals(
            RunMarkerLogic.Verdict.SAME_PROCESS,
            RunMarkerLogic.evaluate(marker, currentPid = 1, nowUptimeMs = 100, cleanExitMarkerPresent = false)
        )
    }

    @Test
    fun uptimeGoingBackwardsMeansDeviceRebooted() {
        // Marker written before a reboot: its uptime is in the future.
        val marker = RunMarkerLogic.decode(RunMarkerLogic.encode(pid = 42, uptimeMs = 500_000, wallMs = 1_000_000))!!
        assertEquals(
            RunMarkerLogic.Verdict.REBOOTED,
            RunMarkerLogic.evaluate(marker, currentPid = 99, nowUptimeMs = 1_000, cleanExitMarkerPresent = true)
        )
    }

    @Test
    fun uptimeWithinSlackIsNotAReboot() {
        // Clock jitter / tiny drift must not be reported as a reboot.
        val marker = RunMarkerLogic.decode(RunMarkerLogic.encode(pid = 42, uptimeMs = 500_000, wallMs = 1_000_000))!!
        assertNotEquals(
            RunMarkerLogic.Verdict.REBOOTED,
            RunMarkerLogic.evaluate(
                marker, currentPid = 99,
                nowUptimeMs = 500_000 + RunMarkerLogic.REBOOT_SLACK_MS,
                cleanExitMarkerPresent = true
            )
        )
    }

    @Test
    fun deepSleepIsNotAReboot() {
        // Wall clock raced far ahead of uptime (device slept) but uptime never
        // went backwards: this is normal, never a reboot.
        val marker = RunMarkerLogic.decode(RunMarkerLogic.encode(pid = 42, uptimeMs = 1_000_000, wallMs = 1_000_000_000))!!
        assertNotEquals(
            RunMarkerLogic.Verdict.REBOOTED,
            RunMarkerLogic.evaluate(marker, currentPid = 99, nowUptimeMs = 1_000_500, cleanExitMarkerPresent = true)
        )
    }

    @Test
    fun cleanCheckpointPresentMeansPreviousProcessDiedInBackground() {
        val marker = RunMarkerLogic.decode(RunMarkerLogic.encode(pid = 42, uptimeMs = 100, wallMs = 200))!!
        assertEquals(
            RunMarkerLogic.Verdict.DIED_IN_BACKGROUND,
            RunMarkerLogic.evaluate(marker, currentPid = 43, nowUptimeMs = 900_000, cleanExitMarkerPresent = true)
        )
    }

    @Test
    fun missingCleanCheckpointMeansPreviousProcessDiedInForeground() {
        val marker = RunMarkerLogic.decode(RunMarkerLogic.encode(pid = 42, uptimeMs = 100, wallMs = 200))!!
        assertEquals(
            RunMarkerLogic.Verdict.DIED_IN_FOREGROUND,
            RunMarkerLogic.evaluate(marker, currentPid = 43, nowUptimeMs = 900_000, cleanExitMarkerPresent = false)
        )
    }

    // ------------------------------------------------------------ notice text

    @Test
    fun noticeMentionsCrashLogWhenPresent() {
        val notice = RunMarkerLogic.abnormalExitNotice(isKorean = false, latestCrashLogName = "error-2026-01-01.log")
        assertTrue(notice.contains("Previous run terminated unexpectedly"))
        assertTrue(notice.contains("error-2026-01-01.log"))
    }

    @Test
    fun noticeWithoutLogSaysSo() {
        val notice = RunMarkerLogic.abnormalExitNotice(isKorean = false, latestCrashLogName = null)
        assertTrue(notice.contains("Previous run terminated unexpectedly"))
        assertTrue(notice.contains("No crash log"))
    }

    @Test
    fun koreanNoticeVariants() {
        val withLog = RunMarkerLogic.abnormalExitNotice(isKorean = true, latestCrashLogName = "error-x.log")
        val withoutLog = RunMarkerLogic.abnormalExitNotice(isKorean = true, latestCrashLogName = null)
        assertTrue(withLog.contains("이전 실행이 비정상 종료되었습니다"))
        assertTrue(withLog.contains("error-x.log"))
        assertTrue(withoutLog.contains("이전 실행이 비정상 종료되었습니다"))
        assertTrue(withoutLog.contains("크래시 로그가 없습니다"))
    }

    // ------------------------------------------------------------ duration formatting

    @Test
    fun formatDurationBuckets() {
        assertEquals("0ms", UiFreezeDetector.formatDuration(0))
        assertEquals("999ms", UiFreezeDetector.formatDuration(999))
        assertEquals("1.0s", UiFreezeDetector.formatDuration(1000))
        assertEquals("2.5s", UiFreezeDetector.formatDuration(2500))
        assertEquals("1m 1s", UiFreezeDetector.formatDuration(61_000))
        assertEquals("2m 5s", UiFreezeDetector.formatDuration(125_000))
    }
}
