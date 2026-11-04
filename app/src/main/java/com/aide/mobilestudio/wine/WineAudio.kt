package com.aide.mobilestudio.wine

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple PCM audio output for Wine applications.
 * Infrastructure for Wine audio integration — full Wine audio driver bridge
 * requires a Wine-side audio DLL that writes PCM to this sink.
 */
class WineAudio(private val sampleRate: Int = 44100, private val channels: Int = 2) {
    companion object { private const val TAG = "WineAudio" }

    private val audioTrack: AudioTrack
    private val playing = AtomicBoolean(false)

    init {
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(8192)
        audioTrack = AudioTrack(
            AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM,
        )
    }

    fun start() {
        if (playing.getAndSet(true)) return
        audioTrack.play()
    }

    fun write(pcmData: ShortArray): Int =
        if (playing.get()) audioTrack.write(pcmData, 0, pcmData.size) else 0

    fun stop() {
        playing.set(false)
        runCatching { audioTrack.stop() }
    }

    fun release() {
        stop()
        runCatching { audioTrack.release() }
    }
}
