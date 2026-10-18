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

@Composable
internal fun SvpOptionsEditor(mode: String, options: SvpOptions, changeOptions: (SvpOptions) -> Unit, status: (() -> String?)? = null, change: (String) -> Unit) {
    if (!videoImagePreferences(LocalContext.current).svpAvailable) return
    if (!com.ambient.tv.ui.performance.LocalPerformanceProfile.current.allowSvpInterpolation) {
        ImageHint(stringResource(R.string.image_svp_locked))
        return
    }
    ImageChoice(R.string.image_svp, mode, listOf(
        "off" to stringResource(R.string.player_off),
        "auto" to stringResource(R.string.image_svp_auto),
        "light" to stringResource(R.string.image_light),
        "balanced" to stringResource(R.string.image_svp_balanced),
        "quality" to stringResource(R.string.image_svp_quality),
    ), change)
    if (mode != "off") {
        val o = options.normalized()
        val preset = 0 to stringResource(R.string.svp_preset_value)
        @Composable fun choice(label: Int, value: Int, values: List<Pair<Int, String>>, set: (Int) -> Unit) {
            ImageChoice(label, value.toString(), values.map { it.first.toString() to it.second }) { set(it.toInt()) }
        }
        choice(R.string.svp_target, o.rateDivisor, listOf(
            1 to stringResource(R.string.svp_display_rate), 2 to stringResource(R.string.svp_half_rate),
        )) { changeOptions(o.copy(rateDivisor = it)) }
        choice(R.string.svp_artifacts, o.artifacts, listOf(preset,
            1 to stringResource(R.string.player_off), 2 to stringResource(R.string.image_medium),
            3 to stringResource(R.string.image_strong))) { changeOptions(o.copy(artifacts = it)) }
        choice(R.string.svp_scene, o.scene, listOf(preset,
            1 to stringResource(R.string.svp_sensitive), 2 to stringResource(R.string.svp_normal),
            3 to stringResource(R.string.svp_relaxed))) { changeOptions(o.copy(scene = it)) }
        var advanced by remember { mutableStateOf(false) }
        ImageChoice(R.string.svp_advanced, if (advanced) "on" else "off", imageOnOff()) { advanced = it == "on" }
        if (advanced) {
            choice(R.string.svp_algorithm, o.algorithm, listOf(preset,
                13 to stringResource(R.string.svp_algo_pair), 21 to stringResource(R.string.svp_algo_cover),
                23 to stringResource(R.string.svp_algo_neighbors))) { changeOptions(o.copy(algorithm = it)) }
            choice(R.string.svp_block, o.block, listOf(preset) + listOf(8, 16, 32).map { it to "${it}×$it" }) { changeOptions(o.copy(block = it)) }
            choice(R.string.svp_overlap, o.overlap, listOf(preset, 1 to "0 %", 2 to "12.5 %", 3 to "25 %", 4 to "50 %").filter { o.block != 8 || it.first != 2 }) { changeOptions(o.copy(overlap = it)) }
            choice(R.string.svp_pel, o.pel, listOf(preset, 1 to "1 px", 2 to "½ px", 4 to "¼ px")) { changeOptions(o.copy(pel = it)) }
            choice(R.string.svp_search, o.search, listOf(preset,
                1 to stringResource(R.string.image_light), 2 to stringResource(R.string.image_medium),
                3 to stringResource(R.string.image_strong))) { changeOptions(o.copy(search = it)) }
            choice(R.string.svp_backend, o.backend, listOf(0 to stringResource(R.string.image_svp_auto), 1 to "CPU", 2 to "Vulkan")) { changeOptions(o.copy(backend = it)) }
            choice(R.string.svp_threads, o.threads, listOf(0 to stringResource(R.string.image_svp_auto)) + (1..8).map { it to it.toString() }) { changeOptions(o.copy(threads = it)) }
            ImageHint(stringResource(R.string.svp_advanced_hint))
        }
        // Keep this row present after resetting so TV focus remains on the same control.
        ImageChoice(R.string.svp_reset, "keep", listOf("keep" to stringResource(R.string.svp_keep),
            "reset" to stringResource(R.string.svp_reset))) { if (it == "reset") changeOptions(SvpOptions()) }
    }
    // What SVP does right now (rates, preset, bypass), refreshed while the panel is open.
    if (status != null && mode != "off") {
        var live by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(status()) }
        androidx.compose.runtime.LaunchedEffect(mode) {
            while (true) { live = status(); kotlinx.coroutines.delay(1_000) }
        }
        live?.let { ImageHint("SVP: $it") }
    }
    ImageHint(stringResource(R.string.image_svp_hint))
}

@Composable
internal fun imageOnOff() = listOf("off" to stringResource(R.string.player_off), "on" to stringResource(R.string.player_on))

