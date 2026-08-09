package com.example.lanmultiplayer
/** Native unsigned comparison keeps UDP ordering correct across Int wrap-around. */
object NativeSequenceWindow {
    private val loaded = runCatching { System.loadLibrary("lanmultiplayer_native"); true }.getOrDefault(false)
    private external fun isNewer(candidate: Int, previous: Int): Boolean
    fun accepts(candidate: Int, previous: Int): Boolean = if (loaded) isNewer(candidate, previous) else previous == -1 || candidate > previous || (previous > Int.MAX_VALUE - 10_000 && candidate < 10_000)
}
