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
    @Test fun alphabetEntersAtFocusedContentLetterInsteadOfAlwaysA() {
        val memory = AlphabetBarFocusMemory(initialSelected = 'M')
        val letters = listOf('A', 'M', 'Z')
        assertEquals('M', memory.target(letters, 'M'))
        assertEquals('A', AlphabetBarFocusMemory(null).target(letters, null))
    }

    @Test fun alphabetRestoresLastRailLetterAfterLeavingAndReturning() {
        val memory = AlphabetBarFocusMemory(initialSelected = 'M')
        val letters = listOf('A', 'M', 'Z')
        memory.focused('Z')
        assertEquals('Z', memory.target(letters, 'M'))
        // A selection made while the rail is focused must not steal its current focus.
        memory.observeSelection('Z', railFocused = true)
        assertEquals('Z', memory.target(letters, 'Z'))
        // The selected content moved afterwards: enter at its letter on the next visit.
        memory.observeSelection('A', railFocused = false)
        assertEquals('A', memory.target(letters, 'A'))
    }

    @Test fun alphabetChoosesAvailableLetterAfterFiltersChange() {
        val memory = AlphabetBarFocusMemory(initialSelected = 'Z')
        assertEquals('B', memory.target(listOf('B', 'D'), selected = 'Z'))
        assertEquals(null, memory.target(emptyList(), selected = 'Z'))
    }

}
