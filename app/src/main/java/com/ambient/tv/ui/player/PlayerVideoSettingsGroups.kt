@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.player

import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.R
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.data.local.VlcImageOptions
import com.ambient.tv.ui.common.GroupedPlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerDropUpSection
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.RightPanelSectionTitle
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.backend.MpvBackendAvailability
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

/**
 * Bumped when the playback panel writes [AppPreferences.preferredDisplayModeKey]
 * so the existing display-mode request recomposes and applies the choice.
 *
 * [sessionFixedFor] keeps a manually chosen resolution for the current playback. Refresh rate still
 * follows the video: double rate (30→60, 50→100, 60→120) and 3:2 pulldown outrank the video's own frame rate.
 */
internal object PlaybackDisplayModeRevision {
    var tick by mutableIntStateOf(0)
        private set

    private var sessionFixedModeKey by mutableStateOf<String?>(null)
    private var sessionPlaybackKey by mutableStateOf<String?>(null)

    fun sessionFixedFor(playbackKey: String): String? =
        sessionFixedModeKey.takeIf { sessionPlaybackKey == playbackKey }

    fun bump(sessionFixedKey: String?, playbackKey: String) {
        sessionFixedModeKey = sessionFixedKey
        sessionPlaybackKey = playbackKey
        tick++
    }

    fun clearSessionFixed() {
        if (sessionFixedModeKey == null && sessionPlaybackKey == null) return
        sessionFixedModeKey = null
        sessionPlaybackKey = null
        tick++
    }

    fun clearSessionFixedFor(playbackKey: String) {
        if (sessionPlaybackKey == playbackKey) clearSessionFixed()
    }
}

/**
 * One video section. Groups appear only for options the current renderer applies.
 * Changing the renderer (engine or MPV/VLC output) recomposes the groups.
 */
