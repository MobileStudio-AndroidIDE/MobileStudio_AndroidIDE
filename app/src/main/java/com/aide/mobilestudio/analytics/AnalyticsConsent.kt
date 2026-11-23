package com.aide.mobilestudio.analytics

import android.content.Context
import android.content.SharedPreferences
import com.aide.mobilestudio.PREFS_NAME

/**
 * Consent state for the four MobileStudio analytics categories.
 *
 * - BASIC    : DAU / WAU / MAU, new vs returning users.
 * - USAGE    : daily usage duration, average session duration, launch count.
 * - FEATURE  : project creation, editor feature usage.
 * - BUILD    : build count, duration, success / failure rate.
 *
 * All categories default to OFF. Consent is persisted locally and is never
 * treated as a secret - it is user-controlled app state.
 */
class AnalyticsConsent(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val listeners = mutableListOf<(Map<AnalyticsCategory, Boolean>) -> Unit>()

    fun isEnabled(category: AnalyticsCategory): Boolean =
        prefs.getBoolean(keyFor(category), false)

    /** Returns true when the stored value changed. */
    fun setEnabled(category: AnalyticsCategory, enabled: Boolean): Boolean {
        val changed = prefs.getBoolean(keyFor(category), false) != enabled
        prefs.edit().putBoolean(keyFor(category), enabled).apply()
        if (changed) notifyListeners()
        return changed
    }

    fun setAll(values: Map<AnalyticsCategory, Boolean>) {
        values.forEach { (category, enabled) -> prefs.edit().putBoolean(keyFor(category), enabled).apply() }
        notifyListeners()
    }

    fun snapshot(): Map<AnalyticsCategory, Boolean> = AnalyticsCategory.values().associate {
        it to prefs.getBoolean(keyFor(it), false)
    }

    /** True once the user has accepted (or declined) the consent screen. */
    fun hasAccepted(): Boolean = prefs.getBoolean(KEY_ACCEPTED, false)

    fun markAccepted() {
        prefs.edit().putBoolean(KEY_ACCEPTED, true).apply()
    }

    /** True while the first-run consent screen should be shown. */
    fun shouldShowConsent(): Boolean = !hasAccepted()

    fun subscribe(listener: (Map<AnalyticsCategory, Boolean>) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        // Copy before iterating so a listener cannot mutate the live list.
        listeners.toList().forEach { it(snapshot()) }
    }

    private fun keyFor(category: AnalyticsCategory): String = "analytics_consent_${category.name}"

    private companion object {
        const val KEY_ACCEPTED = "analytics_consent_accepted"
    }
}
