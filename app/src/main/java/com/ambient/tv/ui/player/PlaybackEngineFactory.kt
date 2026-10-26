@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ambient.tv.ui.player.backend.AudioSpectrumAnalyzer
import java.util.WeakHashMap
import com.ambient.tv.BuildConfig
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import com.ambient.tv.util.withoutUrlQueries
import java.util.concurrent.TimeUnit

/** Central player configuration shared by all playback surfaces. */
object PlaybackEngineFactory {
    private val spectrumAnalyzers = java.util.Collections.synchronizedMap(WeakHashMap<ExoPlayer, AudioSpectrumAnalyzer>())

    fun audioSpectrum(player: ExoPlayer): AudioSpectrumAnalyzer? = spectrumAnalyzers[player]

    private val CREDENTIAL_HEADERS = listOf(
        "Authorization", "Proxy-Authorization", "X-Emby-Authorization", "X-Emby-Token", "X-Plex-Token", "Cookie", "Set-Cookie",
    )

    // A dedicated client for media requests: the app's Retrofit OkHttpClient carries
    // DynamicServerUrlInterceptor, which rewrites requests onto Retrofit's fake base URL and
    // would break these already-absolute stream URLs. Logging redacts tokens in the URL and every
    // credential header (TVHeadend Basic auth, Jellyfin/Emby tokens) so a server-rejected request
    // (e.g. a 4xx from the transcode session endpoint) can still be diagnosed from logcat without
    // leaking user credentials.
    //
    // Level.HEADERS only - not BODY. A live TVHeadend/DVB stream is an unbounded response (no
    // Content-Length, "Connection: close") that only ends when playback stops. BODY-level
    // logging buffers the entire response body before handing it back to the caller, so on an
    // infinite live stream it blocks forever - the player never receives a single byte, and the
    // request eventually dies at the read timeout instead of ever starting playback.
    private val streamingHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (!response.isSuccessful) {
                    val safeUrl = chain.request().url.toString().withoutUrlQueries()
                    val detail = runCatching { response.peekBody(8_192).string() }
                        .getOrDefault("")
                        .replace(Regex("[\\r\\n]+"), " ")
                        .take(1_000)
                    // Error bodies can echo request URLs (tokens, signed googlevideo links).
                    Log.e("PlaybackHttp", "HTTP ${response.code} ${response.message}: $safeUrl; ${detail.withoutUrlQueries()}")
                }
                response
            }
            .apply {
                if (BuildConfig.DEBUG) {
                    addInterceptor(
                        HttpLoggingInterceptor { message ->
                            Timber.tag("PlaybackHttp").d(message.withoutUrlQueries())
                        }.apply {
                            level = HttpLoggingInterceptor.Level.HEADERS
                            CREDENTIAL_HEADERS.forEach(::redactHeader)
                        },
                    )
                }
            }
            // Stream headers carry server tokens; they must not follow a redirect elsewhere.
            .let(com.ambient.tv.network.CredentialRedirectGuard::install)
            .build()
    }

    fun create(
        context: Context,
        streamUrl: String,
        startPositionMs: Long,
        subtitleTracks: List<MediaTrack> = emptyList(),
        streamHeaders: Map<String, String> = emptyMap(),
        audioPassthroughEnabled: Boolean = false,
        metadata: PlaybackMetadata? = null,
        mimeType: String? = null,
        maxVideoHeight: Int = 0,
        /** SmartTube-style target codec and frame rate for adaptive sources (YouTube MPD). */
        preferredVideoMimeType: String? = null,
        maxVideoFrameRate: Int = 0,
        streamUrlRenewer: ((String) -> String?)? = null,
        sourceHdrStandard: HdrStandard? = metadata?.handheldHdrStandard,
        preferOriginalAudio: Boolean = metadata?.source == "youtube",
        audioOnly: Boolean = metadata?.hasVideo == false,
        mpvAudioAllowed: Boolean = sourceHdrStandard != HdrStandard.DOLBY_VISION,
        /** YouTube only: center automatic captions that YouTube pins to the left edge. */
        centerYouTubeCaptions: Boolean = metadata?.source == "youtube",
        /** Built but not prepared: the caller prepares once the previous player closed. */
        deferPrepare: Boolean = false,
    ): ExoPlayer {
        if (metadata != null) {
            return BackgroundPlaybackSession.acquire(context, metadata, "media3",
                listOf(streamUrl, startPositionMs, streamHeaders, audioPassthroughEnabled)) {
                // Live TV (Tvheadend): Media3 takes part in the native release gate like MPV and
                // VLC, so it neither opens a second server subscription while the previous player
                // still holds one nor lets the next player open before its own stream is gone.
                val gated = media3JoinsReleaseGate(metadata.isLive)
                val previous = if (gated) com.ambient.tv.ui.player.backend.nativeAudioReleases.snapshot() else null
                val wait = previous?.takeIf { !it.isDone }
                val player = create(context.applicationContext, streamUrl, startPositionMs,
                    subtitleTracks, streamHeaders, audioPassthroughEnabled, mimeType = mimeType, maxVideoHeight = maxVideoHeight, preferredVideoMimeType = preferredVideoMimeType, maxVideoFrameRate = maxVideoFrameRate, streamUrlRenewer = streamUrlRenewer, sourceHdrStandard = sourceHdrStandard, preferOriginalAudio = preferOriginalAudio, audioOnly = audioOnly, mpvAudioAllowed = mpvAudioAllowed, centerYouTubeCaptions = centerYouTubeCaptions,
                    deferPrepare = wait != null)
                Media3PlaybackHandle(player, joinsReleaseGate = gated).also {
                    if (wait != null) prepareAfterPreviousRelease(player, wait)
                }
            }.player
        }
        val started = System.nanoTime()
        val spectrumAnalyzer = AudioSpectrumAnalyzer()
        val audioSession = AudioSession(videoImagePreferences(context), "media3", audioPassthroughEnabled, audioOnly = audioOnly)
        audioSession.updateMpvAllowed(mpvAudioAllowed)
        val renderersFactory = Media3AudioProcessing.factory(context, audioSession, spectrumAnalyzer)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl)
            .setMimeType(mimeType)
            .setSubtitleConfigurations(
                subtitleTracks
                    .filter { it.type == MediaTrackType.SUBTITLE && !it.externalUrl.isNullOrBlank() }
                    .mapNotNull(::externalSubtitleConfiguration),
            )
            .build()

        val streamClient = if (streamUrlRenewer == null) streamingHttpClient else streamingHttpClient.newBuilder()
            .addInterceptor(StreamUrlRenewalInterceptor(streamUrlRenewer)).build()
        val okHttpDataSourceFactory = OkHttpDataSource.Factory(streamClient)
            .setDefaultRequestProperties(streamHeaders)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, okHttpDataSourceFactory))
            .apply { if (centerYouTubeCaptions) setSubtitleParserFactory(com.ambient.tv.youtube.YouTubeSubtitleParserFactory()) }

        val heapMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.memoryClass ?: 0
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setTargetBufferBytes(media3TargetBufferBytes(heapMb))
            .build()
        logPlaybackStartup("media3", "configured", started,
            "start_buffer_ms=${androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS} " +
                "rebuffer_ms=${androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS} " +
                "target_bytes=${media3TargetBufferBytes(heapMb)} max_height=$maxVideoHeight")

        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .also { spectrumAnalyzers[it] = spectrumAnalyzer; Media3AudioProcessing.attach(it, audioSession, context) }
            .apply {
                setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                // The service applies the TV HDMI-transition policy; phones keep Media3's
                // immediate headphone-unplug protection as well.
                setHandleAudioBecomingNoisy(!com.ambient.tv.ui.mobile.isTelevision(context))
                volume = 1f
                val languages = if (preferOriginalAudio) emptyList() else com.ambient.tv.data.local.LanguagePreferences.playbackLanguageTags(
                    videoImagePreferences(context).playbackAudioLanguage)
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredAudioLanguages(*languages.toTypedArray())
                    .setPreferredTextLanguages(*com.ambient.tv.data.local.LanguagePreferences.playbackLanguageTags(
                        videoImagePreferences(context).playbackSubtitleLanguage).toTypedArray()).build()
                // Apply the first selection before prepare() can start loading DASH tracks.
                // maxVideoHeight is a quality step (short side): a square box also keeps portrait Shorts.
                if (maxVideoHeight > 0) trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .youtubeMaxVideoSize(com.ambient.tv.youtube.youtubeMedia3MaxVideoSize(maxVideoHeight)).build()
                // The box keeps square streams of a higher step: restrict the selected group by step too.
                if (maxVideoHeight > 0) addListener(object : androidx.media3.common.Player.Listener {
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        youtubeConstrainSquareVideo(this@apply, tracks, maxVideoHeight)
                    }
                })
                // Upper bound with SmartTube's 10 fps tolerance (a 30 fps target still allows 25/30).
                if (maxVideoFrameRate > 0) trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setMaxVideoFrameRate(maxVideoFrameRate + 9).build()
                if (preferredVideoMimeType != null) trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setPreferredVideoMimeType(preferredVideoMimeType).build()
                addListener(object : androidx.media3.common.Player.Listener {
                    private var reportedReady = false
                    private var reportedFrame = false
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (!reportedReady && playbackState == androidx.media3.common.Player.STATE_READY) {
                            reportedReady = true
                            logPlaybackStartup("media3", "ready", started)
                        }
                    }
                    override fun onRenderedFirstFrame() {
                        if (!reportedFrame) {
                            reportedFrame = true
                            logPlaybackStartup("media3", "first_frame_callback", started)
                        }
                    }
                })
                val primeResume = needsDolbyVisionResumePriming(android.os.Build.MANUFACTURER,
                    android.os.Build.VERSION.SDK_INT, sourceHdrStandard, startPositionMs)
                setMediaItem(mediaItem)
                seekTo(if (primeResume) 0L else startPositionMs.coerceAtLeast(0L))
                playWhenReady = true
                Media3ImageProcessing.get(context, this)
                if (primeResume) Media3ResumePrimer.start(this, startPositionMs)
                if (!deferPrepare) prepare()
            }
    }

    /** Prepares [player] once the previous MPV/VLC/Media3 player closed (at most [com.ambient.tv.ui.player.backend.PREVIOUS_RELEASE_LIMIT_MS]). */
    private fun prepareAfterPreviousRelease(player: ExoPlayer, previous: com.ambient.tv.ui.player.backend.PreviousRelease) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val started = android.os.SystemClock.elapsedRealtime()
        previous.whenSettled(delay = { ms, block -> main.postDelayed(block, ms) }) { outcome ->
            main.post {
                val waited = android.os.SystemClock.elapsedRealtime() - started
                com.ambient.tv.diagnostics.CrashBreadcrumbs.add("media3", "previous player $outcome after $waited ms")
                if (outcome != com.ambient.tv.ui.player.backend.PreviousReleaseOutcome.READY)
                    Timber.w(previous.failure, "Media3 start: previous player %s after %d ms; starting anyway", outcome, waited)
                // Released meanwhile (channel change, Back): nothing to prepare.
                if (Media3PlaybackHandle.forPlayer(player) != null) player.prepare()
            }
        }
    }

    internal fun externalSubtitleConfiguration(track: MediaTrack): MediaItem.SubtitleConfiguration? {
        val url = track.externalUrl ?: return null
        val mimeType = when (track.codec?.lowercase()) {
            "srt", "subrip" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "webvtt", "vtt" -> "text/vtt"
            "mov_text", "tx3g" -> "application/x-quicktime-tx3g"
            "pgs" -> "application/pgs"
            "vobsub" -> "application/vobsub"
            else -> null
        }
        return MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setMimeType(mimeType)
            .setLanguage(track.languageCode ?: track.language)
            .setLabel(track.title ?: track.language)
            .setSelectionFlags((if (track.selected) C.SELECTION_FLAG_DEFAULT else 0) or (if (track.forced) C.SELECTION_FLAG_FORCED else 0))
            .build()
    }
}

