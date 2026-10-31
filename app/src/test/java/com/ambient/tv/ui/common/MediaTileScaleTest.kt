package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTileScaleTest {
    private val hero = HeroRowTileScale(poster = 1.35f, landscape = 1.75f)

    @Test fun skeletonAndRealTilesUseIdenticalHeroScale() {
        assertEquals(1.35f, mediaTileHomeScale(MediaTileStyle.POSTER, heroRow = true, scale = hero), 0f)
        assertEquals(1.35f, mediaTileHomeScale(MediaTileStyle.AUTO, heroRow = true, scale = hero), 0f)
        assertEquals(1.75f, mediaTileHomeScale(MediaTileStyle.LANDSCAPE, heroRow = true, scale = hero), 0f)
        assertEquals(1.75f, mediaTileHomeScale(MediaTileStyle.CONTINUE, heroRow = true, scale = hero), 0f)
        assertEquals(1.75f, mediaTileHomeScale(MediaTileStyle.EPISODE, heroRow = true, scale = hero), 0f)
    }

    @Test fun phoneAndNonHeroRowsKeepRegularWidths() {
        MediaTileStyle.entries.forEach { style ->
            assertEquals(1f, mediaTileHomeScale(style, heroRow = false, scale = hero), 0f)
        }
    }
}
