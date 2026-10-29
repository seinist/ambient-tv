package com.ambient.tv.ui.player

import com.ambient.tv.data.local.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class Media3DisplayModePolicyTest {
    private val desktop = SupportedDisplayMode("3840x2160@60.000", 1, 3840, 2160, 60f)
    private val sourceMode = SupportedDisplayMode("1920x1080@24.000", 2, 1920, 1080, 24f)
    private val renderMode = SupportedDisplayMode("3840x2160@24.000", 3, 3840, 2160, 24f)
    private val modes = listOf(desktop, sourceMode, renderMode)
    private val whitelist = setOf(sourceMode.key, renderMode.key)

    @Test fun requestedGpuProcessingPreservesDisplayResolutionWithSystemWhitelist() {
        assertEquals(sourceMode, select(preserveOutputResolution = false))
        assertEquals(renderMode, select(preserveOutputResolution = true))
    }

    @Test fun enablingGpuProcessingCanRecoverTheStartingOutputResolution() {
        assertEquals(renderMode, select(
            current = sourceMode,
            preserveOutputResolution = true,
            fallbackModeKey = desktop.key,
        ))
    }

    @Test fun explicitContentResolutionStillWinsOverRendererScaling() {
        assertEquals(sourceMode, select(
            preferredModeKey = AppPreferences.DISPLAY_MODE_AUTO_CONTENT,
            preserveOutputResolution = true,
        ))
    }

    private fun select(
        current: SupportedDisplayMode = desktop,
        preferredModeKey: String = "system",
        preserveOutputResolution: Boolean,
        fallbackModeKey: String? = null,
    ): SupportedDisplayMode? = DisplayRefreshRateMatcher.selectContentMode(
        all = modes,
        current = current,
        contentFrameRate = 24f,
        contentWidth = 1920,
        contentHeight = 1080,
        preferredModeKey = preferredModeKey,
        fallbackModeKey = fallbackModeKey,
        allowedModes = whitelist,
        allowDoubleRate = false,
        allowThreeTwoPulldown = false,
        preserveOutputResolution = preserveOutputResolution,
    )
}
