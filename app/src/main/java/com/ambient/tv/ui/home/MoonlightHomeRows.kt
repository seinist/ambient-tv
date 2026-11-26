package com.ambient.tv.ui.home

import android.content.Context
import android.net.Uri
import com.ambient.tv.data.local.HomeRowDefinition
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.moonlight.MoonlightLibraryStore
import com.ambient.tv.moonlight.MoonlightHost
import com.ambient.tv.ui.common.ContentRowData
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.displayTitle

/** The key is intentionally distinct from the local Libretro game-home key. */
internal const val MOONLIGHT_HOME_ITEM_PREFIX = "moonlight-home:"

internal fun moonlightHomeKey(hostId: String, appId: Int): String =
    MOONLIGHT_HOME_ITEM_PREFIX + Uri.encode(hostId) + ":" + appId

internal fun parseMoonlightHomeKey(key: String): Pair<String, Int>? {
    if (!key.startsWith(MOONLIGHT_HOME_ITEM_PREFIX)) return null
    val rest = key.removePrefix(MOONLIGHT_HOME_ITEM_PREFIX)
    val colon = rest.lastIndexOf(':')
    if (colon <= 0) return null
    val id = Uri.decode(rest.substring(0, colon))
    val app = rest.substring(colon + 1).toIntOrNull() ?: return null
    return if (com.ambient.tv.moonlight.validMoonlightHostId(id) && app > 0) id to app else null
}

/** No network/wake on Home. Only the cached catalog identity-validated by MoonlightLibraryStore. */
internal fun moonlightHomeRows(
    context: Context,
    definitions: List<HomeRowDefinition>,
    hosts: List<MoonlightHost>,
    store: MoonlightLibraryStore,
    limit: Int = 24,
): List<ContentRowData> {
    val favorites = store.favorites()
    val recent = store.recent().withIndex().associate { it.value to it.index }
    val all = hosts.flatMap { host ->
        store.catalog(host.id)?.apps.orEmpty().map { app ->
            Triple(host, app, host.id + ":" + app.id)
        }
    }
    return definitions.filter { it.enabled && it.system == HomeRowDefinition.SYSTEM_MOONLIGHT }
        .distinctBy(HomeRowDefinition::contentKey).mapNotNull { row ->
            val games = when (row.contentKey) {
                HomeRowDefinition.GAMES_ALL -> all.sortedBy { it.second.title.lowercase() }
                HomeRowDefinition.GAMES_FAVORITES -> all.filter { it.third in favorites }
                    .sortedBy { it.second.title.lowercase() }
                HomeRowDefinition.GAMES_RECENT -> all.filter { it.third in recent }
                    .sortedBy { recent[it.third] }
                else -> emptyList()
            }.take(limit)
            if (games.isEmpty()) return@mapNotNull null
            ContentRowData(
                id = "moonlight." + row.contentKey,
                title = row.displayTitle(context),
                style = MediaTileStyle.POSTER,
                items = games.map { (host, app, _) ->
                    val artwork = store.artworkInfo(host.id, app.id)
                    MediaItem(
                        ratingKey = moonlightHomeKey(host.id, app.id),
                        title = app.title,
                        type = "game",
                        index = null,
                        thumbUrl = artwork?.file?.let {
                            Uri.fromFile(it).toString()
                        },
                        artUrl = null,
                        year = null,
                        summary = host.name.ifBlank { host.host },
                        tagline = null,
                        durationMs = null,
                        viewOffsetMs = null,
                        parentTitle = null,
                        parentRatingKey = null,
                        grandparentTitle = null,
                        grandparentRatingKey = null,
                        contentRating = null,
                        rating = null,
                        studio = "Moonlight",
                        gameCoverAspectRatio = artwork?.aspectRatio ?: 2f / 3f,
                    )
                },
            )
        }
}
