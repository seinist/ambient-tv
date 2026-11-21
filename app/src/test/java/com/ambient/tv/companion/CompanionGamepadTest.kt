package com.ambient.tv.companion

import android.app.Application
import android.os.Looper
import com.ambient.tv.games.GameTouchMode
import com.ambient.tv.ui.gamepad.retro.RetroPadButton
import com.ambient.tv.ui.gamepad.retro.RetroPadInput
import org.junit.Assert.*
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
class CompanionGamepadTest {
    private fun pad(): RetroPadInput = RetroPadInput(
        schedule = { _, _ -> {} },
        onMenu = {},
    )

    @Test fun separateAuthenticatedSessionsOwnDifferentPorts() {
        val input = pad()
        try {
            CompanionGamepadSession.attach(input, "snes", "snes9x", "Test game", false) {}
            CompanionGamepadSession.setReady(input, true)
            assertEquals(0, CompanionGamepadSession.claim("phone-1"))
            assertEquals(1, CompanionGamepadSession.claim("phone-2"))
            assertEquals(0, CompanionGamepadSession.claim("phone-1"))
            assertTrue(CompanionGamepadSession.input("phone-1",
                CompanionGamepadState(buttons = RetroPadButton.A.mask, leftX = 1f)))
            assertTrue(CompanionGamepadSession.input("phone-2",
                CompanionGamepadState(buttons = RetroPadButton.B.mask)))
            assertEquals(RetroPadButton.A.mask, input.buttons(0))
            assertEquals(RetroPadButton.B.mask, input.buttons(1))
            assertFalse(CompanionGamepadSession.input("unpaired", CompanionGamepadState(buttons = 1)))
            CompanionGamepadSession.release("phone-1")
            assertEquals(0, input.buttons(0))
            assertEquals(RetroPadButton.B.mask, input.buttons(1))
        } finally {
            CompanionGamepadSession.detach(input)
        }
    }

    @Test fun pauseAndGameSwitchDropHeldButtonsAndClaims() {
        val first = pad()
        val second = pad()
        try {
            CompanionGamepadSession.attach(first, "psx", "pcsx_rearmed", "One", false) {}
            CompanionGamepadSession.setReady(first, true)
            assertEquals(0, CompanionGamepadSession.claim("phone"))
            CompanionGamepadSession.input("phone", CompanionGamepadState(buttons = RetroPadButton.START.mask))
            CompanionGamepadSession.setReady(first, false)
            assertEquals(0, first.buttons(0))
            assertFalse(CompanionGamepadSession.input("phone", CompanionGamepadState(buttons = 1)))
            CompanionGamepadSession.attach(second, "snes", "snes9x", "Two", false) {}
            assertEquals(-1, CompanionGamepadSession.port("phone"))
            assertEquals(0, first.buttons(0))
            assertEquals("snes", CompanionGamepadSession.info()?.systemId)
        } finally {
            CompanionGamepadSession.detach(second)
            CompanionGamepadSession.detach(first)
        }
    }

    @Test fun deadmanClearsHeldInputWhenPacketsStopArriving() {
        val input = pad()
        try {
            CompanionGamepadSession.attach(input, "snes", "snes9x", "Game", false) {}
            CompanionGamepadSession.setReady(input, true)
            CompanionGamepadSession.claim("phone")
            CompanionGamepadSession.input("phone", CompanionGamepadState(buttons = RetroPadButton.A.mask))
            assertEquals(RetroPadButton.A.mask, input.buttons(0))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1_501L, TimeUnit.MILLISECONDS)
            assertEquals(0, input.buttons(0))
        } finally {
            CompanionGamepadSession.detach(input)
        }
    }

    @Test fun snapshotsValidateAndRetainCompatibilityWithLegacyFrames() {
        val pad = CompanionGamepadState(buttons = 0xffff, leftX = -1f, rightY = 1f)
        pad.validate()
        assertTrue(runCatching { pad.copy(buttons = -1).validate() }.isFailure)
        assertTrue(runCatching { pad.copy(leftX = Float.NaN).validate() }.isFailure)
        assertTrue(runCatching { pad.copy(rightY = 1.01f).validate() }.isFailure)
        val legacy = companionJson.decodeFromString<CompanionFrame>("""{"type":"state"}""")
        assertNull(legacy.gamepadInfo)
        assertEquals(-1, legacy.gamepadPort)
        val frame = CompanionFrame(type = "gamepad_input", gamepadState = pad)
        val restored = companionJson.decodeFromString<CompanionFrame>(
            companionJson.encodeToString(CompanionFrame.serializer(), frame))
        assertEquals(frame, restored)
    }

    @Test fun gamepadIsAHandheldPageInsteadOfACompanionDialog() {
        assertTrue(CompanionState(gamepadPanel = true).handheldPageOpen())
        assertFalse(CompanionState().handheldPageOpen())
    }

    @Test fun layoutDefaultsRespectConsoleFamilies() {
        assertEquals(GameTouchMode.DPAD, companionDefaultTouchMode("snes"))
        assertEquals(GameTouchMode.HYBRID, companionDefaultTouchMode("n64"))
        assertEquals(GameTouchMode.DUAL, companionDefaultTouchMode("psx"))
    }
}
