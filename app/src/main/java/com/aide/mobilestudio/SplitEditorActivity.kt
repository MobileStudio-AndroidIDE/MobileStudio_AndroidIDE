package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.aide.mobilestudio.StudioUi
import java.io.File

/**
 * SplitEditorActivity - Editor split view.
 *
 * Shows two editor panes side-by-side (or stacked) so a developer can view/edit
 * two files at once. Useful for comparing code, copying between files, etc.
 */
class SplitEditorActivity : AppCompatActivity() {

    private lateinit var leftEditor: LineNumberEditText
    private lateinit var rightEditor: LineNumberEditText
    private lateinit var leftPathLabel: TextView
    private lateinit var rightPathLabel: TextView
    private var leftFile: File? = null
    private var rightFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        scroll.addView(root)
        setContentView(scroll)

        // Header
        val header = TextView(this).apply {
            text = "Split Editor"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
            setTextColor(StudioUi.color(StudioUi.Blue))
        }
        root.addView(header)

        // Action bar
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
        }
        actionRow.addView(makeBtn("Open Left") { openFile(true) })
        actionRow.addView(makeBtn("Open Right") { openFile(false) })
        actionRow.addView(makeBtn("Swap") { swapEditors() })
        root.addView(actionRow)

        // Split pane
        val split = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val leftPane = buildEditorPane(true)
        val rightPane = buildEditorPane(false)

        split.addView(leftPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        split.addView(rightPane, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(split)

        // Load initial files from intent extras
        val leftPath = intent.getStringExtra("left_path")
        val rightPath = intent.getStringExtra("right_path")
        if (leftPath != null) loadFileInto(File(leftPath), true)
        if (rightPath != null) loadFileInto(File(rightPath), false)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeBtn(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 13f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setOnClickListener { onClick() }
        }
    }

    private fun buildEditorPane(isLeft: Boolean): LinearLayout {
        val pane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
        }

        val label = TextView(this).apply {
            text = if (isLeft) "Left" else "Right"
            textSize = 12f
            setPadding(dp(4), dp(4), dp(4), dp(2))
            setTextColor(StudioUi.color(StudioUi.TextSoft))
        }
        pane.addView(label)

        val editor = LineNumberEditText(this)
        editor.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, 1f
        )
        pane.addView(editor)

        if (isLeft) {
            leftEditor = editor
            leftPathLabel = label
        } else {
            rightEditor = editor
            rightPathLabel = label
        }
        return pane
    }

    private fun openFile(isLeft: Boolean) {
        // Use system file picker
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        val picker = Intent.createChooser(intent, "Select file")
        // We can't easily get the result back to a specific side without activity result API,
        // so just open a simple dialog instead
        showOpenDialog(isLeft)
    }

    private fun showOpenDialog(isLeft: Boolean) {
        val home = System.getProperty("user.home")
        val dirs = listOf(
            "Documents" to home,
            "Downloads" to File(home, "Downloads").absolutePath,
            "App Files" to filesDir.absolutePath
        )
        val items = dirs.map { it.first }.toTypedArray()
        val paths = dirs.map { it.second }
        AlertDialog.Builder(this)
            .setTitle("Open file from")
            .setItems(items) { _, which ->
                val dir = File(paths[which])
                listFilesForOpen(dir, isLeft)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun listFilesForOpen(dir: File, isLeft: Boolean) {
        val files = dir.listFiles() ?: emptyArray()
        val items = files.map { it.name }.toTypedArray()
        val paths = files.map { it.absolutePath }
        AlertDialog.Builder(this)
            .setTitle(dir.absolutePath)
            .setItems(items) { _, which ->
                loadFileInto(File(paths[which]), isLeft)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadFileInto(file: File, isLeft: Boolean) {
        if (!file.exists() || !file.isFile) return
        try {
            val text = file.readText(Charsets.UTF_8)
            if (isLeft) {
                leftEditor.setText(text)
                leftFile = file
                leftPathLabel.text = file.name
            } else {
                rightEditor.setText(text)
                rightFile = file
                rightPathLabel.text = file.name
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun swapEditors() {
        val leftText = leftEditor.text.toString()
        val rightText = rightEditor.text.toString()
        val leftFileTmp = leftFile
        val rightFileTmp = rightFile
        val leftLabel = leftPathLabel.text.toString()
        val rightLabel = rightPathLabel.text.toString()

        leftEditor.setText(rightText)
        rightEditor.setText(leftText)
        leftFile = rightFileTmp
        rightFile = leftFileTmp
        leftPathLabel.text = rightLabel
        rightPathLabel.text = leftLabel
    }

    companion object {
        fun start(context: Context, leftPath: String? = null, rightPath: String? = null) {
            val intent = Intent(context, SplitEditorActivity::class.java)
            leftPath?.let { intent.putExtra("left_path", it) }
            rightPath?.let { intent.putExtra("right_path", it) }
            context.startActivity(intent)
        }
    }
}
