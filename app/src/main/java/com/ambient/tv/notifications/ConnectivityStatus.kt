package com.ambient.tv.notifications

import com.ambient.tv.R

/** A dependency is supplied by the operation that failed, never guessed from its error text. */
enum class ConnectivityDependency { NETWORK, VPN }

data class ConnectivityRow(
    val key: String,
    val notice: AppNotice,
    val sourceKeys: Set<String>,
    val cancelKey: String? = null,
    val blockedBy: ConnectivityDependency? = null,
    val details: List<AppNotice> = emptyList(),
)

/**
 * The live connection session is independent of the short-lived notification queue. Intro,
 * compact status and details read this same snapshot; hiding a surface never ends an operation.
 * Times use the store's monotonic clock, not the wake packet wall-clock budget.
 */
data class ConnectivitySnapshot(
    val sessionId: Long = 0L,
    val entries: List<NoticeEntry> = emptyList(),
    val startedAt: Long = 0L,
    val changedAt: Long = 0L,
    val completedAt: Long? = null,
    val completionPresentedAt: Long? = null,
    val minimized: Boolean = false,
    val presented: Boolean = false,
    val visible: Boolean = false,
) {
    val rows: List<ConnectivityRow> = connectivityRows(entries)
    val primary: AppNotice? = rows.minByOrNull(::connectivityPriority)?.notice
    val activeWakes: List<AppNotice> = entries.map(NoticeEntry::notice).filter {
        it.kind == NoticeKind.WAKE && it.phase == NoticePhase.WORKING
    }.let { wakes ->
        val machines = wakes.filter { it.key.startsWith("wake:") }.distinctBy(AppNotice::key)
        val covered = machines.flatMap(AppNotice::systems).toSet()
        machines + wakes.filter {
            !it.key.startsWith("wake:") && (it.systems.isEmpty() || !covered.containsAll(it.systems))
        }.distinctBy(AppNotice::key)
    }
    val workingCount: Int = rows.count { it.notice.phase == NoticePhase.WORKING }
    val readyCount: Int = rows.count { it.notice.phase == NoticePhase.SUCCESS }
    val hasErrors: Boolean = rows.any { it.notice.phase == NoticePhase.ERROR }
    val working: Boolean = workingCount > 0 || activeWakes.isNotEmpty()
}

internal fun AppNotice.isConnectivity(): Boolean =
    kind == NoticeKind.CONNECTION || kind == NoticeKind.WAKE || kind == NoticeKind.VPN

private fun AppNotice.connectivityRowKey(): String = when {
    machine != null -> "machine:$machine"
    key.startsWith("wake:") -> "machine:${key.removePrefix("wake:")}"
    key.startsWith("connection:") -> "machine:${key.removePrefix("connection:")}"
    else -> key
}

private fun waitingMessage(dependency: ConnectivityDependency): Int = when (dependency) {
    ConnectivityDependency.NETWORK -> R.string.notice_waiting_for_network
    ConnectivityDependency.VPN -> R.string.notice_waiting_for_vpn
}

