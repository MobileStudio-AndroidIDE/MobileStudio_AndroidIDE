package com.aide.mobilestudio

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.ExecHelper
import com.aide.mobilestudio.LogcatManager
import com.aide.mobilestudio.ui.ide.IdeAction
import com.aide.mobilestudio.ui.ide.IdeDot
import com.aide.mobilestudio.ui.ide.IdeDrawerMenu
import com.aide.mobilestudio.ui.ide.IdeEmptyState
import com.aide.mobilestudio.ui.ide.IdeFilterField
import com.aide.mobilestudio.ui.ide.IdeMainLayout
import com.aide.mobilestudio.ui.ide.IdeMenuGroup
import com.aide.mobilestudio.ui.ide.IdeMenuItem
import com.aide.mobilestudio.ui.ide.IdeMono
import com.aide.mobilestudio.ui.ide.IdeStatusStrip
import com.aide.mobilestudio.ui.ide.IdeColors
import com.aide.mobilestudio.ui.ide.LocalIdeColors
import com.aide.mobilestudio.ui.ide.LocalShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader

class LogcatActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val packageName = intent.getStringExtra(EXTRA_PACKAGE) ?: packageName
        val pid = intent.getIntExtra(EXTRA_PID, 0)
        setContent {
            ThemeManager.ProvideTheme {
                LogcatScreen(packageName = packageName, pid = pid, onBack = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package_name"
        const val EXTRA_PID = "pid"
        fun start(context: android.content.Context, packageName: String, pid: Int = 0) {
            context.startActivity(
                android.content.Intent(context, LogcatActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, packageName)
                    .putExtra(EXTRA_PID, pid)
            )
        }
    }
}

data class LogEntry(
    val raw: String,
    val tag: String = "",
    val priority: String = "I",
    val message: String = "",
    val time: String = "",
    @Transient var isCrash: Boolean = false
) {
    companion object {
        private val LINE = Regex(
            """^(\d{2}-\d{2}\s\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(\S+)\s*:\s*(.*)"""
        )
        fun parse(line: String): LogEntry {
            return try {
                val m = LINE.find(line)
                if (m != null) {
                    LogEntry(
                        raw = line,
                        tag = m.groupValues[5],
                        priority = m.groupValues[4],
                        message = m.groupValues[6],
                        time = m.groupValues[1],
                    )
                } else {
                    LogEntry(line, priority = "I", message = line)
                }
            } catch (_: Exception) {
                LogEntry(line, priority = "I", message = line)
            }
        }
    }
}

private val LEVELS = listOf("V", "D", "I", "W", "E", "F")

private fun levelColor(priority: String, ide: IdeColors): Color = when (priority) {
    "V" -> ide.muted
    "D" -> ide.info
    "I" -> ide.success
    "W" -> ide.warning
    "E" -> ide.error
    "F" -> ide.error
    else -> ide.text
}

@Composable
fun LogcatScreen(packageName: String, pid: Int = 0, onBack: () -> Unit) {
    val context = LocalContext.current
    val ide = LocalIdeColors.current
    val shapes = LocalShapes.current
    val logs = remember { mutableStateListOf<LogEntry>() }
    var isCapturing by remember { mutableStateOf(true) }
    var filterText by remember { mutableStateOf("") }
    var priorityFilter by remember { mutableStateOf(LEVELS.toSet()) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var process by remember { mutableStateOf<Process?>(null) }
    var modeText by remember { mutableStateOf("Initializing...") }

    LaunchedEffect(isCapturing, packageName, pid) {
        if (!isCapturing) return@LaunchedEffect
        launch(Dispatchers.IO) {
            try {
                val logcatManager = LogcatManager(context, { _, _ -> }, {})
                val adb = logcatManager.resolveAdbBinary()

                // Clear logs first
                if (adb != null) {
                    ExecHelper.createProcess(context, listOf(adb, "logcat", "-c"), cwd = null, env = System.getenv())
                        .start().waitFor()
                } else {
                    Runtime.getRuntime().exec("logcat -c").waitFor()
                }

                val cmd = if (adb != null) {
                    modeText = "adb logcat"
                    val args = mutableListOf(adb, "logcat", "-v", "time")
                    if (pid > 0) args.add("--pid=$pid") else args.add("*:V")
                    args
                } else {
                    modeText = "direct logcat (adb missing)"
                    if (pid > 0) listOf("logcat", "--pid=$pid")
                    else listOf("logcat", "-v", "time", "*:V")
                }

                val pb = ExecHelper.createProcess(context, cmd, cwd = null, env = System.getenv())
                pb.redirectErrorStream(true)
                val proc = pb.start()
                process = proc
                val reader: BufferedReader = proc.inputStream.bufferedReader()
                while (isActive) {
                    val line = reader.readLine() ?: break
                    val entry = LogEntry.parse(line)
                    synchronized(logs) {
                        logs.add(entry)
                        // Crash detection in real time
                        if (entry.message.contains("FATAL EXCEPTION") || entry.priority == "E") {
                            if (entry.message.contains("FATAL EXCEPTION") ||
                                entry.message.contains("AndroidRuntime") ||
                                entry.message.contains("java.lang.") ||
                                entry.message.contains("kotlin.")) {
                                entry.isCrash = true
                            }
                        }
                        if (logs.size > 2000) {
                            val removeCount = logs.size - 1500
                            repeat(removeCount) { logs.removeAt(0) }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            process?.destroy()
        }
    }

    val filteredLogs = remember(logs.size, filterText, priorityFilter) {
        logs.filter { entry ->
            entry.priority in priorityFilter &&
                (filterText.isBlank() || entry.message.contains(filterText, true) || entry.tag.contains(filterText, true))
        }
    }

    // Pin to the newest line only while the reader actually sits at the
    // bottom; scrolling up freezes the reading position and offers a
    // "jump to latest" bar instead of fighting the user.
    var autoScroll by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount <= 1 || last >= info.totalItemsCount - 1
        }
    }
    LaunchedEffect(atBottom) { autoScroll = atBottom }
    LaunchedEffect(filteredLogs.size) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.scrollToItem(filteredLogs.size - 1)
        }
    }

    // Whole-buffer counts for the status strip.
    val levelCounts = remember(logs.size) {
        val counts = mutableMapOf("V" to 0, "D" to 0, "I" to 0, "W" to 0, "E" to 0, "F" to 0)
        for (entry in logs) counts[entry.priority] = (counts[entry.priority] ?: 0) + 1
        counts
    }

    IdeMainLayout(
        title = "Logcat",
        subtitle = packageName,
        subtitleMono = true,
        showBack = true,
        onBack = onBack,
        drawerContent = {
            IdeDrawerMenu(
                listOf(
                    IdeMenuGroup(
                        "Log",
                        listOf(
                            IdeMenuItem(
                                if (isCapturing) "Stop capture" else "Start capture",
                                onClick = { isCapturing = !isCapturing },
                            ),
                            IdeMenuItem("Clear log", onClick = { logs.clear() }),
                            IdeMenuItem(
                                "Crash analysis",
                                danger = true,
                                onClick = {
                                    val activity = context as? android.app.Activity
                                    if (activity != null) {
                                        MobileStudioCrashGuard.showCrashAnalysis(activity, null)
                                    }
                                },
                            ),
                        ),
                    ),
                    IdeMenuGroup(
                        "Navigate",
                        listOf(
                            IdeMenuItem(
                                "Jump to latest",
                                onClick = {
                                    scope.launch {
                                        if (filteredLogs.isNotEmpty()) {
                                            listState.animateScrollToItem(filteredLogs.size - 1)
                                        }
                                    }
                                },
                            ),
                        ),
                    ),
                ),
            )
        },
        topActions = {
            IdeAction(
                text = "Crash",
                onClick = {
                    val activity = context as? android.app.Activity
                    if (activity != null) MobileStudioCrashGuard.showCrashAnalysis(activity, null)
                },
                danger = true,
                iconRes = R.drawable.ic_ide_alert,
                iconSize = 12.dp,
            )
            IdeAction(text = "Clear", onClick = { logs.clear() })
            IdeAction(
                text = if (isCapturing) "Stop" else "Start",
                onClick = { isCapturing = !isCapturing },
                primary = !isCapturing,
                iconRes = if (isCapturing) R.drawable.ic_ide_stop else R.drawable.ic_ide_run,
                iconSize = if (isCapturing) 12.dp else 14.dp,
            )
        },
        secondaryActions = {
            // Real filters live in the secondary toolbar (Android Studio's
            // Logcat placement); fixed width — the bar is horizontally scrolled.
            IdeFilterField(
                value = filterText,
                onValueChange = { filterText = it },
                placeholder = "Filter by tag or message",
                modifier = Modifier.width(210.dp),
                mono = true,
            )
            LEVELS.forEach { level ->
                val active = level in priorityFilter
                val tint = levelColor(level, ide)
                Text(
                    text = level,
                    style = IdeMono.log.copy(fontSize = 11.sp),
                    color = if (active) tint else ide.muted,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier
                        .background(
                            if (active) tint.copy(alpha = 0.16f) else Color(ide.p.surface),
                            shapes.control
                        )
                        .border(
                            0.5.dp,
                            if (active) tint.copy(alpha = 0.55f) else ide.divider,
                            shapes.control
                        )
                        .clickable {
                            priorityFilter =
                                if (level in priorityFilter) priorityFilter - level
                                else priorityFilter + level
                        }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    fontSize = 11.sp,
                )
            }
        },
        statusBar = {
            IdeStatusStrip(
                left = {
                    IdeDot(if (isCapturing) ide.success else Color(ide.p.disabled))
                    Spacer(Modifier.width(6.dp))
                    Text(modeText, style = MaterialTheme.typography.bodySmall, color = ide.textSoft, maxLines = 1)
                },
                right = {
                    if ((levelCounts["W"] ?: 0) > 0) {
                        Text("W ${levelCounts["W"]}", style = IdeMono.log, color = ide.warning)
                    }
                    if ((levelCounts["E"] ?: 0) > 0) {
                        Text("E ${levelCounts["E"]}", style = IdeMono.log, color = ide.error)
                    }
                    Text(
                        "${filteredLogs.size}/${logs.size}",
                        style = IdeMono.log,
                        color = ide.muted,
                    )
                },
            )
        },
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(ide.p.editorBg))
        ) {
            if (filteredLogs.isEmpty()) {
                IdeEmptyState(
                    title = if (logs.isEmpty()) "Waiting for logs…" else "No lines match the filter",
                    hint = if (logs.isEmpty()) modeText else "Clear the filter or enable more levels above",
                )
            } else {
                SelectionContainer {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(filteredLogs, key = { it.raw.hashCode() }) { entry ->
                            LogRow(entry, ide)
                        }
                    }
                }
            }

            if (!atBottom && logs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .background(Color(ide.p.card), shapes.control)
                        .border(0.5.dp, ide.border, shapes.control)
                        .clickable {
                            scope.launch {
                                if (filteredLogs.isNotEmpty()) {
                                    listState.animateScrollToItem(filteredLogs.size - 1)
                                }
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("↓ Jump to latest", style = MaterialTheme.typography.labelMedium, color = ide.accent)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry, ide: IdeColors) {
    val msgColor = if (entry.isCrash) ide.error else ide.text
    Text(
        text = if (entry.time.isNotEmpty()) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = ide.muted)) { append(entry.time) }
                append(' ')
                withStyle(
                    SpanStyle(color = levelColor(entry.priority, ide), fontWeight = FontWeight.Bold)
                ) { append(entry.priority) }
                append(' ')
                if (entry.tag.isNotEmpty()) {
                    withStyle(SpanStyle(color = tagColor(entry.tag, ide))) { append(entry.tag) }
                    append(": ")
                }
                withStyle(SpanStyle(color = msgColor)) { append(entry.message) }
            }
        } else {
            buildAnnotatedString {
                withStyle(SpanStyle(color = ide.muted)) { append(entry.message.ifEmpty { entry.raw }) }
            }
        },
        style = IdeMono.log,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (entry.isCrash) Color(ide.p.errBg) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** Deterministic hue per tag, in the spirit of Android Studio's tag colors. */
private fun tagColor(tag: String, ide: IdeColors): Color {
    val hue = ((tag.hashCode() % 360) + 360) % 360
    val sat = if (ide.p.isDark) 0.62f else 0.85f
    val value = if (ide.p.isDark) 0.80f else 0.42f
    return Color(android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), sat, value)))
}
