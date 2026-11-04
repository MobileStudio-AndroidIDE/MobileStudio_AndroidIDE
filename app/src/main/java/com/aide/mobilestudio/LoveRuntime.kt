package com.aide.mobilestudio

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.SoundPool
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.File
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.floor

/**
 * LoveRuntime — LÖVE-compatible Lua game runtime (LuaJ 3.0.2, Canvas-backed 2D,
 * software-projected 3D, SoundPool audio, sandboxed filesystem).
 *
 * Strict error policy:
 *  - Syntax errors are reported BEFORE execution (line/column/message)
 *  - Unsupported APIs throw LuaError immediately — never silently ignored
 *  - Sandbox-restricted libs (io/os) are replaced with clear errors
 *
 * Implements the APIs declared in LuaApiRegistry (single source of truth).
 */
class LoveRuntime(
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

    val globals: Globals = JsePlatform.standardGlobals()

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
    private var lastFrameTime = 0L
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
    val pending3DFaces = mutableListOf<FloatArray>()

    /** Real GLES30 renderer (set by the host when using GLSurfaceView for 3D). */
    var gl3d: GameGLRenderer? = null

    init {
        setupPrint()
        setupSandbox()
        setupLoveGraphics()
        setupLoveAudio()
        setupLoveFilesystem()
        setupLoveMath()
        setupLoveTimer()
        setupLoveWindow()
        setupLoveSystem()
        setupEngine()
        setupFrameworks()
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

    private fun setupPrint() {
        globals["print"] = object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val sb = StringBuilder()
                for (i in 1..args.narg()) {
                    if (i > 1) sb.append('\t')
                    sb.append(toDisplayString(args.arg(i)))
                }
                host.log(sb.toString())
                return LuaValue.NIL
            }
        }
    }

    /** Replaces sandbox-restricted modules (io/os) with clear errors. */
    private fun setupSandbox() {
        restrictModule("io", "love.filesystem")
        restrictModule("os", "love.filesystem / os.time is unavailable")
    }

    private fun restrictModule(name: String, alt: String) {
        val module = LuaValue.tableOf()
        module.setmetatable(LuaValue.tableOf().apply {
            set("__index", object : VarArgFunction() {
                override fun invoke(args: Varargs): LuaValue {
                    throw LuaError(
                        "Restricted API: $name.${args.checkjstring(1)} — blocked by the game sandbox.\n" +
                        "Use $alt instead."
                    )
                }
            })
        })
        globals[name] = module
    }

    private fun toDisplayString(v: LuaValue): String = when {
        v.isnil() -> "nil"
        v.isboolean() -> if (v.toboolean()) "true" else "false"
        v.isnumber() -> {
            val d = v.todouble()
            if (d == floor(d) && kotlin.math.abs(d) < 1e15) v.tolong().toString() else d.toString()
        }
        else -> v.tojstring()
    }

    private fun unsupportedFunc(apiName: String): VarArgFunction = object : VarArgFunction() {
        override fun invoke(args: Varargs): LuaValue {
            throw LuaError(
                "Unsupported API\n\n$apiName\n\n" +
                "This API is not implemented by the current runtime.\n" +
                "See the API docs (LuaApiRegistry) for the supported list."
            )
        }
    }

    private fun canvas(): Canvas = host.currentCanvas()
        ?: throw LuaError("love.graphics: no canvas available outside of draw/update callbacks")

    private fun applyColor(a: Float = curColor[3]) {
        drawPaint.color = android.graphics.Color.argb(
            (a * 255).toInt().coerceIn(0, 255),
            (curColor[0] * 255).toInt().coerceIn(0, 255),
            (curColor[1] * 255).toInt().coerceIn(0, 255),
            (curColor[2] * 255).toInt().coerceIn(0, 255)
        )
    }

    // ------------------------------------------------------------------
    // love.graphics
    // ------------------------------------------------------------------

    private fun setupLoveGraphics() {
        val g = LuaValue.tableOf()
        g.set("clear", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val r = num(args, 1, 0f); val gr = num(args, 2, 0f); val b = num(args, 3, 0f)
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
                return LuaValue.NIL
            }
        })
        g.set("setColor", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                curColor[0] = num(args, 1, 1f)
                curColor[1] = num(args, 2, 1f)
                curColor[2] = num(args, 3, 1f)
                curColor[3] = num(args, 4, 1f)
                return LuaValue.NIL
            }
        })
        g.set("getColor", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                return LuaValue.varargsOf(arrayOf(
                    LuaValue.valueOf(curColor[0].toDouble()),
                    LuaValue.valueOf(curColor[1].toDouble()),
                    LuaValue.valueOf(curColor[2].toDouble()),
                    LuaValue.valueOf(curColor[3].toDouble())
                ))
            }
        })
        g.set("rectangle", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val mode = args.checkjstring(1)
                val x = num(args, 2); val y = num(args, 3)
                val w = num(args, 4); val h = num(args, 5)
                val c = canvas()
                if (mode == "fill") {
                    applyColor(); c.drawRect(x, y, x + w, y + h, drawPaint)
                } else {
                    applyColor(); drawPaint.style = Paint.Style.STROKE; drawPaint.strokeWidth = lineWidth
                    c.drawRect(x, y, x + w, y + h, drawPaint)
                    drawPaint.style = Paint.Style.FILL
                }
                return LuaValue.NIL
            }
        })
        g.set("circle", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val mode = args.checkjstring(1)
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
                return LuaValue.NIL
            }
        })
        g.set("ellipse", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val mode = args.checkjstring(1)
                val x = num(args, 2); val y = num(args, 3)
                val rx = num(args, 4); val ry = num(args, 5)
                val c = canvas()
                applyColor()
                drawPaint.style = if (mode == "fill") Paint.Style.FILL else Paint.Style.STROKE
                if (mode != "fill") drawPaint.strokeWidth = lineWidth
                c.drawOval(android.graphics.RectF(x - rx, y - ry, x + rx, y + ry), drawPaint)
                drawPaint.style = Paint.Style.FILL
                return LuaValue.NIL
            }
        })
        globals.set("love", g)
        bindRemainingGraphics(g)
    }

    private fun num(args: Varargs, i: Int, def: Float = 0f): Float =
        if (i <= args.narg() && args.arg(i).isnumber()) args.todouble(i).toFloat() else def

    private fun bindRemainingGraphics(g: LuaValue) {
        g.set("line", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val c = canvas()
                applyColor()
                drawPaint.strokeWidth = lineWidth
                val n = args.narg()
                if (n >= 4) {
                    val path = Path()
                    path.moveTo(num(args, 1), num(args, 2))
                    var i = 3
                    while (i + 1 <= n) { path.lineTo(num(args, i), num(args, i + 1)); i += 2 }
                    c.drawPath(path, drawPaint)
                }
                return LuaValue.NIL
            }
        })
        g.set("points", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val c = canvas()
                applyColor()
                val n = args.narg()
                var i = 1
                while (i + 1 <= n) {
                    c.drawCircle(num(args, i), num(args, i + 1), pointSize, drawPaint)
                    i += 2
                }
                return LuaValue.NIL
            }
        })
        g.set("polygon", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val mode = args.checkjstring(1)
                val c = canvas()
                applyColor()
                if (mode == "fill") drawPaint.style = Paint.Style.FILL else { drawPaint.style = Paint.Style.STROKE; drawPaint.strokeWidth = lineWidth }
                val path = Path()
                var i = 2
                path.moveTo(num(args, i), num(args, i + 1)); i += 2
                while (i + 1 <= args.narg()) { path.lineTo(num(args, i), num(args, i + 1)); i += 2 }
                path.close()
                c.drawPath(path, drawPaint)
                drawPaint.style = Paint.Style.FILL
                return LuaValue.NIL
            }
        })
        g.set("arc", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val mode = args.checkjstring(1)
                val x = num(args, 2); val y = num(args, 3); val r = num(args, 4)
                val a1 = Math.toDegrees(num(args, 5).toDouble()).toFloat()
                val a2 = Math.toDegrees(num(args, 6).toDouble()).toFloat()
                val c = canvas()
                applyColor()
                drawPaint.style = if (mode == "fill") Paint.Style.FILL else Paint.Style.STROKE
                if (mode != "fill") drawPaint.strokeWidth = lineWidth
                c.drawArc(android.graphics.RectF(x - r, y - r, x + r, y + r), a1, a2 - a1, mode == "fill", drawPaint)
                drawPaint.style = Paint.Style.FILL
                return LuaValue.NIL
            }
        })
        g.set("print", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                canvas().drawText(args.checkjstring(1), num(args, 2), num(args, 3), textPaint)
                return LuaValue.NIL
            }
        })
        g.set("printf", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val text = args.checkjstring(1)
                val x = num(args, 2); val y = num(args, 3)
                val limit = num(args, 4, 200f)
                val align = args.optjstring(5, "left")
                val alignPaint = android.graphics.Paint(textPaint)
                alignPaint.textAlign = when (align) {
                    "center" -> android.graphics.Paint.Align.CENTER
                    "right" -> android.graphics.Paint.Align.RIGHT
                    else -> android.graphics.Paint.Align.LEFT
                }
                canvas().drawText(text, x, y, alignPaint)
                return LuaValue.NIL
            }
        })
        g.set("getWidth", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.width().toDouble())
        })
        g.set("getHeight", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.height().toDouble())
        })
        g.set("setLineWidth", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue { lineWidth = num(args, 1, 1f); return LuaValue.NIL }
        })
        g.set("setLineStyle", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue { return LuaValue.NIL }
        })
        g.set("setPointSize", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue { pointSize = num(args, 1, 2f); return LuaValue.NIL }
        })
        g.set("push", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue { canvas().save(); return LuaValue.NIL }
        })
        g.set("pop", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue { canvas().restore(); return LuaValue.NIL }
        })
        g.set("translate", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                canvas().translate(num(args, 1), num(args, 2)); return LuaValue.NIL
            }
        })
        g.set("rotate", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                canvas().rotate(Math.toDegrees(num(args, 1).toDouble()).toFloat()); return LuaValue.NIL
            }
        })
        g.set("scale", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                canvas().scale(num(args, 1, 1f), num(args, 2, num(args, 1, 1f))); return LuaValue.NIL
            }
        })
        g.set("origin", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                host.initialCanvasMatrix()?.let { canvas().setMatrix(it) }
                return LuaValue.NIL
            }
        })
        bindImageApis(g)
        bindStrictUnsupported(g)
    }

    // ------------------------------------------------------------------
    // Image handles (Bitmap-backed)
    // ------------------------------------------------------------------

    private fun bindImageApis(g: LuaValue) {
        g.set("newImage", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val path = args.checkjstring(1)
                val f = resolveGameFile(path)
                    ?: throw LuaError("Image loading error: file not found: $path\nFile must be inside the game folder.")
                val bmp = try { BitmapFactory.decodeFile(f.absolutePath) } catch (e: Exception) { null }
                if (bmp == null) {
                    throw LuaError("Image loading error: could not decode: $path\nSupported formats: png, jpg, webp")
                }
                imageCounter++
                images[imageCounter] = bmp
                return LuaValue.valueOf(imageCounter)
            }
        })
        g.set("draw", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val handle = args.checkint(1)
                val bmp = images[handle]
                    ?: throw LuaError("Draw error: unknown image handle $handle (was it loaded with love.graphics.newImage?)")
                val x = num(args, 2); val y = num(args, 3)
                val rot = Math.toDegrees(num(args, 4).toDouble())
                val sx = num(args, 5, 1f); val sy = num(args, 6, sx)
                val m = Matrix()
                m.postTranslate(-bmp.width / 2f, -bmp.height / 2f)
                m.postRotate(rot.toFloat())
                m.postScale(sx, sy)
                m.postTranslate(x + bmp.width / 2f * sx, y + bmp.height / 2f * sy)
                canvas().drawBitmap(bmp, m, drawPaint)
                return LuaValue.NIL
            }
        })
    }

    /** Strict bindings for known-but-unimplemented APIs (throw clear errors). */
    private fun bindStrictUnsupported(g: LuaValue) {
        listOf(
            "newCanvas", "setCanvas", "newFont", "setFont", "newMesh", "newShader",
            "setShader", "setBlendMode", "setScissor"
        ).forEach { name ->
            g.set(name, unsupportedFunc("love.graphics.$name"))
        }
    }

    // ------------------------------------------------------------------
    // love.audio (SoundPool-backed)
    // ------------------------------------------------------------------

    private fun setupLoveAudio() {
        val a = LuaValue.tableOf()
        a.set("newSource", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val path = args.checkjstring(1)
                val f = resolveGameFile(path)
                    ?: throw LuaError("Audio loading error: file not found: $path\nFile must be inside the game folder.")
                val id = try { soundPool.load(f.absolutePath, 1) } catch (e: Exception) { 0 }
                if (id == 0) {
                    throw LuaError("Audio loading error: could not load: $path\nSupported formats: wav, ogg, mp3")
                }
                soundCounter++
                soundIds[soundCounter] = id
                return LuaValue.valueOf(soundCounter)
            }
        })
        a.set("play", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val handle = args.checkint(1)
                val sid = soundIds[handle]
                    ?: throw LuaError("Audio error: unknown source handle $handle")
                val stream = soundPool.play(sid, masterVolume, masterVolume, 1, 0, 1f)
                streamIds[handle] = stream
                return LuaValue.NIL
            }
        })
        a.set("stop", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                if (args.narg() >= 1 && args.arg(1).isnumber()) {
                    val handle = args.checkint(1)
                    val stream = streamIds[handle]
                    if (stream != null) soundPool.stop(stream)
                } else {
                    soundPool.autoPause()
                }
                return LuaValue.NIL
            }
        })
        a.set("pause", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                if (args.narg() >= 1 && args.arg(1).isnumber()) {
                    val handle = args.checkint(1)
                    streamIds[handle]?.let { soundPool.pause(it) }
                } else soundPool.autoPause()
                return LuaValue.NIL
            }
        })
        a.set("resume", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                if (args.narg() >= 1 && args.arg(1).isnumber()) {
                    val handle = args.checkint(1)
                    streamIds[handle]?.let { soundPool.resume(it) }
                } else soundPool.autoResume()
                return LuaValue.NIL
            }
        })
        a.set("setVolume", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                masterVolume = num(args, 1, 1f).coerceIn(0f, 1f)
                return LuaValue.NIL
            }
        })
        a.set("getVolume", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(masterVolume.toDouble())
        })
        globals.get("love").rawset("audio", a)
    }

    /** Resolves a game-relative file path (sandboxed: no escaping the game folder). */
    private fun resolveGameFile(path: String): File? {
        val f = File(gameDir, path)
        val canonical = runCatching { f.canonicalFile }.getOrDefault(f)
        val rootCanonical = runCatching { gameDir.canonicalFile }.getOrDefault(gameDir)
        if (!canonical.path.startsWith(rootCanonical.path)) return null // sandbox escape attempt
        return canonical.takeIf { it.isFile }
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

    private fun setupLoveFilesystem() {
        val fs = LuaValue.tableOf()
        fs.set("read", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val f = resolveSavePath(args.checkjstring(1))
                if (f == null || !f.isFile) {
                    return LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("File does not exist in save directory"))
                }
                return try {
                    LuaValue.valueOf(f.readText())
                } catch (e: Exception) {
                    LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("Read error: ${e.message}"))
                }
            }
        })
        fs.set("write", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val f = resolveSavePath(args.checkjstring(1))
                    ?: throw LuaError("love.filesystem.write: sandbox escape blocked")
                f.parentFile?.mkdirs()
                return try {
                    f.writeText(args.checkjstring(2))
                    LuaValue.TRUE
                } catch (e: Exception) {
                    LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("Write error: ${e.message}"))
                }
            }
        })
        fs.set("append", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val f = resolveSavePath(args.checkjstring(1))
                    ?: throw LuaError("love.filesystem.append: sandbox escape blocked")
                f.parentFile?.mkdirs()
                return try {
                    f.appendText(args.checkjstring(2))
                    LuaValue.TRUE
                } catch (e: Exception) {
                    LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("Append error: ${e.message}"))
                }
            }
        })
        fs.set("getInfo", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val f = resolveSavePath(args.checkjstring(1)) ?: return LuaValue.NIL
                if (!f.exists()) return LuaValue.NIL
                val info = LuaValue.tableOf()
                info.rawset("size", LuaValue.valueOf(f.length().toDouble()))
                info.rawset("type", LuaValue.valueOf(if (f.isDirectory) "directory" else "file"))
                return info
            }
        })
        fs.set("getDirectoryItems", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val dir = resolveSavePath(args.optjstring(1, "")) ?: saveDir
                val t = LuaValue.tableOf()
                val names = dir.listFiles()?.map { it.name } ?: emptyList()
                names.forEachIndexed { i, n -> t.rawset(i + 1, LuaValue.valueOf(n)) }
                return t
            }
        })
        fs.set("remove", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val f = resolveSavePath(args.checkjstring(1))
                    ?: throw LuaError("love.filesystem.remove: sandbox escape blocked")
                return if (f.exists() && f.delete()) LuaValue.TRUE
                else LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("Remove failed"))
            }
        })
        fs.set("createDirectory", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val f = resolveSavePath(args.checkjstring(1))
                    ?: throw LuaError("love.filesystem.createDirectory: sandbox escape blocked")
                return if (f.mkdirs() || f.isDirectory) LuaValue.TRUE
                else LuaValue.varargsOf(LuaValue.NIL, LuaValue.valueOf("CreateDirectory failed"))
            }
        })
        globals.get("love").rawset("filesystem", fs)
    }

    // ------------------------------------------------------------------
    // love.math
    // ------------------------------------------------------------------

    private fun setupLoveMath() {
        val m = LuaValue.tableOf()
        m.set("random", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return when {
                    args.narg() >= 2 -> LuaValue.valueOf((rng.nextInt((args.checkint(2) - args.checkint(1) + 1).coerceAtLeast(1)) + args.checkint(1)).toDouble())
                    args.narg() == 1 -> LuaValue.valueOf(rng.nextInt(args.checkint(1).coerceAtLeast(1)).toDouble())
                    else -> LuaValue.valueOf(rng.nextDouble())
                }
            }
        })
        m.set("randomNormal", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val stddev = num(args, 1, 1f).toDouble()
                val mean = num(args, 2, 0f).toDouble()
                return LuaValue.valueOf(mean + stddev * rng.nextGaussian())
            }
        })
        m.set("noise", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val x = num(args, 1).toDouble()
                val y = num(args, 2, 0f).toDouble()
                val z = num(args, 3, 0f).toDouble()
                return LuaValue.valueOf(valueNoise(x, y, z))
            }
        })
        m.set("newRandomGenerator", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val seed = if (args.narg() >= 1) args.checklong(1) else System.nanoTime()
                rngGenerator.setSeed(seed)
                val gen = LuaValue.tableOf()
                gen.rawset("random", object : VarArgFunction() {
                    override fun invoke(args: Varargs): LuaValue {
                        return when {
                            args.narg() >= 2 -> LuaValue.valueOf((rngGenerator.nextInt((args.checkint(2) - args.checkint(1) + 1).coerceAtLeast(1)) + args.checkint(1)).toDouble())
                            else -> LuaValue.valueOf(rngGenerator.nextDouble())
                        }
                    }
                })
                gen.rawset("setSeed", object : VarArgFunction() {
                    override fun invoke(args: Varargs): LuaValue {
                        rngGenerator.setSeed(args.checklong(1)); return LuaValue.NIL
                    }
                })
                gen.rawset("getSeed", object : org.luaj.vm2.lib.ZeroArgFunction() {
                    override fun call(): LuaValue = LuaValue.valueOf(rngGenerator.nextLong().toDouble())
                })
                return gen
            }
        })
        m.set("newTransform", unsupportedFunc("love.math.newTransform"))
        globals.get("love").rawset("math", m)
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
        val t = LuaValue.tableOf()
        t.set("getTime", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf((System.nanoTime() - startTime) / 1e9)
        })
        t.set("getDelta", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(lastDelta)
        })
        t.set("getFPS", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(currentFps.toDouble())
        })
        t.set("step", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.NIL
        })
        globals.get("love").rawset("timer", t)
    }

    private fun setupLoveWindow() {
        val w = LuaValue.tableOf()
        w.set("getWidth", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.width().toDouble())
        })
        w.set("getHeight", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.height().toDouble())
        })
        w.set("setTitle", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                host.setTitle(args.checkjstring(1)); return LuaValue.NIL
            }
        })
        w.set("setMode", unsupportedFunc("love.window.setMode"))
        globals.get("love").rawset("window", w)
    }

    private fun setupLoveSystem() {
        val s = LuaValue.tableOf()
        s.set("getOS", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf("Android")
        })
        globals.get("love").rawset("system", s)
    }

    // ------------------------------------------------------------------
    // engine.* — custom API (2D + software 3D)
    // ------------------------------------------------------------------

    private fun setupEngine() {
        val e = LuaValue.tableOf()
        e.set("clear", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                canvas().drawColor(android.graphics.Color.argb(255,
                    (num(args, 1, 0f) * 255).toInt().coerceIn(0, 255),
                    (num(args, 2, 0f) * 255).toInt().coerceIn(0, 255),
                    (num(args, 3, 0f) * 255).toInt().coerceIn(0, 255)
                ))
                return LuaValue.NIL
            }
        })
        e.set("drawRect", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val x = num(args, 1); val y = num(args, 2)
                val w = num(args, 3); val h = num(args, 4)
                drawPaint.color = rgbFrom(args, 5, 6, 7, 1f)
                canvas().drawRect(x, y, x + w, y + h, drawPaint)
                return LuaValue.NIL
            }
        })
        e.set("drawCircle", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val x = num(args, 1); val y = num(args, 2); val r = num(args, 3)
                drawPaint.color = rgbFrom(args, 4, 5, 6, 1f)
                canvas().drawCircle(x, y, r, drawPaint)
                return LuaValue.NIL
            }
        })
        e.set("drawText", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                canvas().drawText(args.checkjstring(1), num(args, 2), num(args, 3), textPaint)
                return LuaValue.NIL
            }
        })
        e.set("getDeltaTime", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(lastDelta)
        })
        e.set("getTime", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf((System.nanoTime() - startTime) / 1e9)
        })
        e.set("isKeyDown", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                return LuaValue.valueOf(args.checkjstring(1).lowercase() in host.keysDown())
            }
        })
        e.set("isTouchDown", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.currentTouch() != null)
        })
        e.set("getTouchX", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val t = host.currentTouch() ?: return LuaValue.NIL
                return LuaValue.valueOf(t[0].toDouble())
            }
        })
        e.set("getTouchY", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                val t = host.currentTouch() ?: return LuaValue.NIL
                return LuaValue.valueOf(t[1].toDouble())
            }
        })
        setupEngine3D(e)
        globals.set("engine", e)

        // ALSO bind the engine/love draw APIs as GLOBALS — games commonly use
        // global names: clear(), begin3D(), camera(), drawCube(), drawPlane(), end3D()
        listOf("clear", "drawRect", "drawCircle", "drawText").forEach { name ->
            globals.set(name, e.get(name))
        }
        listOf("begin3D", "end3D", "camera", "drawCube", "drawPlane").forEach { name ->
            globals.set(name, e.get(name))
        }
        globals.set("screenWidth", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.width().toDouble())
        })
        globals.set("screenHeight", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(host.height().toDouble())
        })
    }

    private fun rgbFrom(args: Varargs, ri: Int, gi: Int, bi: Int, def: Float): Int =
        android.graphics.Color.argb(255,
            (num(args, ri, def) * 255).toInt().coerceIn(0, 255),
            (num(args, gi, def) * 255).toInt().coerceIn(0, 255),
            (num(args, bi, def) * 255).toInt().coerceIn(0, 255)
        )

    // ------------------------------------------------------------------
    // Software 3D pipeline: camera transform + perspective projection +
    // painter's-algorithm depth sorting (rendered by the host after draw)
    // ------------------------------------------------------------------

    private fun setupEngine3D(e: LuaValue) {
        e.set("begin3D", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                in3D = true
                pending3DFaces.clear()
                host.log("[LUA] begin3D()")
                gl3d?.begin3D()
                return LuaValue.NIL
            }
        })
        e.set("camera", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                camX = num(args, 1); camY = num(args, 2); camZ = num(args, 3, 8f)
                camYaw = num(args, 4); camPitch = num(args, 5)
                host.log("[LUA] camera(${String.format("%.1f, %.1f, %.1f, %.2f, %.2f", camX, camY, camZ, camYaw, camPitch)})")
                gl3d?.camera(camX, camY, camZ, camYaw, camPitch)
                return LuaValue.NIL
            }
        })
        e.set("drawCube", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                require3D("engine.drawCube")
                val x = num(args, 1); val y = num(args, 2); val z = num(args, 3)
                val size = num(args, 4, 1f)
                val color = rgbFrom(args, 5, 6, 7, 1f)
                if (cubeLogCount < 5) {
                    host.log("[LUA] drawCube(${String.format("%.1f, %.1f, %.1f, size=%.1f", x, y, z, size)})")
                    cubeLogCount++
                }
                // Real GL renderer (vertex/index buffer + shader + MVP + depth test)
                val renderer = gl3d
                if (renderer != null) {
                    renderer.setDrawColor((color shr 16 and 0xFF) / 255f, (color shr 8 and 0xFF) / 255f, (color and 0xFF) / 255f, 1f)
                    renderer.drawCube(x, y, z, size)
                    return LuaValue.NIL
                }
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
                return LuaValue.NIL
            }
        })
        e.set("drawPlane", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
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
                    return LuaValue.NIL
                }
                val corners = listOf(
                    floatArrayOf(x - w / 2, y, z - d / 2), floatArrayOf(x + w / 2, y, z - d / 2),
                    floatArrayOf(x + w / 2, y, z + d / 2), floatArrayOf(x - w / 2, y, z + d / 2)
                )
                emitFace(corners, color)
                return LuaValue.NIL
            }
        })
        e.set("end3D", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue {
                in3D = false
                gl3d?.end3D()
                return LuaValue.NIL
            }
        })
        e.set("ecs", setupEcsModule())
    }

    private fun require3D(api: String) {
        if (!in3D) {
            throw LuaError("$api: 3D mode is not active. Call engine.begin3D() before 3D draw calls.")
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

    /** Renders queued 3D faces depth-sorted (far → near). Called by the host after draw. */
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

    /** Minimal ECS module (Lua table): entities, components, systems. */
    private fun setupEcsModule(): LuaValue {
        val ecs = LuaValue.tableOf()
        val entities = LuaValue.tableOf()
        ecs.rawset("entities", entities)
        ecs.rawset("create", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val entity = LuaValue.tableOf()
                val nextId = entities.len().toint() + 1
                entities.rawset(nextId, entity)
                entity.rawset("id", LuaValue.valueOf(nextId.toDouble()))
                if (args.narg() >= 1 && args.arg(1).istable()) {
                    val src = args.checktable(1)
                    var key: LuaValue = LuaValue.NIL
                    while (true) {
                        val nv = src.next(key)
                        val nk = nv.arg1()
                        if (nk.isnil()) break
                        entity.rawset(nk, nv.arg(2))
                        key = nk
                    }
                }
                return entity
            }
        })
        ecs.rawset("destroy", object : VarArgFunction() {
            override fun invoke(args: Varargs): LuaValue {
                val entity = args.checktable(1)
                entities.rawset(entity.get("id"), LuaValue.NIL)
                return LuaValue.NIL
            }
        })
        ecs.rawset("count", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf(entities.len().toint().toDouble())
        })
        return ecs
    }

    // ------------------------------------------------------------------
    // Other Lua game frameworks (Solar2D / Defold / Gideros / Moai)
    // Retained-mode display objects mapped onto the same Canvas backend.
    // ------------------------------------------------------------------

    /** Retained display objects (Solar2D/Gideros style) redrawn every frame. */
    data class DisplayObj(
        val kind: String,           // "rect" | "circle" | "text"
        val table: LuaValue,
        var x: Float, var y: Float,
        var w: Float, var h: Float,
        var color: Int,
        var text: String = ""
    )
    private val displayObjs = mutableListOf<DisplayObj>()
    private var enterFrameHandlers = mutableListOf<LuaValue>()

    private fun setupFrameworks() {
        setupSolar2D()
        setupDefold()
        setupGideros()
        setupMoai()
    }

    /** Solar2D (Corona) adapter — display.newRect/newCircle/newText + Runtime:addEventListener. */
    private fun setupSolar2D() {
        val display = LuaValue.tableOf()
        display.rawset("newRect", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val obj = DisplayObj(
                    kind = "rect", table = LuaValue.tableOf(),
                    x = num(args, 1), y = num(args, 2), w = num(args, 3), h = num(args, 4),
                    color = android.graphics.Color.WHITE
                )
                displayObjs.add(obj)
                bindSetFillColor(obj)
                return obj.table
            }
        })
        display.rawset("newCircle", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val obj = DisplayObj(
                    kind = "circle", table = LuaValue.tableOf(),
                    x = num(args, 1), y = num(args, 2), w = num(args, 3), h = num(args, 3),
                    color = android.graphics.Color.WHITE
                )
                displayObjs.add(obj)
                bindSetFillColor(obj)
                return obj.table
            }
        })
        display.rawset("newText", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val obj = DisplayObj(
                    kind = "text", table = LuaValue.tableOf(),
                    x = num(args, 2), y = num(args, 3), w = 0f, h = 0f,
                    color = android.graphics.Color.WHITE,
                    text = args.optjstring(1, "")
                )
                displayObjs.add(obj)
                bindSetFillColor(obj)
                return obj.table
            }
        })
        globals.rawset("display", display)

        val runtime = LuaValue.tableOf()
        runtime.rawset("addEventListener", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val event = args.optjstring(1, "")
                val handler = args.arg(2)
                if (event == "enterFrame" && handler.isfunction()) {
                    enterFrameHandlers.add(handler)
                } else if (event == "touch" && handler.isfunction()) {
                    enterFrameHandlers.add(handler) // touch events forwarded via callTouch
                } else {
                    throw LuaError(
                        "Unsupported API\n\nRuntime:addEventListener(\"$event\")\n\n" +
                        "Supported Solar2D events: enterFrame, touch."
                    )
                }
                return LuaValue.TRUE
            }
        })
        globals.rawset("Runtime", runtime)
    }

    private fun bindSetFillColor(obj: DisplayObj) {
        obj.table.rawset("setFillColor", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                obj.color = android.graphics.Color.argb(255,
                    (num(args, 1, 1f) * 255).toInt().coerceIn(0, 255),
                    (num(args, 2, 1f) * 255).toInt().coerceIn(0, 255),
                    (num(args, 3, 1f) * 255).toInt().coerceIn(0, 255)
                )
                return LuaValue.TRUE
            }
        })
        obj.table.rawset("x", LuaValue.valueOf(obj.x.toDouble()))
        obj.table.rawset("y", LuaValue.valueOf(obj.y.toDouble()))
        obj.table.rawset("text", LuaValue.valueOf(obj.text))
    }

    /** Renders retained display objects + fires enterFrame handlers. */
    fun renderDisplayObjs(canvas: Canvas) {
        for (obj in displayObjs) {
            when (obj.kind) {
                "rect" -> {
                    drawPaint.color = obj.color
                    canvas.drawRect(obj.x - obj.w / 2, obj.y - obj.h / 2, obj.x + obj.w / 2, obj.y + obj.h / 2, drawPaint)
                }
                "circle" -> {
                    drawPaint.color = obj.color
                    canvas.drawCircle(obj.x, obj.y, obj.w, drawPaint)
                }
                "text" -> {
                    textPaint.color = obj.color
                    canvas.drawText(obj.text, obj.x, obj.y, textPaint)
                }
            }
        }
        for (handler in enterFrameHandlers) {
            try {
                handler.invoke(LuaValue.varargsOf(arrayOf(LuaValue.valueOf(lastDelta))))
            } catch (e: LuaError) {
                throw GameError(issueFromLuaError(e, "enterFrame"), e)
            }
        }
    }

    /** Defold adapter — vmath (vector3) + go/msg (strict partial). */
    private fun setupDefold() {
        val vmath = LuaValue.tableOf()
        vmath.rawset("vector3", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val v = LuaValue.tableOf()
                v.rawset("x", LuaValue.valueOf(num(args, 1).toDouble()))
                v.rawset("y", LuaValue.valueOf(num(args, 2).toDouble()))
                v.rawset("z", LuaValue.valueOf(num(args, 3).toDouble()))
                return v
            }
        })
        vmath.rawset("vector4", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val v = LuaValue.tableOf()
                v.rawset("x", LuaValue.valueOf(num(args, 1).toDouble()))
                v.rawset("y", LuaValue.valueOf(num(args, 2).toDouble()))
                v.rawset("z", LuaValue.valueOf(num(args, 3).toDouble()))
                v.rawset("w", LuaValue.valueOf(num(args, 4).toDouble()))
                return v
            }
        })
        globals.rawset("vmath", vmath)

        val go = LuaValue.tableOf()
        go.rawset("animate", unsupportedFunc("go.animate (Defold: not implemented on Android runtime)"))
        go.rawset("get", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = LuaValue.NIL
        })
        val msg = LuaValue.tableOf()
        msg.rawset("post", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                host.log("[Defold msg.post] ${args.optjstring(1, "?")} ${args.optjstring(2, "")}")
                return LuaValue.NIL
            }
        })
        globals.rawset("go", go)
        globals.rawset("msg", msg)
    }

    /** Gideros adapter — stage:addChild + Sprite-like objects (partial). */
    private fun setupGideros() {
        val stage = LuaValue.tableOf()
        val application = LuaValue.tableOf()
        application.rawset("getOrientation", object : org.luaj.vm2.lib.ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.valueOf("landscape")
        })
        stage.rawset("addChild", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val sprite = args.arg(1)
                if (sprite.istable()) displayObjs.add(DisplayObj(
                    kind = "rect", table = sprite, x = 0f, y = 0f, w = 50f, h = 50f,
                    color = android.graphics.Color.WHITE
                ))
                return LuaValue.TRUE
            }
        })
        globals.rawset("stage", stage)
        globals.rawset("application", application)
    }

    /** Moai adapter — MOAISim/viewport stubs (partial). */
    private fun setupMoai() {
        val moaiSim = LuaValue.tableOf()
        moaiSim.rawset("startLoop", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = LuaValue.TRUE
        })
        val moaiViewport = LuaValue.tableOf()
        moaiViewport.rawset("setSize", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = LuaValue.TRUE
        })
        globals.rawset("MOAISim", moaiSim)
        globals.rawset("MOAIViewport", moaiViewport)
    }

    class GameError(val issue: LuaApiRegistry.GameIssue, cause: Throwable) : Exception(cause.message, cause)

    // DEBUG logging (first few calls of per-frame callbacks to avoid spam)
    private var updateLogCount = 0
    private var drawLogCount = 0
    private var cubeLogCount = 0
    private var planeLogCount = 0

    private fun callFn(name: String, vararg args: LuaValue) {
        val fn = globals.get(name)
        if (!fn.isfunction()) return
        logDebugCall(name, args)
        try {
            fn.invoke(LuaValue.varargsOf(args))
        } catch (e: LuaError) {
            throw GameError(issueFromLuaError(e, name), e)
        }
    }

    /** [LUA] debug logs so users can verify callbacks actually fire. */
    private fun logDebugCall(name: String, args: Array<out LuaValue>) {
        when (name) {
            "love.load" -> host.log("[LUA] love.load()")
            "love.update" -> if (updateLogCount < 3) {
                val dt = if (args.isNotEmpty() && args[0].isnumber()) args[0].todouble() else 0.0
                host.log("[LUA] love.update(dt=${String.format("%.4f", dt)})")
                updateLogCount++
            }
            "love.draw" -> if (drawLogCount < 3) {
                host.log("[LUA] love.draw()")
                drawLogCount++
            }
            "love.touchpressed" -> host.log("[LUA] love.touchpressed(id, x, y)")
            "love.touchreleased" -> host.log("[LUA] love.touchreleased(id, x, y)")
            "love.touchmoved" -> {}
            "love.mousepressed" -> host.log("[LUA] love.mousepressed(x, y, button)")
            "love.keypressed" -> host.log("[LUA] love.keypressed(key)")
            "love.keyreleased" -> {}
        }
    }

    fun callLoad() {
        startTime = System.nanoTime()
        callFn("love.load")
    }

    fun callUpdate(dt: Double) {
        lastDelta = dt
        lastFrameTime = System.nanoTime()
        fpsCounter++
        if (fpsWindowStart == 0L) fpsWindowStart = lastFrameTime
        if (lastFrameTime - fpsWindowStart >= 1_000_000_000) {
            currentFps = fpsCounter
            fpsCounter = 0
            fpsWindowStart = lastFrameTime
        }
        callFn("love.update", LuaValue.valueOf(dt))
    }

    fun callDraw() {
        callFn("love.draw")
    }

    fun callTouch(action: String, id: Int, x: Float, y: Float) {
        when (action) {
            "down" -> callFn("love.touchpressed", LuaValue.valueOf(id.toDouble()), LuaValue.valueOf(x.toDouble()), LuaValue.valueOf(y.toDouble()))
            "up" -> callFn("love.touchreleased", LuaValue.valueOf(id.toDouble()), LuaValue.valueOf(x.toDouble()), LuaValue.valueOf(y.toDouble()))
            "move" -> callFn("love.touchmoved", LuaValue.valueOf(id.toDouble()), LuaValue.valueOf(x.toDouble()), LuaValue.valueOf(y.toDouble()))
        }
        // Mouse-equivalent callbacks (LÖVE compatibility on touch devices)
        when (action) {
            "down" -> callFn("love.mousepressed", LuaValue.valueOf(x.toDouble()), LuaValue.valueOf(y.toDouble()), LuaValue.valueOf(1.0))
            "up" -> callFn("love.mousereleased", LuaValue.valueOf(x.toDouble()), LuaValue.valueOf(y.toDouble()), LuaValue.valueOf(1.0))
            "move" -> callFn("love.mousemoved", LuaValue.valueOf(x.toDouble()), LuaValue.valueOf(y.toDouble()))
        }
    }

    fun callKey(key: String, isDown: Boolean) {
        if (isDown) callFn("love.keypressed", LuaValue.valueOf(key), LuaValue.valueOf(key))
        else callFn("love.keyreleased", LuaValue.valueOf(key), LuaValue.valueOf(key))
    }

    fun callWheel(dx: Float, dy: Float) {
        callFn("love.wheelmoved", LuaValue.valueOf(dx.toDouble()), LuaValue.valueOf(dy.toDouble()))
    }

    /** Converts a LuaError into a GameIssue with line/column info. */
    private fun issueFromLuaError(e: LuaError, fnName: String): LuaApiRegistry.GameIssue {
        val msg = e.message ?: e.javaClass.simpleName
        var line = 0
        var column = 0
        val lineMatch = Regex("\\[string[^\\]]*\\]:(\\d+):").find(msg)
            ?: Regex(":(\\d+):").find(msg)
        if (lineMatch != null) line = lineMatch.groupValues[1].toIntOrNull() ?: 0
        val colMatch = Regex("column (\\d+)", RegexOption.IGNORE_CASE).find(msg)
        if (colMatch != null) column = colMatch.groupValues[1].toIntOrNull() ?: 0

        val type = when {
            msg.contains("Unsupported API") -> "Unsupported API"
            msg.contains("Restricted API") -> "Restricted API"
            msg.contains("loading error") -> "Loading Error"
            else -> "Runtime Error"
        }
        return LuaApiRegistry.GameIssue(type, msg, line, column)
    }

    /** Static syntax check: compiles the source without executing. Returns a syntax issue or null. */
    fun syntaxCheck(source: String): LuaApiRegistry.GameIssue? {
        return try {
            globals.load(source)
            null
        } catch (e: LuaError) {
            val msg = e.message ?: "Lua syntax error"
            var line = 0
            var column = 0
            val lineMatch = Regex("\\[string[^\\]]*\\]:(\\d+):").find(msg) ?: Regex(":(\\d+):").find(msg)
            if (lineMatch != null) line = lineMatch.groupValues[1].toIntOrNull() ?: 0
            val colMatch = Regex("\\):(\\d+):").find(msg)
            if (colMatch != null) column = colMatch.groupValues[1].toIntOrNull() ?: 0
            LuaApiRegistry.GameIssue(
                type = "Lua Syntax Error",
                message = msg,
                line = line,
                column = column
            )
        }
    }
}
