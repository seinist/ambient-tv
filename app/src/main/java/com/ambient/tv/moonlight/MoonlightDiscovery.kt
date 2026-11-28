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

/**
 * Explicit, lifecycle-bound Sunshine mDNS discovery; no cold-start multicast or background scan.
 * Older Android NSD resolvers permit only a single outstanding resolve, so findings are queued.
 */
class MoonlightDiscovery @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Suppress("DEPRECATION")
    fun browse(): Flow<MoonlightDiscoveredHost> = callbackFlow {
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var closed = false

        fun next() {
            val target = synchronized(pending) {
                if (closed || resolving) null else pending.removeFirstOrNull()?.also { resolving = true }
            } ?: return
            val resolution = runCatching {
                manager.resolveService(target, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        synchronized(pending) { resolving = false }
                        next()
                    }
                    override fun onServiceResolved(result: NsdServiceInfo) {
                        val address = result.host?.hostAddress
                        val valid = address?.let { MoonlightHostAddress.normalize(it, result.port) }
                        if (valid != null) trySend(MoonlightDiscoveredHost(result.serviceName.take(128), valid, result.port))
                        synchronized(pending) { resolving = false }
                        next()
                    }
                })
            }
            if (resolution.isFailure) {
                synchronized(pending) { resolving = false }
                next()
            }
        }

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
                synchronized(pending) {
                    if (!closed && pending.size < 64) pending.addLast(serviceInfo)
                }
                next()
            }
        }
        try {
            manager.discoverServices("_nvstream._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            close(error)
        }
        awaitClose {
            synchronized(pending) { closed = true; pending.clear() }
            runCatching { manager.stopServiceDiscovery(listener) }
        }
    }
}
