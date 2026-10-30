package com.ambient.tv.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.ConnectedTv
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme

// These catalogs describe the item, not its visibility. Settings can therefore show
// the same symbol as navigation even while that item is hidden from the menu.
internal fun youtubeFeedIcon(key: String): ImageVector = when (key) {
    "home" -> Icons.Filled.Home
    "subscriptions" -> Icons.Filled.Subscriptions
    "channels" -> Icons.Filled.AccountCircle
    "history" -> Icons.Filled.History
    "playlists" -> Icons.AutoMirrored.Filled.PlaylistPlay
    "shorts" -> Icons.Filled.SmartDisplay
    "music" -> Icons.Filled.MusicNote
    "news" -> Icons.Filled.Newspaper
    "gaming" -> Icons.Filled.SportsEsports
    "live" -> Icons.Filled.LiveTv
    "kids" -> Icons.Filled.ChildCare
    else -> Icons.Filled.SmartDisplay
}

internal fun spotifyMenuIcon(key: String): ImageVector = when (key) {
    "liked" -> Icons.Filled.Favorite
    "playlists" -> Icons.AutoMirrored.Filled.PlaylistPlay
    "albums" -> Icons.Filled.Album
    "artists" -> Icons.Filled.Person
    "now_playing" -> Icons.Filled.PlayArrow
    else -> Icons.Filled.Home
}

internal fun radioMenuIcon(key: String): ImageVector = when (key) {
    "favorites" -> Icons.Filled.Favorite
    "recent" -> Icons.Filled.History
    "genres" -> Icons.Filled.MusicNote
    "countries" -> Icons.Filled.Public
    "languages" -> Icons.Filled.Language
    else -> Icons.AutoMirrored.Filled.TrendingUp
}

internal fun liveTvMenuIcon(key: String): ImageVector = when (key) {
    "guide", "timers" -> Icons.Filled.Schedule
    "recordings" -> Icons.Filled.FiberManualRecord
    "hbbtv" -> Icons.Filled.ConnectedTv
    else -> Icons.Filled.Tv
}

internal fun librarySectionIcon(type: String): ImageVector = when (type) {
    "movie" -> Icons.Filled.Movie
    "show" -> Icons.Filled.Tv
    "artist" -> Icons.Filled.LibraryMusic
    "photo" -> Icons.Filled.PhotoLibrary
    else -> Icons.Filled.VideoLibrary
}

/** Decorative identity beside an item's existing label; never a separate focus target. */
@Composable
internal fun SettingsItemIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
}
