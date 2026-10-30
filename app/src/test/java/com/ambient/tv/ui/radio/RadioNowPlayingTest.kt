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
    @Test fun addsExplicitArtistWithoutDuplicatingStreamTitle() {
        assertEquals("Artist - Track", radioMetadataNowPlaying("Track", "Artist", "Station"))
        assertEquals("Artist - Track", radioMetadataNowPlaying("Artist - Track", "Artist", "Station"))
        assertEquals("Track", radioMetadataNowPlaying("Track", null, "Station"))
        assertNull(radioMetadataNowPlaying("Station", "Artist", "Station"))
    }

    @Test fun reconnectUsesBoundedBackoff() {
        assertEquals(1_000L, radioReconnectDelayMs(1))
        assertEquals(2_500L, radioReconnectDelayMs(2))
        assertEquals(5_000L, radioReconnectDelayMs(3))
        assertEquals(5_000L, radioReconnectDelayMs(99))
    }

    @Test fun reconnectOnlyBelongsToTheCurrentRadioGeneration() {
        assertEquals(true, shouldReconnectRadio("radio", "one", "one", "one", 7, 7, 0))
        assertEquals(false, shouldReconnectRadio("radio", "old", "one", "one", 7, 7, 0))
        assertEquals(false, shouldReconnectRadio("radio", "one", "one", "two", 7, 7, 0))
        assertEquals(false, shouldReconnectRadio("plex", "one", "one", "one", 7, 7, 0))
        // A late error from the replaced Media3 instance of the same station is ignored.
        assertEquals(false, shouldReconnectRadio("radio", "one", "one", "one", 8, 7, 0))
        assertEquals(false, shouldReconnectRadio("radio", "one", "one", "one", 7, 7, MAX_RADIO_RECONNECT_ATTEMPTS))
    }

}
