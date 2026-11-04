package com.aide.mobilestudio

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Resolves the project's own resources for the layout preview.
 *
 * The preview renders a layout XML file without an `aapt`-compiled resource
 * table, so `@dimen/`, `@color/`, `@string/`, `@bool/`, `@integer/` and
 * `@style/` references used to silently fall back to defaults and the preview
 * came out wrong. This class reads the raw `res/values*` files instead and
 * dereferences them.
 *
 * Behaviour:
 *  - References are followed transitively (`@dimen/a` -> `@dimen/b` -> `16dp`)
 *    with a cycle guard, so a self-referential resource returns null instead
 *    of hanging the preview.
 *  - Qualifier directories are scored and the best match wins; `values/` is
 *    the baseline and `values-night` only wins when [nightMode] is set. This
 *    is a preview heuristic, not a full resource matcher - density and
 *    smallest-width qualifiers are ignored because the preview draws at the
 *    device's own density anyway.
 *  - Everything is fail-soft: a malformed values file contributes nothing and
 *    never throws, because a preview that is mostly right beats no preview.
 *
 * Pure JVM (java.io + javax.xml): unit-testable on the host.
 */
class PreviewResourceResolver(
    private val resRoots: List<File>,
    private val nightMode: Boolean = false,
) {

    private val dimens = HashMap<String, String>()
    private val colors = HashMap<String, String>()
    private val strings = HashMap<String, String>()
    private val booleans = HashMap<String, String>()
    private val integers = HashMap<String, String>()
    private val styles = HashMap<String, String>()

    init {
        load()
    }

    // ------------------------------------------------------------ access

    /** Number of named resources loaded; used by tests and the status line. */
    val size: Int
        get() = dimens.size + colors.size + strings.size + booleans.size + integers.size

    /**
     * Fully dereferences [raw], or returns it unchanged when it is already a
     * literal. Returns null when the reference cannot be resolved (unknown
     * name, cycle, or an unsupported form such as a theme attribute).
     */
    fun resolve(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        // Theme attributes have no theme in a preview, and "@style/..." is a
        // style handle the caller looks up by name rather than a value.
        if (text.startsWith("?")) return null
        return dereference(text, HashSet(), 0)
    }

    /** Resolved dimension in px, or null. Density conversion is the caller's job. */
    fun dimension(raw: String?): Float? = DimensionParser.toPx(resolve(raw))

    /** Resolved color as `#AARRGGBB`, or null. */
    fun color(raw: String?): String? = ColorParser.canonical(resolve(raw))

    /** Resolved string value, or null. */
    fun string(name: String): String? = strings[name]?.let { dereference(it, HashSet(), 0) }

    /** Resolved boolean, or null. */
    fun bool(name: String): Boolean? =
        booleans[name]?.let { dereference(it, HashSet(), 0) }?.toBooleanStrictOrNull()

    /** Resolved integer, or null. */
    fun integer(name: String): Int? =
        integers[name]?.let { dereference(it, HashSet(), 0) }?.trim()?.toIntOrNull()

    /** Raw declared value of a style attribute, or null when the style is unknown. */
    fun styleAttribute(style: String, attribute: String): String? {
        val body = styles[style.removePrefix("@style/")] ?: return null
        // Styles are stored as "attr=value|attr=value" so they stay a plain string.
        return body.split('|')
            .firstOrNull { it.substringBefore('=').trim() == attribute }
            ?.substringAfter('=', "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------ resolving

    private fun dereference(text: String, seen: MutableSet<String>, depth: Int): String? {
        if (depth > MAX_DEPTH) return null
        val trimmed = text.trim()
        if (!trimmed.startsWith("@")) return trimmed

        val body = when {
            // "@android:dimen/screen_width" - framework resources, not in the
            // project table; the caller keeps its built-in default.
            trimmed.startsWith("@android:") -> return null
            trimmed.startsWith("@+id/") || trimmed.startsWith("@id/") -> return trimmed
            trimmed.startsWith("@null") || trimmed.startsWith("@empty") -> return null
            trimmed.startsWith("@style/") -> return trimmed
            trimmed.startsWith("?") -> return null // theme attribute: no theme here
            else -> trimmed.substringAfter('/')
        }
        if (body.isEmpty()) return null
        if (!seen.add(body)) return null // cycle: @a -> @b -> @a

        val table = when {
            trimmed.startsWith("@dimen/") -> dimens
            trimmed.startsWith("@color/") -> colors
            trimmed.startsWith("@string/") -> strings
            trimmed.startsWith("@bool/") -> booleans
            trimmed.startsWith("@integer/") -> integers
            trimmed.startsWith("@android:color/") -> return null
            else -> return null
        }
        val declared = table[body] ?: return null
        return dereference(declared, seen, depth + 1)
    }

    // ------------------------------------------------------------ loading

    private fun load() {
        val roots = resRoots.filter { it.isDirectory }
        if (roots.isEmpty()) return
        // Baseline first, then qualified dirs, so a qualified value overrides
        // the base one for the same resource name. Ties keep the module order.
        val valuesDirs = roots
            .flatMap { root -> root.listFiles().orEmpty().filter { it.isDirectory && isValuesDir(it) } }
            .sortedWith(compareBy({ qualifierScore(it) }, { it.name }))

        for (dir in valuesDirs) {
            val files = dir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".xml") }
                .sortedBy { it.name }
            for (file in files) {
                runCatching { parseValuesFile(file) }
            }
        }
    }

    /** `values`, or `values-<qualifiers>`. */
    private fun isValuesDir(dir: File): Boolean =
        dir.name == "values" || dir.name.startsWith("values-")

    /**
     * Higher is a better match. The base `values/` directory is preferred,
     * because a preview wants the resource the project declares as its
     * default; only the theme qualifier overrides it, when the preview is
     * actually rendering that theme. Density, width and API-level qualifiers
     * are deliberately ranked below the baseline: the preview draws at the
     * device's own density and screen, so the project default is the honest
     * thing to show.
     */
    private fun qualifierScore(dir: File): Int {
        val name = dir.name
        if (name == "values") return BASE_SCORE
        val qualifiers = name.removePrefix("values-").split('-').filter { it.isNotEmpty() }
        var score = 50
        for (q in qualifiers) {
            when {
                q == "night" -> score = if (nightMode) score.coerceAtLeast(200) else 1
                q == "notnight" -> score = if (nightMode) 1 else score.coerceAtLeast(200)
                // Everything else stays below the baseline.
                else -> score = minOf(score, 50)
            }
        }
        return score
    }

    private fun parseValuesFile(file: File) {
        val document = newDocumentBuilder().parse(file) ?: return
        val root = document.documentElement ?: return
        val children = root.childNodes
        for (i in 0 until children.length) {
            val element = children.item(i) as? Element ?: continue
            when (element.tagName) {
                "dimen" -> put(dimens, element)
                "color" -> put(colors, element)
                "string" -> put(strings, element, decode = true)
                "bool" -> put(booleans, element)
                "integer" -> put(integers, element)
                "item" -> {
                    // <item name="x" type="color">#fff</item>
                    val type = element.getAttribute("type")
                    val name = element.getAttribute("name")
                    val target = when (type) {
                        "dimen" -> dimens
                        "color" -> colors
                        "string" -> strings
                        "bool" -> booleans
                        "integer" -> integers
                        else -> null
                    }
                    if (target != null && name.isNotEmpty()) {
                        target[name] = textOf(element, decode = type == "string")
                    }
                }
                "style" -> {
                    val name = element.getAttribute("name")
                    if (name.isEmpty()) continue
                    val entries = mutableListOf<String>()
                    val items = element.childNodes
                    for (j in 0 until items.length) {
                        val item = items.item(j) as? Element ?: continue
                        if (item.tagName != "item") continue
                        val attr = item.getAttribute("name")
                        if (attr.isEmpty()) continue
                        entries.add("$attr=${textOf(item, decode = false)}")
                    }
                    if (entries.isNotEmpty()) styles[name] = entries.joinToString("|")
                }
            }
        }
    }

    private fun put(target: MutableMap<String, String>, element: Element, decode: Boolean = false) {
        val name = element.getAttribute("name")
        if (name.isEmpty()) return
        target[name] = textOf(element, decode)
    }

    /** Element text with XML escapes decoded for string resources. */
    private fun textOf(element: Element, decode: Boolean): String {
        val raw = element.textContent.orEmpty().trim()
        return if (decode) unescape(raw) else raw
    }

    private fun unescape(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> append('\n'); 't' -> append('\t')
                    else -> append(next)
                }
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    private fun newDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        // Values files are project-authored, but they are still XML that gets
        // parsed, so refuse DTDs and external entities outright.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { isNamespaceAware = false }
        runCatching { setValidating(false) }
    }.newDocumentBuilder()

    companion object {
        private const val MAX_DEPTH = 8

        /** Score of the baseline `values/` directory - the preferred source. */
        private const val BASE_SCORE = 100
    }
}

