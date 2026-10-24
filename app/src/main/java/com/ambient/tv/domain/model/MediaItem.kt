package com.ambient.tv.domain.model

@kotlinx.serialization.Serializable
data class MediaItem(
    val ratingKey: String,
    val title: String,
    val type: String,
    val index: Int?,
    val thumbUrl: String?,
    val artUrl: String?,
    val year: Int?,
    val summary: String?,
    val tagline: String?,
    val durationMs: Long?,
    val viewOffsetMs: Long?,
    val parentTitle: String?,
    val parentIndex: Int? = null,
    val parentRatingKey: String?,
    val grandparentTitle: String?,
    val grandparentRatingKey: String?,
    val contentRating: String?,
    val rating: Double?,
    val audienceRating: Double? = null,
    val criticRating: Double? = null,
    val studio: String?,
    val viewCount: Int? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    val originallyAvailableAt: String? = null,
    val addedAt: Long? = null,
    val lastViewedAt: Long? = null,
    val guid: String? = null,
    val guids: List<String> = emptyList(),
    val linkedRatingKeys: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val collections: List<MediaTag> = emptyList(),
    val cast: List<CastMember> = emptyList(),
    val mediaVersions: List<MediaVersion> = emptyList(),
    val parentThumbUrl: String? = null,
    val grandparentThumbUrl: String? = null,
    /** Theme song URL delivered with the metadata (Plex); other providers resolve it on demand. */
    val themeMusicUrl: String? = null,
    /** Transparent title treatment (ClearLogo / Logo) when the provider supplied one. */
    val logoUrl: String? = null,
    /** Wide backdrop of the show/season/album this item belongs to (episodes and seasons often have none of their own). */
    val parentBackdropUrl: String? = null,
) {
    val allRatingKeys: List<String>
        get() = (linkedRatingKeys + ratingKey).distinct()

    val canonicalContentKey: String
        get() {
            val normalizedIds = (guids + listOfNotNull(guid))
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
            val preferred = listOf("imdb://", "tmdb://", "tvdb://", "plex://")
                .firstNotNullOfOrNull { prefix -> normalizedIds.firstOrNull { it.startsWith(prefix) } }
            if (preferred != null) return "$type:$preferred"

            return when (type) {
                "episode" -> listOf(
                    type,
                    grandparentTitle.orEmpty().trim().lowercase(),
                    (parentIndex ?: -1).toString(),
                    (index ?: -1).toString(),
                ).joinToString(":")
                else -> listOf(type, title.trim().lowercase(), (year ?: -1).toString()).joinToString(":")
            }
        }

    val progressFraction: Float?
        get() {
            val offset = viewOffsetMs ?: return null
            val total = durationMs ?: return null
            if (total <= 0) return null
            return (offset.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        }

    val remainingMs: Long?
        get() {
            val total = durationMs ?: return null
            val offset = viewOffsetMs ?: return total
            return (total - offset).coerceAtLeast(0L)
        }

    val isWatched: Boolean
        get() {
            // A show or season is watched once every episode is. Plex sets viewCount on the show
            // as soon as a single episode has been played, so it cannot answer that.
            val total = leafCount
            val watchedLeaves = viewedLeafCount
            if (total != null && total > 0 && watchedLeaves != null) return watchedLeaves >= total
            return (viewCount ?: 0) > 0 || ((progressFraction ?: 0f) >= 0.9f)
        }

    val unwatchedLeafCount: Int?
        get() {
            val total = leafCount ?: return null
            val watched = viewedLeafCount ?: 0
            return (total - watched).coerceAtLeast(0)
        }

    val streamUrl: String? get() = mediaVersions.firstOrNull()?.streamUrl
    val videoResolution: String? get() = mediaVersions.firstOrNull()?.resolution

    /**
     * Wide background for full-screen pages: the item's own backdrop, else its show's/season's,
     * else a landscape still (episodes, clips); a portrait poster only when nothing wide exists.
     */
    val landscapeBackdropUrl: String?
        get() = wideBackdropUrl ?: thumbUrl

    /** A wide image for the background, or null when only a poster exists. */
    val wideBackdropUrl: String?
        get() = artUrl ?: parentBackdropUrl ?: thumbUrl?.takeIf { type in setOf("episode", "clip") }

    /** Vertical cover for poster tiles: series art for episodes/seasons, otherwise the item thumb. */
    val posterArtUrl: String?
        get() = when (type) {
            "episode", "season" -> grandparentThumbUrl ?: parentThumbUrl ?: thumbUrl
            else -> thumbUrl
        }

    /** Label for a poster tile that represents a series via an in-progress episode. */
    val posterTitle: String
        get() = when (type) {
            "episode" -> grandparentTitle?.takeIf { it.isNotBlank() } ?: title
            "season" -> parentTitle?.takeIf { it.isNotBlank() } ?: title
            else -> title
        }
}

@kotlinx.serialization.Serializable
data class MediaVersion(
    val streamUrl: String?,
    val resolution: String?,
    val videoCodec: String?,
    val audioCodec: String?,
    val container: String?,
    val bitrateKbps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoFrameRate: String? = null,
    val videoDynamicRange: String? = null,
    val tracks: List<MediaTrack> = emptyList(),
    val sourceRatingKey: String? = null,
    val sourceMediaIndex: Int = 0,
    val sourceId: String? = null,
    val fileName: String? = null,
) {
    val selectedAudioTrack: MediaTrack?
        get() = tracks.firstOrNull { it.type == MediaTrackType.AUDIO && it.selected }
            ?: tracks.firstOrNull { it.type == MediaTrackType.AUDIO }

    val selectedSubtitleTrack: MediaTrack?
        get() = tracks.firstOrNull { it.type == MediaTrackType.SUBTITLE && it.selected }

    val pixelCount: Long
        get() = (width?.toLong() ?: 0L) * (height?.toLong() ?: 0L)
}

enum class MediaTrackType { VIDEO, AUDIO, SUBTITLE, UNKNOWN }

@kotlinx.serialization.Serializable
data class MediaTrack(
    val id: Long?,
    val type: MediaTrackType,
    val codec: String?,
    val language: String?,
    val languageCode: String?,
    val title: String?,
    val channels: Int?,
    val selected: Boolean,
    val forced: Boolean,
    val externalUrl: String?,
    val bitDepth: Int? = null,
    val profile: String? = null,
)

/** A title's theme song. [id] is the same on every page of one show; [url] need not be. */
@kotlinx.serialization.Serializable
data class ThemeMusic(
    val id: String,
    val url: String,
)

@kotlinx.serialization.Serializable
data class MediaTag(
    /** Provider-owned id. Plex uses numeric ids, Jellyfin uses GUID strings. */
    val id: String?,
    val name: String,
)

@kotlinx.serialization.Serializable
data class CastMember(
    /** Provider-owned id. Keeping this as a string prevents lossy Jellyfin GUID conversion. */
    val id: String?,
    val name: String,
    val role: String?,
    val thumbUrl: String?,
)

/**
 * Plex (and merged multi-version or multi-backend metadata) can list the same person more than
 * once, often as a bare entry without portrait or role ahead of the real one. Keep one tile per
 * actor: the most complete entry (portrait, then role, then first appearance), at its own position.
 */
fun List<CastMember>.dedupeCastMembers(): List<CastMember> {
    if (size <= 1) return this
    fun CastMember.completeness() = (if (!thumbUrl.isNullOrBlank()) 2 else 0) + (if (!role.isNullOrBlank()) 1 else 0)
    val best = LinkedHashMap<String, IndexedValue<CastMember>>()
    withIndex().forEach { entry ->
        val key = entry.value.name.trim().lowercase()
        val current = best[key]
        if (current == null || entry.value.completeness() > current.value.completeness()) best[key] = entry
    }
    return best.values.sortedBy { it.index }.map { it.value }
}
