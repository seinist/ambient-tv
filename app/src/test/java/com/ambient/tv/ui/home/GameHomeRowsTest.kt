package com.ambient.tv.ui.home

import android.net.Uri
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.games.library.LibraryGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GameHomeRowsTest {
    private fun game(id: String, system: String, fav: Boolean, played: Long): LibraryGame =
        LibraryGame(
            fileId = "files-source:$id", folderId = "folder", sourceId = "nas",
            path = "Games/$id.rom", filename = "$id.rom", systemId = system,
            title = id, internalTitle = null, size = 1234L, modified = 0L,
            favorite = fav, lastPlayed = played,
        )

    @Test fun favoritesAndRecentsAreOfflineCatalogOnlyAndDeepLinkExactly() {
        val input = listOf(game("Alpha", "snes", true, 10L),
            game("Beta", "gb", false, 50L), game("Gamma", "nes", true, 30L))
        val definitions = listOf(
            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_GAMES,
                contentKey = HomeRowDefinition.GAMES_FAVORITES),
            HomeRowDefinition(system = HomeRowDefinition.SYSTEM_GAMES,
                contentKey = HomeRowDefinition.GAMES_RECENT),
        )
        val rows = gameHomeRows(RuntimeEnvironment.getApplication(), definitions, input)
        assertEquals(listOf("games.favorites", "games.recent"), rows.map { it.id })
        assertEquals(listOf("Alpha", "Gamma"), rows[0].items.map { it.title })
        assertEquals(listOf("Beta", "Gamma", "Alpha"), rows[1].items.map { it.title })
        val key = gameHomeKey(input[0].fileId, input[0].systemId)
        assertTrue(key.startsWith(GAME_HOME_ITEM_PREFIX))
        assertEquals(input[0].fileId, Uri.decode(key.substringAfter(GAME_HOME_ITEM_PREFIX).substringBeforeLast('|')))
    }

    @Test fun emptyGamesDoNotPublishEmptyHomeRows() {
        val def = listOf(HomeRowDefinition(system = HomeRowDefinition.SYSTEM_GAMES, contentKey = "favorites"))
        assertTrue(gameHomeRows(RuntimeEnvironment.getApplication(), def, emptyList()).isEmpty())
        assertTrue(gameHomeRows(RuntimeEnvironment.getApplication(), def,
            listOf(game("None", "nes", false, 0L))).isEmpty())
    }
}
