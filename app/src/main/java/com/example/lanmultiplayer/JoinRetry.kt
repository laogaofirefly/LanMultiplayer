package com.example.lanmultiplayer

import kotlinx.coroutines.delay

suspend fun LanMultiplayer.joinWithRetry(
    room: Room,
    playerName: String,
    policy: ReconnectPolicy = ReconnectPolicy()
): Boolean {
    repeat(policy.maxAttempts) { attempt ->
        if (join(room, playerName)) return true
        delay(policy.delayFor(attempt))
    }
    return false
}