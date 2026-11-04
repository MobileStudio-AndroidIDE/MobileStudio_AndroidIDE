package com.aide.mobilestudio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class SupabaseCommunityActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var client: SupabaseCommunityClient
    private lateinit var roomsRow: LinearLayout
    private lateinit var messages: LinearLayout
    private lateinit var polls: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var attachStatus: TextView
    private lateinit var sendButton: Button
    private lateinit var joinButton: Button
    private lateinit var loginButton: Button
    private var refreshJob: Job? = null
    private var selectedRoom: CommunityRoom? = null
    private var sending = false
    private var lengthToastShown = false
    private val pendingAttachments = mutableListOf<PickedAttachment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = SupabaseCommunityClient(this)
        runCatching { intent?.data?.let { client.handleOAuthRedirect(it) } }
            .onSuccess { if (it == true) toast("Google α Ϸ") }
            .onFailure { toast(it.message ?: "Google login failed") }
        setContentView(buildUi())
        refreshRooms()
        startAutoRefresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { intent.data?.let { client.handleOAuthRedirect(it) } }
            .onSuccess {
                if (it == true) {
                    toast("Google α Ϸ")
                    refreshRooms()
                }
            }
            .onFailure { toast(it.message ?: "Google login failed") }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ATTACH || resultCode != Activity.RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } ?: data.data?.let { uris.add(it) }
        addAttachments(uris)
    }

    private fun buildUi(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = StudioUi.gradient(this@SupabaseCommunityActivity, "#08111E", "#12151C")
        addView(topBar(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        addView(TextView(this@SupabaseCommunityActivity).apply {
            text = "äù濡 Ʈ ϰ ڵ ̾߱⸦ ."
            textSize = 12f
            setTextColor(c("#8B949E"))
            setPadding(dp(14), dp(8), dp(14), dp(6))
        })
        addView(roomStrip(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(92)))
        scroll = ScrollView(this@SupabaseCommunityActivity).apply {
            messages = LinearLayout(this@SupabaseCommunityActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            addView(messages)
        }
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        polls = LinearLayout(this@SupabaseCommunityActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        addView(polls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        attachStatus = TextView(this@SupabaseCommunityActivity).apply {
            textSize = 11f
            setTextColor(c("#8E99AC"))
            setPadding(dp(12), 0, dp(12), 0)
        }
        addView(attachStatus)
        addView(composer(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)))
    }

    private fun topBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(8), 0)
        background = StudioUi.gradient(this@SupabaseCommunityActivity, "#232D3B", "#12161F")
        addView(Button(this@SupabaseCommunityActivity).apply {
            text = "←"
            studioButton(fill = "#12161F", text = StudioUi.Text, accent = StudioUi.Border)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(42), dp(40)))
        addView(TextView(this@SupabaseCommunityActivity).apply {
            text = "◆ Community"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c("#E6EDF3"))
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(124), ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this@SupabaseCommunityActivity).apply {
            text = if (client.signedIn) "Online" else "Login"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(c("#46C07D"))
        }
        addView(status, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(HorizontalScrollView(this@SupabaseCommunityActivity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(LinearLayout(this@SupabaseCommunityActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                loginButton = Button(this@SupabaseCommunityActivity).apply {
                    text = if (client.signedIn) "Me" else "Google"
                    textSize = 11f
                    studioButton(fill = "#202A38", text = StudioUi.Text, accent = StudioUi.Blue)
                    setOnClickListener { showAccountDialog() }
                }
                addView(loginButton, LinearLayout.LayoutParams(dp(78), dp(40)))
                joinButton = Button(this@SupabaseCommunityActivity).apply {
                    text = "Join"
                    textSize = 11f
                    studioButton(fill = "#123323", text = StudioUi.Green, accent = StudioUi.Green)
                    setOnClickListener { toggleJoinLeave() }
                }
                addView(joinButton, LinearLayout.LayoutParams(dp(66), dp(40)))
                addView(Button(this@SupabaseCommunityActivity).apply {
                    text = "+"
                    studioButton(fill = "#1F3A5F", text = StudioUi.Text, accent = StudioUi.Blue)
                    setOnClickListener { showCreateRoomDialog() }
                }, LinearLayout.LayoutParams(dp(44), dp(40)))
                addView(Button(this@SupabaseCommunityActivity).apply {
                    text = "ǥ"
                    textSize = 10f
                    studioButton(fill = "#332848", text = StudioUi.Text, accent = StudioUi.Purple)
                    setOnClickListener { showCreatePollDialog() }
                }, LinearLayout.LayoutParams(dp(58), dp(40)))
                addView(Button(this@SupabaseCommunityActivity).apply {
                    text = "SQL"
                    studioButton(fill = "#2A2416", text = StudioUi.Amber, accent = StudioUi.Amber)
                    setOnClickListener { showSqlDialog() }
                }, LinearLayout.LayoutParams(dp(56), dp(40)))
            })
        }, LinearLayout.LayoutParams(0, dp(42), 1f))
    }

    private fun roomStrip(): View = HorizontalScrollView(this).apply {
        setBackgroundColor(c(StudioUi.Ink))
        roomsRow = LinearLayout(this@SupabaseCommunityActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(8))
        }
        addView(roomsRow)
    }

    private fun composer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(8))
        background = StudioUi.gradient(this@SupabaseCommunityActivity, "#232D3B", "#12161F")
        addView(Button(this@SupabaseCommunityActivity).apply {
            text = "+"
            studioButton(fill = "#202A38", text = StudioUi.Text, accent = StudioUi.Border)
            setOnClickListener { pickAttachments() }
        }, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT))
        input = EditText(this@SupabaseCommunityActivity).apply {
            hint = "1000 Ϸ Է..."
            minLines = 1
            maxLines = 3
            textSize = 14f
            setTextColor(c(StudioUi.Text))
            setHintTextColor(c(StudioUi.Muted))
            background = StudioUi.rounded(this@SupabaseCommunityActivity, fill = "#12151C", stroke = "#2F3B4D", radiusDp = 18)
            setPadding(dp(10), 0, dp(10), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if ((s?.length ?: 0) > SupabaseCommunityClient.MAX_MESSAGE_CHARS && !lengthToastShown) {
                        lengthToastShown = true
                        toast("1000 Ϸ Էּ.")
                    }
                    if ((s?.length ?: 0) <= SupabaseCommunityClient.MAX_MESSAGE_CHARS) lengthToastShown = false
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            leftMargin = dp(6)
            rightMargin = dp(6)
        })
        sendButton = Button(this@SupabaseCommunityActivity).apply {
            text = "Send"
            studioButton(fill = "#1F3A5F", text = StudioUi.Text, accent = StudioUi.Blue)
            setOnClickListener { sendMessage() }
        }
        addView(sendButton, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun showAccountDialog() {
        if (!client.signedIn) {
            val loginView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), 0)
                addView(TextView(this@SupabaseCommunityActivity).apply {
                    text = "G"
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    textSize = 18f
                    setTextColor(c("#4285F4"))
                    background = StudioUi.rounded(this@SupabaseCommunityActivity, fill = "#FFFFFF", stroke = "#DADCE0", radiusDp = 22)
                }, LinearLayout.LayoutParams(dp(44), dp(44)))
                addView(TextView(this@SupabaseCommunityActivity).apply {
                    text = " ϼ."
                    textSize = 20f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(c(StudioUi.Text))
                    setPadding(0, dp(14), 0, dp(4))
                })
                addView(TextView(this@SupabaseCommunityActivity).apply {
                    text = "Google α â  MobileStudio Community   մϴ."
                    textSize = 13f
                    setTextColor(c(StudioUi.TextSoft))
                })
                addView(TextView(this@SupabaseCommunityActivity).apply {
                    text = "ʼ : Supabase Auth > URL Configuration mobilestudio://community-auth  Redirect URL ߰ϼ."
                    textSize = 12f
                    setTextColor(c(StudioUi.Muted))
                    setPadding(0, dp(12), 0, 0)
                })
            }
            AlertDialog.Builder(this)
                .setTitle("Google  α")
                .setView(loginView)
                .setPositiveButton("Google ") { _, _ -> openGoogleLogin() }
                .setNeutralButton("Supabase ") { _, _ -> showSetupDialog() }
                .setNegativeButton("ݱ", null)
                .show()
            return
        }
        val inputName = EditText(this).apply {
            hint = "nickname"
            setText(client.displayName)
        }
        AlertDialog.Builder(this)
            .setTitle(client.userEmail.ifBlank { " " })
            .setView(inputName)
            .setPositiveButton("г ") { _, _ ->
                client.saveNickname(inputName.text.toString())
                refreshRooms()
            }
            .setNeutralButton("α׾ƿ") { _, _ ->
                client.signOut()
                selectedRoom = null
                refreshRooms()
            }
            .setNegativeButton("ݱ", null)
            .show()
    }

    private fun openGoogleLogin() {
        loginButton.isEnabled = false
        statusText("Checking")
        scope.launch {
            val setupError = withContext(Dispatchers.IO) {
                runCatching { client.googleProviderSetupErrorOrNull() }
                    .getOrElse { it.message ?: "Google login setup check failed." }
            }
            loginButton.isEnabled = true
            statusText(if (client.signedIn) "Online" else "Login")
            if (setupError != null) {
                AlertDialog.Builder(this@SupabaseCommunityActivity)
                    .setTitle("Google α  ʿ")
                    .setMessage(setupError)
                    .setPositiveButton("Supabase ") { _, _ -> showSetupDialog() }
                    .setNeutralButton("SQL ") { _, _ -> showSqlDialog() }
                    .setNegativeButton("ݱ", null)
                    .show()
                return@launch
            }
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(client.googleSignInUrl())))
            }.onFailure {
                toast(it.message ?: "Cannot open browser")
            }
        }
    }

    private fun showSetupDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        val url = EditText(this).apply {
            hint = "https://xxxx.supabase.co"
            setText(client.projectUrl)
        }
        val key = EditText(this).apply {
            hint = "anon public key"
            setText(client.anonKey)
        }
        val name = EditText(this).apply {
            hint = "nickname"
            setText(client.displayName)
        }
        box.addView(label("Project URL")); box.addView(url)
        box.addView(label("Anon public key")); box.addView(key)
        box.addView(label("Display name")); box.addView(name)
        AlertDialog.Builder(this)
            .setTitle("Supabase Community Setup")
            .setMessage("service_role/secret key ۿ  . Google α Supabase Auth Google Provider Redirect URL  ʿմϴ.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                runCatching { client.saveConfig(url.text.toString(), key.text.toString(), name.text.toString()) }
                    .onSuccess {
                        toast("Supabase  ߽ϴ.")
                        refreshRooms()
                    }
                    .onFailure { toast(it.message ?: "Invalid Supabase settings") }
            }
            .setNeutralButton("SQL") { _, _ -> showSqlDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSqlDialog() {
        AlertDialog.Builder(this)
            .setTitle("Run this in Supabase SQL Editor")
            .setMessage(
                "1) Supabase SQL Editor Ʒ SQL ϼ.\n" +
                    "2) Auth > Providers Google Ѽ.\n" +
                    "3) Auth > URL Configuration > Redirect URLs ${SupabaseCommunityClient.OAUTH_REDIRECT_URI}  ߰ϼ.\n\n" +
                    SupabaseCommunityClient.setupSql
            )
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MobileStudio Community SQL", SupabaseCommunityClient.setupSql))
                toast("SQL copied")
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showCreateRoomDialog() {
        if (!client.signedIn) {
            showAccountDialog()
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val name = EditText(this).apply { hint = "äù ̸" }
        val desc = EditText(this).apply { hint = "" }
        box.addView(label("Name")); box.addView(name)
        box.addView(label("Description")); box.addView(desc)
        AlertDialog.Builder(this)
            .setTitle("äù ")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { client.createRoom(name.text.toString(), desc.text.toString()) }
                    }
                    result.onSuccess {
                        selectedRoom = it
                        refreshRooms()
                    }.onFailure { toast(it.message ?: "Room create failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            while (true) {
                delay(20_000)
                if (!sending) refreshCurrent(silent = true)
            }
        }
    }

    private fun refreshRooms() {
        loginButton.text = if (client.signedIn) "Me" else "Google"
        statusText(if (client.signedIn) "Online" else "Login")
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.fetchRooms() } }
            result.onSuccess { rooms ->
                renderRooms(rooms)
                val next = selectedRoom?.let { current -> rooms.firstOrNull { it.id == current.id } }
                    ?: rooms.firstOrNull { it.joined }
                    ?: rooms.firstOrNull()
                selectedRoom = next
                renderRoomState(next)
                refreshCurrent()
            }.onFailure {
                roomsRow.removeAllViews()
                renderInfo("Ŀ´Ƽ ε : ${it.message}")
            }
        }
    }

    private fun renderRooms(rooms: List<CommunityRoom>) {
        roomsRow.removeAllViews()
        if (rooms.isEmpty()) {
            roomsRow.addView(label("äù ϴ. + ư ."))
            return
        }
        rooms.forEach { room ->
            roomsRow.addView(Button(this).apply {
                text = "${room.name}\n${room.memberCount}"
                textSize = 11f
                setAllCaps(false)
                isEnabled = !room.banned
                setTextColor(c(if (room.id == selectedRoom?.id) "#46C07D" else "#E6EDF3"))
                background = StudioUi.ripple(
                    this@SupabaseCommunityActivity,
                    StudioUi.rounded(
                        this@SupabaseCommunityActivity,
                        fill = if (room.id == selectedRoom?.id) "#163A2A" else "#232D3B",
                        stroke = if (room.id == selectedRoom?.id) StudioUi.Green else StudioUi.Border,
                        radiusDp = 18
                    )
                )
                StudioUi.touchLift(this)
                setOnClickListener {
                    selectedRoom = room
                    renderRoomState(room)
                    refreshCurrent()
                }
            }, LinearLayout.LayoutParams(dp(132), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                rightMargin = dp(6)
            })
        }
    }

    private fun renderRoomState(room: CommunityRoom?) {
        if (room == null) {
            renderInfo(" äù ϼ.")
            return
        }
        statusText(if (room.joined) room.role ?: "member" else "Not joined")
        if (!::joinButton.isInitialized) return
        joinButton.text = when {
            room.banned -> "Banned"
            room.joined -> "Leave"
            else -> "Join"
        }
        joinButton.isEnabled = !room.banned && client.signedIn
    }

    private fun refreshCurrent(silent: Boolean = false) {
        val room = selectedRoom ?: return
        if (!silent) statusText("Loading")
        scope.launch {
            val messageResult = withContext(Dispatchers.IO) {
                runCatching { if (room.joined) client.fetchMessages(room.id) else emptyList() }
            }
            messageResult.onSuccess { list ->
                messages.removeAllViews()
                if (!client.signedIn) {
                    renderInfo("Google α  äÿ   ֽϴ.")
                } else if (!room.joined) {
                    renderInfo("${room.name} Ϸ Join .")
                } else if (list.isEmpty()) {
                    renderInfo(" ȭ ϴ. ù ޽ ܺ.")
                } else {
                    list.forEach { messages.addView(messageBubble(it, room)) }
                }
                statusText(if (room.joined) "Online" else "Join")
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }.onFailure {
                statusText("Error")
                renderInfo("޽ ε : ${it.message}")
            }
            refreshPolls(room)
        }
    }

    private fun refreshPolls(room: CommunityRoom) {
        polls.removeAllViews()
        if (!room.joined) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { client.fetchPolls(room.id) } }
            result.onSuccess { list ->
                polls.removeAllViews()
                list.take(2).forEach { polls.addView(pollCard(it)) }
            }
        }
    }

    private fun toggleJoinLeave() {
        val room = selectedRoom ?: return
        if (!client.signedIn) {
            showAccountDialog()
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (room.joined) client.leaveRoom(room.id) else client.joinRoom(room.id)
                }
            }
            result.onSuccess { refreshRooms() }
                .onFailure { toast(it.message ?: "Action failed") }
        }
    }

    private fun sendMessage() {
        val room = selectedRoom ?: return
        val text = input.text.toString().trim()
        if (text.isBlank() && pendingAttachments.isEmpty()) return
        if (text.length > SupabaseCommunityClient.MAX_MESSAGE_CHARS) {
            toast("1000 Ϸ Էּ.")
            return
        }
        if (!client.signedIn || !room.joined) {
            toast("Google α  äù濡 ϼ.")
            return
        }
        if (sending) return
        statusText("Sending")
        sending = true
        sendButton.isEnabled = false
        val attachments = pendingAttachments.toList()
        scope.launch {
            val result = try {
                val uploaded = withContext(Dispatchers.IO) {
                    attachments.mapIndexed { index, attachment ->
                        client.uploadAttachment(attachment.uri, attachment.name, attachment.mime, attachment.size) { sent, total ->
                            val percent = if (total > 0) ((sent * 100) / total).coerceIn(0, 100) else 0
                            runOnUiThread { statusText("Upload ${index + 1}/${attachments.size} $percent%") }
                        }
                    }
                }
                withContext(Dispatchers.IO) {
                    client.sendMessage(room.id, text.ifBlank { "÷  ߽ϴ." }, uploaded)
                }
                Result.success(Unit)
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            result.onSuccess {
                input.text.clear()
                pendingAttachments.clear()
                updateAttachmentStatus()
                refreshCurrent()
            }.onFailure {
                statusText("Error")
                toast(it.message ?: "Send failed")
            }
            sending = false
            sendButton.isEnabled = true
        }
    }

    private fun showCreatePollDialog() {
        val room = selectedRoom ?: return
        if (!client.signedIn || room.role !in setOf("owner", "admin")) {
            toast("관리자 또는 방장만 투표를 만들 수 있습니다.")
            return
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        val question = EditText(this).apply { hint = "질문을 입력하세요" }
        val optionInputs = (1..5).map { index -> EditText(this).apply { hint = "׸ $index" } }
        val durations = listOf(5 to "5분", 10 to "10분", 30 to "30분", 60 to "1시간", 300 to "5시간", 1440 to "1일", 4320 to "3일")
        var selectedDuration = 60
        box.addView(label("Question")); box.addView(question)
        optionInputs.forEach { box.addView(it) }
        val durationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            durations.take(4).forEach { duration ->
                addView(Button(this@SupabaseCommunityActivity).apply {
                    text = duration.second
                    setOnClickListener {
                        selectedDuration = duration.first
                        toast("${duration.second} 선택됨")
                    }
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
            }
        }
        box.addView(durationRow)
        AlertDialog.Builder(this)
            .setTitle("투표 만들기")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val options = optionInputs.map { it.text.toString().trim() }.filter { it.isNotBlank() }.distinct().take(5)
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { client.createPoll(room.id, question.text.toString(), options, selectedDuration) }
                    }
                    result.onSuccess { refreshPolls(room) }
                        .onFailure { toast(it.message ?: "Poll create failed") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pollCard(poll: CommunityPoll): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = StudioUi.rounded(this@SupabaseCommunityActivity, fill = "#232D3B", stroke = "#2F3B4D", radiusDp = 18)
        val winner = poll.options.maxByOrNull { it.votes }
        addView(TextView(this@SupabaseCommunityActivity).apply {
            text = if (poll.closed) "투표 종료 · ${poll.question}" else "투표 중 · ${poll.question}"
            setTextColor(c("#E6EDF3"))
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
        })
        poll.options.forEach { option ->
            addView(Button(this@SupabaseCommunityActivity).apply {
                text = "${option.text}  ${option.votes}" + if (poll.myVoteOptionId == option.id) " ✓" else ""
                setAllCaps(false)
                isEnabled = !poll.closed && poll.myVoteOptionId == null
                setOnClickListener {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { client.vote(poll.id, option.id) } }
                        result.onSuccess { selectedRoom?.let { refreshPolls(it) } }
                            .onFailure { toast("이미 다른 옵션에 투표했습니다.") }
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        }
        if (poll.closed && winner != null) {
            addView(TextView(this@SupabaseCommunityActivity).apply {
                text = ": ${winner.text} (${winner.votes})"
                setTextColor(c("#46C07D"))
                textSize = 12f
            })
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        }
    }

    private fun pickAttachments() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/zip", "text/*", "application/json", "application/xml",
                "image/jpeg", "image/png", "image/webp", "application/octet-stream"
            ))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_ATTACH)
    }

    private fun addAttachments(uris: List<Uri>) {
        uris.forEach { uri ->
            if (pendingAttachments.size >= SupabaseCommunityClient.MAX_ATTACHMENTS) {
                toast("÷δ ִ ${SupabaseCommunityClient.MAX_ATTACHMENTS} մϴ.")
                return@forEach
            }
            val picked = describe(uri)
            val ext = picked.name.substringAfterLast('.', "").lowercase(Locale.US)
            when {
                picked.size <= 0L -> toast(" ũ⸦ Ȯ  ϴ: ${picked.name}")
                picked.size > SupabaseCommunityClient.MAX_TOTAL_BYTES -> toast(" ʹ Ůϴ: ${picked.name}")
                ext !in SupabaseCommunityClient.ALLOWED_EXTENSIONS -> toast(" ʴ Դϴ: ${picked.name}")
                pendingAttachments.any { it.name == picked.name && it.size == picked.size } -> toast("̹ ÷ Դϴ: ${picked.name}")
                else -> pendingAttachments.add(picked)
            }
        }
        val total = pendingAttachments.sumOf { it.size.coerceAtLeast(0L) }
        if (total > SupabaseCommunityClient.MAX_TOTAL_BYTES) {
            pendingAttachments.clear()
            toast(" հ 300MB Ͽ մϴ.")
        }
        updateAttachmentStatus()
    }

    private fun describe(uri: Uri): PickedAttachment {
        var name = "attachment.bin"
        var size = -1L
        contentResolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
        val mime = contentResolver.getType(uri) ?: "application/octet-stream"
        return PickedAttachment(uri, name, size, mime)
    }

    private fun updateAttachmentStatus() {
        val totalMb = pendingAttachments.sumOf { it.size.coerceAtLeast(0L) } / 1024 / 1024
        attachStatus.text = if (pendingAttachments.isEmpty()) "" else
            "${pendingAttachments.size}/5 files · ${totalMb}MB / 300MB · ${pendingAttachments.joinToString { it.name }}"
    }

    private fun messageBubble(message: CommunityMessage, room: CommunityRoom): View {
        val mine = message.userId == client.userId
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val bubble = LinearLayout(this@SupabaseCommunityActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = StudioUi.rounded(
                    this@SupabaseCommunityActivity,
                    fill = if (mine) "#1F8B4C" else "#F8FAFC",
                    stroke = if (mine) "#2EA043" else "#D0D7DE",
                    radiusDp = 18
                )
                addView(TextView(this@SupabaseCommunityActivity).apply {
                    text = "${message.author}  ${message.createdAt.take(16).replace('T', ' ')}"
                    textSize = 11f
                    setTextColor(c(if (mine) "#DFFFE8" else "#57606A"))
                    typeface = Typeface.DEFAULT_BOLD
                })
                val bodyView = TextView(this@SupabaseCommunityActivity).apply {
                    text = message.body
                    textSize = 14f
                    setTextColor(c(if (mine) "#FFFFFF" else "#24292F"))
                    setPadding(0, dp(4), 0, dp(4))
                }
                addView(bodyView)
                val translatedLabel = TextView(this@SupabaseCommunityActivity).apply {
                    text = ""
                    textSize = 10f
                    setTextColor(c(if (mine) "#DFFFE8" else "#6E7781"))
                    visibility = View.GONE
                }
                addView(translatedLabel)
                message.attachments.forEach { attachment ->
                    addView(TextView(this@SupabaseCommunityActivity).apply {
                        text = "▸ ${attachment.name}  ${attachment.size / 1024 / 1024}MB"
                        textSize = 12f
                        setTextColor(c(if (mine) "#DFFFE8" else "#0969DA"))
                        setPadding(0, dp(2), 0, 0)
                        setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(attachment.url))) }
                    })
                }
                addView(LinearLayout(this@SupabaseCommunityActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    addView(TextView(this@SupabaseCommunityActivity).apply {
                        text = ""
                        textSize = 11f
                        setTextColor(c(if (mine) "#DFFFE8" else "#0969DA"))
                        setPadding(dp(8), dp(4), dp(8), 0)
                        setOnClickListener {
                            bodyView.text = translateMessageForUi(message.body)
                            translatedLabel.visibility = View.VISIBLE
                        }
                    })
                    if (mine || room.role in setOf("owner", "admin")) {
                        addView(TextView(this@SupabaseCommunityActivity).apply {
                            text = ""
                            textSize = 11f
                            setTextColor(c(if (mine) "#FFDAD6" else "#CF222E"))
                            setPadding(dp(8), dp(4), dp(8), 0)
                            setOnClickListener { confirmDeleteMessage(message) }
                        })
                    }
                })
                if (room.role in setOf("owner", "admin") && message.userId != client.userId) {
                    setOnLongClickListener {
                        showModerationDialog(room, message)
                        true
                    }
                }
            }
            addView(bubble, LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
    private fun translateMessageForUi(body: String): String {
        return body
            .replace(":)", "☺")
            .replace(":(", "☹")
            .replace("<3", "♥")
            .replace("lol", "ㅎㅎ", ignoreCase = true)
            .replace("hello", "안녕", ignoreCase = true)
            .replace("thanks", "고마워", ignoreCase = true)
    }

    private fun confirmDeleteMessage(message: CommunityMessage) {
        AlertDialog.Builder(this)
            .setTitle("삭제")
            .setMessage("이 메시지를 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { client.deleteMessage(message.id) } }
                        .onSuccess { refreshCurrent(silent = true) }
                        .onFailure { toast(it.message ?: "Delete failed") }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showModerationDialog(room: CommunityRoom, message: CommunityMessage) {
        val actions = arrayOf("관리자로 승격", "강퇴", "차단")
        AlertDialog.Builder(this)
            .setTitle(message.author)
            .setItems(actions) { _, which ->
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            when (which) {
                                0 -> client.setMemberRole(room.id, message.userId, "admin")
                                1 -> client.kickUser(room.id, message.userId)
                                2 -> client.banUser(room.id, message.userId)
                            }
                        }
                    }
                    result.onSuccess { refreshCurrent() }
                        .onFailure { toast(it.message ?: "Moderation failed") }
                }
            }
            .show()
    }

    private fun renderInfo(text: String) {
        messages.removeAllViews()
        messages.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(c("#8B949E"))
            setPadding(dp(10), dp(14), dp(10), dp(14))
        })
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(c("#8B949E"))
        textSize = 12f
        setPadding(0, dp(8), 0, 0)
    }

    private fun statusText(text: String) {
        if (::status.isInitialized) status.text = text
    }

    private fun c(hex: String): Int = Color.parseColor(hex)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private data class PickedAttachment(val uri: Uri, val name: String, val size: Long, val mime: String)

    companion object {
        private const val REQ_ATTACH = 4401
        fun start(context: Context) {
            context.startActivity(Intent(context, SupabaseCommunityActivity::class.java))
        }
    }
}
