package com.aide.mobilestudio

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object StudioUi {
    // ── Base palette (IntelliJ Darcula-inspired) ──────────────────────────
    const val Ink = "#0D1117"
    const val Surface = "#161B22"
    const val Surface2 = "#30363D"
    const val Surface3 = "#484F58"
    const val Card = "#161B22"
    const val Card2 = "#21262D"
    const val Border = "#30363D"
    const val Text = "#F0F6FC"
    const val TextSoft = "#8B949E"
    const val Muted = "#484F58"
    const val Blue = "#58A6FF"
    const val Purple = "#BC8CFF"
    const val Green = "#3FB950"
    const val Amber = "#D29922"
    const val Red = "#F85149"
    const val Cyan = "#39C5CF"

    // ── Spacing system ───────────────────────────────────────────────────
    const val SpacingNone = 0
    const val SpacingXs = 4
    const val SpacingSm = 8
    const val SpacingMd = 12
    const val SpacingLg = 16
    const val SpacingXl = 24

    // ── Corner radius (IDE style: tight, structural corners only) ───────
    const val RadiusSm = 3
    const val RadiusMd = 4
    const val RadiusLg = 6
    const val RadiusXl = 8
    const val RadiusFull = 999

    // ── Elevation (shadow depth) ─────────────────────────────────────────
    const val ElevNone = 0
    const val ElevLow = 2
    const val ElevMed = 4

    // ── Animation durations (ms) ─────────────────────────────────────────
    const val AnimInstant = 60L
    const val AnimFast = 120L
    const val AnimNormal = 200L
    const val AnimSlow = 300L

    // ── Standard interpolator ────────────────────────────────────────────
    val DefaultInterpolator: Interpolator = DecelerateInterpolator(1.5f)

    // ── Runtime theme state ─────────────────────────────────────────────
    var palette: UiPalette = UiTheme.defaultDarkPalette()
        private set

    private var activeThemeMap: Map<String, String> = emptyMap()

    fun applyTheme(p: UiPalette) {
        palette = p
        activeThemeMap = buildThemeMap(p)
    }

    private fun buildThemeMap(p: UiPalette): Map<String, String> {
        val m = HashMap<String, String>()
        fun put(hex: String, value: Int) { m[hex.lowercase()] = toHex(value) }
        put(Blue, p.accent)
        // Remap for EVERY palette, dark and light: every entry is derived from
        // the palette's own fields, so switching between two dark themes
        // (DRACULA <-> MONOKAI <-> NORD) now restyles the imperative UI too.
        // For light palettes the entries below are exactly the mapping that
        // existed before (same keys, same values, same order) — no regression.
        put(Ink, p.bg)
        put(Card, p.card)
        put(Surface, p.surface)
        put(Surface2, p.surface2)
        put(Surface3, p.muted)
        put(Border, p.borderStrong)
        put(Text, p.text)
        put(TextSoft, p.textSoft)
        put(Muted, p.muted)
        put(Divider, p.divider)
        put(DividerLight, p.divider)
        put(LayerToolbar, p.surface)
        put(LayerTabs, p.surface)
        put(LayerEditor, p.editorBg)
        put(LayerStatusBar, p.surface)
        put(LayerBottomPanel, p.card)
        put(LayerPanelTabs, p.surface)
        put(ButtonNormal, p.surface2)
        put(ButtonHover, p.surface)
        put(ButtonBorder, p.borderStrong)
        put(InputBg, p.card)
        put(InputBorder, p.border)
        put(CardBg, p.card)
        put(CardBorder, p.border)
        return m
    }

    private fun toHex(c: Int): String = String.format("#%06X", 0xFFFFFF and c)

    fun color(hex: String): Int = Color.parseColor(remapHex(hex))

    private val PALETTE_REMAP = mapOf(
        // accents
        "#62D8FF" to Blue, "#61AFEF" to Blue, "#58A6FF" to Blue,
        "#528BFF" to Blue, "#7CC7FF" to Blue, "#4F8EF7" to Blue,
        "#1F6FEB" to Blue, "#5EA8F8" to Blue,
        // purple
        "#C678DD" to Purple, "#9D7CFF" to Purple, "#B57CE7" to Purple,
        // greens
        "#7EE787" to Green, "#98C379" to Green, "#238636" to Green,
        "#46C07D" to Green, "#2E8549" to Green, "#2E6F4F" to Green, "#3FB950" to Green,
        // reds
        "#E06C75" to Red, "#FF7B72" to Red, "#DA3633" to Red,
        "#F05C5C" to Red, "#CE4A4E" to Red,
        // amber
        "#E3B963" to Amber, "#E5C07B" to Amber, "#D19A66" to Amber,
        "#D29922" to Amber, "#E0A458" to Amber,
        // cyan
        "#56B6C2" to Cyan, "#39C5CF" to Cyan,
        // text
        "#D8DEE9" to Text, "#D1D5DB" to Text, "#C9D1D9" to Text,
        "#DCE3EE" to Text, "#C3CCD8" to Text, "#E8EDF5" to Text, "#E6EDF3" to Text,
        "#ABB2BF" to TextSoft, "#9DA7B5" to TextSoft, "#7D8590" to TextSoft,
        "#8B949E" to TextSoft, "#8E99AC" to TextSoft, "#AFB9C8" to TextSoft,
        "#7A889B" to TextSoft, "#9CA3AF" to TextSoft, "#D1D5DB" to Text,
        "#7E8AA0" to TextSoft,
        "#687385" to Muted, "#6B7281" to Muted, "#636D83" to Muted,
        "#4B5263" to Muted, "#555E6B" to Muted, "#76839A" to Muted,
        "#6B7280" to Muted, "#5C6370" to Muted, "#4B5563" to Muted,
        "#495162" to Muted, "#606B7E" to Muted, "#5D6B84" to Muted,
        "#76839A" to Muted, "#687385" to Muted, "#555E6B" to Muted,
        // surfaces
        "#1E1F22" to Surface, "#1E2227" to Surface, "#2C313A" to Card2,
        "#30363D" to Surface2, "#232D3B" to Surface2, "#2D333B" to Surface2,
        "#3A4250" to Surface2, "#262B33" to Surface2, "#2E3540" to Surface2,
        "#333B47" to Surface2, "#2F3B4D" to Surface2, "#3A4556" to Surface2,
        "#374151" to Surface2, "#20242B" to Surface, "#21262D" to Surface,
        "#2F4675" to Surface, "#1F2A3D" to Surface, "#202A38" to Surface,
        "#172033" to Surface, "#1A2332" to Surface, "#1C212B" to Surface,
        "#1C2333" to Surface, "#1F2A3D" to Surface, "#262B33" to Surface2,
        "#171C25" to Surface, "#2E3540" to Divider, "#3E4451" to Divider,
        // inks / cards
        "#0A0F18" to Ink, "#0B1B2A" to Ink, "#071018" to Ink, "#08111E" to Ink,
        "#0D1117" to Ink, "#0F1218" to Ink, "#0B0F15" to Ink, "#0A0E16" to Ink,
        "#0A0F1A" to Ink, "#0F1114" to Ink, "#0B1020" to Ink, "#0C1322" to Ink,
        "#0B1320" to Ink, "#0E1524" to Ink, "#0B0E14" to Ink, "#0B0E13" to Ink,
        "#101218" to Card, "#11151B" to Card, "#111827" to Card, "#101827" to Card,
        "#121821" to Card, "#151B24" to Card, "#161B22" to Card, "#12161F" to Card,
        "#10141B" to Card, "#191D25" to Card, "#12161F" to Card,
        "#0F1724" to Card, "#101827" to Card, "#13161C" to Card,
        "#131722" to Ink, "#12151C" to Card, "#181D26" to Card,
        "#0D1219" to Ink,
        // editor
        "#282C34" to LayerEditor, "#1C2028" to LayerEditor,
        "#1E2530" to LayerToolbar, "#141820" to LayerStatusBar,
        "#121620" to LayerPanelTabs, "#191E26" to LayerTabs,
        "#202833" to Surface2
    )

    fun remapHex(hex: String): String = resolveHex(hex.lowercase())

    private val paletteRemapLower by lazy {
        PALETTE_REMAP.mapKeys { it.key.lowercase() }
    }

    private fun resolveHex(hex: String): String {
        var h = hex
        val seen = HashSet<String>()
        while (true) {
            val next = activeThemeMap[h] ?: paletteRemapLower[h] ?: return h
            val l = next.lowercase()
            if (!seen.add(l)) return l
            h = l
        }
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun rounded(
        context: Context,
        fill: String = Card,
        stroke: String = Border,
        radiusDp: Int = RadiusLg,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(color(fill))
        if (strokeDp > 0) setStroke(dp(context, strokeDp), color(stroke))
    }

    fun gradient(
        context: Context,
        start: String = "#161B22",
        end: String = "#0D1117",
        radiusDp: Int = 0,
        orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR
    ): GradientDrawable = GradientDrawable(orientation, intArrayOf(color(start), color(end))).apply {
        cornerRadius = dp(context, radiusDp).toFloat()
    }

    fun glow(
        context: Context,
        fill: String,
        accent: String,
        radiusDp: Int = RadiusLg
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(color(fill), color("#161B22"))
    ).apply {
        cornerRadius = dp(context, radiusDp).toFloat()
        setStroke(dp(context, 1), color(accent))
    }

    fun ripple(
        context: Context,
        normal: GradientDrawable,
        rippleColor: String = "#555E6B"
    ): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(color(rippleColor)), normal, null)

    fun cardBg(context: Context): GradientDrawable =
        rounded(context, fill = CardBg, stroke = CardBorder, radiusDp = RadiusLg)

    /** Int-color variant for UiPalette-driven colors (palette fields are Color ints). */
    fun roundedInt(
        context: Context,
        fill: Int,
        stroke: Int = 0,
        radiusDp: Int = RadiusLg,
        strokeDp: Int = 0
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(context, strokeDp), stroke)
    }

    fun inputBg(context: Context): GradientDrawable =
        rounded(context, fill = InputBg, stroke = InputBorder, radiusDp = RadiusMd)

    // ── Touch feedback ──────────────────────────────────────────────────

    fun touchLift(view: View, scaleDown: Float = 0.97f) {
        enforceMinTouchTarget(view)
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> target.animate()
                    .scaleX(scaleDown)
                    .scaleY(scaleDown)
                    .alpha(0.88f)
                    .setDuration(AnimInstant)
                    .setInterpolator(DefaultInterpolator)
                    .start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(AnimFast)
                    .setInterpolator(DefaultInterpolator)
                    .start()
            }
            false
        }
    }

    fun enforceMinTouchTarget(view: View) {
        val minSize = dp(view.context, 48)
        view.minimumWidth = minSize
        view.minimumHeight = minSize
        if (view is TextView) {
            view.setPadding(
                view.paddingStart.coerceAtLeast(dp(view.context, SpacingMd)),
                view.paddingTop.coerceAtLeast(dp(view.context, SpacingSm)),
                view.paddingEnd.coerceAtLeast(dp(view.context, SpacingMd)),
                view.paddingBottom.coerceAtLeast(dp(view.context, SpacingSm))
            )
        }
    }

    // ── Animation helpers ───────────────────────────────────────────────

    /** Slide a view's height from current to [targetHeight] with a smooth decelerate. */
    fun animatePanelSlide(
        view: View,
        targetHeight: Int,
        duration: Long = AnimNormal,
        onEnd: (() -> Unit)? = null
    ) {
        val start = view.height
        if (start == targetHeight) { onEnd?.invoke(); return }
        ValueAnimator.ofInt(start, targetHeight).apply {
            this.duration = duration
            interpolator = DefaultInterpolator
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                view.layoutParams = view.layoutParams.apply { height = v }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Fade + translate a view in from below with a subtle slide-up. */
    fun animateFadeIn(view: View, duration: Long = AnimNormal, offsetDp: Int = 8) {
        view.alpha = 0f
        view.translationY = dp(view.context, offsetDp).toFloat()
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(duration)
            .setInterpolator(DefaultInterpolator)
            .start()
    }

    /** Fade + translate a view out downward. */
    fun animateFadeOut(view: View, duration: Long = AnimFast, onEnd: (() -> Unit)? = null) {
        view.animate()
            .alpha(0f)
            .translationY(dp(view.context, 4).toFloat())
            .setDuration(duration)
            .setInterpolator(DefaultInterpolator)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.GONE
                    onEnd?.invoke()
                }
            })
            .start()
    }

    /** Animate the width of a horizontal divider to indicate a tab underline. */
    fun animateTabIndicator(indicator: View, targetWidth: Int, duration: Long = AnimFast) {
        ValueAnimator.ofInt(indicator.width, targetWidth).apply {
            this.duration = duration
            interpolator = DefaultInterpolator
            addUpdateListener { anim ->
                val v = anim.animatedValue as Int
                indicator.layoutParams = indicator.layoutParams.apply { width = v }
            }
            start()
        }
    }

    // ── Reduced motion support ──────────────────────────────────────────

    fun shouldAnimate(context: Context): Boolean = try {
        val scale = android.provider.Settings.Global.getFloat(
            context.contentResolver,
            "animator_duration_scale",
            1f
        )
        scale > 0f
    } catch (_: Exception) { true }

    // ── Stagger animation for lists ─────────────────────────────────────

    fun staggerIn(views: List<View>, delayPerItem: Long = 30) {
        if (views.isEmpty()) return
        if (!shouldAnimate(views.first().context)) {
            views.forEach { it.alpha = 1f; it.translationY = 0f }
            return
        }
        views.forEachIndexed { i, view ->
            view.alpha = 0f
            view.translationY = dp(view.context, 8).toFloat()
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(AnimFast)
                .setStartDelay(i * delayPerItem)
                .setInterpolator(DefaultInterpolator)
                .start()
        }
    }

    // ── Int-color rounded helper ────────────────────────────────────────

    fun rounded(
        context: Context,
        fill: Int,
        stroke: Int,
        radiusDp: Int = RadiusLg,
        strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, radiusDp).toFloat()
        setColor(fill)
        if (strokeDp > 0) setStroke(dp(context, strokeDp), stroke)
    }

    /** Animate expand/collapse of a view (height transition). */
    fun animateExpandCollapse(
        view: View,
        expand: Boolean,
        targetHeight: Int,
        duration: Long = AnimFast
    ) {
        if (expand) {
            view.visibility = View.VISIBLE
            animatePanelSlide(view, targetHeight, duration)
        } else {
            animatePanelSlide(view, 0, duration) {
                view.visibility = View.GONE
            }
        }
    }

    // ── Component helpers ─────────────────────────────────────────────────

    fun divider(context: Context, tint: String = Border): View =
        View(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
            )
            background = GradientDrawable().apply { setColor(color(tint)) }
        }

    fun thinDivider(context: Context, tint: String = Divider): View =
        View(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
            )
            background = GradientDrawable().apply { setColor(color(tint)) }
        }

    // ── Type scale ────────────────────────────────────────────────────────
    //
    // Sp sizes only. Screens used to pick whatever looked right in place, so
    // the same role read at three different sizes depending on which panel
    // you were in, and nothing looked quite aligned with the thing beside it.

    /** Dense metadata: file sizes, timestamps, counts in a status line. */
    const val TypeCaption = 10f

    /** Controls that carry a label in a tight row: tabs, chips, small buttons. */
    const val TypeSmall = 11f

    /** Default reading size for body text and list rows. */
    const val TypeBody = 13f

    /** Section and dialog titles. */
    const val TypeTitle = 15f

    /** Screen headings and empty-state titles. */
    const val TypeHeading = 18f

    /**
     * The view a panel shows when it has nothing to show.
     *
     * A blank panel leaves the reader unable to tell "nothing has happened
     * yet" from "this is broken", and gives no hint of what would put
     * something here. Every list and output surface gets one instead of an
     * empty rectangle.
     */
    fun emptyState(
        context: Context,
        glyph: String,
        title: String,
        hint: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ): View = android.widget.LinearLayout(context).apply {
        orientation = android.widget.LinearLayout.VERTICAL
        gravity = android.view.Gravity.CENTER
        setPadding(dp(context, SpacingXl), dp(context, SpacingXl), dp(context, SpacingXl), dp(context, SpacingXl))
        addView(android.widget.TextView(context).apply {
            text = glyph
            textSize = 26f
            gravity = android.view.Gravity.CENTER
            setTextColor(color(Muted))
        })
        addView(android.widget.TextView(context).apply {
            text = title
            textSize = TypeBody
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setTextColor(color(TextSoft))
            setPadding(0, dp(context, SpacingSm), 0, 0)
        })
        addView(android.widget.TextView(context).apply {
            text = hint
            textSize = TypeCaption
            gravity = android.view.Gravity.CENTER
            setTextColor(color(Muted))
            setPadding(0, dp(context, SpacingXs), 0, 0)
        })
        if (actionLabel != null && onAction != null) {
            addView(ripple(context, rounded(context, fill = Surface2, stroke = Border, radiusDp = RadiusMd)).let {
                android.widget.Button(context).apply {
                    text = actionLabel
                    textSize = TypeSmall
                    setTextColor(color(Blue))
                    background = it
                    minimumHeight = 0
                    setPadding(dp(context, SpacingLg), dp(context, SpacingXs), dp(context, SpacingLg), dp(context, SpacingXs))
                    setOnClickListener { onAction() }
                    enforceMinTouchTarget(this)
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(context, SpacingMd) }
                }
            })
        }
    }

    fun tab(context: Context, text: String, selected: Boolean): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(color(if (selected) Blue else TextSoft))
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setPadding(dp(context, SpacingMd), dp(context, SpacingSm), dp(context, SpacingMd), dp(context, SpacingSm))
            if (selected) {
                background = GradientDrawable().apply {
                    setStroke(dp(context, 1), color(Blue))
                    cornerRadius = dp(context, RadiusMd).toFloat()
                    setColor(color(Surface))
                }
            } else {
                background = null
            }
        }

    fun chip(context: Context, text: String, tint: String = Muted): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setTextColor(color(tint))
            setPadding(dp(context, SpacingSm), dp(context, SpacingXs), dp(context, SpacingSm), dp(context, SpacingXs))
            background = rounded(context, fill = Surface2, stroke = Border, radiusDp = RadiusMd)
        }

    // ── Button fills ──────────────────────────────────────────────────────

    const val ButtonNormal = "#21262D"
    const val ButtonHover = "#30363D"
    const val ButtonBorder = "#6E7681"
    const val ButtonPrimary = "#1F6FEB"
    const val ButtonDanger = "#3A1D1D"
    const val ButtonDangerBorder = "#F85149"
    const val InputBg = "#0D1117"
    const val InputBorder = "#30363D"
    const val InputBorderFocus = Blue
    const val CardBg = "#161B22"
    const val CardBorder = "#30363D"

    // ── Layer separation ──────────────────────────────────────────────────
    const val LayerToolbar = "#161B22"
    const val LayerTabs = "#0D1117"
    const val LayerEditor = "#0D1117"
    const val LayerStatusBar = "#161B22"
    const val LayerBottomPanel = "#0D1117"
    const val LayerPanelTabs = "#161B22"

    // ── Divider / separator ────────────────────────────────────────────
    const val Divider = "#21262D"
    const val DividerLight = "#30363D"

    // ── Active / inactive states ───────────────────────────────────────
    const val ActiveBlue = "#58A6FF"
    const val ActiveGreen = "#3FB950"
    const val ActiveRed = "#F85149"
    const val ActiveAmber = "#D29922"
    const val ActivePurple = "#BC8CFF"

    const val InactiveText = "#484F58"
    const val InactiveIcon = "#484F58"
}

fun TextView.studioTitle(sizeSp: Float = 18f) {
    textSize = sizeSp
    typeface = Typeface.DEFAULT_BOLD
    setTextColor(StudioUi.color(StudioUi.Text))
}

fun TextView.studioMeta(sizeSp: Float = 11f) {
    textSize = sizeSp
    setTextColor(StudioUi.color(StudioUi.TextSoft))
}

fun Button.studioButton(
    fill: String = StudioUi.ButtonNormal,
    text: String = StudioUi.Text,
    accent: String = StudioUi.ButtonBorder,
    radiusDp: Int = StudioUi.RadiusMd
) {
    setAllCaps(false)
    minHeight = 0
    minWidth = 0
    setTextColor(StudioUi.color(text))
    background = StudioUi.ripple(
        context,
        StudioUi.rounded(context, fill = fill, stroke = accent, radiusDp = radiusDp)
    )
    StudioUi.touchLift(this)
}

fun View.studioCard(fill: String = StudioUi.CardBg, stroke: String = StudioUi.CardBorder, radiusDp: Int = StudioUi.RadiusLg) {
    background = StudioUi.rounded(context, fill = fill, stroke = stroke, radiusDp = radiusDp)
}
