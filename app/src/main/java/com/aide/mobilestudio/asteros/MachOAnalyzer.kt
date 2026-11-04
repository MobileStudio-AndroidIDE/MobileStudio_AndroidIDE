package com.aide.mobilestudio.asteros

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Mach-O 바이너리 분석 결과.
 *
 * 분류 세 가지: Success(정상 분석), Fail(파일이 깨졌거나 읽을 수 없음),
 * NotSupported(형식은 알지만 이 분석기가 다루지 않음).
 * 실행·로드는 일절 하지 않으며 결과에 들어 있는 정보는 읽기 전용이다.
 */
sealed class MachOResult {

    /** 분석 성공 - 유효한 64비트 Mach-O 슬라이스 */
    data class Success(
        val magic: String,
        val cputype: String,
        val cpusubtype: String,
        val filetype: String,
        val ncmds: Int,
        val fileSize: Long,
        val segments: List<SegmentInfo>,
        val dylibs: List<String>,
        val uuid: String?,
        /** fat 컨테이너에 들어 있던 아키텍처 전체 목록. 단일 슬라이스면1개. */
        val architectures: List<String>,
        /** 복구 가능한 비정상 요소 (범위 이탈 세그먼트 등). 오류 아님. */
        val warnings: List<String>,
    ) : MachOResult()

    /** 파일이 깨졌거나 읽을 수 없음 */
    data class Fail(val reason: String) : MachOResult()

    /** 형식 미지원 (32비트, 빅엔디안, 미지원 CPU, ELF 등) */
    data class NotSupported(val reason: String) : MachOResult()
}

/** LC_SEGMENT_64 하나의 요약 */
data class SegmentInfo(
    val name: String,
    val vmaddr: Long,
    val vmsize: Long,
    val fileoff: Long,
    val filesize: Long,
    val sectionCount: Int,
)

/**
 * AsterOS Mach-O 분석기 (Phase 1-B).
 *
 * 하는 일:
 * - 매직 값 검증 (64비트 little-endian Mach-O, fat/universal 컨테이너)
 * - mach_header_64 필드 추출 (cputype, filetype, ncmds)
 * - 로드 커맨드 검증 및 파싱 (LC_SEGMENT_64, LC_LOAD_DYLIB 계열, LC_UUID)
 * - 세그먼트/섹션/dylib 목록과 범위 경고 수집
 *
 * 하지 않는 일:
 * - 바이너리 실행, 로드, 설치, 서명 검증
 * - 입력 파일 수정 (전부 읽기 전용 접근)
 *
 * 안전성 규칙:
 * - 모든 오프셋·cmdsize·ncmds는 파일 크기와 대조 후 사용한다
 * - 헤더와 로드 커맨드 영역만 읽으므로 수 GB 파일도 상수 시간에 가깝게 처리한다
 * - 잘못된 구조는 추측하지 않고 Fail/NotSupported로 돌려준다
 */
object MachOAnalyzer {

    // ---- 매직 값 (uint32를 Long으로 부호 없이 비교) ------------------------
    private const val MH_MAGIC_64 = 0xFEEDFACFL    // 64비트, little-endian
    private const val MH_CIGAM_64 = 0xCFFAEDFEL    // 64비트, big-endian
    private const val MH_MAGIC_32 = 0xFEEDFACEL    // 32비트, little-endian
    private const val MH_CIGAM_32 = 0xCEFAEDFEL    // 32비트, big-endian
    private const val FAT_MAGIC = 0xCAFEBABEL      // fat 헤더는 항상 big-endian
    private const val FAT_MAGIC_64 = 0xCAFEBABFL
    private const val ELF_MAGIC = 0x7F454C46L      // '\x7F''E''L''F'

