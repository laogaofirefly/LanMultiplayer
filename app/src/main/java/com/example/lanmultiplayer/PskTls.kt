package com.example.lanmultiplayer

import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsPSKIdentity
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * TLS-PSK transport bootstrap. A room token is not sent as the TLS password: it is first
 * converted to a fixed-length PSK. The protocol transcript then authenticates the handshake
 * and encrypts every TCP frame that follows it.
 *
 * This is intentionally for private, short-lived rooms. It does not replace certificates when
 * connecting to a public, independently operated server.
 */
internal object PskTls {
    private val random = SecureRandom()
    private val identity = "lanmultiplayer-v1".toByteArray(Charsets.US_ASCII)

    fun client(input: InputStream, output: OutputStream, roomToken: String): Pair<InputStream, OutputStream> {
        val protocol = TlsClientProtocol(input, output)
        protocol.connect(object : PSKTlsClient(BcTlsCrypto(random), object : TlsPSKIdentity {
            override fun skipIdentityHint() = Unit
            override fun notifyIdentityHint(pskIdentityHint: ByteArray?) = Unit
            override fun getPSKIdentity(): ByteArray = identity.copyOf()
            override fun getPSK(): ByteArray = psk(roomToken)
        }) {})
        return protocol.inputStream to protocol.outputStream
    }

    fun server(input: InputStream, output: OutputStream, roomToken: String): Pair<InputStream, OutputStream> {
        val protocol = TlsServerProtocol(input, output)
        protocol.accept(object : PSKTlsServer(BcTlsCrypto(random), object : TlsPSKIdentityManager {
            override fun getHint(): ByteArray = identity.copyOf()
            override fun getPSK(pskIdentity: ByteArray): ByteArray? =
                if (pskIdentity.contentEquals(identity)) psk(roomToken) else null
        }) {})
        return protocol.inputStream to protocol.outputStream
    }

    private fun psk(roomToken: String): ByteArray {
        require(roomToken.length >= 16) { "Encrypted rooms require a room token of at least 16 characters" }
        return MessageDigest.getInstance("SHA-256")
            .digest(("LanMultiplayer/TLS-PSK/v1:" + roomToken).toByteArray(Charsets.UTF_8))
    }
}
