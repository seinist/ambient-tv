package com.ambient.tv.ui.player

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.data.local.SvpOptions
import com.ambient.tv.ui.common.PlayerPanelAction
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.player.backend.SvpInactiveReason
import com.ambient.tv.ui.player.backend.SvpState
import com.ambient.tv.ui.player.backend.svpRates

/** Preset name for notifications and overviews. Stored "auto" was the light preset. */
internal fun svpModeLabelRes(mode: String): Int = when (mode) {
    "auto", "light" -> R.string.svp_mode_light
    "balanced" -> R.string.svp_mode_balanced
    "quality" -> R.string.svp_mode_smooth
    else -> R.string.player_off
}

/** Key shown by the preset selector: "custom" once analysis values deviate from the preset. */
internal fun svpPresetKey(mode: String, options: SvpOptions): String =
    if (mode == "off") "off" else if (options.isCustom) "custom" else mode

/**
 * Applies a preset choice. A preset clears analysis overrides; "custom" keeps them (and
 * turns SVP on with the balanced base when it was off). Returns the new mode and options.
 */
internal fun svpApplyPresetChoice(choice: String, mode: String, options: SvpOptions): Pair<String, SvpOptions> = when (choice) {
    "off" -> "off" to options
    "custom" -> (if (mode == "off") "balanced" else mode) to options
    else -> choice to options.withoutAnalysisOverrides()
}

/**
 * Availability known from the UI alone: the engine/output and the performance mode.
 * Everything that depends on the playing source comes from the engine's [SvpState].
 */
internal fun svpUiUnavailable(engineSupportsSvp: Boolean, maximumPerformance: Boolean): SvpInactiveReason? = when {
    !engineSupportsSvp -> SvpInactiveReason.ENGINE
    !maximumPerformance -> SvpInactiveReason.PERFORMANCE_MODE
    else -> null
}

@Composable
internal fun svpModeLabel(mode: String, custom: Boolean): String {
    val base = stringResource(svpModeLabelRes(mode))
    return if (mode != "off" && custom) stringResource(R.string.svp_mode_custom_of, base) else base
}

private fun resolutionLabel(maxHeight: Int) = when (maxHeight) { 720 -> "720p"; 2160 -> "4K"; else -> "1080p" }

@Composable
internal fun svpReasonText(reason: SvpInactiveReason, options: SvpOptions): String {
    val detail = when (reason) {
        SvpInactiveReason.ENGINE -> stringResource(R.string.svp_reason_engine)
        SvpInactiveReason.PERFORMANCE_MODE -> stringResource(R.string.svp_reason_performance)
        SvpInactiveReason.LIBRARY -> stringResource(R.string.svp_reason_library)
        SvpInactiveReason.HDR -> stringResource(R.string.svp_reason_hdr)
        SvpInactiveReason.RESOLUTION -> stringResource(R.string.svp_reason_resolution, resolutionLabel(options.normalized().maxHeight))
        SvpInactiveReason.SOURCE_RATE_LIMIT -> stringResource(R.string.svp_reason_source_limit)
        SvpInactiveReason.SOURCE_RATE_UNKNOWN -> stringResource(R.string.svp_reason_source_unknown)
        SvpInactiveReason.DISPLAY_RATE_UNKNOWN -> stringResource(R.string.svp_reason_display_unknown)
        SvpInactiveReason.NOT_NEEDED -> stringResource(R.string.svp_reason_not_needed)
        SvpInactiveReason.FORMAT -> stringResource(R.string.svp_reason_format)
        SvpInactiveReason.TIMING -> stringResource(R.string.svp_reason_timing)
        SvpInactiveReason.PASSTHROUGH -> stringResource(R.string.svp_reason_passthrough)
        SvpInactiveReason.STARTING -> stringResource(R.string.svp_reason_starting)
        SvpInactiveReason.OVERLOAD -> stringResource(R.string.svp_reason_overload)
    }
    return stringResource(if (reason.unavailable) R.string.svp_unavailable else R.string.svp_inactive, detail)
}

/**
 * One line for the entry row and the status: "Ausgewogen · 23.976 → 60 fps",
 * "Nicht verfügbar: HDR", "Inaktiv: Quelle über 30 fps" or "Aus".
 */
