package com.ambient.tv.ui.player.backend

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.VideoChapter
import com.ambient.tv.ui.display.*
import com.ambient.tv.ui.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

/**
 * Main-thread facade. Native handles and all libmpv calls belong to MpvPlaybackEngine's
 * owner thread. UI reads are immutable cached values; commands never join that thread.
 */
class MpvPlaybackController(
    private val context: Context,
    val url: String,
    private val initialStartPositionMs: Long,
    headers: Map<String, String>,
    audioPassthroughEnabled: Boolean = false,
    videoOutputMode: String = "direct",
    initialImageOptions: MpvImageOptions = MpvImageOptions(),
    networkCache: Boolean = false,
    decoderMode: String = "auto",
    knownAudioCodec: String? = null,
    nativeHdrPreferred: Boolean = false,
    liveStream: Boolean = false,
    declaredHdr: com.ambient.tv.ui.player.HdrStandard? = null,
    val audioOnly: Boolean = false,
    private val preferOriginalAudio: Boolean = false,
    reopeningTimeshift: Boolean = false,
    broadcastSource: Boolean = liveStream,
    /** gpu-next/Vulkan HDR passthrough chosen by [mpvHdrGpuDecision]. */
    val hdrGpuPassthrough: Boolean = false,
) : PlaybackHandle, VideoPresentationTarget, SubtitleDelayTarget {
    val audioSession = AudioSession(videoImagePreferences(context), "mpv", audioPassthroughEnabled, audioOnly = audioOnly)
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("mpv-control").apply { start() }
    private val worker = Handler(thread.looper)
    private var engine: MpvPlaybackEngine? = null // owner thread only
    private var ownedSurface: Surface? = null // independent Surface reference; owner thread only
    private var surfaceIdentity: Surface? = null // UI's SurfaceHolder identity
    private val surfaceGate = MpvSurfaceDetachGate()
    @Volatile private var released = false
    @Volatile private var lastProgress = SystemClock.elapsedRealtime()
    private var revision = 0L // main thread's requested state
    private var appliedRevision = 0L // owner thread's completed state
    private var pendingImage by mutableStateOf<Pair<Long, MpvImageOptions>?>(null)
    private var pendingDeinterlace by mutableStateOf<Pair<Long, Pair<String, String>>?>(null)
    private var pendingSubtitleDelay by mutableStateOf<Pair<Long, Int>?>(null)
    private var playIntent by mutableStateOf(true)
    private var failure by mutableStateOf<String?>(null)
    var backendUnresponsive by mutableStateOf(false)
        private set
    /**
     * The path the engine will start on, known before it exists: the panel keeps the GPU (or
     * direct) layout through a renderer switch instead of collapsing until the first sample.
     * Shown as expected ([engineConfirmed] false) until the running engine reports it.
     */
    private val expectedGpuFilters = runCatching {
        initialMpvAccelerationPlan(context, videoOutputMode, decoderMode, initialImageOptions, nativeHdrPreferred,
            hdrGpuPassthrough, declaredHdr != null &&
                !nativeHdrSurfacePreferred(declaredHdr, HdrSupport.displayStandards(context))).gpu
    }.getOrDefault(false)
    private var state by mutableStateOf(MpvEngineState(imageOptions = initialImageOptions.normalized(),
        status = PlaybackStatus(positionMs = initialStartPositionMs), supportsGpuFilters = expectedGpuFilters))
    private var values by mutableStateOf<Map<String, Any?>>(emptyMap())
    private val changes = MutableStateFlow(0L)
    val stateChanges = changes.asStateFlow()
    /** Bumped on the main thread whenever a sampled value changed; lets the snapshot pick it up. */
    var valuesRevision = 0L
        private set
    val eventDriven = true
    private val presentation = MutableStateFlow(VideoPresentation())
    val videoPresentation = presentation.asStateFlow()
    private class Query(val read: MpvPlaybackEngine.() -> Any?, val spacing: Long) {
        @Volatile var requested = SystemClock.elapsedRealtime()
        var sampled = 0L
    }
    private val queries = ConcurrentHashMap<String, Query>()
    private val sampledValues = mutableMapOf<String, Any?>() // owner thread only
    private var sampledMediaInfoGeneration = -1 // owner thread only
    private val previousAudioRelease = nativeAudioReleases.snapshot()
    private val createdAt = SystemClock.elapsedRealtime()
    /** Completed once the previous player closed, failed to close, or [PREVIOUS_RELEASE_LIMIT_MS] passed. */
    private val ownerReady = java.util.concurrent.CompletableFuture<PreviousReleaseOutcome>()
    /**
     * The previous player (MPV, VLC or live Media3) is still closing its output. Shown as a short
     * hint instead of "Player antwortet nicht": this player has not started yet, nothing hangs.
     */
    var waitingForPreviousPlayer by mutableStateOf(!previousAudioRelease.isDone)
        private set
    /** The owner began building the engine: from here on the watchdog counts. */
    @Volatile private var ownerStarted = false
    /** The owner's current step, named in the log when the stall dialog appears. */
    private val ownerStep = MpvOwnerStep()
    /** Until the first picture (or loaded audio) the watchdog allows [MPV_OPENING_UNRESPONSIVE_MS]. */
    @Volatile private var opening = true
    /** [MpvOwnerStep.reopens] as of the last sample; the watchdog reads it on the main thread. */
    @Volatile private var seenReopens = 0
    private val mailbox = MpvCommandQueue(
        schedule = { task ->
            // Construction can happen inside remember(). Let that composition commit before
            // the owner reads the AudioSession's Compose state for the first time.
            ownerReady.whenComplete { _, _ ->
                main.post { worker.post { task() } }
            }
        },
        failed = { error ->
            Timber.e(error, "MPV owner operation failed")
            main.post { if (!released) failure = context.getString(com.ambient.tv.R.string.mpv_command_failed, -1) }
        },
        running = ownerStep::operation,
    )

    // This monitor never calls MPV. It also detects a blocked constructor/filter/teardown-independent
    // owner loop; a coroutine timeout around a synchronous JNI call could not do that.
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (released) return
            val now = SystemClock.elapsedRealtime()
            val openingPhase = mpvWatchdogOpening(opening, seenReopens, ownerStep.reopens)
            val stalled = mpvBackendUnresponsive(now, lastProgress, ownerStarted, openingPhase)
            if (stalled && !backendUnresponsive) {
                val report = mpvStallReport(ownerStep.name, ownerStep.sinceMs, lastProgress, now, openingPhase)
                Timber.w(report)
                com.ambient.tv.diagnostics.DiagnosticLog.warn("playback", report)
                com.ambient.tv.diagnostics.CrashBreadcrumbs.add("mpv", report)
            }
            backendUnresponsive = stalled
            mailbox.submit("sample") { sample() }
            main.postDelayed(this, 250L)
        }
    }

    init {
        audioSession.apply = { _, _ -> main.post { command("audio") { applyAudioOptions() } } }
        audioSession.routeChanged = { _, _ -> main.post { command("audio") { applyAudioOptions() } } }
        audioSession.startRouteMonitoring(context)
        previousAudioRelease.whenSettled(delay = { ms, block -> main.postDelayed(block, ms) }) { outcome ->
            main.post { waitingForPreviousPlayer = false }
            ownerReady.complete(outcome)
        }
        mailbox.submit("initialize") {
            // Already settled: the mailbox only runs once ownerReady completed. Never blocks.
            val outcome = ownerReady.getNow(PreviousReleaseOutcome.TIMED_OUT)
            val waited = SystemClock.elapsedRealtime() - createdAt
            com.ambient.tv.diagnostics.CrashBreadcrumbs.add("mpv", "previous player $outcome after $waited ms")
            when (outcome) {
                PreviousReleaseOutcome.TIMED_OUT -> {
                    Timber.w("MPV start: previous player still closing after %d ms; not starting", waited)
                    main.post { if (!released) failure = context.getString(com.ambient.tv.R.string.audio_output_release_failed) }
                    return@submit
                }
                PreviousReleaseOutcome.READY_AFTER_FAILURE ->
                    Timber.w(previousAudioRelease.failure, "MPV start: previous player failed to close; starting anyway")
                PreviousReleaseOutcome.READY -> if (waited >= 1_000L) Timber.i("MPV start waited %d ms for the previous player", waited)
            }
            lastProgress = SystemClock.elapsedRealtime()
            ownerStarted = true
            ownerStep.mark("initialize:engine_create")
            engine = MpvPlaybackEngine(context, audioSession, worker, {
                main.post { if (!released && BackgroundPlaybackSession.owns(this)) PlaybackOutputTransition.begin(context) }
            }, url, initialStartPositionMs, headers, audioPassthroughEnabled, videoOutputMode,
                initialImageOptions, networkCache, decoderMode, knownAudioCodec, nativeHdrPreferred, hdrGpuPassthrough, liveStream, declaredHdr, audioOnly, preferOriginalAudio, reopeningTimeshift, broadcastSource, ownerStep)
            ownerStep.mark("initialize:first_sample")
            sample()
        }
        main.post(tick)
    }

    private fun command(key: String, action: MpvPlaybackEngine.() -> Unit): Long {
        if (released) return revision
        val requestedRevision = ++revision
        if (!mailbox.submit(key) {
            engine?.action()
            appliedRevision = requestedRevision
            sample()
        }) failure = context.getString(com.ambient.tv.R.string.mpv_command_failed, -1)
        return requestedRevision
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> read(key: String, fallback: T, spacing: Long = 750L, getter: MpvPlaybackEngine.() -> T): T {
        if (!released) {
            var added = false
            queries.getOrPut(key) { added = true; Query(getter, spacing) }.requested = SystemClock.elapsedRealtime()
            // A value asked for the first time is sampled now, not on the next 250 ms tick.
            if (added) mailbox.submit("sample") { sample() }
        }
        return if (values.containsKey(key)) values[key] as T else fallback
    }

    private fun sample() {
        val player = engine ?: return
        if (released) return
        val now = SystemClock.elapsedRealtime()
        val status = player.status()
        // A reopen (surface change, audio fallback, ended file) loads the stream again: the same
        // opening allowance as the first start, until its first picture or loaded audio.
        val reopens = ownerStep.reopens
        if (reopens != seenReopens) {
            opening = true
            seenReopens = reopens
        }
        if (opening && player.openingDone()) opening = false
        // Codec, size, decoder, tracks … change with a load/reconfigure: read them again in this
        // sample instead of handing the UI a value from before the change for up to 750 ms.
        val generation = player.mediaInfoGeneration
        if (mpvMediaInfoChanged(sampledMediaInfoGeneration, generation)) queries.values.forEach { it.sampled = 0L }
        sampledMediaInfoGeneration = generation
        for ((key, query) in queries) {
            if (now - query.requested <= 2_500L && (query.sampled == 0L || now - query.sampled >= query.spacing)) {
                sampledValues[key] = query.read(player)
                query.sampled = now
            }
        }
        val next = MpvEngineState(
            confirmed = true,
            status = status, wantsToPlay = player.wantsToPlay(), supportsGpuFilters = player.supportsGpuFilters, gpuRenderAtPanel = player.gpuRenderAtPanel, sdrSurfaceDataSpace = player.sdrSurfaceDataSpace,
            usesFrameInterpolation = player.usesFrameInterpolation, accelerationFallback = player.accelerationFallback, shieldSoftwareStatus = player.shieldSoftwareStatus,
            imageOptions = player.imageOptions, imageOptionWarnings = player.imageOptionWarnings.toList(),
            deinterlaceModeKey = player.deinterlaceModeKey, deinterlaceFilterKey = player.deinterlaceFilterKey,
            deinterlaceBackoffGeneration = player.deinterlaceBackoffGeneration, deinterlaceFailed = player.deinterlaceFailed,
            subtitleDelayMs = player.subtitleDelayMs, sdrImageProcessing = player.sdrImageProcessing,
            hdrImageTransfer = player.hdrImageTransfer, sourceHdrStandard = player.sourceHdrStandard,
            hdrGpuFailure = player.hdrGpuFailure, sourceSdrConfirmed = player.sourceSdrConfirmed,
            dolbyVisionDetected = player.dolbyVisionDetected, hdrPassthroughVulkan = player.hdrPassthroughVulkan,
            hdrPassthroughConfirmed = player.hdrPassthroughConfirmed, hdrDisplaySwitched = player.hdrDisplaySwitched,
            overloadDropPercent = player.overloadDropPercent, renderOverloaded = player.renderOverloaded, canReduceAfterOverload = player.canReduceAfterOverload,
            overloadContinueSignature = player.overloadContinueSignature, renderCheckSignature = player.renderCheckSignature,
            mediaInfoGeneration = player.mediaInfoGeneration, reopenGeneration = player.reopenGeneration,
            restoredOutputAudioSelection = player.restoredOutputAudioSelection,
            restoreOutput = player.captureOutputRestartState(),
        )
        val readings = sampledValues.toMap()
        val completed = appliedRevision
        lastProgress = SystemClock.elapsedRealtime()
        main.post {
            if (!released) {
                val changed = state != next || values != readings
                if (values != readings) valuesRevision++
                if (pendingSeekRevision?.let { completed >= it } == true) { pendingSeekRevision = null; pendingSeekTargetMs = null }
                state = next
                values = readings
                if ((pendingImage?.first ?: Long.MAX_VALUE) <= completed) pendingImage = null
                if ((pendingDeinterlace?.first ?: Long.MAX_VALUE) <= completed) pendingDeinterlace = null
                if ((pendingSubtitleDelay?.first ?: Long.MAX_VALUE) <= completed) pendingSubtitleDelay = null
                if (completed >= revision) {
                    playIntent = next.wantsToPlay
                    acceptedOverloadSignature = null
                }
                if (changed) changes.value++
            }
        }
    }

    val bitstreamPermitted get() = audioSession.bitstreamPermitted
    val supportsGpuFilters get() = state.supportsGpuFilters
    /** False until the running engine reported its state; until then output facts are expected. */
    val engineConfirmed get() = state.confirmed
    /** The GPU output renders at the physical panel resolution (otherwise at the UI size). */
    val gpuRenderAtPanel get() = state.gpuRenderAtPanel
    val sdrSurfaceDataSpace get() = state.sdrSurfaceDataSpace
    override val usesRendererScaling get() = supportsGpuFilters
    override val usesFrameInterpolation get() = state.usesFrameInterpolation
    val accelerationFallback get() = state.accelerationFallback
    val shieldSoftwareStatus get() = state.shieldSoftwareStatus
    val imageOptions get() = pendingImage?.second ?: state.imageOptions
    private var saveDefaults by mutableStateOf(false)
    var saveImageDefaults: Boolean
        get() = saveDefaults
        set(value) {
            saveDefaults = value
            command("save-image-defaults") { saveImageDefaults = value }
        }
    val imageOptionWarnings get() = state.imageOptionWarnings
    val deinterlaceModeKey get() = pendingDeinterlace?.second?.first ?: state.deinterlaceModeKey
    val deinterlaceFilterKey get() = pendingDeinterlace?.second?.second ?: state.deinterlaceFilterKey
    val deinterlaceBackoffGeneration get() = state.deinterlaceBackoffGeneration
    val deinterlaceFailed get() = state.deinterlaceFailed
    override val subtitleDelayMs get() = pendingSubtitleDelay?.second ?: state.subtitleDelayMs
    val sdrImageProcessing get() = state.sdrImageProcessing
    val hdrImageTransfer get() = state.hdrImageTransfer
    val sourceHdrStandard get() = state.sourceHdrStandard
    /** Passthrough failed for this player; the view reopens the title on the direct surface. */
    val hdrGpuFailure get() = state.hdrGpuFailure
    /** mpv read an SDR transfer for this file; a live channel's earlier HDR detection is stale. */
    val sourceSdrConfirmed get() = state.sourceSdrConfirmed
    /** The loaded file carries Dolby Vision (track list), on every route. */
    val dolbyVisionDetected get() = state.dolbyVisionDetected
    /**
     * "HDR über GPU" route runs: passthrough player on Vulkan, no failure recorded. Keeps the
     * route stable while options are edited; [hdrPassthroughConfirmed] verifies the PQ surface,
     * never the physical panel or HDMI HDR mode.
     */
    val hdrPassthroughRunning get() = hdrGpuPassthrough && hdrGpuFailure == null && state.hdrPassthroughVulkan
    /**
     * The running passthrough is *marked* HDR: mpv targets PQ/BT.2020 and the surface carries a
     * PQ dataspace. That is the app's own tagging; whether the TV switched is [hdrDisplaySwitched].
     */
    val hdrPassthroughConfirmed get() = hdrPassthroughRunning && state.hdrPassthroughConfirmed
    /** Display HDR mode seen via `Display.getHdrSdrRatio` (API 34+); null = Android cannot tell. */
    val hdrDisplaySwitched get() = state.hdrDisplaySwitched.takeIf { hdrPassthroughConfirmed }
    val overloadDropPercent get() = state.overloadDropPercent
    val renderOverloaded get() = state.renderOverloaded
    val canReduceAfterOverload get() = state.canReduceAfterOverload
    private var acceptedOverloadSignature: String? = null
    val overloadContinueSignature get() = acceptedOverloadSignature ?: state.overloadContinueSignature
    val errorMessage get() = failure ?: state.status.error
    private var pendingSeekRevision: Long? = null
    val ended get() = pendingSeekRevision == null && state.status.ended
    val mediaInfoGeneration get() = state.mediaInfoGeneration
    private var restoringAudioSelection = false
    internal val restoredOutputAudioSelection get() = restoringAudioSelection || state.restoredOutputAudioSelection

    internal fun captureOutputRestartState(): (MpvPlaybackController) -> Unit {
        val restore = state.restoreOutput
        return { target ->
            target.restoringAudioSelection = restore != null
            target.command("restore-selection") { restore?.let(::restoreOutputRestartState) }
        }
    }

    fun updateImageOptions(options: MpvImageOptions) {
        val next = options.normalized()
        pendingImage = (revision + 1) to next
        command("image-options") { updateImageOptions(next) }
    }
    fun setDeinterlace(enabled: Boolean, filter: String = "auto", automatic: Boolean = false) {
        pendingDeinterlace = (revision + 1) to ((if (!enabled) "off" else if (automatic) "auto" else "on") to filter)
        command("deinterlace") { setDeinterlace(enabled, filter, automatic) }
    }
    internal fun presetDeinterlace(mode: String, filter: String) {
        pendingDeinterlace = (revision + 1) to (mode to filter)
        command("preset-deinterlace") { presetDeinterlace(mode, filter) }
    }
    override fun setSubtitleDelay(delayMs: Int) {
        pendingSubtitleDelay = (revision + 1) to delayMs
        command("subtitle-delay") { setSubtitleDelay(delayMs) }
    }
    fun restoreOverloadContinue(signature: String?) {
        acceptedOverloadSignature = signature
        command("overload") { restoreOverloadContinue(signature) }
    }
    fun dismissRenderOverload() { command("overload") { dismissRenderOverload() } }
    fun continueDespiteOverload() {
        acceptedOverloadSignature = state.renderCheckSignature
        command("overload") { continueDespiteOverload() }
    }
    fun reduceAfterOverload() { command("overload") { reduceAfterOverload() } }
    override fun status() = state.status.copy(playing = !released && state.status.playing, ended = ended, error = errorMessage)
    override fun wantsToPlay() = !released && playIntent && !ended && errorMessage == null
    fun positionMs() = state.status.positionMs
    fun durationMs() = state.status.durationMs
    fun isPlaying() = !released && state.status.playing
    fun isPaused() = !isPlaying()
    override fun play() { playIntent = true; command("play-state") { play() } }
    override fun pause() { playIntent = false; command("play-state") { pause() } }
    override fun setOutputMuted(muted: Boolean) { command("output-mute") { setOutputMuted(muted) } }
    override fun reopenGeneration() = state.reopenGeneration
    override fun reopenAudioOutput() { command("ao-reload") { reopenAudioOutput() } }
    fun playPause() { if (wantsToPlay()) pause() else play() }
    /** Target of a seek the engine has not applied yet; quick repeated steps build on it. */
    private var pendingSeekTargetMs: Long? = null
    override fun seekTo(positionMs: Long) {
        pendingSeekTargetMs = positionMs
        pendingSeekRevision = command("seek") { seekTo(positionMs) }
    }
    fun seekBy(deltaMs: Long) = seekTo(mpvSeekByTarget(pendingSeekTargetMs, positionMs(), deltaMs))
    override fun restoreAfterRendererSwitch(positionMs: Long, playWhenReady: Boolean, isLive: Boolean) {
        playIntent = playWhenReady
        command("restore-playback") { restoreAfterRendererSwitch(positionMs, playWhenReady, isLive) }
    }
    fun setSpeed(speed: Float) { command("speed") { setSpeed(speed) } }
    fun setVideoPresentation(resizeMode: String, aspect: String?, zoomFactor: Float) {
        BackgroundPlaybackSession.setVideoPresentation(VideoPresentation(
            resizeMode = videoResizeMode(resizeMode), aspectRatio = videoAspectRatio(aspect), zoom = zoomFactor,
        ))
    }
    override fun applyVideoPresentation(presentation: VideoPresentation) {
        this.presentation.value = presentation
        command("presentation") { applyVideoPresentation(presentation) }
    }
    fun surfaceSizeChanged(width: Int, height: Int) { command("surface-size") { surfaceSizeChanged(width, height) } }
    fun displayRefreshRateChanged(refreshRate: Float) { command("display-rate") { displayRefreshRateChanged(refreshRate) } }
    fun selectVideo(id: Int?) { command("video-track") { selectVideo(id) } }
    fun selectAudio(id: Int?) { command("audio-track") { selectAudio(id) } }
    fun selectSubtitle(id: Int?) { command("subtitle-track") { selectSubtitle(id) } }
    fun ensureExternalSubtitle(track: MediaTrack) { command("subtitle-add:${track.externalUrl}") { ensureExternalSubtitle(track) } }
    fun selectExternalSubtitle(track: MediaTrack) { command("subtitle-track") { selectExternalSubtitle(track) } }
    fun noteAudioCodecName(codec: String?) { command("audio-codec") { noteAudioCodecName(codec) } }
    fun pollEvents() { if (!released) mailbox.submit("sample") { sample() } }
    fun preferredAudioTrack(language: String?): MpvTrack? = choosePreferredAudioTrack(audioTracks(), language, audioSession.settings.tracks) {
        AudioTrackMetadata(it.language, it.title, it.channels)
    }

    private var hdrRequest: SdrToHdrRequest? = null
    private var hdrStatus by mutableStateOf(SdrToHdrStatus.OFF)
    override fun updateSdrToHdr(request: SdrToHdrRequest?): SdrToHdrStatus {
        if (request != hdrRequest) hdrStatus = if (request == null) SdrToHdrStatus.OFF else SdrToHdrStatus.WAITING
        hdrRequest = request
        command("sdr-hdr") {
            val result = updateSdrToHdr(request)
            main.post { if (!released && hdrRequest == request) hdrStatus = result }
        }
        return hdrStatus
    }

    fun attachSurface(surface: Surface, width: Int = 0, height: Int = 0) {
        if (released || surfaceIdentity === surface || !surface.isValid) return
        // SurfaceHolder owns the supplied Java Surface and can release it as soon as its callback
        // returns. Keep an independent native reference until the owner has detached/destroyed VO.
        val parcel = Parcel.obtain()
        val copy = try {
            surface.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Surface.CREATOR.createFromParcel(parcel)
        } finally { parcel.recycle() }
        surfaceIdentity = surface
        mailbox.submit("surface", discard = { copy.release() }) {
            val player = engine
            if (player == null) copy.release()
            else {
                player.detachSurface()
                ownedSurface?.release()
                ownedSurface = copy
                surfaceGate.attaching()
                player.attachSurface(copy, width, height)
                sample()
            }
        }
    }
    fun detachSurface(surface: Surface? = null) {
        if (surface != null && surfaceIdentity !== surface) return
        surfaceIdentity = null
        // Kept across close(): Stop's release() must not drop the detach queued just before it.
        if (!released) {
            val ticket = surfaceGate.requestDetach()
            mailbox.submit("surface", keepOnClose = true) {
                try {
                    engine?.detachSurface()
                    ownedSurface?.release()
                    ownedSurface = null
                } finally { surfaceGate.detached(ticket) }
                sample()
            }
        }
    }

    /**
     * SurfaceHolder.surfaceDestroyed: queue the detach and wait, bounded, until the VO is off the
     * window. Main thread only; the mpv-control thread never waits on itself.
     */
    fun detachSurfaceAndWait(surface: Surface, timeoutMs: Long = SURFACE_DETACH_WAIT_MS) {
        val owner = surfaceIdentity === surface
        detachSurface(surface)
        if (!owner && !released) return
        if (Looper.myLooper() === thread.looper) return
        val started = SystemClock.elapsedRealtime()
        val detached = surfaceGate.await(timeoutMs)
        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("mpv", "surface detach " +
            (if (detached) "done" else "timeout") + " in ${SystemClock.elapsedRealtime() - started} ms")
        if (!detached) Timber.w("MPV VO still on the destroyed surface after %d ms", timeoutMs)
    }
    override fun detachVideoOutput() = detachSurface()
    override fun release() {
        if (released) return
        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("mpv", "release")
        // An in-flight attach may not have reached attaching() yet. Wait for close in that case.
        surfaceGate.requestDetach()
        released = true
        playIntent = false
        backendUnresponsive = false
        surfaceIdentity = null
        main.removeCallbacks(tick)
        audioSession.release()
        val audioRelease = nativeAudioReleases.beginRelease()
        mailbox.close {
            // Never interrupt or destroy a handle concurrently with an in-flight native call.
            try {
                engine?.release()
                audioRelease.complete(Unit)
                engine = null
                ownedSurface?.release()
                ownedSurface = null
            } catch (error: Throwable) {
                audioRelease.completeExceptionally(error)
                throw error
            } finally {
                surfaceGate.closed()
                thread.quitSafely()
            }
        }
    }
    fun loadedImageShaders(): List<String> = read("loadedImageShaders", emptyList(), 750L) { loadedImageShaders() }
    fun sourceDimensions(): Pair<Int, Int>? = read("sourceDimensions", null, 750L) { sourceDimensions() }
    fun videoTracks(): List<MpvTrack> = read("videoTracks", emptyList(), 250L) { videoTracks() }
    fun audioTracks(): List<MpvTrack> = read("audioTracks", emptyList(), 250L) { audioTracks() }
    /** Actual playback rate, so the panel follows renderer restores and bitstream resets. */
    fun currentSpeed(): Float = read("speed", 1f, 250L) { currentSpeed() }
    fun subtitleTracks(): List<MpvTrack> = read("subtitleTracks", emptyList(), 250L) { subtitleTracks() }
    override fun chapters(): List<VideoChapter> = read("chapters", emptyList(), 2000L) { chapters() }
    fun isConfirmedAudioOnly(): Boolean = read("isConfirmedAudioOnly", false, 750L) { isConfirmedAudioOnly() }
    fun waitingForData(): Boolean = read("waitingForData", true, 250L) { waitingForData() }
    fun videoCodec(): String? = read("videoCodec", null, 750L) { videoCodec() }
    fun audioCodec(): String? = read("audioCodec", null, 750L) { audioCodec() }
    fun videoSize(): String? = read("videoSize", null, 750L) { videoSize() }
    fun videoAspectRatio(): Float? = read("videoAspectRatio", null, 750L) { videoAspectRatio() }
    fun containerFps(): Double? = read("containerFps", null, 750L) { containerFps() }
    fun videoFps(): Double = read("videoFps", 0.0, 750L) { videoFps() }
    fun decoder(): String? = read("decoder", null, 750L) { decoder() }
    fun videoBitrateBitsPerSecond(): Long? = read("videoBitrateBitsPerSecond", null, 750L) { videoBitrateBitsPerSecond() }
    fun audioBitrateBitsPerSecond(): Long? = read("audioBitrateBitsPerSecond", null, 750L) { audioBitrateBitsPerSecond() }
    fun cacheDurationSeconds(): Double? = read("cacheDurationSeconds", null, 750L) { cacheDurationSeconds() }
    fun cacheSpeedBytesPerSecond(): Long? = read("cacheSpeedBytesPerSecond", null, 750L) { cacheSpeedBytesPerSecond() }
    fun avSyncSeconds(): Double? = read("avSyncSeconds", null, 750L) { avSyncSeconds() }
    fun frameDropCount(): Long? = read("frameDropCount", null, 750L) { frameDropCount() }
    internal fun isMuted(): Boolean? = read("isMuted", null, 100L) { isMuted() }
    fun decoderFrameDropCount(): Long? = read("decoderFrameDropCount", null, 750L) { decoderFrameDropCount() }
    fun displayFps(): Double? = read("displayFps", null, 750L) { displayFps() }
    fun pixelFormat(): String? = read("pixelFormat", null, 750L) { pixelFormat() }
    fun outputPixelFormat(): String? = read("outputPixelFormat", null, 750L) { outputPixelFormat() }
    fun activeVideoFilters(): String? = read("activeVideoFilters", null, 750L) { activeVideoFilters() }
    fun activeDeinterlace(): String? = read("activeDeinterlace", null, 750L) { activeDeinterlace() }
    fun svpStatus(): String? = read("svpStatus", null, 750L) { svpStatus() }
    fun svpState(): SvpState? = read("svpState", null, 750L) { svpState() }
    fun activeScaler(): String? = read("activeScaler", null, 750L) { activeScaler() }
    fun activeUpscaling(): Boolean? = read("activeUpscaling", null, 750L) { activeUpscaling() }
    fun activeChromaScaler(): String? = read("activeChromaScaler", null, 750L) { activeChromaScaler() }
    fun activeDownscaler(): String? = read("activeDownscaler", null, 750L) { activeDownscaler() }
    fun activeShaderPreset(): String? = read("activeShaderPreset", null, 750L) { activeShaderPreset() }
    fun configuredVideoOutput(): String? = read("configuredVideoOutput", null, 750L) { configuredVideoOutput() }
    fun audioOutputFormat(): String? = read("audioOutputFormat", null, 750L) { audioOutputFormat() }
    fun audioOutputDriver(): String? = read("audioOutputDriver", null, 750L) { audioOutputDriver() }
    override fun uiHdrStandard(): HdrStandard? = read("uiHdrStandard", null, 250L) { uiHdrStandard() }
    override fun sdrToHdrPath(): SdrToHdrPath = read("sdrToHdrPath", SdrToHdrPath(SdrToHdrPlayerKind.MPV_UNSUPPORTED, blocked = true), 250L) { sdrToHdrPath() }
}

