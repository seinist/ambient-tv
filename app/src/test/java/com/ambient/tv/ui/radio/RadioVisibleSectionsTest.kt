package com.ambient.tv.ui.radio

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioVisibleSectionsTest {
    @Test fun enabledSectionsAreOrderedLikeSidebarAndSmartTubeChips() {
        assertEquals(
            listOf(RadioSection.POPULAR, RadioSection.GENRES, RadioSection.LANGUAGES),
            radioVisibleSections(listOf("languages", "genres", "popular")),
        )
    }

    @Test fun disabledSectionsNeverAppearInChipsOrSwipeTabs() {
        assertEquals(listOf(RadioSection.FAVORITES), radioVisibleSections(listOf("favorites", "invalid")))
    }

    @Test fun noEnabledCategoriesUsesSameFallbackAsSidebar() {
        assertEquals(listOf(RadioSection.POPULAR), radioVisibleSections(emptyList()))
    }
}
