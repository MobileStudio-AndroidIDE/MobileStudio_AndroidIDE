package com.aide.mobilestudio

import android.content.Context
import java.io.File

/**
 * Android ARM64 NDK toolchain support (r27d / r28 / r29, GitHub Release "NDK-arm64").
 *
 * These NDKs contain a real aarch64 Android LLVM 24:
 *   <ndk>/toolchains/llvm/bin/{clang-24,clang,clang++,lld,ld.lld,llvm-ar,...}
 * and the aarch64 sysroot:
 *   <ndk>/toolchains/llvm/sysroot/usr/lib/aarch64-linux-android
 *
 * All native tools are bionic ELFs executed via /system/bin/linker64
 * (ExecHelper routing / libexec_redirect.so memfd redirect in the shell).
 *
 * The official linux-x86_64 NDK is NOT used as the arm64 compiler — it is kept
 * only for Google sysroot/headers layouts.
 */
object NdkToolchain {
    const val SHELL_NAME = "StudioShell 26.8.16 Daisy"
    const val SHELL_BANNER_V1 = "StudioShell v1.0.0 \"Core\""
    val SHELL_BANNER = """
        StudioShell v1.0.0 "Core"
        MobileStudio Command Environment
        Android ARM64 Development Environment
        LLVM / Clang / LLD toolchain ready

        StudioShellTools
        Optional development tools are available.
        Tools are downloaded only when needed.

        Type 'help' to see available commands.
    """.trimIndent()

    const val GITHUB_RELEASE = "NDK-arm64"
    const val GITHUB_BASE =
        "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/$GITHUB_RELEASE"

    /**
     * Official Google NDK revisions (full build numbers) rebuilt with a
     * self-built ARM64/Bionic LLVM-24. Assets are `android-ndk-<ver>-arm64.tar.zst`.
     * NEWEST FIRST — this is the bundled FALLBACK list; the dynamic catalog
     * (BuildToolCatalog) is authoritative when the network is reachable.
     */
    val GITHUB_NDK_VERSIONS = listOf(
        "29.0.14206865",
        "29.0.14033849",
        "29.0.13846066",
        "29.0.13599879",
        "29.0.13113456",
        "28.2.13676358",
        "28.1.13356709",
        "28.0.13004108",
        "28.0.12916984",
        "28.0.12674087",
        "28.0.12433566",
        "27.3.13750724",
        "27.2.12479018",
        "27.1.12297006",
        "27.0.12077973",
        "27.0.11902837",
        "27.0.11718014"
    )

    /** Default aarch64 API level used when a project has no minSdk hint. */
    const val DEFAULT_API_LEVEL = 29

    fun githubAssetName(version: String): String = "android-ndk-$version-arm64.tar.zst"

    fun githubUrl(version: String): String = "$GITHUB_BASE/${githubAssetName(version)}"

    /** Install dir: <files>/toolchain/ndk/android-ndk-<version>-arm64 (discovered by collectInstalledNdkDirs). */
    fun installDir(context: Context, version: String): File =
        File(context.filesDir, "toolchain/ndk/android-ndk-$version-arm64")

    fun isArm64NdkDir(dir: File): Boolean =
        File(dir, "toolchains/llvm/bin/clang-24").isFile ||
            File(dir, "toolchains/llvm/bin/clang").isFile ||
            (File(dir, "toolchains/llvm/sysroot/usr/lib/aarch64-linux-android").isDirectory &&
                File(dir, "toolchains/llvm/bin/lld").exists())

    /**
     * Any arm64-hosted NDK already on disk, regardless of which revision the
     * caller would have asked for. Used to avoid downloading a second copy just
     * because a newer version was requested.
     */
    fun findInstalledArm64Ndk(context: Context): File? =
        BuildToolchain.ndkDirs(context).firstOrNull { isArm64NdkDir(it) }

