package com.example.lanmultiplayer

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

/**
 * Minimal reliable control channel for UDP-only tunnels. Each control datagram carries a
 * sequence; the caller retransmits JOIN until WELCOME arrives. Later work will use ACK for
 * reliable chat/player-list events too. The initial handshake is deliberately bounded.
 */
internal object UdpOnlySession {
    private const val JOIN_ATTEMPTS = 4
    private const val JOIN_RETRY_MS = 700L

    suspend fun join(udp: UdpSession, sequence: Int, playerName: String, roomToken: String): Int? {
        val joinPayload = runCatching { Protocol.encodeUdpJoin(roomToken, playerName) }.getOrNull() ?: return null
        repeat(JOIN_ATTEMPTS) {
            udp.send(Protocol.UDP_JOIN, sequence, 0, joinPayload)
            val welcome = withTimeoutOrNull(JOIN_RETRY_MS) {
                while (true) {
                    val packet = udp.receive() ?: continue
                    if (packet.type == Protocol.UDP_WELCOME && packet.sequence == sequence && packet.payload.size == 4) {
                        return@withTimeoutOrNull packet.payload
                    }
                }
            }
            if (welcome != null) {
                val id = ByteBuffer.wrap(welcome).int
                if (id > 0) {
                    udp.send(Protocol.UDP_ACK, sequence, 0, welcome)
                    return id
                }
            }
            delay(50)
        }
        return null
    }
}