@Composable
internal fun svpSummary(mode: String, options: SvpOptions, state: SvpState?, unavailable: SvpInactiveReason?): String {
    if (mode == "off") return stringResource(R.string.player_off)
    val reason = unavailable ?: state?.reason
    if (reason != null) return svpReasonText(reason, options)
    val label = svpModeLabel(mode, options.isCustom)
    return listOfNotNull(label, svpRates(state?.sourceFps, state?.targetFps)).joinToString(" · ")
}

/** Live state, refreshed once per second while shown. */
@Composable
internal fun rememberSvpState(key: Any?, state: (() -> SvpState?)?): SvpState? {
    var live by remember(key) { mutableStateOf(state?.invoke()) }
    val latest by androidx.compose.runtime.rememberUpdatedState(state)
    LaunchedEffect(key) {
        while (true) { live = latest?.invoke(); kotlinx.coroutines.delay(1_000) }
    }
    return live
}

/** "SVP-Bewegungsglättung" row with its live summary; opens the sub-level. */
@Composable
internal fun SvpEntryRow(summary: String, onOpen: () -> Unit) {
    PlayerPanelEntry(icon = Icons.Filled.Animation, title = stringResource(R.string.svp_title), value = summary, onClick = onOpen)
}

@Composable
private fun SvpChoice(label: Int, value: Int, values: List<Pair<Int, String>>, set: (Int) -> Unit) {
    ImageChoice(label, value.toString(), values.map { it.first.toString() to it.second }) { set(it.toInt()) }
}

/**
 * The SVP sub-level: preset, output, limits and the most useful quality settings; analysis
 * details live one level deeper ([SvpAdvancedPage]).
 */
@Composable
internal fun SvpMainPage(
    mode: String,
    options: SvpOptions,
    change: (String, SvpOptions) -> Unit,
    unavailable: SvpInactiveReason?,
    state: (() -> SvpState?)? = null,
    onOpenAdvanced: () -> Unit,
) {
    if (unavailable == SvpInactiveReason.ENGINE) {
        ImageHint(svpReasonText(unavailable, options))
        return
    }
    if (unavailable == SvpInactiveReason.PERFORMANCE_MODE) ImageHint(stringResource(R.string.image_svp_locked))
    val o = options.normalized()
    val context = androidx.compose.ui.platform.LocalContext.current
    val base = stringResource(svpModeLabelRes(if (mode == "off") "balanced" else mode))
    ImageChoice(R.string.svp_mode, svpPresetKey(mode, o), listOf(
        "off" to stringResource(R.string.player_off),
        "light" to stringResource(R.string.svp_mode_light),
        "balanced" to stringResource(R.string.svp_mode_balanced),
        "quality" to stringResource(R.string.svp_mode_smooth),
        "custom" to if (o.isCustom) stringResource(R.string.svp_mode_custom_of, base) else stringResource(R.string.svp_mode_custom),
    )) { choice ->
        val (nextMode, nextOptions) = svpApplyPresetChoice(choice, mode, o)
        // Under playback mode Auto only this explicit pick lets SVP run ([svpModeAllowsInterpolation]).
        videoImagePreferences(context).svpExplicitChoice = nextMode != "off"
        change(nextMode, nextOptions)
        if (choice == "custom") onOpenAdvanced()
    }
    if (mode != "off") {
        SvpChoice(R.string.svp_target, o.rateDivisor, listOf(
            1 to stringResource(R.string.svp_display_rate),
            2 to stringResource(R.string.svp_half_rate),
            3 to stringResource(R.string.svp_max_60),
        )) { change(mode, o.copy(rateDivisor = it)) }
        SvpChoice(R.string.svp_max_resolution, o.maxHeight, listOf(720 to "720p", 1080 to "1080p", 2160 to "4K")) {
            change(mode, o.copy(maxHeight = it))
        }
        ImageChoice(R.string.svp_sources, if (o.filmOnly) "film" else "all", listOf(
            "film" to stringResource(R.string.svp_sources_film),
            "all" to stringResource(R.string.svp_sources_all),
        )) { change(mode, o.copy(filmOnly = it == "film")) }
        SvpChoice(R.string.svp_artifacts, o.artifacts, listOf(
            0 to stringResource(R.string.player_off), 2 to stringResource(R.string.image_medium),
            3 to stringResource(R.string.image_strong),
        )) { change(mode, o.copy(artifacts = it)) }
        SvpChoice(R.string.svp_scene, o.scene, listOf(
            1 to stringResource(R.string.svp_sensitive), 2 to stringResource(R.string.svp_normal),
            3 to stringResource(R.string.svp_relaxed),
        )) { change(mode, o.copy(scene = it)) }
        PlayerPanelEntry(
            icon = Icons.Filled.Tune,
            title = stringResource(R.string.svp_advanced_title),
            value = stringResource(if (o.isCustom) R.string.svp_mode_custom else R.string.svp_preset_value),
            onClick = onOpenAdvanced,
        )
        // A button, not a selector; it stays in place so TV focus remains on it after resetting.
        PlayerPanelAction(Icons.Filled.RestartAlt, stringResource(R.string.svp_reset), onClick = { change(mode, SvpOptions()) })
    }
    if (state != null && mode != "off") {
        val live = rememberSvpState(mode to o, state)
        ImageHint("SVP: " + svpSummary(mode, o, live, unavailable))
    }
}

