package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerValueSlider
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import com.ambient.tv.ui.settings.consoleArtworkVariants

/**
 * The same bounded options are available in Settings, the game OSD and the phone remote. Numbers
 * use the shared value slider and choices the shared select, like every other player panel.
 */
@Composable
internal fun GameTouchOptions(systemId: String? = null) {
    val config = RetroInputSettings.config
    val style = config.touchStyle.normalized()
    fun update(change: (TouchControlStyle) -> TouchControlStyle) =
        RetroInputSettings.update { it.copy(touchStyle = change(it.touchStyle).normalized()) }
    val percent: (Int) -> String = { "$it %" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsHint(stringResource(R.string.game_touch_customization_hint))
        val variants = listOf("" to stringResource(R.string.game_touch_system_layout)) +
            systemId?.let(::consoleArtworkVariants).orEmpty() +
            ("retropad" to stringResource(R.string.game_touch_full_retropad))
        if (systemId != null) {
            val variant = variants.firstOrNull { it.first == config.touchVariants[systemId] } ?: variants.first()
            PlayerDropUpSelector(
                label = stringResource(R.string.input_mapping_visual_variant),
                value = variant.second,
                options = variants,
                selectedKey = variant.first,
                dropUp = false,
                onSelected = { chosen ->
                    RetroInputSettings.update { it.copy(touchVariants = it.touchVariants + (systemId to chosen)) }
                },
            )
        }
        PlayerValueSlider(
            label = stringResource(R.string.game_touch_size),
            value = style.scalePercent,
            valueRange = 100..140,
            step = 10,
            valueLabel = percent(style.scalePercent),
            onValueChange = { value -> update { it.copy(scalePercent = value) } },
        )
        PlayerValueSlider(
            label = stringResource(R.string.game_touch_opacity),
            value = style.opacityPercent,
            valueRange = 25..100,
            step = 5,
            valueLabel = percent(style.opacityPercent),
            onValueChange = { value -> update { it.copy(opacityPercent = value) } },
        )
        val positions = listOf(
            "0" to stringResource(R.string.game_touch_position_upper),
            "50" to stringResource(R.string.game_touch_position_middle),
            "100" to stringResource(R.string.game_touch_position_lower),
        )
        fun positionKey(value: Int): String = when {
            value < 25 -> "0"
            value < 75 -> "50"
            else -> "100"
        }
        PlayerDropUpSelector(
            label = stringResource(R.string.game_touch_portrait_position),
            value = positions.first { it.first == positionKey(style.portraitPosition) }.second,
            options = positions,
            selectedKey = positionKey(style.portraitPosition),
            dropUp = false,
            onSelected = { key -> update { it.copy(portraitPosition = key.toInt()) } },
        )
        PlayerDropUpSelector(
            label = stringResource(R.string.game_touch_landscape_position),
            value = positions.first { it.first == positionKey(style.landscapePosition) }.second,
            options = positions,
            selectedKey = positionKey(style.landscapePosition),
            dropUp = false,
            onSelected = { key -> update { it.copy(landscapePosition = key.toInt()) } },
        )
        PlayerValueSlider(
            label = stringResource(R.string.game_touch_deadzone),
            value = style.stickDeadzonePercent,
            valueRange = 0..35,
            step = 1,
            valueLabel = percent(style.stickDeadzonePercent),
            onValueChange = { value -> update { it.copy(stickDeadzonePercent = value) } },
        )
        val diagonals = listOf(
            "0" to stringResource(R.string.game_touch_diagonals_narrow),
            "1" to stringResource(R.string.game_touch_diagonals_equal),
            "2" to stringResource(R.string.game_touch_diagonals_wide),
        )
        val diagonal = style.diagonalSensitivity.coerceIn(0, 2).toString()
        PlayerDropUpSelector(
            label = stringResource(R.string.game_touch_diagonals),
            value = diagonals.first { it.first == diagonal }.second,
            options = diagonals,
            selectedKey = diagonal,
            dropUp = false,
            onSelected = { key -> update { it.copy(diagonalSensitivity = key.toInt()) } },
        )
        RightPanelChoice(text = stringResource(R.string.game_touch_hide_gamepad),
            subtitle = stringResource(R.string.game_touch_hide_gamepad_hint), selected = style.hideOnGamepadInput,
            onClick = { update { it.copy(hideOnGamepadInput = !it.hideOnGamepadInput) } })
    }
}
