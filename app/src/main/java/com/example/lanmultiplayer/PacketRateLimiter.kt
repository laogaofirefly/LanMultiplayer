package com.example.lanmultiplayer

import java.util.concurrent.atomic.AtomicLong

class PacketRateLimiter(
    private val maxPerSecond: Long = 120
) {
    private val second = AtomicLong(System.currentTimeMillis() / 1000)
    private val count = AtomicLong(0)

    init { require(maxPerSecond > 0) }

    fun allow(nowMs: Long = System.currentTimeMillis()): Boolean {
        val current = nowMs / 1000
        while (true) {
            val old = second.get()
            if (current == old) break
            if (second.compareAndSet(old, current)) {
                count.set(0)
                break
            }
        }
        return count.incrementAndGet() <= maxPerSecond
    }
}