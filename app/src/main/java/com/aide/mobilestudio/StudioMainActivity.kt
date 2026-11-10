package com.aide.mobilestudio

import android.graphics.Typeface
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree
import androidx.compose.runtime.remember
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.spring
import com.aide.mobilestudio.DownloadCenter
import com.aide.mobilestudio.LineNumberEditText
import com.aide.mobilestudio.LineLevel
import com.aide.mobilestudio.GradleRunner
import com.aide.mobilestudio.JreGradleInstaller
import com.aide.mobilestudio.ai.ui.AiWorkspaceActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

private val EditorDefaultContent = "fun main() {\n    println(\"hi\")\ngreet()\n}"
private val SampleMainCode = """
    package com.example.demo

    import java.util.Date

    /**
     * Sample project for MobileStudio.
     * Edit this file and press Save in the files menu.
     */
    fun main() {
        val now = Date()
        val message = "Hello from MobileStudio"
        println(message)
        println("Started at: " + now)
        greet()
    }
""".trimIndent()
private val SampleHelperCode = """
    package com.example.demo

    fun greet() {
        println("hi from Helper.kt")
    }

    fun add(a: Int, b: Int): Int {
        return a + b
    }
""".trimIndent()
private val MenuBarBg = Color(0xFF2B2D30)
private val StripLavender = Color(0xFFD7C8F0)
private val TabBarBg = Color.White
private val PathBarBorder = Color(0xFF616161)
private val EditorBg = Color.Black
private val LineNoGray = Color(0xFF9E9E9E)
private val CodeKeywordHex = "#6C7BFF"
private val CodeFuncHex = "#FFB388FF"
private val CodeStringHex = "#FF76FF03"
private val CodePlain = Color.White
private val ErrorRed = 0xFFFF5252.toInt()
private val PlayGreen = Color(0xFF43A047)
private val StripIconDark = Color(0xFF3C3F41)
private val TerminalBg = Color(0xFF0E1116)

object EditorPaneState {
    var content = EditorDefaultContent
    var unsaved = false
    var editor: LineNumberEditText? = null
}

