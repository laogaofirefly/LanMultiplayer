package com.example.lanmultiplayer

import java.util.concurrent.ConcurrentHashMap

internal class AddressBanList(private val maxFailures: Int, private val durationMs: Long) {
    private data class Entry(var failures: Int, var until: Long)
    private val entries = ConcurrentHashMap<String, Entry>()

    fun isBanned(address: String, now: Long = System.currentTimeMillis()): Boolean =
        entries[address]?.let { it.until > now } == true

    fun failure(address: String, now: Long = System.currentTimeMillis()) {
        synchronized(entries) {
            val entry = entries[address] ?: Entry(0, 0).also { entries[address] = it }
            entry.failures++
            if (entry.failures >= maxFailures) entry.until = now + durationMs
        }
    }

    fun success(address: String) {
        entries.remove(address)
    }

    fun prune(now: Long = System.currentTimeMillis()) {
        for (entry in entries.entries) {
            if (entry.value.until != 0L && entry.value.until <= now) {
                entries.remove(entry.key, entry.value)
            }
        }
    }
}
