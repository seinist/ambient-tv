package com.ambient.tv.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.*
import com.ambient.tv.ui.common.GroupedPlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerDropUpSection
import com.ambient.tv.ui.common.PlayerValueSlider
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.formatSignedIntLabel
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

@Composable
internal fun ImageChoice(label: Int, key: String, choices: List<Pair<String, String>>, changed: (String) -> Unit) {
    ImageChoice(label, key, choices, enabled = true, changed = changed)
}

@Composable
internal fun ImageChoice(
    label: Int,
    key: String,
    choices: List<Pair<String, String>>,
    enabled: Boolean,
    changed: (String) -> Unit,
) {
    PlayerDropUpSelector(
        stringResource(label),
        choices.firstOrNull { it.first == key }?.second ?: key,
        choices,
        key,
        disabledKeys = if (enabled) emptySet() else choices.map { it.first }.toSet(),
        onSelected = changed,
    )
}

@Composable
private fun GroupedImageChoice(
    label: Int,
    key: String,
    sections: List<PlayerDropUpSection>,
    changed: (String) -> Unit,
) {
    val choices = sections.flatMap { it.options }
    GroupedPlayerDropUpSelector(
        label = stringResource(label),
        value = choices.firstOrNull { it.first == key }?.second ?: key,
        sections = sections,
        selectedKey = key,
        onSelected = changed,
    )
}

@Composable
internal fun ImageHint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
internal fun HdrMedia3Hint() = ImageHint(stringResource(R.string.hdr_plays_through_media3))

/** SVP group of the settings editors; hidden while the global SVP switch is off. */
@Composable
internal fun SvpSettingsGroup(mode: String, options: SvpOptions, change: (String, SvpOptions) -> Unit) {
    if (!videoImagePreferences(LocalContext.current).svpAvailable) return
    val maximum = com.ambient.tv.ui.performance.LocalPerformanceProfile.current.allowSvpInterpolation
    SvpEditorGroup(mode, options, svpUiUnavailable(engineSupportsSvp = true, maximumPerformance = maximum), change)
}

@Composable
internal fun imageOnOff() = listOf("off" to stringResource(R.string.player_off), "on" to stringResource(R.string.player_on))

@Composable
private fun imageStrengths() = listOf(
    "off" to stringResource(R.string.player_off), "light" to stringResource(R.string.image_light), "medium" to stringResource(R.string.image_medium),
    "strong" to stringResource(R.string.image_strong),
)


@Composable
internal fun ImageLevel(label: Int, value: Int, changed: (Int) -> Unit) {
    ImageLevelSlider(label, value, changed = changed)
}

@Composable
internal fun ImageLevelSlider(
    label: Int,
    value: Int,
    range: IntRange = -20..20,
    step: Int = 1,
    valueLabel: String = formatSignedIntLabel(value),
    changed: (Int) -> Unit,
) {
    PlayerValueSlider(
        label = stringResource(label),
        value = value,
        valueRange = range,
        step = step,
        valueLabel = valueLabel,
        onValueChange = changed,
    )
}

