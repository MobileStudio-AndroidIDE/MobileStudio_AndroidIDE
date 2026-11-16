package com.aide.mobilestudio.debugger

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/**
 * JDWP (Java Debug Wire Protocol) implementation in pure Kotlin.
 * Simplified version - provides basic JDWP infrastructure.
 */
object JdwpProtocol {

    /** JDWP handshake string */
    const val HANDSHAKE = "JDWP-Handshake"

    /** JDWP packet flags */
    object Flags {
        const val REPLY = 0x80
        const val ASYNC = 0x00
    }

    /** Error codes from JDWP spec */
    object ErrorCode {
        const val NONE = 0
        const val INVALID_THREAD = 10
        const val INVALID_THREAD_GROUP = 11
        const val INVALID_PRIORITY = 12
        const val THREAD_NOT_SUSPENDED = 13
        const val THREAD_SUSPENDED = 14
        const val INVALID_OBJECT = 15
        const val INVALID_CLASS = 16
        const val CLASS_NOT_PREPARED = 17
        const val INVALID_METHODID = 18
        const val INVALID_LOCATION = 19
        const val INVALID_FIELDID = 20
        const val INVALID_FRAMEID = 21
        const val NO_MORE_FRAMES = 22
        const val OPAQUE_FRAME = 23
        const val NOT_CURRENT_FRAME = 24
        const val TYPE_MISMATCH = 25
        const val INVALID_SLOT = 26
        const val DUPLICATE = 27
        const val NOT_FOUND = 28
        const val INVALID_MONITOR = 29
        const val NOT_MONITOR_OWNER = 30
        const val INTERRUPT = 31
        const val INVALID_CLASS_FORMAT = 32
        const val CIRCULAR_CLASS_DEFINITION = 33
        const val FAILS_VERIFICATION = 34
    }

    /** JDWP command set IDs */
    object CommandSet {
        const val VirtualMachine = 1
        const val ReferenceType = 2
        const val Type = 3
        const val Method = 4
        const val Field = 5
        const val Object = 6
        const val Array = 7
        const val String = 8
        const val Thread = 9
        const val ThreadGroup = 10
        const val ClassLoader = 11
        const val Event = 12
        const val StackFrame = 14
        const val EventRequest = 15
        const val StackFrameLocal = 64
    }
}