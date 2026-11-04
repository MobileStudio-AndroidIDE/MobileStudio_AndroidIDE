package com.aide.mobilestudio.web

/**
 * Stateless web completion engine: `query(language, textBeforeCursor) -> List<WebCompletion>`.
 *
 * Pure JVM (no Android imports) so it is trivially unit-testable. The trigger context is
 * derived from the text before the caret only:
 *
 *  - **HTML**: tag names after `<`, attribute names after a space inside the open tag
 *    (filtered by the current tag), attribute values inside `attr="…"` (filtered by
 *    tag+attribute), closing tags after `</` (innermost unclosed element first).
 *    Inside `<script>…</script>` content the query is delegated to the JS engine and
 *    inside `<style>…</style>` to the CSS engine.
 *  - **CSS**: property names inside a block before `:`, value keywords per property after `:`,
 *    units after a numeric token, at-rules after `@`, media features inside `@media (…)`.
 *  - **JavaScript / TypeScript**: keywords, globals, DOM helpers and snippets at statement
 *    level; members after `.` for known receivers (console, document, window, Math, JSON,
 *    Object, Array, Promise, localStorage, classList, …). TS adds type keywords and utility
 *    types. Inside strings or comments the engine returns nothing (never suggests CSS
 *    properties inside a JS string, etc.).
 *
 * `query` never throws — malformed input simply yields an empty result.
 */
object WebCompletionEngine {

    const val LANG_HTML = "html"
    const val LANG_CSS = "css"
    const val LANG_JS = "javascript"
    const val LANG_TS = "typescript"

    /** Maps a file extension to an engine language, or null when not a web language. */
    fun languageForExtension(ext: String): String? = when (ext.lowercase()) {
        "html", "htm" -> LANG_HTML
        "css" -> LANG_CSS
        "js", "jsx", "mjs", "cjs" -> LANG_JS
        "ts", "tsx", "mts", "cts" -> LANG_TS
        else -> null
    }

    /** Spec-level entry point. Never throws; empty list when nothing applies. */
    fun query(language: String, textBeforeCursor: String): List<WebCompletion> =
        queryDetailed(language, textBeforeCursor).items

    /**
     * Same as [query] but also returns the trigger prefix the items replace, so a UI can
     * substitute exactly the token under the caret.
     */
    fun queryDetailed(language: String, textBeforeCursor: String): WebQueryResult = try {
        when (normalize(language)) {
            LANG_HTML -> completeHtml(textBeforeCursor)
            LANG_CSS -> completeCss(textBeforeCursor)
            LANG_JS -> completeJs(textBeforeCursor, ts = false)
            LANG_TS -> completeJs(textBeforeCursor, ts = true)
            else -> WebQueryResult.NONE
        }
    } catch (_: Exception) {
        // Malformed code must never break typing.
        WebQueryResult.NONE
    }

    private fun normalize(language: String): String = when (language.lowercase().trim()) {
        "html", "htm" -> LANG_HTML
        "css" -> LANG_CSS
        "js", "jsx", "mjs", "cjs", "javascript" -> LANG_JS
        "ts", "tsx", "mts", "cts", "typescript" -> LANG_TS
        else -> language.lowercase().trim()
    }

    // ═══════════════════════════════ HTML ═══════════════════════════════

    private enum class TagSub { NAME, ATTR_NAME, ATTR_VALUE }

    private class TagScan(
        val name: StringBuilder,
        val attrToken: StringBuilder,
        val attrName: String?,
        val value: StringBuilder,
        val quoteOpen: Boolean,
        val afterEq: Boolean,
        val nameDone: Boolean,
        val selfClosed: Boolean,
        /** Index of '>' when the tag closed, index of a malformed '<', or text length. */
        val end: Int,
        val restart: Boolean,
    ) {
        val endedInsideTag: Boolean get() = !restart && end >= Int.MAX_VALUE - 1

        val sub: TagSub = when {
            quoteOpen || afterEq -> TagSub.ATTR_VALUE
            !nameDone -> TagSub.NAME
            else -> TagSub.ATTR_NAME
        }
    }

