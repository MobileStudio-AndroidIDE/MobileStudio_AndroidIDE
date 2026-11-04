package com.aide.mobilestudio

import android.content.Context
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class SwapManager(private val context: Context) {

    private var mappedBuffer: MappedByteBuffer? = null
    private var swapFile: RandomAccessFile? = null
    private var currentSize: Long = 0
    private var monitorThread: Thread? = null
    private var isRunning = false

    companion object {
        private const val TAG = "SwapManager"
        private const val SWAP_DIR = "swap"
        private const val MIN_FREE_STORAGE = 5L * 1024 * 1024 * 1024
        private const val MONITOR_INTERVAL_MS = 15_000L

        fun cleanup(context: Context) {
            runCatching { File(context.filesDir, SWAP_DIR).deleteRecursively() }
        }
    }

    private val swapDir: File get() = File(context.filesDir, SWAP_DIR)
    private val swapFilePath: File get() = File(swapDir, "swapfile.dat")

    fun start(swapSizeGB: Int = 5) {
        stop()

        // 1) Try kernel zram
        if (tryZram(swapSizeGB)) {
            Log.i(TAG, "zram enabled: ${swapSizeGB}GB")
            return
        }

        // 2) Try swap file via swapon (root)
        if (trySwapFile(swapSizeGB)) {
            Log.i(TAG, "swap file enabled: ${swapSizeGB}GB")
            return
        }

        // 3) Fallback: MappedByteBuffer (no root, limited effect)
        isRunning = true
        try {
            swapDir.mkdirs()
            val sizeBytes = swapSizeGB.toLong() * 1024 * 1024 * 1024
            val stats = StatFs(context.filesDir.absolutePath)
            val avail = stats.availableBytes
            val actualSize = minOf(sizeBytes, maxOf(avail - MIN_FREE_STORAGE, 0L))
            // FileChannel.map size must be <= Integer.MAX_VALUE
            val cappedSize = minOf(actualSize, Int.MAX_VALUE.toLong() - 1024)
            if (cappedSize < 256 * 1024 * 1024) {
                Log.w(TAG, "not enough storage for swap")
                return
            }
            swapFile = RandomAccessFile(swapFilePath, "rw")
            swapFile?.setLength(cappedSize)
            mappedBuffer = swapFile?.channel?.map(FileChannel.MapMode.READ_WRITE, 0, cappedSize)
            currentSize = cappedSize
            Log.i(TAG, "mmap swap: ${cappedSize / (1024*1024)}MB")
            monitorThread = Thread { monitorLoop() }.apply {
                isDaemon = true; name = "SwapMonitor"; start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "swap init failed", e)
            stop()
        }
    }

    fun stop() {
        isRunning = false
        monitorThread?.interrupt()
        monitorThread = null
        runCatching { swapFile?.close() }
        swapFile = null
        mappedBuffer = null
        currentSize = 0
        // Disable kernel swap if we enabled it
        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "swapoff /data/swapfile4aide")) }
        runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "swapoff /dev/block/zram0")) }
    }

    private fun tryZram(sizeGB: Int): Boolean {
        return try {
            val sizeBytes = (sizeGB.toLong() * 1024 * 1024 * 1024).toString()
            val proc = Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "echo $sizeBytes > /sys/block/zram0/disksize && mkswap /dev/block/zram0 && swapon /dev/block/zram0 && echo OK"
            ))
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out == "OK"
        } catch (_: Exception) { false }
    }

    private fun trySwapFile(sizeGB: Int): Boolean {
        return try {
            val file = "/data/swapfile4aide"
            val count = (sizeGB * 1024).toString()
            val proc = Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "dd if=/dev/zero of=$file bs=1M count=$count && mkswap $file && swapon $file && echo OK"
            ))
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            out.contains("OK")
        } catch (_: Exception) { false }
    }

    fun getSwapInfo(): Triple<Long, Long, Long> {
        val total = readMemInfo()["SwapTotal"] ?: 0L
        val free = readMemInfo()["SwapFree"] ?: 0L
        return Triple(total, free, currentSize)
    }

    private fun readMemInfo(): Map<String, Long> {
        return try {
            File("/proc/meminfo").readLines().mapNotNull { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val key = parts[0].removeSuffix(":")
                    val value = parts[1].toLongOrNull()
                    if (value != null) key to (value * 1024) else null
                } else null
            }.toMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun monitorLoop() {
        while (isRunning) {
            try {
                Thread.sleep(MONITOR_INTERVAL_MS)
                val mem = readMemInfo()
                val total = mem["MemTotal"] ?: continue
                val avail = mem["MemAvailable"] ?: continue
                val usedPct = (total - avail) * 100 / total
                val swapTotal = mem["SwapTotal"] ?: 0L
                if (usedPct > 80 && swapTotal == 0L && currentSize > 0) {
                    // Memory pressure + no kernel swap → hint to user
                    Log.w(TAG, "high mem usage ${usedPct}% but no kernel swap")
                }
            } catch (_: InterruptedException) { break }
            catch (_: Exception) { }
        }
    }

    fun isActive(): Boolean = currentSize > 0 || (readMemInfo()["SwapTotal"] ?: 0L) > 0
}
