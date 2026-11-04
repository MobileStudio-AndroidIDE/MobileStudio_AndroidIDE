package com.aide.mobilestudio.validation

import java.io.File

/**
 * Locates the host tools the sample-project tests need: the JDK running the
 * tests, the Android SDK (local.properties / ANDROID_HOME) and the Kotlin
 * compiler that ships inside the Gradle wrapper distribution. No project
 * paths are hardcoded - everything comes from the environment.
 */
object HostToolchain {

    fun javaBin(): File? =
        File(System.getProperty("java.home"), "bin/java").takeIf { it.isFile }

    fun javacBin(): File? =
        File(System.getProperty("java.home"), "bin/javac").takeIf { it.isFile }

    fun sdkDir(): File? {
        val fromEnv = listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")
            .mapNotNull { System.getenv(it) }
            .map { File(it) }
            .firstOrNull { it.isDirectory }
        if (fromEnv != null) return fromEnv
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = File(dir, "local.properties")
            if (candidate.isFile) {
                val text = runCatching { candidate.readText() }.getOrDefault("")
                val match = Regex("""(?m)^sdk\.dir\s*=\s*(.+)$""").find(text)
                if (match != null) {
                    val path = match.groupValues[1].trim().replace("\\\\", "\\")
                    File(path).takeIf { it.isDirectory }?.let { return it }
                }
            }
            dir = dir?.parentFile
        }
        return null
    }

    fun androidJar(): File? {
        val platforms = File(sdkDir() ?: return null, "platforms")
        if (!platforms.isDirectory) return null
        return platforms.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { File(it, "android.jar") }
            ?.firstOrNull { it.isFile }
    }

    fun aapt2(): File? {
        val buildTools = File(sdkDir() ?: return null, "build-tools")
        if (!buildTools.isDirectory) return null
        return buildTools.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { File(it, "aapt2") }
            ?.firstOrNull { it.isFile && it.canExecute() }
    }

    /** Gradle wrapper dist lib dir containing kotlin-compiler-embeddable-*.jar. */
    private fun kotlinDistLibDir(): File? {
        val gradleHome = System.getenv("GRADLE_USER_HOME")?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".gradle")
        val dists = File(gradleHome, "wrapper/dists")
        if (!dists.isDirectory) return null
        val candidates = mutableListOf<File>()
        dists.walkTopDown().maxDepth(5)
            .filter { it.isDirectory && it.name == "lib" }
            .forEach { lib ->
                if (lib.listFiles()?.any {
                        it.isFile && it.name.startsWith("kotlin-compiler") && it.name.endsWith(".jar")
                    } == true
                ) candidates.add(lib)
            }
        return candidates.sortedByDescending { it.absolutePath }.firstOrNull()
    }

    fun kotlinCompilerJar(): File? {
        val lib = kotlinDistLibDir() ?: return null
        return lib.listFiles()
            ?.firstOrNull {
                it.isFile && it.name.startsWith("kotlin-compiler") &&
                    it.name.contains("embeddable") && it.name.endsWith(".jar")
            }
            ?: lib.listFiles()?.firstOrNull {
                it.isFile && it.name.startsWith("kotlin-compiler") && it.name.endsWith(".jar")
            }
    }

    fun kotlinRuntimeJars(): List<File> {
        val lib = kotlinDistLibDir() ?: return emptyList()
        val prefixes = listOf(
            "kotlin-stdlib", "kotlin-reflect", "kotlin-script-runtime",
            "kotlin-scripting", "kotlinx-coroutines", "annotations-", "trove4j"
        )
        return lib.listFiles()
            ?.filter { it.isFile && it.extension == "jar" && prefixes.any { p -> it.name.startsWith(p) } }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    fun kotlinStdlib(): File? =
        kotlinRuntimeJars().firstOrNull { it.name.startsWith("kotlin-stdlib") }

    /** All host tools or null when the environment cannot support a test. */
    fun toolchain(): ValidationToolchain? {
        val java = javaBin() ?: return null
        val compiler = kotlinCompilerJar() ?: return null
        val stdlib = kotlinStdlib() ?: return null
        val androidJar = androidJar() ?: return null
        val aapt2 = aapt2() ?: return null
        val javac = javacBin() ?: return null
        return ValidationToolchain(
            javaBin = java,
            javacBin = javac,
            kotlinCompilerJar = compiler,
            kotlinRuntimeJars = kotlinRuntimeJars(),
            kotlinStdlibJar = stdlib,
            androidJar = androidJar,
            aapt2 = aapt2,
            dependencyJars = emptyList()
        )
    }
}
