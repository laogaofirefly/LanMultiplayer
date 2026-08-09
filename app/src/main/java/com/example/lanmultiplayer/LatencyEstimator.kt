package com.example.lanmultiplayer

import kotlin.math.abs

class LatencyEstimator(private val alpha: Double = 0.15) {
    private var value = -1.0
    private var jitterValue = 0.0

    fun addSample(rttMs: Long): NetworkStats {
        if (rttMs < 0) return NetworkStats(value.toLong(), 0, 0)
        if (value < 0) value = rttMs.toDouble()
        else {
            val difference = abs(rttMs - value)
            jitterValue += alpha * (difference - jitterValue)
            value += alpha * (rttMs - value)
        }
        return NetworkStats(value.toLong(), 0, 0)
    }

    fun rttMs(): Long = value.toLong()
    fun jitterMs(): Long = jitterValue.toLong()
}