class StudioMainActivity : AppCompatActivity() {
    private lateinit var gradleRunner: GradleRunner
    private lateinit var jreGradleInstaller: JreGradleInstaller
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    internal val pickDir = registerForActivityResult(OpenDocumentTree()) { uri ->
        uri?.let { dirUri ->
            contentResolver.takePersistableUriPermission(dirUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val resolved = resolveTreeUriDir(dirUri)
            if (resolved != null) {
                openProject(resolved)
                putLastProject(resolved.absolutePath)
            }
        }
    }

    var currentProjectDir: File? by mutableStateOf(null)
    var currentFile: File? = null
    var pathText: String by mutableStateOf("")
    var fileContent: String by mutableStateOf("")
    val treeItems = mutableStateListOf<FileItem>()
    var selectedTab by mutableStateOf(0)
    var tabs: MutableList<String> = mutableListOf()
    var editorProblems: MutableList<EditorProblem> = mutableListOf()

    /** The in-window Tools panel currently hosting a file picker, if any. */
    internal var toolsPanelForResult: ToolsPanel? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        toolsPanelForResult?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 마지막으로 연 프로젝트 복원
        val lastProject = getLastProject()
        if (lastProject != null && File(lastProject).isDirectory) {
            openProject(File(lastProject))
        } else {
            // 프로젝트가 없으면 New Project 다이얼로그 표시
            showNewProjectDialog(this@StudioMainActivity)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = MenuBarBg)) {
                StudioApp(this)
            }
        }
    }

    fun getLastProject(): String? {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        return prefs.getString("last_project", null)
    }

    internal fun putLastProject(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("last_project", path).apply()
        addRecentProject(path)
    }

    /** Returns up to 20 recently opened files (most recent first). */
    fun getRecentFiles(): List<String> {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_files", "") ?: ""
        return if (json.isBlank()) emptyList() else json.split("|").filter { it.isNotEmpty() }
    }

    /** Adds a file to the recent list (max 20). */
    fun addRecentFile(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_files", "") ?: ""
        val recent = if (json.isBlank()) mutableListOf<String>() else json.split("|").filter { it.isNotEmpty() }.toMutableList()
        recent.remove(path)
        recent.add(0, path)
        if (recent.size > 20) recent.removeAt(recent.size - 1)
        prefs.edit().putString("recent_files", recent.joinToString("|")).apply()
    }

    /** Returns up to 10 recently opened projects (most recent first). */
    fun getRecentProjects(): List<String> {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_projects", "") ?: ""
        return if (json.isBlank()) emptyList() else json.split("|").filter { it.isNotEmpty() }
    }

    /** Adds a project to the recent list (max 10). */
    fun addRecentProject(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val recent = getRecentProjects().toMutableList()
        recent.remove(path)
        recent.add(0, path)
        if (recent.size > 10) recent.removeAt(recent.size - 1)
        prefs.edit().putString("recent_projects", recent.joinToString("|")).apply()
    }

    private fun seedSampleProject() {
        val root = File(filesDir, "workspace")
        val main = File(root, "MainActivity.kt")
        if (main.exists()) return
        try {
            root.mkdirs()
            main.writeText(SampleMainCode)
            val utils = File(root, "utils").apply { mkdirs() }
            File(utils, "Helper.kt").writeText(SampleHelperCode)
            File(root, "README.md").writeText("# MobileStudio sample project\n\nOpen files from the drawer.")
        } catch (_: Exception) { }
    }

    @Composable
    private fun StudioApp(activity: StudioMainActivity) {
        val installer: JreGradleInstaller = remember(activity) {
            JreGradleInstaller(activity, { }, { })
        }
        val runner: GradleRunner = remember(activity) {
            GradleRunner(activity, installer)
        }
        activity.gradleRunner = runner
        activity.jreGradleInstaller = installer

        var centerPane by remember { mutableStateOf("editor") }
        var drawerOpen by remember { mutableStateOf(false) }
        var drawerMode by remember { mutableStateOf("projects") } // "projects" or "files"
        var showDownloadManager by remember { mutableStateOf(false) }
        var bottomPanelOpen by remember { mutableStateOf(true) }
        var bottomPanelHeight by remember { mutableStateOf(240.dp) }
        var editorProblems by remember { mutableStateOf<MutableList<EditorProblem>>(mutableListOf()) }
        var showSettingsOverlay by remember { mutableStateOf(false) }
        var showToolsOverlay by remember { mutableStateOf(false) }
        var toolsInitialTab by remember { mutableStateOf("sdk_manager") }
        val logLines = remember { mutableStateListOf<LogLine>() }
        val context: Context = LocalContext.current

        // Attach/detach DownloadCenter to this UI
        DisposableEffect(Unit) {
            DownloadCenter.attach()
            onDispose { DownloadCenter.detach() }
        }

        // Observe DownloadCenter state to auto-show window when a download starts
        val downloadTask by remember { derivedStateOf { DownloadCenter.state.value } }
        LaunchedEffect(downloadTask) {
            val task = downloadTask
            if (task != null && task.status != DownloadCenter.Status.DONE && task.status != DownloadCenter.Status.FAILED && task.status != DownloadCenter.Status.CANCELLED) {
                showDownloadManager = true
            }
        }

        LaunchedEffect(Unit) {
            val f = File(activity.currentProjectDir ?: activity.filesDir, activity.tabs.firstOrNull() ?: "MainActivity.kt")
            if (f.exists()) {
                val existing = f.readText()
                if (existing.isNotEmpty()) {
                    activity.fileContent = existing
                    EditorPaneState.content = existing
                }
            }
        }

        fun startBuild() {
            centerPane = "buildlog"
            logLines.add(LogLine("> Running :app:assembleDebug ...", 0xFF1A1A1A.toInt()))
            runner.run(
                projectDir = activity.currentProjectDir ?: activity.filesDir,
                tasks = listOf("assembleDebug"),
                onLine = { line, level ->
                    logLines.add(LogLine(line, if (level == LineLevel.ERROR) ErrorRed else 0xFF1A1A1A.toInt()))
                },
                onDone = { result ->
                    logLines.add(LogLine("BUILD ${if (result.success) "OK" else "FAILED"} (${result.durationMs}ms)", 0xFFB388FF.toInt()))
                }
            )
        }

        fun saveCurrent() {
            activity.scope.launch { activity.saveCurrentFile() }
            EditorPaneState.unsaved = false
            Toast.makeText(context, "Saved: ${activity.currentFile?.name ?: "file"}", Toast.LENGTH_SHORT).show()
        }

        fun showFindDialog() {
            val editor = EditorPaneState.editor ?: return
            val input = EditText(context).apply {
                hint = "Find text"
                setSingleLine(true)
            }
            AlertDialog.Builder(context)
                .setTitle("Find")
                .setView(input)
                .setPositiveButton("Find") { _, _ ->
                    val query = input.text.toString()
                    val text = editor.text?.toString() ?: ""
                    val idx = if (query.isEmpty()) -1 else text.indexOf(query)
                    if (idx >= 0) {
                        editor.setSelection(idx, idx + query.length)
                        editor.requestFocus()
                    } else {
                        Toast.makeText(context, "Not found: $query", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun showGoToLineDialog() {
            val editor = EditorPaneState.editor ?: return
            val input = EditText(context).apply {
                hint = "Line number"
                setSingleLine(true)
            }
            AlertDialog.Builder(context)
                .setTitle("Go to Line")
                .setView(input)
                .setPositiveButton("Go") { _, _ ->
                    val line = input.text.toString().toIntOrNull()
                    val layout = editor.layout
                    if (line != null && layout != null && line in 1..layout.lineCount) {
                        val offset = layout.getLineStart(line - 1)
                        editor.setSelection(offset)
                        editor.requestFocus()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun toggleComment() {
            val editor = EditorPaneState.editor ?: return
            val text = editor.text ?: return
            val selStart = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val selEnd = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
            val lineStart = text.lastIndexOf('\n', (selStart - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
            val lineEnd = text.indexOf('\n', selEnd).let { if (it < 0) text.length else it }
            val lines = text.subSequence(lineStart, lineEnd).toString().split("\n")
            val allCommented = lines.filter { it.isNotBlank() }.all { it.trimStart().startsWith("//") }
            val updated = lines.joinToString("\n") { line ->
                when {
                    line.isBlank() -> line
                    allCommented -> line.replaceFirst("// ", "").replaceFirst("//", "")
                    else -> "// " + line
                }
            }
            text.replace(lineStart, lineEnd, updated)
        }

        fun newFileDialog() {
            val input = EditText(context).apply {
                hint = "File name (e.g. Screen.kt)"
                setSingleLine(true)
            }
            AlertDialog.Builder(context)
                .setTitle("New File")
                .setView(input)
                .setPositiveButton("Create") { _, _ ->
                    val name = input.text.toString().trim().ifBlank { "Untitled.kt" }
                    activity.newEditorTab(name)
                    centerPane = "editor"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        fun openTool(tabId: String) {
            toolsInitialTab = tabId
            showToolsOverlay = true
        }

        fun performAction(action: String) {
            when (action) {
                "new_tab" -> newFileDialog()
                "new_project" -> FileManagerActivity.start(context, projectMode = true, isNewProject = true)
                "new_file" -> showNewFileDialog(activity)
                "new_folder" -> showNewFolderDialog(activity)
                "open_project" -> FileManagerActivity.start(context, projectMode = true)
                "save", "save_all" -> saveCurrent()
                "close_tab" -> {
                    activity.closeEditorTab(activity.selectedTab)
                    centerPane = "editor"
                }
                "close_all_tabs" -> {
                    activity.tabs.clear()
                    activity.selectedTab = -1
                    centerPane = "editor"
                    Toast.makeText(context, "All tabs closed", Toast.LENGTH_SHORT).show()
                }
                "exit" -> activity.finish()
                "undo" -> EditorPaneState.editor?.undo()
                "redo" -> EditorPaneState.editor?.redo()
                "cut" -> EditorPaneState.editor?.onTextContextMenuItem(android.R.id.cut)
                "copy" -> EditorPaneState.editor?.onTextContextMenuItem(android.R.id.copy)
                "paste" -> EditorPaneState.editor?.onTextContextMenuItem(android.R.id.paste)
                "select_all" -> EditorPaneState.editor?.onTextContextMenuItem(android.R.id.selectAll)
                "find" -> showFindDialog()
                "find_replace" -> EditorPaneState.editor?.showFindReplaceDialog()
                "find_in_files" -> {
                    val activity = context as? StudioMainActivity
                    if (activity != null) showFindInFilesDialog(activity)
                }
                "pane_editor" -> centerPane = "editor"
                "pane_buildlog" -> centerPane = "buildlog"
                "pane_terminal" -> centerPane = "terminal"
                "drawer" -> drawerOpen = true
                "next_tab" -> {
                    if (activity.tabs.isNotEmpty()) {
                        activity.selectTab((activity.selectedTab + 1) % activity.tabs.size)
                        centerPane = "editor"
                    }
                }
                "prev_tab" -> {
                    if (activity.tabs.isNotEmpty()) {
                        activity.selectTab((activity.selectedTab - 1 + activity.tabs.size) % activity.tabs.size)
                        centerPane = "editor"
                    }
                }
                "goto_line" -> showGoToLineDialog()
                "comment" -> toggleComment()
                "reformat" -> Toast.makeText(context, "Reformat Code is not available yet", Toast.LENGTH_SHORT).show()
                "optimize" -> Toast.makeText(context, "Optimize Imports is not available yet", Toast.LENGTH_SHORT).show()
                "run" -> startBuild()
                "downloads" -> {
                    showDownloadManager = true
                    DownloadCenter.showIdleWindow()
                }
                // Android Studio Tools menu items
                "device_manager" -> openTool("device_manager")
                "sdk_manager" -> openTool("sdk_manager")
                "resource_manager" -> openTool("resource_manager")
                "agp_upgrade" -> openTool("agp_upgrade")
                "android_sdk_upgrade" -> openTool("android_sdk_upgrade")
                "troubleshoot_device" -> openTool("troubleshoot_device")
                "app_links" -> openTool("app_links")
                "firebase" -> openTool("firebase")
                "layout_inspector" -> openTool("layout_inspector")
                "generate_javadoc" -> openTool("generate_javadoc")
                "create_cli_launcher" -> openTool("create_cli_launcher")
                "services" -> openTool("services")
                "markdown" -> openTool("markdown")
                "xml_actions" -> openTool("xml_actions")
                "cling" -> openTool("cling")
                "groovy_console" -> openTool("groovy_console")
                "kotlin" -> openTool("kotlin")
                "file_manager" -> FileManagerActivity.start(context)
                "git" -> {
                    val activity = context as? StudioMainActivity
                    val projDir = activity?.currentProjectDir?.absolutePath
                    if (projDir != null) GitActivity.start(context, projDir)
                    else Toast.makeText(context, "Open a project first", Toast.LENGTH_SHORT).show()
                }
                "recent_projects" -> {
                    val activity = context as? StudioMainActivity
                    if (activity != null) showRecentProjectsDialog(activity)
                    else Toast.makeText(context, "Unable to open recent projects", Toast.LENGTH_SHORT).show()
                }
                "recent_files" -> {
                    val activity = context as? StudioMainActivity
                    if (activity != null) showRecentFilesDialog(activity)
                    else Toast.makeText(context, "Unable to open recent files", Toast.LENGTH_SHORT).show()
                }
                "terminal" -> centerPane = "terminal"
                "assistant" -> context.startActivity(Intent(context, AiWorkspaceActivity::class.java))
                "settings" -> showSettingsOverlay = true
                "about" -> AlertDialog.Builder(context)
                    .setTitle("About MobileStudio")
                    .setMessage("MobileStudio IDE\nAIDE-style Android IDE rebuilt with Jetpack Compose.\n\nEditor, Gradle build, terminal, file tree and AI assistant are wired in.")
                    .setPositiveButton("OK", null)
                    .show()
                else -> Toast.makeText(context, action, Toast.LENGTH_SHORT).show()
            }
        }

        BackHandler(enabled = drawerOpen) { drawerOpen = false }

        Box(modifier = Modifier.fillMaxSize().background(MenuBarBg)) {
            Column(modifier = Modifier.fillMaxSize().background(MenuBarBg)) {
                TopMenuBar(
                    activity = LocalContext.current as Activity,
                    onRun = { startBuild() },
                    onAction = { performAction(it) }
                )
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (drawerOpen) {
                        LeftStrip(
                            context = context,
                            onFolder = { activity.pickDir.launch(null) },
                            onBuildLog = { centerPane = "buildlog" },
                            onTerminal = { centerPane = "terminal" },
                            onIde = { context.startActivity(Intent(context, AiWorkspaceActivity::class.java)) },
                            onProjectOpen = { activity.pickDir.launch(null) },
                        )
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        val showBottomPanel = bottomPanelOpen && centerPane != "editor"
                        if (showBottomPanel) {
                            BottomPanel(
                                activity = activity,
                                pane = centerPane,
                                logLines = logLines,
                                height = bottomPanelHeight,
                                onHeightChange = { bottomPanelHeight = it },
                            )
                        } else {
                            when (centerPane) {
                                "buildlog" -> BuildLogPane(logLines)
                                "terminal" -> TerminalPane(activity = activity)
                                else -> EditorPane(
                                    activity = activity,
                                    tabs = activity.tabs,
                                    selectedTab = activity.selectedTab,
                                    onTabChange = { idx -> activity.selectTab(idx) },
                                    pathText = activity.pathText,
                                    fileContent = activity.fileContent,
                                    problems = editorProblems,
                                    onProblemFix = { idx ->
                                        editorProblems.removeAt(idx)
                                        activity.selectTab(idx)
                                        centerPane = "editor"
                                    },
                                    onContentChange = {
                                        activity.fileContent = it
                                        EditorPaneState.content = it
                                        EditorPaneState.unsaved = true
                                        activity.scope.launch { activity.saveCurrentFile() }
                                    },
                                    onNewTab = { name ->
                                        activity.newEditorTab(name)
                                        centerPane = "editor"
                                    },
                                    onCloseTab = { idx -> activity.closeEditorTab(idx) },
                                    onOpenProject = { activity.pickDir.launch(null) },
                                    onSaveCurrent = { saveCurrent() },
                                )
                            }
                        }
                        if (!showBottomPanel) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(end = 10.dp, bottom = 10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(22.dp))
                                        .background(Color(0xFF3C3F41))
                                        .clickable {
                                            bottomPanelOpen = true
                                            centerPane = "terminal"
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("▾", color = Color.White, fontSize = 18.sp)
                                }
                            }
                        }
                    }
                }
            }

            if (drawerOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0x99000000))
                        .clickable { drawerOpen = false }
                )
                FileDrawer(
                    activity = activity,
                    mode = drawerMode,
                    onClose = { drawerOpen = false },
                    onPickFile = { file ->
                        activity.openFileInEditor(file)
                        centerPane = "editor"
                        drawerMode = "projects"
                        drawerOpen = false
                    },
                    onOpenProject = { activity.pickDir.launch(null) },
                    onRefresh = { activity.loadFileTree(activity.currentProjectDir ?: activity.filesDir) },
                    onSwitchToFiles = { drawerMode = "files" },
                    onSwitchToProjects = { drawerMode = "projects" },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }

            if (showDownloadManager) {
                DownloadManagerWindow(
                    activity = activity,
                    onDismiss = { showDownloadManager = false }
                )
            }

            // Android Studio style in-window tool windows. These deliberately
            // live in this activity instead of launching a second activity, so
            // the editor and project stay visible (dimmed) behind the window.
            if (showToolsOverlay) {
                ToolsWindowOverlay(
                    activity = activity,
                    initialTab = toolsInitialTab,
                    onClose = { showToolsOverlay = false },
                )
            }
            if (showSettingsOverlay) {
                SettingsWindowOverlay(
                    activity = activity,
                    onClose = { showSettingsOverlay = false },
                )
            }
        }
    }

    private fun resolveTreeUriDir(uri: Uri): File? {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            if (parts.size >= 2) {
                val relative = parts.last()
                val primary = Environment.getExternalStorageDirectory()
                val f = File(primary, relative)
                if (f.exists()) return f
                val f2 = File(filesDir, relative)
                if (f2.exists()) return f2
            }
        } catch (_: Exception) { }
        return null
    }

    fun openProject(dir: File) {
        saveCurrentFile()
        currentProjectDir = dir
        tabs.clear()
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            if (f.extension == "kt" || f.extension == "java" || f.extension == "xml") tabs.add(f.name)
        }
        if (tabs.isEmpty()) tabs.add("MainActivity.kt")
        selectedTab = 0
        pathText = tabs[0]
        loadFileContentForTab(tabs[0])
        loadFileTree(dir)
        Toast.makeText(this, "Project opened: ${dir.name}", Toast.LENGTH_SHORT).show()
    }

    fun loadFileContentForTab(tabName: String) {
        val f = currentProjectDir?.let { File(it, tabName) } ?: File(filesDir, tabName)
        currentFile = f
        if (f.exists()) {
            val content = f.readText()
            fileContent = content
            EditorPaneState.content = content
            EditorPaneState.unsaved = false
        } else {
            fileContent = EditorDefaultContent
            EditorPaneState.content = EditorDefaultContent
            EditorPaneState.unsaved = false
        }
    }

    fun saveCurrentFile() {
        if (tabs.isEmpty()) return
        val file = currentFile ?: File(currentProjectDir ?: filesDir, tabs[selectedTab])
        currentFile = file
        try {
            scope.launch(Dispatchers.IO) {
                file.writeText(EditorPaneState.content)
                withContext(Dispatchers.Main) {
                    EditorPaneState.unsaved = false
                    fileContent = EditorPaneState.content
                }
            }
        } catch (e: Exception) {
        }
    }

    fun loadFileTree(root: File) {
        treeItems.clear()
        root.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { child -> treeItems += FileItem(child, 0, false) }
    }

    fun toggleTreeFolder(dir: File) {
        val idx = treeItems.indexOfFirst { it.file.absolutePath == dir.absolutePath }
        if (idx < 0 || !dir.isDirectory) return
        val item = treeItems[idx]
        if (item.isExpanded) {
            treeItems[idx] = item.copy(isExpanded = false)
            var i = idx + 1
            while (i < treeItems.size && treeItems[i].depth > item.depth) {
                treeItems.removeAt(i)
            }
        } else {
            treeItems[idx] = item.copy(isExpanded = true)
            val children = dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: return
            children.forEachIndexed { offset, child ->
                treeItems.add(idx + 1 + offset, FileItem(child, item.depth + 1, false))
            }
        }
    }

    fun openFileInEditor(file: File) {
        addRecentFile(file.absolutePath)
        val root = currentProjectDir ?: filesDir
        val rel = try {
            file.relativeTo(root).path
        } catch (_: Exception) {
            file.name
        }
        val existing = tabs.indexOf(rel)
        if (existing >= 0) {
            selectTab(existing)
            return
        }
        saveCurrentFile()
        tabs.add(rel)
        selectedTab = tabs.lastIndex
        pathText = rel
        loadFileContentForTab(rel)
    }

    fun newEditorTab(name: String) {
        val root = currentProjectDir ?: filesDir
        val file = File(root, name)
        try {
            file.parentFile?.mkdirs()
            if (!file.exists()) file.writeText("")
        } catch (_: Exception) { }
        tabs.add(name)
        selectedTab = tabs.lastIndex
        pathText = name
        fileContent = ""
        EditorPaneState.content = ""
        EditorPaneState.unsaved = false
        loadFileTree(root)
    }

    fun closeEditorTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) tabs.add("MainActivity.kt")
        selectedTab = selectedTab.coerceAtMost(tabs.lastIndex)
        pathText = tabs[selectedTab]
        loadFileContentForTab(tabs[selectedTab])
    }

    fun selectTab(index: Int) {
        if (index !in tabs.indices) return
        selectedTab = index
        pathText = tabs[index]
        loadFileContentForTab(tabs[index])
    }

    data class LogLine(val text: String, val color: Int)
}

@Composable
private fun EditorPane(
    activity: StudioMainActivity,
    tabs: MutableList<String>,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    pathText: String,
    fileContent: String,
    onContentChange: (String) -> Unit,
    onNewTab: (String) -> Unit,
    onCloseTab: (Int) -> Unit,
    onOpenProject: () -> Unit,
    onSaveCurrent: () -> Unit,
    problems: List<EditorProblem>,
    onProblemFix: (Int) -> Unit,
) {
    val autoCompleteRef = remember { mutableStateOf<KotlinAutoComplete?>(null) }
    Column(modifier = Modifier.fillMaxSize().background(TabBarBg)) {
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(TabBarBg)) {
            for (i in tabs.indices) {
                EditorTab(title = tabs[i], active = selectedTab == i,
                    onClick = { onTabChange(i) },
                    onClose = { onCloseTab(i) })
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("+", color = Color(0xFF3C3F41), fontSize = 18.sp,
                modifier = Modifier.padding(start = 12.dp).clickable { onNewTab("Untitled.kt") })
        }
        Text(
            text = pathText.takeIf { it.isNotEmpty() } ?: "",
            color = Color.Black, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().background(Color.White).border(1.5.dp, PathBarBorder)
                .padding(horizontal = 8.dp, vertical = 5.dp)
        )
        Column(modifier = Modifier.weight(1f).fillMaxWidth().background(EditorBg)) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, top = 10.dp, end = 8.dp),
                factory = { context ->
                    LineNumberEditText(context).apply {
                        EditorPaneState.editor = this
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        setTextColor(android.graphics.Color.WHITE)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 17f)
                        typeface = android.graphics.Typeface.MONOSPACE
                        tag = "loading"
                        setText(fileContent)
                        setSelection(0)
                        scrollTo(0, 0)
                        tag = "editor"
                        text?.let { KotlinHighlighter.highlight(it, pathText) }
                        // Real-time compile check: live syntax error pass driven by
                        // the editor's text watcher (debounced inside the highlighter).
                        KotlinHighlighter.attachTo(this, pathText, suppressCheck = { tag == "loading" }) { errors ->
                            // Real-time compile errors from KotlinHighlighter
                            val newProblems = errors.map { e ->
                                EditorProblem(line = e.line, message = e.message, severity = "ERROR")
                            }
                            val ep: MutableList<EditorProblem> = activity.editorProblems
                            ep.clear()
                            ep.addAll(newProblems)
                        }
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: android.text.Editable) {
                                if (tag == "loading") return
                                val text = s.toString()
                                EditorPaneState.content = text
                                onContentChange(text)
                            }
                        })
                    }
                },
                update = { view: android.widget.EditText ->
                    if (view.text.toString() != fileContent) {
                        view.tag = "loading"
                        view.setText(fileContent)
                        view.setSelection(0)
                        view.scrollTo(0, 0)
                        view.tag = "editor"
                        view.text?.let { KotlinHighlighter.highlight(it, pathText) }
                    }
                    val tabName = tabs.getOrElse(selectedTab) { "Untitled.kt" }
                    autoCompleteRef.value?.setCurrentFile(
                        activity.currentProjectDir?.let { File(it, tabName) } ?: File(activity.filesDir, tabName)
                    )
                }
            )
            if (problems.isNotEmpty()) {
                ProblemsPane(
                    problems = problems,
                    onProblemFix = onProblemFix,
                )
            }
        }
    }
}

