package com.ambient.tv.ui.games

import com.ambient.tv.games.GameShaderGroup
import com.ambient.tv.ui.common.PlayerPanelNavigation
import com.ambient.tv.ui.common.UnifiedPlayerPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePanelNavigationTest {
    @Test fun settingsCoreCategoryAndOptionReturnThroughTheirActualOrigins() {
        val navigation = PlayerPanelNavigation()
        val game = GamePanelNavigation(navigation)
        navigation.fromOrigin("bar:settings", true) { game.open(GamePanel.SETTINGS) }
        navigation.fromOrigin("entry:core", false) { game.open(GamePanel.CORE) }
        navigation.fromOrigin("category:video", false) { game.openCategory("video") }
        navigation.fromOrigin("option:renderer", false) { game.openOption("renderer") }
        assertEquals(GamePanel.CORE, game.panel)
        assertTrue(game.fromSettings)
        assertEquals("video", game.optionCategory)
        assertEquals("renderer", game.optionKey)
        game.back()
        assertNull(game.optionKey)
        assertEquals("video", game.optionCategory)
        assertEquals("option:renderer", navigation.restoreKey)
        game.back()
        assertNull(game.optionCategory)
        assertEquals("category:video", navigation.restoreKey)
        game.back()
        assertEquals(GamePanel.SETTINGS, game.panel)
        assertEquals("entry:core", navigation.restoreKey)
        game.back()
        assertNull(game.panel)
        assertEquals("bar:settings", navigation.restoreKey)
    }

    @Test fun directPictureSubpagesDoNotInventASettingsParent() {
        val navigation = PlayerPanelNavigation()
        val game = GamePanelNavigation(navigation)
        navigation.fromOrigin("bar:picture", true) { game.open(GamePanel.PICTURE) }
        navigation.fromOrigin("group:crt", false) { game.openShaderGroup(GameShaderGroup.CRT) }
        assertEquals(GameShaderGroup.CRT, game.shaderGroup)
        assertFalse(game.fromSettings)
        game.back()
        assertNull(game.shaderGroup)
        assertEquals("group:crt", navigation.restoreKey)
        navigation.fromOrigin("entry:packs", false) { game.openShaderPacks() }
        assertTrue(game.shaderPacksOpen)
        game.back()
        assertFalse(game.shaderPacksOpen)
        assertEquals("entry:packs", navigation.restoreKey)
        game.back()
        assertNull(game.panel)
        assertEquals("bar:picture", navigation.restoreKey)
    }

    @Test fun aLateBackFromAnExitedPageCannotPopItsParent() {
        val game = GamePanelNavigation(PlayerPanelNavigation())
        game.open(GamePanel.CORE)
        game.openCategory("video")
        game.openOption("renderer")
        val old = game.page
        game.back(old)
        assertEquals("video", game.optionCategory)
        game.back(old)
        assertEquals("video", game.optionCategory)
        assertNull(game.optionKey)
    }

    @Test fun stateStripUsesTheBarOriginWithoutBecomingAnOsdHidingPanel() {
        val navigation = PlayerPanelNavigation()
        val game = GamePanelNavigation(navigation)
        navigation.fromOrigin("bar:states", true) { game.toggleStates() }
        assertTrue(game.statesOpen)
        assertEquals(UnifiedPlayerPanel.NONE, navigation.panel)
        game.closeStates()
        assertFalse(game.statesOpen)
        assertEquals("bar:states", navigation.restoreKey)
    }

    @Test fun openingAPanelFromTheStateStripDiscardsTheOldStripHandBack() {
        val navigation = PlayerPanelNavigation()
        val game = GamePanelNavigation(navigation)
        navigation.fromOrigin("bar:states", true) { game.toggleStates() }
        navigation.fromOrigin("bar:settings", true) { game.open(GamePanel.SETTINGS) }
        assertFalse(game.statesOpen)
        assertNull(navigation.restoreKey)
        game.closeStates()
        assertNull(navigation.restoreKey)
        game.back()
        assertEquals("bar:settings", navigation.restoreKey)
    }

    @Test fun resumeCancelsPendingReturnAndCannotReopenControls() {
        val navigation = PlayerPanelNavigation()
        val game = GamePanelNavigation(navigation)
        navigation.fromOrigin("bar:states", true) { game.toggleStates() }
        game.dismissForResume()
        assertFalse(game.statesOpen)
        assertNull(navigation.restoreKey)
        navigation.fromOrigin("bar:settings", true) { game.open(GamePanel.SETTINGS) }
        game.open(GamePanel.CORE)
        game.openOption("renderer")
        game.dismissForResume()
        assertNull(game.page)
        assertNull(navigation.restoreKey)
        navigation.restoreFailed = true
        game.dismissForResume()
        assertFalse(navigation.restoreFailed)
    }

    @Test fun directHardwareSettingsReplacesTheCurrentPath() {
        val navigation = PlayerPanelNavigation()
        val game = GamePanelNavigation(navigation)
        navigation.fromOrigin("bar:core", true) { game.open(GamePanel.CORE) }
        game.openOption("renderer")
        game.openRoot(GamePanel.SETTINGS)
        assertEquals(GamePanel.SETTINGS, game.panel)
        assertNull(game.optionKey)
        assertEquals(1, navigation.depth)
        game.back()
        assertNull(game.page)
    }

    @Test fun unsupportedSubpageRequestsCannotAlterAnotherPanel() {
        val game = GamePanelNavigation(PlayerPanelNavigation())
        game.open(GamePanel.CONTROLS)
        game.openOption("renderer")
        game.openCategory("video")
        game.openShaderGroup(GameShaderGroup.CRT)
        game.openShaderPacks()
        assertEquals(GamePanelPage(GamePanel.CONTROLS), game.page)
    }
}
