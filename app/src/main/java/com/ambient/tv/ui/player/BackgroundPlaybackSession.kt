package com.ambient.tv.ui.player

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable navigation/reporting data: never retain an Activity, View or ViewModel. */
data class NextUpCandidate(
    val mediaId: String,
    val title: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val artworkHeaders: Map<String, String> = emptyMap(),
    val source: String,
    val mediaType: String = "video",
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null,
    val durationMs: Long? = null,
    val contentRating: String? = null,
    val posterUrl: String? = null,
    val posterFallbackUrl: String? = null,
)

data class NextUpConfig(
    val enabled: Boolean = false,
    val leadSeconds: Int = 30,
    val autoplay: Boolean = false,
    val stillWatchingEvery: Int = 3,
    /** Off: advance silently at the end; the card only appears for the still-watching check. */
    val showOverlay: Boolean = true,
    val creditsDelaySeconds: Int = 0,
)

data class PlaybackMetadata(
    val route: String,
    val title: String,
    val subtitle: String = "",
    val source: String,
    val mediaId: String,
    val hasVideo: Boolean = true,
    val artworkUrl: String? = null,
    val artworkHeaders: Map<String, String> = emptyMap(),
    val artworkFallbackUrls: List<String> = emptyList(),
    val artworkAspectRatio: Float = 16f / 9f,
    /** Provider geometry used only until the renderer reports its pixel-correct display aspect. */
    val sourceAspectRatioHint: Float? = null,
    val sourceWidth: Int? = null,
    val sourceHeight: Int? = null,
    val isLive: Boolean = false,
    val liveProgramStartMs: Long? = null,
    val liveProgramEndMs: Long? = null,
    val timelineOffsetMs: Long = 0,
    val durationMs: Long = 0,
    val resumeState: Any? = null,
    val nextUp: NextUpCandidate? = null,
    val creditsSkipMode: String = "off",
    val introSkipMode: String = "off",
    val nextUpConfig: NextUpConfig = NextUpConfig(),
    val successorsLoading: Boolean = false,
    val historyScope: String? = null,
    val isTrailer: Boolean = false,
    val liveTvSession: com.ambient.tv.livetv.LiveTvPlaybackSession? = null,
    val subtitleTracks: List<com.ambient.tv.domain.model.MediaTrack> = emptyList(),
    val externalSubtitlesAllowed: Boolean = true,
    val timelineSeekToken: Long = 0,
    val timelineSeekMs: Long? = null,
    /** Declared direct/direct-stream range, used only by handheld UI-HDR when a backend cannot expose it. */
    val handheldHdrStandard: HdrStandard? = null,
    val companionMedia: com.ambient.tv.companion.CompanionMedia? = null,
    val parentMediaIds: List<String> = emptyList(),
    val companionNext: NextUpCandidate? = null,
    /** Stable server identity for live-channel settings; excludes unrelated live streams. */
    val liveTvSourceId: String? = null,
    val liveRendererOrigin: com.ambient.tv.ui.tvheadend.LiveRendererOrigin? = null,
    /** Title data for an online subtitle search; null where it makes no sense (live TV, music). */
    val subtitleSearch: SubtitleSearchInfo? = null,
    val playbackSegments: List<com.ambient.tv.domain.model.PlaybackSegment> = emptyList(),
    /** Server, season, series and collections for scoped playback settings; null outside libraries. */
    val videoScope: PlaybackVideoScope? = null,
)

/** Library context of a title: where "remember changes for" can store besides the title itself. */
data class PlaybackVideoScope(
    val serverId: String? = null,
    val seasonId: String? = null,
    val seasonLabel: String? = null,
    val seriesId: String? = null,
    val seriesLabel: String? = null,
    /** Collections of the movie, or of the series for an episode. */
    val collections: List<com.ambient.tv.domain.model.MediaTag> = emptyList(),
    val episode: Boolean = false,
)

data class SubtitleSearchInfo(
    val title: String,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val imdbId: String? = null,
    /** A Files/NAS media key: the file can be hashed for exact matches. */
    val fileMediaId: String? = null,
)

