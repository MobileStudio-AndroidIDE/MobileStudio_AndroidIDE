package com.aide.mobilestudio.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HtmlPreviewSourcesTest {

    private fun newProject(): File = Files.createTempDirectory("htmlpreview").toFile()

    private fun write(dir: File, relative: String, content: String): File =
        File(dir, relative).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private fun names(files: List<File>): List<String> = files.map { it.name }

    // ------------------------------------------------------------ discovery

    @Test
    fun `the html file itself comes first`() {
        val dir = newProject()
        val html = write(dir, "index.html", "<html></html>")
        assertEquals(listOf("index.html"), names(HtmlPreviewSources.dependencies(html)))
    }

    @Test
    fun `linked stylesheets and scripts are found`() {
        val dir = newProject()
        write(dir, "style.css", "body{}")
        write(dir, "app.js", "console.log(1)")
        val html = write(dir, "index.html", """
            <html><head>
              <link rel="stylesheet" href="style.css">
              <script src="app.js"></script>
            </head><body></body></html>
        """.trimIndent())

        val deps = names(HtmlPreviewSources.dependencies(html))
        assertTrue(deps.containsAll(listOf("index.html", "style.css", "app.js")))
    }

    @Test
    fun `images referenced by the page are dependencies too`() {
        val dir = newProject()
        write(dir, "logo.svg", "<svg/>")
        val html = write(dir, "index.html", """<img src="logo.svg" alt="logo">""")
        assertTrue(names(HtmlPreviewSources.dependencies(html)).contains("logo.svg"))
    }

    @Test
    fun `assets in a subdirectory resolve`() {
        val dir = newProject()
        write(dir, "assets/site.css", "body{}")
        val html = write(dir, "index.html", """<link href="assets/site.css" rel="stylesheet">""")
        assertTrue(names(HtmlPreviewSources.dependencies(html)).contains("site.css"))
    }

    @Test
    fun `single and double quotes both work`() {
        val dir = newProject()
        write(dir, "a.css", "")
        write(dir, "b.js", "")
        val html = write(dir, "index.html", """<link href='a.css'><script src="b.js"></script>""")
        val deps = names(HtmlPreviewSources.dependencies(html))
        assertTrue(deps.containsAll(listOf("a.css", "b.js")))
    }

    // ------------------------------------------------------------ css imports

    @Test
    fun `imported stylesheets are followed`() {
        val dir = newProject()
        write(dir, "theme.css", "@import url(\"colors.css\");\nbody{}")
        write(dir, "colors.css", ":root{--c:red}")
        val html = write(dir, "index.html", """<link href="theme.css" rel="stylesheet">""")

        val deps = names(HtmlPreviewSources.dependencies(html))
        assertTrue(deps.containsAll(listOf("theme.css", "colors.css")))
    }

    @Test
    fun `url references inside a stylesheet are found`() {
        val dir = newProject()
        write(dir, "bg.png", "png")
        write(dir, "site.css", ".hero{background:url(bg.png)}")
        val html = write(dir, "index.html", """<link href="site.css" rel="stylesheet">""")
        assertTrue(names(HtmlPreviewSources.dependencies(html)).contains("bg.png"))
    }

    @Test
    fun `inline style blocks are scanned`() {
        val dir = newProject()
        write(dir, "extra.css", "p{}")
        val html = write(dir, "index.html", "<style>@import url('extra.css');</style>")
        assertTrue(names(HtmlPreviewSources.dependencies(html)).contains("extra.css"))
    }

    @Test
    fun `an import cycle terminates`() {
        val dir = newProject()
        write(dir, "a.css", "@import url(\"b.css\");")
        write(dir, "b.css", "@import url(\"a.css\");")
        val html = write(dir, "index.html", """<link href="a.css" rel="stylesheet">""")

        val deps = HtmlPreviewSources.dependencies(html)
        assertEquals(deps.size, deps.distinct().size)
        assertTrue(names(deps).containsAll(listOf("a.css", "b.css")))
    }

    // ------------------------------------------------------------ exclusions

    @Test
    fun `remote urls are not dependencies`() {
        val dir = newProject()
        val html = write(dir, "index.html", """
            <link href="https://cdn.example.com/x.css" rel="stylesheet">
            <script src="//cdn.example.com/y.js"></script>
            <img src="data:image/png;base64,AAA">
        """.trimIndent())
        assertEquals(listOf("index.html"), names(HtmlPreviewSources.dependencies(html)))
    }

    @Test
    fun `a reference escaping the directory is refused`() {
        val dir = newProject()
        write(dir, "secret.txt", "nope")
        val html = write(dir, "sub/page.html", """<link href="../secret.txt">""")
        assertFalse(names(HtmlPreviewSources.dependencies(html)).contains("secret.txt"))
    }

    @Test
    fun `a missing file referenced by the page is skipped`() {
        val dir = newProject()
        val html = write(dir, "index.html", """<link href="missing.css" rel="stylesheet">""")
        assertEquals(listOf("index.html"), names(HtmlPreviewSources.dependencies(html)))
    }

    @Test
    fun `an unreadable html file still yields itself`() {
        val dir = newProject()
        val html = File(dir, "gone.html") // never written
        assertEquals(listOf("gone.html"), names(HtmlPreviewSources.dependencies(html)))
    }

    // ------------------------------------------------------------ dependsOn

    @Test
    fun `dependsOn recognises a dependency`() {
        val dir = newProject()
        write(dir, "site.css", "")
        val html = write(dir, "index.html", """<link href="site.css" rel="stylesheet">""")
        assertTrue(HtmlPreviewSources.dependsOn(html, File(dir, "site.css").absolutePath))
        assertFalse(HtmlPreviewSources.dependsOn(html, File(dir, "other.css").absolutePath))
    }
}
