package com.aide.mobilestudio.extension

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHubAuth — GitHub login, two server-less paths:
 *
 *  1. PAT (default): the app opens the token creation page in the browser with the
 *     required scopes pre-selected, the user copies the token and pastes it back.
 *     [loginWithToken] validates it against api.github.com/user and stores it.
 *
 *  2. Device Flow (optional): requires an OAuth App client_id (NOT a secret),
 *     entered once in the Login dialog or via the `github_oauth_client_id`
 *     string resource. Then the user only approves a device code in the browser.
 *
 * Either way the resulting token (scope: repo) is stored in
 * EncryptedSharedPreferences and used directly to create a PUBLIC release under
 * the logged-in account. Tokens are NEVER logged.
 */
object GitHubAuth {

    private const val TAG = "GitHubAuth"
    private const val PREFS = "extensions"
    private const val SECURE_PREFS = "extensions_secure"
    private const val KEY_CLIENT_ID = "github_oauth_client_id"
    private const val KEY_TOKEN = "github_user_token"
    private const val KEY_LOGIN = "github_login"
    private const val KEY_SCOPES = "github_oauth_scopes"

    private const val DEVICE_CODE_URL = "https://github.com/login/device/code"
    private const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    private const val SCOPE = "repo"
    private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    private const val USER_API = "https://api.github.com/user"

