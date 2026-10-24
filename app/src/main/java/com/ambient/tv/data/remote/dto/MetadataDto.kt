package com.ambient.tv.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MetadataResponse(
    @SerialName("MediaContainer") val container: MetadataContainer,
)

@Serializable
data class MetadataContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    val offset: Int? = null,
    @SerialName("Metadata") val metadata: List<MetadataDto> = emptyList(),
)

@Serializable
data class MetadataDto(
    val ratingKey: String,
    val key: String,
    val title: String,
    val type: String,
    /** Extras only: "trailer", "behindTheScenes", … and Plex's numeric extra type (1 = trailer). */
    val subtype: String? = null,
    val extraType: Int? = null,
    val index: Int? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val grandparentThumb: String? = null,
    val art: String? = null,
    val parentArt: String? = null,
    val grandparentArt: String? = null,
    val clearLogo: String? = null,
    val theme: String? = null,
    val parentTheme: String? = null,
    val grandparentTheme: String? = null,
    val year: Int? = null,
    val summary: String? = null,
    val tagline: String? = null,
    val duration: Long? = null,
    val viewOffset: Long? = null,
    val parentTitle: String? = null,
    val parentIndex: Int? = null,
    val parentRatingKey: String? = null,
    val grandparentTitle: String? = null,
    val grandparentRatingKey: String? = null,
    val contentRating: String? = null,
    val rating: Double? = null,
    val audienceRating: Double? = null,
    val studio: String? = null,
    val viewCount: Int? = null,
    val leafCount: Int? = null,
    val viewedLeafCount: Int? = null,
    val originallyAvailableAt: String? = null,
    val addedAt: Long? = null,
    val lastViewedAt: Long? = null,
    val guid: String? = null,
    @SerialName("Guid") val guids: List<GuidDto> = emptyList(),
    @SerialName("Marker") val markers: List<PlaybackMarkerDto> = emptyList(),
    @SerialName("Chapter") val chapters: List<ChapterDto> = emptyList(),
    @SerialName("Media") val media: List<MediaDto> = emptyList(),
    @SerialName("Role") val roles: List<RoleDto> = emptyList(),
    @SerialName("Genre") val genres: List<TagDto> = emptyList(),
    @SerialName("Director") val directors: List<TagDto> = emptyList(),
    @SerialName("Writer") val writers: List<TagDto> = emptyList(),
    @SerialName("Collection") val collections: List<TagDto> = emptyList(),
    @SerialName("Image") val images: List<ImageDto> = emptyList(),
)

@Serializable
data class ImageDto(
    val alt: String? = null,
    val type: String? = null,
    val url: String? = null,
)

@Serializable
data class MediaDto(
    val id: Long? = null,
    val duration: Long? = null,
    val bitrate: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val videoResolution: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val container: String? = null,
    val videoFrameRate: String? = null,
    val videoDynamicRange: String? = null,
    @SerialName("Part") val parts: List<PartDto> = emptyList(),
)

@Serializable
data class PartDto(
    val indexes: String? = null,
    val id: Long? = null,
    val key: String? = null,
    val duration: Long? = null,
    val file: String? = null,
    val container: String? = null,
    @SerialName("Stream") val streams: List<StreamDto> = emptyList(),
)

@Serializable
data class StreamDto(
    val id: Long? = null,
    val streamType: Int? = null,
    val codec: String? = null,
    val language: String? = null,
    val languageCode: String? = null,
    val displayTitle: String? = null,
    val title: String? = null,
    val channels: Int? = null,
    val bitDepth: Int? = null,
    val profile: String? = null,
    val colorSpace: String? = null,
    val colorTrc: String? = null,
    val colorPrimaries: String? = null,
    @SerialName("DOVIPresent") val doviPresent: Boolean? = null,
    @SerialName("DOVIProfile") val doviProfile: Int? = null,
    @SerialName("DOVIBLCompatID") val doviBlCompatId: Int? = null,
    val selected: Boolean? = null,
    val forced: Boolean? = null,
    val key: String? = null,
) {
    val inferredVideoDynamicRange: String?
        get() {
            val descriptor = listOfNotNull(profile, colorSpace, colorTrc, colorPrimaries).joinToString(" ").lowercase()
            return when {
                doviPresent == true || doviProfile != null -> {
                    val profileLabel = when {
                        doviProfile == 8 && doviBlCompatId != null -> " P8.${doviBlCompatId}"
                        doviProfile != null -> " P${doviProfile}"
                        else -> ""
                    }
                    "Dolby Vision$profileLabel"
                }
                "hdr10+" in descriptor || "hdr10plus" in descriptor -> "HDR10+"
                "arib-std-b67" in descriptor || "hlg" in descriptor -> "HLG"
                "smpte2084" in descriptor || "st2084" in descriptor || "pq" in descriptor || "hdr10" in descriptor -> "HDR10"
                else -> null
            }
        }
}

@Serializable
data class RoleDto(
    val id: Long? = null,
    val tag: String,
    val role: String? = null,
    val thumb: String? = null,
)

@Serializable
data class TagDto(
    val id: Long? = null,
    val tag: String,
)

@Serializable
data class GuidDto(
    val id: String,
)

@Serializable
data class ChapterDto(
    val startTimeOffset: Long = -1,
    val endTimeOffset: Long? = null,
    val tag: String? = null,
    val title: String? = null,
    val thumb: String? = null,
)

@Serializable
data class PlaybackMarkerDto(val type: String = "", val startTimeOffset: Long = -1, val endTimeOffset: Long = -1)
