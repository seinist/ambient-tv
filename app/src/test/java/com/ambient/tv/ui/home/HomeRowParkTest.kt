package com.ambient.tv.ui.home

import androidx.compose.ui.unit.dp
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.ui.common.RowColumnFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRowParkTest {
    @Test fun lastRowCanReachTheSameLineAsTheFirst() {
        val hero = 240.dp
        val park = homeRowParkOffset(hero)
        assertEquals(hero - HomeRowsLift + 12.dp, park)
        val viewport = 540.dp
        val bottom = homeLastRowBottomPadding(viewport, park)
        // The scroll range has to pass the park line, so a short last row is not left under the hero.
        assertTrue(bottom >= viewport - park)
        assertEquals(viewport - park, bottom)
    }

    @Test fun tinyViewportKeepsAMinimumTail() {
        assertEquals(36.dp, homeLastRowBottomPadding(viewport = 40.dp, parkOffset = 30.dp))
    }

    @Test fun liveRowOpensOnTheFirstCard() {
        val channels = listOf("a:1", "a:2", "a:3")
        assertEquals(0, homeLiveRowEntryIndex(null, "live-tv-channels", channels))
        assertEquals(0, homeLiveRowEntryIndex("missing", "live-tv-guide", channels))
        assertEquals(0, homeLiveRowEntryIndex("live-tv-channels", "live-tv-channels", channels))
        assertEquals(1, homeLiveRowEntryIndex("a:1", "live-tv-guide", channels))
        assertEquals(3, homeLiveRowEntryIndex("a:3", "live-tv-guide", channels))
        assertEquals(2, homeLiveRowEntryIndex("a:3", null, channels))
    }

    @Test fun restoredEndIsDroppedUntilTheUserMovesSideways() {
        val restored = RowColumnFocus(mapOf("recent-tv" to "a:3"))
        val entry = LiveRowEntry()
        entry.resetUnlessChosen("recent-tv", restored)
        assertNull(restored.rememberedItem("recent-tv"))

        val kept = RowColumnFocus(mapOf("recent-tv" to "a:3"))
        val chosen = LiveRowEntry()
        chosen.choose("recent-tv")
        chosen.resetUnlessChosen("recent-tv", kept)
        assertEquals("a:3", kept.rememberedItem("recent-tv"))
    }

    @Test fun focusingTheFirstLiveCardDoesNotScrollTowardTheEnd() {
        assertEquals(0f, homeLiveRowFocusScroll(focusedIndex = 0, distance = 2400f), 0f)
        assertEquals(-40f, homeLiveRowFocusScroll(focusedIndex = 0, distance = -40f), 0f)
        assertEquals(180f, homeLiveRowFocusScroll(focusedIndex = 4, distance = 180f), 0f)
    }

    @Test fun recentRowShortcutOpensThatSystemsChannelList() {
        assertEquals(HomeRowDefinition.SYSTEM_TVHEADEND, homeTvShortcut("recent-tv", "live").system)
        assertEquals("live", homeTvShortcut("recent-tv", "live").contentKey)
        assertEquals("plex", homeTvShortcut("recent-tv:plex", "live").system)
        assertEquals("guide", homeTvShortcut("live-tv", "guide").contentKey)
    }
}
