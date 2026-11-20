package com.aide.mobilestudio

import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.aide.mobilestudio.analysis.AnalysisEngine
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Contract between the Jetpack Compose IDE shell ([StudioUi]) and the hosting
 * activity ([MainActivity]).
 *
 * The Compose tree is deliberately kept free of any concrete activity type so
 * that all the heavy machinery of [MainActivity] - Gradle builds, terminal
 * sessions, Git, live diagnostics, extension host, virtualised editor - keeps
 * working while the visible UI is the Compose studio shell.
 */
interface StudioHost {
    /** Activity context wrapper - used for dialogs, toasts and resources. */
    val studioActivity: MainActivity

    /** Coroutine scope tied to the activity lifecycle. */
    val studioScope: CoroutineScope

    /** Root of the currently open project, if any. */
    val studioProjectDir: File?

    /** File currently displayed in the editor, if any. */
    val studioCurrentFile: File?

    /** Absolute paths (relative to [studioProjectDir]) of the open editor tabs. */
    val studioTabs: MutableList<String>

    /** Index of the active editor tab. */
    val studioSelectedTab: Int

    /** Breadcrumb / status path of the active file. */
    val studioPathText: String

    /** Live content of the editor pane. */
    var studioFileContent: String

    /** Flattened project tree used by the Compose file drawer. */
    val studioTreeItems: SnapshotStateList<FileItem>

    /** Live diagnostics for the current editor file. */
    val studioEditorProblems: MutableList<EditorProblem>

    /** Real-time compiler engine (null until a project is opened). */
    val studioAnalysisEngine: AnalysisEngine?

    /** The in-window Tools panel currently hosting a file picker, if any. */
    var studioToolsPanelForResult: ToolsPanel?

    /** Whether the Android Studio style "New Project" dialog is visible. */
    var studioNewProjectDialogVisible: Boolean

    /** Launcher used to pick a project directory through SAF. */
    val studioPickDir: ActivityResultLauncher<android.net.Uri?>

    /** Launcher used to pick a NEW PROJECT parent folder (does not open it). */
    val studioPickNewProjectDir: ActivityResultLauncher<android.net.Uri?>

    /** Callback invoked with the chosen new-project parent path. */
    var studioOnNewProjectDirPicked: ((String) -> Unit)?

    // ── Actions ──────────────────────────────────────────────────────────

    /** Opens [dir] as the active project. */
    fun studioOpenProject(dir: File)

    /** Opens [file] in the editor pane. */
    fun studioOpenFileInEditor(file: File)

    /** Creates (or focuses) a new editor tab named [name]. */
    fun studioNewEditorTab(name: String)

    /** Closes the editor tab at [index]. */
    fun studioCloseEditorTab(index: Int)

    /** Activates the editor tab at [index]. */
    fun studioSelectTab(index: Int)

    /** Moves the editor caret to [line]. */
    fun studioSelectEditorLine(line: Int)

    /** Reloads the flattened project tree from [root]. */
    fun studioLoadFileTree(root: File)

    /** Expands/collapses [dir] in the flattened project tree. */
    fun studioToggleTreeFolder(dir: File)

    /** Persists the current editor buffer. */
    fun studioSaveCurrentFile()

    /** Re-runs the live diagnostics for the current file. */
    fun studioRequestLiveDiagnostics()

    /** Path of the last opened project (persisted). */
    fun studioGetLastProject(): String?

    /** Records [path] as the last opened project. */
    fun studioPutLastProject(path: String)

    /** Recently opened projects, most recent first. */
    fun studioGetRecentProjects(): List<String>

    /** Recently opened files, most recent first. */
    fun studioGetRecentFiles(): List<String>

    // ── New: Compose shell infrastructure hooks ──────────────────────────

    /** File encoding of the file shown in the editor. */
    val studioFileEncoding: String

    /** Whether a Gradle build is currently running (for shell stop / run buttons). */
    val studioIsBuildRunning: Boolean

    /** Whether the currently open tab has unsaved modifications. */
    val studioTabDirty: Boolean

    /** Currently checked out git branch (null when not in a repo). */
    val studioGitBranch: String?

    /** Serial of the first connected adb device, or null when none. */
    val studioConnectedDevice: String?

    /** Application id the IDE would launch after a build. */
    val studioPackageName: String?

    /** Debounced persistence hook wired into the Compose editor buffer. */
    fun studioScheduleAutosave()

    /** Cancels an in-flight Gradle build. */
    fun studioStopBuild()

    /** Runs the full run pipeline: assemble → install → launch. */
    fun studioInstallAndLaunchLatest()

    /** Opens [file] and moves the caret to 1-based [line]. */
    fun studioOpenFileAt(file: File, line: Int)
}

/**
 * Global state shared between the Compose editor pane and the imperative
 * editor helpers (find/replace, auto-complete, formatting).
 */
object EditorPaneState {
    /** Monotonic revision bumped whenever the active project is (re)opened. */
    val projectRevision = androidx.compose.runtime.mutableStateOf(0)

    /** Current editor text. */
    var content: String = ""

    /** Whether the buffer has unsaved modifications. */
    var unsaved: Boolean = false

    /** The live [LineNumberEditText] instance, if the editor is mounted. */
    var editor: LineNumberEditText? = null

    /** Absolute/relative path of the file currently shown in the editor. */
    var currentFilePath: String = ""

    /** 1-based caret line, published by the editor for the IDE status bar. */
    val caretLine = androidx.compose.runtime.mutableStateOf(0)

    /** 1-based caret column, published by the editor for the IDE status bar. */
    val caretColumn = androidx.compose.runtime.mutableStateOf(0)
}