@Composable
private fun LineNo(n: String) {
    Text(text = n, color = LineNoGray, fontSize = 15.sp,
        modifier = Modifier.padding(end = 8.dp, bottom = 18.dp))
}

@Composable
private fun EditorTab(title: String, active: Boolean, onClick: () -> Unit, onClose: () -> Unit) {
    Column(modifier = Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Min).clickable { onClick() }) {
        Row(modifier = Modifier.padding(start = 14.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, color = if (active) Color.Black else Color(0xFF555555),
                fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
            Spacer(modifier = Modifier.width(6.dp))
            Text(text = "✕", color = Color(0xFF777777), fontSize = 13.sp,
                modifier = Modifier.clickable { onClose() }.padding(4.dp))
        }
        Spacer(modifier = Modifier.height(7.dp))
        Box(modifier = Modifier.fillMaxWidth().height(2.dp).background(if (active) Color(0xFF3C3F41) else Color.Transparent))
    }
}

@Composable
private fun BottomPanel(
    activity: StudioMainActivity,
    pane: String,
    logLines: List<StudioMainActivity.LogLine>,
    height: Dp,
    onHeightChange: (Dp) -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    var dragging by remember { mutableStateOf(false) }
    var startHeightPx by remember { mutableStateOf(0f) }

    Box(modifier = Modifier.fillMaxSize()) {
        when (pane) {
            "buildlog" -> BuildLogPane(lines = logLines)
            "terminal" -> TerminalPane(activity = activity)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(18.dp)
                .background(Color.Transparent)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { dragging = false },
                    ) { change, dragAmount ->
                        if (!dragging) {
                            dragging = true
                            startHeightPx = height.value * density.density
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        val minPx = 120f * density.density
                        val maxPx = 520f * density.density
                        val newPx = (startHeightPx - dragAmount.y).coerceIn(minPx, maxPx)
                        onHeightChange(Dp(newPx / density.density))
                    }
                },
        )
    }
}

