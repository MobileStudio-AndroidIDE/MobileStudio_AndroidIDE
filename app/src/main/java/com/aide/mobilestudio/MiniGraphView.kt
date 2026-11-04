package com.aide.mobilestudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

class MiniGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val MAX_POINTS = 30
    }

    var graphColor: Int = Color.parseColor(StudioUi.Blue)

    private val data = FloatArray(MAX_POINTS) { Float.NaN }
    private var count = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePath = Path()
    private val fillPath = Path()

    fun pushValue(value: Float) {
        System.arraycopy(data, 0, data, 1, MAX_POINTS - 1)
        data[MAX_POINTS - 1] = value
        if (count < MAX_POINTS) count++
        invalidate()
    }

    /** Fills the graph with an existing history (view is recreated on each poll). */
    fun seedHistory(values: FloatArray) {
        resetData()
        val start = (values.size - MAX_POINTS).coerceAtLeast(0)
        for (i in start until values.size) {
            val v = values[i]
            if (!v.isNaN()) {
                System.arraycopy(data, 0, data, 1, MAX_POINTS - 1)
                data[MAX_POINTS - 1] = v
                if (count < MAX_POINTS) count++
            }
        }
        invalidate()
    }

    fun resetData() {
        for (i in data.indices) data[i] = Float.NaN
        count = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2 || h < 2 || count < 2) return

        val pad = 2f
        val gw = w - pad * 2
        val gh = h - pad * 2
        val stepX = gw / (MAX_POINTS - 1)

        linePaint.color = graphColor
        linePath.reset()
        fillPath.reset()

        var started = false
        var lastX = 0f
        var lastY = 0f
        var maxVal = 1f
        for (i in 0 until count) {
            val v = data[MAX_POINTS - count + i]
            if (!v.isNaN() && v > maxVal) maxVal = v
        }

        for (i in 0 until count) {
            val v = data[MAX_POINTS - count + i]
            if (v.isNaN()) continue
            val x = pad + i * stepX
            val y = pad + gh * (1f - (v / maxVal))
            if (!started) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, pad + gh)
                fillPath.lineTo(x, y)
                started = true
            } else {
                val cpx = (lastX + x) / 2f
                linePath.cubicTo(cpx, lastY, cpx, y, x, y)
                fillPath.cubicTo(cpx, lastY, cpx, y, x, y)
            }
            lastX = x
            lastY = y
        }
        canvas.drawPath(linePath, linePaint)

        fillPath.lineTo(pad + (count - 1) * stepX, pad + gh)
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, pad, 0f, pad + gh,
            Color.argb(40, Color.red(graphColor), Color.green(graphColor), Color.blue(graphColor)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)
    }
}
