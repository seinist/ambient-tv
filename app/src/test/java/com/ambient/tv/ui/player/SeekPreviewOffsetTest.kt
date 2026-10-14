package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekPreviewOffsetTest {
    @Test fun centersOnThePlayheadOnceBothEdgesFit() {
        assertEquals(400f, seekPreviewOffset(1000f, 200f, 0.5f))
        assertEquals(50f, seekPreviewOffset(1000f, 200f, 0.15f))
        assertEquals(750f, seekPreviewOffset(1000f, 200f, 0.85f))
    }

    @Test fun staysPinnedToTheStartUntilCenteringWouldOverflow() {
        assertEquals(0f, seekPreviewOffset(1000f, 200f, 0f))
        assertEquals(0f, seekPreviewOffset(1000f, 200f, 0.05f))
        assertEquals(0f, seekPreviewOffset(1000f, 200f, 0.1f))
    }

    @Test fun pinsToTheEndInsteadOfCrossingTheTrack() {
        assertEquals(800f, seekPreviewOffset(1000f, 200f, 0.9f))
        assertEquals(800f, seekPreviewOffset(1000f, 200f, 0.95f))
        assertEquals(800f, seekPreviewOffset(1000f, 200f, 1f))
    }

    @Test fun widerPreviewThanTrackStaysAtTheStart() {
        assertEquals(0f, seekPreviewOffset(100f, 200f, 0.5f))
        assertEquals(0f, seekPreviewOffset(0f, 200f, 0.5f))
    }

    @Test fun outOfRangeProgressStillStaysOnTheTrack() {
        assertEquals(0f, seekPreviewOffset(1000f, 200f, -1f))
        assertEquals(800f, seekPreviewOffset(1000f, 200f, 2f))
        assertEquals(0f, seekPreviewOffset(1000f, 200f, Float.NaN))
    }
}
