package com.bits.fieldcoach.rtmp

import android.util.Log
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder

/**
 * Decodes RTMP chunks into messages.
 * RTMP protocol: Basic Header (1-3 bytes) + Message Header (0/3/7/11 bytes) + Extended Timestamp (0/4 bytes) + Chunk Data
 * 
 * We only need to decode type 0 and type 1 messages for a single incoming stream.
 */
class RtmpChunkDecoder : ByteToMessageDecoder() {
    companion object {
        private const val TAG = "RtmpChunkDecoder"
    }

    private var chunkSize = 128
    private val chunkStreams = mutableMapOf<Int, ChunkState>()

    data class ChunkState(
        var fmt: Int = 0,
        var csid: Int = 0,
        var timestamp: Int = 0,
        var timestampDelta: Int = 0,
        var messageLength: Int = 0,
        var messageType: Int = 0,
        var messageStreamId: Int = 0,
        var data: ByteArray = ByteArray(0),
        var bytesRead: Int = 0
    )

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        try {
            while (buf.readableBytes() > 0) {
                buf.markReaderIndex()

                // Read basic header
                val firstByte = buf.readByte().toInt() and 0xFF
                val fmt = (firstByte shr 6) and 0x03
                var csid = firstByte and 0x3F

                if (csid == 0) {
                    if (buf.readableBytes() < 1) { buf.resetReaderIndex(); return }
                    csid = (buf.readByte().toInt() and 0xFF) + 64
                } else if (csid == 1) {
                    if (buf.readableBytes() < 2) { buf.resetReaderIndex(); return }
                    csid = (buf.readByte().toInt() and 0xFF) +
                            ((buf.readByte().toInt() and 0xFF) shl 8) + 64
                }

                // Get or create chunk state
                val state = chunkStreams.getOrPut(csid) { ChunkState(csid = csid) }
                state.fmt = fmt

                // Read message header based on fmt type
                when (fmt) {
                    0 -> { // Full header
                        if (buf.readableBytes() < 11) { buf.resetReaderIndex(); return }
                        state.timestamp = buf.readMedium()
                        state.messageLength = buf.readMedium()
                        state.messageType = buf.readByte().toInt() and 0xFF
                        state.messageStreamId = buf.readByte().toInt() and 0xFF or
                                ((buf.readByte().toInt() and 0xFF) shl 8) or
                                ((buf.readByte().toInt() and 0xFF) shl 16) or
                                ((buf.readByte().toInt() and 0xFF) shl 24)
                        state.data = ByteArray(state.messageLength)
                        state.bytesRead = 0
                    }
                    1 -> { // Message header without message stream ID
                        if (buf.readableBytes() < 7) { buf.resetReaderIndex(); return }
                        state.timestampDelta = buf.readMedium()
                        state.messageLength = buf.readMedium()
                        state.messageType = buf.readByte().toInt() and 0xFF
                        state.timestamp += state.timestampDelta
                        state.data = ByteArray(state.messageLength)
                        state.bytesRead = 0
                    }
                    2 -> { // Timestamp only
                        if (buf.readableBytes() < 3) { buf.resetReaderIndex(); return }
                        state.timestampDelta = buf.readMedium()
                        state.timestamp += state.timestampDelta
                        state.data = ByteArray(state.messageLength)
                        state.bytesRead = 0
                    }
                    3 -> { // No header - continuation
                        // data and bytesRead persist from previous chunk
                    }
                }

                // Calculate how many bytes to read for this chunk
                val remaining = state.messageLength - state.bytesRead
                val toRead = minOf(remaining, chunkSize, buf.readableBytes())

                if (buf.readableBytes() < toRead) {
                    buf.resetReaderIndex()
                    return
                }

                buf.readBytes(state.data, state.bytesRead, toRead)
                state.bytesRead += toRead

                // Check if complete message received
                if (state.bytesRead >= state.messageLength) {
                    out.add(RtmpMessage(
                        csid = csid,
                        messageType = state.messageType,
                        timestamp = state.timestamp,
                        streamId = state.messageStreamId,
                        data = state.data.copyOf()
                    ))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding chunk", e)
        }
    }
}
