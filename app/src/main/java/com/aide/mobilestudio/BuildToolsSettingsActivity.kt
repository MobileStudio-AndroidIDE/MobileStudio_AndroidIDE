package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class BuildToolsSettingsActivity : AppCompatActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, BuildToolsSettingsActivity::class.java))
        }
    }

    private lateinit var container: LinearLayout
    private val pendingRunnables = mutableListOf<() -> Unit>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        render()
    }

    override fun onPause() {
        super.onPause()
        pendingRunnables.forEach { it() }
        pendingRunnables.clear()
    }

    private fun buildLayout(): View {
        return ScrollView(this).apply {
            setBackgroundColor(StudioUi.color(StudioUi.InputBg))
            container = LinearLayout(this@BuildToolsSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(20), dp(20), dp(20))
            }
            addView(container)
        }
    }

    private fun render() {
        container.removeAllViews()

        header("Build Options")
        subtitle("Customize all build, compiler, signing, and analysis settings.")

        // ── Gradle ─────────────────────────────────────────────────────
        section("GRADLE")
        numberRow("Gradle Heap (MB)", BuildToolsConfig.gradleHeapMb(this), 512..4096) {
            BuildToolsConfig.setGradleHeapMb(this, it)
        }
        numberRow("Metaspace Size (MB)", BuildToolsConfig.gradleMetaspaceMb(this), 128..1024) {
            BuildToolsConfig.setGradleMetaspaceMb(this, it)
        }
        numberRow("Max Workers", BuildToolsConfig.maxWorkers(this), 1..8) {
            BuildToolsConfig.setMaxWorkers(this, it)
        }
        toggleRow("Parallel builds", BuildToolsConfig.parallel(this)) {
            BuildToolsConfig.setParallel(this, it)
        }
        toggleRow("Build cache", BuildToolsConfig.buildCache(this)) {
            BuildToolsConfig.setBuildCache(this, it)
        }
        toggleRow("Gradle daemon", BuildToolsConfig.daemon(this)) {
            BuildToolsConfig.setDaemon(this, it)
        }
        clickableInfoRow("Gradle daemon status", gradleDaemonStatusLine()) {
            AlertDialog.Builder(this)
                .setTitle("Gradle daemon")
                .setMessage("Daemon: ${BuildToolsConfig.daemon(this)}\nRunning process PID: $gradleDaemonPidCache")
                .setPositiveButton("OK", null)
                .show()
        }
        button("Stop Gradle Daemon") {
            stopGradleDaemonProcesses()
            Toast.makeText(this, "Gradle daemon stop requested", Toast.LENGTH_SHORT).show()
        }
        toggleRow("VFS watch", BuildToolsConfig.vfsWatch(this)) {
            BuildToolsConfig.setVfsWatch(this, it)
        }
        dropdownRow("Console mode", listOf("plain", "auto", "rich"), BuildToolsConfig.console(this)) {
            BuildToolsConfig.setConsole(this, it)
        }
        dropdownRow("Garbage Collector", listOf("UseSerialGC", "UseG1GC", "UseZGC", "UseShenandoahGC"), BuildToolsConfig.gc(this)) {
            BuildToolsConfig.setGc(this, it)
        }
        toggleRow("Heap dump on OOM", BuildToolsConfig.heapDump(this)) {
            BuildToolsConfig.setHeapDump(this, it)
        }
        textRow("Custom Gradle JVM args", BuildToolsConfig.customGradleJvmArgs(this),
            "e.g. -Xss512k -XX:ReservedCodeCacheSize=256m") {
            BuildToolsConfig.setCustomGradleJvmArgs(this, it)
        }
        button("Reset Gradle to Defaults") { BuildToolsConfig.resetGradleDefaults(this); render() }

        gap()

        // ── Kotlin Daemon ──────────────────────────────────────────────
        section("KOTLIN DAEMON")
        numberRow("Kotlin Daemon Heap (MB)", BuildToolsConfig.kotlinHeapMb(this), 256..4096) {
            BuildToolsConfig.setKotlinHeapMb(this, it)
        }
        numberRow("Kotlin Metaspace (MB)", BuildToolsConfig.kotlinMetaspaceMb(this), 64..1024) {
            BuildToolsConfig.setKotlinMetaspaceMb(this, it)
        }
        toggleRow("Kotlin daemon", BuildToolsConfig.kotlinDaemonEnabled(this)) {
            BuildToolsConfig.setKotlinDaemonEnabled(this, it)
        }
        textRow("Custom Kotlin daemon jvmargs", BuildToolsConfig.kotlinDaemonJvmArgs(this),
            "e.g. -Xmx1024m (blank = memory policy defaults)") {
            BuildToolsConfig.setKotlinDaemonJvmArgs(this, it)
        }
        toggleRow("Kotlin incremental", BuildToolsConfig.kotlinIncremental(this)) {
            BuildToolsConfig.setKotlinIncremental(this, it)
        }

        gap()

        // ── Java/Kotlin Compiler ───────────────────────────────────────
        section("JAVA / KOTLIN COMPILER")
        dropdownRow("Java sourceCompatibility", listOf("8", "9", "10", "11", "12", "13", "14", "15", "16", "17"), BuildToolsConfig.javaSourceCompat(this)) {
            BuildToolsConfig.setJavaSourceCompat(this, it)
        }
        dropdownRow("Java targetCompatibility", listOf("8", "9", "10", "11", "12", "13", "14", "15", "16", "17"), BuildToolsConfig.javaTargetCompat(this)) {
            BuildToolsConfig.setJavaTargetCompat(this, it)
        }
        dropdownRow("Kotlin jvmTarget", listOf("1.8", "9", "10", "11", "12", "13", "14", "15", "16", "17"), BuildToolsConfig.kotlinJvmTarget(this)) {
            BuildToolsConfig.setKotlinJvmTarget(this, it)
        }
        dropdownRow("Kotlin apiVersion", listOf("1.3", "1.4", "1.5", "1.6", "1.7", "1.8", "1.9", "2.0"), BuildToolsConfig.kotlinApiVersion(this)) {
            BuildToolsConfig.setKotlinApiVersion(this, it)
        }
        dropdownRow("Kotlin languageVersion", listOf("1.3", "1.4", "1.5", "1.6", "1.7", "1.8", "1.9", "2.0"), BuildToolsConfig.kotlinLanguageVersion(this)) {
            BuildToolsConfig.setKotlinLanguageVersion(this, it)
        }
        numberRow("Kotlin compiler heap (MB)", BuildToolsConfig.kotlinCompilerHeapMb(this), 128..2048) {
            BuildToolsConfig.setKotlinCompilerHeapMb(this, it)
        }
        button("Reset Compiler to Defaults") { BuildToolsConfig.resetCompilerDefaults(this); render() }

        gap()

        // ── Clang / NDK ───────────────────────────────────────────────
        section("CLANG / NDK")
        dropdownRow("Optimization level", listOf("-O0", "-O1", "-O2", "-O3", "-Os", "-Oz"), BuildToolsConfig.optimization(this)) {
            BuildToolsConfig.setOptimization(this, it)
        }
        numberRow("Target API level", BuildToolsConfig.targetApi(this), 21..35) {
            BuildToolsConfig.setTargetApi(this, it)
        }
        toggleRow("Debug symbols (-g)", BuildToolsConfig.debugSymbols(this)) {
            BuildToolsConfig.setDebugSymbols(this, it)
        }
        toggleRow("-fPIC", BuildToolsConfig.fpic(this)) {
            BuildToolsConfig.setFpic(this, it)
        }
        toggleRow("-fPIE", BuildToolsConfig.fpie(this)) {
            BuildToolsConfig.setFpie(this, it)
        }
        toggleRow("-Wall", BuildToolsConfig.wall(this)) {
            BuildToolsConfig.setWall(this, it)
        }
        toggleRow("-Werror", BuildToolsConfig.werror(this)) {
            BuildToolsConfig.setWerror(this, it)
        }
        toggleRow("Strip symbols", BuildToolsConfig.strip(this)) {
            BuildToolsConfig.setStrip(this, it)
        }
        dropdownRow("C++ standard", listOf("", "c++14", "c++17", "c++20", "c++23"), BuildToolsConfig.cppStandard(this)) {
            BuildToolsConfig.setCppStandard(this, it)
        }
        dropdownRow("CMake build type", listOf("Debug", "Release", "RelWithDebInfo", "MinSizeRel"), BuildToolsConfig.cmakeBuildType(this)) {
            BuildToolsConfig.setCmakeBuildType(this, it)
        }
        textRow("CMake generator", BuildToolsConfig.cmakeGenerator(this),
            "e.g. Ninja, Unix Makefiles (blank = default)") {
            BuildToolsConfig.setCmakeGenerator(this, it)
        }
        dropdownRow("NDK STL", listOf("c++_shared", "c++_static", "none"), BuildToolsConfig.ndkStl(this)) {
            BuildToolsConfig.setNdkStl(this, it)
        }
        textRow("ABI filters", BuildToolsConfig.ndkAbiFilters(this),
            "e.g. arm64-v8a, armeabi-v7a, x86_64") {
            BuildToolsConfig.setNdkAbiFilters(this, it)
        }
        textRow("Custom CFLAGS", BuildToolsConfig.customCflags(this),
            "e.g. -DANDROID -D__ANDROID_API__=29") {
            BuildToolsConfig.setCustomCflags(this, it)
        }
        textRow("Custom LDFLAGS", BuildToolsConfig.customLdflags(this),
            "e.g. -lm -lz") {
            BuildToolsConfig.setCustomLdflags(this, it)
        }
        textRow("Linker", BuildToolsConfig.linker(this),
            "e.g. lld, ld.lld (blank = default)") {
            BuildToolsConfig.setLinker(this, it)
        }
        button("Reset Clang to Defaults") { BuildToolsConfig.resetClangDefaults(this); render() }

        gap()

        // ── APK Signing ───────────────────────────────────────────────
        section("APK SIGNING")
        textRow("Key alias", BuildToolsConfig.signingKeyAlias(this), "androiddebugkey") {
            BuildToolsConfig.setSigningKeyAlias(this, it)
        }
        textRow("Store password", BuildToolsConfig.signingStorePassword(this), "android") {
            BuildToolsConfig.setSigningStorePassword(this, it)
        }
        textRow("Key password", BuildToolsConfig.signingKeyPassword(this), "android") {
            BuildToolsConfig.setSigningKeyPassword(this, it)
        }
        dropdownRow("Key algorithm", listOf("RSA", "DSA", "EC"), BuildToolsConfig.signingAlgorithm(this)) {
            BuildToolsConfig.setSigningAlgorithm(this, it)
        }
        dropdownRow("Key size", listOf("1024", "2048", "3072", "4096"), BuildToolsConfig.signingKeySize(this).toString()) {
            BuildToolsConfig.setSigningKeySize(this, it.toIntOrNull() ?: 2048)
        }
        numberRow("Validity (days)", BuildToolsConfig.signingValidityDays(this), 365..36500) {
            BuildToolsConfig.setSigningValidityDays(this, it)
        }
        textRow("Distinguished Name", BuildToolsConfig.signingDn(this),
            "CN=..., O=..., C=...") {
            BuildToolsConfig.setSigningDn(this, it)
        }
        button("Reset Signing to Defaults") { BuildToolsConfig.resetSigningDefaults(this); render() }

        gap()

        // ── Template Defaults ──────────────────────────────────────────
        section("TEMPLATE DEFAULTS")
        numberRow("Default minSdk", BuildToolsConfig.templateMinSdk(this), 21..35) {
            BuildToolsConfig.setTemplateMinSdk(this, it)
        }
        numberRow("Default targetSdk", BuildToolsConfig.templateTargetSdk(this), 28..35) {
            BuildToolsConfig.setTemplateTargetSdk(this, it)
        }
        numberRow("Default compileSdk", BuildToolsConfig.templateCompileSdk(this), 28..35) {
            BuildToolsConfig.setTemplateCompileSdk(this, it)
        }
        textRow("Kotlin version", BuildToolsConfig.templateKotlinVersion(this), "2.0.0") {
            BuildToolsConfig.setTemplateKotlinVersion(this, it)
        }
        textRow("AGP version", BuildToolsConfig.templateAgpVersion(this), "9.3.0") {
            BuildToolsConfig.setTemplateAgpVersion(this, it)
        }
        toggleRow("Release minifyEnabled", BuildToolsConfig.templateReleaseMinify(this)) {
            BuildToolsConfig.setTemplateReleaseMinify(this, it)
        }
        toggleRow("Release shrinkResources", BuildToolsConfig.templateReleaseShrink(this)) {
            BuildToolsConfig.setTemplateReleaseShrink(this, it)
        }
        button("Reset Template to Defaults") { BuildToolsConfig.resetTemplateDefaults(this); render() }

        gap()

        // ── JDK / JVM ─────────────────────────────────────────────────
        section("JDK / JVM")
        toggleRow("Add --add-opens flags", BuildToolsConfig.addOpens(this)) {
            BuildToolsConfig.setAddOpens(this, it)
        }
        textRow("Custom JVM args", BuildToolsConfig.customJvmArgs(this),
            "Android JDK: -XX:-UseCompressedOops -Xint (MTE safe)") {
            BuildToolsConfig.setCustomJvmArgs(this, it)
        }
        textRow("File encoding", BuildToolsConfig.fileEncoding(this), "UTF-8") {
            BuildToolsConfig.setFileEncoding(this, it)
        }
        textRow("user.country", BuildToolsConfig.userCountry(this), "US") {
            BuildToolsConfig.setUserCountry(this, it)
        }
        textRow("user.language", BuildToolsConfig.userLanguage(this), "en") {
            BuildToolsConfig.setUserLanguage(this, it)
        }
        textRow("Extra LD_LIBRARY_PATH", BuildToolsConfig.ldLibraryPathExtra(this),
            "Colon-separated paths to append") {
            BuildToolsConfig.setLdLibraryPathExtra(this, it)
        }
        button("Reset JDK to Defaults") { BuildToolsConfig.resetJdkDefaults(this); render() }

        gap()

        // ── Analysis Engine ────────────────────────────────────────────
        section("ANALYSIS ENGINE")
        numberRow("Daemon heap (MB)", BuildToolsConfig.analysisDaemonHeapMb(this), 16..512) {
            BuildToolsConfig.setAnalysisDaemonHeapMb(this, it)
        }
        numberRowRow("Compiler timeout (ms)", BuildToolsConfig.analysisCompilerTimeoutMs(this).toInt(), 1000..120000) {
            BuildToolsConfig.setAnalysisCompilerTimeoutMs(this, it.toLong())
        }
        numberRowRow("Debounce (ms)", BuildToolsConfig.analysisDebounceMs(this).toInt(), 100..5000) {
            BuildToolsConfig.setAnalysisDebounceMs(this, it.toLong())
        }
        numberRowRow("Cache TTL (ms)", BuildToolsConfig.analysisCacheTtlMs(this).toInt(), 5000..300000) {
            BuildToolsConfig.setAnalysisCacheTtlMs(this, it.toLong())
        }
        numberRow("Cache max entries", BuildToolsConfig.analysisCacheMaxEntries(this), 10..2000) {
            BuildToolsConfig.setAnalysisCacheMaxEntries(this, it)
        }
        numberRowRow("Clang timeout (ms)", BuildToolsConfig.clangTimeoutMs(this).toInt(), 1000..60000) {
            BuildToolsConfig.setClangTimeoutMs(this, it.toLong())
        }
        numberRowRow("Clangd timeout (ms)", BuildToolsConfig.clangdTimeoutMs(this).toInt(), 1000..30000) {
            BuildToolsConfig.setClangdTimeoutMs(this, it.toLong())
        }
        button("Reset Analysis to Defaults") { BuildToolsConfig.resetAnalysisDefaults(this); render() }

        gap()

        // ── Shell / Build Limits ───────────────────────────────────────
        section("SHELL / BUILD LIMITS")
        numberRowRow("Shell timeout (ms)", BuildToolsConfig.shellDefaultTimeoutMs(this).toInt(), 60000..7200000) {
            BuildToolsConfig.setShellDefaultTimeoutMs(this, it.toLong())
        }
        numberRow("Max output lines", BuildToolsConfig.shellMaxOutputLines(this), 500..50000) {
            BuildToolsConfig.setShellMaxOutputLines(this, it)
        }
        numberRow("Max captured chars", BuildToolsConfig.buildMaxCapturedChars(this), 100000..10000000) {
            BuildToolsConfig.setBuildMaxCapturedChars(this, it)
        }
        numberRowRow("Failure cooldown (ms)", BuildToolsConfig.buildFailureCooldownMs(this).toInt(), 5000..300000) {
            BuildToolsConfig.setBuildFailureCooldownMs(this, it.toLong())
        }
        button("Reset Shell to Defaults") { BuildToolsConfig.resetShellDefaults(this); render() }

        gap()

        // ── Memory / Layout Preview ────────────────────────────────────
        section("MEMORY / LAYOUT PREVIEW")
        numberRowRow("Monitor interval (ms)", BuildToolsConfig.memoryMonitorIntervalMs(this).toInt(), 5000..120000) {
            BuildToolsConfig.setMemoryMonitorIntervalMs(this, it.toLong())
        }
        numberRowRow("Heap pressure threshold", (BuildToolsConfig.memoryHeapPressureThreshold(this) * 100).toInt(), 50..98) {
            BuildToolsConfig.setMemoryHeapPressureThreshold(this, it / 100f)
        }
        numberRow("Layout preview heap (MB)", BuildToolsConfig.layoutPreviewHeapMb(this), 128..2048) {
            BuildToolsConfig.setLayoutPreviewHeapMb(this, it)
        }
        numberRowRow("Layout preview timeout (ms)", BuildToolsConfig.layoutPreviewTimeoutMs(this).toInt(), 3000..60000) {
            BuildToolsConfig.setLayoutPreviewTimeoutMs(this, it.toLong())
        }
        button("Reset Memory to Defaults") { BuildToolsConfig.resetMemoryDefaults(this); render() }

        gap(24)
        buttonDanger("Reset ALL to Defaults") {
            AlertDialog.Builder(this)
                .setTitle("Reset all build options?")
                .setMessage("This will restore every option to its default value.")
                .setPositiveButton("Reset") { _, _ -> BuildToolsConfig.resetAll(this); render() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        gap(40)
    }

    // ── UI Helpers ─────────────────────────────────────────────────────

    private fun header(txt: String) {
        container.addView(TextView(this).apply {
            text = txt
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun subtitle(txt: String) {
        container.addView(TextView(this).apply {
            text = txt
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            textSize = 13f
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun section(txt: String) {
        container.addView(TextView(this).apply {
            text = txt
            setTextColor(StudioUi.color(StudioUi.ActiveBlue))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
            letterSpacing = 0.08f
        })
    }

    private fun gap(h: Int = 12) {
        container.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) })
    }

    private fun toggleRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(SwitchCompat(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, isChecked -> onToggle(isChecked) }
        })
        container.addView(row)
    }

    private fun numberRow(label: String, value: Int, range: IntRange, onSet: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val editText = EditText(this).apply {
            setText(value.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(StudioUi.color(StudioUi.Text))
            setBackgroundColor(StudioUi.color(StudioUi.ButtonNormal))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(dp(100), ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = text.toString().toIntOrNull() ?: value
                    onSet(v.coerceIn(range.first, range.last))
                }
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    val v = text.toString().toIntOrNull() ?: value
                    onSet(v.coerceIn(range.first, range.last))
                    clearFocus()
                }
                false
            }
        }
        pendingRunnables.add {
            val v = editText.text.toString().toIntOrNull() ?: value
            onSet(v.coerceIn(range.first, range.last))
        }
        row.addView(editText)
        container.addView(row)
    }

    private fun numberRowRow(label: String, value: Int, range: IntRange, onSet: (Int) -> Unit) {
        numberRow(label, value, range, onSet)
    }

    private fun dropdownRow(label: String, options: List<String>, current: String, onSelect: (String) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val displayText = TextView(this).apply {
            text = current.ifBlank { "(default)" }
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(StudioUi.color(StudioUi.ButtonNormal))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        displayText.setOnClickListener {
            val displayLabels = options.map { if (it.isBlank()) "(default)" else it }
            AlertDialog.Builder(this)
                .setTitle(label)
                .setItems(displayLabels.toTypedArray()) { _, which ->
                    val selected = options[which]
                    onSelect(selected)
                    displayText.text = selected.ifBlank { "(default)" }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        row.addView(displayText)
        container.addView(row)
    }

    private fun textRow(label: String, value: String, hint: String, onSet: (String) -> Unit) {
        val vertical = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        vertical.addView(TextView(this).apply {
            text = label
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
        })
        if (hint.isNotBlank()) {
            vertical.addView(TextView(this).apply {
                text = hint
                setTextColor(StudioUi.color(StudioUi.Muted))
                textSize = 11f
                setPadding(0, dp(2), 0, dp(4))
            })
        }
        val editText = EditText(this).apply {
            setText(value)
            setTextColor(StudioUi.color(StudioUi.Text))
            setBackgroundColor(StudioUi.color(StudioUi.ButtonNormal))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setHint(hint)
            setHintTextColor(StudioUi.color(StudioUi.Muted))
            setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) onSet(text.toString())
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    onSet(text.toString())
                    clearFocus()
                }
                false
            }
        }
        pendingRunnables.add { onSet(editText.text.toString()) }
        vertical.addView(editText)
        container.addView(vertical)
    }

    private fun clickableInfoRow(label: String, value: String, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(TextView(this).apply {
            text = label
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(TextView(this).apply {
            text = value
            setTextColor(StudioUi.color(StudioUi.Text))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(StudioUi.color(StudioUi.ButtonNormal))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        })
        container.addView(row)
    }

    private var gradleDaemonPidCache: String = "unknown"

    /** Daemon enabled/disabled + running PID ("unknown" when undetectable). */
    private fun gradleDaemonStatusLine(): String {
        val enabled = BuildToolsConfig.daemon(this) ||
            getSharedPreferences("ide", Context.MODE_PRIVATE).getBoolean("gradle_daemon", false)
        gradleDaemonPidCache = try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", "ps -A -o PID,ARGS 2>/dev/null || ps -A 2>/dev/null")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            out.lineSequence()
                .filter { it.contains("GradleDaemon") }
                .map { it.trim().split(Regex("\\s+")).firstOrNull() ?: "unknown" }
                .firstOrNull() ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        return "Daemon: ${if (enabled) "ENABLED" else "DISABLED"} | PID: $gradleDaemonPidCache"
    }

    /** Explicit Gradle Daemon stop (equivalent of `gradlew --stop`). */
    private fun stopGradleDaemonProcesses() {
        try {
            val pb = ProcessBuilder("pkill", "-f", "GradleDaemon")
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }

    private fun button(label: String, onClick: () -> Unit) {
        container.addView(Button(this).apply {
            text = label
            setTextColor(StudioUi.color(StudioUi.Text))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(StudioUi.color(StudioUi.ButtonNormal))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), StudioUi.color(StudioUi.DividerLight))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        })
    }

    private fun buttonDanger(label: String, onClick: () -> Unit) {
        container.addView(Button(this).apply {
            text = label
            setTextColor(StudioUi.color(StudioUi.ButtonDangerBorder))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(StudioUi.color(StudioUi.ButtonNormal))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), StudioUi.color(StudioUi.Red))
            }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        })
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
