package com.aide.mobilestudio

import java.io.File

object ProjectExampleCatalog {
    data class Example(
        val folder: String,
        val fileName: String,
        val title: String,
        val content: String
    )

    val androidExamples: List<Example> = listOf(
        Example("android/01_hello_compose", "MainActivity.kt", "Compose hello", compose("Hello MobileStudio")),
        Example("android/02_counter", "CounterScreen.kt", "Counter", """
            package examples

            import androidx.compose.foundation.layout.*
            import androidx.compose.material3.*
            import androidx.compose.runtime.*
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp

            @Composable
            fun CounterScreen() {
                var count by remember { mutableStateOf(0) }
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Count: ${'$'}count")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { count++ }) { Text("Plus") }
                }
            }
        """.trimIndent()),
        Example("android/03_list", "ListScreen.kt", "Lazy list", composeList()),
        Example("android/04_network_state", "NetworkState.kt", "Network state placeholder", compose("Network status UI")),
        Example("android/05_camera_permission", "CameraPermission.kt", "Camera permission", composePermission("android.Manifest.permission.CAMERA")),
        Example("android/06_storage_picker", "StoragePicker.kt", "File picker", compose("Use ActivityResultContracts.OpenDocument() here")),
        Example("android/07_room_like_model", "Note.kt", "Note model", "package examples\n\ndata class Note(val id: Long, val title: String, val body: String)\n"),
        Example("android/08_service_status", "ServiceStatus.kt", "Service status UI", compose("Foreground service status")),
        Example("android/09_settings", "SettingsScreen.kt", "Settings screen", compose("Settings")),
        Example("android/10_theme", "ThemePreview.kt", "Theme preview", compose("Dark / Light preview")),
        Example("android/11_native_call", "NativeCall.kt", "NDK call placeholder", "package examples\n\nexternal fun stringFromNative(): String\n")
    )

    val pythonExamples: List<Example> = listOf(
        Example("python/01_hello", "hello.py", "Hello", "print('Hello from MobileStudio Python')\n"),
        Example("python/02_args", "args.py", "Arguments", "import sys\nprint('args:', sys.argv[1:])\n"),
        Example("python/03_json", "json_demo.py", "JSON", "import json\nprint(json.dumps({'app': 'MobileStudio'}, ensure_ascii=False))\n"),
        Example("python/04_files", "files.py", "Files", "from pathlib import Path\nPath('output.txt').write_text('hello', encoding='utf-8')\nprint(Path('output.txt').read_text())\n"),
        Example("python/05_requests_hint", "requests_hint.py", "HTTP hint", "print('Install requests with: python3 -m pip install requests')\n"),
        Example("python/06_kivy_counter", "main.py", "Kivy counter", kivyCounter()),
        Example("python/07_csv", "csv_demo.py", "CSV", "import csv\nrows=[['name','score'],['MobileStudio','100']]\nprint('\\n'.join(','.join(r) for r in rows))\n"),
        Example("python/08_regex", "regex_demo.py", "Regex", "import re\nprint(re.findall(r'\\w+', 'hello MobileStudio'))\n"),
        Example("python/09_datetime", "datetime_demo.py", "Datetime", "from datetime import datetime\nprint(datetime.now().isoformat())\n"),
        Example("python/10_opencv_hint", "opencv_hint.py", "OpenCV hint", "print('Install Android-compatible OpenCV wheel, then import cv2')\n"),
        Example("python/11_pip_freeze", "pip_freeze.py", "PIP", "print('Run: python3 -m pip freeze')\n")
    )

    fun install(root: File, python: Boolean): Int {
        val examples = if (python) pythonExamples else androidExamples
        val base = File(root, "MobileStudioExamples")
        examples.forEach { example ->
            val dir = File(base, example.folder).apply { mkdirs() }
            File(dir, example.fileName).writeText(example.content)
            File(dir, "README.md").writeText("# ${example.title}\n\nLoaded by MobileStudio.\n")
        }
        // Lua game examples (run directly via the in-process LÖVE runtime)
        luaGameExamples.forEach { example ->
            val dir = File(base, example.folder).apply { mkdirs() }
            File(dir, example.fileName).writeText(example.content)
            File(dir, "README.md").writeText(
                "# ${example.title}\n\n" +
                "Lua game — tap Run to execute directly (no APK build needed).\n" +
                "API: love.graphics / love.audio / love.filesystem / love.math / love.timer + engine.* (2D/3D)\n"
            )
        }
        return examples.size + luaGameExamples.size
    }

