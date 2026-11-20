package com.aide.mobilestudio

import android.accounts.AccountManager
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aide.mobilestudio.AppLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.ai.ui.AiWorkspaceActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.File

const val PREFS_NAME = "mobilestudio_settings"

/**
 * Android Studio style Settings dialog.
 *
 * Layout mirrors the reference screenshots: a light window title bar, a left
 * tree with a search box and a help button, a right pane with a breadcrumb
 * header (back / forward / pin), and an OK / Cancel / Apply footer.
 *
 * All data shown on the toolchain pages (JDK, Gradle, SDK, NDK, CMake,
 * devices, Git, logs, models) is read live from the device - nothing is
 * hardcoded. Edits are staged in [draft] and only written to
 * SharedPreferences when Apply or OK is pressed, so Cancel really discards
 * changes.
 */
/**
 * Thin activity wrapper kept for existing callers. The IDE opens this same UI
 * as an in-window overlay through [SettingsController]; the activity remains
 * for launcher/deep-link compatibility only.
 */
class SettingsActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val langCode = AppLanguage.current(newBase).code
        val locale = Locale(langCode)
        val res = newBase.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        val newContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(newContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = SettingsController(this, onClose = { finish() })
        setContent {
            MobileStudioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.bg) {
                    controller.SettingsScreen()
                }
            }
        }
    }
}

