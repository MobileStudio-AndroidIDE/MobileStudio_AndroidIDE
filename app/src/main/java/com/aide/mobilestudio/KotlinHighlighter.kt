package com.aide.mobilestudio

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.EditText
import com.aide.mobilestudio.highlight.Language
import com.aide.mobilestudio.highlight.LanguageHighlighter
import kotlinx.coroutines.*

object KotlinHighlighter {

    private fun colorKw() = StudioUi.palette.synKw
    private fun colorStr() = StudioUi.palette.synStr
    private fun colorComment() = StudioUi.palette.synComment
    private fun colorDoc() = StudioUi.palette.synDoc
    private fun colorNum() = StudioUi.palette.synNum
    private fun colorAnnotation() = StudioUi.palette.synAnnotation
    private fun colorClass() = StudioUi.palette.synType
    private fun colorFunction() = StudioUi.palette.synFunc
    private fun colorIdent() = StudioUi.palette.synIdent
    private fun colorVariable() = StudioUi.palette.synField
    private fun colorOperator() = StudioUi.palette.synOperator
    private fun colorBoolean() = StudioUi.palette.synBoolean
    private fun colorConst() = StudioUi.palette.synConst
    private fun colorGeneric() = StudioUi.palette.synGeneric
    private fun colorLabel() = StudioUi.palette.synLabel
    private fun colorImport() = StudioUi.palette.synImport
    private fun colorParam() = StudioUi.palette.synParam

    private val keywords by lazy { setOf(
        "abstract", "actual", "annotation", "as", "break", "by", "catch",
        "class", "companion", "constructor", "continue", "crossinline",
        "data", "delegate", "do", "dynamic", "else", "enum", "expect", "external",
        "field", "file", "final", "finally", "for", "fun", "get",
        "if", "import", "in", "infix", "init", "inline", "inner", "interface",
        "internal", "is", "it", "lateinit", "noinline", "object", "open",
        "operator", "out", "override", "package", "param", "private", "property",
        "protected", "public", "reified", "return", "sealed", "set", "super",
        "suspend", "tailrec", "this", "throw", "try", "typealias",
        "typeof", "val", "var", "vararg", "when", "where", "while", "composable",
        "remember", "mutableStateOf", "LaunchedEffect", "SideEffect", "DisposableEffect",
        "produceState", "derivedStateOf", "snapshotFlow"
    ) }
    private val booleans by lazy { setOf("true", "false", "null") }
    private val constKeywords by lazy { setOf("const") }

