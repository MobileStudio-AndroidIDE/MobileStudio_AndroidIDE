package com.aide.mobilestudio.ai.ui

import android.view.ViewTreeObserver
import android.widget.LinearLayout

/**
 * Shared geometry for chat message bubbles. The full-screen workspace
 * ([AiWorkspaceActivity]) uses these rules: the user bubble is a
 * WRAP_CONTENT child of a MATCH_PARENT
 * horizontal row aligned to the end, and its width is capped at
 * [USER_BUBBLE_MAX_WIDTH_FRACTION] of the row so short messages stay narrow
 * while long ones wrap instead of spanning the full width.
 *
 * The width arithmetic lives in this Android-free object so it can be unit
 * tested on the JVM (the app has no Robolectric/instrumented test setup).
 */
object AiBubbleLayout {

    /** A user bubble never takes more than this fraction of the row width. */
    const val USER_BUBBLE_MAX_WIDTH_FRACTION = 0.85f

    /**
     * Width in px to give a user bubble whose natural (WRAP_CONTENT) width
     * measured [contentWidthPx] inside a row of width [rowWidthPx].
     *
     * Returns 0 while either measurement is unavailable (before layout);
     * callers should keep WRAP_CONTENT in that case and retry once the view
     * has been measured (see [capUserBubbleWidthOnceLaidOut]).
     */
    fun clampedUserBubbleWidth(contentWidthPx: Int, rowWidthPx: Int): Int {
        if (rowWidthPx <= 0 || contentWidthPx <= 0) return 0
        val maxWidth = (rowWidthPx * USER_BUBBLE_MAX_WIDTH_FRACTION).toInt()
        return contentWidthPx.coerceAtMost(maxWidth)
    }
}

/**
 * Apply [AiBubbleLayout.clampedUserBubbleWidth] to [bubble] inside [row] as
 * soon as both have been measured. The bubble is built WRAP_CONTENT; if its
 * natural width exceeds the 85% cap it gets a fixed width and the content
 * re-wraps on the next pass.
 *
 * Uses a global-layout listener (not View.post) because messages are often
 * added before the screen is attached (session restore): posts made while
 * detached run before the first traversal, when every width is still 0,
 * while the listener fires again on the pass that actually measures the row.
 */
internal fun capUserBubbleWidthOnceLaidOut(row: LinearLayout, bubble: LinearLayout) {
    bubble.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
        override fun onGlobalLayout() {
            if (row.width <= 0) return // row not measured yet - wait for the next pass
            bubble.viewTreeObserver.removeOnGlobalLayoutListener(this)
            val clamped = AiBubbleLayout.clampedUserBubbleWidth(bubble.width, row.width)
            if (clamped in 1 until bubble.width) {
                bubble.layoutParams = (bubble.layoutParams as? LinearLayout.LayoutParams)?.apply { width = clamped }
            }
        }
    })
}
