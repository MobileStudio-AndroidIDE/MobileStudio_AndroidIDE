package com.aide.mobilestudio.analysis

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.*

/**
 * A persistent JVM daemon that keeps the Kotlin compiler loaded in memory.
 * Compiles a small Java wrapper at runtime, then runs it as a long-lived process.
 * Subsequent requests reuse the same JVM ??no per-save JVM startup overhead.
 *
 * Protocol (one request per line on stdin; every response frame ends with DELIM):
 *   PING                      -> PONG
 *   <abs path>                -> analysis diagnostics for a single file
 *   COMPILE\t<arg>\t<arg>...  -> full compiler invocation; frame ends with
 *                                KDC_EXIT=<code>, then DELIM
 *
 * Auto-shuts down after [AnalysisConfig.daemonIdleTimeoutMs] of inactivity to save RAM.
 */
class KotlinDaemon(
    private val context: Context,
    private val config: AnalysisConfig,
    private val tag: String = "kd"
) {
    data class CompileResult(val exitCode: Int, val lines: List<String>)

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var lastUsedMs = 0L
    @Volatile private var started = false
    @Volatile private var starting = false
    @Volatile private var activeRequests = 0
    private var kotlincDir: File? = null
    private var javaBin: File? = null
    private var explicitJar: File? = null

    /** Serializes write+read of a full request/response frame across all callers. */
    private val requestMutex = Mutex()

    /** android.jar + resolved library jars so single-file analysis resolves externals. */
    private fun buildExtraClasspath(): String {
        val extras = mutableListOf<File>()
        KotlinCompiler.findAndroidJar(context)?.let { extras += it }
        extras += KotlinCompiler.dependencyJars(context)
        return extras.joinToString(File.pathSeparator) { it.absolutePath }
    }

    /** Start the daemon JVM on a background thread. No-op if already running or starting. */
    fun start(kotlincDir: File, javaBin: File, explicitCompilerJar: File? = null) {
        this.kotlincDir = kotlincDir
        this.javaBin = javaBin
        this.explicitJar = explicitCompilerJar
        synchronized(this) {
            if (started || starting) return
            starting = true
        }

        val TAG = TAG
        Thread {
            try {
                val compilerJar = explicitCompilerJar ?: findCompilerJar(kotlincDir)
                if (compilerJar == null) {
                    Log.w(TAG, "kotlin-compiler.jar not found, daemon disabled")
                    starting = false
                    return@Thread
                }
                val classDir = compileDaemonWrapper(compilerJar, javaBin)
                if (classDir == null) {
                    Log.w(TAG, "daemon wrapper compilation failed, daemon disabled")
                    starting = false
                    return@Thread
                }

                val daemonEnv = mutableMapOf<String, String>()
                val execRedirect = File(context.applicationInfo.nativeLibraryDir, "libexec_redirect.so")
                if (execRedirect.exists()) {
                    daemonEnv["LD_PRELOAD"] = execRedirect.absolutePath
                }

                // Analysis mode (single-file) resolves against this classpath;
                // COMPILE requests build their own -cp and ignore it.
                val extraCp = buildExtraClasspath()
                if (extraCp.isNotEmpty()) {
                    daemonEnv["KD_EXTRA_CP"] = extraCp
                }

                // The embeddable compiler ships without its runtime deps
                // (stdlib/reflect/coroutines) - they live next to the compiler jar.
                val runtimeCp = (listOf(classDir, compilerJar) + KotlinCompiler.compilerRuntimeJars(compilerJar))
                    .joinToString(File.pathSeparator) { it.absolutePath }
                val pb = com.aide.mobilestudio.ExecHelper.createProcess(
                    context,
                    listOf(
                        javaBin.absolutePath,
                        "-cp", runtimeCp,
                        "-Xmx${config.daemonHeapMb}m",
                        "-XX:+UseSerialGC",
                        "-XX:+PerfDisableSharedMem",
                        "KotlinDaemonMain"
                    ),
                    cwd = null,
                    env = daemonEnv
                ).redirectErrorStream(true)
                process = pb.start()

                writer = process!!.outputStream.bufferedWriter()
                reader = process!!.inputStream.bufferedReader()

                writer!!.write("PING")
                writer!!.newLine()
                writer!!.flush()

                // Skip JVM startup noise (VM deprecation warnings land on the
                // merged stderr/stdout stream) until PONG or EOF.
                val readThread = Thread {
                    try {
                        var line: String?
                        var sawPong = false
                        while (reader!!.readLine().also { line = it } != null) {
                            if (line == "PONG") {
                                sawPong = true
                                break
                            }
                            Log.w(TAG, "daemon handshake: skipping line: ${line!!.take(200)}")
                        }
                        if (sawPong) {
                            started = true
                            lastUsedMs = System.currentTimeMillis()
                            Log.i(TAG, "daemon ready (heap=${config.daemonHeapMb}MB, jar=${compilerJar.name})")
                        } else {
                            Log.w(TAG, "daemon handshake failed: no PONG before EOF")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "daemon handshake error", e)
                    } finally {
                        starting = false
                    }
                }.apply { isDaemon = true; name = "KotlinDaemonHandshake" }
                readThread.start()
                readThread.join(5000L)
                if (readThread.isAlive) {
                    readThread.interrupt()
                    Log.w(TAG, "daemon handshake timed out after 5s, stopping")
                    stop()
                } else if (!started) {
                    stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "daemon start failed", e)
                stop()
            }
        }.apply { isDaemon = true; name = "KotlinDaemonStarter" }.start()
    }

    /**
     * Analyze a single Kotlin file via the persistent daemon.
     * Returns null if the daemon is not available (caller should fall back to per-process).
     * Automatically restarts the daemon if it has crashed.
     */
    suspend fun analyze(file: File, source: String, timeoutMs: Long): String? {
        if (!ensureAlive()) return null

        lastUsedMs = System.currentTimeMillis()

        val inputFile = File(context.cacheDir, "kdaemon_in/${tag}_${file.name}")
        inputFile.parentFile!!.mkdirs()
        inputFile.writeText(source)
        inputFile.deleteOnExit()

        return try {
            requestMutex.withLock {
                activeRequests++
                try {
                    writer!!.write(inputFile.absolutePath)
                    writer!!.newLine()
                    writer!!.flush()

                    withTimeout(timeoutMs) {
                        runInterruptible(Dispatchers.IO) {
                            buildString {
                                while (true) {
                                    val line = reader!!.readLine() ?: break
                                    if (line == DELIM) break
                                    appendLine(line)
                                }
                            }
                        }
                    }
                } finally {
                    activeRequests--
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "daemon analyze failed", e)
            stop()
            null
        } finally {
            inputFile.delete()
        }
    }

    /**
     * Run a full compiler invocation through the persistent daemon (auto-compile).
     * Returns null when the daemon cannot serve the request; caller falls back
     * to a one-shot fork. [args] must not contain tabs/newlines (protocol framing).
     */
    suspend fun compile(args: List<String>, timeoutMs: Long): CompileResult? {
        if (!ensureAlive()) return null

        lastUsedMs = System.currentTimeMillis()

        return try {
            requestMutex.withLock {
                activeRequests++
                try {
                    writer!!.write("COMPILE\t" + args.joinToString("\t"))
                    writer!!.newLine()
                    writer!!.flush()

                    withTimeout(timeoutMs) {
                        runInterruptible(Dispatchers.IO) {
                            val out = mutableListOf<String>()
                            var exitCode = -1
                            while (true) {
                                val line = reader!!.readLine() ?: break
                                if (line == DELIM) break
                                if (line.startsWith("KDC_EXIT=")) {
                                    exitCode = line.removePrefix("KDC_EXIT=").trim().toIntOrNull() ?: -1
                                } else {
                                    out += line
                                }
                            }
                            CompileResult(exitCode, out)
                        }
                    }
                } finally {
                    activeRequests--
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "daemon compile failed", e)
            stop()
            null
        }
    }

    /** True when daemon has been idle longer than the configured timeout and serves no request. */
    fun isIdle(): Boolean =
        started && activeRequests == 0 &&
            (System.currentTimeMillis() - lastUsedMs) > config.daemonIdleTimeoutMs

    /**
     * Block until the handshake completes or [timeoutMs] elapses.
     * First launch pays wrapper-javac + JVM boot (~1-2s); callers use this
     * instead of racing straight into a one-shot fork.
     */
    fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (started) return true
            if (!starting) return false
            Thread.sleep(100)
        }
        return started
    }

    fun isAlive(): Boolean = process?.isAlive == true

    /** Stop the daemon and release resources. Safe to call multiple times. */
    fun stop() {
        started = false
        starting = false
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { process?.destroy() } catch (_: Exception) {}
        process = null; writer = null; reader = null
        Log.i(TAG, "daemon stopped")
    }

    // ---- internals ----

    private fun ensureAlive(): Boolean {
        val p = process
        if (p != null && !p.isAlive) {
            // Crashed: clear the stale flag so start() may run again.
            started = false
        }
        if (started && process?.isAlive == true) return true
        val kd = kotlincDir ?: return false
        val jb = javaBin ?: return false
        start(kd, jb, explicitJar)
        // Startup is async (wrapper javac + handshake); wait for it so the
        // first request uses the daemon too - the one-shot fallback is not
        // usable on-device (SELinux exec / writable-dex aborts).
        awaitReady(COMPILE_DAEMON_READY_MS)
        return started && process?.isAlive == true
    }

    private fun findCompilerJar(kotlincDir: File): File? {
        val libDir = File(kotlincDir, "lib")
        if (!libDir.isDirectory) return null
        return libDir.listFiles()?.firstOrNull {
            it.name.startsWith("kotlin-compiler") && it.name.endsWith(".jar")
        }
    }

    /** Write, compile, and return the directory containing KotlinDaemonMain.class. */
    private fun compileDaemonWrapper(compilerJar: File, javaBin: File): File? {
        val srcFile = File(context.codeCacheDir, "$tag/KotlinDaemonMain.java")
        srcFile.parentFile?.mkdirs()
        srcFile.writeText(DAEMON_JAVA_SOURCE)

        val classDir = File(context.codeCacheDir, "${tag}_class").apply { mkdirs() }
        val javac = File(javaBin.parentFile, "javac").takeIf { it.isFile }
            ?: return null

        // Ensure execution permission for javac and the entire bin directory
        runCatching {
            val binDir = javaBin.parentFile?.absolutePath
            if (binDir != null) {
                Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", binDir)).waitFor()
            }
        }

        val proc = com.aide.mobilestudio.ExecHelper.createProcess(
            context,
            listOf(
                javac.absolutePath,
                "-cp", compilerJar.absolutePath,
                "-d", classDir.absolutePath,
                srcFile.absolutePath
            ),
            cwd = classDir,
            env = mapOf("JAVA_HOME" to (javaBin.parentFile?.parentFile?.absolutePath ?: ""))
        ).redirectErrorStream(true).start()
        val exited = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        val exitCode = if (exited) proc.exitValue() else { proc.destroyForcibly(); -1 }

        if (exitCode != 0 || !File(classDir, "KotlinDaemonMain.class").exists()) {
            val err = if (exited) proc.inputStream.bufferedReader().readText() else "timed out"
            Log.w(TAG, "javac failed (exit=$exitCode): ${err.take(500)}")
            return null
        }
        return classDir
    }

    companion object {
        private const val TAG = "KotlinDaemon"
        private const val DELIM = "___KDAEMON_DIAG_END___"
        private const val COMPILE_DAEMON_READY_MS = 15_000L
        private val DAEMON_JAVA_SOURCE = """
import java.io.*;
import java.lang.reflect.*;
import org.jetbrains.kotlin.cli.common.ExitCode;
public class KotlinDaemonMain {
    private static final String D = "$DELIM";
    public static void main(String[] a) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
        Class<?> cc = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
        Method exec = cc.getMethod("exec", PrintStream.class, String[].class);
        String runtimeCp = System.getProperty("java.class.path");
        String l;
        while ((l = r.readLine()) != null) {
            if ("PING".equals(l)) { System.out.println("PONG"); System.out.flush(); continue; }
            if (l.isEmpty()) continue;
            boolean compileCmd = l.startsWith("COMPILE\t");
            String[] args;
            String od = null;
            if (compileCmd) {
                args = l.substring("COMPILE\t".length()).split("\t", -1);
            } else {
                od = System.getProperty("java.io.tmpdir") + "/kd_" + System.nanoTime();
                new File(od).mkdirs();
                String extra = System.getenv("KD_EXTRA_CP");
                String acp = (extra == null || extra.isEmpty()) ? runtimeCp
                        : runtimeCp + File.pathSeparator + extra;
                args = new String[]{l, "-d", od, "-no-reflect", "-nowarn", "-no-stdlib", "-cp", acp};
            }
            // Fresh compiler instance per request - no state leaks between compiles.
            Object c = cc.getDeclaredConstructor().newInstance();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(bos, true, "UTF-8");
            PrintStream o = System.out, e = System.err;
            System.setOut(ps); System.setErr(ps);
            int code = 0;
            try {
                ExitCode ec = (ExitCode) exec.invoke(c, ps, args);
                if (ec != null) code = ec.getCode();
            } catch (Throwable x) {
                x.printStackTrace(ps);
                code = -1;
            }
            System.out.flush(); System.err.flush();
            System.setOut(o); System.setErr(e);
            System.out.print(new String(bos.toByteArray(), "UTF-8"));
            if (compileCmd) System.out.println("KDC_EXIT=" + code);
            System.out.println(D);
            System.out.flush();
            if (od != null) delete(new File(od));
        }
    }
    static void delete(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) delete(c);
        f.delete();
    }
}
""".trimIndent()
    }
}
