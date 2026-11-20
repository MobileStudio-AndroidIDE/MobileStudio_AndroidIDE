package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Logcat reader.
 *
 * Tries in order:
 *  1. `adb logcat` against the on-device adb daemon (loopback :5555) — full system log.
 *  2. Plain `logcat` as a fallback (limited to what the app UID may read).
 */
class LogcatManager(
    private val context: Context,
    private val onLine: (raw: String, colored: SpannableString) -> Unit,
    private val onError: (String) -> Unit,
    private val onMode: ((mode: String) -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var paused  = false
    @Volatile private var filterPackage: String? = null
    @Volatile private var filterPid: Int = 0
    private var thread:  Thread?  = null
    private var process: Process? = null

    @Volatile var mode: String = ""
        private set

    fun setFilterPackage(pkg: String?) { filterPackage = pkg }
    fun setFilterPid(pid: Int) { filterPid = pid }

    // ============================================================
    // ADB resolution
    // ============================================================

    fun resolveAdbBinary(): String? {
        val candidates = mutableListOf<File>()
        runCatching {
            ToolchainManager(context).activeDir(ToolchainManager.Kind.SDK)
                ?.let { candidates.add(File(it, "platform-tools/adb")) }
        }
        candidates.add(File(context.filesDir, "toolchain/sdk/platform-tools/adb"))
        candidates.add(File(context.filesDir, "platform-tools/adb"))
        candidates.forEach { if (it.isFile) return it.absolutePath }
        return null
    }

    private fun hasAdbDevice(adb: String): Boolean = runCatching {
        val p = ProcessBuilder(adb, "devices").redirectErrorStream(true).start()
        if (!p.waitFor(5, TimeUnit.SECONDS)) return false
        p.inputStream.bufferedReader().readText().lines().any {
            val parts = it.trim().split(Regex("\\s+"))
            parts.size >= 2 && parts[1] == "device"
        }
    }.getOrDefault(false)

    private fun tryEnableAdbTcpWithSu() {
        // Only attempt the privileged path on a device that actually exposes
        // a `su` binary. On a stock (non-rooted) physical device this avoids a
        // pointless process spawn / 2s wait; the caller falls back to plain
        // `logcat` (what the app UID is allowed to read) either way.
        val hasSu = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su")
            .any { java.io.File(it).exists() }
        if (!hasSu) return
        runCatching {
            val p = ProcessBuilder(
                "su", "-c",
                "setprop service.adb.tcp.port 5555; stop adbd; start adbd"
            ).redirectErrorStream(true).start()
            p.waitFor(2, TimeUnit.SECONDS)
        }
    }

    private fun connectLocalAdbd(adb: String): Boolean {
        runCatching {
            ProcessBuilder(adb, "connect", "127.0.0.1:5555")
                .redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS)
        }
        if (hasAdbDevice(adb)) return true
        runCatching { tryEnableAdbTcpWithSu() }
        runCatching {
            ProcessBuilder(adb, "connect", "127.0.0.1:5555")
                .redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS)
        }
        return hasAdbDevice(adb)
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            try {
                val adb = resolveAdbBinary()
                val viaAdb = adb != null && connectLocalAdbd(adb)
                mode = when {
                    viaAdb          -> "adb logcat (127.0.0.1:5555)"
                    adb != null     -> "direct logcat (adb daemon unreachable)"
                    else            -> "direct logcat (adb not installed)"
                }
                mainHandler.post { onMode?.invoke(mode) }

                val cmd = if (viaAdb) {
                    val args = mutableListOf(adb!!, "logcat", "-v", "time")
                    if (filterPid > 0) args.add("--pid=$filterPid") else args.add("*:V")
                    args
                } else if (filterPid > 0) listOf("logcat", "--pid=$filterPid")
                else listOf("logcat", "-v", "time", "*:V")

                process = ExecHelper.createProcess(context, cmd, cwd = null, env = System.getenv()).redirectErrorStream(true).start()

                val buffer    = ArrayList<String>(80)
                var lastFlush = System.currentTimeMillis()
                var dropped   = 0

                process!!.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        if (!running) return@forEachLine

                        if (paused) return@forEachLine

                        // PID filter: check if log line PID matches
                        if (filterPid > 0) {
                            val parts = line.split("\\s+".toRegex())
                            if (parts.size >= 3) {
                                val logPid = parts[2].toIntOrNull()
                                if (logPid != null && logPid != filterPid) return@forEachLine
                            }
                        }

                        // Package filter: check if TAG starts with package name
                        val pkg = filterPackage
                        if (pkg != null && pkg.isNotBlank() && filterPid <= 0) {
                            val parts = line.split("\\s+".toRegex()).drop(5)
                            val tag = (parts.firstOrNull() ?: "").trimEnd(':')
                            if (!tag.startsWith(pkg)) return@forEachLine
                        }

                        if (buffer.size >= MAX_BUFFERED_LINES) {
                            buffer.removeAt(0)
                            dropped++
                        }
                        buffer.add(line)

                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 500 || buffer.size >= 50) {
                            val snapshot = buffer.toList()
                            val droppedSnapshot = dropped
                            buffer.clear()
                            dropped = 0
                            lastFlush = now
                            mainHandler.post {
                                if (droppedSnapshot > 0) {
                                    onLine("skipped $droppedSnapshot logcat lines to keep UI responsive", colorize(" W skipped"))
                                }
                                snapshot.take(MAX_LINES_PER_UI_FLUSH).forEach { l ->
                                    onLine(l, colorize(l))
                                }
                            }
                        }
                    }
                }
            } catch (e: InterruptedException) {
                // Normal stop
            } catch (e: Exception) {
                if (running) mainHandler.post { onError("Logcat error: ${e.message}") }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        process?.destroy(); process = null
        thread?.interrupt();  thread  = null
    }

    fun restart() {
        stop()
        start()
    }

    fun pause()    { paused = true  }
    fun resume()   { paused = false }
    fun isPaused() = paused

    fun clear() {
        val adb = resolveAdbBinary()
        try {
            if (adb != null && mode.startsWith("adb")) Runtime.getRuntime().exec(arrayOf(adb, "logcat", "-c"))
            else Runtime.getRuntime().exec(arrayOf("logcat", "-c"))
        } catch (_: Exception) {}
    }

    // ============================================================
    // Colorize
    // ============================================================

    private fun colorize(line: String): SpannableString {
        val ss    = SpannableString(line)
        val color = when {
            line.contains(" E ") || line.contains(" E/") -> StudioUi.color(StudioUi.Red)
            line.contains(" W ") || line.contains(" W/") -> StudioUi.color(StudioUi.Amber)
            line.contains(" I ") || line.contains(" I/") -> StudioUi.color(StudioUi.Green)
            line.contains(" D ") || line.contains(" D/") -> StudioUi.color(StudioUi.ActiveBlue)
            line.contains(" V ") || line.contains(" V/") -> StudioUi.color(StudioUi.TextSoft)
            else                                          -> StudioUi.color(StudioUi.TextSoft)
        }
        ss.setSpan(ForegroundColorSpan(color), 0, line.length,
            SpannableString.SPAN_INCLUSIVE_EXCLUSIVE)
        return ss
    }

    private companion object {
        const val MAX_BUFFERED_LINES = 500
        const val MAX_LINES_PER_UI_FLUSH = 80
    }
}