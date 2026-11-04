package com.aide.mobilestudio

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.toColorInt
import java.io.File

class ApkSigningActivity : AppCompatActivity() {

    private lateinit var projectDir: File
    private lateinit var tvOutput: TextView
    private lateinit var progressBar: ProgressBar
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectDir = File(intent.getStringExtra("project_dir") ?: return finish())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#1E2227".toColorInt())
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        root.addView(makeTopBar())

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        content.addView(makeSectionLabel("Keystore"))
        content.addView(buildKeystoreSection())

        content.addView(makeSectionLabel("APK Selection"))
        content.addView(buildApkSection())

        content.addView(makeSectionLabel("Sign & Install"))
        content.addView(buildSignSection())

        content.addView(makeSectionLabel("Output"))
        tvOutput = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor("#C9D1D9".toColorInt())
            setBackgroundColor("#0F1218".toColorInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "Ready.\n"
        }
        content.addView(tvOutput)

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply { topMargin = dp(8) }
        }
        content.addView(progressBar)

        scroll.addView(content)
        root.addView(scroll)

        setContentView(root)
    }

    private fun makeTopBar(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
            addView(TextView(this@ApkSigningActivity).apply {
                text = if (AppLanguage.isKorean(this@ApkSigningActivity)) "APK 서명" else "APK Signing"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
        }
    }

    private fun buildKeystoreSection(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#2E3540".toColorInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        container.addView(TextView(this).apply {
            text = if (AppLanguage.isKorean(this@ApkSigningActivity))
                "기본 debug.keystore 사용" else "Use default debug.keystore"
            textSize = 13f
            setTextColor("#C3CCD8".toColorInt())
            setPadding(0, 0, 0, dp(8))
        })

        val debugKeystore = File(projectDir, "debug.keystore")
        if (debugKeystore.exists()) {
            container.addView(makeInfoRow("debug.keystore", "${debugKeystore.length() / 1024}KB"))
        } else {
            container.addView(makeInfoRow("debug.keystore", "Not found"))
        }

        container.addView(makeButton("Generate New Keystore") {
            showGenerateKeystoreDialog()
        })

        return container
    }

    private fun buildApkSection(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#2E3540".toColorInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val apkFiles = findApkFiles()
        if (apkFiles.isEmpty()) {
            container.addView(TextView(this).apply {
                text = if (AppLanguage.isKorean(this@ApkSigningActivity))
                    "APK 파일이 없습니다. 먼저 빌드하세요." else "No APK files. Build first."
                textSize = 13f
                setTextColor("#76839A".toColorInt())
            })
        } else {
            apkFiles.forEach { apk ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                row.addView(TextView(this).apply {
                    text = apk.name
                    textSize = 12f
                    setTextColor("#C3CCD8".toColorInt())
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                row.addView(TextView(this).apply {
                    text = "${apk.length() / 1024}KB"
                    textSize = 11f
                    setTextColor("#76839A".toColorInt())
                })
                container.addView(row)
            }
        }

        return container
    }

    private fun buildSignSection(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        container.addView(makeButton("Sign APK (debug)") {
            signApk("debug")
        })

        container.addView(makeButton("Sign APK (release)") {
            showReleaseSignDialog()
        })

        return container
    }

    private fun signApk(keystoreType: String) {
        val apkFile = findApkFiles().firstOrNull()
        if (apkFile == null) {
            tvOutput.append("No APK found.\n")
            return
        }

        progressBar.visibility = View.VISIBLE
        tvOutput.append("Signing ${apkFile.name}...\n")

        Thread {
            try {
                val keystoreFile = File(projectDir, "debug.keystore")
                if (!keystoreFile.exists()) {
                    generateDebugKeystore(keystoreFile)
                }

                val signedApk = File(apkFile.parentFile, apkFile.nameWithoutExtension + "-signed.apk")

                val cmd = listOf(
                    "jarsigner",
                    "-keystore", keystoreFile.absolutePath,
                    "-storepass", BuildToolsConfig.signingStorePassword(this@ApkSigningActivity),
                    "-keypass", BuildToolsConfig.signingKeyPassword(this@ApkSigningActivity),
                    "-signedjar", signedApk.absolutePath,
                    apkFile.absolutePath,
                    BuildToolsConfig.signingKeyAlias(this@ApkSigningActivity)
                )

                val process = ProcessBuilder(cmd)
                    .directory(projectDir)
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                handler.post {
                    progressBar.visibility = View.GONE
                    if (exitCode == 0) {
                        tvOutput.append("Signed: ${signedApk.name}\n")
                        tvOutput.append("Size: ${signedApk.length() / 1024}KB\n")
                        showInstallOption(signedApk)
                    } else {
                        tvOutput.append("Signing failed:\n$output\n")
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    progressBar.visibility = View.GONE
                    tvOutput.append("Error: ${e.message}\n")
                }
            }
        }.start()
    }

    private fun showInstallOption(apkFile: File) {
        AlertDialog.Builder(this)
            .setTitle("Install APK?")
            .setMessage("Install ${apkFile.name}?")
            .setPositiveButton("Install") { _, _ ->
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    apkFile
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGenerateKeystoreDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        fun field(hint: String): EditText {
            return EditText(this).apply {
                this.hint = hint
                setTextColor(Color.WHITE)
                setHintTextColor("#555E6B".toColorInt())
                textSize = 14f
                background = GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 10f
                    setStroke(1, StudioUi.color(StudioUi.Border))
                }
                setPadding(24, 18, 24, 18)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 8 }
            }
        }

        layout.addView(field("Alias (e.g. mykey)"))
        layout.addView(field("Password"))
        layout.addView(field("Organization (optional)"))

        AlertDialog.Builder(this)
            .setTitle("Generate Keystore")
            .setView(layout)
            .setPositiveButton("Generate") { _, _ ->
                val keystoreFile = File(projectDir, "release.keystore")
                generateDebugKeystore(keystoreFile)
                tvOutput.append("Generated: release.keystore\n")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showReleaseSignDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 16)
        }

        fun field(hint: String, isPassword: Boolean = false): EditText {
            return EditText(this).apply {
                this.hint = hint
                inputType = if (isPassword) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
                setTextColor(Color.WHITE)
                setHintTextColor("#555E6B".toColorInt())
                textSize = 14f
                background = GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 10f
                    setStroke(1, StudioUi.color(StudioUi.Border))
                }
                setPadding(24, 18, 24, 18)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = 8 }
            }
        }

        layout.addView(field("Keystore path"))
        layout.addView(field("Store password", true))
        layout.addView(field("Key alias"))
        layout.addView(field("Key password", true))

        AlertDialog.Builder(this)
            .setTitle("Release Signing")
            .setView(layout)
            .setPositiveButton("Sign") { _, _ ->
                tvOutput.append("Release signing initiated...\n")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateDebugKeystore(file: File) {
        try {
            val cmd = listOf(
                "keytool", "-genkeypair",
                "-keystore", file.absolutePath,
                "-storepass", BuildToolsConfig.signingStorePassword(this),
                "-alias", BuildToolsConfig.signingKeyAlias(this),
                "-keypass", BuildToolsConfig.signingKeyPassword(this),
                "-keyalg", BuildToolsConfig.signingAlgorithm(this),
                "-keysize", BuildToolsConfig.signingKeySize(this).toString(),
                "-validity", BuildToolsConfig.signingValidityDays(this).toString(),
                "-dname", BuildToolsConfig.signingDn(this)
            )
            ProcessBuilder(cmd).start().waitFor()
        } catch (_: Exception) {}
    }

    private fun findApkFiles(): List<File> = BuildApkLocator.allInstallableApks(
        File(projectDir, "app/build/outputs/apk")
    )

    private fun makeInfoRow(label: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(TextView(this@ApkSigningActivity).apply {
                text = label
                textSize = 12f
                setTextColor("#C3CCD8".toColorInt())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(TextView(this@ApkSigningActivity).apply {
                text = value
                textSize = 12f
                setTextColor("#76839A".toColorInt())
            })
        }
    }

    private fun makeSectionLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor("#C3CCD8".toColorInt())
            setPadding(0, dp(16), 0, dp(8))
        }
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor("#3E4451".toColorInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minimumHeight = 0
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
