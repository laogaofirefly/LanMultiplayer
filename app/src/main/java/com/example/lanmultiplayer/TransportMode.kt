package com.example.lanmultiplayer

/** Actual route selected after connection probing. UDP-only requires the reliable-UDP session layer. */
enum class TransportMode {
    CONNECTING,
    DUAL_CHANNEL,
    TCP_ONLY,
    UDP_ONLY,
    UNAVAILABLE
}
