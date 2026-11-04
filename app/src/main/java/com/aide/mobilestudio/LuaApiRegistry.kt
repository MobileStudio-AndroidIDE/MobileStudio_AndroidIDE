package com.aide.mobilestudio

/**
 * Lua Game Runtime API registry — the single source of truth for:
 *  - API support status (Supported / Partial / Unsupported)
 *  - API documentation (signature, description, example)
 *  - Static validation of game scripts BEFORE execution
 *
 * The runtime (GameRunnerActivity) binds EXACTLY these APIs — anything not
 * listed as Supported/Partial is reported as an error, never silently ignored.
 */
object LuaApiRegistry {

    data class ApiInfo(
        val name: String,        // "love.graphics.rectangle"
        val signature: String,   // "rectangle(mode, x, y, w, h)"
        val description: String,
        val support: String,     // "Supported" | "Partial" | "Unsupported"
        val example: String = ""
    )

    const val SUPPORTED = "Supported"
    const val PARTIAL = "Partial"
    const val UNSUPPORTED = "Unsupported"

    // ------------------------------------------------------------------
    // love.graphics — Canvas-backed 2D renderer
    // ------------------------------------------------------------------
    private val graphicsApis = listOf(
        ApiInfo("love.graphics.clear", "clear(r, g, b)", "Fills the screen with a color (0.0-1.0 components).", SUPPORTED, "love.graphics.clear(0.1, 0.1, 0.15)"),
        ApiInfo("love.graphics.setColor", "setColor(r, g, b, a)", "Sets the draw color (0.0-1.0 components, a defaults to 1).", SUPPORTED, "love.graphics.setColor(1, 0.5, 0.2)"),
        ApiInfo("love.graphics.getColor", "getColor()", "Returns the current draw color as r, g, b, a.", SUPPORTED, "local r, g, b, a = love.graphics.getColor()"),
        ApiInfo("love.graphics.rectangle", "rectangle(mode, x, y, w, h)", "Draws a rectangle. mode is \"fill\" or \"line\".", SUPPORTED, "love.graphics.rectangle(\"fill\", 10, 20, 100, 50)"),
        ApiInfo("love.graphics.circle", "circle(mode, x, y, radius)", "Draws a circle. mode is \"fill\" or \"line\".", SUPPORTED, "love.graphics.circle(\"fill\", 200, 200, 30)"),
        ApiInfo("love.graphics.ellipse", "ellipse(mode, x, y, rx, ry)", "Draws an ellipse.", SUPPORTED, "love.graphics.ellipse(\"fill\", 100, 100, 60, 30)"),
        ApiInfo("love.graphics.line", "line(x1, y1, x2, y2, ...)", "Draws lines through the given points.", SUPPORTED, "love.graphics.line(10, 10, 100, 100, 200, 50)"),
        ApiInfo("love.graphics.points", "points(x1, y1, x2, y2, ...)", "Draws one or more points.", SUPPORTED, "love.graphics.points(10, 20, 30, 40)"),
        ApiInfo("love.graphics.polygon", "polygon(mode, x1, y1, ...)", "Draws a polygon through the given vertices.", SUPPORTED, "love.graphics.polygon(\"fill\", 100, 10, 200, 100, 100, 190)"),
        ApiInfo("love.graphics.arc", "arc(mode, x, y, radius, angle1, angle2)", "Draws an arc (angles in radians).", SUPPORTED, "love.graphics.arc(\"line\", 200, 200, 50, 0, 3.14)"),
        ApiInfo("love.graphics.print", "print(text, x, y)", "Draws text at the given position.", SUPPORTED, "love.graphics.print(\"Hello\", 20, 40)"),
        ApiInfo("love.graphics.printf", "printf(text, x, y, limit, align)", "Draws formatted/wrapped text. align: left/center/right.", SUPPORTED, "love.graphics.printf(\"Hi\", 10, 10, 200, \"center\")"),
        ApiInfo("love.graphics.getWidth", "getWidth()", "Returns the window width in pixels.", SUPPORTED, "local w = love.graphics.getWidth()"),
        ApiInfo("love.graphics.getHeight", "getHeight()", "Returns the window height in pixels.", SUPPORTED, "local h = love.graphics.getHeight()"),
        ApiInfo("love.graphics.setLineWidth", "setLineWidth(width)", "Sets the line width in pixels.", SUPPORTED, "love.graphics.setLineWidth(3)"),
        ApiInfo("love.graphics.setLineStyle", "setLineStyle(style)", "Sets the line style: \"smooth\" or \"rough\".", SUPPORTED, "love.graphics.setLineStyle(\"smooth\")"),
        ApiInfo("love.graphics.setPointSize", "setPointSize(size)", "Sets the point size in pixels.", SUPPORTED, "love.graphics.setPointSize(5)"),
        ApiInfo("love.graphics.push", "push()", "Saves the current coordinate transform (Canvas save).", SUPPORTED, "love.graphics.push()"),
        ApiInfo("love.graphics.pop", "pop()", "Restores the last saved transform.", SUPPORTED, "love.graphics.pop()"),
        ApiInfo("love.graphics.translate", "translate(dx, dy)", "Translates the coordinate system.", SUPPORTED, "love.graphics.translate(100, 50)"),
        ApiInfo("love.graphics.rotate", "rotate(angle)", "Rotates the coordinate system (radians).", SUPPORTED, "love.graphics.rotate(3.14159 / 4)"),
        ApiInfo("love.graphics.scale", "scale(sx, sy)", "Scales the coordinate system.", SUPPORTED, "love.graphics.scale(2, 2)"),
        ApiInfo("love.graphics.origin", "origin()", "Resets the coordinate transform.", SUPPORTED, "love.graphics.origin()"),
        ApiInfo("love.graphics.newImage", "newImage(path)", "Loads an image file relative to the game folder. Returns an Image handle.", PARTIAL, "local img = love.graphics.newImage(\"player.png\")"),
        ApiInfo("love.graphics.draw", "draw(image, x, y, r, sx, sy)", "Draws an Image handle (optionally rotated/scaled).", PARTIAL, "love.graphics.draw(img, 100, 100, 0, 2, 2)"),
        ApiInfo("love.graphics.newCanvas", "newCanvas(w, h)", "Off-screen render target.", UNSUPPORTED),
        ApiInfo("love.graphics.setCanvas", "setCanvas(canvas)", "Renders to a Canvas.", UNSUPPORTED),
        ApiInfo("love.graphics.newFont", "newFont(path, size)", "Loads a TTF font.", UNSUPPORTED),
        ApiInfo("love.graphics.setFont", "setFont(font)", "Sets the draw font.", UNSUPPORTED),
        ApiInfo("love.graphics.newMesh", "newMesh(vertices)", "Draws a vertex mesh.", UNSUPPORTED),
        ApiInfo("love.graphics.newShader", "newShader(code)", "Compiles a GLSL shader.", UNSUPPORTED),
        ApiInfo("love.graphics.setShader", "setShader(shader)", "Activates a shader.", UNSUPPORTED),
        ApiInfo("love.graphics.setBlendMode", "setBlendMode(mode)", "Sets blend mode (alpha/multiply/add).", UNSUPPORTED),
        ApiInfo("love.graphics.setScissor", "setScissor(x, y, w, h)", "Clips drawing to a rectangle.", UNSUPPORTED)
    )

