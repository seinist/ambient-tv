package com.ambient.tv.ui.player

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.*
import org.junit.Test

class PlayerControlFocusTest {
    @Test fun closingOsdResetsBothTargetsWithoutRemovingMountedControls() {
        val focus = PlayerControlFocus()
        val play = FocusRequester()
        val settings = FocusRequester()
        val timeline = FocusRequester()
        val settingsOwner = Any()
        val timelineOwner = Any()
        focus.register("settings", settings, settingsOwner)
        focus.register("seek-bar", timeline, timelineOwner)
        focus.focused("settings", settingsOwner, true)
        focus.focused("seek-bar", timelineOwner, true)

        // Buttons may remain composed during the exit fade or a quick reopening.
        focus.resetForNextOpen()
        assertFalse(focus.hasFocus)
        assertNull(focus.lastKey)
        assertSame(play, focus.target(play))
        assertSame(play, focus.buttonTarget(play))
        assertSame(timeline, focus.seekTarget())

        // Normal navigation resumes with the existing registrations after reopening.
        focus.focused("settings", settingsOwner, true)
        assertTrue(focus.hasFocus)
        assertSame(settings, focus.target(play))
        assertSame(settings, focus.buttonTarget(play))
    }

    @Test fun timelineReturnsToLastButtonAndFallsBackAfterItsRemoval() {
        val focus = PlayerControlFocus()
        val fallback = FocusRequester()
        val button = FocusRequester()
        val timeline = FocusRequester()
        val buttonOwner = Any()
        val timelineOwner = Any()
        focus.register("settings", button, buttonOwner)
        focus.register("seek-bar", timeline, timelineOwner)
        focus.focused("settings", buttonOwner, true)
        focus.focused("seek-bar", timelineOwner, true)
        assertSame(timeline, focus.target(fallback))
        assertSame(timeline, focus.seekTarget())
        assertSame(button, focus.buttonTarget(fallback))
        focus.unregister("settings", buttonOwner)
        assertSame(fallback, focus.buttonTarget(fallback))
        focus.unregister("seek-bar", timelineOwner)
        assertNull(focus.seekTarget())
    }

    @Test fun channelReplacementRestoresTheSameButtonForBothZapDirections() {
        for (key in listOf("previous", "next", "audio", "settings")) {
            val focus = PlayerControlFocus()
            val oldOwner = Any()
            focus.register(key, FocusRequester(), oldOwner)
            focus.focused(key, oldOwner, true)
            focus.unregister(key, oldOwner)
            assertFalse(focus.hasFocus)
            val replacement = FocusRequester()
            focus.register(key, replacement, Any())
            assertSame(replacement, focus.target(FocusRequester()))
        }
    }

    @Test fun lateLossFromOutgoingButtonDoesNotClearTheNewFocus() {
        val focus = PlayerControlFocus()
        val previousOwner = Any()
        val nextOwner = Any()
        val next = FocusRequester()
        focus.register("previous", FocusRequester(), previousOwner)
        focus.register("next", next, nextOwner)
        focus.focused("previous", previousOwner, true)
        focus.focused("next", nextOwner, true)
        focus.focused("previous", previousOwner, false)
        focus.unregister("previous", previousOwner)
        assertTrue(focus.hasFocus)
        assertSame(next, focus.target(FocusRequester()))
    }

    @Test fun missingButtonFallsBackToPlayAndStaleDisposalPreservesReplacement() {
        val focus = PlayerControlFocus()
        val oldOwner = Any()
        val fallback = FocusRequester()
        assertSame(fallback, focus.target(fallback))
        focus.register("next", FocusRequester(), oldOwner)
        focus.focused("next", oldOwner, true)
        val replacement = FocusRequester()
        val newOwner = Any()
        focus.register("next", replacement, newOwner)
        focus.focused("next", newOwner, true)
        focus.unregister("next", oldOwner)
        assertTrue(focus.hasFocus)
        assertSame(replacement, focus.target(fallback))
        focus.unregister("next", newOwner)
        assertSame(fallback, focus.target(fallback))
    }
}
