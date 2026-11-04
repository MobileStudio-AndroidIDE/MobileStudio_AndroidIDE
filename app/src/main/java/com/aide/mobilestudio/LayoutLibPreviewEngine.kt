package com.aide.mobilestudio

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

class LayoutLibPreviewEngine(private val context: Context) {

    data class Result(
        val status: Status,
        val imageFile: File? = null,
        val message: String,
        val detail: String = ""
    )

    enum class Status {
        RENDERED,
        UNAVAILABLE,
        FAILED
    }

    private data class Runtime(
        val jars: List<File>,
        val platformDir: File,
        val frameworkResDir: File
    )

    private data class ProcessResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean = false
    )

    fun render(layoutFile: File, widthPx: Int, heightPx: Int): Result {
        validateLayoutXml(layoutFile)?.let { return it }
        val importer = LayoutLibImporter(context)
        val bundledResult = importer.ensureBundledInstalled()
        if (!bundledResult.success) {
            val dlResult = importer.downloadJdk17Runtime()
            if (!dlResult.success) {
                Log.w("LayoutLibPreview", "LayoutLib bundled install failed: ${bundledResult.message}; download also failed: ${dlResult.message}")
            }
        }
        val runtime = findRuntime() ?: return Result(
            status = Status.UNAVAILABLE,
            message = "LayoutLib runtime not installed. Bundled layoutlib.jar, layoutlib-api.jar and kxml2 were not found.",
            detail = buildString {
                appendLine("Needed: layoutlib bridge + layoutlib API + kxml2 + Android SDK framework resources.")
                appendLine("Try: Tools > Build Tools > LayoutLib > Install JDK17 Runtime")
                appendLine("Or: Install Android SDK first (Tools > Build Tools > SDK)")
            }
        )

        val installer = JreGradleInstaller(context, onLog = {}, onProgress = {})
        if (!installer.isJdkInstalled) {
            return Result(
                Status.UNAVAILABLE,
                message = "JDK is not installed. LayoutLib preview needs JDK 21.",
                detail = layoutLibDiagnostics(javaHome = null, javaBin = null, runtime = runtime)
            )
        }
        runCatching { installer.ensureRuntimeLibsOrThrow(quiet = true) }

        val javaHome = dataDataAlias(resolveLayoutLibJavaHome(installer))
        val javaBin = dataDataAlias(File(javaHome, "bin/java"))
        if (!hasUsableJli(javaHome)) {
            return Result(
                Status.UNAVAILABLE,
                message = "Active JDK is incomplete; using lightweight preview.",
                detail = buildString {
                    appendLine("libjli.so was not found under ${javaHome.absolutePath}. In Build Tools > JDK, activate the nested JDK folder that contains bin/java and lib/libjli.so or lib/jli/libjli.so.")
                    append(layoutLibDiagnostics(javaHome, javaBin, runtime))
                }
            )
        }
        if (javaFeature(javaHome) < 21) {
            return Result(
                Status.UNAVAILABLE,
                message = "LayoutLib requires JDK 21; using lightweight preview.",
                detail = layoutLibDiagnostics(javaHome, javaBin, runtime)
            )
        }
        val compilerModule = File(javaHome, "jmods/jdk.compiler.jmod")
        if (!compilerModule.exists()) {
            return Result(
                Status.UNAVAILABLE,
                message = "jdk.compiler is missing in the active JDK.",
                detail = buildString {
                    appendLine("Current JAVA_HOME: ${javaHome.absolutePath}. Activate a full JDK, not a JRE/shim folder.")
                    append(layoutLibDiagnostics(javaHome, javaBin, runtime))
                }
            )
        }
        layoutLibCompatibilityProblem(runtime, javaHome)?.let { return it }

        val workDir = File(context.cacheDir, "layoutlib_runner").apply { mkdirs() }
        val outputFile = File(
            File(context.cacheDir, "layoutlib_previews").apply { mkdirs() },
            cacheName(layoutFile, widthPx, heightPx)
        )
        if (outputFile.exists() && outputFile.length() > 0) {
            return Result(Status.RENDERED, outputFile, "LayoutLib cached render ready.")
        }

        val sourceFile = File(workDir, "MobileStudioLayoutLibRenderer.java")
        val classesDir = File(workDir, "classes").apply { mkdirs() }
        val source = rendererSource()
        if (!sourceFile.exists() || sourceFile.readText() != source) {
            sourceFile.writeText(source)
            classesDir.deleteRecursively()
            classesDir.mkdirs()
        }

        val platformJar = File(runtime.platformDir, "android.jar")
        val classFile = File(classesDir, "MobileStudioLayoutLibRenderer.class")
        val compileClasspath = (runtime.jars + platformJar)
            .filter { it.exists() }
            .joinToString(File.pathSeparator) { it.absolutePath }
        if (!classFile.exists()) {
            val javacFile = File(javaHome, "bin/javac")
            val realJavac = if (javacFile.exists()) javacFile.canonicalFile else javacFile
            
            val ldPaths = listOf(
                File(javaHome, "lib/server"),
                File(javaHome, "lib"),
                File(javaHome, "lib/jli"),
                File(context.applicationInfo.nativeLibraryDir)
            ).filter { it.exists() }.joinToString(":") { it.absolutePath }
            
            val env = mapOf(
                "JAVA_HOME" to javaHome.absolutePath,
                "LD_LIBRARY_PATH" to ldPaths,
                "PATH" to "/system/bin:/system/xbin"
            )
            
            val pb = ExecHelper.createProcess(
                context = context,
                args = listOf(
                    realJavac.absolutePath,
                    "-cp", compileClasspath,
                    "-Xdiags:verbose",
                    "-encoding", "UTF-8",
                    "-d", classesDir.absolutePath,
                    sourceFile.absolutePath
                ),
                cwd = workDir,
                env = env
            ).redirectErrorStream(true)
            
            val proc = pb.start()
            val output = StringBuilder()
            val reader = Thread {
                runCatching { proc.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
            }.apply { isDaemon = true; start() }
            
            val finished = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                proc.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)
            } else {
                proc.waitFor(); true
            }
            reader.join(1000)
            
            val compileResult = ProcessResult(
                exitCode = if (finished) proc.exitValue() else -1,
                output = output.toString(),
                timedOut = !finished
            )
            
            if (compileResult.exitCode != 0) {
                return Result(
                    status = Status.FAILED,
                    message = "LayoutLib renderer compile failed.",
                    detail = buildString {
                        appendLine("command: ${realJavac.absolutePath} -cp <layoutlib jars> ...")
                        appendLine("exitCode: ${compileResult.exitCode}")
                        appendLine("output:")
                        append(compileResult.output.takeLast(4000))
                    }
                )
            }
        }

        val runtimeClasspath = (listOf(classesDir) + runtime.jars + platformJar)
            .filter { it.exists() }
            .joinToString(File.pathSeparator) { it.absolutePath }
        val render = runNative(
            executable = javaBin,
            args = listOf(
                javaBin.absolutePath,
                "-Xmx${BuildToolsConfig.layoutPreviewHeapMb(context)}m",
                "-Djava.awt.headless=true",
                "-cp", runtimeClasspath,
                "MobileStudioLayoutLibRenderer",
                layoutFile.absolutePath,
                outputFile.absolutePath,
                platformJar.absolutePath,
                runtime.frameworkResDir.absolutePath,
                widthPx.coerceAtLeast(240).toString(),
                heightPx.coerceAtLeast(320).toString()
            ),
            javaHome = javaHome,
            cwd = workDir,
            timeoutMs = 12_000L
        )

        return when {
            render.exitCode == 0 && outputFile.exists() && outputFile.length() > 0 -> {
                Result(Status.RENDERED, outputFile, "Rendered with Android LayoutLib Bridge.")
            }
            render.timedOut -> {
                Result(
                    Status.FAILED,
                    message = "LayoutLib render timed out; using lightweight preview.",
                    detail = buildString {
                        appendLine(render.output)
                        append(layoutLibDiagnostics(javaHome, javaBin, runtime, render))
                    }
                )
            }
            else -> {
                Result(
                    Status.FAILED,
                    message = "LayoutLib render failed; using lightweight preview.",
                    detail = buildString {
                        appendLine(render.output.takeLast(4_000))
                        append(layoutLibDiagnostics(javaHome, javaBin, runtime, render))
                    }
                )
            }
        }
    }

    private fun hasUsableJli(javaHome: File): Boolean {
        return File(javaHome, "lib/libjli.so").let { it.isFile && it.length() > 0L } ||
            File(javaHome, "lib/jli/libjli.so").let { it.isFile && it.length() > 0L }
    }

    private fun resolveLayoutLibJavaHome(installer: JreGradleInstaller): File {
        val toolchain = ToolchainManager(context)
        // LayoutLib requires JDK 21+ for proper rendering
        val roots = listOf(
            File(context.filesDir, "toolchain/jdk/jdk-21"),
            File(context.filesDir, "toolchain/jdk/lib/jvm/java-21-openjdk"),
            File(context.filesDir, "toolchain/jdk"),
            toolchain.activeJdkHome(),
            installer.effectiveJavaHome
        ).filterNotNull()
        
        return roots.asSequence()
            .flatMap { root ->
                sequence {
                    yield(root)
                    if (root.exists()) {
                        root.walkTopDown()
                            .maxDepth(5)
                            .filter { it.isDirectory && File(it, "bin/java").exists() }
                            .forEach { yield(it) }
                    }
                }
            }
            .distinctBy { it.absolutePath }
            .filter { hasUsableJli(it) && File(it, "jmods/jdk.compiler.jmod").exists() }
            .filter { "/shim/" !in it.absolutePath } // CRITICAL: Avoid shim folders
            .sortedWith(
                compareByDescending<File> { javaFeature(it) >= 21 }
                    .thenByDescending { javaFeature(it) }
                    .thenBy { it.absolutePath.length }
            )
            .firstOrNull()
            ?: installer.effectiveJavaHome
    }

    private fun compileUnavailableResult(
        compile: ProcessResult,
        runtime: Runtime,
        platformJar: File,
        sourceFile: File,
        classesDir: File,
        javaHome: File,
        javaBin: File
    ): Result? {
        val output = compile.output
        val message = when {
            output.contains("libjli.so not found", ignoreCase = true) ->
                "Active JDK is incomplete; using lightweight preview."
            output.contains("class file has wrong version", ignoreCase = true) ->
                "LayoutLib requires a newer JDK; using lightweight preview."
            output.contains("Bridge is abstract", ignoreCase = true) ->
                "LayoutLib Bridge API is incompatible; using lightweight preview."
            else -> return null
        }
        return Result(
            status = Status.UNAVAILABLE,
            message = message,
            detail = buildString {
                appendLine("command: ${File(javaHome, "bin/javac").absolutePath} -cp <layoutlib jars> -d ${classesDir.absolutePath} ${sourceFile.absolutePath}")
                appendLine("exitCode: ${compile.exitCode}")
                appendLine("classpathEntries: ${(runtime.jars + platformJar).count { it.exists() }}")
                appendLine("javaHome: ${javaHome.absolutePath}")
                appendLine("javaBin: ${javaBin.absolutePath}")
                append(layoutLibDiagnostics(javaHome, javaBin, runtime, compile))
                appendLine("hint: Activate a full JDK folder with libjli.so, or import a LayoutLib runtime matching that JDK.")
                if (output.isNotBlank()) {
                    appendLine("output:")
                    append(output.takeLast(4_000))
                }
            }
        )
    }

    private fun validateLayoutXml(layoutFile: File): Result? {
        val text = runCatching { layoutFile.readText() }.getOrElse { exception ->
            return Result(Status.FAILED, message = "Layout XML read failed.", detail = exception.message.orEmpty())
        }
        val declarations = Regex("""<\?xml\b""").findAll(text).toList()
        if (declarations.size > 1) {
            val secondOffset = declarations[1].range.first
            val line = text.take(secondOffset).count { it == '\n' } + 1
            return Result(
                Status.FAILED,
                message = "Layout XML parse failed.",
                detail = "Duplicate XML declaration at line $line. Keep only one <?xml version=\"1.0\" encoding=\"utf-8\"?> at the top."
            )
        }
        val firstNonWhitespace = text.indexOfFirst { !it.isWhitespace() }
        if (declarations.isNotEmpty() && firstNonWhitespace >= 0 && declarations.first().range.first != firstNonWhitespace) {
            return Result(
                Status.FAILED,
                message = "Layout XML parse failed.",
                detail = "XML declaration must be the first token in the file."
            )
        }
        return null
    }

    private fun findRuntime(): Runtime? {
        val toolchain = ToolchainManager(context)
        val platformDir = findPlatformDir(toolchain) ?: return null
        val frameworkRes = findLayoutLibDataDir()?.resolve("res")?.takeIf { it.isDirectory }
            ?: File(platformDir, "data/res").takeIf { it.isDirectory }
            ?: return null

        val layoutLibRoot = File(context.filesDir, "toolchain/layoutlib")
        val jarRoots = buildList {
            // Standard layoutlib directories
            add(layoutLibRoot)
            add(File(layoutLibRoot, "lib"))
            add(File(layoutLibRoot, "plugins/android/lib"))
            add(File(layoutLibRoot, "plugins/design-tools/lib"))
            add(File(layoutLibRoot, "android-studio/lib"))
            add(File(layoutLibRoot, "plugins/android/resources"))
            // Also search for Guava and other deps in common locations
            add(File(layoutLibRoot, "lib/ext"))
            add(File(layoutLibRoot, "plugins/android/lib/ext"))
        }.filter { it.exists() }

        val jars = jarRoots
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension.equals("jar", true) }.toList() }
            .distinctBy { it.absolutePath }

        // Also search for Guava jar specifically by name pattern
        val guavaJar = layoutLibRoot.walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", true) &&
                it.name.lowercase(Locale.US).let { n -> n.startsWith("guava") || n.contains("guava") } }
            .firstOrNull()

        val allJars = if (guavaJar != null && jars.none { it.absolutePath == guavaJar.absolutePath }) {
            jars + guavaJar
        } else {
            jars
        }

        val names = allJars.map { it.name.lowercase(Locale.US) }
        val hasLayoutLib = allJars.any { jarContains(it, "com/android/layoutlib/bridge/Bridge.class") }
        val hasApi = allJars.any { jarContains(it, "com/android/ide/common/rendering/api/ILayoutPullParser.class") }
        val hasXmlPull = allJars.any { jarContains(it, "org/xmlpull/v1/XmlPullParser.class") }
        val hasKxml = names.any { it.startsWith("kxml2") } || allJars.any { jarContains(it, "org/kxml2/io/KXmlParser.class") }

        return if (hasLayoutLib && hasApi && hasXmlPull && hasKxml) {
            Runtime(allJars, platformDir, frameworkRes)
        } else {
            null
        }
    }

    private fun findLayoutLibDataDir(): File? {
        val root = File(context.filesDir, "toolchain/layoutlib")
        if (!root.exists()) return null
        val direct = listOf(
            File(root, "plugins/design-tools/resources/layoutlib/data"),
            File(root, "resources/layoutlib/data"),
            File(root, "data")
        )
        return (direct.asSequence() + root.walkTopDown().filter { it.isDirectory && it.name == "data" })
            .distinctBy { it.absolutePath }
            .firstOrNull { File(it, "res").isDirectory && File(it, "fonts").isDirectory }
    }

    private fun findPlatformDir(toolchain: ToolchainManager): File? {
        val roots = buildList {
            toolchain.activeDir(ToolchainManager.Kind.SDK)?.let { add(it) }
            File(context.filesDir, "toolchain/sdk/active").takeIf { it.exists() }?.let { add(it) }
            File(context.filesDir, "toolchain/sdk").listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("android-") }
                ?.forEach { add(it) }
        }.distinctBy { it.absolutePath }

        return roots.flatMap { sdk ->
            buildList {
                add(sdk)
                File(sdk, "platforms").listFiles()
                    ?.filter { it.isDirectory && it.name.startsWith("android-") }
                    ?.forEach { add(it) }
            }
        }.firstOrNull { File(it, "android.jar").isFile && File(it, "data/res").isDirectory }
    }

    private fun jarContains(jar: File, entryName: String): Boolean {
        return runCatching {
            java.util.jar.JarFile(jar).use { it.getEntry(entryName) != null }
        }.getOrDefault(false)
    }

    private fun layoutLibCompatibilityProblem(runtime: Runtime, javaHome: File): Result? {
        val bridgeJar = runtime.jars.firstOrNull { jarContains(it, "com/android/layoutlib/bridge/Bridge.class") } ?: return null
        val bridgeInfo = classInfo(bridgeJar, "com/android/layoutlib/bridge/Bridge.class") ?: return null
        val runtimeMajor = javaFeature(javaHome) + 44
        if (bridgeInfo.major > runtimeMajor) {
            return Result(
                Status.UNAVAILABLE,
                message = "LayoutLib requires a newer JDK; using lightweight preview.",
                detail = buildString {
                    appendLine("layoutlib.jar Bridge.class is Java class ${bridgeInfo.major}. Current JDK supports up to $runtimeMajor. Install/activate JDK ${bridgeInfo.major - 44}+ or import an older Android Studio LayoutLib built for JDK ${javaFeature(javaHome)}.")
                    append(layoutLibDiagnostics(javaHome, File(javaHome, "bin/java"), runtime))
                }
            )
        }
        if (bridgeInfo.isAbstract) {
            return Result(
                Status.UNAVAILABLE,
                message = "LayoutLib Bridge API is incompatible; using lightweight preview.",
                detail = buildString {
                    appendLine("This LayoutLib exposes abstract com.android.layoutlib.bridge.Bridge. Import an older Android Studio LayoutLib runtime or update the renderer adapter for this exact LayoutLib API.")
                    append(layoutLibDiagnostics(javaHome, File(javaHome, "bin/java"), runtime))
                }
            )
        }
        return null
    }

    private fun layoutLibDiagnostics(
        javaHome: File?,
        javaBin: File?,
        runtime: Runtime?,
        process: ProcessResult? = null
    ): String = buildString {
        val bridgeJar = runtime?.jars?.firstOrNull { jarContains(it, "com/android/layoutlib/bridge/Bridge.class") }
        val bridgeInfo = bridgeJar?.let { classInfo(it, "com/android/layoutlib/bridge/Bridge.class") }
        appendLine("diagnostics:")
        appendLine("  jdkPath: ${javaHome?.absolutePath ?: "<missing>"}")
        appendLine("  javaBin: ${javaBin?.absolutePath ?: "<missing>"}")
        appendLine("  javaVersion: ${javaHome?.let { javaFeature(it) } ?: 0}")
        appendLine("  libjli: ${javaHome?.let { hasUsableJli(it) } ?: false}")
        appendLine("  jdkCompiler: ${javaHome?.let { File(it, "jmods/jdk.compiler.jmod").exists() } ?: false}")
        appendLine("  layoutLibJars: ${runtime?.jars?.size ?: 0}")
        appendLine("  layoutLibBridgeJar: ${bridgeJar?.absolutePath ?: "<missing>"}")
        appendLine("  bridgeClassMajor: ${bridgeInfo?.major ?: 0}")
        appendLine("  nativeExecutableStartFailed: ${process?.exitCode == 127}")
        appendLine("  permissionDenied: ${process?.output?.contains("Permission denied", ignoreCase = true) == true || NativeExec.getLastError().contains("Permission denied", ignoreCase = true)}")
        appendLine("  processExitCode: ${process?.exitCode ?: "<not-run>"}")
        appendLine("  processTimedOut: ${process?.timedOut ?: false}")
        appendLine("  nativeLastError: ${NativeExec.getLastError()}")
    }

    private data class ClassInfo(val major: Int, val isAbstract: Boolean)

    private fun classInfo(jar: File, entryName: String): ClassInfo? = runCatching {
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry(entryName) ?: return@use null
            zip.getInputStream(entry).use { input ->
                val header = ByteArray(10)
                if (input.read(header) != header.size) return@use null
                val major = ((header[6].toInt() and 0xff) shl 8) or (header[7].toInt() and 0xff)
                val constantPoolCount = ((header[8].toInt() and 0xff) shl 8) or (header[9].toInt() and 0xff)
                var index = 1
                while (index < constantPoolCount) {
                    when (input.read()) {
                        1 -> {
                            val len = readU2(input)
                            input.skip(len.toLong())
                        }
                        3, 4, 9, 10, 11, 12, 17, 18 -> input.skip(4)
                        5, 6 -> {
                            input.skip(8)
                            index++
                        }
                        7, 8, 16, 19, 20 -> input.skip(2)
                        15 -> input.skip(3)
                        else -> return@use null
                    }
                    index++
                }
                val accessFlags = readU2(input)
                ClassInfo(major, (accessFlags and 0x0400) != 0)
            }
        }
    }.getOrNull()

    private fun readU2(input: java.io.InputStream): Int {
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) return 0
        return ((hi and 0xff) shl 8) or (lo and 0xff)
    }

    private fun javaFeature(javaHome: File): Int {
        val release = File(javaHome, "release")
        val text = runCatching { release.readText() }.getOrDefault("")
        Regex("""JAVA_VERSION="(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        Regex("""java-(\d+)""").find(javaHome.absolutePath)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        return 17
    }

    private fun runNative(
        executable: File,
        args: List<String>,
        javaHome: File,
        cwd: File,
        timeoutMs: Long
    ): ProcessResult {
        val ldPaths = listOf(
            File(javaHome, "lib/server"),
            File(javaHome, "lib"),
            File(javaHome, "lib/jli"),
            File(context.applicationInfo.nativeLibraryDir)
        ).filter { it.exists() }.joinToString(":") { it.absolutePath }

        val env = mapOf(
            "JAVA_HOME" to javaHome.absolutePath,
            "LD_LIBRARY_PATH" to ldPaths,
            "PATH" to "/system/bin:/system/xbin",
            "TMPDIR" to File(context.cacheDir, "layoutlib_tmp").apply { mkdirs() }.absolutePath,
            "HOME" to context.filesDir.absolutePath
        )

        val pb = ExecHelper.createProcess(
            context = context,
            args = args,
            cwd = cwd,
            env = env
        ).redirectErrorStream(true)

        return try {
            val proc = pb.start()
            val output = StringBuilder()
            val reader = Thread {
                runCatching { proc.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
            }.apply { isDaemon = true; start() }

            val finished = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } else {
                proc.waitFor(); true
            }
            
            if (!finished) {
                proc.destroyRecursive()
                reader.join(500)
                ProcessResult(-1, output.toString(), true)
            } else {
                reader.join(1000)
                ProcessResult(proc.exitValue(), output.toString(), false)
            }
        } catch (e: Exception) {
            ProcessResult(127, "Failed to start process: ${e.message}\n${Log.getStackTraceString(e)}")
        }
    }

    private fun Process.destroyRecursive() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            this.destroyForcibly()
        } else {
            this.destroy()
        }
    }

    private fun cacheName(file: File, widthPx: Int, heightPx: Int): String {
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}|$widthPx|$heightPx"
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "layoutlib_$digest.png"
    }

    private fun dataDataAlias(file: File): File {
        val userPrefix = "/data/user/0/${context.packageName}"
        val dataPrefix = "/data/data/${context.packageName}"
        val path = file.absolutePath
        return if (path.startsWith(userPrefix)) {
            File(dataPrefix + path.removePrefix(userPrefix))
        } else {
            file
        }
    }

    private fun rendererSource(): String = """
import com.android.ide.common.rendering.api.*;
import com.android.layoutlib.bridge.Bridge;
import com.android.resources.*;
import org.kxml2.io.KXmlParser;
import java.io.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

public class MobileStudioLayoutLibRenderer {
  static class Parser extends KXmlParser implements ILayoutPullParser {
    Parser(File file) throws Exception {
      setFeature(FEATURE_PROCESS_NAMESPACES, true);
      setInput(new FileReader(file));
    }
    public Object getViewCookie() { return null; }
    public ResourceNamespace getLayoutNamespace() { return ResourceNamespace.RES_AUTO; }
  }

  static class Log implements ILayoutLog {}

  static class Callback extends LayoutlibCallback {
    public Object loadView(String name, Class[] sig, Object[] args) throws Exception { return null; }
    public ResourceReference resolveResourceId(int id) { return null; }
    public int getOrGenerateResourceId(ResourceReference ref) { return ref == null ? 0 : Math.abs(ref.toString().hashCode()); }
    public ILayoutPullParser getParser(ResourceValue value) { return null; }
    public AdapterBinding getAdapterBinding(Object viewCookie, Map<String, String> attributes) { return null; }
    public ActionBarCallback getActionBarCallback() { return null; }
    public org.xmlpull.v1.XmlPullParser createXmlParserForPsiFile(String s) { return createXmlParser(); }
    public org.xmlpull.v1.XmlPullParser createXmlParserForFile(String s) { return createXmlParser(); }
    public org.xmlpull.v1.XmlPullParser createXmlParser() { return new KXmlParser(); }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 6) throw new IllegalArgumentException("layout out androidJar frameworkRes width height required");
    File layout = new File(args[0]);
    File out = new File(args[1]);
    File androidJar = new File(args[2]);
    File frameworkRes = new File(args[3]);
    File platform = androidJar.getParentFile();
    File dataDir = frameworkRes.getParentFile();
    File runtimeRoot = dataDir.getParentFile();
    int width = Integer.parseInt(args[4]);
    int height = Integer.parseInt(args[5]);

    if (!androidJar.isFile()) throw new IllegalArgumentException("android.jar not found: " + androidJar);
    if (!frameworkRes.isDirectory()) throw new IllegalArgumentException("framework res not found: " + frameworkRes);
    System.setProperty("android.jar.path", androidJar.getAbsolutePath());
    System.setProperty("layoutlib.android.jar", androidJar.getAbsolutePath());
    System.setProperty("layoutlib.platform.dir", platform.getAbsolutePath());
    System.setProperty("layoutlib.data.dir", dataDir.getAbsolutePath());
    System.setProperty("layoutlib.framework.res.dir", frameworkRes.getAbsolutePath());

    Properties props = new Properties();
    File buildProp = new File(runtimeRoot, "build.prop");
    if (!buildProp.isFile()) buildProp = new File(platform, "build.prop");
    if (buildProp.isFile()) try (FileInputStream in = new FileInputStream(buildProp)) { props.load(in); }
    Map<String, String> platformProps = new HashMap<>();
    for (String name : props.stringPropertyNames()) platformProps.put(name, props.getProperty(name));

    Bridge bridge = new Bridge();
    boolean initialized = bridge.init(
      platformProps,
      new File(dataDir, "fonts"),
      null,
      null,
      "MobileStudio",
      new String[0],
      new HashMap<String, Map<String, Integer>>(),
      new Log()
    );
    if (!initialized) throw new IllegalStateException("Bridge init failed");

    RenderResources resources = new RenderResources();
    StyleResourceValueImpl theme = new StyleResourceValueImpl(ResourceNamespace.ANDROID, "Theme.Material.Light.NoActionBar", null, null);
    resources.applyStyle(theme, true);

    HardwareConfig hardware = new HardwareConfig(
      width,
      height,
      Density.XHIGH,
      320f,
      320f,
      ScreenSize.NORMAL,
      ScreenOrientation.PORTRAIT,
      ScreenRound.NOTROUND,
      true
    );
    SessionParams params = new SessionParams(
      new Parser(layout),
      SessionParams.RenderingMode.NORMAL,
      null,
      hardware,
      resources,
      new Callback(),
      15,
      34,
      new Log()
    );
    params.setExtendedViewInfoMode(true);

    RenderSession session = bridge.createSession(params);
    Result create = session.getResult();
    if (create == null || !create.isSuccess()) {
      throw new IllegalStateException("createSession failed: " + (create == null ? "null" : create.getErrorMessage()));
    }
    Result render = session.render(5000L);
    if (render == null || !render.isSuccess()) {
      throw new IllegalStateException("render failed: " + (render == null ? "null" : render.getErrorMessage()));
    }
    BufferedImage image = session.getImage();
    if (image == null) throw new IllegalStateException("No rendered image");
    out.getParentFile().mkdirs();
    ImageIO.write(image, "png", out);
    session.dispose();
    bridge.dispose();
    System.out.println("OK " + out.getAbsolutePath());
  }
}
""".trimIndent()
}
