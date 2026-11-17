package com.aide.mobilestudio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Central state shared by every on-device tool downloader (JDK, Gradle, NDK,
 * CMake, LLVM) and the MobileStudio Downloader window rendered by
 *
 * Downloads used to spam the build log with hundreds of "Downloaded 4MB /
 * 1962MB" lines. They now publish one live task here instead: the UI shows the
 * file, size, progress, percentage and transfer speed, and asks for
 * confirmation before the first byte is fetched.
 *
 * [confirm] blocks the calling provisioning thread until the user presses
 * Yes/No. When the Studio UI is not attached (for example a headless build
 * started from another activity) it approves automatically so legacy flows
 * keep working exactly as before.
 */
object DownloadCenter {

    enum class Status { CONFIRM, DOWNLOADING, DONE, FAILED, CANCELLED }

    data class Task(
        val label: String,
        val url: String,
        val downloadPath: String,
        val fileName: String,
        val totalBytes: Long,
        val status: Status,
        val downloadedBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L,
        val message: String = ""
    ) {
        val percent: Int
            get() = if (totalBytes > 0) {
                ((downloadedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
            } else 0
    }

    val state = mutableStateOf<Task?>(null)

    @Volatile
    var uiAttached: Boolean = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val speedLock = Any()
    private var confirmLatch: CountDownLatch? = null
    @Volatile private var approved = false
    @Volatile private var cancelRequested = false

    private var lastProgressBytes = 0L
    private var lastProgressAt = 0L
    private var lastSpeedBytesPerSec = 0L
    private var lastUiPostAt = 0L

    fun attach() {
        uiAttached = true
    }

    fun detach() {
        uiAttached = false
    }

    /** Returns the asset size without downloading the body, or 0 if unknown. */
    fun probeContentLength(urlStr: String): Long {
        // HEAD is cheapest; some release hosts ignore HEAD, so fall back to a
        // one-byte range request and read the total from Content-Range.
        val byHead = runCatching { headLength(urlStr) }.getOrDefault(0L)
        if (byHead > 0) return byHead
        return runCatching { rangeLength(urlStr) }.getOrDefault(0L)
    }

    private fun open(urlStr: String): HttpURLConnection =
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
        }

    private fun headLength(urlStr: String): Long {
        val conn = open(urlStr)
        return try {
            conn.requestMethod = "HEAD"
            conn.connect()
            conn.contentLengthLong
        } finally {
            conn.disconnect()
        }
    }

    private fun rangeLength(urlStr: String): Long {
        val conn = open(urlStr)
        return try {
            conn.setRequestProperty("Range", "bytes=0-0")
            conn.connect()
            val header = conn.getHeaderField("Content-Range")
            header?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: 0L
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Shows the "Download? Yes / No" prompt and blocks until answered.
     * Returns true when the download may start.
     */
    fun confirm(
        label: String,
        url: String,
        downloadPath: String,
        fileName: String,
        totalBytes: Long
    ): Boolean {
        cancelRequested = false
        if (!uiAttached) return true

        approved = false
        val latch = CountDownLatch(1)
        confirmLatch = latch
        resetSpeed()
        main.post {
            state.value = Task(
                label = label,
                url = url,
                downloadPath = downloadPath,
                fileName = fileName,
                totalBytes = totalBytes,
                status = Status.CONFIRM
            )
        }

        val answered = try {
            latch.await(10, TimeUnit.MINUTES)
        } catch (_: InterruptedException) {
            false
        }
        confirmLatch = null
        if (!answered) {
            // The activity went away while the prompt was open. Never leave a
            // build blocked forever: behave like the headless fallback.
            approved = true
        }
        return approved
    }

    fun approve() {
        approved = true
        resetSpeed()
        main.post {
            state.value = state.value?.copy(status = Status.DOWNLOADING)
        }
        confirmLatch?.countDown()
    }

    fun decline() {
        approved = false
        main.post {
            state.value = state.value?.copy(
                status = Status.CANCELLED,
                message = "Download declined by user"
            )
        }
        confirmLatch?.countDown()
    }

    /** Cancels both the download and the provisioning run that started it. */
    fun cancel() {
        cancelRequested = true
        approved = false
        main.post {
            state.value = state.value?.copy(
                status = Status.CANCELLED,
                message = "Download cancelled"
            )
        }
        confirmLatch?.countDown()
        BuildCancellation.cancel()
    }

    fun isCancelled(): Boolean = cancelRequested

    /** Marks the start of a download that was auto-approved (no UI attached). */
    fun begin(
        label: String,
        url: String,
        downloadPath: String,
        fileName: String,
        totalBytes: Long
    ) {
        cancelRequested = false
        resetSpeed()
        main.post {
            val current = state.value
            if (current?.status != Status.CANCELLED) {
                state.value = Task(
                    label = label,
                    url = url,
                    downloadPath = downloadPath,
                    fileName = fileName,
                    totalBytes = totalBytes,
                    status = Status.DOWNLOADING
                )
            }
        }
    }

    fun progress(downloaded: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        var speed: Long
        synchronized(speedLock) {
            when {
                lastProgressAt == 0L -> {
                    lastProgressAt = now
                    lastProgressBytes = downloaded
                    speed = 0L
                }
                now - lastProgressAt >= 200L -> {
                    val delta = downloaded - lastProgressBytes
                    speed = if (delta >= 0) {
                        max(0L, delta * 1000L / (now - lastProgressAt))
                    } else {
                        0L
                    }
                    lastSpeedBytesPerSec = speed
                    lastProgressBytes = downloaded
                    lastProgressAt = now
                }
                else -> speed = lastSpeedBytesPerSec
            }
        }
        val finished = total > 0 && downloaded >= total
        if (!finished && now - lastUiPostAt < 150L) return
        lastUiPostAt = now
        main.post {
            val current = state.value ?: return@post
            if (current.status == Status.CANCELLED) return@post
            state.value = current.copy(
                status = Status.DOWNLOADING,
                downloadedBytes = downloaded.coerceAtLeast(0L),
                totalBytes = if (total > 0) total else current.totalBytes,
                speedBytesPerSec = speed
            )
        }
    }

    /** Keeps the window open during extraction / verification. */
    fun stage(message: String) {
        main.post {
            val current = state.value ?: return@post
            if (current.status != Status.CANCELLED) {
                state.value = current.copy(message = message)
            }
        }
    }

    fun done(message: String = "Download complete") {
        main.post {
            val current = state.value ?: return@post
            if (current.status == Status.CANCELLED) return@post
            state.value = current.copy(
                status = Status.DONE,
                downloadedBytes = if (current.totalBytes > 0) current.totalBytes else current.downloadedBytes,
                speedBytesPerSec = 0L,
                message = message
            )
        }
    }

    fun fail(message: String) {
        main.post {
            val current = state.value ?: return@post
            if (current.status == Status.CANCELLED) return@post
            state.value = current.copy(
                status = Status.FAILED,
                speedBytesPerSec = 0L,
                message = message
            )
        }
    }

    /** Opens an idle Download Manager window from the Tools menu / shell. */
    fun showIdleWindow() {
        main.post {
            if (state.value == null) {
                state.value = Task(
                    label = "Download Manager",
                    url = "",
                    downloadPath = "",
                    fileName = "",
                    totalBytes = 0L,
                    status = Status.DONE,
                    message = "No active downloads."
                )
            }
        }
    }

    fun dismiss() {
        state.value = null
    }

    private fun resetSpeed() {
        synchronized(speedLock) {
            lastProgressBytes = 0L
            lastProgressAt = 0L
            lastSpeedBytesPerSec = 0L
            lastUiPostAt = 0L
        }
    }
}
