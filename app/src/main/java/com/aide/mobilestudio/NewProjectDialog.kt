package com.aide.mobilestudio

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

object NewProjectDialog {

    /** Activity에서 호출: 스토리지 퍼미션이 있으면 true, 없으면 요청 후 false */
    fun checkAndRequestStoragePermissions(activity: Activity): Boolean {
        return true
    }

    fun show(
        context: Context,
        generator: ProjectTemplateGenerator,
        onCreated: (projectDir: File, cfg: ProjectTemplateGenerator.ProjectConfig) -> Unit
    ) {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(24), context.dp(12), context.dp(24), context.dp(8))
            setBackgroundColor(StudioUi.palette.card)
        }

        fun label(text: String) = TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(StudioUi.palette.textSoft)
            setPadding(0, 16, 0, 4)
        }

        fun field(hint: String, default: String = "") = EditText(context).apply {
            this.hint = hint
            setText(default)
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.muted)
            textSize = 14f
            background = GradientDrawable().apply {
                setColor(StudioUi.palette.surface2)
                cornerRadius = 10f
                setStroke(1, StudioUi.palette.border)
            }
            setPadding(24, 18, 24, 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 4 }
        }

        root.addView(label(AppLanguage.string(context, R.string.projects_app_name)))
        val etAppName = field(AppLanguage.string(context, R.string.newproject_app_name_hint))
        root.addView(etAppName)

        root.addView(label(AppLanguage.string(context, R.string.projects_package_name)))
        val etPackage = field("com.example.myapp", "com.example.")
        root.addView(etPackage)

        root.addView(label(AppLanguage.string(context, R.string.projects_project_folder)))
        val etProjectRoot = field(
            AppLanguage.string(context, R.string.newproject_project_folder_hint),
            generator.projectsRoot.absolutePath
        )
        root.addView(etProjectRoot)

        val tvSavePath = TextView(context).apply {
            text = AppLanguage.string(context, R.string.newproject_save_path_format)
                .replace("%1\$s", generator.projectsRoot.absolutePath)
                .replace("<ProjectName>", "&lt;ProjectName&gt;")
            textSize = 11f
            setTextColor(StudioUi.palette.muted)
            typeface = Typeface.MONOSPACE
            setPadding(0, 4, 0, 12)
        }
        root.addView(tvSavePath)

        etAppName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val derived = ProjectTemplateGenerator.ProjectConfig.derivePackage(etAppName.text.toString())
                etPackage.setText(derived)
                tvSavePath.text = AppLanguage.string(context, R.string.newproject_save_path_format)
                    .replace("%1\$s", etProjectRoot.text.toString().trim().ifEmpty { generator.projectsRoot.absolutePath })
                    .replace("<ProjectName>", etAppName.text.toString().trim().ifEmpty { "<ProjectName>" })
            }
        })
        etProjectRoot.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvSavePath.text = AppLanguage.string(context, R.string.newproject_save_path_format)
                    .replace("%1\$s", s.toString().trim().ifEmpty { generator.projectsRoot.absolutePath })
                    .replace("<ProjectName>", etAppName.text.toString().trim().ifEmpty { "<ProjectName>" })
            }
        })

        root.addView(label(AppLanguage.string(context, R.string.projects_project_type)))

        // Android section (shown by default)
        val androidSection = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        androidSection.addView(label(AppLanguage.string(context, R.string.projects_template)))
        val androidTemplates = listOf(
            AppLanguage.string(context, R.string.newproject_type_android),
            "Empty Compose Activity",
            "Empty Activity (Java + XML)",
            "Basic Activity (Material + FAB)",
            "Navigation Drawer Activity",
            "Bottom Navigation Activity",
            "Settings Activity",
            "Native C/C++ (Kotlin + NDK)",
            "JAR App (Java)",
            "Game: Pong (OpenGL ES 3.0)",
            "Game: Snake (OpenGL ES 3.0)",
            "Game: 3D Scene (OpenGL ES 3.0)",
            "Game: Voxel World (OpenGL ES 3.0)",
            "Game: Lua 2D (LuaJ + Canvas)",
            "Game: Vulkan 3D (Vulkan API)",
            "Game: JavaScript 2D (Rhino)",
            "Game: Java 2D (Canvas)",
            "Game: Python 2D (Chaquopy)",
            "Game: GDScript (Canvas)",
            "Game: C# (MonoGame-style)"
        )
        val spinnerTemplate = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, androidTemplates)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        androidSection.addView(spinnerTemplate)

        // Build Script Format (KTS vs Groovy)
        androidSection.addView(label(AppLanguage.string(context, R.string.newproject_build_script)))
        val radioGroupBuildDsl = RadioGroup(context).apply {
            orientation = RadioGroup.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        val rbKts = RadioButton(context).apply {
            text = AppLanguage.string(context, R.string.newproject_build_dsl_kts)
            id = View.generateViewId()
            isChecked = true
            setTextColor(StudioUi.palette.textSoft)
        }
        val rbGroovy = RadioButton(context).apply {
            text = AppLanguage.string(context, R.string.newproject_build_dsl_groovy)
            id = View.generateViewId()
            setTextColor(StudioUi.palette.textSoft)
        }
        radioGroupBuildDsl.addView(rbKts)
        radioGroupBuildDsl.addView(rbGroovy)
        androidSection.addView(radioGroupBuildDsl)

        androidSection.addView(label(AppLanguage.string(context, R.string.newproject_minimum_sdk)))
        val minSdkLabels = listOf(
            "21 (Android 5.0)", "24 (Android 7.0)", "26 (Android 8.0)",
            "28 (Android 9.0)", "29 (Android 10)", "31 (Android 12)", "33 (Android 13)"
        )
        val minSdkValues = listOf(21, 24, 26, 28, 29, 31, 33)
        val spinnerMinSdk = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, minSdkLabels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(1)
        }
        androidSection.addView(spinnerMinSdk)
        androidSection.addView(label(AppLanguage.string(context, R.string.newproject_options)))
        val cbViewBinding = CheckBox(context).apply {
            text = AppLanguage.string(context, R.string.newproject_enable_view_binding)
            isChecked = true
            setTextColor(StudioUi.palette.textSoft)
        }
        androidSection.addView(cbViewBinding)
        root.addView(androidSection)

        // Python section (hidden by default)
        val pythonSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pythonSection.addView(label(AppLanguage.string(context, R.string.projects_template)))
        val tvPythonOnly = TextView(context).apply {
            text = AppLanguage.string(context, R.string.newproject_python_template)
            textSize = 14f
            setTextColor(StudioUi.palette.success)
            setPadding(24, 14, 24, 14)
            background = GradientDrawable().apply {
                setColor(StudioUi.palette.surface2)
                cornerRadius = 10f
                setStroke(1, StudioUi.palette.border)
            }
        }
        pythonSection.addView(tvPythonOnly)
        root.addView(pythonSection)

        // Windows .exe section (hidden by default)
        val windowsSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        windowsSection.addView(label(AppLanguage.string(context, R.string.projects_template)))
        val tvWindowsOnly = TextView(context).apply {
            text = AppLanguage.string(context, R.string.newproject_windows_template)
            textSize = 14f
            setTextColor(StudioUi.palette.warning)
            setPadding(24, 14, 24, 14)
            background = GradientDrawable().apply {
                setColor(StudioUi.palette.surface2)
                cornerRadius = 10f
                setStroke(1, StudioUi.palette.border)
            }
        }
        windowsSection.addView(tvWindowsOnly)
        val tvWindowsNote = TextView(context).apply {
            text = AppLanguage.string(context, R.string.newproject_windows_note)
            textSize = 11f
            setTextColor(StudioUi.palette.muted)
            setPadding(24, 4, 24, 8)
        }
        windowsSection.addView(tvWindowsNote)
        root.addView(windowsSection)

        val godotSection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        godotSection.addView(label(AppLanguage.string(context, R.string.projects_template)))
        val tvGodotOnly = TextView(context).apply {
            text = AppLanguage.string(context, R.string.newproject_godot_template)
            textSize = 14f
            setTextColor(StudioUi.palette.success)
            setPadding(24, 14, 24, 14)
            background = GradientDrawable().apply {
                setColor(StudioUi.palette.surface2)
                cornerRadius = 10f
                setStroke(1, StudioUi.palette.border)
            }
        }
        godotSection.addView(tvGodotOnly)
        val tvGodotNote = TextView(context).apply {
            text = AppLanguage.string(context, R.string.newproject_godot_note)
            textSize = 11f
            setTextColor(StudioUi.palette.muted)
            setPadding(24, 4, 24, 8)
        }
        godotSection.addView(tvGodotNote)
        root.addView(godotSection)

        val radioGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        var selectedType = 0
        val pkgLabel = label(AppLanguage.string(context, R.string.projects_package_name))
        fun applyType(t: Int) {
            selectedType = t
            androidSection.visibility = if (t == 0) View.VISIBLE else View.GONE
            pythonSection.visibility = if (t == 1) View.VISIBLE else View.GONE
            windowsSection.visibility = if (t == 2) View.VISIBLE else View.GONE
            godotSection.visibility = if (t == 3) View.VISIBLE else View.GONE
            etPackage.visibility = if (t == 0) View.VISIBLE else View.GONE
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child === pkgLabel) {
                    child.visibility = if (t == 0) View.VISIBLE else View.GONE
                }
            }
        }
        val rbAndroid = RadioButton(context).apply {
            text = AppLanguage.string(context, R.string.newproject_type_android)
            isChecked = true
            setTextColor(StudioUi.palette.text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { applyType(0) }
        }
        val rbPython = RadioButton(context).apply {
            text = AppLanguage.string(context, R.string.newproject_type_python)
            setTextColor(StudioUi.palette.text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { applyType(1) }
        }
        radioGroup.addView(rbAndroid)
        radioGroup.addView(rbPython)
        val rbWindows = RadioButton(context).apply {
            text = AppLanguage.string(context, R.string.newproject_type_windows)
            setTextColor(StudioUi.palette.text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { applyType(2) }
        }
        radioGroup.addView(rbWindows)
        val rbGodot = RadioButton(context).apply {
            text = AppLanguage.string(context, R.string.newproject_type_godot)
            setTextColor(StudioUi.palette.text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { applyType(3) }
        }
        radioGroup.addView(rbGodot)
        root.addView(radioGroup)

        val tvError = TextView(context).apply {
            setTextColor(StudioUi.palette.error)
            textSize = 11f
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
        }
        root.addView(tvError)

        val dialog = AlertDialog.Builder(context)
            .setTitle(AppLanguage.string(context, R.string.newproject_title))
            .setView(ScrollView(context).apply {
                addView(root)
                setBackgroundColor(StudioUi.palette.card)
            })
            .setPositiveButton(AppLanguage.string(context, R.string.action_create), null)
            .setNegativeButton(AppLanguage.string(context, R.string.action_cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.palette.card)
                    cornerRadius = 16f
                }
            )
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(StudioUi.palette.muted)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(StudioUi.palette.accent)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val appName = etAppName.text.toString().trim()
                val pkgName = etPackage.text.toString().trim()
                val projectRoot = File(etProjectRoot.text.toString().trim().ifEmpty { generator.projectsRoot.absolutePath })

                when {
                    appName.isEmpty() -> {
                        tvError.text = AppLanguage.string(context, R.string.projects_app_name_empty)
                        tvError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    appName.contains(Regex("[^a-zA-Z0-9_ ]")) -> {
                        tvError.text = AppLanguage.string(context, R.string.projects_app_name_invalid)
                        tvError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    selectedType == 0 && !pkgName.matches(Regex("[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+")) -> {
                        tvError.text = AppLanguage.string(context, R.string.projects_package_invalid)
                        tvError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    File(projectRoot, appName).exists() -> {
                        tvError.text = AppLanguage.string(context, R.string.projects_already_exists).replace("%s", appName)
                        tvError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                }

                tvError.visibility = View.GONE

                val template = when (selectedType) {
                0 -> {
                    when (spinnerTemplate.selectedItemPosition) {
                        0 -> ProjectTemplateGenerator.Template.EMPTY_ACTIVITY
                        1 -> ProjectTemplateGenerator.Template.EMPTY_COMPOSE_ACTIVITY
                        2 -> ProjectTemplateGenerator.Template.EMPTY_JAVA_ACTIVITY
                        3 -> ProjectTemplateGenerator.Template.BASIC_ACTIVITY
                        4 -> ProjectTemplateGenerator.Template.NAVIGATION_DRAWER
                        5 -> ProjectTemplateGenerator.Template.BOTTOM_NAVIGATION
                        6 -> ProjectTemplateGenerator.Template.SETTINGS_ACTIVITY
                        7 -> ProjectTemplateGenerator.Template.NATIVE_CPP
                        8 -> ProjectTemplateGenerator.Template.JAR_APP
                        9 -> ProjectTemplateGenerator.Template.GAME_2D_PONG
                        10 -> ProjectTemplateGenerator.Template.GAME_2D_SNAKE
                        11 -> ProjectTemplateGenerator.Template.GAME_3D_SCENE
                        12 -> ProjectTemplateGenerator.Template.GAME_VOXEL_WORLD
                        13 -> ProjectTemplateGenerator.Template.GAME_LUA_2D
                        14 -> ProjectTemplateGenerator.Template.GAME_VULKAN
                        15 -> ProjectTemplateGenerator.Template.GAME_JS_2D
                        16 -> ProjectTemplateGenerator.Template.GAME_JAVA_2D
                        17 -> ProjectTemplateGenerator.Template.GAME_PYTHON_2D
                        18 -> ProjectTemplateGenerator.Template.GAME_GDSCRIPT
                        19 -> ProjectTemplateGenerator.Template.GAME_CSHARP_2D
                        else -> ProjectTemplateGenerator.Template.EMPTY_ACTIVITY
                    }
                }
                1 -> ProjectTemplateGenerator.Template.PYTHON_SCRIPT
                2 -> ProjectTemplateGenerator.Template.WINDOWS_EXE
                else -> ProjectTemplateGenerator.Template.GODOT_PROJECT
            }

                val toolchain = ToolchainManager(context)
                val ndkVersion = toolchain.activeDir(ToolchainManager.Kind.NDK)?.let { ndkDir ->
                    val props = File(ndkDir, "source.properties")
                    if (props.exists()) {
                        props.readText().lines()
                            .firstOrNull { it.startsWith("Pkg.Revision") }
                            ?.substringAfter("=")?.trim()
                    } else null
                } ?: ""
                val cfg = ProjectTemplateGenerator.ProjectConfig(
                    appName        = appName,
                    packageName    = when (selectedType) {
                    0 -> pkgName
                    1 -> "python.$appName"
                    2 -> "win.$appName"
                    else -> "godot.$appName"
                },
                    minSdk         = if (selectedType == 0) minSdkValues[spinnerMinSdk.selectedItemPosition] else 21,
                    template       = template,
                    useViewBinding = selectedType == 0 && cbViewBinding.isChecked,
                    projectRoot    = projectRoot,
                    buildDsl       = if (rbKts.isChecked) ProjectTemplateGenerator.BuildDsl.KTS else ProjectTemplateGenerator.BuildDsl.GROOVY,
                    ndkVersion     = ndkVersion
                )

                dialog.dismiss()

                Thread {
                    try {
                        val dir = generator.create(cfg)
                        Handler(Looper.getMainLooper()).post {
                            onCreated(dir, cfg)
                            Toast.makeText(
                                context,
                                AppLanguage.string(context, R.string.projects_created).replace("%s", StorageHelper.displayPath(context, cfg.appName)),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            val sw = java.io.StringWriter()
                            java.io.PrintWriter(sw).use { pw -> e.printStackTrace(pw) }
                            val stack = sw.toString()
                            val scrollView = android.widget.ScrollView(context)
                            val errorText = android.widget.TextView(context).apply {
                                text = stack
                                textSize = 10f
                                typeface = android.graphics.Typeface.MONOSPACE
                                setTextColor(StudioUi.color(StudioUi.Red))
                                setPadding(30, 20, 30, 20)
                            }
                            scrollView.addView(errorText)
                            MaterialAlertDialogBuilder(context)
                                .setTitle(AppLanguage.string(context, R.string.newproject_creation_failed_title))
                                .setView(scrollView)
                                .setPositiveButton(AppLanguage.string(context, R.string.action_ok), null)
                                .show()
                        }
                    }
                }.start()
            }
        }

        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
}
