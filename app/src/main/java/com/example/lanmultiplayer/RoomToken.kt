package com.example.lanmultiplayer

import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

object RoomToken {
    private val random = SecureRandom()

    fun create(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun hash(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
}