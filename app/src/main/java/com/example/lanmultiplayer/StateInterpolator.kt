package com.example.lanmultiplayer

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class StateInterpolator<T>(
    private val delayMs: Long = 80,
    private val timestamp: (T) -> Long,
    private val interpolate: (T, T, Float) -> T
) {
    private val states = ConcurrentHashMap<Long, T>()

    fun add(state: T) {
        states[timestamp(state)] = state
        val cutoff = timestamp(state) - 2_000
        states.keys.removeIf { it < cutoff }
    }

    fun sample(nowMs: Long = System.currentTimeMillis()): T? {
        val target = nowMs - delayMs
        val ordered = states.entries.sortedBy { it.key }
        if (ordered.isEmpty()) return null
        val before = ordered.lastOrNull { it.key <= target } ?: return ordered.first().value
        val after = ordered.firstOrNull { it.key >= target } ?: return before.value
        if (before.key == after.key) return before.value
        val amount = ((target - before.key).toFloat() / (after.key - before.key)).coerceIn(0f, 1f)
        return interpolate(before.value, after.value, amount)
    }

    fun clear() = states.clear()
}