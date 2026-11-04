package com.aide.mobilestudio

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import java.io.File
import java.util.zip.ZipFile

class ApkInfoActivity : AppCompatActivity() {

    private lateinit var tvOutput: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apkPath = intent.getStringExtra("apk_path") ?: return finish()
        val apkFile = File(apkPath)
        if (!apkFile.exists()) { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#1E2227".toColorInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        root.addView(TextView(this).apply {
            text = apkFile.name
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(16))
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(buildInfoCard("File Information", buildList {
            add("Name" to apkFile.name)
            add("Size" to formatSize(apkFile.length()))
            add("Path" to apkFile.absolutePath)
            add("Last Modified" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(apkFile.lastModified()))
        }))

        content.addView(buildInfoCard("APK Contents", parseApkContents(apkFile)))

        content.addView(buildInfoCard("Permissions", parsePermissions(apkFile)))

        content.addView(buildInfoCard("Activities", parseActivities(apkFile)))

        tvOutput = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor("#C9D1D9".toColorInt())
            setBackgroundColor("#0F1218".toColorInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        content.addView(tvOutput)

        scroll.addView(content)
        root.addView(scroll)

        root.addView(Button(this).apply {
            text = "Copy All Info"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor("#3E4451".toColorInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val allInfo = buildString {
                    appendLine("=== APK Info ===")
                    appendLine("Name: ${apkFile.name}")
                    appendLine("Size: ${formatSize(apkFile.length())}")
                    appendLine()
                    appendLine("=== Contents ===")
                    parseApkContents(apkFile).forEach { (k, v) -> appendLine("$k: $v") }
                    appendLine()
                    appendLine("=== Permissions ===")
                    val perms = parsePermissions(apkFile)
                    if (perms.isEmpty()) appendLine("None") else perms.forEach { (k, v) -> appendLine("$k: $v") }
                    appendLine()
                    appendLine("=== Activities ===")
                    val acts = parseActivities(apkFile)
                    if (acts.isEmpty()) appendLine("None") else acts.forEach { (k, v) -> appendLine("$k: $v") }
                }
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("APK Info", allInfo))
                Toast.makeText(this@ApkInfoActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(root)
    }

    private fun buildInfoCard(title: String, items: List<Pair<String, String>>): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#2E3540".toColorInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
        }

        card.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor("#4F8EF7".toColorInt())
            setPadding(0, 0, 0, dp(8))
        })

        items.forEach { (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor("#C3CCD8".toColorInt())
                layoutParams = LinearLayout.LayoutParams(dp(120), WRAP_CONTENT)
            })
            row.addView(TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor("#C9D1D9".toColorInt())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setHorizontallyScrolling(true)
                isHorizontalScrollBarEnabled = true
            })
            card.addView(row)
        }

        return card
    }

    private fun parseApkContents(apkFile: File): List<Pair<String, String>> {
        return try {
            val entries = mutableMapOf<String, Int>()
            ZipFile(apkFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    val prefix = entry.name.split("/").firstOrNull() ?: entry.name
                    entries[prefix] = (entries[prefix] ?: 0) + 1
                }
            }
            entries.map { (name, count) -> name to "$count files" }
        } catch (e: Exception) {
            listOf("Error" to e.message.orEmpty())
        }
    }

    private fun parsePermissions(apkFile: File): List<Pair<String, String>> {
        return try {
            ZipFile(apkFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return emptyList()
                val bytes = zip.getInputStream(manifestEntry).readBytes()
                val content = String(bytes, Charsets.UTF_8)
                val perms = mutableListOf<Pair<String, String>>()

                val ns = listOf("android.permission.", "android.")
                ns.forEach { prefix ->
                    val regex = Regex("""$prefix([A-Z_]+)""")
                    regex.findAll(content).forEach { match ->
                        val perm = match.value
                        if (perm.startsWith("android.permission.")) {
                            perms.add(perm to "")
                        }
                    }
                }
                perms.distinctBy { it.first }
            }
        } catch (e: Exception) {
            listOf("Error" to e.message.orEmpty())
        }
    }

    private fun parseActivities(apkFile: File): List<Pair<String, String>> {
        return try {
            ZipFile(apkFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: return emptyList()
                val bytes = zip.getInputStream(manifestEntry).readBytes()
                val content = String(bytes, Charsets.UTF_8)
                val activities = mutableListOf<Pair<String, String>>()

                val regex = Regex("""\.([A-Za-z]+)Activity""")
                regex.findAll(content).forEach { match ->
                    activities.add(match.value.trimStart('.') to "Activity")
                }
                activities.distinctBy { it.first }
            }
        } catch (e: Exception) {
            listOf("Error" to e.message.orEmpty())
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "${bytes}B"
            bytes < 1024 * 1024 -> "${bytes / 1024}KB"
            else -> "${"%.1f".format(bytes / 1048576.0)}MB"
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
