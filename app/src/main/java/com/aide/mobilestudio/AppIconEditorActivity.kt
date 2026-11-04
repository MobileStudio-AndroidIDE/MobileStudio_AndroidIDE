package com.aide.mobilestudio

import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import java.io.File

class AppIconEditorActivity : AppCompatActivity() {

    private lateinit var projectDir: File
    private lateinit var imgPreview: ImageView
    private lateinit var tvStatus: TextView
    private var selectedBitmap: Bitmap? = null
    private var packageName: String = ""

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadAndCropImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectDir = File(intent.getStringExtra("project_dir") ?: return finish())
        packageName = intent.getStringExtra("package_name") ?: "com.example.app"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#1E2227".toColorInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        root.addView(makeTopBar())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(makeSectionLabel("Current Icon"))
        content.addView(buildCurrentIconPreview())

        content.addView(makeSectionLabel("New Icon"))
        content.addView(buildNewIconPreview())

        content.addView(makeSectionLabel("Select Image"))
        content.addView(buildImagePicker())

        content.addView(makeSectionLabel("Background Color"))
        content.addView(buildColorPicker())

        content.addView(makeSectionLabel("Icon Shape"))
        content.addView(buildShapePicker())

        content.addView(makeSectionLabel("Preview"))
        content.addView(buildAllDensityPreview())

        scroll.addView(content)
        root.addView(scroll)

        root.addView(buildBottomButtons())

