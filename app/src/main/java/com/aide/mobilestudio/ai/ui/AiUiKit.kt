package com.aide.mobilestudio.ai.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aide.mobilestudio.StudioUi

/**
 * Small view toolkit for the AI workspace. Centralises shape/colour/spacing so
 * every screen is visually consistent and themable through [StudioUi.palette].
 */
object AiUiKit {

    fun Context.dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    fun rounded(color: Int, radiusPx: Int, strokeColor: Int? = null, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(color)
            if (strokeColor != null && strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun rippleContent(context: Context, color: Int, radiusPx: Int, strokeColor: Int? = null): RippleDrawable {
        val content = rounded(color, radiusPx, strokeColor, if (strokeColor != null) context.dp(1) else 0)
        return RippleDrawable(ColorStateList.valueOf(withAlpha(StudioUi.palette.text, 0.12f)), content, null)
    }

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    fun card(context: Context, radiusDp: Int = 12, color: Int = StudioUi.palette.surface, elevated: Boolean = false): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(
                color,
                context.dp(radiusDp),
                strokeColor = if (elevated) StudioUi.palette.border else null,
            )
            clipToPadding = false
        }

    fun circle(context: Context, label: String, bg: Int, fg: Int, sizeDp: Int = 28): TextView =
        TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(fg)
            textSize = StudioUi.TypeSmall
            setTypeface(null, Typeface.BOLD)
            background = rounded(bg, context.dp(sizeDp / 2))
            layoutParams = LinearLayout.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
        }

    fun pill(context: Context, label: String, bg: Int, fg: Int): TextView =
        TextView(context).apply {
            text = label
            setTextColor(fg)
            textSize = StudioUi.TypeSmall
            setTypeface(null, Typeface.BOLD)
            background = rounded(bg, context.dp(999))
            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
        }

    fun primaryButton(context: Context, label: String, onClick: (View) -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(StudioUi.palette.onAccent)
            background = rippleContent(context, StudioUi.palette.accent, context.dp(10))
            setOnClickListener(onClick)
        }

    /**
     * An icon-only button with a real target behind it.
     *
     * Header and composer icons were bare ImageButtons with a null background:
     * no press feedback at all, and a 24dp glyph is well under what a thumb is
     * aiming at, so which part of the row was even pressable was anyone's
     * guess. The ripple is kept at the icon's own size so it reads as a circle
     * around the glyph rather than a slab across the row, while the touch floor
     * grows the hit area around it.
     */
    fun iconButton(context: Context, resId: Int, tint: Int = StudioUi.palette.muted, onClick: (View) -> Unit): ImageButton =
        ImageButton(context).apply {
            setImageResource(resId)
            scaleType = ImageView.ScaleType.CENTER
            background = rippleContent(context, StudioUi.palette.surface2, context.dp(18))
            setColorFilter(tint)
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            setOnClickListener(onClick)
            StudioUi.enforceMinTouchTarget(this)
        }

    fun ghostButton(context: Context, label: String, onClick: (View) -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(StudioUi.palette.accent)
            background = rippleContent(context, StudioUi.palette.surface2, context.dp(10))
            setOnClickListener(onClick)
        }

    fun actionChip(context: Context, label: String, onClick: (View) -> Unit): TextView =
        TextView(context).apply {
            text = label
            setTextColor(StudioUi.palette.textSoft)
            textSize = StudioUi.TypeSmall
            background = rippleContent(context, StudioUi.palette.surface2, context.dp(8))
            setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5))
            setOnClickListener(onClick)
        }

    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(StudioUi.palette.border)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1))
    }

    fun sectionHeader(context: Context, title: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, context.dp(14), 0, context.dp(6))
        addView(TextView(context).apply {
            text = title.uppercase()
            setTextColor(StudioUi.palette.textSoft)
            textSize = StudioUi.TypeSmall
            letterSpacing = 0.08f
            setTypeface(null, Typeface.BOLD)
        })
    }

    fun body(context: Context, text: String, selectable: Boolean = true): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(StudioUi.palette.textSoft)
            textSize = StudioUi.TypeBody
            setLineSpacing(context.dp(3).toFloat(), 1.05f)
            setTextIsSelectable(selectable)
        }

    fun emptyState(context: Context, emoji: String, message: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(context.dp(24), context.dp(48), context.dp(24), context.dp(48))
            addView(TextView(context).apply {
                text = emoji
                textSize = 34f
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = message
                setTextColor(StudioUi.palette.muted)
                textSize = StudioUi.TypeBody
                gravity = Gravity.CENTER
                setPadding(0, context.dp(10), 0, 0)
            })
        }
}
