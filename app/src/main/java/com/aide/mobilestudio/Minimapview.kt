package com.aide.mobilestudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 코드 미니?- VSCode ?????측 미니 ?크롤바
 * ?체 코드?축소?서 보여주고 ????당 ?치??동
 */
class MinimapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bgPaint = Paint().apply { color = StudioUi.palette.bg }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = StudioUi.palette.divider; strokeWidth = 1f
    }
    private val viewportPaint = Paint().apply {
        color = StudioUi.palette.accent; alpha = 40
    }
    private val viewportBorderPaint = Paint().apply {
        color = StudioUi.palette.accent; alpha = 100
        style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val errorPaint  = Paint().apply { color = StudioUi.palette.error; strokeWidth = 2f }
    private val warnPaint   = Paint().apply { color = StudioUi.palette.warning; strokeWidth = 2f }

    var lineCount: Int = 1
        set(v) { field = v.coerceAtLeast(1); invalidate() }
    var viewportStart: Float = 0f  // 0..1
        set(v) { field = v; invalidate() }
    var viewportEnd: Float   = 1f
        set(v) { field = v; invalidate() }
    var errorLines   = listOf<Int>()
        set(v) { field = v; invalidate() }
    var warningLines = listOf<Int>()
        set(v) { field = v; invalidate() }

    private var onScrollFraction: ((Float) -> Unit)? = null

    fun setOnScrollListener(l: (Float) -> Unit) { onScrollFraction = l }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Viewport highlight
        val vpTop = viewportStart * h
        val vpBot = viewportEnd   * h
        canvas.drawRect(0f, vpTop, w, vpBot, viewportPaint)
        canvas.drawRect(0f, vpTop, w, vpBot, viewportBorderPaint)

        // Error/warning lines
        errorLines.forEach { line ->
            val y = (line.toFloat() / lineCount) * h
            canvas.drawLine(0f, y, w, y, errorPaint)
        }
        warningLines.forEach { line ->
            val y = (line.toFloat() / lineCount) * h
            canvas.drawLine(0f, y, w, y, warnPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN ||
            event.action == MotionEvent.ACTION_MOVE) {
            onScrollFraction?.invoke(event.y / height)
        }
        return true
    }
}