package com.bits.fieldcoach.rtmp

import android.util.Log
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.net.InetSocketAddress

/**
 * Minimal RTMP server that runs on the phone's hotspot network.
 * Receives a single RTMP stream from glasses, extracts H.264 video + AAC audio,
 * and forwards via callback to StreamRelay.
 */
class RtmpServer(
    private val onVideoData: (ByteArray) -> Unit,
    private val onAudioData: (ByteArray) -> Unit,
    private val onClientConnected: () -> Unit,
    private val onClientDisconnected: () -> Unit
) {
    companion object {
        private const val TAG = "RtmpServer"
    }

    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var channel: Channel? = null
    private var isRunning = false

    fun start(bindAddress: String, port: Int = 1935): Boolean {
        if (isRunning) {
            Log.w(TAG, "RTMP server already running")
            return true
        }

        return try {
            bossGroup = NioEventLoopGroup(1)
            workerGroup = NioEventLoopGroup(1)

            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast(RtmpHandshakeHandler())
                        ch.pipeline().addLast(RtmpChunkDecoder())
                        ch.pipeline().addLast(RtmpMessageHandler(
                            onVideoData = onVideoData,
                            onAudioData = onAudioData,
                            onClientConnected = onClientConnected,
                            onClientDisconnected = onClientDisconnected
                        ))
                    }
                })

            val future = bootstrap.bind(InetSocketAddress(bindAddress, port)).sync()
            channel = future.channel()
            isRunning = true
            Log.i(TAG, "RTMP server started on $bindAddress:$port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTMP server", e)
            stop()
            false
        }
    }

    fun stop() {
        isRunning = false
        channel?.close()?.syncUninterruptibly()
        channel = null
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
        workerGroup = null
        bossGroup = null
        Log.i(TAG, "RTMP server stopped")
    }

    fun isRunning(): Boolean = isRunning
}
