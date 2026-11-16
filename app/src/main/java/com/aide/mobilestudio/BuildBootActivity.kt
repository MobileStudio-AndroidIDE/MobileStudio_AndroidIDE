package com.aide.mobilestudio

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aide.mobilestudio.ui.ide.IdeDivider
import com.aide.mobilestudio.ui.ide.LocalIdeColors
import java.io.File
import com.aide.mobilestudio.terminal.TerminalPtySelfTest

/**
 * Launcher entry point.
 *
 * First launch shows ONLY a language selection (ko/en) and then goes straight
 * to the IDE — no build-tool downloads, no "check what tools are installed"
 * step. Every tool (JDK / Gradle / NDK / CMake / Ninja / LLVM) is provisioned
 * lazily by [BuildProvisioner] the first time a build actually needs it.
 */
class BuildBootActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "BuildBootActivity"
    }

    private var launchedMain = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // adb (BuildBootActivity is the exported launcher entry point):
        //   adb shell am start -n com.aide.mobilestudio/.BuildBootActivity --ez terminal_self_test true
        // Explicit diagnostic only — never runs on a normal launch.
        if (intent.getBooleanExtra("terminal_self_test", false)) {
            intent.removeExtra("terminal_self_test")
            // The self-test exercises the runtime-tools wrappers (mkdir/ls
            // applets) — install first, then run the tests.
            Thread {
                runCatching { installRuntimeTools() }
            }.start()
        }

        val prefs = getSharedPreferences("setup_wizard", MODE_PRIVATE)
        val wizardDone = prefs.getBoolean("completed", false)

        if (wizardDone) {
            // Existing user (old wizard already completed, or returning after
            // the language step): land in the IDE directly. Only cheap local
            // maintenance runs here — never a download.
            runLocalMaintenance()
            launchMain()
            return
        }

        setContent {
            ThemeManager.ProvideTheme {
                LanguageSetupScreen(
                    initial = initialLanguage(),
                    onLanguageChange = { AppLanguage.set(this@BuildBootActivity, it) },
                    onStart = {
                        // Mark the wizard completed so it never reappears — the
                        // old multi-step wizard (dev type / SDK / JDK / Gradle)
                        // is retired; tools are provisioned at build time.
                        prefs.edit().putBoolean("completed", true).apply()
                        runLocalMaintenance()
                        MobileStudioCrashGuard.safe(this@BuildBootActivity, "BuildBootActivity.openIde") {
                            launchMain()
                        }
                    }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        MobileStudioCrashGuard.safe(this, "BuildBootActivity.onBackPressed") {
            if (launchedMain) {
                MobileStudioCrashGuard.finishSafely(this)
            } else {
                moveTaskToBack(true)
            }
        }
    }

    private fun initialLanguage(): String {
        val current = AppLanguage.current(this).code
        return if (current == "en") "en" else "ko"
    }

    /**
     * Cheap, LOCAL-only hygiene (no network): versioned native libs, legacy
     * Gradle-home migration, JDK exec permissions + linker wrappers.
     * All network provisioning moved to build time (BuildProvisioner).
     */
    private fun runLocalMaintenance() {
        Thread {
            runCatching { NativeLibSetup.ensureVersionedLibs(this@BuildBootActivity) }
                .onFailure { Log.w(TAG, "native lib setup failed: ${it.message}") }
            runCatching { BuildToolchain.migrateLegacyGradleUserHome(this@BuildBootActivity) }
                .onFailure { Log.w(TAG, "gradle home migration failed: ${it.message}") }
            runCatching { fixJdkPermissions() }
                .onFailure { Log.w(TAG, "jdk permission fix failed: ${it.message}") }
        }.start()
    }

    private fun fixJdkPermissions() {
        val toolchain = ToolchainManager(this)
        for (ver in toolchain.installedVersions(ToolchainManager.Kind.JDK)) {
            val dir = File(toolchain.kindDir(ToolchainManager.Kind.JDK), ver).takeIf { it.exists() } ?: continue
            val home = toolchain.resolveJdkHome(dir) ?: continue

            // Regenerate linker wrappers if format version changed (e.g. LD_PRELOAD fix)
            runCatching {
                JdkDownloader.regenerateLinkerWrappers(home, { msg -> Log.i(TAG, "jdk: $msg") })
            }

            val javaBin = File(home, "bin/java")
            if (javaBin.isFile && !javaBin.canExecute()) {
                javaBin.setExecutable(true, false)
                Log.i(TAG, "jdk: fixed permissions for $ver")
            }
            File(home, "bin").listFiles()?.forEach { f ->
                if (f.isFile && !f.canExecute()) f.setExecutable(true, false)
            }
        }
    }

    /** Only for the explicit terminal_self_test adb entry point. */
    private fun installRuntimeTools() {
        if (RuntimeToolsInstaller.areAllToolsInstalled(this)) {
            Log.i(TAG, "runtime-tools: ok")
            return
        }
        Log.i(TAG, "runtime-tools: installing busybox, curl, rg, git, tar, unzip...")
        RuntimeToolsInstaller.installAll(this) { msg -> Log.i(TAG, "runtime-tools: $msg") }
        Log.i(TAG, "runtime-tools: done")
    }

    private fun launchMain() {
        if (isFinishing || launchedMain) return
        launchedMain = true
        val next = Intent(this, MainActivity::class.java)
        intent.getStringExtra("project_dir")?.let { next.putExtra("project_dir", it) }
        startActivity(next)
        finish()
    }
}

/**
 * Language step, IDE-style: a flat selection list with a radio indicator and
 * one accent primary action. Centered with a max width so landscape reads as
 * a focused dialog column instead of a stretched form.
 */
@Composable
private fun LanguageSetupScreen(
    initial: String,
    onLanguageChange: (String) -> Unit,
    onStart: () -> Unit
) {
    var selected by remember { mutableStateOf(initial) }
    val ide = LocalIdeColors.current
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(ide.p.bg)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Spacer(Modifier.height(if (landscape) 8.dp else 40.dp))
            Text(
                text = "MobileStudio",
                color = ide.text,
                fontSize = if (landscape) 22.sp else 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (selected == "ko") "언어를 선택해 주세요." else "Choose your language.",
                color = ide.textSoft,
                fontSize = 13.sp,
            )

            Spacer(Modifier.height(28.dp))

            LanguageRow(
                title = "한국어",
                subtitle = "Korean language interface",
                selected = selected == "ko",
                onClick = {
                    selected = "ko"
                    onLanguageChange("ko")
                },
            )
            IdeDivider()
            LanguageRow(
                title = "English",
                subtitle = "영어 인터페이스",
                selected = selected == "en",
                onClick = {
                    selected = "en"
                    onLanguageChange("en")
                },
            )

            Spacer(Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(ide.accent, LocalShapesSmall())
                    .clickable(onClick = onStart),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (selected == "ko") "시작하기" else "Start",
                    color = ide.onAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(if (landscape) 8.dp else 40.dp))
        }
    }
}

@Composable
private fun LocalShapesSmall() = com.aide.mobilestudio.ui.ide.LocalShapes.current.control

@Composable
private fun LanguageRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val ide = LocalIdeColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) ide.selectedBg else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(Color(ide.p.bg), CircleShape)
                .border(1.dp, if (selected) ide.accent else ide.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(ide.accent, CircleShape)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = ide.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = ide.textSoft, fontSize = 11.sp)
        }
    }
}
