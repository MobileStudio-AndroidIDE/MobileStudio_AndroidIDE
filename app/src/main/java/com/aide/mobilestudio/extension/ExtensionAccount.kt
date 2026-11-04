package com.aide.mobilestudio.extension

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ExtensionAccount — Extension Hub 계정/로그인 모듈.
 *
 * Gmail 로그인 (Google Sign-In)을 사용한다. OAuth Web Client ID는
 * [clientId]에서 읽는다 (strings.xml의 extension_hub_client_id 또는
 * 기본값). Play Services가 없는 기기에서는 [isGoogleSignInAvailable]이
 * false가 되어 로컬 폴백 프로필로 로그인 없이 공개 기능만 사용한다.
 *
 * 계정 데이터 (로컬에 최소한으로만 저장):
 *   userId, displayName, photoUrl, email(기본 비공개), joinedAt, lastLogin,
 *   myExtensions[], likedExtensions[], installedExtensions[]
 *
 * 로그인 불필요: 검색 / 상세 보기 / 다운로드·설치
 * 로그인 필요:   업로드 / 좋아요 / 후기 / 신고 / My Extensions
 */
class ExtensionAccount private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ExtensionAccount"
        private const val PREFS = "extension_account"

        /** OAuth Web Client ID — strings.xml의 extension_hub_client_id로 설정. */
        fun clientId(context: Context): String = runCatching {
            val id = context.resources.getIdentifier("extension_hub_client_id", "string", context.packageName)
            if (id != 0) context.getString(id) else ""
        }.getOrDefault("")

        fun isGoogleSignInAvailable(context: Context): Boolean = runCatching {
            GoogleSignIn.getClient(context, GoogleSignInOptions.DEFAULT_SIGN_IN)
            true
        }.getOrDefault(false)

        @Volatile private var instance: ExtensionAccount? = null
        fun get(context: Context): ExtensionAccount =
            instance ?: synchronized(this) { instance ?: ExtensionAccount(context.applicationContext).also { instance = it } }
    }

    data class Profile(
        val userId: String,
        val displayName: String,
        val photoUrl: String,
        val email: String,
        val joinedAt: Long,
        val lastLogin: Long
    ) {
        /** 이메일은 다른 사용자에게 기본적으로 공개하지 않는다. */
        fun publicJson(): JSONObject = JSONObject()
            .put("userId", userId)
            .put("displayName", displayName)
            .put("photoUrl", photoUrl)
    }

    private var googleClient: GoogleSignInClient? = null

    // ------------------------- session -------------------------

    /** 로그인된 프로필, 없으면 null. lastLogin을 갱신한다. */
    fun current(): Profile? {
        val p = loadProfile() ?: return null
        if (System.currentTimeMillis() - p.lastLogin > 30L * 24 * 3600 * 1000) return null // 30일 세션
        touchLastLogin(p.userId)
        return p
    }

    fun isLoggedIn(): Boolean = current() != null

    /** 로그인이 필요한 기능(업로드/좋아요/후기/신고/My Extensions) 가드. */
    fun requireLogin(action: String): String? =
        if (isLoggedIn()) null else "$action 하려면 Gmail 로그인이 필요합니다"

    fun googleSignInClient(): GoogleSignInClient {
        if (googleClient == null) {
            val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
            // Web Client ID가 설정되어 있으면 id token도 요청 (server-side 검증용)
            clientId(context).takeIf { it.isNotBlank() }?.let { builder.requestIdToken(it) }
            googleClient = GoogleSignIn.getClient(context, builder.build())
        }
        return googleClient!!
    }

    /** Google Sign-In 결과 처리 — onActivityResult에서 호출. */
    fun handleSignInResult(data: Intent?): Profile? {
        return try {
            val acct = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            saveFromGoogle(acct)
        } catch (e: ApiException) {
            Log.w(TAG, "Gmail sign-in failed: ${e.statusCode}")
            null
        }
    }

    private fun saveFromGoogle(acct: GoogleSignInAccount): Profile {
        val now = System.currentTimeMillis()
        val existing = loadProfile()
        val profile = Profile(
            userId = acct.id ?: acct.email?.hashCode()?.toString() ?: "user_$now",
            displayName = acct.displayName ?: acct.email?.substringBefore('@') ?: "User",
            photoUrl = acct.photoUrl?.toString() ?: "",
            email = acct.email ?: "",
            joinedAt = existing?.joinedAt ?: now,
            lastLogin = now
        )
        saveProfile(profile)
        return profile
    }

    fun signOut() {
        runCatching { googleClient?.signOut() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ------------------------- profile storage -------------------------

    private fun loadProfile(): Profile? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("profile", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Profile(
                userId = o.optString("userId"),
                displayName = o.optString("displayName"),
                photoUrl = o.optString("photoUrl"),
                email = o.optString("email"),
                joinedAt = o.optLong("joinedAt"),
                lastLogin = o.optLong("lastLogin")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveProfile(p: Profile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("profile", JSONObject()
                .put("userId", p.userId)
                .put("displayName", p.displayName)
                .put("photoUrl", p.photoUrl)
                .put("email", p.email)
                .put("joinedAt", p.joinedAt)
                .put("lastLogin", p.lastLogin)
                .toString())
            .apply()
    }

    private fun touchLastLogin(userId: String) {
        val p = loadProfile() ?: return
        if (p.userId != userId) return
        saveProfile(p.copy(lastLogin = System.currentTimeMillis()))
    }

    // ------------------------- account lists -------------------------

    private fun setOf_(key: String): MutableSet<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun putSet(key: String, v: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(key, v).apply()
    }

    /** 개발자가 만든 확장 목록 (extension id들). */
    fun myExtensions(): List<String> = setOf_("my_extensions").toList()

    fun addMyExtension(id: String) { val s = setOf_("my_extensions"); s.add(id); putSet("my_extensions", s) }

    /** 좋아요한 확장 목록. */
    fun likedExtensions(): List<String> = setOf_("liked_extensions").toList()

    fun setLikedExtensions(liked: List<String>) { putSet("liked_extensions", liked.toSet()) }

    /** 설치한 확장 목록 (ExtensionManager 상태 기반). */
    fun installedExtensions(): List<String> =
        ExtensionManager.get(context).installed().map { it.manifest.id }

    // ------------------------- statistics -------------------------

    /** 로컬 계정 통계 요약 (공개 데이터만 포함). */
    fun accountSummary(): JSONObject {
        val p = current()
        return JSONObject()
            .put("loggedIn", p != null)
            .put("displayName", p?.displayName ?: "")
            .put("myExtensions", myExtensions().size)
            .put("likedExtensions", likedExtensions().size)
            .put("installedExtensions", installedExtensions().size)
    }
}