        setContentView(root)
    }

    private fun makeTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(TextView(this@AppIconEditorActivity).apply {
                text = "\u2190"
                textSize = 22f
                setTextColor("#C3CCD8".toColorInt())
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { finish() }
            })
            addView(TextView(this@AppIconEditorActivity).apply {
                text = if (AppLanguage.isKorean(this@AppIconEditorActivity)) "앱 아이콘 편집기" else "App Icon Editor"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(dp(12), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
        }
    }

    private fun buildCurrentIconPreview(): View {
        val wrapper = FrameLayout(this).apply {
            setBackgroundColor("#2E3540".toColorInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val iconView = ImageView(this).apply {
            val size = dp(120)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconFile = findProjectIcon()
        if (iconFile != null && iconFile.exists()) {
            val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath)
            if (bitmap != null) {
                iconView.setImageBitmap(bitmap)
            }
        } else {
            iconView.setBackgroundColor("#3E4451".toColorInt())
        }
        wrapper.addView(iconView)
        return wrapper
    }

    private fun buildNewIconPreview(): View {
        val wrapper = FrameLayout(this).apply {
            setBackgroundColor("#2E3540".toColorInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        imgPreview = ImageView(this).apply {
            val size = dp(120)
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor("#3E4451".toColorInt())
        }
        wrapper.addView(imgPreview)
        return wrapper
    }

    private fun buildImagePicker(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(makeButton("Gallery") {
                pickImage.launch("image/*")
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).also { p -> p.marginEnd = dp(8) }
            })
            addView(makeButton("Camera") {
                Toast.makeText(this@AppIconEditorActivity,
                    if (AppLanguage.isKorean(this@AppIconEditorActivity)) "카메라에서 촬영하기" else "Take a photo",
                    Toast.LENGTH_SHORT).show()
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
        }
    }

    private fun buildColorPicker(): View {
        val colors = listOf(
            "#6200EE", "#03DAC5", "#FF5722", "#4CAF50",
            "#2196F3", "#FF9800", "#E91E63", "#009688",
            "#795548", "#607D8B", "#9C27B0", "#F44336"
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(4))
        }
        var selectedColor = colors[0]
        val colorViews = mutableListOf<FrameLayout>()

        colors.forEachIndexed { index, color ->
            val swatch = FrameLayout(this).apply {
                val size = dp(32)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = dp(6) }
                background = GradientDrawable().apply {
                    setColor(color.toColorInt())
                    cornerRadius = dp(4f).toFloat()
                    if (index == 0) setStroke(dp(2), Color.WHITE) else setStroke(1, "#555E6B".toColorInt())
                }
                setOnClickListener { selectedColor = color; updateSwatchBorders(colorViews, index) }
            }
            colorViews.add(swatch)
            container.addView(swatch)
        }
        return container
    }

    private fun updateSwatchBorders(swatches: List<FrameLayout>, selectedIndex: Int) {
        swatches.forEachIndexed { index, swatch ->
            (swatch.background as? GradientDrawable)?.setStroke(
                if (index == selectedIndex) dp(2) else 1,
                if (index == selectedIndex) Color.WHITE else "#555E6B".toColorInt()
            )
        }
    }

    private fun buildShapePicker(): View {
        val shapes = listOf("Circle", "Square", "Rounded")
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        shapes.forEach { shape ->
            container.addView(makeButton(shape) {
                Toast.makeText(this@AppIconEditorActivity, "Shape: $shape", Toast.LENGTH_SHORT).show()
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            })
        }
        return container
    }

    private fun buildAllDensityPreview(): View {
        val densities = mapOf(
            "mdpi (48x48)" to 48,
            "hdpi (72x72)" to 72,
            "xhdpi (96x96)" to 96,
            "xxhdpi (144x144)" to 144,
            "xxxhdpi (192x192)" to 192
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        densities.forEach { (label, size) ->
            val preview = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(4) }
            }
            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(size.coerceAtMost(dp(48)), size.coerceAtMost(dp(48)), Gravity.CENTER)
                setBackgroundColor("#3E4451".toColorInt())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            preview.addView(iv)
            val tv = TextView(this).apply {
                text = label.split(" ").first()
                textSize = 9f
                setTextColor("#C3CCD8".toColorInt())
                gravity = Gravity.CENTER
            }
            container.addView(preview)
            container.addView(tv)
        }
        return container
    }

    private fun buildBottomButtons(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
            addView(makeButton("Apply Icon") {
                applyIcon()
            }.also {
                it.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            })
            addView(makeButton("Reset") {
                selectedBitmap = null
                imgPreview.setImageBitmap(null)
                imgPreview.setBackgroundColor("#3E4451".toColorInt())
            })
        }
    }

    private fun applyIcon() {
        val bitmap = selectedBitmap ?: run {
            Toast.makeText(this,
                if (AppLanguage.isKorean(this)) "먼저 이미지를 선택하세요" else "Select an image first",
                Toast.LENGTH_SHORT).show()
            return
        }

        val densities = mapOf(
            "mipmap-mdpi" to 48,
            "mipmap-hdpi" to 72,
            "mipmap-xhdpi" to 96,
            "mipmap-xxhdpi" to 144,
            "mipmap-xxxhdpi" to 192
        )

        val resDir = File(projectDir, "app/src/main/res")
        var count = 0

        densities.forEach { (folder, size) ->
            val dir = File(resDir, folder).apply { mkdirs() }
            val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
            val file = File(dir, "ic_launcher.png")
            file.outputStream().use { out ->
                resized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val roundFile = File(dir, "ic_launcher_round.png")
            roundFile.outputStream().use { out ->
                resized.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            count += 2
        }

        generateAdaptiveIconXml(resDir)

        Toast.makeText(this,
            if (AppLanguage.isKorean(this)) "아이콘 생성 완료: ${count}개 파일"
            else "Icon generated: $count files",
            Toast.LENGTH_LONG).show()

        tvStatus.text = if (AppLanguage.isKorean(this))
            "아이콘이 성공적으로 적용되었습니다!" else "Icon applied successfully!"
        tvStatus.setTextColor("#4CAF50".toColorInt())
    }

    private fun generateAdaptiveIconXml(resDir: File) {
        val anydpiV26 = File(resDir, "mipmap-anydpi-v26").apply { mkdirs() }
        val icLauncher = """
            <?xml version="1.0" encoding="utf-8"?>
            <adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
                <background android:drawable="@drawable/ic_launcher_background"/>
                <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
            </adaptive-icon>
        """.trimIndent()
        anydpiV26.resolve("ic_launcher.xml").writeText(icLauncher)
        anydpiV26.resolve("ic_launcher_round.xml").writeText(icLauncher)

        val drawableDir = File(resDir, "drawable").apply { mkdirs() }
        val bgXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <shape xmlns:android="http://schemas.android.com/apk/res/android"
                android:shape="rectangle">
                <solid android:color="#6200EE"/>
            </shape>
        """.trimIndent()
        drawableDir.resolve("ic_launcher_background.xml").writeText(bgXml)

        val foregroundDir = File(resDir, "drawable").apply { mkdirs() }
        selectedBitmap?.let { bitmap ->
            val fgBitmap = Bitmap.createScaledBitmap(bitmap, 432, 432, true)
            foregroundDir.resolve("ic_launcher_foreground.png").outputStream().use { out ->
                fgBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }
    }

    private fun loadAndCropImage(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            selectedBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            selectedBitmap?.let { bitmap ->
                val size = 192
                val scaled = Bitmap.createScaledBitmap(bitmap, size, size, true)
                imgPreview.setImageBitmap(scaled)

                val densities = mapOf(
                    "mdpi (48x48)" to 48,
                    "hdpi (72x72)" to 72,
                    "xhdpi (96x96)" to 96,
                    "xxhdpi (144x144)" to 144,
                    "xxxhdpi (192x192)" to 192
                )
                Toast.makeText(this,
                    if (AppLanguage.isKorean(this)) "이미지 로드됨: ${bitmap.width}x${bitmap.height}"
                    else "Image loaded: ${bitmap.width}x${bitmap.height}",
                    Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            ErrorLogDialog.show(
                this,
                if (AppLanguage.isKorean(this)) "이미지 로드 오류" else "Image Load Error",
                "Error: ${e.message}"
            )
        }
    }

    private fun findProjectIcon(): File? {
        val densities = listOf("xxxhdpi", "xxhdpi", "xhdpi", "hdpi", "mdpi")
        for (density in densities) {
            val file = File(projectDir, "app/src/main/res/mipmap-$density/ic_launcher.png")
            if (file.exists()) return file
        }
        return null
    }

    private fun makeSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor("#C3CCD8".toColorInt())
            setPadding(0, dp(16), 0, dp(8))
        }
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor("#3E4451".toColorInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = 0
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()
}
