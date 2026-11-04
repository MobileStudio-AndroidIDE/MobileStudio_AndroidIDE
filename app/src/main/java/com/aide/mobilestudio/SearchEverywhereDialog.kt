package com.aide.mobilestudio

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Search Everywhere - Android Studio Shift+Shift equivalent
 * Searches files, classes, functions, resources, and Gradle tasks
 */
class SearchEverywhereDialog(private val activity: Activity) {

    data class SearchResult(
        val name: String,
        val path: String,
        val type: ResultType,
        val line: Int = 0,
        val preview: String = ""
    )

    enum class ResultType(val icon: String, val label: String) {
        FILE("\uD83D\uDCC4", "File"),
        CLASS("\uD83D\uDCCB", "Class"),
        FUNCTION("\u26A1", "Function"),
        RESOURCE("\uD83D\uDDB1", "Resource"),
        GRADLE_TASK("\u2699\uFE0F", "Gradle Task")
    }

    private val searchExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentQuery = ""
    private var searchDialog: AlertDialog? = null
    private var initialCategory = "All"
    private var currentProjectDir: File? = null

    // Index cache
    private val fileIndex = ConcurrentHashMap<String, SearchResult>()
    private val resourceIndex = ConcurrentHashMap<String, SearchResult>()
    private val gradleTaskIndex = ConcurrentHashMap<String, SearchResult>()
    @Volatile private var indexBuilt = false
    @Volatile private var indexedDirPath: String? = null

