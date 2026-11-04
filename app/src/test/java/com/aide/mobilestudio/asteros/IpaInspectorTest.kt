package com.aide.mobilestudio.asteros

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * IPA 열기 및 실행 가능성 판정 (AsterOS Phase 2-A).
 *
 * fixture는 전부 이 테스트가 직접 만든다 (외부 IPA 미사용).
 * 다루는 경우: 정상 IPA / binary plist / x86_64 전용 / 손상된 ZIP /
 * ZIP 아님 / Payload 없음 / Info.plist 없음·손상 / zip-slip /
 * 실행 파일 없음 /过大 바이너리 생략 / 앱 폴더 복수.
 */
class IpaInspectorTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    // ------------------------------------------------------------- fixture --

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("ms-ipa").toFile().also { tempDirs += it }

    private fun ipa(name: String, entries: List<Pair<String, ByteArray>>): File {
        val file = File(tempDir(), name)
        ZipOutputStream(FileOutputStream(file)).use { z ->
            for ((entryName, bytes) in entries) {
                z.putNextEntry(ZipEntry(entryName))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return file
    }

    private fun u32le(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte(),
    )

    /** mach_header_64 하나 (커맨드 없음). */
    private fun machHeader(cputype: Long): ByteArray {
        val header = byteArrayOf(
            0xCF.toByte(), 0xFA.toByte(), 0xED.toByte(), 0xFE.toByte(),
        ) + u32le(cputype) + u32le(0) + u32le(2) + u32le(0) + u32le(0) +
            u32le(0x00200085.toLong()) + u32le(0)
        return header + ByteArray(64 - header.size)
    }

    private val arm64Binary = machHeader(0x0100000CL)
    private val x86_64Binary = machHeader(0x01000007L)

    private fun xmlInfoPlist(executable: String = "Demo"): ByteArray = """
        <?xml version="1.0" encoding="UTF-8"?>
        <plist version="1.0">
        <dict>
            <key>CFBundleIdentifier</key><string>com.example.demo</string>
            <key>CFBundleDisplayName</key><string>Demo App</string>
            <key>CFBundleExecutable</key><string>$executable</string>
            <key>CFBundleShortVersionString</key><string>1.2.3</string>
            <key>CFBundleVersion</key><string>45</string>
            <key>MinimumOSVersion</key><string>15.0</string>
            <key>UIDeviceFamily</key><array><integer>1</integer><integer>2</integer></array>
        </dict>
        </plist>
    """.trimIndent().toByteArray()

    private fun fullIpa(name: String, binary: ByteArray = arm64Binary, plist: ByteArray = xmlInfoPlist()): File =
        ipa(
            name,
            listOf(
                "Payload/Demo.app/Info.plist" to plist,
                "Payload/Demo.app/Demo" to binary,
                "Payload/Demo.app/_CodeSignature/CodeResources" to "<xml/>".toByteArray(),
                "Payload/Demo.app/embedded.mobileprovision" to ByteArray(32),
                "Payload/Demo.app/Frameworks/InHouseKit.framework/InHouseKit" to ByteArray(16),
                "Payload/Demo.app/PlugIns/ShareExt.appex/ShareExt" to ByteArray(16),
            ),
        )

    // --------------------------------------------------------------- tests --

    @Test
    fun 정상_IPA의_정보와_판정을_모은다() {
        val result = IpaInspector.inspect(fullIpa("ok.ipa").absolutePath)
        assertTrue("Success expected: $result", result is IpaResult.Success)
        result as IpaResult.Success

        val info = result.info
        assertEquals("Demo.app", info.appFolderName)
        assertEquals("com.example.demo", info.bundleId)
        assertEquals("Demo App", info.displayName)
        assertEquals("1.2.3", info.version)
        assertEquals("45", info.buildVersion)
        assertEquals("15.0", info.minimumOsVersion)
        assertEquals("Demo", info.executableName)
        assertEquals(listOf("InHouseKit"), info.frameworks)
        assertEquals(listOf("ShareExt"), info.appExtensions)
        assertTrue(info.hasCodeSignature)
        assertTrue(info.hasMobileProvision)
        assertEquals(listOf(1, 2), info.deviceFamilies)

        val binary = result.mainBinary
        assertTrue("Mach-O Success expected: $binary", binary is MachOResult.Success)
        assertEquals("arm64", (binary as MachOResult.Success).cputype)

        val verdict = result.verdict
        assertFalse("실행 엔진이 없으므로 항상 false", verdict.runnable)
        assertEquals(true, verdict.cpuCompatible)
        assertTrue(verdict.reasons.any { it.contains("실행 엔진") })
        assertTrue(verdict.reasons.any { it.contains("libSystem") || it.contains("UIKit") })
        assertTrue(verdict.notes.any { it.contains("iOS 15.0") })
        assertTrue(verdict.notes.any { it.contains("arm64") })
    }

    @Test
    fun binary_plist_IPA도_읽는다() {
        // binary plist: dict{CFBundleExecutable:Demo, CFBundleIdentifier:...}
        val header = "bplist00".toByteArray(Charsets.US_ASCII)
        fun bpAscii(s: String): ByteArray {
            val b = s.toByteArray(Charsets.US_ASCII)
            return if (b.size < 15) byteArrayOf((0x50 or b.size).toByte()) + b
            else byteArrayOf(0x5F, 0x10, b.size.toByte()) + b
        }
        val objects = listOf(
            //0=dict: 키[1,3] 값[2,4]
            byteArrayOf(0xA2.toByte(), 1, 3, 2, 4),
            bpAscii("CFBundleExecutable"),
            bpAscii("Demo"),
            bpAscii("CFBundleIdentifier"),
            bpAscii("com.example.binary"),
        )
        var offset = header.size
        val offsets = mutableListOf<Int>()
        val body = java.io.ByteArrayOutputStream()
        for (o in objects) { offsets.add(offset); body.write(o); offset += o.size }
        val table = java.io.ByteArrayOutputStream()
        offsets.forEach { table.write(it) }
        val trailer = java.io.ByteArrayOutputStream()
        repeat(6) { trailer.write(0) }
        trailer.write(1); trailer.write(1)
        for (v in listOf(objects.size.toLong(), 0L, offset.toLong())) {
            for (i in 7 downTo 0) trailer.write(((v shr (8 * i)) and 0xFF).toInt())
        }
        val plistBytes = header + body.toByteArray() + table.toByteArray() + trailer.toByteArray()

        val result = IpaInspector.inspect(fullIpa("binary.ipa", plist = plistBytes).absolutePath)
        assertTrue("Success expected: $result", result is IpaResult.Success)
        result as IpaResult.Success
        assertEquals("com.example.binary", result.info.bundleId)
        assertEquals("Demo", result.info.executableName)
        assertNull("CFBundleShortVersionString 없으면 null", result.info.version)
    }

    @Test
    fun x86_64_전용_IPA는_CPU_불호환_사유가_추가된다() {
        val result = IpaInspector.inspect(fullIpa("x86.ipa", binary = x86_64Binary).absolutePath)
        assertTrue(result is IpaResult.Success)
        result as IpaResult.Success
        assertEquals(false, result.verdict.cpuCompatible)
        assertFalse(result.verdict.runnable)
        assertTrue(result.verdict.reasons.any { it.contains("arm64 슬라이스가 없습니다") })
    }

    @Test
    fun ZIP이_아닌_파일은_실패() {
        val file = File(tempDir(), "notzip.bin")
        file.writeBytes(ByteArray(128) { (it * 3).toByte() })
        val result = IpaInspector.inspect(file.absolutePath)
        assertTrue("Fail expected: $result", result is IpaResult.Fail)
        assertTrue((result as IpaResult.Fail).reason.contains("ZIP"))
    }

    @Test
    fun 손상된_ZIP은_실패() {
        val file = File(tempDir(), "corrupt.ipa")
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(200) { 7 })
        val result = IpaInspector.inspect(file.absolutePath)
        assertTrue("Fail expected: $result", result is IpaResult.Fail)
        assertTrue((result as IpaResult.Fail).reason.contains("손상"))
    }

    @Test
    fun Payload가_없으면_실패() {
        val file = ipa("nopayload.ipa", listOf("Something/else.txt" to ByteArray(8)))
        val result = IpaInspector.inspect(file.absolutePath)
        assertTrue(result is IpaResult.Fail)
        assertTrue((result as IpaResult.Fail).reason.contains("Payload"))
    }

    @Test
    fun Info_plist가_없으면_실패() {
        val file = ipa(
            "noplist.ipa",
            listOf("Payload/Demo.app/Demo" to arm64Binary),
        )
        val result = IpaInspector.inspect(file.absolutePath)
        assertTrue(result is IpaResult.Fail)
        assertTrue((result as IpaResult.Fail).reason.contains("Info.plist"))
    }

    @Test
    fun 해석_할_수_없는_Info_plist는_실패() {
        val file = ipa(
            "badplist.ipa",
            listOf("Payload/Demo.app/Info.plist" to ByteArray(64) { 0x55 }),
        )
        val result = IpaInspector.inspect(file.absolutePath)
        assertTrue(result is IpaResult.Fail)
        assertTrue((result as IpaResult.Fail).reason.contains("해석"))
    }

    @Test
    fun zip_slip_경로는_차단한다() {
        val file = ipa(
            "slip.ipa",
            listOf(
                "Payload/Demo.app/Info.plist" to xmlInfoPlist(),
                "../evil.txt" to ByteArray(8),
            ),
        )
        val result = IpaInspector.inspect(file.absolutePath)
        assertTrue("Fail expected: $result", result is IpaResult.Fail)
        assertTrue((result as IpaResult.Fail).reason.contains("안전하지 않은"))
    }

    @Test
    fun 실행_파일이_없으면_경고와_사유를_남긴다() {
        val file = ipa(
            "nobinary.ipa",
            listOf("Payload/Demo.app/Info.plist" to xmlInfoPlist()),
        )
        val result = IpaInspector.inspect(file.absolutePath)
        assertTrue(result is IpaResult.Success)
        result as IpaResult.Success
        assertNull(result.mainBinary)
        assertTrue(result.info.warnings.any { it.contains("메인 실행 파일이 없습니다") })
        assertNull("CPU를 확인하지 못했으면 null", result.verdict.cpuCompatible)
        assertTrue(result.verdict.reasons.any { it.contains("CPU 호환 여부를 확인할 수 없습니다") })
    }

    @Test
    fun 상한보다_큰_바이너리는_분석을_생략한다() {
        val big = arm64Binary + ByteArray(1024)
        val file = ipa(
            "big.ipa",
            listOf(
                "Payload/Demo.app/Info.plist" to xmlInfoPlist(),
                "Payload/Demo.app/Demo" to big,
            ),
        )
        val result = IpaInspector.inspect(file.absolutePath, maxBinaryBytes = 16)
        assertTrue(result is IpaResult.Success)
        result as IpaResult.Success
        assertNull(result.mainBinary)
        assertTrue(result.info.warnings.any { it.contains("생략") })
    }

    @Test
    fun 앱_폴더가_여러_개면_첫_것을_쓰고_경고한다() {
        val file = ipa(
            "two-apps.ipa",
            listOf(
                "Payload/First.app/Info.plist" to xmlInfoPlist(),
                "Payload/First.app/First" to arm64Binary,
                "Payload/Second.app/Info.plist" to xmlInfoPlist(),
            ),
        )
        val result = IpaInspector.inspect(file.absolutePath)
        assertTrue(result is IpaResult.Success)
        result as IpaResult.Success
        assertEquals("First.app", result.info.appFolderName)
        assertTrue(result.info.warnings.any { it.contains("앱 폴더가 2개") })
    }

    @Test
    fun 없는_경로는_실패() {
        val result = IpaInspector.inspect("/nonexistent/nope.ipa")
        assertTrue(result is IpaResult.Fail)
    }

    @Test
    fun 판정의_사유는_실행_엔진_부재를_항상_포함한다() {
        val result = IpaInspector.inspect(fullIpa("reasons.ipa").absolutePath) as IpaResult.Success
        assertNotNull(result.verdict.reasons.firstOrNull())
        // CPU가 맞아도 runnable은 거짓이다
        assertEquals(true, result.verdict.cpuCompatible)
        assertFalse(result.verdict.runnable)
    }
}