internal class SettingsController(
    private val host: ComponentActivity,
    private val onClose: () -> Unit,
    private val initialPageId: String? = null,
) : ContextWrapper(host) {

    private val prefs: SharedPreferences by lazy {
        host.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Pending (not yet applied) edits. Observable so Compose recomposes. */
    private val draft = mutableStateMapOf<String, Any?>()

    private fun toast(msg: String) {
        Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------
    // Staged setting accessors (draft first, then committed prefs)
    // ------------------------------------------------------------
    private fun get(key: String, default: String): String = when (val v = draft[key]) {
        null -> prefs.getString(key, default) ?: default
        else -> v.toString()
    }

    private fun put(key: String, value: String) { draft[key] = value }

    private fun getBool(key: String, default: Boolean): Boolean =
        draft[key] as? Boolean ?: prefs.getBoolean(key, default)

    private fun putBool(key: String, value: Boolean) { draft[key] = value }

    private fun getInt(key: String, default: Int): Int =
        draft[key] as? Int ?: prefs.getInt(key, default)

    private fun putInt(key: String, value: Int) { draft[key] = value }

    /** Committed value only (ignores draft) - used for "modified" detection. */
    private fun prefRaw(key: String): Any? = prefs.all[key]

    private fun isModified(key: String, def: Any): Boolean {
        if (draft.containsKey(key)) return true
        val raw = prefRaw(key) ?: return false
        return raw.toString() != def.toString()
    }

    // ------------------------------------------------------------
    // BuildToolsConfig-backed settings (own prefs file, staged here)
    // ------------------------------------------------------------
    private fun btcBool(key: String, read: () -> Boolean): Boolean =
        draft[BTC_PREFIX + key] as? Boolean ?: read()

    private fun btcInt(key: String, read: () -> Int): Int =
        draft[BTC_PREFIX + key] as? Int ?: read()

    private fun btcStr(key: String, read: () -> String): String =
        draft[BTC_PREFIX + key] as? String ?: read()

    private fun putBtc(key: String, value: Any) { draft[BTC_PREFIX + key] = value }

    private fun commitBtc(key: String, v: Any) {
        when (key) {
            "gradle_heap" -> BuildToolsConfig.setGradleHeapMb(this, v as Int)
            "gradle_metaspace" -> BuildToolsConfig.setGradleMetaspaceMb(this, v as Int)
            "kotlin_heap" -> BuildToolsConfig.setKotlinHeapMb(this, v as Int)
            "kotlin_metaspace" -> BuildToolsConfig.setKotlinMetaspaceMb(this, v as Int)
            "max_workers" -> BuildToolsConfig.setMaxWorkers(this, v as Int)
            "parallel" -> BuildToolsConfig.setParallel(this, v as Boolean)
            "build_cache" -> BuildToolsConfig.setBuildCache(this, v as Boolean)
            "daemon" -> BuildToolsConfig.setDaemon(this, v as Boolean)
            "vfs_watch" -> BuildToolsConfig.setVfsWatch(this, v as Boolean)
            "console" -> BuildToolsConfig.setConsole(this, v.toString())
            "verbose" -> BuildToolsConfig.setGradleVerbose(this, v as Boolean)
            "custom_args" -> BuildToolsConfig.setCustomGradleJvmArgs(this, v.toString())
        }
    }

    private fun commitDraft() {
        if (draft.isEmpty()) return
        // 1. Write to main prefs file
        val editor = prefs.edit()
        for ((k, v) in draft) {
            when {
                k.startsWith(BTC_PREFIX) -> {
                    if (v != null) commitBtc(k.substring(BTC_PREFIX.length), v)
                }
                v is Boolean -> editor.putBoolean(k, v)
                v is Int -> editor.putInt(k, v)
                else -> editor.putString(k, v.toString())
            }
        }
        editor.apply()

        // 2. Sync to ide_options (editor behavior: auto_pair, bracket_match, etc.)
        val ideOpts = host.getSharedPreferences("ide_options", Context.MODE_PRIVATE).edit()
        for ((k, v) in draft) {
            when (k) {
                "auto_pair", "bracket_match" -> {
                    if (v is Boolean) ideOpts.putBoolean(k, v)
                }
                "font_size" -> {
                    if (v is String) {
                        ideOpts.putFloat(k, v.removeSuffix("sp").toFloatOrNull() ?: 14f)
                    }
                }
                "ed_show_line_numbers" -> {
                    if (v is Boolean) ideOpts.putBoolean(k, v)
                }
                "ed_highlight_current" -> {
                    if (v is Boolean) ideOpts.putBoolean(k, v)
                }
                "ed_soft_wrap" -> {
                    if (v is Boolean) ideOpts.putBoolean(k, v)
                }
                "uit_minimap" -> {
                    if (v is Boolean) ideOpts.putBoolean(k, v)
                }
                "uit_breadcrumbs" -> {
                    if (v is Boolean) ideOpts.putBoolean(k, v)
                }
            }
        }
        ideOpts.apply()

        // 3. Sync to ide (build-related settings)
        val idePrefs = host.getSharedPreferences("ide", Context.MODE_PRIVATE).edit()
        for ((k, v) in draft) {
            when (k) {
                "verbose_build_logs" -> {
                    if (v is Boolean) idePrefs.putBoolean(k, v)
                }
                "build_theme" -> {
                    if (v is String) idePrefs.putString(k, v)
                }
                "build_verbosity" -> {
                    if (v is String) idePrefs.putString(k, v)
                }
            }
        }
        idePrefs.apply()

        // 4. Sync to terminal_options (terminal font size)
        val termOpts = host.getSharedPreferences("terminal_options", Context.MODE_PRIVATE).edit()
        for ((k, v) in draft) {
            if (k == "font_size" && v is String) {
                termOpts.putFloat(k, v.removeSuffix("sp").toFloatOrNull() ?: 14f)
            }
        }
        termOpts.apply()

        draft.clear()
    }

    // ------------------------------------------------------------
    // Tree model
    // ------------------------------------------------------------
    private data class SPage(
        val id: String,
        val label: String,
        val children: List<SPage> = emptyList(),
        val desc: String = "",
        val flask: Boolean = false,
    )

    private val pages: List<SPage> = listOf(
        SPage("appearance_behavior", "Appearance & Behavior",
            desc = "Configure the look and feel of the IDE, notifications, and system behavior",
            children = listOf(
                SPage("appearance", "Appearance"),
                SPage("menus_toolbars", "Menus and Toolbars"),
                SPage("system_settings", "System Settings"),
                SPage("file_colors", "File Colors"),
                SPage("scopes", "Scopes"),
                SPage("notifications", "Notifications"),
                SPage("data_editor", "Data Editor and Viewer"),
                SPage("quick_lists", "Quick Lists"),
                SPage("required_plugins", "Required Plugins"),
                SPage("trusted_locations", "Trusted Locations"),
                SPage("path_variables", "Path Variables"),
                SPage("presentation_assistant", "Presentation Assistant"),
            )),
        SPage("keymap", "Keymap"),
        SPage("editor", "Editor",
            desc = "Customize the editor behavior, code style, and appearance",
            children = listOf(
                SPage("ed_general", "General"),
                SPage("ed_code_editing", "Code Editing"),
                SPage("ed_font", "Font"),
                SPage("ed_color_scheme", "Color Scheme"),
                SPage("ed_code_style", "Code Style"),
                SPage("ed_inspections", "Inspections"),
                SPage("ed_file_templates", "File and Code Templates"),
                SPage("ed_encodings", "File Encodings"),
                SPage("ed_live_templates", "Live Templates"),
                SPage("ed_file_types", "File Types"),
                SPage("ed_ui_tools", "UI Tools"),
                SPage("ed_copyright", "Copyright"),
                SPage("ed_inlay_hints", "Inlay Hints"),
                SPage("ed_emmet", "Emmet"),
                SPage("ed_intentions", "Intentions"),
                SPage("ed_language_injections", "Language Injections"),
                SPage("ed_live_edit", "Live Edit"),
                SPage("ed_natural_languages", "Natural Languages"),
            )),
        SPage("plugins", "Plugins"),
        SPage("version_control", "Version Control",
            desc = "Configure the settings related to version control used in your project",
            children = listOf(
                SPage("vc_changelists", "Changelists"),
                SPage("vc_commit", "Commit"),
                SPage("vc_confirmation", "Confirmation"),
                SPage("vc_directory_mappings", "Directory Mappings"),
                SPage("vc_file_status_colors", "File Status Colors"),
                SPage("vc_issue_navigation", "Issue Navigation"),
                SPage("vc_shelf", "Shelf"),
                SPage("vc_git", "Git"),
                SPage("vc_github", "GitHub"),
                SPage("vc_gitlab", "GitLab"),
                SPage("vc_mercurial", "Mercurial"),
                SPage("vc_perforce", "Perforce"),
                SPage("vc_subversion", "Subversion"),
            )),
        SPage("build_deploy", "Build, Execution, Deployment",
            desc = "Configure project execution settings, deployment options, and the debugger behavior",
            children = listOf(
                SPage("b_tools", "Build Tools"),
                SPage("b_coverage", "Coverage"),
                SPage("b_debugger", "Debugger"),
                SPage("b_deployment", "Deployment"),
                SPage("b_gradle_android_compiler", "Gradle-Android Compiler"),
                SPage("b_remote_repos", "Remote Jar Repositories"),
            )),
        SPage("languages_frameworks", "Languages & Frameworks",
            desc = "Configure language-specific settings, code insight, and formatting",
            children = listOf(
                SPage("l_cpp", "C/C++",
                    desc = "Configure the settings related to C/C++ code insight, formatting, and language support",
                    children = listOf(
                        SPage("clang_tidy", "Clang-Tidy"),
                        SPage("clangd", "Clangd"),
                        SPage("cling", "Cling"),
                    )),
                SPage("l_android_sdk", "Android SDK"),
                SPage("l_jvm_logging", "JVM Logging"),
                SPage("l_kotlin", "Kotlin",
                    children = listOf(
                        SPage("kotlin_scripting", "Kotlin Scripting"),
                    )),
                SPage("l_markdown", "Markdown"),
                SPage("l_mermaid", "Mermaid"),
                SPage("l_schemas", "Schemas and DTDs",
                    desc = "Manage external schemas and DTDs used for XML validation",
                    children = listOf(
                        SPage("schemas_external", "External Schemas and DTDs"),
                        SPage("schemas_ignored", "Ignored Schemas"),
                    )),
                SPage("l_template_data", "Template Data Languages"),
            )),
        SPage("tools", "Tools",
            desc = "Configure tools integrated with the IDE",
            children = listOf(
                SPage("actions_on_save", "Actions on Save"),
                SPage("ai", "AI"),
                SPage("app_links", "App Links Assistant"),
                SPage("csv_formats", "CSV Formats"),
                SPage("database_inspector", "Database Inspector"),
                SPage("device_explorer", "Device Explorer"),
                SPage("device_mirroring", "Device Mirroring"),
                SPage("diff_merge", "Diff & Merge"),
                SPage("emulator", "Emulator"),
                SPage("external_tools", "External Tools"),
                SPage("google_accounts", "Google Accounts"),
                SPage("gradle_decl_focus", "Gradle Declarative Focus"),
                SPage("layout_inspector", "Layout Inspector"),
                SPage("logcat", "Logcat"),
                SPage("screenshots_recordings", "Screenshots & Screen Recordings"),
                SPage("startup_tasks", "Startup Tasks"),
                SPage("terminal", "Terminal"),
            )),
        SPage("backup_sync", "Backup and Sync"),
        SPage("advanced", "Advanced Settings"),
        SPage("other_settings", "Other Settings",
            desc = "Additional settings that do not fit into other categories",
            children = listOf(
                SPage("experimental", "Experimental"),
                SPage("studio_labs", "Studio Labs", flask = true),
            )),
    )

    private fun findIn(list: List<SPage>, id: String): SPage? {
        for (p in list) {
            if (p.id == id) return p
            findIn(p.children, id)?.let { return it }
        }
        return null
    }

    private fun findPage(id: String): SPage? = findIn(pages, id)

    private fun findPathIn(list: List<SPage>, id: String, prefix: List<String>): List<String>? {
        for (p in list) {
            val cur = prefix + p.id
            if (p.id == id) return cur
            findPathIn(p.children, id, cur)?.let { return it }
        }
        return null
    }

    private fun findPath(id: String): List<String>? = findPathIn(pages, id, emptyList())

    private fun findFirstPinned(list: List<SPage>): String? {
        for (p in list) {
            if (getBool("pinned_${p.id}", false)) return p.id
            findFirstPinned(p.children)?.let { return it }
        }
        return null
    }

    private fun initialPath(): List<String> {
        findFirstPinned(pages)?.let { findPath(it)?.let { p -> return p } }
        prefs.getString(KEY_LAST_PAGE, null)?.let { findPath(it)?.let { p -> return p } }
        return listOf("appearance_behavior", "appearance")
    }

    private data class FlatRow(
        val page: SPage,
        val depth: Int,
        val selected: Boolean,
        val hasKids: Boolean,
        val isOpen: Boolean,
        val pinned: Boolean,
    )

    private fun buildFlat(query: String, path: List<String>, expanded: Set<String>): List<FlatRow> {
        val q = query.trim().lowercase()
        val out = mutableListOf<FlatRow>()
        fun matches(p: SPage): Boolean =
            q.isEmpty() || p.label.lowercase().contains(q) || p.children.any { matches(it) }
        fun walk(p: SPage, depth: Int) {
            if (!matches(p)) return
            val isOpen = q.isNotEmpty() || p.id in expanded || path.dropLast(1).contains(p.id)
            out += FlatRow(
                page = p,
                depth = depth,
                selected = p.id == path.lastOrNull(),
                hasKids = p.children.isNotEmpty(),
                isOpen = isOpen,
                pinned = getBool("pinned_${p.id}", false),
            )
            if (isOpen) p.children.forEach { walk(it, depth + 1) }
        }
        pages.forEach { walk(it, 0) }
        return out
    }

    // ------------------------------------------------------------
    // Real data: menu actions and shortcuts (mirrors the menu bar)
    // ------------------------------------------------------------
    private data class KeyGroup(val menu: String, val actions: List<Pair<String, String>>)

    private val keymapGroups: List<KeyGroup> = listOf(
        KeyGroup("Files", listOf(
            "New Project..." to "",
            "New File..." to "",
            "New Folder..." to "",
            "Open Project..." to "",
            "Save" to "Ctrl+S",
            "Save All" to "",
            "Close Tab" to "Ctrl+W",
            "Exit" to "",
        )),
        KeyGroup("Edit", listOf(
            "Undo" to "Ctrl+Z",
            "Redo" to "Ctrl+Shift+Z",
            "Cut" to "Ctrl+X",
            "Copy" to "Ctrl+C",
            "Paste" to "Ctrl+V",
            "Select All" to "Ctrl+A",
            "Find..." to "Ctrl+F",
            "Find/Replace..." to "",
            "Find in Files..." to "",
            "Close All Tabs" to "",
        )),
        KeyGroup("View", listOf(
            "Editor" to "",
            "Build Log" to "",
            "Terminal" to "",
            "Project Files Drawer" to "",
        )),
        KeyGroup("Move", listOf(
            "Next Tab" to "Ctrl+Tab",
            "Previous Tab" to "Ctrl+Shift+Tab",
            "Go to Line..." to "Ctrl+G",
        )),
        KeyGroup("Code", listOf(
            "Comment / Uncomment" to "Ctrl+/",
            "Reformat Code" to "Ctrl+Alt+L",
            "Optimize Imports" to "Ctrl+Alt+O",
        )),
        KeyGroup("Run", listOf(
            "Run 'app'" to "Shift+F10",
        )),
        KeyGroup("Tools", listOf(
            "Terminal" to "Alt+F12",
            "AI Assistant" to "",
            "Device Manager" to "",
            "SDK Manager" to "",
            "Resource Manager" to "",
            "AGP Upgrade Assistant..." to "",
            "Android SDK Upgrade Assistant" to "",
            "Troubleshoot Device Connections" to "",
            "App Links Assistant" to "",
            "Firebase" to "",
            "Layout Inspector" to "",
            "Generate Javadoc..." to "",
            "Create Command Line Launcher..." to "",
            "Services" to "",
            "Markdown" to "",
            "XML Actions" to "",
            "Cling" to "",
            "Groovy Console" to "",
            "Kotlin" to "",
            "Git" to "",
            "File Manager" to "",
            "Download Manager" to "",
        )),
        KeyGroup("IDE", listOf(
            "Settings..." to "",
            "Recent Projects" to "",
            "Recent Files" to "",
            "About MobileStudio" to "",
        )),
    )

    // ------------------------------------------------------------
    // Screen shell
    // ------------------------------------------------------------
    @Composable
    internal fun SettingsScreen() {
        val startPath = remember { initialPageId?.let { findPath(it) ?: initialPath() } ?: initialPath() }
        var path by remember { mutableStateOf(startPath) }
        var expanded by remember {
            mutableStateOf(setOf("appearance_behavior", "languages_frameworks") + startPath.dropLast(1))
        }
        var query by remember { mutableStateOf("") }
        var backStack by remember {
            mutableStateOf<List<List<String>>>(listOf(startPath))
        }
        var idx by remember { mutableStateOf(0) }
        var pinTick by remember { mutableStateOf(0) }

        fun navTo(target: List<String>) {
            if (target.isEmpty() || target == path) return
            path = target
            expanded = expanded + target.dropLast(1)
            val stack: List<List<String>> = backStack.take(idx + 1) + listOf(target)
            backStack = stack
            idx = stack.lastIndex
        }

        fun goBack() {
            if (idx > 0) {
                idx--
                path = backStack[idx]
                expanded = expanded + path.dropLast(1)
            }
        }

        fun goForward() {
            if (idx < backStack.lastIndex) {
                idx++
                path = backStack[idx]
                expanded = expanded + path.dropLast(1)
            }
        }

        val treeRows = remember(query, path, expanded, pinTick) { buildFlat(query, path, expanded) }
        val dirty = draft.isNotEmpty()
        val currentId = path.lastOrNull() ?: ""
        val current = findPage(currentId)

        Column(Modifier.fillMaxSize().background(Palette.bg)) {
            TitleBar(onClose = onClose)
            Row(Modifier.weight(1f).fillMaxWidth()) {

                // ---------------- left: search + tree + help ----------------
                Column(Modifier.weight(1f).fillMaxHeight().background(Palette.panel)) {
                    SearchBox(query, placeholder = "Search settings") { query = it }
                    HLine()
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 4.dp)
                    ) {
                        treeRows.forEach { r ->
                            TreeRow(
                                row = r,
                                onSelect = { findPath(r.page.id)?.let { navTo(it) } },
                                onChevron = {
                                    expanded = if (r.page.id in expanded) expanded - r.page.id
                                    else expanded + r.page.id
                                },
                            )
                        }
                        if (treeRows.isEmpty()) {
                            Text(
                                "No matching settings",
                                color = Palette.dimText,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    HLine()
                    HelpRow { showHelpDialog(current) }
                }

                Box(Modifier.fillMaxHeight().width(1.dp).background(Palette.divider))

                // ---------------- right: header + content + footer ----------------
                Column(Modifier.weight(1.35f).fillMaxHeight().background(Palette.bg)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Breadcrumb(path)
                        Spacer(Modifier.weight(1f))
                        NavGlyph("←", enabled = idx > 0, onClick = { goBack() })
                        NavGlyph("→", enabled = idx < backStack.lastIndex, onClick = { goForward() })
                        if (currentId.isNotEmpty()) {
                            val pinKey = "pinned_$currentId"
                            val pinned = getBool(pinKey, false)
                            Box(
                                Modifier
                                    .size(30.dp)
                                    .then(
                                        if (pinned) Modifier.background(
                                            Palette.fieldBg,
                                            RoundedCornerShape(4.dp),
                                        ) else Modifier
                                    )
                                    .clickable {
                                        putBool(pinKey, !pinned)
                                        pinTick++
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "📌",
                                    fontSize = 13.sp,
                                    color = if (pinned) Palette.link else Palette.dimText,
                                )
                            }
                        }
                    }
                    HLine()
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)
                    ) {
                        PageBody(path) { navTo(it) }
                    }
                    HLine()
                    Footer(
                        dirty = dirty,
                        onOk = {
                            put(KEY_LAST_PAGE, currentId)
                            commitDraft()
                            onClose()
                        },
                        onCancel = {
                            draft.clear()
                            onClose()
                        },
                        onApply = {
                            put(KEY_LAST_PAGE, currentId)
                            commitDraft()
                            toast("Settings applied")
                        },
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------
    // Chrome components
    // ------------------------------------------------------------
    @Composable
    private fun TitleBar(onClose: () -> Unit) {
        Box(Modifier.fillMaxWidth().height(36.dp).background(Palette.titleBar)) {
            Text(
                "Settings – MobileStudio",
                color = Palette.titleText,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center),
            )
            Row(
                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WindowGlyph("✕", onClick = onClose)
            }
        }
    }

    @Composable
    private fun WindowGlyph(glyph: String, onClick: () -> Unit) {
        Box(
            Modifier.width(34.dp).fillMaxHeight().clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = Palette.titleText, fontSize = 13.sp)
        }
    }

    @Composable
    private fun SearchBox(
        query: String,
        placeholder: String = "",
        onChange: (String) -> Unit,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .background(Palette.fieldBg)
                .border(1.dp, Palette.border, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🔍", fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        placeholder,
                        color = Palette.dimText,
                        fontSize = 14.sp,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 14.sp),
                    cursorBrush = SolidColor(Palette.link),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    @Composable
    private fun TreeRow(row: FlatRow, onSelect: () -> Unit, onChevron: () -> Unit) {
        val selected = row.selected
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (selected) Palette.selection else Color.Transparent)
                .clickable { onSelect() }
                .padding(start = (6 + 16 * row.depth).dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.hasKids) {
                Box(
                    Modifier.width(18.dp).clickable { onChevron() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (row.isOpen) "▾" else "▸",
                        color = Palette.text,
                        fontSize = 11.sp,
                    )
                }
            } else {
                Spacer(Modifier.width(18.dp))
            }
            Text(
                row.page.label,
                color = if (selected) Color.White else Palette.text,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (row.pinned) Text("📌", fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
            if (row.depth > 0) {
                Text("▭", color = Palette.dimText, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
            }
            if (row.page.flask) Text("🧪", fontSize = 11.sp)
        }
    }

    @Composable
    private fun HelpRow(onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Box(
                Modifier
                    .size(26.dp)
                    .border(1.dp, Palette.border, RoundedCornerShape(13.dp))
                    .clickable { onClick() },
                contentAlignment = Alignment.Center,
            ) {
                Text("?", color = Palette.text, fontSize = 13.sp)
            }
        }
    }

    @Composable
    private fun NavGlyph(glyph: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            Modifier
                .size(30.dp)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                fontSize = 16.sp,
                color = if (enabled) Palette.text else Palette.disabledText,
            )
        }
    }

    @Composable
    private fun Breadcrumb(path: List<String>) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            path.forEachIndexed { i, id ->
                if (i > 0) {
                    Text(
                        "  ›  ",
                        color = Palette.dimText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    findPage(id)?.label ?: id,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
    }

    @Composable
    private fun Footer(dirty: Boolean, onOk: () -> Unit, onCancel: () -> Unit, onApply: () -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onOk,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.okBlue,
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("OK", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            OutlinedButton(
                onClick = onCancel,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.text),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("Cancel", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
            Button(
                onClick = onApply,
                enabled = dirty,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.okBlue,
                    contentColor = Color.White,
                    disabledContainerColor = Palette.fieldBg,
                    disabledContentColor = Palette.disabledText,
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("Apply", fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
    }

    // ------------------------------------------------------------
    // Page body dispatch
    // ------------------------------------------------------------
    @Composable
    private fun PageBody(path: List<String>, navTo: (List<String>) -> Unit) {
        val id = path.lastOrNull() ?: return
        val page = findPage(id) ?: run {
            Text("Page not found.", color = Palette.dimText, fontSize = 14.sp)
            return
        }
        val go: (String) -> Unit = { target -> findPath(target)?.let { navTo(it) } }
        when (id) {
            "appearance" -> AppearancePage(go)
            "system_settings" -> SystemInfoPage()
            "ed_font" -> FontPage()
            "ed_color_scheme" -> ColorSchemePage()
            "keymap" -> KeymapPage()
            "plugins" -> PluginsPage()
            "vc_git" -> GitPage()
            "b_tools" -> BuildToolsPage()
            "clang_tidy" -> NativeToolPage("Clang-Tidy", "clang-tidy")
            "clangd" -> NativeToolPage("Clangd", "clangd")
            "cling" -> NativeToolPage("Cling", "cling")
            "l_android_sdk" -> AndroidSdkPage()
            "l_jvm_logging" -> JvmLoggingPage()
            "kotlin_scripting" -> KotlinScriptingPage()
            "l_markdown" -> MarkdownPage()
            "ai" -> AiPage()
            "device_explorer" -> DevicesPage()
            "device_mirroring" -> DevicesPage()
            "advanced" -> AdvancedPage()
            "backup_sync" -> BackupSyncPage()
            "l_schemas" -> SchemasPage()
            "schemas_external" -> ListEditorPage(
                "schemas_external",
                "External schemas and DTDs:",
                "No external resources",
                "Schema location or URL",
            )
            "schemas_ignored" -> ListEditorPage(
                "schemas_ignored",
                "Ignored schemas and DTDs:",
                "Schemas listed here are not used for XML validation.",
                "Schema location or URL",
            )
            "l_template_data" -> ListEditorPage(
                "template_data_mappings",
                "Path mappings:",
                "Template data languages are the underlying languages in template files like those of " +
                    "FreeMarker/Velocity frameworks. To change template data language settings for a " +
                    "file, a directory, or the entire project, add its path if necessary and then " +
                    "select a language from the drop-down list.",
                "Path — language",
            )
            "external_tools" -> ListEditorPage(
                "external_tools",
                "External tools:",
                "Registered external tools appear in the Tools menu.",
                "Tool name — command",
            )
            "startup_tasks" -> ListEditorPage(
                "startup_tasks",
                "Startup tasks:",
                "Tasks that run when MobileStudio starts.",
                "Task name",
            )
            else -> if (page.children.isNotEmpty()) LinksBody(page, go)
            else FormBody(rowsFor(id), go)
        }
    }

    @Composable
    private fun LinksBody(page: SPage, go: (String) -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            if (page.desc.isNotBlank()) {
                Text(
                    page.desc,
                    color = Palette.desc,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            page.children.forEach { child ->
                Text(
                    child.label,
                    color = Palette.link,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { go(child.id) }
                        .padding(vertical = 5.dp),
                )
            }
        }
    }

    // ------------------------------------------------------------
    // Generic form rows
    // ------------------------------------------------------------
    private sealed class RowItem {
        data class Sec(val label: String) : RowItem()
        data class Chk(
            val key: String,
            val label: String,
            val desc: String = "",
            val def: Boolean = false,
        ) : RowItem()
        data class Txt(
            val key: String,
            val label: String,
            val def: String = "",
            val numeric: Boolean = false,
            val desc: String = "",
        ) : RowItem()
        data class Cbo(
            val key: String,
            val label: String,
            val options: List<String>,
            val def: String,
            val desc: String = "",
        ) : RowItem()
        data class Lnk(val target: String, val label: String) : RowItem()
        data class Nte(val text: String) : RowItem()
        data class Act(val label: String, val action: () -> Unit) : RowItem()
    }

    @Composable
    private fun FormBody(rows: List<RowItem>, go: (String) -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            rows.forEach { r ->
                when (r) {
                    is RowItem.Sec -> SectionRow(r.label)
                    is RowItem.Chk -> CheckRow(
                        checked = getBool(r.key, r.def),
                        label = r.label,
                        desc = r.desc,
                    ) { putBool(r.key, it) }
                    is RowItem.Txt -> FieldRow(
                        label = r.label,
                        value = get(r.key, r.def),
                        numeric = r.numeric,
                        desc = r.desc,
                    ) { put(r.key, it) }
                    is RowItem.Cbo -> ComboRow(
                        label = r.label,
                        options = r.options,
                        value = get(r.key, r.def),
                        desc = r.desc,
                    ) { put(r.key, it) }
                    is RowItem.Lnk -> LinkRow(r.label) { go(r.target) }
                    is RowItem.Nte -> NoteRow(r.text)
                    is RowItem.Act -> ActionRow(r.label) { r.action() }
                }
            }
        }
    }

    @Composable
    private fun SectionRow(label: String) {
        Row(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Palette.divider))
        }
    }

    @Composable
    private fun CheckRow(
        checked: Boolean,
        label: String,
        desc: String,
        onChange: (Boolean) -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = Palette.link,
                    uncheckedColor = Palette.border,
                    checkmarkColor = Color.White,
                ),
                modifier = Modifier.padding(top = 1.dp),
            )
            Column(Modifier.padding(start = 6.dp)) {
                Text(label, color = Palette.text, fontSize = 14.sp, lineHeight = 18.sp)
                if (desc.isNotBlank()) {
                    Text(
                        desc,
                        color = Palette.dimText,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun FieldRow(
        label: String,
        value: String,
        numeric: Boolean,
        desc: String,
        onChange: (String) -> Unit,
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    label,
                    color = Palette.text,
                    fontSize = 14.sp,
                    maxLines = 3,
                )
                Box(
                    Modifier
                        .width(150.dp)
                        .background(Palette.fieldBg)
                        .border(1.dp, Palette.border, RoundedCornerShape(4.dp)),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        singleLine = true,
                        keyboardOptions = if (numeric) {
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        } else KeyboardOptions.Default,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(Palette.link),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
            }
            if (desc.isNotBlank()) {
                Text(
                    desc,
                    color = Palette.dimText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun NumberRow(label: String, value: Int, suffix: String = "", onChange: (Int) -> Unit) {
        var raw by remember(value) { mutableStateOf(value.toString()) }
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label,
                color = Palette.text,
                fontSize = 14.sp,
                maxLines = 4,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .width(86.dp)
                        .background(Palette.fieldBg)
                        .border(1.dp, Palette.border, RoundedCornerShape(4.dp)),
                ) {
                    BasicTextField(
                        value = raw,
                        onValueChange = { s ->
                            raw = s
                            s.filter { it.isDigit() }.toIntOrNull()?.let(onChange)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(Palette.link),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
                if (suffix.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(suffix, color = Palette.dimText, fontSize = 12.sp)
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun ComboRow(
        label: String,
        options: List<String>,
        value: String,
        desc: String,
        onChange: (String) -> Unit,
    ) {
        var open by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    label,
                    color = Palette.text,
                    fontSize = 14.sp,
                    maxLines = 3,
                )
                Box {
                    Row(
                        Modifier
                            .background(Palette.fieldBg)
                            .border(1.dp, Palette.border, RoundedCornerShape(4.dp))
                            .clickable { open = true }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            value,
                            color = Color.White,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("▾", color = Palette.dimText, fontSize = 10.sp)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        options.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt, fontSize = 13.sp) },
                                onClick = {
                                    onChange(opt)
                                    open = false
                                },
                            )
                        }
                    }
                }
            }
            if (desc.isNotBlank()) {
                Text(
                    desc,
                    color = Palette.dimText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }

    @Composable
    private fun NoteRow(text: String, color: Color = Palette.dimText) {
        Text(
            text,
            color = color,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        )
    }

    @Composable
    private fun LinkRow(label: String, onClick: () -> Unit) {
        Text(
            label,
            color = Palette.link,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 5.dp),
        )
    }

    @Composable
    private fun ActionRow(label: String, onClick: () -> Unit) {
        Text(
            label,
            color = Palette.link,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 7.dp),
        )
    }

    @Composable
    private fun HLine() {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.divider))
    }

    @Composable
    private fun Collapsible(title: String, content: @Composable ColumnScope.() -> Unit) {
        var open by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (open) "▾" else "▸", color = Palette.dimText, fontSize = 11.sp)
            Spacer(Modifier.width(6.dp))
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f).height(1.dp).background(Palette.divider))
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp), content = content)
        }
    }

    // ------------------------------------------------------------
    // Appearance & Behavior > Appearance
    // ------------------------------------------------------------
    @Composable
    private fun AppearancePage(go: (String) -> Unit) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "Configure the appearance of the IDE.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            SectionRow("Theme")
            ComboRow(
                label = "Theme:",
                options = listOf("Dark", "Light", "Dracula", "Monokai", "Nord", "One Dark Pro"),
                value = get("theme", "Dark"),
                desc = "Takes effect after restarting the app.",
            ) { put("theme", it) }
            ComboRow(
                label = "Color scheme:",
                options = listOf("Darcula", "Dark", "Light"),
                value = get("color_scheme", "Darcula"),
                desc = "",
            ) { put("color_scheme", it) }
            SectionRow("Editor")
            LinkRow("Font") { go("ed_font") }
            LinkRow("Color Scheme") { go("ed_color_scheme") }
            SectionRow("System")
            LinkRow("System Settings") { go("system_settings") }
            LinkRow("Notifications") { go("notifications") }
            SectionRow("Language")
            val languages = AppLanguage.supported
            val currentCode = AppLanguage.current(host).code
            ComboRow(
                label = "Language:",
                options = languages.map { it.nativeName },
                value = languages.first { it.code == currentCode }.nativeName,
                desc = "Changes take effect immediately.",
            ) { selectedName ->
                val selected = languages.first { it.nativeName == selectedName }
                if (selected.code != currentCode) {
                    AppLanguage.set(host, selected.code)
                    (host as SettingsActivity).recreate()
                }
            }
        }
    }

    // ------------------------------------------------------------
    // Appearance & Behavior > System Settings (real device data)
    // ------------------------------------------------------------
    @Composable
    private fun SystemInfoPage() {
        val version = remember {
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" }
                .getOrDefault("unknown")
        }
        val abi = remember { Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown" }
        val heapMb = remember { Runtime.getRuntime().maxMemory() / (1024 * 1024) }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Environment information reported by this device.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            SectionRow("Application")
            InfoRow("Version", version)
            InfoRow("Package", packageName)
            SectionRow("Device")
            InfoRow("Manufacturer", Build.MANUFACTURER ?: "unknown")
            InfoRow("Model", Build.MODEL ?: "unknown")
            InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow("Primary ABI", abi)
            SectionRow("Runtime")
            InfoRow("Max heap", "$heapMb MB")
            InfoRow("Files directory", filesDir.absolutePath)
            InfoRow("Cache directory", cacheDir.absolutePath)
            InfoRow("Toolchain", File(filesDir, "toolchain").absolutePath)
            InfoRow("Gradle user home", File(filesDir, ".gradle").absolutePath)
            InfoRow("AI models", File(filesDir, "AI/models").absolutePath)
        }
    }

    @Composable
    private fun InfoRow(label: String, value: String) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                label,
                color = Palette.text,
                fontSize = 14.sp,
                modifier = Modifier.width(132.dp),
            )
            Text(
                value,
                color = Palette.dimText,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp,
                modifier = Modifier.weight(1f),
            )
        }
    }

    // ------------------------------------------------------------
    // Editor > Font
    // ------------------------------------------------------------
    @Composable
    private fun FontPage() {
        val family = get("font_family", "Monospace")
        val sizeSp = get("font_size", "14sp").removeSuffix("sp").toIntOrNull() ?: 14
        Column(Modifier.fillMaxWidth()) {
            SectionRow("Editor font")
            ComboRow(
                label = "Font family:",
                options = listOf("Monospace", "Sans-serif", "Serif", "System Default"),
                value = family,
                desc = "",
            ) { put("font_family", it) }
            ComboRow(
                label = "Font size:",
                options = listOf("10sp", "12sp", "14sp", "16sp", "18sp", "20sp", "24sp"),
                value = "${sizeSp}sp",
                desc = "Used by the code editor.",
            ) { put("font_size", it) }
            SectionRow("Preview")
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.fieldBg)
                    .border(1.dp, Palette.border, RoundedCornerShape(4.dp))
                    .padding(12.dp),
            ) {
                Text(
                    "class SettingsActivity : ComponentActivity() {\n" +
                        "    private val prefs = \"mobilestudio_settings\"\n" +
                        "}",
                    color = Color(0xFFA9B7C6),
                    fontSize = sizeSp.sp,
                    fontFamily = when (family) {
                        "Sans-serif" -> FontFamily.SansSerif
                        "Serif" -> FontFamily.Serif
                        else -> FontFamily.Monospace
                    },
                    lineHeight = (sizeSp + 4).sp,
                )
            }
        }
    }

    // ------------------------------------------------------------
    // Editor > Color Scheme
    // ------------------------------------------------------------
    @Composable
    private fun ColorSchemePage() {
        val scheme = get("color_scheme", "Darcula")
        val light = scheme == "Light"
        val keyword = if (light) Color(0xFF0000CC) else Color(0xFFCC7832)
        val string = if (light) Color(0xFF008000) else Color(0xFF6A8759)
        val text = if (light) Color(0xFF000000) else Color(0xFFA9B7C6)
        val bg = if (light) Color(0xFFFFFFFF) else Color(0xFF2B2B2B)

        Column(Modifier.fillMaxWidth()) {
            SectionRow("Color scheme")
            ComboRow(
                label = "Scheme:",
                options = listOf("Darcula", "Dark", "Light"),
                value = scheme,
                desc = "Applied to the editor highlighting.",
            ) { put("color_scheme", it) }
            SectionRow("Preview")
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(bg)
                    .border(1.dp, Palette.border, RoundedCornerShape(4.dp))
                    .padding(12.dp),
            ) {
                Column {
                    Text("class Greeter {", color = keyword, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "    val name = \"MobileStudio\"",
                        color = string,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text("}", color = keyword, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        "    // plain comment",
                        color = text,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------
    // Keymap (real menu actions and shortcuts)
    // ------------------------------------------------------------
    @Composable
    private fun KeymapPage() {
        var q by remember { mutableStateOf("") }
        val query = q.trim().lowercase()
        val groups = remember(query) {
            keymapGroups.map { g ->
                g to g.actions.filter {
                    query.isEmpty() ||
                        it.first.lowercase().contains(query) ||
                        g.menu.lowercase().contains(query)
                }
            }.filter { it.second.isNotEmpty() }
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Actions from the menu bar and their keyboard shortcuts.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            SearchBox(q, placeholder = "Search actions") { q = it }
            groups.forEach { (group, actions) ->
                SectionRow(group.menu)
                actions.forEach { (name, shortcut) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            name,
                            color = Palette.text,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            shortcut.ifBlank { "—" },
                            color = Palette.dimText,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            if (groups.isEmpty()) {
                NoteRow("No matching actions.")
            }
        }
    }

    // ------------------------------------------------------------
    // Plugins (real build info)
    // ------------------------------------------------------------
    @Composable
    private fun PluginsPage() {
        val version = remember {
            runCatching { packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown" }
                .getOrDefault("unknown")
        }
        val components = remember {
            listOf(
                "Kotlin" to "Language support and compiler integration",
                "Markdown" to "Markdown editing and preview",
                "Mermaid" to "Mermaid diagram rendering",
                "Git" to "Version control integration",
                "Terminal" to "Embedded shell",
                "Logcat" to "Device log viewer",
                "Layout Inspector" to "View hierarchy inspection",
                "Compose Preview" to "Compose @Preview rendering",
                "Diff Viewer" to "Side-by-side file comparison",
                "APK Editor" to "Re-sign, split, merge, and inspect APKs",
                "Archive Viewer" to "Browse .zip and .tar.gz archives",
                "Gradle" to "Gradle build integration",
                "Android SDK tools" to "SDK, NDK, and CMake management",
                "llama.cpp AI" to "Local AI assistant (llama.cpp engine)",
            )
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Components bundled with this build of MobileStudio.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            SectionRow("Bundled components (${components.size})")
            components.forEach { (name, desc) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(desc, color = Palette.dimText, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "bundled",
                        color = Palette.dimText,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .background(Palette.fieldBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            SectionRow("About")
            InfoRow("Version", version)
            InfoRow("Package", packageName)
        }
    }

    // ------------------------------------------------------------
    // Version Control > Git (real executable detection)
    // ------------------------------------------------------------
    @Composable
    private fun GitPage() {
        var tick by remember { mutableStateOf(0) }
        var foundPath by remember { mutableStateOf<String?>(null) }
        var version by remember { mutableStateOf("") }
        var checked by remember { mutableStateOf(false) }

        LaunchedEffect(tick) {
            checked = false
            withContext(Dispatchers.IO) {
                val candidates = listOf(
                    File(filesDir, "toolchain/git/bin/git"),
                    File(filesDir, "files/usr/bin/git"),
                    File("/system/bin/git"),
                    File("/system/xbin/git"),
                    File("/data/data/com.termux/files/usr/bin/git"),
                )
                var found: File? = candidates.firstOrNull { it.isFile && it.canExecute() }
                if (found == null) {
                    val pathDirs = (System.getenv("PATH") ?: "").split(":").filter { it.isNotBlank() }
                    found = pathDirs.firstNotNullOfOrNull { d ->
                        File(d, "git").takeIf { it.isFile && it.canExecute() }
                    }
                }
                if (found != null) {
                    foundPath = found.absolutePath
                    version = runCatching {
                        val p = ProcessBuilder(found.absolutePath, "--version")
                            .redirectErrorStream(true)
                            .start()
                        val out = p.inputStream.bufferedReader().readText()
                        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                        p.destroy()
                        out.trim().lineSequence().firstOrNull() ?: ""
                    }.getOrDefault("")
                } else {
                    foundPath = null
                    version = ""
                }
            }
            checked = true
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Git operations use the Git executable found on this device.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            SectionRow("Git executable")
            when {
                !checked -> NoteRow("Searching for Git…")
                foundPath == null -> NoteRow(
                    "No Git executable found on this device. Clone, commit, and push are unavailable " +
                        "until Git is installed.",
                )
                else -> {
                    InfoRow("Path", foundPath ?: "")
                    InfoRow("Version", version.ifBlank { "unknown" })
                }
            }
            ActionRow("Refresh") { tick++ }
            SectionRow("User")
            InfoRow("User name", get("git_user_name", "(not set)"))
            InfoRow("User email", get("git_user_email", "(not set)"))
            FieldRow(
                label = "Name:",
                value = get("git_user_name", ""),
                numeric = false,
                desc = "",
            ) { put("git_user_name", it) }
            FieldRow(
                label = "Email:",
                value = get("git_user_email", ""),
                numeric = false,
                desc = "",
            ) { put("git_user_email", it) }
        }
    }

    // ------------------------------------------------------------
    // Build, Execution, Deployment > Build Tools (real toolchain data)
    // ------------------------------------------------------------
    private data class JdkEntry(val version: String, val path: String, val javaVersion: String)

    private fun jdkVersionOf(home: File): String {
        val rel = File(home, "release")
        if (rel.isFile) {
            val txt = runCatching { rel.readText() }.getOrDefault("")
            Regex("""JAVA_VERSION="?([^"\n]+)"?""").find(txt)?.let { return it.groupValues[1] }
        }
        return ""
    }

    @Composable
    private fun BuildToolsPage() {
        var tick by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var jdkEntries by remember { mutableStateOf<List<JdkEntry>>(emptyList()) }
        var jdkActive by remember { mutableStateOf<String?>(null) }
        var jdkHome by remember { mutableStateOf("") }
        var gradleVersions by remember { mutableStateOf<List<String>>(emptyList()) }
        var gradleActive by remember { mutableStateOf<String?>(null) }
        var gradleHome by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        LaunchedEffect(tick) {
            loading = true
            withContext(Dispatchers.IO) {
                val tc = ToolchainManager(host)
                jdkEntries = tc.installedVersions(ToolchainManager.Kind.JDK).map { v ->
                    val dir = File(tc.kindDir(ToolchainManager.Kind.JDK), v)
                    val home = tc.resolveJdkHome(dir) ?: dir
                    JdkEntry(v, home.absolutePath, jdkVersionOf(home))
                }
                jdkActive = tc.activeVersion(ToolchainManager.Kind.JDK)
                jdkHome = tc.activeJdkHome()?.absolutePath ?: "Not found"
                gradleVersions = tc.installedVersions(ToolchainManager.Kind.GRADLE)
                gradleActive = tc.activeVersion(ToolchainManager.Kind.GRADLE)
                gradleHome = tc.activeDir(ToolchainManager.Kind.GRADLE)?.absolutePath ?: "Not found"
            }
            loading = false
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Gradle build settings and the toolchain used to run builds.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            if (loading) {
                NoteRow("Loading toolchain…")
            }

            SectionRow("Gradle")
            InfoRow("Gradle home", gradleHome)
            InfoRow("Gradle user home", File(filesDir, ".gradle").absolutePath)
            if (gradleVersions.isEmpty()) {
                NoteRow("No Gradle distribution installed yet.")
            } else {
                gradleVersions.forEach { v ->
                    SelectRow(
                        label = v,
                        sub = "",
                        selected = v == gradleActive,
                    ) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                ToolchainManager(host)
                                    .setActiveVersion(ToolchainManager.Kind.GRADLE, v)
                            }
                            toast(if (ok) "Active Gradle: $v" else "Could not activate $v")
                            tick++
                        }
                    }
                }
            }

            SectionRow("Gradle JVM")
            InfoRow("JAVA_HOME", jdkHome)
            if (jdkEntries.isEmpty()) {
                NoteRow(
                    "No JDK installed. Download one from Tools > Download Manager; installed JDKs " +
                        "appear here.",
                )
            } else {
                jdkEntries.forEach { jdk ->
                    val active = jdk.version == jdkActive
                    SelectRow(
                        label = if (active) "${jdk.version}  (default)" else jdk.version,
                        sub = buildString {
                            if (jdk.javaVersion.isNotBlank()) {
                                append("Java ${jdk.javaVersion}")
                                append("  ·  ")
                            }
                            append(jdk.path)
                        },
                        selected = active,
                    ) {
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                ToolchainManager(host)
                                    .setActiveVersion(ToolchainManager.Kind.JDK, jdk.version)
                            }
                            toast(if (ok) "Active JDK: ${jdk.version}" else "Could not activate ${jdk.version}")
                            tick++
                        }
                    }
                }
            }

            SectionRow("JDK Auto-Install Preference")
            val prefJdk = get("preferred_jdk_major", "0")
            ComboRow(
                label = "Preferred JDK:",
                options = listOf("Auto", "JDK 17 (LTS)", "JDK 21 (Latest)"),
                value = when (prefJdk) {
                    "17" -> "JDK 17 (LTS)"
                    "21" -> "JDK 21 (Latest)"
                    else -> "Auto"
                },
                desc = "Which JDK to download when neither 17 nor 21 is installed.",
            ) { selected ->
                val major = when (selected) {
                    "JDK 17 (LTS)" -> "17"
                    "JDK 21 (Latest)" -> "21"
                    else -> "0"
                }
                put("preferred_jdk_major", major)
            }

            SectionRow("Memory")
            NumberRow(
                label = "Gradle heap (MB):",
                value = btcInt("gradle_heap") { BuildToolsConfig.gradleHeapMb(host) },
            ) { putBtc("gradle_heap", it) }
            NumberRow(
                label = "Gradle metaspace (MB):",
                value = btcInt("gradle_metaspace") { BuildToolsConfig.gradleMetaspaceMb(host) },
            ) { putBtc("gradle_metaspace", it) }
            NumberRow(
                label = "Kotlin heap (MB):",
                value = btcInt("kotlin_heap") { BuildToolsConfig.kotlinHeapMb(host) },
            ) { putBtc("kotlin_heap", it) }
            NumberRow(
                label = "Max worker threads:",
                value = btcInt("max_workers") { BuildToolsConfig.maxWorkers(host) },
            ) { putBtc("max_workers", it) }

            SectionRow("Build")
            CheckRow(
                checked = btcBool("parallel") { BuildToolsConfig.parallel(host) },
                label = "Run build in parallel",
                desc = "Executes independent tasks concurrently.",
            ) { putBtc("parallel", it) }
            CheckRow(
                checked = btcBool("build_cache") { BuildToolsConfig.buildCache(host) },
                label = "Enable build cache",
                desc = "Reuses task outputs between builds.",
            ) { putBtc("build_cache", it) }
            CheckRow(
                checked = btcBool("daemon") { BuildToolsConfig.daemon(host) },
                label = "Enable Gradle daemon",
                desc = "Keeps a long-lived Gradle process for faster builds.",
            ) { putBtc("daemon", it) }
            ComboRow(
                label = "Console output:",
                options = listOf("plain", "console", "rich"),
                value = btcStr("console") { BuildToolsConfig.console(host) },
                desc = "",
            ) { putBtc("console", it) }
            CheckRow(
                checked = btcBool("verbose") { BuildToolsConfig.gradleVerbose(host) },
                label = "Verbose Gradle output",
                desc = "Prints full Gradle diagnostics to the build log.",
            ) { putBtc("verbose", it) }

            SectionRow("Advanced")
            ActionRow("Open advanced Build Tools settings") {
                BuildToolsSettingsActivity.start(host)
            }
        }
    }

    @Composable
    private fun SelectRow(label: String, sub: String, selected: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                if (selected) "●" else "○",
                color = if (selected) Palette.link else Palette.dimText,
                fontSize = 14.sp,
                modifier = Modifier.width(22.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = if (selected) Color.White else Palette.text,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
                if (sub.isNotBlank()) {
                    Text(
                        sub,
                        color = Palette.dimText,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------
    // Languages & Frameworks > C/C++ children (real tool detection)
    // ------------------------------------------------------------
    @Composable
    private fun NativeToolPage(title: String, binary: String) {
        // Map binary name to ClangToolsDownloader tool name
        val toolName = when (binary) {
            "clangd" -> "clangd"
            "clang-tidy" -> "clang-tidy"
            "cling" -> "cling"
            else -> null
        }
        var tick by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var path by remember { mutableStateOf<String?>(null) }
        var ndkHint by remember { mutableStateOf<String?>(null) }
        var releases by remember { mutableStateOf<List<ClangToolsDownloader.ReleaseInfo>>(emptyList()) }
        var releasesLoading by remember { mutableStateOf(false) }
        var selectedRelease by remember { mutableStateOf<ClangToolsDownloader.ReleaseInfo?>(null) }
        var downloadActive by remember { mutableStateOf(false) }
        var downloadResult by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(tick) {
            loading = true
            withContext(Dispatchers.IO) {
                // 1. Search in filesDir/toolchain (user-installed tools)
                val root = File(filesDir, "toolchain")
                var found: File? = null
                var hint: String? = null
                if (root.isDirectory) {
                    runCatching {
                        root.walkTopDown().maxDepth(6).forEach { f ->
                            if (found == null && f.isFile && f.name == binary && f.canExecute()) {
                                found = f
                                hint = "User-installed"
                            }
                        }
                    }
                }
                // 2. Also check clang-tools downloader dir
                if (found == null && toolName != null) {
                    val toolFile = ClangToolsDownloader.toolPath(host, toolName)
                    if (toolFile != null) {
                        found = toolFile
                        hint = "GitHub download"
                    }
                }
                // 3. Search in NDK llvm/bin (clang, clang++, clangd, clang-tidy from NDK)
                if (found == null) {
                    val ndkDirs = NdkToolchain.discoverInstalled(host)
                    for (ndkDir in ndkDirs) {
                        val llvmBin = NdkToolchain.llvmBinDir(ndkDir)
                        val candidate = File(llvmBin, binary)
                        if (candidate.isFile && candidate.canExecute()) {
                            found = candidate
                            hint = "NDK: ${ndkDir.name}"
                            break
                        }
                        // Also try versioned clang binaries (clang-24, clang-18, etc.)
                        if (binary == "clang") {
                            for (v in listOf("clang-24", "clang-18", "clang-17", "clang-16")) {
                                val c = File(llvmBin, v)
                                if (c.isFile && c.canExecute()) {
                                    found = c
                                    hint = "NDK: ${ndkDir.name}"
                                    break
                                }
                            }
                            if (found != null) break
                        }
                    }
                }
                path = found?.absolutePath
                ndkHint = hint
            }
            loading = false
        }

        // Fetch releases from GitHub API when tool is not found
        if (toolName != null && path == null) {
            LaunchedEffect(toolName) {
                releasesLoading = true
                withContext(Dispatchers.IO) {
                    val token = host.getSharedPreferences("github_settings", Context.MODE_PRIVATE)
                        .getString("github_token", null)
                    releases = ClangToolsDownloader.fetchReleases(token)
                        .filter { r ->
                            r.assets.any { a ->
                                ClangToolsDownloader.TOOLS.any { t ->
                                    t.name == toolName && a.name == t.assetName
                                }
                            }
                        }
                }
                releasesLoading = false
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "$title settings. The executable is located inside the installed toolchain.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            SectionRow("Executable")
            when {
                loading -> NoteRow("Searching toolchain and NDK…")
                path == null && toolName == null -> NoteRow(
                    "$title was not found. Install the LLVM or NDK toolchain to use it.",
                )
                path == null && toolName != null -> Column {
                    if (releasesLoading) {
                        NoteRow("Fetching versions from GitHub…")
                    } else if (releases.isEmpty()) {
                        NoteRow(
                            "$title was not found. No GitHub releases available for $toolName. " +
                            "Add your GitHub token in Settings > Advanced to access private releases.",
                        )
                        ActionRow("Enter GitHub Token") {
                            val input = android.widget.EditText(host)
                            input.setHint("ghp_...")
                            android.app.AlertDialog.Builder(host)
                                .setTitle("GitHub Personal Access Token")
                                .setView(input)
                                .setPositiveButton("Save") { _, _ ->
                                    host.getSharedPreferences("github_settings", Context.MODE_PRIVATE)
                                        .edit().putString("github_token", input.text.toString().trim()).apply()
                                    toast("Token saved. Refresh to see versions.")
                                    tick++
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    } else {
                        Text(
                            "Available versions for $toolName:",
                            color = Palette.desc,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        )
                        releases.forEach { rel ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selectedRelease = rel
                                }.padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    rel.tagName,
                                    color = if (selectedRelease == rel) Palette.link else Palette.text,
                                    fontSize = 13.sp,
                                    fontWeight = if (selectedRelease == rel) FontWeight.Bold else FontWeight.Normal,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${rel.assets.size} assets",
                                    color = Palette.dimText,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        if (selectedRelease != null) {
                            val url = ClangToolsDownloader.findToolUrl(selectedRelease!!, toolName)
                            if (url != null) {
                                ActionRow("Download $toolName ${selectedRelease!!.tagName}") {
                                    downloadActive = true
                                    downloadResult = null
                                    host.lifecycleScope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            ClangToolsDownloader.downloadTool(
                                                url, toolName, host,
                                                onLog = { msg -> Log.d("ClangDownload", msg) }
                                            )
                                        }
                                        downloadResult = if (result != null) "success" else "failed"
                                        downloadActive = false
                                        tick++ // refresh path detection
                                    }
                                }
                                if (downloadActive) {
                                    NoteRow("Downloading $toolName…")
                                } else if (downloadResult == "success") {
                                    NoteRow("$toolName downloaded successfully!", color = Palette.success)
                                } else if (downloadResult == "failed") {
                                    NoteRow("$toolName download failed.", color = Palette.error)
                                }
                            }
                        }
                    }
                }
                else -> {
                    val p = path ?: ""
                    InfoRow("Path", p)
                    val h = ndkHint
                    if (h != null) InfoRow("Source", h)
                }
            }
            if (toolName != null && path == null) {
                ActionRow("Refresh versions") { tick++ }
            }
            ActionRow("Refresh") { tick++ }
        }
    }

    // ------------------------------------------------------------
    // Languages & Frameworks > Android SDK (real SDK contents)
    // ------------------------------------------------------------
    @Composable
    private fun AndroidSdkPage() {
        var tick by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var sdkFile by remember { mutableStateOf<File?>(null) }
        var topPackages by remember { mutableStateOf<List<String>>(emptyList()) }
        var platforms by remember { mutableStateOf<List<String>>(emptyList()) }
        var buildToolsList by remember { mutableStateOf<List<String>>(emptyList()) }
        var ndkList by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
        var cmakeList by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
        var adbPath by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(tick) {
            loading = true
            withContext(Dispatchers.IO) {
                val tc = ToolchainManager(host)
                val sdk = BuildToolchain.sdkDir(host)
                sdkFile = sdk
                topPackages = sdk?.listFiles()?.map { it.name }?.sorted() ?: emptyList()
                platforms = File(sdk, "platforms").listFiles()?.map { it.name }?.sorted() ?: emptyList()
                buildToolsList = File(sdk, "build-tools").listFiles()?.map { it.name }?.sorted()
                    ?: emptyList()
                val activeNdk = tc.activeVersion(ToolchainManager.Kind.NDK)
                ndkList = tc.installedVersions(ToolchainManager.Kind.NDK).map { it to (it == activeNdk) }
                val activeCmake = tc.activeVersion(ToolchainManager.Kind.CMAKE)
                cmakeList = tc.installedVersions(ToolchainManager.Kind.CMAKE).map {
                    it to (it == activeCmake)
                }
                val adb = File(sdk, "platform-tools/adb")
                adbPath = if (adb.isFile) adb.absolutePath else null
            }
            loading = false
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Android SDK location and installed components.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            if (loading) {
                NoteRow("Loading SDK…")
            }

            SectionRow("SDK location")
            val sdk = sdkFile
            if (sdk == null) {
                NoteRow(
                    "Android SDK not found. Install it from Tools > Download Manager or SDK Manager.",
                )
            } else {
                InfoRow("Path", sdk.absolutePath)
                ActionRow("Open SDK folder") {
                    ToolchainFileExplorerActivity.startAt(
                        host,
                        "Android SDK",
                        sdk,
                    )
                }
            }

            SectionRow("Platforms (${platforms.size})")
            if (platforms.isEmpty()) NoteRow("No platforms installed.")
            platforms.forEach { InfoRow(it, "platform") }

            SectionRow("Build tools (${buildToolsList.size})")
            if (buildToolsList.isEmpty()) NoteRow("No build tools installed.")
            buildToolsList.forEach { InfoRow(it, "build-tools") }

            SectionRow("Installed packages (${topPackages.size})")
            if (topPackages.isEmpty()) NoteRow("No packages installed.")
            topPackages.forEach { InfoRow(it, "") }

            SectionRow("NDK (${ndkList.size})")
            if (ndkList.isEmpty()) NoteRow("No NDK installed.")
            ndkList.forEach { (version, active) ->
                SelectRow(
                    label = if (active) "$version  (active)" else version,
                    sub = "",
                    selected = active,
                ) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            ToolchainManager(host)
                                .setActiveVersion(ToolchainManager.Kind.NDK, version)
                        }
                        toast(if (ok) "Active NDK: $version" else "Could not activate $version")
                        tick++
                    }
                }
            }

            SectionRow("CMake (${cmakeList.size})")
            if (cmakeList.isEmpty()) NoteRow("No CMake installed.")
            cmakeList.forEach { (version, active) ->
                SelectRow(
                    label = if (active) "$version  (active)" else version,
                    sub = "",
                    selected = active,
                ) {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            ToolchainManager(host)
                                .setActiveVersion(ToolchainManager.Kind.CMAKE, version)
                        }
                        toast(if (ok) "Active CMake: $version" else "Could not activate $version")
                        tick++
                    }
                }
            }

            SectionRow("Platform tools")
            if (adbPath == null) NoteRow("platform-tools not installed (adb missing).")
            else InfoRow("adb", adbPath ?: "")

            ActionRow("Refresh") { tick++ }
        }
    }

    // ------------------------------------------------------------
    // Languages & Frameworks > JVM Logging (real log files)
    // ------------------------------------------------------------
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    @Composable
    private fun JvmLoggingPage() {
        var tick by remember { mutableStateOf(0) }
        var logs by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

        LaunchedEffect(tick) {
            withContext(Dispatchers.IO) {
                val list = mutableListOf<Pair<String, String>>()
                fun scan(dir: File, prefix: String) {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && (f.name.endsWith(".log") || f.name.endsWith(".txt"))) {
                            list += (prefix + f.name) to formatSize(f.length())
                        }
                    }
                }
                scan(filesDir, "")
                scan(cacheDir, "cache/")
                logs = list.sortedBy { it.first.lowercase() }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Log files written by the IDE and its background services.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            SectionRow("Log files (${logs.size})")
            if (logs.isEmpty()) NoteRow("No log files yet.")
            logs.forEach { (name, size) -> InfoRow(name, size) }
            ActionRow("Refresh") { tick++ }
            ActionRow("Open log directory") {
                ToolchainFileExplorerActivity.startAt(host, "Logs", filesDir)
            }
            NoteRow(
                "The background AI service appends its output to background_ai.log in the files " +
                    "directory.",
            )
        }
    }

    // ------------------------------------------------------------
    // Languages & Frameworks > Kotlin > Kotlin Compiler
    // ------------------------------------------------------------
    @Composable
    private fun KotlinScriptingPage() {
        var tick by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var kotlinHome by remember { mutableStateOf<String?>(null) }
        var version by remember { mutableStateOf("") }

        LaunchedEffect(tick) {
            loading = true
            withContext(Dispatchers.IO) {
                val home = File(filesDir, "toolchain/kotlin")
                if (home.isDirectory) {
                    kotlinHome = home.absolutePath
                    version = runCatching {
                        home.walkTopDown().maxDepth(3)
                            .map { it.name }
                            .firstNotNullOfOrNull { name ->
                                Regex("""kotlin-compiler-(\d+\.\d+\.\d+)""").find(name)
                                    ?.groupValues?.get(1)
                            } ?: ""
                    }.getOrDefault("")
                } else {
                    kotlinHome = null
                    version = ""
                }
            }
            loading = false
        }

        Column(Modifier.fillMaxWidth()) {
            SectionRow("Kotlin compiler")
            when {
                loading -> NoteRow("Loading…")
                kotlinHome == null -> NoteRow("The Kotlin toolchain is not installed.")
                else -> {
                    InfoRow("Home", kotlinHome ?: "")
                    InfoRow("Version", version.ifBlank { "unknown" })
                }
            }
            ActionRow("Refresh") { tick++ }
            ActionRow("Open Kotlin home") {
                kotlinHome?.let {
                    ToolchainFileExplorerActivity.startAt(
                        host,
                        "Kotlin",
                        File(it),
                    )
                }
            }
            SectionRow("Compiler memory")
            NumberRow(
                label = "Kotlin heap (MB):",
                value = btcInt("kotlin_heap") { BuildToolsConfig.kotlinHeapMb(host) },
            ) { putBtc("kotlin_heap", it) }
            NumberRow(
                label = "Kotlin metaspace (MB):",
                value = btcInt("kotlin_metaspace") { BuildToolsConfig.kotlinMetaspaceMb(host) },
            ) { putBtc("kotlin_metaspace", it) }
            NumberRow(
                label = "Max worker threads:",
                value = btcInt("max_workers") { BuildToolsConfig.maxWorkers(host) },
            ) { putBtc("max_workers", it) }
        }
    }

    // ------------------------------------------------------------
    // Languages & Frameworks > Markdown
    // ------------------------------------------------------------
    @Composable
    private fun MarkdownPage() {
        Column(Modifier.fillMaxWidth()) {
            ComboRow(
                label = "Preview rendering engine:",
                options = listOf("Compose (experimental)", "IntelliJ"),
                value = get("md_engine", "Compose (experimental)"),
                desc = "",
            ) { put("md_engine", it) }
            ComboRow(
                label = "Default layout:",
                options = listOf("Editor and Preview", "Editor only", "Preview only"),
                value = get("md_layout", "Editor and Preview"),
                desc = "",
            ) { put("md_layout", it) }
            ComboRow(
                label = "Preview layout:",
                options = listOf("Split vertically", "Split horizontally"),
                value = get("md_split", "Split vertically"),
                desc = "",
            ) { put("md_split", it) }
            ComboRow(
                label = "Preview font size:",
                options = listOf("9", "10", "11", "12", "13", "14", "15", "16", "18", "20", "24"),
                value = get("md_font_size", "13"),
                desc = "",
            ) { put("md_font_size", it) }
            CheckRow(
                checked = getBool("md_sync_scroll", true),
                label = "Sync scroll in the editor and preview",
                desc = "",
            ) { putBool("md_sync_scroll", it) }
            CheckRow(
                checked = getBool("md_inject_languages", true),
                label = "Inject languages in code fences",
                desc = "",
            ) { putBool("md_inject_languages", it) }
            CheckRow(
                checked = getBool("md_problems_in_fences", true),
                label = "Show problems in code fences",
                desc = "",
            ) { putBool("md_problems_in_fences", it) }
            CheckRow(
                checked = getBool("md_group_same_name", false),
                label = "Group documents with the same name, but different extensions (.md, .html, .docx, ...)",
                desc = "",
            ) { putBool("md_group_same_name", it) }
            CheckRow(
                checked = getBool("md_detect_commands", true),
                label = "Detect commands that can be run right from Markdown files",
                desc = "",
            ) { putBool("md_detect_commands", it) }
            Text(
                "Markdown Extensions:",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = getBool("md_plantuml", false),
                    onCheckedChange = { putBool("md_plantuml", it) },
                    colors = CheckboxDefaults.colors(
                        checkedColor = Palette.link,
                        uncheckedColor = Palette.border,
                        checkmarkColor = Color.White,
                    ),
                )
                Text("PlantUML", color = Palette.text, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Text(
                    "Install",
                    color = Palette.link,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        openExternalUrl("https://plantuml.com/download")
                    },
                )
            }
            Collapsible("Custom CSS") {
                FieldRow(
                    label = "CSS file path:",
                    value = get("md_css_path", ""),
                    numeric = false,
                    desc = "A CSS file applied to the rendered preview.",
                ) { put("md_css_path", it) }
            }
            Collapsible("Pandoc Settings") {
                FieldRow(
                    label = "Pandoc executable:",
                    value = get("md_pandoc_path", ""),
                    numeric = false,
                    desc = "Used to export Markdown to other formats when installed.",
                ) { put("md_pandoc_path", it) }
                ComboRow(
                    label = "Export format:",
                    options = listOf("HTML", "PDF", "DOCX", "LaTeX"),
                    value = get("md_export_format", "HTML"),
                    desc = "",
                ) { put("md_export_format", it) }
            }
        }
    }

    /** Opens a documentation/download page in the system browser. */
    private fun openExternalUrl(url: String) {
        runCatching {
            host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            toast("No application can open $url")
        }
    }

    // ------------------------------------------------------------
    // Tools > AI (llama.cpp engine, real model inventory)
    // ------------------------------------------------------------
    @Composable
    private fun AiPage() {
        var tick by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var models by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var logInfo by remember { mutableStateOf("") }
        var serviceOn by remember { mutableStateOf(false) }

        LaunchedEffect(tick) {
            loading = true
            withContext(Dispatchers.IO) {
                val dir = File(filesDir, "AI/models")
                models = dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".gguf") }
                    ?.sortedBy { it.name }
                    ?.map { it.name to formatSize(it.length()) }
                    ?: emptyList()
                val log = File(filesDir, "background_ai.log")
                logInfo = if (log.isFile) "${formatSize(log.length())}  ·  ${log.absolutePath}"
                else "not written yet  ·  ${log.absolutePath}"
            }
            serviceOn = AIBackgroundService.isRunning
            loading = false
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Local AI assistant powered by the llama.cpp engine. Models are stored on this " +
                    "device and the assistant keeps running in a background service.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            SectionRow("Engine")
            InfoRow("Engine", "llama.cpp")
            InfoRow("Service", if (serviceOn) "running" else "stopped")
            InfoRow("Log", logInfo)

            SectionRow("Background service")
            ActionRow(if (serviceOn) "Stop background service" else "Start background service") {
                if (serviceOn) {
                    AIBackgroundService.stopService(host)
                    toast("Background AI service stopped")
                } else {
                    AIBackgroundService.startService(host)
                    toast("Background AI service started")
                }
                tick++
            }
            NoteRow(
                "While the service runs, AI commands keep executing when the app is closed.",
            )

            SectionRow("Models (${models.size})")
            if (loading) {
                NoteRow("Scanning models directory…")
            } else if (models.isEmpty()) {
                NoteRow(
                    "No .gguf model installed. Download one from the AI workspace's model tab, or " +
                        "copy it into ${File(filesDir, "AI/models").absolutePath}.",
                )
            } else {
                models.forEach { (name, size) -> InfoRow(name, size) }
            }
            ActionRow("Refresh") { tick++ }
            ActionRow("Open models directory") {
                ToolchainFileExplorerActivity.startAt(
                    host,
                    "AI models",
                    File(filesDir, "AI/models"),
                )
            }

            SectionRow("Workspace")
            ActionRow("Open AI workspace") {
                AiWorkspaceActivity.start(host, filesDir.absolutePath, null, null)
            }
            FieldRow(
                label = "Default model:",
                value = get("ai_default_model", ""),
                numeric = false,
                desc = "Leave empty to use the most recently downloaded model.",
            ) { put("ai_default_model", it) }
            NumberRow(
                label = "Context size:",
                value = getInt("ai_context_size", 4096),
            ) { putInt("ai_context_size", it) }
            NumberRow(
                label = "GPU threads:",
                value = getInt("ai_threads", 4),
            ) { putInt("ai_threads", it) }
        }
    }

    // ------------------------------------------------------------
    // Tools > Device Explorer / Device Mirroring (real adb devices)
    // ------------------------------------------------------------
    @Composable
    private fun DevicesPage() {
        var tick by remember { mutableStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var devices by remember { mutableStateOf<List<AdbDeviceManager.AdbDevice>>(emptyList()) }
        var adbPath by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf("") }
        var hostAddr by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("5555") }
        val scope = rememberCoroutineScope()

        LaunchedEffect(tick) {
            loading = true
            withContext(Dispatchers.IO) {
                error = ""
                val sdk = BuildToolchain.sdkDir(host)
                val adb = File(sdk, "platform-tools/adb")
                adbPath = if (adb.isFile) adb.absolutePath else null
                try {
                    devices = AdbDeviceManager.listDevices()
                } catch (t: Throwable) {
                    devices = emptyList()
                    error = t.message ?: "adb failed"
                }
            }
            loading = false
        }

        Column(Modifier.fillMaxWidth()) {
            Text(
                "Devices reported by adb. Wireless devices can be paired by address.",
                color = Palette.desc,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )

            SectionRow("adb")
            if (adbPath == null) {
                NoteRow(
                    "platform-tools not installed. Install it from Tools > Download Manager to " +
                        "use devices.",
                )
            } else {
                InfoRow("Path", adbPath ?: "")
            }

            SectionRow("Connected devices (${devices.size})")
            if (loading) {
                NoteRow("Querying adb…")
            } else if (devices.isEmpty()) {
                NoteRow("No devices connected. Enable USB debugging or connect wirelessly.")
            }
            devices.forEach { d ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "●",
                        color = if (d.state == AdbDeviceManager.DeviceState.DEVICE) {
                            Color(0xFF4CAF50)
                        } else Color(0xFFFFA000),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            d.model.ifBlank { d.serial },
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            buildString {
                                append(d.serial)
                                append("  ·  ")
                                append(d.state.name.lowercase())
                                if (d.apiLevel > 0) {
                                    append("  ·  API ")
                                    append(d.apiLevel)
                                }
                                if (d.abi.isNotBlank()) {
                                    append("  ·  ")
                                    append(d.abi)
                                }
                                if (d.isEmulator) append("  ·  emulator")
                            },
                            color = Palette.dimText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 16.sp,
                        )
                    }
                    ActionRow("Logcat") {
                        LogcatActivity.start(
                            host,
                            d.product.ifBlank { d.model },
                            0,
                        )
                    }
                }
            }
            if (error.isNotBlank()) NoteRow("adb: $error")
            ActionRow("Refresh") { tick++ }

            SectionRow("Wireless debugging")
            FieldRow(
                label = "Host:",
                value = hostAddr,
                numeric = false,
                desc = "Device IP address, e.g. 192.168.0.24",
            ) { hostAddr = it }
            FieldRow(
                label = "Port:",
                value = port,
                numeric = true,
                desc = "",
            ) { port = it }
            ActionRow("Connect") {
                scope.launch {
                    val targetHost = hostAddr.trim()
                    val targetPort = port.trim().toIntOrNull() ?: 5555
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            AdbDeviceManager.connectWireless(targetHost, targetPort)
                        }
                    }
                    result.fold(
                        onSuccess = { ok ->
                            if (ok) toast("Connected to $targetHost:$targetPort")
                            else toast("Connect failed: no response from adb")
                        },
                        onFailure = { toast("Connect failed: ${it.message}") },
                    )
                    tick++
                }
            }
            ActionRow("Open terminal for adb") {
                AndroidShellActivity.start(host, filesDir)
            }
        }
    }

    // ------------------------------------------------------------
    // Advanced Settings (search + modified filter)
    // ------------------------------------------------------------
    private data class AdvRow(
        val section: String,
        val key: String,
        val label: String,
        val def: Any,
        val desc: String = "",
    )

    private val ADVANCED_ROWS: List<AdvRow> = listOf(
        AdvRow(
            "Appearance", "advanced_islands_ui",
            "Enable Islands UI for custom themes", false, "Requires restart",
        ),
        AdvRow(
            "Bookmarks", "bookmarks_line_only",
            "Show only line bookmarks in popup", true,
        ),
        AdvRow(
            "Bookmarks", "bookmarks_restore_context",
            "Restore bookmark context on branch switching", true,
        ),
        AdvRow(
            "Build Tools. Gradle", "gradle_run_using",
            "Run using Gradle", true,
            "By default, the IDE uses Gradle to build the project and run tasks. " +
                "If disabled, application configurations will run using the IDE.",
        ),
        AdvRow(
            "Build Tools. Gradle", "gradle_download_sources",
            "Download sources", true,
            "Download sources for project dependencies during the project import. " +
                "A project sync is required to download the sources after selecting this option.",
        ),
        AdvRow(
            "Build Tools. Gradle", "gradle_attach_script_sources",
            "Attach scripts dependencies sources", false,
            "Attach sources for build scripts dependencies during the project import. " +
                "A project sync is required to attach the sources after selecting this option.",
        ),
        AdvRow(
            "C++ Projects", "cpp_compiler_timeout",
            "Timeout to execute compiler for collecting its information (in seconds)", 60,
        ),
        AdvRow(
            "C++ Projects", "cpp_show_all_external_sources",
            "Show all files in External Sources", false,
            "Show all filesystem contents in External Sources, not just project-referenced directories",
        ),
        AdvRow(
            "Clangd", "clangd_use_indexer",
            "Use Clangd-based indexer", false,
            "Provides faster and more accurate find usages. Requires restart.",
        ),
        AdvRow(
            "Clangd", "clangd_keep_obsolete_asts",
            "Keep obsolete ASTs in clangd", false,
            "Do not evict obsolete ASTs immediately. Speeds up navigation (and other requests) " +
                "in the future.",
        ),
        AdvRow(
            "Code Review Tools", "diff_all_in_one",
            "All-in-one Diff for Code Reviews", false,
            "If enabled, all changed files in code reviews are shown in a single scrollable Diff",
        ),
        AdvRow(
            "Code Review Tools", "diff_keep_thread_margins",
            "Keep increased margins for Diff threads", true,
        ),
        AdvRow(
            "Compiler", "auto_make_while_running",
            "Allow auto-make to start even if developed application is currently running", false,
            "Automatically started make may eventually delete some classes that are required " +
                "by the application",
        ),
        AdvRow(
            "Compiler", "compile_lower_priority",
            "Run compilation with lower priority", true,
            "Run external JPS process with IDLE priority on Windows and nice level 10 on Linux/macOS",
        ),
        AdvRow(
            "Compiler", "unified_java_kotlin_compilation",
            "Enable unified Java/Kotlin incremental compilation implementation", true,
            "External JPS process will use new incremental compilation implementation that can " +
                "handle both Java- and Kotlin-produced bytecode",
        ),
        AdvRow(
            "Compiler", "collect_debug_log",
            "Collect a detailed debug log report in case of build failures", false,
            "Saves detailed logs of failed builds to assist in debugging and troubleshooting. " +
                "Logs are stored alongside other build logs",
        ),
        AdvRow(
            "Debugger", "max_recent_expressions",
            "Maximum number of recent expressions", 50,
        ),
        AdvRow(
            "Debugger", "show_inline_run_cursor",
            "Show inline Run to Cursor popup", true,
        ),
    )

    @Composable
    private fun AdvancedPage() {
        var query by remember { mutableStateOf("") }
        var modifiedOnly by remember { mutableStateOf(false) }
        val q = query.trim().lowercase()

        val rows = remember(q, modifiedOnly, draft.size) {
            ADVANCED_ROWS.filter { r ->
                (q.isEmpty() || r.label.lowercase().contains(q) || r.section.lowercase().contains(q)) &&
                    (!modifiedOnly || isModified(r.key, r.def))
            }
        }
        val sections = remember(rows) { rows.map { it.section }.distinct() }

        Column(Modifier.fillMaxWidth()) {
            SearchBox(query, placeholder = "Search advanced settings") { query = it }
            CheckRow(
                checked = modifiedOnly,
                label = "Show modified only",
                desc = "",
            ) { modifiedOnly = it }

            sections.forEach { section ->
                SectionRow(section)
                rows.filter { it.section == section }.forEach { r ->
                    when (val d = r.def) {
                        is Boolean -> CheckRow(
                            checked = getBool(r.key, d),
                            label = r.label,
                            desc = r.desc,
                        ) { putBool(r.key, it) }
                        is Int -> NumberRow(label = "${r.label}:", value = getInt(r.key, d)) {
                            putInt(r.key, it)
                        }
                        else -> FieldRow(
                            label = r.label,
                            value = get(r.key, d.toString()),
                            numeric = false,
                            desc = r.desc,
                        ) { put(r.key, it) }
                    }
                }
            }
            if (rows.isEmpty()) NoteRow("No matching settings.")
        }
    }

    // ------------------------------------------------------------
    // Backup and Sync
    // ------------------------------------------------------------
    @Composable
    private fun BackupSyncPage() {
        val googleAccounts = remember {
            runCatching {
                AccountManager.get(host).getAccountsByType("com.google").map { it.name }
            }.getOrDefault(emptyList())
        }
        val savedAccount = get("backup_email", "")
        val accounts = remember(googleAccounts, savedAccount) {
            (googleAccounts + listOfNotNull(savedAccount.takeIf { it.isNotBlank() }))
                .distinct()
        }
        val accountOptions = accounts.ifEmpty { listOf("Not signed in") }

        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Sync UI, Code and System settings, Keymaps, Plugins, and Tools.",
                    color = Palette.desc,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Learn more ↗",
                color = Palette.link,
                fontSize = 13.sp,
                modifier = Modifier
                    .clickable {
                        openExternalUrl(
                            "https://developer.android.com/studio/intro/studio-config#backup"
                        )
                    }
                    .padding(vertical = 6.dp),
            )
            CheckRow(
                checked = getBool("backup_enabled", false),
                label = "Enable backup and sync",
                desc = "Uploads IDE settings when the app is idle.",
            ) { putBool("backup_enabled", it) }
            ComboRow(
                label = "Account:",
                options = accountOptions,
                value = savedAccount.ifBlank { accountOptions.first() },
                desc = if (accounts.isEmpty()) {
                    "No Google account is available on this device. Add one in Android Settings to sync."
                } else "",
            ) { put("backup_email", it) }
            SectionRow("Local backup")
            InfoRow("Preferences file", File(filesDir, "shared_prefs/$PREFS_NAME.xml").absolutePath)
            ActionRow("Export settings to a file") { showExportSettingsDialog() }
            ActionRow("Import settings from a file") { showImportSettingsDialog() }
        }
    }

    // ------------------------------------------------------------
    // Languages & Frameworks > Schemas and DTDs
    // ------------------------------------------------------------
    private val DEFAULT_IGNORED_SCHEMA =
        "http://relaxng.org/ns/compatibility/annotations/1.0"

    @Composable
    private fun SchemasPage() {
        Column(Modifier.fillMaxWidth()) {
            SchemaListEditor(
                title = "External schemas and DTDs:",
                key = "schemas_external_list",
                defaults = emptyList(),
                emptyText = "No external resources",
            )
            Spacer(Modifier.height(18.dp))
            SchemaListEditor(
                title = "Ignored schemas and DTDs:",
                key = "schemas_ignored_list",
                defaults = listOf(DEFAULT_IGNORED_SCHEMA),
                emptyText = "No ignored resources",
            )
        }
    }

    @Composable
    private fun SchemaListEditor(
        title: String,
        key: String,
        defaults: List<String>,
        emptyText: String,
    ) {
        val stored = get(key, defaults.joinToString("\n"))
        val items = remember(stored) {
            stored.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        var selected by remember(key) { mutableStateOf(-1) }
        if (selected >= items.size) selected = -1

        Column(Modifier.fillMaxWidth()) {
            Text(
                title,
                color = Palette.text,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, Palette.border)
                    .background(Palette.fieldBg.copy(alpha = 0.35f)),
            ) {
                Row(
                    Modifier.fillMaxWidth().height(34.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SchemaToolGlyph("+") {
                        showSchemaInputDialog("Add schema or DTD", "") { value ->
                            put(key, (items + value).joinToString("\n"))
                            selected = items.size
                        }
                    }
                    SchemaToolGlyph("−", enabled = selected in items.indices) {
                        if (selected in items.indices) {
                            put(key, items.filterIndexed { i, _ -> i != selected }.joinToString("\n"))
                            selected = -1
                        }
                    }
                    SchemaToolGlyph("✎", enabled = selected in items.indices) {
                        if (selected in items.indices) {
                            showSchemaInputDialog("Edit schema or DTD", items[selected]) { value ->
                                put(
                                    key,
                                    items.mapIndexed { i, old -> if (i == selected) value else old }
                                        .joinToString("\n"),
                                )
                            }
                        }
                    }
                }
                HLine()
                if (items.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().height(150.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(emptyText, color = Palette.dimText, fontSize = 13.sp)
                    }
                } else {
                    items.forEachIndexed { index, item ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    if (index == selected) Palette.selection else Color.Transparent
                                )
                                .clickable { selected = index }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item,
                                color = Palette.text,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    @Composable
    private fun SchemaToolGlyph(glyph: String, enabled: Boolean = true, onClick: () -> Unit) {
        Box(
            Modifier
                .size(34.dp)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                glyph,
                color = if (enabled) Palette.text else Palette.disabledText,
                fontSize = 16.sp,
            )
        }
    }

    private fun showSchemaInputDialog(title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(host).apply {
            setText(initial)
            hint = "https://example.com/schema.xsd"
            setSingleLine(true)
        }
        AlertDialog.Builder(host)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onSave(value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------------
    // Simple list editor (add / remove string entries)
    // ------------------------------------------------------------
    @Composable
    private fun ListEditorPage(
        key: String,
        header: String,
        emptyText: String,
        itemHint: String,
    ) {
        val stored = get(key, "")
        val items = remember(stored) {
            stored.lines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        var newItem by remember { mutableStateOf("") }

        Column(Modifier.fillMaxWidth()) {
            Text(
                header,
                color = Palette.text,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            if (items.isEmpty()) {
                NoteRow(emptyText)
            }
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item,
                        color = Palette.text,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(28.dp)
                            .clickable {
                                put(key, items.filterNot { it == item }.joinToString("\n"))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", color = Palette.dimText, fontSize = 12.sp)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .background(Palette.fieldBg)
                        .border(1.dp, Palette.border, RoundedCornerShape(4.dp)),
                ) {
                    BasicTextField(
                        value = newItem,
                        onValueChange = { newItem = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        cursorBrush = SolidColor(Palette.link),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 7.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = {
                        val v = newItem.trim()
                        if (v.isNotEmpty()) {
                            val next = (items + v).joinToString("\n")
                            put(key, next)
                            newItem = ""
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Palette.link),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text("Add", fontSize = 13.sp)
                }
            }
            if (itemHint.isNotBlank()) {
                NoteRow(itemHint)
            }
        }
    }

    // ------------------------------------------------------------
    // Fallback form definitions for remaining leaf pages
    // ------------------------------------------------------------
    private fun rowsFor(id: String): List<RowItem> = when (id) {
        // Appearance & Behavior
        "menus_toolbars" -> listOf(
            RowItem.Nte("Menu and toolbar customization is inherited from the built-in layout."),
            RowItem.Chk("mt_show_icons", "Show icons in menus", def = true),
            RowItem.Chk("mt_show_shortcuts", "Show shortcuts in menus", def = true),
        )
        "file_colors" -> listOf(
            RowItem.Nte("File colors mark files by scope in the project tree."),
            RowItem.Chk("fc_enabled", "Enable file colors", def = true),
            RowItem.Cbo("fc_source", "Source files:", listOf("#2E7D32", "#1565C0", "#6A1B9A"), "#2E7D32"),
            RowItem.Cbo("fc_tests", "Test files:", listOf("#AD1457", "#EF6C00", "#00838F"), "#AD1457"),
        )
        "scopes" -> listOf(
            RowItem.Nte("Scopes restrict searches and inspections to a subset of files."),
            RowItem.Chk("scope_project_files", "Include project files in default scope", def = true),
        )
        "notifications" -> listOf(
            RowItem.Chk("notify_balloon", "Show notification balloons", def = true),
            RowItem.Chk("notify_sound", "Play notification sound", def = false),
            RowItem.Cbo("notify_duration", "Balloon duration:",
                listOf("1 second", "3 seconds", "5 seconds", "10 seconds"), "3 seconds"),
            RowItem.Chk("notify_non_modal", "Show non-modal notifications", def = true),
        )
        "quick_lists" -> listOf(
            RowItem.Nte("Quick lists are shortcut-driven popups of grouped actions."),
            RowItem.Chk("ql_enabled", "Enable quick lists", def = true),
        )
        "required_plugins" -> listOf(
            RowItem.Nte("No plugins are marked as required in this build."),
        )
        "trusted_locations" -> listOf(
            RowItem.Nte("Projects opened from untrusted locations require confirmation."),
            RowItem.Chk("trust_remember", "Remember my choices for untrusted projects", def = true),
            RowItem.Chk("trust_default_off", "Default to untrusted", def = false),
        )
        "path_variables" -> listOf(
            RowItem.Nte("Path variables can be used in run configurations and templates."),
            RowItem.Txt("pv_sdk", "ANDROID_SDK_ROOT:", def = ""),
            RowItem.Txt("pv_ndk", "ANDROID_NDK_HOME:", def = ""),
        )
        "presentation_assistant" -> listOf(
            RowItem.Chk("pa_enabled", "Enable presentation assistant", def = false),
            RowItem.Chk("pa_show_gestures", "Show touch gestures", def = true),
        )

        // Editor
        "ed_general" -> listOf(
            RowItem.Sec("Editing"),
            RowItem.Chk("ed_show_line_numbers", "Show line numbers", def = true),
            RowItem.Chk("ed_show_whitespace", "Show whitespace", def = false),
            RowItem.Chk("ed_highlight_current", "Highlight current line", def = true),
            RowItem.Chk("ed_soft_wrap", "Soft wrap lines", def = false),
            RowItem.Sec("Scrolling"),
            RowItem.Cbo("ed_scroll_lines", "Default scroll amount:",
                listOf("1 line", "5 lines", "10 lines"), "5 lines"),
            RowItem.Chk("ed_smooth_scroll", "Smooth scrolling", def = true),
        )
        "ed_code_editing" -> listOf(
            RowItem.Chk("ed_auto_indent", "Auto indent", def = true),
            RowItem.Chk("ed_format_paste", "Format on paste", def = true),
            RowItem.Chk("ed_complete_basic", "Show basic completion suggestions", def = true),
            RowItem.Chk("ed_complete_doc", "Show documentation on completion", def = true),
            RowItem.Cbo("ed_completion_delay", "Completion delay (ms):",
                listOf("0", "50", "100", "250"), "100"),
        )
        "ed_code_style" -> listOf(
            RowItem.Cbo("cs_language", "Language:", listOf("Kotlin", "Java", "XML", "Gradle"), "Kotlin"),
            RowItem.Cbo("cs_indent", "Tab size:", listOf("2", "4", "8"), "4"),
            RowItem.Cbo("cs_continuation", "Continuation indent:",
                listOf("4", "8", "16"), "8"),
            RowItem.Chk("cs_use_tabs", "Use tab characters", def = false),
            RowItem.Chk("cs_smart_tabs", "Smart tabs", def = true),
        )
        "ed_inspections" -> listOf(
            RowItem.Nte("Inspections run in the editor and in the build."),
            RowItem.Chk("insp_enabled", "Enable inspections", def = true),
            RowItem.Cbo("insp_scope", "Inspection scope:",
                listOf("Project files", "Open files", "Modified files"), "Project files"),
            RowItem.Chk("insp_on_the_fly", "Run inspections on the fly", def = true),
        )
        "ed_file_templates" -> listOf(
            RowItem.Nte("File templates create the initial content of new files."),
            RowItem.Chk("tpl_include_date", "Include date in generated headers", def = false),
            RowItem.Chk("tpl_include_author", "Include author in generated headers", def = false),
        )
        "ed_encodings" -> listOf(
            RowItem.Cbo("enc_default", "Default encoding:",
                listOf("UTF-8", "UTF-16", "ISO-8859-1"), "UTF-8"),
            RowItem.Chk("enc_bom", "Detect BOM automatically", def = true),
        )
        "ed_live_templates" -> listOf(
            RowItem.Nte("Live templates expand abbreviations into code snippets."),
            RowItem.Chk("lt_enabled", "Enable live templates", def = true),
            RowItem.Chk("lt_complete_on_tab", "Expand with Tab", def = true),
        )
        "ed_file_types" -> listOf(
            RowItem.Nte("File types map file extensions to language parsers."),
            RowItem.Chk("ft_detect_by_content", "Detect file types by content", def = true),
        )
        "ed_ui_tools" -> listOf(
            RowItem.Chk("uit_minimap", "Show editor minimap", def = false),
            RowItem.Chk("uit_breadcrumbs", "Show breadcrumbs", def = true),
            RowItem.Chk("uit_folding", "Show code folding", def = true),
        )
        "ed_copyright" -> listOf(
            RowItem.Nte("Copyright templates are inserted into new source files."),
            RowItem.Txt("cr_header", "Copyright header:", def = "Copyright (c) MobileStudio"),
        )
        "ed_inlay_hints" -> listOf(
            RowItem.Chk("ih_parameters", "Show parameter name hints", def = true),
            RowItem.Chk("ih_types", "Show type hints", def = false),
            RowItem.Chk("ih_chain_calls", "Show method chain hints", def = false),
        )
        "ed_emmet" -> listOf(
            RowItem.Chk("emmet_enabled", "Enable Emmet", def = true),
            RowItem.Cbo("emmet_theme", "Emmet theme:", listOf("Default", "Inline"), "Default"),
        )
        "ed_intentions" -> listOf(
            RowItem.Chk("int_enabled", "Enable intentions", def = true),
            RowItem.Chk("int_show_gutter", "Show intention icons in the gutter", def = true),
        )
        "ed_language_injections" -> listOf(
            RowItem.Chk("inj_enabled", "Enable language injections", def = true),
            RowItem.Chk("inj_comments", "Inject into comments", def = false),
        )
        "ed_live_edit" -> listOf(
            RowItem.Chk("le_enabled", "Enable live edit", def = false),
            RowItem.Cbo("le_delay", "Update delay (ms):",
                listOf("100", "300", "500"), "300"),
        )
        "ed_natural_languages" -> listOf(
            RowItem.Cbo("nl_spell", "Spell checker:",
                listOf("Disabled", "Typo detection only", "Typo and grammar"), "Typo detection only"),
            RowItem.Cbo("nl_dialect", "Spelling dialect:",
                listOf("American English", "British English", "Canadian English"), "American English"),
        )

        // Version Control
        "vc_changelists" -> listOf(
            RowItem.Chk("vc_single_changelist", "Use a single changelist", def = true),
            RowItem.Chk("vc_auto_changelist", "Create changelists automatically", def = false),
        )
        "vc_commit" -> listOf(
            RowItem.Chk("vc_show_unversioned", "Show unversioned files", def = true),
            RowItem.Chk("vc_reformat", "Reformat code before committing", def = false),
            RowItem.Chk("vc_optimize_imports", "Optimize imports before committing", def = false),
            RowItem.Cbo("vc_commit_order", "Order files by:",
                listOf("Default", "Name", "Path"), "Default"),
        )
        "vc_confirmation" -> listOf(
            RowItem.Cbo("vc_add", "When adding a file:",
                listOf("Ask", "Add silently", "Do not add"), "Ask"),
            RowItem.Cbo("vc_remove", "When deleting a file:",
                listOf("Ask", "Delete silently", "Do not delete"), "Ask"),
        )
        "vc_directory_mappings" -> listOf(
            RowItem.Nte("Directory mappings associate project directories with repositories."),
            RowItem.Chk("vc_auto_map", "Auto-detect repository roots", def = true),
        )
        "vc_file_status_colors" -> listOf(
            RowItem.Chk("vc_colors", "Show file status colors", def = true),
            RowItem.Cbo("vc_modified", "Modified:", listOf("#1E88E5", "#43A047", "#FB8C00"), "#1E88E5"),
            RowItem.Cbo("vc_added", "Added:", listOf("#43A047", "#1E88E5", "#8E24AA"), "#43A047"),
        )
        "vc_issue_navigation" -> listOf(
            RowItem.Nte("Issue navigation links commit messages to your tracker."),
            RowItem.Txt("vc_issue_url", "Issue URL pattern:", def = ""),
        )
        "vc_shelf" -> listOf(
            RowItem.Chk("vc_shelf_enabled", "Enable shelf", def = true),
            RowItem.Cbo("vc_shelf_format", "Shelf format:",
                listOf("Patch", "Working tree"), "Patch"),
        )
        "vc_github" -> listOf(
            RowItem.Nte("GitHub integration requires an account in Settings > Backup and Sync."),
            RowItem.Chk("gh_enabled", "Enable GitHub integration", def = false),
        )
        "vc_gitlab" -> listOf(
            RowItem.Nte("GitLab integration is available for merge request workflows."),
            RowItem.Chk("gl_enabled", "Enable GitLab integration", def = false),
        )
        "vc_mercurial" -> listOf(
            RowItem.Nte("Mercurial support is not bundled with this build."),
        )
        "vc_perforce" -> listOf(
            RowItem.Nte("Perforce support is not bundled with this build."),
        )
        "vc_subversion" -> listOf(
            RowItem.Nte("Subversion support is not bundled with this build."),
        )

        // Build, Execution, Deployment
        "b_coverage" -> listOf(
            RowItem.Cbo("cov_engine", "Coverage engine:",
                listOf("JaCoCo", "IntelliJ"), "JaCoCo"),
            RowItem.Chk("cov_enabled", "Enable coverage", def = false),
        )
        "b_debugger" -> listOf(
            RowItem.Chk("dbg_attach_auto", "Auto-attach debugger", def = false),
            RowItem.Cbo("dbg_timeout", "Connection timeout (s):",
                listOf("5", "10", "30"), "10"),
            RowItem.Chk("dbg_hotswap", "Allow hot swap on edit", def = true),
        )
        "b_deployment" -> listOf(
            RowItem.Cbo("dep_target", "Default deployment target:",
                listOf("Same as selected device", "Emulator", "USB device"), "Same as selected device"),
            RowItem.Chk("dep_install", "Install automatically on deploy", def = true),
        )
        "b_gradle_android_compiler" -> listOf(
            RowItem.Chk("gac_parallel", "Build in parallel", def = true),
            RowItem.Chk("gac_cache", "Use Gradle build cache", def = true),
            RowItem.Cbo("gac_console", "Console:",
                listOf("plain", "console", "rich"), "plain"),
        )
        "b_remote_repos" -> listOf(
            RowItem.Nte("Remote jar repositories are declared in the project build scripts."),
            RowItem.Chk("repo_offline", "Offline mode", def = false),
        )

        // Languages & Frameworks
        "l_mermaid" -> listOf(
            RowItem.Cbo("mm_theme", "Theme:",
                listOf("Follow IDE", "Light", "Dark", "Neutral", "Forest"), "Follow IDE"),
        )
        "kotlin_gradle" -> listOf(
            RowItem.Chk("kg_apply_kts", "Apply Kotlin script settings to .kts files", def = true),
            RowItem.Chk("kg_auto_sync", "Auto-sync on Gradle change", def = true),
        )
        "kotlin_style" -> listOf(
            RowItem.Cbo("ks_indent", "Tab size:", listOf("2", "4", "8"), "4"),
            RowItem.Cbo("ks_continuation", "Continuation indent:",
                listOf("4", "8", "16"), "8"),
            RowItem.Chk("ks_chain_wrap", "Method chain wrap:", def = false),
        )
        "experimental" -> listOf(
            RowItem.Nte("Experimental features may change or be removed in future builds."),
            RowItem.Chk("exp_editor_2", "Next-generation editor", def = false),
            RowItem.Chk("exp_compose_preview", "Compose preview v2", def = false),
            RowItem.Chk("exp_background_ai", "Background AI service", def = true),
        )
        "studio_labs" -> listOf(
            RowItem.Nte("Studio Labs collects opt-in prototypes. No data leaves the device."),
            RowItem.Chk("labs_enabled", "Enable Studio Labs features", def = false),
        )

        // Tools > Terminal
        "terminal" -> listOf(
            RowItem.Sec("Shell"),
            RowItem.Chk("term_confirm_exit", "Confirm on exit", def = true),
            RowItem.Chk("term_copy_on_select", "Copy on selection", def = false),
            RowItem.Chk("term_paste_on_middle", "Paste on middle click", def = false),
            RowItem.Sec("Appearance"),
            RowItem.Cbo("term_scheme", "Color scheme:",
                listOf("Darcula", "Dark", "Light"), "Darcula"),
            RowItem.Cbo("term_font_size", "Font size:",
                listOf("10sp", "12sp", "14sp", "16sp", "18sp"), "14sp"),
            RowItem.Chk("term_scrollback", "Save scrollback", def = true),
        )

        // Tools > Logcat
        "logcat" -> listOf(
            RowItem.Chk("logcat_show_timestamp", "Show timestamps", def = true),
            RowItem.Chk("logcat_show_level", "Show log level", def = true),
            RowItem.Cbo("logcat_level", "Minimum level:",
                listOf("Verbose", "Debug", "Info", "Warn", "Error"), "Verbose"),
            RowItem.Chk("logcat_filter_app", "Filter by app", def = true),
            RowItem.Txt("logcat_filter_text", "Filter text:", def = ""),
        )

        // Tools > Emulator
        "emulator" -> listOf(
            RowItem.Chk("emu_quick_boot", "Quick boot", def = true),
            RowItem.Chk("emu_snapshot", "Save snapshots", def = true),
            RowItem.Cbo("emu_network", "Network:",
                listOf("NAT", "Bridged", "Host-only"), "NAT"),
            RowItem.Txt("emu_avd", "Default AVD:", def = ""),
        )

        // Tools > Database Inspector
        "database_inspector" -> listOf(
            RowItem.Chk("db_auto_connect", "Auto-connect on open", def = true),
            RowItem.Chk("db_show_system", "Show system tables", def = false),
            RowItem.Cbo("db_limit", "Row limit:",
                listOf("100", "500", "1000", "5000"), "500"),
        )

        // Tools > Layout Inspector
        "layout_inspector" -> listOf(
            RowItem.Chk("li_live_updates", "Live updates", def = true),
            RowItem.Chk("li_show_attrs", "Show attributes", def = true),
            RowItem.Chk("li_show_ids", "Show resource IDs", def = false),
        )

        // Tools > Diff & Merge
        "diff_merge" -> listOf(
            RowItem.Cbo("diff_tool", "External diff tool:",
                listOf("Internal", "Meld", "KDiff3", "WinMerge"), "Internal"),
            RowItem.Chk("diff_ignore_ws", "Ignore whitespace", def = false),
            RowItem.Chk("diff_ignore_case", "Ignore case", def = false),
        )

        // Tools > CSV Formats
        "csv_formats" -> listOf(
            RowItem.Cbo("csv_separator", "Separator:",
                listOf("Comma", "Tab", "Semicolon", "Space"), "Comma"),
            RowItem.Cbo("csv_quote", "Quote character:",
                listOf("\"", "'", "None"), "\""),
            RowItem.Chk("csv_first_row_header", "First row is header", def = true),
        )

        // Tools > Actions on Save
        "actions_on_save" -> listOf(
            RowItem.Chk("aos_reformat", "Reformat code", def = false),
            RowItem.Chk("aos_optimize_imports", "Optimize imports", def = false),
            RowItem.Chk("aos_run_inspections", "Run inspections", def = false),
        )

        // Tools > App Links Assistant
        "app_links" -> listOf(
            RowItem.Chk("al_auto_verify", "Auto-verify links", def = true),
            RowItem.Txt("al_domain", "Domain:", def = ""),
            RowItem.Txt("al_path", "Path prefix:", def = ""),
        )

        // Tools > Google Accounts
        "google_accounts" -> listOf(
            RowItem.Nte("Google accounts for cloud services and publishing."),
            RowItem.Chk("ga_sync", "Sync with Google", def = false),
            RowItem.Txt("ga_account", "Account email:", def = ""),
        )

        // Tools > Gradle Declarative Focus
        "gradle_decl_focus" -> listOf(
            RowItem.Chk("gdf_enabled", "Enable declarative focus", def = true),
            RowItem.Cbo("gdf_scope", "Focus scope:",
                listOf("Project", "Module", "File"), "Project"),
        )

        // Tools > Screenshots & Screen Recordings
        "screenshots_recordings" -> listOf(
            RowItem.Cbo("sr_format", "Screenshot format:",
                listOf("PNG", "JPEG", "WebP"), "PNG"),
            RowItem.Cbo("sr_quality", "Quality:",
                listOf("Low", "Medium", "High", "Lossless"), "High"),
            RowItem.Chk("sr_show_cursor", "Show cursor", def = false),
        )

        // Languages & Frameworks > Data Editor
        "data_editor" -> listOf(
            RowItem.Chk("de_auto_save", "Auto-save changes", def = false),
            RowItem.Chk("de_show_null", "Show null values", def = true),
            RowItem.Cbo("de_page_size", "Page size:",
                listOf("50", "100", "200", "500"), "100"),
        )

        else -> listOf(
            RowItem.Nte(
                "This page has no configurable options in this build. Settings for it are " +
                    "stored with the rest of the IDE preferences.",
            ),
        )
    }

    // ------------------------------------------------------------
    // Help / Export / Import dialogs
    // ------------------------------------------------------------
    private fun showHelpDialog(page: SPage?) {
        val title = page?.label ?: "Settings"
        val body = buildString {
            appendLine(title)
            appendLine()
            if (page != null && page.desc.isNotBlank()) {
                appendLine(page.desc)
                appendLine()
            }
            appendLine("Use the tree on the left to browse settings. The search box filters the tree.")
            appendLine("The pin icon marks a page as your start page.")
            appendLine("Changes are staged until you press Apply or OK; Cancel discards them.")
        }
        AlertDialog.Builder(host)
            .setTitle("Settings Help")
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showExportSettingsDialog() {
        val all = prefs.all
        val sb = StringBuilder()
        all.entries.sortedBy { it.key }.forEach { (k, v) ->
            sb.append("$k=$v\n")
        }
        if (sb.isEmpty()) sb.append("(no settings stored yet)")
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            text = sb.toString()
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(0xFFE6E6E6.toInt())
            setBackgroundColor(0xFF1E1E22.toInt())
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        AlertDialog.Builder(host)
            .setTitle("Export Settings")
            .setMessage("Copy the settings below and save them to a file.")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showImportSettingsDialog() {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val input = EditText(this).apply {
            hint = "Paste settings (key=value per line)"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setMinLines(6)
        }
        AlertDialog.Builder(host)
            .setTitle("Import Settings")
            .setMessage("Paste exported settings, one key=value pair per line.")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) return@setPositiveButton
                val editor = prefs.edit()
                var count = 0
                for (line in text.lines()) {
                    val idx = line.indexOf('=')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim()
                        val value = line.substring(idx + 1).trim()
                        when (value) {
                            "true" -> editor.putBoolean(key, true)
                            "false" -> editor.putBoolean(key, false)
                            else -> {
                                val intVal = value.toIntOrNull()
                                when {
                                    intVal != null -> editor.putInt(key, intVal)
                                    value == "true" || value == "false" -> editor.putBoolean(key, value.toBoolean())
                                    else -> editor.putString(key, value)
                                }
                            }
                        }
                        count++
                    }
                }
                editor.apply()
                toast("Imported $count settings")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ------------------------------------------------------------
// Theme
// ------------------------------------------------------------
@Composable
internal fun MobileStudioTheme(content: @Composable () -> Unit) {
    // Re-resolve the shared palette on every composition (the theme revision is
    // observed by ThemeManager.ProvideTheme wrapping this screen) so the
    // settings window follows the user's selected Light/Dracula/Nord theme.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.remember(ThemeManager.themeRevision()) {
        val p = UiTheme.resolve(context)
        Palette.bg = Color(p.bg)
        Palette.panel = Color(p.surface)
        Palette.titleBar = Color(p.surface2)
        Palette.titleText = Color(p.textSoft)
        Palette.fieldBg = Color(p.surface2)
        Palette.border = Color(p.border)
        Palette.divider = Color(p.divider)
        Palette.selection = Color(p.selection)
        Palette.link = Color(p.accent)
        Palette.okBlue = Color(p.info)
        Palette.text = Color(p.text)
        Palette.dimText = Color(p.muted)
        Palette.desc = Color(p.textSoft)
        Palette.disabledText = Color(p.disabled)
        Palette.success = Color(p.success)
        Palette.error = Color(p.error)
    }
    MaterialTheme(
        colorScheme = if (UiTheme.resolve(context).isDark) darkColorScheme(
            primary = Palette.link,
            onPrimary = Color.White,
            surface = Palette.bg,
            background = Palette.bg,
            onSurface = Palette.text,
            onBackground = Palette.text,
        ) else lightColorScheme(
            primary = Palette.link,
            onPrimary = Color.White,
            surface = Palette.bg,
            background = Palette.bg,
            onSurface = Palette.text,
            onBackground = Palette.text,
        ),
        content = content,
    )
}

// ------------------------------------------------------------
// Palette (fixed dark theme matching the reference screenshots)
// ------------------------------------------------------------
internal object Palette {
    var bg = Color(0xFF2B2B2B)
    var panel = Color(0xFF3C3F41)
    var titleBar = Color(0xFF3C3F41)
    var titleText = Color(0xFFBBBBBB)
    var fieldBg = Color(0xFF45494A)
    var border = Color(0xFF5E6060)
    var divider = Color(0xFF5A5A5A)
    var selection = Color(0xFF2F6CC4)
    var link = Color(0xFF6BA5F0)
    var okBlue = Color(0xFF3B78C9)
    var text = Color(0xFFDDDDDD)
    var dimText = Color(0xFF9A9A9A)
    var desc = Color(0xFFB0B0B0)
    var disabledText = Color(0xFF777777)
    var success = Color(0xFF57965C)
    var error = Color(0xFFF75464)
}

/** Preference key holding the last visited settings page. */
internal const val KEY_LAST_PAGE = "settings_last_page"

/** Draft key prefix for values committed into BuildToolsConfig. */
private const val BTC_PREFIX = "btc:"
