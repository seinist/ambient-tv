package com.ambient.tv.youtube

data class YouTubeAccountProfile(val name: String?, val avatarUrl: String?)

@kotlinx.serialization.Serializable
data class YouTubeItem(
    val videoId: String,
    val title: String,
    val subtitle: String? = null,
    val author: String? = null,
    val imageUrl: String? = null,
    val backgroundUrl: String? = null,
    val durationMs: Long = 0,
    val percentWatched: Int = 0,
    val isLive: Boolean = false,
    val isUpcoming: Boolean = false,
    val isShorts: Boolean = false,
    val channelId: String? = null,
    val scheduledStartMs: Long? = null,
)

@kotlinx.serialization.Serializable
data class YouTubeRow(val title: String, val items: List<YouTubeItem>, val id: String = title)

@kotlinx.serialization.Serializable
data class YouTubeMetadata(
    val videoId: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val backgroundUrl: String? = null,
    val durationMs: Long = 0,
    val percentWatched: Int = 0,
    val isLive: Boolean = false,
    val suggestions: List<YouTubeRow> = emptyList(),
    val chapters: List<com.ambient.tv.domain.model.VideoChapter> = emptyList(),
)

data class YouTubePlayback(
    val videoId: String,
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val backgroundUrl: String? = null,
    val uri: String,
    val mimeType: String? = null,
    /** Muxed googlevideo URL. VLC 4's adaptive demuxer SIGSEGVs on YouTube DASH/HLS. */
    val progressiveUri: String? = null,
    /**
     * Best fitting video-only and original audio-only googlevideo URLs ([youtubeAdaptivePair]).
     * VLC and MPV play these through [YouTubeStreamRelay]; neither can use the generated MPD.
     */
    val adaptiveVideoUri: String? = null,
    val adaptiveAudioUri: String? = null,
    /** All adaptive formats, for the quality and language choices in the player panel. */
    val adaptiveFormats: List<YouTubeAdaptiveFormat> = emptyList(),
    /** Generated MPD still being built for VLC/MPV sessions; Media3 waits for it before opening. */
    val deferredDash: kotlinx.coroutines.Deferred<String?>? = null,
    val isLive: Boolean = false,
    val durationMs: Long = 0,
    val resumeMs: Long = 0,
    val sponsorSegments: List<LongRange> = emptyList(),
    val timeline: com.ambient.tv.domain.model.PlaybackTimeline = com.ambient.tv.domain.model.PlaybackTimeline(),
    val headers: Map<String, String> = emptyMap(),
)

/** Stable fallback for watch-next metadata, which does not expose the current video artwork. */
fun youtubeThumbnailUrl(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

/**
 * Ordered panel-artwork fallbacks. maxresdefault is normally 1280x720, but it is not present for
 * every upload; the shared ArtworkImage fallback chain transparently drops to SD/HQ when needed.
 */
fun youtubePanelThumbnailUrls(videoId: String): List<String> = listOf(
    "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg",
    "https://i.ytimg.com/vi/$videoId/sddefault.jpg",
    youtubeThumbnailUrl(videoId),
)
