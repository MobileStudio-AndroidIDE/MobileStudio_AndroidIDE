package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aide.mobilestudio.CompatibilityChecker.RecommendedSet
import com.aide.mobilestudio.PermissionNotifier
import java.io.File

class ProjectSettingsActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PROJECT_PATH = "project_path"

        fun start(context: Context, projectPath: String) {
            val intent = Intent(context, ProjectSettingsActivity::class.java).apply {
                putExtra(EXTRA_PROJECT_PATH, projectPath)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var projectDir: File
    private lateinit var toolchain: ToolchainManager
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PROJECT_PATH) ?: return finish()
        projectDir = File(path)
        toolchain = ToolchainManager(this)

        setContentView(buildLayout())
        render()
    }

    private fun buildLayout(): View {
        return ScrollView(this).apply {
            setBackgroundColor(StudioUi.color(StudioUi.InputBg))
            container = LinearLayout(this@ProjectSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)
            }
            addView(container)
        }
    }

    private fun render() {
        container.removeAllViews()

        header("Project Settings: ${projectDir.name}")

        section("Build Tools Versions")
        
        versionRow(ToolchainManager.Kind.JDK)
        versionRow(ToolchainManager.Kind.GRADLE)
        versionRow(ToolchainManager.Kind.SDK)
        versionRow(ToolchainManager.Kind.NDK)
        versionRow(ToolchainManager.Kind.CMAKE)

        gap()

        section("Shell & Notifications")
        
        val vibrationEnabled = PermissionNotifier.isVibrationEnabled(this)
        val vibrationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }
        val vibLabel = TextView(this).apply {
            text = "Permission Request Vibration"
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val vibSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = vibrationEnabled
            setOnCheckedChangeListener { _, checked ->
                PermissionNotifier.setVibrationEnabled(this@ProjectSettingsActivity, checked)
            }
        }
        vibrationRow.addView(vibLabel)
        vibrationRow.addView(vibSwitch)
        container.addView(vibrationRow)

        gap()

        val btnRecommended = Button(this).apply {
            text = "Update all to Recommended Versions"
            studioButton(StudioUi.ButtonPrimary, StudioUi.ActiveBlue)
            setOnClickListener { confirmUpdateToRecommended() }
        }
        container.addView(btnRecommended)
        
        gap()
        
        val btnSync = Button(this).apply {
            text = "Sync build files now"
            studioButton(StudioUi.ButtonNormal, StudioUi.TextSoft)
            setOnClickListener { 
                syncProjectBuildFiles()
                Toast.makeText(this@ProjectSettingsActivity, "Sync complete", Toast.LENGTH_SHORT).show()
            }
        }
        container.addView(btnSync)
    }

    private fun versionRow(kind: ToolchainManager.Kind) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 10, 0, 10)
        }

        val label = TextView(this).apply {
            text = kind.displayName
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val current = toolchain.activeVersion(kind) ?: "Not set"
        val value = TextView(this).apply {
            text = current
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(20, 0, 20, 0)
        }

        val btnChange = Button(this).apply {
            text = "Change"
            studioButton(StudioUi.ButtonNormal, StudioUi.Text)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 40.dp())
            setOnClickListener { showVersionPicker(kind) }
        }

        row.addView(label)
        row.addView(value)
        row.addView(btnChange)
        container.addView(row)
    }

    private fun showVersionPicker(kind: ToolchainManager.Kind) {
        val versions = toolchain.availableVersions(kind)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Select ${kind.displayName}")
            .setItems(versions.toTypedArray()) { _, which ->
                val selected = versions[which]
                if (toolchain.setActiveVersion(kind, selected)) {
                    render()
                    checkCompatibility(kind, selected)
                } else {
                    Toast.makeText(this, "$selected is not installed", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .create()
            
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(StudioUi.color(StudioUi.TextSoft))
        }
        dialog.show()
    }

    private fun checkCompatibility(kind: ToolchainManager.Kind, version: String) {
        val recommended = CompatibilityChecker.getRecommendedSet(kind, version)
        val currentJdk = toolchain.activeVersion(ToolchainManager.Kind.JDK)
        val currentGradle = toolchain.activeVersion(ToolchainManager.Kind.GRADLE)
        val currentNdk = toolchain.activeVersion(ToolchainManager.Kind.NDK)
        
        val updates = mutableListOf<String>()
        if (recommended.jdk != currentJdk) updates.add("JDK: ${recommended.jdk}")
        if (recommended.gradle != currentGradle) updates.add("Gradle: ${recommended.gradle}")
        if (recommended.ndk != currentNdk) updates.add("NDK: ${recommended.ndk}")

        if (updates.isNotEmpty()) {
            val dialog = AlertDialog.Builder(this)
                .setTitle("Recommended Updates")
                .setMessage("Based on your change to $version, we recommend these versions for best compatibility:\n\n" + 
                    updates.joinToString("\n") + "\n\nApply these changes now?")
                .setPositiveButton("Apply All", null)
                .setNegativeButton("Keep Current", null)
                .create()
                
            dialog.setOnShowListener {
                val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val cancelBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                okBtn.setTextColor(StudioUi.color(StudioUi.ActiveBlue))
                cancelBtn.setTextColor(StudioUi.color(StudioUi.TextSoft))
                
                okBtn.setOnClickListener {
                    val progress = AlertDialog.Builder(this@ProjectSettingsActivity)
                        .setMessage("Syncing project files...")
                        .setCancelable(false)
                        .show()

                    Thread {
                        toolchain.setActiveVersion(ToolchainManager.Kind.JDK, recommended.jdk)
                        toolchain.setActiveVersion(ToolchainManager.Kind.GRADLE, recommended.gradle)
                        toolchain.setActiveVersion(ToolchainManager.Kind.NDK, recommended.ndk)
                        toolchain.setActiveVersion(ToolchainManager.Kind.CMAKE, recommended.cmake)
                        toolchain.setActiveVersion(ToolchainManager.Kind.SDK, recommended.sdk)
                        
                        // Use recommended set directly for sync
                        ToolchainSyncManager.syncAllVersions(projectDir, recommended)

                        runOnUiThread { 
                            progress.dismiss()
                            render()
                            dialog.dismiss() 
                        }
                    }.start()
                }
            }
            dialog.show()
        }
    }

    private fun confirmUpdateToRecommended() {
        val recommended = CompatibilityChecker.getLatestSet()
        val currentGradle = toolchain.activeVersion(ToolchainManager.Kind.GRADLE)
        
        val dialog = AlertDialog.Builder(this)
            .setTitle("Update to Recommended Stack")
            .setMessage("This will update all build tools to the latest recommended stable versions:\n\n" +
                "JDK: ${recommended.jdk}\n" +
                "Gradle: ${recommended.gradle}\n" +
                "SDK: ${recommended.sdk}\n" +
                "NDK: ${recommended.ndk}\n" +
                "CMake: ${recommended.cmake}\n\n" +
                "Confirm update?")
            .setPositiveButton("Update", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val okBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val cancelBtn = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            
            okBtn.setTextColor(StudioUi.color(StudioUi.ActiveBlue))
            cancelBtn.setTextColor(StudioUi.color(StudioUi.TextSoft))

            okBtn.setOnClickListener {
                val results = mutableListOf<String>()
                
                fun trySet(kind: ToolchainManager.Kind, ver: String) {
                    if (!toolchain.setActiveVersion(kind, ver)) {
                        results.add("${kind.displayName}: $ver (not installed)")
                    }
                }

                trySet(ToolchainManager.Kind.JDK, recommended.jdk)
                trySet(ToolchainManager.Kind.GRADLE, recommended.gradle)
                trySet(ToolchainManager.Kind.NDK, recommended.ndk)
                trySet(ToolchainManager.Kind.CMAKE, recommended.cmake)
                trySet(ToolchainManager.Kind.SDK, recommended.sdk)
                
                val progress = AlertDialog.Builder(this@ProjectSettingsActivity)
                    .setMessage("Syncing project files...")
                    .setCancelable(false)
                    .show()

                Thread {
                    // Sync build files to the recommended versions even if not fully active on device yet
                    ToolchainSyncManager.syncAllVersions(projectDir, recommended)
                    runOnUiThread { 
                        progress.dismiss()
                        render()
                        dialog.dismiss()
                        if (results.isNotEmpty()) {
                            AlertDialog.Builder(this@ProjectSettingsActivity)
                                .setTitle("Update Partial")
                                .setMessage("Project build files updated to the recommended stack, but some tools could not be activated on this device because they are not installed yet:\n\n" +
                                    results.joinToString("\n") + "\n\nPlease go to Build Tools to download them.")
                                .setPositiveButton("OK", null)
                                .show()
                        } else {
                            Toast.makeText(this@ProjectSettingsActivity, "Project tools updated successfully", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun syncProjectBuildFiles() {
        val jdk = toolchain.activeVersion(ToolchainManager.Kind.JDK) ?: "jdk-21"
        val gradle = toolchain.activeVersion(ToolchainManager.Kind.GRADLE) ?: "gradle-9.6.1"
        val sdk = toolchain.activeVersion(ToolchainManager.Kind.SDK) ?: "android-34"
        val ndk = toolchain.activeVersion(ToolchainManager.Kind.NDK) ?: "27.0.12077973"
        val cmake = toolchain.activeVersion(ToolchainManager.Kind.CMAKE) ?: "3.22.1"
        
        val recommended = RecommendedSet(jdk, gradle, sdk, ndk, cmake)
        ToolchainSyncManager.syncAllVersions(projectDir, recommended)
    }

    private fun header(txt: String) {
        container.addView(TextView(this).apply {
            text = txt
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 30)
        })
    }

    private fun section(txt: String) {
        container.addView(TextView(this).apply {
            text = txt.uppercase()
            setTextColor(StudioUi.color(StudioUi.ActiveBlue))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 20, 0, 10)
        })
    }

    private fun gap() {
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 30) })
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun Button.studioButton(fill: String, stroke: String) {
        background = StudioUi.rounded(this@ProjectSettingsActivity, fill = fill, stroke = stroke, radiusDp = 6)
        setTextColor(Color.WHITE)
        isAllCaps = false
    }
}
