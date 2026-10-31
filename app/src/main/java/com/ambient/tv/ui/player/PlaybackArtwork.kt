package com.ambient.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.common.ArtworkImage

/** The native proportions of the best artwork Plex normally exposes for each media type. */
fun playbackArtworkAspectRatio(mediaType: String): Float = when (mediaType.lowercase()) {
    "movie", "show", "season" -> 2f / 3f
    "track", "audio", "album", "artist" -> 1f
    else -> 16f / 9f
}

internal data class MiniPlayerArtwork(
    val url: String?,
    val fallbacks: List<String>,
    val aspectRatio: Float,
)

/** Mini player uses the series cover for episodes; episode stills stay fallback-only. */
internal fun miniPlayerArtwork(
    mediaType: String,
    posterArtUrl: String?,
    thumbUrl: String?,
    panelArtworkUrl: String?,
    panelFallbacks: List<String> = emptyList(),
): MiniPlayerArtwork {
    val poster = posterArtUrl?.takeIf { it.isNotBlank() }
    val thumb = thumbUrl?.takeIf { it.isNotBlank() }
    val episodeCover = mediaType.equals("episode", ignoreCase = true) && poster != null && poster != thumb
    val url = when {
        episodeCover -> poster
        else -> thumb ?: poster ?: panelArtworkUrl?.takeIf { it.isNotBlank() }
    }
    val fallbacks = buildList {
        if (episodeCover) thumb?.let(::add)
        panelArtworkUrl?.takeIf { it.isNotBlank() }?.let(::add)
        addAll(panelFallbacks)
    }.filter { it != url }.distinct()
    val aspectRatio = if (episodeCover) 2f / 3f else playbackArtworkAspectRatio(mediaType)
    return MiniPlayerArtwork(url, fallbacks, aspectRatio)
}

/** A missing URL is not a reason to omit an artwork tile when it has a real fallback. */
internal fun shouldDrawPlaybackArtworkCard(
    artworkUrl: String?,
    fallbackUrls: List<String>,
    hasFallbackContent: Boolean,
): Boolean = !artworkUrl.isNullOrBlank() || fallbackUrls.any(String::isNotBlank) || hasFallbackContent

@Composable
fun PlaybackArtworkCard(
    artworkUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    headers: Map<String, String> = emptyMap(),
    contentScale: ContentScale = ContentScale.Crop,
    fallbackUrls: List<String> = emptyList(),
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    fallbackContent: (@Composable () -> Unit)? = null,
) {
    if (!shouldDrawPlaybackArtworkCard(artworkUrl, fallbackUrls, fallbackContent != null)) return
    Box(
        modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
    ) {
        ArtworkImage(
            model = artworkUrl,
            title = title,
            modifier = Modifier.fillMaxSize(),
            headers = headers,
            contentScale = contentScale,
            fallbackModels = fallbackUrls,
            fallbackContent = fallbackContent,
        )
    }
}
