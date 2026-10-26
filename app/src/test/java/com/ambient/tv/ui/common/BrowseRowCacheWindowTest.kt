package com.ambient.tv.ui.common

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nested rows prepare one to two tiles ahead, not most of a screen width. */
class BrowseRowCacheWindowTest {
    // MediaTile: 160 dp poster / 300 dp landscape plus 2 x 8 dp tile padding and the 16 dp row gap.
    private val posterStep = 160.dp + 16.dp + 16.dp
    private val landscapeStep = 300.dp + 16.dp + 16.dp

    @Test fun aheadWindowCoversOneToTwoTiles() {
        assertTrue(BrowseRowCacheAhead >= posterStep)
        assertTrue(BrowseRowCacheAhead < posterStep * 2)
        assertTrue(BrowseRowCacheAhead < landscapeStep)
    }

    @Test fun behindWindowIsSmallerThanAhead() {
        assertTrue(BrowseRowCacheBehind > 0.dp)
        assertTrue(BrowseRowCacheBehind < BrowseRowCacheAhead)
    }
}
