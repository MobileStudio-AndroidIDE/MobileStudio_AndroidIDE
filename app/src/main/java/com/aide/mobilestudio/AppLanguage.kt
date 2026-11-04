package com.aide.mobilestudio

import android.content.Context
import java.io.File
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AppLanguage {
    data class Language(val code: String, val nativeName: String)
    data class LanguagePack(
        val language: Language,
        val keys: Map<String, String> = emptyMap(),
        val literals: Map<String, String> = emptyMap()
    )

    /**
     * Literal packs shipped as JSON under assets/i18n/<code>.json so translations
     * can be maintained (and machine-translated) outside the Kotlin source. The
     * first five languages keep their hand-tuned maps in this file; everything
     * below loads from assets.
     */
    private val jsonPackCodes = listOf(
        "de", "fr", "it", "pt", "ru", "ar", "hi", "id", "th", "vi",
        "tr", "nl", "pl", "sv", "uk", "fa", "he", "ms", "bn", "tl",
        "cs", "el", "ro", "hu", "da", "fi", "nb", "sk", "hr", "bg"
    )

    private val packs: MutableMap<String, LanguagePack> by lazy {
        linkedMapOf(
            "ko" to LanguagePack(
                language = Language("ko", "한국어"),
                keys = mapOf(
                    "language" to "언어",
                    "build_tools" to "빌드 도구",
                    "close" to "닫기",
                    "logcat_viewer" to "로그캣 뷰어",
                    "no_problems" to "문제 없음",
                    "no_problem_details" to "최근 검사에서 문제가 전혀 발견되지 않았습니다.",
                    "python_toolchain" to "Python 툴체인",
                    "python_buildozer_template" to "Python Buildozer 템플릿",
                    "update_check" to "업데이트 확인",
                    "check_update_now" to "업데이트 확인",
                    "no_update" to "최신 버전입니다"
                ),
                literals = koreanLiterals
            ),
            "en" to LanguagePack(language = Language("en", "English")),
            "ja" to LanguagePack(
                language = Language("ja", "日本語"),
                literals = japaneseLiterals
            ),
            "zh" to LanguagePack(
                language = Language("zh", "中文"),
                literals = chineseLiterals
            ),
            "es" to LanguagePack(
                language = Language("es", "Espanol"),
                literals = spanishLiterals
            )
        ).apply {
            jsonPackCodes.forEach { code -> loadJsonPack(code)?.let { put(code, it) } }
        }
    }

    /** Reads assets/i18n/<code>.json. A missing/corrupt pack is skipped, never fatal. */
    private var assetContext: Context? = null

    @Synchronized
    private fun loadJsonPack(code: String): LanguagePack? {
        val ctx = assetContext ?: return null
        return try {
            val json = ctx.assets.open("i18n/$code.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val o = JSONObject(json)
            val lang = o.optJSONObject("language")
            val nativeName = lang?.optString("nativeName").orEmpty().ifBlank { code }
            val litObj = o.optJSONObject("literals") ?: return null
            val literals = HashMap<String, String>()
            val it = litObj.keys()
            while (it.hasNext()) {
                val k = it.next()
                val v = litObj.optString(k, "")
                if (v.isNotBlank()) literals[k] = v
            }
            if (literals.isEmpty()) return null
            LanguagePack(Language(code, nativeName), literals = literals)
        } catch (e: Exception) {
            null
        }
    }

    /** Assets are needed for the JSON packs; call once from Application/activity. */
    fun bindAssets(context: Context) {
        assetContext = context.applicationContext
        // Re-materialise packs now that assets are reachable (no-op if already loaded).
        jsonPackCodes.forEach { code ->
            if (!packs.containsKey(code)) loadJsonPack(code)?.let { packs[code] = it }
        }
    }

    private var translationApiKey: String = ""
    private var translationApiUrl: String = ""

    fun configureTranslationApi(apiKey: String, apiUrl: String = "https://openapi.naver.com/v1/papago/n2mt") {
        translationApiKey = apiKey
        translationApiUrl = apiUrl
    }

    /**
     * Translates on the fly.
     *
     * Engines, in order:
     *  1. a user-configured API (Naver Papago by default) — needs a key;
     *  2. MyMemory — free, keyless, rate-limited; the default so every one of
     *     the 30+ UI languages and any extension can be translated without
     *     the user setting anything up.
     * Returns [text] unchanged when every engine fails, so a missing key or a
     * network hiccup degrades to English/original instead of breaking the UI.
     */
    fun translateViaApi(text: String, targetLang: String): String {
        if (text.isBlank()) return text
        configuredTranslate(text, targetLang)?.let { return it }
        return myMemoryTranslate(text, targetLang) ?: text
    }

    /** Naver Papago (or whatever endpoint the user configured). Null when unusable. */
    private fun configuredTranslate(text: String, targetLang: String): String? {
        if (translationApiKey.isBlank()) return null
        return try {
            val url = URL(translationApiUrl.ifBlank { "https://openapi.naver.com/v1/papago/n2mt" })
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.setRequestProperty("X-Naver-Client-Id", translationApiKey.substringBefore(":"))
            conn.setRequestProperty("X-Naver-Client-Secret", translationApiKey.substringAfter(":"))
            val source = when (targetLang) { "ja", "zh", "es" -> "ko"; else -> "auto" }
            val params = "source=$source&target=${naverTarget(targetLang)}&text=${URLEncoder.encode(text, "UTF-8")}"
            conn.outputStream.use { it.write(params.toByteArray()) }
            val response = conn.inputStream.bufferedReader().readText()
            JSONObject(response).getJSONObject("message").getJSONObject("result").getString("translatedText")
        } catch (e: Exception) {
            null
        }
    }

    private fun naverTarget(code: String) = when (code) {
        "zh" -> "zh-CN"; "pt" -> "pt"; else -> code
    }

    /** MyMemory: free and keyless. Null on any failure so callers can fall back. */
    private fun myMemoryTranslate(text: String, targetLang: String): String? {
        // Literals are keyed by English source, so English is the safe source.
        val source = "en"
        val target = when (targetLang) { "zh" -> "zh"; "tl" -> "tl"; else -> targetLang }
        return try {
            val url = URL(
                "https://api.mymemory.translated.net/get?q=" + URLEncoder.encode(text, "UTF-8") +
                    "&langpair=$source|$target"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000
            conn.readTimeout = 15_000
            val response = conn.inputStream.bufferedReader().readText()
            val translated = JSONObject(response)
                .optJSONObject("responseData")?.optString("translatedText").orEmpty()
            // MyMemory echoes the query back when it has no translation.
            if (translated.isBlank() || translated.equals(text, ignoreCase = true)) null else translated
        } catch (e: Exception) {
            null
        }
    }

    /** App language code → translation-engine target code. */
    private fun engineTarget(code: String): String? = when (code) {
        "en", "ko" -> null // source languages, never translated
        else -> code
    }

    // For non-Korean source, translate via API
    fun translateText(context: Context, text: String): String {
        val code = current(context).code
        val target = engineTarget(code) ?: return text
        val pack = packs[code] ?: return text
        val cached = pack.literals[text]
        if (cached != null) return cached
        return translateViaApi(text, target)
    }

    val supported: List<Language>
        get() = packs.values.map { it.language }

    fun registerLanguagePack(pack: LanguagePack) {
        packs[pack.language.code] = pack
    }

    fun current(context: Context): Language {
        val code = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("app_language", "ko")
        return packs[code]?.language ?: packs.getValue("ko").language
    }

    fun isKorean(context: Context): Boolean = current(context).code == "ko"

    fun set(context: Context, code: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("app_language", code)
            .apply()
    }

    fun text(context: Context, key: String): String {
        val code = current(context).code
        return packs[code]?.keys?.get(key)
            ?: packs["en"]?.keys?.get(key)
            ?: key
    }

    fun literal(context: Context, value: String): String {
        val code = current(context).code
        val pack = packs[code] ?: return value
        // English is the source of the literal table — nothing to translate.
        if (code == "en") return value
        val cached = pack.literals[value]
        if (cached != null) return cached
        // Not in the shipped pack: translate on the fly (keyless engine by default).
        val target = engineTarget(code) ?: return value
        val apiResult = translateViaApi(value, target)
        if (apiResult != value && apiResult.isNotBlank()) return apiResult
        return if (code == "ko") translateKoreanDynamic(value) else value
    }

    fun translateBuildLog(context: Context, line: String): String {
        val code = current(context).code
        return when (code) {
            "ko" -> translateBuildLogKorean(line)
            "ja" -> translateBuildLogJapanese(line)
            "zh" -> translateBuildLogChinese(line)
            "es" -> translateBuildLogSpanish(line)
            else -> line
        }
    }

    private fun translateBuildLogKorean(line: String): String {
        var text = line
        koreanGradleTaskTranslations.forEach { (from, to) -> text = text.replace(from, to) }
        koreanBuildLogTranslations.forEach { (from, to) -> text = text.replace(from, to) }
        unresolvedReference.find(text)?.let { match ->
            val symbol = match.groupValues[1]
            text = text.replace(match.value, "$symbol 은/는 해결할 수 없는 참조입니다")
        }
        cannotRunProgram.find(text)?.let { match ->
            text = text.replace(match.value, "프로그램을 실행할 수 없습니다: ${match.groupValues[1]}")
        }
        return text
    }

    private fun translateBuildLogJapanese(line: String): String {
        var text = line
        // Keep gradle task names as-is (universal), translate only common messages
        buildLogTranslationsJa.forEach { (from, to) -> text = text.replace(from, to) }
        return text
    }

    private fun translateBuildLogChinese(line: String): String {
        var text = line
        buildLogTranslationsZh.forEach { (from, to) -> text = text.replace(from, to) }
        return text
    }

    private fun translateBuildLogSpanish(line: String): String {
        var text = line
        buildLogTranslationsEs.forEach { (from, to) -> text = text.replace(from, to) }
        return text
    }

    private fun translateKoreanDynamic(value: String): String {
        var text = value
        dynamicReplacements.forEach { (from, to) -> text = text.replace(from, to) }
        return text
    }

    private val unresolvedReference = Regex("""Unresolved reference ['\"]?([^'\"\s.]+)['\"]?""")
    private val cannotRunProgram = Regex("""Cannot run program \"([^\"]+)\"""")

    private val koreanGradleTaskTranslations = mapOf(
        "Task :app:preDebugBuild" to "작업 :app:디버그 빌드 준비",
        "Task :app:mergeDebugNativeDebugMetadata" to "작업 :app:네이티브 디버그 메타데이터 병합",
        "Task :app:dataBindingMergeDependencyArtifactsDebug" to "작업 :app:데이터바인딩 종속성 병합",
        "Task :app:generateDebugResValues" to "작업 :app:디버그 리소스 값 생성",
        "Task :app:generateDebugResources" to "작업 :app:디버그 리소스 생성",
        "Task :app:mergeDebugResources" to "작업 :app:디버그 리소스 병합",
        "Task :app:packageDebugResources" to "작업 :app:디버그 리소스 패키징",
        "Task :app:parseDebugLocalResources" to "작업 :app:로컬 리소스 분석",
        "Task :app:checkDebugAarMetadata" to "작업 :app:AAR 메타데이터 확인",
        "Task :app:mapDebugSourceSetPaths" to "작업 :app:소스셋 경로 매핑",
        "Task :app:createDebugCompatibleScreenManifests" to "작업 :app:호환 화면 매니페스트 생성",
        "Task :app:extractDeepLinksDebug" to "작업 :app:딥링크 추출",
        "Task :app:processDebugMainManifest" to "작업 :app:메인 매니페스트 처리",
        "Task :app:processDebugManifest" to "작업 :app:매니페스트 처리",
        "Task :app:processDebugManifestForPackage" to "작업 :app:패키지용 매니페스트 처리",
        "Task :app:processDebugResources" to "작업 :app:리소스 처리",
        "Task :app:compileDebugKotlin" to "작업 :app:Kotlin 컴파일",
        "Task :app:javaPreCompileDebug" to "작업 :app:Java 사전 컴파일",
        "Task :app:compileDebugJavaWithJavac" to "작업 :app:Java 컴파일",
        "Task :app:mergeDebugShaders" to "작업 :app:셰이더 병합",
        "Task :app:compileDebugShaders" to "작업 :app:셰이더 컴파일",
        "Task :app:generateDebugAssets" to "작업 :app:에셋 생성",
        "Task :app:mergeDebugAssets" to "작업 :app:에셋 병합",
        "Task :app:compressDebugAssets" to "작업 :app:에셋 압축",
        "Task :app:desugarDebugFileDependencies" to "작업 :app:파일 종속성 디슈가링",
        "Task :app:dexBuilderDebug" to "작업 :app:Dex 빌드",
        "Task :app:mergeDebugGlobalSynthetics" to "작업 :app:전역 합성 클래스 병합",
        "Task :app:processDebugJavaRes" to "작업 :app:Java 리소스 처리",
        "Task :app:mergeDebugJavaResource" to "작업 :app:Java 리소스 병합",
        "Task :app:checkDebugDuplicateClasses" to "작업 :app:중복 클래스 확인",
        "Task :app:mergeExtDexDebug" to "작업 :app:외부 Dex 병합",
        "Task :app:mergeLibDexDebug" to "작업 :app:라이브러리 Dex 병합",
        "Task :app:mergeProjectDexDebug" to "작업 :app:프로젝트 Dex 병합",
        "Task :app:configureCMakeDebug" to "작업 :app:CMake 구성",
        "Task :app:buildCMakeDebug" to "작업 :app:CMake 빌드",
        "Task :app:assembleDebug" to "작업 :app:디버그 APK 생성",

        "Task :app:preBuild" to "작업 :app:빌드 준비",
        "Task :app:clean" to "작업 :app:클린",
        "Task :app:generateDebugBuildConfig" to "작업 :app:BuildConfig 생성",
        "Task :app:kaptGenerateStubsDebugKotlin" to "작업 :app:Kapt 스텁 생성",
        "Task :app:kaptDebugKotlin" to "작업 :app:Kapt 어노테이션 처리",
        "Task :app:stripDebugDebugSymbols" to "작업 :app:네이티브 디버그 심볼 제거",
        "Task :app:mergeDebugNativeLibs" to "작업 :app:네이티브 라이브러리 병합",
        "Task :app:mergeLibDexDebug" to "작업 :app:라이브러리 Dex 병합",
        "Task :app:mergeProjectDexDebug" to "작업 :app:프로젝트 Dex 병합",
        "Task :app:configureCMakeDebug" to "작업 :app:CMake 구성",
        "Task :app:buildCMakeDebug" to "작업 :app:CMake 빌드",
        "Task :app:assembleDebug" to "작업 :app:디버그 APK 생성",

        "Task :app:preBuild" to "작업 :app:빌드 준비",
        "Task :app:clean" to "작업 :app:클린",
        "Task :app:generateDebugBuildConfig" to "작업 :app:BuildConfig 생성",
        "Task :app:kaptGenerateStubsDebugKotlin" to "작업 :app:Kapt 스텁 생성",
        "Task :app:kaptDebugKotlin" to "작업 :app:Kapt 어노테이션 처리",
        "Task :app:stripDebugDebugSymbols" to "작업 :app:네이티브 디버그 심볼 제거",
        "Task :app:mergeDebugNativeLibs" to "작업 :app:네이티브 라이브러리 병합",
        "Task :app:mergeLibDexDebug" to "작업 :app:라이브러리 Dex 병합",
        "Task :app:mergeProjectDexDebug" to "작업 :app:프로젝트 Dex 병합",
        "Task :app:configureCMakeDebug" to "작업 :app:CMake 구성",
        "Task :app:buildCMakeDebug" to "작업 :app:CMake 빌드",
        "Task :app:assembleDebug" to "작업 :app:디버그 APK 생성",

        "Task :app:preBuild" to "작업 :app:빌드 준비",
        "Task :app:clean" to "작업 :app:클린",
        "Task :app:generateDebugBuildConfig" to "작업 :app:BuildConfig 생성",
        "Task :app:kaptGenerateStubsDebugKotlin" to "작업 :app:Kapt 스텁 생성",
        "Task :app:kaptDebugKotlin" to "작업 :app:Kapt 어노테이션 처리",
        "Task :app:stripDebugDebugSymbols" to "작업 :app:네이티브 디버그 심볼 제거",
        "Task :app:mergeDebugNativeLibs" to "작업 :app:네이티브 라이브러리 병합",
        "Task :app:mergeLibDexDebug" to "작업 :app:라이브러리 Dex 병합",
        "Task :app:mergeProjectDexDebug" to "작업 :app:프로젝트 Dex 병합",
        "Task :app:configureCMakeDebug" to "작업 :app:CMake 구성",
        "Task :app:buildCMakeDebug" to "작업 :app:CMake 빌드",
        "Task :app:assembleDebug" to "작업 :app:디버그 APK 생성"
    )

    private val koreanBuildLogTranslations = mapOf(
        "BUILD FAILED" to "빌드 실패",
        "BUILD SUCCESSFUL" to "빌드 성공",
        "FAILURE: Build failed with an exception." to "실패: 예외 발생으로 빌드가 실패했습니다.",
        "* What went wrong:" to "* 문제 원인:",
        "* Try:" to "* 시도해 볼 것:",
        "Execution failed for task" to "작업 실행 실패",
        "A problem occurred starting process" to "프로세스를 시작하는 중 문제가 발생했습니다",
        "Permission denied" to "권한이 거부되었습니다",
        "finished with non-zero exit value" to "0이 아닌 종료 코드로 종료되었습니다",
        "UP-TO-DATE" to "최신 상태",
        "FROM-CACHE" to "캐시에서 가져옴",
        "NO-SOURCE" to "소스 없음",
        "WARNING:" to "경고:",
        "error:" to "오류:",
        "warning:" to "경고:"
    )

    private val buildLogTranslationsJa = mapOf(
        "BUILD FAILED" to "ビルド失敗", "BUILD SUCCESSFUL" to "ビルド成功",
        "FAILURE: Build failed with an exception." to "失敗: 例外によりビルドが失敗しました。",
        "* What went wrong:" to "* 問題の原因:", "* Try:" to "* 対処法:",
        "Execution failed for task" to "タスクの実行に失敗しました",
        "Permission denied" to "権限が拒否されました",
        "finished with non-zero exit value" to "ゼロ以外の終了コードで終了しました",
        "UP-TO-DATE" to "最新", "FROM-CACHE" to "キャッシュから取得",
        "NO-SOURCE" to "ソースなし", "WARNING:" to "警告:",
        "error:" to "エラー:", "warning:" to "警告:"
    )

    private val buildLogTranslationsZh = mapOf(
        "BUILD FAILED" to "构建失败", "BUILD SUCCESSFUL" to "构建成功",
        "FAILURE: Build failed with an exception." to "失败：由于异常导致构建失败。",
        "* What went wrong:" to "* 出错原因：", "* Try:" to "* 请尝试：",
        "Execution failed for task" to "任务执行失败",
        "Permission denied" to "权限被拒绝",
        "finished with non-zero exit value" to "以非零退出值结束",
        "UP-TO-DATE" to "已是最新", "FROM-CACHE" to "来自缓存",
        "NO-SOURCE" to "无源文件", "WARNING:" to "警告：",
        "error:" to "错误：", "warning:" to "警告："
    )

    private val buildLogTranslationsEs = mapOf(
        "BUILD FAILED" to "COMPILACION FALLIDA", "BUILD SUCCESSFUL" to "COMPILACION EXITOSA",
        "FAILURE: Build failed with an exception." to "FALLO: La compilacion fallo con una excepcion.",
        "* What went wrong:" to "* Lo que salio mal:", "* Try:" to "* Intente:",
        "Execution failed for task" to "Ejecucion fallida de la tarea",
        "Permission denied" to "Permiso denegado",
        "finished with non-zero exit value" to "finalizo con valor de salida distinto de cero",
        "UP-TO-DATE" to "ACTUALIZADO", "FROM-CACHE" to "DESDE CACHE",
        "NO-SOURCE" to "SIN FUENTE", "WARNING:" to "ADVERTENCIA:",
        "error:" to "error:", "warning:" to "advertencia:"
    )

    private val dynamicReplacements = listOf(
        "Crash logs:" to "크래시 로그:",
        "Missing:" to "누락:",
        "Path:" to "경로:",
        "Download:" to "다운로드:",
        "Target:" to "대상:",
        "Download size:" to "다운로드 크기:",
        "Free space:" to "여유 공간:",
        "Start download?" to "다운로드를 시작할까요?",
        "Already installed" to "이미 설치됨",
        "Download started" to "다운로드 시작",
        "Download failed" to "다운로드 실패",
        "Install and activate an SDK first" to "먼저 SDK를 설치하고 활성화하세요",
        "Manual install is required for this version" to "이 버전은 수동 설치가 필요합니다",
        "Copied" to "복사됨",
        "Delete " to "삭제: ",
        "Install " to "설치: ",
        "Download " to "다운로드: ",
        "Device RAM:" to "기기 RAM:",
        "App heap limit:" to "앱 heap 한도:",
        "Gradle heap:" to "Gradle heap:",
        "Gradle heap set to " to "Gradle heap 설정: "
    )

    private val koreanLiterals = mapOf(
        "File" to "파일",
        "Edit" to "편집",
        "View" to "보기",
        "Navigate" to "이동",
        "Code" to "코드",
        "Refactor" to "리팩토링",
        "Build" to "빌드",
        "Run" to "실행",
        "Tools" to "도구",
        "IDE" to "IDE",
        "Saved" to "저장됨",
        "Build Output" to "빌드 출력",
        "Shell Output" to "쉘 출력",
        "No output yet." to "아직 출력 없음.",
        "Run JAR" to "JAR 실행",
        "Copy JAR path" to "JAR 경로 복사",
        "Explorer" to "탐색기",
        "Cancel back" to "취소",
        "Clean build" to "Clean 빌드",
        "Downloads" to "다운로드",
        "Preview" to "미리보기",
        "Chat" to "대화",
        "Pause logcat" to "로그캣 중단",
        "Clear logcat" to "로그캣 지우기",
        "filter..." to "필터 설정...",
        "Clear" to "지우기",
        "Hide" to "숨기기",
        "Build Tools" to "빌드 도구",
        "Shell" to "쉘",
        "Problems" to "오류",
        "Search" to "검색",
        "Compile" to "컴파일",
        "Outputs" to "출력",
        "Logcat" to "로그캣",
        "PIP" to "PIP",
        "Project" to "프로젝트",
        "Open Project" to "프로젝트 열기",
        "New Project" to "새 프로젝트 만들기",
        "Recent Projects" to "최근 프로젝트",
        "Recent Files" to "최근 파일",
        "File Explorer" to "파일 탐색기",
        "Project Explorer" to "프로젝트 탐색기",
        "Open Build Tools" to "빌드 도구 열기",
        "Extensions" to "확장",
        "MobileStudio Community" to "MobileStudio 커뮤니티",
        "Open Shell Panel" to "쉘 패널 열기",
        "Undo" to "뒤로가기",
        "Redo" to "뒤로가기 취소",
        "Find / Replace" to "검색 및 교체",
        "Duplicate Line" to "중복된 줄",
        "Delete Line" to "줄 삭제",
        "XML Preview" to "XML 미리보기",
        "Back" to "뒤로가기",
        "Current Project" to "현재 프로젝트",
        "Current File Preview" to "현재 파일 미리보기",
        "Format Code" to "코드 서식 정리",
        "Toggle Comment" to "주석 전환",
        "Auto Import" to "Auto Import",
        "Parameter Hint" to "매개변수 힌트",
        "Quick Documentation" to "빠른 문서 보기",
        "Quick Fix" to "빠른 수정",
        "Move Line Up" to "줄 위로 이동",
        "Move Line Down" to "줄 아래로 이동",
        "Rename Symbol" to "심볼 이름 변경",
        "Rename File" to "파일 이름 변경",
        "Use Project Explorer long press to rename" to "이름을 변경하려면 프로젝트 탐색기를 길게 누르세요.",
        "Sync Now" to "동기화",
        "Run assembleDebug" to "assembleDebug 실행",
        "Stop Build" to "빌드 중지",
        "Analyzing..." to "분석중...",
        "No errors" to "오류 없음",
        "error(s)" to "오류",
        "warning(s)" to "경고",
        "Auto Pair" to "자동 괄호 완성",
        "Bracket Pair Highlight" to "괄호 쌍 강조",
        "Horizontal Scroll" to "가로 스크롤",
        "Code wrap is disabled" to "코드 줄 바꿈이 비활성화되어있습니다.",
        "Community" to "커뮤니티",
        "Save" to "저장",
        "Find" to "검색",
        "Format" to "서식 정리",
        "Rebuild" to "재빌드",
        "Clean" to "정리",
        "Stop" to "중지",
        "Install APK" to "APK 설치",
        "Refresh" to "새로고침",
        "Delete" to "삭제",
        "Activate" to "활성화",
        "Browse" to "검색",
        "Install" to "설치",
        "Manual" to "수동",
        "Add" to "추가",
        "Remove" to "제거",
        "Open Folder" to "폴더 열기",
        "Import Android Studio ZIP" to "Android Studio ZIP 가져오기",
        "Install Bundled" to "번들 설치",
        "Python + pip" to "Python + pip",
        "OpenCV" to "OpenCV",
        "Custom URL" to "사용자 설정 URL",
        "Licenses" to "라이선스",
        "SDK Install" to "SDK 설치",
        "Ready" to "설치됨",
        "Not installed" to "설치되지 않음",
        "Optional" to "선택",
        "Active" to "활성화",
        "PATH" to "경로",
        "Not selected" to "선택되지 않음",
        "OK" to "OK",
        "NO" to "NO",
        "Cancel" to "취소",
        "Close" to "닫기",
        "Copy" to "복사",
        "Check Size" to "크기 확인",
        "Open Shell" to "쉘 열기",
        "Download Android LLVM (NDK)" to "Android LLVM(NDK) 설치",
        "Download Android-hosted LLVM" to "Android-hosted LLVM 설치",
        "Android LLVM" to "Android LLVM",
        "No remote versions loaded yet." to "아직 원격 버전이 로드되지 않았습니다.",
        "Diagnostics & Performance" to "진단 및 성능",
        "Gradle build memory (more heap for large projects)" to "Gradle 빌드 메모리(대형 프로젝트에 더 많은 힙)",
        "Auto" to "자동",
        "Performance mode: Gradle uses 2 workers when possible" to "성능 모드: Gradle는 가능한 경우 워커 2개 사용",
        "Verbose build logs: keep Gradle debug output visible" to "상세 빌드 로그: Gradle 디버그 출력을 계속 표시합니다"
    )

    private val japaneseLiterals = mapOf(
        "File" to "ファイル", "Edit" to "編集", "View" to "表示", "Navigate" to "ナビゲート",
        "Code" to "コード", "Refactor" to "リファクタリング", "Build" to "ビルド", "Run" to "実行",
        "Tools" to "ツール", "IDE" to "IDE", "Saved" to "保存済み", "Build Output" to "ビルド出力",
        "Shell Output" to "シェル出力", "No output yet." to "まだ出力がありません。",
        "Run JAR" to "JAR実行", "Copy JAR path" to "JARパスをコピー", "Explorer" to "エクスプローラー",
        "Cancel back" to "キャンセル", "Clean build" to "クリーンビルド", "Downloads" to "ダウンロード",
        "Preview" to "プレビュー", "Chat" to "チャット", "Pause logcat" to "Logcat一時停止",
        "Clear logcat" to "Logcatをクリア", "filter..." to "フィルター...", "Clear" to "クリア",
        "Hide" to "隠す", "Build Tools" to "ビルドツール", "Shell" to "シェル", "Problems" to "問題",
        "Search" to "検索", "Compile" to "コンパイル", "Outputs" to "出力", "Logcat" to "Logcat",
        "PIP" to "PIP", "Project" to "プロジェクト", "Open Project" to "プロジェクトを開く",
        "Recent Projects" to "最近のプロジェクト", "Recent Files" to "最近のファイル",
        "New Project" to "新規プロジェクト", "File Explorer" to "ファイルエクスプローラー",
        "Project Explorer" to "プロジェクトエクスプローラー", "Open Build Tools" to "ビルドツールを開く",
        "Extensions" to "拡張機能", "MobileStudio Community" to "MobileStudioコミュニティ",
        "Open Shell Panel" to "シェルパネルを開く", "Undo" to "元に戻す", "Redo" to "やり直す",
        "Find / Replace" to "検索・置換", "Duplicate Line" to "行を複製", "Delete Line" to "行を削除",
        "XML Preview" to "XMLプレビュー", "Back" to "戻る", "Current Project" to "現在のプロジェクト",
        "Current File Preview" to "現在のファイルのプレビュー", "Format Code" to "コード整形",
        "Toggle Comment" to "コメント切替", "Auto Import" to "自動インポート",
        "Parameter Hint" to "パラメータヒント", "Quick Documentation" to "クイックドキュメント",
        "Quick Fix" to "クイックフィックス", "Move Line Up" to "行を上に移動",
        "Move Line Down" to "行を下に移動", "Rename Symbol" to "シンボル名を変更",
        "Rename File" to "ファイル名を変更",
        "Use Project Explorer long press to rename" to "名前を変更するにはプロジェクトエクスプローラーを長押ししてください",
        "Sync Now" to "今すぐ同期", "Run assembleDebug" to "assembleDebugを実行",
        "Stop Build" to "ビルドを停止", "Analyzing..." to "解析中...", "No errors" to "エラーなし",
        "error(s)" to "エラー", "warning(s)" to "警告", "Auto Pair" to "自動ペア",
        "Bracket Pair Highlight" to "括弧のペア強調", "Horizontal Scroll" to "水平スクロール",
        "Code wrap is disabled" to "コードの折り返しが無効です",
        "Community" to "コミュニティ", "Save" to "保存", "Find" to "検索", "Format" to "整形",
        "Rebuild" to "リビルド", "Clean" to "クリーン", "Stop" to "停止", "Install APK" to "APKをインストール",
        "Refresh" to "更新", "Delete" to "削除", "Activate" to "有効化", "Browse" to "ブラウズ",
        "Install" to "インストール", "Manual" to "手動", "Add" to "追加", "Remove" to "削除",
        "Open Folder" to "フォルダを開く", "Import Android Studio ZIP" to "Android Studio ZIPをインポート",
        "Install Bundled" to "バンドル版をインストール", "Python + pip" to "Python + pip",
        "OpenCV" to "OpenCV", "Custom URL" to "カスタムURL", "Licenses" to "ライセンス",
        "SDK Install" to "SDKインストール", "Ready" to "準備完了", "Not installed" to "未インストール",
        "Optional" to "任意", "Active" to "有効", "PATH" to "パス",
        "Not selected" to "未選択", "OK" to "OK", "NO" to "いいえ", "Cancel" to "キャンセル",
        "Close" to "閉じる", "Copy" to "コピー", "Check Size" to "サイズ確認",
        "Open Shell" to "シェルを開く",
        "Download Android LLVM (NDK)" to "Android LLVM (NDK) をダウンロード",
        "Download Android-hosted LLVM" to "Androidホスト版LLVMをダウンロード",
        "Android LLVM" to "Android LLVM",
        "No remote versions loaded yet." to "リモートバージョンがまだ読み込まれていません。",
        "Diagnostics & Performance" to "診断とパフォーマンス",
        "Gradle build memory (more heap for large projects)" to "Gradleビルド内存（为大型项目提供更多堆空间）",
        "Auto" to "自动",
        "Performance mode: Gradle uses 2 workers when possible" to "性能模式：Gradle在可能时使用2个工作线程",
        "Verbose build logs: keep Gradle debug output visible" to "详细构建日志：保持Gradle调试输出可见"
    )

    private val chineseLiterals = mapOf(
        "File" to "文件", "Edit" to "编辑", "View" to "视图", "Navigate" to "导航",
        "Code" to "代码", "Refactor" to "重构", "Build" to "构建", "Run" to "运行",
        "Tools" to "工具", "IDE" to "IDE", "Saved" to "已保存", "Build Output" to "构建输出",
        "Shell Output" to "Shell输出", "No output yet." to "暂无输出。", "Run JAR" to "运行JAR",
        "Copy JAR path" to "复制JAR路径", "Explorer" to "资源管理器", "Cancel back" to "取消返回",
        "Clean build" to "清理构建", "Downloads" to "下载", "Preview" to "预览", "Chat" to "聊天",
        "Pause logcat" to "暂停logcat", "Clear logcat" to "清除logcat", "filter..." to "过滤...",
        "Clear" to "清除", "Hide" to "隐藏", "Build Tools" to "构建工具", "Shell" to "Shell",
        "Problems" to "问题", "Search" to "搜索", "Compile" to "编译", "Outputs" to "输出",
        "Logcat" to "Logcat", "PIP" to "PIP", "Project" to "项目", "Open Project" to "打开项目",
        "Recent Projects" to "最近的项目", "Recent Files" to "最近的文件",
        "New Project" to "新建项目", "File Explorer" to "文件浏览器",
        "Project Explorer" to "项目浏览器", "Open Build Tools" to "打开构建工具",
        "Extensions" to "扩展", "MobileStudio Community" to "MobileStudio社区",
        "Open Shell Panel" to "打开Shell面板", "Undo" to "撤销", "Redo" to "重做",
        "Find / Replace" to "查找/替换", "Duplicate Line" to "复制行", "Delete Line" to "删除行",
        "XML Preview" to "XML预览", "Back" to "返回", "Current Project" to "当前项目",
        "Current File Preview" to "当前文件预览", "Format Code" to "格式化代码",
        "Toggle Comment" to "切换注释", "Auto Import" to "自动导入", "Parameter Hint" to "参数提示",
        "Quick Documentation" to "快速文档", "Quick Fix" to "快速修复", "Move Line Up" to "上移行",
        "Move Line Down" to "下移行", "Rename Symbol" to "重命名符号", "Rename File" to "重命名文件",
        "Use Project Explorer long press to rename" to "长按项目浏览器可重命名",
        "Sync Now" to "立即同步", "Run assembleDebug" to "运行assembleDebug",
        "Stop Build" to "停止构建", "Analyzing..." to "分析中...", "No errors" to "无错误",
        "error(s)" to "个错误", "warning(s)" to "个警告", "Auto Pair" to "自动配对", "Bracket Pair Highlight" to "括号高亮",
        "Horizontal Scroll" to "水平滚动", "Code wrap is disabled" to "代码换行已禁用",
        "Community" to "社区", "Save" to "保存", "Find" to "查找", "Format" to "格式化",
        "Rebuild" to "重新构建", "Clean" to "清理", "Stop" to "停止", "Install APK" to "安装APK",
        "Refresh" to "刷新", "Delete" to "删除", "Activate" to "激活", "Browse" to "浏览",
        "Install" to "安装", "Manual" to "手动", "Add" to "添加", "Remove" to "移除",
        "Open Folder" to "打开文件夹", "Import Android Studio ZIP" to "导入Android Studio ZIP",
        "Install Bundled" to "安装捆绑包", "Python + pip" to "Python + pip", "OpenCV" to "OpenCV",
        "Custom URL" to "自定义URL", "Licenses" to "许可证", "SDK Install" to "SDK安装",
        "Ready" to "就绪", "Not installed" to "未安装", "Optional" to "可选", "Active" to "活跃",
        "PATH" to "路径", "Not selected" to "未选择", "OK" to "确定", "NO" to "取消",
        "Cancel" to "取消", "Close" to "关闭", "Copy" to "复制", "Check Size" to "检查大小",
        "Open Shell" to "打开Shell",
        "Download Android LLVM (NDK)" to "下载Android LLVM(NDK)",
        "Download Android-hosted LLVM" to "下载Android托管LLVM", "Android LLVM" to "Android LLVM",
        "No remote versions loaded yet." to "尚未加载远程版本。",
        "Diagnostics & Performance" to "诊断与性能",
        "Gradle build memory (more heap for large projects)" to "Gradle构建内存（为大型项目提供更多堆空间）",
        "Auto" to "自动",
        "Performance mode: Gradle uses 2 workers when possible" to "性能模式：Gradle在可能时使用2个工作线程",
        "Verbose build logs: keep Gradle debug output visible" to "详细构建日志：保持Gradle调试输出可见"
    )

    private val spanishLiterals = mapOf(
        "File" to "Archivo", "Edit" to "Editar", "View" to "Ver", "Navigate" to "Navegar",
        "Code" to "Codigo", "Refactor" to "Refactorizar", "Build" to "Construir", "Run" to "Ejecutar",
        "Tools" to "Herramientas", "IDE" to "IDE", "Saved" to "Guardado", "Build Output" to "Salida de compilacion",
        "Shell Output" to "Salida de Shell", "No output yet." to "Aun no hay salida.",
        "Run JAR" to "Ejecutar JAR", "Copy JAR path" to "Copiar ruta JAR", "Explorer" to "Explorador",
        "Cancel back" to "Cancelar retroceso", "Clean build" to "Compilacion limpia",
        "Downloads" to "Descargas", "Preview" to "Vista previa", "Chat" to "Chat",
        "Pause logcat" to "Pausar logcat", "Clear logcat" to "Limpiar logcat",
        "filter..." to "filtrar...", "Clear" to "Limpiar", "Hide" to "Ocultar",
        "Build Tools" to "Herramientas de compilacion", "Shell" to "Shell",
        "Problems" to "Problemas", "Search" to "Buscar", "Compile" to "Compilar",
        "Outputs" to "Salidas", "Logcat" to "Logcat", "PIP" to "PIP", "Project" to "Proyecto",
        "Open Project" to "Abrir proyecto", "New Project" to "Nuevo proyecto",
        "Recent Projects" to "Proyectos recientes", "Recent Files" to "Archivos recientes",
        "File Explorer" to "Explorador de archivos", "Project Explorer" to "Explorador de proyecto",
        "Open Build Tools" to "Abrir herramientas de compilacion", "Extensions" to "Extensiones",
        "MobileStudio Community" to "Comunidad MobileStudio",
        "Open Shell Panel" to "Abrir panel Shell", "Undo" to "Deshacer", "Redo" to "Rehacer",
        "Find / Replace" to "Buscar/Reemplazar", "Duplicate Line" to "Duplicar linea",
        "Delete Line" to "Eliminar linea", "XML Preview" to "Vista previa XML",
        "Back" to "Atras", "Current Project" to "Proyecto actual",
        "Current File Preview" to "Vista previa del archivo actual", "Format Code" to "Formatear codigo",
        "Toggle Comment" to "Alternar comentario", "Auto Import" to "Importacion automatica",
        "Parameter Hint" to "Sugerencia de parametro", "Quick Documentation" to "Documentacion rapida",
        "Quick Fix" to "Solucion rapida", "Move Line Up" to "Mover linea arriba",
        "Move Line Down" to "Mover linea abajo", "Rename Symbol" to "Renombrar simbolo",
        "Rename File" to "Renombrar archivo",
        "Use Project Explorer long press to rename" to "Manten presionado en el explorador para renombrar",
        "Sync Now" to "Sincronizar ahora", "Run assembleDebug" to "Ejecutar assembleDebug",
        "Stop Build" to "Detener compilacion", "Analyzing..." to "Analizando...", "No errors" to "Sin errores",
        "error(s)" to "error(es)", "warning(s)" to "advertencia(s)", "Auto Pair" to "Auto emparejar",
        "Bracket Pair Highlight" to "Resaltar par de corchetes", "Horizontal Scroll" to "Desplazamiento horizontal",
        "Code wrap is disabled" to "El ajuste de codigo esta desactivado",
        "Community" to "Comunidad", "Save" to "Guardar", "Find" to "Buscar", "Format" to "Formatear",
        "Rebuild" to "Reconstruir", "Clean" to "Limpiar", "Stop" to "Detener",
        "Install APK" to "Instalar APK", "Refresh" to "Actualizar", "Delete" to "Eliminar",
        "Activate" to "Activar", "Browse" to "Examinar", "Install" to "Instalar", "Manual" to "Manual",
        "Add" to "Agregar", "Remove" to "Eliminar", "Open Folder" to "Abrir carpeta",
        "Import Android Studio ZIP" to "Importar ZIP de Android Studio",
        "Install Bundled" to "Instalar empaquetado", "Python + pip" to "Python + pip",
        "OpenCV" to "OpenCV", "Custom URL" to "URL personalizada", "Licenses" to "Licencias",
        "SDK Install" to "Instalacion de SDK", "Ready" to "Listo", "Not installed" to "No instalado",
        "Optional" to "Opcional", "Active" to "Activo", "PATH" to "RUTA",
        "Not selected" to "No seleccionado", "OK" to "Aceptar", "NO" to "No", "Cancel" to "Cancelar",
        "Close" to "Cerrar", "Copy" to "Copiar", "Check Size" to "Verificar tamano",
        "Open Shell" to "Abrir Shell",
        "Download Android LLVM (NDK)" to "Descargar Android LLVM (NDK)",
        "Download Android-hosted LLVM" to "Descargar LLVM para Android",
        "Android LLVM" to "Android LLVM",
        "No remote versions loaded yet." to "Aun no se cargaron versiones remotas.",
        "Diagnostics & Performance" to "诊断与性能",
        "Gradle build memory (more heap for large projects)" to "Gradle构建内存（为大型项目提供更多堆空间）",
        "Auto" to "自动",
        "Performance mode: Gradle uses 2 workers when possible" to "性能模式：Gradle在可能时使用2个工作线程",
        "Verbose build logs: keep Gradle debug output visible" to "详细构建日志：保持Gradle调试输出可见"
    )
}

fun Context.getStringIfKorean(korean: String, english: String): String =
    if (AppLanguage.isKorean(this)) korean else english

fun java.io.File.nameWithoutArchiveExtension(): String {
    val n = name
    return when {
        n.endsWith(".tar.zst", true) -> n.removeSuffix(".tar.zst")
        n.endsWith(".tar.gz", true) || n.endsWith(".tgz", true) -> n.removeSuffix(".gz").removeSuffix(".tar")
        n.endsWith(".tar.xz", true) || n.endsWith(".txz", true) -> n.removeSuffix(".xz").removeSuffix(".tar")
        n.endsWith(".tar.bz2", true) -> n.removeSuffix(".bz2").removeSuffix(".tar")
        n.endsWith(".tar.lz", true) -> n.removeSuffix(".lz").removeSuffix(".tar")
        n.endsWith(".tar", true) -> n.removeSuffix(".tar")
        n.endsWith(".zip", true) -> n.removeSuffix(".zip")
        n.endsWith(".gz", true) -> n.removeSuffix(".gz")
        n.endsWith(".xz", true) -> n.removeSuffix(".xz")
        else -> n.substringBeforeLast(".")
    }
}