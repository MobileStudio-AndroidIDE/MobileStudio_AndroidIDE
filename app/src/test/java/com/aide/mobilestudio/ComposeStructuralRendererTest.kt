package com.aide.mobilestudio

import com.aide.mobilestudio.ComposeStructuralParser.ModifierEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import android.view.Gravity
import android.view.ViewGroup

class ComposeStructuralRendererTest {

    private val density = 2f

    private fun m(name: String, vararg args: String) = ModifierEntry(name, args.toList())

    private fun spec(vararg modifiers: ModifierEntry) =
        ComposeModifierSpec.of(modifiers.toList(), density)

    // ------------------------------------------------------------ padding

    @Test
    fun `padding with one argument applies to all four sides`() {
        val result = spec(m("padding", "16.dp"))
        assertEquals(listOf(32, 32, 32, 32), result.padding?.toList())
    }

    @Test
    fun `padding with two arguments is horizontal then vertical`() {
        val result = spec(m("padding", "12.dp", "8.dp"))
        assertEquals(listOf(24, 16, 24, 16), result.padding?.toList())
    }

    @Test
    fun `padding with four arguments is left top right bottom`() {
        val result = spec(m("padding", "4.dp", "8.dp", "12.dp", "16.dp"))
        assertEquals(listOf(8, 16, 24, 32), result.padding?.toList())
    }

    @Test
    fun `per side padding overrides the shorthand`() {
        val result = spec(m("padding", "16.dp"), m("paddingStart", "2.dp"))
        assertEquals(4, result.padding!![0])
        assertEquals(32, result.padding!![1])
    }

    @Test
    fun `no padding modifier means no padding override`() {
        assertNull(spec(m("alpha", "0.5f")).padding)
    }

    @Test
    fun `an unparseable padding leaves the previous value alone`() {
        val result = spec(m("padding", "Dp.Unspecified"))
        assertNull(result.padding)
    }

    // ------------------------------------------------------------ sizes

    @Test
    fun `size with one argument sets both dimensions`() {
        val result = spec(m("size", "48.dp"))
        assertEquals(96, result.width)
        assertEquals(96, result.height)
    }

    @Test
    fun `size with two arguments sets width then height`() {
        val result = spec(m("size", "100.dp", "20.dp"))
        assertEquals(200, result.width)
        assertEquals(40, result.height)
    }

    @Test
    fun `width and height can be set independently`() {
        val result = spec(m("width", "50.dp"), m("height", "10.dp"))
        assertEquals(100, result.width)
        assertEquals(20, result.height)
    }

    @Test
    fun `fill flags are recognised`() {
        val result = spec(m("fillMaxWidth"))
        assertTrue(result.fillMaxWidth)
        assertFalse(result.fillMaxHeight)

        val both = spec(m("fillMaxSize"))
        assertTrue(both.fillMaxWidth)
        assertTrue(both.fillMaxHeight)
    }

