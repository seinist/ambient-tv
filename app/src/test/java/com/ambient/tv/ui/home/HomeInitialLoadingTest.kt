package com.ambient.tv.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeInitialLoadingTest {
    private fun waiting(
        rows: Boolean = false,
        library: Boolean = false,
        radio: Boolean = false,
        tv: Boolean = false,
        photo: Boolean = false,
        trailers: Boolean = false,
    ) = homeAwaitingFirstResults(
        hasVisibleRows = rows,
        libraryPending = library,
        radioPending = radio,
        liveTvPending = tv,
        photoPending = photo,
        trailerPending = trailers,
    )

    @Test fun aFastEmptyProviderMustNotConfirmHomeWhileOtherSourcesLoad() {
        assertTrue(waiting(radio = true))
        assertTrue(waiting(tv = true))
        assertTrue(waiting(library = true))
        assertTrue(waiting(photo = true))
        assertTrue(waiting(trailers = true))
        assertTrue(waiting(tv = true, radio = true))
        assertTrue(waiting(tv = false, radio = true))
    }

    @Test fun firstNonemptyRowCanBeShownWithoutWaitingForSlowSources() {
        assertFalse(waiting(rows = true, radio = true, tv = true))
        assertFalse(waiting(rows = true, library = true))
    }

    @Test fun trulyEmptyHomeIsShownOnlyWhenAllSourcesSettled() {
        assertFalse(waiting())
        assertFalse(waiting(radio = false, tv = false, trailers = false))
    }
}
