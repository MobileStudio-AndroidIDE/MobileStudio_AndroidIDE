package com.aide.mobilestudio

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.aide.mobilestudio.analysis.AnalysisService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MemoryMonitor - Monitors device RAM and MobileStudio memory usage.
 * Detects memory pressure and triggers cleanup actions.
 */
class MemoryMonitor(private val context: Context) {

    interface MemoryListener {
        fun onMemoryPressure(level: PressureLevel)
        fun onMemorySnapshot(snapshot: MemorySnapshot)
        fun onCleanupRecommended(actions: List<CleanupAction>)
    }

    enum class PressureLevel {
        NORMAL,      // > 30% free
        MODERATE,    // 15-30% free
        LOW,         // 5-15% free
        CRITICAL     // < 5% free
    }

    enum class CleanupAction(val description: String) {
        PAUSE_INDEXING("Pause background indexing"),
        REDUCE_GRADLE_WORKERS("Reduce Gradle worker count"),
        CLEAR_FILE_CACHE("Clear file analysis cache"),
        CLEAR_BUILD_CACHE("Clear build output cache"),
        PAUSE_AI_ANALYSIS("Pause AI analysis"),
        GARBAGE_COLLECT("Force garbage collection"),
        COMPACT_EDITOR("Compact editor undo history")
    }

    data class MemorySnapshot(
        val totalRamMB: Long,
        val availableRamMB: Long,
        val usedRamMB: Long,
        val appHeapUsedMB: Long,
        val appHeapMaxMB: Long,
        val appNativeUsedMB: Long,
        val pressureLevel: PressureLevel,
        val freePercent: Float,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val isMonitoring = AtomicBoolean(false)
    private val listeners = mutableListOf<MemoryListener>()
    private val lastCleanupTime = AtomicLong(0)
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring.get()) return
            val snapshot = collectSnapshot()
            notifySnapshot(snapshot)

            val actions = recommendActions(snapshot)
            if (actions.isNotEmpty() && System.currentTimeMillis() - lastCleanupTime.get() > 30_000) {
                lastCleanupTime.set(System.currentTimeMillis())
                notifyCleanupRecommended(actions)
            }

            val interval = 5_000L
            handler.postDelayed(this, interval)
        }
    }

    fun startMonitoring(intervalMs: Long = 5_000) {
        if (isMonitoring.compareAndSet(false, true)) {
            handler.postDelayed(monitorRunnable, intervalMs)
        }
    }

    fun stopMonitoring() {
        if (isMonitoring.compareAndSet(true, false)) {
            handler.removeCallbacks(monitorRunnable)
        }
    }

    fun addListener(listener: MemoryListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: MemoryListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    fun collectSnapshot(): MemorySnapshot {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalRam = memInfo.totalMem / (1024 * 1024)
        val availableRam = memInfo.availMem / (1024 * 1024)
        val usedRam = totalRam - availableRam
        val freePercent = if (totalRam > 0) (availableRam.toFloat() / totalRam) * 100f else 0f

        val runtime = Runtime.getRuntime()
        val heapMax = runtime.maxMemory() / (1024 * 1024)
        val heapUsed = runtime.totalMemory() / (1024 * 1024)

        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        val pressureLevel = when {
            freePercent < 5f -> PressureLevel.CRITICAL
            freePercent < 15f -> PressureLevel.LOW
            freePercent < 30f -> PressureLevel.MODERATE
            else -> PressureLevel.NORMAL
        }

        return MemorySnapshot(
            totalRamMB = totalRam,
            availableRamMB = availableRam,
            usedRamMB = usedRam,
            appHeapUsedMB = heapUsed,
            appHeapMaxMB = heapMax,
            appNativeUsedMB = nativeHeap,
            pressureLevel = pressureLevel,
            freePercent = freePercent
        )
    }

    fun recommendActions(snapshot: MemorySnapshot): List<CleanupAction> {
        val actions = mutableListOf<CleanupAction>()
        when (snapshot.pressureLevel) {
            PressureLevel.CRITICAL -> {
                actions.addAll(CleanupAction.entries)
            }
            PressureLevel.LOW -> {
                actions.add(CleanupAction.PAUSE_INDEXING)
                actions.add(CleanupAction.CLEAR_FILE_CACHE)
                actions.add(CleanupAction.CLEAR_BUILD_CACHE)
                actions.add(CleanupAction.GARBAGE_COLLECT)
            }
            PressureLevel.MODERATE -> {
                actions.add(CleanupAction.CLEAR_FILE_CACHE)
                actions.add(CleanupAction.GARBAGE_COLLECT)
            }
            PressureLevel.NORMAL -> {}
        }
        if (snapshot.appHeapUsedMB > snapshot.appHeapMaxMB * 0.85) {
            if (!actions.contains(CleanupAction.GARBAGE_COLLECT)) {
                actions.add(CleanupAction.GARBAGE_COLLECT)
            }
            if (!actions.contains(CleanupAction.CLEAR_FILE_CACHE)) {
                actions.add(CleanupAction.CLEAR_FILE_CACHE)
            }
        }
        return actions
    }

    fun executeCleanup(actions: List<CleanupAction>) {
        executor.execute {
            for (action in actions) {
                when (action) {
                    CleanupAction.GARBAGE_COLLECT -> System.gc()
                    CleanupAction.CLEAR_FILE_CACHE -> {
                        AnalysisService.getEngine()?.invalidateCache()
                    }
                    CleanupAction.CLEAR_BUILD_CACHE -> {
                        // Build cache is managed by BuildScheduler
                    }
                    else -> {} // Handled by listeners
                }
            }
        }
    }

    fun getFormattedStatus(): String {
        val snapshot = collectSnapshot()
        return buildString {
            append("RAM: ${snapshot.usedRamMB}/${snapshot.totalRamMB} MB (${snapshot.freePercent.toInt()}% free)")
            append("\nHeap: ${snapshot.appHeapUsedMB}/${snapshot.appHeapMaxMB} MB")
            append("\nNative: ${snapshot.appNativeUsedMB} MB")
            append("\nPressure: ${snapshot.pressureLevel.name}")
        }
    }

    private fun notifySnapshot(snapshot: MemorySnapshot) {
        synchronized(listeners) {
            listeners.forEach { it.onMemorySnapshot(snapshot) }
        }
    }

    private fun notifyCleanupRecommended(actions: List<CleanupAction>) {
        handler.post {
            synchronized(listeners) {
                listeners.forEach { it.onCleanupRecommended(actions) }
            }
        }
    }

    private fun notifyPressure(level: PressureLevel) {
        handler.post {
            synchronized(listeners) {
                listeners.forEach { it.onMemoryPressure(level) }
            }
        }
    }

    fun destroy() {
        stopMonitoring()
        executor.shutdownNow()
        synchronized(listeners) { listeners.clear() }
    }
}
