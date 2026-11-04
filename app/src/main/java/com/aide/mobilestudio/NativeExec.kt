package com.aide.mobilestudio

import android.content.Context
import android.util.Log

/**
 * libgradle_exec.so ??JNI ?터?이??
 *
 * gradle_exec.c 가 구현?는 ??가지 exec ?략:
 *   1. 직접 execve()           ??Termux java / 권한 ?린 기기
 *   2. memfd_create + fexecve  ??noexec ?티???전 ?회 (Android 커널 3.17+)
 *   3. codeCacheDir 복사 + execve ??최후 ?단
 *
 * ?용 ??
 *   val handle = NativeExec.start(javaBin, codeCacheDir, args, envp, cwd)
 *   while (true) { val line = NativeExec.readLine(handle) ?: break; ... }
 *   val code = NativeExec.waitFor(handle)
 *   NativeExec.free(handle)
 */
object NativeExec {

    private const val TAG = "NativeExec"

    init {
        System.loadLibrary("gradle_exec")
    }

    // ?? raw JNI (private) ??????????????????????????????????????????????????

    @JvmStatic
    private external fun nativeStart(
        javaBin:       String,
        codeCacheDir:  String,
        nativeLibDir:  String,
        args:          Array<String>,
        envp:          Array<String>,
        cwd:           String
    ): Long   // 0 = ?패, ???= ExecHandle ?인??

    @JvmStatic
    private external fun nativeReadLine(handle: Long): String?

    @JvmStatic
    private external fun nativeReadLineTimeout(handle: Long, timeoutMs: Int): String?

    @JvmStatic
    private external fun nativeReadChunkTimeout(handle: Long, timeoutMs: Int): String?

    @JvmStatic
    private external fun nativeWait(handle: Long): Int

    @JvmStatic
    private external fun nativeKill(handle: Long)

    @JvmStatic
    private external fun nativeFree(handle: Long)

    @JvmStatic
    private external fun nativeCheckExecStrategy(javaBin: String): String

    @JvmStatic
    private external fun nativeGetLastError(): String

    // ?? 공개 API ???????????????????????????????????????????????????????????

    /**
     * java ?로?스??작?다.
     *
     * @param javaBin      java 바이?리 ?? 경로
     * @param codeCacheDir context.codeCacheDir.absolutePath
     * @param args         ?체 argv (args[0] = java 경로)
     * @param envp         "KEY=VALUE" ?태???경변??배열
     * @param cwd          ?로?스 ?업 ?렉?리
     * @return ?들 (0?면 ?패)
     */
    fun start(
        javaBin:      String,
        codeCacheDir: String,
        nativeLibDir: String,
        args:         List<String>,
        envp:         List<String>,
        cwd:          String
    ): Long {
        Log.d(TAG, "start: java=$javaBin cwd=$cwd args=${args.size}")
        val handle = nativeStart(
            javaBin, codeCacheDir, nativeLibDir,
            args.toTypedArray(), envp.toTypedArray(), cwd
        )
        if (handle == 0L) Log.e(TAG, "nativeStart returned 0 → all exec strategies failed")
        return handle
    }

    /**
     * ?이?에??????기 (blocking).
     * EOF ?는 ?로?스 종료 ??null 반환.
     */
    fun readLine(handle: Long): String? {
        if (handle == 0L) return null
        return nativeReadLine(handle)
    }

    /**
     * ?이?에??????기 (timeout).
     * - timeout ?안 출력???으??문자??"") 반환
     * - EOF/?로?스 종료?null 반환
     */
    fun readLine(handle: Long, timeoutMs: Int): String? {
        if (handle == 0L) return null
        return nativeReadLineTimeout(handle, timeoutMs)
    }

    /**
     * ?이?에??출력 ? ?기 (timeout).
     * - timeout ?안 출력???으??문자??"") 반환
     * - EOF/?로?스 종료?null 반환
     */
    fun readChunk(handle: Long, timeoutMs: Int): String? {
        if (handle == 0L) return null
        return nativeReadChunkTimeout(handle, timeoutMs)
    }

    /**
     * ?로?스 종료 ?? 종료 코드 반환 (blocking).
     * ?로?스가 ?? 종료??경우 즉시 반환.
     */
    fun waitFor(handle: Long): Int {
        if (handle == 0L) return -1
        return nativeWait(handle)
    }

    /** ?로?스 강제 종료 (SIGKILL). */
    fun kill(handle: Long) {
        if (handle == 0L) return
        nativeKill(handle)
    }

    /**
     * ?들 ?제. 반드??waitFor() ?는 kill() ???출.
     * ?제 ??handle ???시 ?용?면 undefined behavior.
     */
    fun free(handle: Long) {
        if (handle == 0L) return
        nativeFree(handle)
    }

    /**
     * ?떤 exec ?략???용 가?한지 ?인 (?버?출력).
     * ?? "direct_execve=NO memfd_fexecve=YES"
     */
    fun checkExecStrategy(javaBin: String): String =
        nativeCheckExecStrategy(javaBin)

    fun getLastError(): String = nativeGetLastError()

    // --- Virtual Runtime Engine (libvirtual_runtime.so) native methods ---

    init {
        try {
            System.loadLibrary("virtual_runtime")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load virtual_runtime", e)
        }
    }

    @JvmStatic
    external fun nativeInitialize(runtimeDir: String, nativeLibDir: String, codeCacheDir: String): Long

    @JvmStatic
    external fun nativeShutdown(handle: Long)

    @JvmStatic
    external fun nativeExecute(
        handle: Long,
        executable: String,
        args: Array<String>,
        envVars: Array<String>,
        cwd: String,
        timeoutMs: Int,
        createProcessGroup: Boolean
    ): Long

    @JvmStatic
    external fun nativeReadStdout(processHandle: Long, timeoutMs: Int): String?

    @JvmStatic
    external fun nativeReadStderr(processHandle: Long, timeoutMs: Int): String?

    @JvmStatic
    external fun nativeWaitTimeout(processHandle: Long, timeoutMs: Int): Int

    @JvmStatic
    external fun nativeKillGroup(processHandle: Long)

    @JvmStatic
    external fun nativeFreeProcess(processHandle: Long)

    @JvmStatic
    external fun nativeResolveDependencies(handle: Long, executable: String): String?

    @JvmStatic
    external fun nativeAnalyzeElf(executable: String): String?

    @JvmStatic
    external fun nativeFindExecutable(handle: Long, name: String): String?

    @JvmStatic
    external fun nativeAddSearchPath(handle: Long, path: String)

    @JvmStatic
    external fun nativeIsArm64Elf(path: String): Int

    @JvmStatic
    external fun nativeGetSearchPaths(handle: Long): String?

    @JvmStatic
    external fun nativeVfsAddMapping(handle: Long, virtualPath: String, realPath: String): Int

    @JvmStatic
    external fun nativeVfsResolvePath(handle: Long, path: String): String?

    @JvmStatic
    external fun nativeGetLinkerInfo(executable: String): String?

    @JvmStatic
    external fun nativeCheckPathSecurity(handle: Long, path: String, root: String?): String?
}
