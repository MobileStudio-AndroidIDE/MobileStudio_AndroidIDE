package com.aide.mobilestudio

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView

/**
 * TextView with follow-mode log output:
 * - Follow mode (default ON): new lines auto-scroll to bottom.
 * - When the user scrolls up away from the bottom, follow mode turns itself off.
 * - Scroll to top / bottom / re-enable follow programmatically.
 */
class AutoScrollLogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : TextView(context, attrs, defStyle) {

    var followMode: Boolean = true
        private set

    var onFollowChanged: ((Boolean) -> Unit)? = null

    private var programmaticScroll = false

    fun maxScrollY(): Int {
        val layout = layout ?: return 0
        return (layout.height - (height - totalPaddingTop - totalPaddingBottom)).coerceAtLeast(0)
    }

    fun scrollToTopNow() {
        programmaticScroll = true
        scrollTo(0, 0)
        programmaticScroll = false
    }

    fun scrollToBottomNow() {
        programmaticScroll = true
        scrollTo(0, maxScrollY())
        programmaticScroll = false
    }

    fun setFollowMode(enabled: Boolean) {
        if (followMode == enabled) return
        followMode = enabled
        onFollowChanged?.invoke(enabled)
        if (enabled) scrollToBottomNow()
    }

    /** Append text and keep following the newest line if follow mode is on. */
    fun appendFollow(text: CharSequence) {
        append(text)
        if (followMode) post { scrollToBottomNow() }
    }

    /** Append text and forcibly follow (used after clearing / trimming). */
    fun appendFollowForce(text: CharSequence) {
        append(text)
        post { scrollToBottomNow() }
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (programmaticScroll) return
        val max = maxScrollY()
        val atBottom = t >= max - 10
        if (followMode && !atBottom) {
            followMode = false
            onFollowChanged?.invoke(false)
        } else if (!followMode && atBottom) {
            followMode = true
            onFollowChanged?.invoke(true)
        }
    }
}