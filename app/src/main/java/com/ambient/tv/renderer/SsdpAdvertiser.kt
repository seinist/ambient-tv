package com.ambient.tv.renderer

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import timber.log.Timber

/** A LAN address the renderer is reachable on, with the interface it belongs to. */
internal class RendererAddress(val address: Inet4Address, val prefixLength: Int, val networkInterface: NetworkInterface)

/**
 * Private IPv4 addresses on physical LAN interfaces. Tunnels (the app's own WireGuard route,
 * other VPNs) and mobile data are left out: announcing there would expose the renderer beyond
 * the home network.
 */
internal fun rendererAddresses(): List<RendererAddress> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { nic ->
        nic.isUp && !nic.isLoopback && !nic.isPointToPoint && !nic.isVirtual && nic.supportsMulticast() &&
            listOf("tun", "wg", "ppp", "rmnet", "ccmni", "dummy").none { nic.name.startsWith(it) }
    }.flatMap { nic ->
        nic.interfaceAddresses.mapNotNull { entry: InterfaceAddress ->
            (entry.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.let { RendererAddress(it, entry.networkPrefixLength.toInt(), nic) }
        }
    }
}.getOrDefault(emptyList())

internal fun sameSubnet(a: InetAddress, b: InetAddress, prefixLength: Int): Boolean {
    val left = a.address
    val right = b.address
    if (left.size != right.size || prefixLength !in 0..left.size * 8) return false
    var bits = prefixLength
    for (index in left.indices) {
        if (bits <= 0) return true
        val mask = if (bits >= 8) 0xff else (0xff shl (8 - bits)) and 0xff
        if ((left[index].toInt() and mask) != (right[index].toInt() and mask)) return false
        bits -= 8
    }
    return true
}

/**
 * SSDP for the renderer: answers M-SEARCH from the LAN and announces itself (alive on start
 * and every few minutes, byebye on stop). Runs on its own daemon thread.
 */
internal class SsdpAdvertiser(
    private val udn: String,
    private val server: String,
    private val locationFor: (InetAddress) -> String,
) {
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var running = false
    @Volatile private var addresses: List<RendererAddress> = emptyList()
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "renderer-ssdp").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { announce(alive = false) }
        runCatching { socket?.close() }
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        val group = InetAddress.getByName(SSDP_HOST)
        try {
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                soTimeout = 1_000
                timeToLive = 2
            }
            this.socket = socket
            val joined = mutableSetOf<String>()
            var nextRefresh = 0L
            /** Interfaces come and go (Wi-Fi reconnect, Ethernet plugged in): join new ones. */
            fun refreshInterfaces() {
                addresses = rendererAddresses()
                // A Wi-Fi reconnect drops the kernel's group membership: forget interfaces that went away so they are joined again.
                val present = addresses.map { it.networkInterface.name }.toSet()
                joined.filter { it !in present }.forEach { joined -= it }
                addresses.map { it.networkInterface }.distinctBy { it.name }.filter { it.name !in joined }.forEach { nic ->
                    if (runCatching { socket.joinGroup(InetSocketAddress(group, SSDP_PORT), nic) }.isSuccess) joined += nic.name
                }
                nextRefresh = System.currentTimeMillis() + INTERFACE_REFRESH_MS
            }
            refreshInterfaces()
            if (joined.isEmpty()) Timber.w("Renderer SSDP: no LAN interface joined")
            announce(alive = true)
            var nextAnnounce = System.currentTimeMillis() + ANNOUNCE_INTERVAL_MS
            val buffer = ByteArray(2048)
            val lastReply = HashMap<String, Long>()
            while (running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val sender = packet.address ?: continue
                    if (!sender.isSiteLocalAddress) continue
                    val (st, _) = parseMSearch(String(packet.data, 0, packet.length, Charsets.US_ASCII)) ?: continue
                    // One answer round per sender, search target and half second: a search flood is not amplified.
                    val now = System.currentTimeMillis()
                    val replyKey = "${sender.hostAddress}/$st"
                    if (now - (lastReply[replyKey] ?: 0L) < REPLY_SPACING_MS) continue
                    if (lastReply.size > 256) lastReply.clear()
                    lastReply[replyKey] = now
                    val local = addresses.firstOrNull { sameSubnet(it.address, sender, it.prefixLength) } ?: continue
                    val location = locationFor(local.address)
                    matchingTargets(st, udn).forEach { (target, usn) ->
                        val reply = ssdpSearchResponse(target, usn, location, server, httpDate()).toByteArray(Charsets.US_ASCII)
                        socket.send(DatagramPacket(reply, reply.size, sender, packet.port))
                    }
                } catch (_: SocketTimeoutException) {
                    // Polling interval for the periodic announcement and the stop flag.
                }
                if (running && System.currentTimeMillis() >= nextRefresh) {
                    val before = joined.toSet()
                    refreshInterfaces()
                    // A newly joined network learns about the renderer right away.
                    if (!before.containsAll(joined)) announce(alive = true)
                }
                if (running && System.currentTimeMillis() >= nextAnnounce) {
                    announce(alive = true)
                    nextAnnounce = System.currentTimeMillis() + ANNOUNCE_INTERVAL_MS
                }
            }
        } catch (error: Exception) {
            if (running) Timber.w(error, "Renderer SSDP stopped")
        } finally {
            runCatching { socket?.close() }
            socket = null
        }
    }

    private fun announce(alive: Boolean) {
        val socket = socket ?: return
        val group = InetAddress.getByName(SSDP_HOST)
        addresses.forEach { local ->
            runCatching { socket.networkInterface = local.networkInterface }
            ssdpTargets(udn).forEach { (nt, usn) ->
                val message = ssdpNotify(nt, usn, locationFor(local.address), server, alive).toByteArray(Charsets.US_ASCII)
                runCatching { socket.send(DatagramPacket(message, message.size, group, SSDP_PORT)) }
            }
        }
    }

    private fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date())

    private companion object {
        const val SSDP_HOST = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val ANNOUNCE_INTERVAL_MS = 10 * 60_000L
        const val INTERFACE_REFRESH_MS = 30_000L
        const val REPLY_SPACING_MS = 500L
    }
}
