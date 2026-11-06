package com.ambient.tv.car

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Browsable metadata only: the service resolves opaque IDs through its authenticated catalog. */
internal fun CarAudioEntry.toMedia3Item(): MediaItem {
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setSubtitle(subtitle)
        .setIsPlayable(playable)
        .setIsBrowsable(!playable)
    // Never publish authenticated Plex/Jellyfin/Emby stream or artwork URLs to car clients.
    artworkUrl?.takeIf { it.startsWith("https://", ignoreCase = true) }
        ?.let { metadata.setArtworkUri(Uri.parse(it)) }
    return MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(metadata.build())
        .build()
}
