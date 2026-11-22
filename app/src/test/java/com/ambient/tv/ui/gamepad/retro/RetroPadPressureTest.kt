package com.ambient.tv.ui.gamepad.retro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure virtual-input checks; no Android event/device behavior is simulated. */
class RetroPadPressureTest {
    private fun pad(config: () -> RetroInputConfig = { RetroInputConfig() }) = RetroPadInput(
        config = config, schedule = { _, _ -> {} }, onMenu = {},
    )

    @Test fun legacyPhonesAndDigitalTouchKeepFullPressure() {
        val input = pad()
        input.reserveRemotePort(1, true)
        input.remoteState(1, RetroPadButton.L2.mask, 0f, 0f, 0f, 0f)
        assertEquals(32767, input.trigger(1, 0))
        assertEquals(0, input.trigger(1, 1))
        input.touchButton(RetroPadButton.R2, true)
        assertEquals(32767, input.trigger(0, 1))
        input.touchButton(RetroPadButton.R2, false)
        assertEquals(0, input.trigger(0, 1))
        input.clearRemotePort(1)
        assertEquals(0, input.trigger(1, 0))
    }

    @Test fun partialPressureSurvivesWithoutABinaryPressAndUnionsWithTouch() {
        val input = pad()
        input.reserveRemotePort(0, true)
        input.remoteState(0, 0, 0f, 0f, 0f, 0f, leftTrigger = .25f, rightTrigger = .75f)
        assertEquals(0, input.buttons(0))
        assertEquals(8192, input.trigger(0, 0))
        assertEquals(24575, input.trigger(0, 1))
        input.touchButton(RetroPadButton.L2, true)
        assertEquals(32767, input.trigger(0, 0))
        input.touchButton(RetroPadButton.L2, false)
        assertEquals(8192, input.trigger(0, 0))
        input.remoteState(0, 0, 0f, 0f, 0f, 0f, leftTrigger = Float.NaN, rightTrigger = 5f)
        assertEquals(0, input.trigger(0, 0))
        assertEquals(32767, input.trigger(0, 1))
    }

    @Test fun triggerRemapsAndDescriptionsFollowTheSameHandsetProfile() {
        val descriptor = companionControllerDescriptor("phone")
        val config = RetroInputConfig().assignDevice(RetroInputKind.COMPANION, descriptor,
            RetroInputTarget.Button(RetroPadButton.R2), companionControllerKey(RetroPadButton.L2))
        val input = pad { config }
        input.reserveRemotePort(2, true, descriptor)
        input.remoteState(2, 0, 0f, 0f, 0f, 0f, leftTrigger = .25f, rightTrigger = 0f)
        assertEquals(0, input.trigger(2, 0))
        assertEquals(8192, input.trigger(2, 1))
        val labels = input.remoteInputDescriptions(2, mapOf("2:12" to "Brake", "2:13" to "Accelerate"))
        assertEquals("Accelerate", labels["2:12"])
        assertTrue("2:13" !in labels)
    }

    @Test fun pauseSuppressesEvenPartialPressureUntilPhysicalRelease() {
        val input = pad()
        input.reserveRemotePort(0, true)
        input.remoteState(0, 0, 0f, 0f, 0f, 0f, .2f, .4f)
        input.releaseAll()
        assertEquals(0, input.trigger(0, 0))
        assertEquals(0, input.trigger(0, 1))
        input.remoteState(0, 0, 0f, 0f, 0f, 0f, .2f, .4f)
        assertEquals(0, input.trigger(0, 0))
        input.remoteState(0, 0, 0f, 0f, 0f, 0f, 0f, 0f)
        input.remoteState(0, 0, 0f, 0f, 0f, 0f, .2f, .4f)
        assertTrue(input.trigger(0, 0) > 0 && input.trigger(0, 1) > 0)
    }

    @Test fun identicalHeartbeatsDoNotRecomputeOrDispatchNativeInput() {
        val stable = RetroInputConfig()
        val input = pad { stable }
        input.reserveRemotePort(0, true)
        var changes = 0
        input.onChanged = { changes++ }
        repeat(100) { input.remoteState(0, 0, .2f, -.4f, .1f, 0f, .3f, .6f) }
        assertEquals(1, changes)
        input.releaseAll()
        assertEquals(2, changes)
        input.releaseAll()
        assertEquals(2, changes)
    }

    @Test fun companionShortHoldPulseBeginsAndEndsWithoutWaitingForAHeartbeat() {
        val source = companionControllerKey(RetroPadButton.L2)
        val default = defaultRetroProfile(RetroInputKind.COMPANION)
        val config = RetroInputConfig().withProfile(RetroInputKind.COMPANION,
            default.copy(menu = default.menu + (RetroMenuTrigger.HOLD to listOf(source))))
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val input = RetroPadInput(config = { config }, onMenu = {}, schedule = { delay, task ->
            val scheduled = delay to task
            tasks += scheduled
            ({ tasks.remove(scheduled) })
        })
        input.reserveRemotePort(0, true, companionControllerDescriptor("hold"))
        input.remoteState(0, RetroPadButton.L2.mask, 0f, 0f, 0f, 0f)
        assertEquals(0, input.buttons(0))
        input.remoteState(0, 0, 0f, 0f, 0f, 0f)
        assertEquals(RetroPadButton.L2.mask, input.buttons(0))
        assertEquals(32767, input.trigger(0, 0))
        tasks.single { it.first == RetroPadInput.PULSE_MS }.second()
        assertEquals(0, input.buttons(0))
        assertEquals(0, input.trigger(0, 0))
    }
}