    /**
     * Classic Personal Access Token creation page with the needed scopes already
     * ticked (the user only has to press "Generate token" and copy it).
     */
    const val NEW_TOKEN_URL =
        "https://github.com/settings/tokens/new?description=MobileStudio&scopes=repo"

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val interval: Int,
        val expiresIn: Int
    )

    /** Result of a validated token: who it belongs to and which scopes it carries. */
    data class TokenInfo(val login: String, val scopes: String) {
        /** GitHub only returns the `x-oauth-scopes` header for classic tokens. */
        val isClassic: Boolean get() = scopes.isNotBlank()
        fun scopeWarning(): String? = when {
            !isClassic -> "fine-grained 토큰 — 저장소 생성이 막힐 수 있어 classic 토큰(‘repo’ 권한) 권장"
            !GitHubAuth.hasRepoScope(scopes) -> "‘repo’ 권한이 없습니다 (현재: $scopes) — 게시 시 실패합니다"
            else -> null
        }
    }

    private fun prefs(context: Context) =
        com.aide.mobilestudio.SecurePrefs.get(context, SECURE_PREFS).also {
            com.aide.mobilestudio.SecurePrefs.migrateKeys(context, PREFS, it, setOf(KEY_TOKEN, KEY_LOGIN, KEY_SCOPES))
        }

    // ------------------------- client id -------------------------

    fun clientId(context: Context): String {
        val stored = prefs(context).getString(KEY_CLIENT_ID, null)?.trim()
        if (!stored.isNullOrBlank()) return stored
        return runCatching {
            val id = context.resources.getIdentifier("github_oauth_client_id", "string", context.packageName)
            if (id != 0) context.getString(id).trim() else ""
        }.getOrDefault("")
    }

    fun setClientId(context: Context, id: String) {
        prefs(context).edit().putString(KEY_CLIENT_ID, id.trim()).apply()
    }

    fun isConfigured(context: Context): Boolean = clientId(context).isNotBlank()

    // ------------------------- session -------------------------

    fun token(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }

    fun login(context: Context): String =
        prefs(context).getString(KEY_LOGIN, "") ?: ""

    /** Scope list recorded at login time (classic tokens only; blank when unknown). */
    fun scopes(context: Context): String =
        prefs(context).getString(KEY_SCOPES, "") ?: ""

    /** True when the stored token can actually create repos/releases. */
    fun canPublish(context: Context): Boolean {
        val s = scopes(context)
        // Unknown (fine-grained / legacy session) — let the API be the judge.
        return s.isBlank() || hasRepoScope(s)
    }

    fun isLoggedIn(context: Context): Boolean = token(context) != null

    fun signOut(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_LOGIN).remove(KEY_SCOPES).apply()
    }

    /** Stores token + resolved login (also mirrored into GitHubPublisher for releasing). */
    fun saveSession(context: Context, token: String, login: String, scopes: String = "") {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_LOGIN, login)
            .putString(KEY_SCOPES, scopes)
            .apply()
        GitHubPublisher.setToken(context, token)
    }

    // ------------------------- PAT login (default path) -------------------------

    /** Strips whitespace, newlines and an accidental "Bearer " prefix. */
    fun normalizeToken(raw: String): String {
        var t = raw.replace(Regex("\\s+"), "")
        if (t.startsWith("bearer", ignoreCase = true)) t = t.substring("bearer".length)
        return t
    }

    /**
     * Cheap shape check so the clipboard/typed input can be recognised before any
     * network round-trip. Never logs the value.
     */
    fun looksLikeToken(raw: String): Boolean {
        val t = normalizeToken(raw)
        if (t.length < 20) return false
        return t.startsWith("github_pat_") ||                       // fine-grained
            Regex("^(gh[pousr]_[A-Za-z0-9_]{20,})$").matches(t) ||   // classic / oauth / app
            Regex("^[0-9a-fA-F]{40}$").matches(t)                   // legacy 40-char
    }

    /** True when a classic token's scope list grants repository access. */
    fun hasRepoScope(scopes: String): Boolean =
        scopes.split(',').map { it.trim() }.any { it == "repo" || it == "public_repo" }

    /** Reads a token-looking string from the clipboard, or null. */
    fun clipToken(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
        text.takeIf { looksLikeToken(it) }?.let { normalizeToken(it) }
    }.getOrNull()

    /** Validates [token] against api.github.com/user. Null when rejected/unreachable. */
    fun validateToken(token: String): TokenInfo? {
        return try {
            val conn = URL(USER_API).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer ${normalizeToken(token)}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            val code = conn.responseCode
            val body = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()
            val scopes = conn.getHeaderField("x-oauth-scopes") ?: ""
            conn.disconnect()
            if (code != 200) {
                Log.w(TAG, "validateToken rejected (HTTP $code)")
                null
            } else {
                val login = JSONObject(body ?: "{}").optString("login")
                if (login.isBlank()) null else TokenInfo(login, scopes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "validateToken failed: ${e.message}")
            null
        }
    }

    /**
     * One-shot PAT login: validates the pasted token, then stores it as the
     * session (GitHubAuth) and as the publishing credential (GitHubPublisher).
     * [onDone] runs on a background thread.
     */
    fun loginWithToken(context: Context, token: String, onDone: (Boolean, String) -> Unit) {
        val normalized = normalizeToken(token)
        if (normalized.isBlank()) {
            onDone(false, "토큰을 붙여넣으세요")
            return
        }
        Thread {
            val info = validateToken(normalized)
            if (info == null) {
                onDone(false, "토큰 인증 실패 — 토큰이 만료되었거나 권한이 없습니다")
                return@Thread
            }
            saveSession(context, normalized, info.login, info.scopes)
            val warning = info.scopeWarning()
            onDone(true, if (warning != null) "로그인됨: ${info.login} (⚠ $warning)" else "로그인됨: ${info.login}")
        }.start()
    }

    /**
     * Re-validates the stored token so a session created before scopes were
     * recorded (or a token whose scopes were edited on github.com) gets an
     * accurate publishability answer. [onDone] runs on a background thread.
     */
    fun refreshScopes(context: Context, onDone: (String) -> Unit) {
        val stored = token(context)
        if (stored == null) { onDone(""); return }
        Thread {
            val info = validateToken(stored)
            val current = if (info != null) {
                saveSession(context, stored, info.login, info.scopes)
                info.scopes
            } else {
                scopes(context)
            }
            onDone(current)
        }.start()
    }

    // ------------------------- device flow -------------------------

    /**
     * Full login: request a device code, open the verification page in a browser,
     * poll for the token. [onCode] is invoked on the calling thread with the device
     * code so the UI can display the user code; [onDone] runs on a background thread.
     */
    fun startLogin(
        activity: Activity,
        onCode: (DeviceCode) -> Unit,
        onStatus: (String) -> Unit,
        onDone: (Boolean, String) -> Unit
    ) {
        val client = clientId(activity)
        if (client.isBlank()) {
            onDone(false, "GitHub OAuth client_id가 설정되지 않았습니다")
            return
        }
        Thread {
            try {
                onStatus("기기 코드 요청 중…")
                val code = requestDeviceCode(client)
                activity.runOnUiThread {
                    onCode(code)
                    runCatching {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri)))
                    }
                }
                onStatus("브라우저에서 코드 ${code.userCode} 를 입력하세요")
                pollForToken(activity, client, code, onStatus) { ok, msg, token ->
                    if (ok && token != null) {
                        val login = resolveLogin(token)
                        if (login.isBlank()) {
                            onDone(false, "로그인 확인 실패 (토큰은 유효하지만 사용자 정보를 가져오지 못했습니다)")
                        } else {
                            saveSession(activity, token, login, SCOPE)
                            onDone(true, "GitHub 로그인됨: $login")
                        }
                    } else {
                        onDone(false, msg)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "startLogin failed", e)
                onDone(false, "로그인 실패: ${e.message}")
            }
        }.start()
    }

    private fun requestDeviceCode(clientId: String): DeviceCode {
        val body = "client_id=${enc(clientId)}&scope=${enc(SCOPE)}"
        val (code, resp) = postForm(DEVICE_CODE_URL, body)
        if (code !in 200..299) throw Exception("device code 요청 실패 (HTTP $code): ${resp?.take(120) ?: ""}")
        val json = JSONObject(resp ?: "{}")
        val deviceCode = json.optString("device_code")
        if (deviceCode.isBlank()) throw Exception(json.optString("error_description").ifBlank { "device_code 없음" })
        return DeviceCode(
            deviceCode = deviceCode,
            userCode = json.optString("user_code"),
            verificationUri = json.optString("verification_uri").ifBlank { "https://github.com/login/device" },
            interval = json.optInt("interval", 5).coerceAtLeast(1),
            expiresIn = json.optInt("expires_in", 900)
        )
    }

    private fun pollForToken(
        context: Context,
        clientId: String,
        code: DeviceCode,
        onStatus: (String) -> Unit,
        onDone: (Boolean, String, String?) -> Unit
    ) {
        var interval = code.interval
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L)
            val body = "client_id=${enc(clientId)}&device_code=${enc(code.deviceCode)}&grant_type=${enc(DEVICE_GRANT)}"
            val (http, resp) = try {
                postForm(TOKEN_URL, body)
            } catch (e: Exception) {
                onDone(false, "토큰 요청 실패: ${e.message}", null); return
            }
            val json = try { JSONObject(resp ?: "{}") } catch (_: Exception) { JSONObject() }
            val token = json.optString("access_token")
            if (token.isNotBlank()) { onDone(true, "로그인됨", token); return }
            when (json.optString("error")) {
                "authorization_pending" -> Unit
                "slow_down" -> { interval += 5; onStatus("요청이 느려 대기 시간을 늘립니다…") }
                "expired_token" -> { onDone(false, "코드가 만료되었습니다 — 다시 시도하세요", null); return }
                "access_denied" -> { onDone(false, "사용자가 승인을 거부했습니다", null); return }
                "" -> if (http !in 200..299) { onDone(false, "토큰 요청 실패 (HTTP $http)", null); return }
                else -> { onDone(false, "로그인 실패: ${json.optString("error_description", json.optString("error"))}", null); return }
            }
        }
        onDone(false, "시간 초과 — 다시 로그인하세요", null)
    }

    /** Resolves the authenticated login for a token (independent of the stored token). */
    private fun resolveLogin(token: String): String {
        return try {
            val conn = URL("https://api.github.com/user").openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.connectTimeout = 20_000
            conn.readTimeout = 20_000
            val ok = conn.responseCode == 200
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
            conn.disconnect()
            if (ok) JSONObject(text ?: "{}").optString("login") else ""
        } catch (e: Exception) {
            Log.w(TAG, "resolveLogin failed: ${e.message}")
            ""
        }
    }

    private fun postForm(urlStr: String, body: String): Pair<Int, String?> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.doOutput = true
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { out: OutputStream -> out.write(bytes) }
        val code = conn.responseCode
        val text = try {
            (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
        } catch (_: Exception) { null }
        conn.disconnect()
        return code to text
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