@Composable
internal fun PlayerVideoSettingsGroups(
    /** One area (the panel's VIDEO_SECTION level); otherwise the list of areas. */
    sectionView: Boolean = false,
    /** Opens the area chosen in the list; [VideoPanelState.section] already names it. */
    onOpenSection: (() -> Unit)? = null,
    /** Playback speed for the Motion row when it differs from 1×; the speed control lives in the host. */
    motionSummary: String? = null,
    isLive: Boolean,
    rendererKey: String?,
    onRendererSelected: ((String, Boolean) -> Unit)?,
    showRenderer: Boolean,
    onDeinterlaceChanged: ((String, String) -> Unit)?,
    hasPresentationControls: Boolean = false,
    hasPlaybackControls: Boolean = false,
    presentationControls: @Composable () -> Unit = {},
    playbackControls: @Composable () -> Unit = {},
    hasOutputExtras: Boolean = false,
    outputExtras: @Composable () -> Unit = {},
    /** "svp" or "svp_advanced": the SVP sub-levels below Motion, each its own panel level. */
    subPage: String? = null,
    onOpenSubPage: ((String) -> Unit)? = null,
) {
    val handle = BackgroundPlaybackSession.activeHandle()
    val scale = rememberPlaybackScaleRelation()
    val context = LocalContext.current
    val media3Processing = (handle as? Media3PlaybackHandle)?.let { Media3ImageProcessing.get(context, it.player) }
    val visible = when (handle) {
        is MpvPlaybackController -> playerPanelVideoVisibility("mpv", handle.supportsGpuFilters,
            handle.sdrImageProcessing, false, scale = scale, hdrInput = handle.hdrImageTransfer,
            hdrOutput = handle.uiHdrStandard() != null, hdrGpuPassthrough = handle.hdrPassthroughRunning,
            globalSvp = videoImagePreferences(context).svpAvailable)
        is VlcPlaybackController -> playerPanelVideoVisibility("vlc", false, false, false,
            globalSvp = videoImagePreferences(context).svpAvailable,
            vlcSoftware = handle.imageOptions.softwareProcessing,
            vlcSvp = com.ambient.tv.BuildConfig.VLC_SVP, scale = scale,
            hdrInput = LocalPlaybackMetadata.current?.handheldHdrStandard != null,
            vlcScalerApplies = !com.ambient.tv.ui.player.backend.vlcSurfaceBypassesSwscale(
                handle.usesAndroidSurfaceOutput, handle.imageOptions.softwareProcessing))
        is Media3PlaybackHandle -> playerPanelVideoVisibility("media3", false, false,
            media3Processing?.gpuEffectsSupported == true && media3Processing.options.enabled && media3Processing.sdrEffectsAllowed,
            media3SdrAllowed = media3Processing?.gpuEffectsSupported == true && media3Processing.sdrEffectsAllowed,
            scale = media3Processing?.scaleRelation ?: scale)
        else -> null
    }?.sparsam(LocalPerformanceProfile.current.gateExpensiveVideoVisuals, media3 = handle is Media3PlaybackHandle,
        mpv = handle is MpvPlaybackController)
    // The SVP row stays visible with its reason (player, performance mode, HDR ...) while the global switch is on.
    val svpEntryVisible = handle != null && videoImagePreferences(context).svpAvailable
    val hdrFacts = com.ambient.tv.ui.settings.rememberHdrControlFacts(videoImagePreferences(context).uiHdrModeKey)
    val hasSdrToHdrControl = com.ambient.tv.ui.display.sdrToHdrPlaybackControlVisible(hdrFacts)
    val hasOutputControls = rememberAvailableDisplayModes(context).size > 1 ||
        (handle is MpvPlaybackController && handle.supportsGpuFilters) || hasOutputExtras || hasSdrToHdrControl || hdrFacts.displayHdr || videoImagePreferences(context).sdrToHdrEnabled
    val available = buildList {
        if (showRenderer && onRendererSelected != null && rendererKey != null) add("renderer" to R.string.player_renderer)
        if (hasPresentationControls || (handle is MpvPlaybackController && handle.supportsGpuFilters) || visible?.upscaler == true ||
            visible?.media3Processing == true) add("scaling" to R.string.image_scaling)
        if (visible?.deinterlace == true || visible?.deband == true || visible?.shaders == true) add("filters" to R.string.image_filters)
        if (hasPlaybackControls || svpEntryVisible) add("motion" to R.string.image_motion)
        if (visible?.picture == true) add("color" to R.string.image_color)
        if (hasOutputControls) add("output" to R.string.image_output)
    }
    val videoState = com.ambient.tv.ui.common.LocalPlayerPanelNavigation.current?.videoState
        ?: remember { com.ambient.tv.ui.common.VideoPanelState() }
    var section by videoState::section
    val sections = available.map { it.first to stringResource(it.second) }
    val activeSection = subPage
        ?: section.takeIf { selected -> sections.any { it.first == selected } } ?: sections.firstOrNull()?.first ?: return
    if (!sectionView && onOpenSection != null) {
        // The list: one row per area with what is set there; OK opens the area as its own level.
        if (hasSdrToHdrControl) PlayerSdrToHdrControl()
        available.forEach { (key, title) ->
            com.ambient.tv.ui.common.PlayerPanelEntry(
                icon = videoSectionIcon(key),
                title = stringResource(title),
                value = videoSectionSummary(key, handle, rendererKey, media3Processing?.options,
                    upscalerApplies = visible?.upscaler == true || visible?.media3Processing == true ||
                        (handle is MpvPlaybackController && handle.supportsGpuFilters),
                    motionSummary = motionSummary),
                onClick = { section = key; onOpenSection() },
            )
        }
        return
    }
    androidx.compose.runtime.CompositionLocalProvider(com.ambient.tv.ui.common.LocalVideoPanelState provides videoState) {
        if (activeSection == "renderer" && showRenderer && onRendererSelected != null && rendererKey != null) {
            PlayerRendererSelector(rendererKey, onRendererSelected)
            val backend = BackgroundPlaybackSession.activeHandle()?.let { handle ->
                when (handle) {
                    is MpvPlaybackController -> "mpv"
                    is VlcPlaybackController -> "vlc"
                    is Media3PlaybackHandle -> "media3"
                    else -> null
                }
            }
            backend?.let { PlayerVideoOutputControls(it) }
        }
        val openSvpAdvanced = { onOpenSubPage?.invoke("svp_advanced"); Unit }
        when (handle) {
            is MpvPlaybackController -> if (activeSection != "renderer") MpvVideoGroups(handle, onDeinterlaceChanged, activeSection, scale, openSvpAdvanced)
            is VlcPlaybackController -> if (activeSection != "renderer") VlcVideoGroups(handle, isLive, onDeinterlaceChanged, activeSection, scale, openSvpAdvanced)
            is Media3PlaybackHandle -> if (activeSection != "renderer") Media3VideoGroups(handle.player, activeSection)
        }
        // Media3 and other engines: the SVP level only explains why it is not available.
        if (activeSection == "svp" && handle !is MpvPlaybackController && handle !is VlcPlaybackController) {
            SvpMainPage("off", com.ambient.tv.data.local.SvpOptions(), { _, _ -> }, com.ambient.tv.ui.player.backend.SvpInactiveReason.ENGINE) {}
        }
        if (activeSection == "scaling") presentationControls()
        if (activeSection == "motion") {
            // SVP gets its own level; playback speed stays directly under Motion.
            if (svpEntryVisible) SvpPanelEntry(handle) { onOpenSubPage?.invoke("svp") }
            playbackControls()
        }
        if (activeSection == "output" && (handle !is MpvPlaybackController || !handle.supportsGpuFilters)) PlayerDisplayModeGroup()
        if (activeSection == "output") {
            PlayerHdrDiagnostics()
            if (hasSdrToHdrControl) PlayerSdrToHdrControl()
            outputExtras()
        }
    }
}

