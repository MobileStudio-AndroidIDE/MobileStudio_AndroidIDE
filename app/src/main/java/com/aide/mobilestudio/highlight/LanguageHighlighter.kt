package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.KotlinHighlighter
import com.aide.mobilestudio.StudioUi

data class HighlightSpan(
    val start: Int,
    val end: Int,
    val color: Int,
    val bold: Boolean = false
)

interface LanguageHighlighter {
    fun highlight(source: String): List<HighlightSpan>

    companion object {
        private val registry = mutableMapOf<Language, LanguageHighlighter>()

        init {
            register(Language.KOTLIN, KotlinHighlighterDelegate())
            register(Language.JAVA, JavaHighlighter())
            register(Language.XML, XmlHighlighter())
            register(Language.JSON, JsonHighlighter())
            register(Language.C, CStyleHighlighter(setOf(
                "auto", "break", "case", "char", "const", "continue", "default", "do",
                "double", "else", "enum", "extern", "float", "for", "goto", "if",
                "inline", "int", "long", "register", "restrict", "return", "short",
                "signed", "sizeof", "static", "struct", "switch", "typedef", "union",
                "unsigned", "void", "volatile", "while",
                "_Bool", "_Complex", "_Imaginary",
                "NULL", "true", "false"
            ), setOf(
                "int", "char", "short", "long", "float", "double", "void", "size_t",
                "ssize_t", "int8_t", "int16_t", "int32_t", "int64_t",
                "uint8_t", "uint16_t", "uint32_t", "uint64_t",
                "FILE", "bool", "pid_t", "off_t", "time_t"
            )))
            register(Language.CPP, CStyleHighlighter(setOf(
                "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand",
                "bitor", "break", "case", "catch", "char", "char8_t", "char16_t",
                "char32_t", "class", "co_await", "co_return", "co_yield", "compl",
                "concept", "const", "consteval", "constexpr", "constinit",
                "const_cast", "continue", "co_await", "decltype", "default", "delete",
                "do", "double", "dynamic_cast", "else", "enum", "explicit", "export",
                "extern", "false", "float", "for", "friend", "goto", "if", "inline",
                "int", "long", "mutable", "namespace", "new", "noexcept", "not",
                "not_eq", "nullptr", "operator", "or", "or_eq", "private", "protected",
                "public", "register", "reinterpret_cast", "requires", "return", "short",
                "signed", "sizeof", "static", "static_assert", "static_cast", "struct",
                "switch", "template", "this", "thread_local", "throw", "true", "try",
                "typedef", "typeid", "typename", "union", "unsigned", "using", "virtual",
                "void", "volatile", "wchar_t", "while", "xor", "xor_eq",
                "override", "final", "import", "module"
            ), setOf(
                "int", "char", "short", "long", "float", "double", "void", "bool",
                "size_t", "ssize_t", "string", "wstring", "string_view",
                "vector", "map", "set", "list", "array", "pair", "tuple",
                "shared_ptr", "unique_ptr", "weak_ptr", "optional", "variant",
                "int8_t", "int16_t", "int32_t", "int64_t",
                "uint8_t", "uint16_t", "uint32_t", "uint64_t",
                "FILE", "pid_t", "off_t", "time_t",
                "ostream", "istream", "iostream", "ifstream", "ofstream"
            )))
            register(Language.GRADLE, KotlinHighlighterDelegate())
            register(Language.MARKDOWN, MarkdownHighlighter())
            register(Language.SHELL, ShellHighlighter())
            register(Language.PYTHON, PythonHighlighter())
            register(Language.RUST, RustHighlighter())
            register(Language.GO, GoHighlighter())
            register(Language.YAML, YamlHighlighter())
            register(Language.PROPERTIES, PropertiesHighlighter())
            register(Language.HTML, WebHighlighter.html())
            register(Language.CSS, WebHighlighter.css())
            register(Language.JAVASCRIPT, WebHighlighter.js())
            register(Language.TYPESCRIPT, WebHighlighter.ts())
            register(Language.CMAKE, CStyleHighlighter(setOf(
                "if", "else", "elseif", "endif", "foreach", "endforeach",
                "while", "endwhile", "function", "endfunction", "macro", "endmacro",
                "return", "break", "continue", "set", "unset", "option", "find_package",
                "target_link_libraries", "target_include_directories", "add_executable",
                "add_library", "include_directories", "project", "cmake_minimum_required"
            ), setOf(
                "STRING", "BOOL", "FILE", "PATH", "LIST", "CACHE"
            )))
            register(Language.SWIFT, CStyleHighlighter(setOf(
                "associatedtype", "class", "deinit", "enum", "extension", "func",
                "import", "init", "inout", "internal", "let", "operator", "private",
                "protocol", "public", "static", "struct", "subscript", "typealias",
                "var", "break", "case", "continue", "default", "defer", "do",
                "else", "fallthrough", "for", "guard", "if", "in", "repeat",
                "return", "switch", "where", "while", "as", "catch", "is", "nil",
                "rethrows", "super", "self", "Self", "throw", "throws", "try",
                "true", "false", "try", "async", "await", "some"
            ), setOf(
                "String", "Int", "Double", "Float", "Bool", "Character",
                "Array", "Dictionary", "Set", "Optional", "Result",
                "Any", "AnyObject", "Void", "Error"
            )))
            register(Language.DART, CStyleHighlighter(setOf(
                "abstract", "as", "assert", "async", "await", "break", "case",
                "catch", "class", "const", "continue", "covariant", "default",
                "deferred", "dynamic", "else", "enum", "export", "extends",
                "extension", "external", "factory", "false", "final", "finally",
                "for", "function", "get", "if", "implements", "import", "in",
                "interface", "is", "late", "let", "library", "mixin", "new",
                "null", "on", "operator", "part", "required", "rethrow", "return",
                "set", "show", "static", "super", "switch", "sync", "this",
                "throw", "true", "try", "typedef", "var", "void", "while", "with",
                "yield"
            ), setOf(
                "String", "int", "double", "float", "bool", "num", "dynamic",
                "List", "Map", "Set", "Future", "Stream", "Iterable",
                "Object", "Function", "Type", "void", "Null", "Never"
            )))
            register(Language.R, CStyleHighlighter(setOf(
                "if", "else", "for", "while", "repeat", "in", "function",
                "return", "next", "break", "TRUE", "FALSE", "NULL", "NA",
                "Inf", "NaN", "library", "require", "source", "print",
                "cat", "message", "warning", "stop"
            ), setOf(
                "numeric", "integer", "double", "character", "logical", "complex",
                "list", "data.frame", "matrix", "vector", "factor", "array",
                "environment", "expression", "call", "name", "function"
            )))
            register(Language.LUA, LuaHighlighter())
            register(Language.GDSCRIPT, GdHighlighter())
            register(Language.SQL, SqlHighlighter())
            register(Language.ASSEMBLY, CStyleHighlighter(setOf(
                ".text", ".data", ".bss", ".global", ".extern", ".section",
                ".align", ".ascii", ".asciz", ".byte", ".hword", ".word", ".long",
                "mov", "ldr", "str", "add", "sub", "and", "or", "eor", "cmp",
                "b", "bl", "bx", "push", "pop", "ret", "nop", "swi", "svc"
            ), setOf(
                "r0", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "r9",
                "r10", "r11", "r12", "sp", "lr", "pc", "cpsr", "spsr"
            )))
            register(Language.DIFF, MarkdownHighlighter())
        }

        fun register(lang: Language, highlighter: LanguageHighlighter) {
            registry[lang] = highlighter
        }

        fun forLanguage(lang: Language): LanguageHighlighter {
            return registry[lang] ?: PlainHighlighter
        }

        fun forFile(filename: String): LanguageHighlighter {
            return forLanguage(Language.detect(filename))
        }
    }
}

