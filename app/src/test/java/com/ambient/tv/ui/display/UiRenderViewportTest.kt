package com.ambient.tv.ui.display

import com.ambient.tv.data.local.UiRenderResolutionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiRenderViewportTest {
    @Test fun fullHdIsARealSmallerRenderTargetWithoutChangingOuterWindowCoordinates() {
        assertEquals(UiRenderViewport(1920, 1080, 3840, 2160), resolveUiRenderViewport(
            UiRenderResolutionPolicy.FULL_HD, 3840, 2160, 3840, 2160, 3840, 2160,
        ))
    }

    @Test fun panelCanExceedTheAndroidTvLogicalDesktop() {
        assertEquals(UiRenderViewport(3840, 2160, 1920, 1080), resolveUiRenderViewport(
            UiRenderResolutionPolicy.PANEL, 1920, 1080, 1920, 1080, 3840, 2160,
        ))
    }

    @Test fun dialogAndResizedHostUseTheirVisibleFractionOfThePanel() {
        assertEquals(UiRenderViewport(1920, 1080, 960, 540), resolveUiRenderViewport(
            UiRenderResolutionPolicy.PANEL, 960, 540, 1920, 1080, 3840, 2160,
        ))
        assertEquals(UiRenderViewport(1280, 720, 1280, 720), resolveUiRenderViewport(
            UiRenderResolutionPolicy.FULL_HD, 1280, 720, 3840, 2160, 3840, 2160,
        ))
    }

    @Test fun rotationMatchesPhysicalModeAxesToTheLogicalDisplay() {
        assertEquals(UiRenderViewport(2160, 3840, 1080, 1920), resolveUiRenderViewport(
            UiRenderResolutionPolicy.PANEL, 1080, 1920, 1080, 1920, 3840, 2160,
        ))
    }

    @Test fun invalidOrMissingDisplayFactsDoNotInventA4kTarget() {
        assertNull(resolveUiRenderViewport("panel", 0, 1080, 1920, 1080, 3840, 2160))
        assertEquals(UiRenderViewport(1280, 720, 1280, 720), resolveUiRenderViewport(
            UiRenderResolutionPolicy.PANEL, 1280, 720, 0, 0, 3840, 2160,
        ))
    }
}
