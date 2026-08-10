package com.example.lanmultiplayer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Transport-independent reconnect orchestration. The caller supplies the complete join/resume action,
 * allowing the SDK to re-authenticate instead of reusing a stale socket.
 */
class ReconnectCoordinator(private val policy: ReconnectPolicy = ReconnectPolicy()) {
    suspend fun run(action: suspend (attempt: Int) -> Boolean): Boolean {
        for (attempt in 0..policy.maxAttempts) {
            try {
                if (action(attempt)) return true
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (_: Exception) {
                // Retryable transport/auth failures are intentionally handled by the caller's action.
            }
            if (attempt < policy.maxAttempts) delay(policy.delayFor(attempt))
        }
        return false
    }
}