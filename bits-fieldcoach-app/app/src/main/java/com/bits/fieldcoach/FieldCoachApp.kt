package com.bits.fieldcoach

import android.app.Application

/**
 * Application-level singleton holder for shared managers.
 * Allows CameraPreviewActivity (and future activities) to access
 * the BLE, Speech, and AI clients initialized in MainActivity.
 */
class FieldCoachApp : Application() {
    companion object {
        lateinit var instance: FieldCoachApp
            private set

        var bleManager: com.bits.fieldcoach.ble.BleConnectionManager? = null
        var speechManager: com.bits.fieldcoach.audio.SpeechManager? = null
        var aiClient: com.bits.fieldcoach.ai.FieldCoachClient? = null

        // Mode selector — switches between Field Coach and Halo backends
        enum class AppMode(val displayName: String, val baseUrl: String) {
            FIELD_COACH("Field Coach", "https://bitsfieldcoach.com"),
            HALO("Halo", "https://bitshalo.com")
        }

        var currentMode: AppMode = AppMode.FIELD_COACH

        fun switchMode(mode: AppMode) {
            currentMode = mode
            aiClient = com.bits.fieldcoach.ai.FieldCoachClient(mode.baseUrl)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
