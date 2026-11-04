package com.aide.mobilestudio.extension

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.aide.mobilestudio.StudioUi
import java.io.File
import java.io.FileOutputStream

private const val OFFICIAL_OWNER = "MobileStudio-AndroidIDE"
private const val EXT_REPO = "MobileStudio-Extensions"

/**
 * Bundled sample package, mirrored from examples/ into assets by the
 * `syncExtensionSamples` Gradle task. Keep in sync with the file the host
 * `examples/line-counter/build.sh` produces.
 */
private const val SAMPLE_MSEXT_ASSET = "com.mobilestudio.linecounter-v1.0.0.msext"

/**
 * Extension Manager UI — Installed / Available / Updates tabs, search,
 * permission display, install/uninstall/enable/disable/update actions.
 * Settings → Extensions entry point.
 */
class ExtensionManagerActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var listHost: LinearLayout
    private lateinit var searchBox: EditText
    private var currentTab = 0 // 0=Installed 1=Available 2=Updates
    private var query = ""
    private var loginBtn: TextView? = null
    private var translateBtn: TextView? = null
    private var loginDialog: AlertDialog? = null
    private var patField: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExtensionHost.init(this)
        StudioUi.applyTheme(com.aide.mobilestudio.UiTheme.resolve(this))
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(StudioUi.palette.bg)
        }
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildHeader()
        buildSearch()
        buildTabs()
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // Scrollable list (cards overflow the screen otherwise)
        val scroller = ScrollView(this).apply { isVerticalScrollBarEnabled = true; addView(listHost) }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // Focus the extension requested via intent (banner "Click Here to download")
        val focusId = intent?.getStringExtra("extension_id")
        if (focusId != null) {
            currentTab = 1
            refreshTabs()
        }
        refresh()
        // Warm the registry cache; empty/failed registry -> empty Available list (never a fake catalog)
        ExtensionManager.get(this).refreshRepo(force = false) { _, err ->
            runOnUiThread {
                if (err != null) Toast.makeText(this, "registry: $err", Toast.LENGTH_LONG).show()
                refresh()
            }
        }
    }

    private fun headerText(text: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setTypeface(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(StudioUi.SpacingLg, dp(10), StudioUi.SpacingLg, dp(4))
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Wraps a horizontal button row so actions past the screen edge stay reachable. */
    private fun hScrollActions(row: LinearLayout): View =
        HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun buildHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(StudioUi.palette.surface)
            setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingSm, dp(12))
            // 가로 스크롤 뷰 안에서 내용 폭만큼만 차지해야 스크롤이 동작한다
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        header.addView(headerText("Extensions", 17f, StudioUi.palette.text, true))
        header.addView(textButton("Refresh") {
            Toast.makeText(this, "registry 새로고침 중…", Toast.LENGTH_SHORT).show()
            ExtensionManager.get(this).refreshRepo(force = true) { _, err ->
                runOnUiThread {
                    if (err != null) Toast.makeText(this, "registry: $err", Toast.LENGTH_LONG).show()
                    refreshTabs(); refresh()
                }
            }
        })
        val login = textButton(if (ExtensionAccount.get(this).isLoggedIn()) "My" else "Login") {
            if (ExtensionAccount.get(this).isLoggedIn()) {
                startActivity(android.content.Intent(this, MyExtensionsActivity::class.java))
            } else {
                showLoginDialog()
            }
        }
        loginBtn = login
        header.addView(login)
        refreshLoginButton()
        header.addView(textButton("기기에서 설치") { showLocalPackagesDialog() })
        header.addView(textButton("Build .msext") { startBuildMsext() })
        header.addView(textButton("샘플 설치") { installSampleExtension() })
        translateBtn = textButton(translateLabel()) { toggleAutoTranslate() }
        header.addView(translateBtn)
        header.addView(textButton("Developer") { showDeveloperDialog() })
        header.addView(textButton("Error Log") { showErrorLog() })
        header.addView(textButton("Close") { finish() })
        // 버튼이 화면 폭을 넘어 잘리지 않도록 가로 스크롤로 감싼다.
        // HorizontalScrollView는 FrameLayout이므로 layoutParams를 주지 않으면
        // MATCH_PARENT가 되어 스크롤이 아예 동작하지 않는다 — 폭은 콘텐츠만큼만.
        val headerScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(StudioUi.palette.surface)
            addView(
                header,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        root.addView(headerScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(StudioUi.thinDivider(this))
    }

    /** Header Login/My label reflects Gmail or server (browser OAuth) session. */
    private fun refreshLoginButton() {
        val btn = loginBtn ?: return
        btn.text = when {
            ExtensionAccount.get(this).isLoggedIn() -> "My"
            GitHubAuth.isLoggedIn(this) ->
                "@" + GitHubAuth.login(this).ifBlank { "GitHub" }
            ExtensionServerClient.hasSession(this) ->
                "@" + ExtensionServerClient.loginName(this).ifBlank { "GitHub" }
            else -> "Login"
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLoginButton()
        autofillTokenFromClipboard()
    }

    private fun translateLabel() =
        if (ExtensionI18n.isEnabled(this)) "자동 번역 ON" else "자동 번역 OFF"

    /** Extension metadata auto-translation: default ON, one tap to disable. */
    private fun toggleAutoTranslate() {
        val next = !ExtensionI18n.isEnabled(this)
        ExtensionI18n.setEnabled(this, next)
        translateBtn?.text = translateLabel()
        Toast.makeText(
            this,
            if (next) "확장 이름/설명을 앱 언어로 자동 번역합니다" else "자동 번역을 끔 — 원문만 표시합니다",
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    /** Localises extension metadata; a no-op for ko/en or when the toggle is off. */
    private fun localize(text: String, languages: List<String>, dir: File?): String =
        ExtensionI18n.localize(this, text, languages, dir)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android 10+ 클립보드는 포커스 회복 후에만 읽을 수 있다
        if (hasFocus) autofillTokenFromClipboard()
    }

    /** 브라우저에서 토큰을 복사하고 돌아오면 → 로그인 필드에 자동 채움한다. */
    private fun autofillTokenFromClipboard() {
        val field = patField
        if (field == null || loginDialog?.isShowing != true || !field.text.isNullOrBlank()) return
        GitHubAuth.clipToken(this)?.let { t ->
            field.setText(t)
            Toast.makeText(this, "클립보드의 GitHub 토큰을 붙여넣었습니다 — 로그인을 누르세요", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildSearch() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(StudioUi.SpacingLg, dp(8), StudioUi.SpacingLg, dp(8))
        }
        searchBox = EditText(this).apply {
            hint = "Search Extensions"
            setSingleLine(true)
            textSize = 13f
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.textSoft)
            setBackgroundResource(0)
            background = StudioUi.inputBg(this@ExtensionManagerActivity)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim()?.lowercase() ?: ""; refresh() }
            })
        }
        row.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(row)
    }

    private fun buildTabs() {
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(StudioUi.SpacingSm, 0, StudioUi.SpacingSm, 0) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                tabRow,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(StudioUi.thinDivider(this))
        refreshTabs()
    }

    private fun refreshTabs() {
        val names = listOf("Installed", "Available", "Updates")
        val updates = ExtensionManager.get(this).updates().size
        tabRow.removeAllViews()
        names.forEachIndexed { i, name ->
            val label = if (i == 2 && updates > 0) "$name ($updates)" else name
            val tab = TextView(this).apply {
                text = label
                textSize = 13f
                setTypeface(Typeface.MONOSPACE, if (currentTab == i) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (currentTab == i) StudioUi.palette.accent else StudioUi.palette.textSoft)
                background = if (currentTab == i) StudioUi.roundedInt(this@ExtensionManagerActivity, fill = StudioUi.palette.surface2, radiusDp = StudioUi.RadiusMd) else null
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { currentTab = i; refreshTabs(); refresh() }
            }
            tabRow.addView(tab)
        }
    }

    private fun refresh() {
        listHost.removeAllViews()
        val manager = ExtensionManager.get(this)
        when (currentTab) {
            0 -> {
                val items = manager.installed().filter { query.isEmpty() || it.manifest.name.lowercase().contains(query) || it.manifest.id.contains(query) }
                if (items.isEmpty()) listHost.addView(emptyView("설치된 Extension이 없습니다.\nAvailable 탭에서 설치하세요."))
                items.forEach { listHost.addView(extensionCardInstalled(it)) }
            }
            1 -> {
                val installedIds = manager.installed().map { it.manifest.id }.toSet()
                // Only entries that actually exist in the registry (never a hardcoded catalog)
                val items = manager.cachedRepo()
                    .filter { query.isEmpty() || it.name.lowercase().contains(query) || it.id.contains(query) || it.description.lowercase().contains(query) }
                if (items.isEmpty()) {
                    listHost.addView(emptyView("registry에서 확장을 가져올 수 없거나 등록된 확장이 없습니다.\nRefresh 버튼으로 다시 시도하세요."))
                }
                items.forEach { entry ->
                    val inst = manager.installed().firstOrNull { it.manifest.id == entry.id }
                    listHost.addView(extensionCardAvailable(entry, inst, installedIds))
                }
            }
            2 -> {
                val updates = manager.updates()
                if (updates.isEmpty()) listHost.addView(emptyView("업데이트가 없습니다."))
                updates.forEach { (inst, entry) -> listHost.addView(extensionCardUpdate(inst, entry)) }
            }
        }
    }

    private fun emptyView(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(StudioUi.palette.textSoft)
        setPadding(StudioUi.SpacingLg, dp(24), StudioUi.SpacingLg, dp(24))
        gravity = Gravity.CENTER
    }

    private fun extensionCardInstalled(it: ExtensionManager.InstalledExtension): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingLg, dp(12)) }
        card.addView(
            titleRow(
                localize(it.manifest.name, it.manifest.languages, it.dir),
                it.manifest.version,
                if (it.enabled) "Enabled" else "Disabled",
                if (it.enabled) StudioUi.palette.success else StudioUi.palette.textSoft
            )
        )
        if (it.manifest.description.isNotBlank()) {
            card.addView(desc(localize(it.manifest.description, it.manifest.languages, it.dir)))
        }
        card.addView(permissionRow(it.manifest.permissions))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        actions.addView(textButton(if (it.enabled) "Disable" else "Enable") {
            ExtensionManager.get(this).setEnabled(it.manifest.id, !it.enabled)
            ExtensionBridge.notifyBannersChanged()
            refresh()
        })
        actions.addView(textButton("Uninstall", danger = true) {
            val ok = ExtensionManager.get(this).uninstall(it.manifest.id)
            Toast.makeText(this, if (ok) "제거됨" else "제거 실패", Toast.LENGTH_SHORT).show()
            ExtensionBridge.notifyBannersChanged()
            refresh()
        })
        card.addView(hScrollActions(actions))
        if (it.enabled) {
            actions.addView(textButton("Publish") { publishInstalled(it) })
        }
        return LinearLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)); setBackgroundColor(Color.TRANSPARENT); addView(card); background = StudioUi.cardBg(this@ExtensionManagerActivity) }
    }

    /** Packs the installed extension's project folder into .msext and publishes it.
     *  - GitHub account login (device flow): publishes a PUBLIC release to the
     *    logged-in user's own account, and also uploads to the shared marketplace
     *    server when a session exists.
     *  - Server session only: uploads to the shared marketplace.
     *  - Legacy PAT: direct upload to the official repository. */
    private fun publishInstalled(inst: ExtensionManager.InstalledExtension) {
        val ghToken = GitHubAuth.token(this)
        val useServer = ExtensionServerClient.hasSession(this)
        val hasPat = GitHubPublisher.hasToken(this)
        if (ghToken == null && !useServer && !hasPat) {
            Toast.makeText(this, "GitHub 로그인이 필요합니다 — 로그인 후 바로 업로드됩니다", Toast.LENGTH_LONG).show()
            showLoginDialog()
            return
        }
        if (ghToken != null && !GitHubAuth.canPublish(this)) {
            Toast.makeText(this, "토큰에 ‘repo’ 권한이 없습니다 — 토큰을 다시 만들어 로그인하세요", Toast.LENGTH_LONG).show()
            showLoginDialog()
            return
        }
        Toast.makeText(this, "패키징 후 게시합니다…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val packed = ExtensionManager.get(this).packMsext(inst.dir, File(cacheDir, "publish")) { msg ->
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                } ?: run {
                    runOnUiThread { Toast.makeText(this, "패키징 실패", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                fun progress(p: String) = runOnUiThread { Toast.makeText(this, p, Toast.LENGTH_SHORT).show() }

                if (ghToken != null) {
                    val owner = GitHubAuth.login(this).ifBlank {
                        runCatching { GitHubPublisher.whoAmI(this).message }.getOrDefault("")
                    }
                    if (owner.isBlank()) {
                        runOnUiThread { Toast.makeText(this, "GitHub 계정 확인 실패 — 다시 로그인하세요", Toast.LENGTH_LONG).show() }
                        return@Thread
                    }
                    GitHubPublisher.publishToUserRepo(this, owner, EXT_REPO, packed.first, packed.second, ::progress) { r ->
                        runOnUiThread { Toast.makeText(this, "내 계정 게시: ${r.message}", Toast.LENGTH_LONG).show() }
                    }
                    if (useServer) {
                        ExtensionServerClient.upload(this, packed.first, inst.manifest.id,
                            onProgress = ::progress,
                            onDone = { r -> runOnUiThread { Toast.makeText(this, "공용 마켓: ${r.message}", Toast.LENGTH_LONG).show() } })
                    } else {

                        runOnUiThread {
                            Toast.makeText(this, "공용 마켓 게시는 서버 로그인이 필요합니다 (내 계정에는 게시됨)", Toast.LENGTH_LONG).show()
                        }
                    }
                } else if (useServer) {
                    ExtensionServerClient.upload(this, packed.first, inst.manifest.id,
                        onProgress = ::progress,
                        onDone = { r -> runOnUiThread { Toast.makeText(this, r.message, Toast.LENGTH_LONG).show() } })
                } else {
                    GitHubPublisher.publishExtension(this, OFFICIAL_OWNER, EXT_REPO, packed.first, packed.second, ::progress) { result ->
                        runOnUiThread { Toast.makeText(this, result.message, Toast.LENGTH_LONG).show() }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "게시 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Developer dialog: GitHub PAT + developer name/email registration + repo creation. */
    private fun showDeveloperDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        container.addView(TextView(this).apply {
            text = "개발자 등록 — 확장 생성/업로드 시 사용되며 이후에도 재사용됩니다"
            textSize = 13f
            setTextColor(StudioUi.palette.textSoft)
        })

        fun field(hint: String, initial: String = "") = EditText(this).apply {
            this.hint = hint
            setSingleLine(true)
            textSize = 13f
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.textSoft)
            background = StudioUi.inputBg(this@ExtensionManagerActivity)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setText(initial)
        }

        val profile = DeveloperProfile.get(this)
        val nameField = field("개발자 이름 (20자 이내, 영어+()._ )", profile?.name ?: "")
        val emailField = field("이메일 (gmail)", profile?.email ?: "")
        val tokenField = field("GitHub Personal Access Token (repo scope)")
        container.addView(nameField)
        container.addView(emailField)
        container.addView(tokenField)

        AlertDialog.Builder(this)
            .setTitle("Developer")
            .setView(container)
            .setPositiveButton("등록") { _, _ ->
                val token = tokenField.text.toString().trim()
                if (token.isNotBlank()) {
                    // 검증을 통과해야 세션(GitHubAuth) + 게시 자격증명(GitHubPublisher)이 저장된다
                    GitHubAuth.loginWithToken(this, token) { ok, msg ->
                        runOnUiThread {
                            refreshLoginButton()
                            if (!ok) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                DeveloperProfile.register(this, nameField.text.toString().trim(), emailField.text.toString().trim(), null) { ok, msg ->
                    runOnUiThread {
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (ok) refresh()
                    }
                }
            }
            .setNeutralButton("저장소 생성") { _, _ ->
                val token = tokenField.text.toString().trim()
                if (token.isNotBlank()) GitHubPublisher.setToken(this, token)
                if (!GitHubPublisher.hasToken(this)) {
                    Toast.makeText(this, "GitHub 토큰 필요", Toast.LENGTH_LONG).show()
                    return@setNeutralButton
                }
                Toast.makeText(this, "저장소 생성 중…", Toast.LENGTH_SHORT).show()
                Thread {
                    val result = GitHubPublisher.createRepo(this, "MobileStudio-Extensions", "MobileStudio Extension Repository")
                    runOnUiThread { Toast.makeText(this, result.message, Toast.LENGTH_LONG).show() }
                }.start()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun extensionCardAvailable(entry: ExtensionManager.RepoEntry, inst: ExtensionManager.InstalledExtension?, installedIds: Set<String>): View {
        val marketplace = ExtensionMarketplace.get(this)
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingLg, dp(12)) }
        card.addView(titleRow(localize(entry.name, entry.languages, inst?.dir), entry.version, if (inst != null) (if (inst.enabled) "Enabled" else "Disabled") else "", if (inst != null) (if (inst.enabled) StudioUi.palette.success else StudioUi.palette.textSoft) else StudioUi.palette.textSoft, entry.author.ifBlank { DeveloperProfile.get(this)?.name ?: "" }, DeveloperProfile.get(this)?.email ?: ""))
        if (entry.description.isNotBlank()) card.addView(desc(localize(entry.description, entry.languages, inst?.dir)))
        card.addView(metaRow(entry))
        card.addView(permissionRow(entry.permissions))
        // 신고 누적/관리자 차단 → Extension Hub에 "Under review" 표시
        if (marketplace.isDownloadSuspended(entry.id)) {
            card.addView(TextView(this).apply {
                text = "⚠ Under review — ${marketplace.suspensionReason(entry.id)}"
                textSize = 11f
                setTextColor(StudioUi.palette.warning)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
        }
        // 좋아요 수 표시
        card.addView(TextView(this).apply {
            text = "♥ ${marketplace.likeCount(entry.id)}  ·  ${marketplace.reviews(entry.id).size} reviews  ·  ${marketplace.reportCount(entry.id)} reports"
            textSize = 11f
            setTextColor(StudioUi.palette.textSoft)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        if (entry.download.isNotBlank()) {
            actions.addView(textButton(if (inst != null) "Reinstall" else "Install") {
                installEntry(entry)
            })
        } else {
            actions.addView(textButton("Install") { installOfficial(entry) })
        }
        // 좋아요 (계정당 1회 토글, 로그인 필요)
        actions.addView(textButton(if (ExtensionAccount.get(this).likedExtensions().contains(entry.id)) "♥ Liked" else "♥ Like") {
            val err = marketplace.toggleLike(entry.id)
            if (err != null) Toast.makeText(this, err, Toast.LENGTH_SHORT).show() else refresh()
        })
        // 신고 (하루 1회/확장, 로그인 필요)
        actions.addView(textButton("Report") { showReportDialog(entry) })
        // 상세 정보
        actions.addView(textButton("Details") { showExtensionDetail(entry) })
        card.addView(hScrollActions(actions))
        return LinearLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)); setBackgroundColor(Color.TRANSPARENT); addView(card); background = StudioUi.cardBg(this@ExtensionManagerActivity) }
    }

    /** GitHub 로그인 — PAT(기본, 서버 불필요) + Device Flow(고급) + Gmail. */
    private fun showLoginDialog() {
        val account = ExtensionAccount.get(this)
        val hasGoogle = ExtensionAccount.isGoogleSignInAvailable(this) && ExtensionAccount.clientId(this).isNotBlank()
        var dialog: AlertDialog? = null
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        val profile = DeveloperProfile.get(this)
        val serverSession = ExtensionServerClient.hasSession(this)
        val ghLoggedIn = GitHubAuth.isLoggedIn(this)
        fun statusText(): String = buildString {
            if (ghLoggedIn) {
                val scope = GitHubAuth.scopes(this@ExtensionManagerActivity)
                append("GitHub: ${GitHubAuth.login(this@ExtensionManagerActivity).ifBlank { "로그인됨" }} — 내 계정에 공개 게시")
                if (scope.isNotBlank()) append("  [$scope]")
                append("\n")
                if (scope.isNotBlank() && !GitHubAuth.hasRepoScope(scope)) {
                    append("⚠ ‘repo’ 권한이 없는 토큰입니다 — 게시에 실패할 수 있습니다\n")
                }
            }
            if (serverSession) {
                val login = ExtensionServerClient.loginName(this@ExtensionManagerActivity).ifBlank { "로그인됨" }
                append("GitHub (서버 세션): $login — 공용 마켓\n")
            }
            if (!ghLoggedIn && GitHubPublisher.hasToken(this@ExtensionManagerActivity)) append("GitHub (직접 토큰): 로그인됨\n")
            if (account.isLoggedIn()) append("Gmail: ${account.current()?.displayName ?: "로그인됨"}\n")
            if (profile != null) append("개발자: ${profile.name}")
        }
        val statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(StudioUi.palette.textSoft)
        }
        fun renderStatus() {
            val s = statusText()
            statusView.text = if (s.isBlank()) {
                "서버 없이 바로 로그인합니다.\n① 브라우저에서 토큰 만들기 → ② 복사 → ③ 앱으로 돌아와 로그인"
            } else s
        }
        renderStatus()
        container.addView(statusView)

        // ① 브라우저에서 토큰 생성 (scope=repo 미리 선택됨)
        container.addView(TextView(this).apply {
            text = "① 브라우저에서 토큰 만들기  ▸"
            textSize = 12f
            setTextColor(StudioUi.palette.accent)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(10), 0, 0)
            setOnClickListener { openTokenPage() }
        })

        // ② 붙여넣기 — 클립보드에 토큰이 있으면 미리 채워둔다
        val tokenField = EditText(this).apply {
            hint = "② 토큰 붙여넣기 (ghp_…)"
            setSingleLine(true)
            textSize = 13f
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.textSoft)
            background = StudioUi.inputBg(this@ExtensionManagerActivity)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            GitHubAuth.clipToken(this@ExtensionManagerActivity)?.let { setText(it) }
        }
        container.addView(tokenField)
        patField = tokenField

        if (ghLoggedIn) {
            container.addView(TextView(this).apply {
                text = "▸ 로그아웃"
                textSize = 12f
                setTextColor(StudioUi.palette.error)
                setPadding(0, dp(10), 0, 0)
                setOnClickListener {
                    GitHubAuth.signOut(this@ExtensionManagerActivity)
                    Toast.makeText(this@ExtensionManagerActivity, "GitHub 로그아웃", Toast.LENGTH_SHORT).show()
                    refreshLoginButton()
                    dialog?.dismiss()
                }
            })
        } else if (GitHubAuth.isConfigured(this)) {
            container.addView(TextView(this).apply {
                text = "▸ Device Flow로 로그인 (client_id 설정됨)"
                textSize = 12f
                setTextColor(StudioUi.palette.accent)
                setPadding(0, dp(10), 0, 0)
                setOnClickListener { dialog?.dismiss(); startGitHubDeviceLogin() }
            })
        }

        val clientField = EditText(this).apply {
            hint = "고급: GitHub OAuth client_id (Device Flow, 선택)"
            setSingleLine(true)
            textSize = 13f
            setTextColor(StudioUi.palette.text)
            setHintTextColor(StudioUi.palette.textSoft)
            background = StudioUi.inputBg(this@ExtensionManagerActivity)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setText(GitHubAuth.clientId(this@ExtensionManagerActivity))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        container.addView(clientField)

        val builder = AlertDialog.Builder(this)
            .setTitle("Login")
            .setView(container)
            .setPositiveButton("로그인") { _, _ ->
                val client = clientField.text.toString().trim()
                if (client.isNotBlank()) GitHubAuth.setClientId(this, client)
                val token = GitHubAuth.normalizeToken(tokenField.text.toString())
                if (token.isBlank()) {
                    Toast.makeText(this, "브라우저에서 토큰을 만들고 복사한 뒤 붙여넣으세요", Toast.LENGTH_LONG).show()
                    openTokenPage()
                    return@setPositiveButton
                }
                if (!GitHubAuth.looksLikeToken(token)) {
                    Toast.makeText(this, "GitHub 토큰 형식이 아닙니다 (ghp_…)", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val checking = AlertDialog.Builder(this)
                    .setMessage("토큰 확인 중…")
                    .show()
                GitHubAuth.loginWithToken(this, token) { ok, msg ->
                    runOnUiThread {
                        runCatching { checking.dismiss() }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (ok) {
                            dialog?.dismiss()
                            loginDialog = null
                            refreshLoginButton()
                            refresh()
                        }
                    }
                }
            }
            .setNegativeButton("닫기") { _, _ -> loginDialog = null }
        if (hasGoogle) {
            builder.setNeutralButton("Gmail") { _, _ ->
                try {
                    val intent = account.googleSignInClient().signInIntent
                    startActivityForResult(intent, 0x5101)
                } catch (e: Exception) {
                    Toast.makeText(this, "로그인 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        loginDialog = builder.show()
        dialog = loginDialog
        refreshLoginButton()
        // scope이 아직 기록되지 않은 세션은 한 번 더 확인해 권한 경고를 정확하게
        if (ghLoggedIn && GitHubAuth.scopes(this).isBlank()) {
            GitHubAuth.refreshScopes(this) { _ ->
                runOnUiThread {
                    if (loginDialog?.isShowing == true) renderStatus()
                    refreshLoginButton()
                }
            }
        }
    }

    /** Opens the GitHub token creation page (scopes pre-selected) in the browser. */
    private fun openTokenPage() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubAuth.NEW_TOKEN_URL)))
        }.onFailure {
            Toast.makeText(this, "브라우저를 열 수 없습니다", Toast.LENGTH_LONG).show()
        }
    }

    /** Runs the GitHub Device Flow and shows the user code + live status. */
    private fun startGitHubDeviceLogin() {
        val statusDialog = AlertDialog.Builder(this)
            .setTitle("GitHub 로그인")
            .setMessage("기기 코드를 요청하는 중…")
            .setNegativeButton("닫기", null)
            .show()
        val msgView = statusDialog.findViewById<TextView>(android.R.id.message)
        var codeText = ""
        fun render(status: String) {
            msgView?.text = listOf(codeText, status).filter { it.isNotBlank() }.joinToString("\n\n")
        }
        GitHubAuth.startLogin(
            this,
            onCode = { code ->
                codeText = "브라우저에서 아래 코드를 입력하세요:\n\n${code.userCode}\n\n${code.verificationUri}"
                render("브라우저 자동 실행됨 — 승인을 기다리는 중…")
            },
            onStatus = { s -> runOnUiThread { render(s) } },
            onDone = { ok, msg ->
                runOnUiThread {
                    statusDialog.dismiss()
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    if (ok) {
                        refreshLoginButton()
                        refresh()
                    }
                }
            }
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 0x5101) {
            val profile = ExtensionAccount.get(this).handleSignInResult(data)
            if (profile != null) {
                Toast.makeText(this, "로그인됨: ${profile.displayName}", Toast.LENGTH_SHORT).show()
                refresh()
            } else {
                Toast.makeText(this, "로그인 취소됨", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 신고 다이얼로그 — 사유 선택 + 메시지. */
    private fun showReportDialog(entry: ExtensionManager.RepoEntry) {
        ExtensionAccount.get(this).requireLogin("신고")?.let {
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
            return
        }
        val reasons = ExtensionMarketplace.REPORT_REASONS.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Report: ${entry.name}")
            .setItems(reasons) { _, which ->
                val reason = reasons[which]
                val ok = ExtensionMarketplace.get(this).addReport(entry.id, reason, "")
                if (ok != null) Toast.makeText(this, ok, Toast.LENGTH_LONG).show()
                else Toast.makeText(this, "신고가 접수되었습니다: $reason", Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** 확장 상세 페이지 — 상세 정보 + 후기 + 보안 검사 상태. */
    private fun showExtensionDetail(entry: ExtensionManager.RepoEntry) {
        val marketplace = ExtensionMarketplace.get(this)
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(8)) }

        fun row(label: String, value: String) {
            container.addView(TextView(this).apply {
                text = "$label: $value"
                textSize = 12f
                setTextColor(StudioUi.palette.text)
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            })
        }
            row("Name", entry.name)
        row("ID", entry.id)
        row("Version", entry.version)
        row("Author", entry.author)
        row("Size", if (entry.size > 0) "${entry.size / 1024} KB" else "-")
        row("Downloads", "${marketplace.downloadCount(entry.id)}")
        row("Likes", "${marketplace.likeCount(entry.id)}")
        row("Reports", "${marketplace.reportCount(entry.id)}")
        row("Required MobileStudio", entry.minStudioVersion)
        row("Permissions", entry.permissions.joinToString(", ").ifBlank { "-" })
        row("Security scan", if (marketplace.isDownloadSuspended(entry.id)) "Under review" else "Passed (automated)")
        container.addView(TextView(this).apply {
            text = "자동 검사 통과가 악성코드 부재를 보장하지는 않습니다"
            textSize = 10f
            setTextColor(StudioUi.palette.textSoft)
        })

        container.addView(TextView(this).apply {
            text = "Reviews"
            textSize = 13f
            setTextColor(StudioUi.palette.text)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(4))
        })
        val reviews = marketplace.reviews(entry.id)
        if (reviews.isEmpty()) {
            container.addView(TextView(this).apply { text = "아직 후기가 없습니다"; textSize = 11f; setTextColor(StudioUi.palette.textSoft) })
        } else {
            for (r in reviews.take(10)) {
                container.addView(TextView(this).apply {
                    text = "${r.author} · ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(r.createdAt))}${if (r.edited) " (edited)" else ""}\n${r.content}"
                    textSize = 11f
                    setTextColor(StudioUi.palette.text)
                    setPadding(0, dp(4), 0, 0)
                })
            }
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Extension Detail")
            .setView(container)
            .setPositiveButton("Install") { _, _ -> if (entry.download.isNotBlank()) installEntry(entry) else installOfficial(entry) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun extensionCardUpdate(inst: ExtensionManager.InstalledExtension, entry: ExtensionManager.RepoEntry): View {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingLg, dp(12)) }
        card.addView(titleRow(entry.name, "${inst.manifest.version} → ${entry.version}", "Update", StudioUi.palette.accent))
        if (entry.description.isNotBlank()) card.addView(desc(entry.description))
        card.addView(permissionRow(entry.permissions))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        actions.addView(textButton("Update") {
            if (entry.download.isNotBlank()) installEntry(entry) else installOfficial(entry)
        })
        card.addView(hScrollActions(actions))
        return LinearLayout(this).apply { setPadding(dp(6), dp(6), dp(6), dp(6)); setBackgroundColor(Color.TRANSPARENT); addView(card); background = StudioUi.cardBg(this@ExtensionManagerActivity) }
    }

    private fun titleRow(name: String, version: String, status: String, statusColor: Int, author: String = "", email: String = ""): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = name
            textSize = 17f
            setTextColor(StudioUi.palette.text)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        })
        if (author.isNotBlank()) {
            col.addView(TextView(this).apply {
                text = "developed by $author" + (if (email.isNotBlank()) "  $email" else "")
                textSize = 11f
                setTextColor(StudioUi.palette.textSoft)
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            })
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (status.isNotBlank()) row.addView(TextView(this).apply {
            text = status
            textSize = 11f
            setTextColor(statusColor)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        })
        if (version.isNotBlank()) row.addView(TextView(this).apply {
            text = if (status.isNotBlank()) "  v$version" else "v$version"
            textSize = 11f
            setTextColor(StudioUi.palette.textSoft)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        })
        return row
    }

    /** Description in small text; over 100 chars truncated with a "...show more" toggle. */
    private fun desc(text: String): View {
        if (text.length <= 100) {
            return TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(StudioUi.palette.textSoft)
                setPadding(0, dp(4), 0, 0)
            }
        }
        val shortText = text.take(100) + "…"
        val tv = TextView(this).apply {
            textSize = 12f
            setTextColor(StudioUi.palette.textSoft)
            setPadding(0, dp(4), 0, 0)
        }
        var expanded = false
        fun apply() {
            tv.text = if (expanded) text + "\n...show less" else shortText + "...show more"
        }
        apply()
        tv.setOnClickListener {
            expanded = !expanded
            apply()
        }
        return tv
    }

    private fun metaRow(entry: ExtensionManager.RepoEntry): View = TextView(this).apply {
        val size = if (entry.size > 0) "Size ${(entry.size / 1_000_000.0).let { String.format("%.1f MB", it)}}" else ""
        this.text = listOfNotNull("Version ${entry.version}", size.takeIf { it.isNotBlank() }, "by ${entry.author}".takeIf { entry.author.isNotBlank() }).joinToString("  ·  ")
        textSize = 11f
        setTextColor(StudioUi.palette.textSoft)
        setPadding(0, dp(3), 0, 0)
    }

    private fun permissionRow(perms: List<String>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, 0) }
        if (perms.isEmpty()) return TextView(this).apply {
            text = "권한: 없음"
            textSize = 11f
            setTextColor(StudioUi.palette.textSoft)
        }
        row.addView(TextView(this).apply {
            text = "권한:"
            textSize = 11f
            setTextColor(StudioUi.palette.textSoft)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        })
        perms.forEach { id ->
            row.addView(TextView(this).apply {
                val sensitive = ExtensionApi.isSensitive(id)
                text = "${if (sensitive) "⚠" else "✓"} ${ExtensionApi.permissionLabel(id)}${idLabelSuffix(id)}"
                textSize = 11f
                setTextColor(if (sensitive) StudioUi.palette.warning else StudioUi.palette.textSoft)
                setPadding(dp(10), dp(1), 0, 0)
            })
        }
        return row
    }

    private fun idLabelSuffix(id: String) = " (${id})".takeIf { ExtensionApi.permissionLabel(id) != id } ?: ""

    /** Install only from a real download URL — never fabricate release URLs (404 source). */
    private fun installOfficial(entry: ExtensionManager.RepoEntry) {
        if (entry.download.isBlank()) {
            Toast.makeText(this,
                "이 확장은 아직 배포 파일이 등록되지 않았습니다 (download URL 없음)",
                Toast.LENGTH_LONG).show()
            return
        }
        installEntry(entry)
    }

    private fun installEntry(entry: ExtensionManager.RepoEntry) {
        val manager = ExtensionManager.get(this)
        manager.install(
            entry,
            { msg -> runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } },
            onDone = { ok, msg ->
                runOnUiThread {
                    Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    if (ok) ExtensionBridge.notifyBannersChanged()
                    refreshTabs(); refresh()
                }
            },
            onPermissionsRequired = { manifest, newPermissions, proceed ->
                runOnUiThread { showPermissionApprovalDialog(manifest, newPermissions, proceed) }
            },
        )
    }

    /**
     * Asks before an extension gets permissions it has not been granted yet.
     * The install stays parked in ExtensionManager until the user answers, so a
     * refusal means nothing was written to the extensions directory.
     */
    private fun showPermissionApprovalDialog(
        manifest: ExtensionManifest,
        newPermissions: List<String>,
        proceed: (Boolean) -> Unit,
    ) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(TextView(this@ExtensionManagerActivity).apply {
                text = buildString {
                    append(manifest.name).append(" ").append(manifest.version)
                    append(" (").append(manifest.author).append(")\n\n")
                    append("이 확장은 다음 권한을 요구합니다:\n\n")
                    newPermissions.forEach { append("  • ").append(permissionLabel(it)).append('\n') }
                    append("\n")
                    append(permissionExplanation(newPermissions))
                    append("\n\n허용하면 확장이 이 권한으로 작업을 수행할 수 있습니다. ")
                    append("거부하면 설치가 취소됩니다.")
                }
                textSize = 13f
                setTextColor(StudioUi.palette.text)
            })
        }
        AlertDialog.Builder(this)
            .setTitle("권한 승인")
            .setView(body)
            .setCancelable(false)
            .setPositiveButton("허용") { _, _ -> proceed(true) }
            .setNegativeButton("거부") { _, _ -> proceed(false) }
            .setOnCancelListener { proceed(false) }
            .show()
    }

    private fun permissionLabel(id: String): String = when (id) {
        "editor" -> "editor — 현재 파일 읽기/쓰기, 커서·선택 영역 제어"
        "project" -> "project — 프로젝트 파일 읽기/쓰기"
        "terminal" -> "terminal — 빌드 명령 실행"
        "storage" -> "storage — 확장 전용 데이터/캐시 디렉터리"
        "network" -> "network — 외부 네트워크 접근"
        "notifications" -> "notifications — 알림 표시"
        else -> "$id — 확장이 선언한 권한"
    }

    private fun permissionExplanation(perms: List<String>): String = buildList {
        if ("network" in perms) add("네트워크 권한은 확장이 기기 밖으로 데이터를 보낼 수 있음을 의미합니다.")
        if ("terminal" in perms) add("터미널 권한은 확장이 쉘 명령을 실행할 수 있음을 의미합니다.")
        if ("project" in perms || "editor" in perms) add("파일 권한은 확장이 프로젝트 내용을 읽고 수정할 수 있음을 의미합니다.")
    }.joinToString("\n")

    /** Human-readable byte size: 8.1 KB / 1.2 MB. */
    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /**
     * Lists every .msext package found on the device — path and size, no
     * external file picker involved. Tapping a row installs it through the
     * same validation path as a registry install.
     */
    private fun showLocalPackagesDialog() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(headerText("검색 중…", 13f, StudioUi.palette.textSoft))
        val scroller = ScrollView(this).apply { addView(list) }
        AlertDialog.Builder(this)
            .setTitle("기기에서 설치 (.msext)")
            .setView(scroller)
            .setNegativeButton("닫기", null)
            .show()

        Thread {
            val manager = ExtensionManager.get(this)
            val packages = manager.scanLocalPackages()
            val rows = packages.map { it to runCatching { manager.manifestOfPackage(it.file) }.getOrNull() }
            runOnUiThread {
                list.removeAllViews()
                if (rows.isEmpty()) {
                    list.addView(emptyView("기기에서 .msext 파일을 찾지 못했습니다.\nDownload 폴더에 .msext를 넣고 다시 시도하세요."))
                    return@runOnUiThread
                }
                list.addView(headerText("${rows.size}개 발견", 13f, StudioUi.palette.textSoft))
                rows.forEach { (pkg, manifest) -> list.addView(localPackageRow(pkg, manifest)) }
            }
        }.start()
    }

    private fun localPackageRow(
        pkg: ExtensionManager.LocalPackage,
        manifest: ExtensionManifest?
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(StudioUi.SpacingLg, dp(10), StudioUi.SpacingLg, dp(10))
        }
        val name = manifest?.let { localize(it.name, it.languages, null) } ?: pkg.displayName
        card.addView(
            titleRow(
                name,
                manifest?.version ?: "",
                formatSize(pkg.size),
                StudioUi.palette.textSoft
            )
        )
        // 경로는 사용자가 확인·복사할 수 있어야 한다
        card.addView(TextView(this).apply {
            text = pkg.path
            textSize = 11f
            setTextColor(StudioUi.palette.textSoft)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextIsSelectable(true)
        })
        if (manifest == null) {
            card.addView(TextView(this).apply {
                text = "⚠ extension.json 없음 — 설치할 수 없는 패키지"
                textSize = 12f
                setTextColor(StudioUi.palette.error)
                setPadding(0, dp(4), 0, 0)
            })
        } else {
            if (manifest.description.isNotBlank()) card.addView(desc(manifest.description))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            actions.addView(textButton("설치") {
                ExtensionManager.get(this).installFromFile(
                    pkg.file,
                    onProgress = { msg ->
                        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                    },
                    onDone = { ok, msg ->
                        runOnUiThread {
                            Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                            if (ok) {
                                ExtensionBridge.notifyBannersChanged()
                                refreshTabs(); refresh()
                            }
                        }
                    },
                    // Local installs go through the same gate as downloads, so
                    // new permissions have to be approved here too.
                    onPermissionsRequired = { manifest, newPermissions, proceed ->
                        runOnUiThread { showPermissionApprovalDialog(manifest, newPermissions, proceed) }
                    },
                )
            })
            card.addView(hScrollActions(actions))
        }
        card.addView(StudioUi.thinDivider(this))
        return card
    }

    /**
     * Packs an extension project into a .msext package on the device, using the
     * in-app packer (no external tooling).
     */
    /**
     * Installs the bundled sample extension (examples/line-counter, mirrored
     * into assets at build time). The package goes through exactly the same
     * gate as a download: permission approval, scanned SHA-256 pin,
     * entryPoint/classes.dex consistency, size and compression limits.
     */
    private fun installSampleExtension() {
        val assetName = SAMPLE_MSEXT_ASSET
        val out = File(cacheDir, "samples/$assetName")
        out.parentFile?.mkdirs()
        Thread {
            try {
                assets.open("extensions/samples/$assetName").use { input ->
                    FileOutputStream(out).use { output -> input.copyTo(output) }
                }
                runOnUiThread {
                    ExtensionManager.get(this).installFromFile(
                        out,
                        onProgress = { msg ->
                            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                        },
                        onDone = { ok, message ->
                            runOnUiThread {
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                                if (ok) ExtensionBridge.notifyBannersChanged()
                            }
                        },
                        onPermissionsRequired = { manifest, newPermissions, proceed ->
                            runOnUiThread { showPermissionApprovalDialog(manifest, newPermissions, proceed) }
                        },
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "샘플 설치 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun startBuildMsext() {
        val candidates = ArrayList<File>()
        ExtensionBridge.projectDir?.invoke()?.let { p ->
            if (File(p, "extension.json").isFile) candidates.add(p)
        }
        val workspace = getExternalFilesDir(null)?.let { File(it, "MobileStudio") }
        workspace?.listFiles()
            ?.filter { it.isDirectory && File(it, "extension.json").isFile }
            ?.sortedBy { it.name.lowercase() }
            ?.forEach { if (!candidates.contains(it)) candidates.add(it) }

        if (candidates.isEmpty()) {
            Toast.makeText(this, "확장 프로젝트(extension.json)를 찾지 못했습니다", Toast.LENGTH_LONG).show()
            return
        }
        val names = candidates.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Build .msext — 프로젝트 선택")
            .setItems(names) { _, which -> packProject(candidates[which]) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun packProject(projectDir: File) {
        val outDir = File(getExternalFilesDir(null), "MobileStudio/packages").apply { mkdirs() }
        Thread {
            try {
                val manager = ExtensionManager.get(this)

                // ---- build step: javac -> d8 -> build/classes.dex ----
                val hasSources = projectDir.walkTopDown()
                    .onEnter { it.name !in setOf("build", "out", ".git", ".gradle", ".idea") }
                    .any { it.isFile && it.extension == "java" }
                if (hasSources) {
                    val built = manager.buildExtension(projectDir) { msg ->
                        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                    }
                    if (!built.ok) {
                        runOnUiThread { showBuildErrors(projectDir.name, built.errors) }
                        return@Thread
                    }
                }

                val result = manager.packMsext(projectDir, outDir) { msg ->
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
                }
                runOnUiThread {
                    if (result == null) {
                        Toast.makeText(this, "패키징 실패", Toast.LENGTH_LONG).show()
                    } else {
                        val (msext, _) = result
                        Toast.makeText(
                            this,
                            "생성됨: ${msext.name} (${formatSize(msext.length())})\n${msext.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                        ExtensionBridge.notifyBannersChanged()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "패키징 실패: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Compiler diagnostics are line-based and long; a toast cannot hold them. */
    private fun showBuildErrors(projectName: String, errors: List<String>) {
        val text = TextView(this).apply {
            this.text = errors.joinToString("\n")
            textSize = 11f
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setTextColor(StudioUi.palette.error)
            setPadding(StudioUi.SpacingLg, StudioUi.SpacingLg, StudioUi.SpacingLg, StudioUi.SpacingLg)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle("빌드 실패 — $projectName")
            .setView(scroll)
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun showErrorLog() {
        val log = CrashReporter.readLog(this)
        val scroller = ScrollView(this)
        val text = TextView(this).apply {
            this.text = "Error Log\n\n[[[ERROR ERROR ERROR ERROR ERROR]]]\n\n$log"
            textSize = 12f
            setTextColor(StudioUi.palette.text)
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            setPadding(StudioUi.SpacingLg, dp(16), StudioUi.SpacingLg, dp(16))
            setTextIsSelectable(true)
        }
        scroller.addView(text)
        root.removeAllViews()
        val back = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(StudioUi.SpacingLg, dp(12), StudioUi.SpacingSm, dp(12)); setBackgroundColor(StudioUi.palette.surface) }
        back.addView(textButton("← Extensions") { onCreate(null) })
        back.addView(textButton("Clear Log", danger = true) { CrashReporter.clearLog(this); showErrorLog() })
        root.addView(back)
        root.addView(StudioUi.thinDivider(this))
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun textButton(label: String, danger: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 12f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        // 테두리를 주지 않으면 밝은 테마에서 surface2 위 accent 글자가 "비활성"처럼 보인다.
        setTextColor(if (danger) StudioUi.palette.error else StudioUi.palette.accent)
        background = StudioUi.ripple(
            this@ExtensionManagerActivity,
            StudioUi.roundedInt(
                this@ExtensionManagerActivity,
                fill = StudioUi.palette.surface2,
                stroke = if (danger) StudioUi.palette.error else StudioUi.palette.accent,
                radiusDp = 4,
                strokeDp = 1
            )
        )
        setPadding(dp(12), dp(8), dp(12), dp(8))
        StudioUi.touchLift(this)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) }
    }
}