    // ---- 크기/상한 ----------------------------------------------------------
    private const val HEADER_SIZE_64 = 32
    private const val FAT_HEADER_SIZE = 8
    private const val FAT_ARCH_SIZE = 20
    private const val FAT_ARCH_64_SIZE = 32
    private const val MAX_FAT_ARCH = 64
    private const val MAX_NCMDS = 4096
    private const val MAX_COMMAND_BYTES = 16 * 1024 * 1024
    private const val SEG_COMMAND_SIZE = 72
    private const val SECTION_SIZE_64 = 80
    private const val DYLIB_MIN_SIZE = 24

    // ---- 로드 커맨드 타입 ----------------------------------------------------
    private const val LC_SYMTAB = 0x2L
    private const val LC_SEGMENT_64 = 0x19L
    private const val LC_UUID = 0x1BL
    private const val LC_LOAD_DYLIB = 0xCL
    private const val LC_ID_DYLIB = 0xDL
    private const val LC_LOAD_WEAK_DYLIB = 0x18L
    private const val LC_REEXPORT_DYLIB = 0x1FL
    private const val LC_LAZY_LOAD_DYLIB = 0x20L
    private const val LC_LOAD_UPWARD_DYLIB = 0x23L

    /** 파일이 아예 없는 경우를 포함해 어떤 경로든 분류해서 돌려준다. */
    fun analyze(filePath: String): MachOResult {
        val file = File(filePath)
        if (!file.exists()) return MachOResult.Fail("파일이 존재하지 않습니다: $filePath")
        if (!file.isFile) return MachOResult.Fail("파일이 아닙니다: $filePath")
        val size = file.length()
        if (size == 0L) return MachOResult.Fail("빈 파일입니다")
        if (size < 4L) return MachOResult.Fail("파일이 너무 작습니다: ${size}byte")
        return try {
            RandomAccessFile(file, "r").use { raf -> classify(raf, size) }
        } catch (e: IOException) {
            MachOResult.Fail("파일 읽기 실패: ${e.message}")
        }
    }

    /** 매직 값으로 형식을 가린 뒤 적절한 파서로 보낸다. */
    private fun classify(raf: RandomAccessFile, size: Long): MachOResult {
        val magicBytes = readAt(raf, 0, 4)
            ?: return MachOResult.Fail("매직 값을 읽지 못했습니다")
        val le = u32le(magicBytes, 0)
        val be = u32be(magicBytes, 0)
        return when {
            le == MH_MAGIC_64 -> analyzeSlice(raf, 0, size)
            le == MH_CIGAM_64 -> MachOResult.NotSupported("빅엔디안 64비트 Mach-O는 미지원입니다")
            le == MH_MAGIC_32 || le == MH_CIGAM_32 ->
                MachOResult.NotSupported("32비트 Mach-O는 미지원입니다 (magic=0x${le.toHexString()})")
            be == FAT_MAGIC -> analyzeFat(raf, size, fat64 = false)
            be == FAT_MAGIC_64 -> analyzeFat(raf, size, fat64 = true)
            be == ELF_MAGIC -> MachOResult.NotSupported("ELF 바이너리는 Mach-O가 아닙니다")
            else -> MachOResult.NotSupported(
                "지원하지 않는 매직 값: LE=0x${le.toHexString()} BE=0x${be.toHexString()}"
            )
        }
    }

