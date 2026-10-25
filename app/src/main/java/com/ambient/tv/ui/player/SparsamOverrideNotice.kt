package com.ambient.tv.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.MpvImageOptions
import com.ambient.tv.data.local.VideoImageCatalog
import com.ambient.tv.ui.common.PlayerPanelAction
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.performance.PerformanceMode
import com.ambient.tv.ui.performance.PlaybackPerformanceRequests
import com.ambient.tv.ui.performance.playbackModeLiftingSparsam
import com.ambient.tv.ui.player.backend.effectiveMpvImageOptions

/** A saved MPV image field that Sparsam replaces, with the saved value for the locked row. */
internal enum class MpvSparsamField { UPSCALER, CHROMA, DOWNSCALER, DEBAND, SHARPEN, DENOISE, DEBLOCK, FILM_GRAIN, SUPER_RES, ANTIRING, SIGMOID }

/**
 * Saved MPV fields still at their default that run cheap (Sparsam, or a low-end device; see
 * [effectiveMpvImageOptions]). The menu names them; choosing another value applies it.
 */
internal fun mpvSparsamOverrides(stored: MpvImageOptions, reduced: Boolean): List<Pair<MpvSparsamField, String>> {
    if (!reduced) return emptyList()
    val o = stored.normalized()
    val e = effectiveMpvImageOptions(o, reducedEffects = true)
    return buildList {
        if (o.scaler != e.scaler) add(MpvSparsamField.UPSCALER to o.scaler)
        if (o.chromaScaler != e.chromaScaler) add(MpvSparsamField.CHROMA to o.chromaScaler)
        if (o.downscaler != e.downscaler) add(MpvSparsamField.DOWNSCALER to o.downscaler)
        if (o.deband != e.deband) add(MpvSparsamField.DEBAND to o.deband)
        if (o.sharpening != e.sharpening) add(MpvSparsamField.SHARPEN to o.sharpening)
        if (o.denoise != e.denoise) add(MpvSparsamField.DENOISE to o.denoise)
        if (o.deblock != e.deblock) add(MpvSparsamField.DEBLOCK to o.deblock)
        if (o.filmGrain != e.filmGrain) add(MpvSparsamField.FILM_GRAIN to o.filmGrain)
        if (o.superResolution != e.superResolution) add(MpvSparsamField.SUPER_RES to "on")
        if (o.antiring != e.antiring) add(MpvSparsamField.ANTIRING to "${o.antiring} %")
        if (o.sigmoid != e.sigmoid) add(MpvSparsamField.SIGMOID to "on")
    }
}

/** Stats for nerds: "effective (Sparsam; saved: stored)" whenever Sparsam changed the value. */
internal fun formatSparsamStat(effective: String?, stored: String?, reduced: Boolean): String? {
    if (effective == null) return null
    if (!reduced || stored == null || stored.equals(effective, ignoreCase = true)) return effective
    return "$effective (Sparsam; saved: $stored)"
}

private fun sparsamFieldLabel(field: MpvSparsamField): Int = when (field) {
    MpvSparsamField.UPSCALER -> R.string.player_upscaler
    MpvSparsamField.CHROMA -> R.string.image_chroma
    MpvSparsamField.DOWNSCALER -> R.string.image_downscaler
    MpvSparsamField.DEBAND -> R.string.player_deband
    MpvSparsamField.SHARPEN -> R.string.image_sharpen
    MpvSparsamField.DENOISE -> R.string.image_denoise
    MpvSparsamField.DEBLOCK -> R.string.image_deblock
    MpvSparsamField.FILM_GRAIN -> R.string.image_film_grain
    MpvSparsamField.SUPER_RES -> R.string.image_superres
    MpvSparsamField.ANTIRING -> R.string.image_antiring
    MpvSparsamField.SIGMOID -> R.string.image_sigmoid
}

private fun storedLabel(value: String): String =
    VideoImageCatalog.mpvScalers[value] ?: VideoImageCatalog.chromaScalers[value] ?: VideoImageCatalog.downscalers[value] ?: value

/**
 * Explains why saved settings are not applied and offers the one-tap switch. [forSvp] also
 * covers Balanced/Low and Auto on low-end devices, where SVP is off without Sparsam.
 */
@Composable
internal fun SparsamOverrideNotice(forSvp: Boolean = false, showText: Boolean = true) {
    val profile = LocalPerformanceProfile.current
    val sparsam = profile.gateExpensiveVideoVisuals
    if (!sparsam && !(forSvp && !profile.allowSvpInterpolation)) return
    if (showText) ImageHint(stringResource(when {
        profile.playbackAutoReduced -> R.string.image_sparsam_auto_low_end
        sparsam -> R.string.image_sparsam_override
        else -> R.string.image_svp_needs_maximum
    }))
    val target = playbackModeLiftingSparsam(forSvp)
    // Auto on a full device already lifts the gates; on a low-end device it would not.
    if (profile.playbackMode == target) return
    PlayerPanelAction(
        Icons.Filled.Speed,
        stringResource(if (target == PerformanceMode.MAXIMUM) R.string.image_sparsam_switch_maximum else R.string.image_sparsam_switch_balanced),
        onClick = { PlaybackPerformanceRequests.request(target.key) },
    )
}

/** Locked rows: label, effective value and the retained saved value. */
@Composable
internal fun MpvSparsamLockedRows(stored: MpvImageOptions, fields: Set<MpvSparsamField>) {
    val overrides = mpvSparsamOverrides(stored, mpvCheapDefaultsActive())
        .filter { it.first in fields }
    for ((field, value) in overrides) {
        ImageHint(stringResource(R.string.image_sparsam_locked_row, stringResource(sparsamFieldLabel(field)), storedLabel(value)))
    }
}
