package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.domain.model.MediaVersion
import com.ambient.tv.ui.mobile.LocalHandheldUi

/**
 * Plex-style technical stream properties. These are deliberately icon-led instead of a row of
 * indistinguishable text pills: resolution/display, video/HDR, audio and subtitles each get a
 * stable visual category. The labels still contain the exact PMS values in normalized form.
 */

/**
 * Canonical artwork surface for media cards/thumbnails. Artwork stays clean by default; the old
 * permanent top-right technical overlay can still be opted into for a specialized surface, while
 * normal library cards expose technical highlights only on focus and keep full metadata on detail
 * pages.
 */
@Composable
fun MediaArtworkWithStreamIcons(
    item: MediaItem,
    model: Any?,
    title: String = item.title,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxItems: Int = 3,
    showTechnicalOverlay: Boolean = false,
) {
    Box(modifier = modifier) {
        ArtworkImage(
            model = model,
            title = title,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
            // Pulse on handheld and DLNA so loading tiles stay visible while Coil fetches.
            animateLoading = LocalHandheldUi.current || item.ratingKey.startsWith("dlna:"),
            fallbackIcon = if (item.ratingKey.startsWith("dlna:")) when (item.type) {
                "container" -> Icons.Default.Folder
                "track" -> Icons.Default.AudioFile
                "photo" -> Icons.Default.Image
                else -> Icons.Default.VideoFile
            } else null,
        )
        // Keep library artwork visually quiet. Technical metadata is shown on focus and on the
        // detail page instead of repeating the same codec icons on every cover in a row/grid.
        if (showTechnicalOverlay) {
            StreamPropertyIconRow(
                item = item,
                maxItems = maxItems,
                modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
            )
        }
        com.ambient.tv.companion.PlaybackBadge(com.ambient.tv.companion.playbackIndicator(item),
            Modifier.align(Alignment.BottomStart).padding(7.dp))
    }
}

data class StreamProperty(
    val icon: ImageVector,
    val label: String,
    val emphasized: Boolean = false,
)

@Composable
fun StreamPropertyRow(
    item: MediaItem,
    modifier: Modifier = Modifier,
    maxItems: Int = 5,
) {
    StreamPropertyRow(properties = MediaItemStreamProperties(item), modifier = modifier, maxItems = maxItems)
}

@Composable
fun StreamPropertyRow(
    version: MediaVersion,
    modifier: Modifier = Modifier,
    maxItems: Int = 5,
) {
    StreamPropertyRow(properties = MediaVersionStreamProperties(version), modifier = modifier, maxItems = maxItems)
}

@Composable
fun StreamPropertyRow(
    properties: List<StreamProperty>,
    modifier: Modifier = Modifier,
    maxItems: Int = 5,
) {
    if (properties.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        properties.take(maxItems).forEach { property -> PropertyBadge(property) }
    }
}

/** Compact icon-only technical overlay for artwork cards. Exact values remain available via
 * content descriptions and in text form in list/detail views, while artwork stays visually clean. */
@Composable
fun StreamPropertyIconRow(
    item: MediaItem,
    modifier: Modifier = Modifier,
    maxItems: Int = 3,
) {
    val properties = MediaItemStreamProperties(item)
    if (properties.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        properties.take(maxItems).forEach { property -> PropertyIconBadge(property) }
    }
}

@Composable
private fun PropertyIconBadge(property: StreamProperty) {
    val foreground = if (property.emphasized) MaterialTheme.colorScheme.primary else Color.White
    val border = if (property.emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else ArtworkChipBorder
    Box(
        modifier = Modifier
            .size(26.dp)
            .background(ArtworkChipFill, RoundedCornerShape(7.dp))
            .border(1.dp, border, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = property.icon,
            contentDescription = property.label,
            modifier = Modifier.size(15.dp),
            tint = foreground,
        )
    }
}


/**
 * Readable technical properties for large preview/detail areas. Unlike the card overlay this keeps
 * labels visible, but splits long sets across short rows so codec/audio text can never run past the
 * right edge on 720p/1080p TV density configurations.
 */
@Composable
fun StreamPropertyAdaptiveRows(
    item: MediaItem,
    modifier: Modifier = Modifier,
    maxItems: Int = 5,
    itemsPerRow: Int = 3,
) {
    val properties = MediaItemStreamProperties(item).take(maxItems)
    if (properties.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        properties.chunked(itemsPerRow.coerceAtLeast(1)).forEach { rowProperties ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowProperties.forEach { property -> PropertyBadge(property) }
            }
        }
    }
}

/** Same badges as [StreamPropertyRow], stacked instead of side by side. Used where the row's
 * available width should mostly go to other content (e.g. a list row's description) rather than
 * to a wide strip of horizontally laid-out badges. */
@Composable
fun StreamPropertyColumn(
    item: MediaItem,
    modifier: Modifier = Modifier,
    maxItems: Int = 3,
) {
    StreamPropertyColumn(properties = MediaItemStreamProperties(item), modifier = modifier, maxItems = maxItems)
}

