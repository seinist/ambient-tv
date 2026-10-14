package com.ambient.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceAspectRatioHintTest {
    @Test fun preservesDlnaDisplayGeometryInsteadOfFallingBackToTheViewport() {
        assertEquals(1920f / 1036f, sourceAspectRatioHint(1920, 1036)!!, 0.0001f)
    }

    @Test fun rejectsMissingOrInvalidGeometry() {
        assertNull(sourceAspectRatioHint(null, 1080))
        assertNull(sourceAspectRatioHint(1920, 0))
        assertNull(sourceAspectRatioHint(10_000, 1))
    }
}
