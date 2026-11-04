package com.aide.mobilestudio

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Job type enumeration */
enum class JobType {
    GRADLE, SHELL, COMPILE, DOWNLOAD, GIT, OPENCODE, CUSTOM, NODE, BUN
}

/** Job state */
enum class JobState {
    PENDING, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED
}

/** Shell job metadata */
data class ShellJob(
    val id: Long,
    val type: JobType,
    val command: String,
    val projectPath: String,
    val workingDir: String,
    val env: Map<String, String>,
    val state: JobState = JobState.PENDING,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long = 0,
    val exitCode: Int = -1,
    val stdout: List<String> = emptyList(),
    val stderr: List<String> = emptyList(),
    val progress: Int = 0,
    val progressMessage: String = "",
    val downloadUrl: String? = null,
    val downloadTotalBytes: Long = 0,
    val downloadReceivedBytes: Long = 0,
    val resumeSupported: Boolean = false,
    val notificationId: Int = 0
) {
    fun copy(
        state: JobState? = null,
        endTime: Long? = null,
        exitCode: Int? = null,
        stdout: List<String>? = null,
        stderr: List<String>? = null,
        progress: Int? = null,
        progressMessage: String? = null,
        downloadReceivedBytes: Long? = null
    ): ShellJob = ShellJob(
        id = id, type = type, command = command, projectPath = projectPath,
        workingDir = workingDir, env = env,
        state = state ?: this.state,
        startTime = startTime,
        endTime = endTime ?: this.endTime,
        exitCode = exitCode ?: this.exitCode,
        stdout = stdout ?: this.stdout,
        stderr = stderr ?: this.stderr,
        progress = progress ?: this.progress,
        progressMessage = progressMessage ?: this.progressMessage,
        downloadUrl = downloadUrl, downloadTotalBytes = downloadTotalBytes,
        downloadReceivedBytes = downloadReceivedBytes ?: this.downloadReceivedBytes,
        resumeSupported = resumeSupported, notificationId = notificationId
    )
}

/** Job Manager - persists jobs, handles foreground service lifecycle */
class ShellJobManager(private val context: Context) {
    companion object {
        private const val NOTIFICATION_CHANNEL = "shell_background_jobs"
        private const val NOTIFICATION_BASE_ID = 5000
    }
    
