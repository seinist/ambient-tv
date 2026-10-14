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
    val settings = BackgroundPlaybackSession.videoSettings(metadata, PlayerVideoSettings.from(preferences)
        .withResolution(ResolutionVideoProfileStore.load(context, metadata))
        .withTitle(TitleVideoSettingsStore.load(context, metadata)))
    val caps by VideoAccelerationRuntime.capabilities.collectAsState()
    LaunchedEffect(Unit) { VideoAccelerationRuntime.refresh(context) }
    val mpv = backend == "mpv"
    val runtimeHdr = (BackgroundPlaybackSession.activeHandle() as? com.ambient.tv.ui.player.backend.MpvPlaybackController)?.sourceHdrStandard
    val nativeHdr = nativeHdrSurfacePreferred(metadata.handheldHdrStandard ?: runtimeHdr, HdrSupport.displayStandards(context))
    val output = if (mpv) settings.mpvOutput else settings.vlcOutput
    val disabledOutputs = disabledPlayerVideoOutputs(backend, caps, nativeHdr)
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
            if (next !in disabledPlayerVideoOutputs(backend, caps, nativeHdr)) {
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
    ImageHint(stringResource(if (metadata.isLive) R.string.player_output_restart_live else R.string.player_output_restart))
    if (nativeHdr) ImageHint(stringResource(R.string.player_output_hdr_direct))

}

@Composable
internal fun mpvDecoderLabel(key: String): String = stringResource(when (key) {
    "mediacodec" -> R.string.renderer_decoder_mediacodec
    "vulkan" -> R.string.renderer_decoder_vulkan
    "software" -> R.string.vulkan_software
    else -> R.string.vulkan_decoder_auto
})
