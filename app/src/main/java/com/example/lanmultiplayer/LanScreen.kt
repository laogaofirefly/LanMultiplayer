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
    val name by viewModel.name.collectAsStateWithLifecycle()
    val roomName by viewModel.roomName.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("局域网联机") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                StatusCard(state, stats)
            }
            item {
                PlayersCard(players, state == ConnectionState.CONNECTED)
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = viewModel::setName,
                    label = { Text("玩家名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("创建房间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = roomName,
                            onValueChange = viewModel::setRoomName,
                            label = { Text("房间名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(onClick = viewModel::createRoom, modifier = Modifier.fillMaxWidth()) {
                            Text("创建局域网房间")
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("可加入房间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (searching) viewModel.stopSearch() else viewModel.search() }) {
                        Text(if (searching) "停止搜索" else "搜索")
                    }
                }
            }
            if (rooms.isEmpty()) {
                item { EmptyRooms(searching) }
            } else {
                items(rooms, key = { "${it.name}-${it.host}-${it.tcpPort}" }) { room -> RoomItem(room, viewModel::join) }
            }
            message?.let { text ->
                item {
                    AssistChip(onClick = viewModel::clearMessage, label = { Text(text) })
                }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ConnectionState, stats: NetworkStats) {
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
                Text("RTT ${stats.rttMs.coerceAtLeast(0)} ms  ·  ↑${stats.sent}  ↓${stats.received}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PlayersCard(players: List<Player>, connected: Boolean) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("当前房间玩家（${players.size}）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            when {
                !connected -> Text("加入房间后可查看玩家列表", style = MaterialTheme.typography.bodySmall)
                players.isEmpty() -> Text("正在获取玩家列表…", style = MaterialTheme.typography.bodySmall)
                else -> players.forEachIndexed { index, player ->
                    Text("${index + 1}. ${player.name}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RoomItem(room: Room, onJoin: (Room) -> Unit) {
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

@Composable
private fun EmptyRooms(searching: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (searching) "正在搜索局域网房间…" else "暂无房间")
            Text("请确认设备连接到同一个 Wi-Fi", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun stateLabel(state: ConnectionState): String = when (state) {
    ConnectionState.CONNECTED -> "已连接"
    ConnectionState.CONNECTING -> "连接中"
    ConnectionState.FAILED -> "连接失败"
    ConnectionState.DISCONNECTED -> "未连接"
}