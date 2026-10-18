package com.ambient.tv.ui.player.backend

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import com.ambient.tv.ui.player.withTitle
import com.ambient.tv.ui.player.withResolution
import com.ambient.tv.ui.player.videoImagePreferences
import com.ambient.tv.ui.player.withGlobalSvp
import android.content.Context
import android.view.SurfaceHolder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import timber.log.Timber
import com.ambient.tv.ui.player.BackgroundPlaybackSession
import com.ambient.tv.ui.player.LocalPlaybackMetadata
import com.ambient.tv.ui.player.HdrSupport
import com.ambient.tv.ui.player.nativeHdrSurfacePreferred
import com.ambient.tv.ui.player.VideoSurfaceLayout
import com.ambient.tv.ui.player.applyUiHdrHeadroom
import com.ambient.tv.ui.player.ignoreVideoSurfaceFocus
import kotlinx.coroutines.flow.first

/** Below this demuxed buffer a stalled clock is network starvation, not a decoder stall. */
internal fun mpvStarvedForData(bufferedSeconds: Double): Boolean = bufferedSeconds < 1.0

data class MpvTrack(
    val id: Int,
    val type: String,
    val title: String?,
    val language: String?,
    val selected: Boolean,
    val forced: Boolean = false,
    val channels: Int = 0,
    val codec: String? = null,
) {
    val label: String get() = if (type == "audio") com.ambient.tv.ui.player.audioTrackLabel(title, language, channels, codec, "$type $id") else listOfNotNull(title?.takeIf { it.isNotBlank() }, language?.takeIf { it.isNotBlank() }).joinToString(" · ").ifBlank { "$type $id" }
}


data class MpvPlaybackSnapshot(
    val confirmedAudioOnly: Boolean = false,
    val audioOutputFormat: String? = null,
    val audioOutputDriver: String? = null,
    val isPlaying: Boolean = false,
    val wantsToPlay: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val videoSize: String? = null,
    val fps: Double = 0.0,
    val containerFps: Double? = null,
    val decoder: String? = null,
    val videoBitrateBitsPerSecond: Long? = null,
    val audioBitrateBitsPerSecond: Long? = null,
    val cacheDurationSeconds: Double? = null,
    val cacheSpeedBytesPerSecond: Long? = null,
    val avSyncSeconds: Double? = null,
    val frameDropCount: Long? = null,
    val decoderFrameDropCount: Long? = null,
    val displayFps: Double? = null,
    val pixelFormat: String? = null,
    val outputPixelFormat: String? = null,
    val activeVideoFilters: String? = null,
    val activeScaler: String? = null,
    val activeUpscaling: Boolean? = null,
    val activeChromaScaler: String? = null,
    val activeDownscaler: String? = null,
    val activeShaderPreset: String? = null,
    /** User/session scaler key (may be a GLSL preset while [activeScaler] is the builtin finish). */
    val configuredScaler: String? = null,
    val videoOutput: String? = null,
    val accelerationFallback: String? = null,
    /** SHIELD interlaced zero-copy decoder note (shaders/SVP inactive). */
    val zeroCopyStatus: String? = null,
    /** True after a Stats-for-Nerds poll has read the live filter/scaler properties. */
    val processingSampled: Boolean = false,
    val activeDeinterlace: String? = null,
    /** Live SVP state (source → target fps, preset, bypass); null with SVP off. */
    val svpStatus: String? = null,
    /** False on direct output, where mpv's `scale` property is not the path in effect. */
    val gpuScalerExposed: Boolean = false,
)

