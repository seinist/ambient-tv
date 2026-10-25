package com.ambient.tv.ui.player

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.player.backend.VideoAccelerationRuntime
import com.ambient.tv.ui.player.backend.effectiveMpvDecoder
import com.ambient.tv.ui.player.backend.mpvDecoderChoices

/** Shared by the renderer and video panels for library playback and live TV. */
@Composable
internal fun PlayerVideoOutputControls(backend: String) {
    if (backend !in setOf("mpv", "vlc")) return
    val metadata = LocalPlaybackMetadata.current ?: return
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val title = TitleVideoSettingsStore.load(context, metadata)
    val resolution = ResolutionVideoProfileStore.load(context, metadata)
    val settings = BackgroundPlaybackSession.videoSettings(metadata, PlayerVideoSettings.from(preferences)
        .withResolution(resolution).withTitle(title))
    val caps by VideoAccelerationRuntime.capabilities.collectAsState()
    LaunchedEffect(Unit) { VideoAccelerationRuntime.refresh(context) }
    val mpv = backend == "mpv"
    // Same inputs as the player's own decision (MpvPlaybackView): same playback only, title key,
    // configured title/resolution options with global SVP, Dolby Vision from the track list.
    val activeMpv = (BackgroundPlaybackSession.activeHandle() as? com.ambient.tv.ui.player.backend.MpvPlaybackController)
        ?.takeIf { sameVideoSettingsPlayback(metadata, BackgroundPlaybackSession.snapshot.value.metadata) }
    val mediaKey = com.ambient.tv.ui.player.backend.mpvHdrGpuMediaKey(metadata, activeMpv?.url)
    val runtimeHdr = if (activeMpv?.dolbyVisionDetected == true) HdrStandard.DOLBY_VISION else activeMpv?.sourceHdrStandard
    val source = com.ambient.tv.ui.player.backend.mpvHdrGpuSourceStandard(metadata.handheldHdrStandard,
        com.ambient.tv.ui.player.backend.MpvHdrGpuSession.detectedHdr(mediaKey) ?: runtimeHdr,
        dolbyVisionRuntime = activeMpv?.dolbyVisionDetected == true)
    val display = HdrSupport.displayStandards(context)
    val passthroughRunning = activeMpv?.hdrPassthroughRunning == true
    val passthroughConfirmed = activeMpv?.hdrPassthroughConfirmed == true
    val failure = com.ambient.tv.ui.player.backend.MpvHdrGpuSession.failure(mediaKey,
        com.ambient.tv.ui.player.backend.mpvHdrGpuResolutionKey(metadata),
        com.ambient.tv.ui.player.backend.mpvHdrGpuDisplayKey(context, display))
    val explicitDirect = com.ambient.tv.ui.player.backend.mpvHdrGpuExplicitDirect(
        BackgroundPlaybackSession.videoSettingsOverride(metadata)?.mpvOutput, title?.mpvOutput, resolution?.mpvOutput,
        preferences.mpvVideoOutputModeKey)
    val effectsActive = com.ambient.tv.ui.player.backend.mpvHdrGpuEffectsActiveFor(context, metadata,
        activeMpv?.imageOptions ?: com.ambient.tv.ui.player.backend.mpvHdrGpuConfiguredImageOptions(preferences, title, resolution),
        passthroughRunning)
    val tvDevice = remember(context) { com.ambient.tv.ui.player.backend.mpvHdrGpuTvDevice(context) }
    fun hdrGpuFor(output: String, explicit: Boolean) = if (!mpv) com.ambient.tv.ui.player.backend.MpvHdrGpuDecision.NOT_APPLICABLE
        else com.ambient.tv.ui.player.backend.mpvHdrGpuDecision(preferences.mpvHdrGpuEnabled, output, source, display,
            caps, reducedVideoEffects(context), failure, explicitDirect = explicit, effectsActive = effectsActive, tvDevice = tvDevice)
    val hdrGpu = hdrGpuFor(settings.mpvOutput, explicitDirect)
    // With "Direkt" chosen the passthrough must stay selectable again.
    val hdrGpuProbe = hdrGpuFor("vulkan", explicit = false)
    val hdrGpuAvailable = hdrGpuProbe.gpu
    val nativeHdr = nativeHdrSurfacePreferred(source, display) && !hdrGpu.gpu
    val output = if (mpv) settings.mpvOutput else settings.vlcOutput
    val disabledOutputs = disabledPlayerVideoOutputs(backend, caps, nativeHdr && !hdrGpuAvailable,
        hdrGpuAvailable && !hdrGpuProbe.openGl)
    val outputs = buildList {
        add("auto" to stringResource(R.string.player_automatic))
        if (mpv) {
            add("direct" to stringResource(R.string.renderer_output_direct))
            add("gpu" to stringResource(R.string.renderer_output_opengl))
        } else add("android" to stringResource(R.string.renderer_output_android))
        if ("vulkan" !in disabledOutputs) add("vulkan" to stringResource(R.string.renderer_output_vulkan))
    }
    val shownOutput = output.takeIf { selected -> outputs.any { it.first == selected } } ?: "auto"
    PlayerDropUpSelector(
        label = stringResource(if (mpv) R.string.vulkan_mpv_output else R.string.vulkan_vlc_output),
        value = outputs.first { it.first == shownOutput }.second,
        options = outputs, selectedKey = shownOutput,
        disabledKeys = disabledOutputs,
        onSelected = { next ->
            if (next !in disabledOutputs) {
                BackgroundPlaybackSession.setVideoSettings(metadata,
                    if (mpv) settings.withMpvOutput(next) else settings.copy(vlcOutput = next))
            }
        },
    )
    if (mpv) {
        val hardware = RuntimeCodecSupport.hasHardwareVideoDecoder
        val disabledDecoders = disabledPlayerVideoDecoders(output, caps, nativeHdr)
        val decoders = mpvDecoderChoices(hardware)
            .filter { it != "vulkan" || it !in disabledDecoders }
            .map { it to mpvDecoderLabel(it) }
        val decoder = effectiveMpvDecoder(settings.mpvDecoder, hardware)
            .takeIf { selected -> decoders.any { it.first == selected } } ?: "auto"
        PlayerDropUpSelector(
            label = stringResource(R.string.vulkan_decoder),
            value = decoders.first { it.first == decoder }.second,
            options = decoders, selectedKey = decoder,
            disabledKeys = disabledDecoders,
            onSelected = { next ->
                if (next !in disabledPlayerVideoDecoders(output, caps, nativeHdr)) {
                    BackgroundPlaybackSession.setVideoSettings(metadata, settings.copy(mpvDecoder = next))
                }
            },
        )
    }
    if (mpv && output != "direct") {
        // Live A/B: re-applying the image options re-sends target-trc and re-tags the surface.
        var curve by remember { mutableStateOf(preferences.mpvSdrCurveKey) }
        val curves = com.ambient.tv.ui.settings.sdrCurveChoices()
        PlayerDropUpSelector(
            label = stringResource(R.string.renderer_sdr_curve),
            value = curves.first { it.first == curve }.second,
            options = curves, selectedKey = curve,
            onSelected = { next ->
                curve = next
                preferences.mpvSdrCurveKey = next
                activeMpv?.let { it.updateImageOptions(it.imageOptions) }
            },
        )
    }
    ImageHint(stringResource(if (metadata.isLive) R.string.player_output_restart_live else R.string.player_output_restart))
    // An explicit GPU output under Sparsam still renders bilinear without shaders or SVP.
    if (sparsamGpuOutputHeadsUp(mpv, output, com.ambient.tv.ui.performance.LocalPerformanceProfile.current.gateExpensiveVideoVisuals)) {
        ImageHint(stringResource(R.string.player_output_sparsam_gpu))
        SparsamOverrideNotice(showText = false)
    }
    if (nativeHdr) ImageHint(stringResource(R.string.player_output_hdr_direct))
    // "Als HDR markiert" once mpv and Android tagged the output PQ; the TV's mode only if Android reports it.
    if (passthroughRunning) ImageHint(stringResource(mpvHdrPassthroughStatusRes(passthroughConfirmed, activeMpv?.hdrDisplaySwitched)))
    hdrGpu.reason?.takeUnless { passthroughRunning }?.let { ImageHint(stringResource(mpvHdrGpuReasonRes(it))) }

}