    // ------------------------------------------------------------------
    // love.audio — SoundPool-backed
    // ------------------------------------------------------------------
    private val audioApis = listOf(
        ApiInfo("love.audio.newSource", "newSource(path, type)", "Loads a sound file (wav/ogg/mp3) relative to the game folder. type: \"static\" or \"stream\".", PARTIAL, "local s = love.audio.newSource(\"jump.wav\", \"static\")"),
        ApiInfo("love.audio.play", "play(source)", "Plays a sound source.", SUPPORTED, "love.audio.play(s)"),
        ApiInfo("love.audio.stop", "stop(source)", "Stops a sound source (or all if omitted).", SUPPORTED, "love.audio.stop(s)"),
        ApiInfo("love.audio.pause", "pause(source)", "Pauses a sound source.", SUPPORTED, "love.audio.pause(s)"),
        ApiInfo("love.audio.resume", "resume(source)", "Resumes a paused source.", SUPPORTED, "love.audio.resume(s)"),
        ApiInfo("love.audio.setVolume", "setVolume(volume)", "Sets master volume (0.0-1.0).", SUPPORTED, "love.audio.setVolume(0.5)"),
        ApiInfo("love.audio.getVolume", "getVolume()", "Returns the master volume.", SUPPORTED, "local v = love.audio.getVolume()")
    )

