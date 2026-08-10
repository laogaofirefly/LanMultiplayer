package com.example.lanmultiplayer

import java.util.concurrent.ConcurrentHashMap

internal class AddressBanList(private val maxFailures: Int, private val durationMs: Long) {
    private data class Entry(var failures: Int, var until: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun isBanned(address: String, now: Long = System.currentTimeMillis()): Boolean =
        entries[address]?.let { it.until > now } == true

    fun failure(address: String, now: Long = System.currentTimeMillis()) {
        entries.compute(address) { _, old ->
            val entry = old ?: Entry(0, 0)
            entry.failures++
            if (entry.failures >= maxFailures) entry.until = now + durationMs
            entry
        }
    }

    fun success(address: String) { entries.remove(address) }
    fun prune(now: Long = System.currentTimeMillis()) { entries.entries.removeIf { it.value.until != 0L && it.value.until <= now } }
}
