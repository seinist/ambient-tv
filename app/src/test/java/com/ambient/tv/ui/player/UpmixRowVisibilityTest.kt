package com.ambient.tv.ui.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpmixRowVisibilityTest {
    @Test fun shownForSurroundLayouts() {
        assertTrue(upmixRowVisible(6, "auto"))
        assertTrue(upmixRowVisible(8, "5.1"))
        assertTrue(upmixRowVisible(8, "7.1"))
    }

    @Test fun hiddenForStereoMonoOrStereoRoutes() {
        for (mode in listOf("stereo", "mono", "left", "right")) assertFalse(upmixRowVisible(8, mode))
        assertFalse(upmixRowVisible(2, "auto"))
    }
}
