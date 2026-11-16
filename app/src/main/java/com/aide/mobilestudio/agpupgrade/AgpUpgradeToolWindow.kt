package com.aide.mobilestudio.agpupgrade

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.aide.mobilestudio.DiffViewerActivity
import com.aide.mobilestudio.StudioUi
import com.aide.mobilestudio.ToolchainSyncManager
import com.aide.mobilestudio.agpupgrade.AgpCompatibilityMatrix
import com.aide.mobilestudio.agpupgrade.WizardStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AGP Upgrade Tool Window - Android Studio style upgrade workflow UI.
 */
class AgpUpgradeToolWindow(
    private val host: Activity,
    private val projectDir: File,
    private val onClose: () -> Unit,
) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentStep = WizardStep.ANALYZE
    private var scanResult: AgpUpgradeAnalyzer.ProjectScanResult? = null
    private var selectedTargetAgp = "8.14.0"
    private var selectedTargetGradle = "gradle-8.14"
    private var selectedTargetKotlin = "2.0.20"
    private var compatibilityIssues: List<AgpCompatibilityMatrix.CompatibilityIssue> = emptyList()

    private fun dp(n: Int): Int = (n * host.resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        android.widget.Toast.makeText(host, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // Stub helper functions
    private fun buildSectionTitle(title: String): TextView {
        return TextView(host).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(StudioUi.color(StudioUi.Text))
        }
    }

    private fun buildDescription(text: String): TextView {
        return TextView(host).apply {
            this.text = text
            textSize = 13f
            setTextColor(StudioUi.color(StudioUi.TextSoft))
        }
    }

    private fun buildProgressBar(): ProgressBar {
        return ProgressBar(host)
    }

    private fun buildVersionCard(title: String, items: Map<String, String>): CardView {
        return CardView(host).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun buildIssuesCard(title: String, issues: List<AgpCompatibilityMatrix.CompatibilityIssue>): CardView {
        return CardView(host).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun buildInfoCard(title: String, items: Map<String, String>): CardView {
        return CardView(host).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun buildInfoRow(label: String, value: String): LinearLayout {
        return LinearLayout(host)
    }

    private fun buildSecondaryButton(text: String, onClick: () -> Unit): Button {
        return Button(host).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
    }

    private fun buildPrimaryButton(text: String, onClick: () -> Unit): Button {
        return Button(host).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
    }

    private fun updateActionBarForStep(step: WizardStep) {
        // Stub implementation
    }

    companion object {
        fun show(host: Activity, projectDir: File, onClose: () -> Unit) {
            val intent = Intent(host, AgpUpgradeToolWindowActivity::class.java)
            intent.putExtra("project_dir", projectDir.absolutePath)
            host.startActivity(intent)
        }
    }

    /** Activity that hosts the AGP Upgrade Tool Window. */
    class AgpUpgradeToolWindowActivity : android.app.Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val projectDir = File(intent.getStringExtra("project_dir") ?: ".")
            val tv = android.widget.TextView(this)
            tv.text = "AGP Upgrade Tool Window\n\nProject: ${projectDir.absolutePath}\n\nComing soon - full AGP Upgrade Assistant UI."
            tv.textSize = 18f
            tv.setPadding(24, 24, 24, 24)
            setContentView(tv)
        }
    }
}