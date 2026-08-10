package com.example.lanmultiplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpSession(host: String, private val port: Int) {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(null)
        sendBufferSize = 512 * 1024
        receiveBufferSize = 512 * 1024
    }
    private val address = InetAddress.getByName(host)

    suspend fun send(type: Byte, sequence: Int, frame: Int, payload: ByteArray) = withContext(Dispatchers.IO) {
        val data = Protocol.encodeUdp(type, sequence, frame, payload)
        socket.send(DatagramPacket(data, data.size, address, port))
    }

    suspend fun receive(): UdpPacket? = withContext(Dispatchers.IO) {
        val buffer = ByteArray(1400)
        val packet = DatagramPacket(buffer, buffer.size)
        runCatching {
            socket.receive(packet)
            Protocol.decodeUdp(packet.data, packet.length)
        }.getOrNull()
    }

    fun close() = socket.close()
}