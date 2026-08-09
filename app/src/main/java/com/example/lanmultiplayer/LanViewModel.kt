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

    val name = _name.asStateFlow()
    val roomName = _roomName.asStateFlow()
    val message = _message.asStateFlow()
    val searching = _searching.asStateFlow()
    val inviteLink = _inviteLink.asStateFlow()
    val rooms = client.rooms
    val state = client.state
    val stats = client.stats
    val players = client.players

    fun setName(value: String) { _name.value = value.take(24) }
    fun setRoomName(value: String) { _roomName.value = value.take(24) }
    fun setInviteLink(value: String) { _inviteLink.value = value }

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
            server = LanServer(getApplication(), RoomConfig(_roomName.value, gameId, mode = SyncMode.REALTIME_STATE))
            runCatching { server?.start() }
                .onSuccess { _message.value = "房间已创建，正在广播" }
                .onFailure { _message.value = "创建失败：${it.message}" }
        }
    }

    fun clearMessage() { _message.value = null }

    override fun onCleared() { server?.stop(); client.close(); super.onCleared() }
}