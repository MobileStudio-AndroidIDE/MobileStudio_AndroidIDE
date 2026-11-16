package com.aide.mobilestudio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.widget.NestedScrollView
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.LinkedHashMap
import kotlin.math.roundToInt

/**
 * XML Visual Layout Editor
 *
 * - 실제 Android 위젯으로 렌더링되는 편집 캔버스
 * - 드래그&드롭 / 탭으로 컴포넌트 추가 (검색 포함 팔레트)
 * - 위젯 타입별 Properties 패널 (id/text/size/margin/padding/gravity/visibility...)
 * - ConstraintLayout 시각적 연결 편집 (핸들 드래그 → constraint 속성 생성)
 * - 캔버스 ↔ XML 양방향 실시간 동기화
 */
class XmlVisualEditorActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FILE = "extra_file_path"
        const val ANCHOR_LEFT = 1
        const val ANCHOR_RIGHT = 2
        const val ANCHOR_TOP = 3
        const val ANCHOR_BOTTOM = 4
        const val MIN_ZOOM = 0.7f
        const val MAX_ZOOM = 3.0f
        private val CONTAINER_TAGS = setOf(
            "LinearLayout", "FrameLayout", "RelativeLayout", "ConstraintLayout",
            "ScrollView", "HorizontalScrollView", "NestedScrollView", "CoordinatorLayout",
            "CardView", "MaterialCardView", "RadioGroup", "TableLayout", "TableRow",
            "GridLayout", "AppBarLayout", "Toolbar", "TabLayout", "BottomNavigationView"
        )

        fun start(context: Context, file: File) {
            context.startActivity(
                Intent(context, XmlVisualEditorActivity::class.java)
                    .putExtra(EXTRA_FILE, file.absolutePath)
            )
        }
    }

    // =========================================================================
    // 노드 모델 (원본 XML 구조 보존)
    // =========================================================================

    private class XmlNode(
        var tagName: String,
        val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
        val children: MutableList<XmlNode> = mutableListOf()
    ) {
        var parent: XmlNode? = null
        var textContent: String? = null          // 텍스트 콘텐츠 (TextView 텍스트 등)
        var commentBefore: String? = null        // 시작 태그 앞 주석
        var rect = Rect(0, 0, 200, 60)           // 캔버스 절대 좌표
        var view: View? = null                   // 실제 렌더링된 뷰
        var measuredW = 120
        var measuredH = 60
        var scale = 1f

        val isLayout: Boolean get() = CONTAINER_TAGS.contains(tagName)
        val isRoot: Boolean get() = parent == null

        fun attr(key: String): String? = attrs[key]
        fun attrOr(key: String, def: String): String = attrs[key] ?: def
        fun setAttr(key: String, value: String?) {
            if (value == null) attrs.remove(key) else attrs[key] = value
        }
        fun textAttr(key: String): String = attrOr("android:$key", "")

        val id: String? get() = attr("android:id")?.removePrefix("@+id/")?.removePrefix("@id/")
        val nameForDisplay: String get() {
            val i = id
            if (!i.isNullOrEmpty()) return i
            return if (textAttr("text").isNotBlank()) textAttr("text") else tagName
        }

        fun depth(): Int { var d = 0; var p = parent; while (p != null) { d++; p = p.parent }; return d }
        fun indexInParent(): Int = parent?.children?.indexOf(this) ?: 0

        fun findTag(tag: String): XmlNode? {
            if (tagName.equals(tag, true)) return this
            for (c in children) c.findTag(tag)?.let { return it }
            return null
        }
    }

    // =========================================================================
    // 상태
    // =========================================================================

    private var xmlFile: File? = null
    private var rootNode: XmlNode? = null
    private var selectedNode: XmlNode? = null
    private var hoverNode: XmlNode? = null
    private var mode = "design"                 // design | xml
    private var isDark = true
    private var dirty = false

    private lateinit var designCanvas: DesignCanvas
    private lateinit var propertiesSheet: LinearLayout
    private lateinit var paletteSheet: LinearLayout
    private lateinit var paletteSearch: EditText
    private lateinit var xmlEditor: EditText
    private lateinit var statusBar: TextView
    private lateinit var fileNameView: TextView
    private lateinit var dirtyDot: TextView
    private lateinit var zoomLabel: TextView

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()

    private val componentList = listOf(
        "TextView" to "텍스트", "Button" to "버튼", "EditText" to "입력",
        "ImageView" to "이미지", "CheckBox" to "체크", "Switch" to "스위치",
        "RadioButton" to "라디오", "ProgressBar" to "로딩", "SeekBar" to "슬라이더",
        "Spinner" to "드롭다운", "ToggleButton" to "토글", "RatingBar" to "별점",
        "ImageButton" to "아이콘버튼", "Space" to "여백", "View" to "구분선",
        "RecyclerView" to "리스트", "ScrollView" to "스크롤", "HorizontalScrollView" to "가로스크롤",
        "NestedScrollView" to "중첩스크롤", "LinearLayout" to "선형배치", "FrameLayout" to "프레임",
        "RelativeLayout" to "상대배치", "ConstraintLayout" to "제약배치", "CoordinatorLayout" to "코디네이터",
        "CardView" to "카드", "MaterialCardView" to "머티리얼카드", "RadioGroup" to "라디오그룹",
        "TableLayout" to "테이블", "GridLayout" to "그리드", "Toolbar" to "툴바",
        "AppBarLayout" to "앱바", "TabLayout" to "탭바", "BottomNavigationView" to "하단내비"
    )

    // =========================================================================
    // 수명주기
    // =========================================================================

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filePath = intent.getStringExtra(EXTRA_FILE)
        val file = filePath?.let { File(it) }
        if (file == null || !file.exists()) { finish(); return }
        xmlFile = file
        val dm = ThemeManager.getDarkMode(this)
        isDark = when (dm) {
            ThemeManager.DarkMode.LIGHT -> false
            ThemeManager.DarkMode.DARK -> true
            ThemeManager.DarkMode.SYSTEM -> (resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        setContentView(buildLayout())
        loadXml(file.readText())
    }

    @Deprecated("Deprecated in Java")
    @android.annotation.SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        if (propertiesSheet.visibility == View.VISIBLE) { hideSheets(); return }
        if (paletteSheet.visibility == View.VISIBLE) { hideSheets(); return }
        requestExit()
    }

    private fun requestExit() {
        if (dirty) {
            AlertDialog.Builder(this)
                .setTitle(if (AppLanguage.isKorean(this)) "저장하시겠습니까?" else "Save changes?")
                .setMessage(if (AppLanguage.isKorean(this)) "변경 사항이 저장되지 않았습니다." else "You have unsaved changes.")
                .setPositiveButton(if (AppLanguage.isKorean(this)) "저장" else "Save") { _, _ -> saveXml(); finishSafely() }
                .setNegativeButton(if (AppLanguage.isKorean(this)) "취소" else "Cancel", null)
                .setNeutralButton(if (AppLanguage.isKorean(this)) "저장 안 함" else "Discard") { _, _ -> finishSafely() }
                .show()
        } else {
            finishSafely()
        }
    }

    private fun finishSafely() = MobileStudioCrashGuard.finishSafely(this)

    // =========================================================================
    // UI 구성
    // =========================================================================

    @SuppressLint("SetTextI18n")
    private fun buildLayout(): View {
        val isKo = AppLanguage.isKorean(this)
        val bg = if (isDark) "#0B0E14" else "#F4F5F8"
        val surface = if (isDark) "#1E2227" else "#FFFFFF"
        val surface2 = if (isDark) "#21252B" else "#EEF0F4"
        val textColorStr = if (isDark) "#D8DEE9" else "#20242B"
        val soft = if (isDark) "#8E99AC" else "#5C6370"
        val accent = "#61AFEF"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg.toColorInt())
        }

        // ---- 상단 바 ----
        root.addView(LinearLayout(this).apply {
            setBackgroundColor(surface.toColorInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            elevation = dp(2f)
            addView(iconButton(android.R.drawable.ic_menu_close_clear_cancel, if (isKo) "닫기" else "Close") { requestExit() })
            fileNameView = TextView(this@XmlVisualEditorActivity).apply {
                text = xmlFile?.name ?: ""
                setTextColor(textColorStr.toColorInt())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(dp(6), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(fileNameView)
            dirtyDot = TextView(this@XmlVisualEditorActivity).apply {
                text = "*"
                setTextColor(accent.toColorInt())
                textSize = 16f
                visibility = View.GONE
            }
            addView(dirtyDot)
            addView(iconButton(android.R.drawable.ic_menu_revert, if (isKo) "실행취소" else "Undo") { undo() })
            addView(iconButton(android.R.drawable.ic_menu_edit, if (isKo) "다시실행" else "Redo") { redo() })
            addView(textButton(if (isKo) "저장" else "Save") { saveXml() })
        })

        // ---- 탭 바 ----
        root.addView(buildTabBar())

        // ---- 콘텐츠 ----
        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        designCanvas = DesignCanvas(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            onSelect = { node -> selectNode(node) }
            onHover = { node -> hoverNode = node; invalidate() }
            onNodeMoved = { node -> nodeMoved(node) }
            onNodeResized = { node -> nodeResized(node) }
            onConstraintDragged = { node, dir, targetNode, targetDir -> applyConstraint(node, dir, targetNode, targetDir) }
            onCanvasTap = { selectNode(null) }
            onNodeLongPressed = { node ->
                val options = if (AppLanguage.isKorean(this@XmlVisualEditorActivity))
                    arrayOf("복제", "삭제", "속성")
                else arrayOf("Duplicate", "Delete", "Properties")
                
                AlertDialog.Builder(this@XmlVisualEditorActivity)
                    .setTitle("<${node.tagName}> ${node.id ?: ""}")
                    .setItems(options) { _, which ->
                        when (which) {
                            0 -> duplicateNode(node)
                            1 -> deleteNode(node)
                            2 -> showProperties()
                        }
                    }.show()
            }
        }
        content.addView(designCanvas)

        // ---- 줌 컨트롤 (오버레이) ----
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(StudioUi.color(StudioUi.Surface2))
            elevation = dp(4f)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(0, 0, dp(12), dp(12)) }
            addView(textButton("-") { designCanvas.zoomBy(1f / 1.2f) })
            zoomLabel = TextView(this@XmlVisualEditorActivity).apply {
                text = "100%"
                setTextColor(if (isDark) "#D8DEE9".toColorInt() else "#20242B".toColorInt())
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
            addView(zoomLabel)
            addView(textButton("+") { designCanvas.zoomBy(1.2f) })
            addView(textButton(if (isKo) "맞춤" else "Fit") { designCanvas.fitToView() })
        })
        designCanvas.onZoomChanged = { s ->
            zoomLabel.text = "${(s * 100).roundToInt()}%"
        }

        xmlEditor = EditText(this).apply {
            setBackgroundColor(bg.toColorInt())
            setTextColor(textColorStr.toColorInt())
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
            visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (mode == "xml") {
                        dirty = true
                        dirtyDot.visibility = View.VISIBLE
                    }
                }
            })
        }
        content.addView(xmlEditor)

        // ---- 상태 바 ----
        statusBar = TextView(this@XmlVisualEditorActivity).apply {
            text = if (isKo) "위젯을 탭하여 선택 · 드래그로 이동 · 모서리로 크기 조절" else "Tap to select · drag to move · corners to resize"
            setTextColor(soft.toColorInt())
            textSize = 11f
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(surface.toColorInt())
        }

        root.addView(content)
        root.addView(statusBar)

        // ---- 속성 시트 ----
        propertiesSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface.toColorInt())
            elevation = dp(8f)
            visibility = View.GONE
        }
        root.addView(propertiesSheet, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 0f
        ))

        // ---- 팔레트 시트 ----
        paletteSheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface.toColorInt())
            elevation = dp(8f)
            visibility = View.GONE
        }
        root.addView(paletteSheet, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 0f
        ))

        buildPropertiesSheetContent()
        buildPaletteSheetContent()
        return root
    }

    private fun buildTabBar(): View {
        val isKo = AppLanguage.isKorean(this)
        val surface2 = if (isDark) "#21252B" else "#EEF0F4"
        val tabBar = LinearLayout(this).apply {
            setBackgroundColor(surface2.toColorInt())
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val tabs: List<Pair<String, String>> = listOf(
            (if (isKo) "디자인" else "Design") to "design",
            (if (isKo) "컴포넌트" else "Components") to "components",
            (if (isKo) "속성" else "Properties") to "properties",
            (if (isKo) "XML" else "XML") to "xml"
        )
        val btns = mutableListOf<TextView>()
        tabs.forEach { (label, id) ->
            val btn = TextView(this)
            btn.text = label
            btn.textSize = 12f
            btn.gravity = Gravity.CENTER
            btn.setPadding(dp(8), dp(7), dp(8), dp(7))
            btn.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            btn.setBackgroundColor(if (mode == id) accentColor() else Color.TRANSPARENT)
            btn.setTextColor(if (mode == id) Color.WHITE else textSoftColor())
            btn.setOnClickListener {
                mode = id
                btns.forEach { b -> b.setBackgroundColor(Color.TRANSPARENT); b.setTextColor(textSoftColor()) }
                btn.setBackgroundColor(accentColor()); btn.setTextColor(Color.WHITE)
                switchMode(id)
            }
            btns.add(btn); tabBar.addView(btn)
        }
        return tabBar
    }

    private fun switchMode(id: String) {
        when (id) {
            "design" -> {
                if (mode == "xml") {
                    // XML 편집 내용을 캔버스에 반영 (양방향 동기화)
                    rootNode = parseXml(xmlEditor.text.toString())
                    renderCanvasFromTree()
                    selectNode(rootNode)
                }
                designCanvas.visibility = View.VISIBLE
                xmlEditor.visibility = View.GONE
                hideSheets()
            }
            "components" -> {
                designCanvas.visibility = View.VISIBLE
                xmlEditor.visibility = View.GONE
                showPalette()
            }
            "properties" -> {
                designCanvas.visibility = View.VISIBLE
                xmlEditor.visibility = View.GONE
                showProperties()
            }
            "xml" -> {
                designCanvas.visibility = View.GONE
                hideSheets()
                val xml = currentXmlString()
                xmlEditor.setText(xml)
                xmlEditor.visibility = View.VISIBLE
                // Save to file when switching to XML mode to ensure consistency
                saveXml()
            }
        }
    }

    // =========================================================================
    // Properties 패널
    // =========================================================================

    private fun buildPropertiesSheetContent() {
        propertiesSheet.removeAllViews()
        val isKo = AppLanguage.isKorean(this)
        propertiesSheet.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
            addView(TextView(this@XmlVisualEditorActivity).apply {
                text = if (isKo) "속성" else "Properties"
                setTextColor(textColor())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(Space(this@XmlVisualEditorActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(iconButton(android.R.drawable.ic_menu_close_clear_cancel, "Close") { hideSheets() })
        })
        propertiesSheet.addView(ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isDark) dp(380) else dp(360)
            )
            addView(FrameLayout(this@XmlVisualEditorActivity).apply {
                val inner = LinearLayout(this@XmlVisualEditorActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(12), dp(12))
                    tag = "props_body"
                }
                addView(inner, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            })
        })
    }

    private fun showProperties() {
        if (selectedNode == null) {
            selectNode(rootNode)
        }
        renderProperties(selectedNode)
        propertiesSheet.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(440)
        )
        propertiesSheet.visibility = View.VISIBLE
        paletteSheet.visibility = View.GONE
    }

    private fun renderProperties(node: XmlNode?) {
        val container = propsBody() ?: return
        container.removeAllViews()
        val isKo = AppLanguage.isKorean(this)

        if (node == null) {
            container.addView(infoText(if (isKo) "위젯을 선택하세요" else "Select a widget"))
            return
        }

        val v = node.view

        // ---- 위젯 헤더 ----
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(8))
            addView(TextView(this@XmlVisualEditorActivity).apply {
                text = node.tagName
                setTextColor(accentColor())
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(Space(this@XmlVisualEditorActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(textButton(if (isKo) "삭제" else "Delete") {
                deleteNode(node)
            }.apply {
                setTextColor("#E06C75".toColorInt())
            })
            addView(textButton(if (isKo) "복제" else "Duplicate") { duplicateNode(node) })
        })

        // ---- ID ----
        propSection(if (isKo) "ID" else "ID") {
            propTextField("android:id", node.attrOr("android:id", ""), node, prefix = "@+id/")
        }

        // ---- 텍스트 ----
        if (node.tagName in setOf("TextView", "Button", "EditText", "CheckBox", "RadioButton",
                "Switch", "ToggleButton", "AutoCompleteTextView", "MultiAutoCompleteTextView")) {
            propSection(if (isKo) "텍스트" else "Text") {
                propTextField("android:text", node.textAttr("text"), node)
                if (node.tagName.contains("EditText") || node.tagName.contains("AutoComplete")) {
                    propTextField("android:hint", node.textAttr("hint"), node)
                }
                propEnumField("android:textSize", node.textAttr("textSize").ifEmpty { "14sp" },
                    listOf("10sp", "12sp", "14sp", "16sp", "18sp", "20sp", "24sp", "28sp", "32sp", "36sp"), node)
                propColorField("android:textColor", node.attrOr("android:textColor", ""), node)
                propEnumField("android:textStyle", node.textAttr("textStyle").ifEmpty { "normal" },
                    listOf("normal", "bold", "italic", "bold|italic"), node)
                propEnumField("android:textAllCaps", node.textAttr("textAllCaps").ifEmpty { "false" },
                    listOf("false", "true"), node)
                propEnumField("android:gravity", node.textAttr("gravity").ifEmpty { "left" },
                    listOf("left", "center", "right", "center_horizontal", "top", "bottom",
                        "center_vertical", "start", "end", "left|center_vertical", "center|center_vertical"), node)
                propTextField("android:maxLines", node.textAttr("maxLines"), node, numeric = true)
                propTextField("android:letterSpacing", node.textAttr("letterSpacing"), node)
            }
        }

        // ---- 레이아웃 ----
        propSection(if (isKo) "레이아웃" else "Layout") {
            propEnumField("layout_width", node.attrOr("layout_width", "wrap_content"),
                listOf("wrap_content", "match_parent", "0dp"), node)
            propEnumField("layout_height", node.attrOr("layout_height", "wrap_content"),
                listOf("wrap_content", "match_parent", "0dp"), node)
            propEnumField("layout_gravity", node.attrOr("layout_gravity", ""),
                listOf("", "left", "center_horizontal", "right", "top", "center_vertical", "bottom",
                    "center", "start", "end", "fill"), node)
            propTextField("layout_marginStart", node.attrOr("layout_marginStart", ""), node, numeric = true)
            propTextField("layout_marginEnd", node.attrOr("layout_marginEnd", ""), node, numeric = true)
            propTextField("layout_marginTop", node.attrOr("layout_marginTop", ""), node, numeric = true)
            propTextField("layout_marginBottom", node.attrOr("layout_marginBottom", ""), node, numeric = true)
            propTextField("layout_margin", node.attrOr("layout_margin", ""), node, numeric = true)
            propTextField("layout_weight", node.attrOr("layout_weight", ""), node, numeric = true)
        }

        // ---- 패딩/공통 ----
        propSection(if (isKo) "패딩 & 공통" else "Padding & Common") {
            propTextField("android:padding", node.textAttr("padding"), node, numeric = true)
            propTextField("android:paddingStart", node.textAttr("paddingStart"), node, numeric = true)
            propTextField("android:paddingEnd", node.textAttr("paddingEnd"), node, numeric = true)
            propTextField("android:paddingTop", node.textAttr("paddingTop"), node, numeric = true)
            propTextField("android:paddingBottom", node.textAttr("paddingBottom"), node, numeric = true)
            propEnumField("android:visibility", node.textAttr("visibility").ifEmpty { "visible" },
                listOf("visible", "invisible", "gone"), node)
            propTextField("android:alpha", node.textAttr("alpha").ifEmpty { "1.0" }, node)
            propTextField("android:rotation", node.textAttr("rotation"), node, numeric = true)
            propTextField("android:elevation", node.textAttr("elevation"), node, numeric = true)
            propColorField("android:background", node.attrOr("android:background", ""), node)
            propTextField("android:contentDescription", node.textAttr("contentDescription"), node)
        }

        // ---- 컨테이너 ----
        if (node.tagName in setOf("LinearLayout", "RadioGroup")) {
            propSection(if (isKo) "컨테이너" else "Container") {
                propEnumField("android:orientation", node.textAttr("orientation").ifEmpty { "vertical" },
                    listOf("vertical", "horizontal"), node)
                propTextField("android:weightSum", node.textAttr("weightSum"), node, numeric = true)
                propEnumField("android:gravity", node.textAttr("gravity").ifEmpty { "top" },
                    listOf("top", "center", "bottom", "top|start", "top|center_horizontal",
                        "center_vertical", "center_horizontal", "end", "fill"), node)
            }
        }

        // ---- ConstraintLayout ----
        if (node.parent?.tagName == "ConstraintLayout" || node.tagName == "ConstraintLayout") {
            propSection("Constraint") {
                if (node.parent?.tagName == "ConstraintLayout") {
                    propEnumField("layout_constraintStart_toStartOf", node.attrOr("layout_constraintStart_toStartOf", ""),
                        constraintTargets(node), node)
                    propEnumField("layout_constraintStart_toEndOf", node.attrOr("layout_constraintStart_toEndOf", ""),
                        constraintTargets(node), node)
                    propEnumField("layout_constraintEnd_toStartOf", node.attrOr("layout_constraintEnd_toStartOf", ""),
                        constraintTargets(node), node)
                    propEnumField("layout_constraintEnd_toEndOf", node.attrOr("layout_constraintEnd_toEndOf", ""),
                        constraintTargets(node), node)
                    propEnumField("layout_constraintTop_toTopOf", node.attrOr("layout_constraintTop_toTopOf", ""),
                        constraintTargets(node), node)
                    propEnumField("layout_constraintTop_toBottomOf", node.attrOr("layout_constraintTop_toBottomOf", ""),
                        constraintTargets(node), node)
                    propEnumField("layout_constraintBottom_toTopOf", node.attrOr("layout_constraintBottom_toTopOf", ""),
                        constraintTargets(node), node)
                    propEnumField("layout_constraintBottom_toBottomOf", node.attrOr("layout_constraintBottom_toBottomOf", ""),
                        constraintTargets(node), node)
                    propEnumField("layout_constraintHorizontal_bias", node.attrOr("layout_constraintHorizontal_bias", "0.5"),
                        listOf("0.0", "0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"), node)
                    propEnumField("layout_constraintVertical_bias", node.attrOr("layout_constraintVertical_bias", "0.5"),
                        listOf("0.0", "0.1", "0.2", "0.3", "0.4", "0.5", "0.6", "0.7", "0.8", "0.9", "1.0"), node)
                }
                if (node.tagName == "ConstraintLayout") {
                    infoText(if (isKo)
                        "위젯을 선택하면 파란 연결점을 드래그해\nConstraint를 설정할 수 있습니다."
                    else
                        "Select a child and drag the blue anchor\npoints to create constraints.")
                }
            }
        }

        // ---- 이미지 ----
        if (node.tagName in setOf("ImageView", "ImageButton")) {
            propSection("Image") {
                propTextField("android:src", node.textAttr("src"), node)
                propEnumField("android:scaleType", node.textAttr("scaleType").ifEmpty { "fitCenter" },
                    listOf("fitXY", "fitStart", "fitCenter", "fitEnd", "center", "centerCrop", "centerInside"), node)
                propColorField("android:tint", node.attrOr("android:tint", ""), node)
            }
        }

        // ---- 진행/체크 ----
        if (node.tagName == "ProgressBar") {
            propSection("Progress") {
                propTextField("android:progress", node.textAttr("progress"), node, numeric = true)
                propTextField("android:max", node.textAttr("max").ifEmpty { "100" }, node, numeric = true)
                propEnumField("android:indeterminate", node.textAttr("indeterminate").ifEmpty { "false" },
                    listOf("false", "true"), node)
            }
        }
        if (node.tagName in setOf("CheckBox", "Switch", "RadioButton", "ToggleButton")) {
            propSection("State") {
                propEnumField("android:checked", node.textAttr("checked").ifEmpty { "false" },
                    listOf("false", "true"), node)
            }
        }

        // ---- 요소 개수 표시 ----
        if (node.isLayout && node.children.isNotEmpty()) {
            infoText(if (isKo) "자식 요소 ${node.children.size}개" else "${node.children.size} child elements")
        }
    }

    private fun constraintTargets(node: XmlNode): List<String> {
        val targets = mutableListOf("")
        targets.add("parent")
        val siblings = node.parent?.children ?: return targets
        for (s in siblings) {
            if (s === node) continue
            s.id?.let { targets.add("@id/$it") }
            if (targets.size > 30) break
        }
        return targets.distinct()
    }

    private fun propsBody(): LinearLayout? =
        propertiesSheet.findByTag("props_body") as? LinearLayout

    private fun View.findByTag(tag: String): View? {
        if (this.tag == tag) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) {
                getChildAt(i).findByTag(tag)?.let { return it }
            }
        }
        return null
    }

    private fun propSection(title: String, content: () -> Unit) {
        val container = propsBody() ?: return
        container.addView(TextView(this).apply {
            text = title
            setTextColor(accentColor())
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(10), 0, dp(4))
        })
        content()
    }

    private fun propTextField(key: String, value: String, node: XmlNode, numeric: Boolean = false, prefix: String = "") {
        val container = propsBody() ?: return
        container.addView(propLabel(key))
        container.addView(EditText(this).apply {
            setText(value)
            setTextColor(textColor())
            setHintTextColor(textSoftColor())
            setBackgroundColor(if (isDark) "#161B22".toColorInt() else "#E4E7EC".toColorInt())
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val raw = s?.toString()?.trim() ?: ""
                    val finalValue = if (raw.isEmpty()) "" else prefix + raw
                    node.setAttr(key, finalValue.takeIf { it.isNotEmpty() })
                    applyAttrToNode(node, key, finalValue)
                    pushUndo()
                    markDirty()
                    renderCanvasFromTree()
                    updateStatus(if (AppLanguage.isKorean(this@XmlVisualEditorActivity))
                        "$key → $finalValue" else "$key = $finalValue")
                }
            })
        })
    }

    private fun propEnumField(key: String, value: String, options: List<String>, node: XmlNode) {
        val container = propsBody() ?: return
        container.addView(propLabel(key))
        val spinner = Spinner(this).apply {
            setBackgroundColor(if (isDark) "#161B22".toColorInt() else "#E4E7EC".toColorInt())
            adapter = ArrayAdapter(
                this@XmlVisualEditorActivity,
                R.layout.spinner_dark_item,
                options
            ).apply { setDropDownViewResource(R.layout.spinner_dark_dropdown_item) }
            val idx = options.indexOfFirst { it == value }.let { if (it < 0) 0 else it }
            setSelection(idx)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val v = options[position]
                    if (v.isEmpty()) node.setAttr(key, null)
                    else node.setAttr(key, v)
                    applyAttrToNode(node, key, v)
                    pushUndo()
                    markDirty()
                    renderCanvasFromTree()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        container.addView(spinner, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40)
        ))
    }

    private fun propColorField(key: String, value: String, node: XmlNode) {
        val container = propsBody() ?: return
        container.addView(propLabel(key))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val edit = EditText(this).apply {
            setText(value)
            setTextColor(textColor())
            setBackgroundColor(if (isDark) "#161B22".toColorInt() else "#E4E7EC".toColorInt())
            textSize = 12f
            setPadding(dp(8), dp(6), dp(8), dp(6))
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.trim() ?: ""
                    node.setAttr(key, v.takeIf { it.isNotEmpty() })
                    applyAttrToNode(node, key, v)
                    pushUndo(); markDirty(); renderCanvasFromTree()
                }
            })
        }
        row.addView(edit)
        row.addView(TextView(this).apply {
            text = "🎨"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener {
                showColorPicker { color ->
                    node.setAttr(key, color)
                    applyAttrToNode(node, key, color)
                    edit.setText(color)
                    pushUndo(); markDirty(); renderCanvasFromTree()
                }
            }
        })
        container.addView(row)
    }

    private fun propLabel(key: String): TextView = TextView(this).apply {
        text = key
        setTextColor(textSoftColor())
        textSize = 11f
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun infoText(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(textSoftColor())
        textSize = 11f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun showColorPicker(onPick: (String) -> Unit) {
        val isKo = AppLanguage.isKorean(this)
        val colors = listOf(
            "#FFFFFF", "#000000", "#FFEBEE", "#FFCDD2", "#EF9A9A", "#E57373", "#EF5350", "#F44336",
            "#E53935", "#D32F2F", "#C62828", "#B71C1C", "#FF8A80", "#FF5252", "#FF1744", "#D50000",
            "#FCE4EC", "#F8BBD0", "#F48FB1", "#F06292", "#EC407A", "#E91E63", "#D81B60",
            "#C2185B", "#AD1457", "#880E4F", "#FF80AB", "#FF4081", "#F50057", "#C51162",
            "#F3E5F5", "#E1BEE7", "#CE93D8", "#BA68C8", "#AB47BC", "#9C27B0", "#8E24AA",
            "#7B1FA2", "#6A1B9A", "#4A148C", "#EA80FC", "#E040FB", "#D500F9", "#AA00FF",
            "#EDE7F6", "#D1C4E9", "#B39DDB", "#9575CD", "#7E57C2", "#673AB7", "#5E35B1",
            "#512DA8", "#4527A0", "#311B92", "#B388FF", "#7C4DFF", "#651FFF", "#6200EA",
            "#E8EAF6", "#C5CAE9", "#9FA8DA", "#7986CB", "#5C6BC0", "#3F51B5", "#3949AB",
            "#303F9F", "#283593", "#1A237E", "#8C9EFF", "#536DFE", "#3D5AFE", "#304FFE",
            "#E3F2FD", "#BBDEFB", "#90CAF9", "#64B5F6", "#42A5F5", "#2196F3", "#1E88E5",
            "#1976D2", "#1565C0", "#0D47A1", "#82B1FF", "#448AFF", "#2979FF", "#2962FF",
            "#E1F5FE", "#B3E5FC", "#81D4FA", "#4FC3F7", "#29B6F6", "#03A9F4", "#039BE5",
            "#0288D1", "#0277BD", "#01579B", "#80D8FF", "#40C4FF", "#00B0FF", "#0091EA",
            "#E0F7FA", "#B2EBF2", "#80DEEA", "#4DD0E1", "#26C6DA", "#00BCD4", "#00ACC1",
            "#0097A7", "#00838F", "#006064", "#84FFFF", "#18FFFF", "#00E5FF", "#00B8D4",
            "#E0F2F1", "#B2DFDB", "#80CBC4", "#4DB6AC", "#26A69A", "#009688", "#00897B",
            "#00796B", "#00695C", "#004D40", "#A7FFEB", "#64FFDA", "#1DE9B6", "#00BFA5",
            "#E8F5E9", "#C8E6C9", "#A5D6A7", "#81C784", "#66BB6A", "#4CAF50", "#43A047",
            "#388E3C", "#2E7D32", "#1B5E20", "#B9F6CA", "#69F0AE", "#00E676", "#00C853",
            "#F1F8E9", "#DCEDC8", "#C5E1A5", "#AED581", "#9CCC65", "#8BC34A", "#7CB342",
            "#689F38", "#558B2F", "#33691E", "#CCFF90", "#B2FF59", "#76FF03", "#64DD17",
            "#F9FBE7", "#F0F4C3", "#E6EE9C", "#DCE775", "#D4E157", "#CDDC39", "#C0CA33",
            "#AFB42B", "#9E9D24", "#827717", "#F4FF81", "#EEFF41", "#C6FF00", "#AEEA00",
            "#FFFDE7", "#FFF9C4", "#FFF59D", "#FFF176", "#FFEE58", "#FFEB3B", "#FDD835",
            "#FBC02D", "#F9A825", "#F57F17", "#FFFF8D", "#FFFF00", "#FFEA00", "#FFD600",
            "#FFF8E1", "#FFECB3", "#FFE082", "#FFD54F", "#FFCA28", "#FFC107", "#FFB300",
            "#FFA000", "#FF8F00", "#FF6F00", "#FFE57F", "#FFD740", "#FFC400", "#FFAB00",
            "#FFF3E0", "#FFE0B2", "#FFCC80", "#FFB74D", "#FFA726", "#FF9800", "#FB8C00",
            "#F57C00", "#EF6C00", "#E65100", "#FFD180", "#FFAB40", "#FF9100", "#FF6D00",
            "#FBE9E7", "#FFCCBC", "#FFAB91", "#FF8A65", "#FF7043", "#FF5722", "#F4511E",
            "#E64A19", "#D84315", "#BF360C", "#FF9E80", "#FF6E40", "#FF3D00", "#DD2C00",
            "#EFEBE9", "#D7CCC8", "#BCAAA4", "#A1887F", "#8D6E63", "#795548", "#6D4C41",
            "#5D4037", "#4E342E", "#3E2723", "#FAFAFA", "#F5F5F5", "#EEEEEE", "#E0E0E0",
            "#BDBDBD", "#9E9E9E", "#757575", "#616161", "#424242", "#212121"
        )
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isKo) "색상 선택" else "Pick color")
            .setNegativeButton(if (isKo) "취소" else "Cancel", null)
            .create()
        val grid = GridLayout(this).apply {
            columnCount = 8
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        colors.forEach { hex ->
            val cell = View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = dp(36); height = dp(36)
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
                background = GradientDrawable().apply {
                    setColor(hex.toColorInt())
                    cornerRadius = dp(6f)
                    setStroke(1, "#00000033".toColorInt())
                }
                setOnClickListener {
                    onPick(hex)
                    dialog.dismiss()
                }
            }
            grid.addView(cell)
        }
        val scroll = ScrollView(this).apply { addView(grid) }
        dialog.setView(scroll)
        dialog.show()
    }

    // =========================================================================
    // 컴포넌트 팔레트
    // =========================================================================

    private fun buildPaletteSheetContent() {
        paletteSheet.removeAllViews()
        val isKo = AppLanguage.isKorean(this)
        paletteSheet.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
            addView(TextView(this@XmlVisualEditorActivity).apply {
                text = if (isKo) "컴포넌트" else "Components"
                setTextColor(textColor())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
            })
            addView(Space(this@XmlVisualEditorActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            })
            addView(iconButton(android.R.drawable.ic_menu_close_clear_cancel, "Close") { hideSheets() })
        })
        paletteSearch = EditText(this).apply {
            hint = if (isKo) "컴포넌트 검색…" else "Search components…"
            setTextColor(textColor())
            setHintTextColor(textSoftColor())
            setBackgroundColor(if (isDark) "#161B22".toColorInt() else "#E4E7EC".toColorInt())
            textSize = 12f
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        paletteSheet.addView(paletteSearch, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(10), 0, dp(10), dp(6)) })

        val grid = GridLayout(this).apply {
            columnCount = 3
            setPadding(dp(8), 0, dp(8), dp(12))
            id = View.generateViewId()
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(if (isDark) 380 else 360)
            )
            addView(grid)
        }
        paletteSheet.addView(scroll)

        fun renderList(filter: String) {
            grid.removeAllViews()
            componentList
                .filter { (name, desc) ->
                    filter.isBlank() || name.contains(filter, true) || desc.contains(filter, true)
                }
                .forEach { (name, desc) ->
                    grid.addView(componentCell(name, desc))
                }
            if (grid.childCount == 0) {
                grid.addView(TextView(this).apply {
                    text = if (isKo) "결과 없음" else "No results"
                    setTextColor(textSoftColor())
                    textSize = 12f
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                })
            }
        }
        renderList("")
        paletteSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { renderList(s?.toString()?.trim() ?: "") }
        })
    }

    private fun componentCell(name: String, desc: String): View {
        val isKo = AppLanguage.isKorean(this)
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply {
                setColor(if (isDark) "#464E5E".toColorInt() else "#EFF1F5".toColorInt())
                cornerRadius = dp(8f)
            }
            layoutParams = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            addView(componentIcon(name))
            addView(TextView(this@XmlVisualEditorActivity).apply {
                text = name
                setTextColor(textColor())
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                isSingleLine = true
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(this@XmlVisualEditorActivity).apply {
                text = desc
                setTextColor(textSoftColor())
                textSize = 9f
                maxLines = 1
            })
        }
        // 탭 → 추가, 길게 누르기 → 드래그 모드
        cell.setOnClickListener {
            addComponent(name)
            hideSheets()
        }
        cell.setOnLongClickListener {
            dragComponentName = name
            hideSheets()
            statusBar.text = if (isKo) "캔버스에서 추가할 위치를 탭하세요 (${name})" else "Tap the canvas to place $name"
            designCanvas.dragInsertMode = true
            designCanvas.invalidate()
            true
        }
        return cell
    }

    private fun componentIcon(name: String): TextView {
        val icon = when (name) {
            "TextView" -> "T"
            "Button" -> "B"
            "EditText" -> "⌨"
            "ImageView" -> "🖼"
            "CheckBox" -> "☑"
            "Switch" -> "⇄"
            "RadioButton" -> "◉"
            "ProgressBar" -> "◌"
            "SeekBar" -> "─"
            "Spinner" -> "▽"
            "ToggleButton" -> "◨"
            "RatingBar" -> "★"
            "ImageButton" -> "⏺"
            "Space" -> "⋯"
            "View" -> "▬"
            "RecyclerView" -> "≡"
            "ScrollView" -> "⇅"
            "HorizontalScrollView" -> "⇄"
            "NestedScrollView" -> "⇵"
            "LinearLayout" -> "⊞"
            "FrameLayout" -> "▣"
            "RelativeLayout" -> "◧"
            "ConstraintLayout" -> "✛"
            "CoordinatorLayout" -> "⧉"
            "CardView" -> "▢"
            "MaterialCardView" -> "▢"
            "RadioGroup" -> "◍"
            "TableLayout" -> "▤"
            "GridLayout" -> "▦"
            "Toolbar" -> "⌧"
            "AppBarLayout" -> "▤"
            "TabLayout" -> "▤"
            "BottomNavigationView" -> "▬"
            else -> name.take(1)
        }
        return TextView(this).apply {
            text = icon
            setTextColor(accentColor())
            textSize = 20f
            gravity = Gravity.CENTER
        }
    }

    private var dragComponentName: String? = null

    private fun showPalette() {
        paletteSheet.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(440)
        )
        paletteSheet.visibility = View.VISIBLE
        propertiesSheet.visibility = View.GONE
    }

    private fun hideSheets() {
        paletteSheet.visibility = View.GONE
        propertiesSheet.visibility = View.GONE
    }

    // =========================================================================
    // XML 파싱 (구조 보존)
    // =========================================================================

    private fun loadXml(xml: String) {
        rootNode = parseXml(xml)
        renderCanvasFromTree()
        selectNode(rootNode)
        updateStatus("")
    }

    private fun parseXml(xml: String): XmlNode {
        var actualRoot: XmlNode? = null
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(xml))
            val stack = ArrayDeque<XmlNode>()
            var pendingComment: String? = null
            var lastTextNode: XmlNode? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val node = XmlNode(parser.name)
                        for (i in 0 until parser.attributeCount) {
                            val attrName = parser.getAttributeName(i)
                            val prefix = parser.getAttributePrefix(i)
                            val fullName = if (prefix.isNullOrEmpty()) attrName else "$prefix:$attrName"
                            node.attrs[fullName] = parser.getAttributeValue(i)
                        }
                        node.commentBefore = pendingComment
                        pendingComment = null
                        
                        val parent = stack.lastOrNull()
                        if (parent != null) {
                            parent.children.add(node)
                            node.parent = parent
                        } else if (actualRoot == null) {
                            actualRoot = node
                        }
                        stack.addLast(node)
                        lastTextNode = node
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text
                        if (t.isNotBlank()) lastTextNode?.textContent = t.trim()
                    }
                    XmlPullParser.COMMENT -> pendingComment = parser.text
                    XmlPullParser.END_TAG -> {
                        if (stack.isNotEmpty()) stack.removeLast()
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {}
        
        return actualRoot ?: XmlNode("LinearLayout").apply {
            setAttr("android:layout_width", "match_parent")
            setAttr("android:layout_height", "match_parent")
            setAttr("android:orientation", "vertical")
        }
    }

    // =========================================================================
    // 트리 → XML (양방향 동기화: 편집 결과 반영)
    // =========================================================================

    private fun currentXmlString(): String = rootNode?.let { buildXml(it, 0) } ?: ""

    private fun buildXml(node: XmlNode, depth: Int): String {
        val sb = StringBuilder()
        val indent = "    ".repeat(depth)
        val childIndent = "    ".repeat(depth + 1)
        node.commentBefore?.let { sb.appendLine("$indent<!-- $it -->") }

        val selfClosing = node.isLayout.not()
        val attrs = StringBuilder()
        node.attrs.forEach { (k, v) ->
            attrs.appendLine("$childIndent$k=\"$v\"")
        }

        if (depth == 0) {
            // 루트: 네임스페이스 보장
            sb.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            sb.append("<${node.tagName}")
            val namespaces = mutableListOf(
                "xmlns:android" to "http://schemas.android.com/apk/res/android",
                "xmlns:app" to "http://schemas.android.com/apk/res-auto",
                "xmlns:tools" to "http://schemas.android.com/tools"
            )
            namespaces.forEach { (k, v) ->
                if (node.attrs.containsKey(k).not()) {
                    sb.append("\n$childIndent$k=\"$v\"")
                }
            }
            sb.append(attrs)
            sb.append(">")
            if (node.isLayout) sb.appendLine()
        } else if (node.isLayout || node.children.isNotEmpty() || node.textContent != null) {
            sb.append("<${node.tagName}").append(attrs).append(">")
            if (node.isLayout) sb.appendLine()
        } else {
            sb.append("<${node.tagName}").append(attrs)
            sb.append(" />")
            return sb.toString()
        }

        if (node.children.isNotEmpty()) {
            node.children.forEach { child ->
                sb.append(buildXml(child, depth + 1))
            }
        } else if (node.textContent != null) {
            sb.append(node.textContent)
            if (node.isLayout) sb.appendLine()
        }

        if (node.isLayout) sb.append("$indent</${node.tagName}>")
        else sb.append("</${node.tagName}>")
        sb.appendLine()
        return sb.toString()
    }

    // =========================================================================
    // 캔버스 렌더링 (실제 위젯)
    // =========================================================================

    private fun renderCanvasFromTree() {
        val root = rootNode ?: return
        val d = resources.displayMetrics.density
        
        // Calculate virtual size so that 70% zoom fills the screen width
        val canvasW = designCanvas.width.coerceAtLeast(1)
        val canvasH = designCanvas.height.coerceAtLeast(1)
        
        val virtualW = if (canvasW > 1) (canvasW / 0.7f).toInt() else (360 * d).toInt()
        val virtualH = if (canvasH > 1) (canvasH / 0.7f).toInt() else (800 * d).toInt()
        
        // Define screen areas (Status bar 32dp, Nav bar 42dp as in provided design)
        val statusBarH = (32 * d).toInt()
        val navBarH = (42 * d).toInt()
        
        // Root node fills the "white screen" area between the bars
        computeBounds(root, Rect(0, statusBarH, virtualW, virtualH - navBarH))
        designCanvas.setNodes(root)
        designCanvas.invalidate()
    }

    /** 트리 순회하며 실제 좌표 계산 (Layout 종류별 배치) */
    private fun computeBounds(node: XmlNode, parentRect: Rect) {
        val d = resources.displayMetrics.density
        val wResolved = resolveSize(node.attrOr("layout_width", "wrap_content"), parentRect.width(), d, defaultW(node.tagName))
        val hResolved = resolveSize(node.attrOr("layout_height", "wrap_content"), parentRect.height(), d, defaultH(node.tagName))

        node.rect.set(parentRect.left, parentRect.top, parentRect.left + wResolved, parentRect.top + hResolved)

        // 배치 파라미터 (margin)
        val mL = parseDimenPx(node.attrOr("layout_marginStart", node.attrOr("layout_marginLeft", "")), d) ?: 0
        val mR = parseDimenPx(node.attrOr("layout_marginEnd", node.attrOr("layout_marginRight", "")), d) ?: 0
        val mT = parseDimenPx(node.attrOr("layout_marginTop", ""), d) ?: 0
        val mB = parseDimenPx(node.attrOr("layout_marginBottom", ""), d) ?: 0

        val childRect = Rect(
            node.rect.left + mL,
            node.rect.top + mT,
            node.rect.right - mR,
            node.rect.bottom - mB
        )

        when {
            node.tagName in setOf("LinearLayout", "RadioGroup", "TableLayout", "TableRow") -> {
                val horizontal = node.textAttr("orientation") == "horizontal" ||
                        node.tagName == "TableRow"
                var cursor = 0
                if (horizontal) {
                    node.children.forEach { c ->
                        val cw = resolveSize(c.attrOr("layout_width", "wrap_content"),
                            childRect.width(), d, defaultW(c.tagName))
                        val ch = resolveSize(c.attrOr("layout_height", "wrap_content"),
                            childRect.height(), d, defaultH(c.tagName))
                        val cml = parseDimenPx(c.attrOr("layout_marginStart", c.attrOr("layout_marginLeft", "")), d) ?: 0
                        val cmt = parseDimenPx(c.attrOr("layout_marginTop", ""), d) ?: 0
                        val gravityOffset = gravityXOffset(c.attrOr("layout_gravity", ""), childRect.width(), cw)
                        c.rect.set(
                            childRect.left + cursor + cml + gravityOffset,
                            childRect.top + cmt + gravityYOffset(c.attrOr("layout_gravity", ""), childRect.height(), ch),
                            childRect.left + cursor + cml + cw + gravityOffset,
                            childRect.top + cmt + ch + gravityYOffset(c.attrOr("layout_gravity", ""), childRect.height(), ch)
                        )
                        cursor += cw + cml + (parseDimenPx(c.attrOr("layout_marginEnd", c.attrOr("layout_marginRight", "")), d) ?: 0)
                    }
                } else {
                    node.children.forEach { c ->
                        val cw = resolveSize(c.attrOr("layout_width", "wrap_content"),
                            childRect.width(), d, defaultW(c.tagName))
                        val ch = resolveSize(c.attrOr("layout_height", "wrap_content"),
                            childRect.height(), d, defaultH(c.tagName))
                        val cml = parseDimenPx(c.attrOr("layout_marginStart", c.attrOr("layout_marginLeft", "")), d) ?: 0
                        val cmt = parseDimenPx(c.attrOr("layout_marginTop", ""), d) ?: 0
                        val cms = parseDimenPx(c.attrOr("layout_marginEnd", c.attrOr("layout_marginRight", "")), d) ?: 0
                        val cmb = parseDimenPx(c.attrOr("layout_marginBottom", ""), d) ?: 0
                        val gx = gravityXOffset(c.attrOr("layout_gravity", ""), childRect.width(), cw)
                        c.rect.set(
                            childRect.left + cml + gx,
                            childRect.top + cmt + cursor,
                            childRect.left + cml + gx + cw,
                            childRect.top + cmt + cursor + ch
                        )
                        cursor += ch + cmt + cmb
                    }
                }
            }
            node.tagName == "ConstraintLayout" -> {
                node.children.forEach { c ->
                    var left = childRect.left
                    var top = childRect.top
                    val cw = resolveSize(c.attrOr("layout_width", "wrap_content"), childRect.width(), d, defaultW(c.tagName))
                    val ch = resolveSize(c.attrOr("layout_height", "wrap_content"), childRect.height(), d, defaultH(c.tagName))
                    val hb = c.attrOr("layout_constraintHorizontal_bias", "0.5").toFloatOrNull() ?: 0.5f
                    val vb = c.attrOr("layout_constraintVertical_bias", "0.5").toFloatOrNull() ?: 0.5f

                    val startTarget = c.attr("layout_constraintStart_toStartOf")
                        ?: c.attr("layout_constraintStart_toEndOf")
                        ?: c.attr("layout_constraintEnd_toStartOf")
                        ?: c.attr("layout_constraintEnd_toEndOf")
                    val topTarget = c.attr("layout_constraintTop_toTopOf")
                        ?: c.attr("layout_constraintTop_toBottomOf")
                        ?: c.attr("layout_constraintBottom_toTopOf")
                        ?: c.attr("layout_constraintBottom_toBottomOf")

                    val hCentered = c.attr("layout_constraintStart_toStartOf") != null &&
                            (c.attr("layout_constraintEnd_toEndOf") != null)
                    val vCentered = c.attr("layout_constraintTop_toTopOf") != null &&
                            (c.attr("layout_constraintBottom_toBottomOf") != null)

                    if (startTarget != null) {
                        val anchorLeft = when {
                            c.attr("layout_constraintStart_toStartOf") != null -> anchorNodeX(childRect, startTarget, ANCHOR_LEFT)
                            c.attr("layout_constraintStart_toEndOf") != null -> anchorNodeX(childRect, startTarget, ANCHOR_RIGHT)
                            c.attr("layout_constraintEnd_toStartOf") != null -> anchorNodeX(childRect, startTarget, ANCHOR_LEFT) - cw
                            else -> anchorNodeX(childRect, startTarget, ANCHOR_RIGHT) - cw
                        }
                        left = if (hCentered) {
                            (childRect.left + (childRect.width() - cw) * hb).toInt()
                        } else anchorLeft
                    } else {
                        left = (childRect.left + (childRect.width() - cw) * (hb * 2).coerceAtMost(1f) / 2f).toInt()
                    }
                    if (topTarget != null) {
                        val anchorTop = when {
                            c.attr("layout_constraintTop_toTopOf") != null -> anchorNodeY(childRect, topTarget, ANCHOR_TOP)
                            c.attr("layout_constraintTop_toBottomOf") != null -> anchorNodeY(childRect, topTarget, ANCHOR_BOTTOM)
                            c.attr("layout_constraintBottom_toTopOf") != null -> anchorNodeY(childRect, topTarget, ANCHOR_TOP) - ch
                            else -> anchorNodeY(childRect, topTarget, ANCHOR_BOTTOM) - ch
                        }
                        top = if (vCentered) {
                            (childRect.top + (childRect.height() - ch) * vb).toInt()
                        } else anchorTop
                    } else {
                        top = (childRect.top + (childRect.height() - ch) * vb / 2f).toInt()
                    }
                    c.rect.set(left, top, left + cw, top + ch)
                }
            }
            node.tagName == "RelativeLayout" -> {
                node.children.forEach { c ->
                    val cw = resolveSize(c.attrOr("layout_width", "wrap_content"), childRect.width(), d, defaultW(c.tagName))
                    val ch = resolveSize(c.attrOr("layout_height", "wrap_content"), childRect.height(), d, defaultH(c.tagName))
                    var left = childRect.left
                    var top = childRect.top
                    if (c.attr("layout_alignParentEnd") == "true" || c.attr("layout_alignParentRight") == "true") {
                        left = childRect.right - cw
                    } else if (c.attr("layout_alignParentStart") != "true" &&
                        c.attr("layout_alignParentLeft") != "true" &&
                        c.attr("layout_centerHorizontal") == "true") {
                        left = childRect.left + (childRect.width() - cw) / 2
                    }
                    if (c.attr("layout_alignParentBottom") == "true") {
                        top = childRect.bottom - ch
                    } else if (c.attr("layout_alignParentTop") != "true" && c.attr("layout_centerVertical") == "true") {
                        top = childRect.top + (childRect.height() - ch) / 2
                    }
                    c.rect.set(left, top, left + cw, top + ch)
                }
            }
            else -> {
                // FrameLayout 계열: layout_gravity 기반
                node.children.forEach { c ->
                    val cw = resolveSize(c.attrOr("layout_width", "wrap_content"), childRect.width(), d, defaultW(c.tagName))
                    val ch = resolveSize(c.attrOr("layout_height", "wrap_content"), childRect.height(), d, defaultH(c.tagName))
                    val g = c.attrOr("layout_gravity", "")
                    val gx = if (cw >= childRect.width()) 0 else gravityXOffset(g, childRect.width(), cw)
                    val gy = if (ch >= childRect.height()) 0 else gravityYOffset(g, childRect.height(), ch)
                    c.rect.set(
                        childRect.left + gx,
                        childRect.top + gy,
                        childRect.left + gx + cw,
                        childRect.top + gy + ch
                    )
                }
            }
        }

        // 재귀
        node.children.forEach { c ->
            computeBounds(c, c.rect)
        }
    }

    private fun anchorNodeX(parentRect: Rect, target: String, anchor: Int): Int {
        if (target == "parent") {
            return when (anchor) {
                ANCHOR_RIGHT -> parentRect.right
                else -> parentRect.left
            }
        }
        val t = rootNode?.findTag(target.removePrefix("@id/").removePrefix("@+id/"))
        return if (t != null) {
            when (anchor) {
                ANCHOR_LEFT -> t.rect.left
                ANCHOR_RIGHT -> t.rect.right
                else -> t.rect.centerX()
            }
        } else parentRect.left
    }

    private fun anchorNodeY(parentRect: Rect, target: String, anchor: Int): Int {
        if (target == "parent") {
            return when (anchor) {
                ANCHOR_BOTTOM -> parentRect.bottom
                else -> parentRect.top
            }
        }
        val t = rootNode?.findTag(target.removePrefix("@id/").removePrefix("@+id/"))
        return if (t != null) {
            when (anchor) {
                ANCHOR_TOP -> t.rect.top
                ANCHOR_BOTTOM -> t.rect.bottom
                else -> t.rect.centerY()
            }
        } else parentRect.top
    }

    private fun resolveSize(value: String?, avail: Int, d: Float, default: Int): Int = when {
        value == null -> default
        value == "match_parent" || value == "fill_parent" -> avail
        value == "wrap_content" -> default
        value == "0dp" -> default
        value.endsWith("dp") -> ((value.removeSuffix("dp").toFloatOrNull() ?: default.toFloat()) * d).toInt()
        value.endsWith("px") -> (value.removeSuffix("px").toFloatOrNull() ?: default.toFloat()).toInt()
        else -> default
    }

    private fun defaultW(tag: String): Int {
        val d = resources.displayMetrics.density
        return when (tag) {
            "TextView", "Button", "CheckBox", "RadioButton", "Switch", "ToggleButton",
            "EditText", "AutoCompleteTextView" -> (if (tag == "TextView") 120 else 160) * d.toInt()
            "ImageView", "ImageButton" -> 56 * d.toInt()
            "ProgressBar", "SeekBar", "RatingBar" -> 150 * d.toInt()
            "Spinner" -> 180 * d.toInt()
            "View" -> 60 * d.toInt()
            "RecyclerView", "ListView", "GridView" -> 200 * d.toInt()
            else -> 240 * d.toInt()
        }.coerceAtLeast(40)
    }

    private fun defaultH(tag: String): Int {
        val d = resources.displayMetrics.density
        return when (tag) {
            "TextView" -> 40 * d.toInt()
            "Button" -> 48 * d.toInt()
            "EditText", "AutoCompleteTextView" -> 52 * d.toInt()
            "ImageView", "ImageButton" -> 56 * d.toInt()
            "CheckBox", "RadioButton", "Switch", "ToggleButton" -> 48 * d.toInt()
            "ProgressBar", "SeekBar", "RatingBar" -> 40 * d.toInt()
            "Spinner" -> 48 * d.toInt()
            "View" -> 2 * d.toInt()
            else -> 120 * d.toInt()
        }.coerceAtLeast(20)
    }

    private fun gravityXOffset(g: String, parentW: Int, childW: Int): Int = when {
        g.contains("center_horizontal") || g == "center" -> (parentW - childW) / 2
        g.contains("right") || g.contains("end") -> parentW - childW
        else -> 0
    }

    private fun gravityYOffset(g: String, parentH: Int, childH: Int): Int = when {
        g.contains("center_vertical") || g == "center" -> (parentH - childH) / 2
        g.contains("bottom") -> parentH - childH
        else -> 0
    }

    // =========================================================================
    // 노드 조작
    // =========================================================================

    private fun selectNode(node: XmlNode?) {
        selectedNode = node
        designCanvas.selected = node
        designCanvas.invalidate()
        if (node != null) {
            updateStatus("${node.tagName} · ${node.nameForDisplay}")
        }
        renderProperties(node)
    }

    private fun updateStatus(text: String) {
        if (text.isNotBlank()) statusBar.text = text
    }

    private fun nodeMoved(node: XmlNode) {
        val parent = node.parent ?: return
        val parentRect = parent.rect
        val d = resources.displayMetrics.density
        
        // Clamp within parent bounds and auto-resize if outside
        val nodeW = node.rect.width()
        val nodeH = node.rect.height()
        
        var left = node.rect.left
        var top = node.rect.top
        var right = node.rect.right
        var bottom = node.rect.bottom

        // Horizontal clamping
        if (left < parentRect.left) {
            left = parentRect.left
            right = left + nodeW
        }
        if (right > parentRect.right) {
            right = parentRect.right
            left = right - nodeW
            if (left < parentRect.left) left = parentRect.left
        }

        // Vertical clamping
        if (top < parentRect.top) {
            top = parentRect.top
            bottom = top + nodeH
        }
        if (bottom > parentRect.bottom) {
            bottom = parentRect.bottom
            top = bottom - nodeH
            if (top < parentRect.top) top = parentRect.top
        }

        node.rect.set(left, top, right, bottom)

        when {
            parent.tagName == "ConstraintLayout" -> {
                // 위치 → constraint + bias 로 변환
                val dx = node.rect.centerX().toFloat() / parentRect.width()
                val dy = node.rect.centerY().toFloat() / parentRect.height()
                node.setAttr("layout_constraintHorizontal_bias", "%.2f".format(dx.coerceIn(0.05f, 0.95f)))
                node.setAttr("layout_constraintVertical_bias", "%.2f".format(dy.coerceIn(0.05f, 0.95f)))
                if (node.attr("layout_constraintStart_toStartOf") == null)
                    node.setAttr("layout_constraintStart_toStartOf", "parent")
                if (node.attr("layout_constraintTop_toTopOf") == null)
                    node.setAttr("layout_constraintTop_toTopOf", "parent")
                if (node.attr("layout_constraintEnd_toEndOf") == null)
                    node.setAttr("layout_constraintEnd_toEndOf", "parent")
                if (node.attr("layout_constraintBottom_toBottomOf") == null)
                    node.setAttr("layout_constraintBottom_toBottomOf", "parent")
            }
            parent.tagName == "FrameLayout" || parent.tagName == "ScrollView" ||
                parent.tagName == "NestedScrollView" || parent.tagName == "HorizontalScrollView" ||
                parent.tagName == "CardView" || parent.tagName == "MaterialCardView" ||
                parent.tagName == "CoordinatorLayout" || parent.tagName == "AppBarLayout" ||
                parent.tagName == "Toolbar" || parent.tagName == "BottomNavigationView" -> {
                // gravity + margin
                val child = node.rect
                val g = buildList {
                    val cx = child.centerX().toFloat() / parentRect.width()
                    val cy = child.centerY().toFloat() / parentRect.height()
                    add(if (cx < 0.35f) "start" else if (cx > 0.65f) "end" else "center_horizontal")
                    add(if (cy < 0.35f) "top" else if (cy > 0.65f) "bottom" else "center_vertical")
                }.joinToString("|")
                node.setAttr("layout_gravity", g)
                node.setAttr("layout_marginStart", "${(child.left - parentRect.left) / d}")
                node.setAttr("layout_marginTop", "${(child.top - parentRect.top) / d}")
            }
            parent.tagName == "RelativeLayout" -> {
                val child = node.rect
                node.setAttr("layout_alignParentStart",
                    if (child.left < parentRect.left + parentRect.width() / 4) "true" else null)
                node.setAttr("layout_alignParentEnd",
                    if (child.right > parentRect.right - parentRect.width() / 4) "true" else null)
                node.setAttr("layout_alignParentTop",
                    if (child.top < parentRect.top + parentRect.height() / 4) "true" else null)
                node.setAttr("layout_alignParentBottom",
                    if (child.bottom > parentRect.bottom - parentRect.height() / 4) "true" else null)
            }
            parent.tagName == "LinearLayout" || parent.tagName == "RadioGroup" -> {
                val child = node.rect
                val horizontal = parent.textAttr("orientation") == "horizontal" || parent.tagName == "TableRow"
                if (horizontal) {
                    node.setAttr("layout_marginTop", "${(child.top - parentRect.top) / d}")
                } else {
                    node.setAttr("layout_marginStart", "${(child.left - parentRect.left) / d}")
                }
            }
        }
        node.setAttr("layout_width", "${(node.rect.width() / d).toInt()}dp")
        node.setAttr("layout_height", "${(node.rect.height() / d).toInt()}dp")
        pushUndo()
        markDirty()
        renderCanvasFromTree()
    }

    private fun nodeResized(node: XmlNode) {
        val parent = node.parent ?: return
        val parentRect = parent.rect
        val d = resources.displayMetrics.density
        
        // Clamp resized rect within parent
        val left = node.rect.left.coerceIn(parentRect.left, parentRect.right - 8)
        val top = node.rect.top.coerceIn(parentRect.top, parentRect.bottom - 8)
        val right = node.rect.right.coerceIn(left + 8, parentRect.right)
        val bottom = node.rect.bottom.coerceIn(top + 8, parentRect.bottom)
        
        node.rect.set(left, top, right, bottom)
        
        node.setAttr("layout_width", "${(node.rect.width() / d).coerceAtLeast(8f).toInt()}dp")
        node.setAttr("layout_height", "${(node.rect.height() / d).coerceAtLeast(8f).toInt()}dp")
        pushUndo()
        markDirty()
        renderCanvasFromTree()
    }

    private fun applyConstraint(node: XmlNode, dir: Int, targetNode: XmlNode?, targetDir: Int) {
        val parent = node.parent ?: return
        val target = targetNode ?: return
        if (parent.tagName != "ConstraintLayout") return
        val targetRef = if (target === parent) "parent" else ("@id/" + (target.id ?: ""))
        if (targetRef == "@id/") return
        when (dir) {
            ANCHOR_LEFT -> node.setAttr(
                if (targetDir == ANCHOR_RIGHT) "layout_constraintStart_toEndOf" else "layout_constraintStart_toStartOf",
                targetRef)
            ANCHOR_RIGHT -> node.setAttr(
                if (targetDir == ANCHOR_LEFT) "layout_constraintEnd_toStartOf" else "layout_constraintEnd_toEndOf",
                targetRef)
            ANCHOR_TOP -> node.setAttr(
                if (targetDir == ANCHOR_BOTTOM) "layout_constraintTop_toBottomOf" else "layout_constraintTop_toTopOf",
                targetRef)
            ANCHOR_BOTTOM -> node.setAttr(
                if (targetDir == ANCHOR_TOP) "layout_constraintBottom_toTopOf" else "layout_constraintBottom_toBottomOf",
                targetRef)
        }
        if (target.id == null) {
            // 상대 위젯에 id가 없으면 생성
            target.setAttr("android:id", "@+id/${target.tagName.lowercase()}${(100..999).random()}")
        }
        pushUndo()
        markDirty()
        renderCanvasFromTree()
        updateStatus("Constraint: ${node.nameForDisplay} → $targetRef")
    }

    private fun addComponent(tagName: String) {
        val parent = selectedNode?.takeIf { it.isLayout } ?: rootNode ?: return
        val node = XmlNode(tagName)
        node.parent = parent
        node.setAttr("layout_width", "wrap_content")
        node.setAttr("layout_height", "wrap_content")
        when (tagName) {
            "TextView" -> node.setAttr("android:text", if (AppLanguage.isKorean(this)) "새 텍스트" else "Hello World")
            "Button" -> node.setAttr("android:text", if (AppLanguage.isKorean(this)) "버튼" else "Button")
            "EditText" -> node.setAttr("android:hint", if (AppLanguage.isKorean(this)) "입력하세요" else "Type here")
            "CheckBox" -> node.setAttr("android:text", "CheckBox")
            "RadioButton" -> node.setAttr("android:text", "Radio")
            "Switch" -> node.setAttr("android:text", "Switch")
            "ToggleButton" -> node.setAttr("android:text", "ON")
            "ImageView", "ImageButton" -> node.setAttr("android:src", "@drawable/ic_launcher")
            "ScrollView", "NestedScrollView", "HorizontalScrollView" -> {
                node.setAttr("android:fillViewport", "true")
                node.setAttr("layout_width", "match_parent")
                node.setAttr("layout_height", "match_parent")
            }
            "RecyclerView" -> {
                node.setAttr("layout_width", "match_parent")
                node.setAttr("layout_height", "match_parent")
            }
            "LinearLayout", "FrameLayout", "RelativeLayout", "ConstraintLayout",
            "CoordinatorLayout" -> {
                node.setAttr("layout_width", "match_parent")
                node.setAttr("layout_height", "wrap_content")
                node.setAttr("android:orientation", if (tagName == "LinearLayout") "vertical" else "0")
            }
            "CardView", "MaterialCardView" -> {
                node.setAttr("layout_width", "match_parent")
                node.setAttr("layout_height", "wrap_content")
            }
            "Space" -> { node.setAttr("android:layout_width", "0dp"); node.setAttr("android:layout_height", "0dp") }
        }
        // 위치: 부모 중앙에
        val d = resources.displayMetrics.density
        val pw = parent.rect.width()
        val ph = parent.rect.height()
        val dw = defaultW(tagName)
        val dh = defaultH(tagName)
        
        val left = (parent.rect.left + pw / 2 - dw / 2).coerceIn(parent.rect.left, parent.rect.right - dw)
        val top = (parent.rect.top + ph / 2 - dh / 2).coerceIn(parent.rect.top, parent.rect.bottom - dh)
        
        node.rect.set(
            left, top,
            (left + dw).coerceAtMost(parent.rect.right),
            (top + dh).coerceAtMost(parent.rect.bottom)
        )
        parent.children.add(node)
        pushUndo()
        markDirty()
        renderCanvasFromTree()
        selectNode(node)
    }

    private fun deleteNode(node: XmlNode) {
        val parent = node.parent
        parent?.children?.remove(node)
        if (selectedNode == node) selectNode(null)
        pushUndo()
        markDirty()
        renderCanvasFromTree()
    }

    private fun duplicateNode(node: XmlNode) {
        val parent = node.parent
        if (parent == null) {
            Toast.makeText(this,
                if (AppLanguage.isKorean(this)) "루트 레이아웃은 복제할 수 없습니다" else "Root layout cannot be duplicated",
                Toast.LENGTH_SHORT).show()
            return
        }
        val copy = XmlNode(node.tagName)
        node.attrs.forEach { (k, v) -> copy.attrs[k] = v }
        copy.textContent = node.textContent
        copy.commentBefore = node.commentBefore
        copy.setAttr("android:id", null)
        copy.rect.set(node.rect)
        copy.rect.offset(0, node.rect.height() + 8)
        node.children.forEach { child ->
            val cc = XmlNode(child.tagName)
            child.attrs.forEach { (k, v) -> cc.attrs[k] = v }
            cc.textContent = child.textContent
            cc.commentBefore = child.commentBefore
            cc.parent = copy
            copy.children.add(cc)
        }
        copy.parent = parent
        val idx = parent.children.indexOf(node).coerceAtLeast(0)
        parent.children.add(idx + 1, copy)
        pushUndo()
        markDirty()
        renderCanvasFromTree()
        selectNode(copy)
    }

    // =========================================================================
    // 속성 적용 (실제 뷰 갱신)
    // =========================================================================

    private fun applyAttrToNode(node: XmlNode, key: String, value: String) {
        val v = node.view ?: return
        val noPrefix = key.substringAfter(":")
        val d = resources.displayMetrics.density
        when (key) {
            "android:text" -> if (v is TextView) v.text = value
            "android:hint" -> if (v is TextView) v.hint = value
            "android:textSize" -> {
                val size = value.removeSuffix("sp").removeSuffix("dp").toFloatOrNull() ?: 14f
                if (v is TextView) v.setTextSize(size)
            }
            "android:textColor" -> {
                val c = parseColorCompat(value)
                if (c != null && v is TextView) v.setTextColor(c)
            }
            "android:textStyle" -> {
                val flags = if (value.contains("bold") && value.contains("italic")) Typeface.BOLD_ITALIC
                else if (value.contains("bold")) Typeface.BOLD
                else if (value.contains("italic")) Typeface.ITALIC
                else Typeface.NORMAL
                if (v is TextView) v.setTypeface(v.typeface, flags)
            }
            "android:textAllCaps" -> if (v is TextView) v.isAllCaps = value == "true"
            "android:gravity" -> if (v is TextView) v.gravity = parseGravityCompat(value)
            "android:maxLines" -> value.toIntOrNull()?.let { if (v is TextView) v.maxLines = it }
            "android:letterSpacing" -> value.toFloatOrNull()?.let { if (v is TextView) v.letterSpacing = it }
            "android:alpha" -> value.toFloatOrNull()?.let { v.alpha = it }
            "android:rotation" -> value.toFloatOrNull()?.let { v.rotation = it }
            "android:elevation" -> parseDimenPx(value, d)?.toFloat()?.let { v.elevation = it }
            "android:padding" -> {
                val p = parseDimenPx(value, d) ?: 0
                v.setPadding(p, p, p, p)
            }
            "android:paddingStart" -> v.setPadding(parseDimenPx(value, d) ?: v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom)
            "android:paddingEnd" -> v.setPadding(v.paddingLeft, v.paddingTop, parseDimenPx(value, d) ?: v.paddingRight, v.paddingBottom)
            "android:paddingTop" -> v.setPadding(v.paddingLeft, parseDimenPx(value, d) ?: v.paddingTop, v.paddingRight, v.paddingBottom)
            "android:paddingBottom" -> v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, parseDimenPx(value, d) ?: v.paddingBottom)
            "android:visibility" -> v.visibility = when (value) {
                "gone" -> View.GONE
                "invisible" -> View.INVISIBLE
                else -> View.VISIBLE
            }
            "android:background" -> {
                val c = parseColorCompat(value)
                if (c != null) v.setBackgroundColor(c)
            }
            "android:checked" -> {
                if (v is CompoundButton) v.isChecked = value == "true"
            }
            "android:progress" -> if (v is ProgressBar) v.progress = value.toIntOrNull() ?: 0
            "android:max" -> if (v is ProgressBar) v.max = value.toIntOrNull() ?: 100
            "android:indeterminate" -> if (v is ProgressBar) v.isIndeterminate = value == "true"
            "android:contentDescription" -> v.contentDescription = value
            "android:orientation" -> if (v is LinearLayout)
                v.orientation = if (value == "horizontal") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
    }

    private fun parseColorCompat(raw: String): Int? {
        if (raw.isEmpty()) return null
        if (raw.startsWith("#")) return runCatching { Color.parseColor(raw) }.getOrNull()
        if (raw.startsWith("@android:color/")) {
            val name = raw.removePrefix("@android:color/")
            return runCatching {
                val id = resources.getIdentifier(name, "color", "android")
                if (id != 0) resources.getColor(id, theme) else null
            }.getOrNull()
        }
        return null
    }

    private fun parseGravityCompat(raw: String): Int {
        var g = Gravity.NO_GRAVITY
        raw.split("|").forEach { s ->
            g = g or when (s.trim()) {
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
                else -> 0
            }
        }
        return g
    }

    private fun parseDimenPx(raw: String?, d: Float): Int? {
        if (raw.isNullOrEmpty()) return null
        val v: Float? = when {
            raw.endsWith("dp") -> (raw.removeSuffix("dp").toFloatOrNull() ?: 0f) * d
            raw.endsWith("px") -> raw.removeSuffix("px").toFloatOrNull() ?: 0f
            else -> null
        }
        return v?.toInt()
    }

    // =========================================================================
    // Undo / Redo / 저장
    // =========================================================================

    private fun pushUndo() {
        val xml = currentXmlString()
        if (undoStack.lastOrNull() != xml) {
            undoStack.addLast(xml)
            if (undoStack.size > 50) undoStack.removeFirst()
            redoStack.clear()
        }
    }

    private fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(currentXmlString())
        val prev = undoStack.removeLast()
        rootNode = parseXml(prev)
        renderCanvasFromTree()
        markDirty()
    }

    private fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(currentXmlString())
        val next = redoStack.removeLast()
        rootNode = parseXml(next)
        renderCanvasFromTree()
        markDirty()
    }

    private fun markDirty() {
        dirty = true
        dirtyDot.visibility = View.VISIBLE
        // Auto-save update to text editor (but not to file yet for performance)
        if (mode == "design") {
            xmlEditor.setText(currentXmlString())
        }
    }

    private fun saveXml() {
        val file = xmlFile ?: return
        val xml = currentXmlString()
        runCatching { file.writeText(xml) }
            .onSuccess {
                dirty = false
                dirtyDot.visibility = View.GONE
                Toast.makeText(this,
                    if (AppLanguage.isKorean(this)) "저장되었습니다" else "Saved",
                    Toast.LENGTH_SHORT).show()
            }
            .onFailure {
                val isKo = AppLanguage.isKorean(this)
                ErrorLogDialog.show(
                    this,
                    if (isKo) "저장 오류" else "Save Error",
                    "Save failed: ${it.message}"
                )
            }
    }

    // =========================================================================
    // 캔버스 (실제 위젯 렌더링 + 편집)
    // =========================================================================

    private inner class DesignCanvas(context: Context) : View(context) {

        var onSelect: ((XmlNode?) -> Unit)? = null
        var onHover: ((XmlNode?) -> Unit)? = null
        var onNodeMoved: ((XmlNode) -> Unit)? = null
        var onNodeResized: ((XmlNode) -> Unit)? = null
        var onConstraintDragged: ((XmlNode, Int, XmlNode?, Int) -> Unit)? = null
        var onNodeLongPressed: ((XmlNode) -> Unit)? = null
        var onCanvasTap: (() -> Unit)? = null

        var selected: XmlNode? = null
        var dragInsertMode = false

        private val nodes = mutableListOf<XmlNode>()
        private var root: XmlNode? = null
        var scale = 1f
        var panX = 0f
        var panY = 0f
        var onZoomChanged: ((Float) -> Unit)? = null

        private val pinchDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                val focusX = detector.focusX
                val focusY = detector.focusY
                val k = newScale / scale
                // 줌 포인트를 중심으로 스케일 조정 (design 좌표 유지)
                panX = focusX - (focusX - panX) * k
                panY = focusY - (focusY - panY) * k
                scale = newScale
                onZoomChanged?.invoke(scale)
                invalidate()
                return true
            }
        })

        // 터치 상태
        private var touchMode = 0          // 0 none, 1 drag, 2 resize, 3 constraint, 4 pan
        private var touchNode: XmlNode? = null
        private var touchHandle = 0        // 1..8 리사이즈 핸들
        private var touchAnchor = 0        // ANCHOR_*
        private var lastX = 0f
        private var lastY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var startRect = Rect()
        private var snapLines = mutableListOf<Pair<Int, Boolean>>()  // (pos, isVertical)
        
        private val longPressHandler = Handler(Looper.getMainLooper())
        private val longPressRunnable = Runnable {
            if (touchMode == 1 && touchNode != null) {
                val node = touchNode!!
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onNodeLongPressed?.invoke(node)
                touchMode = 0
            }
        }
        private var constraintPreview: Triple<Int, Int, XmlNode?>? = null // (anchor, x|y, target)

        private val dashPaint = Paint().apply {
            color = StudioUi.color(StudioUi.Blue)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
        private val selectPaint = Paint().apply {
            color = StudioUi.color(StudioUi.Blue)
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        private val selectFill = Paint().apply {
            color = StudioUi.color(StudioUi.Blue)
            style = Paint.Style.FILL
        }
        private val anchorPaint = Paint().apply {
            color = StudioUi.color(StudioUi.Blue)
            style = Paint.Style.FILL
        }
        private val anchorEdgePaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        private val handlePaint = Paint().apply {
            color = StudioUi.color(StudioUi.Blue)
            style = Paint.Style.FILL
        }
        private val handleEdgePaint = Paint().apply {
            color = Color.WHITE
            strokeWidth = 1.5f
            style = Paint.Style.STROKE
        }
        private val gridPaint = Paint().apply {
            color = if (isDark) StudioUi.color(StudioUi.Surface) else StudioUi.color(StudioUi.Text)
            strokeWidth = 1f
        }
        private val nodeBgPaint = Paint().apply {
            color = Color.parseColor("#22000000")
            style = Paint.Style.FILL
        }
        private val constraintPaint = Paint().apply {
            color = StudioUi.color(StudioUi.Blue)
            strokeWidth = 3f
        }

        fun setNodes(root: XmlNode) {
            this.root = root
            this.nodes.clear()
            collectNodes(root, this.nodes)
        }

        fun zoomBy(factor: Float) {
            val newScale = (scale * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val cx = width / 2f
            val cy = height / 2f
            val k = newScale / scale
            panX = cx - (cx - panX) * k
            panY = cy - (cy - panY) * k
            scale = newScale
            onZoomChanged?.invoke(scale)
            invalidate()
        }

        fun fitToView() {
            val r = root?.rect ?: return
            if (r.width() <= 0 || r.height() <= 0) return
            
            val d = resources.displayMetrics.density
            val pad = (48f * d)
            val availW = (width - pad * 2).coerceAtLeast(1f)
            val availH = (height - pad * 2).coerceAtLeast(1f)
            
            // Default scale to fit nicely, but we allow user to set it to 0.7 later
            val fitScale = minOf(availW / r.width().toFloat(), availH / r.height().toFloat())
                .coerceIn(MIN_ZOOM, MAX_ZOOM)
            
            scale = fitScale
            centerOnRoot()
            onZoomChanged?.invoke(scale)
            invalidate()
        }

        private fun centerOnRoot() {
            val r = root?.rect ?: return
            panX = (width - r.width() * scale) / 2f
            panY = (height - r.height() * scale) / 2f
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w > 0 && h > 0) {
                // When canvas size is first determined, we can calculate virtual size
                // so that 70% zoom fills the screen width
                renderCanvasFromTree()
                fitToView()
            }
        }

        private fun collectNodes(node: XmlNode, out: MutableList<XmlNode>) {
            out.add(node)
            node.children.forEach { collectNodes(it, out) }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            pinchDetector.onTouchEvent(event)
            if (pinchDetector.isInProgress) return true
            // 화면 좌표 → 디자인 좌표 변환
            val x = (event.x - panX) / scale
            val y = (event.y - panY) / scale
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = x; lastY = y
                    lastRawX = event.x; lastRawY = event.y
                    if (dragInsertMode) {
                        placeDraggedComponent(x, y)
                        dragInsertMode = false
                        statusBar.text = if (AppLanguage.isKorean(this@XmlVisualEditorActivity))
                            "컴포넌트가 추가되었습니다" else "Component added"
                        return true
                    }
                    // 앵커 히트 (ConstraintLayout 자식일 때)
                    val sel = selected
                    if (sel?.parent?.tagName == "ConstraintLayout") {
                        val a = hitAnchor(sel, x, y)
                        if (a != 0) {
                            touchMode = 3
                            touchAnchor = a
                            touchNode = sel
                            return true
                        }
                    }
                    // 리사이즈 핸들 히트
                    if (sel != null) {
                        val h = hitResizeHandle(sel, x, y)
                        if (h != 0) {
                            touchMode = 2
                            touchHandle = h
                            touchNode = sel
                            startRect.set(sel.rect)
                            return true
                        }
                    }
                    // 노드 히트
                    val hit = hitNode(x, y)
                    if (hit != null) {
                        touchMode = 1
                        touchNode = hit
                        startRect.set(hit.rect)
                        if (selected !== hit) onSelect?.invoke(hit)
                        else invalidate()
                        
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
                            val old = Rect(n.rect)
                            n.rect.offset(dx.toInt(), dy.toInt())
                            // 스냅
                            snapLines.clear()
                            val sx = snapX(n)
                            if (sx != null) {
                                n.rect.offset(sx - old.left, 0)
                                snapLines.add(sx to true)
                            }
                            val sy = snapY(n)
                            if (sy != null) {
                                n.rect.offset(0, sy - old.top)
                                snapLines.add(sy to false)
                            }
                            lastX = x; lastY = y
                            invalidate()
                            return true
                        }
                        2 -> {
                            val n = touchNode ?: return true
                            resizeNode(n, (x - startRect.left).toInt(), (y - startRect.top).toInt(), touchHandle)
                            invalidate()
                            return true
                        }
                        3 -> {
                            val n = touchNode ?: return true
                            // 앵커 드래그: 연결 대상 찾기
                            val target = findConstraintTarget(n, touchAnchor, x, y)
                            constraintPreview = Triple(touchAnchor, if (touchAnchor == ANCHOR_LEFT || touchAnchor == ANCHOR_RIGHT) x.toInt() else y.toInt(), target)
                            invalidate()
                            return true
                        }
                        4 -> {
                            panX += event.x - lastRawX; panY += event.y - lastRawY
                            lastRawX = event.x; lastRawY = event.y
                            invalidate()
                            return true
                        }
                    }
                    lastX = x; lastY = y
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    when (touchMode) {
                        1 -> {
                            val n = touchNode
                            if (n != null && n.rect != startRect) {
                                onNodeMoved?.invoke(n)
                            } else if (n != null) {
                                onSelect?.invoke(n)
                            }
                        }
                        2 -> {
                            val n = touchNode
                            if (n != null && n.rect != startRect) {
                                onNodeResized?.invoke(n)
                            }
                        }
                        3 -> {
                            val n = touchNode
                            val cp = constraintPreview
                            if (n != null && cp != null) {
                                val dir = cp.first
                                val pos = cp.second
                                val target = cp.third
                                var targetDir = 0
                                if (target != null) {
                                    val t = target.rect
                                    targetDir = when (dir) {
                                        ANCHOR_LEFT -> if (pos < t.centerX()) ANCHOR_LEFT else ANCHOR_RIGHT
                                        ANCHOR_RIGHT -> if (pos < t.centerX()) ANCHOR_LEFT else ANCHOR_RIGHT
                                        ANCHOR_TOP -> if (pos < t.centerY()) ANCHOR_TOP else ANCHOR_BOTTOM
                                        else -> if (pos < t.centerY()) ANCHOR_TOP else ANCHOR_BOTTOM
                                    }
                                }
                                onConstraintDragged?.invoke(n, dir, target, targetDir)
                            }
                            constraintPreview = null
                        }
                        4 -> {
                            if (Math.abs(x - lastX) < 10f && Math.abs(y - lastY) < 10f) {
                                onCanvasTap?.invoke()
                            }
                        }
                    }
                    touchMode = 0
                    touchNode = null
                    touchHandle = 0
                    snapLines.clear()
                    invalidate()
                    return true
                }
            }
            return true
        }

        private fun placeDraggedComponent(x: Float, y: Float) {
            val name = dragComponentName ?: return
            val parent = selected?.takeIf { it.isLayout } ?: root ?: return
            val node = XmlNode(name)
            node.parent = parent
            node.setAttr("layout_width", "wrap_content")
            node.setAttr("layout_height", "wrap_content")
            val d = resources.displayMetrics.density
            
            // Fixed default sizes for dragged components
            val dw = if (name == "Button") 120 * d else if (name == "TextView") 100 * d else 80 * d
            val dh = if (name == "Button") 48 * d else 40 * d
            
            // Clamp within parent
            val left = (x - dw / 2).toInt().coerceIn(parent.rect.left, parent.rect.right - dw.toInt())
            val top = (y - dh / 2).toInt().coerceIn(parent.rect.top, parent.rect.bottom - dh.toInt())
            
            node.rect.set(
                left, top,
                (left + dw).toInt().coerceAtMost(parent.rect.right),
                (top + dh).toInt().coerceAtMost(parent.rect.bottom)
            )

            parent.children.add(node)
            if (name == "TextView") node.setAttr("android:text", if (AppLanguage.isKorean(this@XmlVisualEditorActivity)) "새 텍스트" else "New Text")
            if (name == "Button") node.setAttr("android:text", "Button")
            pushUndo()
            markDirty()
            renderCanvasFromTree()
            onSelect?.invoke(node)
        }

        private fun hitNode(x: Float, y: Float): XmlNode? {
            for (i in nodes.indices.reversed()) {
                val n = nodes[i]
                if (n.isRoot) continue
                if (n.rect.contains(x.toInt(), y.toInt())) return n
            }
            return null
        }

        private fun hitResizeHandle(node: XmlNode, x: Float, y: Float): Int {
            val r = node.rect
            val h = 20f
            val corners = mapOf(
                1 to Pair(r.left.toFloat(), r.top.toFloat()),
                2 to Pair(r.right.toFloat(), r.top.toFloat()),
                3 to Pair(r.left.toFloat(), r.bottom.toFloat()),
                4 to Pair(r.right.toFloat(), r.bottom.toFloat())
            )
            corners.forEach { (id, p) ->
                if (Math.abs(x - p.first) < h && Math.abs(y - p.second) < h) return id
            }
            // 가장자리
            val edge = 16f
            if (Math.abs(y - r.top) < edge && x > r.left && x < r.right) return 5
            if (Math.abs(y - r.bottom) < edge && x > r.left && x < r.right) return 6
            if (Math.abs(x - r.left) < edge && y > r.top && y < r.bottom) return 7
            if (Math.abs(x - r.right) < edge && y > r.top && y < r.bottom) return 8
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

        private fun hitAnchor(node: XmlNode, x: Float, y: Float): Int {
            val d = resources.displayMetrics.density
            val r = node.rect
            val rA = 18f
            if (Math.abs(x - r.left) < rA && Math.abs(y - r.centerY()) < rA) return ANCHOR_LEFT
            if (Math.abs(x - r.right) < rA && Math.abs(y - r.centerY()) < rA) return ANCHOR_RIGHT
            if (Math.abs(y - r.top) < rA && Math.abs(x - r.centerX()) < rA) return ANCHOR_TOP
            if (Math.abs(y - r.bottom) < rA && Math.abs(x - r.centerX()) < rA) return ANCHOR_BOTTOM
            return 0
        }

        private fun findConstraintTarget(node: XmlNode, anchor: Int, x: Float, y: Float): XmlNode? {
            val parent = node.parent
            val candidates = nodes.filter { it !== node && (it === parent || it.parent === parent) }
            var best: XmlNode? = null
            var bestDist = 60f * resources.displayMetrics.density
            candidates.forEach { c ->
                val r = c.rect
                val pts = mutableListOf<Pair<Int, Pair<Float, Float>>>()
                when (anchor) {
                    ANCHOR_LEFT, ANCHOR_RIGHT -> {
                        pts.add(ANCHOR_LEFT to Pair(r.left.toFloat(), r.centerY().toFloat()))
                        pts.add(ANCHOR_RIGHT to Pair(r.right.toFloat(), r.centerY().toFloat()))
                    }
                    ANCHOR_TOP, ANCHOR_BOTTOM -> {
                        pts.add(ANCHOR_TOP to Pair(r.centerX().toFloat(), r.top.toFloat()))
                        pts.add(ANCHOR_BOTTOM to Pair(r.centerX().toFloat(), r.bottom.toFloat()))
                    }
                }
                pts.forEach { (dir, p) ->
                    val dist = Math.abs(if (anchor == ANCHOR_LEFT || anchor == ANCHOR_RIGHT) p.first - x else p.second - y)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = c
                        constraintTargetDir = dir
                    }
                }
            }
            return best
        }

        private var constraintTargetDir = 0

        private fun snapX(n: XmlNode): Int? {
            val d = resources.displayMetrics.density
            val thresh = (12f * d).toInt()
            val candidates = nodes.filter { it !== n && it !== n.parent }
            var best: Int? = null
            var bestDist = thresh
            candidates.forEach { c ->
                listOf(c.rect.left, c.rect.centerX(), c.rect.right).forEach { pos ->
                    val dist = Math.abs(n.rect.left - pos)
                    if (dist < bestDist) { bestDist = dist; best = pos }
                }
            }
            return best
        }

        private fun snapY(n: XmlNode): Int? {
            val d = resources.displayMetrics.density
            val thresh = (12f * d).toInt()
            val candidates = nodes.filter { it !== n && it !== n.parent }
            var best: Int? = null
            var bestDist = thresh
            candidates.forEach { c ->
                listOf(c.rect.top, c.rect.centerY(), c.rect.bottom).forEach { pos ->
                    val dist = Math.abs(n.rect.top - pos)
                    if (dist < bestDist) { bestDist = dist; best = pos }
                }
            }
            return best
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width
            val h = height
            val d = resources.displayMetrics.density

            // 배경
            canvas.drawColor(if (isDark) StudioUi.color(StudioUi.Ink) else StudioUi.color(StudioUi.Text))

            // 그리드 (화면 좌표계)
            val gridStep = 20 * d
            var gx = 0f
            while (gx < w) { canvas.drawLine(gx, 0f, gx, h.toFloat(), gridPaint); gx += gridStep }
            var gy = 0f
            while (gy < h) { canvas.drawLine(0f, gy, w.toFloat(), gy, gridPaint); gy += gridStep }

            val root = root ?: return

            canvas.save()
            canvas.translate(panX, panY)
            canvas.scale(scale, scale)

            drawPhoneFrame(canvas)

            // 루트 컨테이너 박스
            canvas.drawRect(root.rect, nodeBgPaint)

            // constraint 연결선
            nodes.forEach { n ->
                if (n.parent?.tagName == "ConstraintLayout") {
                    drawConstraints(canvas, n)
                }
            }

            // 노드 렌더링
            val ordered = nodes.sortedBy { it.depth() }
            ordered.forEach { n ->
                if (n.isRoot) return@forEach
                drawNode(canvas, n)
            }

            // 선택 오버레이
            val sel = selected
            if (sel != null && !sel.isRoot) {
                drawSelectionOverlay(canvas, sel, d)
            }

            // 드래그 인서트 모드 안내
            if (dragInsertMode) {
                val p = Paint().apply {
                    color = StudioUi.color(StudioUi.Blue)
                    style = Paint.Style.FILL
                }
                canvas.drawText(
                    if (AppLanguage.isKorean(this@XmlVisualEditorActivity)) "배치할 위치를 탭" else "Tap to place",
                    root.rect.centerX().toFloat(), root.rect.centerY().toFloat(),
                    p.apply { textSize = 16f * d; textAlign = Paint.Align.CENTER }
                )
            }

            // 스냅 라인
            snapLines.forEach { (pos, isV) ->
                if (isV) canvas.drawLine(pos.toFloat(), root.rect.top.toFloat(), pos.toFloat(), root.rect.bottom.toFloat(), dashPaint)
                else canvas.drawLine(root.rect.left.toFloat(), pos.toFloat(), root.rect.right.toFloat(), pos.toFloat(), dashPaint)
            }

            canvas.restore()
        }

        private fun drawPhoneFrame(canvas: Canvas) {
            val root = root ?: return
            val d = resources.displayMetrics.density
            
            // Screen area calculations based on provided smartphone.png style
            val screenLeft = root.rect.left.toFloat()
            val screenRight = root.rect.right.toFloat()
            val statusBarH = 32f * d
            val navBarH = 42f * d
            
            val screenTop = (root.rect.top - statusBarH)
            val screenBottom = (root.rect.bottom + navBarH)
            
            // Hardware Frame (Thin dark bezel)
            val bezel = (2f * d)
            val frameRect = RectF(screenLeft - bezel, screenTop - bezel, screenRight + bezel, screenBottom + bezel)
            canvas.drawRoundRect(frameRect, 12f * d, 12f * d, Paint().apply {
                color = Color.parseColor("#0B0C0F")
                style = Paint.Style.FILL
                isAntiAlias = true
            })

            // Screen Background (Pure White as in screenshot)
            val screenPaint = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            canvas.drawRect(screenLeft, screenTop, screenRight, screenBottom, screenPaint)

            // --- Status Bar (Black with specific icons) ---
            val statusRect = RectF(screenLeft, screenTop, screenRight, screenTop + statusBarH)
            canvas.drawRect(statusRect, Paint().apply { color = Color.parseColor("#1A1C1E"); style = Paint.Style.FILL })
            
            val textPaint = Paint().apply {
                color = Color.WHITE
                textSize = 12f * d
                isAntiAlias = true
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
            // 9:20 on the LEFT
            canvas.drawText("9:20", screenLeft + 12f * d, screenTop + 21f * d, textPaint)
            
            // "MobileStudio" in the CENTER
            val titlePaint = Paint(textPaint).apply { 
                textAlign = Paint.Align.CENTER 
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textSize = 11f * d
            }
            canvas.drawText("MobileStudio", screenLeft + (screenRight - screenLeft) / 2f, screenTop + 21f * d, titlePaint)
            
            // Icons on the RIGHT: Mute, Wi-Fi, 93% Battery
            val iconPaint = Paint().apply { color = Color.WHITE; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 1.2f * d }
            val rx = screenRight - 10f * d
            val ry = screenTop + 16f * d
            
            // Battery Badge (93%)
            val badgeRect = RectF(rx - 32f * d, ry - 7f * d, rx, ry + 7f * d)
            canvas.drawRoundRect(badgeRect, 7f * d, 7f * d, Paint().apply { color = Color.parseColor("#444444"); style = Paint.Style.FILL })
            canvas.drawText("93%", rx - 28f * d, ry + 4f * d, Paint(textPaint).apply { textSize = 8f * d })
            
            // Wi-Fi icon (simplified arcs)
            val wx = rx - 45f * d
            canvas.drawArc(RectF(wx - 8f * d, ry - 8f * d, wx + 8f * d, ry + 8f * d), -140f, 100f, false, iconPaint)
            
            // Mute icon (slash style)
            val mx = wx - 20f * d
            canvas.drawLine(mx - 4f * d, ry - 4f * d, mx + 4f * d, ry + 4f * d, iconPaint)
            canvas.drawLine(mx + 4f * d, ry - 4f * d, mx - 4f * d, ry + 4f * d, iconPaint)

            // --- Navigation Bar (Light Gray with ||| O < icons) ---
            val navBgColor = Color.parseColor("#E0E0E0")
            val navRect = RectF(screenLeft, screenBottom - navBarH, screenRight, screenBottom)
            canvas.drawRect(navRect, Paint().apply { color = navBgColor; style = Paint.Style.FILL })
            
            val navIconPaint = Paint().apply {
                color = Color.parseColor("#444444")
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * d
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
            }
            val ncx = screenLeft + (screenRight - screenLeft) / 2f
            val ncy = screenBottom - (navBarH / 2f)
            
            // Recent (|||)
            canvas.drawLine(ncx - 65f * d, ncy - 6f * d, ncx - 65f * d, ncy + 6f * d, navIconPaint)
            canvas.drawLine(ncx - 70f * d, ncy - 6f * d, ncx - 70f * d, ncy + 6f * d, navIconPaint)
            canvas.drawLine(ncx - 60f * d, ncy - 6f * d, ncx - 60f * d, ncy + 6f * d, navIconPaint)
            
            // Home (O)
            navIconPaint.style = Paint.Style.STROKE
            canvas.drawRoundRect(RectF(ncx - 7f * d, ncy - 7f * d, ncx + 7f * d, ncy + 7f * d), 4f * d, 4f * d, navIconPaint)
            
            // Back (<)
            canvas.drawLine(ncx + 60f * d, ncy - 6f * d, ncx + 54f * d, ncy, navIconPaint)
            canvas.drawLine(ncx + 54f * d, ncy, ncx + 60f * d, ncy + 6f * d, navIconPaint)

            // Inner border for the screen
            canvas.drawRect(screenLeft, screenTop, screenRight, screenBottom, Paint().apply {
                color = Color.parseColor("#33000000")
                style = Paint.Style.STROKE
                strokeWidth = 0.5f * d
            })
        }

        private fun drawConstraints(canvas: Canvas, n: XmlNode) {
            val parent = n.parent ?: return
            val r = n.rect
            fun line(x1: Int, y1: Int, x2: Int, y2: Int) {
                canvas.drawLine(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), constraintPaint)
            }
            n.attr("layout_constraintStart_toStartOf")?.let { line(r.left, r.centerY(), parent.rect.left, r.centerY()) }
            n.attr("layout_constraintStart_toEndOf")?.let { line(r.left, r.centerY(), parent.rect.right, r.centerY()) }
            n.attr("layout_constraintEnd_toStartOf")?.let { line(r.right, r.centerY(), parent.rect.left, r.centerY()) }
            n.attr("layout_constraintEnd_toEndOf")?.let { line(r.right, r.centerY(), parent.rect.right, r.centerY()) }
            n.attr("layout_constraintTop_toTopOf")?.let { line(r.centerX(), r.top, r.centerX(), parent.rect.top) }
            n.attr("layout_constraintTop_toBottomOf")?.let { line(r.centerX(), r.top, r.centerX(), parent.rect.bottom) }
            n.attr("layout_constraintBottom_toTopOf")?.let { line(r.centerX(), r.bottom, r.centerX(), parent.rect.top) }
            n.attr("layout_constraintBottom_toBottomOf")?.let { line(r.centerX(), r.bottom, r.centerX(), parent.rect.bottom) }
        }

        private fun drawNode(canvas: Canvas, n: XmlNode) {
            val r = n.rect
            val d = resources.displayMetrics.density
            val isSel = selected === n
            val isHover = hoverNode === n

            if (n.view == null) {
                n.view = createNodeView(n)
            }
            val v = n.view
            v?.let {
                it.measure(
                    View.MeasureSpec.makeMeasureSpec(r.width(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(r.height(), View.MeasureSpec.EXACTLY)
                )
                it.layout(0, 0, r.width(), r.height())
                canvas.save()
                canvas.translate(r.left.toFloat(), r.top.toFloat())
                it.draw(canvas)
                canvas.restore()
            }

            // 컨테이너 배경 틴트
            if (n.isLayout) {
                canvas.drawRoundRect(
                    RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()),
                    4f, 4f,
                    Paint().apply { color = if (isSel) Color.parseColor("#3361AFEF") else Color.parseColor("#101820") ; style = Paint.Style.FILL }
                )
            }

            // 태그 라벨
            val lp = Paint().apply {
                color = if (isSel) Color.parseColor("#61AFEF") else if (isHover) Color.parseColor("#61AFEF") else Color.parseColor("#5C6370")
                textSize = 10f * d
                typeface = Typeface.MONOSPACE
                isAntiAlias = true
            }
            val label = "<${n.tagName}" + (n.id?.let { " @+id/$it" } ?: "")
            canvas.drawText(label, r.left.toFloat(), (r.top - 4 * d).coerceAtLeast(10 * d), lp)
        }

        private fun drawSelectionOverlay(canvas: Canvas, n: XmlNode, d: Float) {
            val r = n.rect
            val rectF = RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
            canvas.drawRect(rectF, selectFill)
            canvas.drawRect(rectF, selectPaint)

            // 리사이즈 핸들 (모서리 + 가장자리)
            val hs = 7f * d
            val handles = listOf(
                r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom,
                (r.left + r.right) / 2 to r.top, (r.left + r.right) / 2 to r.bottom,
                r.left to (r.top + r.bottom) / 2, r.right to (r.top + r.bottom) / 2
            )
            handles.forEach { (hx, hy) ->
                canvas.drawCircle(hx.toFloat(), hy.toFloat(), hs, handlePaint)
                canvas.drawCircle(hx.toFloat(), hy.toFloat(), hs, handleEdgePaint)
            }

            // Constraint 앵커
            if (n.parent?.tagName == "ConstraintLayout") {
                val aR = 10f * d
                val anchors = listOf(
                    r.left to (r.top + r.bottom) / 2,
                    r.right to (r.top + r.bottom) / 2,
                    (r.left + r.right) / 2 to r.top,
                    (r.left + r.right) / 2 to r.bottom
                )
                anchors.forEach { (ax, ay) ->
                    canvas.drawCircle(ax.toFloat(), ay.toFloat(), aR, anchorPaint)
                    canvas.drawCircle(ax.toFloat(), ay.toFloat(), aR, anchorEdgePaint)
                }
            }

            // constraint 드래그 미리보기
            val cp = constraintPreview
            if (cp != null) {
                val pos = cp.second
                val target = cp.third
                if (target != null) {
                    val tr = target.rect
                    val cx = if (cp.first == ANCHOR_LEFT || cp.first == ANCHOR_RIGHT) r.centerX() else pos
                    val cy = if (cp.first == ANCHOR_TOP || cp.first == ANCHOR_BOTTOM) r.centerY() else pos
                    canvas.drawLine(cx.toFloat(), cy.toFloat(), tr.centerX().toFloat(), tr.centerY().toFloat(), dashPaint)
                }
            }
        }
    }

    // =========================================================================
    // 실제 위젯 생성
    // =========================================================================

    private fun createNodeView(node: XmlNode): View {
        val d = resources.displayMetrics.density
        val tag = node.tagName
        val attrs = node.attrs
        val view: View = when {
            tag == "TextView" -> TextView(this).apply { text = node.textAttr("text").ifEmpty { "Hello World" } }
            tag.contains("EditText") || tag.contains("AutoCompleteTextView") ->
                EditText(this).apply { hint = node.textAttr("hint").ifEmpty { "Type here" } }
            tag == "Button" || tag == "MaterialButton" ->
                Button(this).apply { text = node.textAttr("text").ifEmpty { "Button" } }
            tag == "ImageButton" -> ImageButton(this)
            tag == "ImageView" -> ImageView(this).apply {
                setBackgroundColor(if (isDark) "#2C313A".toColorInt() else "#DDE1E7".toColorInt())
            }
            tag == "CheckBox" -> CheckBox(this).apply { text = node.textAttr("text").ifEmpty { "CheckBox" } }
            tag == "Switch" || tag == "SwitchCompat" || tag == "MaterialSwitch" ->
                Switch(this).apply { text = node.textAttr("text").ifEmpty { "Switch" } }
            tag == "RadioButton" -> RadioButton(this).apply { text = node.textAttr("text").ifEmpty { "Radio" } }
            tag == "ToggleButton" -> ToggleButton(this)
            tag == "ProgressBar" -> {
                val style = attrs["style"]?.lowercase() ?: ""
                if (style.contains("horizontal")) ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
                else ProgressBar(this)
            }
            tag == "SeekBar" -> SeekBar(this)
            tag == "RatingBar" -> RatingBar(this)
            tag == "Spinner" -> Spinner(this).apply {
                adapter = ArrayAdapter(
                    this@XmlVisualEditorActivity,
                    R.layout.spinner_dark_item,
                    listOf("Item 1", "Item 2", "Item 3")
                )
            }
            tag == "Space" -> Space(this)
            tag == "View" -> View(this).apply {
                setBackgroundColor(if (isDark) "#4B5263".toColorInt() else "#B0B6C0".toColorInt())
            }
            tag in setOf("RecyclerView", "ListView", "GridView", "WebView", "VideoView") ->
                placeholderBox(tag)
            tag in CONTAINER_TAGS -> {
                val base: ViewGroup = when (tag) {
                    "LinearLayout", "RadioGroup", "TableLayout", "TableRow" -> LinearLayout(this).apply {
                        orientation = if (node.textAttr("orientation") == "horizontal") LinearLayout.HORIZONTAL
                        else LinearLayout.VERTICAL
                    }
                    "ScrollView" -> ScrollView(this)
                    "HorizontalScrollView" -> HorizontalScrollView(this)
                    "NestedScrollView" -> NestedScrollView(this)
                    else -> FrameLayout(this)
                }
                base.setBackgroundColor(if (isDark) Color.parseColor("#12000000") else Color.parseColor("#0A000000"))
                base
            }
            else -> TextView(this).apply { text = tag }
        }

        // 공통 속성 적용
        node.textAttr("alpha").toFloatOrNull()?.let { view.alpha = it }
        node.textAttr("visibility").let {
            view.visibility = when (it) {
                "gone" -> View.GONE
                "invisible" -> View.INVISIBLE
                else -> View.VISIBLE
            }
        }
        node.textAttr("padding").parseDimPx()?.let { view.setPadding(it, it, it, it) }
        node.textAttr("background").let {
            parseColorCompat(it)?.let { c -> view.setBackgroundColor(c) }
        }
        if (view is TextView) {
            view.setTextColor(if (isDark) Color.parseColor("#D8DEE9") else Color.parseColor("#20242B"))
            node.textAttr("textSize").toFloatOrNull()?.let { view.setTextSize(it) }
        }
        // Views are drawn directly on the design canvas without being attached to a
        // ViewGroup. Assign a default LayoutParams so TextView.setText() / measure()
        // don't NPE on getLayoutParams() (checkForRelayout crash).
        if (view.layoutParams == null) {
            view.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return view
    }

    private fun placeholderBox(label: String): View {        val frame = FrameLayout(this).apply {
            setBackgroundColor(if (isDark) "#1E242B".toColorInt() else "#E4E8EE".toColorInt())
        }
        frame.addView(TextView(this).apply {
            text = label
            setTextColor(textSoftColor())
            textSize = 11f
            gravity = Gravity.CENTER
        })
        return frame
    }

    private fun String.parseDimPx(): Int? {
        val d = resources.displayMetrics.density
        return when {
            endsWith("dp") -> (removeSuffix("dp").toFloatOrNull() ?: 0f) * d
            endsWith("px") -> removeSuffix("px").toFloatOrNull() ?: 0f
            else -> Float.NaN
        }.let { if (it.isNaN()) null else it.toInt() }
    }

    // =========================================================================
    // 헬퍼
    // =========================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun textColor(): Int = if (isDark) "#D8DEE9".toColorInt() else "#20242B".toColorInt()
    private fun textSoftColor(): Int = if (isDark) "#8E99AC".toColorInt() else "#5C6370".toColorInt()
    private fun accentColor(): Int = Color.parseColor("#61AFEF")

    private fun iconButton(iconRes: Int, contentDesc: String, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            setColorFilter(if (isDark) "#D8DEE9".toColorInt() else "#20242B".toColorInt())
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = contentDesc
            setOnClickListener { onClick() }
        }

    private fun textButton(label: String, onClick: () -> Unit = {}): TextView = TextView(this).apply {
        text = label
        setTextColor(accentColor())
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        setOnClickListener { onClick() }
    }
}
