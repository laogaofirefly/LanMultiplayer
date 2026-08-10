package com.example.lanmultiplayer

import java.net.IDN

/** Public entry point for joining a room exposed through a tunnel, public IP, or DNS name. */
data class ExternalRoomEndpoint(
    val host: String,
    val tcpPort: Int,
    val udpPort: Int = tcpPort,
    val gameId: String,
    val gameVersion: Int = 1,
    val mode: SyncMode = SyncMode.REALTIME_STATE,
    /** Per-attempt TCP connection timeout. Tunnels may require longer than a LAN connection. */
    val connectTimeoutMs: Int = 8_000,
    /** Retry count for transient tunnel/DNS failures. Total attempts include the first attempt. */
    val maxConnectAttempts: Int = 3
) {
    init {
        require(host.isNotBlank() && host.length <= 253) { "Invalid host" }
        require(tcpPort in 1..65535 && udpPort in 1..65535) { "Ports must be in 1..65535" }
        require(gameId.isNotBlank() && gameId.length <= 64) { "Invalid gameId" }
        require(connectTimeoutMs in 1_000..30_000) { "connectTimeoutMs must be in 1000..30000" }
        require(maxConnectAttempts in 1..5) { "maxConnectAttempts must be in 1..5" }
    }

    /** Normalizes international DNS names without resolving or contacting the host. */
    fun normalized(): ExternalRoomEndpoint = copy(host = IDN.toASCII(host.trim().removeSurrounding("[", "]")))

    internal fun toRoom(): Room = Room(
        name = host, host = normalized().host, tcpPort = tcpPort, udpPort = udpPort,
        gameId = gameId, gameVersion = gameVersion, players = 0, maxPlayers = 0, mode = mode
    )
}

interface ExternalMultiplayerApi {
    /**
     * Joins an externally reachable room. Use TLS/VPN/tunnel transport for untrusted networks:
     * this SDK's raw TCP/UDP transport does not encrypt game payloads.
     */
    suspend fun joinExternal(endpoint: ExternalRoomEndpoint, playerName: String): Boolean
}
