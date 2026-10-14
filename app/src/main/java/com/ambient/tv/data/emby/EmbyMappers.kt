package com.ambient.tv.data.emby

import com.ambient.tv.domain.model.CastMember
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.domain.model.dedupeCastMembers
import java.time.Instant

fun EmbyItem.toLibrarySection(urls: EmbyUrlBuilder) = LibrarySection(
    key = EmbyBackend.id(id),
    title = name,
    type = when (collectionType?.lowercase()) {
        "tvshows" -> "show"
        "music" -> "artist"
        "boxsets" -> "collection"
        "audiobooks" -> "track"
        "mixed" -> "mixed"
        else -> "movie"
    },
    thumbUrl = imageTags["Primary"]?.let { urls.imageUrl(id, "Primary", tag = it) },
    artUrl = backdropImageTags.firstOrNull()?.let { urls.imageUrl(id, "Backdrop", 0, it) },
)

fun EmbyItem.toDomain(urls: EmbyUrlBuilder): MediaItem {
    val domainId = EmbyBackend.id(id)
    val domainType = when (type.lowercase()) {
        "movie" -> "movie"
        "series" -> "show"
        "season" -> "season"
        "episode" -> "episode"
        "audio", "audio_book_chapter" -> "track"
        "musicalbum" -> "album"
        "musicartist", "artist" -> "artist"
        "boxset" -> "collection"
        "musicvideo" -> "movie"
        else -> type.lowercase()
    }
    val primaryTag = imageTags["Primary"]
    val backdropTag = backdropImageTags.firstOrNull()
    val providers = providerIds.map { (provider, value) -> "${provider.lowercase()}://$value" }
    return MediaItem(
        ratingKey = domainId,
        title = name,
        type = domainType,
        index = indexNumber,
        thumbUrl = primaryTag?.let { urls.imageUrl(id, "Primary", tag = it) },
        artUrl = backdropTag?.let { urls.imageUrl(id, "Backdrop", 0, it) },
        parentThumbUrl = (seasonId ?: parentId)?.let { urls.imageUrl(it, "Primary") },
        grandparentThumbUrl = seriesId?.let { urls.imageUrl(it, "Primary", tag = seriesPrimaryImageTag) },
        year = productionYear,
        summary = overview,
        tagline = taglines.firstOrNull(),
        durationMs = runTimeTicks?.div(10_000L),
        viewOffsetMs = userData?.playbackPositionTicks?.div(10_000L),
        parentTitle = seasonName,
        parentIndex = parentIndexNumber,
        parentRatingKey = (seasonId ?: parentId)?.let(EmbyBackend::id),
        grandparentTitle = seriesName,
        grandparentRatingKey = seriesId?.let(EmbyBackend::id),
        contentRating = officialRating,
        rating = communityRating,
        audienceRating = null,
        criticRating = criticRating,
        studio = studios.firstOrNull()?.name,
        viewCount = userData?.let { if (it.played) maxOf(1, it.playCount) else it.playCount },
        leafCount = recursiveItemCount ?: childCount,
        viewedLeafCount = watchedLeafCount(),
        originallyAvailableAt = premiereDate?.take(10),
        addedAt = dateCreated.toEpochSeconds(),
        lastViewedAt = userData?.lastPlayedDate.toEpochSeconds(),
        guid = providers.firstOrNull(),
        guids = providers,
        linkedRatingKeys = listOf(domainId),
        genres = genres,
        directors = people.filter { it.type.equals("Director", true) }.map { it.name },
        writers = people.filter { it.type.equals("Writer", true) }.map { it.name },
        collections = emptyList(),
        cast = people.filter { it.type.equals("Actor", true) || it.type.equals("GuestStar", true) }.map {
            CastMember(
                id = it.id?.let(EmbyBackend::id),
                name = it.name,
                role = it.role,
                thumbUrl = urls.personImageUrl(it.id, it.primaryImageTag),
            )
        }.dedupeCastMembers(),
        logoUrl = logoSource()?.let { (itemId, tag) -> urls.logoUrl(itemId, tag) },
        mediaVersions = mediaSources.mapIndexed { index, source -> source.toDomain(domainId, index, urls) }.ifEmpty {
            if (width != null || height != null) {
                listOf(
                    MediaVersion(
                        streamUrl = null,
                        resolution = height?.let { "${it}p" },
                        videoCodec = null,
                        audioCodec = null,
                        container = null,
                        width = width,
                        height = height,
                    ),
                )
            } else emptyList()
        },
    )
}

