package com.ambient.tv.ui.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePointerPolicyTest {
    @Test fun regularPadGameplayHidesThePointerAndOpeningTheMenuRestoresIt() {
        val pads = listOf(null, 1, 5, 0x101)
        assertFalse(gamePointerVisible(false, false, pads))
        assertTrue(gamePointerVisible(true, false, pads))
        assertFalse(gamePointerVisible(false, false, pads))
    }

    @Test fun TouchscreenConsolesAndExplicitMouseGunPointerPortsKeepTheirCursor() {
        assertTrue(gamePointerVisible(false, true, listOf(1)))
        for (device in listOf(2, 4, 6, 0x102, 0x204, 0x306)) {
            assertTrue("Pointer device $device", gamePointerVisible(false, false, listOf(1, device)))
        }
        assertFalse(gamePointerVisible(false, false, listOf(-254, -1, 0)))
    }
}
