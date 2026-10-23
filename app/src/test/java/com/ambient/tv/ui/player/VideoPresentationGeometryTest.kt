@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.*
import org.junit.Test

class VideoPresentationGeometryTest {
    @Test fun explicitFormatsUseTheLargestCompleteRectangle() {
        assertEquals(1920 to 1080, size(16f / 9f))
        assertEquals(1440 to 1080, size(4f / 3f))
        assertEquals(1920 to 823, size(21f / 9f))
    }

    @Test fun fixedFormatsOverrideSavedCropStretchAndZoom() {
        for (mode in listOf(0, 3, 4)) for (zoom in listOf(0.5f, 1f, 1.5f, 2f)) {
            val presentation = VideoPresentation(mode, 4f / 3f, zoom)
            assertEquals(1440 to 1080, videoPresentationSize(1920, 1080, presentation, 21f / 9f))
            assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, presentation.normalized().resizeMode)
            assertEquals(1f, presentation.normalized().zoom, 0f)
        }
    }

    @Test fun fixedFormatsFitLandscapePortraitAndMiniPlayerBounds() {
        for ((w, h) in listOf(3840 to 2160, 1080 to 1920, 320 to 180, 301 to 191)) {
            for (ratio in listOf(16f / 9f, 4f / 3f, 21f / 9f)) {
                val (actualW, actualH) = videoPresentationSize(w, h, VideoPresentation(4, ratio, 2f), null)
                assertTrue(actualW <= w && actualH <= h)
                assertTrue(actualW == w || actualH == h)
                assertEquals(ratio, actualW.toFloat() / actualH, 0.02f)
            }
        }
    }

    @Test fun autoRetainsSourceFitCropStretchAndZoom() {
        assertEquals(1440 to 1080, videoPresentationSize(1920, 1080, VideoPresentation(), 4f / 3f))
        assertEquals(1920 to 1440, videoPresentationSize(1920, 1080, VideoPresentation(4), 4f / 3f))
        assertEquals(1920 to 1080, videoPresentationSize(1920, 1080, VideoPresentation(3), 4f / 3f))
        assertEquals(2160 to 1620, videoPresentationSize(1920, 1080, VideoPresentation(zoom = 1.5f), 4f / 3f))
    }

    @Test fun autoRestoresSourceGeometryAfterFixedFormat() {
        val fixed = VideoPresentation(4, 21f / 9f, 1.5f).normalized()
        assertEquals(1920 to 823, videoPresentationSize(1920, 1080, fixed, 4f / 3f))
        assertEquals(1440 to 1080, videoPresentationSize(1920, 1080, fixed.copy(aspectRatio = null), 4f / 3f))
    }

    @Test fun missingAndInvalidDimensionsAreSafe() {
        assertEquals(0 to 0, videoPresentationSize(0, 1080, VideoPresentation(), null))
        assertEquals(1920 to 1080, videoPresentationSize(1920, 1080, VideoPresentation(aspectRatio = Float.NaN, zoom = Float.NaN), Float.NaN))
    }

    private fun size(ratio: Float) = videoPresentationSize(1920, 1080, VideoPresentation(aspectRatio = ratio), 4f / 3f)
}
