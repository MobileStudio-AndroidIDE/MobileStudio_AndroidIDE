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
import com.aide.mobilestudio.ShellRunner.Result
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground Service for long-running shell jobs */
class ShellJobService : Service() {
    companion object {
        const val ACTION_START = "com.aide.mobilestudio.shelljob.START"
        const val ACTION_STOP = "com.aide.mobilestudio.shelljob.STOP"
        const val ACTION_PAUSE = "com.aide.mobilestudio.shelljob.PAUSE"
        const val ACTION_RESUME = "com.aide.mobilestudio.shelljob.RESUME"
        const val EXTRA_JOB_ID = "job_id"
        
        private const val CHANNEL_ID = "shell_background_jobs"
        private const val NOTIFICATION_ID_BASE = 5000
        
        private const val PREFS = "shell_jobs"
    }
    
    @Volatile private var currentJobId: Long = -1
    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    private lateinit var notificationManager: NotificationManager
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        notificationManager = getSystemService(NotificationManager::class.java)
        
        val action = intent?.action ?: return START_NOT_STICKY
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, -1)
        
        when (action) {
            ACTION_STOP -> {
                stopRequested = true
                pauseRequested = false
                currentJobId = jobId
                val manager = shellJobManager
                val job = manager.getJob(jobId)
                if (job != null) {
                    updateNotification(job.copy(state = JobState.CANCELLED, progressMessage = "Stopping..."), true)
                }
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseRequested = true
                currentJobId = jobId
                val manager = shellJobManager
                val job = manager.getJob(jobId)
                if (job != null) {
                    updateNotification(job.copy(state = JobState.PAUSED, progressMessage = "Paused"), true)
                }
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                pauseRequested = false
                currentJobId = jobId
                // Resume will be handled in the run loop
                return START_NOT_STICKY
            }
            ACTION_START -> {
                currentJobId = jobId
                stopRequested = false
                pauseRequested = false
                Thread {
                    runJob(jobId)
                    stopForegroundCompat()
                    stopSelf(startId)
                }.start()
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }
    
    private fun runJob(jobId: Long) {
        val manager = shellJobManager
        val job = manager.getJob(jobId) ?: return
        
        val updated = job.copy(state = JobState.RUNNING, progressMessage = "Starting...")
        manager.updateProgress(jobId, 0, "Starting...")
        updateNotification(updated, true)
        
        val cwd = File(job.workingDir)
        val env = job.env
        
        try {
            when (job.type) {
                JobType.DOWNLOAD -> runDownload(job)
                JobType.GRADLE -> runGradle(job)
                else -> runShellCommand(job)
            }
        } catch (e: Exception) {
            val stdout = listOf("Exception: ${e.message}")
            val stderr = listOf(e.javaClass.simpleName)
            manager.completeJob(jobId, -1, stdout, stderr)
            updateNotification(job.copy(state = JobState.FAILED, progressMessage = "Error: ${e.message}", exitCode = -1, stderr = stderr), false)
        }
    }
    
    private fun runShellCommand(job: ShellJob) {
        val manager = shellJobManager
        val cwd = File(job.workingDir)
        
        val result = ShellRunner.runSh(
            context = this,
            command = job.command,
            cwd = cwd,
            env = job.env,
            timeoutMs = 0L
        ) { line ->
            if (stopRequested) return@runSh
            while (pauseRequested) {
                if (stopRequested) return@runSh
                Thread.sleep(1000)
            }
            manager.updateProgress(job.id, job.progress, line)
            updateNotification(job.copy(
                stdout = job.stdout + line,
                progressMessage = line.take(80)
            ), false)
        }
        
        if (stopRequested) {
            manager.completeJob(job.id, -1, job.stdout, job.stderr + "Cancelled")
        } else {
            manager.completeJob(job.id, result.exitCode, result.outputLines, emptyList())
        }
    }
    
    private fun runGradle(job: ShellJob) {
        val manager = shellJobManager
        val cwd = File(job.workingDir)
        
        val tasks = if (job.command.isBlank()) "assembleDebug" else job.command
        val gradleCmd = "./gradlew $tasks"
        
        val result = ShellRunner.runSh(
            context = this,
            command = gradleCmd,
            cwd = cwd,
            env = job.env,
            timeoutMs = 0L
        ) { line ->
            if (stopRequested) return@runSh
            while (pauseRequested) {
                if (stopRequested) return@runSh
                Thread.sleep(1000)
            }
            manager.updateProgress(job.id, job.progress, line)
            updateNotification(job.copy(
                stdout = job.stdout + line,
                progressMessage = line.take(80)
            ), false)
        }
        
        if (stopRequested) {
            manager.completeJob(job.id, -1, job.stdout, job.stderr + "Cancelled")
        } else {
            manager.completeJob(job.id, result.exitCode, result.outputLines, emptyList())
        }
    }
    
    private fun runDownload(job: ShellJob) {
        val manager = shellJobManager
        val url = job.downloadUrl ?: return
        val destFile = File(job.workingDir, url.substringAfterLast("/"))
        
        val resumeFrom = if (job.resumeSupported && destFile.exists()) destFile.length() else 0L
        val connection = openConnection(url, resumeFrom)
        
        val totalSize = if (resumeFrom > 0) {
            connection.getHeaderField("Content-Range")?.substringAfterLast("/")?.toLongOrNull() 
                ?: (connection.contentLength.toLong() + resumeFrom)
        } else {
            connection.contentLength.toLong()
        }
        
        val buffer = ByteArray(8192)
        var received = resumeFrom
        val startTime = System.currentTimeMillis()
        
        destFile.parentFile?.mkdirs()
        FileOutputStream(destFile, resumeFrom > 0).use { fos ->
            connection.inputStream.use { input ->
                while (true) {
                    if (stopRequested) {
                        manager.updateProgress(job.id, job.progress, "Cancelled", received)
                        manager.completeJob(job.id, -1, job.stdout, job.stderr + "Cancelled")
                        return@runDownload
                    }
                    while (pauseRequested) {
                        if (stopRequested) {
                            manager.updateProgress(job.id, job.progress, "Cancelled", received)
                            manager.completeJob(job.id, -1, job.stdout, job.stderr + "Cancelled")
                            return@runDownload
                        }
                        Thread.sleep(1000)
                    }
                    
                    val read = input.read(buffer)
                    if (read < 0) break
                    fos.write(buffer, 0, read)
                    received += read
                    
                    val progress = if (totalSize > 0) ((received * 100) / totalSize).toInt() else 0
                    val elapsed = System.currentTimeMillis() - startTime
                    val speed = if (elapsed > 0) (received * 1000 / elapsed) else 0L
                    val msg = "Downloading ${formatBytes(received)}/${formatBytes(totalSize)} (${formatBytes(speed)}/s)"
                    
                    manager.updateProgress(job.id, progress, msg, received)
                    updateNotification(job.copy(
                        progress = progress,
                        progressMessage = msg,
                        downloadReceivedBytes = received,
                        downloadTotalBytes = totalSize
                    ), false)
                }
            }
        }
        
        if (!stopRequested) {
            manager.completeJob(job.id, 0, listOf("Downloaded to ${destFile.absolutePath}"), emptyList())
            updateNotification(job.copy(
                state = JobState.COMPLETED,
                progress = 100,
                progressMessage = "Completed: ${destFile.name}"
            ), false)
        }
    }
    
    private fun openConnection(urlString: String, resumeFrom: Long): HttpURLConnection {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 30_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        connection.connect()
        return connection
    }
    
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Shell Background Jobs",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Long-running shell commands, builds, downloads"
                setShowBadge(false)
            }
            notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun updateNotification(job: ShellJob, ongoing: Boolean) {
        val title = when (job.type) {
            JobType.DOWNLOAD -> "Download: ${job.downloadUrl?.substringAfterLast("/") ?: "file"}"
            JobType.GRADLE -> "Gradle: ${job.command}"
            JobType.COMPILE -> "Compile: ${job.command}"
            JobType.GIT -> "Git: ${job.command}"
            JobType.OPENCODE -> "OpenCode"
            else -> "Shell: ${job.command.take(40)}"
        }
        
        val content = when (job.state) {
            JobState.RUNNING -> job.progressMessage
            JobState.PAUSED -> "Paused: ${job.progressMessage}"
            JobState.COMPLETED -> "Completed (exit=${job.exitCode})"
            JobState.FAILED -> "Failed (exit=${job.exitCode}): ${job.stderr.lastOrNull() ?: "error"}"
            JobState.CANCELLED -> "Cancelled"
            else -> "Pending"
        }
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, job.progress, job.downloadTotalBytes > 0 && job.progress == 0)
        
        if (job.downloadTotalBytes > 0) {
            builder.setProgress(100, job.progress, false)
        }
        
        // Add stop action
        val stopIntent = Intent(this, ShellJobService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_JOB_ID, job.id)
        }
        val stopAction = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopAction)
        
        notificationManager.notify(job.notificationId, builder.build())
    }
    
    private fun stopForegroundCompat() {
        stopForeground(true)
    }
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> String.format("%.1f KB", bytes / 1000.0)
            else -> "$bytes B"
        }
    }
}