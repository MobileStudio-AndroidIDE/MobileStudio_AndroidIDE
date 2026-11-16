package com.aide.mobilestudio.debugger

import android.content.Context

/**
 * Debug Console - REPL console for debugger commands.
 */
class DebugConsole(private val context: Context) {
    private val history = mutableListOf<String>()

    /**
     * Executes a debug command.
     */
    fun execute(command: String): String {
        history.add(command)
        return processCommand(command)
    }

    private fun processCommand(command: String): String {
        return when (command.trim()) {
            "help" -> "Available commands: help, step, next, continue, print <var>, watch <expr>"
            "step" -> "Stepped to next line"
            "next" -> "Stepped to next line"
            "continue" -> "Resumed execution"
            else -> if (command.startsWith("print ")) {
                val varName = command.substring(6).trim()
                "Variable $varName: <value>"
            } else {
                "Unknown command: $command"
            }
        }
    }

    /**
     * Returns command history.
     */
    fun getHistory(): List<String> = history
}
