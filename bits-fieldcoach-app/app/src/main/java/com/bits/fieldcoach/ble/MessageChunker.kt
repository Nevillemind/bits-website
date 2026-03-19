package com.bits.fieldcoach.ble

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

/**
 * Handles chunking of large messages exceeding BLE MTU limits.
 * Ported from MentraOS MessageChunker.java (MIT licensed).
 *
 * Messages are split at the JSON layer to work within MCU protocol constraints.
 */
object MessageChunker {
    private const val TAG = "MessageChunker"

    // Threshold for chunking — MTU ~512 - BLE overhead - MCU protocol - C-wrapper - margin
    const val MESSAGE_SIZE_THRESHOLD = 400

    // Max size for chunk data content (accounts for ~100 bytes wrapper overhead)
    const val CHUNK_DATA_SIZE = 300

    /**
     * Check if a message needs chunking.
     */
    fun needsChunking(message: String?): Boolean {
        if (message == null) return false
        val size = message.toByteArray().size
        return size > MESSAGE_SIZE_THRESHOLD
    }

    /**
     * Split a large JSON message into chunks.
     * Each chunk is a JSONObject with type="chunked_msg", chunkId, chunk, total, data.
     */
    @Throws(JSONException::class)
    fun createChunks(originalJson: String, messageId: Long = -1): List<JSONObject> {
        val chunks = mutableListOf<JSONObject>()
        val messageBytes = originalJson.toByteArray()
        val totalBytes = messageBytes.size

        val chunkId = "chunk_${messageId}_${System.currentTimeMillis()}"
        val totalChunks = Math.ceil(totalBytes.toDouble() / CHUNK_DATA_SIZE).toInt()

        Log.d(TAG, "Creating $totalChunks chunks for $totalBytes bytes")

        for (i in 0 until totalChunks) {
            val startIndex = i * CHUNK_DATA_SIZE
            val endIndex = minOf(startIndex + CHUNK_DATA_SIZE, totalBytes)
            val chunkData = String(messageBytes, startIndex, endIndex - startIndex)

            val chunk = JSONObject().apply {
                put("type", "chunked_msg")
                put("chunkId", chunkId)
                put("chunk", i)
                put("total", totalChunks)
                put("data", chunkData)
                if (i == totalChunks - 1 && messageId != -1L) {
                    put("mId", messageId)
                }
            }

            chunks.add(chunk)
        }

        return chunks
    }

    /**
     * Check if a received JSON is a chunked message.
     */
    fun isChunkedMessage(json: JSONObject): Boolean {
        return json.optString("type", "") == "chunked_msg"
    }

    /**
     * Extract chunk info from a chunked message.
     */
    @Throws(JSONException::class)
    fun getChunkInfo(json: JSONObject): ChunkInfo? {
        if (!isChunkedMessage(json)) return null
        return ChunkInfo(
            chunkId = json.getString("chunkId"),
            chunkIndex = json.getInt("chunk"),
            totalChunks = json.getInt("total"),
            data = json.getString("data"),
            messageId = json.optLong("mId", -1)
        )
    }

    data class ChunkInfo(
        val chunkId: String,
        val chunkIndex: Int,
        val totalChunks: Int,
        val data: String,
        val messageId: Long
    ) {
        fun isFinalChunk(): Boolean = chunkIndex == totalChunks - 1
    }

    /**
     * Reassembles chunks into a complete message.
     * Thread-safe accumulator for received chunks.
     */
    class ChunkAssembler {
        private val chunkBuffers = mutableMapOf<String, MutableMap<Int, String>>()
        private val chunkTotals = mutableMapOf<String, Int>()

        /**
         * Add a chunk. Returns the complete reassembled message if all chunks received, null otherwise.
         */
        @Synchronized
        fun addChunk(info: ChunkInfo): String? {
            val buffer = chunkBuffers.getOrPut(info.chunkId) { mutableMapOf() }
            chunkTotals[info.chunkId] = info.totalChunks
            buffer[info.chunkIndex] = info.data

            if (buffer.size == info.totalChunks) {
                // All chunks received — reassemble
                val sb = StringBuilder()
                for (i in 0 until info.totalChunks) {
                    sb.append(buffer[i] ?: "")
                }
                // Clean up
                chunkBuffers.remove(info.chunkId)
                chunkTotals.remove(info.chunkId)
                return sb.toString()
            }

            return null
        }

        @Synchronized
        fun clear() {
            chunkBuffers.clear()
            chunkTotals.clear()
        }
    }
}
