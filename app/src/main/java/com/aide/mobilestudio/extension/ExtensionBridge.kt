package com.aide.mobilestudio.extension

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The bridge between the MobileStudio Core and the Extension API. The core
 * (MainActivity etc.) populates these hooks; extensions reach them ONLY through
 * ExtensionContext, never directly.
 */
object ExtensionBridge {
    @Volatile var currentFile: (() -> File?)? = null
    @Volatile var openFile: ((File) -> Unit)? = null
    @Volatile var insertText: ((String) -> Unit)? = null
    @Volatile var selectedText: (() -> String?)? = null
    @Volatile var projectDir: (() -> File?)? = null
    @Volatile var showToast: ((String) -> Unit)? = null
    @Volatile var notify: ((String, Boolean) -> Unit)? = null
    @Volatile var progress: ((String, Float) -> Unit)? = null
    @Volatile var runCommand: ((String, (String) -> Unit, (Int) -> Unit) -> Unit)? = null
    @Volatile var registerSyntaxHighlighter: ((String, List<String>, String?) -> Unit)? = null
    @Volatile var registerAutocompleteWords: ((String, List<String>) -> Unit)? = null

    /** Banner close listeners (MainActivity shows/hides editor banners). */
    val bannerListeners = CopyOnWriteArrayList<() -> Unit>()
    fun notifyBannersChanged() { bannerListeners.forEach { runCatching { it() } } }
}
