package com.example.lanmultiplayer

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LanServer(
    private val context: Context,
private val config: RoomConfig,
     private val hostPlayerName: String,
     private val tcpPort: Int = 0,
    private val udpPort: Int = 0
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap<Int, Client>()
    private val udpPeers = ConcurrentHashMap<Int, InetSocketAddress>()
    private val nextId = AtomicInteger(1)
    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var registration: android.net.nsd.NsdManager.RegistrationListener? = null
    @Volatile private var running = false
    private val inboundSequences = SequenceWindow()
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val players = _players.asStateFlow()
    /** Host UI subscribes here because the host is not a TCP client of its own room. */
    val chatMessages = _chatMessages.asStateFlow()

    val actualTcpPort: Int get() = tcp?.localPort ?: 0
    val actualUdpPort: Int get() = udp?.localPort ?: 0

    suspend fun start() = withContext(Dispatchers.IO) {
        if (running) return@withContext
        tcp = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(tcpPort)) }
        udp = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(udpPort)) }
        running = true
        _players.value = listOf(Player(0, hostPlayerName.ifBlank { "房主" }.take(32)))
        registration = NsdDiscovery(context).register(config, actualTcpPort, actualUdpPort, 0)
        scope.launch { acceptLoop() }
        scope.launch { udpLoop() }
    }

    private suspend fun acceptLoop() {
        while (running && scope.isActive) {
            val socket = runCatching { tcp?.accept() }.getOrNull() ?: break
            if (clients.size >= config.maxPlayers) { socket.close(); continue }
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            socket.keepAlive = true
            // Admit a player only after its HELLO was validated. Half-open sockets must not consume slots.
            scope.launch { clientLoop(Client(nextId.getAndIncrement(), socket)) }
        }
    }

    private suspend fun clientLoop(client: Client) {
        try {
            val hello = withTimeout(5000) { client.session.receive() }
            if (hello.type != Protocol.HELLO) throw IllegalStateException("HELLO required")
            client.name = hello.payload.toString(Charsets.UTF_8).trim().take(32)
            if (client.name.isEmpty() || client.name.any { it.isISOControl() }) throw IllegalArgumentException("Invalid player name")
            // maxPlayers includes host player 0; publish only fully admitted clients.
            if (clients.size >= (config.maxPlayers - 1).coerceAtLeast(0)) throw IllegalStateException("Room is full")
            clients[client.id] = client
            client.session.send(Protocol.HELLO, intBytes(client.id))
            broadcastPlayerList()
            broadcastTcp(Protocol.RELIABLE, "join:${client.id}:${client.name}".toByteArray())
            while (scope.isActive) {
                val message = client.session.receive()
                when (message.type) {
Protocol.RELIABLE -> broadcastTcp(Protocol.RELIABLE, message.payload)
                     Protocol.CHAT -> {
                         val text = message.payload.toString(Charsets.UTF_8).trim().take(300)
                         if (text.isNotEmpty()) {
                              val chat = ChatMessage(client.name, text)
                              appendChat(chat)
                              broadcastTcp(Protocol.CHAT, ChatCodec.encode(chat))
                          }
                     }
                     Protocol.PING -> client.session.send(Protocol.PONG, message.payload)
                }
            }
        } catch (_: Exception) {
            remove(client)
        }
    }

    private suspend fun udpLoop() {
        val buffer = ByteArray(1400)
        while (running && scope.isActive) {
            val socket = udp ?: break
            val packet = DatagramPacket(buffer, buffer.size)
            val result = runCatching {
                socket.receive(packet)
                Protocol.decodeUdp(packet.data, packet.length)
            }.getOrNull() ?: continue
            val source = InetSocketAddress(packet.address, packet.port)
            val playerId = findPlayerId(result.payload)
            if (result.type == Protocol.HELLO) {
                if (playerId != null) udpPeers[playerId] = source
                continue
            }
            if (playerId == null || !inboundSequences.accept(playerId, result.sequence)) continue
            udpPeers[playerId] = source
            val bytes = Protocol.encodeUdp(result.type, result.sequence, result.frame, result.payload)
            udpPeers.values.distinct().forEach { peer ->
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, peer.address, peer.port)) }
            }
        }
    }

    private fun findPlayerId(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        val id = java.nio.ByteBuffer.wrap(payload, 0, 4).int
        return if (clients.containsKey(id)) id else null
    }

    private suspend fun broadcastPlayerList() {
        val players = listOf(Player(0, hostPlayerName.ifBlank { "房主" }.take(32))) + clients.values
            .filter { it.name.isNotBlank() }
            .sortedBy { it.id }
            .map { Player(it.id, it.name) }
        _players.value = players
        broadcastTcp(Protocol.PLAYER_LIST, PlayerListCodec.encode(players))
    }

    private fun appendChat(chat: ChatMessage) {
        _chatMessages.value = (_chatMessages.value + chat).takeLast(100)
    }

    suspend fun sendHostChat(text: String) {
        val normalized = ChatCodec.encodeClientText(text)
        if (!running || normalized.isEmpty()) return
        val chat = ChatMessage(hostPlayerName.ifBlank { "房主" }.take(32), normalized.toString(Charsets.UTF_8))
        appendChat(chat)
        broadcastTcp(Protocol.CHAT, ChatCodec.encode(chat))
    }

    private suspend fun broadcastTcp(type: Byte, payload: ByteArray) {
        clients.values.toList().forEach { client ->
            runCatching { client.session.send(type, payload) }
        }
    }

    private fun remove(client: Client) {
        clients.remove(client.id)
        udpPeers.remove(client.id)
        client.session.close()
        scope.launch { broadcastPlayerList() }
    }

    fun stop() {
        running = false
        registration = null
        clients.values.forEach { it.session.close() }
        clients.clear()
        udpPeers.clear()
        inboundSequences.clear()
        runCatching { tcp?.close() }
        udp?.close()
        scope.cancel()
    }

    private fun intBytes(value: Int): ByteArray =
        java.nio.ByteBuffer.allocate(4).putInt(value).array()

    private class Client(val id: Int, socket: Socket) {
        val session = TcpSession.fromSocket(socket)
        var name: String = ""
    }
}