    /** Lua game test examples — validated against the in-process LÖVE runtime. */
    val luaGameExamples: List<Example> = listOf(
        Example("lua/01_cube3d", "main.lua", "3D cube + floor", luaCube3D()),
        Example("lua/02_pong", "main.lua", "2D Pong", luaPong()),
        Example("lua/03_particles", "main.lua", "Particle demo", luaParticles()),
        Example("lua/04_physics_bounce", "main.lua", "Physics bounce", luaPhysicsBounce()),
        Example("lua/05_touch_paint", "main.lua", "Touch paint", luaTouchPaint()),
        Example("lua/06_voxel_grid", "main.lua", "Voxel grid (3D)", luaVoxelGrid())
    )

    private fun compose(text: String): String = """
        package examples

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier

        @Composable
        fun ExampleScreen() {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("$text")
            }
        }
    """.trimIndent()

    private fun luaCube3D(): String = """
-- 3D cube + floor — in-process Lua game (LÖVE-compatible)
function love.load()
    angle = 0
end

function love.update(dt)
    angle = angle + dt * 0.8
end

function love.draw()
    clear(0.1, 0.15, 0.25)

    begin3D()
    camera(0, 2, 6, 0, 0)
    drawPlane(0, 0, 0, 20, 20, 0.2, 0.7, 0.2)
    drawCube(0, 1, 0, 1, 1, 0.2, 0.2)
    end3D()

    setColor(1, 1, 1)
    print("Drag to orbit not wired — cube spins automatically", 20, 40)
end
""".trimIndent() + "\n"

    private fun luaPong(): String = """
-- 2D Pong — in-process Lua game
function love.load()
    padW, padH = 120, 18
    padX = love.graphics.getWidth() / 2 - padW / 2
    padY = love.graphics.getHeight() - 60
    ballX = love.graphics.getWidth() / 2
    ballY = love.graphics.getHeight() / 2
    ballVX, ballVY = 300, 250
    ballR = 10
    score = 0
end

function love.update(dt)
    ballX = ballX + ballVX * dt
    ballY = ballY + ballVY * dt
    if ballX < ballR or ballX > love.graphics.getWidth() - ballR then ballVX = -ballVX end
    if ballY < ballR then ballVY = -ballVY end
    if ballY + ballR > padY and ballX > padX and ballX < padX + padW and ballVY > 0 then
        ballVY = -ballVY
        score = score + 1
    end
    if ballY > love.graphics.getHeight() + ballR then
        ballX, ballY = love.graphics.getWidth() / 2, love.graphics.getHeight() / 3
        score = 0
    end
end

function love.draw()
    love.graphics.clear(0.05, 0.07, 0.12)
    love.graphics.setColor(0.9, 0.3, 0.3)
    love.graphics.rectangle("fill", padX, padY, padW, padH)
    love.graphics.setColor(1, 1, 1)
    love.graphics.circle("fill", ballX, ballY, ballR)
    love.graphics.print("Score: " .. tostring(score), 20, 40)
end

function love.touchmoved(id, x, y)
    padX = x - padW / 2
end

function love.mousemoved(x, y)
    padX = x - padW / 2
end
""".trimIndent() + "\n"

    private fun luaParticles(): String = """
-- Particle demo — in-process Lua game
function love.load()
    parts = {}
    for i = 1, 80 do
        parts[i] = {
            x = love.math.random(0, love.graphics.getWidth()),
            y = love.math.random(0, love.graphics.getHeight()),
            vx = love.math.random(-60, 60),
            vy = love.math.random(-120, -20),
            r = love.math.random(2, 6),
            life = love.math.random(1, 3)
        }
    end
end

function love.update(dt)
    local w, h = love.graphics.getWidth(), love.graphics.getHeight()
    for i, p in ipairs(parts) do
        p.x = p.x + p.vx * dt
        p.y = p.y + p.vy * dt
        p.vy = p.vy + 60 * dt
        p.life = p.life - dt
        if p.life <= 0 or p.y > h then
            p.x = love.math.random(0, w)
            p.y = love.math.random(0, h / 2)
            p.vy = love.math.random(-120, -20)
            p.life = love.math.random(1, 3)
        end
    end
end

function love.draw()
    love.graphics.clear(0.06, 0.06, 0.1)
    for i, p in ipairs(parts) do
        love.graphics.setColor(p.life / 3, 0.6, 1)
        love.graphics.circle("fill", p.x, p.y, p.r)
    end
    love.graphics.setColor(1, 1, 1)
    love.graphics.print("Particles: " .. tostring(#parts), 20, 40)
end
""".trimIndent() + "\n"

