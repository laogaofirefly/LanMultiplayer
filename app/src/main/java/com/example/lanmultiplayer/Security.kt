package com.example.lanmultiplayer

import java.security.MessageDigest
import java.security.SecureRandom

/** Explicit security policy. Insecure transport is opt-in and intended only for trusted LANs. */
enum class SecurityMode { SECURE, TRUSTED_LAN_INSECURE }

data class RoomSecurityPolicy(
    val mode: SecurityMode = SecurityMode.SECURE,
    val maxConnections: Int = 8,
    val maxConnectionsPerAddress: Int = 2,
    val messagesPerSecond: Long = 120,
    val bytesPerSecond: Long = 256 * 1024,
    val banAfterFailures: Int = 8,
    val banDurationMs: Long = 60_000
) {
    init {
        require(maxConnections in 1..64)
        require(maxConnectionsPerAddress in 1..16)
        require(messagesPerSecond in 1..10_000)
        require(bytesPerSecond in 1024..16 * 1024 * 1024)
    }
}

object SessionSecurity {
    private val random = SecureRandom()

    fun nonce(): ByteArray = ByteArray(16).also(random::nextBytes)

    fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean =
        MessageDigest.isEqual(left, right)

    fun validateToken(token: String, required: Boolean = true) {
        require(token.length <= 128 && token.none { it.isISOControl() }) { "Invalid room token" }
        if (required) require(token.length >= 16) { "Secure rooms require a 16+ character token" }
    }
}

interface GameRuleValidator {
    fun validate(playerId: Int, type: Byte, payload: ByteArray): Boolean
}

object DefaultGameRuleValidator : GameRuleValidator {
    override fun validate(playerId: Int, type: Byte, payload: ByteArray): Boolean =
        playerId >= 0 && payload.size <= Protocol.MAX_PAYLOAD &&
            type in setOf(Protocol.RELIABLE, Protocol.REALTIME, Protocol.CHAT, Protocol.UDP_CHAT, Protocol.UDP_RELIABLE)
}
