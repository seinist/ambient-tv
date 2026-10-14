package com.ambient.tv.companion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ambient.tv.data.files.WakeOnLan
import com.ambient.tv.vpn.WireGuardRouteController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal data class CompanionRoute(val network: Network, val address: InetAddress, val port: Int, val vpn: Boolean)

@Singleton
class CompanionConnectionTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    val store: CompanionConnectionStore,
    private val wireGuard: WireGuardRouteController,
    private val wakeOnLan: WakeOnLan,
) {
    private val connectivity get() = context.getSystemService(ConnectivityManager::class.java)

    internal suspend fun vpnRoute(peer: CompanionPeer): CompanionRoute {
        val settings = store.get(peer.fingerprint)
        require(settings.wireGuard && settings.valid())
        wireGuard.ensureForCompanion(settings.vpnHost).getOrThrow()
        @Suppress("DEPRECATION") // the companion VPN is not the default network, so activeNetwork cannot find it
        val network = connectivity.allNetworks.firstOrNull {
            connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                wireGuard.ownsCompanionNetwork(connectivity.getLinkProperties(it))
        } ?: error("VPN unavailable")
        return CompanionRoute(network, InetAddress.getByName(settings.vpnHost), settings.port.takeIf { it > 0 } ?: peer.port, true)
    }

    internal fun alive(route: CompanionRoute): Boolean =
        connectivity.getNetworkCapabilities(route.network) != null &&
            (!route.vpn || wireGuard.ownsCompanionNetwork(connectivity.getLinkProperties(route.network)) &&
                connectivity.getNetworkCapabilities(route.network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)

    internal suspend fun open(peer: CompanionPeer, route: CompanionRoute, allowWake: Boolean,
        own: (Socket) -> Unit, stage: (Int) -> Unit): Socket {
        val settings = store.get(peer.fingerprint)
        suspend fun attempt(): Socket {
            check(alive(route))
            val socket = Socket()
            try {
                own(socket)
                route.network.bindSocket(socket)
                runInterruptible(Dispatchers.IO) { socket.connect(InetSocketAddress(route.address, route.port), 2000) }
                return socket
            } catch (failure: Throwable) { socket.close(); throw failure }
        }
        try { return attempt() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: java.io.IOException) { if (!allowWake || !settings.wake) throw failure }
        stage(com.ambient.tv.R.string.companion_connection_waking)
        wake(peer, route)
        return withTimeout(45_000) {
            while (true) {
                delay(1500)
                try { return@withTimeout attempt() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: java.io.IOException) { /* Wait only for TCP, never retry a playback command. */ }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    internal suspend fun wake(peer: CompanionPeer, route: CompanionRoute) {
        val settings = store.get(peer.fingerprint)
        require(validCompanionMac(settings.mac))
        val broadcast = settings.broadcast.ifBlank {
            if (route.vpn) wireGuard.wakeTargets(settings.vpnHost).firstOrNull { it != settings.vpnHost }
                ?: error("No directed broadcast route")
            else connectivity.getLinkProperties(route.network)?.linkAddresses?.firstNotNullOfOrNull { link ->
                val address = link.address as? Inet4Address ?: return@firstNotNullOfOrNull null
                if (link.prefixLength !in 1..30) return@firstNotNullOfOrNull null
                val value = address.address.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 255) }
                val mask = (0xffffffffL shl (32 - link.prefixLength)) and 0xffffffffL
                val result = value or (mask xor 0xffffffffL)
                (3 downTo 0).joinToString(".") { ((result shr (it * 8)) and 255).toString() }
            } ?: error("No local broadcast route")
        }
        // A configured VPN wake target must actually belong to the installed directed routes.
        if (route.vpn) require(broadcast in wireGuard.wakeTargets(settings.vpnHost))
        wakeOnLan.send(settings.mac, broadcast, settings.wakePort, settings.vpnHost.takeIf { route.vpn },
            network = route.network, routeAlive = { alive(route) && (!route.vpn || broadcast in wireGuard.wakeTargets(settings.vpnHost)) })
    }

    private val settingsGate = kotlinx.coroutines.sync.Mutex()
    suspend fun save(pin: String, settings: CompanionConnectionSettings) {
        settingsGate.lock()
        try {
            require(settings.valid())
            if (settings.wireGuard) wireGuard.validateCompanionHost(settings.vpnHost)
            val previous = store.get(pin)
            store.put(pin, settings)
            try { reconcile() }
            catch (failure: Throwable) {
                withContext(NonCancellable) {
                    store.put(pin, previous)
                    runCatching { reconcile() }
                }
                throw failure
            }
        } finally { settingsGate.unlock() }
    }

    suspend fun reconcile() { wireGuard.reconcile().getOrThrow() }
}
