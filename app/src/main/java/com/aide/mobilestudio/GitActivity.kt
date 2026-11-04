package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.aide.mobilestudio.StudioUi
import kotlin.concurrent.thread
import java.io.File

/**
 * GitActivity - Android Studio-style Git tool window.
 *
 * Tabs:
 *   - Status: modified / added / deleted / untracked files, stage & commit
 *   - Log: commit history with hashes and messages
 *   - Branches: list, create, checkout
 *   - Remotes: pull, push, clone
 *   - Diff: view file diffs
 */
class GitActivity : AppCompatActivity() {

    private lateinit var projectDir: File
    private lateinit var git: GitManager
    private lateinit var rootContainer: LinearLayout
    private lateinit var tabHost: LinearLayout
    private lateinit var contentContainer: LinearLayout

    private var currentTab: String = "status"
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectPath = intent.getStringExtra("project_dir")
        projectDir = if (projectPath != null) File(projectPath) else filesDir
        git = GitManager(this)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        scroll.addView(rootContainer)
        setContentView(scroll)

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Git — ${projectDir.name}"
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(StudioUi.color(StudioUi.Blue))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val refreshBtn = Button(this).apply {
            text = "Refresh"
            setOnClickListener { loadStatus() }
        }
        header.addView(refreshBtn)
        rootContainer.addView(header)

        // Tabs
        tabHost = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabs = listOf("status" to "Status", "log" to "Log", "branches" to "Branches",
            "remotes" to "Remotes", "diff" to "Diff")
        for ((id, label) in tabs) {
            tabHost.addView(makeTab(id, label))
        }
        rootContainer.addView(tabHost)

        contentContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootContainer.addView(contentContainer)

        loadStatus()
        selectTab("status")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeTab(id: String, label: String): Button {
        val btn = Button(this).apply {
            text = label
            textSize = 13f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            tag = id
            setOnClickListener { selectTab(id) }
        }
        return btn
    }

    private fun selectTab(id: String) {
        currentTab = id
        for (i in 0 until tabHost.childCount) {
            val child = tabHost.getChildAt(i) as Button
            val isSelected = (child.tag as? String) == id
            child.setBackgroundColor(if (isSelected) StudioUi.color(StudioUi.Surface2) else 0)
        }
        contentContainer.removeAllViews()
        when (id) {
            "status" -> buildStatusTab()
            "log" -> buildLogTab()
            "branches" -> buildBranchesTab()
            "remotes" -> buildRemotesTab()
            "diff" -> buildDiffTab()
        }
    }

    // ============================================================
    // Status Tab
    // ============================================================
    private fun loadStatus() {
        if (currentTab == "status") selectTab("status")
    }

    private fun buildStatusTab() {
        val status = git.getStatus(projectDir)

        contentContainer.addView(TextView(this).apply {
            text = "Branch: ${status.currentBranch}"
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(8))
            setTextColor(StudioUi.color(StudioUi.Text))
        })

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(makeActionBtn("Stage All") {
            val r = git.addAll(projectDir)
            Toast.makeText(this@GitActivity, if (r.exitCode == 0) "Staged all" else r.output, Toast.LENGTH_SHORT).show()
            loadStatus()
        })
        btnRow.addView(makeActionBtn("Commit…") {
            showCommitDialog()
        })
        btnRow.addView(makeActionBtn("Init Git") {
            val r = git.init(projectDir)
            Toast.makeText(this@GitActivity, if (r.exitCode == 0) "Initialized" else r.output, Toast.LENGTH_SHORT).show()
            loadStatus()
        })
        contentContainer.addView(btnRow)

        val sections = listOf(
            "Modified" to status.modified,
            "Added" to status.added,
            "Deleted" to status.deleted,
            "Untracked" to status.untracked
        )
        for ((title, files) in sections) {
            if (files.isEmpty()) continue
            contentContainer.addView(TextView(this).apply {
                text = "$title (${files.size})"
                textSize = 13f
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(0, dp(10), 0, dp(4))
                setTextColor(StudioUi.color(StudioUi.TextSoft))
            })
            for (f in files) {
                contentContainer.addView(makeFileRow(f))
            }
        }

