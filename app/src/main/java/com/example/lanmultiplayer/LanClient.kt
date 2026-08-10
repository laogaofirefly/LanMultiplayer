package com.example.lanmultiplayer

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LanClient(context: Context, private val gameId: String, private val gameVersion: Int = 1) : LanMultiplayer, ExternalMultiplayerApi {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = NsdDiscovery(context)
    private var discoveryJob: Job? = null
    private var tcp: TcpSession? = null
    private var udp: UdpSession? = null
    private var receiveJob: Job? = null
    private var udpJob: Job? = null
    private var heartbeatJob: Job? = null
    private var udpKeepAliveJob: Job? = null
    private var reconnectJob: Job? = null
    private val joining = AtomicBoolean(false)
    private var reconnectRoom: Room? = null
    private var reconnectPlayerName: String? = null
    @Volatile var autoReconnectEnabled: Boolean = true
    var reconnectPolicy: ReconnectPolicy = ReconnectPolicy()
    @Volatile private var lastPongAt = 0L
    private val sequence = AtomicInteger()
    private var playerId = 0
    private var latestUdpSequence: Int? = null
    /** ACK waiters are used only by UDP-only reliable control messages. */
    private val udpReliableAcks = ConcurrentHashMap<Int, CompletableDeferred<Unit>>()
    private val udpReliableReceiveSequences = SequenceWindow()
    private val _transportMode = MutableStateFlow(TransportMode.UNAVAILABLE)
    /** The selected route is observable so the UI/game can explain TCP fallback. */
    val transportMode = _transportMode.asStateFlow()
    @Volatile private var externalConnectTimeoutMs = 3_000

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    private val _stats = MutableStateFlow(NetworkStats())
    private val _players = MutableStateFlow<List<Player>>(emptyList())
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _reliable = MutableSharedFlow<NetworkMessage>(extraBufferCapacity = 128)
    private val _realtime = MutableSharedFlow<NetworkMessage>(extraBufferCapacity = 256)

    override val state = _state.asStateFlow()
    override val rooms = _rooms.asStateFlow()
    override val stats = _stats.asStateFlow()
    val players = _players.asStateFlow()
    val chatMessages = _chatMessages.asStateFlow()
    override val reliableMessages = _reliable.asSharedFlow()
    override val realtimeMessages = _realtime.asSharedFlow()

    override suspend fun startDiscovery() {
        stopDiscovery()
        discoveryJob = scope.launch {
            discovery.discover(gameId).collect { room ->
                if (room.gameVersion == gameVersion) _rooms.value = _rooms.value.filterNot { it.name == room.name } + room
            }
        }
    }

    override fun stopDiscovery() { discoveryJob?.cancel(); discoveryJob = null; _rooms.value = emptyList() }

    override suspend fun join(room: Room, playerName: String): Boolean {
        if (!joining.compareAndSet(false, true)) return false
        try {
        if (room.gameId != gameId || room.gameVersion != gameVersion || !isValidPlayerName(playerName)) return false
        closeConnection(); _state.value = ConnectionState.CONNECTING
        reconnectRoom = room
        reconnectPlayerName = playerName
        _transportMode.value = TransportMode.CONNECTING
        return runCatching {
            // NSD can expose IPv6 literals; bracketed literals are not accepted by Socket.
            val host = room.host.removePrefix("[").removeSuffix("]").trim()
            require(host.isNotEmpty()) { "Missing host" }
            if (room.tcpPort !in 1..65535) {
                // Token-protected rooms have no plaintext UDP-only fallback.
                if (room.roomToken.isNotEmpty()) return@runCatching false
                return@runCatching joinUdpOnly(host, room.udpPort, playerName, "")
            }
            val session = TcpSession.connect(host, room.tcpPort, externalConnectTimeoutMs, room.roomToken)
            tcp = session
            session.send(Protocol.HELLO, Protocol.encodeUdpJoin(room.roomToken, playerName))
            val welcome = withTimeout(8_000) { session.receive() }
            require(welcome.type == Protocol.HELLO && welcome.payload.size >= 4)
            playerId = ByteBuffer.wrap(welcome.payload).int
            // A token makes TCP TLS-PSK mandatory. UDP is deliberately disabled for such rooms:
            // the current UDP protocol has admission/replay controls but is not encrypted, and it
            // must never silently carry game payloads beside an encrypted TCP channel.
            val encryptedRoom = room.roomToken.isNotEmpty()
            udp = if (!encryptedRoom) room.udpPort.takeIf { it in 1..65535 }
                ?.let { port -> runCatching { UdpSession(host, port) }.getOrNull() } else null
            val idBytes = ByteBuffer.allocate(4).putInt(playerId).array()
            val udpAvailable = udp?.let { udpSession ->
                runCatching {
                    udpSession.send(Protocol.UDP_PROBE, sequence.getAndIncrement(), 0, idBytes)
                    withTimeout(2_500) {
                        while (true) {
                            val packet = udpSession.receive() ?: continue
                            if (packet.type == Protocol.UDP_PROBE_ACK && packet.payload.contentEquals(idBytes)) break
                        }
                    }
                    true
                }.getOrDefault(false)
            } ?: false
            _transportMode.value = if (udpAvailable) TransportMode.DUAL_CHANNEL else TransportMode.TCP_ONLY
            _state.value = ConnectionState.CONNECTED
            receiveJob = scope.launch { tcpLoop(session) }
            if (udpAvailable) udpJob = scope.launch { udpLoop() } else { udp?.close(); udp = null }
            lastPongAt = System.currentTimeMillis()
            heartbeatJob = scope.launch { heartbeatLoop(session) }
            udpKeepAliveJob = scope.launch { udpKeepAliveLoop() }
            true
        }.getOrElse {
            // A token-protected room requires TLS-PSK TCP. Do not downgrade to plaintext
            // UDP-only when the TLS endpoint is unavailable.
            closeConnection()
            val host = room.host.removePrefix("[").removeSuffix("]").trim()
            val udpFallback = if (room.roomToken.isEmpty()) {
                room.udpPort.takeIf { port -> port in 1..65535 }?.let { port ->
                    runCatching {
                        _state.value = ConnectionState.CONNECTING
                        _transportMode.value = TransportMode.CONNECTING
                        joinUdpOnly(host, port, playerName, room.roomToken)
                    }.getOrDefault(false)
                } ?: false
            } else false
            if (!udpFallback) { closeConnection(); _state.value = ConnectionState.FAILED }
            udpFallback
        }
        } finally {
            joining.set(false)
        }
    }

    override suspend fun joinExternal(endpoint: ExternalRoomEndpoint, playerName: String): Boolean {
        val accepted = endpoint.gameId == gameId && endpoint.gameVersion == gameVersion && isValidPlayerName(playerName)
        if (!accepted) return false
        val normalized = endpoint.normalized()
        externalConnectTimeoutMs = normalized.connectTimeoutMs
        repeat(normalized.maxConnectAttempts) { attempt ->
            if (join(normalized.toRoom(), playerName)) return true
            if (attempt + 1 < normalized.maxConnectAttempts) delay(300L * (attempt + 1))
        }
        return false
    }

    private fun isValidPlayerName(name: String): Boolean =
    name.trim().isNotEmpty() && name.length <= 32 && name.none { it.isISOControl() }

    private suspend fun joinUdpOnly(host: String, port: Int, playerName: String, roomToken: String): Boolean {
        require(port in 1..65535) { "UDP port required" }
        val udpSession = UdpSession(host, port)
        udp = udpSession
        val id = UdpOnlySession.join(udpSession, sequence.getAndIncrement(), playerName, roomToken)
            ?: throw IllegalStateException("UDP-only join timed out")
        playerId = id
        _transportMode.value = TransportMode.UDP_ONLY
        _state.value = ConnectionState.CONNECTED
        udpJob = scope.launch { udpOnlyLoop() }
        udpKeepAliveJob = scope.launch { udpKeepAliveLoop() }
        return true
    }

    private suspend fun tcpLoop(session: TcpSession) {
        try {
            while (scope.isActive) {
                val message = session.receive()
                when (message.type) {
                    Protocol.PLAYER_LIST -> _players.value = PlayerListCodec.decode(message.payload)
Protocol.CHAT -> ChatCodec.decode(message.payload)?.let { chat ->
                        _chatMessages.value = (_chatMessages.value + chat).takeLast(100)
                    }
                    Protocol.PONG -> {
                        if (message.payload.size == 8) {
                            val sentAt = ByteBuffer.wrap(message.payload).long
                            lastPongAt = System.currentTimeMillis()
                            _stats.value = _stats.value.copy(rttMs = (lastPongAt - sentAt).coerceAtLeast(0))
                        }
                    }
                    Protocol.REALTIME -> _realtime.emit(NetworkMessage(message.type, message.payload))
                    else -> _reliable.emit(message)
                }
                _stats.value = _stats.value.copy(received = _stats.value.received + 1)
            }
        } catch (_: Exception) {
            if (_state.value == ConnectionState.CONNECTED) {
                _state.value = ConnectionState.FAILED
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (!autoReconnectEnabled || reconnectJob?.isActive == true) return
        val room = reconnectRoom ?: return
        val name = reconnectPlayerName ?: return
        reconnectJob = scope.launch {
            val restored = ReconnectCoordinator(reconnectPolicy).run { _ -> join(room, name) }
            if (!restored && _state.value == ConnectionState.CONNECTING) _state.value = ConnectionState.FAILED
        }
    }

    private suspend fun heartbeatLoop(session: TcpSession) {
        while (scope.isActive && _state.value == ConnectionState.CONNECTED) {
            val now = System.currentTimeMillis()
            try {
                session.send(Protocol.PING, ByteBuffer.allocate(8).putLong(now).array())
                _stats.value = _stats.value.copy(sent = _stats.value.sent + 1)
            } catch (_: Exception) {
                break
            }
            delay(2_000)
            if (System.currentTimeMillis() - lastPongAt > 8_000) {
                _state.value = ConnectionState.FAILED
                closeConnection()
                scheduleReconnect()
                break
            }
        }
    }

    private suspend fun udpKeepAliveLoop() {
        while (scope.isActive && _state.value == ConnectionState.CONNECTED) {
            runCatching {
                udp?.send(Protocol.HELLO, sequence.getAndIncrement(), 0, ByteBuffer.allocate(4).putInt(playerId).array())
            }
            delay(15_000)
        }
    }

    private suspend fun udpOnlyLoop() {
        while (scope.isActive && _state.value == ConnectionState.CONNECTED) {
            val packet = udp?.receive() ?: break
            when (packet.type) {
                Protocol.UDP_ACK -> udpReliableAcks.remove(packet.sequence)?.complete(Unit)
                Protocol.PLAYER_LIST -> _players.value = PlayerListCodec.decode(packet.payload)
                Protocol.REALTIME -> _realtime.emit(NetworkMessage(packet.type, packet.payload))
                Protocol.UDP_RELIABLE, Protocol.UDP_CHAT -> {
                    val senderId = packet.payload.playerIdOrNull() ?: continue
                    if (!udpReliableReceiveSequences.accept(senderId, packet.sequence)) {
                        // The relay may be retrying because our previous ACK was lost.
                        udp?.send(Protocol.UDP_ACK, packet.sequence, 0, intBytes(senderId))
                        continue
                    }
                    // ACK first: duplicate retransmits can be answered even if the app consumer is slow.
                    udp?.send(Protocol.UDP_ACK, packet.sequence, 0, intBytes(senderId))
                    val body = packet.payload.copyOfRange(4, packet.payload.size)
                    if (packet.type == Protocol.UDP_CHAT) {
                        ChatCodec.decode(body)?.let { chat -> _chatMessages.value = (_chatMessages.value + chat).takeLast(100) }
                    } else _reliable.emit(NetworkMessage(Protocol.RELIABLE, body))
                }
            }
            _stats.value = _stats.value.copy(received = _stats.value.received + 1, udpReceived = _stats.value.udpReceived + 1)
        }
    }

    private suspend fun udpLoop() {
        while (scope.isActive) {
            val packet = udp?.receive() ?: continue
            val previous = latestUdpSequence
            if (!NativeSequenceWindow.accepts(packet.sequence, previous)) continue
            val missing = if (previous == null) 0L else unsignedSequenceGap(previous, packet.sequence)
            latestUdpSequence = packet.sequence
            _realtime.emit(NetworkMessage(packet.type, packet.payload))
            _stats.value = _stats.value.copy(
                received = _stats.value.received + 1,
                udpReceived = _stats.value.udpReceived + 1,
                udpEstimatedLost = _stats.value.udpEstimatedLost + missing
            )
        }
    }


    suspend fun sendChat(text: String) {
        val payload = ChatCodec.encodeClientText(text)
        if (payload.isEmpty() || _state.value != ConnectionState.CONNECTED) return
        if (_transportMode.value == TransportMode.UDP_ONLY) {
            // Server adds the sender name before relaying this reliably to every UDP-only peer.
            sendUdpReliable(Protocol.UDP_CHAT, payload)
        } else {
            tcp?.send(Protocol.CHAT, payload)
            _stats.value = _stats.value.copy(sent = _stats.value.sent + 1)
        }
    }

    override suspend fun sendReliable(payload: ByteArray) {
        if (_transportMode.value == TransportMode.UDP_ONLY) sendUdpReliable(Protocol.UDP_RELIABLE, payload)
        else { tcp?.send(Protocol.RELIABLE, payload); _stats.value = _stats.value.copy(sent = _stats.value.sent + 1) }
    }

    private suspend fun sendUdpReliable(type: Byte, payload: ByteArray) {
        require(payload.size <= Protocol.MAX_PAYLOAD - 4) { "Reliable UDP payload too large" }
        val session = udp ?: return
        val packetSequence = sequence.getAndIncrement()
        val ack = CompletableDeferred<Unit>()
        udpReliableAcks[packetSequence] = ack
        val body = ByteBuffer.allocate(4 + payload.size).putInt(playerId).put(payload).array()
        try {
            repeat(4) {
                session.send(type, packetSequence, 0, body)
                if (withTimeoutOrNull(700) { ack.await(); true } == true) return
            }
            throw IllegalStateException("Reliable UDP ACK timed out")
        } finally {
            udpReliableAcks.remove(packetSequence)
        }
    }

    override suspend fun sendRealtime(payload: ByteArray, frame: Int) {
        require(payload.size <= Protocol.MAX_PAYLOAD - 4) { "Realtime payload too large" }
        val body = ByteBuffer.allocate(4 + payload.size).putInt(playerId).put(payload).array()
        when (_transportMode.value) {
            TransportMode.DUAL_CHANNEL -> {
                udp?.send(Protocol.REALTIME, sequence.getAndIncrement(), frame, body)
                _stats.value = _stats.value.copy(sent = _stats.value.sent + 1, udpSent = _stats.value.udpSent + 1)
            }
            // TCP is deliberately the fallback rather than silently dropping gameplay state.
            // Games should rate-limit this path (roughly 10–20 updates/sec) to avoid HOL blocking.
            TransportMode.TCP_ONLY -> {
                tcp?.send(Protocol.REALTIME, body)
                _stats.value = _stats.value.copy(sent = _stats.value.sent + 1)
            }
            TransportMode.UDP_ONLY -> {
                udp?.send(Protocol.REALTIME, sequence.getAndIncrement(), frame, body)
                _stats.value = _stats.value.copy(sent = _stats.value.sent + 1, udpSent = _stats.value.udpSent + 1)
            }
            else -> Unit
        }
    }

    /** Number of skipped values when [newer] follows [older] in unsigned 32-bit sequence space. */
    private fun unsignedSequenceGap(older: Int, newer: Int): Long {
        val delta = (newer.toUInt() - older.toUInt()).toLong() and 0xffff_ffffL
        return (delta - 1L).coerceAtLeast(0L)
    }

    private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(4).putInt(value).array()

    private fun ByteArray.playerIdOrNull(): Int? =
        if (size < 4) null else ByteBuffer.wrap(this, 0, 4).int

    private fun closeConnection() {
        udpReliableAcks.values.forEach { it.cancel() }
        udpReliableAcks.clear(); udpReliableReceiveSequences.clear()
        receiveJob?.cancel(); udpJob?.cancel(); heartbeatJob?.cancel(); udpKeepAliveJob?.cancel()
        receiveJob = null; udpJob = null; heartbeatJob = null; udpKeepAliveJob = null
        tcp?.close(); udp?.close(); tcp = null; udp = null
        playerId = 0; latestUdpSequence = null; _transportMode.value = TransportMode.UNAVAILABLE
        _players.value = emptyList(); _chatMessages.value = emptyList()
    }
    override fun close() {
        joining.set(false)
        autoReconnectEnabled = false
        reconnectJob?.cancel(); reconnectJob = null
        reconnectRoom = null; reconnectPlayerName = null
        stopDiscovery(); closeConnection(); scope.cancel(); _state.value = ConnectionState.DISCONNECTED
    }
}