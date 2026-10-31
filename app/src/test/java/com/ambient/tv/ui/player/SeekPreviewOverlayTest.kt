package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekPreviewOverlayTest {
    @Test fun followsPlayheadAndClampsToScreenInsteadOfTrack() {
        assertEquals(400f, seekPreviewScreenX(60f, 880f, 0f, 1000f, 200f, .5f, 12f))
        assertEquals(12f, seekPreviewScreenX(60f, 880f, 0f, 1000f, 200f, 0f, 12f))
        assertEquals(788f, seekPreviewScreenX(60f, 880f, 0f, 1000f, 200f, 1f, 12f))
    }

    @Test fun accountsForViewportOriginOnHandheld() {
        assertEquals(104f, seekPreviewScreenX(130f, 300f, 100f, 360f, 152f, .5f, 12f))
        assertEquals(12f, seekPreviewScreenX(100f, 300f, 100f, 360f, 152f, Float.NaN, 12f))
    }

    @Test fun bubbleFloatsAboveTimelineWithoutMovingLayout() {
        assertEquals(368f, seekPreviewScreenY(500f, 0f, 700f, 120f, 12f, 12f))
        assertEquals(12f, seekPreviewScreenY(90f, 0f, 500f, 120f, 12f, 12f))
        assertEquals(128f, seekPreviewScreenY(320f, 100f, 400f, 80f, 12f, 12f))
    }

    @Test fun previewAndHeaderOnlyActivateWhenTimelineHasImageryOrChapters() {
        val state = SeekPreviewOverlayState()
        state.update(12_000L, 60_000L, hasPreview = false)
        assertNull(state.positionMs)
        assertFalse(state.showing)

        state.update(12_000L, 60_000L, hasPreview = true)
        assertEquals(12_000L, state.positionMs)
        assertTrue(state.showing)

        state.update(null, 60_000L, hasPreview = true)
        assertNull(state.positionMs)
        assertFalse(state.showing)

        state.update(15_000L, 0L, hasPreview = true)
        assertFalse(state.showing)
    }
}
