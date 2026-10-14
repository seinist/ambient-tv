package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerControlBarScrollTest {
    @Test
    fun `offset survives within a session and resets for the next one`() {
        val first = "youtube" to "abc"
        PlayerControlBarScroll.save(first, "windowed", 240)
        PlayerControlBarScroll.save(first, "fullscreen", 80)
        assertEquals(240, PlayerControlBarScroll.offset(first, "windowed"))
        assertEquals(80, PlayerControlBarScroll.offset(first, "fullscreen"))
        assertEquals(0, PlayerControlBarScroll.offset("plex" to "1", "windowed"))
        assertEquals(0, PlayerControlBarScroll.offset(first, "windowed"))
    }
}
