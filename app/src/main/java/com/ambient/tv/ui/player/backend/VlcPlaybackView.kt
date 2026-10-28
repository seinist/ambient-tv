package com.ambient.tv.ui.player.backend

import com.ambient.tv.ui.display.SdrToHdrUnavailableReason
import com.ambient.tv.ui.player.withTitle
import com.ambient.tv.ui.player.withResolution
import com.ambient.tv.R
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.data.local.vlcOptions
import com.ambient.tv.ui.player.reducedVideoEffects
import com.ambient.tv.ui.player.videoImagePreferences
import com.ambient.tv.ui.player.performanceProfile
import com.ambient.tv.ui.player.withGlobalSvp
import android.content.Context
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ambient.tv.ui.player.SdrHdrSurfaceOutput
import com.ambient.tv.ui.player.HdrStandard
import com.ambient.tv.ui.display.SdrToHdrRequest
import com.ambient.tv.ui.display.SdrToHdrStatus
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import timber.log.Timber
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.LocalPlaybackMetadata
import com.ambient.tv.ui.player.HdrSupport
import com.ambient.tv.ui.player.nativeHdrSurfacePreferred
import com.ambient.tv.ui.player.PlaybackHandle
import com.ambient.tv.ui.player.PlaybackStatus
import com.ambient.tv.ui.player.VideoPresentation
import com.ambient.tv.ui.player.VideoPresentationTarget
import com.ambient.tv.ui.player.VideoSurfaceLayout
import com.ambient.tv.ui.player.applyUiHdrHeadroom
import com.ambient.tv.ui.player.ignoreVideoSurfaceFocus
import com.ambient.tv.ui.player.videoAspectRatio
import com.ambient.tv.ui.player.videoResizeMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stable playback time after a stream retry before another open/network error may retry again. */
private const val STREAM_RETRY_RESET_MS = 30_000L

