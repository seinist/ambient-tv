package com.ambient.tv.ui.player

import androidx.annotation.StringRes
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.livetv.LiveTvChannel
import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.livetv.LiveTvRecording
import com.ambient.tv.ui.common.MediaSystem
import com.ambient.tv.ui.common.formatDuration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How the dialog draws the artwork column, matching the detail page of each kind. */
internal enum class PlayerDetailArtShape {
    /** 2:3 film / series poster. */
    POSTER,
    /** Album cover or station logo. */
    SQUARE,
    /** 16:9 YouTube thumbnail or EPG still. */
    WIDE,
    /** Channel logo: fitted, never cropped. */
    LOGO,
}

/** One labelled text line below the description (e.g. "Als Nächstes"). */
internal data class PlayerDetailLine(@StringRes val label: Int, val value: String)

/**
 * Everything the details dialog over the player shows. Built from data the player already has;
 * nothing here triggers a request. [item] carries the full metadata of films, episodes and music
 * so the dialog can reuse the detail page's badges, stream chips, credits and cast.
 */
internal data class PlayerDetailInfo(
    val osdPlayer: OsdPlayer,
    val title: String,
    val logoUrl: String? = null,
    val artworkUrl: String? = null,
    val artworkFallbackUrls: List<String> = emptyList(),
    val artworkHeaders: Map<String, String> = emptyMap(),
    val artShape: PlayerDetailArtShape = PlayerDetailArtShape.POSTER,
    /** Line above the title; null lets an [item] use the detail page's own line. */
    val eyebrow: String? = null,
    val meta: List<String> = emptyList(),
    val live: Boolean = false,
    val tagline: String? = null,
    val overview: String? = null,
    val genres: List<String> = emptyList(),
    val lines: List<PlayerDetailLine> = emptyList(),
    /** Simple technical chips for sources without an [item] (radio codec and bitrate). */
    val chips: List<String> = emptyList(),
    val item: MediaItem? = null,
    val system: MediaSystem? = null,
)

/** YouTube descriptions can be very long; the dialog shows the beginning. */
internal const val PLAYER_DETAIL_OVERVIEW_MAX_CHARS = 3_000

internal fun capPlayerDetailText(text: String?, max: Int = PLAYER_DETAIL_OVERVIEW_MAX_CHARS): String? {
    val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.length <= max) return trimmed
    val cut = trimmed.substring(0, max)
    val space = cut.lastIndexOf(' ').takeIf { it > max * 3 / 4 } ?: cut.length
    return cut.substring(0, space).trimEnd() + " …"
}

private val MUSIC_TYPES = setOf("track", "album", "artist", "audio")

internal fun isMusicDetailType(type: String?): Boolean = type?.lowercase() in MUSIC_TYPES

/** The cover the detail page shows for this item: series poster for episodes, else the item's own. */
internal fun detailCoverUrl(item: MediaItem): String? =
    (if (item.type == "episode") item.posterArtUrl else item.thumbUrl ?: item.parentThumbUrl)
        ?.takeIf { it.isNotBlank() }

/** Films, episodes, files, DLNA and music from the media player state. */
internal fun playerDetailForMedia(state: PlayerUiState.Ready): PlayerDetailInfo = playerDetailForMedia(
    item = state.item,
    fallback = PlayerDetailMediaFallback(
        title = state.title,
        mediaType = state.mediaType,
        posterArtUrl = state.posterArtUrl,
        artworkUrl = state.artworkUrl,
        panelArtworkUrl = state.panelArtworkUrl,
        parentTitle = state.parentTitle,
        grandparentTitle = state.grandparentTitle,
        seasonIndex = state.seasonIndex,
        episodeIndex = state.episodeIndex,
        year = state.year,
        summary = state.summary,
        tagline = state.tagline,
        durationMs = state.durationMs,
        streamHeaders = state.streamHeaders,
    ),
    selectedVersionIndex = state.selectedVersionIndex,
    sourceRatingKey = state.sourceRatingKey,
)

