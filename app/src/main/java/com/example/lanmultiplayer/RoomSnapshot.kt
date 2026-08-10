package com.example.lanmultiplayer

import java.util.concurrent.atomic.AtomicLong

/** Stable room epoch and snapshot contract used by future reconnect and host migration support. */
data class RoomSnapshot(
    val roomId: String,
    val epoch: Long,
    val revision: Long,
    val players: List<Player>,
    val state: ByteArray = byteArrayOf()
) {
    init {
        require(roomId.isNotBlank() && roomId.length <= 128)
        require(epoch >= 0 && revision >= 0)
        require(state.size <= 1024 * 1024)
    }
}

class RoomEpoch(roomId: String) {
    val roomId: String = roomId.also { require(it.isNotBlank() && it.length <= 128) }
    private val value = AtomicLong(0)
    fun current(): Long = value.get()
    fun advance(): Long = value.incrementAndGet()
}