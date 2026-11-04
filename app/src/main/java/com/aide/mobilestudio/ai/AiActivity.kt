package com.aide.mobilestudio.ai

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aide.mobilestudio.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * AI panel — opencode Serve API client.
 *
 * Flow: install → `opencode serve` → SSE `/event` → session → chat.
 * Messages stream in over SSE; permission requests appear as dialogs
 * (Allow once / Always / Deny) which answer
 * `POST /session/:id/permissions/:permissionID`.
 */
class AiActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AiActivity"
        private const val PREFS = "opencode_ai"
        private const val KEY_SESSION = "session_id"
        private const val KEY_MODEL = "model"
        private const val KEY_AGENT = "agent"
        private const val THINKING_MAX_CHARS = 8000
        private const val THINKING_TAIL_CHARS = 700
        private const val THINKING_FLUSH_MS = 150L
        private const val MAX_PROMPT_CHARS = 5000
        private const val MESSAGE_CLAMP_LINES = 10
        private const val ID_SHOW_MORE = 0x5101

        fun start(context: Context, projectDir: String? = null) {
            val intent = Intent(context, AiActivity::class.java)
            if (projectDir != null) intent.putExtra("project_dir", projectDir)
            context.startActivity(intent)
        }
    }

    private lateinit var env: OpencodeEnvironment
    private lateinit var service: AiService
    private lateinit var chatContainer: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var progressLayout: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText

    private var serveInstance: OpencodeEnvironment.ServeInstance? = null
    private var eventStream: AiService.EventStream? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var sessionId: String? = null
    @Volatile private var connected = false
    @Volatile private var model: String? = null
    @Volatile private var agent: String? = null

    /** assistant messageID → its bubble (streamed over SSE) */
    private val bubbles = ConcurrentHashMap<String, TextView>()
    /** partID → text already rendered (delta/snapshot detection) */
    private val rendered = ConcurrentHashMap<String, String>()
    /** messageID → live reasoning card (streams the model's thinking) */
    private val thinkingCards = ConcurrentHashMap<String, ThinkingCard>()
    private var thinkingFlushPending = false
    private val thinkingFlushRunnable = Runnable {
        thinkingFlushPending = false
        for (tc in thinkingCards.values) {
            if (tc.dirty) {
                tc.dirty = false
                renderThinking(tc)
            }
        }
    }

    private class ThinkingCard(
        val card: LinearLayout,
        val header: TextView,
        val body: TextView,
        var accum: String = "",
        var expanded: Boolean = false,
        var done: Boolean = false,
        var dirty: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        env = OpencodeEnvironment(this)
        service = AiService()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        model = prefs.getString(KEY_MODEL, null)
        agent = prefs.getString(KEY_AGENT, null)

        setContentView(buildLayout())
        initialize()
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E2227"))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusText = TextView(this).apply {
            text = "Initializing..."
            setTextColor(Color.parseColor("#ABB2BF"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.parseColor("#21252B"))
        }

        progressLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 12, 16, 12)
            visibility = View.GONE
            addView(ProgressBar(this@AiActivity, null, android.R.attr.progressBarStyleSmall).apply {
                layoutParams = LinearLayout.LayoutParams(40, 40).apply { marginEnd = 12 }
            })
            addView(TextView(this@AiActivity).apply {
                text = "Starting opencode..."
                setTextColor(Color.parseColor("#ABB2BF"))
                textSize = 13f
            })
        }

        root.addView(statusText)
        root.addView(progressLayout)
        root.addView(buildToolbar())

        scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(chatContainer)
        root.addView(scrollView)
        root.addView(buildInputRow())
        return root
    }

    private fun toolButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3E4451"))
            textSize = 10f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 6 }
            setPadding(18, 4, 18, 4)
            minimumHeight = 0
            setOnClickListener { onClick() }
        }

    private fun buildToolbar(): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.parseColor("#21252B"))
        }
        row.addView(toolButton("Sessions") { pickSession() })
        row.addView(toolButton("Agent") { pickAgent() })
        row.addView(toolButton("Model") { pickModel() })
        row.addView(toolButton("Command") { pickCommand() })
        row.addView(toolButton("TUI") { openTui() })
        row.addView(toolButton("Stop") { stopGeneration() })
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun buildInputRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#21252B"))
        }
        input = EditText(this).apply {
            hint = "Ask opencode..."
            setHintTextColor(Color.parseColor("#6E7681"))
            setTextColor(Color.parseColor("#E6EDF3"))
            setBackgroundColor(Color.parseColor("#282C34"))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // 7 visible lines with internal scrolling; pastes are capped at 5000 chars.
            maxLines = 7
            filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_PROMPT_CHARS))
            setPadding(16, 12, 16, 12)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val send = Button(this).apply {
            text = "Send"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#238636"))
            textSize = 12f
            isAllCaps = false
            setPadding(24, 12, 24, 12)
            minimumHeight = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            setOnClickListener { sendPrompt() }
        }
        row.addView(input)
        row.addView(send)
        return row
    }

    // ------------------------------------------------------------------
    // Startup
    // ------------------------------------------------------------------

    private fun initialize() {
        Thread {
            showProgress(true)
            updateStatus("Installing opencode...")
            val ok = env.installAll { msg -> updateStatus(msg) }
            if (!ok) {
                mainHandler.post {
                    updateStatus("Install failed — see logcat (OpencodeEnvironment)")
                    showProgress(false)
                }
                return@Thread
            }

            updateStatus("Starting opencode serve...")
            val cwd = intent.getStringExtra("project_dir")?.let { java.io.File(it) }
            val serve = env.startServe(cwd)
            if (serve == null) {
                mainHandler.post {
                    updateStatus("Failed to start opencode serve (see logcat)")
                    showProgress(false)
                }
                return@Thread
            }
            serveInstance = serve

            var healthy = false
            for (i in 0 until 30) {
                if (!serve.isAlive) break
                if (runCatching { service.checkHealth() }.getOrDefault(false)) {
                    healthy = true
                    break
                }
                Thread.sleep(2_000)
            }
            if (!healthy) {
                val tail = serve.output().takeLast(1500)
                mainHandler.post {
                    updateStatus("opencode did not answer (exit=${serve.exitCode ?: "running"})")
                    if (tail.isNotBlank()) addMessage("system", tail)
                    showProgress(false)
                }
                return@Thread
            }

            connected = true
            eventStream = runCatching {
                service.connectEvents({ json -> handleEvent(json) }, { err ->
                    Log.w(TAG, "event stream error: $err")
                })
            }.getOrNull()

            val version = runCatching { service.version() }.getOrNull()
            ensureSession()

            mainHandler.post {
                showProgress(false)
                updateStatus("Connected" + (version?.let { " — opencode $it" } ?: ""))
                if (sessionId == null) {
                    addMessage("system", "No session available. Check the server logs.")
                } else if (chatContainer.childCount == 0) {
                    addMessage(
                        "system",
                        "Ready. Ask anything, or open the TUI in the terminal tab.\n" +
                            "Model: ${model ?: "server default"} · Agent: ${agent ?: "build"}"
                    )
                }
            }
        }.start()
    }

    private fun ensureSession() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val saved = prefs.getString(KEY_SESSION, null)
        val existing = saved?.let { id ->
            runCatching { service.session(id) }.getOrNull()
        }
        if (existing != null) {
            sessionId = existing.optString("id", saved)
            loadHistory()
            return
        }
        val created = runCatching {
            service.createSession(title = "MobileStudio")
        }.getOrNull()
        val id = created?.optString("id")?.takeIf { it.isNotEmpty() }
        sessionId = id
        if (id != null) prefs.edit().putString(KEY_SESSION, id).apply()
    }

    private fun loadHistory() {
        val id = sessionId ?: return
        val history = runCatching { service.messages(id, 50) }.getOrNull() ?: return
        mainHandler.post {
            for (i in 0 until history.length()) {
                val entry = history.optJSONObject(i) ?: continue
                val info = entry.optJSONObject("info") ?: continue
                val parts = entry.optJSONArray("parts") ?: continue
                val role = info.optString("role", "assistant")
                val text = buildString {
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") append(part.optString("text"))
                    }
                }
                if (text.isNotBlank()) addMessage(role, text)
            }
        }
    }

    // ------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------

    private fun handleEvent(json: JSONObject) {
        val type = json.optString("type", "")
        val props = json.optJSONObject("properties") ?: json
        when {
            type.contains("permission") -> handlePermission(props)
            type.contains("message.part") -> handlePart(props)
            type.contains("message.updated") -> handleMessageUpdated(props)
            type.contains("session.idle") -> mainHandler.post {
                thinkingCards.values.forEach { tc ->
                    if (!tc.done) {
                        tc.done = true
                        renderThinking(tc)
                    }
                }
                bubbles.values.forEach { refreshMessageClamp(it) }
                updateStatus("Ready")
            }
        }
    }

    private fun handleMessageUpdated(props: JSONObject) {
        val info = props.optJSONObject("message") ?: return
        val id = info.optString("id")
        if (id.isEmpty()) return
        val role = info.optString("role", "assistant")
        if (role != "assistant") return
        mainHandler.post {
            bubbles.computeIfAbsent(id) { createBubble() }
            updateStatus("Thinking...")
        }
    }

    private fun handlePart(props: JSONObject) {
        val part = props.optJSONObject("part") ?: return
        if (part.optString("sessionID").takeIf { it.isNotEmpty() }?.let { it != sessionId } == true) return
        val partId = part.optString("id")
        val messageId = part.optString("messageID")
        val text = part.optString("text")
        if (partId.isEmpty() || messageId.isEmpty()) return

        if (part.optString("type") == "reasoning") {
            mainHandler.post {
                updateThinking(messageId, text)
                updateStatus("Thinking...")
            }
            return
        }
        if (part.optString("type") == "tool") {
            val label = "▸ tool: " + part.optString("tool")
            mainHandler.post {
                val bubble = bubbles.computeIfAbsent(messageId) { createBubble() }
                val current = bubble.text?.toString() ?: ""
                if (!current.contains(label)) bubble.text = current + label + "\n"
                finishThinking(messageId)
            }
            return
        }
        if (part.optString("type") != "text") return

        val previous = rendered.put(partId, text) ?: ""
        val delta = when {
            previous.isEmpty() -> text
            text.startsWith(previous) -> text.substring(previous.length)
            previous.startsWith(text) -> ""
            else -> text
        }
        if (delta.isEmpty() && previous == text) return

        mainHandler.post {
            finishThinking(messageId)
            val pendingBubble = bubbles.remove("_pending")
            if (pendingBubble != null) bubbles[messageId] = pendingBubble
            val bubble = bubbles.computeIfAbsent(messageId) { createBubble() }
            val current = bubble.text?.toString() ?: ""
            bubble.text = if (delta.isEmpty() || previous.length > current.length) {
                text
            } else {
                current + delta
            }
            refreshMessageClamp(bubble)
            scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            updateStatus("Streaming...")
        }
    }

    private fun handlePermission(props: JSONObject) {
        val permission = props.optJSONObject("permission") ?: props
        val id = permission.optString("id")
        if (id.isEmpty()) return
        val status = permission.optString("status", "")
        if (status.isNotEmpty() && status != "pending") return
        val title = permission.optString("title").takeIf { it.isNotEmpty() }
            ?: permission.optString("type", "Permission request")
        val pattern = permission.optString("pattern")
        val kind = permission.optString("type")
        val sid = permission.optString("sessionID").takeIf { it.isNotEmpty() } ?: sessionId ?: return

        mainHandler.post {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(
                    buildString {
                        append("opencode requests permission.")
                        if (kind.isNotEmpty()) append("\nType: $kind")
                        if (pattern.isNotEmpty()) append("\nPattern: $pattern")
                    }
                )
                .setPositiveButton("Allow once") { _, _ -> answer(sid, id, "once") }
                .setNeutralButton("Always") { _, _ -> answer(sid, id, "always") }
                .setNegativeButton("Deny") { _, _ -> answer(sid, id, "reject") }
                .setCancelable(false)
                .show()
        }
    }

    private fun answer(sessionId: String, permissionId: String, response: String) {
        Thread {
            val ok = runCatching {
                service.respondPermission(sessionId, permissionId, response)
            }.getOrDefault(false)
            if (!ok) Log.w(TAG, "permission answer failed ($response)")
        }.start()
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private fun sendPrompt() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        if (!connected) {
            Toast.makeText(this, "Not connected yet", Toast.LENGTH_SHORT).show()
            return
        }
        val id = sessionId
        if (id == null) {
            Toast.makeText(this, "No session", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText("")
        addMessage("user", text)
        updateStatus("Sending...")
        val pending = createBubble()

        Thread {
            try {
                val async = runCatching {
                    service.promptAsync(id, text, model, agent)
                }.getOrDefault(false)
                if (async) {
                    bubbles["_pending"] = pending
                    return@Thread
                }
                // Fallback: blocking call (no SSE or endpoint unavailable)
                val response = service.sendMessage(id, text, model, agent)
                mainHandler.post {
                    val reply = extractReply(response)
                    pending.text = if (reply.isNotBlank()) reply else "(no response)"
                    bubbles.remove("_pending")
                    updateStatus("Ready")
                }
            } catch (e: Exception) {
                Log.e(TAG, "send failed", e)
                mainHandler.post {
                    pending.text = "Error: ${e.message}"
                    updateStatus("Error")
                }
            }
        }.start()
    }

    private fun extractReply(response: JSONObject?): String {
        if (response == null) return ""
        val parts = response.optJSONArray("parts") ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") append(part.optString("text"))
            }
        }
    }

    private fun stopGeneration() {
        val id = sessionId ?: return
        Thread {
            runCatching { service.abortSession(id) }
            mainHandler.post { updateStatus("Stopped") }
        }.start()
    }

    // ------------------------------------------------------------------
    // Live reasoning (thinking) cards
    // ------------------------------------------------------------------

    private fun updateThinking(messageId: String, text: String) {
        val tc = thinkingCards.getOrPut(messageId) { createThinkingCard(messageId) }
        tc.accum = when {
            tc.accum.isEmpty() -> text
            text.startsWith(tc.accum) -> text
            tc.accum.startsWith(text) -> tc.accum
            else -> text
        }
        if (tc.done) tc.done = false
        attachThinkingCard(messageId, tc)
        // Frame-coalesced: reasoning streams token by token.
        tc.dirty = true
        if (!thinkingFlushPending) {
            thinkingFlushPending = true
            mainHandler.postDelayed(thinkingFlushRunnable, THINKING_FLUSH_MS)
        }
    }

    private fun finishThinking(messageId: String) {
        val tc = thinkingCards[messageId] ?: return
        if (tc.done) return
        mainHandler.removeCallbacks(thinkingFlushRunnable)
        thinkingFlushPending = false
        tc.dirty = false
        tc.done = true
        renderThinking(tc)
    }

    private fun createThinkingCard(messageId: String): ThinkingCard {
        val header = TextView(this).apply {
            text = "🧠 Thinking ▾ streaming · tap to expand"
            setTextColor(Color.parseColor("#C678DD"))
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
        }
        val body = TextView(this).apply {
            setTextColor(Color.parseColor("#8B93A1"))
            textSize = 12f
            setLineSpacing(3f, 1f)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#252A32"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(12, 6, 12, 0) }
            setPadding(12, 8, 12, 8)
            addView(header)
            addView(body)
            isClickable = true
            setOnClickListener {
                val t = thinkingCards[messageId] ?: return@setOnClickListener
                t.expanded = !t.expanded
                renderThinking(t)
            }
        }
        return ThinkingCard(card, header, body)
    }

    /** Insert above the response bubble (or the pending one) so thinking precedes the answer. */
    private fun attachThinkingCard(messageId: String, tc: ThinkingCard) {
        if (tc.card.parent != null) return
        val bubble = bubbles[messageId] ?: bubbles["_pending"]
        val bubbleCard = bubble?.parent as? View
        val idx = bubbleCard?.let { chatContainer.indexOfChild(it) } ?: -1
        if (idx >= 0) chatContainer.addView(tc.card, idx) else chatContainer.addView(tc.card)
    }

    private fun renderThinking(tc: ThinkingCard) {
        val full = tc.accum
        if (full.isEmpty()) {
            tc.card.visibility = View.GONE
            return
        }
        tc.card.visibility = View.VISIBLE
        val capped = if (full.length > THINKING_MAX_CHARS) {
            "…(earlier thinking truncated)\n" + full.takeLast(THINKING_MAX_CHARS)
        } else {
            full
        }
        tc.body.text = if (tc.expanded) capped else thinkingTail(capped)
        tc.header.text = when {
            tc.expanded -> "🧠 Thinking ▴ (tap to collapse)"
            tc.done -> "🧠 Thinking ▾ done · tap to expand"
            else -> "🧠 Thinking ▾ streaming · tap to expand"
        }
    }

    private fun thinkingTail(s: String): String {
        if (s.length <= THINKING_TAIL_CHARS) return s
        val tail = s.substring(s.length - THINKING_TAIL_CHARS)
        val nl = tail.indexOf('\n')
        return if (nl in 0 until tail.length - 1) "…\n" + tail.substring(nl + 1) else "…\n$tail"
    }

    // ------------------------------------------------------------------
    // Show more / less for long messages (10-line preview)
    // ------------------------------------------------------------------

    private fun attachShowMore(content: TextView, card: LinearLayout) {
        if (card.findViewById<TextView>(ID_SHOW_MORE) != null) return
        val toggle = TextView(this).apply {
            id = ID_SHOW_MORE
            this.text = "Show more ⌵"
            textSize = 11f
            setTextColor(Color.parseColor("#6E7681"))
            setPadding(0, 8, 0, 0)
            visibility = View.GONE
            setOnClickListener {
                if (tag == true) {
                    content.maxLines = MESSAGE_CLAMP_LINES
                    content.ellipsize = android.text.TextUtils.TruncateAt.END
                    this.text = "Show more ⌵"
                    tag = false
                } else {
                    content.maxLines = Int.MAX_VALUE
                    content.ellipsize = null
                    this.text = "Show less ▴"
                    tag = true
                }
            }
        }
        card.addView(toggle)
        refreshMessageClamp(content)
    }

    /** Clamp [content] to 10 lines once it overflows (skips user-expanded messages). */
    private fun refreshMessageClamp(content: TextView) {
        val card = content.parent as? LinearLayout ?: return
        val toggle = card.findViewById<TextView>(ID_SHOW_MORE) ?: return
        if (toggle.tag == true) return
        content.post {
            if (content.lineCount > MESSAGE_CLAMP_LINES) {
                content.maxLines = MESSAGE_CLAMP_LINES
                content.ellipsize = android.text.TextUtils.TruncateAt.END
                toggle.visibility = View.VISIBLE
            }
        }
    }

    private fun createBubble(): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#282C34"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(12, 6, 12, 6) }
            setPadding(12, 8, 12, 8)
        }
        val content = TextView(this).apply {
            setTextColor(Color.parseColor("#ABB2BF"))
            textSize = 14f
            setLineSpacing(4f, 1f)
            text = ""
        }
        card.addView(content)
        attachShowMore(content, card)
        chatContainer.addView(card)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        return content
    }

    private fun addMessage(role: String, text: String) {
        val isUser = role == "user"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // User messages: rounded speech bubble, right-aligned, max 85% width.
            layoutParams = if (isUser) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.85f)
                    .apply { gravity = Gravity.END }
            } else {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(12, 6, 12, 6) }
            }
            if (isUser) {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#24405F"))
                    cornerRadius = 14f * resources.displayMetrics.density
                }
            } else {
                setBackgroundColor(Color.parseColor("#282C34"))
            }
            setPadding(12, 8, 12, 8)
        }
        val roleLabel = TextView(this).apply {
            this.text = when (role) {
                "user" -> "You"
                "system" -> "System"
                else -> "opencode"
            }
            setTextColor(
                when (role) {
                    "user" -> Color.parseColor("#98C379")
                    "system" -> Color.parseColor("#E06C75")
                    else -> Color.parseColor("#61AFEF")
                }
            )
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
        }
        val content = TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#ABB2BF"))
            textSize = 14f
            setLineSpacing(4f, 1f)
        }
        card.addView(roleLabel)
        card.addView(content)
        attachShowMore(content, card)
        chatContainer.addView(card)
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------------------
    // Pickers
    // ------------------------------------------------------------------

    private fun pickSession() {
        Thread {
            val list = runCatching { service.sessions() }.getOrNull() ?: JSONArray()
            val entries = mutableListOf<Pair<String, String>>()
            for (i in 0 until list.length()) {
                val s = list.optJSONObject(i) ?: continue
                val id = s.optString("id")
                if (id.isEmpty()) continue
                val title = s.optString("title").takeIf { it.isNotEmpty() }
                    ?: s.optString("time", id)
                entries.add(title to id)
            }
            val labels = (listOf("+ New session") + entries.map { it.first }).toTypedArray()
            mainHandler.post {
                AlertDialog.Builder(this)
                    .setTitle("Sessions")
                    .setItems(labels) { _, which ->
                        if (which == 0) {
                            Thread {
                                val created = runCatching { service.createSession("MobileStudio") }.getOrNull()
                                val id = created?.optString("id")
                                if (id != null) {
                                    sessionId = id
                                    getSharedPreferences(PREFS, MODE_PRIVATE)
                                        .edit().putString(KEY_SESSION, id).apply()
                                    bubbles.clear()
                                    rendered.clear()
                                    mainHandler.post {
                                        chatContainer.removeAllViews()
                                        updateStatus("New session")
                                    }
                                }
                            }.start()
                        } else {
                            val id = entries[which - 1].second
                            sessionId = id
                            getSharedPreferences(PREFS, MODE_PRIVATE)
                                .edit().putString(KEY_SESSION, id).apply()
                            bubbles.clear()
                            rendered.clear()
                            chatContainer.removeAllViews()
                            loadHistory()
                            updateStatus("Session switched")
                        }
                    }
                    .show()
            }
        }.start()
    }

    private fun pickAgent() {
        Thread {
            val agents = runCatching { service.agents() }.getOrNull() ?: JSONArray()
            val names = mutableListOf<String>()
            for (i in 0 until agents.length()) {
                agents.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { names.add(it) }
            }
            mainHandler.post {
                if (names.isEmpty()) {
                    Toast.makeText(this, "No agents reported by server", Toast.LENGTH_SHORT).show()
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("Agent")
                    .setSingleChoiceItems(names.toTypedArray(), names.indexOf(agent)) { d, which ->
                        agent = names[which]
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putString(KEY_AGENT, agent).apply()
                        updateStatus("Agent: $agent")
                        d.dismiss()
                    }
                    .show()
            }
        }.start()
    }

    private fun pickModel() {
        Thread {
            val providers = runCatching { service.providers() }.getOrNull()
            val all = providers?.optJSONArray("all") ?: JSONArray()
            val choices = mutableListOf<Pair<String, String>>()
            for (i in 0 until all.length()) {
                val provider = all.optJSONObject(i) ?: continue
                val providerId = provider.optString("id").takeIf { it.isNotEmpty() }
                    ?: provider.optString("providerID")
                if (providerId.isEmpty()) continue
                val models = provider.optJSONObject("models") ?: continue
                val keys = models.keys()
                while (keys.hasNext()) {
                    val modelId = keys.next()
                    val m = models.optJSONObject(modelId) ?: continue
                    if (m.optBoolean("hidden", false)) continue
                    val label = m.optString("name").takeIf { s -> s.isNotEmpty() } ?: modelId
                    choices.add("$label  ($providerId/$modelId)" to "$providerId/$modelId")
                }
            }
            val labels = choices.map { it.first }.toTypedArray()
            mainHandler.post {
                if (labels.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("Model")
                        .setMessage(
                            "No models available.\n\n" +
                                "Connect a provider first:\n" +
                                "  opencode auth login\n" +
                                "or add credentials to ~/.config/opencode/opencode.json"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                    return@post
                }
                val currentLabel = choices.indexOfFirst { it.second == model }
                AlertDialog.Builder(this)
                    .setTitle("Model")
                    .setSingleChoiceItems(labels, currentLabel) { d, which ->
                        model = choices[which].second
                        getSharedPreferences(PREFS, MODE_PRIVATE)
                            .edit().putString(KEY_MODEL, model).apply()
                        updateStatus("Model: $model")
                        d.dismiss()
                    }
                    .show()
            }
        }.start()
    }

    private fun pickCommand() {
        val id = sessionId ?: return
        Thread {
            val commands = runCatching { service.commands() }.getOrNull() ?: JSONArray()
            val entries = mutableListOf<Pair<String, String>>()
            for (i in 0 until commands.length()) {
                val c = commands.optJSONObject(i) ?: continue
                val cid = c.optString("id").takeIf { it.isNotEmpty() } ?: c.optString("name")
                if (cid.isEmpty()) continue
                val desc = c.optString("description").takeIf { it.isNotEmpty() } ?: cid
                entries.add(desc to cid)
            }
            mainHandler.post {
                if (entries.isEmpty()) {
                    Toast.makeText(this, "No slash commands available", Toast.LENGTH_SHORT).show()
                    return@post
                }
                AlertDialog.Builder(this)
                    .setTitle("Commands")
                    .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                        val command = entries[which].second
                        Thread {
                            runCatching {
                                service.runCommand(id, command, emptyList(), agent, model)
                            }.onFailure { e ->
                                mainHandler.post {
                                    Toast.makeText(this, "Command failed: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    }
                    .show()
            }
        }.start()
    }

    private fun openTui() {
        val command = env.tuiCommandLine()
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("run_command", command)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching {
            startActivity(intent)
            Toast.makeText(this, "TUI launched in terminal", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "TUI failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private fun updateStatus(msg: String) {
        mainHandler.post { statusText.text = msg }
    }

    private fun showProgress(show: Boolean) {
        mainHandler.post { progressLayout.visibility = if (show) View.VISIBLE else View.GONE }
    }

    override fun onDestroy() {
        connected = false
        runCatching { eventStream?.close() }
        runCatching { env.stopServe() }
        super.onDestroy()
    }
}
