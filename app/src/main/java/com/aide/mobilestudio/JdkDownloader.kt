package com.aide.mobilestudio

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Downloads JDK from GitHub Release.
 * Custom-built OpenJDK 17/21 for Android ARM64 (aarch64) with Bionic libc.
 * No Termux dependencies, uses /system/bin/linker64 directly.
 */
object JdkDownloader {
    private const val TAG = "JdkDownloader"
    /** Bump this when the linker wrapper format changes to force regeneration */
    const val WRAPPER_FORMAT_VERSION = 13

    // Minimum plausible archive sizes per JDK major (download sanity check).
    private val MIN_SIZE_BY_MAJOR = mapOf(
        21 to 200_000_000L,
        17 to 180_000_000L
    )
    private const val DEFAULT_MIN_SIZE = 150_000_000L

    private const val JDK_VERSION = 21  // default version

    /** URL + SHA-256 + size floor for a JDK major, resolved from the dynamic
     *  catalog with the bundled legacy fallbacks (keeps existing installs
     *  working when the network/catalog has nothing). */
    private data class JdkConfig(
        val url: String,
        val sha256: String?,
        val minSize: Long
    )

    private fun resolveConfig(context: Context?, version: Int): JdkConfig? {
        val catalog = context?.let { ctx ->
            runCatching { BuildToolCatalog.fetch(ctx) }.getOrNull()
        } ?: BuildToolCatalog.Catalog(emptyList(), emptyList(), emptyList(), 0L)
        return configFromCatalog(catalog, version)
    }

    private fun configFromCatalog(catalog: BuildToolCatalog.Catalog, version: Int): JdkConfig? {
        val entry = BuildToolCatalog.findJdk(catalog, version)
        val fallback = BuildToolCatalog.FALLBACK_JDK.firstOrNull { it.version == version.toString() }
        val url = entry?.url ?: fallback?.url ?: return null
        // Trust a catalog hash; otherwise only reuse the bundled hash when the
        // URL is exactly the legacy asset it was computed for.
        val sha256 = entry?.sha256
            ?: fallback?.takeIf { it.url == url }?.sha256
        return JdkConfig(url, sha256, MIN_SIZE_BY_MAJOR[version] ?: DEFAULT_MIN_SIZE)
    }

    data class JdkResult(
        val dir: File?,
        val success: Boolean,
        val message: String
    )

