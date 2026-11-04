package com.aide.mobilestudio.ui.ide

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.UiPalette
import com.aide.mobilestudio.UiTheme

/**
 * MobileStudio IDE design system.
 *
 * Android Studio / IntelliJ Platform-inspired tokens (dense, flat, borders over
 * shadows), driven by the same [UiPalette] instance the imperative View UI
 * resolves, so View screens and Compose screens always match.
 */

class IdeColors(val p: UiPalette) {
    // structure
    val window: Color get() = Color(p.bg)
    val surface: Color get() = Color(p.surface)
    val panel: Color get() = Color(p.card)
    val hover: Color get() = Color(p.surface2)
    val border: Color get() = Color(p.border)
    val borderStrong: Color get() = Color(p.borderStrong)
    val divider: Color get() = Color(p.divider)
    val text: Color get() = Color(p.text)
    val textSoft: Color get() = Color(p.textSoft)
    val muted: Color get() = Color(p.muted)
    // semantics
    val accent: Color get() = Color(p.accent)
    val onAccent: Color get() = Color(p.onAccent)
    val success: Color get() = Color(p.success)
    val warning: Color get() = Color(p.warning)
    val error: Color get() = Color(p.error)
    val info: Color get() = Color(p.info)
    val selection: Color get() = Color(p.selection)
    val selectedBg: Color get() = Color(p.selectedBg)
    val disabled: Color get() = Color(p.disabled)
    // editor
    val editorBg: Color get() = Color(p.editorBg)
    val editorText: Color get() = Color(p.editorText)
    val gutterText: Color get() = Color(p.gutterText)
    // syntax
    val synKw: Color get() = Color(p.synKw)
    val synType: Color get() = Color(p.synType)
    val synFunc: Color get() = Color(p.synFunc)
    val synStr: Color get() = Color(p.synStr)
    val synNum: Color get() = Color(p.synNum)
    val synComment: Color get() = Color(p.synComment)
}

val LocalIdeColors = staticCompositionLocalOf { IdeColors(UiTheme.defaultDarkPalette()) }

val LocalShapes = compositionLocalOf { IdeShapes() }

class IdeShapes {
    val control = RoundedCornerShape(4.dp)   // buttons, inputs, chips
    val field = RoundedCornerShape(3.dp)     // text edits
    val panel = RoundedCornerShape(6.dp)     // panels, dialogs, cards
    val rows = RoundedCornerShape(4.dp)      // list row highlight
    val pill = RoundedCornerShape(999.dp)    // level dots, status pills only
}

/**
 * Single spacing / sizing scale for the whole IDE surface.
 *
 * Every Compose screen must compose from these tokens instead of ad-hoc dp
 * literals so the vertical rhythm, control heights and gutters stay identical
 * across tools (editor, logcat, shell, file browser, media viewer). This is the
 * main reason the pre-redesign screens looked inconsistent.
 */
object IdeDimens {
    // ── spacing scale (4dp based) ──
    val space1 = 2.dp
    val space2 = 4.dp
    val space3 = 6.dp
    val space4 = 8.dp
    val space5 = 12.dp
    val space6 = 16.dp

    // ── fixed chrome heights ──
    val toolbar = 48.dp
    val tabStrip = 30.dp
    val statusStrip = 24.dp

    // ── control sizes ──
    val control = 30.dp        // toolbar buttons, chips, icon buttons
    val controlTall = 34.dp    // primary actions, filter fields
    val row = 44.dp            // list rows
    val badge = 34.dp          // file/type badge in list rows

    // ── gutters ──
    val gutter = 12.dp
    val gutterTight = 8.dp
}

/** IDE typography: small (12–13sp) body, compact titles, mono for code/logs. */
fun ideTypography(): Typography {
    val mono = FontFamily.Monospace
    return Typography(
        displaySmall = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
        bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
        labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        // code styles (explicit families set at usage sites too)
        headlineMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontFamily = mono),
        headlineLarge = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontFamily = mono),
    )
}

