package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackVideoWindowTest {
    @Test fun windowFromTheHbbtvPage() {
        assertEquals(VideoWindowFraction(0.6f, 0.05f, 0.35f, 0.35f), parseVideoWindow("0.6000,0.0500,0.3500,0.3500"))
    }

    @Test fun fullScreenAndNonsenseAreFullScreen() {
        assertNull(parseVideoWindow("full"))
        assertNull(parseVideoWindow("0,0,1,1"))
        assertNull(parseVideoWindow("0.1,0.1,0.01,0.5"))
        assertNull(parseVideoWindow("NaN,0,0.5,0.5"))
    }

    @Test fun windowIsClampedToTheScreen() {
        assertEquals(VideoWindowFraction(0.75f, 0f, 0.25f, 0.5f), parseVideoWindow("0.75,-0.2,0.5,0.5"))
    }
}
