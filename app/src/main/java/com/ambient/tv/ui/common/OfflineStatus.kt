package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.browse.SourceAvailability
import com.ambient.tv.data.browse.SourceHealth

/*
 * Connection status is shown on elements that already exist (hero badges, row titles, page
 * header, sidebar icons). It never adds a layout row, a focus target or a button of its own,
 * so a source going offline or coming back does not move any content.
 */

/** Which sources are unreachable right now; [NONE] outside Home or in previews/tests. */
@Stable
class OfflineMarkers(
    private val offlineSystems: Set<String>,
    private val availability: SourceAvailability?,
    private val revision: Any? = null,
) {
    fun system(system: String): Boolean = system in offlineSystems

    /** Plex/Jellyfin/Emby/… item whose source is confirmed offline (not merely unchecked). */
    fun media(ratingKey: String): Boolean =
        availability?.let { runCatching { it.mediaState(ratingKey) == SourceHealth.OFFLINE }.getOrDefault(false) } == true

    override fun equals(other: Any?): Boolean = other is OfflineMarkers &&
        other.offlineSystems == offlineSystems && other.availability === availability && other.revision == revision

    override fun hashCode(): Int = (offlineSystems.hashCode() * 31 + System.identityHashCode(availability)) * 31 + revision.hashCode()

    companion object {
        val NONE = OfflineMarkers(emptySet(), null)
    }
}

val LocalOfflineMarkers = compositionLocalOf { OfflineMarkers.NONE }

/** Offline label for a page header; null when everything on the page is confirmed. */
val LocalPageOfflineStatus = compositionLocalOf<String?> { null }

/** "Plex · Offline": the old sidebar wording, reused for row titles and page headers. */
@Composable
fun offlineTitle(title: String): String =
    if (title.isBlank()) stringResource(R.string.offline_badge) else "$title · ${stringResource(R.string.offline_badge)}"

/** Hero badge, styled like [WatchedBadge] so it sits on the same metadata baseline. */
@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier = modifier
            .artworkAccentSurface(shape, MaterialTheme.colorScheme.error, borderColor = ArtworkChipBorder, emphasized = true)
            .padding(horizontal = 7.dp, vertical = 4.dp)
            .testTag("offline-badge"),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.offline_badge),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
            maxLines = 1,
        )
    }
}

/** Small dot plus text; never focusable. Used beside existing header actions. */
@Composable
fun OfflineStatusLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.testTag("offline-status"),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
