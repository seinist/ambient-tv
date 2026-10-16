package com.ambient.tv.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NoticePhase { WORKING, SUCCESS, ERROR, INFO }
enum class NoticeKind { CONNECTION, WAKE, VPN, LOGIN, LIBRARY, RECORDING, SETTINGS, PLAYBACK }

/** The user-facing lifetime choice. Errors keep their longer base lifetime. */
enum class NotificationDisplayDuration(val key: String, val timeoutScale: Float, val enabled: Boolean = true) {
    OFF("off", 0f, false),
    SHORT("short", 2f / 3f),
    NORMAL("normal", 1f),
    LONG("long", 5f / 3f);

    companion object {
        val KEYS = entries.mapTo(mutableSetOf()) { it.key }
        fun fromKey(key: String?): NotificationDisplayDuration = entries.firstOrNull { it.key == key } ?: NORMAL
    }
}

data class AppNotice(
    val key: String,
    val message: Int,
    val systems: List<String> = emptyList(),
    val subject: String? = null,
    val phase: NoticePhase = NoticePhase.INFO,
    val kind: NoticeKind = NoticeKind.CONNECTION,
    val args: List<String> = emptyList(),
    /** Automatic checks should not announce a fast, unchanged healthy connection. */
    val quietSuccess: Boolean = false,
    val interruptPlayback: Boolean = true,
)

data class NoticeEntry(
    val id: Long,
    val notice: AppNotice,
    val createdAt: Long,
    val changedAt: Long,
    val wallTime: Long,
    val shownAt: Long? = null,
)

data class NotificationSnapshot(
    val pending: List<NoticeEntry> = emptyList(),
    val visible: List<NoticeEntry> = emptyList(),
    val history: List<NoticeEntry> = emptyList(),
)

/**
 * Several producers of one in-progress notice (e.g. systems waking the same machine) keep each
 * other's systems instead of replacing them. Results carry their own final system list.
 */
internal fun mergeWorkingSystems(old: AppNotice?, new: AppNotice): AppNotice =
    if (old != null && old.phase == NoticePhase.WORKING && new.phase == NoticePhase.WORKING && old.kind == new.kind)
        new.copy(systems = (old.systems + new.systems).distinct()) else new

/** Process-local, bounded queue. No Activity, server credentials or exception text is retained. */
class NotificationStore(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(NotificationSnapshot())
    val state = mutable.asStateFlow()
    private var sequence = 0L

    @Synchronized
    fun post(notice: AppNotice) {
        val state = mutable.value
        val old = state.pending.firstOrNull { it.notice.key == notice.key }
        @Suppress("NAME_SHADOWING")
        val notice = mergeWorkingSystems(old?.notice, notice)
        if (old?.notice == notice) return
        val now = clock()
        val entry = NoticeEntry(
            id = old?.id ?: ++sequence,
            notice = notice,
            createdAt = old?.createdAt ?: now,
            changedAt = now,
            wallTime = wallClock(),
            // Completion gets its full reading time even if progress has been visible for minutes.
            shownAt = if (old?.notice?.phase == notice.phase) old.shownAt else null,
        )
        val quiet = notice.quietSuccess && notice.phase == NoticePhase.SUCCESS && old?.shownAt == null
        val pending = state.pending.filterNot { it.notice.key == notice.key } + if (quiet) emptyList() else listOf(entry)
        val history = if (notice.phase == NoticePhase.WORKING || quiet) state.history else
            (listOf(entry) + state.history.filterNot { it.id == entry.id }).take(20)
        mutable.value = state.copy(pending = pending.takeLast(32), history = history)
    }

    @Synchronized
    fun dismiss(key: String) {
        mutable.value = mutable.value.let { state -> state.copy(
            pending = state.pending.filterNot { it.notice.key == key },
            visible = state.visible.filterNot { it.notice.key == key },
        ) }
    }

    @Synchronized
    fun clearHistory() { mutable.value = mutable.value.copy(history = emptyList()) }

    @Synchronized
    fun cancelWorking(key: String) {
        if (mutable.value.pending.any { it.notice.key == key && it.notice.phase == NoticePhase.WORKING }) dismiss(key)
    }

    /** Reading time starts when a card can actually be seen, including after a dialog closes. */
    @Synchronized
    fun tick(presenting: Boolean = true, playback: Boolean = false, timeoutScale: Float = 1f) {
        val now = clock()
        val state = mutable.value
        var pending = state.pending.filter { entry ->
            val duration = if (entry.notice.phase == NoticePhase.ERROR) 7_000 else 3_000
            when {
                entry.notice.phase == NoticePhase.WORKING -> now - entry.changedAt <
                    (if (entry.notice.kind == NoticeKind.LOGIN) 16 * 60_000 else 4 * 60_000)
                entry.shownAt != null -> now - entry.shownAt < (duration * timeoutScale).toLong()
                else -> now - entry.changedAt < 30_000
            }
        }
        // Independent startup/resume observers may still report connecting during a wake.
        // Keep their state queued, but present the prerequisite until waking has completed.
        val wakingSystems = pending.filter {
            it.notice.kind == NoticeKind.WAKE && it.notice.phase == NoticePhase.WORKING
        }.flatMap { it.notice.systems }.toSet()
        val selected = if (!presenting) emptyList() else pending
            .filter { it.notice.phase != NoticePhase.WORKING || now - it.createdAt >= 700 }
            .filterNot {
                it.notice.kind == NoticeKind.CONNECTION && it.notice.phase == NoticePhase.WORKING &&
                    it.notice.systems.isNotEmpty() && it.notice.systems.all(wakingSystems::contains)
            }
            .filter { !playback || it.notice.interruptPlayback || it.notice.phase == NoticePhase.ERROR }
            .sortedWith(compareBy<NoticeEntry> {
                when (it.notice.phase) { NoticePhase.ERROR -> 0; NoticePhase.WORKING -> 2; else -> 1 }
            }.thenByDescending { it.changedAt }.thenBy { it.id })
            .groupBy {
                if (it.notice.kind in setOf(NoticeKind.CONNECTION, NoticeKind.WAKE, NoticeKind.VPN))
                    "connectivity" else "notice:${it.id}"
            }.values.take(2).flatten().map { it.id }.toSet()
        pending = pending.map { if (it.id in selected && it.shownAt == null) it.copy(shownAt = now) else it }
        mutable.value = state.copy(pending = pending, visible = pending.filter { it.id in selected })
    }
}

/** Shared by background operations and UI; producers publish only confirmed operation results. */
object AppNotifications {
    val store = NotificationStore()
    fun post(notice: AppNotice) {
        if (notice.phase == NoticePhase.ERROR) com.ambient.tv.diagnostics.DiagnosticLog.notice(notice)
        store.post(notice)
    }
    fun dismiss(key: String) = store.dismiss(key)
}
