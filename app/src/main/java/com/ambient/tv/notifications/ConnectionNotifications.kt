package com.ambient.tv.notifications

import com.ambient.tv.R
import com.ambient.tv.connectivity.ResumeConnectivityMonitor
import com.ambient.tv.connectivity.ResumeConnectivityStatus
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.server.EmbyConnectionManager
import com.ambient.tv.server.JellyfinConnectionManager
import com.ambient.tv.server.ServerConnectionManager
import com.ambient.tv.server.ServerConnectionState
import com.ambient.tv.spotify.SpotifyRepository
import com.ambient.tv.vpn.WireGuardRouteController
import com.ambient.tv.vpn.WireGuardRouteState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Observes actual service state independently of navigation and Home's loading focus. */
@Singleton
class ConnectionNotifications @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val preferences: AppPreferences,
    private val plex: ServerConnectionManager,
    private val jellyfin: JellyfinConnectionManager,
    private val emby: EmbyConnectionManager,
    private val tvheadend: com.ambient.tv.server.TvheadendConnectionManager,
    private val vpn: WireGuardRouteController,
    private val resume: ResumeConnectivityMonitor,
    private val spotify: SpotifyRepository,
    private val wakeMachines: com.ambient.tv.wol.WakeMachines,
    private val hosts: com.ambient.tv.connectivity.HostConnectivity,
) {
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            var previous = emptyMap<String, AppNotice>()
            val unhealthy = mutableSetOf<String>()
            // system -> machine key of a wake this observer announced through [WakeNotices].
            val wakingMachines = mutableMapOf<String, String>()
            combine(plex.state, jellyfin.state, emby.state, tvheadend.state, vpn.status) { p, j, e, t, route ->
                route to listOf(
                    Target("plex", preferences.host, preferences.activeServerProfile()?.name, p, preferences.macAddress,
                        preferences.wireGuardPlexEnabled, plex.isAutomaticReconnectPaused()),
                    Target("jellyfin", preferences.jellyfinHost, preferences.activeJellyfinServerProfile()?.name, j, preferences.jellyfinMacAddress,
                        preferences.wireGuardJellyfinEnabled, jellyfin.isAutomaticReconnectPaused()),
                    Target("emby", preferences.embyHost, preferences.activeEmbyServerProfile()?.name, e, preferences.embyMacAddress,
                        preferences.wireGuardEmbyEnabled, emby.isAutomaticReconnectPaused()),
                    Target("tvheadend", preferences.tvheadendHost, null, t, preferences.tvheadendMacAddress,
                        preferences.wireGuardTvheadendEnabled, tvheadend.isAutomaticReconnectPaused()),
                )
            }.collect { (route, targets) ->
                // Wakes are announced per machine, shared with TVHeadend, NAS and DLNA on it.
                targets.forEach { target ->
                    // "Machine awake, service starting" ends the machine's wake; the connection notice carries on.
                    val isWaking = isMachineWaking(target.state)
                    val machine = wakingMachines[target.system]
                    if (isWaking && machine == null) {
                        val key = wakeMachines.keyFor(target.system, target.host, target.mac)
                        wakingMachines[target.system] = key
                        WakeNotices.begin(key, target.system, target.name?.takeIf(String::isNotBlank) ?: target.host.trim().ifBlank { null })
                    } else if (!isWaking && machine != null) {
                        wakingMachines.remove(target.system)
                        if (target.cancelled) WakeNotices.cancel(machine, target.system)
                        else when (target.state) {
                            ServerConnectionState.Connecting, is ServerConnectionState.Online, is ServerConnectionState.Waking ->
                                WakeNotices.finish(machine, target.system, success = true)
                            ServerConnectionState.WakeTimedOut, ServerConnectionState.Offline, is ServerConnectionState.Error ->
                                WakeNotices.finish(machine, target.system, success = false)
                            else -> WakeNotices.cancel(machine, target.system)
                        }
                    }
                    // The connection manager knows the wake's full budget: the card shows its progress.
                    val waking = target.state as? ServerConnectionState.Waking
                    val wakingMachine = wakingMachines[target.system]
                    if (waking != null && wakingMachine != null && waking.totalSeconds > 0)
                        WakeNotices.setBudget(wakingMachine, waking.totalSeconds * 1000L)
                    // Each burst shows up on the card ("packet 2 of 3 · next in 40 s").
                    if (waking != null && wakingMachine != null && waking.packetsSent > 0 && waking.packets > 0)
                        WakeNotices.sent(wakingMachine, waking.packetsSent - 1, waking.packets, waking.windowSeconds * 1000L)
                }
                val notices = targets.filter { it.state != ServerConnectionState.NotConfigured }
                    .groupBy { wakeMachines.keyFor(it.system, it.host, it.mac) }
                    .flatMap { (host, allTargets) ->
                        // Keep causally blocked services separate from independent failures on
                        // the same machine. The shared reducer shows one VPN cause, not N failures.
                        val (vpnTargets, group) = allTargets.partition {
                            !it.cancelled && connectionDependency(it.state, it.vpnEnabled, route) == ConnectivityDependency.VPN
                        }
                        buildList {
                            val vpnReason = vpnTargets.firstNotNullOfOrNull {
                                (it.state as? ServerConnectionState.Error)?.message?.let(::sanitizeFailureDetail)?.takeIf(String::isNotBlank)
                            }
                            if (vpnTargets.isNotEmpty()) add(AppNotice(
                                key = "connection:$host:vpn",
                                message = if (vpnReason != null) R.string.notice_vpn_failed_reason else R.string.notice_waiting_for_vpn,
                                systems = vpnTargets.map(Target::system),
                                subject = vpnTargets.mapNotNull { it.name?.takeIf(String::isNotBlank) }.distinct()
                                    .joinToString(", ").ifBlank { vpnTargets.first().host },
                                phase = if (vpnTargets.any { it.state is ServerConnectionState.Error }) NoticePhase.ERROR else NoticePhase.WORKING,
                                kind = NoticeKind.CONNECTION,
                                quietSuccess = true,
                                args = listOfNotNull(vpnReason),
                                interruptPlayback = vpnTargets.any { it.state is ServerConnectionState.Error },
                                machine = host,
                                blockedBy = ConnectivityDependency.VPN,
                            ))
                            if (group.isNotEmpty()) {
                                val waking = group.any { isMachineWaking(it.state) }
                                val serviceStarting = !waking && group.any { (it.state as? ServerConnectionState.Waking)?.serviceStarting == true }
                                val failed = group.filter { !it.cancelled && (it.state == ServerConnectionState.Offline || it.state == ServerConnectionState.WakeTimedOut || it.state is ServerConnectionState.Error) }
                                val cancelled = group.any { it.cancelled && it.state !is ServerConnectionState.Online }
                                val online = group.all { it.state is ServerConnectionState.Online }
                                // The cause: the connection manager's reason for an error, the address for "offline".
                                val reason = failed.firstNotNullOfOrNull { (it.state as? ServerConnectionState.Error)?.message?.let(::sanitizeFailureDetail)?.takeIf(String::isNotBlank) }
                                    ?: failed.firstOrNull { it.state == ServerConnectionState.Offline }?.let { context.getString(R.string.reason_unreachable, it.host.trim().ifBlank { it.system }) }
                                val phase = when {
                                    waking -> NoticePhase.WORKING
                                    failed.isNotEmpty() -> NoticePhase.ERROR
                                    online -> NoticePhase.SUCCESS
                                    cancelled -> NoticePhase.INFO
                                    else -> NoticePhase.WORKING
                                }
                                AppNotice(
                                    key = "connection:$host",
                                    systems = (if (phase == NoticePhase.ERROR) failed else group).map { it.system },
                                    subject = group.mapNotNull { it.name?.takeIf(String::isNotBlank) }.distinct().joinToString(", ").ifBlank { host },
                                    phase = phase,
                                    kind = if (waking) NoticeKind.WAKE else NoticeKind.CONNECTION,
                                    message = when {
                                        waking -> R.string.notice_waking
                                        serviceStarting -> R.string.notice_service_starting
                                        failed.any { it.state == ServerConnectionState.WakeTimedOut } -> R.string.notice_wake_failed
                                        failed.isNotEmpty() && reason != null -> R.string.notice_unreachable_reason
                                        failed.isNotEmpty() -> R.string.notice_unreachable
                                        online -> R.string.notice_connected
                                        cancelled -> R.string.notice_wake_cancelled
                                        else -> R.string.notice_connecting
                                    },
                                    args = if (!waking && failed.isNotEmpty() && reason != null &&
                                        failed.none { it.state == ServerConnectionState.WakeTimedOut }) listOf(reason) else emptyList(),
                                    quietSuccess = true, interruptPlayback = waking || failed.isNotEmpty(),
                                    machine = host,
                                    cancelled = cancelled && phase == NoticePhase.INFO,
                                ).takeUnless {
                                    // The machine's wake card already covers waking and a failed wake.
                                    waking || (failed.isNotEmpty() && failed.all { f -> f.state == ServerConnectionState.WakeTimedOut })
                                }?.let(::add)
                            }
                        }
                    }.associateBy(AppNotice::key)
                previous.keys.filterNot { it in notices }.forEach(AppNotifications::removeConnectivity)
                notices.forEach { (key, notice) ->
                    if (previous[key] != notice) {
                        if (notice.phase == NoticePhase.ERROR) unhealthy.add(key)
                        val recovered = notice.phase == NoticePhase.SUCCESS && unhealthy.remove(key)
                        // A recovery is confirmed once, outside the player; it never covers running playback.
                        AppNotifications.post(if (recovered) notice.copy(quietSuccess = false, interruptPlayback = false) else notice)
                    }
                }
                previous = notices
            }
        }
        // A machine that answers again ends its wake card at once (also for NAS/DLNA members),
        // instead of the card counting on to its budget while the services start.
        scope.launch {
            var previous = emptyMap<String, com.ambient.tv.connectivity.HostHealth>()
            hosts.states.collect { states ->
                val health = states.mapValues { it.value.health }
                machinesNowOnline(previous, health).forEach(WakeNotices::machineOnline)
                previous = health
            }
        }
        scope.launch {
            var previous: WireGuardRouteState? = null
            vpn.status.collect { state ->
                if (state != previous) {
                    val systems = buildList {
                        if (preferences.wireGuardPlexEnabled) add("plex")
                        if (preferences.wireGuardJellyfinEnabled) add("jellyfin")
                        if (preferences.wireGuardEmbyEnabled) add("emby")
                        if (preferences.wireGuardTvheadendEnabled) add("tvheadend")
                        if (preferences.fileSources.any { it.wireGuard }) add("files")
                    }
                    val (phase, message) = when (state) {
                        WireGuardRouteState.Connecting -> NoticePhase.WORKING to R.string.notice_vpn_connecting
                        WireGuardRouteState.Up -> NoticePhase.SUCCESS to R.string.notice_vpn_connected
                        is WireGuardRouteState.Failed -> NoticePhase.ERROR to R.string.notice_vpn_failed_reason
                        WireGuardRouteState.Idle -> NoticePhase.INFO to R.string.notice_vpn_stopped
                    }
                    // Keep the prerequisite as one stable session row. The compact presenter
                    // aggregates it with its dependants and never replays intermediate successes.
                    if (state != WireGuardRouteState.Idle || previous != null) AppNotifications.post(AppNotice(
                        "vpn", message, systems, "WireGuard", phase, NoticeKind.VPN,
                        args = if (state is WireGuardRouteState.Failed) listOf(sanitizeFailureDetail(state.message)) else emptyList(),
                        quietSuccess = true, interruptPlayback = phase == NoticePhase.ERROR,
                    ))
                }
                previous = state
            }
        }
        scope.launch {
            var offline = false
            resume.status.collect { state ->
                if (state == ResumeConnectivityStatus.NoNetwork && !offline) {
                    AppNotifications.post(AppNotice("network", R.string.notice_network_lost, phase = NoticePhase.ERROR))
                    offline = true
                } else if (offline && (state == ResumeConnectivityStatus.Checking || state is ResumeConnectivityStatus.Progress)) {
                    AppNotifications.post(AppNotice("network", R.string.notice_network_restored, phase = NoticePhase.SUCCESS,
                        interruptPlayback = false))
                    offline = false
                }
            }
        }
        scope.launch {
            var previous = spotify.authState.value
            spotify.authState.collect { state ->
                when {
                    state.authorized && !previous.authorized -> AppNotifications.post(AppNotice(
                        "login:spotify", if (state.accountName.isNullOrBlank()) R.string.notice_signed_in else R.string.notice_signed_in_as,
                        listOf("spotify"), phase = NoticePhase.SUCCESS, kind = NoticeKind.LOGIN, args = listOfNotNull(state.accountName),
                    ))
                    state.error != null && state.error != previous.error -> {
                        val expired = previous.authorized && !state.authorized
                        val reason = sanitizeFailureDetail(state.error).takeIf(String::isNotBlank)
                        AppNotifications.post(AppNotice(
                            "login:spotify", when {
                                expired -> R.string.notice_session_expired
                                reason != null -> R.string.notice_login_failed_reason
                                else -> R.string.notice_login_failed
                            },
                            listOf("spotify"), phase = NoticePhase.ERROR, kind = NoticeKind.LOGIN,
                            args = if (!expired && reason != null) listOf(reason) else emptyList(),
                        ))
                    }
                    !state.authorized && previous.authorized -> {
                        val explicitLogout = AppNotifications.store.state.value.pending.any {
                            it.notice.key == "login:spotify" && it.notice.message == R.string.notice_signed_out
                        }
                        if (!explicitLogout) notifyLogin("spotify", NoticePhase.ERROR, message = R.string.notice_session_expired)
                    }
                }
                previous = state
            }
        }
    }

    private data class Target(
        val system: String,
        val host: String,
        val name: String?,
        val state: ServerConnectionState,
        val mac: String? = null,
        val vpnEnabled: Boolean = false,
        val cancelled: Boolean = false,
    )
}

