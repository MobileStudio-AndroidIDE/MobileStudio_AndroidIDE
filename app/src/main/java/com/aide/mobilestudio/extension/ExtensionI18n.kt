package com.aide.mobilestudio.extension

import android.content.Context
import com.aide.mobilestudio.AppLanguage
import org.json.JSONObject
import java.io.File

/**
 * Localises extension metadata (name / description) into the user's app language.
 *
 * Resolution order, cheapest first:
 *  1. a translation the extension itself ships under `i18n/<lang>.json`
 *     (keyed by the original string) — exact, offline, zero cost;
 *  2. the keyless machine-translation engine, memoised to disk so every string
 *     costs at most one network round-trip ever;
 *  3. the original string.
 *
 * Toggleable and on by default. With the toggle off nothing is read from disk
 * and no request leaves the device.
 */
object ExtensionI18n {

    private const val PREFS = "extensions"
    private const val KEY_AUTO_TRANSLATE = "auto_translate_ext"
    private const val CACHE_DIR = "extensions/.i18n-cache"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_TRANSLATE, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_TRANSLATE, enabled)
            .apply()
    }

    /** Localises one metadata string for display. Never throws. */
    fun localize(
        context: Context,
        original: String,
        languages: List<String> = emptyList(),
        extensionDir: File? = null
    ): String {
        if (original.isBlank()) return original
        val code = AppLanguage.current(context).code
        // Source languages need no translation, and the toggle must win.
        if (code == "ko" || code == "en" || !isEnabled(context)) return original

        bundled(extensionDir, original, code)?.let { return it }

        cacheRead(context, code, original)?.let { return it }
        val translated = AppLanguage.translateViaApi(original, code)
        if (translated != original && translated.isNotBlank()) {
            cacheWrite(context, code, original, translated)
            return translated
        }
        return original
    }

    /** Looks up `<extDir>/i18n/<code>.json` for a translation of [original]. */
    private fun bundled(extensionDir: File?, original: String, code: String): String? {
        val dir = extensionDir ?: return null
        val file = File(File(dir, "i18n"), "$code.json")
        if (!file.isFile) return null
        return try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            val v = o.optString(original, "")
            if (v.isBlank() || v == original) null else v
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheFile(context: Context, code: String): File =
        File(File(context.filesDir, CACHE_DIR), "$code.properties")

    private fun cacheRead(context: Context, code: String, original: String): String? {
        return try {
            val f = cacheFile(context, code)
            if (!f.isFile) return null
            val props = java.util.Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty(original)?.takeIf { it.isNotBlank() && it != original }
        } catch (e: Exception) {
            null
        }
    }

    private fun cacheWrite(context: Context, code: String, original: String, translated: String) {
        try {
            val f = cacheFile(context, code)
            f.parentFile?.mkdirs()
            val props = java.util.Properties()
            f.inputStream().use { props.load(it) }
            props.setProperty(original, translated)
            f.outputStream().use { props.store(it, "MobileStudio extension i18n cache ($code)") }
        } catch (e: Exception) {
            // cache is best-effort; a full disk must not break the UI
        }
    }
}
