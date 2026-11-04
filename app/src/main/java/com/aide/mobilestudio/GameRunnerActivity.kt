package com.aide.mobilestudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.javascript.Context as JsContext
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File

/**
 * Direct in-process game player ??runs script-based games WITHOUT building an APK.
 * Supported engines:
 *  - lua      : LuaJ 3.0.2 (org.luaj.vm2)
 *  - js       : Mozilla Rhino 1.7.15 (interpreter mode)
 *  - gdscript : built-in mini-interpreter (GDScript syntax subset)
 *
 * Game API (identical across engines):
 *  drawRect(x,y,w,h,r,g,b), drawCircle(x,y,radius,r,g,b), drawText(text,x,y),
 *  clear(r,g,b), screenWidth, screenHeight, print(...),
 *  update(dt) [called every frame], onTouch(action,x,y) [down/move/up]
 */
class GameRunnerActivity : Activity() {

    companion object {
        private const val EXTRA_SCRIPT = "script_path"
        private const val EXTRA_ENGINE = "engine"
        private const val EXTRA_NAME = "game_name"
        private const val MAX_LOG_LINES = 400

        fun start(context: Context, scriptFile: File, engine: String, gameName: String) {
            context.startActivity(
                Intent(context, GameRunnerActivity::class.java)
                    .putExtra(EXTRA_SCRIPT, scriptFile.absolutePath)
                    .putExtra(EXTRA_ENGINE, engine)
                    .putExtra(EXTRA_NAME, gameName)
            )
        }

        /** Detects engine type from a game script file. */
        fun detectEngine(scriptFile: File): String? = when {
            scriptFile.name.equals("game.lua", true) || scriptFile.extension.equals("lua", true) -> "lua"
            scriptFile.name.equals("game.js", true) || scriptFile.extension.equals("js", true) -> "js"
            scriptFile.name.equals("game.gd", true) || scriptFile.extension.equals("gd", true) -> "gdscript"
            else -> null
        }

        /** Finds a game script inside a project directory (root first, then common asset locations). */
        fun findGameScript(projectDir: File): File? {
            // Script-only projects: main.lua / main.js / game.lua / game.gd at project root
            val rootFiles = projectDir.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name } ?: emptyList()
            rootFiles.firstOrNull {
                (it.name.equals("main.lua", true) || it.name.equals("game.lua", true) ||
                    it.name.equals("main.js", true) || it.name.equals("game.js", true) ||
                    it.name.equals("game.gd", true) || it.name.equals("main.gd", true) ||
                    it.name.equals("conf.lua", true))
            }?.let { return it }
            rootFiles.firstOrNull {
                it.extension.equals("lua", true) || it.extension.equals("gd", true)
            }?.let { return it }
            rootFiles.firstOrNull { it.extension.equals("js", true) }?.let { return it }

            val candidates = listOf(
                File(projectDir, "app/src/main/assets/game.lua"),
                File(projectDir, "src/main/assets/game.lua"),
                File(projectDir, "assets/game.lua"),
                File(projectDir, "app/src/main/assets/game.js"),
                File(projectDir, "src/main/assets/game.js"),
                File(projectDir, "assets/game.js"),
                File(projectDir, "app/src/main/assets/game.gd"),
                File(projectDir, "src/main/assets/game.gd"),
                File(projectDir, "assets/game.gd")
            )
            candidates.forEach { if (it.isFile) return it }
            // Fallback: any .lua/.gd game script in assets dirs
            listOf("app/src/main/assets", "src/main/assets", "assets").forEach { sub ->
                val d = File(projectDir, sub)
                if (d.isDirectory) {
                    d.listFiles()?.forEach { f ->
                        if (f.isFile && (f.extension.equals("lua", true) || f.extension.equals("gd", true))) return f
                    }
                }
            }
            return null
        }
    }

    private lateinit var gameView: GameRunnerView
    private var glRenderer: GameGLRenderer? = null
    private lateinit var logPanel: LinearLayout
    private lateinit var logText: TextView
    private lateinit var fpsText: TextView
    private var logVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scriptPath = intent.getStringExtra(EXTRA_SCRIPT) ?: ""
        val engine = intent.getStringExtra(EXTRA_ENGINE) ?: "lua"
        val gameName = intent.getStringExtra(EXTRA_NAME) ?: "Game"
        val scriptFile = File(scriptPath)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.InputBg))
        }

        // Top bar: title + log toggle + exit
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "\uD83C\uDFAE $gameName ($engine)"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val logBtn = Button(this).apply {
            text = "Log"
            textSize = 11f
            setOnClickListener {
                logVisible = !logVisible
                logPanel.visibility = if (logVisible) View.VISIBLE else View.GONE
            }
        }
        val exitBtn = Button(this).apply {
            text = "Exit"
            textSize = 11f
            setOnClickListener { finish() }
        }
        topBar.addView(title)
        topBar.addView(logBtn)
        topBar.addView(exitBtn)
        root.addView(topBar)

        // Game surface
        gameView = GameRunnerView(this, scriptFile, engine)
        gameView.onLog = { line -> appendLog(line) }
        gameView.onFps = { fps -> runOnUiThread { fpsText.text = "FPS: $fps" } }
        gameView.onError = { issue -> showGameError(issue, scriptPath) }

