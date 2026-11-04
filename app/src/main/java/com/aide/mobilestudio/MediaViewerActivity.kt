package com.aide.mobilestudio

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import com.aide.mobilestudio.ui.ide.IdeAction
import com.aide.mobilestudio.ui.ide.IdeDimens
import com.aide.mobilestudio.ui.ide.IdeDrawerMenu
import com.aide.mobilestudio.ui.ide.IdeEmptyState
import com.aide.mobilestudio.ui.ide.IdeKeyValueRow
import com.aide.mobilestudio.ui.ide.IdeMainLayout
import com.aide.mobilestudio.ui.ide.IdeMenuGroup
import com.aide.mobilestudio.ui.ide.IdeMenuItem
import com.aide.mobilestudio.ui.ide.IdeMono
import com.aide.mobilestudio.ui.ide.IdeSectionLabel
import com.aide.mobilestudio.ui.ide.IdeTopBar
import com.aide.mobilestudio.ui.ide.LocalIdeColors
import com.aide.mobilestudio.ui.ide.LocalShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Viewer for images / GIFs / videos / audio with swipe-up metadata details. */
class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "path"
        fun start(context: Context, file: File) {
            context.startActivity(
                Intent(context, MediaViewerActivity::class.java)
                    .putExtra(EXTRA_PATH, file.absolutePath)
            )
        }
    }

    private lateinit var file: File
    private var kind = MediaDetector.Kind.IMAGE
    private val details = mutableStateOf<List<Pair<String, String>>>(emptyList())
    private val audioDurationMs = mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        file = File(path)
        kind = MediaDetector.detect(file)
        setContent {
            ThemeManager.ProvideTheme {
                MediaViewerContent(file, kind, details.value)
            }
        }
        loadMeta()
    }

    private fun loadMeta() {
        Thread {
            val map = when (kind) {
                MediaDetector.Kind.IMAGE, MediaDetector.Kind.GIF -> imageInfo(file)
                MediaDetector.Kind.VIDEO, MediaDetector.Kind.AUDIO -> mediaInfo(file)
                else -> emptyList()
            }
            android.os.Handler(mainLooper).post { details.value = map }
        }.start()
    }

    private fun imageInfo(file: File): List<Pair<String, String>> {
        val info = mutableListOf<Pair<String, String>>()
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) {
            info += "Resolution" to "${opts.outWidth} x ${opts.outHeight}"
            val mp = opts.outWidth * opts.outHeight / 1_000_000.0
            info += "Megapixels" to String.format(java.util.Locale.US, "%.1f MP", mp)
        }
        val exif = parseJpegExif(file)
        exif["Make"]?.let { info += "Camera make" to it }
        exif["Model"]?.let { info += "Camera model" to it }
        exif["DateTimeOriginal"]?.let { info += "Taken at" to it }
        exif["ExposureTime"]?.let { info += "Shutter speed" to "${it} s" }
        exif["FNumber"]?.let { info += "Aperture" to "f/${it}" }
        exif["ISOSpeedRatings"]?.let { info += "ISO" to it }
        exif["FocalLength"]?.let { info += "Focal length" to "${it}mm" }
        info += "Size" to formatBytes(file.length())
        return info
    }

    private fun mediaInfo(file: File): List<Pair<String, String>> {
        val info = mutableListOf<Pair<String, String>>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            fun g(key: Int): String? = runCatching { retriever.extractMetadata(key) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { it }
            val w = g(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = g(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (w != null && h != null) {
                info += "Resolution" to "${w} x ${h}"
                info += "Megapixels" to
                    String.format(java.util.Locale.US, "%.1f MP", w.toInt() * h.toInt() / 1_000_000.0)
            }
            g(MediaMetadataRetriever.METADATA_KEY_DURATION)?.let {
                info += "Duration" to formatDuration(it.toLong())
            }
            g(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.let {
                info += "Frame rate" to "$it fps"
            }
            g(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let {
                info += "Bit rate" to formatBits(it)
            }
            g(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let {
                info += "Format" to it.substringBefore('/').uppercase() + " / " + it.substringAfter('/')
            }
            g(MediaMetadataRetriever.METADATA_KEY_DATE)?.let { info += "Date" to it }
            g(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { info += "Title" to it }
            g(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { info += "Artist" to it }
            g(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { info += "Album" to it }
            g(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { info += "Genre" to it }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }
        info += "Size" to formatBytes(file.length())
        return info
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        val m = s / 60
        return String.format(java.util.Locale.US, "%d:%02d", m, s % 60)
    }

    private fun formatBits(bits: String): String {
        val kbps = bits.toLongOrNull()?.div(1000L) ?: return "$bits bps"
        return if (kbps >= 1000) String.format(java.util.Locale.US, "%.1f Mbps", kbps / 1000.0) else "$kbps kbps"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
    }

    private fun parseJpegExif(file: File): Map<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(2)
                raf.readFully(header)
                if (header[0] != 0xFF.toByte() || header[1] != 0xD8.toByte()) return emptyMap()
                while (raf.filePointer + 4 < raf.length()) {
                    val marker = raf.readUnsignedByte()
                    val start = raf.readUnsignedByte()
                    if (marker != 0xFF) break
                    when (start) {
                        in 0xD0..0xD7, 0x01 -> { /* standalone marker */ }
                        0xDA -> break // SOS - image data starts
                        else -> {
                            val len = raf.readUnsignedShort()
                            if (len < 2) break
                            if (start == 0xE1) {
                                val buf = ByteArray(len - 2)
                                raf.readFully(buf)
                                if (buf.size >= 6 && buf[0] == 'E'.code.toByte() && buf[1] == 'x'.code.toByte() &&
                                    buf[2] == 'i'.code.toByte() && buf[3] == 'f'.code.toByte() &&
                                    buf[4] == 0.toByte() && buf[5] == 0.toByte()
                                ) {
                                    parseTiff(buf, 6, out)
                                    return out
                                }
                            } else {
                                val skip = len - 2
                                if (raf.skipBytes(skip) < skip) break
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun parseTiff(buf: ByteArray, off: Int, out: MutableMap<String, String>) {
        if (buf.size < off + 8) return
        val little = buf[off] == 'I'.code.toByte() && buf[off + 1] == 'I'.code.toByte()
        val u16 = fun(pos: Int): Int {
            val a = buf[pos].toInt() and 0xFF
            val b = buf[pos + 1].toInt() and 0xFF
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        val u32 = fun(pos: Int): Long {
            return if (little) {
                (buf[pos].toLong() and 0xFFL) or ((buf[pos + 1].toLong() and 0xFFL) shl 8) or
                    ((buf[pos + 2].toLong() and 0xFFL) shl 16) or ((buf[pos + 3].toLong() and 0xFFL) shl 24)
            } else {
                ((buf[pos].toLong() and 0xFFL) shl 24) or ((buf[pos + 1].toLong() and 0xFFL) shl 16) or
                    ((buf[pos + 2].toLong() and 0xFFL) shl 8) or (buf[pos + 3].toLong() and 0xFFL)
            }
        }
        if (buf[off + 2] != 0x2A.toByte()) return
        val ifd0 = u32(off + 4).toInt()
        if (ifd0 + 2 > buf.size - off) return
        val count = u16(off + ifd0)
        val tagMap = mapOf(
            0x010F to "Make", 0x0110 to "Model", 0x0132 to "DateTime",
            0x0112 to "Orientation", 0x829A to "ExposureTime", 0x829D to "FNumber",
            0x8827 to "ISOSpeedRatings", 0x9003 to "DateTimeOriginal", 0x920A to "FocalLength"
        )
        for (i in 0 until count) {
            val p = off + ifd0 + 2 + i * 12
            if (p + 12 > buf.size) break
            val tag = u16(p)
            val type = u16(p + 2)
            val nCount = u32(p + 4)
            val tagName = tagMap[tag] ?: continue
            val valuePos: Int
            val valueLen: Int
            when (type) {
                3 -> { valuePos = p + 8; valueLen = 2 } // SHORT
                4 -> { valuePos = p + 8; valueLen = 4 } // LONG
                5 -> { // RATIONAL (8 bytes: num/den)
                    val vpos = if (nCount == 1L) p + 8 else off + u32(p + 8).toInt()
                    if (vpos + 8 > buf.size) continue
                    val num = u32(vpos); val den = u32(vpos + 4)
                    if (den == 0L) continue
                    val v = num.toDouble() / den
                    val shown = if (type == 5 && tag == 0x829A) {
                        if (v >= 1) formatFraction(v) else "1/${(1.0 / v).toLong()}"
                    } else String.format(java.util.Locale.US, "%.1f", v)
                    out[tagName] = shown
                    continue
                }
                2 -> { // ASCII
                    valuePos = if (nCount <= 4L) p + 8 else off + u32(p + 8).toInt()
                    valueLen = nCount.toInt().coerceAtMost(buf.size - valuePos)
                    if (valuePos < 0 || valuePos >= buf.size) continue
                    out[tagName] = String(buf, valuePos, valueLen).trim { it <= ' ' }
                    continue
                }
                else -> continue
            }
            if (type == 3) {
                val v = if (little) (buf[valuePos].toInt()) or ((buf[valuePos + 1].toInt() and 0xFF) shl 8)
                else ((buf[valuePos].toInt() and 0xFF) shl 8) or (buf[valuePos + 1].toInt() and 0xFF)
                out[tagName] = if (tag == 0x8827) v.toString() else v.toString()
            } else if (type == 4) {
                val v = u32(valuePos)
                out[tagName] = v.toString()
            }
        }
    }

    private fun formatFraction(v: Double): String {
        val den = Math.round(1.0 / v)
        if (Math.abs((1.0 / v) - den) < 0.05) return "1/$den"
        return String.format(java.util.Locale.US, "%.1f", v)
    }
}

private val THUMB_BG = Color(0xFF1C2028)

@Composable
private fun MediaViewerContent(file: File, kind: MediaDetector.Kind, details: List<Pair<String, String>>) {
    val context = LocalContext.current
    val ide = LocalIdeColors.current
    var showDetails by remember { mutableStateOf(false) }
    var swipeAccum by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ide.editorBg)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        if (swipeAccum <= -80f) showDetails = true
                        else if (swipeAccum >= 80f) showDetails = false
                        swipeAccum = 0f
                    }
                ) { _, dragAmount ->
                    swipeAccum += dragAmount.y
                }
            }
    ) {
        IdeMainLayout(
            title = file.name,
            subtitle = kindLabel(kind) + " · " + formatBytesRoot(file.length()),
            subtitleMono = true,
            showBack = true,
            onBack = { (context as? android.app.Activity)?.finish() },
            drawerContent = {
                IdeDrawerMenu(
                    listOf(
                        IdeMenuGroup(
                            "View",
                            listOf(
                                IdeMenuItem(
                                    if (showDetails) "Hide file info" else "Show file info",
                                    onClick = { showDetails = !showDetails },
                                ),
                            ),
                        ),
                        IdeMenuGroup(
                            "File",
                            listOf(
                                IdeMenuItem("Close", onClick = { (context as? android.app.Activity)?.finish() }),
                            ),
                        ),
                    ),
                )
            },
            topActions = {
                IdeAction(
                    text = if (showDetails) "Hide info" else "Info",
                    onClick = { showDetails = !showDetails },
                    iconRes = R.drawable.ic_ide_info,
                    iconSize = 14.dp,
                )
            },
            breadcrumb = file.parent,
        ) {
            // Stage + info panel share ONE box so the panel anchors to the
            // stage's bottom edge (a separate sibling would escape the scope).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (kind) {
                    MediaDetector.Kind.IMAGE,
                    MediaDetector.Kind.GIF -> ImageStage(file)
                    MediaDetector.Kind.VIDEO -> VideoStage(file)
                    MediaDetector.Kind.AUDIO -> AudioStage(file)
                    else -> IdeEmptyState(
                        title = "Unsupported media type",
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // No BoxScope overload of AnimatedVisibility exists — call the
                // top-level one explicitly so the receiver stays unambiguous.
                androidx.compose.animation.AnimatedVisibility(
                    visible = showDetails,
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ide.surface)
                            .border(0.5.dp, ide.border)
                            .padding(IdeDimens.space5)
                    ) {
                        IdeSectionLabel("FILE INFO")
                        Spacer(Modifier.height(IdeDimens.space4))
                        if (details.isEmpty()) {
                            Text(
                                "Reading metadata…",
                                style = MaterialTheme.typography.bodySmall,
                                color = ide.textSoft,
                            )
                        } else {
                            details.forEach { (k, v) -> IdeKeyValueRow(k, v) }
                        }
                        Spacer(Modifier.height(IdeDimens.space5))
                    }
                }
            }
        }
    }
}

private fun kindLabel(kind: MediaDetector.Kind): String = when (kind) {
    MediaDetector.Kind.IMAGE -> "Image"
    MediaDetector.Kind.GIF -> "GIF"
    MediaDetector.Kind.VIDEO -> "Video"
    MediaDetector.Kind.AUDIO -> "Audio"
    else -> "File"
}

private fun formatBytesRoot(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0)
}

@Composable
private fun ImageStage(file: File) {
    val ide = LocalIdeColors.current
    var scaled by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setBackgroundColor(ide.p.editorBg)
                    setImageBitmap(
                        kkoBitmap(file)
                            ?: android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    )
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
        )
    }
}

private fun kkoBitmap(file: File): android.graphics.Bitmap? {
    return try {
        val opts = android.graphics.BitmapFactory.Options()
        val bmp = android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
        if (bmp != null) {
            val exif = parseExifRotation(file)
            if (exif in listOf(90, 270)) {
                val r = android.graphics.Matrix().apply { postRotate(exif.toFloat()) }
                val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, r, true)
                if (rotated != bmp) bmp.recycle()
                rotated
            } else bmp
        } else null
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun VideoStage(file: File) {
    val ide = LocalIdeColors.current
    var error by remember { mutableStateOf(false) }
    if (error) {
        IdeEmptyState(title = "Video playback failed")
        return
    }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnErrorListener { _, _, _ -> error = true; false }
                setVideoURI(Uri.fromFile(file))
                setMediaController(MediaController(ctx))
                requestFocus()
                start()
            }
        },
        modifier = Modifier.fillMaxSize().background(Color.Black)
    )
}

@Composable
private fun AudioStage(file: File) {
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf(false) }

    val player = remember { mutableStateOf<MediaPlayer?>(null) }

    LaunchedEffect(file) {
        val mp = try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            error = true
            return@LaunchedEffect
        }
        duration = mp.duration.toLong()
        player.value = mp
        playing = true
        while (player.value === mp) {
            kotlinx.coroutines.delay(500)
            position = mp.currentPosition.toLong()
            if (!mp.isPlaying && mp.duration > 0 && position >= mp.duration - 300) {
                playing = false
                break
            }
        }
    }

    if (error) {
        IdeEmptyState(title = "Audio playback failed")
        return
    }

    val ide = LocalIdeColors.current
    Column(
        modifier = Modifier.fillMaxSize().background(ide.editorBg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clickable {
                    val mp = player.value ?: return@clickable
                    if (mp.isPlaying) { mp.pause(); playing = false }
                    else { mp.start(); playing = true }
                }
                .background(if (playing) ide.accent else ide.hover, LocalShapes.current.panel),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (playing) "❚❚" else "▶",
                color = if (playing) ide.onAccent else ide.text,
                fontSize = 22.sp
            )
        }
        Spacer(Modifier.height(IdeDimens.space5))
        Text(
            file.name,
            color = ide.text,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1, fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(IdeDimens.space2))
        Text(
            "${formatTimeM(position)} / ${formatTimeM(duration)}",
            style = IdeMono.log, color = ide.textSoft
        )
    }
}

