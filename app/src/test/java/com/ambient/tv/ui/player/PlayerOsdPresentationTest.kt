package com.ambient.tv.ui.player

import org.junit.Assert.*
import org.junit.Test

class PlayerOsdPresentationTest {
    @Test fun playbackStartsWithVisibleCompactInformation() {
        val osd = PlayerOsdPresentation()
        assertTrue(osd.visible)
        assertTrue(osd.show)
        assertFalse(osd.expanded)
        assertFalse(osd.seekFeedback)
        assertEquals(PlayerOsdMode.COMPACT, osd.mode)
    }

    @Test fun openingHiddenPlaybackGoesToCompactWithoutButtons() {
        val osd = PlayerOsdPresentation(mode = PlayerOsdMode.HIDDEN).reveal()
        assertTrue(osd.show)
        assertEquals(PlayerOsdMode.COMPACT, osd.mode)
    }

    @Test fun transportInteractionDoesNotCollapseAnOpenActionBar() {
        val osd = PlayerOsdPresentation().expand().reveal()
        assertTrue(osd.expanded)
        assertFalse(osd.seekFeedback)
    }

    @Test fun upReturnsFromButtonsToTheSameCompactPresentation() {
        val osd = PlayerOsdPresentation().expand().compact()
        assertEquals(PlayerOsdPresentation(), osd)
    }

    @Test fun seekFeedbackUsesCompactAndOkClaimsItWithoutHidingIt() {
        val feedback = PlayerOsdPresentation(mode = PlayerOsdMode.HIDDEN).compact(feedback = true)
        assertTrue(feedback.visible)
        assertTrue(feedback.seekFeedback)
        assertFalse(feedback.expanded)
        val active = feedback.reveal()
        assertTrue(active.show)
        assertFalse(active.seekFeedback)
        assertEquals(PlayerOsdMode.COMPACT, active.mode)
    }

    @Test fun expandedDismissalKeepsBackOwnedUntilStowAndFadeFinish() {
        val closing = PlayerOsdPresentation().expand().close()
        assertTrue(closing.visible)
        assertTrue(closing.closing)
        assertTrue(closing.closingFromExpanded)
        assertFalse(closing.show)
        assertFalse(closing.expanded)
        val hidden = closing.finishClose()
        assertFalse(hidden.visible)
        assertFalse(hidden.closing)
    }

    @Test fun compactDismissalDoesNotRequestAnotherBarMovement() {
        val closing = PlayerOsdPresentation().close()
        assertTrue(closing.closing)
        assertFalse(closing.closingFromExpanded)
    }

    @Test fun backAlsoDismissesPassiveSeekFeedbackBeforePlaybackCanExit() {
        val feedback = PlayerOsdPresentation().compact(feedback = true)
        assertTrue(feedback.visible)
        val closing = feedback.close()
        assertTrue(closing.visible)
        assertFalse(closing.seekFeedback)
        assertFalse(closing.close().visible)
    }

    @Test fun secondBackCompletesTheCurrentDismissal() {
        val closing = PlayerOsdPresentation().expand().close()
        assertTrue(closing.visible)
        val hidden = closing.close()
        assertEquals(PlayerOsdMode.HIDDEN, hidden.mode)
        assertEquals(hidden, hidden.close())
    }

    @Test fun inputDuringExitReopensCompactAndOldCompletionDoesNotHideIt() {
        val reopened = PlayerOsdPresentation().expand().close().reveal()
        assertTrue(reopened.show)
        assertEquals(PlayerOsdMode.COMPACT, reopened.mode)
        assertEquals(reopened, reopened.finishClose())
    }

    @Test fun phoneStowCompletesBeforeTheFinalFadeAndPanelsSkipIt() {
        assertEquals(300, playerOsdExitFadeDelayMs(true, true, 0f, handheld = true))
        assertEquals(700, playerOsdExitFadeDelayMs(true, true, 0f, handheld = false))
        // A bar already in its compact position should only fade out.
        assertEquals(0, playerOsdExitFadeDelayMs(true, false, 1f, handheld = true))
        // Opening a right-side settings panel must not wait for the stow animation.
        assertEquals(0, playerOsdExitFadeDelayMs(false, true, 0f, handheld = true))
        // An interrupted or in-progress bar movement must finish before fading.
        assertEquals(300, playerOsdExitFadeDelayMs(true, false, 0.5f, handheld = true))
    }
}
