package com.ambient.tv.car

import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.net.Uri

/** Platform browse items share the existing PlaybackService's MediaSession token. */
internal fun CarAudioEntry.toBrowserItem(): MediaBrowserCompat.MediaItem {
    val description = MediaDescriptionCompat.Builder()
        .setMediaId(id)
        .setTitle(title)
        .setSubtitle(subtitle)
    // Never publish authenticated Plex/Jellyfin/Emby stream or artwork URLs to car clients.
    artworkUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
        ?.let { description.setIconUri(Uri.parse(it)) }
    return MediaBrowserCompat.MediaItem(
        description.build(),
        if (playable) MediaBrowserCompat.MediaItem.FLAG_PLAYABLE else MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
    )
}
