package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * native_tool_shim.sh 동작 검증 (호스트 sh + stub linker64).
 *
 * AGP ExecAction 이 실행하는 shim 스크립트가
 *   1. SDK cmake/ninja 경로를 포팅 arm64 바이너리로 치환하는지
 *   2. -DCMAKE_MAKE_PROGRAM 인자를 포팅 ninja 로 재작성하는지
 *   3. 포트가 없을 때 원본 경로를 유지하는지
 *   4. SDK 경로가 아닌 도구는 그대로 통과시키는지
 * 확인한다. MOBILESTUDIO_LINKER64 env 로 linker64 를 stub 으로 교체한다.
 */
class NativeToolShimScriptTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var linker64Stub: File
    private lateinit var shimCopy: File

    private val portedCmake: File get() = File(root, "toolchain/cmake/cmake-3.22/bin/cmake")
    private val portedNinja: File get() = File(root, "toolchain/arm64-tools/cmake-arm64/bin/ninja")
    private val sdkCmake: File get() = File(root, "toolchain/sdk/android-34/cmake/3.22.1/bin/cmake")
    private val sdkNinja: File get() = File(root, "toolchain/sdk/android-34/cmake/3.22.1/bin/ninja")

    /** AGP 로그와 동일한 SDK cmake 경로. */
    private val sdkCmakePath: String get() = sdkCmake.absolutePath
    private val sdkNinjaPath: String get() = sdkNinja.absolutePath

    private fun setUpTree(withPorts: Boolean) {
        root = tmp.newFolder("tc")
        writeStub(sdkCmake, "DIRECT")
        if (withPorts) {
            writeStub(portedCmake)
            writeStub(portedNinja)
            File(root, "toolchain/cmake/cmake-3.22/lib").mkdirs()
            File(root, "toolchain/cmake/cmake-3.22/share/cmake-3.22").mkdirs()
        }
        linker64Stub = File(tmp.newFolder("bin"), "linker64")
        linker64Stub.writeText(
            "#!/bin/sh\n" +
                "bin=\"\$1\"\n" +
                "shift\n" +
                "echo \"BIN:\$bin\"\n" +
                "for a in \"\$@\"; do echo \"ARG:\$a\"; done\n" +
                "echo \"LDLP:\${LD_LIBRARY_PATH:-}\"\n" +
                "echo \"CROOT:\${CMAKE_ROOT:-}\"\n"
        )
        linker64Stub.setExecutable(true)
        shimCopy = File(tmp.newFolder("shim"), "native_tool_shim.sh")
        locateShimAsset().copyTo(shimCopy)
    }

    private fun locateShimAsset(): File {
        val rel = "src/main/assets/native_tool_shim.sh"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf(File(dir, rel), File(dir, "app/$rel")).firstOrNull { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        throw IllegalStateException("native_tool_shim.sh asset not found from ${System.getProperty("user.dir")}")
    }

    private fun writeStub(file: File, tag: String = "DIRECT") {
        file.parentFile?.mkdirs()
        file.writeText(
            "#!/bin/sh\n" +
                "echo \"$tag:\$0\"\n" +
                "for a in \"\$@\"; do echo \"ARG:\$a\"; done\n"
        )
        file.setExecutable(true)
    }

    private fun runShim(vararg args: String, portedCmakeEnv: String? = null, portedNinjaEnv: String? = null): Pair<Int, List<String>> {
        val pb = ProcessBuilder("sh", shimCopy.absolutePath, *args)
            .redirectErrorStream(true)
        val env = pb.environment()
        env.remove("MOBILESTUDIO_PORTED_CMAKE")
        env.remove("MOBILESTUDIO_PORTED_NINJA")
        env["MOBILESTUDIO_LINKER64"] = linker64Stub.absolutePath
        portedCmakeEnv?.let { env["MOBILESTUDIO_PORTED_CMAKE"] = it }
        portedNinjaEnv?.let { env["MOBILESTUDIO_PORTED_NINJA"] = it }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readLines()
        val exit = p.waitFor()
        return exit to out
    }

    private fun linesByPrefix(out: List<String>, prefix: String): List<String> =
        out.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }

    @Test
    fun cmakeRedirectedToPortedBinaryViaEnv() {
        setUpTree(withPorts = true)
        val args = arrayOf("-S", "${root.absolutePath}/proj", "-B", "${root.absolutePath}/out", "-DCMAKE_MAKE_PROGRAM=$sdkNinjaPath")
        val (exit, out) = runShim(sdkCmakePath, *args, portedCmakeEnv = portedCmake.absolutePath, portedNinjaEnv = portedNinja.absolutePath)

        assertEquals(0, exit)
        assertEquals("redirected binary must be the ported cmake", portedCmake.absolutePath, linesByPrefix(out, "BIN:").single())
        assertEquals(
            "original args must be preserved with order kept, with the ported ninja substituted",
            listOf("-S", "${root.absolutePath}/proj", "-B", "${root.absolutePath}/out", "-DCMAKE_MAKE_PROGRAM=${portedNinja.absolutePath}"),
            linesByPrefix(out, "ARG:")
        )
        assertTrue(
            "ported cmake lib dir must come first in LD_LIBRARY_PATH",
            linesByPrefix(out, "LDLP:").single().startsWith(File(root, "toolchain/cmake/cmake-3.22/lib").absolutePath)
        )
        assertEquals(
            "CMAKE_ROOT must point at the ported share dir",
            File(root, "toolchain/cmake/cmake-3.22/share/cmake-3.22").absolutePath,
            linesByPrefix(out, "CROOT:").single()
        )
        assertTrue(out.any { it.contains("native tool redirect") })
    }

    @Test
    fun cmakeRedirectedViaFallbackDiscoveryWithoutEnv() {
        setUpTree(withPorts = true)
        val (exit, out) = runShim(sdkCmakePath, "-S", "proj")

        assertEquals(0, exit)
        assertEquals(portedCmake.absolutePath, linesByPrefix(out, "BIN:").single())
        assertEquals(listOf("-S", "proj"), linesByPrefix(out, "ARG:"))
    }

    @Test
    fun ninjaRedirectedViaFallbackDiscovery() {
        setUpTree(withPorts = true)
        val (exit, out) = runShim(sdkNinjaPath, "-C", "build")

        assertEquals(0, exit)
        assertEquals(portedNinja.absolutePath, linesByPrefix(out, "BIN:").single())
        assertEquals(listOf("-C", "build"), linesByPrefix(out, "ARG:"))
    }

    @Test
    fun ninjaRedirectedViaStudioShellToolsLayout() {
        // The app's known-good arm64 ninja lives at <filesDir>/tools/ninja/bin/
        // ninja (StudioShellTools). The shim must find it and prefer it over the
        // SDK's LINUX x86-64 ninja that Android cannot exec.
        setUpTree(withPorts = false)
        val studioNinja = File(root, "tools/ninja/bin/ninja")
        writeStub(studioNinja)
        val (exit, out) = runShim(sdkNinjaPath, "-C", "build")

        assertEquals(0, exit)
        assertEquals(studioNinja.absolutePath, linesByPrefix(out, "BIN:").single())
        assertEquals(listOf("-C", "build"), linesByPrefix(out, "ARG:"))
    }

    @Test
    fun ninjaRedirectedViaCmakeRuntimeManagerLayout() {
        // Regression: after an app reinstall the port lives at
        // <toolchain>/cmake/<version>/bin/ninja (CmakeRuntimeManager), not under
        // arm64-tools; the shim must still find it (otherwise the SDK's non-PIE
        // ninja is exec'd and linker64 fails with "unexpected e_type: 2").
        setUpTree(withPorts = false)
        val runtimeNinja = File(root, "toolchain/cmake/3.22.1/bin/ninja")
        writeStub(runtimeNinja)
        val (exit, out) = runShim(sdkNinjaPath, "-C", "build")

        assertEquals(0, exit)
        assertEquals(runtimeNinja.absolutePath, linesByPrefix(out, "BIN:").single())
        assertEquals(listOf("-C", "build"), linesByPrefix(out, "ARG:"))
    }

    @Test
    fun makeProgramArgRewrittenToFallbackNinja() {
        setUpTree(withPorts = true)
        val (exit, out) = runShim(sdkCmakePath, "-DCMAKE_MAKE_PROGRAM=$sdkNinjaPath", "-S", "proj")

        assertEquals(0, exit)
        assertEquals(portedCmake.absolutePath, linesByPrefix(out, "BIN:").single())
        assertEquals(
            listOf("-DCMAKE_MAKE_PROGRAM=${portedNinja.absolutePath}", "-S", "proj"),
            linesByPrefix(out, "ARG:")
        )
    }

    @Test
    fun missingPortKeepsOriginalPath() {
        setUpTree(withPorts = false)
        val (exit, out) = runShim(sdkCmakePath, "-S", "proj")

        assertEquals(0, exit)
        assertEquals("no port installed -> original SDK cmake must be exec'd unchanged", sdkCmakePath, linesByPrefix(out, "DIRECT:").single())
        assertEquals(listOf("-S", "proj"), linesByPrefix(out, "ARG:"))
    }

    @Test
    fun nonSdkToolPassesThroughUntouched() {
        setUpTree(withPorts = true)
        val plain = File(root, "plain/echo")
        writeStub(plain)
        val (exit, out) = runShim(plain.absolutePath, "hi")

        assertEquals(0, exit)
        assertEquals(plain.absolutePath, linesByPrefix(out, "DIRECT:").single())
        assertEquals(listOf("hi"), linesByPrefix(out, "ARG:"))
    }

    @Test
    fun appDataElfRoutedThroughLinker64() {
        setUpTree(withPorts = true)
        // App-data ELFs cannot be execve()d inside the app domain (SELinux
        // W^X -> EACCES); the passthrough branch must route them via linker64.
        val elf = File(root, "plain-tool/frobnicator")
        elf.parentFile?.mkdirs()
        elf.writeBytes(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 0, 0))
        val (exit, out) = runShim(elf.absolutePath, "hi")

        assertEquals(0, exit)
        assertEquals(
            "non-system ELF must run through linker64",
            elf.absolutePath,
            linesByPrefix(out, "BIN:").single()
        )
        assertEquals(listOf("hi"), linesByPrefix(out, "ARG:"))
    }

    @Test
    fun shebangScriptRoutedThroughShNotExecved() {
        setUpTree(withPorts = true)
        // Scripts keep working through `sh <script>` (execve() of an
        // app-data script is denied the same way as for ELFs). The stub
        // prints DIRECT:$0 where $0 is the script path.
        val script = File(root, "plain-tool/build-helper")
        writeStub(script, "SCRIPT")
        val (exit, out) = runShim(script.absolutePath, "go")

        assertEquals(0, exit)
        assertEquals(script.absolutePath, linesByPrefix(out, "SCRIPT:").single())
        assertEquals(listOf("go"), linesByPrefix(out, "ARG:"))
    }

    @Test
    fun emptyToolArgExits127() {
        setUpTree(withPorts = true)
        val (exit, out) = runShim()
        assertEquals(127, exit)
        assertTrue(out.isEmpty())
    }
}
