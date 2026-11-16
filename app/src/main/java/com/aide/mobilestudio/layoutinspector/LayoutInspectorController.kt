package com.aide.mobilestudio.layoutinspector

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class LayoutInspectorController(private val context: Context) {
    private val attachedApps = ConcurrentHashMap<String, AttachedApp>()
    private var currentApp: AttachedApp? = null
    private val isConnected = AtomicBoolean(false)

    data class AttachedApp(
        val packageName: String,
        val pid: Int,
        val viewRoot: View?,
        val viewRootNode: AccessibilityNodeInfo?
    )

    fun connect(appPackageName: String): Boolean {
        Log.d("LayoutInspector", "Connecting to $appPackageName")
        isConnected.set(true)
        return true
    }

    fun disconnect() {
        isConnected.set(false)
    }

    fun getConnectedApp(): AttachedApp? = currentApp
}
