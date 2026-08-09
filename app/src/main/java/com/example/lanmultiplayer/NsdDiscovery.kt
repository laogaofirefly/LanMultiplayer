package com.example.lanmultiplayer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap

class NsdDiscovery(context: Context) {
    companion object { const val SERVICE_TYPE = "_lanmp._tcp." }
    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun discover(gameId: String): Flow<Room> = callbackFlow {
        val found = ConcurrentHashMap.newKeySet<String>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(s: String) = Unit
            override fun onDiscoveryStopped(s: String) = Unit
            override fun onServiceLost(i: NsdServiceInfo) { found.remove(i.serviceName) }
            override fun onStartDiscoveryFailed(s: String, e: Int) { close(IllegalStateException("NSD $e")) }
            override fun onStopDiscoveryFailed(s: String, e: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!found.add(info.serviceName)) return
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) { found.remove(i.serviceName) }
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        val a = r.attributes; val id = a["game"]?.toString(Charsets.UTF_8) ?: return
                        if (id != gameId) return
                        val host = r.host.hostAddress ?: return
                        trySend(Room(r.serviceName, host, r.port, a["udp"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0, id, a["ver"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 1, a["players"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0, a["max"]?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 8, runCatching { SyncMode.valueOf(a["mode"]?.toString(Charsets.UTF_8) ?: "RELIABLE") }.getOrDefault(SyncMode.RELIABLE)))
                    }
                })
            }
        }
        val lock = wifi.createMulticastLock("lanmp_nsd").apply { setReferenceCounted(false); acquire() }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsd.stopServiceDiscovery(listener) }; if (lock.isHeld) lock.release() }
    }

    fun register(config: RoomConfig, tcpPort: Int, udpPort: Int, players: Int): NsdManager.RegistrationListener {
        val info = NsdServiceInfo().apply {
            serviceName = config.name; serviceType = SERVICE_TYPE; port = tcpPort
            setAttribute("game", config.gameId); setAttribute("ver", config.gameVersion.toString()); setAttribute("udp", udpPort.toString()); setAttribute("players", players.toString()); setAttribute("max", config.maxPlayers.toString()); setAttribute("mode", config.mode.name)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) = Unit
            override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) = Unit
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); return l
    }
}