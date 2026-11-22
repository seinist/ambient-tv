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

    @Test fun playerStatusUsesPinnedIdentityAndClearsOnReleaseAndGameEnd() {
        val input = pad()
        val settings = com.ambient.tv.ui.gamepad.retro.RetroInputSettings
        settings.attach(emptyMap()) {}
        try {
            assertTrue(CompanionGamepadSession.playerPorts.value.isEmpty())
            CompanionGamepadSession.attach(input, "snes", "snes9x", "Test", false) {}
            CompanionGamepadSession.setReady(input, true)
            assertEquals(0, CompanionGamepadSession.claim("tls-first", "pinned-first"))
            assertEquals(1, CompanionGamepadSession.claim("tls-second", "pinned-second"))
            assertEquals(mapOf("pinned-first" to 0, "pinned-second" to 1), CompanionGamepadSession.playerPorts.value)
            CompanionGamepadSession.release("tls-first")
            assertEquals(mapOf("pinned-second" to 1), CompanionGamepadSession.playerPorts.value)
            CompanionGamepadSession.detach(input)
            assertTrue(CompanionGamepadSession.playerPorts.value.isEmpty())
        } finally {
            CompanionGamepadSession.detach(input)
            settings.attach(emptyMap()) {}
        }
    }

    @Test fun pairedPhonesUsePersistentPlayerSettingsAndDoNotHijackEachOther() {
        val input = pad()
        val first = com.ambient.tv.ui.gamepad.retro.companionControllerDescriptor("fingerprint-a")
        val second = com.ambient.tv.ui.gamepad.retro.companionControllerDescriptor("fingerprint-b")
        val config = com.ambient.tv.ui.gamepad.retro.RetroInputSettings
        config.attach(emptyMap()) {}
        try {
            config.update { it.withPort(first, 2).withPort(second, 2) }
            CompanionGamepadSession.attach(input, "snes", "snes9x", "Test", false) {}
            CompanionGamepadSession.setReady(input, true)
            assertEquals(2, CompanionGamepadSession.claim("tls-a", "fingerprint-a"))
            assertEquals(-1, CompanionGamepadSession.claim("tls-b", "fingerprint-b"))
            // Reconfiguration is respected immediately when the handset reclaims its slot.
            config.update { it.withPort(second, 1) }
            assertEquals(1, CompanionGamepadSession.claim("tls-b", "fingerprint-b"))
            config.update { it.withPort(first, com.ambient.tv.ui.gamepad.retro.RetroPorts.OFF) }
            assertEquals(-1, CompanionGamepadSession.claim("tls-a", "fingerprint-a"))
            assertEquals(-1, CompanionGamepadSession.port("tls-a"))
            assertEquals(1, CompanionGamepadSession.port("tls-b"))
        } finally {
            CompanionGamepadSession.detach(input)
            config.attach(emptyMap()) {}
        }
    }

    @Test fun liveFixedToAutoPortTransitionAndReclaimClearAllHeldState() {
        val input = pad()
        val settings = com.ambient.tv.ui.gamepad.retro.RetroInputSettings
        val descriptor = com.ambient.tv.ui.gamepad.retro.companionControllerDescriptor("device-1")
        settings.attach(emptyMap()) {}
        try {
            settings.update { it.withPort(descriptor, 2) }
            CompanionGamepadSession.attach(input, "snes", "snes9x", "Game", false) {}
            CompanionGamepadSession.setReady(input, true)
            assertEquals(2, CompanionGamepadSession.claim("socket-a", "device-1"))
            assertTrue(CompanionGamepadSession.input("socket-a",
                CompanionGamepadState(buttons = RetroPadButton.A.mask)))
            assertEquals(RetroPadButton.A.mask, input.buttons(2))
            settings.update { it.withPort(descriptor, com.ambient.tv.ui.gamepad.retro.RetroPorts.AUTO) }
            assertTrue(CompanionGamepadSession.input("socket-a", CompanionGamepadState()))
            assertEquals(0, CompanionGamepadSession.port("socket-a"))
            assertEquals(0, input.buttons(2))
            CompanionGamepadSession.release("socket-a")
            assertEquals(-1, CompanionGamepadSession.port("socket-a"))
            assertEquals(0, CompanionGamepadSession.claim("socket-a", "device-1"))
            assertTrue(CompanionGamepadSession.input("socket-a", CompanionGamepadState()))
            assertEquals(0, input.buttons(0))
        } finally {
            CompanionGamepadSession.detach(input)
            settings.attach(emptyMap()) {}
        }
    }

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

    @Test fun partialPressureHasADeadmanAndCannotReplayAcrossPause() {
        val input = pad()
        try {
            CompanionGamepadSession.attach(input, "psx", "pcsx_rearmed", "Game", false) {}
            CompanionGamepadSession.setReady(input, true)
            CompanionGamepadSession.claim("phone")
            val partial = CompanionGamepadState(leftTrigger = .25f, rightTrigger = .4f)
            CompanionGamepadSession.input("phone", partial)
            assertEquals(8192, input.trigger(0, 0))
            CompanionGamepadSession.setReady(input, false)
            assertEquals(0, input.trigger(0, 0))
            CompanionGamepadSession.input("phone", partial)
            CompanionGamepadSession.setReady(input, true)
            CompanionGamepadSession.input("phone", partial)
            assertEquals(0, input.trigger(0, 0))
            CompanionGamepadSession.input("phone", CompanionGamepadState(leftTrigger = 0f, rightTrigger = 0f))
            CompanionGamepadSession.input("phone", partial)
            assertEquals(8192, input.trigger(0, 0))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(1_501L, TimeUnit.MILLISECONDS)
            assertEquals(0, input.trigger(0, 0))
            assertEquals(0, input.trigger(0, 1))
        } finally {
            CompanionGamepadSession.detach(input)
        }
    }

    @Test fun descriptorsAreOptionalInAcksAndDiscardedForANewGame() {
        val input = pad()
        val next = pad()
        try {
            CompanionGamepadSession.attach(input, "nes", "fceumm", "First", false) {}
            CompanionGamepadSession.claim("phone")
            CompanionGamepadSession.setInputDescriptions(input, mapOf("0:8" to "Jump", "1:8" to "Other player"))
            assertEquals(mapOf("0:8" to "Jump"), CompanionGamepadSession.info("phone")?.inputDescriptions)
            assertNull(CompanionGamepadSession.info("phone", includeDescriptions = false)?.inputDescriptions)
            val session = CompanionGamepadSession.info()?.sessionId
            CompanionGamepadSession.attach(next, "nes", "fceumm", "Second", false) {}
            assertNotEquals(session, CompanionGamepadSession.info()?.sessionId)
            assertTrue(CompanionGamepadSession.info()?.inputDescriptions.isNullOrEmpty())
        } finally {
            CompanionGamepadSession.detach(input)
            CompanionGamepadSession.detach(next)
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

    @Test fun specialPointerAndSensorStateStayIsolatedPerPlayerAndReleaseOnPause() {
        val input = pad()
        val pointers = mutableListOf<Pair<Int, CompanionPointerState?>>()
        val motions = mutableListOf<Pair<Int, CompanionMotionState?>>()
        try {
            CompanionGamepadSession.attach(input, "nds", "melonds", "Dual screen", true,
                onPointer = { port, pointer -> pointers += port to pointer },
                onMotion = { port, motion -> motions += port to motion },
                onMenu = {})
            CompanionGamepadSession.setReady(input, true)
            assertEquals(0, CompanionGamepadSession.claim("first"))
            assertEquals(1, CompanionGamepadSession.claim("second"))
            val stylus = CompanionPointerState(
                contacts = listOf(CompanionPointerContact(0, 0.4f, 0.9f),
                    CompanionPointerContact(1, 0.6f, 0.8f)))
            val gyro = CompanionMotionState(mask = 3, ax = 0.2f, az = 1f, gx = 0.6f)
            assertTrue(CompanionGamepadSession.input("first", CompanionGamepadState(
                pointer = stylus, motion = gyro)))
            assertTrue(CompanionGamepadSession.input("second", CompanionGamepadState(
                pointer = CompanionPointerState(kind = "gun",
                    contacts = listOf(CompanionPointerContact(0, 0.2f, 0.1f)), buttons = 1))))
            assertEquals(stylus, pointers.last { it.first == 0 }.second)
            assertEquals("gun", pointers.last { it.first == 1 }.second?.kind)
            assertEquals(gyro, motions.last { it.first == 0 }.second)
            CompanionGamepadSession.setReady(input, false)
            assertNull(pointers.last { it.first == 0 }.second)
            assertNull(pointers.last { it.first == 1 }.second)
            assertNull(motions.last { it.first == 0 }.second)
        } finally {
            CompanionGamepadSession.detach(input)
        }
    }

    @Test fun validateSpecialInputBoundsAndLegacyProtocolCompatibility() {
        val valid = CompanionGamepadState(
            pointer = CompanionPointerState(contacts = listOf(
                CompanionPointerContact(0, 0f, 1f), CompanionPointerContact(1, 1f, 0f))),
            motion = CompanionMotionState(mask = 3, az = 1f, gy = 0.3f))
        valid.validate()
        val frame = CompanionFrame(type = "gamepad_input", gamepadState = valid)
        assertEquals(frame, companionJson.decodeFromString<CompanionFrame>(
            companionJson.encodeToString(CompanionFrame.serializer(), frame)))
        assertTrue(runCatching { valid.copy(pointer = CompanionPointerState(kind = "invalid")).validate() }.isFailure)
        assertTrue(runCatching { valid.copy(pointer = CompanionPointerState(
            contacts = listOf(CompanionPointerContact(0, 2f, 0f)))).validate() }.isFailure)
        assertTrue(runCatching { valid.copy(pointer = CompanionPointerState(
            contacts = listOf(CompanionPointerContact(0, 0f, 0f),
                CompanionPointerContact(0, 1f, 1f)))).validate() }.isFailure)
        assertTrue(runCatching { valid.copy(motion = CompanionMotionState(mask = 4)).validate() }.isFailure)
        assertTrue(runCatching { valid.copy(motion = CompanionMotionState(gz = Float.NaN)).validate() }.isFailure)
    }

    @Test fun fingerSlotsAreStableAcrossReleaseAndReusedWithoutRenumbering() {
        val slots = CompanionTouchContactSlots<String>()
        assertEquals(0, slots.slot("first"))
        assertEquals(1, slots.slot("second"))
        assertEquals(1, slots.slot("second"))
        slots.release("first")
        assertEquals(1, slots.slot("second"))
        assertEquals(0, slots.slot("third"))
        assertEquals(2, slots.slot("fourth"))
        assertEquals(3, slots.slot("fifth"))
        assertNull(slots.slot("sixth"))
        slots.release("second")
        assertEquals(1, slots.slot("sixth"))
    }

    @Test fun pointerCalibrationHandlesDualScreensAndOutsideBounds() {
        assertEquals(0.5f to 0.75f, companionPointerCoordinates(0.5f, 0.5f, "lower"))
        assertEquals(0.5f to 0.25f, companionPointerCoordinates(0.5f, 0.5f, "upper"))
        assertEquals(0.75f to 0.5f, companionPointerCoordinates(0.5f, 0.5f, "right"))
        assertEquals(0f to 1f, companionPointerCoordinates(-4f, 8f, "full"))
        assertEquals("lower", companionDefaultPointerRegion("nds"))
        assertEquals("lower", companionDefaultPointerRegion("wiiu"))
        assertEquals("full", companionDefaultPointerRegion("wii"))
    }

    @Test fun gamepadIsAHandheldPageInsteadOfACompanionDialog() {
        assertTrue(CompanionState(gamepadPanel = true).handheldPageOpen())
        assertFalse(CompanionState().handheldPageOpen())
    }

    @Test fun advertisedCoreDeviceSelectionUsesLibretroBaseIds() {
        assertEquals(1, companionCoreDeviceType(null))
        assertEquals(1, companionCoreDeviceType("pad"))
        assertEquals(2, companionCoreDeviceType("mouse"))
        assertEquals(4, companionCoreDeviceType("gun"))
        assertEquals(6, companionCoreDeviceType("touch"))
    }

    @Test fun layoutDefaultsRespectConsoleFamilies() {
        assertEquals(GameTouchMode.DPAD, companionDefaultTouchMode("snes"))
        assertEquals(GameTouchMode.HYBRID, companionDefaultTouchMode("n64"))
        assertEquals(GameTouchMode.DUAL, companionDefaultTouchMode("psx"))
    }
}
