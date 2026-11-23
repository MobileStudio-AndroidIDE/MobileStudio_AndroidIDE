package com.aide.mobilestudio

import android.app.Application
import android.content.res.Configuration
import com.aide.mobilestudio.ai.LlamaRuntimeManager
import com.aide.mobilestudio.analytics.AnalyticsManager

class MobileStudioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MobileStudioCrashGuard.install(this)
        // Local llama runtime: register the app context at startup. The old
        // bottom-panel AI used to do this from its constructor; that panel is
        // gone (the workspace is the single AI surface now), so the Application
        // is the right home and every entry point - workspace, inline
        // completion - gets context-free access to the runtime manager.
        LlamaRuntimeManager.initAppContext(this)
        // PostHog analytics: configure the official Android SDK client with the
        // centralized project host / project API key and consent-aware capture.
        AnalyticsManager.init(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}
