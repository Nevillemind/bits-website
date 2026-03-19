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
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
