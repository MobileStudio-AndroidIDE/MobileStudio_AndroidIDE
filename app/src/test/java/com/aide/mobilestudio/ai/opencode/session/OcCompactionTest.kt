package com.aide.mobilestudio.ai.opencode.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 대화 압축(컴팩션) 판단.
 *
 * 압축 임계값이 음수로 떨어지면 매턴 압축 모델 호출이 발생하면서도
 * 여전히 창에 들어오지 않는다. 임계값은 항상 창 안에서 의미를 가져야 한다.
 */
class OcCompactionTest {

    private fun tokens(total: Int): JSONObject =
        JSONObject().put("total", total).put("input", total).put("output", 0)
            .put("cache", JSONObject().put("read", 0).put("write", 0))

    private fun userMsg(text: String): JSONObject =
        OcMessage.userMessage("sess1", null, null)
            .let { OcMessage.addPart(it, OcMessage.textPart(text)) }

    private fun assistantMsg(text: String): JSONObject =
        OcMessage.assistantMessage("sess1", "build", JSONObject())
            .let { OcMessage.addPart(it, OcMessage.textPart(text)) }

    // ---------------------------------------------------------------- usable --

    @Test
    fun usable는_예약을_뺀_입력_한도() {
        // context4096, reserve는 창의1/4(1024) →3072
        assertEquals(3_072, OcCompaction.usable(null, 4_096, null, null))
        assertEquals(3_072, OcCompaction.usable(null, 4_096, null, 4_096))
    }

    @Test
    fun 소형_창에서_예약이_한도를_삼키지_않는다() {
        // 회귀: 사용자 예약20k가4096 창에 적용되면 usable이 음수로 떨어졌다.
        val usable = OcCompaction.usable(20_000, 4_096, null, null)
        assertTrue("usable=$usable", usable > 0)
        assertEquals(3_072, usable)
    }

    @Test
    fun usable는_예산의_outputReserve와_같은_기준() {
        val context = 8_192
        val reserved = OcContextBudget.outputReserve(context, 4_096, 12_000)
        assertEquals(
            context - reserved,
            OcCompaction.usable(12_000, context, null, 4_096),
        )
    }

    @Test
    fun inputLimit가_있으면_그쪽을_쓴다() {
        assertEquals(60_000, OcCompaction.usable(null, 200_000, 100_000, 40_000))
        // inputLimit가 reserve보다 작으면0 이하로 떨어진다 (0 미만은0으로)
        assertEquals(0, OcCompaction.usable(null, 200_000, 1_000, 4_096))
    }

    @Test
    fun 컨텍스트가_0이면_0() {
        assertEquals(0, OcCompaction.usable(null, 0, null, null))
    }

    // -------------------------------------------------------------- isOverflow --

    @Test
    fun 자동_꺼짐이면_오버플로가_아니다() {
        assertFalse(OcCompaction.isOverflow(false, tokens(999_999), 4_096, null, null, null))
    }

    @Test
    fun 한도_근처는_오버플로가_아니다() {
        // usable =3072 →2900이면 아직 여유
        assertFalse(OcCompaction.isOverflow(true, tokens(2_900), 4_096, null, null, null))
        assertTrue(OcCompaction.isOverflow(true, tokens(3_072), 4_096, null, null, null))
    }

    @Test
    fun 한도를_넘으면_오버플로() {
        assertTrue(OcCompaction.isOverflow(true, tokens(10_000), 4_096, null, null, null))
    }

    @Test
    fun total이_없으면_부분_합으로_본다() {
        val parts = JSONObject()
            .put("input", 2_000).put("output", 1_500)
            .put("cache", JSONObject().put("read", 0).put("write", 0))
        assertTrue(OcCompaction.isOverflow(true, parts, 4_096, null, null, null))
    }

    @Test
    fun 컨텍스트가_0이면_오버플로_검사도_꺼진다() {
        assertFalse(OcCompaction.isOverflow(true, tokens(10_000), 0, null, null, null))
    }

    // ----------------------------------------------------------- compactIfNeeded --

    private fun conversation(turns: Int, charsPerTurn: Int): List<JSONObject> {
        val msgs = mutableListOf<JSONObject>()
        val text = "a".repeat(charsPerTurn)
        for (i in 1..turns) {
            msgs.add(userMsg("turn $i: $text"))
            msgs.add(assistantMsg("done $i"))
        }
        return msgs
    }

    @Test
    fun 짧은_대화는_압축하지_않는다() {
        val msgs = conversation(2, 100)
        assertFalse(
            OcCompaction.compactIfNeeded(
                msgs, "sys", "tools", null, true, 4_096, null, null, null,
            ),
        )
    }

    @Test
    fun 긴_대화는_압축한다() {
        val msgs = conversation(20, 2_000)
        assertTrue(
            OcCompaction.compactIfNeeded(
                msgs, "sys", "tools", null, true, 4_096, null, null, null,
            ),
        )
    }

    @Test
    fun 자동_꺼짐이면_압축하지_않는다() {
        val msgs = conversation(20, 2_000)
        assertFalse(
            OcCompaction.compactIfNeeded(
                msgs, "sys", "tools", null, false, 4_096, null, null, null,
            ),
        )
    }

    @Test
    fun 소형_창에서도_임계값이_음수가_아니다() {
        // 회귀: 예약20k로 임계값이 -15k가 되어 매턴 압축이 발생했다.
        val msgs = conversation(1, 10)
        assertFalse(
            "짧은 대화가 매턴 압축되면 안 됨",
            OcCompaction.compactIfNeeded(msgs, "sys", "tools", null, true, 4_096, null, null, 20_000),
        )
    }

    @Test
    fun 요약_후에는_다시_여유가_생긴다() {
        val long = conversation(20, 2_000)
        assertTrue(OcCompaction.compactIfNeeded(long, "sys", "tools", null, true, 4_096, null, null, null))
        // 압축 후: 요약 메시지 하나로 대체된 상황
        val after = listOf(
            userMsg("Objective: fix the build"),
            assistantMsg("## Work State\n### Completed\n- nothing yet"),
        )
        assertFalse(OcCompaction.compactIfNeeded(after, "sys", "tools", null, true, 4_096, null, null, null))
    }

    // ------------------------------------------------------------------ select --

    @Test
    fun select는_요약할_대화와_앵커를_돌려준다() {
        val msgs = conversation(10, 1_500)
        val (kept, firstKeptId) = OcCompaction.select(msgs, JSONObject(), 4_096, null, null, null)
        assertTrue("전체보다 작아야 함: ${kept.size}/${msgs.size}", kept.size < msgs.size)
        assertTrue(kept.size > 0)
        assertNotNull(firstKeptId)
        // 남긴 조각의 첫 메시지는 원본에 있어야 한다
        assertTrue(msgs.any { it.optString("id") == firstKeptId })
    }

    @Test
    fun 짧은_대화는_그대로() {
        val msgs = conversation(2, 100)
        val (kept, firstKeptId) = OcCompaction.select(msgs, JSONObject(), 4_096, null, null, null)
        assertEquals(msgs.size, kept.size)
        assertNull(firstKeptId)
    }

    @Test
    fun tail_turns가_0이면_압축_대상_아님() {
        val msgs = conversation(10, 1_500)
        val cfg = JSONObject().put("tail_turns", 0)
        val (kept, _) = OcCompaction.select(msgs, cfg, 4_096, null, null, null)
        assertEquals(msgs.size, kept.size)
    }
}
