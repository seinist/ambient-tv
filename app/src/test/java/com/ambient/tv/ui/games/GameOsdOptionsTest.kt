package com.ambient.tv.ui.games

import com.ambient.tv.games.GameStateEntry
import com.ambient.tv.games.GameStateKind
import com.ambient.tv.ui.player.OsdButton
import com.ambient.tv.ui.player.OsdPlayer
import com.ambient.tv.ui.player.osdButtonVisible
import com.ambient.tv.ui.player.osdHiddenAfterToggle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOsdOptionsTest {
    private val manual = GameStateEntry("m1", GameStateKind.MANUAL, 1, "core", "sha256:current", number = 1)

    private fun ready() = GameOsdAvailability(
        loaded = true, stateAvailable = true, frameExact = true,
        supportsCheats = true, supportsNetplay = true, achievements = true,
        discCount = 2, states = listOf(manual), coreId = "core", coreStamp = "sha256:current",
        legacyCoreStamp = "1024:1234", rewindAvailable = true,
    )

    @Test fun everyGameActionCanBeHiddenWithoutChangingVideoVisibility() {
        var hidden = setOf("MEDIA/EXIT", "YOUTUBE/NEXT")
        ready().buttons().forEach { button ->
            // This also covers optional actions that start hidden.
            hidden = osdHiddenAfterToggle(hidden, OsdPlayer.GAMES, button, visible = true)
            assertTrue(osdButtonVisible(button, OsdPlayer.GAMES, hidden))
            hidden = osdHiddenAfterToggle(hidden, OsdPlayer.GAMES, button, visible = false)
            assertFalse(osdButtonVisible(button, OsdPlayer.GAMES, hidden))
        }
        assertTrue(hidden.containsAll(setOf("MEDIA/EXIT", "YOUTUBE/NEXT")))
        assertTrue(osdButtonVisible(OsdButton.EXIT, OsdPlayer.YOUTUBE, hidden))
        OsdPlayer.GAMES.buttons.forEach { button ->
            hidden = osdHiddenAfterToggle(hidden, OsdPlayer.GAMES, button, visible = true)
        }
        assertEquals(setOf("MEDIA/EXIT", "YOUTUBE/NEXT"),
            hidden.filterNot { it.removePrefix("+").startsWith("GAMES/") }.toSet())
        OsdPlayer.GAMES.buttons.forEach { assertTrue(osdButtonVisible(it, OsdPlayer.GAMES, hidden)) }
    }

    @Test fun supportedActionsUseTheSameOrderingAsTheSharedGameBar() {
        assertEquals(OsdPlayer.GAMES.buttons, ready().buttons())
        val offered = ready().copy(achievements = false, discCount = 1).buttons()
        assertFalse(OsdButton.GAME_ACHIEVEMENTS in offered)
        assertFalse(OsdButton.GAME_DISC in offered)
        assertTrue(OsdButton.GAME_PICTURE in offered)
        assertTrue(OsdButton.GAME_CONTROLS in offered)
        assertTrue(OsdButton.EXIT in offered)
    }

    @Test fun unsupportedStateCoreStillAllowsManagingExistingFiles() {
        val actions = ready().copy(stateAvailable = false, frameExact = false,
            supportsCheats = false, supportsNetplay = false, rewindAvailable = false)
        val offered = actions.buttons()
        listOf(OsdButton.GAME_SAVE, OsdButton.GAME_LOAD, OsdButton.GAME_REWIND,
            OsdButton.GAME_CHEATS, OsdButton.GAME_NETPLAY).forEach { assertFalse(it in offered) }
        assertTrue(OsdButton.GAME_STATES in offered)
        assertFalse(actions.canLoad(manual))
    }

    @Test fun hardcoreHidesLoadingCheatsRewindAndNewNetplayButKeepsSavingAndStateManagement() {
        val actions = ready().copy(hardcore = true)
        val offered = actions.buttons()
        listOf(OsdButton.GAME_LOAD, OsdButton.GAME_CHEATS, OsdButton.GAME_REWIND,
            OsdButton.GAME_NETPLAY).forEach { assertFalse(it in offered) }
        assertTrue(OsdButton.GAME_SAVE in offered)
        assertTrue(OsdButton.GAME_STATES in offered)
        assertFalse(actions.canLoad(manual))
    }

    @Test fun netplayHidesTimelineAndCoreMutationsButRetainsDisconnectPanel() {
        val actions = ready().copy(netplay = true)
        val offered = actions.buttons()
        listOf(OsdButton.GAME_LOAD, OsdButton.GAME_RESET, OsdButton.GAME_FAST_FORWARD,
            OsdButton.GAME_REWIND, OsdButton.GAME_CHEATS, OsdButton.GAME_CORE,
            OsdButton.GAME_DISC).forEach { assertFalse(it in offered) }
        assertTrue(OsdButton.GAME_SAVE in offered)
        assertTrue(OsdButton.GAME_STATES in offered)
        assertTrue(OsdButton.GAME_NETPLAY in offered)
        assertTrue(actions.copy(stateAvailable = false, frameExact = false, directContent = true,
            supportsNetplay = false, hardcore = true).netplaySetup)
    }

    @Test fun busyAndNotReadyHideUnavailableOperationsWithoutRemovingExit() {
        for (actions in listOf(ready().copy(busy = true), ready().copy(loaded = false))) {
            val offered = actions.buttons()
            listOf(OsdButton.GAME_SAVE, OsdButton.GAME_LOAD, OsdButton.GAME_RESET,
                OsdButton.GAME_FAST_FORWARD, OsdButton.GAME_REWIND, OsdButton.GAME_CORE,
                OsdButton.GAME_CHEATS, OsdButton.GAME_NETPLAY, OsdButton.GAME_DISC)
                .forEach { assertFalse(it in offered) }
            assertTrue(OsdButton.EXIT in offered)
            assertTrue(OsdButton.GAME_INFO in offered)
        }
        assertFalse(ready().copy(busy = true).manageStates)
        assertTrue(ready().copy(loaded = false).manageStates)
    }

    @Test fun stateCompatibilityIncludesCoreIdentityAndResolvedLegacyStamp() {
        val actions = ready()
        val legacy = manual.copy(coreStamp = "1024:1234")
        assertTrue(actions.canLoad(manual))
        assertTrue(actions.canLoad(legacy))
        assertFalse(actions.canLoad(manual.copy(coreId = "different-core")))
        assertFalse(actions.canLoad(manual.copy(coreStamp = "sha256:different")))
        assertFalse(actions.copy(legacyCoreStamp = null).canLoad(legacy))
        assertFalse(actions.copy(coreStamp = "").canLoad(manual.copy(coreStamp = "")))
    }

    @Test fun quickloadUsesNewestCompatibleManualRatherThanNewestIncompatibleOrAutoState() {
        val newer = manual.copy(id = "m2", createdAt = 2, number = 2)
        val newestIncompatible = manual.copy(id = "m3", createdAt = 3, coreStamp = "sha256:other")
        val auto = manual.copy(id = "auto", kind = GameStateKind.AUTO, createdAt = 4)
        val undo = manual.copy(id = "undo", kind = GameStateKind.UNDO, createdAt = 5)
        val actions = ready().copy(states = listOf(newestIncompatible, auto, undo, manual, newer))
        assertEquals(newer, actions.latestLoadableManual)
        assertTrue(actions.available(OsdButton.GAME_LOAD))
        assertNull(actions.copy(states = listOf(newestIncompatible, auto, undo)).latestLoadableManual)
        assertNull(actions.copy(hardcore = true).latestLoadableManual)
        assertNull(actions.copy(netplay = true).latestLoadableManual)
    }

    @Test fun rewindAndNetplayRespectTheirSpecificContracts() {
        assertFalse(ready().copy(rewindAvailable = false).rewind)
        assertFalse(ready().copy(frameExact = false).rewind)
        assertFalse(ready().copy(frameExact = false).netplaySetup)
        assertFalse(ready().copy(directContent = true).netplaySetup)
        assertFalse(ready().copy(supportsNetplay = false).netplaySetup)
        assertFalse(ready().copy(supportsCoreOptions = false).coreOptions)
        assertFalse(ready().available(OsdButton.SEEK))
        assertFalse(ready().copy(states = emptyList()).manageStates)
    }
}
