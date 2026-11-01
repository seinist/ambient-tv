package com.ambient.tv.ui.common

import com.ambient.tv.ui.theme.screenGutter
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.CastMember
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.mobile.DeviceActionRow
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp

/*
 * Building blocks of the detail page's header and credits, shared with the player's details
 * dialog so both look the same. Moved from DetailScreen without changing how they draw.
 */

/** Music artwork is usually square on both Plex and Jellyfin. */
fun detailArtworkIsSquare(type: String?): Boolean = type in setOf("album", "artist", "track")

/** Poster / cover with the detail page's size, corner and hairline border. */
@Composable
fun DetailPosterArtwork(
    item: MediaItem,
    model: Any,
) {
    val square = detailArtworkIsSquare(item.type)
    val width = if (LocalHandheldUi.current) 144.dp else if (square) 210.dp else 190.dp
    DetailArtworkFrame(
        model = model,
        title = item.title,
        aspectRatio = if (square) 1f else 2f / 3f,
        width = width,
    )
}

@Composable
fun DetailArtworkFrame(
    model: Any?,
    title: String,
    aspectRatio: Float,
    width: Dp,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackModels: List<Any?> = emptyList(),
    headers: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    ArtworkImage(
        model = model,
        title = title,
        contentScale = contentScale,
        fallbackModels = fallbackModels,
        headers = headers,
        modifier = modifier
            .width(width)
            .aspectRatio(aspectRatio)
            .clip(shape)
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape),
    )
}

/** Series · season · episode for episodes, the show for seasons, otherwise the studio. */
@Composable
fun detailEyebrow(item: MediaItem): String = when (item.type) {
    "episode" -> listOfNotNull(item.grandparentTitle, item.parentTitle, item.index?.let { stringResource(R.string.detail_episode_number, it) }).joinToString(" · ")
    "season" -> item.parentTitle.orEmpty()
    else -> item.studio.orEmpty()
}

@Composable
fun DetailEyebrow(text: String) {
    if (text.isNotBlank()) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The media system for a library item's badge. */
fun detailMediaSystem(ratingKey: String): MediaSystem = when {
    com.ambient.tv.data.emby.EmbyBackend.owns(ratingKey) -> MediaSystem.EMBY
    com.ambient.tv.data.jellyfin.JellyfinBackend.owns(ratingKey) -> MediaSystem.JELLYFIN
    else -> MediaSystem.PLEX
}

/** Source mark and title/clear logo share one line, like the Home hero. */
@Composable
fun DetailTitleRow(
    title: String,
    logoUrl: String?,
    system: MediaSystem?,
    maxLogoWidth: Dp = deviceDp(520.dp, 280.dp),
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        system?.let { SystemBadge(it) }
        TitleLogo(
            title = title,
            logoUrl = logoUrl,
            textStyle = MaterialTheme.typography.headlineLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            maxLogoWidth = maxLogoWidth,
            lockSlotToSingleLine = true,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Year, runtime and (for shows) the episode count. */
@Composable
fun detailPrimaryMeta(item: MediaItem): List<String> = buildList {
    item.year?.let { add(it.toString()) }
    formatDuration(item.durationMs)?.let(::add)
    if (item.type == "show") item.leafCount?.let { add(stringResource(R.string.detail_episode_count, it)) }
}

/** Human metadata followed by the age rating and score badges of [item]. */
@Composable
fun DetailPrimaryMetaRow(
    primaryMeta: List<String>,
    item: MediaItem?,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val hasRatings = item != null && (!item.contentRating.isNullOrBlank() || item.rating != null ||
        item.audienceRating != null || item.criticRating != null)
    if (primaryMeta.isEmpty() && !hasRatings && leading == null) return
    DeviceActionRow(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke(this)
        if (primaryMeta.isNotEmpty()) {
            Text(
                text = primaryMeta.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (item != null) {
            item.contentRating?.takeIf { it.isNotBlank() }?.let { ContentRatingBadge(it) }
            if (item.rating != null || item.audienceRating != null || item.criticRating != null) {
                PrimaryRatingScoreBadge(item)
            }
        }
    }
}

@Composable
fun DetailTagline(text: String?) {
    text?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic))
    }
}

@Composable
fun DetailSummary(text: String?, maxLines: Int = 5) {
    text?.takeIf { it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = maxLines)
    }
}

@Composable
fun DetailGenres(genres: List<String>) {
    if (genres.isNotEmpty()) {
        Text(
            genres.take(5).joinToString("  ·  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun detailHasCredits(item: MediaItem): Boolean =
    item.directors.isNotEmpty() || item.writers.isNotEmpty() || item.studio != null || singleVersionFileName(item) != null

/** "More information": directors, writers, studio, release date and the single file's name. */
@Composable
fun DetailCreditsSection(
    item: MediaItem,
    modifier: Modifier = Modifier.padding(horizontal = screenGutter()),
) {
    Column(
        modifier = Modifier.fillMaxWidth().then(modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.detail_more_info), style = MaterialTheme.typography.titleMedium)
        item.directors.takeIf { it.isNotEmpty() }?.let { Text(stringResource(R.string.detail_directors, it.joinToString(", ")), style = MaterialTheme.typography.bodySmall) }
        item.writers.takeIf { it.isNotEmpty() }?.let { Text(stringResource(R.string.detail_writers, it.joinToString(", ")), style = MaterialTheme.typography.bodySmall) }
        item.studio?.let { Text(stringResource(R.string.detail_studio, it), style = MaterialTheme.typography.bodySmall) }
        item.originallyAvailableAt?.let { Text(stringResource(R.string.detail_release_date, it), style = MaterialTheme.typography.bodySmall) }
        // With several versions the picker lists each file; a single file is named here instead.
        singleVersionFileName(item)?.let { name ->
            Text(
                stringResource(R.string.detail_file_name, name),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

fun singleVersionFileName(item: MediaItem): String? =
    item.mediaVersions.singleOrNull()?.fileName?.takeIf { it.isNotBlank() }
