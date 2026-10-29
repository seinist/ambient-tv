@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.*
import org.junit.Test

class PlayerPinchZoomGestureTest {
    @Test fun landscapeVideoSnapsExactlyToBothPhoneEdges() {
        val edges = requireNotNull(playerZoomEdges(2400, 1080, 16f / 9f))
        assertEquals(1.25f, edges.width, 0.0001f)
        assertEquals(1f, edges.height, 0.0001f)
        val pinch = PlayerPinchZoomGesture(edges, VideoPresentation())
        val width = pinch.scaleBy(1.23f)
        assertEquals(2400 to 1350, videoPresentationSize(2400, 1080, width, 16f / 9f))
        val height = pinch.scaleBy(1f / 1.23f)
        assertEquals(1920 to 1080, videoPresentationSize(2400, 1080, height, 16f / 9f))
    }

    @Test fun portraitFillHeightExceedsTheOldTwoTimesLimit() {
        val edges = requireNotNull(playerZoomEdges(1080, 2400, 21f / 9f))
        assertEquals(1f, edges.width, 0.0001f)
        assertEquals(140f / 27f, edges.height, 0.0001f)
        val pinch = PlayerPinchZoomGesture(edges, VideoPresentation())
        val height = pinch.scaleBy(edges.height * 0.99f)
        assertEquals(5600 to 2400, videoPresentationSize(1080, 2400, height, 21f / 9f))
    }

    @Test fun smallRepeatedMovementsCanLeaveASnapInEitherDirection() {
        val edges = requireNotNull(playerZoomEdges(2400, 1080, 16f / 9f))
        val outward = PlayerPinchZoomGesture(edges, VideoPresentation())
        repeat(5) { assertEquals(1f, outward.scaleBy(1.01f).zoom, 0f) }
        repeat(4) { outward.scaleBy(1.01f) }
        assertTrue(outward.zoom > 1.06f)
        val inward = PlayerPinchZoomGesture(edges, VideoPresentation())
        repeat(4) { assertEquals(1f, inward.scaleBy(0.99f).zoom, 0f) }
        repeat(5) { inward.scaleBy(0.99f) }
        assertTrue(inward.zoom < 0.94f)
    }

    @Test fun crossingAnEdgeDoesNotSkipItsSnapOnAFastPinch() {
        val edges = requireNotNull(playerZoomEdges(2400, 1080, 16f / 9f))
        val pinch = PlayerPinchZoomGesture(edges, VideoPresentation(zoom = 1.1f))
        assertEquals(1.25f, pinch.scaleBy(1.3f).zoom, 0f)
        assertEquals(1.25f, pinch.scaleBy(1f).zoom, 0f)
        assertEquals(1.25f, pinch.scaleBy(1.02f).zoom, 0f)
        assertTrue(pinch.scaleBy(1.1f).zoom > 1.25f)
    }

    @Test fun closeEdgesCannotMaskOneAnother() {
        val edges = PlayerZoomEdges(1f, 1.04f)
        val pinch = PlayerPinchZoomGesture(edges, VideoPresentation())
        assertEquals(1.04f, pinch.scaleBy(1.035f).zoom, 0f)
        assertEquals(1f, pinch.scaleBy(1f / 1.035f).zoom, 0f)
    }

    @Test fun snapHysteresisHoldsMinorJitterAndReleasesOnDeliberateMovement() {
        val edges = requireNotNull(playerZoomEdges(2400, 1080, 16f / 9f))
        val pinch = PlayerPinchZoomGesture(edges, VideoPresentation(zoom = 1.2f))
        assertEquals(1.25f, pinch.scaleBy(1.03f).zoom, 0f)
        assertEquals(1.25f, pinch.scaleBy(1.03f).zoom, 0f)
        assertEquals(1.25f, pinch.scaleBy(0.99f).zoom, 0f)
        assertTrue(pinch.scaleBy(1.1f).zoom > 1.25f)
    }

    @Test fun startingFromFillPreservesTheDisplayedPictureSize() {
        val edges = requireNotNull(playerZoomEdges(2400, 1080, 16f / 9f))
        val fill = VideoPresentation(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, zoom = 1.5f)
        val pinch = PlayerPinchZoomGesture(edges, fill)
        val result = pinch.scaleBy(1f)
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, result.resizeMode)
        assertEquals(videoPresentationSize(2400, 1080, fill, 16f / 9f),
            videoPresentationSize(2400, 1080, result, 16f / 9f))
    }

    @Test fun deliberatePinchLeavesFixedAspectPresetButKeepsSourceAspect() {
        val edges = requireNotNull(playerZoomEdges(2400, 1080, 16f / 9f))
        val pinch = PlayerPinchZoomGesture(edges, VideoPresentation(aspectRatio = 4f / 3f))
        val result = pinch.scaleBy(1.1f)
        assertNull(result.aspectRatio)
        assertEquals(1.1f, result.zoom, 0.0001f)
        val size = videoPresentationSize(2400, 1080, result, 16f / 9f)
        assertEquals(16f / 9f, size.first.toFloat() / size.second, 0.001f)
    }

    @Test fun reachingALimitDoesNotAccumulateInvisibleExtraZoom() {
        val edges = requireNotNull(playerZoomEdges(1920, 1080, 16f / 9f))
        val pinch = PlayerPinchZoomGesture(edges, VideoPresentation(zoom = 2f))
        assertEquals(VIDEO_MAX_ZOOM, pinch.scaleBy(100f).zoom, 0f)
        assertEquals(VIDEO_MAX_ZOOM * 0.9f, pinch.scaleBy(0.9f).zoom, 0.001f)
        // The first fast contraction crosses the 100% edge; continuing releases it.
        assertEquals(1f, pinch.scaleBy(0.001f).zoom, 0f)
        assertEquals(VIDEO_MIN_ZOOM, pinch.scaleBy(0.5f).zoom, 0f)
        assertEquals(VIDEO_MIN_ZOOM * 1.1f, pinch.scaleBy(1.1f).zoom, 0.001f)
    }

    @Test fun invalidGeometryAndPinchFactorsCannotCorruptThePicture() {
        assertNull(playerZoomEdges(0, 1080, 1f))
        assertNull(playerZoomEdges(1920, -1, 1f))
        for (ratio in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(playerZoomEdges(1920, 1080, ratio))
        }
        val pinch = PlayerPinchZoomGesture(PlayerZoomEdges(1f, 1f), VideoPresentation(zoom = 1.5f))
        for (factor in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1.5f, pinch.scaleBy(factor).zoom, 0f)
        }
    }

    @Test fun sameAspectHasOneStableSnapAndEdgesFollowNewViewport() {
        val landscape = requireNotNull(playerZoomEdges(1920, 1080, 16f / 9f))
        assertEquals(landscape.width, landscape.height, 0f)
        val portrait = requireNotNull(playerZoomEdges(1080, 1920, 16f / 9f))
        assertEquals(1f, portrait.width, 0f)
        assertTrue(portrait.height > 3f)
    }
}