    /**
     * mach_header_64 + 로드 커맨드 파싱.
     *
     * [base]는 슬라이스 시작 오프셋 (fat 컨테이너에서는 각 아키텍처의 위치).
     * 커맨드 영역은 sizeofcmds로 한정해 그 이상을 절대 읽지 않는다.
     */
    private fun analyzeSlice(raf: RandomAccessFile, base: Long, size: Long): MachOResult {
        if (base < 0 || base + HEADER_SIZE_64 > size) {
            return MachOResult.Fail("헤더가 잘렸습니다 (mach_header_64 ${HEADER_SIZE_64}byte 필요)")
        }
        val header = readAt(raf, base, HEADER_SIZE_64)
            ?: return MachOResult.Fail("헤더를 읽지 못했습니다")

        val magic = u32le(header, 0)
        if (magic != MH_MAGIC_64) {
            return MachOResult.NotSupported(
                "슬라이스 매직이 64비트 Mach-O가 아닙니다: 0x${magic.toHexString()}"
            )
        }

        val cputype = u32le(header, 4)
        val cpu = cpuName(cputype)
            ?: return MachOResult.NotSupported("지원하지 않는 CPU 아키텍처: 0x${cputype.toHexString()}")
        val cpusubtype = u32le(header, 8)
        val filetype = u32le(header, 12)
        val ncmdsL = u32le(header, 16)
        val sizeofcmds = u32le(header, 20)

        // ncmds 검증: 실물 Mach-O는 커맨드가 수십 개 수준이고 최소 크기가
        // 8byte이므로 ncmds*8은 sizeofcmds를 넘을 수 없다.
        if (ncmdsL > MAX_NCMDS) {
            return MachOResult.Fail("ncmds가 비정상입니다: $ncmdsL (상한 $MAX_NCMDS)")
        }
        if (ncmdsL * 8 > sizeofcmds) {
            return MachOResult.Fail("ncmds($ncmdsL)와 sizeofcmds($sizeofcmds)가 맞지 않습니다")
        }
        if (sizeofcmds > MAX_COMMAND_BYTES) {
            return MachOResult.Fail("로드 커맨드 영역이 비정상적으로 큽니다: ${sizeofcmds}byte")
        }
        if (base + HEADER_SIZE_64 + sizeofcmds > size) {
            return MachOResult.Fail(
                "로드 커맨드가 파일 크기를 벗어납니다 (sizeofcmds=$sizeofcmds, file=${size}byte)"
            )
        }

        val commands = if (sizeofcmds == 0L) ByteArray(0)
        else readAt(raf, base + HEADER_SIZE_64, sizeofcmds.toInt())
            ?: return MachOResult.Fail("로드 커맨드를 읽지 못했습니다")

        val segments = mutableListOf<SegmentInfo>()
        val dylibs = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var uuid: String? = null

        if (ncmdsL == 0L) warnings.add("로드 커맨드가 없습니다 (ncmds=0)")

        var offset = 0
        var seen = 0L
        while (seen < ncmdsL) {
            if (offset + 8 > commands.size) {
                return MachOResult.Fail("${seen + 1}번째 로드 커맨드가 잘렸습니다")
            }
            val cmd = u32le(commands, offset)
            val cmdsize = u32le(commands, offset + 4)
            if (cmdsize < 8) {
                return MachOResult.Fail("잘못된 cmdsize: $cmdsize (cmd=0x${cmd.toHexString()})")
            }
            if (offset + cmdsize > commands.size) {
                return MachOResult.Fail(
                    "커맨드가 커맨드 영역을 벗어납니다 (offset=$offset, cmdsize=$cmdsize)"
                )
            }
            // LC_REQ_DYLD 비트는 타입 판별에서 제외한다.
            val baseCmd = cmd and 0x7FFFFFFFL
            when (baseCmd) {
                LC_SEGMENT_64 -> {
                    if (cmdsize < SEG_COMMAND_SIZE.toLong()) {
                        return MachOResult.Fail("LC_SEGMENT_64가 너무 짧습니다: ${cmdsize}byte")
                    }
                    val nsects = u32le(commands, offset + 64)
                    if (SEG_COMMAND_SIZE + nsects * SECTION_SIZE_64 > cmdsize) {
                        return MachOResult.Fail(
                            "섹션 수($nsects)가 cmdsize($cmdsize)를 벗어납니다"
                        )
                    }
                    val name = asciiString(commands, offset + 8, 16)
                    val vmaddr = u64le(commands, offset + 24)
                    val vmsize = u64le(commands, offset + 32)
                    val fileoff = u64le(commands, offset + 40)
                    val filesize = u64le(commands, offset + 48)
                    // 디스크 이미지에 없는 위치를 가리키는 세그먼트는 깨진
                    // 파일의 신호다. 치명적이지는 않으므로 경고로 남긴다.
                    if (filesize > 0 && fileoff + filesize > size) {
                        warnings.add(
                            "세그먼트 '$name'이 파일 범위를 벗어납니다 " +
                                "(fileoff=$fileoff, filesize=$filesize, file=${size}byte)"
                        )
                    }
                    segments.add(
                        SegmentInfo(name, vmaddr, vmsize, fileoff, filesize, nsects.toInt())
                    )
                }
                LC_UUID -> {
                    if (cmdsize >= 24L) uuid = uuidString(commands, offset + 8)
                    else warnings.add("LC_UUID 커맨드가 너무 짧습니다: ${cmdsize}byte")
                }
                LC_LOAD_DYLIB, LC_ID_DYLIB, LC_LOAD_WEAK_DYLIB,
                LC_REEXPORT_DYLIB, LC_LAZY_LOAD_DYLIB, LC_LOAD_UPWARD_DYLIB -> {
                    if (cmdsize < DYLIB_MIN_SIZE.toLong()) {
                        warnings.add("dylib 커맨드가 너무 짧습니다: ${cmdsize}byte")
                    } else {
                        val nameOffset = u32le(commands, offset + 8).toInt()
                        if (nameOffset < DYLIB_MIN_SIZE || nameOffset >= cmdsize) {
                            warnings.add("dylib 이름 오프셋이 범위를 벗어납니다: $nameOffset")
                        } else {
                            val lib = asciiString(commands, offset + nameOffset, cmdsize.toInt() - nameOffset)
                            if (lib.isNotEmpty()) dylibs.add(lib)
                        }
                    }
                }
                LC_SYMTAB -> {
                    // 심볼 테이블 정보는 현재 화면에서 보여주지 않지만
                    // 구조 검증은 통과했다는 뜻이므로 그대로 건너뛴다.
                }
                else -> {
                    // 미지원 커맨드는 cmdsize만큼 건너뛴다 (정상 처리 경로)
                }
            }
            offset += cmdsize.toInt()
            seen++
        }

        if (offset != commands.size) {
            warnings.add(
                "sizeofcmds와 실제 커맨드 합계가 다릅니다 (sizeofcmds=${commands.size}, used=$offset)"
            )
        }

        return MachOResult.Success(
            magic = "0x${magic.toHexString()}",
            cputype = cpu,
            cpusubtype = "0x${cpusubtype.toHexString()}",
            filetype = filetypeName(filetype),
            ncmds = ncmdsL.toInt(),
            fileSize = size,
            segments = segments,
            dylibs = dylibs,
            uuid = uuid,
            architectures = listOf(cpu),
            warnings = warnings,
        )
    }

