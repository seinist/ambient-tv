package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3UpscalingTest {
    @Test fun onlyEnlargementNeedsLanczos() {
        assertFalse(media3NeedsUpscaling(1920, 1080, 1920, 1080))
        assertFalse(media3NeedsUpscaling(3840, 2160, 1920, 1080))
        assertFalse(media3NeedsUpscaling(1920, 800, 1920, 1080))
        assertTrue(media3NeedsUpscaling(1280, 720, 1920, 1080))
    }

    @Test fun correctedPixelAspectAndRotationAreUsed() {
        assertFalse(media3NeedsUpscaling(1440, 1080, 1920, 1080, 4f / 3f))
        assertFalse(media3NeedsUpscaling(1080, 1920, 1920, 1080, rotationDegrees = 90))
        assertFalse(media3NeedsUpscaling(0, 0, 1920, 1080))
    }
}
