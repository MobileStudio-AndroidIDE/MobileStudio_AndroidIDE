package com.aide.mobilestudio.ai.retry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat

class ApiErrorClassifierTest {

    private fun body(status: Int, json: String, headers: Map<String, String> = emptyMap()) =
        ApiErrorClassifier.fromHttpResponse(status, headers, json)

    // ------------------------------------------------------------ retryable

    @Test
    fun `429 with retry-after seconds is a retryable rate limit`() {
        val info = body(
            429,
            """{"error":{"message":"Rate limited","type":"requests"}}""",
            mapOf("Retry-After" to "3"),
        )
        assertEquals(ApiErrorKind.RATE_LIMIT, info.kind)
        assertTrue(info.retryable)
        assertEquals(3000L, info.retryAfterMs)
    }

    @Test
    fun `429 with retry-after http date is parsed against now`() {
        val now = System.currentTimeMillis()
        val then = now + 5_000
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val header = format.format(java.util.Date(then))
        val info = body(429, "{}", mapOf("Retry-After" to header))
        val parsed = info.retryAfterMs
        assertNotNull(parsed)
        // One second of slop covers formatting to whole seconds.
        assertTrue("retryAfter=$parsed", parsed!! in 3_500..6_500)
    }

    @Test
    fun `unparsable retry-after yields null not a guess`() {
        assertNull(ApiErrorClassifier.parseRetryAfter("soon"))
        assertNull(ApiErrorClassifier.parseRetryAfter(null))
        assertNull(body(500, "{}").retryAfterMs)
    }

    @Test
    fun `503 is retryable server error`() {
        val info = body(503, """{"error":{"message":"Service unavailable"}}""")
        assertEquals(ApiErrorKind.SERVER_ERROR, info.kind)
        assertTrue(info.retryable)
    }

    @Test
    fun `503 overloaded model is retryable overload`() {
        val info = body(503, """{"error":{"message":"The model is overloaded right now"}}""")
        assertEquals(ApiErrorKind.MODEL_OVERLOADED, info.kind)
        assertTrue(info.retryable)
    }

    @Test
    fun `500 502 and 504 are retryable`() {
        for (status in listOf(500, 502, 504)) {
            assertTrue("status $status", body(status, "").retryable)
        }
    }

    @Test
    fun `connect failure is retryable and certain`() {
        val info = ApiErrorClassifier.fromThrowable(ConnectException("Connection refused"), requestSent = true)
        assertEquals(ApiErrorKind.NETWORK, info.kind)
        assertTrue(info.retryable)
        assertFalse("connect never sent the body", info.uncertainOutcome)
    }

    @Test
    fun `dns failure is retryable`() {
        val info = ApiErrorClassifier.fromThrowable(UnknownHostException("api.example.com"), requestSent = true)
        assertEquals(ApiErrorKind.NETWORK, info.kind)
        assertTrue(info.retryable)
    }

    @Test
    fun `read timeout is retryable`() {
        val info = ApiErrorClassifier.fromThrowable(SocketTimeoutException("read timed out"), requestSent = true)
        assertEquals(ApiErrorKind.TIMEOUT, info.kind)
        assertTrue(info.retryable)
    }

    // ---------------------------------------------------------- never retry

    @Test
    fun `401 never retries`() {
        val info = body(401, """{"error":{"message":"Incorrect API key provided"}}""")
        assertEquals(ApiErrorKind.AUTH, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `403 never retries`() {
        val info = body(403, "")
        assertEquals(ApiErrorKind.PERMISSION, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `billing quota never retries even with 429`() {
        val info = body(
            429,
            """{"error":{"message":"You exceeded your current quota, please check your plan and billing details.","code":"insufficient_quota"}}""",
        )
        assertEquals(ApiErrorKind.QUOTA_EXHAUSTED, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `metering quota without rate wording never retries`() {
        val info = body(
            429,
            """{"error":{"code":"RESOURCE_EXHAUSTED","message":"Quota exceeded for quota metric 'Generate Content requests' and quota limit 'GenerateContentRequestsPerDay'","status":"RESOURCE_EXHAUSTED"}}""",
        )
        assertEquals(ApiErrorKind.QUOTA_EXHAUSTED, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `per-minute throttle with quota wording still retries`() {
        val info = body(
            429,
            """{"error":{"code":"RESOURCE_EXHAUSTED","message":"Quota exceeded for quota metric 'requests' - please try again in 30s"}}""",
        )
        assertEquals(ApiErrorKind.RATE_LIMIT, info.kind)
        assertTrue(info.retryable)
    }

    @Test
    fun `missing model never retries`() {
        val info = body(404, """{"error":{"message":"The model 'gpt-9' does not exist"}}""")
        assertEquals(ApiErrorKind.MODEL_NOT_FOUND, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `model access denied is a permission error`() {
        val info = body(403, """{"error":{"message":"You do not have access to this model"}}""")
        assertEquals(ApiErrorKind.PERMISSION, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `context overflow is a bad request not a transient failure`() {
        val info = body(400, """{"error":{"message":"This model's maximum context length is 8192 tokens","type":"context_length_exceeded"}}""")
        assertEquals(ApiErrorKind.BAD_REQUEST, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `context overflow wrapped in 500 still does not retry`() {
        val info = body(500, """{"error":{"message":"context_length_exceeded: prompt is too long"}}""")
        assertEquals(ApiErrorKind.BAD_REQUEST, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `unknown status is not retried`() {
        val info = body(418, "teapot")
        assertEquals(ApiErrorKind.UNKNOWN, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `cancellation is never retryable`() {
        val info = ApiErrorClassifier.fromThrowable(
            java.util.concurrent.CancellationException("user"),
            requestSent = true,
        )
        assertEquals(ApiErrorKind.CANCELLED, info.kind)
        assertFalse(info.retryable)
    }

    // -------------------------------------------------------------- messages

    @Test
    fun `legacy message with embedded http status is classified`() {
        val info = ApiErrorClassifier.fromMessage("Rate limited - too many requests\nHTTP 429")
        assertEquals(ApiErrorKind.RATE_LIMIT, info.kind)
        assertTrue(info.retryable)
    }

    @Test
    fun `legacy message with nothing to go on does not retry`() {
        val info = ApiErrorClassifier.fromMessage("something went sideways")
        assertEquals(ApiErrorKind.UNKNOWN, info.kind)
        assertFalse(info.retryable)
    }

    @Test
    fun `billing wording in a bare message is not retried`() {
        val info = ApiErrorClassifier.fromMessage("You exceeded your current quota, please check your plan and billing details.")
        assertEquals(ApiErrorKind.QUOTA_EXHAUSTED, info.kind)
        assertFalse(info.retryable)
    }

    // ---------------------------------------------------------------- secrets

    @Test
    fun `api keys are stripped from messages`() {
        val raw = """{"error":{"message":"Invalid key Authorization: Bearer nvapi-abcdef1234567890"}}"""
        val info = body(401, raw)
        assertFalse(info.message.contains("nvapi-abcdef1234567890"))
        assertFalse(ApiErrorClassifier.redact("https://x/?key=AIzaSyABCDEFG1234567890").contains("AIzaSyABCDEFG"))
        assertFalse(ApiErrorClassifier.redact("Authorization: Bearer sk-abc123def456").contains("sk-abc123def456"))
    }

    @Test
    fun `detail is bounded and carries the status`() {
        val info = body(500, """{"error":{"message":"boom"}}""")
        assertTrue(info.detail().contains("HTTP 500"))
        assertTrue(info.detail().length <= 420)
    }
}
