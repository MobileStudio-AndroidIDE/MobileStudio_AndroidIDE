package com.aide.mobilestudio

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.io.File

/**
 * CodeContextMenu - Mobile-optimized context menu for code actions
 * Shows when user long-presses on code in the editor
 */
class CodeContextMenu(private val activity: Activity) {

    data class MenuItem(
        val icon: String,
        val label: String,
        val shortcut: String = "",
        val action: () -> Unit
    )

    fun show(
        anchorView: View,
        symbol: String,
        symbolType: SymbolType,
        file: File?,
        line: Int,
        actions: Map<String, () -> Unit>
    ) {
        val dp = { px: Int -> (px * activity.resources.displayMetrics.density).toInt() }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            setPadding(dp(4), dp(8), dp(4), dp(8))
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = params
        }

        // Header with symbol info
        container.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))

            addView(TextView(activity).apply {
                text = symbolType.icon
                textSize = 18f
                setPadding(0, 0, dp(10), 0)
            })

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

                addView(TextView(activity).apply {
                    text = symbol
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                })

                addView(TextView(activity).apply {
                    text = "${symbolType.label}${if (file != null) " in ${file.name}" else ""}${if (line > 0) ":$line" else ""}"
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    textSize = 11f
                })
            })
        })

        // Divider
        container.addView(View(activity).apply {
            setBackgroundColor(StudioUi.color(StudioUi.Border))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        })

        // Menu items
        val menuItems = buildMenuItems(actions, symbolType)
        menuItems.forEach { item ->
            container.addView(createMenuItemView(item, dp))
        }

        val popup = PopupWindow(container,
            (activity.resources.displayMetrics.widthPixels * 0.85).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        // Show below the anchor or at center
        popup.showAtLocation(anchorView, Gravity.CENTER, 0, 0)
    }

    private fun buildMenuItems(actions: Map<String, () -> Unit>, symbolType: SymbolType): List<MenuItem> {
        val items = mutableListOf<MenuItem>()

        actions["goToDefinition"]?.let {
            items.add(MenuItem("\u2197", "Go to Definition", "Ctrl+B", it))
        }
        actions["goToDeclaration"]?.let {
            items.add(MenuItem("\u2197", "Go to Declaration", "", it))
        }
        actions["findUsages"]?.let {
            items.add(MenuItem("\uD83D\uDD0D", "Find Usages", "Ctrl+B", it))
        }

        if (items.isNotEmpty()) {
            items.add(MenuItem("", "", "", {})) // Divider marker
        }

        actions["rename"]?.let {
            items.add(MenuItem("\u270F\uFE0F", "Rename", "Ctrl+Shift+R", it))
        }
        actions["quickDoc"]?.let {
            items.add(MenuItem("\uD83D\uDCD6", "Quick Documentation", "Ctrl+Q", it))
        }
        actions["copyReference"]?.let {
            items.add(MenuItem(Icons.copy, "Copy Reference", "", it))
        }

        if (items.isNotEmpty()) {
            items.add(MenuItem("", "", "", {})) // Divider marker
        }

        actions["comment"]?.let {
            items.add(MenuItem("\uD83D\uDCAC", "Comment / Uncomment", "Ctrl+/", it))
        }
        actions["format"]?.let {
            items.add(MenuItem("\u2699\uFE0F", "Format Code", "Ctrl+Shift+L", it))
        }
        actions["generate"]?.let {
            items.add(MenuItem("\u2728", "Generate", "Ctrl+Insert", it))
        }
        actions["refactor"]?.let {
            items.add(MenuItem("\uD83D\uDD27", "Refactor", "", it))
        }

        return items
    }

    private fun createMenuItemView(item: MenuItem, dp: (Int) -> Int): View {
        if (item.icon.isEmpty() && item.label.isEmpty()) {
            // Divider
            return View(activity).apply {
                setBackgroundColor(StudioUi.color(StudioUi.Border))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
            }
        }

        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            isFocusable = true

            setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> setBackgroundColor(Color.TRANSPARENT)
                }
                false
            }

            addView(TextView(activity).apply {
                text = item.icon
                textSize = 15f
                setPadding(0, 0, dp(12), 0)
                layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
            })

            addView(TextView(activity).apply {
                text = item.label
                setTextColor(Color.WHITE)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            if (item.shortcut.isNotEmpty()) {
                addView(TextView(activity).apply {
                    text = item.shortcut
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                })
            }

            setOnClickListener { item.action() }
        }
    }

    enum class SymbolType(val icon: String, val label: String) {
        FUNCTION("\u26A1", "Function"),
        CLASS("\uD83D\uDCCB", "Class"),
        INTERFACE("\uD83D\uDCCB", "Interface"),
        OBJECT("\uD83D\uDCCB", "Object"),
        PROPERTY("\uD83D\uDCCA", "Property"),
        VARIABLE("\uD83D\uDCCA", "Variable"),
        RESOURCE("\uD83D\uDDB1", "Resource"),
        UNKNOWN("\u2753", "Symbol")
    }

    private object Icons {
        const val copy = "\uD83D\uDCCB"
    }
}
