package com.example.lanmultiplayer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class Player(val id: Int, val name: String)

object PlayerListCodec {
    fun encode(players: Collection<Player>): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.writeShort(players.size)
            players.forEach { player ->
                val name = player.name.take(32).toByteArray(Charsets.UTF_8)
                data.writeInt(player.id)
                data.writeByte(name.size)
                data.write(name)
            }
        }
        return output.toByteArray()
    }

    fun decode(payload: ByteArray): List<Player> = runCatching {
        DataInputStream(ByteArrayInputStream(payload)).use { data ->
            val count = data.readUnsignedShort()
            require(count <= 64)
            List(count) {
                val id = data.readInt()
                val length = data.readUnsignedByte()
                require(length <= 128)
                val name = ByteArray(length).also(data::readFully).toString(Charsets.UTF_8)
                Player(id, name)
            }
        }
    }.getOrDefault(emptyList())
}
