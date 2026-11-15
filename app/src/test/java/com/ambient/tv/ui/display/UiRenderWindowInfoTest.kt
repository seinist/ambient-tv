package com.ambient.tv.ui.display

import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiRenderWindowInfoTest {
    @Test fun embeddedPixelBoundsKeepLogicalBreakpointsAndLiveWindowFocus() {
        val native = object : WindowInfo {
            override var isWindowFocused = false
            override val containerSize = IntSize(3840, 2160)
            override val containerDpSize = DpSize(1920.dp, 1080.dp)
        }
        val embedded = UiRenderWindowInfo(native, UiRenderViewport(1920, 1080, 3840, 2160))
        assertEquals(IntSize(1920, 1080), embedded.containerSize)
        assertEquals(native.containerDpSize, embedded.containerDpSize)
        assertFalse(embedded.isWindowFocused)
        native.isWindowFocused = true
        assertTrue(embedded.isWindowFocused)
        assertEquals(IntSize(3840, 2160), native.containerSize)
    }
}
