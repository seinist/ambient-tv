package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextUpConfirmationTimeoutTest {
    private fun waiting(
        prompt: Boolean = true,
        autoplay: Boolean = true,
        confirmation: Boolean = true,
        ended: Boolean = false,
        clockAtEnd: Boolean = false,
    ) = nextUpConfirmationNeedsTimeout(
        promptVisible = prompt,
        autoplay = autoplay,
        stillWatching = confirmation,
        atEnd = ended,
        clockReachedEnd = clockAtEnd,
    )

    @Test fun terminalStillWatchingPromptHasBoundedLifetime() {
        assertTrue(waiting(ended = true))
        assertTrue(waiting(ended = true, clockAtEnd = true))
        assertEquals(10_000L, NEXT_UP_CONFIRMATION_TIMEOUT_MS)
    }

    @Test fun nativeRendererThatMissesEofGetsSameExitPolicyAtDuration() {
        // Handles a native decoder that silently clears playback at the final timestamp.
        assertTrue(waiting(clockAtEnd = true, ended = false))
        assertEquals(2_000L, NEXT_UP_EOF_GRACE_MS)
    }

    @Test fun ongoingPlaybackMustNotExpireAConfirmationDuringCredits() {
        assertFalse(waiting())
        assertFalse(waiting(clockAtEnd = false, ended = false))
    }

    @Test fun neverExpireHiddenDismissedOrNonConfirmationNextUp() {
        assertFalse(waiting(prompt = false, ended = true))
        assertFalse(waiting(autoplay = false, ended = true))
        assertFalse(waiting(confirmation = false, ended = true))
        assertFalse(waiting(prompt = false, clockAtEnd = true))
    }
}
