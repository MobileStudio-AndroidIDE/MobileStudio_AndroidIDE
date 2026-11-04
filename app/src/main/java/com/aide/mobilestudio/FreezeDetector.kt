package com.aide.mobilestudio

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class FreezeDetector(private val activity: Activity, private val freezeThresholdMs: Long = 4000) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val checkHandler = Handler(Looper.getMainLooper())
    private val pongReceived = AtomicBoolean(true)
    private var running = false
    private var dialogShown = false
    private var currentDialog: AlertDialog? = null
    private var consecutiveFreezes = 0L

    fun start() {
        if (running) return
        running = true
        checkHandler.post(watchLoop)
    }

    fun stop() {
        running = false
        checkHandler.removeCallbacks(watchLoop)
        dismissDialog()
    }

    private val watchLoop = object : Runnable {
        override fun run() {
            if (!running) return
            if (pongReceived.getAndSet(false)) {
                // Main thread was responsive recently; send next ping
                mainHandler.post { pongReceived.set(true) }
                consecutiveFreezes = 0
            } else {
                // Ping wasn't answered — main thread may be stuck
                consecutiveFreezes++
                val frozenSeconds = consecutiveFreezes
                if (!dialogShown && frozenSeconds * 1 >= freezeThresholdMs / 1000) {
                    showFreezeDialog((frozenSeconds * 1).toInt())
                } else if (dialogShown && consecutiveFreezes >= 34) {
                    // Re-show after ~30s of continued freeze
                    showFreezeDialog((frozenSeconds * 1).toInt())
                }
            }
            checkHandler.postDelayed(this, 1000)
        }
    }

    private fun showFreezeDialog(frozenSeconds: Int) {
        mainHandler.post {
            if (activity.isFinishing) return@post
            currentDialog?.dismiss()
            currentDialog = AlertDialog.Builder(activity)
                .setTitle("MobileStudio가 응답하지 않습니다")
                .setMessage("앱이 약 ${frozenSeconds}초 동안 응답하지 않고 있습니다.")
                .setPositiveButton("재시작") { _, _ ->
                    dialogShown = false
                    activity.recreate()
                }
                .setNegativeButton("대기") { _, _ ->
                    dialogShown = false
                }
                .setCancelable(false)
                .show()
            dialogShown = true
        }
    }

    private fun dismissDialog() {
        mainHandler.post {
            currentDialog?.dismiss()
            currentDialog = null
            dialogShown = false
        }
    }
}
