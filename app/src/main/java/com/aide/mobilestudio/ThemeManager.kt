package com.aide.mobilestudio

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.aide.mobilestudio.ui.ide.IdeTheme

object ThemeManager {
    const val PREF_NAME = "ide"
    const val KEY_THEME = "app_theme"
    const val KEY_DARK_MODE = "dark_mode"

    private val revisionState = mutableStateOf(0)

    /** Monotonic revision observed by [ProvideTheme]; bump on every theme change. */
    val revision get() = revisionState

    fun themeRevision(): Int = revisionState.value

    fun notifyThemeChanged() {
        revisionState.value = revisionState.value + 1
    }

    /** Resolves a persisted label ("Dark", "Light", ...) to an enum value. */
    fun themeFromLabel(label: String): AppTheme =
        AppTheme.entries.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: AppTheme.entries.firstOrNull { it.name.equals(label, ignoreCase = true) }
            ?: AppTheme.DARK

    enum class AppTheme(val label: String) {
        DARK("Dark"),
        LIGHT("Light"),
        DRACULA("Dracula"),
        MONOKAI("Monokai"),
        NORD("Nord"),
        ONE_DARK_PRO("One Dark Pro")
    }

    enum class DarkMode(val label: String) {
        SYSTEM("System default"),
        LIGHT("Light"),
        DARK("Dark")
    }

    fun getTheme(context: Context): AppTheme {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_THEME, AppTheme.DARK.name) ?: AppTheme.DARK.name
        return runCatching { AppTheme.valueOf(name) }.getOrDefault(AppTheme.DARK)
    }

    fun setTheme(context: Context, theme: AppTheme) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme.name).apply()
        // Keep the Settings screen value in sync (it historically persisted its
        // own "theme" string in mobilestudio_settings).
        context.getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
            .edit().putString("theme", theme.label).apply()
        // Refresh the process-wide imperative-UI palette immediately (via the
        // same UiTheme.resolve() that MainActivity.onCreate uses — one source
        // of truth) so any view built afterwards picks up the new theme even
        // on screens that never call UiTheme.resolve() themselves and never
        // get a recreate().
        runCatching { UiTheme.resolve(context) }
        notifyThemeChanged()
    }

    fun getDarkMode(context: Context): DarkMode {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DARK_MODE, DarkMode.SYSTEM.name) ?: DarkMode.SYSTEM.name
        return runCatching { DarkMode.valueOf(name) }.getOrDefault(DarkMode.SYSTEM)
    }

    fun setDarkMode(context: Context, mode: DarkMode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DARK_MODE, mode.name).apply()
    }

    /**
     * Single source of truth: resolve the shared [UiPalette] (the exact instance
     * the View-based shell renders with, including Dracula/Monokai/Nord) and let
     * [IdeTheme] derive the Compose color scheme/typography from it.
     */
    @Composable
    fun ProvideTheme(content: @Composable () -> Unit) {
        val context = LocalContext.current
        val revision: Int = ThemeManager.themeRevision()
        val palette = UiTheme.resolve(context)
        IdeTheme(palette, content)
    }
}
