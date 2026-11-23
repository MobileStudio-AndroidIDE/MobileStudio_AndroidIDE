package com.aide.mobilestudio.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsEventsTest {

    @Test
    fun sessionEventsMapToUsageCategory() {
        assertEquals(AnalyticsCategory.USAGE, AnalyticsEvents.categoryOf(AnalyticsEvents.APP_SESSION_STARTED))
        assertEquals(AnalyticsCategory.USAGE, AnalyticsEvents.categoryOf(AnalyticsEvents.APP_SESSION_ENDED))
    }

    @Test
    fun basicEventsMapToBasicCategory() {
        assertEquals(AnalyticsCategory.BASIC, AnalyticsEvents.categoryOf(AnalyticsEvents.USER_FIRST_OPENED))
        assertEquals(AnalyticsCategory.BASIC, AnalyticsEvents.categoryOf(AnalyticsEvents.USER_RETURNED))
    }

    @Test
    fun featureEventsMapToFeatureCategory() {
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.PROJECT_CREATED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.XML_EDITOR_OPENED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.KOTLIN_EDITOR_OPENED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.JAVA_EDITOR_OPENED))
        assertEquals(AnalyticsCategory.FEATURE, AnalyticsEvents.categoryOf(AnalyticsEvents.CPP_EDITOR_OPENED))
    }

    @Test
    fun buildEventsMapToBuildCategory() {
        assertEquals(AnalyticsCategory.BUILD, AnalyticsEvents.categoryOf(AnalyticsEvents.BUILD_STARTED))
        assertEquals(AnalyticsCategory.BUILD, AnalyticsEvents.categoryOf(AnalyticsEvents.BUILD_COMPLETED))
    }

    @Test
    fun unknownEventsMapToNullCategory() {
        assertNull(AnalyticsEvents.categoryOf("some_future_event"))
    }

    @Test
    fun buildEventPropertiesContainOnlyDurationAndSuccess() {
        val properties = mapOf<String, Any>(
            AnalyticsEvents.PROP_DURATION_MS to 1234L,
            AnalyticsEvents.PROP_SUCCESS to true,
        )
        // Build events must never carry project / file / source content.
        assertEquals(2, properties.size)
        assertFalse(properties.containsKey("project_name"))
        assertFalse(properties.containsKey("file_path"))
        assertFalse(properties.containsKey("source_code"))
        assertFalse(properties.containsKey("content"))
    }

    @Test
    fun categoryLabelsAndDescriptionsAreNonEmpty() {
        AnalyticsCategory.values().forEach { category ->
            assertTrue(category.label.isNotEmpty())
            assertTrue(category.description.isNotEmpty())
        }
    }
}
