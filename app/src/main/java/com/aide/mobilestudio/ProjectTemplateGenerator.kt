package com.aide.mobilestudio

import android.content.Context
import java.io.File

class ProjectTemplateGenerator(private val context: Context) {
    private val gradleJvmArgs = ""
    private val mobileWorkerCount: Int
        get() = if (context.getSharedPreferences("ide", Context.MODE_PRIVATE)
                .getBoolean("performance_mode", false)
        ) 2 else 1
    private val gradleDaemonPreference: Boolean
        get() {
            // Default: ENABLED. Explicit Build Tools / "ide" prefs can still disable.
            if (BuildToolsConfig.daemon(context)) return true
            val prefs = context.getSharedPreferences("ide", Context.MODE_PRIVATE)
            return prefs.getBoolean("gradle_daemon", false) ||
                prefs.getBoolean("gradleDaemon", false) ||
                prefs.getBoolean("gradledemon", false)
        }
    private val kotlinDaemonPreference: Boolean
        get() = BuildToolsConfig.kotlinDaemonEnabled(context)

    private fun agpVersionForGradle(gradleVersion: String): String {
        val version = gradleVersion.removePrefix("gradle-")
        val parts = version.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 14
        return when {
            major >= 9 -> "9.3.0"
            major == 8 && minor >= 14 -> "8.13.0"
            major == 8 && minor >= 2 -> "8.$minor.0"
            else -> "8.2.0"
        }
    }

    private fun kotlinVersionForGradle(gradleVersion: String): String {
        val agp = agpVersionForGradle(gradleVersion)
        return when {
            agp.startsWith("9.") -> "2.1.0"
            agp.startsWith("8.13") -> "2.0.20"
            else -> "2.0.0"
        }
    }

    enum class Template {
        EMPTY_ACTIVITY,
        EMPTY_COMPOSE_ACTIVITY,
        EMPTY_JAVA_ACTIVITY,
        BASIC_ACTIVITY,
        NAVIGATION_DRAWER,
        BOTTOM_NAVIGATION,
        SETTINGS_ACTIVITY,
        NATIVE_CPP,
        JAR_APP,
        MINECRAFT_FORGE,
        PYTHON_SCRIPT,
        WINDOWS_EXE,
        GAME_2D_PONG,
        GAME_2D_SNAKE,
        GAME_3D_SCENE,
        GAME_VOXEL_WORLD,
        GAME_LUA_2D,
        GAME_VULKAN,
        GAME_JS_2D,
        GAME_PYTHON_2D,
        GAME_JAVA_2D,
        GAME_GDSCRIPT,
        GAME_CSHARP_2D,
        GODOT_PROJECT,
    }

    enum class BuildDsl { KTS, GROOVY }

    data class ProjectConfig(
        val appName: String,
        val packageName: String = derivePackage(appName),
        val minSdk: Int = 24,
        val targetSdk: Int = 34,
        val compileSdk: Int = 34,
        val kotlinVersion: String = "2.1.0",
        val agpVersion: String = "9.3.0",
        val template: Template = Template.EMPTY_ACTIVITY,
        val useViewBinding: Boolean = true,
        val projectRoot: File? = null,
        val buildDsl: BuildDsl = BuildDsl.KTS,
        val ndkVersion: String = "",
        val javaSourceCompat: String = "17",
        val javaTargetCompat: String = "17",
        val kotlinJvmTarget: String = "17",
        val releaseMinify: Boolean = true,
        val releaseShrink: Boolean = false
    ) {
        companion object {
            fun derivePackage(appName: String): String {
                val safe = appName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase().ifEmpty { "myapp" }
                return "com.example.$safe"
            }
        }
    }

    // StorageHelper 기반 파일 액세스 (권한 불필요)
    val projectsRoot: File
        get() = StorageHelper.projectsRoot(context)

    fun exists(appName: String, rootDir: File = projectsRoot): Boolean = File(rootDir, appName).exists()

    fun create(cfg: ProjectConfig): File {
        val activeGradle = ToolchainManager(context).activeVersion(ToolchainManager.Kind.GRADLE) ?: "gradle-9.6.1"
        val mappedAgpVersion = if (cfg.agpVersion == "8.13.0" || cfg.agpVersion == "8.2.0" || cfg.agpVersion == "9.3.0") {
            // Auto-map AGP version based on Gradle version if using default
            agpVersionForGradle(activeGradle)
        } else {
            cfg.agpVersion
        }
        val mappedKotlinVersion = if (cfg.kotlinVersion == "2.1.0" || cfg.kotlinVersion == "2.0.0") {
            kotlinVersionForGradle(activeGradle)
        } else {
            cfg.kotlinVersion
        }
        val finalCfg = cfg.copy(agpVersion = mappedAgpVersion, kotlinVersion = mappedKotlinVersion)
        val root = File(finalCfg.projectRoot ?: projectsRoot, finalCfg.appName).apply { mkdirs() }
        if (cfg.template == Template.JAR_APP) {
            writeJarProject(root, cfg)
            return root
        }
        if (cfg.template == Template.MINECRAFT_FORGE) {
            writeForgeProject(root, cfg)
            return root
        }
        if (cfg.template == Template.PYTHON_SCRIPT) {
            writePythonScriptProject(root)
            return root
        }
        if (cfg.template == Template.WINDOWS_EXE) {
            writeWindowsExeProject(root)
            return root
        }
        if (cfg.template == Template.GAME_2D_PONG || cfg.template == Template.GAME_2D_SNAKE ||
            cfg.template == Template.GAME_3D_SCENE || cfg.template == Template.GAME_VOXEL_WORLD
        ) {
            writeGameProject(root, cfg)
            return root
        }
        if (cfg.template == Template.GAME_LUA_2D) {
            writeLuaScriptOnlyProject(root, cfg)
            return root
        }
        if (cfg.template == Template.GAME_VULKAN) {
            writeVulkanGameProject(root, cfg)
            return root
        }
        if (cfg.template == Template.GAME_JS_2D) {
            writeJsScriptOnlyProject(root, cfg)
            return root
        }
        if (cfg.template == Template.GAME_PYTHON_2D) {
            writePythonGameProject(root, cfg)
            return root
        }
        if (cfg.template == Template.GAME_JAVA_2D) {
            writeJavaGameProject(root, cfg)
            return root
        }
        if (cfg.template == Template.GAME_GDSCRIPT) {
            writeGdScriptGameProject(root, cfg)
            return root
        }
        if (cfg.template == Template.GAME_CSHARP_2D) {
            writeCSharpGameProject(root, cfg)
            return root
        }
        if (cfg.template == Template.GODOT_PROJECT) {
            writeGodotProject(root, cfg)
            return root
        }
        writeRootFiles(root, cfg)
        writeGradleWrapper(root)
        writeAppModule(root, cfg)
        writeSrcMain(root, cfg)
        if (cfg.template == Template.NATIVE_CPP) writeNativeCpp(root, cfg)
        return root
    }

    fun ensureWrapperFiles(projectRoot: File) {
        if (!projectRoot.exists() || !projectRoot.isDirectory) return
        writeGradleWrapper(projectRoot)
    }

    // gradlew: wrapper jar 불필요, 자체 gradle 직접 호출
    private fun isGradleDaemonEnabled(projectRoot: File, currentLines: List<String>? = null): Boolean {
        return readGradleDaemonFlag(currentLines) == true ||
            readGradleDaemonFlag(File(projectRoot, "gradle.properties")) == true ||
            gradleDaemonPreference
    }

    private fun readGradleDaemonFlag(file: File): Boolean? {
        if (!file.isFile) return null
        return readGradleDaemonFlag(file.readLines())
    }

    private fun readGradleDaemonFlag(lines: List<String>?): Boolean? {
        if (lines == null) return null
        val keys = setOf(
            "org.gradle.daemon",
            "gradleDaemon",
            "gradledemon",
            "mobileStudio.gradleDaemon",
            "mobilestudio.gradleDaemon"
        )
        var sawFalse = false
        val flagPattern = Regex("""^([A-Za-z0-9_.-]+)\s*(?:=|:|\s)\s*(true|false)\b""", RegexOption.IGNORE_CASE)
        lines.asReversed().forEach { line ->
            val trimmed = line.substringBefore('#').trim()
            val match = flagPattern.find(trimmed) ?: return@forEach
            val key = match.groupValues[1].trim()
            if (key !in keys) return@forEach
            if (match.groupValues[2].equals("true", ignoreCase = true)) return true
            sawFalse = true
        }
        return if (sawFalse) false else null
    }

