package com.ambient.tv.notifications

import com.ambient.tv.ui.player.SupportedDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayChangeTrackerTest {
    private val desktop = SupportedDisplayMode("3840x2160@60.000", 1, 3840, 2160, 60f)
    private val film = SupportedDisplayMode("1920x1080@23.976", 2, 1920, 1080, 23.976f)

    @Test fun initialAndDuplicateCallbacksAreSilent() {
        val tracker = DisplayChangeTracker(null)
        assertNull(tracker.update(desktop, true))
        assertNull(tracker.update(desktop.copy(modeId = 3), true))
    }

    @Test fun combinedChangeAndRestorationEachProduceOneNotice() {
        val tracker = DisplayChangeTracker(desktop)
        assertEquals(film, tracker.update(film, true))
        assertNull(tracker.update(film, true))
        assertEquals(desktop, tracker.update(desktop, true))
    }

    @Test fun disabledChangesDoNotGetReplayedWhenEnabled() {
        val tracker = DisplayChangeTracker(desktop)
        assertNull(tracker.update(film, false))
        assertNull(tracker.update(film, true))
        assertEquals(desktop, tracker.update(desktop, true))
    }

    @Test fun refreshOnlyAndResolutionOnlyChangesAreDetected() {
        val tracker = DisplayChangeTracker(desktop)
        val rate = desktop.copy(key = "3840x2160@23.976", refreshRate = 23.976f)
        val size = rate.copy(key = film.key, width = 1920, height = 1080)
        assertEquals(rate, tracker.update(rate, true))
        assertNull(tracker.update(null, true))
        assertEquals(size, tracker.update(size, true))
    }
}
