package com.aide.mobilestudio.godot

import android.content.Context
import java.io.File
import java.lang.reflect.Method

object GodotMobileBridge {
    private const val EDITOR_UTILS = "org.godotengine.godot.editor.utils.EditorUtils"
    private const val GAME_MENU_UTILS = "org.godotengine.godot.editor.utils.GameMenuUtils"

    fun isNativeLibraryAvailable(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir, "libgodot_android.so").isFile

    fun save(): Boolean = invokeEditor("mobileStudioSave")

    fun playMain(): Boolean {
        if (invokeEditor("mobileStudioPlayMain")) return true
        return invokeStatic(GAME_MENU_UTILS, "playMainScene")
    }

    fun stop(): Boolean = invokeEditor("mobileStudioStop")

    fun selectMainScreen(index: Int): Boolean = invokeEditor(
        "mobileStudioSelectMainScreen",
        arrayOf<Class<*>>(Int::class.javaPrimitiveType!!),
        arrayOf<Any?>(index)
    )

    fun focusDock(key: String): Boolean = invokeEditor(
        "mobileStudioFocusDock",
        arrayOf<Class<*>>(String::class.java),
        arrayOf<Any?>(key)
    )

    fun applyMobileLayout(): Boolean = invokeEditor("mobileStudioApplyMobileLayout")

    fun toggleTitleBar(visible: Boolean): Boolean = invokeEditor(
        "toggleTitleBar",
        arrayOf<Class<*>>(Boolean::class.javaPrimitiveType!!),
        arrayOf<Any?>(visible)
    )

    private fun invokeEditor(
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        args: Array<Any?> = emptyArray()
    ): Boolean = invokeStatic(EDITOR_UTILS, name, parameterTypes, args)

    private fun invokeStatic(
        className: String,
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        args: Array<Any?> = emptyArray()
    ): Boolean {
        val method: Method = runCatching {
            Class.forName(className).getMethod(name, *parameterTypes)
        }.getOrNull() ?: return false
        return runCatching {
            method.invoke(null, *args)
            true
        }.getOrDefault(false)
    }
}
