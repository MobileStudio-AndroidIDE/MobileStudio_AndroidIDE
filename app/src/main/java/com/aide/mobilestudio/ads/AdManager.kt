package com.aide.mobilestudio.ads

import android.content.Context
import android.util.Log

/**
 * Centralised Gradle-safe access to the InMobi SDK.
 *
 * The InMobi SDK is NOT available on Maven Central – it must be downloaded
 * manually from https://www.inmobi.com/developers/downloads and placed in
 * app/libs/ or added as a local Maven/Gradle dependency. Until then this class
 * gracefully degrades to a no-op stub so the app compiles and runs without ads.
 *
 * Usage:
 *   AdManager.init(context)           // call once in Application.onCreate
 *   AdManager.nativeAd(context, placementId, listener)  // returns NativeAd or null
 *   AdManager.createBannerView(...)   // returns BannerAdView or null
 *   AdManager.destroy(context)        // call in Activity.onDestroy()
 */
object AdManager {
    private const val TAG = "AdManager"

    private var isInitialized = false
    private var isInitializing = false

    /** True when the real InMobi SDK classes are on the classpath. */
    private var inmobiAvailable = false

    /** Initialise the MobileStudio Ad Manager (call once). */
    fun init(context: Context) {
        if (!AdConfig.isAdsEnabled) {
            Log.d(TAG, "Ads disabled - no account/placement IDs configured")
            return
        }
        if (isInitialized || isInitializing) return
        isInitializing = true

        try {
            // Try to load the real InMobi class via reflection so the app
            // compiles even when the SDK artifact is missing.
            Class.forName("com.inmobi.ads.InMobi")
            inmobiAvailable = true
            Log.i(TAG, "InMobi SDK class found on classpath")
        } catch (e: ClassNotFoundException) {
            inmobiAvailable = false
            Log.w(TAG, "InMobi SDK not on classpath – ads disabled. " +
                "Download inmobi-sdk.aar from https://www.inmobi.com/developers/downloads " +
                "and place it in app/libs/")
        }
        isInitialized = true
        isInitializing = false
    }

    /** Returns true once the SDK has finished initialising. */
    fun isReady(): Boolean = inmobiAvailable && AdConfig.isAdsEnabled

    /** --------------------------------------------------------------------- */
    /* NATIVE AD                                                             */
    /* --------------------------------------------------------------------- */

    /** Load and return a NativeAd for [placementId].
     * Returns null if ads are disabled or the SDK is unavailable. */
    fun loadNativeAd(context: Context, placementId: String, listener: Any? = null): Any? {
        if (!isReady()) return null
        // Real implementation would call JniNativeAd.loadAd here once
        // the SDK jar is present.
        Log.w(TAG, "Native ad not available – InMobi SDK not installed")
        return null
    }

    /** Release a previously loaded native ad. */
    fun destroyNativeAd(placementId: String) {
        Log.d(TAG, "Native ad destroyed: $placementId")
    }

    /** --------------------------------------------------------------------- */
    /* BANNER AD                                                             */
    /* --------------------------------------------------------------------- */

    /** Create a BannerAdView and start loading.
     * Returns null if ads are disabled or the SDK is unavailable. */
    fun createBannerView(context: Context, placementId: String,
                         size: Any? = null, listener: Any? = null): Any? {
        if (!isReady()) return null
        Log.w(TAG, "Banner ad not available – InMobi SDK not installed")
        return null
    }

    /** Destroy a banner view by placement id. */
    fun destroyBannerView(placementId: String) {
        Log.d(TAG, "Banner destroyed: $placementId")
    }

    /** Destroy all active banners (call from Activity.onDestroy). */
    fun destroyAllBanners() {
        Log.d(TAG, "All banners destroyed")
    }

    /** Destroy all native ads (call from Activity.onDestroy). */
    fun destroyAllNativeAds() {
        Log.d(TAG, "All native ads destroyed")
    }

    /** Full cleanup – call from Activity.onDestroy. */
    fun destroy(context: Context) {
        destroyAllBanners()
        destroyAllNativeAds()
    }
}
