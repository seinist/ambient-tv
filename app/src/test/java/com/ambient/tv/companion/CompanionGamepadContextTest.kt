package com.ambient.tv.companion

import android.app.Application
import android.os.Looper
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroPadInput
import com.ambient.tv.ui.gamepad.retro.RetroPorts
import com.ambient.tv.ui.gamepad.retro.companionControllerDescriptor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [35], application = Application::class)
class CompanionGamepadContextTest {
    private val pad = RetroPadInput(schedule = { _, _ -> {} }, onMenu = {})
    private val rewinds = mutableListOf<Boolean>()
    private var menuCalls = 0

    @Before fun attach() {
        RetroInputSettings.attach(emptyMap()) {}
        CompanionGamepadSession.attach(pad, "snes", "snes9x", "Game", false,
            onRewind = { rewinds += it }, onMenu = { menuCalls++ })
        assertEquals(0, CompanionGamepadSession.claim("one", "primary"))
        assertEquals(1, CompanionGamepadSession.claim("two", "secondary"))
    }

    @After fun detach() {
        CompanionGamepadSession.detach(pad)
        RetroInputSettings.attach(emptyMap()) {}
    }

    private fun mode(value: CompanionGamepadInteraction) = CompanionGamepadSession.setInteraction(pad, value)
    private fun info() = requireNotNull(CompanionGamepadSession.info("one"))
    private fun command(action: String, pressed: Boolean = false) = info().let {
        CompanionGamepadCommand(it.sessionId, it.contextRevision, action, pressed)
    }
    private fun advance(ms: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(ms, TimeUnit.MILLISECONDS)

    @Test fun legacyPauseAndUnknownModesNeverInventMenuControls() {
        val legacy = CompanionGamepadInfo(ready = false)
        assertFalse(legacy.acceptsNavigation)
        assertFalse(legacy.acceptsGameplay)
        assertTrue(legacy.copy(ready = true).acceptsGameplay)
        assertFalse(legacy.copy(ready = true, interaction = "future-mode").acceptsGameplay)
        for (mode in CompanionGamepadInteraction.entries) {
            val state = legacy.copy(interaction = mode.key, ready = mode == CompanionGamepadInteraction.PLAYING)
            assertEquals(mode.navigation, state.acceptsNavigation)
        }
    }

    @Test fun directionsRequireAClaimAndTheCurrentMenuTicket() {
        val received = mutableListOf<String>()
        val nav: (CompanionInput) -> Boolean = { received += it.key; true }
        assertFalse(CompanionGamepadSession.command("one", command("down"), nav))
        mode(CompanionGamepadInteraction.MENU)
        val menuTicket = command("down")
        assertFalse(CompanionGamepadSession.command("unclaimed", menuTicket, nav))
        assertFalse(CompanionGamepadSession.command("one", menuTicket.copy(sessionId = "old-game"), nav))
        assertTrue(CompanionGamepadSession.command("one", menuTicket, nav))
        mode(CompanionGamepadInteraction.DIALOG)
        assertFalse(CompanionGamepadSession.command("one", menuTicket, nav))
        assertTrue(CompanionGamepadSession.command("one", command("back"), nav))
        mode(CompanionGamepadInteraction.BUSY)
        assertFalse(CompanionGamepadSession.command("one", command("ok"), nav))
        assertEquals(listOf("down", "back"), received)
    }

    @Test fun menuAndResumeInvalidateQueuedActionsBeforeTheUiCallback() {
        mode(CompanionGamepadInteraction.PLAYING)
        val opening = command("menu")
        assertTrue(CompanionGamepadSession.command("one", opening) { false })
        assertFalse(info().ready)
        assertTrue(info().acceptsNavigation)
        assertFalse(CompanionGamepadSession.command("one", opening) { false })
        assertTrue(CompanionGamepadSession.command("one", command("resume")) { false })
        assertEquals(CompanionGamepadInteraction.BUSY, info().interactionMode)
        assertEquals(2, menuCalls)
    }

    @Test fun gameFramesCannotReplayAfterMenuResumeOrIntoAnotherGame() {
        mode(CompanionGamepadInteraction.PLAYING)
        val ticket = info()
        val press = CompanionGamepadState(buttons = RetroPadButton.A.mask,
            sessionId = ticket.sessionId, contextRevision = ticket.contextRevision)
        assertTrue(CompanionGamepadSession.input("one", press))
        mode(CompanionGamepadInteraction.MENU)
        assertEquals(0, pad.buttons(0))
        mode(CompanionGamepadInteraction.PLAYING)
        assertFalse(CompanionGamepadSession.input("one", press))
        assertFalse(CompanionGamepadSession.input("one", press.copy(sessionId = "other-game",
            contextRevision = info().contextRevision)))
        assertEquals(0, pad.buttons(0))
        assertTrue(CompanionGamepadSession.input("one", CompanionGamepadState(
            sessionId = info().sessionId, contextRevision = info().contextRevision)))
        assertTrue(CompanionGamepadSession.input("one", press.copy(contextRevision = info().contextRevision)))
        assertEquals(RetroPadButton.A.mask, pad.buttons(0))
    }

    @Test fun turningAClaimOffRevokesNavigationWithoutWaitingForAGameFrame() {
        mode(CompanionGamepadInteraction.MENU)
        RetroInputSettings.update { it.withPort(companionControllerDescriptor("primary"), RetroPorts.OFF) }
        assertFalse(CompanionGamepadSession.command("one", command("ok")) { true })
        assertEquals(-1, CompanionGamepadSession.port("one"))
    }

    @Test fun rewindBelongsOnlyToPrimaryPlayerAndExpiresWithoutRenewal() {
        mode(CompanionGamepadInteraction.PLAYING)
        CompanionGamepadSession.setRewind(pad, true, 12.5f)
        assertTrue(info().rewindAvailable)
        assertFalse(requireNotNull(CompanionGamepadSession.info("two")).rewindAvailable)
        val hold = command("rewind", true)
        assertFalse(CompanionGamepadSession.command("two", hold) { false })
        assertTrue(CompanionGamepadSession.command("one", hold) { false })
        assertEquals(listOf(true), rewinds)
        advance(1_000)
        assertTrue(CompanionGamepadSession.command("one", hold) { false })
        advance(1_000)
        assertEquals(listOf(true), rewinds)
        advance(501)
        assertEquals(listOf(true, false), rewinds)
    }

    @Test fun rewindReleaseIsSafeAcrossContextChangesAndInvalidDurationHidesIt() {
        mode(CompanionGamepadInteraction.PLAYING)
        CompanionGamepadSession.setRewind(pad, true, 3f)
        val hold = command("rewind", true)
        assertTrue(CompanionGamepadSession.command("one", hold) { false })
        mode(CompanionGamepadInteraction.BACKGROUND)
        assertEquals(listOf(true, false), rewinds)
        assertFalse(info().rewindAvailable)
        assertTrue(CompanionGamepadSession.command("one", hold.copy(pressed = false)) { false })
        mode(CompanionGamepadInteraction.PLAYING)
        assertFalse(CompanionGamepadSession.command("one", hold) { false })
        CompanionGamepadSession.setRewind(pad, true, Float.NaN)
        assertFalse(info().rewindAvailable)
        assertEquals(0f, info().rewindSeconds, 0f)
    }

    @Test fun releasingOrReassigningPrimaryClearsRewindAndNoTimerSurvives() {
        mode(CompanionGamepadInteraction.PLAYING)
        CompanionGamepadSession.setRewind(pad, true, 2f)
        assertTrue(CompanionGamepadSession.command("one", command("rewind", true)) { false })
        CompanionGamepadSession.release("one")
        assertEquals(listOf(true, false), rewinds)
        advance(2_000)
        assertEquals(listOf(true, false), rewinds)
    }

    @Test fun navigationProtocolRoundTripsAndRejectsArbitraryRemoteCommands() {
        mode(CompanionGamepadInteraction.MENU)
        val frame = CompanionFrame(type = "gamepad_command", gamepadCommand = command("ok"), gamepadInfo = info())
        assertEquals(frame, companionJson.decodeFromString<CompanionFrame>(
            companionJson.encodeToString(CompanionFrame.serializer(), frame)))
        assertThrows(IllegalArgumentException::class.java) { command("home").validate() }
        assertThrows(IllegalArgumentException::class.java) { command("ok", true).validate() }
        assertThrows(IllegalArgumentException::class.java) { command("ok").copy(contextRevision = -1).validate() }
        assertThrows(IllegalArgumentException::class.java) { CompanionGamepadState(sessionId = "incomplete").validate() }
    }

    @Test fun newContextNeutralFrameIsAnOrderedEdgeAndCannotBeCoalescedAway() {
        val before = CompanionGamepadState(sessionId = "game", contextRevision = 1)
        val after = before.copy(contextRevision = 3)
        assertTrue(companionGamepadCriticalEdge(before, after))
        assertNull(coalesceCompanionInput(CompanionFrame(type = "gamepad_input", gamepadState = before),
            CompanionFrame(type = "gamepad_input", gamepadState = after)))
    }

    @Test fun menuConfirmIsHeldOnceRenewedAndReleasedToItsOriginalLease() {
        mode(CompanionGamepadInteraction.MENU)
        val releases = mutableListOf<Boolean>()
        var presses = 0
        val begin: (Int) -> ((Boolean) -> Unit)? = { presses++; { cancelled -> releases += cancelled } }
        assertTrue(CompanionGamepadSession.command("one", command("ok_down"), begin) { true })
        assertFalse(CompanionGamepadSession.input("one", CompanionGamepadState(
            sessionId = info().sessionId, contextRevision = info().contextRevision)))
        assertTrue(releases.isEmpty()) // Late neutral input must not release the menu hold.
        assertFalse(CompanionGamepadSession.command("two", command("ok_down"), begin) { true })
        advance(1_000)
        assertTrue(CompanionGamepadSession.command("one", command("ok_renew"), begin) { true })
        advance(1_000)
        assertTrue(releases.isEmpty())
        assertTrue(CompanionGamepadSession.command("one", command("ok_up"), begin) { true })
        assertEquals(1, presses)
        assertEquals(listOf(false), releases)
    }

    @Test fun heldConfirmCancelsOnPageChangeOrLostHeartbeatWithoutActivatingNewFocus() {
        CompanionGamepadSession.setInteraction(pad, CompanionGamepadInteraction.MENU, "root")
        val releases = mutableListOf<Boolean>()
        val begin: (Int) -> ((Boolean) -> Unit)? = { { cancelled -> releases += cancelled } }
        val first = command("ok_down")
        assertTrue(CompanionGamepadSession.command("one", first, begin) { true })
        CompanionGamepadSession.setInteraction(pad, CompanionGamepadInteraction.MENU, "core-options")
        assertEquals(listOf(true), releases)
        assertTrue(CompanionGamepadSession.command("one", command("ok_down"), begin) { true })
        // The previous page's late UP must not release the newly pressed tool.
        assertTrue(CompanionGamepadSession.command("one", first.copy(action = "ok_up"), begin) { true })
        assertEquals(listOf(true), releases)
        advance(1_501)
        assertEquals(listOf(true, true), releases)
    }

    @Test fun netplaySynchronizationHidesAndRejectsResumeWhileMenuNavigationStillWorks() {
        CompanionGamepadSession.setInteraction(pad, CompanionGamepadInteraction.DIALOG, "netplay", canResume = false)
        assertTrue(info().acceptsNavigation)
        assertFalse(info().acceptsResume)
        assertFalse(CompanionGamepadSession.command("one", command("resume")) { true })
        assertFalse(CompanionGamepadSession.menu("one")) // Legacy phone toggle is guarded too.
        val navigation = mutableListOf<String>()
        assertTrue(CompanionGamepadSession.command("one", command("back")) { navigation += it.key; true })
        assertTrue(CompanionGamepadSession.command("one", command("ok")) { navigation += it.key; true })
        assertEquals(listOf("back", "ok"), navigation)
        assertEquals(0, menuCalls)
    }

    @Test fun resumeCapabilityChangesInvalidateOldCommandsWithoutAViewChange() {
        CompanionGamepadSession.setInteraction(pad, CompanionGamepadInteraction.MENU, "root")
        val oldResume = command("resume")
        CompanionGamepadSession.setInteraction(pad, CompanionGamepadInteraction.MENU, "root", canResume = false)
        assertTrue(info().contextRevision > oldResume.contextRevision)
        assertFalse(info().acceptsResume)
        CompanionGamepadSession.setInteraction(pad, CompanionGamepadInteraction.MENU, "root", canResume = true)
        assertTrue(info().acceptsResume)
        assertFalse(CompanionGamepadSession.command("one", oldResume) { true })
        assertTrue(CompanionGamepadSession.command("one", command("resume")) { true })
        assertEquals(1, menuCalls)
    }
}
