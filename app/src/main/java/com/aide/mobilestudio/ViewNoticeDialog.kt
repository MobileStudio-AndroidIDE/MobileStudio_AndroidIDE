package com.aide.mobilestudio

import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import java.io.BufferedReader
import java.io.InputStreamReader

// VersionNoticeDialog
//
// 앱 실행 시 /assets/MobileStudioVersion.txt를 읽어 팝업으로 표시합니다.
//
// 소스 파일 형식 예시 (MobileStudioVersion.txt):
//
//    <Korean>
//    ## 26.6.15 Daisy
//
//    **26.6.15 Daisy** 버전??는 ??음 기능????함??어 ??습??다:
//
//    - **Gradle** ??디바이??빌드 ??스??지??
//    - **JDK** ??장 Java ?????
//    - **NDK (베??)** C/C++ ??이??브 빌드 지??
//    </Korean>
//    <English>
//    ## 26.6.15 Daisy
//
//    **26.6.15 Daisy** includes the following features:
//
//    - **Gradle** on-device build system
//    - **JDK** built-in Java runtime
//    - **NDK (beta)** C/C++ native build support
//    </English>
//
//  ??지??마크??운:
//    # ??목        ????굵?? ??목
//    ## ??제??    ??중간 굵?? ??목
//    **볼드**      ??굵게 + 밝?? ??
//    - ???        ??불릿 ??인??
//    ????        ????락 구분
//
//  ??MainActivity.kt onCreate() ????에 추??:
//    Handler(Looper.getMainLooper()).postDelayed({
//        VersionNoticeDialog.showIfNeeded(this)
//    }, 400)
object VersionNoticeDialog {

    private const val PREFS        = "ms_version_notice"
    private const val KEY_HIDE_UNTIL = "hide_until"
    private const val ASSET_FILE   = "MobileStudioVersion.txt"
    private const val VERSION_NAME = "26.8.17  ·  Lavender🪻"
    
    fun showIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (System.currentTimeMillis() < prefs.getLong(KEY_HIDE_UNTIL, 0L)) return

        val raw      = readAsset(context) ?: defaultContent()
        val isKorean = AppLanguage.isKorean(context)
        val body     = parseSection(raw, isKorean)

