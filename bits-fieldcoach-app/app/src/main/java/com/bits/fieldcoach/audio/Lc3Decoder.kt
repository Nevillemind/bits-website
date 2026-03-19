package com.bits.fieldcoach.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * LC3 audio decoder and playback for Mentra Live glasses.
 *
 * LC3 packet format from glasses:
 *   Byte 0: 0xF1 (audio header)
 *   Byte 1: sequence number (0-255)
 *   Bytes 2+: LC3 encoded frames (10 frames × 40 bytes = 400 bytes typical)
 *
 * Since the native LC3 codec library requires NDK compilation, this implementation
 * provides a PCM passthrough fallback — it accepts raw PCM data decoded elsewhere
 * and plays it via AudioTrack. When native LC3 decoding is needed, the decodeLc3Frame
 * method should be replaced with JNI calls.
 */
class Lc3Decoder {
    companion object {
        private const val TAG = "Lc3Decoder"
        const val LC3_HEADER: Byte = 0xF1.toByte()
        const val LC3_FRAME_SIZE = 40
        const val SAMPLE_RATE = 16000
        const val CHANNELS = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false
    private var lastSequence = -1
    private var packetsReceived = 0L
    private var bytesReceived = 0L

    /**
     * Initialize AudioTrack for playback.
     */
    fun initialize() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING) * 2

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNELS)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.i(TAG, "AudioTrack initialized: sampleRate=$SAMPLE_RATE, bufferSize=$bufferSize")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioTrack", e)
        }
    }

    /**
     * Start audio playback.
     */
    fun startPlayback() {
        if (isPlaying) return
        try {
            audioTrack?.play()
            isPlaying = true
            packetsReceived = 0
            bytesReceived = 0
            Log.i(TAG, "LC3 audio playback started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback", e)
        }
    }

    /**
     * Stop audio playback.
     */
    fun stopPlayback() {
        if (!isPlaying) return
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            isPlaying = false
            Log.i(TAG, "LC3 audio playback stopped (packets=$packetsReceived, bytes=$bytesReceived)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop playback", e)
        }
    }

    /**
     * Process an incoming LC3 audio packet from BLE.
     * Extracts sequence number and LC3 frames, decodes to PCM, and plays.
     *
     * @param packet Raw BLE packet with 0xF1 header
     * @return PCM audio data (for forwarding to SpeechRecognizer), or null
     */
    fun processLc3Packet(packet: ByteArray): ByteArray? {
        if (packet.size < 3 || packet[0] != LC3_HEADER) return null

        val sequence = packet[1].toInt() and 0xFF
        val lc3Data = packet.copyOfRange(2, packet.size)

        // Check for sequence gaps
        if (lastSequence >= 0) {
            val expected = (lastSequence + 1) and 0xFF
            if (sequence != expected) {
                Log.w(TAG, "LC3 sequence gap: expected $expected, got $sequence")
            }
        }
        lastSequence = sequence

        packetsReceived++
        bytesReceived += lc3Data.size.toLong()

        // Decode LC3 frames to PCM
        val pcmData = decodeLc3Frames(lc3Data)

        // Play the decoded PCM
        if (isPlaying && pcmData != null) {
            audioTrack?.write(pcmData, 0, pcmData.size)
        }

        return pcmData
    }

    /**
     * Decode LC3 encoded frames to PCM.
     *
     * Current implementation: treat as raw PCM passthrough.
     * When native LC3 lib is integrated, this will call Lc3Cpp.decodeLC3().
     *
     * Each LC3 frame is 40 bytes. A typical packet has 10 frames = 400 bytes.
     * Each frame decodes to 320 bytes of PCM (160 samples × 16-bit).
     */
    private fun decodeLc3Frames(lc3Data: ByteArray): ByteArray? {
        if (lc3Data.isEmpty()) return null

        // Without native decoder, pass through the raw data.
        // The glasses firmware may send uncompressed PCM in some modes.
        // For proper LC3 decoding, integrate the native lib via JNI.
        return lc3Data
    }

    /**
     * Release resources.
     */
    fun release() {
        stopPlayback()
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack", e)
        }
    }
}