/** Renderer choice alone; audio-only playback shows nothing else in its video panel. */
@Composable
internal fun PlayerRendererSelector(rendererKey: String, onRendererSelected: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val metadata = LocalPlaybackMetadata.current
    // Its level is titled "Player" (video area and audio-only panel alike).
    PlayerDropUpSelector(
        label = stringResource(R.string.player_renderer_current),
        value = rendererLabel(rendererKey),
        options = rendererOptions(),
        selectedKey = rendererKey,
        onSelected = {
            TitleVideoSettingsStore.rememberRenderer(context, metadata, it)
            onRendererSelected(it, false)
        },
    )
    metadata?.liveRendererOrigin?.let { origin ->
        val reason = when (origin) {
            com.ambient.tv.ui.tvheadend.LiveRendererOrigin.DEFAULT -> R.string.channel_renderer_default
            com.ambient.tv.ui.tvheadend.LiveRendererOrigin.CHANNEL -> R.string.channel_renderer_saved
            com.ambient.tv.ui.tvheadend.LiveRendererOrigin.LIVE_PRESET -> R.string.channel_renderer_live_preset
            com.ambient.tv.ui.tvheadend.LiveRendererOrigin.VIDEO_PRESET -> R.string.channel_renderer_video_preset
            com.ambient.tv.ui.tvheadend.LiveRendererOrigin.MANUAL -> R.string.channel_renderer_manual
            com.ambient.tv.ui.tvheadend.LiveRendererOrigin.RECOVERY -> R.string.channel_renderer_recovery
            com.ambient.tv.ui.tvheadend.LiveRendererOrigin.DOLBY_VISION -> R.string.channel_renderer_dv
            com.ambient.tv.ui.tvheadend.LiveRendererOrigin.UNAVAILABLE -> R.string.channel_renderer_unavailable
        }
        ImageHint(stringResource(reason, rendererLabel(if (rendererKey == "auto") "media3" else rendererKey)))
    }
    if (!MpvBackendAvailability.isAvailable) {
        ImageHint(stringResource(R.string.player_mpv_unavailable_device))
    }
}

@Composable
internal fun rememberPlaybackScaleRelation(): VideoScaleRelation {
    val context = LocalContext.current
    val metadata = LocalPlaybackMetadata.current
    // Read the display revision so a mode change also re-evaluates the physical output size.
    PlaybackDisplayModeRevision.tick
    val activity = context.findActivity()
    val modeId = DisplayRefreshRateMatcher.currentModeId(activity)
    val mode = DisplayRefreshRateMatcher.supportedModes(context).firstOrNull { it.modeId == modeId }
    return videoScaleRelation(metadata?.sourceWidth, metadata?.sourceHeight, mode?.width, mode?.height)
}


@Composable
private fun rendererOptions(): List<Pair<String, String>> = buildList {
    add("auto" to stringResource(R.string.player_automatic))
    add("media3" to "Media3")
    add("vlc" to "VLC")
    if (MpvBackendAvailability.isAvailable) add("mpv" to "MPV")
}

@Composable
internal fun rendererLabel(key: String): String = when (key) {
    "media3" -> "Media3"
    "vlc" -> "VLC"
    "mpv" -> "MPV"
    else -> stringResource(R.string.player_automatic)
}

