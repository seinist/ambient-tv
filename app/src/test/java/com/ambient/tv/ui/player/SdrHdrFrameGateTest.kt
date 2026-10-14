package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class SdrHdrFrameGateTest {
    @Test fun callbackBurstSubmitsOnlyTheLatestAvailableImage() {
        val gate = SdrHdrFrameGate()
        assertTrue(gate.available())
        repeat(30) { assertFalse(gate.available()) }
        assertTrue(gate.consume(connected = true))
        // Replaying the queued callback cannot submit thirty copies of that image.
        repeat(30) { assertFalse(gate.consume(connected = true)) }
        assertTrue(gate.available())
        assertTrue(gate.consume(connected = true))
    }

    @Test fun earlyCallbacksKeepOneImageUntilTheWindowConnects() {
        val gate = SdrHdrFrameGate()
        assertTrue(gate.available())
        assertFalse(gate.consume(connected = false))
        assertTrue(gate.available())
        assertFalse(gate.available())
        assertFalse(gate.consume(connected = false))
        assertTrue(gate.consume(connected = true))
        assertFalse(gate.consume(connected = true))
    }

    @Test fun duplicateTimestampsDoNotRepeatSwapsAndBackwardsSeeksRemainValid() {
        val gate = SdrHdrFrameGate()
        assertTrue(gate.acceptTimestamp(25_000_000_000L))
        assertFalse(gate.acceptTimestamp(25_000_000_000L))
        assertTrue(gate.acceptTimestamp(1_000_000_000L))
        assertFalse(gate.acceptTimestamp(1_000_000_000L))
        gate.discontinuity()
        assertTrue(gate.acceptTimestamp(1_000_000_000L))
        repeat(3) { assertTrue(gate.acceptTimestamp(0L)) }
    }
}
