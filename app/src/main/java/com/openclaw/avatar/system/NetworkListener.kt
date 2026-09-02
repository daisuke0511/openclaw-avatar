package com.openclaw.avatar.system

import android.net.ConnectivityManager
import android.net.Network
import com.openclaw.avatar.events.AvatarEvent
import com.openclaw.avatar.events.EventBus

class NetworkListener(private val cm: ConnectivityManager) : ConnectivityManager.NetworkCallback() {
    private var registered = false

    override fun onAvailable(network: Network) {
        EventBus.publish(AvatarEvent.Network(available = true))
    }

    override fun onLost(network: Network) {
        EventBus.publish(AvatarEvent.Network(available = false))
    }

    fun start() {
        if (registered) return
        try {
            cm.registerDefaultNetworkCallback(this)
            registered = true
        } catch (_: Exception) {}
    }

    fun stop() {
        if (!registered) return
        try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}
        registered = false
    }
}