val LocalPlaybackMetadata = staticCompositionLocalOf<PlaybackMetadata?> { null }

/** Shared controls keep the global skip preference and the active playback in sync. */
data class SegmentSkipControls(
    val introMode: String,
    val creditsMode: String,
    val onIntroModeChanged: ((String) -> Unit)? = null,
    val onCreditsModeChanged: ((String) -> Unit)? = null,
    // The server delivered usable segments for this item (Plex markers, Jellyfin MediaSegments, Emby chapter markers).
    val hasIntro: Boolean = false,
    val hasCredits: Boolean = false,
)

val LocalSegmentSkipControls = staticCompositionLocalOf<SegmentSkipControls?> { null }

data class PlaybackStatus(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = false,
    val ended: Boolean = false,
    val error: String? = null,
)

interface PlaybackHandle {
    /** Opt-in video conversion. Other backends leave their native output untouched. */
    fun updateSdrToHdr(request: com.ambient.tv.ui.display.SdrToHdrRequest?): com.ambient.tv.ui.display.SdrToHdrStatus =
        if (request == null) com.ambient.tv.ui.display.SdrToHdrStatus.OFF else com.ambient.tv.ui.display.SdrToHdrStatus.UNAVAILABLE
    /** Runtime HDR signal suitable for UI opt-in; null means SDR or unverified output. */
    fun uiHdrStandard(): HdrStandard? = null
    /** Null means this engine does not implement SDR-to-HDR. Callers treat an active null as blocked. */
    fun sdrToHdrPath(): com.ambient.tv.ui.display.SdrToHdrPath? = null
    /** Renderer scales to the output; matching the source HDMI resolution would bypass it. */
    val usesRendererScaling: Boolean get() = false
    /** Keep the display target rate when the renderer is generating intermediate frames. */
    val usesFrameInterpolation: Boolean get() = false
    fun status(): PlaybackStatus
    /** Playback intent can differ from isPlaying while a newly created renderer is buffering. */
    fun wantsToPlay(): Boolean = status().playing
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    /** Silences output without pausing, for live streams that cannot hold their position. */
    fun setOutputMuted(muted: Boolean) = Unit
    /** Counts stream reopens the engine did on its own; a position jump across one is no seek. */
    fun reopenGeneration(): Int = 0
    /**
     * Reopens the audio sink after HDMI audio renegotiated (ACTION_HDMI_AUDIO_PLUG after a
     * display mode switch, as Kodi does). Media3 follows the plug on its own; default no-op.
     */
    fun reopenAudioOutput() = Unit
    fun chapters(): List<com.ambient.tv.domain.model.VideoChapter> = emptyList()
    /** Restore one logical playback after replacing only its renderer. */
    fun restoreAfterRendererSwitch(positionMs: Long, playWhenReady: Boolean, isLive: Boolean) {
        if (!isLive) seekTo(positionMs.coerceAtLeast(0L))
        if (playWhenReady) play() else pause()
    }
    /** Disconnect the renderer from its Android surface before the engine is destroyed. */
    fun detachVideoOutput() = Unit
    fun release()
}

/**
 * One service-owned engine across all sources/renderers. All access is on the main
 * looper, not merely synchronized: Media3 and native surfaces require that thread.
 * Screens borrow engines and own only their listeners/surfaces.
 */
object BackgroundPlaybackSession {
    data class Snapshot(
        val metadata: PlaybackMetadata? = null,
        val backend: String = "",
        val status: PlaybackStatus = PlaybackStatus(),
        val generation: Long = 0,
        /** Play intent, including buffering; broader than [playing]. */
        val wantsToPlay: Boolean = false,
        /** A source hand-off owns the player page even after releasing the outgoing decoder. */
        val replacingSource: Boolean = false,
        /** Final user target on the absolute timeline, even before the decoder seeks. */
        val requestedTimelinePositionMs: Long? = null,
    ) {
        val active get() = metadata != null || replacingSource
        val playing get() = status.playing
    }

