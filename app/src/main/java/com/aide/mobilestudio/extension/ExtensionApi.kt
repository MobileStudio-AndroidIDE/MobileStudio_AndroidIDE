package com.aide.mobilestudio.extension

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.LinearLayout
import com.aide.mobilestudio.StudioUi
import java.io.File

/**
 * MobileStudio Extension Platform — API version 1.
 *
 * Third-party extensions implement [MobileStudioExtension] and are packaged as
 * .msext (ZIP) with an extension.json manifest. The core never loads extensions
 * eagerly: lazy loading keeps RAM usage low on mobile.
 */
object ExtensionApi {
    const val API_VERSION = 1

    /** Permissions an extension can request. Sensitive ones are flagged in the UI. */
    val KNOWN_PERMISSIONS = listOf(
        Permission("editor", "코드 편집", false),
        Permission("project.read", "프로젝트 파일 읽기", false),
        Permission("project.write", "프로젝트 파일 수정", false),
        Permission("filesystem", "파일 시스템 접근", true),
        Permission("terminal", "터미널 명령 실행", true),
        Permission("network", "네트워크 접근", true),
        Permission("device", "기기 제어 (APK 설치/ADB)", true),
        Permission("runtime", "코드 실행 (게임/스크립트)", true),
        Permission("ui", "UI 등록 (툴윈도우/메뉴/배너)", false),
        Permission("build", "빌드 훅/태스크 등록", false),
        Permission("storage", "확장 전용 저장소", false)
    )

    data class Permission(val id: String, val label: String, val sensitive: Boolean)

    fun permissionLabel(id: String): String = KNOWN_PERMISSIONS.firstOrNull { it.id == id }?.label ?: id
    fun isSensitive(id: String): Boolean = KNOWN_PERMISSIONS.firstOrNull { it.id == id }?.sensitive ?: true
}

/** extension.json manifest of an .msext package. */
data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    val minStudioVersion: String = "1.0.0",
    val maxStudioVersion: String = "*",
    val entryPoint: String = "",
    val permissions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val extensionApiVersion: Int = ExtensionApi.API_VERSION
) {
    fun versionCode(): List<Int> = version.split(".").map { it.toIntOrNull() ?: 0 }
}

/** Lifecycle every extension goes through. All calls are wrapped in error isolation. */
interface MobileStudioExtension {
    val manifest: ExtensionManifest

    fun onEnable(context: ExtensionContext) {}
    fun onDisable(context: ExtensionContext) {}
    fun onUninstall(context: ExtensionContext) {}
    fun onProjectOpen(context: ExtensionContext, projectDir: File) {}
    fun onProjectClose(context: ExtensionContext) {}
    fun onBuildStart(context: ExtensionContext, tasks: List<String>) {}
    fun onBuildFinish(context: ExtensionContext, success: Boolean) {}
    fun onFileOpen(context: ExtensionContext, file: File) {}
    fun onFileSave(context: ExtensionContext, file: File) {}
    fun onShutdown(context: ExtensionContext) {}
}

/**
 * The API surface extensions get. Extensions NEVER touch core internals directly —
 * they go through these interfaces only.
 */
interface ExtensionContext {
    val androidContext: Context

    /** Editor API */
    fun currentFile(): File?
    fun openFile(file: File)
    fun insertText(text: String)
    fun selectedText(): String?
    fun registerSyntaxHighlighter(languageId: String, keywords: List<String>, lineComment: String?)
    fun registerAutocompleteProvider(languageId: String, words: List<String>)

    /** Project API */
    fun projectDir(): File?
    fun readFile(path: String): String?
    fun writeFile(path: String, content: String): Boolean
    fun listFiles(path: String, maxDepth: Int): List<File>

    /** UI API */
    fun showToast(message: String)
    fun notify(message: String, isError: Boolean)

    /** Terminal API (requires "terminal" permission) */
    fun runCommand(command: String, onLine: (String) -> Unit, onExit: (Int) -> Unit)

    /** Storage API — extension-private dirs (requires "storage" permission) */
    fun dataDir(): File
    fun cacheDir(): File

    /** Notification API */
    fun progress(title: String, fraction: Float)
}

/** A loaded, running extension instance with its error state. */
class LoadedExtension(
    val manifest: ExtensionManifest,
    val instance: MobileStudioExtension?,
    val context: ExtensionContext?
) {
    var crashed: Throwable? = null
    fun isAlive(): Boolean = instance != null && crashed == null
}