/** One machine keeps one row as waking becomes service startup; independent failures stay visible. */
internal fun connectivityRows(entries: List<NoticeEntry>): List<ConnectivityRow> {
    if (entries.isEmpty()) return emptyList()
    val notices = entries.map(NoticeEntry::notice)
    val roots = mapOf(
        ConnectivityDependency.NETWORK to notices.lastOrNull { it.key == "network" },
        ConnectivityDependency.VPN to notices.lastOrNull { it.key == "vpn" },
    )
    val synthetic = mutableListOf<AppNotice>()
    val dependencies = ConnectivityDependency.entries.associateWith { dependency ->
        val explicit = roots[dependency]
        val blocked = notices.filter { it.blockedBy == dependency }
        // Configuration/consent can fail before the VPN controller starts a tunnel. Still show
        // one cause for all dependants, while a healthy existing route keeps endpoint errors local.
        if (blocked.isNotEmpty() && (explicit == null || explicit.phase == NoticePhase.INFO)) {
            val failed = blocked.any { it.phase == NoticePhase.ERROR }
            val reason = blocked.filter { it.phase == NoticePhase.ERROR }.firstNotNullOfOrNull {
                it.args.firstOrNull()?.let(::sanitizeFailureDetail)?.takeIf(String::isNotBlank)
            }
            AppNotice(
                key = if (dependency == ConnectivityDependency.VPN) "vpn" else "network",
                message = when (dependency) {
                    ConnectivityDependency.VPN -> when {
                        failed && reason != null -> R.string.notice_vpn_failed_reason
                        failed -> R.string.notice_vpn_failed
                        else -> R.string.notice_vpn_connecting
                    }
                    ConnectivityDependency.NETWORK -> if (failed) R.string.notice_network_lost else R.string.notice_waiting_for_network
                },
                systems = blocked.flatMap(AppNotice::systems).distinct(),
                phase = if (failed) NoticePhase.ERROR else NoticePhase.WORKING,
                kind = if (dependency == ConnectivityDependency.VPN) NoticeKind.VPN else NoticeKind.CONNECTION,
                args = if (dependency == ConnectivityDependency.VPN && failed && reason != null) listOf(reason) else emptyList(),
                quietSuccess = true,
            ).also(synthetic::add)
        } else explicit
    }
    val effective = notices.filterNot { notice -> synthetic.any { it.key == notice.key } } + synthetic
    return effective.groupBy(AppNotice::connectivityRowKey).map { (key, sources) ->
        val mapped = sources.map { notice ->
            val dependency = notice.blockedBy
            val root = dependency?.let(dependencies::get)
            when {
                dependency == null || root == null || notice.phase == NoticePhase.SUCCESS -> notice
                root.phase == NoticePhase.ERROR -> notice.copy(
                    phase = NoticePhase.INFO, message = waitingMessage(dependency), args = emptyList(),
                )
                root.phase == NoticePhase.WORKING -> notice.copy(
                    phase = NoticePhase.WORKING, message = waitingMessage(dependency), args = emptyList(),
                )
                else -> notice
            }
        }
        val workingWake = mapped.firstOrNull { it.kind == NoticeKind.WAKE && it.phase == NoticePhase.WORKING }
        val actionableWake = sources.firstOrNull { it.kind == NoticeKind.WAKE && it.phase == NoticePhase.WORKING }
        // A confirmed service result supersedes the machine's earlier wake result. A running
        // wake remains actionable while a sibling service reports its own independent error.
        val connections = mapped.filter { it.key.startsWith("connection:") }
        val relevant = if (connections.isNotEmpty()) connections + listOfNotNull(workingWake) else mapped
        val selected = relevant.firstOrNull { it.phase == NoticePhase.ERROR && it.blockedBy == null }
            ?: workingWake
            ?: relevant.firstOrNull { it.phase == NoticePhase.ERROR }
            ?: relevant.firstOrNull { it.phase == NoticePhase.WORKING }
            ?: relevant.lastOrNull { it.phase == NoticePhase.INFO }
            ?: relevant.last()
        ConnectivityRow(
            key = key,
            notice = selected.copy(systems = mapped.flatMap(AppNotice::systems).distinct()),
            sourceKeys = sources.mapTo(linkedSetOf(), AppNotice::key),
            cancelKey = actionableWake?.key,
            blockedBy = selected.blockedBy,
            details = sources,
        )
    }
}

private fun connectivityPriority(row: ConnectivityRow): Int = when {
    row.notice.phase == NoticePhase.ERROR && row.key == "network" -> 0
    row.notice.phase == NoticePhase.ERROR && row.key == "vpn" -> 1
    row.notice.phase == NoticePhase.ERROR -> 2
    row.notice.phase == NoticePhase.WORKING && row.key == "vpn" -> 3
    row.notice.phase == NoticePhase.WORKING && row.notice.kind == NoticeKind.WAKE -> 4
    row.notice.phase == NoticePhase.WORKING -> 5
    row.notice.phase == NoticePhase.INFO -> 6
    else -> 7
}

