package com.example.lanmultiplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class LanPage(val title: String, val subtitle: String) {
    DISCOVER("发现", "创建或加入局域网房间"),
    ROOM("房间", "玩家、聊天与连接状态"),
    REMOTE("异地", "通过邀请链接加入远程房间")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanScreen(viewModel: LanViewModel) {
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val transportMode by viewModel.transportMode.collectAsStateWithLifecycle()
    val players by viewModel.players.collectAsStateWithLifecycle()
    val chatMessages by viewModel.roomChatMessages.collectAsStateWithLifecycle()
    val name by viewModel.name.collectAsStateWithLifecycle()
    val roomName by viewModel.roomName.collectAsStateWithLifecycle()
    val inviteLink by viewModel.inviteLink.collectAsStateWithLifecycle()
    val chatInput by viewModel.chatInput.collectAsStateWithLifecycle()
    val isHosting by viewModel.isHosting.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var pageIndex by remember { mutableIntStateOf(0) }
    val pages = LanPage.entries

    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("LAN MULTIPLAYER", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(pages[pageIndex].subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ConnectionBanner(state, stats, isHosting, transportMode)
            TabRow(selectedTabIndex = pageIndex) {
                pages.forEachIndexed { index, page ->
                    Tab(selected = pageIndex == index, onClick = { pageIndex = index }, text = { Text(page.title) })
                }
            }
            message?.let { Notice(it, viewModel::clearMessage) }
            when (pages[pageIndex]) {
                LanPage.DISCOVER -> DiscoverPage(name, roomName, rooms, searching, state, isHosting, viewModel::setName, viewModel::setRoomName, viewModel::createRoom, viewModel::search, viewModel::stopSearch, viewModel::join)
                LanPage.ROOM -> RoomPage(state, isHosting, players, chatMessages, chatInput, viewModel::setChatInput, viewModel::sendChat)
                LanPage.REMOTE -> RemotePage(inviteLink, state, viewModel::setInviteLink, viewModel::joinInviteLink)
            }
        }
    }
}

@Composable private fun ConnectionBanner(state: ConnectionState, stats: NetworkStats, isHosting: Boolean, transportMode: TransportMode) {
    val color = when {
        isHosting -> MaterialTheme.colorScheme.primary
        state == ConnectionState.CONNECTED -> Color(0xFF247A3D)
        state == ConnectionState.CONNECTING -> Color(0xFFE68A00)
        state == ConnectionState.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val status = if (isHosting) "正在主持房间" else stateLabel(state)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(status, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(connectionDetail(state, stats, isHosting, transportMode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state == ConnectionState.CONNECTING) Text("请稍候", style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

private fun connectionDetail(state: ConnectionState, stats: NetworkStats, isHosting: Boolean, transportMode: TransportMode): String = when {
    isHosting -> "本机正在等待玩家加入"
    state == ConnectionState.CONNECTED && transportMode == TransportMode.DUAL_CHANNEL -> "TCP 控制 + UDP 实时 · RTT ${stats.rttMs.coerceAtLeast(0)} ms · UDP 丢包 ${"%.1f".format(stats.udpLossPercent)}%"
state == ConnectionState.CONNECTED && transportMode == TransportMode.TCP_ONLY -> "UDP 不可达，已降级为全 TCP 同步（建议限制实时同步频率）"
    state == ConnectionState.CONNECTED && transportMode == TransportMode.UDP_ONLY -> "仅 UDP 模式：可靠消息、聊天和实时同步均通过 UDP"
    state == ConnectionState.FAILED -> "连接已中断，请返回发现页重试"
    state == ConnectionState.CONNECTING -> "正在检测 TCP 与 UDP 通道"
    else -> "创建房间、搜索局域网，或使用邀请链接"
}

@Composable private fun DiscoverPage(name: String, roomName: String, rooms: List<Room>, searching: Boolean, state: ConnectionState, isHosting: Boolean, onName: (String) -> Unit, onRoomName: (String) -> Unit, onCreateRoom: () -> Unit, onSearch: () -> Unit, onStopSearch: () -> Unit, onJoin: (Room) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionCard("开始联机", "先设置身份；创建房间后，其他设备可在同一 Wi‑Fi 下发现。") {
            OutlinedTextField(name, onName, label = { Text("玩家名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(roomName, onRoomName, label = { Text("新房间名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onCreateRoom, enabled = name.isNotBlank() && roomName.isNotBlank() && !isHosting, modifier = Modifier.fillMaxWidth()) { Text(if (isHosting) "当前正在主持房间" else "创建局域网房间") }
            if (state == ConnectionState.CONNECTED && !isHosting) Text("已作为客户端加入其他房间。创建新房间前建议先离开当前会话。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("可发现的局域网房间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (searching) "正在通过 NSD / mDNS 搜索附近房间" else "仅显示相同游戏版本的同网段房间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { if (searching) onStopSearch() else onSearch() }) { Text(if (searching) "停止搜索" else "搜索房间") }
        } }
        if (rooms.isEmpty()) item { EmptyRooms(searching) }
        else items(rooms, key = { "${it.name}-${it.host}-${it.tcpPort}" }) { room -> RoomItem(room, state != ConnectionState.CONNECTING, onJoin) }
    }
}

@Composable private fun RoomPage(state: ConnectionState, isHosting: Boolean, players: List<Player>, messages: List<ChatMessage>, chatInput: String, onChatInput: (String) -> Unit, onSend: () -> Unit) {
    val active = isHosting || state == ConnectionState.CONNECTED
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionCard("房间概览", if (active) "联机功能已就绪" else "尚未进入房间") {
            if (!active) Text("请前往“发现”创建/加入局域网房间，或在“异地”使用邀请链接。", style = MaterialTheme.typography.bodyMedium)
            else {
                MetricRow("玩家", "${players.size} 人"); HorizontalDivider()
                MetricRow("传输", if (isHosting) "房主服务运行中" else "TCP 控制 + UDP 实时"); HorizontalDivider()
                MetricRow("状态", if (isHosting) "等待其他玩家" else stateLabel(state))
            }
        } }
        item { SectionCard("玩家", if (active) "房主固定为 ID 0" else null) {
            when {
                !active -> Text("进入房间后显示玩家列表。", style = MaterialTheme.typography.bodySmall)
                players.isEmpty() -> Text("正在同步玩家列表…", style = MaterialTheme.typography.bodySmall)
                else -> players.forEach { player -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = if (player.id == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer) { Text(if (player.id == 0) "主" else "${player.id}", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.width(10.dp)); Text(player.name, style = MaterialTheme.typography.bodyLarge)
                    if (player.id == 0) Text("  房主", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                } }
            }
        } }
        item { ChatPanel(messages, chatInput, active, onChatInput, onSend) }
    }
}

@Composable private fun RemotePage(inviteLink: String, state: ConnectionState, onLink: (String) -> Unit, onJoin: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { SectionCard("通过邀请链接加入", "适用于公网 IP、域名、VPN 或同时支持 TCP/UDP 的内网穿透服务。") {
            OutlinedTextField(inviteLink, onLink, label = { Text("邀请链接") }, placeholder = { Text("lanmultiplayer://join?host=…&tcpPort=…") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Button(onJoin, enabled = inviteLink.isNotBlank() && state != ConnectionState.CONNECTING, modifier = Modifier.fillMaxWidth()) { Text("加入远程房间") }
        } }
        item { SectionCard("链接格式") {
            Text("lanmultiplayer://join?host=example.com&tcpPort=24567&udpPort=24568&token=随机短期令牌&name=远程房间", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(); Text("连接后自动选择可达通道。公网房间建议设置随机短期 token；令牌会随链接传输，勿在公开场所或长期链接中暴露。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { SectionCard("连接前检查") {
            CheckRow("确认链接中的主机名和端口来自可信房主"); CheckRow("穿透或防火墙已同时放行 TCP 与 UDP"); CheckRow("当前版本与房主的游戏版本一致"); CheckRow("不在公开链接中携带密码、令牌或隐私信息")
        } }
    }
}

@Composable private fun ChatPanel(messages: List<ChatMessage>, input: String, enabled: Boolean, onInput: (String) -> Unit, onSend: () -> Unit) {
    SectionCard("房间聊天", if (enabled) "本地保留最近 100 条" else "进入房间后可使用") {
        if (messages.isEmpty()) Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { Text(if (enabled) "暂无消息，发一句试试。" else "尚未连接房间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else messages.takeLast(12).forEach { message -> Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) { Text(message.sender, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(message.text, style = MaterialTheme.typography.bodyMedium) } }
        HorizontalDivider()
        OutlinedTextField(input, onInput, enabled = enabled, label = { Text("输入消息") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onSend, enabled = enabled && input.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("发送消息") }
    }
}

@Composable private fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) { Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold); subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; content() } }
}

@Composable private fun RoomItem(room: Room, enabled: Boolean, onJoin: (Room) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onJoin(room) }, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(room.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${room.players}/${room.maxPlayers} 人 · ${modeLabel(room.mode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${room.host}:${room.tcpPort}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
        Spacer(Modifier.width(12.dp)); Button(onClick = { onJoin(room) }, enabled = enabled) { Text("加入") }
    } }
}

@Composable private fun EmptyRooms(searching: Boolean) { Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) { Text(if (searching) "正在搜索附近房间…" else "还没有发现房间", fontWeight = FontWeight.SemiBold); Text(if (searching) "请保持在同一 Wi‑Fi，并等待几秒。" else "点击“搜索房间”，或创建自己的局域网房间。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun Notice(text: String, onDismiss: () -> Unit) { Surface(color = MaterialTheme.colorScheme.secondaryContainer) { Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) { Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton(onClick = onDismiss) { Text("知道了") } } } }
@Composable private fun MetricRow(label: String, value: String) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, fontWeight = FontWeight.SemiBold) }
@Composable private fun CheckRow(text: String) = Row(verticalAlignment = Alignment.Top) { Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp)); Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall) }
private fun modeLabel(mode: SyncMode) = when (mode) { SyncMode.RELIABLE -> "可靠同步"; SyncMode.REALTIME_STATE -> "实时状态"; SyncMode.LOCKSTEP -> "帧同步"; SyncMode.CUSTOM -> "自定义" }
private fun stateLabel(state: ConnectionState) = when (state) { ConnectionState.CONNECTED -> "已连接"; ConnectionState.CONNECTING -> "连接中"; ConnectionState.FAILED -> "连接失败"; ConnectionState.DISCONNECTED -> "未连接" }