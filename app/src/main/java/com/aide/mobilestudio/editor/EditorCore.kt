package com.aide.mobilestudio.editor

import android.content.Context
import com.aide.mobilestudio.LineNumberEditText

/**
 * Editor Core - wraps LineNumberEditText as the core editor view.
 */
class EditorCore(
    private val context: Context
) {
    private val editor = LineNumberEditText(context)

    fun setText(text: String) {
        editor.setText(text)
    }

    fun getText(): String {
        return editor.text.toString()
    }

    fun createView(): LineNumberEditText {
        return editor
    }

    fun getEditor(): LineNumberEditText {
        return editor
    }

    fun undo() {
        editor.undo()
    }

    fun redo() {
        editor.redo()
    }

    fun setInlayHints(hints: List<LineNumberEditText.InlayHint>) {
        editor.setInlayHints(hints)
    }

    fun setCodeLenses(lenses: List<LineNumberEditText.CodeLens>) {
        editor.setCodeLenses(lenses)
    }
}