private fun formatTimeM(ms: Long): String =
    String.format(java.util.Locale.US, "%d:%02d", ms / 60000, (ms / 1000) % 60)

private fun parseExifRotation(file: File): Int = try {
    RandomAccessFile(file, "r").use { raf ->
        val h = ByteArray(2)
        raf.readFully(h)
        if (h[0] != 0xFF.toByte() || h[1] != 0xD8.toByte()) return 0
        while (raf.filePointer + 4 < raf.length()) {
            val m1 = raf.readUnsignedByte()
            val m2 = raf.readUnsignedByte()
            if (m1 != 0xFF) break
            if (m2 in 0xD0..0xD7 || m2 == 0x01) continue
            if (m2 == 0xDA) return 0
            val len = raf.readUnsignedShort()
            if (len < 2) break
            if (m2 == 0xE1) {
                val b = ByteArray(len - 2)
                raf.readFully(b)
                if (b.size >= 12 && b[0] == 'E'.code.toByte() && b[1] == 'x'.code.toByte() &&
                    b[2] == 'i'.code.toByte() && b[3] == 'f'.code.toByte()
                ) {
                    val little = b[6] == 'I'.code.toByte()
                    val u16s = fun(p: Int): Int {
                        val a = b[p].toInt() and 0xFF
                        val c = b[p + 1].toInt() and 0xFF
                        return if (little) a or (c shl 8) else (a shl 8) or c
                    }
                    val ifd0 = if (little) {
                        b[10].toLong() and 0xFF or ((b[11].toLong() and 0xFF) shl 8) or
                            ((b[12].toLong() and 0xFF) shl 16) or ((b[13].toLong() and 0xFF) shl 24)
                    } else {
                        ((b[10].toLong() and 0xFF) shl 24) or ((b[11].toLong() and 0xFF) shl 16) or
                            ((b[12].toLong() and 0xFF) shl 8) or (b[13].toLong() and 0xFF)
                    }.toInt()
                    val cnt = u16s(ifd0 + 6)
                    for (i in 0 until cnt) {
                        val p = ifd0 + 6 + 2 + i * 12
                        if (p + 12 > b.size) break
                        if (u16s(p) == 0x0112) {
                            val v = u16s(p + 8)
                            return when (v) { 6 -> 90; 8 -> 270; 3 -> 180; else -> v.toInt() }
                        }
                    }
                    return 0
                }
            } else {
                val skip = len - 2
                if (raf.skipBytes(skip) < skip) break
            }
        }
        0
    }
} catch (_: Exception) {
    0
}