/** Only a typed prerequisite failure is blocked; an unrelated service error remains its own error. */
internal fun connectionDependency(
    state: ServerConnectionState,
    vpnEnabled: Boolean,
    route: WireGuardRouteState,
): ConnectivityDependency? = when {
    state is ServerConnectionState.Error -> if (state.cause == com.ambient.tv.server.ServerConnectionFailure.VPN) ConnectivityDependency.VPN else null
    vpnEnabled && route == WireGuardRouteState.Connecting && state !is ServerConnectionState.Online -> ConnectivityDependency.VPN
    else -> null
}

/** Waking the machine itself (packets / waiting for it); not "machine awake, service starting". */
internal fun isMachineWaking(state: ServerConnectionState): Boolean =
    state == ServerConnectionState.SendingWakePacket ||
        (state is ServerConnectionState.Waking && !state.serviceStarting)

/** Machines whose health just turned ONLINE. */
internal fun machinesNowOnline(
    previous: Map<String, com.ambient.tv.connectivity.HostHealth>,
    current: Map<String, com.ambient.tv.connectivity.HostHealth>,
): List<String> = current.filter { (machine, health) ->
    health == com.ambient.tv.connectivity.HostHealth.ONLINE && previous[machine] != com.ambient.tv.connectivity.HostHealth.ONLINE
}.keys.toList()
