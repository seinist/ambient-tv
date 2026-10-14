package com.ambient.tv.ui.sound

import org.junit.Assert.*
import org.junit.Test

class UiSoundActionsTest {
    @Test fun nestedBackNavigationProducesOnlyOneBackSoundAndExecutesEveryCallback() {
        val heard = mutableListOf<UiSound>()
        val sounds = UiSoundActions { heard += it }
        var calls = 0
        sounds.perform(UiSound.SELECT) {
            calls++
            sounds.perform(UiSound.SELECT) { calls++; sounds.request(UiSound.BACK) }
        }
        assertEquals(2, calls)
        assertEquals(listOf(UiSound.BACK), heard)
        sounds.perform(UiSound.SELECT) { calls++ }
        assertEquals(listOf(UiSound.BACK, UiSound.SELECT), heard)
    }

    @Test fun directHardwareBackAndIndependentFastClicksAreNotSuppressed() {
        val heard = mutableListOf<UiSound>()
        val sounds = UiSoundActions { heard += it }
        sounds.request(UiSound.BACK)
        repeat(2) { sounds.perform(UiSound.SELECT) { sounds.request(UiSound.SELECT) } }
        assertEquals(listOf(UiSound.BACK, UiSound.SELECT, UiSound.SELECT), heard)
    }

    @Test fun exceptionsDoNotLeaveTheNextActionInsideAnOldSoundTransaction() {
        val heard = mutableListOf<UiSound>()
        val sounds = UiSoundActions { heard += it }
        runCatching { sounds.perform(UiSound.BACK) { error("failed navigation") } }
        sounds.perform(UiSound.SELECT) {}
        assertEquals(listOf(UiSound.BACK, UiSound.SELECT), heard)
    }

    @Test fun firstClickWaitsForItsSampleAndIsNotLostToSubsequentFocus() {
        val gate = UiSoundLoadGate()
        assertNull(gate.request(UiSound.SELECT, 100))
        assertNull(gate.request(UiSound.FOCUS, 110))
        assertNull(gate.loaded(UiSound.FOCUS, true, 120))
        assertEquals(UiSound.SELECT, gate.loaded(UiSound.SELECT, true, 130))
        assertNull(gate.loaded(UiSound.SELECT, true, 140))
        assertEquals(UiSound.SELECT, gate.request(UiSound.SELECT, 150))
    }

    @Test fun newestClickWinsAndStaleOrFailedSamplesNeverReplay() {
        val gate = UiSoundLoadGate()
        assertNull(gate.request(UiSound.SELECT, 100))
        assertNull(gate.request(UiSound.BACK, 150))
        assertNull(gate.loaded(UiSound.SELECT, true, 160))
        assertEquals(UiSound.BACK, gate.loaded(UiSound.BACK, true, 170))
        val stale = UiSoundLoadGate()
        stale.request(UiSound.SELECT, 100)
        assertNull(stale.loaded(UiSound.SELECT, true, 301))
        val failed = UiSoundLoadGate()
        failed.request(UiSound.BACK, 100)
        assertNull(failed.loaded(UiSound.BACK, false, 120))
        assertNull(failed.loaded(UiSound.BACK, true, 130))
    }
}
