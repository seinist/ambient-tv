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
) {
    private enum class State { WORKING, AWAKE, FAILED }
    private class Member(val system: String, var state: State)
    private class Group(val members: LinkedHashMap<String, Member> = linkedMapOf(), val subjects: LinkedHashSet<String> = linkedSetOf())

    private val groups = mutableMapOf<String, Group>()

    fun noticeKey(machine: String) = "wake:$machine"

    /** [member] identifies the caller within its system (e.g. "files:<id>"); defaults to [system]. */
    @Synchronized
    fun begin(machine: String, system: String, subject: String?, member: String = system) {
        val group = groups.getOrPut(machine) { Group() }
        val existing = group.members[member]
        if (existing?.state == State.WORKING) return
        group.members[member] = Member(system, State.WORKING)
        subject?.takeIf(String::isNotBlank)?.let(group.subjects::add)
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
                AppNotice(noticeKey(machine), R.string.notice_waking, all, subject, NoticePhase.WORKING, NoticeKind.WAKE)
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
