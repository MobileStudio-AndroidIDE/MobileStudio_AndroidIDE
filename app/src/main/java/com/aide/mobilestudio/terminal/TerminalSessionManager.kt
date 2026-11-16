package com.aide.mobilestudio.terminal

import android.content.Context
import android.content.SharedPreferences

/**
 * Terminal Session Manager - manages terminal sessions.
 * Simplified implementation for compilation.
 */
class TerminalSessionManager(
    private val context: Context
) {
    private val sessions = mutableListOf<TerminalSession>()
    private val prefs: SharedPreferences = context.getSharedPreferences("terminal_sessions", Context.MODE_PRIVATE)

    fun createSession(sessionId: String): TerminalSession {
        val session = TerminalSession(context, sessionId)
        sessions.add(session)
        return session
    }

    fun getSessions(): List<TerminalSession> = sessions.toList()

    fun closeSession(sessionId: String) {
        sessions.removeIf { it.sessionId == sessionId }
    }

    fun closeAll() {
        sessions.clear()
    }
}