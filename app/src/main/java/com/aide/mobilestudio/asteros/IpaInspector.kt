package com.aide.mobilestudio.asteros

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** IPA에서 읽어낸 앱 번들 정보. 없는 값은 null이다. */
data class IpaInfo(
    /** Payload/<이름>.app 폴더 이름 (예: "Demo.app") */
    val appFolderName: String?,
    val displayName: String?,
    val bundleId: String?,
    val version: String?,
    val buildVersion: String?,
    val minimumOsVersion: String?,
    val executableName: String?,
    val frameworks: List<String>,
    val appExtensions: List<String>,
    val hasCodeSignature: Boolean,
    val hasMobileProvision: Boolean,
    val deviceFamilies: List<Int>,
    val warnings: List<String>,
)

/**
 * 이 IPA가 이 기기에서 실행될 수 있는지에 대한 판정.
 *
 * [runnable]은 현재 단계에서는 항상 false다: 실행 엔진이 아직 없다.
 * [cpuCompatible]은 null이 아니라면 "엔진이 있다면 CPU는 소화할 수 있는가"만
 * 알려준다. 혼동되지 않도록 두 값을 분리해 둔다.
 */
data class IpaVerdict(
    val runnable: Boolean,
    val cpuCompatible: Boolean?,
    val reasons: List<String>,
    val notes: List<String>,
)

sealed class IpaResult {
    data class Success(
        val info: IpaInfo,
        val mainBinary: MachOResult?,
        val verdict: IpaVerdict,
    ) : IpaResult()

    data class Fail(val reason: String) : IpaResult()
}

/**
 * IPA(압축된 iOS 앱 번들) 열기 및 실행 가능성 판정 (AsterOS Phase 2-A).
 *
 * 하는 일:
 * - Payload 아래 .app 구조와 Info.plist(XML/binary) 판독
 * - 메인 실행 파일을 Mach-O 분석기에 넘겨 아키텍처/의존 라이브러리 확인
 * - 프레임워크, 앱 확장(PlugIns 아래 .appex), 코드 서명, 프로비저닝 존재 여부
 * - 실행 가능성 판정: CPU 호환 여부와 실행 불가 사유를 구분해 보고
 *
 * 하지 않는 일:
 * - 압축 전체 풀기 (필요한 항목만 스트리밍으로 읽는다)
 * - 임의 경로 쓰기 (임시 파일은 고정 위치고 사용 후 삭제한다)
 * - 바이너리 실행
 */
object IpaInspector {

    /** 이 크기를 넘는 메인 바이너리는 분석하지 않고 경고만 남긴다. */
    private const val MAX_BINARY_BYTES = 256L * 1024 * 1024

    /** Info.plist는 보통 수 KB다. 이보다 크면 이상한 파일로 본다. */
    private const val MAX_INFO_PLIST_BYTES = 8L * 1024 * 1024

