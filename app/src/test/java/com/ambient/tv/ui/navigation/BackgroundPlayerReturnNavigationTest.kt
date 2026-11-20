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

    @Test fun fullscreenOsdMinimizeArmsDirectBackAndPreservesBrowseFocus() {
        val navigation = BackgroundPlayerReturnNavigation()
        navigation.enterPlayer("smarttube-live-player", previousIsPlayer = false)
        assertFalse(navigation.preservesBrowseContextOnExit("smarttube-live-player"))

        // The shared EXIT button arms the chain before its onBack callback pops the player.
        navigation.beginFullscreenExit("smarttube-live-player")
        assertTrue(navigation.preservesBrowseContextOnExit("smarttube-live-player"))
        assertFalse(navigation.preservesBrowseContextOnExit("another-player"))
        navigation.enterBrowse() // the exact view that was under the player is now visible
        assertTrue(navigation.canResumeAfterSidebarBack(true))
        assertFalse(navigation.canResumeAfterSidebarBack(false))
        assertFalse(navigation.preservesBrowseContextOnExit("smarttube-live-player"))

        // Back reopens the active session directly above the current view, without refocusing
        // the video or opening its details on the next exit.
        navigation.markMiniPlayerReentry("fullscreen-reopened")
        navigation.enterPlayer("fullscreen-reopened", previousIsPlayer = false)
        assertFalse(navigation.canResumeAfterSidebarBack(true))
        assertTrue(navigation.preservesBrowseContextOnExit("fullscreen-reopened"))
        navigation.enterBrowse()
        assertFalse(navigation.canResumeAfterSidebarBack(true))
    }

    @Test fun fullscreenExitBackChainIsOneShotAndDoesNotHijackNormalBrowsing() {
        val navigation = BackgroundPlayerReturnNavigation()
        navigation.beginFullscreenExit(null) // no valid player entry
        assertFalse(navigation.canResumeAfterSidebarBack(true))

        navigation.enterPlayer("player", previousIsPlayer = false)
        navigation.beginFullscreenExit("player")
        navigation.enterBrowse()
        navigation.disarm() // user moved with D-pad; keep regular Back navigation
        assertFalse(navigation.canResumeAfterSidebarBack(true))

        navigation.enterPlayer("second-player", previousIsPlayer = false)
        navigation.beginFullscreenExit("second-player")
        navigation.enterBrowse()
        navigation.enterBrowse() // user navigated to another page instead of pressing Back
        assertFalse(navigation.canResumeAfterSidebarBack(true))

        navigation.enterPlayer("third-player", previousIsPlayer = false)
        navigation.beginFullscreenExit("third-player")
        navigation.clearStoppedPlayback() // Stop / no longer playing must win
        navigation.enterBrowse()
        assertFalse(navigation.canResumeAfterSidebarBack(true))
        assertFalse(navigation.preservesBrowseContextOnExit("third-player"))
    }

    @Test fun fullscreenOsdExitOnDetailCanResumeEvenWithStaleSidebarFocus() {
        val navigation = BackgroundPlayerReturnNavigation()
        navigation.enterPlayer("youtube-player", previousIsPlayer = false)
        navigation.beginFullscreenExit("youtube-player")
        navigation.enterBrowse() // e.g. YouTubeDetail or Plex Detail was beneath the player

        assertTrue(navigation.canInterceptBackToPlayer(
            route = Destinations.DETAIL,
            hasActivePlayback = true,
            sidebarVisible = false,
            sidebarHasFocus = true,
        ))
        assertTrue(navigation.canInterceptBackToPlayer(
            route = Destinations.YOUTUBE_DETAIL,
            hasActivePlayback = true,
            sidebarVisible = false,
            sidebarHasFocus = true,
        ))
        assertFalse(navigation.canInterceptBackToPlayer(
            route = Destinations.DETAIL,
            hasActivePlayback = false,
            sidebarVisible = false,
            sidebarHasFocus = false,
        ))
        assertFalse(navigation.canInterceptBackToPlayer(
            route = Destinations.YOUTUBE_PLAYER,
            hasActivePlayback = true,
            sidebarVisible = false,
            sidebarHasFocus = false,
        ))
        assertFalse(navigation.canInterceptBackToPlayer(
            route = Destinations.SETTINGS,
            hasActivePlayback = true,
            sidebarVisible = true,
            sidebarHasFocus = false,
        ))
        assertFalse(navigation.canInterceptBackToPlayer(
            route = Destinations.HOME,
            hasActivePlayback = true,
            sidebarVisible = true,
            sidebarHasFocus = true,
        ))
        assertTrue(navigation.canInterceptBackToPlayer(
            route = Destinations.HOME,
            hasActivePlayback = true,
            sidebarVisible = true,
            sidebarHasFocus = false,
        ))
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

    @Test fun backInAMenuOpenedByBackReturnsToThePlayingSessionOnce() {
        val navigation = BackgroundPlayerReturnNavigation()
        navigation.enterPlayer("player", previousIsPlayer = false)
        navigation.enterBrowse() // Back from fullscreen to the page
        navigation.menuOpenedByBack(hasActivePlayback = true) // Back on the page opens the menu
        assertTrue(navigation.consumeMenuBackToPlayer(hasActivePlayback = true))
        assertFalse(navigation.consumeMenuBackToPlayer(hasActivePlayback = true))

        // Without a running session, or after choosing another page, Back only closes the menu.
        navigation.menuOpenedByBack(hasActivePlayback = false)
        assertFalse(navigation.consumeMenuBackToPlayer(hasActivePlayback = true))
        navigation.menuOpenedByBack(hasActivePlayback = true)
        navigation.enterBrowse()
        assertFalse(navigation.consumeMenuBackToPlayer(hasActivePlayback = true))
        navigation.menuOpenedByBack(hasActivePlayback = true)
        navigation.clearStoppedPlayback()
        assertFalse(navigation.consumeMenuBackToPlayer(hasActivePlayback = true))
    }
}
