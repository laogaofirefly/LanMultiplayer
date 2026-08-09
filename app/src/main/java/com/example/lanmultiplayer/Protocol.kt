package com.example.lanmultiplayer

import java.nio.ByteBuffer

object Protocol {
    const val MAGIC: Short = 0x4C47 // LG
    const val VERSION: Byte = 1
    const val MAX_PAYLOAD = 1200

    const val HELLO: Byte = 1
    const val PING: Byte = 2
    const val PONG: Byte = 3
    const val PLAYER_LIST: Byte = 4
    const val RELIABLE: Byte = 10
    const val REALTIME: Byte = 11

    fun encodeUdp(type: Byte, sequence: Int, frame: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "UDP payload too large" }
        return ByteBuffer.allocate(2 + 1 + 1 + 4 + 4 + 2 + payload.size)
            .putShort(MAGIC).put(VERSION).put(type)
            .putInt(sequence).putInt(frame)
            .putShort(payload.size.toShort()).put(payload).array()
    }

    fun decodeUdp(data: ByteArray, length: Int): UdpPacket? {
        if (length < 18) return null
        val b = ByteBuffer.wrap(data, 0, length)
        if (b.short != MAGIC || b.get() != VERSION) return null
        val type = b.get()
        val sequence = b.int
        val frame = b.int
        val size = b.short.toInt() and 0xffff
        if (size > MAX_PAYLOAD || size > b.remaining()) return null
        return UdpPacket(type, sequence, frame, ByteArray(size).also(b::get))
    }
}

data class UdpPacket(
    val type: Byte,
    val sequence: Int,
    val frame: Int,
    val payload: ByteArray
)
