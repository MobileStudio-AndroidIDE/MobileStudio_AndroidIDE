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

class RealtimeGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    companion object {
        const val MAX_POINTS = 60
        const val GRID_LINES = 4
    }

    var graphColor: Int = Color.parseColor(StudioUi.Blue)
    var secondaryColor: Int = Color.parseColor(StudioUi.Green)
    var showSecondary: Boolean = false
    var yMax: Float = 100f
    var yLabel: String = "%"
    var title: String = ""

    private val primaryData = FloatArray(MAX_POINTS) { Float.NaN }
    private val secondaryData = FloatArray(MAX_POINTS) { Float.NaN }
    private var primaryCount = 0
    private var secondaryCount = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.5f
        color = StudioUi.color(StudioUi.Border)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = StudioUi.color(StudioUi.Text)
        textSize = 28f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val gridTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = StudioUi.color(StudioUi.TextSoft)
        textSize = 20f
    }

    private val linePath = Path()
    private val fillPath = Path()

    fun pushPrimary(value: Float) {
        System.arraycopy(primaryData, 0, primaryData, 1, MAX_POINTS - 1)
        primaryData[MAX_POINTS - 1] = value
        if (primaryCount < MAX_POINTS) primaryCount++
        invalidate()
    }

    fun pushSecondary(value: Float) {
        System.arraycopy(secondaryData, 0, secondaryData, 1, MAX_POINTS - 1)
        secondaryData[MAX_POINTS - 1] = value
        if (secondaryCount < MAX_POINTS) secondaryCount++
        if (showSecondary) invalidate()
    }

    fun resetData() {
        for (i in primaryData.indices) primaryData[i] = Float.NaN
        for (i in secondaryData.indices) secondaryData[i] = Float.NaN
        primaryCount = 0
        secondaryCount = 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2 || h < 2) return

        val padLeft = 56f
        val padRight = 12f
        val padTop = if (title.isNotEmpty()) 38f else 8f
        val padBottom = 8f
        val gw = w - padLeft - padRight
        val gh = h - padTop - padBottom

        if (title.isNotEmpty()) {
            canvas.drawText(title, padLeft, padTop - 12f, titlePaint)
        }

        for (i in 0..GRID_LINES) {
            val y = padTop + gh * i / GRID_LINES
            canvas.drawLine(padLeft, y, w - padRight, y, gridPaint)
            val labelVal = yMax * (GRID_LINES - i) / GRID_LINES
            val label = if (yMax <= 10) String.format("%.1f", labelVal)
                        else String.format("%.0f", labelVal)
            canvas.drawText(label + yLabel, 2f, y + 8f, gridTextPaint)
        }

        if (primaryCount < 2) return

        val stepX = gw / (MAX_POINTS - 1)

        linePaint.color = graphColor
        linePaint.shader = null
        linePath.reset()
        fillPath.reset()

        var started = false
        var lastX = 0f
        var lastY = 0f

        for (i in 0 until primaryCount) {
            val v = primaryData[MAX_POINTS - primaryCount + i]
            if (v.isNaN()) continue
            val x = padLeft + i * stepX
            val y = padTop + gh * (1f - (v.coerceIn(0f, yMax) / yMax))
            if (!started) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, padTop + gh)
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

        fillPath.lineTo(padLeft + (primaryCount - 1) * stepX, padTop + gh)
        fillPath.close()
        fillPaint.shader = LinearGradient(
            0f, padTop, 0f, padTop + gh,
            Color.argb(50, Color.red(graphColor), Color.green(graphColor), Color.blue(graphColor)),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(fillPath, fillPaint)

        if (showSecondary && secondaryCount >= 2) {
            linePaint.color = secondaryColor
            linePaint.shader = null
            linePath.reset()
            started = false
            for (i in 0 until secondaryCount) {
                val v = secondaryData[MAX_POINTS - secondaryCount + i]
                if (v.isNaN()) continue
                val x = padLeft + i * stepX
                val y = padTop + gh * (1f - (v.coerceIn(0f, yMax) / yMax))
                if (!started) { linePath.moveTo(x, y); started = true }
                else {
                    val cpx = (lastX + x) / 2f
                    linePath.cubicTo(cpx, lastY, cpx, y, x, y)
                }
                lastX = x
                lastY = y
            }
            linePaint.strokeWidth = 2f
            canvas.drawPath(linePath, linePaint)
            linePaint.strokeWidth = 2.5f
        }
    }
}
