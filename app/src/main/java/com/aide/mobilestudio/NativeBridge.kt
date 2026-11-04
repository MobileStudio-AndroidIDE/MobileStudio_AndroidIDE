package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import java.io.File

@Keep
object NativeBridge {

    private const val TAG = "NativeBridge"
    private const val LIB_NAME = "mobilestudio"

    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary(LIB_NAME)
            isLoaded = true
            Log.i(TAG, "libmobilestudio.so loaded → ABI: ${getNativeAbi()}, API: ${getAndroidApiLevel()}")
        } catch (e: UnsatisfiedLinkError) {
            isLoaded = false
            Log.e(TAG, "libmobilestudio.so 로드실패: ${e.message}")
        }
    }

    @JvmStatic external fun isNoexec(path: String): Int
    @JvmStatic fun isPathNoexec(path: String): Boolean = if (isLoaded) isNoexec(path) == 1 else false
    @JvmStatic external fun applyExecPermissions(path: String): Int
    @JvmStatic external fun chmodNative(path: String, mode: Int): Int
    @JvmStatic fun makeExecutable(file: File): Boolean {
        if (!isLoaded) return file.setExecutable(true, false)
        return chmodNative(file.absolutePath, 0b111_101_101) == 0
    }
    @JvmStatic external fun symlinkNative(target: String, link: String): Int
    @JvmStatic fun createSymlink(target: String, linkPath: String): Boolean {
        if (!isLoaded) return try { android.system.Os.symlink(target, linkPath); true } catch (e: Exception) { false }
        return symlinkNative(target, linkPath) == 0
    }
    @JvmStatic external fun openExecMemFd(path: String): Int
    @JvmStatic external fun getProcFdPath(fd: Int): String

    /**
 * memfd 실행 경로 반환.
 * 또는 /proc/self 또는 /proc/<프로세스PID> 설정:
 *   ProcessBuilder 방식에서 "self" 문자열 대신 부모fd 사용 시 ENOENT
 *   C 구현(openExecMemFd)에서 MFD_CLOEXEC 플래그 fd 생성이 경로에 영향.
     */
    @JvmStatic
    fun getMemFdExecPath(binaryFile: File): String? {
        if (!isLoaded || !binaryFile.exists()) return null
        val fd = openExecMemFd(binaryFile.absolutePath)
                if (fd < 0) { Log.w(TAG, "openExecMemFd 실패"); return null }
        val pid  = android.os.Process.myPid()
        val path = "/proc/$pid/fd/$fd"
                if (!File(path).exists()) { Log.w(TAG, "memfd 경로 탐색 불가: $path"); return null }
        Log.d(TAG, "memfd 경로: $path (fd=$fd, pid=$pid)")
        return path
    }

    @JvmStatic external fun copyBinaryToExecDir(src: String, dst: String): Int
    @JvmStatic fun copyBinary(src: File, dst: File): Boolean {
        dst.parentFile?.mkdirs()
        if (!isLoaded) { src.copyTo(dst, overwrite = true); dst.setExecutable(true, false); return dst.exists() }
        return copyBinaryToExecDir(src.absolutePath, dst.absolutePath) == 0
    }
    @JvmStatic external fun copySharedLibsNative(srcDir: String, dstDir: String): Int
    @JvmStatic fun copySharedLibs(srcDir: File, dstDir: File): Int {
        if (!isLoaded) return copySharedLibsKotlin(srcDir, dstDir)
        return copySharedLibsNative(srcDir.absolutePath, dstDir.absolutePath)
    }
    private fun copySharedLibsKotlin(src: File, dest: File): Int {
        if (!src.exists()) return 0
        var count = 0
        src.walkTopDown().filter { it.isFile && it.extension in listOf("so", "jsa") }.forEach { f ->
            val t = File(dest, f.relativeTo(src).path); t.parentFile?.mkdirs()
            f.copyTo(t, overwrite = true); t.setExecutable(true, false); count++
        }
        return count
    }
    @JvmStatic external fun getFileMtime(path: String): Long
    @JvmStatic fun isUpToDate(src: File, dst: File): Boolean {
        if (!dst.exists()) return false
        if (!isLoaded) return dst.lastModified() >= src.lastModified()
        return getFileMtime(dst.absolutePath) >= getFileMtime(src.absolutePath)
    }
    @JvmStatic external fun spawnProcess(argv: Array<String>, envp: Array<String>, cwd: String): Int
    @JvmStatic external fun readProcessOutput(): String?
    @JvmStatic external fun waitProcess(): Int
    @JvmStatic external fun killProcess()
    @JvmStatic external fun getNativeAbi(): String
    @JvmStatic external fun getAndroidApiLevel(): Int
    @JvmStatic external fun getFreeDiskBytes(path: String): Long
    @JvmStatic fun getFreeDiskMb(context: Context): Long {
        if (!isLoaded) { val s = android.os.StatFs(context.filesDir.absolutePath); return s.availableBlocksLong * s.blockSizeLong / (1024*1024) }
        return getFreeDiskBytes(context.filesDir.absolutePath) / (1024*1024)
    }
    @JvmStatic fun diagnose(context: Context): String = buildString {
        appendLine("=== MobileStudio Native Diagnostics ===")
        appendLine("Library loaded : $isLoaded")
        if (isLoaded) {
            appendLine("ABI            : ${getNativeAbi()}")
            appendLine("NDK API Level  : ${getAndroidApiLevel()}")
            appendLine("filesDir noexec: ${isNoexec(context.filesDir.absolutePath)}")
            appendLine("cacheDir noexec: ${isNoexec(context.codeCacheDir.absolutePath)}")
            appendLine("Free disk (MB) : ${getFreeDiskMb(context)}")
            val pid = android.os.Process.myPid()
            appendLine("memfd PID path : /proc/$pid/fd/ (exists=${File("/proc/$pid/fd").exists()})")
        }
        appendLine("========================================")
    }
}
