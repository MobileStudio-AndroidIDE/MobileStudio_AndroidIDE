package com.aide.mobilestudio.ai.resource

import android.app.ActivityManager
import android.content.Context

/** Reads Android's reported memory state; falls back to the JVM heap when unavailable. */
object AndroidMemoryProbe {
    fun snapshot(context: Context): MemorySnapshot {
        val rt = Runtime.getRuntime()
        val heapUsed = rt.totalMemory() - rt.freeMemory()
        val heapMax = rt.maxMemory()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val info = ActivityManager.MemoryInfo()
            runCatching { am.getMemoryInfo(info) }
            if (info.totalMem > 0) {
                return MemorySnapshot(
                    availableBytes = info.availMem,
                    totalBytes = info.totalMem,
                    lowMemory = info.lowMemory,
                    heapUsedBytes = heapUsed,
                    heapMaxBytes = heapMax,
                )
            }
        }
        return MemorySnapshot.fromJvm()
    }
}