    private fun luaPhysicsBounce(): String = """
-- Physics bounce (semi-implicit Euler) — in-process Lua game
function love.load()
    ball = { x = 200, y = 100, vx = 220, vy = 0, r = 18 }
    gravity = 900
    restitution = 0.82
end

function love.update(dt)
    ball.vy = ball.vy + gravity * dt
    ball.x = ball.x + ball.vx * dt
    ball.y = ball.y + ball.vy * dt
    if ball.y + ball.r > love.graphics.getHeight() then
        ball.y = love.graphics.getHeight() - ball.r
        ball.vy = -ball.vy * restitution
    end
    if ball.x - ball.r < 0 or ball.x + ball.r > love.graphics.getWidth() then
        ball.vx = -ball.vx
    end
end

function love.draw()
    love.graphics.clear(0.1, 0.1, 0.14)
    love.graphics.setColor(1, 0.75, 0.3)
    love.graphics.circle("fill", ball.x, ball.y, ball.r)
    love.graphics.setColor(1, 1, 1)
    love.graphics.print("velocity: " .. string.format("%.0f", ball.vy), 20, 40)
end

function love.touchpressed(id, x, y)
    ball.x, ball.y = x, y
    ball.vx, ball.vy = love.math.random(-200, 200), 0
end
""".trimIndent() + "\n"

    private fun luaTouchPaint(): String = """
-- Touch paint — in-process Lua game
function love.load()
    strokes = {}
    current = nil
end

function love.update(dt)
end

function love.draw()
    love.graphics.clear(0.95, 0.95, 0.98)
    for i, s in ipairs(strokes) do
        love.graphics.setColor(s.r, s.g, s.b)
        love.graphics.line(unpack(s.pts))
    end
    if current then
        love.graphics.setColor(0.2, 0.4, 1)
        love.graphics.line(unpack(current.pts))
    end
    love.graphics.setColor(0.3, 0.3, 0.35)
    love.graphics.print("Draw with your finger", 20, 30)
end

function love.touchpressed(id, x, y)
    current = { pts = { x, y }, r = love.math.random(0, 1), g = love.math.random(0, 1), b = love.math.random(0, 1) }
end

function love.touchmoved(id, x, y)
    if current then table.insert(current.pts, x) table.insert(current.pts, y) end
end

function love.touchreleased(id, x, y)
    if current then
        table.insert(strokes, current)
        current = nil
    end
end
""".trimIndent() + "\n"

    private fun luaVoxelGrid(): String = """
-- Voxel grid (Minecraft-style, software 3D) — in-process Lua game
function love.load()
    yaw = 0.6
    camY = 4
    dist = 10
end

function love.update(dt)
    yaw = yaw + dt * 0.15
end

function love.draw()
    clear(0.35, 0.6, 0.9)

    begin3D()
    camera(math.sin(yaw) * dist, camY, math.cos(yaw) * dist, 0, -0.25)
    drawPlane(0, 0, 0, 12, 12, 0.25, 0.6, 0.25)
    for x = -2, 2 do
        for z = -2, 2 do
            local h = 1 + math.floor(love.math.noise(x * 0.3, z * 0.3) * 3)
            for y = 0, h - 1 do
                drawCube(x, y, z, 0.95, 0.55, 0.38, 0.28)
            end
        end
    end
    end3D()

    setColor(1, 1, 1)
    print("Rotating voxel terrain — love.math.noise", 20, 40)
end
""".trimIndent() + "\n"

    private fun composeList(): String = """
        package examples

        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.foundation.lazy.items
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.Modifier

        @Composable
        fun ExampleList() {
            LazyColumn(Modifier.fillMaxSize()) {
                items((1..50).map { "Item ${'$'}it" }) { Text(it) }
            }
        }
    """.trimIndent()

    private fun composePermission(permission: String): String = """
        package examples

        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable

        @Composable
        fun PermissionHint() {
            Text("Request permission: $permission")
        }
    """.trimIndent()

    private fun kivyCounter(): String = """
        from kivy.app import App
        from kivy.uix.button import Button

        class CounterApp(App):
            count = 0
            def build(self):
                button = Button(text="Tap me")
                button.bind(on_release=self.tap)
                self.button = button
                return button
            def tap(self, *_):
                self.count += 1
                self.button.text = f"Count: {self.count}"

        CounterApp().run()
    """.trimIndent()
}
