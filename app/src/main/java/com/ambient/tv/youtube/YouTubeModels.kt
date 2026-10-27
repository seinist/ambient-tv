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
    /** Subscribed-channel tiles: the channel uploaded since it was last opened. */
    val hasNewContent: Boolean = false,
) {
    /** Channel tiles carry [youtubeChannelKey] as videoId, so rows, focus and paging keep one key. */
    val isChannel: Boolean get() = youtubeChannelIdOf(videoId) != null
}

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
    val channelId: String? = null,
    val authorImageUrl: String? = null,
    val subscribed: Boolean = false,
    /** YouTube's own texts, e.g. "1.234.567 Aufrufe" and "vor 3 Tagen". */
    val viewCount: String? = null,
    val publishedDate: String? = null,
    val likeCount: String? = null,
    val likeStatus: YouTubeLikeStatus = YouTubeLikeStatus.NONE,
)

/** MediaItemMetadata.LIKE_STATUS_* */
@kotlinx.serialization.Serializable
enum class YouTubeLikeStatus { NONE, LIKE, DISLIKE }

/** Channel header of the channel page and the channel actions of details and player. */
data class YouTubeChannelInfo(
    val channelId: String,
    val title: String? = null,
    val avatarUrl: String? = null,
    /** Null while unknown (signed out, or not yet loaded). */
    val subscribed: Boolean? = null,
)

private const val CHANNEL_KEY_PREFIX = "channel:"

/** Item key of a channel tile; YouTube video ids never contain ':'. */
fun youtubeChannelKey(channelId: String): String = CHANNEL_KEY_PREFIX + channelId

fun youtubeChannelIdOf(key: String): String? =
    key.takeIf { it.startsWith(CHANNEL_KEY_PREFIX) }?.removePrefix(CHANNEL_KEY_PREFIX)?.takeIf(String::isNotBlank)

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
    /** Caption tracks; VLC and MPV load them as external subtitles (Media3 reads them from the MPD). */
    val captions: List<YouTubeCaption> = emptyList(),
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

private val CHANNEL_ID_IN_PARAMS = Regex("UC[A-Za-z0-9_-]{22}")

/** Channel id embedded in browse params (URL-encoded base64 protobuf), as on subscribed-channel tabs. */
fun youtubeChannelIdInBrowseParams(params: String): String? = runCatching {
    val base64 = java.net.URLDecoder.decode(params, "UTF-8").replace('-', '+').replace('_', '/')
    val bytes = java.util.Base64.getDecoder().decode(base64.padEnd((base64.length + 3) / 4 * 4, '='))
    CHANNEL_ID_IN_PARAMS.find(String(bytes, Charsets.ISO_8859_1))?.value
}.getOrNull()
