package com.aide.mobilestudio.extension

import android.content.Context
import com.aide.mobilestudio.BuildToolchain
import com.aide.mobilestudio.analysis.KotlinCompiler
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolves the tools an extension build needs: the JDK's javac, android.jar and
 * the build-tools `d8`.
 *
 * Same lookup rules as [com.aide.mobilestudio.validation.DeviceValidationToolchain]
 * so an extension builds with whatever toolchain the user already installed for
 * Android projects -- nothing extra to download.
 */
object ExtensionToolchain {

    fun javac(context: Context): File? =
        KotlinCompiler.findJavaBin(context)
            ?.parentFile
            ?.let { File(it, "javac") }
            ?.takeIf { it.isFile }
            ?.also { makeExecutable(it) }

    fun androidJar(context: Context): File? = KotlinCompiler.findAndroidJar(context)

    /** build-tools `d8` (a launcher script next to lib/d8.jar). */
    fun d8(context: Context): File? {
        val roots = listOfNotNull(
            BuildToolchain.sdkDir(context),
            File(context.filesDir, "toolchain/sdk/active"),
            File(context.filesDir, "toolchain/sdk"),
            System.getenv("ANDROID_HOME")?.let { File(it) },
            System.getenv("ANDROID_SDK_ROOT")?.let { File(it) },
        )
        for (root in roots) {
            val buildTools = File(root, "build-tools")
            if (!buildTools.isDirectory) continue
            val candidates = buildTools.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.map { File(it, "d8") }
                ?.filter { it.isFile }
                .orEmpty()
            for (candidate in candidates) {
                makeExecutable(candidate)
                if (candidate.canExecute()) return candidate
            }
        }
        return null
    }

    internal fun makeExecutable(file: File) {
        runCatching {
            file.setReadable(true, false)
            file.setExecutable(true, false)
        }
    }
}

/**
 * Compiles an extension project into the `classes.dex` that [ExtensionHost]
 * loads.
 *
 * The extension API (MobileStudioExtension / ExtensionContext /
 * ExtensionManifest) is provided as a compile-time-only stub jar extracted
 * from this app's own classes. At runtime the stub is never on the class path:
 * PathClassLoader delegates to the app first, so the interface the extension
 * was compiled against and the one it is cast to are the same class.
 *
 * Flow:  javac (stub + android.jar)  ->  d8  ->  build/classes.dex
 * `build/classes.dex` is exactly where [ExtensionManager.packMsext] looks, so a
 * successful build is immediately packagable.
 */
