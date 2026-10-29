package com.ambient.tv.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsItemIndexTest {
    @Test fun explicitSearchFindsItsTargetOrStartsAtTopWhileNormalBackKeepsScroll() {
        val index = SettingsItemIndex()
        index.replace(mapOf("intro" to 0, "background:video" to 1))

        assertEquals(1, index.landingPosition("background:video", requested = true))
        assertEquals(0, index.landingPosition(null, requested = true))
        assertEquals(0, index.landingPosition("not-visible", requested = true))
        assertNull(index.landingPosition(null, requested = false))
        assertNull(index.landingPosition("background:video", requested = false))
    }
}
