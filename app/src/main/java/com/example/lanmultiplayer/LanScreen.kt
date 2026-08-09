package com.example.lanmultiplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScreen(viewModel: LanViewModel) {
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    val chatMessages by viewModel.chatMessages.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val roomName by viewModel.roomName.collectAsStateWithLifecycle()
    val inviteLink by viewModel.inviteLink.collectAsStateWithLifecycle()
    val chatInput by viewModel.chatInput.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("局域网联机") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { StatusCard(state, stats) }
            item { ChatCard(chatMessages, chatInput, state == ConnectionState.CONNECTED, viewModel::setChatInput, viewModel::sendChat) }
            item { PlayersCard(players, state == ConnectionState.CONNECTED) }
            item {
                SectionCard("身份与房间") {
                    OutlinedTextField(name, viewModel::setName, label = { Text("玩家名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(roomName, viewModel::setRoomName, label = { Text("房间名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(viewModel::createRoom, modifier = Modifier.fillMaxWidth()) { Text("创建局域网房间") }
                }
            }
            item {
                SectionCard("链接加入", "适用于内网穿透或异地联机") {
                    OutlinedTextField(inviteLink, viewModel::setInviteLink, label = { Text("邀请链接") }, placeholder = { Text("lanmultiplayer://join?host=…&tcpPort=…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button(viewModel::joinInviteLink, modifier = Modifier.fillMaxWidth()) { Text("通过链接加入") }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("局域网房间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (searching) "正在搜索附近设备" else "自动发现同 Wi‑Fi 房间", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = { if (searching) viewModel.stopSearch() else viewModel.search() }) { Text(if (searching) "停止" else "搜索") }
                }
            }
            if (rooms.isEmpty()) item { EmptyRooms(searching) }
            else items(rooms, key = { "${it.name}-${it.host}-${it.tcpPort}" }) { RoomItem(it, viewModel::join) }
            message?.let { text -> item { AssistChip(onClick = viewModel::clearMessage, label = { Text(text) }) } }
        }
    }
}

@Composable private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            content()
        }
    }
}

@Composable private fun StatusCard(state: ConnectionState, stats: NetworkStats) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF2E7D32)
        ConnectionState.CONNECTING -> Color(0xFFE68A00)
        ConnectionState.FAILED -> MaterialTheme.colorScheme.error
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Card(shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("连接状态：${stateLabel(state)}", fontWeight = FontWeight.Bold)
                Text("RTT ${stats.rttMs.coerceAtLeast(0)} ms  ·  发送 ${stats.sent}  ·  接收 ${stats.received}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun ChatCard(messages: List<ChatMessage>, input: String, connected: Boolean, onInput: (String) -> Unit, onSend: () -> Unit) {
    SectionCard("房间消息", if (connected) "最多保留最近 100 条消息" else "加入房间后即可聊天") {
        if (messages.isEmpty()) Text(if (connected) "还没有消息，发一句试试。" else "未连接", style = MaterialTheme.typography.bodySmall)
        else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { messages.takeLast(8).forEach { msg -> Text("${msg.sender}：${msg.text}") } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(input, onInput, label = { Text("输入消息") }, enabled = connected, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSend, enabled = connected && input.isNotBlank()) { Text("发送") }
        }
    }
}

@Composable private fun PlayersCard(players: List<Player>, connected: Boolean) {
    SectionCard("当前房间玩家（${players.size}）") {
        when {
            !connected -> Text("加入房间后可查看玩家列表", style = MaterialTheme.typography.bodySmall)
            players.isEmpty() -> Text("正在获取玩家列表…", style = MaterialTheme.typography.bodySmall)
            else -> players.forEachIndexed { index, player -> Text("${index + 1}. ${player.name}") }
        }
    }
}

@Composable private fun RoomItem(room: Room, onJoin: (Room) -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(room.name, fontWeight = FontWeight.Bold)
                Text("${room.host}:${room.tcpPort}", style = MaterialTheme.typography.bodySmall)
                Text("${room.players}/${room.maxPlayers} 人 · ${room.mode}", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onJoin(room) }) { Text("加入") }
        }
    }
}

@Composable private fun EmptyRooms(searching: Boolean) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (searching) "正在搜索局域网房间…" else "暂无可发现房间")
            Text("也可以通过上方邀请链接加入", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun stateLabel(state: ConnectionState) = when (state) {
    ConnectionState.CONNECTED -> "已连接"
    ConnectionState.CONNECTING -> "连接中"
    ConnectionState.FAILED -> "连接失败"
    ConnectionState.DISCONNECTED -> "未连接"
}