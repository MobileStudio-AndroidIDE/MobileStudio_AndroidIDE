package com.aide.mobilestudio.web

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.FileObserver
import android.view.Gravity
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.aide.mobilestudio.StudioUi
import java.io.File
import java.io.FileInputStream

/**
 * Live preview of a project's own HTML, CSS and JavaScript.
 *
 * The document is loaded from the virtual host `https://mobilestudio.local/`
 * and every request is served from disk by [WebPathResolver], rather than
 * loading the file over `file://`. That makes relative stylesheet, script and
 * image references resolve the way a browser resolves them, keeps the page's
 * origin stable so `fetch` works, and lets remote content be refused outright
 * instead of silently reaching the network.
 *
 * Console output is surfaced in a status bar: a preview that hides a JS
 * exception is worse than useless.
 */
class HtmlPreviewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var consoleBar: TextView
    private lateinit var progress: ProgressBar
    private var htmlFile: File? = null
    private var observer: FileObserver? = null
    private val consoleLines = ArrayDeque<String>(CONSOLE_LINES_KEPT)

    companion object {
        private const val EXTRA_PATH = "extra_html_path"
        private const val CONSOLE_LINES_KEPT = 12
        private const val RELOAD_DEBOUNCE_MS = 400L

        fun start(context: Context, file: File) {
            context.startActivity(
                Intent(context, HtmlPreviewActivity::class.java)
                    .putExtra(EXTRA_PATH, file.absolutePath),
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_PATH)?.let { File(it) }
        if (file == null || !file.isFile) {
            finish()
            return
        }
        htmlFile = file
        setContentView(buildLayout(file))
        configureWebView()
        load()
        watchForChanges(file)
    }

    // ------------------------------------------------------------ chrome

    private fun buildLayout(file: File): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
        }

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(StudioUi.color(StudioUi.Surface))

            addView(TextView(this@HtmlPreviewActivity).apply {
                text = file.name
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(View(this@HtmlPreviewActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(TextView(this@HtmlPreviewActivity).apply {
                text = "Reload"
                setTextColor(StudioUi.color(StudioUi.Blue))
                textSize = 13f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { load() }
            })
        })

        progress = ProgressBar(this).apply {
            isIndeterminate = false
            max = 100
            visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)))

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

            addView(webViewHost(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ))

            consoleBar = TextView(this@HtmlPreviewActivity).apply {
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundColor(StudioUi.color(StudioUi.Surface))
                visibility = View.GONE
                maxLines = 4
                gravity = Gravity.START
                setOnClickListener { clearConsole() }
            }
            addView(consoleBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        })

        return root
    }

    private fun webViewHost(): View {
        webView = WebView(this)
        return FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(webView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
    }

    // ------------------------------------------------------------ web view

    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Needed so the interceptor can read the project's own files.
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            defaultTextEncodingName = "UTF-8"
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                return serveLocal(url)
            }

            // Keep navigation inside the preview so a link to another local
            // page shows that page instead of leaving the app.
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("https://${WebPathResolver.LOCAL_HOST}/")) return false
                appendConsole("blocked navigation to remote URL: $url")
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progress.visibility = View.GONE
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                progress.progress = newProgress
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage ?: return true
                val level = consoleMessage.messageLevel() ?: ConsoleMessage.MessageLevel.LOG
                val text = "${level.name.lowercase()}: ${consoleMessage.message()}" +
                    " (${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                appendConsole(text)
                return true
            }
        }
    }

    /**
     * Serves a `mobilestudio.local` URL from the project directory. Anything
     * else is refused with an empty response rather than left to the network,
     * so a preview never reaches out for remote content.
     */
    private fun serveLocal(url: String): WebResourceResponse? {
        val dir = htmlFile?.parentFile ?: return emptyResponse()
        if (!url.startsWith("https://${WebPathResolver.LOCAL_HOST}")) return emptyResponse()

        val relative = url.removePrefix("https://${WebPathResolver.LOCAL_HOST}").ifEmpty { "/index.html" }
        val file = WebPathResolver.resolveFile(dir, relative) ?: return emptyResponse()
        if (!file.isFile) return missingResponse()

        return runCatching {
            WebResourceResponse(
                WebPathResolver.mimeTypeFor(file.name),
                "UTF-8",
                FileInputStream(file),
            )
        }.getOrNull() ?: missingResponse()
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "UTF-8", "".byteInputStream())

    private fun missingResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not found", emptyMap(), "".byteInputStream(),
    )

    // ------------------------------------------------------------ lifecycle

    private fun load() {
        val file = htmlFile ?: return
        clearConsole()
        val relative = runCatching {
            file.canonicalFile.relativeTo(file.parentFile!!.canonicalFile).path
        }.getOrDefault(file.name)
        webView.loadUrl("${WebPathResolver.BASE_URL.removeSuffix("index.html")}$relative")
    }

    /**
     * Reloads when the HTML or anything it references changes, so the preview
     * follows edits to a stylesheet or script without the user pressing
     * reload. The debounce keeps a save that touches several files from
     * reloading once per file.
     */
    private fun watchForChanges(file: File) {
        val dir = file.parentFile ?: return
        var lastReload = 0L
        val watched = HashSet<String>()
        HtmlPreviewSources.dependencies(file).forEach { watched.add(it.absolutePath) }
        watched.add(file.absolutePath)

        runCatching {
            val fileObserver = object : FileObserver(dir, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    path ?: return
                    if (!watched.contains(File(dir, path).absolutePath)) return
                    val now = System.currentTimeMillis()
                    if (now - lastReload < RELOAD_DEBOUNCE_MS) return
                    lastReload = now
                    runOnUiThread { if (!isFinishing) load() }
                }
            }
            fileObserver.startWatching()
            observer = fileObserver
        }
    }

    private fun appendConsole(line: String) {
        runOnUiThread {
            consoleLines.addLast(line)
            while (consoleLines.size > CONSOLE_LINES_KEPT) consoleLines.removeFirst()
            consoleBar.visibility = View.VISIBLE
            consoleBar.text = consoleLines.joinToString("\n")
        }
    }

    private fun clearConsole() {
        consoleLines.clear()
        consoleBar.text = ""
        consoleBar.visibility = View.GONE
    }

    override fun onDestroy() {
        observer?.runCatching { stopWatching() }
        observer = null
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
