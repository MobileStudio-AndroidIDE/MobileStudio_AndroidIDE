package com.aide.mobilestudio.ai.model

import java.net.HttpURLConnection
import java.net.URL

/**
 * The real [HttpFetcher]: `HttpURLConnection` with a Range request and a
 * redirect check.
 *
 * Two safety rules:
 *  - the request only starts for URLs on the Hugging Face allow-list, and
 *  - after redirects the *final* host must still be on it, so a redirect
 *    cannot smuggle the download onto another server.
 *
 * No key is attached: model files are public.
 */
class HttpFetcherImpl(
    private val connectTimeoutMs: Int = 20_000,
    private val readTimeoutMs: Int = 60_000,
    private val urlAllowed: (String) -> Boolean = { HuggingFaceApi.isAllowedDownloadUrl(it) },
) : HttpFetcher {

    override fun open(url: String, offset: Long): FetchResponse {
        if (!urlAllowed(url)) return FetchResponse(0)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MobileStudio/1.0 (Android; GGUF downloader)")
            setRequestProperty("Accept", "application/octet-stream,*/*")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val status = conn.responseCode
            if (status != 200 && status != 206) {
                conn.disconnect()
                return FetchResponse(status)
            }
            val finalUrl = conn.url?.toString() ?: url
            if (!urlAllowed(finalUrl)) {
                conn.disconnect()
                return FetchResponse(0)
            }
            val resumed = status == 206
            val total = if (resumed) {
                parseTotalFromContentRange(conn.getHeaderField("Content-Range"))
            } else {
                conn.contentLengthLong
            }
            return FetchResponse(status, total, resumed, conn.inputStream)
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    companion object {
        /** `bytes 1024-2047/4096` → 4096. Unknown (`*`) → -1. */
        fun parseTotalFromContentRange(value: String?): Long {
            if (value == null) return -1L
            val slash = value.lastIndexOf('/')
            if (slash < 0 || slash == value.length - 1) return -1L
            return value.substring(slash + 1).trim().toLongOrNull() ?: -1L
        }
    }
}
