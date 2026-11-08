package com.ambient.tv.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeHeroEffectsTest {
    @Test fun prismBackgroundBeamTapersAtScreenEdges() {
        assertEquals(0f, homePrismBeamEnvelope(0f), 0.0001f)
        assertEquals(1f, homePrismBeamEnvelope(0.5f), 0.0001f)
        assertEquals(0f, homePrismBeamEnvelope(1f), 0.0001f)
        assertEquals(homePrismBeamEnvelope(0.2f), homePrismBeamEnvelope(0.8f), 0.0001f)
        assertTrue(homePrismBeamEnvelope(0.1f) < homePrismBeamEnvelope(0.25f))
    }
}
