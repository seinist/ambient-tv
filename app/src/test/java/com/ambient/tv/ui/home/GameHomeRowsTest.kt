package com.ambient.tv.ui.home

import android.net.Uri
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.games.library.LibraryGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
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

    @Test fun rommRowsUseTheSameCardsWithServerArtwork() {
        val entry = game("Server game", "snes", true, 5L).copy(
            fileId = "romm:0123456789ab:42",
            coverPath = "http://romm.local/assets/romm/resources/roms/1/42/cover/big.png",
            backdropPath = "https://romm.local/assets/romm/resources/roms/1/42/screenshots/0.jpg",
        )
        val definitions = listOf(HomeRowDefinition(system = HomeRowDefinition.SYSTEM_ROMM,
            contentKey = HomeRowDefinition.GAMES_FAVORITES))
        val context = RuntimeEnvironment.getApplication()
        // A game row definition never picks RomM's games, and the other way round.
        assertTrue(gameHomeRows(context, definitions, listOf(entry)).isEmpty())
        val row = gameHomeRows(context, definitions, listOf(entry), system = HomeRowDefinition.SYSTEM_ROMM).single()
        assertEquals("romm.favorites", row.id)
        val item = row.items.single()
        assertEquals(entry.coverPath, item.thumbUrl)
        assertEquals(entry.backdropPath, item.artUrl)
        assertEquals(entry.fileId, Uri.decode(item.ratingKey.substringAfter(GAME_HOME_ITEM_PREFIX).substringBeforeLast('|')))
    }

    @Test fun emptyGamesDoNotPublishEmptyHomeRows() {
        val def = listOf(HomeRowDefinition(system = HomeRowDefinition.SYSTEM_GAMES, contentKey = "favorites"))
        assertTrue(gameHomeRows(RuntimeEnvironment.getApplication(), def, emptyList()).isEmpty())
        assertTrue(gameHomeRows(RuntimeEnvironment.getApplication(), def,
            listOf(game("None", "nes", false, 0L))).isEmpty())
    }

    @Test fun storedGameArtworkAndYearReachHomeWithoutDependingOnTheRomSource() {
        val entry = game("Local cover", "snes", true, 0L).copy(
            coverPath = "/app/private/game images/cover #1.png",
            backdropPath = "/app/private/game images/screen.png", metadataYear = 1994,
            coverWidth = 720, coverHeight = 480,
        )
        val def = listOf(HomeRowDefinition(system = HomeRowDefinition.SYSTEM_GAMES,
            contentKey = HomeRowDefinition.GAMES_ALL))
        val item = gameHomeRows(RuntimeEnvironment.getApplication(), def, listOf(entry)).single().items.single()
        assertEquals("file", Uri.parse(item.thumbUrl).scheme)
        assertEquals(entry.coverPath, Uri.parse(item.thumbUrl).path)
        assertEquals(entry.backdropPath, Uri.parse(item.artUrl).path)
        assertEquals(1994, item.year)
        assertEquals(entry.title, item.title)
        assertEquals(1.5f, item.gameCoverAspectRatio!!, 0.0001f)
        val hero = item.toHomeHeroContent()
        assertEquals("games:${item.ratingKey}", hero.key)
        assertEquals("SNES", hero.providerLabel)
    }

    @Test fun missingCoverUsesNativeFallbackWithSystemGeometryAndNoFakeImageUrl() {
        val def = listOf(HomeRowDefinition(system = HomeRowDefinition.SYSTEM_GAMES,
            contentKey = HomeRowDefinition.GAMES_ALL))
        val items = gameHomeRows(RuntimeEnvironment.getApplication(), def,
            listOf(game("Western SNES", "snes", false, 0L), game("NES", "nes", false, 0L)))
            .single().items
        assertTrue(items.all { it.thumbUrl == null })
        assertNull(items.first().artUrl)
        assertTrue(items.first { it.title == "Western SNES" }.gameCoverAspectRatio!! > 1f)
        assertTrue(items.first { it.title == "NES" }.gameCoverAspectRatio!! < 1f)
    }

    @Test fun homeCacheRoundTripPreservesGeometryAndOlderEntriesRemainReadable() {
        val def = listOf(HomeRowDefinition(system = HomeRowDefinition.SYSTEM_GAMES,
            contentKey = HomeRowDefinition.GAMES_ALL))
        val item = gameHomeRows(RuntimeEnvironment.getApplication(), def,
            listOf(game("Cover", "snes", false, 0L).copy(coverWidth = 441, coverHeight = 639)))
            .single().items.single()
        val serializer = com.ambient.tv.domain.model.MediaItem.serializer()
        val json = kotlinx.serialization.json.Json
        val restored = json.decodeFromString(serializer, json.encodeToString(serializer, item))
        assertEquals(item.gameCoverAspectRatio, restored.gameCoverAspectRatio)
        val old = item.copy(gameCoverAspectRatio = null)
        val oldPayload = json.encodeToString(serializer, old)
        assertFalse(oldPayload.contains("gameCoverAspectRatio"))
        assertNull(json.decodeFromString(serializer, oldPayload).gameCoverAspectRatio)
    }
}
