package com.ambient.tv.ui.search

import com.ambient.tv.ui.common.MediaSystem
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchLoadingArtworkTest {
    @Test fun loadingPlaceholdersFollowEachProvidersArtworkRatio() {
        assertEquals(SearchLoadingArtwork.POSTER, searchLoadingArtwork(MediaSystem.PLEX))
        assertEquals(SearchLoadingArtwork.POSTER, searchLoadingArtwork(MediaSystem.JELLYFIN))
        assertEquals(SearchLoadingArtwork.POSTER, searchLoadingArtwork(MediaSystem.EMBY))
        assertEquals(SearchLoadingArtwork.LANDSCAPE, searchLoadingArtwork(MediaSystem.YOUTUBE))
        assertEquals(SearchLoadingArtwork.LANDSCAPE, searchLoadingArtwork(MediaSystem.TVHEADEND))
        assertEquals(SearchLoadingArtwork.SQUARE, searchLoadingArtwork(MediaSystem.SPOTIFY))
        assertEquals(SearchLoadingArtwork.SQUARE, searchLoadingArtwork(MediaSystem.RADIO))
    }
}
