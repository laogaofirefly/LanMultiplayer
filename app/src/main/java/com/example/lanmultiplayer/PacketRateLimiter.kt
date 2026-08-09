package com.example.lanmultiplayer

import java.util.concurrent.atomic.AtomicLong

class PacketRateLimiter(
    private val maxPerSecond: Long = 120
) {
    private val second = AtomicLong(System.currentTimeMillis() / 1000)
    private val count = AtomicLong(0)

    fun allow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = nowMs / 1000
        if (current != second.get()) {
            second.set(current)
            count.set(0)
        }
        return count.incrementAndGet() <= maxPerSecond
    }
}