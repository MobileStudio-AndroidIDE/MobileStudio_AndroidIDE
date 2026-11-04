package com.aide.mobilestudio.terminal

import android.util.Log

/**
 * JNI wrapper for native PTY (pseudo-terminal) operations.
 *
 * Each instance owns its own native session (opaque handle), so several
 * terminal tabs can run independent shells.  Every public method is
 * synchronized on the instance: the native layer frees the session inside
 * close(), and the monitor guarantees that no other native call is still
 * in flight when that happens.
 */
class NativePty {

    companion object {
        private const val TAG = "NativePty"

        private var libraryLoaded = false

        init {
            try {
                System.loadLibrary("terminal_pty")
                libraryLoaded = true
                Log.i(TAG, "terminal_pty loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load terminal_pty: ${e.message}")
            }
        }
    }

    private external fun nativeStartShell(
        shellPath: String,
        cwd: String,
        env: Array<String>
    ): Long

    private external fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, len: Int): Int
    private external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, len: Int): Int
    private external fun nativeResize(handle: Long, cols: Int, rows: Int): Boolean
    private external fun nativeIsAlive(handle: Long): Boolean
    private external fun nativeGetExitCode(handle: Long): Int
    private external fun nativeGetExitSignal(handle: Long): Int
    private external fun nativeClose(handle: Long)
    private external fun nativeSendSignal(handle: Long, signal: Int): Boolean

    /** Opaque pointer to the native PtySession. 0 when not started/closed. */
    private var handle: Long = 0L

    private var readBuffer = ByteArray(16384)

    /** True when a native session is currently open. */
    @Synchronized
    fun isStarted(): Boolean = handle != 0L

    /**
     * Start a shell process with PTY.
     * @return true if shell started successfully
     */
    @Synchronized
    fun start(shellPath: String, cwd: String, envMap: Map<String, String>): Boolean {
        if (handle != 0L) {
            closeLocked()
        }

        if (!libraryLoaded) {
            Log.e(TAG, "terminal_pty library not loaded")
            return false
        }

        val envArray = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        handle = nativeStartShell(shellPath, cwd, envArray)

        if (handle == 0L) {
            Log.e(TAG, "Failed to start shell: $shellPath in $cwd")
            return false
        }

        Log.i(TAG, "Shell started: shell=$shellPath, cwd=$cwd")
        return true
    }

    /**
     * Read available output from the PTY.
     *
     * The native side polls briefly, so this returns quickly either way.
     * @return the output string, or null when no data / EOF / closed
     */
    @Synchronized
    fun read(): String? {
        val h = handle
        if (h == 0L) return null
        val n = nativeRead(h, readBuffer, 0, readBuffer.size)
        if (n <= 0) return null
        return String(readBuffer, 0, n, Charsets.UTF_8)
    }

    /**
     * Write input to the PTY.
     * @return number of bytes written, or -1 on error
     */
    @Synchronized
    fun write(text: String): Int {
        val h = handle
        if (h == 0L) return -1
        val bytes = text.toByteArray(Charsets.UTF_8)
        return nativeWrite(h, bytes, 0, bytes.size)
    }

    /**
     * Write a byte range to the PTY.
     *
     * Unlike [write], a partial write is reported precisely so the caller can
     * resume at the right offset instead of dropping the remainder (which
     * would corrupt multi-byte input).
     *
     * @return number of bytes written (>0 when progress was made), -1 on error
     */
    @Synchronized
    fun writeBytes(bytes: ByteArray, offset: Int, len: Int): Int {
        val h = handle
        if (h == 0L) return -1
        if (len <= 0) return 0
        return nativeWrite(h, bytes, offset, len)
    }

    /**
     * Resize the PTY (call when terminal view size changes).
     */
    @Synchronized
    fun resize(cols: Int, rows: Int): Boolean {
        val h = handle
        if (h == 0L) return false
        return nativeResize(h, cols, rows)
    }

    /**
     * Check if the shell process is still alive.
     */
    @Synchronized
    fun isAlive(): Boolean {
        val h = handle
        if (h == 0L) return false
        return nativeIsAlive(h)
    }

    /**
     * Get the shell exit code after it exited.
     * @return >= 0 real exit status (128+signal when killed by a signal),
     *         -1 when still running / unknown
     */
    @Synchronized
    fun getExitCode(): Int {
        val h = handle
        if (h == 0L) return -1
        return nativeGetExitCode(h)
    }

    /**
     * Signal that killed the shell, 0 when it exited normally.
     */
    @Synchronized
    fun getExitSignal(): Int {
        val h = handle
        if (h == 0L) return 0
        return nativeGetExitSignal(h)
    }

    /**
     * Close the PTY and kill the shell process.
     */
    @Synchronized
    fun close() {
        closeLocked()
    }

    private fun closeLocked() {
        val h = handle
        if (h == 0L) return
        handle = 0L
        try {
            nativeClose(h)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeClose failed", e)
        }
    }

    /**
     * Send a signal to the shell (e.g., SIGINT=2, SIGTERM=15, SIGHUP=1).
     */
    @Synchronized
    fun sendSignal(signal: Int): Boolean {
        val h = handle
        if (h == 0L) return false
        return nativeSendSignal(h, signal)
    }

    /** Convenience: send SIGINT (Ctrl+C) */
    fun sendInterrupt(): Boolean = sendSignal(2)

    /** Convenience: send SIGQUIT (Ctrl+\) */
    fun sendQuit(): Boolean = sendSignal(3)

    /** Convenience: send SIGTSTP (Ctrl+Z) */
    fun sendSuspend(): Boolean = sendSignal(20)
}