    /**
     * Ensures a valid JDK exists at the given directory.
     * If missing or corrupted, downloads from GitHub Release and extracts.
     */
    fun ensureJdk(
        context: Context,
        jdkDir: File,
        version: Int = JDK_VERSION,
        onProgress: ((String) -> Unit)? = null
    ): JdkResult {
        val config = resolveConfig(context, version)
            ?: return JdkResult(null, false, "Unsupported JDK version: $version")

        // Check if already valid (has lib/server/libjvm.so)
        if (isValidJdk(jdkDir)) {
            onProgress?.invoke("JDK $version already valid")
            return JdkResult(jdkDir, true, "Already installed")
        }

        val url = config.url
        onProgress?.invoke("Downloading JDK $version from GitHub...")
        Log.i(TAG, "JDK $version download: $url")
        val tmpFile = File(jdkDir.parentFile, "jdk-$version-android.tar.gz.tmp")
        val archiveName = url.substringAfterLast('/').ifBlank { "jdk-$version-android.tar.gz" }
        val totalBytes = DownloadCenter.probeContentLength(url)
        if (!DownloadCenter.confirm("JDK $version", url, jdkDir.absolutePath, archiveName, totalBytes)) {
            onProgress?.invoke("JDK $version download declined")
            return JdkResult(null, false, "Download declined")
        }
        DownloadCenter.begin("JDK $version", url, jdkDir.absolutePath, archiveName, totalBytes)
        try {
            if (jdkDir.exists()) {
                jdkDir.deleteRecursively()
            }
            jdkDir.mkdirs()

            downloadFile(url, tmpFile, onProgress)

            // Verify minimum size
            if (tmpFile.length() < config.minSize) {
                val actualSize = tmpFile.length()
                tmpFile.delete()
                val msg = "JDK file too small ($actualSize bytes), expected > ${config.minSize}"
                onProgress?.invoke(msg)
                Log.w(TAG, msg)
                return JdkResult(null, false, msg)
            }

            // Verify SHA256 (when a hash is known for this build; the dynamic
            // catalog can carry JDK builds whose checksums are not published)
            val expectedSha256 = config.sha256
            if (expectedSha256 != null) {
                val sha256 = computeSha256(tmpFile)
                if (sha256 != expectedSha256) {
                    tmpFile.delete()
                    val msg = "SHA256 mismatch: expected $expectedSha256, got $sha256"
                    onProgress?.invoke(msg)
                    Log.w(TAG, msg)
                    return JdkResult(null, false, msg)
                }
            } else {
                val msg = "No SHA-256 available for JDK $version — hash verification skipped"
                onProgress?.invoke(msg)
                Log.w(TAG, msg)
            }

            // Extract
            onProgress?.invoke("Extracting JDK $version...")
            DownloadCenter.stage("Extracting JDK $version...")
            extractTarGz(tmpFile, jdkDir, onProgress, File(context.applicationInfo.nativeLibraryDir))
            tmpFile.delete()

            // Verify after extraction
            if (!isValidJdk(jdkDir, version)) {
                onProgress?.invoke("Extracted JDK invalid")
                return JdkResult(null, false, "Extracted JDK validation failed")
            }

            context.getSharedPreferences("ide", Context.MODE_PRIVATE)
                .edit().putInt("installed_jdk_version", version).apply()

            onProgress?.invoke("JDK $version installed successfully")
            DownloadCenter.done("JDK $version installed")
            return JdkResult(jdkDir, true, "Downloaded and installed")

        } catch (e: Exception) {
            tmpFile.delete()
            val msg = "JDK download failed: ${e.message}"
            onProgress?.invoke(msg)
            DownloadCenter.fail(msg)
            Log.w(TAG, msg, e)
            return JdkResult(null, false, msg)
        }
    }

    /**
     * Validates that the JDK directory has a working libjvm.so and matches version 21.
     */
    fun isValidJdk(dir: File, expectedVersion: Int = -1): Boolean {
        return try {
            val jvmSo = File(dir, "lib/server/libjvm.so")
            val releaseFile = File(dir, "release")
            
            val basicOk = jvmSo.exists() && jvmSo.length() > 1000000L && isValidElf64(jvmSo)
            if (!basicOk) return false
            
            if (expectedVersion != -1) {
                if (!releaseFile.exists()) {
                    Log.d(TAG, "isValidJdk: release file missing, cannot verify version $expectedVersion")
                    return false
                }
                val content = releaseFile.readText()
                val versionLine = content.lines().firstOrNull { it.startsWith("JAVA_VERSION=") }
                if (versionLine != null) {
                    val actualVerStr = versionLine.substringAfter("=").removeSurrounding("\"")
                    val actualMajor = actualVerStr.split(".").firstOrNull()?.toIntOrNull() 
                        ?: if (actualVerStr.startsWith("1.8")) 8 else -1
                    if (actualMajor != -1 && actualMajor != expectedVersion) {
                        Log.d(TAG, "isValidJdk: version mismatch, expected $expectedVersion but found $actualMajor ($actualVerStr)")
                        return false
                    }
                } else {
                    Log.d(TAG, "isValidJdk: JAVA_VERSION not found in release file")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
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

    private fun downloadFile(urlStr: String, target: File, onProgress: ((String) -> Unit)?) {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 600_000  // 10 min for 244MB
        conn.instanceFollowRedirects = true

        var connection = conn
        var redirects = 0
        while (redirects < 5) {
            val code = connection.responseCode
            if (code in 301..308) {
                val location = connection.getHeaderField("Location") ?: break
                connection.disconnect()
                connection = URL(location).openConnection() as HttpURLConnection
                connection.connectTimeout = 30_000
                connection.readTimeout = 600_000
                redirects++
            } else {
                break
            }
        }

        if (connection.responseCode != 200) {
            val errorMsg = when (connection.responseCode) {
                404 -> "404 Not Found"
                500 -> "500 Internal Server Error"
                503 -> "503 Service Unavailable"
                else -> "HTTP ${connection.responseCode}"
            }
            throw Exception("$errorMsg for $urlStr")
        }

        val totalBytes = connection.contentLength.toLong()
        DownloadCenter.progress(0L, totalBytes)
        var totalRead = 0L
        var lastUpdate = 0L
        connection.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    if (DownloadCenter.isCancelled()) {
                        throw java.io.InterruptedIOException("Download cancelled by user")
                    }
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 250L) {
                        lastUpdate = now
                        DownloadCenter.progress(totalRead, totalBytes)
                    }
                }
                output.flush()
            }
        }
        DownloadCenter.progress(totalRead, totalBytes)
        connection.disconnect()
    }

