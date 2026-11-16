package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import com.aide.mobilestudio.wine.MiniXServer

/**
 * Windows .exe 실행 환경 (Box64 + Wine, mobox 아키텍처 이식).
 *
 * 구성 요소 (모두 mobox GitLab 저장소에서 tar.xz로 배포):
 *  - glibc-prefix        : aarch64 glibc 런타임 (box64 자신 실행용) + x86_64 glibc libs
 *  - box86-box64-binaries: box64 (aarch64 ELF, glibc 링크, x86_64 에뮬레이터)
 *  - wine-ge-custom      : x86_64 Wine (box64가 에뮬레이션)
 *
 * 실행 체인 (Android noexec/SELinux 우회):
 *   /system/bin/linker64 <box64> <wine64> app.exe [args...]   (bionic, app data)
 *   <box64> ...                                              (native lib dir: 커널 직접 exec)
 *   ld-linux-aarch64.so.1 <box64> ...                        (glibc)
 *   - /data/data·/data/user는 untrusted_app execve를 SELinux로 차단하므로
 *     app-data ELF를 직접 exec하면 EACCES/126 → /system/bin/linker64가 로드
 *     (JRE 방식). libfork_loader.so는 e_entry=0인 JNI 공유오브젝트라 프로그램
 *     으로 exec하면 SIGILL(exit 132)이 나므로 사용하지 않는다.
 *   - box64는 glibc 링크 바이너리 → ld-linux-aarch64.so.1이 로드하고,
 *     ld.so가 box64에게 argv=[box64, args...]를 넘긴다 (elf 중복 금지).
 *   - wine64는 x86_64 ELF → box64가 에뮬레이션, x86_64 glibc libs는 BOX64_LD_LIBRARY_PATH로 제공
 */
class WindowsExeEnvironment(private val context: Context) {

    companion object {
        private const val TAG = "WinExeEnv"
        private const val DIR = "toolchain/win64"
        private const val PKG_GLIBC = "glibc-prefix"
        private const val PKG_GLIBC_X86 = "glibc-x86_64"
        private const val PKG_BOX64 = "box86-box64-binaries"
        private const val PKG_WINE = "wine-ge-custom"
        private const val GITLAB_API = "https://gitlab.com/api/v4/projects/52465323/repository/files"
        private const val GITLAB_REF = "ref=main"
        private const val BOOT_MARKER = ".update-timestamp"
        // 500KB auto-repair rule: components smaller than this are re-downloaded
        private const val MIN_HEALTHY_BYTES = 500L * 1024
        // GitHub Release URLs
        private const val BOX64_GITHUB_URL = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/Box64-arm64/box64-arm64"
        private const val GLIBC_GITHUB_URL = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/GLIBC-arm64/glibc-aarch64.tar.gz"

        /**
         * Builds a ProcessBuilder for a Windows .exe via box64 → wine64.
         * Called from ExecHelper when args[0] is an MZ file. Throws
         * IllegalStateException if the Windows environment is not installed.
         */
        fun createProcessBuilder(
            context: Context,
            args: List<String>,
            cwd: File?,
            env: Map<String, String>
        ): ProcessBuilder {
            val exeFile = File(args[0])
            val envInstance = WindowsExeEnvironment(context)
            if (!envInstance.isInstalled) {
                throw IllegalStateException(
                    "Windows .exe 실행 환경이 설치되어 있지 않습니다. 먼저 Install 하세요."
                )
            }
            val wine64 = envInstance.wine64Path()
                ?: throw IllegalStateException("wine64 not installed")
            val command = envInstance.buildBox64Command(
                listOf(wine64.absolutePath, exeFile.absolutePath) + args.drop(1)
            )
            val builder = ProcessBuilder(command)
                .directory(cwd ?: File(context.filesDir, "toolchain/win64"))
            builder.environment().putAll(env)
            builder.environment().putAll(envInstance.box64Env())
            return builder
        }
    }

    private val win64Dir: File get() = File(context.filesDir, DIR)
    private val downloadsDir: File get() = File(win64Dir, "downloads")
    private val pkgsDir: File get() = File(win64Dir, "pkgs")
    private val glibcPkgDir: File get() = File(pkgsDir, PKG_GLIBC)
    private val glibcX86Dir: File get() = File(pkgsDir, PKG_GLIBC_X86)
    private val box64PkgDir: File get() = File(pkgsDir, PKG_BOX64)
    private val winePkgDir: File get() = File(pkgsDir, PKG_WINE)
    private val winePrefix: File get() = File(win64Dir, "wineprefix")
    private val tmpDir: File get() = File(win64Dir, "tmp")

    private var cachedLoader: File? = null
    private var cachedBox64: File? = null
    private var cachedWine64: File? = null
    private var cachedX86LibDir: File? = null
    private var cachedWineUnixDir: File? = null
    private var cachedVersion: String? = null

    fun loaderPath(): File? {
        cachedLoader?.let { if (it.exists()) return it }
        // glibc-prefix 우선, mobox box64 패키지/win64 루트에 번들된
        // ld-linux-aarch64.so.1도 fallback으로 검색
        val found = listOf(glibcPkgDir, box64PkgDir, win64Dir)
            .mapNotNull { findFileRecursive(it, "ld-linux-aarch64.so.1") }
            .firstOrNull()
        cachedLoader = found
        return found
    }

    /**
     * Finds all directories containing glibc shared libraries (*.so.6, *.so.1)
     * — glibc-prefix + box64 패키지(자체 ARM64 libs: libgcc_s, libstdc++ 등) 포함.
     */
    fun glibcLibDirs(): List<File> {
        val dirs = mutableSetOf<File>()
        listOf(glibcPkgDir, box64PkgDir).forEach { root ->
            if (!root.isDirectory) return@forEach
            root.walkTopDown().filter {
                it.isFile && (it.name.endsWith(".so.6") || it.name.contains(".so.1"))
            }.forEach {
                it.parentFile?.let { p -> dirs.add(p) }
            }
        }
        return dirs.toList()
    }
    fun forkLoaderPath(): File {
        // fork_loader is an ET_EXEC executable (PT_INTERP present) so it can be
        // exec()'d directly. PREFER the native library directory copy as it is 
        // apk_data_file type (exec-allowed). The app-data copy is denied by SELinux.
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        
        // Android packages .so files in the native library directory. 
        // We package fork_loader as libfork_loader.so for this reason.
        val candidates = listOf(
            File(nativeLibDir, "libfork_loader.so"),
            File(nativeLibDir, "fork_loader")
        )
        
        val found = candidates.firstOrNull { it.exists() }
        if (found != null) {
            runCatching { found.setExecutable(true, false) }
            runCatching { Os.chmod(found.absolutePath, 0b111_101_101) }
            Log.i(TAG, "forkLoaderPath: using trusted native copy ${found.absolutePath}")
            return found
        }
        
        // If not in native lib dir, try app data but warn (it will likely fail on Android 10+)
        val appData = File(win64Dir, "fork_loader")
        if (appData.exists()) {
            Log.w(TAG, "forkLoaderPath: trusted copy missing, using app-data fallback (may fail)")
            runCatching { appData.setExecutable(true, false) }
            runCatching { Os.chmod(appData.absolutePath, 0b111_101_101) }
            return appData
        }
        
        copyForkLoader()
        return appData
    }
    fun box64Path(): File? = cachedBox64 ?: (
        findFileRecursive(box64PkgDir, "box64") ?: 
        findFileRecursive(box64PkgDir, "box64-arm64") ?:
        findFileRecursive(win64Dir, "box64")
    ).also { cachedBox64 = it }
    
