package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `tools` 디스패치 복원 검증: TerminalSession 이 감싼 완성 그룹
 * (`{ cmd\n}; __st=$?; echo "STUDIO_CMD_DONE ..."`) 에서 내부 명령을
 * 벗겨내야 터미널에서 입력한 `tools ...` 가 builtin 으로 인식된다.
 */
class PersistentShellToolsDispatchTest {

    /** TerminalSession.buildCommandWithCompletionMarker 와 동일한 포맷. */
    private fun wrap(command: String): String = buildString {
        append("{\n")
        append(command)
        append("\n}")
        append("; __st=\$?; ")
        append("echo \"STUDIO_CMD_DONE \$__st STUDIO_PWD_\$(pwd)\"")
    }

    @Test
    fun wrappedSingleLineToolsCommandIsUnwrapped() {
        assertEquals("tools list", PersistentShell.unwrapCompletionGroup(wrap("tools list")))
        assertEquals("tools", PersistentShell.unwrapCompletionGroup(wrap("tools")))
        assertEquals("tools install git", PersistentShell.unwrapCompletionGroup(wrap("tools install git")))
    }

    @Test
    fun wrappedNonToolsCommandIsUnwrappedForPredicate() {
        // unwrap 은 범용: tools 여부 판별은 호출부가 한다.
        assertEquals("echo hello", PersistentShell.unwrapCompletionGroup(wrap("echo hello")))
    }

    @Test
    fun bareCommandIsNotUnwrapped() {
        assertNull(PersistentShell.unwrapCompletionGroup("tools list"))
        assertNull(PersistentShell.unwrapCompletionGroup("echo hello"))
        assertNull(PersistentShell.unwrapCompletionGroup(""))
    }

    @Test
    fun multiLineInnerIsNotUnwrapped() {
        // 여러 줄 스크립트는 셸로 그대로 통과한다.
        assertNull(
            PersistentShell.unwrapCompletionGroup(
                wrap("echo hi\ntools list")
            )
        )
    }

    @Test
    fun wrapperWithoutMarkerTailIsNotUnwrapped() {
        // TerminalSession 포맷이 아닌 그냥 중괄호 그룹은 벗겨내지 않는다.
        assertNull(PersistentShell.unwrapCompletionGroup("{\necho hello\n}"))
        assertNull(PersistentShell.unwrapCompletionGroup("{ tools list"))
        assertNull(PersistentShell.unwrapCompletionGroup("{}"))
    }
}
