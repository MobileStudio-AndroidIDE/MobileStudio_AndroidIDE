package com.aide.mobilestudio.web

import java.io.File

/**
 * Which local files an HTML preview depends on, so it can reload when one of
 * them changes.
 *
 * The preview renders a project's own HTML, so its stylesheet, script and
 * image references are ordinary relative paths rather than URLs. This finds
 * them, following `<link href>`, `<script src>`, `<img src>` and CSS
 * `@import`/`url()` in imported stylesheets, bounded so a chain of imports
 * cannot make the preview hang.
 *
 * Pure java.io: unit-testable on the JVM.
 */
object HtmlPreviewSources {

    /** Hard cap on stylesheets followed, so an import cycle stays cheap. */
    private const val MAX_STYLESHEETS = 32

    private val HREF_ATTR = Regex("""\b(?:href|src)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val CSS_IMPORT = Regex("""@import\s+(?:url\()?["']?([^"')]+)["']?\)?""", RegexOption.IGNORE_CASE)
    private val CSS_URL = Regex("""url\(\s*["']?([^"')]+)["']?\s*\)""", RegexOption.IGNORE_CASE)

    /**
     * Local files [htmlFile] depends on, the file itself first. Relative and
     * absolute-path references are resolved against the HTML file's own
     * directory; remote URLs and data: URIs are skipped, since they are not
     * something a local edit can change.
     */
    fun dependencies(htmlFile: File): List<File> {
        val ordered = LinkedHashSet<File>()
        ordered.add(htmlFile)
        val htmlDir = htmlFile.parentFile ?: return ordered.toList()

        val html = runCatching { htmlFile.readText() }.getOrNull() ?: return ordered.toList()

        val stylesheets = mutableListOf<File>()
        for (match in HREF_ATTR.findAll(html)) {
            // resolveFile rejects absolute and remote URLs on its own, so a
            // null result here means "not a local dependency". Only existing
            // files are dependencies; a broken link is not something the
            // preview can reload.
            val resolved = WebPathResolver.resolveFile(htmlDir, match.groupValues[1])
                ?.takeIf { it.isFile } ?: continue
            ordered.add(resolved)
            if (resolved.name.endsWith(".css", ignoreCase = true)) stylesheets.add(resolved)
        }

        // Inline styles can import or reference other local files too.
        for (inline in INLINE_STYLE.findAll(html)) {
            collectCssReferences(htmlDir, inline.groupValues[1], ordered)
        }

        var visited = 0
        while (stylesheets.isNotEmpty() && visited < MAX_STYLESHEETS) {
            val sheet = stylesheets.removeAt(0)
            val css = runCatching { sheet.readText() }.getOrNull() ?: continue
            val sheetDir = sheet.parentFile ?: htmlDir
            collectCssReferences(sheetDir, css, ordered, stylesheets)
            visited++
        }
        return ordered.toList()
    }

    /** True when [path] is one of [htmlFile]'s dependencies. */
    fun dependsOn(htmlFile: File, path: String): Boolean {
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        return dependencies(htmlFile).any {
            runCatching { it.canonicalFile }.getOrDefault(it) == target
        }
    }

    private fun collectCssReferences(
        baseDir: File,
        css: String,
        into: MutableSet<File>,
        importQueue: MutableList<File>? = null,
    ) {
        for (match in CSS_IMPORT.findAll(css)) {
            val resolved = WebPathResolver.resolveFile(baseDir, match.groupValues[1])
                ?.takeIf { it.isFile } ?: continue
            if (into.add(resolved) && resolved.name.endsWith(".css", ignoreCase = true)) {
                importQueue?.add(resolved)
            }
        }
        for (match in CSS_URL.findAll(css)) {
            WebPathResolver.resolveFile(baseDir, match.groupValues[1])
                ?.takeIf { it.isFile }
                ?.let { into.add(it) }
        }
    }

    private val INLINE_STYLE = Regex(
        """<style[^>]*>(.*?)</style>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
}