private data class MpvEngineState(
    /** Sampled from a running engine; false for the pre-start defaults/expectations. */
    val confirmed: Boolean = false,
    val status: PlaybackStatus = PlaybackStatus(),
    val wantsToPlay: Boolean = true,
    val supportsGpuFilters: Boolean = false,
    val gpuRenderAtPanel: Boolean = false,
    val sdrSurfaceDataSpace: Int? = null,
    val usesFrameInterpolation: Boolean = false,
    val accelerationFallback: String? = null,
    val shieldSoftwareStatus: String? = null,
    val imageOptions: MpvImageOptions = MpvImageOptions(),
    val imageOptionWarnings: List<String> = emptyList(),
    val deinterlaceModeKey: String = "off",
    val deinterlaceFilterKey: String = "auto",
    val deinterlaceBackoffGeneration: Int = 0,
    val deinterlaceFailed: Boolean = false,
    val subtitleDelayMs: Int = 0,
    val sdrImageProcessing: Boolean = false,
    val hdrImageTransfer: Boolean = false,
    val sourceHdrStandard: HdrStandard? = null,
    val hdrGpuFailure: MpvHdrGpuReason? = null,
    val sourceSdrConfirmed: Boolean = false,
    val dolbyVisionDetected: Boolean = false,
    val hdrPassthroughVulkan: Boolean = false,
    val hdrPassthroughConfirmed: Boolean = false,
    val hdrDisplaySwitched: Boolean? = null,
    val overloadDropPercent: Double? = null,
    val renderOverloaded: Boolean = false,
    val canReduceAfterOverload: Boolean = false,
    val overloadContinueSignature: String? = null,
    val renderCheckSignature: String? = null,
    val mediaInfoGeneration: Int = 0,
    val reopenGeneration: Int = 0,
    val restoredOutputAudioSelection: Boolean = false,
    val restoreOutput: List<String?>? = null,
)

