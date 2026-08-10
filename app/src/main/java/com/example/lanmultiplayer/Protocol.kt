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
    const val CHAT: Byte = 5
    const val RELIABLE: Byte = 10
    const val REALTIME: Byte = 11
    /** UDP reachability probe. The server echoes this packet only after a TCP-admitted player proves its id. */
    const val UDP_PROBE: Byte = 12
    const val UDP_PROBE_ACK: Byte = 13
    /** UDP-only session control. JOIN/WELCOME are retried with the same sequence until received. */
    const val UDP_JOIN: Byte = 14
    const val UDP_WELCOME: Byte = 15
    const val UDP_ACK: Byte = 16
    /** Payload is [playerId:int][application bytes]. ACK carries the corresponding sequence. */
    const val UDP_RELIABLE: Byte = 17
    const val UDP_CHAT: Byte = 18

    private const val UDP_HEADER_SIZE = 14

    fun encodeUdp(type: Byte, sequence: Int, frame: Int, payload: ByteArray): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "UDP payload too large" }
        return ByteBuffer.allocate(UDP_HEADER_SIZE + payload.size)
            .putShort(MAGIC).put(VERSION).put(type)
            .putInt(sequence).putInt(frame)
            .putShort(payload.size.toShort()).put(payload).array()
    }

    fun encodeUdpJoin(token: String, playerName: String): ByteArray {
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        val nameBytes = playerName.trim().take(32).toByteArray(Charsets.UTF_8)
        require(tokenBytes.size <= 128 && nameBytes.isNotEmpty() && nameBytes.size <= 32)
        return ByteBuffer.allocate(1 + tokenBytes.size + nameBytes.size)
            .put(tokenBytes.size.toByte()).put(tokenBytes).put(nameBytes).array()
    }

    fun decodeUdpJoin(payload: ByteArray): Pair<String, String>? = runCatching {
        if (payload.isEmpty()) return null
        val tokenSize = payload[0].toInt() and 0xff
        require(tokenSize <= 128 && payload.size > tokenSize + 1)
        val token = payload.copyOfRange(1, 1 + tokenSize).toString(Charsets.UTF_8)
        val name = payload.copyOfRange(1 + tokenSize, payload.size).toString(Charsets.UTF_8).trim().take(32)
        require(name.isNotEmpty())
        token to name
    }.getOrNull()

    fun decodeUdp(data: ByteArray, length: Int): UdpPacket? {
        if (length < UDP_HEADER_SIZE) return null
        val b = ByteBuffer.wrap(data, 0, length)
        if (b.short != MAGIC || b.get() != VERSION) return null
        val type = b.get()
        val sequence = b.int
        val frame = b.int
        val size = b.short.toInt() and 0xffff
        if (size > MAX_PAYLOAD || size != b.remaining()) return null
        return UdpPacket(type, sequence, frame, ByteArray(size).also(b::get))
    }
}

data class UdpPacket(
    val type: Byte,
    val sequence: Int,
    val frame: Int,
    val payload: ByteArray
)
