package com.example.lanmultiplayer

data class ReconnectPolicy(
    val maxAttempts: Int = 5,
    val initialDelayMs: Long = 500,
    val maxDelayMs: Long = 8_000,
    val backoff: Double = 2.0
) {
    init {
        require(maxAttempts >= 0)
        require(initialDelayMs >= 0 && maxDelayMs >= initialDelayMs)
        require(backoff >= 1.0)
    }

    fun delayFor(attempt: Int): Long {
        val value = initialDelayMs * Math.pow(backoff, attempt.coerceAtLeast(0).toDouble())
        return value.toLong().coerceAtMost(maxDelayMs)
    }
}
