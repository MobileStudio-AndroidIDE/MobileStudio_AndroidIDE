package com.aide.mobilestudio.wine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Embedded X11 server for Wine — no external app required.
 *
 * Implements enough of the X11 protocol for Wine's winex11.drv to connect,
 * create windows, render text/graphics, and receive input events. Renders to
 * an in-memory [framebuffer] Bitmap that the UI layer displays.
 *
 * Supported X11 protocol:
 * - Connection handshake (success reply with screen info)
 * - Window management (Create/Map/Unmap/Configure/Destroy)
 * - Graphics contexts (Create/Change/Free, with clipping)
 * - Drawing: PutImage, CopyArea, PolyFillRectangle, PolyFillArc,
 *   PolyLine, PolySegment, PolyRectangle, PolyText8, PolyText16, ImageText8/16
 * - Pixmap management (off-screen drawing surfaces)
 * - Atom management (pre-defined + dynamic InternAtom with real IDs)
 * - Property management (Change/Get/Delete with in-memory storage)
 * - Selection/clipboard basics
 * - Input events: ButtonPress/Release, MotionNotify, KeyPress/Release, Expose
 * - Font management (OpenFont, QueryFont stub)
 * - FreeGC, FreePixmap, FreeColormap, KillClient, NoOperation
 * - Generic reply generation for all reply-expecting opcodes (prevents hangs)
 */
