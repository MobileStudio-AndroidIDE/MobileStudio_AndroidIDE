package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal Git integration (status / log / add / commit / diff) for the
 * current project, executed through the app's native shell runner.
 *
 * Only shows friendly errors when `git` is unavailable on-device.
 */
class GitDialog(
    private val context: Context,
    private val projectDir: File?
) {

    private val running = AtomicBoolean(false)
    private lateinit var outputView: TextView
    private lateinit var msgInput: EditText
    private lateinit var statusBtn: Button
    private lateinit var logBtn: Button
    private lateinit var addBtn: Button
    private lateinit var commitBtn: Button
    private lateinit var diffBtn: Button
    private lateinit var pullBtn: Button
    private lateinit var pushBtn: Button
    private lateinit var branchBtn: Button
    private lateinit var checkoutBtn: Button
    private lateinit var dialog: android.app.AlertDialog

    private val env: Map<String, String> by lazy {
        mutableMapOf<String, String>().apply {
            putAll(System.getenv())
            put("HOME", StorageHelper.projectsRoot(context).parentFile?.absolutePath ?: "/data/data/com.aide.mobilestudio")
            val binDir = runCatching { RuntimeToolsInstaller.binDir(context).absolutePath }.getOrDefault("")
            put("PATH", (System.getenv("PATH") ?: "") + ":/system/bin:/system/xbin" + (if (binDir.isNotEmpty()) ":$binDir" else ""))
        }
    }

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }

        // Action buttons
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        statusBtn = actionButton("\uD83D\uDCC5 Status") { run("git status -s") }
        logBtn = actionButton("\uD83D\uDDE5 Log") { run("git log --oneline -15") }
        addBtn = actionButton("\u002B\u002B Add") { run("git add -A && git status -s") }
        commitBtn = actionButton("\u2713 Commit") { onCommit() }
        diffBtn = actionButton("\u2260 Diff") { run("git diff") }
        listOf(statusBtn, logBtn, addBtn, commitBtn, diffBtn).forEach { btn ->
            actions.addView(btn, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginEnd = dp(4)
            })
        }
        root.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Branch / remote actions
        val actions2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        pullBtn = actionButton("\u2193 Pull") { run("git pull") }
        pushBtn = actionButton("\u2191 Push") { run("git push") }
        branchBtn = actionButton("+ Branch") { promptNewBranch() }
        checkoutBtn = actionButton("\u2192 Checkout") { promptCheckoutBranch() }
        listOf(pullBtn, pushBtn, branchBtn, checkoutBtn).forEach { btn ->
            actions2.addView(btn, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginEnd = dp(4)
            })
        }
        root.addView(actions2, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        // Commit message input
        msgInput = EditText(context).apply {
            hint = "Commit message..."
            textSize = 12f
            setSingleLine(true)
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.muted)
            background = StudioUi.inputBg(context)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        root.addView(msgInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        // Output console
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = true }
        outputView = TextView(context).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.palette.textSoft)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setTextIsSelectable(true)
            background = StudioUi.rounded(context, fill = StudioUi.Ink, radiusDp = 6)
            text = "> Git: ${projectDir?.name ?: "no project"}\nUse Status / Log / Diff / Add / Commit.\nGit must be installed and in PATH."
        }
        scroll.addView(outputView)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(8)
        })

        dialog = android.app.AlertDialog.Builder(context)
            .setTitle("Git - ${projectDir?.name ?: "no project"}")
            .setView(root)
            .setNegativeButton("Close", null)
            .create()
        dialog.show()

        // Set a reasonable dialog height
        runCatching { dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(560)) }
    }

    private fun onCommit() {
        val msg = msgInput.text.toString().trim()
        if (msg.isEmpty()) {
            appendLine("> Commit message is empty.")
            return
        }
        run("git add -A && git commit -m \"${msg.replace("\"", "\\\"")}\" && git log --oneline -3")
    }

    private fun promptInput(title: String, hint: String, onConfirm: (String) -> Unit) {
        val input = EditText(context).apply {
            this.hint = hint
            textSize = 12f
            isSingleLine = true
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.muted)
            background = StudioUi.inputBg(context)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        val wrapper = android.widget.FrameLayout(context).apply {
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(input, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton("OK") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) onConfirm(value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun safeBranchName(name: String): String? =
        if (name.matches(Regex("""[A-Za-z0-9._/-]+""")) && !name.contains("..")) name else null

    private fun promptNewBranch() {
        promptInput("New Branch", "branch name") { raw ->
            val name = safeBranchName(raw.replace(' ', '-'))
            if (name == null) {
                appendLine("> Invalid branch name: $raw")
            } else {
                run("git checkout -b $name && git branch")
            }
        }
    }

    private fun promptCheckoutBranch() {
        promptInput("Checkout Branch", "branch name") { raw ->
            val name = safeBranchName(raw)
            if (name == null) {
                appendLine("> Invalid branch name: $raw")
            } else {
                run("git checkout $name && git branch")
            }
        }
    }

    private fun run(command: String) {
        val dir = projectDir
        if (dir == null) {
            appendLine("> Open a project first.")
            return
        }
        if (!running.compareAndSet(false, true)) {
            appendLine("> A git command is already running.")
            return
        }
        setBusy(true)
        appendLine("\n$ ${command}")
        Thread {
            val result = try {
                ShellRunner.runSh(
                    context = context,
                    command = command,
                    cwd = dir,
                    env = env,
                    timeoutMs = 60_000L,
                    onLine = {}
                )
            } catch (t: Throwable) {
                ShellRunner.Result(127, listOf("git invocation failed: ${t.message ?: t}"), "error")
            }
            android.os.Handler(context.mainLooper).post {
                if (result.exitCode == 127 || result.outputLines.any { it.contains("not found") }) {
                    appendLine("> git not found or unavailable on this device.")
                } else {
                    result.outputLines.forEach { appendLine(it) }
                }
                appendLine("> exit=${result.exitCode}")
                running.set(false)
                setBusy(false)
            }
        }.apply { isDaemon = true }.start()
    }

    private fun setBusy(busy: Boolean) {
        if (!this::statusBtn.isInitialized || !this::checkoutBtn.isInitialized) return
        listOf(statusBtn, logBtn, addBtn, commitBtn, diffBtn, pullBtn, pushBtn, branchBtn, checkoutBtn)
            .forEach { it.isEnabled = !busy }
    }

    private fun appendLine(line: String) {
        runCatching {
            outputView.append("\n" + line.take(4000))
        }
    }

    private fun actionButton(text: String, onTap: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setOnClickListener { onTap() }
            setTextColor(StudioUi.palette.text)
            background = StudioUi.rounded(context, fill = StudioUi.palette.surface2, stroke = StudioUi.palette.border, radiusDp = 6)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}