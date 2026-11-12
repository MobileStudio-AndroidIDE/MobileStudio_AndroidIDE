package com.aide.mobilestudio

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.aide.mobilestudio.ai.ui.AiWorkspaceActivity
import com.aide.mobilestudio.ai.OpencodeEnvironment
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.tukaani.xz.XZInputStream
import android.system.Os


/**
 * Compatibility wrapper. The IDE hosts [ToolsPanel] in-window, so opening the
 * Tools menu no longer replaces the editor with another screen. This activity
 * remains only for callers that still pass an Intent.
 */
class ToolsActivity : AppCompatActivity() {
    private var panel: ToolsPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = ToolsPanel(this) { finish() }
        panel = p
        setContentView(p.create(intent.getStringExtra("tools_tab")))
    }

    override fun onResume() {
        super.onResume()
        panel?.onResume()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        panel?.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        panel?.dispose()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        MobileStudioCrashGuard.finishSafely(this)
    }

    companion object {
        fun start(context: Context, tabId: String? = null) {
            val intent = Intent(context, ToolsActivity::class.java)
            tabId?.let { intent.putExtra("tools_tab", it) }
            context.startActivity(intent)
        }
    }
}

internal class ToolsPanel(
    private val host: AppCompatActivity,
    private val onClose: () -> Unit,
) : ContextWrapper(host) {

    companion object {
        private const val REQ_IMPORT_LAYOUTLIB_ZIP = 4201
        private val DEFAULT_ANDROID_LLVM_URL = AndroidHostedLlvmInstaller.DEFAULT_URL
        private const val DEFAULT_NINJA_URL =
            "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/Ninja_arm64/ninja"
        private const val DEFAULT_PYTHON_URL =
            "https://www.python.org/ftp/python/3.14.6/python-3.14.6-aarch64-linux-android.tar.gz"
    }

    private lateinit var toolchain: ToolchainManager
    private lateinit var rootContainer: LinearLayout
    private lateinit var sidebar: LinearLayout
    private lateinit var contentScroll: ScrollView
    private lateinit var layoutLibImporter: LayoutLibImporter
    private lateinit var updateSites: ToolchainUpdateSites
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** Per-kind count of "Show more old versions" taps (survives re-renders). */
    private val expandedKindCounts = mutableMapOf<ToolchainManager.Kind, Int>()

    /** Currently selected Tools sidebar category. */
    private var currentCategory: String = "all"

    /** Selected tab in the SDK Manager card: 0 = SDK Platforms, 1 = SDK Tools, 2 = SDK Update Sites. */
    private var sdkManagerTab: Int = 0

    private fun t(value: String): String = AppLanguage.literal(this, value)

    /**
     * The project the IDE currently has open. Live IDE state wins, then the
     * persisted Tools preference, then the general IDE preference, so tool
     * cards never report "Open a project first" while an editor tab is open.
     */
    private fun currentProjectDirOrNull(): File? {
        (host as? StudioMainActivity)?.currentProjectDir?.let { if (it.isDirectory) return it }
        getSharedPreferences("ide", MODE_PRIVATE).getString("last_project_dir", null)
            ?.let { File(it).takeIf { d -> d.isDirectory } }?.let { return it }
        getSharedPreferences("mobilestudio_settings", MODE_PRIVATE).getString("last_project", null)
            ?.let { File(it).takeIf { d -> d.isDirectory } }?.let { return it }
        return null
    }

    /** Opens a resource/source file in the embedded editor when possible. */
    private fun openProjectFile(file: File): Boolean {
        if (!file.exists()) {
            toast("File not found: ${file.name}")
            return false
        }
        val ide = host as? StudioMainActivity ?: return false
        ide.openFileInEditor(file)
        return true
    }

    /** One shared dialog for "you need a project" prompts. */
    private fun requireProject(onProject: (File) -> Unit): File? {
        val dir = currentProjectDirOrNull()
        if (dir == null) {
            toast("Open a project first")
            return null
        }
        onProject(dir)
        return dir
    }

    /** Builds the panel and returns the root view to embed in the IDE. */
    fun create(tabId: String?): View {
        toolchain = ToolchainManager(host)
        layoutLibImporter = LayoutLibImporter(host)
        updateSites = ToolchainUpdateSites(host)
        if (tabId != null) {
            if (tabId == "downloads") {
                DownloadCenter.showIdleWindow()
                currentCategory = "all"
            } else {
                currentCategory = mapToolIdToCategory(tabId)
            }
        }
        val root = buildLayout()
        renderCategory(currentCategory)
        refreshRemoteCatalogs()
        return root
    }

    fun onResume() {
        if (::rootContainer.isInitialized) renderAll()
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_IMPORT_LAYOUTLIB_ZIP && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { importLayoutLibZip(it) }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    private fun buildLayout(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.gradient(host, "#1E1F22", "#1E1F22")
        }

        // Android Studio style tool-window title bar.
        outer.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
            )
            addView(TextView(host).apply {
                text = "Tools"
                textSize = 14f
                setTextColor(StudioUi.color(StudioUi.Text))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            listOf("—", "▢", "✕").forEach { glyph ->
                addView(TextView(host).apply {
                    text = glyph
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(38))
                    setOnClickListener { if (glyph == "✕") onClose() }
                })
            }
        })

        // Main row: sidebar + content area
        val mainRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // Sidebar
        sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                dp(150).toInt(), ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        buildSidebar()
        mainRow.addView(sidebar)

        // Divider
        val divider = View(this).apply {
            setBackgroundColor(StudioUi.color(StudioUi.Border))
            layoutParams = LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        mainRow.addView(divider)

        // Content area
        val contentWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Ink))
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f
            )
        }
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Ink))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        contentScroll = ScrollView(this).apply {
            setBackgroundColor(StudioUi.color(StudioUi.Ink))
            addView(rootContainer)
        }
        contentWrapper.addView(contentScroll)
        mainRow.addView(contentWrapper)

        outer.addView(mainRow)
        return outer
    }

    /** Maps a specific tool action ID to a sidebar category ID. */
    private fun mapToolIdToCategory(toolId: String): String {
        return when (toolId) {
            "sdk_manager", "sdk_platforms", "sdk_tools", "sdk_update_manager",
            "sdk_installation", "sdk_configuration", "ndk_manager", "cmake_manager",
            "build_tools_manager", "platform_tools_manager", "emulator_manager" -> "sdk_dev"
            "android_sdk_upgrade" -> "sdk_dev"
            "jdk_manager", "jdk_installation", "jdk_version_selection", "jdk_configuration",
            "java_runtime_info", "java_env_variables" -> "java_dev"
            "device_manager", "virtual_device_manager", "avd_configuration",
            "android_sdk_inspector", "apk_analyzer", "layout_inspector", "logcat",
            "device_explorer", "resource_manager", "firebase", "app_links" -> "android_dev"
            "generate_javadoc", "cling", "groovy_console" -> "java_dev"
            "agp_upgrade" -> "build_gradle"
            "firebase" -> "android_dev"
            "gradle_settings", "gradle_configuration", "gradle_cache_management",
            "build_configuration", "build_tools_configuration" -> "build_gradle"
            "terminal", "external_tools", "project_structure", "plugins", "developer_tools" -> "additional"
            else -> "all"
        }
    }

    /** Renders the sidebar category buttons. */
    private fun buildSidebar() {
        sidebar.removeAllViews()
        sidebar.addView(TextView(this).apply {
            text = "TOOLS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(dp(12), dp(12), dp(12), dp(6))
        })

        val categories = listOf(
            "all" to "All",
            "sdk_dev" to "SDK & Dev Tools",
            "java_dev" to "Java Development",
            "android_dev" to "Android Development",
            "build_gradle" to "Build & Gradle",
            "additional" to "Additional Tools"
        )

        for ((id, label) in categories) {
            val selected = currentCategory == id
            val btn = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) StudioUi.color(StudioUi.Blue) else StudioUi.color(StudioUi.Text))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(if (selected) StudioUi.color(StudioUi.Surface2) else StudioUi.color(StudioUi.Surface))
                setOnClickListener {
                    currentCategory = id
                    renderCategory(id)
                }
            }
            sidebar.addView(btn)
            sidebar.addView(spacer(dp(2)))
        }
    }

    /** Renders the content area for the selected category. */
    private fun renderCategory(category: String) {
        rootContainer.removeAllViews()
        buildSidebar() // refresh selection highlight
        when (category) {
            "all" -> renderAllCards()
            "sdk_dev" -> {
                rootContainer.addView(buildStudioStyleSdkCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildSdkUpgradeCard())
                rootContainer.addView(spacer(dp(8)))
                ToolchainManager.Kind.values().filter { it == ToolchainManager.Kind.SDK }.forEach { kind ->
                    rootContainer.addView(buildKindCard(kind))
                    rootContainer.addView(spacer(dp(8)))
                }
            }
            "java_dev" -> {
                ToolchainManager.Kind.values().filter { it == ToolchainManager.Kind.JDK }.forEach { kind ->
                    rootContainer.addView(buildKindCard(kind))
                    rootContainer.addView(spacer(dp(8)))
                }
            }
            "android_dev" -> {
                rootContainer.addView(buildDeviceManagerCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildResourceManagerCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildFirebaseCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildTroubleshootCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildLogcatCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildApkToolsCard())
                rootContainer.addView(spacer(dp(8)))
                ToolchainManager.Kind.values().filter { it == ToolchainManager.Kind.NDK || it == ToolchainManager.Kind.CMAKE }.forEach { kind ->
                    rootContainer.addView(buildKindCard(kind))
                    rootContainer.addView(spacer(dp(8)))
                }
            }
            "build_gradle" -> {
                rootContainer.addView(buildAgpUpgradeCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildBuildOptionsCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildCacheManagerCard())
                rootContainer.addView(spacer(dp(8)))
                ToolchainManager.Kind.values().filter { it == ToolchainManager.Kind.GRADLE }.forEach { kind ->
                    rootContainer.addView(buildKindCard(kind))
                    rootContainer.addView(spacer(dp(8)))
                }
            }
            "additional" -> {
                rootContainer.addView(buildLanguageCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildAiCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildAppIconCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildDependencySearchCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildDiagnosticsCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildLayoutLibCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildRuntimeToolsCard())
                rootContainer.addView(spacer(dp(8)))
                rootContainer.addView(buildServicesCard())
                rootContainer.addView(spacer(dp(8)))
                val remainingKinds = ToolchainManager.Kind.values().filter { kind ->
                    kind != ToolchainManager.Kind.SDK &&
                        kind != ToolchainManager.Kind.JDK &&
                        kind != ToolchainManager.Kind.NDK &&
                        kind != ToolchainManager.Kind.CMAKE &&
                        kind != ToolchainManager.Kind.GRADLE
                }
                remainingKinds.forEach { kind ->
                    rootContainer.addView(buildKindCard(kind))
                    rootContainer.addView(spacer(dp(8)))
                }
                rootContainer.addView(buildAppUpdateCard())
                rootContainer.addView(spacer(dp(8)))
            }
        }
    }

    /** Renders all tool cards (used by the "All" category). */
    private fun renderAllCards() {
        rootContainer.removeAllViews()
        rootContainer.addView(buildServicesCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildResourceManagerCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildFirebaseCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildAgpUpgradeCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildDeviceManagerCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildTroubleshootCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildLanguageCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildAiCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildAppIconCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildApkToolsCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildDependencySearchCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildCacheManagerCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildLogcatCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildDiagnosticsCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildBuildOptionsCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildStudioStyleSdkCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildLayoutLibCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildRuntimeToolsCard())
        rootContainer.addView(spacer(dp(8)))
        ToolchainManager.Kind.values().forEach { kind ->
            rootContainer.addView(buildKindCard(kind))
            rootContainer.addView(spacer(dp(8)))
        }
        rootContainer.addView(buildAppUpdateCard())
        rootContainer.addView(spacer(dp(8)))
    }

    private fun renderAll() {
        rootContainer.removeAllViews()
        rootContainer.addView(buildServicesCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildResourceManagerCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildFirebaseCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildAgpUpgradeCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildDeviceManagerCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildTroubleshootCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildLanguageCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildAiCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildAppIconCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildApkToolsCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildDependencySearchCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildCacheManagerCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildLogcatCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildDiagnosticsCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildBuildOptionsCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildStudioStyleSdkCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildLayoutLibCard())
        rootContainer.addView(spacer(dp(8)))
        rootContainer.addView(buildRuntimeToolsCard())
        rootContainer.addView(spacer(dp(8)))
        ToolchainManager.Kind.values().forEach { kind ->
            rootContainer.addView(buildKindCard(kind))
            rootContainer.addView(spacer(dp(8)))
        }
        rootContainer.addView(buildAppUpdateCard())
        rootContainer.addView(spacer(dp(8)))
    }

    private fun buildDeviceManagerCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Device Manager"))
        card.addView(TextView(host).apply {
            text = t("ADB devices reported by the Android Debug Bridge")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        val adbInfo = AdbDeviceManager.resolveAdbBinary(host)
        card.addView(TextView(host).apply {
            text = t(
                if (adbInfo?.isAvailable == true) "Android Debug Bridge ready: " + adbInfo?.adbPath
                else "ADB binary not found. Install platform-tools from Tools > Download Manager."
            )
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        val devices = AdbDeviceManager.listDevices()
        card.addView(TextView(host).apply {
            text = t("Connected devices (" + devices.size + ")")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(6))
        })
        for (d in devices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(0), dp(4), dp(0), dp(4))
            }
            row.addView(TextView(host).apply {
                text = "●"
                setTextColor(
                    if (d.state == AdbDeviceManager.DeviceState.DEVICE) StudioUi.color(StudioUi.Green) else StudioUi.color(StudioUi.Amber)
                )
                textSize = 12f
                setPadding(dp(0), 0, dp(8), dp(0))
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(8), 0, dp(8), 0)
            }
            info.addView(TextView(host).apply {
                text = d.model.ifBlank { d.serial }
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.Text))
                setTypeface(null, Typeface.BOLD)
            })
            info.addView(TextView(host).apply {
                text = buildString {
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
                }
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                typeface = Typeface.MONOSPACE
            })
            row.addView(info)
            row.addView(secondaryButton("Logcat") {
                LogcatActivity.start(host, d.product.ifBlank { d.model }, 0)
            })
            card.addView(row)
        }
        card.addView(spacer(dp(8)))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("Refresh") { renderCategory(currentCategory) })
            addView(secondaryButton("Open Terminal for adb") { AndroidShellActivity.start(host, filesDir) })
        })
        return card
    }

    private fun buildTroubleshootCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Troubleshoot Device Connections"))
        card.addView(TextView(host).apply {
            text = t("Check Android Debug Bridge device state and connection health")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        val adbInfo = AdbDeviceManager.resolveAdbBinary(host)
        card.addView(TextView(host).apply {
            text = t(
                if (adbInfo?.isAvailable == true) "Android Debug Bridge ready: " + adbInfo?.adbPath
                else "ADB binary not found. Install platform-tools from Tools > Download Manager."
            )
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        val devices = AdbDeviceManager.listDevices()
        card.addView(TextView(host).apply {
            text = t("Connected devices (${devices.size})")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(6))
        })
        for (d in devices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(0), dp(4), dp(0), dp(4))
            }
            row.addView(TextView(host).apply {
                text = "●"
                setTextColor(
                    if (d.state == AdbDeviceManager.DeviceState.DEVICE) StudioUi.color(StudioUi.Green) else StudioUi.color(StudioUi.Amber)
                )
                textSize = 12f
                setPadding(dp(0), 0, dp(8), dp(0))
            })
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setPadding(dp(8), 0, dp(8), 0)
            }
            info.addView(TextView(host).apply {
                text = d.model.ifBlank { d.serial }
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.Text))
                setTypeface(null, Typeface.BOLD)
            })
            info.addView(TextView(host).apply {
                text = buildString {
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
                }
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                typeface = Typeface.MONOSPACE
            })
            row.addView(info)
            row.addView(secondaryButton("Logcat") {
                LogcatActivity.start(host, d.product.ifBlank { d.model }, 0)
            })
            card.addView(row)
        }
        card.addView(spacer(dp(8)))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("Refresh") { renderCategory(currentCategory) })
            addView(secondaryButton("Open Terminal for adb") { AndroidShellActivity.start(host, filesDir) })
        })
        return card
    }

    private fun buildServicesCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Services"))
        card.addView(TextView(host).apply {
            text = t("MobileStudio background service status and controls")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(TextView(host).apply {
            text = t(
                if (AIBackgroundService.isRunning) "Background AI service is running"
                else "Background AI service is stopped"
            )
            textSize = 13f
            setTextColor(StudioUi.color(if (AIBackgroundService.isRunning) StudioUi.Green else StudioUi.TextSoft))
            setTypeface(null, Typeface.BOLD)
        })
        card.addView(TextView(host).apply {
            text = t("Background AI log: " + File(filesDir, "background_ai.log").absolutePath)
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.Muted))
            typeface = Typeface.MONOSPACE
        })
        card.addView(spacer(dp(8)))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("Start AI Background Service") {
                AIBackgroundService.startService(host)
            })
            addView(secondaryButton("Stop AI Background Service") {
                AIBackgroundService.stopService(host)
            })
        })
        return card
    }

    private fun buildSdkUpgradeCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Android SDK Upgrade Assistant"))
        val toolchain = ToolchainManager(host)
        val installed = toolchain.installedVersions(ToolchainManager.Kind.SDK)
        val available = toolchain.availableVersions(ToolchainManager.Kind.SDK)
        card.addView(TextView(host).apply {
            text = t("Installed SDK versions: " + installed.joinToString(", "))
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(TextView(host).apply {
            text = t("Available SDK versions: " + available.joinToString(", "))
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("Upgrade") {
                val installer = BootstrapSdkInstaller(host, onLog = { }, onProgress = { })
                installer.install { ok ->
                    toast(if (ok) "Android SDK update completed" else "Android SDK update failed")
                }
            })
            addView(secondaryButton("Refresh") {
                renderCategory(currentCategory)
            })
        })
        return card
    }

    /**
     * Tools > Resource Manager. Indexes the open project's res/ tree through
     * [ResourceIndex], lists resources grouped by type, and searches them by
     * name; selecting a result opens the declaring file in the editor.
     */
    private fun buildResourceManagerCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Resource Manager"))

        val projectDir = currentProjectDirOrNull()
        if (projectDir == null) {
            card.addView(TextView(host).apply {
                text = t("Open a project to index its res/ folder, layouts, drawables and values.")
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, 0, 0, dp(8))
            })
            card.addView(primaryButton("Open Project") {
                FileManagerActivity.start(host, projectMode = true)
            })
            return card
        }

        val status = TextView(host).apply {
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(6))
        }
        fun refreshStatus() {
            val entries = ResourceIndex.getAllEntries()
            val total = entries.values.sumOf { it.size }
            status.text = if (!ResourceIndex.isIndexed()) {
                t("Not indexed yet — ${projectDir.name}")
            } else {
                t("$total resources across ${entries.size} types — ${projectDir.name}")
            }
            status.setTextColor(
                StudioUi.color(if (ResourceIndex.isIndexed()) StudioUi.Green else StudioUi.Amber)
            )
        }
        refreshStatus()
        card.addView(status)

        // Grouped type list -------------------------------------------------
        val typeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(typeList)
        fun refreshTypes() {
            typeList.removeAllViews()
            val entries = ResourceIndex.getAllEntries()
                .filterValues { it.isNotEmpty() }
                .toSortedMap(compareByDescending<ResourceIndex.ResourceType> { it.ordinal })
            if (entries.isEmpty()) {
                typeList.addView(TextView(host).apply {
                    text = t("No resources indexed yet.")
                    textSize = 12f
                    setTextColor(StudioUi.color(StudioUi.Muted))
                    setPadding(0, 0, 0, dp(6))
                })
                return
            }
            entries.forEach { (type, list) ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = StudioUi.rounded(
                        host, fill = StudioUi.Surface, stroke = StudioUi.Border, radiusDp = 6
                    )
                }
                row.addView(TextView(host).apply {
                    text = type.name
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(StudioUi.color(StudioUi.Blue))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(TextView(host).apply {
                    text = list.size.toString()
                    textSize = 12f
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                })
                row.setOnClickListener { showResourceList(type, list) }
                typeList.addView(row)
                typeList.addView(spacer(dp(4)))
            }
        }
        refreshTypes()

        // Search ------------------------------------------------------------
        card.addView(spacer(dp(8)))
        val query = EditText(this).apply {
            hint = t("Search resources (e.g. ic_launcher)")
            setTextColor(StudioUi.color(StudioUi.Text))
            setHintTextColor(StudioUi.color(StudioUi.Muted))
            background = StudioUi.inputBg(host)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            textSize = 13f
        }
        card.addView(query)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(spacer(dp(6)))
        card.addView(results)

        fun showResults(list: List<ResourceIndex.ResourceEntry>) {
            results.removeAllViews()
            if (list.isEmpty()) {
                results.addView(TextView(host).apply {
                    text = t("No matching resources.")
                    textSize = 12f
                    setTextColor(StudioUi.color(StudioUi.Muted))
                })
                return
            }
            list.take(50).forEach { entry ->
                results.addView(TextView(host).apply {
                    text = "${entry.type.name.lowercase()}/${entry.name}\n${shortPath(entry.filePath)}"
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setTextColor(StudioUi.color(StudioUi.Text))
                    background = StudioUi.rounded(
                        host, fill = StudioUi.Surface, stroke = StudioUi.Border, radiusDp = 6
                    )
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    setOnClickListener { openProjectFile(File(entry.filePath)) }
                })
                results.addView(spacer(dp(4)))
            }
        }

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("Search") {
                val q = query.text.toString().trim()
                if (q.isEmpty()) {
                    toast("Type a resource name first")
                    return@primaryButton
                }
                showResults(ResourceIndex.searchResources(q, null, 50))
            })
            addView(secondaryButton("Index Project") {
                toast("Indexing resources...")
                ResourceIndex.indexProject(projectDir) {
                    host.runOnUiThread {
                        refreshStatus()
                        refreshTypes()
                        results.removeAllViews()
                    }
                }
            })
            addView(secondaryButton("Refresh") {
                refreshStatus()
                refreshTypes()
            })
        })
        return card
    }

    /** Dialog listing every entry of one resource type; tap to open the file. */
    private fun showResourceList(
        type: ResourceIndex.ResourceType,
        entries: List<ResourceIndex.ResourceEntry>
    ) {
        val labels = entries.map { "${it.name}  ·  ${shortPath(it.filePath)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${type.name} (${entries.size})")
            .setItems(labels) { _, which -> openProjectFile(File(entries[which].filePath)) }
            .setNegativeButton(t("Close"), null)
            .show()
    }

    private fun shortPath(path: String): String {
        val root = currentProjectDirOrNull()?.absolutePath
        return if (root != null && path.startsWith(root)) path.removePrefix(root).removePrefix("/")
        else path
    }

    // ------------------------------------------------------------
    // AGP Upgrade Assistant
    // ------------------------------------------------------------

    /** Current Android Gradle Plugin version declared by the open project. */
    private fun projectAgpVersion(root: File): String {
        val toml = File(root, "gradle/libs.versions.toml")
        if (toml.isFile) {
            Regex("""(?m)^\s*agp\s*=\s*"([^"]+)"""")
                .find(toml.readText())?.let { return it.groupValues[1] }
        }
        for (name in listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")) {
            val file = File(root, name)
            if (!file.isFile) continue
            val text = file.readText()
            Regex("""id\(\s*"com\.android\.(?:application|library)"\s*\)\s*version\s*"([^"]+)"""")
                .find(text)?.let { return it.groupValues[1] }
            Regex("""classpath\s*\(?\s*["']com\.android\.tools\.build:gradle:([^"']+)["']""")
                .find(text)?.let { return it.groupValues[1] }
        }
        return ""
    }

    /** Gradle wrapper version of the open project (`gradle-9.7.0` -> "9.7.0"). */
    private fun projectGradleVersion(root: File): String {
        val props = File(root, "gradle/wrapper/gradle-wrapper.properties")
        if (!props.isFile) return ""
        return Regex("""gradle-([0-9][0-9A-Za-z.\-_]*)-(?:bin|all)\.zip""")
            .find(props.readText())?.groupValues?.get(1) ?: ""
    }

    /**
     * Tools > AGP Upgrade Assistant. Reads the project's real AGP / Gradle /
     * Kotlin versions, compares them against the recommended stack, and rewrites
     * the build files through [ToolchainSyncManager] when the upgrade is applied.
     */
    private fun buildAgpUpgradeCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("AGP Upgrade Assistant"))

        val root = currentProjectDirOrNull()
        if (root == null) {
            card.addView(TextView(host).apply {
                text = t("Open a project to inspect its Android Gradle Plugin version.")
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, 0, 0, dp(8))
            })
            card.addView(primaryButton("Open Project") {
                FileManagerActivity.start(host, projectMode = true)
            })
            return card
        }

        val currentAgp = projectAgpVersion(root)
        val currentGradle = projectGradleVersion(root)
        val defaultGradle = ToolchainManager.DEFAULT_GRADLE.removePrefix("gradle-")
        val targetGradle = defaultGradle
        val targetAgp = ToolchainSyncManager.agpVersionForGradle(ToolchainManager.DEFAULT_GRADLE)
        val targetKotlin = ToolchainSyncManager.kotlinVersionForGradle(ToolchainManager.DEFAULT_GRADLE)

        fun info(label: String, value: String, ok: Boolean? = null): TextView =
            TextView(host).apply {
                text = t("$label: ${value.ifBlank { "not declared" }}")
                textSize = 12f
                setTextColor(
                    StudioUi.color(
                        when (ok) {
                            true -> StudioUi.Green
                            false -> StudioUi.Amber
                            null -> StudioUi.TextSoft
                        }
                    )
                )
                setPadding(0, 0, 0, dp(4))
            }

        card.addView(info("AGP", currentAgp, currentAgp == targetAgp))
        card.addView(info("Gradle wrapper", currentGradle, currentGradle == targetGradle))
        card.addView(info("Recommended AGP", targetAgp))
        card.addView(info("Recommended Gradle", targetGradle))
        card.addView(info("Recommended Kotlin", targetKotlin))

        val warnings = if (currentGradle.isBlank()) emptyList()
        else CompatibilityChecker.check(
            ToolchainManager.Kind.GRADLE,
            currentGradle,
            targetGradle
        )
        if (warnings.isNotEmpty()) {
            card.addView(spacer(dp(6)))
            warnings.forEach { w ->
                card.addView(TextView(host).apply {
                    text = "• ${t(w)}"
                    textSize = 12f
                    setTextColor(StudioUi.color(StudioUi.Amber))
                    setPadding(0, 0, 0, dp(4))
                })
            }
        }

        val upToDate = currentAgp == targetAgp && currentGradle == targetGradle
        card.addView(TextView(host).apply {
            text = t(
                if (upToDate) "This project already uses the recommended build stack."
                else "Upgrading rewrites gradle-wrapper.properties, AGP and Kotlin versions in place."
            )
            textSize = 12f
            setTextColor(StudioUi.color(if (upToDate) StudioUi.Green else StudioUi.TextSoft))
            setPadding(0, dp(6), 0, dp(8))
        })

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton(if (upToDate) "Reapply Stack" else "Upgrade") {
                confirmDialog(
                    "Upgrade this project to AGP $targetAgp, Gradle $targetGradle and Kotlin $targetKotlin?"
                ) {
                    scope.launch(Dispatchers.IO) {
                        ToolchainSyncManager.syncGradleWrapperVersion(root, ToolchainManager.DEFAULT_GRADLE)
                        ToolchainSyncManager.syncAgpVersion(root, targetAgp)
                        ToolchainSyncManager.syncKotlinVersion(root, targetKotlin)
                        withContext(Dispatchers.Main) {
                            toast("Build files updated to AGP $targetAgp / Gradle $targetGradle")
                            renderCategory(currentCategory)
                        }
                    }
                }
            })
            addView(secondaryButton("Refresh") { renderCategory(currentCategory) })
            addView(secondaryButton("Open Build Files") {
                val buildFile = listOf(
                    File(root, "app/build.gradle.kts"),
                    File(root, "app/build.gradle"),
                    File(root, "build.gradle.kts"),
                    File(root, "build.gradle")
                ).firstOrNull { it.isFile }
                if (buildFile == null) toast("No build script found")
                else if (!openProjectFile(buildFile)) showTextDialog(buildFile.name, buildFile.readText())
            })
        })
        return card
    }

    // ------------------------------------------------------------
    // Firebase
    // ------------------------------------------------------------

    private fun projectUsesPlugin(root: File, pluginId: String): Boolean =
        listOf("settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle")
            .map { File(root, it) }
            .filter { it.isFile }
            .any { it.readText().contains(pluginId) }

    private fun firebaseDependencyCount(root: File): Int =
        root.walkTopDown()
            .onEnter { it.name !in setOf(".gradle", ".idea", "build", ".git", "node_modules", ".cxx", "out") }
            .filter { it.name == "build.gradle" || it.name == "build.gradle.kts" }
            .sumOf { file -> Regex("""com\.google\.firebase:[a-z0-9\-]+""").findAll(file.readText()).count() }

    /**
     * Tools > Firebase. Reports what the open project actually contains
     * (google-services plugin, google-services.json, Firebase dependencies)
     * and can apply the missing Gradle wiring.
     */
    private fun buildFirebaseCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Firebase"))

        val root = currentProjectDirOrNull()
        if (root == null) {
            card.addView(TextView(host).apply {
                text = t("Open a project to check its Firebase setup.")
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, 0, 0, dp(8))
            })
            card.addView(primaryButton("Open Project") {
                FileManagerActivity.start(host, projectMode = true)
            })
            return card
        }

        val hasPlugin = projectUsesPlugin(root, "com.google.gms:google-services")
        val json = listOf(File(root, "app/google-services.json"), File(root, "google-services.json"))
            .firstOrNull { it.isFile }
        val depCount = firebaseDependencyCount(root)

        fun row(label: String, present: Boolean, detail: String): TextView =
            TextView(host).apply {
                text = "${if (present) "\u2713" else "\u2717"}  $label — $detail"
                textSize = 12f
                setTextColor(StudioUi.color(if (present) StudioUi.Green else StudioUi.Amber))
                setPadding(0, 0, 0, dp(4))
            }

        card.addView(row("google-services plugin", hasPlugin,
            if (hasPlugin) "applied" else "not applied"))
        card.addView(row("google-services.json", json != null,
            json?.let { shortPath(it.absolutePath) } ?: "missing in app/"))
        card.addView(row("Firebase dependencies", depCount > 0,
            if (depCount > 0) "$depCount declared" else "none declared"))

        card.addView(spacer(dp(8)))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("Apply Firebase Setup") {
                confirmDialog(
                    "Add the google-services plugin and Firebase BoM to this project's Gradle files?"
                ) {
                    scope.launch(Dispatchers.IO) {
                        val result = applyFirebaseSetup(root)
                        withContext(Dispatchers.Main) {
                            toast(result)
                            renderCategory(currentCategory)
                        }
                    }
                }
            })
            addView(secondaryButton("Refresh") { renderCategory(currentCategory) })
            addView(secondaryButton("Console") {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://console.firebase.google.com/")))
                }.onFailure { toast("No browser available") }
            })
        })

        if (json == null) {
            card.addView(spacer(dp(6)))
            card.addView(TextView(host).apply {
                text = t("Download google-services.json from the Firebase console and place it in app/.")
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.Muted))
            })
        }
        return card
    }

    /**
     * Adds the missing google-services classpath/plugin and the Firebase BoM.
     * Returns a human-readable summary of exactly what changed.
     */
    private fun applyFirebaseSetup(root: File): String {
        val changed = mutableListOf<String>()

        val rootBuild = listOf(File(root, "build.gradle.kts"), File(root, "build.gradle"))
            .firstOrNull { it.isFile }
        val appBuild = listOf(File(root, "app/build.gradle.kts"), File(root, "app/build.gradle"))
            .firstOrNull { it.isFile }

        if (rootBuild != null && !rootBuild.readText().contains("com.google.gms:google-services")) {
            val kts = rootBuild.name.endsWith(".kts")
            val text = rootBuild.readText()
            val snippet = if (kts) {
                if (text.contains("plugins {")) {
                    Regex("""plugins\s*\{""").replace(text) { it.value + "\n    id(\"com.google.gms:google-services\") version \"4.4.2\" apply false" }
                } else {
                    "plugins {\n    id(\"com.google.gms:google-services\") version \"4.4.2\" apply false\n}\n" + text
                }
            } else {
                if (text.contains("buildscript") || text.contains("dependencies")) {
                    Regex("""(buildscript\s*\{|dependencies\s*\{)""").replace(text) { it.value + "\n        classpath 'com.google.gms:google-services:4.4.2'" }
                } else {
                    "buildscript {\n    dependencies {\n        classpath 'com.google.gms:google-services:4.4.2'\n    }\n}\n" + text
                }
            }
            rootBuild.writeText(snippet)
            changed += "google-services classpath in ${rootBuild.name}"
        }

        if (appBuild != null) {
            val kts = appBuild.name.endsWith(".kts")
            val text = appBuild.readText()
            var updated = text
            if (!text.contains("com.google.gms:google-services")) {
                updated = if (kts) {
                    Regex("""plugins\s*\{""").replace(updated) { it.value + "\n    id(\"com.google.gms:google-services\")" }
                } else {
                    Regex("""apply plugin:\s*['"]com\.android\.application['"]""")
                        .replace(updated) { it.value + "\napply plugin: 'com.google.gms:google-services'" }
                }
                if (updated == text && !kts) {
                    updated = "apply plugin: 'com.google.gms:google-services'\n" + updated
                }
                if (updated != text) changed += "plugin in ${appBuild.name}"
                appBuild.writeText(updated)
            }
            val after = appBuild.readText()
            if (!after.contains("com.google.firebase:firebase-bom")) {
                val dep = if (kts) {
                    "    implementation(platform(\"com.google.firebase:firebase-bom:33.7.0\"))"
                } else {
                    "    implementation platform('com.google.firebase:firebase-bom:33.7.0')"
                }
                val withDep = if (after.contains("dependencies {")) {
                    Regex("""dependencies\s*\{""").replace(after) { it.value + "\n$dep" }
                } else {
                    after + "\ndependencies {\n$dep\n}\n"
                }
                appBuild.writeText(withDep)
                changed += "Firebase BoM in ${appBuild.name}"
            }
        }

        return if (changed.isEmpty()) "Firebase setup already present"
        else "Updated: " + changed.joinToString(", ")
    }

    private fun buildLogcatCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle(AppLanguage.text(host, "logcat_viewer").toString()))
        card.addView(TextView(host).apply {
            text = t("View logs from apps built by MobileStudio")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        val input = EditText(host).apply {
            hint = t("Package name (e.g. com.example.myapp)")
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = StudioUi.inputBg(host)
            setTextColor(StudioUi.color(StudioUi.Text))
            setHintTextColor(StudioUi.color(StudioUi.Muted))
        }
        card.addView(input)
        card.addView(spacer(dp(8)))
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(primaryButton("Open Logcat") {
                val pkg = input.text.toString().trim().ifBlank { host.packageName }
                LogcatActivity.start(host, pkg)
            })
            addView(secondaryButton("View MobileStudio Logs") {
                LogcatActivity.start(host, host.packageName)
            })
        })
        return card
    }

    private fun buildAppIconCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("App Icon Editor"))
        card.addView(TextView(host).apply {
            text = t("Select an image to auto-generate mipmap icons for all densities")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(primaryButton("Open Icon Editor") {
            val projectDir = currentProjectDirOrNull()?.absolutePath ?: run {
                toast("Open a project first")
                return@primaryButton
            }
            val intent = android.content.Intent(host, AppIconEditorActivity::class.java)
            intent.putExtra("project_dir", projectDir)
            val pkg = readPackageNameFromGradle(File(projectDir))
            intent.putExtra("package_name", pkg)
            startActivity(intent)
        })
        return card
    }

    private fun buildApkToolsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("APK Tools"))
        card.addView(TextView(host).apply {
            text = t("Sign, inspect, and install APK files")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnRow.addView(primaryButton("Sign APK") {
            val projectDir = currentProjectDirOrNull()?.absolutePath ?: run {
                toast("Open a project first")
                return@primaryButton
            }
            val intent = android.content.Intent(host, ApkSigningActivity::class.java)
            intent.putExtra("project_dir", projectDir)
            startActivity(intent)
        })
        btnRow.addView(secondaryButton("APK Info") {
            val projectDir = currentProjectDirOrNull()?.absolutePath ?: run {
                toast("Open a project first")
                return@secondaryButton
            }
            val apkDir = File(projectDir, "app/build/outputs/apk")
            val latestApk = BuildApkLocator.latestInstallableApk(apkDir)
            if (latestApk == null) {
                toast("No APK files found")
                return@secondaryButton
            }
            val intent = android.content.Intent(host, ApkInfoActivity::class.java)
            intent.putExtra("apk_path", latestApk.absolutePath)
            startActivity(intent)
        })
        card.addView(btnRow)
        return card
    }

    private fun readPackageNameFromGradle(projectDir: File): String {
        val gradleFile = listOf(
            File(projectDir, "app/build.gradle.kts"),
            File(projectDir, "app/build.gradle")
        ).firstOrNull { it.exists() } ?: return "com.example.app"
        val text = gradleFile.readText()
        val regex = Regex("""applicationId\s*[=(]?\s*['"]([^'"]+)['"]""")
        return regex.find(text)?.groupValues?.get(1) ?: "com.example.app"
    }

    private fun buildDependencySearchCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Dependency Search"))
        card.addView(TextView(host).apply {
            text = t("Search Maven Central and add libraries to build.gradle")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(primaryButton("Open Dependency Search") {
            val projectDir = currentProjectDirOrNull() ?: run {
                toast("Open a project first")
                return@primaryButton
            }
            DependencySearchDialog(host).show(projectDir)
        })
        return card
    }

    private fun buildCacheManagerCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Build Cache Manager"))
        card.addView(TextView(host).apply {
            text = t("Manage build cache, Gradle cache, and dependency cache")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(primaryButton("Open Cache Manager") {
            BuildCacheManager(host).show(currentProjectDirOrNull())
        })
        return card
    }

    private fun buildDiagnosticsCard(): View {
        val prefs = getSharedPreferences("ide", MODE_PRIVATE)
        val crashCount = MobileStudioCrashGuard.crashCount(this)
        val cacheBytes = directorySize(cacheDir) + directorySize(codeCacheDir)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Diagnostics & Performance"))
        card.addView(TextView(this).apply {
            text = t("Crash logs: $crashCount transient cache: ${formatLocalBytes(cacheBytes)}")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(6))
        })
        run {
            val memoryBudget = BuildMemoryPolicy.resolve(this)
            card.addView(TextView(this).apply {
                text = t(
                    "Device RAM: ${memoryBudget.deviceRamMb} MB App heap limit: ${memoryBudget.appLargeHeapMb} MB Gradle heap: ${memoryBudget.gradleHeapMb} MB"
                )
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, 0, 0, dp(6))
            })
            card.addView(TextView(this).apply {
                text = t("Gradle build memory (more heap for large projects)")
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.Text))
                setPadding(0, 0, 0, dp(4))
            })
            val activeProfile = BuildMemoryPolicy.profile(this)
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                BuildMemoryPolicy.Profile.entries.forEach { profile ->
                    addView(profileMemoryButton(profile, profile == activeProfile) {
                        BuildMemoryPolicy.setProfile(host, profile)
                        val next = BuildMemoryPolicy.resolve(host)
                        toast(
                            t(
                                "Gradle heap set to ${next.gradleHeapMb} MB (${gradleMemoryProfileLabel(profile)})"
                            )
                        )
                        renderAll()
                    })
                }
            })
        }
        card.addView(CheckBox(this).apply {
            text = t("Performance mode: Gradle uses 2 workers when possible")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.Text))
            isChecked = prefs.getBoolean("performance_mode", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("performance_mode", checked).apply()
                toast(if (checked) "Performance mode enabled" else "Cool mode enabled")
            }
        })
        card.addView(CheckBox(this).apply {
            text = t("Verbose build logs: keep Gradle debug output visible")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.Text))
            isChecked = prefs.getBoolean("verbose_build_logs", false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("verbose_build_logs", checked).apply()
                toast(if (checked) "Verbose logs enabled" else "Verbose logs disabled")
            }
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(secondaryButton("View Crash") {
                showTextDialog("Latest crash log", MobileStudioCrashGuard.latestCrashText(host))
            })
            addView(secondaryButton("Copy Crash") {
                copyToClipboard("MobileStudio crash log", MobileStudioCrashGuard.latestCrashText(host))
            })
            addView(dangerButton("Clear Crash") {
                confirmDialog("Clear saved crash logs?") {
                    MobileStudioCrashGuard.clearCrashLogs(host)
                    toast("Crash logs cleared")
                    renderAll()
                }
            })
            addView(dangerButton("Trim Cache") {
                confirmDialog("Clear transient previews, proxy launchers, and temporary downloads?") {
                    trimTransientCaches()
                    toast("Transient cache trimmed")
                    renderAll()
                }
            })
        })
        return card
    }

    private fun buildBuildOptionsCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Build Options"))
        val cfg = BuildToolsConfig
        val summary = buildString {
            append("Gradle: ${cfg.gradleHeapMb(host)}MB heap, ")
            append("${cfg.maxWorkers(host)} workers, ")
            append("parallel=${cfg.parallel(host)}\n")
            append("Clang: ${cfg.optimization(host)}, API ${cfg.targetApi(host)}\n")
            append("Kotlin: ${cfg.kotlinHeapMb(host)}MB heap")
        }
        card.addView(TextView(this).apply {
            text = t(summary)
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(primaryButton("Open Build Options") {
            BuildToolsSettingsActivity.start(host)
        })
        return card
    }

    private fun buildLayoutLibCard(): View {
        val status = layoutLibImporter.status()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(host).apply {
                text = t("LayoutLib Preview")
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(StudioUi.color(StudioUi.Blue))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(host).apply {
                text = t(if (status.installed) "Ready" else "Not installed")
                textSize = 12f
                setTextColor(StudioUi.color(if (status.installed) StudioUi.Green else StudioUi.Muted))
            })
        })
        card.addView(TextView(this).apply {
            text = t(
                if (status.installed) {
                    "Android Studio LayoutLib runtime installed - ${status.jarCount} jars - ${status.sizeBytes / 1024 / 1024}MB"
                } else {
                    "Install bundled LayoutLib jars from APK assets, or import them from an Android Studio ZIP."
                }
            )
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, dp(6), 0, dp(8))
        })
        if (!status.installed && status.missing.isNotEmpty()) {
            card.addView(TextView(this).apply {
                text = t("Missing: ${status.missing.joinToString()}")
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.Red))
                setPadding(0, 0, 0, dp(8))
            })
        }
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(primaryButton("Install Bundled") { installBundledLayoutLib() })
            addView(secondaryButton("Import Android Studio ZIP") { startLayoutLibZipPicker() })
            addView(secondaryButton("Refresh") { renderAll() })
            if (status.jarCount > 0) {
                addView(dangerButton("Delete") {
                    confirmDialog("Delete LayoutLib runtime?") {
                        layoutLibImporter.clear()
                        toast("LayoutLib runtime deleted")
                        renderAll()
                    }
                })
            }
        })
        return card
    }

    private fun buildRuntimeToolsCard(): View {
        val installed = RuntimeToolsInstaller.areAllToolsInstalled(this)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Runtime Tools"))
        val toolList = RuntimeToolsInstaller.ALL_TOOLS.values.joinToString(", ") { it.name }
        card.addView(TextView(this).apply {
            text = "$toolList: ${if (installed) "installed" else "not installed"}"
            textSize = 12f
            setTextColor(StudioUi.color(if (installed) StudioUi.Green else StudioUi.Muted))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(primaryButton(if (installed) "Reinstall All" else "Install All") {
                scope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.Main) { toast("Installing runtime tools...") }
                    RuntimeToolsInstaller.installAll(host) { msg ->
                        host.runOnUiThread { toast(msg) }
                    }
                    withContext(Dispatchers.Main) {
                        toast("Runtime tools install complete")
                        renderAll()
                    }
                }
            })
            addView(secondaryButton("Open Folder") {
                val dir = RuntimeToolsInstaller.binDir(host)
                ToolchainFileExplorerActivity.startAt(host, "Runtime Tools", dir)
            })
            addView(secondaryButton("Refresh") { renderAll() })
        })
        return card
    }

    /**
     * Android Studio-style SDK Manager card ("Settings > Android SDK"):
     * SDK location row, functional tab strip (SDK Platforms / SDK Tools /
     * SDK Update Sites), and per-tab content backed by the real on-device SDK.
     */
    private fun buildStudioStyleSdkCard(): View {
        val sdkDir = toolchain.activeDir(ToolchainManager.Kind.SDK)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("Languages & Frameworks > Android SDK"))
        card.addView(TextView(this).apply {
            text = t("Manager for the Android SDK, platforms, and developer tools used by the IDE")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        // SDK location: label + Edit on one line, the path wraps below so it
        // stays readable in the narrow tool window.
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(host).apply {
                text = t("Android SDK Location:")
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.Text))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(secondaryButton("Edit") {
                ToolchainFileExplorerActivity.start(host, ToolchainManager.Kind.SDK)
            })
        })
        card.addView(TextView(host).apply {
            text = sdkDir?.absolutePath ?: t("Not selected")
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(StudioUi.Text))
            background = StudioUi.inputBg(host)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        })

        // Tab strip — clickable, blue underline on the selected tab (IntelliJ style).
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
            listOf("SDK Platforms", "SDK Tools", "SDK Update Sites").forEachIndexed { index, title ->
                addView(LinearLayout(host).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(2), 0, dp(14), 0)
                    addView(TextView(host).apply {
                        text = t(title)
                        textSize = 13f
                        setTypeface(null, if (index == sdkManagerTab) Typeface.BOLD else Typeface.NORMAL)
                        setTextColor(StudioUi.color(if (index == sdkManagerTab) StudioUi.Blue else StudioUi.TextSoft))
                        setPadding(dp(6), dp(4), dp(6), dp(4))
                    })
                    addView(View(host).apply {
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
                        if (index == sdkManagerTab) {
                            setBackgroundColor(StudioUi.color(StudioUi.Blue))
                        }
                    })
                    setOnClickListener {
                        if (sdkManagerTab != index) {
                            sdkManagerTab = index
                            renderCategory(currentCategory)
                        }
                    }
                })
            }
        })
        card.addView(View(this).apply {
            setBackgroundColor(StudioUi.color(StudioUi.Border))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        })

        when (sdkManagerTab) {
            0 -> card.addView(buildSdkPlatformsBody(sdkDir))
            1 -> card.addView(buildSdkToolsBody(sdkDir))
            else -> card.addView(buildSdkUpdateSitesBody())
        }
        return card
    }

    // ------------------------------------------------------------
    // SDK Manager > SDK Platforms
    // ------------------------------------------------------------
    private fun buildSdkPlatformsBody(sdkDir: File?): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        if (sdkDir == null) {
            body.addView(sdkBodyNote("Android SDK not found. Install an SDK version from the SDK & Dev Tools card first."))
            return body
        }
        val installed = File(sdkDir, "platforms").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") && File(it, "android.jar").exists() }
            ?.map { it.name }
            ?: emptyList()
        val installedSet = installed.toSet()
        val available = toolchain.availableVersions(ToolchainManager.Kind.SDK)
            .filter { it.startsWith("android-") && it !in installedSet }
            .takeLast(10)

        body.addView(sdkGroupLabel("Installed"))
        if (installed.isEmpty()) {
            body.addView(sdkBodyNote("No platforms installed yet. Install one from the Available list below."))
        } else {
            installed.sortedByDescending { sdkApiLevel(it) }.forEach {
                body.addView(sdkPlatformRow(it, installed = true))
            }
        }
        body.addView(sdkGroupLabel("Available"))
        if (available.isEmpty()) {
            body.addView(sdkBodyNote("No additional platforms available from the configured update sites."))
        } else {
            available.sortedByDescending { sdkApiLevel(it) }.forEach {
                body.addView(sdkPlatformRow(it, installed = false))
            }
        }
        return body
    }

    private fun sdkApiLevel(platformName: String): Int =
        platformName.substringAfter("android-", "0").toIntOrNull() ?: 0

    private fun sdkPlatformRow(name: String, installed: Boolean): View {
        val api = sdkApiLevel(name)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(CheckBox(host).apply {
                isChecked = installed
                isEnabled = false
                setPadding(0, 0, dp(6), 0)
            })
            addView(TextView(host).apply {
                text = t("Android $api")
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.Text))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(host).apply {
                text = t(if (installed) "Installed" else "Not installed")
                textSize = 12f
                setTextColor(StudioUi.color(if (installed) StudioUi.Green else StudioUi.Muted))
            })
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(host).apply {
                text = "API $api · platforms/$name"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(StudioUi.Muted))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(if (installed) {
                secondaryButton("SDK Install") {
                    runSdkManager(activeSdkVersion(), listOf(
                        "platform-tools",
                        "platforms;$name",
                        "build-tools;${buildToolsVersionForSdk(name)}",
                    )) { renderCategory(currentCategory) }
                }
            } else {
                primaryButton("Install") {
                    runSdkManager(activeSdkVersion(), listOf(
                        "platforms;$name",
                        "platform-tools",
                        "build-tools;${buildToolsVersionForSdk(name)}",
                    )) { renderCategory(currentCategory) }
                }
            })
        })
        return row
    }

    // ------------------------------------------------------------
    // SDK Manager > SDK Tools
    // ------------------------------------------------------------
    private fun buildSdkToolsBody(sdkDir: File?): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        if (sdkDir == null) {
            body.addView(sdkBodyNote("Android SDK not found. Install an SDK version from the SDK & Dev Tools card first."))
            return body
        }
        val sdkName = activeSdkVersion()

        // Android SDK Platform-Tools
        val ptInstalled = File(sdkDir, "platform-tools/adb").exists()
        body.addView(sdkToolRow(
            name = "Android SDK Platform-Tools",
            version = if (ptInstalled) {
                sdkPkgRevision(File(sdkDir, "platform-tools/source.properties")) ?: ""
            } else "",
            installed = ptInstalled,
            detail = "adb, fastboot and the device debug bridge",
            onInstall = { runSdkManager(sdkName, listOf("platform-tools")) { renderCategory(currentCategory) } },
        ))

        // Android SDK Build-Tools
        val buildTools = File(sdkDir, "build-tools").listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.sortedDescending()
            ?: emptyList()
        body.addView(sdkToolRow(
            name = "Android SDK Build-Tools",
            version = buildTools.firstOrNull() ?: "",
            installed = buildTools.isNotEmpty(),
            detail = if (buildTools.isNotEmpty()) {
                "Installed: ${buildTools.joinToString()}"
            } else {
                "aapt2, d8 and the APK packaging tools"
            },
            onInstall = {
                runSdkManager(sdkName, listOf("build-tools;${buildToolsVersionForSdk(sdkName)}")) {
                    renderCategory(currentCategory)
                }
            },
        ))

        // NDK (Side by side)
        val ndkVersions = toolchain.installedVersions(ToolchainManager.Kind.NDK)
        body.addView(sdkToolRow(
            name = "NDK (Side by side)",
            version = ndkVersions.takeLast(2).joinToString(),
            installed = ndkVersions.isNotEmpty(),
            detail = if (ndkVersions.isNotEmpty()) {
                "Installed: ${ndkVersions.joinToString()}"
            } else {
                "Native toolchain for C/C++ code (arm64-v8a)"
            },
            onInstall = {
                showSdkVersionPickDialog(
                    "Install NDK",
                    toolchain.availableVersions(ToolchainManager.Kind.NDK),
                ) { version -> installNdk(version) }
            },
        ))

        // CMake
        val cmakeVersions = toolchain.installedVersions(ToolchainManager.Kind.CMAKE)
        body.addView(sdkToolRow(
            name = "CMake",
            version = cmakeVersions.takeLast(2).joinToString(),
            installed = cmakeVersions.isNotEmpty(),
            detail = if (cmakeVersions.isNotEmpty()) {
                "Installed: ${cmakeVersions.joinToString()}"
            } else {
                "Build system for native projects"
            },
            onInstall = {
                showSdkVersionPickDialog(
                    "Install CMake",
                    toolchain.availableVersions(ToolchainManager.Kind.CMAKE),
                ) { version -> installCmake(version) }
            },
        ))

        // Android Emulator
        val emulatorInstalled = File(sdkDir, "emulator").isDirectory
        body.addView(sdkToolRow(
            name = "Android Emulator",
            version = if (emulatorInstalled) {
                sdkPkgRevision(File(sdkDir, "emulator/source.properties")) ?: ""
            } else "",
            installed = emulatorInstalled,
            detail = "Virtual device host for running Android images",
            onInstall = { runSdkManager(sdkName, listOf("emulator")) { renderCategory(currentCategory) } },
        ))

        // Sources for Android SDK
        val sources = File(sdkDir, "sources").listFiles()?.filter { it.isDirectory } ?: emptyList()
        val latestApi = File(sdkDir, "platforms").listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") }
            ?.maxOfOrNull { sdkApiLevel(it.name) }
            ?: 35
        body.addView(sdkToolRow(
            name = "Sources for Android SDK",
            version = sources.map { it.name.removePrefix("android-") }.sortedDescending().firstOrNull() ?: "",
            installed = sources.isNotEmpty(),
            detail = if (sources.isNotEmpty()) {
                "Installed API sources: ${sources.joinToString { it.name }}"
            } else {
                "Android framework source for code insight"
            },
            onInstall = {
                runSdkManager(sdkName, listOf("sources;android-$latestApi")) { renderCategory(currentCategory) }
            },
        ))

        // Android SDK Command-line Tools
        val cmdline = File(sdkDir, "cmdline-tools").listFiles()?.filter { it.isDirectory } ?: emptyList()
        body.addView(sdkToolRow(
            name = "Android SDK Command-line Tools",
            version = cmdline.joinToString { it.name },
            installed = cmdline.isNotEmpty(),
            detail = if (cmdline.isNotEmpty()) {
                "sdkmanager, avdmanager and lint"
            } else {
                "Latest command-line tools, sdkmanager and avdmanager"
            },
            onInstall = {
                runSdkManager(sdkName, listOf("cmdline-tools;latest")) { renderCategory(currentCategory) }
            },
        ))
        return body
    }

    private fun sdkToolRow(
        name: String,
        version: String,
        installed: Boolean,
        detail: String,
        onInstall: (() -> Unit)?,
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(CheckBox(host).apply {
                isChecked = installed
                isEnabled = false
                setPadding(0, 0, dp(6), 0)
            })
            addView(TextView(host).apply {
                text = t(name)
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.Text))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(host).apply {
                text = version
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(if (installed) StudioUi.TextSoft else StudioUi.Muted))
            })
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(host).apply {
                text = t(if (installed) "Installed · $detail" else detail)
                textSize = 11f
                setTextColor(StudioUi.color(if (installed) StudioUi.Green else StudioUi.Muted))
                maxLines = 2
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!installed && onInstall != null) {
                addView(primaryButton("Install", onInstall))
            }
        })
        return row
    }

    private fun sdkPkgRevision(sourceProperties: File): String? = runCatching {
        sourceProperties.readLines()
            .firstOrNull { it.startsWith("Pkg.Revision") }
            ?.substringAfter('=')?.trim()
    }.getOrNull()

    private fun activeSdkVersion(): String =
        toolchain.activeVersion(ToolchainManager.Kind.SDK)
            ?: toolchain.installedVersions(ToolchainManager.Kind.SDK).lastOrNull()
            ?: "android-35"

    private fun showSdkVersionPickDialog(title: String, versions: List<String>, onPick: (String) -> Unit) {
        if (versions.isEmpty()) {
            toast("No versions available from the update sites yet")
            return
        }
        val newestFirst = versions.reversed().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(t(title))
            .setItems(newestFirst) { _, index -> onPick(newestFirst[index]) }
            .setNegativeButton(t("Cancel"), null)
            .show()
    }

    private fun sdkGroupLabel(text: String): TextView =
        TextView(this).apply {
            this.text = t(text)
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, dp(10), 0, dp(2))
        }

    private fun sdkBodyNote(text: String): TextView =
        TextView(this).apply {
            this.text = t(text)
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.Muted))
            setPadding(0, dp(4), 0, dp(8))
        }

    // ------------------------------------------------------------
    // SDK Manager > SDK Update Sites
    // ------------------------------------------------------------
    private fun buildSdkUpdateSitesBody(): View {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        body.addView(TextView(this).apply {
            text = t("Fetch installable JDK arm64, Gradle, SDK, NDK, and CMake versions from custom repositories.")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        ToolchainManager.Kind.values().forEach { kind -> body.addView(updateSiteRow(kind)) }
        return body
    }

    private fun updateSiteRow(kind: ToolchainManager.Kind): View {
        val cached = updateSites.cachedVersions(kind)
        val sites = updateSites.sites(kind)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(8))
            addView(LinearLayout(host).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(host).apply {
                    text = kind.displayName
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(StudioUi.color(StudioUi.Text))
                    layoutParams = LinearLayout.LayoutParams(dp(70), ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                addView(View(host), LinearLayout.LayoutParams(0, 1, 1f))
                addView(secondaryButton("Add") { showAddUpdateSiteDialog(kind) })
                addView(secondaryButton("Edit") { showManageUpdateSitesDialog(kind) })
                addView(primaryButton("Fetch") { refreshUpdateSite(kind) })
            })
            addView(TextView(host).apply {
                text = sites.joinToString(separator = "\n")
                textSize = 10f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(StudioUi.Muted))
                setPadding(dp(70), dp(3), 0, 0)
            })
            addView(TextView(host).apply {
                text = t(if (cached.isEmpty()) "No remote versions loaded yet." else "Loaded: ${cached.take(10).joinToString()}${if (cached.size > 10) " ..." else ""}")
                textSize = 12f
                setTextColor(StudioUi.color(if (cached.isEmpty()) StudioUi.Muted else StudioUi.Green))
                setPadding(dp(70), dp(3), 0, 0)
            })
        }
    }

    private fun showAddUpdateSiteDialog(kind: ToolchainManager.Kind) {
        val input = EditText(this).apply {
            hint = "https://example.com/repository.xml"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(t("Add ${kind.displayName} update site"))
            .setView(input)
            .setPositiveButton(t("Add")) { _, _ ->
                updateSites.addSite(kind, input.text?.toString().orEmpty())
                renderAll()
            }
            .setNegativeButton(t("Cancel"), null)
            .show()
    }

    private fun showManageUpdateSitesDialog(kind: ToolchainManager.Kind) {
        val sites = updateSites.sites(kind)
        val labels = sites + t("Reset to defaults")
        AlertDialog.Builder(this)
            .setTitle(t("${kind.displayName} update sites"))
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == sites.size) {
                    updateSites.resetSites(kind)
                    renderAll()
                } else {
                    showEditUpdateSiteDialog(kind, sites[index])
                }
            }
            .setNegativeButton(t("Close"), null)
            .show()
    }

    private fun showEditUpdateSiteDialog(kind: ToolchainManager.Kind, oldUrl: String) {
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(oldUrl)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(t("Edit ${kind.displayName} site"))
            .setView(input)
            .setPositiveButton(t("Save")) { _, _ ->
                updateSites.replaceSite(kind, oldUrl, input.text?.toString().orEmpty())
                renderAll()
            }
            .setNeutralButton(t("Delete")) { _, _ ->
                updateSites.removeSite(kind, oldUrl)
                renderAll()
            }
            .setNegativeButton(t("Cancel"), null)
            .show()
    }

    private fun refreshUpdateSite(kind: ToolchainManager.Kind) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(t("Fetch ${kind.displayName} update sites"))
            .setMessage(t("Connecting..."))
            .setCancelable(false)
            .create()
        dialog.show()
        scope.launch(Dispatchers.IO) {
            val result = runCatching { updateSites.refresh(kind) }
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                result.onSuccess {
                    toast("${kind.displayName}: ${it.size} versions loaded")
                    renderAll()
                }.onFailure {
                    toast("${kind.displayName} update site failed: ${it.message?.take(120) ?: "unknown"}")
                }
            }
        }
    }

    private fun installBundledLayoutLib() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(t("Install Bundled LayoutLib"))
            .setMessage(t("Preparing..."))
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch(Dispatchers.IO) {
            val result = layoutLibImporter.ensureBundledInstalled { message ->
                host.runOnUiThread { dialog.setMessage(message) }
            }
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                toast(result.message)
                renderAll()
            }
        }
    }

    private fun startLayoutLibZipPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        @Suppress("DEPRECATION")
        runCatching { host.startActivityForResult(intent, REQ_IMPORT_LAYOUTLIB_ZIP) }
            .onFailure { toast("File picker unavailable: ${it.message}") }
    }

    private fun importLayoutLibZip(uri: Uri) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(t("Import LayoutLib"))
            .setMessage(t("Preparing..."))
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch(Dispatchers.IO) {
            val result = layoutLibImporter.importFromAndroidStudioZip(uri) { message ->
                host.runOnUiThread { dialog.setMessage(message) }
            }
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                toast(result.message)
                renderAll()
            }
        }
    }

    private fun buildLanguageCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val current = AppLanguage.current(this)
        card.addView(TextView(this).apply {
            text = AppLanguage.text(host, "language")
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(StudioUi.color(StudioUi.Blue))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(secondaryButton(current.nativeName) { showLanguagePicker() })
        return card
    }

    private fun buildAiCard(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle("AI Assistant"))
        card.addView(TextView(this).apply {
            text = t("AI coding agent powered by opencode (native aarch64)")
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(primaryButton("Open AI Workspace") {
                try {
                    AiWorkspaceActivity.start(host)
                } catch (e: Exception) {
                    toast("Failed to start AI: ${e.message}")
                }
            })
            addView(secondaryButton("Open TUI") {
                val cmd = OpencodeEnvironment(host).tuiCommandLine()
                runCatching {
                    startActivity(
                        Intent(host, MainActivity::class.java).apply {
                            putExtra("run_command", cmd)
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                    )
                }.onFailure { e ->
                    toast("TUI failed: ${e.message}")
                }
            })
            addView(dangerButton("Reinstall") {
                scope.launch(Dispatchers.IO) {
                    val env = OpencodeEnvironment(host)
                    env.cleanup()
                    withContext(Dispatchers.Main) { toast("AI environment cleaned. Reopen to reinstall.") }
                }
            })
        })
        return card
    }

    private fun showLanguagePicker() {
        val languages = AppLanguage.supported
        val current = AppLanguage.current(this).code
        AlertDialog.Builder(this)
            .setTitle(AppLanguage.text(this, "language"))
            .setSingleChoiceItems(
                languages.map { it.nativeName }.toTypedArray(),
                languages.indexOfFirst { it.code == current }
            ) { dialog, which ->
                AppLanguage.set(this, languages[which].code)
                dialog.dismiss()
                buildSidebar()
                renderAll()
            }
            .show()
    }

    private fun buildKindCard(kind: ToolchainManager.Kind): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val active = toolchain.activeVersion(kind)

        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(host).apply {
                text = kind.displayName
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(StudioUi.color(StudioUi.Blue))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(host).apply {
                text = active?.let { t("Active: $it") } ?: t("Not installed")
                textSize = 12f
                setTextColor(StudioUi.color(if (active != null) StudioUi.Green else StudioUi.Muted))
            })
        })

        card.addView(buildPathRow(kind))

        if (kind == ToolchainManager.Kind.NDK) {
            card.addView(TextView(this).apply {
                text = t("Real C/C++ compile needs Android-hosted LLVM: put clang/clang++ under <NDK>/toolchains/llvm/prebuilt/android-aarch64/bin or import a folder at files/toolchain/llvm/bin. The official linux-x86_64 NDK is kept only for sysroot/headers.")
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, dp(6), 0, 0)
            })
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
                addView(primaryButton("Download Android LLVM (NDK)") {
                    getSharedPreferences("ondevice_ai", MODE_PRIVATE)
                        .edit()
                        .putString("android_llvm_url", DEFAULT_ANDROID_LLVM_URL)
                        .apply()
                    confirmBackgroundDownload(
                        label = "Android LLVM (clang19 + NDK files)",
                        url = DEFAULT_ANDROID_LLVM_URL,
                        targetDir = File(filesDir, "toolchain/llvm"),
                        mode = ToolchainDownloadService.MODE_ANDROID_LLVM
                    )
                })
                addView(secondaryButton("Custom URL") {
                    showUrlDownloadDialog(
                        title = "Download Android-hosted LLVM",
                        hint = DEFAULT_ANDROID_LLVM_URL,
                        savedKey = "android_llvm_url",
                        targetDir = File(filesDir, "toolchain/llvm"),
                        mode = ToolchainDownloadService.MODE_ANDROID_LLVM,
                        label = "Android LLVM"
                    )
                })
                addView(dangerButton("Stop") { DownloadTask.cancel() })
            })
        }
        if (kind == ToolchainManager.Kind.PYTHON) {
            card.addView(TextView(this).apply {
                text = t("Install an Android arm64 Python distribution that contains bin/python3. After activation, Android Shell and AI tools can run python, pip, opencv-python, and project scripts from PATH.")
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, dp(6), 0, 0)
            })
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
                addView(primaryButton("Python + pip") {
                    showPythonVersionPicker("Python + pip")
                })
                addView(secondaryButton("OpenCV") {
                    showPythonProfileHelp(
                        "Python OpenCV",
                        "After installing Python, use the Shell/PIP panel:\n\npython3 -m pip install numpy\npython3 -m pip install opencv-python\n\nIf wheels are unavailable for Android, use an Android-specific OpenCV wheel/archive."
                    )
                })
                addView(secondaryButton("PIP Commands") {
                    showPythonProfileHelp(
                        "PIP Commands",
                        "python3 -m pip install --upgrade pip wheel setuptools\npython3 -m pip install requests rich pillow\npython3 -m pip freeze"
                    )
                })
            })
        }

        if (kind == ToolchainManager.Kind.CMAKE) {
            card.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, 0)
                addView(primaryButton("Reinstall ARM64 cmake") {
                    reinstallArm64Cmake()
                })
                addView(secondaryButton("Download ARM64 ninja") {
                    confirmBackgroundDownload(
                        label = "ARM64 ninja",
                        url = DEFAULT_NINJA_URL,
                        targetDir = File(filesDir, "toolchain/arm64-tools"),
                        mode = ToolchainDownloadService.MODE_ARM64_NINJA
                    )
                })
                addView(secondaryButton("Custom URL") {
                    showUrlDownloadDialog(
                        title = "Download ARM64 ninja",
                        hint = DEFAULT_NINJA_URL,
                        savedKey = "arm64_ninja_url",
                        targetDir = File(filesDir, "toolchain/arm64-tools"),
                        mode = ToolchainDownloadService.MODE_ARM64_NINJA,
                        label = "ARM64 ninja"
                    )
                })
                addView(secondaryButton("Custom cmake URL") {
                    showUrlDownloadDialog(
                        title = "Download ARM64 cmake",
                        hint = "https://github.com/aspect-build/aspect-cli/releases/download/...",
                        savedKey = "arm64_cmake_url",
                        targetDir = File(filesDir, "toolchain/arm64-tools/cmake-arm64"),
                        mode = ToolchainDownloadService.MODE_ARM64_CMAKE,
                        label = "ARM64 cmake"
                    )
                })
            })
        }

        card.addView(spacer(dp(8)))
        val versions = toolVersionsNewestFirst(kind)
        if (versions.isEmpty()) {
            card.addView(TextView(host).apply {
                text = t("No versions available")
                setTextColor(StudioUi.color(StudioUi.Muted))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
        } else {
            // Paginated: newest PAGE_SIZE versions first, then a single
            // "Show more old versions" row that reveals the next page
            // (with a small staggered fade/slide animation) until exhausted.
            val expansions = expandedKindCounts[kind] ?: 0
            val rowsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            card.addView(rowsHost)
            VersionPagination.window(versions, expansions).forEach { version ->
                rowsHost.addView(buildVersionRow(kind, version, version == active))
            }
            if (VersionPagination.hasMore(versions.size, expansions)) {
                rowsHost.addView(buildShowMoreRow(kind, versions, expansions, rowsHost))
            }
        }
        return card
    }

    /** Installed + available versions merged, deduplicated, NEWEST first. */
    private fun toolVersionsNewestFirst(kind: ToolchainManager.Kind): List<String> =
        BuildToolCatalog.mergedVersionStringsNewestFirst(
            toolchain.availableVersions(kind),
            toolchain.installedVersions(kind)
        )

    /**
     * The "Show more old versions" row. Tapping it appends the next page of
     * rows in place (before this row), animating each new row in; the row
     * removes itself once every version is visible.
     */
    private fun buildShowMoreRow(
        kind: ToolchainManager.Kind,
        versions: List<String>,
        expansions: Int,
        rowsHost: LinearLayout
    ): View {
        val remaining = versions.size - VersionPagination.visibleCount(versions.size, expansions)
        return secondaryButton(t("Show more old versions") + " ($remaining)") {
            val nextExpansions = expansions + 1
            expandedKindCounts[kind] = nextExpansions
            // Drop this button row (last child), append the new page, then
            // re-append the button unless the list is exhausted.
            if (rowsHost.childCount > 0) rowsHost.removeViewAt(rowsHost.childCount - 1)
            val activeNow = toolchain.activeVersion(kind)
            VersionPagination.nextBatchAfter(versions, expansions).forEachIndexed { index, version ->
                val row = buildVersionRow(kind, version, version == activeNow)
                rowsHost.addView(row)
                animateRowIn(row, index)
            }
            if (VersionPagination.hasMore(versions.size, nextExpansions)) {
                rowsHost.addView(buildShowMoreRow(kind, versions, nextExpansions, rowsHost))
            }
        }
    }

    /** Fade + slide entrance for newly revealed version rows (~180ms, staggered). */
    private fun animateRowIn(row: View, index: Int) {
        row.alpha = 0f
        row.translationY = dp(8).toFloat()
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .setStartDelay(index * 30L)
            .withEndAction {
                row.alpha = 1f
                row.translationY = 0f
            }
            .start()
    }

    /**
     * Pulls the dynamic build-tool catalog (GitHub releases) and the live
     * Gradle distribution list in the background, then re-renders so newly
     * published versions appear without hardcoding. Both are TTL-cached;
     * failures are silent (bundled fallbacks keep the UI populated).
     */
    private fun refreshRemoteCatalogs() {
        scope.launch(Dispatchers.IO) {
            runCatching { BuildToolCatalog.fetch(host) }
            runCatching { updateSites.refreshIfStale(ToolchainManager.Kind.GRADLE) }
            withContext(Dispatchers.Main) {
                if (::rootContainer.isInitialized) renderAll()
            }
        }
    }

    private fun buildPathRow(kind: ToolchainManager.Kind): View {
        val activeDir = toolchain.activeDir(kind)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
            addView(TextView(host).apply {
                text = t("PATH")
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(StudioUi.color(StudioUi.Muted))
                layoutParams = LinearLayout.LayoutParams(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(host).apply {
                text = activeDir?.absolutePath ?: t("Not selected")
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(StudioUi.Text))
                background = StudioUi.inputBg(host)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(secondaryButton("Browse") {
                ToolchainFileExplorerActivity.start(host, kind)
            })
        }
    }

    private fun buildVersionRow(
        kind: ToolchainManager.Kind,
        version: String,
        isActive: Boolean
    ): View {
        val isInstalled = toolchain.isInstalled(kind, version)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (isActive) {
                background = StudioUi.rounded(
                    host,
                    fill = "#%06X".format(0xFFFFFF and StudioUi.palette.selectedBg),
                    strokeDp = 0,
                    radiusDp = 6
                )
            }
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        row.addView(TextView(this).apply {
            text = when {
                isActive -> "\u25CF"
                isInstalled -> "\u25CB"
                else -> "\u25E6"
            }
            textSize = 14f
            setTextColor(StudioUi.color(when {
                isActive -> StudioUi.Green
                isInstalled -> StudioUi.TextSoft
                else -> StudioUi.Muted
            }))
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
        })

        row.addView(TextView(this).apply {
            text = version
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(if (isInstalled) StudioUi.Text else StudioUi.Muted))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        if (isInstalled) {
            if (!isActive) {
                row.addView(primaryButton("Activate") {
                    val currentActive = toolchain.activeVersion(kind)
                    if (currentActive != null && currentActive != version) {
                        VersionCompatibilityDialog(host).show(
                            kind = kind,
                            currentVersion = currentActive,
                            newVersion = version,
                            onConfirm = {
                                if (toolchain.setActiveVersion(kind, version)) {
                                    toast("Active: ${kind.displayName} -> $version")
                                    renderAll()
                                }
                            }
                        )
                    } else {
                        if (toolchain.setActiveVersion(kind, version)) {
                            toast("Active: ${kind.displayName} -> $version")
                            renderAll()
                        }
                    }
                })
            }
            if (kind == ToolchainManager.Kind.SDK) {
                row.addView(secondaryButton("Licenses") {
                    runSdkManager(version, listOf("--licenses"))
                })
                row.addView(primaryButton("SDK Install") {
                    runSdkManager(
                        version,
                        listOf("platform-tools", "platforms;$version", "build-tools;${buildToolsVersionForSdk(version)}")
                    ) { renderAll() }
                })
            }
            if (File(toolchain.kindDir(kind), version).exists()) {
                row.addView(dangerButton("Delete") {
                    confirmDialog("Delete $version?") {
                        if (toolchain.uninstall(kind, version)) {
                            toast("Deleted: $version")
                            renderAll()
                        }
                    }
                })
            }
        } else {
            val url = toolchain.downloadUrl(kind, version)
            row.addView(primaryButton(if (url != null || kind == ToolchainManager.Kind.CMAKE || kind == ToolchainManager.Kind.NDK) "Install" else "Manual") {
                when {
                    kind == ToolchainManager.Kind.PYTHON -> showPythonDownloadDialog("Download $version", version, pythonDefaultUrl(version))
                    url != null -> startDownload(kind, version, url)
                    kind == ToolchainManager.Kind.NDK -> installNdk(version)
                    kind == ToolchainManager.Kind.CMAKE -> installCmake(version)
                    else -> toast("Manual install is required for this version")
                }
            })
        }

        return row
    }

    private fun showPythonDownloadDialog(title: String, version: String, defaultUrl: String) {
        val url = pythonDefaultUrl(version).ifBlank { defaultUrl }
        if (url.isBlank()) {
            showUrlDownloadDialog(
                title = title,
                hint = "",
                savedKey = "python_url_$version",
                targetDir = File(toolchain.kindDir(ToolchainManager.Kind.PYTHON), version),
                mode = ToolchainDownloadService.MODE_TOOLCHAIN,
                label = title,
                kind = ToolchainManager.Kind.PYTHON,
                version = version
            )
            return
        }
        startDownload(
            kind = ToolchainManager.Kind.PYTHON,
            version = version,
            url = url
        )
    }

    private fun pythonDefaultUrl(version: String): String =
        toolchain.downloadUrl(ToolchainManager.Kind.PYTHON, version) ?: DEFAULT_PYTHON_URL

    private fun showPythonVersionPicker(profile: String) {
        val versions = toolchain.availableVersions(ToolchainManager.Kind.PYTHON)
            .filter { it.startsWith("python-") }
            .sortedDescending()
            .take(7)
        AlertDialog.Builder(this)
            .setTitle(t("$profile version"))
            .setItems(versions.toTypedArray()) { _, which ->
                val version = versions[which]
                showPythonDownloadDialog(profile, version, pythonDefaultUrl(version))
            }
            .setNeutralButton(t("Custom URL")) { _, _ ->
                val fallback = versions.firstOrNull() ?: "python-3.14.6"
                showPythonDownloadDialog(profile, fallback, pythonDefaultUrl(fallback))
            }
            .setNegativeButton(t("Close"), null)
            .show()
    }

    private fun showPythonProfileHelp(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(t(title))
            .setMessage(t(message))
            .setPositiveButton(t("Copy")) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(title, message))
                toast(t("Copied"))
            }
            .setNegativeButton(t("Close"), null)
            .show()
    }

    private fun startDownload(kind: ToolchainManager.Kind, version: String, url: String) {
        val targetDir = File(toolchain.kindDir(kind), version)
        if (targetDir.exists()) {
            toast("Already installed")
            return
        }
        scope.launch(Dispatchers.IO) {
            val totalBytes = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    connect()
                }
                val length = conn.contentLengthLong
                conn.disconnect()
                length
            }.getOrDefault(-1L)

            val freeMb = StorageHelper.getFreeDiskMb(host)
            val sizeMb = if (totalBytes > 0) (totalBytes / 1024.0 / 1024.0) else -1.0
            val message = buildString {
                if (sizeMb > 0) append("Download size: %.2f GB\n".format(sizeMb / 1024.0))
                append("Free space: ${freeMb}MB\n\nContinue?")
            }

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(host)
                    .setTitle(t("Install ${kind.displayName} $version"))
                    .setMessage(message)
                    .setPositiveButton(t("OK")) { _, _ ->
                        // Use DownloadTask directly so we can refresh the list on completion
                        DownloadTask.start(
                            context = host,
                            label = "${kind.displayName} $version",
                            url = url,
                            targetDir = targetDir,
                            mode = ToolchainDownloadService.MODE_TOOLCHAIN,
                            kind = kind,
                            version = version,
                            onComplete = { host.runOnUiThread { renderAll() } }
                        )
                        toast("Download started")
                    }
                    .setNegativeButton(t("Cancel"), null)
                    .show()
            }
        }
    }

    private fun showUrlDownloadDialog(
        title: String,
        hint: String,
        savedKey: String,
        targetDir: File,
        mode: String,
        label: String,
        kind: ToolchainManager.Kind? = null,
        version: String? = null
    ) {
        val prefs = getSharedPreferences("ondevice_ai", MODE_PRIVATE)
        val input = EditText(this).apply {
            setSingleLine(false)
            minLines = 2
            maxLines = 4
            this.hint = hint
            setText(prefs.getString(savedKey, "").orEmpty())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(t(title))
            .setView(input)
            .setPositiveButton(t("Check Size")) { _, _ ->
                val url = input.text?.toString().orEmpty().trim()
                prefs.edit().putString(savedKey, url).apply()
                confirmBackgroundDownload(label, url, targetDir, mode, kind, version)
            }
            .setNegativeButton(t("Cancel"), null)
            .show()
    }

    private fun confirmBackgroundDownload(
        label: String,
        url: String,
        targetDir: File,
        mode: String,
        kind: ToolchainManager.Kind? = null,
        version: String? = null
    ) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            toast("Enter a valid download URL")
            return
        }
        scope.launch(Dispatchers.IO) {
            val totalBytes = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = true
                    connect()
                }
                val length = conn.contentLengthLong
                conn.disconnect()
                length
            }.getOrDefault(-1L)
            val freeMb = StorageHelper.getFreeDiskMb(host)
            val smartSize = when {
                totalBytes > 0 -> totalBytes
                url.contains("clang19_toolchain") || url.contains("android-llvm") -> (1.1 * 1024 * 1024 * 1024).toLong()
                url.contains("termux") || url.contains("openjdk") -> (80 * 1024 * 1024).toLong()
                else -> -1L
            }
            val message = buildString {
                append("Target: ${targetDir.absolutePath}\n")
                if (smartSize > 0) {
                    append("Download size: ~%.2f GB\n".format(smartSize / 1024.0 / 1024.0 / 1024.0))
                } else {
                    append("Download size: unavailable (will show during download)\n")
                }
                    append("Free space: ${freeMb}MB\n")
                    append("Download will show progress in a dialog.\n\nStart download?")
            }
            withContext(Dispatchers.Main) {
                AlertDialog.Builder(host)
                    .setTitle(t(label))
                    .setMessage(t(message))
                    .setPositiveButton(t("OK")) { _, _ ->
                        DownloadTask.start(
                            context = host,
                            label = label,
                            url = url,
                            targetDir = targetDir,
                            mode = mode,
                            kind = kind,
                            version = version,
                            onComplete = {
                                if (mode == ToolchainDownloadService.MODE_ARM64_NINJA) {
                                    val ninja = File(targetDir, "ninja")
                                    if (ninja.exists()) {
                                        ninja.setExecutable(true, false)
                                        try { android.system.Os.chmod(ninja.absolutePath, 493) } catch (_: Throwable) {}
                                    }
                                }
                                host.runOnUiThread { renderAll() }
                            }
                        )
                        toast("Download started")
                    }
                    .setNegativeButton(t("Cancel"), null)
                    .show()
            }
        }
    }

    private fun downloadWithDialog(
        kind: ToolchainManager.Kind,
        version: String,
        url: String,
        targetDir: File
    ) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(t("Download ${kind.displayName} $version"))
            .setMessage(t("Preparing..."))
            .setCancelable(false)
            .create()
        progressDialog.show()

        scope.launch(Dispatchers.IO) {
            val errorStack = try {
                downloadAndExtract(url, targetDir) { message ->
                    host.runOnUiThread { progressDialog.setMessage(t(message)) }
                }
                postInstall(kind, version, targetDir)
                null
            } catch (e: Exception) {
                Log.e("ToolsActivity", "download error", e)
                targetDir.deleteRecursively()
                val sw = java.io.StringWriter()
                java.io.PrintWriter(sw).use { e.printStackTrace(it) }
                sw.toString()
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (errorStack == null) {
                    val currentActive = toolchain.activeVersion(kind)
                    if (currentActive != null && currentActive != version) {
                        VersionCompatibilityDialog(host).show(
                            kind = kind,
                            currentVersion = currentActive,
                            newVersion = version,
                            onConfirm = {
                                toolchain.setActiveVersion(kind, version)
                                toast("Installed: ${kind.displayName} $version")
                                renderAll()
                            }
                        )
                    } else {
                        toolchain.setActiveVersion(kind, version)
                        toast("Installed: ${kind.displayName} $version")
                        renderAll()
                    }
                } else {
                    AlertDialog.Builder(host)
                        .setTitle(t("Download failed"))
                        .setMessage(errorStack.take(5000))
                        .setPositiveButton(t("OK"), null)
                        .show()
                }
            }
        }
    }

    private fun downloadAndExtract(
        url: String,
        targetDir: File,
        onProgress: (String) -> Unit
    ) {
        targetDir.mkdirs()
        val tmpFile = File(targetDir.parentFile, "${targetDir.name}.tmp")

        onProgress(t("Downloading: ${url.substringAfterLast('/')}"))
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            connect()
        }
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
        val total = conn.contentLengthLong
        var downloaded = 0L

        conn.inputStream.use { input ->
            tmpFile.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var lastProgressTime = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime > 200) {
                        val pct = if (total > 0) (downloaded * 100 / total).toInt() else 0
                        val mb = downloaded / 1024 / 1024
                        onProgress(t("Downloading: $mb MB ($pct%)"))
                        lastProgressTime = now
                    }
                }
            }
        }
        conn.disconnect()

        onProgress(t("Extracting..."))
        when {
            url.endsWith(".zip") -> extractZip(tmpFile, targetDir)
            url.endsWith(".tar.gz") -> extractTarGz(tmpFile, targetDir)
            url.endsWith(".tar") -> extractTar(tmpFile, targetDir)
            else -> error("Unsupported archive format: $url")
        }
        tmpFile.delete()
        onProgress(t("Done"))
    }

    private fun postInstall(kind: ToolchainManager.Kind, version: String, targetDir: File) {
        when (kind) {
            ToolchainManager.Kind.SDK -> normalizeSdkCmdlineTools(targetDir)
            ToolchainManager.Kind.GRADLE,
            ToolchainManager.Kind.NDK,
            ToolchainManager.Kind.PYTHON -> flattenSingleChildDirectory(targetDir)
            else -> Unit
        }
    }

    private fun normalizeSdkCmdlineTools(targetDir: File) {
        val cmdline = File(targetDir, "cmdline-tools")
        if (!cmdline.exists() || !cmdline.isDirectory) return
        val latest = File(cmdline, "latest")
        if (latest.exists()) return

        latest.mkdirs()
        cmdline.listFiles()
            ?.filter { it.name != "latest" }
            ?.forEach { child -> child.renameTo(File(latest, child.name)) }
    }

    private fun flattenSingleChildDirectory(targetDir: File) {
        val only = targetDir.listFiles()
            ?.singleOrNull { it.isDirectory }
            ?: return
        only.listFiles()?.forEach { child ->
            child.renameTo(File(targetDir, child.name))
        }
        only.delete()
    }

    private fun installCmake(version: String) {
        // Prefer the ARM64 port from the dynamic catalog (works without an
        // SDK, SHA-256 verified when the catalog/bundled hash is known).
        val catalogHasEntry = runCatching {
            BuildToolCatalog.findCmake(BuildToolCatalog.loadCached(this), version) != null
        }.getOrDefault(false)
        if (catalogHasEntry || version == CmakeRuntimeManager.CMAKE_VERSION) {
            installArm64Cmake(version)
            return
        }
        // Fall back to the SDK manager flow for versions only published in
        // the Google SDK repository (e.g. Google-only cmake builds).
        val sdkVersion = toolchain.activeVersion(ToolchainManager.Kind.SDK)
        if (sdkVersion == null) {
            toast("Install and activate an SDK first")
            return
        }
        runSdkManager(sdkVersion, listOf("cmake;$version")) {
            if (toolchain.isInstalled(ToolchainManager.Kind.CMAKE, version)) {
                val currentCmake = toolchain.activeVersion(ToolchainManager.Kind.CMAKE)
                if (currentCmake != null && currentCmake != version) {
                    VersionCompatibilityDialog(host).show(
                        kind = ToolchainManager.Kind.CMAKE,
                        currentVersion = currentCmake,
                        newVersion = version,
                        onConfirm = {
                            toolchain.setActiveVersion(ToolchainManager.Kind.CMAKE, version)
                            renderAll()
                        }
                    )
                } else {
                    toolchain.setActiveVersion(ToolchainManager.Kind.CMAKE, version)
                    renderAll()
                }
            } else {
                renderAll()
            }
        }
    }

    /**
     * Installs the user-ported arm64 CMake runtime for [version] via
     * CmakeRuntimeManager (catalog URL, background thread, progress dialog).
     */
    private fun installArm64Cmake(version: String) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(t("Install CMake $version"))
            .setMessage(t("Preparing..."))
            .setCancelable(false)
            .create()
        dialog.show()
        CmakeRuntimeManager.initAppContext(this)
        scope.launch(Dispatchers.IO) {
            // Refresh the catalog so a freshly published port is found even
            // on the first use (TTL-cached afterwards).
            runCatching { BuildToolCatalog.fetch(host) }
            var error: String? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            CmakeRuntimeManager.get(host).ensureInstalled(
                onProgress = { read, total ->
                    val percent = if (total > 0) (read * 100 / total).toInt() else 0
                    val message = if (percent > 0) "$percent%" else "${read / (1024 * 1024)} MB"
                    host.runOnUiThread { dialog.setMessage(t("Downloading... $message")) }
                },
                onLog = { },
                onDone = { success, err ->
                    error = err
                    latch.countDown()
                },
                version = version
            )
            latch.await()
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (error == null && CmakeRuntimeManager.get(host).isInstalled(version)) {
                    val currentCmake = toolchain.activeVersion(ToolchainManager.Kind.CMAKE)
                    if (currentCmake != null && currentCmake != version) {
                        VersionCompatibilityDialog(host).show(
                            kind = ToolchainManager.Kind.CMAKE,
                            currentVersion = currentCmake,
                            newVersion = version,
                            onConfirm = {
                                toolchain.setActiveVersion(ToolchainManager.Kind.CMAKE, version)
                                renderAll()
                            }
                        )
                    } else {
                        toolchain.setActiveVersion(ToolchainManager.Kind.CMAKE, version)
                        renderAll()
                    }
                    toast("Installed: CMake $version")
                } else {
                    toast("CMake install failed: ${error?.take(120) ?: "unknown error"}")
                    renderAll()
                }
            }
        }
    }

    private fun reinstallArm64Cmake() {
        val sdkDir = toolchain.activeDir(ToolchainManager.Kind.SDK) ?: run {
            toast("Install and activate an SDK first"); return
        }
        val cmakeDir = File(sdkDir, "cmake/3.22.1")
        val binDir = File(cmakeDir, "bin")
        val libDir = File(cmakeDir, "lib")

        AlertDialog.Builder(this).apply {
            setTitle("Reinstall ARM64 cmake")
            setMessage("This will re-download and reinstall cmake + ninja from Termux.\nProceed?")
            setPositiveButton("Reinstall") { _, _ ->
                scope.launch(Dispatchers.IO) {
                    withContext(Dispatchers.Main) { toast("Reinstalling ARM64 cmake...") }
                    try {
                        for (name in listOf("cmake.real", "ninja.real", "cmake", "ninja")) {
                            File(binDir, name).delete()
                        }
                        libDir.listFiles()?.forEach { it.delete() }
                        libDir.mkdirs()
                        binDir.mkdirs()

                        downloadAndExtractTermuxPkg("cmake", cmakeDir, binDir, libDir)
                        downloadAndExtractTermuxPkg("ninja", cmakeDir, binDir, libDir)

                        withContext(Dispatchers.Main) { toast("Extracting library files...") }
                        try {
                            val assetPath = "cmake-lib"
                            val files = assets.list(assetPath) ?: emptyArray()
                            for (name in files) {
                                try {
                                    val src = assets.open("$assetPath/$name")
                                    val dst = File(libDir, name)
                                    dst.outputStream().use { out -> src.copyTo(out) }
                                    src.close()
                                } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}

                        for (binName in listOf("cmake.real", "cpack", "ctest")) {
                            val f = File(binDir, binName)
                            if (f.isFile) patchElfRunPath(f)
                        }

                        val cmakeBin = File(binDir, "cmake")
                        val cmakeShim = File(applicationInfo.nativeLibraryDir, "libcmake_shim.so")
                        val cmakeReal = File(binDir, "cmake.real")
                        if (cmakeShim.isFile && isAndroidArm64Executable(cmakeShim) && cmakeBin.isFile && !cmakeReal.isFile) {
                            cmakeBin.renameTo(cmakeReal)
                            if (!cmakeBin.exists()) {
                                runCatching { Os.symlink(cmakeShim.absolutePath, cmakeBin.absolutePath) }
                            }
                        }

                        withContext(Dispatchers.Main) {
                            toast("ARM64 cmake reinstalled successfully!")
                            renderAll()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { toast("Reinstall failed: ${e.message?.take(80)}") }
                    }
                }
            }
            setNegativeButton("Cancel", null)
            show()
        }
    }

    private fun downloadAndExtractTermuxPkg(pkgName: String, cmakeDir: File, binDir: File, libDir: File) {
        val debFile = File(cacheDir, "termux_${pkgName}.deb")
        try {
            val url = resolveTermuxPackageUrlCmake(pkgName)
            downloadUrlToFile(url, debFile)
            extractTermuxDebLibs(debFile, pkgName, cmakeDir, binDir, libDir)
        } finally {
            debFile.delete()
        }
    }

    private var packagesIndexText: String? = null
    private fun resolveTermuxPackageUrlCmake(pkgName: String): String {
        val text = packagesIndexText ?: run {
            val packagesUrl = "https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages"
            val conn = (URL(packagesUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000; connect()
            }
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val t = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            t.also { packagesIndexText = it }
        }
        val block = text.split("\n\n").firstOrNull { b ->
            b.lineSequence().any { it.trim() == "Package: $pkgName" }
        } ?: throw RuntimeException("Package not found: $pkgName")
        val filename = block.lineSequence()
            .firstOrNull { it.startsWith("Filename:") }
            ?.removePrefix("Filename:")?.trim()
            ?: throw RuntimeException("$pkgName: no Filename field")
        return "https://packages-cf.termux.dev/apt/termux-main/$filename"
    }

    private fun downloadUrlToFile(url: String, dest: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 60000
            instanceFollowRedirects = true; connect()
        }
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
        dest.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()
    }

    private fun extractTermuxDebLibs(debFile: File, pkgName: String, cmakeDir: File, binDir: File, libDir: File) {
        val dataTarXz = File(cacheDir, "termux_${pkgName}_data.tar.xz")
        val tmpDir = File(cacheDir, "termux_${pkgName}_extract")
            .apply { deleteRecursively(); mkdirs() }
        try {
            extractArMemberCmake(debFile, "data.tar.xz", dataTarXz)
            val home = File(tmpDir, "home")
            extractTarXzRemapCmake(dataTarXz, home, "data/data/com.termux/files/usr")
            // Copy binaries
            val extractedBin = File(home, "bin/$pkgName")
            if (extractedBin.isFile) {
                extractedBin.copyTo(File(binDir, "$pkgName.real"), overwrite = true)
                File(binDir, "$pkgName.real").setExecutable(true, false)
            }
            // Copy all .so from extracted lib
            val extractedLib = File(home, "lib")
            if (extractedLib.isDirectory) {
                for (f in extractedLib.listFiles() ?: emptyArray()) {
                    val name = f.name
                    if (!name.endsWith(".so") && !name.contains(".so.")) continue
                    val simpleName = when {
                        name.endsWith(".so") -> name
                        name.contains(".so.") -> name.substringBefore(".so.") + ".so"
                        else -> continue
                    }
                    if (f.isFile) {
                        f.copyTo(File(libDir, simpleName), overwrite = true)
                    }
                }
            }
        } finally {
            dataTarXz.delete(); tmpDir.deleteRecursively()
        }
    }

    private fun extractArMemberCmake(arFile: File, memberName: String, dest: File) {
        arFile.inputStream().buffered().use { input ->
            val magic = ByteArray(8)
            input.read(magic)
            check(String(magic) == "!<arch>\n") { "Invalid ar" }
            val hdrBuf = ByteArray(60)
            while (true) {
                val n = input.read(hdrBuf)
                if (n < 60) throw RuntimeException("Member not found: $memberName")
                val name = String(hdrBuf, 0, 16).trim().trimEnd('/')
                val size = String(hdrBuf, 48, 10).trim().toLong()
                if (name == memberName.trimEnd('/')) {
                    dest.outputStream().use { out ->
                        var rem = size; val buf = ByteArray(32 * 1024)
                        while (rem > 0) {
                            val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (r < 0) break; out.write(buf, 0, r); rem -= r
                        }
                    }
                    val pad = (size + 1) % 2
                    if (pad > 0) input.skip(pad)
                    return
                } else {
                    input.skip(size)
                    val pad = (size + 1) % 2
                    if (pad > 0) input.skip(pad)
                }
            }
        }
    }

    private fun extractTarXzRemapCmake(tarXzFile: File, destDir: File, stripPrefix: String) {
        val xzIn = XZInputStream(tarXzFile.inputStream().buffered())
        val buf = ByteArray(512)
        while (true) {
            var offset = 0
            while (offset < 512) {
                val n = xzIn.read(buf, offset, 512 - offset)
                if (n < 0) return
                offset += n
            }
            if (buf[0] == 0.toByte()) break
            val nameEnd = buf.indexOf(0.toByte())
            if (nameEnd < 0) break
            val name = String(buf, 0, nameEnd)
            val size = (buf[124].toLong() and 0xff) or
                       ((buf[125].toLong() and 0xff) shl 8) or
                       ((buf[126].toLong() and 0xff) shl 16) or
                       ((buf[127].toLong() and 0xff) shl 24) or
                       ((buf[128].toLong() and 0xff) shl 32) or
                       ((buf[129].toLong() and 0xff) shl 40) or
                       ((buf[130].toLong() and 0xff) shl 48) or
                       ((buf[131].toLong() and 0xff) shl 56)
            val prefix = name.takeWhile { it != '/' }.ifEmpty { "" }
            val stripped = if (name.startsWith(stripPrefix)) name.removePrefix(stripPrefix) else name
            val target = File(destDir, stripped.trimStart('/'))
            if (name.endsWith("/")) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                val dataBuf = ByteArray(size.toInt())
                var dataOff = 0
                while (dataOff < size) {
                    val r = xzIn.read(dataBuf, dataOff, size.toInt() - dataOff)
                    if (r < 0) break
                    dataOff += r
                }
                target.writeBytes(dataBuf.take(dataOff).toByteArray())
            }
            val pad = (size + 511) / 512 * 512 - size
            if (pad > 0) xzIn.skip(pad)
        }
    }

    private fun patchElfRunPath(file: File) {
        try {
            val raf = java.io.RandomAccessFile(file, "rw")
            val ch = raf.channel
            val size = ch.size().toInt()
            if (size < 64) { raf.close(); return }
            val buf = java.nio.ByteBuffer.allocate(size)
            ch.read(buf)
            buf.flip()
            val le = buf.get(5).toInt() == 1
            buf.order(if (le) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN)
            val is64 = buf.get(4).toInt() == 2
            val phoff = if (is64) buf.getLong(32) else buf.getInt(28).toLong()
            val phent = if (is64) buf.getShort(54).toInt() else buf.getShort(42).toInt()
            val phnum = if (is64) buf.getShort(56).toInt() else buf.getShort(44).toInt()
            val loadVaddr = mutableListOf<Long>()
            val loadOff = mutableListOf<Long>()
            val loadMemsz = mutableListOf<Long>()
            var dynOff = 0L; var dynVaddr = 0L
            for (i in 0 until phnum) {
                val off = phoff.toInt() + i * phent
                val pType = buf.getInt(off)
                if (pType == 1) {
                    loadVaddr += if (is64) buf.getLong(off + 16) else buf.getInt(off + 8).toLong() and 0xFFFFFFFFL
                    loadOff += if (is64) buf.getLong(off + 8) else buf.getInt(off + 4).toLong() and 0xFFFFFFFFL
                    loadMemsz += if (is64) buf.getLong(off + 40) else buf.getInt(off + 20).toLong() and 0xFFFFFFFFL
                } else if (pType == 2) {
                    dynOff = if (is64) buf.getLong(off + 8) else buf.getInt(off + 4).toLong() and 0xFFFFFFFFL
                    dynVaddr = if (is64) buf.getLong(off + 16) else buf.getInt(off + 8).toLong() and 0xFFFFFFFFL
                }
            }
            if (dynOff == 0L) return
            fun vaddrToFoff(vaddr: Long): Long {
                for (j in loadVaddr.indices) {
                    if (vaddr >= loadVaddr[j] && vaddr < loadVaddr[j] + loadMemsz[j])
                        return vaddr - loadVaddr[j] + loadOff[j]
                }
                return -1
            }
            val esize = if (is64) 16 else 8
            var strTabVaddr = 0L; var runPathStrOff = -1L
            val newRunPath = "\u0024ORIGIN/../lib"
            val oldRunPath = "/data/data/com.termux/files/usr/lib"
            val oldRunPathLen = oldRunPath.length
            var dp = dynOff.toInt()
            while (dp + esize <= size) {
                val dTag = if (is64) buf.getLong(dp) else buf.getInt(dp).toLong()
                val dVal = if (is64) buf.getLong(dp + 8) else buf.getInt(dp + 4).toLong()
                if (dTag == 5L) { strTabVaddr = dVal; break }
                if (dTag == 0L) break
                dp += esize
            }
            if (strTabVaddr == 0L) return
            val strTabFileOff = vaddrToFoff(strTabVaddr)
            if (strTabFileOff < 0) return
            dp = dynOff.toInt()
            while (dp + esize <= size) {
                val dTag = if (is64) buf.getLong(dp) else buf.getInt(dp).toLong()
                val dVal = if (is64) buf.getLong(dp + 8) else buf.getInt(dp + 4).toLong()
                if (dTag == 29L || dTag == 15L) {
                    runPathStrOff = strTabFileOff + dVal; break
                }
                if (dTag == 0L) break
                dp += esize
            }
            if (runPathStrOff < 0 || runPathStrOff + oldRunPathLen > size) return
            val existing = ByteArray(oldRunPathLen)
            buf.position(runPathStrOff.toInt()); buf.get(existing)
            if (String(existing, Charsets.UTF_8) != oldRunPath) return
            val newBytes = newRunPath.toByteArray(Charsets.UTF_8)
            raf.seek(runPathStrOff)
            raf.write(newBytes)
            val pad = oldRunPathLen - newBytes.size
            if (pad > 0) raf.write(ByteArray(pad))
            raf.close()
        } catch (_: Exception) {}
    }

    private fun isAndroidArm64Executable(file: File): Boolean {
        if (!file.isFile) return false
        val hdr = ByteArray(20)
        val nread = runCatching { file.inputStream().use { it.read(hdr) } }.getOrDefault(0)
        if (nread < 20) return false
        if (hdr[0] != 0x7f.toByte() || hdr[1] != 'E'.code.toByte() ||
            hdr[2] != 'L'.code.toByte() || hdr[3] != 'F'.code.toByte()
        ) return false
        val le = hdr[5].toInt() == 1
        val machine = if (le) (hdr[18].toInt() and 0xff) or ((hdr[19].toInt() and 0xff) shl 8)
        else ((hdr[18].toInt() and 0xff) shl 8) or (hdr[19].toInt() and 0xff)
        return machine == 183
    }


    private fun installNdk(version: String) {
        val sdkVersion = toolchain.activeVersion(ToolchainManager.Kind.SDK)
        if (sdkVersion == null) {
            toast("Install and activate an SDK first")
            return
        }
        runSdkManager(sdkVersion, listOf("ndk;$version")) {
            if (toolchain.isInstalled(ToolchainManager.Kind.NDK, version)) {
                val currentNdk = toolchain.activeVersion(ToolchainManager.Kind.NDK)
                if (currentNdk != null && currentNdk != version) {
                    VersionCompatibilityDialog(host).show(
                        kind = ToolchainManager.Kind.NDK,
                        currentVersion = currentNdk,
                        newVersion = version,
                        onConfirm = {
                            toolchain.setActiveVersion(ToolchainManager.Kind.NDK, version)
                            toast("NDK active: $version")
                            renderAll()
                        }
                    )
                } else {
                    toolchain.setActiveVersion(ToolchainManager.Kind.NDK, version)
                    toast("NDK active: $version")
                    renderAll()
                }
            } else {
                toast("NDK install finished, but version was not found")
                renderAll()
            }
        }
    }

    private fun runSdkManager(
        version: String,
        args: List<String>,
        onFinished: (() -> Unit)? = null
    ) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(t("SDK installer"))
            .setMessage(t("Preparing..."))
            .setCancelable(false)
            .create()
        dialog.show()

        scope.launch(Dispatchers.IO) {
            val sdkDir = toolchain.activeDir(ToolchainManager.Kind.SDK)
                ?.takeIf { it.name == version }
                ?: File(toolchain.kindDir(ToolchainManager.Kind.SDK), version)
            val failure = runCatching {
                sdkDir.mkdirs()
                if (args.contains("--licenses")) {
                    writeAndroidSdkLicenses(sdkDir)
                    host.runOnUiThread { dialog.setMessage(t("Licenses accepted locally.")) }
                } else {
                    val repositoryXml = downloadText("https://dl.google.com/android/repository/repository2-1.xml")
                    args.forEach { packagePath ->
                        installSdkPackageDirect(repositoryXml, packagePath, sdkDir) { message ->
                            host.runOnUiThread { dialog.setMessage(t(message.take(500))) }
                        }
                    }
                    runCatching { toolchain.ensureAssetOverridesInstalled() }
                    writeAndroidSdkLicenses(sdkDir)
                }
            }.exceptionOrNull()

            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (failure == null) {
                    toast("SDK install complete")
                    onFinished?.invoke()
                } else {
                    toast("SDK install failed: ${failure.message?.take(140) ?: "unknown error"}")
                }
            }
        }
    }

    private fun installSdkPackageDirect(
        repositoryXml: String,
        packagePath: String,
        sdkDir: File,
        onProgress: (String) -> Unit
    ) {
        val destination = sdkPackageDestination(sdkDir, packagePath)
        if (sdkPackageInstalled(packagePath, destination)) return

        val url = resolveSdkPackageUrl(repositoryXml, packagePath)
        val zip = File(cacheDir, "sdk-${packagePath.replace(Regex("[^A-Za-z0-9._-]"), "_")}.zip")
        val temp = File(cacheDir, "sdk-extract-${packagePath.hashCode()}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            onProgress(t("Downloading $packagePath"))
            downloadFile(url, zip, onProgress)
            onProgress(t("Extracting $packagePath"))
            extractZip(zip, temp)
            val source = locateSdkPackageRoot(temp, packagePath)
            replaceDirectory(source, destination)
            destination.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    file.setReadable(true, false)
                    if (file.parentFile?.name == "bin" || file.name in setOf("adb", "aapt2", "cmake", "ninja")) {
                        file.setExecutable(true, false)
                    }
                }
            onProgress(t("Installed $packagePath"))
        } finally {
            zip.delete()
            temp.deleteRecursively()
        }
    }

    private fun sdkPackageDestination(sdkDir: File, packagePath: String): File = when {
        packagePath == "platform-tools" -> File(sdkDir, "platform-tools")
        packagePath.startsWith("platforms;") -> File(sdkDir, "platforms/${packagePath.substringAfter(';')}")
        packagePath.startsWith("build-tools;") -> File(sdkDir, "build-tools/${packagePath.substringAfter(';')}")
        packagePath.startsWith("ndk;") -> File(sdkDir, "ndk/${packagePath.substringAfter(';')}")
        packagePath.startsWith("cmake;") -> File(sdkDir, "cmake/${packagePath.substringAfter(';')}")
        else -> File(sdkDir, packagePath.replace(';', '/'))
    }

    private fun sdkPackageInstalled(packagePath: String, destination: File): Boolean = when {
        packagePath == "platform-tools" -> File(destination, "adb").exists()
        packagePath.startsWith("platforms;") -> File(destination, "android.jar").exists()
        packagePath.startsWith("build-tools;") -> File(destination, "aapt2").exists()
        packagePath.startsWith("ndk;") -> File(destination, "toolchains/llvm").exists() || File(destination, "source.properties").exists()
        packagePath.startsWith("cmake;") -> File(destination, "bin/cmake").exists() || File(destination, "source.properties").exists()
        else -> destination.exists()
    }

    private fun locateSdkPackageRoot(temp: File, packagePath: String): File = when {
        packagePath == "platform-tools" ->
            File(temp, "platform-tools").takeIf { it.exists() }
                ?: temp.walkTopDown().firstOrNull { it.isFile && it.name == "adb" }?.parentFile
        packagePath.startsWith("platforms;") ->
            temp.walkTopDown().firstOrNull { it.isFile && it.name == "android.jar" }?.parentFile
        packagePath.startsWith("build-tools;") ->
            temp.walkTopDown().firstOrNull { it.isFile && it.name == "aapt2" }?.parentFile
        packagePath.startsWith("ndk;") ->
            temp.walkTopDown().firstOrNull { it.isDirectory && File(it, "toolchains/llvm").exists() }
                ?: temp.walkTopDown().firstOrNull { it.isFile && it.name == "source.properties" }?.parentFile
        packagePath.startsWith("cmake;") ->
            temp.walkTopDown().firstOrNull { it.isFile && it.name == "cmake" && it.parentFile?.name == "bin" }?.parentFile?.parentFile
                ?: temp.walkTopDown().firstOrNull { it.isFile && it.name == "source.properties" }?.parentFile
        else -> temp.listFiles()?.singleOrNull { it.isDirectory }
    } ?: error("Archive layout not recognized: $packagePath")

    private fun resolveSdkPackageUrl(repositoryXml: String, packagePath: String): String {
        val blocks = Regex(
            "<remotePackage\\b[^>]*\\bpath=\"${Regex.escape(packagePath)}\"[^>]*>[\\s\\S]*?</remotePackage>"
        ).findAll(repositoryXml).map { it.value }.toList()
        val block = blocks.firstOrNull { "obsolete=\"true\"" !in it } ?: blocks.firstOrNull()
            ?: error("SDK package not found: $packagePath")
        val archive = Regex("<archive>[\\s\\S]*?</archive>")
            .findAll(block)
            .map { it.value }
            .firstOrNull { "<host-os>linux</host-os>" in it || "<host-os>" !in it }
            ?: error("No Linux archive for SDK package: $packagePath")
        val relativeUrl = Regex("<url>([^<]+)</url>").find(archive)?.groupValues?.get(1)
            ?: error("No download URL for SDK package: $packagePath")
        return "https://dl.google.com/android/repository/$relativeUrl"
    }

    private fun downloadText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            connect()
        }
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}: $url" }
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (String) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            connect()
        }
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}: $url" }
        val total = conn.contentLengthLong
        var done = 0L
        var lastProgress = 0L
        dest.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    done += read
                    val now = System.currentTimeMillis()
                    if (now - lastProgress > 600) {
                        val mb = done / 1024 / 1024
                        val suffix = if (total > 0) " / ${total / 1024 / 1024} MB" else " MB"
                        onProgress(t("Downloading: $mb$suffix"))
                        lastProgress = now
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun writeAndroidSdkLicenses(sdkDir: File) {
        val licensesDir = File(sdkDir, "licenses").apply { mkdirs() }
        File(licensesDir, "android-sdk-license").writeText(
            listOf(
                "24333f8a63b6825ea9c5514f83c2829b004d1fee",
                "d56f5187479451eabf01fb78af6dfcb131a6481e",
                "8933bad161af4178b1185d1a37fbf41ea5269c55"
            ).joinToString("\n", postfix = "\n")
        )
        File(licensesDir, "android-sdk-preview-license").writeText(
            "84831b9409646a918e30573bab4c9c91346d8abd\n"
        )
    }

    private fun replaceDirectory(source: File, destination: File) {
        destination.deleteRecursively()
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            copyDirectory(source, destination)
            source.deleteRecursively()
        }
    }

    private fun copyDirectory(source: File, destination: File) {
        if (source.isDirectory) {
            destination.mkdirs()
            source.listFiles()?.forEach { child -> copyDirectory(child, File(destination, child.name)) }
        } else {
            destination.parentFile?.mkdirs()
            source.copyTo(destination, overwrite = true)
        }
    }

    private fun buildToolsVersionForSdk(sdkVersion: String): String = when (sdkVersion) {
        "android-33" -> "33.0.2"
        "android-34" -> "34.0.0"
        "android-35" -> "35.0.0"
        else -> "34.0.0"
    }

    private fun extractZip(zipFile: File, dest: File) {
        java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    if (entry.name.contains("/bin/")) out.setExecutable(true, false)
                }
                entry = zip.nextEntry
            }
        }
    }

    private fun extractTarGz(tarGz: File, dest: File) {
        java.util.zip.GZIPInputStream(tarGz.inputStream().buffered()).use { gzip ->
            extractTarStream(gzip, dest)
        }
    }

    private fun extractTar(tar: File, dest: File) {
        tar.inputStream().buffered().use { extractTarStream(it, dest) }
    }

    private fun extractTarStream(input: InputStream, dest: File) {
        org.apache.commons.compress.archivers.tar.TarArchiveInputStream(input).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (entry.isSymbolicLink) {
                    runCatching {
                        out.parentFile?.mkdirs()
                        if (out.exists()) out.delete()
                        android.system.Os.symlink(entry.linkName, out.absolutePath)
                    }
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                    out.setReadable(true, false)
                    if (entry.mode and 0b001_000_000 != 0) out.setExecutable(true, false)
                }
                entry = tar.nextTarEntry
            }
        }
    }

    private fun showTextDialog(title: String, text: String) {
        val textView = TextView(this).apply {
            setText(text)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.Text))
            setTextIsSelectable(true)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(t(title))
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton(t("Copy")) { _, _ -> copyToClipboard(title, text) }
            .setNegativeButton(t("Close"), null)
            .show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Copied")
    }

    private fun trimTransientCaches() {
        listOf(
            File(cacheDir, "layoutlib_runner"),
            File(cacheDir, "layoutlib_previews"),
            File(codeCacheDir, "native_exec"),
            File(codeCacheDir, "cmake_proxy"),
            File(codeCacheDir, "jdk_exec")
        ).forEach { dir ->
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
        }
        cacheDir.listFiles { file -> file.name.startsWith("download-") || file.name.startsWith("extract-") }
            ?.forEach { it.deleteRecursively() }
    }

    private var cachedCacheSize: Long = -1L
    private var lastCacheSizeMs: Long = 0L

    private fun directorySize(dir: File): Long {
        val now = System.currentTimeMillis()
        if (now - lastCacheSizeMs > 30_000) {
            lastCacheSizeMs = now
            cachedCacheSize = runCatching {
                if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.take(2_000).sumOf { it.length() }
            }.getOrDefault(0L)
        }
        return cachedCacheSize
    }

    private fun buildAppUpdateCard(): View {
        val currentVer = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = StudioUi.cardBg(host)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        card.addView(sectionTitle(AppLanguage.text(host, "update_check").toString()))
        card.addView(TextView(this).apply {
            text = "MobileStudio $currentVer"
            textSize = 12f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, 0, 0, dp(8))
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(primaryButton(AppLanguage.text(host, "check_update_now").toString()) {
                AppUpdateManager.checkForUpdates(
                    host,
                    currentVersionName = currentVer
                ) { info ->
                    host.runOnUiThread {
                        if (info == null) {
                            toast(AppLanguage.text(host, "no_update"))
                        } else {
                            showUpdateDialog(info)
                        }
                    }
                }
            })
            addView(secondaryButton("Refresh") { renderAll() })
        })
        return card
    }

    private fun showUpdateDialog(info: AppUpdateManager.UpdateInfo) {
        val currentVer = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        val isKo = AppLanguage.isKorean(this)
        val message = buildString {
            if (isKo) {
                appendLine("새 버전 ${info.latestTag} 사용 가능!\n")
                appendLine("현재: $currentVer")
                appendLine("최신: ${info.latestTag}")
                if (info.releaseNotes.isNotBlank()) {
                    appendLine("\n--- 변경 사항 ---")
                    append(info.releaseNotes.take(500))
                }
            } else {
                appendLine("New version ${info.latestTag} available!\n")
                appendLine("Current: $currentVer")
                appendLine("Latest: ${info.latestTag}")
                if (info.releaseNotes.isNotBlank()) {
                    appendLine("\n--- Release Notes ---")
                    append(info.releaseNotes.take(500))
                }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(if (isKo) "업데이트 가능" else "Update Available")
            .setMessage(message)
            .setPositiveButton(if (isKo) "지금 업데이트" else "Update Now") { _, _ ->
                downloadUpdate(info)
            }
            .setNegativeButton(if (isKo) "나중에" else "Later") { d, _ -> d.dismiss() }
            .show()
    }

    private fun downloadUpdate(info: AppUpdateManager.UpdateInfo) {
        val isKo = AppLanguage.isKorean(this)
        val progress = AlertDialog.Builder(this)
            .setTitle("Downloading ${info.latestTag}")
                .setMessage(if (isKo) "다운로드 중..." else "Downloading...")
            .setCancelable(false)
            .show()
        AppUpdateManager.downloadAndInstall(this, info,
            onProgress = { pct ->
                Handler(Looper.getMainLooper()).post { progress.setMessage("$pct%") }
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

    private fun formatLocalBytes(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024.0) "%.2fGB".format(mb / 1024.0) else "%.0fMB".format(mb)
    }

    private fun sectionTitle(text: String, sizeSp: Float = 15f): TextView =
        TextView(host).apply {
            this.text = t(text)
            textSize = sizeSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(StudioUi.color(StudioUi.Blue))
            setPadding(0, dp(12), 0, dp(8))
        }

    private fun cardBackground(color: String? = null): GradientDrawable =
        StudioUi.rounded(
            this,
            fill = color ?: StudioUi.CardBg,
            stroke = StudioUi.CardBorder,
            radiusDp = 10
        )

    private fun gradleMemoryProfileLabel(profile: BuildMemoryPolicy.Profile): String = when (profile) {
        BuildMemoryPolicy.Profile.AUTO -> t("Auto")
        BuildMemoryPolicy.Profile.STANDARD -> t("1 GB")
        BuildMemoryPolicy.Profile.EXTENDED -> t("1.5 GB")
        BuildMemoryPolicy.Profile.MAX -> t("2 GB")
    }

    private fun profileMemoryButton(
        profile: BuildMemoryPolicy.Profile,
        selected: Boolean,
        onClick: () -> Unit
    ): Button = Button(this).apply {
        text = gradleMemoryProfileLabel(profile)
        isAllCaps = false
        textSize = 11f
        setTextColor(StudioUi.color(if (selected) StudioUi.Text else StudioUi.TextSoft))
        minimumHeight = 0
        minHeight = dp(36)
        minWidth = 0
        background = StudioUi.ripple(
            host,
            if (selected) {
                StudioUi.rounded(host, fill = StudioUi.ButtonDanger, stroke = StudioUi.Green, radiusDp = 8)
            } else {
                StudioUi.rounded(host, fill = StudioUi.ButtonNormal, stroke = StudioUi.ButtonBorder, radiusDp = 8)
            }
        )
        setPadding(dp(10), dp(6), dp(10), dp(6))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(4) }
        setOnClickListener { onClick() }
        StudioUi.touchLift(this)
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button =
        secondaryButton(label, onClick)

    private fun primaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t(label)
            isAllCaps = false
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.Text))
            minimumHeight = 0
            minHeight = dp(36)
            minWidth = 0
            background = StudioUi.ripple(
                host,
                StudioUi.rounded(host, fill = StudioUi.ButtonPrimary, stroke = StudioUi.Blue, radiusDp = 8)
            )
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { (this as ViewGroup.MarginLayoutParams).marginStart = dp(6) }
            setOnClickListener { onClick() }
            StudioUi.touchLift(this)
        }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t(label)
            isAllCaps = false
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            minimumHeight = 0
            minHeight = dp(36)
            minWidth = 0
            background = StudioUi.ripple(
                host,
                StudioUi.rounded(host, fill = StudioUi.ButtonNormal, stroke = StudioUi.ButtonBorder, radiusDp = 8)
            )
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { (this as ViewGroup.MarginLayoutParams).marginStart = dp(6) }
            setOnClickListener { onClick() }
            StudioUi.touchLift(this)
        }

    private fun dangerButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = t(label)
            isAllCaps = false
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.Red))
            minimumHeight = 0
            minHeight = dp(36)
            minWidth = 0
            background = StudioUi.ripple(
                host,
                StudioUi.rounded(host, fill = StudioUi.ButtonDanger, stroke = StudioUi.ButtonDangerBorder, radiusDp = 8)
            )
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { (this as ViewGroup.MarginLayoutParams).marginStart = dp(6) }
            setOnClickListener { onClick() }
            StudioUi.touchLift(this)
        }

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun toast(message: String) {
        Toast.makeText(this, t(message), Toast.LENGTH_SHORT).show()
    }

    private fun confirmDialog(message: String, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(t(message))
            .setPositiveButton(t("OK")) { _, _ -> onConfirm() }
            .setNegativeButton(t("Cancel"), null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
