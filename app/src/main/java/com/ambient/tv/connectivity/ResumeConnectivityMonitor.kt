package com.ambient.tv.connectivity

import com.ambient.tv.notifications.*

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.ambient.tv.R
import com.ambient.tv.data.files.WakeOnLan
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.vpn.WireGuardRouteState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * On process foreground (after a meaningful background gap, or when last health is stale),
 * rechecks network + VPN + configured local media servers with short timeouts, optional one-shot WoL,
 * and publishes progress for the existing Home startup / connection status UI.
 *
 * Also watches VPN transport loss / revoke signals so mid-playback WireGuard drops surface promptly.
 */
@Singleton
class ResumeConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: AppPreferences,
    private val plexConnection: ServerConnectionManager,
    private val jellyfinConnection: JellyfinConnectionManager,
    private val embyConnection: EmbyConnectionManager,
    private val tvheadendConnection: com.ambient.tv.server.TvheadendConnectionManager,
    private val wakeOnLan: WakeOnLan,
    private val wireGuardRoute: WireGuardRouteController,
    private val wakeMachines: com.ambient.tv.wol.WakeMachines,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Conflated requests never cancel a running, app-scoped wake. A network change during a
    // long wake is checked once more when that attempt completes.
    private val recheckRequests = Channel<String>(Channel.CONFLATED)
    private var networkChangeJob: Job? = null
    private var started = false
    @Volatile private var foreground = false
    private var vpnCallbackRegistered = false
    private var networkCallbackRegistered = false

    init {
        scope.launch {
            for (reason in recheckRequests) {
                try {
                    runRecheck(reason)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // A failed VPN/network setup must not terminate the only resume worker.
                    Timber.w(error, "Resume connectivity worker failed")
                    _status.value = ResumeConnectivityStatus.Idle
                    _recheckGeneration.value = _recheckGeneration.value + 1
                }
            }
        }
    }

    private var lastStopElapsedMs: Long = -1L
    @Volatile private var lastHealthElapsedMs: Long = SystemClock.elapsedRealtime()

    private val _status = MutableStateFlow<ResumeConnectivityStatus>(ResumeConnectivityStatus.Idle)
    val status: StateFlow<ResumeConnectivityStatus> = _status.asStateFlow()

    private val _recheckGeneration = MutableStateFlow(0L)
    /** Bumped after each completed resume recheck so Home can soft-retry offline screens. */
    val recheckGeneration: StateFlow<Long> = _recheckGeneration.asStateFlow()

    private val _vpnDropGeneration = MutableStateFlow(0L)
    /** Bumped when the WireGuard route is marked failed (revoke / VPN network lost) so player UI can react. */
    val vpnDropGeneration: StateFlow<Long> = _vpnDropGeneration.asStateFlow()

    private val vpnNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (!wireGuardRoute.anySystemEnabled()) return
            val current = wireGuardRoute.status.value
            if (current is WireGuardRouteState.Up || current is WireGuardRouteState.Connecting) {
                Timber.w("VPN network lost while WireGuard route was active")
                markVpnRouteFailed(context.getString(R.string.wg_vpn_network_lost))
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        registerVpnCallback()
        registerNetworkCallback()
    }

    /** Called from [com.ambient.tv.vpn.WireGuardVpnService.onRevoke]. */
    fun onVpnRevoked() {
        markVpnRouteFailed(context.getString(R.string.wg_vpn_revoked))
    }

    private fun markVpnRouteFailed(message: String) {
        wireGuardRoute.note(WireGuardRouteState.Failed(message))
        _vpnDropGeneration.value = _vpnDropGeneration.value + 1
        requestRecheck(reason = "vpn-drop: $message")
    }

    private fun registerVpnCallback() {
        if (vpnCallbackRegistered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, vpnNetworkCallback)
            vpnCallbackRegistered = true
        }.onFailure { Timber.w(it, "Failed to register VPN NetworkCallback") }
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        networkChangeJob?.cancel()
        lastStopElapsedMs = SystemClock.elapsedRealtime()
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        val now = SystemClock.elapsedRealtime()
        val backgroundedFor = if (lastStopElapsedMs < 0L) 0L else (now - lastStopElapsedMs).coerceAtLeast(0L)
        val healthAge = (now - lastHealthElapsedMs).coerceAtLeast(0L)
        // A healthy system may have been shut down while the phone was away, even after a
        // short background gap. Always validate after an actual foreground return.
        if (!ResumeConnectivityPolicy.shouldRecheckOnStart(
                wasBackgrounded = lastStopElapsedMs >= 0L,
                backgroundedForMs = backgroundedFor,
                lastHealthAgeMs = healthAge,
            )) return
        requestRecheck(reason = "resume backgrounded=${backgroundedFor}ms healthAge=${healthAge}ms")
    }

    /** Requests are coalesced, but an in-flight wake is allowed to finish or be cancelled
     * explicitly by the user. Cancelling a recheck caller does not cancel SharedConnectRun. */
    fun requestRecheck(reason: String = "manual") {
        com.ambient.tv.network.LocalNetworkPolicy.clearCache()
        recheckRequests.trySend(reason)
    }

    private fun onNetworkChanged(reason: String) {
        if (!foreground) return
        networkChangeJob?.cancel()
        networkChangeJob = scope.launch {
            delay(NETWORK_SETTLE_MS)
            if (foreground) requestRecheck(reason)
        }
    }

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onNetworkChanged("network-available")
        override fun onLost(network: Network) = onNetworkChanged("network-lost")
    }

    private fun registerNetworkCallback() {
        if (networkCallbackRegistered) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, connectivityCallback)
            networkCallbackRegistered = true
        }.onFailure { Timber.w(it, "Failed to register connectivity NetworkCallback") }
    }

    fun noteHealthyNow() {
        lastHealthElapsedMs = SystemClock.elapsedRealtime()
    }

    private suspend fun runRecheck(reason: String) {
        Timber.d("Resume connectivity recheck: %s", reason)
        if (!isNetworkAvailable()) {
            _status.value = ResumeConnectivityStatus.NoNetwork
            _recheckGeneration.value = _recheckGeneration.value + 1
            return
        }
        _status.value = ResumeConnectivityStatus.Checking
        val vpnLabel = if (wireGuardRoute.anySystemEnabled()) {
            wireGuardRoute.reconcile()
            when (val s = wireGuardRoute.status.value) {
                WireGuardRouteState.Up -> context.getString(R.string.wg_status_up)
                WireGuardRouteState.Connecting -> context.getString(R.string.wg_status_connecting)
                is WireGuardRouteState.Failed -> context.getString(R.string.wg_status_failed, s.message)
                WireGuardRouteState.Idle -> context.getString(R.string.wg_status_idle)
            }
        } else null

        val targets = configuredSystems()
        val progress = MutableStateFlow(
            ResumeConnectivityStatus.Progress(
                networkOk = true,
                vpnLabel = vpnLabel,
                servers = targets.map {
                    ResumeConnectivityStatus.ServerProgress(it, ResumeConnectivityStatus.ServerPhase.CHECKING)
                },
            ),
        )
        _status.value = progress.value

        fun update(system: MediaSystem, phase: ResumeConnectivityStatus.ServerPhase, detail: String? = null) {
            // Notices come from each system's connection state (ConnectionNotifications), TVHeadend included.
            progress.update { current ->
                current.copy(servers = current.servers.map {
                    if (it.system == system) it.copy(phase = phase, detail = detail) else it
                })
            }
            _status.value = progress.value
        }

        kotlinx.coroutines.coroutineScope {
            targets.map { system ->
                async {
                    val online = try {
                        recheckSystem(system, ::update)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.w(error, "Resume recheck failed for %s", system)
                        update(system, ResumeConnectivityStatus.ServerPhase.ERROR, error.message)
                        false
                    }
                    if (online) noteHealthyNow()
                    online
                }
            }.awaitAll()
        }

        lastHealthElapsedMs = SystemClock.elapsedRealtime()
        _recheckGeneration.value = _recheckGeneration.value + 1
        // Keep the last progress visible briefly for the status UI, then idle.
        _status.value = progress.value
        delay(COMPLETED_STATUS_DISPLAY_MS)
        if (_status.value == progress.value) {
            _status.value = ResumeConnectivityStatus.Idle
        }
    }

    private suspend fun recheckSystem(
        system: MediaSystem,
        update: (MediaSystem, ResumeConnectivityStatus.ServerPhase, String?) -> Unit,
    ): Boolean {
        update(system, ResumeConnectivityStatus.ServerPhase.CHECKING, null)
        val ok = when (system) {
            MediaSystem.PLEX -> plexConnection.recheckOnResume(
                onWaking = { update(system, ResumeConnectivityStatus.ServerPhase.WAKING, null) },
            )
            MediaSystem.JELLYFIN -> jellyfinConnection.recheckOnResume(
                onWaking = { update(system, ResumeConnectivityStatus.ServerPhase.WAKING, null) },
            )
            MediaSystem.EMBY -> embyConnection.recheckOnResume(
                onWaking = { update(system, ResumeConnectivityStatus.ServerPhase.WAKING, null) },
            )
            MediaSystem.TVHEADEND -> tvheadendConnection.recheckOnResume(
                onWaking = { update(system, ResumeConnectivityStatus.ServerPhase.WAKING, null) },
            )
            MediaSystem.FILES -> recheckFiles(onWaking = { update(system, ResumeConnectivityStatus.ServerPhase.WAKING, null) })
            else -> false
        }
        update(
            system,
            if (ok) ResumeConnectivityStatus.ServerPhase.ONLINE else ResumeConnectivityStatus.ServerPhase.OFFLINE,
            if (ok) null else context.getString(R.string.home_startup_unreachable),
        )
        return ok
    }

    /**
     * Light TCP probe of remote NAS sources. With auto-wake configured the source joins (or
     * starts) its machine's wake with the full budget: a NAS that needs 30 s to boot must not
     * count as unreachable after a few seconds while its wake card keeps waiting.
     */
    private suspend fun recheckFiles(onWaking: (() -> Unit)? = null): Boolean {
        val sources = preferences.fileSources.filter { it.kind != "local" }
        if (sources.isEmpty()) return true
        var allOk = true
        for (source in sources) {
            if (wakeOnLan.isReachable(source)) continue
            if (source.wolAuto && source.wolMac.isNotBlank()) {
                onWaking?.invoke()
                val woke = try {
                    wakeOnLan.wakeAndWait(source)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                if (!woke) allOk = false
            } else {
                allOk = false
                notifyResult("files:offline:${source.id}", "files", source.name, success = false, failureMessage = R.string.notice_unreachable)
            }
        }
        return allOk
    }

    private fun configuredSystems(): List<MediaSystem> = buildList {
        if (preferences.plexEnabled && preferences.isConfigured) add(MediaSystem.PLEX)
        if (preferences.jellyfinEnabled && preferences.isJellyfinConfigured) add(MediaSystem.JELLYFIN)
        if (preferences.embyEnabled && preferences.isEmbyConfigured) add(MediaSystem.EMBY)
        if (preferences.tvheadendEnabled && preferences.isTvheadendConfigured) add(MediaSystem.TVHEADEND)
        if (preferences.filesEnabled && preferences.fileSources.any { it.kind != "local" }) add(MediaSystem.FILES)
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        // A LAN without validated Internet is still useful for server probes and WOL.
        // In particular, cellular may be the default while a local Wi-Fi network is available.
        return cm.allNetworks.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }

    private companion object {
        const val COMPLETED_STATUS_DISPLAY_MS = 2_000L
        const val NETWORK_SETTLE_MS = 750L
    }
}
