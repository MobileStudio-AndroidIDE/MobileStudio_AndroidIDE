package com.aide.mobilestudio.terminal

/**
 * Tracks the state of a running process in the terminal.
 * Separates process status from stdout/stderr output.
 */
class ProcessStatusTracker {

    data class ProcessInfo(
        val command: String,
        val pid: Int = 0,
        val startTime: Long = System.currentTimeMillis(),
        var exitCode: Int? = null,
        var state: State = State.RUNNING
    ) {
        enum class State { RUNNING, SUCCESS, FAILED }

        fun elapsedMs(): Long = System.currentTimeMillis() - startTime

        fun formatElapsed(): String {
            val ms = elapsedMs()
            val sec = ms / 1000.0
            return if (sec < 60) "%.1fs".format(sec)
            else "%dm %ds".format(ms / 60000, (ms % 60000) / 1000)
        }

        fun statusIcon(): String = when (state) {
            State.RUNNING -> "\u25CF" // ●
            State.SUCCESS -> "\u2713" // ✓
            State.FAILED  -> "\u2717" // ✕
        }

        fun statusLabel(): String = when (state) {
            State.RUNNING -> "Running"
            State.SUCCESS -> "Success"
            State.FAILED  -> "Failed"
        }

        fun formatStatusLine(): String {
            val icon = statusIcon()
            val label = statusLabel()
            val cmd = command.split(" ").firstOrNull()?.take(20) ?: "process"
            val elapsed = formatElapsed()
            val pidPart = if (pid > 0 && state == State.RUNNING) "  PID $pid" else ""
            val exitPart = exitCode?.let { "  exit=$it" } ?: ""
            return "$icon $label  $cmd$pidPart  $elapsed$exitPart"
        }
    }

    var currentProcess: ProcessInfo? = null
        private set

    var onStatusChange: ((ProcessInfo?) -> Unit)? = null

    fun startProcess(command: String, pid: Int = 0) {
        currentProcess = ProcessInfo(command = command, pid = pid)
        onStatusChange?.invoke(currentProcess)
    }

    fun endProcess(exitCode: Int) {
        val proc = currentProcess ?: return
        proc.exitCode = exitCode
        proc.state = if (exitCode == 0) ProcessInfo.State.SUCCESS else ProcessInfo.State.FAILED
        onStatusChange?.invoke(proc)
    }

    fun cancelProcess() {
        currentProcess = null
        onStatusChange?.invoke(null)
    }

    fun isRunning(): Boolean = currentProcess?.state == ProcessInfo.State.RUNNING

    fun clearStatus() {
        currentProcess = null
        onStatusChange?.invoke(null)
    }
}
