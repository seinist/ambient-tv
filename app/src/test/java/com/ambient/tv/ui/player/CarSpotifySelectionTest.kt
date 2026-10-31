package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarSpotifySelectionTest {
    @Test fun failedFirstStartRestoresBackgroundPlaybackOwner() {
        val selection = CarSpotifySelection()
        val request = selection.begin()
        assertTrue(selection.active)
        assertTrue(selection.rollbackFailed(request))
        assertFalse(selection.active)
        assertFalse(selection.rollbackFailed(request))
    }

    @Test fun failedTrackChangeKeepsPreviouslyConfirmedSpotifySession() {
        val selection = CarSpotifySelection()
        val first = selection.begin()
        selection.confirm(first)
        val nextTrack = selection.begin()
        assertTrue(selection.rollbackFailed(nextTrack))
        assertTrue(selection.active)
    }

    @Test fun newerSelectionAndStopInvalidateLateFailureAndSuccessCallbacks() {
        val selection = CarSpotifySelection()
        val first = selection.begin()
        selection.clear()
        val second = selection.begin()
        assertFalse(selection.rollbackFailed(first))
        selection.confirm(second)
        assertTrue(selection.active)
        selection.clear()
        selection.confirm(second)
        assertFalse(selection.rollbackFailed(second))
        assertFalse(selection.active)
    }

    @Test fun overlappingUnconfirmedStartsCannotRestorePhantomSpotifyPlayback() {
        val selection = CarSpotifySelection()
        val firstPending = selection.begin()
        val secondPending = selection.begin()
        assertTrue(selection.rollbackFailed(secondPending))
        assertFalse(selection.active)
        selection.confirm(firstPending)
        assertFalse(selection.active)
    }
}
