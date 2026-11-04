package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Windows x86_64 EXE/DLL cross-compile backend.
 *
 * Two-phase pipeline on-device (ARM64 Android, all native tools executed via
 * /system/bin/linker64 — routed automatically by [ExecHelper] for bionic ELFs
 * in app data):
 *
 *  Phase 1  clang-24 --target=x86_64-w64-mingw32 -c  (ARM64 LLVM distribution)
 *  Phase 2  lld -flavor link ...                     (never ld/ld.lld/lld-link:
 *           the LLVM bin dir has no "ld", and the symlinks are just lld)
 *
 * Link library order follows the verified on-device command:
 *   crt2.o crtbegin.o <objects> libmingw32.a libgcc.a libgcc_eh.a
 *   libmoldname.a libmingwex.a libmsvcrt.a crtend.o libkernel32.a
 * (C++ additionally pulls libstdc++.a / libwinpthread.a from the MinGW lib dir.)
 *
 * MinGW real library location is <mingwRoot>/x86_64-w64-mingw32/lib — the
 * nested "$M/x86_64-w64-mingw32/lib" path does not exist.
 */
class WindowsX64BuildBackend(private val context: Context) {

    private val TAG = "WinX64Backend"

    enum class OutputType { EXE, DLL }
    enum class Subsystem { CONSOLE, WINDOWS }

    data class BuildOptions(
        val outputType: OutputType = OutputType.EXE,
        val subsystem: Subsystem = Subsystem.CONSOLE,
        /** true = C++ (adds libstdc++.a); null = auto-detect from source extension */
        val cpp: Boolean? = null,
        val extraArgs: List<String> = emptyList(),
        val extraLinkLibs: List<String> = emptyList(),
        val keepObject: Boolean = true
    )

    val filesDir: File get() = context.filesDir

    val mingwRoot: File get() = File(filesDir, "toolchain/mingw-x86_64")
    val mingwTriple: File get() = File(mingwRoot, "x86_64-w64-mingw32")
    val mingwLibDir: File get() = File(mingwTriple, "lib")

    private val triple = "x86_64-w64-mingw32"

    // ---------------------------------------------------------------------
    // Tool locators (ARM64 LLVM distribution: bin/{clang-24,lld,llvm-readobj})
    // ---------------------------------------------------------------------

    fun clangPath(): File? {
        val roots = listOf(
            File(filesDir, "toolchain/llvm-install"),
            File(filesDir, "toolchain/llvm"),
            File(filesDir, "toolchain/llvm-18"),
            File(filesDir, "toolchain/llvm-19"),
            File(filesDir, "toolchain/llvm-20"),
            File(filesDir, "toolchain/llvm-21"),
            File(filesDir, "toolchain/llvm-22"),
            File(filesDir, "toolchain/llvm-23"),
            File(filesDir, "toolchain/llvm-24"),
            File(filesDir, "toolchain/clang"),
            File(filesDir, "toolchain/android-llvm"),
            File(filesDir, "toolchain/ndk-host"),
            File(filesDir, "toolchain/ndk"),
            File(filesDir, "ndk"),
            File(filesDir, "toolchain")
        )
        val rels = listOf(
            "bin/clang-24", "bin/clang-23", "bin/clang-22", "bin/clang-21",
            "bin/clang-20", "bin/clang-19", "bin/clang-18", "bin/clang-17",
            "bin/clang",
            "usr/bin/clang",
            "usr/clang24/bin/clang", "usr/clang23/bin/clang", "usr/clang22/bin/clang",
            "usr/clang21/bin/clang", "usr/clang20/bin/clang", "usr/clang19/bin/clang",
            "toolchains/llvm/prebuilt/linux-aarch64/bin/clang",
            "toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
        )
        for (root in roots) for (rel in rels) {
            val c = File(root, rel)
            // clang may be a 0-byte symlink on device; accept it if a real
            // clang-<major> sibling exists next to it.
            if (c.isFile && c.length() > 1_000_000) return c
        }
        for (root in roots) for (rel in rels) {
            val c = File(root, rel)
            if (c.exists() && c.length() == 0L && c.parentFile != null) {
                c.parentFile.listFiles()?.firstOrNull {
                    it.name.startsWith("clang-") && it.isFile && it.length() > 1_000_000
                }?.let { return it }
            }
        }
        val termux = File("/data/data/com.termux/files/usr/bin/clang")
        if (termux.isFile) return termux
        return null
    }

