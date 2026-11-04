package com.aide.mobilestudio.ai.provider

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Real transport backed by [HttpURLConnection]. Must be called off the main thread. */
class AndroidHttpTransport : HttpTransport {
    override fun send(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        conn.requestMethod = request.method
        conn.connectTimeout = request.timeoutMs
        conn.readTimeout = request.timeoutMs
        for ((k, v) in request.headers) conn.setRequestProperty(k, v)
        if (request.body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(request.body.toByteArray(Charsets.UTF_8).size)
            conn.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() } ?: ""
            HttpResponse(code, body)
        } finally {
            conn.disconnect()
        }
    }
}
