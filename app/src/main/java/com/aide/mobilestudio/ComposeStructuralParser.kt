package com.aide.mobilestudio

/**
 * Structural parser for `@Composable` function bodies.
 *
 * This is NOT a Kotlin compiler. It parses the small, common subset of Compose
 * UI code (Column/Row/Box/Text/Button/Image/Spacer/TextField/Checkbox/Switch/
 * Slider/Progress/Card/Scaffold/LazyColumn/LazyRow + Modifier chains) well
 * enough to draw an Android-view approximation of the hierarchy.
 *
 * Design goals, in order:
 *  1. Never throw — malformed input degrades to [Result.Failed].
 *  2. Never hang — bounded depth / node count, string-aware scanning.
 *  3. Preserve line numbers for error reporting.
 *
 * Pure Kotlin so it can be unit tested on the JVM.
 */
object ComposeStructuralParser {

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    enum class Kind {
        COLUMN, ROW, BOX, TEXT, BUTTON, IMAGE, ICON, SPACER,
        TEXT_FIELD, CHECKBOX, SWITCH, SLIDER,
        PROGRESS_LINEAR, PROGRESS_CIRCULAR,
        CARD, SCAFFOLD, TOP_BAR, SURFACE, LAZY_COLUMN, LAZY_ROW,
        PLACEHOLDER
    }

    data class ModifierEntry(val name: String, val args: List<String>) {
        /** First numeric arg, understanding both `16.dp` and `0.5f`. */
        val firstNumber: Float?
            get() = args.firstNotNullOfOrNull { numberValue(it) }
    }

    data class Node(
        val kind: Kind,
        val text: String? = null,
        val modifiers: List<ModifierEntry> = emptyList(),
        val children: List<Node> = emptyList(),
        val line: Int = 0,
        /** For PLACEHOLDER nodes: the unknown call name. */
        val label: String? = null,
        /** Extra recognized args (fontSize etc. as raw strings). */
        val extras: Map<String, String> = emptyMap()
    )

    data class ParseError(val message: String, val line: Int = 0)

    sealed class Result {
        data class Ok(val root: Node, val truncated: Boolean = false) : Result()
        data class Failed(val error: ParseError) : Result()
    }

    data class ComposableFun(val name: String, val line: Int, val isPreview: Boolean)

    // ------------------------------------------------------------------
    // Limits (fail-soft guards)
    // ------------------------------------------------------------------

    private const val MAX_DEPTH = 48
    private const val MAX_NODES = 600

    // ------------------------------------------------------------------
    // Function discovery
    // ------------------------------------------------------------------

    private val funRegex = Regex("""(^|\n)(\s*)((?:@\w+\s*)*)((?:public|private|internal|protected)?\s*(?:suspend\s*)?fun\s+)(\w+)\s*\(""")

