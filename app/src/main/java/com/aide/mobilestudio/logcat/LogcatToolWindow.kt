package com.aide.mobilestudio.logcat

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.TextView
import com.aide.mobilestudio.AppLanguage
import com.aide.mobilestudio.R

/**
 * Logcat Tool Window - logcat tool window.
 * Shows device logcat output with filtering.
 */
class LogcatToolWindow(
    private val host: Activity
) {
    private val collector = LogcatCollector(host)
    private lateinit var listView: ListView
    private lateinit var adapter: ArrayAdapter<String>

    companion object {
        const val ID = "Logcat"
        const val DISPLAY_NAME = "Logcat"
        const val ICON_RES = android.R.drawable.ic_menu_info_details
        const val SHORTCUT = "Alt+6"
    }

    /**
     * Shows the Logcat tool window content with real logcat integration.
     */
    fun createView(): View {
        val layout = LinearLayout(host)
        layout.orientation = LinearLayout.VERTICAL
        layout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Header
        val titleTv = TextView(host)
        titleTv.text = AppLanguage.string(host, R.string.tab_logcat)
        titleTv.textSize = 18f
        titleTv.setPadding(16, 16, 16, 8)
        layout.addView(titleTv)

        // Button row
        val btnRow = LinearLayout(host)
        btnRow.orientation = LinearLayout.HORIZONTAL
        btnRow.setPadding(16, 8, 16, 8)

        val clearBtn = Button(host)
        clearBtn.text = AppLanguage.string(host, R.string.action_clear)
        clearBtn.setOnClickListener {
            collector.clearLogs()
            adapter.notifyDataSetChanged()
        }
        btnRow.addView(clearBtn)

        layout.addView(btnRow)

        // Log list
        listView = ListView(host)
        adapter = ArrayAdapter(host, android.R.layout.simple_list_item_1, collector.getLogs())
        listView.adapter = adapter
        listView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        layout.addView(listView)

        return layout
    }

    fun addLog(log: String) {
        collector.addLog(log)
        adapter.notifyDataSetChanged()
        // Auto-scroll to bottom
        listView.post { listView.setSelection(listView.count - 1) }
    }

    fun getLogs(): List<String> = collector.getLogs()
}
