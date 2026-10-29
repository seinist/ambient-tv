package com.ambient.tv.ui.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioNowPlayingTest {
    @Test fun trimsDynamicTitle() {
        assertEquals("Artist - Track", radioNowPlayingTitle("  Artist - Track  ", "Station"))
    }

    @Test fun ignoresBlankAndStationName() {
        assertNull(radioNowPlayingTitle("  ", "Station"))
        assertNull(radioNowPlayingTitle("station", "Station"))
    }
}
