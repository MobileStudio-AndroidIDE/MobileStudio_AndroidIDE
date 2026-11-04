package com.aide.mobilestudio.highlight

import com.aide.mobilestudio.StudioUi

class JavaHighlighter : LanguageHighlighter {

    private val keywords = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while",
        "var", "yield", "record", "sealed", "permits", "non-sealed",
        "module", "opens", "provides", "requires", "to", "transitive", "uses", "with"
    )

    private val types = setOf(
        "String", "Integer", "Long", "Double", "Float", "Boolean", "Byte", "Short", "Character",
        "Object", "Class", "System", "Exception", "Throwable", "Error",
        "List", "ArrayList", "LinkedList", "Map", "HashMap", "TreeMap", "LinkedHashMap",
        "Set", "HashSet", "TreeSet", "LinkedHashSet",
        "Queue", "Deque", "ArrayDeque", "PriorityQueue",
        "Optional", "Stream", "Collector",
        "File", "Path", "Paths", "Files", "InputStream", "OutputStream",
        "Reader", "Writer", "BufferedReader", "BufferedWriter",
        "Thread", "Runnable", "Callable", "Future", "ExecutorService",
        "CompletableFuture", "Supplier", "Consumer", "Function", "Predicate",
        "Collection", "Collections", "Arrays", "Math", "Objects"
    )

    private val booleans = setOf("true", "false", "null")

    override fun highlight(source: String): List<HighlightSpan> {
        val spans = mutableListOf<HighlightSpan>()
        val commentBlockPattern = Regex("/\\*[\\s\\S]*?\\*/")
        val commentLinePattern = Regex("//[^\\n]*")
        val stringPattern = Regex("\"(?:[^\"\\\\]|\\\\.)*\"")
        val charPattern = Regex("'(?:[^'\\\\]|\\\\.)'")
        val annotationPattern = Regex("@\\w+(?:\\([^)]*\\))?")
        val classPattern = Regex("\\b(class|interface|enum|record|sealed)\\s+(\\w+)")
        val functionPattern = Regex("\\b([A-Za-z_]\\w*)\\s*\\(")
        val numberPattern = Regex("\\b(?:0[xX][0-9A-Fa-f]+|0[bB][01]+|\\d+\\.\\d+([eE][+-]?\\d+)?|\\d+([eE][+-]?\\d+)?)[lLfFdD]?\\b")
        val genericPattern = Regex("<\\w+(?:\\s*,\\s*\\w+)*>")
        val importPattern = Regex("^\\s*import\\s+(?:static\\s+)?([\\w.*]+);", RegexOption.MULTILINE)

        val commentRanges = mutableListOf<IntRange>()
        commentBlockPattern.findAll(source).forEach {
            commentRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }
        commentLinePattern.findAll(source).forEach {
            commentRanges += it.range
            spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synComment)
        }

        fun inComment(range: IntRange): Boolean =
            commentRanges.any { it.first <= range.first && range.last <= it.last }

        val stringRanges = mutableListOf<IntRange>()
        stringPattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }
        charPattern.findAll(source).forEach {
            if (!inComment(it.range)) {
                stringRanges += it.range
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synStr)
            }
        }

        fun inString(range: IntRange): Boolean =
            stringRanges.any { it.first <= range.first && range.last <= it.last }

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
                if (nameGroup.value !in keywords && nameGroup.value !in types) {
                    spans += HighlightSpan(nameGroup.range.first, nameGroup.range.last + 1, StudioUi.palette.synFunc)
                }
            }
        }

        genericPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synGeneric)
            }
        }

        numberPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synNum)
            }
        }

        val typePattern = Regex("\\b(${types.joinToString("|")})\\b")
        typePattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                spans += HighlightSpan(it.range.first, it.range.last + 1, StudioUi.palette.synType)
            }
        }

        val keywordPattern = Regex("\\b(${keywords.joinToString("|")})\\b")
        keywordPattern.findAll(source).forEach {
            if (!inComment(it.range) && !inString(it.range)) {
                val color = when (it.value) {
                    in booleans -> StudioUi.palette.synBoolean
                    "const" -> StudioUi.palette.synConst
                    else -> StudioUi.palette.synKw
                }
                spans += HighlightSpan(it.range.first, it.range.last + 1, color)
            }
        }

        return spans.sortedBy { it.start }
    }
}
