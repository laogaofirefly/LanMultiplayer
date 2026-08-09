package com.example.lanmultiplayer

import java.nio.ByteBuffer
import java.security.MessageDigest

object Checksum {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
    fun crc32(data: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(data)
        return crc.value
    }
    fun withCrc(data: ByteArray): ByteArray = ByteBuffer.allocate(4 + data.size).putInt(crc32(data).toInt()).put(data).array()
    fun verifyCrc(data: ByteArray): ByteArray? {
        if (data.size < 4) return null
        val expected = ByteBuffer.wrap(data).int.toLong() and 0xffffffffL
        val body = data.copyOfRange(4, data.size)
        return if (crc32(body) == expected) body else null
    }
}