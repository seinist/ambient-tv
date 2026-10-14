package com.ambient.tv.ui.sound

import android.media.AudioAttributes
import org.junit.Assert.*
import org.junit.Test

class NavSoundDevicePolicyTest {
    @Test fun handsetUsesMediaVolumeWhileTvKeepsSystemSoundRouting() {
        assertEquals(AudioAttributes.USAGE_MEDIA, navSoundAudioUsage(false))
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, navSoundAudioUsage(true))
    }

    @Test fun lowProfileDoesNotMuteHandsetOrExplicitPreview() {
        assertTrue(navSoundProfileAllowed(false, false, false))
        assertTrue(navSoundProfileAllowed(false, true, false))
        assertTrue(navSoundProfileAllowed(true, true, false))
        assertFalse(navSoundProfileAllowed(true, false, false))
        assertTrue(navSoundProfileAllowed(true, false, true))
    }

    @Test fun previewStillRespectsPlaybackSuppression() {
        val allowed = navSoundProfileAllowed(false, true, false)
        assertFalse(menuSoundAllowed(true, allowed, true, false, false))
        assertFalse(menuSoundAllowed(true, allowed, false, true, false))
        assertFalse(menuSoundAllowed(true, allowed, false, false, true))
        assertTrue(menuSoundAllowed(true, allowed, false, false, false))
    }
}