    private fun computeSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun extractTarGz(archive: File, dest: File, onProgress: ((String) -> Unit)?, nativeLibDirOverride: File? = null) {
        dest.mkdirs()
        GzipCompressorInputStream(archive.inputStream().buffered()).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
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
                        val mode = entry.mode
                        if (mode != 0) {
                            val execBits = mode and 0b001_001_001
                            if (execBits != 0) {
                                runCatching { target.setExecutable(true, false); target.setReadable(true, false) }
                                runCatching { Os.chmod(target.absolutePath, (mode and 0b111_111_111)) }
                            }
                        }
                        onProgress?.invoke("Extracting: $rel")
                    }
                }
            }
        }
        // After extraction, create linker64 wrappers for ELF binaries in bin/ and lib/
        setupLinkerWrappers(dest, onProgress, nativeLibDirOverride)
        // Write wrapper format marker so future APK upgrades can detect stale wrappers
        runCatching {
            File(dest, ".wrapper_format_version").writeText(WRAPPER_FORMAT_VERSION.toString())
        }
    }

    /**
     * Creates shell wrapper scripts for ELF binaries so they can be executed from /data
     * (which is typically mounted noexec). The wrapper uses /system/bin/linker64 to load
     * the binary, bypassing the noexec restriction.
     *
     * For each ELF binary like `bin/java`:
     * 1. Renames to `bin/java.real`
     * 2. Creates `bin/java` as a shell script wrapper
     */
    fun setupLinkerWrappers(jdkHome: File, onProgress: ((String) -> Unit)?, nativeLibDirOverride: File? = null) {
        // Use the app's ACTUAL nativeLibraryDir (executable mount) — never a derived path
        val nativeLibDir = nativeLibDirOverride ?: File(jdkHome, "lib")

        // Find all ELF binaries in bin/ directory — and also rewrite STALE wrapper
        // scripts (not ELF, so the old isElfBinary gate skipped them forever).
        // Happens when <tool>.real was removed (Termux-recovery) while the old
        // script stayed: version-bump regeneration could never heal it, because
        // restoreBinaries only acts when a .real exists. The rewritten script gets
        // the javac.real fallback anchor, so it works even without its own .real.
        val binDir = File(jdkHome, "bin")
        if (binDir.isDirectory) {
            binDir.listFiles()?.forEach { file ->
                if (file.isFile && file.canRead() &&
                    (isElfBinary(file) || isStaleWrapperScript(file))) {
                    createLinkerWrapper(file, jdkHome, nativeLibDir, onProgress)
                }
            }
        }

        // Also handle lib/jli/libjli.so if it exists as an executable
        val jliDir = File(jdkHome, "lib/jli")
        if (jliDir.isDirectory) {
            jliDir.listFiles()?.forEach { file ->
                if (file.isFile && isElfBinary(file) && !file.name.endsWith(".so")) {
                    createLinkerWrapper(file, jdkHome, nativeLibDir, onProgress)
                }
            }
        }
    }

    /**
     * Regenerate linker wrappers for a JDK directory if the wrapper format version
     * has changed. This handles the case where the APK is upgraded with a new wrapper
     * format (e.g. adding LD_PRELOAD for linker namespace fix) but the JDK was
     * installed by an older APK version.
     *
     * @param jdkHome The JDK home directory (contains bin/, lib/)
     */
    fun regenerateLinkerWrappers(jdkHome: File, onProgress: ((String) -> Unit)?, nativeLibDirOverride: File? = null) {
        val markerFile = File(jdkHome, ".wrapper_format_version")
        val storedVersion = if (markerFile.exists()) markerFile.readText().trim().toIntOrNull() ?: 0 else 0

        // Content check in addition to the marker: a script can be replaced/stripped
        // AFTER the marker was written (Termux-recovery, manual edits), losing the
        // MOBILESTUDIO_JDK_TOOL export -> java spawns dispatch as javac ->
        // "error: invalid flag: -XX:+UseSerialGC". The marker alone cannot see that.
        if (storedVersion >= WRAPPER_FORMAT_VERSION && !hasStaleWrapperScripts(jdkHome)) return

        onProgress?.invoke("Wrapper format updated ($storedVersion -> $WRAPPER_FORMAT_VERSION), regenerating...")

        // Restore all .real files back to original names.
        // IMPORTANT: the old wrapper script at the original name blocks the rename
        // AND makes createLinkerWrapper skip ("already wrapped") — the stale v1/v2/v3
        // wrapper would persist forever, re-breaking the preload fix on every build.
        // Delete the small wrapper scripts first so the regeneration actually happens.
        fun restoreBinaries(dir: File) {
            if (!dir.isDirectory) return
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    restoreBinaries(file)
                    return@forEach
                }
                if (file.name.endsWith(".real")) {
                    val original = File(file.parentFile, file.name.removeSuffix(".real"))
                    if (original.exists()) {
                        // Only remove if it is a generated wrapper script (small sh script),
                        // never a real ELF binary
                        val isWrapperScript = try {
                            original.length() < 10000 && original.readText().startsWith("#!/system/bin/sh")
                        } catch (_: Exception) { false }
                        if (isWrapperScript) original.delete()
                    }
                    if (!original.exists()) {
                        file.renameTo(original)
                    }
                }
            }
        }

        restoreBinaries(File(jdkHome, "bin"))
        restoreBinaries(File(jdkHome, "lib/jli"))

        setupLinkerWrappers(jdkHome, onProgress, nativeLibDirOverride)

        markerFile.writeText(WRAPPER_FORMAT_VERSION.toString())
        onProgress?.invoke("Wrapper regeneration complete for ${jdkHome.name}")
    }

    /**
     * True when bin/java or bin/javac is a generated wrapper script whose content no
     * longer matches WRAPPER_FORMAT_VERSION (missing MOBILESTUDIO_JDK_TOOL export or
     * an older format header). Non-wrapper files and ELF binaries are never stale.
     */
    private fun hasStaleWrapperScripts(jdkHome: File): Boolean {
        val binDir = File(jdkHome, "bin")
        return listOf("java", "javac").any { isStaleWrapperScript(File(binDir, it)) }
    }

    /** Content-based wrapper staleness — see [hasStaleWrapperScripts]. */
    private fun isStaleWrapperScript(file: File): Boolean {
        if (!file.isFile || file.length() >= 10000 || isElfBinary(file)) return false
        return try {
            val text = file.readText()
            text.startsWith("#!/system/bin/sh") &&
                text.contains("libjava_launcher.so") &&
                (!text.contains("MOBILESTUDIO_JDK_TOOL") ||
                    !text.contains("wrapper v$WRAPPER_FORMAT_VERSION"))
        } catch (_: Exception) {
            false
        }
    }

    private fun createLinkerWrapper(
        binary: File,
        jdkHome: File,
        nativeLibDir: File,
        onProgress: ((String) -> Unit)?
    ) {
        val realBinary = File(binary.parentFile, "${binary.name}.real")

        // Preserve the ELF exactly once: if <name>.real is already an ELF, keep it and
        // just overwrite the (possibly stale/corrupted) wrapper script below. If the
        // current binary is the ELF, move it to <name>.real first. Stale wrapper
        // scripts from ANY previous format are ALWAYS overwritten with the current one.
        if (!realBinary.exists() && isElfBinary(binary)) {
            if (!binary.renameTo(realBinary)) {
                onProgress?.invoke("Failed to rename ${binary.name}")
                return
            }
        }

        // Determine LD_LIBRARY_PATH for this binary
        val ldPath = buildList {
            add(File(jdkHome, "lib").absolutePath)
            add(File(jdkHome, "lib/server").absolutePath)
            add(File(jdkHome, "lib/jli").absolutePath)
            add(nativeLibDir.absolutePath)
            add("/system/lib64")
            add("/system/lib")
        }.joinToString(":")

        // v3 wrapper: resolve the app's nativeLibraryDir at RUNTIME (never hardcode
        // /data/app paths which change per APK install). Exec chain (v12):
        //   <nativeLibraryDir>/libjava_launcher.so <real> <args...>   (direct exec;
        //   PIE executable via PT_INTERP — /system/bin/linker64 fallback if no +x)
         // v11: anchor prefers the tool's OWN .real first — if MOBILESTUDIO_JDK_TOOL is
         // ever lost (stale/partial wrapper), the launcher's basename fallback then
         // derives the CORRECT tool name (java -> java mode, javac -> javac tool mode)
         // instead of always guessing "javac" from the shared javac.real anchor.
         // javac.real stays the fallback (guaranteed ELF, same directory -> identical
         // JAVA_HOME derivation). libjava_launcher.so passes the anchor to JLI_Launch
         // as argv[0] — java_md READS IT AS AN ELF, so a script path here dies with
         // "has bad ELF magic".
         val wrapper = """#!/system/bin/sh
 # Auto-generated JDK launcher wrapper v${WRAPPER_FORMAT_VERSION} for ${binary.name} (MobileStudio)
 # JAVA_HOME is derived from THIS script's location (pwd -P = canonical, so the
 # /data/user/0 vs /data/data alias never changes the spelling). The absolute
 # path below is only the fallback when ${'$'}0 cannot be resolved.
 _MS_JBIN="$(cd "$(dirname "${'$'}0")" 2>/dev/null && pwd -P)"
 JAVA_HOME="${'$'}(dirname "${'$'}_MS_JBIN")"
 case "${'$'}JAVA_HOME" in
     /*) : ;;
     *) JAVA_HOME="${jdkHome.absolutePath}" ;;
 esac
 export MOBILESTUDIO_JDK_TOOL="${binary.name}"

 # ELF anchor: own .real first, then javac.real, then java.real.
 ELF_ANCHOR="${'$'}JAVA_HOME/bin/${binary.name}.real"
 [ -f "${'$'}ELF_ANCHOR" ] || ELF_ANCHOR="${'$'}JAVA_HOME/bin/javac.real"
 [ -f "${'$'}ELF_ANCHOR" ] || ELF_ANCHOR="${'$'}JAVA_HOME/bin/java.real"
 if [ ! -f "${'$'}ELF_ANCHOR" ]; then
     echo "MobileStudio wrapper v${WRAPPER_FORMAT_VERSION}: no ELF anchor for ${binary.name} (JAVA_HOME=${'$'}JAVA_HOME)" >&2
 fi

# Resolve the app's nativeLibraryDir at runtime.
# PREFERRED: pm path (ALWAYS the currently installed APK). A stale Gradle daemon
# inherits MOBILESTUDIO_NATIVE_LIB_DIR from an older APK install — if that old
# directory still exists, the env-first resolution would load the OUTDATED
# libjava_launcher.so (no javac tool-mode dispatch -> "Unrecognized option:
# --system=none" in JdkImageTransform). pm-first guarantees the current library.
APK=${'$'}(pm path com.aide.mobilestudio 2>/dev/null | head -n 1 | sed 's/package://')
if [ -n "${'$'}APK" ]; then
    NLIB="${'$'}(dirname "${'$'}APK")/lib/arm64"
fi
if [ -z "${'$'}NLIB" ] || [ ! -f "${'$'}NLIB/libjava_launcher.so" ]; then
    NLIB="${'$'}MOBILESTUDIO_NATIVE_LIB_DIR"
fi

# Preload all JDK .so files (Android linker namespace fix)
# SKIP Termux-era leftovers whose deps (libpcre2-8.so etc.) are not in the JDK —
# a failed preload library makes the WHOLE exec fail with CANNOT LINK EXECUTABLE.
# SKIP libjsig.so: JDK21's libjsig prints the signal-chaining deprecation
# warning to STDOUT (puts) while JDK17's writes it to stderr, so preloading it
# pollutes `jlink --version` stdout on JDK21 and AGP's JavaVersion.toVersion
# fails with "Could not determine Java version from ... 21.0.4-internal".
# No JDK lib DT_NEEDs libjsig and HotSpot runs fine without it (measured).
_JDK_PRELOAD=""
for _d in "${'$'}JAVA_HOME/lib" "${'$'}JAVA_HOME/lib/server"; do
    [ -d "${'$'}_d" ] || continue
    for _f in "${'$'}_d"/*.so; do
        [ -f "${'$'}_f" ] || continue
        case "${'$'}_f" in
            *libandroid-selinux.so*|*libandroid-shmem.so*|*libandroid-spawn.so*|*libpcre2*|*libselinux*|*libjsig*) continue ;;
        esac
        _JDK_PRELOAD="${'$'}{_JDK_PRELOAD:+${'$'}{_JDK_PRELOAD}:}${'$'}_f"
    done
done
[ -n "${'$'}_JDK_PRELOAD" ] && export LD_PRELOAD="${'$'}_JDK_PRELOAD"

# APPEND exec_redirect to LD_PRELOAD (never replace the JDK preload list).
# This hooks execve/posix_spawn in the JVM so that ANY process it spawns
# (Gradle daemon fork, javac, aapt2) can execute from the noexec app-data
# mount via memfd_create. Without it the daemon fork fails: error=13.
if [ -n "${'$'}NLIB" ] && [ -f "${'$'}NLIB/libexec_redirect.so" ]; then
    case ":${'$'}{LD_PRELOAD}:" in
        *":${'$'}NLIB/libexec_redirect.so:"*) ;;
        *) export LD_PRELOAD="${'$'}{LD_PRELOAD:+${'$'}{LD_PRELOAD}:}${'$'}NLIB/libexec_redirect.so" ;;
    esac
fi

export LD_LIBRARY_PATH="$ldPath${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"

# Launch chain (v12): direct exec — libjava_launcher.so is a PIE EXECUTABLE
# (crt startup -> main, e_entry != 0), so the kernel runs it through
# /system/bin/linker64 via PT_INTERP. argv passed to main() is identical to
# the old explicit form: argv[0]=launcher, argv[1]=<ANCHOR>, rest=user args.
# <ANCHOR> is the wrapper's own path — the launcher uses it ONLY as a string
# (java.home derivation + argv[0]) and NEVER loads it as an ELF, so a
# missing/corrupted <name>.real cannot break execution.
# Fallback: explicit /system/bin/linker64 <path> (needs read access only —
# covers a lost +x bit on the extracted file).
# Tool-aware dispatch: java = java-mode (daemon/client — proven on device),
# javac = TOOL-mode with JAVA_ARGS {-J--add-modules, -JALL-DEFAULT, -J-ms8m,
# -m, jdk.compiler/...Main} so --system=none (JdkImageTransform) is a javac
# option, not a VM option.
if [ -n "${'$'}NLIB" ] && [ -f "${'$'}NLIB/libjava_launcher.so" ] && [ -f "${'$'}ELF_ANCHOR" ]; then
    if [ -x "${'$'}NLIB/libjava_launcher.so" ]; then
        exec "${'$'}NLIB/libjava_launcher.so" "${'$'}ELF_ANCHOR" "${'$'}@"
    fi
    if [ -x /system/bin/linker64 ]; then
        exec /system/bin/linker64 "${'$'}NLIB/libjava_launcher.so" "${'$'}ELF_ANCHOR" "${'$'}@"
    fi
fi
# Last resort: direct exec of the original .real (permissive mounts only)
if [ -f "${'$'}JAVA_HOME/bin/${binary.name}.real" ]; then
    exec "${'$'}JAVA_HOME/bin/${binary.name}.real" "${'$'}@"
fi
exit 127
"""
        binary.writeText(wrapper)
        runCatching {
            Os.chmod(binary.absolutePath, 0b111_101_101)
            binary.setExecutable(true, false)
            binary.setReadable(true, false)
        }
        onProgress?.invoke("Created wrapper: ${binary.name}")
    }

    private fun detectElfInterpreter(file: File): String? {
        if (!file.exists() || file.length() < 64) return null
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val ident = ByteArray(20)
                raf.readFully(ident)
                if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte() ||
                    ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) return@use null
                if (ident[4].toInt() != 2) return@use null // Not 64-bit

                // ELF64: e_phoff @32, e_phentsize @54, e_phnum @56 (little-endian).
                // The old code read the ELF32 offsets 42/44 through big-endian
                // RandomAccessFile reads, so phnum/field offsets were garbage.
                raf.seek(32)
                val phoff = ExecHelper.readLeLong(raf)
                raf.seek(54)
                val phentsize = ExecHelper.readLeShort(raf)
                raf.seek(56)
                val phnum = ExecHelper.readLeShort(raf)
                if (phoff <= 0 || phnum == 0 || phnum > 128 || phentsize < 56) return@use null

                for (i in 0 until phnum) {
                    val base = phoff + i * phentsize.toLong()
                    raf.seek(base)
                    if (ExecHelper.readLeInt(raf) == 3) { // PT_INTERP: p_offset @+8, p_filesz @+32
                        raf.seek(base + 8)
                        val poff = ExecHelper.readLeLong(raf)
                        raf.seek(base + 32)
                        val pfilesz = ExecHelper.readLeLong(raf)
                        if (pfilesz <= 0 || pfilesz > 256 ||
                            poff < 0 || poff + pfilesz > raf.length()
                        ) return@use null
                        raf.seek(poff)
                        val interp = ByteArray(pfilesz.toInt())
                        raf.readFully(interp)
                        return@use String(interp).trim('\u0000').trim()
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun detectInterpreter(file: File): String? {
        val interp = detectElfInterpreter(file) ?: return null
        // If it's already a linker64 path, use it
        if (interp.contains("linker")) return interp
        // Otherwise default to system linker
        return "/system/bin/linker64"
    }

    private fun isElfBinary(file: File): Boolean {
        if (!file.exists() || file.length() < 64) return false
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(5)
                raf.readFully(magic)
                magic[0] == 0x7F.toByte() &&
                magic[1] == 'E'.code.toByte() &&
                magic[2] == 'L'.code.toByte() &&
                magic[3] == 'F'.code.toByte() &&
                (magic[4] == 1.toByte() || magic[4] == 2.toByte()) // 32-bit or 64-bit
            }
        } catch (e: Exception) {
            false
        }
    }
}