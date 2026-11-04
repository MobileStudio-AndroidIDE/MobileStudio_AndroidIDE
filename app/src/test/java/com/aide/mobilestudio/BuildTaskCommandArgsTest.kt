package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The editable command line in the build dialog.
 *
 * The `./gradlew` prefix is fixed and everything after it is handed to Gradle
 * as separate arguments, so splitting has to be right: a task list the user
 * typed by hand must survive intact, and a value holding a space must not be
 * cut in two.
 */
class BuildTaskCommandArgsTest {

    private fun parse(line: String) = BuildTaskDialog.parseCommandArgs(line)

    @Test
    fun `the seeded apk task splits into one argument`() {
        assertEquals(listOf("assembleDebug"), parse("assembleDebug"))
    }

    @Test
    fun `clean is its own task, not an empty build type`() {
        // The bug this guards: Clean used to compose to "assemble", so the
        // option built without cleaning and failed on stale output.
        assertEquals(listOf("clean"), parse("clean"))
    }

    @Test
    fun `extra spaces between arguments are collapsed`() {
        assertEquals(listOf("clean", "assembleDebug"), parse("clean    assembleDebug"))
    }

    @Test
    fun `leading and trailing whitespace is dropped`() {
        assertEquals(listOf("build"), parse("   build   "))
    }

    @Test
    fun `an empty command yields no arguments`() {
        assertEquals(emptyList<String>(), parse(""))
        assertEquals(emptyList<String>(), parse("     "))
    }

    @Test
    fun `a gradle flag survives alongside the task`() {
        assertEquals(
            listOf("assembleDebug", "--offline"),
            parse("assembleDebug --offline"),
        )
    }

    @Test
    fun `a quoted value keeps its space`() {
        assertEquals(
            listOf("test", "-Ptitle=hello world"),
            parse("""test -Ptitle="hello world""""),
        )
    }

    @Test
    fun `single quotes work too`() {
        assertEquals(
            listOf("test", "-Ptitle=hello world"),
            parse("test '-Ptitle=hello world'"),
        )
    }

    @Test
    fun `an unmatched quote does not swallow the rest`() {
        assertEquals(listOf("test", "-Pname=open"), parse("""test -Pname="open"""))
    }

    @Test
    fun `a custom task path passes through unchanged`() {
        assertEquals(listOf("app:testDebugUnitTest"), parse("app:testDebugUnitTest"))
    }

    @Test
    fun `a colon-prefixed task is preserved`() {
        assertEquals(listOf(":app:assembleDebug"), parse(":app:assembleDebug"))
    }

    @Test
    fun `a repeated custom command keeps every occurrence`() {
        assertEquals(
            listOf("clean", "assembleDebug", "lint"),
            parse("clean assembleDebug lint"),
        )
    }
}
