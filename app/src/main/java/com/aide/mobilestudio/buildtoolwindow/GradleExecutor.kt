package com.aide.mobilestudio.buildtoolwindow

import android.content.Context
import com.aide.mobilestudio.GradleRunner
import com.aide.mobilestudio.BuildToolsConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Gradle Executor - handles Gradle task execution with various options:
 * - Parallel execution (--parallel)
 * - Build cache (--build-cache)
 * - Daemon (--daemon / --no-daemon)
 * - Profiling (--profile)
 * - Debug JVM (--debug-jvm)
 * - Offline mode (--offline)
 * - Dry run (-m / --dry-run)
 */
class GradleExecutor(
    private val context: Context,
    private val gradleRunner: GradleRunner,
    private val projectDir: File
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val runningTasks = ConcurrentHashMap<String, RunningTask>()

    data class RunningTask(
        val taskName: String,
        val args: List<String>,
        val startTime: Long = System.currentTimeMillis(),
        var isCancelled: Boolean = false
    )

    data class ExecutionOptions(
        val parallel: Boolean = true,
        val buildCache: Boolean = true,
        val daemon: Boolean = true,
        val profile: Boolean = false,
        val debugJvm: Boolean = false,
        val offline: Boolean = false,
        val dryRun: Boolean = false,
        val info: Boolean = false,
        val debug: Boolean = false,
        val stacktrace: Boolean = false,
        val scan: Boolean = false,
        val customArgs: List<String> = emptyList()
    )

    data class ExecutionResult(
        val success: Boolean,
        val output: String,
        val durationMs: Long,
        val errorMessage: String? = null
    )

    /**
     * Executes a Gradle task.
     */
    fun execute(task: String, options: ExecutionOptions = ExecutionOptions()): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val args = mutableListOf<String>()

        if (options.parallel) args.add("--parallel")
        if (options.buildCache) args.add("--build-cache")
        if (options.daemon) args.add("--daemon")
        if (options.profile) args.add("--profile")
        if (options.debugJvm) args.add("--debug-jvm")
        if (options.offline) args.add("--offline")
        if (options.dryRun) args.add("--dry-run")
        if (options.info) args.add("--info")
        if (options.debug) args.add("--debug")
        if (options.stacktrace) args.add("--stacktrace")
        if (options.scan) args.add("--scan")
        args.addAll(options.customArgs)

        try {
            gradleRunner.run(projectDir, args, { _, _ -> }, { result ->
                // Result is handled via callback
            })
            return ExecutionResult(
                success = true,
                output = "",
                durationMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            return ExecutionResult(
                success = false,
                output = "",
                durationMs = System.currentTimeMillis() - startTime,
                errorMessage = e.message
            )
        }
    }

    /**
     * Cancels a running task.
     */
    fun cancel(taskName: String) {
        val task = runningTasks[taskName]
        task?.isCancelled = true
    }
}
