package com.aide.mobilestudio.extension

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * OAuth deep link target: mobilestudio://ext-auth#session=<token>&expiresIn=<s>
 * The session token arrives in the URL *fragment* so it never appears in
 * server logs or browser history. Saves the session and closes immediately.
 */
class ExtAuthCallbackActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val fragment = intent?.data?.fragment ?: ""
        val params = fragment.split("&").mapNotNull { part ->
            val i = part.indexOf('=')
            if (i > 0) part.substring(0, i) to Uri.decode(part.substring(i + 1)) else null
        }.toMap()
        val session = params["session"]
        if (session.isNullOrBlank()) {
            Toast.makeText(this, "GitHub 로그인 실패: 세션을 받지 못했습니다", Toast.LENGTH_LONG).show()
        } else {
            val expiresIn = params["expiresIn"]?.toLongOrNull() ?: 0L
            ExtensionServerClient.saveSession(this, session, expiresIn)
            Toast.makeText(this, "GitHub 로그인 완료", Toast.LENGTH_SHORT).show()
            ExtensionServerClient.refreshMe(this) { _, _ -> }
        }
        finish()
    }
}
