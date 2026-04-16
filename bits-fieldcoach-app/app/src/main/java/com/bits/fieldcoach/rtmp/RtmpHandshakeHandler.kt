package com.bits.fieldcoach.rtmp

import android.util.Log
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import java.nio.ByteBuffer

/**
 * Handles the RTMP handshake (C0/S0, C1/S1, C2/S2).
 * After handshake completes, passes raw bytes to the chunk decoder.
 */
class RtmpHandshakeHandler : ByteToMessageDecoder() {
    companion object {
        private const val TAG = "RtmpHandshake"
        private const val HANDSHAKE_SIZE = 1536
    }

    private enum class State { WAIT_C0, WAIT_C1, DONE }
    private var state = State.WAIT_C0
    private var clientTime: Int = 0
    private var clientVersion: Byte = 0

    override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
        when (state) {
            State.WAIT_C0 -> {
                if (buf.readableBytes() < 1) return
                clientVersion = buf.readByte()
                Log.d(TAG, "C0 received, version: $clientVersion")
                state = State.WAIT_C1
            }
            State.WAIT_C1 -> {
                if (buf.readableBytes() < HANDSHAKE_SIZE) return
                clientTime = buf.readInt()
                buf.skipBytes(4) // zero bytes
                val clientBytes = ByteArray(HANDSHAKE_SIZE - 8)
                buf.readBytes(clientBytes)
                Log.d(TAG, "C1 received, time: $clientTime")

                // Send S0 + S1 + S2
                val response = ctx.alloc().buffer(1 + HANDSHAKE_SIZE + HANDSHAKE_SIZE)
                // S0: version
                response.writeByte(3)
                // S1: time + zero + random bytes
                response.writeInt((System.currentTimeMillis() / 1000).toInt())
                response.writeInt(0)
                response.writeBytes(ByteArray(HANDSHAKE_SIZE - 8))
                // S2: echo back client time + zero + client random bytes
                response.writeInt(clientTime)
                response.writeInt(0)
                response.writeBytes(clientBytes)

                ctx.writeAndFlush(response)
                Log.d(TAG, "S0+S1+S2 sent")

                // Wait for C2
                state = State.DONE
            }
            State.DONE -> {
                if (buf.readableBytes() < HANDSHAKE_SIZE) return
                buf.skipBytes(HANDSHAKE_SIZE)
                Log.d(TAG, "C2 received, handshake complete")

                // Remove this handler, pass remaining bytes to next handler
                ctx.pipeline().remove(this)
                if (buf.readableBytes() > 0) {
                    out.add(buf.readBytes(buf.readableBytes()))
                }
            }
        }
    }
}
