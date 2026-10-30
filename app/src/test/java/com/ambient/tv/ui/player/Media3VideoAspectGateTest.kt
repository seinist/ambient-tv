package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3VideoAspectGateTest {
    @Test fun decoderNegotiationDoesNotJumpThePictureBeforeTheFirstFrame() {
        val gate = Media3VideoAspectGate()
        gate.configure(forcedAspectRatio = null, sourceAspectRatioHint = 16f / 9f)
        for (candidate in listOf(1f, 4f / 3f, 16f / 9f, 21f / 9f)) {
            gate.onDecoderAspectRatio(candidate)
            assertEquals(16f / 9f, gate.displayedAspectRatio(), 0.0001f)
            assertFalse(gate.hasRenderedFirstFrame)
        }
        // The rendered frame, not any earlier speculative dimensions, selects the first real DAR.
        gate.onFirstFrameRendered(21f / 9f)
        assertTrue(gate.hasRenderedFirstFrame)
        assertEquals(21f / 9f, gate.displayedAspectRatio(), 0.0001f)
    }

    @Test fun noMetadataHintKeepsAnUnconstrainedBlackStartupSurface() {
        val gate = Media3VideoAspectGate()
        gate.configure(null, null)
        gate.onDecoderAspectRatio(9f / 16f)
        assertEquals(0f, gate.displayedAspectRatio(), 0f)
        gate.onFirstFrameRendered()
        assertEquals(9f / 16f, gate.displayedAspectRatio(), 0.0001f)
    }

    @Test fun userSelectedAspectOverridesDecoderImmediatelyAndCanBeCleared() {
        val gate = Media3VideoAspectGate()
        gate.configure(4f / 3f, 16f / 9f)
        gate.onDecoderAspectRatio(21f / 9f)
        gate.onFirstFrameRendered()
        assertTrue(gate.hasForcedAspect)
        assertEquals(4f / 3f, gate.displayedAspectRatio(), 0.0001f)
        gate.configure(null, 16f / 9f)
        assertFalse(gate.hasForcedAspect)
        assertEquals(21f / 9f, gate.displayedAspectRatio(), 0.0001f)
    }

    @Test fun invalidSizesCannotOverrideKnownGoodGeometry() {
        val gate = Media3VideoAspectGate()
        gate.configure(null, 16f / 9f)
        gate.onDecoderAspectRatio(21f / 9f)
        gate.onDecoderAspectRatio(0f)
        gate.onDecoderAspectRatio(Float.NaN)
        gate.onDecoderAspectRatio(Float.POSITIVE_INFINITY)
        gate.onFirstFrameRendered()
        assertEquals(21f / 9f, gate.displayedAspectRatio(), 0.0001f)
        gate.configure(null, Float.NaN)
        assertEquals(21f / 9f, gate.displayedAspectRatio(), 0.0001f)
    }

    @Test fun realFormatChangesStillWorkAfterStartup() {
        val gate = Media3VideoAspectGate()
        gate.configure(null, 16f / 9f)
        gate.onDecoderAspectRatio(16f / 9f)
        gate.onFirstFrameRendered()
        gate.onDecoderAspectRatio(4f / 3f)
        assertEquals(4f / 3f, gate.displayedAspectRatio(), 0.0001f)
        gate.resetDecoder()
        assertFalse(gate.hasRenderedFirstFrame)
        assertEquals(16f / 9f, gate.displayedAspectRatio(), 0.0001f)
    }
}