    /** Finds all `fun`s that look like composables (any `fun` with a body; preview flag from annotations). */
    fun findComposables(source: String): List<ComposableFun> {
        if (source.isBlank()) return emptyList()
        return try {
            val cleaned = stripComments(source)
            val out = mutableListOf<ComposableFun>()
            for (m in funRegex.findAll(cleaned)) {
                val name = m.groupValues[5]
                val line = cleaned.substring(0, m.range.first).count { it == '\n' } + 1
                val annotations = m.groupValues[3]
                // Annotations may sit on the preceding lines too.
                val windowStart = (m.range.first - 240).coerceAtLeast(0)
                val window = cleaned.substring(windowStart, m.range.first)
                val isPreview = annotations.contains("@Preview") || window.contains("@Preview")
                out.add(ComposableFun(name, line, isPreview))
                if (out.size >= 100) break
            }
            out.distinctBy { it.name }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Extracts the `{ … }` body of `fun <name>(…)`. Handles expression bodies too. */
    fun functionBody(source: String, name: String): String? {
        return try {
            val cleaned = stripComments(source)
            val matches = funRegex.findAll(cleaned).filter { it.groupValues[5] == name }.toList()
            val m = matches.firstOrNull() ?: return null
            val openParen = cleaned.indexOf('(', m.range.last)
            if (openParen < 0) return null
            val closeParen = matchBracket(cleaned, openParen, '(', ')') ?: return null
            var i = closeParen + 1
            // Skip return type / where clauses until '{' or '='
            while (i < cleaned.length) {
                val c = cleaned[i]
                if (c == '{') break
                if (c == '=') {
                    // Expression body: fun X() = Text("hi")
                    val exprStart = i + 1
                    val exprEnd = expressionEnd(cleaned, exprStart)
                    return cleaned.substring(exprStart, exprEnd).trim().ifEmpty { null }
                }
                i++
            }
            if (i >= cleaned.length) return null
            val closeBrace = matchBracket(cleaned, i, '{', '}') ?: return null
            cleaned.substring(i + 1, closeBrace)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Body parsing
    // ------------------------------------------------------------------

    fun parseFunction(source: String, name: String): Result {
        val body = functionBody(source, name)
            ?: return Result.Failed(ParseError("Function '$name' not found or has no body"))
        return parseBody(body)
    }

    fun parseBody(body: String): Result {
        return try {
            val scanner = BodyScanner(body)
            val children = scanner.parseStatements(depth = 0)
            when {
                scanner.failed -> Result.Failed(scanner.error ?: ParseError("Unbalanced braces or parentheses"))
                children.isEmpty() -> Result.Failed(ParseError("No Compose calls found in body"))
                else -> Result.Ok(
                    root = Node(
                        kind = Kind.BOX,
                        children = children
                    ),
                    truncated = scanner.truncated
                )
            }
        } catch (e: Exception) {
            Result.Failed(ParseError(e.message ?: e.javaClass.simpleName))
        }
    }

    private class BodyScanner(val src: String) {
        var pos = 0
        var failed = false
        var truncated = false
        var error: ParseError? = null
        var nodeCount = 0

        fun lineAt(index: Int): Int = src.substring(0, index.coerceAtMost(src.length)).count { it == '\n' } + 1

        fun fail(message: String, index: Int = pos): Nothing {
            failed = true
            error = ParseError(message, lineAt(index))
            throw ParseAbort()
        }

        fun parseStatements(depth: Int): List<Node> {
            val out = mutableListOf<Node>()
            while (pos < src.length) {
                if (failed) return out
                skipWhitespace()
                if (pos >= src.length) break
                if (depth > MAX_DEPTH) { truncated = true; break }
                val c = src[pos]
                when {
                    c == '}' -> return out  // caller consumes the brace
                    c == '"' -> {
                        // Skip string literals so keywords inside them are ignored.
                        val close = matchBracket(src, pos, '"', '"')
                        pos = if (close != null) close + 1 else pos + 1
                    }
                    c == ';' || c == ',' -> { pos++; continue }
                    c.isLetter() -> when {
                        keywordAt("if") -> parseIfInto(out, depth)
                        keywordAt("else") -> { pos += 4; parseBlockInto(out, depth) }
                        keywordAt("when") || keywordAt("while") ||
                            keywordAt("for") || keywordAt("try") || keywordAt("do") -> {
                            val kwLen = when {
                                keywordAt("when") -> 4
                                keywordAt("while") -> 5
                                keywordAt("for") -> 3
                                keywordAt("try") -> 3
                                else -> 2
                            }
                            pos += kwLen
                            skipParens()
                            parseBlockInto(out, depth)
                        }
                        keywordAt("val") || keywordAt("var") ||
                            keywordAt("return") || keywordAt("break") ||
                            keywordAt("continue") -> skipToStatementEnd()
                        else -> {
                            val node = parseCallOrNull(depth) ?: continue
                            out += node
                        }
                    }
                    else -> pos++
                }
                if (nodeCount > MAX_NODES) { truncated = true; break }
            }
            return out
        }

        /** True when a standalone keyword starts at [pos] (word boundary respected). */
        private fun keywordAt(kw: String): Boolean {
            if (!src.startsWith(kw, pos)) return false
            val after = src.getOrNull(pos + kw.length) ?: return true
            return !after.isLetterOrDigit()
        }

        private fun skipParens() {
            skipWhitespace()
            if (pos < src.length && src[pos] == '(') {
                val close = matchBracket(src, pos, '(', ')')
                if (close == null) fail("Unbalanced '('", pos)
                pos = close + 1
            }
        }

        /**
         * Control flow: the preview approximation includes the first block's
         * contents in the parent (else branches are skipped for brevity of the
         * approximation, but direct if blocks render).
         */
        private fun parseIfInto(out: MutableList<Node>, depth: Int) {
            pos += 2
            skipParens()
            parseBlockInto(out, depth)
            // else / else if chains: include the else block too.
            val save = pos
            skipWhitespace()
            if (keywordAt("else")) {
                pos += 4
                skipWhitespace()
                if (keywordAt("if")) {
                    parseIfInto(out, depth)
                } else {
                    parseBlockInto(out, depth)
                }
            } else {
                pos = save
            }
        }

        private fun parseBlockInto(out: MutableList<Node>, depth: Int) {
            skipWhitespace()
            if (pos >= src.length) return
            if (src[pos] == '{') {
                val close = matchBracket(src, pos, '{', '}')
                if (close == null) fail("Unbalanced '{'", pos)
                nodeCount++
                if (depth + 1 > MAX_DEPTH) {
                    truncated = true
                    pos = close + 1
                    return
                }
                out += parseBlockInner(pos + 1, close, depth + 1)
                pos = close + 1
            } else {
                parseSingleStatementInto(out, depth)
            }
        }

        fun parseBlockInner(start: Int, end: Int, depth: Int): List<Node> {
            val outerPos = pos
            val sub = BodyScanner(src.substring(start, end))
            return try {
                val nodes = sub.parseStatements(depth + 1)
                truncated = truncated or sub.truncated
                nodeCount += sub.nodeCount
                if (sub.failed && !failed) {
                    failed = true
                    error = sub.error
                }
                nodes
            } catch (_: ParseAbort) {
                failed = true
                if (error == null) error = sub.error ?: ParseError("Unbalanced brackets", lineAt(outerPos))
                emptyList()
            } finally {
                pos = outerPos
            }
        }

        private fun parseSingleStatementInto(out: MutableList<Node>, depth: Int) {
            parseBlockInto(out, depth)
        }

        /** Parses `Name(args) { … }` starting at [pos]; null = skipped (not a UI call). */
        fun parseCallOrNull(depth: Int): Node? {
            val start = pos
            val name = readDottedName() ?: run {
                pos = start + 1
                return null
            }
            val simpleName = name.substringAfterLast('.')
            skipWhitespace()

            var args: List<String> = emptyList()
            var hasArgs = false
            if (pos < src.length && src[pos] == '(') {
                val close = matchBracket(src, pos, '(', ')')
                if (close == null) fail("Unbalanced '(' after $simpleName", pos)
                args = splitArgs(src.substring(pos + 1, close))
                hasArgs = true
                pos = close + 1
            }
            skipWhitespace()

            var childNodes: List<Node> = emptyList()
            if (pos < src.length && src[pos] == '{') {
                val close = matchBracket(src, pos, '{', '}')
                if (close == null) fail("Unbalanced '{' after $simpleName", pos)
                nodeCount++
                if (depth + 1 > MAX_DEPTH) {
                    truncated = true
                    childNodes = emptyList()
                    pos = close + 1
                } else {
                    childNodes = parseBlockInner(pos + 1, close, depth + 1)
                    pos = close + 1
                }
            }

            if (SKIP_CALLS.contains(simpleName) || SKIP_CALLS.contains(name)) return null

            val kind = kindOf(simpleName)
            val (text, extras) = extractTextAndExtras(simpleName, args)
            val modifiers = extractModifiers(args)
            nodeCount++

            return when (kind) {
                null -> {
                    if (!hasArgs && childNodes.isEmpty() && !isLikelyUiCall(simpleName)) {
                        // Unknown function call with no braces: treat as placeholder
                        // only if PascalCase (likely a custom composable).
                        if (simpleName[0].isUpperCase()) {
                            Node(Kind.PLACEHOLDER, label = simpleName, line = lineAt(start))
                        } else null
                    } else if (simpleName[0].isUpperCase() || kind != null) {
                        Node(Kind.PLACEHOLDER, label = simpleName, children = childNodes, line = lineAt(start))
                    } else {
                        // lowercase unknown call with children (e.g. helper): inline children
                        if (childNodes.size == 1) childNodes[0] else inlineNode(childNodes, lineAt(start))
                    }
                }
                Kind.PLACEHOLDER -> Node(Kind.PLACEHOLDER, label = simpleName, children = childNodes, line = lineAt(start))
                else -> Node(
                    kind = kind,
                    text = text,
                    modifiers = modifiers,
                    children = childNodes,
                    line = lineAt(start),
                    extras = extras
                )
            }
        }

        private fun inlineNode(children: List<Node>, line: Int): Node =
            Node(Kind.BOX, children = children, line = line)

        private fun isLikelyUiCall(name: String): Boolean = name[0].isUpperCase()

        private fun readDottedName(): String? {
            if (pos >= src.length || !src[pos].isLetter()) return null
            val start = pos
            while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos] == '.')) pos++
            if (pos == start) return null
            return src.substring(start, pos)
        }

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        /** Skips a `val x = …` / arbitrary statement up to the end of line at bracket depth 0. */
        private fun skipToStatementEnd() {
            var depth = 0
            while (pos < src.length) {
                val c = src[pos]
                when (c) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> {
                        if (depth == 0) return  // caller owns this brace
                        depth--
                    }
                    '\n' -> if (depth == 0 && !src.getOrNull(pos - 1).let { it == '\\' }) {
                        // continue only if next line is a continuation — keep simple: stop
                        pos++
                        return
                    }
                }
                pos++
            }
        }

        private class ParseAbort : Exception()
    }

