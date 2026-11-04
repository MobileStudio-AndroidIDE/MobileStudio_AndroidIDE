package com.aide.mobilestudio.ai.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the user-bubble width rule shared by the bottom AI panel
 * and the AI workspace. Full view-layout behaviour (right alignment, wrapping)
 * needs an Android runtime and is covered by manual/instrumented checks.
 */
class AiBubbleLayoutTest {

    @Test
    fun capsLongBubbleAt85PercentOfRow() {
        assertEquals(850, AiBubbleLayout.clampedUserBubbleWidth(contentWidthPx = 1000, rowWidthPx = 1000))
    }

    @Test
    fun keepsNarrowBubbleAtItsNaturalWidth() {
        assertEquals(300, AiBubbleLayout.clampedUserBubbleWidth(contentWidthPx = 300, rowWidthPx = 1000))
    }

    @Test
    fun neverExceedsTheCapForAnyRowWidth() {
        for (rowWidth in intArrayOf(320, 480, 720, 1080, 1440)) {
            val clamped = AiBubbleLayout.clampedUserBubbleWidth(contentWidthPx = rowWidth * 10, rowWidthPx = rowWidth)
            assertTrue("row=$rowWidth", clamped <= rowWidth * AiBubbleLayout.USER_BUBBLE_MAX_WIDTH_FRACTION)
            assertTrue("row=$rowWidth", clamped > 0)
        }
    }

    @Test
    fun returnsZeroWhileMeasurementsAreUnavailable() {
        assertEquals(0, AiBubbleLayout.clampedUserBubbleWidth(contentWidthPx = 500, rowWidthPx = 0))
        assertEquals(0, AiBubbleLayout.clampedUserBubbleWidth(contentWidthPx = 0, rowWidthPx = 1000))
        assertEquals(0, AiBubbleLayout.clampedUserBubbleWidth(contentWidthPx = -1, rowWidthPx = 1000))
    }

    @Test
    fun fractionMatchesTheDocumentedSpeechBubbleCap() {
        assertEquals(0.85f, AiBubbleLayout.USER_BUBBLE_MAX_WIDTH_FRACTION)
    }
}
