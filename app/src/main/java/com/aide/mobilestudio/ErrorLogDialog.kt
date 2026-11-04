package com.aide.mobilestudio

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Shows a scrollable error log dialog with "Copy all" and "Close" buttons.
 * Replaces toast-based error reporting for better visibility and sharing.
 */
object ErrorLogDialog {
    fun show(context: Context, title: String, log: String) {
        val isKo = AppLanguage.isKorean(context)
        val locale = context.resources.configuration.locales[0]
        
        val textView = TextView(context).apply {
            setTextColor(0xFFB71C1C.toInt())
            setTextIsSelectable(true)
            textSize = 13f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.START
            setPadding(dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, 16), dpToPx(context, 16))
            setText(log)
            setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI)
        }
        val scroll = ScrollView(context).apply { addView(textView) }
        
        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setCancelable(true)
            .setNegativeButton(if (isKo) "닫기" else "Close") { d, _ -> d.dismiss() }
            .setPositiveButton(if (isKo) "모두 복사" else "Copy All") { _, _ ->
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("error log", log))
                Toast.makeText(context, if (isKo) "복사됨" else "Copied", Toast.LENGTH_SHORT).show()
            }
            .create()
            .apply {
                setCanceledOnTouchOutside(true)
                show()
            }
    }
    
    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}