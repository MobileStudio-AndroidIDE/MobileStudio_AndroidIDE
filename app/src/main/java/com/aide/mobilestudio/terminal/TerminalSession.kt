package com.aide.mobilestudio.terminal

import android.content.Context

/**
 * Terminal Session - terminal session management.
 * Simplified implementation for compilation.
 */
class TerminalSession(
    private val context: Context,
    val sessionId: String
) {
    private var active = true

    fun isActive(): Boolean = active

    fun close() {
        active = false
    }

    fun sendCommand(command: String) {
        // Simplified - would send command to PTY
    }
}