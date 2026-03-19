package com.bits.fieldcoach.ble

import java.util.UUID

/**
 * BLE constants from MentraOS source code (MIT licensed).
 * UUIDs match the K900 BES2800 MCU in Mentra Live glasses.
 */
object BleConstants {
    // Mentra Live BLE Service
    val SERVICE_UUID: UUID = UUID.fromString("00004860-0000-1000-8000-00805f9b34fb")

    // Core data characteristics
    val TX_CHAR_UUID: UUID = UUID.fromString("000071FF-0000-1000-8000-00805f9b34fb")  // phone→glasses
    val RX_CHAR_UUID: UUID = UUID.fromString("000070FF-0000-1000-8000-00805f9b34fb")  // glasses→phone

    // File transfer characteristics
    val FILE_READ_UUID: UUID = UUID.fromString("000072FF-0000-1000-8000-00805f9b34fb")   // BES→phone
    val FILE_WRITE_UUID: UUID = UUID.fromString("000073FF-0000-1000-8000-00805f9b34fb")  // phone→BES

    // LC3 audio characteristics (NUS-style UUIDs)
    val LC3_READ_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")   // glasses mic→phone
    val LC3_WRITE_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")  // phone→glasses speaker

    // Client Characteristic Configuration Descriptor
    val CCC_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Device name phone advertises as (glasses look for this)
    const val DEVICE_NAME = "Xy_A"

    // Device name patterns to scan for
    val SCAN_DEVICE_NAMES = listOf("Xy_A", "XyBLE_", "MENTRA_LIVE_BLE", "MENTRA_LIVE_BT", "mentra_live")

    // MTU settings
    const val DEFAULT_MTU = 23
    const val PREFERRED_MTU = 512
    const val BES_MAX_MTU = 256  // BES2700/2800 hardware limit

    // Connection/scan timeouts
    const val SCAN_TIMEOUT_MS = 60000L
    const val RECONNECT_SCAN_TIMEOUT_MS = 10000L
    const val CONNECTION_TIMEOUT_MS = 30000L
    const val PAIRING_RETRY_DELAY_MS = 1500L
    const val MAX_PAIRING_RETRIES = 3

    // Protocol timing
    const val MIN_SEND_DELAY_MS = 160L          // Rate limit between BLE writes
    const val READINESS_CHECK_INTERVAL_MS = 2500L // cs_hrt polling interval
    const val HEARTBEAT_INTERVAL_MS = 30000L     // Keep-alive ping
    const val CHUNK_SEND_DELAY_MS = 50L          // Delay between chunk sends
    const val AUTO_RECONNECT_DELAY_MS = 3000L    // Delay before reconnect attempt

    // K900 command strings
    const val CMD_MIC_STATE = "set_mic_state"
    const val CMD_MIC_VAD_STATE = "set_mic_vad_state"
    const val CMD_TAKE_PHOTO = "take_photo"
    const val CMD_LED_CONTROL = "set_led"
    const val CMD_GET_FIRMWARE = "hs_syvr"
    const val CMD_GET_BATTERY = "get_battery"
    const val CMD_HEARTBEAT_REQUEST = "cs_hrt"
    const val CMD_HEARTBEAT_RESPONSE = "sr_hrt"
    const val CMD_PHONE_READY = "phone_ready"
    const val CMD_GLASSES_READY = "glasses_ready"
    const val CMD_SET_BLE_MTU = "set_ble_mtu"
    const val CMD_PING = "ping"
    const val CMD_ENABLE_AUDIO_TX = "enable_custom_audio_tx"
}