    private fun writeGradleWrapper(root: File) {
        val wrapperDir = File(root, "gradle/wrapper").apply { mkdirs() }
        val activeGradle = ToolchainManager(context).activeVersion(ToolchainManager.Kind.GRADLE) ?: "gradle-9.6.1"
        val gradleVersion = activeGradle.removePrefix("gradle-")
        val wrapperCliJar = "gradle-cli-$gradleVersion.jar"
        val gradlewContent = """
#!/system/bin/sh
# ==============================================================================
#
#   MobileStudio Gradle Launcher
#   ------------------------------------------------------------------------
#   Robust POSIX-shell launcher for running the Gradle Wrapper inside the
#   MobileStudio (com.aide.mobilestudio) on-device Android IDE sandbox.
#
#   Repository : MobileStudio-AndroidIDE/MobileStudio_AndroidIDE
#   Contact    : GitHub Issues, or limsemin17@gmail.com
#
# ==============================================================================
#
#                                 Apache License
#                           Version 2.0, January 2004
#                        http://www.apache.org/licenses/
#
#   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION
#
#   1. Definitions.
#
#      "License" shall mean the terms and conditions for use, reproduction,
#      and distribution as defined by Sections 1 through 9 of this document.
#
#      "Licensor" shall mean the copyright owner or entity authorized by
#      the copyright owner that is granting the License.
#
#      "Legal Entity" shall mean the union of the acting entity and all
#      other entities that control, are controlled by, or are under common
#      control with that entity. For the purposes of this definition,
#      "control" means (i) the power, direct or indirect, to cause the
#      direction or management of such entity, whether by contract or
#      otherwise, or (ii) ownership of fifty percent (50%) or more of the
#      outstanding shares, or (iii) beneficial ownership of such entity.
#
#      "You" (or "Your") shall mean an individual or Legal Entity
#      exercising permissions granted by this License.
#
#      "Source" form shall mean the preferred form for making modifications,
#      including but not limited to software source code, documentation
#      source, and configuration files.
#
#      "Object" form shall mean any form resulting from mechanical
#      transformation or translation of a Source form, including but
#      not limited to compiled object code, generated documentation,
#      and conversions to other media types.
#
#      "Work" shall mean the work of authorship, whether in Source or
#      Object form, made available under the License, as indicated by a
#      copyright notice that is included in or attached to the work
#      (an example is provided in the Appendix below).
#
#      "Derivative Works" shall mean any work, whether in Source or Object
#      form, that is based on (or derived from) the Work and for which the
#      editorial revisions, annotations, elaborations, or other modifications
#      represent, as a whole, an original work of authorship. For the purposes
#      of this License, Derivative Works shall not include works that remain
#      separable from, or merely link (or bind by name) to the interfaces of,
#      the Work and Derivative Works thereof.
#
#      "Contribution" shall mean any work of authorship, including
#      the original version of the Work and any modifications or additions
#      to that Work or Derivative Works thereof, that is intentionally
#      submitted to Licensor for inclusion in the Work by the copyright owner
#      or by an individual or Legal Entity authorized to submit on behalf of
#      the copyright owner. For the purposes of this definition, "submitted"
#      means any form of electronic, verbal, or written communication sent
#      to the Licensor or its representatives, including but not limited to
#      communication on electronic mailing lists, source code control systems,
#      and issue tracking systems that are managed by, or on behalf of, the
#      Licensor for the purpose of discussing and improving the Work, but
#      excluding communication that is conspicuously marked or otherwise
#      designated in writing by the copyright owner as "Not a Contribution."
#
#      "Contributor" shall mean Licensor and any individual or Legal Entity
#      on behalf of whom a Contribution has been received by Licensor and
#      subsequently incorporated within the Work.
#
#   2. Grant of Copyright License. Subject to the terms and conditions of
#      this License, each Contributor hereby grants to You a perpetual,
#      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
#      copyright license to reproduce, prepare Derivative Works of,
#      publicly display, publicly perform, sublicense, and distribute the
#      Work and such Derivative Works in Source or Object form.
#
#   3. Grant of Patent License. Subject to the terms and conditions of
#      this License, each Contributor hereby grants to You a perpetual,
#      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
#      (except as stated in this section) patent license to make, have made,
#      use, offer to sell, sell, import, and otherwise transfer the Work,
#      where such license applies only to those patent claims licensable
#      by such Contributor that are necessarily infringed by their
#      Contribution(s) alone or by combination of their Contribution(s)
#      with the Work to which such Contribution(s) was submitted. If You
#      institute patent litigation against any entity (including a
#      cross-claim or counterclaim in a lawsuit) alleging that the Work
#      or a Contribution incorporated within the Work constitutes direct
#      or contributory patent infringement, then any patent licenses
#      granted to You under this License for that Work shall terminate
#      as of the date such litigation is filed.
#
#   4. Redistribution. You may reproduce and distribute copies of the
#      Work or Derivative Works thereof in any medium, with or without
#      modifications, and in Source or Object form, provided that You
#      meet the following conditions:
#
#      (a) You must give any other recipients of the Work or
#          Derivative Works a copy of this License; and
#
#      (b) You must cause any modified files to carry prominent notices
#          stating that You changed the files; and
#
#      (c) You must retain, in the Source form of any Derivative Works
#          that You distribute, all copyright, patent, trademark, and
#          attribution notices from the Source form of the Work,
#          excluding those notices that do not pertain to any part of
#          the Derivative Works; and
#
#      (d) If the Work includes a "NOTICE" text file as part of its
#          distribution, then any Derivative Works that You distribute must
#          include a readable copy of the attribution notices contained
#          within such NOTICE file, excluding those notices that do not
#          pertain to any part of the Derivative Works, in at least one
#          of the following places: within a NOTICE text file distributed
#          as part of the Derivative Works; within the Source form or
#          documentation, if provided along with the Derivative Works; or,
#          within a display generated by the Derivative Works, if and
#          wherever such third-party notices normally appear. The contents
#          of the NOTICE file are for informational purposes only and
#          do not modify the License. You may add Your own attribution
#          notices within Derivative Works that You distribute, alongside
#          or as an addendum to the NOTICE text from the Work, provided
#          that such additional attribution notices cannot be construed
#          as modifying the License.
#
#      You may add Your own copyright statement to Your modifications and
#      may provide additional or different license terms and conditions
#      for use, reproduction, or distribution of Your modifications, or
#      for any such Derivative Works as a whole, provided Your use,
#      reproduction, and distribution of the Work otherwise complies with
#      the conditions stated in this License.
#
#   5. Submission of Contributions. Unless You explicitly state otherwise,
#      any Contribution intentionally submitted for inclusion in the Work
#      by You to the Licensor shall be under the terms and conditions of
#      this License, without any additional terms or conditions.
#      Notwithstanding the above, nothing herein shall supersede or modify
#      the terms of any separate license agreement you may have executed
#      with Licensor regarding such Contributions.
#
#   6. Trademarks. This License does not grant permission to use the trade
#      names, trademarks, service marks, or product names of the Licensor,
#      except as required for reasonable and customary use in describing the
#      origin of the Work and reproducing the content of the NOTICE file.
#
#   7. Disclaimer of Warranty. Unless required by applicable law or
#      agreed to in writing, Licensor provides the Work (and each
#      Contributor provides its Contributions) on an "AS IS" BASIS,
#      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
#      implied, including, without limitation, any warranties or conditions
#      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
#      PARTICULAR PURPOSE. You are solely responsible for determining the
#      appropriateness of using or redistributing the Work and assume any
#      risks associated with Your exercise of permissions under this License.
#
#   8. Limitation of Liability. In no event and under no legal theory,
#      whether in tort (including negligence), contract, or otherwise,
#      unless required by applicable law (such as deliberate and grossly
#      negligent acts) or agreed to in writing, shall any Contributor be
#      liable to You for damages, including any direct, indirect, special,
#      incidental, or consequential damages of any character arising as a
#      result of this License or out of the use or inability to use the
#      Work (including but not limited to damages for loss of goodwill,
#      work stoppage, computer failure or malfunction, or any and all
#      other commercial damages or losses), even if such Contributor
#      has been advised of the possibility of such damages.
#
#   9. Accepting Warranty or Additional Liability. While redistributing
#      the Work or Derivative Works thereof, You may choose to offer,
#      and charge a fee for, acceptance of support, warranty, indemnity,
#      or other liability obligations and/or rights consistent with this
#      License. However, in accepting such obligations, You may act only
#      on Your own behalf and on Your sole responsibility, not on behalf
#      of any other Contributor, and only if You agree to indemnify,
#      defend, and hold each Contributor harmless for any liability
#      incurred by, or claims asserted against, such Contributor by reason
#      of your accepting any such warranty or additional liability.
#
#   END OF TERMS AND CONDITIONS
#
#   Copyright 2026 MobileStudio-AndroidIDE Project Contributors
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.
#
# ==============================================================================
#
#   ABOUT THIS SCRIPT
#   ------------------------------------------------------------------------
#   MobileStudio runs Gradle *on-device*, inside an Android application's
#   private, `noexec`-mounted `/data` partition. That environment is
#   fundamentally hostile to a normal JVM launch:
#
#     - `/data/data/<pkg>/...` is typically mounted `noexec`, so the shared
#       objects that make up a JDK (`libjvm.so`, `libjli.so`, ...) cannot be
#       `dlopen()`-ed from disk in the ordinary way. MobileStudio's native
#       launcher (`libgradle_exec.so` / "NativeExec") works around this via
#       `memfd_create()` + `fexecve()`, loading the JVM into an anonymous,
#       executable memory-backed file descriptor instead of executing the
#       on-disk path directly.
#     - The bundled JDK is a custom on-device build (see the JDK/Android
#       port notes referenced in this project), cross-compiled against a
#       specific Bionic libc version. If the JDK build's target API level
#       does not match the *running* device's Bionic, dynamic symbol
#       resolution can fail at JVM startup — historically surfacing as
#       `dlopen` failures resolving `_ZN6Thread12_thr_currentE`, or as
#       `no known VMs. (check for corrupt jvm.cfg file)` even when the
#       actual `jvm.cfg` is present and well-formed.
#     - SELinux policy on stock Android further restricts what a
#       third-party app is allowed to execute, map executable, or ptrace,
#       which shapes several of the workarounds below.
#     - The Kotlin compiler bundled with MobileStudio requires a DEX-level
#       patch to bypass Hidden API restrictions
#       (`ConcurrentLongObjectHashMap.<clinit>` / `sun.misc.Unsafe.getAndAddInt`)
#       before it can run under the on-device DEX/D8 pipeline.
#
#   None of the above is fixable from a shell script — those are toolchain
#   and native-launcher concerns. What *this* script can do is:
#
#     1. Find a working JDK across every location MobileStudio has ever
#        installed one to (current, legacy, and shim layouts), instead of
#        assuming a single fixed path.
#     2. Fail loudly, early, and with actionable diagnostics rather than
#        letting Gradle itself produce an opaque JVM crash log.
#     3. Provide a `--doctor` mode that walks through the known Android
#        on-device-JVM failure signatures (see above) and tells the user
#        which one they're most likely hitting, based on what's actually
#        on disk and in the environment.
#     4. Avoid stomping on concurrent Gradle daemon invocations (MobileStudio
#        can trigger a build from more than one UI surface) via a simple
#        advisory lock.
#     5. Keep every destructive or environment-mutating action optional,
#        logged, and reversible, since this runs inside a user's IDE data
#        directory on their personal device.
#
#   This file is intentionally verbose. On a resource-constrained mobile
#   device, a clear error message that survives being copy-pasted into a
#   GitHub issue or emailed to limsemin17@gmail.com is worth far more than
#   a terse script that fails silently.
#
# ==============================================================================

# ==============================================================================
#   SECTION 1: SCRIPT METADATA
# ==============================================================================

MS_SCRIPT_NAME="mobilestudio-gradlew"
MS_SCRIPT_VERSION="2.0.0"
MS_SCRIPT_DESCRIPTION="MobileStudio Gradle Launcher (Android arm64 / on-device JDK toolchain)"

# ==============================================================================
#   SECTION 2: PACKAGE / APPLICATION IDENTIFIERS
# ==============================================================================
#
# These are the MobileStudio (a.k.a. AIDE) Android package identifiers.
# Kept as variables (rather than inlined everywhere below) so that a fork,
# a debug build variant (e.g. `com.aide.mobilestudio.debug`), or a future
# rename can be supported by overriding a single value.
# ==============================================================================

MS_PACKAGE_ID="${'$'}{MOBILESTUDIO_PACKAGE_ID:-com.aide.mobilestudio}"
MS_PACKAGE_ID_ALT="${'$'}{MOBILESTUDIO_PACKAGE_ID_ALT:-com.example.mobilestudio}"

MS_DATA_ROOT_DATA="/data/data/${'$'}{MS_PACKAGE_ID}"
MS_DATA_ROOT_USER="/data/user/0/${'$'}{MS_PACKAGE_ID}"
MS_DATA_ROOT_DATA_ALT="/data/data/${'$'}{MS_PACKAGE_ID_ALT}"
MS_DATA_ROOT_USER_ALT="/data/user/0/${'$'}{MS_PACKAGE_ID_ALT}"

# ==============================================================================
#   SECTION 3: DEFAULT CONFIGURATION VALUES
# ==============================================================================
#
# Every one of these may be overridden by (in ascending priority):
#   1. This block (compiled-in default)
#   2. ${'$'}GRADLE_USER_HOME/mobilestudio.properties  (user-global override)
#   3. ${'$'}APP_HOME/mobilestudio.properties           (project-local override)
#   4. Environment variables                       (e.g. MOBILESTUDIO_VERBOSE=1)
#   5. Command-line flags                          (e.g. --verbose)
#
# Later sections implement that precedence chain explicitly rather than
# relying on shell "last write wins" ordering, so the precedence is provable
# by reading SECTION 8 (config loading) and SECTION 9 (flag parsing) rather
# than by re-deriving it from execution order.
# ==============================================================================

MS_DEFAULT_VERBOSE=0
MS_DEFAULT_QUIET=0
MS_DEFAULT_DEBUG=0
MS_DEFAULT_NO_COLOR=0
MS_DEFAULT_USE_DAEMON=1
MS_DEFAULT_DAEMON_LOCK_TIMEOUT_SEC=120
MS_DEFAULT_MAX_HEAP_MB=256
MS_DEFAULT_METASPACE_MB=96
MS_DEFAULT_TIERED_COMPILATION=0
MS_DEFAULT_LOG_MAX_BYTES=1048576
MS_DEFAULT_LOG_KEEP=5
MS_DEFAULT_RETRY_COUNT=1
MS_DEFAULT_RETRY_DELAY_SEC=2
MS_DEFAULT_DOCTOR_MODE=0
MS_DEFAULT_CLEAN_TMP_ON_START=0
MS_DEFAULT_PREFER_LEGACY_JDK=0
MS_DEFAULT_STRICT_JDK_VALIDATION=1
MS_DEFAULT_ALLOW_FALLBACK_ARCH=1
MS_DEFAULT_GRADLE_OFFLINE=0

# Effective (runtime) values start as copies of the defaults; they are
# mutated in place as config files and flags are processed. Keeping the
# MS_DEFAULT_* originals untouched lets `--doctor` and `--show-config`
# report "default vs effective" side by side for troubleshooting.

MS_VERBOSE=${'$'}MS_DEFAULT_VERBOSE
MS_QUIET=${'$'}MS_DEFAULT_QUIET
MS_DEBUG=${'$'}MS_DEFAULT_DEBUG
MS_NO_COLOR=${'$'}MS_DEFAULT_NO_COLOR
MS_USE_DAEMON=${'$'}MS_DEFAULT_USE_DAEMON
MS_DAEMON_LOCK_TIMEOUT_SEC=${'$'}MS_DEFAULT_DAEMON_LOCK_TIMEOUT_SEC
MS_MAX_HEAP_MB=${'$'}MS_DEFAULT_MAX_HEAP_MB
MS_METASPACE_MB=${'$'}MS_DEFAULT_METASPACE_MB
MS_TIERED_COMPILATION=${'$'}MS_DEFAULT_TIERED_COMPILATION
MS_LOG_MAX_BYTES=${'$'}MS_DEFAULT_LOG_MAX_BYTES
MS_LOG_KEEP=${'$'}MS_DEFAULT_LOG_KEEP
MS_RETRY_COUNT=${'$'}MS_DEFAULT_RETRY_COUNT
MS_RETRY_DELAY_SEC=${'$'}MS_DEFAULT_RETRY_DELAY_SEC
MS_DOCTOR_MODE=${'$'}MS_DEFAULT_DOCTOR_MODE
MS_CLEAN_TMP_ON_START=${'$'}MS_DEFAULT_CLEAN_TMP_ON_START
MS_PREFER_LEGACY_JDK=${'$'}MS_DEFAULT_PREFER_LEGACY_JDK
MS_STRICT_JDK_VALIDATION=${'$'}MS_DEFAULT_STRICT_JDK_VALIDATION
MS_ALLOW_FALLBACK_ARCH=${'$'}MS_DEFAULT_ALLOW_FALLBACK_ARCH
MS_GRADLE_OFFLINE=${'$'}MS_DEFAULT_GRADLE_OFFLINE

MS_JDK_OVERRIDE=""       # set by --jdk <path>
MS_GRADLE_ARGS=""        # remaining args forwarded verbatim to Gradle

# Exit codes. Distinct codes (rather than a single generic "1") make it
# possible for MobileStudio's UI layer to show a specific, translated error
# message instead of a generic "build failed" toast.
MS_EXIT_OK=0
MS_EXIT_GENERIC_ERROR=1
MS_EXIT_NO_JAVA_HOME=10
MS_EXIT_INVALID_JAVA_HOME=11
MS_EXIT_WRAPPER_JAR_MISSING=12
MS_EXIT_LOCK_TIMEOUT=13
MS_EXIT_NATIVE_LAUNCHER_MISSING=14
MS_EXIT_CONFIG_ERROR=15
MS_EXIT_UNSUPPORTED_ARCH=16
MS_EXIT_USER_ABORT=130

# ==============================================================================
#   SECTION 4: LOGGING FRAMEWORK
# ==============================================================================
#
# Four severities: DEBUG < INFO < WARN < ERROR. Everything at or above the
# configured threshold is written to both stderr (for the interactive/
# ADB-shell case) and a rotating log file under the app's files directory
# (for the "user reports a build failure from inside the IDE UI, with no
# terminal attached" case, which is the common case on a phone).
#
# Design notes:
#   - No external dependencies: pure POSIX `sh` builtins plus `date`.
#   - Color is auto-disabled when stderr is not a TTY, when MS_NO_COLOR=1,
#     or when the shell lacks `tput` (Android's `/system/bin/sh` sometimes
#     does).
#   - Log rotation is simple size-based rename-on-exceed, not time-based;
#     time-based rotation needs a cron-like trigger this script doesn't
#     have, and size-based is enough to keep a phone's limited storage from
#     filling with debug logs from a long-lived IDE session.
# ==============================================================================

MS_LOG_FILE=""            # populated once ${'$'}HOME is known, see SECTION 6
MS_LOG_LEVEL_DEBUG=0
MS_LOG_LEVEL_INFO=1
MS_LOG_LEVEL_WARN=2
MS_LOG_LEVEL_ERROR=3
MS_LOG_THRESHOLD=${'$'}MS_LOG_LEVEL_INFO

ms_supports_color() {
    if [ "${'$'}MS_NO_COLOR" = "1" ]; then
        return 1
    fi
    if [ -t 2 ] && command -v tput >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

if ms_supports_color; then
    MS_COLOR_RESET="${'$'}(tput sgr0 2>/dev/null)"
    MS_COLOR_RED="${'$'}(tput setaf 1 2>/dev/null)"
    MS_COLOR_YELLOW="${'$'}(tput setaf 3 2>/dev/null)"
    MS_COLOR_BLUE="${'$'}(tput setaf 4 2>/dev/null)"
    MS_COLOR_GRAY="${'$'}(tput setaf 8 2>/dev/null)"
    MS_COLOR_GREEN="${'$'}(tput setaf 2 2>/dev/null)"
else
    MS_COLOR_RESET=""
    MS_COLOR_RED=""
    MS_COLOR_YELLOW=""
    MS_COLOR_BLUE=""
    MS_COLOR_GRAY=""
    MS_COLOR_GREEN=""
fi

# ms_timestamp: ISO-8601-ish local timestamp for log lines.
ms_timestamp() {
    date "+%Y-%m-%d %H:%M:%S" 2>/dev/null || echo "0000-00-00 00:00:00"
}

# ms_log_rotate_if_needed: rename current log to .1, .2, ... up to
# MS_LOG_KEEP once it exceeds MS_LOG_MAX_BYTES. Best-effort: any failure
# here is silently ignored, since losing log history must never block a
# build.
ms_log_rotate_if_needed() {
    [ -n "${'$'}MS_LOG_FILE" ] || return 0
    [ -f "${'$'}MS_LOG_FILE" ] || return 0

    size=0
    if command -v wc >/dev/null 2>&1; then
        size=${'$'}(wc -c < "${'$'}MS_LOG_FILE" 2>/dev/null)
        size=${'$'}{size:-0}
    fi

    [ "${'$'}size" -gt "${'$'}MS_LOG_MAX_BYTES" ] 2>/dev/null || return 0

    i=${'$'}MS_LOG_KEEP
    while [ "${'$'}i" -gt 1 ]; do
        prev=${'$'}((i - 1))
        if [ -f "${'$'}{MS_LOG_FILE}.${'$'}{prev}" ]; then
            mv -f "${'$'}{MS_LOG_FILE}.${'$'}{prev}" "${'$'}{MS_LOG_FILE}.${'$'}{i}" 2>/dev/null
        fi
        i=${'$'}prev
    done
    mv -f "${'$'}MS_LOG_FILE" "${'$'}{MS_LOG_FILE}.1" 2>/dev/null
    : > "${'$'}MS_LOG_FILE" 2>/dev/null
}

# ms_log_write: append a single line to the log file, ignoring failures
# (the log file may not exist yet during very early startup, before ${'$'}HOME
# is resolved — callers before that point only get the stderr copy).
ms_log_write() {
    [ -n "${'$'}MS_LOG_FILE" ] || return 0
    printf '%s\n' "${'$'}1" >> "${'$'}MS_LOG_FILE" 2>/dev/null
}

# ms_log: internal dispatcher. Args: <level_number> <level_name> <color> <message...>
ms_log() {
    level_num="${'$'}1"; shift
    level_name="${'$'}1"; shift
    color="${'$'}1"; shift
    msg="${'$'}*"

    [ "${'$'}level_num" -ge "${'$'}MS_LOG_THRESHOLD" ] 2>/dev/null || return 0

    ts="${'$'}(ms_timestamp)"
    plain_line="[${'$'}ts] [${'$'}level_name] ${'$'}msg"

    if [ "${'$'}MS_QUIET" != "1" ] || [ "${'$'}level_num" -ge "${'$'}MS_LOG_LEVEL_WARN" ]; then
        printf '%s[%s]%s %s\n' "${'$'}color" "${'$'}level_name" "${'$'}MS_COLOR_RESET" "${'$'}msg" >&2
    fi

    ms_log_rotate_if_needed
    ms_log_write "${'$'}plain_line"
}

ms_log_debug() { [ "${'$'}MS_DEBUG" = "1" ] && ms_log "${'$'}MS_LOG_LEVEL_DEBUG" "DEBUG" "${'$'}MS_COLOR_GRAY" "${'$'}@"; return 0; }
ms_log_info()  { ms_log "${'$'}MS_LOG_LEVEL_INFO"  "INFO"  "${'$'}MS_COLOR_BLUE"   "${'$'}@"; }
ms_log_warn()  { ms_log "${'$'}MS_LOG_LEVEL_WARN"  "WARN"  "${'$'}MS_COLOR_YELLOW" "${'$'}@"; }
ms_log_error() { ms_log "${'$'}MS_LOG_LEVEL_ERROR" "ERROR" "${'$'}MS_COLOR_RED"    "${'$'}@"; }
ms_log_ok()    { ms_log "${'$'}MS_LOG_LEVEL_INFO"  "OK"    "${'$'}MS_COLOR_GREEN"  "${'$'}@"; }

# ms_die: log an error, then exit with the given code. Centralizing this
# means every failure path produces a consistently formatted message
# instead of a bare `exit 1` with no context — important on a phone where
# the user is likely to screenshot or copy-paste the last visible line.
ms_die() {
    code="${'$'}1"; shift
    ms_log_error "${'$'}@"
    ms_log_error "(exiting with code ${'$'}code — run with --doctor for a guided diagnosis)"
    exit "${'$'}code"
}

# ==============================================================================
#   SECTION 5: GENERAL-PURPOSE UTILITY FUNCTIONS
# ==============================================================================
#
# `/system/bin/sh` on Android is typically a minimal Almquist-derived shell
# (toybox/mksh/dash depending on ROM/vendor), so these avoid bashisms:
# no arrays, no `[[ ]]`, no `${'$'}{var,,}`, no `local` guarantees beyond what
# dash/ash provide. Every function below has been written to run under
# `dash`, `mksh`, and `busybox ash`.
# ==============================================================================

# ms_trim: strip leading/trailing whitespace from ${'$'}1, echo the result.
ms_trim() {
    val="${'$'}1"
    # Strip leading whitespace/tabs.
    while [ "${'$'}{val# }" != "${'$'}val" ] || [ "${'$'}{val#	}" != "${'$'}val" ]; do
        val="${'$'}{val# }"
        val="${'$'}{val#	}"
    done
    # Strip trailing whitespace/tabs.
    while [ "${'$'}{val% }" != "${'$'}val" ] || [ "${'$'}{val%	}" != "${'$'}val" ]; do
        val="${'$'}{val% }"
        val="${'$'}{val%	}"
    done
    printf '%s' "${'$'}val"
}

# ms_is_truthy: returns 0 (true) for common "on" spellings, 1 otherwise.
# Accepts: 1, true, TRUE, True, yes, YES, on, ON.
ms_is_truthy() {
    case "${'$'}1" in
        1|true|TRUE|True|yes|YES|on|ON) return 0 ;;
        *) return 1 ;;
    esac
}

# ms_is_falsy: inverse convenience wrapper, accepts the mirror-image spellings.
ms_is_falsy() {
    case "${'$'}1" in
        0|false|FALSE|False|no|NO|off|OFF) return 0 ;;
        *) return 1 ;;
    esac
}

# ms_bool_to_flag: normalize a truthy/falsy string to "1"/"0". Any value
# that is neither recognizably truthy nor falsy is passed through
# unchanged so a caller can detect and warn about a malformed config
# value, rather than silently coercing "maybe" to "0".
ms_bool_to_flag() {
    if ms_is_truthy "${'$'}1"; then
        printf '1'
    elif ms_is_falsy "${'$'}1"; then
        printf '0'
    else
        printf '%s' "${'$'}1"
    fi
}

# ms_path_exists_executable: true if ${'$'}1 exists and is executable.
ms_path_exists_executable() {
    [ -n "${'$'}1" ] && [ -x "${'$'}1" ]
}

# ms_path_exists_regular: true if ${'$'}1 exists and is a regular file.
ms_path_exists_regular() {
    [ -n "${'$'}1" ] && [ -f "${'$'}1" ]
}

# ms_path_exists_dir: true if ${'$'}1 exists and is a directory.
ms_path_exists_dir() {
    [ -n "${'$'}1" ] && [ -d "${'$'}1" ]
}

# ms_join_path: join ${'$'}1 and ${'$'}2 with exactly one slash, regardless of
# whether ${'$'}1 already ends with one. Avoids the classic `"${'$'}a/${'$'}b"` bug
# where a trailing slash on ${'$'}a produces `//`.
ms_join_path() {
    base="${'$'}1"
    leaf="${'$'}2"
    base="${'$'}{base%/}"
    leaf="${'$'}{leaf#/}"
    printf '%s/%s' "${'$'}base" "${'$'}leaf"
}

# ms_realpath: best-effort canonical path resolution. Prefers the
# `realpath` binary if present (not guaranteed on Android), falls back to
# `readlink -f` (also not guaranteed), and finally falls back to a manual
# cd-and-pwd resolution that works for directories even with neither tool.
ms_realpath() {
    target="${'$'}1"
    if command -v realpath >/dev/null 2>&1; then
        realpath "${'$'}target" 2>/dev/null && return 0
    fi
    if command -v readlink >/dev/null 2>&1; then
        readlink -f "${'$'}target" 2>/dev/null && return 0
    fi
    if [ -d "${'$'}target" ]; then
        (cd "${'$'}target" 2>/dev/null && pwd) && return 0
    fi
    dir="${'$'}(dirname "${'$'}target")"
    base="${'$'}(basename "${'$'}target")"
    if [ -d "${'$'}dir" ]; then
        resolved_dir="${'$'}(cd "${'$'}dir" 2>/dev/null && pwd)"
        if [ -n "${'$'}resolved_dir" ]; then
            printf '%s/%s' "${'$'}resolved_dir" "${'$'}base"
            return 0
        fi
    fi
    printf '%s' "${'$'}target"
}

# ms_human_bytes: render a byte count as a human-readable size. Pure
# integer shell arithmetic (no bc/awk dependency, since neither is
# guaranteed present in a minimal Android `sh`).
ms_human_bytes() {
    bytes="${'$'}1"
    if [ "${'$'}bytes" -lt 1024 ] 2>/dev/null; then
        printf '%sB' "${'$'}bytes"
        return 0
    fi
    kb=${'$'}((bytes / 1024))
    if [ "${'$'}kb" -lt 1024 ]; then
        printf '%sKB' "${'$'}kb"
        return 0
    fi
    mb=${'$'}((kb / 1024))
    if [ "${'$'}mb" -lt 1024 ]; then
        printf '%sMB' "${'$'}mb"
        return 0
    fi
    gb=${'$'}((mb / 1024))
    printf '%sGB' "${'$'}gb"
}

# ms_mkdir_p: mkdir -p with a logged failure instead of a silent one,
# used for every directory this script creates so a permissions problem
# on a locked-down device shows up in the log immediately.
ms_mkdir_p() {
    dir="${'$'}1"
    if [ -d "${'$'}dir" ]; then
        return 0
    fi
    if mkdir -p "${'$'}dir" 2>/dev/null; then
        ms_log_debug "created directory: ${'$'}dir"
        return 0
    fi
    ms_log_warn "failed to create directory: ${'$'}dir (continuing; downstream steps may fail)"
    return 1
}

# ms_first_existing_executable: given a list of candidate paths (passed as
# separate arguments), echo the first one that exists and is executable.
# Returns non-zero and prints nothing if none match. Used throughout the
# JDK/toolchain discovery logic in SECTION 7 instead of a long if/elif
# chain, so a new candidate path is a one-line addition, not a new branch.
ms_first_existing_executable() {
    for candidate in "${'$'}@"; do
        if ms_path_exists_executable "${'$'}candidate"; then
            printf '%s' "${'$'}candidate"
            return 0
        fi
    done
    return 1
}

# ms_get_prop: read a single `key=value` line from a simple properties
# file (as used by mobilestudio.properties, gradle.properties). Ignores
# blank lines and lines beginning with `#` or `!` (Java properties
# comment characters). Does not attempt full Java-properties escaping —
# only what MobileStudio's own config files actually use, which is plain
# `key=value` with no line continuations.
ms_get_prop() {
    file="${'$'}1"
    key="${'$'}2"
    [ -f "${'$'}file" ] || return 1

    line="${'$'}(grep -E "^[[:space:]]*${'$'}{key}[[:space:]]*=" "${'$'}file" 2>/dev/null | tail -n 1)"
    [ -n "${'$'}line" ] || return 1

    value="${'$'}{line#*=}"
    ms_trim "${'$'}value"
}

# ms_elapsed_ms: coarse elapsed-time helper for the timing instrumentation
# used in --verbose mode. `date +%s%N` is not universally available on
# Android's `sh` (nanoseconds are a GNU coreutils extension), so this
# degrades gracefully to whole-second resolution when unavailable.
ms_now_ms() {
    if date +%s%N >/dev/null 2>&1; then
        ns="${'$'}(date +%s%N)"
        echo ${'$'}((ns / 1000000))
    else
        s="${'$'}(date +%s)"
        echo ${'$'}((s * 1000))
    fi
}

# ==============================================================================
#   SECTION 6: COMMAND-LINE ARGUMENT PARSING
# ==============================================================================
#
# This script accepts a small set of launcher-level flags of its own (all
# prefixed to avoid ever colliding with a real Gradle task name or CLI
# flag), and forwards everything else untouched to Gradle. Launcher flags
# are stripped before the remainder is handed to GradleWrapperMain, so
# `./gradlew --verbose assembleDebug` runs `assembleDebug` verbosely
# rather than passing the literal string `--verbose` through to Gradle
# (which would fail, since Gradle spells its own verbose flag `-i`/
# `--info`, not `--verbose`).
#
# Recognized launcher flags:
#   --verbose, -v            Enable DEBUG-level logging to stderr + log file
#   --quiet, -q               Suppress INFO-level stderr output (log file
#                              still receives everything at the configured
#                              threshold)
#   --debug                   Alias for --verbose (kept for muscle-memory
#                              compatibility with `gradle --debug`, even
#                              though it does not enable Gradle's own
#                              --debug flag — that still needs to be passed
#                              explicitly if desired, since it is extremely
#                              verbose and rarely what's wanted by default)
#   --no-color                 Disable ANSI color in launcher log output
#                              (Gradle's own output color is unaffected)
#   --no-daemon                 Force org.gradle.daemon=false for this run,
#                              overriding gradle.properties
#   --daemon                    Force org.gradle.daemon=true for this run
#   --jdk <path>                 Use this JDK root instead of autodetecting
#   --clean-tmp                  Remove ${'$'}TMPDIR contents before starting
#   --doctor                     Run diagnostics only; do not invoke Gradle
#   --show-config                 Print the fully resolved configuration and exit
#   --offline                     Force Gradle's --offline flag
#   --retry <n>                  Override MS_RETRY_COUNT for this invocation
#   --help, -h                    Print usage and exit
#   --version                     Print MobileStudio launcher version and exit
#   --                             Everything after this is forwarded to
#                              Gradle verbatim, even if it looks like a
#                              launcher flag (escape hatch for a task named
#                              e.g. "--doctor" in a weird build script)
# ==============================================================================

ms_print_version() {
    printf '%s version %s\n' "${'$'}MS_SCRIPT_NAME" "${'$'}MS_SCRIPT_VERSION"
    printf '%s\n' "${'$'}MS_SCRIPT_DESCRIPTION"
}

ms_print_usage() {
    cat <<'MS_USAGE_EOF'
Usage: gradlew [launcher-options] [--] [gradle-args...]

Launcher options (consumed by this script, not forwarded to Gradle):
  -v, --verbose          Verbose launcher logging (DEBUG level)
  -q, --quiet            Suppress non-warning launcher output
      --debug            Alias for --verbose
      --no-color         Disable ANSI colors in launcher output
      --no-daemon        Force the Gradle daemon off for this run
      --daemon           Force the Gradle daemon on for this run
      --jdk <path>       Use a specific JDK root instead of autodetecting
      --clean-tmp        Clear the launcher tmp dir before starting
      --doctor           Diagnose the on-device JVM environment and exit
      --show-config      Print resolved configuration and exit
      --offline          Force Gradle's --offline mode
      --retry <n>        Retry a failed Gradle invocation up to n times
  -h, --help             Show this help text and exit
      --version          Show launcher version and exit
  --                      Stop parsing launcher options; forward the rest

Everything not recognized above is forwarded to the Gradle Wrapper
unchanged, in order, e.g.:

  gradlew --verbose assembleDebug --stacktrace

runs the launcher verbosely, then hands `assembleDebug --stacktrace` to
Gradle exactly as `./gradlew assembleDebug --stacktrace` would.

Configuration can also be set persistently via a `mobilestudio.properties`
file in either ${'$'}GRADLE_USER_HOME or the project root — see --show-config
for the list of recognized keys and their current effective values.
MS_USAGE_EOF
}

# ms_parse_args: populates the MS_* runtime variables from argv, and
# accumulates whatever remains for forwarding to Gradle in
# ${'$'}MS_GRADLE_ARGS_QUOTED. POSIX sh has no arrays, so a naive accumulator
# (a plain space- or newline-joined string, later re-split with an
# unquoted `${'$'}(...)`) would still be re-split on every IFS character
# inside each argument when it's expanded — silently breaking any
# argument containing a space, e.g. a quoted `-Pmessage="hello world"`
# would arrive at Gradle as two separate arguments. Instead, each
# argument is single-quote-escaped as it's appended (any embedded `'` is
# replaced with `'\''`, the standard POSIX-shell escape for "close quote,
# literal quote, reopen quote"), so that later doing
# `eval set -- ${'$'}MS_GRADLE_ARGS_QUOTED` reconstructs the exact original
# argument list — spaces, quotes, and all — as real positional
# parameters, not a re-split string.
MS_GRADLE_ARGS_QUOTED=""

ms_quote_single_arg() {
    printf '%s' "${'$'}1" | sed "s/'/'\\\\''/g"
}

ms_append_gradle_arg() {
    quoted="${'$'}(ms_quote_single_arg "${'$'}1")"
    MS_GRADLE_ARGS_QUOTED="${'$'}MS_GRADLE_ARGS_QUOTED '${'$'}quoted'"
}

ms_parse_args() {
    forward_rest=0

    while [ ${'$'}# -gt 0 ]; do
        if [ "${'$'}forward_rest" = "1" ]; then
            ms_append_gradle_arg "${'$'}1"
            shift
            continue
        fi

        case "${'$'}1" in
            -v|--verbose)
                MS_VERBOSE=1
                MS_LOG_THRESHOLD=${'$'}MS_LOG_LEVEL_DEBUG
                MS_DEBUG=1
                shift
                ;;
            --debug)
                MS_VERBOSE=1
                MS_LOG_THRESHOLD=${'$'}MS_LOG_LEVEL_DEBUG
                MS_DEBUG=1
                shift
                ;;
            -q|--quiet)
                MS_QUIET=1
                shift
                ;;
            --no-color)
                MS_NO_COLOR=1
                MS_COLOR_RESET=""; MS_COLOR_RED=""; MS_COLOR_YELLOW=""
                MS_COLOR_BLUE=""; MS_COLOR_GRAY=""; MS_COLOR_GREEN=""
                shift
                ;;
            --no-daemon)
                MS_USE_DAEMON=0
                shift
                ;;
            --daemon)
                MS_USE_DAEMON=1
                shift
                ;;
            --jdk)
                shift
                if [ ${'$'}# -eq 0 ]; then
                    ms_die "${'$'}MS_EXIT_CONFIG_ERROR" "--jdk requires a path argument"
                fi
                MS_JDK_OVERRIDE="${'$'}1"
                shift
                ;;
            --jdk=*)
                MS_JDK_OVERRIDE="${'$'}{1#--jdk=}"
                shift
                ;;
            --clean-tmp)
                MS_CLEAN_TMP_ON_START=1
                shift
                ;;
            --doctor)
                MS_DOCTOR_MODE=1
                shift
                ;;
            --show-config)
                MS_SHOW_CONFIG=1
                shift
                ;;
            --offline)
                MS_GRADLE_OFFLINE=1
                shift
                ;;
            --retry)
                shift
                if [ ${'$'}# -eq 0 ]; then
                    ms_die "${'$'}MS_EXIT_CONFIG_ERROR" "--retry requires a numeric argument"
                fi
                MS_RETRY_COUNT="${'$'}1"
                shift
                ;;
            --retry=*)
                MS_RETRY_COUNT="${'$'}{1#--retry=}"
                shift
                ;;
            -h|--help)
                ms_print_usage
                exit ${'$'}MS_EXIT_OK
                ;;
            --version)
                ms_print_version
                exit ${'$'}MS_EXIT_OK
                ;;
            --)
                forward_rest=1
                shift
                ;;
            *)
                ms_append_gradle_arg "${'$'}1"
                shift
                ;;
        esac
    done
}

# ==============================================================================
#   SECTION 7: CONFIGURATION FILE LOADING
# ==============================================================================
#
# Two optional `mobilestudio.properties` files are consulted, in this
# order (later wins):
#
#   1. ${'$'}GRADLE_USER_HOME/mobilestudio.properties   — per-user-install,
#      applies to every MobileStudio project on this device.
#   2. ${'$'}APP_HOME/mobilestudio.properties            — per-project,
#      committed alongside gradle.properties, applies only to this build.
#
# Recognized keys mirror the MS_* variable names in lowercase dotted form,
# e.g. `verbose=true`, `daemon.lockTimeoutSec=120`,
# `jdk.preferLegacy=false`. Unknown keys are logged at DEBUG and ignored
# rather than treated as an error, so a properties file shared between a
# newer and older copy of this script degrades gracefully instead of
# hard-failing on an unrecognized future option.
# ==============================================================================

ms_apply_prop_bool() {
    file="${'$'}1"; key="${'$'}2"; varname="${'$'}3"
    val="${'$'}(ms_get_prop "${'$'}file" "${'$'}key")" || return 0
    [ -n "${'$'}val" ] || return 0
    normalized="${'$'}(ms_bool_to_flag "${'$'}val")"
    case "${'$'}normalized" in
        0|1) eval "${'$'}varname=\${'$'}normalized" ;;
        *) ms_log_warn "ignoring malformed boolean for '${'$'}key' in ${'$'}file: '${'$'}val'" ;;
    esac
}

ms_apply_prop_int() {
    file="${'$'}1"; key="${'$'}2"; varname="${'$'}3"
    val="${'$'}(ms_get_prop "${'$'}file" "${'$'}key")" || return 0
    [ -n "${'$'}val" ] || return 0
    case "${'$'}val" in
        ''|*[!0-9]*)
            ms_log_warn "ignoring non-numeric value for '${'$'}key' in ${'$'}file: '${'$'}val'"
            ;;
        *)
            eval "${'$'}varname=\${'$'}val"
            ;;
    esac
}

ms_apply_prop_str() {
    file="${'$'}1"; key="${'$'}2"; varname="${'$'}3"
    val="${'$'}(ms_get_prop "${'$'}file" "${'$'}key")" || return 0
    [ -n "${'$'}val" ] || return 0
    eval "${'$'}varname=\${'$'}val"
}

# ms_load_config_file: apply every recognized key from one properties
# file to the current MS_* runtime variables.
ms_load_config_file() {
    file="${'$'}1"
    [ -f "${'$'}file" ] || return 0

    ms_log_debug "loading config: ${'$'}file"

    ms_apply_prop_bool "${'$'}file" "verbose"                    MS_VERBOSE
    ms_apply_prop_bool "${'$'}file" "quiet"                       MS_QUIET
    ms_apply_prop_bool "${'$'}file" "debug"                        MS_DEBUG
    ms_apply_prop_bool "${'$'}file" "noColor"                        MS_NO_COLOR
    ms_apply_prop_bool "${'$'}file" "daemon.enabled"                   MS_USE_DAEMON
    ms_apply_prop_int  "${'$'}file" "daemon.lockTimeoutSec"               MS_DAEMON_LOCK_TIMEOUT_SEC
    ms_apply_prop_int  "${'$'}file" "jvm.maxHeapMb"                          MS_MAX_HEAP_MB
    ms_apply_prop_int  "${'$'}file" "jvm.metaspaceMb"                           MS_METASPACE_MB
    ms_apply_prop_bool "${'$'}file" "jvm.tieredCompilation"                        MS_TIERED_COMPILATION
    ms_apply_prop_int  "${'$'}file" "log.maxBytes"                                   MS_LOG_MAX_BYTES
    ms_apply_prop_int  "${'$'}file" "log.keep"                                          MS_LOG_KEEP
    ms_apply_prop_int  "${'$'}file" "retry.count"                                          MS_RETRY_COUNT
    ms_apply_prop_int  "${'$'}file" "retry.delaySec"                                          MS_RETRY_DELAY_SEC
    ms_apply_prop_bool "${'$'}file" "cleanTmpOnStart"                                             MS_CLEAN_TMP_ON_START
    ms_apply_prop_bool "${'$'}file" "jdk.preferLegacy"                                                MS_PREFER_LEGACY_JDK
    ms_apply_prop_bool "${'$'}file" "jdk.strictValidation"                                               MS_STRICT_JDK_VALIDATION
    ms_apply_prop_bool "${'$'}file" "arch.allowFallback"                                                    MS_ALLOW_FALLBACK_ARCH
    ms_apply_prop_bool "${'$'}file" "gradle.offline"                                                           MS_GRADLE_OFFLINE
    ms_apply_prop_str  "${'$'}file" "jdk.path"                                                                    MS_JDK_OVERRIDE

    if [ "${'$'}MS_VERBOSE" = "1" ]; then
        MS_LOG_THRESHOLD=${'$'}MS_LOG_LEVEL_DEBUG
    fi
}

ms_print_effective_config() {
    cat <<MS_CONFIG_EOF
${'$'}{MS_SCRIPT_NAME} v${'$'}{MS_SCRIPT_VERSION} — resolved configuration
------------------------------------------------------------------
verbose                    = ${'$'}MS_VERBOSE      (default: ${'$'}MS_DEFAULT_VERBOSE)
quiet                      = ${'$'}MS_QUIET        (default: ${'$'}MS_DEFAULT_QUIET)
debug                      = ${'$'}MS_DEBUG        (default: ${'$'}MS_DEFAULT_DEBUG)
noColor                    = ${'$'}MS_NO_COLOR     (default: ${'$'}MS_DEFAULT_NO_COLOR)
daemon.enabled             = ${'$'}MS_USE_DAEMON   (default: ${'$'}MS_DEFAULT_USE_DAEMON)
daemon.lockTimeoutSec       = ${'$'}MS_DAEMON_LOCK_TIMEOUT_SEC (default: ${'$'}MS_DEFAULT_DAEMON_LOCK_TIMEOUT_SEC)
jvm.maxHeapMb                = ${'$'}MS_MAX_HEAP_MB (default: ${'$'}MS_DEFAULT_MAX_HEAP_MB)
jvm.metaspaceMb                = ${'$'}MS_METASPACE_MB (default: ${'$'}MS_DEFAULT_METASPACE_MB)
jvm.tieredCompilation             = ${'$'}MS_TIERED_COMPILATION (default: ${'$'}MS_DEFAULT_TIERED_COMPILATION)
log.maxBytes                        = ${'$'}MS_LOG_MAX_BYTES (default: ${'$'}MS_DEFAULT_LOG_MAX_BYTES)
log.keep                               = ${'$'}MS_LOG_KEEP (default: ${'$'}MS_DEFAULT_LOG_KEEP)
retry.count                               = ${'$'}MS_RETRY_COUNT (default: ${'$'}MS_DEFAULT_RETRY_COUNT)
retry.delaySec                               = ${'$'}MS_RETRY_DELAY_SEC (default: ${'$'}MS_DEFAULT_RETRY_DELAY_SEC)
cleanTmpOnStart                                 = ${'$'}MS_CLEAN_TMP_ON_START (default: ${'$'}MS_DEFAULT_CLEAN_TMP_ON_START)
jdk.preferLegacy                                   = ${'$'}MS_PREFER_LEGACY_JDK (default: ${'$'}MS_DEFAULT_PREFER_LEGACY_JDK)
jdk.strictValidation                                  = ${'$'}MS_STRICT_JDK_VALIDATION (default: ${'$'}MS_DEFAULT_STRICT_JDK_VALIDATION)
arch.allowFallback                                       = ${'$'}MS_ALLOW_FALLBACK_ARCH (default: ${'$'}MS_DEFAULT_ALLOW_FALLBACK_ARCH)
gradle.offline                                              = ${'$'}MS_GRADLE_OFFLINE (default: ${'$'}MS_DEFAULT_GRADLE_OFFLINE)
jdk.path (override)                                            = ${'$'}{MS_JDK_OVERRIDE:-<autodetect>}
------------------------------------------------------------------
resolved JAVA_HOME          = ${'$'}{JAVA_HOME:-<not yet resolved>}
resolved APP_HOME            = ${'$'}{APP_HOME:-<not yet resolved>}
resolved HOME (app data)      = ${'$'}{HOME:-<not yet resolved>}
resolved GRADLE_USER_HOME      = ${'$'}{GRADLE_USER_HOME:-<not yet resolved>}
resolved TMPDIR                 = ${'$'}{TMPDIR:-<not yet resolved>}
log file                         = ${'$'}{MS_LOG_FILE:-<not yet resolved>}
MS_CONFIG_EOF
}

# ==============================================================================
#   SECTION 8: JDK / TOOLCHAIN DISCOVERY
# ==============================================================================
#
# MobileStudio has shipped its on-device JDK under several different
# directory layouts over the project's history (current, legacy
# JreGradleInstaller-era, and "shim" compatibility layouts), across two
# JDK major versions (17 and 21), under two possible app-data mount
# points (`/data/data/...` and `/data/user/0/...`), and — for users who
# sideload or test a self-built JDK via Termux rather than through the
# in-app installer — a handful of common Termux-adjacent locations as
# well.
#
# JDK 17 is currently the *recommended* toolchain (Gradle 8.2 / JDK 17 is
# the supported combination as of this writing); JDK 21 support is
# tracked separately and has known issues on-device (see --doctor for
# specifics). Detection therefore prefers a JDK 17 install over a JDK 21
# install when both are present, unless MS_PREFER_LEGACY_JDK reverses
# that (kept as an escape hatch, not because JDK 21 is "legacy" — the
# name refers to the *directory layout* preference, not the JDK version
# preference, and is intentionally decoupled from it).
#
# Detection order is expressed as a flat, ordered list of candidate
# JAVA_HOME roots rather than nested if/elif, so adding a future layout
# is a one-line change to a list, not a restructuring of control flow.
# ==============================================================================

MS_JDK17_CANDIDATES=""
MS_JDK21_CANDIDATES=""
MS_JDK_OTHER_CANDIDATES=""

ms_build_jdk_candidate_lists() {
    for root in "${'$'}MS_DATA_ROOT_DATA" "${'$'}MS_DATA_ROOT_USER" "${'$'}MS_DATA_ROOT_DATA_ALT" "${'$'}MS_DATA_ROOT_USER_ALT"; do
        # --- Current (post-refactor) toolchain layout ---------------------
        MS_JDK17_CANDIDATES="${'$'}MS_JDK17_CANDIDATES
${'$'}{root}/files/toolchain/jdk17"
        MS_JDK21_CANDIDATES="${'$'}MS_JDK21_CANDIDATES
${'$'}{root}/files/toolchain/jdk21"
        # Version-unqualified path: historically pointed at whichever JDK
        # was current at install time — treated as "other" since its
        # actual version can't be assumed without probing `release`.
        MS_JDK_OTHER_CANDIDATES="${'$'}MS_JDK_OTHER_CANDIDATES
${'$'}{root}/files/toolchain/jdk"

        # --- Legacy JreGradleInstaller layout ------------------------------
        MS_JDK17_CANDIDATES="${'$'}MS_JDK17_CANDIDATES
${'$'}{root}/files/toolchain/jdk/lib/jvm/java-17-openjdk"
        MS_JDK21_CANDIDATES="${'$'}MS_JDK21_CANDIDATES
${'$'}{root}/files/toolchain/jdk/lib/jvm/java-21-openjdk"

        # --- Shim compatibility layout --------------------------------------
        MS_JDK17_CANDIDATES="${'$'}MS_JDK17_CANDIDATES
${'$'}{root}/files/toolchain/jdk/shim/java-17-openjdk"
        MS_JDK21_CANDIDATES="${'$'}MS_JDK21_CANDIDATES
${'$'}{root}/files/toolchain/jdk/shim/java-21-openjdk"

        # --- Self-built-JDK convention (matches the Android JDK port's
        #     `make images` output directory name for an arm64 target) ------
        MS_JDK17_CANDIDATES="${'$'}MS_JDK17_CANDIDATES
${'$'}{root}/files/toolchain/build/jdk17/images/jdk
${'$'}{root}/files/jdk-17-android-arm64"
        MS_JDK21_CANDIDATES="${'$'}MS_JDK21_CANDIDATES
${'$'}{root}/files/toolchain/build/jdk21/images/jdk
${'$'}{root}/files/jdk-21-android-arm64"
    done

    # --- Termux-adjacent locations, for users testing a self-built JDK
    #     outside the MobileStudio installer (matches the Termux testing
    #     setup used during JDK bring-up: /data/data/com.termux/files/usr) --
    MS_JDK17_CANDIDATES="${'$'}MS_JDK17_CANDIDATES
/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk
/data/data/com.termux/files/usr/opt/openjdk-17"
    MS_JDK21_CANDIDATES="${'$'}MS_JDK21_CANDIDATES
/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
/data/data/com.termux/files/usr/opt/openjdk-21"

    # --- Explicit environment override, checked first regardless of list
    #     ordering below (handled separately in ms_detect_java_home) --------
    :
}

# ms_probe_jdk_release_version: best-effort read of the `JAVA_VERSION`
# field from a JDK root's `release` file (a standard file every OpenJDK
# distribution writes at build time). Used only for diagnostics and log
# messages — detection order itself does not depend on this succeeding,
# since a self-built on-device JDK's `release` file has occasionally been
# found to be missing or truncated when the build was interrupted.
ms_probe_jdk_release_version() {
    jdk_root="${'$'}1"
    release_file="${'$'}(ms_join_path "${'$'}jdk_root" "release")"
    [ -f "${'$'}release_file" ] || { printf 'unknown'; return 0; }

    version_line="${'$'}(grep -E '^JAVA_VERSION=' "${'$'}release_file" 2>/dev/null | head -n 1)"
    if [ -n "${'$'}version_line" ]; then
        printf '%s' "${'$'}{version_line#JAVA_VERSION=}" | tr -d '"'
        return 0
    fi
    printf 'unknown'
}

# ms_first_valid_jdk_from_list: given a newline-separated candidate list,
# return the first entry whose `bin/java` exists and is executable.
ms_first_valid_jdk_from_list() {
    list="${'$'}1"
    old_ifs="${'$'}IFS"
    IFS='
'
    for candidate in ${'$'}list; do
        [ -n "${'$'}candidate" ] || continue
        java_bin="${'$'}(ms_join_path "${'$'}candidate" "bin/java")"
        if ms_path_exists_executable "${'$'}java_bin"; then
            IFS="${'$'}old_ifs"
            printf '%s' "${'$'}candidate"
            return 0
        fi
    done
    IFS="${'$'}old_ifs"
    return 1
}

# ms_detect_java_home: resolves JAVA_HOME using, in priority order:
#   1. --jdk / mobilestudio.properties jdk.path override
#   2. A JAVA_HOME already set in the calling environment
#   3. JDK 17 candidates (or JDK 21 first, if MS_PREFER_LEGACY_JDK=1 —
#      see the note in the SECTION 8 header about what that flag actually
#      toggles)
#   4. JDK 21 candidates
#   5. Unqualified/"other" candidates, as a last resort
#
# Exits via ms_die (MS_EXIT_NO_JAVA_HOME) if nothing is found anywhere,
# with a message listing every path that was checked — deliberately
# verbose, since "JAVA_HOME not set" with no further context is the
# single most common and least actionable error this script used to
# produce before this rewrite.
ms_detect_java_home() {
    if [ -n "${'$'}MS_JDK_OVERRIDE" ]; then
        java_bin="${'$'}(ms_join_path "${'$'}MS_JDK_OVERRIDE" "bin/java")"
        if ms_path_exists_executable "${'$'}java_bin"; then
            JAVA_HOME="${'$'}MS_JDK_OVERRIDE"
            ms_log_info "using --jdk override: ${'$'}JAVA_HOME"
            return 0
        fi
        ms_die "${'$'}MS_EXIT_INVALID_JAVA_HOME" \
            "--jdk was set to '${'$'}MS_JDK_OVERRIDE' but '${'$'}java_bin' is not executable"
    fi

    if [ -n "${'$'}JAVA_HOME" ]; then
        java_bin="${'$'}(ms_join_path "${'$'}JAVA_HOME" "bin/java")"
        if ms_path_exists_executable "${'$'}java_bin"; then
            ms_log_debug "using pre-set JAVA_HOME from environment: ${'$'}JAVA_HOME"
            return 0
        fi
        ms_log_warn "environment JAVA_HOME='${'$'}JAVA_HOME' has no executable bin/java; autodetecting instead"
        JAVA_HOME=""
    fi

    ms_build_jdk_candidate_lists

    if [ "${'$'}MS_PREFER_LEGACY_JDK" = "1" ]; then
        first_list="${'$'}MS_JDK21_CANDIDATES"; second_list="${'$'}MS_JDK17_CANDIDATES"
    else
        first_list="${'$'}MS_JDK17_CANDIDATES"; second_list="${'$'}MS_JDK21_CANDIDATES"
    fi

    found="${'$'}(ms_first_valid_jdk_from_list "${'$'}first_list")"
    if [ -z "${'$'}found" ]; then
        found="${'$'}(ms_first_valid_jdk_from_list "${'$'}second_list")"
    fi
    if [ -z "${'$'}found" ]; then
        found="${'$'}(ms_first_valid_jdk_from_list "${'$'}MS_JDK_OTHER_CANDIDATES")"
    fi

    if [ -n "${'$'}found" ]; then
        JAVA_HOME="${'$'}found"
        detected_version="${'$'}(ms_probe_jdk_release_version "${'$'}JAVA_HOME")"
        ms_log_info "detected JDK at ${'$'}JAVA_HOME (JAVA_VERSION=${'$'}detected_version)"
        return 0
    fi

    ms_log_error "no usable JDK found. Checked the following locations:"
    old_ifs="${'$'}IFS"; IFS='
'
    for c in ${'$'}MS_JDK17_CANDIDATES ${'$'}MS_JDK21_CANDIDATES ${'$'}MS_JDK_OTHER_CANDIDATES; do
        [ -n "${'$'}c" ] || continue
        ms_log_error "  - ${'$'}c/bin/java"
    done
    IFS="${'$'}old_ifs"
    ms_die "${'$'}MS_EXIT_NO_JAVA_HOME" \
        "JAVA_HOME could not be resolved (MobileStudio JDK not found). Install or reinstall the toolchain from MobileStudio's setup wizard, or pass --jdk <path> to point at a working one."
}

# ==============================================================================
#   SECTION 9: JDK VALIDATION
# ==============================================================================
#
# Finding *a* `bin/java` is necessary but not sufficient on this platform.
# Two failure modes have been observed with on-device JDK builds that
# pass the SECTION 8 existence check but still cannot run Gradle:
#
#   (a) "no known VMs. (check for corrupt jvm.cfg file)" — this is
#       produced by the JDK launcher when `lib/jvm.cfg` is missing, empty,
#       or lists a `-server`/`-client` variant whose corresponding
#       `lib/server/libjvm.so` (or `lib/client/libjvm.so`) is not actually
#       present. Because MobileStudio's on-device JDK builds are
#       server-JVM-only (see the configure-script notes referenced by
#       this project), the *client* variant is expected to be absent —
#       only a missing *server* variant is an actual problem.
#
#   (b) A dlopen failure resolving a Bionic-libc-provided symbol such as
#       `_ZN6Thread12_thr_currentE` when the native launcher tries to
#       load `libjvm.so` — this indicates the JDK build's target Bionic
#       API level does not match the device's actual Bionic, and is a
#       toolchain/rebuild problem this script cannot fix, but *can*
#       detect early (by checking `jvm.cfg` and `libjvm.so` are at least
#       *present and correctly named*) and report clearly instead of
#       letting it surface as an opaque native crash mid-build.
#
# MS_STRICT_JDK_VALIDATION=1 (the default) performs these checks and
# aborts early with a specific, actionable message. Setting it to 0
# skips straight to invoking Gradle — useful if a user's setup trips a
# false positive here (e.g. a nonstandard JDK layout) and they'd rather
# see Gradle's own error than this script's.
# ==============================================================================

ms_validate_jvm_cfg() {
    jdk_root="${'$'}1"
    jvm_cfg="${'$'}(ms_join_path "${'$'}jdk_root" "lib/jvm.cfg")"

    if [ ! -f "${'$'}jvm_cfg" ]; then
        ms_log_warn "no lib/jvm.cfg found under ${'$'}jdk_root — this JDK build may be incomplete"
        return 1
    fi

    if [ ! -s "${'$'}jvm_cfg" ]; then
        ms_log_warn "lib/jvm.cfg at ${'$'}jvm_cfg is empty"
        return 1
    fi

    server_libjvm="${'$'}(ms_join_path "${'$'}jdk_root" "lib/server/libjvm.so")"
    if grep -Eq '^-server[[:space:]]+KNOWN' "${'$'}jvm_cfg" 2>/dev/null; then
        if [ ! -f "${'$'}server_libjvm" ]; then
            ms_log_warn "jvm.cfg declares -server KNOWN but ${'$'}server_libjvm is missing"
            ms_log_warn "this reproduces the 'no known VMs. (check for corrupt jvm.cfg file)' failure mode"
            return 1
        fi
    else
        ms_log_warn "jvm.cfg at ${'$'}jvm_cfg does not declare a -server VM as KNOWN"
        ms_log_warn "MobileStudio's on-device JDK builds are server-JVM-only; a jvm.cfg without it will fail to start"
        return 1
    fi

    return 0
}

# ms_validate_native_symbol_risk: cannot actually resolve dynamic symbols
# from a shell script, but can sanity-check the *shape* of the install —
# a libjvm.so that is zero bytes, or dramatically smaller than any real
# JVM shared object (a real arm64 libjvm.so is reliably in the tens of
# megabytes), is a strong signal of an interrupted/corrupt build, which
# is the most common practical cause of the Bionic-symbol-mismatch class
# of failure this project has hit during JDK bring-up.
ms_validate_native_symbol_risk() {
    jdk_root="${'$'}1"
    server_libjvm="${'$'}(ms_join_path "${'$'}jdk_root" "lib/server/libjvm.so")"
    [ -f "${'$'}server_libjvm" ] || return 1

    size=0
    if command -v wc >/dev/null 2>&1; then
        size=${'$'}(wc -c < "${'$'}server_libjvm" 2>/dev/null)
        size=${'$'}{size:-0}
    fi

    # 1 MiB floor: comfortably below any real arm64 libjvm.so, but high
    # enough to catch a truncated/interrupted copy or extraction.
    if [ "${'$'}size" -lt 1048576 ] 2>/dev/null; then
        ms_log_warn "${'$'}server_libjvm is only ${'$'}(ms_human_bytes "${'$'}size") — this looks truncated, not a complete JVM build"
        return 1
    fi

    return 0
}

ms_validate_jdk() {
    jdk_root="${'$'}1"

    if [ "${'$'}MS_STRICT_JDK_VALIDATION" != "1" ]; then
        ms_log_debug "strict JDK validation disabled (jdk.strictValidation=0); skipping"
        return 0
    fi

    ms_log_debug "validating JDK at ${'$'}jdk_root"

    jvm_cfg_ok=1
    ms_validate_jvm_cfg "${'$'}jdk_root" || jvm_cfg_ok=0

    symbol_risk_ok=1
    ms_validate_native_symbol_risk "${'$'}jdk_root" || symbol_risk_ok=0

    if [ "${'$'}jvm_cfg_ok" = "1" ] && [ "${'$'}symbol_risk_ok" = "1" ]; then
        ms_log_ok "JDK validation passed for ${'$'}jdk_root"
        return 0
    fi

    ms_log_error "JDK validation found issues with ${'$'}jdk_root (see warnings above)."
    ms_log_error "This build will likely fail with a JVM startup error, not a Gradle/build-script error."
    ms_log_error "Run '${'$'}{MS_SCRIPT_NAME} --doctor' for a guided diagnosis, or pass --jdk to point at a different JDK,"
    ms_log_error "or re-run with jdk.strictValidation=false in mobilestudio.properties to bypass this check."
    return 1
}

# ==============================================================================
#   SECTION 10: DEVICE ARCHITECTURE DETECTION
# ==============================================================================
#
# MobileStudio's primary target is arm64-v8a; this launcher is written
# for that architecture, matching the bundled JDK and native StudioShell
# tool binaries (also arm64-v8a, self-built rather than run under proot).
# Detection here exists purely to fail with a clear, specific message on
# an unsupported device rather than proceeding and producing a confusing
# `Exec format error` from deep inside the native launcher.
# ==============================================================================

ms_detect_arch() {
    arch=""
    if command -v uname >/dev/null 2>&1; then
        arch="${'$'}(uname -m 2>/dev/null)"
    fi

    if [ -z "${'$'}arch" ] && command -v getprop >/dev/null 2>&1; then
        arch="${'$'}(getprop ro.product.cpu.abi 2>/dev/null)"
    fi

    printf '%s' "${'$'}arch"
}

ms_validate_arch() {
    detected="${'$'}(ms_detect_arch)"
    ms_log_debug "detected device architecture: ${'$'}{detected:-<unknown>}"

    case "${'$'}detected" in
        aarch64|arm64*|arm64-v8a)
            MS_DEVICE_ARCH="arm64"
            return 0
            ;;
        armv7*|armeabi*)
            MS_DEVICE_ARCH="arm32"
            ;;
        x86_64)
            MS_DEVICE_ARCH="x86_64"
            ;;
        i686|x86)
            MS_DEVICE_ARCH="x86"
            ;;
        *)
            MS_DEVICE_ARCH="unknown"
            ;;
    esac

    if [ "${'$'}MS_ALLOW_FALLBACK_ARCH" = "1" ]; then
        ms_log_warn "device architecture '${'$'}detected' is not arm64 — MobileStudio's native toolchain targets arm64-v8a"
        ms_log_warn "continuing anyway because arch.allowFallback=true; native components will likely fail to load"
        return 0
    fi

    ms_die "${'$'}MS_EXIT_UNSUPPORTED_ARCH" \
        "unsupported device architecture: '${'$'}{detected:-unknown}' (MobileStudio's toolchain requires arm64-v8a; set arch.allowFallback=true to override this check)"
}

# ==============================================================================
#   SECTION 11: NATIVE LIBRARY PATH RESOLUTION
# ==============================================================================
#
# The app's native library directory (containing libgradle_exec.so /
# "NativeExec", and optionally libexec_redirect.so) lives under a
# per-install, versioned APK path that Android rewrites on every
# reinstall or update — e.g.
#   /data/app/~~<hash>==/<pkg>-<hash>==/lib/arm64
#
# Hardcoding that path (as older revisions of this script did) silently
# breaks on the next app update. This section resolves it dynamically by
# scanning known parent directories for a subdirectory matching the
# expected pattern, with the previous hardcoded-style path kept only as
# a documented example of the *shape* being matched, never as a literal
# fallback value.
# ==============================================================================

MS_NATIVE_LIB_DIR=""

ms_resolve_native_lib_dir() {
    if [ -n "${'$'}MOBILESTUDIO_NATIVE_LIB_DIR" ] && [ -d "${'$'}MOBILESTUDIO_NATIVE_LIB_DIR" ]; then
        MS_NATIVE_LIB_DIR="${'$'}MOBILESTUDIO_NATIVE_LIB_DIR"
        ms_log_debug "using MOBILESTUDIO_NATIVE_LIB_DIR from environment: ${'$'}MS_NATIVE_LIB_DIR"
        return 0
    fi

    for app_parent in "/data/app" "/data/app/*"; do
        for pkg_dir in ${'$'}{app_parent}/*"${'$'}{MS_PACKAGE_ID}"*; do
            [ -d "${'$'}pkg_dir" ] || continue
            for lib_variant in "lib/arm64" "lib/arm64-v8a"; do
                candidate="${'$'}(ms_join_path "${'$'}pkg_dir" "${'$'}lib_variant")"
                if ms_path_exists_dir "${'$'}candidate"; then
                    MS_NATIVE_LIB_DIR="${'$'}candidate"
                    ms_log_debug "resolved native lib dir: ${'$'}MS_NATIVE_LIB_DIR"
                    return 0
                fi
            done
        done
    done

    # Some Android versions/OEMs expose native libs pre-extracted under
    # the app's own files directory instead of (or in addition to) the
    # versioned /data/app path.
    for root in "${'$'}MS_DATA_ROOT_DATA" "${'$'}MS_DATA_ROOT_USER"; do
        for lib_variant in "lib" "files/lib" "files/native"; do
            candidate="${'$'}(ms_join_path "${'$'}root" "${'$'}lib_variant")"
            if ms_path_exists_dir "${'$'}candidate"; then
                MS_NATIVE_LIB_DIR="${'$'}candidate"
                ms_log_debug "resolved native lib dir (app-data fallback): ${'$'}MS_NATIVE_LIB_DIR"
                return 0
            fi
        done
    done

    ms_log_warn "could not resolve MobileStudio's native library directory automatically"
    ms_log_warn "NativeExec (libgradle_exec.so) will not be located; set MOBILESTUDIO_NATIVE_LIB_DIR to override"
    return 1
}

# ==============================================================================
#   SECTION 12: NATIVE LAUNCHER (NativeExec / memfd_create workaround)
# ==============================================================================
#
# See the project header comment for *why* this exists: `noexec` on
# `/data` means the on-disk JDK shared objects cannot simply be
# `dlopen()`-ed from their real path. `libgradle_exec.so` ("NativeExec")
# works around this using `memfd_create()` + `fexecve()` to run the JVM
# from an anonymous, executable memory file descriptor instead.
#
# This section does not — and cannot, from shell — perform the memfd
# workaround itself; that is compiled C code. What it does is locate the
# native launcher library, confirm it is present and appears valid
# (non-trivial file size, correct ELF magic bytes if `od`/`xxd` is
# available), and export the handful of environment variables that the
# JVM wrapper script further down (SECTION 15) uses to decide whether the
# native launcher path or the ordinary `bin/java` fallback path is taken.
# ==============================================================================

MS_NATIVE_LAUNCHER=""
MS_EXEC_REDIRECT_LIB=""

ms_looks_like_elf() {
    file="${'$'}1"
    [ -f "${'$'}file" ] || return 1

    if command -v od >/dev/null 2>&1; then
        magic="${'$'}(od -An -tx1 -N4 "${'$'}file" 2>/dev/null | tr -d ' \n')"
        [ "${'$'}magic" = "7f454c46" ] && return 0
        return 1
    fi

    # Without `od`, fall back to a size-only sanity check — a real
    # shared object is never just a few bytes.
    size=0
    if command -v wc >/dev/null 2>&1; then
        size=${'$'}(wc -c < "${'$'}file" 2>/dev/null); size=${'$'}{size:-0}
    fi
    [ "${'$'}size" -gt 4096 ] 2>/dev/null
}

ms_resolve_native_launcher() {
    [ -n "${'$'}MS_NATIVE_LIB_DIR" ] || return 1

    candidate="${'$'}(ms_join_path "${'$'}MS_NATIVE_LIB_DIR" "libgradle_exec.so")"
    if ms_path_exists_regular "${'$'}candidate"; then
        if ms_looks_like_elf "${'$'}candidate"; then
            MS_NATIVE_LAUNCHER="${'$'}candidate"
            ms_log_debug "native launcher (NativeExec) found: ${'$'}MS_NATIVE_LAUNCHER"
        else
            ms_log_warn "found ${'$'}candidate but it does not look like a valid ELF shared object"
        fi
    else
        ms_log_debug "no libgradle_exec.so under ${'$'}MS_NATIVE_LIB_DIR — will fall back to bin/java directly"
    fi

    redirect_candidate="${'$'}(ms_join_path "${'$'}MS_NATIVE_LIB_DIR" "libexec_redirect.so")"
    if ms_path_exists_regular "${'$'}redirect_candidate" && ms_looks_like_elf "${'$'}redirect_candidate"; then
        MS_EXEC_REDIRECT_LIB="${'$'}redirect_candidate"
        ms_log_debug "exec-redirect helper found: ${'$'}MS_EXEC_REDIRECT_LIB"
    fi

    [ -n "${'$'}MS_NATIVE_LAUNCHER" ]
}

ms_export_native_launcher_env() {
    if [ -n "${'$'}MS_NATIVE_LAUNCHER" ]; then
        export MOBILESTUDIO_JAVA_LAUNCHER="${'$'}MS_NATIVE_LAUNCHER"
    fi
    if [ -n "${'$'}MS_EXEC_REDIRECT_LIB" ]; then
        export LD_PRELOAD="${'$'}MS_EXEC_REDIRECT_LIB"
    fi
    # Native aapt2 daemon proxy: lets AAPT2_SHELL_BOOTSTRAP (embedded in AGP)
    # exec through /system/bin/linker64 instead of the dalvikvm/app_process
    # fallbacks, which abort on modern Android (system_class_loader).
    if [ -n "${'$'}MS_NATIVE_LIB_DIR" ]; then
        _aapt2_proxy="$(ms_join_path "${'$'}MS_NATIVE_LIB_DIR" "libaapt2_daemon_proxy.so")"
        if ms_path_exists_regular "${'$'}_aapt2_proxy" && ms_looks_like_elf "${'$'}_aapt2_proxy"; then
            export MOBILESTUDIO_AAPT2_DAEMON_PROXY="${'$'}_aapt2_proxy"
            ms_log_debug "aapt2 daemon proxy: ${'$'}MOBILESTUDIO_AAPT2_DAEMON_PROXY"
        fi
        unset _aapt2_proxy
    fi
}

ms_build_ld_library_path() {
    jdk_root="${'$'}1"
    parts="${'$'}{jdk_root}/lib:${'$'}{jdk_root}/lib/server:${'$'}{jdk_root}/lib/jli"
    if [ -n "${'$'}MS_NATIVE_LIB_DIR" ]; then
        parts="${'$'}{parts}:${'$'}{MS_NATIVE_LIB_DIR}"
    fi
    parts="${'$'}{parts}:/system/lib64:/system/lib"
    if [ -n "${'$'}LD_LIBRARY_PATH" ]; then
        parts="${'$'}{parts}:${'$'}{LD_LIBRARY_PATH}"
    fi
    printf '%s' "${'$'}parts"
}

# ==============================================================================
#   SECTION 13: APPLICATION HOME / DATA DIRECTORY RESOLUTION
# ==============================================================================

MS_APP_HOME_CANDIDATES_RESOLVED=0

ms_resolve_app_home() {
    APP_HOME="${'$'}(cd "${'$'}(dirname "${'$'}0")" >/dev/null 2>&1 && pwd)"
    if [ -z "${'$'}APP_HOME" ]; then
        ms_die "${'$'}MS_EXIT_GENERIC_ERROR" "could not resolve the project directory from \${'$'}0='${'$'}0'"
    fi
}

ms_resolve_home_dir() {
    if ms_path_exists_dir "${'$'}MS_DATA_ROOT_DATA"; then
        HOME="${'$'}MS_DATA_ROOT_DATA"
    elif ms_path_exists_dir "${'$'}MS_DATA_ROOT_USER"; then
        HOME="${'$'}MS_DATA_ROOT_USER"
    elif ms_path_exists_dir "${'$'}MS_DATA_ROOT_DATA_ALT"; then
        HOME="${'$'}MS_DATA_ROOT_DATA_ALT"
    elif ms_path_exists_dir "${'$'}MS_DATA_ROOT_USER_ALT"; then
        HOME="${'$'}MS_DATA_ROOT_USER_ALT"
    else
        HOME="${'$'}MS_DATA_ROOT_DATA"
        ms_log_warn "none of the expected app-data directories exist yet; defaulting HOME=${'$'}HOME"
    fi

    export HOME
    GRADLE_USER_HOME="${'$'}{GRADLE_USER_HOME:-${'$'}(ms_join_path "${'$'}HOME" ".gradle")}"
    TMPDIR="${'$'}{TMPDIR:-${'$'}(ms_join_path "${'$'}HOME" ".tmp")}"
    export GRADLE_USER_HOME
    export TMPDIR

    ms_mkdir_p "${'$'}GRADLE_USER_HOME"
    ms_mkdir_p "${'$'}TMPDIR"

    MS_LOG_DIR="${'$'}(ms_join_path "${'$'}HOME" ".mobilestudio/logs")"
    ms_mkdir_p "${'$'}MS_LOG_DIR"
    MS_LOG_FILE="${'$'}(ms_join_path "${'$'}MS_LOG_DIR" "gradlew.log")"
}

ms_maybe_clean_tmp() {
    [ "${'$'}MS_CLEAN_TMP_ON_START" = "1" ] || return 0
    [ -n "${'$'}TMPDIR" ] || return 0
    [ -d "${'$'}TMPDIR" ] || return 0

    ms_log_info "cleaning ${'$'}TMPDIR (cleanTmpOnStart=true)"
    # Remove contents, not the directory itself, so permissions set by
    # the app on TMPDIR's inode are preserved.
    find "${'$'}TMPDIR" -mindepth 1 -delete 2>/dev/null
}

# ==============================================================================
#   SECTION 14: GRADLE WRAPPER JAR / CLASSPATH
# ==============================================================================

MS_WRAPPER_JAR=""
MS_WRAPPER_SHARED_JAR=""
MS_WRAPPER_CLI_JAR=""
MS_WRAPPER_CP=""

# gradle-cli-<version>.jar is NOT part of the project-local
# `gradle/wrapper/` directory the way gradle-wrapper.jar is — it ships
# inside MobileStudio's installed Gradle *distribution* under the app's
# own toolchain data, e.g.:
#
#   ${'$'}HOME/toolchain/gradle/gradle-9.7.0/lib/gradle-cli-9.7.0.jar
#
# (confirmed on-device; the previous revision of this script incorrectly
# assumed it lived alongside gradle-wrapper.jar under
# `${'$'}APP_HOME/gradle/wrapper/`, which is where the *wrapper* jar lives but
# never where the CLI helper jar does — that mistake made the CLI jar
# permanently unfindable even on an installation where it was present
# and completely intact).
#
# The Gradle version segment (`gradle-9.7.0`) is variable, so this globs
# for it under every known app-data root instead of hardcoding one
# version or one data-root variant, using the same
# data-root-variant list SECTION 2 already maintains for the app's own
# package id (`${'$'}MS_DATA_ROOT_DATA`, `${'$'}MS_DATA_ROOT_USER`, and their
# `_ALT` counterparts) rather than assuming `${'$'}HOME` alone was resolved to
# the same root the toolchain installer used.
ms_build_gradle_cli_candidate_list() {
    list=""
    for root in "${'$'}MS_DATA_ROOT_DATA" "${'$'}MS_DATA_ROOT_USER" "${'$'}MS_DATA_ROOT_DATA_ALT" "${'$'}MS_DATA_ROOT_USER_ALT"; do
        for candidate in "${'$'}{root}"/files/toolchain/gradle/gradle-*/lib/gradle-cli-*.jar; do
            [ -f "${'$'}candidate" ] || continue
            list="${'$'}list
${'$'}candidate"
        done
    done
    # Fallback for a project that vendors its own copy alongside the
    # wrapper jar (not how MobileStudio's own installer lays it out, but
    # kept so a manually-assembled project still works).
    for candidate in "${'$'}{APP_HOME}"/gradle/wrapper/gradle-cli-*.jar; do
        [ -f "${'$'}candidate" ] || continue
        list="${'$'}list
${'$'}candidate"
    done
    printf '%s' "${'$'}list"
}

ms_resolve_wrapper_jars() {
    MS_WRAPPER_JAR="${'$'}(ms_join_path "${'$'}APP_HOME" "gradle/wrapper/gradle-wrapper.jar")"
    MS_WRAPPER_SHARED_JAR="${'$'}(ms_join_path "${'$'}APP_HOME" "gradle/wrapper/gradle-wrapper-shared.jar")"

    cli_candidates="${'$'}(ms_build_gradle_cli_candidate_list)"
    old_ifs="${'$'}IFS"; IFS='
'
    for candidate in ${'$'}cli_candidates; do
        [ -n "${'$'}candidate" ] || continue
        if ms_path_exists_regular "${'$'}candidate"; then
            MS_WRAPPER_CLI_JAR="${'$'}candidate"
            break
        fi
    done
    IFS="${'$'}old_ifs"

    if [ -n "${'$'}MS_WRAPPER_CLI_JAR" ]; then
        ms_log_debug "gradle-cli jar resolved: ${'$'}MS_WRAPPER_CLI_JAR"
    else
        ms_log_debug "no gradle-cli-*.jar found under any known toolchain/gradle install or project wrapper dir (this is only fatal if Gradle itself requires it)"
    fi

    if [ ! -f "${'$'}MS_WRAPPER_JAR" ]; then
        ms_die "${'$'}MS_EXIT_WRAPPER_JAR_MISSING" \
            "gradle-wrapper.jar missing: ${'$'}MS_WRAPPER_JAR (run the project's setup step, or restore it from version control)"
    fi

    MS_WRAPPER_CP="${'$'}MS_WRAPPER_JAR"
    if [ -f "${'$'}MS_WRAPPER_SHARED_JAR" ]; then
        MS_WRAPPER_CP="${'$'}{MS_WRAPPER_CP}:${'$'}{MS_WRAPPER_SHARED_JAR}"
    fi
    if [ -n "${'$'}MS_WRAPPER_CLI_JAR" ] && [ -f "${'$'}MS_WRAPPER_CLI_JAR" ]; then
        MS_WRAPPER_CP="${'$'}{MS_WRAPPER_CP}:${'$'}{MS_WRAPPER_CLI_JAR}"
    fi

    ms_log_debug "wrapper classpath: ${'$'}MS_WRAPPER_CP"
}

# ==============================================================================
#   SECTION 15: GRADLE DAEMON PROPERTY RESOLUTION
# ==============================================================================
#
# Precedence (matches Gradle's own documented behavior, so this script's
# choice of daemon on/off never surprises someone who also knows plain
# Gradle): an explicit --daemon/--no-daemon flag on this invocation wins
# outright; otherwise an explicit `org.gradle.daemon=` in the *project's*
# gradle.properties wins over the user-global one; otherwise the
# MS_USE_DAEMON default (itself overridable via mobilestudio.properties)
# applies.
# ==============================================================================

MS_GRADLE_DAEMON_PROP=""

ms_resolve_daemon_property() {
    if [ -n "${'$'}MS_DAEMON_FLAG_EXPLICIT" ]; then
        MS_GRADLE_DAEMON_PROP="-Dorg.gradle.daemon=${'$'}([ "${'$'}MS_USE_DAEMON" = "1" ] && echo true || echo false)"
        ms_log_debug "daemon setting from explicit CLI flag: ${'$'}MS_GRADLE_DAEMON_PROP"
        return 0
    fi

    project_props="${'$'}(ms_join_path "${'$'}APP_HOME" "gradle.properties")"
    user_props="${'$'}(ms_join_path "${'$'}GRADLE_USER_HOME" "gradle.properties")"

    for props_file in "${'$'}project_props" "${'$'}user_props"; do
        [ -f "${'$'}props_file" ] || continue
        val="${'$'}(ms_get_prop "${'$'}props_file" "org.gradle.daemon")"
        if [ -n "${'$'}val" ]; then
            if ms_is_falsy "${'$'}val"; then
                MS_GRADLE_DAEMON_PROP="-Dorg.gradle.daemon=false"
            else
                MS_GRADLE_DAEMON_PROP="-Dorg.gradle.daemon=true"
            fi
            ms_log_debug "daemon setting from ${'$'}props_file: ${'$'}MS_GRADLE_DAEMON_PROP"
            return 0
        fi
    done

    MS_GRADLE_DAEMON_PROP="-Dorg.gradle.daemon=${'$'}([ "${'$'}MS_USE_DAEMON" = "1" ] && echo true || echo false)"
    ms_log_debug "daemon setting from launcher default: ${'$'}MS_GRADLE_DAEMON_PROP"
}

# ==============================================================================
#   SECTION 16: ADVISORY LOCK (CONCURRENT INVOCATION GUARD)
# ==============================================================================
#
# MobileStudio can trigger a Gradle invocation from more than one UI
# surface in quick succession (e.g. an auto-sync on file save firing
# while the user also taps "Run"). Two `bin/java` processes racing to
# start a Gradle daemon against the same GRADLE_USER_HOME is a known
# source of confusing, hard-to-reproduce failures — lock contention on
# the daemon registry, or two daemons started when only one was wanted.
#
# `flock` is not guaranteed to exist on a minimal Android `sh`/toybox
# environment, so this implements a simple, portable directory-based
# lock: `mkdir` is atomic on every POSIX filesystem this could plausibly
# run on, which is the standard portable substitute for `flock` in shell
# scripts that need to run on minimal/BusyBox-like systems.
# ==============================================================================

MS_LOCK_DIR=""
MS_LOCK_ACQUIRED=0

ms_lock_path() {
    ms_join_path "${'$'}GRADLE_USER_HOME" ".mobilestudio-gradlew.lock"
}

ms_acquire_lock() {
    MS_LOCK_DIR="${'$'}(ms_lock_path)"
    waited=0

    while ! mkdir "${'$'}MS_LOCK_DIR" 2>/dev/null; do
        if [ "${'$'}waited" -eq 0 ]; then
            ms_log_info "waiting for another MobileStudio Gradle invocation to finish (lock: ${'$'}MS_LOCK_DIR)"
        fi

        # Stale-lock recovery: if the lock directory's recorded PID is no
        # longer running, assume a previous invocation crashed without
        # cleaning up and reclaim the lock rather than waiting out the
        # full timeout for a process that no longer exists.
        pid_file="${'$'}(ms_join_path "${'$'}MS_LOCK_DIR" "pid")"
        if [ -f "${'$'}pid_file" ]; then
            held_pid="${'$'}(cat "${'$'}pid_file" 2>/dev/null)"
            if [ -n "${'$'}held_pid" ] && ! kill -0 "${'$'}held_pid" 2>/dev/null; then
                ms_log_warn "reclaiming stale lock held by dead pid ${'$'}held_pid"
                rm -rf "${'$'}MS_LOCK_DIR" 2>/dev/null
                continue
            fi
        fi

        sleep 1
        waited=${'$'}((waited + 1))
        if [ "${'$'}waited" -ge "${'$'}MS_DAEMON_LOCK_TIMEOUT_SEC" ]; then
            ms_die "${'$'}MS_EXIT_LOCK_TIMEOUT" \
                "timed out after ${'$'}{MS_DAEMON_LOCK_TIMEOUT_SEC}s waiting for lock ${'$'}MS_LOCK_DIR (another build may be stuck; delete this directory manually if you're sure nothing is running)"
        fi
    done

    echo ${'$'}${'$'} > "${'$'}(ms_join_path "${'$'}MS_LOCK_DIR" "pid")" 2>/dev/null
    MS_LOCK_ACQUIRED=1
    ms_log_debug "lock acquired: ${'$'}MS_LOCK_DIR"
}

ms_release_lock() {
    [ "${'$'}MS_LOCK_ACQUIRED" = "1" ] || return 0
    [ -n "${'$'}MS_LOCK_DIR" ] || return 0
    rm -rf "${'$'}MS_LOCK_DIR" 2>/dev/null
    MS_LOCK_ACQUIRED=0
    ms_log_debug "lock released: ${'$'}MS_LOCK_DIR"
}

# ==============================================================================
#   SECTION 17: CLEANUP / SIGNAL HANDLING
# ==============================================================================
#
# Ensures the advisory lock is always released — on normal exit, on an
# error path via ms_die, and on the user cancelling the build from the
# MobileStudio UI (which delivers SIGINT/SIGTERM to this process). A
# leaked lock directory would otherwise block every subsequent build
# until the user found and deleted it manually, which is a much worse
# failure mode than the race this lock exists to prevent.
# ==============================================================================

ms_cleanup() {
    exit_code=${'$'}?
    ms_release_lock
    return ${'$'}exit_code
}

ms_install_traps() {
    trap ms_cleanup EXIT
    trap 'ms_log_warn "interrupted"; exit ${'$'}MS_EXIT_USER_ABORT' INT TERM
}

# ==============================================================================
#   SECTION 18: DOCTOR MODE (GUIDED DIAGNOSTICS)
# ==============================================================================
#
# `--doctor` walks through every failure signature this project has
# actually hit during on-device JVM bring-up, checks what's currently on
# disk against each one, and reports a specific verdict instead of a
# generic pass/fail. It does not invoke Gradle. It is safe to run
# repeatedly and touches nothing.
#
# The checks below are deliberately ordered from "cheapest and most
# common" to "rarest and most invasive", so a user watching the output
# scroll on a phone screen sees the most likely explanation first.
# ==============================================================================

ms_doctor_section() {
    printf '\n%s=== %s ===%s\n' "${'$'}MS_COLOR_BLUE" "${'$'}1" "${'$'}MS_COLOR_RESET"
}

ms_doctor_pass() {
    printf '  %s[PASS]%s %s\n' "${'$'}MS_COLOR_GREEN" "${'$'}MS_COLOR_RESET" "${'$'}1"
}

ms_doctor_fail() {
    printf '  %s[FAIL]%s %s\n' "${'$'}MS_COLOR_RED" "${'$'}MS_COLOR_RESET" "${'$'}1"
}

ms_doctor_warn() {
    printf '  %s[WARN]%s %s\n' "${'$'}MS_COLOR_YELLOW" "${'$'}MS_COLOR_RESET" "${'$'}1"
}

ms_doctor_info() {
    printf '  %s[INFO]%s %s\n' "${'$'}MS_COLOR_GRAY" "${'$'}MS_COLOR_RESET" "${'$'}1"
}

ms_doctor_check_arch() {
    ms_doctor_section "Device architecture"
    detected="${'$'}(ms_detect_arch)"
    ms_doctor_info "uname -m / ro.product.cpu.abi: ${'$'}{detected:-<could not determine>}"
    case "${'$'}detected" in
        aarch64|arm64*|arm64-v8a)
            ms_doctor_pass "arm64 device — matches MobileStudio's native toolchain target"
            ;;
        *)
            ms_doctor_fail "not arm64 — MobileStudio's JDK, StudioShell binaries, and native launcher are all arm64-v8a only"
            ;;
    esac
}

ms_doctor_check_noexec() {
    ms_doctor_section "noexec mount check"
    test_dir="${'$'}(ms_join_path "${'$'}HOME" ".mobilestudio/doctor-noexec-test")"
    ms_mkdir_p "${'$'}test_dir"
    test_script="${'$'}(ms_join_path "${'$'}test_dir" "probe.sh")"

    if printf '#!/system/bin/sh\nexit 0\n' > "${'$'}test_script" 2>/dev/null && chmod +x "${'$'}test_script" 2>/dev/null; then
        if "${'$'}test_script" 2>/dev/null; then
            ms_doctor_pass "direct execution from ${'$'}HOME succeeded — this partition is not (or is no longer) noexec"
        else
            ms_doctor_warn "direct execution from ${'$'}HOME failed — consistent with a noexec mount"
            ms_doctor_info "this is expected on many devices; it is exactly why NativeExec's memfd_create()/fexecve() workaround exists"
        fi
    else
        ms_doctor_warn "could not even write/chmod a test script under ${'$'}HOME — check storage permissions"
    fi
    rm -rf "${'$'}test_dir" 2>/dev/null
}

ms_doctor_check_jdk() {
    ms_doctor_section "JDK toolchain"

    if [ -z "${'$'}JAVA_HOME" ]; then
        ms_doctor_fail "JAVA_HOME is not resolved — no JDK was found in any known MobileStudio location"
        ms_doctor_info "re-run the toolchain installer from MobileStudio's setup wizard, or pass --jdk <path>"
        return 0
    fi

    ms_doctor_pass "JAVA_HOME resolved: ${'$'}JAVA_HOME"

    version="${'$'}(ms_probe_jdk_release_version "${'$'}JAVA_HOME")"
    ms_doctor_info "JDK reports JAVA_VERSION=${'$'}version"
    case "${'$'}version" in
        "17"*)
            ms_doctor_pass "JDK 17 — currently the recommended/supported toolchain for this project"
            ;;
        "21"*)
            ms_doctor_warn "JDK 21 — this project's JDK 21 support has known on-device issues; JDK 17 is currently recommended"
            ms_doctor_info "if builds fail with a dlopen/symbol-resolution error below, switching to a JDK 17 install is the most likely fix"
            ;;
        *)
            ms_doctor_warn "could not determine JDK version from release file"
            ;;
    esac

    jvm_cfg="${'$'}(ms_join_path "${'$'}JAVA_HOME" "lib/jvm.cfg")"
    if [ -f "${'$'}jvm_cfg" ] && [ -s "${'$'}jvm_cfg" ]; then
        ms_doctor_pass "lib/jvm.cfg present and non-empty"
        if ms_validate_jvm_cfg "${'$'}JAVA_HOME" >/dev/null 2>&1; then
            ms_doctor_pass "jvm.cfg declares a -server VM backed by an actual lib/server/libjvm.so"
        else
            ms_doctor_fail "jvm.cfg / lib/server/libjvm.so mismatch — this reproduces 'no known VMs. (check for corrupt jvm.cfg file)'"
            ms_doctor_info "this usually means the JDK image was only partially copied/built — rebuild or reinstall the toolchain"
        fi
    else
        ms_doctor_fail "lib/jvm.cfg missing or empty at ${'$'}jvm_cfg"
    fi

    if ms_validate_native_symbol_risk "${'$'}JAVA_HOME" >/dev/null 2>&1; then
        ms_doctor_pass "lib/server/libjvm.so size looks like a complete build"
    else
        ms_doctor_fail "lib/server/libjvm.so is missing or suspiciously small — likely an incomplete/interrupted JDK build"
    fi

    ms_doctor_info "NOTE: this script cannot verify Bionic ABI/symbol compatibility (e.g. the historical"
    ms_doctor_info "_ZN6Thread12_thr_currentE dlopen failure) from a shell — that requires actually attempting"
    ms_doctor_info "JVM startup. If jvm.cfg and libjvm.so both pass above but the JVM still fails to start with"
    ms_doctor_info "a dlopen/symbol error, the JDK was very likely built against a different Android API level"
    ms_doctor_info "(Bionic version) than this device is running, and needs to be rebuilt targeting this device's"
    ms_doctor_info "actual API level, or run under a compatibility layer (e.g. proot/Ubuntu) instead."
}

ms_doctor_check_native_launcher() {
    ms_doctor_section "Native launcher (NativeExec)"

    if ms_resolve_native_lib_dir; then
        ms_doctor_pass "native library directory resolved: ${'$'}MS_NATIVE_LIB_DIR"
    else
        ms_doctor_fail "could not resolve MobileStudio's native library directory"
        return 0
    fi

    if ms_resolve_native_launcher; then
        ms_doctor_pass "libgradle_exec.so (NativeExec) found and looks like a valid ELF: ${'$'}MS_NATIVE_LAUNCHER"
    else
        ms_doctor_warn "libgradle_exec.so not found or invalid — will fall back to executing bin/java directly"
        ms_doctor_info "a direct bin/java execution can still work if this partition is not noexec (see the noexec check above),"
        ms_doctor_info "but will fail with a permission/exec-format error on a genuinely noexec-mounted /data"
    fi
}

ms_doctor_check_wrapper() {
    ms_doctor_section "Gradle wrapper files"
    wrapper_jar="${'$'}(ms_join_path "${'$'}APP_HOME" "gradle/wrapper/gradle-wrapper.jar")"
    if [ -f "${'$'}wrapper_jar" ]; then
        ms_doctor_pass "gradle-wrapper.jar present: ${'$'}wrapper_jar"
    else
        ms_doctor_fail "gradle-wrapper.jar missing: ${'$'}wrapper_jar"
    fi

    # gradle-cli-<version>.jar lives inside the installed Gradle
    # *distribution* under the toolchain data dir, not next to
    # gradle-wrapper.jar — see the long comment in ms_build_gradle_cli_candidate_list
    # (SECTION 14) for why that distinction matters and how a previous
    # revision of this script got it wrong.
    cli_candidates="${'$'}(ms_build_gradle_cli_candidate_list 2>/dev/null)"
    found_cli=""
    old_ifs="${'$'}IFS"; IFS='
'
    for c in ${'$'}cli_candidates; do
        [ -n "${'$'}c" ] || continue
        [ -f "${'$'}c" ] && { found_cli="${'$'}c"; break; }
    done
    IFS="${'$'}old_ifs"

    if [ -n "${'$'}found_cli" ]; then
        ms_doctor_pass "gradle-cli-*.jar present: ${'$'}found_cli"
    else
        ms_doctor_warn "no gradle-cli-*.jar found under \${'$'}HOME/toolchain/gradle/gradle-*/lib/ on any known data root"
        ms_doctor_info "expected layout: \${'$'}HOME/toolchain/gradle/gradle-<version>/lib/gradle-cli-<version>.jar"
        ms_doctor_info "if you know it's installed, check for a Gradle version mismatch between the directory name and the jar filename"
    fi
}

ms_doctor_check_storage() {
    ms_doctor_section "Storage"
    if command -v df >/dev/null 2>&1; then
        avail_line="${'$'}(df -k "${'$'}HOME" 2>/dev/null | tail -n 1)"
        ms_doctor_info "df -k ${'$'}HOME => ${'$'}avail_line"
    else
        ms_doctor_info "df not available; skipping free-space check"
    fi
}

ms_doctor_summary() {
    ms_doctor_section "Summary"
    cat <<'MS_DOCTOR_SUMMARY_EOF'
  This is a guided check, not a guarantee — a JVM startup failure caused
  by a Bionic ABI/symbol mismatch between the on-device JDK build and
  this specific device cannot be fully confirmed without actually
  attempting to launch the JVM (which running a real build will do).

  If every check above passes and a build still fails at JVM startup,
  capture the exact error text and the JAVA_VERSION reported above, and
  report it via GitHub Issues or limsemin17@gmail.com — the project
  tracks known-failing (device Bionic version, JDK build target) pairs
  to prioritize which toolchain rebuilds are needed next.
MS_DOCTOR_SUMMARY_EOF
}

ms_run_doctor() {
    printf '%s v%s — diagnostics\n' "${'$'}MS_SCRIPT_NAME" "${'$'}MS_SCRIPT_VERSION"
    ms_doctor_check_arch
    ms_doctor_check_noexec
    ms_doctor_check_jdk
    ms_doctor_check_native_launcher
    ms_doctor_check_wrapper
    ms_doctor_check_storage
    ms_doctor_summary
}

# ==============================================================================
#   SECTION 19: JVM TUNING OPTIONS
# ==============================================================================
#
# Defaults are conservative (256MB heap, tiered compilation disabled) to
# suit a phone/tablet running Gradle alongside the rest of the Android
# system and the MobileStudio app UI itself, not a desktop CI runner.
# Both are configurable (SECTION 3 defaults, overridable via
# mobilestudio.properties) for devices with more headroom, or projects
# whose build needs more heap than the default allows.
# ==============================================================================

ms_build_jvm_opts() {
    opts="-Xmx${'$'}{MS_MAX_HEAP_MB}m"
    opts="${'$'}opts -XX:MaxMetaspaceSize=${'$'}{MS_METASPACE_MB}m"

    if [ "${'$'}MS_TIERED_COMPILATION" != "1" ]; then
        opts="${'$'}opts -XX:-TieredCompilation"
    fi

    printf '%s' "${'$'}opts"
}

# ==============================================================================
#   SECTION 20: AAPT2 OVERRIDE
# ==============================================================================
#
# AGP's bundled aapt2 binary is built for desktop host architectures and
# does not run on-device; MobileStudio ships (or lets the user point at)
# an arm64-compatible aapt2 build, wired in via the same Gradle project
# property AGP already supports for exactly this purpose, so no
# build.gradle changes are required in the user's project.
# ==============================================================================

ms_resolve_aapt2_override() {
    MS_AAPT2_ARG=""
    # Default the override to the arm64 aapt2 ToolchainManager installs, so a
    # plain `gradlew` run (no IDE env exported) still avoids AGP's desktop-host
    # maven aapt2 binary, which cannot run on-device.
    if [ -z "${'$'}MOBILESTUDIO_AAPT2_OVERRIDE" ]; then
        for _aapt2_cand in \
            "${'$'}MS_DATA_ROOT_DATA/files/toolchain/overrides/aapt2" \
            "/data/user/0/${'$'}MS_PACKAGE_ID/files/toolchain/overrides/aapt2" \
            "${'$'}HOME/files/toolchain/overrides/aapt2"
        do
            if ms_path_exists_executable "${'$'}_aapt2_cand"; then
                MOBILESTUDIO_AAPT2_OVERRIDE="${'$'}_aapt2_cand"
                ms_log_debug "aapt2 override defaulted to ${'$'}MOBILESTUDIO_AAPT2_OVERRIDE"
                break
            fi
        done
        unset _aapt2_cand
    fi
    if [ -n "${'$'}MOBILESTUDIO_AAPT2_OVERRIDE" ]; then
        if ms_path_exists_executable "${'$'}MOBILESTUDIO_AAPT2_OVERRIDE"; then
            MS_AAPT2_ARG="-Dorg.gradle.project.android.aapt2FromMavenOverride=${'$'}MOBILESTUDIO_AAPT2_OVERRIDE"
            # Also export for AAPT2_SHELL_BOOTSTRAP / the native proxy fallbacks.
            export MOBILESTUDIO_AAPT2_EXEC="${'$'}MOBILESTUDIO_AAPT2_OVERRIDE"
            ms_log_debug "aapt2 override: ${'$'}MOBILESTUDIO_AAPT2_OVERRIDE"
        else
            ms_log_warn "MOBILESTUDIO_AAPT2_OVERRIDE='${'$'}MOBILESTUDIO_AAPT2_OVERRIDE' is not executable; ignoring"
        fi
    fi
}

# ==============================================================================
#   SECTION 21: GRADLE ARGUMENT ASSEMBLY
# ==============================================================================
#
# Reconstitutes the forwarded Gradle arguments as real positional
# parameters from the quote-escaped accumulator built in SECTION 6
# (`ms_append_gradle_arg`). This is the point where `eval` is actually
# required to correctly un-escape and re-split on the original argument
# boundaries rather than on whitespace — see the SECTION 6 comment for
# why a plain unquoted expansion is not equivalent to this.
# ==============================================================================

ms_apply_gradle_args_positional() {
    eval set -- ${'$'}MS_GRADLE_ARGS_QUOTED
}

# ==============================================================================
#   SECTION 22: INVOCATION (WITH RETRY)
# ==============================================================================
#
# A retry loop exists for exactly one reason observed in practice on this
# project: a transient failure to acquire the native `noexec` workaround
# (memfd_create returning EMFILE under memory pressure, or a brief
# SELinux denial race during app startup) that succeeds on a second
# attempt without any change to the build itself. MS_RETRY_COUNT defaults
# to 1 (i.e. no retry) precisely because retrying a *real* build failure
# (a compile error, a failing test) is never useful and would only slow
# down the feedback loop; users working on toolchain bring-up who expect
# transient native-launch flakiness can raise it explicitly.
# ==============================================================================

ms_invoke_gradle_once() {
    java_bin="${'$'}(ms_join_path "${'$'}JAVA_HOME" "bin/java")"
    ld_library_path="${'$'}(ms_build_ld_library_path "${'$'}JAVA_HOME")"
    jvm_opts="${'$'}(ms_build_jvm_opts)"

    offline_arg=""
    if [ "${'$'}MS_GRADLE_OFFLINE" = "1" ]; then
        offline_arg="--offline"
    fi

    # Reconstruct the forwarded Gradle arguments as real positional
    # parameters (${'$'}1, ${'$'}2, ... / "${'$'}@") — see SECTION 21. From this point
    # on, "${'$'}@" holds exactly the arguments the user passed to Gradle,
    # each still a single argument even if it contains spaces or quotes.
    ms_apply_gradle_args_positional

    ms_log_debug "exec: ${'$'}java_bin ${'$'}jvm_opts ${'$'}MS_GRADLE_DAEMON_PROP ${'$'}MS_AAPT2_ARG -classpath ${'$'}MS_WRAPPER_CP org.gradle.wrapper.GradleWrapperMain ${'$'}offline_arg ${'$'}MS_GRADLE_ARGS_QUOTED"

    LD_LIBRARY_PATH="${'$'}ld_library_path" \
        "${'$'}java_bin" \
        ${'$'}jvm_opts \
        "${'$'}MS_GRADLE_DAEMON_PROP" \
        ${'$'}MS_AAPT2_ARG \
        "-Djava.io.tmpdir=${'$'}TMPDIR" \
        "-Duser.home=${'$'}HOME" \
        "-Duser.dir=${'$'}APP_HOME" \
        -classpath "${'$'}MS_WRAPPER_CP" \
        org.gradle.wrapper.GradleWrapperMain \
        ${'$'}offline_arg \
        "${'$'}@"
}

ms_invoke_gradle_with_retry() {
    attempt=1
    max_attempts="${'$'}MS_RETRY_COUNT"
    case "${'$'}max_attempts" in
        ''|*[!0-9]*) max_attempts=1 ;;
    esac
    [ "${'$'}max_attempts" -lt 1 ] 2>/dev/null && max_attempts=1

    while :; do
        ms_export_native_launcher_env
        ms_invoke_gradle_once
        status=${'$'}?

        if [ "${'$'}status" -eq 0 ]; then
            return 0
        fi

        if [ "${'$'}attempt" -ge "${'$'}max_attempts" ]; then
            return "${'$'}status"
        fi

        ms_log_warn "Gradle invocation failed (exit ${'$'}status); retrying (attempt ${'$'}((attempt + 1))/${'$'}max_attempts) after ${'$'}{MS_RETRY_DELAY_SEC}s"
        sleep "${'$'}MS_RETRY_DELAY_SEC" 2>/dev/null
        attempt=${'$'}((attempt + 1))
    done
}

# ==============================================================================
#   SECTION 23: MAIN
# ==============================================================================
#
# Execution order matters here and is deliberately linear and readable
# top-to-bottom, rather than calling into a single opaque `ms_run()`
# dispatcher, so that inserting a diagnostic `ms_log_debug` between any
# two steps while troubleshooting this script itself is a one-line edit.
# ==============================================================================

MS_DAEMON_FLAG_EXPLICIT=""
MS_SHOW_CONFIG=0
MS_DEVICE_ARCH=""

main() {
    # Detect whether --daemon/--no-daemon was passed explicitly, ahead of
    # ms_parse_args mutating MS_USE_DAEMON, so SECTION 15's precedence
    # logic can tell "explicitly requested" apart from "default value".
    for arg in "${'$'}@"; do
        case "${'$'}arg" in
            --daemon|--no-daemon) MS_DAEMON_FLAG_EXPLICIT=1 ;;
        esac
    done

    ms_resolve_app_home
    ms_resolve_home_dir
    ms_install_traps

    ms_load_config_file "${'$'}(ms_join_path "${'$'}GRADLE_USER_HOME" "mobilestudio.properties")"
    ms_load_config_file "${'$'}(ms_join_path "${'$'}APP_HOME" "mobilestudio.properties")"

    ms_parse_args "${'$'}@"

    if [ "${'$'}MS_SHOW_CONFIG" = "1" ]; then
        ms_detect_java_home 2>/dev/null || true
        ms_print_effective_config
        exit ${'$'}MS_EXIT_OK
    fi

    ms_maybe_clean_tmp
    ms_validate_arch

    if [ "${'$'}MS_DOCTOR_MODE" = "1" ]; then
        ms_detect_java_home 2>/dev/null || true
        ms_run_doctor
        exit ${'$'}MS_EXIT_OK
    fi

    ms_detect_java_home
    ms_validate_jdk "${'$'}JAVA_HOME" || ms_die "${'$'}MS_EXIT_INVALID_JAVA_HOME" "JDK validation failed; see above (or pass jdk.strictValidation=false to bypass)"

    ms_resolve_native_lib_dir
    ms_resolve_native_launcher

    ms_resolve_wrapper_jars
    ms_resolve_daemon_property
    ms_resolve_aapt2_override

    ms_acquire_lock
    ms_invoke_gradle_with_retry
    status=${'$'}?
    ms_release_lock

    exit "${'$'}status"
}

main "${'$'}@"
        """.trimIndent()

        val gradlewFile = File(root, "gradlew")
        writeIfChanged(gradlewFile, gradlewContent)
        gradlewFile.setExecutable(true, false)

    // gradle-wrapper.properties (wrapper 자체파일 삭제 후 경고 무시)
        writeIfChanged(wrapperDir.resolve("gradle-wrapper.properties"),
            "distributionBase=GRADLE_USER_HOME\n" +
            "distributionPath=wrapper/dists\n" +
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip\n" +
            "zipStoreBase=GRADLE_USER_HOME\n" +
            "zipStorePath=wrapper/dists\n"
        )

    // gradle-wrapper.jar: assets에서 로드하여 사용한다.
        val wrapperJarOut = wrapperDir.resolve("gradle-wrapper.jar")
        if (!wrapperJarOut.exists() && AssetUtils.assetExists(context, "gradle-wrapper.jar")) {
            AssetUtils.copyAsset(context, "gradle-wrapper.jar", wrapperJarOut, overwrite = true)
        }
        val wrapperSharedOut = wrapperDir.resolve("gradle-wrapper-shared.jar")
        if (!wrapperSharedOut.exists() && AssetUtils.assetExists(context, "gradle-wrapper-shared.jar")) {
            AssetUtils.copyAsset(context, "gradle-wrapper-shared.jar", wrapperSharedOut, overwrite = true)
        }
        val wrapperCliOut = wrapperDir.resolve(wrapperCliJar)
        if (!wrapperCliOut.exists() && AssetUtils.assetExists(context, wrapperCliJar)) {
            AssetUtils.copyAsset(context, wrapperCliJar, wrapperCliOut, overwrite = true)
        }
    }

