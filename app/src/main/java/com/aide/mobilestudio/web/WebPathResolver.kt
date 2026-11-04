package com.aide.mobilestudio.web

import java.io.File

/**
 * Pure (JVM-only) resolver that maps URLs referenced by a local HTML file onto files in the
 * same directory tree. Used by [HtmlPreviewActivity]'s `WebViewClient.shouldInterceptRequest`
 * to serve `https://mobilestudio.local/<relative-path>` from disk.
 *
 * Security rules:
 *  - absolute (`http:`, `https:`, `data:`, `blob:`, `javascript:`, `mailto:`, protocol-relative)
 *    URLs are never resolved — remote content stays blocked by the interceptor;
 *  - the canonical path of the result must stay inside [htmlDir] (`..` escaping is rejected);
 *  - query strings and fragments are stripped, percent-escapes are decoded (UTF-8).
 */
object WebPathResolver {

    /** Virtual host all local preview traffic is served from. */
    const val LOCAL_HOST = "mobilestudio.local"

    /** Base URL used to load the main document; relative links resolve against it. */
    const val BASE_URL = "https://$LOCAL_HOST/index.html"

    /**
     * Maps [rawUrl] (as written in the HTML) onto a [File] under [htmlDir], or null when the
     * URL must not be served from disk. The returned file is canonical; existence is checked
     * by the caller (this function is pure path math).
     */
    fun resolveFile(htmlDir: File, rawUrl: String): File? = try {
        val path = rawUrl.trim()
        when {
            path.isEmpty() -> null
            path.startsWith("//") -> null // protocol-relative → remote
            path.contains("://") -> null
            SCHEMES.any { path.startsWith(it) } -> null
            else -> {
                var rel = path.substringBefore('#').substringBefore('?')
                if (rel.startsWith("/")) rel = rel.substring(1) // root-relative → htmlDir itself
                rel = percentDecode(rel)
                if (rel.isEmpty()) {
                    null
                } else {
                    val root = htmlDir.canonicalFile
                    val candidate = File(root, rel).canonicalFile
                    // The canonical path must stay inside the html dir (path-escape guard).
                    if (candidate.path == root.path ||
                        !candidate.path.startsWith(root.path + File.separator)
                    ) null else candidate
                }
            }
        }
    } catch (_: Exception) {
        null
    }

    /** True when the (absolute) URL should be served by the local interceptor. */
    fun isLocalUrl(url: String): Boolean =
        url.startsWith("https://$LOCAL_HOST/") || url == "https://$LOCAL_HOST"

    /**
     * Decodes %XX escapes as UTF-8 bytes. Unlike `URLDecoder` this leaves '+' untouched,
     * which is correct for path components.
     */
    fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        val buf = java.io.ByteArrayOutputStream()
        fun flushBuf() {
            if (buf.size() > 0) {
                out.append(String(buf.toByteArray(), Charsets.UTF_8))
                buf.reset()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = s[i + 1].digitToIntOrNull(16)
                val lo = s[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    buf.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            if (c.code < 128) buf.write(c.code) else { flushBuf(); out.append(c) }
            i++
        }
        flushBuf()
        return out.toString()
    }

    private val SCHEMES = listOf("data:", "blob:", "javascript:", "mailto:", "about:", "tel:", "file:")

    /** Content type the local interceptor serves for a file path. */
    fun mimeTypeFor(path: String): String {
        val name = path.substringAfterLast('/').lowercase()
        return when {
            name.endsWith(".html") || name.endsWith(".htm") -> "text/html"
            name.endsWith(".css") -> "text/css"
            name.endsWith(".js") || name.endsWith(".mjs") -> "text/javascript"
            name.endsWith(".json") -> "application/json"
            name.endsWith(".svg") -> "image/svg+xml"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".webp") -> "image/webp"
            name.endsWith(".gif") -> "image/gif"
            name.endsWith(".ico") -> "image/x-icon"
            name.endsWith(".woff") -> "font/woff"
            name.endsWith(".woff2") -> "font/woff2"
            name.endsWith(".ttf") -> "font/ttf"
            name.endsWith(".otf") -> "font/otf"
            name.endsWith(".txt") || name.endsWith(".md") -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}
