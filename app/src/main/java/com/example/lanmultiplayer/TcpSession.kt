package com.example.lanmultiplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class TcpSession private constructor(private val socket: Socket) {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    suspend fun send(type: Byte, payload: ByteArray) = withContext(Dispatchers.IO) {
        require(payload.size <= 1024 * 1024)
        val body = ByteBuffer.allocate(1 + payload.size).put(type).put(payload).array()
        synchronized(output) {
            output.write(ByteBuffer.allocate(4).putInt(body.size).array())
            output.write(body)
            output.flush()
        }
    }

    suspend fun receive(): NetworkMessage = withContext(Dispatchers.IO) {
        val lengthBytes = ByteArray(4)
        readFully(lengthBytes)
        val length = ByteBuffer.wrap(lengthBytes).int
        require(length in 1..1024 * 1024)
        val body = ByteArray(length)
        readFully(body)
        NetworkMessage(body[0], body.copyOfRange(1, body.size))
    }

    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count <= 0) throw EOFException("TCP closed")
            offset += count
        }
    }

    fun close() = runCatching { socket.close() }

    companion object {
        fun fromSocket(socket: Socket): TcpSession = TcpSession(socket)

        suspend fun connect(host: String, port: Int): TcpSession = withContext(Dispatchers.IO) {
            Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = 256 * 1024
                sendBufferSize = 256 * 1024
                connect(InetSocketAddress(host, port), 3000)
            }.let(::TcpSession)
        }
    }
}