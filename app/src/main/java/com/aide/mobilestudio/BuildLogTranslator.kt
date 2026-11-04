package com.aide.mobilestudio

import android.content.Context

object BuildLogTranslator {
    fun translate(context: Context, line: String): String {
        if (!AppLanguage.isKorean(context)) return line
        val taskLine = translateTaskLine(line) ?: line
        var text = translateCompilerErrors(taskLine)
        regexReplacements.forEach { (regex, replacement) ->
            text = regex.replace(text, replacement)
        }
        text = translateTaskPathInsideText(text)
        replacements.forEach { (from, to) ->
            text = text.replace(from, to)
        }
        return text
    }

    private fun translateTaskLine(line: String): String? {
        val match = Regex("""^(\s*>?\s*Task\s+)(:\S+)(.*)$""").find(line) ?: return null
        val prefix = match.groupValues[1].replace("Task", "작업")
        val taskPath = match.groupValues[2]
        val suffix = match.groupValues[3]
        return "$prefix${translateTaskPath(taskPath)}$suffix"
    }

    private fun translateTaskPath(taskPath: String): String {
        val bracket = taskPath.substringAfter('[', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() && taskPath.contains('[') }
            ?.let { "[${it.substringBefore(']')}]" }
            .orEmpty()
        val withoutBracket = taskPath.substringBefore('[')
        val parts = withoutBracket.split(':').toMutableList()
        if (parts.isEmpty()) return taskPath
        val taskName = parts.last()
        val translated = translateTaskName(taskName)
        parts[parts.lastIndex] = translated + bracket
        return parts.joinToString(":")
    }

    private fun translateTaskName(taskName: String): String =
        taskDescriptions.entries.firstOrNull { taskName.contains(it.key) }?.value ?: taskName

    private fun translateTaskPathInsideText(text: String): String =
        Regex("""'(:[^']+)'""").replace(text) { match ->
            "'${translateTaskPath(match.groupValues[1])}'"
        }

    private fun translateCompilerErrors(text: String): String {
        var next = text
        compilerRegexReplacements.forEach { (regex, transform) ->
            next = regex.replace(next) { transform(it) }
        }
        return next
    }

    private val taskDescriptions = linkedMapOf(
        "preDebugBuild" to "Debug 빌드 준비",
        "preBuild" to "빌드 준비",
        "mergeDebugNativeDebugMetadata" to "네이티브 Debug 메타데이터 병합",
        "dataBindingMergeDependencyArtifactsDebug" to "DataBinding 의존성 병합",
        "generateDebugResValues" to "Debug 리소스 값 생성",
        "generateDebugResources" to "Debug 리소스 생성",
        "mergeDebugResources" to "Debug 리소스 병합",
        "packageDebugResources" to "Debug 리소스 패키징",
        "parseDebugLocalResources" to "로컬 리소스 분석",
        "dataBindingGenBaseClassesDebug" to "DataBinding 기본 클래스 생성",
        "checkDebugAarMetadata" to "AAR 메타데이터 검증",
        "mapDebugSourceSetPaths" to "소스셋 경로 매핑",
        "createDebugCompatibleScreenManifests" to "호환 화면 매니페스트 생성",
        "extractDeepLinksDebug" to "딥링크 추출",
        "processDebugMainManifest" to "메인 매니페스트 처리",
        "processDebugManifestForPackage" to "패키지 매니페스트 처리",
        "processDebugManifest" to "매니페스트 처리",
        "processDebugResources" to "리소스 처리",
        "compileDebugKotlin" to "Kotlin 컴파일",
        "javaPreCompileDebug" to "Java 사전 컴파일",
        "compileDebugJavaWithJavac" to "Java 컴파일",
        "mergeDebugShaders" to "셰이더 병합",
        "compileDebugShaders" to "셰이더 컴파일",
        "generateDebugAssets" to "에셋 생성",
        "mergeDebugAssets" to "에셋 병합",
        "compressDebugAssets" to "에셋 압축",
        "desugarDebugFileDependencies" to "Java 8+ 문법 변환",
        "dexBuilderDebug" to "DEX 생성",
        "mergeDebugGlobalSynthetics" to "전역 synthetic 병합",
        "processDebugJavaRes" to "Java 리소스 처리",
        "mergeDebugJavaResource" to "Java 리소스 병합",
        "checkDebugDuplicateClasses" to "중복 클래스 검증",
        "mergeExtDexDebug" to "외부 라이브러리 DEX 병합",
        "mergeLibDexDebug" to "라이브러리 DEX 병합",
        "mergeProjectDexDebug" to "프로젝트 DEX 병합",
        "configureCMakeDebug" to "CMake 설정",
        "buildCMakeDebug" to "CMake 빌드",
        "externalNativeBuildDebug" to "네이티브 빌드",
        "mergeDebugJniLibFolders" to "JNI 라이브러리 폴더 병합",
        "mergeDebugNativeLibs" to "네이티브 라이브러리 병합",
        "stripDebugDebugSymbols" to "Debug 심볼 제거",
        "validateSigningDebug" to "인증 설정 검증",
        "writeDebugAppMetadata" to "앱 메타데이터 생성",
        "writeDebugSigningConfigVersions" to "인증 설정 버전 생성",
        "packageDebug" to "APK 패키징",
        "assembleDebug" to "Debug APK 조립",
        "lintDebug" to "Lint 검증",
        "checkDebug" to "Debug 검증"
    )

