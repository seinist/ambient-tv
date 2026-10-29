package com.ambient.tv.ui.player

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerDropUpSelector
import com.ambient.tv.ui.common.PlayerOsdTimeout
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.common.RightPanelSectionTitle

private enum class OsdInfoEntry(@StringRes val summary: Int, @StringRes val section: Int) {
    BUTTONS(R.string.player_osd_info_buttons_summary, R.string.player_osd_info_controls),
    DURATION(R.string.player_osd_info_duration, R.string.player_osd_info_controls),
    CODECS(R.string.player_osd_info_codecs_summary, R.string.player_osd_info_information),
    FPS(R.string.player_osd_info_fps_summary, R.string.player_osd_info_information),
    STATS(R.string.player_osd_info_stats_summary, R.string.player_osd_info_information),
}

/** The description and the submenu use the same entries for the current player. */
private fun osdInfoEntries(audioSession: AudioSession?): List<OsdInfoEntry> = buildList {
    if (activeOsdPlayer?.offeredButtons()?.isNotEmpty() == true) add(OsdInfoEntry.BUTTONS)
    add(OsdInfoEntry.DURATION)
    add(OsdInfoEntry.CODECS)
    if (audioSession != null) {
        add(OsdInfoEntry.FPS)
        add(OsdInfoEntry.STATS)
    }
}

@Composable
internal fun PlayerOsdInfoPanelEntry(audioSession: AudioSession?, onOpen: () -> Unit) {
    PlayerPanelEntry(
        icon = Icons.Filled.Tune,
        title = stringResource(R.string.player_osd_info),
        value = osdInfoEntries(audioSession).map { stringResource(it.summary) }.joinToString(" · "),
        onClick = onOpen,
    )
}

@Composable
internal fun PlayerOsdInfoPanelControls(audioSession: AudioSession?, onOpenButtons: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val entries = osdInfoEntries(audioSession)
    entries.forEachIndexed { index, entry ->
        if (index == 0 || entries[index - 1].section != entry.section) {
            RightPanelSectionTitle(stringResource(entry.section))
        }
        when (entry) {
            OsdInfoEntry.BUTTONS -> activeOsdPlayer?.let { player ->
                val hidden = osdHiddenButtons()
                val offered = player.offeredButtons()
                PlayerPanelEntry(
                    icon = Icons.Filled.ViewWeek,
                    title = stringResource(R.string.osd_buttons),
                    value = stringResource(R.string.osd_buttons_summary,
                        offered.count { osdButtonVisible(it, player, hidden) }, offered.size),
                    onClick = onOpenButtons,
                )
            }
            OsdInfoEntry.DURATION -> {
                val seconds = PlayerOsdTimeout.seconds
                PlayerDropUpSelector(
                    icon = Icons.Filled.Timer,
                    label = stringResource(R.string.player_osd_info_duration),
                    value = stringResource(R.string.player_osd_duration_seconds, seconds),
                    options = PlayerOsdTimeout.SECONDS.map {
                        it.toString() to stringResource(R.string.player_osd_duration_seconds, it)
                    },
                    selectedKey = seconds.toString(),
                    onSelected = { key ->
                        key.toIntOrNull()?.takeIf { it in PlayerOsdTimeout.SECONDS }?.let {
                            preferences.playerOsdSeconds = it
                        }
                    },
                )
            }
            OsdInfoEntry.CODECS -> {
                val visible = playerTechBadgesVisible()
                PlayerPanelEntry(
                    icon = Icons.Filled.Visibility,
                    title = stringResource(R.string.player_tech_badges),
                    value = stringResource(if (visible) R.string.player_on else R.string.player_off),
                    onClick = { setPlayerTechBadgesVisible(preferences, !visible) },
                )
            }
            OsdInfoEntry.FPS -> audioSession?.let { session ->
                PlayerPanelEntry(
                    icon = Icons.Filled.Speed,
                    title = stringResource(R.string.player_fps_overlay),
                    value = stringResource(if (session.showFpsOverlay) R.string.player_on else R.string.player_off),
                    onClick = { session.showFpsOverlay = !session.showFpsOverlay },
                )
            }
            OsdInfoEntry.STATS -> audioSession?.let { session ->
                PlayerPanelEntry(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.player_stats_for_nerds),
                    value = stringResource(if (session.performanceHint) R.string.player_on else R.string.player_off),
                    onClick = { session.performanceHint = !session.performanceHint },
                )
            }
        }
    }
}
