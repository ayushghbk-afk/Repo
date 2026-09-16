package com.lenix.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Audio bridge — ported from Stryker's Audio.java for Lenix.
 *
 * Provides audio playback from guest Linux to Android.
 * Stryker's implementation uses AudioTrack with SDL callbacks;
 * Lenix adapts it for VNC/desktop audio forwarding.
 *
 * Usage: Guest can use PulseAudio TCP or pipe audio to this bridge.
 * For now, provides simple PCM playback API.
 */
class AudioBridge {
    private var audioTrack: AudioTrack? = null
    private var sampleRate: Int = 44100
    private var channels: Int = 2
    private var bufferSize: Int = 0

    companion object {
        private const val TAG = "LenixAudio"

        @Volatile
        private var instance: AudioBridge? = null

        fun get(): AudioBridge {
            return instance ?: synchronized(this) {
                instance ?: AudioBridge().also { instance = it }
            }
        }
    }

    fun init(rate: Int = 44100, channelCount: Int = 2, bufferMs: Int = 100): Boolean {
        try {
            sampleRate = rate
            channels = channelCount

            val channelConfig = if (channelCount == 1) {
                AudioFormat.CHANNEL_OUT_MONO
            } else {
                AudioFormat.CHANNEL_OUT_STEREO
            }

            val minBuf = AudioTrack.getMinBufferSize(
                rate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
            )

            bufferSize = maxOf(minBuf, (rate * channelCount * 2 * bufferMs / 1000))

            audioTrack = AudioTrack(
                AudioManager.STREAM_MUSIC,
                rate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM,
            )

            audioTrack?.play()
            Log.d(TAG, "Audio initialized: rate=$rate, channels=$channelCount, buffer=$bufferSize")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init audio: ${e.message}", e)
            return false
        }
    }

    fun write(pcmData: ByteArray, offset: Int = 0, length: Int = pcmData.size): Int {
        return try {
            audioTrack?.write(pcmData, offset, length) ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Audio write failed: ${e.message}")
            0
        }
    }

    fun writeShort(pcmData: ShortArray, offset: Int = 0, length: Int = pcmData.size): Int {
        return try {
            audioTrack?.write(pcmData, offset, length) ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Audio write short failed: ${e.message}")
            0
        }
    }

    fun pause() {
        try {
            audioTrack?.pause()
        } catch (e: Exception) {
            Log.w(TAG, "Audio pause failed: ${e.message}")
        }
    }

    fun resume() {
        try {
            audioTrack?.play()
        } catch (e: Exception) {
            Log.w(TAG, "Audio resume failed: ${e.message}")
        }
    }

    fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.d(TAG, "Audio released")
        } catch (e: Exception) {
            Log.w(TAG, "Audio release failed: ${e.message}")
        }
    }

    fun isInitialized(): Boolean = audioTrack != null
}
