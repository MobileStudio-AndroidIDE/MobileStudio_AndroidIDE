package com.aide.mobilestudio

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * NativeToolWrappers — repairs AGP's expected SDK layout
 * (<sdk>/cmake/<version>/bin/{cmake,ninja}) for the on-device Android build.
 *
 * Why: Android denies execve() of ELFs that live in the app-data directory
 * (the very failure native_tool_shim.sh reports as "inaccessible or not
 * found" / "Permission denied"). /system/bin/linker64 CAN load them, so the
 * file AGP execs is replaced by a small shell wrapper:
 *
 *     #!/system/bin/sh
 *     REAL='<real arm64 elf>'
 *     export CMAKE_ROOT=...   (when <base>/share/cmake-* exists)
 *     exec /system/bin/linker64 "$REAL" "$@"
 *
 * Symlinks are deliberately NOT used: a symlink still ends in a direct
 * execve() of the app-data ELF and fails identically (a previous
 * symlink-repair pass on-device even produced self-referential loops).
 *
 * Real binaries are discovered by SCANNING the toolchain tree (no hardcoded
 * tool locations): ELF magic + a `--version` probe executed directly and,
 * if that fails, through /system/bin/linker64.
 */
object NativeToolWrappers {
    const val DEFAULT_LINKER64 = "/system/bin/linker64"
    private const val WRAPPER_MARKER = "# MobileStudio generated linker64 wrapper"

    /** Directory names pruned while scanning toolchainRoot for real ELFs. */
    private val PRUNED_DIRS = setOf(
        "sdk", "ndk", "llvm", "jdk", "gradle", ".gradle",
        "platforms", "platform-tools", "build-tools", "sources",
        "system-images", "emulator"
    )

    /** Runs a probe command; true when it exits 0 within the timeout. */
    fun interface ExecTester {
        fun run(command: List<String>): Boolean
    }

    /** On-device default tester: ProcessBuilder + 10s timeout. */
    val processExecTester = ExecTester { cmd ->
        try {
            val builder = ProcessBuilder(cmd).redirectErrorStream(true)
            probeEnvironment(cmd).forEach { (key, value) -> builder.environment()[key] = value }
            val process = builder.start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                false
            } else {
                process.inputStream.readBytes() // drain (probe output is tiny)
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Environment needed to execute a probe command on the device:
     *  - LD_LIBRARY_PATH: the tool's <root>/lib plus the toolchain's
     *    LLVM lib dir (libc++_shared.so — DT_NEEDED of the ported
     *    cmake/ninja ELFs), then the caller's inherited value.
     *  - CMAKE_ROOT: <root>/share/cmake-* when present, because under
     *    linker64 /proc/self/exe points at the linker and cmake cannot
     *    locate its module tree by itself.
     * Returns an empty map when nothing applies (tests rely on this).
     */
    fun probeEnvironment(cmd: List<String>): Map<String, String> {
        val libDirs = LinkedHashSet<String>()
        var cmakeRoot: String? = null
        for (arg in cmd) {
            if (!arg.startsWith("/") || arg.contains("=")) continue
            val file = File(arg)
            if (!file.isFile) continue
            file.parentFile?.parentFile?.let { base ->
                File(base, "lib").takeIf { it.isDirectory }?.let { libDirs += it.absolutePath }
                File(base, "share").listFiles()
                    ?.firstOrNull { it.isDirectory && it.name.startsWith("cmake-") }
                    ?.let { cmakeRoot = cmakeRoot ?: it.absolutePath }
            }
            val path = file.absolutePath
            val idx = path.indexOf("/toolchain/")
            if (idx >= 0) {
                File(path.substring(0, idx + "/toolchain".length), "llvm/lib")
                    .takeIf { it.isDirectory }
                    ?.let { libDirs += it.absolutePath }
            }
        }
        val env = LinkedHashMap<String, String>()
        if (libDirs.isNotEmpty()) {
            val inherited = System.getenv("LD_LIBRARY_PATH")
            env["LD_LIBRARY_PATH"] =
                (libDirs + listOfNotNull(inherited?.takeIf { it.isNotBlank() })).joinToString(":")
        }
        cmakeRoot?.let { env["CMAKE_ROOT"] = it }
        return env
    }

    /** True when [file] starts with the ELF magic (symlinks resolved by caller). */
    fun isElf(file: File): Boolean {
        if (!file.isFile || file.length() < 64) return false
        val hdr = ByteArray(4)
        val read = runCatching { file.inputStream().use { it.read(hdr) } }.getOrDefault(0)
        return read == 4 &&
            hdr[0] == 0x7f.toByte() && hdr[1] == 'E'.code.toByte() &&
            hdr[2] == 'L'.code.toByte() && hdr[3] == 'F'.code.toByte()
    }

    /**
     * True when [file] is a PIE (ET_DYN, e_type=3) ELF. Only PIE binaries can be
     * launched through /system/bin/linker64; a non-PIE ET_EXEC (e_type=2) fails
     * with "has unexpected e_type: 2".
     */
    fun isPieElf(file: File): Boolean {
        if (!file.isFile || file.length() < 18) return false
        val eType = runCatching {
            file.inputStream().use { input ->
                val hdr = ByteArray(18)
                var read = 0
                while (read < hdr.size) {
                    val n = input.read(hdr, read, hdr.size - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < 18) return false
                if (!(hdr[0] == 0x7f.toByte() && hdr[1] == 'E'.code.toByte() &&
                        hdr[2] == 'L'.code.toByte() && hdr[3] == 'F'.code.toByte())) return false
                (hdr[16].toInt() and 0xff) or ((hdr[17].toInt() and 0xff) shl 8)
            }
        }.getOrDefault(-1)
        return eType == 3
    }

    private fun isSymbolicLink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /**
     * Scan [toolchainRoot] for real tool ELFs named [toolName].
     * Skips: symlinks, non-ELF files (wrapper scripts, junk), anything under
     * pruned directories (the SDK tree AGP owns, NDK, JDK, ...), and anything
     * inside the SDK cmake layout itself. Deterministic priority:
     * canonical runtime installs first, then shallower/shorter paths.
     */
    fun scanToolCandidates(toolchainRoot: File, toolName: String): List<File> {
        if (!toolchainRoot.isDirectory) return emptyList()
        val out = ArrayList<File>()
        var visited = 0
        toolchainRoot.walkTopDown()
            .onEnter { it.name !in PRUNED_DIRS && ++visited <= 20_000 }
            .forEach { f ->
                if (f.isFile &&
                    f.name == toolName &&
                    !isSymbolicLink(f) &&
                    isElf(f)
                ) {
                    out += f
                }
            }
        return out.sortedWith(
            compareBy<File> { if ("/toolchain/cmake/" in "/${it.path}" || it.parentFile?.parentFile?.name == "cmake") 0 else 1 }
                .thenBy { it.path.length }
                .thenBy { it.path }
        )
    }

    /**
     * Probe candidate [toolName] ELFs (req: run `--version` directly and via
     * linker64; a linker64-only success means an Android exec restriction).
     * Returns the first working (binary, mode) pair: mode is "direct" or
     * "linker64". Null when no candidate executes.
     */
    fun findExecutableTool(
        toolchainRoot: File,
        toolName: String,
        tester: ExecTester = processExecTester,
        linker64: String = DEFAULT_LINKER64
    ): Pair<File, String>? =
        scanToolCandidates(toolchainRoot, toolName).firstNotNullOfOrNull { file ->
            verifyTool(file, tester, linker64)?.let { file to it }
        }

    /** Returns "direct", "linker64" or null (neither way runs the tool). */
    fun verifyTool(
        file: File,
        tester: ExecTester = processExecTester,
        linker64: String = DEFAULT_LINKER64
    ): String? {
        if (tester.run(listOf(file.absolutePath, "--version"))) return "direct"
        if (tester.run(listOf(linker64, file.absolutePath, "--version"))) return "linker64"
        return null
    }

    /**
     * Resolves the best runnable port for [toolName], most-preferred first:
     *   1. <filesDir>/tools/<tool>/bin/<tool>  (StudioShellTools, user-installed arm64)
     *   2. the CMake runtime / arm64-tools tree under [toolchainRoot]
     * Returns (file, "direct"|"linker64") or null. Explicit known-good locations
     * are probed before the tree scan so a validated tool is never shadowed by
     * a same-named binary elsewhere (e.g. the SDK's x86-64 Linux ninja).
     */
    fun resolvePortedTool(
        filesDir: File,
        toolchainRoot: File,
        toolName: String,
        tester: ExecTester = processExecTester,
        linker64: String = DEFAULT_LINKER64
    ): Pair<File, String>? {
        val studioTool = File(filesDir, "tools/$toolName/bin/$toolName")
        if (studioTool.isFile && isElf(studioTool)) {
            // Known user-installed install: trust it even when the probe cannot
            // run it in-process (the shim adds its lib/ to LD_LIBRARY_PATH and
            // execs it through linker64 anyway).
            return studioTool to (verifyTool(studioTool, tester, linker64) ?: "linker64")
        }
        return findExecutableTool(toolchainRoot, toolName, tester, linker64)
    }

    private fun shellQuote(value: String): String = value.replace("'", "'\\''")

    /**
     * Wrapper script for [realElf]. CMAKE_ROOT is exported because under
     * linker64 /proc/self/exe points at the linker, so cmake cannot discover
     * its share/cmake-<ver> tree by itself.
     */
    fun wrapperScript(realElf: File, linker64: String = DEFAULT_LINKER64): String {
        val real = realElf.absolutePath
        val base = realElf.parentFile?.parentFile // <root>/bin/cmake -> <root>
        val lines = mutableListOf(
            "#!/system/bin/sh",
            WRAPPER_MARKER,
            "# App-data ELFs cannot be execve()d directly on Android; the real",
            "# binary runs through the Android linker. Regenerated before builds.",
            "REAL='${shellQuote(real)}'"
        )
        val share = base?.let { File(it, "share") }?.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("cmake-") }
        if (share != null) {
            lines += "export CMAKE_ROOT='${shellQuote(share.absolutePath)}'"
        }
        // libc++_shared.so (DT_NEEDED of the ported cmake/ninja) lives either
        // next to the install (<root>/lib) or in the toolchain LLVM tree.
        val libDirs = mutableListOf<String>()
        base?.let { File(it, "lib") }
            ?.takeIf { it.isDirectory }
            ?.let { libDirs += it.absolutePath }
        generateSequence(realElf) { it.parentFile }
            .firstOrNull { it.name == "toolchain" }
            ?.let { File(it, "llvm/lib") }
            ?.takeIf { it.isDirectory }
            ?.let { libDirs += it.absolutePath }
        if (libDirs.isNotEmpty()) {
            lines += "export LD_LIBRARY_PATH='${shellQuote(libDirs.joinToString(":"))}'\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
        }
        lines += "exec '${shellQuote(linker64)}' \"\$REAL\" \"\$@\""
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    private fun readHead(file: File, bytes: Int): String = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(bytes)
            var read = 0
            while (read < bytes) {
                val n = input.read(buf, read, bytes - read)
                if (n <= 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        }
    }.getOrDefault("")

    /**
     * Replace [dest] (regular file, wrapper or symlink — dangling ones too)
     * with a linker64 wrapper for [realElf]. Idempotent: returns true only
     * when the file content changed. Always ensures the executable bit.
     */
    fun writeWrapper(dest: File, realElf: File, linker64: String = DEFAULT_LINKER64): Boolean {
        val script = wrapperScript(realElf, linker64)
        if (dest.isFile && !isSymbolicLink(dest)) {
            val head = readHead(dest, 512)
            if (WRAPPER_MARKER in head && "REAL='${shellQuote(realElf.absolutePath)}'" in head) {
                if (!dest.canExecute()) dest.setExecutable(true, false)
                return false // already the correct wrapper
            }
        }
        dest.parentFile?.mkdirs()
        if (dest.exists() || isSymbolicLink(dest)) dest.delete()
        dest.writeText(script)
        dest.setExecutable(true, false)
        return true
    }

    /**
     * Make AGP's <sdk>/cmake/<ver>/bin/{cmake,ninja} executable via linker64
     * wrappers backed by real arm64 ELFs found under [toolchainRoot].
     * Returns human-readable log lines (build output diagnostics).
     */
    fun ensureSdkWrappers(
        sdkDir: File,
        toolchainRoot: File,
        filesDir: File? = null,
        tester: ExecTester = processExecTester,
        linker64: String = DEFAULT_LINKER64
    ): List<String> {
        val lines = mutableListOf<String>()
        val sdkCmakeRoot = File(sdkDir, "cmake")
        if (!sdkCmakeRoot.isDirectory) {
            lines += "> [CMake] no SDK cmake folder at ${sdkCmakeRoot.absolutePath} — nothing to repair"
            return lines
        }
        // Prefer the user-installed StudioShellTools tool when available, else
        // scan the toolchain tree (CmakeRuntimeManager / Arm64ToolsDownloader).
        val realCmake = filesDir?.let { resolvePortedTool(it, toolchainRoot, "cmake", tester, linker64) }
            ?: findExecutableTool(toolchainRoot, "cmake", tester, linker64)
        val realNinja = filesDir?.let { resolvePortedTool(it, toolchainRoot, "ninja", tester, linker64) }
            ?: findExecutableTool(toolchainRoot, "ninja", tester, linker64)
        lines += realCmake?.let {
            "> [CMake] real cmake: ${it.first.absolutePath} (exec: ${it.second})"
        } ?: "> [CMake] WARN: no executable arm64 cmake found under ${toolchainRoot.absolutePath} " +
            "(download the CMake runtime first)"
        lines += realNinja?.let {
            "> [CMake] real ninja: ${it.first.absolutePath} (exec: ${it.second})"
        } ?: "> [CMake] WARN: no executable arm64 ninja found under ${toolchainRoot.absolutePath}"

        val versionDirs = sdkCmakeRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?: emptyList()
        for (versionDir in versionDirs) {
            for (tool in listOf("cmake", "ninja")) {
                val dest = File(File(versionDir, "bin"), tool)
                val real = if (tool == "cmake") realCmake?.first else realNinja?.first
                if (real == null) {
                    if (dest.exists() || isSymbolicLink(dest)) {
                        lines += "> [CMake] WARN: ${dest.absolutePath}: no real $tool ELF — left untouched"
                    }
                    continue
                }
                val changed = writeWrapper(dest, real, linker64)
                lines += if (changed) {
                    "> [CMake] wrapper written: ${dest.absolutePath} -> ${real.absolutePath}"
                } else {
                    "> [CMake] wrapper ok: ${dest.absolutePath}"
                }
            }
        }
        return lines
    }
}
