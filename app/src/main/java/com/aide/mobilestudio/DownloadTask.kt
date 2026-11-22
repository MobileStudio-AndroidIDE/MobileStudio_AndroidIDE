package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import com.aide.mobilestudio.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object DownloadTask {
    private var currentJob: Job? = null
    private var currentDialog: AlertDialog? = null
    private var appContext: Context? = null
    @Volatile
    var lastStatus: String = ""

    fun isRunning(): Boolean = currentJob?.isActive == true

    fun cancel() {
        currentJob?.cancel()
        currentDialog?.dismiss()
        currentJob = null
        currentDialog = null
        appContext = null
    }

    fun start(
        context: Context,
        label: String,
        url: String,
        targetDir: File,
        mode: String = "toolchain",
        kind: ToolchainManager.Kind? = null,
        version: String? = null,
        onComplete: (() -> Unit)? = null
    ) {
        if (isRunning()) return
        targetDir.mkdirs()
        appContext = context.applicationContext

        val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val statusText = TextView(context).apply {
            text = "Preparing download..."
            textSize = 12f
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
            addView(progressBar)
            addView(statusText)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Downloading $label")
            .setView(layout)
            .setNegativeButton("Cancel") { _, _ -> cancel() }
            .setCancelable(false)
            .show()
        currentDialog = dialog

        val mainHandler = Handler(Looper.getMainLooper())
        fun post(f: () -> Unit) { mainHandler.post(f) }
        currentJob = scope.launch(Dispatchers.IO) {
            try {
                downloadAndExtract(url, targetDir, mode, kind, version) { progress, text ->
                    post {
                        if (dialog.isShowing) {
                            progressBar.progress = progress
                            statusText.text = text
                        }
                    }
                }
                lastStatus = "all_complete"
                post {
                    try {
                        dialog.setTitle("Complete")
                        statusText.text = "Download complete: $label"
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                    } catch (_: Exception) {
                    }
                }
                onComplete?.invoke()
                Thread.sleep(3000)
                post { try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {} }
            } catch (e: CancellationException) {
                post { try { dialog.dismiss() } catch (_: Exception) {} }
            } catch (e: Exception) {
                val stack = java.io.StringWriter().also { sw ->
                    java.io.PrintWriter(sw).use { pw -> e.printStackTrace(pw) }
                }.toString()
                lastStatus = "failed: $stack"
                post {
                    if (!dialog.isShowing) return@post
                    dialog.setTitle("Download Failed")
                    dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Close") { _, _ -> dialog.dismiss() }
                    val scrollView = android.widget.ScrollView(context)
                    val errorText = android.widget.TextView(context).apply {
                        text = stack
                        textSize = 10f
                        typeface = android.graphics.Typeface.MONOSPACE
                        setTextColor(StudioUi.color(StudioUi.Red))
                        setPadding(20, 20, 20, 20)
                    }
                    scrollView.addView(errorText)
                    dialog.setView(scrollView)
                }
            } finally {
                currentJob = null
            }
        }
    }

    fun downloadAndExtract(
        url: String,
        targetDir: File,
        mode: String,
        kind: ToolchainManager.Kind?,
        version: String?,
        onProgress: (Int, String) -> Unit
    ) {
        val isArchive = url.endsWith(".tar.gz") || url.endsWith(".tgz") ||
            url.endsWith(".tar.xz") || url.endsWith(".tar.zst") ||
            url.endsWith(".zip") || url.endsWith(".deb")

        if (isArchive) {
            downloadAndExtractArchive(url, targetDir, onProgress)
        } else {
            downloadFile(url, targetDir, onProgress)
        }

        if (kind != null && version != null && appContext != null) {
            ToolchainManager(appContext!!).setActiveVersion(kind, version)
        }
    }

    private fun downloadAndExtractArchive(
        url: String,
        targetDir: File,
        onProgress: (Int, String) -> Unit
    ) {
        val tempFile = File(targetDir.parentFile ?: targetDir, ".download_${System.nanoTime()}.tmp")
        try {
            downloadToFile(url, tempFile, onProgress)
            lastStatus = "Extracting archive..."
            onProgress(95, "Extracting archive...")
            val count = intArrayOf(0)
            fun onEntry(name: String) {
                count[0]++
                // Show the current file path being extracted
                onProgress(97, "Extracting: $name")
            }
            when {
                url.endsWith(".tar.gz") || url.endsWith(".tgz") -> {
                    GZIPInputStream(tempFile.inputStream().buffered()).use { gzip ->
                        TarArchiveInputStream(gzip).use { tar -> extractTar(tar, targetDir, { onEntry(it) }) }
                    }
                }
                url.endsWith(".tar.xz") -> {
                    XZInputStream(tempFile.inputStream().buffered()).use { xz ->
                        TarArchiveInputStream(xz).use { tar -> extractTar(tar, targetDir, { onEntry(it) }) }
                    }
                }
                url.endsWith(".tar.zst") -> {
                    FileInputStream(tempFile).use { fileIn ->
                        ZstdInputStream(fileIn.buffered()).use { zstd ->
                            TarArchiveInputStream(zstd).use { tar -> extractTar(tar, targetDir, { onEntry(it) }) }
                        }
                    }
                }
                url.endsWith(".zip") -> {
                    ZipInputStream(tempFile.inputStream().buffered()).use { zip -> extractZip(zip, targetDir, { onEntry(it) }) }
                }
                url.endsWith(".deb") -> {
                    extractDeb(tempFile, targetDir)
                }
            }
            lastStatus = "Extraction complete (${count[0]} files)"
            onProgress(100, "Extraction complete (${count[0]} files)")
        } finally {
            tempFile.delete()
        }
    }

    private fun downloadFile(
        url: String,
        targetDir: File,
        onProgress: (Int, String) -> Unit
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            connect()
        }
        val disposition = conn.getHeaderField("Content-Disposition")
        val fileName = disposition
            ?.let { Regex("""filename="?(.+?)"?($|")""").find(it)?.groupValues?.get(1) }
            ?: URL(url).path.split("/").lastOrNull()
            ?: "download.bin"
        val outputFile = File(targetDir, fileName)
        downloadStream(conn.inputStream, conn.contentLengthLong, outputFile, onProgress)
    }

    private fun downloadToFile(
        url: String,
        outputFile: File,
        onProgress: (Int, String) -> Unit
    ) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            connect()
        }
        downloadStream(conn.inputStream, conn.contentLengthLong, outputFile, onProgress)
    }

    private fun downloadStream(
        input: java.io.InputStream,
        totalBytes: Long,
        outputFile: File,
        onProgress: (Int, String) -> Unit
    ) {
        outputFile.outputStream().use { output ->
            val buffer = ByteArray(32 * 1024)
            var downloaded = 0L
            var lastReport = 0L
            while (true) {
                val bytes = input.read(buffer)
                if (bytes == -1) break
                output.write(buffer, 0, bytes)
                downloaded += bytes
                if (downloaded - lastReport > 65536 || downloaded == totalBytes) {
                    lastReport = downloaded
                    val pct = if (totalBytes > 0) (downloaded * 100 / totalBytes).toInt() else 0
                    val mb = downloaded / (1024 * 1024)
                    val totalMb = if (totalBytes > 0) " / ${totalBytes / (1024 * 1024)} MB" else ""
                    val statusText = if (totalBytes > 0) "${mb}MB / ${totalBytes / (1024 * 1024)}MB ($pct%)" else "${mb}MB downloaded"
                    lastStatus = statusText
                    onProgress(pct, statusText)
                }
            }
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, targetDir: File, onEntry: ((String) -> Unit)? = null) {
        while (true) {
            val entry = tar.nextTarEntry ?: break
            val name = entry.name.substringAfter('/') // strip top-level dir
            if (name.isEmpty()) continue
            onEntry?.invoke(name)
            val outFile = File(targetDir, name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output ->
                    tar.copyTo(output)
                }
                outFile.setExecutable(true, false)
            }
        }
    }

    private fun extractZip(zip: ZipInputStream, targetDir: File, onEntry: ((String) -> Unit)? = null) {
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name.substringAfter('/') // strip top-level dir
            if (name.isEmpty()) { zip.closeEntry(); continue }
            onEntry?.invoke(name)
            val outFile = File(targetDir, name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output ->
                    zip.copyTo(output)
                }
                outFile.setExecutable(true, false)
            }
            zip.closeEntry()
        }
    }

    private fun extractDeb(debFile: File, targetDir: File) {
        val dataTarXz = File(targetDir.parentFile ?: targetDir, "data.tar.xz")
        try {
            extractArMember(debFile, "data.tar.xz", dataTarXz)
            XZInputStream(dataTarXz.inputStream().buffered()).use { xz ->
                extractTarStreamRemap(
                    input = xz,
                    destDir = targetDir,
                    stripPrefix = "data/data/com.termux/files/usr"
                )
            }
        } finally {
            dataTarXz.delete()
        }
    }

    private fun extractArMember(arFile: File, memberName: String, dest: File) {
        arFile.inputStream().buffered().use { input ->
            val magic = ByteArray(8)
            input.read(magic)
            check(String(magic) == "!<arch>\n") { "Not a valid .deb file" }
            val hdrBuf = ByteArray(60)
            while (true) {
                val n = input.read(hdrBuf)
                if (n < 60) throw RuntimeException("DEB member not found: $memberName")
                val name = String(hdrBuf, 0, 16).trim().trimEnd('/')
                val size = String(hdrBuf, 48, 10).trim().toLong()
                if (name == memberName.trimEnd('/')) {
                    FileOutputStream(dest).use { out ->
                        var rem = size
                        val buf = ByteArray(32 * 1024)
                        while (rem > 0) {
                            val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (r < 0) break
                            out.write(buf, 0, r)
                            rem -= r
                        }
                    }
                    return
                } else {
                    var rem = size + (size % 2)
                    val buf = ByteArray(4096)
                    while (rem > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                        if (r < 0) break
                        rem -= r
                    }
                }
            }
        }
    }

    private fun extractTarStreamRemap(
        input: java.io.InputStream,
        destDir: File,
        stripPrefix: String
    ) {
        TarArchiveInputStream(input).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val name = entry.name
                if (name.startsWith(stripPrefix + "/") || name == stripPrefix) {
                    val relName = name.substring(stripPrefix.length).removePrefix("/")
                    val outFile = File(destDir, relName)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        outFile.parentFile?.mkdirs()
                        runCatching { if (outFile.exists()) outFile.delete() }
                        runCatching { Os.symlink(entry.linkName, outFile.absolutePath) }
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { output ->
                            tar.copyTo(output)
                        }
                        outFile.setReadable(true, false)
                        val executable = (entry.mode and 0b001_001_001) != 0 ||
                            outFile.parentFile?.name == "bin"
                        if (executable) outFile.setExecutable(true, false)
                        runCatching { Os.chmod(outFile.absolutePath, entry.mode and 0b111_111_111) }
                    }
                }
            }
        }
    }
}