    fun show(projectDir: File, category: String = "All") {
        initialCategory = category
        currentProjectDir = projectDir
        val dirKey = runCatching { projectDir.canonicalPath }.getOrElse { projectDir.absolutePath }
        if (!indexBuilt || indexedDirPath != dirKey) {
            indexedDirPath = dirKey
            buildIndex(projectDir)
        }

        val dp = { px: Int -> (px * activity.resources.displayMetrics.density).toInt() }

        val resultContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Card))
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val searchInput = EditText(activity).apply {
            hint = AppLanguage.literal(activity, "Search files, classes, functions, resources...")
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(StudioUi.color(StudioUi.Text))
            setHintTextColor(StudioUi.color(StudioUi.Muted))
            background = StudioUi.inputBg(activity)
            textSize = 15f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tabRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        val categories = listOf("All", "Files", "Classes", "Functions", "Resources", "Gradle")
        val categoryButtons = mutableListOf<Button>()
        var selectedCategory = initialCategory
        categories.forEach { cat ->
            val isSelected = cat == selectedCategory
            val btn = Button(activity).apply {
                text = cat
                textSize = 11f
                setTextColor(StudioUi.color(if (isSelected) StudioUi.Text else StudioUi.TextSoft))
                background = StudioUi.rounded(
                    activity,
                    fill = if (isSelected) StudioUi.Surface2 else StudioUi.Card,
                    stroke = if (isSelected) StudioUi.Blue else StudioUi.Border,
                    radiusDp = StudioUi.RadiusMd
                )
                setPadding(dp(12), dp(4), dp(12), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.rightMargin = dp(6) }
                setOnClickListener {
                    selectedCategory = cat
                    categoryButtons.forEach { b ->
                        b.setTextColor(StudioUi.color(StudioUi.TextSoft))
                        b.background = StudioUi.rounded(activity, fill = StudioUi.Card, stroke = StudioUi.Border, radiusDp = StudioUi.RadiusMd)
                    }
                    setTextColor(StudioUi.color(StudioUi.Text))
                    background = StudioUi.rounded(activity, fill = StudioUi.Surface2, stroke = StudioUi.Blue, radiusDp = StudioUi.RadiusMd)
                    performSearch(searchInput.text.toString(), selectedCategory, resultContainer)
                }
            }
            categoryButtons.add(btn)
            tabRow.addView(btn)
        }

        val resultScroll = ScrollView(activity).apply {
            addView(resultContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        container.addView(searchInput)
        container.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow)
        })
        container.addView(resultScroll)

        searchInput.addTextChangedListener(object : TextWatcher {
            private var debounceRunnable: Runnable? = null
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                debounceRunnable = Runnable {
                    performSearch(s?.toString() ?: "", selectedCategory, resultContainer)
                }
                mainHandler.postDelayed(debounceRunnable!!, 200)
            }
        })

        searchDialog = AlertDialog.Builder(activity)
            .setView(container)
            .create()

        searchDialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.95).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.7).toInt()
            )
            setGravity(Gravity.CENTER)
        }

        searchDialog?.show()

        searchInput.post {
            searchInput.requestFocus()
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun performSearch(query: String, category: String, container: LinearLayout) {
        currentQuery = query
        container.removeAllViews()

        if (query.length < 2) {
            container.addView(createHintView(AppLanguage.literal(activity, "Type at least 2 characters to search")))
            return
        }

        container.addView(createHintView(AppLanguage.literal(activity, "Searching...")))

        searchExecutor.execute {
            val results = mutableListOf<SearchResult>()
            val lowerQuery = query.lowercase()

            // Search files
            if (category == "All" || category == "Files") {
                for ((_, result) in fileIndex) {
                    if (result.name.lowercase().contains(lowerQuery) ||
                        result.path.lowercase().contains(lowerQuery)) {
                        results.add(result)
                    }
                }
            }

            // Search classes and functions
            if (category == "All" || category == "Classes" || category == "Functions") {
                for ((_, result) in fileIndex) {
                    if (result.type == ResultType.CLASS || result.type == ResultType.FUNCTION) {
                        if (result.name.lowercase().contains(lowerQuery)) {
                            results.add(result)
                        }
                    }
                }
            }

            // Search resources
            if (category == "All" || category == "Resources") {
                for ((_, result) in resourceIndex) {
                    if (result.name.lowercase().contains(lowerQuery)) {
                        results.add(result)
                    }
                }
            }

            // Search Gradle tasks
            if (category == "All" || category == "Gradle") {
                for ((_, result) in gradleTaskIndex) {
                    if (result.name.lowercase().contains(lowerQuery)) {
                        results.add(result)
                    }
                }
            }

            // Content search for "All" category with 3+ char queries
            if (category == "All" && lowerQuery.length >= 3 && results.size < 30) {
                val dir = currentProjectDir
                if (dir != null) {
                    val contentResults = searchContent(query, dir, maxResults = 15)
                    results.addAll(contentResults)
                }
            }

            // Sort by relevance
            val sorted = results.sortedWith(compareBy<SearchResult> {
                if (it.name.lowercase() == lowerQuery) 0
                else if (it.name.lowercase().startsWith(lowerQuery)) 1
                else if (it.name.lowercase().contains(lowerQuery)) 2
                else 3
            }.thenBy { it.name }).take(50)

            mainHandler.post {
                container.removeAllViews()
                if (sorted.isEmpty()) {
                    container.addView(createHintView(AppLanguage.literal(activity, "No results found")))
                } else {
                    container.addView(createHintView(AppLanguage.literal(activity, "${sorted.size} results")))
                    sorted.forEach { result ->
                        container.addView(createResultRow(result))
                    }
                }
            }
        }
    }

    private fun createHintView(text: String): TextView {
        val dp = { px: Int -> (px * activity.resources.displayMetrics.density).toInt() }
        return TextView(activity).apply {
            this.text = text
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 12f
            setPadding(dp(8), dp(8), dp(8), dp(4))
        }
    }

    private fun createResultRow(result: SearchResult): View {
        val dp = { px: Int -> (px * activity.resources.displayMetrics.density).toInt() }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true

            setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> setBackgroundColor(Color.TRANSPARENT)
                }
                false
            }

            // Icon
            addView(TextView(activity).apply {
                text = result.type.icon
                textSize = 16f
                setPadding(0, 0, dp(10), 0)
            })

            // Text column
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(activity).apply {
                    text = result.name
                    setTextColor(StudioUi.color(StudioUi.Text))
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })

                addView(TextView(activity).apply {
                    text = "${result.type.label}  ${result.path}${if (result.line > 0) ":${result.line}" else ""}"
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    textSize = 11f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })

                if (result.preview.isNotEmpty()) {
                    addView(TextView(activity).apply {
                        text = result.preview
                        setTextColor(StudioUi.color(StudioUi.Muted))
                        textSize = 11f
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }
            })

            setOnClickListener {
                searchDialog?.dismiss()
                onResultClick?.invoke(result)
            }
        }
    }

    var onResultClick: ((SearchResult) -> Unit)? = null

    fun buildIndex(projectDir: File) {
        searchExecutor.execute {
            fileIndex.clear()
            resourceIndex.clear()
            gradleTaskIndex.clear()

            // Index source files
            val srcDirs = listOf(
                File(projectDir, "app/src/main/java"),
                File(projectDir, "app/src/main/kotlin"),
                File(projectDir, "app/src/test/java"),
                File(projectDir, "app/src/androidTest/java")
            )
            srcDirs.filter { it.exists() }.forEach { dir ->
                dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { file ->
                    val relPath = file.relativeTo(projectDir).path
                    fileIndex[relPath] = SearchResult(
                        name = file.nameWithoutExtension,
                        path = relPath,
                        type = ResultType.FILE
                    )
                    // Index classes and functions in file
                    try {
                        val content = file.readText()
                        val classPattern = Regex("""\b(class|object|interface|enum class)\s+(\w+)""")
                        classPattern.findAll(content).forEach { match ->
                            val className = match.groupValues[2]
                            val line = content.substring(0, match.range.first).count { it == '\n' } + 1
                            fileIndex["${relPath}:${className}"] = SearchResult(
                                name = className,
                                path = relPath,
                                type = ResultType.CLASS,
                                line = line
                            )
                        }
                        val funPattern = Regex("""\bfun\s+(\w+)""")
                        funPattern.findAll(content).forEach { match ->
                            val funName = match.groupValues[1]
                            val line = content.substring(0, match.range.first).count { it == '\n' } + 1
                            fileIndex["${relPath}:${funName}"] = SearchResult(
                                name = funName,
                                path = relPath,
                                type = ResultType.FUNCTION,
                                line = line
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            // Index resources
            val resDirs = listOf(
                File(projectDir, "app/src/main/res"),
                File(projectDir, "app/src/debug/res"),
                File(projectDir, "app/src/release/res")
            )
            resDirs.filter { it.exists() }.forEach { dir ->
                dir.walkTopDown().filter { it.isFile && it.extension == "xml" }.forEach { file ->
                    try {
                        val content = file.readText()
                        val namePattern = Regex("""name="(\w+)"""")
                        val resType = when {
                            file.path.contains("/values/") -> "values"
                            file.path.contains("/layout/") -> "layout"
                            file.path.contains("/drawable/") -> "drawable"
                            file.path.contains("/mipmap/") -> "mipmap"
                            file.path.contains("/menu/") -> "menu"
                            file.path.contains("/anim/") -> "anim"
                            file.path.contains("/raw/") -> "raw"
                            file.path.contains("/xml/") -> "xml"
                            else -> "other"
                        }
                        namePattern.findAll(content).forEach { match ->
                            val resName = match.groupValues[1]
                            val relPath = file.relativeTo(projectDir).path
                            resourceIndex["$resType:$resName"] = SearchResult(
                                name = "@$resType/$resName",
                                path = relPath,
                                type = ResultType.RESOURCE
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            // Index Gradle tasks
            val commonTasks = listOf(
                "assembleDebug", "assembleRelease", "clean", "build",
                "test", "lint", "connectedAndroidTest", "publish",
                "bundleDebug", "bundleRelease", "installDebug",
                "uninstallAll", "dependencies", "properties"
            )
            commonTasks.forEach { task ->
                gradleTaskIndex[task] = SearchResult(
                    name = task,
                    path = "Gradle",
                    type = ResultType.GRADLE_TASK
                )
            }

            // Check for custom tasks in build.gradle
            val buildFiles = listOf(
                File(projectDir, "app/build.gradle"),
                File(projectDir, "app/build.gradle.kts"),
                File(projectDir, "build.gradle"),
                File(projectDir, "build.gradle.kts")
            )
            buildFiles.filter { it.exists() }.forEach { file ->
                try {
                    val content = file.readText()
                    val taskPattern = Regex("""(?:register|create|named)\s*\(\s*["'](\w+)["']""")
                    taskPattern.findAll(content).forEach { match ->
                        val taskName = match.groupValues[1]
                        gradleTaskIndex[taskName] = SearchResult(
                            name = taskName,
                            path = file.relativeTo(projectDir).path,
                            type = ResultType.GRADLE_TASK
                        )
                    }
                } catch (_: Exception) {}
            }

            indexBuilt = true
        }
    }

    fun reindexFile(file: File, projectDir: File) {
        searchExecutor.execute {
            val relPath = file.relativeTo(projectDir).path
            // Remove old entries for this file
            fileIndex.keys.removeAll { it.startsWith("$relPath:") || it == relPath }
            // Re-index
            if (file.exists() && (file.extension == "kt" || file.extension == "java")) {
                fileIndex[relPath] = SearchResult(
                    name = file.nameWithoutExtension,
                    path = relPath,
                    type = ResultType.FILE
                )
                try {
                    val content = file.readText()
                    val classPattern = Regex("""\b(class|object|interface|enum class)\s+(\w+)""")
                    classPattern.findAll(content).forEach { match ->
                        val className = match.groupValues[2]
                        val line = content.substring(0, match.range.first).count { it == '\n' } + 1
                        fileIndex["$relPath:$className"] = SearchResult(
                            name = className,
                            path = relPath,
                            type = ResultType.CLASS,
                            line = line
                        )
                    }
                    val funPattern = Regex("""\bfun\s+(\w+)""")
                    funPattern.findAll(content).forEach { match ->
                        val funName = match.groupValues[1]
                        val line = content.substring(0, match.range.first).count { it == '\n' } + 1
                        fileIndex["$relPath:$funName"] = SearchResult(
                            name = funName,
                            path = relPath,
                            type = ResultType.FUNCTION,
                            line = line
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun searchContent(query: String, projectDir: File, maxResults: Int = 20): List<SearchResult> {
        if (query.length < 3) return emptyList()
        val results = mutableListOf<SearchResult>()
        val lowerQuery = query.lowercase()
        val srcDirs = listOf(
            File(projectDir, "app/src/main/java"),
            File(projectDir, "app/src/main/kotlin")
        )
        srcDirs.filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.forEach { file ->
                if (results.size >= maxResults) return@forEach
                try {
                    val lines = file.readLines()
                    lines.forEachIndexed { lineIdx, line ->
                        if (results.size >= maxResults) return@forEachIndexed
                        if (line.lowercase().contains(lowerQuery)) {
                            val relPath = file.relativeTo(projectDir).path
                            results.add(SearchResult(
                                name = line.trim().take(80),
                                path = relPath,
                                type = ResultType.FILE,
                                line = lineIdx + 1,
                                preview = line.trim()
                            ))
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return results
    }

    fun dismiss() {
        searchDialog?.dismiss()
    }

    fun destroy() {
        searchExecutor.shutdownNow()
    }
}
