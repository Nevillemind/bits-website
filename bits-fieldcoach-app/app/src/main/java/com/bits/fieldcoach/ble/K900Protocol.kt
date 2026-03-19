package com.bits.fieldcoach.ble

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * K900 BES2700/2800 binary protocol for Mentra Live glasses.
 * Ported from MentraOS K900ProtocolUtils.java (MIT licensed).
 *
 * Protocol format: ## + cmd_type(1) + length(2) + data + $$
 * Phone→device uses little-endian length.
 * Device→phone uses big-endian length.
 */
object K900Protocol {
    private const val TAG = "K900Protocol"

    // Protocol markers
    const val START_BYTE: Byte = 0x23  // '#'
    const val END_BYTE: Byte = 0x24    // '$'
    val CMD_START = byteArrayOf(START_BYTE, START_BYTE)  // ##
    val CMD_END = byteArrayOf(END_BYTE, END_BYTE)        // $$

    // Command types
    const val CMD_TYPE_STRING: Byte = 0x30
    const val CMD_TYPE_PHOTO: Byte = 0x31
    const val CMD_TYPE_VIDEO: Byte = 0x32
    const val CMD_TYPE_MUSIC: Byte = 0x33
    const val CMD_TYPE_AUDIO: Byte = 0x34
    const val CMD_TYPE_DATA: Byte = 0x35

    // File transfer constants
    const val FILE_PACK_SIZE = 400
    const val LENGTH_FILE_START = 2
    const val LENGTH_FILE_TYPE = 1
    const val LENGTH_FILE_PACKSIZE = 2
    const val LENGTH_FILE_PACKINDEX = 2
    const val LENGTH_FILE_SIZE = 4
    const val LENGTH_FILE_NAME = 16
    const val LENGTH_FILE_FLAG = 2
    const val LENGTH_FILE_VERIFY = 1
    const val LENGTH_FILE_END = 2

    // Minimum file packet header size (before data)
    const val FILE_HEADER_SIZE = LENGTH_FILE_START + LENGTH_FILE_TYPE + LENGTH_FILE_PACKSIZE +
            LENGTH_FILE_PACKINDEX + LENGTH_FILE_SIZE + LENGTH_FILE_NAME + LENGTH_FILE_FLAG

    // JSON field constants
    const val FIELD_C = "C"
    const val FIELD_V = "V"
    const val FIELD_B = "B"

    // LC3 audio packet header
    const val LC3_HEADER: Byte = 0xF1.toByte()
    const val LC3_FRAME_SIZE = 40

    /**
     * Pack JSON string for phone→K900 device communication.
     * 1. Wrap with C-field: {"C": jsonData}
     * 2. Pack with protocol: ## + type + length(LE) + data + $$
     */
    fun packJsonToK900(jsonData: String, wakeup: Boolean = false): ByteArray? {
        if (jsonData.isEmpty()) return null

        return try {
            val wrapper = JSONObject()
            wrapper.put(FIELD_C, jsonData)
            if (wakeup) {
                wrapper.put("W", 1)
            }
            val wrappedJson = wrapper.toString()
            val jsonBytes = wrappedJson.toByteArray(StandardCharsets.UTF_8)
            packDataToK900(jsonBytes, CMD_TYPE_STRING)
        } catch (e: JSONException) {
            Log.e(TAG, "Error packing JSON for K900", e)
            null
        }
    }

    /**
     * Pack raw data with K900 protocol for phone→device (little-endian length).
     * Format: ## + cmd_type + length(2, LE) + data + $$
     */
    fun packDataToK900(data: ByteArray, cmdType: Byte): ByteArray {
        val dataLength = data.size
        val result = ByteArray(dataLength + 7)

        result[0] = START_BYTE
        result[1] = START_BYTE
        result[2] = cmdType
        // Little-endian length for phone→device
        result[3] = (dataLength and 0xFF).toByte()
        result[4] = ((dataLength shr 8) and 0xFF).toByte()
        System.arraycopy(data, 0, result, 5, dataLength)
        result[5 + dataLength] = END_BYTE
        result[6 + dataLength] = END_BYTE

        return result
    }

    /**
     * Pack raw data with big-endian length (general protocol format).
     * Format: ## + cmd_type + length(2, BE) + data + $$
     */
    fun packDataCommand(data: ByteArray, cmdType: Byte): ByteArray {
        val dataLength = data.size
        val result = ByteArray(dataLength + 7)

        result[0] = START_BYTE
        result[1] = START_BYTE
        result[2] = cmdType
        // Big-endian length
        result[3] = ((dataLength shr 8) and 0xFF).toByte()
        result[4] = (dataLength and 0xFF).toByte()
        System.arraycopy(data, 0, result, 5, dataLength)
        result[5 + dataLength] = END_BYTE
        result[6 + dataLength] = END_BYTE

        return result
    }

