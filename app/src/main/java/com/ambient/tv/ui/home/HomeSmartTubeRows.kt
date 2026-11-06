package com.ambient.tv.ui.home

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.youtube.YouTubeItem
import com.ambient.tv.youtube.YouTubeRow
import com.ambient.tv.youtube.YouTubeLocalProgress
import com.ambient.tv.youtube.YOUTUBE_FINISHED_PERCENT
import com.ambient.tv.ui.common.ContentRowData

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

internal fun YouTubeItem.asHomeMediaItem(): MediaItem {
    if (!isLive && !isUpcoming) YouTubeLocalProgress.observeCached(videoId, percentWatched)
    val percentWatched = if (isLive || isUpcoming) 0 else YouTubeLocalProgress.percent(videoId, percentWatched)
    return MediaItem(
    ratingKey = HOME_YOUTUBE_ITEM_PREFIX + videoId,
    title = title,
    type = "clip",
    index = null,
    thumbUrl = imageUrl,
    artUrl = backgroundUrl ?: imageUrl,
    year = null,
    summary = com.ambient.tv.ui.youtube.youtubeCardInfo(this).details.joinToString("  ·  ").takeIf(String::isNotBlank),
    tagline = com.ambient.tv.ui.youtube.youtubeCardInfo(this).channel,
    // Null denotes a live/upcoming item; zero is a VOD whose duration is not known yet.
    durationMs = if (isLive || isUpcoming) null else durationMs.coerceAtLeast(0L),
    viewOffsetMs = if (durationMs > 0 && percentWatched in 1 until YOUTUBE_FINISHED_PERCENT)
        durationMs * percentWatched.coerceIn(0, 100) / 100 else null,
    parentTitle = null,
    parentRatingKey = null,
    grandparentTitle = null,
    grandparentRatingKey = null,
    contentRating = null,
    rating = null,
    studio = "SmartTube",
    viewCount = if (percentWatched >= YOUTUBE_FINISHED_PERCENT) 1 else null,
    watchedOverride = percentWatched >= YOUTUBE_FINISHED_PERCENT,
    )
}

/** Read during composition so Home's cached rows update without fetching or rebuilding a feed. */
internal fun ContentRowData.withHomeYouTubeProgress(): ContentRowData {
    if (!id.startsWith("youtube.")) return this
    val updated = items.map { item ->
        val videoId = homeYouTubeVideoId(item.ratingKey) ?: return@map item
        val cardDuration = item.durationMs ?: return@map item
        val duration = cardDuration.takeIf { it > 0L } ?: YouTubeLocalProgress.durationMs(videoId) ?: 0L
        val cachedPercent = if ((item.viewCount ?: 0) > 0) 100 else
            if (duration > 0L) ((item.viewOffsetMs ?: 0L).toDouble() / duration * 100).toInt().coerceIn(0, 100) else 0
        val percent = YouTubeLocalProgress.percent(videoId, cachedPercent)
        item.copy(
            durationMs = duration,
            viewOffsetMs = if (duration > 0L && percent in 1 until YOUTUBE_FINISHED_PERCENT) duration * percent / 100 else null,
            viewCount = if (percent >= YOUTUBE_FINISHED_PERCENT) 1 else null,
            watchedOverride = percent >= YOUTUBE_FINISHED_PERCENT,
        )
    }
    return if (updated == items) this else copy(items = updated)
}
