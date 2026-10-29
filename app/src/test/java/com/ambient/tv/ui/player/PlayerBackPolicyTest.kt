package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackPolicyTest {
    /** TV keeps its existing default, including live radio and live video. */
    @Test fun backStopsEverythingOnATelevision() {
        listOf(false, true).forEach { audio ->
            listOf(false, true).forEach { live ->
                assertFalse(backLeavesPlaybackRunning(audioOnly = audio, handheld = false, isLive = live))
            }
        }
    }

    @Test fun handheldKeepsAudioRunningWhenLeavingThePlayer() {
        assertTrue(backLeavesPlaybackRunning(audioOnly = true, handheld = true))
        assertTrue(backLeavesPlaybackRunning(audioOnly = true, handheld = true, isLive = true))
    }

    @Test fun handheldKeepsLiveContentWithoutWaitingForAudioOnlyTracks() {
        // Live TV contains video; live radio can still have an empty track list during startup.
        // Both must minimize without relying on audio-only detection or the optional setting.
        assertTrue(backLeavesPlaybackRunning(audioOnly = false, handheld = true, isLive = true))
    }

    @Test fun handheldOnDemandVideoStillStopsByDefault() {
        assertFalse(backLeavesPlaybackRunning(audioOnly = false, handheld = true))
    }

    @Test fun minimizeSettingKeepsEverythingRunning() {
        listOf(true, false).forEach { audio ->
            listOf(true, false).forEach { handheld ->
                listOf(false, true).forEach { live ->
                    assertTrue(backLeavesPlaybackRunning(audioOnly = audio, handheld = handheld, minimizeOnBack = true, isLive = live))
                }
            }
        }
    }
}
