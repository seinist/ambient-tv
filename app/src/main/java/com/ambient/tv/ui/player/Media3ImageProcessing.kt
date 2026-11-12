@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import android.content.Context
import android.os.Build
import android.os.Handler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.LanczosResample
import androidx.media3.effect.GlEffect
import com.ambient.tv.R
import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.backend.effectiveMedia3ImageOptions
import com.ambient.tv.ui.player.backend.media3ImageOptionsAfterOverload
import com.ambient.tv.ui.player.backend.media3RenderOverloaded
import com.ambient.tv.ui.player.backend.mpvRenderSampleSeconds
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal enum class Media3ImageSourceRestriction { UNKNOWN_VIDEO, DRM, DOLBY_VISION, HDR, UNKNOWN_COLOR }

/** One source policy for both the running chain and the reason shown by the filter panel. */
internal fun media3ImageEffectsRestriction(
    format: Format?,
    declaredDolbyVision: Boolean = false,
): Media3ImageSourceRestriction? {
    // A decoder may report only the base layer. Do not lose the source's DV protection.
    if (declaredDolbyVision) return Media3ImageSourceRestriction.DOLBY_VISION
    format ?: return Media3ImageSourceRestriction.UNKNOWN_VIDEO
    if (format.drmInitData != null || format.cryptoType != C.CRYPTO_TYPE_NONE) return Media3ImageSourceRestriction.DRM
    if (RendererCapabilityPolicy.isDolbyVision(format.codecs, format.sampleMimeType, null)) {
        return Media3ImageSourceRestriction.DOLBY_VISION
    }
    // Fail closed when the stream does not identify itself as SDR, unless the codec profile
    // cannot carry HDR without saying so (most H.264 and 8-bit encodes write no colour info).
    val transfer = format.colorInfo?.colorTransfer?.takeIf { it != Format.NO_VALUE }
    if (media3TransferIsSdr(transfer) || transfer == null && media3UnsignalledIsSdr(format.sampleMimeType, format.codecs)) return null
    return if (transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG)
        Media3ImageSourceRestriction.HDR else Media3ImageSourceRestriction.UNKNOWN_COLOR
}

/** Remains SDR-only: the same predicate also guards the separate SDR-to-HDR conversion path. */
internal fun media3ImageEffectsAllowed(format: Format?): Boolean = media3ImageEffectsRestriction(format) == null

/**
 * Streams without a transfer characteristic that are SDR in practice: AVC, MPEG-2/4, VC-1 and the
 * 8-bit profiles of HEVC (Main), VP9 (profile 0) and AV1. HDR10 and HLG always signal their
 * transfer; an unsignalled 10-bit HEVC/VP9/AV1 stream stays fail-closed.
 */
internal fun media3UnsignalledIsSdr(mimeType: String?, codecs: String?): Boolean {
    val codec = codecs?.split(',')?.map { it.trim().lowercase() }?.firstOrNull { it.isNotEmpty() }.orEmpty()
    return when (mimeType) {
        MimeTypes.VIDEO_H264, MimeTypes.VIDEO_MPEG2, MimeTypes.VIDEO_MP4V, MimeTypes.VIDEO_VC1, MimeTypes.VIDEO_H263 -> true
        MimeTypes.VIDEO_H265 -> codec.startsWith("hvc1.1.") || codec.startsWith("hev1.1.")
        MimeTypes.VIDEO_VP9 -> codec.startsWith("vp09.00.")
        MimeTypes.VIDEO_AV1 -> codec.split('.').getOrNull(3) == "08"
        else -> false
    }
}

/** Whether a playback error comes from the video effect chain (shader, GPU feature, frame processor). */
internal fun media3EffectFailure(errorCode: Int, error: Throwable?): Boolean =
    errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
        generateSequence(error) { it.cause }.any { it is VideoFrameProcessingException }

/** sRGB and gamma 2.2 are SDR transfers too; unknown/absent transfer stays fail-closed. */
internal fun media3TransferIsSdr(transfer: Int?): Boolean =
    transfer == C.COLOR_TRANSFER_SDR || transfer == C.COLOR_TRANSFER_SRGB || transfer == C.COLOR_TRANSFER_GAMMA_2_2