    private fun writeRootFiles(root: File, cfg: ProjectConfig) {
        // libs.versions.toml (공통)
        write(File(root, "gradle").apply { mkdirs() }, "libs.versions.toml", """
            [versions]
            agp = "${cfg.agpVersion}"
            kotlin = "${cfg.kotlinVersion}"
            coreKtx = "1.12.0"
            appcompat = "1.6.1"
            material = "1.11.0"
            navigation = "2.7.6"
            constraintlayout = "2.1.4"

            [libraries]
            androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
            androidx-appcompat = { module = "androidx.appcompat:appcompat", version.ref = "appcompat" }
            material = { module = "com.google.android.material:material", version.ref = "material" }
            navigation-fragment = { module = "androidx.navigation:navigation-fragment-ktx", version.ref = "navigation" }
            navigation-ui = { module = "androidx.navigation:navigation-ui-ktx", version.ref = "navigation" }
            constraintlayout = { module = "androidx.constraintlayout:constraintlayout", version.ref = "constraintlayout" }

            [plugins]
            android-application = { id = "com.android.application", version.ref = "agp" }
            kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
        """.trimIndent())

        if (cfg.buildDsl == BuildDsl.KTS) {
    // KTS 파일
            write(root, "settings.gradle.kts", """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = "${cfg.appName}"
                include(":app")
            """.trimIndent())
            write(root, "build.gradle.kts", """
                plugins {
                    id("com.android.application") version "${cfg.agpVersion}" apply false
                }
            """.trimIndent())
        } else {
    // Groovy 파일
            write(root, "settings.gradle", """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
                dependencyResolutionManagement {
                    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                    repositories {
                        google()
                        mavenCentral()
                    }
                }
                rootProject.name = '${cfg.appName}'
                include ':app'
            """.trimIndent())
            write(root, "build.gradle", """
                plugins {
                    id 'com.android.application' version '${cfg.agpVersion}' apply false
                }
            """.trimIndent())
        }

        write(root, "gradle.properties",
            "android.useAndroidX=true\n" +
            "android.nonTransitiveRClass=true\n" +
            "org.gradle.daemon=$gradleDaemonPreference\n" +
            "org.gradle.workers.max=$mobileWorkerCount\n" +
            "org.gradle.caching=true\n" +
            "org.gradle.vfs.watch=false\n" +
            "android.aapt2ThreadPoolSize=$mobileWorkerCount\n" +
            "kotlin.daemon.enabled=$kotlinDaemonPreference\n" +
            ""
        )

        // local.properties: Android SDK 경로
        // write(root, "local.properties", "sdk.dir=/opt/android-sdk\n")
    }

