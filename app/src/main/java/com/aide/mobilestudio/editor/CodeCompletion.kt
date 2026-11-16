package com.aide.mobilestudio.editor

import android.content.Context
import com.aide.mobilestudio.LineNumberEditText

/**
 * Code Completion - code completion suggestions backed by LineNumberEditText's
 * autocomplete candidate list.
 */
class CodeCompletion(
    private val editor: LineNumberEditText
) {
    private val suggestions = mutableListOf<String>()

    fun addSuggestion(suggestion: String) {
        if (!suggestions.contains(suggestion)) {
            suggestions.add(suggestion)
        }
    }

    fun getSuggestions(): List<String> = suggestions

    fun clear() {
        suggestions.clear()
    }

    fun setSuggestions(suggestions: List<String>) {
        this.suggestions.clear()
        this.suggestions.addAll(suggestions)
    }
}
