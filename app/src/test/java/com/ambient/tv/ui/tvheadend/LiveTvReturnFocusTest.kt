package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.GuideOptions
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.livetv.LiveTvDashboard
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvReturnFocus
import com.ambient.tv.livetv.LiveTvServerInfo
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvReturnFocusTest {
    private val now = 10_000L
    private fun channel(uuid: String, number: Int, kind: LiveTvChannelKind = LiveTvChannelKind.TV) =
        LiveTvChannel(uuid, "Sender $uuid", number, null, kind = kind)
    private fun event(id: String, channel: String, start: Long, stop: Long) =
        LiveTvProgramme(id, channel, "", "Sendung $id", startEpochSeconds = start, stopEpochSeconds = stop)
    private val channels = listOf(channel("a", 1), channel("b", 2), channel("c", 3), channel("r", 4, LiveTvChannelKind.RADIO))
    private val events = listOf(
        event("a1", "a", now - 3000, now - 600), event("a2", "a", now - 600, now + 600), event("a3", "a", now + 600, now + 1200),
        event("c1", "c", now - 1000, now + 100), event("c2", "c", now + 100, now + 900),
    )
    private val dashboard = LiveTvDashboard(LiveTvServerInfo("TVH", null, null), channels, events, emptyList(), emptyList())

    @After fun reset() = LiveTvReturnFocus.clear()

    @Test fun watchedChannelIsTakenOncePerSource() {
        LiveTvReturnFocus.watched("tvh", "a")
        LiveTvReturnFocus.watched("tvh", "c") // zapped on
        LiveTvReturnFocus.watched("plex:1", "x")
        assertEquals("c", LiveTvReturnFocus.take("tvh"))
        assertNull(LiveTvReturnFocus.take("tvh"))
        assertEquals("x", LiveTvReturnFocus.take("plex:1"))
    }

    @Test fun blankIdsAreIgnored() {
        LiveTvReturnFocus.watched("", "a")
        LiveTvReturnFocus.watched("tvh", "")
        assertNull(LiveTvReturnFocus.take("tvh"))
        assertNull(LiveTvReturnFocus.take(""))
    }

    @Test fun returnedChannelBecomesLiveAndGuideSelection() {
        val selections = returnFocusSelections("c", dashboard, GuideOptions(), now)!!
        assertEquals(TvhFocusTarget("c"), selections[TvheadendTab.LIVE])
        assertEquals(TvhFocusTarget("channel:c", "c"), selections[TvheadendTab.GUIDE])
        assertEquals(TvhFocusTarget("c"), liveTvFocusTarget(TvheadendTab.LIVE, dashboard, GuideOptions(), "c", null, now))
        assertEquals(TvhFocusTarget("channel:c", "c"), liveTvFocusTarget(TvheadendTab.GUIDE, dashboard, GuideOptions(), "channel:c", "c", now))
    }

    @Test fun preparedGuideMaintainsTheSameReturnFocusAndLiveZapSelection() {
        val options = GuideOptions()
        val listing = buildLiveTvChannelListing(channels, events, options, now)
        assertEquals(
            liveTvFocusTarget(TvheadendTab.LIVE, dashboard, options, "c", null, now),
            liveTvFocusTarget(TvheadendTab.LIVE, dashboard, options, "c", null, now, listing),
        )
        assertEquals(
            liveTvFocusTarget(TvheadendTab.GUIDE, dashboard, options, "event:a3", "a", now),
            liveTvFocusTarget(TvheadendTab.GUIDE, dashboard, options, "event:a3", "a", now, listing),
        )
        assertEquals(
            liveTvFocusTarget(TvheadendTab.GUIDE, dashboard, options, "event:gone", "zz", now),
            liveTvFocusTarget(TvheadendTab.GUIDE, dashboard, options, "event:gone", "zz", now, listing),
        )
    }

    @Test fun channelHiddenByFilterKeepsThePreviousSelection() {
        val tvOnly = GuideOptions(kind = LiveTvChannelKind.TV)
        assertNull(returnFocusSelections("r", dashboard, tvOnly, now))
        // The previous selection still applies; a hidden one falls back to the first channel.
        assertEquals(TvhFocusTarget("b"), liveTvFocusTarget(TvheadendTab.LIVE, dashboard, tvOnly, "b", null, now))
        assertEquals(TvhFocusTarget("a"), liveTvFocusTarget(TvheadendTab.LIVE, dashboard, tvOnly, "r", null, now))
    }

    @Test fun withoutDataTheReturnedChannelIsStillStored() {
        assertEquals(TvhFocusTarget("q"), returnFocusSelections("q", null, GuideOptions(), now)!![TvheadendTab.LIVE])
    }

    @Test fun guideEventSelectionStillWorks() {
        assertEquals(TvhFocusTarget("event:a3", "a"), liveTvFocusTarget(TvheadendTab.GUIDE, dashboard, GuideOptions(), "event:a3", "a", now))
        // An unknown selection starts at the first row's first programme.
        assertEquals(TvhFocusTarget("event:a1", "a"), liveTvFocusTarget(TvheadendTab.GUIDE, dashboard, GuideOptions(), "event:gone", "zz", now))
    }

    @Test fun guideRowStartsAtTheRunningProgramme() {
        val rowA = events.filter { it.channelUuid == "a" }
        assertEquals(1, guideLiveIndex(rowA, now))
        assertEquals(2, guideLiveIndex(rowA, now + 700))
        assertEquals(0, guideLiveIndex(rowA, now + 5000))
        assertEquals(0, guideLiveIndex(emptyList(), now))
    }

    @Test fun playerReportsStartAndEverySuccessfulZap() {
        val source = File("src/main/java/com/ambient/tv/ui/tvheadend/TvheadendPlayerViewModel.kt").readText()
        assertTrue(source.contains("LiveTvReturnFocus.watched(repository.source.id, initialId)"))
        val zap = source.substringAfter("fun zap(delta: Int)").substringBefore("} catch (error: Throwable)")
        assertTrue(zap.substringAfter("TvheadendPlayerState.Ready(").contains("LiveTvReturnFocus.watched(repository.source.id, channel.uuid)"))
    }

    @Test fun browserScrollsToTheTargetBeforeFocusing() {
        val guide = File("src/main/java/com/ambient/tv/ui/tvheadend/Guide.kt").readText()
        val marker = "LaunchedEffect(restoreFocus, focusTarget, visibleChannels, filterFocusState, listing.building)"
        assertTrue(guide.contains(marker))
        val restore = guide.substringAfter(marker)
        assertTrue(restore.indexOf("scrollToItem") in 0 until restore.indexOf("firstContentFocusRequester.requestFocus()"))
        val screen = File("src/main/java/com/ambient/tv/ui/tvheadend/TvheadendScreen.kt").readText()
        assertTrue(screen.contains("viewModel.consumeReturnFocus()"))
    }

    @Test fun channelWatchedThroughAMergedDuplicateSelectsTheShownRow() {
        // G7: two sources of the same channel collapse into one Guide row.
        val twin = listOf(LiveTvChannel("x1", "Das Erste", 1, null), LiveTvChannel("x2", "Das Erste", 2, null))
        val programmes = listOf(event("p1", "x1", now - 100, now + 100), event("p1", "x2", now - 100, now + 100))
        val merged = LiveTvDashboard(LiveTvServerInfo("TVH", null, null), twin, programmes, emptyList(), emptyList())
        assertEquals(listOf("x1"), visibleChannelIds(merged, GuideOptions(), now))
        val selections = returnFocusSelections("x2", merged, GuideOptions(), now)
        assertEquals(TvhFocusTarget("x1"), selections?.get(TvheadendTab.LIVE))
        assertEquals(TvhFocusTarget("channel:x1", "x1"), selections?.get(TvheadendTab.GUIDE))
        assertNull(returnFocusSelections("unknown", merged, GuideOptions(), now))
    }

    @Test fun guideJumpsWaitForTheRowOnlyBriefly() {
        val guide = File("src/main/java/com/ambient/tv/ui/tvheadend/Guide.kt").readText()
        assertEquals(2, Regex("withTimeoutOrNull\\(GUIDE_ROW_LAYOUT_WAIT_MS\\)").findAll(guide).count())
    }
}
