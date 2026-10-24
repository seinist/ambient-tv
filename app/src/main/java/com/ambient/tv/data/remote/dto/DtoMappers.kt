package com.ambient.tv.data.remote.dto

import com.ambient.tv.data.remote.PlexUrlBuilder
import com.ambient.tv.domain.model.CastMember
import com.ambient.tv.domain.model.LibrarySection
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTag
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.domain.model.PlexServerIdentity
import com.ambient.tv.domain.model.dedupeCastMembers

fun IdentityContainer.toDomain() = PlexServerIdentity(
    machineIdentifier = machineIdentifier,
    version = version,
    claimed = claimed,
)

fun DirectoryDto.toDomain(urlBuilder: PlexUrlBuilder) = LibrarySection(
    key = key,
    title = title,
    type = type,
    thumbUrl = urlBuilder.mediaThumbUrl(thumb, type),
    artUrl = urlBuilder.backdropUrl(art),
)

fun MetadataDto.toDomain(urlBuilder: PlexUrlBuilder) = MediaItem(
    ratingKey = ratingKey,
    title = title,
    type = type,
    index = index,
    thumbUrl = urlBuilder.mediaThumbUrl(thumb, type),
    artUrl = urlBuilder.backdropUrl(art),
    parentBackdropUrl = (grandparentArt ?: parentArt)?.let { urlBuilder.backdropUrl(it) },
    year = year,
    summary = summary,
    tagline = tagline,
    durationMs = duration,
    viewOffsetMs = viewOffset,
    parentTitle = parentTitle,
    parentIndex = parentIndex,
    parentRatingKey = parentRatingKey,
    grandparentTitle = grandparentTitle,
    grandparentRatingKey = grandparentRatingKey,
    contentRating = contentRating,
    rating = rating,
    audienceRating = audienceRating,
    studio = studio,
    viewCount = viewCount,
    leafCount = leafCount,
    viewedLeafCount = viewedLeafCount,
    originallyAvailableAt = originallyAvailableAt,
    addedAt = addedAt,
    lastViewedAt = lastViewedAt,
    guid = guid,
    guids = guids.map { it.id },
    linkedRatingKeys = listOf(ratingKey),
    genres = genres.map { it.tag },
    directors = directors.map { it.tag },
    writers = writers.map { it.tag },
    collections = collections.map { MediaTag(it.id?.toString(), it.tag) },
    cast = roles.map { it.toDomain(urlBuilder) }.dedupeCastMembers(),
    mediaVersions = media.mapIndexed { index, version -> version.toDomain(urlBuilder, ratingKey, index) },
    parentThumbUrl = urlBuilder.mediaThumbUrl(parentThumb, "show"),
    grandparentThumbUrl = urlBuilder.mediaThumbUrl(grandparentThumb, "show"),
    // Seasons and episodes carry their show's theme as parentTheme/grandparentTheme.
    themeMusicUrl = urlBuilder.absoluteUrl(theme ?: parentTheme ?: grandparentTheme),
    // Original PNG/WebP: the photo transcoder would flatten ClearLogo transparency to JPEG.
    logoUrl = urlBuilder.absoluteUrl(pickPlexClearLogo(clearLogo, images)),
)

fun MediaDto.toDomain(urlBuilder: PlexUrlBuilder, sourceRatingKey: String, sourceMediaIndex: Int) = MediaVersion(
    streamUrl = parts.firstOrNull()?.key?.let { urlBuilder.absoluteUrl(it) },
    resolution = videoResolution,
    videoCodec = videoCodec,
    audioCodec = audioCodec,
    container = container,
    bitrateKbps = bitrate,
    width = width,
    height = height,
    videoFrameRate = videoFrameRate,
    videoDynamicRange = parts.firstOrNull()?.streams.orEmpty()
        .firstOrNull { it.streamType == 1 }
        ?.inferredVideoDynamicRange
        ?: videoDynamicRange,
    tracks = parts.firstOrNull()?.streams.orEmpty().map { it.toDomain(urlBuilder) },
    sourceRatingKey = sourceRatingKey,
    sourceMediaIndex = sourceMediaIndex,
    fileName = parts.firstOrNull()?.file?.substringAfterLast('/')?.substringAfterLast('\\'),
)

fun StreamDto.toDomain(urlBuilder: PlexUrlBuilder) = MediaTrack(
    id = id,
    type = when (streamType) {
        1 -> MediaTrackType.VIDEO
        2 -> MediaTrackType.AUDIO
        3 -> MediaTrackType.SUBTITLE
        else -> MediaTrackType.UNKNOWN
    },
    codec = codec,
    language = language,
    languageCode = languageCode,
    title = plexStreamTitle(displayTitle, title, streamType == 2),
    channels = channels,
    selected = selected == true,
    forced = forced == true,
    externalUrl = key?.let(urlBuilder::absoluteUrl),
    bitDepth = bitDepth,
    profile = profile,
)

internal fun pickPlexClearLogo(clearLogo: String?, images: List<ImageDto>): String? {
    val typed = images.firstOrNull { image ->
        val type = image.type.orEmpty()
        type.equals("clearLogo", ignoreCase = true) || type.equals("logo", ignoreCase = true)
    }?.url?.takeIf { it.isNotBlank() }
    return typed ?: clearLogo?.takeIf { it.isNotBlank() }
}

fun RoleDto.toDomain(urlBuilder: PlexUrlBuilder) = CastMember(
    id = id?.toString(),
    name = tag,
    role = role,
    thumbUrl = urlBuilder.avatarUrl(thumb),
)

/** Plex displayTitle can omit a Matroska commentary title; keep both for audio role selection. */
internal fun plexStreamTitle(displayTitle: String?, title: String?, audio: Boolean): String? =
    if (!audio) displayTitle ?: title
    else listOfNotNull(displayTitle?.takeIf { it.isNotBlank() }, title?.takeIf { it.isNotBlank() })
        .distinct().joinToString(" · ").takeIf { it.isNotBlank() }