    fun wine64Path(): File? = cachedWine64 ?: (
        findFileRecursive(winePkgDir, "wine64") ?:
        findFileRecursive(winePkgDir, "wine") ?:
        findFileRecursive(win64Dir, "wine64")
    ).also { cachedWine64 = it }
    fun x86LibDir(): File? = cachedX86LibDir ?: (
        findDirRecursive(glibcPkgDir, "x86_64-linux-gnu")
            ?: findDirRecursive(box64PkgDir, "x86_64-linux-gnu")
            // mobox glibc-prefix layout: x86_64 glibc libs live in lib64/
            ?: File(glibcPkgDir, "lib64").takeIf { d ->
                d.isDirectory && d.listFiles()?.any { f -> f.name.startsWith("libc.so") } == true
            }
            ?: File(glibcPkgDir, "usr/lib64").takeIf { d ->
                d.isDirectory && d.listFiles()?.any { f -> f.name.startsWith("libc.so") } == true
            }
        )?.also { cachedX86LibDir = it }

    /** All directories that contain x86_64 shared libraries for BOX64_LD_LIBRARY_PATH. */
    fun x86LibDirs(): List<String> {
        val dirs = mutableListOf<String>()
        x86LibDir()?.let { dirs.add(it.absolutePath) }
        // mobox glibc-prefix puts x86_64 libs in lib64/ and usr/lib64/
        listOf(File(glibcPkgDir, "lib64"), File(glibcPkgDir, "usr/lib64")).forEach { d ->
            if (d.isDirectory && dirs.none { it == d.absolutePath }) {
                val hasX86 = d.listFiles()?.any { f ->
                    f.isFile && (f.name.startsWith("libc.so") || f.name.startsWith("libm.so"))
                } == true
                if (hasX86) dirs.add(d.absolutePath)
            }
        }
        return dirs.distinct()
    }
    fun wineUnixLibDir(): File? = cachedWineUnixDir ?: listOf(
        File(winePkgDir, "lib/wine/x86_64-unix"),
        File(winePkgDir, "lib64/wine/x86_64-unix"),
        File(winePkgDir, "lib/wine")
    ).firstOrNull { it.isDirectory }?.also { cachedWineUnixDir = it }

    /**
     * Ready-to-run when a box64 (preferably the packaged bionic one, which
     * execs directly from the APK's native lib dir) and wine64 exist. The
     * glibc loader is only required by the downloaded glibc box64 fallback.
     */
    val isInstalled: Boolean get() = effectiveBox64() != null && wine64Path() != null
    val isPrefixBooted: Boolean get() = File(winePrefix, BOOT_MARKER).exists()

    /** Ensures box64 binary is valid, re-downloading from GitHub if corrupted. */
    fun ensureBox64Valid(): Boolean {
        val box64File = File(box64PkgDir, "bin/box64")
        val result = Box64Downloader.ensureBox64(context, box64File)
        if (!result.success) {
            Log.w(TAG, "box64 validation failed: ${result.message}")
        }
        return result.success
    }

fun install(onProgress: (String) -> Unit) {
        installBox64Only(onProgress)
        installGlibcOnly(onProgress)
        installWineOnly(onProgress)

        // Copy fork_loader to toolchain/win64/fork_loader
        copyForkLoader()

        cachedLoader = null; cachedBox64 = null; cachedWine64 = null
        cachedX86LibDir = null; cachedWineUnixDir = null
        makeExecutable()
        onProgress("Windows .exe environment ready")
    }

    /** Downloads/refreshes only the box64 binary. */
    fun installBox64Only(onProgress: (String) -> Unit) {
        // The APK ships a bionic box64 (libbox64.so) that execs directly from
        // the native lib dir — the only path that works on ROMs that block
        // app-data execve. The GitHub download is only a fallback for that.
        val packaged = File(context.applicationInfo.nativeLibraryDir, "libbox64.so")
        if (packaged.isFile && packaged.length() > 0L) {
            onProgress("box64 ready (packaged bionic build, ${packaged.length()} bytes)")
            File(box64PkgDir, ".complete").writeText("1")
            return
        }
        onProgress("Downloading box64 from GitHub...")
        val box64File = File(box64PkgDir, "bin/box64")
        box64File.parentFile?.mkdirs()
        val box64Result = Box64Downloader.ensureBox64(context, box64File, onProgress)
        if (!box64Result.success) {
            Log.w(TAG, "box64 download failed: ${box64Result.message}")
            onProgress("ERROR: box64 download failed: ${box64Result.message}")
        } else {
            File(box64PkgDir, ".complete").writeText("1")
            onProgress("box64 ready (${box64File.length()} bytes)")
        }
    }

    /** Downloads/refreshes only the glibc prefix (the ARM64 loader). */
    fun installGlibcOnly(onProgress: (String) -> Unit) {
        onProgress("Downloading glibc from GitHub...")
        val glibcResult = GlibcDownloader.ensureGlibc(context, glibcPkgDir, onProgress)
        if (!glibcResult.success) {
            Log.w(TAG, "glibc download failed: ${glibcResult.message}")
            onProgress("ERROR: glibc download failed: ${glibcResult.message}")
        } else {
            File(glibcPkgDir, ".complete").writeText("1")
            onProgress("glibc ready")
        }
    }

    /** Downloads the x86_64 glibc runtime (needed by box64 to load wine64). */
    fun installX86GlibcOnly(onProgress: (String) -> Unit) {
        if (X86GlibcDownloader.isInstalled(glibcX86Dir)) {
            onProgress("x86_64 glibc already installed")
            return
        }
        onProgress("Downloading x86_64 glibc runtime...")
        val result = X86GlibcDownloader.ensureX86Glibc(context, glibcX86Dir, onProgress)
        if (!result.success) {
            Log.w(TAG, "x86_64 glibc failed: ${result.message}")
            onProgress("ERROR: ${result.message}")
        } else {
            File(glibcX86Dir, ".complete").writeText("1")
            onProgress("x86_64 glibc ready")
        }
    }

