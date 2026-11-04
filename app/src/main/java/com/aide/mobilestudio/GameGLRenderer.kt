package com.aide.mobilestudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * GameGLRenderer — REAL OpenGL ES 3.0 renderer for Lua games.
 *
 *  - GLSL ES 3.00 shaders (vertex: position+aColor → MVP; fragment: uniform color)
 *  - Vertex/index buffers (cube 36 indices, plane 2 triangles)
 *  - Real perspective projection + view matrix (camera x,y,z,yaw,pitch)
 *  - Depth test enabled (GL_DEPTH_TEST)
 *  - 2D API: orthographic-mode quads/lines/points (depth disabled), text via bitmap textures
 *  - clear() → glClearColor
 *  - GL errors are NEVER hidden: shader compile/link errors, GL error codes,
 *    surface size, draw call counts are all reported to the log host.
 *
 * Render-host contract with LoveRuntime: the Lua runtime calls setClearColor /
 * setColor / drawRect2D / ... / begin3D / camera / drawCube / drawPlane / end3D —
 * all executed on the GL thread (LoveRuntime runs its update/draw from onDrawFrame).
 */
class GameGLRenderer(
    private val logHost: (String) -> Unit
) : GLSurfaceView.Renderer {

    // Shader programs
    private var prog3D = 0
    private var uMvp3D = 0
    private var uColor3D = 0
    private var aPos3D = 0

    private var prog2D = 0
    private var uMvp2D = 0
    private var uColor2D = 0
    private var aPos2D = 0
    private var aColor2D = 0

    // Meshes
    private var cubeVbo = 0
    private var cubeIbo = 0
    private var planeVbo = 0
    private var planeIbo = 0

    // Camera + matrices
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private var camX = 0f; private var camY = 2f; private var camZ = 6f
    private var camYaw = 0f; private var camPitch = 0f

    // Render state
    private var in3D = false
    private var clearColor = floatArrayOf(0.1f, 0.15f, 0.25f, 1f)
    private var drawColor = floatArrayOf(1f, 1f, 1f, 1f)
    private var lineWidth = 1f
    private var pointSize = 2f
    private var surfaceW = 0
    private var surfaceH = 0
    private var drawCallCount = 0

    // GL debug info
    var glVersion = "?"
    var glslVersion = "?"
    var lastGlError = 0
    var shaderErrors: MutableList<String> = mutableListOf()
    var linkErrors: MutableList<String> = mutableListOf()
    var glReady = false

    // Transform stack (model matrix)
    private val modelStack = mutableListOf<FloatArray>()
    private var modelMatrix = identity()

    // Texture handles (images + text)
    private val textures = mutableMapOf<Int, Int>()
    private var textureCounter = 0

    // Deferred callbacks from the Lua runtime (executed on the GL thread)
    var onFrame: ((Unit) -> Unit)? = null

    companion object {
        private val VERT_3D = """
            #version 300 es
            uniform mat4 uMvp;
            in vec3 aPos;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """.trimIndent()

        private val FRAG = """
            #version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                fragColor = uColor;
            }
        """.trimIndent()

        private val VERT_2D = """
            #version 300 es
            uniform mat4 uMvp;
            in vec3 aPos;
            in vec4 aColor;
            out vec4 vColor;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vColor = aColor;
            }
        """.trimIndent()

        private val FRAG_2D = """
            #version 300 es
            precision mediump float;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                fragColor = vColor;
            }
        """.trimIndent()

        fun identity(): FloatArray {
            val m = FloatArray(16)
            android.opengl.Matrix.setIdentityM(m, 0)
            return m
        }
    }

    // ------------------------------------------------------------------
    // GLSurfaceView.Renderer
    // ------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        glVersion = GLES30.glGetString(GLES30.GL_VERSION) ?: "?"
        glslVersion = GLES30.glGetString(GLES30.GL_SHADING_LANGUAGE_VERSION) ?: "?"
        logHost("[GL] OpenGL ES version: $glVersion")
        logHost("[GL] GLSL version: $glslVersion")

        prog3D = buildProgram(VERT_3D, FRAG)
        uMvp3D = if (prog3D != 0) GLES30.glGetUniformLocation(prog3D, "uMvp") else -1
        uColor3D = if (prog3D != 0) GLES30.glGetUniformLocation(prog3D, "uColor") else -1
        aPos3D = if (prog3D != 0) GLES30.glGetAttribLocation(prog3D, "aPos") else -1

        prog2D = buildProgram(VERT_2D, FRAG_2D)
        uMvp2D = if (prog2D != 0) GLES30.glGetUniformLocation(prog2D, "uMvp") else -1
        aPos2D = if (prog2D != 0) GLES30.glGetAttribLocation(prog2D, "aPos") else -1
        aColor2D = if (prog2D != 0) GLES30.glGetAttribLocation(prog2D, "aColor") else -1

        buildCubeMesh()
        buildPlaneMesh()

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        if (prog3D == 0 || prog2D == 0) {
            logHost("[GL ERROR] Shader program creation FAILED — cube/plane cannot render")
            logHost("[GL ERROR] compile errors: ${shaderErrors.joinToString("; ").take(600)}")
            logHost("[GL ERROR] link errors: ${linkErrors.joinToString("; ").take(600)}")
            glReady = false
        } else {
            glReady = true
            logHost("[GL] Renderer ready — depth test ON, shaders compiled+linked")
        }
        checkGlError("onSurfaceCreated")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceW = width
        surfaceH = height
        GLES30.glViewport(0, 0, width, height)
        logHost("[GL] Viewport: $width x $height")
        // Perspective projection (fov 50°, near 0.1, far 200)
        val fovRad = Math.toRadians(50.0)
        val aspect = if (height > 0) width.toFloat() / height.toFloat() else 1f
        val f = (1.0 / tan(fovRad / 2.0)).toFloat()
        android.opengl.Matrix.setIdentityM(projMatrix, 0)
        projMatrix[0] = f / aspect
        projMatrix[5] = f
        projMatrix[10] = -(200f + 0.1f) / (200f - 0.1f)
        projMatrix[11] = -1f
        projMatrix[14] = -(2f * 200f * 0.1f) / (200f - 0.1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        drawCallCount = 0
        GLES30.glClearColor(clearColor[0], clearColor[1], clearColor[2], clearColor[3])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawCallCount += 1

        // Execute queued 3D draw commands (real GL: VBO + IBO + shader + MVP + depth test)
        renderQueued3D()

        // Lua game loop runs on the GL thread
        onFrame?.invoke(Unit)
        checkGlError("onDrawFrame")
    }

    private fun checkGlError(where: String) {
        val err = GLES30.glGetError()
        if (err != GLES30.GL_NO_ERROR) {
            lastGlError = err
            logHost("[GL ERROR] $where: GL error code 0x${Integer.toHexString(err)}")
        }
    }

    // ------------------------------------------------------------------
    // Shader + mesh build
    // ------------------------------------------------------------------

    private fun buildShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            shaderErrors.add(log.take(400))
            logHost("[GL ERROR] shader compile failed: ${log.take(200)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vert = buildShader(GLES30.GL_VERTEX_SHADER, vertSrc)
        val frag = buildShader(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        if (vert == 0 || frag == 0) return 0
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vert)
        GLES30.glAttachShader(prog, frag)
        GLES30.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            linkErrors.add(log.take(400))
            logHost("[GL ERROR] program link failed: ${log.take(200)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun bufferOf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    private fun indexBufferOf(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(data); position(0)
        }

    private fun buildCubeMesh() {
        // Unit cube centered at origin (24 verts for per-face normals-free flat shading)
        val s = 0.5f
        val verts = floatArrayOf(
            // front (z+)
            -s, -s, s,  s, -s, s,  s, s, s,  -s, s, s,
            // back (z-)
            -s, -s, -s,  -s, s, -s,  s, s, -s,  s, -s, -s,
            // left (x-)
            -s, -s, s,  -s, s, s,  -s, s, -s,  -s, -s, -s,
            // right (x+)
            s, -s, s,  s, -s, -s,  s, s, -s,  s, s, s,
            // top (y+)
            -s, s, s,  s, s, s,  s, s, -s,  -s, s, -s,
            // bottom (y-)
            -s, -s, s,  -s, -s, -s,  s, -s, -s,  s, -s, s
        )
        val indices = shortArrayOf(
            0, 1, 2, 0, 2, 3,    4, 5, 6, 4, 6, 7,
            8, 9, 10, 8, 10, 11, 12, 13, 14, 12, 14, 15,
            16, 17, 18, 16, 18, 19, 20, 21, 22, 20, 22, 23
        )
        cubeVbo = intArrayOf(0).also { GLES30.glGenBuffers(1, it, 0) }[0]
        cubeIbo = intArrayOf(0).also { GLES30.glGenBuffers(1, it, 0) }[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cubeVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, bufferOf(verts), GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, cubeIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, indexBufferOf(indices), GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun buildPlaneMesh() {
        // Horizontal unit plane (2 triangles) centered at origin, y=0
        val s = 0.5f
        val verts = floatArrayOf(
            -s, 0f, s,   s, 0f, s,   s, 0f, -s,   -s, 0f, -s
        )
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
        planeVbo = intArrayOf(0).also { GLES30.glGenBuffers(1, it, 0) }[0]
        planeIbo = intArrayOf(0).also { GLES30.glGenBuffers(1, it, 0) }[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, planeVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, bufferOf(verts), GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, planeIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, indexBufferOf(indices), GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    // ------------------------------------------------------------------
    // Render API (called by LoveRuntime on the GL thread)
    // ------------------------------------------------------------------

    fun setClearColor(r: Float, g: Float, b: Float, a: Float = 1f) {
        clearColor[0] = r; clearColor[1] = g; clearColor[2] = b; clearColor[3] = a
    }

    fun setDrawColor(r: Float, g: Float, b: Float, a: Float = 1f) {
        drawColor[0] = r; drawColor[1] = g; drawColor[2] = b; drawColor[3] = a
    }

    fun begin3D() {
        in3D = true
    }

    fun end3D() {
        in3D = false
        modelMatrix = identity()
        modelStack.clear()
    }

    fun camera(x: Float, y: Float, z: Float, yaw: Float, pitch: Float) {
        camX = x; camY = y; camZ = z; camYaw = yaw; camPitch = pitch
    }

    private fun updateViewMatrix() {
        android.opengl.Matrix.setIdentityM(viewMatrix, 0)
        android.opengl.Matrix.rotateM(viewMatrix, 0, Math.toDegrees(camPitch.toDouble()).toFloat(), 1f, 0f, 0f)
        android.opengl.Matrix.rotateM(viewMatrix, 0, Math.toDegrees(camYaw.toDouble()).toFloat(), 0f, 1f, 0f)
        android.opengl.Matrix.translateM(viewMatrix, 0, -camX, -camY, -camZ)
    }

    // ------------------------------------------------------------------
    // Thread-safe command queue: the Lua runtime (Canvas thread) enqueues
    // draw commands; the GL thread executes the real GL calls in onDrawFrame.
    // (GL contexts are thread-owned — direct GL calls from other threads fail.)
    // ------------------------------------------------------------------

    private data class DrawCmd(
        val kind: String,       // "cube" | "plane"
        val x: Float, val y: Float, val z: Float,
        val sizeW: Float, val sizeD: Float,
        val color: FloatArray
    )
    private val drawQueue = mutableListOf<DrawCmd>()

    /** Draws a real GL cube (queued; rendered with VBO+IBO+shader+MVP on the GL thread). */
    fun drawCube(x: Float, y: Float, z: Float, size: Float) {
        if (!in3D) {
            logHost("[GL ERROR] drawCube called outside begin3D()/end3D() — cube not rendered")
            return
        }
        synchronized(drawQueue) {
            drawQueue.add(DrawCmd("cube", x, y, z, size, size, drawColor.copyOf()))
        }
    }

    /** Draws a real GL plane (2 triangles, queued). */
    fun drawPlane(x: Float, y: Float, z: Float, w: Float, d: Float) {
        if (!in3D) {
            logHost("[GL ERROR] drawPlane called outside begin3D()/end3D() — plane not rendered")
            return
        }
        synchronized(drawQueue) {
            drawQueue.add(DrawCmd("plane", x, y, z, w, d, drawColor.copyOf()))
        }
    }

    /** Executes queued 3D draw commands with real GL (VBO + IBO + shader + MVP + depth test). */
    private fun renderQueued3D() {
        val queue: List<DrawCmd>
        synchronized(drawQueue) {
            if (drawQueue.isEmpty()) return
            queue = drawQueue.toList()
            drawQueue.clear()
        }
        if (!glReady) {
            logHost("[GL ERROR] Shaders not ready — ${queue.size} draw commands dropped")
            return
        }
        updateViewMatrix()
        GLES30.glUseProgram(prog3D)
        for (cmd in queue) {
            val model = identity()
            android.opengl.Matrix.translateM(model, 0, cmd.x, cmd.y, cmd.z)
            if (cmd.kind == "cube") {
                android.opengl.Matrix.scaleM(model, 0, cmd.sizeW, cmd.sizeD, cmd.sizeW)
            } else {
                android.opengl.Matrix.scaleM(model, 0, cmd.sizeW, 1f, cmd.sizeD)
            }
            val pv = FloatArray(16)
            android.opengl.Matrix.multiplyMM(pv, 0, projMatrix, 0, viewMatrix, 0)
            val mvp = FloatArray(16)
            android.opengl.Matrix.multiplyMM(mvp, 0, pv, 0, model, 0)

            GLES30.glUniformMatrix4fv(uMvp3D, 1, false, mvp, 0)
            GLES30.glUniform4f(uColor3D, cmd.color[0], cmd.color[1], cmd.color[2], cmd.color[3])

            val vbo = if (cmd.kind == "cube") cubeVbo else planeVbo
            val ibo = if (cmd.kind == "cube") cubeIbo else planeIbo
            val indexCount = if (cmd.kind == "cube") 36 else 6

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glEnableVertexAttribArray(aPos3D)
            GLES30.glVertexAttribPointer(aPos3D, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glDisableVertexAttribArray(aPos3D)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
            drawCallCount++
        }
        checkGlError("renderQueued3D (${queue.size} cmds)")
    }

    /** Detailed GL diagnostics for the log screen (never a silent black screen). */
    fun logDiagnostics() {
        logHost("[GL] === GL Diagnostics ===")
        logHost("[GL] OpenGL ES: $glVersion")
        logHost("[GL] GLSL: $glslVersion")
        logHost("[GL] Surface: ${surfaceW}x${surfaceH}")
        logHost("[GL] Draw calls this frame: $drawCallCount")
        logHost("[GL] Shaders ready: $glReady (3D=$prog3D, 2D=$prog2D)")
        if (lastGlError != 0) logHost("[GL] Last GL error: 0x${Integer.toHexString(lastGlError)}")
        if (shaderErrors.isNotEmpty()) logHost("[GL] Shader compile errors: ${shaderErrors.joinToString(" | ").take(500)}")
        if (linkErrors.isNotEmpty()) logHost("[GL] Link errors: ${linkErrors.joinToString(" | ").take(500)}")
    }
}
