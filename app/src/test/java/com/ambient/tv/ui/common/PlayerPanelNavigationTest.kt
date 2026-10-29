package com.ambient.tv.ui.common

import org.junit.Assert.*
import org.junit.Test

class PlayerPanelNavigationTest {

    @Test fun hierarchyDepthTracksForwardBackAndWholePanelClose() {
        val nav = PlayerPanelNavigation()
        assertEquals(0, nav.depth)
        nav.fromOrigin("bar:settings", true) { nav.open(UnifiedPlayerPanel.SETTINGS) }
        assertEquals(1, nav.depth)
        nav.fromOrigin("entry:video", false) { nav.open(UnifiedPlayerPanel.VIDEO) }
        assertEquals(2, nav.depth)
        nav.back(UnifiedPlayerPanel.VIDEO)
        assertEquals(1, nav.depth)
        nav.close()
        assertEquals(0, nav.depth)
    }

    @Test fun videoSelectionSurvivesEngineIndependentPanelAccessButBackClosesPopup() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.SETTINGS)
        nav.open(UnifiedPlayerPanel.VIDEO)
        val state = nav.videoState
        state.section = "filters"
        state.expandedSelector = "filters:deinterlace"
        state.focusedSelector = "filters:deinterlace"
        nav.open(UnifiedPlayerPanel.VIDEO)
        assertSame(state, nav.videoState)
        assertEquals("filters", nav.videoState.section)
        assertEquals("filters:deinterlace", nav.videoState.expandedSelector)
        nav.back()
        assertNull(state.expandedSelector)
        assertNull(state.focusedSelector)
        nav.open(UnifiedPlayerPanel.VIDEO)
        assertEquals("filters", state.section)
        assertNull(state.expandedSelector)
    }

    @Test fun closingPlayerPanelDiscardsPendingVideoPopupFocus() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.VIDEO)
        nav.videoState.expandedSelector = "renderer:output"
        nav.videoState.focusedSelector = "renderer:output"
        nav.close()
        assertNull(nav.videoState.expandedSelector)
        assertNull(nav.videoState.focusedSelector)
    }

    @Test fun repeatedBackClosesEveryLevelAndReturnsToThePlayerOrigin() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:settings", root = true) { nav.open(UnifiedPlayerPanel.SETTINGS) }
        nav.fromOrigin("entry:video", root = false) { nav.open(UnifiedPlayerPanel.VIDEO) }
        nav.back()
        assertEquals(UnifiedPlayerPanel.SETTINGS, nav.panel)
        assertEquals("entry:video", nav.restoreKey)
        nav.back()
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
        assertEquals("bar:settings", nav.restoreKey)
        nav.back()
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
    }

    @Test fun reopeningCancelsThePendingFocusReturnToThePlayer() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:video", root = true) { nav.open(UnifiedPlayerPanel.VIDEO) }
        nav.back()
        val oldGeneration = nav.restoreGeneration
        nav.fromOrigin("bar:audio", root = true) { nav.open(UnifiedPlayerPanel.AUDIO) }
        assertNull(nav.restoreKey)
        assertTrue(nav.restoreGeneration > oldGeneration)
        assertFalse(nav.restoreFailed)
        assertEquals(UnifiedPlayerPanel.AUDIO, nav.panel)
        nav.back()
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
        assertEquals("bar:audio", nav.restoreKey)
    }

    @Test fun callbackFromAnOldPanelCannotCloseTheNewPanel() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.SETTINGS)
        nav.open(UnifiedPlayerPanel.VIDEO)
        nav.back(UnifiedPlayerPanel.VIDEO)
        nav.back(UnifiedPlayerPanel.VIDEO)
        assertEquals(UnifiedPlayerPanel.SETTINGS, nav.panel)
        nav.back(UnifiedPlayerPanel.SETTINGS)
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
    }
    @Test fun centralConfigurationReturnsToItsSingleSettingsEntry() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:settings", root = true) { nav.open(UnifiedPlayerPanel.SETTINGS) }
        nav.fromOrigin("entry:configuration", root = false) { nav.open(UnifiedPlayerPanel.CONFIGURATION) }
        assertEquals(UnifiedPlayerPanel.CONFIGURATION, nav.panel)
        nav.back(UnifiedPlayerPanel.CONFIGURATION)
        assertEquals(UnifiedPlayerPanel.SETTINGS, nav.panel)
        assertEquals("entry:configuration", nav.restoreKey)
        nav.back()
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
    }

    @Test fun liveTvBackReturnsToItsSettingsEntryThenTheActionBar() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:settings", root = true) { nav.open(UnifiedPlayerPanel.SETTINGS) }
        nav.fromOrigin("entry:Live-TV", root = false) { nav.open(UnifiedPlayerPanel.LIVE_TV) }

        assertEquals(UnifiedPlayerPanel.LIVE_TV, nav.panel)
        nav.back(UnifiedPlayerPanel.LIVE_TV)
        assertEquals(UnifiedPlayerPanel.SETTINGS, nav.panel)
        assertEquals("entry:Live-TV", nav.restoreKey)

        nav.back(UnifiedPlayerPanel.SETTINGS)
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
        assertEquals("bar:settings", nav.restoreKey)
    }

    @Test fun liveTvDoesNotSelectVideoAudioOrVisualizerControls() {
        assertFalse(UnifiedPlayerPanel.LIVE_TV.isVideo)
        assertFalse(UnifiedPlayerPanel.LIVE_TV.isAudio)
        assertFalse(UnifiedPlayerPanel.LIVE_TV.isVisualizer)
    }

    @Test fun directPanelClosesWithoutInventingASettingsParent() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:video", true) { nav.open(UnifiedPlayerPanel.VIDEO) }
        nav.back(UnifiedPlayerPanel.VIDEO)
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
        assertEquals("bar:video", nav.restoreKey)
    }

    @Test fun hidingOsdCancelsPendingReturnToItsControls() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:video", true) { nav.open(UnifiedPlayerPanel.VIDEO) }
        nav.back()
        val generation = nav.restoreGeneration
        nav.cancelFocusReturn()
        assertNull(nav.restoreKey)
        assertTrue(nav.restoreGeneration > generation)
        assertFalse(nav.restoreFailed)
    }

    @Test fun audioCodecBackRestoresEachOriginAndKeepsArea() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:audio", true) { nav.open(UnifiedPlayerPanel.AUDIO) }
        nav.audioState.section = "output"
        nav.fromOrigin("entry:output", false) { nav.open(UnifiedPlayerPanel.AUDIO_SECTION) }
        nav.fromOrigin("entry:codecs", false) { nav.open(UnifiedPlayerPanel.AUDIO_CODECS) }
        assertTrue(nav.panel.isAudio)
        nav.back()
        assertEquals(UnifiedPlayerPanel.AUDIO_SECTION, nav.panel)
        assertEquals("entry:codecs", nav.restoreKey)
        assertEquals("output", nav.audioState.section)
        nav.back()
        assertEquals("entry:output", nav.restoreKey)
        nav.back()
        assertEquals("bar:audio", nav.restoreKey)
    }

    @Test fun visualSettingsBackReturnsToBrowserBeforeSettings() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.SETTINGS)
        nav.fromOrigin("entry:visuals", false) { nav.open(UnifiedPlayerPanel.VISUALIZER) }
        nav.fromOrigin("entry:appearance", false) { nav.open(UnifiedPlayerPanel.VISUALIZER_SETTINGS) }
        assertTrue(nav.panel.isVisualizer)
        nav.back()
        assertEquals(UnifiedPlayerPanel.VISUALIZER, nav.panel)
        assertEquals("entry:appearance", nav.restoreKey)
        nav.back()
        assertEquals(UnifiedPlayerPanel.SETTINGS, nav.panel)
    }

    @Test fun audioRecompositionKeepsSelectorButLeavingClearsIt() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.AUDIO_SECTION)
        nav.audioState.section = "eq"
        nav.audioState.focusedSelector = "eq:band"
        nav.audioState.expandedSelector = "eq:band"
        nav.open(UnifiedPlayerPanel.AUDIO_SECTION)
        assertEquals("eq:band", nav.audioState.focusedSelector)
        nav.close()
        assertEquals("eq", nav.audioState.section)
        assertNull(nav.audioState.focusedSelector)
        assertNull(nav.audioState.expandedSelector)
    }

    @Test fun inactivityDiscardsTheEntirePathAndInvalidatesPendingFocusRestoration() {
        val nav = PlayerPanelNavigation()
        nav.fromOrigin("bar:settings", true) { nav.open(UnifiedPlayerPanel.SETTINGS) }
        nav.fromOrigin("entry:audio", false) { nav.open(UnifiedPlayerPanel.AUDIO) }
        nav.open(UnifiedPlayerPanel.AUDIO_SECTION)
        nav.back()
        val generation = nav.restoreGeneration
        nav.audioState.expandedSelector = "output:device"
        assertTrue(nav.closeForInactivity(nav.activity.version))
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
        assertNull(nav.restoreKey)
        assertFalse(nav.restoreFailed)
        assertTrue(nav.restoreGeneration > generation)
        assertNull(nav.audioState.expandedSelector)
        nav.back()
        assertEquals(UnifiedPlayerPanel.NONE, nav.panel)
    }

    @Test fun inputAndIndependentDialogOwnersInvalidateOrBlockAnOldDeadline() {
        val nav = PlayerPanelNavigation()
        nav.open(UnifiedPlayerPanel.VIDEO)
        val old = nav.activity.version
        nav.activity.interact()
        assertFalse(nav.closeForInactivity(old))
        val first = Any()
        val second = Any()
        nav.activity.keepOpen(first, true)
        nav.activity.keepOpen(second, true)
        nav.activity.keepOpen(first, false)
        assertFalse(nav.closeForInactivity(nav.activity.version))
        nav.activity.keepOpen(second, false)
        nav.activity.pointerPressed(first, true)
        assertFalse(nav.closeForInactivity(nav.activity.version))
        nav.activity.pointerPressed(first, false)
        assertTrue(nav.closeForInactivity(nav.activity.version))
    }
}
