package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.Switch
import android.widget.TextView
import com.aide.mobilestudio.ComposeStructuralParser.Kind
import com.aide.mobilestudio.ComposeStructuralParser.ModifierEntry
import com.aide.mobilestudio.ComposeStructuralParser.Node

/**
 * Draws an Android-view approximation of a parsed `@Composable` body.
 *
 * Compose itself cannot run here (there is no composition, no recomposition
 * and no runtime), so this renders the STRUCTURE the parser found: the same
 * layout semantics expressed with classic views, plus the `Modifier` chain
 * values that map onto them. Anything it cannot express becomes a labelled
 * placeholder that names what was skipped - the preview must never look
 * confidently right when it is guessing.
 *
 * All value interpretation lives in [ComposeModifierSpec] and
 * [ComposeColorParser], which are pure and unit-tested; this class only
 * builds views.
 */
class ComposeStructuralRenderer(
    private val context: Context,
    private val density: Float = context.resources.displayMetrics.density,
    /** Typography scale, 1.0 at the user's default text size. */
    private val fontScale: Float = context.resources.configuration.fontScale,
    private val resolver: PreviewResourceResolver? = null,
) {

    /** Renders [node] and its children into a view. Never throws. */
    fun render(node: Node): View = runCatching { buildView(node) }.getOrNull() ?: placeholder(node)

    private fun buildView(node: Node): View {
        val spec = ComposeModifierSpec.of(node.modifiers, density, fontScale)
        val view = when (node.kind) {
            Kind.COLUMN -> container(LinearLayout.VERTICAL)
            Kind.ROW -> container(LinearLayout.HORIZONTAL)
            Kind.BOX -> FrameLayout(context)
            Kind.LAZY_COLUMN -> LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            Kind.LAZY_ROW -> LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            Kind.TEXT -> text(node)
            Kind.BUTTON -> Button(context).apply {
                text = node.text.orEmpty().ifEmpty { "Button" }
                setTextColor(Color.WHITE)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ComposeColorParser.resolve(node.extras["color"]) ?: 0xFF6200EE.toInt())
            }
            Kind.IMAGE, Kind.ICON -> image(node)
            Kind.SPACER -> Space(context)
            Kind.TEXT_FIELD -> EditText(context).apply {
                hint = node.text.orEmpty().ifEmpty { "Text" }
                isFocusable = false
                isFocusableInTouchMode = false
            }
            Kind.CHECKBOX -> CheckBox(context).apply { text = node.text.orEmpty().ifEmpty { "Checkbox" } }
            Kind.SWITCH -> Switch(context).apply { text = node.text.orEmpty().ifEmpty { "Switch" } }
            Kind.SLIDER -> ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 60 }
            Kind.PROGRESS_LINEAR -> ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = 60 }
            Kind.PROGRESS_CIRCULAR -> ProgressBar(context)
            Kind.CARD, Kind.SURFACE -> card(node)
            Kind.SCAFFOLD -> container(LinearLayout.VERTICAL)
            Kind.TOP_BAR -> container(LinearLayout.HORIZONTAL).apply {
                setBackgroundColor(ComposeColorParser.resolve(node.extras["color"]) ?: Color.WHITE)
            }
            Kind.PLACEHOLDER -> placeholder(node)
        }

        applySpec(view, spec)
        applyChildren(view, node)
        return view
    }

    private fun container(orientation: Int): LinearLayout =
        LinearLayout(context).apply { this.orientation = orientation }

    private fun card(node: Node): View {
        val shape = GradientDrawable().apply {
            cornerRadius = 12f * density
            setColor(Color.WHITE)
        }
        return FrameLayout(context).apply {
            background = shape
            elevation = 2f * density
            // Cards draw their content with padding, like Compose's Card.
            val inset = (12 * density).toInt()
            setPadding(inset, inset, inset, inset)
        }
    }

    private fun text(node: Node): View {
        val color = ComposeColorParser.resolve(node.extras["color"]) ?: 0xDD000000.toInt()
        val sizeSp = ComposeModifierSpec.spValue(node.extras["fontSize"]) ?: 14f
        return TextView(context).apply {
            text = node.text.orEmpty()
            setTextColor(color)
            textSize = sizeSp
            maxLines = Int.MAX_VALUE
            typeface = if (ComposeModifierSpec.isBold(node.extras["fontWeight"])) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            ComposeModifierSpec.gravityOf(node.extras["textAlign"])?.let { gravity = it }
            if (node.extras.containsKey("maxLines")) {
                node.extras["maxLines"]?.toIntOrNull()?.let { maxLines = it }
            }
        }
    }

    private fun image(node: Node): View {
        // A real bitmap is only drawable for res/mipmap references; everything
        // else gets a labelled frame so the preview still reads correctly.
        val contentDesc = node.text
        return ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_gallery)
            contentDescription = contentDesc
        }
    }

    /** Children get laid out according to the parent's layout semantics. */
    private fun applyChildren(parent: View, node: Node) {
        if (node.children.isEmpty()) return
        val parentView = parent

        // Unscrollable lazy containers still stack their children; the preview
        // surface scrolls, so there is no need to nest another scroller.
        when (parentView) {
            is LinearLayout -> {
                for (child in node.children) {
                    val spec = ComposeModifierSpec.of(child.modifiers, density, fontScale)
                    val lp = LinearLayout.LayoutParams(
                        childWidth(spec),
                        childHeight(spec),
                    ).apply {
                        applyMargins(this, spec)
                        if (spec.weight > 0f) {
                            weight = spec.weight
                            width = 0
                        }
                        spec.alignInParent?.let { gravity = it }
                    }
                    parentView.addView(render(child), lp)
                }
            }
            is FrameLayout -> {
                for (child in node.children) {
                    val spec = ComposeModifierSpec.of(child.modifiers, density, fontScale)
                    val lp = FrameLayout.LayoutParams(childWidth(spec), childHeight(spec)).apply {
                        applyMargins(this, spec)
                        gravity = spec.alignInParent ?: (Gravity.TOP or Gravity.START)
                    }
                    parentView.addView(render(child), lp)
                }
            }
        }
    }

    private fun childWidth(spec: ComposeModifierSpec): Int =
        spec.width ?: matchOrWrap(spec.fillMaxWidth, spec.matchParentSize)

    private fun childHeight(spec: ComposeModifierSpec): Int =
        spec.height ?: matchOrWrap(spec.fillMaxHeight, spec.matchParentSize)

    private fun applyMargins(lp: ViewGroup.MarginLayoutParams, spec: ComposeModifierSpec) {
        lp.leftMargin = spec.margin[0]
        lp.topMargin = spec.margin[1]
        lp.rightMargin = spec.margin[2]
        lp.bottomMargin = spec.margin[3]
    }

    private fun matchOrWrap(fill: Boolean, matchParent: Boolean): Int = when {
        matchParent || fill -> ViewGroup.LayoutParams.MATCH_PARENT
        else -> ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private fun applySpec(view: View, spec: ComposeModifierSpec) {
        spec.padding?.let { view.setPadding(it[0], it[1], it[2], it[3]) }
        spec.alpha?.let { view.alpha = it }
        spec.background?.let { view.setBackgroundColor(it) }
        view.visibility = if (spec.hidden) View.GONE else View.VISIBLE
        spec.rotation?.let { view.rotation = it }
    }

    private fun placeholder(node: Node): View {
        val label = node.label ?: node.kind.name
        return TextView(context).apply {
            text = label
            setTextColor(0xFF8B949E.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                setColor(0x14000000)
                cornerRadius = 6f * density
                setStroke((1 * density).toInt().coerceAtLeast(1), 0x33000000)
            }
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
        }
    }
}

