package com.aide.mobilestudio.godot

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.aide.mobilestudio.GradleRunner
import com.aide.mobilestudio.JreGradleInstaller
import com.aide.mobilestudio.LineLevel
import org.godotengine.godot.BuildProvider
import org.godotengine.godot.GodotHost
import org.godotengine.godot.variant.Callable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class MobileStudioGodotBuildProvider(
    context: Context,
    private val host: GodotHost
) : BuildProvider {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextJobId = AtomicInteger(1)
    private val installer = JreGradleInstaller(appContext, {}, {})
    private val runner = GradleRunner(appContext, installer)

    override fun buildEnvConnect(callback: Callable): Boolean {
        runOnGodotThread { callback.call() }
        return true
    }

    override fun buildEnvDisconnect() {
        runner.cancel()
    }

    override fun buildEnvExecute(
        buildTool: String,
        arguments: Array<String>,
        projectPath: String,
        buildDir: String,
        outputCallback: Callable,
        resultCallback: Callable
    ): Int {
        if (buildTool != "gradle") return -1
        val tasks = arguments.filter { !it.startsWith("-") }
        if (tasks.isEmpty()) return -1

        val jobId = nextJobId.getAndIncrement()
        runner.run(
            projectDir = File(projectPath),
            tasks = tasks,
            onLine = { line, level ->
                runOnGodotThread {
                    outputCallback.call(if (level == LineLevel.ERROR || level == LineLevel.WARNING) 1 else 0, line)
                }
            },
            onDone = { result ->
                runOnGodotThread {
                    resultCallback.call(if (result.success) 0 else 1)
                }
            }
        )
        return jobId
    }

    override fun buildEnvCancel(jobId: Int) {
        runner.cancel()
    }

    override fun buildEnvCleanProject(projectPath: String, buildDir: String, callback: Callable) {
        runner.run(
            projectDir = File(projectPath),
            tasks = listOf("clean"),
            onLine = { _, _ -> },
            onDone = {
                runOnGodotThread { callback.call() }
            }
        )
    }

    private fun runOnGodotThread(action: () -> Unit) {
        val godot = host.godot
        if (godot != null) {
            godot.runOnRenderThread { action() }
        } else {
            mainHandler.post { action() }
        }
    }
}
