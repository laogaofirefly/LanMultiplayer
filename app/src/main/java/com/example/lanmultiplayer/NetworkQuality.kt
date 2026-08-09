package com.example.lanmultiplayer

import kotlinx.coroutines.*
import java.nio.ByteBuffer

class NetworkQuality(
    private val client: LanMultiplayer,
    private val intervalMs: Long = 1000
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            while (isActive) {
                val timestamp = System.nanoTime()
                val payload = ByteBuffer.allocate(8).putLong(timestamp).array()
                client.sendReliable(payload)
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        scope.cancel()
    }
}
