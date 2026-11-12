package com.aide.mobilestudio

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.content.*
import android.graphics.*
import android.graphics.drawable.*
import android.net.Uri
import android.os.Bundle
import android.os.FileObserver
import android.text.InputType
import android.text.SpannableString
import android.text.TextUtils
import android.os.Handler
import android.os.Looper
import android.text.*
import kotlin.math.roundToInt
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.constraintlayout.widget.ConstraintLayout
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.*
import java.io.File
import java.io.StringReader

/**
 * LayoutPreviewActivity — 통합 XML 미리보기 & 편집기
 *
 * - 미리보기: XML을 실제 안드로이드 위젯으로 렌더링(실시간) + LayoutLib 고화질 렌더(백그라운드)
 * - 배경: assets/smartphone.png (사용자가 만든 핸드폰 사진) 을 폰 프레임으로 사용
 * - 연필 아이콘: 인터랙티브 편집 모드 전환 (위젯 이동/크기 조절/롱클릭 메뉴)
 * - Output 탭: LayoutLib 실행 로그 + Copy All 버튼
 */
class LayoutPreviewActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FILE = "extra_file_path"
        private const val TAG = "LayoutPreview"
        const val REQ_VISUAL_EDIT = 5001

        const val ANCHOR_LEFT = 1; const val ANCHOR_RIGHT = 2
        const val ANCHOR_TOP = 4; const val ANCHOR_BOTTOM = 8

        private const val COLOR_PRIMARY = "#6200EE"
        private const val COLOR_ON_PRIMARY = "#FFFFFF"
        private val CONTAINER_TAGS = setOf(
            "LinearLayout", "FrameLayout", "RelativeLayout", "ConstraintLayout",
            "ScrollView", "HorizontalScrollView", "NestedScrollView", "CoordinatorLayout",
            "CardView", "MaterialCardView", "RadioGroup", "TableLayout", "TableRow",
            "GridLayout", "AppBarLayout", "Toolbar", "TabLayout", "BottomNavigationView"
        )

        fun start(context: Context, file: File) {
            context.startActivity(Intent(context, LayoutPreviewActivity::class.java)
                .putExtra(EXTRA_FILE, file.absolutePath))
        }
    }

    // --- XML 노드 모델 ---
    private class XmlNode(
        var tagName: String,
        val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
        val children: MutableList<XmlNode> = mutableListOf()
    ) {
        var parent: XmlNode? = null
        var view: View? = null // Holds the rendered view for real-time updates
        var rect = Rect()
        // 자연 크기 (스케일 없이 measure된 크기) — 체크박스 등 scaleX/Y로 resize할 때 기준
        var naturalW = 0
        var naturalH = 0
        val isLayout: Boolean get() = CONTAINER_TAGS.contains(tagName)
        val isRoot: Boolean get() = parent == null
        fun attr(key: String): String? = attrs[key]
        fun setAttr(key: String, value: String?) {
            if (value == null) attrs.remove(key) else attrs[key] = value
        }
        val id: String? get() = attr("android:id")?.removePrefix("@+id/")?.removePrefix("@id/")
        fun depth(): Int {
            var d = 0; var p = parent
            while (p != null) { d++; p = p.parent }
            return d
        }
    }

    private var targetFile: File? = null

    /**
     * Resolves the previewed project's own @dimen/@color/@string references.
     * Built lazily per file and cached, since one preview session usually
     * renders a handful of layouts from the same module.
     */
    private var resResolver: PreviewResourceResolver? = null
    private var resResolverBuilt = false

    /**
     * Every `res/` directory on the way up from [file]. A layout lives at
     * `<module>/src/main/res/layout/x.xml`, but library modules and projects
     * using a non-standard layout put resources elsewhere, so the walk looks
     * for a res directory at each level rather than assuming the depth.
     */
    private fun resRootsFor(file: File): List<File> {
        val roots = LinkedHashSet<File>()
        var dir: File? = file.parentFile
        var up = 0
        while (dir != null && up < 6) {
            File(dir, "src/main/res").takeIf { it.isDirectory }?.let { roots.add(it) }
            // A res directory itself: its parent holds the source set dirs.
            if (dir.name == "res") {
                roots.add(dir)
                dir.parentFile?.listFiles()
                    ?.filter { it.isDirectory && File(it, "res").isDirectory }
                    ?.forEach { roots.add(File(it, "res")) }
            }
            dir = dir.parentFile
            up++
        }
        return roots.toList()
    }

    /** Resolver for [file], or null when the project declares no resources. */
    private fun resolverFor(file: File?): PreviewResourceResolver? {
        // parseDimen/parseColor run once per attribute, so the directory walk
        // must happen exactly once rather than per lookup.
        if (resResolverBuilt) return resResolver
        resResolverBuilt = true
        file ?: return null
        val roots = resRootsFor(file)
        if (roots.isEmpty()) return null
        val dark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return PreviewResourceResolver(roots, dark).also { resResolver = it }
    }

    /**
     * Dereferences a raw attribute value through the project's resources.
     * Returns the original text when it is not a project reference - framework
     * (`@android:...`) and theme (`?attr/...`) values keep flowing to the
     * built-in handling below - and null when a project reference cannot be
     * resolved, so the caller keeps its default.
     */
    private fun resolveRef(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (!text.startsWith("@")) return text
        if (text.startsWith("@android:") || text.startsWith("@null") || text.startsWith("@empty")) {
            return text
        }
        val resolved = resolverFor(targetFile)?.resolve(text) ?: return null
        return resolved.ifEmpty { null }
    }
    private var rootNode: XmlNode? = null
    private var selectedNode: XmlNode? = null
    private var isEditMode = false
    private var previewFile: File? = null

    private lateinit var mainContainer: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var designCanvas: DesignCanvas
    private lateinit var editToggleBtn: ImageButton
    private lateinit var outputText: TextView
    private lateinit var sourceText: TextView
    private lateinit var phoneFrame: android.graphics.Bitmap
    private var widgetHost: FrameLayout? = null

    // --- 비주얼 편집기 상태 ---
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var suppressFileObserver = false
    private var fileObserver: FileObserver? = null
    private var widgetPalette: View? = null
    private var editToolbar: View? = null
    private var zoomLabel: TextView? = null
    private var undoBtn: View? = null
    private var redoBtn: View? = null
    private var phoneScreenWidth = 0f
    private var phoneScreenHeight = 0f

    // --- 고급 프리뷰 상태 ---
    private var isDarkTheme = true
    private var deviceSize = "phone" // phone, tablet, foldable
    private var liveSyncEnabled = true
    private var isLandscape = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filePath = intent.getStringExtra(EXTRA_FILE)
        val file = filePath?.let { File(it) }
        targetFile = filePath?.let { File(it) }

        if (file == null || !file.exists()) {
            showError("File not found.")
            return
        }

        when (file.extension.lowercase()) {
            "xml" -> if (isDrawableResource(file)) showDrawablePreview(file) else showXmlPreview(file)
            "png", "jpg", "jpeg", "webp", "gif" -> showDrawablePreview(file)
            "kt", "java", "c", "cpp", "h", "hpp" -> showCodeViewer(file)
            else -> showCodeViewer(file)
        }
    }

    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        MobileStudioCrashGuard.finishSafely(this)
    }

    private fun showXmlPreview(file: File) {
        phoneFrame = try {
            assets.open("smartphone.png").use { BitmapFactory.decodeStream(it) }
                ?: Bitmap.createBitmap(719, 1560, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "Load smartphone.png failed", e)
            Bitmap.createBitmap(719, 1560, Bitmap.Config.ARGB_8888)
        }

        buildUI()
        loadXmlToNodes()
        renderDesign()
        renderLayoutLibInBackground()
    }

    // =========================================================================
    // 메인 UI
    // =========================================================================

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#1E2227".toColorInt())
        }

        // --- 상단 바 ---
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            setBackgroundColor("#21252B".toColorInt())

            addView(TextView(this@LayoutPreviewActivity).apply {
                text = targetFile?.name ?: "Layout"
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })

            // 연필 아이콘 (편집 모드 토글)
            editToggleBtn = ImageButton(this@LayoutPreviewActivity).apply {
                setImageResource(android.R.drawable.ic_menu_edit)
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                setOnClickListener { toggleEditMode() }
            }
            addView(editToggleBtn)

            addView(Button(this@LayoutPreviewActivity).apply {
                text = "Save"
                textSize = 12f
                setTextColor(Color.WHITE)
                setBackgroundColor("#1A5C3A".toColorInt())
                setOnClickListener { saveXml() }
            })

            // 테마 토글 (라이트/다크)
            addView(Button(this@LayoutPreviewActivity).apply {
                text = if (isDarkTheme) "🌙 Dark" else "☀️ Light"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor("#2B3140".toColorInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    isDarkTheme = !isDarkTheme
                    text = if (isDarkTheme) "🌙 Dark" else "☀️ Light"
                    renderDesign()
                    refreshWidgetPreview()
                }
            })

            // 디바이스 크기 선택
            addView(Button(this@LayoutPreviewActivity).apply {
                text = when (deviceSize) {
                    "phone" -> "📱 Phone"
                    "tablet" -> "📟 Tablet"
                    "foldable" -> "📱📱 Foldable"
                    else -> "📱 Phone"
                }
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor("#2B3140".toColorInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    deviceSize = when (deviceSize) {
                        "phone" -> "tablet"
                        "tablet" -> "foldable"
                        else -> "phone"
                    }
                    text = when (deviceSize) {
                        "phone" -> "📱 Phone"
                        "tablet" -> "📟 Tablet"
                        "foldable" -> "📱📱 Foldable"
                        else -> "📱 Phone"
                    }
                    renderDesign()
                    refreshWidgetPreview()
                }
            })

            // 템플릿 갤러리 (XML 몰라도 쉽게 레이아웃 생성)
            addView(Button(this@LayoutPreviewActivity).apply {
                text = "🎨 Templates"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor("#2B3140".toColorInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { showTemplateGallery() }
            })

            // 라이브 싱크 토글 (소스 ↔ 디자인 실시간 동기화)
            addView(Button(this@LayoutPreviewActivity).apply {
                text = if (liveSyncEnabled) "🔄 Sync On" else "🔄 Sync Off"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(if (liveSyncEnabled) 0xFF1A5C3A.toInt() else 0xFF2B3140.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    liveSyncEnabled = !liveSyncEnabled
                    text = if (liveSyncEnabled) "🔄 Sync On" else "🔄 Sync Off"
                    setBackgroundColor(if (liveSyncEnabled) 0xFF1A5C3A.toInt() else 0xFF2B3140.toInt())
                    val isKo = AppLanguage.isKorean(this@LayoutPreviewActivity)
                    Toast.makeText(this@LayoutPreviewActivity,
                        if (isKo) "라이브 싱크 ${if (liveSyncEnabled) "켜짐" else "꺼짐"}"
                        else "Live Sync ${if (liveSyncEnabled) "On" else "Off"}",
                        Toast.LENGTH_SHORT).show()
                }
            })

            // 수동 싱크 버튼 (소스 → 디자인)
            addView(Button(this@LayoutPreviewActivity).apply {
                text = "↻ Sync → Design"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor("#2B3140".toColorInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { syncSourceToDesign() }
            })

            // 화면 회전 토글
            addView(Button(this@LayoutPreviewActivity).apply {
                text = if (isLandscape) "🔁 Landscape" else "🔁 Portrait"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor("#2B3140".toColorInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    isLandscape = !isLandscape
                    text = if (isLandscape) "🔁 Landscape" else "🔁 Portrait"
                    renderDesign()
                    refreshWidgetPreview()
                    val isKo = AppLanguage.isKorean(this@LayoutPreviewActivity)
                    Toast.makeText(this@LayoutPreviewActivity,
                        if (isKo) "회전: ${if (isLandscape) "가로" else "세로"}"
                        else "Rotation: ${if (isLandscape) "Landscape" else "Portrait"}",
                        Toast.LENGTH_SHORT).show()
                }
            })
        }
        root.addView(topBar)

        // --- 탭 바 (Design, Source, Output) ---
        val tabBar = LinearLayout(this).apply { setBackgroundColor("#1B1F24".toColorInt()) }
        val tabs = listOf("Design", "Source", "Output").mapIndexed { i, label ->
            TextView(this).apply {
                text = label; setPadding(dp(20), dp(10), dp(20), dp(10)); textSize = 12f
                setTextColor(if (i == 0) Color.WHITE else Color.LTGRAY)
                setBackgroundColor(if (i == 0) "#4F8EF7".toColorInt() else Color.TRANSPARENT)
                setOnClickListener { switchTab(i) }
            }.also { tabBar.addView(it) }
        }
        root.addView(tabBar)

        // --- 편집 툴바 (편집 모드에서만 표시) ---
        editToolbar = LinearLayout(this).apply {
            tag = "edit_toolbar"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor("#161A20".toColorInt())

            fun makeToolButton(text: String, onClick: () -> Unit): TextView = TextView(this@LayoutPreviewActivity).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setBackgroundColor("#2B3140".toColorInt())
                setOnClickListener { onClick() }
            }

            undoBtn = makeToolButton("← Undo") { undoEdit() }
            addView(undoBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })

            redoBtn = makeToolButton("Redo →") { redoEdit() }
            addView(redoBtn, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })

            zoomLabel = TextView(this@LayoutPreviewActivity).apply {
                text = "100%"
                textSize = 12f
                setTextColor("#4F8EF7".toColorInt())
                setPadding(dp(8), 0, dp(8), 0)
            }
            addView(zoomLabel, LinearLayout.LayoutParams(0, -2, 1f).apply {
                gravity = Gravity.CENTER
            })

            addView(makeToolButton("Fit") { designCanvas?.resetView() }, LinearLayout.LayoutParams(-2, -2))

            // 디자인 → 소스 싱크 버튼
            addView(makeToolButton("↻ → Source") { syncDesignToSource() }, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(editToolbar)

        // --- 메인 콘텐츠 영역 ---
        mainContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // 1. Design 패널 (smartphone.png 배경 + 실제 위젯 프리뷰 + 편집 캔버스)
        val designPanel = buildDesignPanel()
        mainContainer.addView(designPanel)

        // 2. 소스 코드 뷰어
        val sourceView = ScrollView(this).apply {
            tag = "source_scroll"
            visibility = View.GONE
            sourceText = TextView(this@LayoutPreviewActivity).apply {
                tag = "source_text"
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor("#C9D1D9".toColorInt())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setTextIsSelectable(true)
            }
            // 라이브 싱크: 소스 편집 시 디자인에 실시간 반영
            sourceText.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (liveSyncEnabled) {
                        // 디바운싱 없이 즉시 반영 (작은 파일에 적합)
                        sourceText.postDelayed({ syncSourceToDesign() }, 500)
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
            addView(sourceText)
        }
        mainContainer.addView(sourceView)

        // 3. 출력 로그 (Copy All 버튼 포함)
        val outputScroll = buildOutputPanel()
        mainContainer.addView(outputScroll)

        root.addView(mainContainer)
        setContentView(root)

        // Design 탭 활성화
        switchTab(0)
        // 원본 파일 소스를 로드해두기 (전체 저장 용)
        loadXmlToNodes()
    }

    /**
     * Design 탭 패널: smartphone.png 폰 프레임 위에
     *  - 보기 모드: 실제 위젯 렌더링 (widgetHost) 또는 LayoutLib 렌더 이미지
     *  - 편집 모드: 프리뷰 유지 + DesignCanvas 오버레이 (선택/리사이즈/회전/줌)
     *  - 하단: 위젯 팔레트 (편집 모드에서 표시)
     */
    private fun buildDesignPanel(): View {
        val dPanel = LinearLayout(this).apply {
            tag = "design_panel"
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor("#0F1218".toColorInt())
        }

        // 왼쪽: 노드 트리 (컴포넌트 트리)
        val nodeTree = buildNodeTreePanel()
        dPanel.addView(nodeTree, LinearLayout.LayoutParams(dp(220), -1))

        // 오른쪽: 디자인 캔버스 + 프리뷰
        val designArea = FrameLayout(this).apply {
            setBackgroundColor("#0F1218".toColorInt())

            // 편집 캔버스 (스마트폰 화면 영역에 정렬, 투명 배경)
            designCanvas = DesignCanvas(this@LayoutPreviewActivity).apply {
                visibility = View.GONE
                onNodeLongPressed = { showNodeMenu(it) }
                onGeometryCommitted = { commitNodeGeometry(it) }
            }

            // 프리뷰 (smartphone.png + 실제 위젯 + LayoutLib 이미지)
            val screenArea = buildScreenArea(
                this@LayoutPreviewActivity.targetFile ?: return@apply, designCanvas
            )
            addView(screenArea, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // 하단 위젯 팔레트 (편집 모드에서만 표시)
            widgetPalette = buildWidgetPalette().apply { visibility = View.GONE }
            addView(widgetPalette, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(132),
                Gravity.BOTTOM
            ))
        }
        dPanel.addView(designArea, LinearLayout.LayoutParams(0, -1, 1f))

        return dPanel
    }

    /**
     * 노드 트리 패널 (Android Studio Component Tree 스타일).
     * XML 계층 구조를 트리로 표시하고, 항목 클릭 시 해당 노드를 선택/하이라이트.
     */
    private fun buildNodeTreePanel(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#161A20".toColorInt())
        }

        // 헤더
        container.addView(TextView(this).apply {
            text = "Component Tree"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(8))
            setBackgroundColor("#21252B".toColorInt())
        })

        // 트리 스크롤
        val treeScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor("#161A20".toColorInt())
        }
        val treeRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        treeScroll.addView(treeRoot)
        container.addView(treeScroll)

        // 리프레시 버튼
        container.addView(Button(this).apply {
            text = "Refresh Tree"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor("#2B3140".toColorInt())
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(6); bottomMargin = dp(6)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener { refreshNodeTree(treeRoot) }
        })

        // 초기 렌더링
        refreshNodeTree(treeRoot)

        return container
    }

    private fun refreshNodeTree(treeRoot: LinearLayout) {
        treeRoot.removeAllViews()
        val root = rootNode
        if (root == null) {
            treeRoot.addView(TextView(this).apply {
                text = "No nodes"
                textSize = 12f
                setTextColor("#8B949E".toColorInt())
                setPadding(dp(12), dp(12), dp(12), dp(12))
            })
            return
        }
        addNodeTreeItems(treeRoot, root, 0)
    }

    private fun addNodeTreeItems(parent: LinearLayout, node: XmlNode, depth: Int) {
        val isKo = AppLanguage.isKorean(this)
        val isSelected = selectedNode == node

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12 + depth * 12), dp(6), dp(8), dp(6))
            setBackgroundColor(if (isSelected) 0xFF4F8EF7.toInt() else 0xFF161A20.toInt())
            setOnClickListener {
                selectedNode = node
                designCanvas.select(node)
                refreshNodeTree(parent)
                val isKo2 = AppLanguage.isKorean(this@LayoutPreviewActivity)
                Toast.makeText(this@LayoutPreviewActivity, "<${node.tagName}> selected", Toast.LENGTH_SHORT).show()
            }
        }

        // 깊이 인디케이터
        if (depth > 0) {
            row.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(4), dp(4))
                setBackgroundColor("#4F8EF7".toColorInt())
            })
        }

        // 아이콘 + 태그명
        val icon = if (node.isLayout) "▭" else "○"
        row.addView(TextView(this).apply {
            text = icon
            textSize = 12f
            setTextColor(if (isSelected) Color.WHITE else "#C9D1D9".toColorInt())
            setPadding(0, 0, dp(4), 0)
        })
        row.addView(TextView(this).apply {
            text = "<${node.tagName}${node.id?.let { " #$it" } ?: ""}"
            textSize = 12f
            setTextColor(if (isSelected) Color.WHITE else "#C9D1D9".toColorInt())
            typeface = Typeface.MONOSPACE
        })

        parent.addView(row)

        // 자식 노드 재귀
        node.children.forEach { child ->
            addNodeTreeItems(parent, child, depth + 1)
        }
    }

    /**
     * smartphone.png 의 화면 영역(비율 유지)을 계산하고 그 위에 실제 위젯 프리뷰를 배치한다.
     * 편집 캔버스는 프리뷰 위 오버레이로 같은 화면 영역에 정렬된다.
     */
    private fun buildScreenArea(file: File, canvas: DesignCanvas): View {
        val wrapper = FrameLayout(this).apply {
            setBackgroundColor("#0F1218".toColorInt())
        }

        // 폰 프레임 이미지 (비율 유지, 중앙 정렬)
        val imgW = phoneFrame.width.toFloat()
        val imgH = phoneFrame.height.toFloat()

        wrapper.post {
            val availW = wrapper.width.toFloat()
            val availH = wrapper.height.toFloat()
            val scale = minOf(availW / imgW, availH / imgH)
            val phoneW = imgW * scale
            val phoneH = imgH * scale
            val left = (availW - phoneW) / 2f
            val top = (availH - phoneH) / 2f

            val phoneBg = ImageView(this@LayoutPreviewActivity).apply {
                setImageBitmap(phoneFrame)
                scaleType = ImageView.ScaleType.FIT_XY
            }
            wrapper.removeAllViews()
            wrapper.addView(phoneBg, FrameLayout.LayoutParams(
                phoneW.toInt(), phoneH.toInt(), Gravity.TOP or Gravity.LEFT
            ).apply { setMargins(left.toInt(), top.toInt(), 0, 0) })

            // 화면 영역 (상단 노치/하단 내비 여백)
            val statusBarH = (32f * resources.displayMetrics.density)
            val navBarH = (42f * resources.displayMetrics.density)
            val screenW = phoneW
            val screenH = phoneH - statusBarH - navBarH
            val screenLeft = left
            val screenTop = top + statusBarH

            val previewHost = FrameLayout(this@LayoutPreviewActivity)
            previewHost.tag = "widget_host"
            val content = buildLightweightPreviewContent(file)
            previewHost.addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            // LayoutLib 렌더 이미지 (성공 시 덮어씀)
            previewImage = ImageView(this@LayoutPreviewActivity).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
                setBackgroundColor(Color.WHITE)
            }
            previewHost.addView(previewImage, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))

            wrapper.addView(previewHost, FrameLayout.LayoutParams(
                screenW.toInt(), screenH.toInt(), Gravity.TOP or Gravity.LEFT
            ).apply { setMargins(screenLeft.toInt(), screenTop.toInt(), 0, 0) })

            // 편집 캔버스: 프리뷰와 동일한 화면 영역에 오버레이
            wrapper.addView(canvas, FrameLayout.LayoutParams(
                screenW.toInt(), screenH.toInt(), Gravity.TOP or Gravity.LEFT
            ).apply { setMargins(screenLeft.toInt(), screenTop.toInt(), 0, 0) })

            widgetHost = previewHost
            phoneScreenWidth = screenW
            phoneScreenHeight = screenH
            renderDesign()
        }
        return wrapper
    }

    private fun toggleEditMode() {
        isEditMode = !isEditMode
        editToggleBtn.setColorFilter(if (isEditMode) "#4F8EF7".toColorInt() else Color.WHITE)

        if (isEditMode) {
            // 편집 모드 활성화
            previewImage.visibility = View.GONE
            designCanvas.visibility = View.VISIBLE
            widgetPalette?.visibility = View.VISIBLE
            editToolbar?.visibility = View.VISIBLE
            loadXmlToNodes()
            renderDesign()
            refreshWidgetPreview() 
            startFileObserver()
        } else {
            // 편집 모드 비활성화 (미리보기 모드)
            designCanvas.visibility = View.GONE
            widgetPalette?.visibility = View.GONE
            editToolbar?.visibility = View.GONE
            stopFileObserver()
            
            // 최신 상태로 고화질 미리보기 요청
            renderLayoutLibInBackground()
            
            // 고화질 이미지가 없으면 경량 미리보기를 유지
            if (previewImage.drawable != null) {
                previewImage.visibility = View.VISIBLE
                widgetHost?.findViewById<ScrollView>(R.id.widget_scroll)?.visibility = View.GONE
            } else {
                previewImage.visibility = View.GONE
                widgetHost?.findViewById<ScrollView>(R.id.widget_scroll)?.visibility = View.VISIBLE
            }
        }
        updateUndoRedoButtons()
    }

    private fun switchTab(index: Int) {
        val designPanel = mainContainer.findViewWithTag<View>("design_panel")
        val sourceView = mainContainer.findViewWithTag<ScrollView>("source_scroll")
        val outputScroll = mainContainer.findViewWithTag<ScrollView>("output_scroll")
        
        designPanel?.visibility = if (index == 0) View.VISIBLE else View.GONE
        sourceView?.visibility = if (index == 1) View.VISIBLE else View.GONE
        outputScroll?.visibility = if (index == 2) View.VISIBLE else View.GONE

        if (index == 0) {
            // Design 탭으로 돌아올 때 소스 수정사항 반영 (Source → Design)
            loadXmlToNodes()
            renderDesign()
            refreshWidgetPreview()
        } else if (index == 1) {
            // Source 탭으로 갈 때 현재 디자인 상태 반영 (Design → Source)
            val currentRoot = rootNode
            val currentXml = if (currentRoot != null) buildXmlFromNodes(currentRoot, 0) else targetFile?.readText() ?: ""
            applyXmlSyntaxHighlighting(sourceText, currentXml)
        }

        // 탭 버튼 스타일 업데이트
        val tabBar = (mainContainer.parent as? LinearLayout)?.getChildAt(1) as? LinearLayout
        tabBar?.let { highlightTab(it.getChildAt(0).parent as LinearLayout, index) }
    }

    private fun highlightTab(bar: LinearLayout, index: Int) {
        for (i in 0 until bar.childCount) {
            val tv = bar.getChildAt(i) as? TextView ?: continue
            tv.setTextColor(if (i == index) Color.WHITE else Color.LTGRAY)
            tv.setBackgroundColor(if (i == index) "#4F8EF7".toColorInt() else Color.TRANSPARENT)
        }
    }

    // =========================================================================
    // 양방향 소스 ↔ 디자인 동기화
    // =========================================================================

    /**
     * 소스 코드를 파싱하여 디자인 프리뷰에 반영 (Source → Design).
     * 소스 탭에서 편집 후 수동 싱크 버튼 또는 라이브 싱크로 호출됨.
     */
    private fun syncSourceToDesign() {
        val xml = sourceText.text.toString()
        if (xml.isBlank()) {
            val isKo = AppLanguage.isKorean(this)
            Toast.makeText(this, if (isKo) "소스가 비어있습니다." else "Source is empty.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // 파일을 임시로 업데이트하지 않고 메모리에서 바로 파싱
            val newRoot = parseXmlToNodes(xml)
            if (newRoot == null) {
                val isKo = AppLanguage.isKorean(this)
                Toast.makeText(this, if (isKo) "XML 파싱 실패 - 디자인 업데이트 불가" else "XML parse failed - cannot update design",
                    Toast.LENGTH_SHORT).show()
                return
            }
            rootNode = newRoot
            renderDesign()
            refreshWidgetPreview()
            val isKo = AppLanguage.isKorean(this)
            Toast.makeText(this, if (isKo) "소스 → 디자인 동기화 완료" else "Source → Design synced", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "syncSourceToDesign error", e)
            val isKo = AppLanguage.isKorean(this)
            Toast.makeText(this, if (isKo) "동기화 오류: ${e.message}" else "Sync error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 디자인 상태에서 소스 코드로 반영 (Design → Source).
     * 현재 rootNode 기반으로 XML을 생성하여 소스 뷰에 표시.
     */
    private fun syncDesignToSource() {
        val currentRoot = rootNode
        if (currentRoot == null) return
        val xml = buildXmlFromNodes(currentRoot, 0)
        applyXmlSyntaxHighlighting(sourceText, xml)
        val isKo = AppLanguage.isKorean(this)
        Toast.makeText(this, if (isKo) "디자인 → 소스 동기화 완료" else "Design → Source synced", Toast.LENGTH_SHORT).show()
    }

    /**
     * XML 소스에 간단한 구문 하이라이팅 적용 (태그, 속성, 값, 주석).
     * 소스 뷰에 표시될 때 호출됨.
     */
    private fun applyXmlSyntaxHighlighting(textView: TextView, xml: String) {
        val spannable = SpannableString(xml)
        val tagColor = 0xFF4F8EF7.toInt()      // 파란색 - 태그
        val attrColor = 0xFF9B5DE5.toInt()     // 보라색 - 속성명
        val valColor = 0xFF00C853.toInt()      // 초록색 - 속성값
        val commentColor = 0xFF8B949E.toInt()  // 회색 - 주석

        // 태그 하이라이팅: <tagname>
        val tagRegex = Regex("<(/)?(\\w+[\\w.-]*)")
        for (match in tagRegex.findAll(xml)) {
            spannable.setSpan(android.text.style.ForegroundColorSpan(tagColor),
                match.range.first, match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 속성명: name="
        val attrRegex = Regex("(\\w+):?(\\w+)=\"")
        for (match in attrRegex.findAll(xml)) {
            val attrName = match.groupValues[0].removeSuffix("=\"")
            val nameEnd = match.range.first + attrName.length
            spannable.setSpan(android.text.style.ForegroundColorSpan(attrColor),
                match.range.first, nameEnd,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 속성값: "value"
        val valRegex = Regex("\"([^\"]*)\"")
        for (match in valRegex.findAll(xml)) {
            spannable.setSpan(android.text.style.ForegroundColorSpan(valColor),
                match.range.first, match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // 주석: <!-- ... -->
        val commentRegex = Regex("<!--.*?-->")
        for (match in commentRegex.findAll(xml)) {
            spannable.setSpan(android.text.style.ForegroundColorSpan(commentColor),
                match.range.first, match.range.last + 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        textView.setText(spannable, android.widget.TextView.BufferType.SPANNABLE)
    }

    // =========================================================================
    // 실제 위젯 렌더링 (lightweight, LayoutLib 없이도 동작)
    // =========================================================================

    private fun buildLightweightPreviewContent(file: File): View {
        val scrollView = ScrollView(this).apply {
            id = R.id.widget_scroll
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(if (isDarkTheme) "#0F1218".toColorInt() else Color.WHITE)
        }
        try {
            val rootView = parseAndBuildView(file.readText())
            if (rootView != null) {
                scrollView.addView(
                    rootView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            } else {
                scrollView.addView(makeFallbackView("XML parse failed: no root element"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "lightweight XML render error", e)
            scrollView.addView(makeFallbackView("Render error: ${e.message}"))
        }
        return scrollView
    }

    private fun parseAndBuildView(xmlContent: String): View? {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(xmlContent.reader())
            var event = parser.eventType
            while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
                event = parser.next()
            }
            if (event == XmlPullParser.END_DOCUMENT) return null
            buildViewFromParser(parser)
        } catch (e: Exception) {
            Log.e(TAG, "parseAndBuildView error", e)
            null
        }
    }

    private fun buildViewFromParser(parser: XmlPullParser): View? {
        val tag = parser.name ?: return null
        val attrs = collectAttrs(parser)
        return if (isLayoutTag(tag)) {
            buildLayoutView(tag, attrs, parser)
        } else {
            val v = buildWidgetView(tag, attrs)
            consumeUntilEndTag(parser, tag)
            v
        }
    }

    private fun collectAttrs(parser: XmlPullParser): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        for (i in 0 until parser.attributeCount) {
            val prefix = parser.getAttributePrefix(i)
            val name = if (prefix.isNullOrEmpty()) parser.getAttributeName(i) else "$prefix:${parser.getAttributeName(i)}"
            attrs[name] = parser.getAttributeValue(i)
        }
        return attrs
    }

    private fun consumeUntilEndTag(parser: XmlPullParser, tag: String) {
        var event = parser.next()
        var depth = 1
        while (event != XmlPullParser.END_DOCUMENT && depth > 0) {
            when (event) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> {
                    depth--
                    if (depth == 0 && parser.name == tag) return
                }
            }
            event = parser.next()
        }
    }

    // =========================================================================
    // 레이아웃 컨테이너 빌드
    // =========================================================================

    private fun buildLayoutView(tag: String, attrs: Map<String, String>, parser: XmlPullParser): ViewGroup {
        val container: ViewGroup = createContainerForTag(tag, attrs)
        applyCommonAttrs(container, attrs)
        applyContainerAttrs(container, attrs, tag)

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == tag)) {
            if (event == XmlPullParser.START_TAG) {
                val childTag = parser.name ?: ""
                val childAttrs = collectAttrs(parser)
                val child: View? = if (isLayoutTag(childTag)) {
                    buildLayoutView(childTag, childAttrs, parser)
                } else {
                    val w = buildWidgetView(childTag, childAttrs)
                    consumeUntilEndTag(parser, childTag)
                    w
                }
                child?.let {
                    try {
                        applyChildLayoutParams(container, it, childAttrs)
                        container.addView(it)
                    } catch (e: Exception) {
                        Log.w(TAG, "addView failed: $childTag (${e.message})")
                    }
                }
            }
            if (event == XmlPullParser.END_DOCUMENT) break
            event = parser.next()
        }
        return container
    }

    private fun createContainerForTag(tag: String, attrs: Map<String, String>): ViewGroup {
        val low = tag.lowercase()
        return when {
            low.endsWith("radiogroup") -> RadioGroup(this).apply {
                orientation = if (attrs["orientation"]?.lowercase() == "horizontal")
                    LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
            low.endsWith("linearlayout") -> LinearLayout(this).apply {
                orientation = if (attrs["orientation"]?.lowercase() == "horizontal")
                    LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
            low.endsWith("tablelayout") -> LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            low.endsWith("tablerow") -> LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            low.endsWith("gridlayout") -> LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            low.endsWith("nestedscrollview") || low.endsWith("scrollview") -> ScrollView(this)
            low.endsWith("horizontalscrollview") -> HorizontalScrollView(this)
            low.contains("toolbar") -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(COLOR_PRIMARY.toColorInt())
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                minimumHeight = dp(56)
            }
            low.contains("appbarlayout") -> LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COLOR_PRIMARY.toColorInt())
                elevation = dp(4f)
            }
            low.contains("tablayout") -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(COLOR_PRIMARY.toColorInt())
                minimumHeight = dp(48)
            }
            low.contains("bottomnavigation") -> LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.WHITE)
                elevation = dp(8f)
                minimumHeight = dp(56)
            }
            low.contains("cardview") -> FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = dp(4f)
                }
                elevation = dp(2f)
            }
            low.contains("constraintlayout") -> androidx.constraintlayout.widget.ConstraintLayout(this)
            else -> FrameLayout(this)
        }
    }

    // =========================================================================
    // 위젯 빌드
    // =========================================================================

    private fun buildWidgetView(tag: String, attrs: Map<String, String>): View? {
        val low = tag.lowercase()
        val view: View = when {
            low.endsWith("multiautocompletetextview") -> MultiAutoCompleteTextView(this)
            low.endsWith("autocompletetextview") -> AutoCompleteTextView(this)
            low.endsWith("edittext") -> EditText(this)
            low.endsWith("materialbutton") -> Button(this).apply {
                setBackgroundColor(COLOR_PRIMARY.toColorInt())
                setTextColor(COLOR_ON_PRIMARY.toColorInt())
            }
            low.endsWith("imagebutton") -> ImageButton(this)
            low.endsWith("button") -> Button(this)
            low.endsWith("imageview") -> ImageView(this).apply {
                setBackgroundColor("#E0E0E0".toColorInt())
                minimumWidth = dp(48)
                minimumHeight = dp(48)
            }
            low.endsWith("checkbox") -> CheckBox(this)
            low.endsWith("radiobutton") -> RadioButton(this)
            low.endsWith("togglebutton") -> ToggleButton(this)
            low.endsWith("materialswitch") ||
                    low.endsWith("switchcompat") ||
                    low.endsWith("switch") -> Switch(this)
            low.endsWith("seekbar") -> SeekBar(this)
            low.endsWith("ratingbar") -> RatingBar(this)
            low.endsWith("progressbar") -> {
                val style = attrs["style"]?.lowercase() ?: ""
                if (style.contains("horizontal"))
                    ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
                else ProgressBar(this)
            }
            low.endsWith("spinner") -> Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@LayoutPreviewActivity,
                    android.R.layout.simple_spinner_item,
                    listOf(attrs["text"] ?: "Item 1", "Item 2", "Item 3")
                )
            }
            low.endsWith("numberpicker") -> NumberPicker(this).apply { minValue = 0; maxValue = 10 }
            low.endsWith("searchview") -> SearchView(this).apply {
                queryHint = attrs["queryHint"] ?: attrs["hint"] ?: "Search"
            }
            low.endsWith("chronometer") -> Chronometer(this)
            low.endsWith("analogclock") -> @Suppress("DEPRECATION") AnalogClock(this)
            low.endsWith("space") -> Space(this)
            low.endsWith("webview") -> placeholderBox("WebView", "#FFE0B2")
            low.endsWith("videoview") ||
                    low.endsWith("surfaceview") ||
                    low.endsWith("textureview") -> placeholderBox(tag.substringAfterLast('.'), "#212121")
            low.endsWith("recyclerview") ||
                    low.endsWith("listview") ||
                    low.endsWith("gridview") -> placeholderBox(tag.substringAfterLast('.'), "#F5F5F5")
            low == "view" -> View(this).apply {
                if (attrs["background"] == null) setBackgroundColor("#BDBDBD".toColorInt())
            }
            else -> TextView(this).apply {
                text = attrs["text"] ?: tag.substringAfterLast('.')
            }
        }
        applyCommonAttrs(view, attrs)
        applyTextAttrs(view, attrs)
        applyImageAttrs(view, attrs)
        applyCheckableAttrs(view, attrs)
        applyProgressAttrs(view, attrs)
        return view
    }

    private fun placeholderBox(label: String, bg: String): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(bg.toColorInt())
            minimumWidth = dp(120)
            minimumHeight = dp(80)
        }
        frame.addView(TextView(this).apply {
            text = label
            setTextColor(Color.DKGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        })
        return frame
    }

    // =========================================================================
    // 속성 적용
    // =========================================================================

    private fun applyCommonAttrs(v: View, attrs: Map<String, String>) {
        attrs["background"]?.let { raw ->
            parseColor(raw)?.let { v.setBackgroundColor(it) }
        }
        attrs["backgroundTint"]?.let { raw ->
            parseColor(raw)?.let { v.background?.setColorFilter(it, PorterDuff.Mode.SRC_IN) }
        }
        applyPadding(v, attrs)
        attrs["visibility"]?.let {
            v.visibility = when (it.lowercase()) {
                "gone" -> View.GONE
                "invisible" -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }
        attrs["alpha"]?.toFloatOrNull()?.let { v.alpha = it }
        attrs["rotation"]?.toFloatOrNull()?.let { v.rotation = it }
        attrs["scaleX"]?.toFloatOrNull()?.let { v.scaleX = it }
            ?: attrs["android:scaleX"]?.toFloatOrNull()?.let { v.scaleX = it }
        attrs["scaleY"]?.toFloatOrNull()?.let { v.scaleY = it }
            ?: attrs["android:scaleY"]?.toFloatOrNull()?.let { v.scaleY = it }
        parseDimen(attrs["elevation"])?.let { v.elevation = it }
        attrs["enabled"]?.let { v.isEnabled = it.equals("true", true) }
        attrs["clickable"]?.let { v.isClickable = it.equals("true", true) }
        attrs["focusable"]?.let { v.isFocusable = it.equals("true", true) }
        parseDimen(attrs["minWidth"])?.let { v.minimumWidth = it.toInt() }
        parseDimen(attrs["minHeight"])?.let { v.minimumHeight = it.toInt() }
        attrs["contentDescription"]?.let { v.contentDescription = it }
        attrs["gravity"]?.let { applyGravity(v, it) }
    }

    private fun applyPadding(v: View, attrs: Map<String, String>) {
        val all = parseDimen(attrs["padding"])?.toInt()
        var l = all ?: v.paddingLeft
        var t = all ?: v.paddingTop
        var r = all ?: v.paddingRight
        var b = all ?: v.paddingBottom
        parseDimen(attrs["paddingLeft"])?.toInt()?.let { l = it }
        parseDimen(attrs["paddingStart"])?.toInt()?.let { l = it }
        parseDimen(attrs["paddingTop"])?.toInt()?.let { t = it }
        parseDimen(attrs["paddingRight"])?.toInt()?.let { r = it }
        parseDimen(attrs["paddingEnd"])?.toInt()?.let { r = it }
        parseDimen(attrs["paddingBottom"])?.toInt()?.let { b = it }
        parseDimen(attrs["paddingHorizontal"])?.toInt()?.let { l = it; r = it }
        parseDimen(attrs["paddingVertical"])?.toInt()?.let { t = it; b = it }
        v.setPadding(l, t, r, b)
    }

    private fun applyGravity(v: View, raw: String) {
        val g = parseGravity(raw)
        when (v) {
            is TextView -> v.gravity = g
            is LinearLayout -> v.gravity = g
            is FrameLayout -> v.foregroundGravity = g
        }
    }

    private fun applyContainerAttrs(c: ViewGroup, attrs: Map<String, String>, tag: String) {
        if (c is LinearLayout) {
            attrs["orientation"]?.let {
                c.orientation = if (it.lowercase() == "horizontal")
                    LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            }
            attrs["weightSum"]?.toFloatOrNull()?.let { c.weightSum = it }
            attrs["baselineAligned"]?.let { c.isBaselineAligned = it.equals("true", true) }
        }
        if (tag.lowercase().contains("card")) {
            val radius = parseDimen(attrs["cardCornerRadius"]) ?: parseDimen(attrs["cornerRadius"])
            val elev = parseDimen(attrs["cardElevation"])
            val cardBg = parseColor(attrs["cardBackgroundColor"])
            if (radius != null || cardBg != null) {
                val drawable = GradientDrawable().apply {
                    setColor(cardBg ?: Color.WHITE)
                    cornerRadius = radius ?: dp(4f)
                }
                c.background = drawable
            }
            elev?.let { c.elevation = it }
        }
    }

    private fun applyTextAttrs(v: View, attrs: Map<String, String>) {
        if (v !is TextView) return
        // @string/... resolves through the project's res/values; anything else
        // is left exactly as written (the helper returns the input verbatim).
        attrs["text"]?.let { v.text = unescape(resolveRef(it) ?: it) }
        attrs["hint"]?.let { v.hint = unescape(resolveRef(it) ?: it) }
        parseDimen(attrs["textSize"])?.let {
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, it)
        }
        parseColor(attrs["textColor"])?.let { v.setTextColor(it) }
        parseColor(attrs["textColorHint"])?.let { v.setHintTextColor(it) }
        attrs["textStyle"]?.let { style ->
            val flags = style.split('|').fold(Typeface.NORMAL) { acc, s ->
                acc or when (s.trim().lowercase()) {
                    "bold" -> Typeface.BOLD
                    "italic" -> Typeface.ITALIC
                    "bold_italic", "bolditalic" -> Typeface.BOLD_ITALIC
                    else -> Typeface.NORMAL
                }
            }
            v.setTypeface(v.typeface, flags)
        }
        attrs["fontFamily"]?.let { fam ->
            val tf = when (fam.lowercase()) {
                "monospace", "sans-serif-monospace" -> Typeface.MONOSPACE
                "serif" -> Typeface.SERIF
                "sans-serif", "sans" -> Typeface.SANS_SERIF
                else -> null
            }
            tf?.let { v.typeface = it }
        }
        attrs["textAllCaps"]?.let { v.isAllCaps = it.equals("true", true) }
        attrs["letterSpacing"]?.toFloatOrNull()?.let { v.letterSpacing = it }
        attrs["lineSpacingMultiplier"]?.toFloatOrNull()?.let {
            v.setLineSpacing(v.lineSpacingExtra, it)
        }
        parseDimen(attrs["lineSpacingExtra"])?.let {
            v.setLineSpacing(it, v.lineSpacingMultiplier)
        }
        attrs["maxLines"]?.toIntOrNull()?.let { v.maxLines = it }
        attrs["lines"]?.toIntOrNull()?.let { v.setLines(it) }
        attrs["minLines"]?.toIntOrNull()?.let { v.minLines = it }
        attrs["singleLine"]?.let { if (it.equals("true", true)) v.setSingleLine() }
        attrs["ellipsize"]?.let {
            v.ellipsize = when (it.lowercase()) {
                "start" -> TextUtils.TruncateAt.START
                "middle" -> TextUtils.TruncateAt.MIDDLE
                "end" -> TextUtils.TruncateAt.END
                "marquee" -> TextUtils.TruncateAt.MARQUEE
                else -> null
            }
        }
        attrs["inputType"]?.let { v.inputType = parseInputType(it) }
        parseDimen(attrs["drawablePadding"])?.toInt()?.let { v.compoundDrawablePadding = it }
    }

    private fun parseInputType(raw: String): Int {
        var type = InputType.TYPE_CLASS_TEXT
        raw.split('|').forEach {
            type = type or when (it.trim().lowercase()) {
                "textpassword" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                "numberpassword" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                "number" -> InputType.TYPE_CLASS_NUMBER
                "numberdecimal" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                "numbersigned" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                "phone" -> InputType.TYPE_CLASS_PHONE
                "datetime" -> InputType.TYPE_CLASS_DATETIME
                "date" -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                "time" -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
                "textemailaddress" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "texturi" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                "textmultiline" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                "textcapsentences" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                "textcapwords" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                "textcapcharacters" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                else -> 0
            }
        }
        return type
    }

    private fun applyImageAttrs(v: View, attrs: Map<String, String>) {
        if (v !is ImageView) return
        val hasSrc = attrs["src"] != null || attrs["srcCompat"] != null
        if (hasSrc && v.drawable == null) {
            v.setImageDrawable(ColorDrawable("#90CAF9".toColorInt()))
        }
        parseColor(attrs["tint"])?.let {
            v.setColorFilter(it, PorterDuff.Mode.SRC_IN)
        }
        attrs["scaleType"]?.let {
            val st = when (it.lowercase()) {
                "center" -> ImageView.ScaleType.CENTER
                "centercrop" -> ImageView.ScaleType.CENTER_CROP
                "centerinside" -> ImageView.ScaleType.CENTER_INSIDE
                "fitcenter" -> ImageView.ScaleType.FIT_CENTER
                "fitstart" -> ImageView.ScaleType.FIT_START
                "fitend" -> ImageView.ScaleType.FIT_END
                "fitxy" -> ImageView.ScaleType.FIT_XY
                "matrix" -> ImageView.ScaleType.MATRIX
                else -> null
            }
            st?.let { v.scaleType = it }
        }
    }

    private fun applyCheckableAttrs(v: View, attrs: Map<String, String>) {
        attrs["checked"]?.let { raw ->
            val c = raw.equals("true", true)
            when (v) {
                is CompoundButton -> v.isChecked = c
                is ToggleButton -> v.isChecked = c
            }
        }
    }

    private fun applyProgressAttrs(v: View, attrs: Map<String, String>) {
        when (v) {
            is RatingBar -> {
                attrs["numStars"]?.toIntOrNull()?.let { v.numStars = it }
                attrs["rating"]?.toFloatOrNull()?.let { v.rating = it }
                attrs["stepSize"]?.toFloatOrNull()?.let { v.stepSize = it }
                attrs["isIndicator"]?.let { v.setIsIndicator(it.equals("true", true)) }
            }
            is SeekBar -> {
                attrs["max"]?.toIntOrNull()?.let { v.max = it }
                attrs["progress"]?.toIntOrNull()?.let { v.progress = it }
            }
            is ProgressBar -> {
                attrs["max"]?.toIntOrNull()?.let { v.max = it }
                attrs["progress"]?.toIntOrNull()?.let { v.progress = it }
                attrs["indeterminate"]?.let { v.isIndeterminate = it.equals("true", true) }
            }
        }
    }

    private fun applyChildLayoutParams(parent: ViewGroup, child: View, attrs: Map<String, String>) {
        val width = parseSize(attrs["layout_width"], ViewGroup.LayoutParams.WRAP_CONTENT)
        val height = parseSize(attrs["layout_height"], ViewGroup.LayoutParams.WRAP_CONTENT)
        val lp: ViewGroup.LayoutParams = when (parent) {
            is LinearLayout -> {
                val weight = attrs["layout_weight"]?.toFloatOrNull() ?: 0f
                LinearLayout.LayoutParams(width, height, weight).also { p ->
                    attrs["layout_gravity"]?.let { p.gravity = parseGravity(it) }
                }
            }
            is FrameLayout -> FrameLayout.LayoutParams(width, height).also { p ->
                attrs["layout_gravity"]?.let { p.gravity = parseGravity(it) }
            }
            is ConstraintLayout -> {
                ConstraintLayout.LayoutParams(width, height).also { p ->
                    applyConstraintLayoutParams(p, attrs)
                }
            }
            else -> ViewGroup.MarginLayoutParams(width, height)
        }
        if (lp is ViewGroup.MarginLayoutParams) applyMargins(lp, attrs)
        child.layoutParams = lp
    }

    /**
     * ConstraintLayout 제약 속성을 파싱해 ConstraintLayout.LayoutParams에 적용.
     * 지원: toTopOf/toBottomOf/toLeftOf/toRightOf/toStartOf/toEndOf,
     * horizontal/vertical_bias, dimension ratio, gone margin.
     */
    private fun applyConstraintLayoutParams(p: ConstraintLayout.LayoutParams, attrs: Map<String, String>) {
        // Top constraints
        attrs["layout_constraintTop_toTopOf"]?.let { p.topToTop = resolveConstraintId(it) }
        attrs["layout_constraintTop_toBottomOf"]?.let { p.topToBottom = resolveConstraintId(it) }
        // Bottom constraints
        attrs["layout_constraintBottom_toTopOf"]?.let { p.bottomToTop = resolveConstraintId(it) }
        attrs["layout_constraintBottom_toBottomOf"]?.let { p.bottomToBottom = resolveConstraintId(it) }
        // Left/Start constraints
        attrs["layout_constraintLeft_toLeftOf"]?.let { p.leftToLeft = resolveConstraintId(it) }
        attrs["layout_constraintLeft_toRightOf"]?.let { p.leftToRight = resolveConstraintId(it) }
        attrs["layout_constraintStart_toStartOf"]?.let { p.startToStart = resolveConstraintId(it) }
        attrs["layout_constraintStart_toEndOf"]?.let { p.startToEnd = resolveConstraintId(it) }
        // Right/End constraints
        attrs["layout_constraintRight_toLeftOf"]?.let { p.rightToLeft = resolveConstraintId(it) }
        attrs["layout_constraintRight_toRightOf"]?.let { p.rightToRight = resolveConstraintId(it) }
        attrs["layout_constraintEnd_toStartOf"]?.let { p.endToStart = resolveConstraintId(it) }
        attrs["layout_constraintEnd_toEndOf"]?.let { p.endToEnd = resolveConstraintId(it) }
        // Baseline
        attrs["layout_constraintBaseline_toBaselineOf"]?.let { p.baselineToBaseline = resolveConstraintId(it) }
        // Bias
        attrs["layout_constraintHorizontal_bias"]?.toFloatOrNull()?.let { p.horizontalBias = it }
        attrs["layout_constraintVertical_bias"]?.toFloatOrNull()?.let { p.verticalBias = it }
        // Dimension
        attrs["layout_constraintWidth"]?.let {
            when (it.lowercase()) {
                "wrap_content" -> p.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
                "match_parent", "0dp", "match_constraint" -> p.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                else -> parseDimen(it)?.toInt()?.let { p.width = it }
            }
        }
        attrs["layout_constraintHeight"]?.let {
            when (it.lowercase()) {
                "wrap_content" -> p.height = ConstraintLayout.LayoutParams.WRAP_CONTENT
                "match_parent", "0dp", "match_constraint" -> p.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT
                else -> parseDimen(it)?.toInt()?.let { p.height = it }
            }
        }
        // Gone margins
        attrs["layout_goneMarginTop"]?.toFloatOrNull()?.let { p.goneTopMargin = it.toInt() }
        attrs["layout_goneMarginBottom"]?.toFloatOrNull()?.let { p.goneBottomMargin = it.toInt() }
        attrs["layout_goneMarginLeft"]?.toFloatOrNull()?.let { p.goneLeftMargin = it.toInt() }
        attrs["layout_goneMarginRight"]?.toFloatOrNull()?.let { p.goneRightMargin = it.toInt() }
        attrs["layout_goneMarginStart"]?.toFloatOrNull()?.let { p.goneStartMargin = it.toInt() }
        attrs["layout_goneMarginEnd"]?.toFloatOrNull()?.let { p.goneEndMargin = it.toInt() }
        // Chains
        attrs["layout_constraintHorizontal_chainStyle"]?.let {
            p.horizontalChainStyle = when (it.lowercase()) {
                "spread" -> ConstraintLayout.LayoutParams.CHAIN_SPREAD
                "spread_inside" -> ConstraintLayout.LayoutParams.CHAIN_SPREAD_INSIDE
                "packed" -> ConstraintLayout.LayoutParams.CHAIN_PACKED
                else -> ConstraintLayout.LayoutParams.CHAIN_SPREAD
            }
        }
        attrs["layout_constraintVertical_chainStyle"]?.let {
            p.verticalChainStyle = when (it.lowercase()) {
                "spread" -> ConstraintLayout.LayoutParams.CHAIN_SPREAD
                "spread_inside" -> ConstraintLayout.LayoutParams.CHAIN_SPREAD_INSIDE
                "packed" -> ConstraintLayout.LayoutParams.CHAIN_PACKED
                else -> ConstraintLayout.LayoutParams.CHAIN_SPREAD
            }
        }
        // Weight
        attrs["layout_constraintHorizontal_weight"]?.toFloatOrNull()?.let { p.horizontalWeight = it }
        attrs["layout_constraintVertical_weight"]?.toFloatOrNull()?.let { p.verticalWeight = it }
        // Dimension ratio
        attrs["layout_constraintDimensionRatio"]?.let { p.dimensionRatio = it }
    }

    /**
     * Constraint 타겟 ID 문자열("@+id/foo", "@id/foo", "parent")를 뷰 ID int로 변환.
     * 실제 뷰 ID 맵이 구성된 후에는 resolveConstraintIdToViewId 로 대체해야 함.
     */
    private fun resolveConstraintId(ref: String?): Int {
        if (ref.isNullOrBlank()) return ConstraintLayout.LayoutParams.UNSET
        val s = ref.trim()
        return when {
            s.equals("parent", ignoreCase = true) -> ConstraintLayout.LayoutParams.PARENT_ID
            s.startsWith("@+id/") || s.startsWith("@id/") -> {
                val idName = s.removePrefix("@+id/").removePrefix("@id/")
                // 실제 뷰 ID는 buildViewTreeRecursive 단계에서 매핑됨.
                // 여기선 placeholder: 뷰가 생성된 후 applyConstraintLayout에서 재결정.
                idName.hashCode().toInt()
            }
            else -> ConstraintLayout.LayoutParams.UNSET
        }
    }

    private fun applyMargins(p: ViewGroup.MarginLayoutParams, attrs: Map<String, String>) {
        parseDimen(attrs["layout_margin"])?.toInt()?.let { p.setMargins(it, it, it, it) }
        parseDimen(attrs["layout_marginLeft"])?.toInt()?.let { p.leftMargin = it }
        parseDimen(attrs["layout_marginStart"])?.toInt()?.let { p.leftMargin = it }
        parseDimen(attrs["layout_marginTop"])?.toInt()?.let { p.topMargin = it }
        parseDimen(attrs["layout_marginRight"])?.toInt()?.let { p.rightMargin = it }
        parseDimen(attrs["layout_marginEnd"])?.toInt()?.let { p.rightMargin = it }
        parseDimen(attrs["layout_marginBottom"])?.toInt()?.let { p.bottomMargin = it }
        parseDimen(attrs["layout_marginHorizontal"])?.toInt()?.let {
            p.leftMargin = it; p.rightMargin = it
        }
        parseDimen(attrs["layout_marginVertical"])?.toInt()?.let {
            p.topMargin = it; p.bottomMargin = it
        }
    }

    private fun isLayoutTag(tag: String): Boolean {
        val low = tag.lowercase()
        return low.endsWith("layout") ||
                low.endsWith("scrollview") ||
                low.endsWith("horizontalscrollview") ||
                low.endsWith("nestedscrollview") ||
                low.endsWith("radiogroup") ||
                low.endsWith("tablerow") ||
                low.endsWith("cardview") ||
                low.contains("toolbar") ||
                low.contains("appbar") ||
                low.contains("tablayout") ||
                low.contains("bottomnavigation")
    }

    private fun parseSize(raw: String?, default: Int): Int {
        if (raw == null) return default
        return when (raw.lowercase()) {
            "match_parent", "fill_parent" -> ViewGroup.LayoutParams.MATCH_PARENT
            "wrap_content" -> ViewGroup.LayoutParams.WRAP_CONTENT
            else -> parseDimen(raw)?.toInt() ?: default
        }
    }

    private fun parseDimen(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        // @dimen/foo and @android:dimen/foo dereference through the project's
        // own res/values before any literal parsing happens.
        val s = resolveRef(raw)?.trim() ?: return null
        val m = resources.displayMetrics
        fun applyUnit(value: Float, unit: Int) = TypedValue.applyDimension(unit, value, m)
        return when {
            s.endsWith("dp") || s.endsWith("dip") ->
                s.dropLast(if (s.endsWith("dip")) 3 else 2).toFloatOrNull()
                    ?.let { applyUnit(it, TypedValue.COMPLEX_UNIT_DIP) }
            s.endsWith("sp") ->
                s.dropLast(2).toFloatOrNull()?.let { applyUnit(it, TypedValue.COMPLEX_UNIT_SP) }
            s.endsWith("px") -> s.dropLast(2).toFloatOrNull()
            s.endsWith("pt") ->
                s.dropLast(2).toFloatOrNull()?.let { applyUnit(it, TypedValue.COMPLEX_UNIT_PT) }
            s.endsWith("mm") ->
                s.dropLast(2).toFloatOrNull()?.let { applyUnit(it, TypedValue.COMPLEX_UNIT_MM) }
            s.endsWith("in") ->
                s.dropLast(2).toFloatOrNull()?.let { applyUnit(it, TypedValue.COMPLEX_UNIT_IN) }
            else -> s.toFloatOrNull()
        }
    }

    private fun parseColor(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        // @color/foo (including chains like @color/accent -> @color/brand)
        // resolves through the project's res/values first.
        val s = resolveRef(raw)?.trim() ?: return null
        return try {
            when {
                s.startsWith("#") -> s.toColorInt()
                s.startsWith("@android:color/") -> when (s.removePrefix("@android:color/")) {
                    "white" -> Color.WHITE
                    "black" -> Color.BLACK
                    "transparent" -> Color.TRANSPARENT
                    "red" -> Color.RED
                    "green" -> Color.GREEN
                    "blue" -> Color.BLUE
                    "darker_gray" -> Color.DKGRAY
                    "holo_blue_light" -> "#33B5E5".toColorInt()
                    "holo_red_light" -> "#FF4444".toColorInt()
                    "holo_green_light" -> "#99CC00".toColorInt()
                    "holo_orange_light" -> "#FFBB33".toColorInt()
                    else -> null
                }
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun parseGravity(raw: String?): Int {
        var g = 0
        raw?.split('|')?.forEach {
            g = g or when (it.trim().lowercase()) {
                "left" -> Gravity.LEFT
                "right" -> Gravity.RIGHT
                "start" -> Gravity.START
                "end" -> Gravity.END
                "top" -> Gravity.TOP
                "bottom" -> Gravity.BOTTOM
                "center" -> Gravity.CENTER
                "center_horizontal" -> Gravity.CENTER_HORIZONTAL
                "center_vertical" -> Gravity.CENTER_VERTICAL
                "fill" -> Gravity.FILL
                "fill_horizontal" -> Gravity.FILL_HORIZONTAL
                "fill_vertical" -> Gravity.FILL_VERTICAL
                "clip_horizontal" -> Gravity.CLIP_HORIZONTAL
                "clip_vertical" -> Gravity.CLIP_VERTICAL
                else -> 0
            }
        }
        return g
    }

    private fun unescape(raw: String): String =
        raw.replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\'", "'")

    // =========================================================================
    // LayoutLib 고화질 렌더링 (백그라운드) + Output 로그
    // =========================================================================

    private fun renderLayoutLibInBackground() {
        val file = targetFile ?: return
        addOutput("LayoutLib bridge: preparing render...")
        Thread {
            try {
                val width = resources.displayMetrics.widthPixels.coerceAtLeast(dp(320))
                val height = (resources.displayMetrics.heightPixels * 0.72f).toInt().coerceAtLeast(dp(520))
                val result = LayoutLibPreviewEngine(this).render(file, width, height)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    addOutput(buildLayoutLibOutput(result))
                    when (result.status) {
                        LayoutLibPreviewEngine.Status.RENDERED -> {
                            if (result.imageFile != null) {
                                val bmp = BitmapFactory.decodeFile(result.imageFile.absolutePath)
                                if (bmp != null) {
                                    previewImage.setImageBitmap(bmp)
                                    if (!isEditMode) {
                                        previewImage.visibility = View.VISIBLE
                                        widgetHost?.findViewById<ScrollView>(R.id.widget_scroll)?.visibility = View.GONE
                                    }
                                } else {
                                    addOutput("LayoutLib rendered, but bitmap decode failed.")
                                }
                            }
                        }
                        LayoutLibPreviewEngine.Status.UNAVAILABLE -> {
                            addOutput("· ${result.message}")
                        }
                        LayoutLibPreviewEngine.Status.FAILED -> {
                            val detail = result.detail.lineSequence()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .toList()
                                .takeLast(4)
                                .joinToString(" | ")
                            addOutput("· ${result.message}${if (detail.isNotBlank()) " · $detail" else ""}")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { addOutput("LayoutLib render exception: ${e.message}") }
            }
        }.apply {
            name = "MobileStudioLayoutLibPreview"
            isDaemon = true
            start()
        }
    }

    private fun buildLayoutLibOutput(result: LayoutLibPreviewEngine.Result): String = buildString {
        appendLine("----- LayoutLib result -----")
        appendLine("status: ${result.status}")
        appendLine("message: ${result.message}")
        result.imageFile?.let { appendLine("image: ${it.absolutePath}") }
        if (result.detail.isNotBlank()) {
            appendLine()
            appendLine(result.detail)
        }
    }

    private fun addOutput(text: String) {
        val current = outputText.text?.toString().orEmpty()
        outputText.text = buildString {
            append(current.trimEnd())
            if (isNotEmpty()) appendLine()
            append(text.trimEnd())
            appendLine()
        }
    }

    private fun clearOutput() {
        outputText.text = ""
    }

    // =========================================================================
    // Output 탭 패널 (Copy All 버튼)
    // =========================================================================

    private fun buildOutputPanel(): View {
        val scroll = ScrollView(this).apply {
            tag = "output_scroll"
            visibility = View.GONE
            setBackgroundColor("#0F1218".toColorInt())
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Copy All 버튼
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor("#191D25".toColorInt())
            addView(TextView(this@LayoutPreviewActivity).apply {
                text = "LayoutLib Output"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor("#E6EDF3".toColorInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(Button(this@LayoutPreviewActivity).apply {
                text = "Copy All"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor("#1A3A5C".toColorInt())
                setOnClickListener { copyOutput() }
            })
        })

        outputText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor("#C9D1D9".toColorInt())
            setTextIsSelectable(true)
            setPadding(dp(14), dp(10), dp(14), dp(18))
            text = "LayoutLib output will appear here.\n"
        }
        content.addView(outputText)
        scroll.addView(content)
        return scroll
    }

    private fun copyOutput() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LayoutLib Output", outputText.text))
        Toast.makeText(this, "LayoutLib output copied", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // 인터랙티브 편집 캔버스 (smartphone.png 배경)
    // =========================================================================

    private fun renderDesign() {
        val root = rootNode ?: return
        val d = resources.displayMetrics.density
        // Device-specific content area
        val (designW, designH) = when (deviceSize) {
            "tablet" -> Pair((800 * d).toInt(), (1200 * d).toInt())
            "foldable" -> Pair((1200 * d).toInt(), (800 * d).toInt())
            else -> Pair(
                if (phoneScreenWidth > 0) phoneScreenWidth.toInt() else (360 * d).toInt(),
                if (phoneScreenHeight > 0) phoneScreenHeight.toInt() else (694 * d).toInt()
            )
        }
        
        // Landscape: swap width/height
        val finalW = if (isLandscape) designH else designW
        val finalH = if (isLandscape) designW else designH
        
        computeBounds(root, Rect(0, 0, finalW, finalH))
        designCanvas.setNodes(root)
        refreshWidgetPreview()
        updateZoomLabel()
    }

    private fun computeBounds(node: XmlNode, parentRect: Rect) {
        val d = resources.displayMetrics.density
        val w = node.attrs["android:layout_width"]
        val h = node.attrs["android:layout_height"]
        val rw = if (w == "match_parent" || w == "fill_parent") parentRect.width()
                 else ((w?.replace("dp", "")?.replace("px", "")?.toFloatOrNull() ?: 100f) * d).toInt()
        val rh = if (h == "match_parent" || h == "fill_parent") parentRect.height()
                 else ((h?.replace("dp", "")?.replace("px", "")?.toFloatOrNull() ?: 50f) * d).toInt()
        node.rect.set(parentRect.left, parentRect.top, parentRect.left + rw, parentRect.top + rh)
        var offset = 0
        node.children.forEach { child ->
            computeBounds(child, Rect(node.rect.left, node.rect.top + offset, node.rect.right, node.rect.bottom))
            offset += child.rect.height()
        }
    }

    private inner class DesignCanvas(context: Context) : View(context) {
        private val MIN_ZOOM = 1.0f
        private val MAX_ZOOM = 3.0f

        private var nodes = mutableListOf<XmlNode>()
        private var rootNode: XmlNode? = null
        var onNodeLongPressed: ((XmlNode) -> Unit)? = null
        var onGeometryCommitted: ((XmlNode) -> Unit)? = null

        var panX = 0f
        var panY = 0f
        var scale = 1f

        // 터치 상태
        private var touchMode = 0          // 0 none, 1 drag, 2 resize, 3 constraint, 4 pan, 5 rotate
        private var touchNode: XmlNode? = null
        private var touchHandle = 0        // 1..8 리사이즈, 9 회전, 11-16 제약조건 핸들
        private var lastX = 0f
        private var lastY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var startRect = Rect()
        private var touchStartDesign = 0f  // 회전 각도 계산용

        private val pinchDetector = ScaleGestureDetector(context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val newScale = (scale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val k = newScale / scale
                    val fx = detector.focusX
                    val fy = detector.focusY
                    panX = fx - (fx - panX) * k
                    panY = fy - (fy - panY) * k
                    scale = newScale
                    updateZoomLabel()
                    invalidate()
                    return true
                }
            })

        private val longPressHandler = Handler(Looper.getMainLooper())
        private val longPressRunnable = Runnable {
            if (touchMode == 1 && touchNode != null && touchNode != rootNode) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onNodeLongPressed?.invoke(touchNode!!)
                touchMode = 0
            }
        }

        // 더블탭 (줌 리셋)
        private var lastTapTime = 0L
        private val tapThreshold = 300L

        private val selectPaint = Paint().apply {
            color = "#4F8EF7".toColorInt()
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        private val handleFill = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val handleStroke = Paint().apply {
            color = "#4F8EF7".toColorInt()
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        private val rotateLine = Paint().apply {
            color = "#4F8EF7".toColorInt()
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        private val textPaint = Paint().apply {
            color = "#C9D1D9".toColorInt()
            textSize = 11f
        }
        private val nodeStroke = Paint().apply {
            color = "#55FFFFFF".toColorInt()
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        private val constraintHandlePaint = Paint().apply {
            color = "#00E676".toColorInt()
            style = Paint.Style.FILL
        }
        private val constraintHandleStroke = Paint().apply {
            color = "#00E676".toColorInt()
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        private val constraintLinePaint = Paint().apply {
            color = "#00E676".toColorInt()
            strokeWidth = 1f
            style = Paint.Style.STROKE
            setPathEffect(android.graphics.DashPathEffect(floatArrayOf(6f, 6f), 0f))
        }
        private val constraintTargetPaint = Paint().apply {
            color = "#00E676".toColorInt()
            style = Paint.Style.FILL
        }

        fun setNodes(root: XmlNode?) {
            this.rootNode = root
            nodes.clear()
            root?.let { collect(it) }
            invalidate()
        }

        fun resetView() {
            scale = 1f
            panX = 0f
            panY = 0f
            updateZoomLabel()
            invalidate()
        }

        fun select(node: XmlNode?) {
            selectedNode = node
            invalidate()
        }

        private fun collect(n: XmlNode) {
            nodes.add(n)
            n.children.forEach { collect(it) }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 편집 오버레이: 프리뷰는 아래(위젯/LayoutLib)에 유지되므로 투명
            canvas.drawColor(Color.TRANSPARENT)
            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(scale, scale)

            // 전체 노드 외곽선 (가이드)
            nodes.forEach { n ->
                if (n.isRoot) return@forEach
                canvas.drawRect(n.rect, nodeStroke)
            }

            // ConstraintLayout 제약 조건선 (연결된 뷰 사이 점선)
            drawConstraintLines(canvas)

            // 선택 오버레이
            val sel = selectedNode
            if (sel != null && !sel.isRoot) {
                drawSelectionOverlay(canvas, sel)
            }
            canvas.restore()
        }

        private fun drawSelectionOverlay(canvas: Canvas, n: XmlNode) {
            val r = n.rect
            val d = resources.displayMetrics.density
            canvas.drawRect(r, selectPaint)

            // 회전 핸들 (위 중앙)
            val rotCX = r.centerX().toFloat()
            val rotCY = r.top.toFloat() - 28f * d
            canvas.drawLine(r.centerX().toFloat(), r.top.toFloat(), rotCX, rotCY, rotateLine)
            canvas.drawCircle(rotCX, rotCY, 7f * d, handleFill)
            canvas.drawCircle(rotCX, rotCY, 7f * d, handleStroke)

            // 리사이즈 핸들 (8개) - PowerPoint style squares
            val hs = 6f * d
            val handles = listOf(
                1 to (r.left.toFloat() to r.top.toFloat()),
                2 to (r.right.toFloat() to r.top.toFloat()),
                3 to (r.left.toFloat() to r.bottom.toFloat()),
                4 to (r.right.toFloat() to r.bottom.toFloat()),
                5 to (r.centerX().toFloat() to r.top.toFloat()),
                6 to (r.centerX().toFloat() to r.bottom.toFloat()),
                7 to (r.left.toFloat() to r.centerY().toFloat()),
                8 to (r.right.toFloat() to r.centerY().toFloat())
            )
            handles.forEach { (_, p) ->
                val (cx, cy) = p
                canvas.drawRect(cx - hs, cy - hs, cx + hs, cy + hs, handleFill)
                canvas.drawRect(cx - hs, cy - hs, cx + hs, cy + hs, handleStroke)
            }

            // ConstraintLayout 제약조건 핸들 (6개: top, bottom, left, right, start, end)
            // 부모가 ConstraintLayout인 경우에만 표시
            val isConstraintChild = n.parent != null &&
                n.parent?.tagName?.lowercase()?.contains("constraintlayout") == true
            if (isConstraintChild) {
                val ch = 7f * d
                val chandles = listOf(
                    11 to (r.centerX().toFloat() to r.top.toFloat()),      // top
                    12 to (r.centerX().toFloat() to r.bottom.toFloat()),    // bottom
                    13 to (r.left.toFloat() to r.centerY().toFloat()),      // left
                    14 to (r.right.toFloat() to r.centerY().toFloat()),     // right
                    15 to (r.left.toFloat() to r.centerY().toFloat()),      // start
                    16 to (r.right.toFloat() to r.centerY().toFloat())      // end
                )
                chandles.forEach { (id, p) ->
                    val (cx, cy) = p
                    canvas.drawCircle(cx, cy, ch, constraintHandlePaint)
                    canvas.drawCircle(cx, cy, ch + 1.5f, constraintHandleStroke)
                }
            }

            // 회전 각도 표시
            val rot = n.attrs["android:rotation"]?.toFloatOrNull() ?: 0f
            if (rot != 0f) {
                val tp = Paint(textPaint).apply { color = "#4F8EF7".toColorInt() }
                canvas.drawText("${Math.round(rot)}°", r.right.toFloat(), r.top.toFloat() - 40f * d, tp)
            }
            // 크기(dip) 표시
            val d2 = d.toDouble()
            val dims = "${(r.width() / d2).roundToInt()}x${(r.height() / d2).roundToInt()} dp"
            val dimPaint = Paint(textPaint).apply { textSize = 11f * d; color = "#4F8EF7".toColorInt() }
            canvas.drawText(dims, r.left.toFloat(), r.top.toFloat() - 28f * d, dimPaint)
            // 태그 표시
            canvas.drawText(n.tagName, r.left + 4f, r.bottom - 4f, textPaint)
        }

        private fun hitResizeHandle(n: XmlNode, x: Float, y: Float): Int {
            val r = n.rect
            val h = 20f
            val corners = mapOf(
                1 to Pair(r.left.toFloat(), r.top.toFloat()),
                2 to Pair(r.right.toFloat(), r.top.toFloat()),
                3 to Pair(r.left.toFloat(), r.bottom.toFloat()),
                4 to Pair(r.right.toFloat(), r.bottom.toFloat()),
                5 to Pair(r.centerX().toFloat(), r.top.toFloat()),
                6 to Pair(r.centerX().toFloat(), r.bottom.toFloat()),
                7 to Pair(r.left.toFloat(), r.centerY().toFloat()),
                8 to Pair(r.right.toFloat(), r.centerY().toFloat())
            )
            corners.forEach { (id, p) ->
                if (Math.abs(x - p.first) < h && Math.abs(y - p.second) < h) return id
            }
            // 회전 핸들
            val d = resources.displayMetrics.density
            val rotCX = r.centerX().toFloat()
            val rotCY = r.top.toFloat() - 28f * d
            if (Math.abs(x - rotCX) < h && Math.abs(y - rotCY) < h) return 9
            return 0
        }

        private fun resizeNode(n: XmlNode, rx: Int, ry: Int, handle: Int) {
            val r = n.rect
            when (handle) {
                1 -> { r.left = rx; r.top = ry }
                2 -> { r.right = rx; r.top = ry }
                3 -> { r.left = rx; r.bottom = ry }
                4 -> { r.right = rx; r.bottom = ry }
                5 -> r.top = ry
                6 -> r.bottom = ry
                7 -> r.left = rx
                8 -> r.right = rx
            }
            if (r.left >= r.right) r.right = r.left + 1
            if (r.top >= r.bottom) r.bottom = r.top + 1
        }

        private fun drawConstraintLines(canvas: Canvas) {
            val d = resources.displayMetrics.density
            nodes.forEach { n ->
                if (n.isRoot) return@forEach
                val parent = n.parent ?: return@forEach
                if (parent.tagName.lowercase().contains("constraintlayout") != true) return@forEach

                val from = n.rect
                val attrs = n.attrs

                fun drawLineTo(ref: String?, fromX: Float, fromY: Float, toX: Float, toY: Float) {
                    if (ref.isNullOrBlank()) return
                    val s = ref.trim()
                    if (s.equals("parent", ignoreCase = true)) {
                        // Draw to parent edge
                        canvas.drawLine(fromX, fromY, toX, toY, constraintLinePaint)
                        canvas.drawCircle(toX, toY, 5f * d, constraintTargetPaint)
                        return
                    }
                    if (s.startsWith("@+id/") || s.startsWith("@id/")) {
                        val idName = s.removePrefix("@+id/").removePrefix("@id/")
                        val target = nodes.firstOrNull { it.attrs["android:id"] == "@+id/$idName" ||
                            it.attrs["android:id"] == "@id/$idName" }
                        if (target != null && target !== n) {
                            val tr = target.rect
                            canvas.drawLine(fromX, fromY, tr.centerX().toFloat(), tr.centerY().toFloat(), constraintLinePaint)
                            canvas.drawCircle(tr.centerX().toFloat(), tr.centerY().toFloat(), 5f * d, constraintTargetPaint)
                        }
                    }
                }

                // Top constraint
                attrs["layout_constraintTop_toTopOf"]?.let {
                    drawLineTo(it, from.centerX().toFloat(), from.top.toFloat(),
                        from.centerX().toFloat(), 0f)
                }
                attrs["layout_constraintTop_toBottomOf"]?.let { ref ->
                    val target = nodes.firstOrNull { it.attrs["android:id"] == ref.removePrefix("@+id/").removePrefix("@id/") }
                    if (target != null) {
                        drawLineTo(ref, from.centerX().toFloat(), from.top.toFloat(),
                            target.rect.centerX().toFloat(), target.rect.bottom.toFloat())
                    }
                }
                // Bottom constraint
                attrs["layout_constraintBottom_toTopOf"]?.let { ref ->
                    val target = nodes.firstOrNull { it.attrs["android:id"] == ref.removePrefix("@+id/").removePrefix("@id/") }
                    if (target != null) {
                        drawLineTo(ref, from.centerX().toFloat(), from.bottom.toFloat(),
                            target.rect.centerX().toFloat(), target.rect.top.toFloat())
                    }
                }
                attrs["layout_constraintBottom_toBottomOf"]?.let {
                    drawLineTo(it, from.centerX().toFloat(), from.bottom.toFloat(),
                        from.centerX().toFloat(), (from.top + parent.rect.height()).toFloat())
                }
                // Left constraint
                attrs["layout_constraintLeft_toLeftOf"]?.let {
                    drawLineTo(it, from.left.toFloat(), from.centerY().toFloat(),
                        0f, from.centerY().toFloat())
                }
                attrs["layout_constraintLeft_toRightOf"]?.let { ref ->
                    val target = nodes.firstOrNull { it.attrs["android:id"] == ref.removePrefix("@+id/").removePrefix("@id/") }
                    if (target != null) {
                        drawLineTo(ref, from.left.toFloat(), from.centerY().toFloat(),
                            target.rect.right.toFloat(), target.rect.centerY().toFloat())
                    }
                }
                // Right constraint
                attrs["layout_constraintRight_toLeftOf"]?.let { ref ->
                    val target = nodes.firstOrNull { it.attrs["android:id"] == ref.removePrefix("@+id/").removePrefix("@id/") }
                    if (target != null) {
                        drawLineTo(ref, from.right.toFloat(), from.centerY().toFloat(),
                            target.rect.left.toFloat(), target.rect.centerY().toFloat())
                    }
                }
                attrs["layout_constraintRight_toRightOf"]?.let {
                    drawLineTo(it, from.right.toFloat(), from.centerY().toFloat(),
                        parent.rect.width().toFloat(), from.centerY().toFloat())
                }
            }
        }

        private fun hitConstraintHandle(n: XmlNode, x: Float, y: Float): Int {
            if (n.parent?.tagName?.lowercase()?.contains("constraintlayout") != true) return 0
            val r = n.rect
            val ch = 20f
            val handles = mapOf(
                11 to Pair(r.centerX().toFloat(), r.top.toFloat()),
                12 to Pair(r.centerX().toFloat(), r.bottom.toFloat()),
                13 to Pair(r.left.toFloat(), r.centerY().toFloat()),
                14 to Pair(r.right.toFloat(), r.centerY().toFloat()),
                15 to Pair(r.left.toFloat(), r.centerY().toFloat()),
                16 to Pair(r.right.toFloat(), r.centerY().toFloat())
            )
            handles.forEach { (id, p) ->
                if (Math.abs(x - p.first) < ch && Math.abs(y - p.second) < ch) return id
            }
            return 0
        }

        private fun hitNode(x: Float, y: Float): XmlNode? {
            for (i in nodes.indices.reversed()) {
                val n = nodes[i]
                if (n.isRoot) continue
                if (n.rect.contains(x.toInt(), y.toInt())) return n
            }
            return null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            pinchDetector.onTouchEvent(event)
            if (pinchDetector.isInProgress) return true
            val x = (event.x - panX) / scale
            val y = (event.y - panY) / scale
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = x; lastY = y
                    lastRawX = event.x; lastRawY = event.y
                    val sel = selectedNode
                    if (sel != null && !sel.isRoot) {
                        // ConstraintLayout 제약조건 핸들 확인 (최우선)
                        val ch = hitConstraintHandle(sel, x, y)
                        if (ch != 0) {
                            touchMode = 3
                            touchHandle = ch
                            touchNode = sel
                            startRect.set(sel.rect)
                            return true
                        }
                        val h = hitResizeHandle(sel, x, y)
                        if (h != 0) {
                            touchMode = if (h == 9) 5 else 2
                            touchHandle = h
                            touchNode = sel
                            startRect.set(sel.rect)
                            touchStartDesign = if (h == 9) angleOf(sel, x, y) else 0f
                            return true
                        }
                    }
                    val hit = hitNode(x, y)
                    if (hit != null) {
                        touchMode = 1
                        touchNode = hit
                        startRect.set(hit.rect)
                        if (selectedNode !== hit) {
                            selectedNode = hit
                            invalidate()
                        }
                        longPressHandler.postDelayed(longPressRunnable, 500)
                        return true
                    }
                    touchMode = 4
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - lastX
                    val dy = y - lastY
                    if (touchMode == 1 && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    when (touchMode) {
                        1 -> {
                            val n = touchNode ?: return true
                            n.rect.offset(dx.toInt(), dy.toInt())
                            
                            // Real-time view update (PowerPoint style)
                            val v = n.view
                            if (v != null) {
                                val lp = v.layoutParams as? FrameLayout.LayoutParams
                                if (lp != null) {
                                    lp.leftMargin = n.rect.left
                                    lp.topMargin = n.rect.top
                                    v.layoutParams = lp
                                }
                            }
                            
                            lastX = x; lastY = y
                            invalidate()
                            return true
                        }
                        2 -> {
                            val n = touchNode ?: return true
                            // Corrected resize logic: use current touch x/y as the new handle position
                            resizeNode(n, x.toInt(), y.toInt(), touchHandle)
                            
                            // Real-time view update (PowerPoint style)
                            val v = n.view
                            if (v != null) {
                                val lp = v.layoutParams as? FrameLayout.LayoutParams
                                if (lp != null) {
                                    lp.width = n.rect.width()
                                    lp.height = n.rect.height()
                                    lp.leftMargin = n.rect.left
                                    lp.topMargin = n.rect.top
                                    v.layoutParams = lp
                                    // 체크박스 등은 내용(텍스트/체크박스)까지 실제 스케일링
                                    // (자연 크기 대비 scaleX/Y, 왼쪽 위 고정)
                                    if (v is CompoundButton && n.naturalW > 0 && n.naturalH > 0) {
                                        v.pivotX = 0f
                                        v.pivotY = 0f
                                        v.scaleX = n.rect.width().toFloat() / n.naturalW
                                        v.scaleY = n.rect.height().toFloat() / n.naturalH
                                    }
                                }
                            }
                            
                            invalidate()
                            return true
                        }
                        5 -> {
                            val n = touchNode ?: return true
                            val deg = Math.toDegrees(
                                Math.atan2((y - n.rect.centerY()).toDouble(), (x - n.rect.centerX()).toDouble())
                            )
                            n.setAttr("android:rotation", Math.round(deg).toString())
                            invalidate()
                            return true
                        }
                        3 -> {
                            val n = touchNode ?: return true
                            // Constraint drag: 시각적 피드백만 (드래그 중 선 그리기는 invalidate로)
                            invalidate()
                            return true
                        }
                        4 -> {
                            panX += event.x - lastRawX
                            panY += event.y - lastRawY
                            lastRawX = event.x; lastRawY = event.y
                            invalidate()
                            return true
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    when (touchMode) {
                        1 -> {
                            val n = touchNode
                            if (n != null && n.rect != startRect) {
                                onGeometryCommitted?.invoke(n)
                            } else if (n != null) {
                                // 탭 → 선택 (롱프레스 메뉴가 아니었다면)
                                selectedNode = n
                            }
                        }
                        2 -> {
                            val n = touchNode
                            if (n != null && n.rect != startRect) {
                                onGeometryCommitted?.invoke(n)
                            }
                        }
                        5 -> {
                            val n = touchNode
                            if (n != null) onGeometryCommitted?.invoke(n)
                        }
                        3 -> {
                            val n = touchNode
                            if (n != null) {
                                // Constraint drag 종료: 드래그 위치 기반으로 제약조건 생성
                                commitConstraintFromDrag(n, x, y, touchHandle)
                                onGeometryCommitted?.invoke(n)
                            }
                        }
                        4 -> {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < tapThreshold) {
                                resetView()
                            }
                            lastTapTime = now
                        }
                    }
                    touchMode = 0
                    touchNode = null
                    touchHandle = 0
                    invalidate()
                    return true
                }
            }
            return true
        }

        private fun angleOf(n: XmlNode, x: Float, y: Float): Float {
            return Math.toDegrees(
                Math.atan2((y - n.rect.centerY()).toDouble(), (x - n.rect.centerX()).toDouble())
            ).toFloat()
        }

        /**
         * Constraint 핸들 드래그 종료 시 제약조건 속성을 XML 노드에 기록.
         * handle: 11=top, 12=bottom, 13=left, 14=right, 15=start, 16=end
         * (x, y)는 드래그 종료 지점 (캔버스 좌표, scale/pan 적용 후)
         */
        private fun commitConstraintFromDrag(n: XmlNode, x: Float, y: Float, handle: Int) {
            val parent = n.parent ?: return
            if (parent.tagName.lowercase().contains("constraintlayout") != true) return

            val d = resources.displayMetrics.density
            val threshold = 15f * d

            // 드래그 종료 지점이 어떤 뷰에 가까운지 찾음
            val targetNode = nodes.firstOrNull { tn ->
                tn !== n && tn.rect.contains(x.toInt(), y.toInt())
            }

            val attrKey = when (handle) {
                11 -> "layout_constraintTop_toTopOf"
                12 -> "layout_constraintBottom_toBottomOf"
                13, 15 -> "layout_constraintLeft_toLeftOf" // left/start (LTR)
                14, 16 -> "layout_constraintRight_toRightOf" // right/end (LTR)
                else -> return
            }

            if (targetNode != null) {
                val targetId = targetNode.attrs["android:id"]
                if (targetId != null && targetId.isNotEmpty()) {
                    n.setAttr(attrKey, targetId)
                }
            } else {
                // 부모에 붙임 (가장 가까운 부모 엣지)
                val pr = parent.rect
                val distTop = Math.abs(y - pr.top.toFloat())
                val distBottom = Math.abs(y - pr.bottom.toFloat())
                val distLeft = Math.abs(x - pr.left.toFloat())
                val distRight = Math.abs(x - pr.right.toFloat())
                val minDist = minOf(distTop, distBottom, distLeft, distRight)

                if (minDist <= threshold * 2) {
                    when {
                        handle == 11 && distTop == minDist -> n.setAttr(attrKey, "parent")
                        handle == 12 && distBottom == minDist -> n.setAttr(attrKey, "parent")
                        handle == 13 && distLeft == minDist -> n.setAttr(attrKey, "parent")
                        handle == 14 && distRight == minDist -> n.setAttr(attrKey, "parent")
                        handle == 15 && distLeft == minDist -> n.setAttr(attrKey, "parent")
                        handle == 16 && distRight == minDist -> n.setAttr(attrKey, "parent")
                    }
                }
            }
        }
    }

    // =========================================================================
    // 위젯 팔레트 (~30개, 미리보기 셀)
    // =========================================================================

    private data class PaletteItem(val tag: String, val label: String)

    private val paletteItems = listOf(
        PaletteItem("TextView", "텍스트"),
        PaletteItem("Button", "버튼"),
        PaletteItem("EditText", "입력"),
        PaletteItem("ImageView", "이미지"),
        PaletteItem("ImageButton", "아이콘버튼"),
        PaletteItem("CheckBox", "체크"),
        PaletteItem("RadioButton", "라디오"),
        PaletteItem("Switch", "스위치"),
        PaletteItem("ToggleButton", "토글"),
        PaletteItem("ProgressBar", "로딩"),
        PaletteItem("SeekBar", "슬라이더"),
        PaletteItem("RatingBar", "별점"),
        PaletteItem("Spinner", "드롭다운"),
        PaletteItem("Space", "여백"),
        PaletteItem("View", "구분선"),
        PaletteItem("LinearLayout", "선형배치"),
        PaletteItem("FrameLayout", "프레임"),
        PaletteItem("RelativeLayout", "상대배치"),
        PaletteItem("ScrollView", "스크롤"),
        PaletteItem("HorizontalScrollView", "가로스크롤"),
        PaletteItem("CardView", "카드"),
        PaletteItem("RadioGroup", "라디오그룹"),
        PaletteItem("TableLayout", "테이블"),
        PaletteItem("TableRow", "테이블행"),
        PaletteItem("GridLayout", "그리드"),
        PaletteItem("Toolbar", "툴바"),
        PaletteItem("TabLayout", "탭바"),
        PaletteItem("BottomNavigationView", "하단내비"),
        PaletteItem("RecyclerView", "리스트"),
        PaletteItem("ListView", "리스트뷰"),
        PaletteItem("GridView", "그리드뷰"),
        PaletteItem("WebView", "웹뷰"),
        PaletteItem("SearchView", "검색"),
        PaletteItem("Chronometer", "스톱워치"),
        PaletteItem("androidx.constraintlayout.widget.ConstraintLayout", "제약배치")
    )

    private fun buildWidgetPalette(): View {
        val isKo = AppLanguage.isKorean(this)
        val scroll = HorizontalScrollView(this).apply {
            setBackgroundColor("#161A20".toColorInt())
            setHorizontalScrollBarEnabled(false)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        for (item in paletteItems) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(4), dp(6), dp(4))
                setBackgroundColor("#232936".toColorInt())
                setOnClickListener { addWidgetFromPalette(item) }
            }
            val preview = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(64), dp(48))
                setBackgroundColor("#0F1218".toColorInt())
            }
            val mini = buildWidgetPreview(item.tag)
            preview.addView(mini, FrameLayout.LayoutParams(dp(56), dp(40), Gravity.CENTER))
            cell.addView(preview)
            cell.addView(TextView(this).apply {
                text = item.label
                textSize = 9f
                setTextColor("#8B949E".toColorInt())
                maxLines = 1
            })
            row.addView(cell, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(4) })
        }
        scroll.addView(row)
        return scroll
    }

    private fun buildWidgetPreview(tag: String): View {
        return when (tag) {
            "TextView" -> TextView(this).apply { text = "Text"; textSize = 8f }
            "Button" -> Button(this).apply { text = "Btn"; textSize = 7f; setPadding(dp(2), 0, dp(2), 0) }
            "EditText" -> EditText(this).apply { hint = ".."; textSize = 7f }
            "ImageView" -> ImageView(this).apply { setBackgroundColor("#2D3139".toColorInt()) }
            "ImageButton" -> ImageButton(this).apply { setBackgroundColor("#2D3139".toColorInt()) }
            "CheckBox" -> CheckBox(this).apply { text = "C"; textSize = 7f }
            "RadioButton" -> RadioButton(this).apply { text = "R"; textSize = 7f }
            "Switch" -> Switch(this)
            "ToggleButton" -> ToggleButton(this).apply { textOn = ""; textOff = ""; textSize = 6f }
            "ProgressBar" -> ProgressBar(this)
            "SeekBar" -> SeekBar(this)
            "RatingBar" -> RatingBar(this).apply { numStars = 5; stepSize = 1f }
            "Spinner" -> Spinner(this)
            "Space" -> Space(this)
            "View" -> View(this).apply { setBackgroundColor("#BDBDBD".toColorInt()) }
            "SearchView" -> SearchView(this)
            "Chronometer" -> Chronometer(this).apply { textSize = 7f }
            "LinearLayout" -> LinearLayout(this).apply { setBackgroundColor("#3E4451".toColorInt()) }
            "FrameLayout" -> FrameLayout(this).apply { setBackgroundColor("#3E4451".toColorInt()) }
            "RelativeLayout" -> FrameLayout(this).apply { setBackgroundColor("#3E4451".toColorInt()) }
            "ScrollView" -> ScrollView(this).apply { setBackgroundColor("#3E4451".toColorInt()) }
            "HorizontalScrollView" -> HorizontalScrollView(this).apply { setBackgroundColor("#3E4451".toColorInt()) }
            "CardView" -> FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                elevation = dp(1f)
            }
            "androidx.constraintlayout.widget.ConstraintLayout" -> ConstraintLayout(this).apply {
                setBackgroundColor("#3E4451".toColorInt())
            }
            "RadioGroup" -> RadioGroup(this)
            "TableLayout" -> LinearLayout(this).apply { setBackgroundColor("#3E4451".toColorInt()) }
            "TableRow" -> LinearLayout(this).apply { setBackgroundColor("#3E4451".toColorInt()) }
            "GridLayout" -> LinearLayout(this).apply { setBackgroundColor("#3E4451".toColorInt()) }
            "Toolbar" -> LinearLayout(this).apply { setBackgroundColor(COLOR_PRIMARY.toColorInt()) }
            "TabLayout" -> LinearLayout(this).apply { setBackgroundColor(COLOR_PRIMARY.toColorInt()) }
            "BottomNavigationView" -> LinearLayout(this).apply { setBackgroundColor(Color.WHITE) }
            "RecyclerView" -> placeholderBox("List", "#F5F5F5")
            "ListView" -> placeholderBox("List", "#F5F5F5")
            "GridView" -> placeholderBox("Grid", "#F5F5F5")
            "WebView" -> placeholderBox("Web", "#FFE0B2")
            else -> TextView(this).apply { text = tag; textSize = 7f }
        }
    }

    private fun addWidgetFromPalette(item: PaletteItem) {
        val root = rootNode ?: return
        pushUndo()
        val node = XmlNode(item.tag)
        node.setAttr("android:layout_width", "wrap_content")
        node.setAttr("android:layout_height", "wrap_content")
        if (item.tag == "Button") node.setAttr("android:text", "Button")
        if (item.tag == "TextView") node.setAttr("android:text", "Text")
        if (item.tag == "EditText") node.setAttr("android:hint", "Input")
        if (item.tag == "CheckBox" || item.tag == "RadioButton") node.setAttr("android:text", item.tag)
        node.parent = root
        root.children.add(node)
        selectedNode = node
        commitChange()
        designCanvas.select(node)
        val isKo = AppLanguage.isKorean(this)
        Toast.makeText(this, "${item.label} " + if (isKo) "추가됨" else "added", Toast.LENGTH_SHORT).show()
    }

    // =========================================================================
    // XML 노드 파싱 / 저장
    // =========================================================================

    private fun loadXmlToNodes() {
        val xml = targetFile?.readText() ?: ""
        rootNode = parseXmlToNodes(xml)
    }

    private fun parseXmlToNodes(xml: String): XmlNode? {
        var root: XmlNode? = null
        try {
            val parser = Xml.newPullParser()
            parser.setInput(xml.reader())
            val stack = java.util.ArrayDeque<XmlNode>()
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val node = XmlNode(parser.name)
                    for (i in 0 until parser.attributeCount) {
                        val prefix = parser.getAttributePrefix(i)
                        val key = if (prefix.isNullOrEmpty()) parser.getAttributeName(i) else "$prefix:${parser.getAttributeName(i)}"
                        node.attrs[key] = parser.getAttributeValue(i)
                    }
                    val parent = stack.peekLast()
                    if (parent != null) { node.parent = parent; parent.children.add(node) }
                    else if (root == null) root = node
                    stack.addLast(node)
                } else if (event == XmlPullParser.END_TAG) {
                    stack.removeLast()
                }
                event = parser.next()
            }
        } catch (e: Exception) { Log.e(TAG, "Parse error", e) }
        return root
    }

    private fun saveXml() {
        val node = rootNode
        if (node == null) {
            Log.e(TAG, "saveXml failed: rootNode is null")
            val isKo = AppLanguage.isKorean(this)
            ErrorLogDialog.show(
                this,
                if (isKo) "저장 실패" else "Save Failed",
                "Save failed: layout not loaded"
            )
            return
        }
        
        try {
            val xml = buildXmlFromNodes(node, 0)
            if (xml.isBlank()) {
                Log.e(TAG, "saveXml failed: generated XML is blank")
                return
            }
            
            suppressFileObserver = true
            targetFile?.writeText(xml)
            suppressFileObserver = false
            
            val isKo = AppLanguage.isKorean(this)
            Toast.makeText(this, if (isKo) "저장됨: ${targetFile?.name}" else "Saved: ${targetFile?.name}", Toast.LENGTH_SHORT).show()
            Log.i(TAG, "XML saved successfully to ${targetFile?.absolutePath}")
            
            // 저장 후 고화질 미리보기 갱신
            renderLayoutLibInBackground()
        } catch (e: Exception) {
            Log.e(TAG, "saveXml exception", e)
            val isKo = AppLanguage.isKorean(this)
            ErrorLogDialog.show(
                this,
                if (isKo) "저장 오류" else "Save Error",
                "Save error: ${e.message}"
            )
        }
    }

    private fun autoSave() {
        val node = rootNode ?: return
        val xml = buildXmlFromNodes(node, 0)
        if (xml.isNotBlank()) {
            suppressFileObserver = true
            targetFile?.writeText(xml)
            suppressFileObserver = false
            Log.i(TAG, "autoSave success")
        }
    }

    private fun buildXmlFromNodes(node: XmlNode, depth: Int): String {
        val sb = StringBuilder()
        val indent = "    ".repeat(depth)
        sb.append("$indent<${node.tagName}")
        
        if (depth == 0) {
            // Ensure essential namespaces are present
            if (node.attrs.none { it.key == "xmlns:android" }) {
                sb.append("\n        xmlns:android=\"http://schemas.android.com/apk/res/android\"")
            }
            if (node.attrs.none { it.key == "xmlns:app" }) {
                sb.append("\n        xmlns:app=\"http://schemas.android.com/apk/res-auto\"")
            }
            // Add all existing xmlns attributes
            node.attrs.filter { it.key.startsWith("xmlns:") }.forEach { (k, v) ->
                sb.append("\n        $k=\"$v\"")
            }
        }
        
        // Add other attributes
        node.attrs.filter { !it.key.startsWith("xmlns:") }.forEach { (k, v) ->
            sb.append("\n$indent    $k=\"$v\"")
        }
        
        if (node.children.isEmpty()) {
            sb.append(" />\n")
        } else {
            sb.append(">\n")
            node.children.forEach { sb.append(buildXmlFromNodes(it, depth + 1)) }
            sb.append("$indent</${node.tagName}>\n")
        }
        return sb.toString()
    }

    private fun showNodeMenu(node: XmlNode) {
        val isKo = AppLanguage.isKorean(this)
        val options = arrayOf("Duplicate", "Delete", "Properties", "Convert to...", "Extract to Resource...")
        AlertDialog.Builder(this)
            .setTitle("<${node.tagName}> ${node.id ?: ""}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        pushUndo()
                        duplicateNode(node)
                        commitChange()
                    }
                    1 -> {
                        pushUndo()
                        deleteNode(node)
                        commitChange()
                    }
                    2 -> showNodePropertyEditor(node)
                    3 -> showConvertNodeDialog(node)
                    4 -> showExtractToResourceDialog(node)
                }
            }.show()
    }

    /**
     * Extract to Resource (Android Studio 리팰토링).
     * 선택된 노드의 하드코딩된 속성값(text, color, dimen 등)을 res/values/strings.xml
     * 또는 colors.xml에 추출하고, 속성을 @string/name 또는 @color/name 참조로 바꾼다.
     */
    private fun showExtractToResourceDialog(node: XmlNode) {
        val isKo = AppLanguage.isKorean(this)

        // 추출 대상 속성 후보 (문자열, 색상, 디멘 값)
        val extractable = mutableListOf<Pair<String, String>>()
        val resourceKeys = listOf(
            "android:text" to "string",
            "android:hint" to "string",
            "android:contentDescription" to "string",
            "android:textColor" to "color",
            "android:background" to "color",
            "android:backgroundTint" to "color",
            "android:tint" to "color",
            "android:layout_width" to "dimen",
            "android:layout_height" to "dimen",
            "android:layout_margin" to "dimen",
            "android:layout_marginLeft" to "dimen",
            "android:layout_marginTop" to "dimen",
            "android:layout_marginRight" to "dimen",
            "android:layout_marginBottom" to "dimen",
            "android:padding" to "dimen",
            "android:paddingLeft" to "dimen",
            "android:paddingTop" to "dimen",
            "android:paddingRight" to "dimen",
            "android:paddingBottom" to "dimen",
            "android:textSize" to "dimen"
        )
        for ((attr, resType) in resourceKeys) {
            node.attrs[attr]?.let { value ->
                if (value.isNotEmpty() && !value.startsWith("@")) {
                    extractable.add(attr to value)
                }
            }
        }

        if (extractable.isEmpty()) {
            Toast.makeText(this, if (isKo) "추출 가능한 속성이 없습니다." else "No extractable attributes.",
                Toast.LENGTH_SHORT).show()
            return
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        body.addView(TextView(this).apply {
            text = if (isKo) "추출할 속성 선택" else "Select Attribute to Extract"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })

        val selectedAttr = mutableListOf<String>(extractable.first().first)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        scroll.addView(body)

        for ((attr, value) in extractable) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            val rb = android.widget.RadioButton(this).apply {
                text = "$attr = \"$value\""
                textSize = 13f
                setTextColor(Color.WHITE)
                if (attr == selectedAttr.first()) isChecked = true
                setOnClickListener { selectedAttr[0] = attr }
            }
            row.addView(rb)
            body.addView(row)
        }

        val nameInput = EditText(this).apply {
            hint = if (isKo) "리소스 이름 (예: app_name)" else "Resource name (e.g. app_name)"
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor("#8B949E".toColorInt())
            setBackgroundColor("#232936".toColorInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        body.addView(nameInput)

        AlertDialog.Builder(this)
            .setView(scroll)
            .setTitle(if (isKo) "리소스로 추출" else "Extract to Resource")
            .setPositiveButton(if (isKo) "추출" else "Extract") { _, _ ->
                val attrName = selectedAttr.first()
                val resName = nameInput.text.toString().trim()
                if (resName.isEmpty()) {
                    Toast.makeText(this, if (isKo) "이름을 입력하세요." else "Enter a name.",
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                pushUndo()
                val oldValue = node.attrs[attrName] ?: ""
                val resType = resourceKeys.firstOrNull { it.first == attrName }?.second ?: "string"
                val resFile = when (resType) {
                    "color" -> "values/colors.xml"
                    "dimen" -> "values/dimens.xml"
                    else -> "values/strings.xml"
                }
                // 프로젝트의 res 디렉터리 찾기
                val resDir = findResDir(targetFile)
                if (resDir == null) {
                    Toast.makeText(this, if (isKo) "res 디렉터리를 찾을 수 없습니다." else "res directory not found.",
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val targetResFile = File(resDir, resFile)
                try {
                    // 기존 리소스 파일 읽기
                    var existingXml = ""
                    if (targetResFile.exists()) existingXml = targetResFile.readText()

                    // 새 리소스 항목 추가 (중복 방지)
                    val resourceEntry = when (resType) {
                        "string" -> """    <string name="$resName">$oldValue</string>"""
                        "color" -> """    <color name="$resName">$oldValue</color>"""
                        else -> """    <dimen name="$resName">$oldValue</dimen>"""
                    }

                    val newXml = if (existingXml.isBlank() || !existingXml.contains("<resources>")) {
                        """<?xml version="1.0" encoding="utf-8"?>
<resources>
$resourceEntry
</resources>"""
                    } else {
                        // </resources> 닫는 태그 앞에 삽입
                        existingXml.replace("</resources>", "$resourceEntry\n</resources>")
                    }

                    targetResFile.parentFile.mkdirs()
                    targetResFile.writeText(newXml)

                    // 노드 속성을 @type/name 참조로 변경
                    node.setAttr(attrName, "@$resType/$resName")
                    commitChange()

                    val isKo2 = AppLanguage.isKorean(this)
                    Toast.makeText(this,
                        if (isKo2) "추출됨: @$resType/$resName" else "Extracted: @$resType/$resName",
                        Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Extract to resource error", e)
                    Toast.makeText(this, if (isKo) "추출 실패: ${e.message}" else "Extract failed: ${e.message}",
                        Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(if (isKo) "취소" else "Cancel", null)
            .show()
            .window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 16f
                }
            )
    }

    /**
     * 대상 파일의 프로젝트 res 디렉터리를 탐색.
     */
    private fun findResDir(file: File?): File? {
        file ?: return null
        var dir: File? = file.parentFile
        var up = 0
        while (dir != null && up < 6) {
            val res = File(dir, "src/main/res")
            if (res.isDirectory) return res
            dir = dir.parentFile
            up++
        }
        return null
    }

    /**
     * Visual property inspector for a single XmlNode. Lets users edit attributes
     * without touching XML source — Android Studio-style property panel.
     */
    private fun showNodePropertyEditor(node: XmlNode) {
        val isKo = AppLanguage.isKorean(this)
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        // Header
        body.addView(TextView(this).apply {
            text = if (isKo) "속성 편집기" else "Property Inspector"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        })
        body.addView(TextView(this).apply {
            text = "<${node.tagName}>${node.id?.let { " android:id=\"$it\"" } ?: ""}"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor("#4F8EF7".toColorInt())
            setPadding(0, 0, 0, dp(16))
        })

        // Common attributes with labels, hints
        data class AttrDef(val attr: String, val label: String, val hint: String)

        val commonAttrs = listOf(
            AttrDef("android:id", "ID", "@+id/..."),
            AttrDef("android:layout_width", "Width", "wrap_content / match_parent / dp"),
            AttrDef("android:layout_height", "Height", "wrap_content / match_parent / dp"),
            AttrDef("android:layout_margin", "Margin", "dp value"),
            AttrDef("android:layout_marginLeft", "Margin Left", "dp"),
            AttrDef("android:layout_marginTop", "Margin Top", "dp"),
            AttrDef("android:layout_marginRight", "Margin Right", "dp"),
            AttrDef("android:layout_marginBottom", "Margin Bottom", "dp"),
            AttrDef("android:padding", "Padding", "dp value"),
            AttrDef("android:paddingLeft", "Padding Left", "dp"),
            AttrDef("android:paddingTop", "Padding Top", "dp"),
            AttrDef("android:paddingRight", "Padding Right", "dp"),
            AttrDef("android:paddingBottom", "Padding Bottom", "dp"),
            AttrDef("android:text", "Text", "Label text"),
            AttrDef("android:hint", "Hint", "Placeholder"),
            AttrDef("android:textSize", "Text Size", "sp value"),
            AttrDef("android:textColor", "Text Color", "#RRGGBB"),
            AttrDef("android:background", "Background", "#RRGGBB or drawable"),
            AttrDef("android:backgroundTint", "Tint", "#RRGGBB"),
            AttrDef("android:gravity", "Gravity", "center | start | end | top | bottom"),
            AttrDef("android:visibility", "Visibility", "visible | gone | invisible"),
            AttrDef("android:enabled", "Enabled", "true | false"),
            AttrDef("android:clickable", "Clickable", "true | false"),
        )

        val editors = mutableMapOf<String, EditText>()

        for (def in commonAttrs) {
            val attr = def.attr
            val label = def.label
            val hint = def.hint
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(8))
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor("#C9D1D9".toColorInt())
                layoutParams = LinearLayout.LayoutParams(dp(120), -2)
            })
            val current = node.attrs[attr] ?: ""
            val input = EditText(this).apply {
                setText(current)
                setHint(hint)
                textSize = 12f
                setTextColor(Color.WHITE)
                setHintTextColor("#8B949E".toColorInt())
                setBackgroundColor("#232936".toColorInt())
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            editors[attr] = input
            row.addView(input)
            body.addView(row)
        }

        // Custom attribute adder
        body.addView(TextView(this).apply {
            text = if (isKo) "사용자 속성 추가" else "Add Custom Attribute"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(8))
        })
        val customKey = EditText(this).apply {
            hint = "attribute name (e.g. android:src)"
            textSize = 12f
            setSingleLine(true)
            setBackgroundColor("#232936".toColorInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val customVal = EditText(this).apply {
            hint = "value"
            textSize = 12f
            setSingleLine(true)
            setBackgroundColor("#232936".toColorInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }
        addRow.addView(customKey)
        addRow.addView(customVal)
        body.addView(addRow)
        body.addView(Button(this).apply {
            text = if (isKo) "속성 추가" else "Add Attribute"
            textSize = 12f
            setBackgroundColor("#1A5C3A".toColorInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }
            setOnClickListener {
                val k = customKey.text.toString().trim()
                val v = customVal.text.toString().trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    node.setAttr(k, v)
                    customKey.text.clear()
                    customVal.text.clear()
                    commitChange()
                    val isKo2 = AppLanguage.isKorean(this@LayoutPreviewActivity)
                    Toast.makeText(this@LayoutPreviewActivity, "$k added", Toast.LENGTH_SHORT).show()
                }
            }
        })

        // Apply button
        body.addView(Button(this).apply {
            text = if (isKo) "적용" else "Apply Changes"
            textSize = 14f
            setBackgroundColor("#4F8EF7".toColorInt())
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                pushUndo()
                for ((attr, input) in editors) {
                    val v = input.text.toString().trim()
                    if (v.isEmpty()) node.attrs.remove(attr) else node.setAttr(attr, v)
                }
                commitChange()
                Toast.makeText(this@LayoutPreviewActivity, if (isKo) "속성 적용됨" else "Properties applied", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })

        scroll.addView(body)
        AlertDialog.Builder(this)
            .setTitle(node.tagName)
            .setView(scroll)
            .setPositiveButton(if (isKo) "닫기" else "Close", null)
            .show()
            .window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 16f
                }
            )
    }

    /**
     * Convert the selected node to a different tag type (keeps children + most attrs).
     */
    private fun showConvertNodeDialog(node: XmlNode) {
        val isKo = AppLanguage.isKorean(this)
        val input = EditText(this).apply {
            hint = "New tag (e.g. ConstraintLayout, CardView)"
            setSingleLine(true)
            setText(node.tagName)
            setTextColor(Color.WHITE)
            setHintTextColor("#8B949E".toColorInt())
            setBackgroundColor("#232936".toColorInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        AlertDialog.Builder(this)
            .setTitle(if (isKo) "노드 타입 변환" else "Convert Node Type")
            .setMessage(if (isKo) "자식 노드와 대부분의 속성을 유지합니다." else "Keeps children and most attributes.")
            .setView(input)
            .setPositiveButton(if (isKo) "변환" else "Convert") { _, _ ->
                val newTag = input.text.toString().trim()
                if (newTag.isNotEmpty() && newTag != node.tagName) {
                    pushUndo()
                    val oldTag = node.tagName
                    val parent = node.parent
                    val idx = parent?.children?.indexOf(node) ?: -1
                    if (idx >= 0) {
                        val newNode = XmlNode(newTag).apply {
                            attrs.putAll(node.attrs)
                            this.parent = node.parent
                            this.children.addAll(node.children)
                        }
                        newNode.children.forEach { it.parent = newNode }
                        parent?.children?.set(idx, newNode)
                        selectedNode = newNode
                        commitChange()
                        designCanvas.select(newNode)
                    }
                    val isKo2 = AppLanguage.isKorean(this@LayoutPreviewActivity)
                    Toast.makeText(this@LayoutPreviewActivity, "$oldTag → $newTag", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(if (isKo) "취소" else "Cancel", null)
            .show()
    }

    // =========================================================================
    // 템플릿 갤러리 (XML 몰라도 쉽게 레이아웃 생성)
    // =========================================================================

    private data class LayoutTemplate(
        val name: String,
        val desc: String,
        val xml: String
    )

    private val layoutTemplates = listOf(
        LayoutTemplate(
            "Empty Activity",
            "Simple ConstraintLayout with centered text",
            """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <TextView
        android:id="@+id/textHello"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Hello, World!"
        android:textSize="18sp"
        android:textStyle="bold"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>"""
        ),
        LayoutTemplate(
            "Login Form",
            "Card with email/password fields and login button",
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="24dp"
    android:gravity="center">

    <com.google.android.material.card.MaterialCardView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        style="@style/Widget.MaterialComponents.CardView">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="24dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Sign In"
                android:textSize="24sp"
                android:textStyle="bold"
                android:layout_gravity="center"
                android:layout_marginBottom="24dp" />

            <EditText
                android:id="@+id/inputEmail"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="Email"
                android:inputType="textEmailAddress"
                android:layout_marginBottom="16dp" />

            <EditText
                android:id="@+id/inputPassword"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:hint="Password"
                android:inputType="textPassword"
                android:layout_marginBottom="24dp" />

            <Button
                android:id="@+id/btnLogin"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="Login" />

        </LinearLayout>
    </com.google.android.material.card.MaterialCardView>

</LinearLayout>"""
        ),
        LayoutTemplate(
            "Bottom Navigation",
            "BottomNavigationView with 3 destinations",
            """<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <FrameLayout
        android:id="@+id/fragmentContainer"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:layout_above="@id/bottomNavigation" />

    <com.google.android.material.bottomnavigation.BottomNavigationView
        android:id="@+id/bottomNavigation"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_alignParentBottom="true"
        app:menu="@menu/bottom_nav_menu" />

</RelativeLayout>"""
        ),
        LayoutTemplate(
            "List Screen",
            "RecyclerView with header and swipe-to-refresh",
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <TextView
        android:id="@+id/headerTitle"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="My Items"
        android:textSize="22sp"
        android:textStyle="bold"
        android:padding="16dp" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/itemList"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:padding="8dp" />

</LinearLayout>"""
        ),
        LayoutTemplate(
            "Profile Page",
            "Avatar, name, stats row, and action buttons",
            """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center_horizontal"
    android:padding="24dp">

    <ImageView
        android:id="@+id/avatar"
        android:layout_width="100dp"
        android:layout_height="100dp"
        android:background="#E0E0E0"
        android:contentDescription="Profile avatar" />

    <TextView
        android:id="@+id/displayName"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="John Doe"
        android:textSize="20sp"
        android:textStyle="bold"
        android:layout_marginTop="16dp" />

    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:layout_marginTop="24dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="128 Posts"
            android:textSize="14sp"
            android:padding="8dp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="1.2K Followers"
            android:textSize="14sp"
            android:padding="8dp" />

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="456 Following"
            android:textSize="14sp"
            android:padding="8dp" />
    </LinearLayout>

    <Button
        android:id="@+id/btnEdit"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Edit Profile"
        android:layout_marginTop="24dp" />

</LinearLayout>"""
        )
    )

    private fun showTemplateGallery() {
        val isKo = AppLanguage.isKorean(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }

        root.addView(TextView(this).apply {
            text = if (isKo) "템플릿 선택" else "Choose Template"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(12))
        })

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }
        scroll.addView(root)

        for (tpl in layoutTemplates) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setBackgroundColor("#232936".toColorInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = dp(10)
                }
                setOnClickListener {
                    pushUndo()
                    suppressFileObserver = true
                    targetFile?.writeText(tpl.xml)
                    suppressFileObserver = false
                    loadXmlToNodes()
                    renderDesign()
                    refreshWidgetPreview()
                    Toast.makeText(this@LayoutPreviewActivity,
                        if (isKo) "템플릿 적용: ${tpl.name}" else "Applied: ${tpl.name}",
                        Toast.LENGTH_SHORT).show()
                }
            }
            card.addView(TextView(this).apply {
                text = tpl.name
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            })
            card.addView(TextView(this).apply {
                text = tpl.desc
                textSize = 12f
                setTextColor("#8B949E".toColorInt())
                setPadding(0, dp(4), 0, 0)
            })
            scroll.addView(card)
        }

        root.addView(scroll)

        AlertDialog.Builder(this)
            .setView(scroll)
            .setPositiveButton(if (isKo) "닫기" else "Close", null)
            .show()
            .window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 16f
                }
            )
    }

    private fun duplicateNode(node: XmlNode) {
        val parent = node.parent ?: return
        val newNode = XmlNode(node.tagName).apply {
            attrs.putAll(node.attrs)
            node.id?.let { setAttr("android:id", "@+id/${it}_copy") }
        }
        newNode.parent = parent
        parent.children.add(newNode)
    }

    private fun deleteNode(node: XmlNode) {
        if (node.isRoot) return
        node.parent?.children?.remove(node)
    }

    // =========================================================================
    // 지오메트리 커밋 / 자동 저장 / 새로고침
    // =========================================================================

    private fun commitNodeGeometry(node: XmlNode) {
        pushUndo()
        val d = resources.displayMetrics.density
        
        val wDp = (node.rect.width() / d).toInt().coerceAtLeast(8)
        val hDp = (node.rect.height() / d).toInt().coerceAtLeast(8)
        
        val parent = node.parent
        if (parent != null) {
            val leftDp = ((node.rect.left - parent.rect.left) / d).toInt()
            val topDp = ((node.rect.top - parent.rect.top) / d).toInt()
            node.setAttr("android:layout_marginLeft", "${leftDp}dp")
            node.setAttr("android:layout_marginTop", "${topDp}dp")
        }
        
        node.setAttr("android:layout_width", "${wDp}dp")
        node.setAttr("android:layout_height", "${hDp}dp")

        // 체크박스 등: 리사이즈 시 scaleX/Y를 XML로 영속화 (실기기 프리뷰에서도 동일 크기 재현)
        if (node.view is CompoundButton && node.naturalW > 0 && node.naturalH > 0) {
            val scaleX = node.rect.width().toFloat() / node.naturalW
            val scaleY = node.rect.height().toFloat() / node.naturalH
            node.setAttr(
                "android:scaleX",
                if (Math.abs(scaleX - 1f) < 0.02f) null else String.format("%.2f", scaleX)
            )
            node.setAttr(
                "android:scaleY",
                if (Math.abs(scaleY - 1f) < 0.02f) null else String.format("%.2f", scaleY)
            )
        }
        
        autoSave()
        // No need for loadXmlToNodes here to avoid flicker and losing selection
        refreshWidgetPreview()
    }

    private fun commitChange() {
        autoSave()
        loadXmlToNodes()
        renderDesign()
        refreshWidgetPreview()
    }

    private fun refreshWidgetPreview() {
        val host = widgetHost ?: return
        val root = rootNode ?: return
        try {
            host.removeAllViews()
            val content = buildPreviewFromNodes(root)
            host.addView(content, FrameLayout.LayoutParams(-1, -1))
            renderLayoutLibInBackground()
        } catch (e: Exception) {
            Log.e(TAG, "refreshWidgetPreview error", e)
        }
    }

    private fun buildPreviewFromNodes(node: XmlNode): View {
        val host = FrameLayout(this)
        host.setBackgroundColor(if (isDarkTheme) "#0F1218".toColorInt() else Color.WHITE)

        // 1단계: 전체 트리 순회하며 뷰 생성과 ID 맵 구축
        val viewIdMap = LinkedHashMap<String, Int>()
        buildViewTreeWithIdMap(node, host, viewIdMap)

        // 2단계: ConstraintLayout 제약 대상을 실제 뷰 ID로 재결정
        resolveConstraintViewIds(node, viewIdMap)

        return host
    }

    /**
     * 뷰 트리를 생성하면서 각 노드의 android:id를 뷰 ID에 매핑한다.
     */
    private fun buildViewTreeWithIdMap(
        node: XmlNode,
        parent: ViewGroup,
        viewIdMap: MutableMap<String, Int>
    ) {
        val v = buildWidgetView(node.tagName, node.attrs) ?: return
        node.view = v

        // 뷰 ID 할당: android:id가 있으면 이름 기반, 없으면 자동 생성
        val idName = node.attrs["android:id"]?.removePrefix("@+id/")?.removePrefix("@id/")
        if (idName.isNullOrBlank()) {
            v.id = View.generateViewId()
        } else {
            viewIdMap[idName]?.let { v.id = it } ?: run {
                val generatedId = View.generateViewId()
                v.id = generatedId
                viewIdMap[idName] = generatedId
            }
        }

        // 체크박스 등 자연 크기 측정
        if (v is CompoundButton) {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            node.naturalW = v.measuredWidth
            node.naturalH = v.measuredHeight
        }

        val lp: ViewGroup.LayoutParams = when (parent) {
            is ConstraintLayout -> {
                ConstraintLayout.LayoutParams(
                    node.rect.width().coerceAtLeast(0),
                    node.rect.height().coerceAtLeast(0)
                ).also { p ->
                    p.leftMargin = node.rect.left
                    p.topMargin = node.rect.top
                    applyConstraintLayoutParams(p, node.attrs)
                }
            }
            else -> {
                FrameLayout.LayoutParams(node.rect.width(), node.rect.height()).also { p ->
                    p.leftMargin = node.rect.left
                    p.topMargin = node.rect.top
                }
            }
        }
        parent.addView(v, lp)

        node.children.forEach { child ->
            buildViewTreeWithIdMap(child, parent, viewIdMap)
        }
    }

    /**
     * ConstraintLayout 제약 대상 ID를 실제 뷰 ID로 재결정.
     * buildViewTreeWithIdMap가 실행된 후 모든 뷰가 생성되었으므로,
     * XML 노드의 android:id와 뷰 객체를 매핑해 constraint 타겟을 연결한다.
     */
    private fun resolveConstraintViewIds(node: XmlNode, viewIdMap: Map<String, Int>) {
        val v = node.view ?: return
        val lp = v.layoutParams
        if (lp is ConstraintLayout.LayoutParams) {
            val attrs = node.attrs
            fun resolve(ref: String?): Int {
                if (ref.isNullOrBlank()) return ConstraintLayout.LayoutParams.UNSET
                val s = ref.trim()
                return when {
                    s.equals("parent", ignoreCase = true) -> ConstraintLayout.LayoutParams.PARENT_ID
                    s.startsWith("@+id/") || s.startsWith("@id/") -> {
                        val idName = s.removePrefix("@+id/").removePrefix("@id/")
                        viewIdMap[idName] ?: ConstraintLayout.LayoutParams.UNSET
                    }
                    else -> ConstraintLayout.LayoutParams.UNSET
                }
            }
            attrs["layout_constraintTop_toTopOf"]?.let { lp.topToTop = resolve(it) }
            attrs["layout_constraintTop_toBottomOf"]?.let { lp.topToBottom = resolve(it) }
            attrs["layout_constraintBottom_toTopOf"]?.let { lp.bottomToTop = resolve(it) }
            attrs["layout_constraintBottom_toBottomOf"]?.let { lp.bottomToBottom = resolve(it) }
            attrs["layout_constraintLeft_toLeftOf"]?.let { lp.leftToLeft = resolve(it) }
            attrs["layout_constraintLeft_toRightOf"]?.let { lp.leftToRight = resolve(it) }
            attrs["layout_constraintStart_toStartOf"]?.let { lp.startToStart = resolve(it) }
            attrs["layout_constraintStart_toEndOf"]?.let { lp.startToEnd = resolve(it) }
            attrs["layout_constraintRight_toLeftOf"]?.let { lp.rightToLeft = resolve(it) }
            attrs["layout_constraintRight_toRightOf"]?.let { lp.rightToRight = resolve(it) }
            attrs["layout_constraintEnd_toStartOf"]?.let { lp.endToStart = resolve(it) }
            attrs["layout_constraintEnd_toEndOf"]?.let { lp.endToEnd = resolve(it) }
            attrs["layout_constraintBaseline_toBaselineOf"]?.let { lp.baselineToBaseline = resolve(it) }
        }
        node.children.forEach { resolveConstraintViewIds(it, viewIdMap) }
    }

    // =========================================================================
    // Undo / Redo
    // =========================================================================

    private fun pushUndo() {
        val xml = targetFile?.readText() ?: return
        if (undoStack.isNotEmpty() && undoStack.last() == xml) return
        undoStack.addLast(xml)
        if (undoStack.size > 100) undoStack.removeFirst()
        redoStack.clear()
        updateUndoRedoButtons()
    }

    private fun undoEdit() {
        if (undoStack.isEmpty()) return
        val current = targetFile?.readText() ?: ""
        redoStack.addLast(current)
        applyXmlSnapshot(undoStack.removeLast())
        updateUndoRedoButtons()
    }

    private fun redoEdit() {
        if (redoStack.isEmpty()) return
        val current = targetFile?.readText() ?: ""
        undoStack.addLast(current)
        applyXmlSnapshot(redoStack.removeLast())
        updateUndoRedoButtons()
    }

    private fun applyXmlSnapshot(xml: String) {
        suppressFileObserver = true
        targetFile?.writeText(xml)
        suppressFileObserver = false
        loadXmlToNodes()
        renderDesign()
        refreshWidgetPreview()
    }

    private fun updateUndoRedoButtons() {
        undoBtn?.alpha = if (undoStack.isEmpty()) 0.35f else 1f
        undoBtn?.isEnabled = undoStack.isNotEmpty()
        redoBtn?.alpha = if (redoStack.isEmpty()) 0.35f else 1f
        redoBtn?.isEnabled = redoStack.isNotEmpty()
    }

    private fun updateZoomLabel() {
        if (designCanvas == null) return
        zoomLabel?.text = "${Math.round(designCanvas.scale * 100)}%"
    }

    // =========================================================================
    // XML 파일 감시 (외부 수정 자동 반영)
    // =========================================================================

    private fun startFileObserver() {
        val f = targetFile ?: return
        fileObserver?.stopWatching()
        try {
            fileObserver = object : FileObserver(f.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.MODIFY) {
                override fun onEvent(event: Int, path: String?) {
                    if (suppressFileObserver) return
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        loadXmlToNodes()
                        renderDesign()
                        refreshWidgetPreview()
                    }
                }
            }.apply { startWatching() }
        } catch (e: Exception) {
            Log.w(TAG, "FileObserver start failed", e)
        }
    }

    private fun stopFileObserver() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    override fun onDestroy() {
        stopFileObserver()
        super.onDestroy()
    }

    // =========================================================================
    // Drawable 프리뷰
    // =========================================================================

    private fun showDrawablePreview(file: File) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#1E2227".toColorInt())
        }
        root.addView(makeTopBar(file.name, "Drawable preview"))
        root.addView(buildDrawablePreviewPanel(file).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        root.addView(makeInfoBar(file))
        setContentView(root)
    }

    private fun buildDrawablePreviewPanel(file: File): View {
        val wrapper = FrameLayout(this).apply {
            setBackgroundColor("#0F1218".toColorInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val checker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor("#F7F9FC".toColorInt())
                cornerRadius = dp(18f)
                setStroke(dp(1), "#D0D7DE".toColorInt())
            }
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumWidth = dp(96)
            minimumHeight = dp(96)
            maxWidth = dp(260)
            maxHeight = dp(260)
        }
        try {
            if (file.extension.lowercase() == "xml") preview.setImageDrawable(parseDrawableXml(file))
            else preview.setImageURI(Uri.fromFile(file))
        } catch (e: Exception) {
            Log.e(TAG, "Drawable preview error", e)
            return makeFallbackView("Drawable preview error: ${e.message}")
        }
        checker.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        checker.addView(TextView(this).apply {
            text = "${file.name}  (${file.length()} bytes)"
            textSize = 11f
            setTextColor("#57606A".toColorInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        })
        wrapper.addView(checker, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        return wrapper
    }

    private fun parseDrawableXml(file: File): Drawable {
        val xml = file.readText()
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) {
            parser.next()
        }
        if (parser.eventType == XmlPullParser.END_DOCUMENT) return ColorDrawable(Color.TRANSPARENT)
        val rootTag = parser.name?.lowercase().orEmpty()
        return when (rootTag) {
            "shape" -> parseShapeDrawable(xml)
            else -> runCatching {
                val drawableParser = Xml.newPullParser()
                drawableParser.setInput(xml.reader())
                while (drawableParser.eventType != XmlPullParser.START_TAG && drawableParser.eventType != XmlPullParser.END_DOCUMENT) {
                    drawableParser.next()
                }
                Drawable.createFromXml(resources, drawableParser)
            }.getOrElse {
                GradientDrawable().apply {
                    setColor("#90CAF9".toColorInt())
                    cornerRadius = dp(12f)
                    setStroke(dp(2), "#4F8EF7".toColorInt())
                }
            }
        }
    }

    private fun parseShapeDrawable(xml: String): Drawable {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val drawable = GradientDrawable()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = parser.name?.lowercase().orEmpty()
                val attrs = collectAttrs(parser)
                when (tag) {
                    "shape" -> {
                        drawable.shape = when (attrs["shape"]?.lowercase()) {
                            "oval" -> GradientDrawable.OVAL
                            "line" -> GradientDrawable.LINE
                            "ring" -> GradientDrawable.RING
                            else -> GradientDrawable.RECTANGLE
                        }
                    }
                    "solid" -> drawable.setColor(parseColor(attrs["color"]) ?: "#90CAF9".toColorInt())
                    "stroke" -> drawable.setStroke(
                        parseDimen(attrs["width"])?.toInt() ?: dp(1),
                        parseColor(attrs["color"]) ?: "#4F8EF7".toColorInt()
                    )
                    "corners" -> drawable.cornerRadius = parseDimen(attrs["radius"]) ?: dp(8f)
                    "gradient" -> {
                        val start = parseColor(attrs["startColor"]) ?: "#90CAF9".toColorInt()
                        val end = parseColor(attrs["endColor"]) ?: "#4F8EF7".toColorInt()
                        drawable.setColors(intArrayOf(start, end))
                    }
                    "size" -> drawable.setSize(
                        parseDimen(attrs["width"])?.toInt() ?: dp(160),
                        parseDimen(attrs["height"])?.toInt() ?: dp(96)
                    )
                }
            }
            event = parser.next()
        }
        return drawable
    }

    private fun isDrawableResource(file: File): Boolean {
        val normalized = file.absolutePath.replace('\\', '/')
        if (normalized.contains("/res/drawable")) return true
        if (file.extension.lowercase() != "xml") return false
        return runCatching {
            val parser = Xml.newPullParser()
            parser.setInput(file.readText().reader())
            while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) {
                parser.next()
            }
            parser.name?.lowercase() in setOf("shape", "selector", "vector", "layer-list", "ripple", "animated-vector")
        }.getOrDefault(false)
    }

    // =========================================================================
    // Kotlin / 일반 코드 뷰어
    // =========================================================================

    private fun showCodeViewer(file: File) {
        val isKo = AppLanguage.isKorean(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#1E2227".toColorInt())
        }
        val isComposableFile = file.extension.lowercase() == "kt" &&
            file.readText().contains("@Composable", ignoreCase = true)

        val tabLabels = if (isComposableFile) listOf(
            if (isKo) "소스" else "Source",
            if (isKo) "Compose 미리보기" else "Compose Preview"
        ) else listOf(if (isKo) "소스" else "Source")
        val (_, tabViews) = makeTabBar(tabLabels)
        root.addView(makeTopBar(file.name, if (isComposableFile) {
            if (isKo) "Kotlin + Compose" else "Kotlin + Compose"
        } else {
            if (isKo) "코드 뷰어" else "Code viewer"
        }))
        root.addView(tabViews[0])

        if (isComposableFile) {
            val tabBar2 = makeTabBar(listOf(
                if (isKo) "소스" else "Source",
                if (isKo) "Compose 미리보기" else "Compose Preview"
            ))
            root.addView(tabBar2.first)
            val sourcePanel = buildSourcePanel(file)
            val composePanel = buildComposePreviewPanel(file)
            val container = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            }
            container.addView(sourcePanel)
            container.addView(composePanel.apply { visibility = View.GONE })
            root.addView(container)
            tabBar2.second[0].setOnClickListener {
                sourcePanel.visibility = View.VISIBLE
                composePanel.visibility = View.GONE
                highlightTab(tabBar2.second, 0)
            }
            tabBar2.second[1].setOnClickListener {
                sourcePanel.visibility = View.GONE
                composePanel.visibility = View.VISIBLE
                highlightTab(tabBar2.second, 1)
            }
        } else {
            root.addView(buildSourcePanel(file).apply {
                layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            })
        }
        root.addView(makeInfoBar(file))
        setContentView(root)
    }

    private fun buildComposePreviewPanel(file: File): View {
        val isKo = AppLanguage.isKorean(this)
        val content = file.readText().orEmpty()
        val composablePattern = Regex("""@Composable\s*\n\s*(?:suspend\s+)?fun\s+(\w+)\s*\(""")
        val composableFuns = composablePattern.findAll(content).map { it.groupValues[1] }.toList()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor("#1E2227".toColorInt())
        }
        root.addView(TextView(this).apply {
            text = if (isKo) "라이브 미리보기" else "Live Preview"
            setTextColor("#C9D1D9".toColorInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(12), dp(16), dp(4))
        })

        val composeView = ComposeView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(300)).apply { setMargins(dp(12), 0, dp(12), 0) }
            setBackgroundColor("#0F1218".toColorInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    if (composableFuns.isNotEmpty()) {
                        val firstFunc = extractFunctionContent(content, composableFuns.first())
                        val pattern = parseComposablePattern(firstFunc)
                        PreviewComposableHost(pattern)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (isKo) "미리보기할 Composable 함수 없음" else "No composable to preview",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
        root.addView(composeView)

        root.addView(TextView(this).apply {
            text = if (isKo) "\nComposable 함수: ${composableFuns.size}" else "Composable functions: ${composableFuns.size}"
            setTextColor("#C9D1D9".toColorInt())
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(16), dp(16), dp(16), dp(4))
        })

        if (composableFuns.isEmpty()) {
            root.addView(TextView(this).apply {
                text = if (isKo) "@Composable 함수가 없습니다.\n\n함수 위에 @Composable을 추가하면 미리보기할 수 있습니다."
                else "No @Composable functions detected.\n\nAdd @Composable annotation above a function to preview it."
                setTextColor("#8B949E".toColorInt())
                textSize = 13f
                setPadding(dp(16), dp(16), 0, 0)
            })
        } else {
            composableFuns.forEachIndexed { idx, funcName ->
                val funcContent = extractFunctionContent(content, funcName)
                val btn = Button(this).apply {
                    text = funcName
                    setTextColor("#4F8EF7".toColorInt())
                    textSize = 13f
                    setBackgroundColor("#191D25".toColorInt())
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                        leftMargin = dp(16); rightMargin = dp(16); topMargin = dp(6)
                    }
                    setOnClickListener {
                        showComposablePreviewDialog(funcName, funcContent)
                        composeView.setContent {
                            MaterialTheme(colorScheme = darkColorScheme()) {
                                PreviewComposableHost(parseComposablePattern(funcContent))
                            }
                        }
                    }
                }
                root.addView(btn)
            }
        }
        root.addView(TextView(this).apply {
            text = if (isKo) "\nComposeView로 기기에서 렌더링" else "Rendered on-device with ComposeView"
            textSize = 10f
            setTextColor("#76839A".toColorInt())
            setPadding(dp(16), dp(8), 0, dp(16))
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun extractFunctionContent(text: String, funcName: String): String {
        val idx = text.indexOf("fun $funcName(")
        if (idx < 0) return ""
        val start = text.lastIndexOf('\n', idx) + 1
        var depth = 0
        var end = start
        var inString = false
        for (i in start until text.length) {
            val c = text[i]
            if (c == '"' && (i == 0 || text[i - 1] != '\\')) inString = !inString
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') { depth--; if (depth == 0) { end = i + 1; break } }
            }
        }
        if (end <= start) end = text.indexOf('\n', idx + 1).let { if (it < 0) text.length else it + 1 }
        return text.substring(start, end.coerceAtMost(text.length))
    }

    private fun showComposablePreviewDialog(funcName: String, funcContent: String) {
        val isKo = AppLanguage.isKorean(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor("#21252B".toColorInt())
        }
        body.addView(TextView(this).apply {
            text = if (isKo) "Composable 미리보기" else "Composable Preview"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        body.addView(TextView(this).apply {
            text = "@Composable\nfun $funcName"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor("#46C07D".toColorInt())
            setPadding(0, dp(8), 0, 0)
        })

        val previewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(360)).apply { topMargin = dp(8) }
            setBackgroundColor("#0F1218".toColorInt())
        }
        val composePreview = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    PreviewComposableHost(parseComposablePattern(funcContent))
                }
            }
        }
        previewContainer.addView(composePreview)
        val codeScroll = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            visibility = View.GONE
        }
        val codeText = TextView(this).apply {
            text = funcContent.ifEmpty { "// ${if (isKo) "빈 함수" else "empty function"}" }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.color(StudioUi.TextSoft))
            setLineSpacing(0f, 1.4f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        applyComposableSyntaxHighlighting(codeText)
        codeScroll.addView(codeText)
        previewContainer.addView(codeScroll)
        body.addView(previewContainer)
        body.addView(TextView(this).apply {
            text = if (isKo) "ComposeView로 기기에서 렌더링 (Material3 다크 테마)"
            else "On-device ComposeView rendering (Material3 Dark theme)"
            textSize = 10f
            setTextColor("#76839A".toColorInt())
            setPadding(0, dp(6), 0, 0)
        })

        AlertDialog.Builder(this)
            .setView(body)
            .setPositiveButton(if (isKo) "닫기" else "Close", null)
            .show()
            .window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 16f
                }
            )
    }

    private data class ComposablePattern(
        val hasText: Boolean = false,
        val hasButton: Boolean = false,
        val hasImage: Boolean = false,
        val hasTextField: Boolean = false,
        val hasColumn: Boolean = false,
        val hasRow: Boolean = false,
        val hasCard: Boolean = false,
        val hasLazyColumn: Boolean = false,
        val hasScaffold: Boolean = false,
        val hasTopBar: Boolean = false,
        val hasIcon: Boolean = false,
        val hasCheckbox: Boolean = false,
        val hasSwitch: Boolean = false,
        val hasSlider: Boolean = false,
        val hasProgress: Boolean = false,
        val textCount: Int = 0,
        val sampleTexts: List<String> = emptyList()
    )

    private fun parseComposablePattern(source: String): ComposablePattern {
        var p = ComposablePattern()
        p = p.copy(hasColumn = source.contains("Column(") || source.contains("Column {"))
        p = p.copy(hasRow = source.contains("Row(") || source.contains("Row {"))
        p = p.copy(hasCard = source.contains("Card(") || source.contains("Card {"))
        p = p.copy(hasButton = source.contains("Button(") || source.contains("Button {"))
        p = p.copy(hasTextField = source.contains("TextField(") || source.contains("TextField {"))
        p = p.copy(hasLazyColumn = source.contains("LazyColumn(") || source.contains("LazyColumn {"))
        p = p.copy(hasScaffold = source.contains("Scaffold(") || source.contains("Scaffold {"))
        p = p.copy(hasTopBar = source.contains("TopAppBar(") || source.contains("TopAppBar {"))
        p = p.copy(hasIcon = source.contains("Icon(") || source.contains("Icon {"))
        p = p.copy(hasCheckbox = source.contains("Checkbox(") || source.contains("Checkbox {"))
        p = p.copy(hasSwitch = source.contains("Switch(") || source.contains("Switch {"))
        p = p.copy(hasSlider = source.contains("Slider(") || source.contains("Slider {"))
        p = p.copy(hasProgress = source.contains("CircularProgressIndicator") || source.contains("LinearProgressIndicator"))
        val textMatches = Regex("""Text\(\s*"([^"]*?)"\s*[\),]""").findAll(source)
        val texts = textMatches.map { it.groupValues[1].take(30) }.filter { it.isNotBlank() }.toList()
        p = p.copy(hasText = texts.isNotEmpty() || source.contains("Text("), textCount = texts.size, sampleTexts = texts)
        return p
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PreviewComposableHost(pattern: ComposablePattern) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart
        ) {
            if (pattern.hasScaffold) {
                Scaffold(
                    topBar = {
                        if (pattern.hasTopBar) {
                            TopAppBar(
                                title = { Text("Preview", fontWeight = FontWeight.Bold) },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            )
                        }
                    }
                ) { padding ->
                    composableContent(pattern, Modifier.padding(padding))
                }
            } else {
                composableContent(pattern, Modifier.fillMaxSize())
            }
        }
    }

    @Composable
    private fun composableContent(pattern: ComposablePattern, modifier: Modifier) {
        Column(
            modifier = modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (pattern.hasText && pattern.sampleTexts.isNotEmpty()) {
                pattern.sampleTexts.forEach { text ->
                    Text(text = text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                }
            } else if (pattern.hasText) {
                Text(text = "Preview Text", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            if (pattern.hasButton) Button(onClick = {}) { Text("Button") }
            if (pattern.hasTextField) {
                OutlinedTextField(
                    value = "", onValueChange = {},
                    label = { Text("Input") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (pattern.hasCheckbox) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = false, onCheckedChange = {})
                    Spacer(Modifier.width(8.dp))
                    Text("Checkbox", fontSize = 14.sp)
                }
            }
            if (pattern.hasSwitch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = false, onCheckedChange = {})
                    Spacer(Modifier.width(8.dp))
                    Text("Switch", fontSize = 14.sp)
                }
            }
            if (pattern.hasSlider) Slider(value = 0.5f, onValueChange = {})
            if (pattern.hasProgress) CircularProgressIndicator(modifier = Modifier.size(32.dp))
            if (pattern.hasCard) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(text = "Card Content", modifier = Modifier.padding(16.dp), fontSize = 14.sp)
                }
            }
            if (!pattern.hasText && !pattern.hasButton && !pattern.hasCard) {
                Text(
                    text = "@Composable preview rendering",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    private fun applyComposableSyntaxHighlighting(textView: TextView) {
        val text = textView.text?.toString() ?: return
        val highlighted = SpannableString(text)
        val src = text
        var ci = 0
        while (true) {
            val slIdx = src.indexOf("//", ci)
            val mlIdx = src.indexOf("/*", ci)
            when {
                slIdx >= 0 && (mlIdx < 0 || slIdx < mlIdx) -> {
                    val eol = src.indexOf('\n', slIdx + 2)
                    val end = if (eol < 0) src.length else eol
                    highlighted.setSpan(ForegroundColorSpan(StudioUi.color(StudioUi.TextSoft)),
                        slIdx, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ci = end
                }
                mlIdx >= 0 -> {
                    val close = src.indexOf("*/", mlIdx + 2)
                    val end = if (close < 0) src.length else close + 2
                    highlighted.setSpan(ForegroundColorSpan(StudioUi.color(StudioUi.TextSoft)),
                        mlIdx, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ci = end
                }
                else -> break
            }
        }
        val keywords = setOf("fun", "val", "var", "class", "object", "interface", "enum", "import",
            "package", "return", "if", "else", "when", "for", "while", "do", "try", "catch",
            "finally", "throw", "null", "true", "false", "private", "public", "protected",
            "internal", "override", "abstract", "open", "data", "sealed", "companion",
            "inline", "suspend", "operator", "infix", "tailrec", "annotation", "init",
            "constructor", "by", "as", "is", "in", "typealias", "reified", "crossinline",
            "noinline", "actual", "expect", "value", "where", "out", "this", "super",
            "get", "set", "field", "delegate", "composable")
        for (kw in keywords) {
            var idx = 0
            while (true) {
                idx = src.indexOf(kw, idx)
                if (idx < 0) break
                val before = if (idx > 0) src[idx - 1] else ' '
                val after = if (idx + kw.length < src.length) src[idx + kw.length] else ' '
                if (!before.isLetterOrDigit() && !after.isLetterOrDigit() && before != '_' && after != '_') {
                    highlighted.setSpan(ForegroundColorSpan(StudioUi.color(StudioUi.Purple)),
                        idx, idx + kw.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                idx += kw.length
            }
        }
        for (match in Regex("@\\w+").findAll(src)) {
            highlighted.setSpan(ForegroundColorSpan(StudioUi.color(StudioUi.Amber)),
                match.range.first, match.range.last + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        var inStr = false; var strStart = 0
        for (i in src.indices) {
            val c = src[i]
            if (c == '"' && (i == 0 || src[i - 1] != '\\')) {
                if (!inStr) { inStr = true; strStart = i }
                else {
                    inStr = false
                    highlighted.setSpan(ForegroundColorSpan(StudioUi.color(StudioUi.Green)),
                        strStart, i + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                    val strContent = src.substring(strStart + 1, i)
                    var ti = 0
                    while (true) {
                        val tplStart = strContent.indexOf("\${", ti)
                        if (tplStart < 0) break
                        val tplEnd = strContent.indexOf('}', tplStart + 2)
                        if (tplEnd < 0) break
                        highlighted.setSpan(ForegroundColorSpan(StudioUi.color("#D19A66")),
                            strStart + 1 + tplStart, strStart + 1 + tplEnd + 1,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                        ti = tplEnd + 1
                    }
                }
            }
        }
        for (match in Regex("\\b\\d+[\\.\\d]*[LfF]?\\b").findAll(src)) {
            highlighted.setSpan(ForegroundColorSpan(StudioUi.color("#D19A66")),
                match.range.first, match.range.last + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (match in Regex("""fun\s+(\w+)\s*\(""").findAll(src)) {
            highlighted.setSpan(ForegroundColorSpan(StudioUi.color(StudioUi.ActiveBlue)),
                match.range.first + 4, match.range.last - 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val composeFunctions = setOf("Column", "Row", "Box", "Text", "Button", "Card", "Scaffold",
            "TopAppBar", "Icon", "Image", "TextField", "OutlinedTextField",
            "Checkbox", "Switch", "Slider", "CircularProgressIndicator",
            "LinearProgressIndicator", "LazyColumn", "LazyRow", "Surface",
            "Spacer", "Modifier", "remember", "mutableStateOf", "LaunchedEffect",
            "rememberCoroutineScope")
        for (fn in composeFunctions) {
            var idx = 0
            while (true) {
                idx = src.indexOf(fn, idx)
                if (idx < 0) break
                val before = if (idx > 0) src[idx - 1] else ' '
                if (!before.isLetterOrDigit() && before != '_') {
                    highlighted.setSpan(ForegroundColorSpan(StudioUi.color("#56B6C2")),
                        idx, idx + fn.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                idx += fn.length
            }
        }
        for (match in Regex("""import\s+([\w.]+)""").findAll(src)) {
            highlighted.setSpan(ForegroundColorSpan(StudioUi.color("#56B6C2")),
                match.range.first + 7, match.range.last + 1, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textView.text = highlighted
    }

    // =========================================================================
    // XML 속성 편집기
    // =========================================================================

    private fun showXmlPropertyEditor(file: File) {
        val isKo = AppLanguage.isKorean(this)
        val source = file.readText().orEmpty()
        val views = parseXmlViews(source)
        if (views.isEmpty()) {
            showToast(if (isKo) "편집 가능한 위젯이 없습니다." else "No editable widgets found.")
            return
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }
        body.addView(TextView(this).apply {
            text = if (isKo) "위젯 선택" else "Select Widget"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        val widgetList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LayoutPreviewActivity)
            layoutParams = LinearLayout.LayoutParams(-1, dp(200)).apply { topMargin = dp(8) }
            adapter = object : RecyclerView.Adapter<WidgetViewHolder>() {
                override fun getItemCount() = views.size
                override fun onCreateViewHolder(p: ViewGroup, t: Int) = WidgetViewHolder(
                    TextView(this@LayoutPreviewActivity).apply {
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        textSize = 13f
                        typeface = Typeface.MONOSPACE
                    }
                )
                override fun onBindViewHolder(h: WidgetViewHolder, i: Int) {
                    val (tag, id) = views[i]
                    (h.itemView as TextView).apply {
                        text = "<$tag${if (id.isNotBlank()) " android:id=\"$id\"" else ""}>"
                        setTextColor(StudioUi.color("#4F8EF7"))
                        setOnClickListener { showWidgetPropertyEditor(file, source, tag, id) }
                    }
                }
            }
        }
        body.addView(widgetList)
        AlertDialog.Builder(this)
            .setView(body)
            .setPositiveButton(if (isKo) "닫기" else "Close", null)
            .show()
            .window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 16f
                }
            )
    }

    private data class ParsedView(val tag: String, val id: String)

    private fun parseXmlViews(xml: String): List<ParsedView> {
        val views = mutableListOf<ParsedView>()
        val viewRegex = Regex("<(\\w+)\\s[^>]*?>")
        for (match in viewRegex.findAll(xml)) {
            val tag = match.groupValues[1]
            if (tag.startsWith("/") || tag.equals("?xml", ignoreCase = true) ||
                tag.equals("resources", ignoreCase = true) || tag.equals("selector", ignoreCase = true)) continue
            val element = match.value
            val idMatch = Regex("""android:id\s*=\s*"@\+id/(\w+)""").find(element)
            val id = idMatch?.groupValues?.get(1).orEmpty()
            views.add(ParsedView(tag, if (id.isNotBlank()) "@+id/$id" else ""))
        }
        return views
    }

    private class WidgetViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView)

    private fun showWidgetPropertyEditor(file: File, source: String, tag: String, id: String) {
        val isKo = AppLanguage.isKorean(this)
        val attrPattern = Regex("""${tag}[^>]*?(android:(\w+))\s*=\s*"([^"]*)"""")
        val attrs = attrPattern.findAll(source).map {
            it.groupValues[2] to it.groupValues[3]
        }.toMap().toMutableMap()

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }
        body.addView(TextView(this).apply {
            text = if (isKo) "<$tag> 속성 편집" else "<$tag> Property Editor"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        val textTags = setOf("TextView", "EditText", "Button", "ImageButton",
            "AutoCompleteTextView", "MultiAutoCompleteTextView", "CheckBox",
            "RadioButton", "ToggleButton", "TextInputEditText", "SearchView")
        val isTextWidget = tag in textTags

        data class PropDef(
            val key: String,
            val label: String,
            val type: String,
            val enums: List<String> = emptyList(),
            val visibility: String = "all"
        )

        val allPropDefs = mutableListOf(
            PropDef("text", if (isKo) "텍스트" else "Text", "text", visibility = "textOnly"),
            PropDef("hint", if (isKo) "힌트" else "Hint", "text", visibility = "textOnly"),
            PropDef("textColor", if (isKo) "글꼴색" else "Text Color", "color"),
            PropDef("textSize", if (isKo) "글꼴크기(sp)" else "Text Size (sp)", "text"),
            PropDef("textStyle", if (isKo) "글꼴스타일" else "Text Style", "enum",
                enums = listOf("normal", "bold", "italic")),
            PropDef("gravity", if (isKo) "정렬" else "Gravity", "enum",
                enums = listOf("left", "center", "right", "top", "bottom",
                    "center_vertical", "center_horizontal", "fill")),
            PropDef("lines", if (isKo) "줄" else "Lines", "integer", visibility = "textOnly"),
            PropDef("singleLine", "SingleLine", "boolean", visibility = "textOnly"),
            PropDef("layout_width", if (isKo) "너비" else "Width", "enum",
                enums = listOf("match_parent", "wrap_content", "100dp", "150dp", "200dp", "250dp", "300dp")),
            PropDef("layout_height", if (isKo) "높이" else "Height", "enum",
                enums = listOf("match_parent", "wrap_content", "40dp", "48dp", "56dp", "100dp", "150dp", "200dp", "250dp")),
            PropDef("layout_weight", if (isKo) "가중치" else "Weight (layout)", "float"),
            PropDef("layout_margin", if (isKo) "마진(dp)" else "Margin (dp)", "text"),
            PropDef("layout_marginLeft", "MarginLeft", "text"),
            PropDef("layout_marginTop", "MarginTop", "text"),
            PropDef("layout_marginRight", "MarginRight", "text"),
            PropDef("layout_marginBottom", "MarginBottom", "text"),
            PropDef("padding", if (isKo) "패딩(dp)" else "Padding (dp)", "text"),
            PropDef("paddingLeft", "PaddingLeft", "text"),
            PropDef("paddingTop", "PaddingTop", "text"),
            PropDef("paddingRight", "PaddingRight", "text"),
            PropDef("paddingBottom", "PaddingBottom", "text"),
            PropDef("background", if (isKo) "배경색" else "Background", "color"),
            PropDef("backgroundTint", "BackgroundTint", "color"),
            PropDef("elevation", if (isKo) "그림자(dp)" else "Elevation (dp)", "text"),
            PropDef("alpha", if (isKo) "투명도" else "Alpha", "enum",
                enums = listOf("1.0", "0.9", "0.8", "0.7", "0.5", "0.3", "0.1")),
            PropDef("rotation", if (isKo) "회전(도)" else "Rotation (deg)", "text"),
            PropDef("scaleX", "ScaleX", "float"),
            PropDef("scaleY", "ScaleY", "float"),
            PropDef("visibility", if (isKo) "가시성" else "Visibility", "enum",
                enums = listOf("visible", "invisible", "gone")),
            PropDef("enabled", if (isKo) "활성화" else "Enabled", "boolean"),
            PropDef("clickable", if (isKo) "클릭가능" else "Clickable", "boolean"),
            PropDef("focusable", if (isKo) "포커스가능" else "Focusable", "boolean"),
            PropDef("selected", if (isKo) "선택됨" else "Selected", "boolean"),
            PropDef("src", "Src/Drawable", "text", visibility = "textOnly"),
            PropDef("scaleType", "ScaleType", "enum",
                enums = listOf("fitCenter", "centerCrop", "center", "fitXY", "fitStart", "fitEnd"),
                visibility = "textOnly"),
            PropDef("max", "Max", "integer"),
            PropDef("progress", "Progress", "integer"),
            PropDef("drawableLeft", "Icon Left", "text"),
            PropDef("drawableRight", "Icon Right", "text"),
            PropDef("drawableTop", "Icon Top", "text"),
            PropDef("drawableBottom", "Icon Bottom", "text"),
            PropDef("minWidth", "MinWidth(dp)", "text"),
            PropDef("minHeight", "MinHeight(dp)", "text"),
            PropDef("letterSpacing", "LetterSpacing", "float"),
            PropDef("lineSpacingExtra", "LineSpacing", "float")
        )
        val filteredDefs = allPropDefs.filter { p ->
            p.visibility == "all" || (p.visibility == "textOnly" && isTextWidget)
        }

        val scrollBody = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        val propsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scrollBody.addView(propsContainer)

        val editFields = mutableListOf<Pair<String, EditText>>()
        val colorChips = mutableListOf<View>()
        val previewWidget = FrameLayout(this)
        var currentPreview: View? = null

        fun updatePreview() {
            previewWidget.removeAllViews()
            val paramsMap = mutableMapOf<String, String>()
            for ((key, et) in editFields) paramsMap[key] = et.text.toString()
            val w = createPreviewWidget(tag, paramsMap, isKo)
            currentPreview = w
            val lp = FrameLayout.LayoutParams(
                paramsMap["layout_width"]?.let { parseDimension(it) } ?: dp(200),
                paramsMap["layout_height"]?.let { parseDimension(it) } ?: dp(48)
            ).apply {
                gravity = parseGravity(paramsMap["gravity"])
                val m = paramsMap["layout_margin"]
                if (m != null && m.isNotBlank()) {
                    val mMargin = parseDimension(m)
                    setMargins(mMargin, mMargin, mMargin, mMargin)
                }
            }
            previewWidget.addView(w, lp)
            val bg = paramsMap["background"] ?: ""
            if (bg.isNotBlank()) {
                try { w.setBackgroundColor(Color.parseColor(bg)) } catch (_: Exception) {}
            } else {
                w.setBackgroundColor(StudioUi.color(StudioUi.Surface))
            }
            val el = paramsMap["elevation"]
            if (!el.isNullOrBlank()) {
                try { w.elevation = parseDimension(el).toFloat() } catch (_: Exception) {}
            }
        }

        for (prop in filteredDefs) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, 0)
            }
            val label = TextView(this).apply {
                text = prop.label
                textSize = 11f
                setTextColor(StudioUi.color(StudioUi.TextSoft))
                layoutParams = LinearLayout.LayoutParams(0, -2, 0.6f)
            }
            row.addView(label)

            when (prop.type) {
                "boolean" -> {
                    val sw = Switch(this).apply {
                        isChecked = attrs[prop.key]?.let { it.equals("true", ignoreCase = true) } ?: false
                        textSize = 10f
                        setTextColor(Color.WHITE)
                        setOnCheckedChangeListener { _, isChecked ->
                            attrs[prop.key] = if (isChecked) "true" else "false"
                            updatePreview()
                        }
                    }
                    row.addView(sw)
                }
                "color" -> {
                    val edit = EditText(this).apply {
                        setText(attrs[prop.key].orEmpty())
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(StudioUi.color(StudioUi.Surface))
                        setPadding(dp(6), dp(3), dp(6), dp(3))
                        layoutParams = LinearLayout.LayoutParams(0, -2, 0.4f)
                        isSingleLine = true
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: android.text.Editable?) { updatePreview() }
                        })
                    }
                    val colorChip = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { leftMargin = dp(4) }
                        setBackgroundColor(attrs[prop.key]?.let {
                            try { Color.parseColor(it) } catch (_: Exception) { 0 }
                        } ?: 0)
                    }
                    val colorPickerBtn = TextView(this).apply {
                        text = "#"
                        textSize = 14f
                        setPadding(dp(4), 0, dp(4), 0)
                        setOnClickListener { showColorPickerDialog { hex -> edit.setText(hex) } }
                    }
                    row.addView(edit)
                    row.addView(colorChip)
                    row.addView(colorPickerBtn)
                    editFields.add(prop.key to edit)
                    colorChips.add(colorChip)
                }
                "enum" -> {
                    val currentVal = attrs[prop.key].orEmpty()
                    val dropdown = TextView(this).apply {
                        text = currentVal.ifBlank { if (isKo) "선택..." else "Select..." }
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(StudioUi.color(StudioUi.Surface))
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        gravity = Gravity.CENTER_VERTICAL
                        ellipsize = TextUtils.TruncateAt.END
                        maxLines = 1
                        setOnClickListener {
                            showEnumPickerDialog(prop.label, prop.enums, currentVal) { selected ->
                                text = selected
                                attrs[prop.key] = selected
                                for ((k, et) in editFields) if (k == prop.key) et.setText(selected)
                                updatePreview()
                            }
                        }
                    }
                    val hiddenEdit = EditText(this).apply {
                        setText(currentVal)
                        visibility = View.GONE
                    }
                    row.addView(dropdown)
                    row.addView(hiddenEdit)
                    editFields.add(prop.key to hiddenEdit)
                }
                else -> {
                    val edit = EditText(this).apply {
                        setText(attrs[prop.key].orEmpty())
                        textSize = 11f
                        setTextColor(Color.WHITE)
                        setBackgroundColor(StudioUi.color(StudioUi.Surface))
                        setPadding(dp(6), dp(3), dp(6), dp(3))
                        layoutParams = LinearLayout.LayoutParams(0, -2, 0.4f)
                        isSingleLine = true
                        inputType = when (prop.type) {
                            "integer" -> InputType.TYPE_CLASS_NUMBER
                            "float" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                            else -> InputType.TYPE_CLASS_TEXT
                        }
                        addTextChangedListener(object : android.text.TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                            override fun afterTextChanged(s: android.text.Editable?) { updatePreview() }
                        })
                    }
                    row.addView(edit)
                    editFields.add(prop.key to edit)
                }
            }
            propsContainer.addView(row)
        }

        propsContainer.addView(TextView(this).apply {
            text = if (isKo) "사용자 정의 속성" else "Custom Attributes"
            textSize = 11f
            setTextColor(StudioUi.color("#4F8EF7"))
            setPadding(0, dp(12), 0, dp(4))
        })
        val customRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val customKeyEdit = EditText(this).apply {
            hint = if (isKo) "속성명" else "attr name"
            textSize = 11f
            setTextColor(Color.WHITE)
            setHintTextColor(StudioUi.color(StudioUi.TextSoft))
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
            setPadding(dp(6), dp(3), dp(6), dp(3))
            layoutParams = LinearLayout.LayoutParams(0, -2, 0.5f)
            isSingleLine = true
        }
        val customValEdit = EditText(this).apply {
            hint = if (isKo) "값" else "value"
            textSize = 11f
            setTextColor(Color.WHITE)
            setHintTextColor(StudioUi.color(StudioUi.TextSoft))
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
            setPadding(dp(6), dp(3), dp(6), dp(3))
            layoutParams = LinearLayout.LayoutParams(0, -2, 0.5f)
            isSingleLine = true
        }
        val addBtn = Button(this).apply {
            text = "+"
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(StudioUi.color(StudioUi.ActiveBlue))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4) }
        }
        customRow.addView(customKeyEdit)
        customRow.addView(customValEdit)
        customRow.addView(addBtn)
        propsContainer.addView(customRow)

        propsContainer.addView(TextView(this).apply {
            text = if (isKo) "\n실시간 미리보기" else "\nLive Preview"
            textSize = 12f
            setTextColor(StudioUi.color("#4F8EF7"))
            setPadding(0, dp(12), 0, dp(4))
        })
        previewWidget.apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(120)).apply { topMargin = dp(4) }
            setBackgroundColor(StudioUi.color(StudioUi.Ink))
        }
        propsContainer.addView(previewWidget)
        updatePreview()

        addBtn.setOnClickListener {
            val k = customKeyEdit.text.toString().trim()
            val v = customValEdit.text.toString().trim()
            if (k.isNotBlank() && v.isNotBlank()) {
                attrs[k] = v
                customKeyEdit.setText(""); customValEdit.setText("")
                showToast("$k=\"$v\" " + if (isKo) "추가됨" else "added")
                updatePreview()
            }
        }

        body.addView(scrollBody)

        AlertDialog.Builder(this)
            .setView(body)
            .setPositiveButton(if (isKo) "적용" else "Apply") { _, _ ->
                var newXml = source
                for ((key, edit) in editFields) {
                    val newVal = edit.text.toString()
                    val oldVal = attrs[key] ?: ""
                    if (newVal != oldVal) {
                        if (newVal.isBlank() && oldVal.isNotBlank()) {
                            newXml = newXml.replace(Regex("""\s+android:$key\s*=\s*"${Regex.escape(oldVal)}""""), "")
                        } else if (oldVal.isNotBlank()) {
                            newXml = newXml.replace("""android:$key="$oldVal"""", """android:$key="$newVal"""")
                        } else {
                            when {
                                key == "layout_width" || key == "layout_height" -> {}
                                else -> newXml = newXml.replaceFirst("<$tag", "<$tag\n        android:$key=\"$newVal\"")
                            }
                        }
                    }
                }
                file.writeText(newXml)
                showToast(if (isKo) "XML이 업데이트 되었습니다. 미리보기를 새로고침 하세요." else "XML updated. Refresh preview.")
            }
            .setNegativeButton(if (isKo) "취소" else "Cancel", null)
            .show()
            .window?.let { w ->
                w.setBackgroundDrawable(
                    GradientDrawable().apply {
                        setColor(StudioUi.color(StudioUi.Surface2))
                        cornerRadius = 16f
                    }
                )
                w.setLayout(
                    (resources.displayMetrics.widthPixels * 0.92).toInt(),
                    (resources.displayMetrics.heightPixels * 0.85).toInt()
                )
            }
    }

    private val presetColors = listOf(
        "#FF0000" to "Red", "#00FF00" to "Green", "#0000FF" to "Blue",
        "#FFFF00" to "Yellow", "#FF00FF" to "Magenta", "#00FFFF" to "Cyan",
        "#FFFFFF" to "White", "#000000" to "Black", "#808080" to "Gray",
        "#FF5722" to "DeepOrange", "#4CAF50" to "Green500", "#2196F3" to "Blue500",
        "#9C27B0" to "Purple", "#FF9800" to "Orange", "#795548" to "Brown",
        "#607D8B" to "BlueGray", "#E91E63" to "Pink", "#3F51B5" to "Indigo",
        "#009688" to "Teal", "#21252B" to "EditorBG", "#0F1218" to "DarkBG"
    )

    private fun showColorPickerDialog(onColorSelected: (String) -> Unit) {
        val isKo = AppLanguage.isKorean(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }
        body.addView(TextView(this).apply {
            text = if (isKo) "색상 선택" else "Pick a Color"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        val hexInput = EditText(this).apply {
            hint = "#RRGGBB"
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(StudioUi.color(StudioUi.TextSoft))
            setBackgroundColor(StudioUi.color(StudioUi.Surface))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            isSingleLine = true
        }
        body.addView(hexInput)
        val chipGrid = GridLayout(this).apply {
            columnCount = 6
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        }
        for ((hex, name) in presetColors) {
            val chip = FrameLayout(this).apply {
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1f),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = dp(40); height = dp(40)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                setBackgroundColor(Color.parseColor(hex))
                setOnClickListener { hexInput.setText(hex) }
            }
            chipGrid.addView(chip)
        }
        body.addView(chipGrid)
        AlertDialog.Builder(this)
            .setView(body)
            .setPositiveButton(if (isKo) "선택" else "Select") { _, _ ->
                val h = hexInput.text.toString().trim()
                if (h.isNotBlank()) onColorSelected(h)
            }
            .setNegativeButton(if (isKo) "취소" else "Cancel", null)
            .show()
            .window?.setBackgroundDrawable(
                GradientDrawable().apply {
                    setColor(StudioUi.color(StudioUi.Surface2))
                    cornerRadius = 16f
                }
            )
    }

    private fun showEnumPickerDialog(
        title: String, options: List<String>, current: String,
        onSelected: (String) -> Unit
    ) {
        val isKo = AppLanguage.isKorean(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        val list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(250)).apply { topMargin = dp(8) }
            divider = ColorDrawable(StudioUi.color(StudioUi.Border))
            dividerHeight = 1
            adapter = ArrayAdapter(this@LayoutPreviewActivity,
                android.R.layout.simple_list_item_1, options).apply {
                setDropDownViewResource(android.R.layout.simple_list_item_1)
            }
        }
        body.addView(list)
        val dialog = AlertDialog.Builder(this)
            .setView(body)
            .setPositiveButton(if (isKo) "취소" else "Cancel", null)
            .show()
        list.setOnItemClickListener { _, _, position, _ ->
            onSelected(options[position])
            dialog.dismiss()
        }
        dialog.window?.setBackgroundDrawable(
            GradientDrawable().apply {
                setColor(StudioUi.color(StudioUi.Surface2))
                cornerRadius = 16f
            }
        )
    }

    private fun createPreviewWidget(tag: String, params: MutableMap<String, String>, isKo: Boolean): View {
        return when (tag) {
            "Button" -> Button(this).apply {
                text = params["text"].orEmpty().ifBlank { "Button" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, params["textSize"]?.toFloatOrNull() ?: 14f)
                try { setTextColor(Color.parseColor(params["textColor"])) } catch (_: Exception) {}
            }
            "EditText" -> EditText(this).apply {
                setText(params["text"].orEmpty().ifBlank { "input" })
                hint = params["hint"].orEmpty()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, params["textSize"]?.toFloatOrNull() ?: 14f)
            }
            "CheckBox" -> CheckBox(this).apply { text = params["text"].orEmpty().ifBlank { "Check" } }
            "RadioButton" -> RadioButton(this).apply { text = params["text"].orEmpty().ifBlank { "Radio" } }
            "Switch" -> Switch(this).apply { text = params["text"].orEmpty().ifBlank { "Switch" } }
            "ToggleButton" -> ToggleButton(this).apply {
                textOn = params["textOn"].orEmpty().ifBlank { "ON" }
                textOff = params["textOff"].orEmpty().ifBlank { "OFF" }
            }
            "ProgressBar" -> ProgressBar(this)
            "SeekBar" -> SeekBar(this)
            "RatingBar" -> RatingBar(this).apply { numStars = 5; stepSize = 1f }
            "ImageView" -> ImageView(this).apply { setBackgroundColor(StudioUi.color(StudioUi.Surface2)) }
            "ImageButton" -> ImageButton(this).apply { setBackgroundColor(StudioUi.color(StudioUi.Surface2)) }
            "Spinner" -> Spinner(this)
            "SearchView" -> SearchView(this)
            else -> TextView(this).apply {
                text = params["text"].orEmpty().ifBlank { "<$tag>" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, params["textSize"]?.toFloatOrNull() ?: 14f)
                try { setTextColor(Color.parseColor(params["textColor"])) }
                catch (_: Exception) { setTextColor(Color.WHITE) }
                params["gravity"]?.takeIf { it.isNotBlank() }?.let { gravity = parseGravity(it) }
                setPadding(
                    params["paddingLeft"]?.let { parseDimension(it) } ?: dp(8),
                    params["paddingTop"]?.let { parseDimension(it) } ?: dp(6),
                    params["paddingRight"]?.let { parseDimension(it) } ?: dp(8),
                    params["paddingBottom"]?.let { parseDimension(it) } ?: dp(6)
                )
                params["background"]?.takeIf { it.isNotBlank() }?.let {
                    try { setBackgroundColor(Color.parseColor(it)) } catch (_: Exception) {}
                }
            }
        }
    }

    // =========================================================================
    // 유틸 헬퍼
    // =========================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun parseDimension(raw: String?): Int = parseDimen(raw)?.toInt() ?: 0

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor("#1E2227".toColorInt())
            addView(TextView(this@LayoutPreviewActivity).apply {
                text = message
                textSize = 15f
                setTextColor(Color.WHITE)
            })
        }
        setContentView(root)
    }

    private fun makeFallbackView(message: String): View {
        return TextView(this).apply {
            text = message
            textSize = 12f
            setTextColor("#8B949E".toColorInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
    }

    private fun makeTopBar(title: String, subtitle: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(12), dp(8))
            setBackgroundColor("#21252B".toColorInt())
            addView(TextView(this@LayoutPreviewActivity).apply {
                text = title
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
            })
            addView(TextView(this@LayoutPreviewActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor("#8B949E".toColorInt())
            })
        }
    }

    private fun makeInfoBar(file: File): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor("#1B1F24".toColorInt())
            addView(TextView(this@LayoutPreviewActivity).apply {
                text = file.name
                textSize = 11f
                setTextColor("#8B949E".toColorInt())
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(TextView(this@LayoutPreviewActivity).apply {
                text = "${file.length()} B"
                textSize = 11f
                setTextColor("#8B949E".toColorInt())
            })
        }
    }

    private fun makeTabBar(labels: List<String>): Pair<View, List<TextView>> {
        val bar = LinearLayout(this).apply { setBackgroundColor("#1B1F24".toColorInt()) }
        val tabs = labels.mapIndexed { i, label ->
            TextView(this).apply {
                text = label
                setPadding(dp(20), dp(10), dp(20), dp(10))
                textSize = 12f
                setTextColor(if (i == 0) Color.WHITE else Color.LTGRAY)
                setBackgroundColor(if (i == 0) "#4F8EF7".toColorInt() else Color.TRANSPARENT)
            }.also { bar.addView(it) }
        }
        return bar to tabs
    }

    private fun highlightTab(tabs: List<TextView>, index: Int) {
        for (i in tabs.indices) {
            val tv = tabs[i]
            tv.setTextColor(if (i == index) Color.WHITE else Color.LTGRAY)
            tv.setBackgroundColor(if (i == index) "#4F8EF7".toColorInt() else Color.TRANSPARENT)
        }
    }

    private fun buildSourcePanel(file: File): View {
        return ScrollView(this).apply {
            setBackgroundColor("#0F1218".toColorInt())
            addView(TextView(this@LayoutPreviewActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextColor("#C9D1D9".toColorInt())
                setTextIsSelectable(true)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                text = file.readText()
            })
        }
    }
}