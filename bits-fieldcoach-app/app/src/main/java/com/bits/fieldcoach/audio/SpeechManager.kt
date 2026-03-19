package com.bits.fieldcoach.audio

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Manages speech-to-text (STT) and text-to-speech (TTS) for Field Coach.
 *
 * STT: Uses Android's built-in SpeechRecognizer (on-device, free, no API key)
 * TTS: Uses Android's built-in TextToSpeech engine
 *
 * Audio routes through Bluetooth SCO to the glasses mic/speaker when connected.
 * When LC3 audio is available from glasses, it can be used as supplementary input.
 */
class SpeechManager(private val context: Context) {
    companion object {
        private const val TAG = "SpeechManager"
    }

    // State
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // Audio manager for Bluetooth routing
    private var audioManager: AudioManager? = null

    // STT
    private var speechRecognizer: SpeechRecognizer? = null
    private var transcriptionCallback: ((String) -> Unit)? = null
    private var partialCallback: ((String) -> Unit)? = null

    // TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speakCallback: (() -> Unit)? = null

    // LC3 audio from glasses
    private var lc3AudioAvailable = false

    /**
     * Initialize both STT and TTS engines
     */
    fun initialize() {
        // Get audio manager for Bluetooth routing
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Route audio to Bluetooth device (glasses)
        enableBluetoothAudio()

        // Initialize TTS
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
                ttsReady = true
                Log.i(TAG, "TTS initialized")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }

        // TTS progress listener
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                speakCallback?.invoke()
                speakCallback = null
            }

            @Deprecated("Deprecated")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                speakCallback?.invoke()
                speakCallback = null
            }
        })

        // Initialize STT
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            Log.i(TAG, "STT initialized")
        } else {
            Log.e(TAG, "Speech recognition not available on this device")
        }
    }

    /**
     * Start listening for voice input through glasses mic
     */
    fun startListening(
        onTranscription: (String) -> Unit,
        onPartial: ((String) -> Unit)? = null
    ) {
        if (_isSpeaking.value) {
            Log.w(TAG, "Cannot listen while speaking")
            return
        }

        transcriptionCallback = onTranscription
        partialCallback = onPartial

        // Make sure Bluetooth SCO is active for mic input
        try {
            audioManager?.let { am ->
                if (!am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting Bluetooth SCO for mic: ${e.message}")
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            // Use COMMUNICATION audio source to pick up Bluetooth SCO mic
            putExtra("android.speech.extra.AUDIO_SOURCE", android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }

        try {
            speechRecognizer?.startListening(intent)
            _isListening.value = true
            Log.d(TAG, "Started listening via Bluetooth SCO mic")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognition", e)
            // Fallback — try without the audio source override
            try {
                val fallbackIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }
                speechRecognizer?.startListening(fallbackIntent)
                _isListening.value = true
                Log.d(TAG, "Started listening via fallback (phone mic)")
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback speech recognition also failed", e2)
            }
        }
    }

    /**
     * Stop listening
     */
    fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            _isListening.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping speech recognition", e)
        }
    }

    /**
     * Speak text through glasses speaker via Bluetooth SCO
     */
    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready")
            onComplete?.invoke()
            return
        }

        // Ensure Bluetooth SCO is active before speaking
        enableBluetoothAudio()

        speakCallback = onComplete
        _isSpeaking.value = true

        val utteranceId = UUID.randomUUID().toString()
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            // Route through VOICE_CALL stream which goes through SCO to Bluetooth
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /**
     * Stop current speech
     */
    fun stopSpeaking() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /**
     * Route all audio through Bluetooth (glasses speaker + mic)
     */
    fun enableBluetoothAudio() {
        try {
            audioManager?.let { am ->
                // Enable Bluetooth SCO for voice audio
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                am.isSpeakerphoneOn = false
                Log.i(TAG, "Bluetooth SCO audio enabled — routing to glasses")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling Bluetooth audio", e)
        }
    }

    /**
     * Disable Bluetooth audio routing
     */
    fun disableBluetoothAudio() {
        try {
            audioManager?.let { am ->
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
                am.mode = AudioManager.MODE_NORMAL
                Log.i(TAG, "Bluetooth SCO audio disabled")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling Bluetooth audio", e)
        }
    }

    /**
     * Notify that LC3 audio from glasses mic is available.
     * When available, the glasses mic audio comes through BLE LC3 packets
     * in addition to (or instead of) Bluetooth SCO.
     */
    fun setLc3AudioAvailable(available: Boolean) {
        lc3AudioAvailable = available
        Log.i(TAG, "LC3 audio from glasses: ${if (available) "available" else "unavailable"}")
    }

    /**
     * Handle incoming LC3 PCM audio data from glasses.
     * This data comes from the glasses microphone via BLE LC3 packets,
     * decoded by Lc3Decoder into raw PCM.
     *
     * Currently logged for monitoring. When the Android SpeechRecognizer
     * picks up audio via Bluetooth SCO, that path is preferred.
     * This method exists for future direct PCM-to-STT integration.
     */
    fun handleGlassesAudioData(pcmData: ByteArray) {
        // PCM audio from glasses mic is received here
        // Android SpeechRecognizer already picks up glasses audio via BT SCO
        // This data could be used for custom STT or audio recording
        Log.v(TAG, "Glasses LC3 audio: ${pcmData.size} bytes")
    }

    /**
     * Cleanup resources
     */
    fun shutdown() {
        disableBluetoothAudio()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    // -----------------------------------------------------------------------
    // Recognition Listener
    // -----------------------------------------------------------------------

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _isListening.value = false
            Log.d(TAG, "Speech ended")
        }

        override fun onError(error: Int) {
            _isListening.value = false
            val errorMsg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error: $error"
            }
            Log.w(TAG, "Recognition error: $errorMsg")

            // No auto-restart — mic is push-to-talk only
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: return

            if (text.isNotEmpty()) {
                Log.i(TAG, "Final transcription: $text")
                transcriptionCallback?.invoke(text)
            }

            // No auto-restart — mic is push-to-talk only
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: return
            if (text.isNotEmpty()) {
                partialCallback?.invoke(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
