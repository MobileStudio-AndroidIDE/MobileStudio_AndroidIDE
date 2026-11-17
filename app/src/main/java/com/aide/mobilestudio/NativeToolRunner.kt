package com.aide.mobilestudio

import android.util.Log
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * NativeToolRunner - arm64-v8a 네이티브 실행파일 (java, clang, clangd, clang-tidy,
 * lld, cling 등)을 안전하게 실행하기 위한 헬퍼.
 *
 * Android에는 /system/bin/sh가 네이티브 ELF 실행파일의 인터프리터(loader)를
 * 자동으로 처리해주지 않을 수 있으므로, /system/bin/linker64를 명시적으로
 * 인터프리터로 사용해 실행하고, LD_LIBRARY_PATH로 필요한 공유 라이브러리
 * 경로를 제공합니다.
 *
 * 기능:
 *  1. findTool(toolName): 설치된 실행파일 경로를 후보 목록에서 탐색
 *  2. isArm64Elf(file): ELF 헤더로 arm64 실행파일 판별
 *  3. runTool(tool, args): linker64 또는 직접 실행, 출력 결과 반환
 *  4. runToolVersion(toolName): <tool> --version 실행
 */
object NativeToolRunner {
    private const val TAG = "NativeToolRunner"

    /** ELF constants */
    private const val ELF_MAGIC0 = 0x7f.toByte()
    private const val EI_CLASS = 4   // 1 = 32-bit, 2 = 64-bit
    private const val EI_DATA = 5    // 1 = LE, 2 = BE
    private const val PT_DYNAMIC = 2
    private const val DT_NEEDED = 1
    private const val EM_AARCH64 = 183

    /** /system/bin/linker64 경로 */
    private const val LINKER64 = "/system/bin/linker64"

    /** 툴별 후보 경로 (arm64-v8a 우선) */
    private val TOOL_PATHS = mapOf(
        "java" to listOf(
            "/system/bin/java",
            "/system/bin/java64",
            "${System.getProperty("user.dir", "/data/data/com.aide.mobilestudio")}/toolchain/java/bin/java",
            "/data/local/aarch64-linux-android/bin/java",
            "/opt/java/bin/java",
            "/usr/lib/jvm/bin/java",
        ),
        "clang" to listOf(
            "/system/bin/clang",
            "${System.getProperty("user.dir", "/data/data/com.aide.mobilestudio")}/toolchain/clang/bin/clang",
            "/data/local/clang/bin/clang",
            "/opt/clang/bin/clang",
        ),
        "clangd" to listOf(
            "/system/bin/clangd",
            "${System.getProperty("user.dir", "/data/data/com.aide.mobilestudio")}/toolchain/clang-tools/clangd",
            "/data/local/clangd/bin/clangd",
            "/opt/clangd/bin/clangd",
        ),
        "clang-tidy" to listOf(
            "/system/bin/clang-tidy",
            "${System.getProperty("user.dir", "/data/data/com.aide.mobilestudio")}/toolchain/clang-tools/clang-tidy",
            "/data/local/clang-tidy/bin/clang-tidy",
            "/opt/clang-tidy/bin/clang-tidy",
        ),
        "lld" to listOf(
            "/system/bin/lld",
            "${System.getProperty("user.dir", "/data/data/com.aide.mobilestudio")}/toolchain/clang-tools/lld",
            "/data/local/lld/bin/lld",
            "/opt/lld/bin/lld",
        ),
        "cling" to listOf(
            "/system/bin/cling",
            "${System.getProperty("user.dir", "/data/data/com.aide.mobilestudio")}/toolchain/clang-tools/cling",
            "/data/local/cling/bin/cling",
            "/opt/cling/bin/cling",
        ),
    )

    /** 앱 내부 clang 도구 설치 디렉토리 */
    fun clangToolsDir(): File =
        File(System.getProperty("user.dir", "/data/data/com.aide.mobilestudio"), "toolchain/clang-tools").apply { mkdirs() }

    /**
     * 툴 이름으로 설치된 arm64 실행파일 경로를 찾습니다.
     * 클랑 툴은 앱 내부 설치 디렉토리(clang-tools)에서도 찾습니다.
     */
    fun findTool(toolName: String): File? {
        val candidates = TOOL_PATHS[toolName] ?: emptyList()
        // 클랑 도구는 앱 내부 설치 경로 우선
        val clangTools = clangToolsDir()
        val appTool = File(clangTools, toolName)
        if (appTool.isFile && appTool.canExecute() && isArm64Elf(appTool)) {
            Log.d(TAG, "Found $toolName at ${appTool.absolutePath}")
            return appTool
        }
        for (path in candidates) {
            val f = File(path)
            if (f.isFile && f.canExecute() && isArm64Elf(f)) {
                Log.d(TAG, "Found $toolName at $path")
                return f
            }
        }
        Log.w(TAG, "No arm64 binary found for tool: $toolName")
        return null
    }

    /**
     * 파일이 arm64 ELF 실행파일인지 검사합니다.
     */
    private fun isArm64Elf(file: File): Boolean {
        if (!file.isFile || file.length() < 20) return false
        val hdr = ByteArray(20)
        val nread = runCatching { file.inputStream().use { it.read(hdr) } }.getOrDefault(0)
        if (nread < 20) return false
        if (hdr[0] != ELF_MAGIC0 || hdr[1] != 'E'.toByte() ||
            hdr[2] != 'L'.toByte() || hdr[3] != 'F'.toByte()
        ) return false
        val is64 = hdr[EI_CLASS].toInt() == 2
        if (!is64) return false
        val le = hdr[EI_DATA].toInt() == 1
        val machine = if (le) {
            (hdr[18].toInt() and 0xff) or ((hdr[19].toInt() and 0xff) shl 8)
        } else {
            ((hdr[18].toInt() and 0xff) shl 8) or (hdr[19].toInt() and 0xff)
        }
        return machine == EM_AARCH64
    }

