package com.aide.mobilestudio.ai.validation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonValidatorTest {

    @Test
    fun acceptsValidObject() {
        val result = JsonValidator.validate("""{"a": 1, "b": [true, null, "x"]}""")
        assertTrue(result.valid)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun rejectsTrailingComma() {
        val result = JsonValidator.validate("{\n  \"a\": 1,\n}")
        assertFalse(result.valid)
        assertTrue(result.issues.first().message.contains("Trailing comma"))
        assertEquals(3, result.issues.first().line)
    }

    @Test
    fun rejectsSingleQuotedStrings() {
        val result = JsonValidator.validate("{ 'a': 1 }")
        assertFalse(result.valid)
        assertTrue(result.issues.first().message.contains("Single-quoted"))
    }

    @Test
    fun rejectsUnquotedKey() {
        val result = JsonValidator.validate("{ a: 1 }")
        assertFalse(result.valid)
        assertTrue(result.issues.first().message.contains("property name"))
    }

    @Test
    fun reportsMissingClosingBrace() {
        val result = JsonValidator.validate("{\"a\": 1")
        assertFalse(result.valid)
        assertTrue(result.issues.first().message.contains("Unterminated"))
    }

    @Test
    fun reportsLineAndColumnForBadValue() {
        val result = JsonValidator.validate("{\n  \"a\": tru\n}")
        assertFalse(result.valid)
        assertEquals(2, result.issues.first().line)
    }

    @Test
    fun computesOffsetToLineColumn() {
        val text = "ab\ncd\nef"
        assertEquals(1 to 2, JsonValidator.offsetToLineColumn(text, 1))
        assertEquals(2 to 1, JsonValidator.offsetToLineColumn(text, 3))
        assertEquals(3 to 2, JsonValidator.offsetToLineColumn(text, 7))
    }

    // ---- JsonRepairer ----

    @Test
    fun repairLeavesValidJsonUntouched() {
        val input = """{"url": "http://example.com", "n": 1}"""
        val result = JsonRepairer.repair(input)
        assertTrue(result.success)
        assertTrue(result.fixes.isEmpty())
        assertEquals(input, result.repaired)
    }

    @Test
    fun repairRemovesTrailingComma() {
        val result = JsonRepairer.repair("""{"a": 1,}""")
        assertTrue(result.success)
        assertTrue(result.fixes.contains("removed trailing commas"))
        assertTrue(JsonValidator.validate(result.repaired).valid)
    }

    @Test
    fun repairQuotesUnquotedKeys() {
        val result = JsonRepairer.repair("""{a: 1, b: 2}""")
        assertTrue(result.success)
        assertTrue(JsonValidator.validate(result.repaired).valid)
    }

    @Test
    fun repairConvertsSingleQuotes() {
        val result = JsonRepairer.repair("""{'a': 'hello'}""")
        assertTrue(result.success)
        assertTrue(JsonValidator.validate(result.repaired).valid)
    }

    @Test
    fun repairRemovesComments() {
        val result = JsonRepairer.repair("{\n  // note\n  \"a\": 1\n}")
        assertTrue(result.success)
        assertTrue(result.fixes.contains("removed comments"))
        assertTrue(JsonValidator.validate(result.repaired).valid)
    }

    @Test
    fun repairNormalizesSmartQuotes() {
        val result = JsonRepairer.repair("{\u201Ca\u201D: 1}")
        assertTrue(result.success)
        assertTrue(JsonValidator.validate(result.repaired).valid)
    }

    @Test
    fun repairBalancesMissingBrackets() {
        val result = JsonRepairer.repair("""{"a": [1, 2""")
        assertTrue(result.success)
        assertTrue(JsonValidator.validate(result.repaired).valid)
    }

    @Test
    fun repairFailsOnUnrecoverableText() {
        val result = JsonRepairer.repair("this is not json at all")
        assertFalse(result.success)
        assertTrue(result.remainingIssues.isNotEmpty())
    }

    @Test
    fun repairDoesNotMangleUrlsInsideStrings() {
        val result = JsonRepairer.repair("{\n // c\n \"u\": \"http://a/b\",\n}")
        assertTrue(result.success)
        assertTrue(result.repaired.contains("http://a/b"))
    }
}
