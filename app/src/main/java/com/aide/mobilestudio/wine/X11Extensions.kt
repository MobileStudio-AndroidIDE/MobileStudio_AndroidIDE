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

        // XFixes opcodes
        const val XFixes_CURSOR_IMAGE = 0
        const val XFixes_CURSOR_NAME = 1
        const val XFixes_CURSOR_IMAGE_SIZE = 32

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
            // XInput2 core opcodes
            0 -> sendExtensionReply(output, opcode, true) // QueryVersion
            1 -> sendExtensionReply(output, opcode, true) // DeviceChangedNotify
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

    private fun handleXFixes(
        opcode: Int,
        minorOpcode: Int,
        buf: ByteBuffer,
        output: java.io.DataOutputStream
    ): Boolean {
        Log.d(TAG, "XFixes minorOpcode=$minorOpcode")
        when (minorOpcode) {
            XFixes_CURSOR_IMAGE -> {
                // Return cursor image for Wine cursor rendering
                val reply = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
                reply.put(1); reply.put(0)
                reply.putShort(server.nextSeq().toShort())
                reply.putInt(8) // length in 4-byte units
                reply.putInt(XFixes_CURSOR_IMAGE_SIZE) // width
                reply.putInt(XFixes_CURSOR_IMAGE_SIZE) // height
                reply.putInt(0) // xhot
                reply.putInt(0) // yhot
                reply.putInt(0) // cursor serial
                reply.putInt(0) // cursor image data (empty)
                output.write(reply.array())
                output.flush()
            }
            else -> sendExtensionReply(output, opcode, true)
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
                // Composite operation - render source picture onto destination
                // This is the core of XRender antialiased rendering
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