    // ------------------------------------------------------------------
    // love.filesystem — sandboxed to the game's save directory
    // ------------------------------------------------------------------
    private val fsApis = listOf(
        ApiInfo("love.filesystem.read", "read(path)", "Reads a file from the game's save directory. Returns content or nil, err.", SUPPORTED, "local data = love.filesystem.read(\"save.json\")"),
        ApiInfo("love.filesystem.write", "write(path, data)", "Writes a file to the game's save directory.", SUPPORTED, "love.filesystem.write(\"save.json\", data)"),
        ApiInfo("love.filesystem.append", "append(path, data)", "Appends data to a file in the save directory.", SUPPORTED, "love.filesystem.append(\"log.txt\", line)"),
        ApiInfo("love.filesystem.getInfo", "getInfo(path)", "Returns size, type info for a file (or nil).", SUPPORTED, "local info = love.filesystem.getInfo(\"save.json\")"),
        ApiInfo("love.filesystem.getDirectoryItems", "getDirectoryItems(dir)", "Returns a table of file names in a save-directory folder.", SUPPORTED, "local files = love.filesystem.getDirectoryItems(\"\")"),
        ApiInfo("love.filesystem.remove", "remove(path)", "Deletes a file in the save directory.", SUPPORTED, "love.filesystem.remove(\"old.txt\")"),
        ApiInfo("love.filesystem.createDirectory", "createDirectory(dir)", "Creates a folder inside the save directory.", SUPPORTED, "love.filesystem.createDirectory(\"levels\")")
    )

    // ------------------------------------------------------------------
    // love.math / love.timer / love.window / love.system
    // ------------------------------------------------------------------
    private val mathApis = listOf(
        ApiInfo("love.math.random", "random(min, max)", "Returns a random number. With no args: [0, 1).", SUPPORTED, "love.math.random(1, 10)"),
        ApiInfo("love.math.randomNormal", "randomNormal(stddev, mean)", "Gaussian random (Box-Muller).", SUPPORTED, "love.math.randomNormal(5, 100)"),
        ApiInfo("love.math.noise", "noise(x, y, z)", "1D/2D/3D smooth value noise in [0, 1).", PARTIAL, "love.math.noise(0.5, 0.2)"),
        ApiInfo("love.math.newRandomGenerator", "newRandomGenerator(seed)", "Random generator state object.", PARTIAL, "local rng = love.math.newRandomGenerator(42)"),
        ApiInfo("love.math.newTransform", "newTransform()", "Transform object.", UNSUPPORTED),
        ApiInfo("love.timer.getTime", "getTime()", "Seconds since the game started.", SUPPORTED, "love.timer.getTime()"),
        ApiInfo("love.timer.getDelta", "getDelta()", "Seconds since the last frame.", SUPPORTED, "love.timer.getDelta()"),
        ApiInfo("love.timer.getFPS", "getFPS()", "Current frames per second.", SUPPORTED, "love.timer.getFPS()"),
        ApiInfo("love.timer.step", "step()", "Advances the timer (called by the runtime).", SUPPORTED, "love.timer.step()"),
        ApiInfo("love.window.getWidth", "getWidth()", "Window width in pixels.", SUPPORTED, "love.window.getWidth()"),
        ApiInfo("love.window.getHeight", "getHeight()", "Window height in pixels.", SUPPORTED, "love.window.getHeight()"),
        ApiInfo("love.window.setTitle", "setTitle(title)", "Sets the window title.", SUPPORTED, "love.window.setTitle(\"My Game\")"),
        ApiInfo("love.window.setMode", "setMode(w, h)", "Changes the window size.", UNSUPPORTED),
        ApiInfo("love.system.getOS", "getOS()", "Returns \"Android\".", SUPPORTED, "love.system.getOS()")
    )

