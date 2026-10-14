package com.ambient.tv.data.files

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A NAS announcing SMB, NFS, WebDAV or SFTP via mDNS/DNS-SD; only fills the form, never connects on its own. */
data class DiscoveredNas(val name: String, val host: String, val port: Int, val kind: String, val secure: Boolean = true)

/**
 * Passive DNS-SD browse for `_smb._tcp` and `_nfs._tcp` (Samba, Synology, QNAP, TrueNAS, macOS).
 * No subnet or port scanning; SMB1-only devices that only speak NetBIOS are not found.
 * Emits the growing list of resolved servers until the collector stops.
 */
fun discoverNas(context: Context): Flow<List<DiscoveredNas>> = callbackFlow {
    val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val found = linkedMapOf<String, DiscoveredNas>()
    val pending = ArrayDeque<NsdServiceInfo>(); var resolving = false

    // Pre-API-34 NsdManager resolves one service at a time: queue the rest.
    fun next(): Unit = synchronized(pending) {
        if (resolving) return
        val info = pending.removeFirstOrNull() ?: return
        resolving = true
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { synchronized(pending) { resolving = false }; next() }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                val host = serviceInfo.host?.hostAddress?.takeIf { '%' !in it }
                if (host != null) synchronized(pending) {
                    val type = serviceInfo.serviceType
                    val kind = when {
                        type.contains("_nfs") -> FileKinds.NFS
                        type.contains("_webdav") -> FileKinds.WEBDAV
                        type.contains("_sftp-ssh") -> FileKinds.SFTP
                        else -> FileKinds.SMB
                    }
                    val secure = !type.contains("_webdav._")
                    found["$kind/$host"] = DiscoveredNas(serviceInfo.serviceName, host, serviceInfo.port, kind, secure)
                    trySend(found.values.sortedBy { it.name.lowercase() })
                }
                synchronized(pending) { resolving = false }; next()
            }
        })
    }
    val listeners = listOf("_smb._tcp.", "_nfs._tcp.", "_webdavs._tcp.", "_webdav._tcp.", "_sftp-ssh._tcp.").map { type ->
        object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { synchronized(pending) { pending += serviceInfo; next() } }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }.takeIf { listener -> runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess }
    }.filterNotNull()
    awaitClose { listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } } }
}
