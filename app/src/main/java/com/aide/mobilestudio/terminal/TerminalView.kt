package com.aide.mobilestudio.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.View

/**
 * Terminal View - individual terminal view.
 * Simplified implementation for compilation.
 */
class TerminalView(
    context: Context,
    val title: String
) : View(context) {
    fun copySelection() {
        // Stub
    }

    fun pasteFromClipboard() {
        // Stub
    }

    fun clear() {
        // Stub
    }

    fun ctrlTapped() {
        // Stub
    }

    fun ctrlLongPressed() {
        // Stub
    }

    fun keyBarEsc() {
        // Stub
    }

    fun keyBarTab() {
        // Stub
    }

    fun keyBarArrow(keyCode: Int) {
        // Stub
    }

    fun keyBarEnter() {
        // Stub
    }

    fun keyBarBackspace() {
        // Stub
    }

    fun isCtrlLatched(): Boolean = false

    fun isCtrlSticky(): Boolean = false
}