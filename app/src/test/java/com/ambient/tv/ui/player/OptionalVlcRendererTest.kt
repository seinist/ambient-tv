package com.ambient.tv.ui.player

import com.ambient.tv.ui.tvheadend.LiveRendererOrigin
import com.ambient.tv.ui.tvheadend.selectLiveRenderer
import org.junit.Assert.*
import org.junit.Test

class OptionalVlcRendererTest {
    @Test fun unavailableVlcCannotBeSelectedInPanel() {
        val availability = RendererAvailability(dolbyVision = false, mpvAvailable = true, vlcAvailable = false)
        assertFalse(availability.canSelect("vlc"))
        assertTrue(availability.canSelect("mpv"))
        assertTrue(availability.canSelect("media3"))
    }

    @Test fun savedVlcChoiceFallsBackOnlyWhileUnavailable() {
        assertEquals("media3", savedPlaybackRenderer("vlc", null, null, true, false))
        assertEquals("vlc", savedPlaybackRenderer("vlc", null, null, true, true))
        for (source in listOf("youtube", "telegram")) {
            assertEquals("media3", resolveSourceAutoRenderer(source, "vlc", true, vlcAvailable = false))
            assertEquals("vlc", resolveSourceAutoRenderer(source, "vlc", true, vlcAvailable = true))
        }
    }

    @Test fun unavailableVlcCannotBeUsedForAudioRecovery() {
        assertNull(LocalAudioFallbackPolicy.nextBackend("media3", emptySet(), false, vlcAvailable = false))
        assertEquals("mpv", LocalAudioFallbackPolicy.nextBackend("media3", emptySet(), true, vlcAvailable = false))
        assertEquals("vlc", LocalAudioFallbackPolicy.nextBackend("media3", emptySet(), false, vlcAvailable = true))
    }

    @Test fun liveRendererReportsUnavailableInsteadOfOpeningMissingLibrary() {
        val selected = selectLiveRenderer("vlc", null, emptyMap(), null, true, vlcAvailable = false)
        assertEquals("media3", selected.key)
        assertEquals(LiveRendererOrigin.UNAVAILABLE, selected.origin)
    }
}
