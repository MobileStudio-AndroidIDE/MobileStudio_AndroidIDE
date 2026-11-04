package com.aide.mobilestudio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class GradleRunner(private val context: Context, private val installer: JreGradleInstaller) {

    private fun gradleJvmArgs(budget: BuildMemoryPolicy.Budget): List<String> {
        val base = BuildToolsConfig.buildGradleJvmArgs(context).toMutableList()
        // Override heap/metaspace from BuildToolsConfig (not budget profile)
        base.removeAll { it.startsWith("-Xmx") || it.startsWith("-XX:MaxMetaspaceSize") }
        base.add("-Xmx${BuildToolsConfig.gradleHeapMb(context)}m")
        base.add("-XX:MaxMetaspaceSize=${BuildToolsConfig.gradleMetaspaceMb(context)}m")
        base.add("-XX:-TieredCompilation")
        return base
    }

    fun gradleJvmArgsProperty(memory: BuildMemoryPolicy.Budget): String = buildList {
        addAll(gradleJvmArgs(memory))
        add("-Djava.home=${installer.daemonJavaHome.absolutePath}")
    }.joinToString(" ")

    data class BuildResult(val success: Boolean, val durationMs: Long, val errorLines: List<String> = emptyList())

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var currentNativeHandle: Long = 0L
    @Volatile var currentProcess: Process? = null
    // Kept alive for the lifetime of the app; nativeFree deliberately kills it.
    @Volatile private var nativeDaemonHandle: Long = 0L
    @Volatile var cancelRequested: Boolean = false
    private val runGeneration = AtomicLong(0)
    var lastStackSnapshot: List<String> = emptyList()
        private set

    private val verboseBuildLogs: Boolean
        get() = BuildToolsConfig.gradleVerbose(context) ||
            context.getSharedPreferences("ide", Context.MODE_PRIVATE).getBoolean("verbose_build_logs", false)

    private val gradleDaemonPreference: Boolean
        get() = BuildToolsConfig.daemon(context) ||
            context.getSharedPreferences("ide", Context.MODE_PRIVATE)
                .getBoolean("gradle_daemon", false)

    private val flagPattern = Regex("""^([A-Za-z0-9_.-]+)\s*(?:=|:|\s)\s*(true|false)\b""", RegexOption.IGNORE_CASE)

    private val mobileWorkerCount: Int
        get() = BuildToolsConfig.maxWorkers(context)

    fun isGradleDaemonRequested(projectDir: File, gradleUserHome: File): Boolean {
        // 1) Explicit user/project settings win, including an explicit false
        readGradleDaemonFlag(File(projectDir, "gradle.properties"))?.let { return it }
        readGradleDaemonFlag(File(gradleUserHome, "gradle.properties"))?.let { return it }
        // 2) MobileStudio Build Tools setting (default: enabled) / 3) default: enabled
        return gradleDaemonPreference
    }

    private fun readGradleDaemonFlag(file: File): Boolean? {
        if (!file.isFile) return null
        val keys = setOf("org.gradle.daemon", "gradleDaemon", "mobileStudio.gradleDaemon", "mobilestudio.gradleDaemon")
        // Properties semantics: the LAST occurrence of the key wins.
        file.readLines().asReversed().forEach { line ->
            val trimmed = line.substringBefore('#').trim()
            val match = flagPattern.find(trimmed) ?: return@forEach
            val key = match.groupValues[1].trim()
            if (key !in keys) return@forEach
            return match.groupValues[2].equals("true", ignoreCase = true)
        }
        return null
    }

    fun run(
        projectDir: File,
        tasks: List<String>,
        onLine: (String, LineLevel) -> Unit,
        onDone: (BuildResult) -> Unit,
        onActionLine: ((String, LineLevel, () -> Unit) -> Unit)? = null,
        onRetry: ((retry: () -> Unit) -> Unit)? = null
    ) {
        cancel()
        val generation = runGeneration.incrementAndGet()
        executor.submit {
            if (generation != runGeneration.get()) return@submit
            cancelRequested = false
            val start = System.currentTimeMillis()
            val errors = mutableListOf<String>()
            try {
                // ---- [Provision] build-time tool provisioning ----
                // First launch only asks for a language (BuildBootActivity) —
                // no tool downloads happen at boot anymore. Everything the
                // build needs is ensured here, right before Gradle runs.
                // Every step is marker/ELF-guarded, so repeated builds never
                // re-download what is already installed. JDK/Gradle failures
                // abort the build; native-tool failures only warn.
                val provisionError = BuildProvisioner.provision(
                    context = context,
                    projectDir = projectDir,
                    installer = installer,
                    emit = { line, level -> emit(onLine, line, level) }
                )
                if (provisionError != null) {
                    emit(onLine, provisionError, LineLevel.ERROR)
                    emit(onDone, BuildResult(false, System.currentTimeMillis() - start, errors))
                    return@submit
                }

                check(installer.isJdkInstalled) { "JDK is not installed" }
                check(installer.isGradleInstalled) { "Gradle is not installed" }

                val gradleUserHome = BuildToolchain.gradleUserHome(context)
                val tmpDir = File(context.cacheDir, "gradle_tmp").apply { mkdirs() }
                val androidSdkDir = resolveAndroidSdkDir()
                    ?: error("Android SDK not found. Install via Build Tools > SDK Manager.")
                val launcherJar = installer.gradleLauncherJar ?: error("Gradle launcher jar not found")
                // ONE canonical JDK spelling everywhere: argv[0], JAVA_HOME,
                // -Djava.home and -Dorg.gradle.java.home must be byte-identical
                // strings, otherwise Gradle's daemon-compatibility check treats
                // them as different JVMs and forks (or fails) unexpectedly.
                val javaHome = runCatching { installer.canonicalJavaHome.canonicalPath }
                    .getOrDefault(installer.effectiveJavaHome.absolutePath)
                val javaExec = File(javaHome, "bin/java").takeIf { it.exists() }
                    ?: installer.effectiveJavaBin
                val memoryBudget = BuildMemoryPolicy.resolve(context)

                // Priority: 1) explicit user/project settings (incl. explicit false)
                //           2) MobileStudio Build Tools setting
                //           3) default (daemon enabled)
                // Never force daemon OFF here.
                val gradleDaemonEnabled = isGradleDaemonRequested(projectDir, gradleUserHome)

                emit(onLine, "[Gradle] Daemon: ${if (gradleDaemonEnabled) "ENABLED" else "DISABLED"}", LineLevel.INFO)
                emit(onLine, "[Gradle] Gradle version: ${installer.gradleHome.name.removePrefix("gradle-")}", LineLevel.INFO)
                if (gradleDaemonEnabled) {
                    emit(onLine, "[Gradle] Starting/reusing Gradle Daemon...", LineLevel.INFO)
                }

                installer.ensureGradleLauncherReadyForAndroid(allowDaemon = gradleDaemonEnabled)
                installer.removeTermuxLeftovers(installer.effectiveJavaHome)

                // ---- CMake/Ninja wrapper repair (externalNativeBuild) ----
                // The CMake runtime, ninja and NDK themselves are provisioned
                // lazily by BuildProvisioner above — and ONLY for projects that
                // actually contain native code, so plain Java/Kotlin builds
                // never wait on (or download) native tools. Here we only repair
                // the SDK wrapper scripts AGP execs (local, cheap, no network).
                if (BuildProvisioner.needsNativeTooling(projectDir)) {
                    // AGP execs <sdk>/cmake/<ver>/bin/{cmake,ninja} directly,
                    // which Android denies for app-data ELFs. Replace those
                    // entries (files or symlinks) with linker64 wrapper scripts.
                    runCatching {
                        NativeToolWrappers.ensureSdkWrappers(
                            sdkDir = androidSdkDir,
                            toolchainRoot = File(context.filesDir, "toolchain"),
                            filesDir = context.filesDir
                        ).forEach { line -> emit(onLine, line, LineLevel.INFO) }
                    }.onFailure {
                        emit(onLine, "> [CMake] WARN: wrapper repair failed: ${it.message}", LineLevel.WARNING)
                    }
                }

                writeLocalProperties(projectDir, androidSdkDir)
                writeDaemonJvmArgsToGradleProperties(gradleUserHome, projectDir, tmpDir, memoryBudget, gradleDaemonEnabled)
                ensureBuiltInKotlinCompat(projectDir)
                ensureJdkExecPermissions(installer.effectiveJavaHome)
                AndroidHostedLlvmInstaller.repairSymlinksIfNeeded(context)

                // DT_RPATH -> DT_RUNPATH: silences bionic's "unused DT entry:
                // DT_RPATH" warning that the JDK lib set otherwise prints for
                // every spawned process (dozens of lines per tool start).
                runCatching {
                    val retagged = ElfRpathRetagger.retagJdk(installer.effectiveJavaHome)
                    if (retagged > 0) {
                        emit(onLine, "> [ELF] DT_RPATH -> DT_RUNPATH: $retagged JDK lib(s) retagged", LineLevel.INFO)
                    }
                }

                // Clean stale daemon registry entries (previous crashed daemons cause
                // compatibility mismatches / "starting/reusing" confusion)
                runCatching {
                    val daemonDir = File(gradleUserHome, "daemon")
                    daemonDir.listFiles()?.forEach { versionDir ->
                        versionDir.listFiles()
                            ?.filter { it.name.startsWith("registry.bin") || it.name.startsWith("registry.bin.lock") }
                            ?.forEach { it.delete() }
                    }
                }
                // Kill stale Gradle daemons: a daemon started by an OLDER APK keeps the
                // old nativeLibraryDir in its env. Its javac spawns then load the OUTDATED
                // libjava_launcher.so (no tool-mode dispatch -> "Unrecognized option:
                // --system=none" in JdkImageTransform). Killing it forces a fresh daemon
                // with the current APK's libraries (the registry cleanup above makes the
                // client start a new one anyway).
                runCatching { killStaleGradleDaemons(context, onLine) }

                // Patch AGP jars so JdkImageTransform/ExecAction proxies jlink/jmod/javac/cmake/ninja
                runCatching {
                    AndroidGradleCachePatcher.patchAapt2DaemonStartup(gradleUserHome)
                }.onSuccess { result ->
                    emit(
                        onLine,
                        "> AGP tool proxy patch: candidates=${result.candidates}" +
                            " patched=${result.patched} already=${result.alreadyPatched}",
                        LineLevel.INFO
                    )
                    result.failed.forEach { emit(onLine, "> AGP patch failed: $it", LineLevel.WARNING) }
                }.onFailure {
                    emit(onLine, "> AGP tool proxy patch error: ${it.message}", LineLevel.WARNING)
                }

                // native-platform must stay byte-pristine: the old ASM rewrite of
                // DefaultProcessLauncher.start() corrupted its StackMapTable and the
                // daemon then died during verification with "StackMapTable error:
                // bad offset".  Only verify/repair it here; never patch bytecode.
                runCatching { installer.ensureNativePlatformPristine() }
                    .onFailure {
                        emit(onLine, "> [Daemon] native-platform integrity check failed: ${it.message}", LineLevel.WARNING)
                    }

                val javaArgs = buildJavaArgs(
                    javaBin = javaExec,
                    launcherJar = launcherJar,
                    tasks = tasks,
                    projectDir = projectDir,
                    tmpDir = tmpDir,
                    gradleUserHome = gradleUserHome,
                    gradleDaemonEnabled = gradleDaemonEnabled,
                    memoryBudget = memoryBudget
                ).toMutableList()

                val envMap = buildEnvMap(
                    javaHome, gradleUserHome, tmpDir, androidSdkDir, gradleDaemonEnabled, memoryBudget
                ).toMutableMap().apply {
                    listOf("BOOTCLASSPATH", "CLASSPATH").forEach { remove(it) }
                }
                val envp = envMap.entries.map { (k, v) -> "$k=$v" }

                // Diagnostics: print actual NativeExec environment (daemon debugging)
                emit(onLine, "> [Env] JAVA_HOME=${envMap["JAVA_HOME"]}", LineLevel.INFO)
                emit(onLine, "> [Env] JAVA_TOOL_OPTIONS=${envMap["JAVA_TOOL_OPTIONS"] ?: "(unset)"}", LineLevel.INFO)
                emit(onLine, "> [Env] GRADLE_OPTS=${envMap["GRADLE_OPTS"]}", LineLevel.INFO)
                emit(onLine, "> [Env] Daemon JVM args: ${gradleJvmArgsProperty(memoryBudget)}", LineLevel.INFO)
                emit(onLine, "> [Env] exec_redirect=${envMap["MOBILESTUDIO_EXEC_REDIRECT"]}", LineLevel.INFO)
                // Full launch record: the Gradle client forks its daemon from
                // exactly these strings, so one device log line sequence pins
                // down which leg of the chain (script -> launcher -> JVM) broke.
                emit(onLine, "> [Exec] executable=${javaExec.absolutePath}", LineLevel.INFO)
                emit(onLine, "> [Exec] argv[0]=${javaArgs.firstOrNull() ?: "(empty)"} argc=${javaArgs.size}", LineLevel.INFO)
                emit(onLine, "> [Exec] args=${javaArgs.joinToString(" ")}", LineLevel.INFO)
                emit(onLine, "> [Exec] cwd=${projectDir.absolutePath}", LineLevel.INFO)
                emit(onLine, "> [Exec] PATH=${envMap["PATH"]}", LineLevel.INFO)
                emit(onLine, "> [Exec] nativeLibraryDir=${context.applicationInfo.nativeLibraryDir}", LineLevel.INFO)
                emit(onLine, "> [Exec] libjava_launcher=${envMap["MOBILESTUDIO_JAVA_LAUNCHER"]}", LineLevel.INFO)
                emit(onLine, "> [Exec] libexec_redirect=${envMap["MOBILESTUDIO_EXEC_REDIRECT"]}", LineLevel.INFO)
                // Wrapper diagnostics: marker + MOBILESTUDIO_JDK_TOOL export presence
                // (a "NO" here means java spawns dispatch as javac -> "error: invalid
                // flag: -XX:+UseSerialGC") + anchor ELF availability.
                emitWrapperDiagnostics(File(javaHome), onLine)

                // NOTE: DaemonMain requires exactly 1 CLI arg + a Kryo-serialized
                // DaemonServerConfiguration on stdin — a manual NativeExec daemon start
                // cannot supply that.  Gradle forks the daemon itself through
                // ProcessBuilder; libexec_redirect.so (LD_PRELOAD) performs the real
                // exec, so app-data JDK binaries stay runnable under noexec mounts.

                if (cancelRequested || generation != runGeneration.get()) {
                    emit(onLine, "BUILD CANCELLED", LineLevel.WARNING)
                    emit(onDone, BuildResult(false, System.currentTimeMillis() - start, errors))
                    return@submit
                }

                emit(onLine, "> Starting Gradle (NativeExec)...", LineLevel.INFO)
                val handle = NativeExec.start(
                    javaBin = javaExec.absolutePath,
                    codeCacheDir = context.codeCacheDir.absolutePath,
                    nativeLibDir = context.applicationInfo.nativeLibraryDir,
                    args = javaArgs,
                    envp = envp,
                    cwd = projectDir.absolutePath
                )
                currentNativeHandle = handle

                if (handle == 0L) {
                    emit(onLine, "> direct java launch failed before process start", LineLevel.ERROR)
                    val strategy = runCatching { NativeExec.checkExecStrategy(javaExec.absolutePath) }
                        .getOrElse { "checkExecStrategy failed: ${it.message}" }
                    emit(onLine, "> exec strategy: $strategy", LineLevel.WARNING)
                    val nativeErr = runCatching { NativeExec.getLastError() }
                        .getOrElse { "getLastError failed: ${it.message}" }
                    emit(onLine, "> native exec error: $nativeErr", LineLevel.WARNING)
                    emit(onDone, BuildResult(false, System.currentTimeMillis() - start, errors))
                    return@submit
                }

                emit(onLine, "> Gradle process started, waiting for output...", LineLevel.INFO)

                val acc = StringBuilder()
                val outputFilter = BuildOutputFilter(onLine, errors)
                var lastOutputMs = System.currentTimeMillis()
                while (true) {
                    if (cancelRequested || currentNativeHandle != handle) break
                    val chunkOrNull = NativeExec.readChunk(handle, 500) ?: break
                    if (cancelRequested || currentNativeHandle != handle) break
                    if (chunkOrNull.isEmpty()) continue
                    lastOutputMs = System.currentTimeMillis()

                    val normalized = chunkOrNull.replace('\r', '\n')
                    acc.append(normalized)

                    while (true) {
                        val idx = acc.indexOf("\n")
                        if (idx < 0) break
                        val line = acc.substring(0, idx)
                        acc.delete(0, idx + 1)
                        if (line.isEmpty()) continue
                        outputFilter.accept(line)
                    }
                    if (acc.length > 8192) {
                        val line = acc.toString()
                        acc.setLength(0)
                        outputFilter.accept(line)
                    }
                }
                outputFilter.flush()

                val stackSnapshot = outputFilter.consumeStackSnapshot()
                lastStackSnapshot = stackSnapshot
                if (stackSnapshot.isNotEmpty() && onActionLine != null) {
                    val count = stackSnapshot.size
                    onActionLine("> Gradle stacktrace collapsed: $count lines hidden", LineLevel.DEBUG) {
                        stackSnapshot.forEach { onLine(it, LineLevel.DEBUG) }
                    }
                }

                val exit = NativeExec.waitFor(handle)
                runCatching { NativeExec.free(handle) }
                if (currentNativeHandle == handle) currentNativeHandle = 0L
                val ms = System.currentTimeMillis() - start

                if (exit >= 128) {
                    val signal = exit - 128
                    emit(onLine, "> native process ended by signal $signal (exit=$exit)", LineLevel.WARNING)
                }

                /*
                 * Integrity gate: re-check DefaultProcessLauncher after the
                 * build.  The SHA-256 printed here must match the baseline
                 * printed before the build — any difference means something
                 * rewrote Gradle's bytecode again.
                 */
                runCatching { installer.ensureNativePlatformPristine() }
                    .onFailure {
                        emit(
                            onLine,
                            "> [Gradle] [Daemon] post-build native-platform check failed: ${it.message}",
                            LineLevel.WARNING
                        )
                    }

                if (cancelRequested) {
                    emit(onLine, "BUILD CANCELLED", LineLevel.WARNING)
                    emit(onDone, BuildResult(false, ms, errors))
                    return@submit
                }

                val success = exit == 0
                if (!outputFilter.sawTerminalBuildLine) {
                    if (!success) {
                        val nativeErr = runCatching { NativeExec.getLastError() }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() && it != "none" }
                        if (nativeErr != null) {
                            emit(onLine, "> native exec detail: $nativeErr", LineLevel.WARNING)
                        }
                    }
                    emit(
                        onLine,
                        if (success) "BUILD SUCCESSFUL" else "BUILD FAILED (exit=$exit)",
                        if (success) LineLevel.SUCCESS else LineLevel.ERROR
                    )
                }
                emit(onDone, BuildResult(success, ms, errors))

            } catch (e: Exception) {
                Log.e(TAG, "GradleRunner error", e)
                emit(onLine, "Build error: ${e.message}", LineLevel.ERROR)
                emit(onDone, BuildResult(false, System.currentTimeMillis() - start, errors))
            }
        }
    }

    fun cancel() {
        runGeneration.incrementAndGet()
        cancelRequested = true
        val nh = currentNativeHandle
        if (nh != 0L) {
            currentNativeHandle = 0L
            runCatching { NativeExec.kill(nh) }
        }
        currentProcess?.destroy()
        currentProcess = null
        // Note: daemons are intentionally NOT killed here so they can be
        // reused by the next build. Use stopGradleDaemons() for an explicit stop.
    }

    /** Explicit Gradle Daemon stop feature (equivalent of `gradlew --stop`). */
    fun stopGradleDaemons() {
        killGradleProcesses()
    }

    /** Gradle Daemon status: "ENABLED"/"DISABLED" from Build Tools setting. */
    fun gradleDaemonStatus(): String =
        if (BuildToolsConfig.daemon(context) ||
            context.getSharedPreferences("ide", Context.MODE_PRIVATE)
                .getBoolean("gradle_daemon", false)
        ) "ENABLED" else "DISABLED"

    /**
     * PID of a running Gradle Daemon process, or "unknown" when it cannot be
     * determined. Never fabricates a value.
     */
    fun gradleDaemonPid(): String {
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", "ps -A -o PID,ARGS 2>/dev/null || ps -A 2>/dev/null")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor(5, TimeUnit.SECONDS)
            output.lineSequence()
                .filter { it.contains("GradleDaemon") }
                .map { it.trim().split(Regex("\\s+")).firstOrNull() ?: "unknown" }
                .firstOrNull() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun killGradleProcesses() {
        val patterns = listOf("GradleDaemon", "gradle", "kotlin-compile-daemon", "GradleWorkerMain", "org.gradle")
        for (pattern in patterns) {
            try {
                val pb = ProcessBuilder("pkill", "-9", "-f", pattern)
                pb.redirectErrorStream(true)
                val p = pb.start()
                p.waitFor(5, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }
    }

    /**
     * On-device arm64 aapt2 shipped by ToolchainManager.ensureAssetOverridesInstalled.
     * AGP's bundled maven aapt2 is a desktop-host binary and cannot run here, so
     * every build wires the override in through the Gradle project property AGP
     * already understands (android.aapt2FromMavenOverride).
     */
    private val aapt2OverrideFile: File
        get() = File(context.filesDir, "toolchain/overrides/aapt2")

    private val aapt2DaemonProxySo: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libaapt2_daemon_proxy.so")

    private fun aapt2OverrideArg(): String? =
        aapt2OverrideFile.takeIf { it.canExecute() }
            ?.absolutePath
            ?.let { "-Dorg.gradle.project.android.aapt2FromMavenOverride=$it" }

    private fun buildJavaArgs(
        javaBin: File,
        launcherJar: File,
        tasks: List<String>,
        projectDir: File,
        tmpDir: File,
        gradleUserHome: File,
        gradleDaemonEnabled: Boolean,
        memoryBudget: BuildMemoryPolicy.Budget
    ): List<String> = buildList {
        add(javaBin.absolutePath)
        addAll(gradleJvmArgs(memoryBudget))
        add("-Djava.io.tmpdir=${tmpDir.absolutePath}")
        add("-Duser.home=${context.filesDir.absolutePath}")
        add("-Duser.dir=${projectDir.absolutePath}")
        add("-DGRADLE_USER_HOME=${gradleUserHome.absolutePath}")
        add("-Dorg.gradle.daemon=$gradleDaemonEnabled")
        add("-Dorg.gradle.workers.max=${BuildToolsConfig.maxWorkers(context)}")
        add("-Dorg.gradle.caching=${BuildToolsConfig.buildCache(context)}")
        add("-Dorg.gradle.parallel=${BuildToolsConfig.parallel(context)}")
        add("-Dorg.gradle.vfs.watch=${BuildToolsConfig.vfsWatch(context)}")
        add("-Dorg.gradle.project.android.aapt2ThreadPoolSize=${BuildToolsConfig.maxWorkers(context)}")
        aapt2OverrideArg()?.let { add(it) }
        add("-Dkotlin.daemon.jvm.options=${BuildToolsConfig.buildKotlinDaemonArgs(context)}")
        add("-Djava.home=${installer.daemonJavaHome.absolutePath}")
        // Android compatibility: ensure daemon process can exec from /data
        add("-Djdk.lang.Process.allowAmbiguousCommands=true")
        // Initialize JDK platform encoding on Android (prevents InternalError: platform encoding not initialized)
        add("-Dfile.encoding=UTF-8")
        add("-Dsun.jnu.encoding=UTF-8")
        add("-jar"); add(launcherJar.absolutePath)
        addAll(injectedArgs(System.getProperty("os.arch")))
        addAll(tasks)
        addAll(BuildToolsConfig.buildGradleCliArgs(context))
        add("--project-dir"); add(projectDir.absolutePath)
    }

    private fun ensureNativeGradleDaemon(
        javaBin: File,
        gradleUserHome: File,
        tmpDir: File,
        projectDir: File,
        envp: List<String>,
        memoryBudget: BuildMemoryPolicy.Budget,
        onLine: (String, LineLevel) -> Unit
    ) {
        if (nativeDaemonHandle != 0L) return
        val version = installer.gradleHome.name.removePrefix("gradle-")
        // Daemon jar name differs per Gradle version: 9.7 = gradle-daemon-server,
        // 9.0 = gradle-daemon-main, 8.x = gradle-launcher
        val daemonJar = listOf(
            "lib/gradle-daemon-server-$version.jar",
            "lib/gradle-daemon-main-$version.jar",
            "lib/gradle-launcher-$version.jar"
        ).map { File(installer.gradleHome, it) }.firstOrNull { it.isFile }
        val agentJar = File(installer.gradleHome, "lib/agents/gradle-instrumentation-agent-$version.jar")
        if (daemonJar == null || !agentJar.isFile) {
            emit(onLine, "[Gradle] Native daemon bootstrap unavailable: runtime jars not found", LineLevel.WARNING)
            return
        }
        val defaultOpens = listOf(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.prefs/java.util.prefs=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
            "--add-opens=java.base/java.nio.charset=ALL-UNNAMED",
            "--add-opens=java.base/java.net=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
            "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
            "--add-opens=java.xml/javax.xml.namespace=ALL-UNNAMED",
            "--add-opens=java.base/java.time=ALL-UNNAMED"
        )
        val args = buildList {
            add(javaBin.absolutePath)
            addAll(defaultOpens)
            addAll(buildDaemonJvmArgsProperty(gradleUserHome, projectDir, tmpDir, memoryBudget, true).split(" "))
            add("-cp"); add(daemonJar.absolutePath)
            add("-javaagent:${agentJar.absolutePath}")
            add("org.gradle.launcher.daemon.bootstrap.GradleDaemon")
            add(version)
        }
        val handle = NativeExec.start(
            javaBin = javaBin.absolutePath,
            codeCacheDir = context.codeCacheDir.absolutePath,
            nativeLibDir = context.applicationInfo.nativeLibraryDir,
            args = args,
            envp = envp,
            cwd = File(gradleUserHome, "daemon/$version").apply { mkdirs() }.absolutePath
        )
        if (handle == 0L) {
            emit(onLine, "[Gradle] Native daemon bootstrap failed: ${NativeExec.getLastError()}", LineLevel.WARNING)
            return
        }
        nativeDaemonHandle = handle
        emit(onLine, "[Gradle] Starting daemon through NativeExec...", LineLevel.INFO)
        Thread {
            while (nativeDaemonHandle == handle) {
                val chunk = NativeExec.readChunk(handle, 1_000) ?: break
                if (chunk.contains("ERROR", ignoreCase = true)) emit(onLine, "[Gradle daemon] ${chunk.take(500)}", LineLevel.WARNING)
            }
            if (nativeDaemonHandle == handle) nativeDaemonHandle = 0L
        }.apply { isDaemon = true; start() }
        // Give the daemon enough time to publish its address before launching
        // the client; otherwise Gradle attempts a second, blocked ProcessBuilder fork.
        repeat(20) {
            if (File(gradleUserHome, "daemon/$version/registry.bin").isFile) return
            Thread.sleep(100)
        }
    }

    private fun buildEnvMap(
        javaHome: String,
        gradleUserHome: File,
        tmpDir: File,
        androidSdkDir: File,
        gradleDaemonEnabled: Boolean,
        memoryBudget: BuildMemoryPolicy.Budget
    ): Map<String, String> {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val javaLauncher = File(nativeLibDir, "libjava_launcher.so").absolutePath
        val execRedirect = File(nativeLibDir, "libexec_redirect.so")
        val aapt2Launcher = File(nativeLibDir, "libaapt2_launcher.so")
        val aapt2Override = aapt2OverrideFile
        val aapt2Proxy = aapt2DaemonProxySo
        val jdkToolProxy = File(nativeLibDir, "libjdk_tool_proxy.so")
        val nativeToolShell = runCatching { prepareNativeToolShimScript() }
            .getOrDefault(File(context.codeCacheDir, "native_tool_shim.sh"))
        val realJavaHome = javaHome
        val cmakeDir = resolveAndroidCmakeDir(androidSdkDir)
        // Android arm64 ports (user-ported CMake 3.22.1 + arm64 ninja) — the
        // native tool shim redirects AGP's SDK cmake/ninja here and rewrites
        // -DCMAKE_MAKE_PROGRAM args. Discovered by scanning the toolchain tree
        // for REAL ELFs (never hardcoded paths; wrapper scripts are excluded by
        // the ELF check). Empty when not installed: the shim then derives
        // fallbacks from the tool path itself and keeps the original path when
        // the port is missing (no blind substitution).
        val toolchainRoot = File(context.filesDir, "toolchain")
        // Prefer the user-installed StudioShellTools arm64 tool (<files>/tools/<tool>/
        // bin/<tool>) before the toolchain ports; this is the app's known-good ninja.
        val portedCmakeFile: File? =
            NativeToolWrappers.resolvePortedTool(context.filesDir, toolchainRoot, "cmake")?.first
        val portedNinjaFile: File? =
            NativeToolWrappers.resolvePortedTool(context.filesDir, toolchainRoot, "ninja")?.first
        val ndkDirs = resolveAllAndroidNdkDirs()
        val llvmSearchRoots = buildList {
            ndkDirs.forEach { add(it.absolutePath) }
            File(context.filesDir, "toolchain/llvm").let { add(it.absolutePath) }
            File(context.filesDir, "toolchain/android-llvm").let { add(it.absolutePath) }
        }.distinct().joinToString(":")
        val ldPaths = buildList {
            add("$javaHome/lib/server")
            add("$javaHome/lib")
            add("$javaHome/lib/jli")
            // Ported arm64 cmake/ninja need libc++_shared.so from the toolchain
            // LLVM tree (their DT_NEEDED entry) when executed outside the shim.
            add(File(toolchainRoot, "llvm/lib").absolutePath)
            // Ported arm64 cmake libs resolve before the SDK cmake's (the
            // native tool shim redirects native builds to the ported binary).
            portedCmakeFile?.parentFile?.parentFile?.let {
                add(File(it, "lib").absolutePath)
                add(File(it, "bin").absolutePath)
            }
            // Ported ninja (<files>/tools/ninja/bin/ninja or toolchain port):
            // expose its lib/ so its bundled libc++_shared.so resolves.
            portedNinjaFile?.parentFile?.parentFile?.let {
                add(File(it, "lib").absolutePath)
                add(File(it, "bin").absolutePath)
            }
            cmakeDir?.let {
                add(File(it, "bin").absolutePath)
                add(File(it, "lib").absolutePath)
            }
            add(nativeLibDir)
            val extra = BuildToolsConfig.ldLibraryPathExtra(context)
            if (extra.isNotBlank()) addAll(extra.split(":").filter { it.isNotBlank() })
        }.filter { it.isNotBlank() }.distinct().joinToString(":")
        val gradleOpts = buildList {
            add("-Dorg.gradle.daemon=$gradleDaemonEnabled")
            add("-Dorg.gradle.caching=${BuildToolsConfig.buildCache(context)}")
            add("-Dorg.gradle.parallel=${BuildToolsConfig.parallel(context)}")
            add("-Dorg.gradle.vfs.watch=${BuildToolsConfig.vfsWatch(context)}")
            // The DAEMON COMMAND's executable: must be the shim tree's
            // bin/java (a symlink to the native ELF launcher). A script here
            // breaks the JVM's forkAndExec fail-pipe protocol (garbage errno).
            add("-Dorg.gradle.java.home=${installer.daemonJavaHome.absolutePath}")
            add("-Dorg.gradle.jvmargs=${gradleJvmArgsProperty(memoryBudget)}")
        }.joinToString(" ")
        val runtimeToolsBin = RuntimeToolsInstaller.binDir(context).takeIf { it.isDirectory }?.absolutePath
        // LD_PRELOAD: exec_redirect hooks execve/posix_spawn so that ANY process spawned by
        // the Gradle client or daemon (daemon java, javac, aapt2, jlink) can execute from the
        // noexec app-data mount via memfd_create. Without this, the daemon fork fails with
        // "error=13, Permission denied" (rubygrapefruit ProcessBuilder exec).
        // JDK version for the launcher (--version output), read from the release file
        val jdkFullVersion = runCatching {
            File(javaHome, "release").readLines()
                .firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?.substringAfter("JAVA_VERSION=")
                ?.trim('"', ' ')
        }.getOrNull() ?: "21.0"
        val jdkDotVersion = jdkFullVersion.split(".").take(2).joinToString(".")
        return mutableMapOf(
            "LD_LIBRARY_PATH" to ldPaths,
            // The Gradle client starts the daemon with ProcessBuilder. Unlike
            // the initial NativeExec launch, that child only sees inherited
            // environment variables; preload the hook so its execve call is
            // redirected away from Android's noexec app-data mount.
            "LD_PRELOAD" to execRedirect.absolutePath,
            "JAVA_HOME" to javaHome,
            "MOBILESTUDIO_JDK_FULLVERSION" to jdkFullVersion,
            "MOBILESTUDIO_JDK_DOTVERSION" to jdkDotVersion,
            "MOBILESTUDIO_APK_PATH" to context.applicationInfo.sourceDir,
            "MOBILESTUDIO_JAVA_LAUNCHER" to javaLauncher,
            "MOBILESTUDIO_EXEC_REDIRECT" to execRedirect.absolutePath,
            "MOBILESTUDIO_AAPT2_LAUNCHER" to aapt2Launcher.absolutePath,
            // Native aapt2 daemon proxy: AAPT2_SHELL_BOOTSTRAP execs it through
            // /system/bin/linker64 instead of falling back to dalvikvm/app_process
            // (absent/broken on Android 16). Empty when the lib is missing so the
            // bootstrap can take its fallback branches.
            "MOBILESTUDIO_AAPT2_DAEMON_PROXY" to (if (aapt2Proxy.isFile) aapt2Proxy.absolutePath else ""),
            // Arm64 aapt2 override (also exported for tools that read the path
            // directly); the -D aapt2FromMavenOverride arg is added separately.
            "MOBILESTUDIO_AAPT2_OVERRIDE" to aapt2Override.absolutePath,
            "MOBILESTUDIO_AAPT2_EXEC" to (if (aapt2Override.canExecute()) aapt2Override.absolutePath else ""),
            "MOBILESTUDIO_JDK_TOOL_PROXY" to jdkToolProxy.absolutePath,
            // Gate for the AGP ExecAction bytecode patch (AndroidGradleCachePatcher).
            // When set to the proxy ELF, /jlink|/jmod|/javac are rewritten to
            // "/system/bin/linker64 <proxy> <tool> ..." (doProxy -> JLI in-process).
            // Empty/unset falls back to direct exec of the app-data tool, which
            // Android denies (EACCES) and fails with "A problem occurred starting
            // process 'command '.../bin/jlink''".
            "MOBILESTUDIO_JDK_CMAKE_TOOL_PROXY_V30" to
                (if (jdkToolProxy.isFile) jdkToolProxy.absolutePath else ""),
            "MOBILESTUDIO_NATIVE_LIB_DIR" to nativeLibDir,
            "MOBILESTUDIO_NATIVE_TOOL_SHELL" to nativeToolShell.absolutePath,
            "MOBILESTUDIO_REAL_CMAKE_DIR" to (cmakeDir?.let { File(it, "bin/cmake").absolutePath } ?: ""),
            // Android arm64 ports for the native tool shim (SDK cmake/ninja ->
            // ported arm64 builds; see prepareNativeToolShimScript).
            "MOBILESTUDIO_PORTED_CMAKE" to (portedCmakeFile?.absolutePath ?: ""),
            "MOBILESTUDIO_PORTED_NINJA" to (portedNinjaFile?.absolutePath ?: ""),
            "MOBILESTUDIO_LLVM_SEARCH_ROOTS" to llvmSearchRoots,
            "GRADLE_USER_HOME" to gradleUserHome.absolutePath,
            "GRADLE_OPTS" to gradleOpts,
            "TMPDIR" to tmpDir.absolutePath,
            "HOME" to context.filesDir.absolutePath,
            "ANDROID_HOME" to androidSdkDir.absolutePath,
            "ANDROID_SDK_ROOT" to androidSdkDir.absolutePath,
            // AGP resolves the debug-keystore/prefs location from these FIRST.
            // Without them the daemon falls back to user.home-derived /data/.android,
            // which is not writable -> AccessDeniedException applying AGP.
            "ANDROID_USER_HOME" to File(context.filesDir, ".android").apply { mkdirs() }.absolutePath,
            "ANDROID_PREFS_ROOT" to context.filesDir.absolutePath,
            "LANG" to "en_US.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "sun.jnu.encoding" to "UTF-8",
            "PATH" to listOfNotNull("/system/bin", "/system/xbin", runtimeToolsBin, nativeLibDir).joinToString(":"),
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ORG_GRADLE_PROJECT_org.gradle.daemon" to gradleDaemonEnabled.toString()
        )
    }

    private fun resolveAndroidSdkDir(): File? = BuildToolchain.sdkDir(context)

    /**
     * Write org.gradle.jvmargs to gradle.properties to match the current process JVM args.
     * This prevents Gradle from forking a single-use daemon due to JVM settings mismatch.
     * The "To honour the JVM settings for this build a single-use Daemon process will be forked"
     * message appears when org.gradle.jvmargs differs from the current process args.
     *
     * We must write ALL -D and -X flags that buildJavaArgs passes to the JVM, not just
     * heap/metaspace. Gradle compares org.gradle.jvmargs against
     * ManagementFactory.getRuntimeMXBean().getInputArguments() and forks a daemon on any mismatch.
     */
    private fun writeDaemonJvmArgsToGradleProperties(
        gradleUserHome: File,
        projectDir: File,
        tmpDir: File,
        memoryBudget: BuildMemoryPolicy.Budget,
        gradleDaemonEnabled: Boolean
    ) {
        try {
            val propsFile = File(gradleUserHome, "gradle.properties")
            val existing = if (propsFile.exists()) propsFile.readText() else ""

            val jvmArgs = buildDaemonJvmArgsProperty(gradleUserHome, projectDir, tmpDir, memoryBudget, gradleDaemonEnabled)
            val props = existing.lines().toMutableList()
            val marker = "# mobilestudio-daemon-jvmargs-sync"

            // Remove old sync entries
            props.removeAll {
                it.startsWith("org.gradle.jvmargs") ||
                it.startsWith("org.gradle.daemon") ||
                it.startsWith("systemProp.org.gradle.logging.stacktrace") ||
                it.trim() == marker
            }

            // Add synchronized jvmargs
            props.add(marker)
            props.add("org.gradle.jvmargs=$jvmArgs")
            props.add("org.gradle.daemon=$gradleDaemonEnabled")
            // NOTE: never write systemProp.org.gradle.logging.stacktrace here.
            // Gradle 9.x rejects "true" ("must be one of internal, all, or full")
            // and fails daemon startup on it. The removal filter above strips any
            // entry an older build wrote, so the property simply stays absent.

            propsFile.writeText(props.joinToString("\n") + "\n")
        } catch (_: Exception) {}
    }

    /**
     * Keeps `android.builtInKotlin` consistent with what the project's build
     * files actually apply (AGP 9 registers a built-in `kotlin` extension):
     *
     *  - Templates no longer apply `org.jetbrains.kotlin.android`, so any
     *    leftover opt-out written by an older generator is removed — with the
     *    opt-out still present, `.kt` sources would not be compiled at all.
     *  - An imported/legacy project that *does* apply the plugin keeps (or
     *    gains) `android.builtInKotlin=false`, because applying both fails
     *    with "Cannot add extension with name 'kotlin'".
     */
    fun ensureBuiltInKotlinCompat(projectDir: File) {
        try {
            val usesExplicitKotlinPlugin = projectDir.walkTopDown()
                .maxDepth(2)
                .any { f ->
                    f.isFile && (f.name.endsWith(".gradle") || f.name.endsWith(".gradle.kts")) &&
                        runCatching { f.readText() }.getOrDefault("")
                            .contains("org.jetbrains.kotlin.android")
                }

            val propsFile = File(projectDir, "gradle.properties")
            val existing = if (propsFile.exists()) propsFile.readText() else ""
            val kept = existing.lines().filter { !it.trim().startsWith("android.builtInKotlin") }

            if (usesExplicitKotlinPlugin) {
                if (existing.lines().none { it.trim().startsWith("android.builtInKotlin") }) {
                    val base = kept.joinToString("\n").trimEnd()
                    propsFile.writeText(
                        (if (base.isEmpty()) "" else base + "\n") + "android.builtInKotlin=false\n"
                    )
                }
            } else if (existing.lines().size != kept.size) {
                val base = kept.joinToString("\n").trimEnd()
                propsFile.writeText(if (base.isEmpty()) "" else base + "\n")
            }
        } catch (_: Exception) {}
    }

    /**
     * Kills Gradle daemon processes whose environment still points at a previous
     * APK's nativeLibraryDir. Those daemons load the outdated libjava_launcher.so
     * (without javac tool-mode dispatch) and break JdkImageTransform with
     * "Unrecognized option: --system=none" even though the on-disk wrapper is fine.
     */
    private fun killStaleGradleDaemons(
        context: android.content.Context,
        onLine: ((String, LineLevel) -> Unit)?
    ) {
        val currentNlib = context.applicationInfo.nativeLibraryDir
        val procDir = File("/proc")
        procDir.listFiles()?.forEach { p ->
            val pid = p.name.toIntOrNull() ?: return@forEach
            // Gradle daemon?
            val cmdline = runCatching { File(p, "cmdline").readText() }.getOrNull() ?: return@forEach
            if (!cmdline.contains("GradleDaemon")) return@forEach
            // Check its environ for a stale native lib dir
            val environ = runCatching { File(p, "environ").readText() }.getOrNull() ?: return@forEach
            val m = Regex("MOBILESTUDIO_NATIVE_LIB_DIR=([^\\x00]+)").find(environ) ?: return@forEach
            val daemonNlib = m.groupValues[1].trim()
            if (daemonNlib.isNotBlank() && daemonNlib != currentNlib) {
                runCatching { android.os.Process.killProcess(pid) }
                onLine?.invoke("[Gradle] Killed stale daemon pid=$pid (old NLIB: $daemonNlib)", LineLevel.WARNING)
            }
        }
    }

    /**
     * Build the full org.gradle.jvmargs value matching what buildJavaArgs passes to the JVM.
     * Every -X and -D flag must be included; Gradle forks a daemon on any mismatch.
     */
    private fun buildDaemonJvmArgsProperty(
        gradleUserHome: File,
        projectDir: File,
        tmpDir: File,
        memoryBudget: BuildMemoryPolicy.Budget,
        gradleDaemonEnabled: Boolean
    ): String = buildList {
        addAll(gradleJvmArgs(memoryBudget))
        add("-Djava.io.tmpdir=${tmpDir.absolutePath}")
        add("-Duser.home=${context.filesDir.absolutePath}")
        add("-Duser.dir=${projectDir.absolutePath}")
        add("-DGRADLE_USER_HOME=${gradleUserHome.absolutePath}")
        add("-Dorg.gradle.daemon=$gradleDaemonEnabled")
        add("-Dorg.gradle.workers.max=${BuildToolsConfig.maxWorkers(context)}")
        add("-Dorg.gradle.caching=${BuildToolsConfig.buildCache(context)}")
        add("-Dorg.gradle.parallel=${BuildToolsConfig.parallel(context)}")
        add("-Dorg.gradle.vfs.watch=${BuildToolsConfig.vfsWatch(context)}")
        add("-Dorg.gradle.project.android.aapt2ThreadPoolSize=${BuildToolsConfig.maxWorkers(context)}")
        aapt2OverrideArg()?.let { add(it) }
        add("-Dkotlin.daemon.jvm.options=${BuildToolsConfig.buildKotlinDaemonArgs(context)}")
        add("-Djava.home=${installer.daemonJavaHome.absolutePath}")
        add("-Djdk.lang.Process.allowAmbiguousCommands=true")
        add("-Dfile.encoding=UTF-8")
        add("-Dsun.jnu.encoding=UTF-8")
    }.joinToString(" ")

    /**
     * Ensure all JDK binaries have execute permissions and linker wrappers are valid.
     * This is a safety net for when the Gradle daemon fork happens despite our patch.
     */
    private fun ensureJdkExecPermissions(jdkHome: File) {
        try {
            installer.applyExecPermissions(jdkHome)
            JdkDownloader.regenerateLinkerWrappers(jdkHome, { })
        } catch (e: Exception) {
            // Never silent: a throw here leaves a v11 script paired with a v12
            // marker (marker>=VERSION && !stale then skips regeneration forever).
            Log.w(TAG, "ensureJdkExecPermissions failed: $e")
        }
    }

    /**
     * Build-start wrapper diagnostic for device verification: format marker, whether
     * bin/java carries the MOBILESTUDIO_JDK_TOOL export (its absence dispatches java
     * spawns as javac -> "error: invalid flag: -XX:+UseSerialGC"), and whether the
     * anchor ELFs (java.real / javac.real) are present.
     */
    private fun emitWrapperDiagnostics(jdkHome: File, onLine: (String, LineLevel) -> Unit) {
        runCatching {
            val marker = File(jdkHome, ".wrapper_format_version")
                .let { if (it.exists()) it.readText().trim() else "(missing)" }
            val javaBin = File(jdkHome, "bin/java")
            val script = if (javaBin.isFile && javaBin.length() < 10000) {
                runCatching { javaBin.readText() }.getOrDefault("")
            } else ""
            val isWrapper = script.startsWith("#!/system/bin/sh")
            val hasExport = script.contains("MOBILESTUDIO_JDK_TOOL")
            val isCurrent = script.contains("wrapper v${JdkDownloader.WRAPPER_FORMAT_VERSION}")
            val javaState = when {
                !javaBin.isFile -> "MISSING"
                !isWrapper -> "raw-ELF"
                else -> "script v${if (isCurrent) JdkDownloader.WRAPPER_FORMAT_VERSION else "old"}" +
                    " export=${if (hasExport) "yes" else "NO"}"
            }
            fun elfState(name: String): String {
                // The anchor ELFs are the Android linker (java.real ~12KB) —
                // judge by ELF magic, never by size. The old >1MB check marked
                // every genuine anchor "missing".
                val f = File(jdkHome, "bin/$name")
                if (!f.isFile || f.length() < 64) return "missing"
                val magic = runCatching { f.inputStream().use { input ->
                    val m = ByteArray(4)
                    input.read(m) == 4 && m[0] == 0x7F.toByte() && m[1] == 'E'.code.toByte() &&
                        m[2] == 'L'.code.toByte() && m[3] == 'F'.code.toByte()
                } }.getOrDefault(false)
                return if (magic) "OK(${f.length()}B)" else "missing"
            }
            emit(
                onLine,
                "> [Wrapper] marker=$marker java=$javaState " +
                    "java.real=${elfState("java.real")} javac.real=${elfState("javac.real")}",
                LineLevel.INFO
            )
        }
    }

    private fun resolveAndroidCmakeDir(sdkDir: File?): File? =
        BuildToolchain.cmakeDir(context, sdkDir)

    private fun resolveAllAndroidNdkDirs(): List<File> = BuildToolchain.ndkDirs(context)

    private fun prepareNativeToolShimScript(): File {
        val ns = File(context.codeCacheDir, "native_tool_shim.sh")
        // The asset carries the redirect logic (AGP's SDK cmake/ninja -> the
        // Android arm64 ports, -DCMAKE_MAKE_PROGRAM rewrite, linker64 exec).
        // Content-versioned copy so an app update replaces an older shim on
        // device; CRLF normalized (the asset may be checked out on Windows).
        val assetText = runCatching {
            context.assets.open("native_tool_shim.sh").bufferedReader().use { it.readText() }
        }.getOrNull()?.replace("\r\n", "\n")
        if (assetText != null &&
            (
                runCatching { if (ns.isFile) ns.readText() else null }.getOrNull() != assetText ||
                    !ns.canExecute()
                )
        ) {
            ns.parentFile?.mkdirs()
            ns.writeText(assetText)
            ns.setExecutable(true, false)
        }
        if (!ns.exists()) {
            // Asset missing (older APK layout) -> plain pass-through shim.
            ns.parentFile?.mkdirs()
            ns.writeText("#!/system/bin/sh\nexec \"\$@\"\n")
            ns.setExecutable(true, false)
        }
        return ns
    }

    private fun writeLocalProperties(projectDir: File, androidSdkDir: File) {
        val file = File(projectDir, "local.properties")
        val ndkDir = resolveAllAndroidNdkDirs().firstOrNull()
        val cmakeDir = resolveAndroidCmakeDir(androidSdkDir)
        val text = buildString {
            append("sdk.dir=${androidSdkDir.absolutePath}\n")
            if (cmakeDir != null) append("cmake.dir=${cmakeDir.absolutePath}\n")
            if (ndkDir != null) append("ndk.dir=${ndkDir.absolutePath}\n")
        }
        if (!file.exists() || file.readText() != text) file.writeText(text)
    }

    private inner class BuildOutputFilter(
        private val onLine: (String, LineLevel) -> Unit,
        private val errors: MutableList<String>
    ) {
        var sawTerminalBuildLine = false
            private set
        private val collapsedStack = mutableListOf<String>()

        fun accept(line: String) {
            if (isNoiseLine(line)) return
            // Log on the reader thread: emit() posts to the main looper, and a
            // logcat write per line on the UI thread janks heavy build output.
            Log.i("MobileStudioBuild", line)
            if ("BUILD SUCCESSFUL" in line || "BUILD FAILED" in line) sawTerminalBuildLine = true
            if (line.contains("error:", ignoreCase = true) || line.startsWith("e: ")) errors.add(line)
            emit(onLine, line, classifyLine(line))
            emitDaemonLifecycle(line)
        }

        /**
         * Lines that are pure toolchain noise: bionic's "unused DT entry: DT_RPATH"
         * warning (emitted by every process that preloads the JDK's libs) and the
         * OpenJDK signal() deprecation warning. Both were also silenced at the
         * source (DT_RPATH retag, LD_PRELOAD hygiene); this is the last line of
         * defense so they never surface as build "warnings".
         */
        private fun isNoiseLine(line: String): Boolean {
            if ("unused DT entry: DT_RPATH" in line) return true
            if ("unused DT entry: DT_RUNPATH" in line) return true
            if (line.contains("VM warning") && line.contains("signal()")) return true
            return false
        }

        private fun emitDaemonLifecycle(line: String) {
            val msg = when {
                line.contains("Starting a Gradle Daemon", ignoreCase = true) ->
                    "[Gradle] Daemon is starting (new daemon JVM)"
                line.contains("daemon will be stopped", ignoreCase = true) &&
                    line.contains("single-use", ignoreCase = true) ->
                    "[Gradle] WARNING: single-use daemon forked (JVM args mismatch)"
                line.contains("reusing", ignoreCase = true) && line.contains("daemon", ignoreCase = true) ->
                    "[Gradle] Daemon is being reused"
                line.contains("Stopping Daemon", ignoreCase = true) ||
                    line.contains("Daemon stopped", ignoreCase = true) ->
                    "[Gradle] Daemon stopped"
                line.contains("kotlin", ignoreCase = true) &&
                    line.contains("daemon", ignoreCase = true) &&
                    (line.contains("connect", ignoreCase = true) || line.contains("start", ignoreCase = true) ||
                        line.contains("reuse", ignoreCase = true)) ->
                    "[Kotlin] Kotlin compiler daemon in use"
                else -> null
            }
            if (msg != null) emit(onLine, msg, LineLevel.INFO)
        }

        fun flush() {}

        fun consumeStackSnapshot(): List<String> {
            val r = collapsedStack.toList()
            collapsedStack.clear()
            return r
        }

        private fun classifyLine(line: String): LineLevel = when {
            line.startsWith("e: ") || line.contains("error:", ignoreCase = true) -> LineLevel.ERROR
            line.contains("warning:", ignoreCase = true) -> LineLevel.WARNING
            line.contains("BUILD SUCCESSFUL") -> LineLevel.SUCCESS
            else -> LineLevel.INFO
        }
    }

    private fun emit(cb: (String, LineLevel) -> Unit, line: String, level: LineLevel) {
        mainHandler.post { cb(line, level) }
    }

    private fun <T : Any> emit(cb: (T) -> Unit, value: T) {
        mainHandler.post { cb(value) }
    }

    companion object {
        private const val TAG = "GradleRunner"

        /**
         * Android Studio's "Run on device" injects android.injected.build.abi
         * so only the connected device's ABI is configured/built. On-device
         * the hosted LLVM ships aarch64 only, so without this AGP also tries
         * armeabi-v7a/x86 and configure fails ("No available targets are
         * compatible with triple thumbv7...").
         */
        internal fun abiFilterFor(arch: String?): String? = when (arch?.lowercase()) {
            "aarch64", "arm64" -> "arm64-v8a"
            "x86_64", "amd64" -> "x86_64"
            "x86", "i386", "i686" -> "x86"
            else -> null
        }

        /**
         * Gradle properties injected into every build.
         *
         * - `android.injected.build.abi`: see [abiFilterFor].
         * - `android.injected.testOnly=false`: Android Studio's "Run" injects
         *   `android.injected.testOnly=true`, which makes AGP stamp
         *   `android:testOnly="true"` onto the merged manifest. The system
         *   installer then rejects the package (only `adb install -t` works).
         *   MobileStudio builds APKs for normal installation, so force it off.
         */
        internal fun injectedArgs(arch: String?): List<String> = buildList {
            abiFilterFor(arch)?.let { add("-Pandroid.injected.build.abi=$it") }
            add("-Pandroid.injected.testOnly=false")
        }
    }
}
