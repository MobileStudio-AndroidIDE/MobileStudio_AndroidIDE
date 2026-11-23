package com.aide.mobilestudio.analytics

/**
 * Central, single-source-of-truth PostHog project configuration for MobileStudio.
 *
 * - Server (ingestion) host of the actual PostHog Cloud project that was provided
 *   for MobileStudio. Events are sent to `<host>/batch`.
 * - PostHog Project API Key (`phc_...`). This is a *public client key*: it identifies
 *   the project and is safe to embed in an Android client build. It is NOT a personal
 *   admin key and must never be treated as a secret credential.
 *
 * The values below must match the real MobileStudio PostHog project:
 *   POSTHOG_HOST=https://us.i.posthog.com
 *   POSTHOG_API_KEY=phc_oZR4vEy7BhXdRgDsyzQaLm9E3HzGvZSs2wqEZznqsQgC
 */
object AnalyticsConfig {
    const val POSTHOG_HOST: String = "https://us.i.posthog.com"

    const val POSTHOG_API_KEY: String = "phc_oZR4vEy7BhXdRgDsyzQaLm9E3HzGvZSs2wqEZznqsQgC"

    // SDK queue behaviour. Events captured while offline are persisted locally and
    // flushed once connectivity returns (see PostHog Android offline behavior).
    const val FLUSH_AT: Int = 20
    const val FLUSH_INTERVAL_SECONDS: Int = 30
    const val MAX_QUEUE_SIZE: Int = 1000
    const val MAX_BATCH_SIZE: Int = 50
}
