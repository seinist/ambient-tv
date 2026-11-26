package com.ambient.tv.moonlight

import org.junit.Assert.*
import org.junit.Test

class MoonlightStreamProfileTest {
    @Test fun validPresetsAndInputBounds() {
        assertEquals(MoonlightStreamProfile(), MoonlightStreamProfile().validated())
        assertEquals("av1", MoonlightStreamProfile(codec = "av1", width = 3840, height = 2160).validated().codec)
        assertThrows(IllegalArgumentException::class.java) { MoonlightStreamProfile(width = 1).validated() }
        assertThrows(IllegalArgumentException::class.java) { MoonlightStreamProfile(width = 1919).validated() }
        assertThrows(IllegalArgumentException::class.java) { MoonlightStreamProfile(fps = 241).validated() }
        assertThrows(IllegalArgumentException::class.java) { MoonlightStreamProfile(bitrateKbps = 500).validated() }
        assertThrows(IllegalArgumentException::class.java) { MoonlightStreamProfile(codec = "hdr10").validated() }
        assertThrows(IllegalArgumentException::class.java) { MoonlightStreamProfile(audioChannels = 4).validated() }
        for (pacing in MoonlightStreamProfile.PACING_MODES) assertEquals(pacing, MoonlightStreamProfile(pacing = pacing).validated().pacing)
        assertThrows(IllegalArgumentException::class.java) { MoonlightStreamProfile(pacing = "unknown").validated() }
    }

    @Test fun stoppingCannotBeResurrectedByDelayedConnectionCallbacks() {
        val connecting = moonlightNextPhase(MoonlightStreamPhase.IDLE, "connect")
        val stopping = moonlightNextPhase(connecting, "stop")
        assertEquals(MoonlightStreamPhase.STOPPING, stopping)
        assertEquals(stopping, moonlightNextPhase(stopping, "started"))
        assertEquals(MoonlightStreamPhase.ENDED, moonlightNextPhase(stopping, "ended"))
        assertEquals(MoonlightStreamPhase.CONNECTING, moonlightNextPhase(MoonlightStreamPhase.ENDED, "connect"))
    }
}
