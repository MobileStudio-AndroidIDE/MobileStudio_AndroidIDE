package com.aide.mobilestudio

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

data class UiPalette(
    val isDark: Boolean,
    // structure
    val bg: Int,
    val surface: Int,
    val surface2: Int,
    val card: Int,
    val border: Int,
    val borderStrong: Int,
    val divider: Int,
    val text: Int,
    val textSoft: Int,
    val muted: Int,
    // semantics
    val accent: Int,
    val onAccent: Int,
    val success: Int,
    val warning: Int,
    val error: Int,
    val info: Int,
    val selection: Int,
    val selectedBg: Int,
    val disabled: Int,
    // editor
    val editorBg: Int,
    val editorText: Int,
    val gutterBg: Int,
    val gutterText: Int,
    val gutterTextActive: Int,
    val currentLine: Int,
    val caret: Int,
    val editorIndentGuide: Int,
    val editorSelection: Int,
    // syntax highlighting
    val synKw: Int,
    val synType: Int,
    val synFunc: Int,
    val synStr: Int,
    val synNum: Int,
    val synComment: Int,
    val synAnnotation: Int,
    val synIdent: Int,
    val synOperator: Int,
    val synTag: Int,
    val synAttr: Int,
    // extended syntax highlighting
    val synParam: Int,
    val synField: Int,
    val synConst: Int,
    val synEnum: Int,
    val synLabel: Int,
    val synDoc: Int,
    val synBracket: Int,
    val synGeneric: Int,
    val synImport: Int,
    val synBoolean: Int,
    // file explorer indicators
    val indicatorError: Int,
    val indicatorWarning: Int,
    val indicatorModified: Int,
    val indicatorReadonly: Int,
    val indicatorFavorite: Int,
    // terminal semantic
    val termCommand: Int,
    val termGradleTask: Int,
    val termUrl: Int,
    val termExitCode: Int,
    val termComment: Int,
    val termPath: Int,
    // line-level tints
    val errBg: Int,
    val warnBg: Int,
    val okBg: Int
)

object UiTheme {