/** Media info must be re-read when mpv reported a load/reconfigure since the last sample. */
internal fun mpvMediaInfoChanged(sampledGeneration: Int, currentGeneration: Int): Boolean =
    sampledGeneration != currentGeneration

/** A step from the not-yet-applied seek target if any, else from the last sampled position. */
internal fun mpvSeekByTarget(pendingTargetMs: Long?, sampledPositionMs: Long, deltaMs: Long): Long =
    ((pendingTargetMs ?: sampledPositionMs) + deltaMs).coerceAtLeast(0L)

/** Upper bound for surfaceDestroyed waiting on the VO detach (main thread; far below ANR). */
internal const val SURFACE_DETACH_WAIT_MS = 500L

/** No progress for this long after the engine began starting: "Player antwortet nicht". */
internal const val MPV_UNRESPONSIVE_MS = 5_000L

/**
 * Whether the MPV owner counts as hung. Only after it began building the engine: before that it
 * waits for the previous player to close (renderer switch), which is shown as its own hint.
 */
internal fun mpvBackendUnresponsive(nowMs: Long, lastProgressMs: Long, ownerStarted: Boolean,
    opening: Boolean = false, limitMs: Long = mpvUnresponsiveLimitMs(opening)): Boolean =
    ownerStarted && nowMs - lastProgressMs >= limitMs