@Composable
private fun MpvVideoGroups(
    controller: MpvPlaybackController,
    onDeinterlaceChanged: ((String, String) -> Unit)?,
    section: String,
    scale: VideoScaleRelation,
    onOpenSvpAdvanced: () -> Unit = {},
) {
    val visibility = playerPanelVideoVisibility(
        engine = "mpv",
        gpuFilters = controller.supportsGpuFilters,
        shadersApply = controller.sdrImageProcessing,
        media3ScalerApplies = false,
        scale = scale,
        hdrInput = controller.hdrImageTransfer,
        hdrOutput = controller.uiHdrStandard() != null,
        hdrGpuPassthrough = controller.hdrPassthroughRunning,
        globalSvp = videoImagePreferences(LocalContext.current).svpAvailable,
    ).sparsam(LocalPerformanceProfile.current.gateExpensiveVideoVisuals, mpv = true)
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val options = controller.imageOptions
    if (section in setOf("scaling", "filters", "svp", "output")) {
        controller.shieldSoftwareStatus?.let { ImageHint(it) }
    }
    fun change(next: MpvImageOptions) {
        controller.updateImageOptions(next)
        if (controller.saveImageDefaults) preferences.mpvImageOptions = controller.imageOptions
    }
    if (section == "scaling" && controller.supportsGpuFilters) {
        // Sparsam or a low-end device: untouched defaults run cheap (bilinear, no sigmoid); a chosen value applies.
        if (LocalPerformanceProfile.current.mpvCheapDefaults) MpvSparsamLockedRows(options, setOf(MpvSparsamField.UPSCALER, MpvSparsamField.CHROMA,
            MpvSparsamField.DOWNSCALER, MpvSparsamField.SIGMOID))
        MpvScalingRows(options, visibility.shaderUpscalers, visibility.upscaler, visibility.downscaler, ::change,
            hdr = visibility.hdrShaders)
        upscalerNotNeededHint(scale)?.let { ImageHint(stringResource(it)) }
    }
    val showFilters = visibility.deinterlace || visibility.deband || visibility.shaders
    if (section == "filters" && showFilters) {
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
        if (LocalPerformanceProfile.current.mpvCheapDefaults) MpvSparsamLockedRows(options, setOf(MpvSparsamField.DEBAND))
        if (visibility.deband) {
            ImageChoice(R.string.player_deband, options.deband, imageStrengths(true)) { change(options.copy(deband = it)) }
        }
        if (visibility.shaders) {
            if (options.scaler !in setOf("fsr", "nis")) {
                GroupedImageChoice(R.string.image_sharpen, options.sharpening, listOf(
                    PlayerDropUpSection(stringResource(R.string.image_sharpen_builtin), imageStrengths()),
                    PlayerDropUpSection(stringResource(R.string.image_sharpen_shader), listOf("cas" to "AMD CAS", "adaptive" to "Adaptive Sharpen")),
                )) { change(options.copy(sharpening = it)) }
            } else ImageHint(stringResource(if (options.scaler == "nis") R.string.image_nis_integrated_sharpen else R.string.image_fsr_integrated_sharpen))
            // CPU cleanup filters and SSimSuperRes need an SDR source.
            if (visibility.denoise) {
                ImageChoice(R.string.image_denoise, options.denoise, imageStrengths()) { change(options.copy(denoise = it)) }
                ImageChoice(R.string.image_deblock, options.deblock, imageStrengths()) { change(options.copy(deblock = it)) }
            }
            // PQ output (HDR über GPU): grain is not loaded, see mpvImageShaderKeys(pqOutput).
            if (!visibility.hdrPassthrough) {
                ImageChoice(R.string.image_film_grain, options.filmGrain, imageStrengths()) { change(options.copy(filmGrain = it)) }
            }
            if (visibility.hdrShaders) ImageHint(stringResource(
                if (visibility.hdrPassthrough) R.string.image_mpv_hdr_passthrough else R.string.image_mpv_hdr_limited))
            // SSimSuperRes is only loaded while upscaling and never on top of FSR/NIS.
            if (!visibility.hdrShaders && mpvSuperResRowVisible(options.scaler, scale)) {
                ImageChoice(R.string.image_superres, if (options.superResolution) "on" else "off", imageOnOff()) {
                    change(options.copy(superResolution = it == "on"))
                }
            }
        }
    }
    if (section == "svp" || section == "svp_advanced") {
        val changeSvp: (String, com.ambient.tv.data.local.SvpOptions) -> Unit = { mode, svp -> change(options.copy(svpMode = mode, svp = svp)) }
        if (section == "svp" && controller.supportsGpuFilters) SparsamOverrideNotice(forSvp = true)
        if (section == "svp") SvpMainPage(options.svpMode, options.svp, changeSvp,
            svpUiUnavailable(controller.supportsGpuFilters, LocalPerformanceProfile.current.allowSvpInterpolation),
            controller::svpState, onOpenSvpAdvanced)
        else SvpAdvancedPage(options.svpMode, options.svp, changeSvp)
    }
    if (section == "color" && visibility.picture) {
        ImageLevel(R.string.image_brightness, options.brightness) { change(options.copy(brightness = it)) }
        ImageLevel(R.string.image_contrast, options.contrast) { change(options.copy(contrast = it)) }
        ImageLevel(R.string.image_saturation, options.saturation) { change(options.copy(saturation = it)) }
        if (visibility.pictureGamma) {
            ImageLevel(R.string.image_gamma, options.gamma) { change(options.copy(gamma = it)) }
        }
    }
    if (section == "output" && controller.supportsGpuFilters) {
        if (visibility.toneMapping) {
            ImageChoice(R.string.image_tonemap, options.toneMapping, toneMappingChoices()) {
                change(options.copy(toneMapping = it))
            }
        }
        ImageChoice(R.string.image_dither, options.ditherDepth, listOf(
            "auto" to stringResource(R.string.player_automatic),
            "no" to stringResource(R.string.player_off),
            "8" to "8-bit",
            "10" to "10-bit",
        )) { change(options.copy(ditherDepth = it)) }
        PlayerDisplayModeRows()
        if (!controller.sdrImageProcessing && controller.supportsGpuFilters) {
            ImageHint(stringResource(R.string.image_sdr_only))
        }
    }
}

