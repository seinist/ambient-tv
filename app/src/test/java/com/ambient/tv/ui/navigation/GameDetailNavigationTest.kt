package com.ambient.tv.ui.navigation

import android.net.Uri
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ambient.tv.data.files.FileKeys
import com.ambient.tv.data.files.FileSource
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.TokenStore
import com.ambient.tv.ui.FULLSCREEN_ROUTES
import com.ambient.tv.ui.NO_VIEW_TRANSITION_ROUTES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    @Test fun homeSelectionAndExplicitInfoResolveTheSameEncodedRom() {
        val fileId = "files:source:ROM/日本語 & #1?edition=EU|copy"
        val home = com.ambient.tv.ui.home.gameHomeKey(fileId, "snes")
        assertEquals(fileId, gameSelectionFileId(home))
        assertEquals(fileId, Uri.decode(Destinations.gamePlayer(fileId, "snes")
            .removePrefix("game_player/").substringBefore('?')))
        assertNull(gameSelectionFileId("game-home:|snes"))
        assertNull(gameSelectionFileId("game-home:broken|unknown-system"))
        assertNull(gameSelectionFileId("files:source:rom"))
    }

    @Test fun sourceSnapshotIncludesGamesOnlyFoldersAndRetiresDeletedSourceIncarnations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("game-selection-source", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val preferences = AppPreferences(prefs)
        val tokens = TokenStore(prefs, preferences)
        val source = FileSource(id = "snes", name = "SNES", kind = "local",
            treeUri = "content://storage/tree/primary%3ARoms", showInFiles = false)
        preferences.fileSources = listOf(source)
        preferences.filesEnabled = false
        val fileId = FileKeys.key(source.id, "primary:Roms/A game.sfc")
        val first = gameSelectionSource(preferences, tokens, fileId)
        assertNotNull(first)
        assertFalse(first!!.source.showInFiles)
        assertFalse(preferences.filesEnabled)
        preferences.fileSources = emptyList()
        assertNull(gameSelectionSource(preferences, tokens, fileId))
        preferences.fileSources = listOf(source)
        assertNotEquals(first, gameSelectionSource(preferences, tokens, fileId))
    }

    @Test fun changedCredentialsInvalidateADeferredGameStart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("game-selection-credentials", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val preferences = AppPreferences(prefs)
        val tokens = TokenStore(prefs, preferences)
        preferences.fileSources = listOf(FileSource(id = "nas", name = "NAS", kind = "smb",
            host = "nas", share = "games", username = "player", showInFiles = false))
        val fileId = FileKeys.key("nas", "A game.sfc")
        tokens.setSmbPassword("nas", "first test password")
        val first = gameSelectionSource(preferences, tokens, fileId)
        tokens.setSmbPassword("nas", "replacement test password")
        assertNotEquals(first, gameSelectionSource(preferences, tokens, fileId))
    }
}
