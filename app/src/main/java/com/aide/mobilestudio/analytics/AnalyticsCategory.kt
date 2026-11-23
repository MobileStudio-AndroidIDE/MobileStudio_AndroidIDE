package com.aide.mobilestudio.analytics

/**
 * The four consent categories tracked by MobileStudio Analytics.
 *
 * - BASIC    : DAU / WAU / MAU, new vs returning users. (first_opened / returned)
 * - USAGE    : daily usage duration, average session duration, launch count. (sessions)
 * - FEATURE  : project creation, editor feature usage. (project / editor events)
 * - BUILD    : build count, duration, success / failure rate. (build events)
 */
enum class AnalyticsCategory {
    BASIC,
    USAGE,
    FEATURE,
    BUILD;

    /** Short, user-facing English title of the category. */
    val label: String
        get() = when (this) {
            BASIC -> "Basic Analytics"
            USAGE -> "Usage Analytics"
            FEATURE -> "Feature Analytics"
            BUILD -> "Build Analytics"
        }

    /** Short user-facing English description of what the category collects. */
    val description: String
        get() = when (this) {
            BASIC -> "Anonymous app usage statistics such as daily, weekly and monthly active users, and new vs returning users."
            USAGE -> "How long you use the app and how often you launch it: daily usage time, average session duration, and launch count."
            FEATURE -> "Which features you use: project creation and XML / Kotlin / Java / C++ editor usage."
            BUILD -> "Build activity: build count, average build duration, and build success / failure rates."
        }
}
