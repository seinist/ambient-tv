package com.ambient.tv.data.repository

import com.ambient.tv.domain.model.MediaItem

/**
 * Plex's On Deck for servers that list resumable titles and next episodes separately: a finished
 * episode hands over to the next one. A next episode was never played itself, so it takes the
 * date its series was last watched from [playedEpisodes].
 */
internal fun continueWatching(
    resume: List<MediaItem>,
    nextUp: List<MediaItem>,
    playedEpisodes: List<MediaItem>,
): List<MediaItem> {
    val resumeKeys = resume.mapTo(HashSet()) { it.ratingKey }
    val seriesInProgress = resume.mapNotNullTo(HashSet()) { it.grandparentRatingKey }
    val seriesPlayedAt = HashMap<String, Long>()
    playedEpisodes.forEach { episode ->
        val series = episode.grandparentRatingKey ?: return@forEach
        val playedAt = episode.lastViewedAt ?: return@forEach
        if (playedAt > (seriesPlayedAt[series] ?: 0L)) seriesPlayedAt[series] = playedAt
    }
    val upcoming = nextUp
        .filter { it.ratingKey !in resumeKeys && it.grandparentRatingKey !in seriesInProgress }
        .distinctBy { it.grandparentRatingKey ?: it.ratingKey }
    return (resume.map { it to (it.lastViewedAt ?: 0L) } + upcoming.map { it to (seriesPlayedAt[it.grandparentRatingKey] ?: 0L) })
        .sortedByDescending { it.second }
        .map { it.first }
}
