package com.bits.fieldcoach.stream

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * StreamRelay — Relays live video from glasses to the Field Coach backend.
 *
 * Architecture:
 *   Glasses → BLE → Phone → StreamRelay (WebSocket) → Field Coach Backend →
 *   FFmpeg → MediaMTX → HLS → Supervisor browser
 *
 * This class:
 *   1. Connects to wss://bitsfieldcoach.com/ws/live/{workerId} via OkHttp WebSocket
 *   2. Accepts video frame byte arrays (from the glasses camera callback)
 *   3. Sends each frame as a binary WebSocket message to the backend
 *   4. Handles reconnection on disconnect
 *
 * Usage:
 *   val relay = StreamRelay("worker_001")
 *   relay.start()           // Connect WebSocket
 *   relay.sendFrame(bytes)  // Send video frame
 *   relay.stop()            // Disconnect
 */
class StreamRelay(
    private val workerId: String,
    private val serverUrl: String = "wss://bitsfieldcoach.com"
) {
    companion object {
        private const val TAG = "StreamRelay"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_RECONNECT_ATTEMPTS = 10
    }

    // OkHttp client with extended timeouts for streaming
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MINUTES)   // No read timeout — streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep-alive pings
        .build()

    private var webSocket: WebSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val isConnected = AtomicBoolean(false)
    private var reconnectAttempts = 0
    private val handler = Handler(Looper.getMainLooper())

    // Callback for streaming status updates (UI can observe)
    var onStatusChanged: ((StreamStatus) -> Unit)? = null

    // Frame counter for diagnostics
    private var framesSent = 0L
    private var bytesSent = 0L
    private var streamStartTime = 0L

    /**
     * Start the relay — open WebSocket connection to backend.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.w(TAG, "Stream already running")
            return
        }

        reconnectAttempts = 0
        framesSent = 0
        bytesSent = 0
        streamStartTime = System.currentTimeMillis()
        connect()
    }

    /**
     * Stop the relay — close WebSocket and stop reconnection attempts.
     */
    fun stop() {
        isRunning.set(false)
        isConnected.set(false)
        handler.removeCallbacks(reconnectRunnable)
        webSocket?.close(1000, "Stream stopped by user")
        webSocket = null
        notifyStatus(StreamStatus.Disconnected)
        Log.i(TAG, "Stream relay stopped")
    }

    /**
     * Send a video frame to the backend via WebSocket.
     * Called from the RTMP server with H.264 NAL units.
     * Prepends a 1-byte header (0x00 = video) so backend can distinguish audio/video.
     */
    fun sendVideoFrame(frameData: ByteArray): Boolean {
        if (!isConnected.get() || webSocket == null) return false
        return try {
            val packet = ByteArray(1 + frameData.size)
            packet[0] = 0x00 // video marker
            System.arraycopy(frameData, 0, packet, 1, frameData.size)
            webSocket!!.send(packet.toByteString())
            framesSent++
            bytesSent += frameData.size
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send video frame: ${e.message}")
            false
        }
    }

    /**
     * Send an audio frame to the backend via WebSocket.
     * Called from the RTMP server with AAC audio data.
     * Prepends a 1-byte header (0x01 = audio) so backend can distinguish audio/video.
     */
    fun sendAudioFrame(frameData: ByteArray): Boolean {
        if (!isConnected.get() || webSocket == null) return false
        return try {
            val packet = ByteArray(1 + frameData.size)
            packet[0] = 0x01 // audio marker
            System.arraycopy(frameData, 0, packet, 1, frameData.size)
            webSocket!!.send(packet.toByteString())
            framesSent++
            bytesSent += frameData.size
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio frame: ${e.message}")
            false
        }
    }

    /**
     * Legacy method for sending raw frames (no type header).
     */
    fun sendFrame(frameData: ByteArray): Boolean {
        if (!isConnected.get() || webSocket == null) return false
        return try {
            webSocket!!.send(frameData.toByteString())
            framesSent++
            bytesSent += frameData.size
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send frame: ${e.message}")
            false
        }
    }

    /**
     * Check if the relay is currently connected.
     */
    fun isStreaming(): Boolean = isRunning.get() && isConnected.get()

    /**
     * Get streaming statistics.
     */
    fun getStats(): StreamStats {
        val duration = if (streamStartTime > 0)
            (System.currentTimeMillis() - streamStartTime) / 1000 else 0
        return StreamStats(
            framesSent = framesSent,
            bytesSent = bytesSent,
            durationSeconds = duration,
            isConnected = isConnected.get()
        )
    }

    // -----------------------------------------------------------------------
    // Internal: WebSocket connection
    // -----------------------------------------------------------------------

    private fun connect() {
        if (!isRunning.get()) return

        val wsUrl = "${serverUrl}/glasses/ws/live/${workerId}"
        val request = Request.Builder()
            .url(wsUrl)
            .build()

        Log.i(TAG, "Connecting to $wsUrl")
        notifyStatus(StreamStatus.Connecting)

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                isConnected.set(true)
                reconnectAttempts = 0
                notifyStatus(StreamStatus.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text message: $text")
                // Server might send status messages — handle if needed
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: code=$code reason=$reason")
                webSocket.close(1000, null)
                isConnected.set(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: code=$code reason=$reason")
                isConnected.set(false)
                notifyStatus(StreamStatus.Disconnected)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected.set(false)
                notifyStatus(StreamStatus.Error(t.message ?: "Connection failed"))
                scheduleReconnect()
            }
        })
    }

    private val reconnectRunnable = Runnable {
        if (isRunning.get()) {
            connect()
        }
    }

    private fun scheduleReconnect() {
        if (!isRunning.get()) return

        reconnectAttempts++
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached — stopping")
            stop()
            notifyStatus(StreamStatus.Error("Max reconnection attempts reached"))
            return
        }

        // Exponential backoff: 3s, 6s, 12s, 24s...
        val delay = RECONNECT_DELAY_MS * (1L shl minOf(reconnectAttempts - 1, 5))
        Log.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectAttempts)")
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun notifyStatus(status: StreamStatus) {
        onStatusChanged?.invoke(status)
    }
}

/**
 * Streaming status sealed class for UI updates.
 */
sealed class StreamStatus {
    object Connecting : StreamStatus()
    object Connected : StreamStatus()
    object Disconnected : StreamStatus()
    data class Error(val message: String) : StreamStatus()
}

/**
 * Streaming statistics.
 */
data class StreamStats(
    val framesSent: Long,
    val bytesSent: Long,
    val durationSeconds: Long,
    val isConnected: Boolean
)
