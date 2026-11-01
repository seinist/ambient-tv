package com.ambient.tv.ui.home

import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.ui.common.MediaTileStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeMonogramLoadingRowsTest {
    @Test fun monogramFlagsFollowTheSameRowsAsTheStyles() {
        val rows = listOf(
            HomeRowDefinition(id = "tg", system = HomeRowDefinition.SYSTEM_TELEGRAM, contentKey = "recent", displayMode = "landscape"),
            HomeRowDefinition(id = "off", system = HomeRowDefinition.SYSTEM_TELEGRAM, contentKey = "audio", enabled = false),
            HomeRowDefinition(id = "plex", system = "plex", contentKey = "recent", displayMode = "poster"),
        )
        assertEquals(listOf(true, false), homeLoadingRowsMonogram(rows))
        assertEquals(listOf(MediaTileStyle.LANDSCAPE, MediaTileStyle.POSTER), homeLoadingRowStyles(rows))
    }

    @Test fun noMonogramRowsWithoutTelegram() {
        assertEquals(emptyList<Boolean>(), homeLoadingRowsMonogram(emptyList()))
        val plex = HomeRowDefinition(id = "p", system = "plex", contentKey = "continue")
        assertEquals(listOf(false), homeLoadingRowsMonogram(listOf(plex)))
    }
}