// Shield Android 11: the Media3 1.4.1 effect sink produces black frames and can
// wedge NVIDIA GL (RenderThread waits in libnvglsi). Keep saved effects intact,
// but use the working direct decoder surface on this driver family.
internal fun media3GpuEffectsSupported(manufacturer: String, sdk: Int): Boolean =
    !(manufacturer.equals("NVIDIA", ignoreCase = true) && sdk <= 30)

/** One state per player, including while its full-screen UI is detached for the mini-player.
 * Values hold only weak player references so the registry cannot retain released decoders.
 */
@UnstableApi
/** Steady seconds before the dropped-buffer baseline is taken, like MPV's normal warm-up. */
private const val MEDIA3_RENDER_WARMUP_SECONDS = 3

internal class Media3ImageProcessing private constructor(context: Context, player: ExoPlayer) {
    val gpuEffectsSupported = media3GpuEffectsSupported(Build.MANUFACTURER, Build.VERSION.SDK_INT)
    private val appContext = context.applicationContext
    private val playerRef = WeakReference(player)
    private val handler = Handler(player.applicationLooper)
    private var reducedEffects = reducedVideoEffects(context)
    private var format: Format? = player.videoFormat
    private val renderGeometry = Media3RenderGeometry()
    // Shader destinations and GPU budgets always use the confirmed surface, never a prediction.
    private val outputWidth: Int get() = renderGeometry.confirmedSurface?.first ?: 0
    private val outputHeight: Int get() = renderGeometry.confirmedSurface?.second ?: 0
    /** Scaler key the chain really upscales with; null while the display path scales. */
    private var scalerInChain: String? = null
    private var applied: Triple<Media3ImageOptions, Int, Int>? = null
    private var updatePosted = false
    /** Set by a settings change (user or Sparsam); format and surface changes leave it false. */
    private var userChangePending = false
    var options by mutableStateOf(videoImagePreferences(context).media3ImageOptions)
        private set
    private val automaticOptions = AutomaticProfileValue(options)
    internal fun restoreAutomaticOptions(next: Media3ImageOptions) {
        automaticOptions.apply(options, next) { value ->
            val normalized = value.normalized()
            if (normalized != options) {
                // Automatic title/resolution refinement is not a settings interaction. Marking it
                // as one forces the "remove last effect" path through stop()/prepare(), which can
                // produce a second startup spinner after the first frame already appeared.
                effectsFailed = false
                options = normalized
                scheduleUpdate()
            }
            options
        }
    }
    var saveDefaults by mutableStateOf(false)
    var effectsActive by mutableStateOf(false)
        private set
    var sourceRestriction by mutableStateOf(media3ImageEffectsRestriction(player.videoFormat))
        private set
    /** True only while the current track is SDR and Media3 will actually run the effect chain. */
    var sdrEffectsAllowed by mutableStateOf(media3ImageEffectsAllowed(player.videoFormat))
        private set
    /** Preserve the display resolution as soon as the eligible processing path is requested. */
    var rendererScalingRequested by mutableStateOf(false)
        private set
    var scaleRelation by mutableStateOf(VideoScaleRelation.UNKNOWN)
        private set
    /** Deband, sharpening or a heavy scaler is configured but skipped: the video is above the GPU budget. */
    var filtersOverBudget by mutableStateOf(false)
        private set
    /** Explain each omitted filter independently, including its actual processing workload. */
    var filterBudgetExclusions by mutableStateOf(emptyList<Media3FilterBudgetExclusion>())
        private set
    var effectReductions by mutableStateOf(emptyList<Media3EffectReduction>())
        private set
    /** The scaler which really provides sharpening; a requested NIS fallback does not count. */
    var integratedSharpening by mutableStateOf<String?>(null)
        private set
    // Media3 1.4.1 retains its video sink after setVideoEffects(emptyList()). Do not describe
    // an empty chain as direct output: direct output returns with the next player instance.
    var gpuPipelineInitialized by mutableStateOf(false)
        private set
    /**
     * The effect chain failed on this device (a shader that does not compile, a missing GPU
     * feature): playback continues without effects until the settings change.
     */
    var effectsFailed by mutableStateOf(false)
        private set