/** Analysis and device details; "Wie Preset" follows the selected preset. */
@Composable
internal fun SvpAdvancedPage(mode: String, options: SvpOptions, change: (String, SvpOptions) -> Unit) {
    val o = options.normalized()
    // Editing details of an SVP that is off turns it on with the balanced base.
    val activeMode = if (mode == "off") "balanced" else mode
    val preset = 0 to stringResource(R.string.svp_preset_value)
    val automatic = stringResource(R.string.player_automatic)
    SvpChoice(R.string.svp_algorithm, o.algorithm, listOf(preset,
        13 to stringResource(R.string.svp_algo_pair), 21 to stringResource(R.string.svp_algo_cover),
        23 to stringResource(R.string.svp_algo_neighbors))) { change(activeMode, o.copy(algorithm = it)) }
    SvpChoice(R.string.svp_block, o.block, listOf(preset) + listOf(8, 16, 32).map { it to "$it×$it" }) {
        change(activeMode, o.copy(block = it).normalized())
    }
    SvpChoice(R.string.svp_overlap, o.overlap, listOf(preset, 1 to "0 %", 2 to "12,5 %", 3 to "25 %", 4 to "50 %")
        .filter { o.block != 8 || it.first != 2 }) { change(activeMode, o.copy(overlap = it)) }
    SvpChoice(R.string.svp_pel, o.pel, listOf(preset, 1 to "1 px", 2 to "½ px", 4 to "¼ px")) { change(activeMode, o.copy(pel = it)) }
    SvpChoice(R.string.svp_search, o.search, listOf(
        0 to stringResource(R.string.svp_search_standard), 1 to stringResource(R.string.image_light),
        2 to stringResource(R.string.image_medium), 3 to stringResource(R.string.image_strong),
    )) { change(activeMode, o.copy(search = it)) }
    SvpChoice(R.string.svp_backend, o.backend, listOf(0 to automatic, 1 to "CPU", 2 to "Vulkan")) { change(activeMode, o.copy(backend = it)) }
    SvpChoice(R.string.svp_threads, o.threads, listOf(0 to automatic) + (1..8).map { it to it.toString() }) { change(activeMode, o.copy(threads = it)) }
    ImageHint(stringResource(R.string.svp_advanced_hint))
}

/**
 * Settings editors (defaults, live TV profiles): the same two sub-levels as the player panel,
 * as local pages with Back. [unavailable] is usually only the performance mode here.
 */
@Composable
internal fun SvpEditorGroup(mode: String, options: SvpOptions, unavailable: SvpInactiveReason?, change: (String, SvpOptions) -> Unit) {
    var page by rememberSaveable { mutableStateOf("") }
    when (page) {
        "svp", "svp_advanced" -> {
            androidx.activity.compose.BackHandler { page = if (page == "svp_advanced") "svp" else "" }
            PlayerPanelAction(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back),
                onClick = { page = if (page == "svp_advanced") "svp" else "" })
            Text(stringResource(if (page == "svp") R.string.svp_title else R.string.svp_advanced_title),
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.fillMaxWidth())
            if (page == "svp") SvpMainPage(mode, options, change, unavailable) { page = "svp_advanced" }
            else SvpAdvancedPage(mode, options, change)
        }
        else -> SvpEntryRow(svpSummary(mode, options, null, unavailable)) { page = "svp" }
    }
}
