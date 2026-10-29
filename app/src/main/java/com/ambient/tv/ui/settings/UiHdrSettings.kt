package com.ambient.tv.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvSettingRow
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import com.ambient.tv.ui.display.LocalUiHdrState
import com.ambient.tv.ui.display.SdrToHdrPlayerKind
import com.ambient.tv.ui.display.SdrToHdrStatus
import com.ambient.tv.ui.display.SdrToHdrSwitchFacts
import com.ambient.tv.ui.display.hdrInterfaceChoiceVisible
import com.ambient.tv.ui.display.sdrToHdrSwitchEnabled
import com.ambient.tv.ui.display.sdrToHdrSwitchVisible
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.player.BackgroundPlaybackSession

// Preserve existing positional calls with (mode, onModeChanged).
@Composable
internal fun UiHdrSettings(mode: String, onModeChanged: (String) -> Unit) {
    UiHdrSettings(mode = mode, onModeChanged = onModeChanged, sdrToHdrEnabled = false)
}

@Composable
internal fun UiHdrSettings(
    mode: String,
    sdrToHdrEnabled: Boolean = false,
    onSdrToHdrChanged: (Boolean) -> Unit = {},
    sdrToHdrPeakNits: Int = 0,
    onSdrToHdrPeakNitsChanged: (Int) -> Unit = {},
    sdrToHdrReferenceWhiteNits: Int = 0,
    onSdrToHdrReferenceWhiteNitsChanged: (Int) -> Unit = {},
    onModeChanged: (String) -> Unit,
    showInterface: Boolean = true,
    showConversion: Boolean = true,
) {
    val state = LocalUiHdrState.current
    val facts = rememberHdrControlFacts(mode)
    val showChoice = showInterface && hdrInterfaceChoiceVisible(facts.sdk, facts.displayHdr, facts.wideColor)
    val showSwitch = showConversion && sdrToHdrSwitchVisible(facts)
    if (!showChoice && !showConversion) return
    // A standalone conversion switch already owns its label and explanation. Keep a
    // group label when presenting HDR interface choices or explaining an unavailable switch.
    val switchOwnsHeading = !showInterface && showSwitch
    SettingsGroup(
        title = if (switchOwnsHeading) null else stringResource(if (showInterface) R.string.settings_ui_hdr else R.string.settings_sdr_to_hdr),
        subtitle = if (switchOwnsHeading) null else stringResource(if (showInterface) R.string.settings_ui_hdr_hint else R.string.settings_sdr_to_hdr_hint),
    ) {
        if (showChoice) {
            SettingsSelect(options = listOf(
                SettingsSelectOption(stringResource(R.string.settings_ui_hdr_auto), mode == "auto", { onModeChanged("auto") }),
                SettingsSelectOption(stringResource(R.string.settings_ui_hdr_force), mode == "force", { onModeChanged("force") }),
                SettingsSelectOption(stringResource(R.string.settings_ui_hdr_sdr), mode == "sdr", { onModeChanged("sdr") }),
            ))
            Text(stringResource(when {
                state?.failed == true -> R.string.settings_ui_hdr_failed
                state != null && state.headroom > 1f -> R.string.settings_ui_hdr_requested
                else -> R.string.settings_ui_hdr_default
            }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showConversion) {
            com.ambient.tv.ui.display.sdrToHdrUnavailableReason(facts)?.let { reason ->
                Text(stringResource(sdrToHdrReasonTextRes(reason)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (showSwitch) {
            TvSettingRow(
                title = stringResource(R.string.settings_sdr_to_hdr),
                body = stringResource(R.string.settings_sdr_to_hdr_hint),
                checked = sdrToHdrEnabled,
                onCheckedChange = onSdrToHdrChanged,
                enabled = sdrToHdrSwitchEnabled(facts),
            )
            if (sdrToHdrEnabled && sdrToHdrSwitchEnabled(facts)) {
                Text(stringResource(R.string.settings_sdr_to_hdr_nits), style = MaterialTheme.typography.bodyMedium)
                SettingsSelect(options = (listOf(0, 300, 400, 500, 600) + sdrToHdrPeakNits).distinct().sorted().map { nits ->
                    SettingsSelectOption(
                        if (nits == 0) stringResource(R.string.settings_ui_hdr_auto) else "$nits nits",
                        sdrToHdrPeakNits == nits,
                        { onSdrToHdrPeakNitsChanged(nits) },
                    )
                })
                Text(stringResource(R.string.settings_sdr_to_hdr_nits_hint), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.sdr_reference_white), style = MaterialTheme.typography.bodyMedium)
                val whiteKey = com.ambient.tv.ui.player.sdrReferenceWhiteChoiceKey(sdrToHdrReferenceWhiteNits)
                SettingsSelect(options = com.ambient.tv.ui.player.sdrReferenceWhiteChoices().map { (key, label) ->
                    SettingsSelectOption(label, whiteKey == key, {
                        onSdrToHdrReferenceWhiteNitsChanged(
                            com.ambient.tv.ui.player.sdrReferenceWhiteForChoice(key, sdrToHdrReferenceWhiteNits))
                    })
                })
                com.ambient.tv.ui.player.SdrReferenceWhiteCustomSlider(sdrToHdrReferenceWhiteNits, onSdrToHdrReferenceWhiteNitsChanged)
                Text(stringResource(R.string.sdr_reference_white_hint), style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(sdrToHdrStatusTextRes(mode, sdrToHdrEnabled, state?.sdrToHdrStatus)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun rememberHdrControlFacts(mode: String, inspectVideoPlayback: Boolean = true): SdrToHdrSwitchFacts {
    val view = LocalView.current
    val reduced = LocalPerformanceProfile.current.gateSdrToHdr
    val snapshot by BackgroundPlaybackSession.snapshot.collectAsState()
    // Music, Spotify and other audio-only playback has no video path to judge.
    val handle = if (inspectVideoPlayback && snapshot.active && snapshot.metadata?.hasVideo == true) BackgroundPlaybackSession.activeHandle() else null
    val path = handle?.sdrToHdrPath()
    val display = view.display
    val sdk = Build.VERSION.SDK_INT
    val ratio = if (Build.VERSION.SDK_INT >= 34 && display?.isHdrSdrRatioAvailable == true) display.hdrSdrRatio else null
    return SdrToHdrSwitchFacts(
        sdk = sdk,
        displayHdr = display?.isHdr == true,
        wideColor = display?.isWideColorGamut == true,
        mode = mode,
        reducedEffects = reduced,
        ratio = ratio,
        peakNits = display?.hdrCapabilities?.desiredMaxLuminance,
        player = path?.kind ?: if (handle == null) SdrToHdrPlayerKind.NONE else SdrToHdrPlayerKind.MPV_UNSUPPORTED,
        frameWidth = path?.width ?: 0,
        frameHeight = path?.height ?: 0,
        frameRate = path?.fps ?: 0f,
        pathReason = path?.reason,
        pathBlocked = handle != null && (path == null || path.blocked),
        hdr10Supported = display != null && android.view.Display.HdrCapabilities.HDR_TYPE_HDR10 in
            com.ambient.tv.ui.player.HdrSupport.enabledHdrTypes(display),
    )
}

@StringRes
internal fun sdrToHdrStatusTextRes(mode: String, sdrToHdrEnabled: Boolean, status: SdrToHdrStatus?): Int {
    val effectiveStatus = if (mode == "auto" && sdrToHdrEnabled) {
        status ?: SdrToHdrStatus.UNAVAILABLE
    } else {
        SdrToHdrStatus.OFF
    }
    return when (effectiveStatus) {
        SdrToHdrStatus.OFF -> R.string.settings_sdr_to_hdr_off
        SdrToHdrStatus.UNAVAILABLE -> R.string.settings_sdr_to_hdr_unavailable
        SdrToHdrStatus.WAITING -> R.string.settings_sdr_to_hdr_waiting
        SdrToHdrStatus.ACTIVE -> R.string.settings_sdr_to_hdr_active
        SdrToHdrStatus.FAILED -> R.string.settings_sdr_to_hdr_failed
    }
}

@StringRes
internal fun sdrToHdrReasonTextRes(reason: com.ambient.tv.ui.display.SdrToHdrUnavailableReason): Int = when (reason) {
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.SOURCE_NOT_SDR -> R.string.sdr_hdr_reason_source
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.NOT_READY -> R.string.sdr_hdr_reason_waiting
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.FILTER_CONFLICT -> R.string.sdr_hdr_reason_filters
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.SWAPCHAIN_NO_HDR10 -> R.string.sdr_hdr_reason_swapchain
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.OUTPUT_FAILED -> R.string.settings_sdr_to_hdr_failed
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.ANDROID_VERSION -> R.string.sdr_hdr_reason_android
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.DISPLAY_HDR10 -> R.string.sdr_hdr_reason_display
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.WIDE_COLOR -> R.string.sdr_hdr_reason_wide_color
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.REDUCED_EFFECTS -> R.string.sdr_hdr_reason_reduced
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.PATH_BLOCKED -> R.string.sdr_hdr_reason_path
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.DISPLAY_BRIGHTNESS_UNKNOWN -> R.string.sdr_hdr_reason_brightness
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.PLAYER_OUTPUT -> R.string.sdr_hdr_reason_output
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.FRAME_BUDGET -> R.string.sdr_hdr_reason_budget
    com.ambient.tv.ui.display.SdrToHdrUnavailableReason.AUTOMATIC_MODE -> R.string.sdr_hdr_reason_auto
}
