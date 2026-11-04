package com.aide.mobilestudio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * On-device HTML viewer that loads local HTML files in a WebView.
 * Supports editing HTML files and viewing them with full CSS/JS support.
 */
class HtmlViewerActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar

    companion object {
        private const val EXTRA_HTML_PATH = "html_path"
        private const val EXTRA_HTML_ASSET = "html_asset"
        private const val EXTRA_TITLE = "title"

        /** Open a bundled asset HTML file */
        fun openAsset(context: Context, assetName: String, title: String = "HTML Viewer") {
            val intent = Intent(context, HtmlViewerActivity::class.java).apply {
                putExtra(EXTRA_HTML_ASSET, assetName)
                putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        /** Open a local file path */
        fun openFile(context: Context, filePath: String, title: String = "HTML Viewer") {
            val intent = Intent(context, HtmlViewerActivity::class.java).apply {
                putExtra(EXTRA_HTML_PATH, filePath)
                putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "HTML Viewer"
        this.title = title

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.setSupportZoom(true)
            settings.defaultTextEncodingName = "UTF-8"
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar.visibility = View.GONE
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = newProgress
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    return true
                }
            }
        }

        progressBar = ProgressBar(this).apply {
            isIndeterminate = false
            max = 100
            visibility = View.VISIBLE
        }

        val rootLayout = android.widget.FrameLayout(this).apply {
            addView(webView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(progressBar, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.TOP
            })
        }

        setContentView(rootLayout)

        loadContent()
    }

    private fun loadContent() {
        val assetName = intent.getStringExtra(EXTRA_HTML_ASSET)
        val filePath = intent.getStringExtra(EXTRA_HTML_PATH)

        when {
            assetName != null -> {
                webView.loadUrl("file:///android_asset/$assetName")
            }
            filePath != null -> {
                val file = File(filePath)
                if (file.exists()) {
                    webView.loadUrl("file://${file.absolutePath}")
                } else {
                    Toast.makeText(this, "File not found: $filePath", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            else -> {
                Toast.makeText(this, "No HTML source specified", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
