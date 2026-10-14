package com.ambient.tv.server

import android.content.Context
import android.net.wifi.WifiManager
import com.ambient.tv.domain.model.DiscoveredPlexServer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Best-effort Plex GDM discovery. Results are local-LAN only and never leave the device. */
@Singleton
class PlexLanDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun discover(timeoutMs: Int = 1800): List<DiscoveredPlexServer> = withContext(Dispatchers.IO) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = wifiManager?.createMulticastLock("ambient-tv-gdm")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        try {
            discoverInternal(timeoutMs)
        } finally {
            if (multicastLock?.isHeld == true) runCatching { multicastLock.release() }
        }
    }

    private fun discoverInternal(timeoutMs: Int): List<DiscoveredPlexServer> {
        val payload = (
            "M-SEARCH * HTTP/1.0\r\n" +
                "\r\n"
            ).toByteArray(Charsets.UTF_8)
        val found = linkedMapOf<String, DiscoveredPlexServer>()

        // GDM normally replies unicast to the sender. Sending both multicast and limited
        // broadcast improves compatibility with consumer routers/AP isolation quirks.
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 250

            val destinations = listOf(
                InetSocketAddress(InetAddress.getByName(GDM_MULTICAST), GDM_PORT),
                InetSocketAddress(InetAddress.getByName("255.255.255.255"), GDM_PORT),
            )
            destinations.forEach { destination ->
                runCatching { socket.send(DatagramPacket(payload, payload.size, destination)) }
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                val received = runCatching { socket.receive(packet); packet }.getOrNull() ?: continue
                val server = parsePlexGdmResponse(
                    host = received.address.hostAddress ?: continue,
                    raw = String(received.data, received.offset, received.length, Charsets.UTF_8),
                )
                val key = server.machineIdentifier ?: "${server.host}:${server.port}"
                found[key] = server
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    private companion object {
        const val GDM_MULTICAST = "239.0.0.250"
        const val GDM_PORT = 32414
    }
}
