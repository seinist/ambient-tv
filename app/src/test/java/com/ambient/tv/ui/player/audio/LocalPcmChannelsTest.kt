package com.ambient.tv.ui.player.audio

import com.ambient.tv.ui.player.localDecodePcmChannelMode
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalPcmChannelsTest {
    @Test fun stereoMixerReceivesCenterAndSurroundBeforeNativeS16Quantization() {
        assertEquals(8, localDownmixInputChannels(8))
        assertEquals(8, localDownmixInputChannels(7))
        for (channels in 3..6) assertEquals(6, localDownmixInputChannels(channels))
        assertEquals(2, localDownmixInputChannels(2))
        assertEquals("stereo", localDecodePcmChannelMode("auto", 2))
    }

    @Test fun sevenOneKeepsEightChannelsOnAnEightChannelRoute() {
        assertEquals(listOf(8, 6, 2), localPcmChannelCandidates(8, 8, "auto"))
        assertEquals(listOf(8, 6, 2), localPcmChannelCandidates(8, 8, "7.1"))
    }

    @Test fun fiveOneRouteOrPlanCapsAtSix() {
        assertEquals(listOf(6, 2), localPcmChannelCandidates(8, 6, "auto"))
        assertEquals(listOf(6, 2), localPcmChannelCandidates(8, 8, "5.1"))
        assertEquals(listOf(6, 2), localPcmChannelCandidates(6, 8, "auto"))
    }

    @Test fun stereoPlansAndSourcesStayStereo() {
        assertEquals(listOf(2), localPcmChannelCandidates(8, 8, "stereo"))
        assertEquals(listOf(2), localPcmChannelCandidates(2, 8, "auto"))
        assertEquals(listOf(2), localPcmChannelCandidates(8, 2, "auto"))
    }

    @Test fun localDecodePlanFollowsRequestedChannelsNotFixedStereo() {
        assertEquals("auto", localDecodePcmChannelMode("auto", 8))
        assertEquals("5.1", localDecodePcmChannelMode("7.1", 6))
        assertEquals("stereo", localDecodePcmChannelMode("stereo", 8))
        assertEquals("stereo", localDecodePcmChannelMode("5.1", 2))
    }
}
