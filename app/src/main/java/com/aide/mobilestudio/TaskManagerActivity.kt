package com.aide.mobilestudio

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.opengl.GLES20
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.ln

class TaskManagerActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, TaskManagerActivity::class.java))
        }
    }

    private lateinit var rootContainer: LinearLayout
    private lateinit var tabContent: ScrollView
    private lateinit var tabButtons: MutableList<TextView>
    private val handler = Handler(Looper.getMainLooper())
    private var refreshRunning = false
    private var currentTab = 0
    private var processFilterQuery: String = ""
    private var processSearchFocused: Boolean = false
    private val processSearchRenderRunnable = Runnable {
        if (currentTab == 0) refreshCurrentTab()
    }
    private val tabs = listOf("프로세스", "성능", "배터리")
    private val tabIcons = listOf("프로세스", "성능", "배터리")

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastNetworkTime = 0L
    private var lastCpuTotal = 0L
    private var lastCpuIdle = 0L
    private var cpuHistory = FloatArray(RealtimeGraphView.MAX_POINTS) { Float.NaN }
    private var cpuCount = 0
    private var ramHistory = FloatArray(RealtimeGraphView.MAX_POINTS) { Float.NaN }
    private var ramCount = 0
    private var netRxHistory = FloatArray(RealtimeGraphView.MAX_POINTS) { Float.NaN }
    private var netTxHistory = FloatArray(RealtimeGraphView.MAX_POINTS) { Float.NaN }
    private var netCount = 0
    private var diskReadHistory = FloatArray(RealtimeGraphView.MAX_POINTS) { Float.NaN }
    private var diskWriteHistory = FloatArray(RealtimeGraphView.MAX_POINTS) { Float.NaN }
    private var diskCount = 0
    private var gpuHistory = FloatArray(RealtimeGraphView.MAX_POINTS) { Float.NaN }
    private var gpuCount = 0
    private var lastDiskRead = 0L
    private var lastDiskWrite = 0L
    private var lastDiskTime = 0L
    private var lastDiskIoTimeMs = 0L
    private var lastDiskReadBytesPerSec = 0L
    private var lastDiskWriteBytesPerSec = 0L
    private var cachedDiskActivityPct = 0f
    private var sessionStartTime = System.currentTimeMillis()

    data class DiskDevice(
        val name: String,
        val displayName: String,
        var lastIoTimeMs: Long = 0,
        var lastSectorsRead: Long = 0,
        var lastSectorsWritten: Long = 0,
        var lastTime: Long = 0,
        var activityPct: Float = 0f,
        var readBytesPerSec: Long = 0,
        var writeBytesPerSec: Long = 0
    )
    private var diskDevices = mutableListOf<DiskDevice>()

    private var selectedPerfComponent = "CPU"
    private lateinit var perfSidebar: LinearLayout
    private lateinit var perfDetail: LinearLayout
    private var sidebarItems = mutableMapOf<String, SidebarItem>()

    private var prevProcessCpuTimes = mutableMapOf<Int, Long>()
    private var prevTotalCpuTime = 0L
    private var firstCpuRead = true

    private var cachedCpuUsage = 0.0
    private var cachedDiskReadSpeed = 0L
    private var cachedDiskWriteSpeed = 0L

    data class SidebarItem(
        val container: LinearLayout,
        val miniGraph: MiniGraphView,
        val valueText: TextView,
        val detailText: TextView
    )

    data class ProcessInfo(
        val pid: Int,
        val name: String,
        val packageName: String,
        val state: String,
        val cpuPct: Double,
        val memPssKB: Int
    )

    data class PsEntry(
        val name: String,
        val stat: String,
        val rssKB: Long
    )

    /**
     * Parses `ps -A` output to enumerate ALL processes (including other apps like YouTube).
     * /proc/<pid>/cmdline of other apps is SELinux-restricted, but toybox ps often
     * still exposes PID/STAT/NAME/RSS. Falls back to `su -c ps -A` when rooted.
     */
    private fun parsePsOutput(): Map<Int, PsEntry> {
        val map = mutableMapOf<Int, PsEntry>()
        val cmds = mutableListOf(listOf("/system/bin/ps", "-A", "-o", "PID,STAT,NAME,RSS"))
        val suPath = File("/system/bin/su").takeIf { it.isFile }
            ?: File("/system/xbin/su").takeIf { it.isFile }
            ?: File("/sbin/su").takeIf { it.isFile }
        if (suPath != null) {
            cmds.add(listOf(suPath.absolutePath, "-c", "ps -A -o PID,STAT,NAME,RSS"))
        }
        for (cmd in cmds) {
            try {
                val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val reader = proc.inputStream.bufferedReader()
                var first = true
                reader.forEachLine { line ->
                    if (first) { first = false; return@forEachLine }
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val pid = parts[0].toIntOrNull() ?: return@forEachLine
                        val stat = if (parts.size >= 4) parts[1] else ""
                        val rss = if (parts.size >= 4) parts.last().toLongOrNull() ?: 0L else 0L
                        val name = if (parts.size >= 4) parts.drop(2).dropLast(1).joinToString(" ") else parts[2]
                        if (name.isNotEmpty() && !map.containsKey(pid)) {
                            map[pid] = PsEntry(name, stat, rss)
                        }
                    }
                }
                proc.waitFor()
                if (map.isNotEmpty()) break
            } catch (_: Exception) {}
        }
        return map
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStartTime = System.currentTimeMillis()
        setContentView(buildLayout())
        switchTab(0)
    }

    override fun onResume() {
        super.onResume()
        refreshRunning = true
        handler.postDelayed(refreshRunnable, 1000)
    }

    override fun onPause() {
        super.onPause()
        refreshRunning = false
        handler.removeCallbacks(refreshRunnable)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        MobileStudioCrashGuard.finishSafely(this)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!refreshRunning) return
            // Keep the process search field stable while the user is typing.
            if (currentTab == 0 && processSearchFocused) {
                handler.postDelayed(this, 1000)
                return
            }
            refreshCurrentTab()
            handler.postDelayed(this, 1000)
        }
    }

    private fun buildLayout(): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.CardBg))
        }
        outer.addView(buildHeader())
        outer.addView(buildTabBar())

        tabContent = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            setBackgroundColor(StudioUi.color(StudioUi.CardBg))
        }
        outer.addView(tabContent)

        rootContainer = outer
        return outer
    }

    private fun buildHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(StudioUi.color(StudioUi.ButtonNormal))
            addView(TextView(this@TaskManagerActivity).apply {
                text = "작업 관리자"
                setTextColor(StudioUi.color(StudioUi.Text))
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(makeButton("닫기") {
                MobileStudioCrashGuard.finishSafely(this@TaskManagerActivity)
            })
        }
    }

    private fun buildTabBar(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(2), dp(8), dp(2))
            setBackgroundColor(StudioUi.color(StudioUi.ButtonNormal))
        }
        tabButtons = mutableListOf()
        for ((i, label) in tabIcons.withIndex()) {
            val btn = TextView(this).apply {
                text = label
                setTextColor(if (i == 0) Color.parseColor(StudioUi.ActiveBlue) else StudioUi.color("#AAAAAA"))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(if (i == 0) StudioUi.color("#383838") else Color.TRANSPARENT)
                }
                background = bg
                setOnClickListener { switchTab(i) }
            }
            tabButtons.add(btn)
            row.addView(btn)
        }
        scroll.addView(row)
        return scroll
    }

    private fun switchTab(index: Int) {
        currentTab = index
        for ((i, btn) in tabButtons.withIndex()) {
            btn.setTextColor(if (i == index) Color.parseColor(StudioUi.ActiveBlue) else StudioUi.color("#AAAAAA"))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(if (i == index) StudioUi.color("#383838") else Color.TRANSPARENT)
            }
            btn.background = bg
        }
        tabContent.removeAllViews()
        refreshCurrentTab()
    }

    private fun refreshCurrentTab() {
        tabContent.removeAllViews()
        when (currentTab) {
            0 -> renderProcesses()
            1 -> renderPerformance()
            2 -> renderBattery()
            else -> renderSystemInfo()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // BATTERY TAB (Windows 11 style)
    // ════════════════════════════════════════════════════════════════
    private fun renderBattery() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        container.addView(sectionHeader("배터리"))

        val (levelStr, temp, status, voltage, health) = getBatteryDetail()
        val level = levelStr.toIntOrNull() ?: 0

        // Big battery visual: progress bar with level
        val batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = level
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(
                if (level <= 20) StudioUi.color(StudioUi.Red) else StudioUi.color(StudioUi.Green)
            )
        }
        container.addView(batteryBar)

        val bigText = TextView(this).apply {
            text = "$level%"
            setTextColor(Color.WHITE)
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(8), 0, dp(8))
        }
        container.addView(bigText)

        val stats = listOf(
            "상태" to status,
            "온도" to temp,
            "전압" to voltage,
            "상태(Health)" to health
        )
        for ((label, value) in stats) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(StudioUi.color(StudioUi.CardBg))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 1
                }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(StudioUi.color("#AAAAAA"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = value
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            })
            container.addView(row)
        }

        tabContent.addView(container)
    }

    /** Returns detailed battery info: (level%, temperature, status, voltage, health). */
    private fun getBatteryDetail(): Array<String> {
        var level = 0
        var temp = "-"
        var status = "-"
        var voltage = "-"
        var health = "-"
        try {
            val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val lvl = intent.getIntExtra("level", -1)
                val scale = intent.getIntExtra("scale", 100)
                if (lvl >= 0 && scale > 0) level = lvl * 100 / scale
                val t = intent.getIntExtra("temperature", Int.MIN_VALUE)
                if (t != Int.MIN_VALUE) temp = String.format("%.1f °C", t / 10.0)
                val v = intent.getIntExtra("voltage", Int.MIN_VALUE)
                if (v != Int.MIN_VALUE) voltage = "${v} mV"
                val st = intent.getIntExtra("status", -1)
                status = when (st) {
                    android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "충전 중"
                    android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "방전 중"
                    android.os.BatteryManager.BATTERY_STATUS_FULL -> "충전 완료"
                    android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "충전 안 됨"
                    else -> "알 수 없음"
                }
                val h = intent.getIntExtra("health", -1)
                health = when (h) {
                    android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "좋음"
                    android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "과열"
                    android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "방전됨"
                    android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "과전압"
                    else -> "알 수 없음"
                }
            }
        } catch (_: Exception) {}
        return arrayOf("$level", temp, status, voltage, health)
    }

    // ════════════════════════════════════════════════════════════════
    // PROCESSES TAB
    // ════════════════════════════════════════════════════════════════
    private fun renderProcesses() {
        val processes = getProcessList()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }

        val cpuTotal = readSystemCpuTime()
        val procCount = processes.size
        val totalMem = run {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            mi.totalMem / (1024 * 1024)
        }
        val usedMemMB = processes.sumOf { it.memPssKB.toLong() } / 1024
        val memPct = if (totalMem > 0) usedMemMB * 100 / totalMem else 0
        val cpuPctGlobal = if (cachedCpuUsage > 0.0) String.format("%.0f%%", cachedCpuUsage) else "0%"
        val diskSpeed = if (lastDiskReadBytesPerSec + lastDiskWriteBytesPerSec > 0)
            formatBytesPerSec(lastDiskReadBytesPerSec + lastDiskWriteBytesPerSec) else "0 MB/s"
        val netSpeed = formatBytesPerSec(lastRxBytes + lastTxBytes)

        container.addView(sectionHeader("${procCount}개 프로세스"))

        // Global totals header (Windows 11 style): CPU | 메모리 | 디스크 | 네트워크
        val totalsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(StudioUi.color("#2B2B2B"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            }
        }
        val totals = listOf(
            Triple("CPU", cpuPctGlobal, Triple(cpuHistory, StudioUi.color(StudioUi.Blue), 100f)),
            Triple("메모리", "${memPct}%", Triple(ramHistory, StudioUi.color(StudioUi.Purple), null)),
            Triple("디스크", diskSpeed, Triple(diskReadHistory, StudioUi.color(StudioUi.Amber), null)),
            Triple("네트워크", netSpeed, Triple(netRxHistory, StudioUi.color(StudioUi.Purple), null))
        )
        for ((label, value, graphInfo) in totals) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(this@TaskManagerActivity).apply {
                text = label
                setTextColor(StudioUi.color("#AAAAAA"))
                textSize = 10f
            })
            col.addView(TextView(this@TaskManagerActivity).apply {
                text = value
                setTextColor(StudioUi.color(StudioUi.Green))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
            // Windows 11 style: mini live graph under each summary value
            val (history, color, yMaxCap) = graphInfo
            col.addView(MiniGraphView(this).apply {
                graphColor = color
                seedHistory(history)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)).apply {
                    topMargin = dp(4)
                }
            })
            totalsRow.addView(col)
        }
        container.addView(totalsRow)

        // Search box (이름 또는 PID)
        val searchInput = EditText(this).apply {
            hint = "검색할 이름, PID를 입력하세요."
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setHintTextColor(StudioUi.color("#777777"))
            textSize = 12f
            isSingleLine = true
            setText(processFilterQuery)
            setSelection(processFilterQuery.length)
            onFocusChangeListener = View.OnFocusChangeListener { _, has -> processSearchFocused = has }
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val q = s?.toString() ?: ""
                    if (q != processFilterQuery) {
                        processFilterQuery = q
                        handler.removeCallbacks(processSearchRenderRunnable)
                        handler.postDelayed(processSearchRenderRunnable, 250)
                    }
                }
            })
            setPadding(dp(10), dp(6), dp(10), dp(6))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(StudioUi.color(StudioUi.ButtonNormal))
                setStroke(1, StudioUi.color("#444444"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
        }
        container.addView(searchInput)

        val headerRow = tableRow(
            listOf("이름" to 3f, "상태" to 0.8f, "CPU" to 0.8f, "메모리" to 1f, "디스크" to 0.9f, "네트워크" to 0.9f),
            isHeader = true
        )
        container.addView(headerRow)

        val query = processFilterQuery.trim().lowercase()
        val filtered = if (query.isEmpty()) processes else processes.filter {
            it.name.lowercase().contains(query) || it.pid.toString().contains(query) || it.packageName.lowercase().contains(query)
        }

        if (filtered.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "일치하는 프로세스가 없습니다."
                setTextColor(StudioUi.color("#777777"))
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
        }

        for (proc in filtered) {
            val memMB = proc.memPssKB / 1024
            val stateStr = proc.state
            val cpuStr = if (proc.cpuPct > 0.1) String.format("%.1f%%", proc.cpuPct) else "<0.1%"
            val memStr = if (memMB > 0) "${memMB} MB" else "-"

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setBackgroundColor(StudioUi.color(StudioUi.CardBg))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 1
                }
            }

            row.addView(TextView(this@TaskManagerActivity).apply {
                text = proc.name
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                textSize = 11f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f)
            })
            row.addView(TextView(this@TaskManagerActivity).apply {
                text = stateStr
                setTextColor(StudioUi.color("#AAAAAA"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
            })
            row.addView(TextView(this@TaskManagerActivity).apply {
                text = cpuStr
                setTextColor(if (proc.cpuPct > 10) StudioUi.color(StudioUi.Red) else StudioUi.color("#AAAAAA"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f)
            })
            row.addView(TextView(this@TaskManagerActivity).apply {
                text = memStr
                setTextColor(StudioUi.color("#AAAAAA"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            // Per-process disk/network unavailable without root — show dash
            row.addView(TextView(this@TaskManagerActivity).apply {
                text = "-"
                setTextColor(StudioUi.color("#666666"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
            })
            row.addView(TextView(this@TaskManagerActivity).apply {
                text = "-"
                setTextColor(StudioUi.color("#666666"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f)
            })

            // Long-press row → kill / info options
            if (proc.pid > 100 && proc.packageName.isNotEmpty()) {
                row.setOnLongClickListener {
                    showProcessActions(proc)
                    true
                }
            }

            container.addView(row)
        }

        tabContent.addView(container)

        if (processSearchFocused) {
            searchInput.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(searchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Windows 11 style process action popup: 종료 작업 / 자세히. */
    private fun showProcessActions(proc: ProcessInfo) {
        val popup = PopupWindow(this).apply {
            width = dp(220)
            height = ViewGroup.LayoutParams.WRAP_CONTENT
            isOutsideTouchable = true
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(StudioUi.color(StudioUi.ButtonNormal))
                setStroke(1, StudioUi.color("#505050"))
            })
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        content.addView(TextView(this).apply {
            text = proc.name
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), dp(8), dp(10), dp(4))
        })
        content.addView(TextView(this).apply {
            text = "종료 작업"
            setTextColor(StudioUi.color(StudioUi.Red))
            textSize = 12f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                killApp(proc.pid, proc.name, proc.packageName)
                popup.dismiss()
            }
        })
        popup.contentView = content
        popup.showAtLocation(tabContent, Gravity.CENTER, 0, 0)
    }

    private fun formatBytesPerSec(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB/s", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.0f KB/s", bytes / 1024.0)
        else -> "${bytes} B/s"
    }

    private fun getProcessList(): List<ProcessInfo> {
        val result = mutableListOf<ProcessInfo>()
        val procDir = File("/proc")
        val pids = procDir.listFiles()
            ?.mapNotNull { it.name.toIntOrNull() }
            ?.filter { it > 0 }
            ?: return emptyList()

        val memMap = mutableMapOf<Int, Int>()
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val memInfos = am.getProcessMemoryInfo(pids.toIntArray())
            pids.forEachIndexed { i, pid ->
                if (i < memInfos.size) {
                    memMap[pid] = memInfos[i]?.totalPss ?: 0
                }
            }
        } catch (_: Exception) {}

        for (pid in pids) {
            if (memMap[pid] == null || memMap[pid] == 0) {
                memMap[pid] = readProcessMemKB(pid)
            }
        }

        val currentTimes = mutableMapOf<Int, Long>()
        for (pid in pids) {
            try {
                val stat = File("/proc/$pid/stat").readText()
                val lastParen = stat.lastIndexOf(')')
                if (lastParen > 0 && lastParen + 2 < stat.length) {
                    val fields = stat.substring(lastParen + 2).split(" ")
                    if (fields.size > 12) {
                        val utime = fields[11].toLongOrNull() ?: 0
                        val stime = fields[12].toLongOrNull() ?: 0
                        currentTimes[pid] = utime + stime
                    }
                }
            } catch (_: Exception) {}
        }

        val totalCpu = readSystemCpuTime()
        val psEntries = parsePsOutput()

        for (pid in pids) {
            val psEntry = psEntries[pid]
            var name = readProcessName(pid)
            if (name.isEmpty() && psEntry != null) name = psEntry.name
            if (name.isEmpty()) continue

            val prevTime = prevProcessCpuTimes[pid]
            val curTime = currentTimes[pid]
            val cpuPct = if (prevTime != null && curTime != null && !firstCpuRead && totalCpu > prevTotalCpuTime) {
                val procDelta = curTime - prevTime
                val totalDelta = totalCpu - prevTotalCpuTime
                if (totalDelta > 0) (procDelta * 100.0 / totalDelta).coerceIn(0.0, 100.0) else 0.0
            } else 0.0

            var state = readProcessState(pid)
            if (state == "?" && psEntry != null && psEntry.stat.isNotEmpty()) {
                state = when (psEntry.stat.first()) {
                    'R' -> "실행"
                    'S' -> "대기"
                    'D' -> "I/O"
                    'Z' -> "좀비"
                    'T' -> "중지"
                    'I' -> "유휴"
                    else -> "?"
                }
            }
            var memPss = memMap[pid] ?: 0
            if (memPss == 0 && psEntry != null && psEntry.rssKB > 0) memPss = psEntry.rssKB.toInt()
            var pkgName = readPackageName(pid)
            if (pkgName.isEmpty() && psEntry != null) pkgFallback(psEntry.name)?.let { pkgName = it }

            result.add(ProcessInfo(pid, name, pkgName, state, cpuPct, memPss))
        }

        // Merge ps -A entries not visible in /proc (rooted devices / hidden pids)
        for ((pid, entry) in psEntries) {
            if (pid in pids) continue
            if (entry.name.isEmpty()) continue
            result.add(ProcessInfo(pid, entry.name, pkgFallback(entry.name) ?: "", stateFromStat(entry.stat), 0.0, entry.rssKB.toInt()))
        }

        prevProcessCpuTimes.clear()
        prevProcessCpuTimes.putAll(currentTimes)
        prevTotalCpuTime = totalCpu
        firstCpuRead = false

        return result.sortedByDescending { it.memPssKB }
    }

    /** Extracts a package-like name (first dot-separated token) from a process name. */
    private fun pkgFallback(name: String): String? {
        val first = name.substringBeforeLast('/')
        return if (first.contains('.')) first.split(":")[0] else null
    }

    private fun stateFromStat(stat: String): String = when (stat.firstOrNull()) {
        'R' -> "실행"
        'S' -> "대기"
        'D' -> "I/O"
        'Z' -> "좀비"
        'T' -> "중지"
        'I' -> "유휴"
        else -> "?"
    }

    private fun readProcessName(pid: Int): String {
        try {
            val cmdline = File("/proc/$pid/cmdline").readText().trim('\u0000')
            if (cmdline.isNotEmpty()) {
                val shortName = cmdline.substringAfterLast('/')
                return shortName
            }
        } catch (_: Exception) {}
        try {
            val status = File("/proc/$pid/status").readText()
            val nameLine = status.lines().firstOrNull { it.startsWith("Name:") }
            if (nameLine != null) return nameLine.substringAfter(":").trim()
        } catch (_: Exception) {}
        return ""
    }

    private fun readPackageName(pid: Int): String {
        try {
            val cmdline = File("/proc/$pid/cmdline").readText().trim('\u0000')
            if (cmdline.isNotEmpty()) {
                val shortName = cmdline.substringAfterLast('/')
                return shortName.split(":")[0]
            }
        } catch (_: Exception) {}
        return ""
    }

    private fun readProcessState(pid: Int): String {
        try {
            val stat = File("/proc/$pid/stat").readText()
            val lastParen = stat.lastIndexOf(')')
            if (lastParen > 0 && lastParen + 2 < stat.length) {
                return when (stat[lastParen + 2]) {
                    'R' -> "실행"
                    'S' -> "대기"
                    'D' -> "I/O"
                    'Z' -> "좀비"
                    'T' -> "중지"
                    'I' -> " 유휴"
                    else -> "?"
                }
            }
        } catch (_: Exception) {}
        return "?"
    }

    private fun readProcessMemKB(pid: Int): Int {
        try {
            val lines = File("/proc/$pid/status").readLines()
            val rssLine = lines.firstOrNull { it.startsWith("VmRSS:") }
            if (rssLine != null) {
                return rssLine.substringAfter(":").trim().split("\\s+".toRegex())[0].toIntOrNull() ?: 0
            }
        } catch (_: Exception) {}
        return 0
    }

    private fun killApp(pid: Int, name: String, packageName: String) {
        AlertDialog.Builder(this)
            .setTitle("작업 종료")
            .setMessage("\"$name\" (PID: $pid)을(를) 종료하시겠습니까?")
            .setPositiveButton("종료") { _, _ ->
                var killed = false
                try {
                    if (packageName.contains(".")) {
                        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                        am.killBackgroundProcesses(packageName)
                        killed = true
                    }
                } catch (_: Exception) {}
                try {
                    Process.killProcess(pid)
                    killed = true
                } catch (_: Exception) {}

                if (killed) {
                    Toast.makeText(this, "\"$name\" 종료 요청됨", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "종료 실패 — 권한이 필요할 수 있습니다", Toast.LENGTH_SHORT).show()
                }
                handler.postDelayed({ refreshCurrentTab() }, 500)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun tableRow(cols: List<Pair<String, Float>>, isHeader: Boolean = false): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), if (isHeader) dp(8) else dp(5), dp(10), if (isHeader) dp(8) else dp(5))
            setBackgroundColor(if (isHeader) StudioUi.color(StudioUi.ButtonNormal) else StudioUi.color(StudioUi.CardBg))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 1
            }
            for ((text, weight) in cols) {
                addView(TextView(this@TaskManagerActivity).apply {
                    this.text = text
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    textSize = if (isHeader) 11f else 11f
                    typeface = if (isHeader) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
                })
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    // PERFORMANCE TAB
    // ════════════════════════════════════════════════════════════════
    private fun renderPerformance() {
        updateMetrics()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        perfSidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.CardBg))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.MATCH_PARENT)
        }

        perfDetail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.CardBg))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

        sidebarItems.clear()
        addSidebarItem("CPU", "%", Color.parseColor("#50A0FF"))
        addSidebarItem("메모리", "%", Color.parseColor("#A855F7"))
        addSidebarItem("디스크", "%", Color.parseColor("#F59E0B"))
        addSidebarItem("이더넷", "Kbps", Color.parseColor("#EC4899"))
        addSidebarItem("GPU", "%", Color.parseColor("#22C55E"))
        addSidebarItem("배터리", "%", StudioUi.color(StudioUi.Green))

        selectPerfComponent(selectedPerfComponent)

        outer.addView(perfSidebar)
        outer.addView(perfDetail)
        tabContent.addView(outer)
    }

    private fun addSidebarItem(name: String, unit: String, color: Int) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(Color.TRANSPARENT)
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(2)
            }
        }

        val miniGraph = MiniGraphView(this).apply {
            graphColor = color
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                bottomMargin = dp(4)
            }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(2))
        }
        titleRow.addView(TextView(this@TaskManagerActivity).apply {
            text = name
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val valueText = TextView(this@TaskManagerActivity).apply {
            text = "0$unit"
            setTextColor(color)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
        }
        titleRow.addView(valueText)

        val detailText = TextView(this@TaskManagerActivity).apply {
            setTextColor(StudioUi.color("#888888"))
            textSize = 10f
        }

        container.addView(miniGraph)
        container.addView(titleRow)
        container.addView(detailText)

        container.setOnClickListener { selectPerfComponent(name) }

        sidebarItems[name] = SidebarItem(container, miniGraph, valueText, detailText)
        perfSidebar.addView(container)
    }

    private fun selectPerfComponent(name: String) {
        selectedPerfComponent = name
        for ((n, item) in sidebarItems) {
            val bg = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(if (n == name) StudioUi.color("#383838") else Color.TRANSPARENT)
            }
            item.container.background = bg
        }
        perfDetail.removeAllViews()
        when (name) {
            "CPU" -> renderCpuDetail()
            "메모리" -> renderMemoryDetail()
            "디스크" -> renderDiskDetail()
            "이더넷" -> renderNetworkDetail()
            "GPU" -> renderGpuDetail()
            "배터리" -> renderBatteryDetail()
        }
    }

    /** Performance-tab battery detail pane (Windows 11 style). */
    private fun renderBatteryDetail() {
        val (levelStr, temp, status, voltage, health) = getBatteryDetail()
        val level = levelStr.toIntOrNull() ?: 0

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        container.addView(TextView(this).apply {
            text = "배터리"
            setTextColor(Color.WHITE)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
        })

        val batteryBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = level
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(20)).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            }
            progressTintList = android.content.res.ColorStateList.valueOf(
                if (level <= 20) StudioUi.color(StudioUi.Red) else StudioUi.color(StudioUi.Green)
            )
        }
        container.addView(batteryBar)

        val stats = listOf(
            "충전 상태" to status,
            "배터리 잔량" to "$level%",
            "온도" to temp,
            "전압" to voltage,
            "상태(Health)" to health
        )
        for ((label, value) in stats) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setBackgroundColor(StudioUi.color(StudioUi.CardBg))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = 1
                }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(StudioUi.color("#AAAAAA"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = value
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            })
            container.addView(row)
        }

        perfDetail.removeAllViewsInLayout()
        perfDetail.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun updateMetrics() {
        cachedCpuUsage = readCpuUsage()
        System.arraycopy(cpuHistory, 0, cpuHistory, 1, RealtimeGraphView.MAX_POINTS - 1)
        cpuHistory[RealtimeGraphView.MAX_POINTS - 1] = cachedCpuUsage.toFloat()
        if (cpuCount < RealtimeGraphView.MAX_POINTS) cpuCount++
        sidebarItems["CPU"]?.let {
            it.valueText.text = String.format("%.0f%%", cachedCpuUsage)
            it.detailText.text = "${Runtime.getRuntime().availableProcessors()}코어 ${readCpuSpeed() / 1000}GHz"
            it.miniGraph.pushValue(cachedCpuUsage.toFloat())
        }

        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val totalRam = mi.totalMem / (1024 * 1024)
        val usedRam = (mi.totalMem - mi.availMem) / (1024 * 1024)
        val ramPct = if (totalRam > 0) (usedRam * 100 / totalRam) else 0
        System.arraycopy(ramHistory, 0, ramHistory, 1, RealtimeGraphView.MAX_POINTS - 1)
        ramHistory[RealtimeGraphView.MAX_POINTS - 1] = ramPct.toFloat()
        if (ramCount < RealtimeGraphView.MAX_POINTS) ramCount++
        sidebarItems["메모리"]?.let {
            it.valueText.text = "${ramPct}%"
            it.detailText.text = "${usedRam}/${totalRam} MB"
            it.miniGraph.pushValue(ramPct.toFloat())
        }

        val diskPct = readDiskActivity()
        System.arraycopy(diskReadHistory, 0, diskReadHistory, 1, RealtimeGraphView.MAX_POINTS - 1)
        diskReadHistory[RealtimeGraphView.MAX_POINTS - 1] = diskPct
        if (diskCount < RealtimeGraphView.MAX_POINTS) diskCount++
        sidebarItems["디스크"]?.let {
            it.valueText.text = String.format("%.0f%%", diskPct)
            val diskNames = diskDevices.joinToString(", ") { d -> d.name }
            it.detailText.text = if (diskNames.isNotEmpty()) diskNames else "활성 시간"
            it.miniGraph.pushValue(diskPct)
        }

        val currentRx = readNetworkRxBytes()
        val currentTx = readNetworkTxBytes()
        val now = System.currentTimeMillis()
        var rxKbps = 0f
        var txKbps = 0f
        if (lastNetworkTime > 0) {
            val elapsed = (now - lastNetworkTime) / 1000.0
            if (elapsed > 0) {
                rxKbps = ((currentRx - lastRxBytes) / elapsed / 1024).toFloat()
                txKbps = ((currentTx - lastTxBytes) / elapsed / 1024).toFloat()
            }
        }
        lastRxBytes = currentRx
        lastTxBytes = currentTx
        lastNetworkTime = now
        System.arraycopy(netRxHistory, 0, netRxHistory, 1, RealtimeGraphView.MAX_POINTS - 1)
        netRxHistory[RealtimeGraphView.MAX_POINTS - 1] = rxKbps
        System.arraycopy(netTxHistory, 0, netTxHistory, 1, RealtimeGraphView.MAX_POINTS - 1)
        netTxHistory[RealtimeGraphView.MAX_POINTS - 1] = txKbps
        if (netCount < RealtimeGraphView.MAX_POINTS) netCount++
        sidebarItems["이더넷"]?.let {
            it.valueText.text = "${String.format("%.0f", rxKbps + txKbps)} Kbps"
            it.detailText.text = "↓${formatSpeed(rxKbps.toLong())} ↑${formatSpeed(txKbps.toLong())}"
            it.miniGraph.pushValue((rxKbps + txKbps))
        }

        val gpuPct = readGpuUsage()
        System.arraycopy(gpuHistory, 0, gpuHistory, 1, RealtimeGraphView.MAX_POINTS - 1)
        gpuHistory[RealtimeGraphView.MAX_POINTS - 1] = gpuPct
        if (gpuCount < RealtimeGraphView.MAX_POINTS) gpuCount++
        sidebarItems["GPU"]?.let {
            it.valueText.text = String.format("%.0f%%", gpuPct)
            it.detailText.text = getGpuName()
            it.miniGraph.pushValue(gpuPct)
        }

        // Battery (level % + status)
        val battLevel = try {
            val bi = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val l = bi?.getIntExtra("level", -1) ?: -1
            val s = bi?.getIntExtra("scale", 100) ?: 100
            if (l >= 0 && s > 0) l * 100 / s else 0
        } catch (_: Exception) { 0 }
        sidebarItems["배터리"]?.let {
            it.valueText.text = "$battLevel%"
            it.detailText.text = try {
                val bi = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                val st = bi?.getIntExtra("status", -1) ?: -1
                when (st) {
                    android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "충전 중"
                    android.os.BatteryManager.BATTERY_STATUS_FULL -> "충전 완료"
                    android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "방전 중"
                    else -> "사용 중"
                }
            } catch (_: Exception) { "-" }
            it.miniGraph.pushValue(battLevel.toFloat())
        }
    }

    // ════════════════════════════════════════════════════════════════
    // CPU DETAIL
    // ════════════════════════════════════════════════════════════════
    private fun renderCpuDetail() {
        val graph = RealtimeGraphView(this).apply {
            graphColor = StudioUi.color(StudioUi.Blue)
            title = "CPU"
            yMax = 100f
            yLabel = "%"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
            setPadding(0, 0, 0, dp(8))
        }
        for (i in 0 until cpuCount) graph.pushPrimary(cpuHistory[RealtimeGraphView.MAX_POINTS - cpuCount + i])
        perfDetail.addView(graph)

        val cores = Runtime.getRuntime().availableProcessors()
        val speedMhz = readCpuSpeed()
        val procCount = try {
            File("/proc").listFiles()?.filter { it.name.toIntOrNull() != null }?.size ?: 0
        } catch (_: Exception) { 0 }
        val threadCount = countSystemThreads()
        val uptimeStr = getSystemUptime()
        val sessionStr = formatDuration(System.currentTimeMillis() - sessionStartTime)

        perfDetail.addView(statsGrid(listOf<Pair<String, String>>(
            "사용률" to "${String.format("%.0f", cachedCpuUsage)}%",
            "속도" to "${String.format("%.2f", speedMhz / 1000.0)}GHz",
            "프로세스" to "$procCount",
            "스레드" to "$threadCount",
            "논리 프로세서" to "$cores",
            "코어" to "${(cores / 2).coerceAtLeast(1)}",
            "시스템 작동" to uptimeStr,
            "세션 시간" to sessionStr
        )))
    }

    private fun countSystemThreads(): Int {
        var total = 0
        try {
            File("/proc").listFiles()?.forEach { dir ->
                if (dir.name.toIntOrNull() != null) {
                    try {
                        val status = File(dir, "status").readText()
                        val threadsLine = status.lines().firstOrNull { it.startsWith("Threads:") }
                        total += threadsLine?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return total
    }

    private fun getSystemUptime(): String {
        try {
            val f = RandomAccessFile("/proc/uptime", "r")
            val line = f.readLine()
            f.close()
            val uptimeSeconds = line.split(" ")[0].toFloatOrNull()?.toLong() ?: 0
            val days = uptimeSeconds / 86400
            val hours = (uptimeSeconds % 86400) / 3600
            val minutes = (uptimeSeconds % 3600) / 60
            if (days > 0) return "${days}일 ${hours}시간 ${minutes}분"
            return "${hours}시간 ${minutes}분"
        } catch (_: Exception) {}
        return "알 수 없음"
    }

    // ════════════════════════════════════════════════════════════════
    // MEMORY DETAIL
    // ════════════════════════════════════════════════════════════════
    private fun renderMemoryDetail() {
        val graph = RealtimeGraphView(this).apply {
            graphColor = StudioUi.color(StudioUi.Purple)
            title = "메모리"
            yMax = 100f
            yLabel = "%"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
            setPadding(0, 0, 0, dp(8))
        }
        for (i in 0 until ramCount) graph.pushPrimary(ramHistory[RealtimeGraphView.MAX_POINTS - ramCount + i])
        perfDetail.addView(graph)

        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMB = memInfo.totalMem / (1024 * 1024)
        val usedMB = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
        val freeMB = memInfo.availMem / (1024 * 1024)
        val heapMax = Runtime.getRuntime().maxMemory() / (1024 * 1024)
        val heapUsed = Runtime.getRuntime().totalMemory() / (1024 * 1024)
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val cachedMB = readCachedMemory()

        perfDetail.addView(statsGrid(listOf<Pair<String, String>>(
            "사용 중" to "${usedMB} MB",
            "사용 가능" to "${freeMB} MB",
            "총 메모리" to "${totalMB} MB",
            "캐시" to "${cachedMB} MB",
            "Heap 사용" to "${heapUsed} MB",
            "Heap 최대" to "${heapMax} MB",
            "Native Heap" to "${nativeHeap} MB",
            "커밋" to "${usedMB} MB"
        )))

        perfDetail.addView(memoryBar("메모리 사용량", usedMB.toFloat(), totalMB.toFloat(), Color.parseColor("#A855F7")))
    }

    private fun memoryBar(label: String, used: Float, total: Float, color: Int): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        layout.addView(TextView(this).apply {
            text = "$label: ${used.toInt()}/${total.toInt()} MB"
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 12f
            setPadding(0, 0, 0, dp(4))
        })
        val barHeight = dp(20)
        val fillFraction = if (total > 0) (used / total).coerceIn(0f, 1f) else 0f
        val barContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight)
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(StudioUi.color(StudioUi.Border))
            }
        }
        if (fillFraction > 0.01f) {
            barContainer.addView(View(this).apply {
                background = GradientDrawable().apply {
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, fillFraction)
            })
        }
        if (fillFraction < 0.99f) {
            barContainer.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - fillFraction)
            })
        }
        layout.addView(barContainer)
        return layout
    }

    private fun readCachedMemory(): Long {
        try {
            val f = RandomAccessFile("/proc/meminfo", "r")
            var cached = 0L
            var buffers = 0L
            var line = f.readLine()
            while (line != null) {
                when {
                    line.startsWith("Cached:") -> cached = line.substringAfter(":").trim().split("\\s+".toRegex())[0].toLongOrNull() ?: 0
                    line.startsWith("Buffers:") -> buffers = line.substringAfter(":").trim().split("\\s+".toRegex())[0].toLongOrNull() ?: 0
                }
                line = f.readLine()
            }
            f.close()
            return (cached + buffers) / 1024
        } catch (_: Exception) {}
        return 0
    }

    // ════════════════════════════════════════════════════════════════
    // DISK DETAIL
    // ════════════════════════════════════════════════════════════════
    private fun renderDiskDetail() {
        readAllDiskStats()

        if (diskDevices.isEmpty()) {
            val storage = StatFs(Environment.getDataDirectory().path)
            val totalGB = storage.totalBytes / (1024.0 * 1024.0 * 1024.0)
            val availGB = storage.availableBytes / (1024.0 * 1024.0 * 1024.0)

            perfDetail.addView(statsGrid(listOf<Pair<String, String>>(
                "용량" to "${String.format("%.0f", totalGB)} GB",
                "남은 공간" to "${String.format("%.0f", availGB)} GB",
                "유형" to "eMMC/UFS"
            )))
            return
        }

        for (dev in diskDevices) {
            val graph = RealtimeGraphView(this).apply {
                graphColor = StudioUi.color(StudioUi.Amber)
                title = dev.displayName
                yMax = 100f
                yLabel = "%"
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(160))
                setPadding(0, 0, 0, dp(4))
            }
            perfDetail.addView(graph)

            perfDetail.addView(statsGrid(listOf<Pair<String, String>>(
                "활성 시간" to "${String.format("%.1f", dev.activityPct)}%",
                "읽기 속도" to "${formatSpeed(dev.readBytesPerSec / 1024)}",
                "쓰기 속도" to "${formatSpeed(dev.writeBytesPerSec / 1024)}",
                "장치" to dev.name,
                "유형" to getStorageType(dev.name)
            )))
        }
    }

    private fun getStorageType(name: String): String {
        return when {
            name.startsWith("mmcblk") -> "eMMC"
            name.startsWith("nvme") -> "NVMe"
            name.startsWith("sd") -> "UFS/SCSI"
            name.startsWith("loop") -> "Loop"
            else -> "Block"
        }
    }

    // ════════════════════════════════════════════════════════════════
    // NETWORK DETAIL
    // ════════════════════════════════════════════════════════════════
    private fun renderNetworkDetail() {
        val graph = RealtimeGraphView(this).apply {
            graphColor = StudioUi.color(StudioUi.Purple)
            showSecondary = true
            secondaryColor = StudioUi.color(StudioUi.Purple)
            title = "이더넷"
            yMax = 100f
            yLabel = ""
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
            setPadding(0, 0, 0, dp(8))
        }
        val maxNet = (0 until netCount).maxOfOrNull {
            (netRxHistory[RealtimeGraphView.MAX_POINTS - netCount + it] +
             netTxHistory[RealtimeGraphView.MAX_POINTS - netCount + it]).coerceAtLeast(1f)
        } ?: 100f
        graph.yMax = maxNet * 1.2f
        for (i in 0 until netCount) {
            graph.pushPrimary(netRxHistory[RealtimeGraphView.MAX_POINTS - netCount + i])
            graph.pushSecondary(netTxHistory[RealtimeGraphView.MAX_POINTS - netCount + i])
        }
        perfDetail.addView(graph)

        val currentRx = readNetworkRxBytes()
        val currentTx = readNetworkTxBytes()
        var rxSpeed = 0L
        var txSpeed = 0L
        if (lastNetworkTime > 0) {
            val now = System.currentTimeMillis()
            val elapsed = (now - lastNetworkTime) / 1000.0
            if (elapsed > 0) {
                rxSpeed = ((currentRx - lastRxBytes) / elapsed / 1024).toLong()
                txSpeed = ((currentTx - lastTxBytes) / elapsed / 1024).toLong()
            }
        }

        perfDetail.addView(statsGrid(listOf<Pair<String, String>>(
            "보내기" to "${formatSpeed(txSpeed)}",
            "받기" to "${formatSpeed(rxSpeed)}",
            "전송" to "${formatBytes(currentTx)}",
            "수신" to "${formatBytes(currentRx)}",
            "IPv4" to (getWifiIp() ?: "N/A")
        )))
    }

    // ════════════════════════════════════════════════════════════════
    // GPU DETAIL
    // ════════════════════════════════════════════════════════════════
    private fun renderGpuDetail() {
        val graph = RealtimeGraphView(this).apply {
            graphColor = StudioUi.color(StudioUi.Green)
            title = "GPU"
            yMax = 100f
            yLabel = "%"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200))
            setPadding(0, 0, 0, dp(8))
        }
        for (i in 0 until gpuCount) graph.pushPrimary(gpuHistory[RealtimeGraphView.MAX_POINTS - gpuCount + i])
        perfDetail.addView(graph)

        perfDetail.addView(statsGrid(listOf<Pair<String, String>>(
            "사용률" to "${String.format("%.0f", readGpuUsage())}%",
            "GPU 이름" to getGpuName(),
            "GPU 벤더" to getGpuVendor(),
            "온도" to getGpuTemperature()
        )))
    }

    private fun getGpuName(): String {
        val sysfsPaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/class/kgsl/kgsl-3d0/gpu_model_number",
            "/sys/class/misc/mali0/device/gpu_model",
            "/sys/class/misc/mali0/device/gpuinfo"
        )
        for (path in sysfsPaths) {
            try {
                val name = File(path).readText().trim()
                if (name.isNotEmpty() && name != "0" && name.length > 1) return name
            } catch (_: Exception) {}
        }
        return try {
            val gl = GLES20.glGetString(GLES20.GL_RENDERER)
            if (!gl.isNullOrEmpty()) gl else "GPU"
        } catch (_: Exception) { "GPU" }
    }

    private fun getGpuVendor(): String {
        val sysfsPaths = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_manufacturer",
            "/sys/class/misc/mali0/device/gpu_vendor"
        )
        for (path in sysfsPaths) {
            try {
                val v = File(path).readText().trim()
                if (v.isNotEmpty() && v.length > 1) return v
            } catch (_: Exception) {}
        }
        return try {
            val gl = GLES20.glGetString(GLES20.GL_VENDOR)
            if (!gl.isNullOrEmpty()) gl else "Unknown"
        } catch (_: Exception) { "Unknown" }
    }

    private fun readGpuUsage(): Float {
        try {
            val adreno = File("/sys/class/kgsl/kgsl-3d0/gpubusy")
            if (adreno.exists()) {
                val parts = adreno.readText().trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val busy = parts[0].toLongOrNull() ?: 0
                    val total = parts[1].toLongOrNull() ?: 1
                    return if (total > 0) (busy * 100f / total).coerceIn(0f, 100f) else 0f
                }
            }
            val maliUtil = File("/sys/class/misc/mali0/device/utilization")
            if (maliUtil.exists()) {
                val v = maliUtil.readText().trim().toFloatOrNull() ?: 0f
                return if (v > 100) (v / 2.55f).coerceIn(0f, 100f) else v.coerceIn(0f, 100f)
            }
            val maliBusy = File("/sys/kernel/gpu/gpu_busy")
            if (maliBusy.exists()) {
                val v = maliBusy.readText().trim().replace("%", "").toFloatOrNull() ?: 0f
                return v.coerceIn(0f, 100f)
            }
            val maliUtil2 = File("/sys/module/gpu/parameters/mali_utilization")
            if (maliUtil2.exists()) {
                val v = maliUtil2.readText().trim().toFloatOrNull() ?: 0f
                return if (v > 100) (v / 2.55f).coerceIn(0f, 100f) else v.coerceIn(0f, 100f)
            }
        } catch (_: Exception) {}
        return 0f
    }

    private fun getGpuTemperature(): String {
        try {
            val tempDir = File("/sys/class/thermal/")
            tempDir.listFiles()?.forEach { zone ->
                if (!zone.isDirectory) return@forEach
                val type = File(zone, "type").readTextOrNull()?.trim() ?: ""
                if (type.contains("gpu", ignoreCase = true) ||
                    type.contains("gpuss", ignoreCase = true)) {
                    val raw = File(zone, "temp").readTextOrNull()?.trim()?.toFloatOrNull()
                    if (raw != null) {
                        val celsius = if (raw > 1000) raw / 1000f else raw
                        if (celsius in 1f..150f) return "${String.format("%.0f", celsius)} °C"
                    }
                }
            }
        } catch (_: Exception) {}
        return "N/A"
    }

    // ════════════════════════════════════════════════════════════════
    // SYSTEM INFO TAB
    // ════════════════════════════════════════════════════════════════
    private fun renderSystemInfo() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }

        val categories = listOf(
            "장치" to listOf(
                "제조사" to Build.MANUFACTURER,
                "모델" to Build.MODEL,
                "제품" to Build.PRODUCT,
                "기기" to Build.DEVICE,
                "보드" to Build.BOARD,
                "하드웨어" to Build.HARDWARE
            ),
            "Android" to listOf(
                "버전" to Build.VERSION.RELEASE,
                "API 레벨" to "${Build.VERSION.SDK_INT}",
                "보안 패치" to Build.VERSION.SECURITY_PATCH,
                "빌드 ID" to Build.ID,
                "빌드 타입" to Build.TYPE
            ),
            "CPU" to listOf(
                "코어" to "${Runtime.getRuntime().availableProcessors()}",
                "ABI" to Build.SUPPORTED_ABIS.joinToString(),
                "속도" to "${String.format("%.2f", readCpuSpeed() / 1000.0)} GHz"
            ),
            "메모리" to run {
                val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val mi = ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                listOf(
                    "총 RAM" to "${mi.totalMem / (1024 * 1024)} MB",
                    "사용 가능" to "${mi.availMem / (1024 * 1024)} MB",
                    "Heap 최대" to "${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB",
                    "Native" to "${Debug.getNativeHeapAllocatedSize() / (1024 * 1024)} MB"
                )
            },
            "스토리지" to run {
                val stat = StatFs(Environment.getDataDirectory().path)
                val total = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
                val avail = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
                listOf(
                    "총 용량" to "${String.format("%.0f", total)} GB",
                    "사용 가능" to "${String.format("%.0f", avail)} GB",
                    "배터리" to getBatteryInfo()
                )
            }
        )

        for ((cat, items) in categories) {
            container.addView(sectionHeader(cat))
            container.addView(statsGrid(items))
        }

        tabContent.addView(container)
    }

    private fun getBatteryInfo(): String {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val temperature = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
            return "${level}% ${String.format("%.0f", temperature)}°C"
        } catch (_: Exception) {}
        return "N/A"
    }

    // ════════════════════════════════════════════════════════════════
    // CPU / DISK / NETWORK READING
    // ════════════════════════════════════════════════════════════════
    private fun readCpuUsage(): Double {
        try {
            val f = RandomAccessFile("/proc/stat", "r")
            val line = f.readLine()
            f.close()
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 5) {
                val user = parts[1].toLongOrNull() ?: 0
                val nice = parts[2].toLongOrNull() ?: 0
                val system = parts[3].toLongOrNull() ?: 0
                val idle = parts[4].toLongOrNull() ?: 0
                val iowait = parts.getOrNull(5)?.toLongOrNull() ?: 0
                val irq = parts.getOrNull(6)?.toLongOrNull() ?: 0
                val softirq = parts.getOrNull(7)?.toLongOrNull() ?: 0
                val total = user + nice + system + idle + iowait + irq + softirq
                val active = total - idle - iowait
                val dTotal = total - lastCpuTotal
                val dActive = active - lastCpuIdle
                lastCpuTotal = total
                lastCpuIdle = idle
                return if (dTotal > 0) (dActive * 100.0 / dTotal) else 0.0
            }
        } catch (_: Exception) {}
        return 0.0
    }

    private fun readSystemCpuTime(): Long {
        try {
            val f = RandomAccessFile("/proc/stat", "r")
            val line = f.readLine()
            f.close()
            val parts = line.split("\\s+".toRegex())
            if (parts.size >= 5) {
                return parts.drop(1).take(7).sumOf { it.toLongOrNull() ?: 0 }
            }
        } catch (_: Exception) {}
        return 0
    }

    private fun readCpuSpeed(): Float {
        try {
            val f = RandomAccessFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "r")
            val freq = f.readLine().toFloatOrNull() ?: 0f
            f.close()
            return freq / 1000f
        } catch (_: Exception) {}
        return 0f
    }

    private fun readDiskActivity(): Float {
        readAllDiskStats()
        return cachedDiskActivityPct
    }

    private fun readAllDiskStats() {
        val now = System.currentTimeMillis()
        val currentDevices = mutableListOf<DiskDevice>()

        try {
            val lines = File("/proc/diskstats").readLines()
            for (line in lines) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size < 14) continue
                val devName = parts[2]
                if (!isRealBlockDevice(devName)) continue

                val sectorsRead = parts[5].toLongOrNull() ?: 0
                val sectorsWritten = parts[9].toLongOrNull() ?: 0
                val ioTimeMs = parts[12].toLongOrNull() ?: 0

                val existing = diskDevices.find { it.name == devName }
                if (existing != null && existing.lastTime > 0) {
                    val elapsed = (now - existing.lastTime) / 1000.0
                    if (elapsed > 0) {
                        existing.activityPct = ((ioTimeMs - existing.lastIoTimeMs) / (elapsed * 10.0)).coerceIn(0.0, 100.0).toFloat()
                        existing.readBytesPerSec = ((sectorsRead - existing.lastSectorsRead) * 512 / elapsed).toLong()
                        existing.writeBytesPerSec = ((sectorsWritten - existing.lastSectorsWritten) * 512 / elapsed).toLong()
                    }
                } else {
                    existing?.activityPct = 0f
                    existing?.readBytesPerSec = 0
                    existing?.writeBytesPerSec = 0
                }

                val dev = existing ?: DiskDevice(devName, formatDiskName(devName))
                dev.lastIoTimeMs = ioTimeMs
                dev.lastSectorsRead = sectorsRead
                dev.lastSectorsWritten = sectorsWritten
                dev.lastTime = now
                currentDevices.add(dev)
            }
        } catch (_: Exception) {}

        if (currentDevices.isNotEmpty()) {
            diskDevices.clear()
            diskDevices.addAll(currentDevices)
            cachedDiskActivityPct = diskDevices.map { it.activityPct }.maxOrNull() ?: 0f
            cachedDiskReadSpeed = diskDevices.sumOf { it.readBytesPerSec } / 1024
            cachedDiskWriteSpeed = diskDevices.sumOf { it.writeBytesPerSec } / 1024
        }
    }

    private fun isRealBlockDevice(name: String): Boolean {
        return name.startsWith("mmcblk") || name.startsWith("sda") ||
               name.startsWith("sdb") || name.startsWith("nvme") ||
               name.startsWith("vda") || name.startsWith("loop") && name.substringAfter("loop").toIntOrNull()?.let { it < 8 } == true
    }

    private fun formatDiskName(name: String): String {
        return when {
            name.startsWith("mmcblk") -> {
                val num = name.removePrefix("mmcblk").firstOrNull()?.toString() ?: "0"
                "디스크 $num ($name)"
            }
            name.startsWith("sda") -> "디스크 0 ($name)"
            name.startsWith("sdb") -> "디스크 1 ($name)"
            name.startsWith("nvme") -> "디스크 NVMe ($name)"
            else -> "디스크 ($name)"
        }
    }

    private fun readNetworkRxBytes(): Long {
        try {
            val rx = TrafficStats.getTotalRxBytes()
            if (rx != TrafficStats.UNSUPPORTED.toLong() && rx >= 0) return rx
        } catch (_: Exception) {}
        return readProcNetDevBytes().first
    }

    private fun readNetworkTxBytes(): Long {
        try {
            val tx = TrafficStats.getTotalTxBytes()
            if (tx != TrafficStats.UNSUPPORTED.toLong() && tx >= 0) return tx
        } catch (_: Exception) {}
        return readProcNetDevBytes().second
    }

    private fun readProcNetDevBytes(): Pair<Long, Long> {
        try {
            val lines = File("/proc/net/dev").readLines()
            var totalRx = 0L
            var totalTx = 0L
            for (line in lines.drop(2)) {
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 10 && !parts[0].startsWith("lo:")) {
                    totalRx += parts[1].toLongOrNull() ?: 0
                    totalTx += parts[9].toLongOrNull() ?: 0
                }
            }
            return Pair(totalRx, totalTx)
        } catch (_: Exception) {}
        return Pair(0L, 0L)
    }

    private fun getWifiIp(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    // ════════════════════════════════════════════════════════════════
    // HELPERS
    // ════════════════════════════════════════════════════════════════
    private fun statsGrid(items: List<Pair<String, String>>): View {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(StudioUi.color(StudioUi.CardBg))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(2)
            }
        }

        var row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, pair) in items.withIndex()) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(12), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            cell.addView(TextView(this@TaskManagerActivity).apply {
                text = pair.first
                setTextColor(StudioUi.color("#888888"))
                textSize = 11f
            })
            cell.addView(TextView(this@TaskManagerActivity).apply {
                text = pair.second
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            })
            row.addView(cell)

            if ((i + 1) % 3 == 0 || i == items.size - 1) {
                grid.addView(row)
                row = LinearLayout(this@TaskManagerActivity).apply { orientation = LinearLayout.HORIZONTAL }
            }
        }

        return grid
    }

    private fun sectionHeader(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(StudioUi.color("#AAAAAA"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), dp(10), dp(4), dp(6))
        }
    }

    private fun makeButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(StudioUi.color("#AAAAAA"))
            textSize = 12f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(3).toFloat()
                setColor(StudioUi.color("#383838"))
            }
            background = bg
            setOnClickListener { onClick() }
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes} B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${String.format("%.1f", bytes / (1024.0 * 1024.0))} MB"
            else -> "${String.format("%.2f", bytes / (1024.0 * 1024.0 * 1024.0))} GB"
        }
    }

    private fun formatSpeed(kbps: Long): String {
        return when {
            kbps < 1 -> "0 KB/s"
            kbps < 1024 -> "${kbps} KB/s"
            else -> "${String.format("%.1f", kbps / 1024.0)} MB/s"
        }
    }

    private fun formatDuration(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format("%d:%02d:%02d", hours, minutes, seconds)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}

private fun File.readTextOrNull(): String? {
    return try { readText() } catch (_: Exception) { null }
}