        buildAndShow(context, body, isKorean, prefs)
    }

    // ???? Assets ??기 ??????????????????????????????????????????????????????????????????????????????????????????????????????????????

    private fun readAsset(context: Context): String? = runCatching {
        context.assets.open(ASSET_FILE).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).readText()
        }
    }.getOrNull()

    private fun parseSection(raw: String, isKorean: Boolean): String {
        val tag = if (isKorean) "Korean" else "English"
        return Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            .find(raw)?.groupValues?.getOrNull(1)?.trim()
            ?: raw.trim()
    }

    private fun buildAndShow(
        context:  Context,
        body:     String,
        isKorean: Boolean,
        prefs:    SharedPreferences
    ) {
        val dialog = Dialog(context)
        dialog.setCancelable(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density + 0.5f).toInt()

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(StudioUi.palette.card)
                cornerRadius = px(14).toFloat()
                setStroke(px(1), StudioUi.palette.border)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(18), px(20), px(14))
            background = GradientDrawable().apply {
                setColor(StudioUi.palette.surface2)
                cornerRadii = floatArrayOf(
                    px(14).toFloat(), px(14).toFloat(),
                    px(14).toFloat(), px(14).toFloat(),
                    0f, 0f,
                    0f, 0f
                )
            }
        }

        // 버전 배지
        header.addView(TextView(context).apply {
            text  = "v$VERSION_NAME"
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(StudioUi.palette.success)
            background = GradientDrawable().apply {
                setColor(StudioUi.palette.surface2)
                cornerRadius = px(4).toFloat()
                setStroke(px(1), StudioUi.palette.border)
            }
            setPadding(px(8), px(3), px(8), px(4))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = px(10) })

        // 메인 제목
        header.addView(TextView(context).apply {
            text = if (isKorean) "버전 출시" else "What's New"
            textSize = 21f
            setTypeface(null, Typeface.BOLD)
            setTextColor(StudioUi.palette.text)
        })

        // 부제목
        header.addView(TextView(context).apply {
            text = if (isKorean)
                "MobileStudio Android IDE. 버전 출시:"
            else
                "MobileStudio Android IDE. What's New"
            textSize = 12f
            setTextColor(StudioUi.palette.muted)
            setPadding(0, px(4), 0, 0)
        })

        card.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        fun addDivider() = card.addView(View(context).apply {
            setBackgroundColor(StudioUi.palette.divider)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(1)))

        addDivider()

        val tvBody = TextView(context).apply {
            text = renderMarkdown(body)
            textSize = 13.5f
            setTextColor(StudioUi.palette.textSoft)
            setLineSpacing(px(2).toFloat(), 1.1f)
            setPadding(px(20), px(14), px(20), px(14))
        }

        val screenH    = context.resources.displayMetrics.heightPixels
        val maxScrollH = (screenH * 0.42f).toInt()

        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val bounded = MeasureSpec.makeMeasureSpec(maxScrollH, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, bounded)
            }
        }.apply { addView(tvBody) }

        card.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        addDivider()

        val btnArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(12), px(16), px(14))
        }

        btnArea.addView(Button(context).apply {
            text     = if (isKorean) "닫기" else "Close"
            textSize = 14f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(StudioUi.palette.accent)
                cornerRadius = px(9).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, px(46)
            ).apply { bottomMargin = px(8) }
            setOnClickListener { dialog.dismiss() }
        })

        val suppressRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        fun suppressBtn(label: String, days: Long) = Button(context).apply {
            text     = label
            textSize = if (isKorean) 10.5f else 11f
            isAllCaps = false
            setTextColor(StudioUi.palette.muted)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(0, px(38), 1f)
            setOnClickListener {
                prefs.edit()
                    .putLong(KEY_HIDE_UNTIL, System.currentTimeMillis() + days * 86_400_000L)
                    .apply()
                dialog.dismiss()
            }
        }

        fun vSep() = View(context).apply {
            setBackgroundColor(StudioUi.palette.border)
            layoutParams = LinearLayout.LayoutParams(px(1), px(18)).apply {
                gravity = Gravity.CENTER_VERTICAL
                topMargin = px(10)
            }
        }

        if (isKorean) {
            suppressRow.addView(suppressBtn("오늘 보지 않음",      1L))
            suppressRow.addView(vSep())
            suppressRow.addView(suppressBtn("3일동안 보지 않음",     3L))
            suppressRow.addView(vSep())
            suppressRow.addView(suppressBtn("1주동안 보지 않음",  7L))
        } else {
            suppressRow.addView(suppressBtn("Not today",  1L))
            suppressRow.addView(vSep())
            suppressRow.addView(suppressBtn("3 days",     3L))
            suppressRow.addView(vSep())
            suppressRow.addView(suppressBtn("1 week",     7L))
        }

        btnArea.addView(suppressRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        card.addView(btnArea, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        dialog.setContentView(card)
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.88f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.show()
    }

    // Markdown 렌더링 헬퍼
    //
    //  지원 문법:
    //    # 제목      큰 글자 + 굵음 + 밝은 흰색
    //    ## 소제목   중간 글자 + 굵음 + 밝은 흰색
    //    **text**    굵음 + 밝은 흰색
    //    - 불릿      "  •  " 불릿
    //    공백        단락 구분

    private fun renderMarkdown(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()

        fun span(start: Int, vararg spans: Any) {
            spans.forEach { sb.setSpan(it, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }

        fun appendHeading(content: String, sizeMul: Float, colorHex: String = "#DCDFE4") {
            val s = sb.length
            sb.append(content).append("\n")
            span(s,
                StyleSpan(Typeface.BOLD),
                RelativeSizeSpan(sizeMul),
                ForegroundColorSpan(Color.parseColor(colorHex))
            )
        }

        fun appendSubheading(content: String) {
            val s = sb.length
            sb.append(content).append("\n")
            span(s,
                StyleSpan(Typeface.BOLD),
                RelativeSizeSpan(1.05f),
                ForegroundColorSpan(StudioUi.palette.accent)
            )
        }

        fun appendInline(line: String) {
            val boldRx = Regex("""\*\*(.+?)\*\*""")
            var cursor = 0
            for (match in boldRx.findAll(line)) {
                // ??반 ??스???????
                sb.append(line.substring(cursor, match.range.first))
                // **굵게** 부??
                val s = sb.length
                sb.append(match.groupValues[1])
                span(s,
                    StyleSpan(Typeface.BOLD),
                    ForegroundColorSpan(StudioUi.palette.text)
                )
                cursor = match.range.last + 1
            }
            if (cursor < line.length) sb.append(line.substring(cursor))
        }

        for (raw in text.lines()) {
            val t = raw.trim()
            when {
                t.startsWith("# ") -> appendHeading(t.removePrefix("# "), 1.38f)
                t.startsWith("## ") -> appendHeading(t.removePrefix("## "), 1.18f)
                t.startsWith("### ") -> appendSubheading(t.removePrefix("### "))
                t.startsWith("- ") -> {
                    sb.append("  • ")
                    appendInline(t.removePrefix("- "))
                    sb.append("\n")
                }
                t.isEmpty() -> sb.append("\n")
                else -> { appendInline(t); sb.append("\n") }
            }
        }
        return sb
    }
    
    private fun defaultContent() = """
<Korean>
## 26.8.17 Lavender🪻

**MobileStudio 26.8.17 Lavender🪻**

### 빌드 환경

- **Gradle 9.X** 최신 Gradle 9.7.0 및 이전 버전 지원
- **AGP 9.3.0** 최신 Android Gradle Plugin 정렬 완료
- **Windows .EXE 지원** 온디바이스 Windows 바이너리 빌드 및 실행 (Box64/Wine)
- **KTS / Groovy** build.gradle 모든 형식 지원
- **NDK 25~28** C/C++ 네이티브 빌드 지원 (ARM64)
- **CMake / Ninja** 고성능 네이티브 빌드 자동 구성

### JDK (Java Development Kit)

- **JDK 17 (LTS)** Android 개발을 위한 가장 안정적인 버전
- **JDK 21** Gradle 8.5+ 권장 버전. 다운로드 및 실행 오류 해결됨.
- **JDK 22** 최신 Java 기능 지원
- **표준 glibc 통합** OpenCode 호환성 및 실행 안정성 대폭 향상 (aarch64 표준 빌드)
- **Fork Loader** 최신 Android에서 JDK 도구(javac 등) 실행 안정성 강화

### AI 코딩 및 터미널

- **OpenCode AI** 온디바이스 코딩 도우미. "Permission denied" 오류 해결.
- **터미널 개선** `clear` 명령 지원 및 엔터 키로 명령 실행 가능.

### 시각적 도구

- **XML 비주얼 편집기** 실제 핸드폰 프레임 적용, 70% 줌 최적화.
- **양방향 동기화** 시각적 편집 내용이 XML 코드에 즉시 자동 저장됩니다.
- **영역 제한** 컴포넌트가 가상 화면 밖으로 나가지 않도록 자동 조절됩니다.
</Korean>
<English>
## 26.8.17 Lavender🪻

**MobileStudio 26.8.17 Lavender🪻** - Advanced Android IDE.

### Build System

- **Gradle 9.x Support** Now supports the latest Gradle versions including 9.7.0.
- **AGP 9.3.0** Full alignment with the newest Android Gradle Plugin.
- **Windows .EXE Support** Build and run Windows binaries on-device (Box64/Wine).
- **KTS / Groovy** Support for both build.gradle.kts and Groovy formats.
- **NDK 25~28** C/C++ native build support (ARM64).
- **CMake / Ninja** High-performance native build auto-configuration.

### JDK (Java Development Kit)

- **JDK 17 (LTS)** Most stable version for Android development.
- **JDK 21** Recommended for Gradle 8.5+. Fixed download and compatibility issues.
- **JDK 22** Latest Java features available.
- **Standard glibc Integration** Enhanced compatibility and stability for OpenCode (Standard aarch64 build).
- **Fork Loader** Enhanced stability for JDK tools (javac, etc.) on modern Android.

### AI & Terminal

- **OpenCode AI** On-device coding helper. Fixed "Permission denied" errors.
- **Terminal Integration** Now supports the `clear` command and Enter key execution.

### Visual Tools

- **Visual XML Editor** New realistic phone frame, 70% zoom to fit, and bidirectional sync.
- **Live Preview** Real-time XML rendering with auto-save and boundary clamping.
- **Safety Clamping** Components stay safely inside the virtual screen.
</English>
    """.trimIndent()
}