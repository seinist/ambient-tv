package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.local.VideoImageCatalog

/** Describe the selected source, without mistaking every unavailable track for HDR. */
@Composable
internal fun media3SourceRestrictionText(reason: Media3ImageSourceRestriction?): String = stringResource(
    when (reason) {
        Media3ImageSourceRestriction.DOLBY_VISION -> R.string.image_media3_source_dolby_vision
        Media3ImageSourceRestriction.HDR -> R.string.image_media3_source_hdr
        Media3ImageSourceRestriction.DRM -> R.string.image_media3_source_protected
        Media3ImageSourceRestriction.UNKNOWN_COLOR -> R.string.image_media3_source_unknown_color
        Media3ImageSourceRestriction.UNKNOWN_VIDEO, null -> R.string.image_media3_source_waiting
    },
)

/** The same truthful feedback in the playback panel and the compact picture editor. */
@Composable
internal fun Media3ImageProcessingFeedback(processing: Media3ImageProcessing, showActive: Boolean = false) {
    if (!processing.options.enabled) return
    if (processing.effectsFailed) {
        ImageHint(stringResource(R.string.image_media3_effects_failed))
        return
    }
    processing.sourceRestriction?.let {
        ImageHint(media3SourceRestrictionText(it))
        return
    }
    if (showActive && processing.effectsActive) ImageHint(stringResource(R.string.image_media3_active))

    val exclusions = processing.filterBudgetExclusions
    val locale = LocalConfiguration.current.locales[0]
    for (excluded in exclusions) {
        ImageHint(stringResource(R.string.image_media3_filter_budget_detail,
            media3StageLabel(excluded.stage), excluded.width, excluded.height,
            formatFrameTargetFps(excluded.frameRate.toDouble(), locale)))
    }
    // A skipped filter occurs in both collections. Its workload message above is more precise.
    for (reduction in processing.effectReductions) {
        if (exclusions.any { it.stage::class == reduction.requested::class }) continue
        when (val requested = reduction.requested) {
            is Media3Stage.Scale -> {
                val applied = reduction.applied as? Media3Stage.Scale
                if (applied == null) {
                    ImageHint(stringResource(R.string.image_media3_stage_not_applied, media3StageLabel(requested)))
                    continue
                }
                if (requested.scaler != applied.scaler) {
                    ImageHint(stringResource(R.string.image_media3_scaler_fallback,
                        stringResource(R.string.player_upscaler), media3ScalerLabel(requested.scaler),
                        media3ScalerLabel(applied.scaler)))
                }
                if (requested.superResolution && !applied.superResolution) {
                    ImageHint(stringResource(R.string.image_media3_reconstruction_reduced))
                }
                if (requested.antiring != applied.antiring) {
                    ImageHint(stringResource(R.string.image_media3_antiring_reduced,
                        requested.antiring, applied.antiring, media3ScalerLabel(applied.scaler)))
                }
                val requestedSigmoid = requested.sigmoid && requested.scaler in MEDIA3_SIGMOID_SCALERS
                val appliedSigmoid = applied.sigmoid && applied.scaler in MEDIA3_SIGMOID_SCALERS
                if (requestedSigmoid && !appliedSigmoid) {
                    ImageHint(stringResource(R.string.image_media3_sigmoid_reduced))
                }
            }
            is Media3Stage.Downscale -> {
                val applied = reduction.applied as? Media3Stage.Downscale
                if (applied == null) {
                    ImageHint(stringResource(R.string.image_media3_stage_not_applied, media3StageLabel(requested)))
                    continue
                }
                if (requested.scaler != applied.scaler) {
                    ImageHint(stringResource(R.string.image_media3_scaler_fallback,
                        stringResource(R.string.image_downscaler), media3DownscalerLabel(requested.scaler),
                        media3DownscalerLabel(applied.scaler)))
                }
                if (requested.antiring != applied.antiring) {
                    ImageHint(stringResource(R.string.image_media3_antiring_reduced,
                        requested.antiring, applied.antiring, media3DownscalerLabel(applied.scaler)))
                }
            }
            else -> if (reduction.applied == null) {
                ImageHint(stringResource(R.string.image_media3_stage_not_applied, media3StageLabel(requested)))
            }
        }
    }
}

private fun media3DownscalerLabel(key: String): String = VideoImageCatalog.media3Downscalers[key] ?: key

@Composable
private fun media3FilterKindLabel(kind: String): String = when (kind) {
    "off" -> stringResource(R.string.player_off)
    "light" -> stringResource(R.string.image_light)
    "medium" -> stringResource(R.string.image_medium)
    "strong" -> stringResource(R.string.image_strong)
    "cas" -> "AMD CAS"
    "rcas" -> "FSR RCAS"
    "adaptive" -> "Adaptive Sharpen"
    else -> kind
}

/** Algorithm names are shared with the selectors; filter names and strengths are localized. */
@Composable
private fun media3StageLabel(stage: Media3Stage): String {
    val label = when (stage) {
        is Media3Stage.Deblock -> R.string.image_deblock
        is Media3Stage.Denoise -> R.string.image_media3_denoise
        is Media3Stage.Deband -> R.string.player_deband
        is Media3Stage.Sharpen -> R.string.image_sharpen
        is Media3Stage.Grain -> R.string.image_film_grain
        is Media3Stage.Scale -> R.string.player_upscaler
        is Media3Stage.Downscale -> R.string.image_downscaler
        is Media3Stage.Brightness -> R.string.image_brightness
        is Media3Stage.Contrast -> R.string.image_contrast
        is Media3Stage.Saturation -> R.string.image_saturation
        is Media3Stage.Gamma -> R.string.image_gamma
    }
    val detail = when (stage) {
        is Media3Stage.Deblock -> media3FilterKindLabel(stage.strength)
        is Media3Stage.Denoise -> media3FilterKindLabel(stage.strength)
        is Media3Stage.Deband -> media3FilterKindLabel(stage.strength)
        is Media3Stage.Sharpen -> media3FilterKindLabel(stage.kind)
        is Media3Stage.Grain -> media3FilterKindLabel(stage.strength)
        is Media3Stage.Scale -> media3ScalerLabel(stage.scaler)
        is Media3Stage.Downscale -> media3DownscalerLabel(stage.scaler)
        else -> null
    }
    return if (detail == null) stringResource(label)
        else stringResource(R.string.image_media3_filter_named, stringResource(label), detail)
}
