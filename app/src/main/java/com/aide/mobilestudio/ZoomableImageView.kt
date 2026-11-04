package com.aide.mobilestudio

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

/**
 * An image that can be pinched to zoom and dragged to pan.
 *
 * A viewer without zoom is close to useless for what this app shows - layout
 * previews, launcher icons, screenshots of a build - all of which carry detail
 * smaller than a phone screen can display at 1:1.
 *
 * [ScaleType.MATRIX] is used so the class owns the transform outright rather
 * than negotiating with ImageView's own fitting, and the scale is clamped on
 * both ends: past roughly the bitmap's native size there are no more pixels to
 * show, only interpolation of them, and below the fitted size the image can be
 * lost off the edge of the view.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ImageView(context, attrs) {

    private val matrix_ = Matrix()
    private val savedMatrix = Matrix()

    private var minScale = 1f
    private var maxScale = 8f

    private val start = PointF()

    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var fitted = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                if (current <= 0f) return true
                val next = (current * detector.scaleFactor).coerceIn(minScale, maxScale)

                // Zoom around the fingers rather than the top-left corner, so
                // the point being pinched stays under them.
                matrix_.postScale(next / current, next / current, detector.focusX, detector.focusY)
                constrain()
                applyMatrix()
                return true
            }
        },
    )

    fun showBitmap(bitmap: android.graphics.Bitmap) {
        bitmapWidth = bitmap.width
        bitmapHeight = bitmap.height
        setImageBitmap(bitmap)
        fitted = false
        applyMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitted = false
        applyMatrix()
    }

    /** Centres the image, and only ever scales it down to fit. */
    private fun fitImage() {
        if (bitmapWidth == 0 || bitmapHeight == 0 || width == 0 || height == 0) return

        // Never upscale on first show: a blurry blow-up of a small icon hides
        // the very detail the user opened it to check.
        val fit = minOf(width.toFloat() / bitmapWidth, height.toFloat() / bitmapHeight, 1f)
        minScale = fit
        maxScale = (fit * 8f).coerceAtLeast(1f)

        matrix_.reset()
        matrix_.setScale(fit, fit)
        matrix_.postTranslate(
            (width - bitmapWidth * fit) / 2f,
            (height - bitmapHeight * fit) / 2f,
        )
        fitted = true
    }

    private fun currentScale(): Float {
        val values = FloatArray(9)
        matrix_.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** Keeps the image on screen and inside the scale limits after a gesture. */
    private fun constrain() {
        val values = FloatArray(9)
        matrix_.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        if (scale > maxScale) {
            matrix_.setScale(maxScale, maxScale, values[Matrix.MTRANS_X], values[Matrix.MTRANS_Y])
        } else if (scale < minScale) {
            matrix_.setScale(minScale, minScale, values[Matrix.MTRANS_X], values[Matrix.MTRANS_Y])
        }

        matrix_.getValues(values)
        val drawnW = bitmapWidth * values[Matrix.MSCALE_X]
        val drawnH = bitmapHeight * values[Matrix.MSCALE_Y]

        // An image larger than the view may be panned until its edge meets the
        // view's; a smaller one stays centred so it cannot be lost off-screen.
        val tx = if (drawnW <= width) {
            (width - drawnW) / 2f
        } else {
            values[Matrix.MTRANS_X].coerceIn(width - drawnW, 0f)
        }
        val ty = if (drawnH <= height) {
            (height - drawnH) / 2f
        } else {
            values[Matrix.MTRANS_Y].coerceIn(height - drawnH, 0f)
        }

        matrix_.postTranslate(tx - values[Matrix.MTRANS_X], ty - values[Matrix.MTRANS_Y])
    }

    private fun applyMatrix() {
        if (!fitted) fitImage()
        scaleType = ScaleType.MATRIX
        imageMatrix = matrix_
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                savedMatrix.set(matrix_)
                start.set(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // While two fingers are down the scale detector owns the
                // gesture; panning then would fight it and jump.
                if (event.pointerCount > 1) return true
                matrix_.set(savedMatrix)
                matrix_.postTranslate(event.x - start.x, event.y - start.y)
                constrain()
                applyMatrix()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                savedMatrix.set(matrix_)
                return true
            }
            else -> return true
        }
    }
}
