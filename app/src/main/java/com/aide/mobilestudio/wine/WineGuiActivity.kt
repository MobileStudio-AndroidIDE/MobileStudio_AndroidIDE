package com.aide.mobilestudio.wine

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import com.aide.mobilestudio.WindowsExeEnvironment
import com.aide.mobilestudio.StudioUi
import java.io.File

/**
 * Embedded Wine GUI activity — launches a minimal X11 server, runs wine
 * connected to it, and renders the Wine desktop in a SurfaceView with touch
 * input forwarding. This is the foundation for Winlator-style embedded GUI.
 */
class WineGuiActivity : Activity() {

    companion object {
        private const val EXTRA_EXE_PATH = "exe_path"
        private const val EXTRA_PROJECT_DIR = "project_dir"

        fun start(activity: Activity, exePath: String, projectDir: String?) {
            val intent = android.content.Intent(activity, WineGuiActivity::class.java)
            intent.putExtra(EXTRA_EXE_PATH, exePath)
            if (projectDir != null) intent.putExtra(EXTRA_PROJECT_DIR, projectDir)
            activity.startActivity(intent)
        }
    }

    private var xServer: MiniXServer? = null
    private var exeFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exeFile = intent.getStringExtra(EXTRA_EXE_PATH)?.let { File(it) }?.takeIf { it.isFile }

        val (width, height) = 800 to 600
        val server = MiniXServer(
            socketPath = "",
            displayWidth = width,
            displayHeight = height,
        )
        xServer = server

        val root = FrameLayout(this).apply {
            setBackgroundColor(StudioUi.palette.bg)
        }
        val imageView = android.widget.ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        root.addView(imageView)

        // Periodically copy the X framebuffer to the ImageView
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                server.framebuffer.let { fb ->
                    imageView.setImageBitmap(fb)
                }
                if (!isFinishing) handler.postDelayed(this, 100)
            }
        }
        handler.post(updater)

        setContentView(root)

        // Start X server then launch wine
        Thread {
            server.start()
            Thread.sleep(1000) // wait for X server to be ready

            val exe = exeFile ?: return@Thread
            val env = WindowsExeEnvironment(this)
            if (!env.isInstalled) {
                runOnUiThread { Toast.makeText(this, "Wine not installed", Toast.LENGTH_SHORT).show() }
                return@Thread
            }
            try {
                // Bootstrap prefix if needed
                if (!env.isPrefixBooted) {
                    env.bootstrapPrefix { }
                }
                // Launch X server first (GUI mode)
                env.ensureXServer()
                // Run the .exe — wine connects to our embedded X server via DISPLAY=:0
                val code = env.runWindowsExe(exe) { line ->
                    android.util.Log.d("WineGui", line)
                }
                runOnUiThread {
                    Toast.makeText(this, "exit=$code", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.apply { name = "WineGuiLauncher"; isDaemon = true; start() }
    }

    override fun onDestroy() {
        xServer?.stop()
        super.onDestroy()
    }
}