    private fun completeHtml(t: String): WebQueryResult {
        if (t.isEmpty()) return WebQueryResult.NONE

        // Unterminated comment anywhere in front of the caret blocks everything.
        val commentStart = t.lastIndexOf("<!--")
        if (commentStart >= 0 && t.indexOf("-->", commentStart + 4) < 0) return WebQueryResult.NONE

        var i = 0
        while (i <= t.length) {
            val lt = t.indexOf('<', i)
            if (lt < 0) return WebQueryResult.NONE // plain text content
            if (t.startsWith("<!--", lt)) {
                val end = t.indexOf("-->", lt + 4)
                if (end < 0) return WebQueryResult.NONE
                i = end + 3
                continue
            }
            val isClose = lt + 1 < t.length && t[lt + 1] == '/'
            // `a < b` in plain text: browsers treat "< " as content, not a tag open.
            if (!isClose && lt + 1 < t.length && t[lt + 1].isWhitespace()) {
                i = lt + 1
                continue
            }
            val scan = scanTag(t, lt + (if (isClose) 2 else 1))
            if (scan.endedInsideTag) {
                val tagName = scan.name.toString().lowercase()
                return when (scan.sub) {
                    TagSub.NAME ->
                        if (isClose) closingTagCompletions(t, scan.name.toString())
                        else tagCompletions(scan.name.toString())
                    TagSub.ATTR_NAME ->
                        attrCompletions(tagName, scan.attrToken.toString())
                    TagSub.ATTR_VALUE ->
                        attrValueCompletions(tagName, scan.attrName ?: "", scan.value.toString())
                }
            }
            if (scan.restart) {
                i = scan.end // reprocess the '<' that made this tag malformed
                continue
            }
            val tagName = scan.name.toString().lowercase()
            if (!isClose && !scan.selfClosed && (tagName == "script" || tagName == "style")) {
                val closeIdx = indexOfClosingTag(t, tagName, scan.end + 1)
                if (closeIdx < 0) {
                    val contentStart = (scan.end + 1).coerceAtMost(t.length)
                    val content = t.substring(contentStart)
                    return if (tagName == "script") completeJs(content, ts = false)
                    else completeCss(content)
                }
                val gt = t.indexOf('>', closeIdx)
                if (gt < 0) return WebQueryResult.NONE // cursor sits inside "</script" itself
                i = gt + 1
                continue
            }
            i = scan.end + 1
        }
        return WebQueryResult.NONE
    }

    /**
     * Forward-scans the inside of a tag starting at [from] (first char after `<` / `</`),
     * quote-aware. Returns either the state at end-of-text (caret inside the tag) or the
     * position where the tag closed.
     */
    private fun scanTag(t: String, from: Int): TagScan {
        val name = StringBuilder()
        val attrToken = StringBuilder()
        val value = StringBuilder()
        var attrName: String? = null
        var quote = ' '
        var afterEq = false
        var nameDone = false
        var sawSlash = false
        var j = from
        while (j < t.length) {
            val c = t[j]
            if (quote != ' ') {
                if (c == quote) quote = ' ' else value.append(c)
                j++
                continue
            }
            when {
                c == '>' -> {
                    return TagScan(
                        name, attrToken, attrName, value,
                        quoteOpen = false, afterEq = false, nameDone = nameDone,
                        selfClosed = sawSlash, end = j, restart = false
                    )
                }
                c == '<' -> {
                    // A '<' inside a tag is treated as plain text by browsers; restart there.
                    return TagScan(
                        name, attrToken, attrName, value,
                        quoteOpen = false, afterEq = false, nameDone = nameDone,
                        selfClosed = false, end = j, restart = true
                    )
                }
                c == '"' || c == '\'' -> {
                    if (afterEq) {
                        quote = c
                        value.clear()
                    } else {
                        attrToken.clear()
                    }
                }
                c.isWhitespace() -> {
                    if (!nameDone) nameDone = true
                    else {
                        attrToken.clear()
                        attrName = null
                        afterEq = false
                        value.clear()
                    }
                }
                c == '=' -> {
                    if (!nameDone) nameDone = true
                    attrName = attrToken.toString()
                    attrToken.clear()
                    afterEq = true
                    value.clear()
                }
                c == '/' -> {
                    if (!nameDone) nameDone = true
                    sawSlash = true
                }
                else -> {
                    sawSlash = false
                    if (!nameDone) name.append(c)
                    else if (afterEq) value.append(c)
                    else attrToken.append(c)
                }
            }
            j++
        }
        // Caret is inside this tag.
        return TagScan(
            name, attrToken, attrName, value,
            quoteOpen = quote != ' ', afterEq = afterEq, nameDone = nameDone,
            selfClosed = sawSlash, end = Int.MAX_VALUE - 1, restart = false
        )
    }