    /**
     * Check if data follows K900 protocol format (starts with ##).
     */
    fun isK900Format(data: ByteArray): Boolean {
        return data.size >= 7 && data[0] == START_BYTE && data[1] == START_BYTE
    }

    /**
     * Check if byte is LC3 audio header (0xF1).
     */
    fun isLc3AudioPacket(data: ByteArray): Boolean {
        return data.isNotEmpty() && data[0] == LC3_HEADER
    }

    /**
     * Extract payload from device→phone data (big-endian length).
     */
    fun extractPayload(protocolData: ByteArray): ByteArray? {
        if (!isK900Format(protocolData)) return null
        // Big-endian length
        val length = ((protocolData[3].toInt() and 0xFF) shl 8) or (protocolData[4].toInt() and 0xFF)
        if (length + 7 > protocolData.size) return null
        return protocolData.copyOfRange(5, 5 + length)
    }

    /**
     * Extract payload from K900 device data (little-endian length).
     */
    fun extractPayloadFromK900(protocolData: ByteArray): ByteArray? {
        if (!isK900Format(protocolData)) return null
        // Little-endian length
        val length = (protocolData[3].toInt() and 0xFF) or ((protocolData[4].toInt() and 0xFF) shl 8)
        if (length + 7 > protocolData.size) return null
        return protocolData.copyOfRange(5, 5 + length)
    }

    /**
     * Process received bytes from glasses into a JSON object.
     * Handles K900 protocol detection, extraction, and C-field unwrapping.
     */
    fun processReceivedBytesToJson(data: ByteArray): JSONObject? {
        if (data.size < 7) return null
        if (!isK900Format(data)) return null

        val commandType = data[2]
        // Big-endian payload length (device→phone)
        val payloadLength = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)

        if (commandType != CMD_TYPE_STRING) return null
        if (data.size < payloadLength + 7) return null

        // Verify end markers
        if (data[5 + payloadLength] != END_BYTE || data[6 + payloadLength] != END_BYTE) return null