    private val keywordPattern by lazy { Regex("\\b(${keywords.joinToString("|")})\\b") }
    private val docCommentPattern by lazy { Regex("/\\*\\*[\\s\\S]*?\\*/") }
    private val commentBlockPattern by lazy { Regex("(?<!/\\*)/\\*(?!\\*)[\\s\\S]*?\\*/") }
    private val commentLinePattern by lazy { Regex("//[^\\n]*") }
    private val stringDoublePattern by lazy { Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\]|\\\\.)*\"") }
    private val stringSinglePattern by lazy { Regex("'(?:[^'\\\\]|\\\\.)*'") }
    private val numberPattern by lazy { Regex("\\b(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|0[oO][0-7]+|\\d+\\.\\d+([eE][+-]?\\d+)?|\\.\\d+([eE][+-]?\\d+)?|\\d+([eE][+-]?\\d+)?)[uUlLfF]*\\b") }
    private val interpolationPattern by lazy { Regex("\\$\\{[^{}]*\\}|\\$[A-Za-z_]\\w*") }
    private val annotationPattern by lazy { Regex("@\\w+") }
    private val classPattern by lazy { Regex("\\b(class|interface|object|enum|struct|record|typealias)\\s+(\\w+)") }
    private val functionPattern by lazy { Regex("\\b(?:fun\\s+|[A-Za-z_][\\w<>*\\s]*\\s+)([A-Za-z_]\\w*)\\s*[<(]") }
    private val variablePattern by lazy { Regex("\\b(val|var)\\s+(\\w+)\\b") }
    private val constValPattern by lazy { Regex("\\bconst\\s+val\\s+(\\w+)\\b") }
    private val preprocessorPattern by lazy { Regex("^\\s*#\\s*\\w+.*", RegexOption.MULTILINE) }
    private val genericPattern by lazy { Regex("<[A-Z][A-Za-z0-9_, ]*>") }
    private val labelPattern by lazy { Regex("\\b(\\w+):(?![=:])") }
    private val importPattern by lazy { Regex("^\\s*import\\s+([\\w.]+)", RegexOption.MULTILINE) }
    private val fieldAccessPattern by lazy { Regex("\\.([a-z]\\w*)\\s*[(\\[\\]})]") }
    private val operatorPattern by lazy { Regex("[!=<>&|?:]+|\\.+|\\+\\+|--|[+\\-*/%=]") }

    private fun highlightSpansFromDelegate(source: String, lang: Language): List<HighlightSpan> {
        val highlighter = LanguageHighlighter.forLanguage(lang)
        return highlighter.highlight(source).map {
            HighlightSpan(it.start, it.end, it.color, it.bold)
        }
    }

    fun highlight(text: Editable, filePath: String? = null) {
        if (text.length > 250_000) return
        val source = text.toString()
        highlightJob?.cancel()
        highlightJob = scope.launch {
            val lang = Language.detect(filePath ?: "")
            val spans = withContext(Dispatchers.Default) {
                if (lang == Language.KOTLIN || lang == Language.GRADLE || lang == Language.UNKNOWN) {
                    computeSpans(source)
                } else {
                    highlightSpansFromDelegate(source, lang)
                }
            }
            if (text.toString() == source) applySpans(text, spans)
        }
    }

    private data class HighlightSpan(val start: Int, val end: Int, val color: Int, val bold: Boolean = false)

    private fun computeSpans(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val excludedRanges = mutableListOf<IntRange>()

        docCommentPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, colorDoc())
        }
        commentBlockPattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, colorComment())
        }
        commentLinePattern.findAll(source).forEach {
            excludedRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, colorComment())
        }

        fun inComment(range: IntRange): Boolean =
            excludedRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        stringDoublePattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, colorStr())
            }
        }
        stringSinglePattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, colorStr())
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        importPattern.findAll(source).forEach { match ->
            val path = match.groups[1] ?: return@forEach
            if (!inComment(path.range) && !inString(path.range)) {
                spans += HighlightSpan(path.range.first, path.range.last + 1, colorImport())
            }
        }

        annotationPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, colorAnnotation())
            }
        }
        preprocessorPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, colorAnnotation())
            }
        }

        constValPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, colorConst(), bold = true)
            }
        }

        classPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[2] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, colorClass(), bold = true)
            }
        }

        functionPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, colorFunction())
            }
        }

        variablePattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[2] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, colorVariable())
            }
        }

        fieldAccessPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, colorParam())
            }
        }

        genericPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, colorGeneric())
            }
        }

        labelPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                val name = nameGroup.value
                if (name !in keywords && name.length > 1) {
                    spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, colorLabel())
                }
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, colorNum())
            }
        }

        interpolationPattern.findAll(source).forEach {
            if (!inComment(it.range) && inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, colorFunction())
            }
        }

        keywordPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                val color = when {
                    it.value in booleans -> colorBoolean()
                    it.value in constKeywords -> colorConst()
                    it.value in setOf("val", "var") -> colorVariable()
                    else -> colorKw()
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }
        return spans
    }

    private fun applySpans(text: Editable, spans: List<HighlightSpan>) {
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach { text.removeSpan(it) }
        text.getSpans(0, text.length, StyleSpan::class.java).forEach {
            if (it.style == Typeface.BOLD) text.removeSpan(it)
        }
        spans.forEach {
            if (it.start < text.length && it.end <= text.length) {
                text.setSpan(ForegroundColorSpan(it.color), it.start, it.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (it.bold) {
                    text.setSpan(StyleSpan(Typeface.BOLD), it.start, it.end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    data class SyntaxError(val line: Int, val col: Int, val message: String)

    fun checkSyntax(source: String, filePath: String? = null): List<SyntaxError> {
        val lang = Language.detect(filePath ?: "")
        if (lang != Language.KOTLIN && lang != Language.GRADLE && lang != Language.UNKNOWN) {
            return checkBrackets(source)
        }
        val errors = mutableListOf<SyntaxError>()
        val lines = source.lines()
        lines.forEachIndexed { index, line ->
            val stripped = stripStringsAndComments(line)
            if (stripped.trimEnd().endsWith(";") && !stripped.contains("for")) {
                errors += SyntaxError(index + 1, line.lastIndexOf(';'), "Unnecessary semicolon")
            }
            if (line.contains("TODO(") || line.contains("// TODO")) {
                errors += SyntaxError(index + 1, line.indexOf("TODO"), "TODO remains in code")
            }
        }
        errors += checkBrackets(source)
        return errors
    }

    private fun checkBrackets(source: String): List<SyntaxError> {
        val errors = mutableListOf<SyntaxError>()
        val stripped = stripStringsAndComments(source)
        val stack = mutableListOf<Pair<Char, Int>>()
        val pairs = mapOf(')' to '(', '}' to '{', ']' to '[')

        stripped.forEachIndexed { index, char ->
            when (char) {
                '(', '{', '[' -> stack.add(char to index)
                ')', '}', ']' -> {
                    if (stack.isEmpty() || stack.last().first != pairs[char]) {
                        val lineNumber = source.substring(0, index.coerceAtMost(source.length)).count { it == '\n' } + 1
                        errors += SyntaxError(lineNumber, 0, "Mismatched '$char'")
                    } else {
                        stack.removeAt(stack.size - 1)
                    }
                }
            }
        }

        stack.forEach { (char, position) ->
            val lineNumber = source.substring(0, position.coerceAtMost(source.length)).count { it == '\n' } + 1
            errors += SyntaxError(lineNumber, 0, "Unclosed '$char'")
        }

        return errors
    }

    private fun stripStringsAndComments(source: String): String {
        var result = source
        result = commentBlockPattern.replace(result) { " ".repeat(it.value.length) }
        result = commentLinePattern.replace(result) { " ".repeat(it.value.length) }
        result = stringDoublePattern.replace(result) { "\"\"" }
        result = stringSinglePattern.replace(result) { "''" }
        return result
    }

    private var highlightJob: Job? = null
    private val scope by lazy { CoroutineScope(Dispatchers.Main + SupervisorJob()) }

    fun attachTo(editText: LineNumberEditText, filePath: String? = null, suppressCheck: () -> Boolean = { false }, onErrors: (List<SyntaxError>) -> Unit = {}) {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (s == null || suppressCheck()) return
                highlightJob?.cancel()
                val current = s.toString()
                if (current.length > 250_000) return
                highlightJob = scope.launch {
                    delay(300)
                    val lang = withContext(Dispatchers.Default) { Language.detect(filePath ?: "") }
                    val spans = withContext(Dispatchers.Default) {
                        if (lang == Language.KOTLIN || lang == Language.GRADLE || lang == Language.UNKNOWN) {
                            computeSpans(current)
                        } else {
                            highlightSpansFromDelegate(current, lang)
                        }
                    }
                    val errors = withContext(Dispatchers.Default) { checkSyntax(current, filePath) }
                    val hints = withContext(Dispatchers.Default) { computeInlayHints(current) }
                    val lenses = withContext(Dispatchers.Default) { computeCodeLenses(current) }
                    
                    if (s.toString() == current) {
                        applySpans(s, spans)
                        applyErrorUnderlines(s, errors)
                        editText.setInlayHints(hints)
                        editText.setCodeLenses(lenses)
                        onErrors(errors)
                    }
                }
            }
        })
    }

    private fun computeInlayHints(source: String): List<LineNumberEditText.InlayHint> {
        val hints = mutableListOf<LineNumberEditText.InlayHint>()
        val dpPattern = Regex("""\bdp\(\s*(\d+(?:\.\d+)?)\s*\)""")
        dpPattern.findAll(source).forEach { match ->
            hints.add(LineNumberEditText.InlayHint(match.groups[1]!!.range.first, "value = "))
        }
        val parseColorPattern = Regex("""\bparseColor\(\s*("[^"]*")\s*\)""")
        parseColorPattern.findAll(source).forEach { match ->
            hints.add(LineNumberEditText.InlayHint(match.groups[1]!!.range.first, "colorString = "))
        }
        val setPaddingPattern = Regex("""\bsetPadding\(\s*([^,]+),\s*([^,]+),\s*([^,]+),\s*([^,]+)\s*\)""")
        setPaddingPattern.findAll(source).forEach { match ->
            hints.add(LineNumberEditText.InlayHint(match.groups[1]!!.range.first, "left = "))
            hints.add(LineNumberEditText.InlayHint(match.groups[2]!!.range.first, "top = "))
            hints.add(LineNumberEditText.InlayHint(match.groups[3]!!.range.first, "right = "))
            hints.add(LineNumberEditText.InlayHint(match.groups[4]!!.range.first, "bottom = "))
        }
        val constructorPattern = Regex("""\b(LinearLayout|TextView|Button|ImageView|FrameLayout|RelativeLayout|RecyclerView)\(\s*this\s*\)""")
        constructorPattern.findAll(source).forEach { match ->
            hints.add(LineNumberEditText.InlayHint(match.range.first + match.value.indexOf("this"), "context = "))
        }
        val replacePattern = Regex("""\breplace\(\s*("[^"]*"),\s*("[^"]*")\s*\)""")
        replacePattern.findAll(source).forEach { match ->
            hints.add(LineNumberEditText.InlayHint(match.groups[1]!!.range.first, "target = "))
            hints.add(LineNumberEditText.InlayHint(match.groups[2]!!.range.first, "replacement = "))
        }
        return hints
    }

    private fun computeCodeLenses(source: String): List<LineNumberEditText.CodeLens> {
        val lenses = mutableListOf<LineNumberEditText.CodeLens>()
        val declPattern = Regex("""\b(fun|val|var|class|object|interface)\s+(\w+)\b""")
        declPattern.findAll(source).forEach { match ->
            val name = match.groupValues[2]
            if (name.length < 3) return@forEach
            val usageCount = wordBoundaryCount(source, name) - 1
            if (usageCount > 0) {
                val lineNo = source.substring(0, match.range.first).count { it == '\n' }
                lenses.add(LineNumberEditText.CodeLens(lineNo, "$usageCount usages"))
            }
        }
        return lenses
    }

    private fun wordBoundaryCount(source: String, word: String): Int {
        val pattern = Regex("\\b${Regex.escape(word)}\\b")
        return pattern.findAll(source).count()
    }

    fun analyzeNow(editText: EditText, filePath: String? = null, onErrors: (List<SyntaxError>) -> Unit = {}) {
        val text = editText.text ?: return
        val snapshot = text.toString()
        if (snapshot.length > 250_000) {
            onErrors(emptyList())
            return
        }
        scope.launch {
            val lang = withContext(Dispatchers.Default) { Language.detect(filePath ?: "") }
            val spans = withContext(Dispatchers.Default) {
                if (lang == Language.KOTLIN || lang == Language.GRADLE || lang == Language.UNKNOWN) {
                    computeSpans(snapshot)
                } else {
                    highlightSpansFromDelegate(snapshot, lang)
                }
            }
            val errors = withContext(Dispatchers.Default) { checkSyntax(snapshot, filePath) }
            if (editText.text.toString() == snapshot) {
                applySpans(text, spans)
                applyErrorUnderlines(text, errors)
                onErrors(errors)
            }
        }
    }

    private fun applyErrorUnderlines(text: Editable, errors: List<SyntaxError>) {
        text.getSpans(0, text.length, SquigglyUnderlineSpan::class.java).forEach { text.removeSpan(it) }
        val lines = text.toString().lines()
        errors.forEach { error ->
            if (error.line <= lines.size) {
                val lineStart = lines.take(error.line - 1).sumOf { it.length + 1 }
                val lineEnd = (lineStart + lines[error.line - 1].length).coerceAtMost(text.length)
                if (lineStart < lineEnd) {
                    text.setSpan(SquigglyUnderlineSpan(), lineStart, lineEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }
}
