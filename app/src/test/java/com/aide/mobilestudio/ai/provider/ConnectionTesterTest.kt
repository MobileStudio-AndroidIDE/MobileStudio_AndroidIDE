package com.aide.mobilestudio.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTesterTest {

    private fun endpoint(
        id: String = "openai",
        base: String = "https://api.openai.com/v1",
        key: String? = "sk-abcdef123456",
        style: AuthStyle = AuthStyle.BEARER,
    ) = ProviderEndpoint(id, id, base, key, "gpt-4o", style)

    @Test
    fun `missing key fails without a request`() {
        val result = ConnectionTester.test(endpoint(key = null), HttpTransport { error("must not be called") })
        assertFalse(result.ok)
        assertTrue(result.message.contains("Missing API key"))
    }

    @Test
    fun `empty url fails`() {
        val result = ConnectionTester.test(endpoint(base = ""), HttpTransport { error("must not be called") })
        assertFalse(result.ok)
    }

    @Test
    fun `bearer request carries authorization header`() {
        val req = ConnectionTester.buildRequest(endpoint(style = AuthStyle.BEARER))
        assertEquals("GET", req.method)
        assertEquals("https://api.openai.com/v1/models", req.url)
        assertTrue(req.headers["Authorization"]!!.startsWith("Bearer "))
    }

    @Test
    fun `query key request embeds key in url`() {
        val req = ConnectionTester.buildRequest(endpoint(style = AuthStyle.QUERY_KEY, base = "https://generativelanguage.googleapis.com/v1beta"))
        assertTrue(req.url.contains("?key=sk-abcdef123456"))
    }

    @Test
    fun `x-api-key request uses anthropic headers`() {
        val req = ConnectionTester.buildRequest(endpoint(style = AuthStyle.X_API_KEY, base = "https://api.anthropic.com/v1"))
        assertEquals("sk-abcdef123456", req.headers["x-api-key"])
        assertTrue(req.headers.containsKey("anthropic-version"))
    }

    @Test
    fun `http 200 is a success`() {
        val result = ConnectionTester.test(endpoint(), HttpTransport { HttpResponse(200, "{}") })
        assertTrue(result.ok)
        assertEquals(200, result.status)
    }

    @Test
    fun `http 401 reports invalid key without leaking it`() {
        val result = ConnectionTester.test(endpoint(), HttpTransport { HttpResponse(401, "unauthorized") })
        assertFalse(result.ok)
        assertTrue(result.message.contains("Invalid API key"))
        assertFalse(result.message.contains("sk-abcdef123456"))
    }

    @Test
    fun `http 429 reports rate limit`() {
        val result = ConnectionTester.test(endpoint(), HttpTransport { HttpResponse(429, "") })
        assertFalse(result.ok)
        assertEquals(429, result.status)
    }

    @Test
    fun `http 500 reports server error`() {
        val result = ConnectionTester.test(endpoint(), HttpTransport { HttpResponse(500, "") })
        assertFalse(result.ok)
        assertTrue(result.message.contains("Server error"))
    }

    @Test
    fun `transport exception is captured`() {
        val result = ConnectionTester.test(endpoint(), HttpTransport { throw java.io.IOException("no network") })
        assertFalse(result.ok)
        assertTrue(result.message.contains("Connection failed"))
    }

    @Test
    fun `mask hides the middle of a secret`() {
        assertEquals("(none)", ConnectionTester.mask(null))
        assertEquals("***", ConnectionTester.mask("short"))
        val masked = ConnectionTester.mask("sk-abcdef123456")
        assertTrue(masked.startsWith("sk-"))
        assertFalse(masked.contains("abcdef"))
    }
}