class VlcPlaybackController(
    private val context: Context,
    val url: String,
    private val startPositionMs: Long,
    private val headers: Map<String, String>,
    initialDeinterlaceMode: String = "off",
    initialDeinterlaceFilter: String = "auto",
    initialScalingFilter: String = "bilinear",
    initialAudioVisualization: Boolean = false,
    private val initialAudioPassthrough: Boolean = false,
    initialImageOptions: VlcImageOptions = VlcImageOptions(scaler = initialScalingFilter),
    private val networkCache: Boolean = false,
    videoOutputMode: String = "auto",
    /** Separate audio stream played alongside [url] (YouTube's video-only + audio-only files). */
    private val audioSlaveUrl: String? = null,
    private val nativeHdrPreferred: Boolean = false,
    private val liveStream: Boolean = false,
    val sourceHdrStandard: HdrStandard? = null,
    val audioOnly: Boolean = false,
    private val preferOriginalAudio: Boolean = false,
    private val reopeningTimeshift: Boolean = false,
) : PlaybackHandle, VideoPresentationTarget, com.ambient.tv.ui.player.SubtitleDelayTarget {
    private val startupConstructionAt = System.nanoTime()
    private var initialOpenReported = false
    // HDR on an SDR display still needs the decoder/vout's tone mapping. CPU picture
    // filters must not strip its precision or feed it through SDR-to-HDR a second time.
    private val preserveHdrSource = nativeHdrPreferred || sourceHdrStandard != null
    private val toneMappingRequired = sourceHdrStandard != null && !nativeHdrPreferred
    override val usesRendererScaling: Boolean get() = true
    val audioSession = com.ambient.tv.ui.player.AudioSession(videoImagePreferences(context), "vlc", initialAudioPassthrough, audioOnly = audioOnly)
    val bitstreamPermitted: Boolean get() = audioSession.bitstreamPermitted
    private val nightProcessing = VlcNightProcessing(context)
    private var vulkanOutput = !audioOnly && useVlcVulkan(videoOutputMode, VideoAccelerationRuntime.current(context),
        gpuProcessing = toneMappingRequired || initialImageOptions.softwareProcessing && !reducedVideoEffects(context),
        nativeHdrPreferred = nativeHdrPreferred)
    /** Live picture path. Software processing can still run swscale on this surface. */
    var usesAndroidSurfaceOutput by mutableStateOf(!vulkanOutput)
        private set
    private var vulkanSurfaceChecked = false
    private var vulkanStartedAt = 0L
    var accelerationFallback: String? = if (!audioOnly && videoOutputMode == "vulkan" && !vulkanOutput)
        if (nativeHdrPreferred) "Vulkan → Android: native HDR surface" else "Vulkan → Android: unavailable" else null
        private set
    private val vulkanRequested = !audioOnly && videoOutputMode == "vulkan"
    // Before the startup probe finishes the plan is the provisional safe path, not a verdict.
    init { if (accelerationFallback != null && !nativeHdrPreferred && VideoAccelerationRuntime.capabilities.value.checked) VideoAccelerationRuntime.notifyUnavailable() }
    /** VLC renders the picture itself (libplacebo, GL tone mapping, or no MediaCodec direct rendering). */
    val rendersVideoOnGpu: Boolean get() = !audioOnly &&
        (vulkanOutput || toneMappingRequired || configuredSoftwareProcessing || configuredSvpMode != "off")
    /**
     * "Renderauflösung" ([gpuRenderAtPanelResolution]). The direct MediaCodec path never fixes the
     * buffer size; before, every VLC surface was forced to the physical 4K mode.
     */
    val gpuRenderAtPanel: Boolean get() = com.ambient.tv.ui.player.gpuRenderAtPanelResolution(
        videoImagePreferences(context).gpuRenderResolutionKey, rendersVideoOnGpu,
        com.ambient.tv.ui.player.performanceProfile(context).lowEndDevice,
        effectiveImage().copy(svpMode = "off", svp = VlcImageOptions().svp) != VlcImageOptions().normalized().copy(svp = VlcImageOptions().svp),
    )
    val videoOutputDescription: String get() = when {
        vulkanOutput -> "libplacebo / Vulkan"
        toneMappingRequired -> "OpenGL ES / HDR → SDR"
        else -> "Android Surface / OpenGL ES"
    }
    private fun effectiveImage(): VlcImageOptions = vlcOptionsForNativeHdr(
        effectiveVlcImageOptions(imageOptions, reducedVideoEffects(context), vlcSvpAllowed(context)), nativeHdrPreferred, sourceHdrStandard)
        .let { if (it.svpMode != "off" && !vlcSvpApplies(measuredSourceFps, svpOutputFps(svpDisplayFps(context), it.svp), it.svp, measuredSourceSize?.first, measuredSourceSize?.second)) it.copy(svpMode = "off") else it }
    /** Source frame rate of the video track once known; SVP is bypassed when it already meets the target. */
    private var measuredSourceFps: Double? = null
    /** Source size once known; SVP stays off above the configured resolution limit. */
    private var measuredSourceSize: Pair<Int, Int>? = null
    private fun currentSourceSize(): Pair<Int, Int>? = runCatching { trackAccess.currentVideoTrack }.getOrNull()
        ?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height }
    /** libVLC exposes no filter metadata: the shared native counter shows whether frames are synthesized. */
    private var svpFramesSeen = -1L
    private var svpFramesChangedAt = 0L
    private fun currentSourceFps(): Double? = runCatching { trackAccess.currentVideoTrack }.getOrNull()
        ?.takeIf { it.frameRateDen > 0 && it.frameRateNum > 0 }?.let { it.frameRateNum.toDouble() / it.frameRateDen }
    private var configuredSvpMode = vlcOptionsForNativeHdr(
        effectiveVlcImageOptions(initialImageOptions, reducedVideoEffects(context), vlcSvpAllowed(context)), nativeHdrPreferred, sourceHdrStandard).svpMode
    private var configuredSoftwareProcessing = vlcOptionsForNativeHdr(
        effectiveVlcImageOptions(initialImageOptions, reducedVideoEffects(context), vlcSvpAllowed(context)), nativeHdrPreferred, sourceHdrStandard).softwareProcessing
    private var configuredSvpOptions = initialImageOptions.svp.normalized()
    private var configuredSvpRate = svpOutputFps(svpDisplayFps(context), configuredSvpOptions)
    internal var deinterlaceMode by mutableStateOf(initialDeinterlaceMode)
        private set
    internal var deinterlaceFilter by mutableStateOf(com.ambient.tv.data.local.DeinterlaceCatalog.normalize("vlc", initialDeinterlaceFilter))
        private set
    private var configuredDeinterlaceMode = if (preserveHdrSource) "off" else deinterlaceMode
    private var configuredDeinterlaceFilter = if (preserveHdrSource) "auto" else effectiveDeinterlaceFilter(deinterlaceFilter, reducedVideoEffects(context))
    override val usesFrameInterpolation: Boolean
        get() = effectiveImage().svpMode != "off"
    /** An option this LibVLC build does not know fails the whole instance: retry without the subtitle look. */
    private fun createLibVlc(): LibVLC = runCatching { createLibVlc(subtitleLook = true) }.getOrElse { error ->
        timber.log.Timber.w(error, "VLC rejected the subtitle look options; using its defaults")
        createLibVlc(subtitleLook = false)
    }
    private fun createLibVlc(subtitleLook: Boolean) = LibVLC(
        context,
        arrayListOf(
            "--network-caching=1200",
            "--audio-time-stretch",
            "--audiotrack-session-id=${nightProcessing.sessionId}",
            "--avcodec-hw=any",
            "--clock-jitter=0",
            if (com.ambient.tv.BuildConfig.DEBUG) "--verbose=2" else "--verbose=0",
        ).apply {
            // Text subtitle look from Settings › Subtitles (freetype renderer, instance level).
            if (subtitleLook) addAll(videoImagePreferences(context).subtitleAppearance.vlcOptions())
            addAll(vlcDeinterlaceOptions(configuredDeinterlaceMode, configuredDeinterlaceFilter))
            // VLC 4's vout filter chain is inherited from the instance/player,
            // not from Media options. Media-only video-filter is silently ignored.
            addAll(vlcImageMediaOptions(VlcImageOptions(svpMode = configuredSvpMode, svp = configuredSvpOptions), configuredSvpRate)
                .filter { it.startsWith(":video-filter=") || it == ":no-mediacodec-dr" }
                .map { "--" + it.removePrefix(":") })
            // VLC 4 owns vout at player level, not at input/media level.
            // "none" ends implicit module search so a failed Vulkan initialization
            // reaches our observable default-path retry instead of silently using GL.
            addAll(vlcHdrToSdrOutputOptions(vulkanOutput, toneMappingRequired))
            if (vulkanOutput) addAll(listOf(
                "--vout=libplacebo,none", "--pl-gpu=pl_vulkan,none",
                "--vk-platform=vk_android,none",
                "--vk-device=${VideoAccelerationRuntime.capabilities.value.device.device}",
                "--no-mediacodec-dr",
            ))
        },
    )
    private var libVlc = createLibVlc()
    /** A DV-surface swap retires a player asynchronously; its LibVLC must outlive it. */
    private val libVlcRelease = VlcLibraryReleaseGate<LibVLC> { it.release() }
    var player = MediaPlayer(libVlc)
        private set
    internal var trackAccess = VlcTrackAccess(player)
        private set
    // libVLC has no option for HTTP credentials (`:http-user`/`:http-pwd` are silently ignored)
    // and writes every MRL to its log, so they must never reach it. An authenticated TVHeadend
    // stream is opened through a loopback relay that adds the header inside the app instead.
    private val authProxy: AuthenticatedStreamProxy? =
        if (url.startsWith("http", ignoreCase = true) &&
            headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value?.startsWith("Basic ", ignoreCase = true) == true
        ) runCatching { AuthenticatedStreamProxy(url, headers) }.getOrNull() else null
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var ended by mutableStateOf(false)
        private set
    private var attachedView: SurfaceView? = null
    private var pendingAttachView: SurfaceView? = null
    private var deferredPrepareGeneration = 0
    private val hdrOutput = SdrHdrSurfaceOutput(
        disconnect = { if (!released) player.vlcVout.detachViews() },
        routeInput = { surface ->
            if (!released) {
                player.vlcVout.setVideoSurface(surface, null)
                player.vlcVout.attachViews()
            }
        },
        restore = { view ->
            if (!released && attachedView === view) {
                player.vlcVout.setVideoView(view)
                player.vlcVout.attachViews()
                reportWindowSize(view.holder.surfaceFrame.width(), view.holder.surfaceFrame.height())
            }
        },
        unsafeRestore = {
            pendingAttachView = null
            if (!released) {
                pause()
                player.setVideoTrackEnabled(false)
                errorMessage = context.getString(R.string.sdr_to_hdr_restore_failed)
            }
        },
    )
    override fun updateSdrToHdr(request: SdrToHdrRequest?): SdrToHdrStatus {
        if (request == null || released || preserveHdrSource) return hdrOutput.update(null, false, 0, 0, 0f)
        val track = runCatching { trackAccess.currentVideoTrack }.getOrNull()
        val fps = track?.takeIf { it.frameRateDen > 0 }?.let { it.frameRateNum.toFloat() / it.frameRateDen } ?: 0f
        // Start only while frames flow; a confirmed session survives pause and seek so the TV
        // does not switch HDR -> SDR -> HDR (an HDMI resync each time) around every pause.
        val eligible = !released && !vulkanOutput && prepared && !openingOrRestarting &&
            !ended && errorMessage == null && (player.isPlaying || hdrOutput.active) && !audioVisualizationEnabled &&
            !userVideoDisabled && !reducedVideoEffects(context) && !imageOptions.softwareProcessing &&
            imageOptions.svpMode == "off" && track?.orientation == 0 && track.projection == 0
        return hdrOutput.update(request, eligible, track?.width ?: 0, track?.height ?: 0, fps)
    }
    override fun uiHdrStandard(): HdrStandard? = if (hdrOutput.active) HdrStandard.HDR10 else null
    override fun sdrToHdrPath(): com.ambient.tv.ui.display.SdrToHdrPath {
        val track = runCatching { trackAccess.currentVideoTrack }.getOrNull()
        val fps = track?.takeIf { it.frameRateDen > 0 }?.let { it.frameRateNum.toFloat() / it.frameRateDen } ?: 0f
        val reason = when {
            released || !prepared || ended || track == null -> SdrToHdrUnavailableReason.NOT_READY
            errorMessage != null -> SdrToHdrUnavailableReason.OUTPUT_FAILED
            preserveHdrSource -> SdrToHdrUnavailableReason.SOURCE_NOT_SDR
            vulkanOutput -> SdrToHdrUnavailableReason.PLAYER_OUTPUT
            reducedVideoEffects(context) -> SdrToHdrUnavailableReason.REDUCED_EFFECTS
            audioVisualizationEnabled || imageOptions.softwareProcessing || imageOptions.svpMode != "off" ||
                userVideoDisabled || track.orientation != 0 || track.projection != 0 -> SdrToHdrUnavailableReason.FILTER_CONFLICT
            hdrOutput.unavailableReason != null -> hdrOutput.unavailableReason
            attachedView?.holder?.surfaceFrame == null -> SdrToHdrUnavailableReason.NOT_READY
            attachedView?.holder?.surfaceFrame?.let { !com.ambient.tv.ui.player.sdrHdrSizeAllowed(it.width(), it.height(), fps) } == true -> SdrToHdrUnavailableReason.FRAME_BUDGET
            else -> null
        }
        return com.ambient.tv.ui.display.SdrToHdrPath(
            kind = if (vulkanOutput) com.ambient.tv.ui.display.SdrToHdrPlayerKind.VLC_VULKAN
            else com.ambient.tv.ui.display.SdrToHdrPlayerKind.VLC_GL,
            width = track?.width ?: 0, height = track?.height ?: 0, fps = fps,
            blocked = reason != null, reason = reason,
        )
    }
    private val dvResumeWorkaround get() = !liveStream &&
        com.ambient.tv.ui.player.needsDolbyVisionResumePriming(android.os.Build.MANUFACTURER,
            android.os.Build.VERSION.SDK_INT, sourceHdrStandard, 1L)
    private var dvPrimeTask: Runnable? = null
    private var dvPrimeWaiting = false
    private var dvPrimeActive = false
    private var dvSurfacePosition: Long? = null

    private fun beginDvPrime() {
        dvPrimeTask?.let(pendingSeekHandler::removeCallbacks)
        dvPrimeWaiting = true
        dvPrimeActive = true
        if (pausedSeekVolume == null) pausedSeekVolume = player.volume.takeIf { it >= 0 } ?: 100
        Timber.i("DV VLC prime volume=%s", pausedSeekVolume)
        player.setVolume(0)
        val deadline = android.os.SystemClock.elapsedRealtime() + 8_000L
        val task = object : Runnable {
            override fun run() {
                if (released || !dvPrimeWaiting) return
                // VLC counts submitted pictures, not Android's first presented frame.
                // Let the NVIDIA DV presentation pipeline settle before flushing it
                // for the resume seek (P8.1 can otherwise keep a black surface).
                if ((runtimeStats()?.displayedPictures ?: 0) >= 3 && player.time >= 1_000L) {
                    dvPrimeWaiting = false
                    dvPrimeTask = null
                    Timber.i("DV VLC first frame primed; seek target=%s", pendingRestorePositionMs)
                    pendingRestorePositionMs?.let(::seekTo)
                    return
                }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    dvPrimeWaiting = false
                    dvPrimeActive = false
                    dvPrimeTask = null
                    errorMessage = context.getString(R.string.player_seek_failed)
                    player.pause()
                    restorePausedSeekVolume()
                    return
                }
                pendingSeekHandler.postDelayed(this, 50L)
            }
        }
        dvPrimeTask = task
        pendingSeekHandler.postDelayed(task, 50L)
    }

    private var prepared = false
    private val previousAudioRelease = nativeAudioReleases.snapshot()
    private val createdAt = android.os.SystemClock.elapsedRealtime()
    private var awaitingAudioRelease = false
    /** Null until the previous player's teardown settled (main thread). */
    private var previousReleaseOutcome: PreviousReleaseOutcome? = null
    /**
     * The previous player is still closing its output: a short hint, not an error. Only after
     * [PREVIOUS_RELEASE_LIMIT_MS] does this player report one (and Live TV picks another renderer).
     */
    var waitingForPreviousPlayer by mutableStateOf(false)
        private set
    private var released = false
    private var desiredPlayWhenReady = true
    private var streamOpenRetried = false
    // Last known playback or requested resume clock. VLC can report zero while opening.
    private var lastPlaybackTimeMs = startPositionMs.coerceAtLeast(0L)
    private var lastPlaybackSeekable = false
    private var streamRetryPlayingSince = 0L
    private var openingOrRestarting = true
    private val seekState = VlcSeekState().apply {
        if (!liveStream && startPositionMs > 0L) request(startPositionMs)
    }
    private var pendingRestorePositionMs: Long?
        get() = seekState.targetMs
        set(value) {
            if (value == null) seekState.clear() else seekState.request(value)
        }
    private val pendingSeekHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSeekTask: Runnable? = null
    private var pauseAfterRestorePositionMs: Long? = null
    private var pausedSeekVolume: Int? = null
    private var outputMuteVolume: Int? = null
    override fun setOutputMuted(muted: Boolean) {
        if (released) return
        if (muted) {
            if (outputMuteVolume == null) outputMuteVolume = pausedSeekVolume ?: player.volume.takeIf { it >= 0 } ?: 100
            player.setVolume(0)
        } else {
            val volume = outputMuteVolume ?: return
            outputMuteVolume = null
            if (pausedSeekVolume == null) player.setVolume(volume)
        }
    }
    private fun restorePausedSeekVolume() {
        if (outputMuteVolume != null) {
            pausedSeekVolume = null
            return
        }
        pausedSeekVolume?.let {
            val result = player.setVolume(it)
            Timber.i("VLC seek volume restored target=%d actual=%d result=%d", it, player.volume, result)
        }
        pausedSeekVolume = null
    }
    private var detachedVideoTrack: Int? = null
    private var resumeVideoSeekMs: Long? = null
    private var videoRestoreTask: Runnable? = null
    private var userVideoDisabled = false
    private val renderCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var renderCheckTask: Runnable? = null
    private var renderCheckSignature: String? = null
    private var dismissedRenderCheckSignature: String? = null
    /** Signature accepted via "keep watching anyway"; captured into title settings on stop. */
    val overloadContinueSignature: String? get() = dismissedRenderCheckSignature
    /** Set after LibVLC loses too many pictures while a live processing change is active. */
    var overloadDropPercent: Double? = null
        private set
    var renderOverloaded by mutableStateOf(false)
        private set
    fun dismissRenderOverload() {
        renderOverloaded = false
        dismissedRenderCheckSignature = renderCheckSignature
    }

    /** Restore a previously accepted "keep watching anyway" for this title's filter set. */
    fun restoreOverloadContinue(signature: String?) {
        dismissedRenderCheckSignature = signature?.takeIf { it.isNotBlank() }
    }

    fun continueDespiteOverload() {
        dismissRenderOverload()
        play()
    }
    private val surfaceCallback = object : IVLCVout.Callback {
        override fun onSurfacesCreated(vout: IVLCVout) {
            attachedView?.let(::restoreVideoTrackWhenSurfaceIsReady)
        }
        override fun onSurfacesDestroyed(vout: IVLCVout) {
            attachedView?.let { view -> videoRestoreTask?.let(view::removeCallbacks) }
            videoRestoreTask = null
        }
    }

    /**
     * libVLC scales into the vout only once it knows the target geometry. Without this the picture
     * is rendered at source resolution anchored to the surface origin instead of filling it.
     */
    private val surfaceSizeCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            val view = attachedView ?: return
            if (released || view.holder !== holder) return
            // AWindow detaches itself on surfaceDestroyed (including a buffer-size change).
            // The SurfaceView can survive that event, so Compose will not call attach again.
            if (!player.vlcVout.areViewsAttached()) {
                player.vlcVout.setVideoView(view)
                player.vlcVout.attachViews()
            }
            reportWindowSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
            restoreVideoTrackWhenSurfaceIsReady(view)
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
            reportWindowSize(width, height)
        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
    }

    private fun reportWindowSize(width: Int, height: Int) {
        if (released || width <= 0 || height <= 0) return
        runCatching {
            // The host already fits the surface. Fill it instead of preserving source DAR again.
            player.setAspectRatio("$width:$height")
            player.setScale(0f)
            player.vlcVout.setWindowSize(width, height)
        }
            .onFailure { Timber.w(it, "AV-AUDIT VLC: setWindowSize(%d, %d) failed", width, height) }
    }
    var imageOptions by mutableStateOf(initialImageOptions.normalized())
        private set
    var saveImageDefaults by mutableStateOf(false)
    var imageProcessingFallback by mutableStateOf(false)
        private set
    private var pendingImageTrackSelection: Triple<Int, Int, Float>? = null
    internal var restoredOutputAudioSelection = false
        private set

    internal fun captureOutputRestartState(): (VlcPlaybackController) -> Unit {
        val selection = pendingImageTrackSelection ?: Triple(trackAccess.audioTrack, trackAccess.spuTrack, player.rate)
        return { target ->
            target.pendingImageTrackSelection = selection
            target.restoredOutputAudioSelection = selection.first >= 0
        }
    }
    var audioVisualizationEnabled by mutableStateOf(initialAudioVisualization && !bitstreamPermitted)
        private set
    private val mutableVideoPresentation = MutableStateFlow(VideoPresentation())
    val videoPresentation = mutableVideoPresentation.asStateFlow()

    private var startupOpenedAt = 0L
    private var startupPlayingReported = false

    private var appliedAudioDevice: String? = null

    private fun configurePlayer() {
        appliedAudioDevice = null
        // Android's AudioTrack backend has an explicit encoded-output gate. --spdif alone
        // does not control this Java-side route/encoding detection, especially when OFF.
        val audioOutputSet = player.setAudioOutput("audiotrack")
        audioSession.refreshRoute(context)
        // The source codec is not necessarily known during controller construction. Keep digital
        // output off until updateSourceCodec()/track discovery has a concrete codec and plan.
        val digital = audioSession.outputPlan().mode == com.ambient.tv.ui.player.AudioOutputMode.PASSTHROUGH
        val digitalOutputSet = applyAudioOutputDevice()
        Timber.i("AV-AUDIT VLC AudioTrack=%s digital-request=%s accepted=%s", audioOutputSet, digital, digitalOutputSet)
        player.vlcVout.addCallback(surfaceCallback)
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EncounteredError -> {
                    com.ambient.tv.diagnostics.DiagnosticLog.error("playback", "VLC error ${com.ambient.tv.diagnostics.diagnosticUrl(url)}")
                    if (fallbackVulkan("playback_error")) {
                        // prepare() reopens this media with VLC's normal Android output.
                    } else if (effectiveImage().softwareProcessing && !imageProcessingFallback) {
                        // A filter/format/decoder combination can be unsupported. Retry once with
                        // the normal hardware path; a persistent source error still reaches the UI.
                        imageProcessingFallback = true
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (!released) updateImageOptions(
                                imageOptions.copy(softwareProcessing = false),
                                preservePosition = pendingRestorePositionMs != null || player.isSeekable,
                                automaticFallback = true,
                            )
                        }
                    } else if (!streamOpenRetried) {
                        // Renderer switches can leave the previous live HTTP subscription open for
                        // a brief moment. Retry the same URL once before surfacing a hard error.
                        streamOpenRetried = true
                        // Resume a VOD where it failed; a live stream (never seekable) returns to the live edge.
                        val resumePosition = vlcRestartPosition(
                            pendingRestorePositionMs, lastPlaybackTimeMs,
                            pendingRestorePositionMs != null || lastPlaybackSeekable,
                        )
                        val resumePlaying = desiredPlayWhenReady
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            if (released) return@postDelayed
                            openingOrRestarting = true
                            runCatching { player.stop() }
                            prepared = false
                            prepare(resumePosition, resumePlaying)
                        }, 350L)
                    } else errorMessage = context.getString(com.ambient.tv.R.string.vlc_playback_error)
                }
                // VLC 4 reuses EndReached's number for Stopping. Only the final
                // Stopped event can finish that session; explicit restarts are guarded.
                MediaPlayer.Event.EndReached -> if (!trackAccess.modern && !openingOrRestarting && pendingRestorePositionMs == null) ended = true
                MediaPlayer.Event.Stopped -> if (trackAccess.modern && !openingOrRestarting && pendingRestorePositionMs == null) ended = true
                MediaPlayer.Event.ESAdded -> attachedView?.let(::restoreVideoTrackWhenSurfaceIsReady)
                MediaPlayer.Event.TimeChanged -> {
                    if (!openingOrRestarting && pendingRestorePositionMs == null && player.time >= 0L) {
                        lastPlaybackTimeMs = player.time
                        lastPlaybackSeekable = player.isSeekable
                    }
                    // A recovered stream earns a new retry after it has played stably again.
                    if (streamOpenRetried && streamRetryPlayingSince > 0L &&
                        android.os.SystemClock.elapsedRealtime() - streamRetryPlayingSince >= STREAM_RETRY_RESET_MS) {
                        streamOpenRetried = false
                        streamRetryPlayingSince = 0L
                    }
                    observePendingSeek()
                }
                MediaPlayer.Event.Playing -> {
                    if (!startupPlayingReported) {
                        startupPlayingReported = true
                        com.ambient.tv.ui.player.logPlaybackStartup("vlc", "playing_event", startupOpenedAt)
                    }
                    openingOrRestarting = false
                    if (streamOpenRetried && streamRetryPlayingSince == 0L) {
                        streamRetryPlayingSince = android.os.SystemClock.elapsedRealtime()
                    }
                    ended = false
                    if (vulkanOutput) vulkanStartedAt = android.os.SystemClock.elapsedRealtime()
                    attachedView?.let(::restoreVideoTrackWhenSurfaceIsReady)
                    // Seek before audio discovery. refreshSelectedAudioCodec() can restart the
                    // media when the passthrough plan changes; that restart must see the resume
                    // target, not player.time still sitting at 0 while setTime is in flight.
                    (if (liveStream || dvPrimeWaiting) null else pendingRestorePositionMs ?: resumeVideoSeekMs)?.let { position ->
                        lastPlaybackTimeMs = position
                        lastPlaybackSeekable = true
                        if (!desiredPlayWhenReady) pauseAfterRestorePositionMs = position
                        seekState.beginReadyWait(android.os.SystemClock.elapsedRealtime())
                        if (pendingRestorePositionMs == null) seekTo(position)
                        else {
                            submitPendingSeek()
                            retryPendingSeekWhenReady()
                        }
                    }
                    resumeVideoSeekMs = null
                    refreshSelectedAudioCodec()
                    applyAudioLiveOptions()
                    logHdrDiagnostics("playing")
                    pendingImageTrackSelection?.let { (audio, subtitle, rate) ->
                        if (audioTracks().any { it.first == audio }) trackAccess.setAudioTrack(audio)
                        if (subtitle < 0 || subtitleTracks().any { it.first == subtitle }) trackAccess.setSpuTrack(subtitle)
                        player.setRate(rate)
                        pendingImageTrackSelection = null
                    }
                    if (dvPrimeActive) player.setVolume(0)
                    if (!dvPrimeActive && !desiredPlayWhenReady && pauseAfterRestorePositionMs == null) player.pause()
                    if (desiredPlayWhenReady) armRenderCheck()
                }
            }
        }
    }

    init {
        configurePlayer()
        audioSession.supportsNight = nightProcessing.available
        audioSession.apply = { old, _ ->
            val before = audioSession.outputPlan(options = old)
            val after = audioSession.outputPlan()
            if (before.mode != after.mode || before.encoded != after.encoded) restartAudio()
            else applyAudioLiveOptions()
        }
        audioSession.routeChanged = { before, after ->
            if (before.encoded != after.encoded || before.spdifCodecs != after.spdifCodecs) restartAudio()
            else applyAudioLiveOptions()
        }
        audioSession.startRouteMonitoring(context)
        player.setVideoTrackEnabled(false)
        // Creating a Vulkan surface temporarily connects its native window. Probe
        // it before VLC starts, never while another renderer owns the same window.
        if (!vulkanOutput) android.os.Handler(android.os.Looper.getMainLooper()).post {
            // Audio-only playback can start headless. Video prepare waits for its surface.
            if (!released && !prepared) prepare()
        }
    }

    fun updateSourceCodec(codec: String?, channels: Int? = audioSession.sourceChannels) {
        val normalized = com.ambient.tv.ui.player.normalizeSourceCodec(codec) ?: codec?.trim()?.lowercase()
        if (audioSession.sourceCodec == normalized && audioSession.sourceChannels == channels) return
        val before = audioSession.outputPlan()
        audioSession.sourceCodec = normalized
        audioSession.sourceChannels = channels
        audioSession.refreshRoute(context)
        val after = audioSession.outputPlan()
        if (before.mode != after.mode || before.encoded != after.encoded) restartAudio()
        else applyAudioLiveOptions()
    }

    private fun fourCc(value: Int): String = buildString(4) {
        repeat(4) { index ->
            val c = (value ushr (index * 8)) and 0xff
            if (c in 32..126) append(c.toChar())
        }
    }

    private fun refreshSelectedAudioCodec(trackId: Int = currentAudioTrackId()) {
        if (released) return
        trackAccess.mediaTracks(0).firstOrNull { it.id == trackId }?.let { updateSourceCodec(it.codec, it.channels) }
    }

    fun refreshAudioCodec() = refreshSelectedAudioCodec()

    /** Prefer the live clock; during async seeks/teardown fall back to the last confirmed time. */
    private fun confirmedPlaybackTimeMs(): Long = pendingRestorePositionMs
        ?: player.time.takeIf { it > 0L } ?: lastPlaybackTimeMs

    /** Live timeshift: VLC clock position to keep across the next [prepare] (audio/route restart). */
    private var pendingTimeshiftKeepMs: Long? = null

    private fun restartAudio() {
        if (released) return
        if (liveStream) pendingTimeshiftKeepMs = confirmedPlaybackTimeMs().coerceAtLeast(0L)
        val position = vlcRestartPosition(
            pendingRestorePositionMs,
            confirmedPlaybackTimeMs(),
            pendingRestorePositionMs != null || player.isSeekable || lastPlaybackTimeMs > 0L,
        )
        val playing = wantsToPlay()
        rememberTrackSelection()
        nightProcessing.release()
        if (audioSession.outputPlan().encoded) audioVisualizationEnabled = false
        openingOrRestarting = true
        player.stop()
        if (!applyAudioOutputDevice()) audioSession.warning = "audio output"
        prepared = false
        prepare(position, playing)
    }

    private fun rememberTrackSelection() {
        val previous = pendingImageTrackSelection ?: Triple(trackAccess.audioTrack, trackAccess.spuTrack, player.rate)
        pendingImageTrackSelection = previous.copy(third = if (bitstreamPermitted) 1f else previous.third)
    }

    override var subtitleDelayMs by mutableStateOf(0)
        private set

    override fun setSubtitleDelay(delayMs: Int) {
        subtitleDelayMs = delayMs.coerceIn(-com.ambient.tv.ui.player.SubtitleDelayTarget.LIMIT_MS, com.ambient.tv.ui.player.SubtitleDelayTarget.LIMIT_MS)
        if (!released) runCatching { player.setSpuDelay(subtitleDelayMs * 1000L) }
    }

    private fun applyAudioOutputDevice(): Boolean {
        val plan = audioSession.outputPlan()
        val device = if (plan.mode == com.ambient.tv.ui.player.AudioOutputMode.PASSTHROUGH) vlcEncodedDevice(plan.spdifCodecs) else if (
            plan.channels in setOf("stereo", "mono", "left", "right") || audioSession.outputPcmChannels < 6
        ) "stereo" else "pcm"
        if (appliedAudioDevice == device) return true
        // Explicit devices bypass LibVLC Java's cached digital-output toggle.
        val accepted = player.setAudioOutputDevice(device)
        if (accepted) appliedAudioDevice = device
        return accepted
    }

    private fun applyAudioLiveOptions() {
        if (released) return
        // Re-applied with the audio options, which run again after every media restart.
        runCatching { player.setSpuDelay(subtitleDelayMs * 1000L) }
        val plan = audioSession.outputPlan()
        val raw = audioSession.options
        val o = if (plan.applyDsp && !plan.encoded) raw.pcmOptions(audioSession.sourcePassthrough, encodedOutput = false) else raw.pcmOptions(audioSession.sourcePassthrough, encodedOutput = true)
        val errors = mutableListOf<String>()
        if (!applyAudioOutputDevice()) errors.add("audio output")
        if (!nightProcessing.apply(o.night)) errors.add("Android DynamicsProcessing")
        if (!player.setAudioDelay(raw.delayMs * 1000L)) errors.add("audio delay")
        val reserve = if (!plan.encoded && appliedAudioDevice == "stereo")
            vlcDownmixHeadroomDb(audioSession.sourceChannels) else 0f
        val eq = if (o.eqEnabled || o.dialogue > 0 || reserve > 0f) MediaPlayer.Equalizer.create().apply {
            val gains = List(10) { i ->
                (if (o.eqEnabled) o.eqDb[i] else 0) + (if (i == 4 || i == 5) o.dialogue else 0)
            }
            // Reserve headroom for boosts even when the optional Android limiter is off.
            val preamp = (if (o.eqEnabled) o.preampDb else 0) - (gains.maxOrNull() ?: 0).coerceAtLeast(0) - reserve
            val excess = (-20f - preamp).coerceAtLeast(0f)
            setPreAmp(preamp.coerceAtLeast(-20f))
            repeat(minOf(10, MediaPlayer.Equalizer.getBandCount())) { setAmp(it, (gains[it] - excess).coerceAtLeast(-20f)) }
        } else null
        if (!player.setEqualizer(eq)) errors.add("equalizer")
        audioSession.warning = errors.takeIf { it.isNotEmpty() }?.joinToString()
    }

    fun attach(view: SurfaceView) {
        if (audioOnly && !audioVisualizationEnabled) return
        if (released) return
        if (attachedView === view || pendingAttachView === view) return
        pendingAttachView = view
        if (hdrOutput.deferUntilStopped(replacementView = view) {
                if (pendingAttachView === view && !released) {
                    pendingAttachView = null
                    attach(view)
                }
            }) return
        pendingAttachView = null
        attachedView?.let(::detach)
        dvSurfacePosition?.let { position ->
            dvSurfacePosition = null
            val retiring = player
            val retiringVolume = pausedSeekVolume ?: retiring.volume.takeIf { it >= 0 } ?: 100
            retiring.setEventListener(null)
            retiring.vlcVout.removeCallback(surfaceCallback)
            player = MediaPlayer(libVlc)
            // A newly created VLC audio output can report an unset volume (-1).
            // Carry the intended volume into priming instead of reading that sentinel.
            player.setVolume(retiringVolume)
            pausedSeekVolume = retiringVolume
            trackAccess.rebind(player)
            prepared = false
            detachedVideoTrack = null
            resumeVideoSeekMs = null
            pendingRestorePositionMs = position
            configurePlayer()
            val retiringLibVlc = libVlc
            libVlcRelease.retiring(retiringLibVlc)
            kotlin.concurrent.thread(name = "vlc-dv-surface-stop", isDaemon = true) {
                runCatching { retiring.stop() }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching { retiring.release() }
                    libVlcRelease.retired(retiringLibVlc)
                }
            }
        }
        attachedView = view
        hdrOutput.attach(view)
        // VLC 4's newly selected MediaCodec decoder needs fresh keyframe input
        // after being recreated while paused. Seek only when the user resumes.
        if (trackAccess.modern && detachedVideoTrack != null && !desiredPlayWhenReady && player.isSeekable)
            resumeVideoSeekMs = player.time.coerceAtLeast(0L)
        if (vulkanOutput) vulkanStartedAt = android.os.SystemClock.elapsedRealtime()
        player.vlcVout.setVideoView(view)
        player.vlcVout.attachViews()
        view.holder.addCallback(surfaceSizeCallback)
        // A surface that already exists gets no fresh surfaceChanged on re-attach.
        view.holder.surface?.takeIf { it.isValid }?.let {
            val frame = view.holder.surfaceFrame
            reportWindowSize(frame.width(), frame.height())
        }
        Timber.i("HDR-AUDIT VLC surface attached view=%dx%d", view.width, view.height)
        restoreVideoTrackWhenSurfaceIsReady(view)
    }

    private fun restoreVideoTrackWhenSurfaceIsReady(view: SurfaceView) {
        videoRestoreTask?.let(view::removeCallbacks)
        var attempt = 0
        val task = object : Runnable {
            override fun run() {
                if (released || attachedView !== view || userVideoDisabled) return
                // Surface creation and ES discovery are independent. The old one-second
                // timer could expire while AudioTrack/HDMI or network initialization continued.
                if (view.holder.surface.isValid) {
                    if (vulkanOutput && !vulkanSurfaceChecked) {
                        vulkanSurfaceChecked = true
                        if (!VideoAccelerationRuntime.surfaceSupported(view.holder.surface, "vlc")) {
                            fallbackVulkan("surface")
                            return
                        }
                    }
                    if (!prepared) prepare()
                    val available = videoTracks().map { it.first }.filter { it >= 0 }
                    val preferred = detachedVideoTrack?.takeIf { it in available }
                    if (preferred != null) trackAccess.setVideoTrack(preferred) else {
                        player.setVideoTrackEnabled(true)
                    }
                    // VLC 4 accepts selection asynchronously; a successful request
                    // is not proof that a decoder/vout has reattached yet.
                    if (trackAccess.videoTrack >= 0) {
                        detachedVideoTrack = null
                        videoRestoreTask = null
                        Timber.i("AV-AUDIT VLC video restored track=%d attempts=%d", trackAccess.videoTrack, attempt)
                        return
                    }
                }
                if (++attempt < 80) view.postDelayed(this, 125L) else {
                    videoRestoreTask = null
                    if (videoTracks().any { it.first >= 0 }) Timber.w("AV-AUDIT VLC: video ES exists but could not be enabled on the attached surface")
                }
            }
        }
        videoRestoreTask = task
        view.post(task)
    }

    fun detach(view: SurfaceView) {
        if (pendingAttachView === view) pendingAttachView = null
        if (released || attachedView !== view) return
        if (dvResumeWorkaround && prepared) {
            dvSurfacePosition = confirmedPlaybackTimeMs()
            pendingImageTrackSelection = pendingImageTrackSelection ?:
                Triple(trackAccess.audioTrack, trackAccess.spuTrack, player.rate)
        }
        dvPrimeTask?.let(pendingSeekHandler::removeCallbacks)
        dvPrimeTask = null
        dvPrimeWaiting = false
        dvPrimeActive = false
        restorePausedSeekVolume()
        hdrOutput.detach()
        view.holder.removeCallback(surfaceSizeCallback)
        videoRestoreTask?.let(view::removeCallbacks)
        videoRestoreTask = null
        trackAccess.videoTrack.takeIf { it >= 0 }?.let { detachedVideoTrack = it }
        Timber.i("AV-AUDIT VLC detach track=%d", trackAccess.videoTrack)
        // Disable vout before its surface disappears; audio and demuxing keep running.
        player.setVideoTrackEnabled(false)
        player.vlcVout.detachViews()
        attachedView = null
    }

    override fun detachVideoOutput() {
        pendingAttachView = null
        if (released) return
        val view = attachedView
        if (view != null) detach(view) else player.setVideoTrackEnabled(false)
    }

    private fun prepare(resumePositionMs: Long = pendingRestorePositionMs ?: startPositionMs, resumePlaying: Boolean = desiredPlayWhenReady) {
        if (released) return
        if (previousReleaseOutcome == null && previousAudioRelease.isDone) {
            // Nothing to wait for: open in this call, not one main-thread round trip later.
            previousReleaseOutcome = previousReleaseOutcome(true, previousAudioRelease.failed, 0L)
            if (previousReleaseOutcome == PreviousReleaseOutcome.READY_AFTER_FAILURE)
                Timber.w(previousAudioRelease.failure, "VLC start: previous player failed to close; starting anyway")
        }
        if (previousReleaseOutcome == null) {
            pendingRestorePositionMs = resumePositionMs
            desiredPlayWhenReady = resumePlaying
            if (!awaitingAudioRelease) {
                awaitingAudioRelease = true
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                waitingForPreviousPlayer = !previousAudioRelease.isDone
                previousAudioRelease.whenSettled(delay = { ms, block -> main.postDelayed(block, ms) }) { outcome ->
                    main.post {
                        awaitingAudioRelease = false
                        waitingForPreviousPlayer = false
                        previousReleaseOutcome = outcome
                        val waited = android.os.SystemClock.elapsedRealtime() - createdAt
                        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("vlc", "previous player $outcome after $waited ms")
                        when (outcome) {
                            PreviousReleaseOutcome.TIMED_OUT -> {
                                Timber.w("VLC start: previous player still closing after %d ms; not starting", waited)
                                if (!released) errorMessage = context.getString(com.ambient.tv.R.string.audio_output_release_failed)
                            }
                            PreviousReleaseOutcome.READY_AFTER_FAILURE -> {
                                Timber.w(previousAudioRelease.failure, "VLC start: previous player failed to close; starting anyway")
                                if (!released) prepare()
                            }
                            PreviousReleaseOutcome.READY -> {
                                if (waited >= 1_000L) Timber.i("VLC start waited %d ms for the previous player", waited)
                                if (!released) prepare()
                            }
                        }
                    }
                }
            }
            return
        }
        if (previousReleaseOutcome == PreviousReleaseOutcome.TIMED_OUT) return
        if (!applyAudioOutputDevice()) {
            errorMessage = context.getString(com.ambient.tv.R.string.audio_output_config_failed)
            return
        }
        // Compose can acquire the next controller one frame before its SurfaceView exists.
        // VLC 4 may select the new media's video track despite setVideoTrackEnabled(false).
        // Opening here would let EGL create a window against an unready surface and leave
        // the decoder stuck at zero. attach() retries once the actual output is ready.
        if (!audioOnly && attachedView?.holder?.surface?.isValid != true) {
            prepared = false
            pendingRestorePositionMs = resumePositionMs.takeIf { !liveStream && (it > 0L || pendingRestorePositionMs != null) }
            desiredPlayWhenReady = resumePlaying
            return
        }
        if (BackgroundPlaybackSession.owns(this)) com.ambient.tv.ui.player.PlaybackOutputTransition.begin(context)
        val prepareToken = ++deferredPrepareGeneration
        if (hdrOutput.ownsSurface) {
            pendingRestorePositionMs = resumePositionMs.takeUnless { liveStream }
            desiredPlayWhenReady = resumePlaying
            if (hdrOutput.deferUntilStopped {
                    // Coalesce restarts; a seek/play/pause during GPU teardown is newer intent.
                    if (!released && prepareToken == deferredPrepareGeneration)
                        prepare(pendingRestorePositionMs ?: resumePositionMs, desiredPlayWhenReady)
                }) return
        }
        pendingSeekTask?.let(pendingSeekHandler::removeCallbacks)
        pendingSeekTask = null
        startupOpenedAt = System.nanoTime()
        startupPlayingReported = false
        val reopening = initialOpenReported
        if (!initialOpenReported) {
            initialOpenReported = true
            com.ambient.tv.ui.player.logPlaybackStartup("vlc", "initial_open_requested", startupConstructionAt,
                "network_cache_ms=1200 separate_audio=${audioSlaveUrl != null}")
        }
        openingOrRestarting = true
        errorMessage = null
        ended = false
        desiredPlayWhenReady = resumePlaying
        // Live clocks can be positive without being seekable. A filter/audio restart must
        // reopen at the live edge, not queue a VOD seek into an unbounded transport stream.
        val hadPendingSeek = pendingRestorePositionMs != null
        pendingRestorePositionMs = com.ambient.tv.ui.player.rendererReopenPositionMs(liveStream, resumePositionMs)
            .takeIf { !liveStream && (it > 0L || hadPendingSeek) }
        pauseAfterRestorePositionMs = null
        if (liveStream) resumeVideoSeekMs = null
        val effectiveImage = effectiveImage()
        val targetRate = svpOutputFps(svpDisplayFps(context), effectiveImage.svp)
        val effectiveDeinterlaceMode = if (preserveHdrSource) "off" else deinterlaceMode
        val effectiveDeinterlace = if (preserveHdrSource) "auto" else effectiveDeinterlaceFilter(deinterlaceFilter, reducedVideoEffects(context))
        val reattach = if (configuredSoftwareProcessing != effectiveImage.softwareProcessing ||
            configuredSvpOptions != effectiveImage.svp || configuredSvpMode != effectiveImage.svpMode || svpTargetChanged(effectiveImage.svpMode, configuredSvpRate, targetRate) ||
            configuredDeinterlaceMode != effectiveDeinterlaceMode || configuredDeinterlaceFilter != effectiveDeinterlace) {
            val view = attachedView
            view?.let(::detach)
            player.setEventListener(null)
            player.vlcVout.removeCallback(surfaceCallback)
            player.stop(); player.release(); libVlcRelease.releaseWhenIdle(libVlc)
            configuredSoftwareProcessing = effectiveImage.softwareProcessing
            configuredSvpOptions = effectiveImage.svp
            configuredSvpMode = effectiveImage.svpMode
            configuredSvpRate = targetRate
            configuredDeinterlaceMode = effectiveDeinterlaceMode
            configuredDeinterlaceFilter = effectiveDeinterlace
            libVlc = createLibVlc(); player = MediaPlayer(libVlc)
            trackAccess.rebind(player); configurePlayer()
            view
        } else null
        // A new Media drops added slaves and renumbers tracks: forget old subtitle ids and requests.
        externalSubtitleRequests.clear()
        externalSubtitleSources.clear()
        pendingExternalSubtitle = null
        // An audio/route restart keeps the timeshift position; other reopens go to the live edge.
        val keepTimeshiftMs = pendingTimeshiftKeepMs.also { pendingTimeshiftKeepMs = null }
        val playbackUrl = keepTimeshiftMs?.let { com.ambient.tv.livetv.LiveTimeshift.playbackUrlKeepingPosition(url, it, "vlc-audio")?.first }
            ?: com.ambient.tv.livetv.LiveTimeshift.playbackUrl(url, reopening || reopeningTimeshift)
        val media = Media(libVlc, Uri.parse(if (playbackUrl == url) authProxy?.url ?: url else playbackUrl))
        if (!preferOriginalAudio) media.addOption(":audio-language=" + com.ambient.tv.data.local.LanguagePreferences.playbackLanguageTags(
            videoImagePreferences(context).playbackAudioLanguage).joinToString(","))
        media.addOption(":sub-language=" + com.ambient.tv.data.local.LanguagePreferences.playbackLanguageTags(
            videoImagePreferences(context).playbackSubtitleLanguage).joinToString(","))
        // Re-added on every (re)open: slaves belong to the Media, which each prepare recreates.
        audioSlaveUrl?.let {
            // The MP4 demuxer consumes whole YouTube fragments per demux call;
            // paired audio can run seconds ahead and reset the shared clock.
            // avformat demuxes packets and keeps the slave aligned with video.
            media.addOption(":demux=avformat")
            media.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Audio, 4, it))
        }
        // Seek on Playing instead of :start-time. VLC 4 treats start-time as a
        // clipped timeline, which otherwise shifts both duration and later seeks.
        // Respect VLC/device codec blacklists and retain software fallback.
        // Sparsam forces hardware/direct (no hqdn3d/gradfun software path) without mutating prefs.
        media.setHWDecoderEnabled(!effectiveImage.softwareProcessing, false)
        if (vulkanOutput || toneMappingRequired) {
            // VLC's opaque MediaCodec Surface pictures cannot be imported by this Vulkan vout.
            // Keep MediaCodec decoding with CPU-accessible output; software effects may opt out.
            media.addOption(":no-mediacodec-dr")
        }
        applyDeinterlaceOptions(media)
        vlcImageMediaOptions(effectiveImage, configuredSvpRate).forEach(media::addOption)
        if (audioVisualizationEnabled && !bitstreamPermitted) {
            // VLC's visual module performs a real FFT over decoded audio and renders it through
            // the same vout surface. This is preferable to synthesizing movement in Compose.
            media.addOption(":audio-visual=visual")
            media.addOption(":effect-list=spectrum")
            media.addOption(":visual-80-bands=1")
            media.addOption(":visual-peaks=1")
            media.addOption(":effect-width=1280")
            media.addOption(":effect-height=720")
        }
        headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.value?.takeIf { it.isNotBlank() }?.let { media.addOption(":http-user-agent=$it") }
        headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value?.takeIf { it.isNotBlank() }?.let { media.addOption(":http-referrer=$it") }
        headers.entries.firstOrNull { it.key.equals("Cookie", true) }?.value?.takeIf { it.isNotBlank() }?.let { media.addOption(":http-cookie=$it") }
        // Reconnect at the current offset when an HTTP stream breaks off (all sources, not only YouTube).
        if (url.startsWith("http", ignoreCase = true)) media.addOption(":http-reconnect")
        // Live TV: tolerate clock jitter instead of resetting the clock; more cache on the
        // timeshift loopback (added after the YouTube cache below would not apply there).
        com.ambient.tv.ui.player.vlcLiveMediaOptions(liveStream, com.ambient.tv.livetv.isLiveTimeshiftUrl(playbackUrl))
            .forEach(media::addOption)
        if (networkCache) {
            // YouTube-only. googlevideo range requests drop without reconnect. Keep VLC's
            // 1200 ms cache so the first frame is not held behind a larger buffer.
            // Media-level caching overrides LibVLC's default.
            media.addOption(":network-caching=1200")
        }
        player.media = media
        media.release()
        prepared = true
        if (dvResumeWorkaround && (pendingRestorePositionMs ?: 0) > 0) beginDvPrime()
        applyAudioLiveOptions()
        player.play()
        // Pause on the Playing event, after demux and the pending seek are ready.
        // Pausing immediately can interrupt VLC 4's initial stream probe.
        reattach?.let(::attach)
    }


    private fun fallbackVulkan(reason: String): Boolean {
        if (!vulkanOutput || released) return false
        VideoAccelerationRuntime.reportFallback("vlc", reason)
        if (vulkanRequested) VideoAccelerationRuntime.notifyUnavailable()
        vulkanOutput = false // Prevent retry loops before posting the asynchronous restart.
        usesAndroidSurfaceOutput = true
        vulkanStartedAt = 0L
        accelerationFallback = "Vulkan → Android Surface / OpenGL ES: $reason"
        Timber.w("VULKAN VLC fallback: %s", reason)
        val position = vlcRestartPosition(
            pendingRestorePositionMs,
            confirmedPlaybackTimeMs(),
            pendingRestorePositionMs != null || player.isSeekable || lastPlaybackTimeMs > 0L,
        )
        val playing = desiredPlayWhenReady
        rememberTrackSelection()
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!released) {
                openingOrRestarting = true
                val view = attachedView
                view?.let(::detach)
                player.setEventListener(null)
                player.vlcVout.removeCallback(surfaceCallback)
                player.stop()
                player.release()
                libVlcRelease.releaseWhenIdle(libVlc)
                libVlc = createLibVlc()
                player = MediaPlayer(libVlc)
                trackAccess.rebind(player)
                configurePlayer()
                prepared = false
                prepare(position, playing)
                view?.let(::attach)
            }
        }
        return true
    }

    fun checkVulkanOutput() {
        if (!vulkanOutput || released || !player.isPlaying || attachedView?.holder?.surface?.isValid != true || vulkanStartedAt == 0L) return
        if (android.os.SystemClock.elapsedRealtime() - vulkanStartedAt < 8_000L) return
        val stats = runtimeStats()
        if ((stats?.displayedPictures ?: 0) > 0) VideoAccelerationRuntime.reportOutput("vlc", frames = true)
        if ((stats?.decodedVideo ?: 0) > 0 && stats?.displayedPictures == 0) fallbackVulkan("no_video_frames")
    }

    /**
     * VLC applies filter and deinterlacer changes by reopening the media. Once that reopened
     * session is actually playing, sample its lost-picture counter with the same steady 3+6 s
     * window used by MPV. Stats are optional in libVLC, so absent counters never pause playback.
     */
    private fun armRenderCheck() {
        val effective = effectiveImage()
        val effectiveDeinterlace = if (preserveHdrSource) "auto" else effectiveDeinterlaceFilter(deinterlaceFilter, reducedVideoEffects(context))
        val signature = vlcRenderCheckSignature(effective, if (preserveHdrSource) "off" else deinterlaceMode, effectiveDeinterlace)
        renderCheckTask?.let(renderCheckHandler::removeCallbacks)
        renderCheckTask = null
        renderCheckSignature = signature
        // "Keep watching anyway" covers only the settings that overloaded; any change ends it.
        if (signature != dismissedRenderCheckSignature) dismissedRenderCheckSignature = null
        if (signature == null || signature == dismissedRenderCheckSignature) return

        val sampleSeconds = mpvRenderSampleSeconds(false, videoImagePreferences(context).frameDropTestSeconds)
        val keepChecking = effective.svpMode != "off"
        val task = object : Runnable {
            var steadySeconds = 0
            var baselineLostPictures: Long? = null

            override fun run() {
                if (released || !desiredPlayWhenReady || !player.isPlaying || renderCheckSignature != signature) {
                    renderCheckTask = null
                    return
                }
                val stats = runtimeStats()
                if (com.ambient.tv.ui.player.PlaybackOutputTransition.active() || openingOrRestarting) {
                    steadySeconds = 0
                    baselineLostPictures = null
                    renderCheckHandler.postDelayed(this, 1_000L)
                    return
                }
                val fps = vlcRenderSampleFps(stats?.videoFps, configuredSvpMode, configuredSvpRate)
                val lost = stats?.lostPictures?.toLong()
                if (fps == null || lost == null) {
                    renderCheckHandler.postDelayed(this, 1_000L)
                    return
                }
                steadySeconds++
                if (steadySeconds == 3) baselineLostPictures = lost
                if (steadySeconds < 3 + sampleSeconds.toInt()) {
                    renderCheckHandler.postDelayed(this, 1_000L)
                    return
                }
                val dropped = lost - (baselineLostPictures ?: lost)
                if (dropped < 0L) {
                    steadySeconds = 0
                    baselineLostPictures = null
                    renderCheckHandler.postDelayed(this, 1_000L)
                    return
                }
                if (vlcRenderOverloaded(dropped, fps, sampleSeconds)) {
                    Timber.w("VLC render overload: %d lost pictures in %.0f s at %.3f fps with %s", dropped, sampleSeconds, fps, signature)
                    pause()
                    overloadDropPercent = com.ambient.tv.ui.player.frameDropPercent(dropped, fps * sampleSeconds)
                    renderOverloaded = true
                    renderCheckTask = null
                    return
                }
                if (!keepChecking) {
                    renderCheckTask = null
                    return
                }
                steadySeconds = 3
                baselineLostPictures = lost
                renderCheckHandler.postDelayed(this, 1_000L)
            }
        }
        renderCheckTask = task
        renderCheckHandler.postDelayed(task, 1_000L)
    }

    private fun applyDeinterlaceOptions(media: Media) {
        val mode = when (if (preserveHdrSource) "off" else deinterlaceMode) {
            "auto" -> -1
            "on" -> 1
            else -> 0
        }
        val filter = if (preserveHdrSource) "auto" else effectiveDeinterlaceFilter(deinterlaceFilter, reducedVideoEffects(context))
        media.addOption(":deinterlace=$mode")
        // Explicit CPU algorithms need non-opaque pictures; retain MediaCodec decoding.
        if (mode != 0 && filter != "auto") media.addOption(":no-mediacodec-dr")
        if (filter.isNotBlank()) media.addOption(":deinterlace-mode=$filter")
    }

    fun setDeinterlace(modeKey: String, filterKey: String, preservePosition: Boolean = true) {
        if (released) return
        val normalizedMode = modeKey.takeIf { it in setOf("off", "auto", "on") } ?: "off"
        val normalizedFilter = com.ambient.tv.data.local.DeinterlaceCatalog.normalize("vlc", filterKey)
        if (normalizedMode == deinterlaceMode && normalizedFilter == deinterlaceFilter) return
        deinterlaceMode = normalizedMode
        deinterlaceFilter = normalizedFilter
        if (preserveHdrSource) return
        val resumePosition = vlcRestartPosition(pendingRestorePositionMs, confirmedPlaybackTimeMs(), preservePosition)
        val resumePlaying = wantsToPlay()
        rememberTrackSelection()
        openingOrRestarting = true
        runCatching { player.stop() }
        prepared = false
        prepare(resumePosition, resumePlaying)
    }

    fun updateImageOptions(
        options: VlcImageOptions,
        preservePosition: Boolean = true,
        automaticFallback: Boolean = false,
    ) {
        val safe = options.normalized()
        if (!automaticFallback) imageProcessingFallback = false
        if (released || safe == imageOptions) return
        imageOptions = safe
        if (preserveHdrSource) return
        val resumePosition = vlcRestartPosition(pendingRestorePositionMs, confirmedPlaybackTimeMs(), preservePosition)
        val resumePlaying = wantsToPlay()
        rememberTrackSelection()
        openingOrRestarting = true
        runCatching { player.stop() }
        prepared = false
        prepare(resumePosition, resumePlaying)
    }

    /** Re-open media so Sparsam effective overrides (or leaving Low) take effect without changing prefs. */
    fun refreshEffectiveOverrides(preservePosition: Boolean = true) {
        if (released || preserveHdrSource) return
        val resumePosition = vlcRestartPosition(pendingRestorePositionMs, confirmedPlaybackTimeMs(), preservePosition)
        val resumePlaying = wantsToPlay()
        rememberTrackSelection()
        openingOrRestarting = true
        runCatching { player.stop() }
        prepared = false
        prepare(resumePosition, resumePlaying)
    }

    fun setAudioVisualizationEnabled(enabled: Boolean, preservePosition: Boolean = true) {
        if (released) return
        if (enabled && bitstreamPermitted) return
        if (enabled == audioVisualizationEnabled) return
        val resumePosition = vlcRestartPosition(pendingRestorePositionMs, confirmedPlaybackTimeMs(), preservePosition)
        val resumePlaying = wantsToPlay()
        rememberTrackSelection()
        audioVisualizationEnabled = enabled
        openingOrRestarting = true
        runCatching { player.stop() }
        prepared = false
        prepare(resumePosition, resumePlaying)
    }

    fun playPause() {
        if (released) return
        if (wantsToPlay()) pause() else play()
    }

    override fun play() {
        if (released) return
        desiredPlayWhenReady = true
        if (!dvPrimeActive) restorePausedSeekVolume()
        hdrOutput.discontinuity()
        pauseAfterRestorePositionMs = null
        // A decoder recreated on a paused surface may need fresh input before
        // VLC emits Playing. Submit the saved seek before waiting for that event.
        resumeVideoSeekMs?.takeIf { !liveStream }?.let { position ->
            resumeVideoSeekMs = null
            seekTo(position)
        }
        if (!prepared) prepare(resumePlaying = true) else if (ended) prepare(0, true) else if (errorMessage != null) prepare(confirmedPlaybackTimeMs(), true) else player.play()
    }

    override fun pause() {
        hdrOutput.discontinuity()
        desiredPlayWhenReady = false
        pauseAfterRestorePositionMs = null
        if (!released && !dvPrimeActive) player.pause()
    }

    override fun wantsToPlay(): Boolean = !released && desiredPlayWhenReady && !ended && errorMessage == null

    /** Native playing flag for UI snapshots; false once released (the native player may be gone). */
    fun isPlayingNow(): Boolean = !released && runCatching { player.isPlaying }.getOrDefault(false)

    /** A transcoded HTTP response may have no VLC seek index despite having a server start offset. */
    fun canSeekNow(): Boolean = !released && prepared && !openingOrRestarting && player.isSeekable

    internal fun hasPendingSeek(): Boolean = pendingRestorePositionMs != null

    override fun status(): PlaybackStatus {
        if (released) return PlaybackStatus()
        if (imageOptions.svpMode != "off" || configuredSvpMode != "off") {
            if (measuredSourceFps == null) measuredSourceFps = currentSourceFps()
            if (measuredSourceSize == null) measuredSourceSize = currentSourceSize()
            // prepare() returns early without a valid surface; restarting here would stop/prepare every poll.
            val outputReady = audioOnly || attachedView?.holder?.surface?.isValid == true
            val effectiveMode = effectiveImage().svpMode
            if (outputReady && (effectiveMode != configuredSvpMode || svpTargetChanged(effectiveMode, configuredSvpRate, svpOutputFps(svpDisplayFps(context), imageOptions.svp))))
                refreshEffectiveOverrides(player.isSeekable)
        }
        checkVulkanOutput()
        return PlaybackStatus(confirmedPlaybackTimeMs().coerceAtLeast(0), player.length.coerceAtLeast(0), player.isPlaying && (!dvPrimeActive || desiredPlayWhenReady), ended, errorMessage)
    }

    fun seekBy(deltaMs: Long) {
        // Key handlers can still fire between Stop and the next recomposition; a released
        // libVLC MediaPlayer throws IllegalStateException on every native call.
        if (released) return
        val length = player.length.takeIf { it > 0L } ?: Long.MAX_VALUE
        seekTo((confirmedPlaybackTimeMs() + deltaMs).coerceIn(0L, length))
    }

    override fun seekTo(positionMs: Long) {
        if (released || liveStream) return
        if (ended) {
            ended = false
            prepare(positionMs.coerceAtLeast(0), desiredPlayWhenReady)
            return
        }
        if (dvPrimeWaiting) { pendingRestorePositionMs = positionMs.coerceAtLeast(0); return }
        val length = player.length.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = positionMs.coerceIn(0L, length)
        hdrOutput.discontinuity()
        lastPlaybackTimeMs = target
        pendingSeekTask?.let(pendingSeekHandler::removeCallbacks)
        pendingSeekTask = null
        pendingRestorePositionMs = target // Zero is an explicit seek, not "no restore".
        if (!prepared || openingOrRestarting) return
        seekState.beginReadyWait(android.os.SystemClock.elapsedRealtime())
        if (!desiredPlayWhenReady) pauseAfterRestorePositionMs = target
        submitPendingSeek()
        retryPendingSeekWhenReady()
    }

    private fun submitPendingSeek() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!seekState.shouldSubmit(prepared && !openingOrRestarting && player.isSeekable, now)) return
        val target = pendingRestorePositionMs ?: return
        // Precise seeking avoids exposing a preceding keyframe as the requested landing.
        val accepted = runCatching { player.setTime(target, false) >= 0L }.getOrDefault(false)
        seekState.submitted(accepted, now)
        // VLC 4 can keep its native clock frozen on a paused seek, especially zero.
        // Decode silently until the requested native clock is confirmed, then pause again.
        if (accepted && !desiredPlayWhenReady && !player.isPlaying) {
            if (pausedSeekVolume == null) pausedSeekVolume = player.volume.takeIf { it >= 0 } ?: 100
            player.setVolume(0)
            player.play()
        }
    }

    private fun observePendingSeek() {
        if (released || dvPrimeWaiting || openingOrRestarting || errorMessage != null) return
        if (!seekState.observe(player.time, android.os.SystemClock.elapsedRealtime())) return
        lastPlaybackTimeMs = player.time.coerceAtLeast(0L)
        lastPlaybackSeekable = player.isSeekable
        pendingSeekTask?.let(pendingSeekHandler::removeCallbacks)
        pendingSeekTask = null
        pauseAfterRestorePositionMs = null
        if (!desiredPlayWhenReady) player.pause()
        restorePausedSeekVolume()
        dvPrimeActive = false
        Timber.i("AV-AUDIT VLC seek clock confirmed at %d", lastPlaybackTimeMs)
    }

    private fun retryPendingSeekWhenReady() {
        if (pendingSeekTask != null || released || pendingRestorePositionMs == null) return
        val task = object : Runnable {
            override fun run() {
                pendingSeekTask = null
                if (released || !prepared || errorMessage != null || pendingRestorePositionMs == null) return
                observePendingSeek()
                if (pendingRestorePositionMs == null) return
                if (seekState.expired(android.os.SystemClock.elapsedRealtime())) {
                    // Preserve the request for a renderer fallback/retry. Continuing at zero
                    // would silently lose the user's position and rewrite server progress.
                    Timber.w("AV-AUDIT VLC seek not confirmed; target=%d", pendingRestorePositionMs)
                    errorMessage = context.getString(R.string.player_seek_failed)
                    pauseAfterRestorePositionMs = null
                    player.pause()
                    restorePausedSeekVolume()
                    return
                }
                submitPendingSeek()
                pendingSeekTask = this
                pendingSeekHandler.postDelayed(this, 200L)
            }
        }
        pendingSeekTask = task
        pendingSeekHandler.postDelayed(task, 200L)
    }

    override fun restoreAfterRendererSwitch(positionMs: Long, playWhenReady: Boolean, isLive: Boolean) {
        if (released) return
        desiredPlayWhenReady = playWhenReady
        if (!isLive) {
            val resume = positionMs.coerceAtLeast(0L)
            pendingRestorePositionMs = resume
            lastPlaybackTimeMs = resume
            lastPlaybackSeekable = true
            if (prepared) seekTo(resume)
        }
        // A newly created engine has no Media yet; prepare() consumes this intent.
        if (prepared && !openingOrRestarting) {
            if (playWhenReady) player.play() else if (pendingRestorePositionMs == null) player.pause()
        }
    }

    fun setVideoPresentation(resizeMode: String, aspect: String?, zoomFactor: Float) {
        BackgroundPlaybackSession.setVideoPresentation(VideoPresentation(
            resizeMode = videoResizeMode(resizeMode),
            aspectRatio = videoAspectRatio(aspect),
            zoom = zoomFactor,
        ))
    }

    override fun applyVideoPresentation(presentation: VideoPresentation) {
        mutableVideoPresentation.value = presentation
    }

    private fun logHdrDiagnostics(reason: String) {
        if (released) return
        val track = runCatching { trackAccess.currentVideoTrack }.getOrNull()
        val sar = track?.let { if (it.sarNum > 0 && it.sarDen > 0) "${it.sarNum}:${it.sarDen}" else "?" } ?: "?"
        Timber.i(
            "HDR-AUDIT VLC %s | videoTrack=%d size=%dx%d sar=%s time=%d length=%d playing=%s sourceHdr=%s nativeHdrRequested=%s output=%s pendingSeek=%s",
            reason, trackAccess.videoTrack, track?.width ?: 0, track?.height ?: 0, sar,
            player.time, player.length, player.isPlaying, sourceHdrStandard, nativeHdrPreferred, videoOutputDescription, pendingRestorePositionMs
        )
    }

    fun videoAspectRatio(): Float? = runCatching {
        val track = trackAccess.currentVideoTrack ?: return@runCatching null
        val sampleAspect = if (track.sarNum > 0 && track.sarDen > 0) {
            track.sarNum.toFloat() / track.sarDen
        } else 1f
        (track.width.toFloat() * sampleAspect / track.height)
            .takeIf { it.isFinite() && it > 0f }
    }.getOrNull()

    fun sourceDimensions(): Pair<Int, Int>? = runCatching {
        val track = trackAccess.currentVideoTrack ?: return@runCatching null
        if (track.width > 0 && track.height > 0) track.width to track.height else null
    }.getOrNull()

    fun preferredAudioTrack(language: String?): Pair<Int, String>? {
        if (released) return null
        val metadata = trackAccess.mediaTracks(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio).associateBy { it.id }
        return com.ambient.tv.ui.player.choosePreferredAudioTrack(
            audioTracks().filter { it.first >= 0 }, language, audioSession.settings.tracks,
        ) {
            val track = metadata[it.first]
            com.ambient.tv.ui.player.AudioTrackMetadata(track?.language, it.second, track?.channels ?: 0)
        }
    }

    override fun chapters(): List<com.ambient.tv.domain.model.VideoChapter> =
        if (released) emptyList() else player.getChapters(player.title.coerceAtLeast(0))?.map { chapter ->
            com.ambient.tv.domain.model.VideoChapter(chapter.timeOffset, chapter.name,
                endMs = (chapter.timeOffset + chapter.duration).takeIf { chapter.duration > 0 })
        }.orEmpty()

    fun audioTracks(): List<Pair<Int, String>> =
        if (released) emptyList() else {
            val metadata = trackAccess.mediaTracks(0).associateBy { it.id }
            trackAccess.getAudioTracks().map { track ->
                val info = metadata[track.id] ?: track
                track.id to com.ambient.tv.ui.player.audioTrackLabel(track.name ?: info.name, info.language, info.channels, info.codec,
                    context.getString(com.ambient.tv.R.string.vlc_audio_track_fallback, track.id))
            }
        }

    fun subtitleTracks(): List<Pair<Int, String>> =
        if (released) emptyList() else trackAccess.getSpuTracks().map { it.id to (it.name ?: context.getString(com.ambient.tv.R.string.vlc_subtitle_track_fallback, it.id)) }

    fun videoTracks(): List<Pair<Int, String>> = runCatching {
        // TrackDescription exposes public fields, not getId()/getName() methods.
        trackAccess.getVideoTracks().map { it.id to (it.name ?: context.getString(R.string.vlc_video_track_fallback, it.id)) }
    }.getOrDefault(emptyList())

    fun currentVideoTrackId(): Int = if (released) -1 else trackAccess.videoTrack
    fun selectVideoTrack(id: Int) {
        if (released) return
        userVideoDisabled = id < 0
        trackAccess.setVideoTrack(id)
    }

    fun currentAudioTrackId(): Int = if (released) -1 else trackAccess.audioTrack
    private val externalSubtitleRequests = mutableSetOf<String>()
    private var pendingExternalSubtitle: com.ambient.tv.domain.model.MediaTrack? = null
    private var manualExternalSubtitleUrl: String? = null
    private var subtitleIdsBeforeAdd = emptySet<Int>()
    private var subtitleAddStarted = 0L
    private val externalSubtitleSources = mutableMapOf<Int, com.ambient.tv.domain.model.MediaTrack>()

    fun externalSubtitleSource(id: Int): com.ambient.tv.domain.model.MediaTrack? {
        collectExternalSubtitle()
        return externalSubtitleSources[id]
    }
    private fun collectExternalSubtitle() {
        val pending = pendingExternalSubtitle ?: return
        val added = subtitleTracks().firstOrNull { it.first >= 0 && it.first !in subtitleIdsBeforeAdd }
        if (added != null) {
            externalSubtitleSources[added.first] = pending; pendingExternalSubtitle = null
            if (manualExternalSubtitleUrl == pending.externalUrl) { trackAccess.setSpuTrack(added.first); manualExternalSubtitleUrl = null }
        }
        else if (android.os.SystemClock.elapsedRealtime() - subtitleAddStarted > 10_000) pendingExternalSubtitle = null
    }
    fun ensureExternalSubtitle(track: com.ambient.tv.domain.model.MediaTrack) {
        collectExternalSubtitle()
        val url = track.externalUrl ?: return
        if (released || player.length <= 0 || pendingExternalSubtitle != null || !externalSubtitleRequests.add(url)) return
        subtitleIdsBeforeAdd = subtitleTracks().map { it.first }.toSet()
        subtitleAddStarted = android.os.SystemClock.elapsedRealtime()
        pendingExternalSubtitle = track
        if (!player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, Uri.parse(url), false)) pendingExternalSubtitle = null
    }

    fun selectExternalSubtitle(track: com.ambient.tv.domain.model.MediaTrack) {
        val url = track.externalUrl ?: return
        collectExternalSubtitle()
        externalSubtitleSources.entries.firstOrNull { it.value.externalUrl == url }?.let { selectSubtitleTrack(it.key); return }
        require(!released && player.length > 0) { "Subtitle renderer is not ready" }
        require(pendingExternalSubtitle == null || pendingExternalSubtitle?.externalUrl == url) { "A subtitle load is pending" }
        if (pendingExternalSubtitle == null) externalSubtitleRequests.remove(url) // An explicit retry, not an automatic fetch loop.
        manualExternalSubtitleUrl = url
        ensureExternalSubtitle(track)
        check(pendingExternalSubtitle != null) { "Subtitle could not be loaded" }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val poll = object : Runnable {
            override fun run() {
                if (released || manualExternalSubtitleUrl != url) return
                collectExternalSubtitle()
                if (pendingExternalSubtitle != null && manualExternalSubtitleUrl == url) handler.postDelayed(this, 100)
                else manualExternalSubtitleUrl = null
            }
        }
        handler.post(poll)
    }

    fun subtitleTrackLanguages(): Map<Int, String?> = mediaTrackLanguages(org.videolan.libvlc.interfaces.IMedia.Track.Type.Text)
    fun audioTrackLanguage(): String? = mediaTrackLanguages(org.videolan.libvlc.interfaces.IMedia.Track.Type.Audio)[currentAudioTrackId()]
        ?: com.ambient.tv.ui.player.PlaybackLanguagePolicy.inferLanguageFromLabel(audioTracks().firstOrNull { it.first == currentAudioTrackId() }?.second)

    private fun mediaTrackLanguages(type: Int): Map<Int, String?> {
        if (released) return emptyMap()
        return trackAccess.mediaTracks(type).associate { it.id to it.language }
    }

    fun currentSubtitleTrackId(): Int = if (released) -1 else trackAccess.spuTrack
    fun currentScalingFilter(): String = imageOptions.scaler
    /** Filters, deinterlace, and SVP actually passed to the current LibVLC instance. */
    internal fun appliedVideoProcessing(): com.ambient.tv.ui.player.AppliedVideoProcessing =
        com.ambient.tv.ui.player.vlcInstanceAppliedProcessing(
            deinterlaceMode = configuredDeinterlaceMode,
            deinterlaceFilter = configuredDeinterlaceFilter,
            svpMode = configuredSvpMode,
            svpTargetFps = configuredSvpRate,
            upscaler = appliedUpscaler(),
        )

    private fun appliedUpscaler(): String {
        val image = effectiveImage()
        if (vlcSurfaceBypassesSwscale(usesAndroidSurfaceOutput, image.softwareProcessing)) return "Android Surface (hardware)"
        val label = com.ambient.tv.data.local.VideoImageCatalog.vlcScalers[image.scaler] ?: image.scaler
        return if (vulkanOutput) "$label · libplacebo" else "swscale · $label"
    }

    fun currentDeinterlace(): String? = when (deinterlaceMode) {
        "off" -> null
        "auto" -> "Auto${deinterlaceFilter.takeIf { it != "auto" }?.let { " · $it" }.orEmpty()}"
        else -> deinterlaceFilter.takeIf { it.isNotBlank() } ?: deinterlaceMode
    }
    fun selectAudioTrack(id: Int) {
        if (released) return
        trackAccess.setAudioTrack(id)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!released) refreshSelectedAudioCodec(id)
        }, 100L)
    }
    fun selectSubtitleTrack(id: Int?) {
        if (released) return
        manualExternalSubtitleUrl = null
        trackAccess.setSpuTrack(id ?: -1)
    }

    /** Actual playback rate, so the panel follows renderer restores and bitstream resets. */
    fun currentSpeed(): Float = if (released) 1f else runCatching { player.rate }.getOrDefault(1f)

    fun setSpeed(speed: Float) {
        if (!released) player.setRate(if (bitstreamPermitted) 1f else speed)
    }

    fun cycleAudioTrack(): String? {
        if (released) return null
        val tracks = trackAccess.getAudioTracks()
        if (tracks.isEmpty()) return null
        val current = trackAccess.audioTrack
        val nextIndex = (tracks.indexOfFirst { it.id == current }.let { if (it < 0) 0 else it + 1 }) % tracks.size
        val track = tracks[nextIndex]
        selectAudioTrack(track.id)
        return track.name
    }

    fun cycleSubtitleTrack(): String? {
        if (released) return null
        BackgroundPlaybackSession.manualSubtitleSelection()
        val tracks = trackAccess.getSpuTracks()
        if (tracks.isEmpty()) {
            trackAccess.setSpuTrack(-1)
            return null
        }
        val current = trackAccess.spuTrack
        if (current == -1) {
            val track = tracks.first()
            trackAccess.setSpuTrack(track.id)
            return track.name
        }
        val idx = tracks.indexOfFirst { it.id == current }
        if (idx < 0 || idx == tracks.lastIndex) {
            trackAccess.setSpuTrack(-1)
            return null
        }
        val track = tracks[idx + 1]
        trackAccess.setSpuTrack(track.id)
        return track.name
    }

    /** Same live SVP state as MPV. libVLC reports no bypass, so the native frame counter decides. */
    fun svpState(): SvpState? {
        val mode = imageOptions.svpMode
        if (mode == "off") return null
        val track = runCatching { trackAccess.currentVideoTrack }.getOrNull()
        val source = track?.takeIf { it.frameRateDen > 0 && it.frameRateNum > 0 }?.let { it.frameRateNum.toDouble() / it.frameRateDen }
            ?: measuredSourceFps
        val size = track?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width to it.height } ?: measuredSourceSize
        val options = imageOptions.svp.normalized()
        val unavailable = when {
            !com.ambient.tv.BuildConfig.VLC_SVP -> SvpInactiveReason.ENGINE
            !performanceProfile(context).allowSvpInterpolation -> SvpInactiveReason.PERFORMANCE_MODE
            !SvpNative.isEnabledAndAvailable() -> SvpInactiveReason.LIBRARY
            nativeHdrPreferred || sourceHdrStandard != null -> SvpInactiveReason.HDR
            effectiveVlcImageOptions(imageOptions, reducedVideoEffects(context), vlcSvpAllowed(context)).svpMode == "off" ->
                SvpInactiveReason.PERFORMANCE_MODE
            else -> null
        }
        val target = configuredSvpRate ?: svpOutputFps(svpDisplayFps(context), options)
        // VLC probes an unknown MediaCodec rate itself; only known facts can rule SVP out here.
        val decision = if (source == null) {
            if (svpWithinResolution(size?.first, size?.second, options)) target?.let { SvpDecision.Run(0.0, it) }
                ?: SvpDecision.Skip(SvpInactiveReason.DISPLAY_RATE_UNKNOWN)
            else SvpDecision.Skip(SvpInactiveReason.RESOLUTION)
        } else svpDecision(source, svpDisplayFps(context), options, size?.first, size?.second)
        val configured = configuredSvpMode != "off"
        return svpState(
            mode = mode,
            custom = options.isCustom,
            unavailable = unavailable,
            overloaded = false,
            decision = decision,
            inChain = configured,
            runtimeReason = if (configured) vlcSvpRuntimeReason() else null,
        )
    }

    fun svpStatus(): String? = formatSvpStatus(svpState())

    private fun vlcSvpRuntimeReason(): SvpInactiveReason? {
        val frames = SvpNative.interpolatedFramesOrNull() ?: return SvpInactiveReason.PASSTHROUGH
        val now = android.os.SystemClock.elapsedRealtime()
        val playing = !released && runCatching { player.isPlaying }.getOrDefault(false)
        if (frames != svpFramesSeen || !playing || svpFramesSeen < 0) {
            svpFramesSeen = frames
            svpFramesChangedAt = now
            return null
        }
        return if (now - svpFramesChangedAt > VLC_SVP_STALL_MS) SvpInactiveReason.PASSTHROUGH else null
    }

    fun runtimeStats(includeCounters: Boolean = true): VlcRuntimeStats? = runCatching {
        // Compose polling can outlive session release until its disposal is applied.
        // LibVLC's Media.getStats JNI dereferences the native handle even after release.
        if (released) return@runCatching null
        fun member(instance: Any, name: String): Any? = ReflectiveMembers.value(instance, name)

        var result = VlcRuntimeStats()
        var hasAnyValue = false

        val currentVideoTrack = trackAccess.currentVideoTrack
        if (currentVideoTrack != null) {
            val width = (member(currentVideoTrack, "width") as? Number)?.toInt()?.takeIf { it > 0 }
            val height = (member(currentVideoTrack, "height") as? Number)?.toInt()?.takeIf { it > 0 }
            val frameRateNum = (member(currentVideoTrack, "frameRateNum") as? Number)?.toLong()
            val frameRateDen = (member(currentVideoTrack, "frameRateDen") as? Number)?.toLong()
            val fps = if (frameRateNum != null && frameRateDen != null && frameRateNum > 0L && frameRateDen > 0L) {
                frameRateNum.toDouble() / frameRateDen.toDouble()
            } else null
            val bitrate = (member(currentVideoTrack, "bitrate") as? Number)?.toLong()?.takeIf { it > 0L }
            val codec = vlcTrackCodec(currentVideoTrack)
            result = result.copy(
                videoWidth = width,
                videoHeight = height,
                videoFps = fps,
                videoBitrateBitsPerSecond = bitrate,
                videoCodec = codec,
            )
            hasAnyValue = width != null || height != null || fps != null || bitrate != null || codec != null
        }

        trackAccess.currentAudioTrack?.let { audio ->
            result = result.copy(
                audioCodec = vlcTrackCodec(audio),
                audioChannels = audio.channels.takeIf { it > 0 },
                audioSampleRate = audio.rate.takeIf { it > 0 },
                audioBitrateBitsPerSecond = audio.bitrate.toLong().takeIf { it > 0 },
            )
            hasAnyValue = hasAnyValue || result.audioCodec != null || result.audioChannels != null || result.audioSampleRate != null
        }
        val media = if (includeCounters) ReflectiveMembers.call(player, "getMedia") else null
        if (media != null) {
            try {
                val statsObject = ReflectiveMembers.call(media, "getStats")
                if (statsObject != null) {
                    fun number(name: String): Number? = member(statsObject, name) as? Number
                    result = result.copy(
                        readBytes = number("readBytes")?.toLong(),
                        demuxReadBytes = number("demuxReadBytes")?.toLong(),
                        demuxCorrupted = number("demuxCorrupted")?.toInt(),
                        demuxDiscontinuity = number("demuxDiscontinuity")?.toInt(),
                        decodedVideo = number("decodedVideo")?.toInt(),
                        decodedAudio = number("decodedAudio")?.toInt(),
                        displayedPictures = number("displayedPictures")?.toInt(),
                        lostPictures = number("lostPictures")?.toInt(),
                        playedAudioBuffers = number("playedAbuffers")?.toInt(),
                        lostAudioBuffers = number("lostAbuffers")?.toInt(),
                    )
                    hasAnyValue = hasAnyValue || listOf(
                        result.readBytes, result.demuxReadBytes, result.demuxCorrupted,
                        result.demuxDiscontinuity, result.decodedVideo, result.decodedAudio,
                        result.displayedPictures, result.lostPictures, result.playedAudioBuffers,
                        result.lostAudioBuffers,
                    ).any { it != null }
                }
            } finally {
                ReflectiveMembers.call(media, "release")
            }
        }

        result.takeIf { hasAnyValue }
    }.getOrNull()

    override fun release() {
        if (released) return
        com.ambient.tv.diagnostics.CrashBreadcrumbs.add("vlc", "release vulkan=$vulkanOutput")
        pendingSeekTask?.let(pendingSeekHandler::removeCallbacks)
        pendingSeekTask = null
        renderCheckTask?.let(renderCheckHandler::removeCallbacks)
        renderCheckTask = null
        audioSession.release()
        detachVideoOutput()
        hdrOutput.release()
        nightProcessing.release()
        released = true
        player.setEventListener(null)
        player.vlcVout.removeCallback(surfaceCallback)
        // Drop the upstream connection first: a renderer hand-off must not leave the old live
        // subscription open while the next engine connects.
        authProxy?.close()
        val retiringPlayer = player
        val retiringLibVlc = libVlc
        attachedView = null
        // VLC 4's Java stop waits for native Stopped. A blocked network/decoder
        // must not freeze Back or a renderer handoff on the application thread.
        // Surface detachment and listener removal have already happened above.
        val audioRelease = nativeAudioReleases.beginRelease()
        kotlin.concurrent.thread(name = "vlc-stop", isDaemon = true) {
            val started = android.os.SystemClock.elapsedRealtime()
            runCatching { retiringPlayer.stop() }
                .onFailure { Timber.w(it, "VLC native stop failed") }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { retiringPlayer.release() }
                    .onSuccess { audioRelease.complete(Unit) }
                    .onFailure { audioRelease.completeExceptionally(it) }
                libVlcRelease.releaseWhenIdle(retiringLibVlc)
                com.ambient.tv.diagnostics.CrashBreadcrumbs.add("vlc", "native release done")
                Timber.i("VLC native release completed in %d ms", android.os.SystemClock.elapsedRealtime() - started)
            }
        }
    }
}


