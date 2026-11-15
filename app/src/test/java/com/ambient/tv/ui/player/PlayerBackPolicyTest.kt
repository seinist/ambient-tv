package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackPolicyTest {
    /** TV keeps its existing default, including live radio and live video. */
    @Test fun backStopsEverythingOnATelevision() {
        listOf(false, true).forEach { audio ->
            assertFalse(backLeavesPlaybackRunning(audioOnly = audio, handheld = false))
        }
    }

    @Test fun handheldKeepsAudioRunningWhenLeavingThePlayer() {
        assertTrue(backLeavesPlaybackRunning(audioOnly = true, handheld = true))
    }

    @Test fun disablingBackgroundPlaybackStopsVideosOnEveryDevice() {
        // This includes live streams: source liveness must not override the user's choice.
        listOf(false, true).forEach { handheld ->
            assertFalse(backLeavesPlaybackRunning(audioOnly = false, handheld = handheld, minimizeOnBack = false))
        }
    }

    @Test fun minimizeSettingKeepsEverythingRunning() {
        listOf(true, false).forEach { audio ->
            listOf(true, false).forEach { handheld ->
                assertTrue(backLeavesPlaybackRunning(audioOnly = audio, handheld = handheld, minimizeOnBack = true))
            }
        }
    }
}
