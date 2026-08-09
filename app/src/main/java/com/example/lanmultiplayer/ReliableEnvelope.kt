package com.example.lanmultiplayer

import java.nio.ByteBuffer

object ReliableEnvelope {
    fun encode(sequence: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size).putInt(sequence).put(payload).array()

    fun decode(payload: ByteArray): Pair<Int, ByteArray>? {
        if (payload.size < 4) return null
        val b = ByteBuffer.wrap(payload)
        return b.int to ByteArray(b.remaining()).also(b::get)
    }
}