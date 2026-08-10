# LAN Multiplayer SDK

一个面向 **Android 局域网联机、异地直连与内网穿透接入** 的网络底座示例。项目以 Android 16（API 36）为优先目标，最低兼容 Android 6.0（API 23），提供房间发现、TCP/UDP 会话、玩家列表、聊天、邀请链接和游戏同步接入接口。

> **定位与边界**：这是通用联机底座 / SDK 原型，不是可以无修改接入任何游戏的万能联机补丁。具体游戏必须通过 `GameAdapter`、同步模式和自身业务代码接入输入、状态、碰撞、胜负判定与反作弊规则。

## 目录

- [能力概览](#能力概览)
- [架构与数据流](#架构与数据流)
- [环境与兼容性](#环境与兼容性)
- [GitHub Actions 云端构建 APK](#github-actions-云端构建-apk)
- [快速使用](#快速使用)
- [对外联机 API](#对外联机-api)
- [邀请链接与内网穿透](#邀请链接与内网穿透)
- [房主、玩家列表与聊天](#房主玩家列表与聊天)
- [游戏同步接入](#游戏同步接入)
- [协议说明](#协议说明)
- [稳定性与性能设计](#稳定性与性能设计)
- [安全边界](#安全边界)
- [工程结构](#工程结构)
- [限制与后续路线](#限制与后续路线)
- [接入规范与生命周期](#接入规范与生命周期)
- [建议的游戏数据设计](#建议的游戏数据设计)
- [真机联调步骤](#真机联调步骤)
- [故障排查](#故障排查)
- [变更与兼容策略](#变更与兼容策略)
- [开发与贡献约定](#开发与贡献约定)

## 能力概览

- **局域网发现**：使用 Android NSD / mDNS 发现同一局域网内已创建的房间。
- **双传输通道**：TCP 承担握手、聊天、玩家列表与可靠事件；UDP 承担输入、位置与实时状态。
- **房间管理**：创建房间、搜索房间、加入房间、人数上限控制。
- **玩家列表**：玩家加入/离开后自动刷新；房主固定为玩家 ID `0`，远端玩家从 `1` 开始分配。
- **房间聊天**：独立 `CHAT` 协议；服务端统一记录和广播，房主、客户端均保留最近 100 条。
- **异地接入**：支持公网 IPv4、IPv6、域名、国际化域名和内网穿透映射。
- **邀请链接**：支持 `lanmultiplayer://join?...` Deep Link，点击链接可打开应用并自动尝试加入。
- **稳定性机制**：TCP 心跳与 RTT、连接超时、短暂递增重试、UDP NAT 保活、序列窗口、Socket 缓冲优化。
- **多语言底层**：Kotlin 负责 Android / 协程 / UI，C++17/JNI 负责高确定性的无符号 UDP 序列比较，并保留 Kotlin 等价回退。

## 架构与数据流

```text
                    ┌─────────────────────────────┐
                    │        Android Compose       │
                    │ LanScreen / LanViewModel     │
                    └──────────────┬──────────────┘
                                   │ StateFlow / SharedFlow
          ┌────────────────────────┴────────────────────────┐
          │                                                  │
┌─────────▼─────────┐                              ┌─────────▼─────────┐
│    LanClient      │                              │    LanServer      │
│ 发现、加入、收发   │                              │ 建房、接入、转发   │
└───────┬─────┬─────┘                              └───────┬─────┬─────┘
        │     │                                            │     │
  NSD/mDNS   TCP                                    TCP    UDP
  房间发现  握手/聊天/列表/可靠事件                 房间控制  实时转发
        │     │                                            │     │
        └─────┴─────────────── LAN / Tunnel / WAN ─────────┴─────┘
```

### 通道职责

| 通道 | 用途 | 特性 | 适合的数据 |
|---|---|---|---|
| NSD / mDNS | 局域网服务发现 | 仅用于同网段发现 | 房间名称、端口、版本、人数 |
| TCP | 加入、玩家列表、聊天、可靠事件 | 有序、可靠、有连接状态 | UI 事件、道具使用、回合指令、聊天 |
| UDP | 实时同步 | 低延迟、可丢弃旧数据 | 按键输入、位置、朝向、状态快照 |

UDP 单包业务载荷上限为 **1200 字节**，用于尽量避开公网和移动网络下的 IP 分片风险。大包快照、资源数据或必须可靠的内容应走 TCP 或由游戏层自行拆包。

## 环境与兼容性

| 项目 | 当前配置 |
|---|---|
| `compileSdk` / `targetSdk` | 36 |
| 最低系统 | Android 6.0 / API 23 |
| JDK | 17 |
| Android Gradle Plugin | 9.0.0 |
| Gradle | 9.1.0 |
| Android NDK | `25.2.9519653` |
| CMake | `3.22.1` |
| UI | Jetpack Compose + Material 3 |
| 并发 | Kotlin Coroutines / Flow |

### 权限

Manifest 中声明了以下网络相关权限：

- `INTERNET`：TCP/UDP 通信；
- `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`：网络状态与 Wi‑Fi 信息；
- `CHANGE_WIFI_MULTICAST_STATE`：提升局域网 mDNS/多播发现兼容性；
- `NEARBY_WIFI_DEVICES`：较新 Android 版本上的附近 Wi‑Fi 场景；
- `POST_NOTIFICATIONS`：为后续前台服务或连接通知预留。

不同 Android 版本、不同 ROM 对多播、NSD、后台网络和附近设备权限的行为可能有差异，必须以真机多设备测试结果为准。

## GitHub Actions 云端构建 APK

本项目的 APK 构建统一由 **GitHub Actions** 完成，不以本地 APK 构建结果为准。

工作流文件：

```text
.github/workflows/android.yml
```

触发条件：

- 推送到 `main`；
- 向 `main` 发起 Pull Request；
- 在 GitHub Actions 页面手动运行工作流。

云端 Runner 会执行：

1. 检出仓库代码；
2. 配置 Temurin JDK 17；
3. 安装 Android SDK 36、Build Tools 36.0.0、NDK 25.2.9519653 与 CMake 3.22.1；
4. 生成 Gradle Wrapper（项目当前未固定提交根目录 `gradlew`）；
5. 执行 `./gradlew --no-daemon assembleDebug`；
6. 上传 APK Artifact。

构建成功后，在仓库 **Actions → Android Build → 对应运行记录 → Artifacts** 下载：

```text
LanMultiplayer-debug-apk
```

APK 常规输出路径为：

```text
app/build/outputs/apk/debug/app-debug.apk
```

> 每次代码调整后应先完成 `git diff --check`，再提交并推送。请以 Actions 的成功/失败状态和日志作为唯一的 APK 构建验证结论。

## 快速使用

### 局域网联机

1. 让房主和客户端加入同一个 Wi‑Fi 或可互相访问的局域网；
2. 房主填写玩家名称、房间名称，点击 **创建局域网房间**；
3. 客户端点击 **搜索**，从房间列表选择目标房间并点击 **加入**；
4. 成功后可查看玩家、收发聊天，并将游戏数据接入 `GameAdapter` / `LanClient`；
5. 离开页面或结束联机时调用 `close()` 释放客户端会话；房主调用 `LanServer.stop()` 关闭房间。

### Kotlin 创建客户端

```kotlin
val client = LanClient(
    context = context,
    gameId = "com.example.mygame",
    gameVersion = 1
)

val joined = client.join(room, playerName = "Player1")
if (joined) {
    client.sendReliable("ready".toByteArray())
    client.sendRealtime(inputBytes, frame = 120)
}
```

`gameId` 和 `gameVersion` 是兼容性边界：客户端仅应加入相同游戏标识和协议版本的房间。

### 推荐的通用接入入口

为了减少不同 Android 项目之间的接入差异，推荐使用 `LanMultiplayerSdk` 工厂，而不是直接在 Activity 中创建网络对象：

```kotlin
val options = LanMultiplayerOptions(
    gameId = BuildConfig.APPLICATION_ID,
    gameVersion = 1,
    enableAutoReconnect = true
)

val client = LanMultiplayerSdk.createClient(applicationContext, options)
val capabilities = LanMultiplayerSdk.capabilities()

// API 33+ 应先由宿主应用申请附近 Wi‑Fi 权限；API 23–32 不需要该运行时权限。
val permissions = LanMultiplayerSdk.discoveryPermissions()
```

兼容性设计：

- 工厂自动使用 `applicationContext`，避免 Activity 重建造成网络对象泄漏；
- 默认启用自动重连，可通过 `LanMultiplayerOptions` 关闭或配置退避策略；
- `capabilities()` 提供 API level、附近 Wi‑Fi 权限和前台数据同步能力信息，宿主项目可据此降级 UI 或功能；
- Native 序列比较只是可选加速，失败时会回退到 Kotlin 实现，不应把 JNI 当作接入前提；
- 不依赖 Compose，普通 Views、游戏引擎、Service、ViewModel 或其他 Kotlin/Java Android 项目均可调用核心 API；
- 游戏显示名可通过 `LanMultiplayerSdk.normalizePlayerName()` 做统一清理。

房间配置也可以使用便捷 Builder：

```kotlin
val config = RoomConfigBuilder("我的房间", BuildConfig.APPLICATION_ID)
    .gameVersion(1)
    .maxPlayers(8)
    .mode(SyncMode.REALTIME_STATE)
    .trustedLan()
    .build()

// 公网或不可信网络应改用：
// .secureWithToken(generateRandomTokenAtLeast16Chars())
```

> 当前仓库仍是 APK 示例工程。若作为正式 SDK 分发，下一步应将核心网络代码拆分为独立 Android Library module，发布 AAR，并让宿主项目自行提供 UI、Manifest 合并策略和权限请求逻辑。

## 对外联机 API

当不使用 NSD 局域网发现时，可使用 `ExternalMultiplayerApi.joinExternal()` 连接公网 IP、DNS 域名或内网穿透端点。

```kotlin
val client = LanClient(
    context = context,
    gameId = "com.example.mygame",
    gameVersion = 1
)

val connected = client.joinExternal(
    endpoint = ExternalRoomEndpoint(
        host = "play.example.com", // IPv4、IPv6 或域名
        tcpPort = 24567,
        udpPort = 24568,
        gameId = "com.example.mygame",
        gameVersion = 1,
        mode = SyncMode.REALTIME_STATE,
        connectTimeoutMs = 8_000,
        maxConnectAttempts = 3
    ),
    playerName = "Player1"
)
```

### `ExternalRoomEndpoint` 参数

| 参数 | 默认值 | 说明 |
|---|---:|---|
| `host` | 必填 | 域名、公网 IPv4 或 IPv6；国际化域名会转换为 ASCII。IPv6 可使用带或不带 `[]` 的形式。 |
| `tcpPort` | 必填 | TCP 映射端口，范围 `1..65535`。 |
| `udpPort` | `tcpPort` | UDP 映射端口，范围 `1..65535`。 |
| `gameId` | 必填 | 必须与 `LanClient` 的 `gameId` 相同。 |
| `gameVersion` | `1` | 必须与客户端版本相同。 |
| `mode` | `REALTIME_STATE` | 同步模式描述。 |
| `connectTimeoutMs` | `8000` | 单次 TCP 建连超时，范围 `1000..30000` ms。 |
| `maxConnectAttempts` | `3` | 总尝试次数，范围 `1..5`；失败后以短暂递增退避继续尝试。 |

`joinExternal()` 会先校验端点参数、游戏 ID、版本和玩家名；之后再执行主机名规范化、TCP 连接、握手和 UDP 初始化。

## 邀请链接与内网穿透

邀请链接格式：

```text
lanmultiplayer://join?host=example.com&tcpPort=1234&udpPort=1235&name=远程房间
```

| 字段 | 必填 | 说明 |
|---|---:|---|
| `host` | 是 | 映射后的域名、IP 或穿透地址。 |
| `tcpPort` | 是 | 映射后的 TCP 端口。 |
| `udpPort` | 否 | 映射后的 UDP 端口；不填时使用 TCP 端口。 |
| `name` | 否 | UI 中显示的房间名称。 |

若穿透服务允许 TCP/UDP 同端口：

```text
lanmultiplayer://join?host=example.com&tcpPort=1234&name=远程房间
```

应用已注册 `lanmultiplayer://join` Deep Link。点击链接打开应用后会自动解析并尝试加入。

### 部署检查清单

1. 房主设备上的 TCP 监听端口和 UDP 监听端口都必须可被穿透服务访问；
2. 路由器端口转发、云防火墙和系统防火墙必须同时放行对应 **TCP 与 UDP**；
3. 客户端必须使用穿透后暴露的公网主机名/IP 与端口，而不是房主的内网地址；
4. TCP 可用但 UDP 未映射时：加入、玩家列表、聊天可用；实时同步不可用；
5. SDK 会每 15 秒发送 UDP `HELLO` 保活/注册包，帮助维持 NAT 映射，并让服务端重新学习客户端地址变化；
6. 某些对称 NAT、企业网络、运营商网络或仅 TCP 的隧道不会支持 UDP 直通，需要改用支持 UDP 的 VPN、隧道或中继架构。

## 房主、玩家列表与聊天

### 房主模型

房主不是“连接到自己的客户端”。它直接运行 `LanServer`：

- 房主玩家固定 ID 为 `0`；
- 远端 TCP 客户端从 ID `1` 开始分配；
- 服务端在玩家完成 `HELLO` 或断开时更新本地 `players StateFlow`；
- 房主 UI 订阅服务端状态，客户端 UI 订阅 `LanClient` 状态。

这种设计避免房主为显示自身状态再建立一条 loopback TCP 连接，也避免房主列表为空的问题。

### 聊天模型

```text
远端客户端发送 CHAT
        ↓
LanServer 校验、附加发送者名称、写入房主本地聊天流
        ↓
广播 CHAT 给所有已连接远端客户端
        ↓
房主 UI 使用服务端聊天流；客户端 UI 使用 TCP 聊天流
```

- 客户端聊天文本会先 trim，并限制为最多约 300 字符 / 500 UTF‑8 字节；
- 服务端会再次规范化文本，避免超大文本进入广播路径；
- 房主发送消息走 `LanServer.sendHostChat()`，写入本地记录后广播给远端客户端；
- 所有 UI 侧最多保留最近 100 条消息，界面默认显示最近 8 条；
- 聊天独立于 UDP 实时同步，不会与游戏状态包混传。

## 游戏同步接入

### 同步模式

| 模式 | 适用游戏 | 典型策略 |
|---|---|---|
| `RELIABLE` | 回合制、卡牌、棋类 | 所有操作走 TCP，服务端或房主确认顺序。 |
| `REALTIME_STATE` | 动作、赛车、射击原型 | 高频输入/状态走 UDP，关键事件走 TCP。 |
| `LOCKSTEP` | RTS、确定性模拟 | 同步每帧输入，确保各端模拟确定性一致。 |
| `CUSTOM` | 特殊网络模型 | 由游戏自行定义序列化、快照和同步策略。 |

### `GameAdapter`

```kotlin
class MyGameAdapter : GameAdapter {
    override fun encodeInput(): ByteArray {
        // 将本地输入序列化为紧凑二进制数据。
        return byteArrayOf()
    }

    override fun onReliableMessage(message: NetworkMessage) {
        // 处理可靠事件，例如开始、暂停、道具、回合操作。
    }

    override fun onRealtimeMessage(message: NetworkMessage) {
        // 处理实时输入/状态。
    }

    override fun applySnapshot(payload: ByteArray) {
        // 应用状态快照，可配合插值减少视觉抖动。
    }

    override fun simulateFrame(inputs: List<ByteArray>) {
        // 帧同步或本地预测模拟。
    }
}
```

`GameSyncEngine` 在 `Dispatchers.Default` 上运行实时节拍：

- TickRate 会限制在 `1..120`；
- 使用 `System.nanoTime()` 单调时钟而不是墙上时间；
- 使用绝对下一帧时间调度，减少累积漂移；
- 出现严重延迟时会重新同步节拍，避免持续补帧导致 CPU / 网络压力失控；
- 本地预测扩展点为 `GameAdapter.predictInput()`。

> 网络 SDK 不保证游戏逻辑的确定性。LOCKSTEP 模式需要游戏自行避免随机数差异、浮点误差、帧率依赖、物理引擎跨设备差异等问题。

## 协议说明

### TCP 帧

TCP 使用长度前缀帧：

```text
[4 bytes: big-endian body length][1 byte: message type][N bytes: payload]
```

- 单帧最大 body 长度为 `1 MiB`；
- 发送端使用同步锁保护输出流，防止多个协程写入时帧交错；
- TCP 连接启用 `TCP_NODELAY`、`keepAlive`，并设置较大的收发缓冲区。

### UDP 帧

```text
[magic:2][version:1][type:1][sequence:4][frame:4][payloadLength:2][payload:N]
```

| 字段 | 说明 |
|---|---|
| `magic` | 固定 `LG` 标识，用于过滤非本协议数据。 |
| `version` | 当前协议版本。 |
| `type` | 消息类型。 |
| `sequence` | 32 位无符号序列号，用于过滤旧包、重复包和乱序包。 |
| `frame` | 游戏逻辑帧号。 |
| `payloadLength` | 载荷长度，必须与实际剩余字节严格一致。 |

### 消息类型

| 类型 | 编号 | 传输层 | 用途 |
|---|---:|---|---|
| `HELLO` | 1 | TCP / UDP | TCP 首次握手、UDP 地址注册与 NAT 保活。 |
| `PING` | 2 | TCP | 心跳探测，携带时间戳。 |
| `PONG` | 3 | TCP | 心跳响应，用于 RTT 计算。 |
| `PLAYER_LIST` | 4 | TCP | 当前房间玩家列表。 |
| `CHAT` | 5 | TCP | 聊天广播。 |
| `RELIABLE` | 10 | TCP | 游戏可靠消息。 |
| `REALTIME` | 11 | UDP | 游戏实时输入或状态。 |

## 稳定性与性能设计

### TCP 会话

- 客户端 TCP 连接超时可配；局域网常用较短值，对外端点默认 8 秒；
- 对外连接默认最多尝试 3 次，使用 300ms、600ms 等短暂递增退避；
- 服务端要求新连接在 5 秒内发送 `HELLO`；
- 服务端 Socket 读取超时为 30 秒；
- 客户端每 2 秒发送 `PING`；连续 8 秒未收到 `PONG` 会进入 `FAILED` 并释放会话；
- PONG 返回原始时间戳，客户端可计算 RTT 并显示在 UI 状态卡中。

### UDP 会话

- UDP 载荷上限 1200 字节，协议解析进行 Magic、Version、长度与尾部字节严格校验；
- 客户端 UDP socket 连接到解析后的远端地址，内核可优先丢弃非目标来源数据报；
- 接收缓冲区复用，降低高频包下的临时对象分配和 GC 抖动；
- 客户端和服务端均使用序列窗口过滤重复、乱序、过期包；
- 序列号采用 32 位无符号半区间规则，可正确跨越 `0xFFFFFFFF -> 0` 回绕；
- UDP `HELLO` 可更新服务端记录的客户端公网地址，辅助 NAT 映射变化后的恢复。

### Kotlin / C++ 分工

| 层 | 技术 | 职责 |
|---|---|---|
| Android 应用层 | Kotlin + Compose | UI、生命周期、状态展示和输入。 |
| 并发与会话层 | Kotlin Coroutines / Flow | I/O 协程、取消、状态流、事件流、心跳和重试。 |
| 协议与网络层 | Kotlin / Java Socket API | TCP 长度帧、UDP 编解码、输入校验和 Socket 配置。 |
| 性能敏感原语 | C++17 + JNI | 无符号 32 位 UDP 序列比较。 |
| 构建层 | CMake + Android NDK | 编译并打包 `lanmultiplayer_native`。 |

`NativeSequenceWindow` 会优先加载 Native 库。Native 库在某 ABI、厂商 ROM 或打包裁剪条件下加载失败时，会使用 Kotlin `UInt` 的同算法回退，因此不会因为 JNI 加载失败直接崩溃。

## 安全边界

当前工程已加入显式安全策略、TLS-PSK TCP、连接/消息限流、地址失败封禁和可插拔输入校验，但**仍不等同于生产级公网对战安全方案**。安全远程房间必须使用 16 字符以上随机 roomToken；空 token 仅代表显式的可信局域网模式。普通 UDP 仍不会在安全房间启用，直到 DTLS-PSK 完成实际接入。

### 已实现的基础保护

- `gameId`、`gameVersion`、主机名、端口、玩家名和连接参数校验；
- 玩家名长度限制和控制字符拒绝；
- 服务端 `maxPlayers` 限制，满员后拒绝新 TCP 连接；
- TCP 首包 HELLO 时限、读取超时和包长度上限；
- UDP Magic / 版本 / 长度检查、包尺寸上限和序列过滤；
- 客户端已连接 UDP socket 的来源过滤；
- 域名国际化 ASCII 规范化；
- 异常时关闭 socket、取消会话协程，避免大部分资源泄漏。

### 已加入但仍需云端构建和协议接入验证的基础组件

- `SecurityMode`、连接/消息限流、地址失败封禁；
- `GameRuleValidator` 服务端校验接口；
- `AuthenticatedEnvelope` HMAC/时间窗/序列号工具；
- `ReconnectCoordinator` 自动重连编排工具；
- `RoomSnapshot` 与 `RoomEpoch` 恢复/迁移接口骨架；
- Foreground Service 房主后台运行基础。

这些组件目前不等于完整公网安全、自动重连或房主迁移功能，必须继续接入主状态机并通过 GitHub Actions 验证。本地仅进行源码与静态检查，不执行 Gradle 或其他编译命令。

### 尚未实现的安全能力

- 账户认证、中心化身份和访问控制列表；
- DTLS-PSK 实际 UDP transport、STUN/TURN 和公网中继；
- 房主迁移、服务端权威模拟、回滚和完整反作弊；
- 真机 RTT、丢包、功耗、Doze 和厂商 ROM 兼容性数据；
- 生产级 DDoS 防护和中心化审计。

### 公网使用建议

原始 TCP/UDP 载荷当前为明文。不要在不可信网络直接传递账号令牌、隐私数据或高价值游戏资产。

推荐优先使用：

- WireGuard；
- Tailscale；
- 有访问控制与加密能力的内网穿透服务；
- 自建 VPN 或加密隧道。

生产化时应补充 TLS TCP、经过认证的握手、短期令牌、UDP 完整性校验与权威服务端架构。

## 工程结构

```text
.github/workflows/android.yml                    # GitHub Actions 云端 APK 构建
app/src/main/
├── AndroidManifest.xml                          # 权限与 Deep Link
└── java/com/example/lanmultiplayer/
    ├── LanClient.kt                             # 客户端发现、加入、TCP/UDP 收发
    ├── LanServer.kt                             # 房主服务端、接入、广播、UDP 转发
    ├── LanViewModel.kt                          # 房主/客户端状态整合
    ├── LanScreen.kt                             # Compose 示例界面
    ├── NsdDiscovery.kt                          # NSD/mDNS 注册与发现
    ├── TcpSession.kt                            # TCP 长度前缀会话
    ├── UdpSession.kt                            # 已连接 UDP 会话
    ├── Protocol.kt                              # UDP 编解码和协议常量
    ├── Models.kt                                # Room、连接状态、GameAdapter 等模型
    ├── Players.kt                               # 玩家列表编解码
    ├── Chat.kt                                  # 聊天消息编解码
    ├── InviteLink.kt                            # 邀请链接解析
    ├── ExternalMultiplayerApi.kt                # 公网 / 穿透接入 API
    ├── GameSync.kt                              # 同步循环与预测扩展
    ├── Lockstep.kt                              # 帧同步工具
    ├── StateInterpolator.kt                     # 状态插值工具
    ├── ReconnectPolicy.kt                       # 重连退避策略
    ├── Heartbeat.kt                             # 心跳工具
    ├── NetworkMonitor.kt                        # 网络状态监控
    ├── SequenceWindow.kt                        # 多玩家 UDP 序列窗口
    └── NativeSequenceWindow.kt                  # JNI 封装与 Kotlin 回退
native/
├── CMakeLists.txt                               # Native 构建配置
└── src/main/cpp/sequence_window.cpp             # C++17 序列回绕比较
```

## 限制与后续路线

当前工程仍是 SDK 原型。以下项目是**明确未完成的能力或尚未得到真实设备验证的结论**，不得将 README 中的设计说明理解为已完成的生产能力：

1. **安全与访问控制未完成**：尚未实现账户认证、房间密码、加密通信、防作弊、访问控制列表、会话令牌、消息完整性校验和 TLS/DTLS。
2. **完整联机容错机制未完成**：尚未实现自动重连编排、房主迁移、权威服务器快照、UDP 可靠重传、选择确认、丢包率遥测等完整机制。当前仅有基础心跳、短暂连接重试、序列窗口和 UDP NAT 保活工具。
3. **Android 真机兼容性尚未完成验证**：必须在真实双设备及多设备环境验证 Android 13+ 的附近 Wi‑Fi 权限、NSD 生命周期、多播/客户端隔离、UDP 映射、应用前后台切换和网络切换行为。
4. **发现范围有限**：当前 NSD / mDNS 主要用于局域网发现，不应作为异地房间目录或公网发现方案；异地连接应通过邀请链接、`joinExternal()`、VPN 或安全隧道明确指定端点。
5. **性能指标尚未量化**：尚未在真实设备上形成 RTT、丢包、抖动、卡顿、GC、CPU、耗电和弱网恢复的基准数据。因此，任何目标 TickRate、缓冲区大小、心跳间隔或协议设计都只是实现层面的默认策略，不是已经验证的性能承诺。
6. **NAT 与公网覆盖不完整**：对称 NAT、运营商 NAT、IPv6-only、双栈、复杂企业网络和网络切换均可能导致 UDP 失效；应在实际网络中逐项验证，必要时使用支持 UDP 的 VPN、隧道或中继服务。
7. **后台托管未产品化**：长时间作为房主运行时，仍需要按 Android 平台规则接入前台服务、通知、电源策略和厂商后台限制适配。
8. **游戏业务仍需自行实现**：输入编码、快照格式、状态插值、权威判定、回放、作弊检测和具体规则必须由接入游戏负责。

## 接入规范与生命周期

### 客户端生命周期

`LanClient` 是带有内部协程作用域和 Socket 资源的长生命周期对象。建议由 `ViewModel`、游戏会话管理器或前台服务持有，而不是在每次 Compose 重组、每一帧更新或每次按钮点击时重新创建。

推荐流程：

```text
创建 LanClient
    ↓
可选：startDiscovery() → 收集 rooms
    ↓
join(room, playerName) 或 joinExternal(endpoint, playerName)
    ↓
收集 state / stats / reliableMessages / realtimeMessages
    ↓
运行 GameSyncEngine（需要持续实时同步时）
    ↓
结束会话：GameSyncEngine.stop() → LanClient.close()
```

关键约束：

- `startDiscovery()` 会先停止已有发现任务再开启新的 NSD 搜索；`stopDiscovery()` 会取消搜索并清空当前发现房间；
- `join()` 会先关闭旧 TCP/UDP 会话，然后进入 `CONNECTING`；连接成功后才转为 `CONNECTED`；
- 连接、握手或心跳失败时状态会转为 `FAILED`；此时应由业务层提示用户重试或重新搜索房间；
- `close()` 会关闭 TCP、UDP、发现任务及内部协程，并将状态设为 `DISCONNECTED`；关闭后不要继续复用该实例；
- `sendReliable()`、`sendRealtime()` 是 `suspend` API，应在协程中调用；高频调用请由游戏 Tick 统一驱动，避免 UI 事件无节制发送；
- `sendChat()` 只在客户端处于 `CONNECTED` 时发送。房主不通过 `LanClient` 给自己发消息，而通过 `LanServer.sendHostChat()` 广播。

### 订阅状态和事件

```kotlin
lifecycleScope.launch {
    client.state.collect { state ->
        when (state) {
            ConnectionState.CONNECTED -> showConnectedUi()
            ConnectionState.FAILED -> showReconnectHint()
            else -> Unit
        }
    }
}

lifecycleScope.launch {
    client.reliableMessages.collect { message ->
        when (message.type) {
            Protocol.RELIABLE -> handleReliableGameEvent(message.payload)
        }
    }
}

lifecycleScope.launch {
    client.realtimeMessages.collect { message ->
        if (message.type == Protocol.REALTIME) {
            applyRealtimeState(message.payload)
        }
    }
}
```

`state`、`rooms`、`stats` 是状态型 `StateFlow`，适合 UI 持续观察；`reliableMessages` 与 `realtimeMessages` 是事件型 `Flow`，适合由游戏逻辑单独收集。不要依赖 UI 是否可见来保证游戏关键事件一定被处理。

### 房主生命周期

```kotlin
val server = LanServer(
    context = context,
    config = RoomConfig(
        name = "周末开黑",
        gameId = "com.example.mygame",
        gameVersion = 1,
        maxPlayers = 8,
        mode = SyncMode.REALTIME_STATE
    ),
    hostPlayerName = "Host"
)

server.start()
// 收集 server.players / server.chatMessages，或接入你的房主 UI。

// 房主广播一条聊天消息：
server.sendHostChat("房间已开启")

// 结束房间：
server.stop()
```

`LanServer.start()` 是挂起函数，应在 `Dispatchers.IO` 或由 ViewModel 协程启动。服务端监听端口默认传入 `0`，由系统自动分配空闲端口；创建成功后可通过 `actualTcpPort`、`actualUdpPort` 读取实际端口。NSD 注册使用这些实际端口供局域网客户端发现。

> 若要提供给公网/穿透客户端，请在创建 `LanServer` 时传入固定 `tcpPort`、`udpPort`，并保证该端口与路由器或隧道的映射配置一致。自动分配端口只适合纯局域网发现测试。

## 建议的游戏数据设计

网络层只传输 `ByteArray`，数据模型和版本演进由游戏负责。建议为每类消息定义明确的二进制格式，并避免直接把任意大对象或 Java/Kotlin 序列化对象放入实时通道。

### 可靠事件示例

```text
[type: 1 byte][eventId: 4 bytes][actorId: 4 bytes][body: N bytes]
```

适用：开始游戏、准备状态、房间设置、回合指令、技能释放确认、战绩结算等。通过：

```kotlin
client.sendReliable(payload)
```

发送。服务端会将 `RELIABLE` 载荷转发给已连接客户端。需要服务器权威顺序、幂等去重或权限校验时，应在服务端协议之上自行实现事件 ID、确认、拒绝原因和重放保护。

### 实时输入示例

```text
[buttons: 2 bytes][moveX: 1 byte][moveY: 1 byte][aim: 2 bytes]
```

适用：按键、摇杆、方向、瞄准、移动意图。通过：

```kotlin
client.sendRealtime(inputPayload, frame = localFrame)
```

发送。UDP 包会带独立 `sequence` 和调用方给出的 `frame`。旧包会被序列窗口丢弃，所以实时消息必须设计为“最新状态覆盖旧状态”，而不是依赖每个包都到达。

### 状态快照建议

对于 `REALTIME_STATE`，可按固定频率发送权威或房主快照：

```text
[snapshotId: 4][serverFrame: 4][entityCount: 2][entities...]
```

客户端收到后应保存最近多个快照，使用 `StateInterpolator` 或游戏自身插值器按渲染时间播放。不要直接以收到 UDP 包的瞬间强制覆盖渲染位置，否则在抖动和丢包下容易出现跳动。

### 帧同步建议

LOCKSTEP 只适合满足下列前提的游戏：

- 同一帧输入能在所有设备产生一致结果；
- 随机数种子、物理参数、地图资源和脚本版本完全一致；
- 不依赖设备帧率和系统墙上时间；
- 浮点计算差异得到控制，或使用固定点/确定性算法；
- 存在落后玩家处理、输入超时、重放和断线策略。

本项目提供 `Lockstep` 工具和 `GameAdapter.simulateFrame()` 扩展点，但不自动替游戏保证确定性或实现服务端裁决。

## 真机联调步骤

### 最小双设备测试

1. 使用 GitHub Actions 成功构建的同一版本 APK 安装到两台 Android 设备；
2. 两台设备连接相同 Wi‑Fi；关闭移动数据切换、VPN 或隔离访客网络，避免路由策略干扰；
3. 在房主设备创建房间，确认状态提示创建成功；
4. 在客户端设备点击搜索，等待 NSD 返回房间；
5. 加入后检查玩家列表是否包含房主和客户端；
6. 双向发送聊天，验证房主和客户端都能立即看到消息；
7. 持续发送实时输入/状态，检查 `发送`、`接收` 计数是否增加；
8. 断开客户端 Wi‑Fi 或杀掉客户端进程，确认房主玩家列表最终移除该玩家；
9. 恢复网络后重新加入，检查旧 UDP 地址不会影响新的会话。

### 异地/隧道测试

1. 在房主侧配置同时支持 TCP 与 UDP 的映射；
2. 在不同网络下的客户端使用邀请链接加入；
3. 先验证 TCP：能否完成握手、看到玩家列表、收发聊天；
4. 再验证 UDP：实时收发统计是否增长、游戏状态是否更新；
5. 保持至少 30 秒，检查 NAT 保活后实时通道是否仍然可用；
6. 分别测试 Wi‑Fi、蜂窝网络、IPv4、IPv6（如可用）和网络切换；
7. 记录 RTT、丢包表现、设备型号、Android 版本和隧道方案，作为兼容性基线。

### 推荐验收矩阵

| 维度 | 至少覆盖的场景 |
|---|---|
| Android 版本 | API 23、API 29、API 33、API 36 或实际支持范围内的代表设备 |
| 网络 | 同一 Wi‑Fi、不同 Wi‑Fi、移动网络、Wi‑Fi ↔ 移动网络切换 |
| 地址族 | IPv4；具备条件时测试 IPv6 和双栈 |
| 房间规模 | 仅房主、2 人、接近 `maxPlayers` 上限 |
| 传输 | 聊天/TCP 可靠事件、持续 UDP 实时数据、UDP 静默后恢复 |
| 异常 | 拔网、应用进后台、强杀客户端、房主停止服务、端口未映射 |

## 故障排查

### 搜索不到局域网房间

按以下顺序检查：

1. 两台设备是否确实在同一二层网络；部分访客 Wi‑Fi 会开启客户端隔离；
2. 路由器/AP 是否允许 mDNS、多播和设备间互访；
3. 房主是否已经成功创建房间，且未立即退出页面或停止服务；
4. 应用是否获得系统要求的附近 Wi‑Fi/网络权限；
5. 是否存在 VPN、代理、企业管控、私有 DNS 或多网卡导致 NSD 服务解析到不可达地址；
6. 用邀请链接直接填入房主局域网 IP 与实际端口测试，以区分“发现失败”和“TCP 不通”。

### 显示房间但加入失败

- 确认房主和客户端的 `gameId`、`gameVersion` 一致；
- 确认玩家名非空、长度不超过 32、没有控制字符；
- 检查房间人数是否已达到 `maxPlayers`；
- 检查房主 TCP 端口是否被防火墙、安全软件或路由规则阻断；
- 对公网地址，确认 DNS 解析正确、TCP 映射端口正确，必要时增大 `connectTimeoutMs`；
- 查看客户端状态：`CONNECTING` 后进入 `FAILED` 通常意味着建连、HELLO 或心跳阶段发生异常。

### 聊天可用，但实时状态不同步

这通常意味着 TCP 正常而 UDP 不可达。请检查：

- UDP 端口是否已经映射、放行并与邀请链接的 `udpPort` 一致；
- 隧道服务是否真的支持 UDP，而非仅转发 TCP；
- NAT、运营商网络或企业网络是否拦截 UDP；
- 实时 payload 是否不超过 1200 字节；
- 调用方是否持续调用 `sendRealtime()`；
- 接收端是否正在收集 `realtimeMessages`，或 `GameSyncEngine` 是否已 `start()`；
- `frame` 是否按游戏逻辑单调递增，避免调试代码不断发送不可解释的旧帧数据。

### 房主看不到消息或不能发送

- 房主创建房间后应由 `LanServer` 管理本地聊天流，不要求房主再加入自己的房间；
- 检查 `LanViewModel` 是否订阅 `activeServer.chatMessages`；
- 房主发送应走 `activeServer.sendHostChat(text)`，而不是调用未连接的 `LanClient.sendChat()`；
- 客户端发送的 CHAT 会经服务端写入本地流，再广播给所有远端客户端；
- 若只在旧 APK 中出现问题，请使用 GitHub Actions 最新成功构建的 Artifact 重新安装并测试。

### 长时间后断线或 RTT 异常

- 检查设备是否进入省电、Doze、后台冻结或厂商任务清理；
- 检查 Wi‑Fi 信号、AP 漫游、移动网络切换和 VPN 重连；
- 客户端默认 2 秒 PING、8 秒 PONG 超时，弱网可在业务层对 `FAILED` 提示重试；
- 对公网隧道，检查隧道空闲超时、UDP 会话有效期和服务端日志；
- 长连接房主建议使用符合 Android 平台规则的前台服务，并让用户明确知道房间仍在运行。

## 变更与兼容策略

当协议或游戏载荷发生不兼容修改时，应至少执行以下操作：

1. 将 `gameVersion` 加一；
2. 保留旧版本的明确拒绝提示，而不是让旧客户端在握手后异常；
3. 对游戏业务载荷添加内部版本、事件类型和长度字段；
4. 将新旧客户端互联测试纳入 CI/真机验收；
5. 在发布说明中记录网络协议、房间配置、端口和安全行为变化；
6. 若修改 UDP 基础头格式，同时更新 `Protocol.VERSION`，不要仅依赖业务层猜测解析。

`gameVersion` 当前在房间发现与加入前校验中使用；它不是自动兼容转换器。真正的跨版本兼容需要业务协议显式设计。

## 开发与贡献约定

- 修改协议前，先说明传输层（TCP/UDP）、可靠性要求、最大尺寸和版本策略；
- 新增高频路径时避免字符串 JSON、大对象分配和主线程 I/O；
- 所有 Socket、协程和 Flow 收集都要有明确的取消/关闭路径；
- 不要把密码、Access Token、用户隐私或长期密钥放入聊天、UDP 实时包或公开邀请链接；
- 提交前运行 `git diff --check`；推送后以 GitHub Actions 云端构建结果为准；
- 新功能应至少补充一条 README 使用方式、失败行为和真机联调步骤。
## 整体代码优化说明

近期整体优化重点放在“宿主项目更容易接入、异常输入更早失败、不同 Android 设备更稳”三个方向：

- 新增 `LanMultiplayerSdk` 统一工厂和 `LanMultiplayerOptions`，避免宿主项目直接依赖内部实现；
- `RoomConfig` 增加房间名、游戏标识、版本号、人数范围校验，减少错误配置进入网络层；
- TCP/UDP 消息类型与长度边界统一校验，拒绝零类型、超大帧和非法 UDP 端口；
- 玩家名称按 UTF‑8 字节数安全截断，避免中文、表情等多字节字符被截断成无效编码；
- 保留 Native 序列比较的可选加速和 Kotlin 回退，不把 NDK/JNI 作为宿主项目的强制接入条件；
- 核心 API 不依赖 Compose，兼容普通 View、游戏引擎、Service、ViewModel 和纯 Kotlin 业务层；
- 网络资源继续采用显式 `close()`、协程取消和 application-scoped Context 管理。

所有优化仍需通过 GitHub Actions 的 Android Build、Quality Checks 和 Security Checks，并结合多款 Android 真机验证后，才能确认最终兼容性。

## GitHub Actions 工作流

项目将构建和自动检查交给 GitHub Actions 执行，**不要求本地安装 Android SDK、NDK 或 Gradle**。

工作流位于：

```text
.github/workflows/android.yml
.github/workflows/quality.yml
.github/workflows/security.yml
```

### Android Build

`Android Build` 用于编译可安装的 Debug APK：

- 触发：推送到 `main`、向 `main` 发起 Pull Request，或手动运行；
- 环境：Ubuntu、Temurin JDK 17；
- Android SDK：API 36、Build Tools 36.0.0；
- NDK：25.2.9519653；
- CMake：3.22.1；
- Gradle：9.1.0；
- 产物：`LanMultiplayer-debug-apk`；
- APK 路径：`app/build/outputs/apk/debug/app-debug.apk`。

使用方式：

1. 打开 GitHub 仓库的 **Actions** 页面；
2. 选择 **Android Build**；
3. 打开最新一次成功的运行记录；
4. 在 **Artifacts** 中下载 `LanMultiplayer-debug-apk`；
5. 解压后将 APK 安装到 Android 真机进行双设备联调。

### Quality Checks

`Quality Checks` 是代码质量与工程验证工作流，不是单独的 APK 下载任务。它会执行：

- Gradle `test`；
- Android `lint`；
- `assembleDebug` 编译验证；
- 上传 `quality-reports` 报告产物（若有）。

判断方式：

| 工作流结果 | 含义 |
|---|---|
| `Android Build` 成功 | Debug APK 已成功编译，可下载测试 |
| `Quality Checks` 成功 | 测试、Lint 和编译质量检查通过 |
| `Security Checks` 成功 | 密钥扫描及适用的依赖检查通过 |

`Android Build` 成功是“能否下载 APK”的主要判断条件；推荐三个工作流都通过后再进行真机联调。

### Security Checks

`Security Checks` 当前包括：

- Gitleaks 敏感信息扫描；
- Pull Request 上的依赖变更检查。

这些检查不能替代完整的安全审计。项目仍然不是生产级公网对战安全方案，尤其不要在未配置可信隧道或完整认证保护的情况下传输账号令牌、隐私数据或高价值资产。

### 本地构建说明

本项目的验收以 GitHub Actions 云端构建为准。开发者不需要为了验证 APK 在本地配置 Gradle、Android SDK 或 NDK；如果本地环境缺少这些组件，直接查看 Actions 日志即可，不应将本地构建失败当作云端构建结论。

## License

本项目使用 Apache-2.0 许可证，详见根目录 `LICENSE`。Bouncy Castle 等第三方依赖仍需按其各自许可证履行声明和归属义务。
