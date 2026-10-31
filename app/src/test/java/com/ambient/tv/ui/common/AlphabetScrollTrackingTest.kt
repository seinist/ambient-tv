package com.ambient.tv.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class AlphabetScrollTrackingTest {
    @Test fun handheldFollowsTheActualScrollPositionEvenWhenFocusIsStale() {
        assertEquals('Z', alphabetTrackedLetter(handheld = true, scrollLetter = 'Z', focusedLetter = 'A'))
        assertEquals('B', alphabetTrackedLetter(handheld = true, scrollLetter = 'B', focusedLetter = 'A'))
    }

    @Test fun tvPreservesDpadFocusOverViewportPosition() {
        assertEquals('C', alphabetTrackedLetter(handheld = false, scrollLetter = 'Z', focusedLetter = 'C'))
        assertEquals('A', alphabetTrackedLetter(handheld = false, scrollLetter = 'A', focusedLetter = null))
    }

    @Test fun unloadedViewportKeepsLastFocusedLetterUntilScrollIsKnown() {
        assertEquals('F', alphabetTrackedLetter(handheld = true, scrollLetter = null, focusedLetter = 'F'))
        assertEquals(null, alphabetTrackedLetter(handheld = true, scrollLetter = null, focusedLetter = null))
    }
}
