package com.ambient.tv.ui.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GameLoadingDialogTest {
    @Test fun unknownAndNonFiniteProgressRemainIndeterminate() {
        assertNull(gameLoadingProgress(null))
        assertNull(gameLoadingProgress(Float.NaN))
        assertNull(gameLoadingProgress(Float.POSITIVE_INFINITY))
        assertNull(gameLoadingProgress(Float.NEGATIVE_INFINITY))
    }

    @Test fun determinateProgressIsBoundedWithoutInventingValues() {
        assertEquals(0f, gameLoadingProgress(-0.2f)!!, 0f)
        assertEquals(0.68f, gameLoadingProgress(0.68f)!!, 0f)
        assertEquals(1f, gameLoadingProgress(1.2f)!!, 0f)
    }
}
