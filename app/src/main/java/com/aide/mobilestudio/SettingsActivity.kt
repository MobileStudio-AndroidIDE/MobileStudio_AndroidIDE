package com.aide.mobilestudio

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.StudioUi
import java.io.File
import java.util.*

const val PREFS_NAME = "mobilestudio_settings"

class SettingsActivity : ComponentActivity() {
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private fun get(key: String, default: String): String = prefs.getString(key, default) ?: default
    private fun put(key: String, value: String) = prefs.edit().putString(key, value).apply()
    private fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)
    private fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    private fun getBool(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    private fun putBool(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    private fun isFavorite(id: String): Boolean = getBool("fav_$id", false)
    private fun toggleFavorite(id: String) {
        putBool("fav_$id", !getBool("fav_$id", false))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeName = get("theme", "Dark")
        val bgColor = when (themeName) {
            "Light" -> ComposeColor(0xFFF5F5F5)
            "Darcula" -> ComposeColor(0xFF2B2B2B)
            else -> ComposeColor(0xFF1E1E22) // Dark
        }
        setContent {
            MobileStudioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = bgColor) {
                    SettingsScreen()
                }
            }
        }
    }

    data class SettingsCategory(val id: String, val label: String, val subItems: List<SettingsItem> = emptyList())
    data class SettingsItem(val id: String, val label: String)

    private val settingsCategories = listOf(
        SettingsCategory("appearance", "Appearance & Behavior", listOf(
            SettingsItem("a1", "Appearance"),
            SettingsItem("a2", "Menus and Toolbars"),
            SettingsItem("a3", "System Settings"),
            SettingsItem("a4", "File Colors"),
            SettingsItem("a5", "Scopes"),
            SettingsItem("a6", "Notifications"),
            SettingsItem("a7", "Data Editor and Viewer"),
            SettingsItem("a8", "Quick Lists"),
            SettingsItem("a9", "Required Plugins"),
            SettingsItem("a10", "Trusted Locations"),
            SettingsItem("a11", "Path Variables"),
            SettingsItem("a12", "Presentation Assistant"),
        )),
        SettingsCategory("editor", "Editor", listOf(
            SettingsItem("e1", "General"),
            SettingsItem("e2", "Code Editing"),
            SettingsItem("e3", "Font"),
            SettingsItem("e4", "Color Scheme"),
            SettingsItem("e5", "Code Style"),
            SettingsItem("e6", "Inspections"),
            SettingsItem("e7", "File and Code Templates"),
            SettingsItem("e8", "File Encodings"),
            SettingsItem("e9", "Live Templates"),
            SettingsItem("e10", "File Types"),
            SettingsItem("e11", "UI Tools"),
            SettingsItem("e12", "Copyright"),
            SettingsItem("e13", "Inlay Hints"),
            SettingsItem("e14", "Emmet"),
            SettingsItem("e15", "Intentions"),
            SettingsItem("e16", "Language Injections"),
            SettingsItem("e17", "Live Edit"),
            SettingsItem("e18", "Natural Languages"),
        )),
        SettingsCategory("plugins", "Plugins"),
        SettingsCategory("version_control", "Version Control", listOf(
            SettingsItem("v1", "Changelists"),
            SettingsItem("v2", "Commit"),
            SettingsItem("v3", "Confirmation"),
            SettingsItem("v4", "Directory Mappings"),
            SettingsItem("v5", "File Status Colors"),
            SettingsItem("v6", "Issue Navigation"),
            SettingsItem("v7", "Shelf"),
            SettingsItem("v8", "Git"),
            SettingsItem("v9", "GitHub"),
            SettingsItem("v10", "GitLab"),
            SettingsItem("v11", "Mercurial"),
            SettingsItem("v12", "Perforce"),
            SettingsItem("v13", "Subversion"),
        )),
        SettingsCategory("build_deploy", "Build, Execution, Deployment", listOf(
            SettingsItem("b1", "Build Tools"),
            SettingsItem("b2", "Coverage"),
            SettingsItem("b3", "Debugger"),
            SettingsItem("b4", "Deployment"),
            SettingsItem("b5", "Gradle-Android Compiler"),
            SettingsItem("b6", "Remote Jar Repositories"),
        )),
        SettingsCategory("languages_frameworks", "Languages & Frameworks", listOf(
            SettingsItem("l1", "C/C++"),
            SettingsItem("l2", "Android SDK"),
            SettingsItem("l3", "JVM Logging"),
            SettingsItem("l4", "Kotlin"),
            SettingsItem("l5", "Markdown"),
            SettingsItem("l6", "Mermaid"),
            SettingsItem("l7", "Schemas and DTDs"),
            SettingsItem("l8", "Template Data Languages"),
        )),
        SettingsCategory("tools", "Tools", listOf(
            SettingsItem("t1", "Actions on Save"),
            SettingsItem("t2", "AI"),
            SettingsItem("t3", "App Links Assistant"),
            SettingsItem("t4", "CSV Formats"),
            SettingsItem("t5", "Database Inspector"),
            SettingsItem("t6", "Device Explorer"),
            SettingsItem("t7", "Device Mirroring"),
            SettingsItem("t8", "Diff & Merge"),
            SettingsItem("t9", "Emulator"),
            SettingsItem("t10", "External Tools"),
            SettingsItem("t11", "Google Accounts"),
            SettingsItem("t12", "Gradle Declarative Focus"),
            SettingsItem("t13", "Layout Inspector"),
            SettingsItem("t14", "Logcat"),
            SettingsItem("t15", "Screenshots & Screen Recordings"),
            SettingsItem("t16", "Startup Tasks"),
            SettingsItem("t17", "Terminal"),
        )),
        SettingsCategory("backup_sync", "Backup and Sync"),
        SettingsCategory("advanced", "Advanced Settings"),
    )

    @Composable
    private fun SettingsScreen() {
        var selectedCategory by remember { mutableStateOf(settingsCategories.first()) }
        var searchQuery by remember { mutableStateOf("") }

        val filteredCategories = remember(searchQuery) {
            settingsCategories.filter { cat ->
                cat.label.lowercase().contains(searchQuery.lowercase()) ||
                    cat.subItems.any { it.label.lowercase().contains(searchQuery.lowercase()) }
            }
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .background(ComposeColor(0xFF2A2A2E))
                    .verticalScroll(rememberScrollState())
            ) {
                // Header with Search
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Settings",
                        color = ComposeColor(0xFF82AAFF),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search settings", color = ComposeColor(0xFF82AAFF)) },
                        leadingIcon = { Text("🔍", color = ComposeColor(0xFF82AAFF)) },
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }
                Divider(color = ComposeColor(0xFF3A3A4A), modifier = Modifier.fillMaxWidth())
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp)
                ) {
                    items(filteredCategories) { cat ->
                        SettingsTreeItem(
                            item = cat,
                            isSelected = cat.id == selectedCategory.id,
                            onClick = { selectedCategory = cat },
                            searchQuery = searchQuery
                        )
                    }
                }
            }
            Divider(color = ComposeColor(0xFF3A3A4A), modifier = Modifier.fillMaxWidth().height(1.dp))
            SettingsContent(selectedCategory)
        }
    }

    @Composable
    private fun SettingsTreeItem(item: SettingsCategory, isSelected: Boolean, onClick: () -> Unit, searchQuery: String) {
        val isFav = isFavorite("cat_${item.id}")
        val bgColor = if (isSelected) ComposeColor(0xFF3A3A4A) else ComposeColor.Transparent
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp, horizontal = 4.dp)
                .background(bgColor)
                .clickable { onClick() }
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = if (isSelected) ComposeColor(0xFF82AAFF) else ComposeColor.White,
                    fontSize = 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 10.dp)
                )
                if (item.subItems.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.padding(start = 32.dp, end = 16.dp)) {
                        items(item.subItems) { sub ->
                            SettingsSubItem(sub, searchQuery)
                        }
                    }
                }
            }
            Text(
                text = if (isFav) "★" else "☆",
                color = if (isFav) ComposeColor(0xFFFFD54F) else ComposeColor(0xFF666666),
                fontSize = 16.sp,
                modifier = Modifier.clickable { toggleFavorite("cat_${item.id}") }.padding(vertical = 10.dp)
            )
        }
    }

    @Composable
    private fun SettingsSubItem(item: SettingsItem, searchQuery: String) {
        val highlightColor = ComposeColor(0xFFFFD54F)
        val isFav = isFavorite("sub_${item.id}")
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).background(ComposeColor.Transparent)) {
            Text(
                text = buildAnnotatedString {
                    append(item.label)
                    if (searchQuery.isNotEmpty() && item.label.lowercase().contains(searchQuery.lowercase())) {
                        val startIndex = item.label.lowercase().indexOf(searchQuery.lowercase())
                        addStyle(
                            style = SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold),
                            start = startIndex,
                            end = startIndex + searchQuery.length
                        )
                    }
                },
                color = ComposeColor(0xFFB0B0B0),
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp).weight(1f)
            )
            Text(
                text = if (isFav) "★" else "☆",
                color = if (isFav) ComposeColor(0xFFFFD54F) else ComposeColor(0xFF666666),
                fontSize = 14.sp,
                modifier = Modifier.clickable { toggleFavorite("sub_${item.id}") }.padding(vertical = 4.dp)
            )
        }
    }

    @Composable
    private fun SettingsContent(category: SettingsCategory) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                text = category.label,
                color = ComposeColor(0xFF82AAFF),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Divider(color = ComposeColor(0xFF3A3A4A), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(16.dp))
            when (category.id) {
                "appearance" -> AppearanceSection()
                "editor" -> EditorSection()
                "version_control" -> VersionControlSection()
                "build_deploy" -> BuildDeploySection()
                "languages_frameworks" -> LanguagesFrameworksSection(category.subItems)
                "tools" -> ToolsSection()
                "backup_sync" -> BackupSyncSection()
                "advanced" -> AdvancedSection()
                else -> {
                    Text(
                        text = "준비 중입니다.",
                        color = ComposeColor(0xFF888888),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }

    // ============================================================
    // Appearance & Behavior Section
    // ============================================================
    @Composable
    private fun AppearanceSection() {
        SectionHeader("Appearance & Behavior")
        SettingRow("Theme", get("theme", "Dark")) { showThemeDialog() }
        SettingRow("Color Scheme", get("color_scheme", "Darcula")) { showColorSchemeDialog() }
        SettingRow("Font Size", get("font_size", "14sp")) { showFontSizeDialog() }
        SettingRow("Font Family", get("font_family", "Monospace")) { showFontFamilyDialog() }
        SettingRow("File Colors", "") { showFileColorsDialog() }
        SettingRow("Scopes", "") { showScopesDialog() }
        SettingRow("Notifications", "") { showNotificationsDialog() }
        SettingRow("Data Editor and Viewer", "") { showDataEditorDialog() }
        SettingRow("Quick Lists", "") { showQuickListsDialog() }
        SettingRow("Required Plugins", "") { showRequiredPluginsDialog() }
        SettingRow("Trusted Locations", "") { showTrustedLocationsDialog() }
        SettingRow("Path Variables", "") { showPathVariablesDialog() }
        SettingRow("Presentation Assistant", "") { showPresentationAssistantDialog() }
    }

    private fun showFileColorsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("File Colors")
            .setMessage("Configure file color scopes for project files.")
            .setItems(arrayOf("Add New Scope...", "Edit Scope...", "Delete Scope")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "New file color scope", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit file color scope", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete file color scope", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScopesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Scopes")
            .setMessage("Define custom scopes for code inspections and highlighting.")
            .setItems(arrayOf("Add Scope...", "Edit Scope...", "Delete Scope")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "New scope created", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit scope", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete scope", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNotificationsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Notifications")
            .setMessage("Configure IDE notification settings.")
            .setItems(arrayOf("Balloon notifications", "Tool window notifications", "Log notifications")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Balloon notifications toggled", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Tool window notifications toggled", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Log notifications toggled", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDataEditorDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Data Editor and Viewer")
            .setMessage("Configure data editor and viewer settings.")
            .setItems(arrayOf("Data formatters", "CSV settings", "JSON settings", "XML settings")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Data formatters", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "CSV settings", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "JSON settings", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "XML settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQuickListsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Quick Lists")
            .setMessage("Manage quick lists of IDE actions.")
            .setItems(arrayOf("Add Quick List...", "Edit Quick List...", "Delete Quick List")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "New quick list", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit quick list", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete quick list", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRequiredPluginsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Required Plugins")
            .setMessage("Configure required plugins for the project.")
            .setItems(arrayOf("Add Plugin...", "Remove Plugin...", "Update Plugin")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add required plugin", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Remove required plugin", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Update required plugin", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTrustedLocationsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Trusted Locations")
            .setMessage("Manage trusted project locations.")
            .setItems(arrayOf("Add Location...", "Edit Location...", "Delete Location")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add trusted location", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit trusted location", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete trusted location", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPathVariablesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Path Variables")
            .setMessage("Configure path variables for project paths.")
            .setItems(arrayOf("Add Variable...", "Edit Variable...", "Delete Variable")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add path variable", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit path variable", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete path variable", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPresentationAssistantDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Presentation Assistant")
            .setMessage("Configure presentation mode settings.")
            .setItems(arrayOf("Enable Presentation Mode", "Font Size", "Tool Window Visibility")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Presentation mode enabled", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Font size adjusted", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Tool window visibility changed", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // Editor Section
    // ============================================================
    @Composable
    private fun EditorSection() {
        SectionHeader("Editor")
        SettingRow("General", "") { showEditorGeneralDialog() }
        SettingRow("Code Editing", "") { showCodeEditingDialog() }
        SettingRow("Font", "") { showEditorFontDialog() }
        SettingRow("Color Scheme", "") { showEditorColorSchemeDialog() }
        SettingRow("Code Style", "") { showCodeStyleDialog() }
        SettingRow("Inspections", "") { showInspectionsDialog() }
        SettingRow("File and Code Templates", "") { showFileTemplatesDialog() }
        SettingRow("File Encodings", "") { showFileEncodingsDialog() }
        SettingRow("Live Templates", "") { showLiveTemplatesDialog() }
        SettingRow("File Types", "") { showFileTypesDialog() }
        SettingRow("UI Tools", "") { showUIToolsDialog() }
        SettingRow("Copyright", "") { showCopyrightDialog() }
        SettingRow("Inlay Hints", "") { showInlayHintsDialog() }
        SettingRow("Emmet", "") { showEmmetDialog() }
        SettingRow("Intentions", "") { showIntentionsDialog() }
        SettingRow("Language Injections", "") { showLanguageInjectionsDialog() }
        SettingRow("Live Edit", "") { showLiveEditDialog() }
        SettingRow("Natural Languages", "") { showNaturalLanguagesDialog() }
    }

    private fun showEditorGeneralDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Editor General")
            .setMessage("General editor settings including line numbers, caret placement, and tabs.")
            .setItems(arrayOf("Line Numbers", "Caret Placement", "Tabs and Indents")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Line numbers", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Caret placement", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Tabs and indents", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCodeEditingDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Code Editing")
            .setMessage("Configure code editing options.")
            .setItems(arrayOf("Smart Keys", "Code Completion", "Inline Completion")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Smart keys", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Code completion", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Inline completion", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditorFontDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Editor Font")
            .setMessage("Select font family and size for the editor.")
            .setItems(arrayOf("Font Family", "Font Size", "Line Spacing")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Font family", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Font size", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Line spacing", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditorColorSchemeDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Color Scheme")
            .setMessage("Choose color scheme for editor highlighting.")
            .setItems(arrayOf("Darcula", "Light", "High Contrast", "Custom")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Darcula scheme", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Light scheme", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "High Contrast", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "Custom scheme", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCodeStyleDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Code Style")
            .setMessage("Configure code style settings per language.")
            .setItems(arrayOf("Kotlin", "Java", "XML", "JSON", "Groovy")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Kotlin style", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Java style", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "XML style", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "JSON style", Toast.LENGTH_SHORT).show()
                    4 -> Toast.makeText(this@SettingsActivity, "Groovy style", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInspectionsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Inspections")
            .setMessage("Configure code inspections and severity levels.")
            .setItems(arrayOf("Enable Inspections", "Severity Levels", "Inspection Profiles")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Enable inspections", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Severity levels", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Inspection profiles", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileTemplatesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("File and Code Templates")
            .setMessage("Configure file and code templates for new files.")
            .setItems(arrayOf("File Templates", "Code Templates", "Include Templates")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "File templates", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Code templates", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Include templates", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileEncodingsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("File Encodings")
            .setMessage("Configure file encoding settings.")
            .setItems(arrayOf("Global Encoding", "Project Encoding", "Default Encoding")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Global encoding", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Project encoding", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Default encoding", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLiveTemplatesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Live Templates")
            .setMessage("Configure live template abbreviations.")
            .setItems(arrayOf("Add Template...", "Edit Template...", "Delete Template")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add live template", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit live template", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete live template", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileTypesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("File Types")
            .setMessage("Configure recognized file types and extensions.")
            .setItems(arrayOf("Registered Types", "User Types", "Ignore Files")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Registered file types", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "User file types", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Ignore files and folders", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showUIToolsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("UI Tools")
            .setMessage("Configure UI-related editor tools.")
            .setItems(arrayOf("Distraction Free Mode", "Presentation Mode", "Zen Mode")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Distraction free mode", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Presentation mode", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Zen mode", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCopyrightDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Copyright")
            .setMessage("Configure copyright profiles for new files.")
            .setItems(arrayOf("Add Profile...", "Edit Profile...", "Delete Profile")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add copyright profile", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit copyright profile", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete copyright profile", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInlayHintsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Inlay Hints")
            .setMessage("Configure inlay parameter name hints.")
            .setItems(arrayOf("Enable Inlay Hints", "Exclude Types", "Exclude Values")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Enable inlay hints", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Exclude types", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Exclude values", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEmmetDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Emmet")
            .setMessage("Configure Emmet abbreviations for HTML/CSS.")
            .setItems(arrayOf("Enable Emmet", "Abbreviations", "Preview")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Enable Emmet", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Emmet abbreviations", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Emmet preview", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIntentionsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Intentions")
            .setMessage("Configure intention actions for quick fixes.")
            .setItems(arrayOf("Enable Intentions", "Intentions List", "Severity")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Enable intentions", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Intentions list", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Intentions severity", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLanguageInjectionsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Language Injections")
            .setMessage("Configure language injections for code fragments.")
            .setItems(arrayOf("Add Injection...", "Edit Injection...", "Delete Injection")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add language injection", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit language injection", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete language injection", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLiveEditDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Live Edit")
            .setMessage("Configure live edit for instant code updates.")
            .setItems(arrayOf("Enable Live Edit", "Update on Change", "Restart App")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Enable live edit", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Update on change", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Restart app", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNaturalLanguagesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Natural Languages")
            .setMessage("Configure natural language processing settings.")
            .setItems(arrayOf("Spell Checker", "Dictionary", "Language")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Spell checker", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Dictionary", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Natural language", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // Version Control Section
    // ============================================================
    @Composable
    private fun VersionControlSection() {
        SectionHeader("Version Control")
        SettingRow("Changelists", "") { showChangelistsDialog() }
        SettingRow("Commit", "") { showCommitDialog() }
        SettingRow("Confirmation", "") { showConfirmationDialog() }
        SettingRow("Directory Mappings", "") { showDirectoryMappingsDialog() }
        SettingRow("File Status Colors", "") { showFileStatusColorsDialog() }
        SettingRow("Issue Navigation", "") { showIssueNavigationDialog() }
        SettingRow("Shelf", "") { showShelfDialog() }
        SettingRow("Git", "") { showGitDialog() }
        SettingRow("GitHub", "") { showGitHubDialog() }
        SettingRow("GitLab", "") { showGitLabDialog() }
        SettingRow("Mercurial", "") { showMercurialDialog() }
        SettingRow("Perforce", "") { showPerforceDialog() }
        SettingRow("Subversion", "") { showSubversionDialog() }
    }

    private fun showChangelistsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Changelists")
            .setMessage("Configure changelist management settings.")
            .setItems(arrayOf("Default Changelist", "Create Changelist...", "Delete Changelist")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Default changelist", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Create changelist", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete changelist", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCommitDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Commit")
            .setMessage("Configure commit options and behavior.")
            .setItems(arrayOf("Commit Options", "Before Commit", "After Commit")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Commit options", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Before commit", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "After commit", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showConfirmationDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Confirmation")
            .setMessage("Configure confirmation dialogs for VCS operations.")
            .setItems(arrayOf("Delete Confirmation", "Revert Confirmation", "Force Push Confirmation")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Delete confirmation", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Revert confirmation", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Force push confirmation", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDirectoryMappingsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Directory Mappings")
            .setMessage("Map directories to VCS roots.")
            .setItems(arrayOf("Add Mapping...", "Edit Mapping...", "Delete Mapping")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add directory mapping", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit directory mapping", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete directory mapping", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileStatusColorsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("File Status Colors")
            .setMessage("Configure colors for file status indicators.")
            .setItems(arrayOf("Added", "Modified", "Deleted", "Ignored")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Added color", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Modified color", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Deleted color", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "Ignored color", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showIssueNavigationDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Issue Navigation")
            .setMessage("Configure issue navigation patterns.")
            .setItems(arrayOf("Add Pattern...", "Edit Pattern...", "Delete Pattern")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add issue pattern", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit issue pattern", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete issue pattern", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showShelfDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Shelf")
            .setMessage("Configure shelf for temporary changes.")
            .setItems(arrayOf("Shelf Life", "Auto-Shelf", "Shelf Location")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Shelf life", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Auto-shelf", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Shelf location", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGitDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Git")
            .setMessage("Configure Git executable path and settings.")
            .setItems(arrayOf("Git Path", "Git Executable", "SSH Executable")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Git path", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Git executable", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "SSH executable", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGitHubDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("GitHub")
            .setMessage("Configure GitHub account and authentication.")
            .setItems(arrayOf("Add Account...", "Edit Account...", "Remove Account")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add GitHub account", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit GitHub account", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Remove GitHub account", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGitLabDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("GitLab")
            .setMessage("Configure GitLab account and authentication.")
            .setItems(arrayOf("Add Account...", "Edit Account...", "Remove Account")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add GitLab account", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit GitLab account", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Remove GitLab account", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMercurialDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Mercurial")
            .setMessage("Configure Mercurial executable path and settings.")
            .setItems(arrayOf("HG Path", "HG Executable")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Mercurial path", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Mercurial executable", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPerforceDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Perforce")
            .setMessage("Configure Perforce (P4) connection settings.")
            .setItems(arrayOf("P4 Port", "P4 Client", "P4 User")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Perforce port", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Perforce client", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Perforce user", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubversionDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Subversion")
            .setMessage("Configure Subversion (SVN) executable and settings.")
            .setItems(arrayOf("SVN Path", "SVN Executable", "SSH Executable")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Subversion path", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Subversion executable", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "SSH executable", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // Build, Execution, Deployment Section
    // ============================================================
    @Composable
    private fun BuildDeploySection() {
        SectionHeader("Build, Execution, Deployment")
        SettingRow("Build Tools", "") { showBuildToolsDialog() }
        SettingRow("Coverage", "") { showCoverageDialog() }
        SettingRow("Debugger", "") { showDebuggerDialog() }
        SettingRow("Deployment", "") { showDeploymentDialog() }
        SettingRow("Gradle-Android Compiler", "") { showGradleAndroidCompilerDialog() }
        SettingRow("Remote Jar Repositories", "") { showRemoteJarRepositoriesDialog() }
    }

    private fun showBuildToolsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Build Tools")
            .setMessage("Configure build tools for the project.")
            .setItems(arrayOf("Maven", "Gradle", "Ant")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Maven build tools", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Gradle build tools", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Ant build tools", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCoverageDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Coverage")
            .setMessage("Configure code coverage measurement.")
            .setItems(arrayOf("Enable Coverage", "Coverage Data", "Coverage Suites")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Enable coverage", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Coverage data", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Coverage suites", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDebuggerDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Debugger")
            .setMessage("Configure debugger settings and behavior.")
            .setItems(arrayOf("Stepping", "Variables", "Frames")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Debugger stepping", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Debugger variables", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Debugger frames", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeploymentDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Deployment")
            .setMessage("Configure deployment options for applications.")
            .setItems(arrayOf("Deployment Target", "Deployment Options", "Server Settings")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Deployment target", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Deployment options", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Server settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGradleAndroidCompilerDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Gradle-Android Compiler")
            .setMessage("Configure Gradle and Android compiler settings.")
            .setItems(arrayOf("Gradle Settings", "Android Compiler", "Daemon Settings")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Gradle settings", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Android compiler", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Gradle daemon", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoteJarRepositoriesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Remote Jar Repositories")
            .setMessage("Manage remote JAR repository configurations.")
            .setItems(arrayOf("Add Repository...", "Edit Repository...", "Delete Repository")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add remote JAR repository", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit remote JAR repository", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete remote JAR repository", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // Languages & Frameworks Section
    // ============================================================
    @Composable
    private fun LanguagesFrameworksSection(subItems: List<SettingsItem>) {
        SectionHeader("Languages & Frameworks")
        subItems.forEach { item ->
            SettingRow(item.label, "") { when (item.id) {
                "l1" -> showCppDialog()
                "l2" -> showAndroidSdkDialog()
                "l3" -> showJvmLoggingDialog()
                "l4" -> showKotlinDialog()
                "l5" -> showMarkdownDialog()
                "l6" -> showMermaidDialog()
                "l7" -> showSchemasAndDtdsDialog()
                "l8" -> showTemplateDataLanguagesDialog()
                else -> logClick("Languages: ${item.label}")
            } }
        }
    }

    private fun showCppDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("C/C++")
            .setMessage("Configure C/C++ compiler and debugger settings.")
            .setItems(arrayOf("Compiler Path", "Debugger Path", "CMake Settings")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "C/C++ compiler path", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "C/C++ debugger path", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "CMake settings", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAndroidSdkDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Android SDK")
            .setMessage("Configure Android SDK location and components.")
            .setItems(arrayOf("SDK Path", "SDK Components", "SDK Update")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Android SDK path", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "SDK components", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "SDK update", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showJvmLoggingDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("JVM Logging")
            .setMessage("Configure JVM logging options.")
            .setItems(arrayOf("Log Level", "Log File", "Log Rotation")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "JVM log level", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "JVM log file", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Log rotation", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showKotlinDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Kotlin")
            .setMessage("Configure Kotlin compiler and settings.")
            .setItems(arrayOf("Kotlin Version", "Compiler Options", "Coroutines")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Kotlin version", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Kotlin compiler options", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Kotlin coroutines", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMarkdownDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Markdown")
            .setMessage("Configure Markdown editor settings.")
            .setItems(arrayOf("Preview Engine", "Default Layout", "Preview Font Size")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Markdown preview engine", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Default layout", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Preview font size", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMermaidDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Mermaid")
            .setMessage("Configure Mermaid diagram rendering.")
            .setItems(arrayOf("Theme", "Diagram Type", "Rendering Engine")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Mermaid theme", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Diagram type", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Rendering engine", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSchemasAndDtdsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Schemas and DTDs")
            .setMessage("Configure external schemas and DTDs.")
            .setItems(arrayOf("External Schemas", "Ignored Schemas", "Add Schema URL")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "External schemas", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Ignored schemas", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Add schema URL", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTemplateDataLanguagesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Template Data Languages")
            .setMessage("Configure template data language mappings.")
            .setItems(arrayOf("Add Mapping...", "Edit Mapping...", "Delete Mapping")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Add template mapping", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Edit template mapping", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Delete template mapping", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // Tools Section
    // ============================================================
    @Composable
    private fun ToolsSection() {
        SectionHeader("Tools")
        val tools = listOf(
            SettingsItem("t1", "Device Manager"),
            SettingsItem("t2", "SDK Manager"),
            SettingsItem("t3", "Resource Manager"),
            SettingsItem("t4", "AGP Upgrade Assistant..."),
            SettingsItem("t5", "Android SDK Upgrade Assistant"),
            SettingsItem("t6", "Troubleshoot Device Connections"),
            SettingsItem("t7", "App Links Assistant"),
            SettingsItem("t8", "Firebase"),
            SettingsItem("t9", "Layout Inspector"),
            SettingsItem("t10", "Generate Javadoc..."),
            SettingsItem("t11", "Create Command Line Launcher..."),
            SettingsItem("t12", "Services"),
            SettingsItem("t13", "Markdown"),
            SettingsItem("t14", "XML Actions"),
            SettingsItem("t15", "Cling"),
            SettingsItem("t16", "Groovy Console"),
            SettingsItem("t17", "Kotlin")
        )
        tools.forEach { tool ->
            SettingRow(tool.label, "") { when (tool.id) {
                "t1" -> showDeviceManagerDialog()
                "t2" -> showSdkManagerDialog()
                "t3" -> showResourceManagerDialog()
                "t4" -> showAgpUpgradeDialog()
                "t5" -> showAndroidSdkUpgradeDialog()
                "t6" -> showTroubleshootDeviceDialog()
                "t7" -> showAppLinksDialog()
                "t8" -> showFirebaseDialog()
                "t9" -> showLayoutInspectorDialog()
                "t10" -> showGenerateJavadocDialog()
                "t11" -> showCreateCliLauncherDialog()
                "t12" -> showServicesDialog()
                "t13" -> showToolsMarkdownDialog()
                "t14" -> showXmlActionsDialog()
                "t15" -> showClingDialog()
                "t16" -> showGroovyConsoleDialog()
                "t17" -> showToolsKotlinDialog()
                else -> logClick("Tools: ${tool.label}")
            } }
        }
    }

    private fun showDeviceManagerDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Device Manager")
            .setMessage("Manage connected Android devices.")
            .setItems(arrayOf("ADB Restart", "Device Scan", "Connection Test")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "ADB restart", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Device scan", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Connection test", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSdkManagerDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("SDK Manager")
            .setMessage("Manage Android SDK components.")
            .setItems(arrayOf("JDK 21 Download", "NDK Download", "Gradle Download", "Glibc Download")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "JDK 21 download", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "NDK download", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Gradle download", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "Glibc download", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showResourceManagerDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Resource Manager")
            .setMessage("Manage images, vectors, fonts, and strings.")
            .setItems(arrayOf("Images (drawable)", "Vectors (vector)", "Fonts (font)", "Strings (strings)")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Image resources", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Vector resources", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Font resources", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "String resources", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAgpUpgradeDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("AGP Upgrade Assistant")
            .setMessage("Upgrade Android Gradle Plugin version.")
            .setItems(arrayOf("8.0.0", "8.1.0", "8.2.0", "8.3.0")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "AGP 8.0.0 upgrade", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "AGP 8.1.0 upgrade", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "AGP 8.2.0 upgrade", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "AGP 8.3.0 upgrade", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAndroidSdkUpgradeDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Android SDK Upgrade Assistant")
            .setMessage("Update Android SDK tools.")
            .setItems(arrayOf("SDK Tools", "Platform-Tools", "Build-Tools", "Android API 34")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "SDK Tools update", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Platform-Tools update", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Build-Tools update", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "Android API 34 update", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTroubleshootDeviceDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Troubleshoot Device Connections")
            .setMessage("Diagnose ADB and device connection issues.")
            .setItems(arrayOf("ADB Scan", "Connection Test", "Log View")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "ADB scan", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Connection test", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Log view", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAppLinksDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("App Links Assistant")
            .setMessage("Verify and manage app links.")
            .setItems(arrayOf("Verify App Links", "Manage App Links", "Remove App Links")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Verify app links", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Manage app links", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Remove app links", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFirebaseDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Firebase")
            .setMessage("Connect to Firebase services.")
            .setItems(arrayOf("Project Connection", "Auth Setup", "Firestore Setup", "Cloud Messaging")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Firebase project connection", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Firebase Auth setup", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Firebase Firestore setup", Toast.LENGTH_SHORT).show()
                    3 -> Toast.makeText(this@SettingsActivity, "Firebase Cloud Messaging", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLayoutInspectorDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Layout Inspector")
            .setMessage("Inspect layout hierarchy.")
            .setPositiveButton("Open Inspector") { _, _ ->
                Toast.makeText(this@SettingsActivity, "Layout Inspector opened", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGenerateJavadocDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Generate Javadoc...")
            .setMessage("Generate API documentation from sources.")
            .setItems(arrayOf("app Module", "Library Module", "Whole Project")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Generate Javadoc: app module", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Generate Javadoc: library module", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Generate Javadoc: whole project", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateCliLauncherDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Create Command Line Launcher...")
            .setMessage("Generate shell launcher script.")
            .setView(EditText(this@SettingsActivity).apply { setText("studio") })
            .setPositiveButton("Generate") { _, _ ->
                Toast.makeText(this@SettingsActivity, "CLI launcher generated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showServicesDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Services")
            .setMessage("Manage background services.")
            .setItems(arrayOf("Service List", "Start Service", "Stop Service")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Service list", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Start service", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Stop service", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showToolsMarkdownDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Markdown")
            .setMessage("Configure Markdown editor.")
            .setItems(arrayOf("Open Markdown File", "Preview Settings", "Export to HTML")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Open Markdown file", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Preview settings", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Export to HTML", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }    private fun showXmlActionsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("XML Actions")
            .setMessage("Quick XML transformations.")
            .setItems(arrayOf("XML Formatting", "XML Validation", "JSON Conversion")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "XML formatting", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "XML validation", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "JSON conversion", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClingDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Cling")
            .setMessage("UPnP/discovery tools.")
            .setItems(arrayOf("UPnP Device Scan", "UPnP Service Discovery")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "UPnP device scan", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "UPnP service discovery", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroovyConsoleDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Groovy Console")
            .setMessage("Run Groovy scripts.")
            .setView(EditText(this@SettingsActivity).apply { setHint("println 'Hello, Groovy!'") })
            .setPositiveButton("Run") { _, _ ->
                Toast.makeText(this@SettingsActivity, "Groovy script executed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showToolsKotlinDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Kotlin REPL")
            .setMessage("Run Kotlin code.")
            .setView(EditText(this@SettingsActivity).apply { setHint("println(\"Hello, Kotlin!\")") })
            .setPositiveButton("Run") { _, _ ->
                Toast.makeText(this@SettingsActivity, "Kotlin code executed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // ============================================================
    // Backup and Sync Section
    // ============================================================
    @Composable
    private fun BackupSyncSection() {
        SectionHeader("Backup and Sync")
        SettingRow("Enable backup and sync", get("backup_email", "mobilestudio.aide@gmail.com")) {
            showBackupSyncDialog()
        }
    }

    // ============================================================
    // Advanced Settings Section
    // ============================================================
    @Composable
    private fun AdvancedSection() {
        SectionHeader("Advanced Settings")
        SettingRow("Attach scripts dependencies sources", "") { showAttachScriptsDialog() }
        SettingRow("Timeout to execute compiler (in seconds)", "60") { showCompilerTimeoutDialog() }
        SettingRow("Show all files in External Sources", "") { toggleSetting("external_sources", "Show all files in External Sources") }
        SettingRow("Use Clangd-based indexer", "") { toggleSetting("clangd_indexer", "Use Clangd-based indexer") }
        SettingRow("Keep obsolete ASTs in clangd", "") { toggleSetting("clangd_keep_asts", "Keep obsolete ASTs in clangd") }
        SettingRow("All-in-one Diff for Code Reviews", "") { toggleSetting("diff_all_in_one", "All-in-one Diff for Code Reviews") }
        SettingRow("Keep increased margins for Diff threads", "") { toggleSetting("diff_margins", "Keep increased margins for Diff threads") }
        SettingRow("Allow auto-make to start even if developed application is currently running", "") { toggleSetting("auto_make_running", "Allow auto-make while running") }
        SettingRow("Run compilation with lower priority", "") { toggleSetting("compile_lower_priority", "Run compilation with lower priority") }
        SettingRow("Enable unified Java/Kotlin incremental compilation implementation", "") { toggleSetting("unified_compilation", "Enable unified Java/Kotlin compilation") }
        SettingRow("Collect a detailed debug log report in case of build failures", "") { toggleSetting("debug_log_report", "Collect detailed debug log report") }
        SettingRow("Maximum number of recent expressions", "50") { showRecentExpressionsDialog() }
        SettingRow("Show inline Run to Cursor popup", "") { toggleSetting("inline_run_cursor", "Show inline Run to Cursor popup") }
        SettingRow("Basic syntax highlighting of inline code", "As plain code") { showInlineCodeHighlightDialog() }
        SettingRow("Basic syntax highlighting of multiline code blocks", "") { toggleSetting("multiline_highlight", "Basic syntax highlighting of multiline code blocks") }
        SettingRow("Syntax highlighting of links on code elements", "") { toggleSetting("link_highlight", "Syntax highlighting of links on code elements") }
        SettingRow("Add background to inline and multiline code blocks", "") { toggleSetting("code_background", "Add background to inline and multiline code blocks") }
        SettingRow("Show QuickDoc automatically during code completion in modal dialogs", "") { toggleSetting("quickdoc_auto", "Show QuickDoc automatically during code completion") }
        SettingRow("Use undo transparent caret movement", "") { toggleSetting("undo_caret", "Use undo transparent caret movement") }
        SettingRow("Export Settings", "") { showExportSettingsDialog() }
        SettingRow("Import Settings", "") { showImportSettingsDialog() }
    }

    private fun showAttachScriptsDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Attach scripts dependencies sources")
            .setMessage("Attach sources for build scripts dependencies during project import.")
            .setItems(arrayOf("Enabled", "Disabled")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Attach scripts sources enabled", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Attach scripts sources disabled", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCompilerTimeoutDialog() {
        val input = EditText(this@SettingsActivity).apply {
            setText(get("compiler_timeout", "60"))
            setHint("seconds")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Timeout to execute compiler (in seconds)")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                put("compiler_timeout", input.text.toString().trim())
                Toast.makeText(this@SettingsActivity, "Compiler timeout: ${input.text} seconds", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRecentExpressionsDialog() {
        val input = EditText(this@SettingsActivity).apply {
            setText(get("recent_expressions", "50"))
            setHint("expressions")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Maximum number of recent expressions")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                put("recent_expressions", input.text.toString().trim())
                Toast.makeText(this@SettingsActivity, "Recent expressions: ${input.text}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showInlineCodeHighlightDialog() {
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Basic syntax highlighting of inline code")
            .setItems(arrayOf("As plain code", "With syntax highlighting", "Disabled")) { _, which ->
                when (which) {
                    0 -> Toast.makeText(this@SettingsActivity, "Inline code: As plain code", Toast.LENGTH_SHORT).show()
                    1 -> Toast.makeText(this@SettingsActivity, "Inline code: With syntax highlighting", Toast.LENGTH_SHORT).show()
                    2 -> Toast.makeText(this@SettingsActivity, "Inline code: Disabled", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleSetting(key: String, label: String) {
        val current = getBool(key, false)
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle(label)
            .setPositiveButton("OK") { _, _ ->
                putBool(key, !current)
                val state = if (!current) "enabled" else "disabled"
                Toast.makeText(this@SettingsActivity, "$label: $state", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // Dialogs
    // ============================================================
    private fun showThemeDialog() {
        val items = arrayOf("Dark", "Light", "Darcula")
        val current = get("theme", "Dark")
        val selectedIndex = items.indexOf(current).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Theme")
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                put("theme", items[which])
                Toast.makeText(this@SettingsActivity, "테마: ${items[which]} (재시작 후 적용)", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showColorSchemeDialog() {
        val items = arrayOf("Darcula", "Dark", "Light")
        val current = get("color_scheme", "Darcula")
        val selectedIndex = items.indexOf(current).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Color Scheme")
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                put("color_scheme", items[which])
                Toast.makeText(this@SettingsActivity, "색상 체계: ${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFontSizeDialog() {
        val items = arrayOf("10sp", "12sp", "14sp", "16sp", "18sp", "20sp", "24sp")
        val current = get("font_size", "14sp")
        val selectedIndex = items.indexOf(current).takeIf { it >= 0 } ?: 2
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Font Size")
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                put("font_size", items[which])
                Toast.makeText(this@SettingsActivity, "글꼴 크기: ${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFontFamilyDialog() {
        val items = arrayOf("Monospace", "Sans-serif", "Serif", "System Default")
        val current = get("font_family", "Monospace")
        val selectedIndex = items.indexOf(current).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Font Family")
            .setSingleChoiceItems(items, selectedIndex) { _, which ->
                put("font_family", items[which])
                Toast.makeText(this@SettingsActivity, "글꼴: ${items[which]}", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBackupSyncDialog() {
        val emailInput = EditText(this@SettingsActivity).apply {
            setText(get("backup_email", "mobilestudio.aide@gmail.com"))
            setHint("mobilestudio.aide@gmail.com")
        }
        val enableCheck = android.widget.CheckBox(this@SettingsActivity).apply {
            text = "Enable backup and sync"
            isChecked = getBool("backup_enabled", false)
        }
        val layout = android.widget.LinearLayout(this@SettingsActivity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(android.widget.TextView(this@SettingsActivity).apply {
                text = "Sync UI, Code and System settings, Keymaps, Plugins, and Tools."
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
            })
            addView(android.widget.TextView(this@SettingsActivity).apply {
                text = "Google Account Email"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#82AAFF"))
            })
            addView(emailInput)
            addView(enableCheck)
        }
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Backup and Sync")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                put("backup_email", emailInput.text.toString().trim())
                putBool("backup_enabled", enableCheck.isChecked)
                Toast.makeText(this@SettingsActivity, "Backup settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logClick(msg: String) {
        android.util.Log.i("SettingsActivity", "click: $msg")
        Toast.makeText(this@SettingsActivity, "$msg (준비 중)", Toast.LENGTH_SHORT).show()
    }

    // ============================================================
    // Common UI Components
    // ============================================================
    @Composable
    private fun SectionHeader(text: String) {
        Text(
            text = text,
            color = ComposeColor(0xFF82AAFF),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)
        )
        Divider(color = ComposeColor(0xFF3A3A4A), modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
    }

    @Composable
    private fun SettingRow(title: String, value: String, onClick: () -> Unit) {
        val cardColor = ComposeColor(0xFF2A2A2E)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onClick() }
            ,
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = title,
                    color = ComposeColor.White,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f)
                )
                if (value.isNotEmpty()) {
                    Text(
                        text = value,
                        color = ComposeColor(0xFF82AAFF),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "›",
                    color = ComposeColor(0xFF666666),
                    fontSize = 16.sp,
                )
            }
        }
    }

    private fun showExportSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val all = prefs.all
        val sb = StringBuilder()
        all.entries.sortedBy { it.key }.forEach { (k, v) ->
            sb.append("$k=$v\n")
        }
        val density = this@SettingsActivity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val scroll = ScrollView(this@SettingsActivity)
        val tv = TextView(this@SettingsActivity).apply {
            text = sb.toString()
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(0xFFE6E6E6.toInt())
            setBackgroundColor(0xFF1E1E22.toInt())
            setTextIsSelectable(true)
        }
        scroll.addView(tv)
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Export Settings")
            .setMessage("Copy the settings below and save them to a file.")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showImportSettingsDialog() {
        val density = this@SettingsActivity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val input = EditText(this@SettingsActivity).apply {
            hint = "Paste settings (key=value per line)"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setMinLines(6)
        }
        AlertDialog.Builder(this@SettingsActivity)
            .setTitle("Import Settings")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isBlank()) return@setPositiveButton
                val editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
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
                                try {
                                    editor.putInt(key, value.toInt())
                                } catch (_: NumberFormatException) {
                                    try {
                                        editor.putFloat(key, value.toFloat())
                                    } catch (_: NumberFormatException) {
                                        editor.putString(key, value)
                                    }
                                }
                            }
                        }
                        count++
                    }
                }
                editor.apply()
                Toast.makeText(this@SettingsActivity, "Imported $count settings", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Composable
    private fun MobileStudioTheme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = ComposeColor(0xFF82AAFF),
                surface = ComposeColor(0xFF1E1E22),
                background = ComposeColor(0xFF1E1E22)
            )
        ) {
            content()
        }
    }
}