    private val mutableSnapshot = MutableStateFlow(Snapshot())
    val snapshot = mutableSnapshot.asStateFlow()
    private val mutableVideoPresentation = MutableStateFlow(VideoPresentation())
    val videoPresentation = mutableVideoPresentation.asStateFlow()
    private var handle by mutableStateOf<PlaybackHandle?>(null)
    private var subtitlePolicy = SubtitleSessionPolicy()
    private var subtitlePreferences: com.ambient.tv.data.local.AppPreferences? = null
    private var lastSubtitleCheck = 0L
    private var lastTitleSettingsSave = 0L
    fun manualSubtitleSelection() { checkThread(); subtitlePolicy.manualSelection() }
    private var identity: Any? = null
    private var videoSettingsOverride by mutableStateOf<PlayerVideoSettings?>(null)

    fun videoSettings(metadata: PlaybackMetadata?, defaults: PlayerVideoSettings): PlayerVideoSettings =
        if (sameVideoSettingsPlayback(metadata, snapshot.value.metadata)) videoSettingsOverride ?: defaults else defaults

    /**
     * The output/decoder chosen in the player for this playback, null when nothing was chosen.
     * An automatic HDR "direct" ([PlayerVideoSettings.automaticHdrDirect]) is no choice.
     */
    fun videoSettingsOverride(metadata: PlaybackMetadata?): PlayerVideoSettings? =
        if (sameVideoSettingsPlayback(metadata, snapshot.value.metadata)) videoSettingsOverride?.takeUnless { it.automaticHdrDirect } else null

    /** [videoSettings] without the player's automatic HDR "direct": what profiles may store. */
    fun chosenVideoSettings(metadata: PlaybackMetadata?, defaults: PlayerVideoSettings): PlayerVideoSettings =
        videoSettings(metadata, defaults).chosen(defaults)

    fun setVideoSettings(metadata: PlaybackMetadata, settings: PlayerVideoSettings) {
        checkThread()
        if (sameVideoSettingsPlayback(metadata, snapshot.value.metadata)) videoSettingsOverride = settings
    }

    fun refreshMetadata(metadata: PlaybackMetadata) {
        checkThread()
        val current = snapshot.value
        if (current.active && current.metadata?.route == metadata.route && current.metadata != metadata)
            mutableSnapshot.value = current.copy(metadata = metadata)
    }
    private var generation = 0L
    private var appContext: Context? = null
    /** The shared sleep timer ([SleepSession]); this session is one of its targets. */
    val sleepTimer: SleepTimerConfig get() = SleepSession.config
    val sleepPromptDeadline: Long? get() = SleepSession.promptDeadline
    val sleepWarningDeadline: Long? get() = SleepSession.warningDeadline
    private val sleepTarget = object : SleepTarget {
        override fun sleepStatus() = handle?.status() ?: snapshot.value.status
        override fun sleepWantsToPlay() = handle?.wantsToPlay() ?: false
        override fun sleepPause() { handle?.pause() }
        override fun sleepPlay() { handle?.play() }
        override fun sleepStop() = stop()
    }
    private val completed = ArrayDeque<Snapshot>()
    private var consecutiveAutoAdvances = 0
    private var expectedNextUp: Pair<String, String>? = null

    /** The last start of [PlaybackService] was refused (background start on Android 12+). */
    @Volatile var playbackServiceBlocked = false
        private set

