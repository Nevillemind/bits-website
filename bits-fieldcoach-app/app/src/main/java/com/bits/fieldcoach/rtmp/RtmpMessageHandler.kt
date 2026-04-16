package com.bits.fieldcoach.rtmp

import android.util.Log
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import java.io.ByteArrayOutputStream

/**
 * Handles RTMP protocol messages.
 * Responds to connect/createStream/publish, and forwards audio/video data via callbacks.
 */
class RtmpMessageHandler(
    private val onVideoData: (ByteArray) -> Unit,
    private val onAudioData: (ByteArray) -> Unit,
    private val onClientConnected: () -> Unit,
    private val onClientDisconnected: () -> Unit
) : SimpleChannelInboundHandler<RtmpMessage>() {

    companion object {
        private const val TAG = "RtmpMsgHandler"
    }

    private var nextStreamId = 1
    private var currentStreamId = 0
    private var bytesReceived = 0L
    private var windowAckSize = 2500000
    private var chunkSize = 128
    private var connected = false

    override fun channelActive(ctx: ChannelHandlerContext) {
        Log.d(TAG, "Client connected: ${ctx.channel().remoteAddress()}")
        onClientConnected()
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        Log.d(TAG, "Client disconnected: ${ctx.channel().remoteAddress()}")
        connected = false
        onClientDisconnected()
    }

    override fun channelRead0(ctx: ChannelHandlerContext, msg: RtmpMessage) {
        try {
            bytesReceived += msg.data.size

            // Send acknowledgement if needed
            if (bytesReceived >= windowAckSize) {
                sendAck(ctx, bytesReceived)
                bytesReceived = 0
            }

            when (msg.messageType) {
                RtmpMessage.TYPE_SET_CHUNK_SIZE -> {
                    chunkSize = readInt32(msg.data, 0)
                    Log.d(TAG, "Chunk size set to: $chunkSize")
                }
                RtmpMessage.TYPE_WINDOW_ACK_SIZE -> {
                    windowAckSize = readInt32(msg.data, 0)
                    Log.d(TAG, "Window ack size: $windowAckSize")
                }
                RtmpMessage.TYPE_COMMAND_AMF0 -> {
                    handleCommand(ctx, msg)
                }
                RtmpMessage.TYPE_VIDEO -> {
                    if (msg.data.isNotEmpty()) {
                        onVideoData(msg.data)
                    }
                }
                RtmpMessage.TYPE_AUDIO -> {
                    if (msg.data.isNotEmpty()) {
                        onAudioData(msg.data)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message type ${msg.messageType}", e)
        }
    }

    private fun handleCommand(ctx: ChannelHandlerContext, msg: RtmpMessage) {
        val values = decodeAmf0(msg.data)
        if (values.isEmpty()) return

        val command = values[0] as? String ?: return
        val transactionId = if (values.size > 1) (values[1] as? Number)?.toDouble() ?: 0.0 else 0.0

        Log.d(TAG, "Command: $command, txId: $transactionId")

        when (command) {
            "connect" -> handleConnect(ctx, transactionId, values)
            "createStream" -> handleCreateStream(ctx, transactionId)
            "publish" -> handlePublish(ctx, transactionId, values)
            "FCPublish", "releaseStream" -> {
                // Acknowledge these silently
                sendCommandResponse(ctx, msg.streamId, "onStatus", 0.0,
                    mapOf("level" to "status", "code" to "NetStream.Publish.Start", "description" to ""))
            }
            "deleteStream" -> {
                Log.d(TAG, "Stream deleted")
                connected = false
            }
        }
    }

    private fun handleConnect(ctx: ChannelHandlerContext, txId: Double, values: List<Any>) {
        Log.d(TAG, "RTMP connect from glasses")

        // Send Window Acknowledgement Size
        sendProtocolControl(ctx, RtmpMessage.TYPE_WINDOW_ACK_SIZE, writeInt32(2500000))

        // Send Set Peer Bandwidth
        val bandwidth = ByteArray(5)
        bandwidth[0] = 0x02 // dynamic
        writeInt32Into(bandwidth, 1, 2500000)
        sendProtocolControl(ctx, RtmpMessage.TYPE_SET_PEER_BANDWIDTH, bandwidth)

        // Send Set Chunk Size
        sendProtocolControl(ctx, RtmpMessage.TYPE_SET_CHUNK_SIZE, writeInt32(4096))

        // Send _result
        val response = ByteArrayOutputStream()
        encodeAmf0String(response, "_result")
        encodeAmf0Number(response, txId)
        // Properties object
        encodeAmf0Object(response, mapOf(
            "fmsVer" to "FMS/3,0,1,123",
            "capabilities" to 31.0
        ))
        // Information object
        encodeAmf0Object(response, mapOf(
            "level" to "status",
            "code" to "NetConnection.Connect.Success",
            "description" to "Connection succeeded.",
            "objectEncoding" to 0.0
        ))
        sendMessage(ctx, 3, RtmpMessage.TYPE_COMMAND_AMF0, 0, response.toByteArray())

        connected = true
        Log.d(TAG, "Connect response sent")
    }

    private fun handleCreateStream(ctx: ChannelHandlerContext, txId: Double) {
        currentStreamId = nextStreamId++
        Log.d(TAG, "Stream created: $currentStreamId")

        val response = ByteArrayOutputStream()
        encodeAmf0String(response, "_result")
        encodeAmf0Number(response, txId)
        encodeAmf0Null(response)
        encodeAmf0Number(response, currentStreamId.toDouble())
        sendMessage(ctx, 3, RtmpMessage.TYPE_COMMAND_AMF0, 0, response.toByteArray())
    }

    private fun handlePublish(ctx: ChannelHandlerContext, txId: Double, values: List<Any>) {
        val streamName = if (values.size > 3) values[3] as? String ?: "live" else "live"
        Log.d(TAG, "Publish started: $streamName")

        // Send onStatus(NetStream.Publish.Start)
        sendCommandResponse(ctx, currentStreamId, "onStatus", 0.0,
            mapOf(
                "level" to "status",
                "code" to "NetStream.Publish.Start",
                "description" to "$streamName is now published."
            )
        )
    }

    private fun sendProtocolControl(ctx: ChannelHandlerContext, type: Int, data: ByteArray) {
        sendMessage(ctx, 2, type, 0, data)
    }

    private fun sendCommandResponse(
        ctx: ChannelHandlerContext,
        streamId: Int,
        command: String,
        txId: Double,
        info: Map<String, Any>
    ) {
        val response = ByteArrayOutputStream()
        encodeAmf0String(response, command)
        encodeAmf0Number(response, txId)
        encodeAmf0Null(response)
        encodeAmf0Object(response, info)
        sendMessage(ctx, 5, RtmpMessage.TYPE_COMMAND_AMF0, streamId, response.toByteArray())
    }

    private fun sendMessage(ctx: ChannelHandlerContext, csid: Int, type: Int, streamId: Int, data: ByteArray) {
        val buf = ctx.alloc().buffer(12 + data.size)
        // Basic header: fmt=0, csid
        buf.writeByte(csid and 0x3F)
        // Message header (fmt 0): timestamp(3) + length(3) + type(1) + streamId(4 LE)
        buf.writeMedium(0) // timestamp
        buf.writeMedium(data.size)
        buf.writeByte(type)
        buf.writeByte(streamId and 0xFF)
        buf.writeByte((streamId shr 8) and 0xFF)
        buf.writeByte((streamId shr 16) and 0xFF)
        buf.writeByte((streamId shr 24) and 0xFF)
        buf.writeBytes(data)
        ctx.writeAndFlush(buf)
    }

    private fun sendAck(ctx: ChannelHandlerContext, bytesReceived: Long) {
        val data = ByteArray(4)
        writeInt32Into(data, 0, (bytesReceived and 0xFFFFFFFF).toInt())
        sendMessage(ctx, 2, RtmpMessage.TYPE_ACKNOWLEDGEMENT, 0, data)
    }

    // AMF0 helpers
    private fun decodeAmf0(data: ByteArray): List<Any> {
        val result = mutableListOf<Any>()
        var pos = 0
        while (pos < data.size) {
            val type = data[pos++].toInt() and 0xFF
            when (type) {
                0 -> { // Number
                    if (pos + 8 > data.size) break
                    val bits = java.lang.Long.reverseBytes(data.sliceArray(pos until pos + 8).let {
                        var l = 0L
                        for (b in it) l = (l shl 8) or (b.toLong() and 0xFF)
                        l
                    })
                    result.add(java.lang.Double.longBitsToDouble(bits))
                    pos += 8
                }
                2 -> { // String
                    if (pos + 2 > data.size) break
                    val len = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2
                    if (pos + len > data.size) break
                    result.add(String(data, pos, len))
                    pos += len
                }
                3 -> { // Object
                    val obj = mutableMapOf<String, Any>()
                    while (pos + 2 < data.size) {
                        val keyLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                        pos += 2
                        if (keyLen == 0 && pos < data.size && data[pos].toInt() == 0x09) {
                            pos++ // skip end marker
                            break
                        }
                        if (pos + keyLen > data.size) break
                        val key = String(data, pos, keyLen)
                        pos += keyLen
                        // Recursively decode value (simplified - only handles strings and numbers)
                        if (pos >= data.size) break
                        val valType = data[pos++].toInt() and 0xFF
                        when (valType) {
                            0 -> {
                                if (pos + 8 <= data.size) {
                                    val bits = java.lang.Long.reverseBytes(data.sliceArray(pos until pos + 8).let {
                                        var l = 0L; for (b in it) l = (l shl 8) or (b.toLong() and 0xFF); l
                                    })
                                    obj[key] = java.lang.Double.longBitsToDouble(bits)
                                    pos += 8
                                }
                            }
                            2 -> {
                                if (pos + 2 <= data.size) {
                                    val sLen = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                                    pos += 2
                                    if (pos + sLen <= data.size) {
                                        obj[key] = String(data, pos, sLen)
                                        pos += sLen
                                    }
                                }
                            }
                            5 -> { /* null */ }
                            1 -> { /* boolean */ pos++ }
                            else -> break
                        }
                    }
                    result.add(obj)
                }
                5 -> { // Null
                    result.add("null")
                }
                1 -> { // Boolean
                    if (pos < data.size) {
                        result.add(data[pos++].toInt() != 0)
                    }
                }
                else -> break
            }
        }
        return result
    }

    private fun encodeAmf0String(out: ByteArrayOutputStream, value: String) {
        out.write(2) // string marker
        out.write((value.length shr 8) and 0xFF)
        out.write(value.length and 0xFF)
        out.write(value.toByteArray())
    }

    private fun encodeAmf0Number(out: ByteArrayOutputStream, value: Double) {
        out.write(0) // number marker
        val bits = java.lang.Double.doubleToLongBits(value)
        for (i in 7 downTo 0) out.write((bits shr (i * 8)).toInt() and 0xFF)
    }

    private fun encodeAmf0Null(out: ByteArrayOutputStream) {
        out.write(5)
    }

    private fun encodeAmf0Object(out: ByteArrayOutputStream, props: Map<String, Any>) {
        out.write(3) // object marker
        for ((key, value) in props) {
            out.write((key.length shr 8) and 0xFF)
            out.write(key.length and 0xFF)
            out.write(key.toByteArray())
            when (value) {
                is String -> encodeAmf0String(out, value)
                is Double -> encodeAmf0Number(out, value)
                is Int -> encodeAmf0Number(out, value.toDouble())
                else -> encodeAmf0Null(out)
            }
        }
        out.write(0); out.write(0); out.write(0x09) // end marker
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }

    private fun writeInt32(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private fun writeInt32Into(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = ((value shr 24) and 0xFF).toByte()
        arr[offset + 1] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 3] = (value and 0xFF).toByte()
    }
}