@Composable
internal fun MpvImageControls(controller: MpvPlaybackController) {
    if (!controller.supportsGpuFilters) {
        return
    }
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val o = controller.imageOptions
    fun change(next: MpvImageOptions) {
        controller.updateImageOptions(next)
        if (controller.saveImageDefaults) preferences.mpvImageOptions = controller.imageOptions
    }
    MpvImageOptionsEditor(
        o,
        ::change,
        hdrTransfer = controller.hdrImageTransfer,
        hdrOutput = controller.hdrPassthroughRunning,
        onCheapFieldChosen = { markMpvImageExplicitChoice(preferences) },
    )
    if (!controller.hdrImageTransfer && !controller.sdrImageProcessing) ImageHint(stringResource(R.string.image_sdr_only))
    if ("nis-compute-unsupported" in controller.imageOptionWarnings) {
        ImageHint(stringResource(R.string.image_nis_compute_unsupported))
    }
    if ("shader-needs-vulkan" in controller.imageOptionWarnings) {
        ImageHint(stringResource(R.string.image_shader_needs_vulkan))
    }
    if (com.ambient.tv.ui.player.backend.MPV_SHIELD_SOFTWARE_WARNING in controller.imageOptionWarnings) {
        ImageHint(stringResource(R.string.mpv_shield_broadcast_software))
    }
    val rejectedWarnings = controller.imageOptionWarnings.filterNot {
        it == "nis-compute-unknown" || it == "nis-compute-unsupported" || it == "shader-needs-vulkan" ||
            it == com.ambient.tv.ui.player.backend.MPV_SHIELD_SOFTWARE_WARNING
    }
    if (rejectedWarnings.isNotEmpty()) ImageHint(stringResource(R.string.image_rejected, rejectedWarnings.joinToString()))
}


@Composable
internal fun VlcImageControls(controller: VlcPlaybackController, isLive: Boolean = false) {
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val o = controller.imageOptions
    fun change(next: VlcImageOptions) {
        controller.updateImageOptions(next, preservePosition = !isLive)
        if (controller.saveImageDefaults) preferences.vlcImageOptions = controller.imageOptions
    }
    VlcImageOptionsEditor(
        o,
        ::change,
        surfaceBypassesScaler = com.ambient.tv.ui.player.backend.vlcSurfaceBypassesSwscale(
            controller.usesAndroidSurfaceOutput,
            o.softwareProcessing,
        ),
    )
    if (controller.imageProcessingFallback) ImageHint(stringResource(R.string.image_vlc_fallback))
}

