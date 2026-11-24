package com.ambient.tv.ui.games

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.games.GameCoreOption
import com.ambient.tv.games.GameOptionOrigin
import com.ambient.tv.games.GamePictureDefaults
import com.ambient.tv.games.GameShader
import com.ambient.tv.games.GameShaderGroup
import com.ambient.tv.games.GameSystem
import com.ambient.tv.games.InstalledGameShaderPreset
import com.ambient.tv.ui.common.GroupedPlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerDropUpSection
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.SettingsHint

/** Core values use the shared select while categories retain their own configuration page. */
@Composable
internal fun GameCoreOptionSelector(
    option: GameCoreOption,
    origin: GameOptionOrigin,
    onSelected: (String) -> Unit,
) {
    val defaultLabel = stringResource(R.string.game_core_value_default)
    PlayerDropUpSelector(
        label = option.title,
        value = when (origin) {
            GameOptionOrigin.GAME -> stringResource(R.string.game_core_origin_game, option.label)
            GameOptionOrigin.CORE -> stringResource(R.string.game_core_origin_core, option.label)
            GameOptionOrigin.DEFAULT -> option.label
        },
        options = option.values.map { choice ->
            choice.value to if (choice.value == option.default) "${choice.label} · $defaultLabel" else choice.label
        },
        selectedKey = option.value,
        selectorKey = "game-core-option:${option.key}",
        dropUp = false,
        onSelected = { value -> if (option.values.any { it.value == value }) onSelected(value) },
    )
    if (option.info.isNotBlank()) SettingsHint(option.info)
}

/** Built-in groups and installed packs are sections of one value selector, not extra pages. */
@Composable
internal fun GamePictureFilterSelector(
    system: GameSystem,
    shader: GameShader,
    selectedPack: String?,
    installedPresets: List<InstalledGameShaderPreset>,
    onShaderSelected: (GameShader) -> Unit,
    onPackSelected: (InstalledGameShaderPreset) -> Unit,
) {
    val recommended = GamePictureDefaults.recommended(system)
    val recommendedLabel = stringResource(R.string.game_shader_recommended)
    val choices = GameShader.entries.filter { it == shader || GamePictureDefaults.offers(system, it) }
    val groups = GameShaderGroup.entries.filter { group -> choices.any { it.group == group } }
        .sortedBy { group -> recommended.none { it.group == group } }
    val sections = groups.map { group ->
        PlayerDropUpSection(stringResource(group.titleRes), choices.filter { it.group == group }
            .sortedBy { it !in recommended }.map { choice ->
                val title = stringResource(choice.titleRes)
                "shader:${choice.key}" to if (choice in recommended) "$title · $recommendedLabel" else title
            })
    } + installedPresets.groupBy { it.packTitle to it.license }.map { (pack, presets) ->
        PlayerDropUpSection(listOf(pack.first, pack.second).filter(String::isNotBlank).joinToString(" · "),
            presets.map { "pack:${it.selection}" to it.title })
    }
    val currentPreset = installedPresets.firstOrNull { it.selection == selectedPack }
    GroupedPlayerDropUpSelector(
        label = stringResource(R.string.game_picture_filter),
        value = if (selectedPack == null) stringResource(shader.titleRes)
            else currentPreset?.title ?: stringResource(R.string.games_shader_packs_missing),
        sections = sections,
        selectedKey = selectedPack?.let { "pack:$it" } ?: "shader:${shader.key}",
        selectorKey = "game-picture-filter",
        dropUp = false,
        onSelected = { key ->
            choices.firstOrNull { "shader:${it.key}" == key }?.let(onShaderSelected)
                ?: installedPresets.firstOrNull { "pack:${it.selection}" == key }?.let(onPackSelected)
        },
    )
    if (selectedPack == null) SettingsHint(stringResource(shader.hintRes))
    else currentPreset?.let { preset ->
        SettingsHint(listOf(preset.packTitle, preset.license).filter(String::isNotBlank).joinToString(" · "))
    }
}

/** Disc switching is a single value in playback settings; the OSD shortcut shares this control. */
@Composable
internal fun GameDiscSelector(discCount: Int, currentDisc: Int, onSelected: (Int) -> Unit) {
    if (discCount <= 0) return
    val choices = (0 until discCount).map { it.toString() to stringResource(R.string.game_disc, it + 1) }
    PlayerDropUpSelector(
        label = stringResource(R.string.game_panel_disc),
        value = choices.getOrNull(currentDisc)?.second ?: stringResource(R.string.game_panel_disc),
        options = choices,
        selectedKey = currentDisc.toString(),
        selectorKey = "game-disc",
        icon = Icons.Filled.Album,
        dropUp = false,
        onSelected = { key -> key.toIntOrNull()?.takeIf { it in 0 until discCount }?.let(onSelected) },
    )
}
