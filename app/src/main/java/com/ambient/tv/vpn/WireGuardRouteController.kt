package com.ambient.tv.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.ambient.tv.R
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.network.NetworkEndpoint
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class WireGuardRouteController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val tokenStore: TokenStore,
    private val companionConnections: com.ambient.tv.companion.CompanionConnectionStore,
) {
    private val gate = Mutex()
    private val _status = MutableStateFlow<WireGuardRouteState>(WireGuardRouteState.Idle)
    val status: StateFlow<WireGuardRouteState> = _status.asStateFlow()

    fun note(state: WireGuardRouteState) {
        _status.value = state
        if (state is WireGuardRouteState.Idle || state is WireGuardRouteState.Failed) appliedRoutes = ""
    }

    suspend fun ensureForPlex(): Result<Unit> = ensureSystem(preferences.wireGuardPlexEnabled, preferences.host)

    suspend fun ensureForJellyfin(): Result<Unit> = ensureSystem(preferences.wireGuardJellyfinEnabled, preferences.jellyfinHost)

    suspend fun ensureForEmby(): Result<Unit> = ensureSystem(preferences.wireGuardEmbyEnabled, preferences.embyHost)

    suspend fun ensureForTvheadend(): Result<Unit> = ensureSystem(
        preferences.wireGuardTvheadendEnabled,
        NetworkEndpoint.extractHost(preferences.tvheadendHost),
    )

    /**
     * NAS sources opt in per source. [source] may still be unsaved (connection test in the editor),
     * so its host is kept as a pending tunnel host until the next stop.
     */
    suspend fun ensureForFileSource(source: FileSource): Result<Unit> {
        if (!source.wireGuard || source.kind == "local") return Result.success(Unit)
        pendingFileHost = source.host.trim()
        return ensureSystem(true, source.host)
    }

    fun ownsCompanionNetwork(properties: android.net.LinkProperties?): Boolean =
        _status.value is WireGuardRouteState.Up && properties?.linkAddresses?.any {
            it.address.hostAddress == preferences.wireGuardProfile()?.address
        } == true

    /** Validate an editor candidate without mutating routes or stopping an existing tunnel. */
    suspend fun validateCompanionHost(host: String) {
        val profile = preferences.wireGuardProfile()
        check(profile != null && profile.ready()) { context.getString(R.string.wg_missing) }
        require(withContext(Dispatchers.IO) { resolvedHostCovered(profile.allowedIps, host) }) {
            context.getString(R.string.wg_host_outside)
        }
        check(VpnService.prepare(context) == null) { context.getString(R.string.wg_consent) }
    }

    suspend fun ensureForCompanion(host: String): Result<Unit> {
        if (host !in companionConnections.vpnHosts()) return Result.failure(IllegalArgumentException("TV route is not configured"))
        return ensureSystem(true, host)
    }

    /** Connect only for systems whose dedicated switches are enabled; also lifts a manual disconnect. */
    suspend fun ensureActive(): Result<Unit> {
        manuallyStopped = false
        return bringUp()
    }

    /**
     * Proves the peer answers: one WireGuard handshake over a plain UDP socket, without establishing
     * the VPN (no system VPN, no tunnel notification, the current route stays as it is). Returns the
     * handshake time in ms; a valid response means the endpoint answered and accepted our keys.
     */
    suspend fun testConnection(): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val profile = preferences.wireGuardProfile()
            check(profile != null && profile.ready()) { context.getString(R.string.wg_missing) }
            val privateKey = tokenStore.wireGuardPrivateKey?.let { runCatching { decodeKey(it) }.getOrNull() }
                ?: error(context.getString(R.string.wg_missing))
            val preshared = tokenStore.wireGuardPresharedKey?.let { runCatching { decodeKey(it) }.getOrNull() }
            val session = WgSession(privateKey, decodeKey(profile.peerPublicKey), preshared ?: ByteArray(32))
            val link = WgUdpLink(profile.endpointHost, profile.endpointPort, protect = { true })
            try {
                val started = System.currentTimeMillis()
                link.open()
                val buffer = ByteArray(256)
                val datagram = java.net.DatagramPacket(buffer, buffer.size)
                var nextInitiation = 0L
                while (System.currentTimeMillis() - started < 15_000) {
                    val now = System.currentTimeMillis()
                    if (now >= nextInitiation) { link.send(session.initiation()); nextInitiation = now + 5_000 }
                    datagram.setData(buffer, 0, buffer.size)
                    val length = link.receive(datagram)
                    if (length <= 0) continue
                    val received = buffer.copyOf(length)
                    when (session.messageType(received)) {
                        WgSession.TYPE_RESPONSE -> if (session.consumeResponse(received)) return@runCatching System.currentTimeMillis() - started
                        WgSession.TYPE_COOKIE -> if (session.consumeCookieReply(received)) nextInitiation = 0L
                    }
                }
                error(context.getString(R.string.wg_test_no_handshake))
            } finally { link.close() }
        }
    }

    /** Re-applies routes after a system/source changed, or stops the tunnel once nothing uses it. */
    suspend fun reconcile(): Result<Unit> {
        if (anySystemEnabled()) return bringUp()
        stop()
        return Result.success(Unit)
    }

    fun anySystemEnabled(): Boolean = serverSystemEnabled() || fileSourceHosts().isNotEmpty() || companionConnections.vpnHosts().isNotEmpty()

    /** Every host whose traffic is routed through the tunnel; read by [WireGuardVpnService]. */
    fun tunnelHosts(): List<String> = (selectedSystemHosts() + fileSourceHosts() + companionConnections.vpnHosts() + listOfNotNull(pendingFileHost))
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

    fun stop() {
        pendingFileHost = null
        appliedRoutes = ""
        stopService()
        if (_status.value !is WireGuardRouteState.Failed) _status.value = WireGuardRouteState.Idle
    }

    /** User pressed "Disconnect": stop and stay down until "Connect" or a changed switch asks again. */
    fun disconnect() {
        manuallyStopped = true
        stop()
        _status.value = WireGuardRouteState.Idle
    }

    /**
     * The system keeps an established VpnService bound, so stopService alone leaves the tunnel up:
     * the service must close its own interface first (ACTION_STOP).
     */
    private fun stopService() {
        if (_status.value !is WireGuardRouteState.Idle) runCatching {
            context.startService(Intent(context, WireGuardVpnService::class.java).setAction(WireGuardVpnService.ACTION_STOP))
        }
        context.stopService(Intent(context, WireGuardVpnService::class.java))
    }

    fun wakeTargets(host: String): List<String> {
        if (_status.value !is WireGuardRouteState.Up) return emptyList()
        val profile = preferences.wireGuardProfile() ?: return emptyList()
        val normalized = host.trim()
        if (normalized.isBlank() || enabledHosts().none { it.equals(normalized, ignoreCase = true) }) return emptyList()
        val resolved = runCatching {
            if (runCatching { ipv4ToInt(normalized) }.isSuccess) listOf(normalized)
            else InetAddress.getAllByName(normalized).filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }.distinct()
        }.getOrDefault(emptyList())
        return wakeTargetsFor(profile.allowedIps, resolved)
    }

    private suspend fun ensureSystem(enabled: Boolean, host: String): Result<Unit> {
        if (!enabled) return Result.success(Unit)
        val profile = preferences.wireGuardProfile()
        if (profile == null || !profile.ready()) return Result.failure(IllegalStateException(context.getString(R.string.wg_missing)))
        val covered = withContext(Dispatchers.IO) { resolvedHostCovered(profile.allowedIps, host) }
        if (!covered) return Result.failure(IllegalStateException(context.getString(hostProblem(host))))
        return bringUp()
    }

    private suspend fun bringUp(timeoutMs: Long = 12_000): Result<Unit> = gate.withLock {
        if (!anySystemEnabled() && pendingFileHost == null) return Result.success(Unit)
        if (manuallyStopped) return Result.success(Unit)
        val profile = preferences.wireGuardProfile()
        if (profile == null || !profile.ready()) return fail(context.getString(R.string.wg_missing))
        val selectedHosts = tunnelHosts()
        val hostsValid = withContext(Dispatchers.IO) { selectedHosts.all { resolvedHostCovered(profile.allowedIps, it) } }
        if (!hostsValid) {
            val problem = withContext(Dispatchers.IO) { selectedHosts.firstOrNull { !resolvedHostCovered(profile.allowedIps, it) } }
            return fail(context.getString(problem?.let { hostProblem(it) } ?: R.string.wg_host_outside))
        }
        if (VpnService.prepare(context) != null) return fail(context.getString(R.string.wg_consent))
        val signature = routeSignature()
        if (_status.value is WireGuardRouteState.Up && appliedRoutes == signature) return Result.success(Unit)
        appliedRoutes = signature
        _status.value = WireGuardRouteState.Connecting
        context.startForegroundService(Intent(context, WireGuardVpnService::class.java))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            when (val current = _status.value) {
                is WireGuardRouteState.Up -> return Result.success(Unit)
                is WireGuardRouteState.Failed -> return Result.failure(IllegalStateException(current.message))
                else -> delay(200)
            }
        }
        fail(context.getString(R.string.wg_failed))
    }

    /** Hosts of systems whose VPN switch is on and that actually have a server configured. */
    private fun selectedSystemHosts(): List<String> = buildList {
        if (preferences.wireGuardPlexEnabled) add(preferences.host.trim())
        if (preferences.wireGuardJellyfinEnabled) add(preferences.jellyfinHost.trim())
        if (preferences.wireGuardEmbyEnabled) add(preferences.embyHost.trim())
        if (preferences.wireGuardTvheadendEnabled) add(NetworkEndpoint.extractHost(preferences.tvheadendHost).trim())
    }.filter { it.isNotBlank() }

    private fun serverSystemEnabled(): Boolean = selectedSystemHosts().isNotEmpty()

    private fun fileSourceHosts(): List<String> = preferences.fileSources
        .filter { it.wireGuard && it.kind != "local" }
        .map { it.host.trim() }

    private fun enabledHosts(): List<String> = tunnelHosts()

    private fun routeSignature(): String {
        val profile = preferences.wireGuardProfile()
        val material = listOf(
            profile?.address.orEmpty(), profile?.prefix?.toString().orEmpty(), profile?.dns.orEmpty(),
            profile?.mtu?.toString().orEmpty(), profile?.peerPublicKey.orEmpty(), profile?.endpointHost.orEmpty(),
            profile?.endpointPort?.toString().orEmpty(), profile?.allowedIps?.sorted()?.joinToString().orEmpty(),
            profile?.keepaliveSeconds?.toString().orEmpty(), enabledHosts().sorted().joinToString(),
            tokenStore.wireGuardPrivateKey.orEmpty(), tokenStore.wireGuardPresharedKey.orEmpty(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(material.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun resolvedHostCovered(allowedIps: List<String>, host: String): Boolean {
        if (host.isBlank()) return false
        if (hostCovered(allowedIps, host)) return true
        val addresses = resolveIpv4(host)
        return addresses.isNotEmpty() && addresses.all { address ->
            val value = address.hostAddress ?: return@all false
            hostCovered(allowedIps, value)
        }
    }

    /** IPv4 addresses of [host]; answers (and failures) are kept for 30 s so repeated checks do not hit DNS each time. */
    private fun resolveIpv4(host: String): List<Inet4Address> {
        val key = host.trim().lowercase()
        val now = System.currentTimeMillis()
        dnsCache[key]?.let { (at, list) -> if (now - at < 30_000) return list }
        val list = runCatching { InetAddress.getAllByName(host).filterIsInstance<Inet4Address>() }.getOrDefault(emptyList())
        dnsCache[key] = now to list
        return list
    }

    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Inet4Address>>>()

    /** A name that does not resolve is told apart from an address outside the AllowedIPs. */
    private fun hostProblem(host: String): Int =
        if (host.isNotBlank() && runCatching { ipv4ToInt(host.trim()) }.isFailure && resolveIpv4(host).isEmpty()) R.string.wg_host_unresolved
        else R.string.wg_host_outside

    private fun fail(message: String): Result<Unit> {
        appliedRoutes = ""
        _status.value = WireGuardRouteState.Failed(message)
        // Never keep an older, broader route set alive after a requested reconfiguration failed.
        stopService()
        return Result.failure(IllegalStateException(message))
    }

    private var appliedRoutes: String = ""
    @Volatile private var pendingFileHost: String? = null
    @Volatile private var manuallyStopped = false
}
