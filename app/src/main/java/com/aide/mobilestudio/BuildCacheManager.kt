package com.aide.mobilestudio

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import java.io.File

class BuildCacheManager(private val activity: AppCompatActivity) {

    private val handler = Handler(Looper.getMainLooper())

    fun show(projectDir: File?) {
        val isKorean = AppLanguage.isKorean(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        // 프로젝트 빌드 캐시
        root.addView(sectionLabel(if (isKorean) "프로젝트 빌드 캐시" else "Project Build Cache"))

        val projectStats = if (projectDir != null) {
            val buildDir = File(projectDir, "app/build")
            val cacheSize = if (buildDir.exists()) getDirSize(buildDir) else 0L
            val fileCount = if (buildDir.exists()) getFileCount(buildDir) else 0
            listOf(
                "Build" to formatSize(cacheSize),
                "Files" to "$fileCount"
            )
        } else {
            listOf("Status" to "No project open")
        }

        projectStats.forEach { (label, value) ->
            root.addView(infoRow(label, value))
        }

        if (projectDir != null) {
            root.addView(makeButton("\uD83D\uDDD1\uFE0F " + (if (isKorean) "빌드 캐시 정리" else "Clean Build Cache")) {
                cleanProjectBuildCache(projectDir)
            })
        }

        // Gradle 캐시
        root.addView(sectionLabel("Gradle Cache"))

        val gradleHome = File(activity.filesDir, ".gradle")
        val gradleCache = File(gradleHome, "caches")
        val gradleSize = if (gradleCache.exists()) getDirSize(gradleCache) else 0L

        root.addView(infoRow("Cache", formatSize(gradleSize)))

        root.addView(makeButton("\uD83D\uDDD1\uFE0F " + (if (isKorean) "Gradle 캐시 정리" else "Clean Gradle Cache")) {
            cleanGradleCache(gradleCache)
        })

        // 종속성 캐시
        root.addView(sectionLabel(if (isKorean) "종속성 캐시" else "Dependency Cache"))

        val depsCache = File(gradleHome, "caches/modules-2/files-2.1")
        val depsSize = if (depsCache.exists()) getDirSize(depsCache) else 0L

        root.addView(infoRow("Dependencies", formatSize(depsSize)))

        root.addView(makeButton("\uD83D\uDDD1\uFE0F " + (if (isKorean) "종속성 캐시 정리" else "Clean Dependencies")) {
            cleanDependenciesCache(depsCache)
        })

        // 임시 파일
        root.addView(sectionLabel(if (isKorean) "임시 파일" else "Temporary Files"))

        val tmpDir = File(activity.cacheDir, "layoutlib_tmp")
        val swapDir = File(activity.filesDir, "swap")
        val tmpSize = if (tmpDir.exists()) getDirSize(tmpDir) else 0L
        val swapSize = if (swapDir.exists()) getDirSize(swapDir) else 0L

        root.addView(infoRow("Temp", formatSize(tmpSize)))
        root.addView(infoRow("Swap", formatSize(swapSize)))

        root.addView(makeButton("\uD83D\uDDD1\uFE0F " + (if (isKorean) "임시 파일 정리" else "Clean Temp Files")) {
            cleanTempFiles(tmpDir, swapDir)
        })

        // 전체 정리 가능량
        val totalSize = gradleSize + (tmpSize + swapSize)
        root.addView(sectionLabel(if (isKorean) "전체 정리 가능량" else "Total Cleanable"))
        root.addView(TextView(activity).apply {
            text = formatSize(totalSize)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor("#46C07D".toColorInt())
            setPadding(0, dp(4), 0, dp(8))
        })

        val dialog = AlertDialog.Builder(activity)
            .setTitle("\uD83E\uDDF9 " + (if (isKorean) "빌드 캐시 관리" else "Build Cache Manager"))
            .setView(ScrollView(activity).apply {
                addView(root)
                setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            })
            .setPositiveButton(if (isKorean) "닫기" else "Close", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 16f
                }
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor("#4F8EF7".toColorInt())
        }

        dialog.show()
    }

    private fun cleanProjectBuildCache(projectDir: File) {
        val buildDir = File(projectDir, "app/build")
        if (buildDir.exists()) {
            val size = getDirSize(buildDir)
            buildDir.deleteRecursively()
            Toast.makeText(activity,
                "Cleaned: ${formatSize(size)}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, "No build cache found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanGradleCache(cacheDir: File) {
        if (cacheDir.exists()) {
            val size = getDirSize(cacheDir)
            cacheDir.deleteRecursively()
            Toast.makeText(activity,
                "Gradle cache cleaned: ${formatSize(size)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanDependenciesCache(depsDir: File) {
        if (depsDir.exists()) {
            val size = getDirSize(depsDir)
            depsDir.deleteRecursively()
            Toast.makeText(activity,
                "Dependencies cleaned: ${formatSize(size)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanTempFiles(vararg dirs: File) {
        var totalSize = 0L
        dirs.forEach { dir ->
            if (dir.exists()) {
                totalSize += getDirSize(dir)
                dir.deleteRecursively()
            }
        }
        Toast.makeText(activity,
            "Temp cleaned: ${formatSize(totalSize)}", Toast.LENGTH_SHORT).show()
    }

    private fun getDirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        dir.walkTopDown().forEach { file ->
            if (file.isFile) size += file.length()
        }
        return size
    }

    private fun getFileCount(dir: File): Int {
        if (!dir.exists()) return 0
        return dir.walkTopDown().count { it.isFile }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / 1048576.0)}MB"
            else -> "${"%.2f".format(bytes / 1073741824.0)}GB"
        }
    }

    private fun infoRow(label: String, value: String): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(TextView(this@BuildCacheManager.activity).apply {
                text = label
                textSize = 12f
                setTextColor("#C3CCD8".toColorInt())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@BuildCacheManager.activity).apply {
                text = value
                textSize = 12f
                setTextColor("#C9D1D9".toColorInt())
            })
        }
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        return Button(activity).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor("#F05C5C".toColorInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }
    }

    private fun sectionLabel(text: String): TextView {
        return TextView(activity).apply {
            this.text = text
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor("#C3CCD8".toColorInt())
            setPadding(0, dp(12), 0, dp(6))
        }
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
