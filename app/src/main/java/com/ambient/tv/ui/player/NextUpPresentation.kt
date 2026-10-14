package com.ambient.tv.ui.player

import com.ambient.tv.domain.model.MediaItem

internal fun MediaItem.toNextUpCandidate(source: String, headers: Map<String, String>): NextUpCandidate = NextUpCandidate(
    mediaId = ratingKey,
    title = title,
    subtitle = if (type == "episode") grandparentTitle.orEmpty() else "",
    artworkUrl = if (type == "episode") thumbUrl else artUrl,
    artworkHeaders = headers,
    source = source,
    mediaType = type,
    season = parentIndex.takeIf { type == "episode" },
    episode = index.takeIf { type == "episode" },
    year = year,
    durationMs = durationMs,
    contentRating = contentRating,
    posterUrl = if (type == "episode") parentThumbUrl else thumbUrl,
    posterFallbackUrl = grandparentThumbUrl.takeIf { type == "episode" },
)

internal data class NextUpArtwork(val url: String, val poster: Boolean)
internal fun NextUpCandidate.artworkChoices(): List<NextUpArtwork> {
    val landscape = artworkUrl?.takeIf(String::isNotBlank)?.let { NextUpArtwork(it, false) }
    val posters = listOfNotNull(posterUrl, posterFallbackUrl).filter(String::isNotBlank).map { NextUpArtwork(it, true) }
    return (if (mediaType == "movie") posters + listOfNotNull(landscape)
        else listOfNotNull(landscape) + posters).distinctBy { it.url }
}

internal enum class NextUpCountdown { COUNTDOWN, DISABLED, CONFIRMATION, READY }
internal fun nextUpCountdown(autoplay: Boolean, stillWatching: Boolean, secondsRemaining: Int): NextUpCountdown = when {
    stillWatching -> NextUpCountdown.CONFIRMATION
    !autoplay -> NextUpCountdown.DISABLED
    secondsRemaining <= 0 -> NextUpCountdown.READY
    else -> NextUpCountdown.COUNTDOWN
}
