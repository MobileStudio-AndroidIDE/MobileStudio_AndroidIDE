package com.aide.mobilestudio.editor

import com.aide.mobilestudio.LineNumberEditText

/**
 * Code Folding - delegates to LineNumberEditText's built-in code folding.
 */
class CodeFolding(
    private val editor: LineNumberEditText
) {
    fun toggleFoldAtLine(line: Int): Boolean = editor.toggleFoldAtLine(line)

    fun foldCurrentBlock() = editor.foldCurrentBlock()

    fun expandAtLine(line: Int) = editor.expandAtLine(line)

    fun expandAll() = editor.expandAll()

    fun foldAllBlocks() = editor.foldAllBlocks()

    fun clearFolding() = editor.clearFolding()

    fun isLineCollapsed(line: Int) = editor.isLineCollapsed(line)

    fun hasCollapsedBlocks() = editor.hasCollapsedBlocks()
}
