package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test

class ViewTransitionStyleTest {
    @Test fun missingAndUnknownModesPreserveTheAtomicNavigationDefault() {
        assertEquals(ViewTransitionStyle.OFF, ViewTransitionStyle.fromKey(null))
        assertEquals(ViewTransitionStyle.OFF, ViewTransitionStyle.fromKey("future"))
        assertEquals(ViewTransitionStyle.OFF, ViewTransitionStyle.fromKey(""))
        assertFalse(viewTransitionVisual(ViewTransitionStyle.OFF, false, false).animate)
        assertEquals(ViewTransitionStyle.entries.size, ViewTransitionStyle.entries.map { it.key }.toSet().size)
    }

    @Test fun eachOptInStyleHasItsOwnShortBoundedMotion() {
        val fade = viewTransitionVisual(ViewTransitionStyle.SOFT_FADE, false, false)
        val slide = viewTransitionVisual(ViewTransitionStyle.MICRO_SLIDE, false, false)
        val cinematic = viewTransitionVisual(ViewTransitionStyle.CINEMATIC, false, false)
        assertTrue(fade.animate && slide.animate && cinematic.animate)
        assertTrue(fade.durationMs in 120..300)
        assertTrue(slide.durationMs in 120..300)
        assertTrue(cinematic.durationMs in 120..300)
        assertEquals(0f, fade.offsetDp, 0f)
        assertTrue(slide.offsetDp in 2f..12f)
        assertEquals(1f, fade.initialScale, 0f)
        assertTrue(cinematic.initialScale in .98f..1f)
        assertTrue(cinematic.initialScrim > fade.initialScrim)
    }

    @Test fun reducedInterfaceAndVideoBackdropAvoidExpensiveLayers() {
        for (style in ViewTransitionStyle.entries) {
            assertFalse(viewTransitionVisual(style, true, false).animate)
            assertFalse(viewTransitionVisual(style, true, true).animate)
            val whileVideo = viewTransitionVisual(style, false, true)
            assertEquals(0f, whileVideo.offsetDp, 0f)
            assertEquals(1f, whileVideo.initialAlpha, 0f)
            assertEquals(1f, whileVideo.initialScale, 0f)
        }
    }

    @Test fun startupFullscreenPlayersAndRadioAtomicSwitchesNeverAnimate() {
        assertFalse(shouldAnimateViewTransition(null, "b", false, false, false))
        assertFalse(shouldAnimateViewTransition("a", "b", true, false, false))
        assertFalse(shouldAnimateViewTransition("a", "b", false, true, false))
        assertFalse(shouldAnimateViewTransition("a", "b", false, false, true))
        assertTrue(shouldAnimateViewTransition("a", "b", false, false, false))
    }
}