    // app/build.gradle 파일
    private fun writeAppModule(root: File, cfg: ProjectConfig) {
        val app = File(root, "app").apply { mkdirs() }
        val buildFileName = if (cfg.buildDsl == BuildDsl.KTS) "build.gradle.kts" else "build.gradle"
        write(app, buildFileName, buildAppGradle(cfg))
        write(app, "proguard-rules.pro", """
            # R8/ProGuard rules for release builds.
            # Add project-specific keep rules here when reflection, JNI, or serialization requires them.
            -keepattributes *Annotation*,InnerClasses,EnclosingMethod
        """.trimIndent() + "\n")
    }

    private fun ndkVersionLine(cfg: ProjectConfig, kts: Boolean): String {
        val ver = cfg.ndkVersion.ifEmpty { "please download ndk" }
        return if (kts) "ndkVersion = \"$ver\"" else "ndkVersion '$ver'"
    }

    private fun buildAppGradle(cfg: ProjectConfig): String {
        val isNative = cfg.template == Template.NATIVE_CPP
        val isCompose = cfg.template == Template.EMPTY_COMPOSE_ACTIVITY
        val needsNavigation = cfg.template == Template.NAVIGATION_DRAWER || cfg.template == Template.BOTTOM_NAVIGATION
        val nativeDefaultConfig = if (isNative) {
            if (cfg.buildDsl == BuildDsl.KTS) listOf(
                "externalNativeBuild {",
                "    cmake {",
                "        arguments += \"-DCMAKE_C_STANDARD=17\"",
                "    }",
                "}"
            ).joinToString("\n") else listOf(
                "externalNativeBuild {",
                "    cmake {",
                "        arguments '-DCMAKE_C_STANDARD=17'",
                "    }",
                "}"
            ).joinToString("\n")
        } else ""
        val nativeAndroidLines = if (isNative) {
            if (cfg.buildDsl == BuildDsl.KTS) buildList {
                add("externalNativeBuild {")
                add("    cmake {")
                add("        path = file(\"src/main/cpp/CMakeLists.txt\")")
                add("    }")
                add("}")
            }.joinToString("\n") else buildList {
                add("externalNativeBuild {")
                add("    cmake {")
                add("        path file('src/main/cpp/CMakeLists.txt')")
                add("    }")
                add("}")
            }.joinToString("\n")
        } else ""

        if (cfg.buildDsl == BuildDsl.KTS) {
    // KTS 파일
            val buildFeatures = buildList {
                if (cfg.useViewBinding && !isCompose) add("viewBinding = true")
                if (isCompose) add("compose = true")
            }.joinToString("\n")
            val composeOptions = if (isCompose) """
                    composeOptions {
                        kotlinCompilerExtensionVersion = "1.5.3"
                    }
            """.trimIndent() else ""
            val composeDeps = if (isCompose) """
                    implementation(platform("androidx.compose:compose-bom:2024.02.02"))
                    implementation("androidx.activity:activity-compose:1.8.2")
                    implementation("androidx.compose.ui:ui")
                    implementation("androidx.compose.ui:ui-tooling-preview")
                    implementation("androidx.compose.material3:material3")
                    debugImplementation("androidx.compose.ui:ui-tooling")
                    debugImplementation("androidx.compose.ui:ui-test-manifest")
            """.trimIndent() else ""
            val navigationDeps = if (needsNavigation) """
                    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
                    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
                    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
                    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
            """.trimIndent() else ""
            val drawerDeps = if (cfg.template == Template.NAVIGATION_DRAWER) """
                    implementation("com.google.android.material:material:1.11.0")
            """.trimIndent() else ""

            return """
                plugins {
                    id("com.android.application")
                }
                android {
                    namespace = "${cfg.packageName}"
                    compileSdk = ${cfg.compileSdk}
                    \${ndkVersionLine(cfg, true)}
                    defaultConfig {
                        applicationId = "${cfg.packageName}"
                        minSdk = ${cfg.minSdk}
                        targetSdk = ${cfg.targetSdk}
                        versionCode = 1
                        versionName = "1.0"
${nativeDefaultConfig.prependIndent("                    ")}
                    }
                    compileOptions {
                        sourceCompatibility = JavaVersion.VERSION_${cfg.javaSourceCompat}
                        targetCompatibility = JavaVersion.VERSION_${cfg.javaTargetCompat}
                    }
                    buildTypes {
                        release {
                            isMinifyEnabled = ${cfg.releaseMinify}
                            isShrinkResources = ${cfg.releaseShrink}
                            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                        }
                    }
                    buildFeatures {
${buildFeatures.prependIndent("                        ")}
                    }
${composeOptions.prependIndent("                    ")}
${nativeAndroidLines.prependIndent("                ")}
                }
                dependencies {
                    implementation("androidx.core:core-ktx:1.12.0")
                    implementation("androidx.appcompat:appcompat:1.6.1")
                    implementation("com.google.android.material:material:1.11.0")
${navigationDeps.prependIndent("                    ")}
${drawerDeps.prependIndent("                    ")}
${composeDeps.prependIndent("                    ")}
                }
            """.trimIndent()
        }

    // Groovy 파일
        val buildFeatures = buildList {
            if (cfg.useViewBinding && !isCompose) add("viewBinding true")
            if (isCompose) add("compose true")
        }.joinToString("\n")
        val composeOptions = if (isCompose) """
                composeOptions {
                    kotlinCompilerExtensionVersion '1.5.3'
                }
        """.trimIndent() else ""
        val composeDeps = if (isCompose) """
                implementation platform('androidx.compose:compose-bom:2024.02.02')
                implementation 'androidx.activity:activity-compose:1.8.2'
                implementation 'androidx.compose.ui:ui'
                implementation 'androidx.compose.ui:ui-tooling-preview'
                implementation 'androidx.compose.material3:material3'
                debugImplementation 'androidx.compose.ui:ui-tooling'
                debugImplementation 'androidx.compose.ui:ui-test-manifest'
        """.trimIndent() else ""
        val navigationDeps = if (needsNavigation) """
                implementation 'androidx.navigation:navigation-fragment-ktx:2.7.6'
                implementation 'androidx.navigation:navigation-ui-ktx:2.7.6'
                implementation 'androidx.drawerlayout:drawerlayout:1.2.0'
                implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
        """.trimIndent() else ""
        val drawerDeps = if (cfg.template == Template.NAVIGATION_DRAWER) """
                implementation 'com.google.android.material:material:1.11.0'
        """.trimIndent() else ""

        return """
            plugins {
                id 'com.android.application'
            }
            android {
                namespace '${cfg.packageName}'
                compileSdk ${cfg.compileSdk}
                \${ndkVersionLine(cfg, false)}
                defaultConfig {
                    applicationId '${cfg.packageName}'
                    minSdk ${cfg.minSdk}
                    targetSdk ${cfg.targetSdk}
                    versionCode 1
                    versionName '1.0'
${nativeDefaultConfig.prependIndent("                ")}
                }
                compileOptions {
                    sourceCompatibility JavaVersion.VERSION_17
                    targetCompatibility JavaVersion.VERSION_17
                }
                buildTypes {
                    debug {
                        minifyEnabled false
                    }
                    release {
                        minifyEnabled true
                        shrinkResources false
                        proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
                    }
                }
                buildFeatures {
${buildFeatures.prependIndent("                    ")}
                }
${composeOptions.prependIndent("                ")}
${nativeAndroidLines.prependIndent("            ")}
            }
            dependencies {
                implementation 'androidx.core:core-ktx:1.12.0'
                implementation 'androidx.appcompat:appcompat:1.6.1'
                implementation 'com.google.android.material:material:1.11.0'
${navigationDeps.prependIndent("                ")}
${drawerDeps.prependIndent("                ")}
${composeDeps.prependIndent("                ")}
            }
        """.trimIndent()
    }

