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
    val maxConnectAttempts: Int = 3,
    /** Short-lived 16+ character secret that enables mandatory TLS-PSK TCP transport. */
    val roomToken: String = "",
    /** Reserved for a future certificate-authenticated endpoint; TLS-PSK does not use a certificate pin. */
    val tlsCertificateSha256: String = ""
) {
    init {
        require(host.isNotBlank() && host.length <= 253) { "Invalid host" }
        require((tcpPort in 1..65535 || udpPort in 1..65535) && tcpPort in 0..65535 && udpPort in 0..65535) { "At least one port must be in 1..65535" }
        require(gameId.isNotBlank() && gameId.length <= 64) { "Invalid gameId" }
        require(roomToken.length <= 128 && roomToken.none { it.isISOControl() }) { "Invalid roomToken" }
        require(roomToken.isNotEmpty()) { "External rooms require a roomToken; use trusted LAN APIs for insecure transport" }
        require(roomToken.length >= 16) { "TLS-PSK roomToken must be at least 16 characters" }
        require(connectTimeoutMs in 1_000..30_000) { "connectTimeoutMs must be in 1000..30000" }
        require(maxConnectAttempts in 1..5) { "maxConnectAttempts must be in 1..5" }
    }

    /** Normalizes international DNS names without resolving or contacting the host. */
    fun normalized(): ExternalRoomEndpoint = copy(host = IDN.toASCII(host.trim().removeSurrounding("[", "]")))

    internal fun toRoom(): Room = Room(
        name = host, host = normalized().host, tcpPort = tcpPort, udpPort = udpPort,
        gameId = gameId, gameVersion = gameVersion, players = 0, maxPlayers = 8, mode = mode, roomToken = roomToken
    )
}

interface ExternalMultiplayerApi {
    /**
     * Joins an externally reachable room. Supply a 16+ character roomToken to require
     * TLS-PSK TCP encryption. UDP-only/dual-channel operation remains for tokenless rooms.
     */
    suspend fun joinExternal(endpoint: ExternalRoomEndpoint, playerName: String): Boolean
}