if (engine == "lua") {
    // Hybrid layers: GLSurfaceView (real GLES30 3D) under a transparent
    // Canvas view (2D love.graphics API). 3D calls delegate to the renderer.
    glRenderer = GameGLRenderer { line -> appendLog(line) }
    val glSurface = GLSurfaceView(this).apply {
        setEGLContextClientVersion(3)
        setRenderer(glRenderer)
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
    root.addView(glSurface, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    gameView.setBackgroundColor(Color.TRANSPARENT)
    root.addView(gameView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
} else {
    root.addView(gameView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
}
// NOW all callbacks (onLog/onFps/onError) are assigned — safe to init the engine.
// Syntax/runtime errors surface as the GAME ERROR screen from here on.
gameView.start()

        // Game log panel (hidden by default)
        logPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(StudioUi.color(StudioUi.Ink))
        }
        fpsText = TextView(this).apply {
            text = "FPS: 0"
            setTextColor(StudioUi.color(StudioUi.Green))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        logText = TextView(this).apply {
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(4), dp(8), dp(8))
            setTextIsSelectable(true)
        }
        val logScroll = android.widget.ScrollView(this)
        logScroll.addView(logText)
        logPanel.addView(fpsText)
        logPanel.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
        root.addView(logPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)

        if (!scriptFile.isFile) {
            appendLog("Error: script not found: $scriptPath")
        }
    }

    private fun appendLog(line: String) {
        runOnUiThread {
            logText.append(line + "\n")
            // Trim old lines to avoid unbounded growth
            val t = logText.text
            if (t.length > 60000) {
                logText.text = t.subSequence(t.length - 40000, t.length)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gameView.onTouch(e)
        return true
    }

    /** Hardware keys ??love.keypressed/keyreleased + engine.isKeyDown. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val name = when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> "back"
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "volume_up"
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "volume_down"
            android.view.KeyEvent.KEYCODE_DPAD_UP -> "up"
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_CENTER -> "return"
            android.view.KeyEvent.KEYCODE_SPACE -> "space"
            else -> android.view.KeyEvent.keyCodeToString(keyCode).lowercase().removePrefix("keycode_")
        }
        try {
            val viewField = GameRunnerView::class.java.getDeclaredField("loveRuntime")
            viewField.isAccessible = true
            when (val rt = viewField.get(gameView)) {
                is LoveRuntime -> rt.callKey(name, true)
                is NativeLuaRuntime -> rt.callKey(name, true)
            }
        } catch (_: Exception) {}
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val name = when (keyCode) {
            android.view.KeyEvent.KEYCODE_BACK -> "back"
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "volume_up"
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "volume_down"
            else -> android.view.KeyEvent.keyCodeToString(keyCode).lowercase().removePrefix("keycode_")
        }
        try {
            val viewField = GameRunnerView::class.java.getDeclaredField("loveRuntime")
            viewField.isAccessible = true
            when (val rt = viewField.get(gameView)) {
                is LoveRuntime -> rt.callKey(name, false)
                is NativeLuaRuntime -> rt.callKey(name, false)
            }
        } catch (_: Exception) {}
        return super.onKeyUp(keyCode, event)
    }

    /**
     * GAME ERROR screen (Windows Error style):
     * Type / File / Line / Column / code context + "Open in editor" button.
     */
    private fun showGameError(issue: LuaApiRegistry.GameIssue, scriptPath: String) {
        runOnUiThread {
            val overlay = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(StudioUi.color(StudioUi.Surface2))
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            overlay.addView(TextView(this).apply {
                text = "GAME ERROR"
                setTextColor(StudioUi.color(StudioUi.Red))
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
            })
            val rows = listOf(
                "Type" to issue.type,
                "File" to scriptPath.substringAfterLast('/'),
                "Line" to (if (issue.line > 0) issue.line.toString() else "-"),
                "Column" to (if (issue.column > 0) issue.column.toString() else "-")
            )
            for ((label, value) in rows) {
                overlay.addView(TextView(this).apply {
                    text = "$label: $value"
                    setTextColor(StudioUi.color(StudioUi.Text))
                    textSize = 13f
                    typeface = Typeface.MONOSPACE
                })
            }
            overlay.addView(TextView(this).apply {
                text = issue.message
                setTextColor(StudioUi.color(StudioUi.Amber))
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(8), 0, 0)
                setTextIsSelectable(true)
            })
            if (issue.codeContext.isNotBlank()) {
                overlay.addView(TextView(this).apply {
                    text = issue.codeContext
                    setTextColor(StudioUi.color(StudioUi.TextSoft))
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(8), 0, 0)
                    setTextIsSelectable(true)
                })
            }
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            btnRow.addView(Button(this).apply {
                text = "Open in editor"
                textSize = 12f
                setOnClickListener {
                    openInEditor(scriptPath, issue.line)
                }
            })
            btnRow.addView(Button(this).apply {
                text = "Continue"
                textSize = 12f
                setOnClickListener { overlay.visibility = View.GONE }
            })
            overlay.addView(btnRow)
            (findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(overlay)
        }
    }

    private fun openInEditor(scriptPath: String, line: Int) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("open_file", scriptPath)
                putExtra("open_line", line)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            appendLog("Cannot open editor: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        gameView.running = false
    }

    override fun onResume() {
        super.onResume()
        gameView.running = true
        gameView.invalidate()
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Native Lua VM: release memory and resources on exit
        when (val rt = gameView.loveRuntime) {
            is NativeLuaRuntime -> rt.close()
            else -> {}
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // -----------------------------------------------------------------
    // Game view: hosts the engine and drives the game loop
    // -----------------------------------------------------------------

    inner class GameRunnerView(context: Context, private val scriptFile: File, private val engineType: String) :
        View(context), LoveRuntime.Host, NativeLuaRuntime.Host {
        var running = true
        var onLog: ((String) -> Unit)? = null
        var onFps: ((Long) -> Unit)? = null
        var onError: ((LuaApiRegistry.GameIssue) -> Unit)? = null

        private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 48f
            isAntiAlias = true
        }
        private var lastFrameMs = 0L
        private var gameReady = false
        private var pendingCanvas: Canvas? = null
        private var frameCount = 0L
        private var fpsWindowStart = 0L
        private var fpsWindowCount = 0L
        private var scriptText = ""
        private var initialMatrix: android.graphics.Matrix? = null
        private var currentTouchPoint: FloatArray? = null
        private val pressedKeys = mutableSetOf<String>()

        // Lua runtime (NativeLuaRuntime = Lua 5.4.9 native, LoveRuntime = LuaJ fallback)
        var loveRuntime: Any? = null
        // JS state
        private var rhinoContext: JsContext? = null
        private var rhinoScope: Scriptable? = null
        // GDScript state
        private val gdVars = mutableMapOf<String, Double>()
        private var gdScript = ""

        init {
            setBackgroundColor(Color.BLACK)
        try {
            scriptText = if (scriptFile.isFile) scriptFile.readText() else ""
        } catch (e: Exception) {
            onLog?.invoke("Error reading script: ${e.message}")
        }
        // NOTE: engine init is DEFERRED to [start] — the host assigns
        // onLog/onError/onFps AFTER construction, so initializing here would
        // silently drop every syntax/runtime error (onError == null) and leave
        // the surface black with no GAME ERROR screen.
    }

    /** Host calls this AFTER onLog/onError/onFps are assigned. */
    fun start() {
        if (started) return
        started = true
        when (engineType) {
            "lua" -> initLua()
            "js" -> initJs()
            "gdscript" -> initGd()
            else -> onLog?.invoke("Unknown engine: $engineType")
        }
    }

    private var started = false

        // ---------------- LoveRuntime.Host ----------------

        override fun currentCanvas(): Canvas? = pendingCanvas
        override fun width(): Int = width
        override fun height(): Int = height
        override fun log(line: String) { onLog?.invoke(line) }
        override fun currentTouch(): FloatArray? = currentTouchPoint
        override fun keysDown(): Set<String> = pressedKeys
        override fun setTitle(title: String) {
            (context as? GameRunnerActivity)?.let { act ->
                runOnUiThread { act.title = title }
            }
        }
        override fun initialCanvasMatrix(): android.graphics.Matrix? = initialMatrix

        // ---------------- LUA (LÖVE runtime) ----------------

        private fun initLua() {
            if (LuaNativeBridge.available) {
                try {
                    initNativeLua()
                    return
                } catch (e: NativeLuaRuntime.GameError) {
                    onError?.invoke(e.issue)
                    onLog?.invoke("GAME ERROR: ${e.issue.type} (line ${e.issue.line})")
                    return
                } catch (e: Exception) {
                    onLog?.invoke("[Lua] native runtime failed: ${e.message} → falling back to LuaJ")
                }
            } else {
                onLog?.invoke("[Lua] native runtime unavailable → using LuaJ fallback")
            }
            try {
                initLuaJ()
            } catch (e: LoveRuntime.GameError) {
                onError?.invoke(e.issue)
                onLog?.invoke("GAME ERROR: ${e.issue.type} (line ${e.issue.line})")
            } catch (e: Exception) {
                onLog?.invoke("Lua error: ${e.message}")
            }
        }

        /**
         * Native Lua 5.4.9 runtime:
          * MobileStudio documentation.
          * MobileStudio documentation.
         */
        private fun initNativeLua() {
            val rt = NativeLuaRuntime(this@GameRunnerActivity, this, scriptFile.parentFile ?: File("."))
            // Connect the real GLES30 renderer (3D via GLSurfaceView layer)
            (this@GameRunnerActivity as? GameRunnerActivity)?.glRenderer?.let { rt.gl3d = it }
            loveRuntime = rt

            // 1. Static API validation (unsupported/restricted APIs, before execution)
            val syntaxIssue = rt.syntaxCheck(scriptText)
            val apiIssues = LuaApiRegistry.staticValidate(scriptText, syntaxIssue == null, syntaxIssue)
            val fatal = apiIssues.firstOrNull()
            if (fatal != null) {
                onError?.invoke(fatal)
                onLog?.invoke("GAME ERROR: ${fatal.type} (line ${fatal.line})")
                onLog?.invoke(fatal.message)
                return
            }
            if (scriptText.isBlank()) {
                onLog?.invoke("Error: empty script")
                return
            }

            // MobileStudio comment
            rt.init(scriptFile)
            gameReady = true
            onLog?.invoke("[Lua game loaded → Native Lua 5.4.9 + LÖVE runtime]")
            onLog?.invoke("API support: ${LuaApiRegistry.supportedCount()} supported, ${LuaApiRegistry.partialCount()} partial, ${LuaApiRegistry.unsupportedCount()} unsupported")
            // GL diagnostics (surface size, shaders, version) ??visible in the log
            (this@GameRunnerActivity as? GameRunnerActivity)?.glRenderer?.let { glr ->
                onLog?.invoke("[GL] 3D layer active → waiting for surface (see GL logs on first frame)")
                if (!glr.glReady) glr.logDiagnostics()
            }
        }

        /** 기존 LuaJ (Lua 5.2) 기반 런타임 — liblua_game.so 로드 실패 시 폴백. */
        private fun initLuaJ() {
            try {
                val rt = LoveRuntime(this@GameRunnerActivity, this, scriptFile.parentFile ?: File("."))
                // Connect the real GLES30 renderer (3D via GLSurfaceView layer)
                (this@GameRunnerActivity as? GameRunnerActivity)?.glRenderer?.let { rt.gl3d = it }
                loveRuntime = rt

                // 1. Syntax check (before execution)
                val syntaxIssue = rt.syntaxCheck(scriptText)
                // 2. Static API validation (unsupported/restricted APIs)
                val apiIssues = LuaApiRegistry.staticValidate(scriptText, syntaxIssue == null, syntaxIssue)

                val fatal = apiIssues.firstOrNull()
                if (fatal != null) {
                    onError?.invoke(fatal)
                    onLog?.invoke("GAME ERROR: ${fatal.type} (line ${fatal.line})")
                    onLog?.invoke(fatal.message)
                    return
                }

                if (scriptText.isBlank()) {
                    onLog?.invoke("Error: empty script")
                    return
                }

                // 3. Execute love.load()
                rt.callLoad()
                gameReady = true
                onLog?.invoke("[Lua game loaded → LÖVE runtime]")
                onLog?.invoke("API support: ${LuaApiRegistry.supportedCount()} supported, ${LuaApiRegistry.partialCount()} partial, ${LuaApiRegistry.unsupportedCount()} unsupported")
                // GL diagnostics (surface size, shaders, version) ??visible in the log
                (this@GameRunnerActivity as? GameRunnerActivity)?.glRenderer?.let { glr ->
                    onLog?.invoke("[GL] 3D layer active → waiting for surface (see GL logs on first frame)")
                    if (!glr.glReady) glr.logDiagnostics()
                }
            } catch (e: LoveRuntime.GameError) {
                onError?.invoke(e.issue)
                onLog?.invoke("GAME ERROR: ${e.issue.type} (line ${e.issue.line})")
            } catch (e: Exception) {
                onLog?.invoke("Lua error: ${e.message}")
            }
        }

        private fun colorOf(r: Float, g: Float, b: Float): Int = Color.rgb(
            (r * 255).toInt().coerceIn(0, 255),
            (g * 255).toInt().coerceIn(0, 255),
            (b * 255).toInt().coerceIn(0, 255)
        )

        // ---------------- JS (Rhino) ----------------

        private fun initJs() {
            try {
                val ctx = JsContext.enter()
                ctx.optimizationLevel = -1 // interpreter mode required on ART
                rhinoContext = ctx
                val scope = ctx.initStandardObjects()
                rhinoScope = scope

                val host = HostApi()
                ScriptableObject.putProperty(scope, "__host", JsContext.javaToJS(host, scope))
                ctx.evaluateString(scope, """
                    function drawRect(x,y,w,h,r,g,b){ __host.drawRect(Number(x),Number(y),Number(w),Number(h),Number(r),Number(g),Number(b)); }
                    function drawCircle(x,y,rad,r,g,b){ __host.drawCircle(Number(x),Number(y),Number(rad),Number(r),Number(g),Number(b)); }
                    function drawText(t,x,y){ __host.drawText(String(t),Number(x),Number(y)); }
                    function clear(r,g,b){ __host.clear(Number(r),Number(g),Number(b)); }
                    function print(s){ __host.print(String(s)); }
                """.trimIndent(), "bindings", 1, null)

                if (scriptText.isNotBlank()) {
                    ctx.evaluateString(scope, scriptText, "game.js", 1, null)
                    gameReady = true
                    onLog?.invoke("[JS game loaded]")
                } else {
                    onLog?.invoke("Error: empty script")
                }
            } catch (e: Exception) {
                onLog?.invoke("JS error: ${e.message}")
            }
        }

        inner class HostApi {
            fun drawRect(x: Double, y: Double, w: Double, h: Double, r: Double, g: Double, b: Double) {
                val c = pendingCanvas ?: return
                drawPaint.color = colorOf(x(r), x(g), x(b))
                c.drawRect(x(x), x(y), x(x) + x(w), x(y) + x(h), drawPaint)
            }
            fun drawCircle(x: Double, y: Double, rad: Double, r: Double, g: Double, b: Double) {
                val c = pendingCanvas ?: return
                drawPaint.color = colorOf(x(r), x(g), x(b))
                c.drawCircle(x(x), x(y), x(rad), drawPaint)
            }
            fun drawText(t: String, x: Double, y: Double) {
                pendingCanvas?.drawText(t, x(x), x(y), textPaint)
            }
            fun clear(r: Double, g: Double, b: Double) {
                pendingCanvas?.drawColor(colorOf(x(r), x(g), x(b)))
            }
            fun print(s: String) {
                onLog?.invoke(s)
            }
            private fun x(v: Double): Float = v.toFloat()
        }

        // ---------------- GDScript (mini-interpreter) ----------------

        private fun initGd() {
            gdVars["screen_width"] = 0.0
            gdVars["screen_height"] = 0.0
            if (scriptText.isNotBlank()) {
                gameReady = true
                onLog?.invoke("[GDScript game loaded]")
            } else {
                onLog?.invoke("Error: empty script")
            }
        }

        private fun gdRunFunc(funcName: String, args: List<String>) {
            val re = Regex("(?:^|\\n)\\s*func\\s+${Regex.escape(funcName)}\\s*\\([^)]*\\)\\s*:?\\s*\\n([\\s\\S]*?)(?=\\n\\s*func\\s|\\z)")
            val body = re.find(gdScript)?.groupValues?.get(1) ?: return
            for (line in body.lines()) {
                val t = line.trim()
                if (t.isBlank() || t.startsWith("#")) continue
                when {
                    t.startsWith("var ") -> {
                        val p2 = t.removePrefix("var ").split("=", limit = 2)
                        if (p2.size == 2) gdVars[p2[0].trim()] = gdEval(p2[1].trim())
                    }
                    t.contains("=") && !t.startsWith("if") && !t.contains("==") -> {
                        val p2 = t.split("=", limit = 2)
                        if (p2.size == 2) gdVars[p2[0].trim()] = gdEval(p2[1].trim())
                    }
                    t.startsWith("draw_rect(") -> {
                        val a = t.removePrefix("draw_rect(").removeSuffix(")").split(",").map { it.trim() }
                        if (a.size >= 7) {
                            drawPaint.color = colorOf(gdEval(a[4]).toFloat(), gdEval(a[5]).toFloat(), gdEval(a[6]).toFloat())
                            pendingCanvas?.let { c ->
                                val xi = gdEval(a[0]).toFloat(); val yi = gdEval(a[1]).toFloat()
                                c.drawRect(xi, yi, xi + gdEval(a[2]).toFloat(), yi + gdEval(a[3]).toFloat(), drawPaint)
                            }
                        }
                    }
                    t.startsWith("draw_circle(") -> {
                        val a = t.removePrefix("draw_circle(").removeSuffix(")").split(",").map { it.trim() }
                        if (a.size >= 6) {
                            drawPaint.color = colorOf(gdEval(a[3]).toFloat(), gdEval(a[4]).toFloat(), gdEval(a[5]).toFloat())
                            pendingCanvas?.drawCircle(gdEval(a[0]).toFloat(), gdEval(a[1]).toFloat(), gdEval(a[2]).toFloat(), drawPaint)
                        }
                    }
                    t.startsWith("draw_text(") -> {
                        val a = t.removePrefix("draw_text(").removeSuffix(")").split(",").map { it.trim() }
                        if (a.size >= 3) {
                            pendingCanvas?.drawText(gdEvalText(a[0]), gdEval(a[1]).toFloat(), gdEval(a[2]).toFloat(), textPaint)
                        }
                    }
                    t.startsWith("print(") -> {
                        val inner = t.removePrefix("print(").removeSuffix(")")
                        onLog?.invoke(gdEvalText(inner))
                    }
                    t.startsWith("clear(") -> {
                        val a = t.removePrefix("clear(").removeSuffix(")").split(",").map { it.trim() }
                        pendingCanvas?.drawColor(
                            if (a.size >= 3) colorOf(gdEval(a[0]).toFloat(), gdEval(a[1]).toFloat(), gdEval(a[2]).toFloat())
                            else Color.rgb(13, 18, 31)
                        )
                    }
                }
            }
        }

        private fun gdEval(s: String): Double {
            val v = gdVars[s]
            if (v != null) return v
            val d = s.toDoubleOrNull()
            if (d != null) return d
            // str(...) / string concat
            if (s.startsWith("str(") && s.endsWith(")")) return gdEval(s.substring(4, s.length - 1))
            val concat = Regex("^(.+?)\\s*\\+\\s*(.+)$").find(s)
            if (concat != null) {
                val left = gdVars[concat.groupValues[1].trim()]
                if (left != null) return left
                val a = concat.groupValues[1].trim().toDoubleOrNull()
                val b = concat.groupValues[2].trim().toDoubleOrNull()
                if (a != null && b != null) return a + b
            }
            val op = Regex("(.+?)([+\\-*/])(.+)")
            for (m in op.findAll(s)) {
                val a = gdEval(m.groupValues[1].trim())
                val b = gdEval(m.groupValues[3].trim())
                return when (m.groupValues[2]) {
                    "+" -> a + b; "-" -> a - b; "*" -> a * b; "/" -> if (b != 0.0) a / b else 0.0
                    else -> 0.0
                }
            }
            return 0.0
        }

        private fun gdEvalText(s: String): String {
            val t = s.trim().removeSurrounding("\"")
            // String concat: "text" + var
            val concat = Regex("^(.*?)\\s*\\+\\s*(.+)$").find(t)
            if (concat != null) {
                val leftPart = concat.groupValues[1].trim().removeSurrounding("\"")
                val rightName = concat.groupValues[2].trim().removeSurrounding("\"")
                val rightVal = gdVars[rightName]
                return if (rightVal != null) "$leftPart${formatNum(rightVal)}" else leftPart
            }
            val v = gdVars[t]
            return if (v != null) formatNum(v) else t
        }

        private fun formatNum(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

        // ---------------- Input + game loop ----------------

        fun onTouch(e: MotionEvent) {
            // Track touch for engine.isTouchDown/getTouchX/getTouchY
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                currentTouchPoint = floatArrayOf(e.x, e.y)
            } else if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                currentTouchPoint = null
            }
            if (!gameReady) return
            val action = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> "down"
                MotionEvent.ACTION_MOVE -> "move"
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
                else -> ""
            }
            if (action.isEmpty()) return
            try {
                when (engineType) {
                    "lua" -> {
                        val pid = e.getPointerId(0)
                        when (val rt = loveRuntime) {
                            is NativeLuaRuntime -> rt.callTouch(action, pid, e.x, e.y)
                            is LoveRuntime -> rt.callTouch(action, pid, e.x, e.y)
                        }
                    }
                    "js" -> {
                        val ctx = rhinoContext
                        val scope = rhinoScope
                        if (ctx != null && scope != null) {
                            val touchFn = scope.get("onTouch", scope)
                            if (touchFn != null && touchFn != Scriptable.NOT_FOUND) {
                                ctx.evaluateString(
                                    scope,
                                    "onTouch('$action',${e.x.toDouble()},${e.y.toDouble()})",
                                    "touch", 1, null
                                )
                            }
                        }
                    }
                    "gdscript" -> {
                        gdVars["touch_x"] = e.x.toDouble()
                        gdVars["touch_y"] = e.y.toDouble()
                        gdRunFunc("on_touch", listOf(action, e.x.toString(), e.y.toString()))
                    }
                }
            } catch (e2: Exception) {
                onLog?.invoke("Input error: ${e2.message}")
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!running || !gameReady) return
            val now = System.currentTimeMillis()
            val dt = if (lastFrameMs == 0L) 0.0 else (now - lastFrameMs) / 1000.0
            lastFrameMs = now

            // FPS tracking (per 1s window)
            frameCount++
            fpsWindowCount++
            if (fpsWindowStart == 0L) fpsWindowStart = now
            if (now - fpsWindowStart >= 1000) {
                onFps?.invoke(fpsWindowCount)
                fpsWindowStart = now
                fpsWindowCount = 0
            }

            // Capture the initial matrix for love.graphics.origin()
            initialMatrix = android.graphics.Matrix(canvas.matrix)
            pendingCanvas = canvas
            try {
                when (engineType) {
                    "lua" -> {
                        when (val rt = loveRuntime) {
                            is NativeLuaRuntime -> {
                                rt.callUpdate(dt)
                                rt.callDraw()
                                rt.render3DFaces(canvas)
                            }
                            is LoveRuntime -> {
                                rt.callUpdate(dt)
                                rt.callDraw()
                                rt.render3DFaces(canvas)
                            }
                        }
                    }
                    "js" -> {
                        val ctx = rhinoContext
                        val scope = rhinoScope
                        if (ctx != null && scope != null) {
                            ScriptableObject.putProperty(scope, "screenWidth", width)
                            ScriptableObject.putProperty(scope, "screenHeight", height)
                            val updateFn = scope.get("update", scope)
                            if (updateFn != null && updateFn != Scriptable.NOT_FOUND) {
                                ctx.evaluateString(scope, "update($dt)", "update", 1, null)
                            }
                        }
                    }
                    "gdscript" -> {
                        gdVars["screen_width"] = width.toDouble()
                        gdVars["screen_height"] = height.toDouble()
                        gdRunFunc("_process", listOf(dt.toString()))
                    }
            }
        } catch (e: NativeLuaRuntime.GameError) {
            // Runtime error inside love.update/love.draw: stop the loop and show
            // the GAME ERROR screen with the Lua error location (never a silent hang).
            running = false
            gameReady = false
            onError?.invoke(e.issue)
            onLog?.invoke("GAME ERROR: ${e.issue.type} (line ${e.issue.line})")
            onLog?.invoke(e.issue.message)
        } catch (e: LoveRuntime.GameError) {
            running = false
            gameReady = false
            onError?.invoke(e.issue)
            onLog?.invoke("GAME ERROR: ${e.issue.type} (line ${e.issue.line})")
            onLog?.invoke(e.issue.message)
        } catch (e: Exception) {
            running = false
            gameReady = false
            onLog?.invoke("Frame error: ${e.message}")
            onError?.invoke(LuaApiRegistry.GameIssue("Runtime Error", e.message ?: "unknown", 0, 0))
        }
        pendingCanvas = null
        if (running) invalidate()
    }
}
}
