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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Background AI & Command Service.
 *
 * Keeps AI inference and shell command execution running even when the app
 * is in the background or the user has closed the UI. Runs as a foreground
 * service with a persistent notification.
 *
 * Work items are queued and executed sequentially on a single background
 * thread so the UI never blocks and results are persisted to disk.
 */
class AIBackgroundService : Service() {

    private lateinit var notificationManager: NotificationManager
    private val commandQueue = ConcurrentLinkedQueue<String>()
    private val aiQueue = ConcurrentLinkedQueue<String>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    private val CHANNEL_ID = "mobilestudio_ai_bg"
    private val CHANNEL_NAME = "MobileStudio AI Background"
    private val NOTIFICATION_ID = 1001

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Background AI ready", "AI and shell commands are running"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            val action = it.action
            when (action) {
                "com.aide.mobilestudio.action.RUN_COMMAND" -> {
                    val cmd = it.getStringExtra("command") ?: return START_STICKY
                    val cwd = it.getStringExtra("cwd") ?: ""
                    commandQueue.add("$cmd||$cwd")
                }
                "com.aide.mobilestudio.action.AI_MESSAGE" -> {
                    val msg = it.getStringExtra("message") ?: return START_STICKY
                    aiQueue.add(msg)
                }
                "com.aide.mobilestudio.action.STOP_SERVICE" -> {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdown()
        stopForeground(true)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs AI and shell commands in the background"
                enableLights(false)
                enableVibration(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, contentTitle: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    init {
        executor.submit {
            while (!executor.isShutdown) {
                // Process shell commands
                val cmd = commandQueue.poll()
                if (cmd != null) {
                    val parts = cmd.split("||", limit = 2)
                    val command = parts[0]
                    val cwd = if (parts.size > 1) parts[1] else ""
                    runShellInBackground(command, cwd)
                    continue
                }

                // Process AI messages
                val msg = aiQueue.poll()
                if (msg != null) {
                    runAIInBackground(msg)
                    continue
                }

                // Sleep briefly when idle
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun runShellInBackground(command: String, cwd: String) {
        try {
            val dir = if (cwd.isNotBlank()) File(cwd) else filesDir
            val env = runCatching { com.aide.mobilestudio.ShellEnvironment.buildToolEnvironment(this@AIBackgroundService, dir) }
                .getOrDefault(emptyMap())
            val result = com.aide.mobilestudio.ShellRunner.runSh(
                this@AIBackgroundService, command, dir, env, 300_000
            ) { line ->
                appendToBackgroundLog("[CMD] $line")
            }
            appendToBackgroundLog("[EXIT ${result.exitCode}] $command")
            if (result.error.isNotBlank()) {
                appendToBackgroundLog("[ERROR] ${result.error}")
            }
        } catch (e: Exception) {
            appendToBackgroundLog("[EXCEPTION] ${e.message}")
        }
    }

    private fun runAIInBackground(message: String) {
        try {
            val engine = com.aide.mobilestudio.ai.opencode.agent.OcEngine(applicationContext)
            engine.init()
            val result = engine.sendMessage(message)
            appendToBackgroundLog("[AI] $message")
                appendToBackgroundLog("[AI_RESPONSE] processed")
        } catch (e: Exception) {
            appendToBackgroundLog("[AI_ERROR] ${e.message}")
        }
    }

    private fun appendToBackgroundLog(text: String) {
        try {
            val logFile = File(filesDir, "background_ai.log")
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
            logFile.appendText("[$timestamp] $text\n")
        } catch (_: Exception) {}
    }

    companion object {
        private const val ACTION_RUN_COMMAND = "com.aide.mobilestudio.action.RUN_COMMAND"
        private const val ACTION_AI_MESSAGE = "com.aide.mobilestudio.action.AI_MESSAGE"
        private const val ACTION_STOP_SERVICE = "com.aide.mobilestudio.action.STOP_SERVICE"

        fun startService(context: Context) {
            context.startIntentService(Intent(context, AIBackgroundService::class.java))
        }

        fun runCommand(context: Context, command: String, cwd: String) {
            val intent = Intent(context, AIBackgroundService::class.java).apply {
                action = ACTION_RUN_COMMAND
                putExtra("command", command)
                putExtra("cwd", cwd)
            }
            context.startService(intent)
        }

        fun sendAIMessage(context: Context, message: String) {
            val intent = Intent(context, AIBackgroundService::class.java).apply {
                action = ACTION_AI_MESSAGE
                putExtra("message", message)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AIBackgroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}

/** Helper to start the service from any context. */
private fun Context.startIntentService(intent: Intent) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}
