package com.aide.mobilestudio.ai.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelListTest {

    @Test
    fun `parses openai style data array`() {
        val body = """{"object":"list","data":[{"id":"gpt-4o"},{"id":"gpt-4o-mini"}]}"""
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), ProviderModelList.parse("openai", body))
    }

    @Test
    fun `parses gemini style models array and strips prefix`() {
        val body = """{"models":[{"name":"models/gemini-2.0-flash"},{"name":"models/gemini-1.5-pro"}]}"""
        assertEquals(listOf("gemini-2.0-flash", "gemini-1.5-pro"), ProviderModelList.parse("gemini", body))
    }

    @Test
    fun `parses bare array`() {
        val body = """[{"id":"a"},{"id":"b"}]"""
        assertEquals(listOf("a", "b"), ProviderModelList.parse("custom", body))
    }

    @Test
    fun `malformed body yields empty`() {
        assertTrue(ProviderModelList.parse("openai", "not json").isEmpty())
        assertTrue(ProviderModelList.parse("openai", "").isEmpty())
    }

    @Test
    fun `deduplicates`() {
        val body = """{"data":[{"id":"a"},{"id":"a"},{"id":"b"}]}"""
        assertEquals(listOf("a", "b"), ProviderModelList.parse("openai", body))
    }
}
