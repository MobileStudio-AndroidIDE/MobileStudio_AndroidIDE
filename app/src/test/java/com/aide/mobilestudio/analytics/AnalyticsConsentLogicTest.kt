package com.aide.mobilestudio.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic tests for consent behaviour that do not require Android Context. */
class AnalyticsConsentLogicTest {

    @Test
    fun allCategoriesDefaultOff() {
        val consent = AnalyticsCategory.values().associate { it to false }
        AnalyticsCategory.values().forEach { category ->
            assertFalse(consent[category] ?: true, "Category ${category.name} should default to OFF")
        }
    }

    @Test
    fun categoryLabelsAreUnique() {
        val labels = AnalyticsCategory.values().map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test
    fun categoryOfMapsCorrectly() {
        assertEquals(AnalyticsCategory.BASIC, AnalyticsEvents.categoryOf(AnalyticsEvents.USER_FIRST_OPENED))
        assertEquals(AnalyticsCategory.BASIC, AnalyticsEvents.categoryOf(AnalyticsEvents.USER_RETURNED))
        assertEquals(AnalyticsCategory.USAGE, AnalyticsEvents.categoryOf(AnalyticsEvents.APP_SESSION_STARTED))
        assertEquals(AnalyticsCategory.USAGE, AnalyticsEvents.categoryOf(AnalyticsEvents.APP_SESSION_ENDED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.PROJECT_CREATED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.XML_EDITOR_OPENED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.KOTLIN_EDITOR_OPENED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.JAVA_EDITOR_OPENED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.CPP_EDITOR_OPENED))
        assertEquals(AnalyticsCategory.BUILD, AnalyticsEvents.categoryOf(AnalyticsEvents.BUILD_STARTED))
        assertEquals(AnalyticsCategory.BUILD, AnalyticsEvents.categoryOf(AnalyticsEvents.BUILD_COMPLETED))
        assertNull(AnalyticsEvents.categoryOf("unknown_event"))
    }
}
