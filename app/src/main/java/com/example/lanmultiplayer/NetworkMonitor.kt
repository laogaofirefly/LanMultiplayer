package com.example.lanmultiplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _available.value = true }
        override fun onLost(network: Network) { _available.value = manager.activeNetwork != null }
    }

    fun start() {
        _available.value = manager.activeNetwork != null
        manager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        runCatching { manager.unregisterNetworkCallback(callback) }
    }
}