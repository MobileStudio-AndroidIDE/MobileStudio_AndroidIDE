package com.aide.mobilestudio.ai.retry

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException

/**
 * What kind of failure an API call produced.
 *
 * The kind - not the bare HTTP status - decides whether a retry makes sense,
 * because providers reuse the same status for very different situations: a 429
 * can be a per-second limit (retry) or an empty billing account (never).
 */
enum class ApiErrorKind {
    /** Per-request throttle (429 with a limit, not a billing problem). */
    RATE_LIMIT,

    /** Provider-side failure (5xx). */
    SERVER_ERROR,

    /** Connect/DNS/reset failure before a response arrived. */
    NETWORK,

    /** Connect or read timeout. */
    TIMEOUT,

    /** Model is temporarily overloaded - retry a bounded number of times. */
    MODEL_OVERLOADED,

    /** 401 / invalid key. */
    AUTH,

    /** 403 / the key may not use this model. */
    PERMISSION,

    /** The model id does not exist on this provider. */
    MODEL_NOT_FOUND,

    /** Usage or billing limit - retrying cannot help. */
    QUOTA_EXHAUSTED,

    /** Malformed request or unsupported parameters for this model. */
    BAD_REQUEST,

    /** User cancelled the request. */
    CANCELLED,

    /** Anything the classifier could not place; never retried. */
    UNKNOWN,
}

/**
 * A classified API failure.
 *
 * [message] is safe to show in the UI and to log: it has been redacted of key
 * material. [retryAfterMs] is the server's own instruction, parsed from the
 * Retry-After header, and takes precedence over the local backoff schedule.
 *
 * @param uncertainOutcome true when the request may already have reached the
 *   server (the connection died after the body was sent). The retry is still
 *   allowed - no provider here offers idempotency keys - but callers can tell
 *   the user that the repeat may be billed twice.
 */
data class ApiErrorInfo(
    val kind: ApiErrorKind,
    val message: String,
    val status: Int? = null,
    val code: String? = null,
    val retryAfterMs: Long? = null,
    val retryable: Boolean = false,
    val uncertainOutcome: Boolean = false,
) {
    /** Short, user-facing label. Never contains credentials. */
    fun reasonLabel(): String = when (kind) {
        ApiErrorKind.RATE_LIMIT -> "Server request limit reached"
        ApiErrorKind.SERVER_ERROR -> "Provider server error"
        ApiErrorKind.NETWORK -> "Network connection failed"
        ApiErrorKind.TIMEOUT -> "Request timed out"
        ApiErrorKind.MODEL_OVERLOADED -> "Model is overloaded"
        ApiErrorKind.AUTH -> "API key rejected"
        ApiErrorKind.PERMISSION -> "Access to this model is denied"
        ApiErrorKind.MODEL_NOT_FOUND -> "Model not found on this provider"
        ApiErrorKind.QUOTA_EXHAUSTED -> "Usage or billing limit reached"
        ApiErrorKind.BAD_REQUEST -> "Request not accepted"
        ApiErrorKind.CANCELLED -> "Cancelled"
        ApiErrorKind.UNKNOWN -> "Request failed"
    }

    /** One-line detail for a chat bubble; keeps the provider's own wording. */
    fun detail(): String {
        val base = message.trim().ifEmpty { reasonLabel() }
        val withStatus = if (status != null && !base.contains("HTTP $status")) "HTTP $status · $base" else base
        return ApiErrorClassifier.redact(withStatus).take(400)
    }
}

/** Classifies failures from status/headers/body/exception. Pure: JVM unit-testable. */
object ApiErrorClassifier {

    /** Definitive "your account cannot pay for this" - never retried. */
    private val BILLING_MARKERS = listOf(
        "insufficient_quota",
        "exceeded your current quota",
        "check your plan and billing",
        "credit balance is too low",
        "billing",
        "payment required",
        "out of credit",
    )

    /** Usage/metering limits that are not a momentary throttle. */
    private val QUOTA_MARKERS = listOf(
        "quota exceeded for quota metric",
        "quota exceeded for this account",
        "quota exhausted",
        "exceeded the quota",
        "daily limit exceeded",
    )

    private val RATE_MARKERS = listOf(
        "rate limit",
        "rate_limit",
        "too many requests",
        "per-minute",
        "per_minute",
        "per second",
        "requests per",
        "rescue",
        "slow down",
        "please retry after",
        "please try again in",
        "resource has been exhausted",
    )

