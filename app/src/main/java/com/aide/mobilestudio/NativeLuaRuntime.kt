package com.aide.mobilestudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.floor

/**
 * NativeLuaRuntime ??LÖVE-compatible Lua game runtime backed by the embedded
 * OFFICIAL Lua 5.4.9 VM (liblua_game.so, arm64-v8a, JNI via LuaNativeBridge).
 *
 * Execution flow:
  * MobileStudio documentation.
 *   ??main.lua 로드 ??lua_pcall() ??게임 루프 (love.load ??love.update(dt) ??love.draw)
 *
  * MobileStudio documentation.
  * MobileStudio documentation.
 *
 * Strict error policy (same as LoveRuntime):
 *  - Syntax errors are reported BEFORE execution (line/column/message)
 *  - Unsupported APIs raise proper Lua errors ??never silently ignored
 *  - Sandbox-restricted libs (io/os) are replaced with clear errors
 *
 * Step-by-step logs so main.lua execution can be verified before the renderer:
 *  [Lua] Creating Lua VM...
 *  [Lua] Loading main.lua: <path>
 *  [Lua] main.lua executed (top-level OK)
 *
 * Note: table-returning APIs (ECS, Solar2D/Defold adapters) remain in the LuaJ
 * runtime; this native runtime implements love.* + engine.* (value APIs).
 */
