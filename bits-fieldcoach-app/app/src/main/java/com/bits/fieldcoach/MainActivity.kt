package com.bits.fieldcoach

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bits.fieldcoach.ai.FieldCoachClient
import com.bits.fieldcoach.audio.SpeechManager
import com.bits.fieldcoach.ble.*
import com.bits.fieldcoach.camera.PhoneCamera
import com.bits.fieldcoach.rtmp.LivestreamManager
import com.bits.fieldcoach.stream.StreamRelay
import com.bits.fieldcoach.stream.StreamStatus
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
    private lateinit var goLiveButton: Button
    private lateinit var micButton: Button
    private lateinit var debugText: TextView
    private lateinit var liveDebugText: TextView  // dedicated livestream debug — nothing else writes here
    private lateinit var hotspotSsidInput: EditText
    private lateinit var hotspotPasswordInput: EditText
    private var livestreamManager: LivestreamManager? = null
    private var isLive = false
    private var livestreamActive = false  // blocks BLE from overwriting debug log

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind UI
        statusText = findViewById(R.id.statusText)
        batteryText = findViewById(R.id.batteryText)
        transcriptText = findViewById(R.id.transcriptText)
        responseText = findViewById(R.id.responseText)
        connectButton = findViewById(R.id.connectButton)
        goLiveButton = findViewById(R.id.goLiveButton)
        hotspotSsidInput = findViewById(R.id.hotspotSsidInput)
        hotspotPasswordInput = findViewById(R.id.hotspotPasswordInput)

        // Load saved hotspot credentials
        val prefs = getSharedPreferences("bits_fieldcoach", Context.MODE_PRIVATE)
        hotspotSsidInput.setText(prefs.getString("hotspot_ssid", ""))
        hotspotPasswordInput.setText(prefs.getString("hotspot_password", ""))

        // Save hotspot credentials button
        findViewById<Button>(R.id.hotspotSaveButton).setOnClickListener {
            val ssid = hotspotSsidInput.text.toString().trim()
            val pass = hotspotPasswordInput.text.toString().trim()
            if (ssid.isEmpty()) {
                responseText.text = "Enter your hotspot name first."
                return@setOnClickListener
            }
            prefs.edit()
                .putString("hotspot_ssid", ssid)
                .putString("hotspot_password", pass)
                .apply()
            responseText.text = "Hotspot settings saved ✓"
        }

        // Initialize modules
        bleManager = BleConnectionManager(this)
        speechManager = SpeechManager(this)
        aiClient = FieldCoachClient("https://bitsfieldcoach.com")

        // Store in app-level holder so CameraPreviewActivity can access them
        FieldCoachApp.bleManager = bleManager
        FieldCoachApp.speechManager = speechManager
        FieldCoachApp.aiClient = aiClient

        // Request permissions
        requestPermissions()

        micButton = findViewById(R.id.micButton)
        val cameraButton: Button = findViewById(R.id.cameraButton)

        // Button handlers
        connectButton.setOnClickListener { startConnection() }

        // GO LIVE button — start/stop streaming to supervisor
        goLiveButton.setOnClickListener {
            if (isLive) {
                stopLiveStream()
            } else {
                startLiveStream()
            }
        }

        // Camera button — launches live camera preview screen
        cameraButton.setOnClickListener {
            val intent = Intent(this, CameraPreviewActivity::class.java)
            startActivity(intent)
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
        debugText = findViewById(R.id.debugText)
        liveDebugText = findViewById(R.id.liveDebugText)
        lifecycleScope.launch {
            bleManager.discoveredDevices.collectLatest { devices ->
                runOnUiThread {
                    if (livestreamActive) return@runOnUiThread  // livestream owns debug log
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

    // -----------------------------------------------------------------------
    // GO LIVE — Stream glasses camera to supervisor via Field Coach backend
    // -----------------------------------------------------------------------

    private fun startLiveStream() {
        if (!bleManager.isConnected()) {
            responseText.text = "Connect glasses first, then go live."
            return
        }

        val prefs = getSharedPreferences("bits_fieldcoach", Context.MODE_PRIVATE)

        // Check hotspot credentials are saved
        val hotspotSsid = prefs.getString("hotspot_ssid", "") ?: ""
        val hotspotPass = prefs.getString("hotspot_password", "") ?: ""
        if (hotspotSsid.isEmpty()) {
            responseText.text = "Set your hotspot name & password above first, then Save."
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

        livestreamManager = LivestreamManager(this, bleManager, "wss://bitsfieldcoach.com", hotspotSsid, hotspotPass)
        livestreamManager?.setStateListener { state ->
            runOnUiThread {
                when (state) {
                    LivestreamManager.State.IDLE -> {
                        isLive = false
                        goLiveButton.text = "GO LIVE"
                        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF991B1B.toInt())
                    }
                    LivestreamManager.State.LIVE -> {
                        isLive = true
                        goLiveButton.text = "● LIVE — TAP TO STOP"
                        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFDC2626.toInt())
                        responseText.text = "Live! Supervisor is watching at bitsfieldcoach.com/livestream/"
                    }
                    LivestreamManager.State.ERROR -> {
                        isLive = false
                        goLiveButton.text = "GO LIVE"
                        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF991B1B.toInt())
                        responseText.text = "Stream error. Check debug log above."
                    }
                    else -> {
                        goLiveButton.text = "STARTING..."
                        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF59E0B.toInt())
                    }
                }
            }
        }
        // Wire debug log to DEDICATED live debug view — nothing else can overwrite
        livestreamManager?.setDebugListener { log ->
            runOnUiThread {
                liveDebugText.text = log.takeLast(3000)
            }
        }
        livestreamActive = true
        livestreamManager?.goLive(workerId)
    }

    private fun stopLiveStream() {
        livestreamActive = false
        livestreamManager?.stopLive()
        livestreamManager = null
        isLive = false
        goLiveButton.text = "GO LIVE"
        goLiveButton.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF991B1B.toInt())
        responseText.text = "Stream stopped."
        Log.i(TAG, "Live stream stopped")
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )

        // Android 13+ requires NEARBY_WIFI_DEVICES for local hotspot
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val permissionsArray = permissions.toTypedArray()

        val needed = permissionsArray.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)        } else {
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
                        val techId = android.provider.Settings.Secure.getString(
                            contentResolver, android.provider.Settings.Secure.ANDROID_ID
                        ) ?: "worker"
                        bleManager.startRtmpStream("rtmp://bitsfieldcoach.com/live/worker_$techId")
                        speechManager.speak("Starting live feed for your supervisor.")
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
                            // Timeout fallback to phone camera after 25 seconds
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
        // Photo response from glasses — handled by AI analysis
        // (Live streaming now uses RTMP, not BLE photo forwarding)

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
                if (speechManager.isListening.value) {
                    speechManager.stopListening()
                } else {
                    beginListening()
                }
            }
            event.button == 1 && event.longPress -> {
                handleTranscription("take a photo")
            }
            event.button == 2 -> {
                handleTranscription("escalate")
            }
        }
    }

    private fun handleAudioData(event: GlassesEvent.AudioData) {
        speechManager.handleGlassesAudioData(event.data)
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PhoneCamera.REQUEST_CODE) {
            val photoBytes = phoneCamera.processResult(resultCode, data)
            if (photoBytes != null) {
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
