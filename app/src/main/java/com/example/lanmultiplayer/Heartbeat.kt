package com.example.lanmultiplayer

import kotlinx.coroutines.*
import java.nio.ByteBuffer

class Heartbeat(
    private val client: LanMultiplayer,
    private val intervalMs: Long = 1000,
    private val timeoutMs: Long = 5000
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var lastReceived = System.currentTimeMillis()

    fun markReceived() { lastReceived = System.currentTimeMillis() }

    fun start() {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                client.sendReliable(ByteBuffer.allocate(8).putLong(now).array())
                if (now - lastReceived > timeoutMs) break
                delay(intervalMs)
            }
        }
    }

    fun stop() { job?.cancel(); scope.cancel() }
}