/**
 * Interprets a parsed `Modifier` chain into concrete pixel values.
 *
 * Pure: the density and font scale are passed in, so every rule here is
 * unit-testable on the JVM without an Android device.
 */
data class ComposeModifierSpec(
    /** l,t,r,b in px, or null when the chain declares no padding. */
    val padding: IntArray? = null,
    /** l,t,r,b in px. */
    val margin: IntArray = intArrayOf(0, 0, 0, 0),
    val width: Int? = null,
    val height: Int? = null,
    val fillMaxWidth: Boolean = false,
    val fillMaxHeight: Boolean = false,
    val matchParentSize: Boolean = false,
    val background: Int? = null,
    val alpha: Float? = null,
    val rotation: Float? = null,
    val weight: Float = 0f,
    val hidden: Boolean = false,
    /** Gravity for a Box child's `align(...)`, or for a Text's textAlign. */
    val alignInParent: Int? = null,
) {
    companion object {

        /**
         * Reads a modifier chain in declaration order, which is how Compose
         * applies it: the last matching rule wins.
         */
        fun of(modifiers: List<ModifierEntry>, density: Float, fontScale: Float = 1f): ComposeModifierSpec {
            var padding: IntArray? = null
            val margin = intArrayOf(0, 0, 0, 0)
            var width: Int? = null
            var height: Int? = null
            var fillWidth = false
            var fillHeight = false
            var matchParentSize = false
            var background: Int? = null
            var alpha: Float? = null
            var rotation: Float? = null
            var weight = 0f
            var hidden = false
            var align: Int? = null

            for (entry in modifiers) {
                when (entry.name) {
                    "padding" -> padding = sides(entry.args, density, fontScale, padding)
                    "paddingStart", "paddingLeft" -> padding = withSide(padding, 0, entry.args, density, fontScale)
                    "paddingTop" -> padding = withSide(padding, 1, entry.args, density, fontScale)
                    "paddingEnd", "paddingRight" -> padding = withSide(padding, 2, entry.args, density, fontScale)
                    "paddingBottom" -> padding = withSide(padding, 3, entry.args, density, fontScale)
                    "size" -> {
                        width = px(entry.args.getOrNull(0), density, fontScale)
                        height = px(entry.args.getOrNull(1), density, fontScale) ?: width
                    }
                    "width" -> width = px(entry.args.getOrNull(0), density, fontScale)
                    "height" -> height = px(entry.args.getOrNull(0), density, fontScale)
                    "fillMaxWidth" -> fillWidth = true
                    "fillMaxHeight" -> fillHeight = true
                    "fillMaxSize" -> { fillWidth = true; fillHeight = true }
                    "matchParentSize" -> matchParentSize = true
                    "wrapContentWidth", "wrapContentHeight", "wrapContentSize" -> {
                        if (entry.name != "wrapContentHeight") width = ViewGroup.LayoutParams.WRAP_CONTENT
                        if (entry.name != "wrapContentWidth") height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                    "background" -> background = ComposeColorParser.resolve(entry.args.getOrNull(0))
                    "contentColor" -> Unit
                    "alpha" -> alpha = entry.args.firstNotNullOfOrNull { it.toFloatOrNull() }
                    "rotate" -> rotation = entry.args.firstNotNullOfOrNull { it.trimEnd('f').toFloatOrNull() }
                    "weight" -> weight = entry.args.firstNotNullOfOrNull { it.trimEnd('f').toFloatOrNull() } ?: 1f
                    "hidden", "visibility" -> hidden = entry.args.any { it.contains("Gone") || it.contains("gone") }
                    "align" -> align = alignmentGravity(entry.args.firstOrNull())
                    "offset" -> {
                        // Offsets shift drawing, not layout; approximating with
                        // margin keeps the preview in the right place.
                        px(entry.args.getOrNull(0), density, fontScale)?.let { margin[0] = it; margin[2] = -it }
                        px(entry.args.getOrNull(1), density, fontScale)?.let { margin[1] = it; margin[3] = -it }
                    }
                    "border" -> {
                        val color = ComposeColorParser.resolve(entry.args.getOrNull(1))
                            ?: ComposeColorParser.resolve(entry.args.getOrNull(0))
                        if (color != null && background == null) background = color
                    }
                }
            }
            return ComposeModifierSpec(
                padding = padding, margin = margin, width = width, height = height,
                fillMaxWidth = fillWidth, fillMaxHeight = fillHeight,
                matchParentSize = matchParentSize, background = background,
                alpha = alpha, rotation = rotation, weight = weight, hidden = hidden,
                alignInParent = align,
            )
        }

        private fun sides(
            args: List<String>,
            density: Float,
            fontScale: Float,
            previous: IntArray?,
        ): IntArray? {
            when (args.size) {
                0 -> return previous
                1 -> {
                    val one = paddingPx(args[0], density, fontScale) ?: return previous
                    return intArrayOf(one, one, one, one)
                }
                2 -> {
                    val h = paddingPx(args[0], density, fontScale) ?: return previous
                    val v = paddingPx(args[1], density, fontScale) ?: return previous
                    return intArrayOf(h, v, h, v)
                }
                else -> return intArrayOf(0, 0, 0, 0).also { target ->
                    for (i in 0..3) paddingPx(args.getOrNull(i), density, fontScale)?.let { target[i] = it }
                }
            }
        }

        private fun withSide(
            current: IntArray?,
            index: Int,
            args: List<String>,
            density: Float,
            fontScale: Float,
        ): IntArray {
            val base = (current ?: intArrayOf(0, 0, 0, 0)).clone()
            paddingPx(args.getOrNull(0), density, fontScale)?.let { base[index] = it }
            return base
        }

        /**
         * Padding is a length, so `Dp.Unspecified` and `Dp.Infinite` mean "no
         * padding declared" rather than the layout-param sentinels [px] returns.
         */
        private fun paddingPx(raw: String?, density: Float, fontScale: Float): Int? =
            px(raw, density, fontScale)?.takeIf { it >= 0 }

        /**
         * A Compose size argument: `16.dp`, `16.dp` in a `Dp` expression, a
         * resource reference, or `Dp.Unspecified` / `Infinity` meaning "let the
         * parent decide". Returns px, or null when it cannot be interpreted.
         */
        fun px(raw: String?, density: Float, fontScale: Float = 1f): Int? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            // Compared case-insensitively because Compose writes these as
            // Dp.Unspecified / Dp.Infinite / Float.POSITIVE_INFINITY.
            val lower = text.lowercase()
            // Unspecified/Wrap lets the parent decide; Infinite fills it.
            if (lower.contains("unspecified") || lower.contains("wrap")) {
                return ViewGroup.LayoutParams.WRAP_CONTENT
            }
            if (lower.contains("infinity") || lower.contains("infinite")) {
                return ViewGroup.LayoutParams.MATCH_PARENT
            }
            val normalized = text.removeSuffix(".dp").removeSuffix(".sp")
                .removeSuffix(".Dp").removeSuffix(".Sp")
                .trim()
            val value = normalized.removeSuffix("f").toFloatOrNull() ?: return null
            return when {
                text.contains(".sp") || text.endsWith("Sp") -> (value * density * fontScale).toInt()
                else -> (value * density).toInt()
            }
        }

        /** `sp` magnitude of a `fontSize` argument, e.g. `20.sp` -> 20. */
        fun spValue(raw: String?): Float? {
            val text = raw?.trim().orEmpty().removeSuffix(".sp").removeSuffix("Sp").trim()
            return text.removeSuffix("f").toFloatOrNull()
        }

        fun isBold(raw: String?): Boolean {
            val text = raw.orEmpty()
            if (text.contains("Bold", ignoreCase = true)) return true
            // FontWeight.W700 .. W900 are the bold half of the scale.
            return Regex("""W([7-9])\d""").containsMatchIn(text)
        }

        fun gravityOf(raw: String?): Int? = when (raw?.substringAfterLast('.')) {
            "Start", "Left" -> Gravity.START
            "End", "Right" -> Gravity.END
            "Center" -> Gravity.CENTER
            "CenterHorizontally" -> Gravity.CENTER_HORIZONTAL
            "CenterVertically" -> Gravity.CENTER_VERTICAL
            else -> null
        }

        /** `Alignment.Center` etc. as an Android gravity for a Box child. */
        fun alignmentGravity(raw: String?): Int? {
            val name = raw?.substringAfterLast('.').orEmpty()
            return when (name) {
                "TopStart", "TopLeft" -> Gravity.TOP or Gravity.START
                "TopCenter" -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                "TopEnd", "TopRight" -> Gravity.TOP or Gravity.END
                "CenterStart", "CenterLeft" -> Gravity.CENTER
                "Center" -> Gravity.CENTER
                "CenterEnd", "CenterRight" -> Gravity.CENTER
                "BottomStart", "BottomLeft" -> Gravity.BOTTOM or Gravity.START
                "BottomCenter" -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                "BottomEnd", "BottomRight" -> Gravity.BOTTOM or Gravity.END
                "Start", "Left" -> Gravity.START
                "End", "Right" -> Gravity.END
                "Top" -> Gravity.TOP
                "Bottom" -> Gravity.BOTTOM
                else -> null
            }
        }
    }
}