    // src/main 파일
    private fun writeSrcMain(root: File, cfg: ProjectConfig) {
        val pkgPath = cfg.packageName.replace('.', '/')
        val isJavaXml = cfg.template == Template.EMPTY_JAVA_ACTIVITY
        val isKotlinXml = cfg.template == Template.EMPTY_ACTIVITY ||
            cfg.template == Template.BASIC_ACTIVITY ||
            cfg.template == Template.NAVIGATION_DRAWER ||
            cfg.template == Template.BOTTOM_NAVIGATION ||
            cfg.template == Template.SETTINGS_ACTIVITY
        val srcDir  = File(root, if (isJavaXml) "app/src/main/java/$pkgPath" else "app/src/main/kotlin/$pkgPath").apply { mkdirs() }
        val layoutDir = File(root, "app/src/main/res/layout").apply { mkdirs() }
        val menuDir = File(root, "app/src/main/res/menu").apply { mkdirs() }
        val valDir  = File(root, "app/src/main/res/values").apply { mkdirs() }

        val needsNavGraph = cfg.template == Template.NAVIGATION_DRAWER || cfg.template == Template.BOTTOM_NAVIGATION
        val navGraphXml = if (needsNavGraph) """
            <navigation xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto"
                android:id="@+id/nav_graph"
                app:startDestination="@id/homeFragment">
                <fragment
                    android:id="@+id/homeFragment"
                    android:name="${cfg.packageName}.ui.HomeFragment"
                    android:label="Home">
                    <action
                        android:id="@+id/action_home_to_detail"
                        app:destination="@id/detailFragment""/>
                </fragment>
                <fragment
                    android:id="@+id/detailFragment"
                    android:name="${cfg.packageName}.ui.DetailFragment"
                    android:label="Detail"/>
            </navigation>
        """.trimIndent() else ""

        val navSetupInManifest = if (needsNavGraph) """
                    <nav-graph android:value="@navigation/nav_graph"/>
        """.trimIndent() else ""

        val mainActivityClass = when (cfg.template) {
            Template.NAVIGATION_DRAWER -> ".MainActivity"
            Template.BOTTOM_NAVIGATION -> ".MainActivity"
            Template.SETTINGS_ACTIVITY -> ".MainActivity"
            Template.BASIC_ACTIVITY -> ".MainActivity"
            else -> ".MainActivity"
        }

        write(File(root, "app/src/main"), "AndroidManifest.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="${cfg.appName}"
                    android:theme="@style/Theme.App">
                    <activity
                        android:name="$mainActivityClass"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                        $navSetupInManifest
                    </activity>
                </application>
            </manifest>
        """.trimIndent())

        when (cfg.template) {
            Template.NATIVE_CPP -> write(srcDir, "MainActivity.kt", """
                package ${cfg.packageName}

                import android.os.Bundle
                import android.view.Gravity
                import android.widget.TextView
                import androidx.appcompat.app.AppCompatActivity

                class MainActivity : AppCompatActivity() {
                    external fun stringFromJNI(): String

                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(TextView(this).apply {
                            text = stringFromJNI()
                            gravity = Gravity.CENTER
                            textSize = 18f
                        })
                    }

                    companion object {
                        init {
                            System.loadLibrary("native-lib")
                        }
                    }
                }
            """.trimIndent())

            Template.EMPTY_COMPOSE_ACTIVITY -> write(srcDir, "MainActivity.kt", """
                package ${cfg.packageName}

                import android.os.Bundle
                import androidx.activity.ComponentActivity
                import androidx.activity.compose.setContent
                import androidx.compose.foundation.layout.Box
                import androidx.compose.foundation.layout.fillMaxSize
                import androidx.compose.material3.MaterialTheme
                import androidx.compose.material3.Surface
                import androidx.compose.material3.Text
                import androidx.compose.runtime.Composable
                import androidx.compose.ui.Alignment
                import androidx.compose.ui.Modifier
                import androidx.compose.ui.tooling.preview.Preview

                class MainActivity : ComponentActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContent {
                            App()
                        }
                    }
                }

                @Composable
                fun App() {
                    MaterialTheme {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("Hello Compose from MobileStudio!")
                            }
                        }
                    }
                }

                @Preview(showBackground = true)
                @Composable
                fun AppPreview() {
                    App()
                }
            """.trimIndent())

            Template.EMPTY_JAVA_ACTIVITY -> write(srcDir, "MainActivity.java", """
                package ${cfg.packageName};

                import android.os.Bundle;
                import androidx.appcompat.app.AppCompatActivity;

                public class MainActivity extends AppCompatActivity {
                    @Override
                    protected void onCreate(Bundle savedInstanceState) {
                        super.onCreate(savedInstanceState);
                        setContentView(R.layout.activity_main);
                    }
                }
            """.trimIndent())

            Template.BASIC_ACTIVITY -> writeBasicActivity(srcDir, layoutDir, cfg)

            Template.NAVIGATION_DRAWER -> writeNavigationDrawer(srcDir, layoutDir, menuDir, cfg)

            Template.BOTTOM_NAVIGATION -> writeBottomNavigation(srcDir, layoutDir, menuDir, cfg)

            Template.SETTINGS_ACTIVITY -> writeSettingsActivity(srcDir, layoutDir, cfg)

            else -> write(srcDir, "MainActivity.kt", """
                package ${cfg.packageName}

                import androidx.appcompat.app.AppCompatActivity
                import android.os.Bundle

                class MainActivity : AppCompatActivity() {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.activity_main)
                    }
                }
            """.trimIndent())
        }

        if (isKotlinXml || isJavaXml) {
            val layoutXml = when (cfg.template) {
                Template.BASIC_ACTIVITY -> buildBasicActivityLayoutXml()
                Template.NAVIGATION_DRAWER -> buildNavDrawerLayoutXml()
                Template.BOTTOM_NAVIGATION -> buildBottomNavLayoutXml()
                Template.SETTINGS_ACTIVITY -> buildSettingsLayoutXml()
                else -> buildDefaultActivityLayoutXml()
            }
            write(layoutDir, "activity_main.xml", layoutXml)
            if (cfg.template == Template.NAVIGATION_DRAWER) {
                write(layoutDir, "content_main.xml", buildNavDrawerContentXml())
            }
            if (cfg.template == Template.BOTTOM_NAVIGATION) {
                write(layoutDir, "fragment_home.xml", buildFragmentHomeXml())
                write(layoutDir, "fragment_detail.xml", buildFragmentDetailXml())
            }
            if (cfg.template == Template.SETTINGS_ACTIVITY) {
                write(layoutDir, "fragment_settings.xml", buildSettingsFragmentXml())
            }
            if (needsNavGraph) {
                val navDir = File(root, "app/src/main/res/navigation").apply { mkdirs() }
                write(navDir, "nav_graph.xml", navGraphXml)
            }
        }

        write(valDir, "themes.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="Theme.AppCompat.DayNight.NoActionBar"/>
                <style name="Theme.App.BarOverlay" parent="ThemeOverlay.AppCompat.Dark.ActionBar"/>
                <style name="Theme.App.ThemeOverlay" parent="ThemeOverlay.AppCompat.Light"/>
            </resources>
        """.trimIndent())

        if (needsNavGraph) {
            write(valDir, "strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">${cfg.appName}</string>
                    <string name="action_settings">Settings</string>
                </resources>
            """.trimIndent())
            write(valDir, "dimens.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <dimen name="margin_default">16dp</dimen>
                </resources>
            """.trimIndent())
        }

        if (cfg.template == Template.BASIC_ACTIVITY) {
            write(valDir, "strings.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <resources>
                    <string name="app_name">${cfg.appName}</string>
                </resources>
            """.trimIndent())
        }

        if (cfg.template == Template.NAVIGATION_DRAWER) {
            val navHeaderDir = File(root, "app/src/main/res/layout").apply { mkdirs() }
            write(navHeaderDir, "nav_header_main.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:layout_width="match_parent"
                    android:layout_height="176dp"
                    android:gravity="bottom"
                    android:orientation="vertical"
                    android:padding="16dp"
                    android:theme="@style/ThemeOverlay.AppCompat.Dark">

                    <ImageView
                        android:layout_width="64dp"
                        android:layout_height="64dp"
                        android:contentDescription="@string/app_name"
                        android:src="@android:drawable/sym_def_app_icon"/>

                    <TextView
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:layout_marginTop="8dp"
                        android:text="@string/app_name"
                        android:textAppearance="@style/TextAppearance.AppCompat.Body1"/>

                </LinearLayout>
            """.trimIndent())
            write(navHeaderDir, "activity_main_content.xml", """
                <?xml version="1.0" encoding="utf-8"?>
                <androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    xmlns:app="http://schemas.android.com/apk/res-auto"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent">

                    <com.google.android.material.appbar.AppBarLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content">

                        <androidx.appcompat.widget.Toolbar
                            android:id="@+id/toolbar"
                            android:layout_width="match_parent"
                            android:layout_height="?attr/actionBarSize"
                            app:popupTheme="@style/Theme.App.ThemeOverlay"/>

                    </com.google.android.material.appbar.AppBarLayout>

                    <include layout="@layout/content_main"/>

                </androidx.coordinatorlayout.widget.CoordinatorLayout>
            """.trimIndent())
        }
    }

    // Basic Activity 파일
    private fun writeBasicActivity(srcDir: File, layoutDir: File, cfg: ProjectConfig) {
        write(srcDir, "MainActivity.kt", """
            package ${cfg.packageName}

            import android.os.Bundle
            import android.view.View
            import com.google.android.material.snackbar.Snackbar
            import androidx.appcompat.app.AppCompatActivity

            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                    setSupportActionBar(findViewById(R.id.toolbar))
                }

                fun onFabClick(view: View) {
                    Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show()
                }
            }
        """.trimIndent())
    }

    private fun buildBasicActivityLayoutXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <androidx.coordinatorlayout.widget.CoordinatorLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <com.google.android.material.appbar.AppBarLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:theme="@style/Theme.App.BarOverlay">

                <androidx.appcompat.widget.Toolbar
                    android:id="@+id/toolbar"
                    android:layout_width="match_parent"
                    android:layout_height="?attr/actionBarSize"
                    android:background="?attr/colorPrimary"
                    app:popupTheme="@style/Theme.App.ThemeOverlay"/>

            </com.google.android.material.appbar.AppBarLayout>

            <TextView
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="Hello MobileStudio!"
                android:textSize="18sp"
                app:layout_behavior="@string/appbar_scrolling_view_behavior"/>

            <com.google.android.material.floatingactionbutton.FloatingActionButton
                android:id="@+id/fab"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="bottom|end"
                android:layout_margin="16dp"
                android:contentDescription="FAB"
                android:onClick="onFabClick"
                android:src="@android:drawable/ic_dialog_email"/>

        </androidx.coordinatorlayout.widget.CoordinatorLayout>
    """.trimIndent()

    // Navigation Drawer 파일
    private fun writeNavigationDrawer(srcDir: File, layoutDir: File, menuDir: File, cfg: ProjectConfig) {
        val uiDir = File(srcDir.parentFile, "${cfg.packageName.replace('.', '/')}/ui").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", """
            package ${cfg.packageName}

            import android.os.Bundle
            import android.view.Menu
            import com.google.android.material.navigation.NavigationView
            import androidx.navigation.findNavController
            import androidx.navigation.ui.AppBarConfiguration
            import androidx.navigation.ui.navigateUp
            import androidx.navigation.ui.setupActionBarWithNavController
            import androidx.navigation.ui.setupWithNavController
            import androidx.drawerlayout.widget.DrawerLayout
            import androidx.appcompat.app.AppCompatActivity
            import androidx.core.view.GravityCompat

            class MainActivity : AppCompatActivity() {
                private lateinit var appBarConfiguration: AppBarConfiguration

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                    val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                    setSupportActionBar(toolbar)

                    val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
                    val navView = findViewById<NavigationView>(R.id.nav_view)
                    val navController = findNavController(R.id.nav_host_fragment)
                    appBarConfiguration = AppBarConfiguration(
                        setOf(R.id.homeFragment), drawerLayout
                    )
                    setupActionBarWithNavController(navController, appBarConfiguration)
                    navView.setupWithNavController(navController)
                }

                override fun onCreateOptionsMenu(menu: Menu): Boolean {
                    menuInflater.inflate(R.menu.menu_main, menu)
                    return true
                }

                override fun onSupportNavigateUp(): Boolean {
                    val navController = findNavController(R.id.nav_host_fragment)
                    return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
                }

                override fun onBackPressed() {
                    val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
                    if (drawer.isDrawerOpen(GravityCompat.START)) {
                        drawer.closeDrawer(GravityCompat.START)
                    } else {
                        super.onBackPressed()
                    }
                }
            }
        """.trimIndent())
        write(uiDir, "HomeFragment.kt", """
            package ${cfg.packageName}.ui

            import android.os.Bundle
            import android.view.LayoutInflater
            import android.view.View
            import android.view.ViewGroup
            import androidx.fragment.app.Fragment

            class HomeFragment : Fragment() {
                override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
                    return inflater.inflate(R.layout.fragment_home, container, false)
                }
            }
        """.trimIndent())
        write(uiDir, "DetailFragment.kt", """
            package ${cfg.packageName}.ui

            import android.os.Bundle
            import android.view.LayoutInflater
            import android.view.View
            import android.view.ViewGroup
            import androidx.fragment.app.Fragment

            class DetailFragment : Fragment() {
                override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
                    return inflater.inflate(R.layout.fragment_detail, container, false)
                }
            }
        """.trimIndent())
        write(menuDir, "menu_main.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <menu xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto">
                <item
                    android:id="@+id/action_settings"
                    android:orderInCategory="100"
                    android:title="@string/action_settings"
                    app:showAsAction="never"/>
            </menu>
        """.trimIndent())
        write(menuDir, "activity_main_drawer.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <menu xmlns:android="http://schemas.android.com/apk/res/android">
                <group android:checkableBehavior="single">
                    <item
                        android:id="@+id/homeFragment"
                        android:icon="@android:drawable/ic_menu_today"
                        android:title="Home"/>
                    <item
                        android:id="@+id/detailFragment"
                        android:icon="@android:drawable/ic_menu_info_details"
                        android:title="Detail"/>
                </group>
                <item android:title="Other">
                    <menu>
                        <item
                            android:id="@+id/nav_slideshow"
                            android:icon="@android:drawable/ic_menu_slideshow"
                            android:title="Slideshow"/>
                        <item
                            android:id="@+id/nav_tools"
                            android:icon="@android:drawable/ic_menu_manage"
                            android:title="Tools"/>
                    </menu>
                </item>
            </menu>
        """.trimIndent())
    }

    private fun buildNavDrawerLayoutXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <androidx.drawerlayout.widget.DrawerLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            xmlns:tools="http://schemas.android.com/tools"
            android:id="@+id/drawer_layout"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:fitsSystemWindows="true"
            tools:openDrawer="start">

            <include
                android:id="@+id/app_bar"
                layout="activity_main_content"/>

            <com.google.android.material.navigation.NavigationView
                android:id="@+id/nav_view"
                android:layout_width="wrap_content"
                android:layout_height="match_parent"
                android:layout_gravity="start"
                android:fitsSystemWindows="true"
                app:headerLayout="@layout/nav_header_main"
                app:menu="@menu/activity_main_drawer"/>

        </androidx.drawerlayout.widget.DrawerLayout>
    """.trimIndent()

    private fun buildNavDrawerContentXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <androidx.fragment.app.FragmentContainerView
                android:id="@+id/nav_host_fragment"
                android:name="androidx.navigation.fragment.NavHostFragment"
                android:layout_width="match_parent"
                android:layout_height="match_parent"
                app:defaultNavHost="true"
                app:navGraph="@navigation/nav_graph"/>

        </androidx.constraintlayout.widget.ConstraintLayout>
    """.trimIndent()

    // Bottom Navigation 파일
    private fun writeBottomNavigation(srcDir: File, layoutDir: File, menuDir: File, cfg: ProjectConfig) {
        val uiDir = File(srcDir.parentFile, "${cfg.packageName.replace('.', '/')}/ui").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", """
            package ${cfg.packageName}

            import android.os.Bundle
            import androidx.navigation.findNavController
            import androidx.navigation.ui.AppBarConfiguration
            import androidx.navigation.ui.navigateUp
            import androidx.navigation.ui.setupActionBarWithNavController
            import androidx.navigation.ui.setupWithNavController
            import androidx.appcompat.app.AppCompatActivity
            import com.google.android.material.bottomnavigation.BottomNavigationView

            class MainActivity : AppCompatActivity() {
                private lateinit var appBarConfiguration: AppBarConfiguration

                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                    val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                    setSupportActionBar(toolbar)

                    val navController = findNavController(R.id.nav_host_fragment)
                    appBarConfiguration = AppBarConfiguration(
                        setOf(R.id.homeFragment, R.id.detailFragment)
                    )
                    setupActionBarWithNavController(navController, appBarConfiguration)

                    val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
                    bottomNav.setupWithNavController(navController)
                }

                override fun onSupportNavigateUp(): Boolean {
                    val navController = findNavController(R.id.nav_host_fragment)
                    return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
                }
            }
        """.trimIndent())
        write(uiDir, "HomeFragment.kt", """
            package ${cfg.packageName}.ui

            import android.os.Bundle
            import android.view.LayoutInflater
            import android.view.View
            import android.view.ViewGroup
            import android.widget.TextView
            import androidx.fragment.app.Fragment

            class HomeFragment : Fragment() {
                override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
                    return inflater.inflate(R.layout.fragment_home, container, false)
                }
            }
        """.trimIndent())
        write(uiDir, "DetailFragment.kt", """
            package ${cfg.packageName}.ui

            import android.os.Bundle
            import android.view.LayoutInflater
            import android.view.View
            import android.view.ViewGroup
            import androidx.fragment.app.Fragment

            class DetailFragment : Fragment() {
                override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
                    return inflater.inflate(R.layout.fragment_detail, container, false)
                }
            }
        """.trimIndent())
        write(menuDir, "menu_bottom_nav.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <menu xmlns:android="http://schemas.android.com/apk/res/android">
                <item
                    android:id="@+id/homeFragment"
                    android:icon="@android:drawable/ic_menu_today"
                    android:title="Home"/>
                <item
                    android:id="@+id/detailFragment"
                    android:icon="@android:drawable/ic_menu_info_details"
                    android:title="Detail"/>
            </menu>
        """.trimIndent())
    }

    private fun buildBottomNavLayoutXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <com.google.android.material.appbar.MaterialToolbar
                android:id="@+id/toolbar"
                android:layout_width="0dp"
                android:layout_height="?attr/actionBarSize"
                android:background="?attr/colorPrimary"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toTopOf="parent"/>

            <androidx.fragment.app.FragmentContainerView
                android:id="@+id/nav_host_fragment"
                android:name="androidx.navigation.fragment.NavHostFragment"
                android:layout_width="0dp"
                android:layout_height="0dp"
                app:defaultNavHost="true"
                app:layout_constraintBottom_toTopOf="@id/bottom_nav"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toBottomOf="@id/toolbar"
                app:navGraph="@navigation/nav_graph"/>

            <com.google.android.material.bottomnavigation.BottomNavigationView
                android:id="@+id/bottom_nav"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                app:layout_constraintBottom_toBottomOf="parent"
                app:layout_constraintEnd_toEndOf="parent"
                app:layout_constraintStart_toStartOf="parent"
                app:menu="@menu/menu_bottom_nav"/>

        </androidx.constraintlayout.widget.ConstraintLayout>
    """.trimIndent()

    // Settings Activity 파일
    private fun writeSettingsActivity(srcDir: File, layoutDir: File, cfg: ProjectConfig) {
        val uiDir = File(srcDir.parentFile, "${cfg.packageName.replace('.', '/')}/ui").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", """
            package ${cfg.packageName}

            import android.os.Bundle
            import android.view.MenuItem
            import androidx.appcompat.app.AppCompatActivity
            import ${cfg.packageName}.ui.SettingsFragment

            class MainActivity : AppCompatActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.activity_main)
                    setSupportActionBar(findViewById(R.id.toolbar))
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    if (savedInstanceState == null) {
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.container, SettingsFragment())
                            .commit()
                    }
                }

                override fun onOptionsItemSelected(item: MenuItem): Boolean {
                    if (item.itemId == android.R.id.home) {
                        onBackPressed()
                        return true
                    }
                    return super.onOptionsItemSelected(item)
                }
            }
        """.trimIndent())
        write(uiDir, "SettingsFragment.kt", """
            package ${cfg.packageName}.ui

            import android.os.Bundle
            import android.view.LayoutInflater
            import android.view.View
            import android.view.ViewGroup
            import androidx.fragment.app.Fragment

            class SettingsFragment : Fragment() {
                override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
                    return inflater.inflate(R.layout.fragment_settings, container, false)
                }
            }
        """.trimIndent())
    }

    // Layout XML builders
    private fun buildSettingsLayoutXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:orientation="vertical">

            <com.google.android.material.appbar.MaterialToolbar
                android:id="@+id/toolbar"
                android:layout_width="match_parent"
                android:layout_height="?attr/actionBarSize"
                android:background="?attr/colorPrimary"/>

            <FrameLayout
                android:id="@+id/container"
                android:layout_width="match_parent"
                android:layout_height="match_parent"/>

        </LinearLayout>
    """.trimIndent()

    private fun buildDefaultActivityLayoutXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:gravity="center"
            android:orientation="vertical"
            android:padding="24dp">

            <TextView
                android:id="@+id/titleText"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Hello MobileStudio!"
                android:textSize="20sp" />

        </LinearLayout>
    """.trimIndent()

    private fun buildFragmentHomeXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="center"
                android:text="Home Fragment"
                android:textSize="18sp"/>

        </FrameLayout>
    """.trimIndent()

    private fun buildFragmentDetailXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="center"
                android:text="Detail Fragment"
                android:textSize="18sp"/>

        </FrameLayout>
    """.trimIndent()

    private fun buildSettingsFragmentXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
            android:layout_width="match_parent"
            android:layout_height="match_parent">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="vertical"
                android:padding="16dp">

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="Settings"
                    android:textSize="20sp"
                    android:textStyle="bold"
                    android:paddingBottom="16dp"/>

                <TextView
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:text="App Name"
                    android:textSize="14sp"
                    android:paddingBottom="4dp"/>

                <EditText
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:hint="My App"
                    android:inputType="text"/>

            </LinearLayout>
        </ScrollView>
    """.trimIndent()

    private fun writeNativeCpp(root: File, cfg: ProjectConfig) {
        val cppDir = File(root, "app/src/main/cpp").apply { mkdirs() }
        val jniName = cfg.packageName.replace('.', '_')

        write(cppDir, "CMakeLists.txt", """
            cmake_minimum_required(VERSION 3.22.1)
            project(${cfg.appName.replace(Regex("[^A-Za-z0-9_]"), "_")}_native LANGUAGES C)

            add_library(native-lib SHARED native-lib.c math_utils.c)

            find_library(log-lib log)
            target_link_libraries(native-lib ${'$'}{log-lib})
        """.trimIndent())

        write(cppDir, "native-lib.c", """
            #include <jni.h>
            #include "math_utils.h"

            JNIEXPORT jstring JNICALL
            Java_${jniName}_MainActivity_stringFromJNI(JNIEnv *env, jobject thiz) {
                (void) thiz;
                char message[96];
                ms_format_greeting(message, sizeof(message), 25);
                return (*env)->NewStringUTF(env, message);
            }
        """.trimIndent())

        write(cppDir, "math_utils.c", """
            #include "math_utils.h"
            #include <stdio.h>

            int ms_add(int left, int right) {
                return left + right;
            }

            void ms_format_greeting(char *buffer, unsigned long buffer_size, int value) {
                if (buffer == 0 || buffer_size == 0) return;
                snprintf(buffer, buffer_size, "Hello from MobileStudio C / NDK: %d", ms_add(value, 17));
            }
        """.trimIndent())

        write(cppDir, "math_utils.h", """
            #pragma once

            int ms_add(int left, int right);
            void ms_format_greeting(char *buffer, unsigned long buffer_size, int value);
        """.trimIndent())
    }

    // ---------------------------------------------------------------------
    // Game projects (OpenGL ES + NativeActivity-driven via GLSurfaceView/JNI)
    // ---------------------------------------------------------------------

    private fun writeGameProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle.kts", """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "${cfg.appName}"
            include(":app")
        """.trimIndent())
        write(root, "build.gradle.kts", """
            plugins {
                id("com.android.application") version "${cfg.agpVersion}" apply false
            }
        """.trimIndent())
        write(root, "gradle.properties",
            "android.useAndroidX=true\n" +
            "org.gradle.daemon=$gradleDaemonPreference\n" +
            "org.gradle.workers.max=$mobileWorkerCount\n" +
            "org.gradle.caching=true\n" +
            "org.gradle.vfs.watch=false\n" +
            "kotlin.daemon.enabled=$kotlinDaemonPreference\n"
        )

        val app = File(root, "app").apply { mkdirs() }
        write(app, "build.gradle.kts", """
            plugins {
                id("com.android.application")
            }
            android {
                namespace = "${cfg.packageName}"
                compileSdk = ${cfg.compileSdk}
                \${ndkVersionLine(cfg, true)}
                defaultConfig {
                    applicationId = "\${cfg.packageName}"
                    minSdk = \${cfg.minSdk}
                    targetSdk = \${cfg.targetSdk}
                    versionCode = 1
                    versionName = "1.0"
                }
                externalNativeBuild {
                    cmake {
                        path = file("src/main/cpp/CMakeLists.txt")
                    }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                buildTypes {
                    release {
                        isMinifyEnabled = false
                    }
                }
                ndk {
                    abiFilters += listOf("arm64-v8a")
                }
            }
            dependencies {
                implementation("androidx.appcompat:appcompat:1.6.1")
            }
        """.trimIndent())
        write(app, "proguard-rules.pro", "# Game project\n")

        val mainDir = File(app, "src/main").apply { mkdirs() }
        write(mainDir, "AndroidManifest.xml",
            gameManifestXml(cfg))
        val kotlinDir = File(mainDir, "kotlin/${cfg.packageName.replace('.', '/')}").apply { mkdirs() }
        write(kotlinDir, "MainActivity.kt", gameMainActivityKt(cfg))
        val resDir = File(mainDir, "res/values").apply { mkdirs() }
        write(resDir, "themes.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <style name="Theme.App" parent="Theme.AppCompat.DayNight.NoActionBar"/>
            </resources>
        """.trimIndent())

        val cppDir = File(mainDir, "cpp").apply { mkdirs() }
        write(cppDir, "CMakeLists.txt", gameCmake(cfg))
        write(cppDir, "game_math.h", GAME_MATH_HEADER)
        write(cppDir, "game_gl.h", GAME_GL_HEADER)
        val jni = cfg.packageName.replace('.', '_')
        when (cfg.template) {
            Template.GAME_2D_PONG -> write(cppDir, "game.cpp", gamePongCpp(jni))
            Template.GAME_2D_SNAKE -> write(cppDir, "game.cpp", gameSnakeCpp(jni))
            Template.GAME_3D_SCENE -> write(cppDir, "game.cpp", gameSceneCpp(jni))
            Template.GAME_VOXEL_WORLD -> write(cppDir, "game.cpp", gameVoxelCpp(jni))
            else -> {}
        }
        write(root, "README.md", """
            # ${cfg.appName}

            Generated by MobileStudio game project template.

            ## Controls
            - Touch controls are built into the game. See the top of the native
              source (app/src/main/cpp/game.cpp) for the control scheme.

            ## Build
            Run the Gradle build from MobileStudio (assembleDebug). The game is a
            NativeActivity-style OpenGL ES application compiled with the Android
            NDK Clang/CMake/Ninja toolchain for arm64-v8a.

            No PC or Termux is required — MobileStudio bundles the SDK/NDK/CMake.
        """.trimIndent() + "\n")
    }

    // -----------------------------------------------------------------
    // Lua Script-Only Game Project — just main.lua, no Gradle/APK infrastructure.
    // Runs directly via GameRunnerActivity (in-process LuaJ runtime, LÖVE-compatible).
    // -----------------------------------------------------------------

    private fun writeLuaScriptOnlyProject(root: File, cfg: ProjectConfig) {
        write(root, "main.lua", luaStarterScript(cfg))
        write(root, "README.md", "# ${cfg.appName}\n\n" +
            "Lua game (script-only project)\n\n" +
            "- Run: tap Run — executes main.lua directly in the in-process Lua runtime\n" +
            "- No Gradle/APK build needed\n" +
            "- API: love.graphics, love.audio, love.filesystem, love.math, love.timer,\n" +
            "  love.window, love.touch + engine.* (2D/3D: begin3D, camera, drawCube, drawPlane)\n")
    }

    private fun luaStarterScript(cfg: ProjectConfig): String = """
-- ${cfg.appName} — LÖVE-compatible Lua game
-- Run this file directly in MobileStudio (in-process Lua runtime)

function love.load()
    love.window.setTitle("${cfg.appName}")
    player = { x = 100, y = 100, size = 40, vx = 220, vy = 180 }
    score = 0
end

function love.update(dt)
    player.x = player.x + player.vx * dt
    player.y = player.y + player.vy * dt
    local w = love.graphics.getWidth()
    local h = love.graphics.getHeight()
    if player.x < 0 or player.x + player.size > w then
        player.vx = -player.vx
    end
    if player.y < 0 or player.y + player.size > h then
        player.vy = -player.vy
    end
end

function love.draw()
    love.graphics.clear(0.05, 0.07, 0.1)
    love.graphics.setColor(0.3, 0.6, 1.0)
    love.graphics.rectangle("fill", player.x, player.y, player.size, player.size)
    love.graphics.setColor(1, 1, 1)
    love.graphics.print("Score: " .. tostring(score), 20, 40)
    love.graphics.print("Touch to move the box", 20, 70)
end

function love.touchpressed(id, x, y)
    player.x = x - player.size / 2
    player.y = y - player.size / 2
end

function love.mousepressed(x, y, button)
    player.x = x - player.size / 2
    player.y = y - player.size / 2
end
""".trimIndent() + "\n"

    private fun writeJsScriptOnlyProject(root: File, cfg: ProjectConfig) {
        write(root, "game.js", jsStarterScript(cfg))
        write(root, "README.md", "# ${cfg.appName}\n\n" +
            "JavaScript game (script-only project)\n\n" +
            "- Run: tap Run — executes game.js directly in the in-process Rhino runtime\n" +
            "- No Gradle/APK build needed\n" +
            "- API: love.graphics, love.audio, love.filesystem, love.math, love.timer,\n" +
            "  love.window, love.touch + engine.* (2D/3D)\n")
    }

    private fun jsStarterScript(cfg: ProjectConfig): String = """
// ${cfg.appName} — LÖVE-compatible JavaScript game
// Run this file directly in MobileStudio (in-process Rhino runtime)

var player = { x: 100, y: 100, size: 40, vx: 220, vy: 180 };
var score = 0;

function loveLoad() {
    love.window.setTitle("${cfg.appName}");
}

function loveUpdate(dt) {
    player.x = player.x + player.vx * dt;
    player.y = player.y + player.vy * dt;
    var w = love.graphics.getWidth();
    var h = love.graphics.getHeight();
    if (player.x < 0 || player.x + player.size > w) { player.vx = -player.vx; }
    if (player.y < 0 || player.y + player.size > h) { player.vy = -player.vy; }
}

function loveDraw() {
    love.graphics.clear(0.05, 0.07, 0.1);
    love.graphics.setColor(0.3, 0.6, 1.0);
    love.graphics.rectangle("fill", player.x, player.y, player.size, player.size);
    love.graphics.setColor(1, 1, 1);
    love.graphics.print("Score: " + score, 20, 40);
    love.graphics.print("Touch to move the box", 20, 70);
}

function loveTouchpressed(id, x, y) {
    player.x = x - player.size / 2;
    player.y = y - player.size / 2;
}

loveLoad();
""".trimIndent() + "\n"

    // -----------------------------------------------------------------
    // Lua Game Project (LuaJ + Canvas API)
    // -----------------------------------------------------------------

    private fun writeLuaGameProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle.kts", """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "${cfg.appName}"
            include(":app")
        """.trimIndent())
        write(root, "build.gradle.kts", """
            plugins {
                id("com.android.application") version "${cfg.agpVersion}" apply false
            }
        """.trimIndent())
        write(root, "gradle.properties",
            "android.useAndroidX=true\n" +
            "org.gradle.daemon=$gradleDaemonPreference\n" +
            "org.gradle.workers.max=$mobileWorkerCount\n" +
            "org.gradle.caching=true\n" +
            "org.gradle.vfs.watch=false\n" +
            "kotlin.daemon.enabled=$kotlinDaemonPreference\n"
        )

        val app = File(root, "app").apply { mkdirs() }
        write(app, "build.gradle.kts", """
            plugins {
                id("com.android.application")
            }
            android {
                namespace = "${cfg.packageName}"
                compileSdk = ${cfg.compileSdk}
                defaultConfig {
                    applicationId = "${cfg.packageName}"
                    minSdk = ${cfg.minSdk}
                    targetSdk = ${cfg.targetSdk}
                    versionCode = 1
                    versionName = "1.0"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                buildTypes {
                    release {
                        isMinifyEnabled = false
                    }
                }
            }
            dependencies {
                implementation("androidx.appcompat:appcompat:1.6.1")
                implementation("com.github.luaj:luaj:v3.0.2")
            }
        """.trimIndent())
        write(app, "proguard-rules.pro", "# Lua game project\n")

        val mainDir = File(app, "src/main").apply { mkdirs() }
        write(mainDir, "AndroidManifest.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="${cfg.appName}"
                    android:icon="@android:drawable/sym_def_app_icon"
                    android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:configChanges="orientation|screenSize|keyboardHidden"
                        android:screenOrientation="landscape">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent())

        val pkgPath = cfg.packageName.replace('.', '/')
        val srcDir = File(mainDir, "java/$pkgPath").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", luaGameActivityKt(cfg))

        val assetsDir = File(mainDir, "assets").apply { mkdirs() }
        write(assetsDir, "game.lua", luaGameScript(cfg))

        // main.lua — MobileStudio in-app player entry point (LÖVE-style callbacks).
        // findGameScript() prefers main.lua at the project root.
        write(root, "main.lua", """
            print("LUA FILE IS RUNNING!")

            function love.load()
                print("LOVE LOAD OK")
            end

            function love.update(dt)
            end

            function love.draw()
                love.graphics.clear(0.05, 0.07, 0.1)
                love.graphics.setColor(1, 1, 1)
                love.graphics.print("LÖVE API TEST", 100, 100)
            end

            function love.touchpressed(id, x, y)
                print("TOUCH: " .. id .. " at " .. x .. ", " .. y)
            end

            function love.touchreleased(id, x, y)
            end

            function love.touchmoved(id, x, y, dx, dy)
            end

            function love.keypressed(key)
                print("KEY: " .. key)
            end
        """.trimIndent() + "\n")

        write(root, "README.md", """
            # ${cfg.appName}

            Lua game project generated by MobileStudio.

            ## How it works
            - main.lua is the entry point for the MobileStudio in-app player
              (Native Lua 5.4.9 VM + LÖVE-compatible runtime)
            - app/src/main/assets/game.lua is used when building a standalone APK (LuaJ)

            ## LÖVE callbacks supported
            - love.load() / love.update(dt) / love.draw()
            - love.touchpressed/released/moved, love.mousepressed/released/moved
            - love.keypressed/keyreleased

            ## Controls
            - Touch screen to interact (see main.lua)

            ## Build
            Run assembleDebug from MobileStudio. No NDK required.
        """.trimIndent() + "\n")
    }

    private fun luaGameActivityKt(cfg: ProjectConfig): String = """
        package ${cfg.packageName}

        import android.app.Activity
        import android.content.Context
        import android.graphics.Canvas
        import android.graphics.Color
        import android.graphics.Paint
        import android.os.Bundle
        import android.view.MotionEvent
        import android.view.View
        import org.luaj.vm2.*
        import org.luaj.vm2.lib.jse.JsePlatform

        class MainActivity : Activity() {
            private lateinit var gameView: GameView

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                gameView = GameView(this)
                setContentView(gameView)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                gameView.onTouch(e)
                return true
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

            inner class GameView(context: Context) : View(context) {
                var running = true
                private val globals = Globals()
                private val drawPaint = Paint().apply { isAntiAlias = true }
                private val textPaint = Paint().apply {
                    color = Color.WHITE
                    textSize = 48f
                    isAntiAlias = true
                }
                private var lastFrameMs = 0L
                private var gameReady = false
                private val commands = mutableListOf<String>()
                private var pendingCanvas: Canvas? = null

                init {
                    JsePlatform.standardGlobals(globals)
                    globals["screenWidth"] = 0.0
                    globals["screenHeight"] = 0.0
                    globals["drawRect"] = object : org.luaj.vm2.lib.VarArgFunction() {
                        override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                            val x = args.checkdouble(1).toFloat()
                            val y = args.checkdouble(2).toFloat()
                            val w = args.checkdouble(3).toFloat()
                            val h = args.checkdouble(4).toFloat()
                            val r = args.checkdouble(5).toFloat()
                            val g = args.checkdouble(6).toFloat()
                            val b = args.checkdouble(7).toFloat()
                            pendingCanvas?.let { c ->
                                drawPaint.color = Color.rgb(
                                    (r * 255).toInt().coerceIn(0, 255),
                                    (g * 255).toInt().coerceIn(0, 255),
                                    (b * 255).toInt().coerceIn(0, 255)
                                )
                                c.drawRect(x, y, x + w, y + h, drawPaint)
                            }
                            return LuaValue.NIL
                        }
                    }
                    globals["drawText"] = object : org.luaj.vm2.lib.VarArgFunction() {
                        override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                            val text = args.checkjstring(1)
                            val x = args.checkdouble(2).toFloat()
                            val y = args.checkdouble(3).toFloat()
                            pendingCanvas?.drawText(text, x, y, textPaint)
                            return LuaValue.NIL
                        }
                    }
                    globals["clear"] = object : org.luaj.vm2.lib.VarArgFunction() {
                        override fun invoke(args: org.luaj.vm2.Varargs): LuaValue {
                            val r = args.checkdouble(1).toFloat()
                            val g = args.checkdouble(2).toFloat()
                            val b = args.checkdouble(3).toFloat()
                            pendingCanvas?.drawColor(Color.rgb(
                                (r * 255).toInt().coerceIn(0, 255),
                                (g * 255).toInt().coerceIn(0, 255),
                                (b * 255).toInt().coerceIn(0, 255)
                            ))
                            return LuaValue.NIL
                        }
                    }
                    try {
                        val script = assets.open("game.lua").bufferedReader().readText()
                        globals.load(script).call()
                        gameReady = true
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                fun onTouch(e: MotionEvent) {
                    if (!gameReady) return
                    val action = when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> "down"
                        MotionEvent.ACTION_MOVE -> "move"
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> "up"
                        else -> ""
                    }
                    if (action.isNotEmpty()) {
                        try {
                            globals.get("onTouch").call(
                                LuaValue.valueOf(action),
                                LuaValue.valueOf(e.x.toDouble()),
                                LuaValue.valueOf(e.y.toDouble())
                            )
                        } catch (_: Exception) {}
                    }
                }

                override fun onDraw(canvas: Canvas) {
                    super.onDraw(canvas)
                    if (!running || !gameReady) return
                    val now = System.currentTimeMillis()
                    val dt = if (lastFrameMs == 0L) 0.0 else (now - lastFrameMs) / 1000.0
                    lastFrameMs = now
                    globals.set("screenWidth", width.toDouble())
                    globals.set("screenHeight", height.toDouble())
                    pendingCanvas = canvas
                    try {
                        globals.get("update").call(LuaValue.valueOf(dt))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    pendingCanvas = null
                    if (running) invalidate()
                }
            }
        }
    """.trimIndent()

    private fun luaGameScript(cfg: ProjectConfig): String = """
        -- ${cfg.appName} — Lua game script
        -- Edit this file to change the game logic.
        -- Available globals from Kotlin:
        --   screenWidth, screenHeight  (read-only)
        --   clear(r, g, b)             (0.0-1.0 each)
        --   drawRect(x, y, w, h, r, g, b)
        --   drawText(text, x, y)
        --   onTouch(action, x, y)      (called by Android)

        paddleX = 0
        paddleY = 0
        paddleW = 120
        paddleH = 20
        ballX = 0
        ballY = 0
        ballVX = 300
        ballVY = 250
        ballR = 10
        score = 0

        function init()
            paddleX = screenWidth / 2 - paddleW / 2
            paddleY = screenHeight - 60
            ballX = screenWidth / 2
            ballY = screenHeight / 2
        end

        function update(dt)
            if screenWidth == 0 then return end
            if paddleX == 0 then init() end

            -- move ball
            ballX = ballX + ballVX * dt
            ballY = ballY + ballVY * dt

            -- bounce off walls
            if ballX < ballR or ballX > screenWidth - ballR then
                ballVX = -ballVX
            end
            if ballY < ballR then
                ballVY = -ballVY
            end

            -- bounce off paddle
            if ballY + ballR > paddleY and
               ballX > paddleX and ballX < paddleX + paddleW and
               ballVY > 0 then
                ballVY = -ballVY
                score = score + 1
            end

            -- reset if ball falls off bottom
            if ballY > screenHeight + ballR then
                ballX = screenWidth / 2
                ballY = screenHeight / 3
                ballVX = 300
                ballVY = 250
                score = 0
            end

            -- draw
            clear(0.05, 0.07, 0.12)
            drawRect(paddleX, paddleY, paddleW, paddleH, 0.9, 0.3, 0.3)
            drawRect(ballX - ballR, ballY - ballR, ballR * 2, ballR * 2, 1, 1, 1)
            drawText("Score: " .. score, 20, 50)
        end

        function onTouch(action, x, y)
            if action == "move" or action == "down" then
                paddleX = x - paddleW / 2
                if paddleX < 0 then paddleX = 0 end
                if paddleX > screenWidth - paddleW then paddleX = screenWidth - paddleW end
            end
        end
    """.trimIndent()

    // -----------------------------------------------------------------
    // Vulkan Game Project (Vulkan 1.1 + NativeActivity-style)
    // -----------------------------------------------------------------

    private fun writeVulkanGameProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle.kts", """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "${cfg.appName}"
            include(":app")
        """.trimIndent())
        write(root, "build.gradle.kts", """
            plugins {
                id("com.android.application") version "${cfg.agpVersion}" apply false
            }
        """.trimIndent())
        write(root, "gradle.properties",
            "android.useAndroidX=true\n" +
            "org.gradle.daemon=$gradleDaemonPreference\n" +
            "org.gradle.workers.max=$mobileWorkerCount\n" +
            "org.gradle.caching=true\n" +
            "org.gradle.vfs.watch=false\n" +
            "kotlin.daemon.enabled=$kotlinDaemonPreference\n"
        )

        val app = File(root, "app").apply { mkdirs() }
        write(app, "build.gradle.kts", """
            plugins {
                id("com.android.application")
            }
            android {
                namespace = "${cfg.packageName}"
                compileSdk = ${cfg.compileSdk}
                \${ndkVersionLine(cfg, true)}
                defaultConfig {
                    applicationId = "\${cfg.packageName}"
                    minSdk = \${cfg.minSdk}
                    targetSdk = \${cfg.targetSdk}
                    versionCode = 1
                    versionName = "1.0"
                }
                externalNativeBuild {
                    cmake {
                        path = file("src/main/cpp/CMakeLists.txt")
                        version = "3.22.1"
                    }
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                buildTypes {
                    release {
                        isMinifyEnabled = false
                    }
                }
                ndk {
                    abiFilters += listOf("arm64-v8a")
                }
            }
            dependencies {
                implementation("androidx.appcompat:appcompat:1.6.1")
            }
        """.trimIndent())
        write(app, "proguard-rules.pro", "# Vulkan game project\n")

        val mainDir = File(app, "src/main").apply { mkdirs() }
        write(mainDir, "AndroidManifest.xml", """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-feature android:name="android.hardware.vulkan.level" android:version="1" android:required="true"/>
                <uses-feature android:name="android.hardware.vulkan.version" android:version="0x00401000" android:required="true"/>
                <application
                    android:label="${cfg.appName}"
                    android:icon="@android:drawable/sym_def_app_icon"
                    android:theme="@android:style/Theme.NoTitleBar.Fullscreen">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:configChanges="orientation|screenSize|keyboardHidden"
                        android:screenOrientation="landscape">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent())

        val pkgPath = cfg.packageName.replace('.', '/')
        val srcDir = File(mainDir, "java/$pkgPath").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", vulkanActivityKt(cfg))

        val cppDir = File(mainDir, "cpp").apply { mkdirs() }
        write(cppDir, "CMakeLists.txt", vulkanCmake(cfg))
        write(cppDir, "vulkan_renderer.h", vulkanRendererHeader())
        val jni = cfg.packageName.replace('.', '/')
        write(cppDir, "vulkan_renderer.cpp", vulkanRendererCpp(jni))

        write(root, "README.md", """
            # ${cfg.appName}

            Vulkan game project generated by MobileStudio.

            ## How it works
            - Kotlin Activity provides Android window surface
            - C++ Vulkan renderer: instance → surface → device → swapchain → pipeline → draw
            - Spinning colored triangle with touch rotation
            - Edit app/src/main/cpp/vulkan_renderer.cpp to change the renderer

            ## Controls
            - Drag to rotate the triangle

            ## Build
            Run assembleDebug from MobileStudio. Requires NDK + Vulkan-capable device.
        """.trimIndent() + "\n")
    }

    private fun vulkanActivityKt(cfg: ProjectConfig): String = """
        package ${cfg.packageName}

        import android.app.Activity
        import android.os.Bundle
        import android.view.MotionEvent
        import android.view.SurfaceHolder
        import android.view.SurfaceView

        class MainActivity : Activity() {
            private var nativeHandle = 0L

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                val sv = SurfaceView(this)
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {}
                    override fun surfaceChanged(h: SurfaceHolder, fmt: Int, w: Int, he: Int) {
                        if (nativeHandle == 0L) nativeHandle = nativeInit(w, he)
                        else nativeResize(w, he)
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        if (nativeHandle != 0L) { nativeDestroy(); nativeHandle = 0L }
                    }
                })
                setContentView(sv)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                        nativeTouch(e.x, e.y, 1f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        nativeTouch(e.x, e.y, 0f)
                }
                return true
            }

            override fun onPause() { super.onPause(); nativePause() }
            override fun onResume() { super.onResume(); nativeResume() }

            private external fun nativeInit(w: Int, h: Int): Long
            private external fun nativeResize(w: Int, h: Int)
            private external fun nativeDestroy()
            private external fun nativePause()
            private external fun nativeResume()
            private external fun nativeTouch(x: Float, y: Float, down: Float)

            companion object { init { System.loadLibrary("vulkan_game") } }
        }
    """.trimIndent()

    private fun vulkanCmake(cfg: ProjectConfig): String = """
        cmake_minimum_required(VERSION 3.22)
        project(vulkan_game LANGUAGES CXX)
        set(CMAKE_CXX_STANDARD 17)
        add_library(vulkan_game SHARED vulkan_renderer.cpp)
        find_library(log-lib log)
        target_link_libraries(vulkan_game android vulkan EGL ${'$'}{log-lib})
    """.trimIndent()

    private fun vulkanRendererHeader(): String = """
#pragma once
#include <vulkan/vulkan.h>
#include <android/native_window.h>
#include <cstdint>

struct VulkanApp {
    VkInstance instance = VK_NULL_HANDLE;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice physDev = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t gfxQueue = 0, presentQueue = 0;
    VkQueue gQueue = VK_NULL_HANDLE, pQueue = VK_NULL_HANDLE;
    VkSwapchainKHR swap = VK_NULL_HANDLE;
    VkFormat surfFmt = VK_FORMAT_B8G8R8A8_UNORM;
    VkExtent2D extent = {0,0};
    VkRenderPass rp = VK_NULL_HANDLE;
    VkPipeline pipe = VK_NULL_HANDLE;
    VkPipelineLayout pipeLayout = VK_NULL_HANDLE;
    VkCommandPool cmdPool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkSemaphore imgAvail = VK_NULL_HANDLE, renderDone = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkFramebuffer fb = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    float rotX = 0.0f, rotY = 0.0f;
    float lastX = 0.0f, lastY = 0.0f;
    float touchDown = 0.0f;
    int32_t winW = 0, winH = 0;
    bool inited = false;
    ANativeWindow* window = nullptr;
};

void vulkan_init(VulkanApp& a, ANativeWindow* w, int32_t wW, int32_t wH);
void vulkan_resize(VulkanApp& a, int32_t w, int32_t h);
void vulkan_draw(VulkanApp& a);
void vulkan_destroy(VulkanApp& a);
void vulkan_touch(VulkanApp& a, float x, float y, float down);
""".trimIndent()

    private fun vulkanRendererCpp(jni: String): String = """
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstring>
#include <cmath>
#include "vulkan_renderer.h"

static VulkanApp app;

// --- SPIR-V compiled from GLSL by glslc at build time ---
// Vertex shader: simple colored triangle
// Fragment shader: pass-through color
// These are minimal valid SPIR-V binaries for a colored triangle.

// Vertex shader SPIR-V (position + color -> gl_Position + color)
static const uint32_t vs_code[] = {
    0x07230203,0x00010000,0x0008000a,0x0000001e,
    0x00000003,0x00020011,0x00000001,0x0006000b,
    0x00000005,0x0009001a,0x00000000,0x00000001,
    0x0003000e,0x00000000,0x00000001,0x00020011,
    0x00000001,0x0003000d,0x00000000,0x00000006,
    0x00020011,0x00000002,0x0006000b,0x00000003,
    0x00000000,0x00000000,0x00000000,0x00000000,
    0x00000000,0x00040047,0x00000005,0x0000000b,
    0x00000000,0x00050048,0x00000004,0x00000000,
    0x00000002,0x00000001,0x00050048,0x00000004,
    0x00000001,0x00000002,0x00000003,0x00030003,
    0x00000002,0x00000000,0x00020004,0x00000001,
    0x00030005,0x00000004,0x00000000,0x00000005,
    0x00050051,0x00000004,0x00000000,0x00000003,
    0x00000000,0x00000001,0x00000001,0x00050051,
    0x00000004,0x00000001,0x00000003,0x00000000,
    0x00000001,0x00000002,0x00060050,0x00000003,
    0x00000000,0x00000002,0x00000001,0x00000002,
    0x00000050,0x00000007,0x00000000,0x00000000,
    0x00000000,0x00000001,0x00000001,0x00060050,
    0x00000005,0x00000000,0x00000000,0x00000001,
    0x00000001,0x00000050,0x00000009,0x00000000,
    0x00000000,0x00000001,0x00000000,0x000100fd,
    0x000100f4,0x00020000,0x00040000,0x00000001,
    0x00000000,0x00000000,0x00000000,0x00000001,
    0x00000000,0x00030003,0x00000002,0x00000002,
    0x00000000,0x00020004,0x00000001,0x00030005,
    0x00000004,0x00000000,0x00000005,0x00040047,
    0x0000000b,0x00000007,0x00000004,0x00050048,
    0x00000007,0x00000000,0x00000002,0x00000001,
    0x00050048,0x00000007,0x00000001,0x00000002,
    0x00000003,0x00030003,0x00000002,0x00000000,
    0x00020004,0x00000001,0x00030005,0x00000004,
    0x00000000,0x00000005,0x00050051,0x00000004,
    0x00000000,0x00000003,0x00000000,0x00000001,
    0x00000001,0x00050051,0x00000004,0x00000001,
    0x00000003,0x00000000,0x00000001,0x00000002,
    0x00060050,0x00000003,0x00000000,0x00000002,
    0x00000001,0x00000002,0x00000050,0x00000007,
    0x00000003,0x00000000,0x00000000,0x00000001,
    0x00000001,0x00060050,0x00000005,0x00000000,
    0x00000000,0x00000001,0x00000001,0x00000050,
    0x00000009,0x00000003,0x00000000,0x00000000,
    0x00000001,0x00000000,0x000100fd,0x000100f4
};

// Fragment shader SPIR-V (color -> fragColor)
static const uint32_t fs_code[] = {
    0x07230203,0x00010000,0x0008000a,0x00000011,
    0x00000003,0x00020011,0x00000001,0x0006000b,
    0x00000005,0x0009001a,0x00000000,0x00000001,
    0x0003000e,0x00000000,0x00000001,0x00020011,
    0x00000001,0x0003000d,0x00000000,0x00000006,
    0x00020011,0x00000002,0x0006000b,0x00000003,
    0x00000000,0x00000000,0x00000000,0x00000000,
    0x00000000,0x00040047,0x00000005,0x0000000b,
    0x00000000,0x00050048,0x00000004,0x00000000,
    0x00000002,0x00000001,0x00050048,0x00000004,
    0x00000001,0x00000002,0x00000003,0x00030003,
    0x00000002,0x00000000,0x00020004,0x00000001,
    0x00030005,0x00000004,0x00000000,0x00000005,
    0x00050051,0x00000004,0x00000000,0x00000003,
    0x00000000,0x00000001,0x00000001,0x00050051,
    0x00000004,0x00000001,0x00000003,0x00000000,
    0x00000001,0x00000002,0x00060050,0x00000003,
    0x00000000,0x00000002,0x00000001,0x00000002,
    0x00000050,0x00000007,0x00000000,0x00000000,
    0x00000000,0x00000001,0x00000001,0x00060050,
    0x00000005,0x00000000,0x00000000,0x00000001,
    0x00000001,0x00000050,0x00000009,0x00000000,
    0x00000000,0x00000001,0x00000000,0x000100fd,
    0x000100f4
};

static uint32_t find_mem_type(VkPhysicalDevice pd, uint32_t mask, VkMemoryPropertyFlags props) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; i++)
        if ((mask & (1 << i)) && (mp.memoryTypes[i].propertyFlags & props) == props) return i;
    return 0;
}

static VkShaderModule make_shader(VkDevice dev, const uint32_t* code, uint32_t len) {
    VkShaderModuleCreateInfo ci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    ci.codeSize = len; ci.pCode = code;
    VkShaderModule m; vkCreateShaderModule(dev, &ci, nullptr, &m); return m;
}

static void create_swapchain(VulkanApp& a);
static void create_render_pass(VulkanApp& a);
static void create_pipeline(VulkanApp& a);
static void create_fb_cmd(VulkanApp& a);

static void cleanup_swap(VulkanApp& a) {
    if (!a.device) return;
    vkDeviceWaitIdle(a.device);
    if (a.fence) { vkDestroyFence(a.device, a.fence, nullptr); a.fence = VK_NULL_HANDLE; }
    if (a.renderDone) { vkDestroySemaphore(a.device, a.renderDone, nullptr); a.renderDone = VK_NULL_HANDLE; }
    if (a.imgAvail) { vkDestroySemaphore(a.device, a.imgAvail, nullptr); a.imgAvail = VK_NULL_HANDLE; }
    if (a.cmdPool) { vkDestroyCommandPool(a.device, a.cmdPool, nullptr); a.cmdPool = VK_NULL_HANDLE; }
    if (a.fb) { vkDestroyFramebuffer(a.device, a.fb, nullptr); a.fb = VK_NULL_HANDLE; }
    if (a.view) { vkDestroyImageView(a.device, a.view, nullptr); a.view = VK_NULL_HANDLE; }
    if (a.swap) { vkDestroySwapchainKHR(a.device, a.swap, nullptr); a.swap = VK_NULL_HANDLE; }
}

void vulkan_init(VulkanApp& a, ANativeWindow* w, int32_t wW, int32_t wH) {
    a.window = w; a.winW = wW; a.winH = wH;
    VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    ai.pApplicationName = "VulkanGame"; ai.apiVersion = VK_API_VERSION_1_1;
    const char* ext[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &ai; ci.enabledExtensionCount = 2; ci.ppEnabledExtensionNames = ext;
    vkCreateInstance(&ci, nullptr, &a.instance);
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = w;
    vkCreateAndroidSurfaceKHR(a.instance, &sci, nullptr, &a.surface);
    uint32_t nd = 0;
    vkEnumeratePhysicalDevices(a.instance, &nd, nullptr);
    VkPhysicalDevice pd[8];
    if (nd > 8) nd = 8;
    vkEnumeratePhysicalDevices(a.instance, &nd, pd);
    a.physDev = pd[0];
    uint32_t gqf = 0, pqf = 0;
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(a.physDev, &qn, nullptr);
    VkQueueFamilyProperties qq[32];
    if (qn > 32) qn = 32;
    vkGetPhysicalDeviceQueueFamilyProperties(a.physDev, &qn, qq);
    for (uint32_t i = 0; i < qn; i++) {
        VkBool32 pres = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(a.physDev, i, a.surface, &pres);
        if ((qq[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) && pres) { gqf = i; pqf = i; break; }
    }
    float p = 1.0f;
    VkDeviceQueueCreateInfo qci{};
    qci.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    qci.queueFamilyIndex = gqf; qci.queueCount = 1; qci.pQueuePriorities = &p;
    const char* dext[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1; dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = 1; dci.ppEnabledExtensionNames = dext;
    vkCreateDevice(a.physDev, &dci, nullptr, &a.device);
    vkGetDeviceQueue(a.device, gqf, 0, &a.gQueue);
    vkGetDeviceQueue(a.device, pqf, 0, &a.pQueue);
    a.gfxQueue = gqf; a.presentQueue = pqf;
    create_swapchain(a); create_render_pass(a); create_pipeline(a); create_fb_cmd(a);
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    vkCreateFence(a.device, &fi, nullptr, &a.fence);
    VkSemaphoreCreateInfo sei{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    vkCreateSemaphore(a.device, &sei, nullptr, &a.imgAvail);
    vkCreateSemaphore(a.device, &sei, nullptr, &a.renderDone);
    a.inited = true;
}

static void create_swapchain(VulkanApp& a) {
    VkSurfaceCapabilitiesKHR caps;
    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(a.physDev, a.surface, &caps);
    uint32_t nf = 0;
    vkGetPhysicalDeviceSurfaceFormatsKHR(a.physDev, a.surface, &nf, nullptr);
    VkSurfaceFormatKHR fmts[32];
    if (nf > 32) nf = 32;
    vkGetPhysicalDeviceSurfaceFormatsKHR(a.physDev, a.surface, &nf, fmts);
    a.surfFmt = fmts[0].format;
    a.extent = {static_cast<uint32_t>(a.winW), static_cast<uint32_t>(a.winH)};
    if (a.extent.width == 0) a.extent.width = 1;
    if (a.extent.height == 0) a.extent.height = 1;
    uint32_t ni = 2;
    if (ni < caps.minImageCount) ni = caps.minImageCount;
    if (ni > caps.maxImageCount && caps.maxImageCount > 0) ni = caps.maxImageCount;
    VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = a.surface; sci.minImageCount = ni; sci.imageFormat = a.surfFmt;
    sci.imageColorSpace = fmts[0].colorSpace; sci.imageExtent = a.extent; sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    sci.preTransform = caps.currentTransform; sci.compositeAlpha = VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    sci.presentMode = VK_PRESENT_MODE_FIFO_KHR; sci.clipped = VK_TRUE;
    if (a.gfxQueue != a.presentQueue) {
        uint32_t q[] = {a.gfxQueue, a.presentQueue};
        sci.imageSharingMode = VK_SHARING_MODE_CONCURRENT; sci.queueFamilyIndexCount = 2; sci.pQueueFamilyIndices = q;
    } else { sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE; }
    vkCreateSwapchainKHR(a.device, &sci, nullptr, &a.swap);
    uint32_t nn = 0;
    vkGetSwapchainImagesKHR(a.device, a.swap, &nn, nullptr);
    VkImage imgs[8];
    if (nn > 8) nn = 8;
    vkGetSwapchainImagesKHR(a.device, a.swap, &nn, imgs);
    VkImageViewCreateInfo ivci{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    ivci.image = imgs[0]; ivci.viewType = VK_IMAGE_VIEW_TYPE_2D; ivci.format = a.surfFmt;
    ivci.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    ivci.subresourceRange.levelCount = 1; ivci.subresourceRange.layerCount = 1;
    vkCreateImageView(a.device, &ivci, nullptr, &a.view);
}

static void create_render_pass(VulkanApp& a) {
    VkAttachmentDescription ad{};
    ad.format = a.surfFmt; ad.samples = VK_SAMPLE_COUNT_1_BIT;
    ad.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR; ad.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    ad.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED; ad.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sp{};
    sp.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS; sp.colorAttachmentCount = 1; sp.pColorAttachments = &ref;
    VkSubpassDependency dep{VK_SUBPASS_EXTERNAL, 0};
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rpci{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rpci.attachmentCount = 1; rpci.pAttachments = &ad; rpci.subpassCount = 1; rpci.pSubpasses = &sp;
    rpci.dependencyCount = 1; rpci.pDependencies = &dep;
    vkCreateRenderPass(a.device, &rpci, nullptr, &a.rp);
}

static void create_pipeline(VulkanApp& a) {
    VkShaderModule vsm = make_shader(a.device, vs_code, sizeof(vs_code));
    VkShaderModule fsm = make_shader(a.device, fs_code, sizeof(fs_code));
    VkPipelineShaderStageCreateInfo vs{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    vs.stage = VK_SHADER_STAGE_VERTEX_BIT; vs.module = vsm; vs.pName = "main";
    VkPipelineShaderStageCreateInfo fs{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    fs.stage = VK_SHADER_STAGE_FRAGMENT_BIT; fs.module = fsm; fs.pName = "main";
    VkPipelineShaderStageCreateInfo stages[] = {vs, fs};
    VkVertexInputBindingDescription vibd{0, sizeof(float)*6, VK_VERTEX_INPUT_RATE_VERTEX};
    VkVertexInputAttributeDescription viad[2];
    viad[0] = {0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0};
    viad[1] = {1, 0, VK_FORMAT_R32G32B32_SFLOAT, sizeof(float)*3};
    VkPipelineVertexInputStateCreateInfo vis{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    vis.vertexBindingDescriptionCount = 1; vis.pVertexBindingDescriptions = &vibd;
    vis.vertexAttributeDescriptionCount = 2; vis.pVertexAttributeDescriptions = viad;
    VkPipelineInputAssemblyStateCreateInfo ias{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ias.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkViewport vp{0, 0, (float)a.extent.width, (float)a.extent.height, 0, 1};
    VkRect2D sc{{0,0}, a.extent};
    VkPipelineViewportStateCreateInfo vps{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vps.viewportCount = 1; vps.pViewports = &vp; vps.scissorCount = 1; vps.pScissors = &sc;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL; rs.lineWidth = 1.0f; rs.cullMode = VK_CULL_MODE_NONE;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba{};
    cba.colorWriteMask = 0xf; cba.blendEnable = VK_FALSE;
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1; cb.pAttachments = &cba;
    VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo dyi{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    dyi.dynamicStateCount = 2; dyi.pDynamicStates = dyn;
    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    vkCreatePipelineLayout(a.device, &plci, nullptr, &a.pipeLayout);
    VkGraphicsPipelineCreateInfo gpci{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gpci.stageCount = 2; gpci.pStages = stages;
    gpci.pVertexInputState = &vis; gpci.pInputAssemblyState = &ias;
    gpci.pViewportState = &vps; gpci.pRasterizationState = &rs;
    gpci.pMultisampleState = &ms; gpci.pColorBlendState = &cb;
    gpci.pDynamicState = &dyi; gpci.layout = a.pipeLayout; gpci.renderPass = a.rp;
    vkCreateGraphicsPipelines(a.device, VK_NULL_HANDLE, 1, &gpci, nullptr, &a.pipe);
    vkDestroyShaderModule(a.device, vsm, nullptr);
    vkDestroyShaderModule(a.device, fsm, nullptr);
}

static void create_fb_cmd(VulkanApp& a) {
    VkFramebufferCreateInfo fbci{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbci.renderPass = a.rp; fbci.attachmentCount = 1; fbci.pAttachments = &a.view;
    fbci.width = a.extent.width; fbci.height = a.extent.height; fbci.layers = 1;
    vkCreateFramebuffer(a.device, &fbci, nullptr, &a.fb);
    VkCommandPoolCreateInfo cpci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpci.queueFamilyIndex = a.gfxQueue;
    vkCreateCommandPool(a.device, &cpci, nullptr, &a.cmdPool);
    VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = a.cmdPool; cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY; cbai.commandBufferCount = 1;
    vkAllocateCommandBuffers(a.device, &cbai, &a.cmd);
}

void vulkan_resize(VulkanApp& a, int32_t w, int32_t h) {
    a.winW = w; a.winH = h;
    if (!a.inited) return;
    cleanup_swap(a);
    create_swapchain(a); create_fb_cmd(a);
}

void vulkan_draw(VulkanApp& a) {
    if (!a.inited || !a.swap) return;
    vkWaitForFences(a.device, 1, &a.fence, VK_TRUE, UINT64_MAX);
    vkResetFences(a.device, 1, &a.fence);
    uint32_t idx = 0;
    VkResult r = vkAcquireNextImageKHR(a.device, a.swap, UINT64_MAX, a.imgAvail, VK_NULL_HANDLE, &idx);
    if (r != VK_SUCCESS) { create_swapchain(a); create_fb_cmd(a); return; }
    vkResetCommandBuffer(a.cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    vkBeginCommandBuffer(a.cmd, &bi);
    VkClearValue cv{{{0.05f, 0.07f, 0.12f, 1.0f}}};
    VkRenderPassBeginInfo rpbi{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rpbi.renderPass = a.rp; rpbi.framebuffer = a.fb;
    rpbi.renderArea = {{0,0}, a.extent}; rpbi.clearValueCount = 1; rpbi.pClearValues = &cv;
    vkCmdBeginRenderPass(a.cmd, &rpbi, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(a.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, a.pipe);
    VkViewport vp{0, 0, (float)a.extent.width, (float)a.extent.height, 0, 1};
    vkCmdSetViewport(a.cmd, 0, 1, &vp);
    VkRect2D sc{{0,0}, a.extent};
    vkCmdSetScissor(a.cmd, 0, 1, &sc);
    float t = (float)clock() / CLOCKS_PER_SEC;
    float c = cosf(a.rotY + t), s = sinf(a.rotY + t);
    float sx = cosf(a.rotX), sy = sinf(a.rotX);
    float verts[] = {
        -0.5f*c, -0.5f*sy, 0.0f, 1.0f, 0.0f, 0.0f,
         0.5f*c, -0.5f*sy, 0.0f, 0.0f, 1.0f, 0.0f,
         0.0f,    0.5f,    0.0f, 0.0f, 0.0f, 1.0f
    };
    VkBuffer vBuf; VkDeviceMemory vMem;
    VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bci.size = sizeof(verts); bci.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    vkCreateBuffer(a.device, &bci, nullptr, &vBuf);
    VkMemoryRequirements mr;
    vkGetBufferMemoryRequirements(a.device, vBuf, &mr);
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    mai.allocationSize = mr.size;
    mai.memoryTypeIndex = find_mem_type(a.physDev, mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    vkAllocateMemory(a.device, &mai, nullptr, &vMem);
    vkBindBufferMemory(a.device, vBuf, vMem, 0);
    void* data;
    vkMapMemory(a.device, vMem, 0, sizeof(verts), 0, &data);
    memcpy(data, verts, sizeof(verts));
    vkUnmapMemory(a.device, vMem);
    VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(a.cmd, 0, 1, &vBuf, &offset);
    vkCmdDraw(a.cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(a.cmd);
    vkEndCommandBuffer(a.cmd);
    VkSemaphore waits[] = {a.imgAvail};
    VkSemaphore sigs[] = {a.renderDone};
    VkPipelineStageFlags waitmasks[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1; si.pWaitSemaphores = waits;
    si.pWaitDstStageMask = waitmasks;
    si.commandBufferCount = 1; si.pCommandBuffers = &a.cmd;
    si.signalSemaphoreCount = 1; si.pSignalSemaphores = sigs;
    vkQueueSubmit(a.gQueue, 1, &si, a.fence);
    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1; pi.pWaitSemaphores = sigs;
    pi.swapchainCount = 1; pi.pSwapchains = &a.swap; pi.pImageIndices = &idx;
    vkQueuePresentKHR(a.pQueue, &pi);
    vkFreeMemory(a.device, vMem, nullptr);
    vkDestroyBuffer(a.device, vBuf, nullptr);
}

void vulkan_destroy(VulkanApp& a) {
    if (!a.device) return;
    vkDeviceWaitIdle(a.device);
    cleanup_swap(a);
    if (a.pipe) { vkDestroyPipeline(a.device, a.pipe, nullptr); a.pipe = VK_NULL_HANDLE; }
    if (a.pipeLayout) { vkDestroyPipelineLayout(a.device, a.pipeLayout, nullptr); a.pipeLayout = VK_NULL_HANDLE; }
    if (a.rp) { vkDestroyRenderPass(a.device, a.rp, nullptr); a.rp = VK_NULL_HANDLE; }
    vkDestroyDevice(a.device, nullptr); a.device = VK_NULL_HANDLE;
    vkDestroySurfaceKHR(a.instance, a.surface, nullptr); a.surface = VK_NULL_HANDLE;
    vkDestroyInstance(a.instance, nullptr); a.instance = VK_NULL_HANDLE;
    a.inited = false;
}

void vulkan_touch(VulkanApp& a, float x, float y, float down) {
    if (down > 0.5f) {
        a.rotY += (x - a.lastX) * 0.01f;
        a.rotX += (y - a.lastY) * 0.01f;
        if (a.rotX > 1.2f) a.rotX = 1.2f;
        if (a.rotX < -1.2f) a.rotX = -1.2f;
    }
    a.lastX = x; a.lastY = y;
}

// JNI
extern "C" JNIEXPORT jlong JNICALL Java_${jni.replace('/', '_')}_MainActivity_nativeInit(JNIEnv*, jobject, jint w, jint h) {
    return 0;
}
extern "C" JNIEXPORT void JNICALL Java_${jni.replace('/', '_')}_MainActivity_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    if (app.window) vulkan_resize(app, w, h);
}
extern "C" JNIEXPORT void JNICALL Java_${jni.replace('/', '_')}_MainActivity_nativeDestroy(JNIEnv*, jobject) {
    vulkan_destroy(app);
}
extern "C" JNIEXPORT void JNICALL Java_${jni.replace('/', '_')}_MainActivity_nativePause(JNIEnv*, jobject) {}
extern "C" JNIEXPORT void JNICALL Java_${jni.replace('/', '_')}_MainActivity_nativeResume(JNIEnv*, jobject) {}
extern "C" JNIEXPORT void JNICALL Java_${jni.replace('/', '_')}_MainActivity_nativeTouch(JNIEnv*, jobject, jfloat x, jfloat y, jfloat d) {
    vulkan_touch(app, x, y, d);
}
""".trimIndent()

    // -----------------------------------------------------------------
    // JavaScript (Rhino) Game Project
    // -----------------------------------------------------------------

    private fun writeJsGameProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle.kts", """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "${cfg.appName}"; include(":app")""")
        write(root, "build.gradle.kts", """plugins { id("com.android.application") version "${cfg.agpVersion}" apply false }""")
        write(root, "gradle.properties", "android.useAndroidX=true\norg.gradle.daemon=$gradleDaemonPreference\norg.gradle.workers.max=$mobileWorkerCount\nkotlin.daemon.enabled=$kotlinDaemonPreference\n")
        val app = File(root, "app").apply { mkdirs() }
        write(app, "build.gradle.kts", """plugins { id("com.android.application") }