data class VlcRuntimeStats(
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val videoFps: Double? = null,
    val videoBitrateBitsPerSecond: Long? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val audioChannels: Int? = null,
    val audioSampleRate: Int? = null,
    val audioBitrateBitsPerSecond: Long? = null,
    val readBytes: Long? = null,
    val demuxReadBytes: Long? = null,
    val demuxCorrupted: Int? = null,
    val demuxDiscontinuity: Int? = null,
    val decodedVideo: Int? = null,
    val decodedAudio: Int? = null,
    val displayedPictures: Int? = null,
    val lostPictures: Int? = null,
    val playedAudioBuffers: Int? = null,
    val lostAudioBuffers: Int? = null,
)

data class VlcPlaybackSnapshot(
    val isPlaying: Boolean = false,
    val wantsToPlay: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val hasVideo: Boolean = true,
    val hasAudio: Boolean = false,
    val runtimeStats: VlcRuntimeStats? = null,
    val videoOutput: String? = null,
    val accelerationFallback: String? = null,
    val processingSampled: Boolean = false,
    val appliedVideoFilters: String? = null,
    val appliedDeinterlacer: String? = null,
    val appliedUpscaler: String? = null,
    val appliedFrameInterpolation: String? = null,
    val svpStatus: String? = null,
    /** Same sample as [svpStatus]; drives the OSD SVP chip. */
    val svpState: SvpState? = null,
)

