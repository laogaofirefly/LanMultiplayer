package com.example.lanmultiplayer

import java.nio.ByteBuffer

object SnapshotCodec {
    fun encode(frame: Int, serverTimeMs: Long, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + 8 + 2 + payload.size)
            .putInt(frame).putLong(serverTimeMs).putShort(payload.size.toShort()).put(payload).array()

    fun decode(data: ByteArray): Snapshot? {
        if (data.size < 14) return null
        val b = ByteBuffer.wrap(data)
        val frame = b.int
        val time = b.long
        val size = b.short.toInt() and 0xffff
        if (size > b.remaining()) return null
        return Snapshot(frame, time, ByteArray(size).also(b::get))
    }
}

data class Snapshot(val frame: Int, val serverTimeMs: Long, val payload: ByteArray)