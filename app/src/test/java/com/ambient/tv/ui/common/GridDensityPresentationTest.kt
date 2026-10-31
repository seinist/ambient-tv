package com.ambient.tv.ui.common

import com.ambient.tv.ui.mobile.handheldGridColumns
import com.ambient.tv.ui.mobile.handheldPortraitColumns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridDensityPresentationTest {
    @Test fun radioPhonePortraitSettingsMatchRealRenderedGrid() {
        val shown = visibleGridColumns(8, ViewPreview.GRID, GridDensityProfile.RADIO_BROWSER, true, 411, 900)
        assertEquals(handheldPortraitColumns(8, 411, 160f, 8), shown)
        assertTrue(shown != 8)
    }

    @Test fun radioPhoneLandscapeSettingsMatchRealRenderedGrid() {
        val shown = visibleGridColumns(8, ViewPreview.GRID, GridDensityProfile.RADIO_BROWSER, true, 900, 411)
        assertEquals(handheldGridColumns(8, 900, 160f, 8), shown)
    }

    @Test fun mediaAndSmarttubeAccountForDifferentArtworkGeometry() {
        assertEquals(
            handheldPortraitColumns(8, 411, 144f, 8),
            visibleGridColumns(8, ViewPreview.POSTER, GridDensityProfile.MEDIA_LIBRARY, true, 411, 900),
        )
        assertEquals(
            handheldPortraitColumns(4, 411, 144f, 4),
            visibleGridColumns(8, ViewPreview.LANDSCAPE, GridDensityProfile.MEDIA_LIBRARY, true, 411, 900),
        )
        assertEquals(
            handheldPortraitColumns(10, 411, 144f, 8),
            visibleGridColumns(8, ViewPreview.WALL, GridDensityProfile.MEDIA_LIBRARY, true, 411, 900),
        )
        assertEquals(
            handheldPortraitColumns(12, 411, 96f, 8),
            visibleGridColumns(8, ViewPreview.POSTER_WALL, GridDensityProfile.MEDIA_LIBRARY, true, 411, 900),
        )
        assertEquals(
            handheldPortraitColumns(10, 411, 260f, 8),
            visibleGridColumns(8, ViewPreview.WALL, GridDensityProfile.SMARTTUBE, true, 411, 900),
        )
        assertEquals(
            handheldGridColumns(4, 900, 260f, 4),
            visibleGridColumns(8, ViewPreview.LANDSCAPE, GridDensityProfile.SMARTTUBE, true, 900, 411),
        )
    }

    @Test fun tvWallAndLandscapeLabelsRepresentRealColumnCount() {
        assertEquals(10, visibleGridColumns(8, ViewPreview.WALL, GridDensityProfile.MEDIA_LIBRARY, false, 1920, 1080))
        assertEquals(12, visibleGridColumns(8, ViewPreview.POSTER_WALL, GridDensityProfile.MEDIA_LIBRARY, false, 1920, 1080))
        assertEquals(4, visibleGridColumns(8, ViewPreview.LANDSCAPE, GridDensityProfile.MEDIA_LIBRARY, false, 1920, 1080))
        assertEquals(10, visibleGridColumns(8, ViewPreview.WALL, GridDensityProfile.SMARTTUBE, false, 1920, 1080))
    }

    @Test fun everySliderStepChangesTheActualRenderedColumnCount() {
        val previews = listOf(
            GridDensityProfile.RADIO_BROWSER to ViewPreview.GRID,
            GridDensityProfile.MEDIA_LIBRARY to ViewPreview.POSTER,
            GridDensityProfile.MEDIA_LIBRARY to ViewPreview.LANDSCAPE,
            GridDensityProfile.MEDIA_LIBRARY to ViewPreview.WALL,
            GridDensityProfile.MEDIA_LIBRARY to ViewPreview.POSTER_WALL,
            GridDensityProfile.SMARTTUBE to ViewPreview.LANDSCAPE,
            GridDensityProfile.SMARTTUBE to ViewPreview.WALL,
        )
        for ((width, height, handheld) in listOf(Triple(411, 900, true), Triple(900, 411, true), Triple(1920, 1080, false))) {
            for ((profile, preview) in previews) {
                val choices = visibleGridDensityChoices(preview, profile, handheld, width, height)
                assertTrue(choices.isNotEmpty())
                assertTrue(choices.map { it.visibleColumns }.zipWithNext().all { (a, b) -> b > a })
                choices.forEach { choice ->
                    assertEquals(choice.visibleColumns,
                        visibleGridColumns(choice.storedColumns, preview, profile, handheld, width, height))
                }
            }
        }
    }
}