// Unlike MPV (gated behind MpvBackendAvailability before this is ever called), VLC has no
// upfront availability probe - the AUTO backend can fall back into VLC after any Media3 error
// with no guarantee libvlc's native library actually loads on this device/ABI. Constructing
// LibVLC/MediaPlayer can throw (e.g. UnsatisfiedLinkError), and since this used to run directly
// inside `remember`, that exception was uncaught and crashed the whole activity. Returning null
// on failure lets the caller report a normal playback error instead.
@Composable
fun rememberVlcPlaybackController(
    context: Context,
    url: String,
    startPositionMs: Long,
    headers: Map<String, String>,
    initialDeinterlaceMode: String = "off",
    initialDeinterlaceFilter: String = "auto",
    initialScalingFilter: String = "bilinear",
    initialAudioVisualization: Boolean = false,
    initialAudioPassthrough: Boolean = false,
    networkCache: Boolean = false,
    audioSlaveUrl: String? = null,
): VlcPlaybackController? {
    var retainedController by remember { mutableStateOf<VlcPlaybackController?>(null) }
    if (com.ambient.tv.ui.player.LocalPlayerChangingItem.current && retainedController != null) return retainedController
    val metadata = LocalPlaybackMetadata.current
    val preferences = videoImagePreferences(context)
    val title = remember(metadata?.route, metadata?.source, metadata?.mediaId) { com.ambient.tv.ui.player.TitleVideoSettingsStore.load(context, metadata) }
    val resolution = remember(metadata?.route, metadata?.sourceWidth, metadata?.sourceHeight) { com.ambient.tv.ui.player.ResolutionVideoProfileStore.load(context, metadata) }
    val videoOutputMode = BackgroundPlaybackSession.videoSettings(metadata,
        com.ambient.tv.ui.player.PlayerVideoSettings.from(preferences).withResolution(resolution).withTitle(title)).vlcOutput
    val nativeHdr = nativeHdrSurfacePreferred(metadata?.handheldHdrStandard, HdrSupport.displayStandards(context), metadata?.hdrBaseLayer)
    val key = listOf(url, startPositionMs, headers, initialAudioPassthrough, networkCache, videoOutputMode, audioSlaveUrl, nativeHdr, metadata?.handheldHdrStandard)
    val controller = remember(metadata?.route, key) {
        runCatching {
            val previous = (BackgroundPlaybackSession.activeHandle() as? VlcPlaybackController)
                ?.takeIf { com.ambient.tv.ui.player.sameVideoSettingsPlayback(metadata, BackgroundPlaybackSession.snapshot.value.metadata) }
            val imageOptions = previous?.imageOptions
                ?: (title?.vlc ?: resolution?.vlc ?: preferences.vlcImageOptions).withGlobalSvp(preferences)
            val saveDefaults = previous?.saveImageDefaults ?: false
            val restoreOutputState = previous?.captureOutputRestartState()
            val deinterlaceMode = previous?.deinterlaceMode ?: title?.vlcDeinterlaceMode ?: resolution?.vlcDeinterlaceMode ?: initialDeinterlaceMode
            val deinterlaceFilter = previous?.deinterlaceFilter ?: title?.vlcDeinterlaceFilter ?: resolution?.vlcDeinterlaceFilter ?: initialDeinterlaceFilter
            // Same-title output switches must not reopen at the ViewModel's original start.
            val resumeMs = previous?.status()?.positionMs?.takeIf { it > 0L } ?: startPositionMs
            val create = { VlcPlaybackController(context.applicationContext, url, resumeMs, headers,
                deinterlaceMode, deinterlaceFilter, initialScalingFilter, initialAudioVisualization, initialAudioPassthrough, imageOptions, networkCache, videoOutputMode, audioSlaveUrl, nativeHdr,
                liveStream = metadata?.isLive == true, sourceHdrStandard = metadata?.handheldHdrStandard, audioOnly = metadata?.hasVideo == false, preferOriginalAudio = metadata?.source == "youtube",
                reopeningTimeshift = previous?.url == url).also {
                it.saveImageDefaults = saveDefaults
                if (previous == null) title?.vlcOverloadContinueSignature?.let(it::restoreOverloadContinue)
                else previous.overloadContinueSignature?.let(it::restoreOverloadContinue)
                if (previous == null) com.ambient.tv.ui.player.restoreSavedPlaybackAudio(it.audioSession, title, resolution)
                else it.audioSession.restoreFromSession(previous.audioSession)
                // Same playback rebuilt (output switch): keep this session's delay; otherwise the stored one.
                com.ambient.tv.ui.player.TitleVideoSettingsStore.attachAudio(it.audioSession, metadata,
                    if (previous == null) title?.audioDelayMs else previous.audioSession.titleDelayMs)
                (if (previous == null) title?.subtitleDelayMs else previous.subtitleDelayMs)?.takeIf { d -> d != 0 }?.let(it::setSubtitleDelay)
                com.ambient.tv.ui.player.AutomaticVideoProfiles.transfer(previous, it)
                restoreOutputState?.invoke(it)
            } }
            if (metadata == null) create() else
                BackgroundPlaybackSession.acquire(context, metadata, "vlc", key, create)
        }.onFailure { Timber.e(it, "VLC backend unavailable") }.getOrNull()
    }
    LaunchedEffect(controller, title, resolution) {
        controller ?: return@LaunchedEffect
        com.ambient.tv.ui.player.restoreSavedPlaybackAudio(controller.audioSession, title, resolution)
        val automatic = com.ambient.tv.ui.player.AutomaticVideoProfiles.state(controller)
        (title?.vlc ?: resolution?.vlc ?: preferences.vlcImageOptions).withGlobalSvp(preferences).let { image ->
            automatic.image.apply(controller.imageOptions, image) {
                controller.updateImageOptions(it)
                controller.imageOptions
            }
        }
        val mode = title?.vlcDeinterlaceMode ?: resolution?.vlcDeinterlaceMode
            ?: if (metadata?.isLive == true) preferences.tvheadendDeinterlaceModeKey else "off"
        val filter = title?.vlcDeinterlaceFilter ?: resolution?.vlcDeinterlaceFilter
            ?: if (metadata?.isLive == true) preferences.tvheadendDeinterlaceFilterKey else "auto"
        automatic.deinterlace.apply(controller.deinterlaceMode to controller.deinterlaceFilter, mode to filter) {
            controller.setDeinterlace(it.first, it.second)
            controller.deinterlaceMode to controller.deinterlaceFilter
        }
    }
    DisposableEffect(controller) {
        onDispose { if (metadata == null) controller?.release() }
    }
    androidx.compose.runtime.SideEffect { retainedController = controller }
    return controller
}

