package com.example.lanmultiplayer

import android.content.Context
import android.os.Build

/**
 * Stable integration entry point for host applications.
 *
 * Keep game code dependent on this small API instead of constructing networking
 * internals directly. Defaults are deliberately conservative for Android 6+.
 */
data class LanMultiplayerOptions(
    val gameId: String,
    val gameVersion: Int = 1,
    val enableAutoReconnect: Boolean = true,
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy()
) {
    init {
        require(gameId.isNotBlank() && gameId.length <= 64) { "gameId must be 1..64 characters" }
        require(gameId.none { it.isISOControl() }) { "gameId must not contain control characters" }
        require(gameVersion in 1..Int.MAX_VALUE) { "gameVersion must be positive" }
    }
}

/** Runtime capabilities that an app can use to choose an appropriate UI/path. */
data class LanMultiplayerCapabilities(
    val apiLevel: Int = Build.VERSION.SDK_INT,
    val supportsNsd: Boolean = true,
    val supportsNearbyWifiPermission: Boolean = Build.VERSION.SDK_INT >= 33,
    val supportsForegroundDataSync: Boolean = Build.VERSION.SDK_INT >= 29,
    val nativeSequenceAccelerationOptional: Boolean = true
)

object LanMultiplayerSdk {
    const val SDK_VERSION = "0.1.0"
    const val MIN_ANDROID_API = 23

    /** Creates a client with an application-scoped Context, safe for Activity recreation. */
    fun createClient(context: Context, options: LanMultiplayerOptions): LanClient =
        LanClient(context.applicationContext, options.gameId, options.gameVersion).also {
            it.autoReconnectEnabled = options.enableAutoReconnect
            it.reconnectPolicy = options.reconnectPolicy
        }

    fun capabilities(): LanMultiplayerCapabilities = LanMultiplayerCapabilities()

    /** Permissions that the host app may need to request before LAN discovery. */
    fun discoveryPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= 33) add("android.permission.NEARBY_WIFI_DEVICES")
    }

    /** Returns a safe display name without changing the caller's original value. */
    fun normalizePlayerName(name: String, fallback: String = "Player"): String {
        val normalized = name.trim().filterNot { it.isISOControl() }.take(32)
        return normalized.ifBlank { fallback.take(32).ifBlank { "Player" } }
    }
}

/**
 * Convenience builder for room creation. It keeps secure and trusted-LAN modes
 * explicit while leaving RoomConfig source-compatible for existing integrations.
 */
class RoomConfigBuilder(private val name: String, private val gameId: String) {
    private var gameVersion: Int = 1
    private var maxPlayers: Int = 8
    private var mode: SyncMode = SyncMode.REALTIME_STATE
    private var roomToken: String = ""

    fun gameVersion(value: Int) = apply { gameVersion = value }
    fun maxPlayers(value: Int) = apply { maxPlayers = value }
    fun mode(value: SyncMode) = apply { mode = value }
    fun secureWithToken(value: String) = apply { roomToken = value }
    fun trustedLan() = apply { roomToken = "" }

    fun build(): RoomConfig = RoomConfig(
        name = name,
        gameId = gameId,
        gameVersion = gameVersion,
        maxPlayers = maxPlayers,
        mode = mode,
        roomToken = roomToken
    )
}
