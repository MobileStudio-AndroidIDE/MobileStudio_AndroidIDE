package com.aide.mobilestudio.godot

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.godotengine.editor.GodotEditor
import org.godotengine.godot.BuildProvider
import org.godotengine.godot.GodotActivity
import java.io.File

class MobileGodotEditorActivity : GodotEditor() {
    private var buildProvider: MobileStudioGodotBuildProvider? = null
    private var overlayRoot: FrameLayout? = null
    private var topBar: LinearLayout? = null
    private var bottomBar: LinearLayout? = null
    private var restoreButton: TextView? = null
    private var fullscreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupOverlay()
    }

    override fun getBuildProvider(): BuildProvider {
        return buildProvider ?: MobileStudioGodotBuildProvider(this, this).also { buildProvider = it }
    }

    override fun onGodotSetupCompleted() {
        super.onGodotSetupCompleted()
        godot?.runOnRenderThread {
            GodotMobileBridge.applyMobileLayout()
            GodotMobileBridge.toggleTitleBar(true)
        }
        runOnUiThread { updateOverlayVisibility() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOverlayVisibility()
        godot?.runOnRenderThread {
            GodotMobileBridge.toggleTitleBar(true)
        }
    }

    override fun onDestroy() {
        buildProvider?.buildEnvDisconnect()
        super.onDestroy()
    }

    private fun setupOverlay() {
        val content = findViewById<ViewGroup>(android.R.id.content) ?: return
        val root = FrameLayout(this).apply {
            isClickable = false
            isFocusable = false
        }
        content.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = barBackground()
            isClickable = true
        }
        top.addView(actionButton("‹") { finish() })
        top.addView(TextView(this).apply {
            text = projectName()
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        })
        top.addView(actionButton("Save") { runBridge(GodotMobileBridge.save(), "Save unavailable") })
        top.addView(actionButton("▶") { runBridge(GodotMobileBridge.playMain(), "Run unavailable") })
        top.addView(actionButton("■") { runBridge(GodotMobileBridge.stop(), "Stop unavailable") })
        top.addView(actionButton("⛶") { toggleFullscreen() })

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = barBackground()
            isClickable = true
        }
        bottom.addView(tabButton("Scene") { focusDock("scene") })
        bottom.addView(tabButton("Inspector") { focusDock("inspector") })
        bottom.addView(tabButton("Files") { focusDock("files") })
        bottom.addView(tabButton("Script") { selectMainScreen(2) })
        bottom.addView(tabButton("2D") { selectMainScreen(0) })
        bottom.addView(tabButton("3D") { selectMainScreen(1) })

        val restore = actionButton("⛶") { toggleFullscreen() }.apply {
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(8)
                rightMargin = dp(8)
            }
        }

        root.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP
        ))
        root.addView(bottom, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        root.addView(restore)

        ViewCompat.setOnApplyWindowInsetsListener(top) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(6), bars.top, dp(6), 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottom) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(6), 0, dp(6), bars.bottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(restore) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val lp = view.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = dp(8) + bars.top
            lp.rightMargin = dp(8) + bars.right
            view.layoutParams = lp
            insets
        }
        ViewCompat.requestApplyInsets(top)
        ViewCompat.requestApplyInsets(bottom)
        ViewCompat.requestApplyInsets(restore)

        overlayRoot = root
        topBar = top
        bottomBar = bottom
        restoreButton = restore
        updateOverlayVisibility()
    }

    private fun updateOverlayVisibility() {
        val root = overlayRoot ?: return
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        root.visibility = if (landscape) View.GONE else View.VISIBLE
        if (landscape) return
        topBar?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        bottomBar?.visibility = if (fullscreen) View.GONE else View.VISIBLE
        restoreButton?.visibility = if (fullscreen) View.VISIBLE else View.GONE
    }

    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        updateOverlayVisibility()
    }

    private fun focusDock(key: String) {
        runBridge(GodotMobileBridge.focusDock(key), "Dock unavailable")
    }

    private fun selectMainScreen(index: Int) {
        runBridge(GodotMobileBridge.selectMainScreen(index), "Main screen unavailable")
    }

    private fun runBridge(result: Boolean, failure: String) {
        if (!result) Toast.makeText(this, failure, Toast.LENGTH_SHORT).show()
    }

    private fun actionButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 12f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(dp(8), 0, dp(8), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
        setOnClickListener { onClick() }
    }

    private fun tabButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 11f
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        setOnClickListener { onClick() }
    }

    private fun barBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(220, 16, 21, 27))
    }

    private fun projectName(): String {
        val args = intent.getStringArrayExtra(GodotActivity.EXTRA_COMMAND_LINE_PARAMS) ?: return "Godot"
        val pathIndex = args.indexOf("--path")
        val path = if (pathIndex >= 0 && pathIndex + 1 < args.size) args[pathIndex + 1] else null
        return path?.let { File(it).name }?.takeIf { it.isNotBlank() } ?: "Godot"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
