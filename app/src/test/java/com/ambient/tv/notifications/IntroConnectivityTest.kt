package com.ambient.tv.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class IntroConnectivityTest {
    private fun entry(id: Long, kind: NoticeKind, phase: NoticePhase, vararg systems: String) =
        NoticeEntry(id, AppNotice("k$id", 0, systems.toList(), phase = phase, kind = kind), 0, 0, 0)

    @Test
    fun connectCoveredByItsWakeIsLeftToTheWake() {
        val snapshot = NotificationSnapshot(pending = listOf(
            entry(1, NoticeKind.WAKE, NoticePhase.WORKING, "plex"),
            entry(2, NoticeKind.CONNECTION, NoticePhase.WORKING, "plex"),
            entry(3, NoticeKind.CONNECTION, NoticePhase.WORKING, "jellyfin"),
        ))
        assertEquals(listOf(1L, 3L), workingConnectivity(snapshot).map { it.id })
    }

    @Test
    fun finishedAndUnrelatedNoticesDoNotHoldTheIntro() {
        val snapshot = NotificationSnapshot(pending = listOf(
            entry(1, NoticeKind.WAKE, NoticePhase.SUCCESS, "plex"),
            entry(2, NoticeKind.LIBRARY, NoticePhase.WORKING),
        ))
        assertEquals(emptyList<Long>(), workingConnectivity(snapshot).map { it.id })
    }
}
