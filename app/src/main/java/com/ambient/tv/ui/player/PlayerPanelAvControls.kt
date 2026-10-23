@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.R
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.common.PlayerDropUpSection
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.RightPanelSectionTitle
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

/**
 * Short video group on the playback panel: deinterlace and upscaler only.
 * Hidden unless the running engine applies that control. Reuses the live image
 * and deinterlace paths; does not add a second preference.
 */
@Composable
internal fun PlayerPanelVideoGroup(
    isLive: Boolean,
    onDeinterlaceChanged: ((String, String) -> Unit)? = null,
) {
    val handle = BackgroundPlaybackSession.activeHandle()
    when (handle) {
        is MpvPlaybackController -> MpvPanelVideoGroup(handle, onDeinterlaceChanged)
        is VlcPlaybackController -> VlcPanelVideoGroup(handle, isLive, onDeinterlaceChanged)
        is Media3PlaybackHandle -> Media3PanelVideoGroup(handle.player)
    }
}

@Composable
private fun MpvPanelVideoGroup(
    controller: MpvPlaybackController,
    onDeinterlaceChanged: ((String, String) -> Unit)?,
) {
    // Same inputs as the full video panel, so both show the same rows.
    val visibility = playerPanelVideoVisibility(
        engine = "mpv",
        gpuFilters = controller.supportsGpuFilters,
        shadersApply = controller.sdrImageProcessing,
        media3ScalerApplies = false,
        scale = rememberPlaybackScaleRelation(),
    )
    if (!visibility.deinterlace && !visibility.upscaler) return
    RightPanelSectionTitle(stringResource(R.string.player_video))
    if (visibility.deinterlace) {
        PanelDeinterlaceRows(
            backend = "mpv",
            mode = controller.deinterlaceModeKey,
            filter = controller.deinterlaceFilterKey,
            failed = controller.deinterlaceFailed,
            onMode = { mode ->
                controller.setDeinterlace(mode != "off", controller.deinterlaceFilterKey, mode == "auto")
                onDeinterlaceChanged?.invoke(mode, controller.deinterlaceFilterKey)
            },
            onFilter = { filter ->
                val mode = controller.deinterlaceModeKey
                controller.setDeinterlace(mode != "off", filter, mode == "auto")
                onDeinterlaceChanged?.invoke(mode, filter)
            },
        )
    }
    if (visibility.upscaler) {
        val context = LocalContext.current
        val preferences = androidx.compose.runtime.remember(context) { videoImagePreferences(context) }
        val stored = controller.imageOptions.scaler
        val selected = panelUpscalerKey(stored, VideoImageCatalog.mpvShaders.keys, visibility.shaderUpscalers)
        val sections = buildList {
            add(PlayerDropUpSection(stringResource(R.string.image_scaler_builtin), VideoImageCatalog.mpvBuiltins.toList()))
            if (visibility.shaderUpscalers) {
                add(PlayerDropUpSection(stringResource(R.string.image_scaler_shader), VideoImageCatalog.mpvShaders.toList()))
            }
        }
        val choices = sections.flatMap { it.options }
        com.ambient.tv.ui.common.GroupedPlayerDropUpSelector(
            label = stringResource(R.string.player_upscaler),
            value = choices.firstOrNull { it.first == selected }?.second
                ?: VideoImageCatalog.mpvScalers[selected]
                ?: selected,
            sections = sections,
            selectedKey = selected,
            onSelected = { key ->
                if (key == controller.imageOptions.scaler) return@GroupedPlayerDropUpSelector
                controller.updateImageOptions(controller.imageOptions.copy(scaler = key))
                if (controller.saveImageDefaults) preferences.mpvImageOptions = controller.imageOptions
            },
        )
    }
}

@Composable
private fun VlcPanelVideoGroup(
    controller: VlcPlaybackController,
    isLive: Boolean,
    onDeinterlaceChanged: ((String, String) -> Unit)?,
) {
    // Same inputs as the full video panel (HDR input, swscale bypass, scale), so both show the same rows.
    val visibility = playerPanelVideoVisibility("vlc", gpuFilters = false, shadersApply = false, media3ScalerApplies = false,
        vlcSoftware = controller.imageOptions.softwareProcessing,
        vlcSvp = com.ambient.tv.BuildConfig.VLC_SVP,
        scale = rememberPlaybackScaleRelation(),
        hdrInput = LocalPlaybackMetadata.current?.handheldHdrStandard != null,
        vlcScalerApplies = !com.ambient.tv.ui.player.backend.vlcSurfaceBypassesSwscale(
            controller.usesAndroidSurfaceOutput, controller.imageOptions.softwareProcessing))
    if (!visibility.deinterlace && !visibility.upscaler) return
    RightPanelSectionTitle(stringResource(R.string.player_video))
    if (visibility.deinterlace) PanelDeinterlaceRows(
        backend = "vlc",
        mode = controller.deinterlaceMode,
        filter = controller.deinterlaceFilter,
        failed = false,
        onMode = { mode ->
            controller.setDeinterlace(mode, controller.deinterlaceFilter, preservePosition = !isLive)
            onDeinterlaceChanged?.invoke(mode, controller.deinterlaceFilter)
        },
        onFilter = { filter ->
            controller.setDeinterlace(controller.deinterlaceMode, filter, preservePosition = !isLive)
            onDeinterlaceChanged?.invoke(controller.deinterlaceMode, filter)
        },
    )
    if (visibility.upscaler) {
        val context = LocalContext.current
        val preferences = androidx.compose.runtime.remember(context) { videoImagePreferences(context) }
        val choices = VideoImageCatalog.vlcScalers.toList()
        val selected = controller.imageOptions.scaler
        PlayerDropUpSelector(
            label = stringResource(R.string.player_upscaler),
            value = choices.firstOrNull { it.first == selected }?.second ?: selected,
            options = choices,
            selectedKey = selected,
            onSelected = { key ->
                if (key == controller.imageOptions.scaler) return@PlayerDropUpSelector
                controller.updateImageOptions(controller.imageOptions.copy(scaler = key), preservePosition = !isLive)
                if (controller.saveImageDefaults) preferences.vlcImageOptions = controller.imageOptions
            },
        )
    }
}

