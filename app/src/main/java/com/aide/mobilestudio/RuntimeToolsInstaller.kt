package com.aide.mobilestudio

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object RuntimeToolsInstaller {

    private const val TAG = "RuntimeTools"
    private const val GITHUB_BASE = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download"
    private const val TOOLS_VERSION = "studio-tools-v1"
    private const val TOOLS_ARCHIVE = "studio-tools-arm64.tar.gz"
    private const val TOOLS_SHA256 = "d95dac2b41db25ac17b3eb1c70667a637309dfeebe2347e53f0ff9e1622dbc00"

    data class ToolInfo(
        val name: String,
        val description: String,
        val binaries: List<String>,
        val isStatic: Boolean = true
    )

    val ALL_TOOLS = mapOf(
        "tree" to ToolInfo("tree", "디렉토리 구조 표시", listOf("tree")),
        "jsonpp" to ToolInfo("jsonpp", "JSON 프리티프린터", listOf("jsonpp")),
        "watch" to ToolInfo("watch", "명령어 반복 실행", listOf("watch")),
        "b64" to ToolInfo("b64", "Base64 인코더/디코더", listOf("b64")),
        "urlcodec" to ToolInfo("urlcodec", "URL 인코더/디코더", listOf("urlcodec")),
        "hexview" to ToolInfo("hexview", "헥스 뷰어", listOf("hexview")),
        "seq" to ToolInfo("seq", "숫자 시퀀스 생성", listOf("seq")),
        "realpath" to ToolInfo("realpath", "절대 경로 해석", listOf("realpath")),
        "nano" to ToolInfo("nano", "텍스트 편집기", listOf("nano"), isStatic = false),
        "less" to ToolInfo("less", "페이저", listOf("less"), isStatic = false),
        "jq" to ToolInfo("jq", "JSON 프로세서", listOf("jq"), isStatic = false),
        "htop" to ToolInfo("htop", "프로세스 뷰어", listOf("htop"), isStatic = false),
        "file" to ToolInfo("file", "파일 타입 감지", listOf("file"), isStatic = false),
        "strace" to ToolInfo("strace", "시스템콜 추적", listOf("strace"), isStatic = false)
    )

    // Legacy tools (from assets)
    data class ToolDef(
        val name: String,
        val packageName: String,
        val binaryNames: List<String>
    )

    val LEGACY_TOOLS = listOf(
        ToolDef("busybox", "busybox", listOf("busybox")),
        ToolDef("curl", "curl", listOf("curl")),
        ToolDef("ripgrep", "ripgrep", listOf("rg")),
        ToolDef("git", "git", listOf("git")),
        ToolDef("tar", "tar", listOf("tar")),
        ToolDef("unzip", "unzip", listOf("unzip", "funzip"))
    )

    private const val RUNTIME_TOOLS_DIR = "toolchain/runtime-tools"

    fun toolsDir(context: Context): File = File(context.filesDir, RUNTIME_TOOLS_DIR)
    fun binDir(context: Context): File = File(toolsDir(context), "bin")
    fun libDir(context: Context): File = File(toolsDir(context), "lib")
    fun pythonDir(context: Context): File = File(toolsDir(context), "python")

    val BIN_FILES = listOf("busybox", "curl", "funzip", "git", "rg", "tar", "unzip", "adb")
    val LIB_FILES = listOf("libbusybox.so.1.38.0", "libandroid-selinux.so", "libpcre2-8.so")
    val PYTHON_FILES = listOf(
        "libpython3.so", "libpyroot3.so", "libpywhls.so",
        "libastyle.so", "libtermux.so", "libapplet_clear.so"
    )

    fun areAllToolsInstalled(context: Context): Boolean {
        val bin = binDir(context)
        if (!bin.isDirectory) return false
        for (name in BIN_FILES) {
            if (!File(bin, name).isFile) return false
        }
        // Applet wrappers embed the applet name (`exec linker64 busybox mkdir
        // "$@"`); older installs wrote a nameless form that turned
        // `mkdir -p x` into `busybox -p x`.  Stale content must re-trigger
        // installAll so setupBusybox rewrites the wrappers in place.
        val mkdir = File(bin, "mkdir")
        if (mkdir.isFile) {
            val text = runCatching { mkdir.readText() }.getOrDefault("")
            if (!text.contains("busybox mkdir \"\$@\"")) return false
        }
        val lib = libDir(context)
        if (!lib.isDirectory) return false
        for (name in LIB_FILES) {
            if (!File(lib, name).isFile) return false
        }
        return true
    }

    fun installAll(context: Context, onProgress: (String) -> Unit) {
        val errors = mutableListOf<String>()

        onProgress("Copying binaries...")
        copyFiles(context, "tools/bin", BIN_FILES, binDir(context), errors)
        chmod755(binDir(context))

        onProgress("Copying libraries...")
        copyFiles(context, "tools/lib", LIB_FILES, libDir(context), errors)

        val busybox = File(binDir(context), "busybox")
        if (busybox.exists()) {
            onProgress("Setting up busybox...")
            setupBusybox(context, busybox.absolutePath, errors)
            chmod755(libDir(context))
        }

        onProgress("Copying Python...")
        copyFiles(context, "tools/python", PYTHON_FILES, pythonDir(context), errors)
        chmod755(pythonDir(context))

        onProgress("Installing studio tools...")
        installStudioToolsFromGitHub(context, onProgress, errors)

        if (errors.isEmpty()) {
            onProgress("Tools ready")
        } else {
            onProgress("Tools ready (${errors.size} warnings)")
        }
    }

    /**
     * Install a specific tool from GitHub releases.
     * Called from StudioShell: tools install tree
     */
    fun installTool(context: Context, toolName: String, onProgress: (String) -> Unit): Boolean {
        val toolInfo = ALL_TOOLS[toolName]
        if (toolInfo == null) {
            onProgress("Unknown tool: $toolName")
            onProgress("Available tools: ${ALL_TOOLS.keys.joinToString(", ")}")
            return false
        }

        val bin = binDir(context)
        val marker = File(bin, ".$toolName-installed")
        if (marker.exists()) {
            onProgress("$toolName already installed")
            return true
        }

        onProgress("Installing $toolName from GitHub...")
        val errors = mutableListOf<String>()

        try {
            downloadAndExtractTools(context, onProgress, errors)

            // Set up linker64 wrapper for dynamic tools
            if (!toolInfo.isStatic) {
                val libDir = libDir(context)
                for (binary in toolInfo.binaries) {
                    val toolFile = File(bin, binary)
                    if (toolFile.exists()) {
                        createLinker64Wrapper(toolFile, libDir, errors)
                    }
                }
            }

            chmod755(bin)
            marker.writeText(TOOLS_VERSION)
            onProgress("$toolName installed successfully")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to install $toolName: ${e.message}")
            onProgress("Failed to install $toolName: ${e.message}")
            return false
        }
    }

    /**
     * List all available tools with their installation status
     */
    fun listTools(context: Context): List<Triple<String, String, Boolean>> {
        val bin = binDir(context)
        return ALL_TOOLS.map { (name, info) ->
            val installed = File(bin, ".$name-installed").exists() || 
                           info.binaries.all { File(bin, it).exists() }
            Triple(name, info.description, installed)
        }
    }

    /**
     * Download tools from GitHub releases and extract to runtime-tools/bin/
     */
    private fun downloadAndExtractTools(context: Context, onProgress: (String) -> Unit, errors: MutableList<String>) {
        val bin = binDir(context)
        val versionMarker = File(bin, ".$TOOLS_VERSION")

        // Check if already installed with current version
        if (versionMarker.exists() && bin.listFiles()?.isNotEmpty() == true) {
            onProgress("Studio tools already installed (version $TOOLS_VERSION)")
            return
        }

        val url = "$GITHUB_BASE/$TOOLS_VERSION/$TOOLS_ARCHIVE"
        onProgress("Downloading studio tools from GitHub...")
        onProgress("URL: $url")

        val tmpFile = File(context.cacheDir, TOOLS_ARCHIVE)
        try {
            downloadFile(url, tmpFile, onProgress)

            // Verify SHA-256 if possible
            onProgress("Extracting tools...")
            val extractDir = File(context.cacheDir, "studio-tools-extract")
            extractDir.mkdirs()
                JdkDownloader.extractTarGz(tmpFile, extractDir, { msg -> onProgress("  $msg") })
            tmpFile.delete()

            // Copy extracted files to binDir
            val extractedBin = File(extractDir, "bin")
            if (extractedBin.isDirectory) {
                for (f in extractedBin.listFiles() ?: emptyArray()) {
                    if (f.isFile) {
                        f.copyTo(File(bin, f.name), overwrite = true)
                        f.setExecutable(true, false)
                    }
                }
            }
            extractDir.deleteRecursively()

            // Mark as installed
            versionMarker.writeText(TOOLS_VERSION)
            onProgress("Studio tools installed (version $TOOLS_VERSION)")
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    /**
     * Install studio tools from bundled studio-tools-arm64.tar.gz (fallback if GitHub unavailable).
     */
    private fun installStudioToolsFromGitHub(context: Context, onProgress: (String) -> Unit, errors: MutableList<String>) {
        val bin = binDir(context)
        val marker = File(bin, ".$TOOLS_VERSION")
        if (marker.exists() && bin.listFiles()?.isNotEmpty() == true) return

        try {
            downloadAndExtractTools(context, onProgress, errors)

            // Set up linker64 wrappers for dynamic binaries
            val libDir = libDir(context)
            for ((name, info) in ALL_TOOLS) {
                if (!info.isStatic) {
                    for (binary in info.binaries) {
                        val toolFile = File(bin, binary)
                        if (toolFile.exists()) {
                            createLinker64Wrapper(toolFile, libDir, errors)
                        }
                    }
                }
            }

            chmod755(bin)
            onProgress("Studio tools installed")
        } catch (e: Exception) {
            Log.w(TAG, "GitHub download failed, trying bundled assets: ${e.message}")
            onProgress("GitHub download failed, trying bundled assets...")
            installStudioToolsFromAssets(context, onProgress, errors)
        }
    }

    /**
     * Fallback: install from bundled assets (for offline usage)
     */
    private fun installStudioToolsFromAssets(context: Context, onProgress: (String) -> Unit, errors: MutableList<String>) {
        val bin = binDir(context)
        val marker = File(bin, ".$TOOLS_VERSION")
        if (marker.exists()) return

        try {
            context.assets.open("studio-tools/$TOOLS_ARCHIVE").use { input ->
                val tmpFile = File(context.cacheDir, TOOLS_ARCHIVE)
                tmpFile.outputStream().use { output -> input.copyTo(output) }

                val extractDir = File(context.cacheDir, "studio-tools-extract")
                extractDir.mkdirs()
            JdkDownloader.extractTarGz(tmpFile, extractDir, { msg -> onProgress("  $msg") })
                tmpFile.delete()

                val extractedBin = File(extractDir, "bin")
                if (extractedBin.isDirectory) {
                    for (f in extractedBin.listFiles() ?: emptyArray()) {
                        if (f.isFile) {
                            f.copyTo(File(bin, f.name), overwrite = true)
                            f.setExecutable(true, false)
                        }
                    }
                }
                extractDir.deleteRecursively()
            }

            val libDir = libDir(context)
            for ((name, info) in ALL_TOOLS) {
                if (!info.isStatic) {
                    for (binary in info.binaries) {
                        val toolFile = File(bin, binary)
                        if (toolFile.exists()) {
                            createLinker64Wrapper(toolFile, libDir, errors)
                        }
                    }
                }
            }

            chmod755(bin)
            marker.writeText(TOOLS_VERSION)
            onProgress("Studio tools installed from assets")
        } catch (e: Exception) {
            Log.w(TAG, "Studio tools install failed: ${e.message}")
            errors.add("studio-tools: ${e.message}")
        }
    }

    private fun createLinker64Wrapper(binaryFile: File, libDir: File, errors: MutableList<String>) {
        val realFile = File(binaryFile.parentFile, "${binaryFile.name}.real")
        if (realFile.exists()) return

        try {
            binaryFile.renameTo(realFile)
            val libPath = "${libDir.absolutePath}\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
            val wrapperContent = """#!/system/bin/sh
LD_LIBRARY_PATH=$libPath exec /system/bin/linker64 ${realFile.absolutePath} "$@"
"""
            binaryFile.writeText(wrapperContent)
            binaryFile.setExecutable(true, false)
            Os.chmod(binaryFile.absolutePath, 0b111_101_101)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create linker64 wrapper for $binaryFile: ${e.message}")
            errors.add("wrapper-${binaryFile.name}: ${e.message}")
        }
    }

    private fun downloadFile(urlStr: String, target: File, onProgress: (String) -> Unit) {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()

        if (conn.responseCode != 200) {
            throw Exception("HTTP ${conn.responseCode} for $urlStr")
        }

        val total = conn.contentLength
        conn.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buf = ByteArray(8192)
                var read: Int
                var totalRead = 0
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    totalRead += read
                    if (total > 0) {
                        val pct = (totalRead * 100) / total
                        onProgress("Downloaded $pct% ($totalRead / $total)")
                    }
                }
            }
        }
        conn.disconnect()
    }

    private fun setupBusybox(context: Context, busyboxPath: String, errors: MutableList<String>) {
        val bin = binDir(context)
        val lib = libDir(context)
        val wrapper = File(bin, "busybox-wrapper.sh")
        val wrapperContent = """#!/system/bin/sh
LD_LIBRARY_PATH=${lib.absolutePath}${File.pathSeparator}${'$'}{LD_LIBRARY_PATH:-} exec /system/bin/linker64 ${busyboxPath} "${'$'}@"
"""
        if (!wrapper.exists() || wrapper.readText() != wrapperContent) {
            wrapper.writeText(wrapperContent)
            runCatching { Os.chmod(wrapper.absolutePath, 0b111_101_101) }
            wrapper.setExecutable(true, false)
        }

        val appletDir = File(bin, "applets")
        appletDir.mkdirs()
        try {
            val libPath = "${lib.absolutePath}${File.pathSeparator}${'$'}{LD_LIBRARY_PATH:-}"
            val pb = ProcessBuilder(
                "/system/bin/sh", "-c",
                "LD_LIBRARY_PATH=$libPath /system/bin/linker64 $busyboxPath --install ${appletDir.absolutePath}"
            )
            pb.directory(toolsDir(context))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                Log.w(TAG, "busybox --install exit=$exit: $output")
                errors.add("busybox --install: exit=$exit")
            }
        } catch (e: Exception) {
            Log.w(TAG, "busybox --install failed: ${e.message}")
            errors.add("busybox --install: ${e.message}")
        }

        val commonApplets = listOf(
            "sh", "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "pwd", "echo", "printf",
            "test", "true", "false",
            "head", "tail", "sort", "uniq", "wc", "cut", "tr", "sed", "awk", "grep", "egrep", "fgrep",
            "diff", "cmp", "tee", "xargs",
            "chmod", "chown", "chgrp", "ln", "find", "du", "df", "touch", "stat",
            "readlink", "basename", "dirname",
            "tar", "gzip", "gunzip", "zcat", "bzip2", "bunzip2", "xz", "unzip", "funzip", "zip",
            "wget", "curl", "ping", "ifconfig", "netstat", "nslookup", "scp",
            "ps", "kill", "killall", "nice", "nohup", "sleep",
            "export", "source", "eval", "exec", "set", "unset", "alias", "env", "which", "type",
            "uname", "hostname", "id", "whoami", "groups", "logname",
            "mount", "umount", "fdisk", "losetup",
            "date", "cal", "yes", "factor", "clear", "reset",
            "hexdump", "od", "xxd", "strings",
            "vi", "vim", "ed",
            "more",
            "ar", "strings", "nm", "readelf", "objdump", "strip"
        )
        for (applet in commonApplets) {
            val f = File(bin, applet)
            // Each applet wrapper must pass its own applet name: when the shell
            // runs `mkdir -p x`, the script is invoked as `mkdir` with $@="-p x",
            // so `exec busybox "$@"` would call `busybox -p x` ("-p: applet not
            // found").  Rewrite when stale — older installs wrote the nameless
            // form.
            val appletLibPath = "${lib.absolutePath}${File.pathSeparator}" +
                "${'$'}{LD_LIBRARY_PATH:-}"
            val appletContent = """#!/system/bin/sh
LD_LIBRARY_PATH=$appletLibPath exec /system/bin/linker64 $busyboxPath $applet "${'$'}@"
"""
            if (!f.exists() || f.readText() != appletContent) {
                f.writeText(appletContent)
                runCatching { Os.chmod(f.absolutePath, 0b111_101_101) }
                f.setExecutable(true, false)
            }
        }
    }

    fun execBusybox(context: Context, applet: String, vararg args: String): Process {
        val wrapper = File(binDir(context), "busybox-wrapper.sh")
        val fullArgs = listOf("/system/bin/sh", wrapper.absolutePath, applet) + args.toList()
        return ProcessBuilder(fullArgs)
            .directory(toolsDir(context))
            .redirectErrorStream(true)
            .start()
    }

    private fun chmod755(dir: File) {
        for (f in dir.listFiles() ?: return) {
            if (!f.isFile) continue
            try {
                Os.chmod(f.absolutePath, 0b111_101_101)
            } catch (_: Exception) {
                f.setExecutable(true, false)
            }
        }
    }

    private fun copyFiles(
        context: Context,
        assetPrefix: String,
        fileNames: List<String>,
        destDir: File,
        errors: MutableList<String>
    ) {
        destDir.mkdirs()
        for (name in fileNames) {
            val child = File(destDir, name)
            val assetPath = "$assetPrefix/$name"

            var copied = try {
                context.assets.open(assetPath).use { input ->
                    child.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                child.setExecutable(true, false)
                true
            } catch (e: Exception) {
                false
            }

            if (!copied) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                val src = File(nativeLibDir, name)
                if (src.exists()) {
                    try {
                        src.copyTo(child, overwrite = true)
                        child.setExecutable(true, false)
                        copied = true
                    } catch (_: Exception) {}
                }
            }

            if (!copied && (name == "libandroid-selinux.so" || name == "libpcre2-8.so")) {
                // libpcre2-8.so ships in Termux's `pcre2` package — there is
                // no `libpcre2` package, that lookup always failed with
                // "Package not found" and left busybox unable to link.
                val pkgName = if (name == "libandroid-selinux.so") "libandroid-selinux" else "pcre2"
                Log.i(TAG, "Attempting to download missing $name from Termux repo...")
                try {
                    val installer = JreGradleInstaller(context, {}, {})
                    val method = installer.javaClass.getDeclaredMethod("installTermuxLib", String::class.java, File::class.java, List::class.java)
                    method.isAccessible = true
                    method.invoke(installer, pkgName, destDir, listOf(name))
                    if (child.exists()) copied = true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to auto-download $name: ${e.message}")
                }
            }

            if (!copied) {
                errors.add("$name: Not found in assets or native libs")
            }
        }
    }

    fun allBinDirPaths(context: Context): List<String> {
        val dirs = mutableListOf<String>()
        val bin = binDir(context)
        if (bin.isDirectory) dirs.add(bin.absolutePath)
        return dirs
    }
}