    fun lldPath(): File? {
        val bin = clangPath()?.parentFile ?: return null
        for (name in listOf("lld", "ld.lld", "lld-link")) {
            val f = File(bin, name)
            if (f.isFile && f.length() > 100_000) return f
        }
        for (name in listOf("lld", "ld.lld", "lld-link")) {
            val f = File(bin, name)
            if (f.exists()) return f
        }
        return null
    }

    fun llvmReadobjPath(): File? {
        val bin = clangPath()?.parentFile ?: return null
        val f = File(bin, "llvm-readobj")
        if (f.isFile && f.length() > 100_000) return f
        return f.takeIf { it.exists() }
    }

    /** <llvm>/lib/clang/<major> — needed because clang can't auto-find it via
     *  a relative path when started through /system/bin/linker64. */
    fun resourceDir(): File? {
        val bin = clangPath()?.parentFile ?: return null
        val libClang = File(bin.parentFile, "lib/clang")
        if (!libClang.isDirectory) return null
        return libClang.listFiles()
            ?.filter { it.isDirectory && it.name.all(Char::isDigit) }
            ?.maxByOrNull { it.name.toIntOrNull() ?: 0 }
    }

    private fun clangMajor(clang: File): String =
        Regex("clang-(\\d+)").find(clang.name)?.groupValues?.get(1)
            ?: clang.name.substringAfter("clang", "")

    fun isToolchainReady(onLine: (String) -> Unit): Boolean {
        val clang = clangPath()
        val lld = lldPath()
        val mingwOk = MingwDownloader.isValidMingw(mingwRoot)
        if (clang != null) onLine("clang: ${clang.absolutePath} (clang-${clangMajor(clang)})")
        if (lld != null) onLine("lld: ${lld.absolutePath}")
        if (mingwOk) onLine("mingw sysroot: $mingwRoot")
        if (clang == null) onLine("MISSING: clang (expect bin/clang-24 under toolchain/llvm-install)")
        if (lld == null) onLine("MISSING: lld next to clang (expect bin/lld)")
        if (!mingwOk) onLine("MISSING: mingw-w64 sysroot at $mingwRoot (tap Install first)")
        return clang != null && lld != null && mingwOk
    }

    // ---------------------------------------------------------------------
    // Build pipeline
    // ---------------------------------------------------------------------

    /**
     * Compile + link + verify. Returns the built PE file, or null on failure.
     */
    fun build(
        source: File,
        out: File,
        options: BuildOptions = BuildOptions(),
        onLine: (String) -> Unit = {}
    ): File? {
        val clang = clangPath()
        if (clang == null) { onLine("clang not found — install the LLVM toolchain first"); return null }
        val lld = lldPath()
        if (lld == null) { onLine("lld not found next to clang (${clang.parentFile})"); return null }
        if (!MingwDownloader.isValidMingw(mingwRoot)) {
            onLine("mingw-w64 sysroot not valid at $mingwRoot — tap Install first")
            return null
        }

        val workDir = source.parentFile ?: context.cacheDir
        val obj = File(workDir, source.nameWithoutExtension + ".o")
        val isCpp = options.cpp ?: source.extension.lowercase() in setOf("cpp", "cxx", "cc", "c++")

        onLine("== Phase 1: compile (clang-${clangMajor(clang)} ${if (isCpp) "C++" else "C"}) ==")
        if (!compile(source, obj, options, clang, isCpp, onLine)) return null

        onLine("== Phase 2: link (lld -flavor link) ==")
        if (!link(listOf(obj), out, options, lld, isCpp, onLine)) {
            obj.delete()
            return null
        }

        if (!options.keepObject) obj.delete()
        verify(out, options.subsystem, onLine)
        onLine("OK: ${out.name} (${out.length() / 1024} KB)")
        return out
    }

    private fun compile(
        source: File,
        obj: File,
        options: BuildOptions,
        clang: File,
        isCpp: Boolean,
        onLine: (String) -> Unit
    ): Boolean {
        val tripleDir = mingwTriple
        val includeDirs = mutableListOf<File>()
        if (File(tripleDir, "include").isDirectory) includeDirs.add(File(tripleDir, "include"))
        if (File(mingwRoot, "include").isDirectory) includeDirs.add(File(mingwRoot, "include"))
        val sysroot = if (File(tripleDir, "include").isDirectory) tripleDir else mingwRoot

        val args = mutableListOf(
            clang.absolutePath,
            "--target=$triple",
            "--sysroot=${sysroot.absolutePath}",
            "-O2",
            "-c",
            source.absolutePath,
            "-o", obj.absolutePath
        )
        resourceDir()?.let { args.add("-resource-dir=${it.absolutePath}") }
        args.add("-B${clang.parentFile!!.absolutePath}")
        for (d in includeDirs) {
            args.add("-isystem")
            args.add(d.absolutePath)
        }
        args.addAll(options.extraArgs)

        onLine("clang --target=$triple -c ${source.name} -> ${obj.name}")
        val exit = run(args, source.parentFile, onLine)
        if (exit != 0) {
            onLine("COMPILE FAILED (exit $exit)")
            return false
        }
        if (!obj.isFile || obj.length() == 0L) {
            onLine("COMPILE FAILED — no object produced")
            return false
        }
        onLine("OK: ${obj.name} (${obj.length() / 1024} KB)")
        return true
    }