    /** Downloads/refreshes only the wine64 runtime (GitLab tar.xz). */
    fun installWineOnly(onProgress: (String) -> Unit) {
        if (File(winePkgDir, ".complete").exists()) {
            onProgress("wine already installed")
            return
        }
        onProgress("Downloading wine...")
        val archive = File(downloadsDir, "$PKG_WINE.tar.xz")
        downloadFile("$GITLAB_API/$PKG_WINE.tar.xz/raw?$GITLAB_REF", archive, onProgress)
        onProgress("Extracting wine...")
        extractTarXz(archive, winePkgDir, onProgress)
        archive.delete()
        // The tar contains a top-level wine-ge-custom/ directory that stripPrefix
        // does not always remove. Flatten it so wine64Path(), wineUnixLibDir()
        // and buildEnvironment() find files at the expected paths.
        val nested = winePkgDir.listFiles()?.firstOrNull { it.isDirectory && it.name == PKG_WINE }
        if (nested != null && nested.isDirectory) {
            onProgress("Flattening wine directory layout...")
            nested.listFiles()?.forEach { child ->
                val target = File(winePkgDir, child.name)
                if (target.exists()) target.deleteRecursively()
                runCatching { child.renameTo(target) }
            }
            nested.delete()
        }
        File(winePkgDir, ".complete").writeText("1")
    }

    /**
     * 500KB auto-repair rule: if any core component (box64, wine64, glibc
     * loader) exists but is suspiciously small, delete and re-download it.
     * The glibc loader may legitimately be < 500KB when fully valid ELF64 —
     * in that case it is kept (only truly corrupt files are replaced).
     */
    fun ensureComponentHealth(onProgress: (String) -> Unit = {}): Boolean {
        var broke = false
        // box64: the packaged bionic build (libbox64.so in the APK's native lib
        // dir) is the preferred runtime and cannot be corrupted by downloads —
        // only the downloaded glibc fallback is size-checked.
        if (effectiveBox64()?.absolutePath?.startsWith(nativeLibraryDir()) != true) {
            val box = File(box64PkgDir, "bin/box64")
            if (box.exists() && box.length() < MIN_HEALTHY_BYTES) {
                onProgress("box64 too small (${box.length()} B) — re-downloading")
                runCatching { box.delete(); File(box64PkgDir, ".complete").delete() }
                broke = true
            }
        }
        // wine: bin/wine64 is a tiny launcher stub by design (~14 KB) — the
        // 500 KB rule there caused an endless 79 MB re-download loop. Health is
        // signalled by the real runtime payload instead (x86_64-unix/ntdll.so).
        val wineRuntime = File(winePkgDir, "lib64/wine/x86_64-unix/ntdll.so")
            .takeIf { it.isFile }
            ?: File(winePkgDir, "lib/wine/x86_64-unix/ntdll.so").takeIf { it.isFile }
        if (wine64Path() == null || wineRuntime == null || wineRuntime.length() < MIN_HEALTHY_BYTES) {
            onProgress(
                when {
                    wine64Path() == null -> "wine64 missing — re-downloading"
                    wineRuntime == null -> "wine runtime payload missing — re-downloading"
                    else -> "wine runtime too small (${wineRuntime.length()} B) — re-downloading"
                },
            )
            runCatching { File(winePkgDir, ".complete").delete(); winePkgDir.deleteRecursively() }
            broke = true
        }
        loaderPath()?.let { ld ->
            if (ld.exists() && ld.length() < MIN_HEALTHY_BYTES && !isValidElf64(ld)) {
                onProgress("glibc loader corrupt (${ld.length()} B) — re-downloading")
                runCatching { File(glibcPkgDir, ".complete").delete(); glibcPkgDir.deleteRecursively() }
                broke = true
            }
        }
        if (broke) {
            cachedLoader = null; cachedBox64 = null; cachedWine64 = null
            cachedX86LibDir = null; cachedWineUnixDir = null
            install(onProgress)
        } else {
            onProgress("all components healthy")
        }
        return broke
    }

    private fun nativeLibraryDir(): String = context.applicationInfo.nativeLibraryDir

    private fun isValidGlibc(dir: File): Boolean {
        return GlibcDownloader.isValidGlibc(dir)
    }

    private fun isValidElf64(file: File): Boolean {
        return try {
            val raf = java.io.RandomAccessFile(file, "r")
            val magic = ByteArray(5)
            raf.readFully(magic)
            raf.close()
            magic[0] == 0x7F.toByte() &&
            magic[1] == 'E'.code.toByte() &&
            magic[2] == 'L'.code.toByte() &&
            magic[3] == 'F'.code.toByte() &&
            magic[4] == 2.toByte()
        } catch (e: Exception) {
            false
        }
    }

