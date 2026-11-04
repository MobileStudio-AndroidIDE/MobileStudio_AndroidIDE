package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.*

/**
 * APK Decompiled Result Viewer.
 *
 * Shows the contents of a decompiled APK directory (produced by apktool).
 * Lets the developer browse smali files, resources, manifest, assets, etc.
 */
class DecompiledApkViewerActivity : AppCompatActivity() {

    private lateinit var rootContainer: LinearLayout
    private lateinit var pathBar: TextView
    private lateinit var fileList: LinearLayout
    private lateinit var currentDir: File
    private lateinit var apkName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(rootContainer)
        setContentView(scroll)

        apkName = intent.getStringExtra("apk_name") ?: "decompiled"
        val startDir = intent.getStringExtra("start_dir") ?: filesDir.absolutePath
        currentDir = File(startDir)

        val header = TextView(this).apply {
            text = "Decompiled APK — $apkName"
            textSize = 18f
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, dp(12))
            setTextColor(Color.parseColor("#58A6FF"))
        }
        rootContainer.addView(header)

        pathBar = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
            setTextColor(Color.parseColor("#8B949E"))
        }
        rootContainer.addView(pathBar)

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        navRow.addView(makeButton("◀ Back") {
            val parent = currentDir.parentFile
            if (parent != null) { currentDir = parent; refresh() }
        })
        navRow.addView(makeButton("🔍 Find smali") {
            findSmaliFiles()
        })
        rootContainer.addView(navRow)

        fileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootContainer.addView(fileList)

        refresh()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextSize(13f)
            setOnClickListener { onClick() }
        }
    }

    private fun refresh() {
        fileList.removeAllViews()
        pathBar.text = currentDir.absolutePath

        val parent = currentDir.parentFile
        if (parent != null) {
            fileList.addView(makeRow(".. (Parent)", parent, true))
        }

        val files = currentDir.listFiles() ?: emptyArray()
        val sorted = files.sortedWith(compareBy({ it.isDirectory }, { it.name.lowercase() }))

        for (f in sorted) {
            fileList.addView(makeRow(f.name, f, f.isDirectory))
        }
    }

    private fun makeRow(label: String, file: File, isDir: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        val icon = ImageView(this).apply {
            setImageResource(fileIcon(file))
            setColorFilter(Color.parseColor("#8B949E"))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28))
        }
        row.addView(icon)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val nameTv = TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(if (isDir) Typeface.DEFAULT_BOLD else Typeface.create("monospace", Typeface.NORMAL))
            setTextColor(Color.parseColor("#F0F6FC"))
        }
        info.addView(nameTv)
        if (!isDir) {
            val detailTv = TextView(this).apply {
                text = "${humanSize(file.length())}"
                textSize = 11f
                setTextColor(Color.parseColor("#8B949E"))
            }
            info.addView(detailTv)
        }
        row.addView(info)

        val chevron = TextView(this).apply {
            text = "›"
            textSize = 16f
            setTextColor(Color.parseColor("#8B949E"))
        }
        row.addView(chevron)

        row.setOnClickListener {
            if (isDir) {
                currentDir = file
                refresh()
            } else {
                openFile(file)
            }
        }
        return row
    }

    private fun fileIcon(file: File): Int {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".smali") -> android.R.drawable.ic_menu_edit
            name.endsWith(".xml") -> android.R.drawable.ic_menu_preferences
            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".webp") ->
                android.R.drawable.ic_menu_gallery
            name.endsWith("AndroidManifest.xml") -> android.R.drawable.ic_menu_info_details
            name.endsWith(".apk") -> android.R.drawable.ic_menu_agenda
            else -> android.R.drawable.ic_menu_info_details
        }
    }

    private fun humanSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB")
        var size = bytes.toDouble()
        var idx = 0
        while (size >= 1024 && idx < units.size - 1) { size /= 1024; idx++ }
        return "%.1f %s".format(size, units[idx])
    }

    private fun findSmaliFiles() {
        val smaliFiles = mutableListOf<String>()
        currentDir.walkTopDown().forEach { f ->
            if (f.isFile && f.extension == "smali") smaliFiles.add(f.absolutePath)
        }
        if (smaliFiles.isEmpty()) {
            Toast.makeText(this, "No smali files found", Toast.LENGTH_SHORT).show()
            return
        }
        val items = smaliFiles.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Smali files (${smaliFiles.size})")
            .setItems(items) { _, which ->
                openFile(File(items[which]))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openFile(file: File) {
        val name = file.name.lowercase()
        if (name.endsWith(".smali") || name.endsWith(".txt") || name.endsWith(".xml") ||
            name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".properties")
        ) {
            showTextViewer(file)
        } else {
            openGeneric(file)
        }
    }

    private fun showTextViewer(file: File) {
        val scroll = ScrollView(this)
        val tv = TextView(this).apply {
            textSize = 12f
            setTypeface(Typeface.create("monospace", Typeface.NORMAL))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextColor(Color.parseColor("#F0F6FC"))
            setBackgroundColor(Color.parseColor("#161B22"))
        }
        try {
            tv.text = file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            tv.text = "Error reading file: ${e.message}"
        }
        scroll.addView(tv)

        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun openGeneric(file: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(android.net.Uri.fromFile(file), if (mime.isNullOrBlank()) "*/*" else mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        fun start(context: Context, apkName: String, startDir: String) {
            val intent = Intent(context, DecompiledApkViewerActivity::class.java)
            intent.putExtra("apk_name", apkName)
            intent.putExtra("start_dir", startDir)
            context.startActivity(intent)
        }
    }
}
