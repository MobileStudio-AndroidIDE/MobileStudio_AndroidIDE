package com.aide.mobilestudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

/**
 * Real Android View rendering engine for the design-time layout preview.
 *
 * Unlike the old lightweight preview (which drew rectangles calculated from
 * XML attributes), this engine builds an actual Android View hierarchy from
 * the layout XML using the real widget classes, then runs the real Android
 * measure / layout / draw pipeline and captures the result into a Bitmap.
 *
 * The bitmap is what Android itself would render on device, so gravity,
 * padding, backgrounds, text metrics, compound buttons, material widgets and
 * nested containers behave exactly like runtime views.
 */
object NativeLayoutRenderEngine {
    private const val TAG = "NativeLayoutRender"

    data class DeviceConfig(
        val widthDp: Int = 360,
        val heightDp: Int = 640,
        val density: Float = 1f,
        val apiLevel: Int = 34,
        val fontScale: Float = 1f,
    )

    data class RenderResult(
        val bitmap: Bitmap?,
        val message: String,
        val viewCount: Int = 0,
    )

    /**
     * Renders [xml] into a Bitmap using the real Android View system.
     * The caller supplies a [viewFactory] so the owning preview activity can
     * reuse its existing widget/container builders (real View instances).
     */
    fun render(
        context: Context,
        xml: File,
        config: DeviceConfig,
        viewFactory: (tag: String, attrs: Map<String, String>, parser: org.xmlpull.v1.XmlPullParser, isContainer: Boolean) -> View?,
    ): RenderResult = try {
        val source = xml.readText()
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply { setInput(StringReader(source)) }

        var root: View? = null
        var count = 0
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                val tag = parser.name ?: ""
                val attrs = mutableMapOf<String, String>()
                for (i in 0 until parser.attributeCount) {
                    val name = parser.getAttributeName(i)
                    val value = parser.getAttributeValue(i)
                    if (name.isNotBlank() && value != null) attrs[name] = value
                }
                val isContainer = tag.contains("Layout", ignoreCase = true) ||
                    tag.contains("ScrollView", ignoreCase = true) ||
                    tag.contains("RadioGroup", ignoreCase = true)
                root = viewFactory(tag, attrs, parser, isContainer)
                count++
                break
            }
            event = parser.next()
        }

        val view = root ?: return RenderResult(null, "No root view produced from XML")

        // Real Android measure + layout with the configured device size.
        val widthPx = (config.widthDp * config.density).toInt().coerceAtLeast(1)
        val heightPx = (config.heightDp * config.density).toInt().coerceAtLeast(1)
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        view.measure(widthSpec, heightSpec)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        view.draw(canvas)

        RenderResult(bitmap, "Rendered with real Android View pipeline", count)
    } catch (e: Throwable) {
        Log.w(TAG, "render failed", e)
        RenderResult(null, "Render failed: ${e.message}")
    }
}
