package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.system.Os
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.aide.mobilestudio.ui.ide.IdeAction
import com.aide.mobilestudio.ui.ide.IdeChip
import com.aide.mobilestudio.ui.ide.IdeColors
import com.aide.mobilestudio.ui.ide.IdeDimens
import com.aide.mobilestudio.ui.ide.IdeDivider
import com.aide.mobilestudio.ui.ide.IdeDot
import com.aide.mobilestudio.ui.ide.IdeDrawerMenu
import com.aide.mobilestudio.ui.ide.IdeEmptyState
import com.aide.mobilestudio.ui.ide.IdeGlyphs
import com.aide.mobilestudio.ui.ide.IdeIconButton
import com.aide.mobilestudio.ui.ide.IdeKeyValueRow
import com.aide.mobilestudio.ui.ide.IdeListRow
import com.aide.mobilestudio.ui.ide.IdeMainLayout
import com.aide.mobilestudio.ui.ide.IdeMenuGroup
import com.aide.mobilestudio.ui.ide.IdeMenuItem
import com.aide.mobilestudio.ui.ide.IdeMono
import com.aide.mobilestudio.ui.ide.IdeStatusStrip
import com.aide.mobilestudio.ui.ide.LocalIdeColors
import com.aide.mobilestudio.ui.ide.LocalShapes
import com.aide.mobilestudio.ZstdInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

class ToolchainFileExplorerActivity : AppCompatActivity(), ContextMenuUtils.FileContextMenuHandler {
    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_PATH = "path"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SELECTABLE = "selectable"
        private const val EXTRA_PICK_EXE = "pick_exe"
        const val RESULT_EXE_PATH = "exe_path"
        const val REQUEST_CODE_PICK_FILES = 1003

        fun start(context: Context, kind: ToolchainManager.Kind) {
            context.startActivity(Intent(context, ToolchainFileExplorerActivity::class.java).apply {
                putExtra(EXTRA_KIND, kind.name)
                putExtra(EXTRA_SELECTABLE, true)
            })
        }

        fun startExePicker(activity: androidx.appcompat.app.AppCompatActivity, requestCode: Int, startDir: File) {
            activity.startActivityForResult(Intent(activity, ToolchainFileExplorerActivity::class.java).apply {
                putExtra(EXTRA_TITLE, "Select .exe to run")
                putExtra(EXTRA_PATH, startDir.absolutePath)
                putExtra(EXTRA_SELECTABLE, true)
                putExtra(EXTRA_PICK_EXE, true)
            }, requestCode)
        }

