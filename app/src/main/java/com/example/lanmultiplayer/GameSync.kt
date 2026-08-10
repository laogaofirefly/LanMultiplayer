package com.example.lanmultiplayer

import kotlinx.coroutines.*
class GameSyncEngine(
    private val client: LanMultiplayer,
    private val adapter: GameAdapter,
    tickRate: Int = 30
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tickRate = tickRate.coerceIn(1, 120)
    private var frame = 0

    fun start() {
        scope.launch {
            client.reliableMessages.collect { adapter.onReliableMessage(it) }
        }
        scope.launch {
            client.realtimeMessages.collect {
                adapter.onRealtimeMessage(it)
                adapter.applySnapshot(it.payload)
            }
        }
        scope.launch {
            val intervalNanos = 1_000_000_000L / tickRate
            var nextTick = System.nanoTime()
            while (isActive) {
                val input = adapter.encodeInput()
                adapter.predictInput(input)
                client.sendRealtime(input, frame++)
                nextTick += intervalNanos
                // Monotonic time prevents clock changes from causing simulation bursts.
                delay(((nextTick - System.nanoTime()).coerceAtLeast(0L)) / 1_000_000L)
                if (System.nanoTime() - nextTick > intervalNanos * 4) nextTick = System.nanoTime()
            }
        }
    }

    fun stop() = scope.cancel()
}

/** 可选的本地预测扩展；不实现时默认忽略。 */
fun GameAdapter.predictInput(input: ByteArray) {}
