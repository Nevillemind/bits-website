package com.bits.fieldcoach.ble

import android.util.Log
import org.json.JSONObject

/**
 * Protocol handler for Mentra Live K900 BLE communication.
 * Commands are JSON objects wrapped in K900 binary framing for transmission.
 *
 * Outgoing: JSON → K900Protocol.packJsonToK900() → BLE write
 * Incoming: BLE read → K900Protocol.processReceivedBytesToJson() → parse event
 */
object GlassesProtocol {
    private const val TAG = "GlassesProtocol"

    // -----------------------------------------------------------------------
    // Command builders — create JSON, then K900-pack for BLE transmission
    // -----------------------------------------------------------------------

    fun createMicCommand(enabled: Boolean): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_MIC_STATE)
            put("enabled", enabled)
        }
        return packForSend(json.toString())
    }

    fun createVadCommand(enabled: Boolean): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_MIC_VAD_STATE)
            put("enabled", enabled)
        }
        return packForSend(json.toString())
    }

    fun createPhotoCommand(requestId: String): ByteArray {
        val bleImgId = "I" + String.format("%09d", System.currentTimeMillis() % 1000000000)
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_TAKE_PHOTO)
            put("requestId", requestId)
            put("appId", "com.bits.fieldcoach")
            put("size", "small")
            put("compress", "none")
            put("silent", false)
            put("bleImgId", bleImgId)
            put("transferMethod", "auto")
        }
        return packForSend(json.toString(), wakeup = true)
    }

    fun createLedCommand(enabled: Boolean): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_LED_CONTROL)
            put("enabled", enabled)
        }
        return packForSend(json.toString())
    }

    fun createFirmwareRequest(): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_GET_FIRMWARE)
        }
        return packForSend(json.toString())
    }

    fun createBatteryRequest(): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_GET_BATTERY)
        }
        return packForSend(json.toString())
    }

    /**
     * Create heartbeat/readiness check: {C: "cs_hrt", B: ""}
     */
    fun createHeartbeatRequest(): ByteArray {
        val json = JSONObject().apply {
            put("C", BleConstants.CMD_HEARTBEAT_REQUEST)
            put("B", "")
        }
        return K900Protocol.packJsonToK900(json.toString(), wakeup = false) ?: json.toString().toByteArray()
    }

    /**
     * Create phone_ready message sent after glasses report ready.
     */
    fun createPhoneReady(): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_PHONE_READY)
            put("timestamp", System.currentTimeMillis())
        }
        return packForSend(json.toString(), wakeup = true)
    }

    /**
     * Create BLE MTU config notification.
     */
    fun createMtuConfig(mtu: Int): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_SET_BLE_MTU)
            put("mtu", mtu)
        }
        return packForSend(json.toString())
    }

    /**
     * Create heartbeat ping.
     */
    fun createPing(): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_PING)
        }
        return packForSend(json.toString())
    }

    /**
     * Create enable audio TX command (glasses mic streaming).
     */
    fun createEnableAudioTx(enabled: Boolean): ByteArray {
        val json = JSONObject().apply {
            put("type", BleConstants.CMD_ENABLE_AUDIO_TX)
            put("enabled", enabled)
        }
        return packForSend(json.toString())
    }

    // -----------------------------------------------------------------------
    // Send helper — packs JSON with K900 binary framing
    // -----------------------------------------------------------------------

    private fun packForSend(jsonStr: String, wakeup: Boolean = false): ByteArray {
        return K900Protocol.packJsonToK900(jsonStr, wakeup) ?: jsonStr.toByteArray()
    }

    // -----------------------------------------------------------------------
    // Incoming data parser
    // -----------------------------------------------------------------------

    /**
     * Parse incoming data from glasses into a GlassesEvent.
     * Handles both K900-framed and raw JSON data.
     */
    fun parseResponse(data: ByteArray): GlassesEvent? {
        // Try K900 protocol first
        if (K900Protocol.isK900Format(data)) {
            val json = K900Protocol.processReceivedBytesToJson(data)
            if (json != null) {
                return parseJsonEvent(json)
            }
            // Could be a file packet — not JSON
            return null
        }

        // Try raw JSON
        return try {
            val text = String(data, Charsets.UTF_8).trim()
            if (text.startsWith("{")) {
                val json = JSONObject(text)
                parseJsonEvent(json)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not parse as JSON: ${data.size} bytes")
            null
        }
    }

    /**
     * Parse a JSON object into a typed GlassesEvent.
     */
    fun parseJsonEvent(json: JSONObject): GlassesEvent? {
        // Check for K900 C-field commands (sr_hrt, sr_tpevt, etc.)
        val cField = json.optString("C", "")
        if (cField.isNotEmpty()) {
            return parseCFieldCommand(cField, json)
        }

        val type = json.optString("type", "")
        return when (type) {
            "audio_data" -> {
                try {
                    GlassesEvent.AudioData(
                        data = android.util.Base64.decode(json.getString("data"), android.util.Base64.DEFAULT)
                    )
                } catch (e: Exception) {
                    null
                }
            }

            "photo_response" -> GlassesEvent.PhotoResponse(
                requestId = json.optString("requestId", ""),
                success = json.optBoolean("success", false),
                photoData = if (json.has("photoData"))
                    android.util.Base64.decode(json.getString("photoData"), android.util.Base64.DEFAULT)
                else null,
                error = json.optString("error", null)
            )

            "battery_update", "battery_status" -> GlassesEvent.BatteryUpdate(
                level = json.optInt("level", json.optInt("pt", -1)),
                charging = json.optBoolean("charging", json.optBoolean("charg", false))
            )

            "firmware_version" -> GlassesEvent.FirmwareVersion(
                version = json.optString("version", "unknown")
            )

            "button_press" -> GlassesEvent.ButtonPress(
                button = json.optInt("button", 0),
                longPress = json.optBoolean("longPress", false)
            )

            "connection_state" -> GlassesEvent.ConnectionState(
                connected = json.optBoolean("connected", false)
            )

            BleConstants.CMD_GLASSES_READY -> GlassesEvent.GlassesReady

            "ble_photo_ready" -> GlassesEvent.BlePhotoReady(
                bleImgId = json.optString("bleImgId", ""),
                requestId = json.optString("requestId", ""),
                compressionTimeMs = json.optLong("compressionDurationMs", json.optLong("compressionTime", 0))
            )

            "rtmp_stream_status" -> GlassesEvent.RtmpStreamStatus(
                status = json.optString("status", ""),
                errorDetails = if (json.has("error")) json.optString("error", null) else null
            )

            "ble_photo_complete" -> GlassesEvent.BlePhotoComplete(
                success = json.optBoolean("success", true)
            )

            "msg_ack" -> GlassesEvent.MessageAck(
                messageId = json.optLong("mId", -1)
            )

            "keep_alive_ack" -> GlassesEvent.KeepAliveAck

            "chunked_msg" -> GlassesEvent.ChunkedMessage(json)

            else -> {
                if (type.isNotEmpty()) {
                    Log.d(TAG, "Unknown event type: $type")
                }
                GlassesEvent.Unknown(type, json.toString())
            }
        }
    }

    /**
     * Parse K900 C-field commands (sr_hrt, sr_tpevt, etc.)
     */
    private fun parseCFieldCommand(command: String, json: JSONObject): GlassesEvent? {
        val body = json.optJSONObject("B") ?: json.optString("B", "").let {
            try { JSONObject(it) } catch (e: Exception) { null }
        }

        return when (command) {
            BleConstants.CMD_HEARTBEAT_RESPONSE -> {
                // sr_hrt: heartbeat response with ready status and battery
                val ready = body?.optInt("ready", 0) ?: 0
                val battery = body?.optInt("pt", -1) ?: -1
                val charging = body?.optInt("charg", 0) ?: 0

                if (battery >= 0) {
                    // Emit battery update
                    GlassesEvent.HeartbeatResponse(
                        ready = ready == 1,
                        battery = battery,
                        charging = charging == 1
                    )
                } else {
                    GlassesEvent.HeartbeatResponse(ready = ready == 1, battery = -1, charging = false)
                }
            }

            "sr_tpevt" -> {
                // Touchpad event
                val action = body?.optInt("action", 0) ?: 0
                GlassesEvent.ButtonPress(button = action, longPress = action > 2)
            }

            "sr_batv" -> {
                // Battery voltage response
                val level = body?.optInt("pt", -1) ?: -1
                val charging = body?.optInt("charg", 0) == 1
                if (level >= 0) GlassesEvent.BatteryUpdate(level, charging) else null
            }

            else -> {
                Log.d(TAG, "Unknown C-field command: $command")
                GlassesEvent.Unknown(command, json.toString())
            }
        }
    }
}