/** What the player state knows when there is no full [MediaItem] (files, DLNA, a resumed session). */
internal data class PlayerDetailMediaFallback(
    val title: String,
    val mediaType: String,
    val posterArtUrl: String? = null,
    val artworkUrl: String? = null,
    val panelArtworkUrl: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    val seasonIndex: Int? = null,
    val episodeIndex: Int? = null,
    val year: Int? = null,
    val summary: String? = null,
    val tagline: String? = null,
    val durationMs: Long? = null,
    val streamHeaders: Map<String, String> = emptyMap(),
)

internal fun playerDetailForMedia(
    item: MediaItem?,
    fallback: PlayerDetailMediaFallback,
    selectedVersionIndex: Int = 0,
    sourceRatingKey: String = item?.ratingKey.orEmpty(),
): PlayerDetailInfo {
    val type = item?.type ?: fallback.mediaType
    val music = isMusicDetailType(type)
    // Stream chips and the file name describe the version that is actually playing.
    val playing = item?.let {
        val version = it.mediaVersions.getOrNull(selectedVersionIndex) ?: it.mediaVersions.firstOrNull()
        it.copy(mediaVersions = listOfNotNull(version))
    }
    val cover = item?.let(::detailCoverUrl) ?: fallback.posterArtUrl?.takeIf { it.isNotBlank() }
        ?: fallback.artworkUrl?.takeIf { it.isNotBlank() }
    val eyebrow = when {
        music -> listOfNotNull(
            (item?.grandparentTitle ?: fallback.grandparentTitle)?.takeIf { it.isNotBlank() },
            (item?.parentTitle ?: fallback.parentTitle)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        item == null && type == "episode" -> listOfNotNull(
            fallback.grandparentTitle?.takeIf { it.isNotBlank() },
            episodeCode(fallback.seasonIndex, fallback.episodeIndex),
        ).joinToString(" · ").takeIf { it.isNotBlank() }
        else -> null
    }
    return PlayerDetailInfo(
        osdPlayer = OsdPlayer.MEDIA,
        title = item?.title ?: fallback.title,
        logoUrl = item?.logoUrl,
        artworkUrl = cover,
        artworkFallbackUrls = listOfNotNull(fallback.artworkUrl, fallback.panelArtworkUrl)
            .filter { it.isNotBlank() && it != cover }.distinct(),
        artworkHeaders = fallback.streamHeaders.takeIf { item == null }.orEmpty(),
        artShape = if (music) PlayerDetailArtShape.SQUARE else PlayerDetailArtShape.POSTER,
        eyebrow = eyebrow,
        meta = listOfNotNull(
            (item?.year ?: fallback.year)?.toString(),
            formatDuration(item?.durationMs ?: fallback.durationMs),
        ),
        tagline = (item?.tagline ?: fallback.tagline)?.takeIf { it.isNotBlank() },
        overview = capPlayerDetailText(item?.summary ?: fallback.summary),
        genres = item?.genres.orEmpty().take(5),
        item = playing,
        system = mediaSystemForDetail(sourceRatingKey),
    )
}

private fun episodeCode(season: Int?, episode: Int?): String? = when {
    season != null && episode != null -> "S$season E$episode"
    episode != null -> "E$episode"
    else -> null
}

private fun mediaSystemForDetail(ratingKey: String): MediaSystem? =
    com.ambient.tv.connectivity.PlaybackServerUnreachable.mediaSystemForRatingKey(ratingKey)
        ?.takeIf { it == MediaSystem.PLEX || it == MediaSystem.JELLYFIN || it == MediaSystem.EMBY }

/** YouTube: channel, description and thumbnail of the playing video. */
internal fun playerDetailForYouTube(
    title: String,
    author: String?,
    channelTitle: String?,
    description: String?,
    imageUrl: String?,
    fallbackImageUrls: List<String>,
    isLive: Boolean,
    durationMs: Long,
): PlayerDetailInfo {
    val cover = imageUrl?.takeIf { it.isNotBlank() } ?: fallbackImageUrls.firstOrNull()
    return PlayerDetailInfo(
        osdPlayer = OsdPlayer.YOUTUBE,
        title = title,
        artworkUrl = cover,
        artworkFallbackUrls = fallbackImageUrls.filter { it != cover },
        artShape = PlayerDetailArtShape.WIDE,
        eyebrow = (channelTitle ?: author)?.takeIf { it.isNotBlank() },
        meta = if (isLive) emptyList() else listOfNotNull(formatDuration(durationMs)),
        live = isLive,
        overview = capPlayerDetailText(description),
        system = MediaSystem.YOUTUBE,
    )
}

private fun timeRange(start: Long, stop: Long, zone: ZoneId): String {
    val format = DateTimeFormatter.ofPattern("HH:mm")
    val from = Instant.ofEpochSecond(start).atZone(zone).format(format)
    val to = Instant.ofEpochSecond(stop).atZone(zone).format(format)
    return "$from–$to"
}

/** Live TV: the running programme from the EPG, with the next one below; or a recording. */
internal fun playerDetailForLiveTv(
    channel: LiveTvChannel?,
    now: LiveTvProgramme?,
    next: LiveTvProgramme?,
    recording: LiveTvRecording?,
    zone: ZoneId = ZoneId.systemDefault(),
): PlayerDetailInfo? {
    val channelLine = channel?.let { listOfNotNull(it.numberLabel, it.name).joinToString("  ") }
        ?.takeIf { it.isNotBlank() }
    if (recording != null) {
        val image = recording.imageUrl ?: recording.fanartUrl
        return PlayerDetailInfo(
            osdPlayer = OsdPlayer.LIVE_TV,
            title = recording.title,
            artworkUrl = image ?: channel?.iconUrl,
            artShape = if (image != null) PlayerDetailArtShape.WIDE else PlayerDetailArtShape.LOGO,
            eyebrow = channelLine ?: recording.channelName.takeIf { it.isNotBlank() },
            meta = listOfNotNull(
                timeRange(recording.startEpochSeconds, recording.stopEpochSeconds, zone),
                formatDuration((recording.stopEpochSeconds - recording.startEpochSeconds) * 1000L),
            ),
            tagline = recording.subtitle?.takeIf { it.isNotBlank() },
            overview = capPlayerDetailText(recording.description),
            system = MediaSystem.TVHEADEND,
        )
    }
    if (channel == null && now == null) return null
    val image = now?.imageUrl?.takeIf { it.isNotBlank() }
    return PlayerDetailInfo(
        osdPlayer = OsdPlayer.LIVE_TV,
        title = now?.title ?: channel?.name.orEmpty(),
        artworkUrl = image ?: channel?.iconUrl,
        artworkFallbackUrls = listOfNotNull(channel?.iconUrl).filter { image != null },
        artShape = if (image != null) PlayerDetailArtShape.WIDE else PlayerDetailArtShape.LOGO,
        eyebrow = channelLine,
        meta = now?.let {
            listOfNotNull(
                timeRange(it.startEpochSeconds, it.stopEpochSeconds, zone),
                formatDuration(it.durationSeconds * 1000L),
            )
        }.orEmpty(),
        live = true,
        tagline = now?.subtitle?.takeIf { it.isNotBlank() },
        overview = capPlayerDetailText(now?.description ?: now?.summary),
        lines = listOfNotNull(next?.let {
            PlayerDetailLine(R.string.player_detail_next,
                "${timeRange(it.startEpochSeconds, it.stopEpochSeconds, zone)}  ${it.title}")
        }),
        system = MediaSystem.TVHEADEND,
    )
}

/** Radio: station, current song and stream facts. */
internal fun playerDetailForRadio(
    stationName: String,
    nowPlaying: String?,
    artworkUrl: String?,
    artworkFallbackUrls: List<String>,
    artworkHeaders: Map<String, String>,
    stationInfo: String?,
    tags: List<String>,
    codec: String?,
    bitrateKbps: Int?,
): PlayerDetailInfo = PlayerDetailInfo(
    osdPlayer = OsdPlayer.RADIO,
    title = stationName,
    artworkUrl = artworkUrl,
    artworkFallbackUrls = artworkFallbackUrls.filter { it != artworkUrl },
    artworkHeaders = artworkHeaders,
    artShape = PlayerDetailArtShape.SQUARE,
    eyebrow = stationInfo?.takeIf { it.isNotBlank() },
    live = true,
    lines = listOfNotNull(nowPlaying?.trim()?.takeIf { it.isNotEmpty() }?.let {
        PlayerDetailLine(R.string.player_detail_now_playing, it)
    }),
    genres = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5),
    chips = listOfNotNull(
        codec?.trim()?.takeIf { it.isNotEmpty() }?.uppercase(),
        bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" },
    ),
    system = MediaSystem.RADIO,
)