    /**
     * fat/universal 컨테이너 파싱.
     *
     * 헤더와 아키텍처 테이블(big-endian)만 읽고, 지원하는 슬라이스 하나를
     * 골라 [analyzeSlice]에 넘긴다. 지원하지 않는 슬라이스는 건너뛴다.
     */
    private fun analyzeFat(raf: RandomAccessFile, size: Long, fat64: Boolean): MachOResult {
        if (size < FAT_HEADER_SIZE + 4L) {
            return MachOResult.Fail("fat 헤더가 잘렸습니다")
        }
        val head = readAt(raf, 0, FAT_HEADER_SIZE)
            ?: return MachOResult.Fail("fat 헤더를 읽지 못했습니다")
        val nfat = u32be(head, 4)
        if (nfat == 0L || nfat > MAX_FAT_ARCH) {
            return MachOResult.Fail("nfat_arch가 비정상입니다: $nfat")
        }
        val entrySize = if (fat64) FAT_ARCH_64_SIZE else FAT_ARCH_SIZE
        if (FAT_HEADER_SIZE + nfat * entrySize > size) {
            return MachOResult.Fail("fat 아키텍처 테이블이 파일 크기를 벗어납니다")
        }
        val table = readAt(raf, FAT_HEADER_SIZE.toLong(), (nfat * entrySize).toInt())
            ?: return MachOResult.Fail("fat 아키텍처 테이블을 읽지 못했습니다")

        val archs = mutableListOf<String>()
        val candidates = mutableListOf<Long>()
        for (i in 0 until nfat.toInt()) {
            val e = i * entrySize
            val ct = u32be(table, e)
            val name = cpuName(ct) ?: "unknown(0x${ct.toHexString()})"
            archs.add(name)
            val sliceOff = if (fat64) u64be(table, e + 8) else u32be(table, e + 8)
            val sliceSize = if (fat64) u64be(table, e + 16) else u32be(table, e + 12)
            // 슬라이스가 파일 안에 들어 있고 헤더를 읽을 수 있을 때만 후보로 삼는다.
            if (sliceOff < 0 || sliceOff + HEADER_SIZE_64 > size) continue
            if (sliceSize > 0 && sliceOff + sliceSize > size) continue
            candidates.add(sliceOff)
        }

        var firstFail: MachOResult.Fail? = null
        for (off in candidates) {
            val sliceHead = readAt(raf, off, 8) ?: continue
            if (u32le(sliceHead, 0) != MH_MAGIC_64) continue
            val ct = u32le(sliceHead, 4)
            if (cpuName(ct) == null) continue // 지원하지 않는 아키텍처 슬라이스
            when (val result = analyzeSlice(raf, off, size)) {
                is MachOResult.Success -> return result.copy(architectures = archs)
                is MachOResult.Fail -> if (firstFail == null) firstFail = result
                is MachOResult.NotSupported -> { /* 다음 슬라이스 시도 */ }
            }
        }
        firstFail?.let { return it }
        return MachOResult.NotSupported(
            "fat 컨테이너에 지원하는 Mach-O 슬라이스가 없습니다: ${archs.joinToString()}"
        )
    }

