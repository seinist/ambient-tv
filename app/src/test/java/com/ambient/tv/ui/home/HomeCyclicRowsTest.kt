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
}
