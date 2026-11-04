package com.aide.mobilestudio

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
import java.io.File
import java.util.concurrent.CountDownLatch

class GradleBuildService : Service() {
    companion object {
        private const val CHANNEL_ID = "gradle_background_build"
        private const val NOTIFICATION_ID = 4301
        private const val ACTION_START = "com.aide.mobilestudio.gradle.START"
        private const val ACTION_STOP = "com.aide.mobilestudio.gradle.STOP"
        private const val EXTRA_PROJECT = "project"
        private const val EXTRA_TASKS = "tasks"
        private const val PREFS = "gradle_background_status"

        fun start(context: Context, projectDir: File, tasks: List<String>) {
            val intent = Intent(context, GradleBuildService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROJECT, projectDir.absolutePath)
                .putStringArrayListExtra(EXTRA_TASKS, ArrayList(tasks))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GradleBuildService::class.java).setAction(ACTION_STOP))
        }

        fun status(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("message", "").orEmpty()
    }

    @Volatile private var runner: GradleRunner? = null
    @Volatile private var stopRequested = false
    private lateinit var notificationManager: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        notificationManager = getSystemService(NotificationManager::class.java)
        when (intent?.action) {
            ACTION_STOP -> {
                stopRequested = true
                runner?.cancel()
                update("Stopping Gradle build...", true)
                stopForegroundCompat()
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> Unit
            else -> return START_NOT_STICKY
        }

        if (runner != null) {
            update("Gradle build is already running", true)
            return START_NOT_STICKY
        }

        stopRequested = false
        val projectDir = File(intent.getStringExtra(EXTRA_PROJECT).orEmpty())
        val tasks = intent.getStringArrayListExtra(EXTRA_TASKS).orEmpty().ifEmpty { listOf("assembleDebug") }
        startForeground(NOTIFICATION_ID, buildNotification("Starting ${tasks.joinToString(" ")}", true))
        Thread {
            runBuild(projectDir, tasks)
            stopForegroundCompat()
            stopSelf(startId)
        }.start()
        return START_NOT_STICKY
    }

    private fun runBuild(projectDir: File, tasks: List<String>) {
        val latch = CountDownLatch(1)
        val installer = JreGradleInstaller(
            context = applicationContext,
            onLog = { update(it, true) },
            onProgress = { percent -> update("Preparing build tools... $percent%", true) }
        )
        val gradleRunner = GradleRunner(applicationContext, installer)
        runner = gradleRunner
        update("Build started: ${tasks.joinToString(" ")}", true)
        gradleRunner.run(
            projectDir = projectDir,
            tasks = tasks,
            onLine = { line, level ->
                if (level != LineLevel.DEBUG) {
                    update(line.take(160), true)
                }
            },
            onDone = { result ->
                val message = when {
                    stopRequested -> "Build cancelled"
                    result.success -> "Build finished"
                    else -> "Build finished with errors"
                }
                update(message, false)
                runner = null
                latch.countDown()
            }
        )
        latch.await()
    }

    private fun update(message: String, ongoing: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("message", message)
            .putBoolean("ongoing", ongoing)
            .apply()
        notificationManager.notify(NOTIFICATION_ID, buildNotification(message, ongoing))
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun buildNotification(message: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MobileStudio Gradle")
            .setContentText(message.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(ongoing)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, 0, ongoing)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0,
                "Stop",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, GradleBuildService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
            .apply {
                if (ongoing) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Gradle background builds", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
