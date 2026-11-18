package com.ambient.tv.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinematicViewportTest {
    @Test fun roomyTvRetainsOriginalCardWidth() {
        assertEquals(252.dp, libraryCinematicTileWidth(600.dp, poster = false, 252.dp))
        assertEquals(172.dp, libraryCinematicTileWidth(600.dp, poster = true, 172.dp))
        assertEquals(300.dp, youtubeCinematicTileWidth(500.dp, 300.dp))
    }

    @Test fun shortViewportFitsWithoutClippingBottomOfPosterOrLandscape() {
        val available = 345.dp
        val landscape = libraryCinematicTileWidth(available, poster = false, 252.dp)
        val poster = libraryCinematicTileWidth(available, poster = true, 172.dp)
        assertTrue(landscape < 252.dp)
        assertTrue(poster < 172.dp)
        assertTrue(landscape * (9f / 16f) + 130.dp + 120.dp <= available)
        assertTrue(poster * 1.5f + 54.dp + 110.dp <= available)
    }

    @Test fun shortSmartTubeSectionShrinksButFullHeightKeepsItsCards() {
        assertTrue(youtubeCinematicTileWidth(250.dp, 300.dp) < 300.dp)
        assertEquals(300.dp, youtubeCinematicTileWidth(450.dp, 300.dp))
    }

    @Test fun handheldWidthNeverGrowsBeyondOriginal() {
        assertEquals(84.dp, libraryCinematicTileWidth(390.dp, poster = true, 84.dp))
        assertEquals(240.dp, libraryCinematicTileWidth(390.dp, poster = false, 240.dp))
        assertEquals(260.dp, youtubeCinematicTileWidth(390.dp, 260.dp))
    }
}
