package com.ambient.tv.ui.home

import android.content.Context
import android.net.Uri
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.games.GameSystems
import com.ambient.tv.games.library.LibraryGame
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.displayTitle

/** Local indexed games only. No NAS read, library scan or automatic Wake-on-LAN on Home. */
internal const val GAME_HOME_ITEM_PREFIX = "game-home:"

internal fun gameHomeKey(fileId: String, systemId: String): String =
    GAME_HOME_ITEM_PREFIX + Uri.encode(fileId) + "|" + systemId

internal fun gameHomeRows(
    context: Context,
    definitions: List<HomeRowDefinition>,
    games: List<LibraryGame>,
    limit: Int = 24,
): List<ContentRowData> = definitions.filter {
    it.enabled && it.system == HomeRowDefinition.SYSTEM_GAMES
}.distinctBy(HomeRowDefinition::contentKey).mapNotNull { definition ->
    val selected = when (definition.contentKey) {
        HomeRowDefinition.GAMES_ALL -> games
        HomeRowDefinition.GAMES_FAVORITES -> games.filter(LibraryGame::favorite)
        HomeRowDefinition.GAMES_RECENT -> games.filter { it.lastPlayed > 0 }.sortedByDescending(LibraryGame::lastPlayed)
        else -> emptyList()
    }.take(limit)
    if (selected.isEmpty()) return@mapNotNull null
    ContentRowData(
        id = "games.${definition.contentKey}",
        title = definition.displayTitle(context),
        style = MediaTileStyle.POSTER,
        items = selected.map { game ->
            val iconUri = Uri.Builder().scheme("android.resource").authority(context.packageName)
                .appendPath("drawable").appendPath("game_system_${game.systemId}").build().toString()
            MediaItem(
                ratingKey = gameHomeKey(game.fileId, game.systemId),
                title = game.title,
                type = "game",
                index = null,
                thumbUrl = game.coverPath?.let { Uri.fromFile(java.io.File(it)).toString() } ?: iconUri,
                artUrl = game.backdropPath?.let { Uri.fromFile(java.io.File(it)).toString() },
                year = game.metadataYear,
                summary = GameSystems.byId(game.systemId)?.name ?: game.systemId,
                tagline = null,
                durationMs = null,
                viewOffsetMs = null,
                parentTitle = null,
                parentRatingKey = null,
                grandparentTitle = null,
                grandparentRatingKey = null,
                contentRating = null,
                rating = null,
                studio = "Games",
            )
        },
    )
}
