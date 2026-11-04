package com.aide.mobilestudio.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WebPathResolverTest {

    private fun tempDir(): File =
        Files.createTempDirectory("msweb-preview").toFile()

    // ── mapping ────────────────────────────────────────────────────────

    @Test
    fun `simple relative path maps into the html directory`() {
        val dir = tempDir()
        assertEquals(File(dir, "style.css").canonicalFile, WebPathResolver.resolveFile(dir, "style.css"))
    }

    @Test
    fun `subdirectory paths are preserved`() {
        val dir = tempDir()
        assertEquals(
            File(dir, "js/app.js").canonicalFile,
            WebPathResolver.resolveFile(dir, "js/app.js")
        )
    }

    @Test
    fun `root-relative paths map onto the html directory itself`() {
        val dir = tempDir()
        assertEquals(File(dir, "style.css").canonicalFile, WebPathResolver.resolveFile(dir, "/style.css"))
    }

    @Test
    fun `in-directory normalization is allowed`() {
        val dir = tempDir()
        assertEquals(
            File(dir, "style.css").canonicalFile,
            WebPathResolver.resolveFile(dir, "sub/../style.css")
        )
    }

    @Test
    fun `query string and fragment are stripped`() {
        val dir = tempDir()
        assertEquals(
            File(dir, "app.js").canonicalFile,
            WebPathResolver.resolveFile(dir, "app.js?v=2#top")
        )
    }

    @Test
    fun `percent escapes are decoded`() {
        val dir = tempDir()
        assertEquals(
            File(dir, "my file.css").canonicalFile,
            WebPathResolver.resolveFile(dir, "my%20file.css")
        )
    }

    @Test
    fun `percentDecode handles multibyte utf-8 and leaves plus alone`() {
        assertEquals("é.txt", WebPathResolver.percentDecode("%C3%A9.txt"))
        assertEquals("a+b.css", WebPathResolver.percentDecode("a+b.css"))
        assertEquals("100%", WebPathResolver.percentDecode("100%")) // dangling escape kept
    }

    @Test
    fun `missing files inside the directory still resolve`() {
        // Existence is the interceptor's job; the resolver is pure path math.
        val dir = tempDir()
        val resolved = WebPathResolver.resolveFile(dir, "missing.css")
        assertNotNull(resolved)
        assertEquals(File(dir, "missing.css").canonicalFile, resolved)
    }

    // ── path-escape rejection ──────────────────────────────────────────

    @Test
    fun `parent traversal is rejected`() {
        val dir = tempDir()
        assertNull(WebPathResolver.resolveFile(dir, "../secret.txt"))
        assertNull(WebPathResolver.resolveFile(dir, "a/../../secret.txt"))
        assertNull(WebPathResolver.resolveFile(dir, "..%2Fsecret.txt"))
    }

    @Test
    fun `escaping to a sibling directory is rejected`() {
        val outside = Files.createTempDirectory("msweb-outside").toFile()
        val dir = File(outside, "site").apply { mkdirs() }
        assertNull(WebPathResolver.resolveFile(dir, "../outside.css"))
    }

    @Test
    fun `directory root itself is rejected`() {
        val dir = tempDir()
        assertNull(WebPathResolver.resolveFile(dir, ""))
        assertNull(WebPathResolver.resolveFile(dir, "/"))
        assertNull(WebPathResolver.resolveFile(dir, "."))
    }

    // ── remote / non-file schemes ──────────────────────────────────────

    @Test
    fun `absolute and remote urls are rejected`() {
        val dir = tempDir()
        assertNull(WebPathResolver.resolveFile(dir, "https://evil.com/x.js"))
        assertNull(WebPathResolver.resolveFile(dir, "http://evil.com/x.js"))
        assertNull(WebPathResolver.resolveFile(dir, "//evil.com/x.js"))
    }

    @Test
    fun `non-file schemes are rejected`() {
        val dir = tempDir()
        assertNull(WebPathResolver.resolveFile(dir, "data:text/html,hello"))
        assertNull(WebPathResolver.resolveFile(dir, "javascript:void(0)"))
        assertNull(WebPathResolver.resolveFile(dir, "mailto:a@b.c"))
        assertNull(WebPathResolver.resolveFile(dir, "blob:https://x/y"))
        assertNull(WebPathResolver.resolveFile(dir, "about:blank"))
    }

    @Test
    fun `isLocalUrl only accepts the virtual host`() {
        assertTrue(WebPathResolver.isLocalUrl("https://mobilestudio.local/index.html"))
        assertTrue(WebPathResolver.isLocalUrl("https://mobilestudio.local/css/style.css"))
        assertFalse(WebPathResolver.isLocalUrl("https://evil.com/index.html"))
        assertFalse(WebPathResolver.isLocalUrl("http://mobilestudio.local/index.html")) // no cleartext
        assertFalse(WebPathResolver.isLocalUrl("about:blank"))
    }

    @Test
    fun `resolver never throws on hostile input`() {
        val dir = tempDir()
        listOf("\u0000", "%", "%%%", "%ZZ", "\n", "  ", "a/".repeat(400) + "..", "\\..\\x")
            .forEach { input ->
                val result = runCatching { WebPathResolver.resolveFile(dir, input) }
                assertTrue("threw on \"$input\"", result.isSuccess)
            }
    }
}
