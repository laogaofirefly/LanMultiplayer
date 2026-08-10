package com.example.lanmultiplayer

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Optional application authentication envelope for transports that are not already protected by TLS/DTLS.
 * It is deliberately separate from Protocol so old LAN peers remain wire-compatible.
 */
object AuthenticatedEnvelope {
    private const val VERSION: Byte = 1
    private const val NONCE_SIZE = 16
    private const val TAG_SIZE = 32
    private const val HEADER_SIZE = 1 + 8 + 4 + NONCE_SIZE

    fun encode(key: ByteArray, sequence: Long, payload: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray {
        require(key.isNotEmpty())
        require(payload.size <= Protocol.MAX_PAYLOAD)
        val nonce = SessionSecurity.nonce()
        val body = ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .put(VERSION).putLong(nowMs).putInt(sequence.toInt()).put(nonce).put(payload).array()
        return body + tag(key, body)
    }

    fun decode(key: ByteArray, packet: ByteArray, expectedSequence: Long? = null, nowMs: Long = System.currentTimeMillis(), maxSkewMs: Long = 30_000): ByteArray? {
        if (packet.size < HEADER_SIZE + TAG_SIZE || key.isEmpty()) return null
        if (maxSkewMs < 0) return null
        val body = packet.copyOf(packet.size - TAG_SIZE)
        val receivedTag = packet.copyOfRange(body.size, packet.size)
        if (!SessionSecurity.constantTimeEquals(tag(key, body), receivedTag)) return null
        val buffer = ByteBuffer.wrap(body)
        if (buffer.get() != VERSION) return null
        val timestamp = buffer.long
        val sequence = buffer.int.toLong() and 0xffff_ffffL
        if (kotlin.math.abs(nowMs - timestamp) > maxSkewMs) return null
        if (expectedSequence != null && sequence <= (expectedSequence and 0xffff_ffffL)) return null
        buffer.position(buffer.position() + NONCE_SIZE)
        return ByteArray(buffer.remaining()).also(buffer::get)
    }

    private fun tag(key: ByteArray, body: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(body)
    }
}