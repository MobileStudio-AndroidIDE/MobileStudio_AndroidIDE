package com.aide.mobilestudio

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.system.Os
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.aide.mobilestudio.AppLanguage
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream

/**
 * Unified context menu utility for file actions across the app.
 * Delegates to existing activity methods to avoid code duplication.
 */
object ContextMenuUtils {

    /** Checks if a file is a text-like file that can be opened in the editor. */
    fun isTextLike(file: File): Boolean {
        if (file.isDirectory) return false
        return file.extension.lowercase(Locale.US) in setOf(
            "kt", "kts", "java", "xml", "gradle", "properties", "txt", "md", "json",
            "h", "hpp", "c", "cc", "cpp", "cmake", "pro", "sh", "log",
            "py", "js", "ts", "jsx", "tsx", "html", "css", "scss", "yaml", "yml",
            "toml", "ini", "cfg", "conf", "sql", "dart", "go", "rs", "swift",
            "php", "rb", "pl", "lua", "r", "m", "mm", "gradle", "kts"
        )
    }

    /** Checks if a file is an archive that can be extracted. */
    fun isArchive(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return file.isFile && (name.endsWith(".zip") || name.endsWith(".tar") ||
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ||
            name.endsWith(".tar.xz") || name.endsWith(".txz") ||
            name.endsWith(".tar.zst") || name.endsWith(".gz") ||
            name.endsWith(".xz") || name.endsWith(".zst") ||
            name.endsWith(".rar") || name.endsWith(".7z") ||
            name.endsWith(".deb") || name.endsWith(".apk"))
    }

    /**
     * Shows a unified file context menu that delegates to the activity's existing methods.
     * The activity must implement the FileContextMenuHandler interface.
     */
    fun showFileContextMenu(activity: Activity, file: File) {
        if (activity is FileContextMenuHandler) {
            activity.showFileContextMenu(file)
        } else {
            // Fallback for activities that don't implement the interface
            showDefaultContextMenu(activity, file)
        }
    }

    /**
     * Interface for activities that provide custom file context menus.
     */
    interface FileContextMenuHandler {
        fun showFileContextMenu(file: File)
    }

    /** Fallback default context menu for activities without custom implementation. */
    private fun showDefaultContextMenu(activity: Activity, file: File) {
        val actions = buildDefaultActions(activity, file)
        
        AlertDialog.Builder(activity)
            .setTitle(file.name)
            .setItems(actions.toTypedArray()) { _, which ->
                handleDefaultAction(activity, actions[which], file)
            }
            .show()
    }

    private fun buildDefaultActions(context: Context, file: File): List<String> {
        return buildList {
            add(context.getStringIfKorean("열기", "Open"))
            if (isTextLike(file)) add(context.getStringIfKorean("편집", "Edit"))
            if (file.extension.equals("apk", true)) add("Install APK")
            if (file.extension.equals("jar", true)) add("Run JAR")
            if (file.extension.equals("exe", true)) add(context.getStringIfKorean("box64로 실행", "Run with box64"))
            if (isArchive(file)) add(context.getStringIfKorean("압축 풀기", "Extract here"))
            add(context.getStringIfKorean("경로 복사", "Copy Path"))
            add(context.getStringIfKorean("복사", "Copy"))
            add(context.getStringIfKorean("이동", "Move"))
            add(context.getStringIfKorean("이름 바꾸기", "Rename"))
            add(context.getStringIfKorean("삭제", "Delete"))
            add(context.getStringIfKorean("권한", "Permissions"))
            add(context.getStringIfKorean("실행 권한", "Set executable"))
        }
    }

    private fun handleDefaultAction(activity: Activity, action: String, file: File) {
        when (action) {
            activity.getStringIfKorean("열기", "Open") -> openFile(activity, file)
            activity.getStringIfKorean("편집", "Edit") -> openTextFile(activity, file)
            "Install APK" -> installApk(activity, file)
            "Run JAR" -> runJar(activity, file)
            activity.getStringIfKorean("box64로 실행", "Run with box64") -> runExeWithBox64(activity, file)
            activity.getStringIfKorean("압축 풀기", "Extract here") -> extractArchive(activity, file)
            activity.getStringIfKorean("경로 복사", "Copy Path") -> copyPathToClipboard(activity, file)
            activity.getStringIfKorean("복사", "Copy") -> prepareCopy(activity, file, move = false)
            activity.getStringIfKorean("이동", "Move") -> prepareCopy(activity, file, move = true)
            activity.getStringIfKorean("이름 바꾸기", "Rename") -> renameFile(activity, file)
            activity.getStringIfKorean("삭제", "Delete") -> confirmDelete(activity, file)
            activity.getStringIfKorean("권한", "Permissions") -> showPermissions(activity, file)
            activity.getStringIfKorean("실행 권한", "Set executable") -> setExecutable(activity, file)
        }
    }

    // ========== Helper methods ==========

