package com.aide.mobilestudio.buildtoolwindow

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aide.mobilestudio.MainActivity

/**
 * Build Notifications - shows system notifications for build completion/failure
 */
class BuildNotifications(private val context: Context) {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val channelId = "build_notifications"
    private var notificationId = 1000

    companion object {
        const val ACTION_OPEN_OUTPUT = "com.aide.mobilestudio.OPEN_BUILD_OUTPUT"
        const val ACTION_RERUN_BUILD = "com.aide.mobilestudio.RERUN_BUILD"
        const val ACTION_VIEW_ISSUES = "com.aide.mobilestudio.VIEW_ISSUES"
        const val EXTRA_PROJECT_DIR = "project_dir"
        const val EXTRA_SUCCESS = "success"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Build Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for build completion, failure, and sync status"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Notify build completion (success or failure)
     */
    fun notifyBuildComplete(success: Boolean, durationMs: Long, projectDir: String? = null) {
        val durationFormatted = formatDuration(durationMs)
        val title = if (success) "Build Successful" else "Build Failed"
        val contentText = if (success) "Completed in $durationFormatted" else "Build failed after $durationFormatted"

        val intent = android.content.Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_PROJECT_DIR, projectDir)
            putExtra(EXTRA_SUCCESS, success)
        }

        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(contentText)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)

        notificationManager.notify(notificationId, builder.build())
        notificationId++
    }

    private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return if (minutes > 0) "${minutes}m ${remainingSeconds}s" else "${remainingSeconds}s"
    }
}
