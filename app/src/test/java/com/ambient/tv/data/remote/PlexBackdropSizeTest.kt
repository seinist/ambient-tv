package com.ambient.tv.data.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class PlexBackdropSizeTest {
    @Test
    fun `backdrop follows the UI window`() {
        assertEquals(1920 to 1080, plexBackdropSize(1920))
        assertEquals(1280 to 720, plexBackdropSize(1280))
        assertEquals(2560 to 1440, plexBackdropSize(2400))
        assertEquals(3840 to 2160, plexBackdropSize(3840))
        assertEquals(3840 to 2160, plexBackdropSize(3120))
        assertEquals(1920 to 1080, plexBackdropSize(0))
    }
}