    private val replacements = listOf(
        "> Build started:" to "> 빌드 시작:",
        "> Configure project" to "> 프로젝트 설정",
        "BUILD SUCCESSFUL" to "빌드 성공",
        "BUILD FAILED" to "빌드 실패",
        "FAILURE: Build failed with an exception." to "실패: 예외로 인해 빌드가 실패했습니다.",
        "* What went wrong:" to "* 무엇이 문제였는가:",
        "* Try:" to "* 시도하십시오:",
        "Execution failed for task" to "태스크 실행 실패",
        "A problem occurred starting process" to "프로세스 시작 중 문제 발생",
        "Could not start" to "시작할 수 없습니다",
        "Cannot run program" to "프로그램을 실행할 수 없습니다",
        "Permission denied" to "권한 거부",
        "finished with non-zero exit value" to "0이 아닌 종료 코드로 종료됨",
        "Run with --stacktrace option to get the stack trace." to "--stacktrace 옵션으로 스택 트레이스를 확인하십시오.",
        "Run with --info or --debug option to get more log output." to "--info 또는 --debug 옵션으로 더 많은 로그를 확인하십시오.",
        "Get more help at" to "도움말:",
        "Checking the license for package" to "패키지 라이선스 확인 중",
        "License for package" to "패키지 라이선스",
        "accepted." to "수락됨.",
        "Preparing" to "준비 중",
        "Installing" to "설치 중",
        "complete." to "완료.",
        "finished." to "종료.",
        "actionable tasks" to "개의 실행 가능한 태스크",
        "executed" to "실행됨",
        "from cache" to "캐시 사용",
        "up-to-date" to "최신 상태",
        "NO-SOURCE" to "태스크 없음",
        "FROM-CACHE" to "캐시에서 가져옴",
        "UP-TO-DATE" to "최신 상태",
        "WARNING:" to "경고:",
        "error:" to "오류:",
        "Caused by:" to "원인:",
        "> NDK/CMake project detected: preparing MobileStudio native build bridge" to "> NDK/CMake 프로젝트 감지: MobileStudio 네이티브 빌드 브리지 준비",
        "> NDK not found" to "> NDK를 찾을 수 없음",
        "> NDK native build enabled" to "> NDK 네이티브 빌드 활성화됨",
        "Android-hosted LLVM not found" to "Android-hosted LLVM을 찾을 수 없음",
        "Android-hosted LLVM installed:" to "Android-hosted LLVM 설치됨:",
        "installed and activated. Restarting build..." to "설치 및 활성화됨. 빌드를 다시 시작합니다..."
    )

    private val compilerRegexReplacements = listOf<Pair<Regex, (MatchResult) -> String>>(
        Regex("""Unresolved reference ['"]([^'"]+)['"]\.?""") to { m ->
            "${m.groupValues[1]}은/는 해결되지 않은 참조입니다"
        },
        Regex("""Unresolved reference: ([A-Za-z_][A-Za-z0-9_.$]*)""") to { m ->
            "${m.groupValues[1]}은/는 해결되지 않은 참조입니다"
        },
        Regex("""Cannot access '([^']+)': it is (private|protected|internal) in '([^']+)'""") to { m ->
            "${m.groupValues[1]}에 접근할 수 없습니다. ${m.groupValues[3]}에 ${visibilityKo(m.groupValues[2])}입니다"
        },
        Regex("""Type mismatch: inferred type is ([^ ]+) but ([^ ]+) was expected""") to { m ->
            "유형이 맞지 않습니다. 실제 유형은 ${m.groupValues[1]}이고, 필요한 유형은 ${m.groupValues[2]}입니다"
        },
        Regex("""Not enough information to infer type variable ([A-Za-z_][A-Za-z0-9_]*)""") to { m ->
            "유형 변수 ${m.groupValues[1]}을/를 추론할 정보가 부족합니다."
        },
        Regex("""No value passed for parameter '([^']+)'""") to { m ->
            "매개변수 ${m.groupValues[1]}에 전달된 값이 없습니다."
        },
        Regex("""Too many arguments for .*""") to {
            "인자가 너무 많습니다."
        },
        Regex("""Expecting (.+)""") to { m ->
            "${m.groupValues[1]}이/가 필요합니다."
        },
        Regex("""Class '([^']+)' is not abstract and does not implement abstract member (.+)""") to { m ->
            "${m.groupValues[1]} 클래스는 abstract가 아니지만 추상 멤버 ${m.groupValues[2]}을/를 구현하지 않았습니다."
        },
        Regex("""Package '([^']+)' cannot be imported""") to { m ->
            "${m.groupValues[1]} 패키지를 import할 수 없습니다."
        },
        Regex("""Manifest merger failed""") to {
            "매니페스트 병합이 실패했습니다."
        },
        Regex("""Android resource linking failed""") to {
            "Android 리소스 연결이 실패했습니다."
        },
        Regex("""resource ([^ ]+) not found""") to { m ->
            "리소스 ${m.groupValues[1]}을/를 찾을 수 없습니다."
        }
    )

    private val regexReplacements = listOf(
        Regex("""BUILD FAILED in ([0-9A-Za-z가-힣s]+)""") to "빌드 실패: $1",
        Regex("""BUILD SUCCESSFUL in ([0-9A-Za-z가-힣s]+)""") to "빌드 성공: $1",
        Regex("""(\d+) actionable tasks: """) to "$1개의 실행 가능한 태스크: ",
        Regex("""Execution failed for task ('(:[^']+)')\.""") to "$1 태스크 실행 실패.",
        Regex("""Task ('(:[^']+)') not found""") to "$1 태스크를 찾을 수 없습니다"
    )

    private fun visibilityKo(value: String): String = when (value) {
        "private" -> "private(비공개)"
        "protected" -> "protected(하위/패키지 한정)"
        "internal" -> "internal(모듈 한정)"
        else -> value
    }
}