        fun startAt(context: Context, title: String, path: File) {
            context.startActivity(Intent(context, ToolchainFileExplorerActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_PATH, path.absolutePath)
                putExtra(EXTRA_SELECTABLE, false)
            })
        }
    }

    private lateinit var toolchain: ToolchainManager
    private lateinit var kind: ToolchainManager.Kind
    private var currentDir by mutableStateOf(File("/"))
    private var titleText: String? = null
    private var selectionAllowed: Boolean = true
    private var pickExeMode: Boolean = false
    private var visibleFiles by mutableStateOf<List<File>>(emptyList())
    private var currentPath by mutableStateOf("/")
    private var statusText by mutableStateOf("")
    private var statusOk by mutableStateOf(true)
    private var isBusy by mutableStateOf(false)
    private var clipboardFile: File? = null
    private var clipboardMove: Boolean = false
    private var clipboardText by mutableStateOf<String?>(null)
    private var sortMode by mutableStateOf(0) // 0=name, 1=size, 2=modified
    private var showHidden by mutableStateOf(false)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolchain = ToolchainManager(this)
        titleText = intent.getStringExtra(EXTRA_TITLE)
        selectionAllowed = intent.getBooleanExtra(EXTRA_SELECTABLE, true)
        pickExeMode = intent.getBooleanExtra(EXTRA_PICK_EXE, false)
        kind = runCatching {
            ToolchainManager.Kind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "")
        }.getOrDefault(ToolchainManager.Kind.SDK)
        currentDir = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
            ?: toolchain.activeDir(kind)
            ?: File(applicationInfo.dataDir)
        refreshDirectory()
        setContent {
            ThemeManager.ProvideTheme {
                FileExplorerScreen()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        MobileStudioCrashGuard.safe(this, "ToolchainFileExplorerActivity.onBackPressed") {
            navigateUp()
        }
    }

    @Composable
    private fun FileExplorerScreen() {
        val ide = LocalIdeColors.current

        IdeMainLayout(
            title = titleText ?: "Select ${kind.displayName} path",
            subtitle = currentPath,
            subtitleMono = true,
            showBack = true,
            onBack = { navigateUp() },
            drawerContent = {
                val canPaste = clipboardFile != null || clipboardText != null
                IdeDrawerMenu(
                    listOf(
                        IdeMenuGroup(
                            "File",
                            buildList {
                                if (selectionAllowed && !pickExeMode) {
                                    add(IdeMenuItem("Use this folder", onClick = { useCurrentDirectory() }))
                                }
                                add(IdeMenuItem("New folder", onClick = { showNewFolderDialog() }))
                                add(IdeMenuItem("Upload files", onClick = { pickFilesToUpload() }))
                                add(
                                    if (canPaste) {
                                        IdeMenuItem("Paste", onClick = { pasteIntoCurrentDirectory() })
                                    } else {
                                        IdeMenuItem("Paste")
                                    },
                                )
                                add(IdeMenuItem("Compress to zip", onClick = { zipCurrentDirectory() }))
                                add(IdeMenuItem("Refresh", onClick = { refreshDirectory() }))
                            },
                        ),
                        IdeMenuGroup(
                            "View",
                            listOf(
                                IdeMenuItem("Sort by name", checked = sortMode == 0, onClick = { sortMode = 0 }),
                                IdeMenuItem("Sort by size", checked = sortMode == 1, onClick = { sortMode = 1 }),
                                IdeMenuItem("Sort by modified", checked = sortMode == 2, onClick = { sortMode = 2 }),
                                IdeMenuItem("Hidden files", checked = showHidden, onClick = { showHidden = !showHidden }),
                            ),
                        ),
                        IdeMenuGroup(
                            "Navigate",
                            listOf(
                                IdeMenuItem("Up one level", onClick = { navigateUp() }),
                                IdeMenuItem("App data", onClick = {
                                    currentDir = File(applicationInfo.dataDir)
                                    refreshDirectory()
                                }),
                                IdeMenuItem("/sdcard", onClick = {
                                    if (requestExternalStorageAccess()) {
                                        currentDir = Environment.getExternalStorageDirectory()
                                        refreshDirectory()
                                    }
                                }),
                                IdeMenuItem("Downloads", onClick = {
                                    if (requestExternalStorageAccess()) {
                                        currentDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                                        refreshDirectory()
                                    }
                                }),
                                IdeMenuItem("Tools", onClick = {
                                    currentDir = File(filesDir, "toolchain")
                                    refreshDirectory()
                                }),
                            ),
                        ),
                    ),
                )
            },
            topActions = {
                if (selectionAllowed && !pickExeMode) {
                    IdeAction(text = "Use folder", onClick = { useCurrentDirectory() }, primary = true)
                }
                IdeAction(
                    text = "Paste",
                    onClick = { pasteIntoCurrentDirectory() },
                    enabled = clipboardFile != null || clipboardText != null,
                )
                IdeAction(text = "More", onClick = { showDirectoryMenu() }, iconRes = R.drawable.ic_ide_more, iconSize = 16.dp)
            },
            secondaryActions = {
                IdeIconButton(
                    glyph = IdeGlyphs.refresh,
                    onClick = { refreshDirectory() },
                    iconRes = R.drawable.ic_ide_refresh,
                    contentDescription = "Refresh",
                )
                IdeAction(
                    text = "New Folder",
                    onClick = { showNewFolderDialog() },
                    primary = false,
                    iconRes = R.drawable.ic_ide_add,
                    iconSize = 16.dp,
                )
                IdeAction(
                    text = "Upload",
                    onClick = { pickFilesToUpload() },
                    primary = false,
                    iconRes = R.drawable.ic_ide_upload,
                    iconSize = 16.dp,
                )
            },
            breadcrumb = currentPath,
            statusBar = {
                IdeStatusStrip(
                    left = {
                        IdeDot(if (statusOk) ide.success else ide.warning)
                        Spacer(Modifier.width(IdeDimens.space3))
                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = ide.textSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    right = {
                        Text("${visibleFiles.size} items", style = IdeMono.log, color = ide.muted)
                        if (isBusy) {
                            Spacer(Modifier.width(IdeDimens.space4))
                            Text("working…", style = IdeMono.log, color = ide.accent)
                        }
                    },
                )
            },
            // Landscape tool rail: quick toggles that also live in the More menu.
            rail = {
                IdeIconButton(
                    glyph = IdeGlyphs.sort,
                    onClick = { sortMode = (sortMode + 1) % 3 },
                    iconRes = R.drawable.ic_ide_up,
                    selected = true,
                    contentDescription = "Cycle sort",
                )
                IdeIconButton(
                    glyph = if (showHidden) IdeGlyphs.hiddenShown else IdeGlyphs.hiddenOff,
                    onClick = { showHidden = !showHidden },
                    selected = showHidden,
                    iconRes = R.drawable.ic_ide_eye,
                    contentDescription = "Hidden files",
                )
            },
        ) {
            // Quick jump locations.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = IdeDimens.space3),
                horizontalArrangement = Arrangement.spacedBy(IdeDimens.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IdeChip("App") {
                    currentDir = File(applicationInfo.dataDir)
                    refreshDirectory()
                }
                IdeChip("/sdcard") {
                    if (requestExternalStorageAccess()) {
                        currentDir = Environment.getExternalStorageDirectory()
                        refreshDirectory()
                    }
                }
                IdeChip("Tools") {
                    currentDir = File(filesDir, "toolchain")
                    refreshDirectory()
                }
                IdeChip("Downloads") {
                    if (requestExternalStorageAccess()) {
                        currentDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        refreshDirectory()
                    }
                }
            }

            if (clipboardText != null || clipboardFile != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ide.hover)
                        .border(0.5.dp, ide.divider)
                        .padding(horizontal = IdeDimens.gutter, vertical = IdeDimens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = clipboardText ?: clipboardFile?.name.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = ide.textSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Spacer(Modifier.width(IdeDimens.space4))
                    IdeAction(text = "Paste here", onClick = { pasteIntoCurrentDirectory() })
                }
            }

            IdeDivider()

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (visibleFiles.isEmpty()) {
                    IdeEmptyState(
                        title = "Empty folder",
                        hint = "The folder has no readable entries, or access was denied.",
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(visibleFiles, key = { it.absolutePath }) { file ->
                            FileRow(file)
                            IdeDivider()
                        }
                    }
                }
            }
        }
    }

    /** One place for "up one level, or leave the screen at the root". */
    private fun navigateUp() {
        val parent = currentDir.parentFile
        if (parent != null && currentDir.absolutePath != "/") {
            currentDir = parent
            refreshDirectory()
        } else {
            MobileStudioCrashGuard.finishSafely(this)
        }
    }

    /** 새 폴더 만들기. */
    private fun showNewFolderDialog() {
        val input = EditText(this).apply {
            hint = "폴더 이름"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("새 폴더")
            .setView(input)
            .setPositiveButton("만들기") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this@ToolchainFileExplorerActivity, "폴더 이름을 입력하세요.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newDir = File(currentDir, name)
                if (newDir.exists()) {
                    Toast.makeText(this@ToolchainFileExplorerActivity, "이미 존재하는 폴더입니다.", Toast.LENGTH_SHORT).show()
                } else if (newDir.mkdir()) {
                    refreshDirectory()
                    Toast.makeText(this@ToolchainFileExplorerActivity, "폴더를 만들었습니다: $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ToolchainFileExplorerActivity, "폴더 생성에 실패했습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 파일 업로드 - 기기에서 파일을 선택해 현재 폴더로 복사. */
    private fun pickFilesToUpload() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "파일 선택"), REQUEST_CODE_PICK_FILES)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_FILES && resultCode == RESULT_OK && data != null) {
            data.clipData?.let { clip ->
                var copied = 0
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { uri ->
                        if (copyUriToCurrentDir(uri)) copied++
                    }
                }
                if (copied > 0) {
                    refreshDirectory()
                    Toast.makeText(this, "${copied}개 파일을 업로드했습니다.", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                data.data?.let { uri ->
                    if (copyUriToCurrentDir(uri)) {
                        refreshDirectory()
                        Toast.makeText(this, "파일을 업로드했습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun copyUriToCurrentDir(uri: Uri): Boolean {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return false
            val fileName = uri.lastPathSegment?.let { it.substringAfterLast("/") } ?: "uploaded_file"
            val target = File(currentDir, fileName)
            input.use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Toast.makeText(this, "업로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun FileRow(file: File) {
        val ide = LocalIdeColors.current
        val kind = fileKind(file)
        IdeListRow(
            title = file.name.ifBlank { file.absolutePath },
            subtitle = fileSubtitle(file),
            titleEmphasis = file.isDirectory,
            onClick = { openFile(file) },
            onLongClick = { showFileActions(file) },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(IdeDimens.badge)
                        .background(ide.hover, LocalShapes.current.field)
                        .border(0.5.dp, kind.tint(ide).copy(alpha = 0.7f), LocalShapes.current.field),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        kind.code,
                        style = IdeMono.path.copy(fontSize = 10.sp),
                        color = kind.tint(ide),
                    )
                }
            },
            trailingContent = {
                IdeIconButton(
                    glyph = IdeGlyphs.overflow,
                    onClick = { showFileActions(file) },
                    iconRes = R.drawable.ic_ide_more,
                    contentDescription = "More actions",
                )
            },
        )
    }

    /** File class drives both the row badge text and its tint. */
    private enum class FileKind(val code: String) {
        DIR("DIR"), ARCHIVE("ZIP"), EXEC("EXE"), TEXT("TXT");

        fun tint(ide: IdeColors): Color = when (this) {
            DIR -> ide.accent
            ARCHIVE -> ide.info
            EXEC -> ide.success
            TEXT -> ide.textSoft
        }
    }

    private fun fileKind(file: File): FileKind = when {
        file.isDirectory -> FileKind.DIR
        isArchive(file) -> FileKind.ARCHIVE
        file.canExecute() -> FileKind.EXEC
        else -> FileKind.TEXT
    }

    private fun refreshDirectory() {
        currentDir = runCatching { currentDir.canonicalFile }.getOrDefault(currentDir)
        currentPath = currentDir.absolutePath
        val validation = if (selectionAllowed) validatePath(kind, currentDir) else null
        statusOk = validation == null
        statusText = when {
            pickExeMode -> "Tap any .exe file to run it. Long-press for copy, move, rename, delete, or permissions."
            !selectionAllowed -> "Tap a folder to open it. Long-press or tap the menu button for copy, move, delete, rename, permissions, extract, APK install, or JAR run."
            validation == null -> "OK: This folder can be used as ${kind.displayName}."
            else -> "WARN: $validation"
        }
        visibleFiles = currentDir.listFiles()
            ?.filter { showHidden || !it.name.startsWith(".") }
            ?.sortedWith(
                when (sortMode) {
                    1 -> compareBy<File>({ !it.isDirectory }, { -it.length() })
                    2 -> compareBy<File>({ !it.isDirectory }, { -it.lastModified() })
                    else -> compareBy({ !it.isDirectory }, { file -> file.name.lowercase(Locale.US) })
                }
            )
            ?: emptyList()
    }

    private fun requestExternalStorageAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) return true
            AlertDialog.Builder(this)
                .setTitle("Storage Access Required")
                .setMessage("This feature requires access to manage all files. Grant permission in Settings.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                .setNegativeButton("Cancel", null)
                .show()
            return false
        }
        return true
    }

    private fun fileSubtitle(file: File): String {
        val size = if (file.isDirectory) {
            "${file.listFiles()?.size ?: 0} items"
        } else {
            formatSize(file.length())
        }
        return "$size | ${permissions(file)} | ${dateFormat.format(Date(file.lastModified()))}"
    }

    private fun showDirectoryMenu() {
        val sortLabel = when (sortMode) { 0 -> "Name"; 1 -> "Size"; else -> "Modified" }
        val items = listOf(
            "New File",
            "New Folder",
            "Compress folder to .zip",
            "Sort: $sortLabel",
            if (showHidden) "Hide hidden files" else "Show hidden files"
        )
        AlertDialog.Builder(this)
            .setTitle(currentDir.name.ifEmpty { "/" })
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> promptCreateEntry(isFile = true)
                    1 -> promptCreateEntry(isFile = false)
                    2 -> zipCurrentDirectory()
                    3 -> {
                        sortMode = (sortMode + 1) % 3
                        refreshDirectory()
                        toast("Sort: " + when (sortMode) { 0 -> "Name"; 1 -> "Size"; else -> "Modified" })
                    }
                    4 -> {
                        showHidden = !showHidden
                        refreshDirectory()
                    }
                }
            }
            .show()
    }

    private fun promptCreateEntry(isFile: Boolean) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val input = EditText(this).apply {
            hint = if (isFile) "file name (e.g. Main.kt)" else "folder name"
            isSingleLine = true
        }
        val wrapper = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(
                input,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(if (isFile) "New File" else "New Folder")
            .setView(wrapper)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name.contains('/')) {
                    toast("Invalid name")
                    return@setPositiveButton
                }
                val target = File(currentDir, name)
                if (target.exists()) {
                    toast("Already exists: $name")
                    return@setPositiveButton
                }
                val ok = if (isFile) runCatching { target.createNewFile() }.getOrDefault(false)
                else runCatching { target.mkdirs() }.getOrDefault(false)
                if (ok) {
                    toast("${if (isFile) "Created " else "Created folder "}$name")
                    refreshDirectory()
                } else {
                    toast("Could not create $name")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun zipCurrentDirectory() = zipEntry(currentDir)

    private fun zipEntry(target: File) {
        val isDir = target.isDirectory
        val outFile = File(
            target.parentFile ?: target,
            (if (isDir) target.name.ifEmpty { "folder" } else target.nameWithoutExtension.ifEmpty { "file" }) + ".zip"
        )
        if (outFile.absolutePath == target.absolutePath) return
        toast("Compressing to ${outFile.name} ...")
        Thread {
            try {
                java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(outFile.outputStream())).use { zos ->
                    if (isDir) {
                        target.walkTopDown().filter { it.isFile && it != outFile }.forEach { f ->
                            val rel = target.name + "/" + f.relativeTo(target).path.replace('\\', '/')
                            zos.putNextEntry(java.util.zip.ZipEntry(rel))
                            f.inputStream().use { inp -> inp.copyTo(zos) }
                            zos.closeEntry()
                        }
                    } else {
                        zos.putNextEntry(java.util.zip.ZipEntry(target.name))
                        target.inputStream().use { inp -> inp.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                runOnUiThread {
                    toast("Created ${outFile.name} (${formatSize(outFile.length())})")
                    refreshDirectory()
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Zip failed: ${e.message}") }
            }
        }.start()
    }

    private fun showFileActions(file: File) {
        val actions = buildList {
            add("Open")
            if (file.extension.equals("exe", true)) {
                add(if (AppLanguage.isKorean(this@ToolchainFileExplorerActivity)) "box64로 실행" else "Run with box64")
                val hasSource = listOf("main.c", "main.cpp", "main.cc").any { File(file.parentFile, it).isFile }
                if (hasSource) add(if (AppLanguage.isKorean(this@ToolchainFileExplorerActivity)) "box64 빌드 & 실행" else "Build & Run (box64)")
            }
            if (isTextLike(file)) add("Open Text")
            if (isImage(file)) add("Open Image")
            if (file.extension.equals("apk", true)) {
                add("Install APK")
                add(if (AppLanguage.isKorean(this@ToolchainFileExplorerActivity)) "디컴파일 (jadx)" else "Decompile (jadx)")
                add(if (AppLanguage.isKorean(this@ToolchainFileExplorerActivity)) "APK 내용 보기" else "View contents")
                add(if (AppLanguage.isKorean(this@ToolchainFileExplorerActivity)) "APK 서명 확인" else "Check signature")
            }
            if (file.extension.equals("jar", true)) add("Run JAR")
            if (selectionAllowed && file.isDirectory) add("Use as ${kind.displayName}")
            if (isArchive(file) && !file.extension.equals("apk", true)) {
                add(if (AppLanguage.isKorean(this@ToolchainFileExplorerActivity)) "보기 (압축 해제 없이)" else "View contents")
                add("Extract here")
            }
            add("Compress to .zip")
            add("Copy Path")
            add("Copy")
            add("Move")
            add("Rename")
            add("Delete")
            add("Permissions")
            add("Set executable")
        }
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Open" -> openFile(file)
                    "Open Text" -> openTextFile(file)
                    "Run with box64" -> runExeWithBox64(file)
                    "Build & Run (box64)" -> {
                        val src = listOf(File(file.parentFile, "main.c"), File(file.parentFile, "main.cpp"), File(file.parentFile, "main.cc"))
                            .firstOrNull { it.isFile }
                        if (src != null) {
                            toast("Building ${src.name}...")
                            Thread {
                                try {
                                    val env = WindowsExeEnvironment(this@ToolchainFileExplorerActivity)
                                    if (!env.isInstalled) {
                                        runOnUiThread { toast("Wine/box64 not installed") }
                                        return@Thread
                                    }
                                    val built = env.buildWindowsExe(src, onLine = { })
                                    if (built != null) runOnUiThread { runExeWithBox64(built) }
                                    else runOnUiThread { toast("Build failed") }
                                } catch (e: Exception) {
                                    runOnUiThread { toast("Build error: ${e.message}") }
                                }
                            }.start()
                        } else runExeWithBox64(file)
                    }
                    "Install APK" -> installApk(file)
                    "Open Image" -> openImageFile(file)
                    "Decompile (jadx)" -> decompileApk(file)
                    "APK 서명 확인" -> checkApkSignature(file)
                    "Run JAR" -> runJar(file)
                    "Use as ${kind.displayName}" -> {
                        currentDir = file
                        useCurrentDirectory()
                    }
                    "Extract here" -> extractArchive(file)
                    // Two labels, one behaviour: an APK and a tar are both
                    // archives, and looking inside is the same question.
                    "View contents", "APK 내용 보기", "보기 (압축 해제 없이)" -> showArchiveContents(file)
                    "Compress to .zip" -> zipEntry(file)
                    "Copy Path" -> {
                        val path = file.absolutePath
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FilePath", path))
                        toast("Path copied: $path")
                    }
                    "Copy" -> {
                        clipboardFile = file
                        clipboardMove = false
                        clipboardText = "Copy ready: ${file.name}"
                        toast("Copied: ${file.name}. Open a folder and tap Paste.")
                    }
                    "Move" -> {
                        clipboardFile = file
                        clipboardMove = true
                        clipboardText = "Move ready: ${file.name}"
                        toast("Move selected: ${file.name}. Open a folder and tap Paste.")
                    }
                    "Rename" -> renameFile(file)
                    "Delete" -> confirmDelete(file)
                    "Permissions" -> showPermissions(file)
                    "Set executable" -> setExecutable(file)
                }
            }
            .show()
    }

    override fun showFileContextMenu(file: File) {
        showFileActions(file)
    }

    private fun openFile(file: File) {
        if (pickExeMode) {
            if (file.isDirectory) {
                currentDir = file
                refreshDirectory()
            } else if (file.isFile) {
                setResult(RESULT_OK, Intent().putExtra(RESULT_EXE_PATH, file.absolutePath))
                MobileStudioCrashGuard.finishSafely(this)
            }
            return
        }
        when {
            file.isDirectory -> {
                currentDir = file
                refreshDirectory()
            }
            file.extension.equals("apk", true) -> installApk(file)
            file.extension.equals("jar", true) -> runJar(file)
            file.extension.equals("exe", true) -> showFileActions(file)
            isImage(file) -> openImageFile(file)
            isArchive(file) -> showArchiveContents(file)
            isTextLike(file) -> openTextFile(file)
            else -> toast("No built-in opener for .${file.extension.ifBlank { "file" }}")
        }
    }

    /**
     * Extensions the in-app viewer can display.
     *
     * Bitmap formats only. A drawable XML is left to the text opener: it is a
     * vector description, and decoding it needs the resource compiler rather
     * than BitmapFactory.
     */
    private fun isImage(file: File): Boolean {
        if (file.isDirectory) return false
        return file.extension.lowercase(Locale.US) in setOf(
            "png", "jpg", "jpeg", "webp", "bmp", "gif", "ico",
        )
    }

    private fun isTextLike(file: File): Boolean {
        if (file.isDirectory) return false
        return file.extension.lowercase(Locale.US) in setOf(
            "kt", "kts", "java", "xml", "gradle", "properties", "txt", "md", "json",
            "h", "hpp", "c", "cc", "cpp", "cmake", "pro", "sh", "log"
        )
    }

    private fun openTextFile(file: File) {
        Thread {
            val result = runCatching {
                val bytes = file.inputStream().use { input ->
                    val output = ByteArrayOutputStream()
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
            runOnUiThread {
                val textView = TextView(this).apply {
                    text = result
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextColor(StudioUi.color(StudioUi.Text))
                    setTextIsSelectable(true)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                AlertDialog.Builder(this)
                    .setTitle(file.name)
                    .setView(ScrollView(this).apply { addView(textView) })
                    .setPositiveButton("Close", null)
                    .show()
            }
        }.start()
    }

    private fun installApk(file: File) {
        val uri = fileProviderUri(file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }
            .onFailure { toast("APK install failed: ${it.message}") }
    }

    /**
     * Decomposes an APK with jadx.
     *
     * jadx is not bundled - it is several megabytes and most builds never
     * need it - so it is looked up on the tool paths the app already has.
     * When it is missing the dialog says so and offers the built-in contents
     * listing instead, which answers the smaller half of the same question
     * without any download.
     */
    private fun decompileApk(file: File) {
        val jadx = findJadx()
        if (jadx == null) {
            AlertDialog.Builder(this)
                .setTitle("jadx")
                .setMessage(
                    if (AppLanguage.isKorean(this)) {
                        "jadx를 찾지 못했습니다. 터미널에서 설치한 뒤 다시 시도하세요.\n\n" +
                            "APK 내부 목록은 jadx 없이도 볼 수 있습니다."
                    } else {
                        "jadx was not found. Install it in the terminal and try again.\n\n" +
                            "The APK's contents can still be listed without it."
                    },
                )
                .setPositiveButton("View contents") { _, _ -> showArchiveContents(file) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val outDir = File(file.parentFile, file.nameWithoutArchiveExtension() + "-src")
        AlertDialog.Builder(this)
            .setTitle("jadx")
            .setMessage(
                if (AppLanguage.isKorean(this)) {
                    "${file.name}\n\n출력: ${outDir.name}/"
                } else {
                    "${file.name}\n\nOutput: ${outDir.name}/"
                },
            )
            .setPositiveButton("Decompile") { _, _ ->
                toast("Decompiling ${file.name}...")
                Thread {
                    val result = runCatching {
                        val process = ProcessBuilder(
                            jadx.absolutePath, "-d", outDir.absolutePath, file.absolutePath,
                        ).redirectErrorStream(true).start()
                        val out = process.inputStream.bufferedReader().readText()
                        val code = process.waitFor()
                        if (code != 0) "jadx exited with $code\n$out" else null
                    }.getOrElse { "jadx failed: ${it.message}" }

                    runOnUiThread {
                        if (result == null) {
                            toast("Decompiled to ${outDir.name}/")
                            currentDir = outDir
                            refreshDirectory()
                        } else {
                            openTextOutput("jadx", result)
                        }
                    }
                }.start()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Locates a jadx launcher on the paths the app's toolchain uses. */
    private fun findJadx(): File? {
        val names = listOf("jadx", "jadx-cli")
        val roots = listOf(
            File(filesDir, "toolchain/runtime-tools/bin"),
            File(filesDir, "toolchain/usr/bin"),
            File(filesDir, "usr/bin"),
            File("/system/bin"),
        )
        for (root in roots) {
            for (name in names) {
                val candidate = File(root, name)
                if (candidate.isFile && candidate.canExecute()) return candidate
            }
        }
        // Fall back to PATH so a jadx installed in the terminal is found too.
        val path = System.getenv("PATH") ?: return null
        return path.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { dir -> names.mapNotNull { n ->
                File(dir, n).takeIf { it.isFile && it.canExecute() }
            }.firstOrNull() }
            .firstOrNull()
    }

    /**
     * Shows the certificates an APK is signed with.
     *
     * Worth having next to install: a "release" APK signed with the debug key
     * is a common mistake, and it is invisible until the install is refused on
     * a device that already has the app.
     */
    private fun checkApkSignature(file: File) {
        Thread {
            val info = runCatching {
                packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    android.content.pm.PackageManager.GET_SIGNATURES,
                )
            }.getOrNull()

            runOnUiThread {
                if (info == null) {
                    toast("Could not read the APK")
                    return@runOnUiThread
                }
                val body = StringBuilder()
                body.append("package: ${info.packageName}\n")
                body.append("version: ${info.versionName ?: "?"}\n")
                body.append("signatures: ${info.signatures?.size ?: 0}\n\n")
                info.signatures?.forEach { signature ->
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(signature.toByteArray())
                    body.append(
                        digest.joinToString(":") { String.format("%02X", it) },
                    )
                    body.append('\n')
                }
                openTextOutput("${file.name} — signature", body.toString())
            }
        }.start()
    }

    /** Shows a block of text in a scrollable dialog. */
    private fun openTextOutput(title: String, body: String) {
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.Text))
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            text = body
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(text) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun runExeWithBox64(exeFile: File) {
        val output = StringBuilder("> box64 + wine64: ${exeFile.name}\n")
        val textView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.Text))
            setTextIsSelectable(true)
            setHorizontallyScrolling(true)
            text = output.toString()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("box64 Console")
            .setView(ScrollView(this).apply { addView(textView) })
            .setNegativeButton("Close", null)
            .setCancelable(false)
            .show()
        Thread {
            try {
                val env = WindowsExeEnvironment(this@ToolchainFileExplorerActivity)
                if (!env.isInstalled) {
                    runOnUiThread {
                        output.append("\n> Wine/box64 environment not installed.\n")
                        textView.text = output.toString()
                        toast("Wine/box64 not installed")
                    }
                    return@Thread
                }
                if (!env.isPrefixBooted) {
                    runOnUiThread { output.append("\n> Bootstrapping Wine prefix (first run)...\n"); textView.text = output.toString() }
                    env.bootstrapPrefix { msg -> runOnUiThread { output.append("$msg\n"); textView.text = output.toString() } }
                }
                val code = env.runWindowsExe(exeFile) { line -> runOnUiThread { output.append("$line\n"); textView.text = output.toString() } }
                runOnUiThread { output.append("\n> exit=$code\n"); textView.text = output.toString() }
            } catch (e: Exception) {
                runOnUiThread { output.append("\n> Run error: ${e.message}\n"); textView.text = output.toString() }
            }
        }.start()
    }

    private fun runJar(file: File) {
        val javaBin = listOf(
            JreGradleInstaller(this, {}, {}).effectiveJavaBin,
            File(filesDir, "toolchain/jdk/lib/jvm/java-17-openjdk/bin/java"),
            File(filesDir, "toolchain/jdk/jdk-21/bin/java"),
            File(filesDir, "toolchain/jdk/shim/java-17-openjdk/bin/java")
        ).firstOrNull { java -> java.exists() } ?: run {
            toast("JDK not found")
            return
        }
        val output = StringBuilder("> ${javaBin.absolutePath} -jar ${file.absolutePath}\n")
        val dialog = AlertDialog.Builder(this)
            .setTitle("JAR Console")
            .setView(ScrollView(this).apply {
                addView(TextView(this@ToolchainFileExplorerActivity).apply {
                    text = output.toString()
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextColor(StudioUi.color(StudioUi.Text))
                    setTextIsSelectable(true)
                    tag = "jar_output"
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
                    append(applicationInfo.nativeLibraryDir)
                }
            )
            val result = ShellRunner.runSh(
                context = this,
                command = "\"${javaBin.absolutePath}\" -jar \"${file.absolutePath}\"",
                cwd = file.parentFile ?: currentDir,
                env = env
            ) { line ->
                output.append(line).append('\n')
                runOnUiThread {
                    findJarOutputView(dialog)?.text = output.toString().takeLast(80_000)
                }
            }
            output.append("\nexit=").append(result.exitCode)
            runOnUiThread {
                findJarOutputView(dialog)?.text = output.toString().takeLast(80_000)
            }
        }.start()
    }

    private fun findJarOutputView(dialog: AlertDialog): TextView? {
        return dialog.window?.decorView?.findViewWithTag("jar_output")
    }

    private fun fileProviderUri(file: File): Uri {
        return FileProvider.getUriForFile(this, "${packageName}.provider", file)
    }

    private fun pasteIntoCurrentDirectory() {
        val source = clipboardFile ?: run {
            toast("Nothing to paste")
            return
        }
        if (!source.exists()) {
            toast("Source no longer exists")
            clipboardFile = null
            clipboardText = null
            return
        }
        val target = uniqueTarget(File(currentDir, source.name))
        isBusy = true
        Thread {
            val error = runCatching {
                if (clipboardMove) {
                    if (!source.renameTo(target)) {
                        copyPath(source, target)
                        source.deleteRecursively()
                    }
                } else {
                    copyPath(source, target)
                }
            }.exceptionOrNull()
            runOnUiThread {
                isBusy = false
                if (error == null) {
                    if (clipboardMove) {
                        clipboardFile = null
                        clipboardText = null
                    }
                    toast(if (clipboardMove) "Moved to ${target.name}" else "Copied to ${target.name}")
                    refreshDirectory()
                } else {
                    toast("Paste failed: ${error.message}")
                }
            }
        }.start()
    }

    private fun renameFile(file: File) {
        val input = EditText(this).apply {
            setText(file.name)
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank() || "/" in name) {
                    toast("Invalid name")
                    return@setPositiveButton
                }
                val target = File(file.parentFile, name)
                if (target.exists()) {
                    toast("Already exists")
                } else if (file.renameTo(target)) {
                    toast("Renamed")
                    refreshDirectory()
                } else {
                    toast("Rename failed")
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
                isBusy = true
                Thread {
                    val ok = if (file.isDirectory) file.deleteRecursively() else file.delete()
                    runOnUiThread {
                        isBusy = false
                        toast(if (ok) "Deleted" else "Delete failed")
                        refreshDirectory()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPermissions(file: File) {
        val detail = buildString {
            appendLine(file.absolutePath)
            appendLine("Readable: ${file.canRead()}")
            appendLine("Writable: ${file.canWrite()}")
            appendLine("Executable: ${file.canExecute()}")
            appendLine("Unix: ${permissions(file)}")
            appendLine("Size: ${if (file.isDirectory) "${file.listFiles()?.size ?: 0} items" else formatSize(file.length())}")
        }
        AlertDialog.Builder(this)
            .setTitle("Permissions")
            .setMessage(detail)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setExecutable(file: File) {
        val ok = runCatching {
            file.setReadable(true, false)
            file.setWritable(true, true)
            file.setExecutable(true, false)
            Os.chmod(file.absolutePath, if (file.isDirectory) 0b111_101_101 else 0b111_101_101)
        }.isSuccess
        toast(if (ok) "Executable permission granted" else "Permission change failed")
        refreshDirectory()
    }

    private fun extractArchive(file: File) {
        val dest = File(file.parentFile, file.nameWithoutArchiveExtension()).also { it.mkdirs() }
        isBusy = true
        Thread {
            val error = runCatching {
                when {
                    file.name.endsWith(".zip", ignoreCase = true) -> extractZip(file, dest)
                    file.name.endsWith(".tar.gz", ignoreCase = true) || file.name.endsWith(".tgz", ignoreCase = true) ->
                        GZIPInputStream(file.inputStream().buffered()).use { input -> extractTarStream(input, dest) }
                    file.name.endsWith(".tar.xz", ignoreCase = true) || file.name.endsWith(".txz", ignoreCase = true) ->
                        XZInputStream(file.inputStream().buffered()).use { input -> extractTarStream(input, dest) }
                    file.name.endsWith(".tar.zst", ignoreCase = true) ->
                        java.io.FileInputStream(file).use { fileIn ->
                            ZstdInputStream(fileIn.buffered()).use { input -> extractTarStream(input, dest) }
                        }
                    file.name.endsWith(".tar", ignoreCase = true) ->
                        file.inputStream().buffered().use { input -> extractTarStream(input, dest) }
                    else -> error("Unsupported archive")
                }
            }.exceptionOrNull()
            runOnUiThread {
                isBusy = false
                if (error == null) {
                    toast("Extracted to ${dest.name}")
                    refreshDirectory()
                } else {
                    toast("Extract failed: ${error.message}")
                }
            }
        }.start()
    }

    private fun extractZip(zipFile: File, dest: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = safeExtractTarget(dest, entry.name)
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

    private fun extractTarStream(input: InputStream, dest: File) {
        TarArchiveInputStream(input).use { tar ->
            var entry = tar.nextTarEntry
            while (entry != null) {
                val out = safeExtractTarget(dest, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else if (entry.isSymbolicLink) {
                    // Skip links for safety inside the app explorer.
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { tar.copyTo(it) }
                    if ((entry.mode and 0b001_001_001) != 0) out.setExecutable(true, false)
                }
                entry = tar.nextTarEntry
            }
        }
    }

    private fun safeExtractTarget(root: File, name: String): File {
        val target = File(root, name).canonicalFile
        val canonicalRoot = root.canonicalFile.absolutePath
        check(target.absolutePath.startsWith(canonicalRoot)) { "Unsafe archive entry: $name" }
        return target
    }

    private fun copyPath(source: File, target: File) {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child -> copyPath(child, File(target, child.name)) }
        } else {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = false)
            target.setReadable(source.canRead(), false)
            target.setWritable(source.canWrite(), true)
            target.setExecutable(source.canExecute(), false)
        }
    }

    private fun uniqueTarget(file: File): File {
        if (!file.exists()) return file
        val parent = file.parentFile ?: currentDir
        val base = file.nameWithoutExtension
        val ext = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1
        while (true) {
            val candidate = File(parent, "$base copy $index$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun useCurrentDirectory() {
        val warning = validatePath(kind, currentDir)
        if (warning != null) {
            AlertDialog.Builder(this)
                .setTitle("Use anyway?")
                .setMessage("$warning\n\nUse this path anyway?")
                .setPositiveButton("Use") { _, _ -> savePath() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        savePath()
    }

    private fun savePath() {
        if (toolchain.setActivePath(kind, currentDir)) {
            toast("Active ${kind.displayName}: ${currentDir.absolutePath}")
            MobileStudioCrashGuard.finishSafely(this)
        } else {
            toast("Cannot use this path")
        }
    }

    private fun validatePath(kind: ToolchainManager.Kind, dir: File): String? {
        if (!dir.isDirectory) return "Selected path is not a directory."
        return when (kind) {
            ToolchainManager.Kind.JDK -> if (
                File(dir, "bin/java").exists() &&
                (File(dir, "lib/libjli.so").exists() || File(dir, "lib/jli/libjli.so").exists())
            ) null else "JDK folder should contain bin/java and libjli.so."
            ToolchainManager.Kind.GRADLE -> if (File(dir, "lib").listFiles()
                    ?.any { file -> (file.name.startsWith("gradle-launcher") || file.name.startsWith("gradle-gradle-cli-main")) && file.extension == "jar" } == true
            ) null else "Gradle folder should contain lib/gradle-launcher*.jar."
            ToolchainManager.Kind.SDK -> if (
                File(dir, "platforms").isDirectory || File(dir, "android.jar").exists()
            ) null else "SDK folder should contain platforms/android-XX/android.jar."
            ToolchainManager.Kind.NDK -> if (
                File(dir, "toolchains/llvm/prebuilt").isDirectory
            ) null else "NDK folder should contain toolchains/llvm/prebuilt."
            ToolchainManager.Kind.CMAKE -> if (
                File(dir, "bin/cmake").exists() || File(dir, "bin/cmake.exe").exists()
            ) null else "CMake folder should contain bin/cmake."
            ToolchainManager.Kind.PYTHON -> if (
                File(dir, "bin/python3").exists() || File(dir, "bin/python").exists()
            ) null else "Python folder should contain bin/python3 or bin/python."
        }
    }

    private fun isArchive(file: File): Boolean =
        file.isFile && ArchiveInspector.isArchive(file.name)

    /**
     * Lists an archive's contents without unpacking it.
     *
     * The whole point is answering "what is in here?" before deciding whether
     * to spend the space and time on extracting it, so extraction is offered
     * from inside this listing rather than being the only way to look.
     */
    private fun showArchiveContents(file: File) {
        Thread {
            val listing = runCatching { ArchiveInspector.inspect(file) }
                .getOrElse { null }
            runOnUiThread {
                if (listing == null) {
                    toast("Could not read ${file.name}")
                    return@runOnUiThread
                }
                val body = StringBuilder()
                listing.entries.take(400).forEach { entry ->
                    body.append(if (entry.isDirectory) "d  " else "   ")
                    body.append(entry.name)
                    if (!entry.isDirectory && entry.size >= 0) {
                        body.append("   ").append(entry.humanSize)
                    }
                    body.append('\n')
                }
                if (listing.truncated) {
                    body.append("\n… showing the first ${listing.entries.size} entries\n")
                }

                val text = TextView(this).apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 11f
                    setTextColor(StudioUi.color(StudioUi.Text))
                    setTextIsSelectable(true)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    text = buildString {
                        append("${listing.format}  ·  ")
                        append("${listing.fileCount} files")
                        if (listing.directoryCount > 0) {
                            append(", ${listing.directoryCount} folders")
                        }
                        append('\n')
                        append("contents ")
                        append(ArchiveInspector.humanBytes(listing.totalUncompressed))
                        append("  ·  archive ")
                        append(ArchiveInspector.humanBytes(listing.totalCompressed))
                        append("\n\n")
                        append(body)
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle(file.name)
                    .setView(ScrollView(this).apply { addView(text) })
                    .setPositiveButton(
                        if (AppLanguage.isKorean(this)) "여기서 압축 풀기" else "Extract here",
                    ) { _, _ -> extractArchive(file) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }.start()
    }

    /** Shows an image full size, with pinch to zoom and drag to pan. */
    private fun openImageFile(file: File) {
        Thread {
            val bitmap = runCatching { decodeScaledBitmap(file, 2048) }.getOrNull()
            runOnUiThread {
                if (bitmap == null) {
                    toast("Could not decode ${file.name}")
                    return@runOnUiThread
                }
                val zoom = ZoomableImageView(this).apply {
                    showBitmap(bitmap)
                    setBackgroundColor(AndroidColor.BLACK)
                }
                AlertDialog.Builder(this)
                    .setTitle("${file.name}  ·  ${bitmap.width}×${bitmap.height}")
                    .setView(zoom, dp(8), dp(8), dp(8), dp(8))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }.start()
    }

    /**
     * Decodes a bitmap capped to [maxSide].
     *
     * A phone photo can be tens of megapixels; decoding it at full size to show
     * it in a dialog is how an image viewer gets the app killed. The cap keeps
     * the decode bounded while still being sharper than the screen.
     */
    private fun decodeScaledBitmap(file: File, maxSide: Int): android.graphics.Bitmap? {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
            sample *= 2
        }
        return android.graphics.BitmapFactory.decodeFile(
            file.absolutePath,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun File.nameWithoutArchiveExtension(): String {
        val lower = name.lowercase(Locale.US)
        val suffix = listOf(".tar.gz", ".tar.xz", ".tar.zst", ".tgz", ".txz", ".zip", ".tar")
            .firstOrNull { value -> lower.endsWith(value) }
            ?: return nameWithoutExtension
        return name.dropLast(suffix.length).ifBlank { nameWithoutExtension }
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

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
