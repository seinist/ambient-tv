package com.ambient.tv.ui.navigation

import android.net.Uri
import com.ambient.tv.ui.FULLSCREEN_ROUTES
import com.ambient.tv.ui.NO_VIEW_TRANSITION_ROUTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GameDetailNavigationTest {
    @Test fun sourcePathsStayInOneEncodedDetailArgument() {
        val fileId = "files:NAS/Games/A&B #1?edition=EU|copy"
        val route = Destinations.gameDetail(fileId)
        assertEquals(fileId, Uri.decode(route.removePrefix("game_detail/")))
        assertEquals(1, route.count { it == '/' })
        assertTrue(Destinations.isDetailRoute(Destinations.GAME_DETAIL))
    }

    @Test fun gameDetailsUseDetailChromeAndReclaimFocusAfterPlaying() {
        assertTrue(Destinations.GAME_DETAIL in FULLSCREEN_ROUTES)
        assertFalse(Destinations.GAME_DETAIL in NO_VIEW_TRANSITION_ROUTES)
        assertTrue(returnsFromFullscreenPlayer(Destinations.GAME_PLAYER, Destinations.GAME_DETAIL))
    }
}