@Composable
internal fun MpvImageOptionsEditor(
    o: MpvImageOptions,
    change: (MpvImageOptions) -> Unit,
    toneMappingEnabled: Boolean = true,
    hdrTransfer: Boolean = false,
    /** HDR source shown as PQ (HDR über GPU): no film grain. */
    hdrOutput: Boolean = false,
    /** A scaler/sigmoid/deband value was picked (also the default one): see [markMpvImageExplicitChoice]. */
    onCheapFieldChosen: () -> Unit = {},
) {
    var group by remember { mutableStateOf("scaling") }
    val sparsam = com.ambient.tv.ui.performance.LocalPerformanceProfile.current.gateExpensiveVideoVisuals
    val passthrough = hdrTransfer && hdrOutput
    if (hdrTransfer) ImageHint(stringResource(if (passthrough) R.string.image_mpv_hdr_passthrough else R.string.image_mpv_hdr_limited))
    if (mpvCheapDefaultsActive()) ImageHint(stringResource(R.string.image_mpv_sparsam_defaults_hint))
    // Sparsam: chosen filters apply, untouched defaults run cheap; only motion interpolation is off.
    val groups = listOfNotNull(
        "scaling" to stringResource(R.string.image_scaling),
        "filters" to stringResource(R.string.image_filters),
        "color" to stringResource(R.string.image_color),
        ("motion" to stringResource(R.string.image_motion)).takeUnless { sparsam },
        "output" to stringResource(R.string.image_output),
    )
    if (groups.none { it.first == group }) group = "scaling"
    ImageChoice(R.string.image_category, group, groups) { group = it }
    when (group) {
        "scaling" -> {
            val scalerSections = buildList {
                add(PlayerDropUpSection(stringResource(R.string.image_scaler_builtin), VideoImageCatalog.mpvBuiltins.toList()))
                add(PlayerDropUpSection(
                    stringResource(if (hdrTransfer) R.string.image_scaler_shader_hdr else R.string.image_scaler_shader),
                    panelShaderUpscalers(VideoImageCatalog.mpvShaders, hdrTransfer,
                        com.ambient.tv.ui.player.backend.MPV_HDR_UPSCALERS).toList(),
                ))
            }
            GroupedImageChoice(R.string.player_upscaler, o.scaler, scalerSections) { onCheapFieldChosen(); change(o.copy(scaler = it)) }
            // HDR: chroma shaders and SSimDownscaler never load, so only the built-in scalers are offered.
            val chroma = mpvEditorChromaScalers(hdrTransfer)
            val downscalers = mpvEditorDownscalers(hdrTransfer)
            ImageChoice(R.string.image_chroma, o.chromaScaler.takeIf { c -> chroma.any { it.first == c } } ?: "lanczos", chroma) { onCheapFieldChosen(); change(o.copy(chromaScaler = it)) }
            ImageChoice(R.string.image_downscaler, o.downscaler.takeIf { d -> downscalers.any { it.first == d } } ?: "lanczos", downscalers) { onCheapFieldChosen(); change(o.copy(downscaler = it)) }
            ImageLevelSlider(
                R.string.image_antiring, o.antiring,
                range = 0..100, step = 1, valueLabel = "${o.antiring} %",
            ) { change(o.copy(antiring = it)) }
            ImageChoice(R.string.image_sigmoid, if (o.sigmoid) "on" else "off", imageOnOff()) { onCheapFieldChosen(); change(o.copy(sigmoid = it == "on")) }
            ImageHint(stringResource(if (hdrTransfer) R.string.image_mpv_hdr_shader_hint else R.string.image_mpv_shader_hint))
        }
        "filters" -> {
            ImageChoice(R.string.player_deband, o.deband, imageStrengths()) { onCheapFieldChosen(); change(o.copy(deband = it)) }
            if (o.scaler !in setOf("fsr", "nis")) {
                GroupedImageChoice(R.string.image_sharpen, o.sharpening, listOf(
                    PlayerDropUpSection(stringResource(R.string.image_sharpen_builtin), imageStrengths()),
                    PlayerDropUpSection(stringResource(R.string.image_sharpen_shader), listOf("cas" to "AMD CAS", "adaptive" to "Adaptive Sharpen")),
                )) { change(o.copy(sharpening = it)) }
            } else ImageHint(stringResource(if (o.scaler == "nis") R.string.image_nis_integrated_sharpen else R.string.image_fsr_integrated_sharpen))
            // CPU cleanup filters and SSimSuperRes need an SDR source.
            if (!hdrTransfer) {
                ImageChoice(R.string.image_denoise, o.denoise, imageStrengths()) { change(o.copy(denoise = it)) }
                ImageChoice(R.string.image_deblock, o.deblock, imageStrengths()) { change(o.copy(deblock = it)) }
            }
            if (!passthrough) ImageChoice(R.string.image_film_grain, o.filmGrain, imageStrengths()) { change(o.copy(filmGrain = it)) }
            if (!hdrTransfer) ImageChoice(R.string.image_superres, if (o.superResolution) "on" else "off", imageOnOff()) { change(o.copy(superResolution = it == "on")) }
            ImageHint(stringResource(R.string.image_mpv_filter_hint))
        }
        "color" -> if (!hdrTransfer) {
            ImageLevel(R.string.image_brightness, o.brightness) { change(o.copy(brightness = it)) }
            ImageLevel(R.string.image_contrast, o.contrast) { change(o.copy(contrast = it)) }
            ImageLevel(R.string.image_saturation, o.saturation) { change(o.copy(saturation = it)) }
            ImageLevel(R.string.image_gamma, o.gamma) { change(o.copy(gamma = it)) }
        }
        "motion" -> SvpSettingsGroup(o.svpMode, o.svp) { mode, svp -> change(o.copy(svpMode = mode, svp = svp)) }
        "output" -> {
            ImageChoice(
                R.string.image_tonemap,
                o.toneMapping,
                toneMappingChoices(),
                changed = { change(o.copy(toneMapping = it)) },
                enabled = toneMappingEnabled,
            )
            ImageChoice(R.string.image_dither, o.ditherDepth, listOf("auto" to stringResource(R.string.player_automatic), "no" to stringResource(R.string.player_off), "8" to "8-bit", "10" to "10-bit")) { change(o.copy(ditherDepth = it)) }
            ImageHint(stringResource(R.string.image_tonemap_hint))
        }
    }
}

