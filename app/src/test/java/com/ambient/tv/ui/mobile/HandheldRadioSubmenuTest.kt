package com.ambient.tv.ui.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class HandheldRadioSubmenuTest {
    @Test fun configuredCategoriesAreListedOnHandheld() {
        assertEquals(
            listOf("popular", "favorites", "recent", "genres", "countries", "languages"),
            handheldRadioSubmenuKeys(listOf("popular", "favorites", "recent", "genres", "countries", "languages")),
        )
    }

    @Test fun hiddenCategoriesStayHiddenAndEmptyFallsBackToPopular() {
        assertEquals(listOf("popular", "genres"), handheldRadioSubmenuKeys(listOf("genres", "popular")))
        assertEquals(listOf("popular"), handheldRadioSubmenuKeys(emptyList()))
    }
}