    // ------------------------------------------------------------------
    // engine.* — custom API (2D + software 3D)
    // ------------------------------------------------------------------
    private val engineApis = listOf(
        ApiInfo("engine.clear", "clear(r, g, b)", "Clears the screen (same as love.graphics.clear).", SUPPORTED, "engine.clear(0, 0, 0)"),
        ApiInfo("engine.drawRect", "drawRect(x, y, w, h, r, g, b)", "Draws a filled rectangle.", SUPPORTED, "engine.drawRect(10, 10, 50, 50, 1, 0, 0)"),
        ApiInfo("engine.drawCircle", "drawCircle(x, y, radius, r, g, b)", "Draws a filled circle.", SUPPORTED, "engine.drawCircle(100, 100, 20, 0, 1, 0)"),
        ApiInfo("engine.drawText", "drawText(text, x, y)", "Draws text.", SUPPORTED, "engine.drawText(\"Score: 3\", 20, 40)"),
        ApiInfo("engine.getDeltaTime", "getDeltaTime()", "Seconds since the last frame.", SUPPORTED, "engine.getDeltaTime()"),
        ApiInfo("engine.getTime", "getTime()", "Seconds since the game started.", SUPPORTED, "engine.getTime()"),
        ApiInfo("engine.isKeyDown", "isKeyDown(key)", "Checks a hardware key (use key names like \"back\", \"volume_up\").", PARTIAL, "if engine.isKeyDown(\"back\") then"),
        ApiInfo("engine.isTouchDown", "isTouchDown()", "True while a finger is on the screen.", SUPPORTED, "if engine.isTouchDown() then"),
        ApiInfo("engine.getTouchX", "getTouchX()", "Current touch X (or nil).", SUPPORTED, "local x = engine.getTouchX()"),
        ApiInfo("engine.getTouchY", "getTouchY()", "Current touch Y (or nil).", SUPPORTED, "local y = engine.getTouchY()"),
        ApiInfo("engine.begin3D", "begin3D()", "Starts 3D mode — switches to the software 3D pipeline.", SUPPORTED, "engine.begin3D()"),
        ApiInfo("engine.camera", "camera(x, y, z, yaw, pitch)", "Positions the 3D camera.", SUPPORTED, "engine.camera(0, 2, 8, 0, -0.2)"),
        ApiInfo("engine.drawCube", "drawCube(x, y, z, size, r, g, b)", "Draws a 3D cube (perspective-projected, depth-sorted).", SUPPORTED, "engine.drawCube(0, 0, 0, 1, 0.9, 0.3, 0.3)"),
        ApiInfo("engine.drawPlane", "drawPlane(x, y, z, w, d, r, g, b)", "Draws a horizontal 3D plane (ground tiles).", SUPPORTED, "engine.drawPlane(0, -1, 0, 20, 20, 0.2, 0.6, 0.2)"),
        ApiInfo("engine.end3D", "end3D()", "Ends 3D mode — back to 2D rendering.", SUPPORTED, "engine.end3D()"),
        ApiInfo("engine.ecs", "ecs", "Entity-Component-System module (Lua table).", PARTIAL, "local ecs = engine.ecs")
    )

    val allApis: List<ApiInfo> = graphicsApis + audioApis + fsApis + mathApis + engineApis

    private val apiMap: Map<String, ApiInfo> = allApis.associateBy { it.name }

    /** Built-in Lua libraries (full Lua 5.2 via LuaJ). */
    val luaBuiltins = listOf(
        "print", "pairs", "ipairs", "type", "tostring", "tonumber", "pcall", "xpcall",
        "assert", "error", "require", "next", "select", "rawget", "rawset",
        "setmetatable", "getmetatable", "unpack", "collectgarbage", "dofile", "load"
    )

