package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerToolsAvailabilityTest {
    @Test fun liveVideoOffersOnlyScreenshotEvenWithAStaleBookmarkKey() {
        assertEquals(
            listOf(PlayerTool.SCREENSHOT),
            availablePlayerTools(hasVideo = true, isLive = true, hasHandle = true, hasBookmarkKey = true),
        )
    }

    @Test fun liveAudioOffersNoToolsEvenWithAStaleBookmarkKey() {
        assertEquals(
            emptyList<PlayerTool>(),
            availablePlayerTools(hasVideo = false, isLive = true, hasHandle = true, hasBookmarkKey = true),
        )
    }

    @Test fun onDemandVideoWithABookmarkKeyOffersItsCategoriesInPanelOrder() {
        assertEquals(
            listOf(PlayerTool.AB_REPEAT, PlayerTool.FRAME_STEP, PlayerTool.BOOKMARKS, PlayerTool.SCREENSHOT),
            availablePlayerTools(hasVideo = true, isLive = false, hasHandle = true, hasBookmarkKey = true),
        )
    }

    @Test fun onDemandVideoWithoutABookmarkKeyOmitsBookmarks() {
        assertEquals(
            listOf(PlayerTool.AB_REPEAT, PlayerTool.FRAME_STEP, PlayerTool.SCREENSHOT),
            availablePlayerTools(hasVideo = true, isLive = false, hasHandle = true, hasBookmarkKey = false),
        )
    }

    @Test fun onDemandAudioIncludesLyricsAndGaplessAlongsidePlaybackTools() {
        assertEquals(
            listOf(PlayerTool.AB_REPEAT, PlayerTool.BOOKMARKS, PlayerTool.LYRICS, PlayerTool.GAPLESS),
            availablePlayerTools(hasVideo = false, isLive = false, hasHandle = true, hasBookmarkKey = true),
        )
    }

    @Test fun onDemandAudioSettingsDoNotRequireABookmarkKey() {
        assertEquals(
            listOf(PlayerTool.AB_REPEAT, PlayerTool.LYRICS, PlayerTool.GAPLESS),
            availablePlayerTools(hasVideo = false, isLive = false, hasHandle = true, hasBookmarkKey = false),
        )
    }

    @Test fun losingTheHandleKeepsOnlyIndependentCaptureOrAudioSettings() {
        assertEquals(
            listOf(PlayerTool.SCREENSHOT),
            availablePlayerTools(hasVideo = true, isLive = false, hasHandle = false, hasBookmarkKey = true),
        )
        assertEquals(
            listOf(PlayerTool.LYRICS, PlayerTool.GAPLESS),
            availablePlayerTools(hasVideo = false, isLive = false, hasHandle = false, hasBookmarkKey = true),
        )
    }
}
