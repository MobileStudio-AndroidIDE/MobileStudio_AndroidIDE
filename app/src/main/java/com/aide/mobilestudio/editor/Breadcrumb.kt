package com.aide.mobilestudio.editor

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Editor Breadcrumb - file path navigation.
 */
class Breadcrumb(
    private val context: Context
) {
    private val path = mutableListOf<String>()

    fun setPath(path: String) {
        val segments = path.split("/").filter { it.isNotEmpty() }
        this.path.clear()
        this.path.addAll(segments)
    }

    fun getPath(): List<String> = segments
    private val segments = mutableListOf<String>()

    fun createView(): android.widget.LinearLayout {
        val layout = android.widget.LinearLayout(context)
        layout.orientation = android.widget.LinearLayout.HORIZONTAL
        segments.forEach { segment ->
            val tv = android.widget.TextView(context)
            tv.text = segment
            tv.textSize = 12f
            layout.addView(tv)
        }
        return layout
    }
}
