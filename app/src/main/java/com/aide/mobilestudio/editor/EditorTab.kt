package com.aide.mobilestudio.editor

data class EditorTab(
    val id: String,
    val filePath: String,
    val fileName: String,
    val content: String = "",
    val isModified: Boolean = false,
    val isPinned: Boolean = false,
    val isPreview: Boolean = false,
    val cursorPosition: Pair<Int, Int> = Pair(1, 1)
)
