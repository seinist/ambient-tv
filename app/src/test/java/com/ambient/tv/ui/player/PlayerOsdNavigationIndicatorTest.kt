package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOsdNavigationIndicatorTest {
    private fun mode(
        paused: Boolean = false,
        chapters: Boolean = false,
        expanded: Boolean = false,
        seekFocused: Boolean = false,
        buttonFocused: Boolean = false,
    ) = playerOsdNavigationMode(paused, chapters, expanded, seekFocused, buttonFocused)

    @Test fun pauseAlwaysOverridesNavigation() {
        assertEquals(PlayerOsdNavigationMode.PAUSE, mode(paused = true, chapters = true, expanded = true))
        assertEquals(PlayerOsdNavigationMode.PAUSE, mode(paused = true, seekFocused = true))
    }

    @Test fun chapterNavigationOverridesExpandedButtons() {
        assertEquals(PlayerOsdNavigationMode.CHAPTERS, mode(chapters = true, expanded = true))
        assertEquals(PlayerOsdNavigationMode.CHAPTERS, mode(chapters = true, seekFocused = true))
    }

    @Test fun compactAndTimelineFocusUseSeekIcon() {
        assertEquals(PlayerOsdNavigationMode.SEEK, mode())
        assertEquals(PlayerOsdNavigationMode.SEEK, mode(expanded = true, seekFocused = true))
    }

    @Test fun expandedControlsAndButtonFocusUseButtonIcon() {
        assertEquals(PlayerOsdNavigationMode.BUTTONS, mode(expanded = true))
        assertEquals(PlayerOsdNavigationMode.BUTTONS, mode(buttonFocused = true))
    }
}