/**
 * Events received from Mentra Live glasses.
 */
sealed class GlassesEvent {
    data class AudioData(val data: ByteArray) : GlassesEvent()
    data class PhotoResponse(
        val requestId: String,
        val success: Boolean,
        val photoData: ByteArray?,
        val error: String?
    ) : GlassesEvent()
    data class BatteryUpdate(val level: Int, val charging: Boolean) : GlassesEvent()
    data class FirmwareVersion(val version: String) : GlassesEvent()
    data class ButtonPress(val button: Int, val longPress: Boolean) : GlassesEvent()
    data class ConnectionState(val connected: Boolean) : GlassesEvent()
    data class HeartbeatResponse(val ready: Boolean, val battery: Int, val charging: Boolean) : GlassesEvent()
    data object GlassesReady : GlassesEvent()
    data class BlePhotoReady(val bleImgId: String, val requestId: String, val compressionTimeMs: Long) : GlassesEvent()
    data class RtmpStreamStatus(val status: String, val errorDetails: String?) : GlassesEvent()
    data class BlePhotoComplete(val success: Boolean) : GlassesEvent()
    data class MessageAck(val messageId: Long) : GlassesEvent()
    data object KeepAliveAck : GlassesEvent()
    data class ChunkedMessage(val json: JSONObject) : GlassesEvent()
    data class Unknown(val type: String, val raw: String) : GlassesEvent()
}