@Composable
fun VlcVideoSurface(
    controller: VlcPlaybackController,
    modifier: Modifier = Modifier,
    sourceAspectRatioHint: Float? = null,
) {
    val presentation by controller.videoPresentation.collectAsState()
    var sourceAspectRatio by remember(controller, sourceAspectRatioHint) { mutableStateOf(sourceAspectRatioHint) }
    var surfaceView by remember(controller) { mutableStateOf<SurfaceView?>(null) }
    val appVisible by com.ambient.tv.ui.player.rememberAppVisible()
    LaunchedEffect(controller) {
        var pendingAspect: Float? = null
        var pendingCount = 0
        while (true) {
            val candidate = controller.videoAspectRatio()?.takeIf { it.isFinite() && it in 0.25f..4f }
            if (candidate != null) {
                val current = sourceAspectRatio
                if (current == null) {
                    sourceAspectRatio = candidate
                } else if (kotlin.math.abs(candidate - current) / current > 0.01f) {
                    // libVLC/MediaCodec can report transitional track geometry around flushes and
                    // format changes. Require a changed aspect to remain stable before relayout.
                    val pending = pendingAspect
                    if (pending != null && kotlin.math.abs(candidate - pending) / candidate < 0.01f) {
                        pendingCount++
                    } else {
                        pendingAspect = candidate
                        pendingCount = 1
                    }
                    if (pendingCount >= 3) {
                        sourceAspectRatio = candidate
                        pendingAspect = null
                        pendingCount = 0
                    }
                } else {
                    pendingAspect = null
                    pendingCount = 0
                }
            }
            delay(com.ambient.tv.ui.player.playbackPollInterval(250, appVisible))
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSurfaceView by rememberUpdatedState(surfaceView)
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            val view = currentSurfaceView
            if (view != null) when (event) {
                Lifecycle.Event.ON_START -> controller.attach(view)
                Lifecycle.Event.ON_STOP -> controller.detach(view)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            currentSurfaceView?.let(controller::detach)
        }
    }
    val hdrHeadroom = com.ambient.tv.ui.player.videoSurfaceHdrHeadroom()
    key(controller) { AndroidView(
        factory = { context ->
            VlcSurfaceHost(context).also {
                it.renderAtDisplayResolution = controller.gpuRenderAtPanel
                it.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // Keep Android View focus off the video surface so it never competes with the
                // Compose Box that owns onPreviewKeyEvent for the on-screen buttons.
                it.isFocusable = false
                it.isFocusableInTouchMode = false
                surfaceView = it.videoSurface
                controller.attach(it.videoSurface)
            }
        },
        onRelease = { controller.detach(it.videoSurface) },
        update = {
            it.renderAtDisplayResolution = controller.gpuRenderAtPanel
            it.presentation = presentation
            it.sourceAspectRatio = sourceAspectRatio
            it.ambientBackdrop = com.ambient.tv.ui.player.AmbientVideoBackdrop.enabled
            it.videoSurface.applyUiHdrHeadroom(hdrHeadroom)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.attach(it.videoSurface)
        },
        modifier = modifier.ignoreVideoSurfaceFocus(),
    ) }
}

private class VlcSurfaceHost(context: Context) : VideoSurfaceLayout(context)

@Composable
fun rememberVlcPlaybackSnapshot(
    controller: VlcPlaybackController,
    nerdStatsEnabled: Boolean = false,
): VlcPlaybackSnapshot {
    var snapshot by remember(controller) { mutableStateOf(
        VlcPlaybackSnapshot(isPlaying = controller.isPlayingNow(), wantsToPlay = controller.wantsToPlay()),
    ) }
    val appVisible by com.ambient.tv.ui.player.rememberAppVisible()
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    LaunchedEffect(controller, nerdStatsEnabled, changingItem) {
        if (changingItem) return@LaunchedEffect
        var lastNerdPollMs = 0L
        val startedAtMs = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val status = controller.status()
            val now = android.os.SystemClock.elapsedRealtime()
            // Right after a renderer switch the stream is still being parsed: look again soon.
            val warmup = vlcStreamInfoWarmup(now - startedAtMs, snapshot.runtimeStats != null)
            // Stream properties also feed codec badges and the information panel.
            val pollStreamInfo = lastNerdPollMs == 0L || now - lastNerdPollMs >= vlcStreamInfoIntervalMs(warmup)
            val pollNerdStats = nerdStatsEnabled && pollStreamInfo
            val applied = if (pollNerdStats) controller.appliedVideoProcessing() else null
            val svp = if (pollStreamInfo) controller.svpState() else snapshot.svpState
            snapshot = snapshot.copy(
                isPlaying = status.playing,
                wantsToPlay = controller.wantsToPlay(),
                positionMs = status.positionMs,
                durationMs = status.durationMs,
                // Empty track lists while VLC opens the stream do not mean audio-only.
                hasVideo = runCatching {
                    com.ambient.tv.ui.player.playbackPanelHasVideo(controller.trackAccess.getVideoTracks().size,
                        controller.trackAccess.getAudioTracks().size, controller.audioOnly)
                }.getOrDefault(snapshot.hasVideo),
                hasAudio = runCatching { !controller.trackAccess.getAudioTracks().isNullOrEmpty() }.getOrDefault(snapshot.hasAudio),
                runtimeStats = if (pollStreamInfo) controller.runtimeStats(includeCounters = nerdStatsEnabled) else snapshot.runtimeStats,
                svpState = svp,
                svpStatus = if (pollStreamInfo) formatSvpStatus(svp) else snapshot.svpStatus,
                videoOutput = controller.videoOutputDescription,
                accelerationFallback = controller.accelerationFallback,
                processingSampled = if (pollNerdStats) true else snapshot.processingSampled,
                appliedVideoFilters = if (pollNerdStats) applied?.videoFilters else snapshot.appliedVideoFilters,
                appliedDeinterlacer = if (pollNerdStats) applied?.deinterlacer else snapshot.appliedDeinterlacer,
                appliedUpscaler = if (pollNerdStats) applied?.upscaler else snapshot.appliedUpscaler,
                appliedFrameInterpolation = if (pollNerdStats) applied?.frameInterpolation else snapshot.appliedFrameInterpolation,
            )
            controller.checkVulkanOutput()
            if (pollStreamInfo) lastNerdPollMs = now
            delay(com.ambient.tv.ui.player.playbackPollInterval(if (warmup) VLC_WARMUP_POLL_MS else 500L, appVisible))
        }
    }
    return snapshot
}