@Composable
private fun MpvScalingRows(
    options: MpvImageOptions,
    shaderUpscalers: Boolean,
    upscaling: Boolean,
    downscaling: Boolean,
    change: (MpvImageOptions) -> Unit,
    /** HDR source on the GPU VO: only HDR-safe upscalers, and no chroma / SSim shaders. */
    hdr: Boolean = false,
) {
    // Chroma shaders and SSimDownscaler stay SDR-only.
    val shadersApply = shaderUpscalers && !hdr
    val hdrUpscalers = com.ambient.tv.ui.player.backend.MPV_HDR_UPSCALERS
    if (upscaling) {
        val stored = options.scaler
        val selected = panelUpscalerKey(stored, VideoImageCatalog.mpvShaders.keys, shadersApply,
            stillRunning = if (hdr && shaderUpscalers) hdrUpscalers else emptySet())
        val sections = buildList {
            add(PlayerDropUpSection(stringResource(R.string.image_scaler_builtin), VideoImageCatalog.mpvBuiltins.toList()))
            if (shaderUpscalers) {
                add(PlayerDropUpSection(
                    stringResource(if (hdr) R.string.image_scaler_shader_hdr else R.string.image_scaler_shader),
                    panelShaderUpscalers(VideoImageCatalog.mpvShaders, hdr, hdrUpscalers).toList(),
                ))
            }
        }
        val choices = sections.flatMap { it.options }
        GroupedPlayerDropUpSelector(
            label = stringResource(R.string.player_upscaler),
            value = choices.firstOrNull { it.first == selected }?.second ?: VideoImageCatalog.mpvScalers[selected] ?: selected,
            sections = sections,
            selectedKey = selected,
            onSelected = { key -> if (key != options.scaler) change(options.copy(scaler = key)) },
        )
    }
    val chroma = VideoImageCatalog.chromaScalers.filterKeys { shadersApply || it !in VideoImageCatalog.chromaShaders }.toList()
    val chromaKey = if (!shadersApply && options.chromaScaler in VideoImageCatalog.chromaShaders) "lanczos" else options.chromaScaler
    ImageChoice(R.string.image_chroma, chromaKey, chroma) { change(options.copy(chromaScaler = it)) }
    if (downscaling) {
        val down = VideoImageCatalog.downscalers.filterKeys { shadersApply || it != "ssim_down" }.toList()
        val downKey = if (!shadersApply && options.downscaler == "ssim_down") "mitchell" else options.downscaler
        ImageChoice(R.string.image_downscaler, downKey, down) { change(options.copy(downscaler = it)) }
    }
    if (upscaling || downscaling) {
        ImageLevelSlider(R.string.image_antiring, options.antiring, range = 0..100, step = 1, valueLabel = "${options.antiring} %") {
            change(options.copy(antiring = it))
        }
        if (upscaling) ImageChoice(R.string.image_sigmoid, if (options.sigmoid) "on" else "off", imageOnOff()) {
            change(options.copy(sigmoid = it == "on"))
        }
    }
}

