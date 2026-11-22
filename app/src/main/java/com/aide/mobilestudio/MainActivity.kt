package com.aide.mobilestudio

import android.animation.ValueAnimator
import android.util.Log
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import java.nio.charset.Charset
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aide.mobilestudio.R.id.btn_preview
import com.aide.mobilestudio.ai.api.AIProvider
import com.aide.mobilestudio.analysis.KotlinCompiler
import com.aide.mobilestudio.extension.ExtensionManager
import com.aide.mobilestudio.extension.ExtensionHost
import com.aide.mobilestudio.VolumeGestureHandler
import com.aide.mobilestudio.terminal.TerminalTabManager
import com.aide.mobilestudio.terminal.TerminalSession
import com.aide.mobilestudio.terminal.TerminalView
import com.aide.mobilestudio.terminal.TerminalPtySelfTest
import com.aide.mobilestudio.TutorialGuide
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity(), ContextMenuUtils.FileContextMenuHandler, FileOperationsHandler, StudioHost {
    companion object {
        private const val REQ_OPEN_PROJECT_TREE = 7201
        private const val MAX_CAPTURED_BUILD_OUTPUT_CHARS = 1_200_000
        private const val MAX_RETAINED_BUILD_OUTPUT_CHARS = 900_000
        private const val MAX_BUILD_LINE_CHARS = 4_000
        private const val VIRTUAL_TRIGGER = 300
        private const val VIRTUAL_BUFFER = 100
    }

    private lateinit var drawerLayout: DrawerLayout
    internal lateinit var codeEditor: LineNumberEditText
    private lateinit var welcomeScreen: View
    private lateinit var editorContainer: View
    private lateinit var tabContainer: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tvProjectName: TextView
    private lateinit var tvStatusLeft: TextView
    private lateinit var tvStatusRight: TextView
    private lateinit var tvStatusLang: TextView
    private lateinit var tvStatusCursor: TextView
    private lateinit var tvStatusEncoding: TextView
    private var tvMemoryIndicator: TextView? = null
    private lateinit var tvBackgroundTask: TextView
    private lateinit var badgeWarnings: TextView
    private lateinit var badgeErrors: TextView
    private lateinit var bottomPanel: LinearLayout
    private lateinit var buildOutput: BuildOutputView
    private lateinit var buildMonitor: BuildMonitorView
    private lateinit var buildTreeView: BuildOutputTreeView
    private lateinit var logcatContainer: View
    private lateinit var logcatText: AutoScrollLogView
    private lateinit var problemsContainer: View
    private var problemsRecycler: RecyclerView? = null
    private lateinit var buildProgress: ProgressBar
    private lateinit var fileTree: RecyclerView
    private lateinit var tvDrawerTitle: TextView
    private lateinit var editorOverlay: FrameLayout
    private lateinit var projectTabContainer: LinearLayout
    private lateinit var projectTabScroll: HorizontalScrollView

    // Live Edit pipeline
    private var fileWatcher: FileWatcher? = null
    private lateinit var buildScheduler: BuildScheduler
    private lateinit var breadcrumb: LinearLayout          // path breadcrumb

    // Real-time analysis engine
    internal var analysisEngine: com.aide.mobilestudio.analysis.AnalysisEngine? = null
    private var freezeDetector: UiFreezeDetector? = null

    // State
    internal var currentProjectDir: File? = null
    internal var currentFile: File? = null
    private var currentFileEncoding = "UTF-8"
    private var currentProjectKind: ProjectKind = ProjectKind.ANDROID
    private val openTabs = mutableListOf<File>()
    private val projectTabs = mutableListOf<File>()
    private val buildQueue = java.util.ArrayDeque<QueuedBuild>()
    private var cachedSymbolFiles: List<File>? = null
    private var cachedSymbolFilesProject: File? = null
    private val tabScrollPositions = mutableMapOf<String, Int>()   // file -> scroll Y
    private val tabCursorPositions = mutableMapOf<String, Int>()  // file -> cursor
    private var isBuildRunning = false

    /** UI 상태 변수 (MainActivity에서 포팅) */
    var pathText: String by mutableStateOf("")
    var fileContent: String by mutableStateOf("")
    val treeItems = mutableStateListOf<FileItem>()
    var selectedTab by mutableStateOf(0)
    /** Editor tabs shown by the Compose shell (observed state). */
    val tabs = androidx.compose.runtime.snapshots.SnapshotStateList<String>()
    var editorProblems: MutableList<EditorProblem> = mutableStateListOf()

    /** Task names for the in-flight build, handed to extensions via onBuildStart. */
    private var activeBuildTasks: List<String> = emptyList()

    /** Result of the build that just ended, handed to extensions via onBuildFinish. */
    private var pendingBuildSuccess: Boolean = false
    private var unsavedChanges = false
    private var suppressEditorChange = false
    private var isProgrammaticScroll = false
    private val unsavedFiles = mutableSetOf<String>()            // absolute paths
    private var findReplaceVisible = false
    private var currentSearchQuery = ""
    private var searchResultCount = 0
    private var diffSnapshotText: String? = null
    private val blockedEditorExtensions = setOf("bin", "jar", "so", "apk", "aab", "dex", "class")
    internal val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoSaveJob: Job? = null
    private var liveDiagnosticsJob: Job? = null
    private var pythonDiagnosticsJob: Job? = null
    private val conversionPromptedFiles = mutableSetOf<String>()
    private lateinit var searchEverywhere: SearchEverywhereDialog
    private lateinit var codeContextMenu: CodeContextMenu
    private lateinit var memoryMonitor: MemoryMonitor
    private lateinit var commandPalette: CommandPalette
    private lateinit var variantSelector: VariantSelector
    private var syncManager: GradleSyncManager? = null
    private lateinit var backgroundTaskManager: BackgroundTaskManager
    internal lateinit var gitManager: GitManager

    private var virtualLines: MutableList<String>? = null
    private var virtualWindowStart: Int = 0
    private var isVirtualScroll: Boolean = false

    internal lateinit var jreGradleInstaller: JreGradleInstaller
    internal lateinit var gradleRunner: GradleRunner
    private lateinit var generator: ProjectTemplateGenerator
    private lateinit var fileAdapter: FileAdapter
    private lateinit var logcatManager: LogcatManager
    private val expandedFileTreePaths = mutableSetOf<String>()

    private enum class ProjectKind {
        ANDROID,
        PYTHON,
        JAR,
        EXE,
        GAME,
        LUA_GAME,
        GODOT
    }

    internal lateinit var autoComplete: KotlinAutoComplete
    private lateinit var webAutoComplete: com.aide.mobilestudio.web.WebAutoComplete
    private lateinit var problemsAdapter: ProblemsAdapter
    private val editorPairs = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"')
    private var ctrlSymbolHighlightSpans: List<Any> = emptyList()
    private var editorBeforeChange = ""
    private var autoPairEdit = false
    private var gradleSyncNeeded = false
    private var gradleSyncRunning = false
    private var bottomPanelExpandedHeight = 0

    /** Remembered width of the panel when it is docked beside the editor (landscape). */
    private var bottomPanelExpandedWidth = 0
    private var bottomPanelHeightAnimator: ValueAnimator? = null
    private var currentBottomPanelTab = "build"

    // IDE top menu bar dropdown state (File / Edit / View / ... submenus).
    private var openIdeMenuGroup: String? = null
    private var ideMenuAnchor: View? = null
    private var menuBarClosedHeightPx: Int = 0

    // Shell state: persistent cwd + banner-once
    private var shellCwd: File? = null
    private var shellBannerShown = false

    // Shell environment state (persists across commands)
    private var shellEnv: MutableMap<String, String> = mutableMapOf()

    // Terminal tab manager (multiple terminal sessions)
    private var terminalTabManager: TerminalTabManager? = null

    // StudioShellTools lazy-install offer (Y/N) state
    private data class PendingToolOffer(
        val tool: StudioToolsIndex.ToolEntry,
        val originalCommand: String
    )
    private var pendingToolOffer: PendingToolOffer? = null

    // Problems are merged from live diagnostics and Gradle output.
    private var liveErrors: List<BuildProblem> = emptyList()
    private var buildErrors: List<BuildProblem> = emptyList()
    private var problemsRefreshScheduled = false
    private var swapManager: SwapManager? = null
    private var currentDiagnostics: List<com.aide.mobilestudio.analysis.Diagnostic> = emptyList()
    private var lightbulbView: View? = null
    private var lightbulbVisible = false

    // ── StudioHost: bridge between the Compose IDE shell (StudioShell.kt)
    //    and this activity's imperative machinery. ─────────────────────────

    override val studioActivity: MainActivity
        get() = this

    override val studioScope: CoroutineScope
        get() = scope

    override val studioProjectDir: File?
        get() = currentProjectDir

    override val studioCurrentFile: File?
        get() = currentFile

    override val studioTabs: MutableList<String>
        get() = tabs

    override val studioSelectedTab: Int
        get() = selectedTab

    override val studioPathText: String
        get() = pathText

    override var studioFileContent: String
        get() = fileContent
        set(value) { fileContent = value }

    override val studioTreeItems: SnapshotStateList<FileItem>
        get() = treeItems

    override val studioEditorProblems: MutableList<EditorProblem>
        get() = editorProblems

    override val studioAnalysisEngine: com.aide.mobilestudio.analysis.AnalysisEngine?
        get() = analysisEngine

    override var studioToolsPanelForResult: ToolsPanel? = null
    override var studioNewProjectDialogVisible: Boolean by mutableStateOf(false)
    override val studioPickDir: ActivityResultLauncher<android.net.Uri?> = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { u ->
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val resolved = resolveTreeUriDir(u)
            if (resolved != null) {
                openProject(resolved)
                putLastProject(resolved.absolutePath)
            }
        }
    }

    override var studioOnNewProjectDirPicked: ((String) -> Unit)? = null

    override val studioPickProjectViaFileManager: ActivityResultLauncher<android.content.Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val dir = result.data?.getStringExtra(FileManagerActivity.EXTRA_SELECTED_DIR)
                val newProject = result.data?.getBooleanExtra(FileManagerActivity.EXTRA_NEW_PROJECT, false) == true
                if (dir != null) {
                    val file = java.io.File(dir)
                    if (newProject) {
                        studioNewProjectDir = file
                        studioNewProjectDialogVisible = true
                    } else {
                        openProject(file)
                        putLastProject(file.absolutePath)
                    }
                }
            }
        }

    /** Parent folder selected for a new project via the in-app file manager. */
    private var studioNewProjectDir: java.io.File? = null

    override val studioPickNewProjectDir: ActivityResultLauncher<android.net.Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { u ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        u,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                val resolved = resolveTreeUriDir(u)
                if (resolved != null) studioOnNewProjectDirPicked?.invoke(resolved.absolutePath)
            }
        }

    override fun studioOpenProject(dir: File) { openProject(dir) }

    override fun studioOpenFileInEditor(file: File) { openFile(file) }

    override fun studioNewEditorTab(name: String) {
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

    override fun studioCloseEditorTab(index: Int) {
        if (index !in tabs.indices) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) tabs.add("MainActivity.kt")
        selectedTab = selectedTab.coerceAtMost(tabs.lastIndex)
        pathText = tabs[selectedTab]
        loadFileContentForTab(tabs[selectedTab])
    }

    override fun studioSelectTab(index: Int) {
        if (index !in tabs.indices) return
        selectedTab = index
        pathText = tabs[index]
        loadFileContentForTab(tabs[index])
    }

    override fun studioSelectEditorLine(line: Int) {
        val editor = EditorPaneState.editor ?: return
        val textLayout = editor.layout ?: return
        val targetLine = line.coerceIn(0, (textLayout.lineCount - 1).coerceAtLeast(0))
        val offset = textLayout.getLineStart(targetLine)
        editor.setSelection(offset)
        editor.requestFocus()
    }

    override fun studioLoadFileTree(root: File) { loadFileTree(root) }

    override fun studioToggleTreeFolder(dir: File) {
        val key = canonicalPath(dir)
        if (key in expandedFileTreePaths) expandedFileTreePaths.remove(key)
        else expandedFileTreePaths.add(key)
        currentProjectDir?.let { loadFileTree(it) }
    }

    override fun studioSaveCurrentFile() { saveCurrentFile() }

    override fun studioRequestLiveDiagnostics() {
        val file = currentFile ?: return
        val engine = analysisEngine ?: return
        engine.requestAnalysis(file, EditorPaneState.content)
    }

    override fun studioGetLastProject(): String? {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        return prefs.getString("last_project", null)
    }

    override fun studioPutLastProject(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("last_project", path).apply()
        addRecentProject(path)
    }

    override fun studioGetRecentProjects(): List<String> {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_projects", "") ?: ""
        return if (json.isBlank()) emptyList() else json.split("|").filter { it.isNotEmpty() }
    }

    override fun studioGetRecentFiles(): List<String> {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_files", "") ?: ""
        return if (json.isBlank()) emptyList() else json.split("|").filter { it.isNotEmpty() }
    }

    // ── New StudioHost hooks for the Compose shell ──────────────────────

    override val studioFileEncoding: String
        get() = currentFileEncoding

    override val studioIsBuildRunning: Boolean
        get() = isBuildRunning

    override val studioTabDirty: Boolean
        get() = unsavedChanges

    override val studioGitBranch: String?
        get() = currentProjectDir?.let { GitManager(this).getStatus(it).currentBranch.takeIf { it.isNotBlank() } }

    override val studioConnectedDevice: String?
        get() {
            return runCatching {
                AdbDeviceManager.listDevices().firstOrNull()?.serial
            }.getOrNull()
        }

    override val studioPackageName: String?
        get() = packageName

    override fun studioScheduleAutosave() {
        rescheduleAutoSave()
    }

    override fun studioStopBuild() {
        stopBuild()
    }

    override fun studioInstallAndLaunchLatest() {
        installLatestApk()
    }

    override fun studioShowEncodingDialog() {
        showEncodingDialog()
    }

    private val layoutPreviewLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val path = result.data?.getStringExtra("extra_file_path")
            if (!path.isNullOrBlank()) {
                val file = java.io.File(path)
                if (file.exists()) {
                    // Re-open so the Compose editor picks up the visually edited XML.
                    studioOpenFileAt(file, 1)
                }
            }
        }

    override fun studioOpenLayoutPreview(file: File) {
        layoutPreviewLauncher.launch(
            Intent(this, LayoutPreviewActivity::class.java).putExtra("extra_file_path", file.absolutePath)
        )
    }

    override fun studioGoToLine() {
        val editor = EditorPaneState.editor
        if (editor == null) {
            showToast("Open a file first")
            return
        }
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Line number"
        }
        AlertDialog.Builder(this)
            .setTitle("Go to Line")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val line = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                studioSelectEditorLine(line)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun studioOpenFileAt(file: File, line: Int) {
        openFile(file)
        Handler(Looper.getMainLooper()).postDelayed({
            studioSelectEditorLine(line)
        }, 150)
    }

    fun studioAddRecentFile(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_files", "") ?: ""
        val recent = if (json.isBlank()) mutableListOf<String>() else json.split("|").filter { it.isNotEmpty() }.toMutableList()
        recent.remove(path)
        recent.add(0, path)
        if (recent.size > 20) recent.removeAt(recent.size - 1)
        prefs.edit().putString("recent_files", recent.joinToString("|")).apply()
    }

    fun studioAddRecentProject(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val recent = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE).getString("recent_projects", "")?.split("|")?.filter { it.isNotEmpty() }?.toMutableList() ?: mutableListOf()
        recent.remove(path)
        recent.add(0, path)
        if (recent.size > 10) recent.removeAt(recent.size - 1)
        prefs.edit().putString("recent_projects", recent.joinToString("|")).apply()
    }

    private data class SymbolTarget(
        val file: File,
        val line: Int,
        val column: Int,
        val preview: String
    )

    private data class QueuedBuild(
        val project: File,
        val tasks: List<String>
    )

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

    internal fun putLastProject(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("last_project", path).apply()
        val recent = getRecentProjects().toMutableList()
        recent.remove(path)
        recent.add(0, path)
        if (recent.size > 10) recent.removeAt(recent.size - 1)
        prefs.edit().putString("recent_projects", recent.joinToString("|")).apply()
    }

    private fun getLastProject(): String? = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE).getString("last_project", null)

    private fun getRecentProjects(): List<String> {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_projects", "") ?: ""
        return if (json.isBlank()) emptyList() else json.split("|").filter { it.isNotEmpty() }
    }

    private fun getRecentFiles(): List<String> {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_files", "") ?: ""
        return if (json.isBlank()) emptyList() else json.split("|").filter { it.isNotEmpty() }
    }

    private fun addRecentFile(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_files", "") ?: ""
        val recent = if (json.isBlank()) mutableListOf<String>() else json.split("|").filter { it.isNotEmpty() }.toMutableList()
        recent.remove(path)
        recent.add(0, path)
        if (recent.size > 20) recent.removeAt(recent.size - 1)
        prefs.edit().putString("recent_files", recent.joinToString("|")).apply()
    }

    private fun addRecentProject(path: String) {
        val prefs = getSharedPreferences("mobilestudio_settings", Context.MODE_PRIVATE)
        val json = prefs.getString("recent_projects", "") ?: ""
        val recent = if (json.isBlank()) mutableListOf<String>() else json.split("|").filter { it.isNotEmpty() }.toMutableList()
        recent.remove(path)
        recent.add(0, path)
        if (recent.size > 10) recent.removeAt(recent.size - 1)
        prefs.edit().putString("recent_projects", recent.joinToString("|")).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileStudioCrashGuard.activityCreated(this)

        UiTheme.resolve(this)
        wireExtensionBridge()
        setContent {
            ThemeManager.ProvideTheme {
                StudioApp(this@MainActivity)
            }
        }
        // bindViews() - removed: Compose-based StudioApp now owns the UI;
        // the old View-based drawer_layout / code_editor / etc. no longer exist.

        // setupFileTree() - removed: fileTree RecyclerView no longer exists in Compose UI.

        jreGradleInstaller = JreGradleInstaller(
            context = this,
            onLog = { line -> setStatus(line.take(120)) },
            onProgress = { pct ->
                runOnUiThread {
                    if (::buildProgress.isInitialized) {
                        buildProgress.visibility = View.VISIBLE
                        buildProgress.progress = pct
                    }
                }
            }
        )
        gradleRunner = GradleRunner(this, jreGradleInstaller)
        generator = ProjectTemplateGenerator(this)


        buildScheduler = BuildScheduler(
            gradleRunner = gradleRunner,
            realtimeCompiler = com.aide.mobilestudio.analysis.RealtimeCompiler(this),
            onAutoCompileLine = { line, level ->
                runOnUiThread {
                    if (::buildOutput.isInitialized) buildOutput.appendLine(line, level)
                    if (::buildMonitor.isInitialized) buildMonitor.onLine(line, level)
                }
            },
            onProblemsUpdated = { problems ->
                buildErrors = problems
                refreshProblems()

                val errCount = problems.count { it.severity == BuildProblem.Severity.ERROR }
                val warnCount = problems.count { it.severity == BuildProblem.Severity.WARNING }
                runOnUiThread {
                    badgeErrors?.text = if (errCount > 0) " \u2716 $errCount " else ""
                    badgeErrors?.visibility = if (errCount > 0) View.VISIBLE else View.GONE
                    badgeWarnings?.text = if (warnCount > 0) " \u26A0 $warnCount " else ""
                    badgeWarnings?.visibility = if (warnCount > 0) View.VISIBLE else View.GONE
                    tvStatusRight?.text = ""
                }
            },
            onBuildState = { state ->
                runOnUiThread {
                    if (::buildProgress.isInitialized) {
                        buildProgress.visibility = if (state == BuildScheduler.State.BUILDING) View.VISIBLE else View.GONE
                    }
                    when (state) {
                        BuildScheduler.State.BUILDING -> setStatus("Compiling...")
                        BuildScheduler.State.QUEUED -> setStatus("Compile queued (waiting for current build)")
                        BuildScheduler.State.IDLE -> {}
                    }
                }
            }
        )

        // setupFileTree() - removed: fileTree RecyclerView no longer exists in Compose UI.
        // setupEditor() - removed: Compose UI replaces editor View.
        // setupTabs()  # removed: Compose UI
        // setupBottomPanel()  # removed: Compose UI
        // setupToolbar()  # removed: Compose UI
        // setupTopBar()  # removed: Compose UI
        // setupSideRail()  # removed: Compose UI
        // setupWelcomeScreen()  # removed: Compose UI
        // setupLogcat()  # removed: Compose UI
        // setupEditorFeatures() - removed: Compose UI replaces editor View.
        // setupFindReplace()  # removed: Compose UI
        // setupBreadcrumb()  # removed: Compose UI
        // startAutoSave()  # removed: Compose UI
        // deferStartupWork()  # removed: Compose UI

        // Handle "open_file"/"open_line" extras (game error screen → editor jump)
        intent.getStringExtra("open_file")?.let { path ->
            val f = File(path)
            if (f.isFile) {
                val line = intent.getIntExtra("open_line", 0)
                scope.launch {
                    kotlinx.coroutines.delay(600)
                    runOnUiThread { openFileAtLine(f, line) }
                }
            }
        }

        searchEverywhere = SearchEverywhereDialog(this).apply {
            onResultClick = { result ->
                if (result.type == SearchEverywhereDialog.ResultType.GRADLE_TASK) {
                    runGradleTask(result.name)
                } else if (result.path.isNotEmpty()) {
                    currentProjectDir?.let { root ->
                        val file = File(root, result.path)
                        if (file.exists()) {
                            if (result.line > 0) {
                                openFileAtLine(file, result.line)
                            } else {
                                openFile(file)
                            }
                        }
                    }
                }
            }
        }
        codeContextMenu = CodeContextMenu(this)

        memoryMonitor = MemoryMonitor(this).apply {
            addListener(object : MemoryMonitor.MemoryListener {
                override fun onMemoryPressure(level: MemoryMonitor.PressureLevel) {
                    if (level == MemoryMonitor.PressureLevel.CRITICAL) {
                        runOnUiThread {
                            setStatus("Low memory - cleanup recommended")
                        }
                    }
                }
                override fun onMemorySnapshot(snapshot: MemoryMonitor.MemorySnapshot) {
                    runOnUiThread {
                        val emoji = when (snapshot.pressureLevel) {
                            MemoryMonitor.PressureLevel.CRITICAL -> "\u26A0"
                            MemoryMonitor.PressureLevel.LOW -> "\u26A0"
                            MemoryMonitor.PressureLevel.MODERATE -> "\u25CF"
                            MemoryMonitor.PressureLevel.NORMAL -> "\u25CF"
                        }
                        val color = when (snapshot.pressureLevel) {
                            MemoryMonitor.PressureLevel.CRITICAL -> "#F05C5C"
                            MemoryMonitor.PressureLevel.LOW -> "#E3B963"
                            MemoryMonitor.PressureLevel.MODERATE -> "#E5C07B"
                            MemoryMonitor.PressureLevel.NORMAL -> "#5C6370"
                        }
                        tvMemoryIndicator?.text = "$emoji ${snapshot.usedRamMB}/${snapshot.totalRamMB}MB"
                        tvMemoryIndicator?.setTextColor(Color.parseColor(color))
                        tvMemoryIndicator?.setOnClickListener {
                            showMemoryDialog(snapshot)
                        }
                    }
                }
                override fun onCleanupRecommended(actions: List<MemoryMonitor.CleanupAction>) {
                    memoryMonitor.executeCleanup(actions)
                }
            })
            startMonitoring(BuildToolsConfig.memoryMonitorIntervalMs(this@MainActivity))
        }

        commandPalette = CommandPalette(this)
        commandPalette.wireActions(this)

        variantSelector = VariantSelector(this)

        backgroundTaskManager = BackgroundTaskManager(this)

        gitManager = GitManager(this)

        // Do NOT allocate a ~2GB mmap swap file on launch: it forces a huge
        // allocation + page-in and is the main cause of the phone heating up
        // just from opening the IDE. Swap is opt-in from Build Tools instead.
        swapManager = SwapManager(this)

        // Initialize volume gesture handler
        volumeGestureHandler = VolumeGestureHandler(object : VolumeGestureHandler.Callbacks {
            override fun onVolumeDownSingleTap() {
                // No-op: volume down single tap is now unused
            }
            override fun onVolumeDownDoubleTap() {
                // Open Command Palette
                commandPalette.show()
            }
            override fun onVolumeDownLongPressStart() {
                // Show symbol info for selected text or word under cursor
                if (codeEditor.hasFocus()) {
                    val selStart = codeEditor.selectionStart
                    val selEnd = codeEditor.selectionEnd
                    val selected = if (selStart != selEnd) {
                        codeEditor.text?.substring(
                            selStart.coerceAtMost(selEnd),
                            selEnd.coerceAtLeast(selStart)
                        ).orEmpty()
                    } else ""
                    if (selected.isNotBlank()) {
                        showSymbolInfoForText(selected)
                    } else if (selStart in (codeEditor.text?.indices ?: IntRange.EMPTY)) {
                        showContextMenuAtOffset(selStart, codeEditor)
                    }
                    vibrate(10)
                }
            }
            override fun onVolumeDownLongPressEnd() {}
            override fun onVolumeDownRelease() {}

            override fun onVolumeUpSingleTap() {
                showSearchEverywhere("All")
            }
            override fun onVolumeUpDoubleTap() {
                toggleBuildLogPanel()
            }
            override fun onVolumeUpLongPressStart() {
                toggleShellPanel()
            }
            override fun onVolumeUpRelease() {}
        })

        Handler(Looper.getMainLooper()).postDelayed({
            VersionNoticeDialog.showIfNeeded(this)
        }, 450)

        Handler(Looper.getMainLooper()).postDelayed({
            checkForAppUpdate()
        }, 2000)

        freezeDetector = UiFreezeDetector(this).apply {
            setListener(object : UiFreezeDetector.FreezeListener {
                override fun onFreezeDetected(durationMs: Long) {
                    Log.w("MainActivity", "UI freeze detected: ${durationMs}ms")
                }
                override fun onFreezeResolved(durationMs: Long) {
                    Log.i("MainActivity", "UI freeze resolved after ${durationMs}ms")
                }
                override fun onDumpCaptured(dumpFile: java.io.File) {
                    Log.i("MainActivity", "Thread dump saved: ${dumpFile.absolutePath}")
                }
            })
        }

        // Show tutorial on first launch (including volume gesture onboarding)
        showTutorialIfNeeded()
    }

    private var tutorialGuide: TutorialGuide? = null

    private fun showTutorialIfNeeded() {
        val prefs = getSharedPreferences("tutorial", Context.MODE_PRIVATE)
        val isFirstLaunch = prefs.getBoolean("first_launch", true)
        if (isFirstLaunch) {
            prefs.edit().putBoolean("first_launch", false).apply()
            Handler(Looper.getMainLooper()).postDelayed({ showTutorial() }, 800)
        }
    }

    private fun showTutorial() {
        val guide = TutorialGuide(this)
        tutorialGuide = guide
        val isKo = AppLanguage.isKorean(this)
        guide.show(
            steps = listOf(
                TutorialGuide.Step(
                    targetView = { findViewById(R.id.btn_new_file) },
                    title = if (isKo) "새 파일 만들기" else "New File",
                    description = if (isKo) "+ 버튼으로 새 파일을 만듭니다. 길게 누르면 템플릿 선택." else "Tap + to create a file. Long-press for templates.",
                    position = TutorialGuide.TooltipPosition.BELOW,
                    type = TutorialGuide.StepType.INTERACTIVE
                ),
                TutorialGuide.Step(
                    targetView = { findViewById(R.id.btn_new_folder) },
                    title = if (isKo) "폴더 만들기" else "New Folder",
                    description = if (isKo) "폴더 아이콘으로 새 폴더를 만듭니다." else "Tap folder icon to create a new folder.",
                    position = TutorialGuide.TooltipPosition.BELOW,
                    type = TutorialGuide.StepType.INTERACTIVE
                ),
                TutorialGuide.Step(
                    // The View menu lost its own row in the toolbar redesign and
                    // hangs off the hamburger now, so that is what the step has
                    // to point at - pointing at a view that is no longer in the
                    // tree handed the guide a null and took the step down.
                    targetView = { findViewById(R.id.btn_menu) },
                    title = if (isKo) "파일 트리/메뉴 열기" else "File Tree / Menu",
                    description = if (isKo) "왼쪽 가장자리 스와이프 또는 메뉴 > View > Explorer 로 파일 트리를 엽니다. 검색도 가능." else "Swipe from left edge or open Menu > View > Explorer to open the file tree. Search supported.",
                    position = TutorialGuide.TooltipPosition.RIGHT,
                    type = TutorialGuide.StepType.INTERACTIVE
                ),
                TutorialGuide.Step(
                    targetView = { findViewById(R.id.btn_command_palette) },
                    title = if (isKo) "명령 팔레트" else "Command Palette",
                    description = if (isKo) "모든 명령어 검색/실행. 볼륨 아래 더블탭으로도 열림." else "Search/run all commands. Also: Volume Down double-tap.",
                    position = TutorialGuide.TooltipPosition.BELOW,
                    type = TutorialGuide.StepType.INTERACTIVE
                ),
                TutorialGuide.Step(
                    targetView = { findViewById(R.id.btn_ai_chat) },
                    title = if (isKo) "AI 채팅" else "AI Chat",
                    description = if (isKo) "코드 질문, 리팩토링, 생성. 컨텍스트 자동 포함." else "Ask code questions, refactor, generate. Context auto-included.",
                    position = TutorialGuide.TooltipPosition.LEFT,
                    type = TutorialGuide.StepType.INTERACTIVE
                ),
                TutorialGuide.Step(
                    targetView = { findViewById(R.id.code_editor) },
                    title = if (isKo) "에디터 제스처" else "Editor Gestures",
                    description = if (isKo) "좌우 스와이프: 탭 전환  |  핀치: 글자 크기 조절  |  볼륨 아래 길게: 함수 이동" else "Horizontal swipe: switch tabs  |  Pinch: zoom text  |  Vol Down long-press: go to function",
                    position = TutorialGuide.TooltipPosition.ABOVE,
                    type = TutorialGuide.StepType.INFO
                ),
                TutorialGuide.Step(
                    targetView = { findViewById(R.id.bottom_panel) },
                    title = if (isKo) "하단 패널 제스처" else "Bottom Panel Gestures",
                    description = if (isKo) "빌드/로그캣/쉘/검색 탭을 좌우 스와이프로 전환. 볼륨 위 더블탭: 빌드 토글, 길게: 쉘 토글" else "Swipe to switch Build/Logcat/Shell/Search tabs. Vol Up double-tap: toggle Build, long-press: toggle Shell",
                    position = TutorialGuide.TooltipPosition.ABOVE,
                    type = TutorialGuide.StepType.INFO
                ),
                TutorialGuide.Step(
                    targetView = { findViewById(R.id.btn_shell_toggle) },
                    title = if (isKo) "터미널/쉘" else "Terminal / Shell",
                    description = if (isKo) "내장 터미널. 볼륨 위 길게 누르기로 토글." else "Built-in terminal. Toggle with Volume Up long-press.",
                    position = TutorialGuide.TooltipPosition.ABOVE,
                    type = TutorialGuide.StepType.INFO
                ),
                TutorialGuide.Step(
                    targetView = { findViewById(R.id.btn_settings) },
                    title = if (isKo) "설정/테마" else "Settings / Themes",
                    description = if (isKo) "테마 13종(다크/라이트), 폰트 크기, 키바인딩 등 설정." else "13 themes (dark/light), font size, keybindings, and more.",
                    position = TutorialGuide.TooltipPosition.LEFT,
                    type = TutorialGuide.StepType.INFO
                )
            ),
            onComplete = {
                showToast(if (isKo) "튜토리얼 완료! 자유롭게 둘러보세요." else "Tutorial complete! Feel free to explore.")
            },
            onSkip = {
                showToast(if (isKo) "튜토리얼 건너뜀. 언제든 드로어 메뉴에서 다시 시작 가능." else "Tutorial skipped. Restart anytime from drawer menu.")
            }
        )
    }

    private fun showThemeDialog() {
        val themes = ThemeManager.AppTheme.values()
        val currentTheme = ThemeManager.getTheme(this)
        val items = themes.map { it.label }.toTypedArray()
        val checkedIndex = themes.indexOfFirst { it == currentTheme }
        val isKo = AppLanguage.isKorean(this)

        AlertDialog.Builder(this)
            .setTitle(if (isKo) "테마 선택" else "Select Theme")
            .setSingleChoiceItems(items, checkedIndex) { dialog, which ->
                val selectedTheme = themes[which]
                if (selectedTheme == currentTheme) {
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }
                // Ask before applying: the theme is only persisted once the
                // user confirms, so declining leaves the current theme fully
                // intact (no half-restyled UI).
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (isKo) "테마 변경" else "Change Theme")
                    .setMessage(
                        if (isKo) "테마를 바꾸려면 앱을 재시작해야 합니다."
                        else "The app needs to restart to apply the new theme."
                    )
                    .setPositiveButton(if (isKo) "네, 재시작하겠습니다" else "Yes, restart now") { _, _ ->
                        ThemeManager.setTheme(this@MainActivity, selectedTheme)
                        recreate()
                    }
                    .setNegativeButton(if (isKo) "아니요, 나중에 다시 시도하겠습니다" else "No, try again later", null)
                    .show()
            }
            .setNegativeButton(if (isKo) "취소" else "Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val isKorean = AppLanguage.isKorean(this)

        data class Item(val label: String, val onClick: () -> Unit)

        /*
         * Grouped rather than a flat list. Six unrelated entries read as one
         * undifferentiated pile, and the two font sizes - easy to confuse -
         * sat next to each other with nothing saying which surface each one
         * changes, so the grouping carries that instead.
         */
        val groups = listOf(
            (if (isKorean) "화면" else "Appearance") to listOf(
                Item(if (isKorean) "테마 변경" else "Change Theme") { showThemeDialog() },
                Item(if (isKorean) "에디터 글자 크기" else "Editor Font Size") { showFontSizeDialog() },
                Item(if (isKorean) "터미널 글자 크기" else "Terminal Font Size") { showTerminalFontSizeDialog() },
            ),
            (if (isKorean) "입력" else "Input") to listOf(
                Item(if (isKorean) "키바인딩 / 제스처" else "Keybindings / Gestures") { showKeybindingDialog() },
            ),
            (if (isKorean) "일반" else "General") to listOf(
                Item(if (isKorean) "언어" else "Language") { showLanguageDialog() },
                Item(if (isKorean) "튜토리얼 다시 보기" else "Restart Tutorial") {
                    showToast(if (isKorean) "튜토리얼을 다시 시작합니다" else "Tutorial restarted")
                    showTutorial()
                },
            ),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
            setBackgroundColor(StudioUi.color(StudioUi.Card))
        }

        // Set once the dialog exists so a row can close it before opening its
        // own dialog; without that the two would sit on top of each other.
        var dialog: AlertDialog? = null

        groups.forEach { (header, items) ->
            root.addView(TextView(this).apply {
                text = header
                textSize = StudioUi.TypeCaption
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, dp(16), 0, dp(6))
            })
            items.forEach { item ->
                root.addView(TextView(this).apply {
                    text = item.label
                    textSize = StudioUi.TypeBody
                    setTextColor(StudioUi.color(StudioUi.Text))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(13), dp(12), dp(13))
                    background = StudioUi.ripple(
                        this@MainActivity,
                        StudioUi.rounded(
                            this@MainActivity,
                            fill = StudioUi.Card2,
                            stroke = StudioUi.Border,
                            radiusDp = StudioUi.RadiusMd,
                        ),
                    )
                    setOnClickListener {
                        dialog?.dismiss()
                        item.onClick()
                    }
                })
            }
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(if (isKorean) "설정" else "Settings")
            .setView(ScrollView(this).apply { addView(root) })
            .setNegativeButton(if (isKorean) "닫기" else "Close", null)
            .create()
        dialog?.show()
    }

    private fun showTerminalFontSizeDialog() {
        val prefs = getSharedPreferences("terminal_options", MODE_PRIVATE)
        val sizes = floatArrayOf(8f, 10f, 12f, 14f, 18f, 24f)
        val current = prefs.getFloat("font_size", 12f)
        val checked = sizes.indexOfFirst { it == current }
        val items = sizes.map { "${it.toInt()}px" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(if (AppLanguage.isKorean(this)) "터미널 글자 크기" else "Terminal Font Size")
            .setSingleChoiceItems(items, if (checked >= 0) checked else 2) { _, which ->
                val size = sizes[which]
                prefs.edit().putFloat("font_size", size).apply()
                terminalTabManager?.applyFontSize(size)
            }
            .setNegativeButton(if (AppLanguage.isKorean(this)) "취소" else "Cancel", null)
            .show()
    }

    private fun showFontSizeDialog() {
        val currentSize = codeEditor.getEditorTextSize()
        val sizes = floatArrayOf(10f, 11f, 12f, 12.5f, 13f, 14f)
        val items = sizes.map { "${it}sp" }.toTypedArray()
        val checked = sizes.indexOfFirst { it == currentSize.coerceIn(10f, 14f) }
        AlertDialog.Builder(this)
            .setTitle(if (AppLanguage.isKorean(this)) "에디터 글자 크기" else "Editor Font Size")
            .setSingleChoiceItems(items, if (checked >= 0) checked else 3) { _, which ->
                codeEditor.setEditorTextSize(sizes[which])
            }
            .setNegativeButton(if (AppLanguage.isKorean(this)) "취소" else "Cancel", null)
            .show()
    }

    private fun showKeybindingDialog() {
        val items = arrayOf(
            if (AppLanguage.isKorean(this)) "볼륨 버튼 제스처" else "Volume Button Gestures",
            if (AppLanguage.isKorean(this)) "에디터 스와이프" else "Editor Swipe",
            if (AppLanguage.isKorean(this)) "핀치 줌" else "Pinch Zoom"
        )
        AlertDialog.Builder(this)
            .setTitle(if (AppLanguage.isKorean(this)) "키바인딩 / 제스처" else "Keybindings / Gestures")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showVolumeGestureInfo()
                    1 -> showToast(if (AppLanguage.isKorean(this)) "에디터 좌우 스와이프로 탭 전환" else "Swipe editor left/right to switch tabs")
                    2 -> showToast(if (AppLanguage.isKorean(this)) "핀치 제스처로 글자 크기 조절" else "Pinch to zoom text size")
                }
            }
            .setNegativeButton(if (AppLanguage.isKorean(this)) "닫기" else "Close", null)
            .show()
    }

    private fun showVolumeGestureInfo() {
        val msg = if (AppLanguage.isKorean(this)) {
            """볼륨 아래:
단일 탭: 다음 탭
더블 탭: 명령 팔레트
길게 누르기: 함수 이동 (하이라이트)

볼륨 위:
단일 탭: 전체 검색
더블 탭: 빌드 패널 토글
길게 누르기: 쉘 패널 토글"""
        } else {
            """Volume Down:
Single tap: Next tab
Double tap: Command Palette
Long press: Go to function (highlight)

Volume Up:
Single tap: Search Everywhere
Double tap: Toggle Build panel
Long press: Toggle Shell panel"""
        }
        AlertDialog.Builder(this)
            .setTitle(if (AppLanguage.isKorean(this)) "볼륨 버튼 제스처" else "Volume Button Gestures")
            .setMessage(msg.trimIndent())
            .setPositiveButton(if (AppLanguage.isKorean(this)) "확인" else "OK", null)
            .show()
    }

    private fun showLanguageDialog() {
        val items = arrayOf("English", "한국어")
        val current = if (AppLanguage.isKorean(this)) 1 else 0
        AlertDialog.Builder(this)
            .setTitle(if (AppLanguage.isKorean(this)) "언어 선택" else "Select Language")
            .setSingleChoiceItems(items, current) { _, which ->
                AppLanguage.set(this@MainActivity, if (which == 1) "ko" else "en")
                recreate()
            }
            .setNegativeButton(if (AppLanguage.isKorean(this)) "취소" else "Cancel", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentFile()
        freezeDetector?.stopMonitoring()
        memoryMonitor.stopMonitoring()
    }

    override fun onResume() {
        super.onResume()
        freezeDetector?.startMonitoring(this)
        memoryMonitor.startMonitoring(BuildToolsConfig.memoryMonitorIntervalMs(this))
        handleOpenAiPanelIntent()
        handleRunCommandIntent()
        handleTerminalSelfTestIntent()
        setupKeyboardListener()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Phase 6.2: Handle tablet/landscape mode changes
        if (resources.configuration.smallestScreenWidthDp >= 600) {
            // Tablet mode: increase UI density
            applyTabletMode()
        } else {
            // Phone mode: reset to default
            applyPhoneMode()
        }
        // Rebuild layout for orientation changes
        recreate()
    }

    private fun applyTabletMode() {
        // Increase UI density for tablets
        runOnUiThread {
            // Restore full toolbars on tablets
            findViewById<HorizontalScrollView>(R.id.top_text_menu)?.visibility = View.VISIBLE
            findViewById<View>(R.id.build_icon_toolbar)?.visibility = View.VISIBLE
            // Increase toolbar heights
            val primaryToolbar = findViewById<LinearLayout>(R.id.build_icon_toolbar)
            primaryToolbar?.layoutParams?.height = dp(44)
            primaryToolbar?.requestLayout()
        }
    }

    private fun applyPhoneMode() {
        runOnUiThread {
            // Compact mode for phones. Only the toolbar height is adjusted
            // here: whether the menu row gets its own row or scrolls inside
            // the toolbar row is decided by MainActivityLayoutFactory when the
            // layout is built, so hiding it here would just leave a gap (or
            // remove the menus entirely in landscape). The toolbar itself must
            // never be hidden - it carries build / sync / back / forward.
            findViewById<View>(R.id.build_icon_toolbar)?.visibility = View.VISIBLE
            val primaryToolbar = findViewById<LinearLayout>(R.id.build_icon_toolbar)
            primaryToolbar?.layoutParams?.height = dp(40)
            primaryToolbar?.requestLayout()
        }
    }

    private fun setupKeyboardListener() {
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val keyboardHeight = imeInsets.bottom
            val bottomPanel = findViewById<LinearLayout>(R.id.bottom_panel)
            if (bottomPanel != null) {
                val params = bottomPanel.layoutParams as LinearLayout.LayoutParams
                if (keyboardHeight > 0) {
                    params.bottomMargin = keyboardHeight
                } else {
                    params.bottomMargin = 0
                }
                bottomPanel.layoutParams = params
            }
            insets
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenAiPanelIntent()
        handleRunCommandIntent()
        handleTerminalSelfTestIntent()
    }

    /**
     * "terminal_self_test" extra: run the on-device terminal verification
     * suite (PTY isolation, exit codes, signals, Ctrl+C, markers).
     *
     *   adb shell am start -n com.aide.mobilestudio/.MainActivity --ez terminal_self_test true
     *   adb logcat -s TerminalTest
     */
    private fun handleTerminalSelfTestIntent() {
        if (!intent.getBooleanExtra("terminal_self_test", false)) return
        intent.removeExtra("terminal_self_test")
    }

    private fun handleOpenAiPanelIntent() {
        if (intent.getBooleanExtra("open_ai_panel", false)) {
            intent.removeExtra("open_ai_panel")
            openAiWorkspace()
        }
    }

    /**
     * "run_command" extra: bring up the terminal panel and type the command
     * (used by the AI panel to launch `opencode` TUI in a PTY).
     */
    private fun handleRunCommandIntent() {
        val cmd = intent.getStringExtra("run_command") ?: return
        intent.removeExtra("run_command")
        if (cmd.isBlank()) return
        showBottomPanel()
        switchToPanel("shell")
        val tm = terminalTabManager
        if (tm == null) {
            // Terminal tab creation is deferred by switchToPanel; retry shortly.
            window.decorView.postDelayed({
                terminalTabManager?.executeCommand(cmd)
                    ?: runOnUiThread { setStatus("Terminal unavailable: $cmd") }
            }, 900)
        } else {
            tm.executeCommand(cmd)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val actions = mutableListOf<MemoryMonitor.CleanupAction>()
        when {
            level >= TRIM_MEMORY_RUNNING_LOW -> {
                actions.add(MemoryMonitor.CleanupAction.GARBAGE_COLLECT)
                actions.add(MemoryMonitor.CleanupAction.CLEAR_FILE_CACHE)
            }
            level >= TRIM_MEMORY_RUNNING_MODERATE -> {
                actions.add(MemoryMonitor.CleanupAction.GARBAGE_COLLECT)
            }
        }
        if (level >= TRIM_MEMORY_COMPLETE) {
            actions.add(MemoryMonitor.CleanupAction.GARBAGE_COLLECT)
            actions.add(MemoryMonitor.CleanupAction.CLEAR_FILE_CACHE)
            actions.add(MemoryMonitor.CleanupAction.CLEAR_BUILD_CACHE)
        }
        if (actions.isNotEmpty()) {
            memoryMonitor.executeCleanup(actions)
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        memoryMonitor.executeCleanup(
            listOf(
                MemoryMonitor.CleanupAction.GARBAGE_COLLECT,
                MemoryMonitor.CleanupAction.CLEAR_FILE_CACHE
            )
        )
    }

    private var volumeDownHeld = false
    private var volumeUpHeld = false
    private var savedHighlightSpans: List<Any> = emptyList()
    private var longPressStartTime = 0L
    private var longPressStartX = 0f
    private var longPressStartY = 0f
    private lateinit var volumeGestureHandler: VolumeGestureHandler

    // Pinch-to-zoom for editor
    private var editorScaleDetector: android.view.ScaleGestureDetector? = null
    private var editorBaseTextSize = 14f

    private fun setupEditorPinchZoom() {
        editorBaseTextSize = codeEditor.textSize / resources.displayMetrics.scaledDensity
        editorScaleDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newSize = (editorBaseTextSize * scaleFactor).coerceIn(8f, 24f)
                codeEditor.textSize = newSize
                return true
            }
            override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                editorBaseTextSize = codeEditor.textSize / resources.displayMetrics.scaledDensity
            }
        })
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeGestureHandler.onVolumeDownKeyDown()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeGestureHandler.onVolumeUpKeyDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeGestureHandler.onVolumeDownKeyUp()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeGestureHandler.onVolumeUpKeyUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun highlightFunctionNamesForGoTo() {
        val spannable = codeEditor.text ?: return
        @Suppress("SENSELESS_COMPARISON")
        if (spannable !is android.text.Spannable) return
        // Save current state and clear any previous goTo highlights
        restoreHighlightAfterGoTo()
        val source = spannable.toString()
        val patterns = listOf(
            Regex("""\bfun\s+(\w+)\s*\("""),
            Regex("""\b(class|interface|object|enum\s+class|data\s+class)\s+(\w+)"""),
            Regex("""\bval\s+(\w+)\s*[=:]"""),
            Regex("""\bvar\s+(\w+)\s*[=:]""")
        )
        val found = mutableSetOf<IntRange>()
        for (pattern in patterns) {
            for (match in pattern.findAll(source)) {
                val nameRange = if (match.groupValues.size > 2 && match.groupValues[2].isNotEmpty()) {
                    val ns = match.range.first + match.value.indexOf(match.groupValues[2])
                    ns until ns + match.groupValues[2].length
                } else if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty()) {
                    val ns = match.range.first + match.value.indexOf(match.groupValues[1])
                    ns until ns + match.groupValues[1].length
                } else continue
                if (nameRange.first < 0 || nameRange.last >= source.length) continue
                if (found.any { it.first <= nameRange.first && it.last >= nameRange.last }) continue
                found.add(nameRange)
                val s = nameRange.first.coerceIn(0, spannable.length)
                val e = (nameRange.last + 1).coerceIn(0, spannable.length)
                if (s >= e) continue
                val underline = UnderlineSpan()
                val fg = ForegroundColorSpan(StudioUi.color("#7CC7FF"))
                val bold = android.text.style.StyleSpan(Typeface.BOLD)
                spannable.setSpan(underline, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(fg, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(bold, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                savedHighlightSpans = savedHighlightSpans + listOf(underline, fg, bold)
            }
        }
    }

    private fun restoreHighlightAfterGoTo() {
        val spannable = codeEditor.text ?: return
        @Suppress("SENSELESS_COMPARISON")
        if (spannable !is android.text.Spannable) return
        savedHighlightSpans.forEach { spannable.removeSpan(it) }
        savedHighlightSpans = emptyList()
    }

    override fun onDestroy() {
        super.onDestroy()
        MobileStudioCrashGuard.activityDestroyed(this)
        // Extension lifecycle: onShutdown — no UI may be touched after this.
        runCatching { ExtensionHost.dispatchShutdownForAll() }
        freezeDetector?.destroy()
        runCatching { swapManager?.stop() }
        memoryMonitor.destroy()
        commandPalette.destroy()
        scope.cancel()
        fileWatcher?.stop()
        volumeGestureHandler.destroy()
        // Web completions hold a TextWatcher and a coroutine scope on the editor.
        if (::webAutoComplete.isInitialized) runCatching { webAutoComplete.detach() }
    }

    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("WrongConstant")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_OPEN_PROJECT_TREE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                val canRead = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
                val canWrite = data.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0
                val persistableFlags = when {
                    canRead && canWrite -> Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    canRead -> Intent.FLAG_GRANT_READ_URI_PERMISSION
                    canWrite -> Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    else -> Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                runCatching { contentResolver.takePersistableUriPermission(uri, persistableFlags) }
                importExternalProject(uri)
            }
        }
        // GGUF import / attachment picker results no longer route through
        // MainActivity: the old bottom-panel AI owned those pickers, and the
        // workspace handles its own (see AiWorkspaceActivity.onActivityResult).
    }

    private fun deferStartupWork() {
        setStatus("Ready - choose a project")
        attemptAutoReopenLastProject()
        scope.launch {
            delay(600)
            launch(Dispatchers.IO) {
                runCatching { NativeLibSetup.ensureVersionedLibs(this@MainActivity) }
                runCatching { ToolchainManager(this@MainActivity).ensureAssetOverridesInstalled() }
                runCatching { JreGradleInstaller(this@MainActivity, {}, {}).ensureRuntimeLibs() }
            }
        }
    }

    private fun attemptAutoReopenLastProject() {
        // Disabled auto-reopen as requested
        setStatus("Ready - choose a project")
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        MobileStudioCrashGuard.safe(this, "MainActivity.onBackPressed") {
            handleBackAction()
        }
    }

    private fun handleBackAction() {
        MobileStudioCrashGuard.safe(this, "MainActivity.handleBackAction") {
            when {
                openIdeMenuGroup != null -> closeIdeMenuDropdown()
                findReplaceVisible -> hideFindReplaceSafely()
                this::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START) ->
                    drawerLayout.closeDrawer(GravityCompat.START)

                this::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.END) ->
                    drawerLayout.closeDrawer(GravityCompat.END)

                this::bottomPanel.isInitialized && bottomPanel.visibility == View.VISIBLE ->
                    hideBottomPanel()

                unsavedChanges -> confirmDiscard { MobileStudioCrashGuard.finishSafely(this) }
                else -> MobileStudioCrashGuard.finishSafely(this)
            }
        }
    }

    private fun hideFindReplaceSafely() {
        MobileStudioCrashGuard.safe(this, "MainActivity.hideFindReplace") {
            hideFindReplace()
        }
    }

    // MobileStudio comment
    // View binding
    // MobileStudio comment

    /** Populates the Extension API bridge so extensions reach the core only through ExtensionContext. */
    private fun wireExtensionBridge() {
        // JSON language packs under assets/i18n need an AssetManager before they load.
        com.aide.mobilestudio.AppLanguage.bindAssets(this)
        com.aide.mobilestudio.extension.ExtensionHost.init(this)
        val bridge = com.aide.mobilestudio.extension.ExtensionBridge
        bridge.currentFile = { currentFile }
        bridge.openFile = { openFile(it) }
        bridge.insertText = { insertAtCursor(it) }
        bridge.selectedText = { codeEditor.text?.subSequence(codeEditor.selectionStart, codeEditor.selectionEnd)?.toString() }
        bridge.projectDir = { currentProjectDir }
        bridge.showToast = { showToast(it) }
        bridge.notify = { msg, _ -> setStatus(msg) }
        bridge.progress = { title, _ -> setStatus(title) }
        bridge.runCommand = { cmd, onLine, onExit -> runShellCommandBridge(cmd, onLine, onExit) }
        bridge.registerSyntaxHighlighter = { _, _, _ -> }
        bridge.registerAutocompleteWords = { _, _ -> }
    }

    private fun runShellCommandBridge(cmd: String, onLine: (String) -> Unit, onExit: (Int) -> Unit) {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                val pb = ProcessBuilder("/system/bin/sh", "-c", cmd)
                val proc = pb.start()
                val stdout = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    proc.inputStream.bufferedReader().forEachLine { onLine(it) }
                }
                val stderr = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    proc.errorStream.bufferedReader().forEachLine { onLine(it) }
                }
                val code = proc.waitFor()
                stdout.join(); stderr.join()
                onExit(code)
            } catch (e: Exception) {
                onLine("[ERROR] ${e.message}")
                onExit(-1)
            }
        }
    }

    private fun bindViews() {
        fun <T : View> find(id: Int, name: String): T {
            return findViewById<T>(id) ?: run {
                val errorMsg = "Required view not found: $name (id=$id)"
                Log.e("MainActivity", errorMsg)
                throw IllegalStateException(errorMsg)
            }
        }

        drawerLayout = find(R.id.drawer_layout, "drawer_layout")
        codeEditor = find(R.id.code_editor, "code_editor")
        welcomeScreen = find(R.id.welcome_screen, "welcome_screen")
        editorContainer = find(R.id.editor_container, "editor_container")
        tabContainer = find(R.id.tab_container, "tab_container")
        tabScroll = find(R.id.tab_scroll, "tab_scroll")
        tvProjectName = find(R.id.tv_project_name, "tv_project_name")
        tvStatusLeft = find(R.id.tv_status_left, "tv_status_left")
        tvStatusRight = find(R.id.tv_status_right, "tv_status_right")
        tvStatusLang = find(R.id.tv_status_lang, "tv_status_lang")
        tvStatusCursor = find(R.id.tv_status_cursor, "tv_status_cursor")
        tvStatusEncoding = find(R.id.tv_status_encoding, "tv_status_encoding")
        tvMemoryIndicator = find(R.id.tv_memory_indicator, "tv_memory_indicator")
        tvBackgroundTask = find(R.id.tv_background_task, "tv_background_task")
        badgeWarnings = find(R.id.badge_warnings, "badge_warnings")
        badgeErrors = find(R.id.badge_errors, "badge_errors")
        bottomPanel = find(R.id.bottom_panel, "bottom_panel")
        buildOutput = find(R.id.build_output, "build_output")
        buildMonitor = find(R.id.build_monitor, "build_monitor")
        buildTreeView = find(R.id.build_tree_view, "build_tree_view")
        buildOutput.setOnErrorClickListener { file, line, _ ->
            val raw = File(file)
            val resolved = if (raw.isAbsolute) raw else File(currentProjectDir ?: filesDir, file)
            if (resolved.exists()) openFileAtLine(resolved, line)
            else showToast(ui("File not found: ") + file)
        }
        logcatContainer = find(R.id.logcat_container, "logcat_container")
        logcatText = find(R.id.logcat_text, "logcat_text")
        problemsContainer = find(R.id.problems_container, "problems_container")
        problemsRecycler = find(R.id.problems_recycler, "problems_recycler")
        buildProgress = find(R.id.build_progress_mini, "build_progress_mini")
        fileTree = find(R.id.file_tree, "file_tree")
        tvDrawerTitle = find(R.id.tv_drawer_title, "tv_drawer_title")
        editorOverlay = find(R.id.editor_overlay, "editor_overlay")
        breadcrumb = find(R.id.breadcrumb, "breadcrumb")
        projectTabContainer = find(R.id.project_tab_container, "project_tab_container")
        projectTabScroll = find(R.id.project_tab_scroll, "project_tab_scroll")
    }

    // MobileStudio comment
    // Toolbar
    // MobileStudio comment

    private fun setupToolbar() {
        // Row B is now a minimal toolbar: back/forward file history, project
        // name + badges + build status, sync, run and the build spinner.
        // Everything the old rows (quick actions / build toolbar) did lives in
        // the File/Edit/View/Build/Run/Tools text menus.
        findViewById<ImageButton>(R.id.btn_back)?.setOnClickListener {
            MobileStudioCrashGuard.safe(this, "MainActivity.backButton") {
                navigateBack()
            }
        }
        findViewById<ImageButton>(R.id.btn_forward)?.setOnClickListener {
            MobileStudioCrashGuard.safe(this, "MainActivity.forwardButton") {
                navigateForward()
            }
        }
        val runButton = findViewById<ImageButton>(R.id.btn_run)
        runButton.setOnClickListener {
            MobileStudioCrashGuard.safe(this, "MainActivity.buildButton") {
                if (isBuildRunning) stopBuild() else runGradleTask(currentBuildTask())
            }
        }
        runButton.setOnLongClickListener {
            showBuildTaskDialog()
            true
        }
        findViewById<TextView>(R.id.tools_build_option_label).setOnClickListener { showBuildTaskDialog() }
        // Editor navigation helpers (btn_preview still exists as a hidden
        // placeholder — updatePreviewButtonVisibility() flips its visibility).
        findViewById<ImageButton>(btn_preview).setOnClickListener {
            val file = currentFile ?: run {
                showToast("Open a file first"); return@setOnClickListener
            }
            openCurrentPreview(file)
        }
        bindTopTextMenus()
        setupToolsPanel()
        updateNavigationButtons()
    }

    // ------------------------------------------------------------------
    // File navigation history (toolbar Back / Forward)
    // ------------------------------------------------------------------
    private val navBackStack = java.util.ArrayDeque<String>()
    private val navForwardStack = java.util.ArrayDeque<String>()
    private var isNavigatingHistory = false

    /**
     * Records that the editor switched from [previousPath] to [newPath].
     * Browser semantics: a new navigation pushes the previous file onto the
     * back stack and wipes the forward stack. Navigations caused by
     * navigateBack/navigateForward are guarded by [isNavigatingHistory].
     */
    private fun recordFileNavigation(previousPath: String?, newPath: String) {
        if (isNavigatingHistory) return
        if (previousPath != null && previousPath != newPath) {
            navBackStack.addLast(previousPath)
            while (navBackStack.size > 100) navBackStack.removeFirst()
            navForwardStack.clear()
        }
        updateNavigationButtons()
    }

    /** Pops the editor's file history and opens the previous file. No-op on an empty stack. */
    fun navigateBack() {
        navigateHistory(navBackStack, navForwardStack)
    }

    /** Redo: opens the file that was left via navigateBack. No-op on an empty stack. */
    fun navigateForward() {
        navigateHistory(navForwardStack, navBackStack)
    }

    private fun navigateHistory(from: java.util.ArrayDeque<String>, to: java.util.ArrayDeque<String>) {
        try {
            // Drop entries whose file has since disappeared.
            while (!from.isEmpty()) {
                val candidate = from.peekLast() ?: break
                if (File(candidate).isFile) break
                from.pollLast()
            }
            if (from.isEmpty()) return
            val target = from.peekLast() ?: return
            val current = currentFile?.absolutePath
            isNavigatingHistory = true
            try {
                runCatching { openFile(File(target)) }
            } finally {
                isNavigatingHistory = false
            }
            // Always consume the entry we acted on so the button can never get
            // stuck; only carry the old file over when the jump really happened.
            from.pollLast()
            if (current != null && current != target && currentFile?.absolutePath == target) {
                to.addLast(current)
            }
        } finally {
            updateNavigationButtons()
        }
    }

    /** Dims/disables the navigation buttons while their stack is empty. */
    private fun updateNavigationButtons() {
        val backEnabled = !navBackStack.isEmpty()
        val forwardEnabled = !navForwardStack.isEmpty()
        findViewById<ImageButton>(R.id.btn_back)?.apply {
            alpha = if (backEnabled) 1.0f else 0.35f
            isClickable = backEnabled
        }
        findViewById<ImageButton>(R.id.btn_forward)?.apply {
            alpha = if (forwardEnabled) 1.0f else 0.35f
            isClickable = forwardEnabled
        }
    }

    private fun toggleToolsDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END))
            drawerLayout.closeDrawer(GravityCompat.END)
        else drawerLayout.openDrawer(GravityCompat.END)
    }

    private fun toggleExplorerDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START))
            drawerLayout.closeDrawer(GravityCompat.START)
        else drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun showDevicePicker() {
        showToast(ui("Searching for devices..."))
        scope.launch {
            val devices = withContext(Dispatchers.IO) {
                runCatching { AdbDeviceManager.listDevices() }.getOrDefault(emptyList())
            }
            if (devices.isEmpty()) {
                showTextMenu(
                    ui("Devices"),
                    listOf(
                        ui("No devices found (enable USB debugging)") to {
                            showBottomPanel()
                            switchToPanel("logcat")
                            ensureLogcatStarted()
                        }
                    )
                )
            } else {
                showTextMenu(
                    ui("Devices"),
                    devices.map { d ->
                        val name = d.model.ifEmpty { d.product.ifEmpty { d.serial } }
                        "$name · ${d.state.label}" to { showToast("${d.serial} — ${d.state.label}") }
                    }
                )
            }
        }
    }

    private fun setupToolsPanel() {
        // Tools drawer toggle lives in the View menu now ("Tools Panel").
        // Sync button ??triggers GradleSyncManager
        findViewById<View>(R.id.btn_sync_now)?.setOnClickListener {
            currentProjectDir?.let { dir ->
                runGradleSyncNow()
                showToast("Syncing project...")
            }
        }
        // Close button
        findViewById<View>(R.id.btn_close_tools)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.END)
        }
        // Tab switching
        val tabs = mapOf(
            R.id.tools_tab_build to R.id.tools_panel_build,
            R.id.tools_tab_apk to R.id.tools_panel_apk,
            R.id.tools_tab_community to R.id.tools_panel_community
        )
        for ((tabId, panelId) in tabs) {
            findViewById<View>(tabId)?.setOnClickListener { v ->
                val strip = findViewById<ViewGroup>(R.id.tools_tab_strip) ?: return@setOnClickListener
                for (i in 0 until strip.childCount) {
                    val child = strip.getChildAt(i)
                    child?.let { tv ->
                        if (tv is TextView) {
                            tv.setTextColor(StudioUi.color(StudioUi.TextSoft))
                            tv.background = null
                        }
                    }
                }
                (v as? TextView)?.let { tv ->
                    tv.setTextColor(StudioUi.color(StudioUi.ActiveBlue))
                    tv.background = StudioUi.rounded(this, fill = "#172033", stroke = StudioUi.ActiveBlue, radiusDp = 12)
                }
                val content = findViewById<ViewGroup>(R.id.tools_panel_content) ?: return@setOnClickListener
                for (i in 0 until content.childCount) {
                    content.getChildAt(i)?.visibility = View.GONE
                }
                findViewById<View>(panelId)?.visibility = View.VISIBLE
            }
        }
        // Build tab: run/stop/clean/APK buttons
        findViewById<View>(R.id.tools_btn_run)?.setOnClickListener {
            if (isBuildRunning) stopBuild() else runGradleTask(currentBuildTask())
        }
        findViewById<View>(R.id.tools_btn_stop)?.setOnClickListener { stopBuild() }
        findViewById<View>(R.id.tools_btn_clean)?.setOnClickListener {
            if (!isBuildRunning) runGradleTask("clean")
        }
        findViewById<View>(R.id.tools_btn_apk)?.setOnClickListener { installLatestApk() }
        findViewById<View>(R.id.tools_build_option_label)?.setOnClickListener { showBuildTaskDialog() }
        // HTML Viewer tab
        findViewById<View>(R.id.tools_community_chat)?.setOnClickListener {
            HtmlViewerActivity.openAsset(this, "index.html", "MobileStudio")
        }
        // Populate APK list
        populateToolsApkList()
        // Sync build status display
        updateToolsBuildStatus()
    }

    private fun updateToolsBuildStatus() {
        val statusView = findViewById<TextView>(R.id.tv_build_status)
        val runBtn = findViewById<Button>(R.id.tools_btn_run)
        if (isBuildRunning) {
            statusView?.text = "Building..."
            statusView?.visibility = View.VISIBLE
            runBtn?.text = "Stop"
            runBtn?.studioButton("#DA3633", "#FF7B72")
        } else {
            statusView?.visibility = View.GONE
            runBtn?.text = "Build"
            runBtn?.studioButton("#238636", "#7EE787")
        }
    }

    private fun populateToolsApkList() {
        val apkList = findViewById<ViewGroup>(R.id.tools_apk_list) ?: return
        val outputDir = currentProjectDir?.let { File(it, "app/build/outputs/apk/debug") }
        val apkFiles = outputDir?.listFiles { _, name -> name.endsWith(".apk") }?.toList() ?: emptyList()
        apkList.removeAllViews()
        if (apkFiles.isEmpty()) {
            apkList.addView(TextView(this).apply {
                text = "No APKs built yet.\nBuild a project to see APKs here."
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, 8.dp(), 0, 8.dp())
            })
            return
        }
        for (apk in apkFiles.sortedByDescending { it.lastModified() }) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 4.dp(), 0, 4.dp())
                addView(TextView(this@MainActivity).apply {
                    text = apk.name
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    setSingleLine(true)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 4.dp()
                })
                addView(Button(this@MainActivity).apply {
                    text = "Install"
                    textSize = 9f
                    studioButton("#238636", "#7EE787")
                    setOnClickListener { installApk(apk) }
                    layoutParams = LinearLayout.LayoutParams(56.dp(), 28.dp())
                })
            }
            apkList.addView(row)
        }
    }

    /**
     * Opens the menu list. Bound to the hamburger and to the project switcher
     * in the top bar.
     *
     * The File/Edit/View/Build/Run/Tools menus used to have their own row
     * across the top; the redesign spends that row on the workspace tabs
     * instead, and this is how the menus survive it - showMainMenu() had
     * already listed every one of them, so nothing was written to keep them
     * reachable, only nothing ever called it.
     */
    fun openMainMenuFromToolbar() = showMainMenu()

    /** Opens the explorer drawer - what the project name and the rail's project item mean. */
    fun openProjectDrawer() {
        if (drawerLayout.isDrawerOpen(GravityCompat.END)) drawerLayout.closeDrawer(GravityCompat.END)
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) return
        drawerLayout.openDrawer(GravityCompat.START)
    }

    /**
     * Binds the left rail.
     *
     * Every destination lands on something that already implemented it rather
     * than on a new screen: the rail is a way to reach the app's sections, not
     * a second implementation of them, so tapping it does exactly what the
     * matching menu entry, panel tab or dialog always did. That is what keeps
     * it from going stale - there is only ever one copy of each behaviour.
     */
    /**
     * Binds the top bar: the hamburger and the workspace tabs.
     *
     * The tabs read as four modes, but the app has no four modes to switch
     * between - so each one lands on the surface that already does that job
     * (실행 on the build output, 디버그 on logcat, 배포 on installing the last
     * APK) instead of on a stub that would look like a mode and do nothing.
     */
    private fun setupTopBar() {
        findViewById<ImageButton>(R.id.btn_menu)?.setOnClickListener {
            MobileStudioCrashGuard.safe(this, "MainActivity.menuButton") { openMainMenuFromToolbar() }
        }
        val tabs = mapOf<Int, () -> Unit>(
            R.id.tab_top_project to { openProjectDrawer() },
            R.id.tab_top_run to { showBottomPanel(); switchToPanel("build") },
            R.id.tab_top_debug to { showBottomPanel(); switchToPanel("logcat") },
            R.id.tab_top_deploy to { installLatestApk() },
            R.id.tab_top_ai to { openAiWorkspace() },
        )
        tabs.forEach { (viewId, action) ->
            findViewById<TextView>(viewId)?.setOnClickListener {
                MobileStudioCrashGuard.safe(this, "MainActivity.topTab") {
                    val kind = it.tag as? String
                    action()
                    if (kind != null) highlightTopTab(kind)
                }
            }
        }
        highlightTopTab("project")
    }

    /** Marks the active workspace tab in the top bar. See [highlightSideRail] for why the tag is the source of truth. */
    fun highlightTopTab(kind: String) {
        val ids = listOf(
            R.id.tab_top_project, R.id.tab_top_run, R.id.tab_top_debug,
            R.id.tab_top_deploy, R.id.tab_top_ai,
        )
        ids.forEach { id ->
            val tab = findViewById<TextView>(id) ?: return@forEach
            val selected = tab.tag == kind
            tab.setTextColor(c(if (selected) StudioUi.Text else StudioUi.Muted))
            tab.background = if (selected) {
                StudioUi.rounded(this, fill = StudioUi.Surface2, radiusDp = StudioUi.RadiusMd)
            } else {
                null
            }
        }
    }

    private fun setupSideRail() {
        val bindings = mapOf<Int, () -> Unit>(
            R.id.side_item_project to { openProjectDrawer() },
            R.id.side_item_search to { showBottomPanel(); switchToPanel("search") },
            R.id.side_item_problems to { showBottomPanel(); switchToPanel("problems") },
            R.id.side_item_git to {
                val dir = currentProjectDir
                if (dir != null) gitManager.showGitDialog(dir) else showToast(ui("Open a project first"))
            },
            R.id.side_item_shell to { showBottomPanel(); switchToPanel("shell") },
            R.id.side_item_chat to { openAiWorkspace() },
            R.id.side_item_ai to { openAiWorkspace() },
            R.id.side_item_extensions to {
                startActivity(android.content.Intent(this, com.aide.mobilestudio.extension.ExtensionManagerActivity::class.java))
            },
            R.id.side_item_settings to { showSettingsDialog() },
        )
        bindings.forEach { (viewId, action) ->
            findViewById<LinearLayout>(viewId)?.setOnClickListener {
                MobileStudioCrashGuard.safe(this, "MainActivity.sideRail") {
                    val kind = it.tag as? String
                    action()
                    if (kind != null) highlightSideRail(kind)
                }
            }
        }
        highlightSideRail("project")
    }

    /**
     * Marks the current section on the left rail.
     *
     * The destination is read from each item's own tag instead of a parallel
     * list of ids, so a rail entry cannot end up highlighted by the wrong
     * handler, and [switchToPanel] calls this too - the rail is the only thing
     * that says where you are, and it would be lying as soon as a bottom-panel
     * tab moved you somewhere else.
     */
    fun highlightSideRail(kind: String) {
        val rail = findViewById<LinearLayout>(R.id.side_bar) ?: return
        for (i in 0 until rail.childCount) {
            val item = rail.getChildAt(i) ?: continue
            val selected = item.tag == kind
            item.background = if (selected) {
                StudioUi.rounded(
                    this,
                    fill = StudioUi.Surface2,
                    stroke = StudioUi.ActiveBlue,
                    radiusDp = StudioUi.RadiusMd,
                )
            } else {
                null
            }
            if (item !is ViewGroup) continue
            for (j in 0 until item.childCount) {
                (item.getChildAt(j) as? TextView)?.setTextColor(
                    c(if (selected) StudioUi.ActiveBlue else StudioUi.InactiveIcon),
                )
            }
        }
    }

    private fun bindTopTextMenus() {
        findViewById<TextView>(R.id.menu_file)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_file, "File") }
        findViewById<TextView>(R.id.menu_edit)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_edit, "Edit") }
        findViewById<TextView>(R.id.menu_view)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_view, "View") }
        findViewById<TextView>(R.id.menu_navigate)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_navigate, "Navigate") }
        findViewById<TextView>(R.id.menu_code)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_code, "Code") }
        findViewById<TextView>(R.id.menu_refactor)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_refactor, "Refactor") }
        findViewById<TextView>(R.id.menu_build)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_build, "Build") }
        findViewById<TextView>(R.id.menu_run)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_run, "Run") }
        findViewById<TextView>(R.id.menu_tools)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_tools, "Tools") }
        findViewById<TextView>(R.id.menu_git)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_git, "Git") }
        findViewById<TextView>(R.id.menu_window)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_window, "Window") }
        findViewById<TextView>(R.id.menu_help)?.setOnClickListener { openIdeMenuDropdown(R.id.menu_help, "Help") }
    }

    /**
     * Opens the Android Studio-style top menu dropdown for [menuId]'s group.
     * The dropdown lives in the top menu bar area (menu_bar_area) and expands
     * below the thin menu strip; it closes on item pick, overlay tap or back.
     */
    private fun openIdeMenuDropdown(menuId: Int, group: String) {
        val anchor = findViewById<View>(menuId) ?: return
        closeIdeMenuDropdown()
        val area = findViewById<FrameLayout>(R.id.menu_bar_area) ?: return
        val submenu = findViewById<LinearLayout>(R.id.menu_bar_submenu) ?: return
        val overlay = findViewById<View>(R.id.menu_bar_overlay) ?: return

        val items = ideMenuItems(group)
        if (items.isEmpty()) {
            submenu.visibility = View.GONE
            submenu.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
            return
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
        }
        items.forEach { item ->
            val row = TextView(this).apply {
                text = item.label
                textSize = 12f
                setTextColor(if (item.enabled) StudioUi.color(StudioUi.Text) else StudioUi.color(StudioUi.TextSoft))
                setPadding(12.dp(), 14.dp(), 12.dp(), 14.dp())
                setOnClickListener {
                    if (item.enabled) item.action()
                    closeIdeMenuDropdown()
                }
            }
            container.addView(row)
        }
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 200.dp())
            addView(container)
        }
        submenu.addView(scrollView)
        submenu.visibility = View.VISIBLE
        submenu.measure(
            View.MeasureSpec.makeMeasureSpec(area.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(200.dp(), View.MeasureSpec.EXACTLY)
        )
        val submenuH = submenu.measuredHeight
        submenu.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, submenuH)
        menuBarClosedHeightPx = area.height
        area.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, menuBarClosedHeightPx + submenuH)
        overlay.isEnabled = true
        overlay.setOnClickListener { closeIdeMenuDropdown() }
        overlay.bringToFront()
        openIdeMenuGroup = group
        ideMenuAnchor = anchor
    }

    private fun closeIdeMenuDropdown() {
        val area = findViewById<FrameLayout>(R.id.menu_bar_area) ?: return
        val submenu = findViewById<LinearLayout>(R.id.menu_bar_submenu) ?: return
        val overlay = findViewById<View>(R.id.menu_bar_overlay) ?: return
        submenu.visibility = View.GONE
        submenu.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        area.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, menuBarClosedHeightPx)
        overlay.isEnabled = false
        openIdeMenuGroup = null
        ideMenuAnchor = null
    }

    private fun ideMenuItems(group: String): List<IdeMenuItem> {
        val preparing = "Preparing"
        fun makeItem(label: String, action: () -> Unit, enabled: Boolean): IdeMenuItem =
            IdeMenuItem(label, action, enabled)
        return when (group) {
            "File" -> listOf(
                makeItem("New Project", { showNewProjectDialog() }, true),
                makeItem("Open Project", { showOpenProjectPicker() }, true),
                makeItem("Save", { saveCurrentFile(); showToast(ui("Saved")) }, true),
                makeItem("Save All", { showToast(preparing) }, false),
                makeItem("Settings", { showSettingsDialog() }, true),
                makeItem("Exit", { finishAffinity() }, true)
            )
            "Edit" -> listOf(
                makeItem("Undo", { codeEditor.undo() }, true),
                makeItem("Redo", { codeEditor.redo() }, true),
                makeItem("Cut", { copyEditorSelection(); deleteEditorSelection() }, true),
                makeItem("Copy", { copyEditorSelection() }, true),
                makeItem("Paste", { pasteIntoEditor() }, true),
                makeItem("Find", { toggleFindReplace() }, true),
                makeItem("Replace", { toggleFindReplace() }, true)
            )
            "View" -> listOf(
                makeItem("Project", { showProjectTextMenu() }, true),
                makeItem("Terminal", { showBottomPanel(); switchToPanel("shell") }, true),
                makeItem("Problems", { showBottomPanel(); switchToPanel("problems") }, true),
                makeItem("Logcat", { showBottomPanel(); switchToPanel("logcat") }, true),
                makeItem("AI Assistant", { openAiWorkspace() }, true),
                makeItem("Appearance", { showSettingsDialog() }, true),
                makeItem("Toggle Full Screen", { showToast(preparing) }, false)
            )
            "Navigate" -> listOf(
                makeItem("Go to File", { showSearchEverywhere("All") }, true),
                makeItem("Go to Class", { showCommandPalette() }, true),
                makeItem("Go to Symbol", { showCommandPalette() }, true),
                makeItem("Go to Line", { showGoToLineDialog() }, true)
            )
            "Code" -> listOf(
                makeItem("Reformat Code", { formatCurrentFile() }, true),
                makeItem("Comment / Uncomment", { codeEditor.toggleLineComment(commentPrefixFor(currentFile)) }, true),
                makeItem("Generate", { showToast(preparing) }, false),
                makeItem("Optimize Imports", { showToast(preparing) }, false)
            )
            "Refactor" -> listOf(
                makeItem("Rename Symbol", { showRenameSymbolDialog() }, true),
                makeItem("Rename File", { showToast(preparing) }, false),
                makeItem("Duplicate Line", { codeEditor.duplicateCurrentLine() }, true),
                makeItem("Delete Line", { codeEditor.deleteCurrentLine() }, true)
            )
            "Build" -> listOf(
                makeItem("Make Project", { if (!isBuildRunning) runGradleTask("assembleDebug") }, true),
                makeItem("Rebuild Project", { if (!isBuildRunning) runGradleTask("clean", "assembleDebug") }, true),
                makeItem("Clean Project", { if (!isBuildRunning) runGradleTask("clean") }, true),
                makeItem("Build APK", { if (!isBuildRunning) runGradleTask("assembleDebug") }, true),
                makeItem("Build Output", { showBottomPanel(); switchToPanel("build") }, true)
            )
            "Run" -> listOf(
                makeItem("Run", { if (!isBuildRunning) runGradleTask("assembleDebug") }, true),
                makeItem("Debug", { showBottomPanel(); switchToPanel("logcat") }, true),
                makeItem("Stop", { stopBuild() }, true),
                makeItem("Run Configurations", { showBuildTaskDialog() }, true)
            )
            "Tools" -> listOf(
                makeItem("SDK Manager", { ToolsActivity.start(this) }, true),
                makeItem("Device Manager", { showDevicePicker() }, true),
                makeItem("AVD / Emulator", { showToast(preparing) }, false),
                makeItem("Extensions", {
                    startActivity(android.content.Intent(this, com.aide.mobilestudio.extension.ExtensionManagerActivity::class.java))
                }, true),
                makeItem("AI Settings", { openAiWorkspace() }, true)
            )
            "Git" -> listOf(
                makeItem("Commit", { val dir = currentProjectDir; if (dir != null) gitManager.showGitDialog(dir) }, true),
                makeItem("Push", { val dir = currentProjectDir; if (dir != null) gitManager.showGitDialog(dir) }, true),
                makeItem("Pull", { val dir = currentProjectDir; if (dir != null) gitManager.showGitDialog(dir) }, true),
                makeItem("Branches", { val dir = currentProjectDir; if (dir != null) gitManager.showGitDialog(dir) }, true),
                makeItem("History", { val dir = currentProjectDir; if (dir != null) gitManager.showGitDialog(dir) }, true)
            )
            "Window" -> listOf(
                makeItem("Editor", { showBottomPanel(); switchToPanel("build") }, true),
                makeItem("Split Right", { showToast(preparing) }, false),
                makeItem("Split Down", { showToast(preparing) }, false),
                makeItem("Close Other Tabs", { showToast(preparing) }, false)
            )
            "Help" -> listOf(
                makeItem("Documentation", { showToast(preparing) }, false),
                makeItem("Keyboard Shortcuts", { showToast(preparing) }, false),
                makeItem("Check for Updates", { checkForAppUpdate() }, true),
                makeItem("About MobileStudio", { showToast(preparing) }, false)
            )
            else -> emptyList()
        }
    }

    private data class IdeMenuItem(val label: String, val action: () -> Unit, val enabled: Boolean = true)

    private fun showMainMenu() = showTextMenu(ui("Menu"), listOf(
        ui("File") to { showFileMenu() },
        ui("Edit") to { showEditMenu() },
        ui("View") to { showViewMenu() },
        ui("Navigate") to { showNavigateMenu() },
        ui("Code") to { showCodeMenu() },
        ui("Refactor") to { showRefactorMenu() },
        ui("Build") to { showBuildMenu() },
        ui("Run") to { showRunMenu() },
        ui("Tools") to { showToolsMenu() },
        ui("IDE") to { showIdeMenu() },
        // Extensions sat in the old top text row between Community and IDE.
        // Collapsing that row into this menu dropped it, which left the
        // extension manager reachable only from a crash dialog - the only way
        // in was a path taken after something had already failed.
        ui("Extensions") to {
            startActivity(android.content.Intent(this, com.aide.mobilestudio.extension.ExtensionManagerActivity::class.java))
        },
        ui("Community") to { HtmlViewerActivity.openAsset(this, "index.html", "MobileStudio") }
    ))

    fun showMoreMenu() = showTextMenu(ui("More"), listOf(
        ui("Project") to { showProjectTextMenu() },
        ui("Project Structure") to { currentProjectDir?.let { ProjectSettingsActivity.start(this, it.absolutePath) } ?: showToast(ui("Open a project first")) },
        ui("Sync Project") to { if (!gradleSyncRunning) runGradleSyncNow() },
        ui("Clean") to { if (!isBuildRunning) runGradleTask("clean") },
        ui("Rebuild") to { if (!isBuildRunning) runGradleTask("clean", "assembleDebug") },
        ui("Build APK") to { if (!isBuildRunning) runGradleTask("assembleDebug") },
        ui("Install APK") to { installLatestApk() },
        ui("Format Code") to { formatCurrentFile() },
        ui("Go to Line") to { showGoToLineDialog() },
        ui("Find / Replace") to { toggleFindReplace() },
        ui("Undo") to { codeEditor.undo() },
        ui("Redo") to { codeEditor.redo() },
        ui("Save") to { saveCurrentFile(); showToast(ui("Saved")) },
        ui("Word Wrap") to { toggleWordWrap() },
        ui("Terminal") to { showBottomPanel(); switchToPanel("shell") },
        ui("AI Assistant") to { openAiWorkspace() },
        ui("Tools") to { ToolsActivity.start(this) },
        ui("Shell") to { showBottomPanel(); switchToPanel("shell") },
        ui("Logcat") to { showBottomPanel(); switchToPanel("logcat") },
        ui("File Explorer") to { openFileExplorer() }
    ))

    private fun showTextMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second.invoke() }
            .show()
    }

    private fun ui(value: String): String = AppLanguage.literal(this, value)

    private fun showFileMenu() = showTextMenu(ui("File"), listOf(
        ui("New Project") to { showNewProjectDialog() },
        ui("Open Project") to { showOpenProjectPicker() },
        ui("Project Structure") to { currentProjectDir?.let { ProjectSettingsActivity.start(this, it.absolutePath) } ?: showToast("Open a project first") },
        ui("Save") to { saveCurrentFile(); showToast(ui("Saved")) },
        ui("File Explorer") to { openFileExplorer() },
        ui("Project Explorer") to { showProjectTextMenu() }
    ))

    private fun showToolsMenu() = showTextMenu(ui("Tools"), listOf(
        ui("Open Build Tools") to { ToolsActivity.start(this) },
        ui("Wine & Box64") to { Win64ManagerActivity.start(this) },
        // The analyzer has no other way in: it is not exported and nothing in
        // the app referenced it, so the screen existed but no one could open it.
        ui("Mach-O / IPA Analyzer") to {
            startActivity(android.content.Intent(this, com.aide.mobilestudio.asteros.AsterOSActivity::class.java))
        },
        ui("Dependency Search") to {
            val dir = currentProjectDir
            if (dir != null) DependencySearchDialog(this).show(dir)
            else showToast(ui("Open a project first"))
        },
        ui("Build Cache Manager") to { BuildCacheManager(this).show(currentProjectDir) },
        ui("Git") to {
            val dir = currentProjectDir
            if (dir != null) gitManager.showGitDialog(dir)
            else showToast(ui("Open a project first"))
        },
        ui("MobileStudio Community") to { HtmlViewerActivity.openAsset(this, "index.html", "MobileStudio") },
        ui("Open Shell Panel") to { showBottomPanel(); switchToPanel("shell") },
        ui("File Explorer") to { openFileExplorer() },
        // Formerly quick-action toolbar buttons.
        ui("AI Workspace") to { openAiWorkspace() },
        ui("Terminal") to { showBottomPanel(); switchToPanel("shell") },
        ui("Logcat") to { showBottomPanel(); switchToPanel("logcat"); ensureLogcatStarted() },
        ui("Device Picker") to { showDevicePicker() },
        ui("Run Configuration") to { showBuildTaskDialog() },
        ui("Tools Drawer") to { toggleToolsDrawer() }
    ))

    private fun openFileExplorer() {
        val startDir = currentProjectDir ?: StorageHelper.projectsRoot(this)
        ToolchainFileExplorerActivity.startAt(this, "File Explorer", startDir)
    }

    private fun showEditMenu() = showTextMenu(ui("Edit"), listOf(
        ui("Undo") to { codeEditor.undo() },
        ui("Redo") to { codeEditor.redo() },
        ui("Find / Replace") to { toggleFindReplace() },
        ui("Duplicate Line") to { codeEditor.duplicateCurrentLine() },
        ui("Delete Line") to { codeEditor.deleteCurrentLine() },
        // Formerly quick-action toolbar buttons.
        ui("Save") to { saveCurrentFile(); showToast(ui("Saved")) },
        ui("Format Code") to { formatCurrentFile() }
    ))

    private fun showViewMenu() = showTextMenu(ui("View"), listOf(
        ui("Project") to { showProjectTextMenu() },
        ui("Project Structure") to { currentProjectDir?.let { ProjectSettingsActivity.start(this, it.absolutePath) } ?: showToast("Open a project first") },
        ui("Build Output") to { showBottomPanel(); switchToPanel("build") },
        ui("Problems") to { showBottomPanel(); switchToPanel("problems") },
        ui("Logcat") to { showBottomPanel(); switchToPanel("logcat") },
        ui("XML Preview") to { openCurrentXmlPreview() },
        // Formerly toolbar toggle buttons.
        ui("Bottom Panel") to { if (bottomPanel.visibility == View.VISIBLE) hideBottomPanel() else showBottomPanel() },
        ui("Tools Panel") to { toggleToolsDrawer() },
        ui("Explorer") to { toggleExplorerDrawer() },
        ui("Word Wrap") to { toggleWordWrap() },
        ui("Go to Line") to { showGoToLineDialog() }
    ))

    private fun showNavigateMenu() = showTextMenu(ui("Navigate"), listOf(
        ui("Back") to { handleBackAction() },
        ui("Current Project") to { showProjectTextMenu() },
        ui("Structure / Outline") to { showStructureOutline() },
        ui("TODO / FIXME") to { TodoExplorer.show(this, currentProjectDir) { file, line -> openFileAtLine(file, line) } },
        ui("Edit Snippet") to { showSnippetDialog() },
        ui("Current File Preview") to { openCurrentXmlPreview() }
    ))

    private fun showCodeMenu() = showTextMenu(ui("Code"), listOf(
        ui("Format Code") to { formatCurrentFile() },
        ui("Toggle Comment") to { codeEditor.toggleLineComment(commentPrefixFor(currentFile)) },
        ui("Auto Import") to { autoImportCurrentSymbol() },
        ui("Parameter Hint") to { showParameterHint() },
        ui("Quick Documentation") to { showQuickDocumentation() },
        ui("Quick Fix") to { showQuickFixesForCurrentLine() },
        ui("Move Line Up") to { codeEditor.moveCurrentLineUp() },
        ui("Move Line Down") to { codeEditor.moveCurrentLineDown() }
    ))

    private fun showRefactorMenu() = showTextMenu(ui("Refactor"), listOf(
        ui("Rename Symbol") to { showRenameSymbolDialog() },
        ui("Rename File") to { showToast(ui("Use Project Explorer long press to rename")) },
        ui("Duplicate Line") to { codeEditor.duplicateCurrentLine() },
        ui("Delete Line") to { codeEditor.deleteCurrentLine() }
    ))

    private fun showRunMenu() = showTextMenu(ui("Run"), listOf(
        ui("Run assembleDebug") to { if (!isBuildRunning) runGradleTask("assembleDebug") },
        ui("Install APK") to { installLatestApk() },
        ui("Stop Build") to { stopBuild() }
    ))

    private fun showIdeMenu() {
        val prefs = getSharedPreferences("ide_options", MODE_PRIVATE)
        showTextMenu(ui("IDE"), listOf(
            "${if (prefs.getBoolean("auto_pair", true)) "\u2714" else " "} ${ui("Auto Pair")} (), [], {}, \"\"" to {
                prefs.edit().putBoolean("auto_pair", !prefs.getBoolean("auto_pair", true)).apply()
                showIdeMenu()
            },
            "${if (prefs.getBoolean("bracket_match", true)) "\u2714" else " "} ${ui("Bracket Pair Highlight")}" to {
                prefs.edit().putBoolean("bracket_match", !prefs.getBoolean("bracket_match", true)).apply()
                updateBracketMatch()
                showIdeMenu()
            },
            "${ui("Horizontal Scroll")}: On" to { showToast(ui("Code wrap is disabled")) },
            "${ui("Language")}: ${AppLanguage.current(this).nativeName}" to { showLanguagePicker() },
            ui("Community") to { HtmlViewerActivity.openAsset(this, "index.html", "MobileStudio") },
            ui("Open Build Tools") to { ToolsActivity.start(this) }
        ))
    }

    private fun showLanguagePicker() {
        val languages = AppLanguage.supported
        val current = AppLanguage.current(this).code
        val checked = languages.indexOfFirst { it.code == current }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text(this, "language"))
            .setSingleChoiceItems(languages.map { it.nativeName }.toTypedArray(), checked) { dialog, which ->
                AppLanguage.set(this, languages[which].code)
                dialog.dismiss()
                showToast("Language: ${languages[which].nativeName}")
            }
            .show()
    }

    private fun stopBuild() {
        // Abort any tool download first: provisioning can be pulling gigabytes
        // before Gradle even starts, and that is what a user watching a long
        // "Downloading NDK..." line actually wants gone.
        BuildCancellation.cancel()
        gradleRunner.cancel()
        buildScheduler.cancelAndClear()
        setBuildRunning(false)
        buildScheduler.resumeAfterManualBuild(runPending = false)
        setStatus("Build stopped")
        buildOutput.appendLine("> Build stopped by user", LineLevel.WARNING)
    }

    private fun forceKillGradle() {
        gradleRunner.cancel()
    }

    private fun showProjectTextMenu() {
        AlertDialog.Builder(this)
            .setTitle("Projects")
            .setItems(arrayOf("New Project", "Open Project", "Explorer", "Load Examples", "Inspect Project")) { _, which ->
                when (which) {
                    0 -> showNewProjectDialog()
                    1 -> showOpenProjectPicker()
                    2 -> {
                        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                            drawerLayout.closeDrawer(GravityCompat.START)
                        } else {
                            drawerLayout.openDrawer(GravityCompat.START)
                        }
                    }
                    3 -> loadProjectExamples()
                    4 -> runProjectInspection()
                }
            }
            .show()
    }

    private fun loadProjectExamples() {
        val root = currentProjectDir ?: run {
            showToast("Open a project first")
            return
        }
        val count = ProjectExampleCatalog.install(root, currentProjectKind == ProjectKind.PYTHON)
        loadFileTree(root)
        showToast("Loaded $count examples")
    }

    private fun openCurrentXmlPreview() {
        val file = currentFile ?: run {
            showToast("Open an XML file first")
            return
        }
        openCurrentPreview(file)
    }

    private fun openCurrentPreview(file: File) {
        val extension = file.extension.lowercase()
        when (extension) {
            "xml" -> LayoutPreviewActivity.start(this, file)
            "html", "htm" -> com.aide.mobilestudio.web.HtmlPreviewActivity.start(this, file)
            "kt" -> if (runCatching { file.readText().contains("@Preview") }.getOrDefault(false)) {
                ComposePreviewActivity.start(this, file)
            } else {
                showToast("Preview is available for XML layouts, HTML pages and Kotlin @Preview files")
            }
            // A stylesheet or script is only rendered as part of a page, so
            // point at the page that pulls it in rather than pretending to
            // preview it on its own.
            "css", "js", "mjs", "ts" -> {
                val page = runCatching {
                    file.parentFile?.listFiles()
                        ?.firstOrNull { it.isFile && it.extension.lowercase() in listOf("html", "htm") }
                }.getOrNull()
                if (page != null) {
                    com.aide.mobilestudio.web.HtmlPreviewActivity.start(this, page)
                } else {
                    showToast("Open the HTML page that uses $extension files to preview it")
                }
            }
            else -> showToast("Preview is available for XML layouts, HTML pages and Kotlin @Preview files")
        }
    }

    private fun showComposePreviewSummary(file: File) {
        val isKo = AppLanguage.isKorean(this)
        val source = runCatching { file.readText() }.getOrDefault("")
        val previews = Regex("""@Preview[\s\S]{0,300}?\bfun\s+(\w+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()
        AlertDialog.Builder(this)
            .setTitle(if (isKo) "Compose 미리보기" else "Compose Preview")
            .setMessage(
                if (previews.isEmpty()) {
                    if (isKo) "${file.name}에서 @Preview 컴포저블을 찾지 못했습니다." else "No @Preview composables found in ${file.name}."
                } else {
                    (if (isKo) "미리보기 Composable 발견:\n\n" else "Found preview composables:\n\n") +
                        previews.joinToString("\n") { "→$it()" } +
                        (if (isKo) "\n\n현재 기기에서는 Compose 미리보기 렌더링을 지원하지 않습니다. 여기에는 미리보기 진입점만 나열합니다."
                        else "\n\nOn-device interactive Compose rendering is limited; this lists preview entry points for now.")
                }
            )
            .setPositiveButton(if (isKo) "확인" else "OK", null)
            .show()
    }

    private fun updatePreviewButtonVisibility() {
        val file = currentFile
        if (file?.extension?.lowercase() == "xml") {
            findViewById<ImageButton>(R.id.btn_preview)?.visibility = View.VISIBLE
            return
        }
        if (file?.extension?.lowercase() == "kt") {
            scope.launch(Dispatchers.IO) {
                val hasPreview = runCatching { file.readText().contains("@Preview") }.getOrDefault(false)
                if (currentFile == file) {
                    withContext(Dispatchers.Main) {
                        findViewById<ImageButton>(R.id.btn_preview)?.visibility = if (hasPreview) View.VISIBLE else View.GONE
                    }
                }
            }
            return
        }
        findViewById<ImageButton>(R.id.btn_preview)?.visibility = View.GONE
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun insertAtCursor(text: String) {
        val start = codeEditor.selectionStart.coerceAtLeast(0)
        val end = codeEditor.selectionEnd.coerceAtLeast(start)
        codeEditor.text?.replace(start, end, text)
        // For paired characters place cursor in middle
        if (text.length == 2 && text[0] != text[1]) {
            codeEditor.setSelection(start + 1)
        }
    }

    private fun moveCursor(delta: Int) {
        val pos = (codeEditor.selectionStart + delta).coerceIn(0, codeEditor.text?.length ?: 0)
        codeEditor.setSelection(pos)
    }

    internal fun handleEditorToolbarAction(action: String) {
        when (action) {
            "__CURSOR_LEFT__" -> moveCursor(-1)
            "__CURSOR_RIGHT__" -> moveCursor(+1)
            "__QUICK_FIX__" -> showQuickFixesForCurrentLine()
            "__DUP_LINE__" -> codeEditor.duplicateCurrentLine()
            "__DELETE_LINE__" -> codeEditor.deleteCurrentLine()
            "__TOGGLE_COMMENT__" -> codeEditor.toggleLineComment(commentPrefixFor(currentFile))
            "__MOVE_LINE_UP__" -> codeEditor.moveCurrentLineUp()
            "__MOVE_LINE_DOWN__" -> codeEditor.moveCurrentLineDown()
        }
    }

    private fun commentPrefixFor(file: File?): String = when (file?.extension?.lowercase()) {
        "xml", "html", "htm", "md", "markdown" -> "<!-- "
        "py", "pyw", "gd", "sh", "bash", "zsh", "toml", "ini", "cfg", "conf", "r" -> "# "
        "lua", "sql", "hs" -> "-- "
        else -> "// "
    }

    // MobileStudio comment
    // Welcome screen
    // MobileStudio comment

    private fun setupWelcomeScreen() {
        findViewById<Button>(R.id.btn_new_project).setOnClickListener { showNewProjectDialog() }
        findViewById<Button>(R.id.btn_open_project).setOnClickListener { showOpenProjectPicker() }

        val rvRecent = findViewById<RecyclerView>(R.id.rv_recent_projects).apply {
            // Phase 7.1: RecyclerView optimizations
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                isItemPrefetchEnabled = true
            }
            setItemViewCacheSize(10)
            isDrawingCacheEnabled = true
        }
        refreshRecentProjects(rvRecent)
        refreshRecentFiles()

        findViewById<ImageButton>(R.id.btn_drawer_new_project).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showNewProjectDialog()
        }
        findViewById<TextView>(R.id.btn_tutorial)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showTutorial()
        }
        findViewById<TextView>(R.id.btn_settings)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showSettingsDialog()
        }
        findViewById<TextView>(R.id.btn_theme)?.setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            showThemeDialog()
        }
        findViewById<ImageButton>(R.id.btn_new_file).setOnClickListener { showNewFileDialog() }
        findViewById<ImageButton>(R.id.btn_new_folder).setOnClickListener { showNewFolderDialog() }
    }

    private fun refreshRecentProjects(rv: RecyclerView) {
        val projects = StorageHelper.listProjects(this)
        val recentPaths = StorageHelper.getRecentProjects(this)
        val ordered = (recentPaths.mapNotNull { path ->
            projects.firstOrNull { it.absolutePath == path }
        } + projects).distinctBy { it.absolutePath }
        val show = ordered.isNotEmpty()
        findViewById<TextView>(R.id.recent_label)?.visibility = if (show) View.VISIBLE else View.GONE
        rv.visibility = if (show) View.VISIBLE else View.GONE
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
                object : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 12, 0, 12)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }) {}

            override fun getItemCount() = ordered.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val p = ordered[pos]
                val row = holder.itemView as LinearLayout
                row.removeAllViews()
                val icon = TextView(this@MainActivity).apply {
                    text = "\uD83D\uDCC4"; textSize = 16f; setPadding(0, 0, 12, 0)
                }
                val info = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL; layoutParams =
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val name = TextView(this@MainActivity).apply {
                    text = p.name; textSize = 13f; typeface = Typeface.MONOSPACE
                    setTextColor(StudioUi.color("#61AFEF"))
                }
                val path = TextView(this@MainActivity).apply {
                    text = StorageHelper.displayPath(this@MainActivity, p.name)
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                }
                info.addView(name); info.addView(path)
                row.addView(icon); row.addView(info)
                row.setOnClickListener { openProject(p) }
                row.setOnLongClickListener {
                    showProjectContextMenu(p); true
                }
            }
        }
    }

    private fun refreshRecentFiles() {
        val files = StorageHelper.getRecentFiles(this)
            .map { File(it) }.filter { it.exists() }
        val label = findViewById<TextView>(R.id.recent_files_label)
        val rv = findViewById<RecyclerView>(R.id.rv_recent_files).apply {
            // Phase 7.1: RecyclerView optimizations
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                isItemPrefetchEnabled = true
            }
            setItemViewCacheSize(10)
            isDrawingCacheEnabled = true
        }
        label.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        rv.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
                object : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10, 0, 10)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }) {}

            override fun getItemCount() = files.size
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
                val f = files[pos]
                val row = holder.itemView as LinearLayout
                row.removeAllViews()
                val icon = TextView(this@MainActivity).apply {
                    text = "\uD83D\uDCC1"; textSize = 15f; setPadding(0, 0, 12, 0)
                }
                val info = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val name = TextView(this@MainActivity).apply {
                    text = f.name; textSize = 13f; typeface = Typeface.MONOSPACE
                    setTextColor(StudioUi.color("#98C379"))
                }
                val path = TextView(this@MainActivity).apply {
                    text = f.parentFile?.name ?: ""
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                }
                info.addView(name); info.addView(path)
                row.addView(icon); row.addView(info)
                row.setOnClickListener { openFile(f) }
                row.setOnLongClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setItems(arrayOf("Remove from Recents", "Open")) { _, i ->
                            when (i) {
                                0 -> {
                                    StorageHelper.removeRecentFile(this@MainActivity, f.absolutePath)
                                    refreshRecentFiles()
                                }
                                1 -> openFile(f)
                            }
                        }.show()
                    true
                }
            }
        }
    }

    private fun showProjectContextMenu(dir: File) {
        AlertDialog.Builder(this).setTitle(dir.name)
            .setItems(arrayOf("Open", "Delete", "Rename")) { _, i ->
                when (i) {
                    0 -> openProject(dir)
                    1 -> {
                        AlertDialog.Builder(this)
                            .setMessage("Delete project \"${dir.name}\"?")
                            .setPositiveButton("Delete") { _, _ ->
                                dir.deleteRecursively()
                                StorageHelper.removeRecentProject(this, dir.absolutePath)
                                refreshRecentProjects(findViewById(R.id.rv_recent_projects))
                                showToast("Deleted")
                            }.setNegativeButton("Cancel", null).show()
                    }

                    2 -> {
                        val input = EditText(this).apply { setText(dir.name) }
                        AlertDialog.Builder(this).setTitle("Rename").setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                val newDir = File(dir.parent, input.text.toString().trim())
                                dir.renameTo(newDir)
                                if (currentProjectDir == dir) openProject(newDir)
                                refreshRecentProjects(findViewById(R.id.rv_recent_projects))
                            }.setNegativeButton("Cancel", null).show()
                    }
                }
            }.show()
    }

    // MobileStudio comment
    // File Tree
    // MobileStudio comment

    private fun setupFileTree() {
        fileTree.layoutManager = LinearLayoutManager(this).apply {
            isItemPrefetchEnabled = true
        }
        fileTree.setItemViewCacheSize(20)
        fileTree.isDrawingCacheEnabled = true
        fileAdapter = FileAdapter(
            mutableListOf(),
            { file -> if (file.isFile) openFile(file) },
            { file, activity -> ContextMenuUtils.showFileContextMenu(activity, file); true },
            { item -> toggleFolder(item) }
        )
        fileTree.adapter = fileAdapter

        // File tree search filter
        val searchInput = findViewById<EditText>(R.id.et_file_tree_search)
        searchInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                fileAdapter.filter(s?.toString() ?: "")
            }
        })
    }

    internal fun loadFileTree(root: File) {
        val items = mutableListOf<FileItem>()
        fun appendChildren(dir: File, depth: Int) {
            val files = dir.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()
            files.forEach { child ->
                val expanded = child.isDirectory && expandedFileTreePaths.contains(canonicalPath(child))
                items += FileItem(child, depth, expanded)
                if (expanded) appendChildren(child, depth + 1)
            }
        }
        appendChildren(root, 0)

        // The Compose shell reads `treeItems` (a SnapshotStateList); the legacy
        // View adapter/tree are only initialized in the removed bindViews().
        // Always publish to the Compose list first so the drawer shows the full
        // tree, then best-effort update the legacy views when they exist.
        treeItems.clear()
        treeItems.addAll(items)
        if (::fileAdapter.isInitialized) {
            fileAdapter.setAllItems(items.toList())
            fileAdapter.updateItems(items)
        }
        updateDrawerPath()
        if (::fileTree.isInitialized && StudioUi.shouldAnimate(this)) {
            fileTree.post {
                val views = (0 until fileTree.childCount.coerceAtMost(15))
                    .mapNotNull { fileTree.getChildAt(it) }
                StudioUi.staggerIn(views, 20)
            }
        }
    }

    /** Windows Explorer style: Up collapses the tree back to the project root. */
    fun navigateDrawerUp() {
        expandedFileTreePaths.clear()
        currentProjectDir?.let { loadFileTree(it) }
        fileTree.smoothScrollToPosition(0)
    }

    /** Shows the current project path in the drawer's path bar. */
    private fun updateDrawerPath() {
        val pathTv = findViewById<TextView>(R.id.tv_drawer_path) ?: return
        val dir = currentProjectDir
        pathTv.text = if (dir != null) {
            val home = runCatching { StorageHelper.projectsRoot(this) }.getOrNull() ?: dir.parentFile
            val prefix = (home?.absolutePath ?: "") + "/"
            val rel = dir.absolutePath.removePrefix(prefix)
            if (rel != dir.absolutePath && rel.isNotBlank()) rel else dir.name
        } else "No project"
    }

    private fun toggleFolder(item: FileItem) {
        if (!::fileAdapter.isInitialized) return
        val idx = fileAdapter.items.indexOf(item)
        if (idx < 0) return
        val newItems = fileAdapter.items.toMutableList()
        if (item.isExpanded) {
            expandedFileTreePaths.remove(canonicalPath(item.file))
            var end = idx + 1
            while (end < newItems.size && newItems[end].depth > item.depth) end++
            val count = end - (idx + 1)
            newItems.subList(idx + 1, end).clear()
            newItems[idx] = item.copy(isExpanded = false)
            fileAdapter.items = newItems
            fileAdapter.notifyItemRangeRemoved(idx + 1, count)
            fileAdapter.notifyItemChanged(idx)
        } else {
            expandedFileTreePaths.add(canonicalPath(item.file))
            val children = item.file.listFiles()
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                ?.map { FileItem(it, item.depth + 1) } ?: emptyList()
            newItems[idx] = item.copy(isExpanded = true)
            newItems.addAll(idx + 1, children)
            fileAdapter.items = newItems
            fileAdapter.notifyItemRangeInserted(idx + 1, children.size)
            fileAdapter.notifyItemChanged(idx)
        }
    }

    // ── FileOperationsHandler ────────────────────────────────────────────

    override fun createNewFile(parent: File) {
        val ctx = this
        val input = EditText(ctx).apply {
            hint = "filename (e.g. MyClass.kt)"
            setPadding(StudioUi.dp(ctx, 16), StudioUi.dp(ctx, 12), StudioUi.dp(ctx, 16), StudioUi.dp(ctx, 12))
            setTextColor(StudioUi.color(StudioUi.Text))
            setHintTextColor(StudioUi.color(StudioUi.Muted))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("New File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    try {
                        val file = File(parent, name)
                        if (file.exists()) {
                            android.widget.Toast.makeText(ctx, "File already exists", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            file.createNewFile()
                            expandedFileTreePaths.add(canonicalPath(parent))
                            refreshFileTree()
                            openFile(file)
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(ctx, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun createNewFolder(parent: File) {
        val ctx = this
        val input = EditText(ctx).apply {
            hint = "folder name"
            setPadding(StudioUi.dp(ctx, 16), StudioUi.dp(ctx, 12), StudioUi.dp(ctx, 16), StudioUi.dp(ctx, 12))
            setTextColor(StudioUi.color(StudioUi.Text))
            setHintTextColor(StudioUi.color(StudioUi.Muted))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    try {
                        val dir = File(parent, name)
                        if (dir.exists()) {
                            android.widget.Toast.makeText(ctx, "Folder already exists", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            dir.mkdirs()
                            expandedFileTreePaths.add(canonicalPath(parent))
                            refreshFileTree()
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(ctx, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun renameFile(file: File) {
        val ctx = this
        val input = EditText(ctx).apply {
            setText(file.name)
            setPadding(StudioUi.dp(ctx, 16), StudioUi.dp(ctx, 12), StudioUi.dp(ctx, 16), StudioUi.dp(ctx, 12))
            setTextColor(StudioUi.color(StudioUi.Text))
            setHintTextColor(StudioUi.color(StudioUi.Muted))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            selectAll()
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    try {
                        val newFile = File(file.parentFile, newName)
                        if (newFile.exists()) {
                            android.widget.Toast.makeText(ctx, "Name already exists", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            val success = file.renameTo(newFile)
                            if (success) {
                                if (currentFile?.absolutePath == file.absolutePath) {
                                    currentFile = newFile
                                    openTabs.replaceAll { if (it == file) newFile else it }
                                }
                                refreshFileTree()
                            } else {
                                android.widget.Toast.makeText(ctx, "Rename failed", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.widget.Toast.makeText(ctx, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun deleteFile(file: File) {
        val ctx = this
        android.app.AlertDialog.Builder(ctx)
            .setTitle("Delete")
            .setMessage("Delete '${file.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    val deleted = file.deleteRecursively()
                    if (deleted) {
                        if (currentFile?.absolutePath == file.absolutePath) {
                            closeCurrentTab()
                        }
                        refreshFileTree()
                    } else {
                        android.widget.Toast.makeText(ctx, "Delete failed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    android.widget.Toast.makeText(ctx, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun refreshFileTree() {
        currentProjectDir?.let { root ->
            loadFileTree(root)
        }
    }

    override fun expandAll() {
        currentProjectDir?.let { root ->
            expandAllDirs(root)
            refreshFileTree()
        }
    }

    override fun collapseAll() {
        expandedFileTreePaths.clear()
        refreshFileTree()
    }

    private fun expandAllDirs(dir: File) {
        dir.listFiles()?.filter { it.isDirectory }?.forEach { child ->
            expandedFileTreePaths.add(canonicalPath(child))
            expandAllDirs(child)
        }
    }

    private fun closeCurrentTab() {
        codeEditor.setText("")
        currentFile = null
        supportActionBar?.title = getString(R.string.app_name)
    }

    // MobileStudio comment
    // Editor
    // MobileStudio comment

    private fun setupEditor() {
        // Pinch-to-zoom support
        setupEditorPinchZoom()
        // Editor swipe gesture for tab switching
        setupEditorSwipeGesture()
        codeEditor.setSingleLine(false)
        codeEditor.setHorizontallyScrolling(true)
        codeEditor.isHorizontalScrollBarEnabled = true
        codeEditor.showSoftInputOnFocus = true
        codeEditor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {
                editorBeforeChange = s?.toString().orEmpty()
            }
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {
                // Diagnostic squiggles go stale the moment their text is
                // deleted: SPAN_EXCLUSIVE_EXCLUSIVE spans are clamped by the
                // framework, not removed, so they linger on the remaining
                // characters. Clear them synchronously over the deleted range.
                if (b > 0 && s is android.text.Spannable) {
                    val end = (st + b).coerceAtMost(s.length)
                    s.getSpans(st, end, SquigglyUnderlineSpan::class.java).forEach { s.removeSpan(it) }
                    s.getSpans(st, end, DiagnosticClickableSpan::class.java).forEach { s.removeSpan(it) }
                }
                if (suppressEditorChange || autoPairEdit) return
                if (a > 0 && s != null) {
                    codeEditor.propagateTypedText(st, s.subSequence(st, st + a))
                } else if (b > 0) {
                    codeEditor.propagateDeletedText(st, st + b)
                }
            }
            override fun afterTextChanged(s: Editable?) {
                // Inline AI ghost text is anchored to the pre-edit cursor
                // position — it must never survive an edit.
                codeEditor.clearGhostText()
                if (suppressEditorChange || autoPairEdit) return
                handleAutoPair(s)
                if (!unsavedChanges) {
                    unsavedChanges = true
                    currentFile?.let { unsavedFiles.add(it.absolutePath) }
                    updateTabTitle()
                }
                if (isGradleSyncFile(currentFile)) {
                    markGradleSyncNeeded(currentFile)
                }
                updateStatusBar()
                updateBracketMatch()
                rescheduleLiveDiagnostics()
                rescheduleAutoSave()
            }
        })

        codeEditor.setOnScrollChangeListener { _, _, _, _, _ ->
            if (!isProgrammaticScroll) {
                codeEditor.clearHighlight()
            }
        }

        // Cursor change listener updates status bar line/column.
        codeEditor.setOnClickListener {
            updateStatusBar()
            updateBracketMatch()
        }
        codeEditor.setOnTouchListener { view, event ->
            // Feed to pinch-to-zoom detector
            editorScaleDetector?.onTouchEvent(event)
            if (editorScaleDetector?.isInProgress == true) {
                return@setOnTouchListener true
            }
            val ctrlNavigation = (event.metaState and KeyEvent.META_CTRL_ON) != 0
            if (ctrlNavigation) {
                val offset = editorOffsetForTouch(event)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        if (offset >= 0) highlightCtrlSymbolAtOffset(offset)
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (offset >= 0) goToSymbolAtOffset(offset)
                        clearCtrlSymbolHighlight(delayMs = 260L)
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        clearCtrlSymbolHighlight()
                        return@setOnTouchListener true
                    }
                }
            }
            // Long-press → let Android handle default text selection (copy/cut/paste)
            // Symbol lookup is now triggered via Volume Down long-press + selected text
            // Tap on a collapsed "bar" expands it
            if (!ctrlNavigation && event.actionMasked == MotionEvent.ACTION_UP && longPressStartTime == 0L) {
                val offset = editorOffsetForTouch(event)
                val layout = codeEditor.layout
                if (offset >= 0 && layout != null) {
                    val line = layout.getLineForOffset(offset.coerceIn(0, codeEditor.text?.length ?: 0))
                    if (codeEditor.isLineCollapsed(line)) {
                        codeEditor.expandAtLine(line)
                        return@setOnTouchListener true
                    }
                }
            }
            false
        }

        // Smart indent on Enter. Return true when handled so Android does not insert a second newline.
        codeEditor.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            // ESC key ??close autocomplete, close find panel, deselect
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                autoComplete.dismiss()
                webAutoComplete.dismiss()
                if (findReplaceVisible) { hideFindReplace(); return@setOnKeyListener true }
                codeEditor.setSelection(codeEditor.selectionStart)
                vibrate(15)
                return@setOnKeyListener true
            }

            // Tab key ??insert 4 spaces
            if (keyCode == KeyEvent.KEYCODE_TAB) {
                val cursor = codeEditor.selectionStart
                codeEditor.text?.insert(cursor, "    ")
                codeEditor.setSelection(cursor + 4)
                vibrate(10)
                return@setOnKeyListener true
            }

            if (handleEditorShortcut(keyCode, event)) {
                return@setOnKeyListener true
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                if (handleSmartIndent()) {
                    updateStatusBar()
                    vibrate(10)
                    return@setOnKeyListener true
                }
            }
            // Backspace: smart delete (full indentation)
            if (keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN) {
                if (handleSmartBackspace()) {
                    updateStatusBar()
                    updateBracketMatch()
                    vibrate(10)
                    return@setOnKeyListener true
                }
            }
            updateStatusBar()
            updateBracketMatch()
            false
        }
    }

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun handleEditorShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (!event.isCtrlPressed) return false
        return when (keyCode) {
            KeyEvent.KEYCODE_A -> { codeEditor.selectAll(); vibrate(10); true }
            KeyEvent.KEYCODE_C -> { copyEditorSelection(); vibrate(10); true }
            KeyEvent.KEYCODE_X -> { copyEditorSelection(); deleteEditorSelection(); vibrate(10); true }
            KeyEvent.KEYCODE_V -> { pasteIntoEditor(); vibrate(10); true }
            KeyEvent.KEYCODE_S -> {
                if (event.isShiftPressed) false
                else { saveCurrentFile(); vibrate(10); true }
            }
            KeyEvent.KEYCODE_Z -> {
                if (event.isShiftPressed) { codeEditor.redo(); vibrate(10); true }
                else { codeEditor.undo(); vibrate(10); true }
            }
            KeyEvent.KEYCODE_Y -> { codeEditor.redo(); vibrate(10); true }
            KeyEvent.KEYCODE_SPACE -> { requestAiCompletion(); vibrate(10); true }
            KeyEvent.KEYCODE_F -> { toggleFindReplace(); vibrate(10); true }
            KeyEvent.KEYCODE_Q -> { showQuickDocumentation(); vibrate(10); true }
            KeyEvent.KEYCODE_I -> { autoImportCurrentSymbol(); vibrate(10); true }
            KeyEvent.KEYCODE_D -> { duplicateCurrentLine(); vibrate(15); true }
            KeyEvent.KEYCODE_SLASH -> { toggleComment(); vibrate(15); true }
            KeyEvent.KEYCODE_DEL -> { deleteWordBackward(); vibrate(15); true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.isShiftPressed) { moveLineUp(); vibrate(15); true } else false
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.isShiftPressed) { moveLineDown(); vibrate(15); true } else false
            }
            KeyEvent.KEYCODE_L -> {
                if (event.isShiftPressed) { formatCurrentFile(); vibrate(15); true } else false
            }
            KeyEvent.KEYCODE_R -> {
                if (event.isShiftPressed) { showRenameSymbolDialog(); vibrate(10); true } else false
            }
            // Ctrl+B: Go to definition
            KeyEvent.KEYCODE_B -> {
                val cursor = codeEditor.selectionStart
                goToSymbolAtOffset(cursor)
                vibrate(10)
                true
            }
            // Ctrl+N: Go to file
            KeyEvent.KEYCODE_N -> {
                showSearchEverywhere("Files")
                vibrate(10)
                true
            }
            // Ctrl+Shift+P: Command Palette
            KeyEvent.KEYCODE_P -> {
                if (event.isShiftPressed) {
                    commandPalette.show()
                    vibrate(10)
                    true
                } else {
                    showParameterHint()
                    vibrate(10)
                    true
                }
            }
            else -> false
        }
    }

    /**
     * Engine for inline code completion. The old bottom-panel AI owned this
     * job; with the panel gone the editor keeps its own engine, created on
     * first use. (Behavior parity note: OcEngine.provider is never populated,
     * so this path reported "No AI provider" before this change as well.)
     */
    private val completionEngine: com.aide.mobilestudio.ai.opencode.agent.OcEngine by lazy {
        com.aide.mobilestudio.ai.opencode.agent.OcEngine(applicationContext).apply { reloadConfig() }
    }

    private fun requestAiCompletion() {
        val text = codeEditor.text ?: return
        val cursor = codeEditor.selectionStart.coerceIn(0, text.length)
        val prefix = text.substring(0, cursor)
        
        // Only trigger if we have enough context
        if (prefix.length < 20) {
            codeEditor.setGhostText("Type more code for AI completion...")
            return
        }
        
        codeEditor.setGhostText("Thinking...")
        val engine = completionEngine // initialize on the UI thread, use below
        
        scope.launch(Dispatchers.IO) {
            val prompt = """
                Complete the following code. Only output the completion, no explanations.
                
                \`\`\`${currentFile?.extension ?: "kt"}
                $prefix
                \`\`\`
                """.trimIndent()
            
            val result = runCatching {
                engine.provider?.let { provider ->
                    val history = JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                    })
                    val stream = provider.generateStream(history, null, null)
                    val result = StringBuilder()
                    stream.collect { event ->
                        when (event) {
                            is AIProvider.StreamEvent.Text -> result.append(event.text)
                            is AIProvider.StreamEvent.Done -> Unit
                            is AIProvider.StreamEvent.Error -> throw Exception(event.message)
                            else -> Unit
                        }
                    }
                    result.toString()
                } ?: "Error: No AI provider"
            }
            
            runOnUiThread {
                val result = result.getOrElse { it.message ?: "Completion failed" }
                val completion = result.takeIf { it.isNotBlank() } ?: "No completion"
                codeEditor.setGhostText(completion)
            }
        }
    }

    private fun copyEditorSelection() {
        val text = codeEditor.text ?: return
        val start = codeEditor.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
        val end = codeEditor.selectionEnd.coerceAtLeast(0).coerceAtMost(text.length)
        if (start == end) return
        val selected = text.substring(minOf(start, end), maxOf(start, end))
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MobileStudio selection", selected))
    }

    private fun deleteEditorSelection() {
        val text = codeEditor.text ?: return
        val start = codeEditor.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
        val end = codeEditor.selectionEnd.coerceAtLeast(0).coerceAtMost(text.length)
        if (start == end) return
        text.delete(minOf(start, end), maxOf(start, end))
    }

    private fun pasteIntoEditor() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?: return
        val text = codeEditor.text ?: return
        val start = codeEditor.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
        val end = codeEditor.selectionEnd.coerceAtLeast(0).coerceAtMost(text.length)
        text.replace(minOf(start, end), maxOf(start, end), value)
    }

    private fun editorOffsetForTouch(event: MotionEvent): Int {
        val layout = codeEditor.layout ?: return -1
        val x = (event.x + codeEditor.scrollX - codeEditor.totalPaddingLeft).coerceAtLeast(0f)
        val y = (event.y + codeEditor.scrollY - codeEditor.totalPaddingTop).coerceAtLeast(0f)
        val line = layout.getLineForVertical(y.toInt()).coerceIn(0, codeEditor.lineCount - 1)
        return layout.getOffsetForHorizontal(line, x).coerceIn(0, codeEditor.text?.length ?: 0)
    }

    internal fun goToSymbolAtOffset(offset: Int) {
        val source = codeEditor.text?.toString().orEmpty()
        val symbol = wordAtOffset(source, offset)
        if (symbol.isBlank()) {
            showToast("No symbol under cursor")
            return
        }
        // Check if it's a resource reference like @string/app_name
        val lineText = source.lines().getOrNull(source.substring(0, offset).count { it == '\n' }).orEmpty()
        if (tryNavigateToResource(symbol, lineText)) return
        val targets = findSymbolTargets(symbol)
        when {
            targets.isEmpty() -> showToast("No declaration found: $symbol")
            targets.size == 1 -> openSymbolTarget(targets.first())
            else -> showGoToDialog(symbol, targets)
        }
    }

    private fun showContextMenuAtOffset(offset: Int, anchorView: View) {
        val source = codeEditor.text?.toString().orEmpty()
        val symbol = wordAtOffset(source, offset)
        if (symbol.isBlank()) return
        val line = source.substring(0, offset).count { it == '\n' } + 1
        val lineText = source.lines().getOrNull(line - 1).orEmpty()

        val symbolType = when {
            lineText.contains(Regex("""\bfun\s+${Regex.escape(symbol)}""")) -> CodeContextMenu.SymbolType.FUNCTION
            lineText.contains(Regex("""\b(class|interface|object)\s+${Regex.escape(symbol)}""")) -> CodeContextMenu.SymbolType.CLASS
            lineText.contains(Regex("""\b(val|var)\s+${Regex.escape(symbol)}""")) -> CodeContextMenu.SymbolType.PROPERTY
            symbol.startsWith("@") || symbol.startsWith("R.") -> CodeContextMenu.SymbolType.RESOURCE
            else -> CodeContextMenu.SymbolType.UNKNOWN
        }

        val actions = mutableMapOf<String, () -> Unit>()
        actions["goToDefinition"] = { goToSymbolAtOffset(offset) }
        actions["goToDeclaration"] = { goToSymbolAtOffset(offset) }
        actions["findUsages"] = {
            val usages = findSymbolUsages(symbol)
            showSymbolTargetsDialog(symbol, usages)
        }
        actions["copyReference"] = {
            val ref = "${currentFile?.name}:${line}: $symbol"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Symbol Reference", ref))
            showToast("Reference copied")
        }
        actions["comment"] = { toggleComment() }
        actions["format"] = { formatCurrentFile() }

        codeContextMenu.show(anchorView, symbol, symbolType, currentFile, line, actions)
    }

    private fun showSymbolInfoForText(symbol: String) {
        if (symbol.isBlank()) return
        val targets = findSymbolTargets(symbol)
        val usages = findSymbolUsages(symbol)
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Symbol: $symbol")
            .setMessage(buildString {
                if (targets.isNotEmpty()) {
                    appendLine("Declarations (${targets.size}):")
                    for (t in targets.take(5)) {
                        appendLine("  ${t.file.name}:${t.line} ${t.preview}")
                    }
                } else {
                    appendLine("No declarations found")
                }
                if (usages.isNotEmpty()) {
                    appendLine()
                    appendLine("Usages (${usages.size}):")
                    for (u in usages.take(5)) {
                        appendLine("  ${u.file.name}:${u.line}")
                    }
                }
            })
            .setPositiveButton("Go to Definition") { _, _ ->
                if (targets.isNotEmpty()) openSymbolTarget(targets.first())
            }
            .setNeutralButton("Find Usages") { _, _ ->
                showSymbolTargetsDialog(symbol, usages)
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun tryNavigateToResource(symbol: String, lineText: String): Boolean {
        val root = currentProjectDir ?: return false
        // Match @string/app_name, @color/black, etc.
        val atMatch = Regex("""@(\w+)/(\w+)""").find(lineText)
        if (atMatch != null) {
            val resType = atMatch.groupValues[1]
            val resName = atMatch.groupValues[2]
            return navigateToResourceFile(root, resType, resName)
        }
        // Match R.string.app_name, R.id.button1, etc.
        val rMatch = Regex("""R\.(\w+)\.(\w+)""").find(symbol)
        if (rMatch == null) {
            val rMatchLine = Regex("""R\.(\w+)\.(\w+)""").find(lineText)
            if (rMatchLine != null) {
                val resType = rMatchLine.groupValues[1]
                val resName = rMatchLine.groupValues[2]
                return navigateToResourceFile(root, resType, resName)
            }
        } else {
            val resType = rMatch.groupValues[1]
            val resName = rMatch.groupValues[2]
            return navigateToResourceFile(root, resType, resName)
        }
        return false
    }

    private fun navigateToResourceFile(root: File, resType: String, resName: String): Boolean {
        val resDir = File(root, "app/src/main/res")
        if (!resDir.exists()) return false
        val typeDir = File(resDir, "${resType}s") // string -> strings, etc.
        if (!typeDir.exists()) {
            val typeDirAlt = File(resDir, resType)
            if (!typeDirAlt.exists()) return false
            return findAndOpenResourceFile(typeDirAlt, resName)
        }
        return findAndOpenResourceFile(typeDir, resName)
    }

    private fun findAndOpenResourceFile(dir: File, resName: String): Boolean {
        val xmlFiles = dir.listFiles { f -> f.extension == "xml" } ?: return false
        for (file in xmlFiles) {
            try {
                val content = file.readText()
                if (content.contains("""name="$resName"""")) {
                    openFile(file)
                    val idx = content.indexOf("""name="$resName"""")
                    if (idx >= 0) {
                        val line = content.substring(0, idx).count { it == '\n' } + 1
                        val lineStart = content.lines().take(line - 1).sumOf { it.length + 1 }
                        if (lineStart <= (codeEditor.text?.length ?: 0)) {
                            codeEditor.setSelection(lineStart)
                        }
                    }
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    private fun showGoToDialog(symbol: String, targets: List<SymbolTarget>) {
        val root = currentProjectDir
        val results = targets.map {
            com.aide.mobilestudio.analysis.GoToDefinitionResult(
                file = it.file,
                line = it.line,
                column = it.column,
                preview = it.preview,
                symbol = symbol
            )
        }
        com.aide.mobilestudio.analysis.QuickFixProvider.showGoToDefinitionDialog(
            this, symbol, results
        ) { result ->
            openSymbolTarget(SymbolTarget(result.file, result.line, result.column, result.preview))
        }
    }

    private fun highlightCtrlSymbolAtOffset(offset: Int) {
        val editable = codeEditor.text ?: return
        val source = editable.toString()
        val range = wordRangeAtOffset(source, offset) ?: run {
            clearCtrlSymbolHighlight()
            return
        }
        clearCtrlSymbolHighlight()
        val spans = listOf(
            ForegroundColorSpan(StudioUi.color("#7CC7FF")),
            BackgroundColorSpan(Color.argb(36, 88, 166, 255)),
            UnderlineSpan()
        )
        spans.forEach { span ->
            editable.setSpan(span, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        ctrlSymbolHighlightSpans = spans
    }

    private fun clearCtrlSymbolHighlight(delayMs: Long = 0L) {
        if (delayMs > 0L) {
            Handler(Looper.getMainLooper()).postDelayed({ clearCtrlSymbolHighlight() }, delayMs)
            return
        }
        val editable = codeEditor.text ?: return
        ctrlSymbolHighlightSpans.forEach { editable.removeSpan(it) }
        ctrlSymbolHighlightSpans = emptyList()
    }

    private fun wordAtOffset(source: String, offset: Int): String {
        val range = wordRangeAtOffset(source, offset) ?: return ""
        return source.substring(range.first, range.last + 1)
    }

    private fun wordRangeAtOffset(source: String, offset: Int): IntRange? {
        if (source.isEmpty()) return null
        val safe = offset.coerceIn(0, source.length - 1)
        fun isWordChar(index: Int): Boolean {
            val c = source.getOrNull(index) ?: return false
            return c == '_' || c.isLetterOrDigit()
        }
        val seed = when {
            isWordChar(safe) -> safe
            safe > 0 && isWordChar(safe - 1) -> safe - 1
            else -> return null
        }
        var start = seed
        var end = seed + 1
        while (start > 0 && isWordChar(start - 1)) start--
        while (end < source.length && isWordChar(end)) end++
        return start until end
    }

    private fun findSymbolTargets(symbol: String): List<SymbolTarget> {
        val root = currentProjectDir ?: return emptyList()
        val files = root.walkTopDown()
            .onEnter { dir -> dir.name !in setOf("build", ".gradle", ".git", ".cxx") }
            .filter { it.isFile && it.extension.lowercase() in setOf("kt", "kts", "java") && it.length() <= 2_000_000L }
            .take(500)
            .toList()
        val escaped = Regex.escape(symbol)
        val patterns = listOf(
            Regex("""\bfun\s+$escaped\s*\("""),
            Regex("""\b(?:class|interface|object|enum\s+class|data\s+class)\s+$escaped\b"""),
            Regex("""\b(?:public|private|protected|internal|static|final|open|override|suspend|\s)*[A-Za-z_][\w<>,.? \[\]]+\s+$escaped\s*\(""")
        )
        return files.flatMap { file ->
            val text = runCatching { file.readText() }.getOrDefault("")
            if (text.isBlank()) return@flatMap emptyList()
            val lines = text.lines()
            patterns.flatMap { pattern ->
                pattern.findAll(text).map { match ->
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
                    SymbolTarget(
                        file = file,
                        line = line,
                        column = match.range.first - lineStart + 1,
                        preview = lines.getOrNull(line - 1)?.trim().orEmpty()
                    )
                }
            }
        }.distinctBy { "${it.file.absolutePath}:${it.line}:${it.column}" }
            .sortedWith(compareBy({ it.file != currentFile }, { it.file.name }, { it.line }))
            .take(80)
    }

    private fun showSymbolTargetsDialog(symbol: String, targets: List<SymbolTarget>) {
        val root = currentProjectDir
        if (targets.size > 5) {
            // Show scrollable list for 5+ targets
            val scrollView = android.widget.ScrollView(this)
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            targets.forEach { target ->
                val tv = TextView(this).apply {
                    text = "${target.file.relativePathFor(root)}:${target.line}    ${target.preview}"
                    textSize = 13f
                    setTextColor(StudioUi.color("#7CC7FF"))
                    setPadding(8, 12, 8, 12)
                    setOnClickListener { openSymbolTarget(target) }
                }
                container.addView(tv)
            }
            scrollView.addView(container)
            AlertDialog.Builder(this)
                .setTitle("$symbol · ${targets.size} usages")
                .setView(scrollView)
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            val labels = targets.map {
                "${it.file.relativePathFor(root)}:${it.line}    ${it.preview}"
            }
            AlertDialog.Builder(this)
                .setTitle("$symbol · ${targets.size} usages")
                .setItems(labels.toTypedArray()) { _, which -> openSymbolTarget(targets[which]) }
                .show()
        }
    }

    private fun selectedEditorText(): String? {
        val text = codeEditor.text ?: return null
        val start = codeEditor.selectionStart.coerceIn(0, text.length)
        val end = codeEditor.selectionEnd.coerceIn(start, text.length)
        return if (start < end) text.substring(start, end) else null
    }

    private fun currentEditorSymbol(): String {
        val selected = selectedEditorText()
            ?.trim()
            ?.takeIf { it.matches(Regex("""[A-Za-z_]\w*""")) }
        if (!selected.isNullOrBlank()) return selected
        return wordAtOffset(codeEditor.text?.toString().orEmpty(), codeEditor.selectionStart)
    }

    private fun showQuickDocumentation() {
        val symbol = currentEditorSymbol()
        if (symbol.isBlank()) {
            showToast("No symbol under cursor")
            return
        }
        val declarations = findSymbolTargets(symbol)
        val usages = findSymbolUsages(symbol)
        val root = currentProjectDir
        val rows = mutableListOf<Pair<String, SymbolTarget>>()
        declarations.forEach {
            rows += "Declaration  ${it.file.relativePathFor(root)}:${it.line}    ${it.preview}" to it
        }
        usages.filterNot { usage ->
            declarations.any { it.file == usage.file && it.line == usage.line && it.column == usage.column }
        }.take(40).forEach {
            rows += "Usage        ${it.file.relativePathFor(root)}:${it.line}    ${it.preview}" to it
        }
        val message = buildString {
            appendLine("Symbol: $symbol")
            appendLine("Declarations: ${declarations.size}")
            appendLine("Usages: ${usages.size}")
            if (declarations.isNotEmpty()) {
                appendLine()
                appendLine(declarations.first().preview)
            }
        }.trim()
        if (rows.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Quick Documentation")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Quick Documentation")
            .setMessage(message)
            .setItems(rows.map { it.first }.toTypedArray()) { _, which ->
                openSymbolTarget(rows[which].second)
            }
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showRenameSymbolDialog() {
        val symbol = currentEditorSymbol()
        if (symbol.isBlank()) {
            showToast("No symbol under cursor")
            return
        }
        val input = EditText(this).apply {
            setText(symbol)
            selectAll()
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(StudioUi.color(StudioUi.TextSoft))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Symbol")
            .setMessage("Rename '$symbol' across project Java/Kotlin files.")
            .setView(input)
            .setPositiveButton("Preview") { _, _ ->
                val newName = input.text.toString().trim()
                previewRenameSymbol(symbol, newName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun autoImportCurrentSymbol() {
        val symbol = currentEditorSymbol()
        if (symbol.isBlank()) {
            showToast("No symbol under cursor")
            return
        }
        val importPath = knownImportFor(symbol) ?: findProjectImportFor(symbol)
        if (importPath.isNullOrBlank()) {
            showToast("No import candidate: $symbol")
            return
        }
        val text = codeEditor.text ?: return
        val source = text.toString()
        val importLine = "import $importPath"
        if (source.lineSequence().any { it.trim() == importLine }) {
            showToast("Already imported: $symbol")
            return
        }
        val insertAt = importInsertOffset(source)
        val prefix = when {
            insertAt == 0 -> ""
            source.getOrNull(insertAt - 1) == '\n' -> ""
            else -> "\n"
        }
        val suffix = if (source.getOrNull(insertAt) == '\n') "" else "\n"
        text.insert(insertAt, "$prefix$importLine$suffix")
        showToast("Imported $importPath")
    }

    private fun knownImportFor(symbol: String): String? = mapOf(
        "AlertDialog" to "androidx.appcompat.app.AlertDialog",
        "AppCompatActivity" to "androidx.appcompat.app.AppCompatActivity",
        "Intent" to "android.content.Intent",
        "Context" to "android.content.Context",
        "Bundle" to "android.os.Bundle",
        "View" to "android.view.View",
        "MotionEvent" to "android.view.MotionEvent",
        "KeyEvent" to "android.view.KeyEvent",
        "EditText" to "android.widget.EditText",
        "TextView" to "android.widget.TextView",
        "LinearLayout" to "android.widget.LinearLayout",
        "RecyclerView" to "androidx.recyclerview.widget.RecyclerView",
        "LinearLayoutManager" to "androidx.recyclerview.widget.LinearLayoutManager",
        "CoroutineScope" to "kotlinx.coroutines.CoroutineScope",
        "Dispatchers" to "kotlinx.coroutines.Dispatchers",
        "Job" to "kotlinx.coroutines.Job",
        "SupervisorJob" to "kotlinx.coroutines.SupervisorJob",
        "launch" to "kotlinx.coroutines.launch",
        "withContext" to "kotlinx.coroutines.withContext",
        "delay" to "kotlinx.coroutines.delay",
        "Composable" to "androidx.compose.runtime.Composable",
        "remember" to "androidx.compose.runtime.remember",
        "mutableStateOf" to "androidx.compose.runtime.mutableStateOf",
        "Modifier" to "androidx.compose.ui.Modifier",
        "Color" to "androidx.compose.ui.graphics.Color"
    )[symbol]

    private fun findProjectImportFor(symbol: String): String? {
        val target = findSymbolTargets(symbol).firstOrNull() ?: return null
        val text = runCatching { target.file.readText() }.getOrDefault("")
        val pkg = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]+)""").find(text)?.groupValues?.getOrNull(1)
            ?: return null
        return "$pkg.$symbol"
    }

    private fun importInsertOffset(source: String): Int {
        val lines = source.lines()
        var offset = 0
        var lastImportEnd = -1
        var packageEnd = -1
        lines.forEachIndexed { _, line ->
            val nextOffset = offset + line.length + 1
            when {
                line.trim().startsWith("package ") -> packageEnd = nextOffset
                line.trim().startsWith("import ") -> lastImportEnd = nextOffset
            }
            offset = nextOffset
        }
        return when {
            lastImportEnd >= 0 -> lastImportEnd
            packageEnd >= 0 -> packageEnd
            else -> 0
        }
    }

    private fun showParameterHint() {
        val source = codeEditor.text?.toString().orEmpty()
        val cursor = codeEditor.selectionStart.coerceIn(0, source.length)
        val call = functionCallBeforeCursor(source, cursor)
        if (call.isBlank()) {
            showToast("No function call before cursor")
            return
        }
        val params = parameterHintFor(call)
        AlertDialog.Builder(this)
            .setTitle("Parameter Hint: $call")
            .setMessage(params ?: "No parameter info found. Try opening declaration with Ctrl+tap.")
            .setPositiveButton("OK", null)
            .show()
    }

    internal fun formatCurrentFile() {
        val text = codeEditor.text?.toString() ?: return
        val ext = currentFile?.extension?.lowercase() ?: return

        val formatted = when (ext) {
            "kt" -> formatKotlin(text)
            "java" -> formatJava(text)
            "xml" -> formatXml(text)
            "json" -> formatJson(text)
            else -> {
                showToast("Auto-format not supported for .$ext files")
                return
            }
        }

        if (formatted != text) {
            codeEditor.setText(formatted)
            showToast("Formatted")
        } else {
            showToast("Already formatted")
        }
    }

    fun showGoToLineDialog() {
        val text = codeEditor.text ?: return
        val totalLines = text.lines().size
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "Line (1-$totalLines)"
            setPadding(32.dp(), 16.dp(), 32.dp(), 16.dp())
            textSize = 14f
        }
        AlertDialog.Builder(this)
            .setTitle("Go to Line")
            .setView(input)
            .setPositiveButton("Go") { _, _ ->
                val line = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                if (line in 1..totalLines) {
                    val layout = codeEditor.layout ?: return@setPositiveButton
                    val offset = layout.getLineStart((line - 1).coerceIn(0, layout.lineCount - 1))
                    codeEditor.setSelection(offset)
                    codeEditor.requestFocus()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private var isWordWrapEnabled = false

    fun toggleWordWrap() {
        isWordWrapEnabled = !isWordWrapEnabled
        codeEditor.setHorizontallyScrolling(!isWordWrapEnabled)
        val editorHost = findViewById<HorizontalScrollView>(R.id.editor_host)
        if (isWordWrapEnabled) {
            editorHost?.isHorizontalScrollBarEnabled = false
            showToast("Word Wrap: ON")
        } else {
            editorHost?.isHorizontalScrollBarEnabled = true
            showToast("Word Wrap: OFF")
        }
    }

    internal fun showVariantSelector() {
        variantSelector.showSelector()
    }

    internal fun toggleFullscreen() {
        val attrs = window.attributes
        val isFullscreen = (attrs.flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
        if (isFullscreen) {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            showToast("Fullscreen: OFF")
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
            showToast("Fullscreen: ON")
        }
    }

    fun showQuickBar() {
        val quickBar = findViewById<HorizontalScrollView>(R.id.quick_bar_scroll)
        if (quickBar?.visibility == View.VISIBLE) {
            StudioUi.animateFadeOut(quickBar)
        } else if (quickBar != null) {
            StudioUi.animateFadeIn(quickBar)
        }
    }

    private fun formatKotlin(source: String): String {
        var indent = 0
        val lines = source.lines()
        val result = StringBuilder()

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                result.appendLine()
                return@forEach
            }

            val closingChars = trimmed.firstOrNull() == '}' ||
                trimmed.startsWith(")") ||
                trimmed.startsWith("]") ||
                trimmed.startsWith("catch") ||
                trimmed.startsWith("finally") ||
                trimmed.startsWith("else") ||
                trimmed.startsWith(")") ||
                trimmed.startsWith("else")

            if (closingChars && indent > 0) {
                indent--
            }

            result.append("    ".repeat(indent))
            result.appendLine(trimmed)

            val opens = trimmed.count { it == '{' || it == '(' || it == '[' }
            val closes = trimmed.count { it == '}' || it == ')' || it == ']' }
            indent += opens - closes
            if (indent < 0) indent = 0
        }

        return result.toString().trimEnd()
    }

    private fun formatJava(source: String): String = formatKotlin(source)

    private fun formatXml(source: String): String {
        val lines = source.lines()
        val result = StringBuilder()
        var indent = 0

        lines.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            if (trimmed.startsWith("</") && indent > 0) indent--

            result.append("    ".repeat(indent))
            result.appendLine(trimmed)

            if (trimmed.startsWith("<") && !trimmed.startsWith("</") && !trimmed.endsWith("/>")) {
                indent++
            }
        }

        return result.toString().trimEnd()
    }

    private fun formatJson(source: String): String {
        val result = StringBuilder()
        var indent = 0
        var inString = false
        var escaped = false

        source.forEach { ch ->
            when {
                escaped -> {
                    result.append(ch)
                    escaped = false
                }
                ch == '\\' && inString -> {
                    result.append(ch)
                    escaped = true
                }
                ch == '"' -> {
                    result.append(ch)
                    inString = !inString
                }
                !inString -> when (ch) {
                    '{', '[' -> {
                        result.appendLine(ch)
                        indent++
                        result.append("  ".repeat(indent))
                    }
                    '}', ']' -> {
                        result.appendLine()
                        indent--
                        result.append("  ".repeat(indent))
                        result.append(ch)
                    }
                    ',' -> result.appendLine(ch)
                    ':' -> result.append(": ")
                    else -> result.append(ch)
                }
                else -> result.append(ch)
            }
        }

        return result.toString().trimEnd()
    }

    private fun functionCallBeforeCursor(source: String, cursor: Int): String {
        val left = source.substring(0, cursor)
        val open = left.lastIndexOf('(')
        if (open < 1) return ""
        var end = open - 1
        while (end >= 0 && source[end].isWhitespace()) end--
        var start = end
        while (start >= 0 && (source[start].isLetterOrDigit() || source[start] == '_' || source[start] == '.')) start--
        return source.substring(start + 1, end + 1).substringAfterLast('.')
    }

    private fun parameterHintFor(functionName: String): String? {
        val common = mapOf(
            "println" to "println(message: Any?)",
            "print" to "print(message: Any?)",
            "findViewById" to "findViewById<T>(id: Int): T",
            "setOnClickListener" to "setOnClickListener(listener: (View) -> Unit)",
            "remember" to "remember(vararg keys, calculation: () -> T)",
            "mutableStateOf" to "mutableStateOf(value: T)",
            "LaunchedEffect" to "LaunchedEffect(vararg keys, block: suspend CoroutineScope.() -> Unit)",
            "launch" to "launch(context, start, block)",
            "withContext" to "withContext(context, block)",
            "AlertDialog.Builder" to "Builder(context: Context)"
        )
        common[functionName]?.let { return it }
        val declaration = findSymbolTargets(functionName).firstOrNull() ?: return null
        val line = declaration.preview
        Regex("""\bfun\s+$functionName\s*\(([^)]*)\)""").find(line)?.let {
            return "$functionName(${it.groupValues[1].ifBlank { "no parameters" }})"
        }
        Regex("""\b$functionName\s*\(([^)]*)\)""").find(line)?.let {
            return "$functionName(${it.groupValues[1].ifBlank { "no parameters" }})"
        }
        return line.ifBlank { null }
    }

    private fun previewRenameSymbol(oldName: String, newName: String) {
        if (!newName.matches(Regex("""[A-Za-z_]\w*"""))) {
            showToast("Invalid symbol name")
            return
        }
        if (oldName == newName) return
        val changes = collectRenameChanges(oldName, newName)
        if (changes.isEmpty()) {
            showToast("No usages found")
            return
        }
        val total = changes.values.sum()
        val root = currentProjectDir
        val preview = changes.entries
            .sortedBy { it.key.absolutePath }
            .take(30)
            .joinToString("\n") { (file, count) -> "${file.relativePathFor(root)}  $count occurrence(s)" }
        AlertDialog.Builder(this)
            .setTitle("Rename '$oldName' → '$newName'")
            .setMessage("$total occurrence(s) in ${changes.size} file(s).\n\n$preview")
            .setPositiveButton("Apply") { _, _ -> applyRenameSymbol(oldName, newName, changes.keys.toList()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun collectRenameChanges(oldName: String, newName: String): Map<File, Int> {
        val regex = Regex("""\b${Regex.escape(oldName)}\b""")
        return symbolSearchFiles().mapNotNull { file ->
            val text = runCatching { file.readText() }.getOrDefault("")
            val count = regex.findAll(text).count()
            if (count > 0) file to count else null
        }.toMap()
    }

    private fun applyRenameSymbol(oldName: String, newName: String, files: List<File>) {
        saveCurrentFile()
        val regex = Regex("""\b${Regex.escape(oldName)}\b""")
        var changed = 0
        files.forEach { file ->
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            val updated = regex.replace(text, newName)
            if (updated != text) {
                file.writeText(updated)
                changed++
            }
        }
        currentFile?.takeIf { it.exists() }?.let { file ->
            if (files.any { canonicalPath(it) == canonicalPath(file) }) {
                suppressEditorChange = true
                val content = file.readText()
                val oldCursor = codeEditor.selectionStart.coerceIn(0, content.length)
                codeEditor.clearHistory()
                codeEditor.setText(content)
                codeEditor.setSelection(oldCursor)
                suppressEditorChange = false
                unsavedFiles.remove(file.absolutePath)
                unsavedChanges = false
                updateTabTitle()
                rescheduleLiveDiagnostics()
            }
        }
        if (!::fileAdapter.isInitialized) return
        fileAdapter.notifyDataSetChanged()
        showToast("Renamed in $changed file(s)")
    }

    private fun openSymbolTarget(target: SymbolTarget) {
        if (target.file.exists() && target.file != currentFile) {
            openFile(target.file)
        }
        codeEditor.post {
            navigateToLine(target.line)
            val text = codeEditor.text?.toString().orEmpty()
            val lineStart = text.lineStartOffset(target.line)
            val offset = (lineStart + (target.column - 1).coerceAtLeast(0)).coerceIn(0, text.length)
            codeEditor.setSelection(offset)
        }
    }

    private fun File.relativePathFor(root: File?): String =
        root?.let { runCatching { relativeTo(it).path }.getOrDefault(name) } ?: name

    private fun String.lineStartOffset(line: Int): Int {
        if (line <= 1) return 0
        var currentLine = 1
        for (i in indices) {
            if (this[i] == '\n') {
                currentLine++
                if (currentLine == line) return i + 1
            }
        }
        return length
    }

    private fun symbolSearchFiles(): List<File> {
        val root = currentProjectDir ?: return emptyList()
        if (cachedSymbolFiles != null && cachedSymbolFilesProject == root) {
            return cachedSymbolFiles!!
        }
        val files = root.walkTopDown()
            .onEnter { dir -> dir.name !in setOf("build", ".gradle", ".git", ".cxx") }
            .filter { it.isFile && it.extension.lowercase() in setOf("kt", "kts", "java") && it.length() <= 2_000_000L }
            .take(500)
            .toList()
        cachedSymbolFiles = files
        cachedSymbolFilesProject = root
        return files
    }

    private fun invalidateSymbolFileCache() {
        cachedSymbolFiles = null
        cachedSymbolFilesProject = null
    }

    private fun findSymbolUsages(symbol: String): List<SymbolTarget> {
        val regex = Regex("""\b${Regex.escape(symbol)}\b""")
        return symbolSearchFiles().flatMap { file ->
            val text = runCatching { file.readText() }.getOrDefault("")
            if (text.isBlank()) return@flatMap emptyList()
            val lines = text.lines()
            regex.findAll(text).map { match ->
                val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                val lineStart = text.lastIndexOf('\n', match.range.first).let { if (it < 0) 0 else it + 1 }
                SymbolTarget(
                    file = file,
                    line = line,
                    column = match.range.first - lineStart + 1,
                    preview = lines.getOrNull(line - 1)?.trim().orEmpty()
                )
            }.toList()
        }.distinctBy { "${it.file.absolutePath}:${it.line}:${it.column}" }
            .sortedWith(compareBy({ it.file != currentFile }, { it.file.name }, { it.line }, { it.column }))
            .take(200)
    }

    private fun handleAutoPair(s: Editable?) {
        val editable = s ?: return
        if (!getSharedPreferences("ide_options", MODE_PRIVATE).getBoolean("auto_pair", true)) return
        if (editable.length != editorBeforeChange.length + 1) return
        val cursor = codeEditor.selectionStart
        if (cursor !in 1..editable.length) return
        val inserted = editable[cursor - 1]
        val closing = editorPairs[inserted] ?: return
        if (cursor < editable.length && editable[cursor] == closing) return
        codeEditor.autoPairEdit = true
        editable.insert(cursor, closing.toString())
        codeEditor.setSelection(cursor + 1) // cursor between brackets/quotes
        codeEditor.autoPairEdit = false
    }

    private fun updateBracketMatch() {
        if (!getSharedPreferences("ide_options", MODE_PRIVATE).getBoolean("bracket_match", true)) {
            codeEditor.setBracketMatch(-1, -1)
            return
        }
        val source = codeEditor.text?.toString().orEmpty()
        if (source.isEmpty()) {
            codeEditor.setBracketMatch(-1, -1)
            return
        }
        val cursor = codeEditor.selectionStart.coerceIn(0, source.length)
        val index = when {
            cursor > 0 && source[cursor - 1] in "()[]{}\"" -> cursor - 1
            cursor < source.length && source[cursor] in "()[]{}\"" -> cursor
            else -> -1
        }
        if (index < 0) {
            codeEditor.setBracketMatch(-1, -1)
            return
        }
        val match = findMatchingBracket(source, index)
        codeEditor.setBracketMatch(index, match)
    }

    private fun findMatchingBracket(source: String, index: Int): Int {
        val openToClose = mapOf('(' to ')', '[' to ']', '{' to '}')
        val closeToOpen = openToClose.entries.associate { it.value to it.key }
        val char = source[index]
        if (char == '"') {
            val left = source.lastIndexOf('"', index - 1)
            val right = source.indexOf('"', index + 1)
            return if (right >= 0) right else left
        }
        openToClose[char]?.let { closing ->
            var depth = 0
            for (i in index until source.length) {
                when (source[i]) {
                    char -> depth++
                    closing -> if (--depth == 0) return i
                }
            }
        }
        closeToOpen[char]?.let { opening ->
            var depth = 0
            for (i in index downTo 0) {
                when (source[i]) {
                    char -> depth++
                    opening -> if (--depth == 0) return i
                }
            }
        }
        return -1
    }

    private fun handleSmartIndent(): Boolean {
        val text = codeEditor.text ?: return false
        val cursor = codeEditor.selectionStart
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val currentLine = text.substring(lineStart, cursor)
        val indent = currentLine.takeWhile { it == ' ' || it == '\t' }
        val trimmed = currentLine.trim()
        val extraIndent = if (trimmed.endsWith("{") || trimmed.endsWith("(") ||
            trimmed.endsWith(":")
        ) "    " else ""

        // Case 1: cursor between { and } ??Android Studio style
        // Insert newline with indent, cursor between braces
        if (trimmed.endsWith("{") && cursor < text.length && text[cursor] == '}') {
            val insertion = "\n$indent$extraIndent\n$indent"
            text.insert(cursor, insertion)
            codeEditor.setSelection(cursor + ("\n$indent$extraIndent").length)
            return true
        }

        // Case 2: Line ends with { and next char is NOT }
        // (auto-pair already inserted }, or no auto-pair)
        // Insert newline with extra indent, and closing } on next line
        val insertion = "\n$indent$extraIndent"
        text.insert(cursor, insertion)
        codeEditor.setSelection(cursor + insertion.length)

        // Auto-close brace ??but only if next char is NOT already }
        if (trimmed.endsWith("{")) {
            val closePos = cursor + insertion.length
            val nextChar = if (closePos < text.length) text[closePos] else '\u0000'
            if (nextChar != '}') {
                text.insert(closePos, "\n$indent}")
            }
        }
        return true
    }

    private fun handleSmartBackspace(): Boolean {
        val text = codeEditor.text ?: return false
        val cursor = codeEditor.selectionStart
        val selEnd = codeEditor.selectionEnd
        if (cursor != selEnd) return false
        if (cursor <= 0) return false
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val col = cursor - lineStart
        // [FIX] Delete ALL leading whitespace (not just 4 spaces)
        if (col > 0) {
            val lineContent = text.substring(lineStart, cursor)
            val leadingSpaces = lineContent.takeWhile { it == ' ' || it == '\t' }
            if (leadingSpaces.isNotEmpty() && col <= leadingSpaces.length) {
                text.delete(lineStart, cursor)
                codeEditor.setSelection(lineStart)
                return true
            }
            // If cursor is in the middle of leading whitespace, delete to start of indent
            if (col <= leadingSpaces.length && col > 0) {
                text.delete(cursor - col, cursor)
                codeEditor.setSelection(cursor - col)
                return true
            }
        }
        // Fallback: delete 4 spaces at once
        if (col >= 4 && text.substring(cursor - 4, cursor) == "    ") {
            text.delete(cursor - 4, cursor)
            codeEditor.setSelection(cursor - 4)
            return true
        }
        return false
    }

    // MobileStudio comment

    internal fun duplicateCurrentLine() {
        val text = codeEditor.text ?: return
        val cursor = codeEditor.selectionStart
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val lineEnd = text.indexOf('\n', cursor).takeIf { it >= 0 } ?: text.length
        val line = text.substring(lineStart, lineEnd)
        text.insert(lineEnd, "\n$line")
        codeEditor.setSelection(cursor + line.length + 1)
    }

    internal fun toggleComment() {
        val text = codeEditor.text ?: return
        val cursor = codeEditor.selectionStart
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val lineEnd = text.indexOf('\n', cursor).takeIf { it >= 0 } ?: text.length
        val line = text.substring(lineStart, lineEnd)
        val trimmed = line.trimStart()
        val prefix = commentPrefixFor(currentFile)
        if (trimmed.startsWith(prefix)) {
            // Remove comment
            val indentLen = line.length - trimmed.length
            val indent = line.substring(0, indentLen)
            val uncommented = trimmed.removePrefix(prefix).removePrefix(" ")
            text.replace(lineStart, lineEnd, indent + uncommented)
        } else {
            // Add comment
            text.insert(lineStart, prefix)
        }
    }

    private fun deleteWordBackward() {
        val text = codeEditor.text ?: return
        val cursor = codeEditor.selectionStart
        if (cursor <= 0) return
        var pos = cursor - 1
        // Skip whitespace
        while (pos > 0 && text[pos].isWhitespace()) pos--
        // Skip word characters
        while (pos > 0 && !text[pos - 1].isWhitespace() && text[pos] != '\n') pos--
        text.delete(pos, cursor)
        codeEditor.setSelection(pos)
    }

    private fun moveLineUp() {
        val text = codeEditor.text ?: return
        val cursor = codeEditor.selectionStart
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val lineEnd = text.indexOf('\n', cursor).takeIf { it >= 0 } ?: text.length
        if (lineStart <= 0) return
        val prevLineStart = text.lastIndexOf('\n', lineStart - 2) + 1
        val line = text.substring(lineStart, lineEnd)
        val prevLine = text.substring(prevLineStart, lineStart)
        text.replace(prevLineStart, lineEnd, line + "\n" + prevLine.trimEnd())
        codeEditor.setSelection((cursor - prevLine.length - 1).coerceAtLeast(0))
    }

    private fun moveLineDown() {
        val text = codeEditor.text ?: return
        val cursor = codeEditor.selectionStart
        val lineStart = text.lastIndexOf('\n', cursor - 1) + 1
        val lineEnd = text.indexOf('\n', cursor).takeIf { it >= 0 } ?: text.length
        if (lineEnd >= text.length) return
        val nextLineEnd = text.indexOf('\n', lineEnd + 1).takeIf { it >= 0 } ?: text.length
        val line = text.substring(lineStart, lineEnd)
        val nextLine = text.substring(lineEnd + 1, nextLineEnd)
        text.replace(lineStart, nextLineEnd, nextLine + "\n" + line)
        codeEditor.setSelection((cursor + nextLine.length + 1).coerceAtMost(text.length))
    }

    fun loadFileContentForTab(tabName: String) {
        val f = currentProjectDir?.let { File(it, tabName) } ?: File(filesDir, tabName)
        openFile(f)
    }

    fun openFile(file: File) {
        saveCurrentFile()
        // The split preview only makes sense for XML layouts: close it when the
        // newly opened file cannot be previewed so the code keeps the full width.
        if (!file.isPreviewableLayout()) {
            findViewById<View>(R.id.preview_pane)?.visibility = View.GONE
        }
        val fileKind = MediaDetector.detect(file)
        if (MediaDetector.isMedia(fileKind)) {
            MediaViewerActivity.start(this, file)
            return
        }
        if (fileKind == MediaDetector.Kind.APK) {
            showApkActionsDialog(file)
            return
        }
        if (file.extension.equals("exe", ignoreCase = true)) {
            promptRunExe(file)
            return
        }
        if (isBlockedEditorFile(file)) {
            setStatus("Binary file cannot be opened: ${file.name}")
            showToast("Binary file cannot be opened")
            return
        }
        // Save scroll position of previous file
        val previousNavPath = currentFile?.absolutePath
        currentFile?.let {
            if (::codeEditor.isInitialized) {
                tabScrollPositions[it.absolutePath] = (codeEditor.parent as? ScrollView)?.scrollY ?: 0
                tabCursorPositions[it.absolutePath] = codeEditor.selectionStart
            }
        }

        if (!openTabs.contains(file)) openTabs.add(file)
        currentFile = file
        // Keep the Compose shell's tab strip in sync (it reads `tabs`, not
        // the legacy View-based `openTabs`). Without this the editor stays on
        // the "No file selected" welcome screen even though a file is open.
        val tabLabel = currentProjectDir?.let { runCatching { file.relativeTo(it).path }.getOrNull() }
            ?: file.name
        if (!tabs.contains(tabLabel)) tabs.add(tabLabel)
        selectedTab = tabs.indexOf(tabLabel).coerceAtLeast(0)
        recordFileNavigation(previousNavPath, file.absolutePath)
        unsavedChanges = false
        StorageHelper.pushRecentFile(this, file.absolutePath)
        scope.launch(Dispatchers.IO) {
            val content = try {
                val bytes = file.readBytes()
                detectFileEncoding(bytes)?.let { currentFileEncoding = it }
                val charset = Charset.forName(currentFileEncoding)
                if (bytes.size >= 2) {
                    when {
                        currentFileEncoding == "UTF-16LE" && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                            String(bytes, 2, bytes.size - 2, charset)
                        currentFileEncoding == "UTF-16BE" && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                            String(bytes, 2, bytes.size - 2, charset)
                        else -> String(bytes, charset).removePrefix("\uFEFF")
                    }
                } else String(bytes, charset)
            } catch (e: Exception) {
                "// Error: ${e.message}"
            }
            val useVirtual = content.length > 100000
            val window = if (useVirtual) {
                virtualLines = content.lines().toMutableList()
                virtualWindowStart = 0
                buildVirtualWindow(0)
            } else null
            withContext(Dispatchers.Main) {
                // The Compose shell renders from these two state holders — they
                // MUST be updated on every file open or the editor stays blank.
                fileContent = content
                pathText = file.absolutePath
                EditorPaneState.content = content
                EditorPaneState.unsaved = false

                if (::codeEditor.isInitialized) {
                    suppressEditorChange = true
                    codeEditor.clearHistory()
                    if (useVirtual && window != null) {
                        isVirtualScroll = true
                        codeEditor.setText(window)
                    } else {
                        virtualLines = null
                        isVirtualScroll = false
                        codeEditor.setText(content)
                    }
                    suppressEditorChange = false

                    val savedCursor = tabCursorPositions[file.absolutePath] ?: 0
                    val totalLen = if (isVirtualScroll) codeEditor.text?.length else content.length
                    codeEditor.setSelection(savedCursor.coerceIn(0, totalLen))
                    codeEditor.post {
                        updateVirtualScrollListener()
                        if (isVirtualScroll) updateLiveDiagnosticsNow()
                    }
                    codeEditor.post {
                        updateLiveDiagnosticsNow()
                        maybeOfferLanguageConversion()
                    }
                }

                if (::autoComplete.isInitialized) {
                    currentProjectDir?.let { autoComplete.setProjectDir(it) }
                    autoComplete.setCurrentFile(file)
                }
                if (::webAutoComplete.isInitialized) webAutoComplete.setCurrentFile(file)

                currentProjectDir?.let { projDir ->
                    analysisEngine?.openFile(projDir, file)
                }

                showEditor()
                refreshTabs()
                updateStatusBar()
                updatePreviewButtonVisibility()
                updateBreadcrumb()
                setStatus("${file.name}  ${formatFileSize(file.length())}")
                if (::drawerLayout.isInitialized) drawerLayout.closeDrawer(GravityCompat.START)
                maybeShowExtensionBanner(file)
                ExtensionHost.dispatchFileOpenForAll(file)
            }
        }
    }

    /**
     * Extension notice banner (Phase 1 API): shown at the top of the editor when a
     * Shell Script file (.sh or shebang-detected extension-less) is opened and the
     * Shell Script Extension is not installed/enabled. Small, non-blocking, closed
     * banners are not shown again for the same file while it stays open.
     */
    private fun maybeShowExtensionBanner(file: File) {
        val banner = findViewById<LinearLayout>(R.id.extension_banner) ?: return
        banner.removeAllViews()
        banner.visibility = View.GONE

        // Fast path: extension known and installed -> no banner (minimal checks only)
        val manager = ExtensionManager.get(this)
        val isShellFile = file.extension.equals("sh", true) || file.extension.equals("bash", true) ||
            (file.extension.isEmpty() && file.name.contains('.') == false && hasShebang(file))
        if (!isShellFile) return
        if (manager.isInstalled("com.mobilestudio.shell") && manager.isEnabled("com.mobilestudio.shell")) return
        if (manager.isBannerClosed("com.mobilestudio.shell", file.absolutePath)) return

        val ko = AppLanguage.isKorean(this)
        banner.apply {
            setBackgroundColor(StudioUi.palette.card)
            setPadding(dp(10), dp(6), dp(8), dp(6))
            addView(TextView(this@MainActivity).apply {
                text = if (ko) "Shell Script Extension을 다운로드할 수 있습니다." else "You can download Shell Script Extension."
                textSize = 11f
                setTextColor(StudioUi.palette.textSoft)
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@MainActivity).apply {
                text = if (ko) "[다운로드]" else "[Click Here to download]"
                textSize = 11f
                setTextColor(StudioUi.palette.accent)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                background = StudioUi.ripple(this@MainActivity, StudioUi.roundedInt(this@MainActivity, fill = StudioUi.palette.surface2, radiusDp = 4))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    val i = android.content.Intent(this@MainActivity, com.aide.mobilestudio.extension.ExtensionManagerActivity::class.java)
                    i.putExtra("extension_id", "com.mobilestudio.shell")
                    startActivity(i)
                }
            })
            addView(TextView(this@MainActivity).apply {
                text = if (ko) "[닫기]" else "[Close]"
                textSize = 11f
                setTextColor(StudioUi.palette.textSoft)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                background = StudioUi.ripple(this@MainActivity, StudioUi.roundedInt(this@MainActivity, fill = StudioUi.palette.surface2, radiusDp = 4))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    manager.markBannerClosed("com.mobilestudio.shell", file.absolutePath)
                    banner.visibility = View.GONE
                }
            })
        }
        banner.visibility = View.VISIBLE
    }

    /** `file(1)`-style content sniff: shebang means a shell script even without an extension. */
    private fun hasShebang(file: File): Boolean = try {
        file.inputStream().use { input ->
            val buf = ByteArray(64)
            val n = input.read(buf)
            val head = String(buf, 0, if (n > 0) n else 0)
            head.trimStart().startsWith("#!")
        }
    } catch (_: Exception) {
        false
    }

    /** [[[ERROR]]] dialog for a crashed extension: disable / ignore / view log. */
    private fun showExtensionCrashDialog(name: String, version: String, error: Throwable) {
        val ko = AppLanguage.isKorean(this)
        AlertDialog.Builder(this)
            .setTitle("[[[ERROR]]] " + (if (ko) "확장 오류" else "Extension Error"))
            .setMessage(
                (if (ko) "[오류가 발생한 확장 : " else "[Crashed extension: ") + "$name V$version]\n\n" +
                    "Error:\n${error.javaClass.simpleName}: ${error.message ?: "(no message)"}"
            )
            .setPositiveButton(if (ko) "이 확장 사용하지 않기" else "Disable Extension") { _, _ ->
                ExtensionManager.get(this).setEnabled("com.mobilestudio.shell", false)
                showToast(if (ko) "확장 비활성화됨" else "Extension disabled")
            }
            .setNegativeButton(if (ko) "무시" else "Ignore", null)
            .setNeutralButton(if (ko) "오류 로그" else "View Log") { _, _ ->
                startActivity(android.content.Intent(this, com.aide.mobilestudio.extension.ExtensionManagerActivity::class.java))
            }
            .show()
    }

    /**
     * .exe 탭 → Wine/Box64 실행 확인 → (미설치면 자동 설치) → 실행.
     */
    private fun promptRunExe(file: File) {
        val ko = AppLanguage.isKorean(this)
        val env = WindowsExeEnvironment(this)
        val needsInstall = !env.isInstalled
        val needsPrefix = env.isInstalled && !env.isPrefixBooted

        val message = when {
            needsInstall -> if (ko)
                "Wine과 Box64로 이 .exe 파일을 실행하시겠습니까?\n\n" +
                    "최초 실행 시 런타임을 다운로드합니다 (약 120MB, Wi-Fi 권장).\n" +
                    "설치 후 Wine prefix 초기화에 몇 분이 소요될 수 있습니다."
            else
                "Run this .exe with Wine & Box64?\n\n" +
                    "First run downloads the runtime (~120 MB, Wi-Fi recommended).\n" +
                    "Wine prefix initialisation may take a few minutes."
            needsPrefix -> if (ko)
                "Wine과 Box64로 이 .exe 파일을 실행하시겠습니까?\n\n" +
                    "Wine prefix 초기화가 필요합니다 (몇 분 소요)."
            else
                "Run this .exe with Wine & Box64?\n\n" +
                    "Wine prefix needs to be initialised (may take a few minutes)."
            else -> if (ko)
                "Wine과 Box64로 .exe 파일을 실행하시겠습니까?"
            else
                "Run this .exe with Wine & Box64?"
        }

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(message)
            .setPositiveButton(if (ko) "▶ 실행" else "▶ Run") { _, _ ->
                if (needsInstall) installThenRun(file)
                else runExeWithBox64(file)
            }
            .setNegativeButton(if (ko) "취소" else "Cancel", null)
            .setNeutralButton(if (ko) "상세 옵션" else "More options") { _, _ ->
                showExeActionsDialog(file)
            }
            .show()
    }

    /**
     * Wine/Box64 환경이 없으면 설치하고, prefix를 부팅한 뒤 .exe를 실행한다.
     * 진행 상황은 빌드 패널에 스트리밍된다.
     */
    private fun installThenRun(file: File) {
        val ko = AppLanguage.isKorean(this)
        showBottomPanel()
        switchToPanel("build")
        buildOutput.clear()
        buildOutput.appendLine(
            if (ko) "═══ Wine & Box64 설치 + 실행 ═══"
            else "═══ Wine & Box64 install + run ═══",
            LineLevel.INFO,
        )
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(if (ko) "Wine & Box64 설치 중…" else "Installing Wine & Box64…")
            .setMessage(if (ko) "준비 중…" else "Preparing…")
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch(Dispatchers.IO) {
            try {
                val env = WindowsExeEnvironment(this@MainActivity)
                if (!env.isInstalled) {
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage(if (ko) "런타임 다운로드 중…" else "Downloading runtime…")
                    }
                    env.install { msg ->
                        runOnUiThread {
                            buildOutput.appendLine(msg, LineLevel.INFO)
                            progressDialog.setMessage(msg)
                        }
                    }
                }
                if (!env.isPrefixBooted) {
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage(
                            if (ko) "Wine prefix 초기화 중 (몇 분 소요)…"
                            else "Bootstrapping Wine prefix (may take minutes)…",
                        )
                        buildOutput.appendLine(
                            if (ko) "> Wine prefix 초기화 시작…"
                            else "> Bootstrapping Wine prefix…",
                            LineLevel.INFO,
                        )
                    }
                    env.bootstrapPrefix { msg ->
                        runOnUiThread { buildOutput.appendLine(msg, LineLevel.INFO) }
                    }
                }
                // Launch X server for GUI support (XServer XSDL)
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                }
                env.ensureXServer { msg ->
                    runOnUiThread { buildOutput.appendLine("> $msg", LineLevel.INFO) }
                }
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    buildOutput.appendLine(
                        if (ko) "> 설치 완료 — ${file.name} 실행 중…"
                        else "> Install complete — running ${file.name}…",
                        LineLevel.INFO,
                    )
                }
                val code = env.runWindowsExe(file) { line ->
                    runOnUiThread { buildOutput.appendLine(line, LineLevel.INFO) }
                }
                withContext(Dispatchers.Main) {
                    buildOutput.appendLine("> exit=$code", LineLevel.INFO)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressDialog.dismiss()
                    buildOutput.appendLine(
                        if (ko) "> 설치/실행 오류: ${e.message}" else "> Install/run error: ${e.message}",
                        LineLevel.ERROR,
                    )
                }
            }
        }
    }

    private fun showExeActionsDialog(file: File) {
        val ko = AppLanguage.isKorean(this)
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage("${formatFileSize(file.length())} \u00B7 Windows .exe")
            .setItems(
                arrayOf(
                    if (ko) "box64로 빌드 & 실행" else "Build & Run (box64)",
                    if (ko) "빌드만 (clang + mingw)" else "Build only (clang + mingw)",
                    if (ko) "box64로 실행" else "Run with box64",
                    if (ko) "Wine/Box64 설치 화면" else "Wine/Box64 manager",
                    if (ko) "경로 복사" else "Copy Path",
                    if (ko) "체인 테스트 (linker64 → box64)" else "Chain test (linker64 → box64)"
                )
            ) { _, which ->
                when (which) {
                    0 -> {
                        runWindowsExeBuild(file.parentFile)
                        runOnUiThread { runExeWithBox64(file) }
                    }
                    1 -> runWindowsExeBuild(file.parentFile)
                    2 -> runExeWithBox64(file)
                    3 -> Win64ManagerActivity.start(this)
                    4 -> copyPathToClipboard(file)
                    5 -> runChainTestUi()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runExeWithBox64(exeFile: File) {
        showBottomPanel()
        switchToPanel("build")
        buildOutput.appendLine("> Running ${exeFile.name} (box64 + wine64)...", LineLevel.INFO)
        scope.launch(Dispatchers.IO) {
            try {
                val env = WindowsExeEnvironment(this@MainActivity)
                if (!env.isInstalled) {
                    withContext(Dispatchers.Main) {
                        buildOutput.appendLine("> Wine/box64 environment not installed (Build Tools > Run .exe).", LineLevel.ERROR)
                    }
                    return@launch
                }
                if (!env.isPrefixBooted) {
                    withContext(Dispatchers.Main) {
                        buildOutput.appendLine("> Bootstrapping Wine prefix (first run, may take minutes)...", LineLevel.INFO)
                    }
                    env.bootstrapPrefix { msg ->
                        runOnUiThread { buildOutput.appendLine(msg, LineLevel.INFO) }
                    }
                }
                val code = env.runWindowsExe(exeFile) { line ->
                    runOnUiThread { buildOutput.appendLine(line, LineLevel.INFO) }
                }
                withContext(Dispatchers.Main) {
                    buildOutput.appendLine("> exit=$code", LineLevel.INFO)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    buildOutput.appendLine("> Run error: ${e.message}", LineLevel.ERROR)
                }
            }
        }
    }

    private fun runChainTestUi() {
        showBottomPanel()
        switchToPanel("build")
        buildOutput.clear()
        buildOutput.appendLine("> WinEXE chain test (linker64 → box64 → wine64 → exe)", LineLevel.INFO)
        scope.launch(Dispatchers.IO) {
            try {
                WindowsExeEnvironment(this@MainActivity).runChainTest { msg ->
                    runOnUiThread { buildOutput.appendLine(msg, LineLevel.INFO) }
                }
                withContext(Dispatchers.Main) {
                    setStatus("Chain test complete")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    buildOutput.appendLine("> Chain test error: ${e.message}", LineLevel.ERROR)
                    setStatus("Chain test failed")
                }
            }
        }
    }

    private fun showApkActionsDialog(file: File) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage("${formatFileSize(file.length())} \u00B7 APK")
            .setItems(arrayOf("Install APK", "APK Info", "Extract (unzip)")) { _, which ->
                when (which) {
                    0 -> installApkFile(file)
                    1 -> showApkInfo(file)
                    2 -> extractApkZip(file)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installApkFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            startActivity(Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } catch (e2: Exception) {
                ErrorLogDialog.show(
                    this,
                    if (AppLanguage.isKorean(this)) "설치 실패" else "Install Failed",
                    "Install failed: ${e2.message}"
                )
            }
        }
    }

    private fun showApkInfo(file: File) {
        val sb = StringBuilder()
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val manifest = zip.entries().asSequence().firstOrNull { it.name.endsWith("AndroidManifest.xml") && !it.isDirectory }
                sb.appendLine("Path: ${file.absolutePath}")
                sb.appendLine("Size: ${formatFileSize(file.length())}")
            }
        }
        sb.appendLine("Entries: ")
        runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                val count = zip.entries().asSequence().count()
                sb.appendLine(count.toString())
            }
        }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(sb.toString())
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(file.name, sb.toString()))
                showToast("Copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun extractApkZip(file: File) {
        Thread {
            val dest = File(file.parentFile, file.nameWithoutExtension + "_extracted")
            dest.mkdirs()
            var count = 0
            var failed: String? = null
            try {
                java.util.zip.ZipFile(file).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        if ('/' in entry.name) continue
                        val target = File(dest, entry.name)
                        target.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            target.outputStream().use { output -> input.copyTo(output) }
                        }
                        count++
                    }
                }
            } catch (e: Exception) {
                failed = e.message
            }
            val error = failed
            val extracted = count
            runOnUiThread {
                if (error != null) {
                    showToast("Extract failed: $error")
                } else {
                    showToast("Extracted $extracted top-level files to ${dest.name}")
                    val refreshRoot = if (currentProjectDir?.isDirectory == true) currentProjectDir!!
                        else file.parentFile ?: dest
                    loadFileTree(refreshRoot)
                }
            }
        }.start()
    }

    private fun detectFileEncoding(bytes: ByteArray): String? {
        return when {
            bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> "UTF-8"
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
            else -> null
        }
    }

    private fun showEncodingDialog() {
        val encodings = listOf("UTF-8", "UTF-16LE", "UTF-16BE", "EUC-KR", "windows-1252", "ISO-8859-1", "Shift-JIS", "GB2312", "Big5")
        AlertDialog.Builder(this)
            .setTitle("File Encoding: $currentFileEncoding")
            .setItems(encodings.toTypedArray()) { _, which -> applyFileEncoding(encodings[which]) }
            .show()
    }

    private fun applyFileEncoding(encoding: String) {
        val file = currentFile ?: return
        scope.launch(Dispatchers.IO) {
            val content = try {
                val bytes = file.readBytes()
                val charset = Charset.forName(encoding)
                if (bytes.size >= 2) {
                    when {
                        encoding == "UTF-16LE" && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                            String(bytes, 2, bytes.size - 2, charset)
                        encoding == "UTF-16BE" && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
                            String(bytes, 2, bytes.size - 2, charset)
                        else -> String(bytes, charset).removePrefix("\uFEFF")
                    }
                } else String(bytes, charset)
            } catch (e: Exception) {
                "// Cannot decode with $encoding"
            }
            withContext(Dispatchers.Main) {
                currentFileEncoding = encoding
                suppressEditorChange = true
                codeEditor.clearHistory()
                virtualLines = null
                isVirtualScroll = false
                codeEditor.setText(content)
                suppressEditorChange = false
                updateStatusBar()
                setStatus("Encoding changed to $encoding")
                rescheduleLiveDiagnostics()
            }
        }
    }

    internal fun saveCurrentFile() {
        val file = currentFile ?: return
        if (!unsavedChanges) return
        if (!::codeEditor.isInitialized) return
        try {
            val contentToSave = if (isVirtualScroll && virtualLines != null) {
                mergeVirtualEdits()
                virtualLines!!.joinToString("\n")
            } else {
                codeEditor.text.toString()
            }
            scope.launch(Dispatchers.IO) {
                val charset = Charset.forName(currentFileEncoding)
                val bytes = if (currentFileEncoding.startsWith("UTF-16")) {
                    val bom = when (currentFileEncoding) {
                        "UTF-16LE" -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
                        "UTF-16BE" -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
                        else -> byteArrayOf()
                    }
                    bom + contentToSave.toByteArray(charset)
                } else {
                    contentToSave.toByteArray(charset)
                }
                val tmpFile = File(file.parent, "${file.name}.tmp")
                try {
                    tmpFile.writeBytes(bytes)
                    if (!tmpFile.renameTo(file)) {
                        file.writeBytes(bytes)
                        tmpFile.delete()
                    }
                    finishSaveUi(file)
                } catch (e: Exception) {
                    tmpFile.delete()
                    withContext(Dispatchers.Main) { setStatus("Save failed: ${e.message}") }
                }
            }
        } catch (e: Exception) {
            setStatus("Save failed: ${e.message}")
        }
    }

    private suspend fun finishSaveUi(file: File) {
        withContext(Dispatchers.Main) {
            unsavedChanges = false
            unsavedFiles.remove(file.absolutePath)
            updateTabTitle()
            setStatus("Saved: ${file.name}")
            // Extension lifecycle: onFileSave (line counters, formatters, linters).
            runCatching { ExtensionHost.dispatchFileSaveForAll(file) }
            if (isGradleSyncFile(file)) {
                markGradleSyncNeeded(file)
            }
            if (!isAppDataExplorerMode()) {
                buildScheduler.schedule(setOf(file))
            }
            maybeOfferLanguageConversion()
        }
    }

    private fun buildVirtualWindow(windowStart: Int): String {
        val lines = virtualLines ?: return ""
        val total = lines.size
        if (total <= VIRTUAL_BUFFER * 2 + 1) return lines.joinToString("\n")
        val start = windowStart.coerceIn(0, total - 1)
        val end = (start + VIRTUAL_BUFFER * 2 + 1).coerceAtMost(total)
        val showTopMarker = start > 0
        val showBottomMarker = end < total
        val sb = StringBuilder()
        if (showTopMarker) sb.append("▲${start} lines hidden ▲\n")
        for (i in start until end) {
            sb.append(lines[i])
            if (i < end - 1) sb.append('\n')
        }
        if (showBottomMarker) sb.append("\n\u25BC${total - end} lines hidden \u25BC")
        return sb.toString()
    }

    private fun mergeVirtualEdits() {
        val lines = virtualLines ?: return
        val editorText = codeEditor.text.toString()
        val editorLines = editorText.lines()
        val topMarkerLines = if (virtualWindowStart > 0) 1 else 0
        val bottomMarkerLines = if (virtualWindowStart + VIRTUAL_BUFFER * 2 + 1 < lines.size) 1 else 0
        val visibleLines = editorLines.size - topMarkerLines - bottomMarkerLines
        if (visibleLines <= 0) return
        for (i in 0 until visibleLines) {
            val lineIdx = virtualWindowStart + i
            if (lineIdx < lines.size) {
                lines[lineIdx] = editorLines[topMarkerLines + i]
            }
        }
    }

    private fun updateVirtualWindow(scrollY: Int) {
        if (!isVirtualScroll || virtualLines == null) return
        mergeVirtualEdits()
        val lineHeight = codeEditor.lineHeight
        if (lineHeight <= 0) return
        val viewportHeight = codeEditor.height
        if (viewportHeight <= 0) return
        val topVisibleLine = (scrollY + lineHeight / 2) / lineHeight
        val windowHalf = VIRTUAL_BUFFER
        val total = virtualLines!!.size
        if (total <= windowHalf * 2 + 1) return
        val newStart = (topVisibleLine - windowHalf + windowHalf / 2).coerceIn(0, total - windowHalf * 2 - 1)
        if (newStart == virtualWindowStart) return
        val cursorPos = codeEditor.selectionStart
        virtualWindowStart = newStart
        val newText = buildVirtualWindow(newStart)
        val oldScrollY = (codeEditor.parent as? ScrollView)?.scrollY ?: 0
        suppressEditorChange = true
        codeEditor.setText(newText)
        suppressEditorChange = false
        val newCursor = cursorPos.coerceIn(0, newText.length)
        codeEditor.setSelection(newCursor)
        (codeEditor.parent as? ScrollView)?.post {
            (codeEditor.parent as? ScrollView)?.scrollTo(0, oldScrollY.coerceAtMost(
                (codeEditor.parent as? ScrollView)?.let {
                    it.getChildAt(0)?.height?.minus(it.height)?.coerceAtLeast(0)
                } ?: 0
            ))
        }
    }

    private fun updateVirtualScrollListener() {
        val scrollView = codeEditor.parent as? ScrollView ?: return
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            if (!isVirtualScroll) return@setOnScrollChangeListener
            val lineHeight = codeEditor.lineHeight
            if (lineHeight <= 0) return@setOnScrollChangeListener
            val topLine = scrollY / lineHeight
            val threshold = 20
            if (topLine < threshold && virtualWindowStart > 0) {
                updateVirtualWindow(scrollY)
            } else if (topLine > VIRTUAL_BUFFER * 2 - threshold && virtualWindowStart + VIRTUAL_BUFFER * 2 + 1 < (virtualLines?.size ?: 0)) {
                updateVirtualWindow(scrollY)
            }
        }
    }

    private fun isGradleSyncFile(file: File?): Boolean {
        val candidate = file ?: return false
        val name = candidate.name
        return name in setOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            "libs.versions.toml"
        )
    }

    private fun markGradleSyncNeeded(file: File?) {
        if (gradleSyncRunning || !isGradleSyncFile(file)) return
        gradleSyncNeeded = true
        if (::tvStatusRight.isInitialized) tvStatusRight.text = "Rebuild needed"
        if (::tvStatusRight.isInitialized) tvStatusRight.setTextColor(StudioUi.color(StudioUi.Amber))
        setStatus("Gradle changed. Run Build to update.")
    }

    private fun isBlockedEditorFile(file: File): Boolean {
        return file.extension.lowercase() in blockedEditorExtensions
    }

    private fun saveAllFiles() {
        // Save current
        saveCurrentFile()
            // Other unsaved tabs cannot be saved without loading them first.
        if (unsavedFiles.isNotEmpty()) {
            showToast("Saved ${unsavedFiles.size} open files")
        }
    }

    private fun startAutoSave() {
        rescheduleAutoSave()
    }

    private fun rescheduleAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = scope.launch {
            delay(700)
            val file = currentFile
            if (unsavedChanges && file != null) {
                saveCurrentFile()
            }
        }
    }

    private fun rescheduleLiveDiagnostics() {
        liveDiagnosticsJob?.cancel()
        liveDiagnosticsJob = scope.launch {
            delay(500)
            updateLiveDiagnosticsNow()
        }
    }

    private fun showEditor() {
        // Compose shell decides editor vs welcome from `tabs` state; the legacy
        // View-based views are only bound in the removed bindViews().
        if (::welcomeScreen.isInitialized) StudioUi.animateFadeOut(welcomeScreen, StudioUi.AnimFast)
        if (::editorContainer.isInitialized) StudioUi.animateFadeIn(editorContainer, StudioUi.AnimNormal)
    }

    private fun showWelcome() {
        if (::welcomeScreen.isInitialized) StudioUi.animateFadeIn(welcomeScreen, StudioUi.AnimNormal)
        if (::editorContainer.isInitialized) StudioUi.animateFadeOut(editorContainer, StudioUi.AnimFast)
    }

    // MobileStudio comment
    // Tabs
    // MobileStudio comment

    private fun setupTabs() {}

    private fun refreshTabs() {
        if (!::tabContainer.isInitialized) return
        tabContainer.removeAllViews()
        openTabs.forEach { file ->
            val isActive = file == currentFile
            val isUnsaved = unsavedFiles.contains(file.absolutePath) ||
                    (file == currentFile && unsavedChanges)
            val ext = file.extension
            val info = FileIcons.forExtension(ext)
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(
                    if (isActive) StudioUi.palette.surface else StudioUi.palette.bg
                )
                minimumWidth = 80.dp()
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            }
            // Top accent line for active tab
            val accent = View(this).apply {
                setBackgroundColor(if (isActive) StudioUi.palette.accent else Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2.dp())
            }
            tab.addView(accent)

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(10.dp(), 0, 4.dp(), 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0, 1f
                )
            }
            
            val tvIcon = TextView(this).apply {
                text = info.symbol
                textSize = 10f
                setTextColor(Color.parseColor(info.color))
                setPadding(0, 0, 4.dp(), 0)
            }
            // An unsaved file is marked with a dot of its own rather than a
            // prefix. Prefixing shifted the name by a character the moment a
            // file was edited, so the whole row slid sideways while typing.
            val unsavedDot = if (isUnsaved) {
                TextView(this).apply {
                    text = "\u25CF"
                    textSize = 7f
                    setTextColor(StudioUi.color(StudioUi.Amber))
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 5.dp(), 0)
                }
            } else {
                null
            }
            val tvName = TextView(this).apply {
                text = file.name
                textSize = StudioUi.TypeSmall
                typeface = Typeface.create("monospace", Typeface.NORMAL)
                setTextColor(if (isActive) StudioUi.palette.text else StudioUi.palette.textSoft)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = 140.dp()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 6.dp(), 0)
                setOnClickListener { openFile(file) }
            }
            // Close keeps a fixed box with its own hit area, so it lines up
            // down the row and can be pressed without aiming at a glyph a few
            // pixels wide - the usual cost of a bare "\u2715" in a tab.
            val btnClose = TextView(this).apply {
                text = "\u2715"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(if (isActive) StudioUi.palette.textSoft else StudioUi.palette.muted)
                layoutParams = LinearLayout.LayoutParams(32.dp(), LinearLayout.LayoutParams.MATCH_PARENT)
                background = StudioUi.ripple(
                    this@MainActivity,
                    StudioUi.rounded(this@MainActivity, fill = "#00000000", stroke = "#00000000", radiusDp = StudioUi.RadiusMd),
                )
                setOnClickListener { closeTab(file) }
            }
            content.addView(tvIcon)
            unsavedDot?.let { content.addView(it) }
            content.addView(tvName)
            content.addView(btnClose)
            tab.addView(content)
            
            // Long press context menu
            tab.setOnLongClickListener {
                showTabContextMenu(file)
                true
            }
            
            // A full-height hairline rather than a short stub, so the tabs
            // read as a row of tabs instead of floating near each other.
            val sep = View(this).apply {
                setBackgroundColor(StudioUi.color(StudioUi.Border))
                layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
            }
            tabContainer.addView(tab)
            tabContainer.addView(sep)
        }
        // Mirror the imperative tab strip into the Compose-facing state so the
        // Studio shell tab strip stays in sync with openTabs / currentFile.
        tabs.clear()
        openTabs.forEach { f ->
            val rel = currentProjectDir?.let { runCatching { f.relativeTo(it).path }.getOrNull() ?: f.name } ?: f.name
            tabs.add(rel)
        }
        val curIdx = openTabs.indexOf(currentFile)
        if (curIdx >= 0) {
            selectedTab = curIdx
            pathText = tabs.getOrNull(curIdx) ?: ""
        }
        // Scroll to active tab
        val idx = openTabs.indexOf(currentFile)
        if (idx >= 0) {
            tabScroll.post {
                val child = tabContainer.getChildAt(idx) ?: return@post
                tabScroll.smoothScrollTo(child.left, 0)
            }
        }
    }

    private fun showTabContextMenu(file: File) {
        val items = mutableListOf(
            ui("Close") to { closeTab(file) },
            ui("Close Others") to { closeAllOtherTabs(file) },
            ui("Close All") to { closeAllTabs() }
        )
        // Add "Reopen Closed" if we have recently closed files
        if (recentlyClosedFiles.isNotEmpty()) {
            items.add(ui("Reopen Closed") to { reopenLastClosed() })
        }
        showTextMenu(file.name, items)
    }

    private val recentlyClosedFiles = mutableListOf<File>()

    private fun closeAllTabs() {
        recentlyClosedFiles.clear()
        recentlyClosedFiles.addAll(openTabs)
        openTabs.clear()
        unsavedFiles.clear()
        tabScrollPositions.clear()
        tabCursorPositions.clear()
        currentFile = null
        showWelcome()
        refreshTabs()
    }

    private fun reopenLastClosed() {
        val file = recentlyClosedFiles.removeLastOrNull() ?: return
        if (file.exists()) {
            openFile(file)
        }
    }

    private fun updateTabTitle() {
        refreshTabs()
    }

    private fun closeTab(file: File) {
        if (file == currentFile && unsavedChanges) {
            confirmDiscard { doCloseTab(file) }
        } else doCloseTab(file)
    }

    private fun doCloseTab(file: File) {
        val idx = openTabs.indexOf(file)
        if (!::tabContainer.isInitialized) return
        // Capture the tab view + its trailing separator for a close animation
        val tabView = tabContainer.getChildAt(idx * 2)
        val sepView = tabContainer.getChildAt(idx * 2 + 1)

        val finishClose = {
            recentlyClosedFiles.add(file)
            if (recentlyClosedFiles.size > 20) recentlyClosedFiles.removeAt(0)
            openTabs.remove(file)
            unsavedFiles.remove(file.absolutePath)
            tabScrollPositions.remove(file.absolutePath)
            tabCursorPositions.remove(file.absolutePath)
            if (openTabs.isEmpty()) {
                currentFile = null; showWelcome()
            } else {
                val next = openTabs.getOrElse(idx) { openTabs.last() }
                openFile(next)
            }
            refreshTabs()
        }

        if (StudioUi.shouldAnimate(this) && tabView != null) {
            tabView.animate()
                .alpha(0f)
                .translationY(-8.dp().toFloat())
                .setDuration(StudioUi.AnimFast)
                .setInterpolator(StudioUi.DefaultInterpolator)
                .withEndAction { finishClose() }
                .start()
            sepView?.animate()?.alpha(0f)?.setDuration(StudioUi.AnimFast)?.start()
        } else {
            finishClose()
        }
    }

    private fun closeAllOtherTabs(keep: File) {
        val others = openTabs.filter { it != keep }.toList()
        others.forEach { f ->
            openTabs.remove(f)
            unsavedFiles.remove(f.absolutePath)
        }
        if (currentFile != keep) openFile(keep)
        refreshTabs()
    }

    private fun switchToNextTab() {
        if (openTabs.isEmpty()) {
            currentFile = null; showWelcome()
        } else openFile(openTabs.last())
        refreshTabs()
    }

    private fun switchToNextTabInCycle() {
        if (openTabs.size <= 1) return
        val currentIdx = if (currentFile != null) openTabs.indexOf(currentFile) else -1
        val nextIdx = if (currentIdx < 0) 0 else (currentIdx + 1) % openTabs.size
        openFile(openTabs[nextIdx])
        refreshTabs()
    }

    private fun switchToPrevTabInCycle() {
        if (openTabs.size <= 1) return
        val currentIdx = if (currentFile != null) openTabs.indexOf(currentFile) else 0
        val prevIdx = if (currentIdx <= 0) openTabs.size - 1 else currentIdx - 1
        openFile(openTabs[prevIdx])
        refreshTabs()
    }

    // MobileStudio comment
    // Find & Replace
    // MobileStudio comment

    // Editor swipe gesture for tab switching
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracking = false
    private val swipeThreshold = 120f // minimum horizontal distance for swipe

    private fun setupEditorSwipeGesture() {
        val overlay = editorOverlay ?: return
        overlay.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.x
                    swipeStartY = event.y
                    swipeTracking = true
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (swipeTracking) {
                        val dx = event.x - swipeStartX
                        val dy = event.y - swipeStartY
                        // Cancel if vertical movement is significant
                        if (Math.abs(dy) > Math.abs(dx) * 0.5f || Math.abs(dy) > 30f) {
                            swipeTracking = false
                        }
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    if (swipeTracking) {
                        val dx = event.x - swipeStartX
                        if (Math.abs(dx) > swipeThreshold) {
                            if (dx > 0) {
                                // Swipe right -> previous tab
                                switchToPrevTabInCycle()
                            } else {
                                // Swipe left -> next tab
                                switchToNextTabInCycle()
                            }
                            vibrate(10)
                            swipeTracking = false
                            return@setOnTouchListener true
                        }
                    }
                    swipeTracking = false
                    false
                }
                else -> false
            }
        }
    }

    private fun setupFindReplace() {
        val panel = findViewById<LinearLayout>(R.id.find_replace_panel)
        val etFind = findViewById<EditText>(R.id.et_find)
        val etReplace = findViewById<EditText>(R.id.et_replace)
        val tvCount = findViewById<TextView>(R.id.tv_find_count)
        val btnPrev = findViewById<ImageButton>(R.id.btn_find_prev)
        val btnNext = findViewById<ImageButton>(R.id.btn_find_next)
        val btnReplace = findViewById<Button>(R.id.btn_replace_one)
        val btnReplaceAll = findViewById<Button>(R.id.btn_replace_all)
        val btnClose = findViewById<ImageButton>(R.id.btn_find_close)
        val cbCase = findViewById<CheckBox>(R.id.cb_case_sensitive)
        val cbRegex = findViewById<CheckBox>(R.id.cb_regex)

        btnClose.setOnClickListener { hideFindReplace() }

        etFind.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentSearchQuery = s.toString()
                val count =
                    highlightSearchResults(s.toString(), cbCase.isChecked, cbRegex.isChecked)
                tvCount.text = if (count > 0) "$count items" else "None"
                tvCount.setTextColor(StudioUi.color(if (count > 0) "#98C379" else "#E06C75"))
                searchResultCount = count
            }
        })

        btnNext.setOnClickListener {
            navigateSearch(
                forward = true,
                query = etFind.text.toString(),
                caseSensitive = cbCase.isChecked,
                regex = cbRegex.isChecked
            )
        }
        btnPrev.setOnClickListener {
            navigateSearch(
                forward = false,
                query = etFind.text.toString(),
                caseSensitive = cbCase.isChecked,
                regex = cbRegex.isChecked
            )
        }

        btnReplace.setOnClickListener {
            replaceCurrentMatch(etFind.text.toString(), etReplace.text.toString(), cbCase.isChecked)
        }
        btnReplaceAll.setOnClickListener {
            val count = replaceAll(
                etFind.text.toString(),
                etReplace.text.toString(),
                cbCase.isChecked,
                cbRegex.isChecked
            )
            showToast("Replaced $count items")
        }

        etFind.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                navigateSearch(true, etFind.text.toString(), cbCase.isChecked, cbRegex.isChecked)
                true
            } else false
        }
    }

    internal fun toggleFindReplace() {
        val panel = findViewById<LinearLayout>(R.id.find_replace_panel)
        if (findReplaceVisible) {
            hideFindReplace()
        } else {
            StudioUi.animateFadeIn(panel)
            findReplaceVisible = true
            // Pre-fill with selected text
            val sel = selectedEditorText()
            if (!sel.isNullOrEmpty()) {
                findViewById<EditText>(R.id.et_find).setText(sel)
            }
            findViewById<EditText>(R.id.et_find).requestFocus()
        }
    }

    internal fun toggleFindReplaceInline() {
        // For inline find/replace, we use the existing panel but could also show a compact overlay
        // For now, delegate to the existing implementation
        toggleFindReplace()
    }

    private fun hideFindReplace() {
        val panel = findViewById<LinearLayout>(R.id.find_replace_panel)
        StudioUi.animateFadeOut(panel)
        findReplaceVisible = false
        // Clear highlights
        codeEditor.text?.let { KotlinHighlighter.highlight(it, currentFile?.absolutePath) }
    }

    private fun highlightSearchResults(query: String, caseSensitive: Boolean, regex: Boolean): Int {
        if (query.isEmpty()) return 0
        val text = codeEditor.text ?: return 0
        return try {
            val pattern = if (regex) Regex(
                query,
                if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE)
            )
            else Regex(
                Regex.escape(query),
                if (caseSensitive) setOf() else setOf(RegexOption.IGNORE_CASE)
            )
            pattern.findAll(text).count()
        } catch (e: Exception) {
            0
        }
    }

    private fun navigateSearch(
        forward: Boolean,
        query: String,
        caseSensitive: Boolean,
        regex: Boolean
    ) {
        if (query.isEmpty()) return
        val text = codeEditor.text?.toString() ?: return
        val cursor = codeEditor.selectionStart
        try {
            val options =
                if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
            val pattern = if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
            val matches = pattern.findAll(text).toList()
            if (matches.isEmpty()) return
            val idx = if (forward) {
                matches.indexOfFirst { it.range.first > cursor }.takeIf { it >= 0 } ?: 0
            } else {
                matches.indexOfLast { it.range.first < cursor }.takeIf { it >= 0 }
                    ?: (matches.size - 1)
            }
            val match = matches[idx]
            codeEditor.setSelection(match.range.first, match.range.last + 1)
            // Scroll to selection
            val layout = codeEditor.layout ?: return
            val line = layout.getLineForOffset(match.range.first)
            val y = layout.getLineTop(line)
            (codeEditor.parent as? ScrollView)?.smoothScrollTo(0, y)
        } catch (e: Exception) { /* invalid regex */
        }
    }

    private fun replaceCurrentMatch(find: String, replace: String, caseSensitive: Boolean) {
        val sel =
            codeEditor.text?.substring(codeEditor.selectionStart, codeEditor.selectionEnd) ?: return
        val matches = if (caseSensitive) sel.equals(find) else sel.equals(find, ignoreCase = true)
        if (matches) {
            codeEditor.text?.replace(codeEditor.selectionStart, codeEditor.selectionEnd, replace)
        } else {
            navigateSearch(true, find, caseSensitive, false)
        }
    }

    private fun replaceAll(
        find: String,
        replace: String,
        caseSensitive: Boolean,
        regex: Boolean
    ): Int {
        val text = codeEditor.text?.toString() ?: return 0
        val options = if (caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
        val pattern = try {
            if (regex) Regex(find, options) else Regex(Regex.escape(find), options)
        } catch (e: Exception) {
            return 0
        }
        val newText = pattern.replace(text, replace)
        val count = pattern.findAll(text).count()
        codeEditor.setText(newText)
        return count
    }

    // MobileStudio comment
    // Bottom panel
    // MobileStudio comment

    private fun setupBottomPanel() {
        val tabBuild = findViewById<TextView>(R.id.tab_build)
        val tabLogcat = findViewById<TextView>(R.id.tab_logcat)
        val tabProblems = findViewById<TextView>(R.id.tab_problems)
        val tabSearch = findViewById<TextView>(R.id.tab_search)
        val tabShell = findViewById<TextView>(R.id.tab_shell)
        val btnClear = findViewById<ImageButton>(R.id.btn_clear_panel)
        val btnHide = findViewById<ImageButton>(R.id.btn_hide_panel)

        // MobileStudio comment
        val tabColors = mapOf(
            R.id.tab_build to "#98C379",
            R.id.tab_logcat to "#E5C07B",
            R.id.tab_problems to "#E06C75",
            R.id.tab_search to "#61AFEF",
            R.id.tab_shell to "#C678DD"
        )

        fun selectTab(active: TextView) {
            val color = tabColors[active.id] ?: "#61AFEF"
            // listOfNotNull, not listOf: search has no tab in the row (it lives
            // on the left rail) so findViewById hands back null for it. One
            // missing tab used to take the whole activity down with it.
            listOfNotNull(tabBuild, tabLogcat, tabProblems, tabSearch, tabShell).forEach { tab ->
                if (tab.id == active.id) {
                    if (StudioUi.shouldAnimate(this@MainActivity)) {
                        val fromColor = tab.currentTextColor
                        val toColorVal = Color.parseColor(color)
                        val animator = ValueAnimator.ofArgb(fromColor, toColorVal)
                        animator.duration = 150
                        animator.interpolator = StudioUi.DefaultInterpolator
                        animator.addUpdateListener { tab.setTextColor(it.animatedValue as Int) }
                        animator.start()
                        tab.setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                    } else {
                        tab.setTextColor(Color.parseColor(color))
                        tab.setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                    }
                } else {
                    tab.setTextColor(StudioUi.color(StudioUi.Muted))
                    tab.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }

        tabBuild.setOnClickListener { selectTab(tabBuild); switchToPanel("build") }
        tabLogcat.setOnClickListener {
            selectTab(tabLogcat)
            switchToPanel("logcat")
            ensureLogcatStarted()
        }
        tabProblems.setOnClickListener { selectTab(tabProblems); switchToPanel("problems") }
        
        val problemsTabAll = findViewById<TextView>(R.id.problems_tab_all)
        val problemsTabFile = findViewById<TextView>(R.id.problems_tab_file)
        val problemsTabProject = findViewById<TextView>(R.id.problems_tab_project)
        
        fun selectProblemsTab(active: TextView) {
            val tabs = listOf(problemsTabAll, problemsTabFile, problemsTabProject)
            val activeBg = StudioUi.rounded(this, fill = StudioUi.Surface2, radiusDp = StudioUi.RadiusMd)
            tabs.forEach { tab ->
                if (tab == active) {
                    if (StudioUi.shouldAnimate(this@MainActivity)) {
                        val fromColor = tab.currentTextColor
                        val toColor = StudioUi.color(StudioUi.Text)
                        ValueAnimator.ofArgb(fromColor, toColor).apply {
                            duration = 150
                            interpolator = StudioUi.DefaultInterpolator
                            addUpdateListener { tab.setTextColor(it.animatedValue as Int) }
                            start()
                        }
                    } else {
                        tab.setTextColor(StudioUi.color(StudioUi.Text))
                    }
                    tab.background = activeBg
                } else {
                    if (StudioUi.shouldAnimate(this@MainActivity)) {
                        val fromColor = tab.currentTextColor
                        val toColor = StudioUi.color(StudioUi.TextSoft)
                        ValueAnimator.ofArgb(fromColor, toColor).apply {
                            duration = 150
                            interpolator = StudioUi.DefaultInterpolator
                            addUpdateListener { tab.setTextColor(it.animatedValue as Int) }
                            start()
                        }
                    } else {
                        tab.setTextColor(StudioUi.color(StudioUi.TextSoft))
                    }
                    tab.background = null
                }
            }
            renderProblems()
        }
        
        problemsTabAll.setOnClickListener { selectProblemsTab(problemsTabAll) }
        problemsTabFile.setOnClickListener { selectProblemsTab(problemsTabFile) }
        problemsTabProject.setOnClickListener { selectProblemsTab(problemsTabProject) }

        tabProblems.setOnLongClickListener {
            copyProblemsToClipboard()
            true
        }
        tabSearch?.apply {
            setOnClickListener {
                selectTab(this)
                switchToPanel("search")
                setupSearchPanel()
            }
        }
        tabShell.setOnClickListener {
            selectTab(tabShell)
            switchToPanel("shell")
            terminalTabManager?.scrollToBottom()
        }

        buildOutput.setOnLongClickListener {
            showOutputFullscreen(AppLanguage.literal(this, "Build Output"), buildOutput.currentText())
            true
        }
        
        btnClear.setOnClickListener {
            buildOutput.clear()
            logcatText.text = ""
            // Clear the active terminal tab
            terminalTabManager?.clearActiveTab()
        }
        btnHide.setOnClickListener {
            hideBottomPanel()
        }
        syncBottomPanelToggleRotation(expanded = false, animate = false)

        // The grabber is the panel's only handle while it is collapsed, so it
        // toggles the same panel and flips its glyph to show which way the next
        // press will move it.
        findViewById<View>(R.id.shell_grabber)?.setOnClickListener {
            if (bottomPanel.visibility == View.VISIBLE) hideBottomPanel() else showBottomPanel()
        }

        // Drag resize. The panel sits below the editor in portrait and beside
        // it in landscape, so the handle adjusts its height or its width. The
        // direction is read from the panel's own layout params rather than the
        // current orientation, so it stays correct across a rotation without
        // having to re-install the listener.
        val divider = findViewById<View>(R.id.panel_divider)
        var startY = 0f
        var startX = 0f
        var startSize = 0
        // Null in the tablet-landscape 3-pane window: there is no divider there
        // because the panel is docked in its own pane (resize uses the handle
        // inside the panel instead).
        divider?.setOnTouchListener { _, event ->
            val panelParams = bottomPanel.layoutParams as? LinearLayout.LayoutParams
            val resizeHorizontally = panelParams != null && panelParams.width > 0
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startX = event.rawX
                    startSize = if (resizeHorizontally) bottomPanel.width else bottomPanel.height
                }

                MotionEvent.ACTION_MOVE -> {
                    if (resizeHorizontally) {
                        // Dragging the divider to the left widens the panel.
                        val width = (startSize - (event.rawX - startX).toInt())
                            .coerceIn(dp(220), dp(560))
                        panelParams?.width = width
                        bottomPanelExpandedWidth = width
                    } else {
                        val height = (startSize - (event.rawY - startY).toInt()).coerceIn(100, 1400)
                        bottomPanel.layoutParams.height = height
                        bottomPanelExpandedHeight = height
                    }
                    bottomPanel.requestLayout()
                }
            }
            true
        }

        var stripStartY = 0f
        var stripStartX = 0f
        var stripStartSize = 0
        // The strip under the tab row resizes the panel too. It is the handle
        // a user's thumb actually finds while looking at the panel, and it had
        // the worst bug of the two: the factory's listener installed a second
        // listener on ACTION_DOWN and then removed itself on ACTION_UP, so one
        // drag was all it ever managed - the second gesture landed on a view
        // with nothing listening. One listener now, for the whole gesture, and
        // it records the new size so hiding and showing the panel keeps it.
        findViewById<View>(R.id.panel_resize_handle)?.setOnTouchListener { _, event ->
            val panelParams = bottomPanel.layoutParams as? LinearLayout.LayoutParams
            val resizeHorizontally = panelParams != null && panelParams.width > 0
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    stripStartY = event.rawY
                    stripStartX = event.rawX
                    stripStartSize = if (resizeHorizontally) bottomPanel.width else bottomPanel.height
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (resizeHorizontally) {
                        val width = (stripStartSize + (event.rawX - stripStartX).toInt())
                            .coerceIn(dp(220), dp(560))
                        panelParams?.width = width
                        bottomPanelExpandedWidth = width
                    } else {
                        val height = (stripStartSize - (event.rawY - stripStartY).toInt())
                            .coerceIn(dp(160), dp(1400))
                        bottomPanel.layoutParams.height = height
                        bottomPanelExpandedHeight = height
                    }
                    bottomPanel.requestLayout()
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        problemsRecycler?.layoutManager = LinearLayoutManager(this).apply {
            isItemPrefetchEnabled = true
        }
        problemsRecycler?.setItemViewCacheSize(20)
        problemsRecycler?.isDrawingCacheEnabled = true
        problemsRecycler?.setOnLongClickListener {
            copyProblemsToClipboard()
            true
        }

        buildOutput.onFollowChanged = { _ -> }
        logcatText.onFollowChanged = { _ -> }

        // Panel swipe gesture for switching between panel tabs
        setupPanelSwipeGesture()
    }

    private val panelTabs = listOf("build", "logcat", "problems", "search", "shell", "ai")
    private var panelSwipeStartX = 0f
    private var panelSwipeStartY = 0f
    private var panelSwipeTracking = false

    private fun setupPanelSwipeGesture() {
        bottomPanel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    panelSwipeStartX = event.x
                    panelSwipeStartY = event.y
                    panelSwipeTracking = true
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (panelSwipeTracking) {
                        val dx = event.x - panelSwipeStartX
                        val dy = event.y - panelSwipeStartY
                        if (Math.abs(dy) > Math.abs(dx) * 0.5f) {
                            panelSwipeTracking = false
                        }
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    if (panelSwipeTracking) {
                        val dx = event.x - panelSwipeStartX
                        if (Math.abs(dx) > 100f) {
                            val currentIdx = panelTabs.indexOf(currentBottomPanelTab).coerceAtLeast(0)
                            val nextIdx = if (dx < 0) {
                                (currentIdx + 1).coerceAtMost(panelTabs.size - 1)
                            } else {
                                (currentIdx - 1).coerceAtLeast(0)
                            }
                            if (nextIdx != currentIdx) {
                                val newTab = panelTabs[nextIdx]
                                switchToPanel(newTab)
                                vibrate(10)
                            }
                            panelSwipeTracking = false
                            return@setOnTouchListener true
                        }
                    }
                    panelSwipeTracking = false
                    false
                }
                else -> false
            }
        }
    }

    private fun setupSearchPanel() {
        val searchContainer = findViewById<LinearLayout>(R.id.search_panel)
        searchContainer.removeAllViews()

        val etSearch = EditText(this).apply {
            hint = AppLanguage.literal(this@MainActivity, "Search project...")
            setTextColor(StudioUi.color(StudioUi.Text)); setHintTextColor(StudioUi.color(StudioUi.Muted))
            textSize = 13f; background = null
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }

        var caseSensitive = false
        var wholeWord = false
        var useRegex = false

        fun optionCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
            CheckBox(this).apply {
                text = label
                isChecked = checked
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(dp(4), 0, dp(10), 0)
                setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
            }

        val optionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            addView(optionCheck("Aa", false) { caseSensitive = it })
            addView(optionCheck(ui("Whole word"), false) { wholeWord = it })
            addView(optionCheck(".*", false) { useRegex = it })
        }

        val resultList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }

        // Results and the empty state share one host rather than stacking, so
        // the placeholder can fill the panel without the list having to give
        // up its height when there is something to show.
        val resultsHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        resultsHost.addView(
            resultList,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        // Two different blanks: nothing typed yet, and nothing found. They look
        // the same otherwise, and only one of them is the user's problem.
        val emptyContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        fun showEmpty(glyph: String, title: String, hint: String) {
            emptyContainer.removeAllViews()
            emptyContainer.addView(
                StudioUi.emptyState(
                    this@MainActivity,
                    glyph = glyph,
                    title = title,
                    hint = hint,
                ),
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            emptyContainer.visibility = View.VISIBLE
        }
        val idleEmpty = Triple(
            "\uD83D\uDD0D",
            ui("프로젝트 검색"),
            ui("찾을 문자열을 입력하면 파일 전체에서 찾아줍니다"),
        )
        showEmpty(idleEmpty.first, idleEmpty.second, idleEmpty.third)
        resultsHost.addView(emptyContainer)

        searchContainer.addView(etSearch)
        searchContainer.addView(optionsRow)
        searchContainer.addView(resultsHost)

        var searchJob: Job? = null
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: return
                searchJob?.cancel()
                if (q.length < 2) {
                    resultList.adapter = SearchResultAdapter(emptyList()) { _, _ -> }
                    showEmpty(idleEmpty.first, idleEmpty.second, idleEmpty.third)
                    return
                }
                val cs = caseSensitive
                val ww = wholeWord
                val rx = useRegex
                searchJob = scope.launch {
                    delay(250)
                    val results = withContext(Dispatchers.IO) {
                        searchInProject(q, cs, ww, rx)
                    }
                    if (!isActive) return@launch
                    resultList.adapter = SearchResultAdapter(results) { file, line ->
                        openFile(file)
                        // jump to line
                        val text = codeEditor.text?.toString() ?: return@SearchResultAdapter
                        val lines = text.lines()
                        var offset = 0
                        for (i in 0 until minOf(line - 1, lines.size - 1)) {
                            offset += lines[i].length + 1
                        }
                        codeEditor.setSelection(offset.coerceAtMost(text.length))
                    }
                    if (results.isEmpty()) {
                        showEmpty(
                            "\u2753",
                            ui("결과 없음"),
                            "\"$q\"과(와) 일치하는 줄을 찾지 못했습니다",
                        )
                    } else {
                        emptyContainer.visibility = View.GONE
                    }
                }
            }
        })
    }

    data class SearchResult(val file: File, val line: Int, val preview: String)

    private fun searchInProject(
        query: String,
        caseSensitive: Boolean = false,
        wholeWord: Boolean = false,
        regex: Boolean = false
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val root = currentProjectDir ?: return results

        val matcher: (String) -> Boolean
        if (regex) {
            val pattern = try {
                Regex(
                    if (wholeWord) """\b(?:$query)\b""" else query,
                    if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                )
            } catch (_: Exception) {
                return results
            }
            matcher = { line -> pattern.containsMatchIn(line) }
        } else if (wholeWord) {
            val pattern = Regex(
                """\b${Regex.escape(query)}\b""",
                if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            )
            matcher = { line -> pattern.containsMatchIn(line) }
        } else {
            matcher = { line -> line.contains(query, ignoreCase = !caseSensitive) }
        }

        root.walkTopDown()
            .filter { file ->
                if (!file.isFile) return@filter false
                val path = file.path.replace('\\', '/')
                if (path.contains("/build/") || path.contains("/.git/") ||
                    path.contains("/.gradle/") || path.contains("/.idea/") ||
                    path.contains("/node_modules/")
                ) return@filter false
                file.extension in listOf(
                    "kt", "kts", "java", "xml", "json", "gradle", "md", "txt",
                    "pro", "properties", "sh", "c", "cpp", "h", "hpp", "py",
                    "lua", "js", "ts", "html", "css", "yaml", "yml", "toml"
                )
            }
            .forEach { file ->
                try {
                    file.readLines().forEachIndexed { idx, lineText ->
                        if (matcher(lineText)) {
                            results += SearchResult(file, idx + 1, lineText.trim().take(80))
                        }
                    }
                } catch (_: Exception) {
                }
                if (results.size > 200) return@forEach
            }
        return results
    }

    inner class SearchResultAdapter(
        val items: List<SearchResult>,
        val onClick: (File, Int) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
            object : RecyclerView.ViewHolder(LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(12, 8, 12, 8)
            }) {}

        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, pos: Int) {
            val r = items[pos];
            val row = holder.itemView as LinearLayout
            row.removeAllViews()
            row.addView(TextView(this@MainActivity).apply {
                text = "${r.file.name}:${r.line}"; textSize = 11f; typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color("#61AFEF"))
            })
            row.addView(TextView(this@MainActivity).apply {
                text = r.preview; textSize = 11f; typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(StudioUi.TextSoft))
            })
            row.setOnClickListener { onClick(r.file, r.line) }
        }
    }

    // MobileStudio comment
    // Build
    // MobileStudio comment

    private fun ensureBuildReady(onReady: () -> Unit) {
        if (jreGradleInstaller.isInstalled) {
            onReady(); return
        }
        setBuildRunning(true); showBottomPanel(); switchToPanel("build")
        buildOutput.appendLine("> Installing JDK 17 + Gradle... (first run, about 200MB)", LineLevel.INFO)
        jreGradleInstaller.install { ok ->
            setBuildRunning(false)
            if (ok) {
                buildOutput.appendLine("> Build environment ready!", LineLevel.SUCCESS)
                onReady()
            } else {
                buildOutput.appendLine("JDK/Gradle installation failed", LineLevel.ERROR)
            }
        }
    }

    private fun showBuildMenu() = showTextMenu(ui("Build"), listOf(
        ui("Build Task...") to { showBuildTaskDialog() },
        ui("Inspect Project") to { runProjectInspection() },
        // Formerly build toolbar buttons.
        ui("Rebuild") to { if (!isBuildRunning) runGradleTask("clean", "assembleDebug") },
        ui("Stop") to { stopBuild() },
        ui("Install APK") to { installLatestApk() },
        ui("Clean") to { if (!isBuildRunning) runGradleTask("clean") }
    ))

    private fun runProjectInspection() {
        val dir = currentProjectDir ?: run {
            showToast(ui("Open a project first"))
            return
        }
        saveAllFiles()
        com.aide.mobilestudio.validation.ProjectInspectionDialog.show(
            activity = this,
            projectRoot = dir,
            onNavigate = { file, line -> openFileAtLine(file, line) },
            onShowInProblems = { report ->
                buildErrors = report.toBuildProblems()
                refreshProblems()
                showBottomPanel()
                switchToPanel("problems")
                setStatus(
                    "Inspection: ${report.errorCount} errors, ${report.warningCount} warnings"
                )
            }
        )
    }

    private fun showBuildTaskDialog() {
        val currentTask = projectBuildPref("task") ?: defaultBuildTaskForProject()
        val currentFlag = projectBuildPref("flag") ?: "none"

        val variants = variantSelector.getCurrentVariant().let { current ->
            listOf("debug" to "", "release" to "") +
                (syncManager?.projectModel?.availableVariants?.map { v ->
                    v.name to v.flavor
                } ?: emptyList())
        }.distinct()

        BuildTaskDialog(this).show(
            currentTask = currentTask,
            currentFlag = currentFlag,
            isBuildRunning = isBuildRunning,
            gradleSyncNeeded = gradleSyncNeeded,
            variants = variants,
            onRun = { args ->
                gradleSyncNeeded = false
                // Remember the task itself so the toolbar's Build button
                // repeats it; the rest of the line (flags, custom tasks)
                // stays specific to this run.
                saveProjectBuildPrefs(args.first(), "none")
                runGradleTask(*args.toTypedArray())
            },
            onRunBackground = { args ->
                val dir = currentProjectDir ?: return@show
                NotificationPermission.ensure(this@MainActivity)
                saveProjectBuildPrefs(args.first(), "none")
                saveAllFiles()
                GradleBuildService.start(this@MainActivity, dir, args)
                setStatus("Background build started")
            },
            onStop = {
                stopBuild()
                GradleBuildService.stop(this)
            }
        )
    }

    internal fun runGradleSyncNow() {
        val dir = currentProjectDir ?: run { setStatus("Open a project first"); return }
        if (gradleSyncRunning) {
            setStatus("Sync already running")
            return
        }
        buildScheduler.suspendForManualBuild()
        saveAllFiles()
        ensureBuildReady {
            gradleSyncRunning = true
            setBuildRunning(true)
            showBottomPanel()
            switchToPanel("build")
            buildOutput.clear()
            buildOutput.appendLine("> Sync Now: Starting Gradle project sync...", LineLevel.INFO)
            buildOutput.appendLine("> Analyzing build configuration, resolving dependencies, indexing symbols", LineLevel.INFO)
            buildOutput.appendLine("", LineLevel.INFO)

            val syncManager = GradleSyncManager(this@MainActivity)
            this@MainActivity.syncManager = syncManager
            autoComplete.setGradleSyncManager(syncManager)
            com.aide.mobilestudio.analysis.QuickFixProvider.setGradleSyncManager(syncManager)

            // Register progress listener for real-time step updates
            val progressListener = object : GradleSyncProgress.Listener {
                override fun onSyncStepStarted(phase: GradleSyncProgress.SyncPhase, message: String) {
                    runOnUiThread {
                        buildOutput.appendLine("> [${phase.displayName}] $message", LineLevel.INFO)
                    }
                }
                override fun onSyncStepCompleted(phase: GradleSyncProgress.SyncPhase, message: String) {
                    runOnUiThread {
                        buildOutput.appendLine("> [${phase.displayName}] Done", LineLevel.INFO)
                    }
                }
                override fun onSyncProgress(progress: Float, message: String) {
                    runOnUiThread {
                        if (::buildProgress.isInitialized) buildProgress.progress = (progress * 100).toInt()
                    }
                }
                override fun onSyncCompleted(success: Boolean, durationMs: Long, summary: String) {
                    syncManager.syncProgress.removeListener(this)
                }
                override fun onSyncError(phase: GradleSyncProgress.SyncPhase, error: String, suggestion: String) {
                    runOnUiThread {
                        buildOutput.appendLine("", LineLevel.ERROR)
                        buildOutput.appendLine("> SYNC ERROR at ${phase.displayName}:", LineLevel.ERROR)
                        buildOutput.appendLine("> $error", LineLevel.ERROR)
                        if (suggestion.isNotEmpty()) {
                            buildOutput.appendLine("> Suggestion: $suggestion", LineLevel.WARNING)
                        }
                        buildOutput.appendLine("", LineLevel.ERROR)
                    }
                }
            }
            syncManager.syncProgress.addListener(progressListener)

            syncManager.syncProject(
                projectDir = dir,
                gradleRunner = gradleRunner,
                installer = jreGradleInstaller,
                onProgress = { msg ->
                    runOnUiThread {
                        buildOutput.appendLine("> $msg", LineLevel.INFO)
                    }
                },
                onComplete = { success, summary ->
                    runOnUiThread {
                        gradleSyncRunning = false
                        setBuildRunning(false)
                        buildScheduler.resumeAfterManualBuild(runPending = false)
                        gradleSyncNeeded = !success

                        val symbolCount = syncManager.symbolCount
                        val packageCount = syncManager.getPackages().size
                        val model = syncManager.projectModel

                        if (model != null) {
                            buildScheduler.projectModel = model
                            variantSelector.setVariantsFromModel(model)
                        }

                        if (::tvStatusRight.isInitialized) tvStatusRight.text = if (success) "Synced" else "Sync failed"
                        if (::tvStatusRight.isInitialized) tvStatusRight.setTextColor(StudioUi.color(if (success) "#98C379" else "#E06C75"))

                        val status = if (success) {
                            buildString {
                                append("Sync complete: $symbolCount symbols")
                                if (model != null) {
                                    append(" | ${model.applicationId.ifEmpty { model.packageName }}")
                                    if (model.availableVariants.isNotEmpty()) {
                                        append(" | ${model.availableVariants.size} variants")
                                    }
                                    val moduleCount = model.propertiesFromGradle["moduleCount"]?.toIntOrNull() ?: 1
                                    if (moduleCount > 1) {
                                        append(" | $moduleCount modules")
                                    }
                                }
                            }
                        } else {
                            "Sync failed"
                        }
                        setStatus(status)

                        buildOutput.appendLine("", LineLevel.INFO)
                        if (success) {
                            buildOutput.appendLine("> SYNC SUCCESSFUL", LineLevel.SUCCESS)
                        } else {
                            buildOutput.appendLine("> SYNC FAILED", LineLevel.ERROR)
                        }
                        buildOutput.appendLine("> $summary", if (success) LineLevel.SUCCESS else LineLevel.WARNING)

                        // Show detailed project model info
                        if (model != null && success) {
                            buildOutput.appendLine("", LineLevel.INFO)
                            buildOutput.appendLine("=== Project Model ===", LineLevel.INFO)
                            buildOutput.appendLine("  Application ID: ${model.applicationId.ifEmpty { "N/A" }}", LineLevel.INFO)
                            buildOutput.appendLine("  Namespace: ${model.namespace.ifEmpty { "N/A" }}", LineLevel.INFO)
                            buildOutput.appendLine("  compileSdk: ${model.compileSdk} | minSdk: ${model.minSdk} | targetSdk: ${model.targetSdk}", LineLevel.INFO)
                            buildOutput.appendLine("  Version: ${model.versionName} (${model.versionCode})", LineLevel.INFO)
                            if (model.ndkVersion.isNotEmpty()) {
                                buildOutput.appendLine("  NDK: ${model.ndkVersion}", LineLevel.INFO)
                            }
                            buildOutput.appendLine("  Kotlin: ${if (model.isKotlinApplied) "applied" else "not applied"}", LineLevel.INFO)
                            buildOutput.appendLine("  Compose: ${if (model.isComposeEnabled) "enabled" else "disabled"}", LineLevel.INFO)
                            buildOutput.appendLine("  ViewBinding: ${if (model.isViewBindingEnabled) "enabled" else "disabled"}", LineLevel.INFO)
                            buildOutput.appendLine("  Native Build: ${if (model.hasNativeBuild) "yes" else "no"}", LineLevel.INFO)

                            if (model.availableVariants.isNotEmpty()) {
                                buildOutput.appendLine("", LineLevel.INFO)
                                buildOutput.appendLine("  Variants:", LineLevel.INFO)
                                model.availableVariants.forEach { v ->
                                    buildOutput.appendLine("    - ${v.name} (${v.type}${if (v.flavor.isNotEmpty()) ", flavor=${v.flavor}" else ""})", LineLevel.INFO)
                                }
                            }

                            if (model.buildTypes.isNotEmpty()) {
                                buildOutput.appendLine("", LineLevel.INFO)
                                buildOutput.appendLine("  Build Types:", LineLevel.INFO)
                                model.buildTypes.values.forEach { bt ->
                                    buildOutput.appendLine("    - ${bt.name}: debuggable=${bt.isDebuggable}, minify=${bt.isMinifyEnabled}", LineLevel.INFO)
                                }
                            }

                            if (model.sourceSets.isNotEmpty()) {
                                buildOutput.appendLine("", LineLevel.INFO)
                                buildOutput.appendLine("  Source Sets: ${model.sourceSets.size}", LineLevel.INFO)
                            }

                            if (model.dependencies.isNotEmpty()) {
                                buildOutput.appendLine("", LineLevel.INFO)
                                buildOutput.appendLine("  Dependencies: ${model.dependencies.size}", LineLevel.INFO)
                                // Group by configuration
                                val depsByConfig = model.dependencies.groupBy { it.configuration }
                                depsByConfig.forEach { (config, deps) ->
                                    buildOutput.appendLine("    $config: ${deps.size}", LineLevel.INFO)
                                }
                            }

                            val moduleCount = model.propertiesFromGradle["moduleCount"]?.toIntOrNull() ?: 1
                            if (moduleCount > 1) {
                                buildOutput.appendLine("", LineLevel.INFO)
                                buildOutput.appendLine("  Multi-module project: $moduleCount modules", LineLevel.INFO)
                            }
                        }

                        buildOutput.appendLine("", LineLevel.INFO)
                        buildOutput.appendLine("> Autocomplete now includes $symbolCount symbols from dependencies", LineLevel.INFO)

                        // Sync autocomplete symbols
                        autoComplete.syncProject(dir) { count, msg ->
                            buildOutput.appendLine("> $msg", LineLevel.INFO)
                        }
                    }
                }
            )
        }
    }

    private fun showOutputFullscreen(title: String, text: String) {
        val box = TextView(this).apply {
            setTextColor(StudioUi.color(StudioUi.Text))
            setBackgroundColor(StudioUi.color(StudioUi.Ink))
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
            this.text = text.ifBlank { AppLanguage.literal(this@MainActivity, "No output yet.") }
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(AppLanguage.literal(this, "Copy")) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(title, box.text))
                showToast(AppLanguage.literal(this, "Copied"))
            }
            .setNegativeButton(AppLanguage.literal(this, "Close"), null)
            .show()
    }

    private fun startBuildAll() {
        val currentBuildTaskValue = currentBuildTask()
        if (currentProjectDir == null && projectTabs.isEmpty()) {
            setStatus("Open a project first")
            return
        }
        val target = currentProjectDir
        val others = projectTabs.filter { target == null || !sameFile(it, target) }
        if (others.isEmpty()) {
            runGradleTask(currentBuildTaskValue)
            setStatus("Build All: only this project is open")
            return
        }
        if (target == null) {
            openProject(others.first(), askConfirmation = false)
        }
        val toQueue = projectTabs.filter { currentProjectDir?.let { cur -> sameFile(it, cur) } != true }
        buildQueue.addAll(toQueue.map { QueuedBuild(it, listOf(currentBuildTaskValue)) })
        val queuedCount = buildQueue.size
        setStatus("Build All: $queuedCount queued (this + queued)")
        runGradleTask(currentBuildTaskValue)
        refreshProjectTabs()
    }

    private fun startSwitchToProject(project: File) {
        val cur = currentProjectDir
        if (cur != null && sameFile(cur, project)) return
        saveCurrentFile()
        openProject(project, askConfirmation = false)
    }

    private fun closeProjectTab(project: File) {
        if (projectTabs.size <= 1) return
        projectTabs.removeAll { sameFile(it, project) }
        if (project == currentProjectDir && projectTabs.isNotEmpty()) {
            openProject(projectTabs.last(), askConfirmation = false)
        }
        refreshProjectTabs()
    }

    private fun refreshProjectTabs() {
        if (!::projectTabContainer.isInitialized) return
        projectTabContainer.removeAllViews()
        val single = projectTabs.size <= 1 && buildQueue.isEmpty()
        projectTabScroll.visibility = if (single) View.GONE else View.VISIBLE
        if (single) return

        projectTabs.forEach { project ->
            val isActive = currentProjectDir?.let { sameFile(it, project) } ?: false
            val tab = TextView(this).apply {
                text = project.name
                textSize = 11f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(14.dp, 0, 14.dp, 0)
                background = GradientDrawable().apply {
                    cornerRadius = 8.dp.toFloat()
                    setColor(StudioUi.color(if (isActive) "#2E6F4F" else "#1D2A3C"))
                }
                setTextColor(StudioUi.color(if (isActive) "#46C07D" else "#9FC3E8"))
                setOnClickListener { startSwitchToProject(project) }
                setOnLongClickListener {
                    closeProjectTab(project)
                    true
                }
            }
            projectTabContainer.addView(tab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = 6.dp
            })
        }

        if (buildQueue.isNotEmpty()) {
            val queueLabel = TextView(this).apply {
                text = "\u23F1${buildQueue.size} queue"
                textSize = 10f
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                setPadding(10.dp, 0, 10.dp, 0)
                background = GradientDrawable().apply {
                    cornerRadius = 8.dp.toFloat()
                    setColor(StudioUi.color("#3A2B12"))
                }
                setTextColor(StudioUi.color("#E3B963"))
            }
            projectTabContainer.addView(queueLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginStart = 6.dp
            })
        }

        val allTab = TextView(this).apply {
            text = "\u25B6All"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(12.dp, 0, 12.dp, 0)
            background = GradientDrawable().apply {
                cornerRadius = 8.dp.toFloat()
                setColor(StudioUi.color("#1E4D3B"))
            }
            setTextColor(StudioUi.color("#7FE0A8"))
            setOnClickListener { startBuildAll() }
        }
        projectTabContainer.addView(allTab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginStart = 6.dp
        })

        val addTab = TextView(this).apply {
            text = "\uFF0B  Add project"
            textSize = 10f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(12.dp, 0, 12.dp, 0)
            background = GradientDrawable().apply {
                cornerRadius = 8.dp.toFloat()
                setColor(StudioUi.color("#1D2A3C"))
            }
            setTextColor(StudioUi.color("#4F8EF7"))
            setOnClickListener { showOpenProjectPicker() }
        }
        projectTabContainer.addView(addTab, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
            marginStart = 6.dp
        })
    }

    /**
     * Windows .exe cross-build: clang --target=x86_64-w64-mingw32 + mingw-w64 sysroot.
     */
    private fun runWindowsExeBuild(dir: File) {
        showBottomPanel()
        switchToPanel("build")
        buildOutput.clear()
        buildOutput.appendLine("> Build .exe (clang + mingw-w64)", LineLevel.INFO)
        val source = listOf(File(dir, "main.c"), File(dir, "main.cpp"), File(dir, "main.cc"))
            .firstOrNull { it.isFile }
        if (source == null) {
            buildOutput.appendLine("> main.c/main.cpp not found in project root.", LineLevel.ERROR)
            setStatus("No C/C++ source found")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val winExe = WindowsExeEnvironment(this@MainActivity)
                val mingwRoot = File(filesDir, "toolchain/mingw-x86_64")
                if (!MingwDownloader.isValidMingw(mingwRoot)) {
                    withContext(Dispatchers.Main) {
                        buildOutput.appendLine("> MinGW sysroot missing, starting automatic download...", LineLevel.INFO)
                        setStatus("Downloading MinGW...")
                    }
                    val mres = MingwDownloader.ensureMingw(this@MainActivity, mingwRoot) { msg ->
                        runOnUiThread { buildOutput.appendLine(msg, LineLevel.INFO) }
                    }
                    if (!mres.success) {
                        withContext(Dispatchers.Main) {
                            buildOutput.appendLine("> MinGW download failed: ${mres.message}", LineLevel.ERROR)
                            setStatus("MinGW download failed")
                        }
                        return@launch
                    }
                }
                val result = try {
                    winExe.buildWindowsExe(source, onLine = { line ->
                        runOnUiThread {
                            buildOutput.appendLine(line, LineLevel.INFO)
                        }
                    })
                } catch (e: Exception) {
                    runOnUiThread { buildOutput.appendLine("> Build error: ${e.message}", LineLevel.ERROR) }
                    null
                }
                withContext(Dispatchers.Main) {
                    if (result != null) {
                        buildOutput.appendLine("> OK: ${result.name} (${result.length()} bytes)", LineLevel.SUCCESS)
                        setStatus("Build .exe sucessfully complete")
                    } else {
                        buildOutput.appendLine("> Build failed.", LineLevel.ERROR)
                        setStatus("Build .exe failed")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    buildOutput.appendLine("> Error: ${e.message}", LineLevel.ERROR)
                    setStatus("Build .exe error")
                }
            }
        }
    }

    internal fun runGradleTask(vararg tasks: String) {
        // Build notifications: ask for POST_NOTIFICATIONS only now, when the
        // user actually starts a build that posts them (Android 13+).
        NotificationPermission.ensure(this)
        val dir = currentProjectDir ?: run {
            setStatus("Open a project first")
            showBottomPanel()
            switchToPanel("build")
            buildOutput.appendLine("> Build ignored: open a project first.", LineLevel.WARNING)
            return
        }
        if (isBuildRunning) {
            showBottomPanel()
            switchToPanel("build")
            buildOutput.appendLine("> Another project is building. Please wait.", LineLevel.WARNING)
            buildOutput.appendActionLine("> [Click here to auto-build after the current build finishes]", LineLevel.INFO) {
                buildQueue.offer(QueuedBuild(dir, tasks.toList()))
                buildOutput.appendLine("\u23F3 '${dir.name}' build queued", LineLevel.INFO)
                if (buildQueue.isNotEmpty()) {
                    val order = buildQueue.joinToString(" \u2192 ") { it.project.name }
                    buildOutput.appendLine("> Build order: $order", LineLevel.INFO)
                }
                refreshProjectTabs()
            }
            return
        }
        forceKillGradle()
        val requestedTasks = resolveRequestedBuildTasks(tasks.toList())
        if (currentProjectKind == ProjectKind.PYTHON) {
            runPythonProject(dir)
            return
        }
        if (currentProjectKind == ProjectKind.JAR && runCurrentJvmSourceInShell(dir)) {
            return
        }
        if (currentProjectKind == ProjectKind.EXE) {
            runWindowsExeBuild(dir)
            return
        }
        if (currentProjectKind == ProjectKind.GODOT) {
            openGodotEditor(dir)
            return
        }
        // Direct game play for script-based games (lua/js/gdscript) — no APK build needed
        if (runScriptGameDirect(dir)) {
            return
        }
        showBottomPanel()
        switchToPanel("build")
        buildOutput.clear()
        buildTreeView.clear()
        buildTreeView.beginBuild(dir.name)
        buildOutput.appendLine("> Build requested: ${requestedTasks.joinToString(" ")}", LineLevel.INFO)
        setStatus("Build requested")

        try {
            buildScheduler.suspendForManualBuild()
            saveAllFiles()
            ensureBuildReady {
                activeBuildTasks = requestedTasks.toList()
                setBuildRunning(true)
                backgroundTaskManager.onTaskStarted(requestedTasks.toList(), dir)
                updateBackgroundTaskIndicator()
                val manualOutput = StringBuilder()
                val manualProblems = mutableListOf<BuildProblem>()
                gradleRunner.run(
                    projectDir = dir, tasks = requestedTasks,
                    onLine = { line, level ->
                        appendBoundedBuildOutput(manualOutput, line)
                        
                        // Parse task start/end for TreeView
                        if (line.startsWith("> Task :")) {
                            val task = line.substringBefore(" ").substringAfter("> Task ")
                            buildTreeView.beginTask(task)
                        }
                        
                        BuildProblemParser.parseLine(line, dir)?.let { problem ->
                            if (problem.severity != BuildProblem.Severity.INFO || problem.file != null) {
                                manualProblems.add(problem)
                                buildErrors = manualProblems.toList()
                                refreshProblems()
                                
                                if (problem.severity == BuildProblem.Severity.ERROR) buildTreeView.addError(problem.message)
                                else if (problem.severity == BuildProblem.Severity.WARNING) buildTreeView.addWarning(problem.message)
                            }
                        }
                        buildOutput.appendLine(line, level)
                        buildMonitor.onLine(line, level)
                    },
                    onActionLine = { line, level, handler ->
                        buildOutput.appendActionLine(line, level, handler)
                    },
                    onRetry = { retry ->
                        runOnUiThread {
                            showNdkInstallDialog(retry)
                        }
                    },
                    onDone = { result ->
                        pendingBuildSuccess = result.success
                        setBuildRunning(false)
                        backgroundTaskManager.onTaskCompleted(result.success, manualOutput.toString())
                        updateBackgroundTaskIndicator()
                        buildTreeView.endBuild(result.success, result.durationMs)
                        buildErrors = BuildProblemParser.parse(manualOutput.toString(), dir)
                        refreshProblems()
                        buildScheduler.resumeAfterManualBuild(runPending = false)
                        val errorCount = buildErrors.count { it.severity == BuildProblem.Severity.ERROR }
                        val warnCount = buildErrors.count { it.severity == BuildProblem.Severity.WARNING }
                        buildOutput.appendBuildSummary(errorCount, warnCount, result.durationMs)
                        val msg = if (result.success) "Build finished" else "Build finished with $errorCount error(s)"
                        setStatus(msg)
                        if (result.success) {
                            showBuildSuccessActions()
                            vibrate(50)
                        }
                        // Refresh file tree after build to show new files (e.g. build/intermediates/)
                        currentProjectDir?.let { root ->
                            runOnUiThread { loadFileTree(root) }
                        }
                        checkBuildQueueAndContinue()
                    }
                )
            }
        } catch (e: Exception) {
            setBuildRunning(false)
            buildScheduler.resumeAfterManualBuild(runPending = false)
            val detail = e.message ?: e::class.java.simpleName
            buildOutput.appendLine("> Build could not start: $detail", LineLevel.ERROR)
            setStatus("Build start failed")
        }
    }

    private fun checkBuildQueueAndContinue() {
        val next = buildQueue.poll() ?: return
        runOnUiThread {
            buildOutput.appendLine("\u25B6 '${next.project.name}' auto build starting (queued)", LineLevel.WARNING)
            refreshProjectTabs()
            if (currentProjectDir == null || !sameFile(currentProjectDir!!, next.project)) {
                openProject(next.project, askConfirmation = false)
            }
            runGradleTask(*next.tasks.toTypedArray())
        }
    }

    private fun resolveRequestedBuildTasks(tasks: List<String>): List<String> {
        if (tasks.size == 1 && tasks[0] == "python") return tasks
        if (tasks.size == 1 && tasks[0] in setOf("assembleDebug", "assembleRelease", "bundleDebug", "bundleRelease", "jar", "build")) {
            val savedFlag = projectBuildPref("flag") ?: "none"
            return if (savedFlag == "none" || tasks[0].startsWith("-")) tasks else listOf(tasks[0], savedFlag)
        }
        return tasks.ifEmpty { listOf(defaultBuildTaskForProject()) }
    }

    private fun projectUsesNativeBuildUi(root: File): Boolean {
        if (File(root, "app/src/main/cpp/CMakeLists.txt").isFile) return true
        if (File(root, "src/main/cpp/CMakeLists.txt").isFile) return true
        return root.walkTopDown()
            .maxDepth(5)
            .filter { it.isFile }
            .any { file ->
                file.name == "CMakeLists.txt" ||
                    ((file.name == "build.gradle" || file.name == "build.gradle.kts") &&
                        runCatching { file.readText() }.getOrDefault("").contains("externalNativeBuild"))
            }
    }

    private fun appendBoundedBuildOutput(buffer: StringBuilder, line: String) {
        if (buffer.length > MAX_CAPTURED_BUILD_OUTPUT_CHARS) {
            buffer.delete(0, buffer.length - MAX_RETAINED_BUILD_OUTPUT_CHARS)
        }
        buffer.appendLine(line.take(MAX_BUILD_LINE_CHARS))
    }

    private fun parseErrorLine(line: String): Pair<File, Int>? {
        // e.g. src/main/kotlin/Foo.kt:42:5, Foo.java:12, native-lib.c:7
        val m = Regex("""((?:file://)?[^:\s]+?\.(?:kt|kts|java|xml|gradle|c|cc|cpp|h|hpp)):([0-9]+)""")
            .find(line) ?: return null
        val filePath = m.groupValues[1].removePrefix("file://")
        val lineNum = m.groupValues[2].toIntOrNull() ?: return null
        val direct = File(filePath)
        val file = when {
            direct.isAbsolute && direct.exists() -> direct
            else -> currentProjectDir?.walkTopDown()
                ?.firstOrNull { it.absolutePath.endsWith(filePath) } ?: return null
        }
        return file to lineNum
    }

    private fun openFileAtLine(file: File, lineNum: Int) {
        openFile(file)
        codeEditor.post {
            val text = codeEditor.text?.toString() ?: return@post
            val lyt = codeEditor.layout ?: return@post
            val lineCount = lyt.lineCount
            val targetLine = lineNum.coerceIn(1, lineCount)
            val offset = lyt.getLineStart(targetLine - 1)
            codeEditor.setSelection(offset.coerceAtMost(text.length))
            codeEditor.highlightLine(targetLine)
            
            // Scroll to the line
            isProgrammaticScroll = true
            val lineTop = lyt.getLineTop(targetLine - 1)
            codeEditor.scrollTo(0, lineTop.coerceAtLeast(0))
            codeEditor.postDelayed({ isProgrammaticScroll = false }, 100)
        }
    }

    private fun showBuildSuccessActions() {
        val installApkLabel = AppLanguage.literal(this, "Install APK")
        val runJarLabel = AppLanguage.literal(this, "Run JAR")
        val copyJarPathLabel = AppLanguage.literal(this, "Copy JAR path")
        val viewLogsLabel = AppLanguage.literal(this, "View Logs")
        val apk = findLatestApk()
        if (apk != null) {
            buildOutput.appendActionLine("→$installApkLabel", LineLevel.SUCCESS) { installApk(apk) }
            val pkg = currentProjectDir?.let { d ->
                runCatching {
                    val mf = listOf(File(d, "app/build.gradle.kts"), File(d, "app/build.gradle"))
                        .firstOrNull { it.isFile }?.readText() ?: ""
                    Regex("""applicationId\s*[=(]?\s*['"]([^'"]+)['"]""").find(mf)?.groupValues?.getOrNull(1)
                }.getOrNull()
            } ?: packageName
            buildOutput.appendActionLine("→$viewLogsLabel", LineLevel.SUCCESS) {
                LogcatActivity.start(this, pkg)
            }
        }
        val jar = findLatestJar()
        if (jar != null) {
            buildOutput.appendActionLine("→$runJarLabel: ${jar.name}", LineLevel.SUCCESS) { runJarInShell(jar) }
            buildOutput.appendActionLine("→$copyJarPathLabel: ${jar.name}", LineLevel.SUCCESS) { copyPathToClipboard(jar) }
        }
    }

    private fun runJarInShell(jar: File) {
        showBottomPanel()
        switchToPanel("shell")
        // Execute in the active terminal tab
        terminalTabManager?.executeCommand("java -jar ${shellQuote(jar.absolutePath)}")
    }

    private fun projectBuildPref(name: String): String? {
        val dir = currentProjectDir ?: return null
        return getSharedPreferences("project_build_options", MODE_PRIVATE)
            .getString("${dir.absolutePath}::$name", null)
    }

    private fun saveProjectBuildPrefs(task: String, flag: String) {
        val dir = currentProjectDir ?: return
        getSharedPreferences("project_build_options", MODE_PRIVATE).edit()
            .putString("${dir.absolutePath}::task", task)
            .putString("${dir.absolutePath}::flag", flag)
            .apply()
        updateBuildOptionLabel()
    }

    private fun updateBuildOptionLabel() {
        val task = normalizedBuildTask(projectBuildPref("task"))
        val flag = projectBuildPref("flag") ?: "none"
        val label = if (flag == "none") task else "$task $flag"
        findViewById<TextView>(R.id.tv_build_option)?.text = label
        findViewById<TextView>(R.id.tools_build_option_label)?.text = label
    }

    private fun currentBuildTask(): String = normalizedBuildTask(projectBuildPref("task"))

    private fun normalizedBuildTask(savedTask: String?): String {
        val current = savedTask ?: return defaultBuildTaskForProject()
        if (currentProjectKind != ProjectKind.ANDROID) return current

        val base = variantSelector.getCurrentVariant()
        return when {
            current.startsWith("assemble") -> "assemble${base.replaceFirstChar { it.uppercase() }}"
            current.startsWith("bundle") -> "bundle${base.replaceFirstChar { it.uppercase() }}"
            current.startsWith("install") -> {
                if (base.contains("debug", true)) "installDebug" else "installRelease"
            }
            current == "build" -> "assemble${base.replaceFirstChar { it.uppercase() }}"
            current == "clean" -> "clean"
            current == "uninstallAll" -> "uninstallAll"
            else -> current
        }
    }

    private fun defaultBuildTaskForProject(): String = when (currentProjectKind) {
        ProjectKind.JAR -> "jar"
        ProjectKind.PYTHON -> "python"
        ProjectKind.EXE -> "exe"
        ProjectKind.GAME -> "assemble${variantSelector.getCurrentVariant().replaceFirstChar { it.uppercase() }}"
        ProjectKind.ANDROID -> "assemble${variantSelector.getCurrentVariant().replaceFirstChar { it.uppercase() }}"
        ProjectKind.LUA_GAME -> "run"
        ProjectKind.GODOT -> "run"
    }

    private fun openGodotEditor(projectDir: File) {
        if (!com.aide.mobilestudio.godot.GodotMobileBridge.isNativeLibraryAvailable(this)) {
            showToast("Godot native library is missing. Run godot/fetch-prebuilt-libs.sh or godot/build-godot-android.sh.")
            return
        }
        saveAllFiles()
        val args = arrayOf("--editor", "--path", projectDir.absolutePath)
        val intent = Intent(this, com.aide.mobilestudio.godot.MobileGodotEditorActivity::class.java).apply {
            putExtra(org.godotengine.godot.GodotActivity.EXTRA_COMMAND_LINE_PARAMS, args)
        }
        startActivity(intent)
    }

    /**
     * Direct in-process game play for script-based games (lua/js/gdscript).
     * Finds a game script in the project's assets and launches GameRunnerActivity
     * without building an APK. Returns true if a game was launched.
     */
    private fun runScriptGameDirect(projectDir: File): Boolean {
        val script = GameRunnerActivity.findGameScript(projectDir) ?: return false
        val engine = GameRunnerActivity.detectEngine(script) ?: return false
        saveAllFiles()
        showBottomPanel()
        switchToPanel("logcat")
        logcatText.append("> Direct game play: ${script.name} ($engine)\n")
        logcatText.append("> No APK build needed — running in-process.\n")
        setStatus("Game running ($engine)")
        GameRunnerActivity.start(this, script, engine, projectDir.name)
        return true
    }

    private fun runPythonProject(projectDir: File) {        showBottomPanel()
        switchToPanel("logcat")
        saveAllFiles()
        val entry = findPythonEntryPoint(projectDir)
        logcatText.text = ""
        if (entry == null) {
            logcatText.text = "No Python entry file found. Create main.py first."
            setStatus("Python run failed")
            return
        }
        val command = "python3 ${shellQuote(entry.absolutePath)}"
        logcatText.append("> Python run: ${entry.name}\n$ $command\n")
        setStatus("Python running")
        setBuildRunning(true)
        scope.launch {
            val env = buildIntegratedShellEnv()
            val result = withContext(Dispatchers.IO) {
                runPythonCommandIfNeeded(command, projectDir, env) { line ->
                    runOnUiThread {
                        logcatText.append(line.take(4_000) + "\n")
                        logcatContainer.post { logcatText.scrollBy(0, Int.MAX_VALUE) }
                    }
                } ?: ShellRunner.Result(127, listOf("Python command could not start."), "python-native-exec", error = "python command unavailable")
            }
            logcatText.append("exit=${result.exitCode} runner=${result.strategy}\n")
            setBuildRunning(false)
            setStatus(if (result.exitCode == 0) "Python finished" else "Python failed")
        }
    }

    private fun runCurrentJvmSourceInShell(projectDir: File): Boolean {
        val source = currentFile?.takeIf {
            it.extension.equals("java", ignoreCase = true) || it.extension.equals("kt", ignoreCase = true)
        } ?: return false
        showBottomPanel()
        switchToPanel("logcat")
        saveAllFiles()
        logcatText.text = ""
        val outDir = File(cacheDir, "jvm_source_run/classes").apply { mkdirs() }
        val jarFile = File(cacheDir, "jvm_source_run/current.jar")
        val command = when (source.extension.lowercase()) {
            "java" -> {
                val fqcn = findJavaMainClass(source) ?: source.nameWithoutExtension
                "rm -rf ${shellQuote(outDir.absolutePath)} && mkdir -p ${shellQuote(outDir.absolutePath)} && javac -d ${shellQuote(outDir.absolutePath)} ${shellQuote(source.absolutePath)} && java -cp ${shellQuote(outDir.absolutePath)} $fqcn"
            }
            "kt" -> {
                "kotlinc ${shellQuote(source.absolutePath)} -include-runtime -d ${shellQuote(jarFile.absolutePath)} && java -jar ${shellQuote(jarFile.absolutePath)}"
            }
            else -> return false
        }
        logcatText.append("> Shell run: ${source.name}\n$ $command\n")
        setStatus("Shell run starting")
        setBuildRunning(true)
        scope.launch {
            val env = buildIntegratedShellEnv()
            val result = withContext(Dispatchers.IO) {
                ShellRunner.runSh(
                    context = this@MainActivity,
                    command = command,
                    cwd = projectDir,
                    env = env,
                    timeoutMs = 10L * 60L * 1000L
                ) { line ->
                    runOnUiThread {
                        logcatText.append(line.take(4_000) + "\n")
                        logcatContainer.post { logcatText.scrollBy(0, Int.MAX_VALUE) }
                    }
                }
            }
            logcatText.append("exit=${result.exitCode} runner=${result.strategy}\n")
            setBuildRunning(false)
            setStatus(if (result.exitCode == 0) "Shell run finished" else "Shell run failed")
        }
        return true
    }

    private fun findJavaMainClass(file: File): String? {
        val text = runCatching { file.readText() }.getOrDefault("")
        val pkg = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
        val cls = Regex("""\b(?:public\s+)?class\s+([A-Za-z_]\w*)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?: file.nameWithoutExtension
        return if (pkg.isNullOrBlank()) cls else "$pkg.$cls"
    }

    private fun findPythonEntryPoint(projectDir: File): File? {
        val current = currentFile?.takeIf {
            it.extension.equals("py", ignoreCase = true) &&
                runCatching { it.canonicalPath.startsWith(projectDir.canonicalPath) }.getOrDefault(false)
        }
        if (current != null) return current
        File(projectDir, "main.py").takeIf { it.isFile }?.let { return it }
        return projectDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("py", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            ?.firstOrNull()
    }

    private fun runIntegratedShell(command: String) {
        val trimmed = command.trim()
        if (trimmed.isBlank()) return
        
        // Delegate to terminal tab manager if available
        val tm = terminalTabManager
        if (tm != null) {
            // Handle built-in commands that need special treatment
            when {
                trimmed.equals("clear", ignoreCase = true) || trimmed.equals("cls", ignoreCase = true) -> {
                    tm.clearActiveTab()
                    return
                }
                trimmed.equals("exit", ignoreCase = true) || trimmed.equals("quit", ignoreCase = true) -> {
                    hideBottomPanel()
                    return
                }
                else -> {
                    tm.executeCommand(trimmed)
                    return
                }
            }
        }
        
        // Fallback: no terminal tab manager, initialize and retry
        if (shellCwd == null) {
            shellCwd = currentProjectDir ?: StorageHelper.projectsRoot(this)
        }
        val tabBar = findViewById<LinearLayout>(R.id.terminal_tab_bar)
        val container = findViewById<FrameLayout>(R.id.terminal_container)
        if (tabBar != null && container != null) {
            val cwd = shellCwd ?: StorageHelper.projectsRoot(this)
            terminalTabManager = TerminalTabManager(this, container, tabBar, cwd)
            terminalTabManager?.addNewTabButton()
            terminalTabManager?.createNewTab("MobileStudio")
            terminalTabManager?.executeCommand(trimmed)
            wireTerminalToolbar()
            updateDrawerLockState()
        }
    }

    private fun tryRunCodeSnippet(code: String, output: TextView, cwd: File): Boolean {
        val trimmed = code.trim()
        if (trimmed.isBlank()) return false

        // Detect language and wrap code if needed
        val (lang, wrappedCode) = when {
            isKotlinCode(trimmed) -> "kotlin" to wrapKotlinCode(trimmed)
            isJavaCode(trimmed) -> "java" to wrapJavaCode(trimmed)
            isCCode(trimmed) -> "c" to wrapCCode(trimmed)
            else -> return false
        }

        scope.launch {
            appendShellLine(output, "\u001B[90mCompiling $lang...\u001B[0m")
            val result = withContext(Dispatchers.IO) {
                runCodeSnippet(lang, wrappedCode, cwd)
            }
            runOnUiThread {
                result?.let { (exitCode, out) ->
                    if (out.isNotBlank()) {
                        out.lines().forEach { appendShellLine(output, it) }
                    }
                    appendShellLine(output, "\u001B[90mexit=$exitCode\u001B[0m")
                } ?: appendShellLine(output, "\u001B[91mFailed to execute $lang code\u001B[0m")
            }
        }
        return true
    }

    private fun isKotlinCode(code: String): Boolean {
        val patterns = listOf(
            Regex("^fun\\s+main\\s*\\("),                    // fun main()
            Regex("^fun\\s+main\\s*\\)"),                    // fun main)
            Regex("^fun\\s+\\w+\\s*\\([^)]*\\)\\s*\\{"),         // fun name() {
            Regex("^val\\s+\\w+\\s*="),                         // val x = 
            Regex("^var\\s+\\w+\\s*="),                         // var x =
            Regex("^println\\s*\\("),                        // println(
            Regex("^print\\s*\\("),                          // print(
            Regex("^class\\s+\\w+"),                         // class Name
            Regex("^object\\s+\\w+"),                        // object Name
            Regex("^interface\\s+\\w+"),                     // interface Name
            Regex("^data\\s+class\\s+\\w+"),                  // data class Name
        )
        return patterns.any { it.containsMatchIn(code) }
    }

    private fun isJavaCode(code: String): Boolean {
        val patterns = listOf(
            Regex("public\\s+static\\s+void\\s+main\\s*\\("),   // public static void main(
            Regex("^public\\s+class\\s+\\w+"),                // public class Name
            Regex("^class\\s+\\w+"),                          // class Name
            Regex("^interface\\s+\\w+"),                     // interface Name
            Regex("System\\.out\\.print"),                   // System.out.print
            Regex("^import\\s+"),                            // import 
            Regex("^package\\s+"),                           // package 
        )
        return patterns.any { it.containsMatchIn(code) }
    }

    private fun isCCode(code: String): Boolean {
        val patterns = listOf(
            Regex("^#include\\s+[<\"]"),                     // #include <...>
            Regex("^int\\s+main\\s*\\("),                     // int main(
            Regex("^void\\s+main\\s*\\("),                    // void main(
            Regex("printf\\s*\\("),                          // printf(
            Regex("^int\\s+\\w+\\s*="),                         // int x = 
            Regex("^char\\s+\\w+\\s*="),                        // char x =
            Regex("^float\\s+\\w+\\s*="),                       // float x =
            Regex("^double\\s+\\w+\\s*="),                      // double x =
        )
        return patterns.any { it.containsMatchIn(code) }
    }

    private fun wrapKotlinCode(code: String): String {
        val trimmed = code.trim()
        if (trimmed.startsWith("fun main") || trimmed.contains("fun main(")) {
            return "import kotlin.io.*\n$trimmed"
        }
        if (trimmed.startsWith("println") || trimmed.startsWith("print")) {
            return "import kotlin.io.*\nfun main() {\n    $trimmed\n}"
        }
        if (trimmed.startsWith("val ") || trimmed.startsWith("var ") || trimmed.startsWith("class ") || trimmed.startsWith("object ") || trimmed.startsWith("data class ")) {
            return "import kotlin.io.*\nfun main() {\n    $trimmed\n}"
        }
        return "import kotlin.io.*\nfun main() {\n    $trimmed\n}"
    }

    private fun wrapJavaCode(code: String): String {
        val trimmed = code.trim()
        if (trimmed.contains("public static void main")) {
            if (trimmed.startsWith("public class") || trimmed.startsWith("class ")) {
                return trimmed
            }
            return "public class Main {\n    $trimmed\n}"
        }
        if (trimmed.startsWith("public class") || trimmed.startsWith("class ")) {
            val className = trimmed.split("\\s+")[1].split("\\{")[0].trim()
            return "$trimmed\npublic static void main(String[] args) {\n    new $className().main(args);\n}"
        }
        return "public class Main {\n    public static void main(String[] args) {\n        $trimmed\n    }\n}"
    }

    private fun wrapCCode(code: String): String {
        val trimmed = code.trim()
        if (trimmed.contains("int main") || trimmed.contains("void main")) {
            if (trimmed.contains("#include")) {
                return trimmed
            }
            return "#include <stdio.h>\n#include <stdlib.h>\n$trimmed"
        }
        if (trimmed.contains("printf") || trimmed.contains("scanf")) {
            return "#include <stdio.h>\n#include <stdlib.h>\nint main() {\n    $trimmed\n    return 0;\n}"
        }
        if (Regex("^(int|char|float|double|long|short)\\s+\\w+\\s*=").containsMatchIn(trimmed)) {
            return "#include <stdio.h>\n#include <stdlib.h>\nint main() {\n    $trimmed\n    return 0;\n}"
        }
        return "#include <stdio.h>\n#include <stdlib.h>\nint main() {\n    $trimmed\n    return 0;\n}"
    }

    private fun runCodeSnippet(lang: String, code: String, cwd: File): Pair<Int, String>? {
        val tempDir = File(cacheDir, "shell_snippets").apply { mkdirs() }
        val sourceFile = when (lang) {
            "kotlin" -> File(tempDir, "snippet_${System.currentTimeMillis()}.kt")
            "java" -> File(tempDir, "Main.java")
            "c" -> File(tempDir, "snippet_${System.currentTimeMillis()}.c")
            else -> return null
        }
        sourceFile.writeText(code)
        
        val env = buildIntegratedShellEnv()
        
        return when (lang) {
            "kotlin" -> runKotlinSnippet(sourceFile, env, cwd)
            "java" -> runJavaSnippet(sourceFile, env, cwd)
            "c" -> runCSnippet(sourceFile, env, cwd)
            else -> null
        }
    }

    private fun runKotlinSnippet(sourceFile: File, env: Map<String, String>, cwd: File): Pair<Int, String>? {
        val jarFile = File(sourceFile.parentFile, "snippet_${System.currentTimeMillis()}.jar")
        val kotlinc = findKotlincForShell()
        val javaBin = findJavaBinForShell()
        
        if (kotlinc != null) {
            val command = "$kotlinc ${sourceFile.absolutePath} -include-runtime -d ${jarFile.absolutePath} && $javaBin -jar ${jarFile.absolutePath}"
            return ShellRunner.runSh(this, command, cwd, env, 60_000L) { }.let { 
                Pair(it.exitCode, it.outputLines.joinToString("\n")) 
            }
        } else if (javaBin != null) {
            val compilerJar = KotlinCompiler.findKotlinCompilerJar(this)
            if (compilerJar != null) {
                val command = "$javaBin -Xmx512m -jar ${compilerJar.absolutePath} ${sourceFile.absolutePath} -include-runtime -d ${jarFile.absolutePath} && $javaBin -jar ${jarFile.absolutePath}"
                return ShellRunner.runSh(this, command, cwd, env, 60_000L) { }.let { 
                    Pair(it.exitCode, it.outputLines.joinToString("\n")) 
                }
            }
        }
        return null
    }

    private fun findKotlincForShell(): String? {
        val candidates = listOf(
            "/data/data/com.aide.mobilestudio/files/toolchain/kotlinc/bin/kotlinc",
            "/data/data/com.termux/files/usr/bin/kotlinc",
            "/data/data/com.termux/files/usr/share/kotlin/bin/kotlinc",
            File(filesDir, "toolchain/kotlinc/bin/kotlinc").absolutePath,
            File(cacheDir, "kotlinc/bin/kotlinc").absolutePath,
        )
        return candidates.firstOrNull { File(it).isFile && File(it).canExecute() }
    }

    private fun findJavaBinForShell(): String? {
        val candidates = listOf(
            "/data/data/com.aide.mobilestudio/files/toolchain/jdk/bin/java",
            "/data/data/com.termux/files/usr/bin/java",
            File(filesDir, "toolchain/jdk/bin/java").absolutePath,
        )
        return candidates.firstOrNull { File(it).isFile && File(it).canExecute() }
    }

    private fun runJavaSnippet(sourceFile: File, env: Map<String, String>, cwd: File): Pair<Int, String>? {
        val outDir = File(sourceFile.parentFile, "out_${System.currentTimeMillis()}").apply { mkdirs() }
        val javaBin = findJavaBinForShell() ?: return null
        val javacBin = javaBin.replace("java", "javac")
        if (!File(javacBin).isFile) return null
        val command = "rm -rf ${outDir.absolutePath} && mkdir -p ${outDir.absolutePath} && $javacBin -d ${outDir.absolutePath} ${sourceFile.absolutePath} && $javaBin -cp ${outDir.absolutePath} Main"
        return ShellRunner.runSh(this, command, cwd, env, 60_000L) { }.let { 
            Pair(it.exitCode, it.outputLines.joinToString("\n")) 
        }
    }

    private fun runCSnippet(sourceFile: File, env: Map<String, String>, cwd: File): Pair<Int, String>? {
        val exeFile = File(sourceFile.parentFile, "snippet_${System.currentTimeMillis()}")
        val clang = findClangForShell() ?: return null
        val command = "$clang ${sourceFile.absolutePath} -o ${exeFile.absolutePath} && ${exeFile.absolutePath}"
        return ShellRunner.runSh(this, command, cwd, env, 60_000L) { }.let { 
            Pair(it.exitCode, it.outputLines.joinToString("\n")) 
        }
    }

    private fun findClangForShell(): String? {
        val candidates = listOf(
            "/data/data/com.aide.mobilestudio/files/toolchain/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang",
            "/data/data/com.termux/files/usr/bin/clang",
            File(filesDir, "toolchain/ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/clang").absolutePath,
        )
        return candidates.firstOrNull { File(it).isFile && File(it).canExecute() }
    }

    /**
     * `command "xxx" not found` ??check the GitHub release index; if the tool
     * is published there, print version/size and offer a lazy download (Y/N).
     */
    private fun offerOrReportNotFound(output: TextView, first: String, original: String) {
        appendShellLine(output, "command \"$first\" not found")
        scope.launch {
            val index = withContext(Dispatchers.IO) {
                StudioToolsIndex.fetch(this@MainActivity, forceRefresh = true)
            }
            val entry = index.find(first) ?: return@launch
            // Don't offer if the tool already exists somewhere in PATH
            val env = buildIntegratedShellEnv()
            val exists = withContext(Dispatchers.IO) {
                ShellRunner.runSh(
                    context = this@MainActivity,
                    command = "command -v '$first' >/dev/null 2>&1 && echo yes || echo no",
                    cwd = shellCwd!!,
                    env = env,
                    timeoutMs = 15_000L
                ) { }.outputLines.joinToString("\n").trim() == "yes"
            }
            if (exists) {
                appendShellLine(output, "$first already exists on this device — no download needed.")
                return@launch
            }
            appendShellLine(output, "StudioShellTools' $first was found in GitHub.")
            appendShellLine(output, "${first} version: ${entry.version.ifBlank { "?" }}")
            appendShellLine(output, "${first} size: ${StudioShellTools.formatSize(entry.size)}")
            appendShellLine(output, "Would you like to download? [Y / N]")
            pendingToolOffer = PendingToolOffer(entry, original)
        }
    }

    /** Installs a tool offered via Y/N, then re-runs the original command. */
    private fun installPendingTool(pending: PendingToolOffer, output: TextView) {
        runPending = true
        var barAppended = false
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching {
                    StudioShellTools.installTarball(
                        context = this@MainActivity,
                        entry = pending.tool,
                        onMessage = { msg -> terminalTabManager?.executeCommand("echo '$msg'") },
                        onProgress = { pct ->
                            terminalTabManager?.executeCommand("echo 'Progress: $pct%'")
                        }
                    )
                }.exceptionOrNull()
            }
            runPending = false
            if (failure != null) {
                appendShellLine(output, failure.message ?: "Download failed.")
            } else {
                appendShellLine(output, "exit=0 installed=${pending.tool.name}")
                runIntegratedShell(pending.originalCommand)
            }
        }
    }

    @Volatile
    private var runPending = false

    private fun progressBar(pct: Int): String {
        val total = 20
        val filled = (pct * total / 100).coerceIn(0, total)
        return "[" + "#".repeat(filled) + "-".repeat(total - filled) + "] $pct%"
    }

    /** Replaces the last line of the shell output (for progress bars). */
    private fun updateLastShellLine(output: TextView, text: String) {
        val current = output.text?.toString() ?: return
        val idx = current.lastIndexOf('\n')
        output.text = (if (idx >= 0) current.substring(0, idx) else "") + "\n" + text
    }

    private fun isExportCommand(code: String): Boolean {
        return code.trim().startsWith("export ")
    }

    private fun handleExportCommand(code: String) {
        val trimmed = code.trim().removePrefix("export").trim()
        // Handle export VAR=value or export VAR
        val parts = trimmed.split("=", limit = 2)
        if (parts.size == 2) {
            val key = parts[0].trim()
            val value = parts[1].trim().let { v ->
                if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) v.substring(1, v.length - 1) else v
            }
            shellEnv[key] = value
        } else if (parts.size == 1 && parts[0].isNotBlank()) {
            // export VAR without value - mark as exported (keep existing)
            val key = parts[0].trim()
            if (shellEnv.containsKey(key)) {
                // Already set, nothing to do
            }
        }
    }

    /** cd builtin - sends cd command to the terminal session */
    private fun changeShellDirectory(raw: String) {
        val tm = terminalTabManager
        if (tm != null) {
            val target = when {
                raw.isBlank() -> (currentProjectDir ?: StorageHelper.projectsRoot(this)).absolutePath
                raw == "~" -> filesDir.absolutePath
                else -> raw
            }
            tm.executeCommand("cd $target && pwd")
        } else {
            val cwd = shellCwd ?: (currentProjectDir ?: StorageHelper.projectsRoot(this))
            val target = when {
                raw.isBlank() -> currentProjectDir ?: StorageHelper.projectsRoot(this)
                raw == "~" -> filesDir
                File(raw).isAbsolute -> File(raw)
                else -> File(cwd, raw)
            }
            val canonical = runCatching { target.canonicalFile }.getOrDefault(target)
            if (canonical.isDirectory) {
                shellCwd = canonical
            }
        }
    }

    private fun runGradleLikeCommandIfNeeded(
        command: String,
        cwd: File,
        onLine: (String) -> Unit
    ): ShellRunner.Result? {
        val trimmed = command.trim()
        if (trimmed.startsWith("./gradlew") || trimmed.startsWith("sh gradlew") || trimmed.startsWith("gradlew")) {
            val gradleArg = trimmed.substringAfter("gradlew").trim()
            if (this::gradleRunner.isInitialized) {
                showBottomPanel()
                switchToPanel("build")
                buildOutput.clear()
                buildTreeView.clear()
                buildTreeView.beginBuild(cwd.name)
                
                val tasks = listOf(gradleArg.ifBlank { "assembleDebug" })
                // This path never toggles setBuildRunning, so notify extensions directly.
                activeBuildTasks = tasks
                runCatching { ExtensionHost.dispatchBuildStartForAll(tasks) }
                gradleRunner.run(
                    projectDir = cwd,
                    tasks = tasks,
                    onLine = { line, level -> 
                        onLine(line)
                        buildOutput.appendLine(line, level)
                        buildMonitor.onLine(line, level)
                        if (line.startsWith("> Task :")) {
                            buildTreeView.beginTask(line.substringAfter("> Task "))
                        }
                    },
                    onDone = { result ->
                        buildTreeView.endBuild(result.success, result.durationMs)
                        runCatching { ExtensionHost.dispatchBuildFinishForAll(result.success) }
                    }
                )
                return ShellRunner.Result(exitCode = 0, outputLines = emptyList(), strategy = "gradle-runner")
            }
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "+x", File(cwd, "gradlew").absolutePath)).waitFor()
            } catch (_: Exception) {}
            return null
        }
        return null
    }

    private fun runPythonCommandIfNeeded(
        command: String,
        cwd: File,
        env: Map<String, String>,
        onLine: (String) -> Unit
    ): ShellRunner.Result? {
        val tokens = splitShellLike(command)
        val first = tokens.firstOrNull() ?: return null
        if (first != "python" && first != "python3") return null
        val python = findActivePythonBinary() ?: return ShellRunner.Result(
            exitCode = 127,
            outputLines = listOf("Python is not installed or active. Open Build Tools > Python first."),
            strategy = "python-native-exec",
            error = "python not installed"
        )
        return runDirectExecutable(
            executable = python,
            args = listOf(python.absolutePath) + tokens.drop(1),
            cwd = cwd,
            env = env,
            timeoutMs = 30L * 60L * 1000L,
            strategy = "python-native-exec",
            onLine = onLine
        )
    }

    private fun runDirectExecutable(
        executable: File,
        args: List<String>,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long,
        strategy: String,
        onLine: (String) -> Unit
    ): ShellRunner.Result {
        val elfInfo = ExecHelper.analyzeElf(executable)
        val isGlibc = elfInfo.interp?.contains("ld-linux") == true
        val isBionic = elfInfo.interp?.contains("linker") == true && !isGlibc

        // glibc ELF binaries must go through ExecHelper.createProcess (fork_loader/glibc loader)
        if (isGlibc) {
            return runGlibcExecutable(executable, args, cwd, env, timeoutMs, strategy, onLine)
        }

        val finalArgs: List<String>
        val finalEnv = env.toMutableMap()
        if (isBionic) {
            val linker = if (elfInfo.interp?.contains("linker64") == true) "/system/bin/linker64" else "/system/bin/linker"
            finalArgs = listOf(linker, executable.absolutePath) + args
            val ldPath = listOfNotNull(
                executable.parentFile?.absolutePath,
                applicationInfo.nativeLibraryDir,
                File(filesDir, "toolchain/runtime-tools/lib").absolutePath
            ).joinToString(":")
            finalEnv["LD_LIBRARY_PATH"] = if (finalEnv["LD_LIBRARY_PATH"].isNullOrEmpty()) ldPath
                                          else "$ldPath:${finalEnv["LD_LIBRARY_PATH"]}"
        } else {
            finalArgs = args
        }
        val handle = NativeExec.start(
            javaBin = if (isBionic) finalArgs[0] else executable.absolutePath,
            codeCacheDir = codeCacheDir.absolutePath,
            nativeLibDir = applicationInfo.nativeLibraryDir,
            args = finalArgs,
            envp = finalEnv.entries.map { (key, value) -> "$key=$value" },
            cwd = cwd.absolutePath
        )
        if (handle == 0L) {
            val error = NativeExec.getLastError()
            return ShellRunner.Result(127, listOf("NativeExec failed: $error"), strategy, error = error)
        }
        val lines = mutableListOf<String>()
        val buffer = StringBuilder()
        val start = System.currentTimeMillis()
        var timedOut = false
        while (true) {
            if (timeoutMs > 0 && System.currentTimeMillis() - start >= timeoutMs) {
                NativeExec.kill(handle)
                timedOut = true
                break
            }
            val chunk = NativeExec.readChunk(handle, 500) ?: break
            if (chunk.isBlank()) continue
            buffer.append(chunk.replace('\r', '\n'))
            while (true) {
                val newline = buffer.indexOf("\n")
                if (newline < 0) break
                val line = buffer.substring(0, newline).trimEnd()
                buffer.delete(0, newline + 1)
                if (line.isNotBlank()) {
                    lines += line
                    onLine(line)
                }
            }
        }
        if (buffer.isNotBlank()) {
            val line = buffer.toString().trimEnd()
            lines += line
            onLine(line)
        }
        val exit = if (timedOut) -1 else NativeExec.waitFor(handle)
        NativeExec.free(handle)
        return ShellRunner.Result(
            exitCode = exit,
            outputLines = if (timedOut) lines + "Timed out after ${timeoutMs / 1000}s" else lines,
            strategy = strategy,
            timedOut = timedOut,
            error = if (timedOut) "timeout" else ""
        )
    }

    private fun runGlibcExecutable(
        executable: File,
        args: List<String>,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long,
        strategy: String,
        onLine: (String) -> Unit
    ): ShellRunner.Result {
        val fullArgs = listOf(executable.absolutePath) + args
        val pb = ExecHelper.createProcess(this, fullArgs, cwd, env)
        pb.redirectErrorStream(true)
        return try {
            val proc = pb.start()
            val reader = proc.inputStream.bufferedReader()
            val lines = mutableListOf<String>()
            val start = System.currentTimeMillis()
            val buf = CharArray(8192)
            while (true) {
                if (timeoutMs > 0 && System.currentTimeMillis() - start >= timeoutMs) {
                    proc.destroyForcibly()
                    val msg = "Timed out after ${timeoutMs / 1000}s"
                    onLine(msg)
                    return ShellRunner.Result(-1, lines + msg, strategy, timedOut = true, error = "timeout")
                }
                val n = reader.read(buf)
                if (n <= 0) break
                val chunk = String(buf, 0, n).replace('\r', '\n')
                val parts = chunk.split("\n")
                for (part in parts) {
                    if (part.isNotBlank()) {
                        lines += part
                        onLine(part)
                    }
                }
            }
            proc.waitFor(1, java.util.concurrent.TimeUnit.MINUTES)
            val exit = proc.exitValue()
            ShellRunner.Result(exitCode = exit, outputLines = lines, strategy = strategy)
        } catch (e: Exception) {
            ShellRunner.Result(127, listOf("Exec failed: ${e.message}"), strategy, error = e.message ?: "")
        }
    }

    private fun findActivePythonBinary(): File? {
        val toolchain = ToolchainManager(this)
        val roots = buildList {
            toolchain.activeDir(ToolchainManager.Kind.PYTHON)?.let { add(it) }
            File(filesDir, "toolchain/python").listFiles()?.forEach { if (it.isDirectory) add(it) }
        }
        val found = roots.asSequence()
            .flatMap { root -> sequenceOf(File(root, "bin/python3"), File(root, "bin/python")) }
            .firstOrNull { it.isFile && it.length() > 0L }
        if (found != null) return found
        // Fallback: Termux python
        val termuxPython = File("/data/data/com.termux/files/usr/bin/python3")
        if (termuxPython.isFile && termuxPython.canExecute()) return termuxPython
        return null
    }

    private fun splitShellLike(command: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escape = false
        command.forEach { char ->
            when {
                escape -> {
                    current.append(char)
                    escape = false
                }
                char == '\\' -> escape = true
                quote != null -> {
                    if (char == quote) quote = null else current.append(char)
                }
                char == '\'' || char == '"' -> quote = char
                char.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun appendShellLine(output: TextView, line: String) {
        output.append("\n$line")
        val max = 30_000
        val len = output.length()
        if (len > max + 5_000) {
            val text = output.text?.toString() ?: return
            val cut = text.indexOf('\n', text.length - max)
            output.text = if (cut >= 0) text.substring(cut + 1) else text.takeLast(max)
        }
    }

    /**
     * Appends a Linux/Termux-style prompt line: the abbreviated working directory
     * in dark blue followed by `>` and the typed command (default color).
     */
    private fun appendShellPrompt(output: TextView, cwd: File, command: String) {
        val prompt = NdkToolchain.promptFor(cwd) + " "
        val spannable = SpannableString(prompt + command)
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor(NdkToolchain.PROMPT_COLOR)),
            0, prompt.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        NdkToolchain.colorizeCommand(command).forEach { span ->
            spannable.setSpan(
                ForegroundColorSpan(Color.parseColor(span.color)),
                prompt.length + span.start, prompt.length + span.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        output.append("\n")
        output.append(spannable)
    }

    private fun buildIntegratedShellEnv(): Map<String, String> {
        val toolchain = ToolchainManager(this)
        val jdk = toolchain.activeJdkHome()
        val sdk = toolchain.activeDir(ToolchainManager.Kind.SDK)
        val ndk = toolchain.activeDir(ToolchainManager.Kind.NDK)
        val ndkDirs = collectInstalledNdkDirs(sdk, ndk)
        val gradle = toolchain.activeDir(ToolchainManager.Kind.GRADLE)
        val python = toolchain.activeDir(ToolchainManager.Kind.PYTHON)

        val path = buildList {
            // Priority 1: ARM64 toolchain (LLVM, clang, lld)
            NdkToolchain.discoverInstalled(this@MainActivity).forEach { ndkDir ->
                add(NdkToolchain.llvmBinDir(ndkDir).absolutePath)
            }
            
            // Priority 2: StudioShellTools (git, curl, node, etc.)
            StudioShellTools.installedBinDirs(this@MainActivity).forEach { add(it.absolutePath) }
            
            // Priority 3: JDK, Gradle, Python
            jdk?.let { add(File(it, "bin").absolutePath) }
            gradle?.let { add(File(it, "bin").absolutePath) }
            python?.let {
                add(File(it, "bin").absolutePath)
                add(File(it, "local/bin").absolutePath)
            }
            
            // Priority 4: System
            add("/system/bin")
            add("/system/xbin")
            
            // Project dir
            currentProjectDir?.let { add(it.absolutePath) }
        }.distinct().joinToString(":")

        val ldPath = buildList {
            // ARM64 toolchain libs
            NdkToolchain.discoverInstalled(this@MainActivity).forEach { ndkDir ->
                val llvmHome = NdkToolchain.llvmHomeDir(ndkDir)
                add(File(llvmHome, "lib").absolutePath)
                add(File(llvmHome, "lib64").absolutePath)
            }
            
            // StudioShellTools libs
            StudioShellTools.installedLibDirs(this@MainActivity).forEach { add(it.absolutePath) }
            
            // System libs
            add(applicationInfo.nativeLibraryDir)
            add("/system/lib64")
            add("/system/lib")
        }.distinct().filter { File(it).isDirectory }.joinToString(":")

        val env = mutableMapOf<String, String>().apply {
            put("PATH", path)
            put("LD_LIBRARY_PATH", ldPath)
            put("HOME", filesDir.absolutePath)
            put("TMPDIR", cacheDir.absolutePath)
            put("TERM", "xterm-256color")
            put("LANG", "en_US.UTF-8")
            
            jdk?.let { put("JAVA_HOME", it.absolutePath) }
            gradle?.let { put("GRADLE_HOME", it.absolutePath) }
            currentProjectDir?.let { put("PROJECT_DIR", it.absolutePath) }
        }

        // Apply NDK environment (CC, CXX, flags)
        NdkToolchain.discoverInstalled(this).firstOrNull()?.let { ndkDir ->
            NdkToolchain.applyNdkEnv(env, ndkDir)
        }
        
        return env
    }

    private fun collectInstalledNdkDirs(sdk: File?, activeNdk: File?): List<File> {
        val candidates = buildList {
            activeNdk?.let { add(it) }
            File(filesDir, "toolchain/ndk").listFiles()?.forEach { if (it.isDirectory) add(it) }
            File(filesDir, "toolchain/sdk").listFiles()?.forEach { sdkDir ->
                if (sdkDir.isDirectory) {
                    File(sdkDir, "ndk").listFiles()?.forEach { if (it.isDirectory) add(it) }
                }
            }
            sdk?.let {
                File(it, "ndk").listFiles()?.forEach { ndkDir -> if (ndkDir.isDirectory) add(ndkDir) }
            }
        }
        return candidates
            .map { runCatching { it.canonicalFile }.getOrDefault(it) }
            .distinctBy { it.absolutePath }
            .filter { File(it, "source.properties").exists() || File(it, "toolchains/llvm").exists() }
    }

    // MobileStudio comment
    // APK Install
    // MobileStudio comment

    private fun installLatestApk() {
        val apk = findLatestApk()
        if (apk == null) {
            showToast("No APK found. Build first."); return
        }
        installApk(apk)
    }

    private fun findLatestApk(): File? = BuildApkLocator.latestInstallableApk(currentProjectDir)

    private fun findLatestJar(): File? {
        val root = currentProjectDir ?: return null
        val buildLibs = "${File.separator}build${File.separator}libs${File.separator}"
        return root.walkTopDown()
            .filter {
                it.isFile &&
                    it.extension.equals("jar", ignoreCase = true) &&
                    it.absolutePath.contains(buildLibs)
            }
            .maxByOrNull { it.lastModified() }
    }

    private fun installApk(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.provider", apk
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            ErrorLogDialog.show(
                this,
                if (AppLanguage.isKorean(this)) "설치 실패" else "Install Failed",
                "Install failed: ${e.message}"
            )
        }
    }

    // ────────────────────────────────────────────────────────
    // Breadcrumb
    // ────────────────────────────────────────────────────────

    private fun setupBreadcrumb() {}

    private fun updateBreadcrumb() {
        val file = currentFile ?: return
        val project = currentProjectDir ?: return
        breadcrumb.removeAllViews()
        val relative = try {
            file.relativeTo(project).path
        } catch (_: Exception) {
            file.name
        }
        val parts = relative.split(File.separator)
            parts.forEachIndexed { idx, part ->
                if (idx > 0) {
                    breadcrumb.addView(TextView(this).apply {
                        text = " / "; textSize = 11f; setTextColor(StudioUi.color("#4B5263"))
                    })
                }
            breadcrumb.addView(TextView(this).apply {
                text = part; textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(
                    if (idx == parts.size - 1) StudioUi.color("#ABB2BF")
                    else StudioUi.color("#636D83")
                )
            })
        }
    }

    // MobileStudio comment
    // Project management
    // MobileStudio comment

    internal fun showNewProjectDialog() {
        NewProjectDialog.show(this, generator) { dir, _ -> openProject(dir) }
    }

    internal fun launchAiAssistant() {
        openAiWorkspace()
    }

    internal fun openProject(dir: File) = openProject(dir, askConfirmation = true)

    /**
     * Opens [dir] as the active project.
     *
     * Projects join the tab strip instead of replacing it, so several can be
     * open at once (tap a tab to switch, long-press to close). Nothing is
     * closed behind the user's back, which is why the old "the project that is
     * currently open will be closed" confirmation is gone.
     *
     * @param askConfirmation kept for call-site compatibility; switching no
     *   longer destroys the previous project, so there is nothing to confirm
     */
    internal fun openProject(dir: File, @Suppress("UNUSED_PARAMETER") askConfirmation: Boolean) {
        val targetDir = dir
        saveCurrentFile()
        closeCurrentProjectState()
        currentProjectDir = targetDir
        EditorPaneState.projectRevision.value += 1
        com.aide.mobilestudio.ai.agent.ToolRegistry.setCurrentProjectDir(targetDir)
        invalidateSymbolFileCache()
        currentProjectKind = detectProjectKind(targetDir)
        saveLastProject(targetDir)
        StorageHelper.pushRecentProject(this, targetDir.absolutePath)
        if (projectTabs.none { sameFile(it, targetDir) }) projectTabs.add(targetDir)
        refreshProjectTabs()
        if (::tvProjectName.isInitialized) tvProjectName.text = targetDir.name
        if (::tvDrawerTitle.isInitialized) tvDrawerTitle.text = targetDir.name
        updateBuildOptionLabel()
        updateProjectModeUi()
        File(targetDir, "gradlew").takeIf { it.exists() }?.setExecutable(true, false)
        // Always populate the Compose file tree; `fileAdapter` is only bound
        // in the removed View-based bindViews(), so gating on it left the
        // drawer empty when a project was first opened.
        loadFileTree(targetDir)
        if (::drawerLayout.isInitialized) drawerLayout.closeDrawer(GravityCompat.START)
        if (::autoComplete.isInitialized) autoComplete.setProjectDir(targetDir)
        gradleSyncNeeded = currentProjectKind != ProjectKind.GODOT
        checkProjectUpdates()

        if (::tvStatusRight.isInitialized) {
            if (::tvStatusRight.isInitialized) tvStatusRight.text = if (currentProjectKind == ProjectKind.GODOT) "Godot" else "Sync Now"
            if (::tvStatusRight.isInitialized) tvStatusRight.setTextColor(StudioUi.color(if (currentProjectKind == ProjectKind.GODOT) "#61AFEF" else StudioUi.Amber))
        }

        // Live edit: watch project files.
        fileWatcher?.stop()
        fileWatcher = FileWatcher(targetDir) { changedFiles ->
            if (changedFiles.any { isGradleSyncFile(it) }) {
                runOnUiThread { markGradleSyncNeeded(changedFiles.firstOrNull { isGradleSyncFile(it) }) }
            }
            if (currentProjectKind != ProjectKind.GODOT) {
                if (currentProjectKind == ProjectKind.PYTHON) {
                    schedulePythonDiagnostics(changedFiles)
                } else if (::buildScheduler.isInitialized) {
                    buildScheduler.schedule(changedFiles)
                }
            }
        }.also { it.start() }
        if (currentProjectKind != ProjectKind.GODOT && ::buildScheduler.isInitialized && fileWatcher != null) {
            buildScheduler.attachTo(fileWatcher!!, targetDir)
            scheduleInitialProjectDiagnostics(targetDir)
        }
        
        // Start a fresh real-time analysis engine for this project; the old
        // one's scope/threads/FileObservers are cancelled by stopAll().
        analysisEngine?.stopAll()
        val analysisConfig = com.aide.mobilestudio.analysis.AnalysisConfig(
            debounceMs = 300L,
            enableQuickSyntaxCheck = true,
            enableKotlinCompiler = true,
            enableKotlinDaemon = true,
            daemonHeapMb = 64,
            daemonIdleTimeoutMs = 60_000L,
            compilerTimeoutMs = 10_000L,
            cacheTtlMs = 30_000L
        )
        analysisEngine = com.aide.mobilestudio.analysis.AnalysisEngine(this, analysisConfig).apply {
            startWatching(targetDir)
            // Register listener for diagnostics updates
            editorDiagnostics.addListener(object : com.aide.mobilestudio.analysis.EditorDiagnostics {
                override fun onDiagnosticsUpdated(file: java.io.File, diagnostics: List<com.aide.mobilestudio.analysis.Diagnostic>, source: String, analysisTimeMs: Long) {
                    runOnUiThread {
                        if (file == currentFile) {
                            // The diagnostics were computed against [source];
                            // if the editor has moved on (user typed/deleted
                            // while the compiler ran), the line/column spans
                            // would land on the wrong text — drop the result
                            // instead of painting stale squiggles.
                            val editorText = if (::codeEditor.isInitialized) codeEditor.text?.toString() else null
                            if (source != editorText) return@runOnUiThread
                            liveErrors = diagnostics.map { diag ->
                                BuildProblem(
                                    file = file,
                                    line = diag.line,
                                    column = diag.column,
                                    severity = when (diag.severity) {
                                        com.aide.mobilestudio.analysis.DiagnosticSeverity.ERROR -> BuildProblem.Severity.ERROR
                                        com.aide.mobilestudio.analysis.DiagnosticSeverity.WARNING -> BuildProblem.Severity.WARNING
                                        else -> BuildProblem.Severity.INFO
                                    },
                                    message = diag.message,
                                    raw = diag.rawText ?: diag.message
                                )
                            }
                            refreshProblems()
                            // Update inline error display
                            updateInlineDiagnostics(diagnostics, source)
                        }
                    }
                }

                override fun onAnalysisStarted(file: java.io.File) {
                    runOnUiThread {
                        if (file == currentFile && ::tvStatusRight.isInitialized) {
                            if (::tvStatusRight.isInitialized) tvStatusRight.text = "Analyzing..."
                            if (::tvStatusRight.isInitialized) tvStatusRight.setTextColor(StudioUi.color(StudioUi.Amber))
                        }
                    }
                }

                override fun onAnalysisFinished(file: java.io.File) {
                    runOnUiThread {
                        if (file == currentFile && ::tvStatusRight.isInitialized) {
                            val summary = analysisEngine?.getDiagnosticsForFile(file).orEmpty()
                            val errCount = summary.count { it.severity == com.aide.mobilestudio.analysis.DiagnosticSeverity.ERROR }
                            val warnCount = summary.count { it.severity == com.aide.mobilestudio.analysis.DiagnosticSeverity.WARNING }
                            if (::tvStatusRight.isInitialized) tvStatusRight.text = if (errCount == 0 && warnCount == 0) "No errors" else "$errCount error(s), $warnCount warning(s)"
                            if (::tvStatusRight.isInitialized) tvStatusRight.setTextColor(StudioUi.color(if (errCount == 0) "#98C379" else "#E06C75"))
                        }
                    }
                }

                override fun onClearDiagnostics(file: java.io.File) {
                    runOnUiThread {
                        if (file == currentFile) {
                            liveErrors = emptyList()
                            refreshProblems()
                            // Clear inline error spans
                            val spannable = codeEditor.text
                            if (spannable is android.text.Spannable) {
                                spannable.getSpans(0, spannable.length, BackgroundColorSpan::class.java).forEach { spannable.removeSpan(it) }
                                spannable.getSpans(0, spannable.length, ForegroundColorSpan::class.java).forEach { spannable.removeSpan(it) }
                                spannable.getSpans(0, spannable.length, SquigglyUnderlineSpan::class.java).forEach { spannable.removeSpan(it) }
                                spannable.getSpans(0, spannable.length, DiagnosticClickableSpan::class.java).forEach { spannable.removeSpan(it) }
                            }
                        }
                    }
                }

                override fun onProjectDiagnosticsUpdate(projectDir: java.io.File, errors: Int, warnings: Int, infos: Int) {
                    // Could update project-wide error count in status bar
                }
            })
        }

        // Extension lifecycle: onProjectOpen (project is fully wired up by now).
        runCatching { ExtensionHost.dispatchProjectOpenForAll(targetDir) }
    }

    private fun closeCurrentProjectState() {
        // Extension lifecycle: onProjectClose, but only if one was actually open.
        if (currentProjectDir != null) runCatching { ExtensionHost.dispatchProjectCloseForAll() }
        autoSaveJob?.cancel()
        liveDiagnosticsJob?.cancel()
        pythonDiagnosticsJob?.cancel()
        fileWatcher?.stop()
        analysisEngine?.stopAll()
        analysisEngine = null
        currentFile = null
        expandedFileTreePaths.clear()
        openTabs.clear()
        unsavedFiles.clear()
        tabScrollPositions.clear()
        tabCursorPositions.clear()
        unsavedChanges = false
        liveErrors = emptyList()
        buildErrors = emptyList()
        refreshTabs()
        refreshProblems()
    }

    private fun sameFile(left: File, right: File): Boolean {
        val leftPath = runCatching { left.canonicalPath }.getOrDefault(left.absolutePath)
        val rightPath = runCatching { right.canonicalPath }.getOrDefault(right.absolutePath)
        return leftPath == rightPath
    }

    private fun detectProjectKind(root: File): ProjectKind {
        if (File(root, "project.godot").isFile) return ProjectKind.GODOT
val hasAndroidManifest = File(root, "app/src/main/AndroidManifest.xml").isFile ||
            File(root, "src/main/AndroidManifest.xml").isFile
        val hasAndroidBuild = File(root, "settings.gradle").isFile &&
            (File(root, "app/build.gradle").isFile || File(root, "app/build.gradle.kts").isFile)
        val hasNativeAndroidBuild = File(root, "app/src/main/cpp/CMakeLists.txt").isFile ||
            root.walkTopDown().maxDepth(5).any { file ->
                file.isFile && (
                    file.name == "CMakeLists.txt" ||
                        (file.name == "build.gradle" || file.name == "build.gradle.kts") &&
                        runCatching { file.readText() }.getOrDefault("").contains("externalNativeBuild")
                )
            }
        // Game project detection: has game_math.h and game target in CMakeLists
        val isGameProject = (File(root, "app/src/main/cpp/game_math.h").isFile ||
            File(root, "src/main/cpp/game_math.h").isFile ||
            File(root, "app/src/main/cpp/vulkan_renderer.h").isFile ||
            File(root, "src/main/cpp/vulkan_renderer.h").isFile) &&
            (File(root, "app/src/main/cpp/CMakeLists.txt").isFile ||
                File(root, "src/main/cpp/CMakeLists.txt").isFile) &&
            runCatching {
                File(root, "app/src/main/cpp/CMakeLists.txt").readText()
            }.getOrDefault("").contains("add_library(game")
            || runCatching {
                File(root, "src/main/cpp/CMakeLists.txt").readText()
            }.getOrDefault("").contains("add_library(game")
            || runCatching {
                File(root, "app/src/main/cpp/CMakeLists.txt").readText()
            }.getOrDefault("").contains("add_library(vulkan_game")
        if (isGameProject) return ProjectKind.GAME
        if (hasAndroidManifest || hasAndroidBuild || hasNativeAndroidBuild) return ProjectKind.ANDROID
        // Lua/JS script-only game project: .lua/.js game script in root or assets (no Gradle)
        val hasLuaGameScript = root.listFiles()?.any {
            it.isFile && (it.extension.equals("lua", true) || it.extension.equals("js", true)) &&
                (it.name.startsWith("main.", true) || it.name.startsWith("game.", true) || it.name == "conf.lua")
        } == true
        if (hasLuaGameScript) return ProjectKind.LUA_GAME
        // Windows .exe project: README marker + C/C++ source in root
        val isExeProject = File(root, "README.md").let { readme ->
            readme.isFile && runCatching { readme.readText() }.getOrDefault("").contains("# Windows .exe Project")
        } && (File(root, "main.c").isFile || File(root, "main.cpp").isFile || File(root, "main.cc").isFile)
        if (isExeProject) return ProjectKind.EXE
        if (File(root, "buildozer.spec").isFile ||
            File(root, "pyproject.toml").isFile ||
            root.listFiles()?.any { it.isFile && it.extension.equals("py", ignoreCase = true) } == true
        ) return ProjectKind.PYTHON
        if (File(root, "build.gradle").isFile || File(root, "build.gradle.kts").isFile) return ProjectKind.JAR
        return ProjectKind.ANDROID
    }

    private fun updateProjectModeUi() {
        val isPython = currentProjectKind == ProjectKind.PYTHON
        val isJar = currentProjectKind == ProjectKind.JAR
        val isGodot = currentProjectKind == ProjectKind.GODOT
        (findViewById<View>(R.id.tools_btn_run)?.parent as? View)?.visibility = View.VISIBLE
        findViewById<TextView>(R.id.tools_build_option_label)?.visibility = if (isPython || isGodot) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tools_btn_clean)?.visibility = if (isPython || isGodot) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tools_btn_apk)?.visibility = if (isPython || isGodot) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tab_build)?.text = when {
            isPython -> ui("Compile")
            currentProjectKind == ProjectKind.EXE -> ui("Build .exe")
            isJar -> ui("Build")
            currentProjectKind == ProjectKind.GAME -> ui("Build APK")
            isGodot -> ui("Editor")
            else -> ui("Build")
        }
        findViewById<TextView>(R.id.tab_logcat)?.text = if (isPython) ui("Outputs") else ui("Logcat")
        findViewById<TextView>(R.id.tab_problems)?.text = ui("Problems")
        findViewById<TextView>(R.id.tab_shell)?.text = ui("Shell")
        if (::tvStatusLang.isInitialized) {
            if (::tvStatusLang.isInitialized) tvStatusLang.text = when (currentProjectKind) {
                ProjectKind.PYTHON -> "Python"
                ProjectKind.JAR -> "Java/JAR"
                ProjectKind.EXE -> "C/C++"
                ProjectKind.GAME -> "Game"
                ProjectKind.ANDROID -> "Android"
                ProjectKind.LUA_GAME -> "Lua Game"
                ProjectKind.GODOT -> "Godot"
            }
        }
    }

    private fun isAppDataExplorerMode(): Boolean {
        val projectDir = currentProjectDir ?: return false
        return sameFile(projectDir, File(applicationInfo.dataDir))
    }

    private fun scheduleInitialProjectDiagnostics(projectDir: File) {
        if (currentProjectKind == ProjectKind.GODOT) return
        scope.launch {
            delay(1_200)
            if (currentProjectDir != projectDir || isBuildRunning) return@launch
            if (currentProjectKind == ProjectKind.PYTHON) {
                schedulePythonDiagnostics(null)
                return@launch
            }
            if (!jreGradleInstaller.isInstalled) {
                setStatus("Realtime diagnostics ready after toolchain install")
                return@launch
            }
            // Real-time compilation is done by AnalysisEngine (javac + Kotlin
            // compiler), NEVER by Gradle. Gradle only runs on explicit Build.
            // Forcing a Gradle build here crashed with
            // UninitializedPropertyAccessException (View status views are gone
            // in the Compose shell) and caused an endless recreate loop.
            setStatus("Realtime diagnostics active")
        }
    }

    private fun schedulePythonDiagnostics(changedFiles: Set<File>?) {
        val project = currentProjectDir ?: return
        pythonDiagnosticsJob?.cancel()
        pythonDiagnosticsJob = scope.launch {
            delay(450)
            val files = changedFiles
                ?.filter { it.isFile && it.extension.equals("py", ignoreCase = true) }
                ?.takeIf { it.isNotEmpty() }
                ?: project.walkTopDown()
                    .filter { it.isFile && it.extension.equals("py", ignoreCase = true) }
                    .filterNot { it.invariantSeparatorsPath.contains("/.buildozer/") }
                    .take(80)
                    .toList()
            if (files.isEmpty()) {
                liveErrors = emptyList()
                refreshProblems()
                return@launch
            }
            val command = "python3 -m py_compile " + files.joinToString(" ") { shellQuote(it.absolutePath) }
            val result = withContext(Dispatchers.IO) {
                ShellRunner.runSh(
                    context = this@MainActivity,
                    command = command,
                    cwd = project,
                    env = buildIntegratedShellEnv(),
                    timeoutMs = 60_000L,
                    onLine = {}
                )
            }
            liveErrors = parsePythonProblems(result.outputLines.joinToString("\n"), result.exitCode, project)
            refreshProblems()
            setStatus(if (liveErrors.isEmpty()) "Python compile: no problems" else "Python compile: ${liveErrors.size} problem(s)")
        }
    }

    private fun parsePythonProblems(output: String, exitCode: Int, project: File): List<BuildProblem> {
        if (exitCode == 0) return emptyList()
        val fileMatch = Regex("""File "(.+?)", line (\d+)""").find(output)
        val message = output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.endsWith("Error") || it.contains("Error:") }
            ?: output.lineSequence().lastOrNull()?.trim().orEmpty().ifBlank { "Python compile failed" }
        val file = fileMatch?.groupValues?.getOrNull(1)?.let { File(it) }
        val line = fileMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        return listOf(
            BuildProblem(
                file = file?.takeIf { it.exists() },
                line = line,
                column = 0,
                severity = BuildProblem.Severity.ERROR,
                message = message,
                raw = output.ifBlank { "python3 -m py_compile failed with exit=$exitCode" }
            )
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    private fun showOpenProjectPicker() {
        val projects = StorageHelper.listProjects(this)
        val labels = projects.map { it.name } + listOf(
            "App data file explorer",
            "Import project from /sdcard..."
        )
        AlertDialog.Builder(this).setTitle("Open project")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < projects.size -> openProject(projects[i])
                    i == projects.size -> openAppDataExplorer()
                    else -> startExternalProjectPicker()
                }
            }
            .show()
    }

    private fun openAppDataExplorer() {
        val root = File(applicationInfo.dataDir).takeIf { it.exists() }
            ?: File("/data/data/$packageName")
        val current = currentProjectDir
        if (current != null && !sameFile(current, root)) {
            AlertDialog.Builder(this)
                .setMessage("The project that is currently open will be closed. Do you want to continue?")
                .setPositiveButton("Yes") { _, _ -> openAppDataExplorerInternal(root) }
                .setNegativeButton("No", null)
                .show()
            return
        }
        openAppDataExplorerInternal(root)
    }

    private fun openAppDataExplorerInternal(root: File) {
        closeCurrentProjectState()
        currentProjectDir = root
        invalidateSymbolFileCache()
        if (::tvProjectName.isInitialized) tvProjectName.text = "App data"
        if (::tvDrawerTitle.isInitialized) tvDrawerTitle.text = root.absolutePath
        loadFileTree(root)
        if (::drawerLayout.isInitialized) drawerLayout.openDrawer(GravityCompat.START)
        setStatus("App data explorer: ${root.absolutePath}")
    }

    private fun startExternalProjectPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        runCatching { startActivityForResult(intent, REQ_OPEN_PROJECT_TREE) }
            .onFailure { showToast("Folder picker unavailable: ${it.message}") }
    }

    private fun importExternalProject(treeUri: Uri) {
        val rootDoc = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val name = queryDocumentName(rootDoc)
            ?.let { sanitizeProjectName(it) }
            ?.takeIf { it.isNotBlank() }
            ?: "ImportedProject"
        val targetDir = File(StorageHelper.projectsRoot(this), name)
        val importAction = {
            setStatus("Importing /sdcard project...")
            scope.launch(Dispatchers.IO) {
                val error = runCatching {
                    targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    copyDocumentTree(rootDoc, targetDir)
                    File(targetDir, "gradlew").takeIf { it.exists() }?.setExecutable(true, false)
                }.exceptionOrNull()
                withContext(Dispatchers.Main) {
                    if (error == null) {
                        openProject(targetDir, askConfirmation = false)
                        refreshRecentProjects(findViewById(R.id.rv_recent_projects))
                        setStatus("Imported: ${targetDir.name}")
                    } else {
                        setStatus("Import failed: ${error.message}")
                    }
                }
            }
            Unit
        }

        if (currentProjectDir != null && !sameFile(currentProjectDir!!, targetDir)) {
            AlertDialog.Builder(this)
                .setMessage("The project that is currently open will be closed. Do you want to continue?")
                .setPositiveButton("Yes") { _, _ ->
                    confirmReplaceImportedProject(targetDir, importAction)
                }
                .setNegativeButton("No", null)
                .show()
        } else {
            confirmReplaceImportedProject(targetDir, importAction)
        }
    }

    private fun confirmReplaceImportedProject(targetDir: File, importAction: () -> Unit) {
        if (!targetDir.exists()) {
            importAction()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Replace project?")
            .setMessage("MobileStudio/${targetDir.name} already exists. Replace it with the selected /sdcard project?")
            .setPositiveButton("Replace") { _, _ -> importAction() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun queryDocumentName(documentUri: Uri): String? {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun copyDocumentTree(documentUri: Uri, target: File) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(documentUri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) error("Cannot read selected folder")
            val documentId = cursor.getString(0)
            val mime = cursor.getString(2)
            if (mime != DocumentsContract.Document.MIME_TYPE_DIR) error("Select a project folder")
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(documentUri, documentId)
            contentResolver.query(childrenUri, projection, null, null, null)?.use { children ->
                while (children.moveToNext()) {
                    val childId = children.getString(0)
                    val childName = sanitizeFileName(children.getString(1))
                    val childMime = children.getString(2)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(documentUri, childId)
                    copyDocumentNode(childUri, File(target, childName), childMime)
                }
            }
        } ?: error("Cannot access selected folder")
    }

    private fun copyDocumentNode(documentUri: Uri, target: File, knownMime: String?) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val mime = knownMime ?: contentResolver.query(documentUri, projection, null, null, null)?.use {
            if (it.moveToFirst()) it.getString(2) else null
        }
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            target.mkdirs()
            val documentId = DocumentsContract.getDocumentId(documentUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(documentUri, documentId)
            contentResolver.query(childrenUri, projection, null, null, null)?.use { children ->
                while (children.moveToNext()) {
                    val childId = children.getString(0)
                    val childName = sanitizeFileName(children.getString(1))
                    val childMime = children.getString(2)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(documentUri, childId)
                    copyDocumentNode(childUri, File(target, childName), childMime)
                }
            }
        } else {
            target.parentFile?.mkdirs()
            contentResolver.openInputStream(documentUri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Cannot read file: ${target.name}")
            if (target.name == "gradlew") target.setExecutable(true, false)
        }
    }

    private fun sanitizeProjectName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "ImportedProject" }

    private fun sanitizeFileName(name: String): String =
        name.replace('/', '_').replace('\\', '_').ifBlank { "unnamed" }

    private fun copyDirectory(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.list()?.forEach { copyDirectory(File(source, it), File(target, it)) }
        } else {
            source.inputStream().use { i -> target.outputStream().use { i.copyTo(it) } }
            if (target.name == "gradlew") target.setExecutable(true, false)
        }
    }

    // MobileStudio comment
    // File management
    // MobileStudio comment

    private data class FileTemplateOption(
        val label: String,
        val kind: String,
        val defaultName: String
    )

    private val fileTemplateOptions = listOf(
        FileTemplateOption("Empty file", "empty", "new_file.txt"),
        FileTemplateOption("Kotlin file", "kotlin_file", "NewFile.kt"),
        FileTemplateOption("Kotlin class", "kotlin_class", "NewClass.kt"),
        FileTemplateOption("Java file", "java_file", "NewFile.java"),
        FileTemplateOption("Java class", "java_class", "NewClass.java"),
        FileTemplateOption("Android Activity", "activity", "NewActivity.kt"),
        FileTemplateOption("Fragment", "fragment", "NewFragment.kt"),
        FileTemplateOption("ViewModel", "viewmodel", "NewViewModel.kt"),
        FileTemplateOption("Repository", "repository", "NewRepository.kt"),
        FileTemplateOption("C source file", "c_source", "native_file.c"),
        FileTemplateOption("C header file", "c_header", "native_file.h"),
        FileTemplateOption("C++ source file", "cpp_source", "NativeFile.cpp"),
        FileTemplateOption("C++ header file", "cpp_header", "NativeFile.hpp"),
        FileTemplateOption("C/C++ class pair", "cpp_class", "NativeClass"),
        FileTemplateOption("CMakeLists.txt", "cmake", "CMakeLists.txt"),
        FileTemplateOption("XML layout", "xml_layout", "activity_new.xml"),
        FileTemplateOption("Drawable shape", "drawable_shape", "bg_card.xml"),
        FileTemplateOption("Values XML", "values_xml", "strings_extra.xml"),
        FileTemplateOption("Assets text file", "asset_file", "data.json")
    )

    private fun showNewFileDialogInDir(dir: File, preferredKind: String? = null) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 16, 32, 8)
        }
        val preferredIndex = fileTemplateOptions.indexOfFirst { it.kind == preferredKind }.takeIf { it >= 0 } ?: 0
        val etName = EditText(this).apply {
            hint = "File name"
            setText(fileTemplateOptions[preferredIndex].defaultName)
            setTextColor(Color.WHITE)
            setHintTextColor(StudioUi.color("#4B5263"))
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                R.layout.spinner_dark_item,
                fileTemplateOptions.map { it.label }
            ).also { it.setDropDownViewResource(R.layout.spinner_dark_dropdown_item) }
            setSelection(preferredIndex)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val current = etName.text.toString()
                    if (current.isBlank() || fileTemplateOptions.any { it.defaultName == current }) {
                        etName.setText(fileTemplateOptions[position].defaultName)
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        root.addView(TextView(this).apply {
            text = "File name"
            setTextColor(StudioUi.color(StudioUi.TextSoft))
        })
        root.addView(etName)
        root.addView(TextView(this).apply {
            text = "Template"
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 12, 0, 4)
        })
        root.addView(spinner)

        AlertDialog.Builder(this).setTitle("New file").setView(root)
            .setPositiveButton("Create") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { return@setPositiveButton }
                val option = fileTemplateOptions[spinner.selectedItemPosition]
                val file = createRichTemplateFiles(dir, name, option)
                expandedFileTreePaths.add(canonicalPath(dir))
                loadFileTree(currentProjectDir ?: dir)
                openFile(file)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createRichTemplateFiles(dir: File, rawName: String, option: FileTemplateOption): File {
        val pkg = currentProjectDir?.let { detectPackage(it) } ?: "com.example.app"
        if (option.kind == "cpp_class") {
            val className = toClassName(rawName.substringBeforeLast('.'))
            val header = File(dir, "$className.hpp")
            val source = File(dir, "$className.cpp")
            header.writeText(generateRichFileTemplate(header.name, "cpp_header", pkg, className))
            source.writeText(generateRichFileTemplate(source.name, "cpp_class_source", pkg, className))
            return source
        }
        val file = File(dir, normalizeTemplateFileName(rawName, option))
        file.parentFile?.mkdirs()
        file.writeText(generateRichFileTemplate(file.name, option.kind, pkg))
        return file
    }

    private fun normalizeTemplateFileName(name: String, option: FileTemplateOption): String {
        if (option.kind == "cmake") return "CMakeLists.txt"
        val ext = when (option.kind) {
            "kotlin_file", "kotlin_class", "activity", "fragment", "viewmodel", "repository" -> ".kt"
            "java_file", "java_class" -> ".java"
            "c_source" -> ".c"
            "c_header" -> ".h"
            "cpp_source" -> ".cpp"
            "cpp_header" -> ".hpp"
            "xml_layout", "drawable_shape", "values_xml" -> ".xml"
            "asset_file" -> ""
            else -> ""
        }
        return if (ext.isNotEmpty() && !name.endsWith(ext, ignoreCase = true)) "$name$ext" else name
    }

    private fun generateRichFileTemplate(name: String, kind: String, pkg: String, explicitClassName: String? = null): String {
        val className = explicitClassName ?: toClassName(name.substringBeforeLast("."))
        val guard = className.uppercase().replace(Regex("[^A-Z0-9_]"), "_") + "_H"
        return when (kind) {
            "kotlin_file" -> "package $pkg\n\n"
            "kotlin_class" -> "package $pkg\n\nclass $className {\n    \n}\n"
            "java_file" -> "package $pkg;\n\n"
            "java_class" -> "package $pkg;\n\npublic class $className {\n    \n}\n"
            "activity" -> "package $pkg\n\nimport android.os.Bundle\nimport androidx.appcompat.app.AppCompatActivity\n\nclass $className : AppCompatActivity() {\n    override fun onCreate(savedInstanceState: Bundle?) {\n        super.onCreate(savedInstanceState)\n    }\n}\n"
            "fragment" -> "package $pkg\n\nimport android.os.Bundle\nimport android.view.LayoutInflater\nimport android.view.View\nimport android.view.ViewGroup\nimport androidx.fragment.app.Fragment\n\nclass $className : Fragment() {\n    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {\n        return null\n    }\n}\n"
            "viewmodel" -> "package $pkg\n\nimport androidx.lifecycle.ViewModel\n\nclass $className : ViewModel() {\n    \n}\n"
            "repository" -> "package $pkg\n\nclass $className {\n    \n}\n"
            "c_source" -> "#include <stdio.h>\n\nint main(void) {\n    printf(\"Hello from MobileStudio C\\n\");\n    return 0;\n}\n"
            "c_header" -> "#ifndef $guard\n#define $guard\n\n#endif\n"
            "cpp_source" -> "#include <iostream>\n\nint main() {\n    std::cout << \"Hello from MobileStudio C++\" << std::endl;\n    return 0;\n}\n"
            "cpp_header" -> "#pragma once\n\nclass $className {\npublic:\n    $className();\n};\n"
            "cpp_class_source" -> "#include \"$className.hpp\"\n\n$className::$className() = default;\n"
            "cmake" -> "cmake_minimum_required(VERSION 3.22.1)\nproject(native_module LANGUAGES C CXX)\n\nadd_library(native-lib SHARED native-lib.c)\nfind_library(log-lib log)\ntarget_link_libraries(native-lib ${'$'}{log-lib})\n"
            "xml_layout" -> "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n\n    <TextView\n        android:layout_width=\"wrap_content\"\n        android:layout_height=\"wrap_content\"\n        android:layout_gravity=\"center\"\n        android:text=\"Hello MobileStudio\" />\n</FrameLayout>\n"
            "drawable_shape" -> "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<shape xmlns:android=\"http://schemas.android.com/apk/res/android\" android:shape=\"rectangle\">\n    <solid android:color=\"#2F81F7\" />\n    <corners android:radius=\"16dp\" />\n</shape>\n"
            "values_xml" -> "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"new_value\">Hello MobileStudio</string>\n</resources>\n"
            "asset_file" -> "{\n  \"name\": \"MobileStudio asset\",\n  \"version\": 1\n}\n"
            else -> ""
        }
    }

    private fun toClassName(raw: String): String =
        raw.split(Regex("[^A-Za-z0-9]+"))
            .filter { it.isNotBlank() }
            .joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
            .ifBlank { "NewFile" }

    private fun showNewFileDialog() {
        val targetDir = currentProjectDir ?: return
        showNewFileDialogInDir(targetDir)
    }

    private fun detectPackage(projectDir: File): String {
        return projectDir.walkTopDown()
            .filter { it.extension in setOf("kt", "java") && it.length() < 4096 }
            .firstOrNull()
            ?.readText()
            ?.let {
                Regex(
                    "^package\\s+([\\w.]+);?",
                    RegexOption.MULTILINE
                ).find(it)?.groupValues?.get(1)
            }
            ?: "com.example.app"
    }

    private fun showNewFolderDialog() {
        val dir = currentProjectDir ?: return
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("New folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val created = File(dir, input.text.toString().trim())
                created.mkdirs()
                expandedFileTreePaths.add(canonicalPath(dir))
                expandedFileTreePaths.add(canonicalPath(created))
                loadFileTree(dir)
            }.show()
    }

    override fun showFileContextMenu(file: File) {
        val ctx = this
        val actions = buildList {
            add(ctx.getStringIfKorean("열기", "Open"))
            if (ContextMenuUtils.isTextLike(file)) add(ctx.getStringIfKorean("AI에게 묻기", "Ask AI"))
            if (file.extension.lowercase() == "xml" && file.absolutePath.contains("/res/layout")) {
                add(ctx.getStringIfKorean("프리뷰 및 편집", "Preview & Edit"))
            }
            add(ctx.getStringIfKorean("이름 바꾸기", "Rename"))
            add(ctx.getStringIfKorean("복제", "Duplicate"))
            add(ctx.getStringIfKorean("삭제", "Delete"))
            add(ctx.getStringIfKorean("경로 복사", "Copy Path"))
            add(ctx.getStringIfKorean("내용 복사", "Copy Content"))
            if (file.extension.equals("exe", true)) {
                add(ctx.getStringIfKorean("box64 빌드 & 실행", "Build & Run (box64)"))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(actions.toTypedArray()) { _, which ->
                val action = actions[which]
                when (action) {
                    ctx.getStringIfKorean("열기", "Open") -> openFile(file)
                    ctx.getStringIfKorean("AI에게 묻기", "Ask AI") -> askAiAboutFile(file)
                    ctx.getStringIfKorean("프리뷰 및 편집", "Preview & Edit") -> LayoutPreviewActivity.start(this, file)
                    ctx.getStringIfKorean("이름 바꾸기", "Rename") -> renameFile(file)
                    ctx.getStringIfKorean("복제", "Duplicate") -> duplicateFile(file)
                    ctx.getStringIfKorean("삭제", "Delete") -> deleteFile(file)
                    ctx.getStringIfKorean("경로 복사", "Copy Path") -> copyPathToClipboard(file)
                    ctx.getStringIfKorean("내용 복사", "Copy Content") -> copyContentToClipboard(file)
                    ctx.getStringIfKorean("box64 빌드 & 실행", "Build & Run (box64)") -> buildAndRunExe(file)
                }
            }
            .show()
    }

    private fun askAiAboutFile(file: File) {
        val content = runCatching { file.readText() }.getOrElse { "Failed to read file" }
        com.aide.mobilestudio.ai.ui.AiWorkspaceActivity.start(
            this,
            currentProjectDir?.absolutePath,
            file.absolutePath,
            initialMessage = "Explain this file: ${file.name}\n\n```${file.extension}\n$content\n```",
        )
    }

    private fun contextualCreateLabel(dir: File): String = when (contextualTemplateKind(dir)) {
        "asset_file" -> "Create assets file"
        "xml_layout" -> "Create Layout File"
        "drawable_shape" -> "Create Drawable File"
        "values_xml" -> "Create Values XML"
        "kotlin_class" -> "Create Kotlin Class"
        "java_class" -> "Create Java Class"
        "cmake" -> "Create CMakeLists.txt"
        else -> "Create File"
    }

    private fun contextualTemplateKind(dir: File): String {
        val path = dir.absolutePath.replace('\\', '/')
        return when {
            path.endsWith("/app/src/main") -> "asset_file"
            "/res/layout" in path -> "xml_layout"
            "/res/drawable" in path -> "drawable_shape"
            "/res/values" in path -> "values_xml"
            path.endsWith("/src/main/cpp") || "/src/main/cpp/" in path -> "c_source"
            "/src/main/kotlin" in path -> "kotlin_class"
            "/src/main/java" in path -> "java_class"
            else -> "empty"
        }
    }

    private fun contextualCreateTarget(dir: File): File {
        val path = dir.absolutePath.replace('\\', '/')
        return if (path.endsWith("/app/src/main")) {
            File(dir, "assets").apply { mkdirs() }
        } else {
            dir
        }
    }

    private fun showNewFileInDir(dir: File) {
        showNewFileDialogInDir(dir)
    }

    private fun showNewFolderInDir(dir: File) {
        val input = EditText(this).apply { hint = "Folder name" }
        AlertDialog.Builder(this).setTitle("New folder").setView(input)
            .setPositiveButton("Create") { _, _ ->
                val created = File(dir, input.text.toString().trim())
                created.mkdirs()
                expandedFileTreePaths.add(canonicalPath(dir))
                expandedFileTreePaths.add(canonicalPath(created))
                loadFileTree(currentProjectDir ?: return@setPositiveButton)
            }.show()
    }

    private fun duplicateFile(file: File) {
        val newName = "${file.nameWithoutExtension}_copy.${file.extension}"
        val dest = File(file.parent, newName)
        file.copyTo(dest, overwrite = true)
        currentProjectDir?.let { loadFileTree(it) }
        openFile(dest)
    }

    private fun copyPathToClipboard(file: File) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("path", file.absolutePath))
        showToast("Path copied")
    }

    private fun copyContentToClipboard(file: File) {
        if (isBlockedEditorFile(file)) {
            showToast("Binary content cannot be copied")
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("content", file.readText()))
        showToast("Content copied (${file.length()} bytes)")
    }

    private fun buildAndRunExe(file: File) {
        val intent = Intent(this, ToolsActivity::class.java)
        startActivity(intent)
        Toast.makeText(this, getStringIfKorean("box64 도구로 실행합니다", "Running with box64 tools"), Toast.LENGTH_SHORT).show()
    }

    // MobileStudio comment
    // Editor features (highlighting + autocomplete)
    // MobileStudio comment

    private fun setupEditorFeatures() {
        problemsAdapter = ProblemsAdapter(
            onNavigateToFile = { file, line ->
                if (line > 0 && file != null && file.exists()) {
                    openFileAtLine(file, line)
                } else if (file != null && file.exists()) {
                    openFile(file)
                }
            },
            onShowProblemActions = { problem ->
                showProblemActionsDialog(problem)
            }
        )
        problemsRecycler?.adapter = problemsAdapter

        // The empty view follows the adapter rather than the places that fill
        // it, so it stays correct however problems arrive - from a build, from
        // a lint run, or from the editor's own analysis.
        val problemsParent = problemsRecycler?.parent as? LinearLayout
        val problemsEmpty = StudioUi.emptyState(
            this,
            glyph = "\u2705",
            title = ui("문제 없음"),
            hint = ui("빌드와 정적 분석의 진단이 여기에 표시됩니다"),
        )
        problemsParent?.addView(
            problemsEmpty,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        // Mirror the count on the panel tab the way an IDE does, so the number
        // of problems is visible without opening the tab. The icon-only label
        // used on narrow screens is left alone: a count would not fit.
        val problemsTab = findViewById<TextView>(R.id.tab_problems)
        val problemsTabBase = problemsTab?.text?.toString()
        fun syncProblemsTab(count: Int) {
            if (problemsTab == null || problemsTabBase == null || problemsTabBase.length <= 3) return
            problemsTab.text = if (count > 0) "$problemsTabBase ($count)" else problemsTabBase
        }

        fun syncProblemsEmpty() {
            val empty = problemsAdapter.itemCount == 0
            problemsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            problemsRecycler?.visibility = if (empty) View.GONE else View.VISIBLE
            syncProblemsTab(problemsAdapter.itemCount)
        }
        problemsAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = syncProblemsEmpty()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = syncProblemsEmpty()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = syncProblemsEmpty()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = syncProblemsEmpty()
        })
        syncProblemsEmpty()
        autoComplete = KotlinAutoComplete(this, codeEditor)
        webAutoComplete = com.aide.mobilestudio.web.WebAutoComplete(this, codeEditor)

        KotlinHighlighter.attachTo(codeEditor, currentFile?.absolutePath) { errors ->
            val extension = currentFile?.extension?.lowercase()
            if (extension !in setOf("kt", "kts", "java", "c", "cc", "cpp", "h", "hpp")) {
                liveErrors = emptyList()
                refreshProblems()
                return@attachTo
            }
            if (analysisEngine != null) {
                // AnalysisEngine owns diagnostics for compiler-checked files;
                // the lightweight highlighter must not clobber them with its
                // much weaker (usually empty) results.
                return@attachTo
            }
            liveErrors = errors.map { e ->
                BuildProblem(
                    file = currentFile,
                    line = e.line,
                    column = e.col,
                    severity = BuildProblem.Severity.WARNING,
                    message = e.message,
                    raw = e.message
                )
            }
            refreshProblems()
        }

        // Problems: navigate to line.
        problemsRecycler?.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    view.setOnClickListener {
                        val pos = problemsRecycler?.getChildAdapterPosition(view)
                        val err = problemsAdapter.getItem(pos ?: -1) ?: return@setOnClickListener
                        if (err.line <= 0) {
                            showToast("No source location for this problem")
                            return@setOnClickListener
                        }
                        err.file?.takeIf { it.exists() && it != currentFile }?.let { openFile(it) }
                        codeEditor.post { navigateToLine(err.line) }
                    }
                    view.setOnLongClickListener {
                        val pos = problemsRecycler?.getChildAdapterPosition(view)
                        val err = problemsAdapter.getItem(pos ?: -1) ?: return@setOnLongClickListener true
                        showProblemActions(err)
                        true
                    }
                }

                override fun onChildViewDetachedFromWindow(view: View) {}
            })
    }

    private fun showProblemActions(problem: BuildProblem) {
        AlertDialog.Builder(this)
            .setTitle("Problem")
            .setItems(arrayOf("Copy problem", "Copy all problems")) { _, which ->
                when (which) {
                    0 -> copyProblemToClipboard(problem)
                    1 -> copyProblemsToClipboard()
                }
            }
            .show()
    }

    private fun copyProblemToClipboard(problem: BuildProblem) {
        val text = "[${problem.severity}] ${problem.displayPath(currentProjectDir)}:${problem.line}:${problem.column}: ${problem.message}\n${problem.raw}"
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MobileStudio Problem", text))
        showToast("Problem copied")
    }

    fun triggerLiveDiagnosticsAndWait() = scope.launch {
        updateLiveDiagnosticsNow()
    }

    private fun updateLiveDiagnosticsNow() {
        val file = currentFile ?: return
        val extension = file.extension.lowercase()
        if (extension !in setOf("kt", "kts", "java", "c", "cpp", "cc", "cxx", "h", "hpp")) {
            liveErrors = emptyList()
            refreshProblems()
            return
        }

        // Use AnalysisEngine for real Kotlin/Java compiler diagnostics
        val engine = analysisEngine
        if (engine != null) {
            engine.requestAnalysis(file, codeEditor.text?.toString())
            return
        }

        // Fallback to basic syntax check
        KotlinHighlighter.analyzeNow(codeEditor, currentFile?.absolutePath) { errors ->
            liveErrors = errors.map { error ->
                BuildProblem(
                    file = currentFile,
                    line = error.line,
                    column = error.col,
                    severity = BuildProblem.Severity.WARNING,
                    message = error.message,
                    raw = error.message
                )
            }
            refreshProblems()
        }
    }

    /**
     * Apply inline error/warning underlines to the editor
     */
    private fun updateInlineDiagnostics(diagnostics: List<com.aide.mobilestudio.analysis.Diagnostic>, source: String) {
        currentDiagnostics = diagnostics
        if (!::codeEditor.isInitialized) return
        val editor = codeEditor
        val text = editor.text?.toString() ?: return
        if (diagnostics.isEmpty()) {
            // Clear only diagnostic spans
            val spannable = editor.text
            if (spannable is android.text.Spannable) {
                val oldSpans = spannable.getSpans(0, spannable.length, SquigglyUnderlineSpan::class.java)
                oldSpans.forEach { spannable.removeSpan(it) }
                val clickableSpans = spannable.getSpans(0, spannable.length, DiagnosticClickableSpan::class.java)
                clickableSpans.forEach { spannable.removeSpan(it) }
            }
            hideLightbulb()
            updateErrorNavigation()
            return
        }

        val spannable = editor.text
        if (spannable !is android.text.Spannable) return

        // Clear previous diagnostic spans
        val oldSpans = spannable.getSpans(0, spannable.length, SquigglyUnderlineSpan::class.java)
        oldSpans.forEach { spannable.removeSpan(it) }
        val clickableSpans = spannable.getSpans(0, spannable.length, DiagnosticClickableSpan::class.java)
        clickableSpans.forEach { spannable.removeSpan(it) }

        val lines = text.lines()
        diagnostics.filter { it.line > 0 && it.line <= lines.size }.forEach { diag ->
            val lineStart = lines.take(diag.line - 1).sumOf { it.length + 1 }
            val start = (lineStart + diag.column - 1).coerceIn(0, spannable.length)
            val end = (start + diag.length.coerceAtLeast(1)).coerceIn(0, spannable.length)
            if (start < end) {
                val underlineColor = when (diag.severity) {
                    com.aide.mobilestudio.analysis.DiagnosticSeverity.ERROR -> StudioUi.color(StudioUi.Red)
                    com.aide.mobilestudio.analysis.DiagnosticSeverity.WARNING -> StudioUi.color(StudioUi.Amber)
                    else -> StudioUi.color(StudioUi.ActiveBlue)
                }
                spannable.setSpan(SquigglyUnderlineSpan(underlineColor), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                
                // Add clickable span for navigation to error
                val message = diag.message
                spannable.setSpan(DiagnosticClickableSpan(diag.line, diag.column, message, diag.severity), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Function name highlighting is now only active during Volume Down hold
        updateErrorNavigation()
    }

    inner class DiagnosticClickableSpan(
        val line: Int,
        val column: Int,
        val message: String,
        val severity: com.aide.mobilestudio.analysis.DiagnosticSeverity
    ) : ClickableSpan() {
        override fun onClick(widget: View) {
            navigateToError(line, column, message)
        }

        override fun updateDrawState(ds: android.text.TextPaint) {
            ds.isUnderlineText = false
            ds.color = StudioUi.color("#CCCCCC")
        }
    }

    private fun navigateToError(line: Int, column: Int, message: String) {
        val editor = codeEditor
        val text = editor.text?.toString() ?: return
        val lines = text.lines()
        if (line > 0 && line <= lines.size) {
            val lineStart = lines.take(line - 1).sumOf { it.length + 1 }
            val pos = (lineStart + column - 1).coerceIn(0, text.length)
            editor.setSelection(pos)
            editor.requestFocus()
            // Show error message in a toast or status
            showToast("Line $line: $message")
        }
    }

    private fun addClickableFunctionSpans(spannable: android.text.Spannable, source: String) {
        // Pattern: fun name(, class name, object name
        val patterns = listOf(
            Regex("""\bfun\s+(\w+)\s*\("""),
            Regex("""\b(class|interface|object|enum\s+class|data\s+class)\s+(\w+)"""),
            Regex("""\bval\s+(\w+)\s*[=:]"""),
            Regex("""\bvar\s+(\w+)\s*[=:]""")
        )
        val found = mutableSetOf<IntRange>()
        for (pattern in patterns) {
            for (match in pattern.findAll(source)) {
                val nameRange = if (match.groupValues.size > 2 && match.groupValues[2].isNotEmpty()) {
                    // class/object/interface pattern: group 2 is the name
                    val nameStart = match.range.first + match.value.indexOf(match.groupValues[2])
                    nameStart until nameStart + match.groupValues[2].length
                } else if (match.groupValues.size > 1 && match.groupValues[1].isNotEmpty()) {
                    // fun/val/var pattern: group 1 is the name
                    val nameStart = match.range.first + match.value.indexOf(match.groupValues[1])
                    nameStart until nameStart + match.groupValues[1].length
                } else continue
                if (nameRange.first < 0 || nameRange.last >= source.length) continue
                if (found.any { it.first <= nameRange.first && it.last >= nameRange.last }) continue
                found.add(nameRange)
                val safeStart = nameRange.first.coerceIn(0, spannable.length)
                val safeEnd = (nameRange.last + 1).coerceIn(0, spannable.length)
                if (safeStart >= safeEnd) continue
                // Visual: underline + blue color
                spannable.setSpan(UnderlineSpan(), safeStart, safeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(ForegroundColorSpan(StudioUi.color("#7CC7FF")), safeStart, safeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(StyleSpan(Typeface.BOLD), safeStart, safeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    // MobileStudio comment
    private fun showLightbulb(line: Int, diagnostic: com.aide.mobilestudio.analysis.Diagnostic) {
        hideLightbulb()
        val layout = codeEditor.parent as? ViewGroup ?: return
        val tv = TextView(this).apply {
            text = "\uD83D\uDCA1"
            textSize = 20f
            setPadding(8, 4, 8, 4)
            setOnClickListener { showQuickFixForCurrentLine() }
            elevation = 12f
            tag = "lightbulb"
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        // Position: left gutter area
        val lineMetrics = codeEditor.layout?.let {
            val lineTop = it.getLineTop(line - 1)
            val lineBottom = it.getLineBottom(line - 1)
            params.topMargin = lineTop + codeEditor.scrollY - codeEditor.scrollY + 2
            params.leftMargin = 4
            params.width = FrameLayout.LayoutParams.WRAP_CONTENT
            params.height = FrameLayout.LayoutParams.WRAP_CONTENT
        }
        layout.addView(tv, params)
        lightbulbView = tv
        lightbulbVisible = true
    }

    private fun hideLightbulb() {
        lightbulbView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        lightbulbView = null
        lightbulbVisible = false
    }

    // MobileStudio comment
    fun toggleShellFromEditor() {
        if (bottomPanel.visibility != View.VISIBLE) {
            showBottomPanel()
            switchToPanel("shell")
        } else if (currentBottomPanelTab == "shell") {
            hideBottomPanel()
        } else {
            switchToPanel("shell")
        }
    }

    // =====================================================================
    // Split layout preview (에디터 미리보기 버튼)
    // =====================================================================

    /** Device presets for the split preview (name, width px, height px). */
    private val previewDevices = listOf(
        Triple("Pixel 7", 1080, 2400),
        Triple("Pixel 7 Pro", 1440, 3120),
        Triple("Pixel 5", 1080, 2340),
        Triple("Galaxy S23", 1080, 2340),
    )
    private var previewDeviceIndex = 0
    private var previewEngine: LayoutLibPreviewEngine? = null

    /** Shows the preview pane for XML layouts; hides it otherwise. */
    fun togglePreview() {
        val pane = findViewById<View>(R.id.preview_pane) ?: return
        if (pane.visibility == View.VISIBLE) {
            pane.visibility = View.GONE
            return
        }
        if (!currentFile.isPreviewableLayout()) {
            showToast(AppLanguage.literal(this, "XML 레이아웃 파일만 미리볼 수 있습니다"))
            return
        }
        pane.visibility = View.VISIBLE
        renderPreview()
    }

    fun cyclePreviewDevice() {
        previewDeviceIndex = (previewDeviceIndex + 1) % previewDevices.size
        if (findViewById<View>(R.id.preview_pane)?.visibility == View.VISIBLE) renderPreview()
    }

    fun refreshPreview() {
        if (findViewById<View>(R.id.preview_pane)?.visibility == View.VISIBLE) renderPreview()
    }

    fun closePreview() {
        findViewById<View>(R.id.preview_pane)?.visibility = View.GONE
    }

    /**
     * Renders the open layout at the selected device size. Runs off the main
     * thread and ignores stale results when the pane was closed or the file
     * changed while rendering.
     */
    private fun renderPreview() {
        val file = currentFile ?: return
        val device = previewDevices[previewDeviceIndex]
        findViewById<TextView>(R.id.preview_device_chip)?.text = device.first
        val message = findViewById<TextView>(R.id.preview_message) ?: return
        val image = findViewById<ImageView>(R.id.preview_image) ?: return
        val renderToken = file.absolutePath
        message.visibility = View.VISIBLE
        message.text = AppLanguage.literal(this, "미리보기 렌더링 중…")
        image.setImageDrawable(null)
        scope.launch {
            val engine = previewEngine ?: LayoutLibPreviewEngine(this@MainActivity).also { previewEngine = it }
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.render(file, device.second, device.third) }.getOrNull()
            }
            // Drop stale renders: pane closed or a different file open now.
            if (findViewById<View>(R.id.preview_pane)?.visibility != View.VISIBLE) return@launch
            if (currentFile?.absolutePath != renderToken) return@launch
            if (result?.status == LayoutLibPreviewEngine.Status.RENDERED && result.imageFile != null) {
                val bitmap = withContext(Dispatchers.IO) {
                    runCatching { android.graphics.BitmapFactory.decodeFile(result.imageFile.absolutePath) }.getOrNull()
                }
                if (bitmap != null) {
                    message.visibility = View.GONE
                    image.setImageBitmap(bitmap)
                    return@launch
                }
            }
            image.setImageDrawable(null)
            message.visibility = View.VISIBLE
            message.text = (result?.message ?: AppLanguage.literal(this@MainActivity, "미리보기를 생성하지 못했습니다")) +
                (result?.detail?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: "")
        }
    }

    // MobileStudio comment
    fun openAiChatFromEditor() {
        openAiWorkspace()
    }

    /** Opens the new full-screen AI workspace, scoped to the current project. */
    fun openAiWorkspace() {
        com.aide.mobilestudio.ai.ui.AiWorkspaceActivity.start(this, currentProjectDir?.absolutePath)
    }

    fun sendCrashToAI(crashLog: String) {
        // The old bottom panel was half-height and could not show the crash
        // analysis next to the editor; hand it to the full workspace instead.
        com.aide.mobilestudio.ai.ui.AiWorkspaceActivity.start(
            this,
            currentProjectDir?.absolutePath,
            initialMessage = "Analyze this crash and suggest fixes:\n\n```\n$crashLog\n```",
        )
    }

    private var errorNavIndex = -1

    fun navigateToNextError() {
        val errors = currentDiagnostics.filter { it.severity == com.aide.mobilestudio.analysis.DiagnosticSeverity.ERROR }
        if (errors.isEmpty()) { showToast("No errors"); return }
        errorNavIndex = (errorNavIndex + 1) % errors.size
        navigateToError(errors[errorNavIndex])
    }

    fun navigateToPrevError() {
        val errors = currentDiagnostics.filter { it.severity == com.aide.mobilestudio.analysis.DiagnosticSeverity.ERROR }
        if (errors.isEmpty()) { showToast("No errors"); return }
        errorNavIndex = if (errorNavIndex <= 0) errors.lastIndex else errorNavIndex - 1
        navigateToError(errors[errorNavIndex])
    }

    private fun navigateToError(diag: com.aide.mobilestudio.analysis.Diagnostic) {
        val text = codeEditor.text?.toString() ?: return
        val lines = text.lines()
        if (diag.line < 1 || diag.line > lines.size) return
        val lineStart = lines.take(diag.line - 1).sumOf { it.length + 1 }
        val offset = (lineStart + diag.column - 1).coerceIn(0, text.length)
        codeEditor.setSelection(offset)
        // Scroll to make the line visible
        codeEditor.post {
            val layout = codeEditor.layout ?: return@post
            val lineTop = layout.getLineTop(diag.line - 1)
            val lineHeight = layout.getLineBottom(diag.line - 1) - lineTop
            val editorHeight = codeEditor.height - codeEditor.paddingTop - codeEditor.paddingBottom
            val targetScroll = lineTop - editorHeight / 2
            codeEditor.scrollTo(codeEditor.scrollX, targetScroll.coerceAtLeast(0))
        }
        vibrate(10)
    }

    private fun updateErrorNavigation() {
        val errorCount = currentDiagnostics.count { it.severity == com.aide.mobilestudio.analysis.DiagnosticSeverity.ERROR }
        val warnCount = currentDiagnostics.count { it.severity == com.aide.mobilestudio.analysis.DiagnosticSeverity.WARNING }
        val errorTv = findViewById<TextView>(R.id.editor_error_count)
        val warnTv = findViewById<TextView>(R.id.editor_warning_count)
        if (errorCount > 0) {
            errorTv?.text = "\u2716 $errorCount"
            errorTv?.visibility = View.VISIBLE
        } else {
            errorTv?.visibility = View.GONE
        }
        if (warnCount > 0) {
            warnTv?.text = "\u26A0 $warnCount"
            warnTv?.visibility = View.VISIBLE
        } else {
            warnTv?.visibility = View.GONE
        }
        errorNavIndex = -1
    }

    private fun showQuickFixForCurrentLine() {
        val file = currentFile ?: return
        val cursorLine = getCurrentLine()
        val diagnostic = currentDiagnostics.find { it.line == cursorLine }
        if (diagnostic == null) {
            showToast("No errors on this line")
            return
        }
        com.aide.mobilestudio.analysis.QuickFixProvider.showFixDialog(
            this, diagnostic, file
        ) {
            // After fix applied, refresh the file
            openFile(file)
        }
    }

    private fun getCurrentLine(): Int {
        val text = codeEditor.text?.toString() ?: return 1
        val cursor = codeEditor.selectionStart.coerceIn(0, text.length)
        return text.substring(0, cursor).count { it == '\n' } + 1
    }

    private fun maybeOfferLanguageConversion() {
        val file = currentFile ?: return
        val extension = file.extension.lowercase()
        if (extension !in setOf("java", "kt")) return
        if (!conversionPromptedFiles.add(file.absolutePath)) return
        val text = codeEditor.text?.toString().orEmpty()
        val looksKotlinInJava = extension == "java" && looksLikeKotlin(text)
        val looksJavaInKotlin = extension == "kt" && looksLikeJava(text)
        if (!looksKotlinInJava && !looksJavaInKotlin) return

        AlertDialog.Builder(this)
            .setTitle("Language mismatch detected")
            .setMessage("This .$extension file looks like Kotlin syntax but has a .java extension (or vice versa).")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun looksLikeKotlin(text: String): Boolean {
        val signals = listOf(
            Regex("""\bfun\s+\w+\s*\("""),
            Regex("""\bval\s+\w+"""),
            Regex("""\bvar\s+\w+"""),
            Regex("""\bclass\s+\w+\s*:"""),
            Regex("""\boverride\s+fun\b""")
        )
        return signals.count { it.containsMatchIn(text) } >= 2
    }

    private fun looksLikeJava(text: String): Boolean {
        val signals = listOf(
            Regex("""\bpublic\s+class\s+\w+"""),
            Regex("""\bprivate\s+\w+[<>\w,\s]*\s+\w+\s*;"""),
            Regex("""\bextends\s+\w+"""),
            Regex("""\bimplements\s+\w+"""),
            Regex("""\bstatic\s+void\s+main\s*\(""")
        )
        return signals.count { it.containsMatchIn(text) } >= 2
    }

    private fun refreshProblems() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { refreshProblems() }
            return
        }
        if (problemsRefreshScheduled) return
        problemsRefreshScheduled = true
        problemsRecycler?.postDelayed({
            problemsRefreshScheduled = false
            renderProblems()
        }, 120L)
    }

    private fun renderProblems() {
        val problemsTabFile = findViewById<TextView>(R.id.problems_tab_file)
        val problemsTabProject = findViewById<TextView>(R.id.problems_tab_project)
        
        var combined = (liveErrors + buildErrors).distinctBy {
            "${it.file?.absolutePath}:${it.line}:${it.column}:${it.severity}:${it.message}"
        }
        
        if (problemsTabFile.background != null) {
            combined = combined.filter { it.file == currentFile }
        } else if (problemsTabProject.background != null) {
            combined = combined.filter { it.file == null || !it.file.absolutePath.startsWith(currentProjectDir?.absolutePath ?: "") }
        }

        problemsAdapter.updateBatched(combined, batchSize = 50, intervalMs = 1_000L)
        updateBadges()

        val tabProblems = findViewById<TextView>(R.id.tab_problems)
        val errorCount = combined.count { it.severity == BuildProblem.Severity.ERROR }
        val warningCount = combined.count { it.severity == BuildProblem.Severity.WARNING }
        val baseText = if (combined.isEmpty()) "\u2716 Problems"
            else "\u2716 Problems"
        val badgeText = when {
            combined.isEmpty() -> ""
            combined.size > 50 -> " ${combined.size}"
            errorCount > 0 -> " E$errorCount"
            warningCount > 0 -> " W$warningCount"
            else -> ""
        }
        val span = android.text.SpannableString(baseText + badgeText)
        if (badgeText.isNotEmpty()) {
            val badgeColor = when {
                errorCount > 0 -> StudioUi.color(StudioUi.ActiveRed)
                warningCount > 0 -> StudioUi.color(StudioUi.ActiveAmber)
                else -> Color.TRANSPARENT
            }
            span.setSpan(android.text.style.BackgroundColorSpan(badgeColor),
                baseText.length, baseText.length + badgeText.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.ForegroundColorSpan(Color.WHITE),
                baseText.length, baseText.length + badgeText.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.AbsoluteSizeSpan(10, true),
                baseText.length, baseText.length + badgeText.length,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tabProblems.text = span
        tabProblems.setTextColor(
            Color.parseColor(
                when {
                    errorCount > 0 -> StudioUi.ActiveRed
                    warningCount > 0 -> StudioUi.ActiveAmber
                    else -> StudioUi.Muted
                }
            )
        )
    }

    private fun copyProblemsToClipboard() {
        val combined = liveErrors + buildErrors
        if (combined.isEmpty()) {
            showToast("No problems to copy")
            return
        }
        val root = currentProjectDir
        val text = combined.joinToString("\n") { problem ->
            val location = buildString {
                append(problem.displayPath(root))
                if (problem.line > 0) append(":${problem.line}")
                if (problem.column > 0) append(":${problem.column}")
            }
            "[${problem.severity}] $location: ${problem.message}"
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MobileStudio Problems", text))
        showToast("Problems copied (${combined.size})")
    }


    private fun navigateToLine(lineNum: Int) {
        val text = codeEditor.text?.toString() ?: return
        val lines = text.lines()
        if (lineNum <= 0 || lines.isEmpty()) return
        val targetLine = lineNum.coerceIn(1, lines.size)
        var offset = 0
        for (i in 0 until minOf(targetLine - 1, lines.size)) offset += lines[i].length + 1
        codeEditor.setSelection(offset.coerceAtMost(text.length))
        val layout = codeEditor.layout
        if (layout != null) {
            val y = layout.getLineTop(targetLine - 1)
            (codeEditor.parent as? ScrollView)?.smoothScrollTo(0, y)
        }
    }

    private fun showQuickFixesForCurrentLine() {
        val lineNumber = currentEditorLine()
        val file = currentFile ?: return
        val source = codeEditor.text?.toString() ?: return

        // Try analysis engine diagnostics first (more accurate)
        val analysisDiag = currentDiagnostics.find { it.line == lineNumber }
        if (analysisDiag != null) {
            com.aide.mobilestudio.analysis.QuickFixProvider.showFixDialog(
                this, analysisDiag, file
            ) { openFile(file) }
            return
        }

        // Fallback to basic problem-based fixes
        val lineText = source.lines().getOrNull(lineNumber - 1).orEmpty()
        val problems = liveErrors.filter { it.line == lineNumber }
        val fixes = mutableListOf<Pair<String, () -> Unit>>()

        if (problems.any { it.message.contains("semicolon", ignoreCase = true) } || lineText.trimEnd().endsWith(";")) {
            fixes += "Remove trailing semicolon" to { replaceCurrentLine(lineText.trimEnd().removeSuffix(";")) }
        }
        if (problems.any { it.message.contains("TODO", ignoreCase = true) } || lineText.contains("TODO")) {
            fixes += "Remove TODO placeholder" to {
                replaceCurrentLine(
                    lineText
                        .replace("TODO(\"Not implemented\")", "")
                        .replace("TODO()", "")
                        .replace("// TODO", "//")
                        .trimEnd()
                )
            }
        }
        val unclosed = problems.firstOrNull { it.message.startsWith("Unclosed") }?.message
        if (unclosed != null) {
            val closing = when {
                unclosed.contains("'{'") -> "}"
                unclosed.contains("'('") -> ")"
                unclosed.contains("'['") -> "]"
                else -> null
            }
            if (closing != null) fixes += "Insert missing $closing" to { insertAtCursor(closing) }
        }
        if (currentFile?.extension?.lowercase() in setOf("kt", "java")) {
            commonImportForLine(lineText)?.let { importLine ->
                if (!source.contains(importLine)) {
                    fixes += "Add import: $importLine" to { addImportLine(importLine) }
                }
            }
        }

        if (fixes.isEmpty()) {
            showToast("No quick fix available for this line")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Quick fixes")
            .setItems(fixes.map { it.first }.toTypedArray()) { _, index -> fixes[index].second.invoke() }
            .show()
    }

    private fun currentEditorLine(): Int {
        val layout = codeEditor.layout ?: return 1
        val offset = codeEditor.selectionStart.coerceIn(0, codeEditor.text?.length ?: 0)
        return layout.getLineForOffset(offset) + 1
    }

    private fun currentLineBounds(): Pair<Int, Int> {
        val text = codeEditor.text?.toString().orEmpty()
        val cursor = codeEditor.selectionStart.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', (cursor - 1).coerceAtLeast(0)).let { if (it < 0) 0 else it + 1 }
        val end = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
        return start to end
    }

    private fun replaceCurrentLine(newLine: String) {
        val (start, end) = currentLineBounds()
        codeEditor.text?.replace(start, end, newLine)
        codeEditor.setSelection((start + newLine.length).coerceAtMost(codeEditor.text?.length ?: 0))
    }

    private fun commonImportForLine(line: String): String? {
        val isJava = currentFile?.extension?.equals("java", true) == true
        return when {
            line.contains("Toast") -> if (isJava) "import android.widget.Toast;" else "import android.widget.Toast"
            line.contains("Intent") -> if (isJava) "import android.content.Intent;" else "import android.content.Intent"
            line.contains("Bundle") -> if (isJava) "import android.os.Bundle;" else "import android.os.Bundle"
            line.contains("Log.") -> if (isJava) "import android.util.Log;" else "import android.util.Log"
            line.contains("File(") -> if (isJava) "import java.io.File;" else "import java.io.File"
            else -> null
        }
    }

    private fun addImportLine(importLine: String) {
        val text = codeEditor.text?.toString().orEmpty()
        val packageMatch = Regex("^package\\s+[\\w.]+;?", RegexOption.MULTILINE).find(text)
        val insertAt = packageMatch?.range?.last?.plus(1) ?: 0
        val prefix = if (insertAt > 0) "\n" else ""
        codeEditor.text?.insert(insertAt, "$prefix$importLine\n")
    }

    // MobileStudio comment
    // Logcat
    // MobileStudio comment

    private fun setupLogcat() {
        logcatText.text = "> logcat starting...\n"
        try {
            logcatManager = LogcatManager(
                context = this,
                onLine = { raw, colored ->
                    logcatText.append(colored)
                    logcatText.append("\n")
                    trimLogcatText()
                },
                onError = { err ->
                    logcatText.append("> logcat error: $err\n")
                }
            )
            logcatManager.start()
        } catch (e: Exception) {
            logcatText.text = "> logcat failed: ${e.message}\n"
        }

        // Filter bar
        val etFilter = findViewById<EditText?>(R.id.et_logcat_filter)
        etFilter?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, a: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyLogcatFilter(s?.toString().orEmpty())
            }
        })
        // Wire filter level buttons
        val act = this
        val density = resources.displayMetrics.density
        fun Int.dp() = (this * density).toInt()
        val filterAll = Button(act).apply {
            text = "All"
            setTextColor(Color.WHITE)
            textSize = 11f
            setBackgroundColor(StudioUi.color(StudioUi.Green))
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            setOnClickListener { setLogcatFilterLevel(null) }
        }
        val filterError = Button(act).apply {
            text = "Error"
            setTextColor(Color.WHITE)
            textSize = 11f
            setBackgroundColor(StudioUi.color(StudioUi.Red))
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            setOnClickListener { setLogcatFilterLevel("E") }
        }
        val filterWarn = Button(act).apply {
            text = "Warn"
            setTextColor(Color.WHITE)
            textSize = 11f
            setBackgroundColor(StudioUi.color(StudioUi.Amber))
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            setOnClickListener { setLogcatFilterLevel("W") }
        }
        val filterInfo = Button(act).apply {
            text = "Info"
            setTextColor(Color.WHITE)
            textSize = 11f
            setBackgroundColor(StudioUi.color("#98C379"))
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            setOnClickListener { setLogcatFilterLevel("I") }
        }
        val btnClear = Button(act).apply {
            text = "Clear"
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 11f
            setBackgroundColor(StudioUi.color(StudioUi.Card2))
            setPadding(8.dp(), 4.dp(), 8.dp(), 4.dp())
            setOnClickListener {
                logcatText.text = ""
                logcatManager.clear()
            }
        }
        val filterRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4.dp(), 0, 4.dp())
            addView(filterAll)
            addView(filterError.apply { layoutParams = LinearLayout.LayoutParams(0, 28.dp(), 1f).apply { marginStart = 4.dp() } })
            addView(filterWarn.apply { layoutParams = LinearLayout.LayoutParams(0, 28.dp(), 1f).apply { marginStart = 4.dp() } })
            addView(filterInfo.apply { layoutParams = LinearLayout.LayoutParams(0, 28.dp(), 1f).apply { marginStart = 4.dp() } })
            addView(btnClear.apply { layoutParams = LinearLayout.LayoutParams(0, 28.dp(), 1f).apply { marginStart = 4.dp() } })
        }
        findViewById<ViewGroup>(R.id.logcat_container)?.addView(filterRow, 0)
        logcatFilterLevel = null
    }

    private var logcatFilterLevel: String? = null
    private var logcatFilterText: String = ""

    private fun setLogcatFilterLevel(level: String?) {
        logcatFilterLevel = level
        applyLogcatFilter(logcatFilterText)
    }

    private fun applyLogcatFilter(filter: String) {
        logcatFilterText = filter
        // Re-filter: walk the current log text and rebuild visibility
        val text = logcatText.text?.toString().orEmpty()
        val lines = text.split("\n")
        logcatText.text = ""
        for (line in lines) {
            if (line.isBlank()) continue
            val levelMatch = logcatFilterLevel == null || line.contains(" ${logcatFilterLevel} ") || line.contains(" ${logcatFilterLevel}/")
            val textMatch = filter.isBlank() || line.contains(filter, ignoreCase = true)
            if (levelMatch && textMatch) {
                logcatText.append(line + "\n")
            }
        }
    }

    private fun ensureLogcatStarted() {
        if (::logcatManager.isInitialized && logcatManager.isPaused()) {
            logcatManager.resume()
        } else if (!::logcatManager.isInitialized) {
            setupLogcat()
        }
    }

    private fun trimLogcatText() {
        val maxChars = 30_000
        val current = logcatText.text?.toString() ?: return
        if (current.length <= maxChars + 5_000) return
        val cutTo = (current.length - maxChars).coerceAtLeast(0)
        val nextLine = current.indexOf('\n', cutTo).let { if (it < 0) cutTo else it + 1 }
        logcatText.text = current.substring(nextLine)
    }

    // MobileStudio comment
    // UI helpers
    // MobileStudio comment

    private fun setBuildRunning(running: Boolean) {
        val wasRunning = isBuildRunning
        isBuildRunning = running
        // Extension lifecycle: fire only on a real transition, so the many
        // start/stop call sites (Gradle, Python, JVM, NDK) all go through here
        // exactly once per build.
        if (running && !wasRunning) {
            runCatching { ExtensionHost.dispatchBuildStartForAll(activeBuildTasks) }
        } else if (!running && wasRunning) {
            runCatching { ExtensionHost.dispatchBuildFinishForAll(pendingBuildSuccess) }
            pendingBuildSuccess = false
        }
        if (::buildProgress.isInitialized) buildProgress.visibility = if (running) View.VISIBLE else View.INVISIBLE
        // Row B spinner (moved up from the deleted build toolbar row)
        findViewById<ProgressBar>(R.id.build_progress)?.visibility =
            if (running) View.VISIBLE else View.INVISIBLE
        if (running) {
            buildMonitor.clear()
            // The first line of output replaces the placeholder; leaving it up
            // would put a note about having never built over the build that is
            // now running.
            findViewById<View>(R.id.build_output_empty)?.visibility = View.GONE
        }
        // Update toolbar run button: single dynamic button
        findViewById<ImageButton>(R.id.btn_run)?.apply {
            setImageResource(if (running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            Color.parseColor(if (running) "#FF7B72" else "#7EE787")
            setContentDescription(if (running) "Stop build" else "Build")
        }
        // Update build status text in toolbar
        val statusView = findViewById<TextView>(R.id.tv_build_status)
        if (running) {
            statusView?.text = "Building..."
            statusView?.visibility = View.VISIBLE
        } else {
            statusView?.visibility = View.GONE
        }
        // Update Build tab label with running indicator
        val tabBuild = findViewById<TextView>(R.id.tab_build)
        if (tabBuild != null) {
            if (running) {
                val base = "\u25B6 Build"
                val indicator = " ●"
                val span = android.text.SpannableString(base + indicator)
                span.setSpan(android.text.style.ForegroundColorSpan(
                    Color.parseColor(StudioUi.ActiveGreen)),
                    base.length, base.length + indicator.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                tabBuild.text = span
            } else {
                tabBuild.text = "\u25B6 Build"
            }
        }
        // Dim other buttons during build
        var alpha = if (running) 0.4f else 1f
        findViewById<ImageButton>(R.id.btn_sync_now)?.alpha = alpha
        // Update tools panel
        val toolsRunBtn = findViewById<Button>(R.id.tools_btn_run)
        if (toolsRunBtn != null) {
            toolsRunBtn.text = if (running) "Stop" else "Build"
            toolsRunBtn.studioButton(if (running) "#DA3633" else "#238636", if (running) "#FF7B72" else "#7EE787")
        }
        val toolsOutput = findViewById<TextView>(R.id.tools_build_output)
        if (running && toolsOutput != null) {
            toolsOutput.text = "Build running..."
        }
        updateToolsBuildStatus()
        if (!running) {
            populateToolsApkList()
        }
    }

    private fun setStatus(msg: String) {
        // The View-based status bar was removed by the Compose migration;
        // guard the lateinit field so imperative status callers stay safe.
        if (::tvStatusLeft.isInitialized) tvStatusLeft.text = msg
    }

    private fun showMemoryDialog(snapshot: MemoryMonitor.MemorySnapshot) {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Memory Monitor")
            .setMessage(buildString {
                appendLine("Device RAM: ${snapshot.usedRamMB}MB / ${snapshot.totalRamMB}MB (${snapshot.freePercent.toInt()}% free)")
                appendLine("App Heap: ${snapshot.appHeapUsedMB}MB / ${snapshot.appHeapMaxMB}MB")
                appendLine("Native: ${snapshot.appNativeUsedMB}MB")
                appendLine("Pressure: ${snapshot.pressureLevel.name}")
                appendLine()
                appendLine("Actions available:")
                for (action in MemoryMonitor.CleanupAction.entries) {
                    appendLine("  - ${action.description}")
                }
            })
            .setPositiveButton("Run Cleanup") { _, _ ->
                val actions = memoryMonitor.recommendActions(snapshot)
                if (actions.isNotEmpty()) {
                    memoryMonitor.executeCleanup(actions)
                    setStatus("Cleanup executed: ${actions.size} actions")
                }
            }
            .setNeutralButton("Task Manager") { _, _ ->
                TaskManagerActivity.start(this)
            }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
    }

    private fun updateBackgroundTaskIndicator() {
        val status = backgroundTaskManager.getStatusText()
        tvBackgroundTask.text = status
        tvBackgroundTask.setTextColor(
            Color.parseColor(if (backgroundTaskManager.isRunning()) "#4F8EF7" else "#5C6370")
        )
        tvBackgroundTask.setOnClickListener {
            backgroundTaskManager.showHistoryDialog()
        }
    }

    internal fun showStructureOutline() {
        val text = codeEditor.text?.toString().orEmpty()
        val fileName = currentFile?.name
        SymbolOutline.show(this, text, fileName) { line ->
            navigateToLine(line)
        }
    }

    private fun showSnippetDialog() {
        val fileName = currentFile?.name
        val ext = fileName?.substringAfterLast('.', "")
        SnippetDialog.show(this, ext) { rawBody ->
            val (text, caret) = SnippetDialog.expand(rawBody)
            val cursor = codeEditor.selectionStart.coerceIn(0, codeEditor.text?.length ?: 0)
            codeEditor.text?.replace(cursor, cursor, text)
            codeEditor.setSelection((cursor + caret).coerceIn(0, codeEditor.text?.length ?: 0))
        }
    }

    private fun selectAllEditorOccurrences() {
        val word = currentEditorSymbol()
        if (word.isBlank()) { showToast("No symbol under cursor"); return }
        val found = codeEditor.selectAllOccurrences(word)
        showToast("$found+1 cursors: $word")
    }

    private fun addNextEditorOccurrence() {
        val word = currentEditorSymbol()
        if (word.isBlank()) { showToast("No symbol under cursor"); return }
        codeEditor.addNextOccurrence(word)
        showToast("Multi-cursor: ${codeEditor.multiCursorCount}")
    }

    private fun saveDiffSnapshot() {
        val text = codeEditor.text?.toString().orEmpty()
        diffSnapshotText = text
        showToast("Snapshot saved (${text.lines().size} lines)")
    }

    private fun showDiffVsSnapshot() {
        if (diffSnapshotText == null) {
            showToast("Save a snapshot first (View > Save Snapshot)")
            return
        }
        val currentText = codeEditor.text?.toString().orEmpty()
        val fileName = currentFile?.name ?: "current"
        DiffViewerActivity.start(this, "snapshot", diffSnapshotText ?: "", fileName, currentText)
    }

    private fun updateStatusBar() {
        // Compose shell owns the status bar; View-based status views are not
        // bound after the migration, so skip when uninitialized.
        if (!::codeEditor.isInitialized) return
        if (!::tvStatusCursor.isInitialized) return
        val text   = codeEditor.text ?: return
        val cursor = codeEditor.selectionStart.coerceAtLeast(0).coerceAtMost(text.length)
        val before = text.substring(0, cursor)
        val line   = before.count { it == '\n' } + 1
        val col    = cursor - before.lastIndexOf('\n')
        tvStatusCursor.text   = "Ln $line, Col $col"
        val errorCount = liveErrors.count { it.severity == BuildProblem.Severity.ERROR }
        val warningCount = liveErrors.count { it.severity == BuildProblem.Severity.WARNING }
        if (errorCount > 0 || warningCount > 0) {
            if (::tvStatusRight.isInitialized) tvStatusRight.text = "\u26A0$warningCount \u2716$errorCount"
            if (::tvStatusRight.isInitialized) tvStatusRight.setTextColor(StudioUi.color(StudioUi.ActiveAmber))
        } else if (!gradleSyncNeeded && !gradleSyncRunning) {
            if (::tvStatusRight.isInitialized) tvStatusRight.text = "No errors"
            if (::tvStatusRight.isInitialized) tvStatusRight.setTextColor(StudioUi.color(StudioUi.ActiveGreen))
        }
        val file = currentFile
        if (::tvStatusLang.isInitialized) tvStatusLang.text     = when (file?.extension) {
            "kt"   -> "Kotlin"
            "java" -> "Java"
            "xml"  -> "XML"
            "json" -> "JSON"
            "gradle","kts" -> "Gradle"
            "md"   -> "Markdown"
            "cpp","c","h","hpp" -> "C/C++"
            else   -> file?.extension?.uppercase() ?: ""
        }
        tvStatusEncoding.text = currentFileEncoding
        tvStatusEncoding.setOnClickListener { showEncodingDialog() }
        findViewById<TextView>(R.id.tv_status_gradle)?.let { view ->
            view.text = projectGradleVersion().let { v -> if (v.isBlank()) "" else "Gradle $v" }
        }
    }

    /**
     * Gradle version of the open project, read from the wrapper properties
     * (`distributionUrl=…gradle-8.4-bin.zip`). Empty when there is no project
     * or no wrapper — the status bar simply hides the segment then.
     */
    private fun projectGradleVersion(): String {
        val root = currentProjectDir ?: return ""
        if (root == lastGradleVersionProject) return lastGradleVersion
        val version = runCatching {
            val props = File(root, "gradle/wrapper/gradle-wrapper.properties")
            if (!props.isFile) return@runCatching ""
            Regex("""gradle-([0-9][0-9A-Za-z.\-_]*)-(?:bin|all)\.zip""")
                .find(props.readText())?.groupValues?.get(1).orEmpty()
        }.getOrDefault("")
        lastGradleVersionProject = root
        lastGradleVersion = version
        return version
    }

    private var lastGradleVersionProject: File? = null
    private var lastGradleVersion: String = ""

    internal fun showBottomPanel() {
        bottomPanel.animate().cancel()
        // Docked beside the editor (landscape) it grows by width; the layout
        // params still carry the docked width while it is hidden, which is
        // what restores it after a hide.
        val targetHeight = if (isBottomPanelSideDocked()) {
            bottomPanelExpandedWidth.takeIf { it > 0 }
                ?: (bottomPanel.layoutParams as? LinearLayout.LayoutParams)?.width
                ?: 0
        } else {
            currentBottomPanelTargetHeight()
        }
        if (bottomPanel.visibility == View.VISIBLE && bottomPanel.height > 0) {
            syncBottomPanelToggleRotation(expanded = true)
            return
        }
        bottomPanel.visibility = View.VISIBLE
        bottomPanel.alpha = 0f
        bottomPanel.translationY = StudioUi.dp(this, 12).toFloat()
        // Reset along the axis the panel grows on: height when docked below,
        // width when docked beside the editor in landscape.
        setBottomPanelSize(0)
        animateBottomPanelHeight(0, targetHeight) {
            if (isBottomPanelSideDocked()) bottomPanelExpandedWidth = targetHeight
            else bottomPanelExpandedHeight = targetHeight
        }
        bottomPanel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(StudioUi.AnimNormal)
            .setInterpolator(StudioUi.DefaultInterpolator)
            .start()
        syncBottomPanelToggleRotation(expanded = true)
        updateDrawerLockState()
    }

    fun toggleShellPanel() {
        if (bottomPanel.visibility == View.VISIBLE && currentBottomPanelTab == "shell") {
            hideBottomPanel()
        } else {
            showBottomPanel()
            switchToPanel("shell")
        }
        updateShellToggleIcon()
    }

    fun toggleBuildLogPanel() {
        if (bottomPanel.visibility == View.VISIBLE && currentBottomPanelTab == "build") {
            hideBottomPanel()
        } else {
            showBottomPanel()
            switchToPanel("build")
        }
    }

    fun showCommandPalette() {
        commandPalette?.show()
    }

    private fun updateShellToggleIcon() {
        val shellToggle = findViewById<ImageButton>(R.id.btn_shell_toggle)
        val isShellVisible = bottomPanel.visibility == View.VISIBLE && currentBottomPanelTab == "shell"
        shellToggle?.apply {
            setImageResource(if (isShellVisible) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            setColorFilter(c(if (isShellVisible) "#DA3633" else "#61AFEF"))
        }
    }

    private fun hideBottomPanel() {
        bottomPanel.animate().cancel()
        if (bottomPanel.visibility != View.VISIBLE) {
            syncBottomPanelToggleRotation(expanded = false)
            return
        }
        // Read the current size along whichever axis the panel is docked on,
        // so hiding works the same below and beside the editor.
        val startSize = bottomPanelSize()
            .takeIf { it > 0 }
            ?: bottomPanel.layoutParams.let { if (isBottomPanelSideDocked()) it.width else it.height }
                .takeIf { it > 0 }
            ?: currentBottomPanelTargetHeight()
        if (isBottomPanelSideDocked()) {
            bottomPanelExpandedWidth = startSize
        } else {
            bottomPanelExpandedHeight = startSize
        }
        animateBottomPanelHeight(startSize, 0) {
            bottomPanel.visibility = View.GONE
            setBottomPanelSize(if (isBottomPanelSideDocked()) 0 else bottomPanelExpandedHeight)
        }
        syncBottomPanelToggleRotation(expanded = false)
        updateDrawerLockState()
    }

    private fun updateDrawerLockState() {
        if (!this::drawerLayout.isInitialized) return
        val shellActive = bottomPanel.visibility == View.VISIBLE && currentBottomPanelTab == "shell"
        val lockMode = if (shellActive) DrawerLayout.LOCK_MODE_LOCKED_CLOSED else DrawerLayout.LOCK_MODE_UNLOCKED
        drawerLayout.setDrawerLockMode(lockMode, GravityCompat.END)
        drawerLayout.setDrawerLockMode(lockMode, GravityCompat.START)
    }

    private fun wireTerminalToolbar() {
        val tm = terminalTabManager ?: return
        findViewById<View>(R.id.btn_terminal_copy)?.setOnClickListener { tm.getActiveTerminalView()?.copySelection() }
        findViewById<View>(R.id.btn_terminal_paste)?.setOnClickListener { tm.getActiveTerminalView()?.pasteFromClipboard() }
        // The layout's "Clear" button targets the legacy TextView; while the
        // tabbed terminal is active it must clear that terminal instead.
        findViewById<View>(R.id.btn_shell_clear)?.setOnClickListener {
            terminalTabManager?.getActiveTerminalView()?.clear()
                ?: findViewById<TextView>(R.id.shell_output_text)?.setText("")
        }
        wireTerminalKeyBar()
        tm.setOnCtrlStateChanged { syncTerminalCtrlButton() }
        syncTerminalCtrlButton()
    }

    private fun wireTerminalKeyBar() {
        fun press(action: (view: TerminalView) -> Unit) {
            terminalTabManager?.getActiveTerminalView()?.let(action)
            syncTerminalCtrlButton()
        }

        findViewById<View>(R.id.btn_terminal_ctrl)?.apply {
            setOnClickListener { press { view -> view.ctrlTapped() } }
            setOnLongClickListener {
                press { view -> view.ctrlLongPressed() }
                true
            }
        }
        findViewById<View>(R.id.btn_terminal_esc)?.setOnClickListener { press { view -> view.keyBarEsc() } }
        findViewById<View>(R.id.btn_terminal_tab)?.setOnClickListener { press { view -> view.keyBarTab() } }
        findViewById<View>(R.id.btn_terminal_up)?.setOnClickListener { press { view -> view.keyBarArrow(KeyEvent.KEYCODE_DPAD_UP) } }
        findViewById<View>(R.id.btn_terminal_down)?.setOnClickListener { press { view -> view.keyBarArrow(KeyEvent.KEYCODE_DPAD_DOWN) } }
        findViewById<View>(R.id.btn_terminal_left)?.setOnClickListener { press { view -> view.keyBarArrow(KeyEvent.KEYCODE_DPAD_LEFT) } }
        findViewById<View>(R.id.btn_terminal_right)?.setOnClickListener { press { view -> view.keyBarArrow(KeyEvent.KEYCODE_DPAD_RIGHT) } }
        findViewById<View>(R.id.btn_terminal_enter)?.setOnClickListener { press { view -> view.keyBarEnter() } }
        findViewById<View>(R.id.btn_terminal_backspace)?.setOnClickListener { press { view -> view.keyBarBackspace() } }
    }

    /**
     * Refreshes the Ctrl key bar button (label + highlight) from the
     * active terminal's latch/sticky state.
     */
    private fun syncTerminalCtrlButton() {
        val btn = findViewById<TextView>(R.id.btn_terminal_ctrl) ?: return
        val active = terminalTabManager?.getActiveTerminalView()
        val latched = active?.isCtrlLatched() == true
        val sticky = active?.isCtrlSticky() == true

        btn.text = if (sticky) "Ctrl*" else "Ctrl"
        btn.setTextColor(
            if (latched) StudioUi.color("#C678DD") else StudioUi.color(StudioUi.TextSoft)
        )

        val bg = btn.background as? GradientDrawable ?: return
        bg.setColor(
            if (latched) StudioUi.color("#2D333B")
            else Color.TRANSPARENT
        )
        bg.setStroke(
            1,
            if (sticky) StudioUi.color("#C678DD") else StudioUi.color(StudioUi.Border)
        )
    }

    /**
     * Lazily creates the tabbed PTY terminal inside the Shell panel.
     * Falls back to the legacy shell output/input UI if the terminal fails to start.
     */
    private fun ensureTerminalTabs(cwd: File) {
        if (terminalTabManager != null) return
        val tabBar = findViewById<LinearLayout>(R.id.terminal_tab_bar)
        val container = findViewById<FrameLayout>(R.id.terminal_container)
        if (tabBar == null || container == null) return
        runCatching {
            val tm = TerminalTabManager(this, container, tabBar, cwd)
            tm.addNewTabButton()
            val session = tm.createNewTab("MobileStudio")
            terminalTabManager = tm
            tabBar.visibility = View.VISIBLE
            container.visibility = View.VISIBLE
            findViewById<View>(R.id.terminal_key_bar)?.visibility = View.VISIBLE
            findViewById<View>(R.id.shell_output_scroll)?.visibility = View.GONE
            findViewById<View>(R.id.shell_input_row)?.visibility = View.GONE
            wireTerminalToolbar()
            // If the shell process fails to start, bring the legacy shell UI back.
            container.postDelayed({
                if (terminalTabManager != null) {
                    showLegacyShellUi()
                }
            }, 2500)
        }.onFailure {
            showLegacyShellUi()
        }
    }

    private fun showLegacyShellUi() {
        terminalTabManager = null
        val tabBar = findViewById<LinearLayout>(R.id.terminal_tab_bar)
        val container = findViewById<FrameLayout>(R.id.terminal_container)
        container?.removeAllViews()
        tabBar?.removeAllViews()
        tabBar?.visibility = View.GONE
        container?.visibility = View.GONE
        findViewById<View>(R.id.terminal_key_bar)?.visibility = View.GONE
        findViewById<View>(R.id.shell_output_scroll)?.visibility = View.VISIBLE
        findViewById<View>(R.id.shell_input_row)?.visibility = View.VISIBLE
    }

    private fun currentBottomPanelTargetHeight(): Int {
        return bottomPanelExpandedHeight
            .takeIf { it >= 100.dp() }
            ?: bottomPanel.layoutParams.height.takeIf { it >= 100.dp() }
            ?: 780.dp()
    }

    /**
     * True when the build-output panel is docked beside the editor rather than
     * under it, which is what the landscape layout does. Read from the panel's
     * own layout params so it stays right after a rotation without re-installing
     * any listener.
     */
    private fun isBottomPanelSideDocked(): Boolean =
        (bottomPanel.layoutParams as? LinearLayout.LayoutParams)?.let { it.width > 0 } == true

    /** The dimension the panel is resized along: height below, width beside. */
    private fun bottomPanelSize(): Int =
        if (isBottomPanelSideDocked()) bottomPanel.width else bottomPanel.height

    private fun setBottomPanelSize(value: Int) {
        bottomPanel.layoutParams = bottomPanel.layoutParams.apply {
            if (isBottomPanelSideDocked()) width = value else height = value
        }
    }

    private fun animateBottomPanelHeight(start: Int, end: Int, onEnd: (() -> Unit)? = null) {
        bottomPanelHeightAnimator?.cancel()
        bottomPanelHeightAnimator = ValueAnimator.ofInt(start, end).apply {
            duration = StudioUi.AnimNormal
            interpolator = StudioUi.DefaultInterpolator
            addUpdateListener { animator ->
                setBottomPanelSize(animator.animatedValue as Int)
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (bottomPanelHeightAnimator === animation) {
                            bottomPanelHeightAnimator = null
                        }
                        onEnd?.invoke()
                    }
                }
            )
            start()
        }
    }

    private fun syncBottomPanelToggleRotation(expanded: Boolean, animate: Boolean = true) {
        val target = if (expanded) 180f else 0f
        listOfNotNull(findViewById<ImageButton>(R.id.btn_hide_panel)).forEach { button ->
            button.animate().cancel()
            if (animate) {
                button.animate()
                    .rotation(target)
                    .setDuration(StudioUi.AnimFast)
                    .setInterpolator(StudioUi.DefaultInterpolator)
                    .start()
            } else {
                button.rotation = target
            }
        }
        // The grabber is the panel's only handle while it is collapsed, so it
        // tracks the same state and turns around with it.
        val grabberIcon = findViewById<TextView>(R.id.shell_grabber_icon)
        val base = if (resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        ) -90f else 0f
        val grabberTarget = if (expanded) base + 180f else base
        grabberIcon?.animate()?.cancel()
        if (animate && grabberIcon != null) {
            grabberIcon.animate()
                .rotation(grabberTarget)
                .setDuration(StudioUi.AnimFast)
                .setInterpolator(StudioUi.DefaultInterpolator)
                .start()
        } else {
            grabberIcon?.rotation = grabberTarget
        }
    }

    internal fun switchToPanel(name: String) {
        currentBottomPanelTab = name
        val panels = mapOf(
            "build" to buildOutput,
            "logcat" to logcatContainer,
            "problems" to problemsContainer,
            "search" to findViewById<View>(R.id.search_panel),
            "shell" to findViewById<View>(R.id.shell_panel),
        )
        val targetView = panels[name] ?: return
        val shouldAnimate = StudioUi.shouldAnimate(this) && targetView.visibility != View.VISIBLE
        panels.values.filter { it != targetView }.forEach { it.visibility = View.GONE }
        if (shouldAnimate) {
            targetView.alpha = 0f
            targetView.visibility = View.VISIBLE
            targetView.animate()
                .alpha(1f)
                .setDuration(StudioUi.AnimFast)
                .setInterpolator(StudioUi.DefaultInterpolator)
                .start()
        } else {
            targetView.visibility = View.VISIBLE
        }

        if (name == "shell") {
            shellBannerShown = true
            ensureTerminalTabs(shellCwd ?: (currentProjectDir ?: StorageHelper.projectsRoot(this)))
        }
        updateDrawerLockState()
        wireTerminalToolbar()
        highlightPanelTab(name)
        // The rail is the only thing that says where you are, so it follows a
        // panel switch as well as a tap on itself - a bottom-panel tab moves
        // you just as surely as the rail does, and leaving the rail pointing at
        // the last thing you tapped there would have it disagreeing with the
        // screen from then on. Build and logcat are not on the rail: they are
        // states of the dock, not places.
        when (name) {
            "search" -> highlightSideRail("search")
            "problems" -> highlightSideRail("problems")
            "shell" -> highlightSideRail("shell")
            "ai" -> highlightSideRail("chat")
        }
if (name == "logcat") {
            logcatManager?.setFilterPackage(currentProjectDir?.let { detectPackage(it) })
        }

    }

    /**
     * Marks the visible panel's tab.
     *
     * It draws the same bordered chip the layout factory draws for the tab
     * that is selected at startup. It used to paint a flat fill instead, so
     * the border disappeared on the first switch and how the active tab looked
     * depended on how you had got there. The colours come from the same
     * tokens for the same reason.
     */
    private fun highlightPanelTab(name: String) {
        val tabId = when (name) {
            "build" -> R.id.tab_build
            "logcat" -> R.id.tab_logcat
            "problems" -> R.id.tab_problems
            "search" -> R.id.tab_search
            "shell" -> R.id.tab_shell
            else -> return
        }
        val color = when (name) {
            "build" -> StudioUi.ActiveGreen
            "logcat" -> StudioUi.ActiveAmber
            "problems" -> StudioUi.ActiveRed
            "search" -> StudioUi.ActiveBlue
            "shell" -> StudioUi.ActivePurple
            else -> StudioUi.ActiveBlue
        }
        val tabIds = listOf(
            R.id.tab_build, R.id.tab_logcat, R.id.tab_problems,
            R.id.tab_search, R.id.tab_shell
        )
        tabIds.forEach { id ->
            val tab = findViewById<TextView>(id) ?: return@forEach
            if (id == tabId) {
                tab.setTextColor(StudioUi.color(color))
                tab.background = StudioUi.rounded(
                    this,
                    fill = StudioUi.Surface2,
                    stroke = color,
                    radiusDp = StudioUi.RadiusMd,
                )
            } else {
                tab.setTextColor(StudioUi.color(StudioUi.InactiveIcon))
                tab.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun getSelectedText(): String? {
        val start = codeEditor.selectionStart.coerceIn(0, codeEditor.text?.length ?: 0)
        val end = codeEditor.selectionEnd.coerceIn(start, codeEditor.text?.length ?: 0)
        return if (start != end) {
            codeEditor.text?.subSequence(start, end)?.toString()
        } else null
    }

    private fun confirmDiscard(onConfirm: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Unsaved changes")
            .setMessage("Save changes?")
            .setPositiveButton("Save") { _, _ -> saveCurrentFile(); onConfirm() }
            .setNeutralButton("Discard") { _, _ -> unsavedChanges = false; onConfirm() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun showToast(msg: String) {
        val view = findViewById<View>(android.R.id.content)
        if (view != null) {
            com.google.android.material.snackbar.Snackbar.make(view, msg, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSnackbarWithAction(msg: String, actionText: String, action: () -> Unit) {
        val view = findViewById<View>(android.R.id.content) ?: return
        com.google.android.material.snackbar.Snackbar.make(view, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
            .setAction(actionText) { action() }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        val vib = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        vib?.vibrate(ms)
    }

    internal fun showSearchEverywhere(category: String = "All") {
        val root = currentProjectDir
        if (root == null) {
            showToast("No project open")
            return
        }
        searchEverywhere.show(root, category)
    }

    private fun showProblemActionsDialog(problem: BuildProblem) {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (problem.file?.exists() == true && problem.line > 0) {
            items.add("Go to Error Location")
            actions.add { openFileAtLine(problem.file!!, problem.line) }
        }

        val symbol = extractSymbolFromMessage(problem.message)
        if (symbol != null) {
            items.add("Find Usages of '$symbol'")
            actions.add {
                val usages = findSymbolUsages(symbol)
                showSymbolTargetsDialog(symbol, usages)
            }
        }

        items.add("Copy Error Message")
        actions.add {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Build Error", "${problem.message}\n${problem.raw}"))
            showToast("Error message copied")
        }

        items.add("Copy Full Output")
        actions.add {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Build Output", problem.raw))
            showToast("Output copied")
        }

        AlertDialog.Builder(this)
            .setTitle("Build Problem")
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extractSymbolFromMessage(message: String): String? {
        val patterns = listOf(
            Regex("""Unresolved reference:\s*(\w+)"""),
            Regex("""Cannot find name\s*'?(\w+)'?"""),
            Regex("""Reference to missing variable\s*'?(\w+)'?"""),
            Regex("""Function\s*'(\w+)'"""),
            Regex("""Type\s*'?(\w+)'?\s*has no member"""),
            Regex("""Property\s*'?(\w+)'?\s*not found""")
        )
        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun scrollEditorToBottom() {
        val scrollView = codeEditor.parent as? ScrollView ?: return
        val editorHeight = codeEditor.height
        if (editorHeight > 0) {
            scrollView.smoothScrollTo(0, editorHeight)
        }
    }

    private fun updateBadges() {
        val warnings = liveErrors.count { it.severity == BuildProblem.Severity.WARNING }
        val errors = liveErrors.count { it.severity == BuildProblem.Severity.ERROR }
        val badgeWarnings = findViewById<TextView>(R.id.badge_warnings)
        val badgeErrors = findViewById<TextView>(R.id.badge_errors)
        if (warnings > 0) {
            badgeWarnings.text = "\u25B2$warnings"
            badgeWarnings.visibility = View.VISIBLE
        } else {
            badgeWarnings.visibility = View.GONE
        }
        if (errors > 0) {
            badgeErrors.text = "\u2716$errors"
            badgeErrors.visibility = View.VISIBLE
        } else {
            badgeErrors.visibility = View.GONE
        }
    }

    private fun saveLastProject(dir: File) {
        getSharedPreferences("ide", MODE_PRIVATE).edit()
            .putString("last_project", dir.absolutePath).apply()
    }

    private fun restoreLastProject() {
        val path = getSharedPreferences("ide", MODE_PRIVATE).getString("last_project", null) ?: return
        val dir  = File(path)
        if (dir.exists()) openProject(dir)
    }

    private fun formatFileSize(bytes: Long) = when {
        bytes < 1024       -> "$bytes B"
        bytes < 1024*1024  -> "${bytes/1024} KB"
        else               -> "${bytes/1024/1024} MB"
    }

    private fun showNdkInstallDialog(retry: () -> Unit) {
        val githubVersions: List<String> = NdkToolchain.GITHUB_NDK_VERSIONS.map { "arm64 $it (GitHub)" }
        val googleVersions: List<String> = ToolchainManager.AVAILABLE_NDK
        val versions: Array<String> = Array(githubVersions.size + googleVersions.size) { i ->
            if (i < githubVersions.size) githubVersions[i] else googleVersions[i - githubVersions.size]
        }
        val defaultVersion: String = versions.firstOrNull { it.startsWith("arm64 29.") } ?: versions.first()
        val sdkVersion = ToolchainManager(this).activeVersion(ToolchainManager.Kind.SDK)
        if (sdkVersion == null) {
            buildOutput.appendLine("> Install an Android SDK first (Build Tools > SDK)", LineLevel.ERROR)
            return
        }

        val checkedItem = versions.indexOfFirst { v -> v == defaultVersion }.coerceAtLeast(0)

        val isKo = AppLanguage.isKorean(this)
        val listener = android.content.DialogInterface.OnClickListener { d, _ ->
            val dialog = d as? AlertDialog
            val idx = dialog?.listView?.checkedItemPosition?.takeIf { it >= 0 } ?: checkedItem
            val version: String = versions.getOrElse(idx) { defaultVersion }
            dialog?.dismiss()
            val githubVersion = NdkToolchain.GITHUB_NDK_VERSIONS.firstOrNull { version.startsWith("arm64 $it") }
            if (githubVersion != null) {
                installArm64NdkFromGithub(githubVersion, retry)
            } else {
                installNdkAndRetry(ToolchainManager(this), sdkVersion, version, retry)
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (isKo) "Android NDK 설치" else "Install Android NDK")
            .setMessage(if (isKo) "이 프로젝트의 C/C++ 컴파일에는 Android NDK가 필요합니다.\nNDK 버전을 선택하세요 (arm64 = GitHub 직접 다운로드, 그 외 = Google SDK 저장소)" else "This project requires the Android NDK for native C/C++ compilation.\nSelect NDK version (arm64 = GitHub download, others = Google SDK repository):")
            .setSingleChoiceItems(versions, checkedItem, null)
            .setPositiveButton(if (isKo) "설치" else "Install", listener)
            .setNegativeButton("Cancel", null as android.content.DialogInterface.OnClickListener?)
            .show()
    }

    private fun installArm64NdkFromGithub(version: String, retry: () -> Unit) {
        val isKo = AppLanguage.isKorean(this)
        val progress = AlertDialog.Builder(this)
            .setTitle(if (isKo) "NDK $version 설치 중" else "Installing NDK $version")
            .setMessage("Preparing...")
            .setCancelable(false)
            .create()
        progress.show()
        Thread {
            try {
                val ndkDir = NdkToolchain.installFromGithub(this, version) { msg ->
                    runOnUiThread { progress.setMessage(msg) }
                }
                ToolchainManager(this).setActivePath(ToolchainManager.Kind.NDK, ndkDir)
                runOnUiThread {
                    progress.dismiss()
                    buildOutput.appendLine("> NDK $version installed and activated (ARM64 clang). Restarting build...", LineLevel.SUCCESS)
                    retry()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progress.dismiss()
                    buildOutput.appendLine("> NDK $version install failed: ${e.message}", LineLevel.ERROR)
                    buildOutput.appendLine("> Check your network or run the build again after the download completes.", LineLevel.ERROR)
                }
            }
        }.start()
    }

    private fun checkForAppUpdate() {
        val currentVer = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        AppUpdateManager.checkForUpdates(
            this,
            currentVersionName = currentVer
        ) { info ->
            if (info == null) return@checkForUpdates
            showAppUpdateDialog(info)
        }
    }

    private fun showAppUpdateDialog(info: AppUpdateManager.UpdateInfo) {
        val currentVer = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        val isKo = AppLanguage.isKorean(this)
        val message = buildString {
            if (isKo) {
                appendLine("\uc0c8 \ubc84\uc804 ${info.latestTag} \uc0ac\uc6a9 \uac00\ub2a5!\n")
                appendLine("\ud604\uc7ac \ubc84\uc804: $currentVer")
                appendLine("\ucd5c\uc804: ${info.latestTag}")
                if (info.apkSize > 0) {
                    appendLine("\ud06c\uae30: ${info.apkSize / (1024 * 1024)} MB")
                }
                if (info.releaseNotes.isNotBlank()) {
                    appendLine("\n--- \ubcc0\uacbd \uc0ac\ud56d ---")
                    append(info.releaseNotes.take(500))
                }
            } else {
                appendLine("New version ${info.latestTag} available!\n")
                appendLine("Current: $currentVer")
                appendLine("Latest: ${info.latestTag}")
                if (info.apkSize > 0) {
                    appendLine("Size: ${info.apkSize / (1024 * 1024)} MB")
                }
                if (info.releaseNotes.isNotBlank()) {
                    appendLine("\n--- Release Notes ---")
                    append(info.releaseNotes.take(500))
                }
            }
        }

AlertDialog.Builder(this)
            .setTitle(if (isKo) "업데이트" else "Update Available")
            .setMessage(message)
            .setPositiveButton(if (isKo) "업데이트" else "Update Now") { _, _ ->
                downloadAndInstallUpdate(info)
            }
            .setNegativeButton(if (isKo) "나중에" else "Later") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun downloadAndInstallUpdate(info: AppUpdateManager.UpdateInfo) {
        val isKo = AppLanguage.isKorean(this)
        val progress = AlertDialog.Builder(this)
            .setTitle("Downloading ${info.latestTag}")
            .setMessage(if (isKo) "\ub2e4\uc6b4\ub85c\ub4dc \uc911.." else "Downloading...")
            .setCancelable(false)
            .show()

        AppUpdateManager.downloadAndInstall(this, info,
            onProgress = { pct ->
                Handler(Looper.getMainLooper()).post {
                    progress.setMessage("${pct}%")
                }
            },
            onDone = { success, error ->
                Handler(Looper.getMainLooper()).post {
                    progress.dismiss()
                    if (!success) {
                        AlertDialog.Builder(this)
                            .setTitle("Update Failed")
                            .setMessage(error ?: "Unknown error")
                            .setPositiveButton("OK") { d, _ -> d.dismiss() }
                            .show()
                    }
                }
            }
        )
    }

    private fun installNdkAndRetry(toolchain: ToolchainManager, sdkVersion: String, ndkVersion: String, retry: () -> Unit) {
        val isKo = AppLanguage.isKorean(this)
        val progress = AlertDialog.Builder(this)
            .setTitle("Installing NDK $ndkVersion")
            .setMessage(if (isKo) "\uc900\ube44\uc911.." else "Preparing...")
            .setCancelable(false)
            .create()
        progress.show()

        Thread {
            try {
                val sdkDir = toolchain.activeDir(ToolchainManager.Kind.SDK)
                    ?.takeIf { it.name == sdkVersion }
                    ?: File(toolchain.kindDir(ToolchainManager.Kind.SDK), sdkVersion)
                sdkDir.mkdirs()
                val ndkDir = File(sdkDir, "ndk/$ndkVersion")
                if (ndkDir.exists()) {
                    runOnUiThread { progress.setMessage(if (isKo) "NDK가 이미 설치되어 있습니다. 활성화 중..." else "NDK already installed, activating...") }
                } else {
                    val repositoryXml = URL("https://dl.google.com/android/repository/repository2-1.xml")
                        .openConnection().let { conn ->
                            conn as HttpURLConnection; conn.connectTimeout = 15000; conn.readTimeout = 30000
                            conn.inputStream.bufferedReader().readText()
                        }
                    val packagePath = "ndk;$ndkVersion"
                    val url = resolveNdkUrl(repositoryXml, packagePath)
                    runOnUiThread { progress.setMessage(if (isKo) "NDK $ndkVersion 다운로드 중..." else "Downloading NDK $ndkVersion...") }
                    val zip = File(cacheDir, "ndk-$ndkVersion.zip")
                    val temp = File(cacheDir, "ndk-extract-$ndkVersion").apply { deleteRecursively(); mkdirs() }
                    try {
                        downloadFileWithProgress(url, zip, isKo) { msg ->
                            runOnUiThread { progress.setMessage(msg) }
                        }
                        runOnUiThread { progress.setMessage(if (isKo) "NDK 압축 푸는 중..." else "Extracting NDK...") }
                        extractZipSimple(zip, temp) { name ->
                            runOnUiThread { progress.setMessage(if (isKo) "압축 푸는 중: $name" else "Extracting: $name") }
                        }
                        val source = locateNdkRoot(temp)
                        if (source != null) {
                            source.copyRecursively(ndkDir, overwrite = true)
                            ndkDir.walkTopDown().filter { it.isFile }.forEach {
                                it.setReadable(true, false)
                                if (it.parentFile?.name == "bin") it.setExecutable(true, false)
                            }
                        } else {
                            throw RuntimeException("Could not locate NDK root in archive")
                        }
                    } finally {
                        zip.delete(); temp.deleteRecursively()
                    }
                }
                toolchain.setActiveVersion(ToolchainManager.Kind.NDK, ndkVersion)
                runOnUiThread {
                    progress.dismiss()
                    buildOutput.appendLine("> NDK $ndkVersion installed and activated. Restarting build...", LineLevel.SUCCESS)
                    retry()
                }
            } catch (e: Exception) {
                val sw = java.io.StringWriter()
                java.io.PrintWriter(sw).use { e.printStackTrace(it) }
                runOnUiThread {
                    progress.dismiss()
                    buildOutput.appendLine("> NDK install failed: ${e.message}", LineLevel.ERROR)
                    sw.toString().lines().forEach { line ->
                        if (line.isNotBlank()) buildOutput.appendLine("  $line", LineLevel.ERROR)
                    }
                }
            }
        }.start()
    }

    private fun resolveNdkUrl(repositoryXml: String, packagePath: String): String {
        val block = Regex(
            """<remotePackage\b[^>]*\bpath="${Regex.escape(packagePath)}"[^>]*>[\s\S]*?</remotePackage>"""
        ).findAll(repositoryXml).map { it.value }.firstOrNull { "obsolete=\"true\"" !in it }
            ?: throw RuntimeException("NDK package not found: $packagePath")
        val url = Regex("""<url>([^<]+)</url>""").find(block)?.groupValues?.get(1)
            ?: throw RuntimeException("No download URL for $packagePath")
        return "https://dl.google.com/android/repository/$url"
    }

    private fun downloadFileWithProgress(url: String, dest: File, isKorean: Boolean = false, onProgress: (String) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000; conn.readTimeout = 120000
        conn.instanceFollowRedirects = true
        conn.connect()
        val total = conn.contentLengthLong
        dest.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var lastEmit = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    output.write(buf, 0, read)
                    done += read
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 1000) {
                        lastEmit = now
                        val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else -1
                        val prefix = if (isKorean) "NDK \ub2e4\uc6b4\ub85c\ub4dc \uc911.. " else "Downloading NDK... "
                        onProgress(if (pct >= 0) "$prefix$pct% (${formatFileSize(done)} / ${formatFileSize(total)})"
                            else "$prefix${formatFileSize(done)}")
                    }
                }
            }
        }
    }

    private fun extractZipSimple(zipFile: File, destDir: File, onEntry: ((String) -> Unit)? = null) {
        java.util.zip.ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                onEntry?.invoke(entry.name)
                val target = File(destDir, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    fun getTotalErrorCount(): Int {
        return (liveErrors + buildErrors).distinctBy {
            "${it.file?.absolutePath}:${it.line}:${it.column}:${it.severity}:${it.message}"
        }.count { it.severity == BuildProblem.Severity.ERROR }
    }

    fun getErrorsAsText(): String {
        return (liveErrors + buildErrors).distinctBy {
            "${it.file?.absolutePath}:${it.line}:${it.column}:${it.severity}:${it.message}"
        }.filter { it.severity == BuildProblem.Severity.ERROR }
            .take(10)
            .joinToString("\n") { 
                "${it.file?.name}:${it.line}:${it.column}: ${it.message}"
            }
    }

    fun getErrorFingerprints(): List<com.aide.mobilestudio.analysis.ErrorFingerprint> {
        return (liveErrors + buildErrors).distinctBy {
            "${it.file?.absolutePath}:${it.line}:${it.column}:${it.severity}:${it.message}"
        }.filter { it.severity == BuildProblem.Severity.ERROR }
            .map { com.aide.mobilestudio.analysis.ErrorFingerprint.fromBuildProblem(it, currentProjectDir) }
    }

    /** All current problems (errors, warnings, info) for the AI validation pipeline. */
    fun getAllProblems(): List<BuildProblem> {
        return (liveErrors + buildErrors).distinctBy {
            "${it.file?.absolutePath}:${it.line}:${it.column}:${it.severity}:${it.message}"
        }
    }

    private fun checkProjectUpdates() {
        val root = currentProjectDir ?: return
        val bannerContainer = findViewById<LinearLayout>(R.id.banner_container) ?: return
        bannerContainer.removeAllViews()
        bannerContainer.visibility = View.GONE

        val buildFile = File(root, "build.gradle.kts")
        if (!buildFile.exists()) return
        
        val content = buildFile.readText()
        val agpMatch = Regex("""id\("com.android.application"\)\s*version\s*"([^"]+)"""").find(content)
            ?: Regex("""id\("com.android.library"\)\s*version\s*"([^"]+)"""").find(content)
            
        if (agpMatch != null) {
            val version = agpMatch.groupValues[1]
            if (version < "9.2.1") {
                showUpgradeBanner("9.2.1")
            }
        }
    }

    private fun showUpgradeBanner(newVersion: String) {
        val bannerContainer = findViewById<LinearLayout>(R.id.banner_container) ?: return
        bannerContainer.removeAllViews()
        bannerContainer.visibility = View.VISIBLE

        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Card2))
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
            
            val header = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "⚠️ Project update recommended"
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
            }
            addView(header)

            addView(TextView(this@MainActivity).apply {
                text = "Android Gradle plugin version $newVersion has an upgrade available. Start the AGP Upgrade Assistant to update this project."
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, 4.dp, 0, 8.dp)
            })

            val actionRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                
                val btnUpgrade = Button(this@MainActivity).apply {
                    text = "Start AGP Upgrade Assistant"
                    isAllCaps = false
                    setPadding(12.dp, 0, 12.dp, 0)
                    setOnClickListener { 
                        runAgpUpgrade(newVersion)
                        bannerContainer.visibility = View.GONE
                    }
                }
                addView(btnUpgrade)
            }
            addView(actionRow)
        }
        bannerContainer.addView(banner)
    }

    private fun runAgpUpgrade(targetVersion: String) {
        val root = currentProjectDir ?: return
        val buildFile = File(root, "build.gradle.kts")
        if (buildFile.exists()) {
            var content = buildFile.readText()
            content = content.replace(Regex("""(id\("com.android.application"\)\s*version\s*")([^"]+)(")"""), "$1$targetVersion$3")
            content = content.replace(Regex("""(id\("com.android.library"\)\s*version\s*")([^"]+)(")"""), "$1$targetVersion$3")
            buildFile.writeText(content)
            Toast.makeText(this, "Project updated to AGP $targetVersion. Please Sync Gradle.", Toast.LENGTH_LONG).show()
            gradleSyncNeeded = true
        }
    }

    private fun locateNdkRoot(temp: File): File? {
        temp.walkTopDown().firstOrNull { it.isFile && it.name == "source.properties" }?.parentFile?.let { return it }
        temp.walkTopDown().firstOrNull { it.isDirectory && File(it, "toolchains/llvm").exists() }?.let { return it }
        temp.listFiles()?.firstOrNull { it.isDirectory && it.name.startsWith("android-ndk") }?.let { return it }
        return temp
    }

private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
}


