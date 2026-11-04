package com.aide.mobilestudio.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileMentionsTest {

    // ------------------------------------------------------------ extraction

    @Test
    fun `extracts path mentions in order and dedupes`() {
        val mentions = FileMentions.extract("look at @app/src/Main.kt and @README.md, then @app/src/Main.kt again")
        assertEquals(listOf("app/src/Main.kt", "README.md"), mentions)
    }

    @Test
    fun `ignores emails and mid-word at`() {
        assertNullMention("mail me@example.com now")
        assertNullMention("foo@bar/baz")
    }

    private fun assertNullMention(text: String) {
        assertEquals(emptyList<String>(), FileMentions.extract(text))
    }

    @Test
    fun `handles mentions at text boundaries`() {
        assertEquals(listOf("a.txt"), FileMentions.extract("@a.txt"))
        assertEquals(listOf("a.txt"), FileMentions.extract("(see @a.txt)"))
        assertEquals(listOf("a.txt"), FileMentions.extract("x\n@a.txt"))
    }

    @Test
    fun `stops at whitespace and does not swallow punctuation`() {
        val mentions = FileMentions.extract("@src/A.kt, @src/B.kt.")
        assertEquals(listOf("src/A.kt", "src/B.kt"), mentions)
    }

    // ------------------------------------------------------------ resolution

    private fun newRoot(): File = Files.createTempDirectory("mentions").toFile()

    @Test
    fun `resolves relative mentions against the root`() {
        val root = newRoot()
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main() {}")

        val resolved = FileMentions.resolve(root, listOf("src/Main.kt"))
        val single = resolved.single()
        assertEquals("src/Main.kt", single.mention)
        assertEquals(File(root, "src/Main.kt").absolutePath, single.path)
        assertEquals("fun main() {}", single.content)
        assertFalse(single.skipped)
        assertNull(single.reason)
    }

    @Test
    fun `unknown mentions report not found`() {
        val resolved = FileMentions.resolve(newRoot(), listOf("nope/Missing.kt")).single()
        assertNull(resolved.path)
        assertNull(resolved.content)
        assertEquals("not found", resolved.reason)
    }

    @Test
    fun `oversized files are skipped with a reason`() {
        val root = newRoot()
        val big = File(root, "big.txt")
        big.writeText("x".repeat(65 * 1024))

        val resolved = FileMentions.resolve(root, listOf("big.txt")).single()
        assertTrue(resolved.skipped)
        assertNull(resolved.content)
        assertTrue(resolved.reason!!.contains("64 KB"))
        assertTrue(resolved.bytes == 0)
    }

    @Test
    fun `smaller files at exactly the limit are attached`() {
        val root = newRoot()
        File(root, "edge.txt").writeText("x".repeat(64 * 1024))
        val resolved = FileMentions.resolve(root, listOf("edge.txt")).single()
        assertFalse(resolved.skipped)
        assertEquals(64 * 1024, resolved.bytes)
    }

    @Test
    fun `resolves absolute mentions too`() {
        val root = newRoot()
        val outside = Files.createTempDirectory("outside").toFile()
        val file = File(outside, "abs.txt").apply { writeText("absolute") }
        val resolved = FileMentions.resolve(root, listOf(file.absolutePath)).single()
        assertEquals("absolute", resolved.content)
    }

    // ------------------------------------------------------------ formatting

    @Test
    fun `formatContextBlock embeds content and notes unknown mentions`() {
        val root = newRoot()
        File(root, "ok.txt").writeText("hello")
        val resolved = FileMentions.resolve(root, listOf("ok.txt", "gone.txt"))

        val block = FileMentions.formatContextBlock(resolved)
        assertTrue(block.startsWith("---ATTACHED FILE CONTEXT---"))
        assertTrue(block.contains("@ok.txt:"))
        assertTrue(block.contains("hello"))
        assertTrue(block.contains("@gone.txt: not found — ignored"))
        assertTrue(block.endsWith("---END ATTACHED FILE CONTEXT---"))
    }

    @Test
    fun `formatContextBlock notes skipped files`() {
        val root = newRoot()
        File(root, "big.txt").writeText("x".repeat(65 * 1024))
        val block = FileMentions.formatContextBlock(FileMentions.resolve(root, listOf("big.txt")))
        assertTrue(block.contains("skipped"))
    }

    @Test
    fun `formatContextBlock is empty without mentions`() {
        assertEquals("", FileMentions.formatContextBlock(emptyList()))
    }
}
