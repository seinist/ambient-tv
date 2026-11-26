package com.ambient.tv.ui.games

import com.ambient.tv.ui.player.DisplayModeClaim
import com.ambient.tv.ui.player.DisplayModeOrigin
import com.ambient.tv.ui.player.SupportedDisplayMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GameDisplaySessionTest {
    private val desktop = SupportedDisplayMode("desktop", 1, 3840, 2160, 60f)
    private val game = SupportedDisplayMode("game", 2, 1920, 1080, 50f)
    private val next = SupportedDisplayMode("next", 3, 1920, 1080, 60f)

    private inner class FakeHost : GameDisplayHost {
        var actual = desktop
        var preference: Int? = desktop.modeId
        var video = false
        var accepts = true
        var throwsOnRequest = false
        var cancelConfirmation = false
        var inherited = false
        var captures = 0
        var releases = 0
        var handoffs = 0
        val requests = mutableListOf<Int>()
        val restores = mutableListOf<Int?>()
        override fun actualMode() = actual
        override fun modes() = listOf(desktop, game, next)
        override fun preferenceId() = preference
        override fun videoOwnsDisplay() = video
        override fun captureOrigin(): DisplayModeOrigin {
            captures++
            return DisplayModeOrigin(DisplayModeClaim(desktop.modeId, desktop.key), inherited)
        }
        override fun releaseOrigin(origin: DisplayModeOrigin) { releases++ }
        override fun handoff(origin: DisplayModeOrigin) { handoffs++ }
        override fun request(mode: SupportedDisplayMode, settleMs: Int) {
            if (throwsOnRequest) error("mode rejected")
            requests.add(mode.modeId)
            preference = mode.modeId
            if (accepts) actual = mode
        }
        override fun restore(modeId: Int?) {
            restores.add(modeId)
            preference = modeId
            actual = modes().firstOrNull { it.modeId == modeId } ?: desktop
        }
        override suspend fun confirm(key: String, timeoutMs: Long): Boolean {
            if (cancelConfirmation) {
                cancelConfirmation = false
                throw CancellationException("core detached")
            }
            return actual.key == key
        }
    }

    @Test fun nativeRestartRetainsOriginalModeAndDoesNotRequestTwice() = runBlocking {
        val host = FakeHost()
        val session = GameDisplaySession(host)
        assertEquals(desktop, session.baseline())
        assertTrue(session.apply(game, 0))
        assertEquals(desktop, session.baseline())
        assertTrue(session.apply(game, 0))
        assertEquals(listOf(game.modeId), host.requests)
        session.close()
        session.close()
        assertEquals(listOf(desktop.modeId), host.restores)
        assertEquals(1, host.captures)
        assertEquals(1, host.releases)
        assertFalse(session.hasLease)
    }

    @Test fun rejectedModeRollsBackAndDoesNotLoop() = runBlocking {
        val host = FakeHost().apply { accepts = false }
        val session = GameDisplaySession(host)
        session.baseline()
        assertFalse(session.apply(game, 0))
        assertFalse(session.apply(game, 0))
        assertEquals(listOf(game.modeId), host.requests)
        assertEquals(listOf(desktop.modeId), host.restores)
        session.close()
        assertEquals(1, host.restores.size)
    }

    @Test fun cancellationKeepsLeaseForReplacementCore() = runBlocking {
        val host = FakeHost().apply { cancelConfirmation = true }
        val session = GameDisplaySession(host)
        session.baseline()
        try {
            session.apply(game, 0)
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(session.hasLease)
        }
        assertTrue(session.apply(game, 0))
        assertEquals(listOf(game.modeId), host.requests)
        session.close()
        assertEquals(listOf(desktop.modeId), host.restores)
    }

    @Test fun failedReplacementRestoresPreviousLease() = runBlocking {
        val host = FakeHost()
        val session = GameDisplaySession(host)
        session.baseline()
        assertTrue(session.apply(game, 0))
        host.throwsOnRequest = true
        assertFalse(session.apply(next, 0))
        assertEquals(listOf(desktop.modeId), host.restores)
        session.close()
        assertEquals(1, host.restores.size)
    }

    @Test fun disablingInheritedModeRestoresDesktopEvenWithoutNewRequest() = runBlocking {
        val host = FakeHost().apply {
            inherited = true
            actual = game
            preference = game.modeId
        }
        val session = GameDisplaySession(host)
        assertEquals(desktop, session.baseline())
        assertTrue(session.apply(game, 0))
        session.reset()
        assertTrue(host.requests.isEmpty())
        assertEquals(listOf(desktop.modeId), host.restores)
        assertFalse(session.hasLease)
    }

    @Test fun videoHandoffNeverRestoresOverActiveVideo() = runBlocking {
        val host = FakeHost()
        val session = GameDisplaySession(host)
        session.baseline()
        assertTrue(session.apply(game, 0))
        host.video = true
        session.close()
        assertEquals(1, host.handoffs)
        assertTrue(host.restores.isEmpty())
        assertNull(session.baseline())
    }

    @Test fun unrelatedNewWindowPreferenceIsNotOverwritten() = runBlocking {
        val host = FakeHost()
        val session = GameDisplaySession(host)
        session.baseline()
        assertTrue(session.apply(game, 0))
        host.preference = next.modeId
        host.actual = next
        session.close()
        assertTrue(host.restores.isEmpty())
        assertEquals(next, host.actual)
    }
}