/**
 * Pure dimension literal parsing, separated from the Android density
 * conversion so the unit rules can be tested on the JVM.
 */
object DimensionParser {
    /** Units understood by an Android layout, in the order the parser tries them. */
    private val UNITS = listOf("dip", "dp", "sp", "px", "pt", "mm", "in")

    /**
     * Splits a literal like `16dp` into its numeric value and unit, so the
     * caller can convert with the right density. Returns null for anything
     * that is not a dimension literal (references, `match_parent`, junk).
     */
    fun parse(raw: String?): Pair<Float, String>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val unit = UNITS.firstOrNull { text.endsWith(it) } ?: return text.toFloatOrNull()?.let { it to "px" }
        val number = text.dropLast(unit.length).trim().toFloatOrNull() ?: return null
        return number to if (unit == "dip") "dp" else unit
    }

    /**
     * Dimension as px at the reference density of 1 (dp and sp are 1:1), so
     * tests are density-independent. The preview converts with its own
     * display metrics via [toPxAtDensity].
     */
    fun toPx(raw: String?): Float? = parse(raw)?.first

    /** Converts a parsed literal to px using [density] and [fontScale]. */
    fun toPxAtDensity(raw: String?, density: Float, fontScale: Float = 1f): Float? {
        val (value, unit) = parse(raw) ?: return null
        return when (unit) {
            "dp" -> value * density
            "sp" -> value * density * fontScale
            else -> value
        }
    }
}

/**
 * Pure color literal parsing: `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB` into a
 * canonical `#AARRGGBB` string. Kept as text so it is testable without the
 * Android `Color` class.
 */
object ColorParser {
    /** Canonical 9-char `#AARRGGBB` form, or null when [raw] is not a color literal. */
    fun canonical(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith("#")) return null
        val digits = text.substring(1)
        if (!digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return when (digits.length) {
            3 -> "#FF" + digits.map { it }.joinToString("") { "$it$it" }.uppercase()
            4 -> digits.map { it }.joinToString("") { "$it$it" }.uppercase().let { "#$it" }
            6 -> "#FF${digits.uppercase()}"
            8 -> "#${digits.uppercase()}"
            else -> null
        }
    }

    /** ARGB int for the canonical form, or null. */
    fun argb(raw: String?): Int? {
        val canonical = canonical(raw) ?: return null
        return canonical.substring(1).toLongOrNull(16)?.toInt()
    }
}
