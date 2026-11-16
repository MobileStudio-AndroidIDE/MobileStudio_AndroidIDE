package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

/**
 * Wine & Box64 manager — the missing installer UI for the Windows .exe runtime.
 *
 * The runtime itself lives in [WindowsExeEnvironment] (Box64 → Wine64 → .exe,
 * the standard Android approach: an x86_64 Wine build emulated by Box64 on
 * ARM64). Everything was wired for *running* an .exe, but nothing in the app
 * ever called [WindowsExeEnvironment.install], so a fresh install could never
 * get past "environment not installed". This screen is that entry point.
 */
class Win64ManagerActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, Win64ManagerActivity::class.java))
        }
    }

    private val env by lazy { WindowsExeEnvironment(this) }

    private lateinit var statusColumn: LinearLayout
    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView
    private lateinit var actionsRow: LinearLayout

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.aide.mobilestudio.UiTheme.resolve(this)
        setContentView(buildUi())
        refreshStatus()
        appendLine(t("box64 → wine64 → .exe 체인으로 Windows 프로그램을 실행합니다 (ARM64에서 x86_64 에뮬레이션)."))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun t(ko: String, en: String = ko): String =
        if (AppLanguage.isKorean(this)) ko else en

    private fun buildUi(): View {
        val p = StudioUi.palette
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.bg)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(buildHeader())
        statusColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(statusColumn) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(p.surface)
        }
        root.addView(actionsRow)
        console = TextView(this).apply {
            setTextColor(p.textSoft)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        consoleScroll = ScrollView(this).apply { addView(console) }
        root.addView(consoleScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        buildActions()
        return root
    }

    private fun buildHeader(): View {
        val p = StudioUi.palette
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(p.surface)
            setPadding(dp(14), dp(12), dp(14), dp(10))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            text = t("Wine & Box64", "Wine & Box64")
            setTextColor(p.text)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = "✕"
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setOnClickListener { finish() }
        })
        header.addView(row)
        header.addView(TextView(this).apply {
            text = t(
                "실행 체인: linker64 → box64(ARM64) → wine64(x86_64) → app.exe",
                "Chain: linker64 → box64(ARM64) → wine64(x86_64) → app.exe",
            )
            setTextColor(p.textSoft)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        })
        return header
    }

    private fun buildActions() {
        actionsRow.removeAllViews()
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun add(row: LinearLayout, label: String, action: () -> Unit) {
            row.addView(
                button(label) { action() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) },
            )
        }
        add(row1, t("전체 설치", "Install all")) { installAll() }
        add(row1, t("상태 새로고침", "Refresh")) { refreshStatus() }
        add(row1, t("건강 검사", "Health check")) { healthCheck() }
        add(row2, t("Wine prefix 초기화", "Boot prefix")) { bootPrefix() }
        add(row2, t("체인 테스트", "Chain test")) { chainTest() }
        add(row2, t("버전 확인", "Versions")) { showVersions() }
        actionsRow.addView(row1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        actionsRow.addView(row2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(StudioUi.palette.onAccent)
            setBackgroundColor(StudioUi.palette.accent)
            setOnClickListener { if (busy) toast(t("작업이 진행 중입니다", "A task is already running")) else onClick() }
        }

    // ------------------------------------------------------------- rendering

    private fun refreshStatus() {
        statusColumn.removeAllViews()
        statusColumn.addView(sectionTitle(t("구성 요소", "Components")))
        addComponent(
            t("box64 (x86_64 에뮬레이터)", "box64 (x86_64 emulator)"),
            env.effectiveBox64(),
            env.box64Kind(),
        )
        addComponent(t("glibc loader (ld-linux-aarch64)", "glibc loader (ld-linux-aarch64)"), env.loaderPath(), null)
        addComponent(t("wine64 (x86_64 Wine)", "wine64 (x86_64 Wine)"), env.wine64Path(), null)

        val prefix = statusRow(
            t("Wine prefix", "Wine prefix"),
            if (env.isPrefixBooted) t("초기화 완료", "booted") else t("미초기화", "not booted"),
            if (env.isPrefixBooted) StudioUi.palette.success else StudioUi.palette.warning,
        )
        statusColumn.addView(prefix)
        statusColumn.addView(sectionTitle(t("설치 위치", "Install location")))
        statusColumn.addView(TextView(this).apply {
            text = File(filesDir, "toolchain/win64").absolutePath
            setTextColor(StudioUi.palette.muted)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(14), 0, dp(14), dp(10))
            setTextIsSelectable(true)
        })
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(StudioUi.palette.textSoft)
        textSize = 11f
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(14), dp(12), dp(14), dp(4))
    }

    private fun statusRow(name: String, value: String, color: Int): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(6))
            addView(TextView(this@Win64ManagerActivity).apply {
                this.text = name
                setTextColor(StudioUi.palette.text)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@Win64ManagerActivity).apply {
                this.text = value
                setTextColor(color)
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
            })
        }

    private fun addComponent(name: String, file: File?, kind: String?) {
        val installed = file != null && file.exists()
        val detail = buildString {
            append(if (installed) t("설치됨", "installed") else t("없음", "missing"))
            kind?.takeIf { it.isNotBlank() && it != "missing" }?.let { append(" · $it") }
            file?.let { append(" · ${formatSize(it.length())}") }
        }
        val row = statusRow(name, detail, if (installed) StudioUi.palette.success else StudioUi.palette.error)
        row.getChildAt(1).setOnClickListener {
            file?.absolutePath?.let { path ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path))
                toast(t("경로 복사됨", "Path copied"))
            }
        }
        statusColumn.addView(row)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }

    // -------------------------------------------------------------- actions

    private fun installAll() = runTask(t("전체 설치 시작…", "Installing all components…")) { progress ->
        env.ensureBox64Valid()
        env.install(progress)
    }

    private fun healthCheck() = runTask(t("건강 검사…", "Health check…")) { progress ->
        val repaired = env.ensureComponentHealth(progress)
        progress(if (repaired) t("손상된 구성 요소를 재설치했습니다", "Repaired corrupted components") else t("모든 구성 요소 정상", "All components healthy"))
    }

    private fun bootPrefix() = runTask(t("Wine prefix 초기화…", "Booting Wine prefix…")) { progress ->
        if (!env.isInstalled) {
            progress(t("먼저 전체 설치를 실행하세요", "Run 'Install all' first"))
            return@runTask
        }
        env.bootstrapPrefix(progress)
    }

    private fun chainTest() = runTask(t("체인 테스트…", "Chain test…")) { line ->
        env.runChainTest { msg -> line(msg) }
    }

    private fun showVersions() = runTask(t("버전 확인…", "Checking versions…")) { progress ->
        val wine = env.versionString()
        val box = StringBuilder().also { sb ->
            val code = env.runBox64(listOf("--version"), 60_000) { line -> sb.appendLine(line) }
            if (code != 0) sb.append(t("(종료 코드 $code)", "(exit $code)"))
        }.toString().trim()
        progress(
            t("wine64: ", "wine64: ") + (wine ?: t("설치되지 않음", "not installed")) + "\n" +
                t("box64: ", "box64: ") + box.ifBlank { t("실행 실패", "failed") },
        )
    }

    /**
     * Runs a blocking environment task on a worker thread while keeping the
     * console live. [progress] is always marshalled back to the UI thread.
     */
    private fun runTask(startMessage: String, task: ((String) -> Unit) -> Unit) {
        if (busy) {
            toast(t("작업이 진행 중입니다", "A task is already running"))
            return
        }
        busy = true
        appendLine("═══ $startMessage")
        Thread {
            try {
                task { msg -> appendLine(msg) }
            } catch (e: Exception) {
                appendLine(t("오류: ${e.message}", "Error: ${e.message}"))
            } finally {
                busy = false
                runOnUiThread { refreshStatus() }
            }
        }.apply { isDaemon = true; name = "Win64Manager" }.start()
    }

    private fun appendLine(message: String) {
        runOnUiThread {
            if (!::console.isInitialized) return@runOnUiThread
            console.append(message.trimEnd() + "\n")
            consoleScroll.post { consoleScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
