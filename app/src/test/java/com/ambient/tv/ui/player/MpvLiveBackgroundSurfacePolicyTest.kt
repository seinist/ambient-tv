package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvLiveBackgroundSurfacePolicyTest {
    @Test fun onlyActiveLiveMpvVideoKeepsItsOutputAcrossBrowse() {
        assertTrue(shouldRetainMpvLiveVideoSurface(
            activeVideo = true, isLive = true, mpvActive = true,
        ))
        assertFalse(shouldRetainMpvLiveVideoSurface(
            activeVideo = true, isLive = false, mpvActive = true,
        ))
        assertFalse(shouldRetainMpvLiveVideoSurface(
            activeVideo = true, isLive = true, mpvActive = false,
        ))
        assertFalse(shouldRetainMpvLiveVideoSurface(
            activeVideo = false, isLive = true, mpvActive = true,
        ))
    }
}