    private fun link(
        objects: List<File>,
        out: File,
        options: BuildOptions,
        lld: File,
        isCpp: Boolean,
        onLine: (String) -> Unit
    ): Boolean {
        val lib = mingwLibDir
        if (!lib.isDirectory) {
            onLine("MinGW lib dir missing: $lib")
            return false
        }
        val isDll = options.outputType == OutputType.DLL

        val entry = when {
            isDll -> "DllMainCRTStartup"
            options.subsystem == Subsystem.WINDOWS -> "WinMainCRTStartup"
            else -> "mainCRTStartup"
        }
        val subsystem = if (isDll) "windows" else
            if (options.subsystem == Subsystem.WINDOWS) "windows" else "console"

        val args = mutableListOf(
            lld.absolutePath,
            "-flavor", "link",
            "/machine:x64",
            "/subsystem:$subsystem",
            "/entry:$entry",
            "/out:${out.absolutePath}",
            "/libpath:${lib.absolutePath}"
        )
        if (isDll) args.add("/dll")

        val crt2 = File(lib, "crt2.o")
        val crtbegin = File(lib, "crtbegin.o")
        val crtend = File(lib, "crtend.o")
        val libs = listOf(
            "libmingw32.a", "libgcc.a", "libgcc_eh.a",
            "libmoldname.a", "libmingwex.a", "libmsvcrt.a"
        )
        val tail = listOf("libkernel32.a")
        val cppLibs = listOf("libstdc++.a", "libwinpthread.a")

        val missing = mutableListOf<String>()
        for (f in listOf(crt2, crtbegin, crtend) + libs.map { File(lib, it) } + tail.map { File(lib, it) }) {
            if (!f.isFile) missing.add(f.name)
        }
        if (isCpp) {
            for (n in cppLibs) if (!File(lib, n).isFile) missing.add(n)
        }
        for (extra in options.extraLinkLibs) {
            if (!File(extra).isFile) missing.add(extra)
        }
        if (missing.isNotEmpty()) {
            onLine("LINK FAILED — missing MinGW libs: ${missing.joinToString(", ")}")
            return false
        }

        args += crt2.absolutePath
        args += crtbegin.absolutePath
        args += objects.map { it.absolutePath }
        if (isCpp) {
            for (n in cppLibs) {
                if (File(lib, n).isFile) args += File(lib, n).absolutePath
            }
        }
        args += libs.map { File(lib, it).absolutePath }
        args += crtend.absolutePath
        args += tail.map { File(lib, it).absolutePath }
        args += options.extraLinkLibs

        onLine("lld -flavor link /machine:x64 /subsystem:$subsystem /entry:$entry")
        val exit = run(args, out.parentFile, onLine)
        if (exit != 0) {
            onLine("LINK FAILED (exit $exit)")
            return false
        }
        if (!out.isFile || out.length() == 0L) {
            onLine("LINK FAILED — no output produced")
            return false
        }
        onLine("OK: ${out.name} (${out.length() / 1024} KB)")
        return true
    }

    // ---------------------------------------------------------------------
    // PE verification (llvm-readobj)
    // ---------------------------------------------------------------------

    data class VerifyResult(
        val ok: Boolean,
        val magic: Boolean,
        val machine: Boolean,
        val subsystem: Boolean,
        val entry: Boolean,
        val raw: List<String>
    )