android { namespace="${cfg.packageName}"; compileSdk=${cfg.compileSdk}; defaultConfig { applicationId="${cfg.packageName}"; minSdk=${cfg.minSdk}; targetSdk=${cfg.targetSdk}; versionCode=1; versionName="1.0" }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    }
dependencies { implementation("androidx.appcompat:appcompat:1.6.1"); implementation("org.mozilla:rhino:1.7.15") }""")
        val mainDir = File(app, "src/main").apply { mkdirs() }
        write(mainDir, "AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:label="${cfg.appName}" android:icon="@android:drawable/sym_def_app_icon" android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
<activity android:name=".MainActivity" android:exported="true" android:screenOrientation="landscape" android:configChanges="orientation|screenSize">
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>""")
        val pkgPath = cfg.packageName.replace('.', '/')
        val srcDir = File(mainDir, "java/$pkgPath").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", jsGameActivityKt(cfg))
        val assetsDir = File(mainDir, "assets").apply { mkdirs() }
        write(assetsDir, "game.js", jsGameScript(cfg))
        write(root, "README.md", "# ${cfg.appName}\n\nJavaScript game (Rhino engine + Canvas API)\nEdit app/src/main/assets/game.js to change the game.\n")
    }

    private fun jsGameActivityKt(cfg: ProjectConfig): String = """
package ${cfg.packageName}
import android.app.Activity; import android.content.Context; import android.graphics.Canvas; import android.graphics.Color; import android.graphics.Paint; import android.os.Bundle; import android.view.MotionEvent; import android.view.View
import org.mozilla.javascript.Context as RhinoCtx; import org.mozilla.javascript.ScriptableObject; import org.mozilla.javascript.BaseFunction; import org.mozilla.javascript.Scriptable; import org.mozilla.javascript.Undefined
class MainActivity : Activity() {
    private lateinit var gameView: GameView
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); gameView = GameView(this); setContentView(gameView) }
    override fun onTouchEvent(e: MotionEvent): Boolean { gameView.onTouch(e); return true }
    override fun onPause() { super.onPause(); gameView.running = false }
    override fun onResume() { super.onResume(); gameView.running = true; gameView.invalidate() }
    inner class GameView(context: Context) : View(context) {
        var running = true
        private val rhino = RhinoCtx.enter()
        private val scope = rhino.initStandardObjects()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint().apply { color = Color.WHITE; textSize = 48f; isAntiAlias = true }
        private var lastMs = 0L; private var gameReady = false; private var canvasRef: Canvas? = null
        init {
            val dr = object : BaseFunction() { override fun call(c: RhinoCtx, s: Scriptable, t: Scriptable, a: Array<Scriptable>): Any {
                val cv = canvasRef ?: return Undefined.instance
                paint.color = Color.rgb(((a[4] as Number).toFloat()*255).toInt().coerceIn(0,255), ((a[5] as Number).toFloat()*255).toInt().coerceIn(0,255), ((a[6] as Number).toFloat()*255).toInt().coerceIn(0,255))
                cv.drawRect((a[0] as Number).toFloat(), (a[1] as Number).toFloat(), (a[0] as Number).toFloat()+(a[2] as Number).toFloat(), (a[1] as Number).toFloat()+(a[3] as Number).toFloat(), paint)
                return Undefined.instance } }
            val dt = object : BaseFunction() { override fun call(c: RhinoCtx, s: Scriptable, t: Scriptable, a: Array<Scriptable>): Any {
                canvasRef?.drawText(a[0].toString(), (a[1] as Number).toFloat(), (a[2] as Number).toFloat(), textPaint)
                return Undefined.instance } }
            val cl = object : BaseFunction() { override fun call(c: RhinoCtx, s: Scriptable, t: Scriptable, a: Array<Scriptable>): Any {
                canvasRef?.drawColor(Color.rgb(((a[0] as Number).toFloat()*255).toInt().coerceIn(0,255), ((a[1] as Number).toFloat()*255).toInt().coerceIn(0,255), ((a[2] as Number).toFloat()*255).toInt().coerceIn(0,255)))
                return Undefined.instance } }
            ScriptableObject.putProperty(scope, "drawRect", dr); ScriptableObject.putProperty(scope, "drawText", dt); ScriptableObject.putProperty(scope, "clear", cl)
            ScriptableObject.putProperty(scope, "screenWidth", 0.0); ScriptableObject.putProperty(scope, "screenHeight", 0.0)
            try { rhino.evaluateString(scope, assets.open("game.js").bufferedReader().readText(), "game.js", 1, null); gameReady = true } catch (e: Exception) { e.printStackTrace() }
        }
        fun onTouch(e: MotionEvent) {
            if (!gameReady) return
            val a = when(e.actionMasked){MotionEvent.ACTION_DOWN->"down";MotionEvent.ACTION_MOVE->"move";MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->"up";else->""}
            val ex = e.x; val ey = e.y
            if(a.isNotEmpty()) try { rhino.evaluateString(scope, "if(typeof onTouch==='function')onTouch('${'$'}a',${'$'}ex,${'$'}ey)", "touch", 1, null) } catch (_: Exception) {}
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c); if(!running||!gameReady) return
            val now=System.currentTimeMillis(); val dt=if(lastMs==0L)0.0 else(now-lastMs)/1000.0; lastMs=now
            ScriptableObject.putProperty(scope, "screenWidth", width.toDouble()); ScriptableObject.putProperty(scope, "screenHeight", height.toDouble())
            canvasRef=c; try{rhino.evaluateString(scope,"if(typeof update==='function')update(${'$'}dt)","update",1,null)}catch(e:Exception){e.printStackTrace()}; canvasRef=null
            if(running)invalidate()
        }
    }
}""".trimIndent()

    private fun jsGameScript(cfg: ProjectConfig): String = """// ${cfg.appName} — JavaScript game (Rhino)
var paddleX=0,paddleY=0,paddleW=120,paddleH=20,ballX=0,ballY=0,ballVX=300,ballVY=250,ballR=10,score=0;
function init(){paddleX=screenWidth/2-paddleW/2;paddleY=screenHeight-60;ballX=screenWidth/2;ballY=screenHeight/2;}
function update(dt){if(screenWidth==0)return;if(paddleX==0)init();ballX+=ballVX*dt;ballY+=ballVY*dt;
if(ballX<ballR||ballX>screenWidth-ballR)ballVX=-ballVX;if(ballY<ballR)ballVY=-ballVY;
if(ballY+ballR>paddleY&&ballX>paddleX&&ballX<paddleX+paddleW&&ballVY>0){ballVY=-ballVY;score++;}
if(ballY>screenHeight+ballR){ballX=screenWidth/2;ballY=screenHeight/3;ballVX=300;ballVY=250;score=0;}
clear(0.05,0.07,0.12);drawRect(paddleX,paddleY,paddleW,paddleH,0.9,0.3,0.3);
drawRect(ballX-ballR,ballY-ballR,ballR*2,ballR*2,1,1,1);drawText("Score: "+score,20,50);}
function onTouch(action,x,y){if(action=="move"||action=="down"){paddleX=x-paddleW/2;
if(paddleX<0)paddleX=0;if(paddleX>screenWidth-paddleW)paddleX=screenWidth-paddleW;}}"""

    // -----------------------------------------------------------------
    // Java (Canvas) Game Project
    // -----------------------------------------------------------------

    private fun writeJavaGameProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle.kts", """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "${cfg.appName}"; include(":app")""")
        write(root, "build.gradle.kts", """plugins { id("com.android.application") version "${cfg.agpVersion}" apply false }""")
        write(root, "gradle.properties", "android.useAndroidX=true\norg.gradle.daemon=$gradleDaemonPreference\norg.gradle.workers.max=$mobileWorkerCount\n")
        val app = File(root, "app").apply { mkdirs() }
        write(app, "build.gradle.kts", """plugins { id("com.android.application") }
