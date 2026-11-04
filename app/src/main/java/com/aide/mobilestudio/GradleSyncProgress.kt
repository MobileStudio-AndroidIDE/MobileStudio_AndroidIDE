package com.aide.mobilestudio

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * GradleSyncProgress - Tracks Gradle Sync progress with detailed steps
 * Similar to Android Studio's sync progress indicator
 */
class GradleSyncProgress {

    enum class SyncPhase(val displayName: String) {
        INITIALIZING("Initializing Gradle"),
        LOADING_SETTINGS("Loading settings.gradle"),
        CONFIGURING_PROJECT("Configuring project"),
        CONFIGURING_MODULES("Configuring modules"),
        RESOLVING_DEPENDENCIES("Resolving dependencies"),
        LOADING_SDK("Loading Android SDK"),
        LOADING_VARIANTS("Loading variants"),
        LOADING_SOURCE_SETS("Loading source sets"),
        INDEXING_SYMBOLS("Indexing symbols"),
        UPDATING_IDE("Updating IDE"),
        FINISHED("Finished"),
        FAILED("Failed")
    }

    data class SyncStep(
        val phase: SyncPhase,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val isComplete: Boolean = false,
        val isError: Boolean = false
    )

    interface Listener {
        fun onSyncStepStarted(phase: SyncPhase, message: String)
        fun onSyncStepCompleted(phase: SyncPhase, message: String)
        fun onSyncProgress(progress: Float, message: String)
        fun onSyncCompleted(success: Boolean, durationMs: Long, summary: String)
        fun onSyncError(phase: SyncPhase, error: String, suggestion: String)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val steps = mutableListOf<SyncStep>()

    @Volatile var currentPhase: SyncPhase = SyncPhase.INITIALIZING
        private set
    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var startTime: Long = 0
        private set
    @Volatile var progress: Float = 0f
        private set

    private val phaseOrder = SyncPhase.entries.toTypedArray()

    fun addListener(listener: Listener) { listeners.add(listener) }
    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun beginSync() {
        isRunning = true
        startTime = System.currentTimeMillis()
        steps.clear()
        currentPhase = SyncPhase.INITIALIZING
        progress = 0f
        notifyStepStarted(SyncPhase.INITIALIZING, "Initializing Gradle...")
    }

    fun advanceTo(phase: SyncPhase, message: String = phase.displayName) {
        if (!isRunning) return
        val prevPhase = currentPhase
        if (prevPhase != phase) {
            // Complete previous phase
            notifyStepCompleted(prevPhase, "${prevPhase.displayName} completed")
            steps.add(SyncStep(prevPhase, "${prevPhase.displayName} completed", isComplete = true))
        }
        currentPhase = phase
        progress = calculateProgress(phase)
        notifyProgress(progress, message)
        notifyStepStarted(phase, message)
        steps.add(SyncStep(phase, message))
    }

    fun updateMessage(message: String) {
        if (!isRunning) return
        notifyProgress(progress, message)
    }

    fun completeSync(success: Boolean, summary: String = "") {
        isRunning = false
        val durationMs = System.currentTimeMillis() - startTime
        if (success) {
            currentPhase = SyncPhase.FINISHED
            progress = 1.0f
            steps.add(SyncStep(SyncPhase.FINISHED, "Sync completed in ${formatDuration(durationMs)}", isComplete = true))
        } else {
            currentPhase = SyncPhase.FAILED
            steps.add(SyncStep(SyncPhase.FAILED, "Sync failed", isError = true))
        }
        notifyCompleted(success, durationMs, summary)
    }

    fun reportError(phase: SyncPhase, error: String, suggestion: String = "") {
        steps.add(SyncStep(phase, error, isError = true))
        notifyError(phase, error, suggestion)
    }

    fun getSteps(): List<SyncStep> = steps.toList()

    fun getDurationMs(): Long = if (isRunning) System.currentTimeMillis() - startTime else 0

    fun getCurrentStepSummary(): String {
        return buildString {
            appendLine("=== Gradle Sync Status ===")
            appendLine("Phase: ${currentPhase.displayName}")
            appendLine("Progress: ${(progress * 100).toInt()}%")
            appendLine("Duration: ${formatDuration(getDurationMs())}")
            appendLine("Steps completed: ${steps.count { it.isComplete }}/${steps.size}")
            if (steps.isNotEmpty()) {
                appendLine("Last step: ${steps.last().message}")
            }
        }
    }

    private fun calculateProgress(phase: SyncPhase): Float {
        val idx = phaseOrder.indexOf(phase)
        return if (idx >= 0) (idx.toFloat() / (phaseOrder.size - 1).toFloat()).coerceIn(0f, 1f) else 0f
    }

    private fun notifyStepStarted(phase: SyncPhase, message: String) {
        listeners.forEach { l ->
            mainHandler.post { l.onSyncStepStarted(phase, message) }
        }
    }

    private fun notifyStepCompleted(phase: SyncPhase, message: String) {
        listeners.forEach { l ->
            mainHandler.post { l.onSyncStepCompleted(phase, message) }
        }
    }

    private fun notifyProgress(progress: Float, message: String) {
        listeners.forEach { l ->
            mainHandler.post { l.onSyncProgress(progress, message) }
        }
    }

    private fun notifyCompleted(success: Boolean, durationMs: Long, summary: String) {
        listeners.forEach { l ->
            mainHandler.post { l.onSyncCompleted(success, durationMs, summary) }
        }
    }

    private fun notifyError(phase: SyncPhase, error: String, suggestion: String) {
        listeners.forEach { l ->
            mainHandler.post { l.onSyncError(phase, error, suggestion) }
        }
    }

    private fun formatDuration(ms: Long): String {
        return when {
            ms < 1000 -> "${ms}ms"
            ms < 60000 -> String.format("%.1fs", ms / 1000.0)
            else -> String.format("%dm %ds", ms / 60000, (ms % 60000) / 1000)
        }
    }
}
