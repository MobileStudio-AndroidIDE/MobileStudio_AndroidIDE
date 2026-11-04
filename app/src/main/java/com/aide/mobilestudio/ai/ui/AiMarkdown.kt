package com.aide.mobilestudio.ai.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import com.aide.mobilestudio.StudioUi

/**
 * Lightweight markdown-ish renderer for assistant messages: fenced code blocks,
 * inline code, **bold**, `#` headings and `-` bullets. Deliberately dependency
 * free (spans only) and never translates its content.
 */
object AiMarkdown {

    fun render(context: Context, text: String, baseColor: Int = StudioUi.palette.text): CharSequence {
        val out = SpannableStringBuilder()
        val codeBg = AiUiKit.withAlpha(StudioUi.palette.text, 0.08f)
        val codeFg = StudioUi.palette.info
        val lines = text.split("\n")
        var inFence = false

        for ((index, rawLine) in lines.withIndex()) {
            val line = rawLine
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                // Render the fence marker as a subtle language label instead of raw backticks.
                val lang = line.trim().removePrefix("```").trim()
                if (inFence && lang.isNotEmpty()) {
                    val start = out.length
                    out.append(lang).append("  ")
                    out.setSpan(ForegroundColorSpan(StudioUi.palette.muted), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(RelativeSizeSpan(0.85f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (!inFence) out.append("\n")
                continue
            }

            val lineStart = out.length
            if (inFence) {
                out.append(line).append("\n")
                out.setSpan(TypefaceSpan("monospace"), lineStart, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(ForegroundColorSpan(codeFg), lineStart, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(BackgroundColorSpan(codeBg), lineStart, out.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                appendInline(out, line, baseColor)
                if (index != lines.lastIndex) out.append("\n")
            }
        }
        return out
    }

    private fun appendInline(out: SpannableStringBuilder, line: String, baseColor: Int) {
        // Headings
        if (line.startsWith("### ")) { appendStyled(out, line.removePrefix("### "), baseColor, 1.15f, true); return }
        if (line.startsWith("## ")) { appendStyled(out, line.removePrefix("## "), baseColor, 1.25f, true); return }
        if (line.startsWith("# ")) { appendStyled(out, line.removePrefix("# "), baseColor, 1.35f, true); return }
        if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
            out.append("  • ")
            inline(out, line.trimStart().substring(2), baseColor)
            return
        }
        inline(out, line, baseColor)
    }

    private fun appendStyled(out: SpannableStringBuilder, text: String, color: Int, size: Float, bold: Boolean) {
        val start = out.length
        out.append(text)
        out.setSpan(RelativeSizeSpan(size), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** Handles inline `code` and **bold** within a single line. */
    private fun inline(out: SpannableStringBuilder, text: String, baseColor: Int) {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        val start = out.length
                        out.append(text.substring(i + 1, end))
                        out.setSpan(TypefaceSpan("monospace"), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(AiUiKit.withAlpha(StudioUi.palette.text, 0.08f)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(StudioUi.palette.info), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    } else {
                        out.append(text.substring(i)); i = text.length
                    }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) {
                        val start = out.length
                        out.append(text.substring(i + 2, end))
                        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    } else {
                        out.append(text.substring(i)); i = text.length
                    }
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
    }
}
