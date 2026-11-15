package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.ui.player.backLeavesPlaybackRunning
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvBackPolicyTest {
    @Test fun radioKeepsPlayingOnHandheldBeforeTracksAreDiscovered() {
        val audioOnly = liveTvAudioOnlyForBack(LiveTvChannelKind.RADIO, detectedAudioOnly = false)

        assertTrue(backLeavesPlaybackRunning(audioOnly, handheld = true, minimizeOnBack = false))
        assertFalse(backLeavesPlaybackRunning(audioOnly, handheld = false, minimizeOnBack = false))
    }

    @Test fun tvChannelFollowsSwitchEvenWhileOnlyAudioTracksAreAvailable() {
        listOf(false, true).forEach { detectedAudioOnly ->
            val audioOnly = liveTvAudioOnlyForBack(LiveTvChannelKind.TV, detectedAudioOnly)

            listOf(false, true).forEach { handheld ->
                assertFalse(backLeavesPlaybackRunning(audioOnly, handheld, minimizeOnBack = false))
                assertTrue(backLeavesPlaybackRunning(audioOnly, handheld, minimizeOnBack = true))
            }
        }
    }

    @Test fun unknownChannelsAndRecordingsNeedConfirmedAudioBeforeKeepingPlayback() {
        listOf(LiveTvChannelKind.UNKNOWN, null).forEach { channelKind ->
            assertFalse(backLeavesPlaybackRunning(
                liveTvAudioOnlyForBack(channelKind, detectedAudioOnly = false),
                handheld = true,
                minimizeOnBack = false,
            ))
            assertTrue(backLeavesPlaybackRunning(
                liveTvAudioOnlyForBack(channelKind, detectedAudioOnly = true),
                handheld = true,
                minimizeOnBack = false,
            ))
        }
    }
}