        val payload = data.copyOfRange(5, 5 + payloadLength)
        val payloadStr = try {
            String(payload, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            return null
        }

        if (!payloadStr.startsWith("{") || !payloadStr.endsWith("}")) return null

        return try {
            val json = JSONObject(payloadStr)
            if (json.has(FIELD_C)) {
                val inner = json.optString(FIELD_C, "")
                try {
                    JSONObject(inner)
                } catch (e: JSONException) {
                    json
                }
            } else {
                json
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Error parsing JSON payload", e)
            null
        }
    }

    /**
     * Prepare data for transmission — handles already-formatted data, JSON, and raw bytes.
     */
    fun prepareForTransmission(data: ByteArray): ByteArray {
        if (isK900Format(data)) return data

        val str = try { String(data, StandardCharsets.UTF_8) } catch (e: Exception) { null }
        if (str != null && str.startsWith("{")) {
            return try {
                JSONObject(str) // validate JSON
                val wrapper = JSONObject()
                wrapper.put(FIELD_C, str)
                wrapper.put(FIELD_V, 1)
                wrapper.put(FIELD_B, JSONObject())
                packDataCommand(wrapper.toString().toByteArray(StandardCharsets.UTF_8), CMD_TYPE_STRING)
            } catch (e: JSONException) {
                packDataCommand(data, CMD_TYPE_STRING)
            }
        }

        return packDataCommand(data, CMD_TYPE_STRING)
    }

    // -----------------------------------------------------------------------
    // File packet handling
    // -----------------------------------------------------------------------

    data class FilePacketInfo(
        val fileType: Byte = 0,
        val packSize: Int = 0,
        val packIndex: Int = 0,
        val fileSize: Int = 0,
        val fileName: String = "",
        val flags: Int = 0,
        val data: ByteArray = ByteArray(0),
        val verifyCode: Byte = 0,
        val isValid: Boolean = false
    )

    /**
     * Extract file packet information from received K900 protocol data.
     */
    fun extractFilePacket(protocolData: ByteArray): FilePacketInfo? {
        if (!isK900Format(protocolData) || protocolData.size < 31) return null

        var pos = LENGTH_FILE_START

        val fileType = protocolData[pos]
        pos += LENGTH_FILE_TYPE

        // Pack size (big-endian)
        val packSize = ((protocolData[pos].toInt() and 0xFF) shl 8) or
                (protocolData[pos + 1].toInt() and 0xFF)
        pos += LENGTH_FILE_PACKSIZE

        // Pack index (big-endian)
        val packIndex = ((protocolData[pos].toInt() and 0xFF) shl 8) or
                (protocolData[pos + 1].toInt() and 0xFF)
        pos += LENGTH_FILE_PACKINDEX

        // File size (big-endian, 4 bytes)
        val fileSize = ((protocolData[pos].toInt() and 0xFF) shl 24) or
                ((protocolData[pos + 1].toInt() and 0xFF) shl 16) or
                ((protocolData[pos + 2].toInt() and 0xFF) shl 8) or
                (protocolData[pos + 3].toInt() and 0xFF)
        pos += LENGTH_FILE_SIZE

        // File name (16 bytes, null-padded)
        val nameBytes = protocolData.copyOfRange(pos, pos + LENGTH_FILE_NAME)
        var nameLen = 0
        for (i in 0 until LENGTH_FILE_NAME) {
            if (nameBytes[i] == 0.toByte()) break
            nameLen++
        }
        val fileName = String(nameBytes, 0, nameLen, StandardCharsets.UTF_8)
        pos += LENGTH_FILE_NAME

        // Flags (big-endian)
        val flags = ((protocolData[pos].toInt() and 0xFF) shl 8) or
                (protocolData[pos + 1].toInt() and 0xFF)
        pos += LENGTH_FILE_FLAG

        // Check enough data
        if (protocolData.size < pos + packSize + LENGTH_FILE_VERIFY + LENGTH_FILE_END) {
            Log.e(TAG, "File packet too short: need ${pos + packSize + LENGTH_FILE_VERIFY + LENGTH_FILE_END}, have ${protocolData.size}")
            return null
        }

        // Data
        val data = protocolData.copyOfRange(pos, pos + packSize)
        pos += packSize

        // Verify code
        val verifyCode = protocolData[pos]
        pos += LENGTH_FILE_VERIFY

        // Check end markers
        if (protocolData[pos] != END_BYTE || protocolData[pos + 1] != END_BYTE) return null

        // Calculate and verify checksum
        var checkSum = 0
        for (b in data) {
            checkSum += (b.toInt() and 0xFF)
        }
        val calculatedVerify = (checkSum and 0xFF).toByte()
        val isValid = calculatedVerify == verifyCode

        if (!isValid) {
            Log.e(TAG, "File packet checksum failed: expected ${String.format("%02X", verifyCode)}, got ${String.format("%02X", calculatedVerify)}")
        }

        return FilePacketInfo(
            fileType = fileType,
            packSize = packSize,
            packIndex = packIndex,
            fileSize = fileSize,
            fileName = fileName,
            flags = flags,
            data = data,
            verifyCode = verifyCode,
            isValid = isValid
        )
    }

    /**
     * Create file transfer acknowledgment.
     */
    fun createFileTransferAck(state: Int, index: Int): String? {
        return try {
            val body = JSONObject().apply {
                put("state", state)
                put("index", index)
            }
            JSONObject().apply {
                put("C", "cs_flts")
                put("B", body)
            }.toString()
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Extract complete file packets from a buffer that may contain MTU fragments.
     * Returns list of complete packets and the remaining unprocessed bytes.
     */
    fun extractCompleteFilePackets(buffer: ByteArray): Pair<List<ByteArray>, ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var offset = 0

        while (offset < buffer.size - 1) {
            // Look for ## start marker
            if (buffer[offset] != START_BYTE || buffer[offset + 1] != START_BYTE) {
                offset++
                continue
            }

            // Need at least header to determine packet size
            if (buffer.size - offset < FILE_HEADER_SIZE) break

            // Read packSize from header (offset+3, offset+4, big-endian)
            val packSize = ((buffer[offset + 3].toInt() and 0xFF) shl 8) or
                    (buffer[offset + 4].toInt() and 0xFF)

            val totalPacketSize = FILE_HEADER_SIZE + packSize + LENGTH_FILE_VERIFY + LENGTH_FILE_END
            if (buffer.size - offset < totalPacketSize) break

            // Verify end markers
            val endPos = offset + totalPacketSize - LENGTH_FILE_END
            if (buffer[endPos] == END_BYTE && buffer[endPos + 1] == END_BYTE) {
                packets.add(buffer.copyOfRange(offset, offset + totalPacketSize))
                offset += totalPacketSize
            } else {
                offset++
            }
        }

        val remaining = if (offset < buffer.size) buffer.copyOfRange(offset, buffer.size) else ByteArray(0)
        return Pair(packets, remaining)
    }
}
