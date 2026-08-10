package com.example.lanmultiplayer

import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.DTLSClientProtocol
import org.bouncycastle.tls.DTLSTransport
import org.bouncycastle.tls.DatagramTransport
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * DTLS-PSK helper used by remote UDP sessions. The room token is never used directly as a cipher key:
 * a SHA-256 domain-separated derivation produces a fixed 256-bit PSK for Bouncy Castle.
 */
internal object DtlsPsk {
    private const val IDENTITY = "lanmultiplayer-dtls-v1"
    private val random = SecureRandom()

    fun derive(roomToken: String): ByteArray {
        require(roomToken.length >= 16) { "Encrypted remote rooms require a room token of at least 16 characters" }
        return MessageDigest.getInstance("SHA-256")
            .digest("LanMultiplayerSdk/DTLS-PSK/v1\u0000$roomToken".toByteArray(Charsets.UTF_8))
    }

    fun client(transport: DatagramTransport, roomToken: String): DTLSTransport {
        val psk = derive(roomToken)
        return DTLSClientProtocol().connect(
            PSKTlsClient(BcTlsCrypto(random), BasicTlsPSKIdentity(IDENTITY, psk)),
            transport
        )
    }

    fun server(transport: DatagramTransport, roomToken: String): DTLSTransport {
        val expectedPsk = derive(roomToken)
        val identity = IDENTITY.toByteArray(Charsets.UTF_8)
        val manager = object : TlsPSKIdentityManager {
            override fun getHint(): ByteArray = identity
            override fun getPSK(clientIdentity: ByteArray): ByteArray? =
                if (clientIdentity.contentEquals(identity)) expectedPsk.copyOf() else null
        }
        return PSKTlsServer(BcTlsCrypto(random), manager).let { server ->
            org.bouncycastle.tls.DTLSServerProtocol().apply { setVerifyRequests(true) }.accept(server, transport)
        }
    }
}

/** Connected DatagramSocket adapter. Bouncy Castle owns record framing; this class never exposes plaintext UDP. */
internal class ConnectedDatagramTransport(private val socket: java.net.DatagramSocket) : DatagramTransport {
    override fun getReceiveLimit(): Int = 16 * 1024
    override fun getSendLimit(): Int = 16 * 1024
    override fun receive(buffer: ByteArray, offset: Int, length: Int, waitMillis: Int): Int {
        val oldTimeout = socket.soTimeout
        try {
            socket.soTimeout = waitMillis.coerceIn(1, 30_000)
            val packet = java.net.DatagramPacket(buffer, offset, length)
            socket.receive(packet)
            return packet.length
        } finally {
            socket.soTimeout = oldTimeout
        }
    }
    override fun send(buffer: ByteArray, offset: Int, length: Int) {
        socket.send(java.net.DatagramPacket(buffer, offset, length))
    }
    override fun close() { socket.close() }
}