/** Chroma choices for the MPV editor; an HDR source drops the chroma shaders (SDR-only). */
internal fun mpvEditorChromaScalers(hdr: Boolean): List<Pair<String, String>> =
    VideoImageCatalog.chromaScalers.filterKeys { !hdr || it !in VideoImageCatalog.chromaShaders }.toList()

/** Downscaler choices for the MPV editor; an HDR source drops SSimDownscaler (SDR-only). */
internal fun mpvEditorDownscalers(hdr: Boolean): List<Pair<String, String>> =
    VideoImageCatalog.downscalers.filterKeys { !hdr || it != "ssim_down" }.toList()

@Composable
internal fun VlcImageOptionsEditor(
    o: VlcImageOptions,
    change: (VlcImageOptions) -> Unit,
    surfaceBypassesScaler: Boolean = false,
) {
    val sparsam = com.ambient.tv.ui.performance.LocalPerformanceProfile.current.gateExpensiveVideoVisuals
    if (sparsam) ImageHint(stringResource(R.string.image_filters_sparsam_hint))
    // VLC SVP only exists in builds with VLC_SVP; elsewhere the choice would do nothing.
    if (!sparsam && com.ambient.tv.BuildConfig.VLC_SVP && videoImagePreferences(LocalContext.current).svpAvailable) {
        // VLC's own "Motion" group, with the same SVP sub-levels as MPV.
        Text(stringResource(R.string.image_motion), style = MaterialTheme.typography.titleSmall)
        SvpSettingsGroup(o.svpMode, o.svp) { mode, svp -> change(o.copy(svpMode = mode, svp = svp)) }
    }
    if (!sparsam) ImageChoice(R.string.image_processing, if (o.softwareProcessing) "software" else "hardware", listOf(
        "hardware" to stringResource(R.string.image_vlc_hardware), "software" to stringResource(R.string.image_vlc_software),
    )) { change(o.copy(softwareProcessing = it == "software")) }
    if (surfaceBypassesScaler) {
        ImageHint(stringResource(R.string.image_vlc_scaler_bypassed))
    } else {
        ImageChoice(R.string.player_upscaler, o.scaler, VideoImageCatalog.vlcScalers.toList()) { change(o.copy(scaler = it)) }
        ImageHint(stringResource(R.string.image_vlc_scaler_hint))
    }
    if (o.softwareProcessing && !sparsam) {
        ImageHint(stringResource(R.string.image_vlc_cpu_hint))
        var group by remember { mutableStateOf("filters") }
        ImageChoice(R.string.image_category, group, listOf("filters" to stringResource(R.string.image_filters), "color" to stringResource(R.string.image_color))) { group = it }
        if (group == "filters") {
            ImageChoice(R.string.player_deband, o.deband, imageStrengths()) { change(o.copy(deband = it)) }
            ImageChoice(R.string.image_denoise, o.denoise, imageStrengths()) { change(o.copy(denoise = it)) }
        } else {
            ImageLevel(R.string.image_brightness, o.brightness) { change(o.copy(brightness = it)) }
            ImageLevel(R.string.image_contrast, o.contrast) { change(o.copy(contrast = it)) }
            ImageLevel(R.string.image_saturation, o.saturation) { change(o.copy(saturation = it)) }
            ImageLevel(R.string.image_gamma, o.gamma) { change(o.copy(gamma = it)) }
        }
    }
}

