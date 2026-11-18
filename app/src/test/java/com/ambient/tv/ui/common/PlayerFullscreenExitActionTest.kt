package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerFullscreenExitActionTest {
    @Test fun tvOsdExitRegistersReturnPathBeforeThePlayerPops() {
        val calls = mutableListOf<String>()
        playerFullscreenExitAction(
            handheld = false,
            onMinimize = { calls += "arm-back-to-player" },
            onBack = { calls += "pop-fullscreen" },
        )
        assertEquals(listOf("arm-back-to-player", "pop-fullscreen"), calls)
    }

    @Test fun handheldExitRetainsTheNormalBackSemantics() {
        val calls = mutableListOf<String>()
        playerFullscreenExitAction(
            handheld = true,
            onMinimize = { calls += "arm-back-to-player" },
            onBack = { calls += "pop-fullscreen" },
        )
        assertEquals(listOf("pop-fullscreen"), calls)
    }
}
