package com.ambient.tv.notifications

import com.ambient.tv.R

/**
 * One wake notice per machine, whichever systems wake it. Callers pass the shared machine key
 * (see [com.ambient.tv.wol.WakeMachines]); every system joining that key adds itself to the
 * same card. The result is announced once the last system of the machine has finished.
 */
class WakeNoticeTracker(
    private val post: (AppNotice) -> Unit,
    private val dismiss: (String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private enum class State { WORKING, AWAKE, FAILED }
    private class Member(val system: String, var state: State)
    private class Group(
        val members: LinkedHashMap<String, Member> = linkedMapOf(),
        val subjects: LinkedHashSet<String> = linkedSetOf(),
        val startedAt: Long = 0L,
        /** Latest moment any member's wake may still succeed; 0 while no budget is known. */
        var deadline: Long = 0L,
    )

    private val groups = mutableMapOf<String, Group>()

    fun noticeKey(machine: String) = "wake:$machine"

    /**
     * [member] identifies the caller within its system (e.g. "files:<id>"); defaults to [system].
     * [budgetMs] is how long this wake may take at most (all retries); the card shows it as progress.
     */
    @Synchronized
    fun begin(machine: String, system: String, subject: String?, member: String = system, budgetMs: Long = 0L) {
        val group = groups.getOrPut(machine) { Group(startedAt = nowMs()) }
        if (budgetMs > 0) group.deadline = maxOf(group.deadline, nowMs() + budgetMs)
        val existing = group.members[member]
        if (existing?.state == State.WORKING) return
        group.members[member] = Member(system, State.WORKING)
        subject?.takeIf(String::isNotBlank)?.let(group.subjects::add)
        publish(machine, group)
    }

    /**
     * Manual wake: starts the machine's card only when nothing on that machine is waking yet.
     * Returns false (and changes nothing) while a wake for the machine is already running.
     */
    @Synchronized
    fun tryBegin(machine: String, system: String, subject: String?, member: String = system, budgetMs: Long = 0L): Boolean {
        if (isWaking(machine)) return false
        begin(machine, system, subject, member, budgetMs)
        return true
    }

    /** A running wake learned its total budget ([totalMs] from the wake's start). */
    @Synchronized
    fun setBudget(machine: String, totalMs: Long) {
        val group = groups[machine] ?: return
        if (totalMs <= 0 || group.members.values.none { it.state == State.WORKING }) return
        val deadline = maxOf(group.deadline, group.startedAt + totalMs)
        if (deadline == group.deadline) return
        group.deadline = deadline
        publish(machine, group)
    }

    @Synchronized
    fun finish(machine: String, system: String, success: Boolean, member: String = system) {
        val group = groups[machine] ?: return
        val entry = group.members[member] ?: return
        entry.state = if (success) State.AWAKE else State.FAILED
        publish(machine, group)
    }

    /** The caller stopped without a result (cancelled); it no longer holds the notice open. */
    @Synchronized
    fun cancel(machine: String, system: String, member: String = system) {
        val group = groups[machine] ?: return
        group.members.remove(member) ?: return
        if (group.members.isEmpty()) {
            groups.remove(machine)
            dismiss(noticeKey(machine))
        } else publish(machine, group)
    }

    @Synchronized
    fun isWaking(machine: String): Boolean = groups[machine]?.members?.values?.any { it.state == State.WORKING } == true

    private fun publish(machine: String, group: Group) {
        val members = group.members.values
        val subject = group.subjects.joinToString(", ").ifBlank { null }
        val all = members.map { it.system }.distinct()
        val notice = when {
            members.any { it.state == State.WORKING } ->
                AppNotice(noticeKey(machine), R.string.notice_waking, all, subject, NoticePhase.WORKING, NoticeKind.WAKE,
                    progressStartedAt = if (group.deadline > 0) group.startedAt else 0L,
                    progressTotalMs = if (group.deadline > 0) (group.deadline - group.startedAt).coerceAtLeast(0L) else 0L)
            members.any { it.state == State.FAILED } ->
                AppNotice(noticeKey(machine), R.string.notice_wake_failed,
                    members.filter { it.state == State.FAILED }.map { it.system }.distinct(), subject, NoticePhase.ERROR, NoticeKind.WAKE)
            else -> AppNotice(noticeKey(machine), R.string.notice_awake, all, subject, NoticePhase.SUCCESS, NoticeKind.WAKE)
        }
        // A finished machine starts a fresh card on its next wake.
        if (notice.phase != NoticePhase.WORKING) groups.remove(machine)
        post(notice)
    }
}

/** Process-wide tracker feeding [AppNotifications]. */
val WakeNotices = WakeNoticeTracker(AppNotifications::post, AppNotifications::dismiss)