    private fun startPlaybackService(context: Context) {
        playbackServiceBlocked = !startForegroundServiceSafely(
            start = { ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java)) },
            blocked = { error ->
                // Playback still runs; notification/MediaSession/progress reports follow once
                // the app is visible again (retryBlockedPlaybackService from Activity.onStart).
                timber.log.Timber.w(error, "Playback service could not start; continuing without it")
                com.ambient.tv.diagnostics.CrashBreadcrumbs.add("session", "service start blocked: ${error.javaClass.simpleName}")
            },
        )
    }

    /** Activity.onStart: a session whose service start was refused in the background gets it now. */
    fun retryBlockedPlaybackService(context: Context) {
        if (!playbackServiceBlocked || !snapshot.value.active) return
        startPlaybackService(context.applicationContext)
    }

    private fun checkThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Playback must be accessed on the main looper"
    }

    fun <T : PlaybackHandle> acquire(
        context: Context,
        metadata: PlaybackMetadata,
        backend: String,
        key: Any,
        create: () -> T,
    ): T {
        checkThread()
        val current = snapshot.value
        if (current.metadata?.route != metadata.route) {
            mutableVideoPresentation.value = VideoPresentation()
        }
        if (handle != null && current.metadata?.route == metadata.route &&
            current.backend == backend && identity == key) {
            mutableSnapshot.value = current.copy(metadata = metadata)
            @Suppress("UNCHECKED_CAST")
            return handle as T
        }
        val previous = handle
        val replacementPlayIntent = current.wantsToPlay.takeIf { current.replacingSource }
        val samePlayback = previous != null && current.metadata?.let { previousMetadata ->
            previousMetadata.route == metadata.route &&
                previousMetadata.source == metadata.source &&
                previousMetadata.mediaId == metadata.mediaId
        } == true
        appContext = context.applicationContext
        if (previous != null) runCatching {
            TitleVideoSettingsStore.capture(context, current.metadata, previous, videoSettingsOverride)
        }
        lastTitleSettingsSave = SystemClock.elapsedRealtime()
        if (!samePlayback) {
            AudioEngineRedirectGuard.reset()
            videoSettingsOverride = null
        }
        // Duration and inactivity timers belong to the listening session, including zapping,
        // autoplay and renderer switches. An end-of-item timer belongs to its current item.
        if (!samePlayback) SleepSession.itemChanged(sleepTarget)
        SleepSession.begin(sleepTarget, { videoImagePreferences(context) }, metadata.isLive,
            hadPlayer = previous != null, replacingSource = current.replacingSource)
        val continuesNextUpChain = expectedNextUp == (metadata.source to metadata.mediaId)
        startPlaybackService(context.applicationContext)
        val previousStatus = previous?.status()
        val previousAudio = if (samePlayback) playbackProfileAudio(previous) else null
        val previousSubtitleDelay = if (samePlayback) (previous as? SubtitleDelayTarget)?.subtitleDelayMs else null
        val previousZeroReady = rendererZeroPositionReady(previous)
        if (previous != null) PlaybackOutputTransition.begin(context)
        val previousPlayIntent = previous?.wantsToPlay() ?: previousStatus?.playing ?: true
        // SurfaceView/PlayerView disposal happens on the next Compose apply pass. Native players
        // must release their video output before their engine is destroyed, otherwise the old
        // renderer can crash while its Surface is still owned by the persistent mini-player host.
        subtitlePreferences = videoImagePreferences(context)
        if (samePlayback) subtitlePolicy.replaceRenderer(previous, current.metadata.subtitleTracks)
        else subtitlePolicy = SubtitleSessionPolicy()
        lastSubtitleCheck = 0L
        previous?.detachVideoOutput()
        // A renderer replacement is not a stopped playback. Reporting it as stopped makes Plex/
        // Jellyfin briefly close the active session and can also move the resume point backwards.
        if (previous != null && !samePlayback) {
            completed.addLast(current.copy(status = previousStatus ?: current.status))
        }
        // Retire the outgoing owner before acquiring the next one. Native teardown may finish
        // asynchronously; the snapshot stays active so the UI cannot mistake this handoff for Stop.
        //
        // Clear the session handle before release() so listeners still attached to the outgoing
        // Compose surface (TVHeadend Media3/VLC/MPV) can see that this engine is no longer active
        // and ignore teardown errors instead of marking a live channel unavailable.
        handle = null
        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("session",
            "acquire backend=$backend previous=${current.backend} same=$samePlayback")
        previous?.release()
        val created = try {
            create()
        } catch (error: Throwable) {
            // Same cleanup as stop(): the outgoing playback ended here, so report it as stopped
            // (a renderer switch had deliberately not queued it above).
            if (previous != null && samePlayback) completed.addLast(current.copy(status = previousStatus ?: current.status))
            PlaybackOutputTransition.clear()
            handle = null
            identity = null
            mutableVideoPresentation.value = VideoPresentation()
            videoSettingsOverride = null
            consecutiveAutoAdvances = 0
            expectedNextUp = null
            // A renderer that failed to start is retried or replaced; the listening timer stays.
            SleepSession.end(sleepTarget, keep = true)
            mutableSnapshot.value = Snapshot(generation = ++generation)
            throw error
        }
        handle = created
        // A destination renderer may have read title defaults before the outgoing save completed.
        // Carry current edits directly so a quick renderer switch cannot restore stale audio.
        previousAudio?.let { playbackProfileAudio(created)?.restoreFromSession(it) }
        previousSubtitleDelay?.let { (created as? SubtitleDelayTarget)?.setSubtitleDelay(it) }
        created.applySubtitle(null)
        identity = key
        (created as? VideoPresentationTarget)?.applyVideoPresentation(mutableVideoPresentation.value)
        if (samePlayback && previousStatus != null) {
            // During a renderer hand-off the outgoing decoder may already report zero while the
            // newly created one carries the requested resume time. Preserve that request.
            val previousPositionMs = rendererSwitchPositionMs(previousStatus.positionMs, created.status().positionMs, previousZeroReady)
            created.restoreAfterRendererSwitch(
                positionMs = ((if (current.metadata.timelineSeekToken != metadata.timelineSeekToken)
                    metadata.timelineSeekMs ?: previousPositionMs
                else previousPositionMs + current.metadata.timelineOffsetMs) - metadata.timelineOffsetMs).coerceAtLeast(0L),
                playWhenReady = previousPlayIntent,
                isLive = metadata.isLive,
            )
        } else if (replacementPlayIntent != null) {
            // A new item was constructed at its own start/resume position. Re-seeking its
            // not-yet-ready decoder (especially VLC at zero) can stall the incoming stream.
            // Constructors already request Play; only carry an explicit pending Pause.
            if (!replacementPlayIntent) created.pause()
        }
        mutableSnapshot.value = Snapshot(
            metadata, backend, created.status(), ++generation, wantsToPlay = created.wantsToPlay(),
        )
        if (previous != null || current.replacingSource) PlaybackOutputTransition.begin(context)
        if (!continuesNextUpChain) consecutiveAutoAdvances = 0
        expectedNextUp = null
        return created
    }

    private var seekOwner: PlaybackHandle? = null
    private var seekState: PlaybackSeekState? = null

    internal fun registerSeekState(owner: PlaybackHandle?, state: PlaybackSeekState) {
        if (owner == null || !owns(owner)) return
        seekOwner = owner
        seekState = state
    }

    internal fun unregisterSeekState(state: PlaybackSeekState) {
        if (seekState !== state) return
        seekOwner = null
        seekState = null
    }

    private fun completedSnapshot(current: Snapshot, status: PlaybackStatus): Snapshot =
        current.copy(status = status, requestedTimelinePositionMs =
            if (handle != null && seekOwner === handle) seekState?.pendingTargetMs else null)

    fun owns(candidate: PlaybackHandle) = handle === candidate

    /** Keep the outgoing picture/OSD until the next request is ready, without an EOF/Stop gap. */
    fun beginItemTransition(): Long {
        checkThread()
        val current = snapshot.value
        SleepSession.itemChanged(sleepTarget)
        mutableSnapshot.value = current.copy(
            generation = ++generation, replacingSource = true, wantsToPlay = true,
            status = current.status.copy(ended = false),
        )
        return generation
    }

    fun stopIfCurrent(route: String, expectedGeneration: Long) {
        checkThread()
        val current = snapshot.value
        if (current.metadata?.route == route && current.generation == expectedGeneration && !current.replacingSource) stop()
    }

    /** Release an upstream subscription without publishing Stop or resetting listening timers. */
    fun beginSourceReplacement(replacementMetadata: PlaybackMetadata? = null, playWhenReadyOverride: Boolean? = null): Long {
        checkThread()
        val current = snapshot.value
        val previous = handle
        val status = previous?.status() ?: current.status
        val playIntent = playWhenReadyOverride ?: previous?.wantsToPlay() ?: if (current.active) current.wantsToPlay else true
        appContext?.let { context ->
            runCatching { TitleVideoSettingsStore.capture(context, current.metadata, previous, videoSettingsOverride) }
        }
        if (previous != null) completed.addLast(current.copy(status = status))
        handle = null
        identity = null
        videoSettingsOverride = null
        mutableVideoPresentation.value = VideoPresentation()
        AudioEngineRedirectGuard.reset()
        SleepSession.itemChanged(sleepTarget)
        mutableSnapshot.value = current.copy(
            metadata = replacementMetadata ?: current.metadata,
            status = if (replacementMetadata != null) PlaybackStatus() else status.copy(playing = false, ended = false, error = null),
            generation = ++generation, wantsToPlay = playIntent, replacingSource = true,
        )
        val token = generation
        appContext?.let(PlaybackOutputTransition::begin)
        try {
            try { previous?.detachVideoOutput() } finally { previous?.release() }
        } catch (error: Throwable) {
            cancelSourceReplacement(token)
            throw error
        }
        return token
    }

    fun failSourceReplacement(token: Long, message: String) {
        checkThread()
        if (isSourceReplacement(token)) mutableSnapshot.value = snapshot.value.copy(
            status = snapshot.value.status.copy(error = message), wantsToPlay = false)
    }

    fun isSourceReplacement(token: Long): Boolean =
        snapshot.value.generation == token && snapshot.value.replacingSource

    fun cancelSourceReplacement(token: Long) {
        checkThread()
        if (isSourceReplacement(token)) stop()
    }
    fun activeHandle(): PlaybackHandle? = handle
    fun currentStatus(): PlaybackStatus? {
        checkThread()
        return handle?.status()
    }
    fun setVideoPresentation(value: VideoPresentation) {
        checkThread()
        val effective = value.normalized()
        mutableVideoPresentation.value = effective
        (handle as? VideoPresentationTarget)?.applyVideoPresentation(effective)
    }
    fun updateMetadata(metadata: PlaybackMetadata) {
        if (snapshot.value.metadata?.route == metadata.route) {
            mutableSnapshot.value = snapshot.value.copy(metadata = metadata)
        }
    }
    fun cached(route: String): Any? = snapshot.value.takeUnless { it.replacingSource }
        ?.metadata?.takeIf { it.route == route }?.resumeState
    fun setSleepTimer(value: SleepTimerConfig) {
        checkThread()
        SleepSession.set(value)
    }

    /** "OK: +30 min" on the pre-warning or "Extend" in the notification. */
    fun extendSleepTimer() {
        checkThread()
        SleepSession.extend()
        registerUserInteraction()
    }

    fun tick() {
        checkThread()
        val player = handle ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSubtitleCheck >= 1_000L) {
            lastSubtitleCheck = now
            subtitlePreferences?.let { prefs ->
                subtitlePolicy.apply(player, snapshot.value.metadata?.subtitleTracks.orEmpty(), prefs.subtitlePreferences,
                    prefs.playbackAudioLanguage,
                    com.ambient.tv.data.local.LanguagePreferences.systemLanguageTag(), snapshot.value.metadata?.externalSubtitlesAllowed == true)
            }
        }
        val status = player.status()
        // Persist edits while playing/paused, not only on clean Stop. MPV exposes cached state here.
        if (now - lastTitleSettingsSave >= 1_000L) {
            lastTitleSettingsSave = now
            appContext?.let { context ->
                runCatching { TitleVideoSettingsStore.capture(context, snapshot.value.metadata, player, videoSettingsOverride) }
            }
        }
        val current = snapshot.value
        mutableSnapshot.value = current.copy(status = status,
            wantsToPlay = if (current.replacingSource) current.wantsToPlay else player.wantsToPlay())
        SleepSession.tick(sleepTarget)
    }

    /** Bumped on every user key/touch; player OSD auto-hide restarts its timeout on it. */
    var userInputVersion by mutableIntStateOf(0)
        private set

    fun registerUserInteraction() {
        checkThread()
        SleepSession.userInput()
        userInputVersion++
    }

    /** Answer to the sleep prompt: resume with the timer off, or with the same timer started again. */
    fun continueAfterSleep(rearm: Boolean) {
        checkThread()
        SleepSession.continueAfter(rearm)
        registerUserInteraction()
    }

    fun play() {
        checkThread()
        handle?.play()
        if (snapshot.value.replacingSource) mutableSnapshot.value = snapshot.value.copy(wantsToPlay = true)
        tick()
    }
    fun pause() {
        checkThread()
        handle?.pause()
        if (snapshot.value.replacingSource) mutableSnapshot.value = snapshot.value.copy(wantsToPlay = false)
        tick()
    }
    fun toggle() { if (handle?.wantsToPlay() ?: snapshot.value.wantsToPlay) pause() else play() }
    /** Bumped by every seek routed through the session (companion, media session, DLNA, OSD). */
    var externalSeekVersion by mutableIntStateOf(0)
        private set
    fun seekTo(positionMs: Long) { checkThread(); externalSeekVersion++; handle?.seekTo(positionMs.coerceAtLeast(0)); tick() }

    fun shouldConfirmNextUp(limit: Int): Boolean = limit > 0 && consecutiveAutoAdvances >= limit
    fun recordAutomaticNextUp(candidate: NextUpCandidate) {
        consecutiveAutoAdvances++
        expectedNextUp = candidate.source to candidate.mediaId
    }
    fun recordManualNextUp(candidate: NextUpCandidate) {
        consecutiveAutoAdvances = 0
        expectedNextUp = candidate.source to candidate.mediaId
    }

    /**
     * [keepSleepTimer]: an internal stop (TVHeadend retry, server unreachable) that is followed by
     * a new start; the user's sleep timer survives it for [KEEP_TIMER_WINDOW_MS].
     */
    fun stop(preserveNextUpChain: Boolean = false, keepSleepTimer: Boolean = false) {
        AudioEngineRedirectGuard.reset()
        checkThread()
        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("session",
            "stop backend=${snapshot.value.backend} live=${snapshot.value.metadata?.isLive} keepTimer=$keepSleepTimer")
        PlaybackOutputTransition.clear()
        if (keepSleepTimer || preserveNextUpChain) SleepSession.end(sleepTarget, keep = true) else SleepSession.end(sleepTarget)
        val previous = handle
        appContext?.let { context ->
            runCatching { TitleVideoSettingsStore.capture(context, snapshot.value.metadata, previous, videoSettingsOverride) }
        }
        // The next start of any title compares "changed in this session" against what is stored then.
        TitleVideoSettingsStore.endSession()
        if (previous != null) completed.addLast(completedSnapshot(snapshot.value, previous.status()))
        previous?.detachVideoOutput()
        // Invalidate before release; synchronous callbacks cannot revive the old owner.
        handle = null
        identity = null
        mutableVideoPresentation.value = VideoPresentation()
        videoSettingsOverride = null
        mutableSnapshot.value = Snapshot(generation = ++generation)
        if (!preserveNextUpChain) {
            consecutiveAutoAdvances = 0
            expectedNextUp = null
        }
        previous?.release()
    }

    fun takeCompleted(): List<Snapshot> = buildList {
        while (completed.isNotEmpty()) add(completed.removeFirst())
    }
}

/**
 * startForegroundService() throws ForegroundServiceStartNotAllowedException (an
 * IllegalStateException) from the background on Android 12+, and SecurityException on some
 * vendor builds. Neither may take down playback; returns false when refused.
 */
internal fun startForegroundServiceSafely(start: () -> Unit, blocked: (Throwable) -> Unit): Boolean = try {
    start()
    true
} catch (error: IllegalStateException) {
    blocked(error)
    false
} catch (error: SecurityException) {
    blocked(error)
    false
}