/**
 * Media3 buffers in the Java heap. Its default byte target for video (~131 MB) is more than half of
 * the heap on 1 GB sticks, so a high-bitrate remux could run the app out of memory. The target is
 * a quarter of the app heap, between 32 MB and the Media3 default.
 */
internal fun media3TargetBufferBytes(heapMb: Int): Int {
    val default = androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE
    if (heapMb <= 0) return default
    return (heapMb.toLong() * 1024 * 1024 / 4).coerceIn(32L * 1024 * 1024, default.toLong()).toInt()
}

private fun androidx.media3.common.TrackSelectionParameters.Builder.youtubeMaxVideoSize(box: Int) = setMaxVideoSize(box, box)

/**
 * Limits adaptive selection in the playing video group to tracks of the quality step
 * ([com.ambient.tv.youtube.youtubeMedia3AllowedVideoTracks]). An existing video override (an explicit
 * pick in the panel, or this cap) is left alone; "Auto" clears it and the cap applies again.
 */
private fun youtubeConstrainSquareVideo(player: androidx.media3.common.Player, tracks: androidx.media3.common.Tracks, maxQualityHeight: Int) {
    val parameters = player.trackSelectionParameters
    if (parameters.overrides.values.any { it.type == C.TRACK_TYPE_VIDEO }) return
    val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected } ?: return
    val sizes = (0 until group.length).map { group.getTrackFormat(it).let { f -> f.width to f.height } }
    val allowed = com.ambient.tv.youtube.youtubeMedia3AllowedVideoTracks(sizes, maxQualityHeight)
        ?.filter(group::isTrackSupported)?.takeIf { it.isNotEmpty() } ?: return
    player.trackSelectionParameters = parameters.buildUpon()
        .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, allowed)).build()
}

/** Live TV Media3 players wait for and take part in the native release gate (MPV/VLC hand-off). */
internal fun media3JoinsReleaseGate(isLive: Boolean): Boolean = isLive
