package com.ambient.tv.ui.display

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ambient.tv.data.local.UiRenderResolutionPolicy
import com.ambient.tv.ui.common.qrDialogMaxHeightFraction
import org.junit.Assert.assertEquals
import org.junit.Test

class UiRenderScreenHeightTest {
    private data class Screen(val viewport: UiRenderViewport?, val heightPixels: Int, val density: Float)

    private val screens = listOf(
        Screen(null, 1080, 2f),
        Screen(null, 2160, 4f),
        Screen(UiRenderViewport(1920, 1080, 3840, 2160), 2160, 4f),
        Screen(UiRenderViewport(3840, 2160, 1920, 1080), 1080, 2f),
    )

    private fun height(screen: Screen, uiScale: Float) = resolveUiRenderScreenHeight(
        viewport = screen.viewport,
        screenHeightPixels = screen.heightPixels,
        density = Density(screen.density * uiScale * UiRenderEnvironment(
            UiRenderResolutionPolicy.PANEL, screen.viewport,
        ).densityScale),
        fallbackHeightDp = 540,
    )

    @Test fun qrAndCompanionKeepTheirLayoutAcrossSystemDownsamplingAndPanelUpsampling() {
        screens.forEach { screen ->
            val height = height(screen, uiScale = 1f)
            assertEquals(540.dp, height)
            assertEquals(0.90f, qrDialogMaxHeightFraction(height), 0.0001f)
            assertEquals(false, height < 500.dp)
        }
    }

    @Test fun userControlScaleStillChangesTheLayoutEquallyInEveryRenderMode() {
        screens.forEach { screen ->
            val height = height(screen, uiScale = 1.25f)
            assertEquals(432.dp, height)
            assertEquals(0.875f, qrDialogMaxHeightFraction(height), 0.0001f)
            assertEquals(true, height < 500.dp)
        }
    }

    @Test fun unavailablePixelHeightUsesConfigurationFallback() {
        assertEquals(540.dp, resolveUiRenderScreenHeight(null, 0, Density(2f), 540))
    }
}
