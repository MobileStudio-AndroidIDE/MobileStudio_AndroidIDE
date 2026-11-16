package com.aide.mobilestudio.editor

import com.aide.mobilestudio.LineNumberEditText

/**
 * Code Formatter - provides code formatting for the editor.
 */
class CodeFormatter {
    /**
     * Formats the given code. Currently performs basic cleanup:
     * - Trims trailing whitespace
     * - Normalizes line endings
     * - Removes duplicate blank lines
     */
    fun format(code: String, language: String): String {
        val lines = code.split("\n").map { it.trimEnd() }
        val result = mutableListOf<String>()
        var prevBlank = false
        for (line in lines) {
            val isBlank = line.isBlank()
            if (!isBlank || !prevBlank) {
                result.add(line)
            }
            prevBlank = isBlank
        }
        return result.joinToString("\n")
    }
}