/** Passthrough status: being marked, marked (display unknown), display in HDR, or display still SDR. */
internal fun mpvHdrPassthroughStatusRes(marked: Boolean, displaySwitched: Boolean?): Int = when {
    !marked -> R.string.player_output_hdr_gpu_checking
    displaySwitched == true -> R.string.player_output_hdr_gpu_display_switched
    displaySwitched == false -> R.string.player_output_hdr_gpu_display_waiting
    // Android does not report the TV's mode here: the TV's own HDR indicator tells.
    else -> R.string.player_output_hdr_gpu_active
}

internal fun sparsamGpuOutputHeadsUp(mpv: Boolean, output: String, reduced: Boolean): Boolean =
    mpv && reduced && output in setOf("vulkan", "gpu")

/** Why "HDR über GPU verarbeiten" left this HDR title on its usual route. */
internal fun mpvHdrGpuReasonRes(reason: com.ambient.tv.ui.player.backend.MpvHdrGpuReason): Int = when (reason) {
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.OUTPUT_DIRECT -> R.string.player_output_hdr_gpu_output_direct
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.DOLBY_VISION -> R.string.player_output_hdr_gpu_dolby_vision
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.NOT_HDR10 -> R.string.player_output_hdr_gpu_not_hdr10
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.SWAPCHAIN_NO_HDR10 -> R.string.player_output_hdr_gpu_swapchain
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.REDUCED_EFFECTS -> R.string.player_output_hdr_gpu_reduced
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.NO_EFFECTS -> R.string.player_output_hdr_gpu_no_effects
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.NOT_CONFIRMED -> R.string.player_output_hdr_gpu_not_confirmed
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.DISPLAY_NOT_SWITCHED -> R.string.player_output_hdr_gpu_display_not_switched
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.VULKAN_FALLBACK -> R.string.player_output_hdr_gpu_vulkan_fallback
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.OPENGL_FALLBACK -> R.string.player_output_hdr_gpu_opengl_fallback
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.OVERLOAD -> R.string.player_output_hdr_gpu_overload
    com.ambient.tv.ui.player.backend.MpvHdrGpuReason.SURFACE_UNVERIFIABLE -> R.string.player_output_hdr_gpu_surface_unverifiable
}

@Composable
internal fun mpvDecoderLabel(key: String): String = stringResource(when (key) {
    "mediacodec" -> R.string.renderer_decoder_mediacodec
    "vulkan" -> R.string.renderer_decoder_vulkan
    "software" -> R.string.vulkan_software
    else -> R.string.vulkan_decoder_auto
})
