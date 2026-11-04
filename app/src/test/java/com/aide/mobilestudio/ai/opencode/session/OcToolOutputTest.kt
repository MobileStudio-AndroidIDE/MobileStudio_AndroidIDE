package com.aide.mobilestudio.ai.opencode.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 도구 출력 트리밍.
 *
 * 긴 빌드 로그/테스트 로그가 다음 요청을 창 밖으로 밀어내지 않으면서,
 * 모델이 판단에 필요한 첫 실패와 마지막 요약줄은 남겨야 한다.
 */
class OcToolOutputTest {

    private fun buildLog(lines: Int): String = buildString {
        appendLine("> Task :app:compileDebugKotlin")
        for (i in 1..lines) {
            appendLine("warning: unused variable foo$i at src/File$i.kt:$i")
        }
        appendLine("e: file:///src/Main.kt:42:9 Unresolved reference 'huggingFaceApi'")
        appendLine("e: file:///src/Other.kt:17:1 Cannot infer type")
        appendLine("FAILURE: Build failed with an exception.")
        appendLine("* What went wrong:")
        appendLine("Execution failed for task ':app:compileDebugKotlin'.")
        appendLine("BUILD FAILED in 37s")
    }

    @Test
    fun 짧은_출력은_그대로_둔다() {
        val text = "ok\nBUILD SUCCESSFUL in 2s"
        assertEquals(text, OcToolOutput.trim(text, 2_000))
    }

    @Test
    fun 긴_빌드_로그는_머리와_꼬리를_남긴다() {
        val log = buildLog(500)
        val trimmed = OcToolOutput.trim(log, 2_000)

        assertTrue("길이 상한: ${trimmed.length}", trimmed.length <= 2_000)
        // 머리: 첫 실행 줄
        assertTrue(trimmed.contains("> Task :app:compileDebugKotlin"))
        // 꼬리: 최종 상태
        assertTrue(trimmed.contains("BUILD FAILED in 37s"))
        assertTrue(trimmed.contains("Execution failed for task"))
        // 가운데 진단은 유지
        assertTrue(trimmed.contains("Unresolved reference"))
    }

    @Test
    fun 생략_분량을_정직하게_알린다() {
        val trimmed = OcToolOutput.trim(buildLog(500), 2_000)
        assertTrue("생략 표기: $trimmed", trimmed.contains("omitted"))
        assertTrue(trimmed.contains("diagnostic lines kept"))
        assertTrue(trimmed.contains("session"))
    }

    @Test
    fun 진단_외_중간_줄은_버린다() {
        val log = buildLog(300)
        val trimmed = OcToolOutput.trim(log, 2_000)
        assertFalse("warning 줄300개 중 일부만 유지", trimmed.contains("unused variable foo250"))
        assertTrue(trimmed.contains("unused variable foo1") || trimmed.contains("omitted"))
    }

    @Test
    fun 아주_작은_상한에서도_길이를_지킨다() {
        val trimmed = OcToolOutput.trim(buildLog(200), 100)
        assertTrue("길이 ${trimmed.length}", trimmed.length <= 100)
    }

    @Test
    fun 줄_구조가_없는_긴_한줄은_양끝을_자른다() {
        val oneLine = "x".repeat(10_000)
        val trimmed = OcToolOutput.trim(oneLine, 500)
        assertTrue(trimmed.length <= 500)
        assertTrue(trimmed.contains("[... clipped ...]"))
    }

    @Test
    fun 상한이_0이하면_빈_문자열() {
        assertEquals("", OcToolOutput.trim("anything", 0))
        assertEquals("", OcToolOutput.trim("anything", -5))
    }

    @Test
    fun 진단_판별은_실패와_경고를_잡는다() {
        assertTrue(OcToolOutput.isSignal("error: cannot find symbol"))
        assertTrue(OcToolOutput.isSignal("FAILED: command not found"))
        assertTrue(OcToolOutput.isSignal("warning: deprecated API"))
        assertTrue(OcToolOutput.isSignal("Caused by: java.lang.NullPointerException"))
        assertTrue(OcToolOutput.isSignal("    at com.aide.Foo.bar(Foo.kt:12)"))
        assertTrue(OcToolOutput.isSignal("src/Main.kt:42: something"))
        assertTrue(OcToolOutput.isSignal("BUILD SUCCESSFUL in 3s"))
        assertFalse(OcToolOutput.isSignal("printing ordinary progress line"))
    }

    @Test
    fun 중복_진단은_한번만_남는다() {
        // 머리(일반 줄) + 가운데(반복 진단) + 꼬리(일반 줄) 구조여야
        // 가운데 진단의 중복 제거가 검증된다.
        val log = buildString {
            repeat(50) { appendLine("ordinary line $it") }
            repeat(100) { appendLine("error: same failure repeated") }
            repeat(100) { appendLine("trailing progress $it") }
        }
        val trimmed = OcToolOutput.trim(log, 3_000)
        val occurrences = trimmed.split("error: same failure repeated").size - 1
        assertEquals("진단 중복 제거: $occurrences", 1, occurrences)
    }
}
