package com.ambient.tv.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlin.coroutines.coroutineContext

internal enum class YouTubeVodStreamPolicy {
    DASH_FIRST,
    PROGRESSIVE_FIRST,
}

/** A single progressive URL must carry both streams; adaptive video URLs are silent alone. */
internal fun youtubeFormatHasAudioAndVideo(mime: String?): Boolean =
    mime?.startsWith("video/", ignoreCase = true) == true &&
        Regex("(?i)(mp4a|opus|vorbis|aac)").containsMatchIn(mime)

/**
 * Physical devices use SmartTube's generated MPD (the same stream SmartTube's own ExoPlayer
 * plays). YouTube's dashManifestUrl is SABR and must never be selected. Emulators keep the
 * muxed progressive workaround for incomplete virtual VP9/AV1 decoders.
 */
internal fun youtubeVodStreamPolicy(isEmulator: Boolean): YouTubeVodStreamPolicy =
    if (isEmulator) YouTubeVodStreamPolicy.PROGRESSIVE_FIRST else YouTubeVodStreamPolicy.DASH_FIRST

internal fun selectYouTubePlaybackUri(
    isLive: Boolean,
    vodPolicy: YouTubeVodStreamPolicy,
    hls: String?,
    generatedDash: () -> String?,
    direct: () -> String?,
): String? = when {
    isLive -> hls ?: generatedDash() ?: direct()
    vodPolicy == YouTubeVodStreamPolicy.PROGRESSIVE_FIRST -> direct() ?: generatedDash() ?: hls
    else -> generatedDash() ?: hls ?: direct()
}

/** Generated MPD is only useful when it lists googlevideo segments, not just xmlns URIs. */
internal fun youtubeGeneratedDashIsPlayable(xml: String): Boolean =
    xml.contains("<AdaptationSet") && xml.contains("googlevideo.com", ignoreCase = true)

/**
 * Regular urlFormats are muxed itags. Many omit codecs in the mime string, so require a URL
 * that is not a DASH/HLS manifest; prefer an entry that also names an audio codec.
 */
internal fun youtubeMuxedProgressiveUrl(
    urlFormats: List<Pair<String?, String?>>,
    extraUrls: List<String>,
): String? {
    val candidates = urlFormats.mapNotNull { (mime, url) ->
        val value = url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (youtubeAdaptiveStream(value, mime)) null else mime to value
    }
    return candidates.firstOrNull { youtubeFormatHasAudioAndVideo(it.first) }?.second
        ?: candidates.firstOrNull()?.second
        ?: extraUrls.firstOrNull { it.isNotBlank() && !youtubeAdaptiveStream(it, null) }
}

internal fun youtubeAdaptiveStream(uri: String, mimeType: String?): Boolean {
    val mime = mimeType?.lowercase().orEmpty()
    return mime == "application/dash+xml" ||
        mime == "application/x-mpegurl" ||
        mime == "application/vnd.apple.mpegurl" ||
        uri.contains(".mpd", ignoreCase = true) ||
        uri.contains(".m3u8", ignoreCase = true)
}

/**
 * mpv's EDL joins separate video and audio streams into one file (what its ytdl hook does).
 * mpv cannot use the generated MPD: it is a file:// manifest, and ffmpeg's DASH demuxer then
 * refuses the https segment URLs, which leaves a black screen.
 */
internal fun youtubeMpvEdl(videoUrl: String, audioUrl: String): String {
    fun part(url: String) = "!new_stream;!no_clip;!no_chapters;%${url.toByteArray(Charsets.UTF_8).size}%$url"
    return "edl://" + part(videoUrl) + ";" + part(audioUrl)
}

/**
 * What VLC or MPV opens for a YouTube stream. [relayed] sources point at the loopback
 * [ChunkedStreamRelay], which adds the request headers itself; others need [YouTubePlayback.headers].
 */
data class YouTubeExternalSource(val uri: String, val audioUri: String? = null, val relayed: Boolean = true)

/** A googlevideo URL a player can open as one file: not a manifest and not the local MPD. */
private fun youtubeDirectMedia(uri: String?, mimeType: String?): String? =
    uri?.takeIf { it.startsWith("http", ignoreCase = true) && !youtubeAdaptiveStream(it, mimeType) }

/**
 * MPV: the separate video and audio streams joined by an EDL, both through the relay; else the
 * muxed file through the relay. Live keeps HLS, whose segments mpv fetches itself.
 */