    /**
     * Verifies a produced PE via llvm-readobj --file-headers:
     *   Magic 0x20B (PE32+), Machine AMD64 (0x8664), WINDOWS_CUI/GUI,
     *   non-zero EntryPointAddress.
     */
    fun verify(
        exe: File,
        subsystem: Subsystem = Subsystem.CONSOLE,
        onLine: (String) -> Unit = {}
    ): VerifyResult {
        val readobj = llvmReadobjPath()
        if (readobj == null) {
            onLine("(llvm-readobj not found — PE verification skipped)")
            return VerifyResult(false, false, false, false, false, emptyList())
        }
        val raw = mutableListOf<String>()
val exit = try {
             val readobjDir = readobj.parentFile
             val pathEnv = readobjDir.absolutePath + ":" + (System.getenv("PATH") ?: "/system/bin")
             val env = mapOf("PATH" to pathEnv)
             val pb = ExecHelper.createProcess(
                 context,
                 listOf(readobj.absolutePath, "--file-headers", exe.absolutePath),
                 exe.parentFile,
                 env
             ).redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().forEachLine { line ->
                Log.d(TAG, "[readobj] $line")
                raw.add(line)
            }
            val done = proc.waitFor(60, TimeUnit.SECONDS)
            if (!done) { proc.destroyForcibly(); -1 } else proc.exitValue()
        } catch (e: Exception) {
            onLine("verification error: ${e.message}")
            -1
        }
        if (exit != 0) {
            onLine("(llvm-readobj exit $exit — verification failed)")
            return VerifyResult(false, false, false, false, false, raw)
        }
        val text = raw.joinToString("\n")
        val magic = text.contains("0x20B")
        val machine = text.contains("0x8664") || text.contains("AMD64") || text.contains("X86_64")
        val isDll = exe.extension.equals("dll", ignoreCase = true)
        val want = if (isDll || subsystem == Subsystem.WINDOWS) "windows(gui)" else "windows(cui)"
        val sub = Regex("(?i)$want").containsMatchIn(text)
        val entry = Regex("(?i)entrypointaddress:\\s*0x[1-9a-f][0-9a-f]*").containsMatchIn(text)

        val checks = listOf(
            "PE32+ (Magic 0x20B)" to magic,
            "Machine AMD64 (0x8664)" to machine,
            "Subsystem ${if (isDll || subsystem == Subsystem.WINDOWS) "WINDOWS_GUI" else "WINDOWS_CUI"}" to sub,
            "EntryPoint non-zero" to entry
        )
        val ok = checks.all { it.second }
        for ((label, pass) in checks) {
            onLine("  ${if (pass) "[OK]" else "[FAIL]"} $label")
        }
        if (ok) onLine("PE verification passed")
        else onLine("PE verification FAILED — inspect llvm-readobj output above")
        return VerifyResult(ok, magic, machine, sub, entry, raw)
    }

    // ---------------------------------------------------------------------
    // Self-test: test.c -> test.o -> test.exe -> verify
    // ---------------------------------------------------------------------

    fun selfTest(onLine: (String) -> Unit = {}): Boolean {
        onLine("== Windows x64 build self-test ==")
        if (!isToolchainReady(onLine)) return false
        val dir = File(filesDir, "toolchain/mingw-x86_64/self-test")
        dir.mkdirs()
        val src = File(dir, "test.c")
        src.writeText(
            "#include <stdio.h>\n" +
                "int main(void) {\n" +
                "    printf(\"hello from mingw x64\\n\");\n" +
                "    return 0;\n" +
                "}\n"
        )
        val exe = File(dir, "test.exe")
        val result = build(src, exe, BuildOptions(keepObject = true), onLine)
        if (result == null) {
            onLine("SELF-TEST FAILED — build failed")
            return false
        }
        val v = verify(exe, Subsystem.CONSOLE, onLine)
        if (!v.ok) {
            onLine("SELF-TEST FAILED — PE verification failed")
            return false
        }
        onLine("SELF-TEST PASSED: ${exe.absolutePath}")
        return true
    }

    // ---------------------------------------------------------------------
    // Process runner (ExecHelper routes app-data bionic ELFs through linker64)
    // ---------------------------------------------------------------------

    private fun run(args: List<String>, cwd: File?, onLine: (String) -> Unit): Int {
        val env = mutableMapOf<String, String>()
        val binDir = args[0].substringBeforeLast('/')
        env["PATH"] = binDir + ":" + (System.getenv("PATH") ?: "/system/bin")
        
        // Ensure sub-processes (like cc1) can execute on devices with app-data exec restrictions.
        val preload = File(context.applicationInfo.nativeLibraryDir, "libexec_redirect.so")
        if (preload.exists()) {
            env["LD_PRELOAD"] = preload.absolutePath
            env["MOBILESTUDIO_CODE_CACHE"] = context.codeCacheDir.absolutePath
        }

        val pb = ExecHelper.createProcess(
            context,
            args,
            cwd,
            env
        ).redirectErrorStream(true)
        val proc = pb.start()
        proc.inputStream.bufferedReader().forEachLine { line ->
            Log.d(TAG, line)
            runCatching { onLine(line) }
        }
        val done = proc.waitFor(5, TimeUnit.MINUTES)
        if (!done) {
            runCatching { proc.destroyForcibly() }
            onLine("(build timed out — killed)")
            return -1
        }
        return proc.exitValue()
    }
}