package com.aide.mobilestudio.editor

import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Editor Core - core editor functionality.
 */
class EditorCore(
    private val context: Context
) {
    private val editor = android.widget.EditText(context)

    fun setText(text: String) {
        editor.setText(text)
    }

    fun getText(): String {
        return editor.text.toString()
    }

    fun createView(): android.widget.EditText {
        return editor
    }
}
