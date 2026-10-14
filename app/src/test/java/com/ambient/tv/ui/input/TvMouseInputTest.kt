package com.ambient.tv.ui.input

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvMouseInputTest {
    @Test
    fun secondaryAndBackButtonsNavigateBack() {
        assertEquals(TvMouseButtonAction.BACK, tvMouseButtonAction(MotionEvent.BUTTON_SECONDARY))
        assertEquals(TvMouseButtonAction.BACK, tvMouseButtonAction(MotionEvent.BUTTON_BACK))
        assertNull(tvMouseButtonAction(MotionEvent.BUTTON_PRIMARY))
        assertNull(tvMouseButtonAction(MotionEvent.BUTTON_FORWARD))
        assertNull(tvMouseButtonAction(MotionEvent.BUTTON_TERTIARY))
    }

    @Test
    fun middlePanStartsOnlyAfterTouchSlop() {
        assertFalse(shouldStartMiddleMouseDrag(100f, 100f, 105f, 105f, touchSlop = 8))
        assertTrue(shouldStartMiddleMouseDrag(100f, 100f, 110f, 100f, touchSlop = 8))
        assertTrue(shouldStartMiddleMouseDrag(100f, 100f, 100f, 112f, touchSlop = 8))
    }
}
