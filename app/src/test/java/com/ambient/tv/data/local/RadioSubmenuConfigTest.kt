package com.ambient.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioSubmenuConfigTest {
    @Test
    fun defaultRadioSubmenuContainsEveryBrowseSectionInOrder() {
        val expected = listOf("popular", "favorites", "recent", "genres", "countries", "languages")
        assertEquals(expected, AppPreferences.DEFAULT_RADIO_SUBMENU_KEYS)
        assertEquals(expected.toSet(), AppPreferences.RADIO_SUBMENU_KEYS)
    }
}