    private fun openFile(activity: Activity, file: File) {
        if (file.isDirectory) return
        when {
            file.extension.equals("apk", true) -> installApk(activity, file)
            file.extension.equals("jar", true) -> runJar(activity, file)
            file.extension.equals("exe", true) -> runExeWithBox64(activity, file)
            isTextLike(file) -> openTextFile(activity, file)
            else -> {
                val ext = file.extension.ifBlank { "file" }
                Toast.makeText(activity, "No built-in opener for .$ext", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openTextFile(activity: Activity, file: File) {
        Thread {
            val result = runCatching {
                val bytes = file.inputStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > 20L * 1024L * 1024L) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                String(bytes, Charsets.UTF_8)
            }.getOrElse { "Failed to open: ${it.message}" }
            (activity as? AppCompatActivity)?.runOnUiThread {
                val textView = android.widget.TextView(activity).apply {
                    text = result
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextColor(StudioUi.color(StudioUi.Text))
                    setTextIsSelectable(true)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                AlertDialog.Builder(activity)
                    .setTitle(file.name)
                    .setView(ScrollView(activity).apply { addView(textView) })
                    .setPositiveButton("Close", null)
                    .show()
            }
        }.start()
    }

    private fun installApk(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { activity.startActivity(intent) }
            .onFailure { Toast.makeText(activity, "APK install failed: ${it.message}", Toast.LENGTH_SHORT).show() }
    }

    private fun runExeWithBox64(activity: Activity, exeFile: File) {
        val output = StringBuilder("> box64 + wine64: ${exeFile.name}\n")
        val textView = android.widget.TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.Text))
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
            text = output.toString()
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("box64 Console")
            .setView(ScrollView(activity).apply { addView(textView) })
            .setNegativeButton("Close", null)
            .setCancelable(false)
            .show()
        Thread {
            try {
                val env = WindowsExeEnvironment(activity)
                if (!env.isInstalled) {
                    (activity as? AppCompatActivity)?.runOnUiThread {
                        output.append("\n> Wine/box64 environment not installed.\n")
                        textView.text = output.toString()
                        Toast.makeText(activity, "Wine/box64 not installed", Toast.LENGTH_SHORT).show()
                    }
                    return@Thread
                }
                if (!env.isPrefixBooted) {
                    (activity as? AppCompatActivity)?.runOnUiThread { output.append("\n> Bootstrapping Wine prefix (first run)...\n"); textView.text = output.toString() }
                    env.bootstrapPrefix { msg -> (activity as? AppCompatActivity)?.runOnUiThread { output.append("$msg\n"); textView.text = output.toString() } }
                }
                val code = env.runWindowsExe(exeFile) { line -> (activity as? AppCompatActivity)?.runOnUiThread { output.append("$line\n"); textView.text = output.toString() } }
                (activity as? AppCompatActivity)?.runOnUiThread { output.append("\n> exit=$code\n"); textView.text = output.toString() }
            } catch (e: Exception) {
                (activity as? AppCompatActivity)?.runOnUiThread { output.append("\n> Run error: ${e.message}\n"); textView.text = output.toString() }
            }
        }.start()
    }

    private fun runJar(activity: Activity, file: File) {
        val javaBin = listOf(
            JreGradleInstaller(activity, {}, {}).effectiveJavaBin,
            File(activity.filesDir, "toolchain/jdk/lib/jvm/java-17-openjdk/bin/java"),
            File(activity.filesDir, "toolchain/jdk/jdk-21/bin/java"),
            File(activity.filesDir, "toolchain/jdk/shim/java-17-openjdk/bin/java")
        ).firstOrNull { java -> java.exists() } ?: run {
            Toast.makeText(activity, "JDK not found", Toast.LENGTH_SHORT).show()
            return
        }
        val output = StringBuilder("> ${javaBin.absolutePath} -jar ${file.absolutePath}\n")
        val dialog = AlertDialog.Builder(activity)
            .setTitle("JAR Console")
            .setView(ScrollView(activity).apply {
                addView(android.widget.TextView(activity).apply {
                    text = output.toString()
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextColor(StudioUi.color(StudioUi.Text))
                    setTextIsSelectable(true)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                })
            })
            .setPositiveButton("Close", null)
            .show()
        Thread {
            val env = mapOf(
                "JAVA_HOME" to javaBin.parentFile?.parentFile?.absolutePath.orEmpty(),
                "LD_LIBRARY_PATH" to buildString {
                    val home = javaBin.parentFile?.parentFile
                    if (home != null) {
                        append(File(home, "lib/server").absolutePath)
                        append(":")
                        append(File(home, "lib").absolutePath)
                        append(":")
                        append(File(home, "lib/jli").absolutePath)
                        append(":")
                    }
                    append(activity.applicationInfo.nativeLibraryDir)
                }
            )
            val result = ShellRunner.runSh(
                context = activity,
                command = "\"${javaBin.absolutePath}\" -jar \"${file.absolutePath}\"",
                cwd = file.parentFile ?: activity.filesDir,
                env = env
            ) { line ->
                output.append(line).append('\n')
            }
            output.append("\nexit=").append(result.exitCode)
            (activity as? AppCompatActivity)?.runOnUiThread {
            }
        }.start()
    }

    private fun extractArchive(activity: Activity, file: File) {
        val dest = File(file.parentFile, file.nameWithoutArchiveExtension()).also { it.mkdirs() }
        Toast.makeText(activity, "Extracting...", Toast.LENGTH_SHORT).show()
        Thread {
            val error = runCatching {
                when {
                    file.name.endsWith(".zip", ignoreCase = true) -> extractZip(file, file.parentFile)
                    file.name.endsWith(".tar.gz", ignoreCase = true) || file.name.endsWith(".tgz", ignoreCase = true) ->
                        GZIPInputStream(file.inputStream().buffered()).use { input -> extractTarStream(input, file.parentFile) }
                    file.name.endsWith(".tar.xz", ignoreCase = true) || file.name.endsWith(".txz", ignoreCase = true) ->
                        XZInputStream(file.inputStream().buffered()).use { input -> extractTarStream(input, file.parentFile) }
                    file.name.endsWith(".tar.zst", ignoreCase = true) ->
                        java.io.FileInputStream(file).use { fileIn ->
                            ZstdInputStream(fileIn.buffered()).use { input -> extractTarStream(input, file.parentFile) }
                        }
                    file.name.endsWith(".tar", ignoreCase = true) ->
                        file.inputStream().buffered().use { input -> extractTarStream(input, file.parentFile) }
                    else -> error("Unsupported archive")
                }
            }.exceptionOrNull()
            (activity as? AppCompatActivity)?.runOnUiThread {
                if (error == null) {
                    Toast.makeText(activity, "Extracted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Extract failed: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun extractZip(zipFile: File, dest: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                    if (entry.name.substringAfterLast('/').contains('.').not()) out.setExecutable(true, false)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun extractTarStream(input: java.io.InputStream, dest: File) {
        TarArchiveInputStream(input).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (entry.isSymbolicLink) {
                    // Skip links for safety
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                    if ((entry.mode and 0b001_001_001) != 0) out.setExecutable(true, false)
                }
                entry = tar.nextTarEntry
            }
        }
    }

    private fun copyPathToClipboard(activity: Activity, file: File) {
        val path = file.absolutePath
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FilePath", path))
        Toast.makeText(activity, "Path copied: $path", Toast.LENGTH_SHORT).show()
    }

    private var clipboardFile: File? = null
    private var clipboardMove = false

    private fun prepareCopy(activity: Activity, file: File, move: Boolean) {
        clipboardFile = file
        clipboardMove = move
        val action = if (move) "Move" else "Copy"
        Toast.makeText(activity, "$action selected: ${file.name}. Open a folder and tap Paste.", Toast.LENGTH_SHORT).show()
    }

    private fun renameFile(activity: Activity, file: File) {
        val input = android.widget.EditText(activity).apply {
            setText(file.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(activity)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || "/" in name) {
                    Toast.makeText(activity, "Invalid name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val target = File(file.parentFile, name)
                if (target.exists()) {
                    Toast.makeText(activity, "Already exists", Toast.LENGTH_SHORT).show()
                } else if (file.renameTo(target)) {
                    Toast.makeText(activity, "Renamed", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(activity: Activity, file: File) {
        AlertDialog.Builder(activity)
            .setTitle("Delete ${file.name}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                    (activity as? AppCompatActivity)?.runOnUiThread {
                        Toast.makeText(activity, if (ok) "Deleted" else "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPermissions(activity: Activity, file: File) {
        val detail = buildString {
            appendLine(file.absolutePath)
            appendLine("Readable: ${file.canRead()}")
            appendLine("Writable: ${file.canWrite()}")
            appendLine("Executable: ${file.canExecute()}")
            appendLine("Unix: ${permissions(file)}")
            appendLine("Size: ${if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else formatSize(file.length())}")
        }
        AlertDialog.Builder(activity)
            .setTitle("Permissions")
            .setMessage(detail)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setExecutable(activity: Activity, file: File) {
        val ok = runCatching {
            file.setReadable(true, false)
            file.setWritable(true, true)
            file.setExecutable(true, false)
            Os.chmod(file.absolutePath, if (file.isDirectory) 0b111_101_101 else 0b111_101_101)
        }.isSuccess
        Toast.makeText(activity, if (ok) "Executable permission granted" else "Permission change failed", Toast.LENGTH_SHORT).show()
    }

    private fun permissions(file: File): String {
        return buildString {
            append(if (file.isDirectory) "d" else "-")
            append(if (file.canRead()) "r" else "-")
            append(if (file.canWrite()) "w" else "-")
            append(if (file.canExecute()) "x" else "-")
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024} KB"
        bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(Locale.US, bytes / 1024.0 / 1024.0)
        else -> "%.2f GB".format(Locale.US, bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private fun dp(px: Int): Int = (px * Resources.getSystem().displayMetrics.density).toInt()
}