@Composable
private fun Media3PanelVideoGroup(player: ExoPlayer) {
    val context = LocalContext.current
    val processing = androidx.compose.runtime.remember(player) { Media3ImageProcessing.get(context, player) }
    val visibility = playerPanelVideoVisibility(
        engine = "media3",
        gpuFilters = false,
        shadersApply = false,
        media3ScalerApplies = processing.gpuEffectsSupported && processing.sdrEffectsAllowed && processing.options.enabled,
        scale = processing.scaleRelation,
    )
    if (!visibility.upscaler) return
    val preferences = androidx.compose.runtime.remember(context) { videoImagePreferences(context) }
    val choices = listOf("bilinear" to "Bilinear", "lanczos" to "Lanczos · 3")
    val selected = processing.options.scaler
    RightPanelSectionTitle(stringResource(R.string.player_video))
    PlayerDropUpSelector(
        label = stringResource(R.string.player_upscaler),
        value = choices.firstOrNull { it.first == selected }?.second ?: selected,
        options = choices,
        selectedKey = selected,
        onSelected = { key ->
            if (key == processing.options.scaler) return@PlayerDropUpSelector
            processing.updateOptions(processing.options.copy(scaler = key))
            if (processing.saveDefaults) preferences.media3ImageOptions = processing.options
        },
    )
}

@Composable
private fun PanelDeinterlaceRows(
    backend: String,
    mode: String,
    filter: String,
    failed: Boolean,
    onMode: (String) -> Unit,
    onFilter: (String) -> Unit,
) {
    val options = listOf(
        "off" to stringResource(R.string.player_off),
        "auto" to stringResource(R.string.tvh_deinterlace_auto),
        "on" to stringResource(R.string.player_on),
    )
    PlayerDropUpSelector(
        label = stringResource(R.string.tvh_deinterlace_state),
        value = options.firstOrNull { it.first == mode }?.second ?: mode,
        options = options,
        selectedKey = mode,
        onSelected = onMode,
    )
    if (mode != "off") {
        DeinterlaceMethodChoice(backend = backend, key = filter, onSelected = onFilter, showHints = false)
    }
    if (failed) ImageHint(stringResource(R.string.deint_failure))
}

/**
 * Short audio group: night mode and bitstream/passthrough only.
 * Night mode is omitted when this engine does not apply it. Bitstream is omitted
 * when the current route cannot carry it, including a phone speaker.
 */
@Composable
internal fun PlayerPanelAudioGroup(session: AudioSession?) {
    session ?: return
    val routeBitstream = routeOffersBitstream(session.route.passthrough, AudioCodecRejects.snapshot())
    val visibility = playerPanelAudioVisibility(
        session.supportsNight,
        // AAC/MP3/FLAC etc. are never bitstreamed; the toggle would do nothing for them.
        routeBitstream && bitstreamToggleApplies(session.sourceCodec),
        dspSuspended = session.outputPlan().let { it.encoded && !it.applyDsp },
    )
    if (!visibility.night && !visibility.bitstream) return
    val options = session.options
    RightPanelSectionTitle(stringResource(R.string.player_audio))
    if (visibility.night) {
        ImageChoice(
            R.string.audio_night,
            if (options.night) "on" else "off",
            imageOnOff(),
        ) { key -> session.update(session.options.copy(night = key == "on")) }
    }
    if (visibility.bitstream) {
        val enabled = options.permitsBitstream(session.sourcePassthrough)
        // Off remembers the output it replaced (source/auto), so On restores it instead of forcing "bitstream".
        var previousOutput by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
        ImageChoice(
            R.string.audio_prefer_bitstream,
            if (enabled) "on" else "off",
            imageOnOff(),
        ) { key ->
            val current = session.options
            if (key != "on" && current.output != "pcm") previousOutput = current.output
            val next = bitstreamToggleOutput(key == "on", current.output, previousOutput) { output ->
                current.copy(output = output, channels = "auto").permitsBitstream(session.sourcePassthrough)
            }
            session.update(current.copy(output = next, channels = if (key == "on") "auto" else current.channels))
        }
    }
}

/** Unknown codecs keep the toggle; known non-passthrough codecs (AAC, MP3, …) hide it. */
internal fun bitstreamToggleApplies(sourceCodec: String?): Boolean =
    sourceCodec == null || normalizeSourceCodec(sourceCodec) != null

/**
 * Output for the bitstream toggle. Off is PCM. On restores the output that Off replaced when it
 * still permits bitstream (for example "source" or "auto"), otherwise asks for bitstream.
 */
internal fun bitstreamToggleOutput(on: Boolean, current: String, previous: String?, permits: (String) -> Boolean): String {
    if (!on) return "pcm"
    if (current != "pcm" && permits(current)) return current
    if (previous != null && previous != "pcm" && permits(previous)) return previous
    return "bitstream"
}
