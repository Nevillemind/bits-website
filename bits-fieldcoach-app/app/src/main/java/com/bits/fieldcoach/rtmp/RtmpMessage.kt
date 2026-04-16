package com.bits.fieldcoach.rtmp

/**
 * Represents a decoded RTMP message.
 */
data class RtmpMessage(
    val csid: Int,
    val messageType: Int,
    val timestamp: Int,
    val streamId: Int,
    val data: ByteArray
) {
    companion object {
        // Protocol control messages (csid 2)
        const val TYPE_SET_CHUNK_SIZE = 1
        const val TYPE_ABORT = 2
        const val TYPE_ACKNOWLEDGEMENT = 3
        const val TYPE_WINDOW_ACK_SIZE = 5
        const val TYPE_SET_PEER_BANDWIDTH = 6

        // User control message
        const val TYPE_USER_CONTROL = 4

        // Command/data messages (csid 3+)
        const val TYPE_AUDIO = 8
        const val TYPE_VIDEO = 9
        const val TYPE_COMMAND_AMF0 = 20
        const val TYPE_COMMAND_AMF3 = 17
        const val TYPE_DATA_AMF0 = 18
        const val TYPE_DATA_AMF3 = 15
    }

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