/** All retained active entries survive queue timeouts. Only old finished detail rows are bounded. */
internal fun ConnectivitySnapshot.postConnectivity(entry: NoticeEntry, now: Long): ConnectivitySnapshot {
    if (!entry.notice.isConnectivity()) return removeConnectivity(entry.notice.key, now)
    val startsOperation = entry.notice.phase == NoticePhase.WORKING || entry.notice.phase == NoticePhase.ERROR
    // Adjacent producer updates belong to one attempt. A later explicit retry starts a fresh
    // presentation after the old attempt settled, preserving other active operations.
    val fresh = sessionId == 0L || (startsOperation && !working && completedAt != null &&
        now - completedAt >= 700 && (entry.notice.phase == NoticePhase.WORKING || !hasErrors))
    val base = if (fresh) ConnectivitySnapshot(
        sessionId = sessionId + 1, startedAt = now, entries = entries,
    ) else this
    val previous = entries.firstOrNull { it.notice.key == entry.notice.key }?.notice
    val rowKey = entry.notice.connectivityRowKey()
    val replaced = base.entries.filterNot {
        it.notice.key == entry.notice.key ||
            (entry.notice.phase == NoticePhase.WORKING && it.notice.connectivityRowKey() == rowKey &&
                (it.notice.phase == NoticePhase.ERROR || it.notice.cancelled))
    } + entry
    val active = replaced.filter { it.notice.phase == NoticePhase.WORKING }
    val finished = replaced.filter { it.notice.phase != NoticePhase.WORKING }.takeLast(64)
    val updated = base.copy(entries = (active + finished).sortedBy(NoticeEntry::id), changedAt = now)
    val newError = entry.notice.phase == NoticePhase.ERROR &&
        (previous?.phase != NoticePhase.ERROR || previous.message != entry.notice.message || previous.args != entry.notice.args)
    val newResult = !updated.working && (base.hasErrors != updated.hasErrors || newError)
    val completed = if (updated.working) null else if (newResult) now else base.completedAt ?: now
    return updated.copy(
        completedAt = completed,
        completionPresentedAt = if (updated.working || newResult) null else base.completionPresentedAt,
    )
}

/** Operation owners use this for cancellation/removal; UI minimization never calls it. */
internal fun ConnectivitySnapshot.removeConnectivity(key: String, now: Long): ConnectivitySnapshot {
    if (entries.none { it.notice.key == key }) return this
    val updated = copy(entries = entries.filterNot { it.notice.key == key }, changedAt = now)
    return updated.copy(
        completedAt = if (updated.working) null else completedAt ?: now,
        visible = visible && updated.entries.isNotEmpty(),
    )
}

internal fun ConnectivitySnapshot.tickConnectivity(now: Long, playback: Boolean, timeoutScale: Float): ConnectivitySnapshot {
    val canInterrupt = !playback || hasErrors || entries.any { it.notice.interruptPlayback }
    val show = entries.isNotEmpty() && !minimized && canInterrupt && when {
        working -> hasErrors || now - startedAt >= 700
        hasErrors -> completionPresentedAt?.let { now - it < (7_000 * timeoutScale).toLong() }
            ?: (now - (completedAt ?: changedAt) < 30_000)
        !presented -> false
        else -> completionPresentedAt?.let { now - it < (3_000 * timeoutScale).toLong() }
            ?: (now - (completedAt ?: changedAt) < 3_000)
    }
    return if (visible == show) this else copy(visible = show)
}

internal fun ConnectivitySnapshot.markPresented(now: Long): ConnectivitySnapshot = when {
    !visible || minimized -> this
    working -> if (presented) this else copy(presented = true)
    completionPresentedAt == null -> copy(presented = true, completionPresentedAt = now)
    presented -> this
    else -> copy(presented = true)
}