    private fun isDarkMode(context: Context): Boolean {
        val mode = ThemeManager.getDarkMode(context)
        return when (mode) {
            ThemeManager.DarkMode.LIGHT -> false
            ThemeManager.DarkMode.DARK -> true
            ThemeManager.DarkMode.SYSTEM ->
                (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun isDark(context: Context): Boolean = isDarkMode(context)

    fun resolve(context: Context): UiPalette {
        val theme = ThemeManager.getTheme(context)
        val dark = isDarkMode(context)
        val p = when (theme) {
            // An explicit Dark/Light choice wins over the system night mode;
            // otherwise the IDE could render light while the user picked Dark.
            ThemeManager.AppTheme.DARK -> darkPalette()
            ThemeManager.AppTheme.LIGHT -> lightPalette()
            // Named themes have a light and a dark variant; follow the dark-mode
            // preference (or the system setting when it is set to SYSTEM).
            ThemeManager.AppTheme.DRACULA -> if (dark) draculaDarkPalette() else draculaLightPalette()
            ThemeManager.AppTheme.MONOKAI -> if (dark) monokaiDarkPalette() else monokaiLightPalette()
            ThemeManager.AppTheme.NORD -> if (dark) nordDarkPalette() else nordLightPalette()
        }
        StudioUi.applyTheme(p)
        return p
    }

    fun defaultDarkPalette(): UiPalette = darkPalette()

    fun onAccent(accent: Int): Int {
        val r = Color.red(accent); val g = Color.green(accent); val b = Color.blue(accent)
        val luma = 0.299f * r + 0.587f * g + 0.114f * b
        return if (luma > 165f) Color.parseColor("#1A1A1A") else Color.WHITE
    }

    // ═══════════════════════════════════════════════════════════════
    // DARK — IntelliJ Platform "Dark" (Android Studio New UI) tokens
    // ═══════════════════════════════════════════════════════════════
    private fun darkPalette(): UiPalette {
        val accent = C("#3574F0")
        return UiPalette(
            isDark = true,
            bg = C("#1E1F22"), surface = C("#2B2D30"), surface2 = C("#393B40"),
            card = C("#2B2D30"), border = C("#43454A"), borderStrong = C("#54565B"),
            divider = C("#333538"), text = C("#DFDFDF"), textSoft = C("#9B9DA3"),
            muted = C("#6F737A"),
            accent = accent, onAccent = onAccent(accent),
            success = C("#57965C"), warning = C("#D8A762"), error = C("#F75464"),
            info = C("#548AF7"), selection = C("#2E436E"),
            selectedBg = C("#2E436E"), disabled = C("#5A5D63"),
            editorBg = C("#1E1F22"), editorText = C("#DFDFDF"),
            gutterBg = C("#1E1F22"), gutterText = C("#606366"), gutterTextActive = C("#A8AAB0"),
            currentLine = blend(accent, C("#1E1F22"), 0.06f), caret = C("#BBBBBB"),
            editorIndentGuide = C("#323438"), editorSelection = C("#2E436E"),
            synKw = C("#CF8E6D"), synType = C("#C77DBB"), synFunc = C("#56A8F5"),
            synStr = C("#6AAB73"), synNum = C("#2AACBD"), synComment = C("#7A7E85"),
            synAnnotation = C("#B3AEAD"), synIdent = C("#DFDFDF"), synOperator = C("#A1A3A7"),
            synTag = C("#F25470"), synAttr = C("#C77DBB"),
            synParam = C("#ABC6FF"), synField = C("#68ABE1"), synConst = C("#FFC66D"),
            synEnum = C("#C77DBB"), synLabel = C("#BBBBBB"), synDoc = C("#7A7E85"),
            synBracket = C("#D4D7DC"), synGeneric = C("#56A8F5"), synImport = C("#A1A3A7"),
            synBoolean = C("#CF8E6D"),
            indicatorError = C("#F75464"), indicatorWarning = C("#D8A762"),
            indicatorModified = C("#548AF7"), indicatorReadonly = C("#6F737A"),
            indicatorFavorite = C("#D8A762"),
            termCommand = C("#56A8F5"), termGradleTask = C("#548AF7"),
            termUrl = C("#548AF7"), termExitCode = C("#57965C"),
            termComment = C("#7A7E85"), termPath = C("#2AACBD"),
            errBg = C("#4A262B"), warnBg = C("#4A3B24"), okBg = C("#2A4032")
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // LIGHT — IntelliJ Light (Android Studio New UI) tokens
    // ═══════════════════════════════════════════════════════════════
    private fun lightPalette(): UiPalette {
        val accent = C("#3574F0")
        return UiPalette(
            isDark = false,
            bg = C("#FFFFFF"), surface = C("#F7F8FA"), surface2 = C("#EEF0F3"),
            card = C("#FFFFFF"), border = C("#DFE1E5"), borderStrong = C("#B9BDC4"),
            divider = C("#EBECF0"), text = C("#1E1F22"), textSoft = C("#6C707E"),
            muted = C("#9EA3AC"),
            accent = accent, onAccent = onAccent(accent),
            success = C("#3E8439"), warning = C("#A8801B"), error = C("#D64550"),
            info = C("#2E6BD0"), selection = C("#D6E4FC"),
            selectedBg = C("#D6E4FC"), disabled = C("#A8ACB3"),
            editorBg = C("#FFFFFF"), editorText = C("#1E1F22"),
            gutterBg = C("#FFFFFF"), gutterText = C("#A8ADB6"), gutterTextActive = C("#37393D"),
            currentLine = blend(accent, C("#FFFFFF"), 0.07f), caret = C("#1E1F22"),
            editorIndentGuide = C("#E4E6EA"), editorSelection = C("#D6E4FC"),
            synKw = C("#0033B3"), synType = C("#7A3E9D"), synFunc = C("#0067C0"),
            synStr = C("#067D17"), synNum = C("#1750EB"), synComment = C("#8C8C8C"),
            synAnnotation = C("#9E880D"), synIdent = C("#1E1F22"), synOperator = C("#1E1F22"),
            synTag = C("#0033B3"), synAttr = C("#D83336"),
            synParam = C("#0067C0"), synField = C("#871094"), synConst = C("#871094"),
            synEnum = C("#7A3E9D"), synLabel = C("#0033B3"), synDoc = C("#8C8C8C"),
            synBracket = C("#1E1F22"), synGeneric = C("#7A3E9D"), synImport = C("#1E1F22"),
            synBoolean = C("#0033B3"),
            indicatorError = C("#D64550"), indicatorWarning = C("#A8801B"),
            indicatorModified = C("#2E6BD0"), indicatorReadonly = C("#8A8E96"),
            indicatorFavorite = C("#D8A762"),
            termCommand = C("#0067C0"), termGradleTask = C("#2E6BD0"),
            termUrl = C("#2E6BD0"), termExitCode = C("#3E8439"),
            termComment = C("#8C8C8C"), termPath = C("#0E7B7B"),
            errBg = C("#FBE9EA"), warnBg = C("#FBF3E2"), okBg = C("#EAF4EC")
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // DRACULA — Pink/Cyan/Green/Yellow/Purple
    // ═══════════════════════════════════════════════════════════════
    private fun draculaDarkPalette(): UiPalette {
        val accent = C("#FF79C6")
        return UiPalette(
            isDark = true,
            bg = C("#282A36"), surface = C("#21222C"), surface2 = C("#44475A"),
            card = C("#21222C"), border = C("#6272A4"), borderStrong = C("#BD93F9"),
            divider = C("#44475A"), text = C("#F8F8F2"), textSoft = C("#6272A4"),
            muted = C("#6272A4"),
            accent = accent, onAccent = Color.WHITE,
            success = C("#50FA7B"), warning = C("#FFB86C"), error = C("#FF5555"),
            info = C("#8BE9FD"), selection = C("#44475A"), selectedBg = C("#44475A"),
            disabled = C("#6272A4"),
            editorBg = C("#282A36"), editorText = C("#F8F8F2"),
            gutterBg = C("#21222C"), gutterText = C("#6272A4"), gutterTextActive = C("#F8F8F2"),
            currentLine = C("#44475A"), caret = C("#FF79C6"),
            editorIndentGuide = C("#44475A"), editorSelection = C("#44475A"),
            synKw = C("#FF79C6"), synType = C("#8BE9FD"), synFunc = C("#50FA7B"),
            synStr = C("#F1FA8C"), synNum = C("#BD93F9"), synComment = C("#6272A4"),
            synAnnotation = C("#FFB86C"), synIdent = C("#F8F8F2"), synOperator = C("#FF79C6"),
            synTag = C("#FF79C6"), synAttr = C("#50FA7B"),
            synParam = C("#F8F8F2"), synField = C("#F8F8F2"), synConst = C("#BD93F9"),
            synEnum = C("#BD93F9"), synLabel = C("#FF79C6"), synDoc = C("#6272A4"),
            synBracket = C("#FF79C6"), synGeneric = C("#8BE9FD"), synImport = C("#8BE9FD"),
            synBoolean = C("#BD93F9"),
            indicatorError = C("#FF5555"), indicatorWarning = C("#FFB86C"),
            indicatorModified = C("#8BE9FD"), indicatorReadonly = C("#6272A4"),
            indicatorFavorite = C("#FFB86C"),
            termCommand = C("#50FA7B"), termGradleTask = C("#8BE9FD"),
            termUrl = C("#8BE9FD"), termExitCode = C("#50FA7B"),
            termComment = C("#6272A4"), termPath = C("#8BE9FD"),
            errBg = C("#44202A"), warnBg = C("#3A3020"), okBg = C("#203A28")
        )
    }

    private fun draculaLightPalette(): UiPalette {
        val accent = C("#BD93F9")
        return UiPalette(
            isDark = false,
            bg = C("#F8F8FF"), surface = C("#FFFFFF"), surface2 = C("#F1F1F4"),
            card = C("#FFFFFF"), border = C("#D6D6E6"), borderStrong = C("#7B7BAA"),
            divider = C("#E8E8F0"), text = C("#282A36"), textSoft = C("#57577A"),
            muted = C("#9595AA"),
            accent = accent, onAccent = Color.WHITE,
            success = C("#2E8B57"), warning = C("#CC7A00"), error = C("#CC3333"),
            info = C("#1565C0"), selection = C("#E8DEFF"), selectedBg = C("#E8DEFF"),
            disabled = C("#C0C0D0"),
            editorBg = C("#FFFFFF"), editorText = C("#282A36"),
            gutterBg = C("#F8F8FF"), gutterText = C("#B0B0C0"), gutterTextActive = C("#57577A"),
            currentLine = C("#F0F0F8"), caret = C("#BD93F9"),
            editorIndentGuide = C("#E0E0EA"), editorSelection = C("#E8DEFF"),
            synKw = C("#A259E0"), synType = C("#1565C0"), synFunc = C("#2E8B57"),
            synStr = C("#B8860B"), synNum = C("#A259E0"), synComment = C("#9595AA"),
            synAnnotation = C("#CC7A00"), synIdent = C("#282A36"), synOperator = C("#CC3333"),
            synTag = C("#CC3333"), synAttr = C("#A259E0"),
            synParam = C("#57577A"), synField = C("#282A36"), synConst = C("#A259E0"),
            synEnum = C("#A259E0"), synLabel = C("#CC3333"), synDoc = C("#9595AA"),
            synBracket = C("#CC3333"), synGeneric = C("#1565C0"), synImport = C("#1565C0"),
            synBoolean = C("#A259E0"),
            indicatorError = C("#CC3333"), indicatorWarning = C("#CC7A00"),
            indicatorModified = C("#1565C0"), indicatorReadonly = C("#9595AA"),
            indicatorFavorite = C("#CC7A00"),
            termCommand = C("#2E8B57"), termGradleTask = C("#1565C0"),
            termUrl = C("#1565C0"), termExitCode = C("#2E8B57"),
            termComment = C("#9595AA"), termPath = C("#1565C0"),
            errBg = C("#FFE8E8"), warnBg = C("#FFF4E0"), okBg = C("#E8F5E9")
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // MONOKAI — Red/Pink/Cyan/Green/Purple/Orange
    // ═══════════════════════════════════════════════════════════════
    private fun monokaiDarkPalette(): UiPalette {
        val accent = C("#FFD866")
        return UiPalette(
            isDark = true,
            bg = C("#2D2A2E"), surface = C("#403E41"), surface2 = C("#5B595C"),
            card = C("#403E41"), border = C("#5B595C"), borderStrong = C("#FC9867"),
            divider = C("#4A484D"), text = C("#FCFCFE"), textSoft = C("#919191"),
            muted = C("#75717E"),
            accent = accent, onAccent = C("#2D2A2E"),
            success = C("#A9DC76"), warning = C("#FC9867"), error = C("#FF6188"),
            info = C("#78DCE8"), selection = C("#5B595C"), selectedBg = C("#5B595C"),
            disabled = C("#5B595C"),
            editorBg = C("#2D2A2E"), editorText = C("#FCFCFE"),
            gutterBg = C("#2D2A2E"), gutterText = C("#5B595C"), gutterTextActive = C("#919191"),
            currentLine = C("#403E41"), caret = C("#A9DC76"),
            editorIndentGuide = C("#4A484D"), editorSelection = C("#5B595C"),
            synKw = C("#FF6188"), synType = C("#A9DC76"), synFunc = C("#66D9EF"),
            synStr = C("#FCFCFA"), synNum = C("#AB9DF2"), synComment = C("#727072"),
            synAnnotation = C("#FC9867"), synIdent = C("#FCFCFE"), synOperator = C("#FF6188"),
            synTag = C("#FF6188"), synAttr = C("#A9DC76"),
            synParam = C("#FCFCFE"), synField = C("#FCFCFE"), synConst = C("#AB9DF2"),
            synEnum = C("#AB9DF2"), synLabel = C("#FF6188"), synDoc = C("#727072"),
            synBracket = C("#FF6188"), synGeneric = C("#66D9EF"), synImport = C("#66D9EF"),
            synBoolean = C("#AB9DF2"),
            indicatorError = C("#FF6188"), indicatorWarning = C("#FC9867"),
            indicatorModified = C("#78DCE8"), indicatorReadonly = C("#75717E"),
            indicatorFavorite = C("#FC9867"),
            termCommand = C("#A9DC76"), termGradleTask = C("#78DCE8"),
            termUrl = C("#78DCE8"), termExitCode = C("#A9DC76"),
            termComment = C("#727072"), termPath = C("#66D9EF"),
            errBg = C("#3D2228"), warnBg = C("#3A3020"), okBg = C("#223A28")
        )
    }

    private fun monokaiLightPalette(): UiPalette {
        val accent = C("#F59E0B")
        return UiPalette(
            isDark = false,
            bg = C("#F8F8F8"), surface = C("#FFFFFF"), surface2 = C("#F0F0F0"),
            card = C("#FFFFFF"), border = C("#D6D6D6"), borderStrong = C("#999999"),
            divider = C("#E0E0E0"), text = C("#2D2A2E"), textSoft = C("#75717E"),
            muted = C("#AAAAAA"),
            accent = accent, onAccent = C("#2D2A2E"),
            success = C("#669A39"), warning = C("#D5840F"), error = C("#E0234E"),
            info = C("#0F7FD5"), selection = C("#D6E8FF"), selectedBg = C("#D6E8FF"),
            disabled = C("#CCCCCC"),
            editorBg = C("#FFFFFF"), editorText = C("#2D2A2E"),
            gutterBg = C("#F8F8F8"), gutterText = C("#CCCCCC"), gutterTextActive = C("#75717E"),
            currentLine = C("#F4F4F4"), caret = C("#F59E0B"),
            editorIndentGuide = C("#E8E8E8"), editorSelection = C("#D6E8FF"),
            synKw = C("#E0234E"), synType = C("#669A39"), synFunc = C("#0F7FD5"),
            synStr = C("#B8860B"), synNum = C("#8E3FC5"), synComment = C("#AAAAAA"),
            synAnnotation = C("#D5840F"), synIdent = C("#2D2A2E"), synOperator = C("#E0234E"),
            synTag = C("#E0234E"), synAttr = C("#669A39"),
            synParam = C("#75717E"), synField = C("#2D2A2E"), synConst = C("#8E3FC5"),
            synEnum = C("#8E3FC5"), synLabel = C("#E0234E"), synDoc = C("#AAAAAA"),
            synBracket = C("#E0234E"), synGeneric = C("#0F7FD5"), synImport = C("#0F7FD5"),
            synBoolean = C("#8E3FC5"),
            indicatorError = C("#E0234E"), indicatorWarning = C("#D5840F"),
            indicatorModified = C("#0F7FD5"), indicatorReadonly = C("#AAAAAA"),
            indicatorFavorite = C("#D5840F"),
            termCommand = C("#669A39"), termGradleTask = C("#0F7FD5"),
            termUrl = C("#0F7FD5"), termExitCode = C("#669A39"),
            termComment = C("#AAAAAA"), termPath = C("#0F7FD5"),
            errBg = C("#FFE8EC"), warnBg = C("#FFF4E0"), okBg = C("#E8F5E9")
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // NORD — Cool blue/teal/green/purple
    // ═══════════════════════════════════════════════════════════════
    private fun nordDarkPalette(): UiPalette {
        val accent = C("#88C0D0")
        return UiPalette(
            isDark = true,
            bg = C("#2E3440"), surface = C("#3B4252"), surface2 = C("#434C5E"),
            card = C("#3B4252"), border = C("#4C566A"), borderStrong = C("#88C0D0"),
            divider = C("#434C5E"), text = C("#ECEFF4"), textSoft = C("#D8DEE9"),
            muted = C("#616E88"),
            accent = accent, onAccent = C("#2E3440"),
            success = C("#A3BE8C"), warning = C("#EBCB8B"), error = C("#BF616A"),
            info = C("#81A1C1"), selection = C("#434C5E"), selectedBg = C("#434C5E"),
            disabled = C("#4C566A"),
            editorBg = C("#2E3440"), editorText = C("#ECEFF4"),
            gutterBg = C("#2E3440"), gutterText = C("#4C566A"), gutterTextActive = C("#D8DEE9"),
            currentLine = C("#3B4252"), caret = C("#88C0D0"),
            editorIndentGuide = C("#3B4252"), editorSelection = C("#434C5E"),
            synKw = C("#81A1C1"), synType = C("#8FBCBB"), synFunc = C("#88C0D0"),
            synStr = C("#A3BE8C"), synNum = C("#B48EAD"), synComment = C("#616E88"),
            synAnnotation = C("#D08770"), synIdent = C("#ECEFF4"), synOperator = C("#81A1C1"),
            synTag = C("#81A1C1"), synAttr = C("#8FBCBB"),
            synParam = C("#D8DEE9"), synField = C("#ECEFF4"), synConst = C("#B48EAD"),
            synEnum = C("#B48EAD"), synLabel = C("#81A1C1"), synDoc = C("#616E88"),
            synBracket = C("#81A1C1"), synGeneric = C("#8FBCBB"), synImport = C("#81A1C1"),
            synBoolean = C("#81A1C1"),
            indicatorError = C("#BF616A"), indicatorWarning = C("#EBCB8B"),
            indicatorModified = C("#88C0D0"), indicatorReadonly = C("#616E88"),
            indicatorFavorite = C("#EBCB8B"),
            termCommand = C("#A3BE8C"), termGradleTask = C("#88C0D0"),
            termUrl = C("#88C0D0"), termExitCode = C("#A3BE8C"),
            termComment = C("#616E88"), termPath = C("#8FBCBB"),
            errBg = C("#3D2A2E"), warnBg = C("#3D3628"), okBg = C("#2A3D2E")
        )
    }

    private fun nordLightPalette(): UiPalette {
        val accent = C("#5E81AC")
        return UiPalette(
            isDark = false,
            bg = C("#ECEFF4"), surface = C("#FFFFFF"), surface2 = C("#E5E9F0"),
            card = C("#FFFFFF"), border = C("#D8DEE9"), borderStrong = C("#4C566A"),
            divider = C("#D8DEE9"), text = C("#2E3440"), textSoft = C("#4C566A"),
            muted = C("#9CA3B4"),
            accent = accent, onAccent = Color.WHITE,
            success = C("#40893F"), warning = C("#C17A0A"), error = C("#BF4040"),
            info = C("#456BA0"), selection = C("#D6DEFF"), selectedBg = C("#D6DEFF"),
            disabled = C("#C0C6D2"),
            editorBg = C("#FFFFFF"), editorText = C("#2E3440"),
            gutterBg = C("#ECEFF4"), gutterText = C("#C0C6D2"), gutterTextActive = C("#4C566A"),
            currentLine = C("#E8ECF2"), caret = C("#5E81AC"),
            editorIndentGuide = C("#D8DEE9"), editorSelection = C("#D6DEFF"),
            synKw = C("#456BA0"), synType = C("#2E8B7A"), synFunc = C("#397E8A"),
            synStr = C("#40893F"), synNum = C("#8E5FA0"), synComment = C("#9CA3B4"),
            synAnnotation = C("#C17A0F"), synIdent = C("#2E3440"), synOperator = C("#BF4040"),
            synTag = C("#BF4040"), synAttr = C("#2E8B7A"),
            synParam = C("#4C566A"), synField = C("#2E3440"), synConst = C("#8E5FA0"),
            synEnum = C("#8E5FA0"), synLabel = C("#BF4040"), synDoc = C("#9CA3B4"),
            synBracket = C("#BF4040"), synGeneric = C("#2E8B7A"), synImport = C("#456BA0"),
            synBoolean = C("#456BA0"),
            indicatorError = C("#BF4040"), indicatorWarning = C("#C17A0A"),
            indicatorModified = C("#456BA0"), indicatorReadonly = C("#9CA3B4"),
            indicatorFavorite = C("#C17A0A"),
            termCommand = C("#40893F"), termGradleTask = C("#456BA0"),
            termUrl = C("#456BA0"), termExitCode = C("#40893F"),
            termComment = C("#9CA3B4"), termPath = C("#2E8B7A"),
            errBg = C("#FFE8E8"), warnBg = C("#FFF4E0"), okBg = C("#E8F5E9")
        )
    }

    private fun C(hex: String): Int = Color.parseColor(hex)

    private fun blend(fg: Int, bg: Int, ratio: Float): Int {
        val r = Color.red(fg) * ratio + Color.red(bg) * (1 - ratio)
        val g = Color.green(fg) * ratio + Color.green(bg) * (1 - ratio)
        val b = Color.blue(fg) * ratio + Color.blue(bg) * (1 - ratio)
        return Color.rgb(r.toInt(), g.toInt(), b.toInt())
    }
}