class ExtensionBuilder(
    private val javac: File?,
    private val androidJar: File?,
    private val d8: File?,
) {

    data class Result(
        val ok: Boolean,
        val dex: File?,
        val errors: List<String>,
        val compiledClasses: Int = 0,
    )

    /**
     * @param apiJar compile-time stub for the extension API; must exist whenever
     *   the project has Java sources
     * @param entryPoint the manifest's entryPoint, or null/blank for a
     *   metadata-only extension
     */
    fun build(
        projectDir: File,
        apiJar: File,
        entryPoint: String?,
        onProgress: (String) -> Unit = {},
    ): Result {
        val sources = collectJavaSources(projectDir)
        val wantsCode = !entryPoint.isNullOrBlank()

        // ------------------------------------------------------- no code at all
        if (sources.isEmpty()) {
            return if (wantsCode) {
                Result(
                    ok = false, dex = null,
                    errors = listOf(
                        "entryPoint '$entryPoint'이 선언되어 있지만 찾을 수 있는 .java 소스가 없습니다 " +
                            "(src/ 아래에 확장 클래스를 두세요)"
                    ),
                )
            } else {
                // metadata-only extension: nothing to compile
                Result(ok = true, dex = null, errors = emptyList())
            }
        }

        // ------------------------------------------------------ tool availability
        val missing = buildList {
            if (javac == null || !javac.isFile) add("javac을 찾을 수 없습니다 — JDK를 먼저 설치하세요")
            if (androidJar == null || !androidJar.isFile) add("android.jar을 찾을 수 없습니다 — Android SDK 플랫폼을 먼저 설치하세요")
            if (d8 == null || !d8.isFile) add("d8을 찾을 수 없습니다 — Android SDK build-tools를 먼저 설치하세요")
        }
        if (missing.isNotEmpty()) return Result(ok = false, dex = null, errors = missing)
        if (!apiJar.isFile) {
            return Result(ok = false, dex = null,
                errors = listOf("확장 API 스텁 jar를 찾을 수 없습니다 (${apiJar.name})"))
        }

        val buildDir = File(projectDir, "build")
        val classesDir = File(buildDir, "ext-classes")
        val dexDir = File(buildDir, "ext-dex")
        classesDir.deleteRecursively(); classesDir.mkdirs()
        dexDir.deleteRecursively(); dexDir.mkdirs()

        // ------------------------------------------------------------- javac
        onProgress("Java 소스 ${sources.size}개 컴파일 중...")
        val javacArgs = mutableListOf(
            javac!!.absolutePath,
            "-nowarn", "-proc:none", "-encoding", "UTF-8",
            "-d", classesDir.absolutePath,
            "-classpath", listOf(apiJar, androidJar!!).joinToString(File.pathSeparator) { it.absolutePath },
        )
        val javacArgFile = File(buildDir, "ext-javac.args")
        writeArgFile(javacArgFile, sources.map { it.absolutePath })
        javacArgs += "@${javacArgFile.absolutePath}"

        val javacRun = run(javacArgs, projectDir, TIMEOUT_JAVAC_MS)
        if (javacRun.exitCode != 0) {
            return Result(ok = false, dex = null, errors = javacRun.diagnostics(), compiledClasses = 0)
        }
        val classes = classesDir.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
        if (classes.isEmpty()) {
            return Result(ok = false, dex = null,
                errors = listOf("javac이 클래스를 생성하지 않았습니다"))
        }

        // ----------------------------------------------------------------- d8
        onProgress("DEX 변환 중 (${classes.size}개 클래스)...")
        val d8Args = mutableListOf(
            d8!!.absolutePath,
            "--release",
            "--lib", androidJar!!.absolutePath,
            "--classpath", apiJar.absolutePath,
            "--output", dexDir.absolutePath,
        )
        val d8ArgFile = File(buildDir, "ext-d8.args")
        writeArgFile(d8ArgFile, classes.map { it.absolutePath })
        d8Args += "@${d8ArgFile.absolutePath}"

        val d8Run = run(d8Args, projectDir, TIMEOUT_D8_MS)
        if (d8Run.exitCode != 0) {
            return Result(ok = false, dex = null, errors = d8Run.diagnostics(), compiledClasses = classes.size)
        }
        val produced = File(dexDir, "classes.dex")
        if (!produced.isFile) {
            return Result(ok = false, dex = null,
                errors = listOf("d8이 classes.dex를 생성하지 않았습니다"), compiledClasses = classes.size)
        }

        val target = File(buildDir, "classes.dex")
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) {
            return Result(ok = false, dex = null,
                errors = listOf("기존 classes.dex를 교체할 수 없습니다: ${target.absolutePath}"))
        }
        if (!produced.renameTo(target)) {
            produced.copyTo(target, overwrite = true)
        }
        onProgress("classes.dex 생성됨 (${target.length()} bytes)")
        return Result(ok = true, dex = target, errors = emptyList(), compiledClasses = classes.size)
    }

    // ------------------------------------------------------------------ pure
    /** Every .java under [root], skipping build output and VCS directories. */
    internal fun collectJavaSources(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it.name !in SKIP_DIRS }
            .filter { it.isFile && it.extension == "java" }
            .sortedBy { it.absolutePath }
            .toList()

    /**
     * Writes an @argfile. javac and d8 both accept one, which sidesteps
     * E2BIG on the argument array once a project has a few hundred classes.
     * Quoted so paths containing spaces survive.
     */
    internal fun writeArgFile(target: File, args: List<String>) {
        target.parentFile?.mkdirs()
        target.writeText(args.joinToString("\n") { "\"${it.replace("\"", "\\\"")}\"" } + "\n",
            Charsets.UTF_8)
    }

    private data class RunResult(val exitCode: Int, val output: String) {
        fun diagnostics(): List<String> {
            val lines = output.lines().filter { it.isNotBlank() }
            return if (lines.isEmpty()) listOf("도구가 오류 출력 없이 실패했습니다 (exit $exitCode)")
            else lines.take(MAX_DIAGNOSTICS)
        }
    }

    private fun run(args: List<String>, cwd: File, timeoutMs: Long): RunResult {
        val process = ProcessBuilder(args)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            return RunResult(-1, "타임아웃 (${timeoutMs / 1000}s)")
        }
        return RunResult(process.exitValue(), output)
    }

    companion object {
        private const val TIMEOUT_JAVAC_MS = 120_000L
        private const val TIMEOUT_D8_MS = 120_000L
        private const val MAX_DIAGNOSTICS = 40
        private val SKIP_DIRS = setOf(
            "build", "out", ".git", ".gradle", ".idea", "node_modules",
            "build-tools", "platforms", "platform-tools",
        )
    }
}
