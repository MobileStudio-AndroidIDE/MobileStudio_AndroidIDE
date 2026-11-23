package com.aide.mobilestudio.analytics

/**
 * Central event catalog for MobileStudio PostHog analytics.
 *
 * Event naming follows the `[object]_[verb]` snake_case convention
 * (e.g. `project_created`, `build_completed`).
 *
 * Privacy contract: events never contain project names, file paths, source code,
 * or edit content. Build events only carry execution duration and success state.
 */
object AnalyticsEvents {
    // ---- Session / usage ----
    const val APP_SESSION_STARTED = "app_session_started"
    const val APP_SESSION_ENDED = "app_session_ended"
    const val USER_FIRST_OPENED = "user_first_opened"
    const val USER_RETURNED = "user_returned"

    // ---- Feature ----
    const val PROJECT_CREATED = "project_created"
    const val XML_EDITOR_OPENED = "xml_editor_opened"
    const val KOTLIN_EDITOR_OPENED = "kotlin_editor_opened"
    const val JAVA_EDITOR_OPENED = "java_editor_opened"
    const val CPP_EDITOR_OPENED = "cpp_editor_opened"

    // ---- Build ----
    const val BUILD_STARTED = "build_started"
    const val BUILD_COMPLETED = "build_completed"

    // ---- Event properties (no PII, no content) ----
    const val PROP_DURATION_MS: String = "duration_ms"
    const val PROP_SUCCESS: String = "success"

    /**
     * Maps a MobileStudio event name to the consent category it belongs to.
     * Returns null for internal/unmapped events that are always allowed through.
     */
    fun categoryOf(eventName: String): AnalyticsCategory? = when (eventName) {
        USER_FIRST_OPENED, USER_RETURNED -> AnalyticsCategory.BASIC
        APP_SESSION_STARTED, APP_SESSION_ENDED -> AnalyticsCategory.USAGE
        PROJECT_CREATED, XML_EDITOR_OPENED, KOTLIN_EDITOR_OPENED, JAVA_EDITOR_OPENED, CPP_EDITOR_OPENED -> AnalyticsCategory.FEATURE
        BUILD_STARTED, BUILD_COMPLETED -> AnalyticsCategory.BUILD
        else -> null
    }
}