    private val prefs = context.getSharedPreferences("shell_jobs", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val typeToken = object : TypeToken<Map<Long, String>>() {}
    private val jobs = ConcurrentHashMap<Long, ShellJob>()
    private val idGenerator = AtomicLong(System.currentTimeMillis())
    
    init { loadAll() }
    
    /** Creates and starts a new shell job */
    fun startJob(
        type: JobType,
        command: String,
        projectPath: String,
        workingDir: String = "",
        env: Map<String, String> = emptyMap(),
        downloadUrl: String? = null,
        downloadTotalBytes: Long = 0,
        resumeSupported: Boolean = false
    ): ShellJob {
        val id = idGenerator.incrementAndGet()
        val notificationId = NOTIFICATION_BASE_ID + (id % 1000).toInt()
        val cwd = if (workingDir.isNotBlank()) workingDir else projectPath
        
        val job = ShellJob(
            id = id,
            type = type,
            command = command,
            projectPath = projectPath,
            workingDir = cwd,
            env = buildDefaultEnv(env),
            downloadUrl = downloadUrl,
            downloadTotalBytes = downloadTotalBytes,
            resumeSupported = resumeSupported,
            notificationId = notificationId
        )
        
        jobs[id] = job
        persistJob(job)
        startForegroundService(job)
        return job
    }
    
    /** Builds default environment for jobs */
    private fun buildDefaultEnv(userEnv: Map<String, String>): Map<String, String> {
        val base = mutableMapOf<String, String>()
        base["PATH"] = userEnv["PATH"] ?: System.getenv("PATH") ?: "/system/bin"
        base["HOME"] = System.getenv("HOME") ?: context.filesDir.absolutePath
        base["PWD"] = userEnv["PWD"] ?: context.filesDir.absolutePath
        base["TMPDIR"] = System.getenv("TMPDIR") ?: context.cacheDir.absolutePath
        base["ANDROID_NDK_ROOT"] = userEnv["ANDROID_NDK_ROOT"] ?: ""
        base["LLVM"] = userEnv["LLVM"] ?: ""
        base["TERM"] = "xterm-256color"
        base["SHELL"] = "/system/bin/sh"
        base["LANG"] = "en_US.UTF-8"
        base["LC_ALL"] = "en_US.UTF-8"
        // Node.js / Bun / OpenCode specific
        base["NODE_PATH"] = userEnv["NODE_PATH"] ?: ""
        base["NPM_CONFIG_PREFIX"] = userEnv["NPM_CONFIG_PREFIX"] ?: ""
        base["BUN_INSTALL"] = userEnv["BUN_INSTALL"] ?: ""
        base["OPENCODE_CONFIG_DIR"] = userEnv["OPENCODE_CONFIG_DIR"] ?: File(context.filesDir, "opencode/config").absolutePath
        userEnv.forEach { base[it.key] = it.value }
        return base
    }
    
    /** Starts the foreground service for a job */
    private fun startForegroundService(job: ShellJob) {
        val intent = Intent(context, ShellJobService::class.java).apply {
            action = ShellJobService.ACTION_START
            putExtra(ShellJobService.EXTRA_JOB_ID, job.id)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
    
    /** Stops a job */
    fun stopJob(id: Long) {
        jobs[id]?.let { job ->
            val updated = job.copy(state = JobState.CANCELLED, endTime = System.currentTimeMillis())
            jobs[id] = updated
            persistJob(updated)
            val intent = Intent(context, ShellJobService::class.java).apply {
                action = ShellJobService.ACTION_STOP
                putExtra(ShellJobService.EXTRA_JOB_ID, id)
            }
            context.startService(intent)
        }
    }
    
    /** Pauses a job (for downloads) */
    fun pauseJob(id: Long) {
        jobs[id]?.let { job ->
            val updated = job.copy(state = JobState.PAUSED)
            jobs[id] = updated
            persistJob(updated)
            val intent = Intent(context, ShellJobService::class.java).apply {
                action = ShellJobService.ACTION_PAUSE
                putExtra(ShellJobService.EXTRA_JOB_ID, id)
            }
            context.startService(intent)
        }
    }
    
    /** Resumes a paused job */
    fun resumeJob(id: Long) {
        jobs[id]?.let { job ->
            val updated = job.copy(state = JobState.RUNNING)
            jobs[id] = updated
            persistJob(updated)
            val intent = Intent(context, ShellJobService::class.java).apply {
                action = ShellJobService.ACTION_RESUME
                putExtra(ShellJobService.EXTRA_JOB_ID, id)
            }
            context.startService(intent)
        }
    }
    
    /** Updates job progress from service */
    fun updateProgress(id: Long, progress: Int, message: String, receivedBytes: Long? = null) {
        jobs[id]?.let { job ->
            val updated = job.copy(
                progress = progress,
                progressMessage = message,
                downloadReceivedBytes = receivedBytes ?: job.downloadReceivedBytes
            )
            jobs[id] = updated
            persistJob(updated)
        }
    }
    
    /** Called by service when job completes */
    fun completeJob(id: Long, exitCode: Int, stdout: List<String>, stderr: List<String>) {
        jobs[id]?.let { job ->
            val state = if (exitCode == 0) JobState.COMPLETED else JobState.FAILED
            val updated = job.copy(
                state = state,
                endTime = System.currentTimeMillis(),
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr
            )
            jobs[id] = updated
            persistJob(updated)
            stopForegroundService(id)
        }
    }
    
    /** Stops foreground notification */
    private fun stopForegroundService(id: Long) {
        jobs[id]?.let { job ->
            val intent = Intent(context, ShellJobService::class.java).apply {
                action = ShellJobService.ACTION_STOP
                putExtra(ShellJobService.EXTRA_JOB_ID, id)
            }
            context.startService(intent)
        }
    }
    
    /** Gets all jobs */
    fun getAllJobs(): List<ShellJob> = jobs.values.toList().sortedByDescending { it.startTime }
    
    /** Gets running jobs */
    fun getRunningJobs(): List<ShellJob> = jobs.values.filter { it.state == JobState.RUNNING }.toList()
    
    /** Gets a job by ID */
    fun getJob(id: Long): ShellJob? = jobs[id]
    
    /** Removes completed/failed jobs older than maxAge */
    fun cleanup(maxAge: Long = 24 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        val toRemove = mutableListOf<Long>()
        jobs.entries.removeIf { (id, job) ->
            val shouldRemove = job.state in setOf(JobState.COMPLETED, JobState.FAILED, JobState.CANCELLED) &&
                (now - job.endTime) > maxAge
            if (shouldRemove) toRemove.add(id)
            shouldRemove
        }
        toRemove.forEach { id ->
            prefs.edit().remove("job_$id").apply()
        }
    }
    
    /** Persists a single job */
    private fun persistJob(job: ShellJob) {
        prefs.edit().putString("job_${job.id}", gson.toJson(job)).apply()
    }
    
    /** Loads all jobs from SharedPreferences */
    private fun loadAll() {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("job_") && value is String) {
                try {
                    val job = gson.fromJson(value, ShellJob::class.java)
                    jobs[job.id] = job
                } catch (e: Exception) {
                    // Ignore corrupted entries
                }
            }
        }
    }
    
    /** Formats job for display in `jobs` command */
    fun formatJobList(): String {
        val all = getAllJobs()
        if (all.isEmpty()) return "No jobs"
        return all.joinToString("\n") { job ->
            val status = when (job.state) {
                JobState.RUNNING -> "RUNNING"
                JobState.PENDING -> "PENDING"
                JobState.PAUSED -> "PAUSED"
                JobState.COMPLETED -> "DONE"
                JobState.FAILED -> "FAILED"
                JobState.CANCELLED -> "CANCELLED"
            }
            val progressStr = if (job.type == JobType.DOWNLOAD && job.downloadTotalBytes > 0) {
                " ${formatBytes(job.downloadReceivedBytes)}/${formatBytes(job.downloadTotalBytes)} (${job.progress}%)"
            } else if (job.progress > 0) {
                " ${job.progress}%"
            } else ""
            "[${job.id}] $status  ${job.command.take(60)}${if (job.command.length > 60) "..." else ""}$progressStr"
        }
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

/** Application extension for easy access */
private val jobManagerCache = mutableMapOf<Application, ShellJobManager>()

val Application.shellJobManager: ShellJobManager
    get() = jobManagerCache.getOrPut(this) { ShellJobManager(this) }

val Context.shellJobManager: ShellJobManager get() = (applicationContext as Application).shellJobManager