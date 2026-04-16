package com.bits.fieldcoach

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bits.fieldcoach.ble.ConnectionStatus
import com.bits.fieldcoach.ble.GlassesEvent
import com.bits.fieldcoach.rtmp.LivestreamManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * BITS Field Coach — Live Camera Preview Activity
 *
 * TAKE PHOTO — takes a single BLE photo, displays on screen.
 * ASK AI — analyzes the current photo with AI.
 * GO LIVE — starts RTMP livestream via LivestreamManager (hotspot → RTMP → supervisor).
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
    private lateinit var micButton: Button
    private lateinit var backButton: Button
    private lateinit var aiResponseText: TextView
    private lateinit var questionInput: EditText

    // GO LIVE streaming — uses LivestreamManager (RTMP path)
    private lateinit var goLiveButton: Button
    private var livestreamManager: LivestreamManager? = null
    private var isLive = false

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

        // Bind new views
        micButton = findViewById(R.id.micButton)
        questionInput = findViewById(R.id.questionInput)

        // Back button
        backButton.setOnClickListener {
            stopPreviewLoop()
            finish()
        }

        // Take Photo button — single shot, shows on screen, NO auto-analyze
        startStopButton.text = "📸 TAKE PHOTO"
        startStopButton.setOnClickListener {
            takeSinglePhoto()
        }

        // Ask AI button — analyzes current photo with the question from input/voice
        askAiButton.setOnClickListener {
            val typedQuestion = questionInput.text.toString().trim()
            if (typedQuestion.isNotEmpty()) {
                pendingQuestion = typedQuestion
            }
            takePhotoAndAnalyze()
        }

        // Mic button — voice input for question
        micButton.setOnClickListener {
            val speechManager = FieldCoachApp.speechManager ?: return@setOnClickListener
            if (speechManager.isListening.value) {
                speechManager.stopListening()
                micButton.text = "🎤"
                micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF16A34A.toInt())
            } else {
                micButton.text = "⏹️"
                micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
                speechManager.startListening(
                    onTranscription = { text ->
                        runOnUiThread {
                            questionInput.setText(text)
                            pendingQuestion = text
                            micButton.text = "🎤"
                            micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF16A34A.toInt())
                            frameCountText.text = "Question set: \"$text\" — tap ASK AI"
                        }
                    },
                    onPartial = { partial ->
                        runOnUiThread {
                            questionInput.setText(partial)
                        }
                    }
                )
            }
        }

        // GO LIVE button — RTMP livestream via LivestreamManager (same as MainActivity)
        goLiveButton = findViewById(R.id.goLiveButton)
        goLiveButton.setOnClickListener {
            if (isLive) {
                stopLiveStream()
            } else {
                startLiveStream()
            }
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
        updateConnectionUi(bleManager?.connectionState?.value ?: ConnectionStatus.DISCONNECTED)
    }

    private var pendingQuestion: String = "What do you see? Describe any issues or observations."
    private var waitingForPhoto = false

    private fun setupGlassesEventListener() {
        val listener: (GlassesEvent) -> Unit = { event ->
            when (event) {
                is GlassesEvent.PhotoResponse -> {
                    if (event.success && event.photoData != null) {
                        handleNewFrame(event.photoData)
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
                            ConnectionStatus.CONNECTED
                        else
                            ConnectionStatus.DISCONNECTED
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

        runOnUiThread { startStopButton.isEnabled = true }
    }

    /**
     * Take a single photo from glasses via BLE.
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

        handler.postDelayed({
            startStopButton.isEnabled = true
            if (lastPhotoData == null) {
                frameCountText.text = "Photo timed out — tap to retry"
            }
        }, 10000)
    }

    /**
     * ASK AI — analyzes the already captured photo.
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

    // -----------------------------------------------------------------------
    // GO LIVE — RTMP livestream via LivestreamManager (same as MainActivity)
    // -----------------------------------------------------------------------

    private fun startLiveStream() {
        val bleManager = FieldCoachApp.bleManager
        if (bleManager == null || !bleManager.isConnected()) {
            aiResponseText.text = "Glasses not connected. Connect from main screen first."
            aiResponseText.visibility = View.VISIBLE
            return
        }

        val prefs = getSharedPreferences("bits_fieldcoach", Context.MODE_PRIVATE)

        // Check hotspot credentials are saved
        val hotspotSsid = prefs.getString("hotspot_ssid", "") ?: ""
        if (hotspotSsid.isEmpty()) {
            aiResponseText.text = "Set hotspot name & password on main screen first."
            aiResponseText.visibility = View.VISIBLE
            return
        }

        val workerId = prefs.getString("worker_id",
            "worker_${android.provider.Settings.Secure.getString(
                contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )?.take(6) ?: "unknown"}"
        ) ?: "worker_unknown"

        Log.i(TAG, "Starting RTMP live stream as: $workerId")
        goLiveButton.text = "CONNECTING..."
        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF59E0B.toInt())

        livestreamManager = LivestreamManager(this, bleManager, "wss://bitsfieldcoach.com",
            prefs.getString("hotspot_ssid", "") ?: "",
            prefs.getString("hotspot_password", "") ?: ""
        )
        livestreamManager?.setStateListener { state ->
            runOnUiThread {
                when (state) {
                    LivestreamManager.State.IDLE -> {
                        isLive = false
                        goLiveButton.text = "GO LIVE"
                        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFB91C1C.toInt())
                    }
                    LivestreamManager.State.LIVE -> {
                        isLive = true
                        goLiveButton.text = "● LIVE — TAP TO STOP"
                        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
                        frameCountText.text = "LIVE — streaming to supervisor"
                    }
                    LivestreamManager.State.ERROR -> {
                        isLive = false
                        goLiveButton.text = "GO LIVE"
                        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFB91C1C.toInt())
                        frameCountText.text = "Stream error. Check hotspot and try again."
                    }
                    else -> {
                        goLiveButton.text = "STARTING..."
                        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF59E0B.toInt())
                    }
                }
            }
        }
        livestreamManager?.goLive(workerId)
    }

    private fun stopLiveStream() {
        livestreamManager?.stopLive()
        livestreamManager = null
        isLive = false
        goLiveButton.text = "GO LIVE"
        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFB91C1B.toInt())
        frameCountText.text = "Stream stopped"
        Log.i(TAG, "Live stream stopped")
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
        stopLiveStream()
    }
}
