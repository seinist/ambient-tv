package com.ambient.tv.connectivity

import com.ambient.tv.notifications.AppNotice
import com.ambient.tv.notifications.ConnectivityDependency
import com.ambient.tv.notifications.ConnectivitySnapshot
import com.ambient.tv.notifications.NoticeKind
import com.ambient.tv.notifications.NoticePhase

/** Dispatchable targets only: healthy or already connecting media services are left out. */
internal data class ConnectionRetryTarget(
    val id: String,
    val system: String,
    val machine: String?,
    val manual: Boolean = false,
    val requiresVpn: Boolean = false,
    /** Retained manual requests preserve an unsaved Settings endpoint. */
    val preferred: Boolean = false,
)

internal data class ConnectionRetryPlan(
    val checkNetwork: Boolean = false,
    val restoreVpn: Boolean = false,
    val systems: Set<String> = emptySet(),
    val manualIds: Set<String> = emptySet(),
) {
    val possible: Boolean get() = checkNetwork || restoreVpn || systems.isNotEmpty() || manualIds.isNotEmpty()
}

internal fun connectionNoticeMachine(notice: AppNotice): String? = notice.machine
    ?: when {
        notice.key.startsWith("wake:") -> notice.key.removePrefix("wake:")
        notice.key.startsWith("connection:") -> notice.key.removePrefix("connection:").removeSuffix(":vpn")
        else -> null
    }?.takeIf { it.startsWith("mac:") || it.startsWith("host:") || it.startsWith("system:") }

/** Root rows may synthesize a prerequisite error while the raw controller still says Idle. */
internal fun currentConnectionRetryNotice(requested: AppNotice, snapshot: ConnectivitySnapshot): AppNotice? =
    if (requested.key == "vpn" || requested.key == "network") {
        snapshot.rows.firstOrNull { it.notice.key == requested.key }?.notice
    } else snapshot.entries.firstOrNull { it.notice.key == requested.key }?.notice

/** A root VPN/network retry selects only its failed dependants; a device retry stays on that device. */
internal fun planConnectionRetry(
    notice: AppNotice,
    session: List<AppNotice>,
    targets: List<ConnectionRetryTarget>,
    vpnConfigured: Boolean,
): ConnectionRetryPlan {
    fun AppNotice.retryable() = phase == NoticePhase.ERROR || cancelled
    if (!notice.retryable()) return ConnectionRetryPlan()
    val vpnCause = notice.kind == NoticeKind.VPN
    val networkCause = notice.key == "network"
    val cause = when {
        vpnCause -> ConnectivityDependency.VPN
        networkCause -> ConnectivityDependency.NETWORK
        else -> null
    }
    val selected = when {
        networkCause -> session.filter { it.phase == NoticePhase.ERROR && !it.cancelled && it.key != "network" &&
            it.kind != NoticeKind.VPN && (it.blockedBy == null || it.blockedBy == ConnectivityDependency.NETWORK) }
        cause != null -> session.filter { it.phase == NoticePhase.ERROR && !it.cancelled && it.blockedBy == cause }
        else -> listOf(notice)
    }
    val matching = targets.filter { target -> (networkCause && !target.manual && session.none {
        it.cancelled && target.system in it.systems &&
            (connectionNoticeMachine(it) == null || connectionNoticeMachine(it) == target.machine)
    }) || selected.any { entry ->
        val machine = connectionNoticeMachine(entry)
        val exactSystem = target.system in entry.systems || target.id in entry.systems
        when {
            cause != null -> exactSystem && (machine == null || machine == target.machine) &&
                (!vpnCause || target.requiresVpn)
            machine != null -> machine == target.machine
            // An unscoped "files" or "dlna" notice cannot identify one of several saved servers.
            target.manual -> target.id in entry.systems
            else -> exactSystem
        }
    } }
    val systems = matching.filterNot { it.manual }.mapTo(linkedSetOf()) { it.system }
    val mediaMachines = matching.filterNot { it.manual }.mapNotNull { it.machine }.toSet()
    // One wake/probe per physical device. A media reconnect already handles that machine's WOL.
    val manual = matching.filter { it.manual && it.machine !in mediaMachines }
        .groupBy { it.machine ?: it.id }.values.map { choices ->
            choices.sortedWith(compareByDescending<ConnectionRetryTarget> { candidate ->
                selected.any { candidate.system in it.systems || candidate.id in it.systems }
            }.thenByDescending { it.preferred }.thenBy { it.id }).first()
        }
    val restoreVpn = (vpnCause && vpnConfigured) ||
        selected.any { it.blockedBy == ConnectivityDependency.VPN } ||
        (matching.filterNot { it.manual } + manual).any { it.requiresVpn }
    return ConnectionRetryPlan(networkCause, restoreVpn, systems, manual.mapTo(linkedSetOf()) { it.id })
}

/** Batch selection includes offline siblings once, without reconnecting healthy machines. */
internal fun selectedOfflineRetryTargets(
    offline: Set<String>,
    selected: Set<String>?,
    machineFor: (String) -> String?,
): Set<String> {
    if (selected == null) return offline
    val selectedOffline = selected.intersect(offline)
    val machines = selectedOffline.mapNotNull(machineFor).toSet()
    return offline.filterTo(linkedSetOf()) { it in selectedOffline || machineFor(it)?.let(machines::contains) == true }
}
