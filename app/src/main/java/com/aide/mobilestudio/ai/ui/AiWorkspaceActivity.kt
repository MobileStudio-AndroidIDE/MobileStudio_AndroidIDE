package com.aide.mobilestudio.ai.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.Spinner
import com.aide.mobilestudio.AppLanguage
import com.aide.mobilestudio.R
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aide.mobilestudio.DiffViewerActivity
import com.aide.mobilestudio.ShellEnvironment
import com.aide.mobilestudio.ShellRunner
import com.aide.mobilestudio.StudioUi
import com.aide.mobilestudio.highlight.LanguageHighlighter
import com.aide.mobilestudio.DownloadCenter
import com.aide.mobilestudio.ai.AiSettings
import com.aide.mobilestudio.ai.opencode.tool.DownloadResult
import com.aide.mobilestudio.ai.agent.CheckpointOutcome
import com.aide.mobilestudio.ai.agent.PromptContextBadge
import com.aide.mobilestudio.ai.agent.ProjectFiles
import com.aide.mobilestudio.ai.agent.StatusLine
import com.aide.mobilestudio.ai.build.GradleOutput
import com.aide.mobilestudio.ai.command.SlashCommand
import com.aide.mobilestudio.ai.command.SlashCommandAction
import com.aide.mobilestudio.ai.command.SlashCommandRegistry
import com.aide.mobilestudio.ai.command.SlashInvocation
import com.aide.mobilestudio.ai.model.ApiModelFetcher
import com.aide.mobilestudio.ai.model.DownloadEngine
import com.aide.mobilestudio.ai.model.DownloadState
import com.aide.mobilestudio.ai.model.DownloadTask
import com.aide.mobilestudio.ai.model.GgufCatalog
import com.aide.mobilestudio.ai.model.GgufSuggestion
import com.aide.mobilestudio.ai.model.HuggingFaceApi
import com.aide.mobilestudio.ai.model.HuggingFaceSource
import com.aide.mobilestudio.ai.model.ModelCatalog
import com.aide.mobilestudio.ai.model.ModelDownloadService
import com.aide.mobilestudio.ai.model.ModelDownloads
import com.aide.mobilestudio.ai.model.ModelEntry
import com.aide.mobilestudio.ai.model.ModelFilter
import com.aide.mobilestudio.ai.model.ModelQuery
import com.aide.mobilestudio.ai.model.ModelSort
import com.aide.mobilestudio.ai.model.ModelSource
import com.aide.mobilestudio.ai.model.ModelTab
import com.aide.mobilestudio.ai.model.ParamBand
import com.aide.mobilestudio.ai.model.ParamsSource
import com.aide.mobilestudio.ai.model.QuantFamily
import com.aide.mobilestudio.ai.opencode.agent.OcEngine
import com.aide.mobilestudio.ai.opencode.provider.OcModelInfo
import com.aide.mobilestudio.ai.orchestrator.AgentTask
import com.aide.mobilestudio.ai.orchestrator.TaskStatus
import com.aide.mobilestudio.ai.opencode.session.OcMessage
import com.aide.mobilestudio.ai.opencode.session.OcSessionStore
import com.aide.mobilestudio.ai.provider.AndroidHttpTransport
import com.aide.mobilestudio.ai.provider.AuthStyle
import com.aide.mobilestudio.ai.provider.ConnectionTester
import com.aide.mobilestudio.ai.provider.ProviderEndpoint
import com.aide.mobilestudio.ai.provider.ProviderModelList
import com.aide.mobilestudio.ai.resource.AndroidMemoryProbe
import com.aide.mobilestudio.ai.resource.ResourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * New AI workspace. Replaces the old chat panel as the primary AI entry point.
 *
 * The screen is a small IDE for the agent: Chat, Plan, Changes, Diagnostics,
 * Sessions, Models, Settings and Terminal. It talks to the same [OcEngine] the
 * old panel used (no engine fork) and adds its own live validation through
 * [WorkspaceValidation] so it works outside MainActivity as well.
 */
class AiWorkspaceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROJECT_DIR = "project_dir"

        /** File currently open in the editor, shown as context in the top bar. */
        const val EXTRA_CURRENT_FILE = "current_file"

        /**
         * Message submitted to the agent as soon as the workspace opens. Used
         * by editor actions that used to type into the old bottom-panel AI
         * ("Explain this file", crash analysis) so they land in the workspace
         * conversation instead.
         */
        const val EXTRA_INITIAL_MESSAGE = "initial_message"

        /**
         * Extra consumed by MainActivity's onCreate ("open_file" → open that
         * absolute path in the editor, optionally with "open_line"). Used by
         * [openInEditor]; no MainActivity change is required for it.
         */
        const val EXTRA_OPEN_IN_EDITOR = "open_file"

        /** Tools whose successful run means a project file was modified. */
        private val FILE_CHANGING_TOOLS = setOf("write", "edit", "patch")

        private const val REQUEST_ATTACHMENT = 4001
        private const val REQUEST_MIC = 4002

        /** Bound on the reasoning text kept in one card; the rest is dropped. */
        private const val REASONING_MAX_CHARS = 16_000

        /**
         * Tool output longer than this is collapsed to its first lines with a
         * "Show more" affordance; the full text is one tap away.
         */
        private const val SHOW_MORE_LINES = 10

        /** Bound on the build log kept in memory (and painted); older lines fall off. */
        private const val BUILD_MAX_LINES = 6_000

        /** ANSI colour/control sequences Gradle may emit in its log. */
        private val ANSI_ESCAPE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

        /**
         * Open the full-screen AI workspace - the primary AI surface.
         *
         * @param projectDir  project root the agent operates on; the editor
         *   AI button passes the open project here.
         * @param currentFile file open in the editor, shown in the top bar as
         *   conversational context. Optional with a null default so existing
         *   callers keep compiling; callers wanting file context add the
         *   argument (see [EXTRA_CURRENT_FILE]).
         * @param initialMessage prompt submitted right after the workspace
         *   opens (see [EXTRA_INITIAL_MESSAGE]); null leaves the composer to
         *   the user.
         */
        fun start(
            context: Context,
            projectDir: String? = null,
            currentFile: String? = null,
            initialMessage: String? = null,
        ) {
            val intent = Intent(context, AiWorkspaceActivity::class.java)
            if (projectDir != null) intent.putExtra(EXTRA_PROJECT_DIR, projectDir)
            if (currentFile != null) intent.putExtra(EXTRA_CURRENT_FILE, currentFile)
            if (!initialMessage.isNullOrBlank()) intent.putExtra(EXTRA_INITIAL_MESSAGE, initialMessage)
            context.startActivity(intent)
        }
    }

    private enum class Tab(val label: String, val labelKo: String) {
        FILES("Files", "프로젝트"),
        CHAT("Chat", "채팅"),
        PLAN("Plan", "계획"),
        AGENTS("Agents", "에이전트"),
        RESULTS("Results", "결과"),
        LOGS("Logs", "로그"),
        CHANGES("Changes", "변경"),
        SEARCH("Search", "검색"),
        GIT("Git", "Git"),
        DIAGNOSTICS("Diagnostics", "진단"),
        TOOLS("Tools", "도구"),
        BUILD("Build", "빌드"),
        RESOURCES("Resources", "자원"),
        SESSIONS("Sessions", "세션"),
        MODELS("Models", "모델"),
        SETTINGS("Settings", "설정"),
        TERMINAL("Terminal", "터미널"),
    }

    /**
     * The five destinations of the left navigation rail (프로젝트 / 검색 / Git /
     * AI 어시스턴트 / 설정). Several [Tab]s share one rail destination; the AI and
     * settings destinations show a segmented sub-bar to switch between them.
     */
    private enum class Screen(val label: String, val labelKo: String, val icon: String) {
        PROJECT("Files", "프로젝트", "🗂"),
        SEARCH("Search", "검색", "🔍"),
        GIT("Git", "Git", "⑂"),
        AI("AI", "AI", "✦"),
        SETTINGS("Settings", "설정", "⚙"),
    }

    private fun screenOf(tab: Tab): Screen = when (tab) {
        Tab.FILES -> Screen.PROJECT
        Tab.SEARCH -> Screen.SEARCH
        Tab.GIT -> Screen.GIT
        Tab.SETTINGS, Tab.SESSIONS, Tab.MODELS -> Screen.SETTINGS
        else -> Screen.AI
    }

    /**
     * Built in [onCreate], **not** as a field initializer. Activity field
     * initializers run before the framework attaches the base context, so
     * constructing the engine here crashed on device with
     * `getFilesDir() on a null object reference` (OcSessionStore → OcEngine).
     */
    private lateinit var engine: OcEngine
    private lateinit var validation: WorkspaceValidation
    private val settings by lazy { AiSettings(this) }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var projectDir: File? = null

    /** File open in the editor when the workspace was launched, if provided. */
    private var currentFile: File? = null
    private var activeTab = Tab.CHAT
    private val changedFiles = linkedSetOf<String>()
    private val READ_ONLY_HINT = setOf(
        "read", "glob", "grep", "webfetch", "websearch",
        "ls", "file_info", "diff", "git", "sqlite_query", "zip_list",
        "network_info", "current_time", "app_info", "model_search", "model_download_status",
    )

    // top bar
    private lateinit var titleText: TextView
    private lateinit var modelText: TextView
    private lateinit var statusText: TextView
    private lateinit var projectText: TextView
    private lateinit var statusPill: TextView
    private lateinit var tokenCounter: TextView
    private lateinit var railRow: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var statusBar: TextView

    // assistant header chips (kept so pickers can refresh their labels)
    private lateinit var agentChip: TextView
    private lateinit var modelChip: TextView

    // project file browser (프로젝트 rail destination)
    private var browseDir: File? = null
    private lateinit var filesList: LinearLayout

    // git screen output
    private lateinit var gitOutput: TextView

    // composer attachments
    private var pendingImageBase64: String? = null

    // chat (persistent)
    private lateinit var chatScroll: ScrollView
    private lateinit var chatMessages: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var thinkingView: TextView
    private lateinit var retryView: TextView
    private var streamingView: TextView? = null
    private var streamingCard: LinearLayout? = null

    /**
     * A tool call shown as a card in the transcript. The card is clickable even
     * while running; once the result arrives, one tap opens the full output.
     */
    private data class LiveToolCard(
        val card: LinearLayout,
        val header: TextView,
        val preview: TextView,
        val name: String,
        val args: JSONObject?,
        var result: String?,
        var isError: Boolean,
        var finished: Boolean,
    )

    private val liveToolCards = mutableListOf<LiveToolCard>()

    /**
     * Running tool cards by tool name, so streamed command output
     * ([OcEngine.Listener.onCommandOutput]) can find the card a running shell
     * tool is painting into without racing the tool-start/end lifecycle.
     */
    private val liveCommandCards = mutableMapOf<String, LiveToolCard>()

    /**
     * Bumped whenever the retry countdown must stop (attempt started, run
     * finished). Pending runnables compare their token and bail out.
     */
    private var retryTickToken = 0

    private var engineState = OcEngine.AgentState.IDLE
    private var thinkDots = 0
    private var thinkBase = "Ready"

    // Generation speed (t/s). The clock used to start at the first streamed
    // chunk and count characters at a fixed 4 chars/token: that is a guess,
    // wrong for Korean and source code alike, and it mixed prefill time into
    // the decode measurement. Now the engine reports exact output tokens and
    // decode time from the provider (native llama.cpp token callback, or the
    // API's usage field). When nothing is reported the counter shows "— t/s"
    // instead of a fabricated number.
    private var lastTokensPerSec = 0.0

    // --- slash commands + @file mentions + context badge ---

    // --- slash commands + @file mentions + context badge ---
    private val slashRegistry = SlashCommandRegistry()
    private var slashPopup: ListPopupWindow? = null
    private var mentionPopup: ListPopupWindow? = null
    private var mentionFiles: List<String> = emptyList()
    private var mentionFilesRoot: File? = null
    private var mentionFilesLoading = false
    private var activeMentionToken: String? = null
    private lateinit var contextBadge: TextView

    // ------------------------------------------------ unified model picker
    // One list for API models, installed GGUFs and Hugging Face download
    // candidates. Sizes, quant labels and checksums are always the values the
    // source reported; unknown values are shown as unknown.

    private var pickerTab: ModelTab = ModelTab.ALL
    private var pickerSort: ModelSort = ModelSort.NAME
    private var pickerQuery = ""
    private var pickerProvider: String? = null
    private val pickerQuantFamilies = mutableSetOf<QuantFamily>()
    private val pickerBands = mutableSetOf<ParamBand>()
    private var pickerInstalledOnly = false
    private var pickerKnown: List<ModelEntry> = emptyList()
    private var pickerRemote: List<ModelEntry> = emptyList()
    private var pickerMessage: String? = null
    private var pickerSearching = false
    private var pickerPopularAttempted = false
    private var pickerList: LinearLayout? = null
    private var pickerTabsRow: LinearLayout? = null
    private var pickerFilterHost: LinearLayout? = null
    private var pickerReasoningRow: LinearLayout? = null
    private var pickerSearchField: EditText? = null
    private val downloadProgressBars = mutableMapOf<String, ProgressBar>()
    private val downloadProgressTexts = mutableMapOf<String, TextView>()
    private var downloadSignature = ""
    private var downloadObserverStarted = false

    /** Downloads survive tab switches; the holder owns exactly one instance. */
    private val modelDownloads by lazy { ModelDownloads.get(this) }

    private val modelCatalog by lazy {
        ModelCatalog(
            context = this,
            modelManager = com.aide.mobilestudio.ai.agent.ModelManager(this),
            worktreeProvider = { projectDir },
            hfSource = HuggingFaceSource(AndroidHttpTransport()),
            apiFetcher = ApiModelFetcher(AndroidHttpTransport()),
        )
    }

    // "Think" mode chip (the toggle itself lives in the engine/AiSettings).
    private var thinkChip: TextView? = null

    // Live reasoning card: one per turn, finished into a collapsed history
    // card when the model starts answering or calling tools.
    private var reasoningView: ReasoningView? = null

    private class ReasoningView(val card: LinearLayout, val header: TextView, val body: TextView) {
        var expanded = false
        var chars = 0
        var live = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Belt and braces with MobileStudioApp: the workspace is the single AI
        // surface now and must be able to reach the local runtime even if the
        // Application-level init is ever lost.
        com.aide.mobilestudio.ai.LlamaRuntimeManager.initAppContext(applicationContext)
        // The base context is attached by the time onCreate runs, so the
        // engine/storage may safely touch filesDir and SharedPreferences here.
        engine = OcEngine(this)
        validation = WorkspaceValidation(this)
        com.aide.mobilestudio.UiTheme.resolve(this)
        setContentView(buildRoot())

        projectDir = intent.getStringExtra(EXTRA_PROJECT_DIR)?.let { File(it) }?.takeIf { it.isDirectory }
        currentFile = intent.getStringExtra(EXTRA_CURRENT_FILE)?.let { File(it) }?.takeIf { it.isFile }
        engine.projectDir = projectDir
        engine.reloadConfig()
        // The composer belongs to the chat screen and is built lazily by
        // chatRoot() the first time that screen is shown - which happens well
        // after this point. The engine reports its state the instant a session
        // is loaded, though, and loadSession calls back synchronously, from
        // inside resumeLatestSessionOrStartNew() below. That first report
        // therefore landed on a composer that did not exist yet and threw
        // UninitializedPropertyAccessException on sendButton, killing the
        // workspace on every launch that had a stored session to resume. Build
        // the chat views now: chatRoot() reuses them, so the screen switch
        // later on is unchanged.
        buildChatViews()
        setupEngine()
        resumeLatestSessionOrStartNew()
        renderSessionHistory()
        resumedSessionTitle?.let {
            addSystemBubble(t("Resumed previous conversation", "이전 대화를 복원했습니다") + ": " + it)
        }
        showTab(Tab.CHAT)
        updateModelLabel()

        // Editor actions (explain file / crash report) used to type into the
        // old bottom-panel AI; they now hand the prompt over to this screen.
        intent.getStringExtra(EXTRA_INITIAL_MESSAGE)?.takeIf { it.isNotBlank() }?.let { submit(it) }

        if (projectDir != null) {
            scope.launch {
                withContext(Dispatchers.IO) { validation.beginBaseline(projectDir!!) }
                if (activeTab == Tab.DIAGNOSTICS) showTab(Tab.DIAGNOSTICS)
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // A build must not outlive the screen that started it; otherwise the
        // process keeps writing into a dead activity's pipe.
        runCatching { buildProcess?.destroy() }
        runCatching { engine.stop() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    /**
     * Reopening the workspace must not wipe the conversation: a fresh
     * [OcEngine] starts with no session, so unconditionally calling
     * [OcEngine.newSession] here created a new empty one on every relaunch
     * even though the session store still had the transcript on disk. Resume
     * the newest stored session instead; only first launches (nothing stored
     * yet) start empty.
     */
    private fun resumeLatestSessionOrStartNew() {
        if (engine.currentSessionId() != null) return
        val latest = engine.listSessions(includeArchived = false).firstOrNull()
        if (latest == null) {
            engine.newSession()
        } else {
            engine.loadSession(latest.id)
            resumedSessionTitle = latest.title
        }
    }

    /** Title of the stored session resumed on launch, shown once after render. */
    private var resumedSessionTitle: String? = null

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- root UI

    private fun buildRoot(): View {
        val p = StudioUi.palette
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(buildTopBar())
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        body.addView(buildNavRail(), LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.MATCH_PARENT))
        content = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        body.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        statusBar = TextView(this).apply {
            setTextColor(p.muted)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = AiUiKit.rounded(p.surface, 0)
        }
        root.addView(statusBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateStatusBar()
        return root
    }

    private fun updateStatusBar() {
        if (!::statusBar.isInitialized) return
        val state = when (engineState) {
            OcEngine.AgentState.THINKING -> t("Thinking", "생각 중")
            OcEngine.AgentState.GENERATING -> t("Writing", "작성 중")
            OcEngine.AgentState.EXECUTING_TOOL, OcEngine.AgentState.PROCESSING_TOOL_RESULT -> t("Working", "작업 중")
            OcEngine.AgentState.WAITING_TOOL_APPROVAL -> t("Approval", "승인 대기")
            OcEngine.AgentState.ERROR -> t("Error", "오류")
            OcEngine.AgentState.CANCELLED -> t("Cancelled", "취소")
            else -> t("Ready", "준비")
        }
        statusBar.text = "$state  ·  ${engine.estimateTokens()} tok  ·  ${engine.currentModelLabel()}  ·  UTF-8"
    }

    private fun buildTopBar(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AiUiKit.rounded(p.surface, 0)
            setPadding(dp(14), dp(12), dp(14), dp(8))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(AiUiKit.circle(this, "AI", p.accent, p.onAccent, 30))
        titleText = TextView(this).apply {
            text = t("AI Workspace", "AI 작업공간")
            setTextColor(p.text)
            textSize = StudioUi.TypeHeading
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(10), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        projectText = AiUiKit.pill(this, "", p.surface2, p.textSoft)
        row.addView(titleText)
        row.addView(projectText)
        // Quick navigation + build actions, as in the IDE design (검색 / 실행 / 정리).
        row.addView(
            AiUiKit.actionChip(this, "🔍") { _ -> showTab(Tab.SEARCH) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) },
        )
        val runButton = AiUiKit.pill(this, "▶", AiUiKit.withAlpha(p.success, 0.85f), p.onAccent)
        runButton.setOnClickListener { runGradleBuild() }
        row.addView(
            runButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) },
        )
        row.addView(
            AiUiKit.actionChip(this, "🧹") { _ -> runGradleClean() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) },
        )
        // Persistent escape hatch on every tab: finish the workspace and drop
        // back on the editor exactly as it was. The transcript lives in the
        // session store, so reopening the workspace resumes it (see
        // resumeLatestSessionOrStartNew) and scrolls to the bottom.
        row.addView(
            AiUiKit.actionChip(this, t("‹ Editor", "‹ 편집기")) { _ -> finish() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) },
        )
        column.addView(row)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        modelText = TextView(this).apply {
            setTextColor(p.accent)
            textSize = StudioUi.TypeSmall
            setOnClickListener { showModelPicker() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        tokenCounter = TextView(this).apply {
            this.text = "— t/s"
            setTextColor(p.muted)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(6), 0, dp(8), 0)
        }
        statusPill = AiUiKit.pill(this, t("Ready", "대기"), p.surface2, p.textSoft)
        row2.addView(modelText)
        row2.addView(tokenCounter)
        row2.addView(statusPill)
        column.addView(row2)

        statusText = TextView(this).apply { visibility = View.GONE }
        column.addView(statusText)
        updateProjectChip()
        return column
    }

    private fun updateProjectChip() {
        val name = projectDir?.name ?: t("No project", "프로젝트 없음")
        val file = currentFile?.name
        projectText.text = if (file != null) "$name · $file" else name
    }

    private fun updateStatusPill(text: String, color: Int) {
        statusPill.text = text
        statusPill.setTextColor(color)
        statusPill.background = AiUiKit.rounded(AiUiKit.withAlpha(color, 0.15f), dp(999))
    }

    private fun buildNavRail(): View {
        val p = StudioUi.palette
        railRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AiUiKit.rounded(p.surface, 0)
            setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        for (screen in Screen.entries) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(9), dp(2), dp(9))
                tag = screen
                setOnClickListener {
                    when (screen) {
                        Screen.PROJECT -> showTab(Tab.FILES)
                        Screen.SEARCH -> showTab(Tab.SEARCH)
                        Screen.GIT -> showTab(Tab.GIT)
                        Screen.AI -> showTab(Tab.CHAT)
                        Screen.SETTINGS -> showTab(Tab.SETTINGS)
                    }
                }
            }
            item.addView(TextView(this).apply {
                text = screen.icon
                textSize = 16f
                gravity = Gravity.CENTER
            })
            item.addView(TextView(this).apply {
                text = t(screen.label, screen.labelKo)
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(p.textSoft)
            })
            railRow.addView(
                item,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
        return railRow
    }

    private fun highlightRail() {
        val p = StudioUi.palette
        if (!::railRow.isInitialized) return
        val active = screenOf(activeTab)
        for (i in 0 until railRow.childCount) {
            val item = railRow.getChildAt(i) as LinearLayout
            val selected = item.tag == active
            item.background = if (selected) AiUiKit.rounded(AiUiKit.withAlpha(p.accent, 0.18f), dp(10)) else null
            (item.getChildAt(0) as TextView).setTextColor(if (selected) p.accent else p.textSoft)
            (item.getChildAt(1) as TextView).setTextColor(if (selected) p.text else p.textSoft)
        }
    }

    private fun segmentedRow(items: List<Triple<String, Boolean, () -> Unit>>): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(2), dp(10), dp(4))
        }
        for ((label, selected, onClick) in items) {
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = AiUiKit.rippleContent(
                    this@AiWorkspaceActivity,
                    if (selected) StudioUi.palette.accent else StudioUi.palette.surface2,
                    dp(999),
                )
                setTextColor(if (selected) StudioUi.palette.onAccent else StudioUi.palette.textSoft)
                setOnClickListener { onClick() }
            }
            row.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) },
            )
        }
        return row
    }

    private enum class AiGroup { CHAT, PLAN, PROJECT, TOOLS }

    private fun aiGroupOf(tab: Tab): AiGroup = when (tab) {
        Tab.PLAN, Tab.AGENTS, Tab.RESULTS, Tab.LOGS -> AiGroup.PLAN
        Tab.CHANGES, Tab.DIAGNOSTICS -> AiGroup.PROJECT
        Tab.TOOLS, Tab.BUILD, Tab.RESOURCES, Tab.TERMINAL -> AiGroup.TOOLS
        else -> AiGroup.CHAT
    }

    private fun buildAiHeader(): View {
        val p = StudioUi.palette
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        row.addView(TextView(this).apply {
            text = t("AI Assistant", "AI 어시스턴트")
            setTextColor(p.text)
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // Start a fresh conversation without hunting through the Sessions tab.
        row.addView(
            AiUiKit.actionChip(this, "＋") { _ -> newChat() }.apply {
                contentDescription = t("New chat", "새 대화")
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) },
        )
        // Agent mode (build / plan / ...) — OpenCode-style agent switching.
        agentChip = AiUiKit.pill(this, engine.currentAgentName(), AiUiKit.withAlpha(p.accent, 0.15f), p.accent)
        agentChip.setOnClickListener { showAgentPicker() }
        row.addView(
            agentChip,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) },
        )
        // On-demand conversation compaction.
        row.addView(
            AiUiKit.actionChip(this, t("Compact", "압축")) { _ ->
                scope.launch {
                    val ok = runCatching { engine.compactNow() }.getOrDefault(false)
                    Toast.makeText(
                        this@AiWorkspaceActivity,
                        if (ok) t("Conversation compacted", "대화를 압축했습니다") else t("Compaction unavailable", "압축을 사용할 수 없습니다"),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) },
        )
        modelChip = AiUiKit.pill(this, compactModelLabel(), p.surface2, p.accent)
        modelChip.setOnClickListener { showModelPicker() }
        row.addView(modelChip)
        return row
    }

    private fun showAgentPicker() {
        val agents = runCatching { engine.listAgents() }.getOrDefault(emptyList())
        if (agents.isEmpty()) {
            Toast.makeText(this, t("No agents available", "사용 가능한 에이전트가 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        val names = agents.map { it.name }.toTypedArray()
        val current = engine.currentAgentName()
        themedDialog()
            .setTitle(t("Agent", "에이전트"))
            .setSingleChoiceItems(names, names.indexOf(current)) { dialog, which ->
                engine.setAgent(names[which])
                if (::agentChip.isInitialized) agentChip.text = engine.currentAgentName()
                dialog.dismiss()
                showTab(Tab.CHAT)
            }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    /**
     * Model picker for the header chip: switches to the unified Models tab,
     * where every provider's models sit in one list with context limits and
     * the current one checked, next to keys, endpoints and fetch.
     */
    private fun showModelPicker() {
        // The chip opens the unified picker tab: API models, installed GGUFs
        // and Hugging Face downloads in one place, with a safe switch that
        // reverts to the previous model when the new one cannot be created.
        showTab(Tab.MODELS)
    }

    /** Model label short enough for the assistant header chip. */
    private fun compactModelLabel(): String {
        val ref = runCatching { engine.currentModelRef() }.getOrNull() ?: return engine.currentModelLabel()
        val id = ref.modelID.substringAfterLast('/')
        val short = if (id.length > 18) id.take(17) + "…" else id
        return "${ref.providerID}/$short"
    }

    /** Fresh conversation: stop the current turn, new engine session, empty chat. */
    private fun newChat() {
        runCatching { engine.stop() }
        runCatching { engine.newSession() }
        changedFiles.clear()
        runCatching { validation.reset() }
        streamingView = null
        streamingCard = null
        chatMessages.removeAllViews()
        thinkingView.visibility = View.GONE
        contextBadge.visibility = View.GONE
        renderSessionHistory()
        resetTokenCounter()
        showTab(Tab.CHAT)
    }

    private fun resetTokenCounter() {
        lastTokensPerSec = 0.0
        if (::tokenCounter.isInitialized) tokenCounter.text = "— t/s"
    }

    private fun updateTokenCount() {
        if (!::tokenCounter.isInitialized) return
        tokenCounter.text = if (lastTokensPerSec > 0) {
            String.format(Locale.US, "%.1f t/s", lastTokensPerSec)
        } else {
            "— t/s"
        }
    }

    /** AI destination: header + (채팅/계획/프로젝트/도구) segmented bar + content. */
    private fun aiScreen(tab: Tab): View {
        val p = StudioUi.palette
        val group = aiGroupOf(tab)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        column.addView(buildAiHeader())
        column.addView(
            segmentedRow(
                listOf(
                    Triple(t("Chat", "채팅"), group == AiGroup.CHAT) { showTab(Tab.CHAT) },
                    Triple(t("Plan", "계획"), group == AiGroup.PLAN) { showTab(Tab.PLAN) },
                    Triple(
                        t("Project", "프로젝트"),
                        group == AiGroup.PROJECT,
                    ) { showTab(if (tab == Tab.DIAGNOSTICS) Tab.DIAGNOSTICS else Tab.CHANGES) },
                    Triple(
                        t("Tools", "도구"),
                        group == AiGroup.TOOLS,
                    ) { showTab(if (tab == Tab.TERMINAL) Tab.TERMINAL else Tab.TOOLS) },
                ),
            ),
        )
        when (group) {
            AiGroup.PLAN -> column.addView(
                segmentedRow(
                    listOf(
                        Triple(t("Plan", "계획"), tab == Tab.PLAN) { showTab(Tab.PLAN) },
                        Triple(t("Agents", "에이전트"), tab == Tab.AGENTS) { showTab(Tab.AGENTS) },
                        Triple(t("Results", "결과"), tab == Tab.RESULTS) { showTab(Tab.RESULTS) },
                        Triple(t("Logs", "로그"), tab == Tab.LOGS) { showTab(Tab.LOGS) },
                    ),
                ),
            )
            AiGroup.PROJECT -> column.addView(
                segmentedRow(
                    listOf(
                        Triple(t("Changes", "변경"), tab == Tab.CHANGES) { showTab(Tab.CHANGES) },
                        Triple(t("Diagnostics", "진단"), tab == Tab.DIAGNOSTICS) { showTab(Tab.DIAGNOSTICS) },
                    ),
                ),
            )
            AiGroup.TOOLS -> column.addView(
                segmentedRow(
                    listOf(
                        Triple(t("Tools", "도구"), tab == Tab.TOOLS) { showTab(Tab.TOOLS) },
                        Triple(t("Build", "빌드"), tab == Tab.BUILD) { showTab(Tab.BUILD) },
                        Triple(t("Resources", "자원"), tab == Tab.RESOURCES) { showTab(Tab.RESOURCES) },
                        Triple(t("Terminal", "터미널"), tab == Tab.TERMINAL) { showTab(Tab.TERMINAL) },
                    ),
                ),
            )
            else -> {}
        }
        val frame = FrameLayout(this)
        frame.addView(
            when (tab) {
                Tab.PLAN -> buildPlanTab()
                Tab.AGENTS -> buildAgentsTab()
                Tab.RESULTS -> buildResultsTab()
                Tab.LOGS -> buildLogsTab()
                Tab.CHANGES -> buildChangesTab()
                Tab.DIAGNOSTICS -> buildDiagnosticsTab()
                Tab.TOOLS -> buildToolsTab()
                Tab.BUILD -> buildBuildTab()
                Tab.RESOURCES -> buildResourcesTab()
                Tab.TERMINAL -> buildTerminalTab()
                else -> chatRoot()
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        column.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    /** Settings destination: (설정/세션/모델) segmented bar + content. */
    private fun settingsScreen(tab: Tab): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        column.addView(
            segmentedRow(
                listOf(
                    Triple(t("Settings", "설정"), tab == Tab.SETTINGS) { showTab(Tab.SETTINGS) },
                    Triple(t("Sessions", "세션"), tab == Tab.SESSIONS) { showTab(Tab.SESSIONS) },
                    Triple(t("Models", "모델"), tab == Tab.MODELS) { showTab(Tab.MODELS) },
                ),
            ),
        )
        val frame = FrameLayout(this)
        frame.addView(
            when (tab) {
                Tab.SESSIONS -> buildSessionsTab()
                Tab.MODELS -> buildModelsTab()
                else -> buildSettingsTab()
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        column.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    private fun showTab(tab: Tab) {
        activeTab = tab
        highlightRail()
        content.removeAllViews()
        content.addView(
            when (screenOf(tab)) {
                Screen.AI -> aiScreen(tab)
                Screen.SETTINGS -> settingsScreen(tab)
                else -> when (tab) {
                    Tab.FILES -> buildFilesTab()
                    Tab.GIT -> buildGitTab()
                    else -> buildSearchTab()
                }
            },
        )
        updateStatusBar()
    }

    // ---------------------------------------------------------------- Chat tab

    private fun chatRoot(): View {
        if (!::chatWrapper.isInitialized) buildChatViews()
        (chatWrapper.parent as? ViewGroup)?.removeView(chatWrapper)
        return chatWrapper
    }

    private fun buildChatViews() {
        val p = StudioUi.palette
        chatWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        chatMessages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        chatScroll = ScrollView(this).apply { addView(chatMessages) }
        chatWrapper.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        thinkingView = TextView(this).apply {
            setTextColor(p.textSoft)
            textSize = StudioUi.TypeSmall
            setPadding(dp(12), dp(4), dp(12), dp(4))
            visibility = View.GONE
        }
        chatWrapper.addView(thinkingView)

        // Transient-failure retry line: shows attempt number, reason and a live
        // countdown. Deliberately not an error bubble — the retry is automatic.
        retryView = TextView(this).apply {
            setTextColor(p.info)
            textSize = StudioUi.TypeSmall
            setPadding(dp(12), dp(2), dp(12), dp(4))
            visibility = View.GONE
        }
        chatWrapper.addView(retryView)

        // Context badge: compact line showing what the next request carries
        // (open editor file, @mention count, rough token size).
        contextBadge = TextView(this).apply {
            setTextColor(p.muted)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), dp(2), dp(14), dp(2))
            visibility = View.GONE
        }
        chatWrapper.addView(contextBadge)
        chatWrapper.addView(buildQuickActions())
        chatWrapper.addView(buildThinkRow())

        chatWrapper.addView(buildInputRow())
    }

    /**
     * "Think" switch above the composer.
     *
     * On, the engine asks the model to state its approach before acting, and
     * any reasoning a provider streams is rendered as a full, expandable card
     * in the transcript instead of the one-line ticker. It is a real request
     * change, so the chip shows the engine's persisted state - not a local
     * toggle that would forget itself when the workspace closes.
     */
    private fun buildThinkRow(): View {
        val p = StudioUi.palette
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), dp(2))
        }
        thinkChip = AiUiKit.actionChip(this, thinkChipLabel()) { _ -> toggleThinkMode() }
        row.addView(thinkChip)
        row.addView(TextView(this).apply {
            text = t(
                "States the approach before acting",
                "행동 전에 접근 방식을 먼저 설명합니다",
            )
            setTextColor(p.muted)
            textSize = 10f
            setPadding(dp(8), 0, 0, 0)
        })
        paintThinkChip()
        return row
    }

    private fun thinkChipLabel(): String =
        if (engine.thinkMode) t("🧠 Think: on", "🧠 Think: 켜짐") else t("🧠 Think: off", "🧠 Think: 꺼짐")

    private fun toggleThinkMode() {
        engine.setThinkMode(!engine.thinkMode)
        paintThinkChip()
        Toast.makeText(
            this,
            if (engine.thinkMode) {
                t(
                    "Think mode on: the model explains its approach and any reasoning stream is shown in full.",
                    "Think 모드 켜짐: 모델이 접근 방식을 먼저 설명하고, 추론 스트림은 전체 카드로 표시됩니다.",
                )
            } else {
                t("Think mode off.", "Think 모드 꺼짐.")
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun paintThinkChip() {
        val chip = thinkChip ?: return
        val p = StudioUi.palette
        val on = engine.thinkMode
        chip.text = thinkChipLabel()
        chip.setTextColor(if (on) p.accent else p.textSoft)
        chip.background = AiUiKit.rippleContent(
            this,
            if (on) AiUiKit.withAlpha(p.accent, 0.16f) else p.surface2,
            dp(8),
        )
    }

    /**
     * One-tap prompts under the assistant header (프로젝트 생성 / 문제 해결 /
     * 리팩토링 / 파일 생성 / 분석 / 테스트), matching the IDE design.
     */
    private fun buildQuickActions(): View {
        val actions = listOf(
            t("Create project", "프로젝트 생성하기") to t(
                "Create a new project here. Ask me for the details you need first.",
                "여기에 새 프로젝트를 만들어줘. 필요한 정보를 먼저 물어봐줘.",
            ),
            t("Fix problems", "문제 해결하기") to t(
                "Check the current diagnostics and fix the errors and warnings.",
                "현재 진단 오류와 경고를 확인하고 수정해줘.",
            ),
            t("Refactor", "리팩토링하기") to t(
                "Refactor this project. Propose a plan first and wait for my approval.",
                "이 프로젝트를 리팩토링해줘. 먼저 계획을 제시하고 승인을 받은 뒤 진행해줘.",
            ),
            t("New file", "파일 생성하기") to t(
                "Create a new file. Ask me what to create first.",
                "새 파일을 만들어줘. 무엇을 만들지 먼저 물어봐줘.",
            ),
            t("Analyze project", "프로젝트 분석하기") to t(
                "Analyze the project structure, dependencies and build setup, then summarise it.",
                "프로젝트 구조, 의존성, 빌드 설정을 분석하고 요약해줘.",
            ),
            t("Write tests", "테스트 작성하기") to t(
                "Write unit tests for the core code of this project.",
                "이 프로젝트의 핵심 코드에 대한 단위 테스트를 작성해줘.",
            ),
        )
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        var row: LinearLayout? = null
        for ((index, action) in actions.withIndex()) {
            if (index % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                column.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            row!!.addView(
                AiUiKit.actionChip(this, action.first) { _ -> submit(action.second) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, dp(3), dp(6), dp(3)) },
            )
        }
        return column
    }

    private lateinit var chatWrapper: LinearLayout

    private fun buildInputRow(): View {
        val p = StudioUi.palette
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AiUiKit.rounded(p.surface, 0)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        input = EditText(this).apply {
            hint = t("Ask the agent…", "에이전트에게 요청…")
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = StudioUi.TypeBody
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = AiUiKit.rounded(p.surface2, dp(14), strokeColor = p.border, strokePx = dp(1))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
            }
            // Multi-line composer: Enter sends, Shift+Enter (hardware
            // keyboards) inserts a newline. Without this the IME's Enter key
            // only ever produced newlines and the send action never fired.
            setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    !event.isShiftPressed
                ) {
                    send(); true
                } else false
            }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val text = s?.toString().orEmpty()
                    updateSlashSuggestions(text)
                    updateMentionSuggestions(text, input.selectionStart)
                    updateContextBadge(text)
                }
            })
        }
        sendButton = Button(this).apply {
            text = t("Send", "전송")
            isAllCaps = false
            setTextColor(p.onAccent)
            background = AiUiKit.rippleContent(this@AiWorkspaceActivity, p.accent, dp(14))
            setOnClickListener {
                if (engineState == OcEngine.AgentState.GENERATING || engineState == OcEngine.AgentState.THINKING) engine.stop()
                else send()
            }
        }
        row.addView(
            AiUiKit.actionChip(this, "📎") { _ -> pickAttachment() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(4) },
        )
        row.addView(
            AiUiKit.actionChip(this, "🎤") { _ -> startVoiceInput() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(4) },
        )
        row.addView(input)
        row.addView(sendButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        return row
    }

    private fun send() {
        submit(input.text?.toString()?.trim().orEmpty())
    }

    /**
     * Sends [text] (plus any pending attachment) to the engine after the local
     * slash-command pass. Shared by the composer, quick-action chips and file
     * actions so everything uses one entry point.
     */
    private fun submit(text: String) {
        val image = pendingImageBase64
        if (text.isBlank() && image == null) return
        // A leading space used to hide the slash from this check, so
        // " /model" was sent to the model as prose. The command parser trims;
        // the check must look at the same text it will parse.
        val slashText = text.trimStart()
        if (slashText.startsWith("/") && image == null && handleSlashInput(slashText)) return
        input.setText("")
        pendingImageBase64 = null
        addUserBubble(if (image != null) "🖼 " + text.ifBlank { t("(image attached)", "(이미지 첨부)") } else text)
        streamingView = null
        streamingCard = null
        engine.sendMessage(
            text.ifBlank { t("What is in this image?", "이 이미지에는 무엇이 있나요?") },
            imageBase64 = image,
        )
    }

    // -------------------------------------------------------- attach / voice

    private fun pickAttachment() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "text/*", "application/json"))
        }
        runCatching { startActivityForResult(intent, REQUEST_ATTACHMENT) }
            .onFailure {
                Toast.makeText(this, t("No file picker available", "파일 선택기를 사용할 수 없습니다"), Toast.LENGTH_SHORT).show()
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ATTACHMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching {
            val mime = contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("image/")) {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    Toast.makeText(this, t("Could not read file", "파일을 읽을 수 없습니다"), Toast.LENGTH_SHORT).show()
                    return@runCatching
                }
                pendingImageBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                Toast.makeText(this, t("Image attached", "이미지 첨부됨"), Toast.LENGTH_SHORT).show()
            } else {
                val text = contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                if (text == null) {
                    Toast.makeText(this, t("Could not read file", "파일을 읽을 수 없습니다"), Toast.LENGTH_SHORT).show()
                    return@runCatching
                }
                val excerpt = text.take(4000)
                val label = uri.lastPathSegment ?: "file"
                input.setText(
                    input.text?.toString().orEmpty() + "\n\n" +
                        t("[Attached $label]", "[첨부 $label]") + "\n```\n$excerpt\n```",
                )
                Toast.makeText(this, t("File attached", "파일 첨부됨"), Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(this, t("Attach failed: ${it.message}", "첨부 실패: ${it.message}"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startVoiceInput() {
        val available = runCatching { android.speech.SpeechRecognizer.isRecognitionAvailable(this) }.getOrDefault(false)
        if (!available) {
            Toast.makeText(this, t("Speech recognition is not available", "음성 인식을 사용할 수 없습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        listen()
    }

    private fun listen() {
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().language)
        }
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@AiWorkspaceActivity, t("Listening…", "듣는 중…"), Toast.LENGTH_SHORT).show()
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                Toast.makeText(this@AiWorkspaceActivity, t("Voice input failed", "음성 입력 실패"), Toast.LENGTH_SHORT).show()
                recognizer.destroy()
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (text.isNotBlank()) {
                    input.setText(text)
                    input.setSelection(text.length)
                }
                recognizer.destroy()
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MIC && grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            listen()
        }
    }

    /**
     * Intercepts slash input before sending. Returns true when the text was
     * consumed by a local command or an inline notice (unknown command);
     * false when it should still flow to the engine (template commands).
     */
    private fun handleSlashInput(text: String): Boolean {
        return when (val invocation = slashRegistry.parse(text)) {
            is SlashInvocation.Known -> {
                input.setText("")
                runSlashCommand(invocation.command, invocation.argument)
                true
            }
            is SlashInvocation.Unknown -> {
                // Template commands (init, review, skills...) still go through
                // the engine; anything else becomes an inline notice.
                val knownTemplate = runCatching {
                    engine.listCommands().any { it.name.equals(invocation.name, ignoreCase = true) }
                }.getOrDefault(false)
                if (knownTemplate) false else {
                    input.setText("")
                    addSystemBubble(
                        t(
                            "Unknown command: /${invocation.name} — type / to see commands.",
                            "Unknown command: /${invocation.name} — type / to see commands.",
                        ),
                    )
                    true
                }
            }
            SlashInvocation.NotACommand -> false
        }
    }

    private fun runSlashCommand(command: SlashCommand, argument: String) {
        when (command.action) {
            SlashCommandAction.HELP -> addSystemBubble(SlashCommandRegistry.helpText())
            SlashCommandAction.CLEAR -> confirmClearConversation()
            SlashCommandAction.NEW_SESSION -> newChat()
            SlashCommandAction.SHOW_SESSIONS -> showTab(Tab.SESSIONS)
            SlashCommandAction.SHOW_MODELS -> showModels()
            SlashCommandAction.SHOW_TOOLS -> showToolUsage(argument)
            SlashCommandAction.TOGGLE_PLAN -> togglePlanMode()
            SlashCommandAction.UNDO -> confirmUndo()
            SlashCommandAction.REDO -> performRedo()
            SlashCommandAction.SHOW_CHANGES -> showTab(Tab.CHANGES)
            SlashCommandAction.STATUS -> addSystemBubble(
                StatusLine.format(
                    project = projectDir?.absolutePath,
                    openFile = currentFile?.absolutePath,
                    model = engine.currentModelLabel(),
                    sessionId = engine.currentSessionId(),
                    agent = engine.agentName,
                ),
            )
            SlashCommandAction.AI_EXPLAIN -> aiExplain(argument)
            SlashCommandAction.AI_REFACTOR -> aiRefactor(argument)
            SlashCommandAction.AI_GENERATE -> aiGenerate(argument)
            SlashCommandAction.AI_TEST -> aiTest(argument)
            SlashCommandAction.AI_DOC -> aiDoc(argument)
        }
    }

    private fun aiExplain(code: String) {
        val prompt = if (code.isNotBlank()) code else "current file"
        engine.sendMessage("Explain this code in detail:\n\n$prompt")
    }

    private fun aiRefactor(code: String) {
        val prompt = if (code.isNotBlank()) code else "current file"
        engine.sendMessage("Refactor this code to improve readability and performance:\n\n$prompt")
    }

    private fun aiGenerate(description: String) {
        val prompt = if (description.isNotBlank()) description else "Generate a Kotlin class"
        engine.sendMessage("Generate code for:\n\n$prompt")
    }

    private fun aiTest(description: String) {
        val prompt = if (description.isNotBlank()) description else "current file"
        engine.sendMessage("Generate unit tests for:\n\n$prompt")
    }

    private fun aiDoc(description: String) {
        val prompt = if (description.isNotBlank()) description else "current file"
        engine.sendMessage("Generate documentation for:\n\n$prompt")
    }

    /** /models — open the Models tab and load the live model list from the API. */
    private fun showModels() {
        showTab(Tab.MODELS)
        val providerId = pickerProvider ?: runCatching { engine.listProviders() }.getOrDefault(emptyList())
            .firstOrNull { (id, _) -> id != "local" && id.isNotBlank() }?.first ?: "openai"
        pickerProvider = providerId
        val endpoint = endpointFor(providerId) ?: ProviderEndpoint(providerId, providerId, "", null, null, AuthStyle.NONE)
        runOnUiThread {
            // surface the loaded list through the same picker state used by the
            // Models tab so the user sees the freshly fetched entries there too
            refreshKnownModels()
            pickerQuery = ""
            pickerSort = ModelSort.NAME
            pickerTab = ModelTab.ALL
            pickerSearchField?.setText("")
            renderPickerTabs()
            renderPickerFilters()
            renderModelList()
        }
        scope.launch {
            val models = withContext(Dispatchers.IO) {
                runCatching { ApiModelFetcher(AndroidHttpTransport()).listModels(endpoint.baseUrl, endpoint.apiKey, endpoint.authStyle) }
                    .getOrDefault(emptyList())
            }
            val finalModels = models
            runOnUiThread {
                if (finalModels.isEmpty()) {
                    themedDialog()
                        .setTitle(t("Models", "모델"))
                        .setMessage(t("No models returned (check the key and endpoint).", "모델을 가져오지 못했습니다 (키/엔드포인트 확인)."))
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    themedDialog()
                        .setTitle(t("Available models (${finalModels.size})", "사용 가능한 모델 (${finalModels.size})"))
                        .setMessage(finalModels.joinToString("\n"))
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun togglePlanMode() {
        val enabling = engine.agentName != "plan"
        engine.setAgentMode(if (enabling) "plan" else "build")
        addSystemBubble(
            if (enabling) {
                t("Plan mode ON — the agent can only read and plan.", "계획 모드 켜짐 — 에이전트는 읽기와 계획만 할 수 있습니다.")
            } else {
                t("Plan mode OFF — back to build.", "계획 모드 꺼짐 — 빌드 모드로 돌아갑니다.")
            },
        )
        if (enabling) showTab(Tab.PLAN)
    }

    private fun confirmClearConversation() {
        themedDialog()
            .setTitle(t("Clear conversation?", "대화를 지울까요?"))
            .setMessage(
                t(
                    "This clears the visible conversation and starts a new session. The previous session stays in Sessions.",
                    "화면의 대화를 지우고 새 세션을 시작합니다. 이전 세션은 세션 목록에 남아 있습니다.",
                ),
            )
            .setPositiveButton(t("Clear", "지우기")) { _, _ ->
                newChat()
                addSystemBubble(t("Conversation cleared.", "대화가 지워졌습니다."))
            }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    /** /undo: confirm dialog listing what will be restored (path + line delta). */
    private fun confirmUndo() {
        scope.launch {
            val deltas = withContext(Dispatchers.IO) { engine.pendingUndoDeltas() }
            if (deltas.isEmpty()) {
                addSystemBubble(t("Nothing to undo.", "되돌릴 변경이 없습니다."))
                return@launch
            }
            val listing = deltas.joinToString("\n") { d ->
                val sign = if (d.lineDelta >= 0) "+" else ""
                val created = if (d.existedBefore) "" else t(" (created)", " (새 파일)")
                "${relativePath(d.path)}  ($sign${d.lineDelta} lines)$created"
            }
            themedDialog()
                .setTitle(t("Undo the agent's last change?", "에이전트의 마지막 변경을 되돌릴까요?"))
                .setMessage(listing)
                .setPositiveButton(t("Undo", "되돌리기")) { _, _ -> performUndo() }
                .setNegativeButton(t("Cancel", "취소"), null)
                .show()
        }
    }

    private fun performUndo() {
        scope.launch {
            when (val outcome = withContext(Dispatchers.IO) { engine.undoLastTurn() }) {
                is CheckpointOutcome.Undone ->
                    addSystemBubble(t("Undid ${outcome.files.size} file(s).", "${outcome.files.size}개 파일을 되돌렸습니다."))
                CheckpointOutcome.NothingToUndo ->
                    addSystemBubble(t("Nothing to undo.", "되돌릴 변경이 없습니다."))
                CheckpointOutcome.NothingToRedo -> {}
                is CheckpointOutcome.Redone -> {}
                is CheckpointOutcome.Failed ->
                    addSystemBubble("⚠ " + t("Undo failed: ", "되돌리기 실패: ") + outcome.message)
            }
        }
    }

    private fun performRedo() {
        scope.launch {
            when (val outcome = withContext(Dispatchers.IO) { engine.redoLastTurn() }) {
                is CheckpointOutcome.Redone ->
                    addSystemBubble(t("Re-applied ${outcome.files.size} file(s).", "${outcome.files.size}개 파일을 다시 적용했습니다."))
                CheckpointOutcome.NothingToRedo ->
                    addSystemBubble(t("Nothing to redo.", "다시 적용할 변경이 없습니다."))
                CheckpointOutcome.NothingToUndo -> {}
                is CheckpointOutcome.Undone -> {}
                is CheckpointOutcome.Failed ->
                    addSystemBubble("⚠ " + t("Redo failed: ", "다시 적기 실패: ") + outcome.message)
            }
        }
    }

    private fun updateContextBadge(text: String) {
        val label = engine.currentFileContextPath()
        val badge = PromptContextBadge.format(label, text)
        contextBadge.text = badge
        contextBadge.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------- "/" command suggestions

    /**
     * Filterable "/" popup: local commands first, then the engine's template
     * commands (init, review, skills...). Typing filters; selecting a local
     * command runs it, selecting a template command fills the input.
     */
    private fun updateSlashSuggestions(text: String) {
        val body = text.trimStart()
        if (!body.startsWith("/") || body.contains(' ')) {
            slashPopup?.dismiss()
            return
        }
        val query = body.substring(1)
        val local = slashRegistry.filter(query)
        val templates = runCatching { engine.listCommands() }.getOrDefault(emptyList()).filter { cmd ->
            cmd.name.startsWith(query, ignoreCase = true) ||
                (cmd.description ?: "").contains(query, ignoreCase = true)
        }
        if (local.isEmpty() && templates.isEmpty()) {
            slashPopup?.dismiss()
            return
        }
        val labels = ArrayList<String>(local.size + templates.size)
        local.forEach { labels.add("/${it.name} — ${it.description}") }
        templates.forEach { labels.add("/${it.name} — ${it.description ?: "template command"}") }

        val popup = slashPopup ?: ListPopupWindow(this).apply {
            anchorView = input
            isModal = false
            setOnItemClickListener { _, _, position, _ ->
                slashPopup?.dismiss()
                if (position < local.size) {
                    val command = local[position]
                    input.setText("")
                    runSlashCommand(command, "")
                } else {
                    val cmd = templates.getOrNull(position - local.size) ?: return@setOnItemClickListener
                    input.setText("/${cmd.name} ")
                    input.setSelection(input.text.length)
                }
            }
            slashPopup = this
        }
        popup.setContentWidth(input.width.coerceAtLeast(dp(220)))
        popup.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        runCatching { popup.show() }
    }

    // -------------------------------------------------- @file mention picker

    /**
     * Shows the project-file picker while the caret is inside an "@path"
     * token. Selection replaces the token with "@relative/path ".
     */
    private fun updateMentionSuggestions(text: String, caret: Int) {
        val token = mentionTokenAt(text, caret)
        if (token == null) {
            mentionPopup?.dismiss()
            return
        }
        ensureMentionFiles {
            val query = token.removePrefix("@")
            val matches = ProjectFiles.filter(mentionFiles, query)
            if (matches.isEmpty()) {
                mentionPopup?.dismiss()
                return@ensureMentionFiles
            }
            activeMentionToken = token
            val labels = matches.map { "@$it" }
            val popup = mentionPopup ?: ListPopupWindow(this).apply {
                anchorView = input
                isModal = false
                setOnItemClickListener { _, _, position, _ ->
                    mentionPopup?.dismiss()
                    val relative = matches.getOrNull(position) ?: return@setOnItemClickListener
                    val current = activeMentionToken ?: return@setOnItemClickListener
                    val editable = input.text ?: return@setOnItemClickListener
                    val start = (editable.length - current.length).coerceAtLeast(0)
                    editable.replace(start, editable.length, "@$relative ")
                    input.setSelection(editable.length)
                    activeMentionToken = null
                }
                mentionPopup = this
            }
            popup.setContentWidth(input.width.coerceAtLeast(dp(220)))
            popup.setAdapter(android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
            runCatching { popup.show() }
        }
    }

    /** The "@..." token ending at [caret], or null when none is being typed. */
    private fun mentionTokenAt(text: String, caret: Int): String? {
        if (text.isEmpty()) return null
        val end = if (caret in 1..text.length) caret else text.length
        val upToCaret = text.substring(0, end)
        val start = upToCaret.lastIndexOf('@')
        if (start < 0) return null
        if (start > 0) {
            val prev = upToCaret[start - 1]
            // emails and mid-word @ are not mentions
            if (prev.isLetterOrDigit() || prev == '_' || prev == '.' || prev == '/') return null
        }
        val token = upToCaret.substring(start)
        if (token.contains(' ') || token.contains('\n')) return null
        return if (token.length >= 1) token else null
    }

    private fun ensureMentionFiles(onReady: () -> Unit) {
        val root = engine.workspaceRoot()
        if (mentionFilesRoot == root && mentionFiles.isNotEmpty()) {
            onReady()
            return
        }
        if (mentionFilesLoading) return
        mentionFilesLoading = true
        scope.launch {
            val files = withContext(Dispatchers.IO) { ProjectFiles.list(root) }
            mentionFiles = files
            mentionFilesRoot = root
            mentionFilesLoading = false
            onReady()
        }
    }

    private fun addUserBubble(text: String) {
        val p = StudioUi.palette
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AiUiKit.rounded(p.accent, dp(12))
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }
        card.addView(TextView(this).apply {
            this.text = text
            setTextColor(p.onAccent)
            textSize = StudioUi.TypeBody
            setLineSpacing(dp(2).toFloat(), 1.05f)
            setTextIsSelectable(true)
        })
        // Speech bubble: WRAP_CONTENT card inside a MATCH_PARENT horizontal row
        // aligned to the end, so it hugs the right edge and is capped at ~85%
        // of the row. (A width-0 weighted child of the VERTICAL chatMessages
        // list measures 0 px wide - weight distributes height there.)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(
            card,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(8), dp(6), dp(8), dp(6)) },
        )
        chatMessages.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        capUserBubbleWidthOnceLaidOut(row, card)
        scrollChatToBottom()
    }

    private fun assistantCard(): Pair<LinearLayout, TextView> {
        val p = StudioUi.palette
        val content = TextView(this).apply {
            setTextColor(p.text)
            textSize = StudioUi.TypeBody
            setLineSpacing(dp(3).toFloat(), 1.08f)
            setTextIsSelectable(true)
        }
        val card = AiUiKit.card(this, radiusDp = 12, color = p.surface, elevated = true).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(8), dp(6), dp(8), dp(6)) }
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(AiUiKit.circle(this, "AI", AiUiKit.withAlpha(p.accent, 0.22f), p.accent, 22))
        header.addView(TextView(this).apply {
            text = t("Agent", "에이전트")
            setTextColor(p.textSoft)
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setPadding(dp(8), 0, 0, 0)
        })
        card.addView(header)
        card.addView(content)
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        footer.addView(AiUiKit.actionChip(this, t("Copy", "복사")) { _ ->
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("message", content.text))
            Toast.makeText(this@AiWorkspaceActivity, t("Copied", "복사됨"), Toast.LENGTH_SHORT).show()
        })
        footer.addView(AiUiKit.actionChip(this, t("Regenerate", "다시 생성")) { _ ->
            engine.sendMessage(t("Continue and improve the previous answer.", "이전 답변을 이어서 더 개선해줘."))
        })
        card.addView(footer)
        chatMessages.addView(card)
        return card to content
    }

    // ------------------------------------------------------------- tool cards

    /**
     * A compact tool row. The whole card is the target: one tap opens the full
     * output, matching the way tool blocks work in opencode.
     */
    private fun addToolCard(
        name: String,
        args: JSONObject?,
        result: String?,
        isError: Boolean,
        running: Boolean,
        scrollToBottom: Boolean = true,
    ): LiveToolCard {
        val p = StudioUi.palette
        val card = AiUiKit.card(this, radiusDp = 10, color = p.surface, elevated = false).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(20), dp(2), dp(20), dp(2)) }
            background = AiUiKit.rippleContent(this@AiWorkspaceActivity, p.surface, dp(10), p.border)
            isClickable = true
            isFocusable = true
        }
        val header = TextView(this).apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        val preview = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(p.textSoft)
            setPadding(0, dp(3), 0, 0)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        val holder = LiveToolCard(
            card = card,
            header = header,
            preview = preview,
            name = name,
            args = args,
            result = result,
            isError = isError,
            finished = !running,
        )
        header.text = toolCardHeader(holder, running)
        header.setTextColor(if (running) p.info else if (isError) p.error else p.success)
        if (result.isNullOrBlank()) {
            preview.visibility = View.GONE
        } else {
            preview.visibility = View.VISIBLE
            preview.text = toolResultPreview(result)
        }
        card.addView(header)
        card.addView(preview)
        card.setOnClickListener { showToolResultDialog(holder) }
        chatMessages.addView(card)
        if (scrollToBottom) scrollChatToBottom()
        return holder
    }

    private fun startToolCard(name: String, args: JSONObject): LiveToolCard {
        val holder = addToolCard(name, args, null, isError = false, running = true)
        liveToolCards.add(holder)
        liveCommandCards[name] = holder
        return holder
    }

    private fun finishToolCard(name: String, args: JSONObject?, result: String, isError: Boolean) {
        // The engine passes the same args object to start/end. Identity is
        // enough to pair parallel calls and avoids stringifying large writes.
        val exact = if (args == null) -1 else liveToolCards.indexOfLast {
            !it.finished && it.name == name && it.args === args
        }
        val byName = liveToolCards.indexOfLast { !it.finished && it.name == name }
        val holder = when {
            exact >= 0 -> liveToolCards.removeAt(exact)
            byName >= 0 -> liveToolCards.removeAt(byName)
            else -> addToolCard(name, args, result, isError, running = false, scrollToBottom = false)
        }
        holder.result = result
        holder.isError = isError
        holder.finished = true
        holder.header.text = toolCardHeader(holder, running = false)
        holder.header.setTextColor(if (isError) StudioUi.palette.error else StudioUi.palette.success)
        if (result.isBlank()) {
            holder.preview.visibility = View.GONE
        } else {
            holder.preview.visibility = View.VISIBLE
            holder.preview.text = toolResultPreview(result)
        }
        scrollChatToBottom()
        liveCommandCards.remove(name)
    }

    private fun addToolCardFromPart(part: JSONObject, scrollToBottom: Boolean) {
        val name = part.optString("tool")
        if (name.isBlank()) return
        val state = part.optJSONObject("state")
        val args = state?.optJSONObject("input")
        val output = state?.optString("output", "").orEmpty()
            .ifEmpty { state?.optString("error", "").orEmpty() }
        addToolCard(
            name = name,
            args = args,
            result = output,
            isError = state?.optString("status") == "error",
            running = false,
            scrollToBottom = scrollToBottom,
        )
    }

    private fun toolCardHeader(holder: LiveToolCard, running: Boolean): String {
        val icon = when {
            running -> "⚙"
            holder.isError -> "✗"
            else -> "✓"
        }
        val summary = toolArgSummary(holder.args)
        val lines = holder.result
            ?.takeIf { it.isNotBlank() }
            ?.let { it.count { ch -> ch == '\n' } + 1 }
        return buildString {
            append(icon).append(' ').append(holder.name)
            if (summary.isNotBlank()) append(" · ").append(summary)
            if (!running && lines != null) append("  · ").append(t("$lines lines", "${lines}줄"))
            append("  ›")
        }
    }

    private fun toolArgSummary(args: JSONObject?): String {
        if (args == null) return ""
        for (key in listOf("filePath", "pattern", "query", "command", "path", "url", "description", "subagent_type")) {
            val value = args.optString(key, "")
            if (value.isNotBlank()) {
                val short = if (key == "filePath" || key == "path") value.substringAfterLast('/') else value
                return short.replace('\n', ' ').trim().take(72)
            }
        }
        return ""
    }

    private fun toolArgsBlock(args: JSONObject?): String {
        if (args == null) return ""
        val keys = listOf(
            "filePath", "path", "pattern", "query", "command", "url",
            "description", "subagent_type", "offset", "limit",
        )
        return keys.mapNotNull { key ->
            args.optString(key, "").takeIf { it.isNotBlank() }?.let { "$key: $it" }
        }.joinToString("\n")
    }

    private fun toolResultPreview(result: String): String =
        result.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(180)
            ?: t("(no output)", "(출력 없음)")

    private fun showToolResultDialog(holder: LiveToolCard) {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        val argsText = toolArgsBlock(holder.args)
        if (argsText.isNotBlank()) {
            column.addView(TextView(this).apply {
                text = argsText
                setTextColor(p.textSoft)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, 0, 0, dp(8))
            })
        }
        val args = holder.args
        val path = args?.optString("filePath", "")?.takeIf { it.isNotBlank() }
            ?: args?.optString("path", "").orEmpty()
        if (path.isNotBlank()) {
            val resolved = if (File(path).isAbsolute) File(path) else File(projectDir ?: File("."), path)
            if (resolved.isFile) {
                val open = AiUiKit.actionChip(this, t("Open in editor", "편집기에서 열기")) {
                    openInEditor(resolved.absolutePath)
                }
                column.addView(open, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { setMargins(0, 0, 0, dp(8)) })
            }
        }
        val fullText = holder.result?.takeIf { it.isNotBlank() } ?: t("(no output)", "(출력 없음)")
        val fullLines = fullText.lineSequence().filter { it.isNotBlank() }.toList()
        var expanded = false
        val visibleText =
            if (fullLines.size > SHOW_MORE_LINES && !expanded) fullLines.take(SHOW_MORE_LINES).joinToString("\n") else fullText
        val body = TextView(this).apply {
            text = visibleText
            setTextColor(if (holder.isError) p.error else p.text)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(AiUiKit.withAlpha(p.text, 0.04f))
        }
        if (fullLines.size > SHOW_MORE_LINES) {
            column.addView(TextView(this).apply {
                text = if (expanded) t("Show less", "접기") else t("Show more", "더 보기")
                setTextColor(p.accent)
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
                setOnClickListener {
                    expanded = !expanded
                    body.text = if (fullLines.size > SHOW_MORE_LINES && !expanded) {
                        fullLines.take(SHOW_MORE_LINES).joinToString("\n")
                    } else {
                        fullText
                    }
                }
            })
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val height = (resources.displayMetrics.heightPixels * 0.62f).toInt().coerceAtLeast(dp(240))
        column.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height),
        )
        val dialog = themedDialog()
            .setTitle(if (holder.isError) t("${holder.name} · error", "${holder.name} · 오류") else holder.name)
            .setView(column)
            .setPositiveButton(t("Close", "닫기"), null)
            .setNeutralButton(t("Copy", "복사")) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(holder.name, holder.result.orEmpty()))
                Toast.makeText(this@AiWorkspaceActivity, t("Copied", "복사됨"), Toast.LENGTH_SHORT).show()
            }
            .create()
        dialog.show()
    }

    // ------------------------------------------------------ reasoning (Think)

    private fun createReasoningView(): ReasoningView {
        val p = StudioUi.palette
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AiUiKit.rounded(AiUiKit.withAlpha(p.accent, 0.08f), dp(10))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
        }
        val header = TextView(this).apply {
            setTextColor(p.accent)
            textSize = StudioUi.TypeSmall
            setTypeface(null, Typeface.BOLD)
        }
        val body = TextView(this).apply {
            setTextColor(p.textSoft)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
            visibility = View.GONE
        }
        val view = ReasoningView(card, header, body)
        header.setOnClickListener {
            view.expanded = !view.expanded
            paintReasoning(view)
        }
        card.addView(header)
        card.addView(body)
        chatMessages.addView(card)
        scrollChatToBottom()
        return view
    }

    /**
     * Renders the reasoning a provider streams as it arrives.
     *
     * The card only exists when text actually came: a model without a
     * reasoning channel (the bundled local model is one) is never shown an
     * empty "thinking" box pretending otherwise. The text is capped so a
     * runaway stream cannot grow the view without bound.
     */
    private fun showReasoning(text: String) {
        if (text.isBlank()) return
        val view = reasoningView ?: createReasoningView().also { reasoningView = it }
        val shown = if (text.length > REASONING_MAX_CHARS) {
            text.take(REASONING_MAX_CHARS) + "\n… " +
                t("reasoning truncated", "추론이 잘렸습니다")
        } else {
            text
        }
        view.chars = shown.length
        view.live = true
        view.body.text = shown
        view.body.visibility = View.VISIBLE
        view.expanded = true
        paintReasoning(view)
        scrollChatToBottom()
    }

    private fun finishReasoning() {
        val view = reasoningView ?: return
        view.live = false
        view.expanded = false
        paintReasoning(view)
        reasoningView = null
    }

    /** A reasoning part replayed from the session store, collapsed by default. */
    private fun addHistoryReasoning(text: String) {
        if (text.isBlank()) return
        val view = createReasoningView()
        view.live = false
        view.expanded = false
        view.chars = text.length
        view.body.text = text
        paintReasoning(view)
    }

    private fun paintReasoning(view: ReasoningView) {
        view.header.text = buildString {
            append("🧠 ")
            append(if (view.live) t("Thinking", "생각 중") else t("Thought", "사고"))
            append(" · ").append(view.chars)
            append(if (view.live) t(" chars", "자") else t(" chars · tap to expand/collapse", "자 · 탭하여 접기/펼치기"))
        }
        view.body.visibility = if (view.expanded) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------ diff (preview)

    /**
     * Approval dialog for write/edit that shows the change itself.
     *
     * The engine hands over the real file contents, so this is the same diff
     * the tool would write - not a reconstruction from the arguments. "Always
     * allow" keeps its old meaning (persist a permission rule for this tool).
     */
    private fun showDiffApprovalDialog(
        preview: OcEngine.DiffPreview,
        onApproval: (Boolean, Boolean) -> Unit,
    ) {
        val p = StudioUi.palette
        val patch = preview.patch
        val lines = if (patch != null) {
            patchLines(patch)
        } else {
            AiDiff.preview(preview.before, preview.after, maxLines = 400)
        }
        val summary = if (patch != null) {
            t("${lines.size} patch lines", "패치 ${lines.size}줄")
        } else {
            val stats = AiDiff.stats(preview.before, preview.after)
            "+${stats.added}  −${stats.removed}"
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        column.addView(TextView(this).apply {
            text = relativePath(preview.path)
            setTextColor(p.text)
            textSize = 13f
            typeface = Typeface.MONOSPACE
        })
        column.addView(TextView(this).apply {
            text = summary
            setTextColor(p.muted)
            textSize = 11f
            setPadding(0, dp(2), 0, dp(6))
        })
        val diff = TextView(this).apply {
            text = diffSpannable(lines, preview.path)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val hScroll = HorizontalScrollView(this).apply {
            setBackgroundColor(p.surface2)
            addView(diff)
        }
        val lineHeight = dp(17)
        val maxHeight = (resources.displayMetrics.heightPixels * 0.54f).toInt()
        val vScroll = ScrollView(this).apply {
            addView(hScroll)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (lines.size * lineHeight + dp(28)).coerceIn(dp(96), maxHeight),
            )
        }
        column.addView(vScroll)
        column.addView(TextView(this).apply {
            text = t(
                "This is the exact content the tool will write.",
                "도구가 실제로 기록할 내용입니다.",
            )
            setTextColor(p.muted)
            textSize = 10f
            setPadding(0, dp(6), 0, 0)
        })
        val dialog = themedDialog()
            .setTitle(t("Review change", "변경 검토") + " · " + preview.tool)
            .setView(column)
            .setPositiveButton(t("Apply", "적용"), null)
            .setNeutralButton(t("Always allow", "항상 허용"), null)
            .setNegativeButton(t("Cancel", "취소")) { _, _ -> onApproval(false, false) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                onApproval(true, false)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                onApproval(true, true)
            }
        }
        dialog.show()
    }

    /** Splits raw patch text into coloured lines; `***` headers stay neutral. */
    private fun patchLines(text: String): List<AiDiff.Line> = text.lineSequence().map { line ->
        when {
            line.startsWith("***") -> AiDiff.Line(' ', line)
            line.startsWith("+") -> AiDiff.Line('+', line.drop(1))
            line.startsWith("-") -> AiDiff.Line('-', line.drop(1))
            else -> AiDiff.Line(' ', line)
        }
    }.toList()

    private fun diffSpannable(lines: List<AiDiff.Line>, path: String? = null): CharSequence {
        val p = StudioUi.palette
        // Same language the editor uses. The diff marker/line remains the
        // fallback colour; tokens get their syntax colour on top.
        val highlighter = path?.takeIf { it.isNotBlank() }?.let {
            runCatching { LanguageHighlighter.forFile(it) }.getOrNull()
        }
        val out = SpannableStringBuilder()
        for (line in lines) {
            val start = out.length
            out.append(line.kind).append(line.text).append('\n')
            val color = when (line.kind) {
                '+' -> p.success
                '-' -> p.error
                else -> p.textSoft
            }
            out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (line.kind != ' ') {
                out.setSpan(
                    BackgroundColorSpan(AiUiKit.withAlpha(color, 0.10f)),
                    start,
                    out.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            highlighter?.let { h ->
                val codeStart = start + 1
                val codeEnd = codeStart + line.text.length
                for (span in h.highlight(line.text)) {
                    val spanStart = codeStart + span.start
                    val spanEnd = codeStart + span.end
                    if (spanStart < codeStart || spanEnd > codeEnd || spanEnd <= spanStart) continue
                    out.setSpan(
                        ForegroundColorSpan(span.color),
                        spanStart,
                        spanEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    if (span.bold) {
                        out.setSpan(
                            StyleSpan(Typeface.BOLD),
                            spanStart,
                            spanEnd,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
        }
        return out
    }

    /**
     * An inline diff card after a tool changed a file.
     *
     * It compares the latest pre-edit snapshot with what is on disk now, so
     * the statistics and the visible lines are measured, not announced by the
     * model. The full diff still opens in the reader on demand.
     */
    private fun addDiffCard(path: String) {
        if (!::chatMessages.isInitialized) return
        val backup = engine.latestBackupForCurrentSession(path) ?: return
        // Reading both files and diffing belongs off the UI thread; a large
        // generated file would otherwise stall the transcript it is joining.
        scope.launch(Dispatchers.IO) {
            val before = runCatching { backup.readText() }.getOrNull() ?: return@launch
            val file = File(path)
            val after = if (file.isFile) runCatching { file.readText() }.getOrNull() ?: "" else ""
            if (before == after) return@launch
            val stats = AiDiff.stats(before, after)
            val preview = AiDiff.preview(before, after, maxLines = 10, context = 1)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                addDiffCardView(path, stats, preview)
            }
        }
    }

    private fun addDiffCardView(path: String, stats: AiDiff.Stats, preview: List<AiDiff.Line>) {
        val p = StudioUi.palette
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AiUiKit.rounded(p.surface, dp(10))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = "📄 " + relativePath(path)
            setTextColor(p.text)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(TextView(this).apply {
            text = "+${stats.added} −${stats.removed}"
            setTextColor(p.muted)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        })
        card.addView(head)
        if (preview.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = diffSpannable(preview.take(9), path)
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, dp(2))
            })
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(AiUiKit.actionChip(this, t("View diff", "차이 보기")) { _ -> showDiff(path) })
        actions.addView(
            AiUiKit.actionChip(this, t("Open", "열기")) { _ -> openInEditor(path) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) },
        )
        card.addView(actions)
        chatMessages.addView(card)
        scrollChatToBottom()
    }

    private fun showToolHelp(name: String) {
        val detail = engine.toolDetail(name) ?: return
        themedDialog()
            .setTitle(name)
            .setMessage(detail)
            .setPositiveButton(t("Close", "닫기"), null)
            .show()
    }

    /**
     * `/tools` deliberately prints names only. Full documentation is fetched
     * for the one tool the user (or the model, through `tool_help`) asks for;
     * dumping every schema into the conversation is what the lazy tool
     * definitions were introduced to stop.
     */
    private fun showToolUsage(argument: String) {
        val names = runCatching { engine.toolNames() }.getOrDefault(emptyList()).sorted()
        if (names.isEmpty()) {
            addSystemBubble(t("No tools are registered.", "등록된 도구가 없습니다."))
            return
        }
        val requested = argument.split(',', ' ', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (requested.isEmpty()) {
            addSystemBubble(
                t(
                    "Tools (${names.size}) — names only. Type /tools <name> for its full usage.",
                    "도구 ${names.size}개 — 이름만 표시합니다. 사용법은 /tools <이름>으로 확인하세요.",
                ) + "\n\n" + names.joinToString("\n") { "• $it" },
            )
            return
        }
        val found = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (name in requested.distinct()) {
            val detail = runCatching { engine.toolDetail(name) }.getOrNull()
            if (detail == null) missing.add(name) else found.add(detail)
        }
        val text = buildString {
            append(found.joinToString("\n\n"))
            if (missing.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(
                    t(
                        "Unknown tool: ${missing.joinToString(", ")}",
                        "알 수 없는 도구: ${missing.joinToString(", ")}",
                    ),
                )
                append("\n")
                append(t("Available: ", "사용 가능: "))
                append(names.joinToString(", "))
            }
        }
        addSystemBubble(text)
    }

    private fun addSystemBubble(text: String) {
        val p = StudioUi.palette
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        row.addView(TextView(this).apply {
            this.text = text
            setTextColor(p.warning)
            textSize = StudioUi.TypeSmall
            background = AiUiKit.rounded(AiUiKit.withAlpha(p.warning, 0.12f), dp(999))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        })
        chatMessages.addView(row)
        scrollChatToBottom()
    }

    private fun renderSessionHistory() {
        chatMessages.removeAllViews()
        liveToolCards.clear()
        streamingView = null
        streamingCard = null
        reasoningView = null
        changedFiles.clear()
        restoreChangedFilesFromSession()
        val sessionId = engine.currentSessionId() ?: return
        val messages = engine.sessionMessages(sessionId)
        for (msg in messages) {
            val role = msg.optString("role", "assistant")
            val parts = OcMessage.parts(msg)
            val sb = StringBuilder()
            val toolParts = mutableListOf<JSONObject>()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text" -> sb.append(part.optString("text"))
                    "tool" -> toolParts.add(part)
                    "reasoning" -> addHistoryReasoning(part.optString("text"))
                }
            }
            if (sb.isNotBlank()) {
                if (role == "user") addUserBubble(sb.toString().trim()) else addAssistantBubble(sb.toString().trim())
            }
            // Tool calls are rendered as cards after the message text they
            // belong to. They stay collapsed until tapped, but the result is
            // already in the part, so opening one costs no engine round-trip.
            for (part in toolParts) addToolCardFromPart(part, scrollToBottom = false)
        }
        // Reopening the activity loses the ScrollView state; land the user on
        // the newest message instead of the top of the restored transcript.
        scrollChatToBottom()
    }

    /**
     * The in-memory [changedFiles] set dies with the activity, which made the
     * Changes tab empty after leaving and returning. Rebuild it from the
     * persisted write/edit/patch tool calls of the restored session so the
     * files the agent touched stay openable (diff / open in editor).
     */
    private fun restoreChangedFilesFromSession() {
        val sessionId = engine.currentSessionId() ?: return
        for (msg in engine.sessionMessages(sessionId)) {
            val parts = OcMessage.parts(msg)
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") != "tool") continue
                if (part.optString("tool") !in FILE_CHANGING_TOOLS) continue
                val input = part.optJSONObject("state")?.optJSONObject("input") ?: continue
                val path = input.optString("filePath", "").ifEmpty { input.optString("path", "") }
                if (path.isNotEmpty()) changedFiles.add(path)
            }
        }
    }

    private fun addAssistantBubble(text: String) {
        val (_, content) = assistantCard()
        content.text = AiMarkdown.render(this, text)
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------- engine glue

    private fun setupEngine() {
        engine.listener = object : OcEngine.Listener {
            override fun onMessage(text: String, isComplete: Boolean) = runOnUiThread {
                if (text.isBlank()) return@runOnUiThread
                if (streamingView == null) {
                    val (card, tv) = assistantCard()
                    streamingCard = card
                    streamingView = tv
                }
                // Plain text while streaming (cheap); markdown once the turn ends.
                streamingView?.text = if (isComplete) AiMarkdown.render(this@AiWorkspaceActivity, text) else text
                scrollChatToBottom()
                if (isComplete) {
                    streamingView = null
                    streamingCard = null
                    updateTokenCount()
                }
            }

            override fun onThinking(status: String) = runOnUiThread {
                thinkBase = status.removeSuffix("...").ifBlank { "Working" }
                thinkingView.visibility = View.VISIBLE
            }

            override fun onReasoning(text: String) = runOnUiThread {
                showReasoning(text)
            }

            override fun onReasoningDone() = runOnUiThread {
                finishReasoning()
                thinkingView.visibility = View.GONE
            }

            override fun onToolCall(
                name: String, args: JSONObject, risk: OcEngine.RiskLevel,
                onApproval: (Boolean, Boolean) -> Unit,
            ) = runOnUiThread {
                showApprovalDialog(name, args, risk, onApproval)
            }

            override fun onDiffApproval(
                preview: OcEngine.DiffPreview,
                onApproval: (Boolean, Boolean) -> Unit,
            ) = runOnUiThread {
                showDiffApprovalDialog(preview, onApproval)
            }

            override fun onToolStart(name: String, args: JSONObject) = runOnUiThread {
                thinkingView.visibility = View.VISIBLE
                thinkingView.text = "⚙ " + t("Running", "실행 중") + ": $name"
                startToolCard(name, args)
            }

            override fun onToolEnd(name: String, result: String) = runOnUiThread {
                thinkingView.text = "⚙ " + t("Done", "완료") + ": $name"
                finishToolCard(name, null, result, isError = false)
            }

            override fun onToolEnd(name: String, args: JSONObject, result: String, isError: Boolean) = runOnUiThread {
                thinkingView.text = "⚙ " + t("Done", "완료") + ": $name"
                finishToolCard(name, args, result, isError)
            }

            /**
             * Live shell-log streaming: the engine emits one line per produced
             * output line while a shell tool runs. The running tool card's
             * preview is repainted with the newest lines so the user watches the
             * command working instead of staring at a frozen composer.
             */
            override fun onCommandOutput(commandId: String, line: String) = runOnUiThread {
                val name = commandId.removePrefix("tool_")
                val holder = liveCommandCards[name] ?: return@runOnUiThread
                val current = holder.result ?: ""
                holder.result = if (current.isBlank()) line else current + "\n" + line
                val visible = holder.result!!.lineSequence().filter { it.isNotBlank() }.toList().takeLast(2)
                holder.preview.text = visible.joinToString("\n")
                holder.preview.visibility = View.VISIBLE
            }

            override fun onPlanUpdate(rawPlan: String) = runOnUiThread {
                if (activeTab == Tab.PLAN) showTab(Tab.PLAN)
            }

            override fun onGenerationStats(tokens: Int, decodeElapsedMs: Long?) = runOnUiThread {
                if (tokens > 0 && (decodeElapsedMs ?: 0L) > 0) {
                    lastTokensPerSec = tokens / ((decodeElapsedMs ?: 0L) / 1000.0)
                } else if (tokens == 0) {
                    lastTokensPerSec = 0.0
                }
                if (::tokenCounter.isInitialized) updateTokenCount()
            }

            override fun onStateChange(state: OcEngine.AgentState) = runOnUiThread {
                engineState = state
                // The bottom bar's token count is derived from the stored
                // messages, so it has to be recomputed as the turn moves.
                // Without this it kept the value from the initial build - the
                // bar read "0 tok" for the whole session.
                updateStatusBar()
                if (state != OcEngine.AgentState.GENERATING) {
                    if (::tokenCounter.isInitialized) updateTokenCount()
                }
                sendButton.text = if (state == OcEngine.AgentState.GENERATING || state == OcEngine.AgentState.THINKING)
                    t("Stop", "중지") else t("Send", "전송")
                val p = StudioUi.palette
                val (label, color) = when (state) {
                    OcEngine.AgentState.THINKING -> t("Thinking…", "생각 중…") to p.info
                    OcEngine.AgentState.GENERATING -> t("Writing…", "응답 생성 중…") to p.accent
                    OcEngine.AgentState.EXECUTING_TOOL -> t("Running tool…", "도구 실행 중…") to p.info
                    OcEngine.AgentState.WAITING_TOOL_APPROVAL -> t("Waiting for approval", "승인 대기") to p.warning
                    OcEngine.AgentState.PROCESSING_TOOL_RESULT -> t("Processing result…", "결과 처리 중…") to p.info
                    OcEngine.AgentState.COMPLETED -> t("Completed", "완료") to p.success
                    OcEngine.AgentState.ERROR -> t("Error", "오류") to p.error
                    OcEngine.AgentState.CANCELLED -> t("Cancelled", "취소됨") to p.muted
                    OcEngine.AgentState.IDLE -> t("Ready", "대기") to p.textSoft
                }
                updateStatusPill(label, color)
                if (state == OcEngine.AgentState.IDLE || state == OcEngine.AgentState.COMPLETED ||
                    state == OcEngine.AgentState.ERROR || state == OcEngine.AgentState.CANCELLED
                ) {
                    thinkingView.visibility = View.GONE
                    if (::retryView.isInitialized) {
                        retryView.visibility = View.GONE
                        retryTickToken++
                    }
                }
            }

            override fun onError(message: String) = runOnUiThread {
                thinkingView.visibility = View.GONE
                if (::retryView.isInitialized) {
                    retryView.visibility = View.GONE
                    retryTickToken++
                }
                addSystemBubble("⚠ $message")
                // Only offer retry when the engine really can re-run the same
                // request (nothing running, failure remembered, no live request).
                if (runCatching { engine.canRetryLastFailed() }.getOrDefault(false)) {
                    addRetryCard()
                }
            }

            override fun onTodos(todos: JSONArray) = runOnUiThread {
                if (activeTab == Tab.PLAN) showTab(Tab.PLAN)
            }

            override fun onOrchestratorUpdate() = runOnUiThread {
                if (activeTab == Tab.AGENTS || activeTab == Tab.RESULTS || activeTab == Tab.LOGS) {
                    showTab(activeTab)
                }
            }

            override fun onAskQuestion(questions: JSONArray, onAnswer: (JSONArray?) -> Unit) = runOnUiThread {
                showQuestionDialog(questions, onAnswer)
            }

            override fun onDownloadFile(
                url: String,
                description: String,
                fileName: String?,
                onResult: (DownloadResult) -> Unit
            ) = runOnUiThread {
                showDownloadDialog(url, description, fileName, onResult)
            }

            override fun onFileChanged(path: String) = runOnUiThread {
                changedFiles.add(path)
                addDiffCard(path)
                scheduleValidation()
            }

            override fun onNotice(text: String) = runOnUiThread {
                addSystemBubble(text)
            }

            override fun onRetryStatus(status: com.aide.mobilestudio.ai.retry.RetryStatus) = runOnUiThread {
                if (!::retryView.isInitialized) return@runOnUiThread
                // CONNECTING and STREAMING are the normal path and carry no
                // error, so rendering every phase turned each local-model
                // request into a "🔁 Retry 1/0 · transient error" banner before
                // its first token ever arrived. Only a live retry gets a banner.
                if (!status.isRetrying()) {
                    retryView.visibility = View.GONE
                    retryTickToken++
                    return@runOnUiThread
                }
                val label = status.error?.reasonLabel() ?: t("transient error", "일시적 오류")
                retryView.visibility = View.VISIBLE
                val next = status.nextAttemptAtMs
                if (next == null) {
                    retryView.text = "🔁 " + t("Retrying", "재시도") +
                        " ${status.attempt}/${status.maxRetries} · $label"
                    return@runOnUiThread
                }
                retryTickToken++
                val token = retryTickToken
                val tick = object : Runnable {
                    override fun run() {
                        if (token != retryTickToken) return
                        val remain = next - System.currentTimeMillis()
                        if (remain <= 0) {
                            retryView.text = "🔁 " + t("Retrying now…", "지금 재시도…") +
                                " (${status.attempt}/${status.maxRetries})"
                            return
                        }
                        val seconds = String.format(Locale.US, "%.1f", remain / 1000.0)
                        retryView.text = "🔁 " + t("Retry", "재시도") +
                            " ${status.attempt}/${status.maxRetries} · $label · ${seconds}s"
                        handler.postDelayed(this, 200)
                    }
                }
                tick.run()
            }
        }
    }

    /** Returns an AlertDialog.Builder using the app's dark dialog theme so text is always visible. */
    private fun themedDialog(): AlertDialog.Builder {
        return AlertDialog.Builder(this, R.style.Theme_MobileStudio_Dialog)
    }

    private fun showApprovalDialog(
        name: String, args: JSONObject, risk: OcEngine.RiskLevel,
        onApproval: (Boolean, Boolean) -> Unit,
    ) {
        val detail = args.toString(2).take(1500)
        themedDialog()
            .setTitle(t("Approval required", "승인 필요") + " · $name")
            .setMessage("${t("Risk", "위험도")}: ${risk.name}\n\n$detail")
            .setPositiveButton(t("Allow once", "1회 허용")) { _, _ -> onApproval(true, false) }
            .setNeutralButton(t("Always", "항상 허용")) { _, _ -> onApproval(true, true) }
            .setNegativeButton(t("Deny", "거부")) { _, _ -> onApproval(false, false) }
            .setOnCancelListener { onApproval(false, false) }
            .show()
    }

    private fun showQuestionDialog(questions: JSONArray, onAnswer: (JSONArray?) -> Unit) {
        var answered = false
        val answerOnce: (JSONArray?) -> Unit = { r -> if (!answered) { answered = true; onAnswer(r) } }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(column) }
        val providers = mutableListOf<() -> JSONObject?>()
        for (i in 0 until questions.length()) {
            val q = questions.optJSONObject(i) ?: continue
            val header = q.optString("header", "Question").ifBlank { "Question" }
            val text = q.optString("question", "")
            val options = q.optJSONArray("options")
            column.addView(TextView(this).apply {
                this.text = header
                setTypeface(null, Typeface.BOLD)
                setTextColor(StudioUi.palette.accent)
                textSize = StudioUi.TypeSmall
                setPadding(0, dp(12), 0, dp(4))
            })
            if (text.isNotEmpty()) column.addView(TextView(this).apply {
                this.text = text
                setTextColor(StudioUi.palette.text)
                textSize = StudioUi.TypeBody
            })
            if (options == null || options.length() == 0) {
                val et = EditText(this).apply { hint = t("Your answer", "답변 입력") }
                column.addView(et)
                providers.add { et.text.toString().trim().takeIf { s -> s.isNotEmpty() }?.let { JSONObject().put("answer", it) } }
            } else {
                // A single-choice question used to render checkboxes, so the
                // user could select two mutually exclusive answers. Honour the
                // tool's `multiple` flag instead of always allowing many.
                val multiple = q.optBoolean("multiple", false)
                val selected = mutableListOf<Pair<CompoundButton, String>>()
                val radioGroup = if (multiple) null else {
                    RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
                }
                for (j in 0 until options.length()) {
                    val opt = options.optJSONObject(j) ?: continue
                    val label = opt.optString("label").ifBlank { "Option ${j + 1}" }
                    val description = opt.optString("description")
                    val button: CompoundButton = if (multiple) CheckBox(this) else RadioButton(this)
                    button.text = if (description.isBlank()) label else "$label — $description"
                    button.setTextColor(StudioUi.palette.text)
                    if (radioGroup != null) radioGroup.addView(button) else column.addView(button)
                    selected.add(button to label)
                }
                radioGroup?.let { column.addView(it) }
                // The tool description promises a free-form answer. Without
                // this field a question whose options do not fit had no way
                // to be answered at all.
                val custom = EditText(this).apply {
                    hint = t("Type your own answer (optional)", "직접 입력 (선택)")
                    setHintTextColor(StudioUi.palette.muted)
                    setTextColor(StudioUi.palette.text)
                }
                column.addView(custom)
                providers.add {
                    val chosen = selected.filter { it.first.isChecked }.map { it.second }.toMutableList()
                    custom.text.toString().trim().takeIf { it.isNotEmpty() }?.let { chosen.add(it) }
                    if (chosen.isEmpty()) null
                    else JSONArray().apply { chosen.forEach { put(it) } }.let { JSONObject().put("selected", it) }
                }
            }
        }
        themedDialog()
            .setTitle(t("Agent question", "에이전트 질문"))
            .setView(scroll)
            .setPositiveButton(t("Answer", "답변")) { _, _ ->
                val answers = JSONArray()
                for (provider in providers) provider()?.let { answers.put(it) }
                answerOnce(if (answers.length() == 0) null else answers)
            }
            .setNegativeButton(t("Dismiss", "취소")) { _, _ -> answerOnce(null) }
            .setOnCancelListener { answerOnce(null) }
            .show()
    }

    private fun showDownloadDialog(
        url: String,
        description: String,
        fileName: String?,
        onResult: (DownloadResult) -> Unit
    ) {
        val fileNameStr = fileName ?: url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "download"
        val message = "Download: $fileNameStr\n$description"
        
        themedDialog()
            .setTitle("Download File")
            .setMessage(message)
            .setPositiveButton("Download") { _, _ ->
                // Start the download using DownloadCenter
                val totalBytes = DownloadCenter.probeContentLength(url)
                if (DownloadCenter.confirm(
                    label = fileNameStr,
                    url = url,
                    downloadPath = filesDir.absolutePath,
                    fileName = fileNameStr,
                    totalBytes = totalBytes
                )) {
                    // Download was approved, start it
                    // The actual download will be handled by the download manager
                    // For now, return success to indicate user approved
                    onResult(DownloadResult(success = true, filePath = "pending"))
                } else {
                    onResult(DownloadResult(success = false, error = "User declined download"))
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                onResult(DownloadResult(success = false, error = "User cancelled"))
            }
            .setOnCancelListener { onResult(DownloadResult(success = false, error = "User cancelled")) }
            .show()
    }

    // ------------------------------------------------------------- Plan tab

    private fun buildPlanTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        column.addView(sectionTitle(t("Plan / Tasks", "계획 / 작업")))
        val todos = engine.currentTodos()
        if (todos.length() == 0) {
            column.addView(AiUiKit.emptyState(this, "🗒️", t("No plan yet. Ask the agent to plan a change.", "아직 계획이 없습니다. 에이전트에게 계획을 요청하세요.")))
        } else {
            for (i in 0 until todos.length()) {
                val todo = todos.optJSONObject(i) ?: continue
                val status = todo.optString("status", "pending")
                val mark = when (status) {
                    "completed" -> "✔"; "in_progress" -> "▶"; "cancelled" -> "✖"; else -> "○"
                }
                val priority = todo.optString("priority", "medium")
                val content = todo.optString("content")
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(this).apply {
                    text = "$mark  $content  ($priority)"
                    setTextColor(if (status == "completed") p.muted else p.text)
                    textSize = StudioUi.TypeBody
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(AiUiKit.actionChip(this, t("Retry", "재시도")) { _ ->
                    engine.sendMessage(t("Retry this step: $content", "다음 단계를 다시 시도해줘: $content"))
                    showTab(Tab.CHAT)
                })
                row.setOnLongClickListener { showTodoActions(i, content); true }
                column.addView(row)
            }
            column.addView(AiUiKit.ghostButton(this, t("+ Add step", "+ 단계 추가")) { _ -> promptAddTodo() })
            column.addView(AiUiKit.ghostButton(this, t("Edit plan", "계획 편집")) { _ ->
                engine.sendMessage(
                    t(
                        "Revise the plan: add, remove, or reorder steps as needed, then show the updated todo list.",
                        "계획을 수정해줘: 필요하면 단계를 추가/삭제/순서 변경하고, 갱신된 todo 목록을 보여줘.",
                    ),
                )
                showTab(Tab.CHAT)
            })
        }
        column.addView(Button(this).apply {
            text = t("Ask the agent to plan", "에이전트에게 계획 요청")
            isAllCaps = false
            setOnClickListener {
                engine.sendMessage(
                    t(
                        "Break this task into a short, ordered plan with a todo list, then wait for approval.",
                        "이 작업을 짧고 순서가 있는 계획과 todo 목록으로 나눠서 작성하고 승인을 기다려줘.",
                    ),
                )
                showTab(Tab.CHAT)
            }
        })
        return ScrollView(this).apply { addView(column) }
    }

    // ------------------------------------------------------- orchestration UI

    private fun buildAgentsTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        column.addView(sectionTitle(t("Agents / multi-model tasks", "에이전트 / 다중 모델 작업")))
        column.addView(AiUiKit.body(this, t(
            "The main model proposes delegated tasks. The orchestrator validates them, chooses a local or API model using capabilities, memory, success history and budget, and runs only the tools the task needs. A subagent never receives an API key or permission to call another provider.",
            "메인 모델은 위임 작업을 제안합니다. 오케스트레이터가 이를 검증하고, 기능·메모리·성공 이력·예산을 기준으로 로컬 또는 API 모델을 선택하며, 작업에 필요한 도구만 허용합니다. 하위 에이전트는 API 키나 다른 공급자 호출 권한을 받지 않습니다.",
        )))
        column.addView(buildAgentLimitsCard())

        val snapshot = runCatching { engine.latestOrchestratorSnapshot() }.getOrNull()
        if (snapshot == null) {
            column.addView(AiUiKit.emptyState(this, "🧭", t(
                "No delegated batch yet. Ask the main agent to split a task, or use the delegate tool.",
                "아직 위임된 작업 묶음이 없습니다. 메인 에이전트에게 작업 분할을 요청하거나 delegate 도구를 사용하세요.",
            )))
            return ScrollView(this).apply { addView(column) }
        }

        val summary = AiUiKit.card(this, elevated = true).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val statusText = when {
            snapshot.success == true -> t("SUCCEEDED", "성공")
            snapshot.success == false -> t("FAILED", "실패")
            else -> t("RUNNING", "실행 중")
        }
        val statusColor = when {
            snapshot.success == true -> p.success
            snapshot.success == false -> p.error
            else -> p.warning
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(this).apply {
            text = t("Latest batch", "최근 작업 묶음") + " · " + snapshot.batchId
            setTextColor(p.text)
            textSize = StudioUi.TypeBody
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(AiUiKit.pill(this, statusText, AiUiKit.withAlpha(statusColor, 0.16f), statusColor))
        summary.addView(head)
        summary.addView(AiUiKit.body(this, snapshot.summary.ifBlank { t("No summary", "요약 없음") }).apply {
            setPadding(0, dp(8), 0, 0)
        })
        val budget = snapshot.budget
        val cost = budget.costUsd?.let { String.format(Locale.US, "~$%.4f", it) }
            ?: t("unknown", "알 수 없음")
        summary.addView(AiUiKit.body(this, t(
            "API calls ${budget.apiCalls}/${budget.maxApiCalls} · tokens ~${budget.totalTokens}/${budget.maxTokens} · cost $cost",
            "API 호출 ${budget.apiCalls}/${budget.maxApiCalls} · 토큰 ~${budget.totalTokens}/${budget.maxTokens} · 비용 $cost",
        )).apply { setPadding(0, dp(4), 0, 0) })
        snapshot.verification?.let {
            summary.addView(AiUiKit.body(this, t("Verification: ", "검증: ") + it).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (snapshot.tasks.any { !it.isFinished }) {
            summary.addView(AiUiKit.ghostButton(this, t("Cancel whole batch", "전체 작업 취소")) { _ ->
                engine.cancelOrchestratorTask(null)
                showTab(Tab.AGENTS)
            })
        }
        column.addView(summary, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) })

        column.addView(sectionTitle(t("Tasks", "작업")))
        for ((index, task) in snapshot.tasks.withIndex()) {
            column.addView(buildAgentTaskCard(task, index), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun buildAgentLimitsCard(): View {
        val p = StudioUi.palette
        val card = AiUiKit.card(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = t("Limits (this device)", "제한 (이 기기)")
            setTextColor(p.text)
            textSize = StudioUi.TypeBody
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(stepperRow(
            t("Parallel tasks", "동시 작업"),
            settings.orchestratorMaxParallel,
            1,
            5,
        ) { value -> settings.orchestratorMaxParallel = value })
        card.addView(stepperRow(
            t("Maximum depth", "최대 위임 깊이"),
            settings.orchestratorMaxDepth,
            1,
            5,
        ) { value -> settings.orchestratorMaxDepth = value })
        card.addView(stepperRow(
            t("Tasks per batch", "묶음당 작업"),
            settings.orchestratorMaxTasksPerBatch,
            1,
            24,
        ) { value -> settings.orchestratorMaxTasksPerBatch = value })
        card.addView(AiUiKit.body(this, t(
            "API calls ${settings.orchestratorApiCallBudget} · token budget ${settings.orchestratorTokenBudget} · cost cap " +
                if (settings.orchestratorCostBudgetUsd > 0f) String.format(Locale.US, "$%.4f", settings.orchestratorCostBudgetUsd)
                else t("not set", "설정 안 함"),
            "API 호출 ${settings.orchestratorApiCallBudget} · 토큰 예산 ${settings.orchestratorTokenBudget} · 비용 상한 " +
                if (settings.orchestratorCostBudgetUsd > 0f) String.format(Locale.US, "$%.4f", settings.orchestratorCostBudgetUsd)
                else t("설정 안 함", "설정 안 함"),
        )).apply { setPadding(0, dp(6), 0, 0) })
        card.addView(AiUiKit.actionChip(this, t("Edit budgets", "예산 편집")) { _ -> editOrchestratorBudgets() })
        return card
    }

    private fun stepperRow(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        onChange: (Int) -> Unit,
    ): View {
        val p = StudioUi.palette
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(p.textSoft)
            textSize = StudioUi.TypeBody
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(AiUiKit.actionChip(this, "−") { _ ->
            onChange((value - 1).coerceAtLeast(min))
            showTab(Tab.AGENTS)
        })
        row.addView(TextView(this).apply {
            text = value.toString()
            setTextColor(p.text)
            textSize = StudioUi.TypeBody
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), 0)
        })
        row.addView(AiUiKit.actionChip(this, "+") { _ ->
            onChange((value + 1).coerceAtMost(max))
            showTab(Tab.AGENTS)
        })
        return row
    }

    private fun editOrchestratorBudgets() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val calls = EditText(this).apply {
            hint = t("API calls (0 = unlimited)", "API 호출 (0 = 무제한)")
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.orchestratorApiCallBudget.toString())
        }
        val tokens = EditText(this).apply {
            hint = t("Tokens (0 = unlimited)", "토큰 (0 = 무제한)")
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.orchestratorTokenBudget.toString())
        }
        val cost = EditText(this).apply {
            hint = t("Cost cap in USD (0 = not set)", "비용 상한 USD (0 = 설정 안 함)")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (settings.orchestratorCostBudgetUsd > 0f) settings.orchestratorCostBudgetUsd.toString() else "")
        }
        column.addView(calls)
        column.addView(tokens)
        column.addView(cost)
        themedDialog()
            .setTitle(t("Orchestrator budgets", "오케스트레이터 예산"))
            .setView(ScrollView(this).apply { addView(column) })
            .setPositiveButton(t("Save", "저장")) { _, _ ->
                calls.text.toString().toIntOrNull()?.let { settings.orchestratorApiCallBudget = it.coerceAtLeast(0) }
                tokens.text.toString().toIntOrNull()?.let { settings.orchestratorTokenBudget = it.coerceAtLeast(0) }
                cost.text.toString().toFloatOrNull()?.let { settings.orchestratorCostBudgetUsd = it.coerceAtLeast(0f) }
                showTab(Tab.AGENTS)
            }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    private fun buildAgentTaskCard(task: AgentTask, index: Int): View {
        val p = StudioUi.palette
        val statusColor = when (task.status) {
            TaskStatus.SUCCEEDED -> p.success
            TaskStatus.FAILED, TaskStatus.TIMED_OUT -> p.error
            TaskStatus.CANCELLED -> p.muted
            TaskStatus.RUNNING, TaskStatus.READY -> p.warning
            else -> p.textSoft
        }
        val card = AiUiKit.card(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "${index + 1}. ${task.description}"
            setTextColor(p.text)
            textSize = StudioUi.TypeBody
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(AiUiKit.pill(this, task.status.name, AiUiKit.withAlpha(statusColor, 0.16f), statusColor))
        card.addView(header)

        val provider = if (task.assignedModelId?.startsWith("local/") == true) "LOCAL" else "API"
        val model = task.assignedModelId ?: t("(not assigned)", "(미배정)")
        val details = buildString {
            append(provider).append(" · ").append(model)
            append(" · ").append(task.difficulty.name)
            append(" · ").append(task.status.name)
            if (task.modelHistory.isNotEmpty()) append(" · attempts ").append(task.modelHistory.size)
            task.durationMs?.let { append(" · ").append(it).append("ms") }
            task.result?.let { r ->
                if (r.costUsd != null) append(" · ~$").append(String.format(Locale.US, "%.4f", r.costUsd))
                if (r.tokensIn + r.tokensOut > 0) {
                    append(" · ~").append(r.tokensIn + r.tokensOut).append(" tokens")
                }
            }
        }
        card.addView(AiUiKit.body(this, details).apply { setPadding(0, dp(4), 0, 0) })
        if (task.modelHistory.size > 1) {
            card.addView(AiUiKit.body(this, t("Attempts: ", "시도: ") + task.modelHistory.joinToString(" → ") {
                "${it.modelId}${if (it.success) " ✔" else " ✖"}${if (it.promoted) " ↑" else ""}"
            }).apply { setPadding(0, dp(4), 0, 0) })
        }
        card.addView(AiUiKit.body(this, t("Tools: ", "도구: ") +
            if (task.allowedTools.isEmpty()) t("agent default", "에이전트 기본") else task.allowedTools.sorted().joinToString(", ")
        ).apply { setPadding(0, dp(4), 0, 0) })
        task.error?.let {
            card.addView(AiUiKit.body(this, t("Error: ", "오류: ") + it).apply {
                setTextColor(p.error)
                setPadding(0, dp(4), 0, 0)
            })
        }
        task.result?.changedFiles?.takeIf { it.isNotEmpty() }?.let { files ->
            card.addView(AiUiKit.body(this, t("Changed files: ", "변경 파일: ") + files.joinToString(", ")).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        if (!task.isFinished) {
            card.addView(AiUiKit.actionChip(this, t("Cancel task", "작업 취소")) { _ ->
                engine.cancelOrchestratorTask(task.id)
                showTab(Tab.AGENTS)
            })
        }
        return card
    }

    private fun buildResultsTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        column.addView(sectionTitle(t("Results", "결과")))
        val snapshot = runCatching { engine.latestOrchestratorSnapshot() }.getOrNull()
        if (snapshot == null) {
            column.addView(AiUiKit.emptyState(this, "📋", t(
                "No delegated results yet.",
                "아직 위임 결과가 없습니다.",
            )))
            return ScrollView(this).apply { addView(column) }
        }
        column.addView(AiUiKit.body(this, snapshot.summary).apply { setPadding(0, 0, 0, dp(8)) })
        snapshot.verification?.let {
            column.addView(AiUiKit.body(this, t("Verification: ", "검증: ") + it).apply {
                setPadding(0, 0, 0, dp(8))
            })
        }
        val changed = snapshot.tasks.flatMap { it.result?.changedFiles ?: emptyList() }.distinct()
        if (changed.isNotEmpty()) {
            val card = AiUiKit.card(this).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            card.addView(TextView(this).apply {
                text = t("Changed files", "변경된 파일")
                setTextColor(p.text)
                textSize = StudioUi.TypeBody
                setTypeface(null, Typeface.BOLD)
            })
            for (file in changed) {
                card.addView(AiUiKit.body(this, "• $file").apply { setPadding(0, dp(3), 0, 0) })
            }
            column.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
        for ((index, task) in snapshot.tasks.withIndex()) {
            val result = task.result
            val card = AiUiKit.card(this).apply {
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            card.addView(TextView(this).apply {
                text = "${index + 1}. ${task.description}"
                setTextColor(p.text)
                textSize = StudioUi.TypeBody
                setTypeface(null, Typeface.BOLD)
            })
            if (result == null) {
                card.addView(AiUiKit.body(this, t("No result (", "결과 없음 (") + task.status.name + ")"))
            } else {
                card.addView(AiUiKit.body(this, t("Summary: ", "요약: ") + result.summary).apply {
                    setPadding(0, dp(5), 0, 0)
                })
                if (result.errors.isNotEmpty()) {
                    card.addView(AiUiKit.body(this, t("Errors: ", "오류: ") + result.errors.joinToString(" | ")).apply {
                        setTextColor(p.error)
                        setPadding(0, dp(5), 0, 0)
                    })
                }
                if (result.needsFollowUp) {
                    card.addView(AiUiKit.body(this, t("Needs follow-up", "후속 작업 필요")).apply {
                        setTextColor(p.warning)
                        setPadding(0, dp(5), 0, 0)
                    })
                }
                if (result.output.isNotBlank()) {
                    card.addView(TextView(this).apply {
                        text = result.output.take(3_000)
                        setTextColor(p.textSoft)
                        textSize = 12f
                        setPadding(0, dp(6), 0, 0)
                        setTextIsSelectable(true)
                    })
                }
                val diff = result.diff
                if (!diff.isNullOrBlank()) {
                    card.addView(TextView(this).apply {
                        text = diff.take(8_000)
                        setTextColor(p.textSoft)
                        textSize = 11f
                        typeface = Typeface.MONOSPACE
                        setPadding(0, dp(6), 0, 0)
                        setTextIsSelectable(true)
                    })
                }
            }
            column.addView(card, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) })
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun buildLogsTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        column.addView(sectionTitle(t("Orchestrator log", "오케스트레이터 로그")))
        column.addView(AiUiKit.ghostButton(this, t("Refresh", "새로고침")) { _ -> showTab(Tab.LOGS) })
        val logs = runCatching { engine.orchestratorLogs() }.getOrDefault(emptyList())
        if (logs.isEmpty()) {
            column.addView(AiUiKit.emptyState(this, "📜", t(
                "No orchestration log yet.",
                "아직 오케스트레이션 로그가 없습니다.",
            )))
        } else {
            column.addView(TextView(this).apply {
                text = logs.takeLast(300).joinToString("\n")
                setTextColor(p.textSoft)
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
            })
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun planTodos(): MutableList<JSONObject> {
        val arr = engine.currentTodos()
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.let { o -> JSONObject(o.toString()) } }
            .toMutableList()
    }

    private fun saveTodos(list: List<JSONObject>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        engine.updateTodos(arr)
        showTab(Tab.PLAN)
    }

    private fun promptAddTodo() {
        val field = EditText(this).apply { hint = t("Step description", "단계 설명") }
        themedDialog()
            .setTitle(t("Add step", "단계 추가"))
            .setView(field)
            .setPositiveButton(t("Add", "추가")) { _, _ ->
                val content = field.text.toString().trim()
                if (content.isNotEmpty()) {
                    val list = planTodos()
                    list.add(JSONObject().apply {
                        put("content", content)
                        put("status", "pending")
                        put("priority", "medium")
                    })
                    saveTodos(list)
                }
            }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    private fun showTodoActions(index: Int, content: String) {
        val actions = arrayOf(
            t("Retry", "재시도"),
            t("Move up", "위로 이동"),
            t("Move down", "아래로 이동"),
            t("Toggle done", "완료 토글"),
            t("Delete", "삭제"),
        )
        themedDialog()
            .setTitle(content.take(60))
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> {
                        engine.sendMessage(t("Retry this step: $content", "다음 단계를 다시 시도해줘: $content"))
                        showTab(Tab.CHAT)
                    }
                    1 -> {
                        val list = planTodos()
                        if (index > 0) {
                            val item = list.removeAt(index)
                            list.add(index - 1, item)
                            saveTodos(list)
                        }
                    }
                    2 -> {
                        val list = planTodos()
                        if (index < list.size - 1) {
                            val item = list.removeAt(index)
                            list.add(index + 1, item)
                            saveTodos(list)
                        }
                    }
                    3 -> {
                        val list = planTodos()
                        list.getOrNull(index)?.let { o ->
                            o.put("status", if (o.optString("status") == "completed") "pending" else "completed")
                        }
                        saveTodos(list)
                    }
                    4 -> {
                        val list = planTodos()
                        if (index in list.indices) {
                            list.removeAt(index)
                            saveTodos(list)
                        }
                    }
                }
            }
            .show()
    }

    // ------------------------------------------------------------ Changes tab

    private fun buildChangesTab(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        column.addView(sectionTitle(t("Changed files", "변경된 파일")))
        if (changedFiles.isEmpty()) {
            column.addView(AiUiKit.emptyState(this, "📝", t("No files changed in this session yet.", "이 세션에서 아직 변경된 파일이 없습니다.")))
        } else {
            for (path in changedFiles) {
                // Tap the file name to preview the diff; the chip hands the
                // file to the editor (finishing this activity).
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                }
                row.addView(TextView(this).apply {
                    text = relativePath(path)
                    setTextColor(StudioUi.palette.info)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { showDiff(path) }
                })
                row.addView(AiUiKit.actionChip(this, t("Open in editor", "편집기에서 열기")) { _ ->
                    openInEditor(path)
                })
                column.addView(row)
            }
            column.addView(Button(this).apply {
                text = t("Revert all changes", "모든 변경 되돌리기")
                isAllCaps = false
                setOnClickListener {
                    themedDialog()
                        .setMessage(t("Revert all files changed in this session?", "이 세션에서 변경한 모든 파일을 되돌릴까요?"))
                        .setPositiveButton(t("Revert", "되돌리기")) { _, _ ->
                            val restored = engine.revert()
                            addSystemBubble(t("Reverted ${restored.size} file(s).", "${restored.size}개 파일을 되돌렸습니다."))
                            changedFiles.clear()
                            showTab(Tab.CHANGES)
                        }
                        .setNegativeButton(t("Cancel", "취소"), null)
                        .show()
                }
            })
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun refreshChangedFiles() {
        // no-op placeholder retained for clarity; the Changes tab is rebuilt on show.
    }

    private fun showDiff(path: String) {
        val file = File(path)
        val after = runCatching { file.readText() }.getOrDefault("")
        val before = engine.latestBackupForCurrentSession(path)?.let { runCatching { it.readText() }.getOrNull() }
        if (before == null) {
            themedDialog()
                .setTitle(relativePath(path))
                .setMessage(t("No pre-edit snapshot available for this file.", "이 파일의 변경 전 스냅샷이 없습니다."))
                .setPositiveButton("OK", null)
                .show()
            return
        }
        DiffViewerActivity.start(
            this,
            relativePath(path) + " (before)",
            before,
            relativePath(path) + " (after)",
            after,
            filePath = path,
        )
    }

    /**
     * Public entry point for editing an agent-changed file: hand it back to
     * the editor and leave the workspace. MainActivity already consumes the
     * [EXTRA_OPEN_IN_EDITOR] extra ("open_file") in its onCreate, so this
     * needs no MainActivity change. CLEAR_TOP folds the stack back to a single
     * editor instance; because MainActivity has the default (standard) launch
     * mode, it is recreated with the new intent and its onCreate opens the
     * file (its onNewIntent deliberately ignores this extra).
     */
    fun openInEditor(path: String) {
        val intent = Intent(this, com.aide.mobilestudio.MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_IN_EDITOR, path)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    // ------------------------------------------------------------ Search tab

    private lateinit var searchInput: EditText
    private lateinit var searchResults: LinearLayout

    private data class SearchHit(val file: String, val line: Int, val text: String)

    private fun buildSearchTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(6))
        }
        searchInput = EditText(this).apply {
            hint = t("Search file names and contents…", "파일명·내용 검색…")
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = StudioUi.TypeBody
            background = AiUiKit.rounded(p.surface2, dp(12), strokeColor = p.border, strokePx = dp(1))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { runSearch(); true } else false
            }
        }
        row.addView(searchInput)
        row.addView(Button(this).apply {
            text = t("Search", "검색")
            isAllCaps = false
            setTextColor(p.onAccent)
            background = AiUiKit.rippleContent(this@AiWorkspaceActivity, p.accent, dp(12))
            setOnClickListener { runSearch() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        })
        column.addView(row)

        searchResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        column.addView(
            ScrollView(this).apply { addView(searchResults) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return column
    }

    private fun runSearch() {
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val root = projectDir ?: return
        if (query.isEmpty()) return
        val p = StudioUi.palette
        searchResults.removeAllViews()
        searchResults.addView(body(t("Searching…", "검색 중…")))
        scope.launch {
            val results = withContext(Dispatchers.IO) { performSearch(root, query) }
            searchResults.removeAllViews()
            if (results.isEmpty()) {
                searchResults.addView(AiUiKit.emptyState(this@AiWorkspaceActivity, "🔎", t("No matches.", "결과 없음.")))
                return@launch
            }
            for (r in results) {
                searchResults.addView(TextView(this@AiWorkspaceActivity).apply {
                    text = "${r.file}:${r.line}  ${r.text.take(160)}"
                    setTextColor(p.info)
                    textSize = StudioUi.TypeSmall
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(6), 0, dp(6))
                    setOnClickListener {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("result", r.file + ":" + r.line))
                        Toast.makeText(this@AiWorkspaceActivity, t("Copied path", "경로 복사됨"), Toast.LENGTH_SHORT).show()
                    }
                    setOnLongClickListener {
                        engine.sendMessage(t("Open and explain ${r.file}:${r.line}:\n${r.text}", "${r.file}:${r.line} 를 열어서 설명해줘:\n${r.text}"))
                        showTab(Tab.CHAT)
                        true
                    }
                })
            }
        }
    }

    private fun performSearch(root: File, query: String): List<SearchHit> {
        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
        val nameQuery = query.lowercase()
        val hits = mutableListOf<SearchHit>()
        val excluded = setOf(".git", "build", ".gradle", "node_modules", ".idea", ".cxx", ".kotlin", "out")
        root.walkTopDown().onEnter { it.name !in excluded }.forEach { f ->
            if (hits.size >= 200 || !f.isFile || f.length() > 2L * 1024 * 1024) return@forEach
            val rel = runCatching { f.relativeTo(root).path }.getOrDefault(f.name)
            if (rel.lowercase().contains(nameQuery)) hits.add(SearchHit(rel, 0, ""))
            if (regex == null) return@forEach
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            var n = 1
            for (ln in text.lineSequence()) {
                if (regex.containsMatchIn(ln)) {
                    hits.add(SearchHit(rel, n, ln.trim()))
                    if (hits.size >= 200) break
                }
                n++
            }
        }
        return hits
    }

    // -------------------------------------------------------- Diagnostics tab

    private fun buildDiagnosticsTab(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        column.addView(sectionTitle(t("Diagnostics", "진단")))
        if (projectDir == null) {
            column.addView(AiUiKit.emptyState(this, "🧭", t("No project is open, so live validation is unavailable.", "열린 프로젝트가 없어 실시간 검증을 사용할 수 없습니다.")))
            return ScrollView(this).apply { addView(column) }
        }
        val summary = validation.lastSummary()
        val summaryText = when {
            summary == null -> t("Not validated yet.", "아직 검증하지 않았습니다.")
            else -> summary.report()
        }
        column.addView(body(summaryText))
        column.addView(Button(this).apply {
            text = t("Re-run validation", "다시 검사")
            isAllCaps = false
            setOnClickListener { scheduleValidation(immediate = true) }
        })

        for (d in validation.diagnostics()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { showDiagnostic(d) }
            }
            row.addView(TextView(this).apply {
                text = (if (d.severity.name == "ERROR") "✖ " else "⚠ ") + "${d.file}:${d.line}:${d.column}"
                setTextColor(if (d.severity.name == "ERROR") StudioUi.palette.error else StudioUi.palette.warning)
                textSize = StudioUi.TypeSmall
            })
            row.addView(TextView(this).apply {
                text = d.message
                setTextColor(StudioUi.palette.text)
                textSize = 13f
            })
            column.addView(row)
        }
        val resolved = validation.resolved()
        if (resolved.isNotEmpty()) {
            column.addView(sectionTitle(t("Resolved", "해결됨")))
            for (d in resolved) {
                column.addView(TextView(this).apply {
                    text = "✔ ${d.file}:${d.line} ${d.message}"
                    setTextColor(StudioUi.palette.success)
                    textSize = StudioUi.TypeSmall
                })
            }
        }
        return ScrollView(this).apply { addView(column) }
    }

    private fun showDiagnostic(d: com.aide.mobilestudio.ai.validation.LiveDiagnostic) {
        themedDialog()
            .setTitle(t("Diagnostic", "진단"))
            .setMessage(
                "${d.file}:${d.line}:${d.column}\n${d.severity}\n\n${d.message}" +
                    (d.snippet?.let { "\n\n$it" } ?: ""),
            )
            .setPositiveButton(t("Ask AI to fix", "AI에게 수정 요청")) { _, _ ->
                engine.sendMessage(
                    t(
                        "Fix this problem:\n${d.file}:${d.line}:${d.column}: ${d.message}",
                        "이 문제를 수정해줘:\n${d.file}:${d.line}:${d.column}: ${d.message}",
                    ),
                )
                showTab(Tab.CHAT)
            }
            .setNeutralButton(t("Copy", "복사")) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnostic", "${d.file}:${d.line}:${d.column}: ${d.message}"))
            }
            .setNegativeButton(t("Close", "닫기"), null)
            .show()
    }

    private fun scheduleValidation(immediate: Boolean = false) {
        if (projectDir == null) return
        handler.removeCallbacks(validateRunnable)
        handler.postDelayed(validateRunnable, if (immediate) 0 else 1200)
    }

    private val validateRunnable = Runnable {
        val dir = projectDir ?: return@Runnable
        scope.launch {
            withContext(Dispatchers.IO) {
                validation.recordChanges(changedFiles.map { File(it) })
                validation.validate(dir)
            }
            if (activeTab == Tab.DIAGNOSTICS) showTab(Tab.DIAGNOSTICS)
        }
    }

    // --------------------------------------------------- Files rail destination

    private fun buildFilesTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        if (projectDir == null) {
            column.addView(AiUiKit.emptyState(this, "🗂", t("No project is open.", "열린 프로젝트가 없습니다.")))
            return column
        }
        if (browseDir == null || !browseDir!!.isDirectory) browseDir = projectDir
        val crumbs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        if (browseDir != projectDir) {
            crumbs.addView(
                AiUiKit.actionChip(this, "‹ ${t("Up", "위로")}") { _ ->
                    browseDir = browseDir?.parentFile ?: projectDir
                    renderFiles()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(8) },
            )
        }
        crumbs.addView(TextView(this).apply {
            text = relativePath(browseDir!!.absolutePath).ifEmpty { "." }
            setTextColor(p.textSoft)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        column.addView(crumbs)
        filesList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(0), dp(8), dp(12))
        }
        column.addView(
            ScrollView(this).apply { addView(filesList) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        renderFiles()
        return column
    }

    private fun renderFiles() {
        if (!::filesList.isInitialized) return
        filesList.removeAllViews()
        val dir = browseDir ?: return
        val entries = dir.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.take(300)
            .orEmpty()
        if (entries.isEmpty()) {
            filesList.addView(AiUiKit.emptyState(this, "📭", t("Empty folder.", "빈 폴더입니다.")))
            return
        }
        for (entry in entries) {
            val isDir = entry.isDirectory
            val icon = when {
                isDir -> "📁"
                entry.extension.lowercase() in setOf("kt", "kts") -> "🄺"
                entry.extension.lowercase() == "java" -> "☕"
                entry.extension.lowercase() in setOf("xml") -> "📄"
                entry.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif") -> "🖼"
                else -> "📄"
            }
            filesList.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(9), dp(8), dp(9))
                    background = AiUiKit.rippleContent(this@AiWorkspaceActivity, StudioUi.palette.surface, dp(8))
                    setOnClickListener {
                        if (isDir) {
                            browseDir = entry
                            renderFiles()
                        } else {
                            showFileActions(entry)
                        }
                    }
                    addView(TextView(this@AiWorkspaceActivity).apply {
                        text = icon
                        textSize = 13f
                    })
                    addView(TextView(this@AiWorkspaceActivity).apply {
                        text = entry.name + if (isDir) "/" else ""
                        setTextColor(if (isDir) StudioUi.palette.text else StudioUi.palette.textSoft)
                        textSize = 13f
                        setPadding(dp(8), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    addView(TextView(this@AiWorkspaceActivity).apply {
                        text = if (isDir) "" else formatBytes(entry.length())
                        setTextColor(StudioUi.palette.muted)
                        textSize = 10f
                    })
                },
            )
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.US, bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(Locale.US, bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    private fun showFileActions(file: File) {
        val actions = arrayOf(
            t("Preview", "미리보기"),
            t("Ask AI to explain", "AI에게 설명 요청"),
            t("Ask AI to change", "AI에게 수정 요청"),
            t("Open in editor", "편집기에서 열기"),
        )
        themedDialog()
            .setTitle(file.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> previewFile(file)
                    1 -> {
                        showTab(Tab.CHAT)
                        submit(
                            t(
                                "Read ${relativePath(file.absolutePath)} and explain what it does.",
                                "${relativePath(file.absolutePath)} 파일을 읽고 어떤 역할을 하는지 설명해줘.",
                            ),
                        )
                    }
                    2 -> {
                        showTab(Tab.CHAT)
                        // Prefill instead of sending: the user still needs to
                        // describe what to change.
                        input.setText(
                            t(
                                "Change ${relativePath(file.absolutePath)} as follows: ",
                                "${relativePath(file.absolutePath)} 파일을 다음과 같이 수정해줘: ",
                            ),
                        )
                        input.requestFocus()
                    }
                    3 -> openInEditor(file.absolutePath)
                }
            }
            .show()
    }

    private fun previewFile(file: File) {
        val content = runCatching { file.readText() }.getOrNull()
        if (content == null) {
            Toast.makeText(this, t("Cannot read file (binary?)", "파일을 읽을 수 없습니다(바이너리?)"), Toast.LENGTH_SHORT).show()
            return
        }
        val lines = content.lineSequence().take(200).toList()
        val body = lines.joinToString("\n") + if (content.lines().size > 200) "\n… (+more)" else ""
        themedDialog()
            .setTitle(file.name)
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
    }

    // ----------------------------------------------------- Git rail destination

    private fun buildGitTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        if (projectDir == null) {
            column.addView(AiUiKit.emptyState(this, "⑂", t("No project is open.", "열린 프로젝트가 없습니다.")))
            return column
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(4))
        }
        fun gitChip(label: String, args: String) {
            actions.addView(
                AiUiKit.actionChip(this, label) { _ -> runGit(args) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) },
            )
        }
        gitChip(t("Status", "상태"), "status --short --branch")
        gitChip(t("Log", "로그"), "log --oneline -15")
        gitChip(t("Diff", "변경"), "diff --stat")
        gitChip(t("Branch", "브랜치"), "branch --show-current")
        column.addView(actions)
        gitOutput = TextView(this).apply {
            setTextColor(p.text)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setTextIsSelectable(true)
            text = t("Run a git command to see output.", "git 명령을 실행하면 결과가 표시됩니다.")
        }
        column.addView(
            ScrollView(this).apply { addView(gitOutput) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return column
    }

    private fun runGit(args: String) {
        val dir = projectDir ?: return
        if (!::gitOutput.isInitialized) return
        gitOutput.text = "git $args\n" + t("Running…", "실행 중…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val env = runCatching { ShellEnvironment.buildToolEnvironment(this@AiWorkspaceActivity, dir) }.getOrDefault(emptyMap())
                val lines = mutableListOf<String>()
                val run = runCatching {
                    ShellRunner.runSh(this@AiWorkspaceActivity, "git $args", dir, env, 60_000) { line -> lines.add(line) }
                }.getOrNull()
                (lines.joinToString("\n")) to (run?.exitCode ?: -1)
            }
            val (output, exit) = result
            gitOutput.text = "git $args\n" +
                (output.ifBlank { t("(no output)", "(출력 없음)") }) +
                "\n\n[${t("exit", "종료")} $exit]"
        }
    }

    // ----------------------------------------------------- build (실행 / 정리)

    private fun runGradleBuild() {
        val dir = projectDir
        if (dir == null) {
            Toast.makeText(this, t("No project is open.", "열린 프로젝트가 없습니다."), Toast.LENGTH_SHORT).show()
            return
        }
        val gradlew = File(dir, "gradlew").isFile
        runBuildCommand(if (gradlew) "./gradlew assembleDebug" else "gradle assembleDebug")
    }

    private fun runGradleClean() {
        val dir = projectDir
        if (dir == null) {
            Toast.makeText(this, t("No project is open.", "열린 프로젝트가 없습니다."), Toast.LENGTH_SHORT).show()
            return
        }
        val gradlew = File(dir, "gradlew").isFile
        runBuildCommand(if (gradlew) "./gradlew clean" else "gradle clean")
    }

    private fun runBuildCommand(command: String) {
        showTab(Tab.TERMINAL)
        if (!::terminalInput.isInitialized) return
        terminalInput.setText(command)
        runTerminalCommand()
    }

    // ------------------------------------------------------------- Tools tab

    private fun buildToolsTab(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        column.addView(sectionTitle(t("Available tools", "사용 가능한 도구")))
        val names = runCatching { engine.toolNames() }.getOrDefault(emptyList()).sorted()
        if (names.isEmpty()) {
            column.addView(body(t("No tools registered.", "등록된 도구가 없습니다.")))
        } else {
            for (name in names) {
                val readOnly = name in READ_ONLY_HINT
                column.addView(TextView(this).apply {
                    text = (if (readOnly) "👁 " else "⚙ ") + name +
                        (if (readOnly) t("  (read-only)", "  (읽기 전용)") else "") + "  ›"
                    setTextColor(if (readOnly) StudioUi.palette.info else StudioUi.palette.text)
                    textSize = 13f
                    setPadding(0, dp(5), 0, dp(5))
                    setOnClickListener { showToolHelp(name) }
                })
            }
        }
        column.addView(body(
            t(
                "Tap a tool for its full description and parameter schema. Destructive actions require approval. " +
                    "Read-only tools may run in parallel when already permitted.",
                "도구를 탭하면 전체 설명과 매개변수 스키마가 표시됩니다. 파괴적 작업은 승인이 필요하며, " +
                    "이미 허용된 읽기 전용 도구는 병렬 실행될 수 있습니다.",
            ),
        ))
        return ScrollView(this).apply { addView(column) }
    }

    // --------------------------------------------------------------- Build tab

    private lateinit var buildStatus: TextView
    private lateinit var buildSummary: TextView
    private lateinit var buildOutput: TextView
    private lateinit var buildScroll: ScrollView
    private lateinit var buildTaskField: EditText
    private lateinit var buildRunButton: Button
    private lateinit var buildStopButton: TextView
    private var buildFix: TextView? = null
    @Volatile private var buildProcess: Process? = null
    private var buildJob: Job? = null
    private val buildLines = java.util.ArrayDeque<String>()
    private var buildPainted = 0
    private var buildPaintQueued = false
    private var buildAutoScroll = true
    private var buildRunning = false
    @Volatile private var buildStopped = false
    private var buildLastTask = "assembleDebug"
    private var buildLastStatus = ""
    private var buildDiagnostics: List<String> = emptyList()

    /**
     * Dedicated Gradle screen: task presets, an editable task line, a live log
     * and a one-tap hand-off of the captured errors to the agent.
     *
     * The run is a real `./gradlew` process, not an emulation: its stdout and
     * stderr are read line by line on an IO thread, painted on the UI thread at
     * a bounded rate, and the process is destroyed by Stop or by leaving the
     * activity. Output is capped so a long build cannot freeze the workspace.
     */
    private fun buildBuildTab(): View {
        val p = StudioUi.palette
        val dir = projectDir
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(4))
        }
        head.addView(sectionTitle(t("Gradle build", "Gradle 빌드")))
        head.addView(TextView(this).apply {
            text = dir?.absolutePath ?: t("No project is open.", "열린 프로젝트가 없습니다.")
            setTextColor(p.muted)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        })
        if (dir == null) {
            column.addView(head)
            column.addView(
                AiUiKit.emptyState(this, "🔨", t("Open a project to build.", "빌드할 프로젝트를 여세요.")),
            )
            return column
        }
        val hasWrapper = File(dir, "gradlew").isFile
        head.addView(TextView(this).apply {
            text = if (hasWrapper) {
                t("wrapper: ./gradlew (project version)", "래퍼: ./gradlew (프로젝트 버전)")
            } else {
                t("wrapper: missing - a `gradle` on PATH will be used", "래퍼: 없음 - PATH의 `gradle`을 사용합니다")
            }
            setTextColor(if (hasWrapper) p.success else p.warning)
            textSize = 11f
        })

        // Presets fill the field and start immediately; the field stays
        // editable, so flavours and extra flags do not need a new screen.
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(2))
        }
        for (task in listOf("assembleDebug", "installDebug", "testDebugUnitTest", "lint", "clean")) {
            chips.addView(
                AiUiKit.actionChip(this, task) { _ ->
                    buildTaskField.setText(task)
                    startGradleBuild(task, dir)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) },
            )
        }
        head.addView(HorizontalScrollView(this).apply { addView(chips) })

        val runRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(2))
        }
        buildTaskField = EditText(this).apply {
            setText(buildLastTask)
            hint = t("tasks and flags, e.g. assembleRelease -x lint", "작업과 플래그, 예: assembleRelease -x lint")
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            maxLines = 1
            background = AiUiKit.rounded(p.surface2, dp(8), strokeColor = p.border, strokePx = dp(1))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isEnabled = !buildRunning
        }
        runRow.addView(
            buildTaskField,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
        )
        buildRunButton = AiUiKit.primaryButton(this, t("Run", "실행")) { _ ->
            startGradleBuild(buildTaskField.text.toString(), dir)
        }
        buildRunButton.isEnabled = !buildRunning
        runRow.addView(buildRunButton)
        buildStopButton = AiUiKit.actionChip(this, t("Stop", "중지")) { _ -> stopGradleBuild() }
        buildStopButton.isEnabled = buildRunning
        runRow.addView(
            buildStopButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(6) },
        )
        head.addView(runRow)

        buildStatus = TextView(this).apply {
            text = buildLastStatus.ifEmpty { t("Idle", "대기 중") }
            setTextColor(if (buildRunning) p.warning else p.textSoft)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 2)
        }
        head.addView(buildStatus)
        buildFix = AiUiKit.actionChip(this, t("Fix with AI", "AI로 수정")) { _ -> fixBuildWithAi() }
        buildFix?.visibility = if (buildDiagnostics.isEmpty()) View.GONE else View.VISIBLE
        head.addView(buildFix)
        column.addView(head)

        buildSummary = TextView(this).apply {
            setTextColor(p.error)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(12), dp(2), dp(12), dp(4))
            text = buildDiagnostics.take(8).joinToString("\n")
            visibility = if (buildDiagnostics.isEmpty()) View.GONE else View.VISIBLE
        }
        column.addView(buildSummary)

        buildOutput = TextView(this).apply {
            setTextColor(p.textSoft)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(6), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        buildScroll = ScrollView(this).apply {
            addView(buildOutput)
            setOnScrollChangeListener { _, _, _, _, _ -> buildAutoScroll = isBuildAtBottom() }
        }
        column.addView(buildScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildPainted = 0
        paintBuildOutput(force = true)
        return column
    }

    private fun isBuildAtBottom(): Boolean {
        if (!::buildScroll.isInitialized) return true
        val child = buildScroll.getChildAt(0) ?: return true
        return buildScroll.scrollY + buildScroll.height >= child.height - dp(24)
    }

    private fun startGradleBuild(rawTask: String, dir: File) {
        if (buildRunning) {
            Toast.makeText(this, t("A build is already running.", "이미 빌드가 실행 중입니다."), Toast.LENGTH_SHORT).show()
            return
        }
        val task = rawTask.trim()
        if (task.isEmpty()) {
            Toast.makeText(this, t("Enter a Gradle task.", "Gradle 작업을 입력하세요."), Toast.LENGTH_SHORT).show()
            return
        }
        buildLastTask = task
        val command = (if (File(dir, "gradlew").isFile) "sh ./gradlew " else "gradle ") + task
        buildRunning = true
        buildStopped = false
        buildLines.clear()
        buildDiagnostics = emptyList()
        buildPainted = 0
        buildLastStatus = t("Building… $command", "빌드 중… $command")
        buildAutoScroll = true
        if (::buildOutput.isInitialized) buildOutput.text = ""
        if (::buildSummary.isInitialized) {
            buildSummary.text = ""
            buildSummary.visibility = View.GONE
        }
        buildFix?.visibility = View.GONE
        if (::buildTaskField.isInitialized) buildTaskField.isEnabled = false
        if (::buildRunButton.isInitialized) buildRunButton.isEnabled = false
        if (::buildStopButton.isInitialized) buildStopButton.isEnabled = true
        if (::buildStatus.isInitialized) {
            buildStatus.text = buildLastStatus
            buildStatus.setTextColor(StudioUi.palette.warning)
        }
        appendBuildLine("$ $command")
        val started = System.currentTimeMillis()
        buildJob = scope.launch(Dispatchers.IO) {
            var exit = -1
            var failure: String? = null
            try {
                val env = runCatching {
                    ShellEnvironment.buildToolEnvironment(this@AiWorkspaceActivity, dir)
                }.getOrDefault(emptyMap())
                val process = ProcessBuilder("/system/bin/sh", "-c", command)
                    .directory(dir)
                    .redirectErrorStream(true)
                    .apply { environment().putAll(env) }
                    .start()
                buildProcess = process
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (buildStopped) break
                            appendBuildLine(line)
                        }
                    }
                } finally {
                    buildProcess = null
                    if (buildStopped) runCatching { process.destroy() }
                }
                if (!buildStopped) exit = process.waitFor()
            } catch (cancel: CancellationException) {
                runCatching { buildProcess?.destroy() }
                throw cancel
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            }
            val seconds = (System.currentTimeMillis() - started) / 1000.0
            withContext(Dispatchers.Main) { finishGradleBuild(exit, seconds, failure) }
        }
    }

    private fun stopGradleBuild() {
        if (!buildRunning) return
        buildStopped = true
        if (::buildStatus.isInitialized) {
            buildStatus.text = t("Stopping…", "중지 중…")
            buildStatus.setTextColor(StudioUi.palette.warning)
        }
        val process = buildProcess
        if (process != null) {
            runCatching { process.destroy() }
            // Gradle installs its own shutdown hooks; give them a moment, then
            // make sure the process is gone rather than leaving it orphaned.
            handler.postDelayed({ runCatching { process.destroyForcibly() } }, 2_000)
        } else {
            buildJob?.cancel()
            finishGradleBuild(-1, 0.0, null)
        }
    }

    /**
     * Queues one log line. Repainting is throttled: a Gradle build can emit
     * thousands of lines, and appending each one directly stalls the UI thread.
     */
    private fun appendBuildLine(line: String) {
        // Gradle can colour its log; escape sequences in a TextView render as
        // noise, so strip them before the line is kept.
        val text = line.trimEnd().replace(ANSI_ESCAPE, "")
        synchronized(buildLines) {
            if (buildLines.size >= BUILD_MAX_LINES) buildLines.removeFirst()
            buildLines.addLast(text)
        }
        if (buildPaintQueued) return
        buildPaintQueued = true
        handler.postDelayed({
            buildPaintQueued = false
            if (!isFinishing && !isDestroyed) paintBuildOutput()
        }, 120)
    }

    private fun paintBuildOutput(force: Boolean = false) {
        if (!::buildOutput.isInitialized) return
        val snapshot = synchronized(buildLines) { buildLines.toList() }
        val atBottom = buildAutoScroll || isBuildAtBottom()
        if (force || buildPainted == 0 || buildPainted > snapshot.size || buildOutput.length() > 400_000) {
            buildOutput.text = snapshot.joinToString("\n")
        } else if (snapshot.size > buildPainted) {
            buildOutput.append("\n" + snapshot.subList(buildPainted, snapshot.size).joinToString("\n"))
        }
        buildPainted = snapshot.size
        if (force || atBottom) buildScroll.post { buildScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun finishGradleBuild(exit: Int, seconds: Double, failure: String?) {
        // A stop can race the natural end of the process; only the first
        // result wins, so the log never shows "stopped" and "failed" together.
        if (!buildRunning) return
        buildRunning = false
        paintBuildOutput(force = true)
        val p = StudioUi.palette
        val stopped = buildStopped
        val ok = !stopped && failure == null && exit == 0
        val lines = synchronized(buildLines) { buildLines.toList() }
        buildDiagnostics = GradleOutput.diagnostics(lines, 40)
        val secs = String.format(Locale.US, "%.1f", seconds)
        buildLastStatus = when {
            stopped -> t("Stopped after ${secs}s", "${secs}초 후 중지됨")
            failure != null -> t("Could not start Gradle: $failure", "Gradle 실행 실패: $failure")
            ok -> t("Build succeeded in ${secs}s", "빌드 성공 (${secs}초)")
            else -> t("Build failed (exit $exit) in ${secs}s", "빌드 실패 (종료 $exit, ${secs}초)")
        }
        if (::buildStatus.isInitialized) {
            buildStatus.text = buildLastStatus
            buildStatus.setTextColor(
                when {
                    stopped -> p.warning
                    ok -> p.success
                    else -> p.error
                },
            )
        }
        if (::buildTaskField.isInitialized) buildTaskField.isEnabled = true
        if (::buildRunButton.isInitialized) buildRunButton.isEnabled = true
        if (::buildStopButton.isInitialized) buildStopButton.isEnabled = false
        if (::buildSummary.isInitialized) {
            val shown = buildDiagnostics.take(8)
            buildSummary.text = shown.joinToString("\n")
            buildSummary.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        }
        buildFix?.visibility = if (buildDiagnostics.isEmpty()) View.GONE else View.VISIBLE
        if (!stopped) {
            Toast.makeText(
                this,
                if (ok) t("Build succeeded", "빌드 성공") else t("Build failed", "빌드 실패"),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * Hands the captured failures to the agent, with the project and command
     * that produced them. The model is told to fix and verify, not to narrate:
     * a repair that stops at a description has not repaired anything.
     */
    private fun fixBuildWithAi() {
        val dir = projectDir ?: return
        val diagnostics = buildDiagnostics
        if (diagnostics.isEmpty()) {
            Toast.makeText(this, t("No Gradle errors were captured.", "수집된 Gradle 오류가 없습니다."), Toast.LENGTH_SHORT).show()
            return
        }
        showTab(Tab.CHAT)
        engine.sendMessage(
            buildString {
                append("The Gradle build failed.\n\n")
                append("Project: ").append(dir.absolutePath).append('\n')
                append("Command: sh ./gradlew ").append(buildLastTask).append("\n\n")
                append("Errors from the build log:\n```\n")
                append(diagnostics.take(30).joinToString("\n"))
                append("\n```\n\n")
                append("Find the cause, fix the code, then run gradle_build to verify. Do not just describe the fix.")
            },
        )
    }

    // --------------------------------------------------------- Resources tab

    private fun buildResourcesTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        column.addView(sectionTitle(t("Resource monitor", "자원 모니터")))
        val mem = AndroidMemoryProbe.snapshot(this)
        val rm = ResourceManager()
        val decision = rm.decide(mem, pendingTasks = 4, loadedLocalModels = 1, buildRunning = false, userMax = 4)

        fun row(label: String, value: String, color: Int = p.text) {
            column.addView(TextView(this).apply {
                text = "$label: $value"
                setTextColor(color)
                textSize = 13f
                setPadding(0, dp(5), 0, dp(5))
            })
        }
        row(t("Current model", "현재 모델"), engine.currentModelLabel(), p.accent)
        row(t("Free memory", "여유 메모리"), "${mem.availableMb} MB")
        row(t("Heap used", "힙 사용"), "${(mem.heapUsedFraction * 100).toInt()}%")
        row(t("Low memory", "저메모리"), mem.lowMemory.toString(), if (mem.lowMemory) p.error else p.success)
        row(t("Parallel tasks (estimate)", "병렬 작업(추정)"), "${decision.count} — ${decision.reason}")
        val canLoad = rm.canLoadModel(mem, alreadyLoaded = 0)
        row(
            t("Can load a model", "모델 로드 가능"),
            (if (canLoad.first) "yes" else "no") + " (" + canLoad.second + ")",
            if (canLoad.first) p.success else p.warning,
        )
        row(t("Build with inference", "빌드+추론 동시"), rm.canRunBuildWithInference(mem, inferenceActive = true).toString())
        column.addView(body(
            t(
                "Memory values are measured from Android; model memory and parallelism are estimates.",
                "메모리 값은 Android에서 측정되며, 모델 메모리와 병렬성은 추정치입니다."
            ),
        ))
        return ScrollView(this).apply { addView(column) }
    }

    // ----------------------------------------------------------- Sessions tab

    private fun buildSessionsTab(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        column.addView(sectionTitle(t("Sessions", "세션")))
        column.addView(AiUiKit.primaryButton(this, t("+ New session", "+ 새 세션")) {
            newChat()
            if (projectDir != null) scheduleValidation(immediate = true)
        })
        val search = EditText(this).apply {
            hint = t("Search sessions…", "세션 검색…")
            setHintTextColor(StudioUi.palette.muted)
            setTextColor(StudioUi.palette.text)
            textSize = 13f
            maxLines = 1
            setText(sessionQuery)
            background = AiUiKit.rounded(StudioUi.palette.surface2, dp(10), strokeColor = StudioUi.palette.border, strokePx = dp(1))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    sessionQuery = s?.toString().orEmpty()
                    populateSessions()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        column.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(8), 0, dp(6)) })
        sessionListContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            ScrollView(this).apply { addView(sessionListContainer) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        populateSessions()
        return column
    }

    private lateinit var sessionListContainer: LinearLayout
    private var sessionQuery: String = ""

    private fun populateSessions() {
        sessionListContainer.removeAllViews()
        val all = engine.listSessions(includeArchived = true)
        val sessions = if (sessionQuery.isBlank()) all
        else all.filter { it.title.contains(sessionQuery, ignoreCase = true) }
        if (sessions.isEmpty()) {
            sessionListContainer.addView(AiUiKit.emptyState(this, "💬", t("No sessions.", "세션이 없습니다.")))
            return
        }
        val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        for (s in sessions) {
            val current = s.id == engine.currentSessionId()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { openSession(s) }
            }
            row.addView(TextView(this).apply {
                text = (if (current) "● " else "") + s.title
                setTextColor(if (current) StudioUi.palette.accent else StudioUi.palette.text)
                textSize = StudioUi.TypeBody
            })
            row.addView(TextView(this).apply {
                text = "${s.agent} · ${fmt.format(Date(s.timeUpdated))}" + if (s.archived) " · archived" else ""
                setTextColor(StudioUi.palette.textSoft)
                textSize = 11f
            })
            row.setOnLongClickListener { showSessionActions(s); true }
            sessionListContainer.addView(row)
        }
    }

    private fun openSession(s: OcSessionStore.Session) {
        engine.loadSession(s.id)
        changedFiles.clear()
        validation.reset()
        if (projectDir != null) scheduleValidation(immediate = true)
        renderSessionHistory()
        showTab(Tab.CHAT)
    }

    private fun showSessionActions(s: OcSessionStore.Session) {
        val actions = arrayOf(
            t("Rename", "이름 변경"),
            t("Fork", "분기"),
            if (s.archived) t("Unarchive", "보관 해제") else t("Archive", "보관"),
            t("Delete", "삭제"),
        )
        themedDialog()
            .setTitle(s.title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> promptRename(s)
                    1 -> { engine.forkSession(s.id)?.let { engine.loadSession(it) }; renderSessionHistory(); showTab(Tab.CHAT) }
                    2 -> { engine.archiveSession(s.id, !s.archived); showTab(Tab.SESSIONS) }
                    3 -> themedDialog()
                        .setMessage(t("Delete this session?", "이 세션을 삭제할까요?"))
                        .setPositiveButton(t("Delete", "삭제")) { _, _ -> engine.deleteSession(s.id); showTab(Tab.SESSIONS) }
                        .setNegativeButton(t("Cancel", "취소"), null)
                        .show()
                }
            }
            .show()
    }

    private fun promptRename(s: OcSessionStore.Session) {
        val et = EditText(this).apply { setText(s.title) }
        themedDialog()
            .setTitle(t("Rename session", "세션 이름 변경"))
            .setView(et)
            .setPositiveButton("OK") { _, _ ->
                engine.renameSession(s.id, et.text.toString().ifBlank { s.title })
                showTab(Tab.SESSIONS)
            }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    // ------------------------------------------------------------- Models tab

    // ------------------------------------------------------------ Models tab
    //
    // One unified picker for everything the app can run: configured API
    // models, installed GGUF files, and real Hugging Face download candidates.
    // Sizes, quant labels, checksums and licences are the values the sources
    // reported; unknown values are shown as unknown, never guessed.

    private val pickerFamilies = listOf(QuantFamily.Q4, QuantFamily.Q5, QuantFamily.Q6, QuantFamily.Q8)

    private fun buildModelsTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        column.addView(sectionTitle(t("Models", "모델")))
        column.addView(TextView(this).apply {
            text = t("Current", "현재") + ": " + engine.currentModelLabel()
            setTextColor(p.accent)
            textSize = 13f
            setPadding(0, 0, 0, dp(6))
        })
        column.addView(TextView(this).apply {
            text = t(
                "Reasoning effort — only sent to models that support it",
                "추론 강도 — 지원하는 모델에만 전달됩니다",
            )
            setTextColor(p.textSoft)
            textSize = 12f
        })
        pickerReasoningRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickerReasoningRow?.let { column.addView(hScroll(it)) }
        renderReasoningChips()

        pickerTabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pickerTabsRow?.let { column.addView(hScroll(it)) }
        renderPickerTabs()

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val search = EditText(this).apply {
            hint = t("Search models or providers…", "모델·제공자 검색…")
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 13f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(pickerQuery)
            background = AiUiKit.rounded(p.surface2, dp(10), strokeColor = p.border, strokePx = dp(1))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    pickerQuery = s?.toString().orEmpty()
                    renderModelList()
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        pickerSearchField = search
        searchRow.addView(search, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchRow.addView(
            AiUiKit.actionChip(this, t("HF search", "HF 검색")) { _ -> searchRemoteModels() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(6) },
        )
        column.addView(searchRow)
        column.addView(buildGgufDiscoveryCard())

        pickerFilterHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pickerFilterHost?.let { column.addView(it) }
        renderPickerFilters()

        pickerList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pickerList?.let { column.addView(it) }

        refreshKnownModels()
        renderModelList()
        ensureDownloadObserver()
        // If the Models screen was rebuilt while the download tab was active,
        // keep its browse list useful instead of showing an empty tab.
        if (pickerTab == ModelTab.DOWNLOAD) ensurePopularGgufs()
        return ScrollView(this).apply { addView(column) }
    }

    /**
     * Discovery card for the GGUF download path. The chips are search
     * shortcuts, not baked-in model URLs: the list below always comes from the
     * Hugging Face API and therefore contains real files, sizes and checksums.
     */
    private fun buildGgufDiscoveryCard(): View {
        val p = StudioUi.palette
        val card = AiUiKit.card(this, radiusDp = 12, color = p.surface, elevated = false).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { setMargins(0, dp(6), 0, dp(4)) }
        }
        card.addView(TextView(this).apply {
            text = t("GGUF downloads", "GGUF 다운로드")
            setTextColor(p.text)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(TextView(this).apply {
            text = t(
                "Browse popular models or pick a family — Qwen, DeepSeek, Llama and more. Any owner/repo works too. Complete split GGUF sets are supported; projectors and adapters are skipped.",
                "인기 모델을 둘러보거나 계열을 고르세요 — Qwen, DeepSeek, Llama 등. owner/repo를 직접 입력해도 됩니다. 완전한 분할 GGUF 세트를 지원하며 프로젝터·어댑터는 제외합니다.",
            )
            setTextColor(p.textSoft)
            textSize = 11f
            setPadding(0, dp(2), 0, dp(6))
        })
        val families = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (suggestion in GgufCatalog.suggestions) {
            addChip(families, AiUiKit.actionChip(this, t(suggestion.labelEn, suggestion.labelKo)) { _ ->
                onGgufSuggestion(suggestion)
            })
        }
        card.addView(hScroll(families))
        val extra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addChip(extra, AiUiKit.actionChip(this, t("Repo / URL", "Repo / URL")) { _ -> promptHuggingFaceRepo() })
        addChip(extra, AiUiKit.actionChip(this, t("Popular refresh", "인기 새로고침")) { _ -> loadPopularGgufs(force = true) })
        card.addView(hScroll(extra))
        return card
    }

    private fun onGgufSuggestion(suggestion: GgufSuggestion) {
        if (pickerSearching) return
        pickerTab = ModelTab.DOWNLOAD
        pickerPopularAttempted = true
        pickerQuery = suggestion.query
        pickerSearchField?.setText(suggestion.query)
        renderPickerTabs()
        if (suggestion.popular) {
            searchRemoteModels(query = "", popular = true)
        } else {
            searchRemoteModels(
                query = suggestion.query,
                popular = false,
                clearQueryOnSuccess = true,
            )
        }
    }

    private fun ensurePopularGgufs() {
        if (pickerPopularAttempted || pickerSearching || pickerRemote.isNotEmpty()) return
        pickerPopularAttempted = true
        searchRemoteModels(query = "", popular = true)
    }

    private fun loadPopularGgufs(force: Boolean = false) {
        if (pickerSearching) return
        if (!force && (pickerPopularAttempted || pickerRemote.isNotEmpty())) return
        pickerTab = ModelTab.DOWNLOAD
        pickerPopularAttempted = true
        pickerQuery = ""
        pickerSearchField?.setText("")
        renderPickerTabs()
        searchRemoteModels(query = "", popular = true)
    }

    // ---------------------------------------------------------- picker parts

    private fun renderReasoningChips() {
        val row = pickerReasoningRow ?: return
        row.removeAllViews()
        for (level in listOf("auto", "minimal", "low", "medium", "high")) {
            addChip(row, toggleChip(level, engine.reasoningEffort == level) { active ->
                engine.chooseReasoningEffort(if (active) level else "auto")
                renderReasoningChips()
            })
        }
    }

    private fun renderPickerTabs() {
        val row = pickerTabsRow ?: return
        row.removeAllViews()
        for (tab in ModelTab.values()) {
            val chip = TextView(this).apply {
                text = tab.label
                textSize = 12f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                paintChip(this, tab == pickerTab)
                setOnClickListener {
                    pickerTab = tab
                    renderPickerTabs()
                    renderModelList()
                    if (tab == ModelTab.DOWNLOAD) ensurePopularGgufs()
                }
            }
            addChip(row, chip)
        }
    }

    private fun renderPickerFilters() {
        val host = pickerFilterHost ?: return
        host.removeAllViews()

        val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addChip(rowA, toggleChip(t("All providers", "모든 제공자"), pickerProvider == null) { active ->
            if (active) {
                pickerProvider = null
                renderPickerFilters()
            }
        })
        for ((id, label) in runCatching { engine.listProviders() }.getOrDefault(emptyList())) {
            addChip(rowA, toggleChip(label, pickerProvider == id) { active ->
                pickerProvider = if (active) id else null
                renderPickerFilters()
            })
        }
        addChip(rowA, toggleChip(t("Installed", "설치됨"), pickerInstalledOnly) { pickerInstalledOnly = it })
        host.addView(hScroll(rowA))

        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (family in pickerFamilies) {
            addChip(rowB, toggleChip(family.label, family in pickerQuantFamilies) { active ->
                if (active) pickerQuantFamilies.add(family) else pickerQuantFamilies.remove(family)
            })
        }
        for (band in ParamBand.values()) {
            addChip(rowB, toggleChip(band.label, band in pickerBands) { active ->
                if (active) pickerBands.add(band) else pickerBands.remove(band)
            })
        }
        val sortChip = TextView(this).apply {
            text = sortChipLabel()
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            paintChip(this, true)
            setOnClickListener {
                pickerSort = when (pickerSort) {
                    ModelSort.NAME -> ModelSort.SIZE
                    ModelSort.SIZE -> ModelSort.DOWNLOADS
                    ModelSort.DOWNLOADS -> ModelSort.NAME
                }
                text = sortChipLabel()
                renderModelList()
            }
        }
        addChip(rowB, sortChip)
        addChip(rowB, AiUiKit.actionChip(this, t("Reset filters", "필터 초기화")) { _ -> resetPickerFilters() })
        host.addView(hScroll(rowB))
    }

    private fun sortChipLabel(): String = t("Sort", "정렬") + ": " + when (pickerSort) {
        ModelSort.NAME -> t("name", "이름")
        ModelSort.SIZE -> t("size", "크기")
        ModelSort.DOWNLOADS -> t("popular", "인기")
    }

    private fun resetPickerFilters() {
        pickerProvider = null
        pickerQuantFamilies.clear()
        pickerBands.clear()
        pickerInstalledOnly = false
        pickerQuery = ""
        pickerSort = ModelSort.NAME
        pickerMessage = null
        pickerSearchField?.setText("")
        renderPickerFilters()
        renderModelList()
    }

    private fun refreshKnownModels() {
        pickerKnown = runCatching { modelCatalog.knownEntries() }.getOrDefault(emptyList())
    }

    private fun renderModelList() {
        val host = pickerList ?: return
        host.removeAllViews()
        downloadProgressBars.clear()
        downloadProgressTexts.clear()

        if (pickerTab == ModelTab.DOWNLOADING) {
            val tasks = modelDownloads.tasks.value
            if (tasks.isEmpty()) {
                host.addView(emptyState(t("No downloads yet.", "다운로드 기록이 없습니다.")))
            } else {
                for (task in tasks.sortedByDescending { it.updatedAtMs }) host.addView(downloadRow(task))
            }
            return
        }

        val downloading = modelDownloads.tasks.value.filter { it.isActive }.map { it.id }.toSet()
        val entries = (pickerKnown + pickerRemote).distinctBy { it.uid }
        val filter = ModelFilter(
            tab = pickerTab,
            query = pickerQuery,
            providerId = pickerProvider,
            quantFamilies = pickerQuantFamilies.toSet(),
            paramBands = pickerBands.toSet(),
            installedOnly = pickerInstalledOnly,
        )
        val shown = ModelQuery.apply(entries, filter, pickerSort, downloading)

        if (pickerSearching) host.addView(messageCard(t("Searching Hugging Face…", "Hugging Face 검색 중…")))
        pickerMessage?.let { host.addView(messageCard(it)) }
        if (pickerTab == ModelTab.DOWNLOAD && pickerRemote.isNotEmpty()) {
            host.addView(sectionTitle(t("Downloadable GGUF", "다운로드 가능한 GGUF")))
        }
        if (shown.isEmpty() && !pickerSearching && pickerMessage == null) {
            host.addView(emptyState(pickerEmptyText(filter)))
        }
        for (entry in shown) host.addView(modelRow(entry))

        val tasks = modelDownloads.tasks.value
        if (tasks.isNotEmpty()) {
            host.addView(sectionTitle(t("Downloads", "다운로드")))
            for (task in tasks.sortedByDescending { it.updatedAtMs }) host.addView(downloadRow(task))
            host.addView(TextView(this).apply {
                text = t(
                    "Downloads keep running while the app is in the background (with a notification). " +
                        "If the app is force-stopped they pause and can be resumed later.",
                    "앱이 백그라운드에 있는 동안 다운로드가 계속됩니다(알림 표시). " +
                        "앱을 강제 종료하면 다운로드가 일시정지되며 나중에 이어받기로 재개할 수 있습니다.",
                )
                setTextColor(StudioUi.palette.muted)
                textSize = 11f
            })
        }
    }

    private fun pickerEmptyText(filter: ModelFilter): String {
        if (filter.tab == ModelTab.DOWNLOAD) {
            return t(
                "No downloadable models yet. Tap ‘Popular’ or a family above, or search by name.",
                "아직 다운로드할 모델이 없습니다. 위의 ‘인기’ 또는 계열을 누르거나 이름으로 검색하세요.",
            )
        }
        return ModelQuery.emptyReason(filter)
    }

    private fun modelRow(entry: ModelEntry): View {
        val p = StudioUi.palette
        val current = entry.modelRef != null && entry.modelRef == engine.modelRef
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = AiUiKit.rounded(
                if (current) AiUiKit.withAlpha(p.accent, 0.12f) else p.surface,
                dp(12),
                strokeColor = if (current) p.accent else p.border,
                strokePx = dp(1),
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = (if (current) "✓ " else "") + entry.displayName
            setTextColor(if (current) p.accent else p.text)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(this).apply {
            text = when {
                entry.source == ModelSource.HF_REMOTE -> t("download", "다운로드")
                entry.runtime == com.aide.mobilestudio.ai.model.ModelRuntime.API -> "API"
                else -> t("local", "로컬")
            }
            setTextColor(p.muted)
            textSize = 11f
        })
        card.addView(titleRow)

        val meta = listOfNotNull(
            entry.providerLabel,
            entry.repoId?.takeIf { entry.source == ModelSource.HF_REMOTE },
            entry.paramsLabel.takeIf { it.isNotBlank() },
            entry.quant,
            entry.parts.count().takeIf { it > 1 }?.let { t("$it shards", "샤드 ${it}개") },
            entry.fileSizeBytes?.let { DownloadEngine.humanBytes(it) },
            entry.contextTokens?.let { "ctx $it" },
            entry.license?.let { "license: $it" },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            card.addView(TextView(this).apply {
                text = meta
                setTextColor(p.textSoft)
                textSize = 12f
            })
        }
        entry.note?.let { note ->
            card.addView(TextView(this).apply {
                text = note
                setTextColor(p.warning)
                textSize = 11f
            })
        }
        val hasChecksum = entry.sha256 != null || entry.parts.any { it.sha256 != null }
        if (entry.source == ModelSource.HF_REMOTE && !hasChecksum) {
            card.addView(TextView(this).apply {
                text = t("no checksum provided — will be marked unverified", "체크섬 미제공 — 검증 안 됨으로 표시")
                setTextColor(p.muted)
                textSize = 11f
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        when {
            entry.source == ModelSource.HF_REMOTE -> addChip(
                actions,
                AiUiKit.actionChip(this, t("Download", "다운로드")) { _ -> openQuantDialog(entry) },
            )
            entry.modelRef != null -> addChip(
                actions,
                AiUiKit.actionChip(
                    this,
                    if (current) t("In use", "사용 중") else t("Use", "사용"),
                ) { _ -> selectModelEntry(entry) },
            )
        }
        // Live /models listing for providers that expose it.
        if (entry.runtime == com.aide.mobilestudio.ai.model.ModelRuntime.API && entry.providerId != null) {
            addChip(
                actions,
                AiUiKit.actionChip(this, t("Remote list", "원격 목록")) { _ -> showLiveModels(entry.providerId!!) },
            )
        }
        addChip(actions, AiUiKit.actionChip(this, t("Details", "상세")) { _ -> showModelDetails(entry) })
        if (entry.source == ModelSource.LOCAL_INSTALLED && entry.installed && entry.localPath != null) {
            addChip(actions, AiUiKit.actionChip(this, t("Delete", "삭제")) { _ -> confirmDeleteModel(entry) })
        }
        card.addView(actions)

        if (entry.source != ModelSource.HF_REMOTE && entry.modelRef != null) {
            card.setOnClickListener { selectModelEntry(entry) }
        }
        return card
    }

    // ------------------------------------------------------------- downloads

    private fun ensureDownloadObserver() {
        if (downloadObserverStarted) return
        downloadObserverStarted = true
        modelDownloads.onCompleted = { task ->
            val ok = modelCatalog.registerCompleted(task)
            runOnUiThread {
                refreshKnownModels()
                if (activeTab == Tab.MODELS) renderModelList()
                if (::chatMessages.isInitialized) {
                    val name = task.spec.displayName.ifBlank { task.finishedFile?.name ?: "model" }
                    addSystemBubble(
                        if (ok) "✓ $name " + t("downloaded and registered.", "다운로드 완료 · 모델로 등록되었습니다.")
                        else "⚠ $name " + t("downloaded, but registration failed.", "다운로드되었지만 모델 등록에 실패했습니다.")
                    )
                }
            }
        }
        scope.launch {
            modelDownloads.tasks.collect { tasks ->
                runOnUiThread {
                    if (activeTab != Tab.MODELS) return@runOnUiThread
                    val signature = tasks.joinToString("|") { "${it.id}:${it.state}" }
                    if (signature != downloadSignature) {
                        downloadSignature = signature
                        renderModelList()
                    } else {
                        updateDownloadProgress(tasks)
                    }
                }
            }
        }
    }

    private fun updateDownloadProgress(tasks: List<DownloadTask>) {
        for (task in tasks) {
            val bar = downloadProgressBars[task.id] ?: continue
            bar.isIndeterminate = task.totalBytes <= 0 && task.state == DownloadState.RUNNING
            bar.progress = (task.fraction * 1000).toInt()
            downloadProgressTexts[task.id]?.text = downloadStateLine(task)
        }
    }

    private fun downloadStateLine(task: DownloadTask): String {
        val bytes = if (task.totalBytes > 0) {
            "${DownloadEngine.humanBytes(task.progress)} / ${DownloadEngine.humanBytes(task.totalBytes)}"
        } else {
            DownloadEngine.humanBytes(task.progress)
        }
        val pct = if (task.totalBytes > 0) " (${(task.fraction * 100).toInt()}%)" else ""
        val parts = if (task.spec.parts.size > 1) {
            " · " + t("${task.spec.parts.size} parts", "샤드 ${task.spec.parts.size}개")
        } else {
            ""
        }
        val state = when (task.state) {
            DownloadState.QUEUED -> t("queued", "대기 중")
            DownloadState.RUNNING -> t("downloading", "다운로드 중")
            DownloadState.PAUSED -> t("paused — resume continues from the partial file", "일시정지 — 이어받기로 계속")
            DownloadState.CANCELLED -> t("cancelled", "취소됨")
            DownloadState.COMPLETED -> {
                val verified = if (task.checksumVerified) {
                    t("verified (SHA-256)", "검증됨 (SHA-256)")
                } else {
                    t("unverified (server sent no checksum)", "검증 안 됨 (서버가 체크섬을 제공하지 않음)")
                }
                t("completed", "완료") + " · $verified"
            }
            DownloadState.FAILED -> t("failed", "실패")
        }
        return "$state · $bytes$pct$parts"
    }

    private fun downloadRow(task: DownloadTask): View {
        val p = StudioUi.palette
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = AiUiKit.rounded(p.surface, dp(12), strokeColor = p.border, strokePx = dp(1))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, dp(4), 0, dp(4)) }
        }
        card.addView(TextView(this).apply {
            text = task.spec.displayName.ifBlank { task.finishedFile?.name ?: task.id }
            setTextColor(p.text)
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
        })
        val status = TextView(this).apply {
            text = downloadStateLine(task)
            setTextColor(p.textSoft)
            textSize = 12f
        }
        downloadProgressTexts[task.id] = status
        card.addView(status)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = (task.fraction * 1000).toInt()
            isIndeterminate = task.totalBytes <= 0 && task.state == DownloadState.RUNNING
        }
        downloadProgressBars[task.id] = bar
        card.addView(bar)
        task.lastError?.let { err ->
            card.addView(TextView(this).apply {
                text = err
                setTextColor(p.error)
                textSize = 12f
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        when (task.state) {
            DownloadState.QUEUED, DownloadState.RUNNING -> {
                addChip(actions, AiUiKit.actionChip(this, t("Pause", "일시정지")) { _ -> modelDownloads.pause(task.id) })
                addChip(actions, AiUiKit.actionChip(this, t("Cancel", "취소")) { _ -> modelDownloads.cancel(task.id) })
            }
            DownloadState.PAUSED -> {
                addChip(actions, AiUiKit.actionChip(this, t("Resume", "이어받기")) { _ ->
                    modelDownloads.resume(task.id)
                    runCatching { ModelDownloadService.start(this, task.id) }
                })
                addChip(actions, AiUiKit.actionChip(this, t("Cancel", "취소")) { _ -> modelDownloads.cancel(task.id) })
            }
            DownloadState.FAILED, DownloadState.CANCELLED -> {
                addChip(actions, AiUiKit.actionChip(this, t("Retry", "다시 시도")) { _ ->
                    modelDownloads.retry(task.id)
                    runCatching { ModelDownloadService.start(this, task.id) }
                })
                addChip(actions, AiUiKit.actionChip(this, t("Remove", "제거")) { _ -> modelDownloads.remove(task.id) })
            }
            DownloadState.COMPLETED -> {
                addChip(actions, AiUiKit.actionChip(this, t("Use model", "모델 사용")) { _ -> selectLocalFile(task) })
                addChip(actions, AiUiKit.actionChip(this, t("Remove", "제거")) { _ -> modelDownloads.remove(task.id) })
            }
        }
        card.addView(actions)
        return card
    }

    private fun searchRemoteModels(
        query: String = pickerQuery.trim(),
        popular: Boolean = false,
        clearQueryOnSuccess: Boolean = false,
    ) {
        if (query.isBlank() && !popular) {
            pickerMessage = t(
                "Type a model name to search Hugging Face, or tap a GGUF shortcut above.",
                "Hugging Face에서 검색할 모델 이름을 입력하거나 위의 GGUF 바로가기를 누르세요.",
            )
            renderModelList()
            return
        }
        if (pickerSearching) return
        if (popular) {
            // Browse is not a text filter: clear any leftover query so the
            // whole popular list is visible.
            pickerQuery = ""
            pickerSearchField?.setText("")
        }
        pickerSearching = true
        pickerPopularAttempted = true
        pickerMessage = null
        pickerRemote = emptyList()
        renderModelList()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (popular) modelCatalog.popularDownloadable() else modelCatalog.searchDownloadable(query)
            }
            pickerSearching = false
            when (result) {
                is ModelCatalog.SearchResult.Ok -> {
                    pickerRemote = result.entries
                    pickerMessage = if (result.entries.isEmpty()) {
                        if (popular) {
                            t(
                                "Hugging Face returned no complete GGUF models. Try another family or a repository URL.",
                                "Hugging Face가 완전한 GGUF 모델을 반환하지 않았습니다. 다른 계열이나 리포지토리 URL을 시도하세요.",
                            )
                        } else {
                            t("No GGUF repositories found for “$query”.", "‘$query’에 해당하는 GGUF 리포지토리를 찾지 못했습니다.")
                        }
                    } else {
                        null
                    }
                    pickerTab = ModelTab.DOWNLOAD
                    if (clearQueryOnSuccess) {
                        // The family chip searched the API, not the local list;
                        // an empty field prevents the server query from hiding
                        // results whose tags matched but whose id did not.
                        pickerQuery = ""
                        pickerSearchField?.setText("")
                    }
                    renderPickerTabs()
                }
                is ModelCatalog.SearchResult.Failure -> pickerMessage = result.message
            }
            renderModelList()
        }
    }

    /** Paste any Hugging Face GGUF repository instead of relying on search. */
    private fun promptHuggingFaceRepo() {
        val p = StudioUi.palette
        val field = EditText(this).apply {
            hint = "owner/repo"
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 13f
            setSingleLine()
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = AiUiKit.rounded(p.surface2, dp(10), strokeColor = p.border, strokePx = dp(1))
        }
        val wrapper = FrameLayout(this).apply {
            setPadding(dp(16), dp(4), dp(16), 0)
            addView(field)
        }
        themedDialog()
            .setTitle(t("Download from Hugging Face", "Hugging Face에서 다운로드"))
            .setMessage(
                t(
                    "Paste any Hugging Face GGUF repository ID or URL (for example unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF). The real file list is fetched, then you choose a quantisation; split sets are downloaded together.",
                    "Hugging Face GGUF 리포지토리 ID 또는 URL을 붙여넣으세요(예: unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF). 실제 파일 목록을 가져온 뒤 양자화를 선택하며, 분할 모델은 샤드를 함께 받습니다.",
                ),
            )
            .setView(wrapper)
            .setPositiveButton(t("Fetch files", "파일 불러오기")) { _, _ ->
                val raw = field.text.toString()
                val repo = HuggingFaceApi.parseRepoId(raw)
                if (repo == null) {
                    pickerTab = ModelTab.DOWNLOAD
                    pickerMessage = t(
                        "Enter owner/repo or a Hugging Face model URL.",
                        "owner/repo 또는 Hugging Face 모델 URL을 입력하세요.",
                    )
                    renderPickerTabs()
                    renderModelList()
                } else {
                    loadRepoModels(repo)
                }
            }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    private fun loadRepoModels(repo: String) {
        if (pickerSearching) return
        pickerSearching = true
        pickerPopularAttempted = true
        pickerMessage = null
        pickerRemote = emptyList()
        pickerQuery = ""
        pickerSearchField?.setText("")
        pickerTab = ModelTab.DOWNLOAD
        renderPickerTabs()
        renderModelList()
        scope.launch {
            val result = withContext(Dispatchers.IO) { modelCatalog.downloadableFromRepo(repo) }
            pickerSearching = false
            when (result) {
                is ModelCatalog.SearchResult.Ok -> {
                    pickerRemote = result.entries
                    pickerMessage = if (result.entries.isEmpty()) {
                        t(
                            "No complete GGUF models in $repo.",
                            "${repo}에 완전한 GGUF 모델이 없습니다.",
                        )
                    } else {
                        "$repo · " + t(
                            "${result.entries.size} models",
                            "${result.entries.size}개 모델",
                        )
                    }
                }
                is ModelCatalog.SearchResult.Failure -> pickerMessage = result.message
            }
            renderModelList()
        }
    }

    /** Quantisation choice over the *real* files the tree API returned. */
    private fun openQuantDialog(entry: ModelEntry) {
        val repo = entry.repoId
        if (repo == null) {
            beginDownload(entry)
            return
        }
        val files = pickerRemote.filter { it.repoId == repo }.sortedBy { it.fileSizeBytes ?: Long.MAX_VALUE }
        if (files.isEmpty()) {
            beginDownload(entry)
            return
        }
        val labels = files.map { f ->
            val size = f.fileSizeBytes?.let { DownloadEngine.humanBytes(it) } ?: "?"
            val checksum = if (f.sha256 != null || f.parts.any { it.sha256 != null }) {
                "SHA-256"
            } else {
                t("no checksum", "체크섬 없음")
            }
            val name = if (f.parts.size > 1) f.displayName else f.quant ?: f.fileName
            val parts = if (f.parts.size > 1) " · ${f.parts.size} parts" else ""
            "$name · $size$parts · $checksum"
        }.toTypedArray()
        themedDialog()
            .setTitle(t("Choose quantisation", "양자화 선택") + " · $repo")
            .setItems(labels) { _, which -> beginDownload(files[which]) }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    private fun beginDownload(entry: ModelEntry) {
        val spec = modelCatalog.downloadSpec(entry)
        if (spec == null) {
            addSystemBubble("⚠ " + t("This entry cannot be downloaded.", "다운로드할 수 없는 항목입니다."))
            return
        }
        val existing = modelDownloads.task(spec.id)
        if (existing != null && existing.state == DownloadState.COMPLETED) {
            Toast.makeText(this, t("Already downloaded", "이미 다운로드되었습니다"), Toast.LENGTH_SHORT).show()
            return
        }
        modelDownloads.start(spec)
        // The manager already runs the transfer in-process; the foreground
        // service keeps it alive in the background and can be refused by the
        // OS (Android 12+), which must not take the download down with it.
        runCatching { ModelDownloadService.start(this, spec.id) }
        pickerTab = ModelTab.DOWNLOADING
        renderPickerTabs()
        renderModelList()
        Toast.makeText(
            this,
            t("Download started: ", "다운로드 시작: ") + spec.displayName,
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun selectModelEntry(entry: ModelEntry) {
        entry.modelRef?.let { applyModelRef(it) }
    }

    /**
     * Switches model refs safely: if the new model cannot be created (missing
     * key, bad base URL) the previous one is restored and the reason shown.
     */
    private fun applyModelRef(ref: String) {
        val previous = engine.modelRef
        if (previous == ref) return
        engine.setModel(ref)
        if (engine.resolveModelInfo() == null) {
            // Switching must never strand the user on a model that cannot be
            // created: put the previous one back and say why.
            previous?.let { engine.setModel(it) }
            updateModelLabel()
            addSystemBubble(
                "⚠ " + t(
                    "Could not switch to $ref — keeping the previous model. Check the API key in Settings.",
                    "$ref (으)로 전환하지 못했습니다 — 이전 모델을 유지합니다. 설정에서 API 키를 확인하세요.",
                ),
            )
        } else {
            updateModelLabel()
            addSystemBubble("✓ " + engine.currentModelLabel())
        }
    }

    /** Live `GET /models` for an API provider; cached list when the refresh fails. */
    private fun showLiveModels(providerId: String) {
        Toast.makeText(
            this,
            t("Fetching models from $providerId…", "$providerId 모델 목록 가져오는 중…"),
            Toast.LENGTH_SHORT,
        ).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { modelCatalog.liveModels(providerId) }
            when (result) {
                is ModelCatalog.LiveResult.Ok -> {
                    val title = if (result.cached) {
                        t("Cached model list (refresh failed)", "캐시된 모델 목록 (갱신 실패)")
                    } else {
                        t("Models from the API", "API에서 받은 모델 목록")
                    }
                    val models = result.models
                    themedDialog()
                        .setTitle("$title · $providerId")
                        .setItems(models.toTypedArray()) { _, which ->
                            applyModelRef("$providerId/${models[which]}")
                        }
                        .setNeutralButton("OK", null)
                        .show()
                }
                is ModelCatalog.LiveResult.Failure -> addSystemBubble("⚠ " + result.message)
            }
        }
    }

    private fun selectLocalFile(task: DownloadTask) {
        val file = task.finishedFile ?: return
        val ref = "local/" + file.nameWithoutExtension
        val previous = engine.modelRef
        engine.setModel(ref)
        if (engine.resolveModelInfo() == null) {
            previous?.let { engine.setModel(it) }
            updateModelLabel()
            addSystemBubble("⚠ " + t("Could not switch to the downloaded model.", "다운로드한 모델로 전환하지 못했습니다."))
        } else {
            updateModelLabel()
            addSystemBubble("✓ " + file.name)
        }
    }

    private fun showModelDetails(entry: ModelEntry) {
        val lines = mutableListOf<String>()
        lines.add(t("Name", "이름") + ": " + entry.displayName)
        entry.modelRef?.let { lines.add(t("Ref", "참조") + ": $it") }
        entry.providerLabel?.let { lines.add(t("Provider", "공급자") + ": $it") }
        if (entry.paramsLabel.isNotBlank()) {
            val suffix = if (entry.paramsSource == ParamsSource.NAME_INFERRED) {
                " (" + t("inferred from the name", "이름에서 추정") + ")"
            } else {
                ""
            }
            lines.add(t("Parameters", "파라미터") + ": ${entry.paramsLabel}$suffix")
        }
        entry.quant?.let { lines.add(t("Quantisation", "양자화") + ": $it") }
        entry.fileSizeBytes?.let { lines.add(t("Size", "크기") + ": ${DownloadEngine.humanBytes(it)}") }
        entry.contextTokens?.let { lines.add(t("Context (app)", "컨텍스트(앱)") + ": $it") }
        entry.maxContext?.let { lines.add(t("Context (model maximum)", "컨텍스트(모델 최대)") + ": $it") }
        entry.license?.let { lines.add(t("License", "라이선스") + ": $it") }
        entry.repoId?.let { lines.add(t("Repository", "리포지토리") + ": $it") }
        entry.fileName?.let { lines.add(t("File", "파일") + ": $it") }
        entry.sha256?.let { lines.add("SHA-256: $it") }
        if (entry.source == ModelSource.HF_REMOTE) {
            lines.add(
                if (entry.sha256 != null) t("Checksum: will be verified after download", "체크섬: 다운로드 후 검증 예정")
                else t("Checksum: the server provided none — cannot be verified", "체크섬: 서버가 제공하지 않음 — 검증 불가"),
            )
        }
        entry.localPath?.let { lines.add(t("Path", "경로") + ": $it") }
        entry.note?.let { lines.add(t("Note", "참고") + ": $it") }
        themedDialog()
            .setTitle(t("Model details", "모델 상세 정보"))
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }

    private fun confirmDeleteModel(entry: ModelEntry) {
        themedDialog()
            .setTitle(t("Delete model", "모델 삭제"))
            .setMessage(
                t("Delete the GGUF file and remove it from the list?", "GGUF 파일을 삭제하고 목록에서 제거할까요?") +
                    "\n\n" + (entry.localPath ?: ""),
            )
            .setPositiveButton(t("Delete", "삭제")) { _, _ ->
                val error = modelCatalog.deleteInstalled(entry)
                refreshKnownModels()
                renderModelList()
                if (error != null) addSystemBubble("⚠ $error") else addSystemBubble("🗑 " + entry.displayName)
            }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    // ------------------------------------------------------- picker helpers

    private fun addChip(container: LinearLayout, chip: View) {
        container.addView(
            chip,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(6) },
        )
    }

    private fun hScroll(inner: View): View = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(
            inner,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun toggleChip(labelText: String, activeNow: Boolean, onToggle: (Boolean) -> Unit): TextView {
        var active = activeNow
        val chip = TextView(this).apply {
            text = labelText
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            paintChip(this, active)
        }
        chip.setOnClickListener {
            active = !active
            paintChip(chip, active)
            onToggle(active)
            renderModelList()
        }
        return chip
    }

    private fun paintChip(chip: TextView, active: Boolean) {
        val p = StudioUi.palette
        chip.setTextColor(if (active) p.bg else p.textSoft)
        chip.background = AiUiKit.rounded(
            if (active) p.accent else p.surface2,
            dp(999),
            strokeColor = if (active) p.accent else p.border,
            strokePx = dp(1),
        )
    }

    private fun emptyState(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(StudioUi.palette.muted)
        textSize = 13f
        setPadding(dp(4), dp(16), dp(4), dp(16))
    }

    private fun messageCard(text: String): View = TextView(this).apply {
        this.text = text
        setTextColor(StudioUi.palette.warning)
        textSize = 12f
        background = AiUiKit.rounded(
            AiUiKit.withAlpha(StudioUi.palette.warning, 0.10f),
            dp(10),
            strokeColor = StudioUi.palette.warning,
            strokePx = dp(1),
        )
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(4), 0, dp(4)) }
    }

    /** Failure card in the chat: retry the same request, or go fix the cause. */
    private fun addRetryCard() {
        val p = StudioUi.palette
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = AiUiKit.rounded(
                AiUiKit.withAlpha(p.warning, 0.10f),
                dp(12),
                strokeColor = p.warning,
                strokePx = dp(1),
            )
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
        }
        card.addView(TextView(this).apply {
            text = t(
                "The request failed. Retrying sends the same message once — it is never duplicated.",
                "요청이 실패했습니다. 다시 시도해도 같은 메시지를 한 번만 다시 보내며 중복되지 않습니다.",
            )
            setTextColor(p.text)
            textSize = 12f
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        addChip(actions, AiUiKit.actionChip(this, t("Retry", "다시 시도")) { _ ->
            val ok = runCatching { engine.retryLastFailed() }.getOrDefault(false)
            if (ok) {
                (card.parent as? ViewGroup)?.removeView(card)
            } else {
                addSystemBubble(t("Retry unavailable right now.", "지금은 다시 시도할 수 없습니다."))
            }
        })
        addChip(actions, AiUiKit.actionChip(this, t("Choose model", "모델 선택")) { _ -> showTab(Tab.MODELS) })
        addChip(actions, AiUiKit.actionChip(this, t("Settings", "설정")) { _ -> showTab(Tab.SETTINGS) })
        card.addView(actions)
        chatMessages.addView(card)
        scrollChatToBottom()
    }

    private fun updateModelLabel() {
        modelText.text = engine.currentModelLabel()
        if (::modelChip.isInitialized) modelChip.text = compactModelLabel()
    }

    private fun fetchModels(endpoint: ProviderEndpoint, providerId: String) {
        Toast.makeText(this, t("Fetching models…", "모델 목록 가져오는 중…"), Toast.LENGTH_SHORT).show()
        scope.launch {
            val models = withContext(Dispatchers.IO) {
                runCatching {
                    val response = AndroidHttpTransport().send(ConnectionTester.buildRequest(endpoint))
                    if (response.status in 200..299) ProviderModelList.parse(providerId, response.body) else emptyList()
                }.getOrDefault(emptyList())
            }
            val message = if (models.isEmpty()) {
                t("No models returned (check the key and endpoint).", "모델을 가져오지 못했습니다 (키/엔드포인트 확인).")
            } else {
                models.joinToString("\n")
            }
            themedDialog()
                .setTitle(t("Available models", "사용 가능한 모델"))
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun endpointFor(providerId: String): ProviderEndpoint? = when (providerId) {
        "gemini" -> settings.apiKey?.let {
            ProviderEndpoint("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta", it, settings.geminiModel, AuthStyle.QUERY_KEY)
        }
        "openai" -> settings.openaiApiKey?.let {
            ProviderEndpoint("openai", "OpenAI", settings.openaiBaseUrl, it, settings.openaiModel, AuthStyle.BEARER)
        }
        "anthropic" -> settings.anthropicApiKey?.let {
            ProviderEndpoint("anthropic", "Anthropic", "https://api.anthropic.com/v1", it, settings.anthropicModel, AuthStyle.X_API_KEY)
        }
        else -> null
    }

    private fun testConnection(endpoint: ProviderEndpoint) {
        Toast.makeText(this, t("Testing ${endpoint.displayName}…", "${endpoint.displayName} 테스트 중…"), Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { ConnectionTester.test(endpoint, AndroidHttpTransport()) }
            themedDialog()
                .setTitle(t("Connection test", "연결 테스트"))
                .setMessage("${endpoint.displayName} (${ConnectionTester.mask(endpoint.apiKey)})\n\n${result.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ----------------------------------------------------------- Settings tab

    private fun buildSettingsTab(): View {
        val p = StudioUi.palette
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        column.addView(sectionTitle(t("AI settings", "AI 설정")))

        val geminiKey = field("Gemini API key", settings.apiKey.orEmpty(), secret = true)
        val geminiModel = field("Gemini model", settings.geminiModel)
        val openaiKey = field("OpenAI API key", settings.openaiApiKey.orEmpty(), secret = true)
        val openaiBase = field("OpenAI base URL", settings.openaiBaseUrl)
        val openaiModel = field("OpenAI model", settings.openaiModel)
        val anthropicKey = field("Anthropic API key", settings.anthropicApiKey.orEmpty(), secret = true)
        val anthropicModel = field("Anthropic model", settings.anthropicModel)
        val threads = field("CPU threads", settings.nThreads.toString())
        val context = field("Context length", settings.contextLength.toString())
        // GPU offload: layers llama.cpp places on the Vulkan GPU. 99 (or any
        // negative value) means every layer the model has - llama.h clamps it to
        // the real count - and 0 keeps inference on the CPU.
        val gpuLayers = field("GPU layers (99 = all, 0 = CPU)", settings.nGpuLayers.toString())

        for (v in listOf(geminiKey, geminiModel, openaiKey, openaiBase, openaiModel, anthropicKey, anthropicModel, threads, context, gpuLayers)) {
            column.addView(label(v.first))
            column.addView(v.second)
        }
        val autoTune = CheckBox(this).apply {
            text = t(
                "Auto-tune CPU/GPU (recommended)",
                "CPU/GPU 자동 최적화 (권장)",
            )
            isChecked = settings.autoTuneLocal
        }
        fun syncTuneFields() {
            threads.second.isEnabled = !autoTune.isChecked
            gpuLayers.second.isEnabled = !autoTune.isChecked
        }
        autoTune.setOnCheckedChangeListener { _, _ -> syncTuneFields() }
        syncTuneFields()
        column.addView(autoTune)
        val topology = com.aide.mobilestudio.ai.agent.HardwareManager(this).getCpuTopology()
        column.addView(body(
            t(
                "Detected ${topology.cores} CPU cores, ${topology.performanceThreads} performance threads. " +
                    "Auto-tune asks llama.cpp for every GPU layer and uses the performance cores for CPU " +
                    "layers; if the model does not fit, offload is lowered and the largest working value is cached.",
                "CPU ${topology.cores}코어, 성능 코어 ${topology.performanceThreads}스레드 감지. " +
                    "자동 최적화는 모든 레이어의 GPU 오프로드를 요청하고 CPU 레이어는 성능 코어로 처리합니다. " +
                    "모델이 GPU 메모리에 다 들어가지 않으면 오프로드를 낮추고, 성공한 최대값을 캐시합니다.",
            ),
        ))
        val autoApply = CheckBox(this).apply { text = t("Auto-apply edits", "자동 편집 적용"); isChecked = settings.autoApplyChanges }
        val webSearch = CheckBox(this).apply { text = t("Web search tool", "웹 검색 도구"); isChecked = settings.webSearchEnabled }
        column.addView(autoApply)
        column.addView(webSearch)

        column.addView(Button(this).apply {
            text = t("Save", "저장")
            isAllCaps = false
            setOnClickListener {
                settings.apiKey = geminiKey.second.text.toString().trim().ifBlank { null }
                settings.geminiModel = geminiModel.second.text.toString().trim().ifBlank { settings.geminiModel }
                settings.openaiApiKey = openaiKey.second.text.toString().trim().ifBlank { null }
                settings.openaiBaseUrl = openaiBase.second.text.toString().trim().ifBlank { settings.openaiBaseUrl }
                settings.openaiModel = openaiModel.second.text.toString().trim().ifBlank { settings.openaiModel }
                settings.anthropicApiKey = anthropicKey.second.text.toString().trim().ifBlank { null }
                settings.anthropicModel = anthropicModel.second.text.toString().trim().ifBlank { settings.anthropicModel }
                settings.autoTuneLocal = autoTune.isChecked
                settings.nThreads = threads.second.text.toString().toIntOrNull() ?: settings.nThreads
                settings.contextLength = context.second.text.toString().toIntOrNull() ?: settings.contextLength
                settings.nGpuLayers = gpuLayers.second.text.toString().toIntOrNull() ?: settings.nGpuLayers
                settings.autoApplyChanges = autoApply.isChecked
                settings.webSearchEnabled = webSearch.isChecked
                engine.reloadConfig()
                updateModelLabel()
                Toast.makeText(this@AiWorkspaceActivity, t("Saved", "저장됨"), Toast.LENGTH_SHORT).show()
            }
        })
        column.addView(body(
            t(
                "API keys are stored in the app's encrypted preferences and are never written to logs.",
                "API 키는 앱의 암호화된 저장소에 보관되며 로그에 남기지 않습니다."
            ),
        ))
        column.addView(body(
            t(
                "GPU layers: 99 (or -1) puts every layer the model has on the Vulkan GPU, 0 keeps it on the CPU. " +
                    "A device that cannot hold the model lowers the offload on its own; the layers actually " +
                    "offloaded are reported by llama.cpp in the log.",
                "GPU 레이어: 99(또는 -1)는 모델의 모든 레이어를 Vulkan GPU에 올리고, 0은 CPU만 사용합니다. " +
                    "메모리가 부족한 기기에서는 오프로드 수준을 스스로 낮춥니다. 실제로 올라간 레이어 수는 " +
                    "llama.cpp가 로그에 남깁니다.",
            ),
        ))

        val mem = com.aide.mobilestudio.ai.resource.AndroidMemoryProbe.snapshot(this)
        val decision = com.aide.mobilestudio.ai.resource.ResourceManager().decide(
            snapshot = mem,
            pendingTasks = 4,
            loadedLocalModels = 1,
            buildRunning = false,
            userMax = 4,
        )
        column.addView(sectionTitle(t("Resources", "자원")))
        column.addView(body(
            t(
                "Free ${mem.availableMb}MB · heap ${(mem.heapUsedFraction * 100).toInt()}% · " +
                    "parallel tasks ≤ ${decision.count} (${decision.reason})",
                "여유 ${mem.availableMb}MB · 힙 ${(mem.heapUsedFraction * 100).toInt()}% · " +
                    "병렬 작업 ≤ ${decision.count} (${decision.reason})",
            ),
        ))

        column.addView(sectionTitle(t("Project languages", "프로젝트 언어")))
        if (projectDir == null) {
            column.addView(body(t("No project open.", "열린 프로젝트가 없습니다.")))
        } else {
            val markers = projectDir!!.listFiles()?.map { it.name } ?: emptyList()
            val detected = com.aide.mobilestudio.ai.lang.LanguageRegistry.byProjectFiles(markers)
            if (detected.isEmpty()) {
                column.addView(body(t("No build/config markers detected.", "빌드·설정 마커를 찾지 못했습니다.")))
            } else {
                for (spec in detected) {
                    val caps = com.aide.mobilestudio.ai.lang.LanguageRegistry.capabilities(spec, com.aide.mobilestudio.ai.lang.PathToolProbe)
                    column.addView(TextView(this).apply {
                        text = "${spec.displayName} — " + if (caps.compile.available) "compile: ${caps.compile.tool}" else "compile: unavailable"
                        setTextColor(if (caps.compile.available) StudioUi.palette.success else StudioUi.palette.textSoft)
                        textSize = StudioUi.TypeSmall
                        setPadding(0, dp(3), 0, dp(3))
                    })
                }
            }
        }

        column.addView(sectionTitle(t("Language servers (LSP)", "언어 서버 (LSP)")))
        val lsp = com.aide.mobilestudio.ai.lsp.LspServerRegistry.availability(com.aide.mobilestudio.ai.lang.PathToolProbe)
        val availableCount = lsp.count { it.second }
        for ((spec, available) in lsp) {
            column.addView(TextView(this).apply {
                text = (if (available) "✔ " else "· ") + spec.command + "  (" + spec.languageIds.joinToString(", ") + ")"
                setTextColor(if (available) StudioUi.palette.success else StudioUi.palette.muted)
                textSize = StudioUi.TypeSmall
                setPadding(0, dp(3), 0, dp(3))
            })
        }
        column.addView(body(
            t(
                "$availableCount of ${lsp.size} language servers are present on PATH. Others are unavailable.",
                "${lsp.size}개 중 ${availableCount}개 언어 서버가 PATH에 있습니다. 나머지는 사용할 수 없습니다."
            ),
        ))

        column.addView(sectionTitle(t("MCP servers", "MCP 서버")))
        val mcp = runCatching { engine.mcpServerNames() }.getOrDefault(emptyList())
        if (mcp.isEmpty()) {
            column.addView(body(t("No MCP servers configured.", "설정된 MCP 서버가 없습니다.")))
        } else {
            for (name in mcp) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(this).apply {
                    text = name
                    setTextColor(StudioUi.palette.text)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(AiUiKit.actionChip(this, t("Test", "테스트")) { _ ->
                    scope.launch {
                        val status = engine.testMcpServer(name)
                        themedDialog()
                            .setTitle(name)
                            .setMessage(status)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
                row.addView(AiUiKit.actionChip(this, t("Delete", "삭제")) { _ ->
                    engine.removeMcpServer(name)
                    Toast.makeText(this@AiWorkspaceActivity, t("Removed $name", "$name 삭제됨"), Toast.LENGTH_SHORT).show()
                    showTab(Tab.SETTINGS)
                })
                column.addView(row)
            }
        }
        column.addView(AiUiKit.ghostButton(this, t("+ Add MCP server", "+ MCP 서버 추가")) { _ -> promptAddMcp() })

        // Language selection
        column.addView(sectionTitle(t("Language", "언어")))
        val languages = AppLanguage.supported
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, languages.map { it.nativeName }.toTypedArray())
        val languageSpinner = androidx.appcompat.widget.AppCompatSpinner(this).apply {
            setAdapter(adapter)
            setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val selectedLang = languages[position]
                    if (selectedLang.code != AppLanguage.current(this@AiWorkspaceActivity).code) {
                        AppLanguage.set(this@AiWorkspaceActivity, selectedLang.code)
                        // Every label is baked into the view tree at build time,
                        // so rebuild the whole screen instead of one tab.
                        recreate()
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
            val currentCode = AppLanguage.current(this@AiWorkspaceActivity).code
            val currentIndex = languages.indexOfFirst { it.code == currentCode }.coerceAtLeast(0)
            setSelection(currentIndex)
        }
        column.addView(languageSpinner)

        return ScrollView(this).apply { addView(column) }
    }

    private fun promptAddMcp() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val nameField = EditText(this).apply { hint = t("Name (e.g. filesystem)", "이름 (예: filesystem)") }
        val commandField = EditText(this).apply {
            hint = t("Command (e.g. npx -y @modelcontextprotocol/server-filesystem /sdcard)", "명령 (예: npx -y @modelcontextprotocol/server-filesystem /sdcard)")
            typeface = Typeface.MONOSPACE
            textSize = StudioUi.TypeSmall
        }
        column.addView(label(t("Name", "이름")))
        column.addView(nameField)
        column.addView(label(t("Command", "명령")))
        column.addView(commandField)
        themedDialog()
            .setTitle(t("Add MCP server", "MCP 서버 추가"))
            .setView(column)
            .setPositiveButton(t("Add", "추가")) { _, _ ->
                val name = nameField.text.toString().trim()
                val command = commandField.text.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (name.isNotEmpty() && command.isNotEmpty()) {
                    engine.addMcpServer(name, command)
                    Toast.makeText(this, t("Added $name", "$name 추가됨"), Toast.LENGTH_SHORT).show()
                    showTab(Tab.SETTINGS)
                }
            }
            .setNegativeButton(t("Cancel", "취소"), null)
            .show()
    }

    private fun field(hintText: String, value: String, secret: Boolean = false): Pair<String, EditText> {
        val et = EditText(this).apply {
            hint = hintText
            setText(value)
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.muted)
            textSize = StudioUi.TypeBody
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        return hintText to et
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(StudioUi.palette.textSoft)
        textSize = 11f
        setPadding(0, dp(10), 0, dp(2))
    }

    // ----------------------------------------------------------- Terminal tab

    private lateinit var terminalOutput: TextView
    private lateinit var terminalInput: EditText

    private fun buildTerminalTab(): View {
        val p = StudioUi.palette
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        terminalOutput = TextView(this).apply {
            setTextColor(p.text)
            textSize = StudioUi.TypeSmall
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val scroll = ScrollView(this).apply { addView(terminalOutput) }
        wrapper.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.surface)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        terminalInput = EditText(this).apply {
            hint = t("Shell command…", "셸 명령…")
            setHintTextColor(p.muted)
            setTextColor(p.text)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(terminalInput)
        row.addView(Button(this).apply {
            text = t("Run", "실행")
            isAllCaps = false
            setOnClickListener { runTerminalCommand() }
        })
        wrapper.addView(row)
        return wrapper
    }

    private fun runTerminalCommand() {
        val cmd = terminalInput.text?.toString()?.trim().orEmpty()
        if (cmd.isEmpty()) return
        terminalInput.setText("")
        val cwd = projectDir ?: File(filesDir, "workspace").apply { mkdirs() }
        terminalOutput.append("$ $cmd\n")
        Thread {
            val env = runCatching { ShellEnvironment.buildToolEnvironment(this, cwd) }.getOrDefault(emptyMap())
            val result = runCatching {
                ShellRunner.runSh(this, cmd, cwd, env, 100_000) { line ->
                    runOnUiThread { terminalOutput.append(line + "\n") }
                }
            }.getOrNull()
            runOnUiThread {
                terminalOutput.append("[exit ${result?.exitCode ?: -1}]\n\n")
            }
        }.start()
    }

    // ------------------------------------------------------------- small utils

    /**
     * Translates a string using the centralized AppLanguage system.
     * Supports 30+ languages. English is the fallback.
     */
    private fun t(en: String, ko: String): String = AppLanguage.text(this, en)

    private fun sectionTitle(text: String): View = AiUiKit.sectionHeader(this, text)

    private fun body(text: String): TextView = AiUiKit.body(this, text)

    private fun relativePath(path: String): String {
        val root = projectDir ?: return path
        return runCatching { File(path).relativeTo(root).path }.getOrDefault(path)
    }
}
