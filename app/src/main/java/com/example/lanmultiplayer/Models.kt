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
    val mode: SyncMode,
    /** Enables TLS-PSK for TCP. Token-protected rooms intentionally use TCP only until encrypted UDP is wired in. */
    val roomToken: String = ""
)

enum class SyncMode { RELIABLE, REALTIME_STATE, LOCKSTEP, CUSTOM }

data class RoomConfig(
    val name: String,
    val gameId: String,
    val gameVersion: Int = 1,
    val maxPlayers: Int = 8,
    val mode: SyncMode = SyncMode.REALTIME_STATE,
    /** Empty is allowed only for explicitly trusted LAN rooms. */
    val roomToken: String = "",
    val security: RoomSecurityPolicy = RoomSecurityPolicy(
        mode = if (roomToken.isEmpty()) SecurityMode.TRUSTED_LAN_INSECURE else SecurityMode.SECURE,
        maxConnections = maxPlayers
    ),
    val gameRuleValidator: GameRuleValidator = DefaultGameRuleValidator
) {
    init {
        require(name.isNotBlank() && name.length <= 64 && name.none { it.isISOControl() }) { "Invalid room name" }
        require(gameId.isNotBlank() && gameId.length <= 64 && gameId.none { it.isISOControl() }) { "Invalid gameId" }
        require(gameVersion > 0) { "gameVersion must be positive" }
        require(maxPlayers in 1..64) { "maxPlayers must be in 1..64" }
        require(roomToken.length <= 128 && roomToken.none { it.isISOControl() })
        if (security.mode == SecurityMode.SECURE) SessionSecurity.validateToken(roomToken)
        require((security.mode == SecurityMode.SECURE) == roomToken.isNotEmpty()) { "Secure mode requires a roomToken; insecure mode must not carry one" }
        require(security.maxConnections >= maxPlayers) { "security.maxConnections must cover maxPlayers" }
    }
}

data class NetworkMessage(val type: Byte, val payload: ByteArray)

/**
 * Transport telemetry. TCP and UDP counters are separate because a healthy TCP
 * control channel does not imply that the UDP realtime path is available.
 */
data class NetworkStats(
    val rttMs: Long = -1,
    val sent: Long = 0,
    val received: Long = 0,
    val udpSent: Long = 0,
    val udpReceived: Long = 0,
    /** Estimated missing UDP sequence numbers. This is an estimate, not a wire-level ACK result. */
    val udpEstimatedLost: Long = 0
) {
    val udpLossPercent: Double
        get() {
            val total = udpReceived + udpEstimatedLost
            return if (total == 0L) 0.0 else udpEstimatedLost * 100.0 / total
        }
}

/** Explicit room admission policy. Account verification requires an application backend. */
data class RoomAccessPolicy(
    val requiresPassword: Boolean = false,
    val requiresAuthenticatedIdentity: Boolean = false,
    val allowGuests: Boolean = true
) {
    init { require(!requiresAuthenticatedIdentity || !allowGuests) }
}

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