    /**
     * Explains what the NDK scan actually found, for the build log.
     *
     * An NDK that hosts x86_64 tools cannot run on an arm64 phone, so it is
     * correctly treated as "not installed" - but from the user's side that
     * looks exactly like the app ignoring an NDK it already has. These lines
     * make the decision readable instead of mysterious.
     */
    fun describeInstalled(context: Context): List<String> {
        val dirs = BuildToolchain.ndkDirs(context).distinctBy { it.absolutePath }
        if (dirs.isEmpty()) {
            return listOf("> [Provision] NDK: none found on disk; downloading the Android ARM64 build")
        }
        val lines = mutableListOf("> [Provision] NDK: found ${dirs.size} on disk, none usable for on-device builds")
        for (dir in dirs) {
            lines += if (isArm64NdkDir(dir)) {
                "> [Provision]   ${versionOf(dir) ?: dir.name}: usable"
            } else {
                // The stock NDK ships prebuilt/linux-x86_64, an x86_64 ELF that
                // cannot execute on this device.
                "> [Provision]   ${versionOf(dir) ?: dir.name}: x86_64 host tools (cannot run on this device)"
            }
        }
        return lines
    }

    /** Revision of an installed NDK dir: source.properties first, then the dir name. */
    fun versionOf(ndkDir: File): String? {
        runCatching {
            val props = File(ndkDir, "source.properties")
            if (props.isFile) {
                props.readLines()
                    .firstOrNull { it.trim().startsWith("Pkg.Revision") }
                    ?.substringAfter('=')
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
        }
        return Regex("android-ndk-(.+)-arm64").find(ndkDir.name)?.groupValues?.get(1)
    }

    /** <ndk>/toolchains/llvm/bin — arm64 LLVM layout (NOT prebuilt/linux-x86_64). */
    fun llvmBinDir(ndkDir: File): File = File(ndkDir, "toolchains/llvm/bin")

    /** <ndk>/toolchains/llvm — LLVM_HOME. */
    fun llvmHomeDir(ndkDir: File): File = File(ndkDir, "toolchains/llvm")

    /** <ndk>/toolchains/llvm/sysroot — aarch64 sysroot root. */
    fun sysrootDir(ndkDir: File): File = File(ndkDir, "toolchains/llvm/sysroot")

    fun clangPath(ndkDir: File): File {
        val bin = llvmBinDir(ndkDir)
        return listOf("clang-24", "clang").firstNotNullOfOrNull { f ->
            File(bin, f).takeIf { it.isFile }
        } ?: File(bin, "clang-24")
    }

    /**
     * Scans all known NDK locations (toolchain/ndk, sdk subdir ndk, active sdk ndk)
     * for ARM64-hosted LLVM NDKs.
     */
    fun discoverInstalled(context: Context): List<File> {
        val filesDir = context.filesDir
        val candidates = mutableListOf<File>()
        val toolchain = ToolchainManager(context)
        val activeNdk = toolchain.activeDir(ToolchainManager.Kind.NDK)
        activeNdk?.let { candidates.add(it) }
        File(filesDir, "toolchain/ndk").listFiles()?.forEach { if (it.isDirectory) candidates.add(it) }
        File(filesDir, "toolchain/sdk").listFiles()?.forEach { sdkDir ->
            if (sdkDir.isDirectory) {
                File(sdkDir, "ndk").listFiles()?.forEach { if (it.isDirectory) candidates.add(it) }
            }
        }
        toolchain.activeDir(ToolchainManager.Kind.SDK)?.let { sdk ->
            File(sdk, "ndk").listFiles()?.forEach { if (it.isDirectory) candidates.add(it) }
        }
        return candidates
            .map { runCatching { it.canonicalFile }.getOrDefault(it) }
            .distinctBy { it.absolutePath }
            .filter { isArm64NdkDir(it) }
            .sortedByDescending { it.name }
    }

    /** Builds `aarch64-linux-android<api>` (r27d/r28/r29 all use the same target). */
    fun targetFor(apiLevel: Int = DEFAULT_API_LEVEL): String = "aarch64-linux-android$apiLevel"

    /**
     * Adds the full NDK environment: PATH (llvm bin), CC/CXX/AR/AS/LD/NM/STRIP/RANLIB,
     * LLVM_HOME, ANDROID_NDK_ROOT/HOME, ANDROID_NDK_TARGET, ANDROID_NDK_SYSROOT.
     */
    fun applyNdkEnv(
        env: MutableMap<String, String>,
        ndkDir: File,
        apiLevel: Int = DEFAULT_API_LEVEL
    ) {
        val bin = llvmBinDir(ndkDir)
        val llvmHome = llvmHomeDir(ndkDir)
        val sysroot = sysrootDir(ndkDir)
        val target = targetFor(apiLevel)

        val oldPath = env["PATH"] ?: ""
        env["PATH"] = if (oldPath.isBlank()) bin.absolutePath
        else "$bin:$oldPath"

        env["CC"] = File(bin, "clang").takeIf { it.isFile }?.absolutePath ?: clangPath(ndkDir).absolutePath
        env["CXX"] = File(bin, "clang++").takeIf { it.isFile }?.absolutePath ?: env["CC"]!!
        env["AR"] = File(bin, "llvm-ar").takeIf { it.isFile }?.absolutePath ?: File(bin, "ar").absolutePath
        env["AS"] = env["CC"]!!
        env["LD"] = File(bin, "ld.lld").takeIf { it.isFile }?.absolutePath ?: File(bin, "lld").absolutePath
        env["NM"] = File(bin, "llvm-nm").takeIf { it.isFile }?.absolutePath ?: File(bin, "nm").absolutePath
        env["STRIP"] = File(bin, "llvm-strip").takeIf { it.isFile }?.absolutePath ?: File(bin, "strip").absolutePath
        env["RANLIB"] = File(bin, "llvm-ranlib").takeIf { it.isFile }?.absolutePath ?: File(bin, "ranlib").absolutePath
        env["LLVM_HOME"] = llvmHome.absolutePath
        env["ANDROID_NDK_ROOT"] = ndkDir.absolutePath
        env["ANDROID_NDK_HOME"] = ndkDir.absolutePath
        env["ANDROID_NDK_TARGET"] = target
        env["ANDROID_NDK_SYSROOT"] = sysroot.absolutePath

        // clang/ld.lld need their own libs (libLLVM, libc++, ...) on the loader path
        val oldLd = env["LD_LIBRARY_PATH"] ?: ""
        val libDirs = buildList {
            add(File(llvmHome, "lib").absolutePath)
            add(File(llvmHome, "lib64").absolutePath)
            add(File(llvmHome, "lib/aarch64-unknown-linux-gnu").absolutePath)
        }.filter { File(it).isDirectory }.joinToString(":")
        if (libDirs.isNotBlank()) {
            env["LD_LIBRARY_PATH"] = if (oldLd.isBlank()) libDirs else "$libDirs:$oldLd"
        }
    }

    // ---------------------------------------------------------------------
    // Shell prompt helpers
    // ---------------------------------------------------------------------

    /** Dark/medium blue for the shell prompt path (readable on dark terminals). */
    const val PROMPT_COLOR = "#4E6FD8"

    // ---- PowerShell-style command token colors (10 palette) ----
    const val CMD_COLOR_EXE = "#E3B84C"        // command/executable  (진한 노란색)
    const val CMD_COLOR_TASK = "#46C07D"       // subcommand/task/arg (진한 초록색)
    const val CMD_COLOR_FLAG = "#8E99AC"       // --flag / -x         (회색)
    const val CMD_COLOR_WHITE = "#E8ECF4"      // plain text          (흰색)
    const val CMD_COLOR_MINT = "#3FD0C6"       // path/url            (진한 민트색)
    const val CMD_COLOR_ORANGE = "#E09A4C"     // number/version      (주황)
    const val CMD_COLOR_PINK = "#E07BB0"       // assignment KEY=val  (분홍)
    const val CMD_COLOR_PURPLE = "#B389F0"     // env var $VAR        (보라)
    const val CMD_COLOR_RED = "#F05C5C"        // error/unsafe        (빨강)
    const val CMD_COLOR_OP = "#7E8AA0"         // operators | ; && >  (청회색)

    /**
     * Splits a typed command into [CommandSpan]s, each carrying its own color,
     * PowerShell-style. The leading prompt (e.g. `/path>`) is kept in [promptColor].
     */
    fun colorizeCommand(command: String, promptColor: String = PROMPT_COLOR): List<CommandSpan> {
        val spans = mutableListOf<CommandSpan>()
        val n = command.length
        var i = 0
        fun emit(start: Int, end: Int, color: String) {
            if (end > start) spans.add(CommandSpan(start, end, color))
        }
        while (i < n) {
            while (i < n && command[i].isWhitespace()) i++
            if (i >= n) break
            val start = i
            // token boundary: whitespace or unquoted operator chars
            while (i < n && !command[i].isWhitespace()) i++
            val token = command.substring(start, i)
            val color = when {
                spans.isEmpty() && token.endsWith(">") -> promptColor
                spans.isEmpty() -> CMD_COLOR_EXE
                token.startsWith("--") || token.startsWith("-") -> CMD_COLOR_FLAG
                token.contains('=') -> CMD_COLOR_PINK
                token.startsWith('$') -> CMD_COLOR_PURPLE
                token.startsWith("./") || token.startsWith('/') ||
                    token.contains('/') || token.startsWith("http") -> CMD_COLOR_MINT
                token.startsWith("rm ") || token == "rm" || token == "sudo" ||
                    token == "reboot" || token == "format" -> CMD_COLOR_RED
                token.any { it.isDigit() } && token.any { it.isLetter() } -> CMD_COLOR_ORANGE
                token == "&&" || token == "||" || token == "|" ||
                    token == ";" || token == ">" || token == ">>" || token == "<" -> CMD_COLOR_OP
                token == "assemble" || token == "build" || token == "install" ||
                    token == "clean" || token == "test" || token == "run" ||
                    token.startsWith("assemble") || token.startsWith("install") ->
                    CMD_COLOR_TASK
                else -> CMD_COLOR_WHITE
            }
            emit(start, i, color)
        }
        return spans
    }

    /**
     * Abbreviates a long path, keeping the last [keep] components:
     *   /data/.../files/projects/MyProject/app/src/main -> .../MyProject/app/src/main
     * Short paths are never abbreviated.
     */
    fun abbreviatePath(path: String, keep: Int = 4): String {
        if (path.length <= 28) return path
        val comps = path.split('/').filter { it.isNotBlank() }
        if (comps.size <= keep + 1) return path
        return ".../" + comps.takeLast(keep).joinToString("/")
    }

    /** Abbreviated prompt like `.../MyProject/app/src/main>` */
    fun promptFor(cwd: File): String = abbreviatePath(cwd.absolutePath) + ">"

    // ---------------------------------------------------------------------
    // GitHub NDK install
    // ---------------------------------------------------------------------

    /**
     * Downloads + extracts an arm64 NDK from the GitHub Release into
     * toolchain/ndk/android-<version>-arm64. Returns the NDK root dir.
     * Any official Google NDK revision is accepted — the download URL is
     * resolved from the dynamic catalog (BuildToolCatalog) with the
     * NDK-arm64 release form as fallback, so newly published revisions work
     * without shipping an updated hardcoded list.
     */
    fun installFromGithub(
        context: Context,
        version: String,
        onLine: (String) -> Unit = {}
    ): File {
        val url = runCatching {
            BuildToolCatalog.findNdk(BuildToolCatalog.fetch(context), version)?.url
        }.getOrNull() ?: githubUrl(version)
        val target = installDir(context, version)
        if (isArm64NdkDir(target)) {
            onLine("NDK $version already installed: $target")
            return target
        }
        // A different revision may already be installed. Downloading a second
        // ~1GB NDK just because the caller asked for a newer one wastes the
        // user's data and time, so reuse what is on disk and report it.
        val alreadyInstalled = findInstalledArm64Ndk(context)
        if (alreadyInstalled != null) {
            onLine("NDK ${versionOf(alreadyInstalled) ?: alreadyInstalled.name} already installed; reusing it instead of downloading $version")
            return alreadyInstalled
        }
        val tmp = File(context.cacheDir, "ndk-github-$version.tar.zst")
        val archiveName = githubAssetName(version)
        val totalBytes = DownloadCenter.probeContentLength(url)
        if (!DownloadCenter.confirm("NDK $version", url, target.absolutePath, archiveName, totalBytes)) {
            onLine("NDK download declined — the Gradle build cannot continue")
            throw RuntimeException("NDK download declined by user")
        }
        DownloadCenter.begin("NDK $version", url, target.absolutePath, archiveName, totalBytes)
        try {
            GlibcDownloader.downloadFile(
                url,
                tmp,
                onProgress = { msg -> onLine(msg) },
                onBytes = { done, total -> DownloadCenter.progress(done, total) }
            )
            onLine("Extracting ${githubAssetName(version)} ...")
            DownloadCenter.stage("Extracting $archiveName ...")
            target.parentFile?.mkdirs()
            runCatching { target.deleteRecursively() }
            target.mkdirs()
            GlibcDownloader.extractTarZst(tmp, target) { msg -> onLine(msg) }
            tmp.delete()

            // The archive may contain a single nested root (e.g. android-r27d-arm64/...)
            val nested = target.listFiles()
                ?.firstOrNull { it.isDirectory && isArm64NdkDir(it) }
            if (nested != null) {
                val temp = File(target.parentFile, "${target.name}.extract")
                runCatching { temp.deleteRecursively() }
                target.renameTo(temp)
                temp.listFiles()?.forEach { it.renameTo(File(target, it.name)) }
                runCatching { temp.deleteRecursively() }
            }

            if (!isArm64NdkDir(target)) {
                throw RuntimeException("Extracted NDK missing toolchains/llvm/bin/clang-24 (bad archive?)")
            }
            // Ensure exec bits on all native tools
            llvmBinDir(target).walkTopDown().filter { it.isFile }.forEach {
                runCatching { it.setExecutable(true, false); it.setReadable(true, false) }
            }
            onLine("NDK $version installed: $target")
            DownloadCenter.done("NDK $version installed")
            return target
        } catch (e: Exception) {
            DownloadCenter.fail("NDK download failed: ${e.message}")
            throw e
        }
    }

    // ---------------------------------------------------------------------
    // 8-step "Permission denied" diagnosis (Linux/Termux style, exact error)
    // ---------------------------------------------------------------------

    /**
     * Builds a POSIX sh script that diagnoses why [tool] cannot execute.
     * Every step prints concrete evidence; the final step prints the exact
     * error instead of a blanket "Permission denied".
     */
    fun diagnosisScript(tool: String): String = """
        TOOL=${quoteShell(tool)}
        echo "== Step 1/8: PATH entries containing the tool =="
        for d in $(echo "${'$'}PATH" | tr ':' ' '); do
          [ -n "${'$'}d" ] && [ -e "${'$'}d/${'$'}TOOL" ] && echo "found: ${'$'}d/${'$'}TOOL"
        done
        echo "== Step 2/8: which + symlink resolution =="
        F=${'$'}(command -v "${'$'}TOOL" 2>/dev/null)
        if [ -z "${'$'}F" ]; then echo "$tool: command not found"; echo "PATH=${'$'}PATH"; exit 1; fi
        echo "resolved: ${'$'}F"
        readlink -f "${'$'}F" || true
        echo "== Step 3/8: clang-24 present under LLVM_HOME =="
        echo "LLVM_HOME=${'$'}LLVM_HOME"
        ls -l "${'$'}LLVM_HOME/bin/clang-24" 2>&1 || echo "clang-24 missing"
        echo "== Step 4/8: executable bits =="
        ls -l "${'$'}F" || true
        echo "== Step 5/8: ARM64 ELF check (aarch64 = 64 64 01) =="
        head -c 20 "${'$'}F" | od -An -tx1 || true
        echo "== Step 6/8: direct /system/bin/linker64 exec =="
        /system/bin/linker64 "${'$'}F" --version 2>&1 || echo "linker64 direct failed"
        echo "== Step 7/8: LD_LIBRARY_PATH =="
        echo "${'$'}LD_LIBRARY_PATH"
        echo "== Step 8/8: exact error =="
        "${'$'}F" --version 2>&1
    """.trimIndent() + "\n"

    private fun quoteShell(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

/** A color span over [start, end) of the shell command text. */
data class CommandSpan(val start: Int, val end: Int, val color: String)