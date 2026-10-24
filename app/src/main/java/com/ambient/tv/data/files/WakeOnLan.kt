package com.ambient.tv.data.files

import com.ambient.tv.R
import com.ambient.tv.notifications.*
import kotlinx.coroutines.CancellationException

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.wol.WolManager
import com.ambient.tv.wol.wakeWithRetries
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Files/NAS wake helper. Magic-packet bursts go through [WolManager] so the same NIC configured
 * for Plex/JF/Emby/TVH and a NAS source shares coalescing. TCP reachability + wait loops
 * stay local to Files because they are source/port specific.
 */
@Singleton
class WakeOnLan {
    private var hosts: com.ambient.tv.connectivity.HostConnectivity? = null
    private val wolManager: WolManager?
    private val directSend: (suspend (String, String, Int) -> Unit)?
    private val retryCount: () -> Int
    private val machines: com.ambient.tv.wol.WakeMachines?
    private val notices: com.ambient.tv.notifications.WakeNoticeTracker

    @Inject
    constructor(wolManager: WolManager, preferences: AppPreferences, machines: com.ambient.tv.wol.WakeMachines, hosts: com.ambient.tv.connectivity.HostConnectivity) {
        this.hosts = hosts
        this.wolManager = wolManager
        this.machines = machines
        this.notices = WakeNotices
        this.directSend = null
        this.retryCount = { preferences.wolRetryCount }
    }

    /** Test seam: sends UDP directly without WolManager coalescing. */
    internal constructor(
        directSend: suspend (mac: String, broadcast: String, port: Int) -> Unit = ::defaultDirectSend,
        retryCount: () -> Int = { 0 },
        notices: com.ambient.tv.notifications.WakeNoticeTracker = WakeNotices,
    ) {
        this.wolManager = null
        this.machines = null
        this.notices = notices
        this.directSend = directSend
        this.retryCount = retryCount
    }

    private val lastOnline = ConcurrentHashMap<String, Long>()
    private fun endpoint(source: FileSource) = "${source.host.lowercase(java.util.Locale.ROOT)}:${source.port}"
    private fun rememberOnline(source: FileSource) {
        val now = System.nanoTime()
        lastOnline.entries.removeAll { now - it.value >= 30_000_000_000L }
        if (lastOnline.size >= 64) lastOnline.clear()
        lastOnline[endpoint(source)] = now
    }

    suspend fun ensureAwake(source: FileSource) {
        if (source.kind == "local" || !source.wolAuto || source.wolMac.isBlank()) return
        val now = System.nanoTime()
        if (lastOnline[endpoint(source)]?.let { now - it in 0 until 30_000_000_000L } == true) return
        if (online(source)) { rememberOnline(source); return }
        wakeAndWait(source)
    }

    /** Shared machine key of a source: same MAC or host as any other system joins its wake. */
    fun machineKey(source: FileSource): String =
        machines?.keyFor("files", source.host, source.wolMac) ?: com.ambient.tv.wol.wakeGroupKey(source.wolMac, source.host, "files")

    /** True while any system is waking this source's machine (a manual press is then ignored). */
    fun isMachineWaking(source: FileSource): Boolean = notices.isWaking(machineKey(source))

    /**
     * Wakes the source's machine and waits until the source answers. A [manual] press only starts
     * when nothing on that machine is waking yet and returns false (sending nothing, keeping the
     * running card) otherwise; automatic callers join a running wake and always wait.
     */
    suspend fun wakeAndWait(source: FileSource, manual: Boolean = false): Boolean {
        val machine = machineKey(source)
        val member = "files:${source.id}"
        val window = source.wolWaitSeconds.coerceIn(10, 180) * 1000L
        val budget = com.ambient.tv.wol.wakeBudgetMs(retryCount(), window)
        if (manual) {
            if (!notices.tryBegin(machine, "files", source.name, member, budget)) return false
        } else notices.begin(machine, "files", source.name, member, budget)
        try {
        val ready = wakeWithRetries(
            retries = retryCount(),
            windowMs = source.wolWaitSeconds.coerceIn(10, 180) * 1000L,
            pollMs = 1500L,
            send = { attempt ->
                send(
                    mac = source.wolMac,
                    broadcast = source.wolBroadcast,
                    port = source.wolPort,
                    wireGuardHost = source.host.takeIf { source.wireGuard },
                    retry = attempt > 0,
                )
            },
            reachable = { online(source) },
            onSent = { attempt, attempts -> notices.sent(machine, attempt, attempts, window) },
        )
        if (!ready) throw java.net.SocketTimeoutException("Wake timeout")
        rememberOnline(source)
        notices.finish(machine, "files", success = true, member = member)
        return true
        } catch (cancelled: CancellationException) {
            notices.cancel(machine, "files", member)
            throw cancelled
        } catch (error: Exception) {
            notices.finish(machine, "files", success = false, member = member)
            throw error
        }
    }

    /** Light TCP probe used by resume connectivity. */
    suspend fun isReachable(source: FileSource): Boolean = online(source)

    suspend fun send(
        mac: String,
        broadcast: String,
        port: Int,
        wireGuardHost: String? = null,
        network: android.net.Network? = null,
        routeAlive: () -> Boolean = { true },
        retry: Boolean = false,
    ) {
        val manager = wolManager
        if (manager != null) {
            manager.sendWakeBurst(
                macAddress = mac,
                broadcastAddress = broadcast,
                port = port,
                wireGuardHost = wireGuardHost,
                network = network, routeAlive = routeAlive,
                retry = retry,
            ).getOrThrow()
        } else {
            directSend!!.invoke(mac, broadcast, port)
        }
    }

    private suspend fun online(source: FileSource): Boolean {
        val check = hosts?.beginEndpoint("files:${source.id}", source.host, source.port)
        return runInterruptible(Dispatchers.IO) {
        try {
            Socket().use { it.connect(InetSocketAddress(source.host, source.port), 900) }
            true
        } catch (_: java.io.IOException) {
            false
        }
        }.also { if (it) hosts?.online(check) else hosts?.failure(check) }
    }

    companion object {
        fun packet(mac: String): ByteArray {
            val normalized = mac.replace(":", "").replace("-", "")
            require(normalized.matches(Regex("[0-9a-fA-F]{12}")))
            val bytes = ByteArray(6) { normalized.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            require(bytes.any { it != 0.toByte() } && bytes[0].toInt() and 1 == 0)
            return ByteArray(102) { if (it < 6) 0xff.toByte() else bytes[(it - 6) % 6] }
        }

        private suspend fun defaultDirectSend(mac: String, broadcast: String, port: Int) =
            runInterruptible(Dispatchers.IO) {
                val packet = packet(mac)
                require(port in 1..65535)
                val destination = java.net.InetAddress.getByName(broadcast)
                java.net.DatagramSocket().use { socket ->
                    socket.broadcast = true
                    repeat(3) {
                        socket.send(java.net.DatagramPacket(packet, packet.size, destination, port))
                    }
                }
            }
    }
}
