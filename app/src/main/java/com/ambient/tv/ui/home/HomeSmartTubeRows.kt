package com.ambient.tv.ui.home

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow

/** Home video tiles use the regular ContentRow UI, but never enter Plex's detail resolver. */
internal const val HOME_YOUTUBE_ITEM_PREFIX = "home-youtube:"

internal fun homeYouTubeVideoId(ratingKey: String): String? =
    ratingKey.takeIf { it.startsWith(HOME_YOUTUBE_ITEM_PREFIX) }
        ?.removePrefix(HOME_YOUTUBE_ITEM_PREFIX)?.takeIf(String::isNotBlank)

/**
 * One configured Home row represents one SmartTube feed. Preserve the source row's real ID,
 * so the player's next/previous and continuation stay in that row, not in a different feed.
 * Channels are not playable video tiles and belong on SmartTube's channel page instead.
 */
internal fun homeYouTubeSourceRow(rows: List<YouTubeRow>, limit: Int): YouTubeRow? =
    rows.firstNotNullOfOrNull { source ->
        source.copy(items = source.items.asSequence()
            .filterNot { it.isChannel }
            .distinctBy { it.videoId }
            .take(limit.coerceAtLeast(0))
            .toList()).takeIf { it.items.isNotEmpty() }
    }

internal fun YouTubeItem.asHomeMediaItem(): MediaItem = MediaItem(
    ratingKey = HOME_YOUTUBE_ITEM_PREFIX + videoId,
    title = title,
    type = "clip",
    index = null,
    thumbUrl = imageUrl,
    artUrl = backgroundUrl ?: imageUrl,
    year = null,
    summary = subtitle ?: author,
    tagline = author,
    durationMs = durationMs.takeIf { it > 0 },
    viewOffsetMs = if (!isLive && durationMs > 0 && percentWatched > 0)
        durationMs * percentWatched.coerceIn(0, 100) / 100 else null,
    parentTitle = null,
    parentRatingKey = null,
    grandparentTitle = null,
    grandparentRatingKey = null,
    contentRating = null,
    rating = null,
    studio = "SmartTube",
    viewCount = if (percentWatched >= 95) 1 else null,
)