    private fun makeExecutable() {
        for (f in listOfNotNull(loaderPath(), box64Path(), wine64Path())) {
            runCatching { f.setExecutable(true, false); f.setReadable(true, false); Os.chmod(f.absolutePath, 0b111_101_101) }
        }
        // Wine runtime executables (wineboot, wineserver, loader, ...) are exec'd by
        // wine itself — they must carry exec bits too, otherwise prefix boot fails
        // with EACCES right after "Initializing Wine prefix...".
        val wineExecDirs = listOf(
            File(winePkgDir, "bin"),
            File(winePkgDir, "lib/wine/x86_64-unix"),
            File(winePkgDir, "lib64/wine/x86_64-unix"),
            File(winePkgDir, "lib/wine"),
            File(winePkgDir, "lib64/wine")
        )
        for (dir in wineExecDirs) {
            if (!dir.isDirectory) continue
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || f.length() == 0L) return@forEach
                if (f.name.endsWith(".so") || f.name.endsWith(".dll")) return@forEach
                runCatching { f.setExecutable(true, false); f.setReadable(true, false) }
            }
        }
    }

    fun buildEnvironment(): Map<String, String> {
        tmpDir.mkdirs()
        // BOX64_LD_LIBRARY_PATH is for GUEST (x86_64) libraries only. It must
        // contain the x86_64 glibc runtime + wine x86_64 libs — and must NOT
        // contain the aarch64 glibc dirs (glibc-prefix/lib64 is AArch64 on this
        // package), otherwise box64 resolves wine64's libc.so.6 to the wrong
        // ELF class and crashes.
        val libDirs = mutableListOf<String>()
        // x86_64 glibc runtime (ld-linux-x86-64.so.2, libc.so.6, libpthread...)
        listOf(
            File(glibcX86Dir, "x86_64-linux-gnu"),
            File(glibcX86Dir, "usr/lib/x86_64-linux-gnu"),
        ).forEach { if (it.isDirectory) libDirs.add(it.absolutePath) }
        // Wine x86_64 host libs and PE modules
        listOf(
            File(winePkgDir, "lib64/wine/x86_64-unix"),
            File(winePkgDir, "lib64/wine/x86_64-windows"),
            File(winePkgDir, "lib/wine/x86_64-unix"),
            File(winePkgDir, "lib/wine/x86_64-windows"),
        ).forEach { if (it.isDirectory) libDirs.add(it.absolutePath) }
        // x86_64-linux-gnu elsewhere if the package uses that layout
        x86LibDir()?.absolutePath?.let { if (it !in libDirs) libDirs.add(it) }
        val box64LibPath = libDirs.distinct().joinToString(":")
        Log.i(TAG, "BOX64_LD_LIBRARY_PATH=$box64LibPath")
        return mapOf(
            "HOME" to win64Dir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to "/system/bin:/system/xbin",
            "DISPLAY" to ":0",
            "BOX64_LD_LIBRARY_PATH" to box64LibPath,
            "WINEPREFIX" to winePrefix.absolutePath,
            "WINEARCH" to "win64",
            "WINEDEBUG" to "-all",
            "BOX64_LOG" to "0",
            "BOX64_NOBANNER" to "1",
            "BOX64_DYNAREC_CALLRET" to "1",
            "BOX64_DYNAREC_STRONGMEM" to "0"
        )
    }

    // =====================================================================
    // X11 디스플레이 서버 (Wine GUI용)
    // =====================================================================

    private val xServerPackages = listOf(
        "x11.xserver",
        "au.com.darkside.XServer",
    )

    fun findInstalledXServer(): String? =
        xServerPackages.firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }

    fun ensureXServer(onProgress: (String) -> Unit = {}): Boolean {
        val pkg = findInstalledXServer()
        if (pkg == null) {
            onProgress("X server app not installed. Install XServer XSDL from F-Droid.")
            return false
        }
        onProgress("Starting X server ($pkg)...")
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        for (i in 0 until 20) {
            Thread.sleep(500)
            runCatching {
                java.net.Socket("127.0.0.1", 6000).use { s ->
                    if (s.isConnected) { onProgress("X server ready (TCP)"); return true }
                }
            }
        }
        onProgress("X server socket timeout — running in console mode")
        return false
    }

    // =========================================================================
    // Box64 ARM64 네이티브 실행 경로
    // =========================================================================

    /** 사용할 box64 파일: native lib dir에 libbox64.so가 있으면 최우선. */
    fun effectiveBox64(): File? {
        File(context.applicationInfo.nativeLibraryDir, "libbox64.so")
            .takeIf { it.isFile && it.length() > 0L }
            ?.let { return it }
        return box64Path()
    }

    /** "bionic" / "glibc" / "unknown" / "missing" */
    fun box64Kind(): String {
        val box64 = effectiveBox64() ?: return "missing"
        return when {
            ExecHelper.isBionicElf(box64) -> "bionic"
            ExecHelper.isGlibcElf(box64) -> "glibc"
            else -> "unknown"
        }
    }

    /**
     * box64 실행 앞단 명령 (app 인자는 뒤에 붙인다):
     *  - glibc box64: [ld-linux-aarch64.so.1, box64] + appArgs
     *  - bionic box64 (native lib): [libbox64.so] + appArgs (커널 직접 exec)
     *  - bionic box64 (app data):   [/system/bin/linker64, box64] + appArgs
     *
     * argv 규칙:
     *  - bionic: execve(linker64, [linker64, box64] + appArgs) →
     *    box64 argv = [box64, appArgs...] (linker64가 box64를 로드, JRE 방식).
     *  - glibc: execve(ld.so, [ld.so, box64] + appArgs) →
     *    ld.so가 box64에게 [box64, appArgs...] 를 넘긴다.
     *    target_argv에 box64를 또 넣으면 box64가 자기 자신을
     *    x86-64 대상으로 에뮬레이션하려 한다 (금지).
     *  예) box64 --version       → appArgs = [--version]
     *      box64 wine64 test.exe → appArgs = [wine64, test.exe]
     *
     *  (fork_loader는 패키징된 libfork_loader.so가 e_entry=0인 JNI 공유
     *   오브젝트여서 프로그램으로 exec 시 SIGILL/exit=132가 나므로 사용하지
     *   않는다.)
     */
    fun box64LaunchPrefix(): List<String> {
        val box64 = effectiveBox64() ?: throw IllegalStateException("box64 not installed")
        return when (box64Kind()) {
            "bionic" -> {
                val nativeCopy = File(context.applicationInfo.nativeLibraryDir, "libbox64.so")
                if (nativeCopy.isFile) {
                    // apk_data_file: 커널 직접 exec 허용
                    listOf(nativeCopy.absolutePath)
                } else {
                    // app-data bionic box64 → linker64 로딩 (JRE 방식).
                    // untrusted_app의 app-data execve는 SELinux가 차단하므로
                    // 커널 직접 exec 대신 /system/bin/linker64가 box64를 로드한다.
                    listOf("/system/bin/linker64", box64.absolutePath)
                }
            }
            else -> {
                // glibc box64 → ld.so가 box64를 로드 (run_argv = [ld.so, box64] + appArgs)
                val ld = loaderPath() ?: throw IllegalStateException("glibc loader not installed")
                listOf(ld.absolutePath, box64.absolutePath)
            }
        }
    }

    /** box64에 전달할 app 인자를 포함한 전체 명령. */
    fun buildBox64Command(appArgs: List<String>): List<String> = box64LaunchPrefix() + appArgs

    /**
     * box64 실행용 환경.
     * glibc box64는 fork_loader가 자식 LD_LIBRARY_PATH로 사용할
     * FORK_LOADER_NATIVE_DIR에 aarch64 glibc lib dirs만 넣는다
     * (x86_64 디렉터리를 섞으면 "wrong ELF class"로 크래시).
     * bionic box64는 box64 패키지 lib dirs + native lib dir을 넣는다.
     */
    fun box64Env(): Map<String, String> {
        val env = buildEnvironment().toMutableMap()
        when (box64Kind()) {
            "bionic" -> {
                val dirs = mutableListOf<String>()
                box64PkgDir.walkTopDown().filter {
                    it.isFile && (it.name.endsWith(".so.6") || it.name.contains(".so.1"))
                }.forEach { it.parentFile?.let { p -> if (p.isDirectory) dirs.add(p.absolutePath) } }
                dirs.add(context.applicationInfo.nativeLibraryDir)
                env["FORK_LOADER_NATIVE_DIR"] = dirs.distinct().joinToString(":")
            }
            else -> {
                val aarch64Libs = glibcLibDirs()
                    .filter { it.isDirectory && !it.absolutePath.contains("x86_64") }
                    .map { it.absolutePath }
                    .distinct()
                    .joinToString(":")
                if (aarch64Libs.isNotBlank()) env["FORK_LOADER_NATIVE_DIR"] = aarch64Libs
            }
        }
        return env
    }

    /**
     * box64 실행 (--version 등 단순 인자) — 단계별 테스트에서 사용.
     *
     * @return exit code (-1 = timeout)
     */
    fun runBox64(args: List<String>, timeoutMs: Long = 90_000, onLine: (String) -> Unit = {}): Int {
        val command = buildBox64Command(args)
        Log.i(TAG, "runBox64: ${command.joinToString(" ")}")
        val process = try {
            ExecHelper.createProcess(context, command, win64Dir, box64Env())
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "runBox64 start failed: ${e.message}", e)
            return -3
        }
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { onLine(it) } }
        }.apply {
            name = "Box64CmdReader"
            isDaemon = true
            start()
        }
        val done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) runCatching { process.destroyForcibly() }
        reader.join(2000)
        return if (done) process.exitValue() else -1
    }

    /**
     * 단계별 실행 테스트 (linker64 로딩 검증 → box64 → wine64 → test.exe):
     *
     *   S0  linker64 로딩 검증: /system/bin/linker64로 /system/bin/echo를
     *       bionic 로드해 "FORK_LOADER_OK" 출력 확인 (app-data ELF 실행 경로)
     *   S1  box64 --version          : box64가 Android에서 네이티브 실행되는지
     *   S2  box64 wine64 --version   : x86-64 Wine64가 box64 에뮬레이션으로 뜨는지
     *   S3  box64 wine64 test.exe    : 전체 체인 (test.exe는 win64/test.exe 또는
     *       filesDir/test.exe에서 탐색, 없으면 Build .exe로 생성 안내)
     */
    fun runChainTest(onLine: (String) -> Unit) {
        fun log(m: String) { Log.i(TAG, m); onLine(m) }
        log("=== WinEXE Chain Test (linker64 → box64 → wine64 → exe) ===")

        log("S0: linker64 로딩 검증 (execve linker64 → echo)...")
        val s0 = ForkLoader.selfTest(context)
        log("S0: $s0")

        val box64 = effectiveBox64()
        val wine64 = wine64Path()
        if (box64 == null || wine64 == null) {
            log("SKIP: box64/wine64 not installed (Install 먼저)")
            log("=== done ===")
            return
        }
        log("S1: box64=${box64.absolutePath} (${box64.length() / 1024} KB, kind=${box64Kind()})")
        log("S1: launch prefix: ${box64LaunchPrefix().joinToString(" ")}")

        log("S2: box64 --version ...")
        val v1 = runBox64(listOf("--version")) { line -> log("  | $line") }
        log("S2: exit=$v1")
        if (v1 != 0) {
            log("S2: box64 실행 실패 — ld-linux/linker64 로딩 문제 또는 EACCES/noexec 확인")
        }

        log("S3: wine64 --version ...")
        val v2 = runBox64(listOf(wine64.absolutePath, "--version")) { line -> log("  | $line") }
        log("S3: exit=$v2")

        val testExe = listOf(File(win64Dir, "test.exe"), File(context.filesDir, "test.exe"))
            .firstOrNull { it.isFile && it.length() > 0L }
        if (testExe != null) {
            log("S4: test.exe=${testExe.absolutePath} ...")
            val v3 = runBox64(listOf(wine64.absolutePath, testExe.absolutePath), timeoutMs = 5 * 60_000) { line ->
                log("  | $line")
            }
            log("S4: exit=$v3")
        } else {
            log("S4: SKIP — test.exe 없음. win64/test.exe에 배치하거나 Build .exe로 생성하세요.")
        }
        log("=== done ===")
    }

    /** One-shot diagnostics to tell whether app-data ELF exec / linker64 load works. */
    private fun diagnoseExecEnvironment(onLine: (String) -> Unit) {
        ensureBox64Valid()
        val diagDir = File(win64Dir, "tmp").apply { mkdirs() }
        val echoCopy = File(diagDir, "echo.test")
        runCatching {
            File("/system/bin/echo").copyTo(echoCopy, overwrite = true)
            echoCopy.setExecutable(true, false)
            Os.chmod(echoCopy.absolutePath, 0b111_101_101)
        }
        fun prepareStop(p: Process) {
            p.outputStream.close()
            if (!p.isAlive && !p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly()
        }
        fun runDiag(name: String, build: () -> Process): String = try {
            val p = build()
            prepareStop(p)
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            val c = if (p.isAlive) { p.destroyForcibly(); -1 } else p.exitValue()
            "$name exit=$c out=${if (out.isBlank()) "<blank>" else out.take(80)}"
        } catch (e: Exception) {
            "$name FAIL: ${e.message?.take(120)}"
        }
        val t1 = runDiag("T1 directELF") {
            ProcessBuilder(echoCopy.absolutePath, "HELLO").redirectErrorStream(true).start()
        }
        val t2 = runDiag("T2 linker64") {
            ProcessBuilder("/system/bin/linker64", echoCopy.absolutePath, "HELLO").redirectErrorStream(true).start()
        }
        val script = File(diagDir, "diag.sh")
        script.writeText("#!/system/bin/sh\necho SCRIPT_OK\n")
        script.setExecutable(true, false)
        Os.chmod(script.absolutePath, 0b111_101_101)
        val t3 = runDiag("T3 script") {
            ProcessBuilder(script.absolutePath).redirectErrorStream(true).start()
        }
        val t4 = runDiag("T4 sh-c") {
            ProcessBuilder("/system/bin/sh", "-c", "echo SH_OK").redirectErrorStream(true).start()
        }
        val t5 = runDiag("T5 toybox") {
            ProcessBuilder("/system/bin/toybox", "echo", "TOY_OK").redirectErrorStream(true).start()
        }
        val t6 = runDiag("T6 sh(script)") {
            ProcessBuilder("/system/bin/sh", script.absolutePath).redirectErrorStream(true).start()
        }
        val t7 = runCatching {
            val nlDir = context.applicationInfo.nativeLibraryDir
            val nlCopy = File(nlDir, "echo.diag")
            File("/system/bin/echo").copyTo(nlCopy, overwrite = true)
            nlCopy.setExecutable(true, false)
            runDiag("T7 nativeLib") {
                ProcessBuilder(nlCopy.absolutePath, "NAT_OK").redirectErrorStream(true).start()
            }
        }.getOrElse { "T7 nativeLib write FAIL: ${it.message?.take(90)}" }
        val t8 = runCatching {
            val ccDir = File(context.codeCacheDir, "exec_diag").apply { mkdirs() }
            val ccCopy = File(ccDir, "echo.test")
            File("/system/bin/echo").copyTo(ccCopy, overwrite = true)
            ccCopy.setExecutable(true, false)
            Os.chmod(ccCopy.absolutePath, 0b111_101_101)
            runDiag("T8 codeCache") {
                ProcessBuilder(ccCopy.absolutePath, "CC_OK").redirectErrorStream(true).start()
            }
        }.getOrElse { "T8 codeCache write FAIL: ${it.message?.take(90)}" }
        val t9 = realLinkerCandidates()
            .filter { File(it).exists() && File(it).canExecute() }
            .joinToString(" | ") { cand ->
                runDiag("T9 ${File(cand).name}") {
                    ProcessBuilder(cand, echoCopy.absolutePath, "HELLO").redirectErrorStream(true).start()
                }
            }
        val t10 = runCatching {
            val p = ProcessBuilder(
                "/system/bin/sh",
                "-c",
                "cat /proc/mounts | grep -E ' /(data|apex|system|dev) '; echo ---SEL; cat /proc/self/attr/current; echo ---SU; ls -l /system/bin/su /system/xbin/su /sbin/su 2>/dev/null; echo ---APEX; ls /apex 2>/dev/null; echo ---RL; readlink -f /system/bin/linker64; readlink -f /system/lib64/linker64"
            ).redirectErrorStream(true).start()
            prepareStop(p)
            val out = p.inputStream.bufferedReader().use { it.readText().trim() }
            "T10 sys: ${out.take(400)}"
        }.getOrElse { "T10 sys FAIL: ${it.message?.take(90)}" }
        val t11 = runCatching {
            val arm32Src = File("/system/bin/linker").takeIf { it.isFile && isElf32(it) }
                ?: File("/system/bin/").listFiles()?.firstOrNull { isElf32(it) }
            val armCopy = File(diagDir, "arm32.test")
            armCopy.delete()
            FileOutputStream(armCopy).use { out ->
                arm32Src!!.inputStream().use { it.copyTo(out) }
            }
            armCopy.setExecutable(true, false)
            Os.chmod(armCopy.absolutePath, 0b111_101_101)
            runDiag("T11 arm32Elf") {
                ProcessBuilder(armCopy.absolutePath, "HL").redirectErrorStream(true).start()
            }
        }.getOrElse { "T11 arm32 write FAIL: ${it.message?.take(90)}" }
        val erLib = File(context.applicationInfo.nativeLibraryDir, "libexec_redirect.so")
        val t12 = if (!erLib.isFile) "T12 no-preload-lib" else runDiag("T12 preloadmemfd") {
            ProcessBuilder("/system/bin/sh", "-c", "${echoCopy.absolutePath} HELLO2")
                .redirectErrorStream(true)
                .apply { environment()["LD_PRELOAD"] = erLib.absolutePath }
                .start()
        }
        val t13 = runCatching {
            val box = effectiveBox64()
            if (box == null) "T13 no-box64"
            else runDiag("T13 box64preload") {
                ProcessBuilder("/system/bin/sh", "-c", "${box.absolutePath} --version")
                    .redirectErrorStream(true)
                    .apply { environment()["LD_PRELOAD"] = erLib.absolutePath }
                    .start()
            }
        }.getOrElse { "T13 FAIL: ${it.message?.take(90)}" }
        val t14 = "n/a"
        val t15 = if (ForkLoader.ensure(context) != null) runCatching {
            val box = effectiveBox64()
            val ld = loaderPath()
            if (box == null || ld == null) "no-box64-or-loader"
            else {
                // 실운영 체인과 동일한 env 사용:
                // FORK_LOADER_NATIVE_DIR = aarch64 glibc lib dirs (x86_64 제외)
                val env = box64Env().toMutableMap().apply {
                    put("BOX64_LOG", "1")
                    put("LD_LIBRARY_PATH", "/system/lib64:/system/lib:" + ld.parentFile.absolutePath)
                }
                // glibc 모드: run_argv=[ld.so, box64] + target_argv 이므로
                // 프로그램은 argv=[box64, --version] 을 받는다.
                // (target_argv에 box64를 또 넣으면 box64가 자기 자신을 에뮬레이션하려 함)
                val code = ForkLoader.runForkLoader(
                    context,
                    box.absolutePath,
                    ld.absolutePath,
                    listOf("--version"),
                    env,
                    timeoutSec = 30
                )
                "exit=$code"
            }
        }.getOrElse { "FAIL ${it.message}" } else "no-fork-loader"
        val msg = "DIAG $t1 | $t2 | $t3 | $t4 | $t5 | $t6 | $t7 | $t8${if (t9.isBlank()) "" else " | $t9"}${if (t10.isBlank()) "" else " | $t10"}${if (t11.isBlank()) "" else " | $t11"} | $t12 | $t13 | T14 fork=$t14 | T15 load=$t15"
        Log.i(TAG, msg)
        onLine(msg)
    }

    private fun realLinkerCandidates() = listOf(
        "/system/bin/linker64",
        "/system/lib64/linker64",
        "/system/lib64/ld-android.so",
        "/system/lib/linker64",
        "/apex/com.android.runtime/lib64/bionic/linker64",
        "/apex/com.android.runtime/bin/linker64",
        "/system/bin/linker",
        "/system/lib64/linker"
    )

    private var cachedRealLinker: File? = null

    private fun realLinker(): File? {
        cachedRealLinker?.let { return it }
        val found = realLinkerCandidates().mapNotNull { f ->
            val file = File(f)
            if (file.exists() && file.canExecute() && isElf64(file)) file else null
        }.firstOrNull()
        cachedRealLinker = found
        return found
    }

    private fun isElf32(file: File): Boolean = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(5)
            raf.readFully(b)
            b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() &&
                b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte() && b[4] == 1.toByte()
        }
    } catch (e: Exception) {
        false
    }

    private fun isElf64(file: File): Boolean = try {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val b = ByteArray(5)
            raf.readFully(b)
            b[0] == 0x7f.toByte() && b[1] == 'E'.code.toByte() &&
                b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte() && b[4] == 2.toByte()
        }
    } catch (e: Exception) {
        false
    }

    fun bootstrapPrefix(onProgress: (String) -> Unit) {
        makeExecutable()
        ensureBox64Valid()
        diagnoseExecEnvironment(onProgress)
        if (isPrefixBooted) return
        val wine64 = wine64Path() ?: throw IllegalStateException("wine64 not installed")

        onProgress("Initializing Wine prefix (first run, can take a few minutes)...")
        var attempts = 0
        while (true) {
            val code = runCommand(
                args = buildBox64Command(listOf(wine64.absolutePath, "wineboot", "-u")),
                cwd = win64Dir,
                env = box64Env(),
                timeoutMs = TimeUnit.MINUTES.toMillis(10),
                onLine = { line -> onProgress(line) }
            )
            if (code == 0) break
            attempts++
            Log.w(TAG, "wineboot exited with code $code (attempt $attempts)")
            if (attempts >= 2) {
                throw IllegalStateException(
                    "wineboot failed with exit code $code (box64/glibc loader may be blocked on this device)"
                )
            }
            onProgress("wineboot failed (code $code) — wiping prefix and retrying once...")
            runCatching { winePrefix.deleteRecursively() }
        }
        File(winePrefix, BOOT_MARKER).writeText("1")
        onProgress("Wine prefix ready")
    }

    fun startWindowsExe(
        exeFile: File,
        args: List<String> = emptyList(),
        cwd: File = win64Dir,
        extraEnv: Map<String, String> = emptyMap()
    ): Process {
        ensureBox64Valid()
        ensureComponentHealth()
        GlibcDownloader.fixExecutableBits(win64Dir)
        makeExecutable()
        val wine64 = wine64Path() ?: throw IllegalStateException("wine64 not installed")
        runCatching { wine64.setExecutable(true, false); wine64.setReadable(true, false) }
        require(exeFile.isFile) { "exe not found: ${exeFile.absolutePath}" }

        // Box64 → Wine64 → EXE (box64는 ARM64 네이티브로 실행, wine64는 box64가 에뮬레이션)
        val command = buildBox64Command(listOf(wine64.absolutePath, exeFile.absolutePath) + args)
        Log.i(TAG, "Start: ${command.joinToString(" ")}")

        return try {
            ExecHelper.createProcess(context, command, cwd, box64Env() + extraEnv).start()
        } catch (e: Exception) {
            val status = command.joinToString(" ") { p ->
                val f = File(p)
                if (f.isFile) "$p(exec=${f.canExecute()},mode=${runCatching { java.lang.Integer.toOctalString(Os.stat(p).st_mode) }.getOrDefault("?")})" else p
            }
            Log.e(TAG, "startWindowsExe failed: ${e.message}", e)
            Log.e(TAG, "command status: $status")
            throw e
        }
    }

    fun runWindowsExe(
        exeFile: File,
        args: List<String> = emptyList(),
        cwd: File = win64Dir,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutMs: Long = TimeUnit.MINUTES.toMillis(30),
        onLine: (String) -> Unit = {}
    ): Int {
        // Initialize EGL for hardware-accelerated rendering
        MiniXServer.eglContextManager?.init()
        Log.i(TAG, "EGL context initialized for hardware acceleration")
        val process = startWindowsExe(exeFile, args, cwd, extraEnv)
        val exeLogDir = File(context.filesDir, "logs/exe").apply { mkdirs() }
        val exeLog = File(exeLogDir, "${exeFile.name}-${System.currentTimeMillis()}.log")
        val writer = runCatching { exeLog.bufferedWriter() }.getOrNull()
        fun persist(line: String) {
            runCatching { writer?.write(line); writer?.newLine(); writer?.flush() }
        }
        persist("=== ${exeFile.name} ${args.joinToString(" ")} ===")
        val timedOut = AtomicBoolean(false)
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    persist(line)
                    Log.i(TAG, "[${exeFile.name}] $line")
                    if (line.contains("error", ignoreCase = true) ||
                        line.contains("failed", ignoreCase = true) ||
                        line.contains("segmentation", ignoreCase = true) ||
                        line.contains("SIGSEGV", ignoreCase = true)) {
                        Log.e(TAG, "[${exeFile.name}] $line")
                    }
                    runCatching { onLine(line) }
                }
            }.onFailure { err ->
                Log.e(TAG, "stream read ended: ${err.message}", err)
                persist("[stream error] ${err.message}")
            }
        }.apply {
            name = "WinExeStreamReader"
            isDaemon = true
            start()
        }
        val done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            timedOut.set(true)
            runCatching { process.destroyForcibly() }
        }
        reader.join(2000)
        val code = if (timedOut.get()) -1 else process.exitValue()
        Log.i(TAG, "${exeFile.name} exit=$code")
        persist("=== exit=$code (timedOut=$timedOut.get()) ===")
        runCatching { writer?.close() }
        Log.i(TAG, "exe log saved: ${exeLog.absolutePath}")
        return code
    }

    /**
     * Windows x86_64 EXE/DLL cross-build — delegates to [WindowsX64BuildBackend]
     * (two-phase: clang-24 -c, then lld -flavor link via /system/bin/linker64).
     *
     * Result is a PE .exe that can be run with [runWindowsExe] (box64 → wine64).
     */
    fun buildWindowsExe(
        source: File,
        onLine: (String) -> Unit = {},
        options: WindowsX64BuildBackend.BuildOptions = WindowsX64BuildBackend.BuildOptions()
    ): File? {
        val backend = WindowsX64BuildBackend(context)
        if (!MingwDownloader.isValidMingw(backend.mingwRoot)) {
            onLine("mingw-w64 sysroot not installed — tap Install first")
            return null
        }
        if (backend.clangPath() == null) {
            // First-time users may not have the LLVM toolchain installed. Auto-download
            // the Android-hosted LLVM instead of failing silently.
            onLine("clang not found — downloading Android-hosted LLVM automatically...")
            try {
                AndroidHostedLlvmInstaller.ensureInstalled(context, emptyList(), { msg, _ ->
                    onLine(msg)
                })
            } catch (e: Exception) {
                onLine("clang download failed: ${e.message}")
                return null
            }
        }
        if (backend.clangPath() == null) {
            onLine("clang not found — install the LLVM toolchain first")
            return null
        }
        val ext = if (options.outputType == WindowsX64BuildBackend.OutputType.DLL) "dll" else "exe"
        val outExe = File(source.parentFile, source.nameWithoutExtension + "." + ext)
        return backend.build(source, outExe, options, onLine)
    }

    fun versionString(): String? {
        if (cachedVersion != null) return cachedVersion
        if (!isInstalled) return null
        ensureBox64Valid()
        val wine64 = wine64Path()!!
        return try {
            val cmd = buildBox64Command(listOf(wine64.absolutePath, "--version"))
            val pb = ExecHelper.createProcess(context, cmd, win64Dir, box64Env())
                .redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor(60, TimeUnit.SECONDS)
            cachedVersion = if (out.isBlank()) null else out.take(120)
            cachedVersion
        } catch (e: Exception) {
            Log.w(TAG, "version check failed: ${e.message}")
            null
        }
    }

    fun cleanup() {
        win64Dir.deleteRecursively()
        cachedLoader = null; cachedBox64 = null; cachedWine64 = null
        cachedX86LibDir = null; cachedWineUnixDir = null; cachedVersion = null
    }

    /**
     * App-data script files cannot be execve()d on this ROM (EACCES), but
     * /system/bin/sh can. If args[0] is a shebang script, launch it through sh.
     */
    private fun adjustArgsForExec(args: List<String>): List<String> {
        if (args.isEmpty()) return args
        val first = File(args[0])
        if (!first.isFile) return args
        val shebang = try { first.bufferedReader().use { it.readLine() } } catch (e: Exception) { null }
        if (shebang?.startsWith("#!") != true) return args
        val sh = File("/system/bin/sh")
        if (sh.exists() && sh.canExecute()) {
            return listOf(sh.absolutePath, args[0]) + args.drop(1)
        }
        return args
    }

    private fun runCommand(
        args: List<String>,
        cwd: File,
        env: Map<String, String>,
        timeoutMs: Long,
        onLine: (String) -> Unit
    ): Int {
        // Route through ExecHelper so the exec strategy is chosen per binary:
        // native-lib dir → direct exec, app-data bionic → linker64, app-data
        // glibc → ld-linux. A raw ProcessBuilder here bypassed that routing and
        // failed with EACCES on ROMs that block app-data execve (Android 16).
        val effectiveArgs = adjustArgsForExec(args)
        val process = try {
            ExecHelper.createProcess(context, effectiveArgs, cwd, env)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Log.e(TAG, "runCommand start failed: ${e.message}", e)
            Log.e(TAG, "args: ${args.joinToString(" ") { p ->
                val f = File(p)
                if (f.isFile) "$p(exec=${f.canExecute()},mode=${runCatching { java.lang.Integer.toOctalString(Os.stat(p).st_mode) }.getOrDefault("?")})" else p
            }}")
            throw e
        }
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { line -> onLine(line) } }
        }.apply {
            name = "WinExeCmdReader"
            isDaemon = true
            start()
        }
        val done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) runCatching { process.destroyForcibly() }
        reader.join(2000)
        return if (done) process.exitValue() else -1
    }

    private fun extractTarXz(archive: File, dest: File, onProgress: (String) -> Unit) {
        dest.mkdirs()
        try {
            XZCompressorInputStream(archive.inputStream().buffered()).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var stripPrefix: String? = null
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        if (stripPrefix == null) {
                            stripPrefix = if (entry.name.contains('/')) entry.name.substringBefore('/') + "/" else ""
                        }
                        val rel = entry.name.removePrefix(stripPrefix)
                        if (rel.isBlank()) continue
                        val target = File(dest, rel)
                        if (entry.isSymbolicLink) {
                            target.parentFile?.mkdirs()
                            runCatching { Os.symlink(entry.linkName, target.absolutePath) }
                        } else if (entry.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> tar.copyTo(out) }
                            // Restore exec bits from the tar entry mode — mobox packages ship
                            // 755 binaries, and without this every execve() fails with EACCES
                            // (observed: "Cannot run program ... ld-linux-aarch64.so.1
                            // Permission denied" when launching wine).
                            val mode = entry.mode
                            if (mode != 0) {
                                val execBits = mode and 0b001_001_001
                                if (execBits != 0) {
                                    runCatching { target.setExecutable(true, false); target.setReadable(true, false) }
                                    runCatching { Os.chmod(target.absolutePath, (mode and 0b111_111_111)) }
                                }
                            }
                            onProgress("Extracting: $rel")
                        }
                    }
                }
            }
        } catch (e: java.io.EOFException) {
            throw java.io.EOFException("Extraction failed: premature end of XZ stream (archive truncated)")
        }
    }

    private fun findFileRecursive(root: File, name: String): File? {
        if (!root.isDirectory) return null
        return root.walkTopDown().firstOrNull {
            it.isFile && it.name == name && it.length() > 0L
        }
    }

    private fun findDirRecursive(root: File, name: String): File? {
        if (!root.isDirectory) return null
        return root.walkTopDown().firstOrNull {
            it.isDirectory && it.name == name
        }
    }

    private fun downloadFile(url: String, dest: File, onProgress: (String) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000; readTimeout = 120_000
            instanceFollowRedirects = true; connect()
        }
        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}: $url" }
        val total = conn.contentLengthLong
        var done = 0L
        dest.parentFile?.mkdirs()
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    output.write(buf, 0, read)
                    done += read
                    if (total > 0 && done % (2 * 1024 * 1024) < buf.size) {
                        onProgress("Downloaded ${done / (1024 * 1024)}MB / ${total / (1024 * 1024)}MB")
                    }
                }
                if (total > 0 && done < total) {
                    throw java.io.EOFException("Download incomplete: $done / $total bytes received")
                }
            }
        }
        conn.disconnect()
    }

    private fun copyForkLoader() {
        val targetFile = File(win64Dir, "fork_loader")
        try {
            // Try to extract from assets (bundled in APK)
            val assetName = "fork_loader"
            val inputStream = context.assets.open(assetName)
            targetFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            targetFile.setExecutable(true, false)
            runCatching { Os.chmod(targetFile.absolutePath, 0b111_101_101) }
            Log.d(TAG, "Extracted fork_loader from assets (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract fork_loader from assets: ${e.message}")
            // Fallback: try to find in native lib dir
            val fallbackSrc = File(context.applicationInfo.nativeLibraryDir, "fork_loader")
            if (fallbackSrc.exists() && fallbackSrc.length() > 0) {
                try {
                    fallbackSrc.copyTo(File(win64Dir, "fork_loader"), overwrite = true)
                    targetFile.setExecutable(true, false)
                    runCatching { Os.chmod(targetFile.absolutePath, 0b111_101_101) }
                    Log.d(TAG, "Copied fork_loader from native lib dir")
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to copy fork_loader from native lib: ${e2.message}")
                }
            } else {
                Log.w(TAG, "fork_loader not found in assets or native lib dir")
            }
        }
    }
}
