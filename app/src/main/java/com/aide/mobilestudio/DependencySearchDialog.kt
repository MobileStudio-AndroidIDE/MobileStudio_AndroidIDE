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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class DependencySearchDialog(private val activity: AppCompatActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private val searchResults = mutableListOf<Dependency>()

    data class Dependency(
        val groupId: String,
        val artifactId: String,
        val version: String,
        val description: String = ""
    ) {
        val mavenNotation: String get() = "$groupId:$artifactId:$version"
        val gradleLine: String get() = "implementation '$mavenNotation'"
    }

    fun show(projectDir: File) {
        val isKorean = AppLanguage.isKorean(activity)

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        // Search input
        val searchInput = EditText(activity).apply {
            hint = if (isKorean) "라이브러리 검색(예시: retrofit, room, coil)" else "Search libraries (e.g. retrofit, room, coil)"
            setTextColor(Color.WHITE)
            setHintTextColor("#555E6B".toColorInt())
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(StudioUi.color(StudioUi.Surface2))
                cornerRadius = 10f
                setStroke(1, StudioUi.color(StudioUi.Border))
            }
            setPadding(24, 18, 24, 18)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        root.addView(searchInput)

        // Search button
        val searchBtn = Button(activity).apply {
            text = if (isKorean) "검색" else "Search"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor("#3E4451".toColorInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        root.addView(searchBtn)

        // Loading indicator
        val loadingText = TextView(activity).apply {
            text = ""
            textSize = 11f
            setTextColor("#76839A".toColorInt())
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(loadingText)

        // Results list
        val resultsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val resultsScroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            addView(resultsContainer)
        }
        root.addView(resultsScroll)

        // Quick categories
        root.addView(sectionLabel(if (isKorean) "인기 라이브러리" else "Popular Libraries"))
        val categories = listOf(
            "Retrofit" to "com.squareup.retrofit2",
            "Room" to "androidx.room",
            "Coil" to "io.coil-kt",
            "Hilt" to "dagger.hilt",
            "Compose" to "androidx.compose",
            "Navigation" to "androidx.navigation",
            "Glide" to "com.github.bumptech.glide",
            "Coroutines" to "org.jetbrains.kotlinx"
        )
        val catRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        categories.take(4).forEach { (name, group) ->
            catRow.addView(makeChip(name) {
                searchInput.setText(group)
                performSearch(group, resultsContainer, loadingText, projectDir)
            })
        }
        root.addView(catRow)
        val catRow2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        categories.drop(4).forEach { (name, group) ->
            catRow2.addView(makeChip(name) {
                searchInput.setText(group)
                performSearch(group, resultsContainer, loadingText, projectDir)
            })
        }
        root.addView(catRow2)

        searchBtn.setOnClickListener {
            val query = searchInput.text.toString().trim()
            if (query.isEmpty()) return@setOnClickListener
            performSearch(query, resultsContainer, loadingText, projectDir)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("\uD83D\uDD0D " + (if (isKorean) "의존성 검색" else "Dependency Search"))
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

    private fun performSearch(query: String, container: LinearLayout, loadingText: TextView, projectDir: File) {
        container.removeAllViews()
        loadingText.text = "Searching..."
        searchResults.clear()

        Thread {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://search.maven.org/solrsearch/select?q=$encoded&rows=20&wt=json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                val response = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(response)
                val docs = json.getJSONObject("response").getJSONArray("docs")

                val results = mutableListOf<Dependency>()
                for (i in 0 until docs.length()) {
                    val doc = docs.getJSONObject(i)
                    results.add(Dependency(
                        groupId = doc.optString("g", ""),
                        artifactId = doc.optString("a", ""),
                        version = doc.optString("latestVersion", ""),
                        description = doc.optString("id", "")
                    ))
                }

                handler.post {
                    loadingText.text = "${results.size} results found"
                    searchResults.clear()
                    searchResults.addAll(results)

                    results.forEach { dep ->
                        container.addView(buildResultRow(dep, projectDir))
                    }

                    if (results.isEmpty()) {
                        loadingText.text = "No results found"
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    loadingText.text = "Search failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun buildResultRow(dep: Dependency, projectDir: File): View {
        val isKorean = AppLanguage.isKorean(activity)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(4) }
        }

        row.addView(TextView(activity).apply {
            text = "${dep.groupId}:${dep.artifactId}"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor("#E6EDF3".toColorInt())
            setHorizontallyScrolling(true)
            isHorizontalScrollBarEnabled = true
        })

        row.addView(TextView(activity).apply {
            text = "v${dep.version}"
            textSize = 11f
            setTextColor("#46C07D".toColorInt())
            setPadding(0, dp(2), 0, dp(2))
        })

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        btnRow.addView(Button(activity).apply {
            text = if (isKorean) "build.gradle에 추가" else "Add to build.gradle"
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor("#3E4451".toColorInt())
            setPadding(dp(12), dp(4), dp(12), dp(4))
            minimumHeight = 0
            setOnClickListener { addToBuildGradle(dep, projectDir) }
        })

        btnRow.addView(Button(activity).apply {
            text = "Copy"
            textSize = 10f
            setTextColor("#4F8EF7".toColorInt())
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minimumHeight = 0
            setOnClickListener {
                val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Dependency", dep.gradleLine))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
        })

        row.addView(btnRow)
        return row
    }

    private fun addToBuildGradle(dep: Dependency, projectDir: File) {
        val gradleFile = listOf(
            File(projectDir, "app/build.gradle.kts"),
            File(projectDir, "app/build.gradle")
        ).firstOrNull { it.exists() }
        if (gradleFile == null) {
            Toast.makeText(activity, "build.gradle not found", Toast.LENGTH_SHORT).show()
            return
        }

        val text = gradleFile.readText()
        if (text.contains(dep.mavenNotation)) {
            Toast.makeText(activity, "Already added", Toast.LENGTH_SHORT).show()
            return
        }

        val dependenciesPattern = Regex("""dependencies\s*\{""")
        val match = dependenciesPattern.find(text)
        if (match == null) {
            Toast.makeText(activity, "dependencies block not found", Toast.LENGTH_SHORT).show()
            return
        }

        val insertPos = match.range.last + 1
        val newLine = "\n    ${dep.gradleLine}"
        val newText = text.substring(0, insertPos) + newLine + text.substring(insertPos)

        gradleFile.writeText(newText)

        Toast.makeText(activity,
            "Added: ${dep.mavenNotation}",
            Toast.LENGTH_SHORT).show()
    }

    private fun makeChip(text: String, onClick: () -> Unit): Button {
        return Button(activity).apply {
            this.text = text
            textSize = 10f
            setTextColor("#C3CCD8".toColorInt())
            setBackgroundColor("#2E3540".toColorInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginEnd = dp(4)
            }
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