/** Short post texts that fit the title line are not repeated as a description. */
internal const val TELEGRAM_DETAIL_REPEAT_MAX_CHARS = 60

private val TELEGRAM_FORMAT_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "mp3", "flac", "ogg", "opus", "m4a", "wav")

/**
 * Telegram (Monogram): only what the post itself carries — channel, date, runtime, picture size,
 * file and the full post text. Telegram has no year, genres, cast or ratings, so those stay empty
 * and the dialog hides their sections.
 */
internal fun playerDetailForTelegram(
    media: com.ambient.tv.data.telegram.TelegramMedia,
    channel: com.ambient.tv.data.telegram.TelegramChannel?,
    formatDate: (Long) -> String,
    formatSize: (Long) -> String,
    playerDurationMs: Long = 0L,
): PlayerDetailInfo {
    val kind = media.kind
    val cover = media.previewUrl?.takeIf { it.isNotBlank() }
    val channelImage = channel?.takeIf { it.chatId == media.chatId }?.thumbnailUrl?.takeIf { it.isNotBlank() }
    val visual = kind == com.ambient.tv.data.telegram.TelegramMediaKind.VIDEO ||
        kind == com.ambient.tv.data.telegram.TelegramMediaKind.PHOTO
    val width = media.width.takeIf { visual && it > 0 }
    val height = media.height.takeIf { visual && it > 0 }
    val shape = when {
        cover == null -> PlayerDetailArtShape.LOGO
        kind == com.ambient.tv.data.telegram.TelegramMediaKind.AUDIO -> PlayerDetailArtShape.SQUARE
        width != null && height != null && height > width -> PlayerDetailArtShape.POSTER
        else -> PlayerDetailArtShape.WIDE
    }
    val title = media.title.trim().ifEmpty { media.channel }
    val text = media.summary?.trim()?.takeIf { it.isNotEmpty() }
    // The title is the beginning of the post text; a short post would only repeat it.
    val overview = text?.takeUnless { it == title && it.length <= TELEGRAM_DETAIL_REPEAT_MAX_CHARS && '\n' !in it }
    val duration = media.durationMs.takeIf { it > 0 }
        ?: playerDurationMs.takeIf { it > 0 && kind != com.ambient.tv.data.telegram.TelegramMediaKind.PHOTO }
    val fileName = media.fileName?.trim()?.takeIf { it.isNotEmpty() && it != title }
    val resolution = playerResolutionLabel(width, height)
    val exactSize = if (width != null && height != null) "${width}×${height}" else null
    val format = media.fileName?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it in TELEGRAM_FORMAT_EXTENSIONS }?.uppercase()
    return PlayerDetailInfo(
        osdPlayer = OsdPlayer.MEDIA,
        title = title,
        artworkUrl = cover ?: channelImage,
        artworkFallbackUrls = listOfNotNull(channelImage).filter { cover != null && it != cover },
        artShape = shape,
        eyebrow = (channel?.takeIf { it.chatId == media.chatId }?.title ?: media.channel).takeIf { it.isNotBlank() },
        meta = listOfNotNull(
            media.publishedAtMs.takeIf { it > 0 }?.let(formatDate),
            formatDuration(duration),
        ),
        overview = capPlayerDetailText(overview),
        lines = listOfNotNull(fileName?.let { PlayerDetailLine(R.string.photos_info_file, it) }),
        chips = listOfNotNull(
            resolution,
            exactSize?.takeIf { it != resolution },
            format,
            media.fileSize.takeIf { it > 0 }?.let(formatSize),
        ),
        system = MediaSystem.TELEGRAM,
    )
}