@Composable
internal fun Media3ImageOptionsEditor(o: Media3ImageOptions, change: (Media3ImageOptions) -> Unit) {
    if (!media3GpuEffectsSupported(android.os.Build.MANUFACTURER, android.os.Build.VERSION.SDK_INT)) {
        ImageHint(stringResource(R.string.image_media3_device_bypass))
        return
    }
    if (com.ambient.tv.ui.performance.LocalPerformanceProfile.current.gateExpensiveVideoVisuals) {
        ImageHint(stringResource(R.string.image_filters_sparsam_hint))
        return
    }
    ImageChoice(R.string.image_processing, if (o.enabled) "on" else "off", imageOnOff()) { change(o.copy(enabled = it == "on")) }
    ImageHint(stringResource(R.string.image_media3_hint))
    if (o.enabled) {
        ImageChoice(R.string.player_upscaler, o.scaler, media3ScalerChoices()) { change(o.copy(scaler = it)) }
        // Saved defaults: the source size is not known here, so FSR counts as upscaling.
        Media3FilterRows(o, fsrSharpens = media3FsrSharpens(o, upscalerApplies = true), change = change)
        ImageLevel(R.string.image_brightness, o.brightness) { change(o.copy(brightness = it)) }
        ImageLevel(R.string.image_contrast, o.contrast) { change(o.copy(contrast = it)) }
        ImageLevel(R.string.image_saturation, o.saturation) { change(o.copy(saturation = it)) }
        ImageLevel(R.string.image_gamma, o.gamma) { change(o.copy(gamma = it)) }
    }
}

/**
 * Media3 deband and sharpening, with MPV's labels. FSR brings RCAS, so its sharpening row is
 * replaced by the same hint MPV shows instead of stacking a second sharpening pass.
 */
@Composable
internal fun Media3FilterRows(
    o: Media3ImageOptions,
    fsrSharpens: Boolean,
    deband: Boolean = true,
    sharpen: Boolean = true,
    change: (Media3ImageOptions) -> Unit,
) {
    // Denoise and deband both work on source pixels and share their budget.
    if (deband) {
        ImageChoice(R.string.image_denoise, o.denoise, imageStrengths()) { change(o.copy(denoise = it)) }
        ImageChoice(R.string.player_deband, o.deband, imageStrengths()) { change(o.copy(deband = it)) }
    }
    if (sharpen) {
        if (fsrSharpens) ImageHint(stringResource(R.string.image_fsr_integrated_sharpen))
        else GroupedImageChoice(R.string.image_sharpen, o.sharpening, listOf(
            PlayerDropUpSection(stringResource(R.string.image_sharpen_builtin), imageStrengths()),
            PlayerDropUpSection(stringResource(R.string.image_sharpen_shader), listOf("cas" to "AMD CAS", "adaptive" to "Adaptive Sharpen")),
        )) { change(o.copy(sharpening = it)) }
        // Grain shares the sharpening pass's size and budget.
        ImageChoice(R.string.image_film_grain, o.filmGrain, imageStrengths()) { change(o.copy(filmGrain = it)) }
    }
    ImageHint(stringResource(R.string.image_media3_filter_hint))
}

