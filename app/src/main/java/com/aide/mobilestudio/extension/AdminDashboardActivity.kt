package com.aide.mobilestudio.extension

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.aide.mobilestudio.StudioUi
import java.io.File

/**
 * AdminDashboardActivity — 관리자 전용 Extension Management.
 *
 * 관리자만 접근한다 (isAdmin()). 기능:
 *  - 전체 확장 / 신고가 많은 확장 보기
 *  - Restore / Keep blocked / Remove (audit log 기록)
 *  - abuse/audit 로그 보기
 */
class AdminDashboardActivity : Activity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(android.content.Intent(context, AdminDashboardActivity::class.java))
        }
    }

    private lateinit var listHost: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StudioUi.applyTheme(com.aide.mobilestudio.UiTheme.resolve(this))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.palette.bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(StudioUi.palette.surface)
            setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingSm, dp(12))
        }
        header.addView(TextView(this).apply {
            text = "Admin Dashboard"
            textSize = 17f
            setTextColor(StudioUi.palette.text)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "Close"
            textSize = 11f
            setOnClickListener { finish() }
        })
        root.addView(header, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(StudioUi.thinDivider(this))
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroller = android.widget.ScrollView(this).apply { addView(listHost) }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        listHost.removeAllViews()
        val marketplace = ExtensionMarketplace.get(this)
        if (!marketplace.isAdmin()) {
            listHost.addView(TextView(this).apply {
                text = "관리자 권한이 필요합니다"
                textSize = 13f
                setTextColor(StudioUi.palette.error)
                setPadding(StudioUi.SpacingLg, dp(16), StudioUi.SpacingLg, dp(16))
            })
            return
        }

        // 신고가 많은 / 차단된 확장
        listHost.addView(sectionText("Reported / Suspended Extensions"))
        val repo = ExtensionManager.get(this).cachedRepo()
        val flagged = repo.filter { marketplace.reportCount(it.id) > 0 || marketplace.isDownloadSuspended(it.id) }
        if (flagged.isEmpty()) {
            listHost.addView(note("플래그된 확장이 없습니다"))
        }
        for (entry in flagged) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingLg, dp(12)) }
            card.addView(TextView(this).apply {
                text = "${entry.name} (${entry.id})"
                textSize = 14f
                setTextColor(StudioUi.palette.text)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
            card.addView(TextView(this).apply {
                text = "Reports (24h): ${marketplace.reportCount(entry.id)}  ·  Suspended: ${marketplace.isDownloadSuspended(entry.id)}\nReason: ${marketplace.suspensionReason(entry.id).ifBlank { "-" }}"
                textSize = 11f
                setTextColor(StudioUi.palette.textSoft)
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setPadding(0, dp(4), 0, 0)
            })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            actions.addView(Button(this).apply {
                text = "Restore"
                textSize = 11f
                setOnClickListener {
                    val err = marketplace.adminResolve(entry.id, "restore")
                    if (err != null) Toast.makeText(this@AdminDashboardActivity, err, Toast.LENGTH_LONG).show()
                    else Toast.makeText(this@AdminDashboardActivity, "Restored", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            })
            actions.addView(Button(this).apply {
                text = "Keep blocked"
                textSize = 11f
                setOnClickListener {
                    val err = marketplace.adminResolve(entry.id, "keep_blocked")
                    if (err != null) Toast.makeText(this@AdminDashboardActivity, err, Toast.LENGTH_LONG).show()
                    refresh()
                }
            })
            actions.addView(Button(this).apply {
                text = "Remove"
                textSize = 11f
                setOnClickListener {
                    val err = marketplace.adminResolve(entry.id, "remove")
                    if (err != null) Toast.makeText(this@AdminDashboardActivity, err, Toast.LENGTH_LONG).show()
                    else Toast.makeText(this@AdminDashboardActivity, "Removed", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            })
            card.addView(actions)
            listHost.addView(LinearLayout(this).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6)); addView(card); background = StudioUi.cardBg(this@AdminDashboardActivity)
            })
        }

        // audit log
        listHost.addView(sectionText("Audit Log"))
        val logFile = java.io.File(filesDir, "extensions/audit.log")
        val audit = TextView(this).apply {
            text = if (logFile.isFile) {
                val lines = logFile.readLines().takeLast(50)
                if (lines.isEmpty()) "(empty)" else lines.joinToString("\n")
            } else "(no audit entries)"
            textSize = 10f
            setTextColor(StudioUi.palette.textSoft)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(StudioUi.SpacingLg, dp(8), StudioUi.SpacingLg, dp(8))
        }
        listHost.addView(audit)
    }

    private fun note(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(StudioUi.palette.textSoft)
        setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingLg, dp(12))
    }

    private fun sectionText(text: String): View = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(StudioUi.palette.accent)
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setPadding(StudioUi.SpacingLg, dp(14), StudioUi.SpacingLg, dp(6))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
