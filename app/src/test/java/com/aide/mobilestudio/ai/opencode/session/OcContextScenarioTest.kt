package com.aide.mobilestudio.ai.opencode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스펙 §10 통합 시나리오.
 *
 * 짧은/긴 대화, 한도 근접·초과, 모델·컨텍스트 변경, 요약 후 재개,
 * 긴 빌드 로그, 메모리 제한 상태를 한 흐름으로 검증한다.
 */
class OcContextScenarioTest {

    private val systemText = "You are a coding agent working inside a mobile IDE."
    private val toolsJson = """{"read":{"description":"Read a file."},"edit":{"description":"Edit a file."}}"""

    private fun budgetFor(ctx: Int, modelMax: Int?, history: String): OcContextBudget =
        OcContextBudget.of(
            contextTokens = ctx,
            modelMaxTokens = modelMax,
            declaredOutput = null,
            configuredReserve = null,
            systemText = systemText,
            toolsJson = toolsJson,
            historyText = history,
        )

    @Test
    fun 짧은_대화는_소형_창에_들어간다() {
        val b = budgetFor(4_096, 4_096, "user: hi\nassistant: hello, what should I build?")
        assertTrue("used=${b.usedTokens}", b.fits)
        assertTrue(b.usedTokens < b.contextTokens)
    }

    @Test
    fun 긴_대화는_한도를_넘어_압축_신호를_낸다() {
        val longHistory = buildString {
            repeat(80) { appendLine("user: 테스트를 실행해줘 step $it\nassistant: 실행 결과입니다. " + "x".repeat(400)) }
        }
        val b = budgetFor(4_096, 4_096, longHistory)
        assertFalse(b.fits)
        assertTrue("초과분이 나와야 함: ${b.overByTokens}", b.overByTokens > 0)
    }

    @Test
    fun 한도_근접이면_들어가고_예약만_남는다() {
        // 한 턴씩 채우다 마지막으로 들어간 순간: 남은 여유는 outputReserve 미만
        val unit = "y".repeat(3_500)
        var history = ""
        var b = budgetFor(4_096, 4_096, history)
        while (history.length < 1_000_000) {
            val next = history + unit
            val nextBudget = budgetFor(4_096, 4_096, next)
            if (!nextBudget.fits) break
            history = next
            b = nextBudget
        }
        assertTrue(b.fits)
        assertTrue("남은 여유: ${b.contextTokens - b.usedTokens}", b.contextTokens - b.usedTokens < b.outputReserveTokens)
    }

    @Test
    fun 모델을_바꾸면_같은_대화가_들어가거나_안_들어간다() {
        val history = "z".repeat(30_000) // ~8571 토큰
        val small = budgetFor(4_096, 4_096, history)
        val large = budgetFor(128_000, 128_000, history)
        assertFalse("소형 모델에서 초과", small.fits)
        assertTrue("대형 모델에서 수용", large.fits)
    }

    @Test
    fun 컨텍스트가_모델_선언치를_넘으면_경고() {
        val b = budgetFor(16_384, 8_192, "short")
        assertTrue(b.exceedsModelMax)
    }

    @Test
    fun 요약_후_재개하면_다시_들어간다() {
        val longHistory = buildString {
            repeat(80) { appendLine("user: step $it\nassistant: " + "r".repeat(500)) }
        }
        val before = budgetFor(4_096, 4_096, longHistory)
        assertFalse("요약 전 초과", before.fits)

        val summary = "## Objective\n- 빌드 수정\n## Work State\n### Completed\n- nothing"
        val after = budgetFor(4_096, 4_096, summary)
        assertTrue("요약 후 수용", after.fits)
        assertTrue(
            "히스토리 토큰이 줄어야 함: ${before.historyTokens} → ${after.historyTokens}",
            after.historyTokens < before.historyTokens,
        )
    }

    @Test
    fun 긴_빌드_로그는_트리밍_후_예산에_든다() {
        val log = buildString {
            repeat(400) { appendLine("warning: unused foo$it at File$it.kt:$it") }
            appendLine("e: file:///src/Main.kt:42:9 Unresolved reference 'HuggingFaceApi'")
            appendLine("BUILD FAILED in 37s")
        }
        val ctx = 4_096
        val reserve = OcContextBudget.outputReserve(ctx, null, null)
        val maxChars = OcContextBudget.toolResultChars(ctx, reserve)

        val trimmed = OcToolOutput.trim(log, maxChars)
        assertTrue("트리밍 결과가 도구 예산 이내: ${trimmed.length} > $maxChars", trimmed.length <= maxChars)
        assertTrue(trimmed.contains("Unresolved reference"))
        assertTrue(trimmed.contains("BUILD FAILED"))

        val b = budgetFor(ctx, 4_096, "user: 빌드 오류 알려줘\nassistant: 로그 확인:\n$trimmed")
        assertTrue("트리밍된 로그를 넣어도 수용: used=${b.usedTokens}", b.fits)
    }

    @Test
    fun 메모리_제한_상태는_제안과_함께_표시된다() {
        val perToken = OcContextBudget.kvBytesPerToken(28, 4, 128, 128)
        assertNotNull(perToken)
        val choice = OcContextBudget.chooseLocalContext(
            configured = 8_192,
            modelMax = 8_192,
            availableBytes = perToken!! * 1_024,
            kvBytesPerToken = perToken,
        )
        assertTrue(choice.limitedByMemory)
        assertTrue(choice.tokens < 8_192)
        assertNotNull(choice.suggestion)
        // 제한된 창으로 다시 예산을 짜도 성립해야 한다
        assertTrue(budgetFor(choice.tokens, 8_192, "user: hi").fits)
    }

    @Test
    fun 모든_예산_표기는_추정치임을_표시한다() {
        val b = budgetFor(4_096, 4_096, "hello")
        assertTrue(b.isEstimate)
        val line = b.describe()
        assertTrue("추정 표기: $line", line.contains("system=~"))
        assertTrue(line.contains("fits="))
        assertEquals("~${b.usedTokens}", line.substringAfter("used=").substringBefore(" "))
    }
}
