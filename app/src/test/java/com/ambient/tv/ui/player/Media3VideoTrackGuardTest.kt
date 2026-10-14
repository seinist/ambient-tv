package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class Media3VideoTrackGuardTest {
    @Test fun unsupportedVideoIsDetected() {
        assertTrue(media3HasUnsupportedVideo(listOf(false), false))
        assertTrue(media3HasUnsupportedVideo(listOf(false, false), false))
    }
    @Test fun intentionalAudioOnlyAndEmptyTracksDoNotTriggerFallback() {
        assertFalse(media3HasUnsupportedVideo(emptyList(), false))
        assertFalse(media3HasUnsupportedVideo(listOf(false), true))
    }
    @Test fun playableVideoAlternativePreventsFallback() {
        assertFalse(media3HasUnsupportedVideo(listOf(false, true), false))
    }
}
