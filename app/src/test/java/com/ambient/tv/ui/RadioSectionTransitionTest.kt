package com.ambient.tv.ui

import com.ambient.tv.ui.navigation.Destinations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSectionTransitionTest {
    @Test fun categoryChangesAreAtomicEvenOnPhones() {
        assertTrue(radioSectionTransitionIsInstant(Destinations.RADIO, Destinations.RADIO_SECTION))
        assertTrue(radioSectionTransitionIsInstant(Destinations.RADIO_SECTION, Destinations.RADIO))
        // Genres -> Countries -> Languages share one NavHost route pattern.
        assertTrue(radioSectionTransitionIsInstant(Destinations.RADIO_SECTION, Destinations.RADIO_SECTION))
    }

    @Test fun transitionsToOtherSystemsAndThePlayerKeepTheirExistingAnimations() {
        assertFalse(radioSectionTransitionIsInstant(Destinations.HOME, Destinations.RADIO))
        assertFalse(radioSectionTransitionIsInstant(Destinations.YOUTUBE, Destinations.RADIO_SECTION))
        assertFalse(radioSectionTransitionIsInstant(Destinations.RADIO, Destinations.RADIO_PLAYER))
        assertFalse(radioSectionTransitionIsInstant(Destinations.RADIO_PLAYER, Destinations.RADIO_SECTION))
        assertFalse(radioSectionTransitionIsInstant(null, Destinations.RADIO))
    }
}