    // ------------------------------------------------------------------
    // Call classification
    // ------------------------------------------------------------------

    private val SKIP_CALLS = setOf(
        "remember", "mutableStateOf", "rememberSaveable", "derivedStateOf",
        "LaunchedEffect", "SideEffect", "DisposableEffect", "rememberCoroutineScope",
        "collectAsState", "collectAsStateWithLifecycle", "observeAsState",
        "getValue", "setValue", "animateFloatAsState", "animateDpAsState",
        "rememberScrollState", "rememberLazyListState", "verticalScroll",
        "horizontalScroll", "LocalContext", "LocalConfiguration", "current",
        "repeat", "forEach", "map", "also", "apply", "let", "run", "with",
        "painterResource", "stringResource", "dimensionResource", "colorResource"
    )

    private fun kindOf(simpleName: String): Kind? = when (simpleName) {
        "Column" -> Kind.COLUMN
        "Row" -> Kind.ROW
        "Box" -> Kind.BOX
        "Text" -> Kind.TEXT
        "Button", "OutlinedButton", "TextButton", "FilledTonalButton", "ElevatedButton", "MaterialButton" -> Kind.BUTTON
        "Image", "AsyncImage", "coilImage" -> Kind.IMAGE
        "Icon", "IconButton" -> Kind.ICON
        "Spacer" -> Kind.SPACER
        "TextField", "OutlinedTextField", "BasicTextField" -> Kind.TEXT_FIELD
        "Checkbox", "CheckBox" -> Kind.CHECKBOX
        "Switch", "SwitchCompat" -> Kind.SWITCH
        "Slider" -> Kind.SLIDER
        "LinearProgressIndicator" -> Kind.PROGRESS_LINEAR
        "CircularProgressIndicator" -> Kind.PROGRESS_CIRCULAR
        "Card", "ElevatedCard", "OutlinedCard" -> Kind.CARD
        "Scaffold" -> Kind.SCAFFOLD
        "TopAppBar", "SmallTopAppBar", "CenterAlignedTopAppBar" -> Kind.TOP_BAR
        "Surface" -> Kind.SURFACE
        "LazyColumn" -> Kind.LAZY_COLUMN
        "LazyRow" -> Kind.LAZY_ROW
        else -> null
    }

