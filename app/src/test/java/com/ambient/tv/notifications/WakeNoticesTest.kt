package com.ambient.tv.notifications

import com.ambient.tv.R
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeNoticesTest {
    private val posted = mutableListOf<AppNotice>()
    private val dismissed = mutableListOf<String>()
    private val tracker = WakeNoticeTracker({ posted += it }, { dismissed += it })

    @Test
    fun systemsOnOneMachineShareOneNoticeAndSucceedOnceAllAreAwake() {
        tracker.begin("mac:aa", "plex", "Server")
        tracker.begin("mac:aa", "tvheadend", "Server")
        assertEquals(setOf("wake:mac:aa"), posted.map { it.key }.toSet())
        assertEquals(listOf("plex", "tvheadend"), posted.last().systems)
        assertEquals(NoticePhase.WORKING, posted.last().phase)

        tracker.finish("mac:aa", "tvheadend", success = true)
        assertEquals(NoticePhase.WORKING, posted.last().phase)
        tracker.finish("mac:aa", "plex", success = true)
        assertEquals(NoticePhase.SUCCESS, posted.last().phase)
        assertEquals(R.string.notice_awake, posted.last().message)
        assertEquals(1, posted.count { it.phase == NoticePhase.SUCCESS })
    }

    @Test
    fun wakeBudgetBecomesProgressAndTheLongestBudgetWins() {
        var now = 1_000L
        val timed = WakeNoticeTracker({ posted += it }, { dismissed += it }, nowMs = { now })
        timed.begin("mac:aa", "plex", null)
        assertEquals(0L, posted.last().progressTotalMs)
        timed.setBudget("mac:aa", 120_000L)
        assertEquals(1_000L, posted.last().progressStartedAt)
        assertEquals(120_000L, posted.last().progressTotalMs)
        now = 11_000L
        timed.begin("mac:aa", "tvheadend", null, budgetMs = 180_000L)
        assertEquals(190_000L, posted.last().progressTotalMs)
        timed.setBudget("mac:aa", 60_000L)
        assertEquals(190_000L, posted.last().progressTotalMs)
        timed.finish("mac:aa", "plex", success = true)
        timed.finish("mac:aa", "tvheadend", success = true)
        assertEquals(NoticePhase.SUCCESS, posted.last().phase)
        assertEquals(0L, posted.last().progressTotalMs)
    }

    @Test
    fun eachWakePacketIsShownWithTheNextOneAndTheResult() {
        var now = 0L
        val timed = WakeNoticeTracker({ posted += it }, { dismissed += it }, nowMs = { now })
        timed.begin("mac:aa", "plex", null)
        timed.sent("mac:aa", 0, 3, 60_000L)
        assertEquals(1, posted.last().packetsSent)
        assertEquals(3, posted.last().packets)
        assertEquals(60_000L, posted.last().nextPacketAt)
        val count = posted.size
        timed.sent("mac:aa", 0, 3, 60_000L)
        assertEquals(count, posted.size)
        now = 60_000L
        timed.sent("mac:aa", 2, 3, 60_000L)
        assertEquals(3, posted.last().packetsSent)
        assertEquals(0L, posted.last().nextPacketAt)
        timed.finish("mac:aa", "plex", success = false)
        assertEquals(NoticePhase.ERROR, posted.last().phase)
        assertEquals(3, posted.last().packetsSent)
    }

    @Test
    fun anyFailureReportsTheFailedSystems() {
        tracker.begin("mac:aa", "plex", null)
        tracker.begin("mac:aa", "tvheadend", null)
        tracker.finish("mac:aa", "plex", success = true)
        tracker.finish("mac:aa", "tvheadend", success = false)
        assertEquals(NoticePhase.ERROR, posted.last().phase)
        assertEquals(R.string.notice_wake_failed, posted.last().message)
        assertEquals(listOf("tvheadend"), posted.last().systems)
    }

    @Test
    fun differentMachinesKeepSeparateNotices() {
        tracker.begin("mac:aa", "plex", null)
        tracker.begin("mac:bb", "files", null, member = "files:1")
        assertEquals(setOf("wake:mac:aa", "wake:mac:bb"), posted.map { it.key }.toSet())
    }

    @Test
    fun lastCancelledMemberDismisses() {
        tracker.begin("mac:aa", "plex", null)
        tracker.cancel("mac:aa", "plex")
        assertEquals(listOf("wake:mac:aa"), dismissed)
    }

    @Test
    fun storeMergesSystemsOfWorkingNoticesWithTheSameKey() {
        val store = NotificationStore()
        store.post(AppNotice("wake:m", R.string.notice_waking, listOf("plex"), phase = NoticePhase.WORKING, kind = NoticeKind.WAKE))
        store.post(AppNotice("wake:m", R.string.notice_waking, listOf("tvheadend"), phase = NoticePhase.WORKING, kind = NoticeKind.WAKE))
        val pending = store.state.value.pending
        assertEquals(1, pending.size)
        assertEquals(listOf("plex", "tvheadend"), pending.single().notice.systems)
    }
}
