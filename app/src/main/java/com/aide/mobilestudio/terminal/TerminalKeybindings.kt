package com.aide.mobilestudio.terminal

import android.content.Context
import android.view.KeyEvent

/**
 * Terminal Keybindings - terminal keybinding handling.
 * Simplified implementation for compilation.
 */
class TerminalKeybindings(
    private val context: Context
) {
    private val bindings = mutableListOf<String>()

    fun addBinding(binding: String) {
        bindings.add(binding)
    }

    fun getBindings(): List<String> = bindings

    fun isCtrlPressed(event: KeyEvent): Boolean {
        return (event.modifiers and KeyEvent.META_CTRL_ON) != 0
    }

    fun isShiftPressed(event: KeyEvent): Boolean {
        return (event.modifiers and KeyEvent.META_SHIFT_ON) != 0
    }
}