/** Faster stream-info polling for this long after the engine appeared, until VLC reports stats. */
internal const val VLC_STREAM_INFO_WARMUP_MS = 3_000L
internal const val VLC_WARMUP_POLL_MS = 250L

internal fun vlcStreamInfoWarmup(sinceStartMs: Long, statsKnown: Boolean): Boolean =
    sinceStartMs < VLC_STREAM_INFO_WARMUP_MS || !statsKnown && sinceStartMs < 2 * VLC_STREAM_INFO_WARMUP_MS

internal fun vlcStreamInfoIntervalMs(warmup: Boolean): Long = if (warmup) VLC_WARMUP_POLL_MS else 1_000L

/** SVP for VLC needs both the shared engine gate and a libVLC build that contains the filter. */
internal fun vlcSvpAllowed(context: Context): Boolean = com.ambient.tv.BuildConfig.VLC_SVP && svpAllowed(context)

/** Without a new synthesized frame for this long while playing, VLC reports passthrough. */
private const val VLC_SVP_STALL_MS = 4_000L

/**
 * VLC: known facts that rule SVP out (film-only limit, resolution limit, source >= target).
 * Unknown rates and sizes keep SVP; the native filter probes the rate itself.
 */
internal fun vlcSvpApplies(sourceFps: Double?, targetFps: Double?, options: com.ambient.tv.data.local.SvpOptions, width: Int?, height: Int?): Boolean {
    val o = options.normalized()
    if (!svpWithinResolution(width, height, o)) return false
    if (o.filmOnly && sourceFps != null && sourceFps > 30.5) return false
    return vlcSvpUseful(sourceFps, targetFps)
}

/** Interpolation only helps when the target rate is above the source; unknown rates keep SVP. */
internal fun vlcSvpUseful(sourceFps: Double?, targetFps: Number?): Boolean {
    val target = targetFps?.toDouble() ?: return true
    val source = sourceFps ?: return true
    return target > source * 1.01
}
