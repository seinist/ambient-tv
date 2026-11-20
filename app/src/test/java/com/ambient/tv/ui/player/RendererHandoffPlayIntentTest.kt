package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererHandoffPlayIntentTest {
    @Test fun `decoder failure does not turn an intended Play into Pause on same-item fallback`() {
        assertTrue(rendererHandoffPlayIntent(
            samePlayback = true, failed = true, readyToPlay = false, requestedToPlay = true))
    }

    @Test fun `explicit Pause stays paused even if failed renderer is replaced`() {
        assertFalse(rendererHandoffPlayIntent(
            samePlayback = true, failed = true, readyToPlay = false, requestedToPlay = false))
    }

    @Test fun `healthy handoff preserves normal player readiness`() {
        assertFalse(rendererHandoffPlayIntent(
            samePlayback = true, failed = false, readyToPlay = false, requestedToPlay = true))
        assertTrue(rendererHandoffPlayIntent(
            samePlayback = true, failed = false, readyToPlay = true, requestedToPlay = false))
    }

    @Test fun `different item does not inherit errored decoder play request`() {
        assertFalse(rendererHandoffPlayIntent(
            samePlayback = false, failed = true, readyToPlay = false, requestedToPlay = true))
    }
}
