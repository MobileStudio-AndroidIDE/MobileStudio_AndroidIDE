package com.aide.mobilestudio.ai.opencode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 컨텍스트 예산.
 *
 * 시스템 프롬프트/도구 스키마/대화 이력/도구 출력/답변 예약을 각각 분리해
 * 두지 않으면 소형 창(4096)에서 고정 오버헤드만으로 매턴 초과가 된다.
 */
class OcContextBudgetTest {

    // ----------------------------------------------------------- outputReserve --

    @Test
    fun 큰_창에서는_기본_예약을_쓴다() {
        assertEquals(4096, OcContextBudget.outputReserve(200_000, null, null))
        // 사용자 설정이 더 크면 그 값을 쓰되 창의1/4로 제한된다.
        assertEquals(20_000, OcContextBudget.outputReserve(200_000, null, 20_000))
    }

    @Test
    fun 소형_창에서는_창의_사분의_일로__CLAMP된다() {
        // 회귀:20k 예약이4096 창보다 커 압축 임계값이 음수로 나왔다.
        val reserve = OcContextBudget.outputReserve(4_096, 4_096, 20_000)
        assertEquals(1_024, reserve)
        assertTrue("예약이 창보다 클 수 없음", reserve <= 4_096 / 4)
    }

    @Test
    fun 아주_작은_창에서도_최소_예약을_보존한다() {
        assertEquals(
            OcContextBudget.MIN_OUTPUT_RESERVE,
            OcContextBudget.outputReserve(512, null, null),
        )
    }

    @Test
    fun 창이_0이면_선언된_값또는_기본값() {
        assertEquals(4_096, OcContextBudget.outputReserve(0, null, null))
        assertEquals(1_024, OcContextBudget.outputReserve(0, 1_024, null))
    }

    // ------------------------------------------------------------- fits/over --

    private fun budget(
        ctx: Int = 4_096,
        modelMax: Int? = null,
        systemTokens: Int = 1_500,
        toolTokens: Int = 800,
        historyTokens: Int = 500,
    ): OcContextBudget = OcContextBudget(
        contextTokens = ctx,
        modelMaxTokens = modelMax,
        outputReserveTokens = OcContextBudget.outputReserve(ctx, null, null),
        systemTokens = systemTokens,
        toolTokens = toolTokens,
        historyTokens = historyTokens,
        toolResultChars = OcContextBudget.toolResultChars(ctx, 1_024),
    )

    @Test
    fun 한도_이내면_들어간다() {
        val b = budget()
        // used =1500+800+500+1024 =3824 ≤4096
        assertTrue(b.fits)
        assertEquals(0, b.overByTokens)
    }

    @Test
    fun 한도를_넘으면_초과분을_알린다() {
        val b = budget(historyTokens = 3_000)
        assertFalse(b.fits)
        // used =1500+800+3000+1024 =6324 → over2228
        assertEquals(6_324 - 4_096, b.usedTokens - b.contextTokens)
        assertEquals(b.usedTokens - b.contextTokens, b.overByTokens)
    }

    @Test
    fun 한도에_근접하면_들어가지만_여유가_없다() {
        val b = budget(historyTokens = 772) //1500+800+772+1024 =4096
        assertTrue(b.fits)
        assertEquals(4_096, b.usedTokens)
        assertEquals(0, b.contextTokens - b.usedTokens)
    }

    @Test
    fun 모델_최대치를_넘는_창은_표시된다() {
        val b = budget(ctx = 8_192, modelMax = 4_096)
        assertTrue(b.exceedsModelMax)
        assertFalse(budget(ctx = 4_096, modelMax = 4_096).exceedsModelMax)
        assertFalse(budget(ctx = 4_096, modelMax = null).exceedsModelMax)
    }

    @Test
    fun describe는_추정치임을_표시한다() {
        val line = budget().describe()
        assertTrue(line.contains("fits=true"))
        assertTrue("추정치 표시: $line", line.contains("~"))
    }

    // ------------------------------------------------------------ toolResult --

    @Test
    fun 도구_출력은_입력_예산의_사분의_1_문자() {
        // ctx4096, reserve1024 → 입력3072토큰 → 도구1개당3072자(≈768토큰)
        assertEquals(3_072, OcContextBudget.toolResultChars(4_096, 1_024))
        // 큰 창에서는 무제한에 가깝지만 여전히 입력 예산에 비례한다.
        assertTrue(OcContextBudget.toolResultChars(128_000, 20_000) > 100_000)
    }

    // -------------------------------------------------------- chooseLocalContext --

    @Test
    fun 설정값은_하한_GGUF_선언치는_상한() {
        // 설정2048 < 선언4096 →4096
        assertEquals(4_096, OcContextBudget.chooseLocalContext(2_048, 4_096).tokens)
        // 설정8192 > 선언4096 →4096 (모델이 못 버틴다)
        assertEquals(4_096, OcContextBudget.chooseLocalContext(8_192, 4_096).tokens)
        // 선언이 없으면 설정을 그대로 쓴다
        assertEquals(3_072, OcContextBudget.chooseLocalContext(3_072, null).tokens)
    }

