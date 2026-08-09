package com.example.lanmultiplayer

import java.util.concurrent.ConcurrentHashMap

class RoomRegistry(private val maxPlayers: Int) {
    private val players = ConcurrentHashMap<Int, String>()

    fun add(id: Int, name: String): Boolean {
        if (players.size >= maxPlayers) return false
        players[id] = name.take(32)
        return true
    }

    fun remove(id: Int) = players.remove(id)
    fun count(): Int = players.size
    fun contains(id: Int): Boolean = players.containsKey(id)
    fun snapshot(): Map<Int, String> = players.toMap()
}