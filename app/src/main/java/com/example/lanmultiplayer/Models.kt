package com.example.lanmultiplayer

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

data class Room(
    val name: String,
    val host: String,
    val tcpPort: Int,
    val udpPort: Int,
    val gameId: String,
    val gameVersion: Int,
    val players: Int,
    val maxPlayers: Int,
    val mode: SyncMode
)

enum class SyncMode { RELIABLE, REALTIME_STATE, LOCKSTEP, CUSTOM }

data class RoomConfig(
    val name: String,
    val gameId: String,
    val gameVersion: Int = 1,
    val maxPlayers: Int = 8,
    val mode: SyncMode = SyncMode.REALTIME_STATE
)

data class NetworkMessage(val type: Byte, val payload: ByteArray)
data class NetworkStats(val rttMs: Long = -1, val sent: Long = 0, val received: Long = 0)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

interface GameAdapter {
    fun encodeInput(): ByteArray = ByteArray(0)
    fun onReliableMessage(message: NetworkMessage) {}
    fun onRealtimeMessage(message: NetworkMessage) {}
    fun applySnapshot(payload: ByteArray) {}
    fun simulateFrame(inputs: List<ByteArray>) {}
}

interface LanMultiplayer {
    val state: StateFlow<ConnectionState>
    val rooms: StateFlow<List<Room>>
    val stats: StateFlow<NetworkStats>
    val reliableMessages: Flow<NetworkMessage>
    val realtimeMessages: Flow<NetworkMessage>
    suspend fun startDiscovery()
    fun stopDiscovery()
    suspend fun join(room: Room, playerName: String): Boolean
    suspend fun sendReliable(payload: ByteArray)
    suspend fun sendRealtime(payload: ByteArray, frame: Int = 0)
    fun close()
}
