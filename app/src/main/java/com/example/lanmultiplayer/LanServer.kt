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
    /** Clients admitted through the UDP-only JOIN handshake (no TCP socket exists). */
    private val udpOnlyClients = ConcurrentHashMap<Int, UdpOnlyClient>()
    private val udpPeers = ConcurrentHashMap<Int, InetSocketAddress>()
    /** Per-recipient relay retries make UDP reliable messages end-to-end, not merely client→server. */
    private val pendingUdpReliable = ConcurrentHashMap<String, PendingUdpReliable>()
    private val nextId = AtomicInteger(1)
    private val serverSequence = AtomicInteger()
    private var tcp: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var registration: android.net.nsd.NsdManager.RegistrationListener? = null
    @Volatile private var running = false
    private val inboundSequences = SequenceWindow()
    private val bannedAddresses = AddressBanList(config.security.banAfterFailures, config.security.banDurationMs)
    private val connectionRates = ConcurrentHashMap<String, PacketRateLimiter>()
    private val udpReplayWindow = ReplayWindow()
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
        // Plain UDP is never exposed for secure rooms until DTLS-PSK is wired into UdpSession.
        udp = if (config.security.mode == SecurityMode.TRUSTED_LAN_INSECURE) {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(udpPort)) }
        } else null
        running = true
        _players.value = listOf(Player(0, hostPlayerName.ifBlank { "房主" }.take(32)))
        registration = NsdDiscovery(context).register(config, actualTcpPort, actualUdpPort, 0)
        scope.launch { acceptLoop() }
        scope.launch { udpLoop() }
        scope.launch { udpExpiryLoop() }
    }

    private suspend fun acceptLoop() {
        while (running && scope.isActive) {
            val socket = runCatching { tcp?.accept() }.getOrNull() ?: break
            val address = socket.inetAddress.hostAddress ?: "unknown"
             if (bannedAddresses.isBanned(address) || !connectionRates.getOrPut(address) { PacketRateLimiter(config.security.messagesPerSecond) }.allow()) {
                 socket.close(); continue
             }
             if (clients.values.count { it.address == address } >= config.security.maxConnectionsPerAddress || allClientCount() >= (config.security.maxConnections - 1).coerceAtLeast(0)) {
                 socket.close(); continue
             }
            socket.soTimeout = 30_000
            socket.tcpNoDelay = true
            socket.keepAlive = true
            // Admit a player only after its HELLO was validated. Half-open sockets must not consume slots.
            scope.launch {
                // A token-protected room never accepts a plaintext TCP frame. TLS handshake failure
                // closes the socket and consumes no player slot.
                val session = runCatching {
if (config.security.mode == SecurityMode.TRUSTED_LAN_INSECURE) TcpSession.fromSocket(socket)
                     else TcpSession.fromTlsSocket(socket, config.roomToken)
                }.getOrElse { socket.close(); return@launch }
                clientLoop(Client(nextId.getAndIncrement(), session, address))
            }
        }
    }

    private suspend fun clientLoop(client: Client) {
        try {
            val hello = withTimeout(5000) { client.session.receive() }
            if (hello.type != Protocol.HELLO) throw IllegalStateException("HELLO required")
            val join = Protocol.decodeUdpJoin(hello.payload) ?: throw IllegalArgumentException("Invalid HELLO")
            if (config.security.mode == SecurityMode.SECURE && join.first != config.roomToken) throw IllegalAccessException("Invalid room token")
            bannedAddresses.success(client.address)
            client.name = join.second
            if (client.name.any { it.isISOControl() }) throw IllegalArgumentException("Invalid player name")
            // maxPlayers includes host player 0; publish only fully admitted clients.
            if (allClientCount() >= (config.maxPlayers - 1).coerceAtLeast(0)) throw IllegalStateException("Room is full")
            clients[client.id] = client
            client.session.send(Protocol.HELLO, intBytes(client.id))
            broadcastPlayerList()
            broadcastTcp(Protocol.RELIABLE, "join:${client.id}:${client.name}".toByteArray())
            while (scope.isActive) {
                val message = client.session.receive()
                if (!client.rateLimiter.allow()) throw IllegalStateException("message rate exceeded")
                if (message.payload.size > Protocol.MAX_PAYLOAD && message.type != Protocol.RELIABLE) throw IllegalArgumentException("payload too large")
                when (message.type) {
Protocol.RELIABLE -> {
                         if (config.gameRuleValidator.validate(client.id, message.type, message.payload)) {
                             broadcastTcp(Protocol.RELIABLE, message.payload)
                         }
                     }
                     Protocol.CHAT -> {
                         val text = message.payload.toString(Charsets.UTF_8).trim().take(300)
if (text.isNotEmpty() && config.gameRuleValidator.validate(client.id, message.type, text.toByteArray(Charsets.UTF_8))) {
                               val chat = ChatMessage(client.name, text)
                              appendChat(chat)
                              broadcastTcp(Protocol.CHAT, ChatCodec.encode(chat))
                          }
                     }
                     Protocol.PING -> client.session.send(Protocol.PONG, message.payload)
                    // TCP fallback for tunnels which do not expose UDP. REALTIME bodies already
                    // begin with playerId, so the server can validate and relay them safely.
                    Protocol.REALTIME -> {
if (findPlayerId(message.payload) == client.id && config.gameRuleValidator.validate(client.id, message.type, message.payload)) {
                             broadcastTcp(Protocol.REALTIME, message.payload)
                         }
                    }
                }
            }
        } catch (_: Exception) {
            bannedAddresses.failure(client.address)
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
            if (result.type == Protocol.UDP_JOIN) {
                handleUdpOnlyJoin(socket, source, result)
                continue
            }
            val playerId = findPlayerId(result.payload)
            if (result.type != Protocol.HELLO && result.type != Protocol.UDP_PROBE &&
                !udpReplayWindow.accept(source.toString(), result.sequence)) continue
            if (result.type == Protocol.HELLO || result.type == Protocol.UDP_PROBE) {
                if (playerId != null) {
                    udpPeers[playerId] = source
                    udpOnlyClients[playerId]?.lastSeenAt = System.currentTimeMillis()
                    // A concrete reply is required: creating a DatagramSocket locally does not prove
                    // that a tunnel forwards UDP in both directions.
                    if (result.type == Protocol.UDP_PROBE) {
                        val ack = Protocol.encodeUdp(Protocol.UDP_PROBE_ACK, result.sequence, 0, intBytes(playerId))
                        runCatching { socket.send(DatagramPacket(ack, ack.size, source.address, source.port)) }
                    }
                }
                continue
            }
            if (result.type == Protocol.UDP_ACK) {
                val recipientId = udpOnlyClients.values.firstOrNull { it.address == source }?.id
                val senderId = findPlayerId(result.payload)
                if (recipientId != null && senderId != null) pendingUdpReliable.remove(reliableKey(recipientId, senderId, result.sequence))
                continue
            }
            if (playerId == null) continue
            // A UDP-only session is bound to the address that completed its token-verified JOIN.
            // Do not let a guessed player id rebind it from another public endpoint.
            if (udpOnlyClients[playerId]?.address?.let { it != source } == true) continue
            val isReliableUdp = result.type == Protocol.UDP_RELIABLE || result.type == Protocol.UDP_CHAT
            if (!inboundSequences.accept(playerId, result.sequence)) {
                // The application message was already processed, but its ACK may have been lost.
                if (isReliableUdp) {
                    val ack = Protocol.encodeUdp(Protocol.UDP_ACK, result.sequence, 0, intBytes(playerId))
                    runCatching { socket.send(DatagramPacket(ack, ack.size, source.address, source.port)) }
                }
                continue
            }
            udpPeers[playerId] = source
            udpOnlyClients[playerId]?.lastSeenAt = System.currentTimeMillis()
            if (isReliableUdp) {
                // ACK delivery to the sender before broadcasting. Retries are deduplicated above.
                val ack = Protocol.encodeUdp(Protocol.UDP_ACK, result.sequence, 0, intBytes(playerId))
                runCatching { socket.send(DatagramPacket(ack, ack.size, source.address, source.port)) }
                if (result.type == Protocol.UDP_CHAT) {
                    val sender = udpOnlyClients[playerId]?.name ?: clients[playerId]?.name ?: continue
                    val text = result.payload.copyOfRange(4, result.payload.size).toString(Charsets.UTF_8).trim().take(300)
                    if (text.isEmpty()) continue
                    val chat = ChatMessage(sender, text)
                    appendChat(chat)
                    val encodedChat = ChatCodec.encode(chat)
                    val udpBody = java.nio.ByteBuffer.allocate(4 + encodedChat.size).putInt(playerId).put(encodedChat).array()
                    broadcastUdpReliable(Protocol.UDP_CHAT, result.sequence, playerId, udpBody)
                    broadcastTcp(Protocol.CHAT, encodedChat)
                } else {
                    broadcastUdpReliable(Protocol.UDP_RELIABLE, result.sequence, playerId, result.payload)
                    broadcastTcp(Protocol.RELIABLE, result.payload.copyOfRange(4, result.payload.size))
                }
                continue
            }
            val bytes = Protocol.encodeUdp(result.type, result.sequence, result.frame, result.payload)
            udpPeers.values.distinct().forEach { peer ->
                runCatching { socket.send(DatagramPacket(bytes, bytes.size, peer.address, peer.port)) }
            }
        }
    }

    private suspend fun udpExpiryLoop() {
        while (running && scope.isActive) {
            delay(5_000)
            val cutoff = System.currentTimeMillis() - 25_000
            val expired = udpOnlyClients.values.filter { it.lastSeenAt < cutoff }
            if (expired.isNotEmpty()) {
                expired.forEach { client ->
                    udpOnlyClients.remove(client.id)
                    udpPeers.remove(client.id)
                    pendingUdpReliable.keys.filter { it.startsWith("${client.id}:") }.forEach(pendingUdpReliable::remove)
                }
                broadcastPlayerList()
            }
        }
    }

    private suspend fun handleUdpOnlyJoin(socket: DatagramSocket, source: InetSocketAddress, packet: UdpPacket) {
        val join = Protocol.decodeUdpJoin(packet.payload) ?: return
        val token = join.first
        val name = join.second
        // Empty config token preserves LAN compatibility; remote hosts should configure a secret.
        if (config.security.mode == SecurityMode.SECURE && token != config.roomToken) return
        if (name.any { it.isISOControl() }) return
        // JOIN retransmits use the same source address + sequence, so return the prior id.
        val existing = udpOnlyClients.values.firstOrNull { it.address == source && it.joinSequence == packet.sequence }
        val client = existing ?: run {
            if (allClientCount() >= (config.maxPlayers - 1).coerceAtLeast(0)) return
            UdpOnlyClient(nextId.getAndIncrement(), name, source, packet.sequence).also {
                udpOnlyClients[it.id] = it
                udpPeers[it.id] = source
                broadcastPlayerList()
            }
        }
        val payload = intBytes(client.id)
        val welcome = Protocol.encodeUdp(Protocol.UDP_WELCOME, packet.sequence, 0, payload)
        runCatching { socket.send(DatagramPacket(welcome, welcome.size, source.address, source.port)) }
        // The first room broadcast may have preceded WELCOME while the client was still in its
        // join receive loop; send a current snapshot after admission as well.
        val playerList = PlayerListCodec.encode(_players.value)
        val listBytes = Protocol.encodeUdp(Protocol.PLAYER_LIST, 0, 0, playerList)
        runCatching { socket.send(DatagramPacket(listBytes, listBytes.size, source.address, source.port)) }
    }

    private fun findPlayerId(payload: ByteArray): Int? {
        if (payload.size < 4) return null
        val id = java.nio.ByteBuffer.wrap(payload, 0, 4).int
        return if (id == 0 || clients.containsKey(id) || udpOnlyClients.containsKey(id)) id else null
    }

    private fun allClientCount(): Int = clients.size + udpOnlyClients.size

    private suspend fun broadcastPlayerList() {
        val players = listOf(Player(0, hostPlayerName.ifBlank { "房主" }.take(32))) +
            (clients.values.filter { it.name.isNotBlank() }.map { Player(it.id, it.name) } +
                udpOnlyClients.values.map { Player(it.id, it.name) }).sortedBy { it.id }
        _players.value = players
        val payload = PlayerListCodec.encode(players)
        broadcastTcp(Protocol.PLAYER_LIST, payload)
        broadcastUdp(Protocol.PLAYER_LIST, payload)
    }

    private fun appendChat(chat: ChatMessage) {
        _chatMessages.value = (_chatMessages.value + chat).takeLast(100)
    }

    suspend fun sendHostChat(text: String) {
        val normalized = ChatCodec.encodeClientText(text)
        if (!running || normalized.isEmpty()) return
        val chat = ChatMessage(hostPlayerName.ifBlank { "房主" }.take(32), normalized.toString(Charsets.UTF_8))
        appendChat(chat)
        val encoded = ChatCodec.encode(chat)
        broadcastTcp(Protocol.CHAT, encoded)
        val body = java.nio.ByteBuffer.allocate(4 + encoded.size).putInt(0).put(encoded).array()
        broadcastUdpReliable(Protocol.UDP_CHAT, serverSequence.getAndIncrement(), 0, body)
    }

    private suspend fun broadcastTcp(type: Byte, payload: ByteArray) {
        clients.values.toList().forEach { client ->
            runCatching { client.session.send(type, payload) }
        }
    }

    private fun broadcastUdp(type: Byte, payload: ByteArray) = broadcastUdpPacket(type, serverSequence.getAndIncrement(), payload)

    private fun broadcastUdpPacket(type: Byte, sequence: Int, payload: ByteArray) {
        val socket = udp ?: return
        val bytes = Protocol.encodeUdp(type, sequence, 0, payload)
        udpOnlyClients.values.map { it.address }.distinct().forEach { peer ->
            runCatching { socket.send(DatagramPacket(bytes, bytes.size, peer.address, peer.port)) }
        }
    }

    private fun broadcastUdpReliable(type: Byte, sequence: Int, senderId: Int, payload: ByteArray) {
        val socket = udp ?: return
        val bytes = Protocol.encodeUdp(type, sequence, 0, payload)
        udpOnlyClients.values.forEach { client ->
            val key = reliableKey(client.id, senderId, sequence)
            pendingUdpReliable[key] = PendingUdpReliable(bytes, client.address)
            scope.launch {
                repeat(4) {
                    val pending = pendingUdpReliable[key] ?: return@launch
                    runCatching { socket.send(DatagramPacket(pending.bytes, pending.bytes.size, pending.address.address, pending.address.port)) }
                    delay(700)
                }
                pendingUdpReliable.remove(key)
            }
        }
    }

    private fun reliableKey(recipientId: Int, senderId: Int, sequence: Int): String = "$recipientId:$senderId:$sequence"

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
        udpOnlyClients.clear()
        udpPeers.clear()
        pendingUdpReliable.clear()
        inboundSequences.clear()
        udpReplayWindow.clear()
        runCatching { tcp?.close() }
        udp?.close()
        scope.cancel()
    }

    private fun intBytes(value: Int): ByteArray =
        java.nio.ByteBuffer.allocate(4).putInt(value).array()

    private class Client(val id: Int, val session: TcpSession, val address: String) {
        var name: String = ""
        val rateLimiter = PacketRateLimiter(120)
    }

    private data class PendingUdpReliable(val bytes: ByteArray, val address: InetSocketAddress)

    private data class UdpOnlyClient(
        val id: Int,
        val name: String,
        val address: InetSocketAddress,
        val joinSequence: Int,
        @Volatile var lastSeenAt: Long = System.currentTimeMillis()
    )
}