    @Test
    fun `wrap and infinite sizes map onto the right layout params`() {
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, ComposeModifierSpec.px("Dp.Unspecified", density))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, ComposeModifierSpec.px("Dp.Infinite", density))
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, ComposeModifierSpec.px("Float.POSITIVE_INFINITY", density))
    }

    // ------------------------------------------------------------ later wins

    @Test
    fun `the last matching modifier wins`() {
        val result = spec(m("alpha", "1f"), m("alpha", "0.25f"))
        assertEquals(0.25f, result.alpha!!, 0.001f)
    }

    @Test
    fun `weight is read as a float`() {
        assertEquals(1f, spec(m("weight", "1f")).weight, 0.001f)
        assertEquals(2f, spec(m("weight", "2f")).weight, 0.001f)
    }

    // ------------------------------------------------------------ misc

    @Test
    fun `background colour is parsed`() {
        assertEquals(0xFF6200EE.toInt(), spec(m("background", "Color(0xFF6200EE)")).background)
        assertEquals(0xFFFFFFFF.toInt(), spec(m("background", "Color.White")).background)
    }

    @Test
    fun `a chain that declares nothing yields the default spec`() {
        val result = spec()
        assertNull(result.padding)
        assertNull(result.width)
        assertNull(result.background)
        assertEquals(0f, result.weight, 0.001f)
        assertFalse(result.fillMaxWidth)
    }

    // ------------------------------------------------------------ alignment

    @Test
    fun `box alignment maps to gravity`() {
        assertEquals(
            android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL,
            ComposeModifierSpec.alignmentGravity("Alignment.TopCenter"),
        )
        assertEquals(android.view.Gravity.CENTER, ComposeModifierSpec.alignmentGravity("Alignment.Center"))
        assertEquals(
            android.view.Gravity.BOTTOM or android.view.Gravity.END,
            ComposeModifierSpec.alignmentGravity("Alignment.BottomEnd"),
        )
        assertNull(ComposeModifierSpec.alignmentGravity("Alignment.SomethingElse"))
    }

    @Test
    fun `text alignment maps to gravity`() {
        assertEquals(android.view.Gravity.CENTER, ComposeModifierSpec.gravityOf("TextAlign.Center"))
        assertEquals(android.view.Gravity.END, ComposeModifierSpec.gravityOf("TextAlign.End"))
        assertNull(ComposeModifierSpec.gravityOf("TextAlign.Justify"))
    }

    @Test
    fun `font size is read in sp`() {
        assertEquals(20f, ComposeModifierSpec.spValue("20.sp"))
        assertEquals(14f, ComposeModifierSpec.spValue("14f"))
        assertNull(ComposeModifierSpec.spValue("FontScale"))
    }

    @Test
    fun `bold detection understands both notations`() {
        assertTrue(ComposeModifierSpec.isBold("FontWeight.Bold"))
        assertTrue(ComposeModifierSpec.isBold("FontWeight.W700"))
        assertFalse(ComposeModifierSpec.isBold("FontWeight.Normal"))
        assertFalse(ComposeModifierSpec.isBold("FontWeight.W400"))
        assertFalse(ComposeModifierSpec.isBold(null))
    }

    // ------------------------------------------------------- ComposeColorParser

    @Test
    fun `named colours resolve`() {
        assertEquals(0xFFFFFFFF.toInt(), ComposeColorParser.resolve("Color.White"))
        assertEquals(0xFF000000.toInt(), ComposeColorParser.resolve("Color.Black"))
        assertEquals(0xFFFF0000.toInt(), ComposeColorParser.resolve("Color.Red"))
        assertEquals(0x00000000, ComposeColorParser.resolve("Color.Transparent"))
    }

    @Test
    fun `a bare name works too`() {
        assertEquals(0xFF00FF00.toInt(), ComposeColorParser.resolve("Green"))
    }

    @Test
    fun `hex literals with alpha are honoured`() {
        assertEquals(0x80FF0000.toInt(), ComposeColorParser.resolve("Color(0x80FF0000)"))
        assertEquals(0xFF6200EE.toInt(), ComposeColorParser.resolve("Color(0xFF6200EE)"))
    }

    @Test
    fun `a six digit hex literal is fully opaque`() {
        assertEquals(0xFF336699.toInt(), ComposeColorParser.resolve("Color(0x336699)"))
    }

    @Test
    fun `unknown colours resolve to nothing rather than black`() {
        assertNull(ComposeColorParser.resolve("Color(0x12345)"))
        assertNull(ComposeColorParser.resolve("SomeExpression"))
        assertNull(ComposeColorParser.resolve(null))
        assertNull(ComposeColorParser.resolve(""))
    }

    @Test
    fun `resource colours are left to the caller`() {
        assertNull(ComposeColorParser.resolve("colorResource(R.color.brand)"))
        assertNull(ComposeColorParser.resolve("MaterialTheme.colorScheme.primary"))
    }
}