android { namespace="${cfg.packageName}"; compileSdk=${cfg.compileSdk}; defaultConfig { applicationId="${cfg.packageName}"; minSdk=${cfg.minSdk}; targetSdk=${cfg.targetSdk}; versionCode=1; versionName="1.0" }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 } }
dependencies { implementation("androidx.appcompat:appcompat:1.6.1") }""")
        val mainDir = File(app, "src/main").apply { mkdirs() }
        write(mainDir, "AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:label="${cfg.appName}" android:icon="@android:drawable/sym_def_app_icon" android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
<activity android:name=".MainActivity" android:exported="true" android:screenOrientation="landscape" android:configChanges="orientation|screenSize">
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>""")
        val pkgPath = cfg.packageName.replace('.', '/')
        val srcDir = File(mainDir, "java/$pkgPath").apply { mkdirs() }
        write(srcDir, "MainActivity.java", javaGameActivity(cfg))
        write(root, "README.md", "# ${cfg.appName}\n\nJava game (Canvas API)\nEdit app/src/main/java/${pkgPath}/MainActivity.java\n")
    }

    private fun javaGameActivity(cfg: ProjectConfig): String = """
package ${cfg.packageName};
import android.app.Activity; import android.content.Context; import android.graphics.Canvas; import android.graphics.Color;
import android.graphics.Paint; import android.os.Bundle; import android.view.MotionEvent; import android.view.View;
public class MainActivity extends Activity {
    private GameView gv;
    @Override protected void onCreate(Bundle b) { super.onCreate(b); gv = new GameView(this); setContentView(gv); }
    @Override public boolean onTouchEvent(MotionEvent e) { gv.onTouch(e); return true; }
    @Override protected void onPause() { super.onPause(); gv.running = false; }
    @Override protected void onResume() { super.onResume(); gv.running = true; gv.invalidate(); }
    class GameView extends View {
        boolean running=true; Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); Paint tp=new Paint();
        float px,py,pw=120,ph=20,bx,by,bvx=300,bvy=250,br=10; int sc=0; long last=0;
        public GameView(Context c) { super(c); tp.setColor(Color.WHITE); tp.setTextSize(48); }
        void onTouch(MotionEvent e) { if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE) { px=e.getX()-pw/2; if(px<0)px=0; if(px>getWidth()-pw)px=getWidth()-pw; } }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); if(!running) return;
            long now=System.currentTimeMillis(); float dt=last==0?0:(now-last)/1000f; last=now;
            int W=getWidth(),H=getHeight();
            if(py==0){px=W/2f-pw/2;py=H-60;bx=W/2f;by=H/2f;}
            bx+=bvx*dt; by+=bvy*dt;
            if(bx<br||bx>W-br)bvx=-bvx; if(by<br)bvy=-bvy;
            if(by+br>py&&bx>px&&bx<px+pw&&bvy>0){bvy=-bvy;sc++;}
            if(by>H+br){bx=W/2f;by=H/3f;bvx=300;bvy=250;sc=0;}
            c.drawColor(Color.rgb(13,18,31));
            p.setColor(Color.rgb(230,77,77)); c.drawRect(px,py,px+pw,py+ph,p);
            p.setColor(Color.WHITE); c.drawCircle(bx,by,br,p);
            c.drawText("Score: "+sc,20,50,tp);
            invalidate();
        }
    }
}""".trimIndent()

    // -----------------------------------------------------------------
    // Python (Chaquopy) Game Project
    // -----------------------------------------------------------------

    private fun writePythonGameProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle.kts", """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "${cfg.appName}"; include(":app")""")
        write(root, "build.gradle.kts", """plugins { id("com.android.application") version "${cfg.agpVersion}" apply false }""")
        write(root, "gradle.properties", "android.useAndroidX=true\norg.gradle.daemon=$gradleDaemonPreference\norg.gradle.workers.max=$mobileWorkerCount\nkotlin.daemon.enabled=$kotlinDaemonPreference\n")
        val app = File(root, "app").apply { mkdirs() }
        write(app, "build.gradle.kts", """plugins { id("com.android.application"); id("com.chaquo.python") version "15.0.1" }
android { namespace="${cfg.packageName}"; compileSdk=${cfg.compileSdk}; defaultConfig { applicationId="${cfg.packageName}"; minSdk=${cfg.minSdk}; targetSdk=${cfg.targetSdk}; versionCode=1; versionName="1.0"; ndk { abiFilters += listOf("arm64-v8a") } }
    python { pip { install("pygame-ce") } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    }
dependencies { implementation("androidx.appcompat:appcompat:1.6.1") }""")
        val mainDir = File(app, "src/main").apply { mkdirs() }
        write(mainDir, "AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:label="${cfg.appName}" android:icon="@android:drawable/sym_def_app_icon" android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
<activity android:name=".MainActivity" android:exported="true" android:screenOrientation="landscape" android:configChanges="orientation|screenSize">
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>""")
        val pkgPath = cfg.packageName.replace('.', '/')
        val srcDir = File(mainDir, "java/$pkgPath").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", pythonGameActivityKt(cfg))
        val pyDir = File(mainDir, "python").apply { mkdirs() }
        write(pyDir, "game.py", pythonGameScript(cfg))
        write(root, "README.md", "# ${cfg.appName}\n\nPython game (Chaquopy + Pygame CE)\nEdit app/src/main/python/game.py\n")
    }

    private fun pythonGameActivityKt(cfg: ProjectConfig): String = """
package ${cfg.packageName}
import android.app.Activity; import android.os.Bundle; import com.chaquo.python.Python
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Python.getInstance().getModule("game").callAttr("run")
    }
}""".trimIndent()

    private fun pythonGameScript(cfg: ProjectConfig): String = """# ${cfg.appName} — Python game (Pygame CE)
import pygame, sys
def run():
    pygame.init()
    screen = pygame.display.set_mode((800, 480))
    clock = pygame.time.Clock()
    px, py, pw, ph = 340, 420, 120, 20
    bx, by, bvx, bvy, br = 400, 240, 300, 250, 10
    score = 0
    while True:
        dt = clock.tick(60) / 1000.0
        for event in pygame.event.get():
            if event.type == pygame.QUIT: pygame.quit(); sys.exit()
            if event.type == pygame.MOUSEMOTION: px = event.pos[0] - pw // 2
        bx += bvx * dt; by += bvy * dt
        if bx < br or bx > 800 - br: bvx = -bvx
        if by < br: bvy = -bvy
        if by + br > py and px < bx < px + pw and bvy > 0: bvy = -bvy; score += 1
        if by > 480 + br: bx, by, bvx, bvy, score = 400, 240, 300, 250, 0
        screen.fill((13, 18, 31))
        pygame.draw.rect(screen, (230, 77, 77), (px, py, pw, ph))
        pygame.draw.circle(screen, (255, 255, 255), (int(bx), int(by)), br)
        font = pygame.font.Font(None, 36)
        screen.blit(font.render(f"Score: {score}", True, (255,255,255)), (20, 20))
        pygame.display.flip()
    pygame.quit()"""

    // -----------------------------------------------------------------
    // Godot Project (project.godot + main scene)
    // -----------------------------------------------------------------

    private fun writeGodotProject(root: File, cfg: ProjectConfig) {
        val appName = cfg.appName.replace("\\", "").replace("\"", "")
        File(root, "project.godot").writeText(
            """
            config_version=5

            [application]

            config/name="$appName"
            run/main_scene="res://main.tscn"
            config/features=PackedStringArray("4.7", "GL Compatibility")

            [rendering]

            renderer/rendering_method="gl_compatibility"
            renderer/rendering_method.mobile="gl_compatibility"
            """.trimIndent() + "\n"
        )
        File(root, "main.tscn").writeText(
            """
            [gd_scene load_steps=2 format=3]

            [ext_resource type="Script" path="res://main.gd" id="1_main"]

            [node name="Main" type="Node2D"]
            script = ExtResource("1_main")
            """.trimIndent() + "\n"
        )
        File(root, "main.gd").writeText(
            """
            extends Node2D

            func _ready() -> void:
                var label := Label.new()
                label.text = "Hello from Godot!"
                label.position = Vector2(80, 80)
                add_child(label)
            """.trimIndent() + "\n"
        )
        File(root, ".gitignore").writeText(
            """
            .godot/
            *.apk
            *.aab
            """.trimIndent() + "\n"
        )
    }

    private fun writeGdScriptGameProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle.kts", """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "${cfg.appName}"; include(":app")""")
        write(root, "build.gradle.kts", """plugins { id("com.android.application") version "${cfg.agpVersion}" apply false }""")
        write(root, "gradle.properties", "android.useAndroidX=true\norg.gradle.daemon=$gradleDaemonPreference\norg.gradle.workers.max=$mobileWorkerCount\nkotlin.daemon.enabled=$kotlinDaemonPreference\n")
        val app = File(root, "app").apply { mkdirs() }
        write(app, "build.gradle.kts", """plugins { id("com.android.application") }
android { namespace="${cfg.packageName}"; compileSdk=${cfg.compileSdk}; defaultConfig { applicationId="${cfg.packageName}"; minSdk=${cfg.minSdk}; targetSdk=${cfg.targetSdk}; versionCode=1; versionName="1.0" }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    }
dependencies { implementation("androidx.appcompat:appcompat:1.6.1") }""")
        val mainDir = File(app, "src/main").apply { mkdirs() }
        write(mainDir, "AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:label="${cfg.appName}" android:icon="@android:drawable/sym_def_app_icon" android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
<activity android:name=".MainActivity" android:exported="true" android:screenOrientation="landscape" android:configChanges="orientation|screenSize">
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>""")
        val pkgPath = cfg.packageName.replace('.', '/')
        val srcDir = File(mainDir, "java/$pkgPath").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", gdScriptGameActivityKt(cfg))
        val assetsDir = File(mainDir, "assets").apply { mkdirs() }
        write(assetsDir, "game.gd", gdScriptGameScript(cfg))
        write(root, "README.md", "# ${cfg.appName}\n\nGDScript game (Canvas API)\nEdit app/src/main/assets/game.gd\n")
    }

    private fun gdScriptGameActivityKt(cfg: ProjectConfig): String = """
package ${cfg.packageName}
import android.app.Activity; import android.content.Context; import android.graphics.Canvas; import android.graphics.Color
import android.graphics.Paint; import android.os.Bundle; import android.view.MotionEvent; import android.view.View
class MainActivity : Activity() {
    private lateinit var gv: GameView
    override fun onCreate(s: Bundle?) { super.onCreate(s); gv = GameView(this); setContentView(gv) }
    override fun onTouchEvent(e: MotionEvent): Boolean { gv.onTouch(e); return true }
    override fun onPause() { super.onPause(); gv.running = false }
    override fun onResume() { super.onResume(); gv.running = true; gv.invalidate() }
    inner class GameView(ctx: Context) : View(ctx) {
        var running = true
        private val vars = mutableMapOf<String, Double>()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint().apply { color = Color.WHITE; textSize = 48f; isAntiAlias = true }
        private var lastMs = 0L; private var canvas: Canvas? = null; private var script = ""
        init { try { script = assets.open("game.gd").bufferedReader().readText() } catch (_: Exception) {} vars["screen_width"]=0.0; vars["screen_height"]=0.0 }
        fun onTouch(e: MotionEvent) {
            if (e.actionMasked==MotionEvent.ACTION_DOWN||e.actionMasked==MotionEvent.ACTION_MOVE) {
                vars["touch_x"]=e.x.toDouble(); vars["touch_y"]=e.y.toDouble(); runFunc("on_touch", listOf("down",e.x.toString(),e.y.toString()))
            }
        }
        private fun runFunc(funcName: String, args: List<String>) {
            val re = Regex("(?:^|\\n)\\s*func\\s+${'$'}funcName\\s*\\([^)]*\\)\\s*:\\s*\\n([\\s\\S]*?)(?=\\n\\s*func\\s|\\z)")
            val body = re.find(script)?.groupValues?.get(1) ?: return
            for (line in body.lines()) {
                val t = line.trim()
                when {
                    t.startsWith("var ") -> { val p2 = t.removePrefix("var ").split("="); if(p2.size==2) vars[p2[0].trim()] = eval(p2[1].trim()) }
                    t.contains("=") && !t.startsWith("if") -> { val p2 = t.split("="); if(p2.size==2) vars[p2[0].trim()] = eval(p2[1].trim()) }
                    t.startsWith("draw_rect(") -> { val a = t.removePrefix("draw_rect(").removeSuffix(")").split(",").map{it.trim()}; if(a.size>=7) drawRect(a[0],a[1],a[2],a[3],a[4],a[5],a[6]) }
                    t.startsWith("draw_text(") -> { val a = t.removePrefix("draw_text(").removeSuffix(")").split(",").map{it.trim()}; if(a.size>=3) drawText(a[0].removeSurrounding("\""),a[1],a[2]) }
                    t == "clear()" -> canvas?.drawColor(Color.rgb(13,18,31))
                }
            }
        }
        private fun eval(s: String): Double { val v = vars[s]; if(v!=null) return v; val d = s.toDoubleOrNull(); if(d!=null) return d; val op = Regex("(.+?)([+\\-*/])(.+)").find(s); if(op!=null){val a=eval(op.groupValues[1].trim());val b=eval(op.groupValues[3].trim());return when(op.groupValues[2]){"+"->a+b;"-"->a-b;"*"->a*b;"/"->if(b!=0.0)a/b else 0.0;else->0.0}} return 0.0 }
        private fun drawRect(x:String,y:String,w:String,h:String,r:String,g:String,b:String) {
            val cv = canvas ?: return
            p.color = Color.rgb((eval(r)*255).toInt().coerceIn(0,255),(eval(g)*255).toInt().coerceIn(0,255),(eval(b)*255).toInt().coerceIn(0,255))
            val xi=eval(x).toFloat(); val yi=eval(y).toFloat(); cv.drawRect(xi,yi,xi+eval(w).toFloat(),yi+eval(h).toFloat(),p)
        }
        private fun drawText(t:String,x:String,y:String) { canvas?.drawText(t.removeSurrounding("\""),eval(x).toFloat(),eval(y).toFloat(),tp) }
        override fun onDraw(c: Canvas) {
            super.onDraw(c); if(!running) return
            val now=System.currentTimeMillis(); val dt=if(lastMs==0L)0.0 else(now-lastMs)/1000.0; lastMs=now
            vars["screen_width"]=width.toDouble(); vars["screen_height"]=height.toDouble(); canvas=c
            runFunc("_process", listOf(dt.toString()))
            canvas=null; if(running)invalidate()
        }
    }
}""".trimIndent()

    private fun gdScriptGameScript(cfg: ProjectConfig): String = """# ${cfg.appName} — GDScript game
var paddle_x = 0.0
var paddle_y = 0.0
var paddle_w = 120.0
var paddle_h = 20.0
var ball_x = 0.0
var ball_y = 0.0
var ball_vx = 300.0
var ball_vy = 250.0
var ball_r = 10.0
var score = 0.0

func _ready():
	paddle_x = screen_width / 2.0 - paddle_w / 2.0
	paddle_y = screen_height - 60.0
	ball_x = screen_width / 2.0
	ball_y = screen_height / 2.0

func _process(dt):
	if screen_width == 0.0:
		return
	if paddle_x == 0.0:
		_ready()
	ball_x = ball_x + ball_vx * dt
	ball_y = ball_y + ball_vy * dt
	if ball_x < ball_r or ball_x > screen_width - ball_r:
		ball_vx = -ball_vx
	if ball_y < ball_r:
		ball_vy = -ball_vy
	if ball_y + ball_r > paddle_y and ball_x > paddle_x and ball_x < paddle_x + paddle_w and ball_vy > 0.0:
		ball_vy = -ball_vy
		score = score + 1.0
	if ball_y > screen_height + ball_r:
		ball_x = screen_width / 2.0
		ball_y = screen_height / 3.0
		ball_vx = 300.0
		ball_vy = 250.0
		score = 0.0
	clear()
	draw_rect(paddle_x, paddle_y, paddle_w, paddle_h, 0.9, 0.3, 0.3)
	draw_rect(ball_x - ball_r, ball_y - ball_r, ball_r * 2.0, ball_r * 2.0, 1.0, 1.0, 1.0)
	draw_text("Score: " + str(score), 20.0, 50.0)

func on_touch(action, x, y):
	if action == "move" or action == "down":
		paddle_x = x - paddle_w / 2.0
		if paddle_x < 0.0:
			paddle_x = 0.0
		if paddle_x > screen_width - paddle_w:
			paddle_x = screen_width - paddle_w"""

    // -----------------------------------------------------------------
    // C# (MonoGame-style) Game Project
    // -----------------------------------------------------------------

    private fun writeCSharpGameProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle.kts", """pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "${cfg.appName}"; include(":app")""")
        write(root, "build.gradle.kts", """plugins { id("com.android.application") version "${cfg.agpVersion}" apply false }""")
        write(root, "gradle.properties", "android.useAndroidX=true\norg.gradle.daemon=$gradleDaemonPreference\norg.gradle.workers.max=$mobileWorkerCount\nkotlin.daemon.enabled=$kotlinDaemonPreference\n")
        val app = File(root, "app").apply { mkdirs() }
        write(app, "build.gradle.kts", """plugins { id("com.android.application") }
android { namespace="${cfg.packageName}"; compileSdk=${cfg.compileSdk}; defaultConfig { applicationId="${cfg.packageName}"; minSdk=${cfg.minSdk}; targetSdk=${cfg.targetSdk}; versionCode=1; versionName="1.0" }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
    }
dependencies { implementation("androidx.appcompat:appcompat:1.6.1") }""")
        val mainDir = File(app, "src/main").apply { mkdirs() }
        write(mainDir, "AndroidManifest.xml", """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:label="${cfg.appName}" android:icon="@android:drawable/sym_def_app_icon" android:theme="@style/Theme.AppCompat.DayNight.NoActionBar">
<activity android:name=".MainActivity" android:exported="true" android:screenOrientation="landscape" android:configChanges="orientation|screenSize">
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>""")
        val pkgPath = cfg.packageName.replace('.', '/')
        val srcDir = File(mainDir, "java/$pkgPath").apply { mkdirs() }
        write(srcDir, "MainActivity.kt", cSharpGameActivityKt(cfg))
        write(root, "README.md", "# ${cfg.appName}\n\nC# MonoGame-style game (Canvas API)\nEdit app/src/main/java/${pkgPath}/MainActivity.kt\n")
    }

    private fun cSharpGameActivityKt(cfg: ProjectConfig): String = """
package ${cfg.packageName}
import android.app.Activity; import android.content.Context; import android.graphics.Canvas; import android.graphics.Color
import android.graphics.Paint; import android.graphics.Typeface; import android.os.Bundle; import android.view.MotionEvent; import android.view.View
class MainActivity : Activity() {
    private lateinit var gv: GameView
    override fun onCreate(s: Bundle?) { super.onCreate(s); gv = GameView(this); setContentView(gv) }
    override fun onTouchEvent(e: MotionEvent): Boolean { gv.onTouch(e); return true }
    override fun onPause() { super.onPause(); gv.running = false }
    override fun onResume() { super.onResume(); gv.running = true; gv.invalidate() }
    inner class GameView(ctx: Context) : View(ctx) {
        var running = true
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tp = Paint().apply { color = Color.WHITE; textSize = 48f; isAntiAlias = true; typeface = Typeface.MONOSPACE }
        private var lastMs = 0L
        private var px = 0f; private var py = 0f; private val pw = 120f; private val ph = 20f
        private var bx = 0f; private var by = 0f; private var bvx = 300f; private var bvy = 250f; private val br = 10f; private var sc = 0
        fun onTouch(e: MotionEvent) { if(e.actionMasked==MotionEvent.ACTION_DOWN||e.actionMasked==MotionEvent.ACTION_MOVE){px=e.x-pw/2;if(px<0)px=0f;if(px>width-pw)px=(width-pw).toFloat()} }
        override fun onDraw(c: Canvas) {
            super.onDraw(c); if(!running) return
            val now=System.currentTimeMillis(); val dt=if(lastMs==0L)0f else(now-lastMs)/1000f; lastMs=now
            val W=width.toFloat(); val H=height.toFloat()
            if(py==0f){px=W/2-pw/2;py=H-60;bx=W/2;by=H/2}
            bx+=bvx*dt; by+=bvy*dt
            if(bx<br||bx>W-br)bvx=-bvx; if(by<br)bvy=-bvy
            if(by+br>py&&bx>px&&bx<px+pw&&bvy>0){bvy=-bvy;sc++}
            if(by>H+br){bx=W/2;by=H/3;bvx=300f;bvy=250f;sc=0}
            c.drawColor(Color.rgb(13,18,31))
            p.color=Color.rgb(230,77,77); c.drawRect(px,py,px+pw,py+ph,p)
            p.color=Color.WHITE; c.drawCircle(bx,by,br,p)
            c.drawText("Score: ${'$'}sc",20f,50f,tp)
            c.drawText("MonoGame-style C# Canvas",20f,H-30,tp)
            invalidate()
        }
    }
}""".trimIndent()

    // -----------------------------------------------------------------

    private fun gameManifestXml(cfg: ProjectConfig): String {
        val label = cfg.appName
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="$label"
                    android:icon="@android:drawable/sym_def_app_icon"
                    android:theme="@style/Theme.App">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true"
                        android:configChanges="orientation|screenSize|keyboardHidden|screenLayout"
                        android:screenOrientation="landscape">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()
    }

    private fun gameMainActivityKt(cfg: ProjectConfig): String = """
        package ${cfg.packageName}

        import android.app.Activity
        import android.opengl.GLSurfaceView
        import android.os.Bundle
        import android.view.MotionEvent
        import javax.microedition.khronos.egl.EGLConfig
        import javax.microedition.khronos.opengles.GL10

        class MainActivity : Activity() {
            private lateinit var glView: GLSurfaceView
            private var lastFrameNanos = 0L

            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                glView = GLSurfaceView(this)
                glView.setEGLContextClientVersion(3)
                glView.setRenderer(object : GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                        nativeInit(filesDir.absolutePath)
                    }
                    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                        nativeResize(width, height)
                    }
                    override fun onDrawFrame(gl: GL10?) {
                        val now = System.nanoTime()
                        if (lastFrameNanos == 0L) lastFrameNanos = now
                        val dtMs = ((now - lastFrameNanos) / 1_000_000f).coerceAtLeast(0f).coerceAtMost(100f)
                        lastFrameNanos = now
                        nativeStep(dtMs)
                    }
                })
                glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                setContentView(glView)
            }

            override fun onTouchEvent(e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> nativeTouchDown(e.x, e.y)
                    MotionEvent.ACTION_MOVE -> nativeTouchMove(e.x, e.y)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> nativeTouchUp(e.x, e.y)
                }
                return true
            }

            override fun onPause() { super.onPause(); glView.onPause() }
            override fun onResume() { super.onResume(); glView.onResume() }

            private external fun nativeInit(dataDir: String)
            private external fun nativeResize(width: Int, height: Int)
            private external fun nativeStep(dtMs: Float)
            private external fun nativeTouchDown(x: Float, y: Float)
            private external fun nativeTouchMove(x: Float, y: Float)
            private external fun nativeTouchUp(x: Float, y: Float)

            companion object {
                init {
                    System.loadLibrary("game")
                }
            }
        }
    """.trimIndent()

    private fun gameCmake(cfg: ProjectConfig): String {
        val proj = cfg.appName.replace(Regex("[^A-Za-z0-9_]"), "_")
        return """
            cmake_minimum_required(VERSION 3.22.1)
            project(${proj}_game LANGUAGES C CXX)
            set(CMAKE_C_STANDARD 17)
            set(CMAKE_CXX_STANDARD 17)

            add_library(game SHARED game.cpp)

            find_library(gl-lib GLESv3)
            find_library(egl-lib EGL)
            find_library(android-lib android)
            find_library(log-lib log)

            target_link_libraries(game ${'$'}{gl-lib} ${'$'}{egl-lib} ${'$'}{android-lib} ${'$'}{log-lib})
            target_include_directories(game PRIVATE ${'$'}{CMAKE_CURRENT_SOURCE_DIR})
        """.trimIndent()
    }

    private fun gamePongCpp(jni: String): String = """