    /**
     * ELF 동적 섹션에서 DT_NEEDED(필요 공유 라이브러리) 목록을 추출합니다.
     * 파싱 실패 시 빈 목록 반환.
     */
    fun resolveNeededLibraries(executable: File): List<String> {
        val needed = mutableListOf<String>()
        if (!executable.isFile) return needed

        try {
            File(executable.absolutePath).inputStream().use { input ->
                val dis = DataInputStream(input)
                val ident = ByteArray(16)
                dis.readFully(ident)
                if (ident[0] != ELF_MAGIC0 || ident[1] != 'E'.toByte() ||
                    ident[2] != 'L'.toByte() || ident[3] != 'F'.toByte()
                ) return needed

                val is64 = ident[EI_CLASS].toInt() == 2
                val le = ident[EI_DATA].toInt() == 1
                val bo = if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN

                // ELF 헤더 스킵: e_type(2) e_machine(2) e_version(4)
                dis.skipBytes(8)
                // e_entry (4 또는 8)
                if (is64) dis.skipBytes(8) else dis.skipBytes(4)
                // e_phoff (4 또는 8)
                val phoff = if (is64) dis.readLong() else dis.readInt().toInt()
                // e_shoff(4) e_flags(4) e_ehsize(2)
                dis.skipBytes(10)
                val phentsize = dis.readShort().toInt()
                val phnum = dis.readShort().toInt()

                for (i in 0 until phnum) {
                    dis.skipBytes(phoff.toInt() + i * phentsize)
                    val pType = dis.readInt()
                    if (pType != PT_DYNAMIC) continue
                    // p_offset (4 또는 8)
                    val pOffset = if (is64) dis.readInt().toLong() else dis.readInt().toLong()
                    // p_vaddr, p_paddr, p_filesz, p_memsz, p_flags, p_align 스킵
                    val skipBytes = if (is64) 40 else 24
                    dis.skipBytes(skipBytes)

                    // 동적 엔트리 읽기 (DT_NULL 만날 때까지)
                    dis.skipBytes(pOffset.toInt())
                    while (true) {
                        val tag = if (is64) dis.readLong().toInt() else dis.readInt()
                        if (tag == 0) break // DT_NULL
                        val val_ = if (is64) dis.readLong() else dis.readInt().toLong()
                        if (tag == DT_NEEDED) {
                            // val_은 문자열 테이블 오프셋. 이름만 대략 기록.
                            needed.add("lib_$val_.so")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveNeededLibraries failed: ${e.message}")
        }

        return needed
    }

    /**
     * 지정된 실행파일을 /system/bin/linker64를 인터프리터로 사용해 실행합니다.
     * LD_LIBRARY_PATH에 실행파일 디렉토리와 시스템 라이브러리 경로를 설정합니다.
     */
    fun runTool(
        toolName: String,
        args: List<String>,
        timeoutSeconds: Long = 30,
    ): String {
        val tool = findTool(toolName) ?: return "Error: $toolName binary not found"

        val execDir = tool.parentFile
        val libDirs = mutableListOf<String>()
        execDir?.let { libDirs.add(it.absolutePath) }
        listOf("/system/lib64", "/vendor/lib64", "/data/local/lib64").forEach {
            val d = File(it)
            if (d.isDirectory) libDirs.add(it)
        }

        val env = mutableMapOf<String, String>()
        env["LD_LIBRARY_PATH"] = libDirs.joinToString(":")

        // linker64가 존재하면 사용, 없으면 직접 실행
        val linker = File(LINKER64)
        val cmdArgs = mutableListOf<String>()
        if (linker.isFile) {
            cmdArgs.add(LINKER64)
        }
        cmdArgs.add(tool.absolutePath)
        cmdArgs.addAll(args)

        return runProcess(cmdArgs, env, timeoutSeconds)
    }

    /**
     * `<tool> --version`을 실행하고 출력 결과를 반환합니다.
     */
    fun runToolVersion(toolName: String): String {
        return runTool(toolName, listOf("--version"), 15)
    }

    /**
     * 프로세스를 실행하고 결합된 stdout/stderr 출력을 문자열로 반환합니다.
     */
    private fun runProcess(
        cmdArgs: List<String>,
        env: Map<String, String>,
        timeoutSeconds: Long,
    ): String {
        val output = StringBuilder()
        try {
            val pb = ProcessBuilder(*cmdArgs.toTypedArray())
            env.forEach { (k, v) -> pb.environment()[k] = v }
            pb.redirectErrorStream(true)
            val proc = pb.start()

            val reader = BufferedReader(InputStreamReader(proc.inputStream, java.nio.charset.StandardCharsets.UTF_8))
            var line: String?
            while (proc.isAlive || reader.ready()) {
                line = reader.readLine()
                if (line == null) break
                output.append(line).append("\n")
            }

            val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                output.append("\n[Timeout after ${timeoutSeconds}s]")
            }
        } catch (e: Exception) {
            output.append("Error: ${e.message}")
        }
        return output.toString()
    }
}
