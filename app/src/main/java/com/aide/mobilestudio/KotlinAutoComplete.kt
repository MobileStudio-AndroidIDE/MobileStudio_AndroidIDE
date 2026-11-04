package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.*
import java.io.File
import java.util.zip.ZipFile

/**
 * Kotlin ??동??성
 *
 * 제공하는 제안 목록:
 *  1. Kotlin 키워드 + 내장 라이브러리 멤버
 *  2. 현재 파일에서 선언된 변수/함수/클래스명
 *  3. import된 클래스의 멤버 (프로젝트 소스 파싱)
 *  4. 타입 추론: val/var 선언 시 타입 제안
 *
 * 동작: EditText 커서 위치 근처 팝업으로 표시
 */
class KotlinAutoComplete(
    private val context: Context,
    private val editText: EditText
) {

    data class Suggestion(
        val label: String,
        val insert: String,
        val detail: String = "",
        val kind: Kind = Kind.KEYWORD
    ) {
        enum class Kind { KEYWORD, FUNCTION, CLASS, VARIABLE, SNIPPET, IMPORT }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var completeJob: Job? = null
    private var projectDir: File? = null
    private var currentExtension: String = "kt"
    private var lastChangeWasDeletion = false
    @Volatile private var projectSuggestions: List<Suggestion> = emptyList()
    @Volatile private var gradleSyncManager: GradleSyncManager? = null

    // ???? ??업 ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    private val popup = PopupWindow(context).apply {
        width  = ViewGroup.LayoutParams.WRAP_CONTENT
        height = ViewGroup.LayoutParams.WRAP_CONTENT
        isFocusable = false
        isOutsideTouchable = true
        setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        elevation = 12f * context.resources.displayMetrics.density
    }

    private val listView = ListView(context).apply {
        background = roundedBg(StudioUi.palette.surface, StudioUi.palette.borderStrong, 8)
        divider = null
        dividerHeight = 0
        selector = android.graphics.drawable.ColorDrawable(StudioUi.palette.selectedBg)
    }

    init {
        popup.contentView = listView
        attachWatcher()
    }

    fun setProjectDir(dir: File) { projectDir = dir }

    fun setCurrentFile(file: File) {
        currentExtension = file.extension.lowercase()
    }

    /**
     * GradleSyncManager? ? ? sync
     * Android Studioó? ?Ȯ? ?ɺ? ?ε?
     */
    fun syncProject(dir: File, onComplete: (Int, String) -> Unit = { _, _ -> }) {
        projectDir = dir
        scope.launch {
            val indexed = withContext(Dispatchers.IO) {
                // 1?: GradleSyncManager ? JAR ?ε? (? ? + SDK)
                val syncManager = gradleSyncManager
                if (syncManager != null && syncManager.symbolCount > 0) {
                    syncManager.getAllSymbols().values.map { sym ->
                        Suggestion(
                            label = sym.simpleName,
                            insert = sym.simpleName,
                            detail = sym.packageName,
                            kind = when (sym.kind) {
                                GradleSyncManager.SymbolKind.CLASS -> Suggestion.Kind.CLASS
                                GradleSyncManager.SymbolKind.INTERFACE -> Suggestion.Kind.CLASS
                                GradleSyncManager.SymbolKind.OBJECT -> Suggestion.Kind.CLASS
                                GradleSyncManager.SymbolKind.FUNCTION -> Suggestion.Kind.FUNCTION
                                GradleSyncManager.SymbolKind.CONSTANT -> Suggestion.Kind.VARIABLE
                                GradleSyncManager.SymbolKind.ENUM -> Suggestion.Kind.CLASS
                            }
                        )
                    }.sortedWith(compareBy<Suggestion> { kindOrder(it.kind) }
                        .thenBy { it.label.length }
                        .thenBy { it.label })
                        .take(5000)
                } else {
                    // 2?: ? JAR ?ĵ (SDK + Gradle ĳ? ?ü)
                    val jarSymbols = indexGradleJarSymbols(dir)
                    val sourceSymbols = indexSourceSymbols(dir)
                    (jarSymbols + sourceSymbols)
                        .distinctBy { "${it.kind}:${it.label}:${it.detail}" }
                        .sortedWith(compareBy<Suggestion> { kindOrder(it.kind) }
                            .thenBy { it.label.length }
                            .thenBy { it.label })
                        .take(5000)
                }
            }
            projectSuggestions = indexed
            onComplete(indexed.size, "Indexed ${indexed.size} project/dependency symbols")
        }
    }

    /**
     * GradleSyncManager ? (?ܺο? ȣ?)
     */
    fun setGradleSyncManager(manager: GradleSyncManager) {
        gradleSyncManager = manager
    }

    /**
     * import ?ڵ? ?߰? ?
     */
    fun autoAddImports(code: String, currentPackage: String): String {
        val syncManager = gradleSyncManager ?: return code
        val missingImports = syncManager.findMissingImports(code, currentPackage)
        if (missingImports.isEmpty()) return code

        val importSection = missingImports.joinToString("\n")
        val lastImportIndex = code.lastIndexOf("\nimport ")

        return if (lastImportIndex >= 0) {
            val endOfLine = code.indexOf('\n', lastImportIndex + 1)
            code.substring(0, endOfLine) + "\n" + importSection + code.substring(endOfLine)
        } else {
            importSection + "\n\n" + code
        }
    }

    // ???? TextWatcher ??결 ????????????????????????????????????????????????????????????????????????????????????????????????????

    private fun attachWatcher() {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                lastChangeWasDeletion = c > a
            }

            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}

            override fun afterTextChanged(s: Editable?) {
                if (s == null) {
                    dismiss()
                    return
                }
                // Synchronous dismiss: never leave a stale suggestion list on
                // screen while the debounce job decides what (if anything) to
                // show next. Deletions always close the current popup.
                if (lastChangeWasDeletion) dismiss()
                val cursor0 = editText.selectionStart.coerceAtLeast(0)
                val text0 = s.toString()
                val dot0 = getDotPrefix(text0, cursor0)
                if (dot0 == null && getPrefix(text0, cursor0).length < 2) dismiss()
                val wasDeletion = lastChangeWasDeletion

                completeJob?.cancel()
                completeJob = scope.launch {
                    delay(220)
                    val cursor = editText.selectionStart.coerceAtLeast(0)
                    val text   = s.toString()
                    val dotPrefix = getDotPrefix(text, cursor)
                    val prefix = dotPrefix?.second ?: getPrefix(text, cursor)

                    if (dotPrefix == null && prefix.length < 2) { dismiss(); return@launch }
                    // Deleting back to "receiver." must NOT re-populate the
                    // member list — an empty member prefix after a deletion
                    // means the user is erasing, not asking for members.
                    if (dotPrefix != null && prefix.isEmpty() && wasDeletion) {
                        dismiss(); return@launch
                    }

                    val suggestions = withContext(Dispatchers.Default) {
                        buildSuggestions(text, prefix, cursor)
                    }

                    if (suggestions.isEmpty()) { dismiss(); return@launch }
                    showPopup(suggestions, prefix)
                }
            }
        })
    }

    // ???? ??동??성 목록 구성 ????????????????????????????????????????????????????????????????????????????????????????????????

    private fun buildSuggestions(
        fullText: String,
        prefix: String,
        cursor: Int
    ): List<Suggestion> {
        val results = mutableListOf<Suggestion>()

        val dotPrefix = getDotPrefix(fullText, cursor)
        if (dotPrefix != null) {
            val (receiver, memberPrefix) = dotPrefix
            return getMembersForReceiver(receiver, fullText)
                .filter { memberPrefix.isEmpty() || it.label.startsWith(memberPrefix) }
                .sortedWith(compareByDescending<Suggestion> { scoreSuggestion(it, memberPrefix) }.thenBy { it.label })
                .distinctBy { it.label }
                .take(5)
        }

        parseDeclarations(fullText).filter { it.label.startsWith(prefix) }.forEach { results += it }
        projectSuggestions.filter { it.label.startsWith(prefix) }.forEach { results += it }
        languageBuiltIns(prefix).forEach { results += it }

        // 4. import된 클래스 패키지 기반 제안
        val imports = parseImports(fullText)
        imports.flatMap { suggestionsForImport(it) }
            .filter { it.label.startsWith(prefix) }
            .forEach { results += it }

        snippetsForCurrentLanguage().filter { it.label.startsWith(prefix) }.forEach { results += it }
        keywordsForCurrentLanguage().filter { it.startsWith(prefix) }.forEach { kw ->
            results += Suggestion(kw, kw, kind = Suggestion.Kind.KEYWORD)
        }

        // 5. 점(.) 뒤 멤버 제안
        // 타입 제안 (val/var 뒤)
        if (isAfterColon(fullText, cursor)) {
            ALL_TYPES.filter { it.startsWith(prefix) }.forEach { t ->
                results += Suggestion(t, t, "type", Suggestion.Kind.CLASS)
            }
        }

        return results
            .distinctBy { it.label }
            .sortedWith(compareByDescending<Suggestion> { scoreSuggestion(it, prefix) }.thenBy { kindOrder(it.kind) }.thenBy { it.label.length })
            .take(5)
    }

    // ???? ??일 ??언 ??싱 ????????????????????????????????????????????????????????????????????????????????????????????????????????

    private fun indexSourceSymbols(root: File): List<Suggestion> {
        val results = mutableListOf<Suggestion>()
        val extensions = setOf("kt", "java", "c", "cc", "cpp", "h", "hpp")
        root.walkTopDown()
            .onEnter { dir -> dir.name !in setOf("build", ".gradle", ".idea", ".git") }
            .filter { it.isFile && it.extension.lowercase() in extensions && it.length() <= 512_000 }
            .take(600)
            .forEach { file ->
                val source = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val pkg = Regex("^\\s*package\\s+([\\w.]+)", RegexOption.MULTILINE).find(source)?.groupValues?.get(1)
                val relative = runCatching { file.relativeTo(root).path }.getOrDefault(file.name)
                Regex("\\b(?:class|object|interface|enum\\s+class|enum|record|struct)\\s+(\\w+)").findAll(source).forEach { match ->
                    val name = match.groupValues[1]
                    results += Suggestion(name, name, listOfNotNull(pkg, relative).joinToString(" · "), Suggestion.Kind.CLASS)
                }
                Regex("\\bfun\\s+(\\w+)\\s*\\(").findAll(source).forEach { match ->
                    val name = match.groupValues[1]
                    results += Suggestion("$name()", "$name()", relative, Suggestion.Kind.FUNCTION)
                }
            }
        return results
    }

    /**
     * ?ּ? fallback ? GradleSyncManager? ? ? ?˴ϴ?.
     * ? JAR ?ĵ? ?ֿ? ?ҽ?Դϴ?.
     */
    private fun indexGradleDependencyHints(root: File): List<Suggestion> {
        val results = mutableListOf<Suggestion>()
        root.walkTopDown()
            .onEnter { dir -> dir.name !in setOf("build", ".gradle", ".idea", ".git") }
            .filter { it.isFile && it.name in setOf("build.gradle", "build.gradle.kts") }
            .take(10)
            .forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                val lower = text.lowercase()
                fun add(label: String, detail: String) {
                    results += Suggestion(label, label, detail, Suggestion.Kind.CLASS)
                }
                // Compose ?ּ? Ű? (?)
                if ("compose" in lower) {
                    listOf("Composable", "Modifier", "remember", "LaunchedEffect", "Column", "Row", "Box", "MaterialTheme")
                        .forEach { add(it, "Compose") }
                }
            }
        return results
    }

    /**
     * Gradle ĳ? + SDK ?÷? JAR? ? Ŭ? ? ?ĵ?մϴ?.
     * ? ? android.jar, classes.jar ?? ? ?ɺ? ?մϴ?.
     */
    private fun indexGradleJarSymbols(root: File): List<Suggestion> {
        val cacheRoots = mutableListOf<File>()

        // Gradle ĳ?
        File(context.filesDir, ".gradle/caches/modules-2/files-2.1").takeIf { it.exists() }?.let { cacheRoots.add(it) }
        File(root, ".gradle/caches/modules-2/files-2.1").takeIf { it.exists() }?.let { cacheRoots.add(it) }
        File(context.filesDir, ".gradle/caches/transforms-3").takeIf { it.exists() }?.let { cacheRoots.add(it) }

        // Android SDK platforms ? android.jar (ī?޶?, ?, ?̵? ? ? Android API)
        val sdkDir = listOf(
            File(context.filesDir, "toolchain/sdk/active"),
            File(context.filesDir, "toolchain/sdk"),
            File(System.getenv("ANDROID_HOME") ?: ""),
            File(System.getenv("ANDROID_SDK_ROOT") ?: "")
        ).firstOrNull { it.exists() && File(it, "platforms").exists() }

        if (sdkDir != null) {
            val platforms = File(sdkDir, "platforms")
            if (platforms.exists()) {
                platforms.listFiles()?.filter { it.isDirectory }?.forEach { platformDir ->
                    val androidJar = File(platformDir, "android.jar")
                    if (androidJar.exists()) cacheRoots.add(platformDir)
                }
            }
        }

        val jars = cacheRoots
            .filter { it.exists() }
            .flatMap { cacheRoot ->
                cacheRoot.walkTopDown()
                    .filter { file -> file.isFile && file.extension == "jar" && file.length() in 1..80_000_000 }
                    .take(200)
                    .toList()
            }

        val results = mutableListOf<Suggestion>()
        val seen = mutableSetOf<String>()

        for (jarFile in jars) {
            runCatching {
                ZipFile(jarFile).use { zip ->
                    zip.entries().asSequence()
                        .filter { entry ->
                            !entry.isDirectory &&
                            entry.name.endsWith(".class") &&
                            !entry.name.endsWith("module-info.class") &&
                            !entry.name.endsWith("package-info.class")
                        }
                        .take(2000)
                        .forEach { entry ->
                            val classPath = entry.name.removeSuffix(".class").replace('/', '.')
                            val simpleName = classPath.substringAfterLast('.')
                            val packageName = classPath.substringBeforeLast('.', "")

                            // ? Ŭ?($)? ?ŵ
                            if ('$' in simpleName) return@forEach

                            if (simpleName.firstOrNull()?.isUpperCase() == true && simpleName.length > 1) {
                                val key = "${packageName}.${simpleName}"
                                if (key !in seen) {
                                    seen.add(key)
                                    results += Suggestion(
                                        simpleName, simpleName, packageName, Suggestion.Kind.CLASS
                                    )
                                }
                            }

                            // ?(?޼?/?ʵ?)? ?ε?
                            try {
                                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                                extractMembersFromBytes(bytes).forEach { memberName ->
                                    val mKey = "${packageName}.${simpleName}.$memberName"
                                    if (mKey !in seen && memberName.length > 1) {
                                        seen.add(mKey)
                                        results += Suggestion(
                                            memberName, memberName,
                                            "${packageName}.${simpleName}",
                                            if (memberName[0].isUpperCase()) Suggestion.Kind.CLASS else Suggestion.Kind.FUNCTION
                                        )
                                    }
                                }
                            } catch (_: Exception) { }
                        }
                }
            }
            if (results.size > 15000) break
        }
        return results
    }

    /**
     * Ŭ? bytecode? ?޼?/?ʵ? ?̸? ?մϴ?.
     */
    private fun extractMembersFromBytes(bytes: ByteArray): List<String> {
        val members = mutableListOf<String>()
        if (bytes.size < 10) return members
        try {
            var offset = 8
            val cpCount = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
            offset += 2
            val utf8s = mutableListOf<String>()
            var i = 1
            while (i < cpCount && offset < bytes.size) {
                val tag = bytes[offset].toInt() and 0xFF
                offset++
                when (tag) {
                    1 -> {
                        val len = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
                        offset += 2
                        if (offset + len <= bytes.size) utf8s.add(String(bytes, offset, len, Charsets.UTF_8))
                        else utf8s.add("")
                        offset += len
                    }
                    3, 4 -> offset += 4
                    5, 6 -> { offset += 8; i++ }
                    7, 8, 16, 19, 20 -> offset += 2
                    9, 10, 11, 12, 15, 17, 18 -> offset += 4
                    else -> offset += 2
                }
                i++
            }
            val methodRx = Regex("^[a-z][a-zA-Z0-9_]{1,50}$")
            val fieldRx = Regex("^[a-zA-Z][a-zA-Z0-9_]{1,50}$")
            val seen = mutableSetOf<String>()
            for (s in utf8s) {
                val t = s.trim()
                if (t.length < 2 || t.length > 50 || t.startsWith("(")) continue
                if ((methodRx.matches(t) || fieldRx.matches(t)) && t !in seen) {
                    seen.add(t)
                    members.add(t)
                }
            }
        } catch (_: Exception) { }
        return members.take(50)
    }

    private fun scoreSuggestion(suggestion: Suggestion, prefix: String): Int {
        val label = suggestion.label
        val commonBoost = when (label) {
            "override", "private", "fun", "val", "var", "Composable", "Modifier", "remember",
            "setContent", "findViewById", "launch", "withContext", "Intent", "Bundle" -> 20
            else -> 0
        }
        val matchBoost = when {
            label == prefix -> 80
            label.startsWith(prefix) -> 50
            label.contains(prefix, ignoreCase = true) -> 15
            else -> 0
        }
        return commonBoost + matchBoost + (20 - kindOrder(suggestion.kind) * 2)
    }

    private fun kindOrder(kind: Suggestion.Kind): Int = when (kind) {
        Suggestion.Kind.VARIABLE -> 0
        Suggestion.Kind.FUNCTION -> 1
        Suggestion.Kind.CLASS -> 2
        Suggestion.Kind.IMPORT -> 3
        Suggestion.Kind.SNIPPET -> 4
        Suggestion.Kind.KEYWORD -> 5
    }

    private fun keywordsForCurrentLanguage(): List<String> = when (currentExtension) {
        "java" -> JAVA_KEYWORDS
        "xml" -> XML_KEYWORDS
        "gradle", "kts" -> GRADLE_KEYWORDS
        "c", "h" -> C_KEYWORDS
        "cpp", "cc", "hpp" -> (C_KEYWORDS + listOf("namespace", "template", "typename", "using", "new", "delete", "public", "private", "protected")).distinct()
        "py" -> PYTHON_KEYWORDS
        "lua" -> LUA_KEYWORDS
        "js" -> JS_KEYWORDS
        "ts", "tsx" -> TS_KEYWORDS
        "html", "htm" -> HTML_KEYWORDS
        "css", "scss" -> CSS_KEYWORDS
        "rs" -> RUST_KEYWORDS
        "go" -> GO_KEYWORDS
        "swift" -> SWIFT_KEYWORDS
        "dart" -> DART_KEYWORDS
        "sql" -> SQL_KEYWORDS
        "sh", "bash" -> SHELL_KEYWORDS
        "gd" -> GD_KEYWORDS
        "json" -> JSON_KEYWORDS
        "yml", "yaml" -> YAML_KEYWORDS
        "cmake" -> CMAKE_KEYWORDS
        else -> KOTLIN_KEYWORDS
    }

    private fun snippetsForCurrentLanguage(): List<Suggestion> = when (currentExtension) {
        "java" -> JAVA_SNIPPETS
        "xml" -> XML_SNIPPETS
        "gradle", "kts" -> GRADLE_SNIPPETS
        "c", "h", "cpp", "cc", "hpp" -> C_SNIPPETS
        "py" -> PYTHON_SNIPPETS
        "lua" -> LUA_SNIPPETS
        "js" -> JS_SNIPPETS
        "gd" -> GD_SNIPPETS
        else -> SNIPPETS
    }

    private fun languageBuiltIns(prefix: String): List<Suggestion> {
        val items = when (currentExtension) {
            "java" -> listOf(
                Suggestion("println", "System.out.println()", "java print", Suggestion.Kind.FUNCTION),
                Suggestion("print", "System.out.print()", "java print", Suggestion.Kind.FUNCTION),
                Suggestion("String", "String", "java type", Suggestion.Kind.CLASS),
                Suggestion("ArrayList", "ArrayList", "java util", Suggestion.Kind.CLASS)
            )
            "xml" -> listOf(
                Suggestion("android:", "android:", "Android XML namespace", Suggestion.Kind.KEYWORD),
                Suggestion("app:", "app:", "App XML namespace", Suggestion.Kind.KEYWORD),
                Suggestion("layout_width", "layout_width=\"match_parent\"", "View attribute", Suggestion.Kind.VARIABLE),
                Suggestion("layout_height", "layout_height=\"wrap_content\"", "View attribute", Suggestion.Kind.VARIABLE),
                Suggestion("TextView", "TextView", "widget", Suggestion.Kind.CLASS),
                Suggestion("LinearLayout", "LinearLayout", "layout", Suggestion.Kind.CLASS),
                Suggestion("ConstraintLayout", "androidx.constraintlayout.widget.ConstraintLayout", "layout", Suggestion.Kind.CLASS)
            )
            "gradle", "kts" -> listOf(
                Suggestion("implementation", "implementation(\"\")", "dependency", Suggestion.Kind.FUNCTION),
                Suggestion("android", "android {\n    \n}", "Android Gradle DSL", Suggestion.Kind.SNIPPET),
                Suggestion("dependencies", "dependencies {\n    \n}", "Gradle DSL", Suggestion.Kind.SNIPPET),
                Suggestion("plugins", "plugins {\n    \n}", "Gradle DSL", Suggestion.Kind.SNIPPET)
            )
            "c", "h", "cpp", "cc", "hpp" -> listOf(
                Suggestion("printf", "printf()", "stdio", Suggestion.Kind.FUNCTION),
                Suggestion("fprintf", "fprintf()", "stdio", Suggestion.Kind.FUNCTION),
                Suggestion("malloc", "malloc()", "stdlib", Suggestion.Kind.FUNCTION),
                Suggestion("free", "free()", "stdlib", Suggestion.Kind.FUNCTION),
                Suggestion("size_t", "size_t", "c type", Suggestion.Kind.CLASS)
            )
            "py" -> listOf(
                Suggestion("print", "print()", "python output", Suggestion.Kind.FUNCTION),
                Suggestion("input", "input()", "python input", Suggestion.Kind.FUNCTION),
                Suggestion("range", "range()", "python iterable", Suggestion.Kind.FUNCTION),
                Suggestion("list", "list()", "python type", Suggestion.Kind.CLASS),
                Suggestion("dict", "dict()", "python type", Suggestion.Kind.CLASS)
            )
            "lua" -> listOf(
                Suggestion("print", "print()", "lua output", Suggestion.Kind.FUNCTION),
                Suggestion("pairs", "pairs()", "lua iterate table", Suggestion.Kind.FUNCTION),
                Suggestion("ipairs", "ipairs()", "lua iterate array", Suggestion.Kind.FUNCTION),
                Suggestion("table", "table", "lua lib", Suggestion.Kind.CLASS),
                Suggestion("string", "string", "lua lib", Suggestion.Kind.CLASS),
                Suggestion("math", "math", "lua lib", Suggestion.Kind.CLASS),
                Suggestion("tostring", "tostring()", "lua convert", Suggestion.Kind.FUNCTION),
                Suggestion("tonumber", "tonumber()", "lua convert", Suggestion.Kind.FUNCTION),
                Suggestion("drawRect", "drawRect(x, y, w, h, r, g, b)", "game draw", Suggestion.Kind.FUNCTION),
                Suggestion("drawCircle", "drawCircle(x, y, radius, r, g, b)", "game draw", Suggestion.Kind.FUNCTION),
                Suggestion("drawText", "drawText(text, x, y)", "game draw", Suggestion.Kind.FUNCTION),
                Suggestion("clear", "clear(r, g, b)", "game clear screen", Suggestion.Kind.FUNCTION),
                Suggestion("screenWidth", "screenWidth", "game screen width", Suggestion.Kind.VARIABLE),
                Suggestion("screenHeight", "screenHeight", "game screen height", Suggestion.Kind.VARIABLE)
            )
            "js" -> listOf(
                Suggestion("console", "console", "JS console", Suggestion.Kind.CLASS),
                Suggestion("log", "console.log()", "console log", Suggestion.Kind.FUNCTION),
                Suggestion("document", "document", "DOM", Suggestion.Kind.CLASS),
                Suggestion("querySelector", "document.querySelector()", "DOM query", Suggestion.Kind.FUNCTION),
                Suggestion("addEventListener", "addEventListener()", "event listener", Suggestion.Kind.FUNCTION),
                Suggestion("setTimeout", "setTimeout()", "timer", Suggestion.Kind.FUNCTION),
                Suggestion("setInterval", "setInterval()", "timer", Suggestion.Kind.FUNCTION),
                Suggestion("JSON", "JSON", "JS JSON", Suggestion.Kind.CLASS),
                Suggestion("stringify", "JSON.stringify()", "JSON convert", Suggestion.Kind.FUNCTION),
                Suggestion("parse", "JSON.parse()", "JSON parse", Suggestion.Kind.FUNCTION),
                Suggestion("Math", "Math", "JS math", Suggestion.Kind.CLASS),
                Suggestion("drawRect", "drawRect(x, y, w, h, r, g, b)", "game draw", Suggestion.Kind.FUNCTION),
                Suggestion("drawCircle", "drawCircle(x, y, radius, r, g, b)", "game draw", Suggestion.Kind.FUNCTION),
                Suggestion("drawText", "drawText(text, x, y)", "game draw", Suggestion.Kind.FUNCTION),
                Suggestion("clear", "clear(r, g, b)", "game clear screen", Suggestion.Kind.FUNCTION)
            )
            "gd" -> GD_BUILTIN_FUNCS.map { Suggestion(it, it, "GDScript game API", Suggestion.Kind.FUNCTION) }
            "rs" -> listOf(
                Suggestion("println", "println!()", "rust print", Suggestion.Kind.FUNCTION),
                Suggestion("vec", "Vec::new()", "rust vec", Suggestion.Kind.FUNCTION),
                Suggestion("match", "match", "rust match", Suggestion.Kind.KEYWORD)
            )
            "go" -> listOf(
                Suggestion("println", "fmt.Println()", "go print", Suggestion.Kind.FUNCTION),
                Suggestion("printf", "fmt.Printf()", "go print", Suggestion.Kind.FUNCTION),
                Suggestion("make", "make()", "go make", Suggestion.Kind.FUNCTION),
                Suggestion("append", "append()", "go append", Suggestion.Kind.FUNCTION)
            )
            "swift" -> listOf(
                Suggestion("print", "print()", "swift print", Suggestion.Kind.FUNCTION),
                Suggestion("guard", "guard", "swift guard", Suggestion.Kind.KEYWORD)
            )
            "dart" -> listOf(
                Suggestion("print", "print()", "dart print", Suggestion.Kind.FUNCTION),
                Suggestion("StatelessWidget", "StatelessWidget", "flutter widget", Suggestion.Kind.CLASS),
                Suggestion("StatefulWidget", "StatefulWidget", "flutter widget", Suggestion.Kind.CLASS)
            )
            "sql" -> listOf(
                Suggestion("SELECT", "SELECT * FROM", "SQL query", Suggestion.Kind.KEYWORD),
                Suggestion("CREATE TABLE", "CREATE TABLE IF NOT EXISTS", "SQL DDL", Suggestion.Kind.KEYWORD)
            )
            "sh", "bash" -> listOf(
                Suggestion("echo", "echo", "shell output", Suggestion.Kind.FUNCTION),
                Suggestion("export", "export", "shell env", Suggestion.Kind.FUNCTION)
            )
            "cmake" -> listOf(
                Suggestion("cmake_minimum_required", "cmake_minimum_required(VERSION )", "CMake version", Suggestion.Kind.FUNCTION),
                Suggestion("add_executable", "add_executable()", "CMake target", Suggestion.Kind.FUNCTION),
                Suggestion("add_library", "add_library()", "CMake lib", Suggestion.Kind.FUNCTION)
            )
            else -> listOf(
                Suggestion("println", "println()", "kotlin print", Suggestion.Kind.FUNCTION),
                Suggestion("print", "print()", "kotlin print", Suggestion.Kind.FUNCTION),
                Suggestion("remember", "remember", "compose", Suggestion.Kind.FUNCTION),
                Suggestion("mutableStateOf", "mutableStateOf()", "compose state", Suggestion.Kind.FUNCTION),
                Suggestion("listOf", "listOf()", "create list", Suggestion.Kind.FUNCTION),
                Suggestion("mapOf", "mapOf()", "create map", Suggestion.Kind.FUNCTION),
                Suggestion("setOf", "setOf()", "create set", Suggestion.Kind.FUNCTION),
                Suggestion("mutableListOf", "mutableListOf()", "create mutable list", Suggestion.Kind.FUNCTION),
                Suggestion("mutableMapOf", "mutableMapOf()", "create mutable map", Suggestion.Kind.FUNCTION),
                Suggestion("arrayOf", "arrayOf()", "create array", Suggestion.Kind.FUNCTION),
                Suggestion("requireNotNull", "requireNotNull(value) { \"msg\" }", "not-null assertion", Suggestion.Kind.FUNCTION),
                Suggestion("checkNotNull", "checkNotNull(value)", "not-null check", Suggestion.Kind.FUNCTION),
                Suggestion("require", "require(condition) { \"msg\" }", "argument check", Suggestion.Kind.FUNCTION),
                Suggestion("check", "check(condition) { \"msg\" }", "state check", Suggestion.Kind.FUNCTION),
                Suggestion("error", "error(\"message\")", "throw error", Suggestion.Kind.FUNCTION),
                Suggestion("runCatching", "runCatching {  }", "result wrapper", Suggestion.Kind.FUNCTION),
                Suggestion("with", "with(obj) {  }", "scoped function", Suggestion.Kind.FUNCTION),
                Suggestion("apply", "obj.apply {  }", "scoped function", Suggestion.Kind.FUNCTION),
                Suggestion("also", "obj.also {  }", "scoped function", Suggestion.Kind.FUNCTION),
                Suggestion("let", "obj.let {  }", "scoped function", Suggestion.Kind.FUNCTION),
                Suggestion("run", "obj.run {  }", "scoped function", Suggestion.Kind.FUNCTION),
                Suggestion("use", "obj.use {  }", "closeable use", Suggestion.Kind.FUNCTION),
                Suggestion("takeIf", "obj.takeIf {  }", "conditional", Suggestion.Kind.FUNCTION),
                Suggestion("takeUnless", "obj.takeUnless {  }", "conditional", Suggestion.Kind.FUNCTION),
                Suggestion("repeat", "repeat(count) {  }", "repeat action", Suggestion.Kind.FUNCTION),
                Suggestion("generateSequence", "generateSequence(seed) {  }", "sequence", Suggestion.Kind.FUNCTION)
            )
        }
        return items.filter { it.label.startsWith(prefix) }
    }

    private fun parseDeclarations(src: String): List<Suggestion> {
        val list = mutableListOf<Suggestion>()

        // fun ??언
        Regex("\\bfun\\s+(\\w+)\\s*\\(([^)]*)\\)").findAll(src).forEach { m ->
            val name   = m.groupValues[1]
            val params = m.groupValues[2]
            list += Suggestion("$name()", "$name()", "fun ($params)", Suggestion.Kind.FUNCTION)
        }

        // val/var ??언
        Regex("\\b(val|var)\\s+(\\w+)\\s*[=:]").findAll(src).forEach { m ->
            val name = m.groupValues[2]
            val kind = m.groupValues[1]
            list += Suggestion(name, name, kind, Suggestion.Kind.VARIABLE)
        }

        // class/object ??언
        Regex("\\b(class|object|interface)\\s+(\\w+)").findAll(src).forEach { m ->
            val name = m.groupValues[2]
            list += Suggestion(name, name, m.groupValues[1], Suggestion.Kind.CLASS)
        }

        Regex("\\b(?:public\\s+)?(?:class|interface|enum|record)\\s+(\\w+)").findAll(src).forEach { m ->
            val name = m.groupValues[1]
            list += Suggestion(name, name, "java type", Suggestion.Kind.CLASS)
        }

        Regex("\\b(?:int|long|float|double|char|void|String|boolean|[A-Z]\\w*)\\s+(\\w+)\\s*\\(")
            .findAll(src).forEach { m ->
                val name = m.groupValues[1]
                if (name !in KOTLIN_KEYWORDS) list += Suggestion("$name()", "$name()", "function", Suggestion.Kind.FUNCTION)
            }

        Regex("\\b(?:int|long|float|double|char|bool|String|boolean|auto)\\s+(\\w+)\\s*(?:=|;)").findAll(src).forEach { m ->
            val name = m.groupValues[1]
            list += Suggestion(name, name, "variable", Suggestion.Kind.VARIABLE)
        }

        return list
    }

    private fun parseImports(src: String): List<String> {
        return Regex("^import\\s+([\\w.*]+)", RegexOption.MULTILINE)
            .findAll(src)
            .map { it.groupValues[1] }
            .toList()
    }

    private fun suggestionsForImport(importPath: String): List<Suggestion> {
        val simpleName = importPath.substringAfterLast('.')
        val exact = LIBRARY_MEMBERS[importPath] ?: LIBRARY_MEMBERS[simpleName]
        if (exact != null) return exact

        if (importPath.endsWith(".*")) {
            val packageName = importPath.removeSuffix(".*")
            return LIBRARY_PACKAGES[packageName] ?: emptyList()
        }

        val packageSuggestions = LIBRARY_PACKAGES
            .filterKeys { pkg -> importPath.startsWith("$pkg.") }
            .values
            .flatten()
        return packageSuggestions + (STDLIB_MEMBERS[simpleName] ?: emptyList())
    }

    private fun getDotPrefix(text: String, cursor: Int): Pair<String, String>? {
        val before = text.substring(0, cursor)
        val dotIdx = before.lastIndexOf('.')
        if (dotIdx < 0) return null
        val memberPrefix = before.substring(dotIdx + 1)
        if (memberPrefix.contains(' ') || memberPrefix.contains('\n')) return null
        val receiver = before.substring(0, dotIdx).trimEnd().substringAfterLast(' ')
        return receiver to memberPrefix
    }

    private fun getMembersForReceiver(receiver: String, src: String): List<Suggestion> {
        // 변수 타입 추론 (간단한)
        val explicitType = Regex("\\b(?:val|var)\\s+${Regex.escape(receiver)}\\s*:\\s*([\\w.<>]+)")
            .find(src)?.groupValues?.get(1)?.substringBefore('<')?.substringAfterLast('.')
        val inferredType = inferReceiverType(receiver, src)
        val type = explicitType ?: inferredType ?: receiver
        return STDLIB_MEMBERS[type]
            ?: LIBRARY_MEMBERS[type]
            ?: LIBRARY_MEMBERS[receiver]
            ?: emptyList()
    }

    private fun inferReceiverType(receiver: String, src: String): String? {
        val escaped = Regex.escape(receiver)
        val assignment = Regex("\\b(?:val|var)\\s+$escaped\\s*=\\s*([^\\n]+)").find(src)?.groupValues?.get(1)?.trim() ?: return null
        return when {
            assignment.startsWith("File(") -> "File"
            assignment.startsWith("Intent(") -> "Intent"
            assignment.startsWith("Bundle(") -> "Bundle"
            assignment.startsWith("Uri.parse") -> "Uri"
            assignment.startsWith("ArrayList(") || assignment.startsWith("mutableListOf(") || assignment.startsWith("listOf(") -> "List"
            assignment.startsWith("HashMap(") || assignment.startsWith("mutableMapOf(") || assignment.startsWith("mapOf(") -> "Map"
            assignment.startsWith("CoroutineScope(") -> "CoroutineScope"
            assignment.startsWith("Regex(") -> "Regex"
            assignment.startsWith("StringBuilder(") -> "StringBuilder"
            assignment.startsWith('\"') -> "String"
            assignment.matches(Regex("\\d+")) -> "Int"
            else -> null
        }
    }

    private fun getPrefix(text: String, cursor: Int): String {
        val before = text.substring(0, cursor)
        return before.substringAfterLast { !it.isLetterOrDigit() && it != '_' }
    }

    private fun String.substringAfterLast(predicate: (Char) -> Boolean): String {
        val idx = indexOfLast(predicate)
        return if (idx < 0) this else substring(idx + 1)
    }

    private fun isAfterColon(text: String, cursor: Int): Boolean {
        val before = text.substring(0, cursor).trimEnd()
        return before.endsWith(":")
    }

    // ???? ??업 ??시 ??????????????????????????????????????????????????????????????????????????????????????????????????????????????????

    private fun showPopup(suggestions: List<Suggestion>, prefix: String) {
        listView.adapter = SuggestionAdapter(suggestions)
        listView.setOnItemClickListener { _, _, pos, _ ->
            applySuggestion(suggestions[pos], prefix)
            dismiss()
        }

        val density = context.resources.displayMetrics.density
        popup.width = (300 * density).toInt()
        val maxH = (suggestions.size * 40 * density).toInt()
        popup.height = maxH.coerceAtMost((220 * density).toInt())

        val layout = editText.layout
        val caretLine = layout?.getLineForOffset(editText.selectionStart) ?: 0
        val caretX = if (layout != null) (layout.getPrimaryHorizontal(editText.selectionStart) + editText.paddingLeft + editText.scrollX).toInt() else 0
        val caretBaseline = if (layout != null) layout.getLineBottom(caretLine) + editText.paddingTop else 0
        val loc = IntArray(2)
        editText.getLocationInWindow(loc)
        var x = loc[0] + caretX
        var y = loc[1] + caretBaseline + (2 * density).toInt()
        val screenW = context.resources.displayMetrics.widthPixels
        val screenH = context.resources.displayMetrics.heightPixels
        val minX = (4 * density).toInt()
        x = x.coerceIn(minX, (screenW - popup.width - minX).coerceAtLeast(minX))
        if (y + popup.height > screenH) {
            val caretTop = if (layout != null) layout.getLineTop(caretLine) else 0
            y = (loc[1] + caretTop - popup.height).coerceAtLeast(minX)
        }
        if (!popup.isShowing) {
            popup.showAtLocation(editText.rootView, Gravity.TOP or Gravity.START, x, y)
        } else {
            popup.update(x, y, popup.width, popup.height)
        }
    }

    private fun applySuggestion(s: Suggestion, prefix: String) {
        val start = editText.selectionStart
        val textBefore = editText.text.toString().substring(0, start)
        val prefixStart = start - prefix.length
        editText.text.replace(prefixStart, start, s.insert)
        // ??수??괄호 ??에 커서
        if (s.insert.endsWith("()")) {
            editText.setSelection(prefixStart + s.insert.length - 1)
        }
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    private fun Int.dpToPx() =
        (this * context.resources.displayMetrics.density).toInt()

    private fun roundedBg(fill: Int, stroke: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            setColor(fill)
            setStroke((1 * context.resources.displayMetrics.density).toInt(), stroke)
        }

    // 어댑터 클래스?

    private inner class SuggestionAdapter(val items: List<Suggestion>) :
        ArrayAdapter<Suggestion>(context, 0, items) {

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[pos]
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14, 10, 14, 10)
                setBackgroundColor(Color.TRANSPARENT)
            }

            val icon = TextView(context).apply {
                text = when (item.kind) {
                    Suggestion.Kind.FUNCTION -> "f"
                    Suggestion.Kind.CLASS    -> "C"
                    Suggestion.Kind.VARIABLE -> "v"
                    Suggestion.Kind.SNIPPET  -> "S"
                    Suggestion.Kind.IMPORT   -> "i"
                    else                     -> "k"
                }
                setTextColor(when (item.kind) {
                    Suggestion.Kind.FUNCTION -> StudioUi.palette.synFunc
                    Suggestion.Kind.CLASS    -> StudioUi.palette.error
                    Suggestion.Kind.VARIABLE -> StudioUi.palette.synNum
                    Suggestion.Kind.SNIPPET  -> StudioUi.palette.synAnnotation
                    else                     -> StudioUi.palette.synKw
                })
                textSize = 10f
                typeface = Typeface.MONOSPACE
                width = 24.dpToPx()
            }

            val label = TextView(context).apply {
                text = item.label
                setTextColor(StudioUi.palette.text)
                textSize = 12f
                typeface = Typeface.MONOSPACE
            }

            val detail = TextView(context).apply {
                text = item.detail
                setTextColor(StudioUi.palette.textSoft)
                textSize = 10f
                setPadding(8, 1, 0, 0)
            }

            row.addView(icon); row.addView(label); row.addView(detail)
            return row
        }
    }

    // 보조 함수들?

    companion object {

        val KOTLIN_KEYWORDS = listOf(
            "abstract", "actual", "annotation", "as", "break", "by", "catch",
            "class", "companion", "const", "constructor", "continue", "crossinline",
            "data", "do", "else", "enum", "expect", "external", "false", "finally",
            "for", "fun", "if", "import", "in", "infix", "init", "inline", "inner",
            "interface", "internal", "is", "it", "lateinit", "noinline", "null",
            "object", "open", "operator", "out", "override", "package", "private",
            "protected", "public", "reified", "return", "sealed", "super", "suspend",
            "tailrec", "this", "throw", "true", "try", "typealias", "val", "var",
            "vararg", "when", "where", "while"
        )

        val KOTLIN_TYPES = listOf(
            "Int", "Long", "Float", "Double", "Boolean", "String", "Char", "Byte",
            "Short", "Unit", "Any", "Nothing", "List", "MutableList", "Map",
            "MutableMap", "Set", "MutableSet", "Array", "IntArray", "ByteArray",
            "Pair", "Triple", "Result", "Sequence", "Flow", "File", "Context",
            "Intent", "Bundle", "Uri", "View", "TextView", "EditText", "RecyclerView",
            "CoroutineScope", "Job", "Deferred", "Regex", "StringBuilder",
            "Modifier", "Composable", "ActivityResultLauncher", "Handler",
            "ProgressBar", "ScrollView", "ImageView", "Button", "CheckBox",
            "RadioButton", "Spinner", "AlertDialog", "PopupWindow",
            "NumberFormat", "SimpleDateFormat", "Calendar", "Color", "Bitmap"
        )

        /** love.<module> member completions generated from LuaApiRegistry (support status included). */
        private fun luaLoveModuleMembers(): Map<String, List<Suggestion>> {
            val built = mutableMapOf<String, MutableList<Suggestion>>()
            LuaApiRegistry.allApis.filter { it.name.startsWith("love.") }.forEach { api ->
                val rest = api.name.removePrefix("love.")
                val mod = rest.substringBefore('.')
                if (rest.contains('.')) {
                    built.getOrPut("love.$mod") { mutableListOf() }.add(Suggestion(
                        api.name.substringAfterLast('.'),
                        api.name.substringAfterLast('.'),
                        "${api.support}: ${api.signature} — ${api.description.take(50)}",
                        if (api.support == LuaApiRegistry.UNSUPPORTED) Suggestion.Kind.KEYWORD else Suggestion.Kind.FUNCTION
                    ))
                }
            }
            val result = mutableMapOf<String, List<Suggestion>>()
            built.forEach { (k, v) -> result[k] = v.toList() }
            result["love"] = (result["love"] ?: emptyList()) + listOf(
                Suggestion("graphics", "graphics", "love.graphics (Lua game runtime)", Suggestion.Kind.CLASS)
            )
            return result
        }

        val JAVA_KEYWORDS = listOf(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch",
            "char", "class", "const", "continue", "default", "do", "double",
            "else", "enum", "extends", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "instanceof", "int", "interface",
            "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "record", "var"
        )

        val C_KEYWORDS = listOf(
            "auto", "break", "case", "char", "const", "continue", "default", "do",
            "double", "else", "enum", "extern", "float", "for", "goto", "if",
            "inline", "int", "long", "register", "restrict", "return", "short",
            "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
            "unsigned", "void", "volatile", "while", "include", "define", "ifdef",
            "ifndef", "endif", "pragma", "JNIEXPORT", "JNIEnv", "jobject", "jstring"
        )

        val XML_KEYWORDS = listOf(
            "android", "app", "tools", "layout_width", "layout_height", "id", "text",
            "hint", "src", "background", "visibility", "orientation", "padding",
            "paddingStart", "paddingEnd", "margin", "layout_margin", "gravity",
            "layout_gravity", "contentDescription", "inputType", "autofillHints",
            "ConstraintLayout", "LinearLayout", "FrameLayout", "ScrollView",
            "TextView", "EditText", "Button", "ImageView", "RecyclerView"
        )

        val GRADLE_KEYWORDS = listOf(
            "plugins", "android", "namespace", "compileSdk", "defaultConfig",
            "applicationId", "minSdk", "targetSdk", "versionCode", "versionName",
            "dependencies", "implementation", "api", "compileOnly", "runtimeOnly",
            "testImplementation", "androidTestImplementation", "repositories",
            "mavenCentral", "google", "kotlinOptions", "buildFeatures",
            "externalNativeBuild", "cmake", "ndkVersion"
        )

        val PYTHON_KEYWORDS = listOf(
            "False", "None", "True", "and", "as", "assert", "async", "await",
            "break", "class", "continue", "def", "del", "elif", "else", "except",
            "finally", "for", "from", "global", "if", "import", "in", "is",
            "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
            "try", "while", "with", "yield"
        )

        val LUA_KEYWORDS = listOf(
            "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
            "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true",
            "until", "while", "self"
        )

        val JS_KEYWORDS = listOf(
            "var", "let", "const", "function", "return", "if", "else", "for", "while", "do",
            "switch", "case", "break", "continue", "new", "this", "typeof", "instanceof",
            "class", "extends", "super", "import", "export", "default", "from", "async",
            "await", "try", "catch", "finally", "throw", "yield", "delete", "void", "in", "of",
            "true", "false", "null", "undefined", "static", "get", "set"
        )

        val TS_KEYWORDS = (JS_KEYWORDS + listOf(
            "interface", "type", "enum", "implements", "readonly", "public", "private",
            "protected", "abstract", "declare", "namespace", "as", "satisfies", "keyof",
            "infer", "unknown", "never", "any", "string", "number", "boolean"
        )).distinct()

        val HTML_KEYWORDS = listOf(
            "div", "span", "p", "a", "img", "ul", "ol", "li", "table", "tr", "td", "th",
            "form", "input", "button", "select", "option", "textarea", "label", "h1", "h2",
            "h3", "h4", "h5", "h6", "header", "footer", "nav", "section", "article", "aside",
            "main", "script", "style", "link", "meta", "title", "body", "html", "head",
            "canvas", "video", "audio", "iframe", "svg", "path"
        )

        val CSS_KEYWORDS = listOf(
            "color", "background", "background-color", "background-image", "margin", "padding",
            "border", "border-radius", "width", "height", "display", "flex", "grid",
            "position", "top", "left", "right", "bottom", "font-size", "font-family",
            "font-weight", "text-align", "line-height", "opacity", "overflow", "z-index",
            "transition", "transform", "animation", "box-shadow", "justify-content",
            "align-items", "flex-direction", "gap", "important", "hover", "media"
        )

        val RUST_KEYWORDS = listOf(
            "fn", "let", "mut", "const", "struct", "enum", "trait", "impl",
            "match", "if", "else", "loop", "while", "for", "in", "return", "break", "continue",
            "pub", "use", "mod", "crate", "self", "super", "where", "async", "await", "move",
            "dyn", "ref", "unsafe", "as", "true", "false", "Some", "None", "Ok", "Err", "Box", "Vec", "String"
        )

        val GO_KEYWORDS = listOf(
            "func", "var", "const", "type", "struct", "interface", "map", "chan", "go",
            "defer", "if", "else", "for", "range", "switch", "case", "default", "return",
            "break", "continue", "package", "import", "select", "fallthrough", "goto",
            "nil", "true", "false", "make", "new", "len", "cap", "append", "error", "string", "int", "bool"
        )

        val SWIFT_KEYWORDS = listOf(
            "func", "var", "let", "struct", "class", "enum", "protocol", "extension",
            "if", "else", "guard", "switch", "case", "for", "while", "repeat", "in", "return",
            "break", "continue", "import", "init", "deinit", "self", "super", "static", "where",
            "async", "await", "throws", "try", "catch", "defer", "nil", "true", "false",
            "String", "Int", "Double", "Bool", "Array", "Dictionary", "Optional"
        )

        val DART_KEYWORDS = listOf(
            "var", "final", "const", "void", "class", "extends", "implements", "with", "abstract",
            "if", "else", "for", "while", "do", "switch", "case", "return", "break", "continue",
            "import", "export", "library", "part", "async", "await", "yield", "try", "catch", "finally",
            "throw", "rethrow", "this", "super", "static", "new", "late", "required", "dynamic",
            "true", "false", "null", "String", "int", "double", "bool", "List", "Map", "Set",
            "Widget", "StatelessWidget", "StatefulWidget"
        )

        val SQL_KEYWORDS = listOf(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "CREATE", "TABLE", "DROP", "ALTER", "ADD", "COLUMN",
            "JOIN", "ON", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET", "DISTINCT",
            "COUNT", "SUM", "AVG", "MIN", "MAX",
            "PRIMARY KEY", "FOREIGN KEY", "REFERENCES", "INDEX", "UNIQUE", "NOT NULL", "DEFAULT",
            "AND", "OR", "NOT", "LIKE", "IN", "BETWEEN", "IS NULL", "AS", "ASC", "DESC"
        )

        val SHELL_KEYWORDS = listOf(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case", "esac",
            "function", "return", "exit", "echo", "export", "source", "local", "readonly",
            "cd", "ls", "mkdir", "rm", "cp", "mv", "cat", "grep", "find", "chmod", "chown",
            "apt", "pkg", "git", "curl", "wget", "tar", "unzip", "sed", "awk", "printf"
        )

        val GD_KEYWORDS = listOf(
            "func", "var", "const", "extends", "if", "elif", "else", "for", "while", "return",
            "match", "break", "continue", "pass", "class_name", "signal", "enum", "export",
            "self", "true", "false", "null", "and", "or", "not", "in", "is", "as",
            "await", "static", "yield", "assert", "preload"
        )

        val GD_BUILTIN_FUNCS = listOf(
            "draw_rect(", "draw_circle(", "draw_text(", "clear(", "print(", "str(",
            "screen_width", "screen_height", "_ready(", "_process(", "on_touch("
        )

        val JSON_KEYWORDS = listOf("true", "false", "null")
        val YAML_KEYWORDS = listOf("true", "false", "null", "on", "off", "yes", "no")
        val CMAKE_KEYWORDS = listOf(
            "cmake_minimum_required", "project", "add_executable", "add_library", "add_subdirectory",
            "target_link_libraries", "target_include_directories", "target_compile_options",
            "set", "set_target_properties", "if", "else", "elseif", "endif", "foreach", "endforeach",
            "function", "endfunction", "macro", "endmacro", "include", "find_package", "find_library",
            "message", "option", "install", "file", "string", "list", "math", "return", "unset"
        )

        val JAVA_TYPES = listOf(
            "String", "Integer", "Long", "Float", "Double", "Boolean", "Character",
            "Object", "List", "ArrayList", "Map", "HashMap", "Set", "HashSet",
            "Runnable", "Thread", "File", "Intent", "Bundle", "Context", "View",
            "TextView", "EditText", "RecyclerView"
        )

        val C_TYPES = listOf(
            "int", "long", "float", "double", "char", "void", "size_t", "uint8_t",
            "uint32_t", "bool", "JNIEnv", "jobject", "jstring", "jint", "jlong"
        )

        val ALL_KEYWORDS = (KOTLIN_KEYWORDS + JAVA_KEYWORDS + C_KEYWORDS).distinct()
        val ALL_TYPES = (KOTLIN_TYPES + JAVA_TYPES + C_TYPES).distinct()

        val SNIPPETS = listOf(
            Suggestion("fun", "fun name(): Unit {\n    \n}", "function", Suggestion.Kind.SNIPPET),
            Suggestion("class", "class Name {\n    \n}", "class", Suggestion.Kind.SNIPPET),
            Suggestion("data class", "data class Name(val field: Type)", "data class", Suggestion.Kind.SNIPPET),
            Suggestion("when", "when (expr) {\n    else -> {}\n}", "when expression", Suggestion.Kind.SNIPPET),
            Suggestion("for", "for (item in list) {\n    \n}", "for loop", Suggestion.Kind.SNIPPET),
            Suggestion("if", "if (condition) {\n    \n} else {\n    \n}", "if-else", Suggestion.Kind.SNIPPET),
            Suggestion("try", "try {\n    \n} catch (e: Exception) {\n    \n}", "try-catch", Suggestion.Kind.SNIPPET),
            Suggestion("launch", "launch {\n    \n}", "coroutine", Suggestion.Kind.SNIPPET),
            Suggestion("println", "println()", "print to console", Suggestion.Kind.FUNCTION),
            Suggestion("listOf", "listOf()", "create list", Suggestion.Kind.FUNCTION),
            Suggestion("mapOf", "mapOf()", "create map", Suggestion.Kind.FUNCTION),
            Suggestion("TODO", "TODO(\"Not implemented\")", "placeholder", Suggestion.Kind.SNIPPET),
            Suggestion("suspend fun", "suspend fun name() {\n    \n}", "coroutine function", Suggestion.Kind.SNIPPET),
            Suggestion("withContext", "withContext(Dispatchers.IO) {\n    \n}", "coroutine context", Suggestion.Kind.SNIPPET),
            Suggestion("runCatching", "runCatching {\n    \n}.onFailure { it.printStackTrace() }", "result block", Suggestion.Kind.SNIPPET),
            Suggestion("lazy", "by lazy {  }", "lazy delegate", Suggestion.Kind.SNIPPET),
            Suggestion("companion object", "companion object {\n    \n}", "static-like scope", Suggestion.Kind.SNIPPET),
            Suggestion("public class", "public class Name {\n    \n}", "java class", Suggestion.Kind.SNIPPET),
            Suggestion("main", "public static void main(String[] args) {\n    \n}", "java main", Suggestion.Kind.SNIPPET),
            Suggestion("sout", "System.out.println();", "java print", Suggestion.Kind.SNIPPET),
            Suggestion("trycatch", "try {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}", "java try-catch", Suggestion.Kind.SNIPPET),
            Suggestion("#include", "#include <stdio.h>", "c include", Suggestion.Kind.SNIPPET),
            Suggestion("cmain", "int main(void) {\n    return 0;\n}", "c main", Suggestion.Kind.SNIPPET),
            Suggestion("printf", "printf(\"%s\\n\", \"\");", "c print", Suggestion.Kind.FUNCTION),
            Suggestion("struct", "typedef struct Name {\n    \n} Name;", "c struct", Suggestion.Kind.SNIPPET),
            Suggestion("jni", "JNIEXPORT jstring JNICALL\nJava_package_MainActivity_stringFromJNI(JNIEnv *env, jobject thiz) {\n    return (*env)->NewStringUTF(env, \"Hello\");\n}", "JNI function", Suggestion.Kind.SNIPPET),
            // Compose snippets
            Suggestion("Composable", "@Composable\nfun Name() {\n    Column {\n        Text(\"Hello\")\n    }\n}", "compose function", Suggestion.Kind.SNIPPET),
            Suggestion("Column", "Column(\n    modifier = Modifier.padding(16.dp),\n    verticalArrangement = Arrangement.spacedBy(8.dp)\n) {\n    \n}", "compose Column", Suggestion.Kind.SNIPPET),
            Suggestion("Row", "Row(\n    modifier = Modifier.fillMaxWidth(),\n    horizontalArrangement = Arrangement.SpaceBetween\n) {\n    \n}", "compose Row", Suggestion.Kind.SNIPPET),
            Suggestion("Box", "Box(\n    modifier = Modifier.fillMaxSize()\n) {\n    \n}", "compose Box", Suggestion.Kind.SNIPPET),
            Suggestion("Text", "Text(text = \"\", color = Color.White)", "compose Text", Suggestion.Kind.SNIPPET),
            Suggestion("Button", "Button(onClick = {  }) {\n    Text(\"Click\")\n}", "compose Button", Suggestion.Kind.SNIPPET),
            Suggestion("TextField", "var value by remember { mutableStateOf(\"\") }\nOutlinedTextField(value = value, onValueChange = { value = it })", "compose TextField", Suggestion.Kind.SNIPPET),
            Suggestion("Image", "Image(painter = painterResource(id = R.drawable.icon), contentDescription = null)", "compose Image", Suggestion.Kind.SNIPPET),
            Suggestion("LazyColumn", "LazyColumn {\n    items(items) { item ->\n        Text(text = item.toString())\n    }\n}", "compose LazyColumn", Suggestion.Kind.SNIPPET),
            Suggestion("remember", "remember { mutableStateOf() }", "compose state", Suggestion.Kind.SNIPPET),
            Suggestion("mutableStateOf", "mutableStateOf(initialValue)", "compose mutable state", Suggestion.Kind.SNIPPET),
            Suggestion("setContent", "setContent {\n    MaterialTheme {\n        \n    }\n}", "Activity content", Suggestion.Kind.SNIPPET),
            // Android snippets
            Suggestion("onCreate", "override fun onCreate(savedInstanceState: Bundle?) {\n    super.onCreate(savedInstanceState)\n    setContentView(R.layout.activity_main)\n}", "Activity lifecycle", Suggestion.Kind.SNIPPET),
            Suggestion("onClick", "setOnClickListener {\n    \n}", "view click", Suggestion.Kind.SNIPPET),
            Suggestion("intent", "Intent(this, TargetActivity::class.java).apply {\n    putExtra(\"key\", value)\n    startActivity(this)\n}", "intent launch", Suggestion.Kind.SNIPPET),
            Suggestion("viewBinding", "private lateinit var binding: ActivityMainBinding\nbinding = ActivityMainBinding.inflate(layoutInflater)\nsetContentView(binding.root)", "ViewBinding", Suggestion.Kind.SNIPPET),
            Suggestion("toast", "Toast.makeText(this, \"message\", Toast.LENGTH_SHORT).show()", "show toast", Suggestion.Kind.SNIPPET),
            Suggestion("sharedPrefs", "getSharedPreferences(\"name\", Context.MODE_PRIVATE).edit().putString(\"key\", \"value\").apply()", "SharedPreferences", Suggestion.Kind.SNIPPET),
            Suggestion("handler", "Handler(Looper.getMainLooper()).postDelayed({\n    \n}, 1000)", "delayed handler", Suggestion.Kind.SNIPPET),
            Suggestion("coroutineScope", "val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())\nscope.launch {\n    \n}", "coroutine scope", Suggestion.Kind.SNIPPET),
            Suggestion("retrofit", "Retrofit.Builder()\n    .baseUrl(\"https://api.example.com/\")\n    .addConverterFactory(GsonConverterFactory.create())\n    .build()", "Retrofit client", Suggestion.Kind.SNIPPET),
            Suggestion("roomDao", "@Dao\ninterface ItemDao {\n    @Query(\"SELECT * FROM items\")\n    suspend fun getAll(): List<Item>\n    \n    @Insert\n    suspend fun insert(item: Item)\n}", "Room DAO", Suggestion.Kind.SNIPPET),
        )

        val JAVA_SNIPPETS = listOf(
            Suggestion("class", "public class Name {\n    \n}", "java class", Suggestion.Kind.SNIPPET),
            Suggestion("main", "public static void main(String[] args) {\n    \n}", "java main", Suggestion.Kind.SNIPPET),
            Suggestion("sout", "System.out.println();", "java print", Suggestion.Kind.SNIPPET),
            Suggestion("println", "System.out.println()", "java print", Suggestion.Kind.FUNCTION),
            Suggestion("try", "try {\n    \n} catch (Exception e) {\n    e.printStackTrace();\n}", "java try-catch", Suggestion.Kind.SNIPPET),
            Suggestion("for", "for (int i = 0; i < count; i++) {\n    \n}", "java for", Suggestion.Kind.SNIPPET)
        )

        val C_SNIPPETS = listOf(
            Suggestion("#include", "#include <stdio.h>", "c include", Suggestion.Kind.SNIPPET),
            Suggestion("main", "int main(void) {\n    return 0;\n}", "c main", Suggestion.Kind.SNIPPET),
            Suggestion("printf", "printf(\"%s\\n\", \"\");", "c print", Suggestion.Kind.FUNCTION),
            Suggestion("fprintf", "fprintf(stderr, \"%s\\n\", \"\");", "c stderr", Suggestion.Kind.FUNCTION),
            Suggestion("struct", "typedef struct Name {\n    \n} Name;", "c struct", Suggestion.Kind.SNIPPET),
            Suggestion("jni", "JNIEXPORT jstring JNICALL\nJava_package_MainActivity_stringFromJNI(JNIEnv *env, jobject thiz) {\n    return (*env)->NewStringUTF(env, \"Hello\");\n}", "JNI function", Suggestion.Kind.SNIPPET)
        )

        val XML_SNIPPETS = listOf(
            Suggestion("TextView", "<TextView\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"\" />", "Android widget", Suggestion.Kind.SNIPPET),
            Suggestion("Button", "<Button\n    android:layout_width=\"wrap_content\"\n    android:layout_height=\"wrap_content\"\n    android:text=\"\" />", "Android widget", Suggestion.Kind.SNIPPET),
            Suggestion("LinearLayout", "<LinearLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\"\n    android:orientation=\"vertical\">\n\n</LinearLayout>", "Android layout", Suggestion.Kind.SNIPPET),
            Suggestion("ConstraintLayout", "<androidx.constraintlayout.widget.ConstraintLayout\n    android:layout_width=\"match_parent\"\n    android:layout_height=\"match_parent\">\n\n</androidx.constraintlayout.widget.ConstraintLayout>", "ConstraintLayout", Suggestion.Kind.SNIPPET),
            Suggestion("xmlns", "xmlns:android=\"http://schemas.android.com/apk/res/android\"\nxmlns:app=\"http://schemas.android.com/apk/res-auto\"", "XML namespaces", Suggestion.Kind.SNIPPET)
        )

        val GRADLE_SNIPPETS = listOf(
            Suggestion("plugins", "plugins {\n    id(\"com.android.application\")\n}", "Gradle plugins", Suggestion.Kind.SNIPPET),
            Suggestion("android", "android {\n    namespace = \"\"\n    compileSdk = 35\n\n    defaultConfig {\n        minSdk = 26\n        targetSdk = 35\n    }\n}", "Android DSL", Suggestion.Kind.SNIPPET),
            Suggestion("dependencies", "dependencies {\n    implementation(\"\")\n}", "Dependencies", Suggestion.Kind.SNIPPET),
            Suggestion("implementation", "implementation(\"\")", "Dependency", Suggestion.Kind.FUNCTION)
        )

        val PYTHON_SNIPPETS = listOf(
            Suggestion("def", "def name():\n    pass", "python function", Suggestion.Kind.SNIPPET),
            Suggestion("class", "class Name:\n    def __init__(self):\n        pass", "python class", Suggestion.Kind.SNIPPET),
            Suggestion("main", "if __name__ == \"__main__\":\n    main()", "python main guard", Suggestion.Kind.SNIPPET),
            Suggestion("print", "print()", "python output", Suggestion.Kind.FUNCTION),
            Suggestion("try", "try:\n    pass\nexcept Exception as exc:\n    print(exc)", "python try-except", Suggestion.Kind.SNIPPET),
            Suggestion("kivy app", "from kivy.app import App\n\nclass MainApp(App):\n    def build(self):\n        return None\n\nif __name__ == \"__main__\":\n    MainApp().run()", "Kivy app", Suggestion.Kind.SNIPPET)
        )

        val LUA_SNIPPETS = listOf(
            Suggestion("function", "function \$CURSOR()\n    \nend", "Lua function", Suggestion.Kind.SNIPPET),
            Suggestion("local", "local \$CURSOR = ", "Lua local variable", Suggestion.Kind.SNIPPET),
            Suggestion("forin", "for i, v in ipairs(\$CURSOR) do\n    \nend", "Lua for-in loop", Suggestion.Kind.SNIPPET),
            Suggestion("fornum", "for i = 1, 10 do\n    \nend", "Lua numeric for", Suggestion.Kind.SNIPPET),
            Suggestion("ifthen", "if \$CURSOR then\n    \nend", "Lua if", Suggestion.Kind.SNIPPET),
            Suggestion("while", "while \$CURSOR do\n    \nend", "Lua while", Suggestion.Kind.SNIPPET),
            Suggestion("gamefunc", "function update(dt)\n    \nend", "Game update loop", Suggestion.Kind.SNIPPET),
            Suggestion("ontouch", "function onTouch(action, x, y)\n    \nend", "Game touch handler", Suggestion.Kind.SNIPPET)
        )

        val JS_SNIPPETS = listOf(
            Suggestion("function", "function \$CURSOR() {\n    \n}", "JS function", Suggestion.Kind.SNIPPET),
            Suggestion("arrow", "const \$CURSOR = () => {\n    \n};", "Arrow function", Suggestion.Kind.SNIPPET),
            Suggestion("forof", "for (const item of \$CURSOR) {\n    \n}", "for-of loop", Suggestion.Kind.SNIPPET),
            Suggestion("fori", "for (let i = 0; i < arr.length; i++) {\n    \n}", "Classic for", Suggestion.Kind.SNIPPET),
            Suggestion("ifelse", "if (\$CURSOR) {\n    \n} else {\n    \n}", "if-else", Suggestion.Kind.SNIPPET),
            Suggestion("trycatch", "try {\n    \n} catch (e) {\n    console.error(e);\n}", "try-catch", Suggestion.Kind.SNIPPET),
            Suggestion("log", "console.log(\$CURSOR);", "console.log", Suggestion.Kind.SNIPPET),
            Suggestion("promise", "new Promise((resolve, reject) => {\n    \n})", "Promise", Suggestion.Kind.SNIPPET),
            Suggestion("gameupdate", "function update(dt) {\n    \n}", "Game update loop", Suggestion.Kind.SNIPPET)
        )

        val GD_SNIPPETS = listOf(
            Suggestion("func", "func \$CURSOR():\n    pass", "GDScript function", Suggestion.Kind.SNIPPET),
            Suggestion("var", "var \$CURSOR = 0.0", "GDScript variable", Suggestion.Kind.SNIPPET),
            Suggestion("ready", "func _ready():\n    pass", "_ready callback", Suggestion.Kind.SNIPPET),
            Suggestion("process", "func _process(dt):\n    pass", "_process callback", Suggestion.Kind.SNIPPET),
            Suggestion("ontouch", "func on_touch(action, x, y):\n    pass", "Touch handler", Suggestion.Kind.SNIPPET)
        )

        private fun s(label: String, insert: String = label, detail: String = "", kind: Suggestion.Kind = Suggestion.Kind.FUNCTION) =
            Suggestion(label, insert, detail, kind)

        // ??주 ??는 ??래????이브러??멤버 ??트
        val STDLIB_MEMBERS: Map<String, List<Suggestion>> = mapOf(
            "String" to listOf(
                Suggestion("length", "length", "Int", Suggestion.Kind.VARIABLE),
                Suggestion("isEmpty()", "isEmpty()", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("isNotEmpty()", "isNotEmpty()", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("trim()", "trim()", "String", Suggestion.Kind.FUNCTION),
                Suggestion("split()", "split(\"\")", "List<String>", Suggestion.Kind.FUNCTION),
                Suggestion("contains()", "contains(\"\")", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("replace()", "replace(\"\", \"\")", "String", Suggestion.Kind.FUNCTION),
                Suggestion("startsWith()", "startsWith(\"\")", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("endsWith()", "endsWith(\"\")", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("uppercase()", "uppercase()", "String", Suggestion.Kind.FUNCTION),
                Suggestion("lowercase()", "lowercase()", "String", Suggestion.Kind.FUNCTION),
                Suggestion("toInt()", "toInt()", "Int", Suggestion.Kind.FUNCTION),
                Suggestion("lines()", "lines()", "List<String>", Suggestion.Kind.FUNCTION),
            ),
            "List" to listOf(
                Suggestion("size", "size", "Int", Suggestion.Kind.VARIABLE),
                Suggestion("isEmpty()", "isEmpty()", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("first()", "first()", "T", Suggestion.Kind.FUNCTION),
                Suggestion("last()", "last()", "T", Suggestion.Kind.FUNCTION),
                Suggestion("map { }", "map { it }", "List<R>", Suggestion.Kind.FUNCTION),
                Suggestion("filter { }", "filter { it }", "List<T>", Suggestion.Kind.FUNCTION),
                Suggestion("forEach { }", "forEach { }", "Unit", Suggestion.Kind.FUNCTION),
                Suggestion("find { }", "find { it }", "T?", Suggestion.Kind.FUNCTION),
                Suggestion("any { }", "any { it }", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("all { }", "all { it }", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("joinToString()", "joinToString(\", \")", "String", Suggestion.Kind.FUNCTION),
                Suggestion("sortedBy { }", "sortedBy { it }", "List<T>", Suggestion.Kind.FUNCTION),
            ),
            "Map" to listOf(
                s("size", detail = "Int", kind = Suggestion.Kind.VARIABLE),
                s("keys", detail = "Set<K>", kind = Suggestion.Kind.VARIABLE),
                s("values", detail = "Collection<V>", kind = Suggestion.Kind.VARIABLE),
                s("get()", "get(\"\")", "V?"),
                s("containsKey()", "containsKey(\"\")", "Boolean"),
                s("mapValues { }", "mapValues { it.value }", "Map<K, R>")
            ),
            "File" to listOf(
                Suggestion("exists()", "exists()", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("readText()", "readText()", "String", Suggestion.Kind.FUNCTION),
                Suggestion("writeText()", "writeText(\"\")", "Unit", Suggestion.Kind.FUNCTION),
                Suggestion("listFiles()", "listFiles()", "Array<File>?", Suggestion.Kind.FUNCTION),
                Suggestion("mkdirs()", "mkdirs()", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("absolutePath", "absolutePath", "String", Suggestion.Kind.VARIABLE),
                Suggestion("name", "name", "String", Suggestion.Kind.VARIABLE),
                Suggestion("length()", "length()", "Long", Suggestion.Kind.FUNCTION),
                Suggestion("delete()", "delete()", "Boolean", Suggestion.Kind.FUNCTION),
                Suggestion("copyTo()", "copyTo(File(\"\"))", "File", Suggestion.Kind.FUNCTION),
            ),
            "Context" to listOf(
                Suggestion("filesDir", "filesDir", "File", Suggestion.Kind.VARIABLE),
                Suggestion("cacheDir", "cacheDir", "File", Suggestion.Kind.VARIABLE),
                Suggestion("applicationInfo", "applicationInfo", "ApplicationInfo", Suggestion.Kind.VARIABLE),
                Suggestion("getSharedPreferences()", "getSharedPreferences(\"\", MODE_PRIVATE)", "SharedPreferences", Suggestion.Kind.FUNCTION),
                Suggestion("startActivity()", "startActivity(Intent(this, TargetActivity::class.java))", "Unit", Suggestion.Kind.FUNCTION),
            ),
            "StringBuilder" to listOf(
                s("append()", "append(\"\")", "StringBuilder"),
                s("toString()", "toString()", "String"),
                s("clear()", "clear()", "StringBuilder"),
                s("length", detail = "Int", kind = Suggestion.Kind.VARIABLE)
            ),
            "Regex" to listOf(
                s("find()", "find(input)", "MatchResult?"),
                s("findAll()", "findAll(input)", "Sequence<MatchResult>"),
                s("matches()", "matches(input)", "Boolean"),
                s("replace()", "replace(input, \"\")", "String")
            ),
            "Intent" to listOf(
                s("putExtra()", "putExtra(\"key\", value)", "Intent"),
                s("getStringExtra()", "getStringExtra(\"key\")", "String?"),
                s("setAction()", "setAction(Intent.ACTION_VIEW)", "Intent"),
                s("data", detail = "Uri?", kind = Suggestion.Kind.VARIABLE)
            ),
            "Bundle" to listOf(
                s("putString()", "putString(\"key\", value)", "Unit"),
                s("getString()", "getString(\"key\")", "String?"),
                s("putInt()", "putInt(\"key\", value)", "Unit"),
                s("containsKey()", "containsKey(\"key\")", "Boolean")
            ),
            "Uri" to listOf(
                s("parse()", "parse(\"\")", "Uri"),
                s("toString()", "toString()", "String"),
                s("scheme", detail = "String?", kind = Suggestion.Kind.VARIABLE),
                s("host", detail = "String?", kind = Suggestion.Kind.VARIABLE)
            ),
            "View" to listOf(
                s("visibility", detail = "Int", kind = Suggestion.Kind.VARIABLE),
                s("setOnClickListener { }", "setOnClickListener {  }", "Unit"),
                s("findViewById()", "findViewById(R.id.)", "View"),
                s("setPadding()", "setPadding(0, 0, 0, 0)", "Unit")
            ),
            "TextView" to listOf(
                s("text", detail = "CharSequence", kind = Suggestion.Kind.VARIABLE),
                s("setTextColor()", "setTextColor(Color.BLACK)", "Unit"),
                s("textSize", detail = "Float", kind = Suggestion.Kind.VARIABLE),
                s("typeface", detail = "Typeface", kind = Suggestion.Kind.VARIABLE)
            ),
            "EditText" to listOf(
                s("text", detail = "Editable", kind = Suggestion.Kind.VARIABLE),
                s("setSelection()", "setSelection(text.length)", "Unit"),
                s("addTextChangedListener()", "addTextChangedListener(object : TextWatcher { })", "Unit")
            ),
            "CoroutineScope" to listOf(
                s("launch { }", "launch {  }", "Job"),
                s("async { }", "async {  }", "Deferred<T>"),
                s("cancel()", "cancel()", "Unit")
            ),
            "Job" to listOf(
                s("cancel()", "cancel()", "Unit"),
                s("isActive", detail = "Boolean", kind = Suggestion.Kind.VARIABLE),
                s("join()", "join()", "Unit")
            )
        )

        val LIBRARY_PACKAGES: Map<String, List<Suggestion>> = mapOf(
            "kotlinx.coroutines" to listOf(
                s("launch", "launch {  }", "Job"),
                s("async", "async {  }", "Deferred<T>"),
                s("withContext", "withContext(Dispatchers.IO) {  }", "T"),
                s("delay", "delay(1000)", "Unit"),
                s("Dispatchers.IO", "Dispatchers.IO", "CoroutineDispatcher"),
                s("SupervisorJob", "SupervisorJob()", "Job")
            ),
            "java.io" to listOf(
                s("File", "File(\"\")", "class", Suggestion.Kind.CLASS),
                s("FileInputStream", "FileInputStream(file)", "class", Suggestion.Kind.CLASS),
                s("FileOutputStream", "FileOutputStream(file)", "class", Suggestion.Kind.CLASS),
                s("BufferedReader", "BufferedReader(reader)", "class", Suggestion.Kind.CLASS)
            ),
            "android.content" to listOf(
                s("Intent", "Intent(this, TargetActivity::class.java)", "class", Suggestion.Kind.CLASS),
                s("Context", "Context", "class", Suggestion.Kind.CLASS),
                s("SharedPreferences", "SharedPreferences", "interface", Suggestion.Kind.CLASS),
                s("BroadcastReceiver", "BroadcastReceiver", "class", Suggestion.Kind.CLASS)
            ),
            "android.os" to listOf(
                s("Bundle", "Bundle()", "class", Suggestion.Kind.CLASS),
                s("Handler", "Handler(Looper.getMainLooper())", "class", Suggestion.Kind.CLASS),
                s("Looper", "Looper.getMainLooper()", "class", Suggestion.Kind.CLASS),
                s("Build.VERSION.SDK_INT", "Build.VERSION.SDK_INT", "Int")
            ),
            "android.view" to listOf(
                s("View", "View(context)", "class", Suggestion.Kind.CLASS),
                s("ViewGroup", "ViewGroup", "class", Suggestion.Kind.CLASS),
                s("Gravity", "Gravity.CENTER", "object", Suggestion.Kind.CLASS),
                s("LayoutInflater", "LayoutInflater.from(context)", "class", Suggestion.Kind.CLASS),
                s("WindowInsetsController", "WindowInsetsController", "class", Suggestion.Kind.CLASS)
            ),
            "android.widget" to listOf(
                s("TextView", "TextView(context)", "class", Suggestion.Kind.CLASS),
                s("EditText", "EditText(context)", "class", Suggestion.Kind.CLASS),
                s("LinearLayout", "LinearLayout(context)", "class", Suggestion.Kind.CLASS),
                s("Toast", "Toast.makeText(context, \"\", Toast.LENGTH_SHORT).show()", "toast"),
                s("ProgressBar", "ProgressBar(context)", "class", Suggestion.Kind.CLASS),
                s("ImageView", "ImageView(context)", "class", Suggestion.Kind.CLASS),
                s("Button", "Button(context)", "class", Suggestion.Kind.CLASS),
                s("ScrollView", "ScrollView(context)", "class", Suggestion.Kind.CLASS),
                s("ListView", "ListView(context)", "class", Suggestion.Kind.CLASS),
                s("RecyclerView", "RecyclerView(context)", "class", Suggestion.Kind.CLASS)
            ),
            "androidx.recyclerview.widget" to listOf(
                s("RecyclerView", "RecyclerView(context)", "class", Suggestion.Kind.CLASS),
                s("LinearLayoutManager", "LinearLayoutManager(context)", "class", Suggestion.Kind.CLASS),
                s("GridLayoutManager", "GridLayoutManager(context, 2)", "class", Suggestion.Kind.CLASS)
            ),
            "androidx.appcompat.app" to listOf(
                s("AppCompatActivity", "AppCompatActivity", "class", Suggestion.Kind.CLASS),
                s("AlertDialog.Builder", "AlertDialog.Builder(this)", "dialog", Suggestion.Kind.CLASS)
            ),
            "android.net" to listOf(
                s("Uri", "Uri.parse(\"\")", "class", Suggestion.Kind.CLASS)
            ),
            // Compose packages
            "androidx.compose.foundation.layout" to listOf(
                s("Column", "Column {  }", "compose layout", Suggestion.Kind.SNIPPET),
                s("Row", "Row {  }", "compose layout", Suggestion.Kind.SNIPPET),
                s("Box", "Box {  }", "compose layout", Suggestion.Kind.SNIPPET),
                s("Spacer", "Spacer(modifier = Modifier.height(16.dp))", "compose spacer", Suggestion.Kind.SNIPPET),
                s("Arrangement", "Arrangement.SpaceBetween", "compose arrangement", Suggestion.Kind.CLASS),
                s("fillMaxSize", "Modifier.fillMaxSize()", "compose modifier", Suggestion.Kind.FUNCTION),
                s("fillMaxWidth", "Modifier.fillMaxWidth()", "compose modifier", Suggestion.Kind.FUNCTION),
                s("padding", "Modifier.padding(16.dp)", "compose modifier", Suggestion.Kind.FUNCTION)
            ),
            "androidx.compose.material3" to listOf(
                s("MaterialTheme", "MaterialTheme {  }", "compose theme", Suggestion.Kind.SNIPPET),
                s("Button", "Button(onClick = {  }) { Text(\"\") }", "compose button", Suggestion.Kind.SNIPPET),
                s("Text", "Text(text = \"\")", "compose text", Suggestion.Kind.SNIPPET),
                s("TextField", "OutlinedTextField(value = \"\", onValueChange = { })", "compose textfield", Suggestion.Kind.SNIPPET),
                s("Surface", "Surface {  }", "compose surface", Suggestion.Kind.SNIPPET),
                s("Scaffold", "Scaffold { padding -> }", "compose scaffold", Suggestion.Kind.SNIPPET),
                s("TopAppBar", "TopAppBar(title = { Text(\"\") })", "compose appbar", Suggestion.Kind.SNIPPET),
                s("NavigationBar", "NavigationBar {  }", "compose bottom nav", Suggestion.Kind.SNIPPET),
                s("Card", "Card { Text(\"\") }", "compose card", Suggestion.Kind.SNIPPET)
            ),
            "androidx.compose.ui" to listOf(
                s("Modifier", "Modifier", "compose modifier", Suggestion.Kind.CLASS),
                s("Color", "Color.White", "compose color", Suggestion.Kind.CLASS),
                s("Alignment", "Alignment.Center", "compose alignment", Suggestion.Kind.CLASS)
            ),
            "androidx.compose.runtime" to listOf(
                s("remember", "remember { mutableStateOf() }", "compose state", Suggestion.Kind.FUNCTION),
                s("mutableStateOf", "mutableStateOf()", "compose mutable state", Suggestion.Kind.FUNCTION),
                s("LaunchedEffect", "LaunchedEffect(Unit) {  }", "compose effect", Suggestion.Kind.FUNCTION),
                s("State", "State<T>", "compose state type", Suggestion.Kind.CLASS),
                s("MutableState", "MutableState<T>", "compose mutable state type", Suggestion.Kind.CLASS)
            ),
            "androidx.activity.compose" to listOf(
                s("setContent", "setContent { MaterialTheme {  } }", "compose content", Suggestion.Kind.FUNCTION)
            )
        )

        val LIBRARY_MEMBERS: Map<String, List<Suggestion>> = STDLIB_MEMBERS + mapOf<String, List<Suggestion>>(
            // Lua game runtime: love.* modules + engine.* API (from LuaApiRegistry)
            "love" to (LuaApiRegistry.implementedModules().map { mod ->
                Suggestion(mod, mod, "love.$mod (Lua game runtime)", Suggestion.Kind.CLASS)
            } + listOf(
                Suggestion("touch", "touch", "love.touch (Lua game runtime)", Suggestion.Kind.CLASS),
                Suggestion("keyboard", "keyboard", "love.keyboard (Lua game runtime)", Suggestion.Kind.CLASS),
                Suggestion("event", "event", "love.event (Lua game runtime)", Suggestion.Kind.CLASS)
            )),
            "engine" to LuaApiRegistry.allApis
                .filter { it.name.startsWith("engine.") }
                .map { api ->
                    val shortName = api.name.removePrefix("engine.")
                    Suggestion(
                        shortName,
                        shortName,
                        "${api.support}: ${api.signature} — ${api.description.take(50)}",
                        if (api.support == LuaApiRegistry.UNSUPPORTED) Suggestion.Kind.KEYWORD else Suggestion.Kind.FUNCTION
                    )
                }
        ) + luaLoveModuleMembers() + mapOf<String, List<Suggestion>>(
            "Dispatchers" to listOf(                s("IO", "IO", "CoroutineDispatcher", Suggestion.Kind.VARIABLE),
                s("Main", "Main", "CoroutineDispatcher", Suggestion.Kind.VARIABLE),
                s("Default", "Default", "CoroutineDispatcher", Suggestion.Kind.VARIABLE)
            ),
            "Toast" to listOf(
                s("makeText()", "makeText(context, \"\", Toast.LENGTH_SHORT)", "Toast"),
                s("LENGTH_SHORT", "LENGTH_SHORT", "Int", Suggestion.Kind.VARIABLE),
                s("LENGTH_LONG", "LENGTH_LONG", "Int", Suggestion.Kind.VARIABLE)
            ),
            "AlertDialog" to listOf(
                s("Builder()", "Builder(this)", "AlertDialog.Builder")
            ),
            "RecyclerView" to listOf(
                s("adapter", detail = "Adapter<*>?", kind = Suggestion.Kind.VARIABLE),
                s("layoutManager", detail = "LayoutManager?", kind = Suggestion.Kind.VARIABLE),
                s("setHasFixedSize()", "setHasFixedSize(true)", "Unit")
            ),
            "System" to listOf(
                s("out.println()", "out.println()", "java print"),
                s("currentTimeMillis()", "currentTimeMillis()", "long"),
                s("arraycopy()", "arraycopy(src, 0, dest, 0, length)", "void")
            ),
            "std" to listOf(
                s("cout", "cout << \"\" << std::endl", "c++ stream"),
                s("string", "string", "std::string", Suggestion.Kind.CLASS),
                s("vector", "vector<>", "std::vector", Suggestion.Kind.CLASS)
            )
        )
    }
}
