package com.ambient.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerValueSlider
import com.ambient.tv.ui.display.SDR_REFERENCE_WHITE_MAX
import com.ambient.tv.ui.display.SDR_REFERENCE_WHITE_MIN
import com.ambient.tv.ui.display.SDR_REFERENCE_WHITE_PRESETS
import com.ambient.tv.ui.display.normalizedSdrReferenceWhiteNits

internal const val SDR_REFERENCE_WHITE_CUSTOM = "custom"
/** First custom value when leaving a preset: between the 203 default and the 300 preset. */
internal const val SDR_REFERENCE_WHITE_CUSTOM_START = 250
internal const val SDR_REFERENCE_WHITE_STEP = 10

/** Preset key ("0" = Auto) or [SDR_REFERENCE_WHITE_CUSTOM]. */
internal fun sdrReferenceWhiteChoiceKey(value: Int): String =
    normalizedSdrReferenceWhiteNits(value).let { if (it in SDR_REFERENCE_WHITE_PRESETS) it.toString() else SDR_REFERENCE_WHITE_CUSTOM }

/** The value a choice stores. Custom keeps an existing custom value. */
internal fun sdrReferenceWhiteForChoice(key: String, current: Int): Int =
    if (key == SDR_REFERENCE_WHITE_CUSTOM) {
        normalizedSdrReferenceWhiteNits(current).takeIf { it !in SDR_REFERENCE_WHITE_PRESETS } ?: SDR_REFERENCE_WHITE_CUSTOM_START
    } else normalizedSdrReferenceWhiteNits(key.toIntOrNull() ?: 0)

@Composable
internal fun sdrReferenceWhiteChoices(): List<Pair<String, String>> =
    SDR_REFERENCE_WHITE_PRESETS.map { nits ->
        nits.toString() to if (nits == 0) stringResource(R.string.sdr_reference_white_auto) else "$nits nits"
    } + (SDR_REFERENCE_WHITE_CUSTOM to stringResource(R.string.sdr_reference_white_custom))

/** Custom value slider, shown only while the custom choice is selected. */
@Composable
internal fun SdrReferenceWhiteCustomSlider(value: Int, onChanged: (Int) -> Unit) {
    if (sdrReferenceWhiteChoiceKey(value) != SDR_REFERENCE_WHITE_CUSTOM) return
    PlayerValueSlider(
        label = stringResource(R.string.sdr_reference_white_custom_value),
        value = normalizedSdrReferenceWhiteNits(value),
        valueRange = SDR_REFERENCE_WHITE_MIN..SDR_REFERENCE_WHITE_MAX,
        step = SDR_REFERENCE_WHITE_STEP,
        valueLabel = "${normalizedSdrReferenceWhiteNits(value)} nits",
        onValueChange = { onChanged(normalizedSdrReferenceWhiteNits(it)) },
    )
}
