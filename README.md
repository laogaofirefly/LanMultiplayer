# LAN Multiplayer SDK

一个以 **Android 16（API 36）** 为优先目标、兼容 **Android 6.0+（minSdk 23）** 的局域网联机示例工程。它提供局域网房间发现、TCP/UDP 通信、房间玩家列表、房间聊天以及内网穿透链接加入等基础能力。

> 这是一个可运行的网络联机原型 / SDK 示例，并不是“无需修改游戏代码即可支持所有游戏”的成品方案。游戏需要通过 `GameAdapter`、同步策略和自身业务逻辑接入输入、状态、碰撞与规则。

## 功能概览

- **局域网发现**：NSD / mDNS 自动发现同一 Wi‑Fi 下的房间。
- **双通道通信**：
  - TCP：加入房间、可靠消息、玩家列表、聊天。
  - UDP：输入、位置、实时状态等低延迟数据。
- **房间管理**：创建、搜索、加入局域网房间。
- **玩家列表**：玩家加入/退出自动刷新；房主和客户端均可查看。房主使用 ID `0`。
- **房间聊天**：TCP 广播文本消息，单条消息有长度限制，客户端最多保存最近 100 条。
- **链接加入**：支持使用域名、公网 IP 或内网穿透地址直接加入远程房间。
- **网络工具**：重连策略、心跳、RTT/抖动估算、包限流、序列窗口、压缩、快照、插值、帧同步等基础工具类。
- **Compose 示例界面**：连接状态、收发统计、聊天、玩家、创建房间、链接加入和房间列表。

## 技术方案

```text
NSD / mDNS                  TCP                         UDP
局域网服务发现     加入/聊天/可靠事件/列表       输入/位置/实时状态
      │                    │                           │
      └────────────── LanServer / LanClient ───────────┘
```

- TCP 启用 `TCP_NODELAY`，减少小包等待。
- UDP 使用二进制头与序列号，单个载荷上限约 **1200 字节**，避免 IP 分片。
- 推荐按游戏类型选择 `RELIABLE`、`REALTIME_STATE`、`LOCKSTEP` 或 `CUSTOM` 同步模式。

## 环境要求

| 项目 | 要求 |
|---|---|
| 编译 SDK | 36 |
| 目标 SDK | 36 |
| 最低 Android 版本 | Android 6.0 / API 23 |
| JDK | 17 |
| Android Gradle Plugin | 9.0.0 |
| Gradle | 9.1.0+ |

## 构建 APK

本地需要安装 Android SDK 36、Build Tools 36 与 JDK 17：

```bash
./gradlew assembleDebug
```

生成路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

仓库也配置了 GitHub Actions。推送到 `main` 后，打开仓库的 **Actions → Android Build**，在成功运行页底部下载：

```text
LanMultiplayer-debug-apk
```

## 使用流程

### 局域网联机

1. 两台设备连接到同一个 Wi‑Fi。
2. 房主设置玩家名和房间名，点击 **创建局域网房间**。
3. 客户端点击 **搜索**，发现房间后点击 **加入**。
4. 加入成功后可查看玩家列表、发送房间消息，并接入游戏同步逻辑。

### 内网穿透 / 异地联机

将服务端 TCP 与 UDP 端口通过内网穿透工具映射到公网或域名。客户端填写：

```text
lanmultiplayer://join?host=example.com&tcpPort=1234&udpPort=1235&name=我的房间
```

字段说明：

| 字段 | 必填 | 说明 |
|---|---:|---|
| `host` | 是 | 域名、公网 IP 或内网穿透地址 |
| `tcpPort` | 是 | 映射后的 TCP 端口 |
| `udpPort` | 否 | 映射后的 UDP 端口；不填时使用 `tcpPort` |
| `name` | 否 | 房间显示名称 |

如果穿透服务的 TCP/UDP 共用同一端口：

```text
lanmultiplayer://join?host=example.com&tcpPort=1234&name=远程房间
```

应用已注册 `lanmultiplayer://join` Deep Link，点击该链接打开应用时会自动尝试加入。

> 内网穿透必须正确转发 TCP 和 UDP。只有 TCP 映射时，加入与聊天可用，但依赖 UDP 的实时同步无法正常工作。

## 房间玩家与聊天

- 玩家完成 TCP `HELLO` 后，服务端广播 `PLAYER_LIST`。
- 玩家断开后，服务端再次广播列表。
- 房主不作为 TCP 客户端连接自己，因此服务端同时维护本地 `StateFlow` 供房主 UI 使用。
- 聊天使用独立 `CHAT` 消息类型：客户端发送文本 → 服务端添加发送者名称 → 广播给全部客户端。

当前示例中，房主创建房间后可看见自己和远端客户端；但房主尚未接入本地聊天发送/接收通道。若需要完整的“房主也可聊天”，建议后续让房主同时走一个本地消息总线或将房主作为 loopback 客户端接入。

## 工程结构

```text
app/src/main/java/com/example/lanmultiplayer/
├── LanClient.kt          # 客户端：发现、加入、TCP/UDP 收发
├── LanServer.kt          # 房主服务端：接入、转发、玩家广播
├── NsdDiscovery.kt       # NSD/mDNS 发现和注册
├── TcpSession.kt         # TCP 长度前缀消息会话
├── UdpSession.kt         # UDP 会话
├── Protocol.kt           # 协议常量与 UDP 编解码
├── Players.kt            # 玩家模型与玩家列表编解码
├── Chat.kt               # 聊天消息与编解码
├── InviteLink.kt         # 内网穿透邀请链接解析
├── GameSync.kt           # 游戏同步接口示例
├── Lockstep.kt           # 帧同步工具
├── StateInterpolator.kt  # 状态插值工具
├── ReconnectPolicy.kt    # 重连退避策略
├── Heartbeat.kt          # 心跳工具
├── NetworkMonitor.kt     # 网络监控
├── LanViewModel.kt       # Compose UI 状态管理
└── LanScreen.kt          # 示例 UI
```

## 协议摘要

| 类型 | 编号 | 用途 |
|---|---:|---|
| `HELLO` | 1 | 客户端握手与玩家 ID 分配 |
| `PING` / `PONG` | 2 / 3 | 心跳和延迟测量 |
| `PLAYER_LIST` | 4 | 当前房间玩家列表 |
| `CHAT` | 5 | 房间聊天 |
| `RELIABLE` | 10 | 游戏可靠消息 |
| `REALTIME` | 11 | 游戏实时 UDP 数据 |

## 当前限制与后续建议

- 未完成账户认证、房间密码、加密通信、防作弊和访问控制。
- 未完成房主迁移、权威服务器快照、UDP 可靠重传等完整机制。
- 需在真实双设备或多设备环境验证 Android 13+ 附近 Wi‑Fi 权限、NSD 生命周期、UDP 映射和网络切换。
- 当前 NSD 主要适用于局域网；异地连接应使用链接加入。
- 尚未在真实设备上量化 RTT、丢包、抖动和卡顿，不应将设计目标视为已验证的性能指标。

## License

当前仓库未声明许可证。发布或复用前，请根据用途补充 `LICENSE` 文件。