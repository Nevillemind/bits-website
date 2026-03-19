package com.bits.fieldcoach

import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bits.fieldcoach.ble.ConnectionStatus
import com.bits.fieldcoach.ble.GlassesEvent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * BITS Field Coach — Live Camera Preview Activity
 *
 * Displays a continuous feed from the Mentra glasses camera.
 * Requests a new photo every ~1 second after receiving each frame.
 * Allows AI analysis of the current frame via "Ask AI" button.
 */
class CameraPreviewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CameraPreview"
    }

    // UI
    private lateinit var previewImage: ImageView
    private lateinit var frameCountText: TextView
    private lateinit var noSignalText: TextView
    private lateinit var connectionStatusText: TextView
    private lateinit var connectionDot: View
    private lateinit var batteryText: TextView
    private lateinit var startStopButton: Button
    private lateinit var askAiButton: Button
    private lateinit var backButton: Button
    private lateinit var aiResponseText: TextView

    // Preview state
    private var isPreviewRunning = false
    private var frameCount = 0
    private var lastFrameTimeMs = 0L
    private var lastPhotoData: ByteArray? = null

    private val handler = Handler(Looper.getMainLooper())

    // Event listener reference for cleanup
    private var glassesEventListener: ((GlassesEvent) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_preview)

        // Bind views
        previewImage = findViewById(R.id.previewImage)
        frameCountText = findViewById(R.id.frameCountText)
        noSignalText = findViewById(R.id.noSignalText)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        connectionDot = findViewById(R.id.connectionDot)
        batteryText = findViewById(R.id.batteryText)
        startStopButton = findViewById(R.id.startStopButton)
        askAiButton = findViewById(R.id.askAiButton)
        backButton = findViewById(R.id.backButton)
        aiResponseText = findViewById(R.id.aiResponseText)

        // Back button
        backButton.setOnClickListener {
            stopPreviewLoop()
            finish()
        }

        // Start/Stop preview toggle
        startStopButton.setOnClickListener {
            if (isPreviewRunning) {
                stopPreviewLoop()
            } else {
                startPreviewLoop()
            }
        }

        // Ask AI button
        askAiButton.setOnClickListener {
            askAi()
        }

        // Observe connection state
        val bleManager = FieldCoachApp.bleManager
        if (bleManager != null) {
            lifecycleScope.launch {
                bleManager.connectionState.collectLatest { state ->
                    runOnUiThread { updateConnectionUi(state) }
                }
            }
            lifecycleScope.launch {
                bleManager.batteryLevel.collectLatest { level ->
                    runOnUiThread {
                        batteryText.text = if (level >= 0) "🔋 $level%" else ""
                    }
                }
            }
        }

        // Register event listener for photo responses
        setupGlassesEventListener()

        // Initial UI state
        updateConnectionUi(bleManager?.connectionState?.value ?: com.bits.fieldcoach.ble.ConnectionStatus.DISCONNECTED)
    }

    private fun setupGlassesEventListener() {
        val listener: (GlassesEvent) -> Unit = { event ->
            when (event) {
                is GlassesEvent.PhotoResponse -> {
                    if (event.success && event.photoData != null && isPreviewRunning) {
                        handleNewFrame(event.photoData)
                    } else if (isPreviewRunning) {
                        // Photo failed — retry after short delay
                        Log.w(TAG, "Photo failed: ${event.error}")
                        handler.postDelayed({ requestNextFrame() }, 2000)
                    }
                }
                is GlassesEvent.ConnectionState -> {
                    runOnUiThread {
                        val status = if (event.connected)
                            com.bits.fieldcoach.ble.ConnectionStatus.CONNECTED
                        else
                            com.bits.fieldcoach.ble.ConnectionStatus.DISCONNECTED
                        updateConnectionUi(status)
                    }
                }
                else -> { /* ignore other events */ }
            }
        }

        glassesEventListener = listener
        FieldCoachApp.bleManager?.addEventListener(listener)
    }

    private fun handleNewFrame(photoData: ByteArray) {
        frameCount++
        lastPhotoData = photoData

        val now = System.currentTimeMillis()
        val fps = if (lastFrameTimeMs > 0) {
            val elapsed = (now - lastFrameTimeMs) / 1000.0
            if (elapsed > 0) String.format("%.1f FPS", 1.0 / elapsed) else "-- FPS"
        } else "--"
        lastFrameTimeMs = now

        runOnUiThread {
            val bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.size)
            if (bitmap != null) {
                previewImage.setImageBitmap(bitmap)
                noSignalText.visibility = View.GONE
                previewImage.visibility = View.VISIBLE
            }
            frameCountText.text = "Frame $frameCount | $fps"
            Log.d(TAG, "Frame $frameCount displayed (${photoData.size} bytes, $fps)")
        }

        // Queue next frame after 1 second
        handler.postDelayed({ requestNextFrame() }, 1000)
    }

    private fun startPreviewLoop() {
        val bleManager = FieldCoachApp.bleManager
        if (bleManager == null) {
            aiResponseText.text = "BLE manager not available."
            aiResponseText.visibility = View.VISIBLE
            return
        }
        if (!bleManager.isConnected()) {
            aiResponseText.text = "Glasses not connected. Connect from main screen first."
            aiResponseText.visibility = View.VISIBLE
            return
        }

        isPreviewRunning = true
        frameCount = 0
        lastFrameTimeMs = 0L

        startStopButton.text = "STOP PREVIEW"
        startStopButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF475569.toInt())

        frameCountText.text = "Starting..."
        Log.i(TAG, "Preview loop started")

        requestNextFrame()
    }

    private fun stopPreviewLoop() {
        isPreviewRunning = false
        handler.removeCallbacksAndMessages(null)

        startStopButton.text = "START PREVIEW"
        startStopButton.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFdc3246.toInt())

        frameCountText.text = "Stopped | Frame $frameCount"
        Log.i(TAG, "Preview loop stopped at frame $frameCount")
    }

    private fun requestNextFrame() {
        if (!isPreviewRunning) return

        val bleManager = FieldCoachApp.bleManager ?: return

        if (bleManager.isConnected() && bleManager.isFullyBooted()) {
            val requestId = "preview_${System.currentTimeMillis()}"
            bleManager.requestPhoto(requestId)
            Log.d(TAG, "Requesting frame: $requestId")
        } else {
            // Not ready — retry after 2 seconds
            Log.w(TAG, "Glasses not ready — retrying in 2s")
            handler.postDelayed({ requestNextFrame() }, 2000)
        }
    }

    private fun askAi() {
        val photoData = lastPhotoData
        if (photoData == null) {
            aiResponseText.text = "No frame captured yet. Start preview and wait for a frame."
            aiResponseText.visibility = View.VISIBLE
            return
        }

        val aiClient = FieldCoachApp.aiClient
        val speechManager = FieldCoachApp.speechManager

        if (aiClient == null) {
            aiResponseText.text = "AI client not available."
            aiResponseText.visibility = View.VISIBLE
            return
        }

        // Show analysis in progress
        aiResponseText.text = "Analyzing..."
        aiResponseText.visibility = View.VISIBLE
        askAiButton.isEnabled = false

        speechManager?.speak("Analyzing what I see.")

        lifecycleScope.launch {
            val result = aiClient.analyzePhoto(
                "What do you see? Describe any issues or observations.",
                photoData
            )

            result.onSuccess { answer ->
                runOnUiThread {
                    aiResponseText.text = answer
                    aiResponseText.visibility = View.VISIBLE
                    askAiButton.isEnabled = true
                }
                speechManager?.speak(answer)
                Log.i(TAG, "AI answer: ${answer.take(80)}")
            }

            result.onFailure { error ->
                runOnUiThread {
                    aiResponseText.text = "Analysis failed: ${error.message}"
                    aiResponseText.visibility = View.VISIBLE
                    askAiButton.isEnabled = true
                }
                Log.e(TAG, "AI analysis failed", error)
            }
        }
    }

    private fun updateConnectionUi(state: ConnectionStatus) {
        when (state) {
            ConnectionStatus.CONNECTED -> {
                connectionStatusText.text = "Connected"
                connectionStatusText.setTextColor(0xFF16A34A.toInt())
                connectionDot.setBackgroundResource(R.drawable.dot_connected)
            }
            ConnectionStatus.CONNECTING, ConnectionStatus.ADVERTISING -> {
                connectionStatusText.text = "Connecting..."
                connectionStatusText.setTextColor(0xFFF59E0B.toInt())
                connectionDot.setBackgroundResource(R.drawable.dot_disconnected)
            }
            else -> {
                connectionStatusText.text = "Disconnected"
                connectionStatusText.setTextColor(0xFF94A3B8.toInt())
                connectionDot.setBackgroundResource(R.drawable.dot_disconnected)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreviewLoop()
        // Note: we don't remove the listener from bleManager since it's a global list
        // and BleConnectionManager doesn't currently support listener removal.
        // The activity reference in the lambda will be cleared naturally by GC.
    }
}
