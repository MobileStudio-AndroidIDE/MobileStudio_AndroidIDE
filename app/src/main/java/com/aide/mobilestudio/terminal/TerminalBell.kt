package com.aide.mobilestudio.terminal

import android.content.Context
import android.media.AudioManager

/**
 * Terminal Bell - terminal bell sound.
 * Simplified implementation for compilation.
 */
class TerminalBell(
    private val context: Context
) {
    private var enabled = true

    fun play() {
        if (!enabled) return
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    fun isEnabled(): Boolean = enabled
}