    val hasConfiguredEffects: Boolean
        get() = media3EffectPlan(options, upscaling = true).isNotEmpty()

    /** Set after the effect chain drops too many output buffers; playback pauses for the dialog. */
    var renderOverloaded by mutableStateOf(false)
        private set
    var overloadDropPercent: Double? = null
        private set
    /** Chain accepted with "keep watching anyway"; captured into the title settings on stop. */
    var overloadContinueSignature: String? = null
        private set
    val canReduceAfterOverload: Boolean get() = media3ImageOptionsAfterOverload(options) != null
    private val renderCheck = Runnable { checkRender() }
    private var renderCheckSignature: String? = null
    private var appliedWorkloadSignature: String? = null
    /** Chain which passed its sample, or which the user is adjusting by hand: not sampled again. */
    private var settledSignature: String? = null
    private var steadySeconds = 0
    private var baselineCounters: Pair<Long, Long>? = null

    fun restoreOverloadContinue(signature: String?) {
        overloadContinueSignature = signature?.takeIf { it.isNotBlank() }
    }

    /** "Settings": the user adjusts by hand; the next chain change re-arms the check. */
    fun dismissRenderOverload() {
        renderOverloaded = false
        settledSignature = renderCheckSignature
    }

    fun continueDespiteOverload() {
        overloadContinueSignature = renderCheckSignature
        renderOverloaded = false
        playerRef.get()?.play()
    }

    /** One lighter step, kept like a manual change; the changed chain gets a fresh sample. */
    fun reduceAfterOverload() {
        renderOverloaded = false
        playerRef.get()?.play()
        media3ImageOptionsAfterOverload(options)?.let(::updateOptions)
    }

    /**
     * Same guard as MPV and VLC: after a short warm-up, more than 10% dropped output buffers
     * over the sample window (6 s, or the configured frame-drop test) pause playback.
     */
    private fun checkRender() {
        // A collected player ends the loop (release() is the normal way out).
        val player = playerRef.get() ?: return
        handler.postDelayed(renderCheck, 1_000L)
        val signature = if (effectsActive) appliedWorkloadSignature else null
        if (signature != renderCheckSignature) {
            renderCheckSignature = signature
            steadySeconds = 0
            baselineCounters = null
        }
        if (signature == null || signature == settledSignature || signature == overloadContinueSignature ||
            renderOverloaded || !player.isPlaying || PlaybackOutputTransition.active()) {
            steadySeconds = 0
            baselineCounters = null
            return
        }
        val counters = player.videoDecoderCounters ?: return
        counters.ensureUpdated()
        val dropped = counters.droppedBufferCount.toLong()
        val rendered = counters.renderedOutputBufferCount.toLong()
        steadySeconds++
        if (steadySeconds == MEDIA3_RENDER_WARMUP_SECONDS) baselineCounters = dropped to rendered
        val (startDropped, startRendered) = baselineCounters ?: return
        val sampleSeconds = mpvRenderSampleSeconds(false, videoImagePreferences(appContext).frameDropTestSeconds)
        if (steadySeconds < MEDIA3_RENDER_WARMUP_SECONDS + sampleSeconds.toInt()) return
        val droppedInSample = dropped - startDropped
        val renderedInSample = rendered - startRendered
        steadySeconds = 0
        baselineCounters = null
        // Counters restart with the renderer; that sample says nothing.
        if (droppedInSample < 0 || renderedInSample < 0) return
        if (!media3RenderOverloaded(droppedInSample, renderedInSample)) {
            settledSignature = signature
            return
        }
        com.ambient.tv.diagnostics.DiagnosticLog.warn("playback",
            "Media3 render overload: $droppedInSample dropped / $renderedInSample rendered in ${sampleSeconds.toInt()} s with $signature")
        overloadDropPercent = frameDropPercent(droppedInSample, (droppedInSample + renderedInSample).toDouble())
        player.pause()
        renderOverloaded = true
    }