fun youtubeMpvSource(playback: YouTubePlayback, relay: (String) -> String): YouTubeExternalSource? {
    if (!playback.isLive) {
        val video = playback.adaptiveVideoUri
        val audio = playback.adaptiveAudioUri
        if (video != null && audio != null) return YouTubeExternalSource(youtubeMpvEdl(relay(video), relay(audio)))
        (playback.progressiveUri ?: youtubeDirectMedia(playback.uri, playback.mimeType))
            ?.let { return YouTubeExternalSource(relay(it)) }
    }
    return playback.uri.takeIf { it.startsWith("http", ignoreCase = true) }
        ?.let { YouTubeExternalSource(it, relayed = false) }
}

/**
 * VLC: the video stream with the audio stream as a slave, both through the relay (VLC needs no
 * muxed file then, which YouTube often no longer offers); else the muxed file. VLC never gets a
 * YouTube DASH/HLS manifest: its adaptive demuxer crashes on them.
 */
fun youtubeVlcSource(playback: YouTubePlayback, relay: (String) -> String): YouTubeExternalSource? {
    if (!playback.isLive) {
        val video = playback.adaptiveVideoUri
        val audio = playback.adaptiveAudioUri
        if (video != null && audio != null) return YouTubeExternalSource(relay(video), relay(audio))
        (playback.progressiveUri ?: youtubeDirectMedia(playback.uri, playback.mimeType))
            ?.let { return YouTubeExternalSource(relay(it)) }
    }
    return youtubeVlcPlaybackUri(playback.uri, playback.mimeType, playback.progressiveUri)
        ?.let { YouTubeExternalSource(it, relayed = false) }
}

/** Original video URL used by VLC, before relaying; metadata must describe this representation. */
internal fun youtubeVlcVideoUri(playback: YouTubePlayback): String? {
    if (!playback.isLive) {
        if (playback.adaptiveVideoUri != null && playback.adaptiveAudioUri != null) return playback.adaptiveVideoUri
        (playback.progressiveUri ?: youtubeDirectMedia(playback.uri, playback.mimeType))?.let { return it }
    }
    return youtubeVlcPlaybackUri(playback.uri, playback.mimeType, playback.progressiveUri)
}

internal fun youtubeVlcVideoMimeType(playback: YouTubePlayback): String? {
    val uri = youtubeVlcVideoUri(playback) ?: return null
    return playback.adaptiveFormats.firstOrNull { it.url == uri }?.mimeType
        ?: playback.mimeType.takeIf { uri == playback.uri && !youtubeAdaptiveStream(uri, playback.mimeType) }
}

/** VLC plays the muxed URL like it used to. Adaptive YouTube manifests crash vlc-adapt-mngr. */
fun youtubeVlcPlaybackUri(uri: String, mimeType: String?, progressiveUri: String?): String? {
    if (!youtubeAdaptiveStream(uri, mimeType)) return uri
    return progressiveUri?.takeIf { it.isNotBlank() && !youtubeAdaptiveStream(it, null) }
}

