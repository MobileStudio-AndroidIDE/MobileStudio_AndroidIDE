package com.aide.mobilestudio.wine

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * X11 Extensions Handler - XInput2, XRender, XComposite, XDamage, XFixes, XRandR, XKB, GLX
 *
 * Wine requires these extensions for proper GUI operation:
 *   - XInput2: Tablet/touch input, extended device events
 *   - XRender: Antialiased text, compositing, picture operations
 *   - XComposite: Redirect window drawing to off-screen pixmaps
 *   - XDamage: Track damaged regions for efficient redraw
 *   - XFixes: Cursor images, region operations, selection notification
 *   - XRandR: Screen resolution, rotation, output management
 *   - XKB: Keyboard extension, indicators, bell
 *   - GLX: OpenGL rendering to X drawables
 */
class X11Extensions(
    private val server: MiniXServer
) {
    companion object {
        private const val TAG = "X11Extensions"

        // Extension base opcodes (assigned dynamically at connection setup)
        var extBase = IntArray(8) { 0 }

        // Extension event/error bases
        var extEventBase = IntArray(8) { 0 }
        var extErrorBase = IntArray(8) { 0 }

        // Extension indices
        const val IDX_BIG_REQUESTS = 0
        const val IDX_XINPUT = 1
        const val IDX_XKB = 2
        const val IDX_XRANDR = 3
        const val IDX_XFIXES = 4
        const val IDX_XSHAPE = 5
        const val IDX_XCOMPOSITE = 6
        const val IDX_XDAMAGE = 7
        const val IDX_XRENDER = 8
        const val IDX_GLX = 9
        const val IDX_MITSHM = 10
        const val IDX_XCMISC = 11
        const val IDX_DRI3 = 12
        const val IDX_PRESENT = 13

        // XInput2 minor opcodes
        const val XI_QUERY_VERSION = 0
        const val XI_DEVICE_CHANGED_NOTIFY = 1
        const val XI_LIST_DEVICES = 2

        // Extension names
        val EXTENSION_NAMES = listOf(
            "BIG-REQUESTS",
            "XInputExtension",
            "XKEYBOARD",
            "XINERAMA",
            "XFIXES",
            "XRENDER",
            "RANDR",
            "XTEST",
            "XINPUT",
            "MIT-SHM",
            "XC-MISC",
            "XShape",
            "Composite",
            "Damage",
            "GLX",
            "DRI3",
            "Present"
        )

        // XFixes opcodes (requests)
        const val XFixes_QUERY_VERSION = 0
        const val XFixes_CREATE_REGION = 1
        const val XFixes_CREATE_REGION_FROM_BITMAP = 2
        const val XFixes_CREATE_REGION_FROM_WINDOW = 3
        const val XFixes_CREATE_REGION_FROM_GC = 4
        const val XFixes_CREATE_REGION_FROM_PICTURE = 5
        const val XFixes_DESTROY_REGION = 6
        const val XFixes_SET_REGION = 7
        const val XFixes_COPY_REGION = 8
        const val XFixes_UNION_REGION = 9
        const val XFixes_INTERSECT_REGION = 10
        const val XFixes_SUBTRACT_REGION = 11
        const val XFixes_INVERT_REGION = 12
        const val XFixes_TRANSLATE_REGION = 13
        const val XFixes_REGION_EXTENTS = 14
        const val XFixes_FETCH_REGION = 15
        const val XFixes_SET_GC_CLIP_REGION = 16
        const val XFixes_SET_WINDOW_CLIP_REGION = 17
        const val XFixes_SET_PICTURE_CLIP_REGION = 18
        const val XFixes_SET_CURSOR = 19
        const val XFixes_SET_CURSOR_NAME = 20
        const val XFixes_GET_CURSOR_IMAGE = 21
        const val XFixes_GET_CURSOR_NAME_AND_IMAGE = 22
        const val XFixes_SET_CURSOR_IMAGE = 23
        const val XFixes_CURSOR_IMAGE_SIZE = 32

        // XFixes events
        const val XFixes_CURSOR_NOTIFY = 0
        const val XFixes_REGION_NOTIFY = 1

        // XRender opcodes
        const val XRender_QUERY_VERSION = 0
        const val XRender_CREATE_PICTURE = 6
        const val XRender_FREE_PICTURE = 7
        const val XRender_COMPOSITE = 8

        // XComposite opcodes
        const val XComposite_QUERY_VERSION = 0
        const val XComposite_REDIRECT_WINDOW = 2
        const val XComposite_UNREDIRECT_WINDOW = 3

        // XDamage opcodes
        const val XDamage_QUERY_VERSION = 0
        const val XDamage_CREATE = 2

        // XRandR opcodes
        const val XRandR_QUERY_VERSION = 0
        const val XRandR_GET_SCREEN_RESOURCES = 2

        // XKB opcodes
        const val XKB_QUERY_EXTENSION = 0
        const val XKB_USE_EXTENSION = 1
    }

    // =====================================================================
    // Extension registration
    // =====================================================================

    fun registerExtensions() {
        // Assign base opcodes dynamically
        var nextBase = 128
        extBase[IDX_BIG_REQUESTS] = nextBase++
        extBase[IDX_XINPUT] = nextBase++
        extBase[IDX_XKB] = nextBase++
        extBase[IDX_XRANDR] = nextBase++
        extBase[IDX_XFIXES] = nextBase++
        extBase[IDX_XSHAPE] = nextBase++
        extBase[IDX_XCOMPOSITE] = nextBase++
        extBase[IDX_XDAMAGE] = nextBase++
        extBase[IDX_XRENDER] = nextBase++
        extBase[IDX_GLX] = nextBase++
        extBase[IDX_MITSHM] = nextBase++
        extBase[IDX_XCMISC] = nextBase++
        extBase[IDX_DRI3] = nextBase++
        extBase[IDX_PRESENT] = nextBase++

        Log.i(TAG, "Registered ${
            EXTENSION_NAMES.size
        } X11 extensions")
    }

    // =====================================================================
    // Extension request dispatch
    // =====================================================================

    fun handleExtensionRequest(
        opcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        // Find which extension this opcode belongs to
        val extIdx = findExtensionIndex(opcode)
        if (extIdx < 0) return false

        val minorOpcode = buf.getShort(2).toInt() and 0xFFFF

        return when (extIdx) {
            IDX_BIG_REQUESTS -> handleBigRequests(opcode, buf, output)
            IDX_XINPUT -> handleXInput(opcode, minorOpcode, buf, output)
            IDX_XKB -> handleXKB(opcode, minorOpcode, buf, output)
            IDX_XRANDR -> handleXRandR(opcode, minorOpcode, buf, output)
            IDX_XFIXES -> handleXFixes(opcode, minorOpcode, buf, output)
            IDX_XSHAPE -> handleXShape(opcode, minorOpcode, buf, output)
            IDX_XCOMPOSITE -> handleXComposite(opcode, minorOpcode, buf, output)
            IDX_XDAMAGE -> handleXDamage(opcode, minorOpcode, buf, output)
            IDX_XRENDER -> handleXRender(opcode, minorOpcode, buf, output)
            IDX_GLX -> handleGLX(opcode, minorOpcode, buf, output)
            IDX_MITSHM -> handleMITSHM(opcode, minorOpcode, buf, output)
            IDX_XCMISC -> handleXCMISC(opcode, minorOpcode, buf, output)
            IDX_DRI3 -> handleDRI3(opcode, minorOpcode, buf, output)
            IDX_PRESENT -> handlePresent(opcode, minorOpcode, buf, output)
            else -> false
        }
    }

    private fun findExtensionIndex(opcode: Int): Int {
        for (i in extBase.indices) {
            if (opcode >= extBase[i] && opcode < extBase[i] + 256) {
                return i
            }
        }
        return -1
    }

    // =====================================================================
    // BIG-REQUESTS extension
    // =====================================================================

    private fun handleBigRequests(
        opcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        // BIG-REQUESTS allows requests larger than 65535 bytes
        val minorOpcode = buf.getShort(2).toInt() and 0xFFFF
        when (minorOpcode) {
            0 -> {
                // Enable big requests
                sendExtensionReply(output, opcode, true)
            }
            else -> {
                sendExtensionError(output, opcode, minorOpcode)
            }
        }
        return true
    }

    // =====================================================================
    // XInput2 extension
    // =====================================================================

    private fun handleXInput(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XInput2 minorOpcode=$minorOpcode")
        when (minorOpcode) {
            XI_QUERY_VERSION -> { // 0
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(2); reply.put(3) // version 2.3
                output.write(reply.array())
                output.flush()
            }
            XI_DEVICE_CHANGED_NOTIFY -> { // 1
                sendExtensionReply(output, opcode, true)
            }
            XI_LIST_DEVICES -> { // 2 - List input devices (master/slave pointer/keyboard)
                val serverRef = this.server
                val devices = if (serverRef.inputDevices.isEmpty()) {
                    // Register default devices if none exist
                    serverRef.inputDevices[1] = MiniXServer.XInputDevice(1, 3, "Virtual core pointer", true)
                    serverRef.inputDevices[2] = MiniXServer.XInputDevice(2, 4, "Virtual core keyboard", true)
                    serverRef.inputDevices[3] = MiniXServer.XInputDevice(3, 5, "Virtual core XTEST pointer", true)
                    serverRef.inputDevices[4] = MiniXServer.XInputDevice(4, 6, "Virtual core XTEST keyboard", true)
                    serverRef.inputDevices
                } else {
                    serverRef.inputDevices
                }
                val reply = ByteBuffer.allocate(32 + devices.size * 24).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(devices.size.toByte()) // success, num devices
                reply.putShort(serverRef.nextSeq().toShort())
                reply.putInt(devices.size * 6) // length in 4-byte units
                for ((id, dev) in devices) {
                    reply.putInt(id.toInt()) // deviceid
                    reply.put(dev.type.toByte()) // type
                    reply.put(0) // flags
                    reply.putShort(0.toShort()) // num_classes
                    reply.putShort(0.toShort()) // num scroll
                    reply.put(0); reply.put(0) // padding
                    // Device name (null-terminated, padded to 4 bytes)
                    val nameBytes = dev.name.toByteArray()
                    val padded = (nameBytes.size + 3) / 4 * 4
                    reply.put(nameBytes)
                    repeat(padded - nameBytes.size) { reply.put(0) }
                }
                output.write(reply.array(), 0, reply.position())
                output.flush()
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // XKB extension
    // =====================================================================

    private fun handleXKB(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XKB minorOpcode=$minorOpcode")
        when (minorOpcode) {
            XKB_QUERY_EXTENSION -> {
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(1) // supported
                reply.put(1); reply.put(0) // major, minor version
                output.write(reply.array())
                output.flush()
            }
            XKB_USE_EXTENSION -> {
                // Use XKB extension
                sendExtensionReply(output, opcode, true)
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // XRandR extension
    // =====================================================================

    private fun handleXRandR(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XRandR minorOpcode=$minorOpcode")
        when (minorOpcode) {
            XRandR_QUERY_VERSION -> {
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(1); reply.put(6) // version 1.6
                output.write(reply.array())
                output.flush()
            }
            XRandR_GET_SCREEN_RESOURCES -> {
                // Return screen resources with current mode
                val reply = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(8) // length
                reply.putInt(server.rootWindowId) // root window
                reply.putInt(0) // timestamp
                reply.putInt(1) // number of CRTCs
                reply.putInt(1) // number of outputs
                reply.putInt(1) // number of modes
                reply.putInt(server.displayWidth) // current mode width
                reply.putInt(server.displayHeight) // current mode height
                reply.putInt(60) // dot clock (kHz)
                output.write(reply.array())
                output.flush()
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // XFixes extension
    // =====================================================================

    // =====================================================================
    // XFixes extension
    // =====================================================================

    private fun handleXFixes(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XFixes minorOpcode=$minorOpcode")
        when (minorOpcode) {
            XFixes_QUERY_VERSION -> {
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(1); reply.put(5) // version 1.5
                output.write(reply.array())
                output.flush()
            }
            XFixes_GET_CURSOR_IMAGE -> {
                // XFixesGetCursorImage: return current cursor image (21)
                val serverRef = this.server
                val img = serverRef.cursorImage
                val w = if (img != null) XFixes_CURSOR_IMAGE_SIZE else 0
                val h = if (img != null) XFixes_CURSOR_IMAGE_SIZE else 0
                val dataSize = w * h * 4
                val reply = ByteBuffer.allocate(32 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(serverRef.nextSeq().toShort())
                reply.putInt((8 + dataSize / 4).toInt()) // length in 4-byte units
                reply.putInt(w) // width
                reply.putInt(h) // height
                reply.putInt(serverRef.cursorHotX) // xhot
                reply.putInt(serverRef.cursorHotY) // yhot
                reply.putInt(serverRef.cursorSerial) // cursor serial
                if (img != null) {
                    reply.put(img)
                }
                output.write(reply.array())
                output.flush()
            }
            XFixes_SET_CURSOR_IMAGE -> {
                // XFixesSetCursorImage: Wine sets a new cursor image (23)
                // Request format: [1 byte major opcode][1 byte minor opcode]
                // [2 bytes sequence][4 bytes length][4 bytes cursor id]
                // [4 bytes xhot][4 bytes yhot][4 bytes width][4 bytes height]
                // [4 bytes cursor serial][w*h*4 bytes ARGB image data]
                val serverRef = this.server
                val cursorId = buf.getInt(4)
                val xhot = buf.getInt(8).toInt()
                val yhot = buf.getInt(12).toInt()
                val width = buf.getInt(16).toInt()
                val height = buf.getInt(20).toInt()
                val serial = buf.getInt(24).toInt()

                val dataSize = width * height * 4
                val imgData = if (dataSize > 0 && buf.remaining() >= dataSize) {
                    val data = ByteArray(dataSize)
                    buf.get(data)
                    data
                } else {
                    null
                }

                serverRef.cursorImage = imgData
                serverRef.cursorHotX = xhot
                serverRef.cursorHotY = yhot
                serverRef.cursorSerial = serial

                Log.d(TAG, "Cursor image set: id=$cursorId ${width}x${height} hot=($xhot,$yhot) serial=$serial")
                sendExtensionReply(output, opcode, true)
            }
            XFixes_SET_CURSOR -> {
                // XFixesSetCursor: set cursor for a window (19)
                sendExtensionReply(output, opcode, true)
            }
            XFixes_SET_CURSOR_NAME -> {
                // XFixesSetCursorName: set cursor name (20)
                sendExtensionReply(output, opcode, true)
            }
            else -> {
                // Region operations (1-17) and others: reply success
                sendExtensionReply(output, opcode, true)
            }
        }
        return true
    }

    // =====================================================================
    // XShape extension
    // =====================================================================

    private fun handleXShape(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XShape minorOpcode=$minorOpcode")
        sendExtensionReply(output, opcode, true)
        return true
    }

    // =====================================================================
    // MIT-SHM extension
    // =====================================================================

    private fun handleMITSHM(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "MIT-SHM minorOpcode=$minorOpcode")
        sendExtensionReply(output, opcode, true)
        return true
    }

    // =====================================================================
    // XC-MISC extension
    // =====================================================================

    private fun handleXCMISC(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XC-MISC minorOpcode=$minorOpcode")
        sendExtensionReply(output, opcode, true)
        return true
    }

    // =====================================================================
    // XComposite extension
    // =====================================================================

    private fun handleXComposite(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XComposite minorOpcode=$minorOpcode")
        when (minorOpcode) {
            XComposite_QUERY_VERSION -> {
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(0); reply.put(0) // major, minor
                output.write(reply.array())
                output.flush()
            }
            XComposite_REDIRECT_WINDOW -> {
                // Redirect window drawing to off-screen pixmap
                sendExtensionReply(output, opcode, true)
            }
            XComposite_UNREDIRECT_WINDOW -> {
                // Stop redirecting window drawing
                sendExtensionReply(output, opcode, true)
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // XDamage extension
    // =====================================================================

    private fun handleXDamage(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XDamage minorOpcode=$minorOpcode")
        when (minorOpcode) {
            XDamage_QUERY_VERSION -> {
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(1); reply.put(1) // major, minor version 1.1
                output.write(reply.array())
                output.flush()
            }
            XDamage_CREATE -> {
                // Create damage object to track region changes
                sendExtensionReply(output, opcode, true)
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // XRender extension
    // =====================================================================

    private fun handleXRender(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XRender minorOpcode=$minorOpcode")
        when (minorOpcode) {
            XRender_QUERY_VERSION -> {
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(0); reply.put(0) // major, minor
                output.write(reply.array())
                output.flush()
            }
            XRender_CREATE_PICTURE -> {
                // Create a picture from a drawable
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.putInt(buf.getInt(4)) // picture ID echoed back
                output.write(reply.array())
                output.flush()
            }
            XRender_FREE_PICTURE -> {
                // Free a picture - no reply needed typically
            }
            XRender_COMPOSITE -> {
                // Composite operation: render source picture onto destination.
                // This is the core of XRender antialiased text/picture rendering.
                try {
                    val op = buf.get().toInt() and 0xFF
                    buf.getShort() // src x
                    buf.getShort() // src y
                    val srcX = buf.getShort().toInt()
                    val srcY = buf.getShort().toInt()
                    val maskX = buf.getShort().toInt()
                    val maskY = buf.getShort().toInt()
                    val dstX = buf.getShort().toInt()
                    val dstY = buf.getShort().toInt()
                    val width = buf.getShort().toInt() and 0xFFFF
                    val height = buf.getShort().toInt() and 0xFFFF

                    val srcId = buf.getInt()
                    val maskId = buf.getInt()
                    val dstId = buf.getInt()

                    val serverRef = this.server
                    val srcPic = serverRef.pictures[srcId]
                    val dstPic = serverRef.pictures[dstId]
                    val target = if (dstPic != null) {
                        serverRef.targetBitmapForDrawable(dstPic.drawableId)
                    } else {
                        serverRef.framebuffer
                    }

                    if (target != null && width > 0 && height > 0) {
                        // Simple Src copy from source picture's drawable
                        if (srcPic != null) {
                            val srcBmp = serverRef.targetBitmapForDrawable(srcPic.drawableId)
                            if (srcBmp != null) {
                                val canvas = android.graphics.Canvas(target)
                                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                                when (op) {
                                    1 -> { // PictOp.Src
                                        paint.xfermode = null
                                        canvas.drawBitmap(srcBmp, srcX.toFloat(), srcY.toFloat(), paint)
                                    }
                                    3 -> { // PictOp.Over
                                        paint.xfermode = null
                                        canvas.drawBitmap(srcBmp, srcX.toFloat(), srcY.toFloat(), paint)
                                    }
                                    else -> { // Default: Src
                                        paint.xfermode = null
                                        canvas.drawBitmap(srcBmp, srcX.toFloat(), srcY.toFloat(), paint)
                                    }
                                }
                                serverRef.dirtyRects.add(
                                    MiniXServer.DirtyRect(
                                        dstX.coerceAtLeast(0),
                                        dstY.coerceAtLeast(0),
                                        (dstX + width).coerceAtMost(serverRef.displayWidth),
                                        (dstY + height).coerceAtMost(serverRef.displayHeight),
                                    )
                                )
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore parse errors; keep server alive
                }
                sendExtensionReply(output, opcode, true)
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // GLX extension
    // =====================================================================

    private fun handleGLX(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "GLX minorOpcode=$minorOpcode")
        when (minorOpcode) {
            1 -> {
                // glXQueryVersion - initialize EGL for hardware acceleration
                val ready = server.eglContextManager.init()
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(if (ready) 1 else 0) // supported
                reply.put(1); reply.put(4) // GLX 1.4
                output.write(reply.array())
                output.flush()
            }
            2 -> {
                // glXCreateContext - create GLX context backed by EGL
                val ready = server.eglContextManager.init()
                if (ready && server.eglContextManager.isReady()) {
                    sendExtensionReply(output, opcode, true)
                } else {
                    sendExtensionError(output, opcode, minorOpcode)
                }
            }
            4 -> {
                // glXMakeCurrent - bind context to drawable for current thread
                val ready = server.eglContextManager.init()
                if (ready && server.eglContextManager.isReady()) {
                    val drawableId = buf.getInt(4)
                    val contextId = buf.getInt(8)
                    server.eglSurfaceToDrawable[server.eglContextManager.createPbufferSurface(server.displayWidth, server.displayHeight)!!] = drawableId
                    server.eglContextManager.makeCurrent(server.eglContextManager.createPbufferSurface(server.displayWidth, server.displayHeight))
                    sendExtensionReply(output, opcode, true)
                } else {
                    sendExtensionError(output, opcode, minorOpcode)
                }
            }
            5 -> {
                // glXCreateWindow - create GLX window from X11 drawable
                val ready = server.eglContextManager.init()
                if (ready && server.eglContextManager.isReady()) {
                    val drawableId = buf.getInt(4)
                    val glxWindowId = buf.getInt(8)
                    val surface = server.eglContextManager.createPbufferSurface(server.displayWidth, server.displayHeight)
                    if (surface != null) {
                        server.eglSurfaceToDrawable[surface] = drawableId
                        server.drawableToEglSurface[glxWindowId] = surface
                        sendExtensionReply(output, opcode, true)
                    } else {
                        sendExtensionError(output, opcode, minorOpcode)
                    }
                } else {
                    sendExtensionError(output, opcode, minorOpcode)
                }
            }
            6 -> {
                // glXDestroyContext - destroy GLX context
                server.eglContextManager.release()
                sendExtensionReply(output, opcode, true)
            }
            12 -> {
                // glXSwapBuffers - present rendered content to screen
                server.eglContextManager.swapBuffers(null)
                sendExtensionReply(output, opcode, true)
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // DRI3 extension - Direct Rendering Infrastructure 3
    // Provides buffer sharing between clients and direct rendering
    // =====================================================================

    private fun handleDRI3(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "DRI3 minorOpcode=$minorOpcode")
        when (minorOpcode) {
            0 -> { // DRI3 QueryVersion
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(1); reply.put(0) // version 1.0
                output.write(reply.array())
                output.flush()
            }
            1 -> { // DRI3 OpenConnection - Open a rendering connection to a drawable
                sendExtensionReply(output, opcode, true)
            }
            2 -> { // DRI3 CreatePixmapFromBuffer - Create a pixmap from a buffer
                sendExtensionReply(output, opcode, true)
            }
            3 -> { // DRI3 BufferFromPixmap - Get buffer info from pixmap
                sendExtensionReply(output, opcode, true)
            }
            4 -> { // DRI3 FenceFromFD - Create fence from file descriptor
                sendExtensionReply(output, opcode, true)
            }
            5 -> { // DRI3 FDFromFence - Get file descriptor from fence
                sendExtensionReply(output, opcode, true)
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // Present extension - Present windows to screen with timing
    // Provides VBlank-synchronized presentation for smooth rendering
    // =====================================================================

    private fun handlePresent(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "Present minorOpcode=$minorOpcode")
        when (minorOpcode) {
            0 -> { // Present QueryVersion
                val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(0)
                reply.put(1); reply.put(1) // version 1.1
                output.write(reply.array())
                output.flush()
            }
            1 -> { // Present Create Pixmap
                sendExtensionReply(output, opcode, true)
            }
            2 -> { // Present Present Pixmap - Present window contents to screen
                sendExtensionReply(output, opcode, true)
            }
            else -> sendExtensionReply(output, opcode, true)
        }
        return true
    }

    // =====================================================================
    // Helper methods
    // =====================================================================

    private fun sendExtensionReply(
        output: java.io.DataOutputStream,
        opcode: Int,
        success: Boolean
    ) {
        val reply = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        reply.put(if (success) 1 else 0)
        reply.put(0)
        reply.putShort(server.nextSeq().toShort())
        reply.putInt(0)
        repeat(20) { reply.putInt(0) }
        output.write(reply.array())
        output.flush()
    }

    private fun sendExtensionError(
        output: java.io.DataOutputStream,
        opcode: Int,
        minorOpcode: Int
    ) {
        val err = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
        err.put(0) // error type
        err.put(1) // BadRequest
        err.putShort(server.nextSeq().toShort())
        err.putInt(minorOpcode.toLong().toInt())
        err.putShort(opcode.toShort())
        repeat(21) { err.put(0) }
        output.write(err.array())
        output.flush()
    }
}
