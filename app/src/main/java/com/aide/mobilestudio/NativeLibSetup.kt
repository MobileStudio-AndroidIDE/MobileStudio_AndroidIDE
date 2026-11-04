package com.aide.mobilestudio

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Maps packaged versioned libraries named lib<name>_<version>.so to
 * filesDir/usr/lib/lib<name>.so.<version> for tools that expect SONAME files.
 */
object NativeLibSetup {

    private const val TAG = "NativeLibSetup"

    @Volatile private var cachedLibDir: File? = null

    /** lib<base>_<ver>.so ?턴 ??lib<base>.so.<ver> ?매핑 */
    private val PATTERN = Regex("""^(lib.+)_(\d+)\.so$""")

    /**
     * ???작 ????번만 ?출. ??번째 ?출부?는 no-op.
     * @return LD_LIBRARY_PATH ??prepend ???렉?리
     */
    fun ensureVersionedLibs(context: Context): File {
        cachedLibDir?.let { return it }

        synchronized(this) {
            cachedLibDir?.let { return it }

            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val outDir = File(context.filesDir, "usr/lib").apply { mkdirs() }

            nativeDir.listFiles()?.forEach { f ->
                val m = PATTERN.matchEntire(f.name) ?: return@forEach
                val (base, ver) = m.destructured
                val link = File(outDir, "$base.so.$ver")

                // ?? ?일 ?깃으?만들?져 ?으?skip
                if (link.exists()) {
                    if (sameTarget(link, f)) return@forEach
                    link.delete()
                }

                try {
                    Os.symlink(f.absolutePath, link.absolutePath)
                    Log.i(TAG, "symlink: ${link.name} -> ${f.name}")
                } catch (e: ErrnoException) {
                    // symlink ?패??기기??복사??백
                    try {
                        f.copyTo(link, overwrite = true)
                        Log.w(TAG, "copied (symlink failed): ${link.name}")
                    } catch (e2: Exception) {
                        Log.e(TAG, "복사 실패: ${link.name}", e2)
                    }
                }
            }

            cachedLibDir = outDir
            return outDir
        }
    }

    fun getLibDir(context: Context): File =
        cachedLibDir ?: ensureVersionedLibs(context)

    /** LD_LIBRARY_PATH 문자?을 바로 만들?주???의 ?수 */
    fun ldLibraryPath(context: Context): String {
        val lib = getLibDir(context).absolutePath
        val native = context.applicationInfo.nativeLibraryDir
        return "$lib:$native"
    }

    private fun sameTarget(link: File, expected: File): Boolean {
        return try {
            // ?링?면 readlink, ?니?그냥 존재 ???
            val target = Os.readlink(link.absolutePath)
            target == expected.absolutePath
        } catch (_: Exception) {
            link.length() == expected.length()
        }
    }
}
