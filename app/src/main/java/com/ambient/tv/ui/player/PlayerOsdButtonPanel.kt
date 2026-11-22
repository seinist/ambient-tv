package com.ambient.tv.ui.player

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerPanelEntry
import com.ambient.tv.ui.common.RightPanelSectionTitle
import com.ambient.tv.ui.common.icon
import com.ambient.tv.ui.mobile.LocalHandheldUi

private enum class OsdButtonSection(@StringRes val title: Int) {
    PLAYBACK(R.string.player_playback_info),
    PICTURE_SOUND(R.string.osd_buttons_section_picture_sound),
    LIVE_TV(R.string.settings_live_tv_title),
    YOUTUBE(R.string.osd_buttons_section_youtube),
    RADIO(R.string.radio_title),
    GAMES(R.string.game_library_title),
    TOOLS(R.string.player_tools),
    INFORMATION(R.string.player_osd_info_information),
}

/** Every button belongs to one section; a new button must choose its section explicitly. */
private val OsdButton.section: OsdButtonSection get() = when (this) {
    OsdButton.SEEK, OsdButton.STOP, OsdButton.NEXT, OsdButton.EXIT, OsdButton.CHAPTERS,
    OsdButton.SLEEP, OsdButton.SHUFFLE, OsdButton.REPEAT -> OsdButtonSection.PLAYBACK
    OsdButton.AUDIO, OsdButton.SUBTITLES, OsdButton.VIDEO, OsdButton.VOLUME,
    OsdButton.VISUALIZER -> OsdButtonSection.PICTURE_SOUND
    OsdButton.ZAP, OsdButton.RECORD, OsdButton.GO_LIVE, OsdButton.TELETEXT,
    OsdButton.TELETEXT_SUBTITLES, OsdButton.HBBTV -> OsdButtonSection.LIVE_TV
    OsdButton.CHANNEL, OsdButton.SUBSCRIBE -> OsdButtonSection.YOUTUBE
    OsdButton.FAVORITE -> OsdButtonSection.RADIO
    OsdButton.SCREENSHOT, OsdButton.BOOKMARK, OsdButton.AB_REPEAT, OsdButton.FRAME_STEP,
    OsdButton.LYRICS, OsdButton.TOOLS -> OsdButtonSection.TOOLS
    OsdButton.DETAILS, OsdButton.STATS, OsdButton.FPS -> OsdButtonSection.INFORMATION
    OsdButton.GAME_SAVE, OsdButton.GAME_LOAD, OsdButton.GAME_STATES, OsdButton.GAME_RESET,
    OsdButton.GAME_FAST_FORWARD, OsdButton.GAME_REWIND -> OsdButtonSection.PLAYBACK
    OsdButton.GAME_PICTURE -> OsdButtonSection.PICTURE_SOUND
    OsdButton.GAME_CORE, OsdButton.GAME_CONTROLS, OsdButton.GAME_ACHIEVEMENTS,
    OsdButton.GAME_CHEATS, OsdButton.GAME_NETPLAY, OsdButton.GAME_DISC -> OsdButtonSection.GAMES
    OsdButton.GAME_INFO -> OsdButtonSection.INFORMATION
}

@Composable
internal fun PlayerOsdButtonPanelControls(
    hasVideo: Boolean,
    player: OsdPlayer? = activeOsdPlayer,
    offered: List<OsdButton>? = null,
) {
    player ?: return
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val handheld = LocalHandheldUi.current
    val hidden = osdHiddenButtons()
    // Group only actual actions: an audio-only player must not leave an empty Information heading.
    val sections = filterVideoDiagnosticButtons(offered ?: player.offeredButtons(), hasVideo).groupBy { it.section }
    Text(stringResource(if (player == OsdPlayer.GAMES) R.string.game_osd_buttons_hint else R.string.osd_buttons_hint),
        style = MaterialTheme.typography.bodySmall)
    OsdButtonSection.entries.forEach { section ->
        sections[section]?.let { buttons ->
            key(player, section) {
                RightPanelSectionTitle(stringResource(section.title))
                buttons.forEach { button ->
                    key(button) {
                        val visible = osdButtonVisible(button, player, hidden)
                        PlayerPanelEntry(
                            icon = when {
                                button == OsdButton.EXIT && player == OsdPlayer.GAMES -> Icons.Filled.Stop
                                button == OsdButton.EXIT && handheld -> Icons.AutoMirrored.Filled.ArrowBack
                                else -> button.icon
                            },
                            title = stringResource(if (button == OsdButton.EXIT && player == OsdPlayer.GAMES)
                                R.string.game_exit else button.label),
                            value = stringResource(if (visible) R.string.osd_button_shown else R.string.osd_button_hidden),
                            trailingIcon = if (visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            onClick = { setOsdButtonVisible(preferences, player, button, !visible) },
                        )
                    }
                }
            }
        }
    }
}
