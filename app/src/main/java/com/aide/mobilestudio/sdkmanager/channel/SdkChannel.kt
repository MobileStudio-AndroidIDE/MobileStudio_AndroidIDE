package com.aide.mobilestudio.sdkmanager.channel

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.aide.mobilestudio.sdkmanager.model.SdkComponent

/**
 * SDK Channel Manager - Stable, Beta, Dev, Canary channel switching.
 * Simplified implementation for compilation.
 */
class SdkChannel(
    private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("sdk_channels", Context.MODE_PRIVATE)
    private val KEY_CURRENT_CHANNEL = "current_channel"

    enum class Channel(val description: String, val repositoryUrl: String) {
        STABLE(
            "Stable releases, recommended for production",
            "https://dl.google.com/android/repository/repository2-1.xml"
        ),
        BETA(
            "Beta releases, early access to new features",
            "https://dl.google.com/android/repository/repository2-1-beta.xml"
        ),
        DEV(
            "Development builds, latest features but unstable",
            "https://dl.google.com/android/repository/repository2-1-dev.xml"
        ),
        CANARY(
            "Bleeding edge, daily builds, may break",
            "https://dl.google.com/android/repository/repository2-1-canary.xml"
        )
    }

    fun getCurrentChannel(): Channel {
        val name = prefs.getString(KEY_CURRENT_CHANNEL, Channel.STABLE.name)
        return Channel.valueOf(name ?: "STABLE")
    }

    fun setCurrentChannel(channel: Channel) {
        prefs.edit().putString(KEY_CURRENT_CHANNEL, channel.name).apply()
        Log.i("SdkChannel", "Switched to ${channel.name} channel")
    }

    fun getRepositoryUrl(channel: Channel = getCurrentChannel()): String {
        return channel.repositoryUrl
    }

    fun getAllChannels(): List<Channel> {
        return Channel.values().toList()
    }

    fun getChannelInfo(channel: Channel): ChannelInfo {
        return ChannelInfo(
            channel = channel,
            updateFrequency = "Monthly",
            stability = "High",
            recommendedFor = "Production apps",
            includes = listOf("Stable platform releases", "Stable build tools", "Stable NDK", "Stable emulator")
        )
    }

    fun filterComponentsByChannel(components: List<SdkComponent>, channel: Channel = getCurrentChannel()): List<SdkComponent> {
        return components.filter { component ->
            component.channel == "Stable"
        }
    }

    data class ChannelInfo(
        val channel: Channel,
        val updateFrequency: String,
        val stability: String,
        val recommendedFor: String,
        val includes: List<String>
    )
}