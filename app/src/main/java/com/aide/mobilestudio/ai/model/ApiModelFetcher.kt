package com.aide.mobilestudio.ai.model

import com.aide.mobilestudio.ai.provider.AuthStyle
import com.aide.mobilestudio.ai.provider.HttpRequest
import com.aide.mobilestudio.ai.provider.HttpTransport
import org.json.JSONObject

/**
 * Live model listing for OpenAI-compatible endpoints: `GET {baseUrl}/models`.
 *
 * Parsing is separate from transport so both are testable without a network.
 * The API key is only ever placed in the Authorization header (never a query
 * string), so it cannot leak through a logged URL.
 */
object ApiModelList {

    /** OpenAI style: `{"object":"list","data":[{"id":"gpt-4o"},…]}`. */
    fun parse(body: String): List<String> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<String>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i)
            val id = when {
                item != null -> item.optString("id").ifBlank { item.optString("name") }
                else -> data.optString(i)
            }
            if (id.isNotBlank()) out.add(id)
        }
        return out
    }

    fun modelsUrl(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.endsWith("/models")) base else "$base/models"
    }
}

class ApiModelFetcher(
    private val transport: HttpTransport,
    private val timeoutMs: Int = 15_000,
) {

    /**
     * Returns the model ids the endpoint reports.
     *
     * @throws ApiModelException on HTTP failure, with the status named but no
     *   key or URL query in the message.
     */
    fun listModels(baseUrl: String, apiKey: String?, authStyle: AuthStyle = AuthStyle.BEARER): List<String> {
        if (baseUrl.isBlank()) throw ApiModelException("Base URL이 비어 있습니다")
        val headers = mutableMapOf("Accept" to "application/json")
        if (!apiKey.isNullOrBlank()) {
            when (authStyle) {
                AuthStyle.BEARER -> headers["Authorization"] = "Bearer $apiKey"
                AuthStyle.X_API_KEY -> headers["x-api-key"] = apiKey
                else -> Unit
            }
        }
        val response = transport.send(HttpRequest("GET", ApiModelList.modelsUrl(baseUrl), headers, null, timeoutMs))
        if (response.status !in 200..299) {
            throw ApiModelException(
                when (response.status) {
                    401, 403 -> "인증 실패 (HTTP ${response.status}) — API 키를 확인하세요"
                    404 -> "모델 목록 API를 찾을 수 없습니다 (HTTP 404)"
                    429 -> "요청 제한 (HTTP 429) — 잠시 후 다시 시도하세요"
                    else -> "모델 목록 조회 실패 (HTTP ${response.status})"
                }
            )
        }
        return ApiModelList.parse(response.body)
    }
}

class ApiModelException(message: String) : Exception(message)
