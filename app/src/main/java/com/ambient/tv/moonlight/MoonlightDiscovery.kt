package com.ambient.tv.moonlight

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class MoonlightDiscoveredHost(val name: String, val host: String, val port: Int)

/** Explicit, lifecycle-bound Sunshine mDNS discovery; no cold-start multicast or background scan. */
class MoonlightDiscovery @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    fun browse(): Flow<MoonlightDiscoveredHost> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) { close() }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("Moonlight discovery unavailable (" + errorCode + ")"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.trimEnd('.').equals("_nvstream._tcp", ignoreCase = true)) return
                runCatching {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(result: NsdServiceInfo) {
                            val host = result.host?.hostAddress ?: return
                            val normalized = MoonlightHostAddress.normalize(host, result.port) ?: return
                            trySend(MoonlightDiscoveredHost(result.serviceName.take(128), normalized, result.port))
                        }
                    })
                }
            }
        }
        try {
            manager.discoverServices("_nvstream._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            close(error)
        }
        awaitClose { runCatching { manager.stopServiceDiscovery(listener) } }
    }
}