    private fun tagCompletions(prefix: String): WebQueryResult {
        val items = WebTables.HTML_TAGS
            .filter { prefix.isEmpty() || it.startsWith(prefix, ignoreCase = true) }
            .map { tag ->
                if (tag in WebTables.VOID_TAGS) {
                    WebCompletion(tag, "$tag ", "void tag", WebCompletion.Kind.TAG)
                } else {
                    WebCompletion(tag, "$tag></$tag>", "tag", WebCompletion.Kind.TAG, tag.length + 3)
                }
            }
        return WebQueryResult(prefix, items)
    }

    private fun closingTagCompletions(t: String, prefix: String): WebQueryResult {
        val stack = openTagStack(stripEmbedded(stripComments(t)))
        val items = stack.asReversed()
            .take(8)
            .filter { prefix.isEmpty() || it.startsWith(prefix, ignoreCase = true) }
            .map { tag -> WebCompletion(tag, "$tag>", "close <${tag}>", WebCompletion.Kind.TAG) }
        return WebQueryResult(prefix, items)
    }

    private fun attrCompletions(tag: String, prefix: String): WebQueryResult {
        val specific = WebTables.tagAttrs(tag)
        val all = (specific + WebTables.GLOBAL_ATTRS).distinct()
        val items = all
            .filter { prefix.isEmpty() || it.startsWith(prefix, ignoreCase = true) }
            .map { attrName ->
                val valueless = attrName in WebTables.BOOLEAN_ATTRS
                WebCompletion(
                    attrName,
                    if (valueless) attrName else "$attrName=\"\"",
                    if (attrName in specific) "$tag attribute" else "global attribute",
                    WebCompletion.Kind.ATTRIBUTE,
                    if (valueless) 0 else 1
                )
            }
        return WebQueryResult(prefix, items)
    }

    private fun attrValueCompletions(tag: String, attr: String, prefix: String): WebQueryResult {
        if (attr.isEmpty()) return WebQueryResult.NONE
        val values = WebTables.tagAttrValues(tag, attr)
        val items = values
            .filter { prefix.isEmpty() || it.startsWith(prefix, ignoreCase = true) }
            .map { v -> WebCompletion(v, v, "$tag $attr", WebCompletion.Kind.VALUE) }
        return WebQueryResult(prefix, items)
    }

    // ── HTML helpers ────────────────────────────────────────────────────

    private fun stripComments(t: String): String =
        t.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun stripEmbedded(t: String): String =
        t.replace(Regex("<script[^>]*>[\\s\\S]*?(?:</script>|$)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?(?:</style>|$)", RegexOption.IGNORE_CASE), "")

