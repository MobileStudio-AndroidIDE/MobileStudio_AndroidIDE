package com.aide.mobilestudio.ai.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aide.mobilestudio.ai.ui.AiWorkspaceActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps a model download alive while the app is in the background.
 *
 * The service owns no download logic: it observes [ModelDownloads] and mirrors
 * the task list into one notification with Pause/Cancel actions. When nothing
 * is active it stops itself. This is the honest ceiling of background work
 * here: a `dataSync` foreground service, with a visible notification while it
 * runs - not a claim that downloads survive a force-stop.
 */
class ModelDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = ModelDownloads.get(this)
        when (intent?.action) {
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let(manager::pause)
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_ID)?.let(manager::cancel)
        }
        // Must happen within seconds of startForegroundService.
        val snapshot = manager.tasks.value
        startForeground(NOTIFICATION_ID, buildNotification(snapshot))
        if (collectJob == null) {
            collectJob = scope.launch {
                manager.tasks.collect { tasks -> updateNotification(tasks) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collectJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateNotification(tasks: List<DownloadTask>) {
        val active = tasks.filter { it.isActive }
        if (active.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(tasks))
    }

    private fun buildNotification(tasks: List<DownloadTask>): Notification {
        val active = tasks.filter { it.isActive }
        val first = active.firstOrNull()
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openWorkspaceIntent())

        if (first == null) {
            builder.setContentTitle("모델 다운로드")
                .setContentText("준비 중…")
                .setProgress(0, 0, true)
        } else {
            val name = first.spec.displayName.ifBlank { "모델" }
            val total = if (first.totalBytes > 0) first.totalBytes else -1L
            val text = if (total > 0) {
                "${DownloadEngine.humanBytes(first.progress)} / ${DownloadEngine.humanBytes(total)}"
            } else {
                DownloadEngine.humanBytes(first.progress)
            }
            builder.setContentTitle("다운로드 중: $name")
                .setContentText(if (active.size > 1) "$text · 외 ${active.size - 1}개" else text)
                .setProgress(
                    if (total > 0) 1000 else 0,
                    if (total > 0) ((first.progress.toDouble() / total) * 1000).toInt() else 0,
                    total <= 0,
                )
                .addAction(
                    android.R.drawable.ic_media_pause,
                    "일시정지",
                    actionIntent(ACTION_PAUSE, first.id, 1),
                )
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "취소",
                    actionIntent(ACTION_CANCEL, first.id, 2),
                )
        }
        return builder.build()
    }

    private fun openWorkspaceIntent(): PendingIntent {
        val intent = Intent(this, AiWorkspaceActivity::class.java)
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(action: String, id: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, ModelDownloadService::class.java).apply {
            this.action = action
            putExtra(EXTRA_ID, id)
        }
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "모델 다운로드", NotificationManager.IMPORTANCE_LOW).apply {
                description = "GGUF 모델 다운로드 진행률을 표시합니다"
            }
        )
    }

    companion object {
        const val CHANNEL_ID = "model_downloads"
        const val NOTIFICATION_ID = 4401
        const val ACTION_PAUSE = "com.aide.mobilestudio.ai.model.PAUSE"
        const val ACTION_CANCEL = "com.aide.mobilestudio.ai.model.CANCEL"
        const val EXTRA_ID = "task_id"

        /** Starts the foreground service for a download that just began. */
        fun start(context: Context, taskId: String) {
            val intent = Intent(context, ModelDownloadService::class.java).apply {
                putExtra(EXTRA_ID, taskId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
