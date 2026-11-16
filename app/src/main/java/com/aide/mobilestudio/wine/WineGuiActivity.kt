package com.aide.mobilestudio.wine

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
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
            context = this,
            socketPath = "",
            displayWidth = width,
            displayHeight = height,
        )
        xServer = server

        val root = FrameLayout(this).apply {
            setBackgroundColor(StudioUi.palette.bg)
        }
        val surfaceView = android.view.SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) {}
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
            })
        }
        root.addView(surfaceView)

        // Forward touch events to X11 server for Wine input
        surfaceView.setOnTouchListener { _, event ->
            val x = event.x.toInt()
            val y = event.y.toInt()
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    xServer?.injectPointerEvent(4, x, y, 1) // ButtonPress, button 1
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    xServer?.injectPointerEvent(5, x, y, 1) // ButtonRelease, button 1
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    xServer?.injectPointerEvent(6, x, y, 0) // MotionNotify
                    true
                }
                else -> false
            }
        }

        // Periodically render X framebuffer to SurfaceView canvas (dirty rect optimized)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val updater = object : Runnable {
            override fun run() {
                val holder = surfaceView.holder
                if (!holder.isCreating && holder.surface.isValid) {
                    val canvas = holder.lockCanvas()
                    if (canvas != null) {
                        try {
                            // Clip to dirty region for efficient partial redraw
                            val dirty = server.dirtyRects.toList()
                            server.dirtyRects.clear()
                            if (dirty.isNotEmpty()) {
                                for (r in dirty) {
                                    canvas.clipRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), android.graphics.Region.Op.UNION)
                                }
                            }
                            canvas.drawBitmap(server.framebuffer, 0f, 0f, null)
                        } finally {
                            holder.unlockCanvasAndPost(canvas)
                        }
                    }
                }
                if (!isFinishing) handler.postDelayed(this, 16) // ~60 FPS
            }
        }
        handler.post(updater)

        setContentView(root)

        // Start X server then launch wine
        Thread {
            server.start()
            Thread.sleep(1000) // wait for X server to be ready

            Log.d("WineGui", "X11 display: ${server.getDisplayString()}")
            Log.d("WineGui", "X11 unix socket: ${MiniXServer.X11_UNIX_SOCKET}")
            Log.d("WineGui", "EGL ready: ${server.eglContextManager.isReady()}")

            // Test EGL pbuffer surface creation for hardware acceleration
            val eglSurface = server.eglContextManager.createPbufferSurface(800, 600)
            Log.d("WineGui", "EGL pbuffer surface: ${if (eglSurface != null) "created" else "failed"}")
            if (eglSurface != null) {
                server.eglContextManager.makeCurrent(eglSurface)
                Log.d("WineGui", "EGL makeCurrent: ${server.eglContextManager.isReady()}")
                server.eglContextManager.swapBuffers(eglSurface)
                Log.d("WineGui", "EGL swapBuffers: ok")
            }

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
