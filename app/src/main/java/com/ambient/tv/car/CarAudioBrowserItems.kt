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
    // Live radio uses our read-only provider so HTTP, authenticated and ticketed logos work
    // without exposing their server URLs. Other providers retain their public HTTPS artwork.
    artworkUrl?.let(Uri::parse)?.takeIf {
        it.scheme.equals("https", ignoreCase = true) || CarAudioArtwork.mediaId(it) != null
    }?.let(metadata::setArtworkUri)
    return MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(metadata.build())
        .build()
}
