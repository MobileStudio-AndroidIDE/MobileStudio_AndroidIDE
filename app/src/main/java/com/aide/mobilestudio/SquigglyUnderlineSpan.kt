package com.aide.mobilestudio

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.style.ReplacementSpan

class SquigglyUnderlineSpan(
    private val underlineColor: Int? = null
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        text ?: return 0
        return paint.measureText(text, start, end).toInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        if (text == null) return
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
        val originalColor = paint.color
        val originalStyle = paint.style
        val originalStroke = paint.strokeWidth
        paint.color = underlineColor ?: StudioUi.palette.error
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        val width = paint.measureText(text, start, end)
        val baseline = y + 4f
        var cursor = x
        var up = true
        val step = 6f
        while (cursor < x + width) {
            val next = (cursor + step).coerceAtMost(x + width)
            canvas.drawLine(cursor, if (up) baseline else baseline + 3f, next, if (up) baseline + 3f else baseline, paint)
            cursor = next
            up = !up
        }
        paint.color = originalColor
        paint.style = originalStyle
        paint.strokeWidth = originalStroke
    }
}
