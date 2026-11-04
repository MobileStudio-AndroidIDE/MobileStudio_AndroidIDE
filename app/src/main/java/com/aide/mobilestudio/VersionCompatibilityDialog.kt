package com.aide.mobilestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import com.aide.mobilestudio.CompatibilityChecker.RecommendedSet
import java.io.File

class VersionCompatibilityDialog(private val activity: AppCompatActivity) {

    fun show(
        kind: ToolchainManager.Kind,
        currentVersion: String,
        newVersion: String,
        onConfirm: () -> Unit
    ) {
        val isKorean = AppLanguage.isKorean(activity)
        val warnings = CompatibilityChecker.check(kind, currentVersion, newVersion)
        val hasWarnings = warnings.isNotEmpty()
        
        val recommended = CompatibilityChecker.getRecommendedSet(kind, newVersion)
        val toolchain = ToolchainManager(activity)
        val currentJdk = toolchain.activeVersion(ToolchainManager.Kind.JDK)
        val currentGradle = toolchain.activeVersion(ToolchainManager.Kind.GRADLE)
        val currentNdk = toolchain.activeVersion(ToolchainManager.Kind.NDK)
        
        val updates = mutableListOf<String>()
        if (recommended.jdk != currentJdk && kind != ToolchainManager.Kind.JDK) updates.add("JDK: $currentJdk -> ${recommended.jdk}")
        if (recommended.gradle != currentGradle && kind != ToolchainManager.Kind.GRADLE) updates.add("Gradle: $currentGradle -> ${recommended.gradle}")
        if (recommended.ndk != currentNdk && kind != ToolchainManager.Kind.NDK) updates.add("NDK: $currentNdk -> ${recommended.ndk}")
        
        val hasRecommended = updates.isNotEmpty()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        // Header
        val headerText = TextView(activity).apply {
            text = if (isKorean) "${kind.displayName} 버전 변경" else "${kind.displayName} Version Change"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(StudioUi.color(StudioUi.Text))
        }
        root.addView(headerText)

        // Before / After comparison
        val compareRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 24, 0, 16)
        }

        fun versionBox(label: String, version: String, bgColor: Int): LinearLayout {
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setPadding(16, 14, 16, 14)
                background = GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 12f
                }
            }
            col.addView(TextView(activity).apply {
                text = label
                textSize = 11f
                setTextColor("#8B949E".toColorInt())
            })
            col.addView(TextView(activity).apply {
                text = versionLabel(version)
                textSize = 13f
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextColor(StudioUi.color(StudioUi.Text))
                gravity = Gravity.CENTER
                setPadding(0, 6, 0, 0)
            })
            return col
        }

        compareRow.addView(versionBox(
            if (isKorean) "현재" else "Current",
            currentVersion,
            StudioUi.color(StudioUi.Surface2)
        ))

        compareRow.addView(TextView(activity).apply {
            text = "  \u27A1  "
            textSize = 20f
            setTextColor("#4F8EF7".toColorInt())
            gravity = Gravity.CENTER
        })

        compareRow.addView(versionBox(
            if (isKorean) "변경" else "Change to",
            newVersion,
            if (hasWarnings) StudioUi.color("#3D2200") else StudioUi.color("#0D2818")
        ))

        root.addView(compareRow)

        // Compatibility warnings
        if (hasWarnings) {
            val warnTitle = TextView(activity).apply {
                text = if (isKorean) "\u26A0\uFE0F 호환성 경고" else "\u26A0\uFE0F Compatibility Warnings"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor("#E3B963".toColorInt())
                setPadding(0, 12, 0, 8)
            }
            root.addView(warnTitle)

            warnings.forEach { warning ->
                val warnRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(8, 6, 8, 6)
                    background = GradientDrawable().apply {
                        setColor(StudioUi.color("#2D2200"))
                        cornerRadius = 8f
                        setStroke(1, StudioUi.color("#5A4E1E"))
                    }
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        bottomMargin = 6
                    }
                }
                warnRow.addView(TextView(activity).apply {
                    text = "\u26A0"
                    textSize = 14f
                    setPadding(0, 0, 10, 0)
                })
                warnRow.addView(TextView(activity).apply {
                    text = warning
                    textSize = 12f
                    setTextColor("#E3B963".toColorInt())
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                root.addView(warnRow)
            }
        } else {
            val okText = TextView(activity).apply {
                text = if (isKorean) "\u2705 호환되는 버전입니다" else "\u2705 Versions are compatible"
                textSize = 13f
                setTextColor("#46C07D".toColorInt())
                setPadding(0, 16, 0, 8)
            }
            root.addView(okText)
        }

        // Recommended updates section
        if (hasRecommended) {
            gap(root, 16)
            val recTitle = TextView(activity).apply {
                text = if (isKorean) "\u2728 권장 업데이트" else "\u2728 Recommended Updates"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor("#58A6FF".toColorInt())
                setPadding(0, 8, 0, 8)
            }
            root.addView(recTitle)
            
            val recContainer = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                background = GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface))
                    cornerRadius = 8f
                    setStroke(1, StudioUi.color(StudioUi.Border))
                }
            }
            
            updates.forEach { update ->
                recContainer.addView(TextView(activity).apply {
                    text = "• $update"
                    textSize = 12f
                    setTextColor(StudioUi.color(StudioUi.Text))
                    setPadding(0, 2, 0, 2)
                })
            }
            
            val btnApplyAll = Button(activity).apply {
                text = if (isKorean) "권장 설정 모두 적용" else "Apply Recommended Stack"
                textSize = 11f
                isAllCaps = false
                val fill = "#1F6FEB" // Bright blue
                val stroke = "#58A6FF" // Cyan-blue border
                background = StudioUi.rounded(activity, fill = fill, stroke = stroke, radiusDp = 6)
                setTextColor(StudioUi.color(StudioUi.Text))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 36.dp()).apply {
                    topMargin = 12
                }
                tag = "apply_all"
            }
            recContainer.addView(btnApplyAll)
            root.addView(recContainer)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(ScrollView(activity).apply {
                addView(root)
                setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            })
            .setPositiveButton(if (isKorean) "변경" else "Change", null)
            .setNegativeButton(if (isKorean) "변경하지 않기" else "Don't change", null)
            .create()

        dialog.setOnShowListener {
            root.findViewWithTag<Button>("apply_all")?.setOnClickListener {
                applyAllRecommended(toolchain, recommended)
                dialog.dismiss()
                onConfirm()
            }
            dialog.window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 16f
                }
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                if (hasWarnings) "#E3B963".toColorInt() else "#58A6FF".toColorInt()
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor("#8B949E".toColorInt())

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun versionLabel(version: String): String = when {
        version.startsWith("jdk-") -> version.removePrefix("jdk-").let { "JDK $it" }
        version.startsWith("gradle-") -> version.removePrefix("gradle-").let { "Gradle $it" }
        version.startsWith("android-") -> version.removePrefix("android-").let { "API $it" }
        version.startsWith("python-") -> version.removePrefix("python-").let { "Python $it" }
        else -> version
    }

    private fun gap(parent: LinearLayout, size: Int) {
        parent.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, size.dp()) })
    }

    private fun applyAllRecommended(toolchain: ToolchainManager, recommended: RecommendedSet) {
        toolchain.setActiveVersion(ToolchainManager.Kind.JDK, recommended.jdk)
        toolchain.setActiveVersion(ToolchainManager.Kind.GRADLE, recommended.gradle)
        toolchain.setActiveVersion(ToolchainManager.Kind.NDK, recommended.ndk)
        toolchain.setActiveVersion(ToolchainManager.Kind.CMAKE, recommended.cmake)
        toolchain.setActiveVersion(ToolchainManager.Kind.SDK, recommended.sdk)
        
        val lastProject = activity.getSharedPreferences("ide", Context.MODE_PRIVATE).getString("last_project", null)
        if (lastProject != null) {
            val dir = File(lastProject)
            if (dir.exists()) {
                ToolchainSyncManager.syncAllVersions(dir, recommended)
            }
        }
    }

    private fun Int.dp(): Int = (this * activity.resources.displayMetrics.density).toInt()
}
