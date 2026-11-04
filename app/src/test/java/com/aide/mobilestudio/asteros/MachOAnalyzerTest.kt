package com.aide.mobilestudio.asteros

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AsterOS Phase 1-B - Mach-O 분석기 단위 테스트.
 *
 * fixture는 전부 이 테스트가 직접 byte 배열로 만든다 (외부 바이너리 미사용).
 * 다루는 경우: 정상 파일 / 잘못된 매직 / 빈 파일 / 잘린 헤더 /
 * 잘린 커맨드 영역 / 잘못된 cmdsize / 범위 이탈 세그먼트 / 비정상 ncmds /
 * 미지원 CPU / fat 컨테이너 / 32비트 / ELF / 대용량 파일.
 */
class MachOAnalyzerTest {

    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    // ------------------------------------------------------------- fixtures --

    private fun fixture(name: String, bytes: ByteArray): File {
        val dir = java.nio.file.Files.createTempDirectory("ms-macho").toFile()
        tempDirs += dir
        val f = File(dir, name)
        f.writeBytes(bytes)
        return f
    }

    private fun u32le(v: Long): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun u32be(v: Long): ByteArray = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun u64le(v: Long): ByteArray =
        (0 until 8).map { i -> ((v shr (8 * i)) and 0xFF).toByte() }.toByteArray()

