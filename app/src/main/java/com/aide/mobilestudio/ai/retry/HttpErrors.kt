package com.aide.mobilestudio.ai.retry

import java.net.HttpURLConnection

/**
 * HttpURLConnection helpers shared by every remote AI provider: consistent
 * timeouts and one place that turns a non-2xx response into a classified
 * [ApiErrorInfo] (status + headers + Retry-After + body).
 *
 * Providers previously read `conn.inputStream` straight away, which throws for
 * 4xx/5xx and left the engine with nothing but
 * "Server returned HTTP response code: 400 for URL: ...", i.e. no status, no
 * Retry-After and no way to tell a throttle from a dead key.
 */
object HttpErrors {

    /** Time to establish the TCP/TLS connection. */
    const val CONNECT_TIMEOUT_MS = 20_000

    /** Idle time between reads while a stream is arriving. */
    const val READ_TIMEOUT_MS = 120_000

    fun applyTimeouts(conn: HttpURLConnection, connectMs: Int = CONNECT_TIMEOUT_MS, readMs: Int = READ_TIMEOUT_MS) {
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
    }

    /**
     * Reads the response status of [conn] after the request body was written.
     *
     * @return null when the response is 2xx (the caller may start streaming),
     * otherwise the classified failure. Never throws.
     */
    fun failureOf(conn: HttpURLConnection): ApiErrorInfo? {
        val status = try {
            conn.responseCode
        } catch (t: Throwable) {
            return ApiErrorClassifier.fromThrowable(t, requestSent = true)
        }
        if (status in 200..299) return null
        val headers = headersOf(conn)
        val body = runCatching {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }.getOrNull()
        return ApiErrorClassifier.fromHttpResponse(status, headers, body, requestSent = true)
    }

    fun headersOf(conn: HttpURLConnection): Map<String, String> {
        val raw = runCatching { conn.headerFields }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((key, values) in raw) {
            if (key == null) continue
            val first = values?.firstOrNull() ?: continue
            out[key] = first
        }
        return out
    }
}
