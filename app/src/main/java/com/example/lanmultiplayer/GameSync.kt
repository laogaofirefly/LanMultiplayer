package com.example.lanmultiplayer

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue

class GameSyncEngine(
    private val client: LanMultiplayer,
    private val adapter: GameAdapter,
    private val tickRate: Int = 30
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inputs = ConcurrentLinkedQueue<ByteArray>()
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
            val interval = 1000L / tickRate.coerceIn(1, 120)
            while (isActive) {
                val input = adapter.encodeInput()
                adapter.predictInput(input)
                client.sendRealtime(input, frame++)
                delay(interval)
            }
        }
    }

    fun stop() = scope.cancel()
}

/** 可选的本地预测扩展；不实现时默认忽略。 */
fun GameAdapter.predictInput(input: ByteArray) {}
