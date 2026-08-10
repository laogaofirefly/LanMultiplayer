package com.example.lanmultiplayer

import java.util.concurrent.ConcurrentHashMap

class SequenceWindow(private val maxEntries: Int = 4096) {
    private val latest = ConcurrentHashMap<Int, Int>()

    fun accept(playerId: Int, sequence: Int): Boolean {
        val old = latest[playerId]
        if (old != null && !NativeSequenceWindow.accepts(sequence, old)) return false
        latest[playerId] = sequence
        if (latest.size > maxEntries) latest.keys.take(latest.size - maxEntries).forEach(latest::remove)
        return true
    }

    fun clear() = latest.clear()
}