/**
 * Resolves Compose color literals to ARGB ints: `Color.White`, `Color.Red`,
 * `Color(0xFF6200EE)`, `Color(colorRes)` and `colorResource(R.color.x)`.
 *
 * Pure, so the mapping is unit-tested; returns null for anything unknown,
 * which keeps the caller's default.
 */
object ComposeColorParser {
    private val NAMED = mapOf(
        "White" to "#FFFFFFFF", "Black" to "#FF000000", "Red" to "#FFFF0000",
        "Green" to "#FF00FF00", "Blue" to "#FF0000FF", "Yellow" to "#FFFFFF00",
        "Cyan" to "#FF00FFFF", "Magenta" to "#FFFF00FF", "Gray" to "#FF888888",
        "DarkGray" to "#FF444444", "LightGray" to "#FFCCCCCC",
        "Transparent" to "#00000000",
    )

    /** ARGB int for a Compose color expression, or null. */
    fun resolve(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // Color(0xAARRGGBB) / Color(0xRRGGBB) / Color(colorRes)
        val call = Regex("""^Color\s*\((.*)\)$""").find(text)?.groupValues?.get(1)?.trim()
        if (call != null) {
            if (call.startsWith("0x") || call.startsWith("0X")) {
                return parseHex(call.removePrefix("0x").removePrefix("0X"))
            }
            return ColorParser.argb(call) ?: namedOrResource(call)
        }
        // colorResource(R.color.brand) has no compiled resource table here;
        // returning null keeps the caller's default colour.
        if (Regex("""^(?:colorResource|materialColor)\s*\(""").containsMatchIn(text)) return null
        return namedOrResource(text)
    }

    private fun namedOrResource(text: String): Int? {
        val bare = text.substringAfterLast('.')
        return NAMED[bare]?.let { ColorParser.argb(it) }
    }

    /**
     * Compose hex literals may omit the alpha channel: `0xRRGGBB` means fully
     * opaque, unlike Android's `#RRGGBB` convention which this also accepts.
     */
    private fun parseHex(digits: String): Int? {
        val clean = digits.trim().takeWhile { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
        if (clean.isEmpty()) return null
        val padded = when (clean.length) {
            6 -> "FF$clean"
            8 -> clean
            else -> return null
        }
        return padded.toLongOrNull(16)?.toInt()
    }
}
