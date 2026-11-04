package com.aide.mobilestudio.extension

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Server-backed store client — browser GitHub OAuth login + .msext upload.
 *
 * The app only holds an opaque session token handed back by the OAuth callback
 * (URL fragment: mobilestudio://ext-auth#session=...). The GitHub access token
 * stays on the server. Uploads go to POST /api/v1/extensions/upload and the
 * returned submissionId is polled until published/failed.
 *
 * Callbacks (onProgress / onDone / onError) run on a background thread —
 * callers must hop to the UI thread themselves.
 */
object ExtensionServerClient {

    data class Result(val success: Boolean, val message: String, val url: String? = null)

    private const val PREFS = "extensions"
    private const val SECURE_PREFS = "ext_session_secure"
    private const val KEY_SESSION = "server_session"
    private const val KEY_EXP = "server_session_exp"
    private const val KEY_LOGIN = "server_login"
    private const val KEY_BASE = "server_base_url"
    private const val DEFAULT_BASE = "https://mobilestudio-extension-store.onrender.com"
    private const val REDIRECT_TO = "mobilestudio://ext-auth"
    private const val POLL_MS = 2_000L
    private const val POLL_MAX_MS = 15 * 60_000L
    private const val DEFAULT_EXPIRES_IN = 7L * 24 * 3600

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE, DEFAULT_BASE)?.trim()?.trimEnd('/') ?: DEFAULT_BASE

    fun hasSession(context: Context): Boolean = session(context) != null

    fun session(context: Context): String? =
        prefs(context).getString(KEY_SESSION, null)?.takeIf { it.isNotBlank() }

    fun loginName(context: Context): String = prefs(context).getString(KEY_LOGIN, "") ?: ""

    fun sessionExpiry(context: Context): Long = prefs(context).getLong(KEY_EXP, 0L)

    fun saveSession(context: Context, token: String, expiresIn: Long) {
        val ttl = if (expiresIn > 0) expiresIn else DEFAULT_EXPIRES_IN
        prefs(context).edit()
            .putString(KEY_SESSION, token)
            .putLong(KEY_EXP, System.currentTimeMillis() + ttl * 1000L)
            .apply()
    }

    fun clearSession(context: Context) {
        prefs(context).edit().remove(KEY_SESSION).remove(KEY_EXP).remove(KEY_LOGIN).apply()
    }

    /** Starts browser OAuth: fetches the server-issued authorize URL (state+PKCE). */
    fun startLogin(activity: Activity, onError: (String) -> Unit) {
        Thread {
            try {
                val url = "${baseUrl(activity)}/api/v1/auth/github/login?redirect_to=${Uri.encode(REDIRECT_TO)}"
                val (code, body) = get(url, null)
                val authorize = if (code == 200 && body != null)
                    JSONObject(body).optString("authorizeUrl") else ""
                if (authorize.isBlank()) {
                    onError(serverMessage(body) ?: "로그인 시작 실패 (HTTP $code)")
                    return@Thread
                }
                activity.runOnUiThread {
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorize)))
                    } catch (e: Exception) {
                        onError("브라우저를 열 수 없습니다: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                onError("로그인 실패: ${e.message}")
            }
        }.start()
    }

    /** Refreshes the cached GitHub login name (/auth/me); drops an expired session. */
    fun refreshMe(context: Context, onDone: (String?, String?) -> Unit) {
        val token = session(context) ?: return onDone(null, null)
        Thread {
            try {
                val (code, body) = get("${baseUrl(context)}/api/v1/auth/me", token)
                if (code == 401) {
                    clearSession(context)
                    onDone(null, "세션이 만료되었습니다 — 다시 로그인하세요")
                } else if (code == 200 && body != null) {
                    val login = JSONObject(body).optString("login")
                    if (login.isNotBlank()) prefs(context).edit().putString(KEY_LOGIN, login).apply()
                    onDone(login.ifBlank { loginName(context) }, null)
                } else {
                    onDone(loginName(context), null)
                }
            } catch (e: Exception) {
                onDone(loginName(context), e.message)
            }
        }.start()
    }

    fun logout(context: Context) {
        val token = session(context)
        clearSession(context)
        if (token != null) Thread {
            try { post("${baseUrl(context)}/api/v1/auth/logout", token) } catch (_: Exception) {}
        }.start()
    }

    /** Uploads the packed .msext and polls the submission until it settles. */
    fun upload(
        context: Context,
        msext: File,
        extensionId: String,
        onProgress: (String) -> Unit,
        onDone: (Result) -> Unit
    ) {
        val token = session(context)
        if (token == null) {
            onDone(Result(false, "로그인이 필요합니다 — Login에서 브라우저로 로그인하세요"))
            return
        }
        Thread {
            val result = try {
                send(context, token, msext, extensionId, onProgress)
            } catch (e: Exception) {
                Result(false, "업로드 실패: ${e.message}")
            }
            onDone(result)
        }.start()
    }

    // ------------------------- internals -------------------------

    private fun send(
        context: Context,
        token: String,
        msext: File,
        extensionId: String,
        onProgress: (String) -> Unit
    ): Result {
        onProgress("업로드 중…")
        val boundary = "----MobileStudioBoundary" + System.currentTimeMillis()
        val conn = URL("${baseUrl(context)}/api/v1/extensions/upload").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.doOutput = true
        conn.outputStream.use { out ->
            out.write(("--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"extensionId\"\r\n\r\n" +
                "$extensionId\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"${msext.name}\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray(Charsets.UTF_8))
            msext.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        val body = readBody(conn)
        conn.disconnect()
        if (code == 401) return Result(false, "로그인이 만료되었습니다 — 다시 로그인하세요")
        if (code !in 200..299) return Result(false, serverMessage(body) ?: "업로드 실패 (HTTP $code)")
        val subId = if (body != null)
            runCatching { JSONObject(body).optString("submissionId") }.getOrNull() else null
        if (subId.isNullOrBlank()) return Result(false, "서버 응답에 submissionId가 없습니다")

        onProgress("검사 대기…")
        var waited = 0L
        var lastLine = ""
        while (waited < POLL_MAX_MS) {
            Thread.sleep(POLL_MS); waited += POLL_MS
            val (scode, sbody) = get("${baseUrl(context)}/api/v1/extensions/submissions/$subId", token)
            if (scode == 404) return Result(false, "submission을 찾을 수 없습니다 (서버가 재시작되었을 수 있습니다)")
            if (sbody == null) continue
            val obj = runCatching { JSONObject(sbody) }.getOrNull() ?: continue
            val status = obj.optString("status")
            val events = obj.optJSONArray("events")
            val lastEvent = if (events != null && events.length() > 0) events.getString(events.length() - 1) else ""
            val line = if (lastEvent.isBlank()) status else "$status — $lastEvent"
            if (line != lastLine) { lastLine = line; onProgress(line) }
            when (status) {
                "published" -> {
                    val ver = obj.optString("version")
                    val url = obj.optString("releaseUrl").ifBlank { obj.optString("downloadUrl") }
                    return Result(true, "게시 완료: ${obj.optString("extensionId")} v$ver", url.ifBlank { null })
                }
                "failed" -> {
                    val errs = obj.optJSONArray("errors")
                    val msg = if (errs != null) (0 until errs.length()).joinToString("; ") { errs.getString(it) } else ""
                    return Result(false, "게시 실패: ${msg.ifBlank { "검사 실패" }}")
                }
            }
        }
        return Result(false, "시간 초과 (15분) — 서버가 오래 걸리면 나중에 다시 확인하세요")
    }

    private fun get(url: String, token: String?): Pair<Int, String?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        val body = readBody(conn)
        conn.disconnect()
        return code to body
    }

    private fun post(url: String, token: String): Pair<Int, String?> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        val body = readBody(conn)
        conn.disconnect()
        return code to body
    }

    private fun readBody(conn: HttpURLConnection): String? = try {
        val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
        stream?.bufferedReader()?.readText()
    } catch (_: Exception) { null }

    /** Server error payloads: {"message": ...} or {"errors": [...]} — never logged. */
    private fun serverMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching {
            val obj = JSONObject(body)
            val msg = obj.optString("message")
            if (msg.isNotBlank()) return msg
            val errs = obj.optJSONArray("errors")
            if (errs != null && errs.length() > 0) errs.getString(0) else null
        }.getOrNull()
    }

    private fun prefs(context: Context) =
        com.aide.mobilestudio.SecurePrefs.get(context, SECURE_PREFS).also {
            com.aide.mobilestudio.SecurePrefs.migrateKeys(
                context, PREFS, it, setOf(KEY_SESSION, KEY_EXP, KEY_LOGIN)
            )
        }
}
