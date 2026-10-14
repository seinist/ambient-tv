package com.ambient.tv.ui.sound

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuSoundPolicyTest {
    private fun allowed(
        enabled: Boolean = true,
        profileAllows: Boolean = true,
        playbackActive: Boolean = false,
        spotifyPlaying: Boolean = false,
        themeMusicAudible: Boolean = false,
    ) = menuSoundAllowed(enabled, profileAllows, playbackActive, spotifyPlaying, themeMusicAudible)

    @Test fun enabledAndQuietScreenClicks() {
        assertTrue(allowed())
    }

    /** The switch wins over everything: off means silent, in every other combination. */
    @Test fun disabledIsAlwaysSilent() {
        for (profile in listOf(true, false)) for (playback in listOf(true, false)) {
            for (spotify in listOf(true, false)) for (theme in listOf(true, false)) {
                assertFalse(
                    "enabled=false profile=$profile playback=$playback spotify=$spotify theme=$theme",
                    allowed(enabled = false, profileAllows = profile, playbackActive = playback,
                        spotifyPlaying = spotify, themeMusicAudible = theme),
                )
            }
        }
    }

    @Test fun anythingAudibleOnTheSpeakersSilencesClicks() {
        assertFalse(allowed(playbackActive = true))
        assertFalse(allowed(spotifyPlaying = true))
        assertFalse(allowed(themeMusicAudible = true))
        assertFalse(allowed(profileAllows = false))
    }
}
