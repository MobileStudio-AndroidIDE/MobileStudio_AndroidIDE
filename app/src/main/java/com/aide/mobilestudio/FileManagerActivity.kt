package com.aide.mobilestudio

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aide.mobilestudio.StudioUi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Developer File Manager for MobileStudio.
 *
 * Android-style file explorer UI with developer-oriented actions:
 * - Browse internal storage, downloads, and project directories
 * - Open text files in the built-in editor
 * - Install APK / split APKs
 * - Decompile APK (via apktool if present)
 * - Copy / Move / Delete / Rename
 * - Create new folder / file
 */
class FileManagerActivity : AppCompatActivity() {

    private lateinit var rootContainer: LinearLayout
    private lateinit var pathBar: TextView
    private lateinit var fileList: LinearLayout
    private lateinit var currentDir: File
    private val navBackStack = mutableListOf<File>()
    private val navForwardStack = mutableListOf<File>()
    private val handler = Handler(Looper.getMainLooper())

    private val REQ_STORAGE = 1001
    private val REQ_INSTALL_APK = 1002

    /** When true, directory selection opens the directory as a project instead of browsing. */
    private var projectMode = false
    /** Optional filename filter entered in the search box. */
    private var nameFilter: String = ""
    /** When true, after opening dir as project, show New Project dialog. */
    private var isNewProject = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectMode = intent.getBooleanExtra("project_mode", false)
        isNewProject = intent.getBooleanExtra("is_new_project", false)

