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
    private var lanczosInChain = false
    private var applied: Triple<Media3ImageOptions, Int, Int>? = null
    private var updatePosted = false
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
    // Media3 1.4.1 retains its video sink after setVideoEffects(emptyList()). Do not describe
    // an empty chain as direct output: direct output returns with the next player instance.
    var gpuPipelineInitialized by mutableStateOf(false)
        private set

    val hasConfiguredEffects: Boolean
        get() = options.enabled && (
            options.scaler == "lanczos" ||
                options.brightness != 0 ||
                options.contrast != 0 ||
                options.saturation != 0
            )

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
        media3AppliedProcessing(
            effectsActive = false,
            scaler = null,
            brightness = 0,
            contrast = 0,
            saturation = 0,
            outputWidth = 0,
            outputHeight = 0,
        ),
    )
        private set

    /** Effect chain last handed to ExoPlayer, not the saved settings defaults. */
    fun appliedProcessing(): AppliedVideoProcessing {
        val signature = applied
        val options = signature?.first
        return media3AppliedProcessing(
            effectsActive = effectsActive,
            scaler = options?.scaler?.takeIf { lanczosInChain },
            brightness = options?.brightness ?: 0,
            contrast = options?.contrast ?: 0,
            saturation = options?.saturation ?: 0,
            outputWidth = signature?.second ?: 0,
            outputHeight = signature?.third ?: 0,
        )
    }

    private fun publishApplied() {
        appliedForStats = appliedProcessing()
    }

    fun diagnosticSummary(): String = when {
        effectsActive -> "SDR GPU requested · ${options.scaler} · brightness ${options.brightness} · contrast ${options.contrast} · saturation ${options.saturation}"
        gpuPipelineInitialized -> "GPU initialized · effects bypassed"
        !gpuEffectsSupported && options.enabled -> "Direct · GPU effects bypassed on Shield Android 11"
        options.enabled -> "Direct · waiting for eligible SDR track"
        else -> "Direct · effects disabled"
    }

    fun updateOptions(value: Media3ImageOptions) {
        val next = value.normalized()
        if (next != options) PlaybackOutputTransition.begin(appContext)
        options = next
        scheduleUpdate()
    }

    fun refreshPerformanceOverrides() {
        applied = null
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
        val needsLanczos = enabled && effective.scaler == "lanczos" && format?.let { source ->
            media3NeedsUpscaling(source.width, source.height, outputWidth, outputHeight,
                source.pixelWidthHeightRatio, source.rotationDegrees)
        } == true
        if (signature == applied && needsLanczos == lanczosInChain) return
        if (!enabled && !gpuPipelineInitialized) {
            effectsActive = false
            lanczosInChain = false
            applied = signature
            publishApplied()
            return // Even an empty list before prepare would unnecessarily enable the GPU sink.
        }
        val effects = buildList<Effect> {
            if (enabled) {
                if (needsLanczos) {
                    add(LanczosResample.scaleToFit(outputWidth, outputHeight))
                }
                if (effective.brightness != 0) add(Brightness(effective.brightness / 100f))
                if (effective.contrast != 0) add(Contrast(effective.contrast / 100f))
                if (effective.saturation != 0) add(HslAdjustment.Builder().adjustSaturation(effective.saturation.toFloat()).build())
            }
        }
        applied = signature
        if (effects.isEmpty() && !gpuPipelineInitialized) {
            effectsActive = false
            lanczosInChain = false
            publishApplied()
            return
        }
        if (!gpuPipelineInitialized) {
            // 1.4.1 only creates the sink when enabling/resetting the renderer. stop() resets
            // renderers outside foreground mode; setVideoEffects MUST precede prepare().
            val index = player.currentMediaItemIndex
            val position = player.currentPosition.coerceAtLeast(0L)
            val playWhenReady = player.playWhenReady
            val live = player.isCurrentMediaItemLive ||
                ((BackgroundPlaybackSession.activeHandle() as? Media3PlaybackHandle)?.player === player &&
                    BackgroundPlaybackSession.snapshot.value.metadata?.isLive == true)
            player.setForegroundMode(false)
            player.stop()
            player.setVideoEffects(effects)
            gpuPipelineInitialized = true
            if (!player.reopenTimeshiftAtLiveEdge()) {
                if (live) player.seekToDefaultPosition() else player.seekTo(index, position)
            }
            player.playWhenReady = playWhenReady
            player.prepare()
        } else {
            player.setVideoEffects(effects)
        }
        effectsActive = enabled && effects.isNotEmpty()
        lanczosInChain = needsLanczos
        publishApplied()
    }

    companion object {
        private val instances = WeakHashMap<ExoPlayer, Media3ImageProcessing>()
        fun gpuInitialized(player: ExoPlayer): Boolean = instances[player]?.gpuPipelineInitialized == true
        /** No processor means nothing has been applied. Does not create one. */
        fun peekApplied(player: ExoPlayer): AppliedVideoProcessing =
            instances[player]?.appliedForStats ?: media3AppliedProcessing(
                effectsActive = false,
                scaler = null,
                brightness = 0,
                contrast = 0,
                saturation = 0,
                outputWidth = 0,
                outputHeight = 0,
            )
        fun get(context: Context, player: ExoPlayer): Media3ImageProcessing =
            instances.getOrPut(player) { Media3ImageProcessing(context.applicationContext, player) }
        fun release(player: ExoPlayer) {
            instances.remove(player)?.let { it.handler.removeCallbacksAndMessages(null) }
        }
    }
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
}
