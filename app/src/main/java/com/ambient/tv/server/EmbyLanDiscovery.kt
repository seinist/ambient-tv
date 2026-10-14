package com.ambient.tv.server

import android.content.Context
import android.net.wifi.WifiManager
import com.ambient.tv.domain.model.DiscoveredEmbyServer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class EmbyLanDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun discover(timeoutMs: Int = 1800): List<DiscoveredEmbyServer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("ambient-tv-emby-discovery")?.apply {
            setReferenceCounted(false); runCatching { acquire() }
        }
        try {
            val payload = "who is EmbyServer?".toByteArray(Charsets.UTF_8)
            val found = linkedMapOf<String, DiscoveredEmbyServer>()
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.broadcast = true
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 250
                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT))
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    val received = runCatching { socket.receive(packet); packet }.getOrNull() ?: continue
                    val reply = parseMediaBrowserDiscovery(
                        String(received.data, received.offset, received.length, Charsets.UTF_8),
                        received.address?.hostAddress,
                    ) ?: continue
                    found[reply.key] = DiscoveredEmbyServer(reply.name, reply.host, reply.port, reply.https, reply.basePath, reply.id)
                }
            }
            found.values.sortedBy { it.name.lowercase() }
        } finally {
            if (lock?.isHeld == true) runCatching { lock.release() }
        }
    }

    private companion object { const val DISCOVERY_PORT = 7359 }
}
