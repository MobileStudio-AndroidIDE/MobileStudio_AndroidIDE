package com.aide.mobilestudio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreviewResourceResolverTest {

    private fun newRes(): File = Files.createTempDirectory("previewres").toFile()

    private fun write(root: File, relative: String, content: String) {
        val file = File(root, relative)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun resolver(res: File, night: Boolean = false) =
        PreviewResourceResolver(listOf(res), night)

    // ------------------------------------------------------------ literals

    @Test
    fun `literals pass through unchanged`() {
        val res = newRes()
        val r = resolver(res)
        assertEquals("16dp", r.resolve("16dp"))
        assertEquals("#FF0000", r.resolve("#FF0000"))
        assertEquals("hello", r.resolve("hello"))
    }

    @Test
    fun `blank and null resolve to nothing`() {
        val r = resolver(newRes())
        assertNull(r.resolve(null))
        assertNull(r.resolve(""))
        assertNull(r.resolve("   "))
    }

    @Test
    fun `theme attributes and framework references are unresolvable here`() {
        val r = resolver(newRes())
        assertNull(r.resolve("?attr/colorPrimary"))
        assertNull(r.resolve("@android:color/black"))
        assertNull(r.resolve("@android:dimen/app_icon_size"))
    }

    @Test
    fun `id references survive untouched so they can be matched by name`() {
        val r = resolver(newRes())
        assertEquals("@+id/submit", r.resolve("@+id/submit"))
        assertEquals("@id/title", r.resolve("@id/title"))
    }

    @Test
    fun `null and empty references resolve to nothing`() {
        val r = resolver(newRes())
        assertNull(r.resolve("@null"))
        assertNull(r.resolve("@empty"))
    }

    // ------------------------------------------------------------ dimens

    @Test
    fun `dimen references are dereferenced`() {
        val res = newRes()
        write(res, "values/dimens.xml", """
            <resources>
                <dimen name="screen_margin">16dp</dimen>
            </resources>
        """.trimIndent())
        val r = resolver(res)
        assertEquals("16dp", r.resolve("@dimen/screen_margin"))
        assertEquals(16f, r.dimension("@dimen/screen_margin"))
    }

    @Test
    fun `nested dimen references follow the chain`() {
        val res = newRes()
        write(res, "values/dimens.xml", """
            <resources>
                <dimen name="a">@dimen/b</dimen>
                <dimen name="b">@dimen/c</dimen>
                <dimen name="c">24dp</dimen>
            </resources>
        """.trimIndent())
        assertEquals(24f, resolver(res).dimension("@dimen/a"))
    }

    @Test
    fun `a reference cycle resolves to nothing instead of hanging`() {
        val res = newRes()
        write(res, "values/dimens.xml", """
            <resources>
                <dimen name="a">@dimen/b</dimen>
                <dimen name="b">@dimen/a</dimen>
            </resources>
        """.trimIndent())
        assertNull(resolver(res).resolve("@dimen/a"))
        assertNull(resolver(res).resolve("@dimen/b"))
    }

    @Test
    fun `a self reference resolves to nothing`() {
        val res = newRes()
        write(res, "values/dimens.xml", """
            <resources><dimen name="a">@dimen/a</dimen></resources>
        """.trimIndent())
        assertNull(resolver(res).resolve("@dimen/a"))
    }

    @Test
    fun `an unknown resource name resolves to nothing`() {
        val res = newRes()
        write(res, "values/dimens.xml", """
            <resources><dimen name="known">8dp</dimen></resources>
        """.trimIndent())
        assertNotNull(resolver(res).resolve("@dimen/known"))
        assertNull(resolver(res).resolve("@dimen/missing"))
    }

    // ------------------------------------------------------------ colors

    @Test
    fun `color references are dereferenced`() {
        val res = newRes()
        write(res, "values/colors.xml", """
            <resources>
                <color name="brand">#6200EE</color>
                <color name="brand_on">@color/brand</color>
            </resources>
        """.trimIndent())
        val r = resolver(res)
        assertEquals("#FF6200EE", r.color("@color/brand"))
        assertEquals("#FF6200EE", r.color("@color/brand_on"))
    }

    @Test
    fun `item typed colors are collected`() {
        val res = newRes()
        write(res, "values/colors.xml", """
            <resources>
                <item name="accent" type="color">#00BCD4</item>
            </resources>
        """.trimIndent())
        assertEquals("#FF00BCD4", resolver(res).color("@color/accent"))
    }

    // ------------------------------------------------------------ strings

    @Test
    fun `string references are dereferenced and unescaped`() {
        val res = newRes()
        write(res, "values/strings.xml", """
            <resources>
                <string name="greeting">Hello\nWorld</string>
                <string name="greeting_shouty">@string/greeting</string>
            </resources>
        """.trimIndent())
        val r = resolver(res)
        assertEquals("Hello\nWorld", r.string("greeting"))
        assertEquals("Hello\nWorld", r.string("greeting_shouty"))
    }

    @Test
    fun `unknown string is null`() {
        assertNull(resolver(newRes()).string("nope"))
    }

    // ------------------------------------------------------------ bool/int

    @Test
    fun `booleans and integers are resolved`() {
        val res = newRes()
        write(res, "values/misc.xml", """
            <resources>
                <bool name="flag_on">true</bool>
                <integer name="retry_count">3</integer>
            </resources>
        """.trimIndent())
        val r = resolver(res)
        assertEquals(true, r.bool("flag_on"))
        assertEquals(3, r.integer("retry_count"))
        assertNull(r.bool("missing"))
        assertNull(r.integer("missing"))
    }

    // ------------------------------------------------------------ styles

    @Test
    fun `style attributes are readable by name`() {
        val res = newRes()
        write(res, "values/styles.xml", """
            <resources>
                <style name="Card.Badge">
                    <item name="android:textSize">12sp</item>
                    <item name="android:textColor">@color/brand</item>
                </style>
            </resources>
        """.trimIndent())
        val r = resolver(res)
        assertEquals("12sp", r.styleAttribute("Card.Badge", "android:textSize"))
        assertEquals("@color/brand", r.styleAttribute("Card.Badge", "android:textColor"))
        assertNull(r.styleAttribute("Card.Badge", "android:visibility"))
        assertNull(r.styleAttribute("Unknown", "android:textSize"))
    }

    @Test
    fun `a style reference resolves to itself so callers can look it up`() {
        val res = newRes()
        write(res, "values/styles.xml", """
            <resources><style name="AppTheme"><item name="android:colorBackground">#fff</item></style></resources>
        """.trimIndent())
        assertEquals("@style/AppTheme", resolver(res).resolve("@style/AppTheme"))
    }

    // ------------------------------------------------------------ qualifiers

    @Test
    fun `base values win over qualified ones by default`() {
        val res = newRes()
        write(res, "values/colors.xml", "<resources><color name=\"bg\">#FFFFFF</color></resources>")
        write(res, "values-zh/colors.xml", "<resources><color name=\"bg\">#000000</color></resources>")
        assertEquals("#FFFFFFFF", resolver(res).color("@color/bg"))
    }

    @Test
    fun `night values only win in night mode`() {
        val res = newRes()
        write(res, "values/colors.xml", "<resources><color name=\"bg\">#FFFFFF</color></resources>")
        write(res, "values-night/colors.xml", "<resources><color name=\"bg\">#121212</color></resources>")

        assertEquals("#FFFFFFFF", resolver(res, night = false).color("@color/bg"))
        assertEquals("#FF121212", resolver(res, night = true).color("@color/bg"))
    }

    @Test
    fun `a resource only defined in a qualifier still resolves`() {
        val res = newRes()
        write(res, "values-land/dimens.xml", "<resources><dimen name=\"wide\">320dp</dimen></resources>")
        assertEquals(320f, resolver(res).dimension("@dimen/wide"))
    }

    @Test
    fun `several res roots are merged`() {
        val base = newRes()
        val feature = newRes()
        write(base, "values/dimens.xml", "<resources><dimen name=\"a\">1dp</dimen></resources>")
        write(feature, "values/dimens.xml", "<resources><dimen name=\"b\">2dp</dimen></resources>")
        val r = PreviewResourceResolver(listOf(base, feature))
        assertEquals(1f, r.dimension("@dimen/a"))
        assertEquals(2f, r.dimension("@dimen/b"))
    }

    // ------------------------------------------------------------ robustness

    @Test
    fun `a malformed values file is skipped without throwing`() {
        val res = newRes()
        write(res, "values/broken.xml", "<resources><dimen name=\"a\">1dp</resources>") // unclosed
        write(res, "values/good.xml", "<resources><dimen name=\"b\">2dp</dimen></resources>")
        val r = resolver(res)
        assertNull(r.resolve("@dimen/a"))
        assertEquals(2f, r.dimension("@dimen/b"))
    }

    @Test
    fun `a missing res directory yields an empty resolver`() {
        val r = resolver(File("/definitely/not/here"))
        assertNull(r.resolve("@dimen/x"))
        assertEquals(0, r.size)
    }

    @Test
    fun `a doctype declaration is refused rather than parsed`() {
        val res = newRes()
        write(
            res, "values/evil.xml", """
            <!DOCTYPE resources [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <resources><dimen name="a">&xxe;</dimen></resources>
        """.trimIndent(),
        )
        // Whatever the parser decided, it must not throw and must not leak the file.
        val r = resolver(res)
        assertFalse(r.resolve("@dimen/a").orEmpty().contains("root"))
    }

    // ------------------------------------------------------- DimensionParser

    @Test
    fun `dimension parser understands every android unit`() {
        assertEquals(16f to "dp", DimensionParser.parse("16dp"))
        assertEquals(16f to "dp", DimensionParser.parse("16dip"))
        assertEquals(14f to "sp", DimensionParser.parse("14sp"))
        assertEquals(3f to "px", DimensionParser.parse("3px"))
        assertEquals(2.5f to "pt", DimensionParser.parse("2.5pt"))
        assertEquals(1f to "mm", DimensionParser.parse("1mm"))
        assertEquals(0.5f to "in", DimensionParser.parse("0.5in"))
    }

    @Test
    fun `a bare number is treated as px`() {
        assertEquals(8f to "px", DimensionParser.parse("8"))
    }

    @Test
    fun `non-dimensions do not parse`() {
        assertNull(DimensionParser.parse("match_parent"))
        assertNull(DimensionParser.parse("wrap_content"))
        assertNull(DimensionParser.parse("@dimen/x"))
        assertNull(DimensionParser.parse("abcbd"))
        assertNull(DimensionParser.parse(null))
    }

    @Test
    fun `density conversion applies only to dp and sp`() {
        assertEquals(32f, DimensionParser.toPxAtDensity("16dp", density = 2f))
        assertEquals(28f, DimensionParser.toPxAtDensity("14sp", density = 2f, fontScale = 1f))
        assertEquals(14f, DimensionParser.toPxAtDensity("14sp", density = 2f, fontScale = 0.5f))
        assertEquals(3f, DimensionParser.toPxAtDensity("3px", density = 3f))
    }

    // ---------------------------------------------------------- ColorParser

    @Test
    fun `every color literal form normalizes to argb`() {
        assertEquals("#FFFF0000", ColorParser.canonical("#F00"))
        assertEquals("#88FF0000", ColorParser.canonical("#8F00"))
        assertEquals("#FF336699", ColorParser.canonical("#336699"))
        assertEquals("#80336699", ColorParser.canonical("#80336699"))
        assertEquals("#FFABCDEF", ColorParser.canonical("#abcdef"))
    }

    @Test
    fun `invalid colors do not parse`() {
        assertNull(ColorParser.canonical("#12345"))
        assertNull(ColorParser.canonical("#GGGGGG"))
        assertNull(ColorParser.canonical("red"))
        assertNull(ColorParser.canonical(null))
    }

    @Test
    fun `argb int matches the canonical form`() {
        assertEquals(0xFFFF0000.toInt(), ColorParser.argb("#FF0000"))
        assertEquals(0x11223344, ColorParser.argb("#11223344"))
        assertNull(ColorParser.argb("nope"))
    }
}
