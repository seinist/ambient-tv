package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.common.RightPanelChoice
import com.ambient.tv.ui.common.SettingsHint
import com.ambient.tv.ui.gamepad.retro.RetroInputSettings
import com.ambient.tv.ui.gamepad.retro.TouchControlStyle
import com.ambient.tv.ui.settings.consoleArtworkVariants

/** The same bounded options are available in Settings, the game OSD and the phone remote. */
@Composable
internal fun GameTouchOptions(systemId: String? = null) {
    val config = RetroInputSettings.config
    val style = config.touchStyle.normalized()
    fun update(change: (TouchControlStyle) -> TouchControlStyle) =
        RetroInputSettings.update { it.copy(touchStyle = change(it.touchStyle).normalized()) }
    fun next(current: Int, values: List<Int>) = values[(values.indexOf(current) + 1) % values.size]
    val percent: (Int) -> String = { "$it %" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsHint(stringResource(R.string.game_touch_customization_hint))
        val variants = listOf("" to stringResource(R.string.game_touch_system_layout)) +
            systemId?.let(::consoleArtworkVariants).orEmpty() +
            ("retropad" to stringResource(R.string.game_touch_full_retropad))
        if (systemId != null) {
            val variant = variants.firstOrNull { it.first == config.touchVariants[systemId] } ?: variants.first()
            RightPanelChoice(text = stringResource(R.string.input_mapping_visual_variant),
                subtitle = variant.second, selected = false, onClick = {
                    val chosen = variants[(variants.indexOf(variant) + 1) % variants.size].first
                    RetroInputSettings.update { it.copy(touchVariants = it.touchVariants + (systemId to chosen)) }
                })
        }
        RightPanelChoice(text = stringResource(R.string.game_touch_size), subtitle = percent(style.scalePercent),
            selected = false, onClick = { update { it.copy(scalePercent = next(it.scalePercent, listOf(100, 110, 120, 130, 140))) } })
        RightPanelChoice(text = stringResource(R.string.game_touch_opacity), subtitle = percent(style.opacityPercent),
            selected = false, onClick = { update { it.copy(opacityPercent = next(it.opacityPercent, listOf(25, 50, 75, 100))) } })
        fun positionLabel(value: Int): Int = when (value) {
            0 -> R.string.game_touch_position_upper
            50 -> R.string.game_touch_position_middle
            else -> R.string.game_touch_position_lower
        }
        RightPanelChoice(text = stringResource(R.string.game_touch_portrait_position),
            subtitle = stringResource(positionLabel(style.portraitPosition)), selected = false,
            onClick = { update { it.copy(portraitPosition = next(it.portraitPosition, listOf(0, 50, 100))) } })
        RightPanelChoice(text = stringResource(R.string.game_touch_landscape_position),
            subtitle = stringResource(positionLabel(style.landscapePosition)), selected = false,
            onClick = { update { it.copy(landscapePosition = next(it.landscapePosition, listOf(0, 50, 100))) } })
        RightPanelChoice(text = stringResource(R.string.game_touch_deadzone), subtitle = percent(style.stickDeadzonePercent),
            selected = false, onClick = { update { it.copy(stickDeadzonePercent = next(it.stickDeadzonePercent, listOf(0, 5, 12, 20, 30))) } })
        RightPanelChoice(text = stringResource(R.string.game_touch_diagonals), subtitle = stringResource(when (style.diagonalSensitivity) {
            0 -> R.string.game_touch_diagonals_narrow
            2 -> R.string.game_touch_diagonals_wide
            else -> R.string.game_touch_diagonals_equal
        }), selected = false, onClick = { update { it.copy(diagonalSensitivity = (it.diagonalSensitivity + 1) % 3) } })
        RightPanelChoice(text = stringResource(R.string.game_touch_hide_gamepad),
            subtitle = stringResource(R.string.game_touch_hide_gamepad_hint), selected = style.hideOnGamepadInput,
            onClick = { update { it.copy(hideOnGamepadInput = !it.hideOnGamepadInput) } })
    }
}
