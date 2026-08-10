package com.example.lanmultiplayer

import java.util.concurrent.ConcurrentHashMap

/** Per-session sequence replay guard. UDP sequence numbers are isolated by session/player. */
class ReplayWindow(private val maxSources: Int = 256) {
    private val latest = ConcurrentHashMap<String, Int>()

    fun accept(source: String, sequence: Int): Boolean {
        val previous = latest[source]
        if (previous != null && !NativeSequenceWindow.accepts(sequence, previous)) return false
        latest[source] = sequence
        if (latest.size > maxSources) latest.keys.take(latest.size - maxSources).forEach(latest::remove)
        return true
    }

    fun clear() = latest.clear()
}