class NativeLuaRuntime(
    private val context: Context,
    private val host: Host,
    private val gameDir: File
) {
    interface Host {
        fun currentCanvas(): Canvas?
        fun width(): Int
        fun height(): Int
        fun log(line: String)
        fun currentTouch(): FloatArray?
        fun keysDown(): Set<String>
        fun setTitle(title: String)
        fun initialCanvasMatrix(): Matrix?
    }

    class GameError(val issue: LuaApiRegistry.GameIssue, cause: Throwable? = null) :
        Exception(cause?.message ?: issue.message, cause)

    /** Real GLES30 renderer (set by the host when using the GLSurfaceView layer). */
    var gl3d: GameGLRenderer? = null

    // VM lifecycle
    private var vmReady = false
    private var closed = false

    // graphics state
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint().apply { color = android.graphics.Color.WHITE; textSize = 48f; isAntiAlias = true }
    private var curColor = floatArrayOf(1f, 1f, 1f, 1f)
    private var lineWidth = 1f
    private var pointSize = 2f

    // image handles
    private val images = mutableMapOf<Int, Bitmap>()
    private var imageCounter = 0

    // audio state (SoundPool)
    private val soundPool: SoundPool
    private val soundIds = mutableMapOf<Int, Int>()
    private val streamIds = mutableMapOf<Int, Int>()
    private var soundCounter = 0
    private var masterVolume = 1f

    // timer state
    private var startTime = System.nanoTime()
    private var lastDelta = 0.0
    private var fpsCounter = 0L
    private var fpsWindowStart = 0L
    private var currentFps = 0L

    // math state
    private val rng = Random()
    private val rngGenerator = Random()

    // 3D state (software pipeline fallback)
    private var in3D = false
    private var camX = 0f; private var camY = 0f; private var camZ = 8f
    private var camYaw = 0f; private var camPitch = 0f
    private val pending3DFaces = mutableListOf<FloatArray>()

    // per-frame debug log counters
    private var updateLogCount = 0
    private var drawLogCount = 0
    private var cubeLogCount = 0
    private var planeLogCount = 0

    init {
        soundPool = SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Creates the Lua VM (idempotent). */
    private fun ensureVm(): Boolean {
        if (vmReady) return true
        if (!LuaNativeBridge.available) {
            host.log("[Lua ERROR] native Lua runtime unavailable (liblua_game.so not loaded)")
            return false
        }
        host.log("[Lua] Creating Lua VM (native, ${LuaNativeBridge.nativeVersion()})...")
        vmReady = LuaNativeBridge.nativeCreateVM()
        if (vmReady) {
            host.log("[Lua] VM created → luaL_openlibs OK")
        } else {
            host.log("[Lua ERROR] Lua VM creation failed (out of memory?)")
        }
        return vmReady
    }

    /**
      * MobileStudio documentation.
      * MobileStudio documentation.
     * Throws [GameError] on syntax/runtime errors.
     */
    fun init(mainLua: File) {
        check(LuaNativeBridge.available) { "native Lua runtime unavailable (liblua_game.so)" }
        if (!ensureVm()) error("Lua VM creation failed")
        if (closed) error("Lua runtime already closed")

        host.log("[Lua] Registering MobileStudio API + LÖVE compatible APIs...")
        registerApis()

        host.log("[Lua] Loading main.lua: ${mainLua.absolutePath}")
        if (!mainLua.isFile) {
            host.log("[Lua ERROR] main.lua not found: ${mainLua.absolutePath}")
            error("main.lua not found: ${mainLua.name}")
        }

        val err = LuaNativeBridge.nativeLoadAndRun(mainLua.absolutePath)
        if (err.isNotEmpty()) {
            host.log("[Lua ERROR] ${err}")
            throw GameError(issueFromNativeError(err, "Runtime Error"))
        }
        host.log("[Lua] main.lua executed (top-level OK)")
        host.log("[Lua] Game loop: love.load → love.update(dt) → love.draw")
        callLoad()
    }

    /** Static syntax check: compiles the source without executing. */
    fun syntaxCheck(source: String): LuaApiRegistry.GameIssue? {
        if (!LuaNativeBridge.available) return null
        if (!ensureVm()) return null
        val err = LuaNativeBridge.nativeSyntaxCheck(source)
        if (err.isEmpty()) return null
        host.log("[Lua] Syntax error: $err")
        return issueFromNativeError(err, "Lua Syntax Error")
    }

    /** Closes the VM and frees all resources (images, sounds). */
    fun close() {
        if (closed) return
        closed = true
        images.values.forEach { runCatching { it.recycle() } }
        images.clear()
        runCatching { soundPool.release() }
        if (vmReady) {
            LuaNativeBridge.nativeDestroyVM()
            vmReady = false
        }
        host.log("[Lua] VM closed → memory and resources freed")
    }

    private fun issueFromNativeError(err: String, type: String): LuaApiRegistry.GameIssue {
        var line = 0
        var column = 0
        val lineMatch = Regex("\\[string[^\\]]*\\]:(\\d+):").find(err)
            ?: Regex(":(\\d+):").find(err)
        if (lineMatch != null) line = lineMatch.groupValues[1].toIntOrNull() ?: 0
        val colMatch = Regex("column (\\d+)", RegexOption.IGNORE_CASE).find(err)
        if (colMatch != null) column = colMatch.groupValues[1].toIntOrNull() ?: 0
        val resolvedType = when {
            err.contains("Unsupported API") -> "Unsupported API"
            err.contains("Restricted API") -> "Restricted API"
            err.contains("loading error") || err.contains("cannot open") -> "Loading Error"
            else -> type
        }
        return LuaApiRegistry.GameIssue(resolvedType, err, line, column)
    }

    // ------------------------------------------------------------------
    // Binding helpers
    // ------------------------------------------------------------------

    private fun bind(path: String, fn: (List<Any?>) -> List<Any?>) {
        val id = LuaNativeBridge.registerCallback(fn)
        if (!LuaNativeBridge.nativeBindCallback(path, id)) {
            host.log("[Lua ERROR] failed to bind $path")
        }
    }

    private fun bindUnit(path: String, fn: (List<Any?>) -> Unit) {
        bind(path) { args -> fn(args); emptyList() }
    }

    // ------------------------------------------------------------------
    // Argument helpers
    // ------------------------------------------------------------------

    private fun num(args: List<Any?>, i: Int, def: Float = 0f): Float =
        (args.getOrNull(i - 1) as? Number)?.toFloat() ?: def

    private fun dbl(args: List<Any?>, i: Int, def: Double = 0.0): Double =
        (args.getOrNull(i - 1) as? Number)?.toDouble() ?: def

    private fun str(args: List<Any?>, i: Int, def: String = ""): String =
        args.getOrNull(i - 1)?.toString() ?: def

    private fun toDisplayString(v: Any?): String = when {
        v == null -> "nil"
        v is Boolean -> if (v) "true" else "false"
        v is Number -> {
            val d = v.toDouble()
            if (d == floor(d) && kotlin.math.abs(d) < 1e15) v.toLong().toString() else d.toString()
        }
        else -> v.toString()
    }

    private fun canvas(): Canvas = host.currentCanvas()
        ?: throw IllegalStateException("love.graphics: no canvas available outside of draw/update callbacks")

    private fun applyColor(a: Float = curColor[3]) {
        drawPaint.color = android.graphics.Color.argb(
            (a * 255).toInt().coerceIn(0, 255),
            (curColor[0] * 255).toInt().coerceIn(0, 255),
            (curColor[1] * 255).toInt().coerceIn(0, 255),
            (curColor[2] * 255).toInt().coerceIn(0, 255)
        )
    }

    private fun rgbFrom(args: List<Any?>, ri: Int, gi: Int, bi: Int, def: Float): Int =
        android.graphics.Color.argb(255,
            (num(args, ri, def) * 255).toInt().coerceIn(0, 255),
            (num(args, gi, def) * 255).toInt().coerceIn(0, 255),
            (num(args, bi, def) * 255).toInt().coerceIn(0, 255)
        )

    private fun unsupportedFunc(apiName: String): (List<Any?>) -> List<Any?> = { _ ->
        throw LuaError(
            "Unsupported API\n\n$apiName\n\n" +
            "This API is not implemented by the current runtime.\n" +
            "See the API docs (LuaApiRegistry) for the supported list."
        )
    }

    // ------------------------------------------------------------------
    // love.graphics
    // ------------------------------------------------------------------

    private fun setupLoveGraphics() {
        bindUnit("love.graphics.clear") { args ->
            val r = num(args, 1); val gr = num(args, 2); val b = num(args, 3)
            // With the GL layer active, clear goes to glClearColor AND the Canvas
            // stays transparent so the real GL content shows through
            val renderer = gl3d
            if (renderer != null) {
                renderer.setClearColor(r, gr, b, 1f)
            } else {
                canvas().drawColor(android.graphics.Color.argb(255,
                    (r * 255).toInt().coerceIn(0, 255),
                    (gr * 255).toInt().coerceIn(0, 255),
                    (b * 255).toInt().coerceIn(0, 255)
                ))
            }
        }
        bindUnit("love.graphics.setColor") { args ->
            curColor[0] = num(args, 1, 1f)
            curColor[1] = num(args, 2, 1f)
            curColor[2] = num(args, 3, 1f)
            curColor[3] = num(args, 4, 1f)
        }
        bind("love.graphics.getColor") { _ ->
            listOf(curColor[0].toDouble(), curColor[1].toDouble(), curColor[2].toDouble(), curColor[3].toDouble())
        }
        bindUnit("love.graphics.rectangle") { args ->
            val mode = str(args, 1)
            val x = num(args, 2); val y = num(args, 3)
            val w = num(args, 4); val h = num(args, 5)
            val c = canvas()
            applyColor()
            if (mode == "fill") {
                c.drawRect(x, y, x + w, y + h, drawPaint)
            } else {
                drawPaint.style = Paint.Style.STROKE; drawPaint.strokeWidth = lineWidth
                c.drawRect(x, y, x + w, y + h, drawPaint)
                drawPaint.style = Paint.Style.FILL
            }
        }
        bindUnit("love.graphics.circle") { args ->
            val mode = str(args, 1)
            val x = num(args, 2); val y = num(args, 3); val r = num(args, 4)
            val c = canvas()
            applyColor()
            if (mode == "fill") {
                c.drawCircle(x, y, r, drawPaint)
            } else {
                drawPaint.style = Paint.Style.STROKE; drawPaint.strokeWidth = lineWidth
                c.drawCircle(x, y, r, drawPaint)
                drawPaint.style = Paint.Style.FILL
            }
        }
        bindUnit("love.graphics.ellipse") { args ->
            val mode = str(args, 1)
            val x = num(args, 2); val y = num(args, 3)
            val rx = num(args, 4); val ry = num(args, 5)
            val c = canvas()
            applyColor()
            drawPaint.style = if (mode == "fill") Paint.Style.FILL else Paint.Style.STROKE
            if (mode != "fill") drawPaint.strokeWidth = lineWidth
            c.drawOval(android.graphics.RectF(x - rx, y - ry, x + rx, y + ry), drawPaint)
            drawPaint.style = Paint.Style.FILL
        }
        setupLoveGraphicsPart2()
    }

    private class LuaError(message: String) : Exception(message)

    private fun setupLoveGraphicsPart2() {
        bindUnit("love.graphics.line") { args ->
            val c = canvas()
            applyColor()
            drawPaint.strokeWidth = lineWidth
            val n = args.size
            if (n >= 4) {
                val path = Path()
                path.moveTo(num(args, 1), num(args, 2))
                var i = 3
                while (i + 1 <= n) { path.lineTo(num(args, i), num(args, i + 1)); i += 2 }
                c.drawPath(path, drawPaint)
            }
        }
        bindUnit("love.graphics.points") { args ->
            val c = canvas()
            applyColor()
            val n = args.size
            var i = 1
            while (i + 1 <= n) {
                c.drawCircle(num(args, i), num(args, i + 1), pointSize, drawPaint)
                i += 2
            }
        }
        bindUnit("love.graphics.polygon") { args ->
            val mode = str(args, 1)
            val c = canvas()
            applyColor()
            if (mode == "fill") drawPaint.style = Paint.Style.FILL
            else { drawPaint.style = Paint.Style.STROKE; drawPaint.strokeWidth = lineWidth }
            val path = Path()
            var i = 2
            path.moveTo(num(args, i), num(args, i + 1)); i += 2
            while (i + 1 <= args.size) { path.lineTo(num(args, i), num(args, i + 1)); i += 2 }
            path.close()
            c.drawPath(path, drawPaint)
            drawPaint.style = Paint.Style.FILL
        }
        bindUnit("love.graphics.arc") { args ->
            val mode = str(args, 1)
            val x = num(args, 2); val y = num(args, 3); val r = num(args, 4)
            val a1 = Math.toDegrees(num(args, 5).toDouble()).toFloat()
            val a2 = Math.toDegrees(num(args, 6).toDouble()).toFloat()
            val c = canvas()
            applyColor()
            drawPaint.style = if (mode == "fill") Paint.Style.FILL else Paint.Style.STROKE
            if (mode != "fill") drawPaint.strokeWidth = lineWidth
            c.drawArc(android.graphics.RectF(x - r, y - r, x + r, y + r), a1, a2 - a1, mode == "fill", drawPaint)
            drawPaint.style = Paint.Style.FILL
        }
        bindUnit("love.graphics.print") { args ->
            canvas().drawText(str(args, 1), num(args, 2), num(args, 3), textPaint)
        }
        bindUnit("love.graphics.printf") { args ->
            val text = str(args, 1)
            val x = num(args, 2); val y = num(args, 3)
            val limit = num(args, 4, 200f)
            val align = str(args, 5, "left")
            val alignPaint = android.graphics.Paint(textPaint)
            alignPaint.textAlign = when (align) {
                "center" -> android.graphics.Paint.Align.CENTER
                "right" -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.LEFT
            }
            canvas().drawText(text, x, y, alignPaint)
        }
        bind("love.graphics.getWidth") { _ -> listOf(host.width().toDouble()) }
        bind("love.graphics.getHeight") { _ -> listOf(host.height().toDouble()) }
        bindUnit("love.graphics.setLineWidth") { args -> lineWidth = num(args, 1, 1f) }
        bindUnit("love.graphics.setLineStyle") { _ -> }
        bindUnit("love.graphics.setPointSize") { args -> pointSize = num(args, 1, 2f) }
        bindUnit("love.graphics.push") { _ -> canvas().save() }
        bindUnit("love.graphics.pop") { _ -> canvas().restore() }
        bindUnit("love.graphics.translate") { args ->
            canvas().translate(num(args, 1), num(args, 2))
        }
        bindUnit("love.graphics.rotate") { args ->
            canvas().rotate(Math.toDegrees(num(args, 1).toDouble()).toFloat())
        }
        bindUnit("love.graphics.scale") { args ->
            canvas().scale(num(args, 1, 1f), num(args, 2, num(args, 1, 1f)))
        }
        bindUnit("love.graphics.origin") { _ ->
            host.initialCanvasMatrix()?.let { canvas().setMatrix(it) }
        }
        bindImageApis()
        bindStrictUnsupported()
    }

    // ------------------------------------------------------------------
    // Image handles (Bitmap-backed)
    // ------------------------------------------------------------------

    private fun bindImageApis() {
        bind("love.graphics.newImage") { args ->
            val path = str(args, 1)
            val f = resolveGameFile(path)
                ?: throw LuaError("Image loading error: file not found: $path\nFile must be inside the game folder.")
            val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }
                ?: throw LuaError("Image loading error: could not decode: $path\nSupported formats: png, jpg, webp")
            imageCounter++
            images[imageCounter] = bmp
            listOf(imageCounter)
        }
        bindUnit("love.graphics.draw") { args ->
            val handle = num(args, 1).toInt()
            val bmp = images[handle]
                ?: throw LuaError("Draw error: unknown image handle $handle (was it loaded with love.graphics.newImage?)")
            val x = num(args, 2); val y = num(args, 3)
            val rot = num(args, 4)
            val sx = num(args, 5, 1f); val sy = num(args, 6, sx)
            val m = Matrix()
            m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
            m.postRotate(Math.toDegrees(rot.toDouble()).toFloat())
            m.postScale(sx, sy)
            m.postTranslate(x + bmp.width / 2f * sx, y + bmp.height / 2f * sy)
            canvas().drawBitmap(bmp, m, drawPaint)
        }
    }

    /** Strict bindings for known-but-unimplemented APIs (raise clear Lua errors). */
    private fun bindStrictUnsupported() {
        listOf(
            "newCanvas", "setCanvas", "newFont", "setFont", "newMesh", "newShader",
            "setShader", "setBlendMode", "setScissor"
        ).forEach { name ->
            bind("love.graphics.$name", unsupportedFunc("love.graphics.$name"))
        }
    }

    // ------------------------------------------------------------------
    // love.audio (SoundPool-backed)
    // ------------------------------------------------------------------

    private fun setupLoveAudio() {
        bind("love.audio.newSource") { args ->
            val path = str(args, 1)
            val f = resolveGameFile(path)
                ?: throw LuaError("Audio loading error: file not found: $path\nFile must be inside the game folder.")
            val id = try { soundPool.load(f.absolutePath, 1) } catch (e: Exception) { 0 }
            if (id == 0) {
                throw LuaError("Audio loading error: could not load: $path\nSupported formats: wav, ogg, mp3")
            }
            soundCounter++
            soundIds[soundCounter] = id
            listOf(soundCounter)
        }
        bindUnit("love.audio.play") { args ->
            val handle = num(args, 1).toInt()
            val sid = soundIds[handle]
                ?: throw LuaError("Audio error: unknown source handle $handle")
            streamIds[handle] = soundPool.play(sid, masterVolume, masterVolume, 1, 0, 1f)
        }
        bindUnit("love.audio.stop") { args ->
            if (args.isNotEmpty() && args[0] is Number) {
                streamIds[num(args, 1).toInt()]?.let { soundPool.stop(it) }
            } else {
                soundPool.autoPause()
            }
        }
        bindUnit("love.audio.pause") { args ->
            if (args.isNotEmpty() && args[0] is Number) {
                streamIds[num(args, 1).toInt()]?.let { soundPool.pause(it) }
            } else soundPool.autoPause()
        }
        bindUnit("love.audio.resume") { args ->
            if (args.isNotEmpty() && args[0] is Number) {
                streamIds[num(args, 1).toInt()]?.let { soundPool.resume(it) }
            } else soundPool.autoResume()
        }
        bindUnit("love.audio.setVolume") { args -> masterVolume = num(args, 1, 1f).coerceIn(0f, 1f) }
        bind("love.audio.getVolume") { _ -> listOf(masterVolume.toDouble()) }
    }

    // ------------------------------------------------------------------
    // love.filesystem (sandboxed to the game's save directory)
    // ------------------------------------------------------------------

    private val saveDir: File by lazy {
        File(context.filesDir, "games/${gameDir.name}/savedata").apply { mkdirs() }
    }

    private fun resolveSavePath(path: String): File? {
        val f = File(saveDir, path)
        val canonical = runCatching { f.canonicalFile }.getOrDefault(f)
        if (!canonical.path.startsWith(runCatching { saveDir.canonicalFile }.getOrDefault(saveDir).path)) return null
        return canonical
    }

    /** Resolves a game-relative file path (sandboxed: no escaping the game folder). */
    private fun resolveGameFile(path: String): File? {
        val f = File(gameDir, path)
        val canonical = runCatching { f.canonicalFile }.getOrDefault(f)
        val rootCanonical = runCatching { gameDir.canonicalFile }.getOrDefault(gameDir)
        if (!canonical.path.startsWith(rootCanonical.path)) return null // sandbox escape attempt
        return canonical.takeIf { it.isFile }
    }

    private fun setupLoveFilesystem() {
        bind("love.filesystem.read") { args ->
            val f = resolveSavePath(str(args, 1))
                ?: return@bind listOf(null, "File does not exist in save directory")
            try {
                listOf(f.readText())
            } catch (e: Exception) {
                listOf(null, "Read error: ${e.message}")
            }
        }
        bind("love.filesystem.write") { args ->
            val f = resolveSavePath(str(args, 1))
                ?: throw LuaError("love.filesystem.write: sandbox escape blocked")
            f.parentFile?.mkdirs()
            try {
                f.writeText(str(args, 2))
                listOf(true)
            } catch (e: Exception) {
                listOf(null, "Write error: ${e.message}")
            }
        }
        bind("love.filesystem.append") { args ->
            val f = resolveSavePath(str(args, 1))
                ?: throw LuaError("love.filesystem.append: sandbox escape blocked")
            f.parentFile?.mkdirs()
            try {
                f.appendText(str(args, 2))
                listOf(true)
            } catch (e: Exception) {
                listOf(null, "Append error: ${e.message}")
            }
        }
        bind("love.filesystem.getInfo") { args ->
            val f = resolveSavePath(str(args, 1)) ?: return@bind listOf(null)
            if (!f.exists()) return@bind listOf(null)
            listOf(f.length().toDouble(), if (f.isDirectory) "directory" else "file")
        }
        bind("love.filesystem.getDirectoryItems") { args ->
            val dir = resolveSavePath(str(args, 1)) ?: saveDir
            dir.listFiles()?.map { it.name } ?: emptyList<String>()
        }
        bind("love.filesystem.remove") { args ->
            val f = resolveSavePath(str(args, 1))
                ?: throw LuaError("love.filesystem.remove: sandbox escape blocked")
            if (f.exists() && f.delete()) listOf(true) else listOf(null, "Remove failed")
        }
        bind("love.filesystem.createDirectory") { args ->
            val f = resolveSavePath(str(args, 1))
                ?: throw LuaError("love.filesystem.createDirectory: sandbox escape blocked")
            if (f.mkdirs() || f.isDirectory) listOf(true) else listOf(null, "CreateDirectory failed")
        }
    }

    // ------------------------------------------------------------------
    // love.math
    // ------------------------------------------------------------------

    private fun setupLoveMath() {
        bind("love.math.random") { args ->
            when {
                args.size >= 2 -> {
                    val min = dbl(args, 1); val max = dbl(args, 2)
                    listOf((min + rng.nextDouble() * (max - min)).let { if (min == max) min else kotlin.math.abs(it) })
                }
                args.size == 1 -> listOf(rng.nextDouble() * dbl(args, 1))
                else -> listOf(rng.nextDouble())
            }
        }
        bind("love.math.randomNormal") { args ->
            val stddev = dbl(args, 1, 1.0)
            val mean = dbl(args, 2)
            listOf(mean + stddev * rng.nextGaussian())
        }
        bind("love.math.noise") { args ->
            listOf(valueNoise(dbl(args, 1), dbl(args, 2), dbl(args, 3)))
        }
        bind("love.math.newTransform", unsupportedFunc("love.math.newTransform"))
        bind("love.math.newRandomGenerator", unsupportedFunc("love.math.newRandomGenerator (table-returning APIs use the LuaJ runtime)"))
    }

    /** Smooth 1D/2D/3D value noise in [0, 1). */
    private fun valueNoise(x: Double, y: Double, z: Double): Double {
        fun hash(i: Int, j: Int, k: Int): Double {
            var h = i * 374761393 + j * 668265263 + k * 2147483647
            h = (h xor (h shr 13)) * 1274126177
            h = h xor (h shr 16)
            return (h and 0x7fffffff).toDouble() / 0x7fffffff.toDouble()
        }
        fun smooth(t: Double): Double = t * t * (3 - 2 * t)
        fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val zi = floor(z).toInt()
        val xf = smooth(x - xi); val yf = smooth(y - yi); val zf = smooth(z - zi)

        val c000 = hash(xi, yi, zi); val c100 = hash(xi + 1, yi, zi)
        val c010 = hash(xi, yi + 1, zi); val c110 = hash(xi + 1, yi + 1, zi)
        val c001 = hash(xi, yi, zi + 1); val c101 = hash(xi + 1, yi, zi + 1)
        val c011 = hash(xi, yi + 1, zi + 1); val c111 = hash(xi + 1, yi + 1, zi + 1)

        val x00 = lerp(c000, c100, xf); val x10 = lerp(c010, c110, xf)
        val x01 = lerp(c001, c101, xf); val x11 = lerp(c011, c111, xf)
        val y0 = lerp(x00, x10, yf); val y1 = lerp(x01, x11, yf)
        return lerp(y0, y1, zf)
    }

    // ------------------------------------------------------------------
    // love.timer / love.window / love.system
    // ------------------------------------------------------------------

    private fun setupLoveTimer() {
        bind("love.timer.getTime") { _ -> listOf((System.nanoTime() - startTime) / 1e9) }
        bind("love.timer.getDelta") { _ -> listOf(lastDelta) }
        bind("love.timer.getFPS") { _ -> listOf(currentFps.toDouble()) }
        bindUnit("love.timer.step") { _ -> }
    }

    private fun setupLoveWindow() {
        bind("love.window.getWidth") { _ -> listOf(host.width().toDouble()) }
        bind("love.window.getHeight") { _ -> listOf(host.height().toDouble()) }
        bindUnit("love.window.setTitle") { args -> host.setTitle(str(args, 1)) }
        bind("love.window.setMode", unsupportedFunc("love.window.setMode"))
    }

    private fun setupLoveSystem() {
        bind("love.system.getOS") { _ -> listOf("Android") }
    }

    // ------------------------------------------------------------------
    // engine.* ??custom MobileStudio API (2D + 3D delegation to GameGLRenderer)
    // ------------------------------------------------------------------

    private fun setupEngine() {
        bindEngine("engine.clear") { args ->
            canvas().drawColor(android.graphics.Color.argb(255,
                (num(args, 1) * 255).toInt().coerceIn(0, 255),
                (num(args, 2) * 255).toInt().coerceIn(0, 255),
                (num(args, 3) * 255).toInt().coerceIn(0, 255)
            ))
            listOf<Any?>()
        }
        bindEngine("engine.drawRect") { args ->
            val x = num(args, 1); val y = num(args, 2)
            val w = num(args, 3); val h = num(args, 4)
            drawPaint.color = rgbFrom(args, 5, 6, 7, 1f)
            canvas().drawRect(x, y, x + w, y + h, drawPaint)
            listOf<Any?>()
        }
        bindEngine("engine.drawCircle") { args ->
            val x = num(args, 1); val y = num(args, 2); val r = num(args, 3)
            drawPaint.color = rgbFrom(args, 4, 5, 6, 1f)
            canvas().drawCircle(x, y, r, drawPaint)
            listOf<Any?>()
        }
        bindEngine("engine.drawText") { args ->
            canvas().drawText(str(args, 1), num(args, 2), num(args, 3), textPaint)
            listOf<Any?>()
        }
        bindEngine("engine.getDeltaTime") { _ -> listOf(lastDelta) }
        bindEngine("engine.getTime") { _ -> listOf((System.nanoTime() - startTime) / 1e9) }
        bindEngine("engine.isKeyDown") { args ->
            listOf(str(args, 1).lowercase() in host.keysDown())
        }
        bindEngine("engine.isTouchDown") { _ -> listOf(host.currentTouch() != null) }
        bindEngine("engine.getTouchX") { _ ->
            val t = host.currentTouch() ?: return@bindEngine listOf(null)
            listOf(t[0].toDouble())
        }
        bindEngine("engine.getTouchY") { _ ->
            val t = host.currentTouch() ?: return@bindEngine listOf(null)
            listOf(t[1].toDouble())
        }
        setupEngine3D()

        // ALSO bind the engine draw APIs as GLOBALS ??games commonly use
        // global names: clear(), drawRect(), drawCircle(), drawText(),
        // begin3D(), camera(), drawCube(), drawPlane(), end3D()
        listOf("clear", "drawRect", "drawCircle", "drawText",
            "begin3D", "camera", "drawCube", "drawPlane", "end3D"
        ).forEach { name ->
            val eid = engineIds[name] ?: return@forEach
            bind(name) { args -> LuaNativeBridge.invokeRegistered(eid, args) }
        }
        bind("screenWidth") { _ -> listOf(host.width().toDouble()) }
        bind("screenHeight") { _ -> listOf(host.height().toDouble()) }
    }

    /** engine.* callback ids by short name (for global aliases). */
    private val engineIds = mutableMapOf<String, Int>()

    private fun bindEngine(path: String, fn: (List<Any?>) -> List<Any?>) {
        val id = LuaNativeBridge.registerCallback(fn)
        engineIds[path.removePrefix("engine.")] = id
        if (!LuaNativeBridge.nativeBindCallback(path, id)) {
            host.log("[Lua ERROR] failed to bind $path")
        }
    }

    private fun setupEngine3D() {
        bindUnit("engine.begin3D") { _ ->
            in3D = true
            pending3DFaces.clear()
            host.log("[LUA] begin3D()")
            gl3d?.begin3D()
        }
        bindUnit("engine.camera") { args ->
            camX = num(args, 1); camY = num(args, 2); camZ = num(args, 3, 8f)
            camYaw = num(args, 4); camPitch = num(args, 5)
            gl3d?.camera(camX, camY, camZ, camYaw, camPitch)
        }
        bindUnit("engine.drawCube") { args ->
            require3D("engine.drawCube")
            val x = num(args, 1); val y = num(args, 2); val z = num(args, 3)
            val size = num(args, 4, 1f)
            val color = rgbFrom(args, 5, 6, 7, 1f)
            if (cubeLogCount < 5) {
                host.log("[LUA] drawCube(${String.format("%.1f, %.1f, %.1f, size=%.1f", x, y, z, size)})")
                cubeLogCount++
            }
            val renderer = gl3d
            if (renderer != null) {
                renderer.setDrawColor((color shr 16 and 0xFF) / 255f, (color shr 8 and 0xFF) / 255f, (color and 0xFF) / 255f, 1f)
                renderer.drawCube(x, y, z, size)
                return@bindUnit
            }
            emitCubeFaces(x, y, z, size, color)
        }
        bindUnit("engine.drawPlane") { args ->
            require3D("engine.drawPlane")
            val x = num(args, 1); val y = num(args, 2); val z = num(args, 3)
            val w = num(args, 4, 1f); val d = num(args, 5, 1f)
            val color = rgbFrom(args, 6, 7, 8, 1f)
            if (planeLogCount < 5) {
                host.log("[LUA] drawPlane(${String.format("%.1f, %.1f, %.1f, w=%.1f, d=%.1f", x, y, z, w, d)})")
                planeLogCount++
            }
            val renderer = gl3d
            if (renderer != null) {
                renderer.setDrawColor((color shr 16 and 0xFF) / 255f, (color shr 8 and 0xFF) / 255f, (color and 0xFF) / 255f, 1f)
                renderer.drawPlane(x, y, z, w, d)
                return@bindUnit
            }
            emitFace(listOf(
                floatArrayOf(x - w / 2, y, z - d / 2), floatArrayOf(x + w / 2, y, z - d / 2),
                floatArrayOf(x + w / 2, y, z + d / 2), floatArrayOf(x - w / 2, y, z + d / 2)
            ), color)
        }
        bindUnit("engine.end3D") { _ ->
            in3D = false
            gl3d?.end3D()
        }
        bind("engine.ecs", unsupportedFunc("engine.ecs (table-returning APIs use the LuaJ runtime)"))
    }

    private fun require3D(api: String) {
        if (!in3D) {
            throw LuaError("$api: 3D mode is not active. Call engine.begin3D() before 3D draw calls.")
        }
    }

    private fun emitCubeFaces(x: Float, y: Float, z: Float, size: Float, color: Int) {
        val h = size / 2f
        val corners = arrayOf(
            floatArrayOf(x - h, y - h, z - h), floatArrayOf(x + h, y - h, z - h),
            floatArrayOf(x + h, y + h, z - h), floatArrayOf(x - h, y + h, z - h),
            floatArrayOf(x - h, y - h, z + h), floatArrayOf(x + h, y - h, z + h),
            floatArrayOf(x + h, y + h, z + h), floatArrayOf(x - h, y + h, z + h)
        )
        val faces = listOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(4, 5, 6, 7),
            intArrayOf(0, 1, 5, 4), intArrayOf(2, 3, 7, 6),
            intArrayOf(0, 3, 7, 4), intArrayOf(1, 2, 6, 5)
        )
        for (face in faces) {
            emitFace(face.map { corners[it] }, color)
        }
    }

    /** Projects one face (world-space corners) into screen space and queues it. */
    private fun emitFace(corners: List<FloatArray>, color: Int) {
        val w = host.width().toFloat()
        val h = host.height().toFloat()
        val focal = w * 0.9f
        val cy = cos(-camYaw.toDouble()).toFloat(); val sy = sin(-camYaw.toDouble()).toFloat()
        val cp = cos(-camPitch.toDouble()).toFloat(); val sp = sin(-camPitch.toDouble()).toFloat()
        val screen = FloatArray(8)
        var avgZ = 0f
        var valid = true
        for ((i, c) in corners.withIndex()) {
            var vx = c[0] - camX; var vy = c[1] - camY; var vz = c[2] - camZ
            val tx = vx * cy + vz * sy; val tz = -vx * sy + vz * cy
            vx = tx; vz = tz
            val ty2 = vy * cp - vz * sp; val tz2 = vy * sp + vz * cp
            vy = ty2; vz = tz2
            if (vz < 0.1f) { valid = false; break }
            screen[i * 2] = (vx / vz) * focal + w / 2
            screen[i * 2 + 1] = -(vy / vz) * focal + h / 2
            avgZ += vz
        }
        if (!valid) return
        pending3DFaces.add(floatArrayOf(
            screen[0], screen[1], screen[2], screen[3],
            screen[4], screen[5], screen[6], screen[7],
            java.lang.Float.intBitsToFloat(color), avgZ / corners.size
        ))
    }

    /** Renders queued 3D faces depth-sorted (far ??near). Called by the host after draw. */
    fun render3DFaces(canvas: Canvas) {
        if (pending3DFaces.isEmpty()) return
        val sorted = pending3DFaces.sortedByDescending { it[9] }
        for (face in sorted) {
            val path = Path()
            path.moveTo(face[0], face[1])
            path.lineTo(face[2], face[3])
            path.lineTo(face[4], face[5])
            path.lineTo(face[6], face[7])
            path.close()
            drawPaint.color = java.lang.Float.floatToIntBits(face[8])
            drawPaint.style = Paint.Style.FILL
            canvas.drawPath(path, drawPaint)
        }
        pending3DFaces.clear()
    }

    // ------------------------------------------------------------------
    // Game loop callbacks (called by the host: GameRunnerActivity)
    // ------------------------------------------------------------------

    private fun callFn(name: String, vararg args: Any?) {
        val res = LuaNativeBridge.callGlobalJson(name, args.toList())
        if (res.error != null) {
            throw GameError(issueFromNativeError(res.error, "Runtime Error"))
        }
    }

    /** [LUA] debug logs so users can verify callbacks actually fire. */
    private fun logDebugCall(name: String, args: List<Any?>) {
        when (name) {
            "love.load" -> host.log("[LUA] love.load()")
            "love.update" -> if (updateLogCount < 3) {
                host.log("[LUA] love.update(dt=${String.format("%.4f", dbl(args, 1))})")
                updateLogCount++
            }
            "love.draw" -> if (drawLogCount < 3) {
                host.log("[LUA] love.draw()")
                drawLogCount++
            }
            "love.touchpressed" -> host.log("[LUA] love.touchpressed(id, x, y)")
            "love.touchreleased" -> host.log("[LUA] love.touchreleased(id, x, y)")
            "love.mousepressed" -> host.log("[LUA] love.mousepressed(x, y, button)")
            "love.keypressed" -> host.log("[LUA] love.keypressed(key)")
        }
    }

    fun callLoad() {
        startTime = System.nanoTime()
        logDebugCall("love.load", emptyList())
        callFn("love.load")
    }

    fun callUpdate(dt: Double) {
        lastDelta = dt
        fpsCounter++
        if (fpsWindowStart == 0L) fpsWindowStart = System.nanoTime()
        if (System.nanoTime() - fpsWindowStart >= 1_000_000_000) {
            currentFps = fpsCounter
            fpsCounter = 0
            fpsWindowStart = System.nanoTime()
        }
        logDebugCall("love.update", listOf(dt))
        callFn("love.update", dt)
    }

    fun callDraw() {
        logDebugCall("love.draw", emptyList())
        callFn("love.draw")
    }

    fun callTouch(action: String, id: Int, x: Float, y: Float) {
        when (action) {
            "down" -> {
                logDebugCall("love.touchpressed", listOf(id, x, y))
                callFn("love.touchpressed", id.toDouble(), x.toDouble(), y.toDouble())
                callFn("love.mousepressed", x.toDouble(), y.toDouble(), 1.0)
            }
            "up" -> {
                logDebugCall("love.touchreleased", listOf(id, x, y))
                callFn("love.touchreleased", id.toDouble(), x.toDouble(), y.toDouble())
                callFn("love.mousereleased", x.toDouble(), y.toDouble(), 1.0)
            }
            "move" -> {
                callFn("love.touchmoved", id.toDouble(), x.toDouble(), y.toDouble())
                callFn("love.mousemoved", x.toDouble(), y.toDouble())
            }
        }
    }

    fun callKey(key: String, isDown: Boolean) {
        if (isDown) {
            logDebugCall("love.keypressed", listOf(key))
            callFn("love.keypressed", key, key)
        } else {
            callFn("love.keyreleased", key, key)
        }
    }

    fun callWheel(dx: Float, dy: Float) {
        callFn("love.wheelmoved", dx.toDouble(), dy.toDouble())
    }

    // ------------------------------------------------------------------
    // MobileStudio comment
    // ------------------------------------------------------------------

    private fun registerApis() {
        // print ??MobileStudio LOG
        bind("print") { args ->
            val sb = StringBuilder()
            for (i in args.indices) {
                if (i > 0) sb.append('\t')
                sb.append(toDisplayString(args[i]))
            }
            host.log(sb.toString())
            emptyList()
        }
        // Sandbox: io/os are replaced with clear errors (same policy as LoveRuntime)
        LuaNativeBridge.nativeRestrictModule("io", "love.filesystem")
        LuaNativeBridge.nativeRestrictModule("os", "love.filesystem / os.time is unavailable")
        setupLoveGraphics()
        setupLoveAudio()
        setupLoveFilesystem()
        setupLoveMath()
        setupLoveTimer()
        setupLoveWindow()
        setupLoveSystem()
        setupEngine()
        host.log("[Lua] API registration complete (love.* + engine.* + print)")
    }
}
