package com.ambient.tv.ui.settings

import org.junit.Assert.*
import org.junit.Test

class UiDisplayDraftTest {
    @Test fun newDraftKeepsTheSystemResolutionAndResolutionChangesSaveImmediately() {
        val saved = SettingsUiState()
        assertEquals("system", saved.uiRenderResolutionKey)
        val changed = saved.copy(uiDisplay = saved.uiDisplay.copy(renderResolutionKey = "1080p"))
        assertNotEquals(saved.editableValues(), changed.editableValues())
        assertEquals("1080p", changed.deferredSettingsFrom(saved).uiRenderResolutionKey)
        assertEquals(saved.gpuRenderResolutionKey, changed.gpuRenderResolutionKey)
        assertEquals(saved.uiHdrModeKey, changed.uiHdrModeKey)
    }

    @Test fun localScaleEditAndImportedResolutionMergeIndependently() {
        val saved = SettingsUiState()
        val draft = saved.copy(uiDisplay = saved.uiDisplay.copy(scale = .9f))
        val stored = saved.copy(uiDisplay = saved.uiDisplay.copy(renderResolutionKey = "panel"))
        val merged = mergeStoredSettings(draft, saved, stored)
        assertEquals(.9f, merged.uiScale, 0f)
        assertEquals("panel", merged.uiRenderResolutionKey)
    }

    @Test fun localResolutionEditSurvivesAnImportedScaleOrResolution() {
        val saved = SettingsUiState()
        val draft = saved.copy(uiDisplay = saved.uiDisplay.copy(renderResolutionKey = "1080p"))
        val stored = saved.copy(uiDisplay = UiDisplayDraft(scale = .8f, renderResolutionKey = "panel"))
        val merged = mergeStoredSettings(draft, saved, stored)
        assertEquals(.8f, merged.uiScale, 0f)
        assertEquals("1080p", merged.uiRenderResolutionKey)
    }
}
