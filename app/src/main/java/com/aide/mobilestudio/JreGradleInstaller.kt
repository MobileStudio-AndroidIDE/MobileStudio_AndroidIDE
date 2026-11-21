package com.aide.mobilestudio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.system.OsConstants
import android.util.Log
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Installs the Android-compatible JDK 17 runtime and Gradle into filesDir.
 *
 * Main paths:
 *   filesDir/toolchain/jdk/    openjdk-17 (Bionic, aarch64)
 *   filesDir/toolchain/gradle/ Gradle 8.2
 */
class JreGradleInstaller(
    private val context: Context,
    private val onLog:      (String) -> Unit,
    private val onProgress: (Int) -> Unit
) {
    private val quietLogs = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    val installDir: File get() = File(context.filesDir, "toolchain")
    val jdkRoot: File
        get() {
            val toolchain = ToolchainManager(context)
            return toolchain.activeDir(ToolchainManager.Kind.JDK)
                ?.takeIf { toolchain.resolveJdkHome(it) != null }
                ?: File(installDir, "jdk")
        }

    /** Expected .deb extraction layout: jdkRoot/lib/jvm/java-17-openjdk/bin/java */
    val javaHome: File get() = resolveJavaHome(jdkRoot)
    private val javaHomeShim: File get() = File(jdkRoot, "shim/${javaHome.name}")
    private val shimJavaBin: File get() = File(javaHomeShim, "bin/java")

    val gradleHome: File
        get() = resolveGradleHome(
            ToolchainManager(context).activeDir(ToolchainManager.Kind.GRADLE)
                ?: File(installDir, "gradle")
        )
    val gradleLibDir:  File get() = File(gradleHome, "lib")

    val javaBin: File get() = File(javaHome, "bin/java")

    val gradleLauncherJar: File?
        get() {
            val files = gradleLibDir.listFiles() ?: return null
            // Gradle 8.14+: entry point is gradle-gradle-cli-main-<v>.jar (has Main-Class)
            files.firstOrNull { it.name.startsWith("gradle-gradle-cli-main") && it.name.endsWith(".jar") }?.let { return it }
            // Try exact gradle-launcher-*.jar match first
            files.firstOrNull { it.name.startsWith("gradle-launcher") && it.name.endsWith(".jar") }?.let { return it }
            // Fallback: any jar with "launcher" in name
            files.firstOrNull { it.name.contains("launcher", ignoreCase = true) && it.name.endsWith(".jar") }?.let { return it }
            // Last resort: the first (smallest) jar with "gradle" in name
            return files.filter { it.name.contains("gradle", ignoreCase = true) && it.name.endsWith(".jar") }
                .minByOrNull { it.length() }
        }

    val isJdkInstalled: Boolean
        get() = javaBin.exists() || legacyJavaBin.exists()

    val isGradleInstalled: Boolean
        get() = gradleLauncherJar != null

    val isInstalled: Boolean
        get() = isJdkInstalled && isGradleInstalled

    fun ensureGradleLauncherReadyForAndroid(allowDaemon: Boolean) {
        val launcher = gradleLauncherJar ?: error("Gradle launcher jar not installed")
        if (allowDaemon) {
            restoreGradleLauncherForDaemon(launcher)
        } else {
            patchGradleLauncherForAndroid(launcher)
        }
    }

    /**
     * Removes Termux-era leftover .so files from the JDK lib tree. These are NOT part
     * of the custom Android JDK build; a corrupted libandroid-selinux.so (truncated
     * extraction: "invalid shdr offset/size") breaks the whole exec chain with
     * "CANNOT LINK EXECUTABLE /system/bin/sh: library libpcre2-8.so not found".
     */
    fun removeTermuxLeftovers(jdkHome: File) {
        try {
            val badNames = setOf(
                "libandroid-selinux.so", "libandroid-shmem.so", "libandroid-spawn.so",
                "libselinux.so", "libpcre2-8.so", "libpcre2-16.so", "libpcre2-32.so", "libpcre2-posix.so"
            )
            var removed = 0
            jdkHome.walkTopDown().maxDepth(3).forEach { dir ->
                if (!dir.isDirectory) return@forEach
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name in badNames) {
                        if (f.delete()) removed++
                    }
                }
            }
            if (removed > 0) log("Removed $removed Termux leftover .so file(s) from ${jdkHome.name}")
        } catch (e: Exception) {
            log("Leftover cleanup failed: ${e.message}")
        }
    }

    fun ensureGradleLauncherPatchedForAndroid() {
        if (!patchGradleLauncherFromGithub()) {
            ensureGradleLauncherReadyForAndroid(allowDaemon = false)
        }
        patchDaemonCompatibilitySpecInAllJars()
        /*
         * native-platform must stay byte-pristine: the old ASM rewrite of
         * DefaultProcessLauncher.start() produced an invalid StackMapTable and
         * killed the daemon during verification ("StackMapTable error: bad
         * offset").  exec_redirect handles noexec instead of bytecode patching.
         */
        ensureNativePlatformPristine()
        stripNativePlatformLibsFromGradle()
    }

    /**
     * Verifies (and repairs) `net/rubygrapefruit/platform/internal/
     * DefaultProcessLauncher.class` inside every Gradle distribution jar.
     *
     * Logs the SHA-256 of the class so the value can be compared before and
     * after a build: it must never change.
     *
     * Tags: `[Gradle] [Daemon]`
     */
    fun ensureNativePlatformPristine() {
        val statuses = AndroidGradleCachePatcher.inspectNativePlatformJars(gradleLibDir)
        if (statuses.isEmpty()) {
            log("[Gradle] [Daemon] native-platform jars: none in $gradleLibDir")
            return
        }
        for (status in statuses) {
            when {
                !status.present || !status.readable || status.patched -> {
                    log(
                        "[Gradle] [Daemon] native-platform ${status.jar.name}: " +
                            if (!status.present) "DefaultProcessLauncher.class missing -> restoring"
                            else if (!status.readable) "unreadable class (sha=${status.sha256}) -> restoring"
                            else "ASM-patched (sha=${status.sha256}) -> restoring"
                    )
                    restoreNativePlatformJar(status.jar)
                }

                else -> {
                    val sha = status.sha256
                    val prev = sha?.let {
                        nativeLauncherHashes.put(status.jar.absolutePath, it)
                    }
                    when {
                        prev == null ->
                            log(
                                "[Gradle] [Daemon] native-platform ${status.jar.name}: " +
                                    "DefaultProcessLauncher SHA-256=$sha (pristine, baseline)"
                            )

                        prev == sha ->
                            log(
                                "[Gradle] [Daemon] native-platform ${status.jar.name}: " +
                                    "DefaultProcessLauncher SHA-256=$sha (pristine, unchanged)"
                            )

                        else ->
                            log(
                                "[Gradle] [Daemon][WARN] native-platform ${status.jar.name}: " +
                                    "DefaultProcessLauncher SHA-256 CHANGED $prev -> $sha"
                            )
                    }
                }
            }
        }
        // Refresh the baseline for jars that are healthy now (includes the
        // ones restored above) so the next check compares against them.
        AndroidGradleCachePatcher.inspectNativePlatformJars(gradleLibDir).forEach { s ->
            if (s.present && s.readable && !s.patched && s.sha256 != null) {
                nativeLauncherHashes[s.jar.absolutePath] = s.sha256!!
            }
        }
        val stale = AndroidGradleCachePatcher.deleteNativeLauncherMarkers(gradleLibDir)
        if (stale > 0) {
            log("[Gradle] [Daemon] removed $stale stale native-platform patch marker(s)")
        }
    }

    /** SHA-256 of DefaultProcessLauncher.class seen at the previous check. */
    private val nativeLauncherHashes = mutableMapOf<String, String>()

    /**
     * Restores a damaged/patched `native-platform-*.jar`:
     *  1. from `<jar>.mobilestudio-np-original` when one was saved, else
     *  2. from the Gradle distribution zip (downloaded on demand).
     */
    private fun restoreNativePlatformJar(jar: File) {
        val backup = File(jar.parentFile, "${jar.name}.mobilestudio-np-original")
        if (backup.exists() && backup.length() > 0L) {
            runCatching {
                val status = AndroidGradleCachePatcher.inspectNativePlatformJar(backup)
                if (status.readable && !status.patched && status.sha256 != null) {
                    backup.copyTo(jar, overwrite = true)
                    log(
                        "[Gradle] [Daemon] restored ${jar.name} from backup; " +
                            "DefaultProcessLauncher SHA-256=${status.sha256}"
                    )
                    return
                }
            }
        }

        runCatching {
            val version = gradleHome.name.removePrefix("gradle-")
            val zipFile = File(installDir, "gradle-$version-bin.zip")
            if (!zipFile.exists() || zipFile.length() == 0L) {
                log("[Gradle] [Daemon] downloading gradle-$version-bin.zip to restore ${jar.name} ...")
                downloadFile(
                    "https://services.gradle.org/distributions/gradle-$version-bin.zip",
                    zipFile
                )
            }

            val tmp = File(jar.parentFile, "${jar.name}.restore.tmp")
            try {
                ZipFile(zipFile).use { zip ->
                    val entries = zip.entries()
                    var found = false
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory || !entry.name.endsWith("/lib/${jar.name}")) continue
                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(tmp).use { out -> input.copyTo(out) }
                        }
                        found = true
                        break
                    }
                    if (!found) error("${jar.name} not found in ${zipFile.name}")
                }

                val status = AndroidGradleCachePatcher.inspectNativePlatformJar(tmp)
                if (!status.readable || status.patched || status.sha256 == null) {
                    error("extracted launcher still invalid (sha=${status.sha256})")
                }

                tmp.copyTo(jar, overwrite = true)
                log(
                    "[Gradle] [Daemon] restored ${jar.name} from ${zipFile.name}; " +
                        "DefaultProcessLauncher SHA-256=${status.sha256}"
                )
            } finally {
                tmp.delete()
            }
        }.onFailure {
            log("[Gradle] [Daemon][WARN] could not restore ${jar.name}: ${it.message}")
        }
    }

    /** Prebuilt patch jars published on GitHub (see mobilestudio-gradle-patches release).
     *  They contain the exact jar that ships with each Gradle distribution with
     *  BuildActionsFactory.canUseCurrentProcess() already rewritten to `iconst_1; ireturn`,
     *  which is simpler and more reliable than re-patching on-device. */
    private fun patchGradleLauncherFromGithub(): Boolean {
        val version = gradleHome.name.removePrefix("gradle-")
        if (version.isEmpty() || !version[0].isDigit()) {
            log("GitHub patch: unknown Gradle version from '${gradleHome.name}', using on-device patcher")
            return false
        }
        val target = gradleLibDir.listFiles()?.firstOrNull { jar ->
            jar.name.endsWith(".jar") && runCatching {
                ZipFile(jar).use { zip -> zip.getEntry("org/gradle/launcher/cli/BuildActionsFactory.class") != null }
            }.getOrDefault(false)
        } ?: run {
            log("GitHub patch: $targetClass not found in any lib jar, using on-device patcher")
            return false
        }
        val marker = File(target.parentFile, "${target.name}.mobilestudio-inprocess-v2")
        if (marker.exists() && marker.lastModified() >= target.lastModified() && marker.readText().trim() == "patched") {
            log("GitHub patch: already applied to ${target.name}")
            return true
        }
        val tmp = File(target.parentFile, "${target.name}.github.tmp")
        try {
            val url = "$GRADLE_PATCH_RELEASE_BASE/mobilestudio-gradle-patch-$version.jar"
            log("GitHub patch: downloading $url ...")
            downloadFile(url, tmp)
            val isValid = runCatching {
                ZipFile(tmp).use { zip ->
                    val entry = zip.getEntry("org/gradle/launcher/cli/BuildActionsFactory.class")
                    if (entry == null) false else {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val (p, a, _) = patchBuildActionsFactoryClass(bytes)
                        p || a
                    }
                }
            }.getOrDefault(false)
            if (!isValid) {
                tmp.delete()
                log("GitHub patch: downloaded jar failed verification, using on-device patcher")
                return false
            }
            val backup = File(target.parentFile, "${target.name}.mobilestudio-original")
            if (!backup.exists() || backup.length() == 0L) {
                target.copyTo(backup, overwrite = true)
            }
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
            marker.writeText("patched\n")
            log("GitHub patch: replaced ${target.name} with prebuilt patched jar")
            return true
        } catch (e: Exception) {
            tmp.delete()
            log("GitHub patch failed (${e.message}), using on-device patcher")
            return false
        }
    }

    private fun stripNativePlatformLibsFromGradle() {
        val marker = File(gradleLibDir, ".native-platform-stripped")
        if (marker.exists()) return
        var strippedAny = false
        for (jar in gradleLibDir.listFiles().orEmpty()) {
            if (!jar.name.contains("native-platform") || !jar.name.endsWith(".jar")) continue
            val tmp = File(jar.parentFile, "${jar.name}.tmp")
            var modified = false
            try {
                ZipOutputStream(tmp.outputStream()).use { zos ->
                    ZipInputStream(jar.inputStream()).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val keep = !entry.name.endsWith(".so")
                            if (!keep) modified = true
                            if (keep) {
                                zos.putNextEntry(ZipEntry(entry.name))
                                zis.copyTo(zos)
                                zos.closeEntry()
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                if (modified) {
                    jar.delete()
                    tmp.renameTo(jar)
                    strippedAny = true
                    log("Stripped native .so from ${jar.name} (force ProcessBuilder fallback)")
                } else {
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                log("Failed to strip ${jar.name}: ${e.message}")
            }
        }
        if (strippedAny) marker.writeText("stripped")
    }

    /** Prefer the real JDK home. The shim is only a last-resort launcher wrapper. */
    val effectiveJavaBin: File
        get() = when {
            javaBin.exists() && hasUsableJli(javaHome) -> javaBin
            fallbackJavaHome()?.let { File(it, "bin/java") }?.exists() == true -> File(fallbackJavaHome()!!, "bin/java")
            legacyJavaBin.exists() -> legacyJavaBin
            javaBin.exists() -> javaBin
            else -> javaBin
        }

    /** JAVA_HOME must point at the real JDK tree, never the generated shim tree. */
    val effectiveJavaHome: File
        get() {
            // Use ToolchainManager's active JDK first
            val activeHome = ToolchainManager(context).activeJdkHome()
            if (activeHome != null && hasUsableJli(activeHome)) return activeHome
            if (hasUsableJli(javaHome)) return javaHome
            return fallbackJavaHome() ?: javaHome
        }

    /**
     * Canonical form of [effectiveJavaHome].  On Android the app data dir is
     * reachable as both /data/user/0/<pkg> and /data/data/<pkg> (one of them
     * is a symlink depending on the device build).  Gradle compares JAVA_HOME,
     * -Djava.home and -Dorg.gradle.java.home as STRINGS when it decides whether
     * to fork a daemon, so every site must use this one canonical spelling.
     */
    val canonicalJavaHome: File
        get() = runCatching { effectiveJavaHome.canonicalFile }
            .getOrDefault(effectiveJavaHome)

    /**
     * JAVA_HOME spelling for the DAEMON's exec path. The JVM's forkAndExec
     * cannot exec a shell script from the noexec app-data mount: the fail-pipe
     * protocol breaks and the JDK port reports a garbage errno ("error=<big
     * number>, Unknown error"). The shim tree's bin/java is a symlink to the
     * native ELF launcher (libjava_launcher.so), which the forkAndExec child
     * CAN exec (noexec-ELF strategies: memfd, linker64). Point
     * org.gradle.java.home and -Djava.home here so the daemon command is an
     * ELF, not a script. The shim's lib/conf/etc. are symlinks to the real
     * tree, so every JVM lookup still resolves.
     */
    val daemonJavaHome: File
        get() {
            runCatching { ensureJavaHomeShim() }
            if (shimJavaBin.exists()) {
                return runCatching { javaHomeShim.canonicalFile }
                    .getOrDefault(javaHomeShim)
            }
            return canonicalJavaHome
        }

    private fun ensureJavaHomeShim() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val launcher = File(nativeLibDir, "libjava_launcher.so")
        if (!launcher.exists()) return

        javaHomeShim.mkdirs()
        val shimBin = File(javaHomeShim, "bin").apply { mkdirs() }
        val shimJava = File(shimBin, "java")

        // Recreate a broken shim tree left by older installs.
        if (javaHomeShim.exists() && !shimBin.exists()) {
            javaHomeShim.mkdirs()
            shimBin.mkdirs()
        }

        fun ensureSymlink(link: File, target: String) {
            val current = symlinkTarget(link)
            if (current == target) return
            runCatching { if (link.exists() || isSymlink(link)) Os.remove(link.absolutePath) }
            runCatching { if (link.exists()) link.deleteRecursively() else link.delete() }
            runCatching { Os.symlink(target, link.absolutePath) }
        }

        listOf("conf", "include", "jmods", "legal", "lib", "man").forEach { child ->
            val realChild = File(javaHome, child)
            if (realChild.exists()) {
                ensureSymlink(File(javaHomeShim, child), realChild.absolutePath)
            }
        }

        val realRelease = File(javaHome, "release")
        if (realRelease.exists()) {
            runCatching { File(javaHomeShim, "release").writeText(realRelease.readText()) }
        }

        File(javaHome, "bin").listFiles()?.forEach { tool ->
            val shimTool = File(shimBin, tool.name)
            if (tool.name == "java") return@forEach
            if (tool.isFile) {
                ensureSymlink(shimTool, tool.absolutePath)
            }
        }

        runCatching {
            val current = symlinkTarget(shimJava)
            if (current != launcher.absolutePath) {
                if (shimJava.exists() || isSymlink(shimJava)) {
                    runCatching { Os.remove(shimJava.absolutePath) }
                    runCatching { shimJava.delete() }
                }
                Os.symlink(launcher.absolutePath, shimJava.absolutePath)
            }
        }.recoverCatching {
            shimJava.writeText("#!/system/bin/sh\nexec ${launcher.absolutePath} \"$@\"\n")
        }.getOrThrow()

        check(hasUsableJli(javaHome) || hasUsableJli(javaHomeShim)) {
            "libjli.so not found under ${javaHome.absolutePath}"
        }

        if (!isSymlink(shimJava)) {
            runCatching { Os.chmod(shimJava.absolutePath, 0b111_101_101) }
        }
    }

    private fun hasUsableJli(home: File): Boolean {
        return File(home, "lib/libjli.so").let { it.isFile && it.length() > 0L } ||
            File(home, "lib/jli/libjli.so").let { it.isFile && it.length() > 0L }
    }

    private fun ensureRealJavaExecWrapper() {
        val realJava = File(javaHome, "bin/java")
        realJava.parentFile?.mkdirs()

        runCatching {
            // Check wrapper format version — regenerate if stale
            val markerFile = File(javaHome, ".wrapper_format_version")
            val storedVersion = if (markerFile.exists()) markerFile.readText().trim().toIntOrNull() ?: 0 else 0
            val needsRegen = storedVersion < JdkDownloader.WRAPPER_FORMAT_VERSION

            // If JdkDownloader already created a linker64 wrapper, verify it works.
            // MUST contain the MOBILESTUDIO_JDK_TOOL export AND the current format
            // marker: a script without the export dispatches java spawns as javac
            // ("error: invalid flag: -XX:+UseSerialGC" / "Usage: javac <options>").
            if (!needsRegen && realJava.exists() && realJava.length() < 10000) {
                val existing = runCatching { realJava.readText() }.getOrDefault("")
                if (existing.startsWith("#!/system/bin/sh") &&
                    existing.contains("MOBILESTUDIO_JDK_TOOL") &&
                    existing.contains("wrapper v${JdkDownloader.WRAPPER_FORMAT_VERSION}")
                ) {
                    log("java exec wrapper already exists (linker64), verifying...")
                    // Check if .real backup exists.  The anchor ELF is the
                    // Android linker (a few tens of KB) — judge it by ELF
                    // magic, never by size: an earlier >1MB check marked every
                    // real anchor "missing" and forced a wrapper rebuild on
                    // every build.
                    val realBackup = File(javaHome, "bin/java.real")
                    if (isElfBinary(realBackup)) {
                        log("java exec wrapper verified: ${realJava.absolutePath}")
                        return@runCatching
                    }
                }
            }

        val backupTarget = ensureOriginalJavaBackup()
        if (backupTarget == null) {
            log("[WARN] no valid java.real — anchor falls back to javac.real (libjava_launcher performs the launch)")
        }
        val useBackupElf = backupTarget?.let { isValidJavaBinary(it) } ?: false

            // Always create wrapper script — the wrapper script (#!/system/bin/sh)
            // is the ONLY proven working execution path for glibc JDK binaries on Android.
            // Never restore raw ELF to javaHome/bin/java.

            val wrapper = """
#!/system/bin/sh
# Auto-generated JDK launcher wrapper v${JdkDownloader.WRAPPER_FORMAT_VERSION} for java (MobileStudio)
# JAVA_HOME is derived from THIS script's location (pwd -P = canonical, so the
# /data/user/0 vs /data/data alias never changes the spelling). The absolute
# path below is only the fallback when ${'$'}0 cannot be resolved.
_MS_JBIN="$(cd "$(dirname "${'$'}0")" 2>/dev/null && pwd -P)"
JAVA_HOME_DIR="${'$'}(dirname "${'$'}_MS_JBIN")"
case "${'$'}JAVA_HOME_DIR" in
    /*) : ;;
    *) JAVA_HOME_DIR="${javaHome.absolutePath}" ;;
esac
# v11: anchor prefers java.real — the basename fallback in libjava_launcher then
# derives tool "java" even if MOBILESTUDIO_JDK_TOOL is lost. javac.real stays the
# guaranteed-ELF fallback (java_md reads argv[0] as an ELF — a script path dies
# with "bad ELF magic").
export MOBILESTUDIO_JDK_TOOL="java"
ELF_ANCHOR="${javaHome.absolutePath}/bin/java.real"
[ -f "${'$'}ELF_ANCHOR" ] || ELF_ANCHOR="${javaHome.absolutePath}/bin/javac.real"
if [ ! -f "${'$'}ELF_ANCHOR" ]; then
    echo "MobileStudio wrapper v${JdkDownloader.WRAPPER_FORMAT_VERSION}: no ELF anchor for java" >&2
fi

# Resolve the app's nativeLibraryDir at RUNTIME.
# PREFERRED: pm path (ALWAYS the current APK). Env can be stale in old daemons
# (older APK's nativeLibraryDir -> outdated libjava_launcher.so without javac
# tool-mode dispatch -> "Unrecognized option: --system=none").
APK=${'$'}(pm path com.aide.mobilestudio 2>/dev/null | head -n 1 | sed 's/package://')
if [ -n "${'$'}APK" ]; then
NLIB="${'$'}(dirname "${'$'}APK")/lib/arm64"
fi
if [ -z "${'$'}NLIB" ] || [ ! -f "${'$'}NLIB/libjava_launcher.so" ]; then
NLIB="${'$'}MOBILESTUDIO_NATIVE_LIB_DIR"
fi

# Preload all JDK .so files to avoid Android linker namespace issues
# SKIP Termux leftovers (corrupted libandroid-selinux.so breaks the whole exec:
# "CANNOT LINK EXECUTABLE /system/bin/sh: library libpcre2-8.so not found")
# SKIP libjsig.so: JDK21's libjsig writes the signal-chaining deprecation
# warning to STDOUT (puts), polluting `jlink --version` stdout so AGP's
# JavaVersion.toVersion fails with "Could not determine Java version from ...
# 21.0.4-internal" (JDK17 writes it to stderr — that's why only JDK21 failed).
_JDK_PRELOAD=""
for _d in "${'$'}JAVA_HOME_DIR/lib" "${'$'}JAVA_HOME_DIR/lib/server"; do
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
                # Hooks execve in the JVM so the Gradle daemon fork and javac/aapt2 spawns
                # execute from the noexec app-data mount via memfd_create (fixes error=13).
                if [ -n "${'$'}NLIB" ] && [ -f "${'$'}NLIB/libexec_redirect.so" ]; then
                    case ":${'$'}{LD_PRELOAD}:" in
                        *":${'$'}NLIB/libexec_redirect.so:"*) ;;
                        *) export LD_PRELOAD="${'$'}{LD_PRELOAD:+${'$'}{LD_PRELOAD}:}${'$'}NLIB/libexec_redirect.so" ;;
                    esac
                fi

                export LD_LIBRARY_PATH="${'$'}JAVA_HOME_DIR/lib:${'$'}JAVA_HOME_DIR/lib/server:${'$'}JAVA_HOME_DIR/lib/jli:${'$'}{NLIB:+${'$'}NLIB:}/system/lib64:/system/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"

# Launch chain (v12): direct exec — libjava_launcher.so is a PIE EXECUTABLE
# (crt startup -> main, e_entry != 0), kernel runs it via PT_INTERP; argv
# reaches main() as argv[0]=launcher, argv[1]=<ELF_ANCHOR>, rest=user args.
# <ELF_ANCHOR> is a real ELF (javac.real) — java_md READS argv[0] as an ELF, so a
# script path dies with "has bad ELF magic". Tool identity (java vs javac) comes
# from MOBILESTUDIO_JDK_TOOL. The launcher dispatches internally:
# java = java-mode (daemon/client), javac = TOOL-mode with JAVA_ARGS so
# --system=none is a javac option (JdkImageTransform).
# Fallback: explicit /system/bin/linker64 <path> (read access only, covers +x loss).
if [ -n "${'$'}NLIB" ] && [ -f "${'$'}NLIB/libjava_launcher.so" ] && [ -f "${'$'}ELF_ANCHOR" ]; then
if [ -x "${'$'}NLIB/libjava_launcher.so" ]; then
exec "${'$'}NLIB/libjava_launcher.so" "${'$'}ELF_ANCHOR" "${'$'}@"
fi
if [ -x /system/bin/linker64 ]; then
exec /system/bin/linker64 "${'$'}NLIB/libjava_launcher.so" "${'$'}ELF_ANCHOR" "${'$'}@"
fi
fi
# Last resort: direct exec of the original .real (permissive mounts only)
if [ -f "${'$'}JAVA_HOME_DIR/bin/java.real" ]; then
exec "${'$'}JAVA_HOME_DIR/bin/java.real" "${'$'}@"
fi
exit 127
""".trimIndent() + "\n"

            if (isSymlink(realJava)) {
                runCatching { Os.remove(realJava.absolutePath) }
            } else if (realJava.exists()) {
                realJava.delete()
            }
            realJava.writeText(wrapper)
            Os.chmod(realJava.absolutePath, 0b111_101_101)
            log("java exec wrapper ready: ${realJava.absolutePath} (backupElf=$useBackupElf backupSize=${backupTarget?.length() ?: -1L})")
            // Write marker so future APK upgrades can detect stale wrappers
            runCatching {
                File(javaHome, ".wrapper_format_version").writeText(JdkDownloader.WRAPPER_FORMAT_VERSION.toString())
            }
        }.onFailure { e ->
            log("[WARN] java exec redirect failed: ${e.message}")
        }
    }

    private fun detectElfInterpreter(file: File): String? {
        if (!file.exists() || file.length() < 64) return null
        return try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val ident = ByteArray(20)
                raf.readFully(ident)
                if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte() ||
                    ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()) return@use null
                if (ident[4].toInt() != 2) return@use null

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

    private fun ensureOriginalJavaBackup(): File? {
        val realJava = File(javaHome, "bin/java")
        val backup = File(javaHome, "bin/java.real")

        if (isValidJavaBinary(backup)) return backup
        if (backup.exists() || isSymlink(backup)) {
            log("Discarding invalid java.real: size=${backup.length()} elf=${isElfBinary(backup)}")
            runCatching { Os.remove(backup.absolutePath) }
            runCatching { backup.delete() }
        }

        if (isValidJavaBinary(realJava)) {
            realJava.copyTo(backup, overwrite = true)
            runCatching { Os.chmod(backup.absolutePath, 0b111_101_101) }
            return backup
        }

        log("[WARN] java.real is missing/invalid and bin/java is not an ELF — the v9 "
            + "wrapper does not need java.real (libjava_launcher.so performs the native "
            + "launch). NEVER download the legacy Termux JDK here; it corrupted the "
            + "toolchain before (libandroid-selinux.so / libpcre2-8.so link errors).")
        return null
    }

    /** 이전 버전에서 발견한 중첩 경로 설치 경우 백업 */
    private val legacyJavaBin: File
        get() = File(javaHome, "bin/java")

    private fun resolveJavaHome(root: File): File {
        // Use ToolchainManager's active JDK first
        val activeHome = ToolchainManager(context).activeJdkHome()
        if (activeHome != null && hasUsableJli(activeHome)) return activeHome

        ToolchainManager(context).resolveJdkHome(root)?.let { return it }
        val candidates = buildList {
            add(root)
            add(File(root, "lib/jvm/java-17-openjdk"))
            add(File(root, "lib/jvm/java-21-openjdk"))
            root.listFiles()?.filter { it.isDirectory }?.forEach { child ->
                add(child)
                add(File(child, "Contents/Home"))
                add(File(child, "lib/jvm/java-17-openjdk"))
                add(File(child, "lib/jvm/java-21-openjdk"))
                File(child, "lib/jvm").listFiles()?.filter { it.isDirectory }?.forEach { add(it) }
            }
            File(root, "lib/jvm").listFiles()?.filter { it.isDirectory }?.forEach { add(it) }
        }.distinctBy { it.absolutePath }
            .filter { "${File.separator}shim${File.separator}" !in it.absolutePath }
            .filter { File(it, "bin/java").exists() }

        return candidates.firstOrNull { hasUsableJli(it) }
            ?: candidates.firstOrNull()
            ?: root
    }

    private fun fallbackJavaHome(): File? {
        val roots = listOf(
            File(installDir, "jdk"),
            File(installDir, "jdk/lib/jvm/java-17-openjdk"),
            File(installDir, "jdk/jdk-17"),
            File(installDir, "jdk/lib/jvm/java-21-openjdk"),
            File(installDir, "jdk/jdk-21")
        )
        return roots.asSequence()
            .flatMap { root ->
                sequence {
                    yield(resolveJavaHome(root))
                    if (root.exists()) {
                        root.walkTopDown()
                            .maxDepth(4)
                            .filter { it.isDirectory && File(it, "bin/java").exists() }
                            .forEach { yield(it) }
                    }
                }
            }
            .distinctBy { it.absolutePath }
            .firstOrNull { hasUsableJli(it) }
    }

    private fun resolveGradleHome(root: File): File {
        if (File(root, "lib").exists()) return root
        return root.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "lib").exists() }
            ?: root
    }

    // 설치 경로 진입 (최초 1회 하위 디렉토리 생성)

    fun install(
        jdkVersion: String? = null,
        gradleVersion: String? = null,
        callback: (Boolean) -> Unit
    ) {
        Thread {
            val ok: Boolean = try {
                installDir.mkdirs()
                log("Build tool install started...")
                installJdkByVersion(
                    jdkVersion
                        ?.takeIf { it.startsWith("jdk-") }
                        ?: ToolchainManager.DEFAULT_JDK
                )
                installGradle(gradleVersion)
                log("Build tool install complete.")
                true
            } catch (e: Exception) {
                log("Install failed: ${e.message}")
                Log.e(TAG, "install failed", e)
                false
            }
            Handler(Looper.getMainLooper()).post { callback(ok) }
        }.start()
    }

    // Gradle only — never downloads a JDK (the JDK is installed via Toolchain Manager)
    fun installGradleOnly(gradleVersion: String? = null, callback: (Boolean) -> Unit) {
        Thread {
            val ok: Boolean = try {
                installDir.mkdirs()
                log("Gradle-only install started (JDK auto-download disabled)...")
                installGradle(gradleVersion)
                log("Gradle install complete.")
                true
            } catch (e: Exception) {
                log("Gradle install failed: ${e.message}")
                Log.e(TAG, "installGradleOnly failed", e)
                false
            }
            Handler(Looper.getMainLooper()).post { callback(ok) }
        }.start()
    }

    private fun installJdkByVersion(version: String) {
        val jdkNum = version.removePrefix("jdk-")

        // For custom-built JDK 17/21 (Android ARM64 Bionic), use dedicated installers
        when (version) {
            "jdk-21" -> { installJdk21(); return }
            "jdk-17" -> { installJdk17(); return }
        }

        val packageName = "openjdk-$jdkNum"
        val toolchain = ToolchainManager(context)
        val versionRoot = File(toolchain.kindDir(ToolchainManager.Kind.JDK), version)
        val home = File(versionRoot, "lib/jvm/java-$jdkNum-openjdk")
        val bin = File(home, "bin/java")

        if (bin.exists()) {
            log("JDK $jdkNum already installed at ${home.absolutePath}")
            toolchain.setActiveVersion(ToolchainManager.Kind.JDK, version)
            return
        }

        log("Resolving $packageName package URL from Termux repository...")
        val url = resolveTermuxJdkUrl(packageName)
        log("JDK $jdkNum URL: $url")

        val isTarXz = url.endsWith(".tar.xz")
        val isTarGz = url.endsWith(".tar.gz")
        val jdkFile = File(installDir, if (isTarXz) "$packageName.tar.xz" else if (isTarGz) "$packageName.tar.gz" else "$packageName.deb")
        log("Downloading JDK $jdkNum (${if (isTarXz) "tar.xz" else if (isTarGz) "tar.gz" else "aarch64"})...")
        downloadFile(url, jdkFile)

        log("Extracting $packageName archive...")
        versionRoot.mkdirs()
        if (isTarXz) {
            GlibcDownloader.extractTarXz(jdkFile, home) { msg -> log("  $msg") }
        } else if (isTarGz) {
            GlibcDownloader.extractTarGz(jdkFile, home) { msg -> log("  $msg") }
        } else {
            extractDebToJdk(jdkFile, versionRoot)
        }
        jdkFile.delete()

        applyExecPermissions(home)
        toolchain.setActiveVersion(ToolchainManager.Kind.JDK, version)
        log("JDK $jdkNum install complete: ${home.absolutePath}")
        
        installRuntimeLibs()
        runCatching { ensureJavaHomeShim() }
        runCatching { ensureRealJavaExecWrapper() }
    }

    fun repairPermissions(callback: (Boolean) -> Unit) {
        Thread {
            val ok: Boolean = try {
                if (!javaBin.exists()) {
                    log("JDK is not installed. Run install first.")
                    false
                } else {
                    log("Repairing runtime permissions...")
                    applyExecPermissions(javaHome)
                    runCatching { ensureRuntimeLibs() }
                    log("Permission repair complete: java.exists=${javaBin.exists()}")
                    javaBin.exists()
                }
            } catch (e: Exception) {
                log("Permission repair failed: ${e.message}")
                false
            }
            Handler(Looper.getMainLooper()).post { callback(ok) }
        }.start()
    }

    // JDK 설치 위치 확인 (이전 버전에서 발견한 경우 경로 이동)

    /**
     * JDK 설치 후 필요한 런타임 라이브러리(libz.so.1 등)가 빠진 경우 보정.
     */
    fun ensureRuntimeLibs(): Boolean {
        return runCatching {
            if (!isJdkInstalled) return false
            installRuntimeLibs()
            runCatching { ensureJavaHomeShim() }
            runCatching { ensureRealJavaExecWrapper() }
            true
        }.getOrElse { e ->
            Log.w(TAG, "ensureRuntimeLibs failed", e)
            false
        }
    }

    /** 이전 버전버그용: 설치 확인 로그를 위해 추가 출력 */
    fun ensureRuntimeLibsOrThrow(quiet: Boolean = false) {
        check(isJdkInstalled) { "JDK not installed" }
        withQuietLogs(quiet) {
            installRuntimeLibs()
            ensureJavaHomeShim()
            ensureRealJavaExecWrapper()
        }
    }

    private fun installJdk() {
        if (javaBin.exists()) {
            log("Local JDK already installed. Skipping.")
            return
        }

        log("Resolving OpenJDK 17 package URL from Termux repository...")
        val url = resolveTermuxJdkUrl()
        log("JDK URL: $url")

        val debFile = File(installDir, "jdk.deb")
        log("Downloading JDK (Bionic aarch64)...")
        downloadFile(url, debFile)

        log("Extracting JDK archive...")
        jdkRoot.mkdirs()
        extractDebToJdk(debFile, jdkRoot)
        debFile.delete()

        applyExecPermissions(javaHome)
        log("JDK install complete: ${javaHome.absolutePath}")
        log("  java: ${javaBin.exists()}")

    // JDK 설치 직후 런타임 라이브러리 1차 설치 (GradleRunner에서 호출 시 사용)
        installRuntimeLibs()
        runCatching { ensureJavaHomeShim() }
        runCatching { ensureRealJavaExecWrapper() }
    }

    private fun installJdk21() {
        // After extractTarGz strips prefix, java is at jdkRoot/bin/java
        val jdk21Bin = File(jdkRoot, "bin/java")
        if (jdk21Bin.exists() && jdk21Bin.length() > 1000000L) {
            log("JDK 21 already installed. Skipping.")
            return
        }
        // Also check the old path layout
        val jdk21BinAlt = File(jdkRoot, "lib/jvm/java-21-openjdk/bin/java")
        if (jdk21BinAlt.exists() && jdk21BinAlt.length() > 1000000L) {
            log("JDK 21 already installed (alt path). Skipping.")
            return
        }
        if (jdk21Bin.exists()) {
            log("Existing JDK 21 seems corrupted (too small), re-installing...")
            jdkRoot.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() }
        }

        log("Resolving custom OpenJDK 21 Android ARM64 URL...")
        val url = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/jdk21-android-21.0.14/jdk21-android-arm64.tar.gz"
        log("JDK 21 URL: $url")

        val jdkFile = File(installDir, "jdk21-android.tar.gz")
        log("Downloading JDK 21 (Android ARM64 Bionic, ~244MB)...")
        downloadFile(url, jdkFile)

        log("Extracting JDK 21 archive...")
        jdkRoot.mkdirs()
        JdkDownloader.extractTarGz(jdkFile, jdkRoot, { msg -> log("  $msg") }, File(context.applicationInfo.nativeLibraryDir))
        jdkFile.delete()

        applyExecPermissions(jdkRoot)
        log("JDK 21 install complete: ${jdk21Bin.absolutePath}")
        log("  java: ${jdk21Bin.exists()}")

        // Install runtime libs for JDK 21 too
        val savedEffective = effectiveJavaHome
        runCatching { installRuntimeLibs() }
        runCatching { ensureJavaHomeShim() }
        runCatching { ensureRealJavaExecWrapper() }
    }

    private fun installJdk17() {
        // After extractTarGz strips prefix, java is at jdkRoot/bin/java
        val jdk17Bin = File(jdkRoot, "bin/java")
        // javac validation: a proper javac launcher references the javac main class;
        // a corrupted javac.real (java.real copied over it) does NOT and fails
        // JdkImageTransform with "Unrecognized option: --system=none".
        val javacFile = if (File(jdkRoot, "bin/javac.real").isFile) File(jdkRoot, "bin/javac.real") else File(jdkRoot, "bin/javac")
        val javacOk = try {
            javacFile.isFile && javacFile.readBytes().toString(Charsets.ISO_8859_1).contains("com.sun.tools.javac")
        } catch (_: Exception) { false }
        if (jdk17Bin.exists() && jdk17Bin.length() > 1000000L && javacOk) {
            log("JDK 17 already installed. Skipping.")
            return
        }
        // Also check the old path layout
        val jdk17BinAlt = File(jdkRoot, "lib/jvm/java-17-openjdk/bin/java")
        if (jdk17BinAlt.exists() && jdk17BinAlt.length() > 1000000L && !javacFile.exists()) {
            log("JDK 17 already installed (alt path). Skipping.")
            return
        }
        if (jdk17Bin.exists()) {
            log("Existing JDK 17 seems corrupted (too small or broken javac), re-installing...")
            jdkRoot.listFiles()?.forEach { if (it.isDirectory) it.deleteRecursively() }
        }

        log("Resolving custom OpenJDK 17 Android ARM64 URL...")
        val url = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/MobileStudio-JDK17-Android-ARM64/jdk17-android-arm64.tar.gz"
        log("JDK 17 URL: $url")

        val jdkFile = File(installDir, "jdk17-android-arm64.tar.gz")
        log("Downloading JDK 17 (Android ARM64 Bionic)...")
        downloadFile(url, jdkFile)

        log("Extracting JDK 17 archive...")
        jdkRoot.mkdirs()
        JdkDownloader.extractTarGz(jdkFile, jdkRoot, { msg -> log("  $msg") }, File(context.applicationInfo.nativeLibraryDir))
        jdkFile.delete()

        applyExecPermissions(jdkRoot)
        log("JDK 17 install complete: ${jdk17Bin.absolutePath}")
        log("  java: ${jdk17Bin.exists()}")

        // Install runtime libs for JDK 17 too
        val savedEffective = effectiveJavaHome
        runCatching { installRuntimeLibs() }
        runCatching { ensureJavaHomeShim() }
        runCatching { ensureRealJavaExecWrapper() }
    }

    /**
    // JDK 가 존재하는 런타임 라이브러리의 javaHome/lib 위치 설치.
    // install() 또는 installJdk() 에서 호출. GradleRunner 에서 호출 시 사용.
     */
    private fun installRuntimeLibs() {
        val libDir = File(effectiveJavaHome, "lib").also { it.mkdirs() }

        // 1. libz.so.1
        // Always prefer a known-good libz.so.1 (Termux zlib) over /system/lib* to avoid verneed mismatch with OpenJDK libzip.so.
        val libz1 = File(libDir, "libz.so.1")
        // If libz.so.1 is a symlink into /system, it will frequently cause verneed mismatch with OpenJDK libzip.so.
        // In that case force-remove it and replace with Termux zlib copy. If it's a local symlink (e.g. -> libz.so.1.3.x),
        // that's fine.
        val existingLinkTarget = symlinkTarget(libz1)
        if (existingLinkTarget != null && existingLinkTarget.startsWith("/system/")) {
            runCatching { Os.remove(libz1.absolutePath) }
            runCatching { libz1.delete() }
        }
        val existingLibzReady = libz1.exists() &&
                libz1.length() > 0L &&
                (existingLinkTarget == null || !existingLinkTarget.startsWith("/system/"))
        log(if (!existingLibzReady) "runtime lib install: libz.so.1..." else "runtime lib ready: libz.so.1")
        var done = existingLibzReady
        runCatching {
            if (!done) {
                installTermuxLib("zlib", libDir, listOf("libz.so.1"))
                val target = symlinkTarget(libz1)
                done = libz1.exists() && libz1.length() > 0L
                // If the file is a symlink, ensure it doesn't point to /system (verneed mismatch risk)
                if (done && target != null && target.startsWith("/system/")) done = false
            }
        }.onFailure { e ->
            log("  [WARN] Termux zlib install failed: ${e.message}")
        }
        if (!done) {
            // Fallback: if user bundled a known-good bionic libz.so.1 in assets, install it.
            runCatching {
                val assetName = AssetUtils.listAssets(context)
                    .firstOrNull { it == "libz.so.1" || it.startsWith("libz.so.1.") }
                if (assetName != null) {
                    log("  Fallback: installing $assetName from assets...")
                    if (libz1.exists()) runCatching { Os.remove(libz1.absolutePath) }
                    AssetUtils.copyAsset(context, assetName, libz1, overwrite = true)
                    runCatching { Os.chmod(libz1.absolutePath, 0b110_100_100) }
                    val target = symlinkTarget(libz1)
                    done = libz1.exists() && libz1.length() > 0L && (target == null || !target.startsWith("/system/"))
                }
            }.onFailure { e ->
                log("  [WARN] assets libz.so.1 install failed: ${e.message}")
            }
        }
        if (!done) {
            // Do NOT silently fall back to /system/lib* here: it can break OpenJDK libzip.so with verneed mismatch.
            log("  [ERROR] libz.so.1 setup failed (Termux zlib required)")
            log("    path=${libz1.absolutePath} exists=${libz1.exists()} size=${libz1.length()} symlink=${isSymlink(libz1)} target=${symlinkTarget(libz1) ?: "-"}")
            throw RuntimeException("libz.so.1 install failed (need Termux zlib)")
        }

        // 2. libandroid-shmem.so
        val shmem = File(libDir, "libandroid-shmem.so")
        if (!shmem.exists() || isSymlink(shmem)) {
            if (isSymlink(shmem)) {
                runCatching { Os.remove(shmem.absolutePath) }
                runCatching { shmem.delete() }
            }
            log("runtime lib install: libandroid-shmem.so...")
    // APK 번들에서 복사 (jniLibs/arm64-v8a 포함 경우)
            val fromNative = File(context.applicationInfo.nativeLibraryDir, "libandroid-shmem.so")
            if (fromNative.exists()) {
                try { fromNative.copyTo(shmem, overwrite = true) }
                catch (_: Exception) {
                    for (pkg in listOf("libandroid-shmem", "android-shmem")) {
                        try { installTermuxLib(pkg, libDir, listOf("libandroid-shmem.so")); if (shmem.exists()) break }
                        catch (_: Exception) {}
                    }
                }
            } else {
                for (pkg in listOf("libandroid-shmem", "android-shmem")) {
                    try { installTermuxLib(pkg, libDir, listOf("libandroid-shmem.so")); if (shmem.exists()) break }
                    catch (_: Exception) {}
                }
            }
            if (!shmem.exists()) log("  [ERROR] libandroid-shmem.so install failed")
        }

        // 3. libandroid-spawn.so
        val spawn = File(libDir, "libandroid-spawn.so")
        if (!spawn.exists() || isSymlink(spawn)) {
            if (isSymlink(spawn)) {
                runCatching { Os.remove(spawn.absolutePath) }
                runCatching { spawn.delete() }
            }
            log("runtime lib install: libandroid-spawn.so...")
            val fromNative = File(context.applicationInfo.nativeLibraryDir, "libandroid-spawn.so")
            if (fromNative.exists()) {
                try { fromNative.copyTo(spawn, overwrite = true) }
                catch (_: Exception) {
                    try { installTermuxLib("libandroid-spawn", libDir, listOf("libandroid-spawn.so")) }
                    catch (e: Exception) { log("  [ERROR] libandroid-spawn.so install failed: ${e.message}") }
                }
            } else {
                try { installTermuxLib("libandroid-spawn", libDir, listOf("libandroid-spawn.so")) }
                catch (e: Exception) { log("  [ERROR] libandroid-spawn.so install failed: ${e.message}") }
            }
        }

        // 4. libc++_shared.so
        val cppShared = File(libDir, "libc++_shared.so")
        if (!cppShared.exists() || isSymlink(cppShared)) {
            if (isSymlink(cppShared)) {
                runCatching { Os.remove(cppShared.absolutePath) }
                runCatching { cppShared.delete() }
            }
            log("runtime lib install: libc++_shared.so...")
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            var done = false
            for (src in listOf(
                File(nativeLibDir, "libc++_shared.so"),
                File("/system/lib64/libc++_shared.so"),
                File("/system/lib/libc++_shared.so")
            )) {
                if (src.exists()) {
                    // Prefer copy over symlink to avoid namespace/selinux oddities on some ROMs
                    try {
                        log("  try copy: ${src.absolutePath}")
                        src.copyTo(cppShared, overwrite = true)
                        done = cppShared.exists() && cppShared.length() > 0L
                        if (done) break
                    } catch (e: Exception) {
                        log("  [WARN] copy failed: ${src.name}: ${e.message}")
                    }
                }
            }
            if (!done) {
                try {
                    installTermuxLib("libc++", libDir, listOf("libc++_shared.so"))
                    done = cppShared.exists() && cppShared.length() > 0L
                } catch (_: Exception) {}
            }
            if (!done) {
                runCatching {
                    val assetName = AssetUtils.listAssets(context).firstOrNull { it == "libc++_shared.so" }
                    if (assetName != null) {
                        log("  Fallback: installing $assetName from assets...")
                        AssetUtils.copyAsset(context, assetName, cppShared, overwrite = true)
                        runCatching { Os.chmod(cppShared.absolutePath, 0b110_100_100) }
                        done = cppShared.exists() && cppShared.length() > 0L
                    }
                }
            }
            if (!done) log("  [ERROR] libc++_shared.so setup failed")
        }

        log("runtime libs ready: " +
                "libz.so.1=${libz1.exists()}, " +
                "libandroid-shmem.so=${shmem.exists()}, " +
                "libandroid-spawn.so=${spawn.exists()}, " +
                "libc++_shared.so=${cppShared.exists()}, " +
                "libandroid-selinux.so=${File(libDir, "libandroid-selinux.so").exists()}")

        // Hard requirements for starting the embedded OpenJDK on Android.
        // If these are missing, Gradle/Java will fail with dlopen/linker errors.
        val missing = buildList {
            if (!libz1.exists()) add("libz.so.1")
            if (!cppShared.exists()) add("libc++_shared.so")
            if (!spawn.exists()) add("libandroid-spawn.so")
            if (!shmem.exists()) add("libandroid-shmem.so")
            if (!File(libDir, "libandroid-selinux.so").exists()) add("libandroid-selinux.so")
            if (!File(libDir, "libpcre2-8.so").exists()) add("libpcre2-8.so")
        }
        if (missing.isNotEmpty()) {
            // Attempt to install missing native libs before failing.
            // libandroid-selinux.so depends on libpcre2-8.so — install both.
            if ("libandroid-selinux.so" in missing || "libpcre2-8.so" in missing) {
                runCatching {
                    installTermuxLib("libandroid-selinux", libDir, listOf("libandroid-selinux.so"))
                }
                for (pkg in listOf("pcre2", "libpcre2")) {
                    runCatching {
                        installTermuxLib(pkg, libDir, listOf("libpcre2-8.so"))
                    }
                    if (File(libDir, "libpcre2-8.so").exists()) break
                }
            }
            val missingAfter = buildList {
                if (!libz1.exists()) add("libz.so.1")
                if (!cppShared.exists()) add("libc++_shared.so")
                if (!spawn.exists()) add("libandroid-spawn.so")
                if (!shmem.exists()) add("libandroid-shmem.so")
                if (!File(libDir, "libandroid-selinux.so").exists()) add("libandroid-selinux.so")
                if (!File(libDir, "libpcre2-8.so").exists()) add("libpcre2-8.so")
            }
            if (missingAfter.isNotEmpty()) {
                throw RuntimeException("Missing runtime libs: ${missingAfter.joinToString(", ")}")
            }
        }

    // 5. Stub glibc shlibs -> Android bionic libc via symlink
        // Android's linker64 does NOT support GNU ld linker scripts.
        // Glibc binaries expect separate libpthread, libdl, librt; bionic bundles
        // all of those into libc.so, so symlinks are the correct approach.
        data class Shim(val name: String)
        val shims = listOf(
            Shim("libpthread.so.0"),
            Shim("libdl.so.2"),
            Shim("librt.so.1"),
            Shim("libc.so.6"),
            Shim("libm.so.6"),
        )
        for (shim in shims) {
            val f = File(libDir, shim.name)
            if (!f.exists() || symlinkTarget(f) != "libc.so") {
                runCatching { if (f.exists() || isSymlink(f)) Os.remove(f.absolutePath) }
                runCatching { f.delete() }
        log("runtime lib install: ${shim.name} (symlink to libc.so)...")
                runCatching { Os.symlink("libc.so", f.absolutePath) }
                if (!f.exists()) {
                    runCatching {
                        val libc = File("/system/lib64/libc.so").takeIf { it.exists() }
                            ?: File("/system/lib/libc.so")
                        if (libc.exists()) libc.copyTo(f, overwrite = true)
                    }
                }
            }
        }

        // 6. Copy JDK lib/*.so into lib/server/ so the JVM's child linker
        //    namespace (which searches lib/server/ for libjvm.so) can resolve
        //    inter-library deps like libnet.so (needed by libnio.so).
        //    Symlinks DON'T work — bionic linker resolves the target and
        //    rejects paths outside the namespace. Hard-copy is required.
        ensureServerLibSymlinks()
    }

    /**
     * Copies every .so from $JAVA_HOME/lib/ into $JAVA_HOME/lib/server/
     * so the JVM's child linker namespace (clns-N) can find them.
     *
     * IMPORTANT: Symlinks DON'T work!  Bionic linker resolves symlink target
     * and rejects it if the target is outside the namespace's search path.
     * e.g. lib/server/libnet.so -> ../libnet.so resolves to lib/libnet.so
     * which is OUTSIDE lib/server/ → linker rejects it.
     * Always hard-copy instead.
     */
    private fun ensureServerLibSymlinks() {
        val home = effectiveJavaHome
        val libDir = File(home, "lib")
        val serverDir = File(home, "lib/server")
        if (!libDir.isDirectory) return
        serverDir.mkdirs()
        libDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".so") }
            ?.forEach { src ->
                val dst = File(serverDir, src.name)
                // Remove broken symlinks from previous versions
                if (dst.exists() && isSymlink(dst)) {
                    runCatching { dst.delete() }
                }
                // Skip if destination already exists with same size
                if (dst.exists() && !isSymlink(dst) && dst.length() == src.length()) return@forEach
                // Always hard copy
                runCatching { src.copyTo(dst, overwrite = true) }
                    .onFailure { e -> log("  [WARN] server copy failed: ${src.name}: ${e.message}") }
            }
    }

    private fun installTermuxLib(packageName: String, libDir: File, wantedLibs: List<String>) {
        val url     = resolveTermuxPackageUrl(packageName)
        log("  URL: $url")
        val debFile = File(installDir, "$packageName.deb")
        downloadFile(url, debFile)
        extractDebSoFiles(debFile, libDir, wantedLibs)
        debFile.delete()
        // Some Termux packages ship versioned SONAME files (e.g. libz.so.1.3.1) and a symlink libz.so.1 -> it.
        // When extracting with a filter, we may not get the symlink, so create a real file copy for the wanted name.
        for (wanted in wantedLibs) {
            val wantedFile = File(libDir, wanted)
            // If it's a symlink, replace with a real file copy (Android app sandbox + linker can be picky).
            if (isSymlink(wantedFile)) {
                runCatching { Os.remove(wantedFile.absolutePath) }
                    .onFailure { e -> log("  [WARN] remove symlink failed: ${wantedFile.name}: ${e.message}") }
                runCatching { wantedFile.delete() }
                    .onFailure { e -> log("  [WARN] delete symlink failed: ${wantedFile.name}: ${e.message}") }
            }
            if (!wantedFile.exists()) {
                val versioned = libDir.listFiles()
                    ?.firstOrNull { it.isFile && it.name.startsWith("$wanted.") && it.length() > 0L }
                if (versioned != null) {
                    runCatching { versioned.copyTo(wantedFile, overwrite = true) }
                        .onFailure { e -> log("  [WARN] copy ${versioned.name} -> ${wantedFile.name} failed: ${e.message}") }
                } else {
                    log("  [WARN] no versioned candidate found for $wanted in ${libDir.absolutePath}")
                }
            }
            log("  lib: ${wantedFile.name} exists=${wantedFile.exists()} size=${wantedFile.length()} symlink=${isSymlink(wantedFile)}")
            wantedFile.takeIf { it.exists() }?.setReadable(true, false)
        }
    }

    private fun isSymlink(f: File): Boolean = try {
        val st = Os.lstat(f.absolutePath)
        (st.st_mode.toInt() and OsConstants.S_IFMT) == OsConstants.S_IFLNK
    } catch (_: Exception) { false }

    private fun symlinkTarget(f: File): String? {
        return try {
            if (!f.exists() || !isSymlink(f)) null else Os.readlink(f.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun isElfBinary(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        return runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    magic[0] == 0x7F.toByte() &&
                    magic[1] == 'E'.code.toByte() &&
                    magic[2] == 'L'.code.toByte() &&
                    magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun isValidJavaBinary(file: File): Boolean {
        return isElfBinary(file)
    }

    private fun findRecoveredJavaBinary(root: File): File? {
        if (!root.exists()) return null
        val candidates = root.walkTopDown()
            .filter { it.isFile && it.name == "java" }
            .sortedByDescending { it.length() }
            .toList()
        if (candidates.isEmpty()) {
            log("[WARN] no java candidates found in recovery dir: ${root.absolutePath}")
            return null
        }
        candidates.take(5).forEach { candidate ->
            log("  recovery candidate: ${candidate.absolutePath} size=${candidate.length()} elf=${isElfBinary(candidate)}")
        }
        return candidates.firstOrNull { isValidJavaBinary(it) } ?: candidates.firstOrNull { isElfBinary(it) }
    }

    private fun resolveTermuxPackageUrl(packageName: String): String {
        val baseUrl     = "https://packages-cf.termux.dev/apt/termux-main"
        val packagesUrl = "$baseUrl/dists/stable/main/binary-aarch64/Packages"
        val conn = (URL(packagesUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000; connect()
        }
        check(conn.responseCode == 200) { "Packages response error: ${conn.responseCode}" }
        val text = conn.inputStream.bufferedReader().readText()
        conn.disconnect()

        val block = text.split("\n\n").firstOrNull { b ->
            b.lineSequence().any { it.trim() == "Package: $packageName" }
        } ?: throw RuntimeException("Package not found: $packageName")

        val filename = block.lineSequence()
            .firstOrNull { it.startsWith("Filename:") }
            ?.removePrefix("Filename:")?.trim()
            ?: throw RuntimeException("$packageName has no Filename field")

        return "$baseUrl/$filename"
    }

    private fun extractDebSoFiles(debFile: File, libDir: File, wantedLibs: List<String>) {
        extractDebToJdk(debFile, libDir, soFilter = wantedLibs)
    }

    private fun resolveTermuxJdkUrl(packageName: String = "openjdk-17"): String {
        // For JDK 17/21, use our custom-built Android ARM64 binaries from GitHub
        return when (packageName) {
            "openjdk-17" -> "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/MobileStudio-JDK17-Android-ARM64/jdk17-android-arm64.tar.gz"
            "openjdk-21" -> "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/download/jdk21-android-21.0.14/jdk21-android-arm64.tar.gz"
            else -> throw RuntimeException("Unsupported JDK package: $packageName. Only openjdk-17 and openjdk-21 are supported.")
        }
    }

    private fun resolveTermuxJdk21Url(): String = resolveTermuxJdkUrl("openjdk-21")

    private fun extractDebToJdk(debFile: File, destDir: File, soFilter: List<String>? = null) {
        val dataTarXz = File(installDir, "data.tar.xz")
        try {
            extractArMember(debFile, "data.tar.xz", dataTarXz)
            XZInputStream(dataTarXz.inputStream().buffered()).use { xzis ->
                extractTarStreamRemap(
                    input       = xzis,
                    destDir     = destDir,
                    stripPrefix = "data/data/com.termux/files/usr",
                    soFilter    = soFilter
                )
            }
        } finally {
            dataTarXz.delete()
        }
    }

    private fun extractArMember(arFile: File, memberName: String, dest: File) {
        arFile.inputStream().buffered().use { input ->
            val magic = ByteArray(8)
            input.read(magic)
        check(String(magic) == "!<arch>\n") { "ar 매직 불일치 ${String(magic)}" }
            val hdrBuf = ByteArray(60)
            while (true) {
                val n = input.read(hdrBuf)
                if (n < 60) throw RuntimeException(".deb member not found: $memberName")
                val name    = String(hdrBuf, 0, 16).trim().trimEnd('/')
                val size    = String(hdrBuf, 48, 10).trim().toLong()
                if (name == memberName.trimEnd('/')) {
                    FileOutputStream(dest).use { out ->
                        var rem = size; val buf = ByteArray(32 * 1024)
                        while (rem > 0) {
                            val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (r < 0) break; out.write(buf, 0, r); rem -= r
                        }
                    }
                    return
                } else {
                    var rem = size + (size % 2); val buf = ByteArray(4096)
                    while (rem > 0) {
                        val r = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                        if (r < 0) break; rem -= r
                    }
                }
            }
        }
    }

    private fun extractTarStreamRemap(
        input: InputStream,
        destDir: File,
        stripPrefix: String,
        soFilter: List<String>? = null
    ) {
        var fileCount = 0
        val pendingSymlinks = mutableListOf<Pair<String, String>>()

        fun readHeader(): ByteArray? {
            val buf = ByteArray(512); var read = 0
            while (read < 512) {
                val n = input.read(buf, read, 512 - read)
                if (n < 0) return null; read += n
            }
            return if (buf.all { it == 0.toByte() }) null else buf
        }

        fun ByteArray.str(off: Int, len: Int): String {
            val end = (off until (off + len)).firstOrNull { this[it] == 0.toByte() } ?: (off + len)
            return String(this, off, end - off, Charsets.UTF_8).trim()
        }

        fun parseSize(hdr: ByteArray): Long =
            if (hdr[124].toInt() and 0x80 != 0) {
                var v = 0L; for (i in 125..135) v = (v shl 8) or (hdr[i].toLong() and 0xFF); v
            } else {
                val oct = hdr.str(124, 12).filter { it in '0'..'7' }
                if (oct.isEmpty()) 0L else oct.toLong(8)
            }

        fun parseMode(hdr: ByteArray): Int {
            val oct = hdr.str(100, 8).filter { it in '0'..'7' }
            return if (oct.isEmpty()) 0b110_100_100 else oct.toInt(8)
        }

        fun skipBytes(n: Long) {
            var rem = n; val tmp = ByteArray(4096)
            while (rem > 0) {
                val r = input.read(tmp, 0, minOf(tmp.size.toLong(), rem).toInt())
                if (r < 0) break; rem -= r
            }
        }

        val stripCount = stripPrefix.trim('/').split("/").filter { it.isNotEmpty() }.size
        var gnuLongName: String? = null
        var gnuLongLink: String? = null

        while (true) {
            val hdr = readHeader() ?: break
            val rawName  = hdr.str(0, 100)
            val prefix   = hdr.str(345, 155)
            val typeFlag = hdr[156].toInt().toChar()
            val linkName = hdr.str(157, 100)
            val size     = parseSize(hdr)
            val mode     = parseMode(hdr)
            val blockCount = (size + 511) / 512

            if (typeFlag == 'L' || typeFlag == 'K') {
                val buf = ByteArray(size.toInt()); var r = 0
                while (r < size) { val n = input.read(buf, r, (size - r).toInt()); if (n < 0) break; r += n }
                skipBytes(blockCount * 512 - size)
                val s = String(buf, Charsets.UTF_8).trimEnd('\u0000')
                if (typeFlag == 'L') gnuLongName = s else gnuLongLink = s
                continue
            }
            if (typeFlag == 'x' || typeFlag == 'g') {
                val buf = ByteArray(size.toInt()); var r = 0
                while (r < size) { val n = input.read(buf, r, (size - r).toInt()); if (n < 0) break; r += n }
                skipBytes(blockCount * 512 - size)
                val text = String(buf, Charsets.UTF_8)
                Regex("""^\d+ path=(.+)$""", RegexOption.MULTILINE).find(text)?.let { gnuLongName = it.groupValues[1] }
                Regex("""^\d+ linkpath=(.+)$""", RegexOption.MULTILINE).find(text)?.let { gnuLongLink = it.groupValues[1] }
                continue
            }

            val fullName = (gnuLongName
                ?: if (prefix.isNotEmpty()) "$prefix/$rawName" else rawName)
                .removePrefix("./").trimEnd('/')
            val linkTarget = gnuLongLink ?: linkName
            gnuLongName = null; gnuLongLink = null

            if (fullName.isBlank()) { skipBytes(blockCount * 512); continue }

            val parts    = fullName.split("/").filter { it.isNotEmpty() }
            val stripped = if (parts.size <= stripCount) null
            else parts.drop(stripCount).joinToString("/")

            if (stripped != null && stripped.isNotEmpty()) {
                log("Extracting: $stripped")
            }

            when (typeFlag) {
                '0', '\u0000', ' ' -> {
                    val fileName = stripped?.substringAfterLast('/') ?: ""
                    val shouldExtract = stripped != null && (
                            soFilter == null ||
                                    fileName in soFilter ||
                                    soFilter.any { wanted -> fileName.startsWith("$wanted.") }
                            )
                    if (shouldExtract) {
                        val dest = if (soFilter != null) File(destDir, fileName)
                        else File(destDir, stripped!!)
                        dest.parentFile?.mkdirs()
                        try {
                            FileOutputStream(dest).use { fos ->
                                var rem = size; val buf = ByteArray(32 * 1024)
                                while (rem > 0) {
                                    val n = input.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                                    if (n < 0) break; fos.write(buf, 0, n); rem -= n
                                }
                            }
                            try { Os.chmod(dest.absolutePath, mode) }
                            catch (_: Exception) { if (mode and 0b001_000_000 != 0) dest.setExecutable(true, false) }
                            fileCount++
                        } catch (e: Exception) {
                            log("  [WARN] failed to write file: $stripped -> ${e.message}")
                        }
                        skipBytes(blockCount * 512 - size)
                    } else skipBytes(blockCount * 512)
                }
                '2' -> if (stripped != null && soFilter == null) pendingSymlinks += stripped to linkTarget
                '5' -> if (stripped != null) File(destDir, stripped).mkdirs()
                else -> skipBytes(blockCount * 512)
            }
        }

        var symlinkOk = 0
        for ((name, target) in pendingSymlinks) {
            try {
                val link = File(destDir, name)
                link.parentFile?.mkdirs()
                try { Os.remove(link.absolutePath) } catch (_: Exception) {}
                Os.symlink(target, link.absolutePath)
                symlinkOk++
            } catch (_: Exception) {}
        }
        log("  extract complete: files=$fileCount symlinks=$symlinkOk/${pendingSymlinks.size}")
    }

    // 설치 Gradle 위치 확인 및 경로 검증

    private fun installGradle(requestedVersion: String? = null) {
        if (isGradleInstalled && requestedVersion == null) {
            log("Gradle already installed: ${gradleHome.absolutePath}")
            ensureGradleLauncherPatchedForAndroid()
            return
        }

        val toolchain = ToolchainManager(context)
        val anyInstalled = toolchain.installedVersions(ToolchainManager.Kind.GRADLE).firstOrNull()
        if (anyInstalled != null && requestedVersion == null) {
            log("Found installed Gradle: $anyInstalled, skipping download.")
            ensureGradleLauncherPatchedForAndroid()
            return
        }

        val activeVersion = requestedVersion
            ?.takeIf { it.startsWith("gradle-") }
            ?: toolchain.activeVersion(ToolchainManager.Kind.GRADLE)
            ?: ToolchainManager.DEFAULT_GRADLE
        val versionRoot = File(toolchain.kindDir(ToolchainManager.Kind.GRADLE), activeVersion)
        if (File(versionRoot, "lib").isDirectory) {
            toolchain.setActiveVersion(ToolchainManager.Kind.GRADLE, activeVersion)
            log("Gradle already installed: ${versionRoot.absolutePath}")
            ensureGradleLauncherPatchedForAndroid()
            return
        }
        val gradleVersion = activeVersion.removePrefix("gradle-")
        val url     = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
        val zipFile = File(installDir, "gradle.zip")
        val totalBytes = DownloadCenter.probeContentLength(url)
        if (!DownloadCenter.confirm("Gradle $gradleVersion", url, versionRoot.absolutePath, zipFile.name, totalBytes)) {
            log("Gradle download declined — the Gradle build cannot continue")
            throw RuntimeException("Gradle download declined by user")
        }
        DownloadCenter.begin("Gradle $gradleVersion", url, versionRoot.absolutePath, zipFile.name, totalBytes)
        try {
            log("Downloading Gradle (v$gradleVersion)...")
            downloadFile(url, zipFile)
            log("Extracting Gradle...")
            DownloadCenter.stage("Extracting Gradle $gradleVersion...")
            versionRoot.mkdirs()
            extractZip(zipFile, versionRoot, stripFirstDir = true)
            zipFile.delete()
            toolchain.setActiveVersion(ToolchainManager.Kind.GRADLE, activeVersion)
            ensureGradleLauncherPatchedForAndroid()
            log("Gradle install complete: ${gradleHome.absolutePath}")
            log("  launcher: ${gradleLauncherJar?.name ?: "missing"}")
            DownloadCenter.done("Gradle $gradleVersion installed")
        } catch (e: Exception) {
            DownloadCenter.fail("Gradle download failed: ${e.message}")
            throw e
        }
    }

private fun patchGradleLauncherForAndroid(jarFile: File) {
        val marker = File(jarFile.parentFile, "${jarFile.name}.mobilestudio-inprocess-v2")
        // Always re-verify: even if marker exists, check if patch is actually applied
        // (stale markers from previous builds can cause patching to be skipped)
        val backup = File(jarFile.parentFile, "${jarFile.name}.mobilestudio-original")

        val tmp = File(jarFile.parentFile, "${jarFile.name}.tmp")
        var patched = false
        var alreadyPatched = false
        var classFound = false

        ZipFile(jarFile).use { zip ->
            ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val next = ZipEntry(entry.name).apply { time = entry.time }
                    out.putNextEntry(next)
                    if (!entry.isDirectory) {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val isTarget = entry.name == targetClass || (entry.name.startsWith("META-INF/versions/") && entry.name.endsWith("/$targetClass"))
                        val outputBytes = if (isTarget) {
                            classFound = true
                            val result = patchBuildActionsFactoryClass(bytes)
                            if (result.first) patched = true
                            if (result.second) alreadyPatched = true
                            result.third
                        } else if (entry.name == "META-INF/MANIFEST.MF") {
                            // Ensure Main-Class is present for Gradle 9.x+
                            ensureMainClassManifest(bytes)
                        } else {
                            bytes
                        }
                        out.write(outputBytes)
                    }
                    out.closeEntry()
                }
            }
        }

        if (!classFound) {
            // Gradle 8.14+: BuildActionsFactory moved out of the entry point jar.
            // Search ALL JARs in lib/ for the target class.
            val allJars = gradleLibDir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".jar") && it.absolutePath != jarFile.absolutePath
                    && !it.name.endsWith(".tmp")
            } ?: emptyList()
            for (candidate in allJars) {
                val hasTarget = runCatching {
                    ZipFile(candidate).use { zip -> zip.getEntry(targetClass) != null }
                }.getOrDefault(false)
                if (hasTarget) {
                    log("Gradle launcher patch: $targetClass found in ${candidate.name}, patching.")
                    patchGradleLauncherForAndroid(candidate)
                    val candMarker = File(candidate.parentFile, "${candidate.name}.mobilestudio-inprocess-v2")
                    val candState = candMarker.takeIf { it.exists() }?.readText()?.trim()
                    marker.writeText(if (candState == "patched") "patched\n" else "patched-skip\n")
                    tmp.delete()
                    return
                }
            }
        }
        if (!classFound) {
            tmp.delete()
            // Newer Gradle versions (8.8+) refactored the launcher; BuildActionsFactory
            // may be absent or restructured. Fall back to --no-daemon args in GradleRunner.
            log("Gradle launcher patch: $targetClass not found (Gradle version changed).")
            log("  Falling back to --no-daemon args only when daemon mode is not requested.")
            // Still ensure manifest has Main-Class for 9.x+
            fixManifestMainClassIfMissing(jarFile)
            marker.writeText("patched-skip\n")
            return
        }
        if (patched) {
            if (!backup.exists() || backup.length() == 0L) {
                jarFile.copyTo(backup, overwrite = true)
            }
            tmp.copyTo(jarFile, overwrite = true)
            tmp.delete()
            marker.writeText("patched\n")
            log("Gradle launcher patched for Android in-process build v2: ${jarFile.name}")
        } else {
            tmp.delete()
            if (alreadyPatched) {
                marker.writeText("patched\n")
            } else {
                // Fallback: Newer Gradle versions might have refactored this again.
                // Don't fail the whole install, just log it and fallback to --no-daemon args.
                log("Gradle launcher patch failed: daemon branch pattern not found in ${jarFile.name}. Falling back to --no-daemon.")
                fixManifestMainClassIfMissing(jarFile)
                marker.writeText("patched-skip\n")
            }
        }
    }

    private fun ensureMainClassManifest(bytes: ByteArray): ByteArray {
        val manifest = String(bytes, Charsets.UTF_8)
        val expected = "Main-Class: org.gradle.launcher.GradleMain"
        val lines = manifest.split("\n").toMutableList()
        val mainClassIdx = lines.indexOfFirst { it.startsWith("Main-Class:") }
        if (mainClassIdx >= 0) {
            if (lines[mainClassIdx].trim() == expected) return bytes
            log("Fixing Main-Class in Gradle launcher manifest: ${lines[mainClassIdx].trim()} -> org.gradle.launcher.GradleMain")
            lines[mainClassIdx] = expected
        } else {
            log("Adding Main-Class to Gradle launcher manifest")
            val manifestVersionIdx = lines.indexOfFirst { it.startsWith("Manifest-Version:") }
            if (manifestVersionIdx >= 0) {
                lines.add(manifestVersionIdx + 1, expected)
            }
        }
        return lines.joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    private fun fixManifestMainClassIfMissing(jarFile: File) {
        val tmp = File(jarFile.parentFile, "${jarFile.name}.manifest.tmp")
        try {
            ZipFile(jarFile).use { zip ->
                ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val next = ZipEntry(entry.name).apply { time = entry.time }
                        out.putNextEntry(next)
                        if (!entry.isDirectory && entry.name == "META-INF/MANIFEST.MF") {
                            val bytes = zip.getInputStream(entry).use { it.readBytes() }
                            out.write(ensureMainClassManifest(bytes))
                        } else {
                            zip.getInputStream(entry).use { it.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
            tmp.copyTo(jarFile, overwrite = true)
            tmp.delete()
        } catch (e: Exception) {
            tmp.delete()
            log("Failed to fix manifest: ${e.message}")
        }
    }

    private fun restoreGradleLauncherForDaemon(jarFile: File) {
        val marker = File(jarFile.parentFile, "${jarFile.name}.mobilestudio-inprocess-v2")
        if (!marker.exists()) return

        val backup = File(jarFile.parentFile, "${jarFile.name}.mobilestudio-original")
        if (backup.exists() && backup.length() > 0L) {
            backup.copyTo(jarFile, overwrite = true)
            marker.delete()
            log("Gradle launcher restored for daemon mode: ${jarFile.name}")
            return
        }

        runCatching {
            val restored = downloadOriginalGradleLauncherJar(jarFile)
            restored.copyTo(jarFile, overwrite = true)
            restored.delete()
            marker.delete()
            log("Gradle launcher re-downloaded for daemon mode: ${jarFile.name}")
        }.onFailure {
            log("  [WARN] Gradle daemon requested but launcher restore failed: ${it.message}")
        }
    }

    private fun downloadOriginalGradleLauncherJar(jarFile: File): File {
        val version = Regex("""(?:gradle-launcher|gradle-gradle-cli-main|gradle-gradle-cli)-(?:gradle-)?(.+?)\.jar""")
            .find(jarFile.name)
            ?.groupValues
            ?.getOrNull(1)
            ?: "8.2"
        val zipFile = File(installDir, "gradle-$version-bin.zip")
        if (!zipFile.exists() || zipFile.length() == 0L) {
            downloadFile("https://services.gradle.org/distributions/gradle-$version-bin.zip", zipFile)
        }

        val out = File(jarFile.parentFile, "${jarFile.name}.daemon-restore.tmp")
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.endsWith("/lib/${jarFile.name}")) {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(out).use { output -> input.copyTo(output) }
                    }
                    return out
                }
            }
        }
        out.delete()
        error("Original Gradle launcher not found in gradle-$version-bin.zip")
    }

    private fun patchBuildActionsFactoryClass(bytes: ByteArray): Triple<Boolean, Boolean, ByteArray> {
        // Gradle 8.14+ / 9.x: refactored class. Force canUseCurrentProcess() to return true
        // so builds always run in-process instead of forking a single-use daemon.
        val (patchedNew, alreadyPatchedNew, outNew) = patchBuildActionsFactoryClassNew(bytes)
        if (patchedNew || alreadyPatchedNew) return Triple(patchedNew, alreadyPatchedNew, outNew)
        log("Gradle launcher patch: new-style patcher did not match (class size=${bytes.size}), falling back to legacy byte pattern")

        val out = bytes.copyOf()
        var patched = false
        var alreadyPatched = false

        for (i in 0 until out.size - 13) {
            val isV2Patched =
                out[i].u() == 0x04 &&
                    out[i + 1].u() == 0x00 &&
                    out[i + 2].u() == 0x00 &&
                    out[i + 3].u() == 0x00 &&
                    out[i + 4].u() == 0x00 &&
                    out[i + 5].u() == 0x00 &&
                    out[i + 6].u() == 0x00 &&
                    out[i + 7].u() == 0x00 &&
                    out[i + 8].u() == 0x99 &&
                    out[i + 9].u() == 0x00 &&
                    out[i + 10].u() == 0x13 &&
                    out[i + 11].u() == 0x2a &&
                    out[i + 12].u() == 0x2d
            if (isV2Patched) {
                alreadyPatched = true
                break
            }

            val isBranchToSingleUseDaemon =
                out[i].u() == 0x2a &&
                    out[i + 1].u() == 0x2d &&
                    out[i + 2].u() == 0xb6 &&
                    out[i + 5].u() == 0xb6 &&
                    (out[i + 8].u() == 0x99 || out[i + 8].u() == 0x57) &&
                    out[i + 11].u() == 0x2a &&
                    out[i + 12].u() == 0x2d
            if (isBranchToSingleUseDaemon) {
                out[i] = 0x04.toByte()
                for (j in 1..7) out[i + j] = 0x00.toByte()
                out[i + 8] = 0x99.toByte()
                out[i + 9] = 0x00.toByte()
                out[i + 10] = 0x13.toByte()
                patched = true
                break
            }
        }

        return Triple(patched, alreadyPatched, out)
    }

    /** Gradle 8.14+ / 9.x: parse the class file and rebuild the Code attribute of
     *  `canUseCurrentProcess(DaemonParameters, DaemonRequestContext)Z` as
     *  `iconst_1; ireturn` (2 bytes, no branches, no stack map table) so the build
     *  always runs in-process instead of forking a single-use daemon, which cannot
     *  execve() on Android. Verified by running Gradle 8.14.3 and 9.1.0 with the
     *  patched jar (both are identical 15810-byte classes). */
    private fun patchBuildActionsFactoryClassNew(bytes: ByteArray): Triple<Boolean, Boolean, ByteArray> {
        val b = bytes
        val methodNames = mutableListOf<String>()
        try {
            val r = ClassFileReader(b)
            val cpCount = r.u16()
            log("Gradle launcher patch: cpCount=$cpCount classSize=${b.size}")
            val cpNames = HashMap<Int, String>()
            var i = 1
            while (i < cpCount) {
                val pBefore = r.p
                r.requireAtLeast(1, "CP tag")
                val tag = b[r.p].u(); r.p++
                when (tag) {
                    1 -> { val len = r.u16(); r.requireAtLeast(len, "CP UTF8"); cpNames[i] = String(b, r.p, len, Charsets.UTF_8); r.p += len }
                    3, 4 -> r.skip(4)
                    5, 6 -> { r.skip(8); i++ } // long/double take two slots
                    7, 8, 16, 19, 20 -> r.skip(2)
                    9, 10, 11, 12, 17, 18 -> r.skip(4)
                    15 -> r.skip(3)
                    else -> {
                        log("Gradle launcher patch: unknown constant pool tag $tag at $pBefore, skipping binary scan")
                        return Triple(false, false, bytes)
                    }
                }
                i++
            }
            log("Gradle launcher patch: after CP at p=${r.p}")
            r.skip(6) // access_flags, this_class, super_class
            val ifaceCount = r.u16(); r.skip(ifaceCount * 2)
            val fieldCount = r.u16()
            log("Gradle launcher patch: fieldCount=$fieldCount at p=${r.p}")
            repeat(fieldCount) {
                r.skip(6) // access_flags, name, desc
                val fac = r.u16()
                repeat(fac) {
                    r.skip(2) // attr name
                    val attrLen = r.u32()
                    r.skip(attrLen) // attr data
                }
            }
            log("Gradle launcher patch: after fields at p=${r.p}")
            val methodCount = r.u16()
            log("Gradle launcher patch: methodCount=$methodCount at p=${r.p}")
            repeat(methodCount) {
                r.skip(2) // access_flags
                val nameIdx = r.u16()
                val descIdx = r.u16()
                val name = cpNames[nameIdx]
                val desc = cpNames[descIdx]
                methodNames.add("$name:$desc")
                val attrCount = r.u16()
                var attrStart = -1 // Code attribute info start (after name_index + length)
                var attrEnd = -1
                var codeLen = -1
                repeat(attrCount) {
                    val an = r.u16()
                    val len = r.u32()
                    val s = r.p
                    if (cpNames[an] == "Code" && len >= 8) {
                        r.skip(4) // max_stack, max_locals
                        codeLen = r.u32()
                        attrStart = s
                        attrEnd = s + len
                    }
                    r.p = s + len
                }
                if (name == "canUseCurrentProcess" && desc != null && desc.endsWith(")Z") && attrStart > 0) {
                    log("Gradle launcher patch: found canUseCurrentProcess at attrStart=$attrStart codeLen=$codeLen")
                    r.requireAtLeast(10, "canUseCurrentProcess code check")
                    if (codeLen >= 2 && b[attrStart + 8].u() == 0x04 && b[attrStart + 9].u() == 0xac) {
                        return Triple(false, true, bytes) // already patched
                    }
                    // Rebuild the Code attribute: name(2) length(4) max_stack(2)=1
                    // max_locals(2)=3 code_length(4)=2 code(2)=iconst_1;ireturn
                    // exception_table_length(2)=0 attributes_count(2)=0.
                    // The old stack map table and exception table are dropped, and the
                    // file is re-assembled (the tail after attrEnd is shifted forward).
                    val attrInfoStart = attrStart - 6
                    val out = ByteArray(attrInfoStart + 20 + (b.size - attrEnd))
                    System.arraycopy(b, 0, out, 0, attrInfoStart)
                    out[attrInfoStart] = b[attrInfoStart]
                    out[attrInfoStart + 1] = b[attrInfoStart + 1]
                    out[attrInfoStart + 2] = 0; out[attrInfoStart + 3] = 0
                    out[attrInfoStart + 4] = 0; out[attrInfoStart + 5] = 14 // attribute length
                    out[attrInfoStart + 6] = 0; out[attrInfoStart + 7] = 1  // max_stack
                    out[attrInfoStart + 8] = 0; out[attrInfoStart + 9] = 3  // max_locals (this + 2 args)
                    out[attrInfoStart + 10] = 0; out[attrInfoStart + 11] = 0
                    out[attrInfoStart + 12] = 0; out[attrInfoStart + 13] = 2 // code_length
                    out[attrInfoStart + 14] = 4; out[attrInfoStart + 15] = 0xac.toByte() // iconst_1; ireturn
                    out[attrInfoStart + 16] = 0; out[attrInfoStart + 17] = 0 // exception_table_length
                    out[attrInfoStart + 18] = 0; out[attrInfoStart + 19] = 0 // attributes_count
                    System.arraycopy(b, attrEnd, out, attrInfoStart + 20, b.size - attrEnd)
                    log("Gradle launcher patch: patched canUseCurrentProcess (${b.size} -> ${out.size} bytes)")
                    return Triple(true, false, out)
                }
            }
        } catch (e: Exception) {
            log("Gradle launcher patch: parse error at p=${(e as? ClassFormatException)?.message ?: e.message?.take(200)}")
            return Triple(false, false, bytes)
        }
        log("Gradle launcher patch: canUseCurrentProcess not found. Methods: ${methodNames.joinToString(", ").take(500)}")
        return Triple(false, false, bytes)
    }

    /**
     * Patch DaemonCompatibilitySpec.whyUnsatisfied() to return an empty list immediately
     * on Android. This is the second-layer fix: when BuildActionsFactory.canUseCurrentProcess
     * is absent (Gradle 9.7+), Gradle forks a single-use daemon. That daemon process is
     * then checked against DaemonCompatibilitySpec.whyUnsatisfied(), which rejects it because
     * daemonOpts, agentStatus, and nativeServicesMode differ on Android.
     *
     * The patch finds the bytecode pattern `new ArrayList; dup; invokespecial; astore_N`
     * (which creates the empty reasons list) and changes `astore_N` → `areturn`, making the
     * method return the empty list immediately — meaning "all checks passed, daemon is compatible".
     *
     * Falls back to full Code attribute replacement if the pattern is not found.
     */
    private fun patchDaemonCompatibilitySpecClass(bytes: ByteArray): Triple<Boolean, Boolean, ByteArray> {
        val b = bytes
        val methodNames = mutableListOf<String>()
        try {
            val r = ClassFileReader(b)
            val cpCount = r.u16()
            val cpNames = HashMap<Int, String>()
            val cpTags = HashMap<Int, Int>()
            var i = 1
            while (i < cpCount) {
                val pBefore = r.p
                r.requireAtLeast(1, "CP tag")
                val tag = b[r.p].toInt() and 0xff; r.p++
                cpTags[i] = tag
                when (tag) {
                    1 -> { val len = r.u16(); r.requireAtLeast(len, "CP UTF8"); cpNames[i] = String(b, r.p, len, Charsets.UTF_8); r.p += len }
                    3, 4 -> r.skip(4)
                    5, 6 -> { r.skip(8); i++ }
                    7, 8, 16, 19, 20 -> r.skip(2)
                    9, 10, 11, 12, 17, 18 -> r.skip(4)
                    15 -> r.skip(3)
                    else -> {
                        log("DaemonCompatibilitySpec patch: unknown cp tag $tag at $pBefore")
                        return Triple(false, false, bytes)
                    }
                }
                i++
            }
            r.skip(6) // access_flags, this_class, super_class
            val ifaceCount = r.u16(); r.skip(ifaceCount * 2)
            val fieldCount = r.u16()
            repeat(fieldCount) {
                r.skip(6)
                val fac = r.u16()
                repeat(fac) { r.skip(2); r.skip(r.u32()) }
            }
            val methodCount = r.u16()
            repeat(methodCount) {
                r.skip(2) // access_flags
                val nameIdx = r.u16()
                val descIdx = r.u16()
                val name = cpNames[nameIdx]
                val desc = cpNames[descIdx]
                methodNames.add("$name:$desc")
                val attrCount = r.u16()
                var codeAttrStart = -1   // offset of attribute_info (name_idx u2 + length u4 + data)
                var codeAttrEnd = -1     // offset right after the attribute data
                var codeOffset = -1      // offset of bytecode within class bytes
                var codeLen = -1
                var codeAttrNameIdx = -1 // constant pool index of "Code" attribute name
                repeat(attrCount) {
                    val an = r.u16()
                    val len = r.u32()
                    val s = r.p
                    if (cpNames[an] == "Code" && len >= 8) {
                        codeAttrNameIdx = an
                        r.skip(4) // max_stack, max_locals
                        codeLen = r.u32()
                        codeAttrStart = s - 6 // back up to name_index
                        codeOffset = r.p
                        codeAttrEnd = s + len
                    }
                    r.p = s + len
                }
                if (name == "whyUnsatisfied" && codeAttrStart > 0 && codeLen > 0) {
                    // Check if already patched: first bytecode should NOT be `areturn` (0xB0)
                    // or `aconst_null; areturn` (01 B0) which would be our full-replacement patch
                    if (codeLen >= 2 && b[codeOffset].toInt() and 0xff == 0x01 &&
                        b[codeOffset + 1].toInt() and 0xff == 0xb0) {
                        // Already patched — but REPAIR max_locals if an older broken patch
                        // wrote 0 (instance methods need this + params → VerifyError otherwise)
                        val curMaxLocals = ((b[codeAttrStart + 8].toInt() and 0xFF) shl 8) or
                                (b[codeAttrStart + 9].toInt() and 0xFF)
                        if (curMaxLocals < 1) {
                            val out = b.copyOf()
                            out[codeAttrStart + 8] = 0
                            out[codeAttrStart + 9] = 4
                            log("DaemonCompatibilitySpec patch: repaired corrupted max_locals (0 → 4)")
                            return Triple(true, false, out)
                        }
                        return Triple(false, true, bytes) // already patched (return null)
                    }
                    if (codeLen >= 2 && b[codeOffset].toInt() and 0xff == 0x03 &&
                        b[codeOffset + 1].toInt() and 0xff == 0xac) {
                        return Triple(false, true, bytes) // already patched (return 0 / empty)
                    }
                    if (codeLen >= 1 && b[codeOffset].toInt() and 0xff == 0xb0) {
                        return Triple(false, true, bytes) // already patched (areturn)
                    }

                    // Strategy 1: Find `new ArrayList; dup; invokespecial; astore_N`
                    // and change astore_N (4B-4E) to areturn (B0).
                    // Works for Gradle versions where whyUnsatisfied returns Collection<String>.
                    val patch1 = patchDaemonSpecAstoreToAreturn(b, codeOffset, codeLen)
                    if (patch1 != null) {
                        log("DaemonCompatibilitySpec patch: patched whyUnsatisfied (astore→areturn)")
                        return Triple(true, false, patch1)
                    }

                    // Strategy 2: Full Code attribute replacement with `aconst_null; areturn`.
                    // Works for ALL Gradle versions: returns null (= "satisfied") regardless
                    // of whether whyUnsatisfied returns String or Collection<String>.
                    val patch2 = replaceDaemonSpecCodeAttrWithNull(b, codeAttrStart, codeAttrEnd, codeAttrNameIdx)
                    if (patch2 != null) {
                        log("DaemonCompatibilitySpec patch: patched whyUnsatisfied (aconst_null→areturn)")
                        return Triple(true, false, patch2)
                    }

                    log("DaemonCompatibilitySpec patch: found whyUnsatisfied but could not patch (codeLen=$codeLen)")
                    return Triple(false, false, bytes)
                }
            }
        } catch (e: Exception) {
            log("DaemonCompatibilitySpec patch error: ${e.message?.take(200)}")
            return Triple(false, false, bytes)
        }
        log("DaemonCompatibilitySpec patch: whyUnsatisfied not found. Methods: ${methodNames.joinToString(", ").take(500)}")
        return Triple(false, false, bytes)
    }

    /** Find `new ArrayList( BB xx xx 59 B7 xx xx 4X )` in bytecode and change astore_N → areturn */
    private fun patchDaemonSpecAstoreToAreturn(b: ByteArray, codeOffset: Int, codeLen: Int): ByteArray? {
        val end = codeOffset + codeLen - 7
        for (i in codeOffset..end) {
            if (b[i].toInt() and 0xff != 0xBB) continue       // new
            if (b[i + 3].toInt() and 0xff != 0x59) continue   // dup
            if (b[i + 4].toInt() and 0xff != 0xB7) continue   // invokespecial
            val astore = b[i + 7].toInt() and 0xff
            if (astore in 0x4B..0x4E) { // astore_0 .. astore_3
                val out = b.copyOf()
                out[i + 7] = 0xB0.toByte() // areturn
                return out
            }
        }
        return null
    }

    /** Replace whyUnsatisfied Code attribute with `aconst_null; areturn` (return null = satisfied).
     *  Works for ALL Gradle versions regardless of return type (String or Collection). */
    private fun replaceDaemonSpecCodeAttrWithNull(
        b: ByteArray, codeAttrStart: Int, codeAttrEnd: Int, codeAttrNameIdx: Int
    ): ByteArray? {
        // New code: 01 B0 = aconst_null; areturn (2 bytes)
        // Code attribute: name_idx(2) + length(4) + max_stack(2) + max_locals(2) +
        //                 code_length(4) + code(2) + exc_table_len(2) + attrs_count(2) = 20 bytes
        val attrLen = 20
        val out = ByteArray(codeAttrStart + attrLen + (b.size - codeAttrEnd))
        System.arraycopy(b, 0, out, 0, codeAttrStart)
        // name_index (2 bytes) — keep original
        out[codeAttrStart] = b[codeAttrStart]
        out[codeAttrStart + 1] = b[codeAttrStart + 1]
        // attribute_length = 14 (data after name+length)
        out[codeAttrStart + 2] = 0; out[codeAttrStart + 3] = 0
        out[codeAttrStart + 4] = 0; out[codeAttrStart + 5] = 14
        // max_stack = 1
        out[codeAttrStart + 6] = 0; out[codeAttrStart + 7] = 1
        // max_locals: PRESERVE the original value — an instance method needs
        // at least `this` + params in locals (max_locals=0 causes JVM VerifyError
        // "Arguments can't fit into locals in class file")
        val origMaxLocals1 = ((b[codeAttrStart + 8].toInt() and 0xFF) shl 8) or (b[codeAttrStart + 9].toInt() and 0xFF)
        val maxLocals1 = if (origMaxLocals1 < 1) 4 else origMaxLocals1
        out[codeAttrStart + 8] = (maxLocals1 shr 8).toByte()
        out[codeAttrStart + 9] = (maxLocals1 and 0xFF).toByte()
        // code_length = 2
        out[codeAttrStart + 10] = 0; out[codeAttrStart + 11] = 0
        out[codeAttrStart + 12] = 0; out[codeAttrStart + 13] = 2
        // code: aconst_null (01) areturn (B0)
        out[codeAttrStart + 14] = 0x01.toByte()
        out[codeAttrStart + 15] = 0xB0.toByte()
        // exception_table_length = 0
        out[codeAttrStart + 16] = 0; out[codeAttrStart + 17] = 0
        // attributes_count = 0
        out[codeAttrStart + 18] = 0; out[codeAttrStart + 19] = 0
        // copy rest
        System.arraycopy(b, codeAttrEnd, out, codeAttrStart + attrLen, b.size - codeAttrEnd)
        return out
    }

    /** Replace the Code attribute of whyUnsatisfied with `new ArrayList; dup; invokespecial; areturn` */
    private fun replaceDaemonSpecCodeAttr(
        b: ByteArray, codeAttrStart: Int, codeAttrEnd: Int,
        cpNames: Map<Int, String>, codeAttrNameIdx: Int,
        arrayListClassIdx: Int, arrayListInitIdx: Int
    ): ByteArray? {
        // New code: BB [classIdx] 59 B7 [initIdx] B0 = 8 bytes
        val newCode = byteArrayOf(
            0xBB.toByte(), (arrayListClassIdx shr 8).toByte(), (arrayListClassIdx and 0xFF).toByte(),
            0x59,
            0xB7.toByte(), (arrayListInitIdx shr 8).toByte(), (arrayListInitIdx and 0xFF).toByte(),
            0xB0.toByte() // areturn
        )
        // Code attribute: name_idx(2) + length(4) + max_stack(2) + max_locals(2) +
        //                 code_length(4) + code(8) + exc_table_len(2) + attrs_count(2) = 26 bytes
        val attrLen = 26
        val out = ByteArray(codeAttrStart + attrLen + (b.size - codeAttrEnd))
        System.arraycopy(b, 0, out, 0, codeAttrStart)
        // name_index (2 bytes) — keep original
        out[codeAttrStart] = b[codeAttrStart]
        out[codeAttrStart + 1] = b[codeAttrStart + 1]
        // attribute_length = 20 (data after name+length)
        out[codeAttrStart + 2] = 0; out[codeAttrStart + 3] = 0
        out[codeAttrStart + 4] = 0; out[codeAttrStart + 5] = 20
        // max_stack = 3
        out[codeAttrStart + 6] = 0; out[codeAttrStart + 7] = 3
        // max_locals: PRESERVE the original (instance method needs this + params)
        val origMaxLocals2 = ((b[codeAttrStart + 8].toInt() and 0xFF) shl 8) or (b[codeAttrStart + 9].toInt() and 0xFF)
        val maxLocals2 = if (origMaxLocals2 < 1) 4 else origMaxLocals2
        out[codeAttrStart + 8] = (maxLocals2 shr 8).toByte()
        out[codeAttrStart + 9] = (maxLocals2 and 0xFF).toByte()
        // code_length = 8
        out[codeAttrStart + 10] = 0; out[codeAttrStart + 11] = 0
        out[codeAttrStart + 12] = 0; out[codeAttrStart + 13] = 8
        // code
        System.arraycopy(newCode, 0, out, codeAttrStart + 14, 8)
        // exception_table_length = 0
        out[codeAttrStart + 22] = 0; out[codeAttrStart + 23] = 0
        // attributes_count = 0
        out[codeAttrStart + 24] = 0; out[codeAttrStart + 25] = 0
        // copy rest
        System.arraycopy(b, codeAttrEnd, out, codeAttrStart + attrLen, b.size - codeAttrEnd)
        return out
    }

    /**
     * Search ALL JARs in gradleLibDir for DaemonCompatibilitySpec and patch it.
     * This is the second-layer fix for when BuildActionsFactory patch fails.
     */
    private fun patchDaemonCompatibilitySpecInAllJars() {
        val libDir = gradleLibDir
        if (!libDir.exists()) return
        val jars = libDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".jar") && !it.name.endsWith(".tmp")
        } ?: return
        val specClassName = "DaemonCompatibilitySpec.class"
        for (jar in jars) {
            val marker = File(jar.parentFile, "${jar.name}.mobilestudio-daemon-spec-v1")
            // Always re-verify: markers can be stale from previous builds
            var patched = false
            var alreadyPatched = false
            var found = false
            val tmp = File(jar.parentFile, "${jar.name}.ds.tmp")
            try {
                ZipFile(jar).use { zip ->
                    ZipOutputStream(tmp.outputStream().buffered()).use { out ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val next = ZipEntry(entry.name).apply { time = entry.time }
                            out.putNextEntry(next)
                            if (!entry.isDirectory) {
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                val isSpec = entry.name.endsWith("/$specClassName")
                                val outputBytes = if (isSpec) {
                                    found = true
                                    val result = patchDaemonCompatibilitySpecClass(bytes)
                                    if (result.first) patched = true
                                    if (result.second) alreadyPatched = true
                                    result.third
                                } else bytes
                                out.write(outputBytes)
                            }
                            out.closeEntry()
                        }
                    }
                }
                if (found && patched) {
                    val backup = File(jar.parentFile, "${jar.name}.mobilestudio-original-ds")
                    if (!backup.exists() || backup.length() == 0L) {
                        jar.copyTo(backup, overwrite = true)
                    }
                    tmp.copyTo(jar, overwrite = true)
                    tmp.delete()
                    marker.writeText("patched\n")
                    log("DaemonCompatibilitySpec patched in ${jar.name}")
                } else {
                    tmp.delete()
                    if (found && alreadyPatched) {
                        marker.writeText("patched\n")
                    }
                }
            } catch (e: Exception) {
                tmp.delete()
                if (found) log("DaemonCompatibilitySpec patch failed in ${jar.name}: ${e.message?.take(100)}")
            }
        }
    }

    private class ClassFileReader(val b: ByteArray) {
        var p = 8 // skip magic + minor + major
        fun u16(): Int {
            if (p + 2 > b.size) throw ClassFormatException("u16 underflow at p=$p (class size=${b.size})")
            val v = (((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff))
            p += 2
            return v
        }
        fun u32(): Int {
            if (p + 4 > b.size) throw ClassFormatException("u32 underflow at p=$p (class size=${b.size})")
            val v = (((b[p].toInt() and 0xff) shl 24) or
                ((b[p + 1].toInt() and 0xff) shl 16) or
                ((b[p + 2].toInt() and 0xff) shl 8) or
                (b[p + 3].toInt() and 0xff))
            p += 4
            return v
        }
        fun skip(n: Int) {
            if (p + n > b.size) throw ClassFormatException("skip($n) underflow at p=$p (class size=${b.size})")
            p += n
        }
        fun requireAtLeast(n: Int, label: String) {
            if (p + n > b.size) throw ClassFormatException("$label underflow at p=$p need $n bytes (class size=${b.size})")
        }
    }

    private class ClassFormatException(msg: String) : RuntimeException(msg)

    private fun Byte.u(): Int = toInt() and 0xff

    // 설치 실행 권한 확인 및 설정

    fun applyExecPermissions(dir: File) {
        var ok = 0; var fail = 0
        fun chmod755(f: File) {
            try { Os.chmod(f.absolutePath, 0b111_101_101); ok++ }
            catch (_: Exception) { if (f.setExecutable(true, false)) ok++ else fail++ }
        }
        File(dir, "bin").listFiles()?.forEach { chmod755(it) }
        File(dir, "lib").walkTopDown()
            .filter { it.isFile && it.extension in setOf("so", "jsa", "dylib") }
            .forEach { chmod755(it) }
        File(dir, "lib/server").listFiles()?.forEach { chmod755(it) }
        File(dir, "lib/jli").listFiles()?.forEach    { chmod755(it) }
        log("chmod complete: success=$ok, failed=$fail")
    }

    // 설치 ZIP 압축 해제 확인

    private fun extractZip(zipFile: File, destDir: File, stripFirstDir: Boolean = false) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                var name = entry.name
                if (stripFirstDir) {
                    val idx = name.indexOf('/')
                    name = if (idx >= 0) name.substring(idx + 1) else name
                }
                if (name.isNotEmpty()) {
                    val dest = File(destDir, name)
                    log("Extracting: $name")
                    if (entry.isDirectory) dest.mkdirs()
                    else { dest.parentFile?.mkdirs(); FileOutputStream(dest).use { zis.copyTo(it) } }
                }
                zis.closeEntry(); entry = zis.nextEntry
            }
        }
    }

    // 설치 HTTP 다운로드 확인

    private fun downloadFile(urlStr: String, dest: File) {
        var url = urlStr
        repeat(5) { attempt ->
            val existingBytes = if (dest.exists()) dest.length() else 0L
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000; readTimeout = 120_000
                if (existingBytes > 0) {
                    setRequestProperty("Range", "bytes=$existingBytes-")
                }
                connect()
            }
            val code = conn.responseCode
            if (code in 300..399) {
                url = conn.getHeaderField("Location")
                    ?: throw RuntimeException("Redirect failed (attempt=$attempt)")
                conn.disconnect(); return@repeat
            }
            val isResume = code == 206
            val total = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                conn.contentLengthLong
            } else {
                conn.contentLength.toLong()
            }
            val totalSize = if (isResume) existingBytes + total else total
            var downloaded = if (isResume) existingBytes else 0L
            val mode = if (isResume) "rw" else "w"
            conn.inputStream.use { input ->
                java.io.FileOutputStream(dest, isResume).use { output ->
                    val buf = ByteArray(32 * 1024)
                    var n = input.read(buf)
                    while (n != -1) {
                        if (DownloadCenter.isCancelled()) {
                            throw RuntimeException("Download cancelled by user")
                        }
                        output.write(buf, 0, n); downloaded += n
                        if (totalSize > 0) {
                            val pct = (downloaded * 100 / totalSize).toInt()
                            Handler(Looper.getMainLooper()).post { onProgress(pct) }
                            DownloadCenter.progress(downloaded, totalSize)
                        }
                        n = input.read(buf)
                    }
                }
            }
            conn.disconnect()
            if (totalSize > 0 && downloaded < totalSize) {
                throw java.io.EOFException("Download incomplete: $downloaded / $totalSize bytes received")
            }
            if (dest.length() == 0L) throw RuntimeException("Download failed (0 bytes): $urlStr")
            log("Download complete: ${dest.name} (${dest.length() / 1024 / 1024}MB)")
            return
        }
        if (dest.length() == 0L) throw RuntimeException("Download failed after retries: $urlStr")
    }

    private fun withQuietLogs(quiet: Boolean, block: () -> Unit) {
        if (!quiet) {
            block()
            return
        }
        val previous = quietLogs.get()
        quietLogs.set(true)
        try {
            block()
        } finally {
            quietLogs.set(previous)
        }
    }

    private fun log(msg: String) {
        if (quietLogs.get() == true) return
        Handler(Looper.getMainLooper()).post { onLog(msg) }
    }

    companion object {
        private const val TAG = "JreGradleInstaller"
        private const val GRADLE_PATCH_RELEASE_BASE = "https://github.com/MobileStudio-AndroidIDE/MobileStudio_AndroidIDE/releases/latest/download"
        private const val targetClass = "org/gradle/launcher/cli/BuildActionsFactory.class"
    }
}
