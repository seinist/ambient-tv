package com.ambient.tv.notifications

import org.junit.Assert.*
import org.junit.Test

class NotificationStoreTest {
    private var now = 0L
    private val store = NotificationStore({ now }, { 1234L + now })
    private fun notice(key: String = "server", phase: NoticePhase = NoticePhase.WORKING, quiet: Boolean = false) =
        AppNotice(key, 1, listOf("plex"), "Media server", phase, kind = NoticeKind.SETTINGS, quietSuccess = quiet)

    @Test fun wakeHidesConcurrentConnectionUntilWakeFinishes() {
        store.post(notice("resume:tvheadend").copy(kind = NoticeKind.CONNECTION, systems = listOf("tvheadend")))
        store.post(notice("wake:server").copy(kind = NoticeKind.WAKE, systems = listOf("tvheadend", "plex")))
        now = 700
        store.tick()
        assertEquals(listOf("wake:server"), store.state.value.visible.map { it.notice.key })
        store.post(notice("wake:server", NoticePhase.SUCCESS).copy(kind = NoticeKind.WAKE))
        store.tick()
        assertTrue(store.state.value.visible.any { it.notice.key == "resume:tvheadend" })
    }

    @Test fun allConcurrentConnectionsShareOnePresentationSlot() {
        repeat(4) { store.post(notice("connection:$it").copy(kind = NoticeKind.CONNECTION)) }
        store.post(notice("unrelated", NoticePhase.ERROR))
        now = 700
        store.tick()
        assertEquals(5, store.state.value.visible.size)
        assertTrue(store.state.value.visible.any { it.notice.key == "unrelated" })
    }

    @Test fun fastHealthyChecksStaySilentAndDoNotFillHistory() {
        store.post(notice(quiet = true))
        now = 600
        store.tick()
        assertTrue(store.state.value.visible.isEmpty())
        store.post(notice(phase = NoticePhase.SUCCESS, quiet = true))
        store.tick()
        assertTrue(store.state.value.pending.isEmpty())
        assertTrue(store.state.value.history.isEmpty())
    }

    @Test fun wakeConnectionAndCompletionKeepOneCardAndGiveSuccessFullReadingTime() {
        store.post(notice().copy(kind = NoticeKind.WAKE))
        now = 700; store.tick()
        val id = store.state.value.visible.single().id
        now = 40_000
        store.post(notice().copy(message = 2))
        store.tick()
        assertEquals(id, store.state.value.visible.single().id)
        store.post(notice(phase = NoticePhase.SUCCESS, quiet = true))
        store.tick()
        assertEquals(id, store.state.value.visible.single().id)
        now += 2_999; store.tick()
        assertEquals(1, store.state.value.visible.size)
        now++; store.tick()
        assertTrue(store.state.value.visible.isEmpty())
        assertEquals(1, store.state.value.history.size)
    }

    @Test fun errorPreemptsProgressAndQueuedCardsGetTheirOwnReadingTime() {
        store.post(notice("a")); store.post(notice("b"))
        now = 700; store.tick()
        store.post(notice("c", NoticePhase.ERROR)); store.tick()
        assertEquals(2, store.state.value.visible.size)
        assertTrue(store.state.value.visible.any { it.notice.key == "c" })
        store.post(notice("d")); store.tick()
        assertFalse(store.state.value.visible.any { it.notice.key == "d" })
        store.dismiss("a"); store.dismiss("b")
        now += 5_000
        store.post(notice("d", NoticePhase.SUCCESS)); store.tick()
        assertTrue(store.state.value.visible.any { it.notice.key == "d" })
        now += 2_999; store.tick()
        assertTrue(store.state.value.visible.any { it.notice.key == "d" })
    }

    @Test fun playbackSuppressesRoutineProgressButKeepsErrorsAndUserActions() {
        store.post(notice("check").copy(interruptPlayback = false))
        store.post(notice("error", NoticePhase.ERROR).copy(interruptPlayback = false))
        now = 700; store.tick(playback = true)
        assertEquals("error", store.state.value.visible.single().notice.key)
        store.post(notice("action", NoticePhase.SUCCESS)); store.tick(playback = true)
        assertEquals(setOf("error", "action"), store.state.value.visible.map { it.notice.key }.toSet())
    }

    @Test fun hiddenCardsDoNotConsumeReadingTimeButStaleResultsAreDiscarded() {
        store.post(notice(phase = NoticePhase.SUCCESS))
        store.tick(presenting = false)
        now = 10_000; store.tick()
        assertEquals(now, store.state.value.visible.single().shownAt)
        store.post(notice("old", NoticePhase.SUCCESS))
        now = 41_000; store.tick()
        assertTrue(store.state.value.pending.isEmpty())
    }

    @Test fun repeatedProgressDoesNotRestartDelayOrCreateHistoryAndHistoryIsBounded() {
        store.post(notice())
        now = 600; store.post(notice())
        now = 700; store.tick()
        assertEquals(1, store.state.value.visible.size)
        repeat(30) { store.post(notice("result:$it", NoticePhase.SUCCESS)) }
        assertEquals(20, store.state.value.history.size)
        store.clearHistory()
        assertTrue(store.state.value.history.isEmpty())
        assertFalse(store.state.value.pending.isEmpty())
    }

    @Test fun accessibilityExtendsReadingTime() {
        store.post(notice(phase = NoticePhase.ERROR)); store.tick(timeoutScale = 2f)
        now = 13_999; store.tick(timeoutScale = 2f)
        assertEquals(1, store.state.value.visible.size)
        now++; store.tick(timeoutScale = 2f)
        assertTrue(store.state.value.visible.isEmpty())
    }

    @Test fun shortDurationReducesTerminalReadingTimeAndInvalidChoiceFallsBackToNormal() {
        store.post(notice(phase = NoticePhase.SUCCESS)); store.tick(timeoutScale = NotificationDisplayDuration.SHORT.timeoutScale)
        now = 1_999; store.tick(timeoutScale = NotificationDisplayDuration.SHORT.timeoutScale)
        assertEquals(1, store.state.value.visible.size)
        now++; store.tick(timeoutScale = NotificationDisplayDuration.SHORT.timeoutScale)
        assertTrue(store.state.value.visible.isEmpty())
        assertEquals(NotificationDisplayDuration.NORMAL, NotificationDisplayDuration.fromKey("unexpected"))
    }

    @Test fun concurrentProviderResultsAreNotLost() {
        val threads = (0..15).map { n -> Thread { store.post(notice("provider:$n", NoticePhase.SUCCESS)) }.apply { start() } }
        threads.forEach { it.join() }
        assertEquals(16, store.state.value.pending.size)
        assertEquals(16, store.state.value.history.size)
        assertEquals(16, store.state.value.pending.map { it.id }.distinct().size)
    }
}
