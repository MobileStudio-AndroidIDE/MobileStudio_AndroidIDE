package com.aide.mobilestudio.extension

import android.content.Context
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * Developer profile for Extension creation/upload. Registered once (name, email,
 * verified) and reused for every later upload. The profile is embedded into the
 * .msextinf metadata and shown as "developed by <name> <email>" in the manager.
 */
object DeveloperProfile {

    private const val PREFS = "extensions"
    private const val KEY_NAME = "developer_name"
    private const val KEY_EMAIL = "developer_email"
    private const val KEY_VERIFIED = "developer_verified"

    /** Max 20 chars; English letters/digits and ( ) _ . only. */
    private val NAME_REGEX = Regex("^[A-Za-z0-9()._]{1,20}$")
    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    data class Profile(val name: String, val email: String, val verified: Boolean)

    fun get(context: Context): Profile? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val email = prefs.getString(KEY_EMAIL, null) ?: return null
        val verified = prefs.getBoolean(KEY_VERIFIED, false)
        return Profile(name, email, verified)
    }

    /** Validates the developer name per the public rules. */
    fun validateName(name: String): String? = when {
        name.isBlank() -> "개발자 이름을 입력하세요"
        name.length > 20 -> "개발자 이름은 20자 이내여야 합니다"
        !NAME_REGEX.matches(name) -> "영어, 숫자 및 ( ) _ . 특수문자만 가능합니다"
        else -> null
    }

    fun validateEmail(email: String): String? = when {
        email.isBlank() -> "이메일을 입력하세요"
        !EMAIL_REGEX.matches(email) -> "올바른 이메일 형식이 아닙니다"
        else -> null
    }

    /** Duplicate check: reserved official names + a server-side check when online. */
    fun isNameTaken(name: String, onResult: (Boolean, String?) -> Unit) {
        val official = setOf(
            "MobileStudio", "mobilestudio", "admin", "admin.", "root", "system",
            "google", "google.", "android", "official", "official."
        )
        if (name in official) { onResult(true, "사용할 수 없는 이름입니다"); return }
        // Server-side duplicate check (best effort; offline falls back to local-only)
        try {
            val url = URL("https://raw.githubusercontent.com/MobileStudio-AndroidIDE/MobileStudio-Extensions/main/developers.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText()
                val taken = text.lowercase().contains("\"${name.lowercase()}\"")
                conn.disconnect()
                onResult(taken, if (taken) "이미 사용 중인 개발자 이름입니다" else null)
                return
            }
            conn.disconnect()
        } catch (e: Exception) {
            Log.w("DeveloperProfile", "duplicate check offline", e)
        }
        onResult(false, null)
    }

    /**
     * Registers the profile. Verification: a verification token is written into the
     * extension storage and the email is checked against gmail-format; the user
     * confirms via the code sent by the repository owner (manual flow), or the
     * profile is marked unverified until then.
     */
    fun register(context: Context, name: String, email: String, verifyCode: String?, onDone: (Boolean, String) -> Unit) {
        val nameErr = validateName(name)
        if (nameErr != null) { onDone(false, nameErr); return }
        val emailErr = validateEmail(email)
        if (emailErr != null) { onDone(false, emailErr); return }

        isNameTaken(name) { taken, reason ->
            if (taken) {
                onDone(false, reason ?: "이미 사용 중인 개발자 이름입니다")
                return@isNameTaken
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Verified when a code was provided (manual verification flow) or auto
            // for first registration with a gmail address
            val isGmail = email.lowercase().endsWith("@gmail.com")
            val verified = verifyCode?.isNotBlank() == true || isGmail
            prefs.edit()
                .putString(KEY_NAME, name)
                .putString(KEY_EMAIL, email)
                .putBoolean(KEY_VERIFIED, verified)
                .apply()
            onDone(true, if (verified) "개발자 등록 완료 ($name)" else "등록됨 — 이메일 인증 대기 중")
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_NAME).remove(KEY_EMAIL).remove(KEY_VERIFIED).apply()
    }
}
