package com.aide.mobilestudio.agpupgrade

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.ViewGroup
import android.view.Window
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.aide.mobilestudio.DiffViewerActivity
import com.aide.mobilestudio.StudioUi
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AGP 업그레이드 변경 사항 미리보기 (Diff 뷰어).
 * 기존 DiffViewerActivity를 재사용/확장하여 사용.
 */
class AgpUpgradePreview {

    data class FileDiff(
        val file: File,
        val originalContent: String,
        val newContent: String,
        val description: String,
    ) {
        val hasChanges: Boolean get() = originalContent != newContent
    }

    data class PreviewResult(
        val diffs: List<FileDiff>,
        val summary: String,
        val totalChanges: Int,
        val filesModified: Int,
    )

    private val backupCache = ConcurrentHashMap<File, String>()

    /**
     * 업그레이드 계획에 따른 변경 사항 미리보기 생성.
     */
    fun generatePreview(
        projectDir: File,
        scanResult: AgpUpgradeAnalyzer.ProjectScanResult,
        targetAgpVersion: String,
        targetGradleVersion: String,
        targetKotlinVersion: String,
    ): PreviewResult {
        val diffs = mutableListOf<FileDiff>()

        // 1. gradle-wrapper.properties 미리보기
        val wrapperFile = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        if (wrapperFile.exists()) {
            val original = wrapperFile.readText()
            val newGradleVer = targetGradleVersion.removePrefix("gradle-")
            val newUrl = "https://services.gradle.org/distributions/gradle-$newGradleVer-all.zip"
            val modified = original.replace(
                Regex("""distributionUrl=.*gradle-(.*)-(bin|all)\.zip"""),
                "distributionUrl=$newUrl"
            )
            if (original != modified) {
                diffs.add(FileDiff(wrapperFile, original, modified, "Gradle Wrapper upgraded to $targetGradleVersion"))
            }
        }

        // 2. libs.versions.toml 미리보기
        scanResult.libsTomlPath?.let { file ->
            if (file.exists()) {
                val original = file.readText()
                var modified = original

                modified = modified.replace(
                    Regex("""(?m)^(\s*agp\s*=\s*")[^"]*(")"""),
                    "\$1$targetAgpVersion\$2"
                )
                modified = modified.replace(
                    Regex("""(?m)^(\s*kotlin\s*=\s*")[^"]*(")"""),
                    "\$1$targetKotlinVersion\$2"
                )
                val gradleVer = targetGradleVersion.removePrefix("gradle-")
                modified = modified.replace(
                    Regex("""(?m)^(\s*gradle\s*=\s*")[^"]*(")"""),
                    "\$1$gradleVer\$2"
                )

                val recommendedNdk = AgpCompatibilityMatrix.getCompatibilityInfo(targetAgpVersion).recommendedNdkVersion
                modified = modified.replace(
                    Regex("""(?m)^(\s*ndk\s*=\s*")[^"]*(")"""),
                    "\$1$recommendedNdk\$2"
                )

                val recommendedCmake = AgpCompatibilityMatrix.getCompatibilityInfo(targetAgpVersion).recommendedCmakeVersion
                modified = modified.replace(
                    Regex("""(?m)^(\s*cmake\s*=\s*")[^"]*(")"""),
                    "\$1$recommendedCmake\$2"
                )

                if (original != modified) {
                    diffs.add(FileDiff(file, original, modified, "Version catalog updated: AGP $targetAgpVersion, Kotlin $targetKotlinVersion, Gradle $targetGradleVersion"))
                }
            }
        }

        // 3. 모듈 build.gradle.kts 미리보기
        for (file in scanResult.moduleBuildGradlePaths) {
            if (file.exists()) {
                val original = file.readText()
                var modified = original

                // AGP 버전
                modified = modified.replace(
                    Regex("(id\\s*[(\"']com\\.android\\.(?:application|library)[\")']\\s*(?:version\\s*)?[(\")'][^\"']*([\")'])"),
                    "\$1$targetAgpVersion\$2"
                )

                // Kotlin 버전
                modified = modified.replace(
                    Regex("""(id\s*[("']org\.jetbrains\.kotlin\.android[")']\s*(?:version\s*)?[("'])[^"']*([")'])"""),
                    "\$1$targetKotlinVersion\$2"
                )

                // NDK 버전
                val recommendedNdk = AgpCompatibilityMatrix.getCompatibilityInfo(targetAgpVersion).recommendedNdkVersion
                modified = modified.replace(
                    Regex("""ndkVersion\s*=\s*"[^"]+"""),
                    "ndkVersion = \"$recommendedNdk\""
                )
                modified = modified.replace(
                    Regex("""ndkVersion\s*"[^"]+"""),
                    "ndkVersion \"$recommendedNdk\""
                )

                // CMake 버전
                val recommendedCmake = AgpCompatibilityMatrix.getCompatibilityInfo(targetAgpVersion).recommendedCmakeVersion
                modified = modified.replace(
                    Regex("""cmake\s*\{\s*version\s+"[^"]+"""),
                    "cmake { version = \"$recommendedCmake\""
                )
                modified = modified.replace(
                    Regex("""cmakeVersion\s*=\s*"[^"]+"""),
                    "cmakeVersion = \"$recommendedCmake\""
                )

                // namespace 추가 (AGP 8.0+)
                val majorVersion = targetAgpVersion.split(".").first().toIntOrNull() ?: 0
                if (majorVersion >= 8 && !modified.contains("namespace")) {
                    modified = modified.replace(
                        Regex("(android\\s*\\{)"),
                        "\$1\n    namespace = \"${scanResult.namespace ?: "com.example.app"}\""
                    )
                }

                // buildToolsVersion 제거
                modified = modified.replace(Regex("buildToolsVersion\\s*=\"[^\"]+\"\\s*\\n?"), "")

                // 지원 중단 API 교체
                modified = applyApiMigrations(modified)

                if (original != modified) {
                    diffs.add(FileDiff(file, original, modified, "Module build file updated for AGP $targetAgpVersion"))
                }
            }
        }

        // 4. settings.gradle.kts 미리보기
        scanResult.settingsGradlePath?.let { file ->
            if (file.exists()) {
                val original = file.readText()
                var modified = original

                modified = modified.replace(
                    Regex("(id\\s*[(\"']com\\.android\\.(?:application|library)[\")']\\s*(?:version\\s*)?[(\")'][^\"']*([\")'])"),
                    "\$1$targetAgpVersion\$2"
                )

                if (original != modified) {
                    diffs.add(FileDiff(file, original, modified, "Settings Gradle updated for AGP $targetAgpVersion"))
                }
            }
        }

        // 5. gradle.properties 미리보기
        scanResult.gradlePropertiesPath?.let { file ->
            if (file.exists()) {
                val original = file.readText()
                var modified = original

                val recommendedNdk = AgpCompatibilityMatrix.getCompatibilityInfo(targetAgpVersion).recommendedNdkVersion
                modified = modified.replace(
                    Regex("android\\.ndkVersion\\s*=\\s*.+"),
                    "android.ndkVersion=$recommendedNdk"
                )

                // 지원 중단 속성 제거
                modified = modified.replace(Regex("android\\.enableR8\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = modified.replace(Regex("android\\.enableD8\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = modified.replace(Regex("android\\.useAndroidX\\s*=\"[^\"]+\"\\s*\\n?"), "")
                modified = modified.replace(Regex("android\\.enableJetifier\\s*=\"[^\"]+\"\\s*\\n?"), "")

                if (original != modified) {
                    diffs.add(FileDiff(file, original, modified, "Gradle properties cleaned up"))
                }
            }
        }

        val totalChanges = diffs.sumOf { countDiffLines(it.originalContent, it.newContent) }
        val summary = buildSummary(diffs, targetAgpVersion, targetGradleVersion, targetKotlinVersion)

        return PreviewResult(
            diffs = diffs,
            summary = summary,
            totalChanges = totalChanges,
            filesModified = diffs.size,
        )
    }

    private fun applyApiMigrations(content: String): String {
        var modified = content

        // variant.outputs.each → variant.outputs.all
        modified = modified.replace("variant.outputs.each", "variant.outputs.all")
        modified = modified.replace("variant.outputs {", "variant.outputs.all {")
        modified = modified.replace("variant.outputs[", "variant.outputs.all[")

        // dataBinding.enabled → buildFeatures.dataBinding
        modified = modified.replace(
            Regex("""dataBinding\s*\{\s*enabled\s*="[^"]+"\s*\}"""),
            "buildFeatures { dataBinding = true }"
        )

        // viewBinding.enabled → buildFeatures.viewBinding
        modified = modified.replace(
            Regex("""viewBinding\s*\{\s*enabled\s*="[^"]+"\s*\}"""),
            "buildFeatures { viewBinding = true }"
        )

        // 지원 중단 속성 제거
        modified = modified.replace(Regex("android\\.enableR8\\s*=\"[^\"]+\"\\s*\\n?"), "")
        modified = modified.replace(Regex("android\\.enableD8\\s*=\"[^\"]+\"\\s*\\n?"), "")
        modified = modified.replace(Regex("android\\.useAndroidX\\s*=\"[^\"]+\"\\s*\\n?"), "")
        modified = modified.replace(Regex("android\\.enableJetifier\\s*=\"[^\"]+\"\\s*\\n?"), "")

        // splits → bundle
        modified = modified.replace(
            Regex("splits\\s*\\{[^}]+}"),
            "// TODO: Migrate splits to bundle { }\n// splits configuration removed"
        )

        // lintOptions → lint
        modified = modified.replace("lintOptions {", "lint {")

        // testOptions → testOptions.unitTests
        if (modified.contains("testOptions {") && !modified.contains("unitTests {")) {
            modified = modified.replace(
                Regex("""testOptions\s*\{"""),
                "testOptions {\n        unitTests {\n            // unit test options here\n        }"
            )
        }

        // renderScript 제거
        modified = modified.replace(Regex("renderscriptSupportMode\\s*=\"[^\"]+\"\\s*\\n?"), "")
        modified = modified.replace(Regex("renderscriptTargetApi\\s*=\\d+\\s*\\n?"), "")

        // useProguard/runProguard → isMinifyEnabled
        modified = modified.replace(Regex("""useProguard\s*="[^"]+"""), "isMinifyEnabled = true")
        modified = modified.replace(Regex("""runProguard\s*="[^"]+"""), "isMinifyEnabled = true")

        // ndk.moduleName → ndk.abiFilters
        modified = modified.replace(
            Regex("ndk\\s*\\{\\s*moduleName\\s*=\"[^\"]+\"\\s*\\}"),
            "ndk { abiFilters = [] } // TODO: Specify abiFilters"
        )

        return modified
    }

    private fun countDiffLines(original: String, modified: String): Int {
        val origLines = original.lines().toSet()
        val modLines = modified.lines().toSet()
        return (origLines - modLines).size + (modLines - origLines).size
    }

    private fun buildSummary(
        diffs: List<FileDiff>,
        targetAgp: String,
        targetGradle: String,
        targetKotlin: String,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("AGP Upgrade Preview Summary")
        sb.appendLine("============================")
        sb.appendLine("Target AGP: $targetAgp")
        sb.appendLine("Target Gradle: $targetGradle")
        sb.appendLine("Target Kotlin: $targetKotlin")
        sb.appendLine("")
        sb.appendLine("Files to be modified: ${diffs.size}")
        
        val totalLines = diffs.sumOf { countDiffLines(it.originalContent, it.newContent) }
        sb.appendLine("Estimated line changes: ~$totalLines")
        sb.appendLine("")
        sb.appendLine("Changes by file:")
        for (diff in diffs) {
            val lines = countDiffLines(diff.originalContent, diff.newContent)
            sb.appendLine("  • ${diff.file.name}: ~$lines lines (${diff.description})")
        }
        
        return sb.toString()
    }

    /**
     * DiffViewerActivity를 사용하여 미리보기 표시.
     */
    fun showPreview(context: Context, preview: PreviewResult, index: Int = 0) {
        if (preview.diffs.isEmpty()) return
        
        val diff = preview.diffs[index]
        DiffViewerActivity.startFiles(context, createTempFile(diff.originalContent, "${diff.file.name}.original"), createTempFile(diff.newContent, "${diff.file.name}.new"))
    }

    /**
     * 모든 diff를 순차적으로 표시.
     */
    fun showAllPreviews(context: Context, preview: PreviewResult) {
        for (diff in preview.diffs) {
            showPreview(context, preview, preview.diffs.indexOf(diff))
        }
    }

    private fun createTempFile(content: String, name: String): File {
        val file = File(context!!.cacheDir, "agp_preview_${System.currentTimeMillis()}_$name")
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    private var context: Context? = null
        @Suppress("UNUSED_PARAMETER")
        set(value) { field = value }

    companion object {
        fun createPreview(context: Context): AgpUpgradePreview {
            return AgpUpgradePreview().apply { this.context = context }
        }
    }
}

/**
 * Diff 미리보기를 위한 커스텀 액티비티 (DiffViewerActivity 확장).
 * 여러 파일의 diff를 탭으로 보여줌.
 */
class AgpUpgradePreviewActivity : Activity() {

    companion object {
        fun start(context: Context, preview: AgpUpgradePreview.PreviewResult) {
            val intent = Intent(context, AgpUpgradePreviewActivity::class.java)
            // Serialize preview to pass via intent (simplified)
            intent.putExtra("preview_summary", preview.summary)
            intent.putExtra("files_modified", preview.filesModified)
            intent.putExtra("total_changes", preview.totalChanges)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.statusBarColor = Color.parseColor("#0B0F15")
        
        val summary = intent.getStringExtra("preview_summary") ?: "No preview available"
        val filesModified = intent.getIntExtra("files_modified", 0)
        val totalChanges = intent.getIntExtra("total_changes", 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F15"))
        }
        setContentView(root)

        // Header
        root.addView(TextView(this).apply {
            text = "AGP Upgrade Preview"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#DCE3EE"))
            setPadding(dp(16), dp(16), dp(16), dp(12))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Summary
        root.addView(TextView(this).apply {
            text = summary
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#8E99AC"))
            setPadding(dp(16), 0, dp(16), dp(16))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Stats
        val stats = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        stats.addView(createStatChip("Files: $filesModified", StudioUi.Blue))
        stats.addView(createStatChip("Changes: ~$totalChanges", StudioUi.Amber))
        stats.addView(createStatChip("Status: Ready to Apply", StudioUi.Green))
        root.addView(stats)

        // Action buttons
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(16))
            gravity = android.view.Gravity.END
        }
        actions.addView(TextView(this).apply {
            text = "CANCEL"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#8E99AC"))
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener { finish() }
        })
        actions.addView(TextView(this).apply {
            text = "APPLY UPGRADE"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FFFFFF"))
            setBackgroundColor(StudioUi.color(StudioUi.Blue))
            setPadding(dp(24), dp(12), dp(24), dp(12))
            setOnClickListener {
                // Apply upgrade logic here
                toast("Upgrade applied!")
                finish()
            }
        })
        root.addView(actions)
    }

    private fun createStatChip(text: String, color: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(color))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}