package com.aide.mobilestudio.ai.provider

/** How a provider expects its credential to be attached to a request. */
enum class AuthStyle { BEARER, X_API_KEY, QUERY_KEY, NONE }

/** A provider endpoint the connection tester can validate. */
data class ProviderEndpoint(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val apiKey: String?,
    val model: String?,
    val authStyle: AuthStyle,
)

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutMs: Int,
)

data class HttpResponse(val status: Int, val body: String)

/** Injectable transport so the tester is unit-testable without real network. */
fun interface HttpTransport {
    fun send(request: HttpRequest): HttpResponse
}

data class ConnectionResult(
    val ok: Boolean,
    val status: Int,
    val message: String,
    val latencyMs: Long = 0,
)

/**
 * Validates provider configuration and connection without depending on a real
 * network call in tests. It never logs or returns the API key — only a masked
 * form appears in messages.
 */
object ConnectionTester {

    fun test(endpoint: ProviderEndpoint, transport: HttpTransport, timeoutMs: Int = 15_000): ConnectionResult {
        if (endpoint.authStyle != AuthStyle.NONE && endpoint.apiKey.isNullOrBlank()) {
            return ConnectionResult(false, 0, "Missing API key for ${endpoint.displayName}")
        }
        if (endpoint.baseUrl.isBlank()) {
            return ConnectionResult(false, 0, "Endpoint URL is empty for ${endpoint.displayName}")
        }
        val request = buildRequest(endpoint, timeoutMs)
        val start = System.currentTimeMillis()
        return try {
            val response = transport.send(request)
            interpret(endpoint, response, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            ConnectionResult(false, 0, "Connection failed: ${e.message ?: e.javaClass.simpleName}", System.currentTimeMillis() - start)
        }
    }

    /** Builds the minimal model-listing request for the endpoint's auth style. */
    fun buildRequest(endpoint: ProviderEndpoint, timeoutMs: Int = 15_000): HttpRequest {
        val base = endpoint.baseUrl.trimEnd('/')
        val (url, authHeaders) = when (endpoint.authStyle) {
            AuthStyle.BEARER -> "$base/models" to mapOf("Authorization" to "Bearer ${endpoint.apiKey}")
            AuthStyle.X_API_KEY -> "$base/models" to mapOf(
                "x-api-key" to (endpoint.apiKey ?: ""),
                "anthropic-version" to "2023-06-01",
            )
            AuthStyle.QUERY_KEY -> "$base/models?key=${endpoint.apiKey}" to emptyMap()
            AuthStyle.NONE -> "$base/models" to emptyMap()
        }
        val headers = authHeaders + mapOf("Accept" to "application/json")
        return HttpRequest("GET", url, headers, null, timeoutMs)
    }

    fun interpret(endpoint: ProviderEndpoint, response: HttpResponse, latencyMs: Long): ConnectionResult {
        return when (response.status) {
            200, 201, 204 -> ConnectionResult(true, response.status, "Connected to ${endpoint.displayName} (${latencyMs}ms)", latencyMs)
            400 -> ConnectionResult(false, 400, "Bad request — check the endpoint URL", latencyMs)
            401 -> ConnectionResult(false, 401, "Invalid API key for ${endpoint.displayName}", latencyMs)
            403 -> ConnectionResult(false, 403, "Access forbidden (key lacks permission)", latencyMs)
            404 -> ConnectionResult(false, 404, "Endpoint not found — check the base URL", latencyMs)
            429 -> ConnectionResult(false, 429, "Rate limited — try again later", latencyMs)
            in 500..599 -> ConnectionResult(false, response.status, "Server error (${response.status})", latencyMs)
            else -> if (response.status in 200..299) {
                ConnectionResult(true, response.status, "Connected (HTTP ${response.status})", latencyMs)
            } else {
                ConnectionResult(false, response.status, "Unexpected response (HTTP ${response.status})", latencyMs)
            }
        }
    }

    /** Masks a secret for safe display/logging. */
    fun mask(secret: String?): String = when {
        secret.isNullOrBlank() -> "(none)"
        secret.length <= 6 -> "***"
        else -> secret.take(3) + "…" + secret.takeLast(2)
    }
}
