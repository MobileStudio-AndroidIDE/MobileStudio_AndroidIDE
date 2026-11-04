package com.aide.mobilestudio.validation

import android.content.Context
import com.aide.mobilestudio.BuildToolchain
import com.aide.mobilestudio.ExecHelper
import com.aide.mobilestudio.analysis.KotlinCompiler
import java.io.File

/**
 * Resolves the validation toolchain from the MobileStudio toolchain layout:
 * active JDK (ToolchainManager), Kotlin compiler inside the Gradle
 * distribution, Android SDK platform jars, build-tools aapt2 (with
 * toolchain/overrides/aapt2 taking priority) and the Gradle dependency cache.
 */
object DeviceValidationToolchain {

    fun create(context: Context): ValidationToolchain {
        val javaBin = KotlinCompiler.findJavaBin(context)
        val javac = javaBin?.parentFile
            ?.let { File(it, "javac") }
            ?.takeIf { it.isFile }
            ?.also { ensureExecutable(it) }
        val compilerJar = KotlinCompiler.findKotlinCompilerJar(context)
        val runtimeJars = compilerJar?.let { KotlinCompiler.compilerRuntimeJars(it) }.orEmpty()
        val stdlib = runtimeJars.firstOrNull { it.name.startsWith("kotlin-stdlib") }
            ?: compilerJar?.parentFile?.let { dir ->
                dir.listFiles()?.firstOrNull {
                    it.isFile && it.name.startsWith("kotlin-stdlib") && it.name.endsWith(".jar")
                }
            }
        val androidJar = KotlinCompiler.findAndroidJar(context)
        val aapt2 = findAapt2(context)
        val deps = KotlinCompiler.dependencyJars(context)
        return ValidationToolchain(
            javaBin = javaBin,
            javacBin = javac,
            kotlinCompilerJar = compilerJar,
            kotlinRuntimeJars = runtimeJars,
            kotlinStdlibJar = stdlib,
            androidJar = androidJar,
            aapt2 = aapt2,
            dependencyJars = deps
        )
    }

    /** Spec priority: toolchain/overrides/aapt2 first, then SDK build-tools. */
    private fun findAapt2(context: Context): File? {
        val override = File(context.filesDir, "toolchain/overrides/aapt2")
        if (override.isFile) {
            ensureExecutable(override)
            return override
        }
        val sdkRoots = listOfNotNull(
            BuildToolchain.sdkDir(context),
            File(context.filesDir, "toolchain/sdk/active"),
            File(context.filesDir, "toolchain/sdk"),
            System.getenv("ANDROID_HOME")?.let { File(it) },
            System.getenv("ANDROID_SDK_ROOT")?.let { File(it) }
        )
        for (root in sdkRoots) {
            val buildTools = File(root, "build-tools")
            if (!buildTools.isDirectory) continue
            val candidates = buildTools.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.map { File(it, "aapt2") }
                ?.filter { it.isFile }
                .orEmpty()
            for (candidate in candidates) {
                ensureExecutable(candidate)
                if (candidate.canExecute()) return candidate
            }
        }
        return null
    }

    private fun ensureExecutable(file: File) {
        if (!file.canExecute()) {
            runCatching { file.setExecutable(true, false) }
            runCatching { android.system.Os.chmod(file.absolutePath, 0b111_101_101) }
        }
    }
}

/**
 * ProcessRunner for the device: every tool spawn goes through ExecHelper so
 * ARM64 ELFs and shell scripts in app data work under noexec/SELinux, and
 * libexec_redirect.so is preloaded so the fail-pipe logging gate is active.
 */
class DeviceProcessRunner(private val context: Context) : StreamProcessRunner() {

    private val execRedirect: File? by lazy {
        File(context.applicationInfo.nativeLibraryDir, "libexec_redirect.so").takeIf { it.isFile }
    }

    override fun createProcessBuilder(command: List<String>, cwd: File?): ProcessBuilder {
        val env = buildMap {
            execRedirect?.let { put("LD_PRELOAD", it.absolutePath) }
        }
        return ExecHelper.createProcess(context, command, cwd, env).redirectErrorStream(true)
    }
}
