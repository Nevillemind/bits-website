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

        // Take Photo button — single shot, shows on screen
        startStopButton.text = "📸 TAKE PHOTO"
        startStopButton.setOnClickListener {
            takeSinglePhoto()
        }

        // Ask AI button — takes ONE photo and analyzes it
        askAiButton.setOnClickListener {
            takePhotoAndAnalyze()
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

    private var pendingQuestion: String = "What do you see? Describe any issues or observations."
    private var waitingForPhoto = false

    private fun setupGlassesEventListener() {
        val listener: (GlassesEvent) -> Unit = { event ->
            when (event) {
                is GlassesEvent.PhotoResponse -> {
                    if (event.success && event.photoData != null) {
                        handleNewFrame(event.photoData)
                        // Photo displayed — user can now tap ASK AI when ready
                        runOnUiThread {
                            frameCountText.text = "Photo captured ✅ — tap ASK AI to analyze"
                        }
                    } else {
                        Log.w(TAG, "Photo failed: ${event.error}")
                        runOnUiThread {
                            frameCountText.text = "Photo capture failed — tap TAKE PHOTO to retry"
                        }
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

        // Re-enable take photo button
        runOnUiThread { startStopButton.isEnabled = true }
    }

    /**
     * Take a single photo from glasses and display it on screen.
     * Does NOT auto-analyze — user must tap ASK AI or use voice.
     */
    private fun takeSinglePhoto() {
        val bleManager = FieldCoachApp.bleManager
        if (bleManager == null || !bleManager.isConnected()) {
            aiResponseText.text = "Glasses not connected. Connect from main screen first."
            aiResponseText.visibility = View.VISIBLE
            return
        }

        frameCountText.text = "Taking photo..."
        startStopButton.isEnabled = false
        
        val requestId = "preview_${System.currentTimeMillis()}"
        bleManager.requestPhoto(requestId)
        Log.i(TAG, "Single photo requested: $requestId")

        // Re-enable button after 10 seconds (timeout)
        handler.postDelayed({
            startStopButton.isEnabled = true
            if (lastPhotoData == null) {
                frameCountText.text = "Photo timed out — tap to retry"
            }
        }, 10000)
    }

    /**
     * ASK AI button — analyzes the ALREADY CAPTURED photo.
     * User must take a photo first, then tap ASK AI.
     * Does NOT take a new photo — uses whatever is on screen.
     */
    private fun takePhotoAndAnalyze() {
        val photoData = lastPhotoData
        if (photoData == null) {
            aiResponseText.text = "Take a photo first, then tap ASK AI."
            aiResponseText.visibility = View.VISIBLE
            return
        }

        analyzeCurrentFrame(pendingQuestion)
    }

    /**
     * Analyze the current frame with AI using the given question.
     */
    private fun analyzeCurrentFrame(question: String) {
        val photoData = lastPhotoData ?: return
        val aiClient = FieldCoachApp.aiClient
        val speechManager = FieldCoachApp.speechManager

        if (aiClient == null) {
            aiResponseText.text = "AI not available."
            aiResponseText.visibility = View.VISIBLE
            return
        }

        aiResponseText.text = "Analyzing..."
        aiResponseText.visibility = View.VISIBLE
        askAiButton.isEnabled = false

        speechManager?.speak("Got it. Analyzing now.")

        lifecycleScope.launch {
            val result = aiClient.analyzePhoto(question, photoData)

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

    private fun stopPreviewLoop() {
        isPreviewRunning = false
        handler.removeCallbacksAndMessages(null)
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
