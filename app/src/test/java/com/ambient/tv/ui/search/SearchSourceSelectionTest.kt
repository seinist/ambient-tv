package com.ambient.tv.ui.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSourceSelectionTest {
    @Test fun localSourcesAreSelectedInitiallyWhileOnlineSourcesRemainOptional() {
        assertEquals(
            setOf(SearchSource.PLEX, SearchSource.EMBY, SearchSource.LIVE_TV),
            defaultSearchSources(setOf(SearchSource.PLEX, SearchSource.EMBY, SearchSource.LIVE_TV, SearchSource.YOUTUBE, SearchSource.SPOTIFY)),
        )
        assertEquals(setOf(SearchSource.YOUTUBE), defaultSearchSources(setOf(SearchSource.YOUTUBE)))
        assertEquals(setOf(SearchSource.YOUTUBE), reconcileSearchSources(emptySet(), emptySet(), setOf(SearchSource.YOUTUBE)))
    }

    @Test fun refreshKeepsChosenOnlineSourcesAndAddsNewLocalSources() {
        val before = setOf(SearchSource.PLEX, SearchSource.YOUTUBE)
        val selected = setOf(SearchSource.YOUTUBE)
        val after = setOf(SearchSource.YOUTUBE, SearchSource.EMBY, SearchSource.SPOTIFY)
        assertEquals(setOf(SearchSource.YOUTUBE, SearchSource.EMBY), reconcileSearchSources(selected, before, after))
    }

    @Test fun liveTvResultsFollowTheirOwningSystemChip() {
        assertEquals(SearchSource.PLEX, liveTvSearchSource("plex"))
        assertEquals(SearchSource.EMBY, liveTvSearchSource("emby"))
        assertEquals(SearchSource.LIVE_TV, liveTvSearchSource("tvheadend"))
        assertEquals(SearchSource.LIVE_TV, liveTvSearchSource("iptv"))
        assertEquals(null, liveTvSearchSource("unknown"))
    }
}
