package com.example.lanmultiplayer

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
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
    @Volatile private var lastPongAt = 0L
    private val sequence = AtomicInteger()
    private var playerId = 0
    private var latestUdpSequence = -1
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
        if (room.gameId != gameId || room.gameVersion != gameVersion || !isValidPlayerName(playerName)) return false
        closeConnection(); _state.value = ConnectionState.CONNECTING
        return runCatching {
            val session = TcpSession.connect(room.host, room.tcpPort, externalConnectTimeoutMs)
            tcp = session; udp = UdpSession(room.host, room.udpPort.takeIf { it in 1..65535 } ?: room.tcpPort)
            session.send(Protocol.HELLO, playerName.take(32).toByteArray())
            val welcome = withTimeout(5000) { session.receive() }
            require(welcome.type == Protocol.HELLO && welcome.payload.size >= 4)
            playerId = ByteBuffer.wrap(welcome.payload).int
            udp?.send(Protocol.HELLO, sequence.getAndIncrement(), 0, ByteBuffer.allocate(4).putInt(playerId).array())
            _state.value = ConnectionState.CONNECTED
            receiveJob = scope.launch { tcpLoop(session) }
            udpJob = scope.launch { udpLoop() }
            lastPongAt = System.currentTimeMillis()
            heartbeatJob = scope.launch { heartbeatLoop(session) }
            udpKeepAliveJob = scope.launch { udpKeepAliveLoop() }
            true
        }.getOrElse { closeConnection(); _state.value = ConnectionState.FAILED; false }
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
                    else -> _reliable.emit(message)
                }
                _stats.value = _stats.value.copy(received = _stats.value.received + 1)
            }
        } catch (_: Exception) {
            if (_state.value == ConnectionState.CONNECTED) _state.value = ConnectionState.FAILED
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

    private suspend fun udpLoop() {
        while (scope.isActive) {
            val packet = udp?.receive() ?: continue
            if (!NativeSequenceWindow.accepts(packet.sequence, latestUdpSequence)) continue
            latestUdpSequence = packet.sequence
            _realtime.emit(NetworkMessage(packet.type, packet.payload))
            _stats.value = _stats.value.copy(received = _stats.value.received + 1)
        }
    }


    suspend fun sendChat(text: String) {
        val payload = ChatCodec.encodeClientText(text)
        if (payload.isNotEmpty() && _state.value == ConnectionState.CONNECTED) {
            tcp?.send(Protocol.CHAT, payload)
            _stats.value = _stats.value.copy(sent = _stats.value.sent + 1)
        }
    }

    override suspend fun sendReliable(payload: ByteArray) { tcp?.send(Protocol.RELIABLE, payload); _stats.value = _stats.value.copy(sent = _stats.value.sent + 1) }

    override suspend fun sendRealtime(payload: ByteArray, frame: Int) {
        val body = ByteBuffer.allocate(4 + payload.size).putInt(playerId).put(payload).array()
        udp?.send(Protocol.REALTIME, sequence.getAndIncrement(), frame, body)
        _stats.value = _stats.value.copy(sent = _stats.value.sent + 1)
    }

    private fun closeConnection() { receiveJob?.cancel(); udpJob?.cancel(); heartbeatJob?.cancel(); udpKeepAliveJob?.cancel(); tcp?.close(); udp?.close(); tcp = null; udp = null; playerId = 0; latestUdpSequence = -1; _players.value = emptyList(); _chatMessages.value = emptyList() }
    override fun close() { stopDiscovery(); closeConnection(); scope.cancel(); _state.value = ConnectionState.DISCONNECTED }
}