    // ---- 해석 헬퍼 ----------------------------------------------------------

    private fun cpuName(cputype: Long): String? = when (cputype) {
        0x00000007L -> "x86"        // CPU_TYPE_X86
        0x0000000CL -> "arm"        // CPU_TYPE_ARM
        0x01000007L -> "x86_64"     // CPU_TYPE_X86_64
        0x0100000CL -> "arm64"      // CPU_TYPE_ARM64
        0x0200000CL -> "arm64_32"   // CPU_TYPE_ARM64_32
        else -> null
    }

    private fun filetypeName(filetype: Long): String = when (filetype) {
        0x1L -> "OBJECT"
        0x2L -> "EXECUTE"
        0x5L -> "DYLIB"
        0x6L -> "BUNDLE"
        0x8L -> "DSYM"
        0x9L -> "KEXT_BUNDLE"
        else -> "unknown($filetype)"
    }

    /** 지정한 범위를 벗어나지 않는 길이만 읽는다. 실패 시 null. */
    private fun readAt(raf: RandomAccessFile, offset: Long, len: Int): ByteArray? {
        if (offset < 0 || len < 0) return null
        if (offset + len > raf.length()) return null
        val buf = ByteArray(len)
        raf.seek(offset)
        raf.readFully(buf)
        return buf
    }

    private fun u32le(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun u32be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun u64le(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    private fun u64be(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
        return v
    }

    /** NUL로 끝나는 ASCII 필드 (세그먼트명, dylib 경로). */
    private fun asciiString(buf: ByteArray, off: Int, maxLen: Int): String {
        if (off < 0 || off >= buf.size) return ""
        val end = minOf(buf.size, off + maxLen)
        var i = off
        while (i < end && buf[i] != 0.toByte()) i++
        return String(buf, off, i - off, Charsets.US_ASCII)
    }

    private fun uuidString(b: ByteArray, off: Int): String {
        val sb = StringBuilder(36)
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-')
            sb.append(String.format(java.util.Locale.US, "%02x", b[off + i]))
        }
        return sb.toString()
    }
}
