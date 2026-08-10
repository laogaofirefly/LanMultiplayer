package com.example.lanmultiplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class UdpSession(host: String, private val port: Int) {
    private val address = InetAddress.getByName(host)
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        sendBufferSize = 512 * 1024
        receiveBufferSize = 512 * 1024
        bind(null)
        // Kernel-side peer filtering drops spoofed/unrelated datagrams before Kotlin decoding.
        connect(address, port)
    }
    private val receiveBuffer = ByteArray(Protocol.MAX_PAYLOAD + 14)

    suspend fun send(type: Byte, sequence: Int, frame: Int, payload: ByteArray) = withContext(Dispatchers.IO) {
        val data = Protocol.encodeUdp(type, sequence, frame, payload)
        socket.send(DatagramPacket(data, data.size))
    }

    suspend fun receive(): UdpPacket? = withContext(Dispatchers.IO) {
        val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
        runCatching {
            socket.receive(packet)
            Protocol.decodeUdp(packet.data, packet.length)
        }.getOrNull()
    }

    fun close() = socket.close()
}