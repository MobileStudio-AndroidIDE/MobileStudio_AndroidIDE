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
import kotlinx.coroutines.withContext
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
        private lateinit var projectDir: File
        private lateinit var statusText: TextView
        private lateinit var stepsContainer: LinearLayout
        private lateinit var progress: ProgressBar
        private var scan: AgpUpgradeAnalyzer.ProjectScanResult? = null
        private var plan: AgpUpgradePlan? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            title = "AGP Upgrade Assistant"
            projectDir = File(intent.getStringExtra("project_dir") ?: ".")
            val scroll = ScrollView(this)
            val root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            statusText = TextView(this).apply {
                textSize = 15f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(StudioUi.color(StudioUi.Text))
                setPadding(0, 0, 0, dp(12))
            }
            progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(-1, dp(8))
            }
            stepsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val buttonRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(16), 0, 0)
            }
            val analyzeBtn = Button(this).apply { text = "Analyze" }
            val upgradeBtn = Button(this).apply { text = "Run upgrade"; isEnabled = false }
            val rollbackBtn = Button(this).apply { text = "Rollback all"; isEnabled = false }
            val closeBtn = Button(this).apply { text = "Close" }
            buttonRow.addView(analyzeBtn)
            buttonRow.addView(upgradeBtn)
            buttonRow.addView(rollbackBtn)
            buttonRow.addView(closeBtn)
            root.addView(statusText)
            root.addView(progress)
            root.addView(stepsContainer)
            root.addView(buttonRow)
            scroll.addView(root)
            setContentView(scroll)

            analyzeBtn.setOnClickListener { runAnalysis(upgradeBtn) }
            upgradeBtn.setOnClickListener { executeUpgrade(rollbackBtn) }
            rollbackBtn.setOnClickListener { rollback() }
            closeBtn.setOnClickListener { finish() }
            runAnalysis(upgradeBtn)
        }

        private fun runAnalysis(upgradeBtn: Button) {
            statusText.text = "Analyzing ${projectDir.name}…"
            progress.visibility = View.VISIBLE
            scope.launch(Dispatchers.IO) {
                val result = runCatching { AgpUpgradeAnalyzer(projectDir).scanProject() }.getOrNull()
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    scan = result
                    stepsContainer.removeAllViews()
                    if (result == null) {
                        statusText.text = "Analysis failed for ${projectDir.absolutePath}"
                        upgradeBtn.isEnabled = false
                        return@withContext
                    }
                    statusText.text = "Current: AGP ${result.agpVersion} · Gradle ${result.gradleVersion} · Kotlin ${result.kotlinVersion} · JDK ${result.jdkVersion}"
                    val target = "8.13.0"
                    val issues = AgpCompatibilityMatrix.checkCompatibility(
                        result.agpVersion, target, result.gradleVersion,
                        result.jdkVersion, result.kotlinVersion, result.ndkVersion, result.cmakeVersion,
                    )
                    val summary = TextView(this@AgpUpgradeToolWindowActivity).apply {
                        text = "Target AGP $target · ${issues.size} compatibility issue(s) · ${result.deprecatedApiUsages.size} deprecated API usage(s)"
                        textSize = 13f
                        setTextColor(StudioUi.color(StudioUi.TextSoft))
                        setPadding(0, 0, 0, dp(12))
                    }
                    stepsContainer.addView(summary)
                    issues.take(20).forEach { issue ->
                        stepsContainer.addView(TextView(this@AgpUpgradeToolWindowActivity).apply {
                            text = "• ${issue.severity}: ${issue.message}"
                            textSize = 12f
                            setTextColor(if (issue.severity == AgpCompatibilityMatrix.Severity.ERROR) StudioUi.color(StudioUi.Red) else StudioUi.color(StudioUi.Amber))
                            setPadding(0, 0, 0, dp(6))
                        })
                    }
                    upgradeBtn.isEnabled = true
                }
            }
        }

        private fun executeUpgrade(rollbackBtn: Button) {
            val result = scan ?: return
            upgradePlan(result, rollbackBtn)
        }

        private fun upgradePlan(result: AgpUpgradeAnalyzer.ProjectScanResult, rollbackBtn: Button) {
            progress.visibility = View.VISIBLE
            statusText.text = "Applying upgrade plan…"
            scope.launch(Dispatchers.IO) {
                val upgradePlan = AgpUpgradePlan(projectDir, result, "8.13.0", "gradle-8.14", "2.0.20")
                plan = upgradePlan
                val stepViews = mutableListOf<TextView>()
                withContext(Dispatchers.Main) {
                    stepsContainer.removeAllViews()
                    upgradePlan.getSteps().forEach { step ->
                        val tv = TextView(this@AgpUpgradeToolWindowActivity).apply {
                            text = "○ ${step.name}: ${step.description}"
                            textSize = 12f
                            setTextColor(StudioUi.color(StudioUi.TextSoft))
                            setPadding(0, 0, 0, dp(6))
                        }
                        stepViews.add(tv)
                        stepsContainer.addView(tv)
                    }
                }
                val outcome = upgradePlan.executeAll { step, stepResult ->
                    runOnUiThread {
                        val idx = upgradePlan.getSteps().indexOfFirst { it.id == step.id }
                        if (idx in stepViews.indices) {
                            stepViews[idx].text = "${if (stepResult.success) "✓" else "✗"} ${step.name}: ${stepResult.message.take(100)}"
                            stepViews[idx].setTextColor(StudioUi.color(if (stepResult.success) StudioUi.Green else StudioUi.Red))
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    statusText.text = if (outcome.success) "Upgrade complete (${outcome.completedSteps.size}/${outcome.totalSteps} steps)" else "Upgrade stopped: ${outcome.message}"
                    rollbackBtn.isEnabled = outcome.completedSteps.isNotEmpty()
                }
            }
        }

        private fun rollback() {
            val p = plan ?: return
            progress.visibility = View.VISIBLE
            statusText.text = "Rolling back…"
            scope.launch(Dispatchers.IO) {
                p.rollbackAll { _, _ -> }
                withContext(Dispatchers.Main) {
                    progress.visibility = View.GONE
                    statusText.text = "Rollback finished"
                }
            }
        }
    }
}