package com.example.lanmultiplayer

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class LockstepEngine(
    private val client: LanMultiplayer,
    private val adapter: GameAdapter,
    private val tickRate: Int = 30,
    private val inputTimeoutMs: Long = 300
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = ConcurrentHashMap<Int, MutableMap<Int, ByteArray>>()
    private var frame = 0
    private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch { receiveLoop() }
        scope.launch { tickLoop() }
    }

    private suspend fun receiveLoop() {
        client.realtimeMessages.collect { message ->
            if (message.payload.size < 8) return@collect
            val inputFrame = java.nio.ByteBuffer.wrap(message.payload).int
            val playerId = java.nio.ByteBuffer.wrap(message.payload, 4, 4).int
            val input = message.payload.copyOfRange(8, message.payload.size)
            frames.getOrPut(inputFrame) { ConcurrentHashMap() }[playerId] = input
        }
    }

    private suspend fun tickLoop() {
        val interval = 1000L / tickRate.coerceIn(1, 120)
        while (scope.isActive) {
            val local = adapter.encodeInput()
            val payload = java.nio.ByteBuffer.allocate(8 + local.size)
                .putInt(frame).putInt(0).put(local).array()
            client.sendRealtime(payload, frame)
            val inputs = frames.remove(frame)?.values?.toList() ?: emptyList()
            if (inputs.isNotEmpty()) adapter.simulateFrame(inputs)
            frame++
            delay(interval)
        }
    }

    fun stop() { running = false; scope.cancel() }
}