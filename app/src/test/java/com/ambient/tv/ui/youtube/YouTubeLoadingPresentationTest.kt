package com.ambient.tv.ui.youtube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeLoadingPresentationTest {
    @Test fun initialLoadingUsesTheSelectedViewNotAnUnconditionalLandscapeRow() {
        assertEquals(YouTubeLoadingPresentation.ROWS, youtubeLoadingPresentation(YouTubeViewMode.LANDSCAPE, sectioned = true))
        assertEquals(YouTubeLoadingPresentation.GRID, youtubeLoadingPresentation(YouTubeViewMode.LANDSCAPE, sectioned = false))
        assertEquals(YouTubeLoadingPresentation.LIST, youtubeLoadingPresentation(YouTubeViewMode.LIST, sectioned = true))
        assertEquals(YouTubeLoadingPresentation.INFO_LIST, youtubeLoadingPresentation(YouTubeViewMode.INFO_LIST, sectioned = false))
        assertEquals(YouTubeLoadingPresentation.WALL, youtubeLoadingPresentation(YouTubeViewMode.WALL, sectioned = true))
        assertEquals(YouTubeLoadingPresentation.CINEMATIC, youtubeLoadingPresentation(YouTubeViewMode.CINEMATIC, sectioned = false))
    }
}