/**
 * Resume position comes from watch-next percentWatched. A cache hit is folded in before the
 * player starts. A cache miss must not hold the first frame: the playable URI is returned as
 * soon as it exists, and [applyYouTubePlaybackMetadata] runs when watch-next arrives.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun prepareYouTubePlayback(
    cachedMetadata: YouTubeMetadata?,
    loadMetadata: suspend () -> YouTubeMetadata?,
    loadStream: suspend () -> YouTubePlayback,
): YouTubePlayback {
    if (cachedMetadata != null) {
        return applyYouTubePlaybackMetadata(loadStream(), cachedMetadata)
    }
    // Child of the caller (the player ViewModel), not of a coroutineScope that joins before
    // returning. Stream failure still cancels the watch-next work.
    val pending = CoroutineScope(coroutineContext).async(start = CoroutineStart.UNDISPATCHED) { loadMetadata() }
    val stream = try {
        loadStream()
    } catch (error: Throwable) {
        pending.cancel()
        pending.join()
        throw error
    }
    val ready = if (pending.isCompleted && !pending.isCancelled) {
        runCatching { pending.getCompleted() }.getOrNull()
    } else {
        null
    }
    return applyYouTubePlaybackMetadata(stream, ready)
}

/** Fills title, chapters, and resume from watch-next without touching an in-flight sponsor list. */
internal fun applyYouTubePlaybackMetadata(stream: YouTubePlayback, metadata: YouTubeMetadata?): YouTubePlayback {
    val duration = metadata?.durationMs?.coerceAtLeast(0) ?: stream.durationMs
    val resolveResume = !stream.resumeResolved && metadata != null && (duration > 0L || stream.isLive)
    return stream.copy(
        title = stream.title.takeUnless { it.isBlank() || it == "YouTube" || it == "SmartTube" } ?: metadata?.title ?: "SmartTube",
        author = stream.author ?: metadata?.author,
        description = stream.description ?: metadata?.description,
        imageUrl = metadata?.imageUrl ?: stream.imageUrl,
        backgroundUrl = metadata?.backgroundUrl ?: stream.backgroundUrl,
        durationMs = duration,
        timeline = stream.timeline.copy(chapters = metadata?.chapters.orEmpty()).normalized(duration),
        resumeMs = if (resolveResume) com.ambient.tv.ui.player.resumeRewindPositionMs(
            youtubeResumeMs(duration, metadata.percentWatched, stream.isLive), stream.resumeRewindSeconds,
        ) else stream.resumeMs,
        resumeResolved = stream.resumeResolved || resolveResume,
    )
}

/** Called once for a new user launch; late metadata receives the same captured rewind value. */
internal fun prepareYouTubeSavedResume(stream: YouTubePlayback, rewindSeconds: Int): YouTubePlayback =
    stream.copy(
        resumeMs = if (stream.isLive) 0L else com.ambient.tv.ui.player.resumeRewindPositionMs(stream.resumeMs, rewindSeconds),
        resumeResolved = stream.resumeResolved || stream.resumeMs > 0L || stream.isLive,
        resumeRewindSeconds = rewindSeconds.coerceAtLeast(0),
    )

/** Videos watched to (nearly) the end start from the beginning instead of at the credits. */
internal const val YOUTUBE_FINISHED_PERCENT = 95

internal fun youtubeResumeMs(durationMs: Long, percentWatched: Int?, isLive: Boolean): Long {
    val percent = percentWatched?.coerceIn(0, 100) ?: 0
    if (isLive || percent >= YOUTUBE_FINISHED_PERCENT) return 0
    return durationMs / 100 * percent
}

/**
 * A cached resume is already [initialStartMs]. A late watch-next may seek only when playback
 * opened at the beginning and the user has not chosen another position.
 */
internal fun lateYouTubeResumeTarget(
    isLive: Boolean,
    initialStartMs: Long,
    resumeMs: Long,
    userSeeked: Boolean,
    resumeAlreadyApplied: Boolean = false,
): Long? {
    if (isLive || userSeeked || resumeAlreadyApplied || initialStartMs > 0L) return null
    if (resumeMs <= 0L || resumeMs == initialStartMs) return null
    return resumeMs
}

/** Short-lived detail metadata; signed stream URLs are deliberately never cached here. */
internal class YouTubePlaybackMetadataCache(private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val metadata: YouTubeMetadata, val storedAt: Long)
    private val entries = LinkedHashMap<String, Entry>(24, 0.75f, true)
    var generation: Long = 0
        @Synchronized get
        private set
    @Synchronized fun get(id: String): YouTubeMetadata? {
        val entry = entries[id] ?: return null
        if (nowMs() - entry.storedAt in 0..120_000) return entry.metadata
        entries.remove(id)
        return null
    }
    @Synchronized fun put(metadata: YouTubeMetadata, expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        entries[metadata.videoId] = Entry(metadata, nowMs())
        while (entries.size > 24) entries.remove(entries.keys.first())
    }
    @Synchronized fun updatePosition(id: String, positionMs: Long) {
        val old = get(id) ?: return
        if (old.durationMs > 0) {
            val percent = ((positionMs.coerceAtLeast(0).toDouble() / old.durationMs) * 100).toInt().coerceIn(0, 100)
            entries[id] = Entry(old.copy(percentWatched = percent), nowMs())
        }
    }
    @Synchronized fun clear() { generation++; entries.clear() }
    /** Keep other details available, but reject pending writes from before a reconnect refresh. */
    @Synchronized fun invalidate(id: String) { generation++; entries.remove(id) }
}
