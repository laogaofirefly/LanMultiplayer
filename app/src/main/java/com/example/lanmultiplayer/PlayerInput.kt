package com.example.lanmultiplayer

import java.nio.ByteBuffer

data class PlayerInput(
    val sequence: Int,
    val moveX: Short,
    val moveY: Short,
    val buttons: Int,
    val clientTimeMs: Int
) {
    fun encode(): ByteArray = ByteBuffer.allocate(16)
        .putInt(sequence).putShort(moveX).putShort(moveY).putInt(buttons).putInt(clientTimeMs).array()

    companion object {
        fun decode(data: ByteArray): PlayerInput? {
            if (data.size < 16) return null
            val b = ByteBuffer.wrap(data)
            return PlayerInput(b.int, b.short, b.short, b.int, b.int)
        }
    }
}