private object PlainHighlighter : LanguageHighlighter {
    override fun highlight(source: String): List<HighlightSpan> = emptyList()
}

private class KotlinHighlighterDelegate : LanguageHighlighter {
    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentBlockPattern = Regex("/\\*[\\s\\S]*?\\*/")
        val commentLinePattern = Regex("//[^\\n]*")
        val stringDoublePattern = Regex("\"\"\"[\\s\\S]*?\"\"\"|\"(?:[^\"\\\\]|\\\\.)*\"")
        val stringSinglePattern = Regex("'(?:[^'\\\\]|\\\\.)*'")

        val commentRanges = mutableListOf<IntRange>()
        commentBlockPattern.findAll(source).forEach { commentRanges += it.range }
        commentLinePattern.findAll(source).forEach { commentRanges += it.range }

        fun inComment(range: IntRange): Boolean =
            commentRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        stringDoublePattern.findAll(source).forEach {
            if (!inComment(it.range)) { stringRanges += it.range }
        }
        stringSinglePattern.findAll(source).forEach {
            if (!inComment(it.range)) { stringRanges += it.range }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

        commentBlockPattern.findAll(source).forEach {
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }
        commentLinePattern.findAll(source).forEach {
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }
        stringDoublePattern.findAll(source).forEach {
            if (!inComment(it.range)) spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
        }
        stringSinglePattern.findAll(source).forEach {
            if (!inComment(it.range)) spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
        }

        val keywords = setOf(
            "abstract", "actual", "annotation", "as", "break", "by", "catch",
            "class", "companion", "const", "constructor", "continue", "crossinline",
            "data", "delegate", "do", "dynamic", "else", "enum", "expect", "external",
            "false", "field", "file", "final", "finally", "for", "fun", "get",
            "if", "import", "in", "infix", "init", "inline", "inner", "interface",
            "internal", "is", "it", "lateinit", "noinline", "null", "object", "open",
            "operator", "out", "override", "package", "param", "private", "property",
            "protected", "public", "reified", "return", "sealed", "set", "super",
            "suspend", "tailrec", "this", "throw", "true", "try", "typealias",
            "typeof", "val", "var", "vararg", "when", "where", "while", "composable",
            "remember", "mutableStateOf", "LaunchedEffect", "SideEffect", "DisposableEffect"
        )

        val booleans = setOf("true", "false", "null")
        val keywordPattern = Regex("\\b(${keywords.joinToString("|")})\\b")
        val annotationPattern = Regex("@\\w+")
        val classPattern = Regex("\\b(class|interface|object|enum|record|typealias)\\s+(\\w+)")
        val functionPattern = Regex("\\b(?:fun\\s+|[A-Za-z_][\\w<>*\\s]*\\s+)([A-Za-z_]\\w*)\\s*\\(")
        val variablePattern = Regex("\\b(val|var)\\s+(\\w+)\\b")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|0[oO][0-7]+|\\d+\\.\\d+([eE][+-]?\\d+)?|\\d+([eE][+-]?\\d+)?)[uUlLfF]*\\b")
        val labelPattern = Regex("\\b\\w+:")
        val genericPattern = Regex("<\\w+(?:\\s*,\\s*\\w+)*>")
        val importPattern = Regex("^\\s*import\\s+([\\w.]+)", RegexOption.MULTILINE)

        importPattern.findAll(source).forEach { match ->
            val path = match.groups[1] ?: return@forEach
            if (!inComment(path.range) && !inString(path.range)) {
                spans += HighlightSpan(path.range.first, path.range.last + 1, StudioUi.palette.synImport)
            }
        }

        annotationPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synAnnotation)
            }
        }

        classPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[2] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synType, bold = true)
            }
        }

        functionPattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[1] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
            }
        }

        variablePattern.findAll(source).forEach { match ->
            val nameGroup = match.groups[2] ?: return@forEach
            if (!inComment(nameGroup.range) && !inString(nameGroup.range)) {
                spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synIdent)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        genericPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synGeneric)
            }
        }

        labelPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last, StudioUi.palette.synLabel)
            }
        }

        keywordPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                val color = when {
                    it.value in booleans -> StudioUi.palette.synBoolean
                    it.value in setOf("val", "var") -> StudioUi.palette.synField
                    it.value == "const" -> StudioUi.palette.synConst
                    else -> StudioUi.palette.synKw
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        return spans.sortedBy { it.start }
    }
}