    @Test
    fun 파생_기본값은_상한으로_캡된다() {
        // 선언32k라도 파생 기본값은8192 캡
        assertEquals(
            OcContextBudget.LOCAL_CONTEXT_CEILING,
            OcContextBudget.chooseLocalContext(512, 32_768).tokens,
        )
        // 사용자가 크게 설정하면 설정이 상한이다 (캡은 기본값에만 적용)
        assertEquals(16_384, OcContextBudget.chooseLocalContext(16_384, 32_768).tokens)
    }

    @Test
    fun 너무_작은_선언치는_무시한다() {
        assertEquals(2_048, OcContextBudget.chooseLocalContext(2_048, 256).tokens)
    }

    @Test
    fun KV_비용을_알면_메모리로_제한한다() {
        // arm6440-레이어/8헤드/128차원/FP16 → 레이어*헤드*(k+v)*2
        val perToken = OcContextBudget.kvBytesPerToken(40, 8, 128, 128)
        assertNotNull(perToken)
        val usableBytes = perToken!! * 2_048
        val choice = OcContextBudget.chooseLocalContext(
            configured = 8_192,
            modelMax = 8_192,
            availableBytes = usableBytes,
            kvBytesPerToken = perToken,
            memoryHeadroomBytes = 0,
        )
        assertTrue(choice.limitedByMemory)
        assertTrue(choice.tokens < 8_192)
        assertTrue(choice.tokens >= OcContextBudget.MIN_CONTEXT)
        assertNotNull(choice.estimatedBytes)
        assertTrue(choice.estimatedBytes!! <= usableBytes)
        assertNotNull(choice.suggestion)
    }

    @Test
    fun 메모리가_아예_없으면_최소창으로_내려간다() {
        val perToken = OcContextBudget.kvBytesPerToken(40, 8, 128, 128)!!
        val choice = OcContextBudget.chooseLocalContext(
            configured = 8_192,
            modelMax = 8_192,
            availableBytes = perToken, // 최소창도 못 버틴다
            kvBytesPerToken = perToken,
            memoryHeadroomBytes = 0,
        )
        assertTrue(choice.limitedByMemory)
        assertEquals(OcContextBudget.MIN_CONTEXT, choice.tokens)
        assertNotNull(choice.suggestion)
    }

    @Test
    fun KV_비용을_모르면_메모리_검사를_하지_않는다() {
        val choice = OcContextBudget.chooseLocalContext(
            configured = 8_192,
            modelMax = 8_192,
            availableBytes = 1024,
            kvBytesPerToken = null,
        )
        assertFalse(choice.limitedByMemory)
        assertNull(choice.estimatedBytes)
        assertNull(choice.suggestion)
        assertEquals(8_192, choice.tokens)
    }

    // ------------------------------------------------------------- kv / head --

    @Test
    fun KV_비트_계산은_정보가_완전할_때만() {
        assertEquals(40L * 8 * 256 * 2, OcContextBudget.kvBytesPerToken(40, 8, 128, 128))
        // v가 없으면 k를 그대로 쓴다
        assertEquals(40L * 8 * 128 * 2 * 2, OcContextBudget.kvBytesPerToken(40, 8, 128, null))
        assertNull(OcContextBudget.kvBytesPerToken(null, 8, 128, 128))
        assertNull(OcContextBudget.kvBytesPerToken(0, 8, 128, 128))
        assertNull(OcContextBudget.kvBytesPerToken(40, null, 128, 128))
        assertNull(OcContextBudget.kvBytesPerToken(40, 8, 0, 128))
    }

    @Test
    fun 헤드_길이는_선언치_우선_그다음_유도() {
        assertEquals(128, OcContextBudget.headLength(128, 4096, 32))
        assertEquals(128, OcContextBudget.headLength(null, 4096, 32)) //4096/32
        assertNull(OcContextBudget.headLength(null, null, null))
        assertNull(OcContextBudget.headLength(null, 100, 0))
    }

    // --------------------------------------------------------------------- of --

    @Test
    fun of는_텍스트에서_예산을_만든다() {
        val b = OcContextBudget.of(
            contextTokens = 4_096,
            modelMaxTokens = 4_096,
            declaredOutput = 4_096,
            configuredReserve = null,
            systemText = "You are a coding agent.",
            toolsJson = """{"read":{"description":"Read a file from disk."}}""",
            historyText = "user: hi\nassistant: hello",
        )
        assertTrue(b.systemTokens > 0)
        assertTrue(b.toolTokens > 0)
        assertTrue(b.historyTokens > 0)
        assertEquals(1_024, b.outputReserveTokens)
        assertTrue(b.fits)
    }
}
