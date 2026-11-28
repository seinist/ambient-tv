package com.ambient.tv.ui.home

import com.ambient.tv.ui.common.CyclicRowFocusKey
import com.ambient.tv.ui.common.logicalRowFocusKey
import org.junit.Assert.*
import org.junit.Test

class HomeCyclicRowsTest {
    @Test fun lastToFirstIsOnePhysicalRowStep() {
        val source = listOf("plex.continue", "jellyfin.recent", "live-tv")
        val virtual = cyclicHomeRows(source)
        assertEquals(CyclicRowFocusKey("live-tv", 0), virtual[2])
        assertEquals(CyclicRowFocusKey("plex.continue", 1), virtual[3])
        assertEquals(source.size * 9, virtual.size)
        assertEquals(source, virtual.drop(3).take(3).map { it.logical })
    }

    @Test fun sameLogicalRowSharesHorizontalFocusAcrossLaps() {
        assertEquals("plex.continue", logicalRowFocusKey(CyclicRowFocusKey("plex.continue", 0)))
        assertEquals("plex.continue", logicalRowFocusKey(CyclicRowFocusKey("plex.continue", 5)))
        assertEquals("plex.continue", logicalRowFocusKey("plex.continue"))
    }

    @Test fun rebaseNeverReturnsToFirstLapOrAnInvalidIndex() {
        val count = 3
        assertNull(cyclicHomeRebaseViewportIndex(7, count, lap = 4))
        assertEquals(10, cyclicHomeRebaseViewportIndex(16, count, lap = 5))
        assertEquals(4, cyclicHomeRebaseViewportIndex(10, count, lap = 5))
        assertNull(cyclicHomeRebaseViewportIndex(8, count, lap = 5))
        assertNull(cyclicHomeRebaseViewportIndex(12, rowCount = 1, lap = 7))
    }

    @Test fun aSingleRowIsNotDuplicated() {
        assertEquals(listOf(CyclicRowFocusKey("only", 1)), cyclicHomeRows(listOf("only")))
        assertTrue(cyclicHomeRows(emptyList()).isEmpty())
    }

    @Test fun liveDestinationBehindHeroDoesNotHideOutgoingMediaRow() {
        val media = homePhysicalRowKey(CyclicRowFocusKey("plex.continue", 0))
        val live = homePhysicalRowKey(CyclicRowFocusKey("live-tv", 0))
        assertEquals(media, homeVisibleHeroRow(live, listOf(
            HomeHeroRowBounds(live, 0, 100),
            HomeHeroRowBounds(media, 100, 200),
        ), 100, 300))
    }

    @Test fun leavingShortLiveRowKeepsItVisibleUntilMediaArrives() {
        val live = HomeHeroRowBounds("live", 120, 100)
        val media = HomeHeroRowBounds("media", 224, 180)
        assertEquals("live", homeVisibleHeroRow("media", listOf(live, media), 100, 240))
        assertEquals("media", homeVisibleHeroRow("media", listOf(
            live.copy(offset = 20), media.copy(offset = 124),
        ), 100, 300))
    }

    @Test fun preferredParkedShortRowWinsOverAdjacentRow() {
        assertEquals("live", homeVisibleHeroRow("live", listOf(
            HomeHeroRowBounds("media", 0, 150),
            HomeHeroRowBounds("live", 154, 90),
        ), 100, 250))
    }

    @Test fun removedOrOffscreenPreferredRowFallsBackToVisibleRow() {
        val visible = HomeHeroRowBounds("media", 100, 120)
        assertEquals("media", homeVisibleHeroRow("removed", listOf(visible), 100, 240))
        assertEquals("media", homeVisibleHeroRow("live", listOf(visible,
            HomeHeroRowBounds("live", 244, 100)), 100, 240))
    }

    @Test fun paddedLazyViewportUsesContentCoordinatesAndDistinctLapKeys() {
        val old = homePhysicalRowKey(CyclicRowFocusKey("live-tv", 0))
        val next = homePhysicalRowKey(CyclicRowFocusKey("live-tv", 1))
        assertNotEquals(old, next)
        assertEquals(next, homeVisibleHeroRow(old, listOf(
            HomeHeroRowBounds(old, -220, 100),
            HomeHeroRowBounds(next, -116, 100),
        ), -120, 0))
    }
}
