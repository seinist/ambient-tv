package com.ambient.tv.car

import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.net.Uri

/** Platform browse items share the existing PlaybackService's MediaSession token. */
internal fun CarAudioEntry.toBrowserItem(): MediaBrowser.MediaItem {
    val description = MediaDescription.Builder()
        .setMediaId(id)
        .setTitle(title)
        .setSubtitle(subtitle)
    // Never publish authenticated Plex/Jellyfin/Emby stream or artwork URLs to car clients.
    artworkUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
        ?.let { description.setIconUri(Uri.parse(it)) }
    return MediaBrowser.MediaItem(
        description.build(),
        if (playable) MediaBrowser.MediaItem.FLAG_PLAYABLE else MediaBrowser.MediaItem.FLAG_BROWSABLE,
    )
}