#include <jni.h>
#include <GLES3/gl3.h>
#include <cmath>
#include "game_gl.h"

static GLuint program;
static GLint uColor, uOffset, uScale;
static GLuint vao, vbo;
static int winW = 1, winH = 1;
static float padY = 0.0f, padRY = 0.0f;
static float ballX = 0.0f, ballY = 0.0f;
static float ballVX = 0.008f, ballVY = 0.005f;
static int scoreL = 0, scoreR = 0;
static const float PAD_HALF = 0.25f, PAD_W = 0.04f, BALL_SZ = 0.06f;

static const char* VS = R"(#version 300 es
layout(location=0) in vec2 aPos;
uniform vec2 uOffset;
uniform vec2 uScale;
void main() { gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0); }
)";

static const char* FS = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 fragColor;
void main() { fragColor = uColor; }
)";

static void drawQuad(float cx, float cy, float w, float h, float r, float g, float b) {
    glUniform2f(uOffset, cx, cy);
    glUniform2f(uScale, w * 0.5f, h * 0.5f);
    glUniform4f(uColor, r, g, b, 1.0f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeInit(JNIEnv* env, jobject, jstring dataDir) {
    (void)env; (void)dataDir;
    glClearColor(0.05f, 0.07f, 0.12f, 1.0f);
    program = ms_link_program(VS, FS);
    uColor = glGetUniformLocation(program, "uColor");
    uOffset = glGetUniformLocation(program, "uOffset");
    uScale = glGetUniformLocation(program, "uScale");
    glUseProgram(program);
    float quad[] = { -1,-1, 1,-1, -1,1, 1,1 };
    glGenVertexArrays(1, &vao);
    glBindVertexArray(vao);
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    winW = w > 0 ? w : 1; winH = h > 0 ? h : 1;
    glViewport(0, 0, winW, winH);
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeStep(JNIEnv*, jobject, jfloat dtMs) {
    (void)dtMs;
    ballX += ballVX;
    ballY += ballVY;
    if (ballY > 1.0f - BALL_SZ) { ballY = 1.0f - BALL_SZ; ballVY = -ballVY; }
    if (ballY < -1.0f + BALL_SZ) { ballY = -1.0f + BALL_SZ; ballVY = -ballVY; }
    if (ballX > 1.0f) { scoreL++; ballX = 0; ballY = 0; ballVX = -fabsf(ballVX); }
    if (ballX < -1.0f) { scoreR++; ballX = 0; ballY = 0; ballVX = fabsf(ballVX); }
    if (ballX - BALL_SZ < -0.9f + PAD_W && ballX > -0.9f &&
        ballY > padY - PAD_HALF && ballY < padY + PAD_HALF) {
        ballX = -0.9f + PAD_W + BALL_SZ; ballVX = fabsf(ballVX);
    }
    padRY += (ballY - padRY) * 0.08f;
    if (ballX + BALL_SZ > 0.9f - PAD_W && ballX < 0.9f &&
        ballY > padRY - PAD_HALF && ballY < padRY + PAD_HALF) {
        ballX = 0.9f - PAD_W - BALL_SZ; ballVX = -fabsf(ballVX);
    }
    glClear(GL_COLOR_BUFFER_BIT);
    drawQuad(0.0f, 0.0f, 0.01f, 2.0f, 0.8f, 0.8f, 0.8f);
    drawQuad(-0.9f, padY, PAD_W, PAD_HALF * 2.0f, 0.9f, 0.3f, 0.3f);
    drawQuad(0.9f, padRY, PAD_W, PAD_HALF * 2.0f, 0.3f, 0.5f, 0.9f);
    drawQuad(ballX, ballY, BALL_SZ, BALL_SZ, 1.0f, 1.0f, 1.0f);
    if (scoreL == 5 || scoreR == 5) { scoreL = 0; scoreR = 0; }
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchDown(JNIEnv*, jobject, jfloat x, jfloat y) {
    padY = 1.0f - 2.0f * (y / (float)winH);
}
extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchMove(JNIEnv*, jobject, jfloat x, jfloat y) {
    padY = 1.0f - 2.0f * (y / (float)winH);
}
extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchUp(JNIEnv*, jobject, jfloat, jfloat) {}
""".trimIndent()

    private fun gameSnakeCpp(jni: String): String = """
#include <jni.h>
#include <GLES3/gl3.h>
#include <cmath>
#include "game_gl.h"

static GLuint program;
static GLint uColor, uOffset, uScale;
static GLuint vao, vbo;
static int winW = 1, winH = 1;
static const int GRID = 16;
static int snakeX[256], snakeY[256];
static int snakeLen = 3;
static int dirX = 1, dirY = 0;
static int foodX = 5, foodY = 5;
static float timer = 0.0f;
static bool dead = false;

static const char* VS = R"(#version 300 es
layout(location=0) in vec2 aPos;
uniform vec2 uOffset;
uniform vec2 uScale;
void main() { gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0); }
)";
static const char* FS = R"(#version 300 es
precision mediump float;
uniform vec4 uColor;
out vec4 o;
void main() { o = uColor; }
)";

static void cell(int gx, int gy, float r, float g, float b) {
    float cx = (gx + 0.5f) / GRID * 2.0f - 1.0f;
    float cy = (gy + 0.5f) / GRID * 2.0f - 1.0f;
    float w = 2.0f / GRID;
    glUniform2f(uOffset, cx, cy);
    glUniform2f(uScale, w * 0.5f, w * 0.5f);
    glUniform4f(uColor, r, g, b, 1.0f);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

static void resetGame() {
    snakeLen = 3; dirX = 1; dirY = 0; dead = false;
    snakeX[0] = GRID/2; snakeY[0] = GRID/2;
    for (int i = 1; i < snakeLen; ++i) { snakeX[i] = snakeX[0]-i; snakeY[i] = snakeY[0]; }
    foodX = GRID/2 + 3; foodY = GRID/2;
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeInit(JNIEnv*, jobject, jstring) {
    glClearColor(0.03f, 0.05f, 0.08f, 1.0f);
    program = ms_link_program(VS, FS);
    uColor = glGetUniformLocation(program, "uColor");
    uOffset = glGetUniformLocation(program, "uOffset");
    uScale = glGetUniformLocation(program, "uScale");
    glUseProgram(program);
    float q[] = { -1,-1, 1,-1, -1,1, 1,1 };
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glGenBuffers(1, &vbo); glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(q), q, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0); glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
    resetGame();
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    winW = w > 0 ? w : 1; winH = h > 0 ? h : 1;
    glViewport(0, 0, winW, winH);
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeStep(JNIEnv*, jobject, jfloat dtMs) {
    timer += dtMs;
    if (!dead && timer > 120.0f) {
        timer = 0.0f;
        int nx = snakeX[0] + dirX, ny = snakeY[0] + dirY;
        if (nx < 0 || nx >= GRID || ny < 0 || ny >= GRID) { dead = true; }
        for (int i = 0; i < snakeLen && !dead; ++i) { if (snakeX[i]==nx && snakeY[i]==ny) dead = true; }
        if (!dead) {
            for (int i = snakeLen; i > 0; --i) { snakeX[i]=snakeX[i-1]; snakeY[i]=snakeY[i-1]; }
            snakeX[0]=nx; snakeY[0]=ny;
            if (nx==foodX && ny==foodY) { snakeLen++; foodX = (foodX*7+3)%GRID; foodY = (foodY*11+5)%GRID; }
        }
    }
    glClear(GL_COLOR_BUFFER_BIT);
    cell(foodX, foodY, 1.0f, 0.3f, 0.3f);
    for (int i = 0; i < snakeLen; ++i) cell(snakeX[i], snakeY[i], 0.3f, 0.9f, 0.4f);
    if (dead) { cell(GRID/2-2, GRID/2, 1.0f, 0.5f, 0.0f); }
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchDown(JNIEnv*, jobject, jfloat, jfloat) {}
extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchMove(JNIEnv*, jobject, jfloat, jfloat) {}
extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchUp(JNIEnv*, jobject, jfloat x, jfloat) {
    float cx = x - (float)winW * 0.5f;
    if (fabsf(cx) < 20.0f) return;
    if (cx > 0 && dirX != -1) { dirX = 1; dirY = 0; }
    else if (cx < 0 && dirX != 1) { dirX = -1; dirY = 0; }
}
""".trimIndent()

    private fun gameSceneCpp(jni: String): String = """
#include <jni.h>
#include <GLES3/gl3.h>
#include <cmath>
#include "game_gl.h"
#include "game_math.h"

static GLuint program;
static GLint uMvp, uModelLoc, uColor;
static GLuint vao, vbo;
static int winW = 1, winH = 1;
static float angle = 0.0f;
static float camYaw = 0.5f, camPitch = 0.0f;

static const char* VS = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vNormal;
void main() {
    vNormal = mat3(uModel) * aNormal;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
)";

static const char* FS = R"(#version 300 es
precision mediump float;
uniform vec3 uColor;
uniform vec3 uLightDir;
in vec3 vNormal;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    float d = max(dot(n, normalize(uLightDir)), 0.0);
    fragColor = vec4(uColor * (0.25 + 0.75 * d), 1.0);
}
)";

static const float cubeVerts[] = {
    +1,+1,+1, +0,+0,+1,
    -1,+1,+1, +0,+0,+1,
    -1,-1,+1, +0,+0,+1,
    +1,-1,+1, +0,+0,+1,
    +1,+1,-1, +0,+0,-1,
    +1,-1,-1, +0,+0,-1,
    -1,-1,-1, +0,+0,-1,
    -1,+1,-1, +0,+0,-1,
    +1,+1,-1, +1,+0,+0,
    +1,+1,+1, +1,+0,+0,
    +1,-1,+1, +1,+0,+0,
    +1,-1,-1, +1,+0,+0,
    -1,+1,+1, -1,+0,+0,
    -1,+1,-1, -1,+0,+0,
    -1,-1,-1, -1,+0,+0,
    -1,-1,+1, -1,+0,+0,
    +1,+1,-1, +0,+1,+0,
    -1,+1,-1, +0,+1,+0,
    -1,+1,+1, +0,+1,+0,
    +1,+1,+1, +0,+1,+0,
    +1,-1,+1, +0,-1,+0,
    -1,-1,+1, +0,-1,+0,
    -1,-1,-1, +0,-1,+0,
    +1,-1,-1, +0,-1,+0,
};

static const GLushort cubeIdx[] = {0,1,2, 0,2,3, 4,5,6, 4,6,7, 8,9,10, 8,10,11, 12,13,14, 12,14,15, 16,17,18, 16,18,19, 20,21,22, 20,22,23};
static GLuint ebo;

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeInit(JNIEnv*, jobject, jstring) {
    glClearColor(0.12f, 0.13f, 0.18f, 1.0f);
    glEnable(GL_DEPTH_TEST);
    program = ms_link_program(VS, FS);
    uMvp = glGetUniformLocation(program, "uMvp");
    uModelLoc = glGetUniformLocation(program, "uModel");
    uColor = glGetUniformLocation(program, "uColor");
    glUniform3f(glGetUniformLocation(program, "uLightDir"), 0.5f, 0.8f, 0.4f);
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glGenBuffers(1, &vbo); glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(cubeVerts), cubeVerts, GL_STATIC_DRAW);
    glGenBuffers(1, &ebo); glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
    glBufferData(GL_ELEMENT_ARRAY_BUFFER, sizeof(cubeIdx), cubeIdx, GL_STATIC_DRAW);
    GLint aPos = glGetAttribLocation(program, "aPos");
    GLint aNormal = glGetAttribLocation(program, "aNormal");
    glEnableVertexAttribArray(aPos);
    glVertexAttribPointer(aPos, 3, GL_FLOAT, GL_FALSE, 5*sizeof(float), 0);
    glEnableVertexAttribArray(aNormal);
    glVertexAttribPointer(aNormal, 3, GL_FLOAT, GL_FALSE, 5*sizeof(float), (void*)(3*sizeof(float)));
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    winW = w > 0 ? w : 1; winH = h > 0 ? h : 1;
    glViewport(0, 0, winW, winH);
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeStep(JNIEnv*, jobject, jfloat dtMs) {
    angle += dtMs * 0.05f;
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glUseProgram(program);
    mat4 proj; ms_mat4_perspective(&proj, 0.9f, (float)winW/(float)winH, 0.1f, 100.0f);
    float r = 6.0f;
    float ex = r * cosf(camPitch) * sinf(camYaw);
    float ey = r * sinf(camPitch);
    float ez = r * cosf(camPitch) * cosf(camYaw);
    vec3 eye = {ex, ey, ez};
    vec3 center = {0,0,0};
    vec3 up = {0,1,0};
    mat4 view = ms_mat4_lookat(eye, center, up);
    mat4 vp; ms_mat4_mult(&vp, &proj, &view);
    mat4 model; ms_mat4_identity(&model);
    ms_mat4_rotate_y(&model, angle);
    ms_mat4_rotate_x(&model, angle * 0.3f);
    mat4 mvp; ms_mat4_mult(&mvp, &vp, &model);
    glUniformMatrix4fv(uMvp, 1, GL_FALSE, mvp.m);
    glUniformMatrix4fv(uModelLoc, 1, GL_FALSE, model.m);
    glUniform3f(uColor, 0.3f, 0.7f, 0.9f);
    glBindVertexArray(vao);
    glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_SHORT, 0);
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchDown(JNIEnv*, jobject, jfloat, jfloat) {}
extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchMove(JNIEnv*, jobject, jfloat x, jfloat y) {
    static float lx = 0, ly = 0;
    static bool init = false;
    if (!init) { lx = x; ly = y; init = true; return; }
    float dx = x - lx, dy = y - ly; lx = x; ly = y;
    camYaw += dx * 0.01f;
    camPitch -= dy * 0.01f;
    if (camPitch > 1.4f) camPitch = 1.4f;
    if (camPitch < -1.4f) camPitch = -1.4f;
}
extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchUp(JNIEnv*, jobject, jfloat, jfloat) {}
""".trimIndent()

    private fun gameVoxelCpp(jni: String): String = """
#include <jni.h>
#include <GLES3/gl3.h>
#include <cmath>
#include <cstring>
#include <vector>
#include <cstdio>
#include "game_gl.h"
#include "game_math.h"

static int WX = 32, WY = 16, WZ = 32;
static unsigned char* world = nullptr;
static GLuint program;
static GLint uMvp, uModelLoc, uColor;
static GLuint vao, vbo;
static int winW = 1, winH = 1;
static float camX = 16.0f, camY = 18.0f, camZ = 16.0f;
static float yaw = -1.57f, pitch = -0.3f;
static int selectedBlock = 1;
static char dataDir[512] = {0};

static int idx(int x, int y, int z) { return y * WX * WZ + x * WZ + z; }
static bool inBounds(int x, int y, int z) { return x >= 0 && x < WX && y >= 0 && y < WY && z >= 0 && z < WZ; }
static unsigned char getBlock(int x, int y, int z) { return inBounds(x, y, z) ? world[idx(x, y, z)] : 0; }
static void setBlock(int x, int y, int z, unsigned char b) { if (inBounds(x, y, z)) world[idx(x, y, z)] = b; }

struct V { float x, y, z, nx, ny, nz; };
static std::vector<V> verts;

static void pushFace(float cx, float cy, float cz, float nx, float ny, float nz) {
    float ux, uy, uz, vx, vy, vz;
    if (fabsf(nx) > 0.5f) { ux=0;uy=1;uz=0; vx=0;vy=0;vz=1; }
    else if (fabsf(ny) > 0.5f) { ux=1;uy=0;uz=0; vx=0;vy=0;vz=1; }
    else { ux=1;uy=0;uz=0; vx=0;vy=1;vz=0; }
    float p = 0.5f;
    V a = {cx+(ux-vx)*p, cy+(uy-vy)*p, cz+(uz-vz)*p, nx, ny, nz};
    V b = {cx+(ux+vx)*p, cy+(uy+vy)*p, cz+(uz+vz)*p, nx, ny, nz};
    V c = {cx+(ux-vx+nx)*p, cy+(uy-vy+ny)*p, cz+(uz-vz+nz)*p, nx, ny, nz};
    V d = {cx+(ux+vx+nx)*p, cy+(uy+vy+ny)*p, cz+(uz+vz+nz)*p, nx, ny, nz};
    verts.push_back(a); verts.push_back(c); verts.push_back(b);
    verts.push_back(b); verts.push_back(c); verts.push_back(d);
}

static void buildMesh() {
    verts.clear();
    for (int x = 0; x < WX; ++x) for (int y = 0; y < WY; ++y) for (int z = 0; z < WZ; ++z) {
        unsigned char b = getBlock(x, y, z);
        if (!b) continue;
        if (!getBlock(x+1,y,z)) pushFace(x,y,z, 1,0,0);
        if (!getBlock(x-1,y,z)) pushFace(x,y,z,-1,0,0);
        if (!getBlock(x,y+1,z)) pushFace(x,y,z, 0,1,0);
        if (!getBlock(x,y-1,z)) pushFace(x,y,z, 0,-1,0);
        if (!getBlock(x,y,z+1)) pushFace(x,y,z, 0,0,1);
        if (!getBlock(x,y,z-1)) pushFace(x,y,z, 0,0,-1);
    }
    glBindVertexArray(vao); glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, verts.size()*sizeof(V), verts.empty()?nullptr:verts.data(), GL_DYNAMIC_DRAW);
}

static void resetWorld() {
    if (!world) world = new unsigned char[WX*WY*WZ];
    memset(world, 0, WX*WY*WZ);
    for (int x = 0; x < WX; ++x) for (int z = 0; z < WZ; ++z) {
        setBlock(x, 0, z, 3);
        for (int y = 1; y < 3; ++y) setBlock(x, y, z, 2);
    }
    setBlock(8, 3, 8, 1);
}

static const char* VS = R"(#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vNormal;
out float vShade;
void main() {
    vNormal = aNormal;
    float d = (aNormal.y > 0.5) ? 1.0 : (aNormal.y < -0.5 ? 0.4 : 0.7);
    vShade = d;
    gl_Position = uMvp * uModel * vec4(aPos, 1.0);
}
)";

static const char* FS = R"(#version 300 es
precision mediump float;
uniform vec3 uColor;
in vec3 vNormal;
in float vShade;
out vec4 fragColor;
void main() { fragColor = vec4(uColor * vShade, 1.0); }
)";

static void setBlockColor(unsigned char b) {
    switch (b) {
        case 1: glUniform3f(uColor, 0.3f, 0.8f, 0.2f); break;
        case 2: glUniform3f(uColor, 0.42f, 0.31f, 0.2f); break;
        case 3: glUniform3f(uColor, 0.55f, 0.55f, 0.58f); break;
        default: glUniform3f(uColor, 0.6f, 0.6f, 0.6f); break;
    }
}

static void castRay(float& hx, float& hy, float& hz) {
    float dx = cosf(pitch)*sinf(yaw), dy = sinf(pitch), dz = cosf(pitch)*cosf(yaw);
    for (float t = 0; t < 8.0f; t += 0.1f) {
        int x = (int)floorf(camX + dx*t), y = (int)floorf(camY + dy*t), z = (int)floorf(camZ + dz*t);
        if (getBlock(x, y, z)) { hx=x; hy=y; hz=z; return; }
    }
    hx=-1; hy=-1; hz=-1;
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeInit(JNIEnv* env, jobject, jstring jdataDir) {
    const char* p = env->GetStringUTFChars(jdataDir, nullptr);
    if (p) { strncpy(dataDir, p, sizeof(dataDir)-1); env->ReleaseStringUTFChars(jdataDir, p); }
    glClearColor(0.5f, 0.7f, 0.9f, 1.0f);
    glEnable(GL_DEPTH_TEST);
    glEnable(GL_CULL_FACE);
    program = ms_link_program(VS, FS);
    uMvp = glGetUniformLocation(program, "uMvp");
    uModelLoc = glGetUniformLocation(program, "uModel");
    uColor = glGetUniformLocation(program, "uColor");
    glGenVertexArrays(1, &vao); glBindVertexArray(vao);
    glGenBuffers(1, &vbo); glBindBuffer(GL_ARRAY_BUFFER, vbo);
    GLint aPos = glGetAttribLocation(program, "aPos");
    GLint aNormal = glGetAttribLocation(program, "aNormal");
    glEnableVertexAttribArray(aPos);
    glVertexAttribPointer(aPos, 3, GL_FLOAT, GL_FALSE, sizeof(V), (void*)0);
    glEnableVertexAttribArray(aNormal);
    glVertexAttribPointer(aNormal, 3, GL_FLOAT, GL_FALSE, sizeof(V), (void*)(3*sizeof(float)));
    resetWorld();
    buildMesh();
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    winW = w > 0 ? w : 1; winH = h > 0 ? h : 1;
    glViewport(0, 0, winW, winH);
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeStep(JNIEnv*, jobject, jfloat) {
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glUseProgram(program);
    mat4 proj; ms_mat4_perspective(&proj, 1.0f, (float)winW/(float)winH, 0.1f, 100.0f);
    vec3 eye = {camX, camY, camZ};
    float fxx = cosf(pitch)*sinf(yaw), fyy = sinf(pitch), fzz = cosf(pitch)*cosf(yaw);
    vec3 center = {camX + fxx, camY + fyy, camZ + fzz};
    vec3 up = {0,1,0};
    mat4 view = ms_mat4_lookat(eye, center, up);
    mat4 vp; ms_mat4_mult(&vp, &proj, &view);
    mat4 model; ms_mat4_identity(&model);
    mat4 mvp; ms_mat4_mult(&mvp, &vp, &model);
    glUniformMatrix4fv(uMvp, 1, GL_FALSE, mvp.m);
    glUniformMatrix4fv(uModelLoc, 1, GL_FALSE, model.m);
    glBindVertexArray(vao);
    glDrawArrays(GL_TRIANGLES, 0, (GLsizei)verts.size());
}

extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchDown(JNIEnv*, jobject, jfloat, jfloat) {}
extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchMove(JNIEnv*, jobject, jfloat x, jfloat y) {
    static float lx = 0, ly = 0;
    static bool i = false;
    if (!i) { lx=x; ly=y; i=true; return; }
    float dx = x-lx, dy = y-ly; lx=x; ly=y;
    yaw += dx*0.008f; pitch -= dy*0.008f;
    if (pitch > 1.2f) pitch = 1.2f; if (pitch < -1.2f) pitch = -1.2f;
}
extern "C" JNIEXPORT void JNICALL Java_${jni}_MainActivity_nativeTouchUp(JNIEnv*, jobject, jfloat, jfloat) {}
""".trimIndent()

    companion object {
        private const val GAME_MATH_HEADER = """#pragma once

// Minimal vector / matrix math for MobileStudio game templates.

typedef struct { float x, y; } vec2;
typedef struct { float x, y, z; } vec3;
typedef struct { float m[16]; } mat4;

static void ms_mat4_identity(mat4* out) {
    for (int i = 0; i < 16; ++i) out->m[i] = 0.0f;
    out->m[0] = out->m[5] = out->m[10] = out->m[15] = 1.0f;
}

static void ms_mat4_perspective(mat4* out, float fovyRad, float aspect, float znear, float zfar) {
    float f = 1.0f / tanf(fovyRad * 0.5f);
    for (int i = 0; i < 16; ++i) out->m[i] = 0.0f;
    out->m[0] = f / aspect;
    out->m[5] = f;
    out->m[10] = (zfar + znear) / (znear - zfar);
    out->m[11] = -1.0f;
    out->m[14] = (2.0f * zfar * znear) / (znear - zfar);
}

static void ms_mat4_mult(mat4* out, const mat4* a, const mat4* b) {
    mat4 r;
    for (int c = 0; c < 4; ++c) {
        for (int row = 0; row < 4; ++row) {
            r.m[c * 4 + row] =
                a->m[row] * b->m[c * 4] +
                a->m[4 + row] * b->m[c * 4 + 1] +
                a->m[8 + row] * b->m[c * 4 + 2] +
                a->m[12 + row] * b->m[c * 4 + 3];
        }
    }
    *out = r;
}

static void ms_mat4_translate(mat4* m, float x, float y, float z) {
    m->m[12] += x; m->m[13] += y; m->m[14] += z;
}

static mat4 ms_mat4_lookat(vec3 eye, vec3 center, vec3 up) {
    vec3 f = {(center.x - eye.x), (center.y - eye.y), (center.z - eye.z)};
    float fl = sqrtf(f.x*f.x + f.y*f.y + f.z*f.z);
    if (fl > 1e-6f) { f.x /= fl; f.y /= fl; f.z /= fl; }
    vec3 s = { f.y*up.z - f.z*up.y, f.z*up.x - f.x*up.z, f.x*up.y - f.y*up.x };
    float sl = sqrtf(s.x*s.x + s.y*s.y + s.z*s.z);
    if (sl > 1e-6f) { s.x /= sl; s.y /= sl; s.z /= sl; }
    vec3 u = { s.y*f.z - s.z*f.y, s.z*f.x - s.x*f.z, s.x*f.y - s.y*f.x };
    mat4 m;
    m.m[0]=s.x; m.m[1]=u.x; m.m[2]=-f.x; m.m[3]=0;
    m.m[4]=s.y; m.m[5]=u.y; m.m[6]=-f.y; m.m[7]=0;
    m.m[8]=s.z; m.m[9]=u.z; m.m[10]=-f.z; m.m[11]=0;
    m.m[12]=-(s.x*eye.x+s.y*eye.y+s.z*eye.z);
    m.m[13]=-(u.x*eye.x+u.y*eye.y+u.z*eye.z);
    m.m[14]= (f.x*eye.x+f.y*eye.y+f.z*eye.z);
    m.m[15]=1;
    return m;
}

static vec3 ms_vec3_add(vec3 a, vec3 b) { vec3 r = {a.x+b.x, a.y+b.y, a.z+b.z}; return r; }
static vec3 ms_vec3_scale(vec3 a, float s) { vec3 r = {a.x*s, a.y*s, a.z*s}; return r; }
static float ms_vec3_dot(vec3 a, vec3 b) { return a.x*b.x + a.y*b.y + a.z*b.z; }

static vec3 ms_vec3_cross(vec3 a, vec3 b) {
    vec3 r = { a.y*b.z - a.z*b.y, a.z*b.x - a.x*b.z, a.x*b.y - a.y*b.x };
    return r;
}

static vec3 ms_vec3_normalize(vec3 a) {
    float l = sqrtf(a.x*a.x + a.y*a.y + a.z*a.z);
    if (l < 1e-6f) return a;
    vec3 r = { a.x/l, a.y/l, a.z/l };
    return r;
}

static void ms_mat4_rotate_x(mat4* m, float a) {
    float c = cosf(a), s = sinf(a);
    float a1 = m->m[4], a2 = m->m[5], a3 = m->m[6], a4 = m->m[7];
    float b1 = m->m[8], b2 = m->m[9], b3 = m->m[10], b4 = m->m[11];
    m->m[4] = a1*c + b1*s; m->m[5] = a2*c + b2*s; m->m[6] = a3*c + b3*s; m->m[7] = a4*c + b4*s;
    m->m[8] = b1*c - a1*s; m->m[9] = b2*c - a2*s; m->m[10] = b3*c - a3*s; m->m[11] = b4*c - a4*s;
}

static void ms_mat4_rotate_y(mat4* m, float a) {
    float c = cosf(a), s = sinf(a);
    float a1 = m->m[0], a2 = m->m[1], a3 = m->m[2], a4 = m->m[3];
    float b1 = m->m[8], b2 = m->m[9], b3 = m->m[10], b4 = m->m[11];
    m->m[0] = a1*c - b1*s; m->m[1] = a2*c - b2*s; m->m[2] = a3*c - b3*s; m->m[3] = a4*c - b4*s;
    m->m[8] = b1*c + a1*s; m->m[9] = b2*c + a2*s; m->m[10] = b3*c + a3*s; m->m[11] = b4*c + a4*s;
}
"""

        private const val GAME_GL_HEADER = """#pragma once

#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <android/log.h>

#define MS_TAG "MobileStudioGame"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MS_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, MS_TAG, __VA_ARGS__)

static GLuint ms_compile_shader(GLenum type, const char* src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[1024];
        glGetShaderInfoLog(s, sizeof(buf), NULL, buf);
        LOGE("shader compile failed: %s", buf);
        glDeleteShader(s);
        return 0;
    }
    return s;
}

static GLuint ms_link_program(const char* vsSrc, const char* fsSrc) {
    GLuint vs = ms_compile_shader(GL_VERTEX_SHADER, vsSrc);
    GLuint fs = ms_compile_shader(GL_FRAGMENT_SHADER, fsSrc);
    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[1024];
        glGetProgramInfoLog(p, sizeof(buf), NULL, buf);
        LOGE("program link failed: %s", buf);
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
}
"""
    }

    private fun writeJarProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle", "rootProject.name = '${cfg.appName}'\n")
        write(root, "build.gradle", """
            plugins {
                id 'java'
                id 'application'
            }

            repositories {
                mavenCentral()
            }

            java {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            application {
                mainClass = '${cfg.packageName}.Main'
            }

            jar {
                manifest {
                    attributes 'Main-Class': '${cfg.packageName}.Main'
                }
            }
        """.trimIndent())
        write(root, "gradle.properties",
            "org.gradle.daemon=$gradleDaemonPreference\n" +
                "org.gradle.workers.max=$mobileWorkerCount\n" +
                "org.gradle.caching=true\n" +
                "org.gradle.vfs.watch=false\n"
        )
        val src = File(root, "src/main/java/${cfg.packageName.replace('.', '/')}").apply { mkdirs() }
        write(src, "Main.java", """
            package ${cfg.packageName};

            public class Main {
                public static void main(String[] args) {
                    System.out.println("Hello from MobileStudio JAR project!");
                }
            }
        """.trimIndent())
    }

    private fun writeForgeProject(root: File, cfg: ProjectConfig) {
        writeGradleWrapper(root)
        write(root, "settings.gradle", """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    maven { url = 'https://maven.minecraftforge.net/' }
                    mavenCentral()
                }
            }
            rootProject.name = '${cfg.appName}'
        """.trimIndent())
        write(root, "build.gradle", """
            plugins {
                id 'net.minecraftforge.gradle' version '6.0.26'
            }

            group = '${cfg.packageName}'
            version = '1.0.0'

            java {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }

            minecraft {
                mappings channel: 'official', version: '1.20.1'
            }

            repositories {
                maven { url = 'https://maven.minecraftforge.net/' }
                mavenCentral()
            }

            dependencies {
                minecraft 'net.minecraftforge:forge:1.20.1-47.3.0'
            }
        """.trimIndent())
        write(root, "gradle.properties",
            "org.gradle.daemon=$gradleDaemonPreference\n" +
                "org.gradle.workers.max=$mobileWorkerCount\n" +
                "org.gradle.caching=true\n" +
                "org.gradle.vfs.watch=false\n"
        )
        val src = File(root, "src/main/java/${cfg.packageName.replace('.', '/')}").apply { mkdirs() }
        write(src, "ExampleMod.java", """
            package ${cfg.packageName};

            import net.minecraftforge.fml.common.Mod;

            @Mod(ExampleMod.MODID)
            public class ExampleMod {
                public static final String MODID = "${cfg.appName.lowercase().replace(Regex("[^a-z0-9_]"), "_")}";
            }
        """.trimIndent())
    }

    private fun writePythonScriptProject(root: File) {
        write(root, "main.py", """
            if __name__ == "__main__":
                print("Hello MobileStudioPython!")
        """.trimIndent())
        write(root, "README.md", """
            # MobileStudio Python

            Simple Python shell project for MobileStudio.

            ## Run

            Press the green run button or use:

            ```sh
            python3 main.py
            ```
        """.trimIndent())
    }

    private fun writeWindowsExeProject(root: File) {
        write(root, "main.c", """
            #include <stdio.h>
            #include <string.h>

            int main(void) {
                char name[64];
                printf("Hello from Windows! (mingw-w64 / box64-wine)\n");
                printf("Enter your name: ");
                fflush(stdout);
                if (fgets(name, sizeof(name), stdin)) {
                    name[strcspn(name, "\n")] = '\0';
                    printf("Nice to meet you, %s!\n", name);
                }
                return 0;
            }
        """.trimIndent())
        write(root, "README.md", """
            # Windows .exe Project

            Cross-compiles a native x86-64 Windows .exe on-device.

            ## Build

            Tools > Wine & Box64 > **Build .exe** (uses clang + mingw-w64 sysroot)

            ## Run

            Tools > Wine & Box64 > **Run .exe** (runs via box64 → wine64)

            Console apps read stdin from the input line at the bottom of the console dialog.
        """.trimIndent())
    }

    private fun write(dir: File, name: String, content: String) {
        writeIfChanged(File(dir, name), content)
    }

    private fun writeIfChanged(file: File, content: String) {
        if (file.exists() && file.readText() == content) return
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun upsertGradleProperty(lines: MutableList<String>, key: String, value: String) {
        val prefix = "$key="
        val index = lines.indexOfFirst { it.trim().startsWith(prefix) }
        val line = "$key=$value"
        if (index >= 0) lines[index] = line else lines.add(line)
    }
}
