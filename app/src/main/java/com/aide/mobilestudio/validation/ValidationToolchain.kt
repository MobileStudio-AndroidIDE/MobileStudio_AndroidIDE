package com.aide.mobilestudio.validation

import java.io.File

/**
 * Resolved locations of every external tool the validator shells out to.
 * Built on-device from the MobileStudio toolchain (ToolchainManager / Gradle
 * dist / Android SDK / overrides) and on the host test JVM from the local SDK
 * and Gradle wrapper distribution. Checks never search for tools themselves.
 */
data class ValidationToolchain(
    val javaBin: File?,
    val javacBin: File?,
    val kotlinCompilerJar: File?,
    val kotlinRuntimeJars: List<File>,
    val kotlinStdlibJar: File?,
    val androidJar: File?,
    val aapt2: File?,
    val dependencyJars: List<File>
) {
    fun hasKotlinCompiler(): Boolean =
        javaBin != null && javaBin.canExecute() && kotlinCompilerJar != null && kotlinCompilerJar.isFile

    fun hasJavac(): Boolean = javacBin != null && javacBin.canExecute()

    fun hasAapt2(): Boolean = aapt2 != null && aapt2.isFile && aapt2.canExecute()

    fun hasAndroidJar(): Boolean = androidJar != null && androidJar.isFile

    /** JVM classpath used to launch org.jetbrains.kotlin.cli.jvm.K2JVMCompiler. */
    fun kotlinCompilerClasspath(): List<File> {
        val jar = kotlinCompilerJar ?: return emptyList()
        return (listOf(jar) + kotlinRuntimeJars).distinctBy { it.absolutePath }
    }

    /** Classpath passed to -cp for the sources under analysis (stdlib + android + deps). */
    fun kotlinSourceClasspath(): List<File> {
        val parts = mutableListOf<File>()
        kotlinStdlibJar?.let { parts.add(it) }
        androidJar?.let { parts.add(it) }
        parts.addAll(dependencyJars)
        return parts.distinctBy { it.absolutePath }
    }

    fun javacClasspath(): List<File> {
        val parts = mutableListOf<File>()
        androidJar?.let { parts.add(it) }
        parts.addAll(dependencyJars)
        return parts.distinctBy { it.absolutePath }
    }

    /**
     * Fingerprint of the toolchain itself: any tool change (upgrade, platform
     * install, new dependency cache) invalidates cached check results.
     */
    fun toolSignature(): String {
        val sb = StringBuilder()
        fun add(f: File?) {
            if (f == null) return
            sb.append(f.absolutePath).append('|')
                .append(f.length()).append('|')
                .append(f.lastModified()).append(';')
        }
        add(javaBin); add(javacBin); add(kotlinCompilerJar); add(androidJar); add(aapt2); add(kotlinStdlibJar)
        kotlinRuntimeJars.forEach { add(it) }
        // Dependency jars come and go; hash the set of paths only (sizes too many files).
        sb.append("deps:")
        dependencyJars.forEach { sb.append(it.absolutePath).append(',').append(it.length()).append(';') }
        return sha256Hex(sb.toString())
    }

    companion object {
        fun sha256Hex(text: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
