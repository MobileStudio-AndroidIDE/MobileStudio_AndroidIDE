package com.aide.mobilestudio

import android.app.ActivityManager
import android.content.Context

object BuildMemoryPolicy {
    const val PREF_GRADLE_HEAP_PROFILE = "gradle_heap_profile"

    enum class Profile(val prefValue: String, val requestedMb: Int?) {
        AUTO("auto", null),
        STANDARD("1024", 1024),
        EXTENDED("1536", 1536),
        MAX("2048", 2048),
    }

    data class Budget(
        val gradleHeapMb: Int,
        val kotlinHeapMb: Int,
        val metaspaceMb: Int,
        val kotlinMetaspaceMb: Int,
        val profile: Profile,
        val deviceRamMb: Long,
        val appLargeHeapMb: Int
    )

    fun profile(context: Context): Profile {
        val raw = context.getSharedPreferences("ide", Context.MODE_PRIVATE)
            .getString(PREF_GRADLE_HEAP_PROFILE, Profile.AUTO.prefValue)
            ?: Profile.AUTO.prefValue
        return Profile.entries.firstOrNull { it.prefValue == raw } ?: Profile.AUTO
    }

    fun setProfile(context: Context, profile: Profile) {
        context.getSharedPreferences("ide", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_GRADLE_HEAP_PROFILE, profile.prefValue)
            .apply()
    }

    fun deviceTotalRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    fun appLargeHeapMb(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.largeMemoryClass
    }

    fun resolve(context: Context): Budget {
        val deviceRamMb = deviceTotalRamMb(context)
        val profile = profile(context)
        val requested = profile.requestedMb ?: autoGradleHeapMb(deviceRamMb)
        val capped = capGradleHeapMb(requested, deviceRamMb)
        // Allow BuildToolsConfig to override the computed heap
        val configHeap = BuildToolsConfig.gradleHeapMb(context)
        val gradleHeap = configHeap.coerceIn(512, capped.coerceAtMost(4096))
        val kotlinHeap = BuildToolsConfig.kotlinHeapMb(context).coerceIn(256, gradleHeap - 128)
        val metaspace = BuildToolsConfig.gradleMetaspaceMb(context).coerceIn(128, 1024)
        return Budget(
            gradleHeapMb = gradleHeap,
            kotlinHeapMb = kotlinHeap,
            metaspaceMb = metaspace,
            kotlinMetaspaceMb = BuildToolsConfig.kotlinMetaspaceMb(context),
            profile = profile,
            deviceRamMb = deviceRamMb,
            appLargeHeapMb = appLargeHeapMb(context)
        )
    }

    private fun autoGradleHeapMb(totalRamMb: Long): Int = when {
        totalRamMb >= 12_000 -> 2048
        totalRamMb >= 8_000 -> 1536
        totalRamMb >= 6_000 -> 1280
        else -> 1024
    }

    private fun capGradleHeapMb(requested: Int, deviceRamMb: Long): Int {
        val ceiling = (deviceRamMb * 0.40).toInt().coerceAtLeast(768)
        return requested.coerceIn(768, ceiling.coerceAtMost(2048))
    }
}