@Composable
private fun VlcVideoGroups(
    controller: VlcPlaybackController,
    isLive: Boolean,
    onDeinterlaceChanged: ((String, String) -> Unit)?,
    section: String,
    scale: VideoScaleRelation,
    onOpenSvpAdvanced: () -> Unit = {},
) {
    val options = controller.imageOptions
    val visibility = playerPanelVideoVisibility(
        engine = "vlc",
        gpuFilters = false,
        shadersApply = false,
        media3ScalerApplies = false,
        vlcSoftware = options.softwareProcessing,
        vlcSvp = com.ambient.tv.BuildConfig.VLC_SVP,
        scale = scale,
        hdrInput = LocalPlaybackMetadata.current?.handheldHdrStandard != null,
        vlcScalerApplies = !com.ambient.tv.ui.player.backend.vlcSurfaceBypassesSwscale(
            controller.usesAndroidSurfaceOutput, options.softwareProcessing),
        globalSvp = videoImagePreferences(LocalContext.current).svpAvailable,
    ).sparsam(LocalPerformanceProfile.current.gateExpensiveVideoVisuals)
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    fun change(next: VlcImageOptions) {
        controller.updateImageOptions(next, preservePosition = !isLive)
        if (controller.saveImageDefaults) preferences.vlcImageOptions = controller.imageOptions
    }
    if (section == "scaling" && visibility.upscaler) {
        ImageChoice(R.string.player_upscaler, options.scaler, VideoImageCatalog.vlcScalers.toList()) {
            change(options.copy(scaler = it))
        }
        ImageHint(stringResource(R.string.image_vlc_scaler_hint))
    }
    if (section == "filters") {
        PanelDeinterlaceRows(
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
        // Sparsam forces hardware processing; the CPU filter rows would have no effect.
        if (LocalPerformanceProfile.current.gateExpensiveVideoVisuals) {
            ImageHint(stringResource(R.string.image_filters_sparsam_hint))
            SparsamOverrideNotice(showText = false)
        } else ImageChoice(R.string.image_processing, if (options.softwareProcessing) "software" else "hardware", listOf(
            "hardware" to stringResource(R.string.image_vlc_hardware),
            "software" to stringResource(R.string.image_vlc_software),
        )) { change(options.copy(softwareProcessing = it == "software")) }
        if (visibility.denoise) {
            ImageHint(stringResource(R.string.image_vlc_cpu_hint))
            ImageChoice(R.string.player_deband, options.deband, imageStrengths()) { change(options.copy(deband = it)) }
            ImageChoice(R.string.image_denoise, options.denoise, imageStrengths()) { change(options.copy(denoise = it)) }
        }
    }
    if (section == "svp" || section == "svp_advanced") {
        val changeSvp: (String, com.ambient.tv.data.local.SvpOptions) -> Unit = { mode, svp -> change(options.copy(svpMode = mode, svp = svp)) }
        if (section == "svp") SvpMainPage(options.svpMode, options.svp, changeSvp,
            svpUiUnavailable(com.ambient.tv.BuildConfig.VLC_SVP, LocalPerformanceProfile.current.allowSvpInterpolation),
            controller::svpState, onOpenSvpAdvanced)
        else SvpAdvancedPage(options.svpMode, options.svp, changeSvp)
    }
    if (section == "color" && visibility.picture) {
        ImageLevel(R.string.image_brightness, options.brightness) { change(options.copy(brightness = it)) }
        ImageLevel(R.string.image_contrast, options.contrast) { change(options.copy(contrast = it)) }
        ImageLevel(R.string.image_saturation, options.saturation) { change(options.copy(saturation = it)) }
        ImageLevel(R.string.image_gamma, options.gamma) { change(options.copy(gamma = it)) }
    }
    if (section == "filters" && controller.imageProcessingFallback) ImageHint(stringResource(R.string.image_vlc_fallback))
}

/** "SVP-Bewegungsglättung" row under Motion with the live summary of the current engine. */
@Composable
private fun SvpPanelEntry(handle: Any?, onOpen: () -> Unit) {
    val maximum = LocalPerformanceProfile.current.allowSvpInterpolation
    when (handle) {
        is MpvPlaybackController -> {
            val live = rememberSvpState(handle) { handle.svpState() }
            SvpEntryRow(svpSummary(handle.imageOptions.svpMode, handle.imageOptions.svp, live,
                svpUiUnavailable(handle.supportsGpuFilters, maximum)), onOpen)
        }
        is VlcPlaybackController -> {
            val live = rememberSvpState(handle) { handle.svpState() }
            SvpEntryRow(svpSummary(handle.imageOptions.svpMode, handle.imageOptions.svp, live,
                svpUiUnavailable(com.ambient.tv.BuildConfig.VLC_SVP, maximum)), onOpen)
        }
        else -> SvpEntryRow(svpReasonText(com.ambient.tv.ui.player.backend.SvpInactiveReason.ENGINE, com.ambient.tv.data.local.SvpOptions()), onOpen)
    }
}

@Composable
private fun Media3VideoGroups(player: ExoPlayer, section: String) {
    val context = LocalContext.current
    val processing = remember(player) { Media3ImageProcessing.get(context, player) }
    val preferences = remember(context) { videoImagePreferences(context) }
    val options = processing.options
    val visibility = playerPanelVideoVisibility(
        engine = "media3",
        gpuFilters = false,
        shadersApply = false,
        // Shield Android 11 bypasses the GPU effect sink; its controls would do nothing there.
        media3ScalerApplies = processing.gpuEffectsSupported && processing.sdrEffectsAllowed && options.enabled,
        media3SdrAllowed = processing.gpuEffectsSupported && processing.sdrEffectsAllowed,
        scale = processing.scaleRelation,
    ).sparsam(LocalPerformanceProfile.current.gateExpensiveVideoVisuals, media3 = true)
    fun change(next: com.ambient.tv.data.local.Media3ImageOptions) {
        processing.updateOptions(next)
        if (processing.saveDefaults) preferences.media3ImageOptions = processing.options
    }
    if (!visibility.media3Processing && !visibility.upscaler && !visibility.picture) return
    if (section == "scaling" && (visibility.media3Processing || visibility.upscaler)) {
        if (visibility.media3Processing) {
            ImageChoice(R.string.image_processing, if (options.enabled) "on" else "off", imageOnOff()) {
                change(options.copy(enabled = it == "on"))
            }
            ImageHint(stringResource(R.string.image_media3_hint))
        }
        if (visibility.upscaler) {
            ImageChoice(R.string.player_upscaler, options.scaler, listOf("bilinear" to "Bilinear", "lanczos" to "Lanczos · 3")) {
                change(options.copy(scaler = it))
            }
        }
    }
    if (section == "color" && visibility.picture) {
        ImageLevel(R.string.image_brightness, options.brightness) { change(options.copy(brightness = it)) }
        ImageLevel(R.string.image_contrast, options.contrast) { change(options.copy(contrast = it)) }
        ImageLevel(R.string.image_saturation, options.saturation) { change(options.copy(saturation = it)) }
    }
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
    if (mode != "off") DeinterlaceMethodChoice(backend = backend, key = filter, onSelected = onFilter, showHints = false)
    if (failed) ImageHint(stringResource(R.string.deint_failure))
}

/**
 * Resolution and refresh already live in settings as the preferred display mode.
 * The panel writes that same preference. There is no second mode switcher.
 */
@Composable
private fun PlayerDisplayModeGroup() {
    // The area's own level is titled "Output" already.
    PlayerDisplayModeRows(showTitle = false)
}

@Composable
private fun PlayerDisplayModeRows(showTitle: Boolean = false) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val playbackKey = LocalPlaybackMetadata.current?.route
    val modes = rememberAvailableDisplayModes(context)
    if (modes.size <= 1) return
    if (showTitle) RightPanelSectionTitle(stringResource(R.string.image_output))
    val preferences = remember(context) { videoImagePreferences(context) }
    var selected by remember { mutableStateOf(preferences.preferredDisplayModeKey) }
    val options = listOf(
        AppPreferences.DISPLAY_MODE_AUTO_CONTENT to stringResource(R.string.settings_display_mode_auto_content),
        AppPreferences.DISPLAY_MODE_AUTO_DISPLAY to stringResource(R.string.settings_display_mode_auto_display),
        "system" to stringResource(R.string.settings_display_mode_system),
    ) +
        modes.map { it.key to it.label }
    PlayerDropUpSelector(
        label = stringResource(R.string.settings_display_mode_title),
        value = options.firstOrNull { it.first == selected }?.second ?: selected,
        options = options,
        selectedKey = selected,
        onSelected = { key ->
            if (key == selected) return@PlayerDropUpSelector
            selected = key
            preferences.preferredDisplayModeKey = key
            val automatic = key == AppPreferences.DISPLAY_MODE_AUTO_CONTENT || key == AppPreferences.DISPLAY_MODE_AUTO_DISPLAY
            if (automatic && preferences.displayRefreshAdjustmentKey == AppPreferences.DISPLAY_REFRESH_OFF) {
                preferences.displayRefreshAdjustmentKey = AppPreferences.DISPLAY_REFRESH_START_STOP
            }
            // System restores the desktop mode immediately. A fixed resolution is applied by the
            // playback hook, which still matches double rate and 3:2 pulldown at that size.
            if (key == "system") DisplayRefreshRateMatcher.restoreMode(activity, null)
            PlaybackDisplayModeRevision.bump(
                sessionFixedKey = key.takeUnless { automatic },
                playbackKey = playbackKey ?: "panel",
            )
        },
    )
    if (selected == AppPreferences.DISPLAY_MODE_AUTO_CONTENT || selected == AppPreferences.DISPLAY_MODE_AUTO_DISPLAY) {
        ImageHint(stringResource(R.string.settings_display_mode_auto_hint))
    }
    if (displaySwitchPauseVisible(modes.size, preferences.displayRefreshAdjustmentKey, selected)) {
        var pauseMs by remember { mutableStateOf(preferences.displaySwitchPauseMs) }
        // Applies to the next mode request; the running title keeps its current output.
        DisplaySwitchPauseSlider(valueMs = pauseMs, onValueChange = { pauseMs = it; preferences.displaySwitchPauseMs = it })
    }
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
private fun imageStrengths(strong: Boolean = false) = listOf(
    "off" to stringResource(R.string.player_off),
    "light" to stringResource(R.string.image_light),
    "medium" to stringResource(R.string.image_medium),
) + if (strong) listOf("strong" to stringResource(R.string.image_strong)) else emptyList()

/** Why the upscaler row is absent on a GPU VO: the fitted output needs no enlargement. */
internal fun upscalerNotNeededHint(scale: VideoScaleRelation): Int? = when (scale) {
    VideoScaleRelation.NATIVE -> R.string.image_upscaler_not_needed_native
    VideoScaleRelation.DOWNSCALE -> R.string.image_upscaler_not_needed_downscale
    else -> null
}

internal fun mpvSuperResRowVisible(scaler: String, scale: VideoScaleRelation): Boolean =
    scaler !in setOf("fsr", "nis") && scale in setOf(VideoScaleRelation.UNKNOWN, VideoScaleRelation.UPSCALE)

/** Title of a video panel area, for its own level. */
internal fun videoSectionTitle(key: String): Int = when (key) {
    "renderer" -> R.string.player_renderer
    "scaling" -> R.string.image_scaling
    "filters" -> R.string.image_filters
    "motion" -> R.string.image_motion
    "color" -> R.string.image_color
    else -> R.string.image_output
}

private fun videoSectionIcon(key: String): androidx.compose.ui.graphics.vector.ImageVector = when (key) {
    "renderer" -> androidx.compose.material.icons.Icons.Filled.Memory
    "scaling" -> androidx.compose.material.icons.Icons.Filled.AspectRatio
    "filters" -> androidx.compose.material.icons.Icons.Filled.Tune
    "motion" -> androidx.compose.material.icons.Icons.Filled.Animation
    "color" -> androidx.compose.material.icons.Icons.Filled.Palette
    else -> androidx.compose.material.icons.Icons.Filled.Tv
}

/**
 * Settings of one area that differ from the defaults (0 = all default). Scaling is shown by its
 * upscaler instead; Player and Output have no single default.
 */
internal fun videoSectionChanges(key: String, mpv: MpvImageOptions?, vlc: VlcImageOptions?, media3: com.ambient.tv.data.local.Media3ImageOptions?): Int {
    val m = MpvImageOptions(); val v = VlcImageOptions(); val d = com.ambient.tv.data.local.Media3ImageOptions()
    return when (key) {
        "filters" -> mpv?.let { listOf(it.deband != m.deband, it.sharpening != m.sharpening, it.denoise != m.denoise,
            it.deblock != m.deblock, it.filmGrain != m.filmGrain, it.superResolution != m.superResolution).count { c -> c } }
            ?: vlc?.let { listOf(it.softwareProcessing != v.softwareProcessing, it.deband != v.deband, it.denoise != v.denoise).count { c -> c } } ?: 0
        "motion" -> if ((mpv?.svpMode ?: vlc?.svpMode ?: "off") != "off") 1 else 0
        "color" -> mpv?.let { listOf(it.brightness, it.contrast, it.saturation, it.gamma).count { x -> x != 0 } }
            ?: vlc?.let { listOf(it.brightness, it.contrast, it.saturation, it.gamma).count { x -> x != 0 } }
            ?: media3?.let { listOf(it.brightness - d.brightness, it.contrast - d.contrast, it.saturation - d.saturation).count { x -> x != 0 } } ?: 0
        else -> 0
    }
}

@androidx.compose.runtime.Composable
private fun videoSectionSummary(
    key: String, handle: Any?, rendererKey: String?, media3: com.ambient.tv.data.local.Media3ImageOptions?,
    upscalerApplies: Boolean, motionSummary: String?,
): String {
    val mpv = (handle as? MpvPlaybackController)?.imageOptions
    val vlc = (handle as? VlcPlaybackController)?.imageOptions
    return when (key) {
        "renderer" -> rendererKey?.let { rendererLabel(it) }.orEmpty()
        // Without a scaler that applies, the area only holds fit/zoom: no misleading scaler name.
        "scaling" -> when {
            !upscalerApplies -> ""
            mpv != null -> VideoImageCatalog.mpvScalers[mpv.scaler] ?: mpv.scaler
            vlc != null -> VideoImageCatalog.vlcScalers[vlc.scaler] ?: vlc.scaler
            media3 != null -> if (media3.enabled) (if (media3.scaler == "lanczos") "Lanczos · 3" else "Bilinear") else stringResource(R.string.player_off)
            else -> ""
        }
        "output" -> stringResource(R.string.video_section_output_summary)
        "motion" -> listOfNotNull(motionSummary,
            stringResource(R.string.image_motion_svp_on).takeIf { videoSectionChanges(key, mpv, vlc, media3) > 0 })
            .joinToString(" · ").ifEmpty { stringResource(R.string.video_section_default) }
        else -> videoSectionChanges(key, mpv, vlc, media3).let { changed ->
            if (changed == 0) stringResource(R.string.video_section_default)
            else androidx.compose.ui.res.pluralStringResource(R.plurals.video_section_changed, changed, changed)
        }
    }
}