@Composable
private fun imageStrengths(strong: Boolean = false) = listOf(
    "off" to stringResource(R.string.player_off), "light" to stringResource(R.string.image_light), "medium" to stringResource(R.string.image_medium),
) + if (strong) listOf("strong" to stringResource(R.string.image_strong)) else emptyList()


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
    )
    if (!controller.hdrImageTransfer && !controller.sdrImageProcessing) ImageHint(stringResource(R.string.image_sdr_only))
    if ("nis-compute-unsupported" in controller.imageOptionWarnings) {
        ImageHint(stringResource(R.string.image_nis_compute_unsupported))
    }
    if ("shader-needs-vulkan" in controller.imageOptionWarnings) {
        ImageHint(stringResource(R.string.image_shader_needs_vulkan))
    }
    val rejectedWarnings = controller.imageOptionWarnings.filterNot {
        it == "nis-compute-unknown" || it == "nis-compute-unsupported" || it == "shader-needs-vulkan"
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
) {
    var group by remember { mutableStateOf("scaling") }
    val sparsam = com.ambient.tv.ui.performance.LocalPerformanceProfile.current.gateExpensiveVideoVisuals
    if (hdrTransfer) ImageHint(stringResource(R.string.image_mpv_needs_sdr))
    if (sparsam) ImageHint(stringResource(R.string.image_filters_sparsam_hint))
    // Sparsam: filters and motion interpolation do not run, so their groups are hidden.
    val groups = listOfNotNull(
        "scaling" to stringResource(R.string.image_scaling),
        ("filters" to stringResource(R.string.image_filters)).takeUnless { sparsam },
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
                if (!hdrTransfer) add(PlayerDropUpSection(stringResource(R.string.image_scaler_shader), VideoImageCatalog.mpvShaders.toList()))
            }
            GroupedImageChoice(R.string.player_upscaler, o.scaler, scalerSections) { change(o.copy(scaler = it)) }
            ImageChoice(R.string.image_chroma, o.chromaScaler, VideoImageCatalog.chromaScalers.toList()) { change(o.copy(chromaScaler = it)) }
            ImageChoice(R.string.image_downscaler, o.downscaler, VideoImageCatalog.downscalers.toList()) { change(o.copy(downscaler = it)) }
            ImageLevelSlider(
                R.string.image_antiring, o.antiring,
                range = 0..100, step = 1, valueLabel = "${o.antiring} %",
            ) { change(o.copy(antiring = it)) }
            ImageChoice(R.string.image_sigmoid, if (o.sigmoid) "on" else "off", imageOnOff()) { change(o.copy(sigmoid = it == "on")) }
            ImageHint(stringResource(R.string.image_mpv_shader_hint))
        }
        "filters" -> {
            ImageChoice(R.string.player_deband, o.deband, imageStrengths(true)) { change(o.copy(deband = it)) }
            if (o.scaler !in setOf("fsr", "nis")) {
                GroupedImageChoice(R.string.image_sharpen, o.sharpening, listOf(
                    PlayerDropUpSection(stringResource(R.string.image_sharpen_builtin), imageStrengths()),
                    PlayerDropUpSection(stringResource(R.string.image_sharpen_shader), listOf("cas" to "AMD CAS", "adaptive" to "Adaptive Sharpen")),
                )) { change(o.copy(sharpening = it)) }
            } else ImageHint(stringResource(if (o.scaler == "nis") R.string.image_nis_integrated_sharpen else R.string.image_fsr_integrated_sharpen))
            ImageChoice(R.string.image_denoise, o.denoise, imageStrengths()) { change(o.copy(denoise = it)) }
            ImageChoice(R.string.image_deblock, o.deblock, imageStrengths()) { change(o.copy(deblock = it)) }
            ImageChoice(R.string.image_film_grain, o.filmGrain, imageStrengths()) { change(o.copy(filmGrain = it)) }
            ImageChoice(R.string.image_superres, if (o.superResolution) "on" else "off", imageOnOff()) { change(o.copy(superResolution = it == "on")) }
            ImageHint(stringResource(R.string.image_mpv_filter_hint))
        }
        "color" -> if (!hdrTransfer) {
            ImageLevel(R.string.image_brightness, o.brightness) { change(o.copy(brightness = it)) }
            ImageLevel(R.string.image_contrast, o.contrast) { change(o.copy(contrast = it)) }
            ImageLevel(R.string.image_saturation, o.saturation) { change(o.copy(saturation = it)) }
            ImageLevel(R.string.image_gamma, o.gamma) { change(o.copy(gamma = it)) }
        }
        "motion" -> SvpOptionsEditor(o.svpMode, o.svp, { change(o.copy(svp = it)) }) { change(o.copy(svpMode = it)) }
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
        Text(stringResource(R.string.image_motion), style = MaterialTheme.typography.titleSmall)
        SvpOptionsEditor(o.svpMode, o.svp, { change(o.copy(svp = it)) }) { change(o.copy(svpMode = it)) }
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
        ImageChoice(R.string.player_upscaler, o.scaler, listOf("bilinear" to "Bilinear", "lanczos" to "Lanczos · 3")) { change(o.copy(scaler = it)) }
        ImageLevel(R.string.image_brightness, o.brightness) { change(o.copy(brightness = it)) }
        ImageLevel(R.string.image_contrast, o.contrast) { change(o.copy(contrast = it)) }
        ImageLevel(R.string.image_saturation, o.saturation) { change(o.copy(saturation = it)) }
    }
}

/** Shown after the controller paused playback because loaded shaders/filters drop frames. */
@Composable
internal fun MpvRenderOverloadDialog(controller: MpvPlaybackController, onAdjust: (() -> Unit)? = null) {
    var dismissedStall by remember(controller) { mutableStateOf(false) }
    LaunchedEffect(controller.backendUnresponsive) {
        if (!controller.backendUnresponsive) dismissedStall = false
    }
    if (controller.backendUnresponsive && !dismissedStall) {
        com.ambient.tv.ui.common.AppDialog(
            onDismiss = { dismissedStall = true },
            title = stringResource(R.string.mpv_unresponsive_title),
            hint = stringResource(R.string.mpv_unresponsive_body),
            footer = {
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.mpv_unresponsive_wait), {
                    dismissedStall = true
                })
                com.ambient.tv.ui.common.TvActionButton(stringResource(R.string.player_stop), {
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
internal fun VlcRenderOverloadDialog(controller: VlcPlaybackController, onAdjust: (() -> Unit)? = null) {
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