@Composable
fun StreamPropertyColumn(
    properties: List<StreamProperty>,
    modifier: Modifier = Modifier,
    maxItems: Int = 3,
) {
    if (properties.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        properties.take(maxItems).forEach { property -> PropertyBadge(property) }
    }
}

@Composable
private fun PropertyBadge(property: StreamProperty) {
    val background = ArtworkChipFill
    val border = if (property.emphasized) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    } else {
        ArtworkChipBorder
    }
    val foreground = if (property.emphasized) MaterialTheme.colorScheme.primary else Color.White
    Row(
        modifier = Modifier
            .background(background, RoundedCornerShape(6.dp))
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = property.icon,
            contentDescription = property.label,
            modifier = Modifier.size(14.dp),
            tint = foreground,
        )
        Text(
            text = property.label,
            color = foreground,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

fun MediaItemStreamProperties(item: MediaItem): List<StreamProperty> {
    val versions = item.mediaVersions
    if (versions.isEmpty()) return emptyList()

    val resolutionLabels = versions.mapNotNull { formatResolution(it.resolution) }.distinct()
    if (versions.size > 1) {
        // A merged title can represent physically different files. Only advertise values that
        // are true for the logical title as a whole; codec/audio details belong to a selected
        // version and are shown once that version is known (detail version buttons/player info).
        return buildList {
            if (resolutionLabels.isNotEmpty()) {
                add(StreamProperty(Icons.Filled.Tv, resolutionLabels.joinToString(" / "), emphasized = resolutionLabels.any { it == "4K" }))
            }
            val dynamicRanges = versions.mapNotNull { version ->
                val videoTrack = version.tracks.firstOrNull { it.type == MediaTrackType.VIDEO }
                formatHdrMetadata(version.videoDynamicRange, videoTrack?.profile)
            }.distinct()
            if (dynamicRanges.size == 1) add(StreamProperty(Icons.Filled.Movie, dynamicRanges.first(), emphasized = true))
        }
    }

    return MediaVersionStreamProperties(versions.first())
}

fun MediaVersionStreamProperties(version: MediaVersion): List<StreamProperty> {
    val videoTrack = version.tracks.firstOrNull { it.type == MediaTrackType.VIDEO }
    val audio = version.selectedAudioTrack
    val subtitleCount = version.tracks.count { it.type == MediaTrackType.SUBTITLE }
    return buildList {
        formatResolution(version.resolution)?.let {
            add(StreamProperty(Icons.Filled.Tv, it, emphasized = it == "4K"))
        }
        formatHdrMetadata(version.videoDynamicRange, videoTrack?.profile)?.let {
            add(StreamProperty(Icons.Filled.Movie, it, emphasized = true))
        }
        normalizeStreamVideoCodec(version.videoCodec)?.let {
            add(StreamProperty(Icons.Filled.Movie, it))
        }
        val audioCodec = audio?.codec ?: version.audioCodec
        val audioProfile = audio?.profile
        val audioLabel = buildList {
            normalizeStreamAudioCodec(audioCodec, audioProfile)?.let(::add)
            if (isAtmosAudio(audioCodec, audioProfile)) add("ATMOS")
            audio?.channels?.let { add(formatChannels(it)) }
        }.joinToString(" ")
        if (audioLabel.isNotBlank()) add(StreamProperty(Icons.AutoMirrored.Filled.VolumeUp, audioLabel))
        if (subtitleCount > 0) add(StreamProperty(Icons.Filled.Subtitles, "SUB $subtitleCount"))
    }.distinctBy { it.label }
}


private fun isAtmosAudio(codec: String?, profile: String?): Boolean {
    val text = listOfNotNull(codec, profile).joinToString(" ").lowercase()
    return "atmos" in text || "joc" in text
}

private fun formatChannels(channels: Int): String = when (channels) {
    8 -> "7.1"
    6 -> "5.1"
    2 -> "2.0"
    1 -> "1.0"
    else -> "${channels}ch"
}

private fun normalizeStreamVideoCodec(codec: String?): String? = when (codec?.lowercase()) {
    "hevc", "h265" -> "HEVC"
    "h264", "avc" -> "H.264"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    else -> codec?.uppercase()?.takeIf { it.isNotBlank() }
}

private fun normalizeStreamAudioCodec(codec: String?, profile: String?): String? {
    val c = codec?.lowercase().orEmpty()
    val p = profile?.lowercase().orEmpty()
    return when {
        "truehd" in c || "truehd" in p -> "TRUEHD"
        c in setOf("eac3", "e-ac-3", "ec-3") -> "E-AC3"
        c in setOf("ac3", "ac-3") -> "AC3"
        "dts" in c && ("ma" in p || "hd" in p) -> "DTS-HD"
        "dts" in c || c == "dca" -> "DTS"
        c == "aac" -> "AAC"
        c == "opus" -> "OPUS"
        else -> codec?.uppercase()?.takeIf { it.isNotBlank() }
    }
}
