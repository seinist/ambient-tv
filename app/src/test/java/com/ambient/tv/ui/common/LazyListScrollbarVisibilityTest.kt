package com.ambient.tv.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LazyListScrollbarVisibilityTest {
    @Test fun radioListCanShowScrollbarNextToAlphabet() {
        assertTrue(lazyListScrollbarVisible(
            hasAlphabet = true, scrollbarWithAlphabet = true,
            canScrollForward = true, canScrollBackward = false,
        ))
        assertTrue(lazyListScrollbarVisible(
            hasAlphabet = true, scrollbarWithAlphabet = true,
            canScrollForward = false, canScrollBackward = true,
        ))
    }

    @Test fun existingIndexOnlyListsKeepTheirPresentation() {
        assertFalse(lazyListScrollbarVisible(
            hasAlphabet = true, scrollbarWithAlphabet = false,
            canScrollForward = true, canScrollBackward = false,
        ))
        assertTrue(lazyListScrollbarVisible(
            hasAlphabet = false, scrollbarWithAlphabet = false,
            canScrollForward = true, canScrollBackward = false,
        ))
    }

    @Test fun scrollbarStaysHiddenWhenContentFits() {
        assertFalse(lazyListScrollbarVisible(
            hasAlphabet = true, scrollbarWithAlphabet = true,
            canScrollForward = false, canScrollBackward = false,
        ))
        assertFalse(lazyListScrollbarVisible(
            hasAlphabet = false, scrollbarWithAlphabet = true,
            canScrollForward = false, canScrollBackward = false,
        ))
    }
}
