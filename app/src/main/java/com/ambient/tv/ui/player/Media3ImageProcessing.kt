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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.LanczosResample
import com.ambient.tv.R
import com.ambient.tv.data.local.Media3ImageOptions
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.backend.effectiveMedia3ImageOptions
import java.lang.ref.WeakReference
import java.util.WeakHashMap

internal fun media3ImageEffectsAllowed(format: Format?): Boolean {
    format ?: return false
    if (format.drmInitData != null || format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION ||
        format.codecs?.split(',')?.any { it.trim().startsWith("dv", ignoreCase = true) } == true) return false
    // Fail closed when the stream does not identify itself as SDR. This keeps
    // Media3 aligned with MPV and avoids applying SDR effects to unknown HDR.
    return media3TransferIsSdr(format.colorInfo?.colorTransfer)
}

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
internal class Media3ImageProcessing private constructor(context: Context, player: ExoPlayer) {
    val gpuEffectsSupported = media3GpuEffectsSupported(Build.MANUFACTURER, Build.VERSION.SDK_INT)
    private val appContext = context.applicationContext
    private val playerRef = WeakReference(player)
    private val handler = Handler(player.applicationLooper)
    private var format: Format? = player.videoFormat
    private var outputWidth = 0
    private var outputHeight = 0
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
        automaticOptions.apply(options, next) {
            updateOptions(it)
            options
        }
    }
    var saveDefaults by mutableStateOf(false)
    var effectsActive by mutableStateOf(false)
        private set
    /** True only while the current track is SDR and Media3 will actually run the effect chain. */
    var sdrEffectsAllowed by mutableStateOf(media3ImageEffectsAllowed(player.videoFormat))
        private set
    var scaleRelation by mutableStateOf(VideoScaleRelation.UNKNOWN)
        private set
    /** Deband or sharpening is configured but skipped: the video is above the GPU budget. */
    var filtersOverBudget by mutableStateOf(false)
        private set
    // Media3 1.4.1 retains its video sink after setVideoEffects(emptyList()). Do not describe
    // an empty chain as direct output: direct output returns with the next player instance.
    var gpuPipelineInitialized by mutableStateOf(false)
        private set

    val hasConfiguredEffects: Boolean
        get() = media3EffectPlan(options, upscaling = true).isNotEmpty()

    init {
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, inputFormat: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                format = inputFormat
                scheduleUpdate()
            }
            override fun onSurfaceSizeChanged(eventTime: AnalyticsListener.EventTime, width: Int, height: Int) {
                if (width > 0 && height > 0) {
                    outputWidth = width
                    outputHeight = height
                    scheduleUpdate()
                }
            }
        })
        scheduleUpdate()
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
        }
        options = next
        scheduleUpdate()
    }

    fun refreshPerformanceOverrides() {
        applied = null
        userChangePending = true
        scheduleUpdate()
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
        sdrEffectsAllowed = media3ImageEffectsAllowed(format)
        scaleRelation = format?.takeIf { it.width > 0 && it.height > 0 && outputWidth > 0 && outputHeight > 0 }?.let {
            if (media3NeedsUpscaling(it.width, it.height, outputWidth, outputHeight,
                    it.pixelWidthHeightRatio, it.rotationDegrees)) VideoScaleRelation.UPSCALE
            else VideoScaleRelation.NATIVE
        } ?: VideoScaleRelation.UNKNOWN
        val player = playerRef.get() ?: return
        // Player release clears the playlist. Never revive a stopped/released session.
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_IDLE) return
        // Sparsam disables the GPU sink / Lanczos / brightness chain without clearing saved prefs.
        val gated = effectiveMedia3ImageOptions(options, reducedVideoEffects(appContext))
        val enabled = gpuEffectsSupported && gated.enabled && media3ImageEffectsAllowed(format)
        val effective = if (enabled) gated else Media3ImageOptions()
        val signature = Triple(effective, outputWidth, outputHeight)
        // Scalers only run while the fitted picture is larger than the source: never past the display.
        val upscaling = enabled && format?.let { source ->
            media3NeedsUpscaling(source.width, source.height, outputWidth, outputHeight,
                source.pixelWidthHeightRatio, source.rotationDegrees)
        } == true
        // Multi-tap deband/sharpen passes stay within the Full HD 60 fps budget (sharpening runs
        // after the scaler, so on the output size while upscaling).
        val fps = format?.frameRate ?: 0f
        val debandAllowed = format?.let { media3FilterBudgetAllows(it.width, it.height, fps) } ?: true
        val sharpenAllowed = if (upscaling) media3FilterBudgetAllows(outputWidth, outputHeight, fps) else debandAllowed
        val stages = if (enabled) media3EffectPlan(effective, upscaling, debandAllowed, sharpenAllowed) else emptyList()
        filtersOverBudget = enabled && stages != media3EffectPlan(effective, upscaling)
        val userChange = userChangePending
        userChangePending = false
        if (signature == applied && stages == appliedStages) return
        val scaler = stages.firstNotNullOfOrNull { (it as? Media3Stage.Scale)?.scaler }
        val effects = media3FusedStages(stages).map { media3Effect(it.stage, outputWidth, outputHeight, it.gamma) }
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
        publishApplied()
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
    is Media3Stage.Deband -> Media3DebandEffect(stage.strength, gamma)
    is Media3Stage.Scale -> when (stage.scaler) {
        "lanczos" -> LanczosResample.scaleToFit(outputWidth, outputHeight)
        "fsr" -> Media3FsrEasuEffect(outputWidth, outputHeight, gamma)
        "catmull_rom" -> Media3CubicScaleEffect(outputWidth, outputHeight, b = 0f, c = 0.5f, gamma = gamma)
        else -> Media3CubicScaleEffect(outputWidth, outputHeight, b = 1f, c = 0f, gamma = gamma)
    }
    is Media3Stage.Sharpen -> Media3SharpenEffect(stage.kind, gamma)
    is Media3Stage.Brightness -> Brightness(stage.value / 100f)
    is Media3Stage.Contrast -> Contrast(stage.value / 100f)
    is Media3Stage.Saturation -> HslAdjustment.Builder().adjustSaturation(stage.value.toFloat()).build()
    is Media3Stage.Gamma -> Media3GammaEffect(stage.value)
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
    val o = processing.options
    fun change(next: Media3ImageOptions) {
        processing.updateOptions(next)
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
    Media3ImageOptionsEditor(o, ::change)
    if (o.enabled && processing.hasConfiguredEffects) {
        ImageHint(stringResource(if (processing.effectsActive) R.string.image_media3_active else R.string.image_media3_waiting))
    }
    if (o.enabled && processing.filtersOverBudget) ImageHint(stringResource(R.string.image_media3_filters_over_budget))
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
