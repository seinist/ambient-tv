package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackPolicyTest {
    /** The rule the user asked for: on a TV, Back stops - whatever is playing. */
    @Test fun backStopsEverythingOnATelevision() {
        assertFalse(backLeavesPlaybackRunning(audioOnly = false, handheld = false))
        assertFalse("Music must stop too", backLeavesPlaybackRunning(audioOnly = true, handheld = false))
    }

    @Test fun handheldKeepsAudioRunningWhenLeavingThePlayer() {
        assertTrue(backLeavesPlaybackRunning(audioOnly = true, handheld = true))
        assertFalse("Video still stops", backLeavesPlaybackRunning(audioOnly = false, handheld = true))
    }

    @Test fun minimizeSettingKeepsEverythingRunning() {
        listOf(true, false).forEach { audio ->
            listOf(true, false).forEach { handheld ->
                assertTrue(backLeavesPlaybackRunning(audioOnly = audio, handheld = handheld, minimizeOnBack = true))
            }
        }
    }
}
