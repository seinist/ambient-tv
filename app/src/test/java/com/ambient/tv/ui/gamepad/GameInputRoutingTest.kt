package com.ambient.tv.ui.gamepad

import android.view.KeyEvent
import com.ambient.tv.data.browse.BrowseWorkBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.ambient.tv.ui.keyboard.KeyAction
import com.ambient.tv.tvheadend.data.TvheadendHtspHbbtv
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class GameInputRoutingTest {
    private val first = Any()
    private val second = Any()

    @After fun reset() {
        GameInputRouting.detach(first)
        GameInputRouting.detach(second)
    }

    @Test fun gameActionsStayInsideTheirSessionAndGlobalNavigationNeverFallsThrough() {
        var menus = 0
        var settings = 0
        var info = 0
        GameInputRouting.attach(first, GameInputMode.MENU, { menus++ }, { settings++ }, { info++ })
        assertTrue(GameInputRouting.perform(KeyAction.SHOW_OSD))
        assertTrue(GameInputRouting.perform(KeyAction.PLAY_PAUSE))
        assertTrue(GameInputRouting.perform(KeyAction.SETTINGS))
        assertTrue(GameInputRouting.perform(KeyAction.DETAILS))
        assertEquals(2, menus)
        assertEquals(1, settings)
        assertEquals(1, info)
        listOf(KeyAction.START_PAGE, KeyAction.SEARCH, KeyAction.LIVE_TV,
            KeyAction.WEATHER, KeyAction.SCREENSAVER).forEach { assertTrue(GameInputRouting.perform(it)) }
        assertEquals(2, menus)
        assertEquals(1, settings)
        assertEquals(1, info)
    }

    @Test fun busyAndBackgroundSessionsConsumeActionsWithoutInvokingCallbacks() {
        GameInputRouting.attach(first, GameInputMode.BLOCKED, { fail("Menu while blocked") },
            { fail("Settings while blocked") }, { fail("Info while blocked") })
        KeyAction.entries.forEach { assertTrue(GameInputRouting.perform(it)) }
        GameInputRouting.detach(first)
        assertFalse(GameInputRouting.perform(KeyAction.START_PAGE))
    }

    @Test fun anOldOwnerCannotClearOrChangeAReplacementSession() {
        GameInputRouting.attach(first, GameInputMode.RUNNING, {})
        GameInputRouting.attach(second, GameInputMode.MENU, {})
        val revision = GameInputRouting.generation
        GameInputRouting.detach(first)
        GameInputRouting.update(first, GameInputMode.BLOCKED)
        assertTrue(GameInputRouting.active)
        assertEquals(GameInputMode.MENU, GameInputRouting.mode)
        assertEquals(revision, GameInputRouting.generation)
    }

    @Test fun onlyRealSessionOrModeTransitionsInvalidatePendingInput() {
        val modes = mutableListOf<GameInputMode?>()
        val token = GameInputRouting.observeChanges { modes += GameInputRouting.mode }
        try {
            GameInputRouting.attach(first, GameInputMode.RUNNING, {})
            GameInputRouting.update(first, GameInputMode.RUNNING)
            GameInputRouting.attach(first, GameInputMode.RUNNING, {})
            GameInputRouting.update(first, GameInputMode.MENU)
            GameInputRouting.update(first, GameInputMode.DIALOG)
            GameInputRouting.detach(first)
            assertEquals(listOf(GameInputMode.RUNNING, GameInputMode.MENU, GameInputMode.DIALOG, null), modes)
        } finally { GameInputRouting.removeObserver(token) }
    }

    @Test fun gameEpochInvalidatesOldWorkEvenAfterAnEntireShortSession() {
        val previous = BrowseWorkBudget.gameEpoch()
        assertTrue(BrowseWorkBudget.gameFreeSince(previous))
        val oldJob = BrowseWorkBudget.speculativeJob
        val job = Job()
        BrowseWorkBudget.speculativeJob = job
        try {
            GameInputRouting.attach(first, GameInputMode.MENU, {})
            val started = BrowseWorkBudget.gameEpoch()
            assertEquals(previous + 1, started)
            assertTrue(job.isCancelled)
            assertFalse(BrowseWorkBudget.gameFreeSince(started))
            GameInputRouting.update(first, GameInputMode.RUNNING)
            GameInputRouting.attach(second, GameInputMode.RUNNING, {})
            GameInputRouting.detach(first)
            assertEquals(started, BrowseWorkBudget.gameEpoch())
            assertFalse(BrowseWorkBudget.gameFreeSince(previous))
            GameInputRouting.detach(second)
            assertFalse(BrowseWorkBudget.gameFreeSince(previous))
            assertTrue(BrowseWorkBudget.gameFreeSince(started))
        } finally {
            BrowseWorkBudget.speculativeJob = oldJob
            job.cancel()
        }
    }

    @Test fun shortGameStartCancelsInFlightOptionalWorkAndDiscardsItsResult() = runBlocking {
        withTimeout(5_000L) {
            val cancelled = CompletableDeferred<Unit>()
            val work = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    BrowseWorkBudget.interruptOnGameStart({ cancelled.complete(Unit) }) {
                        cancelled.await()
                        "obsolete result"
                    }
                }
            }
            GameInputRouting.attach(first, GameInputMode.RUNNING, {})
            GameInputRouting.detach(first)
            cancelled.await()
            assertTrue(work.await().isFailure)
        }
    }

    @Test fun optionalWorkCompletesNormallyWithoutGameEntry() = runBlocking {
        assertEquals(42, BrowseWorkBudget.interruptOnGameStart({ fail("Unexpected cancellation") }) { 42 })
    }

    @Test fun menuMappingKeepsConfirmSideButCannotNavigateToAppPages() {
        assertEquals(GamepadCommand.Key(GamepadKeyCommand.CONFIRM), gameMenuCommand(GamepadButton.A, false, false))
        assertEquals(GamepadCommand.Key(GamepadKeyCommand.CONFIRM), gameMenuCommand(GamepadButton.B, false, true))
        assertEquals(GamepadCommand.Key(GamepadKeyCommand.CONTEXT_MENU), gameMenuCommand(GamepadButton.MODE, false, false))
        assertEquals(GamepadCommand.None, gameMenuCommand(GamepadButton.Y, false, false))
        GamepadButton.entries.forEach { button ->
            assertEquals(GamepadCommand.None, gameMenuCommand(button, true, false))
            assertFalse(gameMenuCommand(button, false, false) is GamepadCommand.Action)
        }
    }

    @Test fun changingMenuPagesInvalidatesHeldNavigationWithoutChangingPlaybackMode() {
        GameInputRouting.attach(first, GameInputMode.MENU, {}, context = "core")
        val revision = GameInputRouting.generation
        GameInputRouting.update(first, GameInputMode.MENU, context = "core")
        assertEquals(revision, GameInputRouting.generation)
        GameInputRouting.update(first, GameInputMode.MENU, context = "core-option")
        assertEquals(revision + 1, GameInputRouting.generation)
    }

    @Test fun aSharedHeldStickMustReturnToNeutralBeforeItCanOperateTheNextContext() {
        val transitions = GameInputTransitions()
        transitions.advance(1, true)
        assertFalse(transitions.motion(7, true))
        transitions.advance(2, true)
        assertTrue(transitions.motion(7, true))
        assertTrue(transitions.motion(7, false))
        assertFalse(transitions.motion(7, true))
    }

    @Test fun systemHomeVolumeAndPowerAreNeverQuarantinedAsGameKeys() {
        listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH).forEach { assertFalse(gameTracksKey(it)) }
        assertTrue(gameTracksKey(KeyEvent.KEYCODE_BUTTON_MODE))
        assertTrue(gameTracksKey(KeyEvent.KEYCODE_DPAD_CENTER))
    }

    @Test fun oldEpochCannotStartNewNetworkWorkAfterShortGame() = runBlocking {
        val epoch = BrowseWorkBudget.gameEpoch()
        GameInputRouting.attach(first, GameInputMode.RUNNING, {})
        GameInputRouting.detach(first)
        var started = false
        val result = runCatching {
            BrowseWorkBudget.interruptOnGameStart({}, epoch) { started = true }
        }
        assertTrue(result.isFailure)
        assertFalse(started)
    }

    @Test fun startingGameActivelyClosesBlockedHtspProbe() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            Socket().use { socket ->
                val epoch = BrowseWorkBudget.gameEpoch()
                val work = async(Dispatchers.IO) {
                    runCatching {
                        BrowseWorkBudget.interruptOnGameStart({ socket.close() }, epoch) {
                            TvheadendHtspHbbtv.discover(socket, "127.0.0.1", server.localPort, "", "")
                        }
                    }
                }
                server.accept().use { peer ->
                    peer.soTimeout = 5_000
                    // Receive the hello frame header but deliberately never reply:
                    // the probe is waiting for input until its exact socket is closed.
                    assertTrue(DataInputStream(peer.getInputStream()).readInt() > 0)
                    GameInputRouting.attach(first, GameInputMode.RUNNING, {})
                    GameInputRouting.detach(first)
                    assertTrue(withTimeout(2_000) { work.await() }.isFailure)
                    assertTrue(socket.isClosed)
                }
            }
        }
    }

}
