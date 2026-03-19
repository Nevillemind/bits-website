package com.bits.fieldcoach

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bits.fieldcoach.ai.FieldCoachClient
import com.bits.fieldcoach.audio.SpeechManager
import com.bits.fieldcoach.ble.*
import com.bits.fieldcoach.camera.PhoneCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * BITS Field Coach — Main Activity
 *
 * Single screen that:
 * 1. Connects to Mentra Live glasses via BLE (K900 protocol)
 * 2. Listens for voice commands through glasses mic
 * 3. Sends questions to Field Coach AI
 * 4. Speaks answers through glasses speaker
 * 5. Handles photo requests via glasses camera or phone camera
 * 6. Receives LC3 audio from glasses mic
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "BITSFieldCoach"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    // Core modules
    private lateinit var bleManager: BleConnectionManager
    private lateinit var speechManager: SpeechManager
    private lateinit var aiClient: FieldCoachClient
    private val phoneCamera = PhoneCamera()

    // State
    private var isBusy = false
    private var lastQuestion = ""
    private var lastAnswer = ""

    // Vision keywords
    private val visionKeywords = listOf(
        "look at this", "what is this", "what am i looking at", "can you see",
        "take a photo", "snap a photo", "photo", "picture", "what do you see",
        "analyze this", "what's wrong here", "what's wrong with this"
    )

    private val escalationKeywords = listOf(
        "escalate", "get help", "need help", "supervisor", "head tech",
        "can't figure", "i need a human", "connect me", "call someone"
    )

    // UI elements
    private lateinit var statusText: TextView
    private lateinit var batteryText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var responseText: TextView
    private lateinit var connectButton: Button
    private lateinit var micButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI
        statusText = findViewById(R.id.statusText)
        batteryText = findViewById(R.id.batteryText)
        transcriptText = findViewById(R.id.transcriptText)
        responseText = findViewById(R.id.responseText)
        connectButton = findViewById(R.id.connectButton)

        // Initialize modules
        bleManager = BleConnectionManager(this)
        speechManager = SpeechManager(this)
        aiClient = FieldCoachClient("https://bitsfieldcoach.com")

        // Request permissions
        requestPermissions()

        micButton = findViewById(R.id.micButton)
        val cameraButton: Button = findViewById(R.id.cameraButton)

        // Button handlers
        connectButton.setOnClickListener { startConnection() }

        // Camera button — try glasses camera first, fall back to phone camera
        cameraButton.setOnClickListener {
            if (bleManager.isConnected() && bleManager.isFullyBooted()) {
                // Use glasses camera via BLE
                speechManager.speak("Taking a photo now.")
                val requestId = "photo_${System.currentTimeMillis()}"
                bleManager.requestPhoto(requestId)
                Log.i(TAG, "Photo requested via glasses camera: $requestId")
            } else {
                // Fall back to phone camera
                val intent = phoneCamera.createCameraIntent(this)
                if (intent != null) {
                    startActivityForResult(intent, PhoneCamera.REQUEST_CODE)
                }
            }
        }

        // Mic button — push to talk
        micButton.setOnClickListener {
            if (speechManager.isListening.value) {
                // Stop listening
                speechManager.stopListening()
                micButton.text = "MIC"
                micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1A56DB.toInt())
            } else {
                // Start listening
                micButton.text = "LISTENING..."
                micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
                beginListening()
            }
        }

        // Observe listening state to update mic button
        lifecycleScope.launch {
            speechManager.isListening.collectLatest { listening ->
                runOnUiThread {
                    if (listening) {
                        micButton.text = "LISTENING..."
                        micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
                    } else if (!isBusy) {
                        micButton.text = "MIC"
                        micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1A56DB.toInt())
                    }
                }
            }
        }

        // Observe speaking state
        lifecycleScope.launch {
            speechManager.isSpeaking.collectLatest { speaking ->
                runOnUiThread {
                    if (speaking) {
                        micButton.text = "SPEAKING..."
                        micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF16A34A.toInt())
                    }
                }
            }
        }

        // Observe connection state (from BLE manager OR our local override)
        lifecycleScope.launch {
            bleManager.connectionState.collectLatest { state ->
                _connectionState.value = state
            }
        }

        lifecycleScope.launch {
            _connectionState.collectLatest { state ->
                runOnUiThread {
                    statusText.text = when (state) {
                        ConnectionStatus.DISCONNECTED -> "Disconnected"
                        ConnectionStatus.ADVERTISING -> "Waiting for glasses..."
                        ConnectionStatus.CONNECTING -> "Connecting..."
                        ConnectionStatus.CONNECTED -> "Glasses Connected"
                        ConnectionStatus.ERROR -> "Connection Error"
                    }

                    connectButton.text = when (state) {
                        ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR -> "Connect Glasses"
                        ConnectionStatus.ADVERTISING -> "Searching..."
                        else -> "Disconnect"
                    }
                }
            }
        }

        // Observe BLE scan debug
        val debugText: TextView = findViewById(R.id.debugText)
        lifecycleScope.launch {
            bleManager.discoveredDevices.collectLatest { devices ->
                runOnUiThread {
                    if (devices.isEmpty()) {
                        debugText.text = "BLE: scanning..."
                    } else {
                        debugText.text = devices.takeLast(8).joinToString("\n")
                    }
                }
            }
        }

        // Observe battery
        lifecycleScope.launch {
            bleManager.batteryLevel.collectLatest { level ->
                runOnUiThread {
                    batteryText.text = if (level >= 0) "Battery: $level%" else ""
                }
            }
        }

        // Listen for glasses events
        bleManager.addEventListener { event ->
            when (event) {
                is GlassesEvent.PhotoResponse -> handlePhotoResponse(event)
                is GlassesEvent.ButtonPress -> handleButtonPress(event)
                is GlassesEvent.AudioData -> handleAudioData(event)
                is GlassesEvent.ConnectionState -> {
                    if (event.connected) {
                        speechManager.setLc3AudioAvailable(true)
                        startVoiceLoop()
                    } else {
                        speechManager.setLc3AudioAvailable(false)
                    }
                }
                is GlassesEvent.BatteryUpdate -> {
                    // Battery is also handled via StateFlow, but log it
                    Log.d(TAG, "Battery: ${event.level}% (charging: ${event.charging})")
                }
                is GlassesEvent.FirmwareVersion -> {
                    Log.i(TAG, "Glasses firmware: ${event.version}")
                }
                is GlassesEvent.HeartbeatResponse -> {
                    // Handled internally by BleConnectionManager
                }
                is GlassesEvent.GlassesReady -> {
                    // Handled internally by BleConnectionManager
                }
                else -> Log.d(TAG, "Glasses event: $event")
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            initializeAfterPermissions()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            initializeAfterPermissions()
        }
    }

    private fun initializeAfterPermissions() {
        speechManager.initialize()
        bleManager.initialize()
        Log.i(TAG, "BITS Field Coach initialized")
    }

    private fun startConnection() {
        // If currently connected (BLE or Classic), disconnect
        if (bleManager.isConnected() || _connectionState.value == ConnectionStatus.CONNECTED) {
            bleManager.disconnect()
            speechManager.stopListening()
            speechManager.stopSpeaking()
            speechManager.disableBluetoothAudio()
            _connectionState.value = ConnectionStatus.DISCONNECTED
            Log.i(TAG, "Disconnected from glasses")
            return
        }

        // Start BLE connection (handles both saved device + scan)
        bleManager.startAdvertisingAndListen()

        // Also enable Classic Bluetooth audio immediately if available
        val audioMgr = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        if (audioMgr.isBluetoothA2dpOn || audioMgr.isBluetoothScoAvailableOffCall) {
            Log.i(TAG, "Classic BT audio available — enabling")
            speechManager.enableBluetoothAudio()
            startVoiceLoop()
        }
    }

    // Allow external state update
    private val _connectionState = MutableStateFlow(ConnectionStatus.DISCONNECTED)

    /**
     * Welcome message when glasses connect.
     * Mic button controls when listening starts.
     */
    private fun startVoiceLoop() {
        speechManager.speak("BITS Field Coach is ready. Tap the mic to ask a question.")
    }

    private fun beginListening() {
        speechManager.startListening(
            onTranscription = { text -> handleTranscription(text) },
            onPartial = { partial ->
                runOnUiThread { transcriptText.text = "Hearing: $partial" }
            }
        )
    }

    private fun handleTranscription(text: String) {
        if (text.length < 3 || isBusy) return

        isBusy = true
        runOnUiThread { transcriptText.text = "You: $text" }
        Log.i(TAG, "Question: $text")

        val lower = text.lowercase()

        lifecycleScope.launch {
            try {
                when {
                    // Escalation
                    escalationKeywords.any { lower.contains(it) } -> {
                        speechManager.speak("Escalating now. Connecting you to your supervisor.")
                        val result = aiClient.escalate("glasses_user", lastQuestion.ifEmpty { text }, lastAnswer)
                        result.onSuccess {
                            speechManager.speak("Your supervisor has been notified.") { finishProcessing() }
                        }.onFailure {
                            speechManager.speak("I had trouble reaching the dashboard. Please call your supervisor directly.") { finishProcessing() }
                        }
                    }

                    // Vision request — try glasses camera, fall back to phone after timeout
                    visionKeywords.any { lower.contains(it) } -> {
                        speechManager.speak("Taking a photo now.")
                        val requestId = "photo_${System.currentTimeMillis()}"
                        
                        if (bleManager.isConnected()) {
                            bleManager.requestPhoto(requestId)
                            Log.i(TAG, "Photo requested via glasses: $requestId")
                            // Photo response handled in event listener
                            // Timeout fallback to phone camera after 25 seconds
                            // (glasses BLE file transfer can take 10-20s)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (isBusy) {
                                    Log.w(TAG, "Glasses photo timed out (25s) — falling back to phone camera")
                                    speechManager.speak("Using phone camera instead.")
                                    runOnUiThread {
                                        val intent = phoneCamera.createCameraIntent(this@MainActivity)
                                        if (intent != null) {
                                            startActivityForResult(intent, PhoneCamera.REQUEST_CODE)
                                        }
                                    }
                                }
                            }, 25000)
                        } else {
                            // No BLE — phone camera immediately
                            runOnUiThread {
                                val intent = phoneCamera.createCameraIntent(this@MainActivity)
                                if (intent != null) {
                                    startActivityForResult(intent, PhoneCamera.REQUEST_CODE)
                                } else {
                                    speechManager.speak("I couldn't access the camera.") { finishProcessing() }
                                }
                            }
                        }
                    }

                    // Standard Q&A
                    else -> {
                        val result = aiClient.askQuestion(text)
                        result.onSuccess { answer ->
                            lastQuestion = text
                            lastAnswer = answer
                            runOnUiThread { responseText.text = answer }
                            speechManager.speak(answer) { finishProcessing() }
                        }.onFailure { error ->
                            speechManager.speak("Something went wrong. Please try again.") { finishProcessing() }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing question", e)
                speechManager.speak("Something went wrong. Please try again.") { finishProcessing() }
            }
        }
    }

    private fun finishProcessing() {
        isBusy = false
        runOnUiThread {
            micButton.text = "MIC"
            micButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1A56DB.toInt())
        }
    }

    private fun handlePhotoResponse(event: GlassesEvent.PhotoResponse) {
        lifecycleScope.launch {
            if (event.success && event.photoData != null) {
                speechManager.speak("Analyzing what I see.")
                val result = aiClient.analyzePhoto(lastQuestion.ifEmpty { "What do you see?" }, event.photoData)
                result.onSuccess { answer ->
                    lastAnswer = answer
                    runOnUiThread { responseText.text = answer }
                    speechManager.speak(answer) { finishProcessing() }
                }.onFailure {
                    speechManager.speak("I couldn't analyze the photo. Please try again.") { finishProcessing() }
                }
            } else {
                speechManager.speak("Photo capture failed. Please try again.") { finishProcessing() }
            }
        }
    }

    private fun handleButtonPress(event: GlassesEvent.ButtonPress) {
        Log.i(TAG, "Button ${event.button} pressed (long: ${event.longPress})")
        when {
            event.button == 1 && !event.longPress -> {
                // Short press button 1 — start/stop listening
                if (speechManager.isListening.value) {
                    speechManager.stopListening()
                } else {
                    beginListening()
                }
            }
            event.button == 1 && event.longPress -> {
                // Long press button 1 — photo mode
                handleTranscription("take a photo")
            }
            event.button == 2 -> {
                // Button 2 — escalate
                handleTranscription("escalate")
            }
        }
    }

    private fun handleAudioData(event: GlassesEvent.AudioData) {
        // Forward LC3 decoded PCM to speech manager for potential processing
        speechManager.handleGlassesAudioData(event.data)
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PhoneCamera.REQUEST_CODE) {
            val photoBytes = phoneCamera.processResult(resultCode, data)
            if (photoBytes != null) {
                // Send to vision AI
                speechManager.speak("Analyzing what I see.")
                transcriptText.text = "Photo captured — analyzing..."
                lifecycleScope.launch {
                    val result = aiClient.analyzePhoto(
                        lastQuestion.ifEmpty { "What do you see in this photo? Describe any issues or observations." },
                        photoBytes
                    )
                    result.onSuccess { answer ->
                        lastAnswer = answer
                        runOnUiThread { responseText.text = answer }
                        speechManager.speak(answer)
                    }.onFailure {
                        speechManager.speak("I couldn't analyze the photo. Please try again.")
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechManager.shutdown()
        bleManager.disconnect()
    }
}