fun UiPalette.toMaterialColorScheme(): ColorScheme {
    val dark = isDark
    return if (dark) darkColorScheme(
        primary = Color(accent),
        onPrimary = Color(onAccent),
        primaryContainer = Color(selection),
        onPrimaryContainer = Color(text),
        secondary = Color(info),
        onSecondary = Color(onAccent),
        secondaryContainer = Color(surface2),
        onSecondaryContainer = Color(text),
        tertiary = Color(success),
        onTertiary = Color(onAccent),
        tertiaryContainer = Color(okBg),
        onTertiaryContainer = Color(text),
        background = Color(bg),
        onBackground = Color(text),
        surface = Color(card),
        onSurface = Color(text),
        surfaceVariant = Color(surface2),
        onSurfaceVariant = Color(textSoft),
        surfaceDim = Color(bg),
        surfaceContainerLowest = Color(bg),
        surfaceContainerLow = Color(card),
        surfaceContainer = Color(surface),
        surfaceContainerHigh = Color(surface2),
        surfaceContainerHighest = Color(surface2),
        inverseSurface = Color(text),
        inverseOnSurface = Color(bg),
        error = Color(error),
        onError = Color(onAccent),
        errorContainer = Color(errBg),
        onErrorContainer = Color(text),
        outline = Color(border),
        outlineVariant = Color(divider),
        scrim = Color.Black,
    ) else lightColorScheme(
        primary = Color(accent),
        onPrimary = Color(onAccent),
        primaryContainer = Color(selection),
        onPrimaryContainer = Color(text),
        secondary = Color(info),
        onSecondary = Color(onAccent),
        secondaryContainer = Color(surface2),
        onSecondaryContainer = Color(text),
        tertiary = Color(success),
        onTertiary = Color(onAccent),
        tertiaryContainer = Color(okBg),
        onTertiaryContainer = Color(text),
        background = Color(bg),
        onBackground = Color(text),
        surface = Color(card),
        onSurface = Color(text),
        surfaceVariant = Color(surface2),
        onSurfaceVariant = Color(textSoft),
        surfaceDim = Color(bg),
        surfaceContainerLowest = Color(bg),
        surfaceContainerLow = Color(card),
        surfaceContainer = Color(surface),
        surfaceContainerHigh = Color(surface2),
        surfaceContainerHighest = Color(surface2),
        inverseSurface = Color(text),
        inverseOnSurface = Color(bg),
        error = Color(error),
        onError = Color(onAccent),
        errorContainer = Color(errBg),
        onErrorContainer = Color(text),
        outline = Color(border),
        outlineVariant = Color(divider),
        scrim = Color.Black,
    )
}

private val ideShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(10.dp),
)

/**
 * IDE theme container. The palette is the single source shared with the
 * imperative View UI ([com.aide.mobilestudio.UiTheme.resolve]), so both renderers
 * always agree on colors.
 */
@Composable
fun IdeTheme(palette: UiPalette, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIdeColors provides IdeColors(palette),
        LocalShapes provides IdeShapes(),
    ) {
        MaterialTheme(
            colorScheme = palette.toMaterialColorScheme(),
            typography = ideTypography(),
            shapes = ideShapes,
            content = content,
        )
    }
}

/** Convenience for screens that follow the global theme preference. */
@Composable
fun IdeThemeFromPrefs(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val palette = androidx.compose.runtime.remember(context) { UiTheme.resolve(context) }
    IdeTheme(palette, content)
}

object Ide {
    /** Compose color for an imperative palette hex constant (theme-remapped). */
    @Composable
    fun themedHex(hex: String, palette: UiPalette? = null): Color =
        Color(android.graphics.Color.parseColor(com.aide.mobilestudio.StudioUi.remapHex(hex)))
}

/** Monospace style presets used by consoles. */
object IdeMono {
    val log = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
    val terminal = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    val path = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

/** Small helper for "is system dark" screens that do not use the theme prefs. */
@Composable
fun isIdeDark(): Boolean = isSystemInDarkTheme()
