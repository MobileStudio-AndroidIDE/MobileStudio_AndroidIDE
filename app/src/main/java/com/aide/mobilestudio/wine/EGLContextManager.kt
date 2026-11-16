package com.aide.mobilestudio.wine

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log

/**
 * EGLContextManager - Android EGL and X11/GLX bridge for hardware-accelerated rendering.
 *
 * Provides EGL context creation and surface management so that Wine applications
 * using GLX can render through Android's OpenGL ES driver with hardware acceleration.
 */
class EGLContextManager(private val server: MiniXServer) {
    companion object {
        private const val TAG = "EGLContextManager"

        @JvmStatic
        fun instance(server: MiniXServer): EGLContextManager {
            return EGLContextManager(server)
        }
    }

    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var initialized = false

    /** Initialize EGL display and context for hardware acceleration. */
    fun init(): Boolean {
        if (initialized) return true

        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                Log.e(TAG, "eglGetDisplay failed")
                return false
            }

            val major = IntArray(1)
            val minor = IntArray(1)
            if (!EGL14.eglInitialize(eglDisplay, major, 0, minor, 0)) {
                Log.e(TAG, "eglInitialize failed")
                return false
            }

            Log.i(TAG, "EGL initialized: ${major[0]}.${minor[0]}")

            // Choose a basic config: RGBA8888, depth 16, stencil 8
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_STENCIL_SIZE, 8,
                EGL14.EGL_NONE
            )
            val numConfigs = IntArray(1)
            val configs = arrayOfNulls<EGLConfig>(1)
            if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                Log.e(TAG, "eglChooseConfig failed")
                return false
            }
            val config = configs[0] ?: return false

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                Log.e(TAG, "eglCreateContext failed")
                return false
            }

            initialized = true
            Log.i(TAG, "EGLContext created successfully (OpenGL ES 2.0)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "EGL init exception", e)
            return false
        }
    }

    /** Create an EGL pbuffer surface for off-screen rendering. */
    fun createPbufferSurface(width: Int, height: Int): EGLSurface? {
        if (!initialized) {
            if (!init()) return null
        }

        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, width,
            EGL14.EGL_HEIGHT, height,
            EGL14.EGL_NONE
        )

        val surface = EGL14.eglCreatePbufferSurface(eglDisplay!!, configs[0]!!, surfaceAttribs, 0)
        if (surface == EGL14.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreatePbufferSurface failed")
            return null
        }

        Log.d(TAG, "EGLPbufferSurface created: ${width}x${height}")
        return surface
    }

    /** Make the EGL context current for rendering. */
    fun makeCurrent(surface: EGLSurface?): Boolean {
        if (!initialized || eglDisplay == null || eglContext == null) return false

        val readSurface = if (surface == null) EGL14.EGL_NO_SURFACE else surface
        val drawSurface = if (surface == null) EGL14.EGL_NO_SURFACE else surface

        return EGL14.eglMakeCurrent(eglDisplay, drawSurface, readSurface, eglContext)
    }

    /** Swap buffers - present rendered content. */
    fun swapBuffers(surface: EGLSurface?): Boolean {
        if (!initialized || eglDisplay == null) return false
        return EGL14.eglSwapBuffers(eglDisplay, surface ?: EGL14.EGL_NO_SURFACE)
    }

    /** Release EGL resources. */
    fun release() {
        if (eglDisplay != null) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            eglContext?.let { EGL14.eglDestroyContext(eglDisplay, it) }
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = null
        eglContext = null
        initialized = false
    }

    /** Check if EGL is initialized and ready. */
    fun isReady(): Boolean = initialized && eglDisplay != null && eglContext != null

    /**
     * Get OpenGL ES version string for reporting to Wine GLX queries.
     */
    fun getGlVersionString(): String {
        return if (initialized && eglDisplay != null) {
            EGL14.eglQueryString(eglDisplay, EGL14.EGL_VERSION)
        } else {
            "1.4 (ES 2.0)"
        }
    }

    private var configs: Array<EGLConfig?> = arrayOfNulls(1)
}
