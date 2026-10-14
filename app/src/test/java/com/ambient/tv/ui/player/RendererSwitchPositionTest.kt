package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererSwitchPositionTest {
    @Test fun liveRendererAndFilterReopensNeverUseTheElapsedStreamClockAsASeek() {
        assertEquals(0L, rendererReopenPositionMs(true, 425_000L))
        assertEquals(425_000L, rendererReopenPositionMs(false, 425_000L))
        assertEquals(0L, rendererReopenPositionMs(false, -1L))
    }
    @Test fun preservesTheVisiblePositionWhenTheOutgoingDecoderTemporarilyReportsZero() {
        assertEquals(420_000L, rendererSwitchPositionMs(0L, 420_000L))
        assertEquals(420_000L, rendererSwitchPositionMs(null, 420_000L))
    }

    @Test fun prefersTheRunningDecoderClockAndAllowsAnIntentionalStartAtZero() {
        assertEquals(425_000L, rendererSwitchPositionMs(425_000L, 420_000L))
        assertEquals(0L, rendererSwitchPositionMs(0L, 0L))
    }
    @Test fun confirmedOrRequestedZeroWinsOverAStaleScreenTimestamp() {
        assertEquals(0L, rendererSwitchPositionMs(0L, 420_000L, reportedZeroReady = true))
        assertEquals(420_000L, rendererSwitchPositionMs(0L, 420_000L, reportedZeroReady = false))
        assertEquals(420_000L, rendererSwitchPositionMs(null, 420_000L, reportedZeroReady = true))
        assertEquals(425_000L, rendererSwitchPositionMs(425_000L, 420_000L, reportedZeroReady = true))
    }

}