    private fun asciiField(s: String, len: Int): ByteArray {
        val out = ByteArray(len)
        val b = s.toByteArray(Charsets.US_ASCII)
        b.copyInto(out, 0, 0, minOf(b.size, len))
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    /** mach_header_64 (32byte). 기본 cpu=arm64(0x0100000c). */
    private fun header64(
        cputype: Long = 0x0100000CL,
        cpusubtype: Long = 0,
        filetype: Long = 2,
        ncmds: Long = 0,
        sizeofcmds: Long = 0,
    ): ByteArray = concat(
        u32le(0xFEEDFACF), u32le(cputype), u32le(cpusubtype), u32le(filetype),
        u32le(ncmds), u32le(sizeofcmds), u32le(0x00200085), u32le(0),
    )

    /** LC_SEGMENT_64 (72byte + 섹션). */
    private fun segment64(
        name: String,
        vmaddr: Long = 0,
        vmsize: Long = 0x1000,
        fileoff: Long = 0,
        filesize: Long = 0x1000,
        nsects: Long = 0,
    ): ByteArray = concat(
        u32le(0x19),
        u32le(72 + nsects * 80),
        asciiField(name, 16),
        u64le(vmaddr), u64le(vmsize), u64le(fileoff), u64le(filesize),
        u32le(7), u32le(5), u32le(nsects), u32le(0),
    )

    /** LC_LOAD_DYLIB 계열: 커맨드 헤더(24) + NUL 종료 경로(8byte 정렬). */
    private fun dylibCommand(cmd: Long, path: String, nameOffset: Long = 24): ByteArray {
        val body = path.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val unpadded = 24 + body.size
        val cmdsize = (unpadded + 7) and 7.inv()
        val name = ByteArray(cmdsize - 24)
        body.copyInto(name)
        return concat(u32le(cmd), u32le(cmdsize.toLong()), u32le(nameOffset), u32le(0), u32le(0x10000), u32le(0x10000), name)
    }

    private fun uuidCommand(hex16: ByteArray): ByteArray =
        concat(u32le(0x1B), u32le(24), hex16)

    // ------------------------------------------------------- 정상 경로 테스트 --

    @Test
    fun 정상_arm64_실행파일_분석() {
        val uuidBytes = (0 until 16).map { it.toByte() }.toByteArray()
        val segment = segment64("__TEXT", vmaddr = 0x100000000L, fileoff = 0, filesize = 0x200)
        val dylib = dylibCommand(0xC, "/usr/lib/libSystem.B.dylib")
        val uuid = uuidCommand(uuidBytes)
        val commands = concat(segment, dylib, uuid)
        val file = fixture(
            "ok.bin",
            concat(header64(ncmds = 3, sizeofcmds = commands.size.toLong()), commands, ByteArray(512 - 32 - commands.size)),
        )

        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Success expected: $result", result is MachOResult.Success)
        result as MachOResult.Success
        assertEquals("arm64", result.cputype)
        assertEquals("EXECUTE", result.filetype)
        assertEquals(3, result.ncmds)
        assertEquals(1, result.segments.size)
        assertEquals("__TEXT", result.segments[0].name)
        assertEquals(0x100000000L, result.segments[0].vmaddr)
        assertTrue(result.dylibs.contains("/usr/lib/libSystem.B.dylib"))
        assertNotNull(result.uuid)
        assertEquals(listOf("arm64"), result.architectures)
        assertTrue("경고 없어야 함: ${result.warnings}", result.warnings.isEmpty())
    }

    @Test
    fun 커맨드_없는_파일은_성공하지만_경고() {
        val file = fixture("nocommands.bin", header64(ncmds = 0, sizeofcmds = 0))
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Success expected: $result", result is MachOResult.Success)
        result as MachOResult.Success
        assertEquals(0, result.ncmds)
        assertTrue(result.warnings.any { it.contains("ncmds=0") })
    }

    @Test
    fun 범위_이탈_세그먼트는_경고로_남긴다() {
        val segment = segment64("__DATA", fileoff = 100_000, filesize = 50_000)
        val file = fixture(
            "oob.bin",
            concat(header64(ncmds = 1, sizeofcmds = segment.size.toLong()), segment),
        )
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Success expected: $result", result is MachOResult.Success)
        result as MachOResult.Success
        assertTrue(result.warnings.any { it.contains("__DATA") && it.contains("벗어납니다") })
    }

    @Test
    fun dylib_이름_오프셋_오류는_경고() {
        val dylib = dylibCommand(0xC, "/usr/lib/libfoo.dylib", nameOffset = 4)
        val file = fixture(
            "badyliboff.bin",
            concat(header64(ncmds = 1, sizeofcmds = dylib.size.toLong()), dylib, ByteArray(32)),
        )
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Success expected: $result", result is MachOResult.Success)
        result as MachOResult.Success
        assertTrue(result.warnings.any { it.contains("오프셋") })
    }

    // ------------------------------------------------------- 실패/미지원 테스트 --

    @Test
    fun 없는_파일은_실패() {
        val result = MachOAnalyzer.analyze("/nonexistent/path/definitely-missing.bin")
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
    }

    @Test
    fun 빈_파일은_실패() {
        val file = fixture("empty.bin", ByteArray(0))
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
    }

    @Test
    fun 잘못된_매직은_미지원() {
        val file = fixture("badmagic.bin", ByteArray(64) { (it * 7 + 1).toByte() })
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("NotSupported expected: $result", result is MachOResult.NotSupported)
        assertTrue((result as MachOResult.NotSupported).reason.contains("매직"))
    }

    @Test
    fun 헤더가_잘린_파일은_실패() {
        // 매직은 맞지만 mach_header_64(32byte)보다 작다.
        val file = fixture("trunc-header.bin", u32le(0xFEEDFACF) + ByteArray(12))
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
        assertTrue((result as MachOResult.Fail).reason.contains("잘렸습니다"))
    }

    @Test
    fun 커맨드_영역이_파일보다_큰_파일은_실패() {
        // sizeofcmds=72인데 파일에는 커맨드가16byte만 있다.
        val partial = segment64("__TEXT").copyOf(16)
        val file = fixture(
            "trunc-cmds.bin",
            concat(header64(ncmds = 1, sizeofcmds = 72), partial),
        )
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
        assertTrue((result as MachOResult.Fail).reason.contains("벗어납니다"))
    }

    @Test
    fun 잘못된_cmdsize_0은_실패() {
        val bad = concat(u32le(0x19), u32le(0), ByteArray(64))
        val file = fixture(
            "bad-cmdsize.bin",
            concat(header64(ncmds = 1, sizeofcmds = bad.size.toLong()), bad),
        )
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
        assertTrue((result as MachOResult.Fail).reason.contains("cmdsize"))
    }

    @Test
    fun cmdsize가_영역을_벗어나면_실패() {
        val bad = concat(u32le(0x19), u32le(500), ByteArray(64))
        val file = fixture(
            "oob-cmdsize.bin",
            concat(header64(ncmds = 1, sizeofcmds = bad.size.toLong()), bad),
        )
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
        assertTrue((result as MachOResult.Fail).reason.contains("cmdsize"))
    }

    @Test
    fun 비정상한_ncmds는_실패() {
        // ncmds가 상한(4096)을 넘는 경우
        val segment = segment64("__TEXT")
        val file = fixture(
            "huge-ncmds.bin",
            concat(header64(ncmds = 0x00FFFFFF, sizeofcmds = segment.size.toLong()), segment),
        )
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
        assertTrue((result as MachOResult.Fail).reason.contains("ncmds"))
    }

    @Test
    fun ncmds와_cmdsize가_불일치하면_실패() {
        val segment = segment64("__TEXT")
        val file = fixture(
            "ncmds-mismatch.bin",
            concat(header64(ncmds = 100, sizeofcmds = segment.size.toLong()), segment),
        )
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
        assertTrue((result as MachOResult.Fail).reason.contains("맞지 않습니다"))
    }

    @Test
    fun 미지원_CPU는_NotSupported() {
        val file = fixture("ppc.bin", header64(cputype = 18, ncmds = 0, sizeofcmds = 0))
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("NotSupported expected: $result", result is MachOResult.NotSupported)
        assertTrue((result as MachOResult.NotSupported).reason.contains("CPU"))
    }

    @Test
    fun 삼십이비트_MachO는_NotSupported() {
        val header = u32le(0xFEEDFACE) + ByteArray(28)
        val file = fixture("macho32.bin", header)
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("NotSupported expected: $result", result is MachOResult.NotSupported)
        assertTrue((result as MachOResult.NotSupported).reason.contains("32비트"))
    }

    @Test
    fun ELF는_NotSupported() {
        val file = fixture("elf.bin", byteArrayOf(0x7F, 0x45, 0x4C, 0x46) + ByteArray(60))
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("NotSupported expected: $result", result is MachOResult.NotSupported)
        assertTrue((result as MachOResult.NotSupported).reason.contains("ELF"))
    }

    // ------------------------------------------------------------ fat 컨테이너 --

    /** fat_header + fat_arch 테이블 (모두 big-endian). Triple(cputype, offset, size). */
    private fun fatHeader(arches: List<Triple<Long, Long, Long>>): ByteArray {
        var table = ByteArray(0)
        for ((ct, off, sz) in arches) {
            table = concat(table, u32be(ct), u32be(0), u32be(off), u32be(sz), u32be(12))
        }
        return concat(u32be(0xCAFEBABE), u32be(arches.size.toLong()), table)
    }

    @Test
    fun fat_컨테이너는_지원_슬라이스를_고른다() {
        val slice1Off = 0x1000L
        val slice2Off = 0x4000L
        val segment = segment64("__TEXT", fileoff = 0, filesize = 0x200)
        val slice1 = concat(
            header64(cputype = 0x0100000C, ncmds = 1, sizeofcmds = segment.size.toLong()),
            segment,
        )
        val slice2 = header64(cputype = 0x01000007, ncmds = 0, sizeofcmds = 0)
        val fat = fatHeader(
            listOf(
                Triple(0x0100000C, slice1Off, slice1.size.toLong()),
                Triple(0x01000007, slice2Off, slice2.size.toLong()),
            ),
        )
        val bytes = ByteArray(slice2Off.toInt() + slice2.size)
        fat.copyInto(bytes, 0)
        slice1.copyInto(bytes, slice1Off.toInt())
        slice2.copyInto(bytes, slice2Off.toInt())
        val file = fixture("fat.bin", bytes)

        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Success expected: $result", result is MachOResult.Success)
        result as MachOResult.Success
        assertEquals("arm64", result.cputype)
        assertEquals(listOf("arm64", "x86_64"), result.architectures)
        assertEquals(1, result.segments.size)
    }

    @Test
    fun fat에_지원하는_슬라이스가_없으면_NotSupported() {
        val sliceOff = 0x1000L
        val slice = header64(cputype = 18, ncmds = 0, sizeofcmds = 0) // powerpc
        val fat = fatHeader(listOf(Triple(18L, sliceOff, slice.size.toLong())))
        val bytes = ByteArray((sliceOff + slice.size).toInt())
        fat.copyInto(bytes, 0)
        slice.copyInto(bytes, sliceOff.toInt())
        val file = fixture("fat-ppc.bin", bytes)

        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("NotSupported expected: $result", result is MachOResult.NotSupported)
        assertTrue((result as MachOResult.NotSupported).reason.contains("fat"))
    }

    @Test
    fun nfat_arch가_비정상이면_실패() {
        val fat = concat(u32be(0xCAFEBABE), u32be(0x7FFFFFFF))
        val file = fixture("fat-badnfat.bin", fat + ByteArray(64))
        val result = MachOAnalyzer.analyze(file.absolutePath)
        assertTrue("Fail expected: $result", result is MachOResult.Fail)
    }

    // ------------------------------------------------------------- 대용량 파일 --

    @Test
    fun 대용량_파일도_헤더_영역만_읽는다() {
        //8MB 파일. 헤더와 커맨드는 앞에만 있고 나머지는 전부0이다.
        val segment = segment64("__TEXT", fileoff = 0, filesize = 8L * 1024 * 1024)
        val header = header64(ncmds = 1, sizeofcmds = segment.size.toLong())
        val bytes = ByteArray(8 * 1024 * 1024)
        header.copyInto(bytes, 0)
        segment.copyInto(bytes, header.size)
        val file = fixture("large.bin", bytes)

        val started = System.currentTimeMillis()
        val result = MachOAnalyzer.analyze(file.absolutePath)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("Success expected: $result", result is MachOResult.Success)
        assertEquals("__TEXT", (result as MachOResult.Success).segments[0].name)
        assertTrue("전체를 읽었다면 오래 걸린다: ${elapsed}ms", elapsed < 5_000)
    }
}
