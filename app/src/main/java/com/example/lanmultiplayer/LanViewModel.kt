package com.example.lanmultiplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class LanViewModel(app: Application) : AndroidViewModel(app) {
    private val gameId = "demo-game"
    private val client = LanClient(app, gameId)
    private var discoveryJob: Job? = null
    private var server: LanServer? = null

    private val _name = MutableStateFlow("Player")
    private val _roomName = MutableStateFlow("我的房间")
    private val _message = MutableStateFlow<String?>(null)
    private val _searching = MutableStateFlow(false)
    private val _inviteLink = MutableStateFlow("")
    private val _chatInput = MutableStateFlow("")

    val name = _name.asStateFlow()
    val roomName = _roomName.asStateFlow()
    val message = _message.asStateFlow()
    val searching = _searching.asStateFlow()
    val inviteLink = _inviteLink.asStateFlow()
    val chatInput = _chatInput.asStateFlow()
    val chatMessages = client.chatMessages
    val rooms = client.rooms
    val state = client.state
    val stats = client.stats
    private val _hostPlayers = MutableStateFlow<List<Player>>(emptyList())
    private val _hostChatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val players = combine(client.players, _hostPlayers) { clientPlayers, hostPlayers ->
        if (hostPlayers.isNotEmpty()) hostPlayers else clientPlayers
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val roomChatMessages = combine(client.chatMessages, _hostChatMessages, _hostPlayers) { clientMessages, hostMessages, hostPlayers ->
        if (hostPlayers.isNotEmpty()) hostMessages else clientMessages
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val isHosting = _hostPlayers.map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setName(value: String) { _name.value = value.take(24) }
    fun setRoomName(value: String) { _roomName.value = value.take(24) }
    fun setInviteLink(value: String) { _inviteLink.value = value }
    fun setChatInput(value: String) { _chatInput.value = value.take(300) }

    fun sendChat() {
        val text = _chatInput.value.trim()
        if (text.isEmpty()) return
        val activeServer = server
        val hosting = activeServer != null && _hostPlayers.value.isNotEmpty()
        if (!hosting && client.state.value != ConnectionState.CONNECTED) {
            _message.value = "请先加入房间后再发送消息"
            return
        }
        viewModelScope.launch {
            if (hosting) activeServer?.sendHostChat(text)
            else client.sendChat(text)
            _chatInput.value = ""
        }
    }

    fun search() {
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            _searching.value = true
            runCatching { client.startDiscovery() }
            kotlinx.coroutines.awaitCancellation()
        }
    }

    fun stopSearch() { discoveryJob?.cancel(); discoveryJob = null; client.stopDiscovery(); _searching.value = false }

    fun join(room: Room) {
        viewModelScope.launch {
            val ok = client.join(room, _name.value)
            _message.value = if (ok) "已加入：${room.name}" else "加入失败，请检查 Wi-Fi 和房间状态"
        }
    }

    fun openInviteLink(link: String) {
        _inviteLink.value = link
        joinInviteLink()
    }

    fun joinInviteLink() {
        val room = InviteLink.parse(_inviteLink.value, gameId)
        if (room == null) {
            _message.value = "链接无效。示例：lanmultiplayer://join?host=example.com&tcpPort=1234&udpPort=1235"
            return
        }
        join(room)
    }

    fun createRoom() {
        viewModelScope.launch {
            server?.stop()
            _hostPlayers.value = emptyList()
            _hostChatMessages.value = emptyList()
            server = LanServer(getApplication(), RoomConfig(_roomName.value, gameId, mode = SyncMode.REALTIME_STATE), _name.value)
            runCatching { server?.start() }
                .onSuccess {
                    server?.let { activeServer ->
                        viewModelScope.launch { activeServer.players.collect { _hostPlayers.value = it } }
                        viewModelScope.launch { activeServer.chatMessages.collect { _hostChatMessages.value = it } }
                    }
                    _message.value = "房间已创建，正在广播"
                }
                .onFailure { _message.value = "创建失败：${it.message}" }
        }
    }

    fun clearMessage() { _message.value = null }

    override fun onCleared() { server?.stop(); client.close(); super.onCleared() }
}