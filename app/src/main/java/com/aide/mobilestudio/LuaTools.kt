package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.nio.file.Files

/**
 * Exposes the APK-packaged native Lua 5.4 interpreter (`lua`) and bytecode
 * compiler (`luac`) as shell commands.
 *
 * CMake builds them as executables and POST_BUILD copies them to
 * jniLibs/arm64-v8a/liblua.so / libluac.so so the Android installer extracts
 * them into [android.content.ApplicationInfo.nativeLibraryDir] (jniLibs
 * naming: only `lib*.so` files are extracted). This object publishes the
 * user-facing command names in a symlink farm under
 * `files/toolchain/lua/bin`, which is added to the shell PATH — the exec
 * chain (LD_PRELOAD hook -> native_tool_shim -> linker64) runs the ELF like
 * any other toolchain binary (see exec_redirect.c `is_native_build_tool`).
 */
object LuaTools {

    private const val TAG = "LuaTools"

    fun binDir(context: Context): File = File(context.filesDir, "toolchain/lua/bin")

    fun luaBinary(context: Context): File = File(binDir(context), "lua")

    /**
     * (Re)creates the `lua`/`luac` symlinks pointing at the extracted native
     * libraries. Idempotent; falls back to a copy when symlinking is not
     * possible. Returns the bin directory (created even when the binaries are
     * absent, e.g. on non-arm64 builds).
     */
    @JvmStatic
    fun sync(context: Context): File {
        val bin = binDir(context)
        runCatching { bin.mkdirs() }
        val libDir = context.applicationInfo.nativeLibraryDir
        for ((lib, name) in listOf("liblua.so" to "lua", "libluac.so" to "luac")) {
            val src = File(libDir, lib)
            if (!src.isFile) continue
            val dst = File(bin, name)
            try {
                val link = dst.toPath()
                if (Files.isSymbolicLink(link)) {
                    if (Files.readSymbolicLink(link).toString() == src.absolutePath) continue
                    Files.delete(link)
                } else if (dst.exists()) {
                    dst.delete()
                }
                Files.createSymbolicLink(link, src.toPath())
            } catch (e: Exception) {
                Log.w(TAG, "symlink $name failed, copying instead", e)
                runCatching {
                    src.copyTo(dst, overwrite = true)
                    dst.setExecutable(true, false)
                }
            }
        }
        return bin
    }
}