    fun inspect(ipaPath: String, maxBinaryBytes: Long = MAX_BINARY_BYTES): IpaResult {
        val file = File(ipaPath)
        if (!file.isFile) return IpaResult.Fail("파일이 아닙니다: $ipaPath")
        if (file.length() < 4) return IpaResult.Fail("파일이 너무 작습니다: ${file.length()}byte")

        val magic = ByteArray(4)
        RandomAccessFile(file, "r").use { raf -> raf.readFully(magic) }
        if (!(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte())
        ) {
            return IpaResult.Fail("IPA(ZIP) 형식이 아닙니다 (magic=${magic.joinToString(" ") { "%02x".format(it) }})")
        }

        val zip = try {
            ZipFile(file)
        } catch (e: ZipException) {
            return IpaResult.Fail("손상된 ZIP/IPA입니다: ${e.message}")
        }

        zip.use { z ->
            //1) 내부 경로 안전 검사 + .app 폴더 후보 수집
            val appFolders = LinkedHashSet<String>()
            val allNames = mutableListOf<String>()
            var sawUnsafe = false
            var unsafeName = ""
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                val name = entries.nextElement().name
                if (name.startsWith("/") || name.contains("..") || name.contains('\\')) {
                    sawUnsafe = true
                    unsafeName = name
                    break
                }
                allNames.add(name)
                val m = Regex("""^Payload/([^/]+\.app)/""").find(name)
                if (m != null) appFolders.add(m.groupValues[1])
            }
            if (sawUnsafe) return IpaResult.Fail("압축 내부에 안전하지 않은 경로가 있어 중단합니다: $unsafeName")
            val appFolder = appFolders.firstOrNull()
                ?: return IpaResult.Fail("Payload/*.app 폴더를 찾지 못했습니다")
            val appDir = "Payload/$appFolder/"

            //2) Info.plist
            val plistEntry = z.getEntry(appDir + "Info.plist")
                ?: return IpaResult.Fail("Info.plist가 없습니다: ${appDir}Info.plist")
            if (plistEntry.size > MAX_INFO_PLIST_BYTES) {
                return IpaResult.Fail("Info.plist가 비정상적으로 큽니다: ${plistEntry.size}byte")
            }
            val plistBytes = z.getInputStream(plistEntry)?.use { it.readBytes() }
                ?: return IpaResult.Fail("Info.plist를 읽지 못했습니다")
            val plist = Plist.parse(plistBytes)
                ?: return IpaResult.Fail("Info.plist를 해석하지 못했습니다 (XML/binary plist 지원)")

            fun string(key: String): String? = (plist[key] as? String)?.takeIf { it.isNotEmpty() }

            val warnings = mutableListOf<String>()
            if (appFolders.size > 1) warnings.add("앱 폴더가 ${appFolders.size}개입니다: 첫 번째($appFolder)만 사용")

            val executableName = string("CFBundleExecutable") ?: appFolder.removeSuffix(".app")

            //3) 목록성 정보
            val fwPrefix = appDir + "Frameworks/"
            val frameworks = allNames.mapNotNull { name ->
                if (!name.startsWith(fwPrefix)) return@mapNotNull null
                val rest = name.removePrefix(fwPrefix)
                val folder = rest.substringBefore('/')
                if (folder.endsWith(".framework") && rest.length > folder.length) {
                    folder.removeSuffix(".framework")
                } else null
            }.distinct()
            val exPrefix = appDir + "PlugIns/"
            val extensions = allNames.mapNotNull { name ->
                if (!name.startsWith(exPrefix)) return@mapNotNull null
                val rest = name.removePrefix(exPrefix)
                val folder = rest.substringBefore('/')
                if (folder.endsWith(".appex") && rest.length > folder.length) {
                    folder.removeSuffix(".appex")
                } else null
            }.distinct()
            val hasSignature = z.entries().asSequence().any { it.name.startsWith(appDir + "_CodeSignature/") }
            val hasProvision = z.getEntry(appDir + "embedded.mobileprovision") != null
            val deviceFamilies = (plist["UIDeviceFamily"] as? List<*>)
                ?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()

            //4) 메인 실행 파일 분석 (임시 파일로 옮겨 분석 후 즉시 삭제)
            var mainBinary: MachOResult? = null
            val binaryEntry = z.getEntry(appDir + executableName)
            if (binaryEntry == null) {
                warnings.add("메인 실행 파일이 없습니다: $appDir$executableName")
            } else if (binaryEntry.size > maxBinaryBytes) {
                warnings.add("메인 실행 파일이 ${binaryEntry.size}byte로 커서 분석을 생략했습니다")
            } else {
                val tmp = File.createTempFile("asteros-ipa-main", ".bin")
                try {
                    var extracted = true
                    z.getInputStream(binaryEntry)?.use { ins ->
                        tmp.outputStream().use { outs ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxBinaryBytes) { extracted = false; break }
                                outs.write(buf, 0, n)
                            }
                        }
                    }
                    if (extracted) {
                        mainBinary = MachOAnalyzer.analyze(tmp.absolutePath)
                    } else {
                        warnings.add("메인 실행 파일 추출이 ${maxBinaryBytes}byte 상한을 넘겨 분석을 생략했습니다")
                    }
                } finally {
                    tmp.delete()
                }
            }

            val info = IpaInfo(
                appFolderName = appFolder,
                displayName = string("CFBundleDisplayName") ?: string("CFBundleName"),
                bundleId = string("CFBundleIdentifier"),
                version = string("CFBundleShortVersionString"),
                buildVersion = string("CFBundleVersion"),
                minimumOsVersion = string("MinimumOSVersion"),
                executableName = executableName,
                frameworks = frameworks,
                appExtensions = extensions,
                hasCodeSignature = hasSignature,
                hasMobileProvision = hasProvision,
                deviceFamilies = deviceFamilies,
                warnings = warnings,
            )
            return IpaResult.Success(info, mainBinary, judge(info, mainBinary))
        }
    }

    /**
     * 실행 가능성 판정.
     *
     * "실행 엔진이 없다"는 사실은 어떤 IPA에도 공통으로 적용되는 구조적
     * 제약이고, 그 위에서 IPA마다 다른 부분(CPU, 의존 라이브러리)을
     * 따로 보고한다. CPU가 맞다고 실행 가능하다고 쓰지 않는다.
     */
    private fun judge(info: IpaInfo, mainBinary: MachOResult?): IpaVerdict {
        val reasons = mutableListOf<String>()
        val notes = mutableListOf<String>()
        var cpuCompatible: Boolean? = null

        reasons.add("AsterOS에 실행 엔진이 없습니다: Android 커널은 ELF만 로드하며, Mach-O 로더와 dyld 번역이 없습니다")
        reasons.add("iOS 시스템 런타임이 없습니다: UIKit/CoreFoundation/libSystem(.dylib/.framework)을 제공할 수 없습니다")

        when (mainBinary) {
            is MachOResult.Success -> {
                val archs = mainBinary.architectures
                cpuCompatible = archs.any { it == "arm64" || it == "arm64_32" }
                if (cpuCompatible == true) {
                    notes.add("arm64 슬라이스가 있어 이 기기 CPU 구조와는 맞습니다 (엔진이 있어야 의미가 있습니다)")
                } else {
                    reasons.add("메인 실행 파일에 arm64 슬라이스가 없습니다: ${archs.joinToString()} — ARM 기기에서 네이티브 실행 불가")
                }
                if (mainBinary.filetype != "EXECUTE") {
                    notes.add("메인 바이너리 filetype이 ${mainBinary.filetype}입니다 (EXECUTE가 아님)")
                }
                if (mainBinary.dylibs.isNotEmpty()) {
                    notes.add("의존 라이브러리 ${mainBinary.dylibs.size}개: ${mainBinary.dylibs.take(3).joinToString()}${if (mainBinary.dylibs.size > 3) " ..." else ""}")
                }
                mainBinary.warnings.forEach { notes.add("Mach-O 경고: $it") }
            }
            is MachOResult.Fail -> reasons.add("메인 실행 파일을 분석할 수 없습니다: ${mainBinary.reason}")
            is MachOResult.NotSupported -> reasons.add("메인 실행 파일 형식을 다룰 수 없습니다: ${mainBinary.reason}")
            null -> reasons.add("메인 실행 파일이 IPA에 없어 CPU 호환 여부를 확인할 수 없습니다")
        }

        info.minimumOsVersion?.let { notes.add("이 앱은 최소 iOS ${it}을 요구합니다") }
        if (!info.hasCodeSignature) notes.add("코드 서명(_CodeSignature)이 없습니다")
        if (info.frameworks.isNotEmpty()) notes.add("번들 프레임워크 ${info.frameworks.size}개: ${info.frameworks.joinToString()}")
        if (info.appExtensions.isNotEmpty()) notes.add("앱 확장 ${info.appExtensions.size}개: ${info.appExtensions.joinToString()}")

        return IpaVerdict(runnable = false, cpuCompatible = cpuCompatible, reasons = reasons, notes = notes)
    }
}
