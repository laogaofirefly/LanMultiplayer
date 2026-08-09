package com.example.lanmultiplayer

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class LanClient(context: Context, private val gameId: String, private val gameVersion: Int = 1) : LanMultiplayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discovery = NsdDiscovery(context)
    private var discoveryJob: Job? = null
    private var tcp: TcpSession? = null
    private var udp: UdpSession? = null
    private var receiveJob: Job? = null
    private var udpJob: Job? = null
    private val sequence = AtomicInteger()
    private var playerId = 0

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
        closeConnection(); _state.value = ConnectionState.CONNECTING
        return runCatching {
            val session = TcpSession.connect(room.host, room.tcpPort)
            tcp = session; udp = UdpSession(room.host, room.udpPort)
            session.send(Protocol.HELLO, playerName.take(32).toByteArray())
            val welcome = withTimeout(5000) { session.receive() }
            require(welcome.type == Protocol.HELLO && welcome.payload.size >= 4)
            playerId = ByteBuffer.wrap(welcome.payload).int
            udp?.send(Protocol.HELLO, sequence.getAndIncrement(), 0, ByteBuffer.allocate(4).putInt(playerId).array())
            _state.value = ConnectionState.CONNECTED
            receiveJob = scope.launch { tcpLoop(session) }
            udpJob = scope.launch { udpLoop() }
            true
        }.getOrElse { closeConnection(); _state.value = ConnectionState.FAILED; false }
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
                    else -> _reliable.emit(message)
                }
                _stats.value = _stats.value.copy(received = _stats.value.received + 1)
            }
        } catch (_: Exception) {
            if (_state.value == ConnectionState.CONNECTED) _state.value = ConnectionState.FAILED
        }
    }

    private suspend fun udpLoop() {
        while (scope.isActive) { val p = udp?.receive() ?: continue; _realtime.emit(NetworkMessage(p.type, p.payload)); _stats.value = _stats.value.copy(received = _stats.value.received + 1) }
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

    private fun closeConnection() { receiveJob?.cancel(); udpJob?.cancel(); tcp?.close(); udp?.close(); tcp = null; udp = null; playerId = 0; _players.value = emptyList(); _chatMessages.value = emptyList() }
    override fun close() { stopDiscovery(); closeConnection(); scope.cancel(); _state.value = ConnectionState.DISCONNECTED }
}