/** Shown after the controller paused playback because loaded shaders/filters drop frames. */
@Composable
internal fun MpvRenderOverloadDialog(
    controller: MpvPlaybackController,
    panelNavigation: com.ambient.tv.ui.common.PlayerPanelNavigation? = null,
    onAdjust: (() -> Unit)? = null,
) {
    var dismissedStall by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller.backendUnresponsive) {
        if (!controller.backendUnresponsive) dismissedStall = false
    }
    PreviousPlayerClosingHint(controller.waitingForPreviousPlayer)
    val stallShown = controller.backendUnresponsive && !dismissedStall
    // "Warten", Back or the end of the stall puts focus back on the panel row used before.
    val focusReturn = com.ambient.tv.ui.common.rememberDialogFocusReturn(
        panelNavigation ?: com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current,
        stallShown || controller.renderOverloaded)
    if (stallShown) {
        com.ambient.tv.ui.common.AppDialog(
            onDismiss = { dismissedStall = true },
            title = stringResource(R.string.mpv_unresponsive_title),
            hint = stringResource(R.string.mpv_unresponsive_body),
            footer = {
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.mpv_unresponsive_wait), {
                    dismissedStall = true
                })
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.player_stop), {
                    focusReturn.forget()
                    dismissedStall = true
                    if (BackgroundPlaybackSession.owns(controller)) BackgroundPlaybackSession.stop()
                    else controller.release()
                })
            },
        )
        return
    }
    if (!controller.renderOverloaded) return
    val context = LocalContext.current
    val metadata = LocalPlaybackMetadata.current
    // Back/outside keeps the current settings, like "keep watching anyway".
    fun resume() {
        controller.continueDespiteOverload()
        TitleVideoSettingsStore.rememberOverloadContinue(
            context, metadata, "mpv", controller.overloadContinueSignature,
        )
    }
    com.ambient.tv.ui.common.AppDialog(
        onDismiss = ::resume,
        title = stringResource(R.string.player_render_overload_title),
        hint = stringResource(R.string.player_render_overload_body) + "\n\n" +
            stringResource(R.string.player_frame_drop_sample_percent, formatFramePercent(controller.overloadDropPercent)),
        footer = {
            onAdjust?.let { adjust ->
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.player_render_overload_adjust), {
                    controller.dismissRenderOverload(); adjust()
                })
            }
            if (controller.canReduceAfterOverload) {
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.player_render_overload_reduce), controller::reduceAfterOverload)
            }
            com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.player_render_overload_continue), ::resume, selected = true)
        },
    )
}

/** VLC's equivalent guard uses LibVLC's lost-picture counter after a media reopen. */
@Composable
internal fun VlcRenderOverloadDialog(
    controller: VlcPlaybackController,
    panelNavigation: com.ambient.tv.ui.common.PlayerPanelNavigation? = null,
    onAdjust: (() -> Unit)? = null,
) {
    PreviousPlayerClosingHint(controller.waitingForPreviousPlayer)
    com.ambient.tv.ui.common.rememberDialogFocusReturn(
        panelNavigation ?: com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current, controller.renderOverloaded)
    if (!controller.renderOverloaded) return
    val context = LocalContext.current
    val metadata = LocalPlaybackMetadata.current
    fun resume() {
        controller.continueDespiteOverload()
        TitleVideoSettingsStore.rememberOverloadContinue(
            context, metadata, "vlc", controller.overloadContinueSignature,
        )
    }
    com.ambient.tv.ui.common.AppDialog(
        onDismiss = ::resume,
        title = stringResource(R.string.player_render_overload_title),
        hint = stringResource(R.string.player_render_overload_body) + "\n\n" +
            stringResource(R.string.player_frame_drop_sample_percent, formatFramePercent(controller.overloadDropPercent)),
        footer = {
            onAdjust?.let { adjust ->
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.player_render_overload_adjust), {
                    controller.dismissRenderOverload(); adjust()
                })
            }
            com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.player_render_overload_continue), ::resume)
        },
    )
}

/** Readable names for mpv's tone-mapping curves instead of the raw option keys. */
@Composable
internal fun toneMappingChoices(): List<Pair<String, String>> = VideoImageCatalog.toneMapping.map { key ->
    key to when (key) {
        "auto" -> stringResource(R.string.image_tonemap_auto)
        "bt.2390" -> stringResource(R.string.image_tonemap_bt_2390)
        "reinhard" -> stringResource(R.string.image_tonemap_reinhard)
        "hable" -> stringResource(R.string.image_tonemap_hable)
        "mobius" -> stringResource(R.string.image_tonemap_mobius)
        "clip" -> stringResource(R.string.image_tonemap_clip)
        else -> key
    }
}