    private val MODEL_NOT_FOUND_MARKERS = listOf(
        "model_not_found",
        "does not exist",
        "no such model",
        "model not found",
        "unknown model",
        "invalid model",
        "is not a valid model",
        "no endpoints found that support",
        "model_decommissioned",
    )

    private val PERMISSION_MARKERS = listOf(
        "permission_denied",
        "insufficient permissions",
        "access denied",
        "not allowed to",
        "do not have access",
        "lacks permission",
        "permission to access",
        "is gated",
        "requires access approval",
        "user does not have",
        "api key not valid", // Gemini returns 400 for a bad key
    )

    private val OVERLOADED_MARKERS = listOf(
        "overloaded",
        "model is currently overloaded",
        "server is busy",
        "capacity",
        "temporarily unavailable",
        "try again later",
        "engine is overloaded",
    )

    /**
     * Markers checked before the status codes, because a rejected request must
     * never be replayed - not even when the provider wraps it in a 5xx.
     */
    private val PARAM_MARKERS = listOf(
        "context_length_exceeded",
        "maximum context length",
        "prompt is too long",
        "input is too long",
        "unsupported parameter",
        "unknown parameter",
        "invalid_request_error",
        "messages must contain at least one",
    )

    /** Tokens that look like credentials and must never reach the UI or a log. */
    private val SECRET_PATTERNS = listOf(
        Regex("""(?i)\b(bearer|key|api[_-]?key|token|authorization)\s*[=:]\s*["']?[^\s"',&]+"""),
        Regex("""(?i)\bnvapi-[A-Za-z0-9._-]+"""),
        Regex("""(?i)\bsk-[A-Za-z0-9._-]{8,}"""),
        Regex("""(?i)\bsk-ant-[A-Za-z0-9._-]{8,}"""),
        Regex("""(?i)\bAIza[A-Za-z0-9_\-]{20,}"""),
        Regex("""(?i)[?&]key=[^&\s]+"""),
    )

    fun redact(text: String): String {
        var out = text
        for (p in SECRET_PATTERNS) {
            out = p.replace(out) { m ->
                val raw = m.value
                val sep = raw.indexOfFirst { it == ':' || it == '=' }
                if (sep > 0) raw.substring(0, sep + 1) + "***" else "***"
            }
        }
        return out
    }

