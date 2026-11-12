package com.ambient.tv.ui.common

import com.ambient.tv.ui.navigation.Destinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InPlaceSectionSelectionTest {
    @Test fun rootRoutesMeanTheDefaultSection() {
        assertEquals("recent", telegramSectionKeyOfRoute(Destinations.TELEGRAM))
        assertEquals("channel:42", telegramSectionKeyOfRoute("telegram_section/channel%3A42"))
        assertNull(telegramSectionKeyOfRoute(Destinations.RADIO))
        assertEquals("popular", radioSectionKeyOfRoute(Destinations.RADIO))
        assertEquals("favorites", radioSectionKeyOfRoute("radio_section/favorites"))
        assertNull(radioSectionKeyOfRoute(Destinations.HOME))
    }

    @Test fun switchesInPlaceOnlyOnTheLiveScreenAndForAnotherSection() {
        assertTrue(sectionSwitchesInPlace(Destinations.TELEGRAM, TELEGRAM_SECTION_ROUTES, "recent", "videos"))
        assertTrue(sectionSwitchesInPlace(Destinations.RADIO_SECTION, RADIO_SECTION_ROUTES, "favorites", "popular"))
        assertFalse(sectionSwitchesInPlace(Destinations.TELEGRAM, TELEGRAM_SECTION_ROUTES, "recent", "recent"))
        assertFalse(sectionSwitchesInPlace(Destinations.HOME, TELEGRAM_SECTION_ROUTES, "recent", "videos"))
        assertFalse(sectionSwitchesInPlace(Destinations.RADIO, RADIO_SECTION_ROUTES, null, "favorites"))
    }

    @Test fun onlyTheOwnerClearsItsSection() {
        val selection = InPlaceSectionSelection()
        val first = Any()
        val second = Any()
        selection.show(first, "recent")
        selection.show(second, "videos")
        selection.hide(first)
        assertEquals("videos", selection.displayed.value?.second)
        selection.hide(second)
        assertNull(selection.displayed.value)
        selection.request("audio")
        selection.request("audio")
        assertEquals(2L, selection.requests.value?.serial)
        assertEquals("audio", selection.requests.value?.key)
    }
}