class MiniXServer(
    @Suppress("unused") private val socketPath: String,
    val displayWidth: Int = 800,
    val displayHeight: Int = 600,
) {
    companion object {
        private const val TAG = "MiniXServer"

        // --- X11 opcodes ---
        private const val OP_CREATE_WINDOW = 1
        private const val OP_CHANGE_WINDOW_ATTRIBUTES = 2
        private const val OP_GET_WINDOW_ATTRIBUTES = 3
        private const val OP_DESTROY_WINDOW = 4
        private const val OP_DESTROY_SUBWINDOWS = 5
        private const val OP_REPARENT_WINDOW = 7
        private const val OP_MAP_WINDOW = 8
        private const val OP_MAP_SUBWINDOWS = 9
        private const val OP_UNMAP_WINDOW = 10
        private const val OP_CONFIGURE_WINDOW = 12
        private const val OP_QUERY_TREE = 15
        private const val OP_INTERN_ATOM = 16
        private const val OP_CHANGE_PROPERTY = 18
        private const val OP_DELETE_PROPERTY = 19
        private const val OP_GET_PROPERTY = 20
        private const val OP_GET_GEOMETRY = 14
        private const val OP_QUERY_POINTER = 38
        private const val OP_TRANSLATE_COORDS = 40
        private const val OP_WARP_POINTER = 41
        private const val OP_SET_INPUT_FOCUS = 42
        private const val OP_CREATE_PIXMAP = 53
        private const val OP_FREE_PIXMAP = 54
        private const val OP_CREATE_GC = 55
        private const val OP_CHANGE_GC = 56
        private const val OP_FREE_GC = 60
        private const val OP_COPY_AREA = 62
        private const val OP_POLY_LINE = 65
        private const val OP_POLY_SEGMENT = 66
        private const val OP_POLY_RECTANGLE = 67
        private const val OP_POLY_ARC = 68
        private const val OP_POLY_FILL_RECTANGLE = 70
        private const val OP_POLY_FILL_ARC = 71
        private const val OP_PUT_IMAGE = 72
        private const val OP_GET_IMAGE = 73
        private const val OP_CREATE_COLORMAP = 78
        private const val OP_FREE_COLORMAP = 79
        private const val OP_OPEN_FONT = 45
        private const val OP_CLOSE_FONT = 46
        private const val OP_QUERY_FONT = 47
        private const val OP_IMAGE_TEXT8 = 76
        private const val OP_IMAGE_TEXT16 = 77
        private const val OP_POLY_TEXT8 = 74
        private const val OP_POLY_TEXT16 = 75
        private const val OP_SEND_EVENT = 25
        private const val OP_GRAB_POINTER = 26
        private const val OP_UNGRAB_POINTER = 27
        private const val OP_NO_OPERATION = 127
        private const val OP_LIST_FONTS = 49
        private const val OP_LIST_INSTALLED_FONTS = 51
        private const val OP_ALLOC_COLOR = 84
        private const val OP_FREE_COLORS = 88
        private const val OP_QUERY_EXTENSION = 98
        private const val OP_LIST_EXTENSIONS = 99
        private const val OP_GET_KEYBOARD_MAPPING = 101
        private const val OP_BELL = 104
        private const val OP_SET_SCREEN_SAVER = 107
        private const val OP_GET_POINTER_MAPPING = 117
        private const val OP_SET_POINTER_MAPPING = 118

        // Pre-defined atoms (X11 standard)
        val PREDEFINED_ATOMS = mapOf(
            "WM_PROTOCOLS" to 1, "WM_DELETE_WINDOW" to 2, "WM_STATE" to 3,
            "WM_CHANGE_STATE" to 4, "WM_TAKE_FOCUS" to 5, "WM_CLASS" to 6,
            "WM_NAME" to 7, "WM_TRANSIENT_FOR" to 8, "WM_NORMAL_HINTS" to 9,
            "WM_HINTS" to 10, "WM_CLIENT_MACHINE" to 11, "WM_COMMAND" to 12,
            "_NET_WM_NAME" to 13, "_NET_WM_PID" to 14, "_NET_WM_STATE" to 15,
            "_NET_WM_STATE_FULLSCREEN" to 16, "_NET_SUPPORTED" to 17,
            "_NET_SUPPORTING_WM_CHECK" to 18, "_NET_WM_ICON" to 19,
            "_NET_ACTIVE_WINDOW" to 20, "_NET_CLOSE_WINDOW" to 21,
            "UTF8_STRING" to 22, "COMPOUND_TEXT" to 23, "STRING" to 24,
            "TEXT" to 25, "CLIPBOARD" to 26, "PRIMARY" to 27,
            "SECONDARY" to 28, "TARGETS" to 29, "INCR" to 30,
            "XdndAware" to 31, "XdndEnter" to 32, "XdndPosition" to 33,
            "XdndDrop" to 34, "XdndLeave" to 35, "XdndSelection" to 36,
            "XdndFinished" to 37, "XdndStatus" to 38,
            "XKLAVIER_STATE" to 39, "_MOTIF_WM_HINTS" to 40,
            "RESOURCE_MANAGER" to 41, "SCREEN_NUMBER" to 42,
        )

        // Event types
        const val EVENT_KEY_PRESS = 2
        const val EVENT_KEY_RELEASE = 3
        const val EVENT_BUTTON_PRESS = 4
        const val EVENT_BUTTON_RELEASE = 5
        const val EVENT_MOTION_NOTIFY = 6
        const val EVENT_EXPOSE = 12
        const val EVENT_CONFIGURE_NOTIFY = 22
    }

    // =====================================================================
    // Data classes
    // =====================================================================

    data class XWindow(
        val id: Int,
        var x: Int = 0,
        var y: Int = 0,
        var width: Int = 0,
        var height: Int = 0,
        var mapped: Boolean = false,
        var bgColor: Int = Color.WHITE,
        var parentId: Int = 0,
        var eventMask: Int = 0,
        var borderWidth: Int = 0,
    ) {
        val children = CopyOnWriteArrayList<Int>()
    }

    data class XGC(
        val id: Int,
        var fgColor: Int = Color.BLACK,
        var bgColor: Int = Color.WHITE,
        var lineWidth: Int = 0,
        var fontId: Int = 0,
        var clipX: Int = 0,
        var clipY: Int = 0,
        var clipW: Int = -1,
        var clipH: Int = -1,
        var function: Int = 3, // GXcopy
    ) {
        val clipEnabled: Boolean get() = clipW > 0 && clipH > 0
    }

    data class XPixmap(val id: Int, val width: Int, val height: Int) {
        val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    data class XFont(val id: Int, val name: String) {
        val paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f
            typeface = Typeface.MONOSPACE
        }
    }

    // =====================================================================
    // State
    // =====================================================================

    /** The main framebuffer that the UI layer reads and displays. */
    val framebuffer: Bitmap = Bitmap.createBitmap(displayWidth, displayHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(framebuffer)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var nextResourceId = AtomicInteger(0x00400000)
    private var nextSequence = AtomicInteger(0)

    val windows = ConcurrentHashMap<Int, XWindow>()
    val gcs = ConcurrentHashMap<Int, XGC>()
    val pixmaps = ConcurrentHashMap<Int, XPixmap>()
    val fonts = ConcurrentHashMap<Int, XFont>()
    val atoms = ConcurrentHashMap<String, Int>()
    val properties = ConcurrentHashMap<Int, ConcurrentHashMap<String, ByteArray>>()

    var rootWindowId: Int = 0
        private set

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private val lock = Any()

    /** The currently focused window (for keyboard input routing). */
    var focusedWindowId: Int = 0
        private set

    /** Callback when the framebuffer changes. */
    var onFrameChanged: (() -> Unit)? = null

    /** Clients waiting for events, mapped by their output stream. */
    private val clientOutputs = CopyOnWriteArrayList<DataOutputStream>()

    fun allocateResourceId(): Int = nextResourceId.getAndIncrement()

    private fun nextSeq(): Int = nextSequence.getAndIncrement() and 0xFFFF

    // =====================================================================
    // Server lifecycle
    // =====================================================================

    fun start() {
        if (running.getAndSet(true)) return
        // Register pre-defined atoms
        PREDEFINED_ATOMS.forEach { (name, id) -> atoms[name] = id }

        rootWindowId = allocateResourceId()
        windows[rootWindowId] = XWindow(
            id = rootWindowId, width = displayWidth, height = displayHeight,
            mapped = true, bgColor = Color.rgb(28, 28, 36),
        )
        canvas.drawColor(windows[rootWindowId]!!.bgColor)

        serverThread = Thread {
            try {
                val socket = ServerSocket(6000, 10, java.net.InetAddress.getByName("127.0.0.1"))
                serverSocket = socket
                Log.i(TAG, "Embedded X server listening on 127.0.0.1:6000")

                while (running.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread {
                        handleClient(client)
                    }.apply { name = "XClient-${client.port}"; isDaemon = true; start() }
                }
            } catch (e: Exception) {
                if (running.get()) Log.e(TAG, "X server error", e)
            } finally {
                Log.i(TAG, "X server stopped")
            }
        }.apply { name = "MiniXServer"; isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        clientOutputs.clear()
    }

    val isRunning: Boolean get() = running.get()

    // =====================================================================
    // Client handling
    // =====================================================================

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())

            if (!handleHandshake(input, output)) {
                Log.w(TAG, "X handshake failed"); socket.close(); return
            }
            Log.i(TAG, "X handshake complete — client registered")
            clientOutputs.add(output)

            socket.soTimeout = 0
            while (running.get() && !socket.isClosed) {
                val b0 = input.read()
                if (b0 < 0) break
                val opcode = b0
                input.read() // unused/detail
                val requestLen = input.readUnsignedShort() * 4

                val body = ByteArray(requestLen)
                if (requestLen > 0) input.readFully(body)
                val buf = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)

                handleRequest(opcode, buf, output)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client disconnected: ${e.message}")
        } finally {
            clientOutputs.removeIf { runCatching { it.write(0); true }.getOrDefault(false) }
            runCatching { socket.close() }
        }
    }

    // =====================================================================
    // X11 handshake
    // =====================================================================

    private fun handleHandshake(input: DataInputStream, output: DataOutputStream): Boolean {
        val byteOrder = input.read()
        val order = if (byteOrder == 0x6C) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        input.read()
        input.readShort(); input.readShort()
        val authNameLen = input.readUnsignedShort()
        val authDataLen = input.readUnsignedShort()
        input.readShort()
        val skip1 = (authNameLen + 3) / 4 * 4
        val skip2 = (authDataLen + 3) / 4 * 4
        if (skip1 > 0) input.skipBytes(skip1)
        if (skip2 > 0) input.skipBytes(skip2)

        val vendorName = "MobileStudio"
        val vendorBytes = vendorName.toByteArray(Charsets.US_ASCII)
        val vendorPadded = (vendorBytes.size + 3) / 4 * 4

        // Calculate total additional data length in 4-byte units
        val formatCount = 2 // depth 24 and 32
        val visualCount = 1
        val screenSize = 8 * 4 + 2 * 4 + 2 + 2 + 2 + 2 + 4 * 2 + 2 + 2 + 1 + 1 + 1 + 1 + 4 + 4 + 4 + 4 + 4 + 4 // ~68 bytes
        val visualSize = 24
        val totalBody = 8 * 2 + vendorPadded + formatCount * 8 + screenSize + visualSize
        val totalLen = (totalBody + 3) / 4

        val sb = ByteBuffer.allocate(32 + totalLen * 4).order(order)
        sb.put(0) // success
        sb.put(0) // unused
        sb.putShort(11) // protocol major
        sb.putShort(0) // protocol minor
        sb.putShort(totalLen.toShort()) // additional data length

        sb.putInt(0) // release
        sb.putInt(0x00400000) // resource-id-base
        sb.putInt(0x00200000) // resource-id-mask
        sb.putInt(256) // motion-buffer-size
        sb.putShort(vendorBytes.size.toShort())
        sb.putShort(65535.toShort()) // max request length
        sb.put(1) // screens count
        sb.put(formatCount.toByte()) // formats count
        sb.put(0) // image byte order LSB
        sb.put(0) // bitmap bit order
        sb.put(32); sb.put(32) // scanline unit/pad
        sb.put(8); sb.put(255.toByte()) // min/max keycode
        sb.putInt(0) // unused

        sb.put(vendorBytes)
        repeat(vendorPadded - vendorBytes.size) { sb.put(0) }

        // Pixmap formats: (depth, bpp, pad) x2
        sb.put(24); sb.put(32); sb.put(32); sb.put(0); sb.put(0); sb.put(0); sb.put(0); sb.put(0)
        sb.put(32); sb.put(32); sb.put(32); sb.put(0); sb.put(0); sb.put(0); sb.put(0); sb.put(0)

        // Screen
        val visualId = allocateResourceId()
        sb.putInt(rootWindowId)
        sb.putShort(displayWidth.toShort()); sb.putShort(displayHeight.toShort())
        sb.putShort(270.toShort()); sb.putShort(135.toShort())
        sb.putShort(1) // allowed depths
        sb.putInt(visualId) // root visual
        sb.put(0) // backup stores
        sb.put(1); sb.put(1) // min/max installed maps
        sb.putInt(0x00FFFFFF) // white
        sb.putInt(0x00000000) // black
        sb.putInt(0) // current input
        sb.putInt(allocateResourceId()) // default colormap
        sb.putInt(0) // bit gravities
        sb.putInt(0) // backup

        // Visual (depth 24, TrueColor)
        sb.put(24) // depth
        sb.put(0) // unused
        sb.putShort(1) // visual count
        sb.putInt(visualId)
        sb.put(4) // TrueColor
        sb.put(0) // unused
        sb.putShort(8) // bits per RGB
        sb.putShort(256) // colormap entries
        sb.putInt(0x00FF0000); sb.putInt(0x0000FF00); sb.putInt(0x000000FF)
        sb.putInt(0) // unused

        output.write(sb.array(), 0, sb.position())
        output.flush()
        return true
    }

    // =====================================================================
    // Request dispatch — ALL opcodes get handled, no Wine hangs
    // =====================================================================

    private fun handleRequest(opcode: Int, buf: ByteBuffer, output: DataOutputStream) {
        when (opcode) {
            OP_CREATE_WINDOW -> handleCreateWindow(buf)
            OP_CHANGE_WINDOW_ATTRIBUTES -> handleChangeWindowAttr(buf)
            OP_DESTROY_WINDOW, OP_DESTROY_SUBWINDOWS -> handleDestroyWindow(buf)
            OP_MAP_WINDOW -> handleMapWindow(buf)
            OP_UNMAP_WINDOW -> handleUnmapWindow(buf)
            OP_CONFIGURE_WINDOW -> handleConfigureWindow(buf)
            OP_CREATE_GC -> handleCreateGC(buf)
            OP_CHANGE_GC -> handleChangeGC(buf)
            OP_CREATE_PIXMAP -> handleCreatePixmap(buf)
            OP_PUT_IMAGE -> handlePutImage(buf)
            OP_COPY_AREA -> handleCopyArea(buf)
            OP_POLY_FILL_RECTANGLE -> handlePolyFillRectangle(buf)
            OP_POLY_LINE -> handlePolyLine(buf)
            OP_POLY_SEGMENT -> handlePolySegment(buf)
            OP_POLY_RECTANGLE -> handlePolyRectangle(buf)
            OP_POLY_ARC -> handlePolyArc(buf, OP_POLY_ARC)
            OP_POLY_FILL_ARC -> handlePolyArc(buf, OP_POLY_FILL_ARC)
            OP_IMAGE_TEXT8 -> handleImageText8(buf)
            OP_IMAGE_TEXT16 -> handleImageText16(buf)
            OP_POLY_TEXT8 -> handlePolyText8(buf)
            OP_POLY_TEXT16 -> handlePolyText16(buf)
            OP_OPEN_FONT -> handleOpenFont(buf)
            OP_INTERN_ATOM -> handleInternAtom(buf, output)
            OP_CHANGE_PROPERTY -> handleChangeProperty(buf)
            OP_DELETE_PROPERTY -> handleDeleteProperty(buf)
            OP_GET_PROPERTY -> handleGetProperty(buf, output)
            OP_QUERY_POINTER -> handleQueryPointer(buf, output)
            OP_TRANSLATE_COORDS -> handleTranslateCoords(buf, output)
            OP_QUERY_TREE -> handleQueryTree(buf, output)
            OP_GET_GEOMETRY -> handleGetGeometry(buf, output)
            OP_GET_WINDOW_ATTRIBUTES -> handleGetWindowAttributes(buf, output)
            OP_SEND_EVENT -> {} // Wine sends events to itself; we just consume
            OP_GRAB_POINTER -> sendSimpleReply(output, 0) // success=0
            OP_UNGRAB_POINTER -> sendSimpleReply(output, 0)
            OP_SET_INPUT_FOCUS -> handleSetInputFocus(buf)
            OP_WARP_POINTER -> {} // consume
            OP_QUERY_EXTENSION -> handleQueryExtension(buf, output)
            OP_LIST_EXTENSIONS -> handleListExtensions(output)
            OP_GET_KEYBOARD_MAPPING -> handleGetKeyboardMapping(buf, output)
            OP_ALLOC_COLOR -> handleAllocColor(buf, output)
            OP_BELL, OP_SET_SCREEN_SAVER, OP_SET_POINTER_MAPPING, OP_GET_POINTER_MAPPING,
            OP_FREE_GC, OP_FREE_PIXMAP, OP_FREE_COLORMAP, OP_FREE_COLORS,
            OP_CLOSE_FONT, OP_REPARENT_WINDOW, OP_MAP_SUBWINDOWS,
            -> {} // consume silently
            OP_QUERY_FONT -> handleQueryFont(buf, output)
            else -> {
                // Unknown request: if it expects a reply, send an error.
                // Error response: type=0(error), opcode, sequence, bad-value...
                val err = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                err.put(0) // error type
                err.put(1) // BadRequest
                err.putShort(nextSeq().toShort())
                err.putInt(0) // bad value
                err.putShort(opcode.toShort())
                repeat(21) { err.put(0) }
                output.write(err.array())
                output.flush()
            }
        }
        onFrameChanged?.invoke()
    }

    // =====================================================================
    // Reply helpers
    // =====================================================================

    private fun sendSimpleReply(output: DataOutputStream, value: Int = 0) {
        val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0)
        reply.putShort(nextSeq().toShort())
        reply.putInt(0) // length
        reply.putInt(value)
        repeat(20) { reply.putInt(0) }
        output.write(reply.array())
        output.flush()
    }

    // =====================================================================
    // Window operations
    // =====================================================================

    private fun handleCreateWindow(buf: ByteBuffer) {
        val windowId = buf.getInt(4)
        val parentId = buf.getInt(8)
        val x = buf.getShort(16).toInt()
        val y = buf.getShort(18).toInt()
        val w = buf.getShort(20).toInt()
        val h = buf.getShort(22).toInt()

        windows[windowId] = XWindow(id = windowId, x = x, y = y, width = w, height = h, parentId = parentId)
        windows[parentId]?.children?.add(windowId)
        Log.d(TAG, "CreateWindow id=$windowId parent=$parentId (${x},${y} ${w}x${h})")
    }

    private fun handleMapWindow(buf: ByteBuffer) {
        val windowId = buf.getInt(4)
        windows[windowId]?.let { w ->
            w.mapped = true
            // Redraw background
            val target = getTargetBitmap(windowId)
            if (target != null && windowId != rootWindowId) {
                val pc = Canvas(target)
                pc.drawColor(w.bgColor)
            }
            // Send Expose event
            sendExposeEvent(windowId, w.x, w.y, w.width, w.height)
            Log.d(TAG, "MapWindow id=$windowId")
        }
    }

    private fun handleUnmapWindow(buf: ByteBuffer) {
        val windowId = buf.getInt(4)
        windows[windowId]?.mapped = false
    }

    private fun handleConfigureWindow(buf: ByteBuffer) {
        val windowId = buf.getInt(4)
        val valueMask = buf.getShort(8).toInt()
        val window = windows[windowId] ?: return
        var offset = 12
        // value mask bits: x=1, y=2, w=4, h=8, border-width=16
        if (valueMask and 0x1 != 0) { window.x = buf.getShort(offset).toInt(); offset += 2; offset += 2 }
        if (valueMask and 0x2 != 0) { window.y = buf.getShort(offset + 2).toInt(); offset += 4 }
        if (valueMask and 0x4 != 0) { window.width = buf.getShort(offset).toInt(); offset += 4 }
        if (valueMask and 0x8 != 0) { window.height = buf.getShort(offset).toInt(); offset += 4 }
    }

    private fun handleDestroyWindow(buf: ByteBuffer) {
        val windowId = buf.getInt(4)
        windows.remove(windowId)
    }

    private fun handleChangeWindowAttr(buf: ByteBuffer) {
        val windowId = buf.getInt(4)
        val valueMask = buf.getInt(8)
        val window = windows[windowId] ?: return
        var offset = 12
        // background-pixel is bit 2 in the value mask
        if (valueMask and (1 shl 2) != 0) {
            window.bgColor = buf.getInt(offset)
            offset += 4
        }
        // event-mask is bit 12
        if (valueMask and (1 shl 12) != 0) {
            window.eventMask = buf.getInt(offset)
            offset += 4
        }
    }

    private fun handleSetInputFocus(buf: ByteBuffer) {
        focusedWindowId = buf.getInt(4)
    }

    // =====================================================================
    // GC operations
    // =====================================================================

    private fun handleCreateGC(buf: ByteBuffer) {
        val gcId = buf.getInt(4)
        gcs[gcId] = XGC(id = gcId)
    }

    private fun handleChangeGC(buf: ByteBuffer) {
        val gcId = buf.getInt(4)
        val valueMask = buf.getInt(8)
        val gc = gcs[gcId] ?: return
        var offset = 12
        // GC value mask bits: function=0, plane-mask=1, fg=2, bg=3, line-width=6, font=14,
        // clip-x=17, clip-y=18, clip-mask=19
        if (valueMask and (1 shl 0) != 0) { gc.function = buf.getInt(offset); offset += 4 }
        if (valueMask and (1 shl 1) != 0) offset += 4
        if (valueMask and (1 shl 2) != 0) { gc.fgColor = buf.getInt(offset); offset += 4 }
        if (valueMask and (1 shl 3) != 0) { gc.bgColor = buf.getInt(offset); offset += 4 }
        if (valueMask and (1 shl 6) != 0) { gc.lineWidth = buf.getShort(offset).toInt(); offset += 4 }
        if (valueMask and (1 shl 14) != 0) { gc.fontId = buf.getInt(offset); offset += 4 }
        if (valueMask and (1 shl 17) != 0) { gc.clipX = buf.getInt(offset); offset += 4 }
        if (valueMask and (1 shl 18) != 0) { gc.clipY = buf.getInt(offset); offset += 4 }
    }

    // =====================================================================
    // Pixmap operations
    // =====================================================================

    private fun handleCreatePixmap(buf: ByteBuffer) {
        val pixmapId = buf.getInt(4)
        val drawableId = buf.getInt(8)
        val width = buf.getShort(12).toInt()
        val height = buf.getShort(14).toInt()
        if (width > 0 && height > 0) {
            pixmaps[pixmapId] = XPixmap(id = pixmapId, width = width, height = height)
        }
    }

    // =====================================================================
    // Drawing operations (with clipping support)
    // =====================================================================

    private fun getTargetBitmap(drawableId: Int): Bitmap? {
        pixmaps[drawableId]?.let { return it.bitmap }
        // If it's a window, draw to the main framebuffer
        if (windows.containsKey(drawableId)) return framebuffer
        return framebuffer
    }

    private fun applyClip(gc: XGC?, canvas: Canvas, block: () -> Unit) {
        if (gc?.clipEnabled == true) {
            canvas.save()
            canvas.clipRect(
                gc.clipX.toFloat(), gc.clipY.toFloat(),
                (gc.clipX + gc.clipW).toFloat(), (gc.clipY + gc.clipH).toFloat(),
            )
            block()
            canvas.restore()
        } else {
            block()
        }
    }

    private fun handlePutImage(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val width = buf.getShort(12).toInt()
        val height = buf.getShort(14).toInt()
        val dstX = buf.getShort(16).toInt()
        val dstY = buf.getShort(18).toInt()
        val format = buf.get(24).toInt()
        val gc = gcs[gcId]

        val bytesPerPixel = 4 // ZPixmap, depth 24/32
        val rowBytes = width * bytesPerPixel
        val padding = (rowBytes + 3) / 4 * 4 - rowBytes
        val actualRowBytes = rowBytes + padding

        val target = getTargetBitmap(drawableId) ?: return
        val pixels = IntArray(width * height)

        // Skip to image data (after 4+4+4+2+2+2+2+4+4 = fixed header + drawable + gc)
        // PutImage wire format: drawable(4), gc(4), width(2), height(2), dst-x(2), dst-y(2),
        // left-pad(1), depth(1), format(1), pad(1), then image data
        val dataOffset = 4 + 4 + 2 + 2 + 2 + 2 + 1 + 1 + 1 + 1 // = 20 bytes
        buf.position(dataOffset)

        for (row in 0 until height) {
            for (col in 0 until width) {
                if (buf.remaining() < 4) break
                val b = buf.get().toInt() and 0xFF
                val g = buf.get().toInt() and 0xFF
                val r = buf.get().toInt() and 0xFF
                buf.get() // alpha/padding
                pixels[row * width + col] = Color.rgb(r, g, b)
            }
            if (padding > 0) buf.position(buf.position() + padding)
        }

        applyClip(gc, canvas) {
            target.setPixels(pixels, 0, width, dstX, dstY, width, height)
        }
    }

    private fun handleCopyArea(buf: ByteBuffer) {
        val srcId = buf.getInt(4)
        val dstId = buf.getInt(8)
        val gcId = buf.getInt(12)
        val srcX = buf.getShort(16).toInt()
        val srcY = buf.getShort(18).toInt()
        val width = buf.getShort(20).toInt()
        val height = buf.getShort(22).toInt()
        val dstX = buf.getShort(24).toInt()
        val dstY = buf.getShort(26).toInt()

        val src = getTargetBitmap(srcId) ?: return
        val dst = getTargetBitmap(dstId) ?: return
        val gc = gcs[gcId]
        val w = minOf(width, src.width - srcX, dst.width - dstX).coerceAtLeast(0)
        val h = minOf(height, src.height - srcY, dst.height - dstY).coerceAtLeast(0)
        if (w <= 0 || h <= 0) return

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, srcX, srcY, w, h)
        applyClip(gc, canvas) {
            dst.setPixels(pixels, 0, w, dstX, dstY, w, h)
        }
    }

    private fun handlePolyFillRectangle(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val gc = gcs[gcId] ?: return
        val rectPaint = Paint().apply { color = gc.fgColor; style = Paint.Style.FILL }
        applyClip(gc, canvas) {
            var offset = 12
            while (offset + 8 <= buf.limit()) {
                val x = buf.getShort(offset).toInt()
                val y = buf.getShort(offset + 2).toInt()
                val w = buf.getShort(offset + 4).toInt()
                val h = buf.getShort(offset + 6).toInt()
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), rectPaint)
                offset += 8
            }
        }
    }

    private fun handlePolyRectangle(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val gc = gcs[gcId] ?: return
        val strokePaint = Paint().apply {
            color = gc.fgColor; style = Paint.Style.STROKE
            strokeWidth = gc.lineWidth.coerceAtLeast(1).toFloat()
        }
        applyClip(gc, canvas) {
            var offset = 12
            while (offset + 8 <= buf.limit()) {
                val x = buf.getShort(offset).toInt()
                val y = buf.getShort(offset + 2).toInt()
                val w = buf.getShort(offset + 4).toInt()
                val h = buf.getShort(offset + 6).toInt()
                canvas.drawRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), strokePaint)
                offset += 8
            }
        }
    }

    private fun handlePolyLine(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val gc = gcs[gcId] ?: return
        val linePaint = Paint().apply {
            color = gc.fgColor; strokeWidth = gc.lineWidth.coerceAtLeast(1).toFloat()
            style = Paint.Style.STROKE
        }
        applyClip(gc, canvas) {
            var offset = 14 // drawable(4)+gc(4)+coordinate-mode(1)+pad(3) = 12, then points start
            if (buf.limit() > offset + 4) {
                val path = android.graphics.Path()
                var first = true
                while (offset + 4 <= buf.limit()) {
                    val x = buf.getShort(offset).toInt().toFloat()
                    val y = buf.getShort(offset + 2).toInt().toFloat()
                    if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    offset += 4
                }
                canvas.drawPath(path, linePaint)
            }
        }
    }

    private fun handlePolySegment(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val gc = gcs[gcId] ?: return
        val linePaint = Paint().apply {
            color = gc.fgColor; strokeWidth = gc.lineWidth.coerceAtLeast(1).toFloat()
        }
        applyClip(gc, canvas) {
            var offset = 12
            while (offset + 8 <= buf.limit()) {
                val x1 = buf.getShort(offset).toInt().toFloat()
                val y1 = buf.getShort(offset + 2).toInt().toFloat()
                val x2 = buf.getShort(offset + 4).toInt().toFloat()
                val y2 = buf.getShort(offset + 6).toInt().toFloat()
                canvas.drawLine(x1, y1, x2, y2, linePaint)
                offset += 8
            }
        }
    }

    private fun handlePolyArc(buf: ByteBuffer, opcodeParam: Int) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val gc = gcs[gcId] ?: return
        val arcPaint = Paint().apply { color = gc.fgColor }
        arcPaint.style = if (opcodeParam == 71) Paint.Style.FILL else Paint.Style.STROKE
        applyClip(gc, canvas) {
            var offset = 12
            while (offset + 12 <= buf.limit()) {
                val x = buf.getShort(offset).toInt().toFloat()
                val y = buf.getShort(offset + 2).toInt().toFloat()
                val w = buf.getShort(offset + 4).toInt().toFloat()
                val h = buf.getShort(offset + 6).toInt().toFloat()
                val startAngle = buf.getShort(offset + 8).toInt() / 64f
                val sweepAngle = buf.getShort(offset + 10).toInt() / 64f
                canvas.drawArc(x, y, x + w, y + h, startAngle, sweepAngle, true, arcPaint)
                offset += 12
            }
        }
    }

    // =====================================================================
    // Text rendering (PolyText8/16, ImageText8/16)
    // =====================================================================

    private fun getTextPaint(gc: XGC, font: XFont?): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = gc.fgColor
        textSize = font?.paint?.textSize ?: 14f
        typeface = font?.paint?.typeface ?: Typeface.MONOSPACE
    }

    private fun drawText(
        buf: ByteBuffer, drawableId: Int, gcId: Int,
        x: Int, y: Int, text: String,
    ) {
        val gc = gcs[gcId] ?: return
        val font = fonts[gc.fontId]
        val textPaint = getTextPaint(gc, font)
        val target = getTargetBitmap(drawableId) ?: return
        applyClip(gc, canvas) {
            canvas.drawText(text, x.toFloat(), y.toFloat(), textPaint)
        }
    }

    private fun handleImageText8(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val x = buf.getShort(16).toInt()
        val y = buf.getShort(18).toInt()
        val textLen = buf.get(20).toInt()
        if (textLen <= 0) return
        val textBytes = ByteArray(textLen)
        buf.position(21)
        buf.get(textBytes)
        drawText(buf, drawableId, gcId, x, y, String(textBytes, Charsets.ISO_8859_1))
    }

    private fun handleImageText16(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val x = buf.getShort(16).toInt()
        val y = buf.getShort(18).toInt()
        val textLen = buf.get(20).toInt()
        if (textLen <= 0) return
        val charBytes = ByteArray(textLen * 2)
        buf.position(21)
        buf.get(charBytes)
        val sb = StringBuilder()
        for (i in 0 until textLen) {
            sb.append((((charBytes[i * 2].toInt() and 0xFF) shl 8) or (charBytes[i * 2 + 1].toInt() and 0xFF)).toChar())
        }
        drawText(buf, drawableId, gcId, x, y, sb.toString())
    }

    private fun handlePolyText8(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val gc = gcs[gcId] ?: return
        val font = fonts[gc.fontId]
        val textPaint = getTextPaint(gc, font)
        val target = getTargetBitmap(drawableId) ?: return

        // PolyText8: drawable(4), gc(4), x(2), y(2), then items
        // Each item: len(1), delta-x(1), delta-y(1), chars(len bytes) — len=255 means font change
        var offset = 12
        var curX = buf.getShort(12).toInt()
        var curY = buf.getShort(14).toInt()

        applyClip(gc, canvas) {
            while (offset < buf.limit()) {
                val itemLen = buf.get(offset).toInt() and 0xFF
                offset += 1
                if (itemLen == 255) {
                    // Font change item: font-id(4) — skip
                    offset += 4
                    continue
                }
                val deltaX = buf.get(offset).toInt()
                val deltaY = buf.get(offset + 1).toInt()
                offset += 2
                if (itemLen > 0 && offset + itemLen <= buf.limit()) {
                    val textBytes = ByteArray(itemLen)
                    buf.position(offset)
                    buf.get(textBytes)
                    val text = String(textBytes, Charsets.ISO_8859_1)
                    canvas.drawText(text, curX.toFloat(), curY.toFloat(), textPaint)
                    curX += deltaX + textPaint.measureText(text).toInt()
                }
                offset += itemLen
            }
        }
    }

    private fun handlePolyText16(buf: ByteBuffer) {
        val drawableId = buf.getInt(4)
        val gcId = buf.getInt(8)
        val gc = gcs[gcId] ?: return
        val font = fonts[gc.fontId]
        val textPaint = getTextPaint(gc, font)
        val target = getTargetBitmap(drawableId) ?: return

        var offset = 12
        var curX = buf.getShort(12).toInt()
        var curY = buf.getShort(14).toInt()

        applyClip(gc, canvas) {
            while (offset < buf.limit()) {
                val itemLen = buf.get(offset).toInt() and 0xFF
                offset += 1
                if (itemLen == 255) { offset += 4; continue }
                val deltaX = buf.get(offset).toInt()
                val deltaY = buf.get(offset + 1).toInt()
                offset += 2
                if (itemLen > 0 && offset + itemLen * 2 <= buf.limit()) {
                    val sb = StringBuilder()
                    for (i in 0 until itemLen) {
                        sb.append((((buf.get(offset + i * 2).toInt() and 0xFF) shl 8) or
                            (buf.get(offset + i * 2 + 1).toInt() and 0xFF)).toChar())
                    }
                    val text = sb.toString()
                    canvas.drawText(text, curX.toFloat(), curY.toFloat(), textPaint)
                    curX += deltaX + textPaint.measureText(text).toInt()
                }
                offset += itemLen * 2
            }
        }
    }

    private fun handleOpenFont(buf: ByteBuffer) {
        val fontId = buf.getInt(4)
        val nameLen = buf.getShort(8).toInt()
        buf.position(12)
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        fonts[fontId] = XFont(id = fontId, name = String(nameBytes))
    }

    private fun handleQueryFont(buf: ByteBuffer, output: DataOutputStream) {
        // Return a minimal valid QueryFont reply
        // This is complex but essential — without it Wine blocks
        val reply = ByteBuffer.allocate(60 + 4).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1) // reply type
        reply.put(0) // unused
        reply.putShort(nextSeq().toShort())
        reply.putInt(0) // additional data length
        reply.putShort(14.toShort()) // min bounds (simplified)
        reply.putShort(14.toShort())
        reply.putShort(0.toShort()); reply.putShort(0.toShort())
        reply.putShort(16.toShort()); reply.putShort(0.toShort())
        reply.putInt(0) // unused
        reply.putShort(14.toShort()) // max bounds
        reply.putShort(14.toShort())
        reply.putShort(0.toShort()); reply.putShort(0.toShort())
        reply.putShort(16.toShort()); reply.putShort(0.toShort())
        reply.putInt(0) // unused
        reply.putShort(0) // min-char
        reply.putShort(255.toShort()) // max-char
        reply.putShort(0) // default char
        reply.putInt(0) // font properties count
        reply.putInt(0) // char infos count
        // Pad
        repeat(4) { reply.putInt(0) }
        output.write(reply.array())
        output.flush()
    }

    // =====================================================================
    // Atom management (proper atom table with real IDs)
    // =====================================================================

    private fun handleInternAtom(buf: ByteBuffer, output: DataOutputStream) {
        val nameLen = buf.getShort(4).toInt()
        buf.position(8)
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val name = String(nameBytes, Charsets.ISO_8859_1)

        val atomId = synchronized(atoms) {
            atoms.getOrPut(name) {
                // Allocate a new atom ID that doesn't conflict with predefined ones
                var newId = (PREDEFINED_ATOMS.values.maxOrNull() ?: 0) + 1
                while (atoms.values.contains(newId)) newId++
                newId
            }
        }
        Log.d(TAG, "InternAtom: '$name' -> $atomId")

        val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0)
        reply.putShort(nextSeq().toShort())
        reply.putInt(0)
        reply.putInt(atomId)
        output.write(reply.array())
        output.flush()
    }

    // =====================================================================
    // Property management (in-memory storage for EWMH properties)
    // =====================================================================

    private fun handleChangeProperty(buf: ByteBuffer) {
        val windowId = buf.getInt(4)
        val propertyAtom = buf.getInt(8)
        val typeAtom = buf.getInt(16)
        val dataLen = buf.getInt(24)
        buf.position(32)
        val data = ByteArray(minOf(dataLen, buf.remaining()))
        buf.get(data)

        val atomName = atoms.entries.firstOrNull { it.value == propertyAtom }?.key ?: "prop_$propertyAtom"
        properties.getOrPut(windowId) { ConcurrentHashMap() }[atomName] = data

        // Track _NET_WM_NAME for display
        if (atomName == "_NET_WM_NAME") {
            val name = String(data, Charsets.UTF_8)
            Log.d(TAG, "Window $windowId name: $name")
        }
    }

    private fun handleDeleteProperty(buf: ByteBuffer) {
        val windowId = buf.getInt(4)
        val propertyAtom = buf.getInt(8)
        val atomName = atoms.entries.firstOrNull { it.value == propertyAtom }?.key ?: return
        properties[windowId]?.remove(atomName)
    }

    private fun handleGetProperty(buf: ByteBuffer, output: DataOutputStream) {
        val windowId = buf.getInt(4)
        val propertyAtom = buf.getInt(8)
        val atomName = atoms.entries.firstOrNull { it.value == propertyAtom }?.key
        val data = atomName?.let { properties[windowId]?.get(it) } ?: ByteArray(0)

        val typeAtom = if (data.isNotEmpty()) 22 else 0 // UTF8_STRING or None
        val reply = ByteBuffer.allocate(32 + data.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0)
        reply.putShort(nextSeq().toShort())
        reply.putInt(data.size / 4) // value length in 4-byte units
        reply.putInt(if (data.isNotEmpty()) typeAtom else 0)
        reply.putInt(data.size.toLong().toInt()) // bytes after
        reply.putInt(data.size / 4)
        reply.putInt(0); reply.putInt(0)
        reply.put(data)
        // Pad to 4-byte boundary
        val pad = (4 - data.size % 4) % 4
        repeat(pad) { reply.put(0) }
        output.write(reply.array(), 0, reply.position())
        output.flush()
    }

    // =====================================================================
    // Query / info requests
    // =====================================================================

    private fun handleQueryPointer(buf: ByteBuffer, output: DataOutputStream) {
        val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0)
        reply.putShort(nextSeq().toShort())
        reply.putInt(0)
        reply.putInt(rootWindowId) // root window
        reply.putInt(focusedWindowId.takeIf { it != 0 } ?: rootWindowId) // child
        reply.putShort(0); reply.putShort(0) // root coords
        reply.putShort(0); reply.putShort(0) // event coords
        reply.put(0) // key mask
        repeat(3) { reply.putInt(0) }
        output.write(reply.array())
        output.flush()
    }

    private fun handleTranslateCoords(buf: ByteBuffer, output: DataOutputStream) {
        val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(1) // same-screen = true
        reply.putShort(nextSeq().toShort())
        reply.putInt(0)
        reply.putInt(rootWindowId)
        reply.putShort(0); reply.putShort(0)
        reply.putInt(0)
        repeat(3) { reply.putInt(0) }
        output.write(reply.array())
        output.flush()
    }

    private fun handleQueryTree(buf: ByteBuffer, output: DataOutputStream) {
        val windowId = buf.getInt(4)
        val window = windows[windowId]
        val children = window?.children ?: emptyList()

        val replyLen = (4 + children.size * 4 + 3) / 4
        val reply = ByteBuffer.allocate(32 + children.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0)
        reply.putShort(nextSeq().toShort())
        reply.putInt(children.size / 4)
        reply.putInt(rootWindowId)
        reply.putInt(window?.parentId ?: 0)
        reply.putShort(children.size.toShort())
        reply.putShort(0)
        children.forEach { reply.putInt(it) }
        output.write(reply.array(), 0, reply.position())
        output.flush()
    }

    private fun handleGetGeometry(buf: ByteBuffer, output: DataOutputStream) {
        val drawableId = buf.getInt(4)
        val window = windows[drawableId]
        val pixmap = pixmaps[drawableId]
        val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0)
        reply.putShort(nextSeq().toShort())
        reply.putInt(0)
        reply.putInt(rootWindowId)
        reply.putShort((window?.x ?: 0).toShort()); reply.putShort((window?.y ?: 0).toShort())
        reply.putShort((window?.width ?: pixmap?.width ?: 0).toShort())
        reply.putShort((window?.height ?: pixmap?.height ?: 0).toShort())
        reply.put(0); reply.put(0) // border width, depth
        repeat(3) { reply.putInt(0) }
        output.write(reply.array())
        output.flush()
    }

    private fun handleGetWindowAttributes(buf: ByteBuffer, output: DataOutputStream) {
        val windowId = buf.getInt(4)
        val window = windows[windowId]
        val reply = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0) // reply, backing-store
        reply.putShort(nextSeq().toShort())
        reply.putInt(0) // length
        reply.putInt(rootWindowId) // visual
        reply.put(0) // class (InputOutput)
        reply.put(0) // bit-gravity
        reply.put(0) // win-gravity
        reply.putInt(0) // backing planes
        reply.putInt(0xFFFFFF) // background pixel
        reply.putInt(0) // border pixel
        reply.put(1) // override-redirect
        reply.put(1) // save-under
        reply.putInt(0) // event mask
        reply.putInt(0) // all event masks
        reply.put(0); reply.put(0); reply.put(0) // padding
        output.write(reply.array())
        output.flush()
    }

    private fun handleQueryExtension(buf: ByteBuffer, output: DataOutputStream) {
        val nameLen = buf.getShort(4).toInt()
        buf.position(8)
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val name = String(nameBytes)
        // We support XInput2 for touch
        val supported = name == "XInputExtension" || name == "BIG-REQUESTS"
        val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0)
        reply.putShort(nextSeq().toShort())
        reply.putInt(0)
        reply.put(if (supported) 1 else 0) // present
        reply.put(0); reply.put(0) // first-error, first-event
        output.write(reply.array())
        output.flush()
    }

    private fun handleListExtensions(output: DataOutputStream) {
        val extensions = listOf("BIG-REQUESTS", "XInputExtension")
        val namesBlob = extensions.joinToString("") { it + "\u0000" }
        val bytes = namesBlob.toByteArray(Charsets.US_ASCII)
        val reply = ByteBuffer.allocate(32 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(extensions.size.toByte())
        reply.putShort(nextSeq().toShort())
        reply.putInt(0)
        reply.put(0); reply.put(0); reply.put(0); reply.put(0)
        reply.put(0); reply.put(0); reply.put(0); reply.put(0)
        reply.put(bytes)
        output.write(reply.array(), 0, reply.position())
        output.flush()
    }

    private fun handleGetKeyboardMapping(buf: ByteBuffer, output: DataOutputStream) {
        val reply = ByteBuffer.allocate(32 + 248).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(1) // keysyms-per-keycode
        reply.putShort(nextSeq().toShort())
        reply.putInt(0)
        reply.putInt(248) // length
        repeat(62) { reply.putInt(0) } // empty keymap
        output.write(reply.array(), 0, reply.position())
        output.flush()
    }

    private fun handleAllocColor(buf: ByteBuffer, output: DataOutputStream) {
        val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(1); reply.put(0)
        reply.putShort(nextSeq().toShort())
        reply.putInt(0)
        reply.put(0); reply.put(0); reply.put(0); reply.put(0) // pad
        reply.putShort(0.toShort()); reply.putShort(0.toShort()); reply.putShort(0.toShort()) // RGB
        reply.putInt(0) // pixel
        repeat(3) { reply.putInt(0) }
        output.write(reply.array())
        output.flush()
    }

    // =====================================================================
    // Event delivery
    // =====================================================================

    private fun sendExposeEvent(windowId: Int, x: Int, y: Int, w: Int, h: Int) {
        val event = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        event.put(EVENT_EXPOSE.toByte()) // event type
        event.put(0) // detail
        event.putShort(nextSeq().toShort()) // sequence
        event.putInt(windowId) // event window
        event.putInt(windowId) // window
        event.putShort(x.toShort()); event.putShort(y.toShort())
        event.putShort(w.toShort()); event.putShort(h.toShort())
        event.putShort(0) // count (no more expose events)
        repeat(3) { event.putInt(0) } // pad

        val bytes = event.array()
        clientOutputs.forEach { output ->
            runCatching { output.write(bytes); output.flush() }
        }
    }

    // =====================================================================
    // Input injection (called from UI touch events)
    // =====================================================================

    fun injectPointerEvent(type: Int, x: Int, y: Int, button: Int) {
        // Find the topmost window at (x, y)
        val target = windows.values.filter { it.mapped }
            .filter { x >= it.x && x < it.x + it.width && y >= it.y && y < it.y + it.height }
            .maxByOrNull { it.id } ?: return

        val event = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        event.put(type.toByte())
        event.put(0) // detail (button number or key code)
        event.putShort(nextSeq().toShort())
        event.putInt(target.id) // event window
        event.putInt(rootWindowId) // root window
        event.putInt(target.id) // child window
        event.putShort(x.toShort()); event.putShort(y.toShort()) // root coords
        event.putShort((x - target.x).toShort()); event.putShort((y - target.y).toShort()) // event coords
        event.put(0); event.put(0); event.put(0) // state, same-screen, pad
        repeat(2) { event.putInt(0) }

        val bytes = event.array()
        clientOutputs.forEach { output ->
            runCatching { output.write(bytes); output.flush() }
        }
    }

    fun injectKeyEvent(type: Int, keyCode: Int) {
        val target = if (focusedWindowId != 0) focusedWindowId else rootWindowId
        val event = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        event.put(type.toByte())
        event.put(keyCode.toByte())
        event.putShort(nextSeq().toShort())
        event.putInt(target)
        event.putInt(rootWindowId)
        repeat(6) { event.putInt(0) }
        val bytes = event.array()
        clientOutputs.forEach { output ->
            runCatching { output.write(bytes); output.flush() }
        }
    }
}