    private val TAG_RX = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:"[^"]*"|'[^']*'|[^>"'])*)(/?)>""")

    /** Innermost-first list of currently open (unclosed, non-void) elements. */
    private fun openTagStack(t: String): List<String> {
        val stack = ArrayDeque<String>()
        for (m in TAG_RX.findAll(t)) {
            val tagName = m.groupValues[2].lowercase()
            if (tagName in WebTables.VOID_TAGS) continue
            if (m.groupValues[1] == "/") {
                val idx = stack.lastIndexOf(tagName)
                if (idx >= 0) stack.removeAt(idx)
            } else if (m.groupValues[4] != "/") {
                stack.addLast(tagName)
            }
        }
        return stack.toList()
    }

    private fun indexOfClosingTag(t: String, tag: String, from: Int): Int {
        val needle = "</$tag"
        var idx = from
        while (true) {
            idx = t.indexOf(needle, idx)
            if (idx < 0) return -1
            val after = idx + needle.length
            val next = t.getOrNull(after)
            if (next == null || next == '>' || next.isWhitespace()) return idx
            idx = after
        }
    }

    // ═══════════════════════════════ CSS ═══════════════════════════════

    private fun completeCss(t: String): WebQueryResult {
        if (t.isEmpty()) return WebQueryResult.NONE

        val commentStart = t.lastIndexOf("/*")
        if (commentStart >= 0 && t.indexOf("*/", commentStart + 2) < 0) return WebQueryResult.NONE

        val lastOpen = t.lastIndexOf('{')
        if (lastOpen > t.lastIndexOf('}')) {
            // Inside a declaration block.
            val body = t.substring(lastOpen + 1)
            val decl = body.substringAfterLast(';')
            val colon = decl.indexOf(':')
            return if (colon >= 0) {
                val prop = decl.substring(0, colon).trim().lowercase()
                cssValueCompletions(prop, decl.substring(colon + 1))
            } else {
                val prefix = identTail(decl) { it.isLetter() || it == '-' }
                val items = WebTables.CSS_PROPERTIES
                    .filter { prefix.isEmpty() || it.startsWith(prefix) }
                    .map { p -> WebCompletion(p, "$p: ", "property", WebCompletion.Kind.ATTRIBUTE) }
                WebQueryResult(prefix, items)
            }
        }

        // Top level: strip a possibly unterminated string (odd number of unescaped quotes
        // since the last structural boundary) so we never suggest inside `content: "…`.
        val boundary = maxOf(t.lastIndexOf('{'), t.lastIndexOf('}'), t.lastIndexOf(';'))
        val tail = if (boundary >= 0) t.substring(boundary + 1) else t
        if (tail.count { it == '"' } % 2 == 1 || tail.count { it == '\'' } % 2 == 1) {
            return WebQueryResult.NONE
        }

        val prefix = identTail(tail) { it.isLetter() || it == '-' || it == '@' }
        if (prefix.startsWith("@")) {
            val items = WebTables.AT_RULES.filter { it.label.startsWith(prefix) }
            return WebQueryResult(prefix, items)
        }
        // Inside @media(…) prelude: suggest media features.
        if (Regex("@media[^{;]*\\([^)]*$", RegexOption.IGNORE_CASE).containsMatchIn(tail)) {
            val featurePrefix = identTail(tail) { it.isLetter() || it == '-' }
            val items = WebTables.MEDIA_FEATURES
                .filter { featurePrefix.isEmpty() || it.startsWith(featurePrefix) }
                .map { f -> WebCompletion(f, f, "media feature", WebCompletion.Kind.VALUE) }
            return WebQueryResult(featurePrefix, items)
        }
        // Selector position: nothing useful to offer without user-defined names.
        return WebQueryResult(prefix, emptyList())
    }

    private fun cssValueCompletions(prop: String, valuePart: String): WebQueryResult {
        val table = WebTables.CSS_PROPERTY_VALUES[prop].orEmpty()
        val items = ArrayList<WebCompletion>()
        if (valuePart.isBlank()) {
            // Right after the colon (also with the trailing space our own property
            // insert produces): offer every keyword for this property.
            table.forEach { items += WebCompletion(it, it, prop, WebCompletion.Kind.VALUE) }
            return WebQueryResult("", items)
        }
        val lastToken = valuePart.substringAfterLast(' ')
        if (lastToken.isEmpty()) return WebQueryResult("", emptyList()) // token finished
        val numeric = Regex("^-?\\d+(?:\\.\\d+)?([a-zA-Z%]*)$").matchEntire(lastToken)
        val unitPrefix = numeric?.groupValues?.get(1).orEmpty()
        table.filter { it.startsWith(lastToken) }.forEach {
            items += WebCompletion(it, it, prop, WebCompletion.Kind.VALUE)
        }
        if (numeric != null) {
            WebTables.CSS_UNITS
                .filter { unitPrefix.isEmpty() || it.startsWith(unitPrefix) }
                .forEach { u ->
                    // Keep the typed number: replacing "10" with insert "10px".
                    items += WebCompletion(u, lastToken + u, "unit", WebCompletion.Kind.VALUE)
                }
        }
        return WebQueryResult(lastToken, items)
    }

    // ═══════════════════════ JavaScript / TypeScript ═══════════════════════

    private fun completeJs(t: String, ts: Boolean): WebQueryResult {
        if (inStringOrComment(t)) return WebQueryResult.NONE

        val dotIdx = t.lastIndexOf('.')
        if (dotIdx >= 0) {
            val member = identTail(t.substring(dotIdx + 1)) { isJsIdent(it) }
            val receiver = receiverBefore(t, dotIdx) ?: return WebQueryResult(member, emptyList())
            val members = when (receiver) {
                "__element__" -> WebTables.ELEMENT_MEMBERS
                else -> WebTables.JS_MEMBERS[receiver] ?: emptyList()
            }
            val items = members.filter { member.isEmpty() || it.label.startsWith(member) }
            return WebQueryResult(member, items)
        }

        val prefix = identTail(t) { isJsIdent(it) }
        if (prefix.isEmpty()) return WebQueryResult.NONE
        val items = buildList {
            addAll(WebTables.JS_KEYWORDS)
            addAll(WebTables.JS_GLOBALS)
            addAll(WebTables.JS_DOM_METHODS)
            addAll(WebTables.JS_SNIPPETS)
            if (ts) {
                addAll(WebTables.TS_TYPE_KEYWORDS)
                addAll(WebTables.TS_UTILITY_TYPES)
            }
        }.filter { it.label.startsWith(prefix) }
            .distinctBy { "${it.label}\u0000${it.insert}" }
        return WebQueryResult(prefix, items)
    }

    private fun isJsIdent(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /**
     * Identifier directly before the dot, or `"__element__"` for a
     * `document.querySelector(...)`/`getElementById(...)`-style chain.
     * Returns null when there is no usable receiver (e.g. number literals).
     */
    private fun receiverBefore(t: String, dotIdx: Int): String? {
        var end = dotIdx
        if (end > 0 && t[end - 1] == ')') {
            // Walk back over the balanced argument list, then read the callee name.
            var depth = 0
            var k = end - 1
            while (k >= 0) {
                when (t[k]) {
                    ')' -> depth++
                    '(' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                k--
            }
            if (k < 0) return null
            var start = k
            while (start > 0 && isJsIdent(t[start - 1])) start--
            val callee = t.substring(start, k)
            return if (callee in setOf(
                    "getElementById", "querySelector", "querySelectorAll",
                    "createElement", "createTextNode", "createElementNS"
                )
            ) "__element__" else null
        }
        var start = end
        while (start > 0 && isJsIdent(t[start - 1])) start--
        if (start >= end) return null
        val ident = t.substring(start, end)
        if (ident.isEmpty() || ident[0].isDigit()) return null // 1.5 → no members
        return ident
    }

    /** Single forward scan tracking strings (' " `), line and block comments. */
    private fun inStringOrComment(t: String): Boolean {
        var inStr = ' '
        var inLine = false
        var inBlock = false
        var i = 0
        val n = t.length
        while (i < n) {
            val c = t[i]
            when {
                inLine -> if (c == '\n') inLine = false
                inBlock -> if (c == '*' && i + 1 < n && t[i + 1] == '/') {
                    inBlock = false
                    i++
                }
                inStr != ' ' -> when {
                    c == '\\' -> i++
                    c == inStr -> inStr = ' '
                }
                c == '/' && i + 1 < n && t[i + 1] == '/' -> {
                    inLine = true
                    i++
                }
                c == '/' && i + 1 < n && t[i + 1] == '*' -> {
                    inBlock = true
                    i++
                }
                c == '\'' || c == '"' || c == '`' -> inStr = c
            }
            i++
        }
        return inStr != ' ' || inLine || inBlock
    }

    // ── shared helpers ──────────────────────────────────────────────────

    private inline fun identTail(s: String, isPart: (Char) -> Boolean): String {
        var start = s.length
        while (start > 0 && isPart(s[start - 1])) start--
        return s.substring(start)
    }
}
