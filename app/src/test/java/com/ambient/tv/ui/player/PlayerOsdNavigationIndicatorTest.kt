package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOsdNavigationIndicatorTest {
    private fun mode(
        paused: Boolean = false,
        chapters: Boolean = false,
        directSeekFeedback: Boolean = false,
        expanded: Boolean = false,
        seekFocused: Boolean = false,
        buttonFocused: Boolean = false,
    ) = playerOsdNavigationMode(paused, chapters, directSeekFeedback, expanded, seekFocused, buttonFocused)

    @Test fun pauseAlwaysOverridesNavigation() {
        assertEquals(PlayerOsdNavigationMode.PAUSE, mode(paused = true, chapters = true, expanded = true))
        assertEquals(PlayerOsdNavigationMode.PAUSE, mode(paused = true, seekFocused = true))
        assertEquals(PlayerOsdNavigationMode.PAUSE, mode(paused = true, directSeekFeedback = true))
    }

    @Test fun chapterNavigationOverridesExpandedButtons() {
        assertEquals(PlayerOsdNavigationMode.CHAPTERS, mode(chapters = true, expanded = true))
        assertEquals(PlayerOsdNavigationMode.CHAPTERS, mode(chapters = true, seekFocused = true))
        assertEquals(PlayerOsdNavigationMode.CHAPTERS, mode(chapters = true, directSeekFeedback = true))
    }

    @Test fun passiveSeekFeedbackOverridesEvenStaleTimelineFocus() {
        assertEquals(PlayerOsdNavigationMode.DIRECT_SEEK, mode(directSeekFeedback = true))
        assertEquals(PlayerOsdNavigationMode.DIRECT_SEEK, mode(directSeekFeedback = true, seekFocused = true))
        assertEquals(PlayerOsdNavigationMode.DIRECT_SEEK, mode(directSeekFeedback = true, buttonFocused = true))
    }

    @Test fun compactAndTimelineFocusUseTimelineIcon() {
        assertEquals(PlayerOsdNavigationMode.TIMELINE, mode())
        assertEquals(PlayerOsdNavigationMode.TIMELINE, mode(seekFocused = true))
        // Focus has moved to the timeline even if the expanded layout has not collapsed yet.
        assertEquals(PlayerOsdNavigationMode.TIMELINE, mode(expanded = true, seekFocused = true))
    }

    @Test fun expandedControlsAndButtonFocusUseButtonIcon() {
        assertEquals(PlayerOsdNavigationMode.BUTTONS, mode(expanded = true))
        assertEquals(PlayerOsdNavigationMode.BUTTONS, mode(buttonFocused = true))
    }
}