        val scroll = ScrollView(this)
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(rootContainer)
        setContentView(scroll)

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = makeButton("◀") { goBack() }
        val fwdBtn = makeButton("▶") { goForward() }
        val upBtn = makeButton("↑") { goUp() }
        val homeBtn = makeButton("🏠 Home") {
            navigateTo(Environment.getExternalStorageDirectory())
        }
        val dlBtn = makeButton("⬇ Downloads") {
            navigateTo(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS))
        }
        listOf(backBtn, fwdBtn, upBtn, homeBtn, dlBtn).forEach { topBar.addView(it) }

        // Project mode: add "Open as Project" button
        if (projectMode) {
            topBar.addView(makeButton("📂 Open as Project") {
                confirmOpenAsProject(currentDir)
            })
        }
        rootContainer.addView(topBar)

        pathBar = TextView(this).apply {
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(8))
            setTextColor(StudioUi.color(StudioUi.Blue))
        }
        rootContainer.addView(pathBar)

        // New folder / new file
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        actionRow.addView(makeButton("+ Folder") { promptNewFolder() })
        actionRow.addView(makeButton("+ File") { promptNewFile() })
        rootContainer.addView(actionRow)

        // Live filename filter.
        val searchBox = EditText(this).apply {
            hint = "Filter files by name…"
            setSingleLine(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        searchBox.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                nameFilter = s?.toString()?.trim().orEmpty()
                refresh()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        rootContainer.addView(searchBox)

        fileList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rootContainer.addView(fileList)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        } else {
            navigateTo(Environment.getExternalStorageDirectory())
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeButton(text: String, onClick: () -> Unit): AppCompatButton {
        return AppCompatButton(this).apply {
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
            if (nameFilter.isNotBlank() && !f.name.contains(nameFilter, ignoreCase = true)) continue
            fileList.addView(makeRow(f.name, f, f.isDirectory))
        }
        if (fileList.childCount == 0) {
            val empty = TextView(this).apply {
                text = if (nameFilter.isBlank()) "This folder is empty." else "No files match \"$nameFilter\"."
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            fileList.addView(empty)
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
            setColorFilter(StudioUi.color(StudioUi.TextSoft))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        }
        row.addView(icon)

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val nameTv = TextView(this).apply {
            text = label
            textSize = 15f
            setTypeface(null, if (isDir) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setTextColor(StudioUi.color(StudioUi.Text))
        }
        info.addView(nameTv)
        val detailTv = TextView(this).apply {
            text = if (isDir) "folder" else "${humanSize(file.length())}  •  ${fmtDate(file.lastModified())}"
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
        }
        info.addView(detailTv)
        row.addView(info)

        val chevron = TextView(this).apply {
            text = "›"
            textSize = 16f
            setTextColor(StudioUi.color(StudioUi.Muted))
        }
        row.addView(chevron)

        row.setOnClickListener {
            if (isDir) {
                // Always browse into directories. In project mode the dedicated
                // "Open as Project" button confirms the current folder, so users
                // can still navigate deep into app/src/main/... before picking.
                navigateTo(file)
            } else {
                openFileAction(file)
            }
        }
        row.setOnLongClickListener {
            fileLongPress(file)
            true
        }
        return row
    }

    private fun fileIcon(file: File): Int {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".apk") -> android.R.drawable.ic_menu_agenda
            name.endsWith(".exe") -> android.R.drawable.ic_menu_info_details
            name.endsWith(".zip") -> android.R.drawable.ic_menu_info_details
            name.endsWith(".kt") || name.endsWith(".java") -> android.R.drawable.ic_menu_edit
            name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ->
                android.R.drawable.ic_menu_gallery
            name.endsWith(".mp3") || name.endsWith(".wav") -> android.R.drawable.ic_menu_info_details
            name.endsWith(".mp4") -> android.R.drawable.ic_menu_info_details
            name.endsWith(".json") || name.endsWith(".xml") || name.endsWith(".gradle") ->
                android.R.drawable.ic_menu_preferences
            else -> android.R.drawable.ic_menu_info_details
        }
    }

    private fun humanSize(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = bytes.toDouble()
        var unitIdx = 0
        while (size >= 1024 && unitIdx < units.size - 1) {
            size /= 1024
            unitIdx++
        }
        return "%.1f %s".format(size, units[unitIdx])
    }

    private fun fmtDate(millis: Long): String {
        return SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(millis))
    }

    /** Confirms opening the selected directory as a project (project mode only). */
    private fun confirmOpenAsProject(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("Open as Project")
            .setMessage("Open '${dir.absolutePath}' as the MobileStudio project?")
            .setPositiveButton("Open") { _, _ ->
                // FileManagerActivity runs in its own task, so it cannot cast
                // `this` to MainActivity. Return the chosen path via setResult
                // and let the caller (MainActivity) open the project.
                val result = Intent().apply {
                    putExtra(EXTRA_SELECTED_DIR, dir.absolutePath)
                    putExtra(EXTRA_NEW_PROJECT, isNewProject)
                }
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun goUp() {
        val parent = currentDir.parentFile
        if (parent != null) {
            navigateTo(parent)
        }
    }

    private fun goBack() {
        if (navBackStack.isNotEmpty()) {
            navForwardStack.add(currentDir)
            val prev = navBackStack.removeAt(navBackStack.size - 1)
            currentDir = prev
            refresh()
        }
    }

    private fun goForward() {
        if (navForwardStack.isNotEmpty()) {
            navBackStack.add(currentDir)
            val next = navForwardStack.removeAt(navForwardStack.size - 1)
            currentDir = next
            refresh()
        }
    }

    private fun navigateTo(dir: File, addToBack: Boolean = true) {
        if (addToBack && ::currentDir.isInitialized) {
            navBackStack.add(currentDir)
            navForwardStack.clear()
        }
        currentDir = dir
        refresh()
    }

    private fun promptNewFolder() {
        val input = EditText(this).apply {
            hint = "Folder name"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("New Folder")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val newDir = File(currentDir, name)
                if (newDir.mkdirs()) refresh()
                else Toast.makeText(this@FileManagerActivity, "Failed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptNewFile() {
        val input = EditText(this).apply {
            hint = "File name (e.g. notes.txt)"
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("New File")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val newFile = File(currentDir, name)
                try {
                    newFile.createNewFile()
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(this@FileManagerActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openFileAction(file: File) {
        val name = file.name.lowercase()
        when {
            name.endsWith(".apk") -> showApkActions(file)
            name.endsWith(".zip") && name.contains("split") -> showSplitApkActions(file)
            name.endsWith(".exe") -> showExeActions(file)
            name.endsWith(".txt") || name.endsWith(".kt") || name.endsWith(".java") ||
                name.endsWith(".xml") || name.endsWith(".json") || name.endsWith(".gradle") ||
                name.endsWith(".md") || name.endsWith(".log") -> openInEditor(file)
            else -> openGeneric(file)
        }
    }

    private fun showExeActions(exe: File) {
        AlertDialog.Builder(this)
            .setTitle("EXE: ${exe.name}")
            .setItems(arrayOf("Run with Wine (GUI)", "Run with Wine (Shell)", "View EXE Info", "Cancel")) { _, which ->
                when (which) {
                    0 -> runExeWithWine(exe, gui = true)
                    1 -> runExeWithWine(exe, gui = false)
                    2 -> showExeInfo(exe)
                    3 -> {}
                }
            }
            .show()
    }

    private fun runExeWithWine(exe: File, gui: Boolean) {
        // Check if Wine is installed
        val wineDir = File(filesDir, "wine-mobile")
        val wineExe = File(wineDir, "wine")
        if (!wineExe.exists()) {
            AlertDialog.Builder(this)
                .setTitle("Wine Not Installed")
                .setMessage("Wine is not installed. Please install Wine first from the Win64 Manager.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val statusMsg = if (gui) "Running EXE with Wine (GUI)..." else "Running EXE with Wine (Shell)..."
        Toast.makeText(this, statusMsg, Toast.LENGTH_SHORT).show()

        thread(isDaemon = true) {
            val env = mutableMapOf<String, String>()
            env["DISPLAY"] = ":99"
            env["WINEDEBUG"] = "-all"
            env["WINEPREFIX"] = File(wineDir, "prefix").absolutePath
            env["LD_LIBRARY_PATH"] = "${wineDir.absolutePath}:${File(wineDir, "lib").absolutePath}"

            val command = if (gui) {
                listOf(wineExe.absolutePath, exe.absolutePath)
            } else {
                listOf(wineExe.absolutePath, exe.absolutePath)
            }

            try {
                val process = ProcessBuilder(command)
                    .directory(wineDir)
                    .environment()
                    .putAll(env)
                    .redirectErrorStream(true)
                    .start()

                // Read output
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()

                handler.post {
                    if (process.exitValue() == 0) {
                        Toast.makeText(this@FileManagerActivity, "EXE finished (exit 0)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@FileManagerActivity, "EXE finished (exit ${process.exitValue()})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this@FileManagerActivity, "Wine error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showExeInfo(exe: File) {
        val msg = StringBuilder()
        msg.append("Name: ").append(exe.name).append("\n")
        msg.append("Path: ").append(exe.absolutePath).append("\n")
        msg.append("Size: ").append(humanSize(exe.length())).append("\n")
        msg.append("Modified: ").append(fmtDate(exe.lastModified())).append("\n")
        AlertDialog.Builder(this)
            .setTitle("EXE Info")
            .setMessage(msg.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun openInEditor(file: File) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putExtra("open_file", file.absolutePath)
        startActivity(intent)
        finish()
    }

    private fun openGeneric(file: File) {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(file), if (mime.isNullOrBlank()) "*/*" else mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No app to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showApkActions(apk: File) {
        val name = apk.name.lowercase()
        val items = if (name.contains("split")) {
            arrayOf("Install Split APK", "Install Split APKs (All in folder)", "View APK Info", "Cancel")
        } else {
            arrayOf("Install APK", "Decompile APK", "View APK Info", "Cancel")
        }
        AlertDialog.Builder(this)
            .setTitle("APK: ${apk.name}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> installApk(apk)
                    1 -> installAllSplitApks(apk)
                    2 -> showApkInfo(apk)
                    3 -> {}
                }
            }
            .show()
    }

    private fun showSplitApkActions(apk: File) {
        AlertDialog.Builder(this)
            .setTitle("Split APK: ${apk.name}")
            .setMessage("Install this split APK?")
            .setPositiveButton("Install") { _, _ -> installApk(apk) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installAllSplitApks(apk: File) {
        val folder = apk.parentFile
        val baseName = apk.name.removeSuffix(".apk")
        val splits = folder?.listFiles()?.filter {
            it.name.lowercase().endsWith(".apk") &&
                it.name.lowercase().contains("split") &&
                it.name.contains(baseName.takeWhile { it != '-' })
        } ?: emptyList()

        if (splits.isEmpty()) {
            Toast.makeText(this, "No split APKs found in same folder", Toast.LENGTH_SHORT).show()
            return
        }

        val items = splits.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Install Split APKs (${splits.size})")
            .setMessage(splits.joinToString("\n") { it.name })
            .setPositiveButton("Install All") { _, _ ->
                thread(isDaemon = true) {
                    for (split in splits) {
                        handler.post {
                            Toast.makeText(this@FileManagerActivity, "Installing: ${split.name}", Toast.LENGTH_SHORT).show()
                        }
                        installApk(split)
                        Thread.sleep(2000)
                    }
                    handler.post {
                        Toast.makeText(this@FileManagerActivity, "All split APKs installed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun installApk(apk: File) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).setData(
                        Uri.parse("package:${packageName}")
                    ),
                    REQ_INSTALL_APK
                )
                return
            }
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun showApkInfo(apk: File) {
        try {
            val pkgInfo = packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_ACTIVITIES)
            val msg = StringBuilder()
            msg.append("Package: ").append(pkgInfo?.packageName ?: "unknown").append("\n")
            msg.append("Version: ").append(pkgInfo?.versionName ?: "unknown").append("\n")
            msg.append("Version Code: ").append(pkgInfo?.versionCode ?: -1).append("\n")
            msg.append("Size: ").append(humanSize(apk.length())).append("\n")
            AlertDialog.Builder(this)
                .setTitle("APK Info")
                .setMessage(msg.toString())
                .setPositiveButton("OK", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read APK info", Toast.LENGTH_SHORT).show()
        }
    }

    private fun decompileApk(apk: File) {
        Toast.makeText(this, "APK decompile: checking for apktool…", Toast.LENGTH_SHORT).show()
        val candidates = listOf(
            File(currentDir, "apktool.jar"),
            File(Environment.getExternalStorageDirectory(), "apktool.jar"),
            File(filesDir, "apktool.jar")
        )
        val apktool = candidates.firstOrNull { it.exists() }
        if (apktool == null) {
            AlertDialog.Builder(this)
                .setTitle("Decompile APK")
                .setMessage("apktool.jar not found. Place it in the app directory or internal storage.\n\nAPK: ${apk.absolutePath}")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val outDir = File(currentDir, "${apk.nameWithoutExtension}_decompiled")
        outDir.mkdirs()
        thread(isDaemon = true) {
            try {
                val process = ProcessBuilder(
                    "java", "-jar", apktool.absolutePath, "d", apk.absolutePath, "-o", outDir.absolutePath, "-f"
                ).redirectErrorStream(true).start()
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                handler.post {
                    Toast.makeText(
                        this@FileManagerActivity,
                        "Decompiled to: ${outDir.absolutePath}",
                        Toast.LENGTH_LONG
                    ).show()
                    // Open decompiled viewer
                    DecompiledApkViewerActivity.start(this@FileManagerActivity, apk.name, outDir.absolutePath)
                    refresh()
                }
            } catch (e: Exception) {
                handler.post {
                    Toast.makeText(this@FileManagerActivity, "Decompile failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun fileLongPress(file: File) {
        val items = arrayOf("Open", "Copy", "Move", "Delete", "Rename", "Share", "Cancel")
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openFileAction(file)
                    1 -> promptCopyTarget(file)
                    2 -> promptMoveTarget(file)
                    3 -> confirmDelete(file)
                    4 -> promptRename(file)
                    5 -> shareFile(file)
                    6 -> {}
                }
            }
            .show()
    }

    private fun promptCopyTarget(file: File) {
        val input = EditText(this).apply {
            hint = "Destination folder path"
            setText(currentDir.absolutePath)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Copy ${file.name} to…")
            .setView(input)
            .setPositiveButton("Copy") { _, _ ->
                val dest = File(input.text.toString().trim(), file.name)
                try {
                    copyFile(file, dest)
                    Toast.makeText(this@FileManagerActivity, "Copied to ${dest.path}", Toast.LENGTH_SHORT).show()
                    refresh()
                } catch (e: Exception) {
                    Toast.makeText(this@FileManagerActivity, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptMoveTarget(file: File) {
        val input = EditText(this).apply {
            hint = "Destination folder path"
            setText(currentDir.absolutePath)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Move ${file.name} to…")
            .setView(input)
            .setPositiveButton("Move") { _, _ ->
                val dest = File(input.text.toString().trim(), file.name)
                try {
                    if (file.renameTo(dest)) {
                        Toast.makeText(this@FileManagerActivity, "Moved", Toast.LENGTH_SHORT).show()
                        refresh()
                    } else {
                        Toast.makeText(this@FileManagerActivity, "Move failed", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@FileManagerActivity, "Move failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${file.name}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (ok) refresh()
                else Toast.makeText(this@FileManagerActivity, "Delete failed", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptRename(file: File) {
        val input = EditText(this).apply {
            setText(file.name)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) return@setPositiveButton
                val dest = File(currentDir, newName)
                if (file.renameTo(dest)) {
                    refresh()
                } else {
                    Toast.makeText(this@FileManagerActivity, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareFile(file: File) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile.mkdirs()
        val srcChan = FileInputStream(src).channel
        val dstChan = FileOutputStream(dst).channel
        srcChan.transferTo(0, src.length(), dstChan)
        srcChan.close()
        dstChan.close()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            navigateTo(Environment.getExternalStorageDirectory())
        } else {
            navigateTo(Environment.getExternalStorageDirectory())
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INSTALL_APK) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                packageManager.canRequestPackageInstalls()
            ) {
                Toast.makeText(this, "Please select the APK again to install", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_SELECTED_DIR = "selected_dir"
        const val EXTRA_NEW_PROJECT = "new_project"

        fun start(context: Context, projectMode: Boolean = false, isNewProject: Boolean = false) {
            val intent = Intent(context, FileManagerActivity::class.java)
            intent.putExtra("project_mode", projectMode)
            intent.putExtra("is_new_project", isNewProject)
            context.startActivity(intent)
        }
    }
}
