package com.ambient.tv.wol

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import com.ambient.tv.R
import com.ambient.tv.notifications.*
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.network.NetworkEndpoint
import com.ambient.tv.vpn.WireGuardRouteController
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WolManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val wireGuardRoute: WireGuardRouteController,
) {

    private val burstCoalescer = WakeBurstCoalescer()
    /** Follow-up bursts outlive the caller's wait loop; they are tiny and end on their own. */
    private val followUps = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Sends one reliable WOL burst per physical NIC. The MAC address is canonicalized before
     * coalescing, so the same NIC entered for Plex, Jellyfin and TVHeadend is woken only once.
     *
     * If several enabled systems with the same MAC use WireGuard, their wake targets are merged
     * into this one burst. This keeps per-system split routing intact without issuing duplicate
     * wake cycles for the same physical machine.
     */
    suspend fun sendWakeBurst(
        macAddress: String,
        broadcastAddress: String = "255.255.255.255",
        port: Int = 9,
        packetCount: Int = DEFAULT_PACKET_COUNT,
        packetDelayMs: Long = DEFAULT_PACKET_DELAY_MS,
        coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,
        wireGuardHost: String? = null,
        network: android.net.Network? = null,
        routeAlive: () -> Boolean = { true },
        retry: Boolean = false,
    ): Result<Unit> {
        val macKey = canonicalWolMacKey(macAddress)
            ?: return Result.failure(IllegalArgumentException(context.getString(com.ambient.tv.R.string.wol_invalid_mac, macAddress)))
        val macBytes = macKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val wireGuardHosts = if (network != null) listOfNotNull(wireGuardHost) else matchingWireGuardHosts(macKey, wireGuardHost)
        val initiatingJob = currentCoroutineContext()[Job]
        // Delayed bursts belong to the wake attempt, not the process lifetime. Cancelling the
        // shared connect (or finishing it) must also stop its scheduled Magic Packets.
        val wakeStillActive = { (initiatingJob?.isActive != false) && routeAlive() }

        // Check before coalescing too: an earlier successful wake says nothing about today's route.
        if (!withContext(Dispatchers.IO) { wakeNetworkAvailable(network, wireGuardHosts, wakeStillActive) }) {
            return Result.failure(networkUnavailable(macAddress))
        }
        lastWakeBurstAtMs = System.currentTimeMillis()

        val burst: suspend () -> Result<Unit> = {
            sendMagicPacketBurst(
                macBytes = macBytes,
                displayMacAddress = macAddress,
                broadcastAddress = broadcastAddress,
                port = port,
                packetCount = packetCount,
                packetDelayMs = packetDelayMs,
                wireGuardHosts = wireGuardHosts,
                network = network, routeAlive = wakeStillActive,
            )
        }
        val cycle: suspend () -> Result<Unit> = {
            burst().also { sent ->
                // UDP has no delivery guarantee (a congested tunnel, a NIC still settling into
                // standby): repeat the burst while callers wait, once per wake cycle and NIC.
                if (sent.isSuccess) followUps.launch {
                    FOLLOW_UP_DELAYS_MS.forEach { wait ->
                        delay(wait)
                        if (!wakeStillActive() || burst().isFailure) return@launch
                    }
                }
            }
        }
        // A retry follows a full wait window without an answer, so it bypasses coalescing: it must
        // reach the NIC even if another system's first burst for that machine is still recent.
        val result = if (retry) cycle().map { WakeBurstOutcome.SENT }
        else burstCoalescer.run(if (network == null) macKey else "$macKey:$network:$broadcastAddress:$port", coalesceWindowMs, cycle)
        result.onSuccess { outcome ->
            if (outcome == WakeBurstOutcome.COALESCED) {
                Timber.d("WOL burst for $macAddress shared with a recent wake request for the same NIC")
            }
        }
        return result.map { }
    }

    /** LAN needs no Internet validation: an isolated Ethernet/Wi-Fi network can still wake a NAS. */
    @Suppress("DEPRECATION")
    private fun wakeNetworkAvailable(network: Network?, hosts: List<String>, alive: () -> Boolean): Boolean {
        if (!alive()) return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return (network?.let { listOf(it) } ?: connectivity.allNetworks.toList()).any { candidate ->
            val caps = connectivity.getNetworkCapabilities(candidate) ?: return@any false
            val links = connectivity.getLinkProperties(candidate) ?: return@any false
            if (links.linkAddresses.none { it.address is Inet4Address && !it.address.isLoopbackAddress }) return@any false
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                wireGuardRoute.ownsCompanionNetwork(links) && hosts.any { wireGuardRoute.wakeTargets(it).isNotEmpty() }
            } else {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            }
        }
    }

    private fun networkUnavailable(mac: String): IllegalStateException {
        AppNotifications.post(AppNotice(
            key = "wol-network:${canonicalWolMacKey(mac)}",
            message = R.string.wol_network_unavailable,
            subject = mac,
            phase = NoticePhase.ERROR,
            kind = NoticeKind.WAKE,
        ))
        return IllegalStateException(context.getString(R.string.wol_network_unavailable))
    }

    private fun matchingWireGuardHosts(macKey: String, requestedHost: String?): List<String> = buildList {
        requestedHost?.let(::add)

        if (preferences.wireGuardPlexEnabled && canonicalWolMacKey(preferences.macAddress) == macKey) {
            add(preferences.host)
        }
        if (preferences.wireGuardJellyfinEnabled && canonicalWolMacKey(preferences.jellyfinMacAddress) == macKey) {
            add(preferences.jellyfinHost)
        }
        if (preferences.wireGuardEmbyEnabled && canonicalWolMacKey(preferences.embyMacAddress) == macKey) {
            add(preferences.embyHost)
        }
        if (preferences.wireGuardTvheadendEnabled && canonicalWolMacKey(preferences.tvheadendMacAddress) == macKey) {
            add(preferences.tvheadendHost)
        }
        preferences.fileSources.forEach { source ->
            if (source.wireGuard && source.kind != "local" &&
                canonicalWolMacKey(source.wolMac) == macKey &&
                source.host.isNotBlank()
            ) {
                add(source.host)
            }
        }
    }.map(NetworkEndpoint::extractHost)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase() }

    /** Prefer the actual LAN for IPv4 broadcasts. The default route on a phone may be
     * cellular (or VPN) even while Wi-Fi is connected and the server lives on that LAN. */
    private fun preferredLanNetwork(): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.mapNotNull { candidate ->
            val caps = cm.getNetworkCapabilities(candidate) ?: return@mapNotNull null
            val links = cm.getLinkProperties(candidate) ?: return@mapNotNull null
            if (links.linkAddresses.none { it.address is Inet4Address && !it.address.isLoopbackAddress })
                return@mapNotNull null
            val priority = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 0
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                else -> return@mapNotNull null
            }
            priority to candidate
        }.minByOrNull { it.first }?.second
    }

    private suspend fun sendMagicPacketBurst(
        macBytes: ByteArray,
        displayMacAddress: String,
        broadcastAddress: String,
        port: Int,
        packetCount: Int,
        packetDelayMs: Long,
        wireGuardHosts: List<String>,
        network: android.net.Network?,
        routeAlive: () -> Boolean,
    ): Result<Unit> {
        require(packetCount > 0) { "packetCount must be positive" }
        require(packetDelayMs >= 0) { "packetDelayMs must not be negative" }
        return try {
            val sentPackets = withContext(Dispatchers.IO) {
                val packet = ByteArray(6 + 16 * 6)
                for (i in 0 until 6) packet[i] = 0xFF.toByte()
                for (i in 0 until 16) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6)

                val extras = wireGuardHosts
                    .flatMap(wireGuardRoute::wakeTargets)
                    .distinctBy { it.lowercase() }
                val targets = (listOf(broadcastAddress) + extras)
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .distinctBy { it.lowercase() }

                val lanNetwork = if (network == null) preferredLanNetwork() else null
                var sentPackets = 0
                var firstError: Throwable? = null
                repeat(packetCount) { index ->
                    if (!wakeNetworkAvailable(network, wireGuardHosts, routeAlive)) throw networkUnavailable(displayMacAddress)
                    targets.forEach { target ->
                        runCatching {
                            DatagramSocket().use { socket ->
                                if (!wakeNetworkAvailable(network, wireGuardHosts, routeAlive)) throw networkUnavailable(displayMacAddress)
                                // The local broadcast uses Ethernet/Wi-Fi, never the phone's
                                // cellular default. VPN wake targets still use Android's VPN routes.
                                (network ?: lanNetwork.takeIf { target == broadcastAddress })?.bindSocket(socket)
                                socket.broadcast = true
                                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(target), port))
                            }
                        }.onSuccess {
                            sentPackets++
                        }.onFailure { error ->
                            if (firstError == null) firstError = error
                        }
                    }
                    if (index < packetCount - 1) delay(packetDelayMs)
                }
                if (sentPackets == 0) throw firstError ?: IllegalStateException("No WOL packet was sent")
                sentPackets
            }
            Timber.d("WOL burst ($sentPackets sends across one wake cycle) sent to $displayMacAddress via $broadcastAddress:$port")
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            Timber.w(error, "Failed to send WOL burst")
            Result.failure(error)
        }
    }

    companion object {
        /** Last wake burst for any system; servers answer 401 or 503 while they start up. */
        @Volatile var lastWakeBurstAtMs: Long = 0L
            internal set
        private const val DEFAULT_PACKET_COUNT = 3
        private const val DEFAULT_PACKET_DELAY_MS = 200L
        // Share overlapping requests, but let a later explicit retry send a fresh burst.
        private const val DEFAULT_COALESCE_WINDOW_MS = 5_000L
        private val FOLLOW_UP_DELAYS_MS = listOf(8_000L, 12_000L)
    }
}
