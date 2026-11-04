package com.aide.mobilestudio

import android.os.Handler
import android.os.Looper

/**
 * Multi-gesture handler for volume buttons.
 *
 * Volume Down:
 *   - Single tap   -> Switch to next tab
 *   - Double tap   -> Open Command Palette
 *   - Long press   -> Go-To navigation (existing: highlight function names)
 *   - Release      -> Restore highlights
 *
 * Volume Up:
 *   - Single tap   -> Search Everywhere (existing)
 *   - Double tap   -> Toggle Build panel
 *   - Long press   -> Toggle Shell panel
 */
class VolumeGestureHandler {

    interface Callbacks {
        fun onVolumeDownSingleTap()
        fun onVolumeDownDoubleTap()
        fun onVolumeDownLongPressStart()
        fun onVolumeDownLongPressEnd()
        fun onVolumeDownRelease()

        fun onVolumeUpSingleTap()
        fun onVolumeUpDoubleTap()
        fun onVolumeUpLongPressStart()
        fun onVolumeUpRelease()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val callbacks: Callbacks

    // Timing constants
    companion object {
        const val DOUBLE_TAP_WINDOW = 350L   // max ms between taps for double-tap
        const val LONG_PRESS_THRESHOLD = 800L // ms to consider it a long press
        const val TAP_MAX_DURATION = 300L     // max ms for a press to count as a tap
    }

    // Volume Down state
    private var downDownTime = 0L
    private var downTapCount = 0
    private var downLastTapUpTime = 0L
    private var downIsHeld = false
    private var downLongPressTriggered = false
    private val downLongPressRunnable = Runnable {
        if (downDownTime > 0 && !downLongPressTriggered) {
            downLongPressTriggered = true
            downIsHeld = true
            callbacks.onVolumeDownLongPressStart()
        }
    }
    private val downDoubleTapTimeout = Runnable {
        if (downTapCount == 1) {
            downTapCount = 0
            callbacks.onVolumeDownSingleTap()
        }
    }

    // Volume Up state
    private var upDownTime = 0L
    private var upTapCount = 0
    private var upLastTapUpTime = 0L
    private var upIsHeld = false
    private var upLongPressTriggered = false
    private val upLongPressRunnable = Runnable {
        if (upDownTime > 0 && !upLongPressTriggered) {
            upLongPressTriggered = true
            upIsHeld = true
            callbacks.onVolumeUpLongPressStart()
        }
    }
    private val upDoubleTapTimeout = Runnable {
        if (upTapCount == 1) {
            upTapCount = 0
            callbacks.onVolumeUpSingleTap()
        }
    }

    constructor(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    // ===== Volume Down =====

    fun onVolumeDownKeyDown(): Boolean {
        downDownTime = System.currentTimeMillis()
        downLongPressTriggered = false
        downIsHeld = false
        handler.postDelayed(downLongPressRunnable, LONG_PRESS_THRESHOLD)
        return true
    }

    fun onVolumeDownKeyUp(): Boolean {
        val duration = System.currentTimeMillis() - downDownTime
        handler.removeCallbacks(downLongPressRunnable)

        if (downLongPressTriggered) {
            // Was a long press release
            downLongPressTriggered = false
            downIsHeld = false
            downDownTime = 0L
            callbacks.onVolumeDownLongPressEnd()
            return true
        }

        downDownTime = 0L

        if (duration > TAP_MAX_DURATION) {
            // Held briefly but not long enough for long press - ignore
            return true
        }

        // It was a tap
        val now = System.currentTimeMillis()
        if (now - downLastTapUpTime < DOUBLE_TAP_WINDOW && downTapCount == 1) {
            // Double tap!
            downTapCount = 0
            downLastTapUpTime = 0L
            handler.removeCallbacks(downDoubleTapTimeout)
            callbacks.onVolumeDownDoubleTap()
        } else {
            // First tap - wait for potential second
            downTapCount = 1
            downLastTapUpTime = now
            handler.removeCallbacks(downDoubleTapTimeout)
            handler.postDelayed(downDoubleTapTimeout, DOUBLE_TAP_WINDOW)
        }
        return true
    }

    // ===== Volume Up =====

    fun onVolumeUpKeyDown(): Boolean {
        upDownTime = System.currentTimeMillis()
        upLongPressTriggered = false
        upIsHeld = false
        handler.postDelayed(upLongPressRunnable, LONG_PRESS_THRESHOLD)
        return true
    }

    fun onVolumeUpKeyUp(): Boolean {
        val duration = System.currentTimeMillis() - upDownTime
        handler.removeCallbacks(upLongPressRunnable)

        if (upLongPressTriggered) {
            upLongPressTriggered = false
            upIsHeld = false
            upDownTime = 0L
            callbacks.onVolumeUpRelease()
            return true
        }

        upDownTime = 0L

        if (duration > TAP_MAX_DURATION) {
            return true
        }

        val now = System.currentTimeMillis()
        if (now - upLastTapUpTime < DOUBLE_TAP_WINDOW && upTapCount == 1) {
            upTapCount = 0
            upLastTapUpTime = 0L
            handler.removeCallbacks(upDoubleTapTimeout)
            callbacks.onVolumeUpDoubleTap()
        } else {
            upTapCount = 1
            upLastTapUpTime = now
            handler.removeCallbacks(upDoubleTapTimeout)
            handler.postDelayed(upDoubleTapTimeout, DOUBLE_TAP_WINDOW)
        }
        return true
    }

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }
}
