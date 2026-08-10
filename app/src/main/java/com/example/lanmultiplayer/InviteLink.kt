package com.example.lanmultiplayer

import android.net.Uri

/** Link format: lanmultiplayer://join?host=example.com&tcpPort=1234&udpPort=1235&name=MyRoom */
object InviteLink {
    fun parse(value: String, gameId: String): Room? = runCatching {
        val uri = Uri.parse(value.trim())
        require(uri.scheme == "lanmultiplayer" && uri.host == "join") { "链接格式无效" }
        val host = uri.getQueryParameter("host")?.trim().orEmpty()
        val tcpPort = uri.getQueryParameter("tcpPort")?.toIntOrNull() ?: 0
        val udpPort = uri.getQueryParameter("udpPort")?.toIntOrNull() ?: 0
        val roomToken = uri.getQueryParameter("token")?.trim().orEmpty()
        require(roomToken.length <= 128 && roomToken.none { it.isISOControl() }) { "房间令牌无效" }
        require(roomToken.isEmpty() || roomToken.length >= 16) { "加密房间令牌至少需要 16 个字符" }
        require(host.isNotBlank() && (tcpPort in 1..65535 || udpPort in 1..65535)) { "请至少填写有效的 TCP 或 UDP 端口" }
        Room(
            name = uri.getQueryParameter("name")?.take(24)?.ifBlank { "远程房间" } ?: "远程房间",
            host = host,
            tcpPort = tcpPort,
            udpPort = udpPort,
            gameId = gameId,
            gameVersion = 1,
            players = 0,
            maxPlayers = 8,
            mode = SyncMode.REALTIME_STATE,
            roomToken = roomToken
        )
    }.getOrNull()
}