    // ------------------------------------------------------------------
    // Argument extraction
    // ------------------------------------------------------------------

    /** Splits top-level commas (paren/brace/angle aware, string aware). */
    internal fun splitArgs(raw: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        var inString = false
        while (i < raw.length) {
            val c = raw[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else when {
                c == '"' -> inString = true
                c == '(' || c == '[' || c == '<' || c == '{' -> depth++
                c == ')' || c == ']' || c == '>' || c == '}' -> depth--
                c == ',' && depth == 0 -> {
                    out.add(raw.substring(start, i))
                    start = i + 1
                }
            }
            i++
        }
        if (start < raw.length) out.add(raw.substring(start))
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun extractTextAndExtras(callName: String, args: List<String>): Pair<String?, Map<String, String>> {
        var text: String? = null
        val extras = LinkedHashMap<String, String>()
        for (arg in args) {
            val eq = arg.indexOf('=')
            val isNamed = eq in 1 until arg.length - 1 && !isOperator(arg, eq)
            if (isNamed) {
                val key = arg.substring(0, eq).trim()
                val value = arg.substring(eq + 1).trim()
                when (key) {
                    "text" -> text = stringLiteral(value) ?: text
                    "fontSize" -> extras["fontSize"] = value
                    "fontWeight" -> extras["fontWeight"] = value
                    "color", "contentColor" -> extras["color"] = value
                    "label", "placeholder" -> extras.putIfAbsent("hint", stringLiteral(value) ?: "")
                }
            } else {
                // Positional: Text("Hello") / Text(stringArg)
                if (text == null && callName == "Text") {
                    stringLiteral(arg)?.let { text = it }
                }
            }
        }
        return text to extras
    }

    private fun isOperator(arg: String, eq: Int): Boolean {
        if (eq == 0) return true
        val prev = arg[eq - 1]
        return prev == '=' || prev == '!' || prev == '<' || prev == '>'
    }

    /** Extracts the content of a "…" literal (no interpolation evaluation). */
    internal fun stringLiteral(value: String): String? {
        val s = value.trim()
        if (s.length < 2) return null
        if (s.first() != '"') return null
        return try {
            val close = matchBracket(s, 0, '"', '"') ?: return null
            s.substring(1, close)
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Modifiers
    // ------------------------------------------------------------------

    internal fun extractModifiers(args: List<String>): List<ModifierEntry> {
        val raw = args.firstOrNull {
            it == "Modifier" || it.startsWith("Modifier.") ||
                Regex("""^modifier\s*=""").containsMatchIn(it)
        } ?: return emptyList()
        val expr = raw.substringAfter('=', raw).trim()
        if (expr != "Modifier" && !expr.startsWith("Modifier.")) return emptyList()
        return splitModifierChain(expr)
            .filter { it != "Modifier" }
            .mapNotNull { entry ->
                val open = entry.indexOf('(')
                if (open < 0) ModifierEntry(entry.trim(), emptyList())
                else ModifierEntry(
                    entry.substring(0, open).trim(),
                    splitArgs(entry.substring(open + 1, entry.lastIndexOf(')').coerceAtLeast(open + 1)))
                )
            }
    }

    /** "Modifier.padding(8.dp).fillMaxWidth()" → ["Modifier", "padding(8.dp)", "fillMaxWidth()"] */
    internal fun splitModifierChain(expr: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        var inString = false
        while (i < expr.length) {
            val c = expr[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else when {
                c == '"' -> inString = true
                c == '(' -> depth++
                c == ')' -> depth--
                c == '.' && depth == 0 -> {
                    out.add(expr.substring(start, i))
                    start = i + 1
                }
            }
            i++
        }
        if (start < expr.length) out.add(expr.substring(start))
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** "16.dp" → 16f, "0.5f" → 0.5f, "1" → 1f; else null. */
    internal fun numberValue(raw: String): Float? {
        val s = raw.trim()
        val m = Regex("""^(-?\d+(?:\.\d+)?)\s*(?:\.?(?:dp|sp)|f|F)?$""").matchEntire(s) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    // ------------------------------------------------------------------
    // Scanner helpers (string aware)
    // ------------------------------------------------------------------

    /** Returns the index of the bracket matching the one at [open], or null. */
    internal fun matchBracket(src: String, open: Int, openCh: Char, closeCh: Char): Int? {
        if (openCh == '"') {
            // Matching a string literal: find the unescaped closing quote.
            var i = open + 1
            while (i < src.length) {
                when (src[i]) {
                    '\\' -> i++
                    '"' -> return i
                }
                i++
            }
            return null
        }
        var depth = 0
        var i = open
        var inString = false
        while (i < src.length) {
            val c = src[i]
            if (inString) {
                when (c) {
                    '\\' -> i++
                    '"' -> inString = false
                }
            } else when {
                c == '"' -> inString = true
                c == openCh -> depth++
                c == closeCh -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    /** End index (exclusive) of an expression body statement. */
    private fun expressionEnd(src: String, start: Int): Int {
        var i = start
        var depth = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '"') {
                val close = matchBracket(src, i, '"', '"') ?: return src.length
                i = close + 1
                continue
            }
            if (c == '(' || c == '{' || c == '[') depth++
            if (c == ')' || c == '}' || c == ']') {
                if (depth == 0) return i
                depth--
            }
            if (c == '\n' && depth == 0) {
                // Statement ends at newline unless the next line starts with '.'
                var j = i + 1
                while (j < src.length && src[j].isWhitespace()) j++
                if (j < src.length && src[j] == '.') { i = j; continue }
                return i
            }
            i++
        }
        return src.length
    }

    /** Removes // and /* */ comments, replacing them with spaces (line count preserved). */
    internal fun stripComments(src: String): String {
        val sb = StringBuilder(src.length)
        var i = 0
        var blockDepth = 0
        while (i < src.length) {
            val c = src[i]
            if (blockDepth > 0) {
                if (c == '*' && i + 1 < src.length && src[i + 1] == '/') { blockDepth--; sb.append("  "); i += 2 }
                else { sb.append(if (c == '\n') '\n' else ' '); i++ }
                continue
            }
            if (c == '"') {
                val close = matchBracket(src, i, '"', '"')
                if (close != null) {
                    sb.append(src, i, close + 1)
                    i = close + 1
                    continue
                }
            }
            if (c == '\'' && i + 2 < src.length && src[i + 2] == '\'') {
                sb.append(src, i, i + 3); i += 3; continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '/') {
                while (i < src.length && src[i] != '\n') { sb.append(' '); i++ }
                continue
            }
            if (c == '/' && i + 1 < src.length && src[i + 1] == '*') {
                blockDepth = 1; sb.append("  "); i += 2; continue
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}
