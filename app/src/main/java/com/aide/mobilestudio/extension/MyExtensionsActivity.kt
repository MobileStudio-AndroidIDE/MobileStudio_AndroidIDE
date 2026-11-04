package com.aide.mobilestudio.extension

import android.app.Activity
import android.content.Context
import android.graphics.Color
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

/**
 * MyExtensionsActivity — Extension Hub "My Extensions".
 *
 * 표시: 내 확장의 이름/버전/검사 상태/진행률(예상 시간은 실측 기반,
 * 확실하지 않으면 estimated/unknown 명시)/다운로드 수/좋아요 수/후기 수/
 * 신고 수/공개 여부.
 */
class MyExtensionsActivity : Activity() {
    companion object {
        fun start(context: Context) {
            context.startActivity(android.content.Intent(context, MyExtensionsActivity::class.java))
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
            text = "My Extensions"
            textSize = 17f
            setTextColor(StudioUi.palette.text)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(Button(this).apply {
            text = "Refresh"
            textSize = 11f
            setOnClickListener { refresh() }
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
        refresh()
    }

    private fun refresh() {
        listHost.removeAllViews()
        val account = ExtensionAccount.get(this)
        val profile = account.current()
        if (profile == null) {
            listHost.addView(note("Gmail 로그인이 필요합니다. Extensions 화면의 Login 버튼을 사용하세요."))
            return
        }
        ExtensionUploadQueue.get(this).restore()
        val queue = ExtensionUploadQueue.get(this)
        val marketplace = ExtensionMarketplace.get(this)

        // 내 업로드 (대기열 상태 + 진행률)
        val uploads = queue.myUploads(profile.displayName)
        listHost.addView(sectionText("Uploads / Scan Status (${uploads.size})"))
        if (uploads.isEmpty()) {
            listHost.addView(note("업로드한 확장이 없습니다. Extensions 화면에서 Upload Extension을 사용하세요."))
        }
        for (item in uploads) {
            listHost.addExtensionCard(item, queue, marketplace)
        }

        // 통계
        listHost.addView(sectionText("Statistics"))
        val stats = TextView(this).apply {
            text = buildString {
                append("Downloads total: ${sumDownloads(marketplace)}\n")
                append("Likes given: ${account.likedExtensions().size}\n")
                append("Installed extensions: ${account.installedExtensions().size}\n")
                append("Reviews written: ${account.myExtensions().size} extensions")
            }
            textSize = 12f
            setTextColor(StudioUi.palette.text)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(StudioUi.SpacingLg, dp(8), StudioUi.SpacingLg, dp(8))
        }
        listHost.addView(stats)

        // 관리자 (일반 사용자에게 노출하지 않음)
        if (marketplace.isAdmin()) {
            listHost.addView(sectionText("Admin"))
            listHost.addView(Button(this).apply {
                text = "Open Admin Dashboard"
                textSize = 11f
                setOnClickListener { AdminDashboardActivity.start(this@MyExtensionsActivity) }
            })
        }
    }

    private fun sumDownloads(marketplace: ExtensionMarketplace): Int =
        ExtensionAccount.get(this).myExtensions().sumOf { marketplace.downloadCount(it) }

    private fun LinearLayout.addExtensionCard(
        item: ExtensionUploadQueue.UploadItem,
        queue: ExtensionUploadQueue,
        marketplace: ExtensionMarketplace
    ) {
        val card = LinearLayout(this@MyExtensionsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingLg, dp(12))
        }
        card.addView(TextView(this@MyExtensionsActivity).apply {
            text = "${item.name} v${item.version}"
            textSize = 15f
            setTextColor(StudioUi.palette.text)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        })
        val statusColor = when (item.status) {
            ExtensionUploadQueue.Status.Published, ExtensionUploadQueue.Status.Passed, ExtensionUploadQueue.Status.Approved -> StudioUi.palette.success
            ExtensionUploadQueue.Status.Failed, ExtensionUploadQueue.Status.Rejected -> StudioUi.palette.error
            ExtensionUploadQueue.Status.ManualReview, ExtensionUploadQueue.Status.Warning -> StudioUi.palette.warning
            else -> StudioUi.palette.accent
        }
        card.addView(TextView(this@MyExtensionsActivity).apply {
            text = queue.statusText(item).replace("\n", "\n")
            textSize = 11f
            setTextColor(statusColor)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, dp(4), 0, 0)
        })
        // 검사 단계 진행률
        card.addView(TextView(this@MyExtensionsActivity).apply {
            val sb = StringBuilder()
            for ((i, stage) in ExtensionUploadQueue.SCAN_STAGES.withIndex()) {
                val mark = when {
                    i < item.currentStage -> "[✓]"
                    i == item.currentStage && item.status == ExtensionUploadQueue.Status.Scanning -> "[▶]"
                    else -> "[ ]"
                }
                sb.append(if (i > 0) "\n" else "").append(mark).append(" ").append(stage)
            }
            text = sb.toString()
            textSize = 10f
            setTextColor(StudioUi.palette.textSoft)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, dp(6), 0, 0)
        })
        card.addView(TextView(this@MyExtensionsActivity).apply {
            text = "Downloads: ${marketplace.downloadCount(item.id)}  ·  Likes: ${marketplace.likeCount(item.id)}  ·  Reports: ${marketplace.reportCount(item.id)}"
            textSize = 11f
            setTextColor(StudioUi.palette.textSoft)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(0, dp(6), 0, 0)
        })
        val actions = LinearLayout(this@MyExtensionsActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        actions.addView(Button(this@MyExtensionsActivity).apply {
            text = "Cancel"
            textSize = 11f
            setOnClickListener {
                ExtensionUploadQueue.get(this@MyExtensionsActivity).cancel(item.id, item.version)
                refresh()
            }
        })
        card.addView(actions)
        addView(LinearLayout(this@MyExtensionsActivity).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6)); addView(card); background = StudioUi.cardBg(this@MyExtensionsActivity)
        })
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
