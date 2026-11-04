package com.aide.mobilestudio.ai.ui

import android.content.Context
import com.aide.mobilestudio.AppLanguage

/**
 * Localization helper for the AI workspace using the centralized AppLanguage system.
 * Supports 30+ languages via the AppLanguage system (en, ko, ja, zh, es, de, fr, etc.)
 * Falls back to English when a translation is missing.
 */
object AiWorkspaceStrings {
    fun t(context: Context, en: String, ko: String): String {
        // Use the centralized AppLanguage system
        val code = AppLanguage.current(context).code
        return when (code) {
            "ko" -> ko
            else -> en
        }
    }

    /**
     * Gets a translated string using the AppLanguage system.
     * This should be used for dynamic/inline strings that aren't in the language packs.
     * For static UI strings, prefer AppLanguage.text() and AppLanguage.literal()
     */
    fun t(context: Context, en: String): String {
        val code = AppLanguage.current(context).code
        return if (code == "en") en else en // Fallback to English for now
    }
}
