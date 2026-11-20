package com.ambient.tv.ui

import com.ambient.tv.ui.navigation.Destinations
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioSectionTransitionTest {
    @Test fun detailsCanUseOptedInMotionButActualPlayersStayUnanimated() {
        for (route in listOf(Destinations.DETAIL, Destinations.DETAIL_FILTERED_BROWSE,
            Destinations.YOUTUBE_DETAIL, Destinations.YOUTUBE_CHANNEL,
            Destinations.TELEGRAM_DETAIL, Destinations.RADIO_DETAIL, Destinations.SPOTIFY_DETAIL)) {
            assertFalse("Detail route should be eligible for page motion: $route",
                route in NO_VIEW_TRANSITION_ROUTES)
        }
        for (route in listOf(Destinations.PLAYER, Destinations.TVH_PLAYER,
            Destinations.YOUTUBE_PLAYER, Destinations.RADIO_PLAYER,
            Destinations.PHOTO_VIEWER, Destinations.WEATHER_PLAYER)) {
            assertTrue("Video route must remain atomic: $route",
                route in NO_VIEW_TRANSITION_ROUTES)
        }
    }

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
