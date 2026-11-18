package com.ambient.tv.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPlayerReturnNavigationTest {
    @Test fun backOnlyOpensRunningMiniPlayerAfterClosingSidebar() {
        val navigation = BackgroundPlayerReturnNavigation()
        assertFalse(navigation.canResumeAfterSidebarBack(true))
        navigation.armAfterSidebarBack(true)
        assertTrue(navigation.canResumeAfterSidebarBack(true))
        assertFalse(navigation.canResumeAfterSidebarBack(false))
        navigation.disarm() // User moved focus or chose another destination
        assertFalse(navigation.canResumeAfterSidebarBack(true))
        navigation.armAfterSidebarBack(false)
        assertFalse(navigation.canResumeAfterSidebarBack(true))
    }

    @Test fun reopeningFromDifferentViewDoesNotSelectPlayingItemWhenBackingOut() {
        val navigation = BackgroundPlayerReturnNavigation()

        // Media originated in SmartTube, but the user is now browsing Plex.
        navigation.enterPlayer("smarttube-original-player", previousIsPlayer = false)
        navigation.enterBrowse() // SmartTube was minimized
        navigation.armAfterSidebarBack(true)
        navigation.markMiniPlayerReentry("fullscreen-above-plex")
        navigation.enterPlayer("fullscreen-above-plex", previousIsPlayer = false)
        assertTrue(navigation.isMiniPlayerReentry("fullscreen-above-plex"))
        assertFalse(navigation.armedBySidebarBack)

        // Returning to the current Plex page must not issue a playing-item focus return.
        navigation.enterBrowse()
        assertFalse(navigation.isMiniPlayerReentry("fullscreen-above-plex"))

        // Starting a genuinely new item from Plex keeps ordinary focus-return behavior.
        navigation.enterPlayer("new-plex-player", previousIsPlayer = false)
        assertFalse(navigation.isMiniPlayerReentry("new-plex-player"))
    }

    @Test fun reentrySurvivesNextWithinFullscreenButNotANewBrowseSelection() {
        val navigation = BackgroundPlayerReturnNavigation()
        navigation.markMiniPlayerReentry("reopened-player")
        navigation.enterPlayer("reopened-player", previousIsPlayer = false)

        navigation.enterPlayer("player-next", previousIsPlayer = true)
        assertTrue(navigation.isMiniPlayerReentry("player-next"))
        assertFalse(navigation.isMiniPlayerReentry("reopened-player"))

        navigation.markNextPlayerContinuation()
        navigation.enterPlayer("player-next-replaced", previousIsPlayer = false)
        assertTrue(navigation.isMiniPlayerReentry("player-next-replaced"))

        navigation.enterBrowse()
        navigation.enterPlayer("different-selection", previousIsPlayer = false)
        assertFalse(navigation.isMiniPlayerReentry("different-selection"))
    }

    @Test fun stoppingPlaybackClearsReentryAndSidebarBackState() {
        val navigation = BackgroundPlayerReturnNavigation()
        navigation.armAfterSidebarBack(true)
        navigation.markMiniPlayerReentry("player")
        assertTrue(navigation.isMiniPlayerReentry("player"))
        navigation.clearStoppedPlayback()
        assertFalse(navigation.isMiniPlayerReentry("player"))
        assertFalse(navigation.canResumeAfterSidebarBack(true))
    }
}
