package com.aide.mobilestudio

import android.app.Activity
import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * StorageHelper.kt (NDK 강화 + Play Store ?환)
 *
 * 변??항:
 *   - ?? ??소 권한 ?전 ?거 (MANAGE_EXTERNAL_STORAGE 불필??
 *   - 모든 ?로?트 ?이?? ???? filesDir ????? *   - NativeBridge ?용: ?스???유 공간 ?인, ?일 복사
 *   - Play Store ?책: ?험 권한 0??용
 */
object StorageHelper {

    private const val TAG = "StorageHelper"
    const val ROOT_DIR_NAME = "MobileStudio"

    // ?? ?로?트 루트 ?????????????????????????????????????????????????????

    /**
     * ???용 ?? ??소: /data/user/0/<pkg>/files/MobileStudio/
     * ???? ??소 권한 불필?? Play Store ?책 ?전 준??
     */
    fun projectsRoot(context: Context): File =
        preferredExternalProjectsRoot(context).also { it.mkdirs() }
            .takeIf { it.exists() && it.canWrite() }
            ?: legacyExternalProjectsRoot().also { it.mkdirs() }
                .takeIf { it.exists() && it.canWrite() }
            ?: internalProjectsRoot(context)

    fun internalProjectsRoot(context: Context): File =
        File(context.filesDir, ROOT_DIR_NAME).also { it.mkdirs() }

    fun preferredExternalProjectsRoot(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT_DIR_NAME)

    fun legacyExternalProjectsRoot(): File =
        File(Environment.getExternalStorageDirectory(), ROOT_DIR_NAME)

    fun projectDir(context: Context, name: String): File =
        File(projectsRoot(context), name).also { it.mkdirs() }

    private val PROJECT_MARKERS = listOf(
        "build.gradle", "build.gradle.kts",
        "settings.gradle", "settings.gradle.kts",
        "pom.xml", "CMakeLists.txt", "AndroidManifest.xml",
        "extension.json", "package.json", ".git", "src", "app",
        // C/C++ / Windows .exe projects (root main.c + README.md)
        "main.c", "main.cpp", "main.cc",
        "Makefile", "meson.build",
    )

    /** 프로젝트로 인식할 수 있는 마커가 있는 디렉터리만 프로젝트로 취급한다.
     *  (없으면 빌드 출력 디렉터리예: `packages` 가 "최근 프로젝트"에 섞여 나온다) */
    fun looksLikeProject(dir: File): Boolean =
        PROJECT_MARKERS.any { marker ->
            val f = File(dir, marker)
            (f.isFile || (marker == ".git" && f.isDirectory)) && f.exists()
        }

    fun listProjects(context: Context): List<File> =
        listOf(projectsRoot(context), internalProjectsRoot(context))
            .flatMap { it.listFiles { f -> f.isDirectory }?.toList().orEmpty() }
            .filter { looksLikeProject(it) }
            .distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
            .sortedByDescending { it.lastModified() }

    // =========================================================================
    // 최근 프로젝트 / 최근 파일 (SharedPreferences 기반)
    // =========================================================================

    private const val PREF_RECENTS = "recents"
    private const val KEY_RECENT_PROJECTS = "recent_projects"
    private const val KEY_RECENT_FILES = "recent_files"
    private const val MAX_RECENTS = 12

    fun recentFileKey(): String = KEY_RECENT_FILES

    fun getRecentProjects(context: Context): List<String> =
        context.getSharedPreferences(PREF_RECENTS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_PROJECTS, "")?.split('\n')
            ?.filter { it.isNotBlank() } ?: emptyList()

    fun pushRecentProject(context: Context, projectPath: String) {
        val list = (listOf(projectPath) + getRecentProjects(context).filter { it != projectPath })
            .distinct().take(MAX_RECENTS)
        context.getSharedPreferences(PREF_RECENTS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENT_PROJECTS, list.joinToString("\n")).apply()
    }

    fun removeRecentProject(context: Context, projectPath: String) {
        val list = getRecentProjects(context).filter { it != projectPath }
        context.getSharedPreferences(PREF_RECENTS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENT_PROJECTS, list.joinToString("\n")).apply()
    }

    fun getRecentFiles(context: Context): List<String> =
        context.getSharedPreferences(PREF_RECENTS, Context.MODE_PRIVATE)
            .getString(KEY_RECENT_FILES, "")?.split('\n')
            ?.filter { it.isNotBlank() } ?: emptyList()

    fun pushRecentFile(context: Context, filePath: String) {
        val list = (listOf(filePath) + getRecentFiles(context).filter { it != filePath })
            .distinct().take(MAX_RECENTS)
        context.getSharedPreferences(PREF_RECENTS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENT_FILES, list.joinToString("\n")).apply()
    }

    fun removeRecentFile(context: Context, filePath: String) {
        val list = getRecentFiles(context).filter { it != filePath }
        context.getSharedPreferences(PREF_RECENTS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENT_FILES, list.joinToString("\n")).apply()
    }

    // ?? 권한 ?퍼 (?험 권한 0? ?????????????????????????????????????????

    /** ?? ??소 = ?? ?근 가????true 고정 */
    fun hasStoragePermission(context: Context): Boolean = true

    /** ?? ??소 모드?서???무것도 ?청?? ?음 */
    fun requestStoragePermission(activity: Activity, requestCode: Int = 1001) {
        Log.d(TAG, "requestStoragePermission: 저장소 모드 권한 요청 시작")
    }

    // ?? ?유 공간 ?인 ????????????????????????????????????????????????????

    /**
     * ???? ??소 ?유 공간 (MB).
     * NativeBridge (C ?벨 statvfs) ?선, fallback StatFs.
     */
    fun getFreeDiskMb(context: Context): Long =
        NativeBridge.getFreeDiskMb(context)

    fun checkDiskSpace(context: Context, requiredMb: Long): Boolean {
        val free = getFreeDiskMb(context)
        Log.d(TAG, "disk: ${free}MB free, need ${requiredMb}MB")
        return free >= requiredMb
    }

    // ?? 경로 ?시 ?????????????????????????????????????????????????????????

    fun displayPath(context: Context, projectName: String): String =
        "${projectsRoot(context).absolutePath}/$projectName"

    // ?? 백업 (?? ??소가 ?을 ?만, 권한 ?어???일?에???근 가?? ??

    fun getExternalBackupDir(): File? {
        val ext = Environment.getExternalStorageDirectory()
        return if (ext != null && ext.canWrite())
            File(ext, ROOT_DIR_NAME).also { if (!it.exists()) it.mkdirs() }
        else null
    }

    /**
     * ?로?트??? ??소??보?기 (?택??.
     * NativeBridge ???일 복사 기능 ?용.
     */
    fun exportProjectToExternal(context: Context, projectName: String): Boolean {
        val src  = projectDir(context, projectName)
        val extDir = getExternalBackupDir() ?: return false
        val dst  = File(extDir, projectName)
        return try {
            src.copyRecursively(dst, overwrite = true)
            true
        } catch (e: Exception) {
            Log.e(TAG, "export failed: $e")
            false
        }
    }
}