    val luaLibraries = listOf("math", "string", "table", "coroutine", "os", "io", "debug", "utf8")

    /** Sandbox-restricted Lua features (blocked with a clear error). */
    val restrictedLibraries = setOf("io", "os")

    fun find(name: String): ApiInfo? = apiMap[name]

    fun isImplemented(name: String): Boolean = apiMap[name]?.support == SUPPORTED ||
            apiMap[name]?.support == PARTIAL

    fun supportedCount(): Int = allApis.count { it.support == SUPPORTED }
    fun partialCount(): Int = allApis.count { it.support == PARTIAL }
    fun unsupportedCount(): Int = allApis.count { it.support == UNSUPPORTED }

    /** Returns the love.* module names that have at least one implemented API. */
    fun implementedModules(): List<String> =
        allApis.filter { it.support != UNSUPPORTED }
            .map { it.name.substringAfter("love.").substringBefore(".") }
            .distinct()
            .sorted()

    // ------------------------------------------------------------------
    // Static validation — runs BEFORE the game executes
    // ------------------------------------------------------------------

    data class GameIssue(
        val type: String,       // "Syntax Error" | "Unsupported API" | "Restricted API"
        val message: String,
        val line: Int,
        val column: Int,
        val codeContext: String = ""
    )

    /**
     * Statically validates a Lua game script:
     *  1. syntax (compiled via LuaJ)
     *  2. unsupported love and engine API calls
     *  3. restricted (sandbox) library usage
     * Returns all issues found (empty = safe to run).
     */
    fun staticValidate(source: String, compiledOk: Boolean, syntaxError: GameIssue?): List<GameIssue> {
        val issues = mutableListOf<GameIssue>()
        syntaxError?.let { issues.add(it) }

        val lines = source.lines()
        // love.graphics.someFunc( / engine.someFunc( call patterns
        val callPattern = Regex("\\b(love|engine)\\.([\\w.]+)\\s*\\(")
        val loveModulePattern = Regex("\\blove\\.([a-z]+)\\s*=")

        lines.forEachIndexed { idx, line ->
            val lineNo = idx + 1
            callPattern.findAll(line).forEach { m ->
                val root = m.groupValues[1]
                var path = m.groupValues[2]
                // love.graphics.rectangle → love.graphics.rectangle ; engine.drawRect → engine.drawRect
                val fullName = "$root.$path"
                val info = apiMap[fullName]
                if (info == null) {
                    // Trailing segment may be a module member (love.graphics.xxx)
                    issues.add(GameIssue(
                        type = "Unsupported API",
                        message = "$fullName\n\nThis API is not implemented by the current runtime.\nUse LuaApiRegistry docs for the supported list.",
                        line = lineNo,
                        column = m.range.first + 1,
                        codeContext = codeContextFor(lines, idx)
                    ))
                } else if (info.support == UNSUPPORTED) {
                    issues.add(GameIssue(
                        type = "Unsupported API",
                        message = "$fullName\n\nThis API is known but NOT implemented on Android by the current runtime.\n(${info.signature})",
                        line = lineNo,
                        column = m.range.first + 1,
                        codeContext = codeContextFor(lines, idx)
                    ))
                }
                path = "" // suppress unused warning
            }
            loveModulePattern.findAll(line).forEach { m ->
                val mod = m.groupValues[1]
                if (mod in restrictedLibraries) {
                    issues.add(GameIssue(
                        type = "Restricted API",
                        message = "love.$mod\n\nDirect library assignment to a sandbox-restricted module is blocked.",
                        line = lineNo,
                        column = m.range.first + 1
                    ))
                }
            }
        }
        return issues
    }

    private fun codeContextFor(lines: List<String>, idx: Int): String {
        val from = (idx - 2).coerceAtLeast(0)
        val to = (idx + 2).coerceAtMost(lines.size - 1)
        return (from..to).joinToString("\n") { i ->
            val marker = if (i == idx) ">>" else "  "
            "$marker ${i + 1}: ${lines[i]}"
        }
    }
}
