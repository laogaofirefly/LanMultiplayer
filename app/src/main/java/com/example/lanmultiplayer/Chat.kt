package com.example.lanmultiplayer

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

data class ChatMessage(val sender: String, val text: String)

object ChatCodec {
    private const val MAX_NAME_BYTES = 32
    private const val MAX_TEXT_BYTES = 500

    fun encodeClientText(text: String): ByteArray =
        text.trim().take(300).toByteArray(Charsets.UTF_8).take(MAX_TEXT_BYTES).toByteArray()

    fun encode(message: ChatMessage): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            val name = message.sender.toByteArray(Charsets.UTF_8).take(MAX_NAME_BYTES).toByteArray()
            val text = message.text.toByteArray(Charsets.UTF_8).take(MAX_TEXT_BYTES).toByteArray()
            out.writeByte(name.size)
            out.write(name)
            out.writeShort(text.size)
            out.write(text)
        }
        bytes.toByteArray()
    }

    fun decode(payload: ByteArray): ChatMessage? = runCatching {
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            val nameSize = input.readUnsignedByte()
            require(nameSize <= MAX_NAME_BYTES)
            val name = ByteArray(nameSize).also(input::readFully).toString(Charsets.UTF_8)
            val textSize = input.readUnsignedShort()
            require(textSize in 1..MAX_TEXT_BYTES && input.available() >= textSize)
            ChatMessage(name, ByteArray(textSize).also(input::readFully).toString(Charsets.UTF_8))
        }
    }.getOrNull()
}