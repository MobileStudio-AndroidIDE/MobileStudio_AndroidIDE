package com.aide.mobilestudio.analytics

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig
import java.util.Calendar
import java.util.HashMap
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * MobileStudio AnalyticsManager.
 *
 * Owns the PostHog Android SDK client, the consent gate and the in-app usage
 * session tracking. All analytics events flow through [capture] so that the
 * consent state is enforced before anything is queued or sent to PostHog.
 *
 * Privacy model:
 * - No PII is ever captured. User separation uses a random anonymous UUID.
 * - Project names, file paths, source code and edit content are never sent.
 * - Build events only carry duration + success.
 * - Session Replay / Autocapture / automatic screen tracking are disabled in the SDK config.
 * - SDK failures never affect app startup or build functionality.
 */
object AnalyticsManager {

    private const val TAG = "MobileStudioAnalytics"
    private const val PREFS_NAME = "mobilestudio_settings"
    private const val PENDING_SESSION_KEY = "analytics_pending_session_start_ms"
    private const val LAST_SESSION_DAY_KEY = "analytics_last_session_day"
    private const val FIRST_SEEN_PREFIX = "analytics_first_seen_"

    // Prefs keys for the server-side deletion endpoint (set by the admin, never an admin key).
    private const val PREF_DELETE_URL = "analytics_delete_url"
    private const val PREF_DELETE_TOKEN = "analytics_shared_token"

    private var appContext: Context? = null
    private var consentInstance: AnalyticsConsent? = null
    private var posthogConfig: PostHogAndroidConfig? = null

    private var sessionOpen = false
    private var sessionStartMs: Long = 0L
    @Volatile private var anonymousUserId: String = UUID.randomUUID().toString()
    private val dailyUsageMs = HashMap<String, Long>()

    // ------------------------------------------------------------------
    // Init / PostHog setup
    // ------------------------------------------------------------------
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        consentInstance = AnalyticsConsent(app)
        val config = PostHogAndroidConfig(
            projectToken = AnalyticsConfig.POSTHOG_API_KEY,
            host = AnalyticsConfig.POSTHOG_HOST,
        ).apply {
            // Analytics backend: disable SDK-managed capture that MobileStudio
            // does not use. Sessions / features / builds are tracked explicitly.
            captureApplicationLifecycleEvents = false
            captureDeepLinks = false
            captureScreenViews = false
            sessionReplay = false
            debug = false
            flushAt = AnalyticsConfig.FLUSH_AT
            flushIntervalSeconds = AnalyticsConfig.FLUSH_INTERVAL_SECONDS
            maxQueueSize = AnalyticsConfig.MAX_QUEUE_SIZE
            maxBatchSize = AnalyticsConfig.MAX_BATCH_SIZE
        }
        // Defense in depth: drop any event whose consent category is not granted.
        // This runs before the event reaches the disk queue, so withdrawn categories
        // can never be queued after consent is withdrawn.
        config.addBeforeSend { event ->
            val category = AnalyticsEvents.categoryOf(event.event)
            val allowed = category == null || (consentInstance?.isEnabled(category) ?: false)
            if (allowed) event else null
        }
        PostHogAndroid.setup(app, config)
        posthogConfig = config