/** Episodes already played, from the server's unplayed count; null when it did not send one. */
internal fun EmbyItem.watchedLeafCount(): Int? {
    // ChildCount counts seasons, so only the recursive count can be set against unplayed episodes.
    val total = recursiveItemCount ?: return null
    val unplayed = userData?.unplayedItemCount ?: return null
    return (total - unplayed).coerceIn(0, total)
}

internal fun EmbyItem.logoSource(): Pair<String, String>? {
    imageTags.entries.firstOrNull { it.key.equals("Logo", ignoreCase = true) }
        ?.value?.takeIf { it.isNotBlank() }
        ?.let { return id to it }
    val parentId = parentLogoItemId?.takeIf { it.isNotBlank() } ?: return null
    val parentTag = parentLogoImageTag?.takeIf { it.isNotBlank() } ?: return null
    return parentId to parentTag
}

private fun EmbyMediaSource.toDomain(itemId: String, mediaIndex: Int, urls: EmbyUrlBuilder): MediaVersion {
    val videoStream = mediaStreams.firstOrNull { it.type.equals("Video", true) }
    return MediaVersion(
    streamUrl = urls.directStreamUrl(itemId, this),
    resolution = height?.let { "${it}p" },
    videoCodec = videoCodec ?: videoStream?.codec,
    audioCodec = audioCodec ?: mediaStreams.firstOrNull { it.type.equals("Audio", true) }?.codec,
    container = container,
    bitrateKbps = bitrate?.div(1000),
    width = width,
    height = height,
    videoFrameRate = null,
    videoDynamicRange = videoStream?.inferredVideoDynamicRange(),
    tracks = mediaStreams.map { stream ->
        MediaTrack(
            id = stream.index?.toLong(),
            type = when {
                stream.type.equals("Video", true) -> MediaTrackType.VIDEO
                stream.type.equals("Audio", true) -> MediaTrackType.AUDIO
                stream.type.equals("Subtitle", true) -> MediaTrackType.SUBTITLE
                else -> MediaTrackType.UNKNOWN
            },
            codec = stream.codec,
            language = stream.language,
            languageCode = stream.language,
            title = stream.displayTitle ?: stream.title,
            channels = stream.channels,
            selected = stream.isDefault,
            forced = stream.isForced,
            externalUrl = if (stream.isExternal && stream.index != null) urls.externalSubtitleUrl(itemId, id, stream.index) else stream.deliveryUrl?.let(urls::absolute),
            bitDepth = stream.bitDepth,
            profile = stream.profile,
        )
    },
    sourceRatingKey = itemId,
    sourceMediaIndex = mediaIndex,
    sourceId = id,
    )
}

internal fun EmbyMediaStream.inferredVideoDynamicRange(): String? {
    val descriptor = listOfNotNull(
        videoRangeType, videoRange, videoDoViTitle, colorTransfer, colorPrimaries, colorSpace, profile,
    ).joinToString(" ").lowercase()

    if (dvProfile != null || "dovi" in descriptor || "dolby vision" in descriptor) {
        val titleProfile = Regex("(?i)profile\\s*(\\d+(?:\\.\\d+)?)").find(videoDoViTitle.orEmpty())?.groupValues?.getOrNull(1)
        val profileLabel = when {
            !titleProfile.isNullOrBlank() -> " P$titleProfile"
            dvProfile == 8 && dvBlSignalCompatibilityId != null -> " P8.${dvBlSignalCompatibilityId}"
            dvProfile != null -> " P$dvProfile"
            else -> ""
        }
        return "Dolby Vision$profileLabel"
    }
    if (hdr10PlusPresentFlag == true || "hdr10plus" in descriptor || "hdr10+" in descriptor) return "HDR10+"
    if ("hlg" in descriptor || "arib-std-b67" in descriptor) return "HLG"
    if ("hdr10" in descriptor || "smpte2084" in descriptor || "st2084" in descriptor || " pq" in " $descriptor") return "HDR10"
    if ("hdr" in descriptor) return "HDR"
    return null
}

private fun String?.toEpochSeconds(): Long? = this?.let { value ->
    runCatching { Instant.parse(value).epochSecond }.getOrNull()
}
