package com.aide.mobilestudio.ai.opencode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 추정 토큰 카운터.
 *
 * 오류의 방향이 안전해야 한다: 너무 크게 잡으면 창 안에 들어오고,
 * 너무 작게 잡으면 요청이 거절되어 턴이 통째로 날아간다.
 * 특히 한글은 length/4 추정이 실제보다4배 작게 나와 로컬 모델에서
 * 매번 오버플로가 났던 주원인이므로 넓은 문자를 별도로 센다.
 */
class OcTokenEstimateTest {

    @Test
    fun 빈_문자열은_0() {
        assertEquals(0, OcTokenEstimate.tokens(""))
    }

    @Test
    fun ASCII는_글자당_세_글자_이상으로_나뉜다() {
        val text = "The quick brown fox jumps over the lazy dog"
        val tokens = OcTokenEstimate.tokens(text)
        //43자 /3.5 ≈13
        assertTrue("ASCII 추정이 너무 큼: $tokens", tokens in 10..16)
    }

    @Test
    fun 한글은_글자당_약_한_토큰() {
        assertEquals(5, OcTokenEstimate.tokens("안녕하세요"))
        assertEquals(7, OcTokenEstimate.tokens("컨텍스트최적화"))
    }

    @Test
    fun 같은_길이의_한글이_ASCII보다_크게_나온다() {
        val hangul = "가나다라마바사아자차카타파하가나다라마바사"
        val ascii = "abcdefghijklmnopqrstuvwxy"
        assertTrue(
            "한글(${OcTokenEstimate.tokens(hangul)}) > ASCII(${OcTokenEstimate.tokens(ascii)})",
            OcTokenEstimate.tokens(hangul) > OcTokenEstimate.tokens(ascii),
        )
    }

    @Test
    fun 긴_한글_대화가_length_div_4로_과소평가되지_않는다() {
        // 과거 버그: length/4로100글짜 한글을25토큰으로 봤다.
        val korean = "이것은 긴 한글 대화입니다. ".repeat(20) // ~540자
        val tokens = OcTokenEstimate.tokens(korean)
        assertTrue("length/4=${korean.length / 4} 보다 커야 함: $tokens", tokens > korean.length / 4)
    }

    @Test
    fun 추정치는_정확치_않음을_표시한다() {
        assertFalse(OcTokenEstimate.IS_EXACT)
        assertTrue(OcTokenEstimate.label(10).startsWith(OcTokenEstimate.ESTIMATE_SUFFIX))
    }

    @Test
    fun null은_0_숫자는_문자열로_계산() {
        assertEquals(0, OcTokenEstimate.tokens(null))
        assertEquals(OcTokenEstimate.tokens("123456"), OcTokenEstimate.tokens(123456))
    }

    @Test
    fun 혼합_문자열은_ASCII와_와이드를_더한다() {
        val mixed = "hello 안녕하세요 world"
        val tokens = OcTokenEstimate.tokens(mixed)
        //11 ASCII ≈3.1 +5 wide =5 → 합 ~9
        assertTrue("혼합 추정 범위: $tokens", tokens in 6..12)
    }
}
