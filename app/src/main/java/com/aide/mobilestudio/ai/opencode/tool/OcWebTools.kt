package com.aide.mobilestudio.ai.opencode.tool

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder

class WebFetchTool : OcTool {
    override val name = "webfetch"

    override fun description(ctx: ToolContext): String =
        OcAssets.read(ctx.androidContext, "ai/prompt/tool/webfetch.txt")
            ?: "Fetches content from a specified URL, converts to requested format (markdown by default)."

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("url", JSONObject().put("type", "string").put("description", "The URL to fetch content from"))
            put("format", JSONObject().put("type", "string").put(
                "description", "The format to return the content in: text, markdown (default), or html",
            ))
            put("timeout", JSONObject().put("type", "integer").put("description", "Optional timeout in seconds (max 120)"))
        })
        put("required", JSONArray().put("url"))
    }

    override fun permissionKey(): String = "webfetch"

    override fun resourcePattern(args: JSONObject, ctx: ToolContext): String = args.optString("url", "")

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.READ_ONLY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        var url = args.optString("url", "")
        val format = args.optString("format", "markdown")
        val timeoutSeconds = args.optLong("timeout", 30L).coerceIn(1L, 120L)
        if (url.isBlank()) return ToolResult("Error: url is required", isError = true)
        if (url.startsWith("http://")) url = url.replaceFirst("http://", "https://")
        if (!url.startsWith("https://")) return ToolResult("Error: URL must be a fully-formed valid URL", isError = true)
        var (content, contentType) = fetch(url, timeoutSeconds * 1000) ?: run {
            return ToolResult("Error: unable to fetch URL: $url", isError = true)
        }
        return when (format) {
            "html" -> {
                if (contentType?.contains("html") != true) {
                    ToolResult(content.take(MAX_CONTENT_CHARS))
                } else {
                    ToolResult(content.take(MAX_CONTENT_CHARS))
                }
            }
            "text" -> ToolResult(HtmlConvert.htmlToText(content).take(MAX_CONTENT_CHARS))
            else -> ToolResult(HtmlConvert.htmlToMarkdown(content).take(MAX_CONTENT_CHARS))
        }
    }

    private fun fetch(url: String, timeoutMs: Long, redirects: Int = 0): Pair<String, String?>? {
        if (redirects > 5) return null
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; MobileStudio OpenCode)")
                setRequestProperty("Accept", "text/html,application/json,*/*")
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: return null
                val next = if (location.startsWith("http")) location else URL(url).toURI().resolve(location).toString()
                conn.disconnect()
                return fetch(next.replaceFirst("http://", "https://"), timeoutMs, redirects + 1)
            }
            if (code !in 200..299) return null
            val type = conn.contentType
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            val buffer = CharArray(8192)
            var read: Int
            var total = 0
            while (reader.read(buffer).also { read = it } > 0) {
                sb.append(buffer, 0, read)
                total += read
                if (total > MAX_RAW_BYTES) break
            }
            sb.toString() to type
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val MAX_CONTENT_CHARS = 100_000
        const val MAX_RAW_BYTES = 2 * 1024 * 1024
    }
}

object HtmlConvert {

