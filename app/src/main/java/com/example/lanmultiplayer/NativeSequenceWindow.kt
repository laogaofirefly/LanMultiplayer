package com.example.lanmultiplayer
/** Native unsigned comparison keeps UDP ordering correct across Int wrap-around. */
object NativeSequenceWindow {
    private val loaded = runCatching { System.loadLibrary("lanmultiplayer_native"); true }.getOrDefault(false)
    private external fun isNewer(candidate: Int, previous: Int): Boolean

    /**
     * Compares UInt32 sequence values using the RFC-style half-range rule.
     * This is exact for every wrap-around position, unlike threshold based checks.
     */
    fun accepts(candidate: Int, previous: Int?): Boolean {
        previous ?: return true
        return if (loaded) isNewer(candidate, previous) else isNewerKotlin(candidate, previous)
    }

    private fun isNewerKotlin(candidate: Int, previous: Int): Boolean {
        val delta = candidate.toUInt() - previous.toUInt()
        return delta != 0u && delta < 0x80000000u
    }
}
