package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * GitManager - Provides basic Git operations within the IDE.
 * init, add, commit, status, log, diff, branch, checkout.
 */
class GitManager(private val context: Context) {

    data class GitStatus(
        val modified: List<String>,
        val added: List<String>,
        val deleted: List<String>,
        val untracked: List<String>,
        val currentBranch: String,
        val isClean: Boolean
    )

    data class GitCommit(
        val hash: String,
        val author: String,
        val date: String,
        val message: String
    )

    private fun runGit(projectDir: File, vararg args: String): GitResult {
        return try {
            val cmd = listOf("git") + args.toList()
            val process = ProcessBuilder(cmd)
                .directory(projectDir)
                .redirectErrorStream(true)
                .start()
            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val exitCode = process.waitFor()
            GitResult(exitCode, output.trim())
        } catch (e: Exception) {
            GitResult(-1, "Git not available: ${e.message}")
        }
    }

    fun isGitInstalled(projectDir: File): Boolean {
        val result = runGit(projectDir, "--version")
        return result.exitCode == 0 && result.output.contains("git version")
    }

    fun init(projectDir: File): GitResult = runGit(projectDir, "init")

    fun addAll(projectDir: File): GitResult = runGit(projectDir, "add", ".")

    fun addFile(projectDir: File, path: String): GitResult = runGit(projectDir, "add", path)

    fun commit(projectDir: File, message: String): GitResult =
        runGit(projectDir, "commit", "-m", message)

    fun getStatus(projectDir: File): GitStatus {
        val result = runGit(projectDir, "status", "--porcelain")
        val branchResult = runGit(projectDir, "branch", "--show-current")
        val branch = if (branchResult.exitCode == 0) branchResult.output else "unknown"

        val modified = mutableListOf<String>()
        val added = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val untracked = mutableListOf<String>()

        for (line in result.output.lines()) {
            if (line.length < 3) continue
            val status = line.substring(0, 2)
            val file = line.substring(3)
            when {
                status[0] == '?' -> untracked.add(file)
                status[0] == 'A' || status[1] == 'A' -> added.add(file)
                status[0] == 'D' || status[1] == 'D' -> deleted.add(file)
                status[0] == 'M' || status[1] == 'M' -> modified.add(file)
                status[0] == 'R' -> modified.add(file)
            }
        }

        return GitStatus(modified, added, deleted, untracked, branch, result.output.isBlank())
    }

    fun getLog(projectDir: File, count: Int = 20): List<GitCommit> {
        val result = runGit(projectDir, "log", "--oneline", "-$count", "--format=%H|%an|%ad|%s", "--date=short")
        if (result.exitCode != 0) return emptyList()
        return result.output.lines().filter { it.contains("|") }.map { line ->
            val parts = line.split("|", limit = 4)
            GitCommit(
                hash = parts.getOrElse(0) { "" }.take(8),
                author = parts.getOrElse(1) { "" },
                date = parts.getOrElse(2) { "" },
                message = parts.getOrElse(3) { "" }
            )
        }
    }

    fun getDiff(projectDir: File, file: String? = null): String {
        val args = if (file != null) listOf("diff", file) else listOf("diff")
        val result = runGit(projectDir, *args.toTypedArray())
        return result.output.ifEmpty { "No changes" }
    }

    fun getBranches(projectDir: File): List<String> {
        val result = runGit(projectDir, "branch", "--format=%(refname:short)")
        return result.output.lines().filter { it.isNotBlank() }
    }

    fun checkout(projectDir: File, branch: String): GitResult =
        runGit(projectDir, "checkout", branch)

    fun createBranch(projectDir: File, name: String): GitResult =
        runGit(projectDir, "checkout", "-b", name)

    fun pull(projectDir: File): GitResult = runGit(projectDir, "pull")

    fun push(projectDir: File): GitResult = runGit(projectDir, "push")

    fun hasUncommittedChanges(projectDir: File): Boolean {
        val status = getStatus(projectDir)
        return !status.isClean
    }

    fun showGitDialog(projectDir: File) {
        if (!isGitInstalled(projectDir)) {
            AlertDialog.Builder(context)
                .setTitle("Git")
                .setMessage("Git is not installed or not available in PATH.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val status = getStatus(projectDir)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        // Branch info
        root.addView(sectionLabel("Branch"))
        root.addView(infoRow("Current", status.currentBranch))

        // Status summary
        root.addView(sectionLabel("Changes"))
        val changes = mutableListOf<String>()
        if (status.modified.isNotEmpty()) changes.add("${status.modified.size} modified")
        if (status.added.isNotEmpty()) changes.add("${status.added.size} staged")
        if (status.deleted.isNotEmpty()) changes.add("${status.deleted.size} deleted")
        if (status.untracked.isNotEmpty()) changes.add("${status.untracked.size} untracked")
        if (changes.isEmpty()) changes.add("Clean working tree")
        root.addView(infoRow("Status", changes.joinToString(", ")))

        // Recent commits
        val commits = getLog(projectDir, 5)
        if (commits.isNotEmpty()) {
            root.addView(sectionLabel("Recent Commits"))
            for (commit in commits) {
                root.addView(TextView(context).apply {
                    text = "${commit.hash} ${commit.message}"
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    setPadding(0, dp(2), 0, dp(2))
                })
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Git Status")
            .setView(ScrollView(context).apply {
                addView(root)
                setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            })
            .setPositiveButton("Commit All") { _, _ ->
                showCommitDialog(projectDir)
            }
            .setNeutralButton("Diff") { _, _ ->
                showDiffDialog(projectDir)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    internal fun showCommitDialog(projectDir: File) {
        val input = android.widget.EditText(context).apply {
            hint = "Commit message"
            setPadding(48, 24, 48, 24)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(context)
            .setTitle("Git Commit")
            .setView(input)
            .setPositiveButton("Commit") { _, _ ->
                val msg = input.text.toString().trim()
                if (msg.isNotEmpty()) {
                    addAll(projectDir)
                    val result = commit(projectDir, msg)
                    AlertDialog.Builder(context)
                        .setTitle("Commit Result")
                        .setMessage(result.output)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    internal fun showDiffDialog(projectDir: File) {
        val diff = getDiff(projectDir)
        val tv = TextView(context).apply {
            text = diff
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(24, 16, 24, 16)
        }
        AlertDialog.Builder(context)
            .setTitle("Git Diff")
            .setView(ScrollView(context).apply {
                addView(tv)
                setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(0, dp(14), 0, dp(6))
        }
    }

    private fun infoRow(label: String, value: String): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(2))
            addView(TextView(context).apply {
                text = "$label: "
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
            })
            addView(TextView(context).apply {
                text = value
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(StudioUi.color(StudioUi.TextSoft))
            })
        }
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    data class GitResult(val exitCode: Int, val output: String)
}