    fun htmlToText(html: String): String {
        var text = html
            .replace(Regex("(?is)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?is)<style[\\s\\S]*?</style>"), "")
            .replace(Regex("(?is)<(br|hr)[^>]*>"), "\n")
            .replace(Regex("(?is)</(p|div|h[1-6]|li|tr|section|article|header|footer)>"), "\n")
        text = decodeEntities(text.replace(Regex("<[^>]+>"), ""))
        return text.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun htmlToMarkdown(html: String): String {
        var text = html
            .replace(Regex("(?is)<script[\\s\\S]*?</script>"), "")
            .replace(Regex("(?is)<style[\\s\\S]*?</style>"), "")
        text = text
            .replace(Regex("(?is)<h1[^>]*>([\\s\\S]*?)</h1>"), "# $1\n")
            .replace(Regex("(?is)<h2[^>]*>([\\s\\S]*?)</h2>"), "## $1\n")
            .replace(Regex("(?is)<h3[^>]*>([\\s\\S]*?)</h3>"), "### $1\n")
            .replace(Regex("(?is)<h4[^>]*>([\\s\\S]*?)</h4>"), "#### $1\n")
            .replace(Regex("(?is)<h5[^>]*>([\\s\\S]*?)</h5>"), "##### $1\n")
            .replace(Regex("(?is)<h6[^>]*>([\\s\\S]*?)</h6>"), "###### $1\n")
            .replace(Regex("(?is)<li[^>]*>([\\s\\S]*?)</li>"), "- $1")
            .replace(Regex("(?is)<a\\s[^>]*href=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</a>"), "[$2]($1)")
            .replace(Regex("(?is)<(strong|b)>([\\s\\S]*?)</\\1>"), "**$2**")
            .replace(Regex("(?is)<(em|i)>([\\s\\S]*?)</\\1>"), "*$2*")
            .replace(Regex("(?is)<code[^>]*>([\\s\\S]*?)</code>"), "`$1`")
            .replace(Regex("(?is)<pre[^>]*>([\\s\\S]*?)</pre>"), "\n```\n$1\n```\n")
            .replace(Regex("(?is)<(br|hr)[^>]*>"), "\n")
            .replace(Regex("(?is)</(p|div|h[1-6]|li|tr|section|article|header|footer)>"), "\n")
        text = decodeEntities(text.replace(Regex("<[^>]+>"), ""))
        return text.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun decodeEntities(text: String): String {
        var t = text
        t = t.replace("&" + "amp;", "&")
        t = t.replace("&" + "lt;", "<")
        t = t.replace("&" + "gt;", ">")
        t = t.replace("&" + "quot;", "\"")
        t = t.replace("&" + "#39;", "'")
        t = t.replace("&" + "apos;", "'")
        t = t.replace("&" + "nbsp;", " ")
        return t
    }
}

class WebSearchTool : OcTool {
    override val name = "websearch"

    override fun description(ctx: ToolContext): String {
        val base = OcAssets.read(ctx.androidContext, "ai/prompt/tool/websearch.txt") ?: ""
        return base.replace("{{year}}", java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString())
    }

    override fun parameters(): JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().put("type", "string").put("description", "Websearch query"))
            put("numResults", JSONObject().put("type", "integer").put("description", "Number of search results to return (default: 8, maximum: $MAX_NUM_RESULTS)"))
        })
        put("required", JSONArray().put("query"))
    }

    override fun permissionKey(): String = "websearch"

    override fun riskHint(args: JSONObject, ctx: ToolContext): RiskHint = RiskHint.READ_ONLY

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val query = args.optString("query", "")
        if (query.isBlank()) return ToolResult("Error: query is required", isError = true)
        val numResults = args.optInt("numResults", 8).coerceIn(1, MAX_NUM_RESULTS)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://html.duckduckgo.com/html/?q=$encoded"
        val html = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30000
                readTimeout = 30000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android; MobileStudio OpenCode)")
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
        }.getOrElse { return ToolResult("Error: web search failed: ${it.message}", isError = true) }

        val results = mutableListOf<String>()
        val linkRegex = Regex("(?is)<a[^>]*class=\"[^\"]*result__a[^\"]*\"[^>]*href=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</a>")
        val snippetRegex = Regex("(?is)<a[^>]*class=\"[^\"]*result__snippet[^\"]*\"[^>]*>([\\s\\S]*?)</a>")
        val snippets = snippetRegex.findAll(html).map { HtmlConvert.htmlToText(it.groupValues[1]) }.toList()
        var index = 0
        for (match in linkRegex.findAll(html)) {
            if (results.size >= numResults) break
            var href = match.groupValues[1]
            val title = HtmlConvert.htmlToText(match.groupValues[2])
            if (href.startsWith("//duckduckgo.com/l/") || href.startsWith("/l/")) {
                val uddg = Regex("uddg=([^&]+)").find(href)?.groupValues?.get(1)
                href = uddg?.let { URLDecoder.decode(it, "UTF-8") } ?: continue
            }
            if (title.isBlank() || href.isBlank()) continue
            val snippet = snippets.getOrNull(index) ?: ""
            results.add("${results.size + 1}. $title\n   $href" + if (snippet.isNotBlank()) "\n   $snippet" else "")
            index++
        }
        if (results.isEmpty()) return ToolResult("No search results found for: $query")
        return ToolResult(results.joinToString("\n\n"))
    }

    companion object {
        const val MAX_NUM_RESULTS = 20
    }
}
