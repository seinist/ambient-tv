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
    @Test fun reconnectUsesBoundedBackoff() {
        assertEquals(1_000L, radioReconnectDelayMs(1))
        assertEquals(2_500L, radioReconnectDelayMs(2))
        assertEquals(5_000L, radioReconnectDelayMs(3))
        assertEquals(5_000L, radioReconnectDelayMs(99))
    }

    @Test fun reconnectOnlyBelongsToTheCurrentRadioStation() {
        assertEquals(true, shouldReconnectRadio("radio", "one", "one", "one", 0))
        assertEquals(false, shouldReconnectRadio("radio", "old", "one", "one", 0))
        assertEquals(false, shouldReconnectRadio("radio", "one", "one", "two", 0))
        assertEquals(false, shouldReconnectRadio("plex", "one", "one", "one", 0))
        assertEquals(false, shouldReconnectRadio("radio", "one", "one", "one", MAX_RADIO_RECONNECT_ATTEMPTS))
    }

}