@Composable
fun rememberMpvPlaybackController(
    context: Context,
    url: String,
    startPositionMs: Long,
    headers: Map<String, String>,
    audioPassthroughEnabled: Boolean = false,
    videoOutputMode: String = "direct",
    networkCache: Boolean = false,
    knownAudioCodec: String? = null,
): MpvPlaybackController? {
    var retainedController by remember { mutableStateOf<MpvPlaybackController?>(null) }
    if (com.ambient.tv.ui.player.LocalPlayerChangingItem.current && retainedController != null) return retainedController
    val metadata = LocalPlaybackMetadata.current
    val preferences = videoImagePreferences(context)
    val title = remember(metadata?.route, metadata?.source, metadata?.mediaId) { com.ambient.tv.ui.player.TitleVideoSettingsStore.load(context, metadata) }
    val resolution = remember(metadata?.route, metadata?.sourceWidth, metadata?.sourceHeight) { com.ambient.tv.ui.player.ResolutionVideoProfileStore.load(context, metadata) }
    val settings = BackgroundPlaybackSession.videoSettings(metadata,
        com.ambient.tv.ui.player.PlayerVideoSettings.from(preferences).copy(mpvOutput = videoOutputMode)
            .withResolution(resolution).withTitle(title))
    val decoderMode = effectiveMpvDecoder(settings.mpvDecoder, com.ambient.tv.ui.player.RuntimeCodecSupport.hasHardwareVideoDecoder)
    val outputMode = settings.mpvOutput
    val declaredHdr = metadata?.handheldHdrStandard
    val nativeHdr = nativeHdrSurfacePreferred(declaredHdr, HdrSupport.displayStandards(context))
    val key = listOf(url, startPositionMs, headers, audioPassthroughEnabled, outputMode, decoderMode, networkCache, nativeHdr)
    val controller = remember(metadata?.route, key) {
        runCatching {
            val previous = (BackgroundPlaybackSession.activeHandle() as? MpvPlaybackController)
                ?.takeIf { com.ambient.tv.ui.player.sameVideoSettingsPlayback(metadata, BackgroundPlaybackSession.snapshot.value.metadata) }
            val imageOptions = previous?.imageOptions
                ?: (title?.mpv ?: resolution?.mpv ?: preferences.mpvImageOptions).withGlobalSvp(preferences)
            val saveDefaults = previous?.saveImageDefaults ?: false
            val restoreOutputState = previous?.captureOutputRestartState()
            // Decoder/output switches keep the same ViewModel startPositionMs; seed from the
            // live clock so loadfile/start is already near the right place before acquire seeks.
            val resumeMs = com.ambient.tv.ui.player.rendererReopenPositionMs(metadata?.isLive == true,
                previous?.positionMs()?.takeIf { it > 0L } ?: startPositionMs)
            val create = { MpvPlaybackController(context.applicationContext, url, resumeMs, headers, audioPassthroughEnabled, outputMode, imageOptions, networkCache, decoderMode, knownAudioCodec, nativeHdr,
                liveStream = metadata?.isLive == true, declaredHdr = declaredHdr, audioOnly = metadata?.hasVideo == false, preferOriginalAudio = metadata?.source == "youtube",
                reopeningTimeshift = previous?.url == url).also {
                it.saveImageDefaults = saveDefaults
                restoreOutputState?.invoke(it)
                if (previous == null) (title?.mpvDeinterlaceMode ?: resolution?.mpvDeinterlaceMode)?.let { mode ->
                    it.presetDeinterlace(mode, title?.mpvDeinterlaceFilter ?: resolution?.mpvDeinterlaceFilter ?: "auto")
                }
                if (previous == null) title?.mpvOverloadContinueSignature?.let(it::restoreOverloadContinue)
                else previous.overloadContinueSignature?.let(it::restoreOverloadContinue)
                if (previous == null) com.ambient.tv.ui.player.restoreSavedPlaybackAudio(it.audioSession, title, resolution)
                else it.audioSession.restoreFromSession(previous.audioSession)
                // Same playback rebuilt (output switch): keep this session's delay; otherwise the stored one.
                com.ambient.tv.ui.player.TitleVideoSettingsStore.attachAudio(it.audioSession, metadata,
                    if (previous == null) title?.audioDelayMs else previous.audioSession.titleDelayMs)
                (if (previous == null) title?.subtitleDelayMs else previous.subtitleDelayMs)?.takeIf { d -> d != 0 }?.let(it::setSubtitleDelay)
                com.ambient.tv.ui.player.AutomaticVideoProfiles.transfer(previous, it)
            } }
            if (metadata == null) create() else
                BackgroundPlaybackSession.acquire(context, metadata, "mpv", key, create)
        }.onFailure { Timber.e(it, "MPV backend initialization failed") }.getOrNull()
    }
    // Some live/remote streams report HDR only after the first decoded frame. Reopen
    // once on the direct surface; the session override is retained across recomposition.
    LaunchedEffect(controller?.sourceHdrStandard, controller, outputMode) {
        if (controller != null && metadata != null && controller.supportsGpuFilters &&
            nativeHdrSurfacePreferred(controller.sourceHdrStandard, HdrSupport.displayStandards(context))) {
            BackgroundPlaybackSession.setVideoSettings(metadata, settings.withMpvOutput("direct"))
        }
    }
    LaunchedEffect(controller, title, resolution) {
        controller ?: return@LaunchedEffect
        com.ambient.tv.ui.player.restoreSavedPlaybackAudio(controller.audioSession, title, resolution)
        val automatic = com.ambient.tv.ui.player.AutomaticVideoProfiles.state(controller)
        (title?.mpv ?: resolution?.mpv ?: preferences.mpvImageOptions).withGlobalSvp(preferences).let { image ->
            automatic.image.apply(controller.imageOptions, image) {
                controller.updateImageOptions(it)
                controller.imageOptions
            }
        }
        val mode = title?.mpvDeinterlaceMode ?: resolution?.mpvDeinterlaceMode
            ?: if (metadata?.isLive == true) tvheadendMpvDeinterlaceMode(preferences.tvheadendDeinterlaceModeKey) else "off"
        val filter = title?.mpvDeinterlaceFilter ?: resolution?.mpvDeinterlaceFilter
            ?: if (metadata?.isLive == true) preferences.tvheadendMpvDeinterlaceFilterKey else "auto"
        automatic.deinterlace.apply(controller.deinterlaceModeKey to controller.deinterlaceFilterKey, mode to filter) {
            controller.setDeinterlace(it.first != "off", it.second, it.first == "auto")
            controller.deinterlaceModeKey to controller.deinterlaceFilterKey
        }
    }
    DisposableEffect(controller) {
        onDispose { if (metadata == null) controller?.release() }
    }
    androidx.compose.runtime.SideEffect { retainedController = controller }
    return controller
}

