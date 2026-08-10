package com.example.lanmultiplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

internal interface FramedSession {
    suspend fun send(type: Byte, payload: ByteArray)
    suspend fun receive(): NetworkMessage
    fun close()
}

/** Length-framed stream protocol, optionally protected by TLS-PSK. */
class TcpSession private constructor(
    private val socket: Socket,
    inputStream: InputStream,
    outputStream: OutputStream,
    val isEncrypted: Boolean
) : FramedSession {
    private val input = BufferedInputStream(inputStream)
    private val output = BufferedOutputStream(outputStream)

    override suspend fun send(type: Byte, payload: ByteArray) = withContext(Dispatchers.IO) {
        require(payload.size <= 1024 * 1024)
        val body = ByteBuffer.allocate(1 + payload.size).put(type).put(payload).array()
        synchronized(output) {
            output.write(ByteBuffer.allocate(4).putInt(body.size).array())
            output.write(body)
            output.flush()
        }
    }

    override suspend fun receive(): NetworkMessage = withContext(Dispatchers.IO) {
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

    override fun close() = runCatching { socket.close() }

    companion object {
        fun fromSocket(socket: Socket): TcpSession = TcpSession(socket, socket.getInputStream(), socket.getOutputStream(), false)

        /** Executes the TLS-PSK server handshake before exposing any game frame. */
        fun fromTlsSocket(socket: Socket, roomToken: String): TcpSession {
            val streams = PskTls.server(socket.getInputStream(), socket.getOutputStream(), roomToken)
            return TcpSession(socket, streams.first, streams.second, true)
        }

        suspend fun connect(host: String, port: Int, timeoutMs: Int = 3_000, roomToken: String = ""): TcpSession = withContext(Dispatchers.IO) {
            require(port in 1..65535 && timeoutMs in 1_000..30_000)
            val socket = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = 256 * 1024
                sendBufferSize = 256 * 1024
                connect(InetSocketAddress(host, port), timeoutMs)
            }
            try {
                if (roomToken.isEmpty()) fromSocket(socket)
                else {
                    val streams = PskTls.client(socket.getInputStream(), socket.getOutputStream(), roomToken)
                    TcpSession(socket, streams.first, streams.second, true)
                }
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        }
    }
}