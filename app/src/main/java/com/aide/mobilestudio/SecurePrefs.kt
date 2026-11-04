package com.aide.mobilestudio

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecurePrefs — EncryptedSharedPreferences factory with a plaintext fallback.
 *
 * Secrets (AI API keys, GitHub/OAuth tokens, session tokens) should not sit in
 * cleartext XML on disk. Callers keep using their normal key names; this only
 * changes the backing store.
 *
 * Because [EncryptedSharedPreferences] cannot open a file that already holds
 * plaintext, use a NEW file name (e.g. "ai_settings" -> "ai_settings_secure")
 * and call [migrateAll]/[migrateKeys] once to move existing values over.
 *
 * Falls back to a regular SharedPreferences when the Android Keystore is
 * unavailable (rare), so the app keeps working rather than crashing.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"

    private val cache = HashMap<String, SharedPreferences>()

    @Synchronized
    fun get(context: Context, fileName: String): SharedPreferences {
        cache[fileName]?.let { return it }
        val app = context.applicationContext
        val prefs = try {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Encrypted prefs unavailable ($fileName) — using plaintext fallback", t)
            app.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        }
        cache[fileName] = prefs
        return prefs
    }

    /** Copies every entry from the legacy plaintext file into [target], then clears the legacy file. */
    fun migrateAll(context: Context, legacyFileName: String, target: SharedPreferences) {
        try {
            val legacy = context.applicationContext
                .getSharedPreferences(legacyFileName, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) return
            if (target.all.isNotEmpty()) {
                legacy.edit().clear().apply()
                return
            }
            copy(target, legacy.all)
            legacy.edit().clear().apply()
        } catch (t: Throwable) {
            Log.w(TAG, "migrateAll($legacyFileName) failed: ${t.message}")
        }
    }

    /** Copies only [keys] from the legacy plaintext file into [target], then removes them from the legacy file. */
    fun migrateKeys(context: Context, legacyFileName: String, target: SharedPreferences, keys: Set<String>) {
        try {
            val legacy = context.applicationContext
                .getSharedPreferences(legacyFileName, Context.MODE_PRIVATE)
            val present = legacy.all.filterKeys { it in keys }
            if (present.isEmpty()) return
            if (target.all.keys.any { it in keys }) {
                present.keys.forEach { legacy.edit().remove(it).apply() }
                return
            }
            copy(target, present)
            present.keys.forEach { legacy.edit().remove(it).apply() }
        } catch (t: Throwable) {
            Log.w(TAG, "migrateKeys($legacyFileName) failed: ${t.message}")
        }
    }

    private fun copy(target: SharedPreferences, entries: Map<String, *>) {
        val editor = target.edit()
        for ((key, value) in entries) {
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    editor.putStringSet(key, value as Set<String>)
                }
            }
        }
        editor.apply()
    }
}