@Composable
private fun BuildLogPane(lines: List<StudioMainActivity.LogLine>) {
    val scrollState = rememberScrollState()
    LaunchedEffect(lines.size) {
        scrollState.scrollTo(Int.MAX_VALUE)
    }
    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Box(modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp)) {
            Box(modifier = Modifier.background(MenuBarBg, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 7.dp)) {
                Text(text = "build log", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 16.dp)) {
            lines.forEachIndexed { i, l ->
                Text(text = l.text, color = Color(l.color), fontSize = 13.sp, lineHeight = 18.sp)
                if (i < lines.lastIndex) Spacer(modifier = Modifier.height(2.dp))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TerminalPane(activity: StudioMainActivity) {
    val context: Context = LocalContext.current
    var shellProcess by remember { mutableStateOf<java.lang.Process?>(null) }
    val outputState = remember { mutableStateOf<String>("") }

    LaunchedEffect(Unit) {
        val pb = java.lang.ProcessBuilder("/system/bin/sh")
        pb.directory(activity.currentProjectDir ?: activity.filesDir)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        shellProcess = proc

        activity.scope.launch(Dispatchers.IO) {
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(proc.inputStream, Charset.forName("UTF-8"))
            )
            while (proc.isAlive) {
                val line = reader.readLine()
                if (line == null) break
                withContext(Dispatchers.Main) {
                    outputState.value = outputState.value + (line ?: "") + "\n"
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBg)
    ) {
        Text(
            text = outputState.value,
            color = Color(0xFF22C55E),
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp)
                .background(Color(0xFF0A0C10))
                .verticalScroll(rememberScrollState())
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF151A21))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "$ ", color = Color(0xFF3B82F6), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            AndroidView(
                modifier = Modifier.weight(1f),
                factory = { ctx ->
                    EditText(ctx).apply {
                        hint = "Type a command..."
                        setTextColor(android.graphics.Color.WHITE)
                        setHintTextColor(android.graphics.Color.GRAY)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                        typeface = android.graphics.Typeface.MONOSPACE
                        setBackgroundColor(android.graphics.Color.parseColor("#0E1116"))
                        setSingleLine(true)
                        setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
                    }
                },
                update = { view: android.widget.EditText ->
                    view.setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                            val cmd = view.text.toString()
                            if (cmd.isNotEmpty()) {
                                activity.scope.launch(Dispatchers.IO) {
                                    shellProcess?.outputStream?.also { os ->
                                        os.write((cmd + "\n").toByteArray(Charset.forName("UTF-8")))
                                        os.flush()
                                    }
                                }
                                view.setText("")
                            }
                            true
                        } else false
                    }
                }
            )
        }
    }
}