    /**
     * Parses a `Retry-After` header.
     *
     * Both RFC 7231 forms are handled: delay-seconds (`120`) and an HTTP-date
     * (`Wed, 21 Oct 2015 07:28:00 GMT`). Returns milliseconds until that
     * moment (never negative), or null when the header is absent/unparsable.
     */
    fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        raw.toLongOrNull()?.let { seconds -> return (seconds * 1000L).coerceAtLeast(0L) }
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy",
        )
        for (pattern in formats) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            val parsed = runCatching { parser.parse(raw) }.getOrNull() ?: continue
            return (parsed.time - nowMs).coerceAtLeast(0L)
        }
        return null
    }

    fun fromHttpResponse(
        status: Int,
        headers: Map<String, String>,
        body: String?,
        requestSent: Boolean = true,
    ): ApiErrorInfo {
        val headerKeys = headers.keys.associateBy { it.lowercase(Locale.US) }
        val retryAfter = headerKeys["retry-after"]?.let { headers[it] }
        val retryAfterMs = parseRetryAfter(retryAfter)
        val (code, providerMessage) = parseBody(body)
        val lower = (providerMessage + " " + (code ?: "")).lowercase(Locale.US)
        val billing = containsAny(lower, BILLING_MARKERS)
        val quotaish = containsAny(lower, QUOTA_MARKERS)
        val rateish = containsAny(lower, RATE_MARKERS)
        // A rejected request must never be replayed, but an auth/rate/model
        // failure wearing the same "invalid_request_error" type must still be
        // read as what it is - so those statuses are excluded up front.
        val paramish = status !in 200..299 && status != 401 && status != 403 &&
            status != 404 && status != 429 && containsAny(lower, PARAM_MARKERS)

        val kind: ApiErrorKind
        val retryable: Boolean
        when {
            paramish -> {
                kind = ApiErrorKind.BAD_REQUEST
                retryable = false
            }
            status == 500 || status == 502 || status == 503 || status == 504 || status in 505..599 -> {
                kind = if (containsAny(lower, OVERLOADED_MARKERS)) ApiErrorKind.MODEL_OVERLOADED else ApiErrorKind.SERVER_ERROR
                retryable = true
            }
            billing -> {
                kind = ApiErrorKind.QUOTA_EXHAUSTED
                retryable = false
            }
            status == 429 && (rateish || !quotaish) -> {
                // A per-second limit wears the same 429 as an empty account.
                // Rate wording (or no quota wording at all) means throttle.
                kind = ApiErrorKind.RATE_LIMIT
                retryable = true
            }
            status == 429 -> {
                // Metering quota (e.g. "Quota exceeded for quota metric ...")
                // without any rate wording: the window has really run out.
                kind = ApiErrorKind.QUOTA_EXHAUSTED
                retryable = false
            }
            containsAny(lower, MODEL_NOT_FOUND_MARKERS) -> {
                kind = ApiErrorKind.MODEL_NOT_FOUND
                retryable = false
            }
            containsAny(lower, PERMISSION_MARKERS) -> {
                kind = ApiErrorKind.PERMISSION
                retryable = false
            }
            status == 401 -> {
                kind = ApiErrorKind.AUTH
                retryable = false
            }
            status == 403 -> {
                kind = ApiErrorKind.PERMISSION
                retryable = false
            }
            status == 404 -> {
                kind = ApiErrorKind.MODEL_NOT_FOUND
                retryable = false
            }
            status == 402 -> {
                kind = ApiErrorKind.QUOTA_EXHAUSTED
                retryable = false
            }
            status == 408 || status == 425 -> {
                kind = ApiErrorKind.TIMEOUT
                retryable = true
            }
            status == 429 -> {
                kind = ApiErrorKind.RATE_LIMIT
                retryable = true
            }
            status == 400 || status == 413 || status == 422 -> {
                kind = if (containsAny(lower, OVERLOADED_MARKERS)) ApiErrorKind.MODEL_OVERLOADED else ApiErrorKind.BAD_REQUEST
                retryable = kind == ApiErrorKind.MODEL_OVERLOADED
            }
            status in 200..299 -> {
                kind = ApiErrorKind.UNKNOWN
                retryable = false
            }
            else -> {
                kind = ApiErrorKind.UNKNOWN
                retryable = false
            }
        }

        val message = providerMessage.ifBlank {
            when (kind) {
                ApiErrorKind.RATE_LIMIT -> "Rate limited by the provider"
                ApiErrorKind.SERVER_ERROR -> "Provider returned HTTP $status"
                else -> "Provider returned HTTP $status"
            }
        }
        return ApiErrorInfo(
            kind = kind,
            message = redact(message).take(600),
            status = status,
            code = code,
            retryAfterMs = retryAfterMs,
            retryable = retryable,
            // A response status proves the server answered, so nothing is ambiguous.
            uncertainOutcome = false,
        )
    }

    fun fromThrowable(t: Throwable, requestSent: Boolean): ApiErrorInfo {
        if (t is java.util.concurrent.CancellationException || t is kotlinx.coroutines.CancellationException) {
            return ApiErrorInfo(ApiErrorKind.CANCELLED, "Request cancelled", retryable = false)
        }
        val kind = when (t) {
            is SocketTimeoutException -> ApiErrorKind.TIMEOUT
            is InterruptedIOException -> ApiErrorKind.TIMEOUT
            is UnknownHostException -> ApiErrorKind.NETWORK
            is ConnectException -> ApiErrorKind.NETWORK
            is NoRouteToHostException -> ApiErrorKind.NETWORK
            is PortUnreachableException -> ApiErrorKind.NETWORK
            is SSLException -> ApiErrorKind.NETWORK
            is SocketException -> ApiErrorKind.NETWORK
            is IOException -> ApiErrorKind.NETWORK
            else -> ApiErrorKind.UNKNOWN
        }
        val retryable = kind == ApiErrorKind.TIMEOUT || kind == ApiErrorKind.NETWORK
        val reason = when (kind) {
            ApiErrorKind.TIMEOUT -> "The provider did not answer in time"
            ApiErrorKind.NETWORK -> "Could not reach the provider"
            else -> t.javaClass.simpleName.ifEmpty { "Request failed" }
        }
        return ApiErrorInfo(
            kind = kind,
            message = redact("$reason (${t.javaClass.simpleName})").take(600),
            retryable = retryable,
            // A connect/DNS failure means the body never left the device, so a
            // repeat cannot double-bill. Once we were writing or reading the
            // response the server may already have done the work.
            uncertainOutcome = requestSent && kind == ApiErrorKind.NETWORK && t !is ConnectException &&
                t !is UnknownHostException && t !is NoRouteToHostException,
        )
    }

    /** Extracts (code, message) from the common provider error bodies. */
    fun parseBody(body: String?): Pair<String?, String> {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null to ""
        val json = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return null to text.take(400)
        val error = json.optJSONObject("error")
        // Prefer the provider's semantic status over a numeric code: Gemini
        // reports {"code":400,"status":"API_KEY_INVALID"} and the status is
        // the part that says what actually went wrong.
        fun firstText(vararg keys: String): String? {
            for (key in keys) {
                val holder = if (error != null && error.has(key) && !error.isNull(key)) error else json
                if (!holder.has(key) || holder.isNull(key)) continue
                val value = holder.optString(key).trim()
                if (value.isNotEmpty() && value != "0") return value
            }
            return null
        }
        val code = firstText("status", "type", "code")
        val message = when {
            error != null && error.has("message") -> error.optString("message")
            json.has("message") -> json.optString("message")
            json.has("error_description") -> json.optString("error_description")
            else -> text.take(400)
        }
        return code to message.trim()
    }

    private fun containsAny(lower: String, markers: List<String>): Boolean = markers.any { lower.contains(it) }

    /** Convenience for streams that only have an HTTP status code. */
    fun fromStatusOnly(status: Int): ApiErrorInfo = fromHttpResponse(status, emptyMap(), null)

    /**
     * Classifies a bare message from a provider that did not hand us a status
     * (older clients emit `StreamEvent.Error(message)` and nothing else).
     *
     * An embedded "HTTP 4xx/5xx" is recovered first; otherwise only the body
     * markers are consulted. When they say nothing definitive the result is
     * [ApiErrorKind.UNKNOWN] and NOT retryable - guessing "transient" is how a
     * broken key turns into three pointless retries.
     */
    fun fromMessage(rawMessage: String): ApiErrorInfo {
        val text = redact(rawMessage.trim())
        if (text.isEmpty()) {
            return ApiErrorInfo(ApiErrorKind.UNKNOWN, "Request failed", retryable = false)
        }
        val embeddedStatus = Regex("""\bHTTP\s+(\d{3})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (embeddedStatus != null) {
            return fromHttpResponse(embeddedStatus, emptyMap(), text, requestSent = true)
        }
        val (code, message) = parseBody(text)
        val lower = (message + " " + (code ?: "")).lowercase(Locale.US)
        val kind = when {
            containsAny(lower, BILLING_MARKERS) -> ApiErrorKind.QUOTA_EXHAUSTED to false
            containsAny(lower, RATE_MARKERS) -> ApiErrorKind.RATE_LIMIT to true
            containsAny(lower, QUOTA_MARKERS) -> ApiErrorKind.QUOTA_EXHAUSTED to false
            containsAny(lower, MODEL_NOT_FOUND_MARKERS) -> ApiErrorKind.MODEL_NOT_FOUND to false
            containsAny(lower, PERMISSION_MARKERS) -> ApiErrorKind.PERMISSION to false
            containsAny(lower, PARAM_MARKERS) -> ApiErrorKind.BAD_REQUEST to false
            containsAny(lower, OVERLOADED_MARKERS) -> ApiErrorKind.MODEL_OVERLOADED to true
            text.contains("timed out", ignoreCase = true) ||
                text.contains("timeout", ignoreCase = true) -> ApiErrorKind.TIMEOUT to true
            text.contains("unable to resolve host", ignoreCase = true) ||
                text.contains("failed to connect", ignoreCase = true) ||
                text.contains("connection refused", ignoreCase = true) ||
                text.contains("network is unreachable", ignoreCase = true) -> ApiErrorKind.NETWORK to true
            else -> ApiErrorKind.UNKNOWN to false
        }
        return ApiErrorInfo(
            kind = kind.first,
            message = message.ifBlank { text }.take(400),
            code = code,
            retryable = kind.second,
        )
    }
}

/** True for exceptions that mean "this machine has no usable path to the server". */
fun Throwable.isConnectivityFailure(): Boolean =
    this is UnknownHostException || this is ConnectException || this is SocketTimeoutException ||
        this is NoRouteToHostException || this is SSLException

/** Kept for callers that only have an [HttpURLConnection]; status codes map to a kind. */
fun Int.isRetryableHttpStatus(): Boolean = this == 408 || this == 429 || this in 500..599
