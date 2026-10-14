package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.guideChannels
import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import org.junit.Assert.assertEquals
import org.junit.Test

class GuideChannelsTest {
    private fun channel(uuid: String, name: String, number: Int) = LiveTvChannel(uuid, name, number, null)
    private fun event(channelUuid: String, stop: Long) = LiveTvProgramme("$channelUuid:$stop", channelUuid, "", "Show", startEpochSeconds = stop - 3600, stopEpochSeconds = stop)

    @Test fun sameStationOnTwoChannelsIsOneGuideRow() {
        val channels = listOf(channel("a", "ZDF HD", 2), channel("b", "zdf  hd ", 102), channel("c", "ARD", 1))
        val events = listOf(event("a", 10_000), event("b", 10_000), event("c", 10_000)).groupBy { it.channelUuid }
        assertEquals(listOf("c", "a"), guideChannels(channels, events, 5_000).map { it.uuid })
    }

    @Test fun duplicateWithoutCurrentGuideDoesNotHideTheOneWithIt() {
        val channels = listOf(channel("a", "ZDF HD", 2), channel("b", "ZDF HD", 102))
        val events = listOf(event("a", 1_000), event("b", 10_000)).groupBy { it.channelUuid }
        assertEquals(listOf("b"), guideChannels(channels, events, 5_000, GuideOptions(onlyWithEpg = true)).map { it.uuid })
    }
}
