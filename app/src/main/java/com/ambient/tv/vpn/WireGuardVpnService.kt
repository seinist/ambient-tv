package com.ambient.tv.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import com.ambient.tv.R
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import dagger.hilt.android.AndroidEntryPoint
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ThreadLocalRandom
import javax.inject.Inject
import kotlin.concurrent.thread

@AndroidEntryPoint
class WireGuardVpnService : VpnService() {
    @Inject lateinit var preferences: AppPreferences
    @Inject lateinit var tokenStore: TokenStore
    @Inject lateinit var route: WireGuardRouteController
    @Inject lateinit var resumeConnectivity: ResumeConnectivityMonitor

    private val sessionId = AtomicInteger()
    @Volatile private var stopHook: (() -> Unit)? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action == ACTION_STOP) {
            sessionId.incrementAndGet(); stopTunnel(); route.note(WireGuardRouteState.Idle)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        val id = sessionId.incrementAndGet()
        stopTunnel()
        val profile = preferences.wireGuardProfile()
        val privateKey = tokenStore.wireGuardPrivateKey?.let { runCatching { decodeKey(it) }.getOrNull() }
        if (profile == null || !profile.ready() || privateKey == null) {
            route.note(WireGuardRouteState.Failed(getString(R.string.wg_missing))); stopSelf(); return START_NOT_STICKY
        }
        val preshared = tokenStore.wireGuardPresharedKey?.let { runCatching { decodeKey(it) }.getOrNull() }
        thread(name = "wireguard-route") {
            val failed = runCatching { tunnel(id, profile, privateKey, preshared) }.exceptionOrNull()
            if (sessionId.get() != id) return@thread
            if (failed != null) route.note(WireGuardRouteState.Failed(failed.message ?: getString(R.string.wg_failed)))
            stopSelf()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // System revoked VPN consent / another VPN took over — fail the route so playback can recover.
        sessionId.incrementAndGet()
        stopTunnel()
        resumeConnectivity.onVpnRevoked()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        sessionId.incrementAndGet(); stopTunnel()
        if (route.status.value !is WireGuardRouteState.Failed) route.note(WireGuardRouteState.Idle)
        super.onDestroy()
    }

    /** Only the newest tunnel thread may report its state; an older one is shutting down. */
    private fun current(id: Int) = sessionId.get() == id
    private fun note(id: Int, state: WireGuardRouteState) { if (current(id)) route.note(state) }

    private fun tunnel(id: Int, profile: WireGuardProfile, privateKey: ByteArray, preshared: ByteArray?) {
        val session = WgSession(privateKey, decodeKey(profile.peerPublicKey), preshared ?: ByteArray(32))
        // One connected socket that survives network changes and a restarting peer (see WgUdpLink).
        val link = WgUdpLink(profile.endpointHost, profile.endpointPort, ::protect)
        link.open()

        val enabledHosts = route.tunnelHosts()
        // One name that does not resolve (yet) must not take the tunnel down for every other host.
        val resolvedHosts = enabledHosts.flatMap { host -> runCatching { resolveIpv4Strict(host) }.getOrDefault(emptyList()) }.distinct()
        if (enabledHosts.isNotEmpty() && resolvedHosts.isEmpty()) error(getString(R.string.wg_host_outside))
        if (resolvedHosts.any { !hostCovered(profile.allowedIps, it) }) error(getString(R.string.wg_host_outside))

        val builder = Builder().setSession(getString(R.string.wg_session)).setMtu(profile.mtu).setBlocking(true)
            .addAddress(profile.address, profile.prefix).addAllowedApplication(packageName)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        tunnelRoutes(profile.allowedIps, resolvedHosts).forEach { cidr ->
            val parsed = parseCidr(cidr)
            builder.addRoute(intToIpv4(parsed.address and prefixMask(parsed.prefix)), parsed.prefix)
        }
        if (enabledHosts.any { runCatching { ipv4ToInt(it) }.isFailure } && profile.dns.isNotBlank() && hostCovered(profile.allowedIps, profile.dns)) {
            builder.addRoute(profile.dns, 32)
            builder.addDnsServer(profile.dns)
        }
        if (!current(id)) { link.close(); return }
        val fd = builder.establish() ?: run { link.close(); error("vpn") }
        val alive = AtomicBoolean(true)
        val hook = { alive.set(false); link.close(); runCatching { fd.close() }; Unit }
        // A newer start may have run while this one was setting up: never install (or keep) an
        // older tunnel's hook over the newer one, and stop right away instead of running twice.
        synchronized(hookLock) {
            if (!current(id)) { hook(); return }
            stopHook = hook
        }
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)

        fun send(data: ByteArray) { link.send(data) }
        val timers = WgTunnelTimers(profile.keepaliveSeconds, ::handshakeRetryDelayMs)
        send(session.initiation())
        timers.start(System.currentTimeMillis())
        note(id, WireGuardRouteState.Connecting)
        val routeState = WgRouteAnnouncer { up -> note(id, if (up) WireGuardRouteState.Up else WireGuardRouteState.Connecting) }

        // A network switch (Wi-Fi ↔ mobile, new router) leaves the connected socket on the old route:
        // rebuild it and start a handshake right away instead of waiting for the retry timer.
        val roam = AtomicBoolean(false)
        val startedAt = System.currentTimeMillis()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val networkWatch = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { roam.set(true) }
            override fun onLost(network: Network) { roam.set(true) }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { roam.set(true) }
        }
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                networkWatch,
            )
        }
        var lastRoam = 0L
        var unansweredInitiations = 0

        val reader = thread(name = "wireguard-tun") {
            // Buffers and the datagram are reused: this loop runs once per outgoing packet.
            val buffer = ByteArray(profile.mtu)
            val sealed = ByteArray(profile.mtu + 32)
            val outgoing = DatagramPacket(sealed, sealed.size)
            while (alive.get()) {
                if (!session.established) { Thread.sleep(50); continue }
                val size = runCatching { input.read(buffer) }.getOrDefault(-1)
                if (size <= 0) break
                // Padding hides the exact packet length; the receiver trims it by the IPv4 length.
                val padded = WgSession.paddedLength(size, buffer.size)
                java.util.Arrays.fill(buffer, size, padded, 0)
                val length = session.sealPacketInto(buffer, padded, sealed)
                // A lost datagram is not an error here: TCP above retransmits.
                if (length > 0) { outgoing.setData(sealed, 0, length); link.send(outgoing) }
            }
        }
        try {
            val inbound = ByteArray(65535)
            val plain = ByteArray(65535)
            val datagram = DatagramPacket(inbound, inbound.size)
            val allowed = parseAllowedIps(profile.allowedIps)
            while (alive.get() && current(id)) {
                datagram.setData(inbound, 0, inbound.size)
                val length = link.receive(datagram)
                val type = if (length >= 0) session.messageType(inbound, length) else 0
                if (type == WgSession.TYPE_TRANSPORT) {
                    // Hot path: decrypt and validate in place without per-packet copies.
                    val size = session.openPacketInto(inbound, length, plain)
                    val packet = if (size > 0) inboundIpv4Length(plain, size, allowed) else -1
                    if (packet > 0) output.write(plain, 0, packet)
                } else if (type != 0) {
                    // Handshake messages are rare; they keep the copying API.
                    val received = inbound.copyOf(length)
                    when (type) {
                        WgSession.TYPE_INITIATION -> if (session.consumeInitiation(received)) {
                            session.response()?.let(::send)
                            timers.handshakeCompleted(System.currentTimeMillis())
                            unansweredInitiations = 0
                        }
                        WgSession.TYPE_RESPONSE -> if (session.consumeResponse(received)) {
                            timers.handshakeCompleted(System.currentTimeMillis())
                            unansweredInitiations = 0
                        }
                        WgSession.TYPE_COOKIE -> if (session.consumeCookieReply(received)) {
                            send(session.initiation())
                            timers.initiationSent(System.currentTimeMillis())
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (roam.get() && now - startedAt > 3_000 && now - lastRoam > 2_000) {
                    roam.set(false)
                    lastRoam = now
                    link.reopen()
                    send(session.initiation())
                    timers.initiationSent(now)
                }
                // Rekeys, roams and cookie replies keep sending on the current keypair: the route
                // stays up until that keypair expires. Reporting "connecting" earlier made the next
                // request restart a working tunnel (WireGuardRouteController.bringUp).
                val established = session.established
                routeState.update(established)
                val due = timers.tick(now, established)
                if (due and WgTunnelTimers.KEEPALIVE != 0) session.sealPacket(ByteArray(0))?.let(::send)
                if (due and WgTunnelTimers.INITIATE != 0) {
                    // A peer that stays silent may have a new address (dynamic DNS): look again.
                    if (++unansweredInitiations % 3 == 0) link.refreshEndpoint()
                    send(session.initiation())
                }
                if (due and WgTunnelTimers.TIMED_OUT != 0) error("WireGuard handshake timed out")
            }
        } finally {
            alive.set(false); reader.join(500)
            synchronized(hookLock) { if (stopHook === hook) stopHook = null }
            runCatching { connectivity.unregisterNetworkCallback(networkWatch) }
            runCatching { input.close() }; runCatching { output.close() }; link.close(); runCatching { fd.close() }
        }
    }

    private fun handshakeRetryDelayMs(): Long = 5_000L + ThreadLocalRandom.current().nextLong(-334L, 335L)

    private fun resolveIpv4Strict(host: String): List<String> {
        runCatching { ipv4ToInt(host) }.getOrNull()?.let { return listOf(host) }
        return InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" }.filter { it.isNotBlank() }
    }

    private fun prefixMask(prefix: Int): Int = if (prefix <= 0) 0 else if (prefix >= 32) -1 else -1 shl (32 - prefix)
    private val hookLock = Any()
    private fun stopTunnel() { synchronized(hookLock) { stopHook }?.invoke() }

    private fun startAsForeground() {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wg_session), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.wg_session)).setContentText(com.ambient.tv.ui.common.MediaSystem.renamedAll(getString(R.string.wg_notification)))
            .setContentIntent(open).setOngoing(true).build()
    }

    companion object {
        private const val CHANNEL = "wireguard-route"
        private const val NOTIFICATION_ID = 44
        const val ACTION_STOP = "com.ambient.tv.vpn.STOP"
    }
}