        // Anonymous, random UUID based user separation - no personal identifiers.
        val userId = UUID.randomUUID().toString()
        anonymousUserId = userId
        runCatching { PostHog.identify(distinctId = userId) }
    }

    /** Re-initialises PostHog with a fresh anonymous ID and a fresh queue. Used after
     *  consent withdrawal so any events that were already queued under the previous
     *  identity are not delivered to PostHog. */
    private fun reinitialisePostHog() {
        val app = appContext ?: return
        // Stop the current SDK instance so its queue stops flushing.
        runCatching { PostHog.close() }
        // Fresh anonymous id so previously queued events can no longer be linked.
        val userId = UUID.randomUUID().toString()
        anonymousUserId = userId
        val config = PostHogAndroidConfig(
            projectToken = AnalyticsConfig.POSTHOG_API_KEY,
            host = AnalyticsConfig.POSTHOG_HOST,
        ).apply {
            captureApplicationLifecycleEvents = false
            captureDeepLinks = false
            captureScreenViews = false
            sessionReplay = false
            debug = false
            flushAt = AnalyticsConfig.FLUSH_AT
            flushIntervalSeconds = AnalyticsConfig.FLUSH_INTERVAL_SECONDS
            maxQueueSize = AnalyticsConfig.MAX_QUEUE_SIZE
            maxBatchSize = AnalyticsConfig.MAX_BATCH_SIZE
        }
        config.addBeforeSend { event ->
            val category = AnalyticsEvents.categoryOf(event.event)
            val allowed = category == null || (consentInstance?.isEnabled(category) ?: false)
            if (allowed) event else null
        }
        PostHogAndroid.setup(app, config)
        posthogConfig = config
        runCatching { PostHog.identify(distinctId = userId) }
    }

    // ------------------------------------------------------------------
    // Consent
    // ------------------------------------------------------------------
    fun getConsent(): Map<AnalyticsCategory, Boolean> = consentInstance?.snapshot() ?: defaultConsent()

    private fun defaultConsent(): Map<AnalyticsCategory, Boolean> = AnalyticsCategory.values().associate {
        it to false
    }

    /** Grants or revokes a single category. Revoking immediately stops new events
     *  of that category from being queued (via [beforeSend]) and re-initialises
     *  PostHog so previously queued events of the withdrawn category are abandoned. */
    fun setCategoryEnabled(category: AnalyticsCategory, enabled: Boolean): Boolean {
        val changed = consentInstance?.setEnabled(category, enabled) ?: false
        if (enabled) {
            if (changed) runCatching { PostHog.flush() }
        } else {
            // Consent withdrawn: close the in-flight session so no further usage
            // events are produced for the current session.
            if (category == AnalyticsCategory.USAGE) {
                endSessionInternal()
            }
            // Drop any events already queued under the previous identity so they are
            // never flushed to PostHog after consent is withdrawn.
            if (changed) {
                reinitialisePostHog()
            }
        }
        return changed
    }

    fun setAllCategories(values: Map<AnalyticsCategory, Boolean>) {
        consentInstance?.setAll(values)
    }

    // ------------------------------------------------------------------
    // Capturing (consent enforced before the event reaches the SDK queue)
    // ------------------------------------------------------------------
    fun capture(event: String, category: AnalyticsCategory, properties: Map<String, Any> = emptyMap()) {
        val consentState = consentInstance
        if (consentState == null) return
        if (!consentState.isEnabled(category)) return
        val config = posthogConfig ?: return
        if (config.projectToken.isBlank()) return
        runCatching { PostHog.capture(event = event, properties = properties) }
            .onFailure { Log.w(TAG, "capture($event) failed", it) }
    }

    // ------------------------------------------------------------------
    // Feature tracking
    // ------------------------------------------------------------------
    fun trackProjectCreated() {
        capture(AnalyticsEvents.PROJECT_CREATED, AnalyticsCategory.FEATURE, emptyMap())
    }

    fun trackEditorOpened(language: String?) {
        val event = when (language?.trim()?.lowercase()) {
            "xml" -> AnalyticsEvents.XML_EDITOR_OPENED
            "kotlin", "kts" -> AnalyticsEvents.KOTLIN_EDITOR_OPENED
            "java" -> AnalyticsEvents.JAVA_EDITOR_OPENED
            "cpp", "cc" -> AnalyticsEvents.CPP_EDITOR_OPENED
            else -> return
        }
        capture(event, AnalyticsCategory.FEATURE, emptyMap())
    }

    fun trackBuildStarted() {
        capture(AnalyticsEvents.BUILD_STARTED, AnalyticsCategory.BUILD, emptyMap())
    }

    fun trackBuildCompleted(success: Boolean, durationMs: Long) {
        val properties = mapOf<String, Any>(
            AnalyticsEvents.PROP_DURATION_MS to durationMs,
            AnalyticsEvents.PROP_SUCCESS to success,
        )
        capture(AnalyticsEvents.BUILD_COMPLETED, AnalyticsCategory.BUILD, properties)
    }

    // ------------------------------------------------------------------
    // Usage / session tracking (Activity foreground & process lifecycle)
    // ------------------------------------------------------------------
    fun onAppForeground() {
        startSessionInternal()
    }

    fun onAppBackground() {
        if (sessionOpen) endSessionInternal()
    }

    fun endSession() {
        endSessionInternal()
    }

    private fun startSessionInternal() {
        // Abnormal exit recovery: close an unfinished session before opening a new one.
        if (sessionOpen) endSessionInternal()
        sessionOpen = true
        sessionStartMs = System.currentTimeMillis()
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(PENDING_SESSION_KEY, sessionStartMs).apply()

        val today = dayKey(System.currentTimeMillis())
        val firstSeenKey = FIRST_SEEN_PREFIX + anonymousUserId
        val firstSeen = prefs.getString(firstSeenKey, null)
        if (firstSeen == null) {
            prefs.edit().putString(firstSeenKey, today).apply()
            capture(AnalyticsEvents.USER_FIRST_OPENED, AnalyticsCategory.BASIC, emptyMap())
        } else {
            val lastDay = prefs.getString(LAST_SESSION_DAY_KEY, null)
            if (lastDay != today) {
                prefs.edit().putString(LAST_SESSION_DAY_KEY, today).apply()
                capture(AnalyticsEvents.USER_RETURNED, AnalyticsCategory.BASIC, emptyMap())
            }
        }
        capture(AnalyticsEvents.APP_SESSION_STARTED, AnalyticsCategory.USAGE, emptyMap())
    }

    private fun endSessionInternal() {
        if (!sessionOpen) return
        sessionOpen = false
        val endMs = System.currentTimeMillis()
        val durationMs = (endMs - sessionStartMs).coerceAtLeast(0L)
        val context = appContext ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PENDING_SESSION_KEY)
            .apply()
        if (durationMs > 0L) {
            addDailyUsage(sessionStartMs, endMs)
            capture(
                AnalyticsEvents.APP_SESSION_ENDED,
                AnalyticsCategory.USAGE,
                mapOf<String, Any>(AnalyticsEvents.PROP_DURATION_MS to durationMs),
            )
        }
    }

    private fun addDailyUsage(startMs: Long, endMs: Long) {
        var cursor = startMs
        val end = endMs
        while (cursor < end) {
            val dayStart = startOfDay(cursor)
            val dayEnd = dayStart + TimeUnit.DAYS.toMillis(1)
            val segmentEnd = minOf(end, dayEnd)
            val segmentMs = (segmentEnd - cursor).coerceAtLeast(0L)
            if (segmentMs > 0L) {
                val key = dayKey(cursor)
                dailyUsageMs[key] = (dailyUsageMs[key] ?: 0L) + segmentMs
            }
            cursor = segmentEnd
        }
    }

    private fun dayKey(ms: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = ms
        return String.format("%04d-%02d-%02d", calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH))
    }

    private fun startOfDay(ms: Long): Long {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = ms
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    // ------------------------------------------------------------------
    // Flush / lifecycle
    // ------------------------------------------------------------------
    fun flush() {
        runCatching { PostHog.flush() }
    }

    // ------------------------------------------------------------------
    // Delete analytics data (local + server)
    // ------------------------------------------------------------------
    fun deleteLocalAnalyticsData() {
        sessionOpen = false
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(PENDING_SESSION_KEY)
            .remove(LAST_SESSION_DAY_KEY)
            .remove(FIRST_SEEN_PREFIX + anonymousUserId)
            .apply()
    }

    // Duplicate-request guard: only one server delete in flight at a time.
    @Volatile private var deleteInFlight = false

    /**
     * Deletes locally stored analytics state and asks the protected backend to
     * delete already-ingested events for this anonymous user.
     *
     * @param onComplete called on the main thread with (ok, errorMessageOrNull).
     *   ok=true means the server accepted the deletion (2xx). ok=false means failure.
     */
    fun deleteAnalyticsData(onComplete: (Boolean, String?) -> Unit) {
        // Duplicate-request prevention
        if (deleteInFlight) {
            onComplete(false, "delete_already_in_progress")
            return
        }
        deleteInFlight = true

        // 1) Local: drop pending local analytics state first.
        deleteLocalAnalyticsData()
        val context = appContext ?: run {
            deleteInFlight = false
            onComplete(false, "context_unavailable")
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val endpoint = prefs.getString(PREF_DELETE_URL, "") ?: ""
        val token = prefs.getString(PREF_DELETE_TOKEN, "") ?: ""
        if (endpoint.isBlank()) {
            deleteInFlight = false
            onComplete(false, "analytics_delete_endpoint_not_configured")
            return
        }
        // Enforce HTTPS only
        if (!endpoint.lowercase().startsWith("https://")) {
            deleteInFlight = false
            onComplete(false, "analytics_delete_endpoint_must_be_https")
            return
        }

        // 2) Server: ask the protected backend deletion endpoint to remove the
        //    anonymous user's already-ingested events. The admin key never leaves
        //    the server (held as a backend secret), only the anonymous id is sent.
        val userId = anonymousUserId
        val deleteThread = Thread {
            var ok = false
            var error: String? = null
            try {
                val connection = java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $token")
                val body = "{\"distinctId\":\"$userId\"}"
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                connection.disconnect()
                ok = status in 200..299
                if (!ok) {
                    error = responseBody.take(500)
                }
            } catch (t: Throwable) {
                error = t.message ?: "analytics_delete_failed"
            } finally {
                deleteInFlight = false
            }
            // Post result on main thread via a simple Handler
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete(ok, error)
            }
        }
        deleteThread.start()
    }

    // ------------------------------------------------------------------
    // Test helpers (package-visible for unit tests)
    // ------------------------------------------------------------------
    internal fun internalGetAnonymousUserId(): String = anonymousUserId

    internal fun internalSetConsentInstance(instance: AnalyticsConsent) {
        consentInstance = instance
    }

    internal fun internalReinitialiseForTest() {
        reinitialisePostHog()
    }
}
