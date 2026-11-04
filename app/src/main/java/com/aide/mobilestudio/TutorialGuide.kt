package com.aide.mobilestudio

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class TutorialGuide(private val context: Context) {

    enum class StepType { INFO, INTERACTIVE, WAIT }

    data class Step(
        val targetView: () -> View?,
        val title: String,
        val description: String,
        val position: TooltipPosition = TooltipPosition.BELOW,
        val type: StepType = StepType.INTERACTIVE,
        val autoAdvanceOnClick: Boolean = true,
        val onAdvance: (() -> Unit)? = null
    )

    enum class TooltipPosition { ABOVE, BELOW, LEFT, RIGHT }

    private var overlay: FrameLayout? = null
    private var currentStepIndex = 0
    private var steps: List<Step> = emptyList()
    private var onComplete: (() -> Unit)? = null
    private var onSkip: (() -> Unit)? = null
    private var isShowing = false
    private var pulseAnimator: AnimatorSet? = null
    private var dimView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun show(
        steps: List<Step>,
        startIndex: Int = 0,
        onComplete: (() -> Unit)? = null,
        onSkip: (() -> Unit)? = null
    ) {
        if (isShowing) return
        this.steps = steps
        this.currentStepIndex = startIndex
        this.onComplete = onComplete
        this.onSkip = onSkip
        isShowing = true

        val rootView = (context as? android.app.Activity)?.window?.decorView
            ?: return
        val activityRoot = rootView.findViewById<ViewGroup>(android.R.id.content) ?: return

        overlay = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }

        activityRoot.addView(overlay, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))

        showStep(currentStepIndex)
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) {
            dismiss()
            onComplete?.invoke()
            return
        }

        val step = steps[index]
        val target = step.targetView() ?: run {
            dismiss()
            return
        }

        overlay?.removeAllViews()
        pulseAnimator?.cancel()

    // Calculate target screen rect
        val location = IntArray(2)
        target.getLocationOnScreen(location)
        val decorView = (context as? android.app.Activity)?.window?.decorView ?: return
        val contentView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        val contentLocation = IntArray(2)
        contentView.getLocationOnScreen(contentLocation)

        val targetRect = Rect(
            location[0] - contentLocation[0] - dp(8),
            location[1] - contentLocation[1] - dp(8),
            location[0] - contentLocation[0] + target.width + dp(8),
            location[1] - contentLocation[1] + target.height + dp(8)
        )

    // Dim overlay with cutout
        dimView = View(context).apply {
            setBackgroundColor(Color.argb(0x99, 0, 0, 0))
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val x = event.x.toInt()
                    val y = event.y.toInt()
                    if (targetRect.contains(x, y) && step.autoAdvanceOnClick) {
                        step.onAdvance?.invoke()
                        target.performClick()
                        mainHandler.post { showStep(index + 1) }
                        return@setOnTouchListener true
                    }
                }
                true
            }
        }
        overlay?.addView(dimView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        // Ensure target is clickable through the overlay by placing a transparent pass-through view
        val passThrough = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                targetRect.width(),
                targetRect.height()
            ).apply {
                leftMargin = targetRect.left
                topMargin = targetRect.top
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    if (step.type == StepType.INTERACTIVE || step.type == StepType.INFO) {
                        step.onAdvance?.invoke()
                        target.performClick()
                        if (step.autoAdvanceOnClick) {
                            mainHandler.post { showStep(index + 1) }
                        }
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }
        overlay?.addView(passThrough)

    // Green highlight border with pulse
        val pulseView = View(context).apply {
            background = GradientDrawable().apply {
                setShape(GradientDrawable.RECTANGLE)
                setStroke(dp(4), StudioUi.color(StudioUi.Green))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = FrameLayout.LayoutParams(
                targetRect.width(),
                targetRect.height()
            ).apply {
                leftMargin = targetRect.left
                topMargin = targetRect.top
            }
        }
        overlay?.addView(pulseView)

        pulseAnimator = AnimatorSet().apply {
            val scaleUp = ObjectAnimator.ofFloat(pulseView, "scaleX", 1f, 1.10f)
            scaleUp.repeatCount = ValueAnimator.INFINITE
            scaleUp.repeatMode = ValueAnimator.REVERSE
            val scaleUpY = ObjectAnimator.ofFloat(pulseView, "scaleY", 1f, 1.10f)
            scaleUpY.repeatCount = ValueAnimator.INFINITE
            scaleUpY.repeatMode = ValueAnimator.REVERSE
            val alphaDown = ObjectAnimator.ofFloat(pulseView, "alpha", 1f, 0.3f)
            alphaDown.repeatCount = ValueAnimator.INFINITE
            alphaDown.repeatMode = ValueAnimator.REVERSE
            playTogether(scaleUp, scaleUpY, alphaDown)
            interpolator = AccelerateDecelerateInterpolator()
            duration = 800
            start()
        }

    // Tooltip card
        val tooltip = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = GradientDrawable().apply {
                setColor(StudioUi.color(StudioUi.Surface2))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), StudioUi.color(StudioUi.TextSoft))
            }
            elevation = dp(8).toFloat()
        }

        // Step indicator + skip button row
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        topRow.addView(TextView(context).apply {
            text = "Step ${index + 1} / ${steps.size}"
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(context).apply {
            text = "Skip"
            textSize = 11f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                dismiss()
                onSkip?.invoke()
            }
        })
        tooltip.addView(topRow)

        val titleView = TextView(context).apply {
            text = step.title
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(6), 0, dp(4))
        }
        tooltip.addView(titleView)

        val descView = TextView(context).apply {
            text = step.description
            textSize = 13f
            setTextColor(StudioUi.color(StudioUi.Text))
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        tooltip.addView(descView)

        // Button row
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(12), 0, 0)
        }

        if (index > 0) {
            btnRow.addView(Button(context).apply {
                text = "Back"
                textSize = 12f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    showStep(index - 1)
                }
            })
        }

        if (step.type == StepType.INFO) {
            btnRow.addView(Button(context).apply {
                text = if (index < steps.size - 1) "Next \u2192" else "Done \u2714"
                textSize = 12f
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Green))
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener {
                    step.onAdvance?.invoke()
                    showStep(index + 1)
                }
            })
        }

        tooltip.addView(btnRow)

        // Position the tooltip
        val tooltipParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )

        val tooltipWidth = (context.resources.displayMetrics.widthPixels * 0.85).toInt()

        when (step.position) {
            TooltipPosition.BELOW -> {
                tooltipParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                tooltipParams.topMargin = targetRect.bottom + dp(12)
                tooltipParams.width = tooltipWidth
            }
            TooltipPosition.ABOVE -> {
                tooltipParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                tooltipParams.bottomMargin = (decorView.height - targetRect.top) + dp(12)
                tooltipParams.width = tooltipWidth
            }
            TooltipPosition.LEFT -> {
                tooltipParams.gravity = Gravity.TOP or Gravity.LEFT
                tooltipParams.topMargin = targetRect.centerY() - dp(40)
                tooltipParams.leftMargin = dp(12)
                tooltipParams.width = tooltipWidth / 2
            }
            TooltipPosition.RIGHT -> {
                tooltipParams.gravity = Gravity.TOP or Gravity.RIGHT
                tooltipParams.topMargin = targetRect.centerY() - dp(40)
                tooltipParams.rightMargin = dp(12)
                tooltipParams.width = tooltipWidth / 2
            }
        }

        overlay?.addView(tooltip, tooltipParams)
    }

    fun advanceToNext() {
        mainHandler.post { showStep(currentStepIndex + 1) }
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        pulseAnimator?.cancel()
        dimView = null
        (context as? android.app.Activity)?.let { activity ->
            val content = activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)
            overlay?.let { content?.removeView(it) }
        }
        overlay = null
    }

    val isActive: Boolean get() = isShowing
    val currentStep: Int get() = currentStepIndex

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
