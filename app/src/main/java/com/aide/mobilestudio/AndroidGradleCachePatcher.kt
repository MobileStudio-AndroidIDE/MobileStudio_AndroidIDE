package com.aide.mobilestudio

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object AndroidGradleCachePatcher {

    private const val AAPT2_DAEMON_CLASS =
        "com/android/builder/internal/aapt/v2/Aapt2DaemonImpl.class"

    private const val GRADLE_EXEC_ACTION_CLASS =
        "com/android/build/gradle/internal/process/GradleProcessExecutor\$ExecAction.class"

    private const val NATIVE_LAUNCHER_CLASS =
        "net/rubygrapefruit/platform/internal/DefaultProcessLauncher.class"

    private const val PATCH_MARKER_SUFFIX =
        ".mobilestudio-jdk-cmake-tool-proxy-v31"

    private const val EXEC_PATCH_MARKER =
        "MOBILESTUDIO_JDK_CMAKE_TOOL_PROXY_V30"

    /*
     * AAPT2 daemon bootstrap.
     *
     * AAPT2 itself can live inside the application-private toolchain area,
     * which is subject to Android's noexec restrictions.
     *
     * The actual executable redirection is handled by the native exec
     * redirect layer. This bootstrap only chooses an Android-executable
     * launcher for the AAPT2 daemon.
     */
    private const val AAPT2_SHELL_BOOTSTRAP =
        // Resolve the aapt2 executable: env override wins, $0 is AGP's path
        // (which equals the override when aapt2FromMavenOverride is wired up).
        "AAPT2_EXEC=\"\${MOBILESTUDIO_AAPT2_EXEC:-\$0}\"; " +
            "AAPT2_PROXY=\"\${MOBILESTUDIO_AAPT2_DAEMON_PROXY:-}\"; " +
            // LD_PRELOAD hygiene: the Gradle client merges the entire JDK lib set
            // into LD_PRELOAD, and that must NOT reach sh/linker64/dalvikvm —
            // it triggers bionic's DT_RPATH warning spam on every spawn and, in
            // ART processes, breaks native resolution (system_class_loader abort).
            // Keep only the exec-redirect hook so the Java helper can still
            // spawn aapt2 from the noexec app-data mount.
            "OLDIFS=\$IFS; IFS=:; NEW_PRELOAD=; " +
            "for _p in \$LD_PRELOAD; do " +
            "case \"\$_p\" in */libexec_redirect.so) NEW_PRELOAD=\"\$_p\";; esac; " +
            "done; IFS=\$OLDIFS; " +
            "LD_PRELOAD=\"\${MOBILESTUDIO_EXEC_REDIRECT:-\$NEW_PRELOAD}\"; " +
            "export LD_PRELOAD; " +
            // Native proxy first (linker64 + libaapt2_daemon_proxy.so), then the
            // Java helper through dalvikvm, then app_process as last resort.
            "if [ -n \"\$AAPT2_PROXY\" ] && [ -r \"\$AAPT2_PROXY\" ] && [ -x /system/bin/linker64 ]; then " +
            "exec /system/bin/linker64 \"\$AAPT2_PROXY\" \"\$AAPT2_EXEC\" \"\$@\"; " +
            "fi; " +
            "if [ -x /system/bin/dalvikvm ]; then " +
            "exec /system/bin/dalvikvm -cp \"\${MOBILESTUDIO_APK_PATH}\" " +
            "com.aide.mobilestudio.Aapt2DaemonProxy \"\$AAPT2_EXEC\" \"\$@\"; " +
            "fi; " +
            "APP_PROCESS=/system/bin/app_process64; " +
            "[ -x \"\$APP_PROCESS\" ] || APP_PROCESS=/system/bin/app_process; " +
            "export CLASSPATH=\"\${MOBILESTUDIO_APK_PATH}\"; " +
            "exec \"\$APP_PROCESS\" /system/bin --application --nice-name=mobilestudio_aapt2_proxy " +
            "com.aide.mobilestudio.Aapt2DaemonProxy \"\$AAPT2_EXEC\" \"\$@\""

    data class Result(
        val candidates: Int,
        val patched: Int,
        val alreadyPatched: Int,
        val failed: List<String>
    )

    /*
     * ----------------------------------------------------------------------
     * Android Gradle / AGP patches
     * ----------------------------------------------------------------------
     */

    fun patchAapt2DaemonStartup(gradleUserHome: File): Result {
        val builderCache = File(
            gradleUserHome,
            "caches/modules-2/files-2.1/com.android.tools.build/builder"
        )

        val builderJars = builderCache
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter {
                it.isFile &&
                    it.name.matches(
                        Regex("builder-[0-9].*\\.jar")
                    )
            }
            ?.filterNot {
                it.name.contains("sources") ||
                    it.name.contains("javadoc")
            }
            ?.toList()
            .orEmpty()

        val gradlePluginCache = File(
            gradleUserHome,
            "caches/modules-2/files-2.1/com.android.tools.build/gradle"
        )

        val gradlePluginJars = gradlePluginCache
            .takeIf { it.isDirectory }
            ?.walkTopDown()
            ?.filter {
                it.isFile &&
                    it.name.matches(
                        Regex("gradle-[0-9].*\\.jar")
                    )
            }
            ?.filterNot {
                it.name.contains("sources") ||
                    it.name.contains("javadoc")
            }
            ?.toList()
            .orEmpty()

        val jars = builderJars + gradlePluginJars

        var patched = 0
        var alreadyPatched = 0
        val failed = mutableListOf<String>()

        builderJars.forEach { jar ->
            runCatching {
                when (patchBuilderJar(jar)) {
                    PatchState.PATCHED -> patched++
                    PatchState.ALREADY_PATCHED -> alreadyPatched++
                    PatchState.NOT_APPLICABLE -> Unit
                }
            }.onFailure {
                failed +=
                    "${jar.name}: ${it.message ?: it::class.java.simpleName}"
            }
        }

        gradlePluginJars.forEach { jar ->
            runCatching {
                when (patchGradlePluginJar(jar)) {
                    PatchState.PATCHED -> patched++
                    PatchState.ALREADY_PATCHED -> alreadyPatched++
                    PatchState.NOT_APPLICABLE -> Unit
                }
            }.onFailure {
                failed +=
                    "${jar.name}: ${it.message ?: it::class.java.simpleName}"
            }
        }

        return Result(
            candidates = jars.size,
            patched = patched,
            alreadyPatched = alreadyPatched,
            failed = failed
        )
    }

    private fun patchBuilderJar(jarFile: File): PatchState {
        val marker = File(
            jarFile.parentFile,
            jarFile.name + PATCH_MARKER_SUFFIX
        )

        if (
            marker.exists() &&
            marker.lastModified() >= jarFile.lastModified()
        ) {
            return PatchState.ALREADY_PATCHED
        }

        val tmp = File(
            jarFile.parentFile,
            jarFile.name + ".mobilestudio.tmp"
        )

        var classFound = false
        var classPatched = false
        var classAlreadyPatched = false

        try {
            ZipFile(jarFile).use { zip ->
                ZipOutputStream(
                    tmp.outputStream().buffered()
                ).use { out ->

                    val entries = zip.entries()

                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()

                        val next = ZipEntry(entry.name).apply {
                            time = entry.time
                        }

                        out.putNextEntry(next)

                        if (!entry.isDirectory) {
                            val bytes =
                                zip.getInputStream(entry).use {
                                    it.readBytes()
                                }

                            val output =
                                if (entry.name == AAPT2_DAEMON_CLASS) {
                                    classFound = true

                                    val result =
                                        patchAapt2DaemonClass(bytes)

                                    classPatched = result.patched
                                    classAlreadyPatched =
                                        result.alreadyPatched

                                    result.bytes
                                } else {
                                    bytes
                                }

                            out.write(output)
                        }

                        out.closeEntry()
                    }
                }
            }

            if (!classFound) {
                tmp.delete()
                return PatchState.NOT_APPLICABLE
            }

            return if (classPatched) {
                jarFile.setWritable(true, true)

                tmp.copyTo(
                    jarFile,
                    overwrite = true
                )

                tmp.delete()

                marker.writeText("patched\n")

                PatchState.PATCHED
            } else {
                tmp.delete()

                if (classAlreadyPatched) {
                    marker.writeText("patched\n")
                    PatchState.ALREADY_PATCHED
                } else {
                    error(
                        "Aapt2DaemonImpl constructor pattern not found"
                    )
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun patchGradlePluginJar(
        jarFile: File
    ): PatchState {
        val marker = File(
            jarFile.parentFile,
            jarFile.name + PATCH_MARKER_SUFFIX
        )

        if (
            marker.exists() &&
            marker.lastModified() >= jarFile.lastModified()
        ) {
            return PatchState.ALREADY_PATCHED
        }

        val tmp = File(
            jarFile.parentFile,
            jarFile.name + ".mobilestudio.tmp"
        )

        var classFound = false
        var classPatched = false
        var classAlreadyPatched = false

        try {
            ZipFile(jarFile).use { zip ->
                ZipOutputStream(
                    tmp.outputStream().buffered()
                ).use { out ->

                    val entries = zip.entries()

                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()

                        val next = ZipEntry(entry.name).apply {
                            time = entry.time
                        }

                        out.putNextEntry(next)

                        if (!entry.isDirectory) {
                            val bytes =
                                zip.getInputStream(entry).use {
                                    it.readBytes()
                                }

                            val output =
                                if (entry.name == GRADLE_EXEC_ACTION_CLASS) {
                                    classFound = true

                                    val result =
                                        patchGradleExecActionClass(bytes)

                                    classPatched = result.patched
                                    classAlreadyPatched =
                                        result.alreadyPatched

                                    result.bytes
                                } else {
                                    bytes
                                }

                            out.write(output)
                        }

                        out.closeEntry()
                    }
                }
            }

            if (!classFound) {
                tmp.delete()
                return PatchState.NOT_APPLICABLE
            }

            return if (classPatched) {
                jarFile.setWritable(true, true)

                tmp.copyTo(
                    jarFile,
                    overwrite = true
                )

                tmp.delete()

                marker.writeText("patched\n")

                PatchState.PATCHED
            } else {
                tmp.delete()

                if (classAlreadyPatched) {
                    marker.writeText("patched\n")
                    PatchState.ALREADY_PATCHED
                } else {
                    error(
                        "GradleProcessExecutor ExecAction pattern not found"
                    )
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private fun patchAapt2DaemonClass(
        bytes: ByteArray
    ): ClassPatch {
        val reader = ClassReader(bytes)

        val writer = ClassWriter(
            reader,
            ClassWriter.COMPUTE_MAXS
        )

        var patched = false
        var alreadyPatched = false

        val visitor = object : ClassVisitor(
            Opcodes.ASM9,
            writer
        ) {
            private var ownerName = ""

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                ownerName = name

                super.visit(
                    version,
                    access,
                    name,
                    signature,
                    superName,
                    interfaces
                )
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val method =
                    super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                    )

                val targetDescriptor =
                    "(Ljava/lang/String;" +
                        "Ljava/nio/file/Path;" +
                        "Lcom/android/builder/internal/aapt/v2/Aapt2DaemonTimeouts;" +
                        "Lcom/android/utils/ILogger;)V"

                if (
                    name == "<init>" &&
                    descriptor == targetDescriptor
                ) {
                    return ShellAapt2ConstructorVisitor(
                        method,
                        ownerName
                    ) {
                        patched = true
                        alreadyPatched =
                            alreadyPatched || it
                    }
                }

                return method
            }
        }

        reader.accept(visitor, 0)

        return ClassPatch(
            patched = patched,
            alreadyPatched = alreadyPatched,
            bytes = writer.toByteArray()
        )
    }

    private fun patchGradleExecActionClass(
        bytes: ByteArray
    ): ClassPatch {
        val reader = ClassReader(bytes)

        val writer = SafeClassWriter(
            reader,
            ClassWriter.COMPUTE_FRAMES or
                ClassWriter.COMPUTE_MAXS
        )

        var patched = false
        var alreadyPatched = false

        val visitor = object : ClassVisitor(
            Opcodes.ASM9,
            writer
        ) {
            private var ownerName = ""

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                ownerName = name

                super.visit(
                    version,
                    access,
                    name,
                    signature,
                    superName,
                    interfaces
                )
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor {
                val method =
                    super.visitMethod(
                        access,
                        name,
                        descriptor,
                        signature,
                        exceptions
                    )

                if (
                    name == "execute" &&
                    descriptor ==
                        "(Lorg/gradle/process/ExecSpec;)V"
                ) {
                    return JdkToolExecActionVisitor(
                        method,
                        ownerName
                    ) {
                        patched = true
                        alreadyPatched =
                            alreadyPatched || it
                    }
                }

                return method
            }
        }

        reader.accept(visitor, 0)

        return ClassPatch(
            patched = patched,
            alreadyPatched = alreadyPatched,
            bytes = writer.toByteArray()
        )
    }

    /*
     * native-platform launcher integrity.
     *
     * Older releases rewrote DefaultProcessLauncher.start() with ASM
     * (ClassWriter.COMPUTE_FRAMES).  The regenerated StackMapTable was
     * invalid and the Gradle daemon died during class verification with:
     *
     *   StackMapTable error: bad offset
     *   Invalid stackmap specification. chop_frame(@65536,2)
     *
     * The class must stay byte-for-byte pristine.  The noexec problem that
     * the rewrite used to work around is handled by libexec_redirect.so
     * (execveat -> fexecve -> linker64 -> /proc/self/fd), so nothing in
     * here writes to the jar any more: these helpers only inspect it and
     * report the SHA-256 of the class.
     */

    data class NativeLauncherStatus(
        val jar: File,
        val sha256: String?,
        val present: Boolean,
        val readable: Boolean,
        val patched: Boolean
    )

    fun inspectNativePlatformJars(gradleLibDir: File): List<NativeLauncherStatus> {
        if (!gradleLibDir.isDirectory) return emptyList()
        val jars = gradleLibDir
            .listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.filter { it.name.matches(Regex("native-platform-[0-9].*\\.jar")) }
            ?.filterNot { it.name.contains("-sources") || it.name.contains("-javadoc") }
            ?.toList()
            .orEmpty()
        return jars.map { inspectNativePlatformJar(it) }
    }

    fun inspectNativePlatformJar(jar: File): NativeLauncherStatus {
        val bytes = readNativeLauncherClass(jar)
            ?: return NativeLauncherStatus(
                jar = jar,
                sha256 = null,
                present = false,
                readable = false,
                patched = false
            )

        val sha = sha256Hex(bytes)
        val readable = runCatching { ClassReader(bytes) }.isSuccess
        val patched = containsAscii(bytes, "/system/bin/sh")

        return NativeLauncherStatus(
            jar = jar,
            sha256 = sha,
            present = true,
            readable = readable,
            patched = patched
        )
    }

    private fun readNativeLauncherClass(jar: File): ByteArray? = try {
        ZipFile(jar).use { zip ->
            val entry = zip.getEntry(NATIVE_LAUNCHER_CLASS) ?: return null
            zip.getInputStream(entry).use { it.readBytes() }
        }
    } catch (t: Throwable) {
        null
    }

    /** Removes stale markers left behind by the removed ASM patcher. */
    fun deleteNativeLauncherMarkers(gradleLibDir: File): Int {
        if (!gradleLibDir.isDirectory) return 0
        var removed = 0
        gradleLibDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.contains("mobilestudio-native-platform-launcher")) {
                if (f.delete()) removed++
            }
        }
        return removed
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        if (n.isEmpty() || haystack.size < n.size) return false
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) {
                if (haystack[i + j] != n[j]) continue@outer
            }
            return true
        }
        return false
    }

    /*
     * ----------------------------------------------------------------------
     * ASM helpers
     * ----------------------------------------------------------------------
     */

    private class SafeClassWriter(
        reader: ClassReader,
        flags: Int
    ) : ClassWriter(
        reader,
        flags
    ) {
        override fun getCommonSuperClass(
            type1: String,
            type2: String
        ): String {
            return "java/lang/Object"
        }
    }

    /*
     * ----------------------------------------------------------------------
     * Gradle JDK / CMake / native tool ExecAction
     * ----------------------------------------------------------------------
     */

    private class JdkToolExecActionVisitor(
        private val delegate: MethodVisitor,
        private val ownerName: String,
        private val onEmit: (Boolean) -> Unit
    ) : MethodVisitor(
        Opcodes.ASM9
    ) {
        private var emitted = false
        private var sawMarker = false

        override fun visitLdcInsn(
            value: Any?
        ) {
            if (value == EXEC_PATCH_MARKER) {
                sawMarker = true
            }
        }

        override fun visitCode() {
            if (emitted) {
                return
            }

            emitted = true
            emitReplacement()
        }

        override fun visitEnd() {
            if (!emitted) {
                onEmit(sawMarker)
            }

            delegate.visitEnd()
        }

        private fun emitReplacement() {
            onEmit(false)

            delegate.visitCode()

            val loopStart = Label()
            val loopEnd = Label()
            val useOriginalArg = Label()
            val addArg = Label()

            val directExec = Label()
            val afterExec = Label()
            val noWorkingDir = Label()

            val proxyJlink = Label()
            val proxyJmod = Label()
            val proxyJavac = Label()

            val doProxy = Label()

            val proxyCmake = Label()
            val proxyNinja = Label()
            val proxyCtest = Label()
            val proxyCpack = Label()

            val doNativeProxy = Label()

            /*
             * ArrayList normalizedArgs =
             *     new ArrayList(processInfo.getArgs().size());
             */
            delegate.visitTypeInsn(
                Opcodes.NEW,
                "java/util/ArrayList"
            )

            delegate.visitInsn(
                Opcodes.DUP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                0
            )

            delegate.visitFieldInsn(
                Opcodes.GETFIELD,
                ownerName,
                "processInfo",
                "Lcom/android/ide/common/process/ProcessInfo;"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/android/ide/common/process/ProcessInfo",
                "getArgs",
                "()Ljava/util/List;",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "size",
                "()I",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "(I)V",
                false
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                2
            )

            /*
             * Normalize empty arguments to "".
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                0
            )

            delegate.visitFieldInsn(
                Opcodes.GETFIELD,
                ownerName,
                "processInfo",
                "Lcom/android/ide/common/process/ProcessInfo;"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/android/ide/common/process/ProcessInfo",
                "getArgs",
                "()Ljava/util/List;",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "iterator",
                "()Ljava/util/Iterator;",
                true
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                3
            )

            delegate.visitLabel(
                loopStart
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "hasNext",
                "()Z",
                true
            )

            delegate.visitJumpInsn(
                Opcodes.IFEQ,
                loopEnd
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/Iterator",
                "next",
                "()Ljava/lang/Object;",
                true
            )

            delegate.visitTypeInsn(
                Opcodes.CHECKCAST,
                "java/lang/String"
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                4
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                2
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                4
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "isEmpty",
                "()Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFEQ,
                useOriginalArg
            )

            delegate.visitLdcInsn(
                "\"\""
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                addArg
            )

            delegate.visitLabel(
                useOriginalArg
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                4
            )

            delegate.visitLabel(
                addArg
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "add",
                "(Ljava/lang/Object;)Z",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                loopStart
            )

            delegate.visitLabel(
                loopEnd
            )

            /*
             * executable = processInfo.getExecutable()
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                0
            )

            delegate.visitFieldInsn(
                Opcodes.GETFIELD,
                ownerName,
                "processInfo",
                "Lcom/android/ide/common/process/ProcessInfo;"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/android/ide/common/process/ProcessInfo",
                "getExecutable",
                "()Ljava/lang/String;",
                true
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                3
            )

            /*
             * jdkProxy = getenv(EXEC_PATCH_MARKER)
             */
            delegate.visitLdcInsn(
                EXEC_PATCH_MARKER
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "getenv",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                5
            )

            /*
             * nativeToolShell =
             *     getenv("MOBILESTUDIO_NATIVE_TOOL_SHELL")
             */
            delegate.visitLdcInsn(
                "MOBILESTUDIO_NATIVE_TOOL_SHELL"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "getenv",
                "(Ljava/lang/String;)Ljava/lang/String;",
                false
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                8
            )

            /*
             * Only use the JDK proxy for executable paths that
             * are actually JDK/native tool candidates.
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                5
            )

            delegate.visitJumpInsn(
                Opcodes.IFNULL,
                directExec
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                5
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "isEmpty",
                "()Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                directExec
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitJumpInsn(
                Opcodes.IFNULL,
                directExec
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitLdcInsn(
                "/bin/"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "contains",
                "(Ljava/lang/CharSequence;)Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFEQ,
                directExec
            )

            /*
             * /jlink
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitLdcInsn(
                "/jlink"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "endsWith",
                "(Ljava/lang/String;)Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                proxyJlink
            )

            /*
             * /jmod
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitLdcInsn(
                "/jmod"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "endsWith",
                "(Ljava/lang/String;)Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                proxyJmod
            )

            /*
             * /javac
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitLdcInsn(
                "/javac"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "endsWith",
                "(Ljava/lang/String;)Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                proxyJavac
            )

            /*
             * /cmake
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitLdcInsn(
                "/cmake"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "endsWith",
                "(Ljava/lang/String;)Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                proxyCmake
            )

            /*
             * /ninja
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitLdcInsn(
                "/ninja"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "endsWith",
                "(Ljava/lang/String;)Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                proxyNinja
            )

            /*
             * /ctest
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitLdcInsn(
                "/ctest"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "endsWith",
                "(Ljava/lang/String;)Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                proxyCtest
            )

            /*
             * /cpack
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitLdcInsn(
                "/cpack"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "endsWith",
                "(Ljava/lang/String;)Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                proxyCpack
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                directExec
            )

            delegate.visitLabel(
                proxyJlink
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                doProxy
            )

            delegate.visitLabel(
                proxyJmod
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                doProxy
            )

            delegate.visitLabel(
                proxyJavac
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                doProxy
            )

            delegate.visitLabel(
                proxyCmake
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                doNativeProxy
            )

            delegate.visitLabel(
                proxyNinja
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                doNativeProxy
            )

            delegate.visitLabel(
                proxyCtest
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                doNativeProxy
            )

            delegate.visitLabel(
                proxyCpack
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                doNativeProxy
            )

            /*
             * Native build tool shell proxy.
             */
            delegate.visitLabel(
                doNativeProxy
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                8
            )

            delegate.visitJumpInsn(
                Opcodes.IFNULL,
                directExec
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                8
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "isEmpty",
                "()Z",
                false
            )

            delegate.visitJumpInsn(
                Opcodes.IFNE,
                directExec
            )

            delegate.visitTypeInsn(
                Opcodes.NEW,
                "java/util/ArrayList"
            )

            delegate.visitInsn(
                Opcodes.DUP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                2
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "size",
                "()I",
                true
            )

            delegate.visitInsn(
                Opcodes.ICONST_2
            )

            delegate.visitInsn(
                Opcodes.IADD
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "(I)V",
                false
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                6
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                6
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                8
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "add",
                "(Ljava/lang/Object;)Z",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                6
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "add",
                "(Ljava/lang/Object;)Z",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                6
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                2
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "addAll",
                "(Ljava/util/Collection;)Z",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitLdcInsn(
                "/system/bin/sh"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "setExecutable",
                "(Ljava/lang/String;)V",
                true
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                6
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "args",
                "(Ljava/lang/Iterable;)" +
                    "Lorg/gradle/process/ExecSpec;",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                afterExec
            )

            /*
             * JDK proxy.
             */
            delegate.visitLabel(
                doProxy
            )

            delegate.visitTypeInsn(
                Opcodes.NEW,
                "java/util/ArrayList"
            )

            delegate.visitInsn(
                Opcodes.DUP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                2
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "size",
                "()I",
                true
            )

            delegate.visitInsn(
                Opcodes.ICONST_2
            )

            delegate.visitInsn(
                Opcodes.IADD
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                "java/util/ArrayList",
                "<init>",
                "(I)V",
                false
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                6
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                6
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                5
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "add",
                "(Ljava/lang/Object;)Z",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                6
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "add",
                "(Ljava/lang/Object;)Z",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                6
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                2
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/util/List",
                "addAll",
                "(Ljava/util/Collection;)Z",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitLdcInsn(
                "/system/bin/linker64"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "setExecutable",
                "(Ljava/lang/String;)V",
                true
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                6
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "args",
                "(Ljava/lang/Iterable;)" +
                    "Lorg/gradle/process/ExecSpec;",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitJumpInsn(
                Opcodes.GOTO,
                afterExec
            )

            /*
             * Direct execution.
             */
            delegate.visitLabel(
                directExec
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "setExecutable",
                "(Ljava/lang/String;)V",
                true
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                2
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "args",
                "(Ljava/lang/Iterable;)" +
                    "Lorg/gradle/process/ExecSpec;",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitLabel(
                afterExec
            )

            /*
             * Environment.
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                0
            )

            delegate.visitFieldInsn(
                Opcodes.GETFIELD,
                ownerName,
                "processInfo",
                "Lcom/android/ide/common/process/ProcessInfo;"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/android/ide/common/process/ProcessInfo",
                "getEnvironment",
                "()Ljava/util/Map;",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "environment",
                "(Ljava/util/Map;)" +
                    "Lorg/gradle/process/ProcessForkOptions;",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            /*
             * stdout.
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                0
            )

            delegate.visitFieldInsn(
                Opcodes.GETFIELD,
                ownerName,
                "processOutput",
                "Lcom/android/ide/common/process/ProcessOutput;"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/android/ide/common/process/ProcessOutput",
                "getStandardOutput",
                "()Ljava/io/OutputStream;",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "setStandardOutput",
                "(Ljava/io/OutputStream;)" +
                    "Lorg/gradle/process/BaseExecSpec;",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            /*
             * stderr.
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                0
            )

            delegate.visitFieldInsn(
                Opcodes.GETFIELD,
                ownerName,
                "processOutput",
                "Lcom/android/ide/common/process/ProcessOutput;"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/android/ide/common/process/ProcessOutput",
                "getErrorOutput",
                "()Ljava/io/OutputStream;",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "setErrorOutput",
                "(Ljava/io/OutputStream;)" +
                    "Lorg/gradle/process/BaseExecSpec;",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            /*
             * Working directory.
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                0
            )

            delegate.visitFieldInsn(
                Opcodes.GETFIELD,
                ownerName,
                "processInfo",
                "Lcom/android/ide/common/process/ProcessInfo;"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "com/android/ide/common/process/ProcessInfo",
                "getWorkingDirectory",
                "()Ljava/io/File;",
                true
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                7
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                7
            )

            delegate.visitJumpInsn(
                Opcodes.IFNULL,
                noWorkingDir
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                7
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "setWorkingDir",
                "(Ljava/io/File;)V",
                true
            )

            delegate.visitLabel(
                noWorkingDir
            )

            /*
             * The Gradle process action handles the actual exit status.
             * Preserve the original behavior of the existing patch.
             */
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            delegate.visitInsn(
                Opcodes.ICONST_1
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "org/gradle/process/ExecSpec",
                "setIgnoreExitValue",
                "(Z)Lorg/gradle/process/BaseExecSpec;",
                true
            )

            delegate.visitInsn(
                Opcodes.POP
            )

            delegate.visitInsn(
                Opcodes.RETURN
            )

            delegate.visitMaxs(
                0,
                0
            )
        }
    }

    /*
     * ----------------------------------------------------------------------
     * AAPT2 constructor patch
     * ----------------------------------------------------------------------
     */

    private class ShellAapt2ConstructorVisitor(
        private val delegate: MethodVisitor,
        private val ownerName: String,
        private val onEmit: (Boolean) -> Unit
    ) : MethodVisitor(
        Opcodes.ASM9
    ) {
        private var emitted = false
        private var sawShellConstant = false

        override fun visitLdcInsn(
            value: Any?
        ) {
            if (value == AAPT2_SHELL_BOOTSTRAP) {
                sawShellConstant = true
            }
        }

        override fun visitCode() {
            if (emitted) {
                return
            }

            emitted = true
            emitReplacement()
        }

        override fun visitEnd() {
            if (!emitted) {
                onEmit(sawShellConstant)
            }

            delegate.visitEnd()
        }

        private fun emitReplacement() {
            onEmit(false)

            delegate.visitCode()

            checkNotNullParameter(
                1,
                "displayId"
            )

            checkNotNullParameter(
                2,
                "aaptExecutable"
            )

            checkNotNullParameter(
                3,
                "daemonTimeouts"
            )

            checkNotNullParameter(
                4,
                "logger"
            )

            /*
             * this.<init>(
             *     displayId,
             *     aaptPath,
             *     listOf("/system/bin/sh", "-c",
             *         AAPT2_SHELL_BOOTSTRAP,
             *         aaptPath,
             *         "m"),
             *     aaptParentName,
             *     daemonTimeouts,
             *     logger
             * )
             */

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                0
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                1
            )

            loadAaptPath()

            delegate.visitInsn(
                Opcodes.DUP
            )

            delegate.visitLdcInsn(
                "getAbsolutePath(...)"
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlin/jvm/internal/Intrinsics",
                "checkNotNullExpressionValue",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false
            )

            delegate.visitInsn(
                Opcodes.ICONST_5
            )

            delegate.visitTypeInsn(
                Opcodes.ANEWARRAY,
                "java/lang/String"
            )

            delegate.visitVarInsn(
                Opcodes.ASTORE,
                5
            )

            storeArrayString(
                0,
                "/system/bin/sh"
            )

            storeArrayString(
                1,
                "-c"
            )

            storeArrayString(
                2,
                AAPT2_SHELL_BOOTSTRAP
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                5
            )

            delegate.visitInsn(
                Opcodes.ICONST_3
            )

            loadAaptPath()

            delegate.visitInsn(
                Opcodes.AASTORE
            )

            storeArrayString(
                4,
                "m"
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                5
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlin/collections/CollectionsKt",
                "listOf",
                "([Ljava/lang/Object;)Ljava/util/List;",
                false
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                2
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/nio/file/Path",
                "getParent",
                "()Ljava/nio/file/Path;",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/nio/file/Path",
                "getFileName",
                "()Ljava/nio/file/Path;",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/Object",
                "toString",
                "()Ljava/lang/String;",
                false
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                3
            )

            delegate.visitVarInsn(
                Opcodes.ALOAD,
                4
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                ownerName,
                "<init>",
                "(Ljava/lang/String;" +
                    "Ljava/lang/String;" +
                    "Ljava/util/List;" +
                    "Ljava/lang/String;" +
                    "Lcom/android/builder/internal/aapt/v2/Aapt2DaemonTimeouts;" +
                    "Lcom/android/utils/ILogger;)V",
                false
            )

            delegate.visitInsn(
                Opcodes.RETURN
            )

            delegate.visitMaxs(
                0,
                0
            )
        }

        private fun checkNotNullParameter(
            localIndex: Int,
            name: String
        ) {
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                localIndex
            )

            delegate.visitLdcInsn(
                name
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlin/jvm/internal/Intrinsics",
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false
            )
        }

        private fun loadAaptPath() {
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                2
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEINTERFACE,
                "java/nio/file/Path",
                "toFile",
                "()Ljava/io/File;",
                true
            )

            delegate.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/io/File",
                "getAbsolutePath",
                "()Ljava/lang/String;",
                false
            )
        }

        private fun storeArrayString(
            index: Int,
            value: String
        ) {
            delegate.visitVarInsn(
                Opcodes.ALOAD,
                5
            )

            when (index) {
                0 -> delegate.visitInsn(
                    Opcodes.ICONST_0
                )

                1 -> delegate.visitInsn(
                    Opcodes.ICONST_1
                )

                2 -> delegate.visitInsn(
                    Opcodes.ICONST_2
                )

                3 -> delegate.visitInsn(
                    Opcodes.ICONST_3
                )

                4 -> delegate.visitInsn(
                    Opcodes.ICONST_4
                )

                else -> delegate.visitIntInsn(
                    Opcodes.BIPUSH,
                    index
                )
            }

            delegate.visitLdcInsn(
                value
            )

            delegate.visitInsn(
                Opcodes.AASTORE
            )
        }
    }

    /*
     * ----------------------------------------------------------------------
     * Data types
     * ----------------------------------------------------------------------
     */

    private data class ClassPatch(
        val patched: Boolean,
        val alreadyPatched: Boolean,
        val bytes: ByteArray
    )

    private enum class PatchState {
        PATCHED,
        ALREADY_PATCHED,
        NOT_APPLICABLE
    }
}