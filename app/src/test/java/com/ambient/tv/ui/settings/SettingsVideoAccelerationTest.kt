package com.ambient.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsVideoAccelerationTest {
    @Test fun editsRemainIndependentDuringBackgroundRefresh() {
        val baseline = SettingsUiState()
        val draft = baseline.copy(mpvVideoDecoderKey = "vulkan", vlcVideoOutputModeKey = "vulkan")
        val stored = baseline.copy(mpvVideoOutputModeKey = "auto", mpvVideoDecoderKey = "software")
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals("auto", merged.mpvVideoOutputModeKey)
        assertEquals("vulkan", merged.mpvVideoDecoderKey)
        assertEquals("vulkan", merged.vlcVideoOutputModeKey)
        assertNotEquals(baseline.editableValues(), draft.editableValues())
    }
    @Test fun untouchedAccelerationSettingsRefreshWithoutBecomingDirty() {
        val baseline = SettingsUiState()
        val stored = baseline.copy(mpvVideoOutputModeKey = "vulkan", mpvVideoDecoderKey = "vulkan", vlcVideoOutputModeKey = "android")
        assertEquals(stored.editableValues(), mergeStoredSettings(baseline, baseline, stored).editableValues())
    }
    @Test fun gpuOutputPreferencesAreDraftFieldsThatSaveAndMerge() {
        val baseline = SettingsUiState()
        val draft = baseline.copy(allowHighRefreshDoubling = true, gpuRenderResolutionKey = "1080")
        assertNotEquals(baseline.editableValues(), draft.editableValues())
        val stored = baseline.copy(mpvSdrCurveKey = "bt1886")
        val merged = mergeStoredSettings(draft, baseline, stored)
        assertEquals(true, merged.allowHighRefreshDoubling)
        assertEquals("1080", merged.gpuRenderResolutionKey)
        assertEquals("bt1886", merged.mpvSdrCurveKey)
    }
}
