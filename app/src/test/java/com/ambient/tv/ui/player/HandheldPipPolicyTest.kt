package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandheldPipPolicyTest {
    private fun eligible(
        handheld: Boolean = true,
        supported: Boolean = true,
        enabled: Boolean = true,
        active: Boolean = true,
        hasVideo: Boolean = true,
        wantsToPlay: Boolean = true,
        isTrailer: Boolean = false,
    ) = handheldPipEligible(handheld, supported, enabled, active, hasVideo, wantsToPlay, isTrailer)

    @Test fun onlyActiveHandheldVideosCanAutoEnter() {
        assertTrue(eligible())
        assertFalse(eligible(handheld = false))
        assertFalse(eligible(supported = false))
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(active = false))
        assertFalse(eligible(hasVideo = false))
        assertFalse(eligible(wantsToPlay = false))
        assertFalse(eligible(isTrailer = true))
    }

    @Test fun aspectRatioUsesSourceAndClampsExtremeValues() {
        assertEquals(16f / 9f, handheldPipAspectRatio(null, null, null), 0.0001f)
        assertEquals(9f / 16f, handheldPipAspectRatio(9, 16, null), 0.0001f)
        assertEquals(4f / 3f, handheldPipAspectRatio(9, 16, 4f / 3f), 0.0001f)
        assertEquals(2.39f, handheldPipAspectRatio(null, null, 4.5f), 0.0001f)
        assertEquals(1f / 2.39f, handheldPipAspectRatio(1, 8, null), 0.0001f)
        assertEquals(16f / 9f, handheldPipAspectRatio(null, null, Float.NaN), 0.0001f)
    }
}