        if (status.isClean) {
            contentContainer.addView(TextView(this).apply {
                text = "Working tree clean — no changes."
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun makeFileRow(fileName: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        row.addView(TextView(this).apply {
            text = fileName
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(StudioUi.color(StudioUi.Text))
        })
        row.addView(Button(this).apply {
            text = "Stage"
            textSize = 11f
            setPadding(dp(6), dp(2), dp(6), dp(2))
            setOnClickListener {
                val r = git.addFile(projectDir, fileName)
                Toast.makeText(this@GitActivity, if (r.exitCode == 0) "Staged $fileName" else r.output, Toast.LENGTH_SHORT).show()
                loadStatus()
            }
        })
        return row
    }

    private fun showCommitDialog() {
        val input = EditText(this).apply {
            hint = "Commit message"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(dp(16), dp(16), dp(16), dp(16))
        val titleTv = TextView(this).apply {
            text = "Commit changes"
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        layout.addView(titleTv)
        layout.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Commit")
            .setView(layout)
            .setPositiveButton("Commit") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isEmpty()) {
                    Toast.makeText(this@GitActivity, "Enter a commit message", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val r = git.commit(projectDir, msg)
                Toast.makeText(this@GitActivity, if (r.exitCode == 0) "Committed" else r.output, Toast.LENGTH_SHORT).show()
                loadStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // Log Tab
    // ============================================================
    private fun buildLogTab() {
        val commits = git.getLog(projectDir, 50)
        if (commits.isEmpty()) {
            contentContainer.addView(TextView(this).apply {
                text = "No commits yet."
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, dp(10), 0, 0)
            })
            return
        }
        for (c in commits) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            row.addView(TextView(this).apply {
                text = c.hash
                textSize = 12f
                setTypeface(Typeface.MONOSPACE)
                setTextColor(StudioUi.color(StudioUi.Blue))
                layoutParams = LinearLayout.LayoutParams(dp(60), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = "${c.date}  ${c.author}"
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                layoutParams = LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = c.message
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.Text))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            contentContainer.addView(row)
        }
    }

    // ============================================================
    // Branches Tab
    // ============================================================
    private fun buildBranchesTab() {
        val branches = git.getBranches(projectDir)
        contentContainer.addView(TextView(this).apply {
            text = "Local Branches"
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(8))
            setTextColor(StudioUi.color(StudioUi.Text))
        })
        for (b in branches) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            row.addView(TextView(this).apply {
                text = b
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextColor(StudioUi.color(StudioUi.Text))
            })
            row.addView(Button(this).apply {
                text = "Checkout"
                textSize = 11f
                setPadding(dp(6), dp(2), dp(6), dp(2))
                setOnClickListener {
                    val r = git.checkout(projectDir, b)
                    Toast.makeText(this@GitActivity, if (r.exitCode == 0) "Switched to $b" else r.output, Toast.LENGTH_SHORT).show()
                    loadStatus()
                }
            })
            contentContainer.addView(row)
        }
        contentContainer.addView(Button(this).apply {
            text = "Create New Branch…"
            setOnClickListener {
                val input = EditText(this@GitActivity).apply {
                    hint = "Branch name"
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
                AlertDialog.Builder(this@GitActivity)
                    .setTitle("Create Branch")
                    .setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isEmpty()) return@setPositiveButton
                        val r = git.createBranch(projectDir, name)
                        Toast.makeText(this@GitActivity, if (r.exitCode == 0) "Created $name" else r.output, Toast.LENGTH_SHORT).show()
                        loadStatus()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
    }

    // ============================================================
    // Remotes Tab
    // ============================================================
    private fun buildRemotesTab() {
        contentContainer.addView(TextView(this).apply {
            text = "Remotes"
            textSize = 15f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(8))
            setTextColor(StudioUi.color(StudioUi.Text))
        })
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Button(this).apply {
            text = "Clone…"
            setOnClickListener { showCloneDialog() }
        })
        btnRow.addView(Button(this).apply {
            text = "Pull"
            setOnClickListener {
                val r = git.pull(projectDir)
                Toast.makeText(this@GitActivity, if (r.exitCode == 0) "Pulled" else r.output, Toast.LENGTH_SHORT).show()
                loadStatus()
            }
        })
        btnRow.addView(Button(this).apply {
            text = "Push"
            setOnClickListener {
                val r = git.push(projectDir)
                Toast.makeText(this@GitActivity, if (r.exitCode == 0) "Pushed" else r.output, Toast.LENGTH_SHORT).show()
                loadStatus()
            }
        })
        contentContainer.addView(btnRow)
        contentContainer.addView(TextView(this).apply {
            text = "Note: Configure remotes via `git remote add` in Terminal."
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun showCloneDialog() {
        val urlInput = EditText(this).apply {
            hint = "Repository URL (e.g. https://github.com/user/repo.git)"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val dirInput = EditText(this).apply {
            hint = "Destination directory"
            setText(projectDir.absolutePath)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(urlInput)
            addView(dirInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Clone Repository")
            .setView(layout)
            .setPositiveButton("Clone") { _, _ ->
                val url = urlInput.text.toString().trim()
                val dest = File(dirInput.text.toString().trim())
                if (url.isEmpty()) return@setPositiveButton
                thread(isDaemon = true) {
                    val r = ProcessBuilder("git", "clone", url, dest.absolutePath)
                        .redirectErrorStream(true).start()
                    val out = r.inputStream.bufferedReader().readText()
                    r.waitFor()
                    handler.post {
                        Toast.makeText(this@GitActivity, if (r.exitValue() == 0) "Cloned to $dest" else out.take(200), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ============================================================
    // Diff Tab
    // ============================================================
    private fun buildDiffTab() {
        val status = git.getStatus(projectDir)
        val allFiles = status.modified + status.added + status.untracked
        if (allFiles.isEmpty()) {
            contentContainer.addView(TextView(this).apply {
                text = "No changes to diff."
                textSize = 13f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(0, dp(10), 0, 0)
            })
            return
        }
        contentContainer.addView(TextView(this).apply {
            text = "Select a file to view diff"
            textSize = 13f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(8))
            setTextColor(StudioUi.color(StudioUi.Text))
        })
        for (f in allFiles) {
            contentContainer.addView(Button(this).apply {
                text = f
                setAllCaps(false)
                setTextSize(13f)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val diff = git.getDiff(projectDir, f)
                    showDiffDialog(f, diff)
                }
            })
        }
    }

    private fun showDiffDialog(fileName: String, diff: String) {
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            textSize = 12f
            setTypeface(Typeface.MONOSPACE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(StudioUi.color(StudioUi.Text))
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
        }
        tv.text = diff
        scroll.addView(tv)
        AlertDialog.Builder(this)
            .setTitle("Diff — $fileName")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun makeActionBtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
    }

    companion object {
        fun start(context: Context, projectDir: String) {
            val intent = Intent(context, GitActivity::class.java)
            intent.putExtra("project_dir", projectDir)
            context.startActivity(intent)
        }
    }
}
