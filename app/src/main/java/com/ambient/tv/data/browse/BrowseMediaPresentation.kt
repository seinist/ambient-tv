package com.ambient.tv.data.browse

import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTrackType

/**
 * Fields that can visibly change a browse card/row.
 *
 * Provider/playback-only state (stream URLs, source IDs, GUID mappings, cast, collections,
 * theme music, file names, etc.) intentionally stays out. A response may update those in the
 * repository without invalidating every visible card.
 */
internal data class BrowseMediaPresentationKey(
    val title: String,
    val type: String,
    val index: Int?,
    val parentIndex: Int?,
    val thumb: Any?,
    val art: Any?,
    val parentThumb: Any?,
    val grandparentThumb: Any?,
    val parentBackdrop: Any?,
    val logo: Any?,
    val year: Int?,
    val summary: String?,
    val tagline: String?,
    val durationMs: Long?,
    val viewOffsetMs: Long?,
    val parentTitle: String?,
    val grandparentTitle: String?,
    val contentRating: String?,
    val rating: Double?,
    val audienceRating: Double?,
    val criticRating: Double?,
    val viewCount: Int?,
    val leafCount: Int?,
    val viewedLeafCount: Int?,
    val originallyAvailableAt: String?,
    val addedAt: Long?,
    val watchedOverride: Boolean?,
    val technical: List<BrowseTechnicalVersionKey>,
)

internal data class BrowseTechnicalVersionKey(
    val resolution: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val dynamicRange: String?,
    val tracks: List<BrowseTechnicalTrackKey>,
)

internal data class BrowseTechnicalTrackKey(
    val type: MediaTrackType,
    val codec: String?,
    val channels: Int?,
    val selected: Boolean,
    val profile: String?,
)

internal fun MediaItem.browsePresentationKey(): BrowseMediaPresentationKey =
    BrowseMediaPresentationKey(
        title = title,
        type = type,
        index = index,
        parentIndex = parentIndex,
        thumb = artworkRequestIdentity(thumbUrl),
        art = artworkRequestIdentity(artUrl),
        parentThumb = artworkRequestIdentity(parentThumbUrl),
        grandparentThumb = artworkRequestIdentity(grandparentThumbUrl),
        parentBackdrop = artworkRequestIdentity(parentBackdropUrl),
        logo = artworkRequestIdentity(logoUrl),
        year = year,
        summary = summary,
        tagline = tagline,
        durationMs = durationMs,
        viewOffsetMs = viewOffsetMs,
        parentTitle = parentTitle,
        grandparentTitle = grandparentTitle,
        contentRating = contentRating,
        rating = rating,
        audienceRating = audienceRating,
        criticRating = criticRating,
        viewCount = viewCount,
        leafCount = leafCount,
        viewedLeafCount = viewedLeafCount,
        originallyAvailableAt = originallyAvailableAt,
        addedAt = addedAt,
        watchedOverride = watchedOverride,
        technical = mediaVersions.map { version ->
            BrowseTechnicalVersionKey(
                resolution = version.resolution,
                videoCodec = version.videoCodec,
                audioCodec = version.audioCodec,
                dynamicRange = version.videoDynamicRange,
                tracks = version.tracks.map { track ->
                    BrowseTechnicalTrackKey(
                        type = track.type,
                        codec = track.codec,
                        channels = track.channels,
                        selected = track.selected,
                        profile = track.profile,
                    )
                },
            )
        },
    )
