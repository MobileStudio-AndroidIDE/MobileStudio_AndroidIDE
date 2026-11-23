package com.aide.mobilestudio.ads

/**
 * Configuration for MobileStudio ad integration.
 *
 * All ad unit IDs live here so they can be changed in one place (e.g. injected
 * via buildConfig or Gradle properties) without touching UI code.
 *
 * NOTE: Replace the placeholder values below with your real InMobi account and
 * placement IDs before publishing. Until real IDs are provided, ads remain
 * disabled and the app behaves exactly as before (no ad UI, no network calls).
 */
object AdConfig {
    // ┌─────────────────────────────────────────────────────────────────────┐
    // │  Replace these with your actual InMobi credentials.               │
    // └─────────────────────────────────────────────────────────────────────┘
    const val ACCOUNT_ID: String = ""

    const val NATIVE_PLACEMENT_ID: String = ""
    const val BANNER_PLACEMENT_ID: String = ""

    /** Whether Ads are enabled. Disabled when account id is empty. */
    val isAdsEnabled: Boolean
        get() = ACCOUNT_ID.isNotBlank() && NATIVE_PLACEMENT_ID.isNotBlank() && BANNER_PLACEMENT_ID.isNotBlank()

    /** Test mode - keep true during development, set false before release. */
    val isTestMode: Boolean = true

    /** Whether the app respects user privacy (GDPR/CCPA). */
    val isGdprConsentRequired: Boolean = false
}