@Composable
private fun DownloadManagerWindow(
    activity: StudioMainActivity,
    onDismiss: () -> Unit
) {
    val task by remember { derivedStateOf { DownloadCenter.state.value } }
    val context: Context = LocalContext.current

    if (task == null) {
        onDismiss()
        return
    }

    // Use task directly with !! to get non-null type
    val t: DownloadCenter.Task = task!!

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .width(420.dp)
                .height(IntrinsicSize.Min),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF2B2D30)),
            elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Title bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "MobileStudio Downloader",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = {
                        if (t.status == DownloadCenter.Status.CONFIRM) {
                            DownloadCenter.decline()
                        } else if (t.status == DownloadCenter.Status.DOWNLOADING) {
                            DownloadCenter.cancel()
                        }
                        onDismiss()
                    }) {
                        Text("✕", color = Color(0xFFBBBBBB), fontSize = 20.sp)
                    }
                }
                Divider(color = Color(0xFF4A4D50), modifier = Modifier.padding(vertical = 12.dp))

                // Heading: "Downloading: Jdk 17" style
                Text(
                    text = "Downloading: ${t.label}",
                    color = Color(0xFF82AAFF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))

                // DownloadPATH
                Text(
                    text = "DownloadPATH",
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )
                Text(
                    text = t.downloadPath.ifBlank { "—" },
                    color = Color(0xFFBBBBBB),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                // FileName
                Text(
                    text = "FileName",
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )
                Text(
                    text = t.fileName.ifBlank { "—" },
                    color = Color(0xFFBBBBBB),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                when (t.status) {
                    DownloadCenter.Status.CONFIRM -> {
                        // Confirm dialog
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val totalMB = if (t.totalBytes > 0) "${(t.totalBytes / (1024 * 1024)).toString()} MB" else "Unknown size"
                            Text(
                                text = "File size: $totalMB",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Do you want to download this file?",
                                color = Color(0xFFE6E6E6),
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "If you select No, the ${t.label.lowercase()} will not be downloaded and the Gradle build cannot continue.",
                                color = Color(0xFFFF9999),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { DownloadCenter.approve() },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047))
                                ) {
                                    Text("Yes", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        DownloadCenter.decline()
                                        onDismiss()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                                ) {
                                    Text("No", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    DownloadCenter.Status.DOWNLOADING -> {
                        // Progress bar
                        val percent = t.percent
                        val downloadedMB = t.downloadedBytes / (1024 * 1024)
                        val totalMB = if (t.totalBytes > 0) t.totalBytes / (1024 * 1024) else 0L
                        val speedMB = t.speedBytesPerSec / (1024 * 1024)

                        Column(modifier = Modifier.fillMaxWidth()) {
                            // Progress bar background
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(14.dp)
                                    .background(Color(0xFF1E1F22))
                                    .clip(RoundedCornerShape(7.dp))
                            ) {
                                // Progress fill
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(percent.toFloat() / 100f)
                                        .fillMaxHeight()
                                        .background(Color(0xFF8257E6)) // Purple
                                        .clip(RoundedCornerShape(7.dp))
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            // Tooltip-like info: downloaded MB + percent + MB/s
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${downloadedMB} MB / ${if (totalMB > 0) "${totalMB} MB" else "?"} (${percent}%)",
                                    color = Color(0xFFE6E6E6),
                                    fontSize = 12.sp
                                )
                                if (speedMB > 0) {
                                    Text(
                                        text = "${speedMB} MB/s",
                                        color = Color(0xFF82AAFF),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            if (t.message.isNotBlank()) {
                                Text(
                                    text = t.message,
                                    color = Color(0xFF888888),
                                    fontSize = 11.sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { DownloadCenter.cancel() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                            ) {
                                Text("Cancel", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    DownloadCenter.Status.DONE, DownloadCenter.Status.FAILED, DownloadCenter.Status.CANCELLED -> {
                        // Completed / Failed / Cancelled
                        val iconText: String
                        val iconColor: Color
                        val title: String
                        when (t.status) {
                            DownloadCenter.Status.DONE -> {
                                iconText = "✓"
                                iconColor = Color(0xFF43A047)
                                title = "Download Complete"
                            }
                            DownloadCenter.Status.FAILED -> {
                                iconText = "✗"
                                iconColor = Color(0xFFEF5350)
                                title = "Download Failed"
                            }
                            else -> {
                                iconText = "⊘"
                                iconColor = Color(0xFFBBBBBB)
                                title = "Download Cancelled"
                            }
                        }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = iconText,
                                color = iconColor,
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = title,
                                color = iconColor,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (t.message.isNotBlank()) {
                                Text(
                                    text = t.message,
                                    color = Color(0xFFBBBBBB),
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { onDismiss() },
                                modifier = Modifier.width(120.dp)
                            ) {
                                Text("Close", color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class StudioMenuItem(
    val label: String,
    val action: String = "",
    val shortcut: String = "",
    val divider: Boolean = false,
    val subItems: List<StudioMenuItem> = emptyList()
)

private data class StudioMenu(val label: String, val items: List<StudioMenuItem>)

@Composable
private fun TopMenuBar(
    activity: Activity,
    onRun: () -> Unit,
    onAction: (String) -> Unit,
) {
    val menus = remember {
        listOf(
            StudioMenu("File", listOf(
                StudioMenuItem("New", "", subItems = listOf(
                    StudioMenuItem("Project...", "new_project"),
                    StudioMenuItem("File...", "new_file"),
                    StudioMenuItem("Folder...", "new_folder"),
                )),
                StudioMenuItem("Open Project...", "open_project"),
                StudioMenuItem("Recent Projects", "recent_projects"),
                StudioMenuItem("Recent Files", "recent_files"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Save", "save", "Ctrl+S"),
                StudioMenuItem("Save All", "save_all"),
                StudioMenuItem("Close Tab", "close_tab", "Ctrl+W"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Settings...", "settings", "Ctrl+Alt+S"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Exit", "exit")
            )),
            StudioMenu("Edit", listOf(
                StudioMenuItem("Undo", "undo", "Ctrl+Z"),
                StudioMenuItem("Redo", "redo", "Ctrl+Shift+Z"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Cut", "cut", "Ctrl+X"),
                StudioMenuItem("Copy", "copy", "Ctrl+C"),
                StudioMenuItem("Paste", "paste", "Ctrl+V"),
                StudioMenuItem("Select All", "select_all", "Ctrl+A"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Find...", "find", "Ctrl+F"),
                StudioMenuItem("Find/Replace...", "find_replace"),
                StudioMenuItem("Find in Files...", "find_in_files"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Close All Tabs", "close_all_tabs")
            )),
            StudioMenu("View", listOf(
                StudioMenuItem("Editor", "pane_editor"),
                StudioMenuItem("Build Log", "pane_buildlog"),
                StudioMenuItem("Terminal", "pane_terminal"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Project Files Drawer", "drawer")
            )),
            StudioMenu("Navigate", listOf(
                StudioMenuItem("Next Tab", "next_tab", "Ctrl+Tab"),
                StudioMenuItem("Previous Tab", "prev_tab", "Ctrl+Shift+Tab"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Go to Line...", "goto_line", "Ctrl+G")
            )),
            StudioMenu("Code", listOf(
                StudioMenuItem("Comment / Uncomment", "comment", "Ctrl+/"),
                StudioMenuItem("Reformat Code", "reformat", "Ctrl+Alt+L"),
                StudioMenuItem("Optimize Imports", "optimize", "Ctrl+Alt+O")
            )),
            StudioMenu("Run", listOf(
                StudioMenuItem("Run 'app'", "run", "Shift+F10")
            )),
            StudioMenu("Tools", listOf(
                StudioMenuItem("Terminal", "terminal", "Alt+F12"),
                StudioMenuItem("AI Assistant", "assistant"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Device Manager", "device_manager"),
                StudioMenuItem("SDK Manager", "sdk_manager"),
                StudioMenuItem("Resource Manager", "resource_manager"),
                StudioMenuItem("AGP Upgrade Assistant...", "agp_upgrade"),
                StudioMenuItem("Android SDK Upgrade Assistant", "android_sdk_upgrade"),
                StudioMenuItem("Troubleshoot Device Connections", "troubleshoot_device"),
                StudioMenuItem("App Links Assistant", "app_links"),
                StudioMenuItem("Firebase", "firebase"),
                StudioMenuItem("Layout Inspector", "layout_inspector"),
                StudioMenuItem("Generate Javadoc...", "generate_javadoc"),
                StudioMenuItem("Create Command Line Launcher...", "create_cli_launcher"),
                StudioMenuItem("Services", "services"),
                StudioMenuItem("Markdown", "markdown"),
                StudioMenuItem("XML Actions", "xml_actions"),
                StudioMenuItem("Cling", "cling"),
                StudioMenuItem("Groovy Console", "groovy_console"),
                StudioMenuItem("Kotlin", "kotlin"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Git", "git"),
                StudioMenuItem("File Manager", "file_manager"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Download Manager", "downloads")
            )),
            StudioMenu("VCS", listOf(
                StudioMenuItem("Git...", "git"),
                StudioMenuItem("File Manager", "file_manager")
            )),
            StudioMenu("Window", listOf(
                StudioMenuItem("Editor", "pane_editor"),
                StudioMenuItem("Build Log", "pane_buildlog"),
                StudioMenuItem("Terminal", "pane_terminal"),
                StudioMenuItem("Project Files Drawer", "drawer"),
                StudioMenuItem("", divider = true),
                StudioMenuItem("Download Manager", "downloads")
            )),
            StudioMenu("Help", listOf(
                StudioMenuItem("About MobileStudio", "about")
            ))
        )
    }
    var openMenu by remember { mutableStateOf<String?>(null) }
    var gearOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().height(46.dp).background(MenuBarBg).padding(start = 0.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            menus.forEach { menu ->
                Box {
                    Text(
                        text = menu.label,
                        color = if (openMenu == menu.label) Color(0xFF3574F0) else Color(0xFFDFE1E5),
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { openMenu = if (openMenu == menu.label) null else menu.label }
                            .padding(horizontal = 7.dp, vertical = 10.dp)
                    )
                    DropdownMenu(
                        expanded = openMenu == menu.label,
                        onDismissRequest = { openMenu = null },
                        modifier = Modifier.width(250.dp).background(Color(0xFF2B2D30))
                    ) {
                        menu.items.forEach { item ->
                            if (item.divider) {
                                Divider(color = Color(0xFF4A4D50), modifier = Modifier.padding(vertical = 4.dp))
                            } else if (item.subItems.isNotEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.label, color = Color(0xFFE6E6E6), fontSize = 13.sp)
                                            Spacer(modifier = Modifier.weight(1f))
                                            Text("›", color = Color(0xFF888888), fontSize = 13.sp)
                                        }
                                    },
                                    onClick = {
                                        openMenu = null
                                        showSubMenuDialog(activity, item.label, item.subItems, onAction)
                                    },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                )
                            } else {
                                DropdownMenuItem(
                                    text = {
                                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(item.label, color = Color(0xFFE6E6E6), fontSize = 13.sp)
                                            Spacer(modifier = Modifier.weight(1f))
                                            if (item.shortcut.isNotEmpty()) {
                                                Text(item.shortcut, color = Color(0xFF888888), fontSize = 11.sp)
                                            }
                                        }
                                    },
                                    onClick = {
                                        openMenu = null
                                        onAction(item.action)
                                    },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        Canvas(modifier = Modifier.size(32.dp).clickable { onRun() }.padding(3.dp)) {
            val w = size.width; val h = size.height
            val p = Path().apply { moveTo(w * 0.22f, h * 0.14f); lineTo(w * 0.88f, h * 0.5f); lineTo(w * 0.22f, h * 0.86f); close() }
            drawPath(p, PlayGreen)
        }
        Box {
            Box(modifier = Modifier.size(32.dp).clickable { gearOpen = true }, contentAlignment = Alignment.Center) {
                GearIcon(Modifier.size(20.dp))
            }
            DropdownMenu(
                expanded = gearOpen,
                onDismissRequest = { gearOpen = false },
                modifier = Modifier.width(210.dp).background(Color(0xFF2B2D30))
            ) {
                DropdownMenuItem(
                    text = { Text("Settings...", color = Color(0xFFE6E6E6), fontSize = 13.sp) },
                    onClick = { gearOpen = false; onAction("settings") },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                )
                DropdownMenuItem(
                    text = { Text("About MobileStudio", color = Color(0xFFE6E6E6), fontSize = 13.sp) },
                    onClick = { gearOpen = false; onAction("about") },
                    modifier = Modifier.height(36.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
    }
}

@Composable
private fun FileDrawer(
    activity: StudioMainActivity,
    mode: String, // "projects" or "files"
    onClose: () -> Unit,
    onPickFile: (File) -> Unit,
    onOpenProject: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchToFiles: () -> Unit,
    onSwitchToProjects: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (mode) {
        "projects" -> ProjectsView(
            activity = activity,
            onOpenProject = onOpenProject,
            onSwitchToFiles = onSwitchToFiles,
            currentProjectDir = activity.currentProjectDir,
            onClose = onClose,
        )
        "files" -> FilesView(
            activity = activity,
            onPickFile = onPickFile,
            onRefresh = onRefresh,
            onSwitchToProjects = onSwitchToProjects,
            onOpenProject = onOpenProject,
            onClose = onClose,
        )
    }
}

@Composable
private fun ProjectsView(
    activity: StudioMainActivity,
    onOpenProject: () -> Unit,
    onSwitchToFiles: () -> Unit,
    currentProjectDir: File?,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        // Current project header - clickable to switch to files view
        if (currentProjectDir != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MenuBarBg)
                    .clickable { onSwitchToFiles() }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FolderIcon(modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(7.dp))
                Text(
                    text = currentProjectDir.name,
                    color = Color(0xFFDDDDDD),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("▸", color = Color(0xFF999999), fontSize = 14.sp)
            }
            Divider(color = Color(0xFF44474A))
        } else {
            // No project selected
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MenuBarBg)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "[no projects]",
                    color = Color(0xFF888888),
                    fontSize = 13.sp
                )
            }
            Divider(color = Color(0xFF44474A))
        }

        // Known projects list
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            val knownProjects = getKnownProjects(activity)
            if (knownProjects.isEmpty()) {
                Text(
                    text = "No projects yet.\nTap 'Open Project' to add one.",
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                knownProjects.forEach { projectDir ->
                    val isCurrent = currentProjectDir != null && projectDir.absolutePath == currentProjectDir.absolutePath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isCurrent) Color(0xFF3C3F41) else Color.Transparent)
                            .clickable { activity.openProject(projectDir); onSwitchToFiles() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FolderIcon(modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = projectDir.name,
                            color = if (isCurrent) Color(0xFF82AAFF) else Color(0xFFDDDDDD),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (isCurrent) {
                            Text("✓", color = Color(0xFF43A047), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Divider(color = Color(0xFF44474A), modifier = Modifier.padding(start = 36.dp))
                }
            }
        }

        // Bottom buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DrawerButton("Open Project", onOpenProject)
            DrawerButton("Close", onClose)
        }
    }
}

@Composable
private fun FilesView(
    activity: StudioMainActivity,
    onPickFile: (File) -> Unit,
    onRefresh: () -> Unit,
    onSwitchToProjects: () -> Unit,
    onOpenProject: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        // Header with back button
        Row(
            modifier = Modifier.fillMaxWidth().background(MenuBarBg).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("◂", color = Color(0xFFBBBBBB), fontSize = 16.sp, modifier = Modifier
                .clickable { onSwitchToProjects() }
                .padding(end = 8.dp))
            Text("files", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "✕",
                color = Color(0xFFBBBBBB),
                fontSize = 16.sp,
                modifier = Modifier.clickable { onClose() }.padding(4.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onRefresh() }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FolderIcon(modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                text = activity.currentProjectDir?.name ?: "no project",
                color = Color(0xFFDDDDDD),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text("⟳", color = Color(0xFF999999), fontSize = 14.sp)
        }
        Divider(color = Color(0xFF44474A))
        Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (activity.treeItems.isEmpty()) {
                Text(
                    text = "No files in this project.\nUse Open Project to pick a folder.",
                    color = Color(0xFF888888),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            activity.treeItems.forEach { item ->
                key(item.file.absolutePath, item.isExpanded) {
                    FileTreeRow(item = item) {
                        if (item.file.isDirectory) activity.toggleTreeFolder(item.file)
                        else onPickFile(item.file)
                    }
                }
            }
        }
        Divider(color = Color(0xFF44474A))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DrawerButton("Open Project", onOpenProject)
            DrawerButton("Close", onClose)
        }
    }
}

@Composable
private fun DrawerButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Color(0xFF3C3F41), RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = Color.White, fontSize = 12.sp)
    }
}

@Composable
private fun FileTreeRow(item: FileItem, onClick: () -> Unit) {
    val isDir = item.file.isDirectory
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(start = (10 + item.depth * 14).dp, top = 7.dp, bottom = 7.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDir) {
            Text(if (item.isExpanded) "▾" else "▸", color = Color(0xFFBBBBBB), fontSize = 11.sp)
            Spacer(modifier = Modifier.width(4.dp))
            FolderIcon(modifier = Modifier.size(14.dp))
        } else {
            Spacer(modifier = Modifier.width(15.dp))
            Text(
                text = fileBadge(item.file),
                color = fileBadgeColor(item.file),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color(0xFF1E1F22), RoundedCornerShape(2.dp))
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = item.file.name,
            color = if (isDir) Color(0xFFE0E0E0) else Color(0xFFBDBDBD),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun fileBadge(file: File): String = when (file.extension.lowercase()) {
    "kt" -> "KT"
    "java" -> "J"
    "xml" -> "XML"
    "gradle" -> "G"
    "md" -> "MD"
    "json" -> "{}"
    else -> "·"
}

private fun fileBadgeColor(file: File): Color = when (file.extension.lowercase()) {
    "kt" -> Color(0xFF7F9EFF)
    "java" -> Color(0xFFE8A33D)
    "xml" -> Color(0xFF6FBF73)
    "gradle" -> Color(0xFF5BC0BE)
    else -> Color(0xFF999999)
}

@Composable
private fun getKnownProjects(activity: StudioMainActivity): List<File> {
    val projects = mutableListOf<File>()
    val workspaceDir = File(activity.filesDir, "workspace")
    if (workspaceDir.exists() && workspaceDir.isDirectory) {
        workspaceDir.listFiles()?.filter { it.isDirectory }?.forEach { projects.add(it) }
    }
    // Also check filesDir for any other project directories
    activity.filesDir.listFiles()?.filter { it.isDirectory && it.name != "toolchain" && it.name != "workspace" && it.name != "AI" && it.name != "code_cache" }?.forEach { projects.add(it) }
    return projects.distinctBy { it.absolutePath }
}

@Composable
private fun GearIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width; val c = Offset(w / 2f, w / 2f)
        val strokeW = w * 0.16f
        repeat(8) { i ->
            val ang = Math.toRadians((i * 45).toDouble())
            val r1 = w * 0.26f; val r2 = w * 0.46f
            drawLine(color = Color.White,
                start = Offset(c.x + (r1 * kotlin.math.cos(ang)).toFloat(), c.y + (r1 * kotlin.math.sin(ang)).toFloat()),
                end = Offset(c.x + (r2 * kotlin.math.cos(ang)).toFloat(), c.y + (r2 * kotlin.math.sin(ang)).toFloat()),
                strokeWidth = strokeW)
        }
        drawCircle(color = Color.White, radius = w * 0.30f, center = c, style = Stroke(width = strokeW))
    }
}

@Composable
private fun LeftStrip(
    context: Context,
    onFolder: () -> Unit,
    onBuildLog: () -> Unit,
    onTerminal: () -> Unit,
    onIde: () -> Unit,
    onProjectOpen: () -> Unit,
) {
    Column(modifier = Modifier.width(42.dp).fillMaxHeight().background(StripLavender), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(42.dp).clickable { onProjectOpen() }, contentAlignment = Alignment.Center) {
            FolderIcon(Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.size(38.dp).clickable { onBuildLog() }, contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val w = size.width; val h = size.height
                val p = Path().apply { moveTo(w * 0.15f, h * 0.1f); lineTo(w * 0.92f, h * 0.5f); lineTo(w * 0.15f, h * 0.9f); close() }
                drawPath(p, StripIconDark)
            }
        }
        Box(modifier = Modifier.size(38.dp).clickable { onIde() }, contentAlignment = Alignment.Center) { CatIcon(Modifier.size(24.dp)) }
        Box(modifier = Modifier.size(38.dp).clickable {}, contentAlignment = Alignment.Center) { AlertIcon(Modifier.size(22.dp)) }
        Box(modifier = Modifier.size(38.dp).clickable { onTerminal() }, contentAlignment = Alignment.Center) { Text(">_", color = StripIconDark, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Box(modifier = Modifier.size(38.dp).clickable {}, contentAlignment = Alignment.Center) { Text("✦", color = StripIconDark, fontSize = 18.sp) }
        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun FolderIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w * 0.05f, h * 0.25f); lineTo(w * 0.38f, h * 0.25f); lineTo(w * 0.46f, h * 0.38f)
            lineTo(w * 0.95f, h * 0.38f); lineTo(w * 0.95f, h * 0.85f); lineTo(w * 0.05f, h * 0.85f); close()
        }
        drawPath(p, Color(0xFFFFD54F))
        drawPath(p, Color(0xFFF9A825), style = Stroke(width = 1.dp.toPx()))
    }
}

@Composable
private fun CatIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx())
        val c = Offset(w * 0.5f, h * 0.62f)
        drawCircle(color = StripIconDark, radius = w * 0.30f, center = c, style = stroke)
        val earL = Path().apply { moveTo(w * 0.24f, h * 0.46f); lineTo(w * 0.30f, h * 0.14f); lineTo(w * 0.44f, h * 0.36f); close() }
        drawPath(earL, StripIconDark, style = stroke)
        val earR = Path().apply { moveTo(w * 0.76f, h * 0.46f); lineTo(w * 0.70f, h * 0.14f); lineTo(w * 0.56f, h * 0.36f); close() }
        drawPath(earR, StripIconDark, style = stroke)
    }
}

@Composable
private fun AlertIcon(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = StripIconDark, radius = size.minDimension / 2f, style = Stroke(width = 1.6.dp.toPx()))
        }
        Text("!", color = StripIconDark, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

private fun LineNumberEditText.setHighlighted(content: String) {
    val show = if (content.isEmpty()) EditorDefaultContent else content
    val spannable = SpannableString(show)
    val kwRegex = Regex("\\b(fun|val|var|if|else|when|for|while|return|class|object|interface|import|package|override|suspend|try|catch|finally|throw|in|is|as)\\b")
    kwRegex.findAll(content).forEach {
        spannable.setSpan(
            ForegroundColorSpan(android.graphics.Color.parseColor(CodeKeywordHex)), it.range.first, it.range.last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    val strRegex = Regex("\"([^\"\\\\]|\\\\.)*\"")
    strRegex.findAll(content).forEach {
        spannable.setSpan(
            ForegroundColorSpan(android.graphics.Color.parseColor(CodeStringHex)), it.range.first, it.range.last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    setText(spannable, TextView.BufferType.SPANNABLE)
}

/** 새 프로젝트 만들기 다이얼로그 */
/** 프로젝트 열기 다이얼로그 */
private fun openExistingProjectDialog(activity: StudioMainActivity) {
    activity.pickDir.launch(null)
}

/**
 * Android Studio style Settings window drawn over the IDE. This is not a
 * separate activity: the editor, project tree and toolbars stay alive behind
 * the dimmed scrim, and closing the window returns to the exact same state.
 */
@Composable
private fun SettingsWindowOverlay(activity: StudioMainActivity, onClose: () -> Unit) {
    BackHandler { onClose() }
    val close by rememberUpdatedState(onClose)
    val controller = remember(activity) {
        SettingsController(activity) { close() }
    }
    MobileStudioTheme {
        Box(Modifier.fillMaxSize().background(Color(0xAA000000))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, Palette.border, RoundedCornerShape(6.dp))
            ) {
                Surface(Modifier.fillMaxSize(), color = Palette.bg) {
                    controller.SettingsScreen()
                }
            }
        }
    }
}

/**
 * Android Studio style Tools tool-window. The existing [ToolsPanel] view tree
 * is embedded directly, so opening SDK Manager, Device Manager, Logcat, etc.
 * no longer replaces the editor with a separate activity.
 */
@Composable
private fun ToolsWindowOverlay(
    activity: StudioMainActivity,
    initialTab: String,
    onClose: () -> Unit,
) {
    BackHandler { onClose() }
    val close by rememberUpdatedState(onClose)
    val panel = remember(activity) {
        ToolsPanel(activity) { close() }
    }
    DisposableEffect(panel) {
        activity.toolsPanelForResult = panel
        onDispose {
            if (activity.toolsPanelForResult === panel) {
                activity.toolsPanelForResult = null
            }
            panel.dispose()
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0xAA000000))) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Color(0xFF393B40), RoundedCornerShape(6.dp))
        ) {
            AndroidView(
                factory = { panel.create(initialTab) },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Opens the Android Studio-style Tools activity on the given tab. */
fun openTools(context: Context, tabId: String) {
    val intent = Intent(context, ToolsActivity::class.java)
    intent.putExtra("tools_tab", tabId)
    context.startActivity(intent)
}

/** Shows a sub-menu dialog for items that have [subItems]. */
private fun showSubMenuDialog(activity: Activity, parentLabel: String, subItems: List<StudioMenuItem>, onAction: (String) -> Unit) {
    val items = subItems.filter { !it.divider && it.label.isNotEmpty() }.map { it.label }
    AlertDialog.Builder(activity)
        .setTitle(parentLabel)
        .setItems(items.toTypedArray()) { _, which ->
            val selected = subItems.filter { !it.divider && it.label.isNotEmpty() }[which]
            onAction(selected.action)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Shows the "New Project" dialog (Android Studio style). */
fun showNewProjectDialog(activity: StudioMainActivity) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()

    val layout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    layout.addView(TextView(activity).apply {
        text = "Project Type"
        setTextColor(android.graphics.Color.parseColor("#E6E6E6"))
        textSize = 14f
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setPadding(0, 0, 0, dp(8))
    })
    val typeSpinner = android.widget.Spinner(activity)
    val types = arrayOf("Android Application", "Java Application", "Kotlin Application", "EXE / Native Binary")
    typeSpinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, types)
    layout.addView(typeSpinner)
    layout.addView(TextView(activity).apply {
        text = "Project Name"
        setTextColor(android.graphics.Color.parseColor("#E6E6E6"))
        textSize = 14f
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setPadding(0, dp(12), 0, dp(8))
    })
    val nameInput = EditText(activity).apply {
        hint = "MyApplication"
        setTextColor(android.graphics.Color.parseColor("#E6E6E6"))
        setHintTextColor(android.graphics.Color.parseColor("#888888"))
        background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#3A3D42"))
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    layout.addView(nameInput)
    layout.addView(TextView(activity).apply {
        text = "Location"
        setTextColor(android.graphics.Color.parseColor("#E6E6E6"))
        textSize = 14f
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setPadding(0, dp(12), 0, dp(8))
    })
    val locInput = EditText(activity).apply {
        setText(activity.getLastProject() ?: System.getProperty("user.home"))
        setTextColor(android.graphics.Color.parseColor("#E6E6E6"))
        background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#3A3D42"))
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    layout.addView(locInput)
    layout.addView(TextView(activity).apply {
        text = "Language"
        setTextColor(android.graphics.Color.parseColor("#E6E6E6"))
        textSize = 14f
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setPadding(0, dp(12), 0, dp(8))
    })
    val langSpinner = android.widget.Spinner(activity)
    val langs = arrayOf("Kotlin", "Java")
    langSpinner.adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, langs)
    layout.addView(langSpinner)

    AlertDialog.Builder(activity)
        .setTitle("New Project")
        .setView(layout)
        .setPositiveButton("Create") { _, _ ->
            val projName = nameInput.text.toString().trim()
            val projLoc = locInput.text.toString().trim()
            val projType = types[typeSpinner.selectedItemPosition]
            if (projName.isEmpty() || projLoc.isEmpty()) {
                Toast.makeText(activity, "Please enter project name and location", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val projDir = File(projLoc, projName)
            projDir.mkdirs()
            when (projType) {
                "Android Application" -> {
                    File(projDir, "app").mkdirs()
                    File(projDir, "app/src/main/java").mkdirs()
                    File(projDir, "app/src/main/res").mkdirs()
                    File(projDir, "app/build.gradle.kts").writeText("""
                        plugins { id('com.android.application') id('org.jetbrains.kotlin.android') }
                        android { namespace = 'com.example.${projName.lowercase()}' compileSdk = 34 }
                        dependencies { implementation(project(':app')) }
                    """.trimIndent())
                    File(projDir, "settings.gradle.kts").writeText("rootProject.name = \"$projName\"")
                    File(projDir, "app/src/main/java/com/example/${projName.lowercase()}/MainActivity.kt").writeText("""
                        package com.example.${projName.lowercase()}

                        import android.os.Bundle
                        import androidx.appcompat.app.AppCompatActivity

                        class MainActivity : AppCompatActivity() {
                            override fun onCreate(savedInstanceState: Bundle?) {
                                super.onCreate(savedInstanceState)
                            }
                        }
                    """.trimIndent())
                    File(projDir, "README.md").writeText("# $projName\n\nAndroid Application created with MobileStudio")
                }
                "Java Application" -> {
                    File(projDir, "src").mkdirs()
                    File(projDir, "src/com/example").mkdirs()
                    File(projDir, "src/com/example/Main.java").writeText("""
                        package com.example;

                        public class Main {
                            public static void main(String[] args) {
                                System.out.println("Hello, $projName!");
                            }
                        }
                    """.trimIndent())
                    File(projDir, "README.md").writeText("# $projName\n\nJava Application created with MobileStudio")
                }
                "Kotlin Application" -> {
                    File(projDir, "src").mkdirs()
                    File(projDir, "src/com/example").mkdirs()
                    File(projDir, "src/com/example/Main.kt").writeText("""
                        package com.example

                        fun main() {
                            println("Hello, $projName!")
                        }
                    """.trimIndent())
                    File(projDir, "README.md").writeText("# $projName\n\nKotlin Application created with MobileStudio")
                }
                "EXE / Native Binary" -> {
                    File(projDir, "src").mkdirs()
                    File(projDir, "src/main.cpp").writeText("""
                        #include <iostream>

                        int main() {
                            std::cout << "Hello, $projName!" << std::endl;
                            return 0;
                        }
                    """.trimIndent())
                    File(projDir, "README.md").writeText("# $projName\n\nEXE / Native Binary project created with MobileStudio")
                }
            }
            activity.openProject(projDir)
            activity.putLastProject(projDir.absolutePath)
            Toast.makeText(activity, "Project created: $projDir", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Cancel", null)
        .show()
}


/** Shows a simple "New File" dialog inside the current project. */
fun showNewFileDialog(activity: StudioMainActivity) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val input = EditText(activity).apply {
        hint = "File name (e.g. Main.kt)"
        setTextColor(android.graphics.Color.parseColor("#E6E6E6"))
        setHintTextColor(android.graphics.Color.parseColor("#888888"))
        background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#3A3D42"))
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    AlertDialog.Builder(activity)
        .setTitle("New File")
        .setView(input)
        .setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(activity, "Enter a file name", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val projectDir = activity.currentProjectDir ?: return@setPositiveButton
            val newFile = File(projectDir, name)
            try {
                newFile.createNewFile()
                activity.openFileInEditor(newFile)
                Toast.makeText(activity, "Created: $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(activity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Shows a simple "New Folder" dialog inside the current project. */
fun showNewFolderDialog(activity: StudioMainActivity) {
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val input = EditText(activity).apply {
        hint = "Folder name"
        setTextColor(android.graphics.Color.parseColor("#E6E6E6"))
        setHintTextColor(android.graphics.Color.parseColor("#888888"))
        background = android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#3A3D42"))
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    AlertDialog.Builder(activity)
        .setTitle("New Folder")
        .setView(input)
        .setPositiveButton("Create") { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(activity, "Enter a folder name", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val projectDir = activity.currentProjectDir ?: return@setPositiveButton
            val newDir = File(projectDir, name)
            if (newDir.mkdirs()) {
                Toast.makeText(activity, "Created: $name", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "Failed to create folder", Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Shows a dialog with recently opened projects. */
fun showRecentProjectsDialog(activity: StudioMainActivity) {
    val recent = activity.getRecentProjects()
    if (recent.isEmpty()) {
        Toast.makeText(activity, "No recent projects", Toast.LENGTH_SHORT).show()
        return
    }
    val items = recent.map { it.takeLast(30).toString() }.toTypedArray()
    val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, items)
    val fullPaths = recent.toTypedArray()
    AlertDialog.Builder(activity)
        .setTitle("Recent Projects")
        .setAdapter(adapter, { dialog: DialogInterface?, which: Int ->
            val dir = File(fullPaths[which])
            if (dir.exists()) {
                activity.openProject(dir)
                activity.putLastProject(dir.absolutePath)
            } else {
                Toast.makeText(activity, "Project not found: ${dir.name}", Toast.LENGTH_SHORT).show()
            }
        })
        .setNegativeButton("Cancel", null)
        .show()
}

/** Quick Open: fuzzy-search and open any file in the current project. */
fun showQuickOpenDialog(activity: StudioMainActivity) {
    val projectDir = activity.currentProjectDir ?: return
    val files = mutableListOf<String>()
    projectDir.walkTopDown().forEach { f ->
        if (f.isFile && !f.path.contains("/.git/") && !f.path.contains("/.gradle/") && !f.path.contains("/build/")) {
            files.add(f.absolutePath)
        }
    }
    if (files.isEmpty()) {
        Toast.makeText(activity, "No files found", Toast.LENGTH_SHORT).show()
        return
    }
    val prefix = projectDir.absolutePath + File.separator
    val items = files.map { it.removePrefix(prefix) }.toTypedArray()
    AlertDialog.Builder(activity)
        .setTitle("Quick Open")
        .setItems(items) { _, which ->
            activity.openFileInEditor(File(files[which]))
        }
        .setNegativeButton("Cancel", null)
        .show()
}

/** Shows a dialog with recently opened files. */
fun showRecentFilesDialog(activity: StudioMainActivity) {
    val recent = activity.getRecentFiles()
    if (recent.isEmpty()) {
        Toast.makeText(activity, "No recent files", Toast.LENGTH_SHORT).show()
        return
    }
    val items = recent.map { it.takeLast(40).toString() }.toTypedArray()
    val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, items)
    val fullPaths = recent.toTypedArray()
    AlertDialog.Builder(activity)
        .setTitle("Recent Files")
        .setAdapter(adapter, { dialog: DialogInterface?, which: Int ->
            val file = File(fullPaths[which])
            if (file.exists()) {
                activity.openFileInEditor(file)
            } else {
                Toast.makeText(activity, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            }
        })
        .setNegativeButton("Cancel", null)
        .show()
}

/** Shows project statistics: file count, directory count, line count. */
fun showProjectStatsDialog(activity: StudioMainActivity) {
    val projectDir = activity.currentProjectDir ?: return
    var fileCount = 0
    var lineCount = 0L
    var dirCount = 0
    projectDir.walkTopDown().forEach { f ->
        if (f.isDirectory) {
            dirCount++
        } else if (f.isFile && !f.path.contains("/.git/") && !f.path.contains("/.gradle/") && !f.path.contains("/build/")) {
            fileCount++
            try {
                lineCount += f.readLines().size
            } catch (_: Exception) {}
        }
    }
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val layout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }
    layout.addView(TextView(activity).apply {
        text = "Project Statistics"
        setTextColor(0xFFE6E6E6.toInt())
        textSize = 16f
        setTypeface(Typeface.DEFAULT_BOLD)
        setPadding(0, 0, 0, dp(8))
    })
    layout.addView(TextView(activity).apply {
        text = "Files: $fileCount\nDirectories: $dirCount\nLines: $lineCount"
        setTextColor(0xFFE6E6E6.toInt())
        textSize = 14f
        setTypeface(Typeface.create("monospace", Typeface.NORMAL))
        setPadding(0, 0, 0, dp(8))
    })
    AlertDialog.Builder(activity)
        .setTitle("Project Statistics")
        .setView(layout)
        .setPositiveButton("OK", null)
        .show()
}

/** Search text across all files in the current project. */
fun showFindInFilesDialog(activity: StudioMainActivity) {
    val projectDir = activity.currentProjectDir ?: return
    val density = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * density).toInt()
    val queryInput = EditText(activity).apply {
        hint = "Search text"
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    val layout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(TextView(activity).apply {
            text = "Find in Files"
            setTextColor(0xFFE6E6E6.toInt())
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(8))
        })
        addView(queryInput)
    }
    AlertDialog.Builder(activity)
        .setTitle("Find in Files")
        .setView(layout)
        .setPositiveButton("Search") { _, _ ->
            val query = queryInput.text.toString().trim()
            if (query.isBlank()) return@setPositiveButton
            val results = mutableListOf<String>()
            projectDir.walkTopDown().forEach { f ->
                if (f.isFile && !f.path.contains("/.git/") && !f.path.contains("/.gradle/") && !f.path.contains("/build/")) {
                    try {
                        val text = f.readText(Charsets.UTF_8)
                        val lines = text.lines()
                        for ((line, idx) in lines.withIndex()) {
                            val l = line.toString()
                            if (l.lowercase().contains(query.lowercase())) {
                                val relPath = f.relativeTo(projectDir).path
                                val snippet = l.take(60)
                                results.add("$relPath:${idx + 1}: $snippet")
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            if (results.isEmpty()) {
                Toast.makeText(activity, "No results found", Toast.LENGTH_SHORT).show()
            } else {
                AlertDialog.Builder(activity)
                    .setTitle("Results (${results.size})")
                    .setMessage(results.joinToString("\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}