    init {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, inputFormat: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                format = inputFormat
                scheduleUpdate()
            }
            override fun onSurfaceSizeChanged(eventTime: AnalyticsListener.EventTime, width: Int, height: Int) {
                if (renderGeometry.updateSurface(width, height)) scheduleUpdate()
            }
        })
        scheduleUpdate()
        handler.postDelayed(renderCheck, 1_000L)
    }

    /** Compose-visible copy of the chain last handed to ExoPlayer. */
    var appliedForStats by mutableStateOf(
        media3AppliedProcessing(effectsActive = false, scaler = null, stages = emptyList()),
    )
        private set
    private var appliedStages: List<Media3Stage> = emptyList()

    /** Effect chain last handed to ExoPlayer, not the saved settings defaults. */
    fun appliedProcessing(): AppliedVideoProcessing {
        return media3AppliedProcessing(
            effectsActive = effectsActive,
            scaler = scalerInChain,
            stages = appliedStages,
        )
    }

    private fun publishApplied() {
        appliedForStats = appliedProcessing()
    }

    fun diagnosticSummary(): String = when {
        effectsActive -> "SDR GPU requested · " + appliedStages.joinToString(" · ") { media3StageSummary(it) }
        gpuPipelineInitialized -> "GPU initialized · effects bypassed"
        !gpuEffectsSupported && options.enabled -> "Direct · GPU effects bypassed on Shield Android 11"
        options.enabled -> "Direct · waiting for eligible SDR track"
        else -> "Direct · effects disabled"
    }

    fun updateOptions(value: Media3ImageOptions) {
        val next = value.normalized()
        if (next != options) {
            PlaybackOutputTransition.begin(appContext)
            userChangePending = true
            // Other settings get their own attempt.
            effectsFailed = false
        }
        options = next
        scheduleUpdate()
    }

    fun refreshPerformanceOverrides() {
        reducedEffects = reducedVideoEffects(appContext)
        applied = null
        userChangePending = true
        scheduleUpdate()
    }

    internal fun updatePerformancePolicy(reduced: Boolean) {
        if (reducedEffects == reduced) return
        reducedEffects = reduced
        applied = null
        userChangePending = true
        scheduleUpdate()
    }

    internal fun attachRenderTarget(owner: Any) {
        if (renderGeometry.attach(owner)) scheduleUpdate()
    }

    internal fun updateRenderTarget(owner: Any, size: Pair<Int, Int>?) {
        if (renderGeometry.updateTarget(owner, size)) scheduleUpdate()
    }

    internal fun detachRenderTarget(owner: Any) {
        if (renderGeometry.detach(owner)) scheduleUpdate()
    }

    private fun scheduleUpdate() {
        if (updatePosted) return
        updatePosted = true
        handler.post {
            updatePosted = false
            applyOptions()
        }
    }

    private fun applyOptions() {
        val player = playerRef.get() ?: return
        val sourceMetadata = BackgroundPlaybackSession.snapshot.value.metadata.takeIf {
            (BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player === player
        }
        sourceRestriction = media3ImageEffectsRestriction(format,
            declaredDolbyVision = sourceMetadata?.dolbyVisionSource == true ||
                sourceMetadata?.handheldHdrStandard == HdrStandard.DOLBY_VISION)
        sdrEffectsAllowed = sourceRestriction == null
        val viewport = renderGeometry.scalerViewport
        scaleRelation = if (viewport == null) VideoScaleRelation.UNKNOWN else
            format?.takeIf { it.width > 0 && it.height > 0 }?.let {
                when {
                    media3NeedsUpscaling(it.width, it.height, viewport.first, viewport.second,
                        it.pixelWidthHeightRatio, it.rotationDegrees) -> VideoScaleRelation.UPSCALE
                    media3NeedsDownscaling(it.width, it.height, viewport.first, viewport.second,
                        it.pixelWidthHeightRatio, it.rotationDegrees) -> VideoScaleRelation.DOWNSCALE
                    else -> VideoScaleRelation.NATIVE
                }
            } ?: VideoScaleRelation.UNKNOWN
        // Sparsam disables the effect chain without clearing saved preferences.
        val gated = effectiveMedia3ImageOptions(options, reducedEffects)
        val enabled = gpuEffectsSupported && !effectsFailed && gated.enabled && sdrEffectsAllowed
        // This must not wait for a Scale stage: the display matcher could otherwise switch to
        // the source's 1080p mode before the 4K surface makes that very stage available.
        rendererScalingRequested = enabled
        // Player release clears the playlist. Never revive a stopped/released session.
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        val effective = if (enabled) gated else Media3ImageOptions()
        val signature = Triple(effective, outputWidth, outputHeight)
        // Scalers only run while the fitted picture is larger than the source: never past the display.
        val upscaling = enabled && format?.let { source ->
            media3NeedsUpscaling(source.width, source.height, outputWidth, outputHeight,
                source.pixelWidthHeightRatio, source.rotationDegrees)
        } == true
        val downscaling = enabled && format?.let { source ->
            media3NeedsDownscaling(source.width, source.height, outputWidth, outputHeight,
                source.pixelWidthHeightRatio, source.rotationDegrees)
        } == true
        val configuredStages = if (enabled) media3EffectPlan(effective, upscaling, downscaling = downscaling) else emptyList()
        val fps = format?.frameRate ?: 0f
        // Media3 rotates and expands non-square pixels before custom effects run. The raw
        // format is conservative for decoder crops, but must include that expansion for budgets.
        val processingInputSize = format?.let {
            media3ProcessingInputSize(it.width, it.height, it.pixelWidthHeightRatio, it.rotationDegrees)
        } ?: (0 to 0)
        // NIS, RAVU and the EWA/wide kernels at most 4K30 output; above that they run as Catmull-Rom.
        val heavyScaleAllowed = !upscaling || media3HeavyScalerBudgetAllows(outputWidth, outputHeight, fps)
        val heavyDownscaleAllowed = !downscaling || media3HeavyDownscaleBudgetAllows(
            processingInputSize.first, processingInputSize.second, outputWidth, outputHeight, fps)
        val ssimDownAllowed = !downscaling || media3SsimDownBudgetAllows(
            processingInputSize.first, processingInputSize.second, outputWidth, outputHeight, fps)
        val ssimSuperAllowed = !upscaling || media3SsimSuperBudgetAllows(
            processingInputSize.first, processingInputSize.second, outputWidth, outputHeight, fps)
        val anime4kAllowed = !upscaling || format?.let { source ->
            // The CNN's 2x feature grid assumes square pixels and an unrotated source.
            // Keep Media3's normal geometry path for anamorphic or rotated tracks.
            source.rotationDegrees % 180 == 0 && source.pixelWidthHeightRatio == 1f &&
                media3Anime4kBudgetAllows(effective.scaler, source.width, source.height,
                    outputWidth, outputHeight, fps)
        } == true
        val fsrcnnxAllowed = !upscaling || format?.let { source ->
            source.rotationDegrees % 180 == 0 && source.pixelWidthHeightRatio == 1f &&
                media3FsrcnnxBudgetAllows(effective.scaler, source.width, source.height,
                    outputWidth, outputHeight, fps)
        } == true
        val nnedi3Allowed = !upscaling || format?.let { source ->
            source.rotationDegrees % 180 == 0 && source.pixelWidthHeightRatio == 1f &&
                media3Nnedi3BudgetAllows(effective.scaler, source.width, source.height,
                    outputWidth, outputHeight, fps)
        } == true
        val computeAllowed = !upscaling || format?.let { source ->
            source.rotationDegrees % 180 == 0 && source.pixelWidthHeightRatio == 1f &&
                media3NeuralComputeBudgetAllows(effective.scaler, source.width, source.height,
                    outputWidth, outputHeight, fps)
        } == true
        val candidateStages = if (enabled) media3EffectPlan(effective, upscaling,
            heavyScaleAllowed = heavyScaleAllowed, downscaling = downscaling,
            heavyDownscaleAllowed = heavyDownscaleAllowed, ssimDownAllowed = ssimDownAllowed,
            ssimSuperAllowed = ssimSuperAllowed, anime4kAllowed = anime4kAllowed,
            fsrcnnxAllowed = fsrcnnxAllowed, nnedi3Allowed = nnedi3Allowed,
            computeAllowed = computeAllowed) else emptyList()
        // Budget the real stage order after scaler fallbacks. Cleanup reads source pixels;
        // sharpening/grain read the fitted GPU output, or the source with display-only scaling.
        val budget = media3ApplyFilterBudget(candidateStages,
            sourceWidth = processingInputSize.first, sourceHeight = processingInputSize.second,
            outputWidth = outputWidth, outputHeight = outputHeight, fps = fps)
        val stages = budget.stages
        filterBudgetExclusions = budget.excluded
        effectReductions = media3EffectReductions(configuredStages, stages)
        filtersOverBudget = enabled && (filterBudgetExclusions.isNotEmpty() || effectReductions.isNotEmpty())
        // A source can move from 1080p24 to UHD60 without changing the shader list. That is
        // a new workload and must not inherit the successful/accepted sample of the old one.
        appliedWorkloadSignature = media3RenderWorkloadSignature(stages,
            sourceWidth = processingInputSize.first, sourceHeight = processingInputSize.second,
            outputWidth = outputWidth, outputHeight = outputHeight, fps = fps,
            colorTransfer = format?.colorInfo?.colorTransfer)
        val userChange = userChangePending
        userChangePending = false
        if (signature == applied && stages == appliedStages) return
        val scaler = stages.firstNotNullOfOrNull { (it as? Media3Stage.Scale)?.scaler }
        val effects = media3FusedStages(stages).map { media3Effect(it.stage, outputWidth, outputHeight, it.gamma) }
        // Programs are created on the GL thread after setVideoEffects; they read this flag then.
        val profile = performanceProfile(appContext)
        Media3ChainPrecision.halfFloatPasses = media3HalfFloatPasses(effects.size, profile.lowEndDevice, profile.reducedPlayback)
        applied = signature
        appliedStages = stages
        when (media3ChainTransition(prevActive = effectsActive, nextEmpty = effects.isEmpty(),
            gpuInitialized = gpuPipelineInitialized, userChange = userChange)) {
            // Even an empty list before prepare would unnecessarily enable the GPU sink.
            Media3ChainTransition.NONE -> Unit
            // First chain: 1.4.1+ only creates the sink when enabling/resetting the renderer.
            // Last effect removed by a settings change: the same restart, so the picture (also
            // when paused) shows the unfiltered frame right away instead of keeping the filtered one.
            // A source/surface size change that drops the scaler only updates the chain.
            Media3ChainTransition.RESTART -> restartWithEffects(player, effects)
            Media3ChainTransition.UPDATE -> player.setVideoEffects(effects)
        }
        effectsActive = enabled && effects.isNotEmpty()
        scalerInChain = scaler
        integratedSharpening = when {
            !effectsActive -> null
            scaler == "nis" -> "nis"
            scaler == "fsr" && Media3Stage.Sharpen("rcas") in stages -> "fsr"
            else -> null
        }
        publishApplied()
    }

    /**
     * A failing effect chain must not end playback: drop the effects and continue where the
     * picture stopped. A second failure without effects is a real playback error.
     */
    private fun recover(error: PlaybackException): Boolean {
        val player = playerRef.get() ?: return false
        if (effectsFailed || !effectsActive || !media3EffectFailure(error.errorCode, error)) return false
        com.ambient.tv.diagnostics.DiagnosticLog.error("playback",
            "Media3 effects failed (${appliedStages.joinToString { media3StageSummary(it) }}); continuing without them", error)
        effectsFailed = true
        rendererScalingRequested = false
        applied = Triple(Media3ImageOptions(), outputWidth, outputHeight)
        appliedStages = emptyList()
        Media3ChainPrecision.halfFloatPasses = false
        restartWithEffects(player, emptyList())
        effectsActive = false
        scalerInChain = null
        integratedSharpening = null
        filterBudgetExclusions = emptyList()
        effectReductions = emptyList()
        filtersOverBudget = false
        publishApplied()
        return true
    }

    /** stop() resets the renderers outside foreground mode; setVideoEffects MUST precede prepare(). */
    private fun restartWithEffects(player: ExoPlayer, effects: List<Effect>) {
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        val playWhenReady = player.playWhenReady
        val live = player.isCurrentMediaItemLive ||
            ((BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player === player &&
                BackgroundPlaybackSession.snapshot.value.metadata?.isLive == true)
        player.setForegroundMode(false)
        player.stop()
        player.setVideoEffects(effects)
        // The renderer keeps its video sink until release, even with an empty chain.
        gpuPipelineInitialized = true
        // Timeshift: continue where the viewer is, like an audio restart, not at the live edge.
        if (!player.reopenTimeshiftKeepingPosition("media3-effects")) {
            if (live) player.seekToDefaultPosition() else player.seekTo(index, position)
        }
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    companion object {
        private val instances = WeakHashMap<ExoPlayer, Media3ImageProcessing>()
        fun gpuInitialized(player: ExoPlayer): Boolean = instances[player]?.gpuPipelineInitialized == true
        fun rendererScalingRequested(player: ExoPlayer): Boolean = instances[player]?.rendererScalingRequested == true
        /** True when [error] came from the effect chain and playback restarted without it. */
        fun recoverFromEffectFailure(player: ExoPlayer, error: PlaybackException): Boolean =
            instances[player]?.recover(error) == true
        /** No processor means nothing has been applied. Does not create one. */
        fun peekApplied(player: ExoPlayer): AppliedVideoProcessing =
            instances[player]?.appliedForStats ?: media3AppliedProcessing(effectsActive = false, scaler = null, stages = emptyList())
        fun get(context: Context, player: ExoPlayer): Media3ImageProcessing =
            instances.getOrPut(player) { Media3ImageProcessing(context.applicationContext, player) }
        fun release(player: ExoPlayer) {
            instances.remove(player)?.let { it.handler.removeCallbacksAndMessages(null) }
        }
    }
}

/** GL effect for one planned stage; the built-in matrix effects merge into one pass. */
private fun media3Effect(stage: Media3Stage, outputWidth: Int, outputHeight: Int, gamma: Int = 0): Effect = when (stage) {
    is Media3Stage.Deblock -> Media3DeblockEffect(stage.strength, gamma)
    is Media3Stage.Denoise -> Media3DenoiseEffect(stage.strength, gamma)
    is Media3Stage.Deband -> Media3DebandEffect(stage.strength, gamma)
    is Media3Stage.Scale -> {
        val upscale = media3ScalerEffect(stage, outputWidth, outputHeight, gamma)
        if (stage.superResolution) Media3SsimSuperEffect(upscale) else upscale
    }
    is Media3Stage.Downscale -> when (stage.scaler) {
        "ssim_down" -> Media3SsimDownEffect(outputWidth, outputHeight)
        "lanczos" -> LanczosResample.scaleToFit(outputWidth, outputHeight)
        else -> Media3KernelEffect(stage.scaler, outputWidth, outputHeight,
            antiring = stage.antiring, sigmoid = false, downscaling = true)
    }
    is Media3Stage.Sharpen -> Media3SharpenEffect(stage.kind, gamma)
    is Media3Stage.Grain -> Media3FilmGrainEffect(stage.strength, gamma)
    is Media3Stage.Brightness -> Brightness(stage.value / 100f)
    is Media3Stage.Contrast -> Contrast(stage.value / 100f)
    is Media3Stage.Saturation -> HslAdjustment.Builder().adjustSaturation(stage.value.toFloat()).build()
    is Media3Stage.Gamma -> Media3GammaEffect(stage.value)
}

/** All Media3 scale algorithms can be wrapped by SSimSuperRes without replacing them. */
private fun media3ScalerEffect(stage: Media3Stage.Scale, outputWidth: Int, outputHeight: Int,
    gamma: Int): GlEffect = when (stage.scaler) {
    "lanczos" -> if (stage.superResolution)
        Media3KernelEffect("ssim_lanczos3", outputWidth, outputHeight, sigmoid = false)
        else LanczosResample.scaleToFit(outputWidth, outputHeight)
    "fsr" -> Media3FsrEasuEffect(outputWidth, outputHeight, gamma)
    in MEDIA3_ANIME4K_SCALERS -> Media3Anime4kEffect(stage.scaler, outputWidth, outputHeight)
    in MEDIA3_FSRCNNX_SCALERS -> Media3FsrcnnxEffect(stage.scaler, outputWidth, outputHeight)
    in MEDIA3_NNEDI3_SCALERS -> Media3Nnedi3Effect(stage.scaler, outputWidth, outputHeight)
    in MEDIA3_NEURAL_COMPUTE_SCALERS -> Media3NeuralComputeEffect(stage.scaler, outputWidth, outputHeight)
    in MEDIA3_RAVU_SCALERS -> Media3RavuEffect(stage.scaler, outputWidth, outputHeight)
    "nis" -> Media3NisEffect(outputWidth, outputHeight)
    in MEDIA3_KERNEL_SCALERS -> Media3KernelEffect(stage.scaler, outputWidth, outputHeight,
        antiring = stage.antiring, sigmoid = stage.sigmoid)
    "catmull_rom" -> Media3CubicScaleEffect(outputWidth, outputHeight, b = 0f, c = 0.5f,
        gamma = gamma, antiring = stage.antiring)
    else -> Media3CubicScaleEffect(outputWidth, outputHeight, b = 1f, c = 0f,
        gamma = gamma, antiring = stage.antiring)
}

@Composable
@UnstableApi
internal fun Media3ImageControls(player: ExoPlayer) {
    val context = LocalContext.current
    val processing = remember(player) { Media3ImageProcessing.get(context, player) }
    val preferences = remember(context) { videoImagePreferences(context) }
    val performance = LocalPerformanceProfile.current
    var appliedVideoVisualGate by remember(player) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(performance.gateExpensiveVideoVisuals) {
        val gate = performance.gateExpensiveVideoVisuals
        if (appliedVideoVisualGate == null) {
            appliedVideoVisualGate = gate
            return@LaunchedEffect
        }
        if (appliedVideoVisualGate == gate) return@LaunchedEffect
        appliedVideoVisualGate = gate
        processing.refreshPerformanceOverrides()
    }
    val o = processing.options.shown()
    fun change(next: Media3ImageOptions) {
        processing.updateOptions(next.withProcessingFromChoices())
        if (processing.saveDefaults) preferences.media3ImageOptions = processing.options
    }
    if (!processing.gpuEffectsSupported) {
        ImageHint(stringResource(R.string.image_media3_device_bypass))
        return
    }
    if (performance.gateExpensiveVideoVisuals) {
        ImageHint(stringResource(R.string.image_filters_sparsam_hint))
        return
    }
    if (processing.sourceRestriction != null) {
        ImageHint(media3SourceRestrictionText(processing.sourceRestriction))
        return
    }
    Media3ImageOptionsEditor(o, ::change, integratedSharpening = processing.integratedSharpening)
    Media3ImageProcessingFeedback(processing, showActive = processing.hasConfiguredEffects)
}

internal enum class Media3ChainTransition { NONE, RESTART, UPDATE }

/**
 * How a new effect chain reaches ExoPlayer. [prevActive]: effects are running now; [nextEmpty]: the
 * new chain has no effect; [gpuInitialized]: the GPU video sink exists (it stays until release).
 * Before the sink exists, an empty chain changes nothing and the first effect needs a restart.
 * Removing the last effect through a settings change ([userChange]) restarts the same way, so
 * filters stop at once, also while paused. When the chain empties on its own (the source no
 * longer needs upscaling: higher YouTube quality, SD→HD programme, smaller mini-player surface),
 * an update is enough; restarting there would rebuffer on every quality step.
 */
internal fun media3ChainTransition(
    prevActive: Boolean,
    nextEmpty: Boolean,
    gpuInitialized: Boolean,
    userChange: Boolean = true,
): Media3ChainTransition = when {
    !gpuInitialized -> if (nextEmpty) Media3ChainTransition.NONE else Media3ChainTransition.RESTART
    prevActive && nextEmpty && userChange -> Media3ChainTransition.RESTART
    else -> Media3ChainTransition.UPDATE
}