@Composable
fun MpvVideoSurface(
    controller: MpvPlaybackController,
    modifier: Modifier = Modifier,
    sourceAspectRatioHint: Float? = null,
) {
    val presentation by controller.videoPresentation.collectAsState()
    var sourceAspectRatio by remember(controller, sourceAspectRatioHint) { mutableStateOf(sourceAspectRatioHint) }
    val appVisible by com.ambient.tv.ui.player.rememberAppVisible()
    LaunchedEffect(controller) {
        var pendingAspect: Float? = null
        var pendingCount = 0
        while (true) {
            val candidate = controller.videoAspectRatio()?.takeIf { it.isFinite() && it in 0.25f..4f }
            if (candidate != null) {
                val current = sourceAspectRatio
                if (current == null) {
                    // First valid decoder geometry can be used immediately.
                    sourceAspectRatio = candidate
                } else if (kotlin.math.abs(candidate - current) / current > 0.01f) {
                    // MediaCodec can briefly expose transitional dimensions while flushing or
                    // reconfiguring. Do not let one transient sample resize/crop the SurfaceView.
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
            // A temporary null/zero aspect during decoder reconfiguration deliberately keeps the
            // last known-good geometry instead of falling back to the viewport ratio.
            delay(com.ambient.tv.ui.player.playbackPollInterval(250, appVisible))
        }
    }
    val hdrHeadroom = com.ambient.tv.ui.player.videoSurfaceHdrHeadroom()
    key(controller) { AndroidView(
        factory = { context ->
            MpvSurfaceHost(context, controller)
        },
        onRelease = { it.release() },
        update = { view ->
            view.renderAtDisplayResolution = controller.supportsGpuFilters
            view.presentation = presentation
            view.sourceAspectRatio = sourceAspectRatio
            view.videoSurface.applyUiHdrHeadroom(hdrHeadroom)
        },
        modifier = modifier.ignoreVideoSurfaceFocus(),
    ) }
}

private class MpvSurfaceHost(
    context: Context,
    private val controller: MpvPlaybackController,
) : VideoSurfaceLayout(context), SurfaceHolder.Callback {
    init {
        renderAtDisplayResolution = controller.supportsGpuFilters
        videoSurface.holder.addCallback(this)
    }

    override fun onDisplayRefreshRateChanged(refreshRate: Float) = controller.displayRefreshRateChanged(refreshRate)

    override fun surfaceCreated(holder: SurfaceHolder) {
        val frame = holder.surfaceFrame
        controller.attachSurface(holder.surface, frame.width(), frame.height())
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        controller.surfaceSizeChanged(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        controller.detachSurface(holder.surface)
    }

    fun release() {
        videoSurface.holder.removeCallback(this)
        if (videoSurface.holder.surface.isValid) controller.detachSurface(videoSurface.holder.surface)
    }
}

@Composable
fun rememberMpvPlaybackSnapshot(
    controller: MpvPlaybackController,
    nerdStatsEnabled: Boolean = false,
): MpvPlaybackSnapshot {
    var snapshot by remember(controller) { mutableStateOf(MpvPlaybackSnapshot(wantsToPlay = controller.wantsToPlay())) }
    val appVisible by com.ambient.tv.ui.player.rememberAppVisible()
    val changingItem = com.ambient.tv.ui.player.LocalPlayerChangingItem.current
    LaunchedEffect(controller, nerdStatsEnabled, changingItem) {
        if (changingItem) return@LaunchedEffect
        var lastNerdPollMs = 0L
        var mediaInfoSeen = -1
        var lastMediaInfoMs = 0L
        var changesSeen = -1L
        while (true) {
            // With the event pump, mpv's events were already drained when they arrived.
            if (!controller.eventDriven) controller.pollEvents()
            changesSeen = controller.stateChanges.value
            val now = android.os.SystemClock.elapsedRealtime()
            // Codecs, size, decoder and frame rate change only on load/reconfigure events; read
            // them then (and every 2 s for the estimated fps) instead of on every 350 ms tick.
            val refreshMediaInfo = mediaInfoSeen != controller.mediaInfoGeneration || now - lastMediaInfoMs >= 2_000L
            if (refreshMediaInfo) {
                mediaInfoSeen = controller.mediaInfoGeneration
                lastMediaInfoMs = now
            }
            val observedAudioCodec = if (refreshMediaInfo) controller.audioCodec() else snapshot.audioCodec
            if (refreshMediaInfo) controller.noteAudioCodecName(observedAudioCodec)
            val pollNerdStats = nerdStatsEnabled && (lastNerdPollMs == 0L || now - lastNerdPollMs >= 750L)
            snapshot = snapshot.copy(
                confirmedAudioOnly = if (refreshMediaInfo) controller.isConfirmedAudioOnly() else snapshot.confirmedAudioOnly,
                audioOutputFormat = if (pollNerdStats) controller.audioOutputFormat() else snapshot.audioOutputFormat,
                audioOutputDriver = if (pollNerdStats) controller.audioOutputDriver() else snapshot.audioOutputDriver,
                isPlaying = controller.isPlaying(),
                wantsToPlay = controller.wantsToPlay(),
                positionMs = controller.positionMs(),
                durationMs = controller.durationMs(),
                videoCodec = if (refreshMediaInfo) controller.videoCodec() else snapshot.videoCodec,
                audioCodec = observedAudioCodec,
                videoSize = if (refreshMediaInfo) controller.videoSize() else snapshot.videoSize,
                fps = if (refreshMediaInfo) controller.videoFps() else snapshot.fps,
                // Source cadence drives display matching even when every stats overlay is off.
                containerFps = if (refreshMediaInfo || pollNerdStats) controller.containerFps() else snapshot.containerFps,
                decoder = if (refreshMediaInfo) controller.decoder() else snapshot.decoder,
                videoBitrateBitsPerSecond = if (pollNerdStats) controller.videoBitrateBitsPerSecond() else snapshot.videoBitrateBitsPerSecond,
                audioBitrateBitsPerSecond = if (pollNerdStats) controller.audioBitrateBitsPerSecond() else snapshot.audioBitrateBitsPerSecond,
                cacheDurationSeconds = if (pollNerdStats) controller.cacheDurationSeconds() else snapshot.cacheDurationSeconds,
                cacheSpeedBytesPerSecond = if (pollNerdStats) controller.cacheSpeedBytesPerSecond() else snapshot.cacheSpeedBytesPerSecond,
                avSyncSeconds = if (pollNerdStats) controller.avSyncSeconds() else snapshot.avSyncSeconds,
                frameDropCount = if (pollNerdStats) controller.frameDropCount() else snapshot.frameDropCount,
                decoderFrameDropCount = if (pollNerdStats) controller.decoderFrameDropCount() else snapshot.decoderFrameDropCount,
                displayFps = if (pollNerdStats) controller.displayFps() else snapshot.displayFps,
                pixelFormat = if (pollNerdStats) controller.pixelFormat() else snapshot.pixelFormat,
                outputPixelFormat = if (pollNerdStats) controller.outputPixelFormat() else snapshot.outputPixelFormat,
                activeVideoFilters = if (pollNerdStats) controller.activeVideoFilters() else snapshot.activeVideoFilters,
                activeScaler = if (pollNerdStats) controller.activeScaler() else snapshot.activeScaler,
                activeUpscaling = if (pollNerdStats) controller.activeUpscaling() else snapshot.activeUpscaling,
                activeChromaScaler = if (pollNerdStats) controller.activeChromaScaler() else snapshot.activeChromaScaler,
                activeDownscaler = if (pollNerdStats) controller.activeDownscaler() else snapshot.activeDownscaler,
                activeShaderPreset = if (pollNerdStats) controller.activeShaderPreset() else snapshot.activeShaderPreset,
                configuredScaler = if (pollNerdStats) controller.imageOptions.scaler else snapshot.configuredScaler,
                videoOutput = if (pollNerdStats) controller.configuredVideoOutput() else snapshot.videoOutput,
                accelerationFallback = controller.accelerationFallback,
                zeroCopyStatus = controller.zeroCopyStatus,
                processingSampled = if (pollNerdStats) true else snapshot.processingSampled,
                activeDeinterlace = if (pollNerdStats) controller.activeDeinterlace() else snapshot.activeDeinterlace,
                svpStatus = if (refreshMediaInfo || pollNerdStats) controller.svpStatus() else snapshot.svpStatus,
                gpuScalerExposed = if (pollNerdStats) controller.supportsGpuFilters else snapshot.gpuScalerExposed,
            )
            if (pollNerdStats) lastNerdPollMs = now
            if (controller.eventDriven) {
                // Rebuild on a pushed change (pause, seek, buffering, codec, end …) or on a tick
                // that only advances the position: 500 ms while playing, 2 s while paused.
                val tickMs = mpvSnapshotTickMs(snapshot.isPlaying, nerdStatsEnabled)
                val seen = changesSeen
                kotlinx.coroutines.withTimeoutOrNull(com.ambient.tv.ui.player.playbackPollInterval(tickMs, appVisible)) {
                    controller.stateChanges.first { it != seen }
                }
                delay(MPV_SNAPSHOT_MIN_SPACING_MS)
            } else {
                // Fallback without a wakeup pipe: the previous fixed poll.
                delay(com.ambient.tv.ui.player.playbackPollInterval(350, appVisible))
            }
        }
    }
    return snapshot
}

/** Shortest gap between two snapshot rebuilds, so a burst of property changes costs one. */
private const val MPV_SNAPSHOT_MIN_SPACING_MS = 100L

/** Position-only refresh interval of the event-driven snapshot. */
internal fun mpvSnapshotTickMs(playing: Boolean, nerdStats: Boolean): Long = when {
    nerdStats -> 750L
    playing -> 500L
    else -> 2_000L
}
