package com.aide.mobilestudio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.util.concurrent.ConcurrentHashMap

/**
 * CommandPalette - Quick access to all IDE actions (like VS Code Ctrl+Shift+P / Android Studio Shift+Shift Actions)
 */
class CommandPalette(private val activity: Activity) {

    data class Action(
        val id: String,
        val name: String,
        val category: String,
        val shortcut: String = "",
        val icon: String = "",
        val action: () -> Unit
    )

    private val actions = mutableListOf<Action>()
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    init {
        registerDefaultActions()
    }

    fun registerAction(action: Action) {
        actions.add(action)
    }

    fun registerAction(id: String, name: String, category: String, shortcut: String = "", icon: String = "", action: () -> Unit) {
        actions.add(Action(id, name, category, shortcut, icon, action))
    }

    fun show() {
        val dp = { px: Int -> (px * activity.resources.displayMetrics.density).toInt() }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Card))
            setPadding(dp(16), dp(12), dp(16), dp(8))
        }

        val searchInput = EditText(activity).apply {
            hint = AppLanguage.literal(activity, "Type a command...")
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

        val resultContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        val resultScroll = ScrollView(activity).apply {
            addView(resultContainer)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f
            )
        }

        container.addView(searchInput)
        container.addView(resultScroll)

        var currentCategory = "All"
        val categoryRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }

        val categories = listOf("All", "Build", "Run", "Navigate", "Edit", "View", "Git", "AI", "Settings")
        val categoryButtons = mutableListOf<Button>()
        categories.forEach { cat ->
            val btn = Button(activity).apply {
                text = cat
                textSize = 10f
                setTextColor(StudioUi.color(if (cat == "All") StudioUi.Text else StudioUi.TextSoft))
                background = StudioUi.rounded(
                    activity,
                    fill = if (cat == "All") StudioUi.Surface2 else StudioUi.Card,
                    stroke = if (cat == "All") StudioUi.Blue else StudioUi.Border,
                    radiusDp = StudioUi.RadiusMd
                )
                setPadding(dp(8), dp(2), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.rightMargin = dp(4) }
                setOnClickListener {
                    currentCategory = cat
                    categoryButtons.forEach { b ->
                        b.setTextColor(StudioUi.color(StudioUi.TextSoft))
                        b.background = StudioUi.rounded(activity, fill = StudioUi.Card, stroke = StudioUi.Border, radiusDp = StudioUi.RadiusMd)
                    }
                    setTextColor(StudioUi.color(StudioUi.Text))
                    background = StudioUi.rounded(activity, fill = StudioUi.Surface2, stroke = StudioUi.Blue, radiusDp = StudioUi.RadiusMd)
                    filterActions(searchInput.text.toString(), currentCategory, resultContainer)
                }
            }
            categoryButtons.add(btn)
            categoryRow.addView(btn)
        }

        container.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(categoryRow)
        }, 1) // Insert after searchInput
        searchInput.addTextChangedListener(object : TextWatcher {
            private var debounceRunnable: Runnable? = null
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                debounceRunnable?.let { handler.removeCallbacks(it) }
                debounceRunnable = Runnable {
                    filterActions(s?.toString() ?: "", currentCategory, resultContainer)
                }
                handler.postDelayed(debounceRunnable!!, 150)
            }
        })

        dialog = AlertDialog.Builder(activity)
            .setView(container)
            .create()

        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.65).toInt()
            )
            setGravity(Gravity.CENTER)
        }

        dialog?.show()

        searchInput.post {
            searchInput.requestFocus()
            val imm = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }

        // Show all actions initially
        filterActions("", "All", resultContainer)
    }

    private fun filterActions(query: String, category: String, container: LinearLayout) {
        container.removeAllViews()
        val dp = { px: Int -> (px * activity.resources.displayMetrics.density).toInt() }
        val lowerQuery = query.lowercase()

        val filtered = actions.filter { action ->
            val matchesCategory = category == "All" || action.category == category
            val matchesQuery = lowerQuery.isEmpty() ||
                action.name.lowercase().contains(lowerQuery) ||
                action.category.lowercase().contains(lowerQuery) ||
                action.id.lowercase().contains(lowerQuery)
            matchesCategory && matchesQuery
        }.sortedWith(compareBy<Action> {
            if (lowerQuery.isEmpty()) 0
            else if (it.name.lowercase().startsWith(lowerQuery)) 0
            else if (it.name.lowercase().contains(lowerQuery)) 1
            else 2
        }.thenBy { it.name }).take(30)

        if (filtered.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = AppLanguage.literal(activity, "No matching commands")
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                textSize = 13f
                setPadding(dp(16), dp(16), dp(16), dp(16))
            })
            return
        }

        filtered.forEach { action ->
            container.addView(createActionRow(action, dp))
        }
    }

    private fun createActionRow(action: Action, dp: (Int) -> Int): View {
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
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
            if (action.icon.isNotEmpty()) {
                addView(TextView(activity).apply {
                    text = action.icon
                    textSize = 14f
                    setPadding(0, 0, dp(8), 0)
                })
            }

            // Name
            addView(TextView(activity).apply {
                text = action.name
                setTextColor(StudioUi.color(StudioUi.Text))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // Category badge
            addView(TextView(activity).apply {
                text = action.category
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                textSize = 10f
                setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                setPadding(dp(6), dp(2), dp(6), dp(2))
            })

            // Shortcut
            if (action.shortcut.isNotEmpty()) {
                addView(TextView(activity).apply {
                    text = action.shortcut
                    setTextColor(StudioUi.color(StudioUi.Muted))
                    textSize = 10f
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(8), 0, 0, 0)
                })
            }

            setOnClickListener {
                dialog?.dismiss()
                action.action()
            }
        }
    }

    private fun registerDefaultActions() {
        // Build actions
        registerAction("build_assemble", "Build APK", "Build", "Ctrl+B", "\uD83D\uDEE0") {}
        registerAction("build_clean", "Clean Project", "Build", "", "\uD83E\uDDF9") {}
        registerAction("build_rebuild", "Rebuild Project", "Build", "", "\uD83D\uDD04") {}
        registerAction("build_sync", "Sync Gradle Project", "Build", "Ctrl+Shift+Y", "\uD83D\uDD04") {}
        registerAction("build_bundle", "Build Bundle (AAB)", "Build", "", "\uD83D\uDCE6") {}
        registerAction("build_variant", "Select Build Variant", "Build", "", "\uD83D\uDCCB") {}

        // Run actions
        registerAction("run_app", "Run App", "Run", "Shift+F10", "\u25B6") {}

        // Navigate actions
        registerAction("nav_search", "Search Everywhere", "Navigate", "Shift+Shift", "\uD83D\uDD0D") {}
        registerAction("nav_goto", "Go to Definition", "Navigate", "Ctrl+B", "\u2197") {}
        registerAction("nav_file", "Go to File", "Navigate", "Ctrl+N", "\uD83D\uDCC4") {}
        registerAction("nav_class", "Go to Class", "Navigate", "Ctrl+N", "\uD83D\uDCCB") {}
        registerAction("nav_symbol", "Go to Symbol", "Navigate", "Ctrl+Shift+N", "\u26A1") {}
        registerAction("nav_structure", "Structure Outline", "Navigate", "Ctrl+O", "\uD83D\uDCCB") {}

        // Edit actions
        registerAction("edit_find", "Find", "Edit", "Ctrl+F", "\uD83D\uDD0D") {}
        registerAction("edit_format", "Format Code", "Edit", "Ctrl+Shift+L", "\u2699\uFE0F") {}
        registerAction("edit_comment", "Toggle Comment", "Edit", "Ctrl+/", "\uD83D\uDCAC") {}
        registerAction("edit_duplicate", "Duplicate Line", "Edit", "Ctrl+D", "\uD83D\uDCB0") {}
        registerAction("edit_undo", "Undo", "Edit", "Ctrl+Z", "\u21A9") {}
        registerAction("edit_redo", "Redo", "Edit", "Ctrl+Shift+Z", "\u21AA") {}
        registerAction("edit_selectall", "Select All", "Edit", "Ctrl+A", "\uD83C\uDF10") {}
        registerAction("edit_delete_line", "Delete Line", "Edit", "Ctrl+Y", "\uD83D\uDDD1") {}

        // View actions
        registerAction("view_toggle_softwrap", "Toggle Soft Wrap", "View", "", "\u2B50") {}
        registerAction("view_toggle_fullscreen", "Toggle Fullscreen", "View", "F11", "\u26F6") {}
        registerAction("view_zoom_in", "Zoom In", "View", "Ctrl+=", "\uD83D\uDD0D") {}
        registerAction("view_zoom_out", "Zoom Out", "View", "Ctrl+-", "\uD83D\uDD0D") {}

        // Git actions
        registerAction("git_status", "Git Status", "Git", "", "\uD83D\uDCCB") {}
        registerAction("git_commit", "Git Commit", "Git", "", "\u2705") {}
        registerAction("git_diff", "Git Diff", "Git", "", "\uD83D\uDCC8") {}
        registerAction("git_log", "Git Log", "Git", "", "\uD83D\uDCDC") {}

        // AI actions
        registerAction("ai_chat", "AI Chat", "AI", "", "\uD83E\uDD16") {}

        // Settings actions
        registerAction("settings", "IDE Settings", "Settings", "", "\u2699\uFE0F") {}
    }

    /**
     * Wire actions to actual implementations. Call this after MainActivity is ready.
     */
    fun wireActions(mainActivity: MainActivity) {
        updateAction("build_assemble") { mainActivity.runGradleTask("assembleDebug") }
        updateAction("build_clean") { mainActivity.runGradleTask("clean") }
        updateAction("build_rebuild") { mainActivity.runGradleTask("clean", "assembleDebug") }
        updateAction("build_sync") { mainActivity.runGradleSyncNow() }
        updateAction("build_bundle") { mainActivity.runGradleTask("bundleDebug") }
        updateAction("run_app") { mainActivity.runGradleTask("installDebug") }
        updateAction("nav_search") { mainActivity.showSearchEverywhere() }
        updateAction("nav_goto") {
            val cursor = mainActivity.codeEditor.selectionStart
            mainActivity.goToSymbolAtOffset(cursor)
        }
        updateAction("nav_file") { mainActivity.showSearchEverywhere("Files") }
        updateAction("nav_class") { mainActivity.showSearchEverywhere("Classes") }
        updateAction("nav_symbol") { mainActivity.showSearchEverywhere("Functions") }
        updateAction("nav_structure") { mainActivity.showStructureOutline() }
        updateAction("build_variant") { mainActivity.showVariantSelector() }
        updateAction("edit_find") { mainActivity.toggleFindReplace() }
        updateAction("edit_format") { mainActivity.formatCurrentFile() }
        updateAction("edit_comment") { mainActivity.toggleComment() }
        updateAction("edit_duplicate") { mainActivity.duplicateCurrentLine() }
        updateAction("edit_undo") { mainActivity.codeEditor.undo() }
        updateAction("edit_redo") { mainActivity.codeEditor.redo() }
        updateAction("edit_selectall") { mainActivity.codeEditor.selectAll() }
        updateAction("edit_delete_line") { mainActivity.codeEditor.deleteCurrentLine() }
        updateAction("view_toggle_softwrap") { mainActivity.toggleWordWrap() }
        updateAction("view_toggle_fullscreen") { mainActivity.toggleFullscreen() }
        updateAction("view_zoom_in") {
            val editor = mainActivity.codeEditor
            editor.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                (editor.textSize * 1.1f).coerceAtMost(48f)
            )
        }
        updateAction("view_zoom_out") {
            val editor = mainActivity.codeEditor
            editor.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                (editor.textSize * 0.9f).coerceAtLeast(8f)
            )
        }
        updateAction("git_status") {
            val dir = mainActivity.currentProjectDir ?: return@updateAction
            mainActivity.gitManager.showGitDialog(dir)
        }
        updateAction("git_commit") {
            val dir = mainActivity.currentProjectDir ?: return@updateAction
            mainActivity.gitManager.showCommitDialog(dir)
        }
        updateAction("git_diff") {
            val dir = mainActivity.currentProjectDir ?: return@updateAction
            mainActivity.gitManager.showDiffDialog(dir)
        }
        updateAction("git_log") {
            val dir = mainActivity.currentProjectDir ?: return@updateAction
            mainActivity.gitManager.showGitDialog(dir)
        }
        updateAction("settings") {
            val intent = android.content.Intent(mainActivity, ProjectSettingsActivity::class.java)
            mainActivity.startActivity(intent)
        }
        updateAction("ai_chat") {
            mainActivity.openAiWorkspace()
        }
    }

    private fun updateAction(id: String, action: () -> Unit) {
        val idx = actions.indexOfFirst { it.id == id }
        if (idx >= 0) {
            val old = actions[idx]
            actions[idx] = old.copy(action = action)
        }
    }

    fun destroy() {
        dialog?.dismiss()
        actions.clear()
    }
}
