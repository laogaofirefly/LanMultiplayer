package com.example.lanmultiplayer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.3 framing transport. Certificate pinning is intentionally mandatory for remote mode;
 * do not replace [PinnedTrustManager] with a trust-all manager.
 */
internal class TlsSession private constructor(private val socket: SSLSocket) : FramedSession {
    private val input = BufferedInputStream(socket.inputStream)
    private val output = BufferedOutputStream(socket.outputStream)

    override suspend fun send(type: Byte, payload: ByteArray) = withContext(Dispatchers.IO) {
        require(payload.size <= 1024 * 1024)
        val body = ByteBuffer.allocate(1 + payload.size).put(type).put(payload).array()
        synchronized(output) {
            output.write(ByteBuffer.allocate(4).putInt(body.size).array())
            output.write(body)
            output.flush()
        }
    }

    override suspend fun receive(): NetworkMessage = withContext(Dispatchers.IO) {
        val lengthBytes = ByteArray(4)
        readFully(lengthBytes)
        val length = ByteBuffer.wrap(lengthBytes).int
        require(length in 1..1024 * 1024)
        val body = ByteArray(length)
        readFully(body)
        NetworkMessage(body[0], body.copyOfRange(1, body.size))
    }

    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count <= 0) throw EOFException("TLS closed")
            offset += count
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        suspend fun connect(host: String, port: Int, timeoutMs: Int, pinnedCertificateSha256: String): TlsSession =
            withContext(Dispatchers.IO) {
                require(port in 1..65535 && timeoutMs in 1_000..30_000)
                require(pinnedCertificateSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "TLS certificate SHA-256 pin required" }
                val trustManagers = arrayOf<TrustManager>(PinnedTrustManager(pinnedCertificateSha256))
                val context = SSLContext.getInstance("TLS").apply { init(null, trustManagers, SecureRandom()) }
                val raw = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(host, port), timeoutMs)
                }
                (context.socketFactory.createSocket(raw, host, port, true) as SSLSocket).apply {
                    enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" }.toTypedArray()
                    require(enabledProtocols.isNotEmpty()) { "TLS 1.3 unavailable on this device" }
                    startHandshake()
                }.let(::TlsSession)
            }
    }
}

private class PinnedTrustManager(private val expectedHex: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val certificate = chain.firstOrNull() ?: throw java.security.cert.CertificateException("Empty TLS certificate chain")
        val actual = java.security.MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(expectedHex, ignoreCase = true)) throw java.security.cert.CertificateException("TLS certificate pin mismatch")
    }
}
