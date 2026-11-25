package com.ambient.tv.ui.games

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.PlayerIconButton
import com.ambient.tv.ui.player.OsdButton

/** Same transport order as the shared media, SmartTube and TVH OSDs, followed by game saves. */
@Composable
internal fun GamePlayerTransportControls(
    availability: GameOsdAvailability,
    canResume: Boolean,
    fastForward: Boolean,
    rewinding: Boolean,
    statesOpen: Boolean,
    onResume: () -> Unit,
    onExit: () -> Unit,
    onReset: () -> Unit,
    onFastForward: () -> Unit,
    onRewind: (Boolean) -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    onStates: () -> Unit,
    resumeModifier: Modifier = Modifier,
    statesModifier: Modifier = Modifier,
) {
    if (availability.rewind) PlayerIconButton(Icons.Filled.FastRewind,
        stringResource(R.string.game_rewind_hold), onClick = {}, selected = rewinding,
        osd = OsdButton.GAME_REWIND, onHold = onRewind)
    if (canResume) PlayerIconButton(Icons.Filled.PlayArrow, stringResource(R.string.game_continue),
        onResume, modifier = resumeModifier, primary = true)
    if (availability.reset) PlayerIconButton(Icons.Filled.RestartAlt, stringResource(R.string.game_reset),
        onReset, osd = OsdButton.GAME_RESET)
    PlayerIconButton(Icons.Filled.Stop, stringResource(R.string.game_exit), onExit, osd = OsdButton.EXIT)
    if (availability.fastForward) PlayerIconButton(Icons.Filled.FastForward,
        stringResource(R.string.game_fast_forward), onFastForward,
        selected = fastForward, osd = OsdButton.GAME_FAST_FORWARD)
    if (availability.save) PlayerIconButton(Icons.Filled.Save, stringResource(R.string.game_save_state),
        onSave, osd = OsdButton.GAME_SAVE)
    if (availability.latestLoadableManual != null) PlayerIconButton(Icons.Filled.SettingsBackupRestore,
        stringResource(R.string.game_quick_load), onLoad, osd = OsdButton.GAME_LOAD)
    if (availability.manageStates) PlayerIconButton(Icons.Filled.Bookmarks, stringResource(R.string.game_states),
        onStates, modifier = statesModifier, selected = statesOpen, osd = OsdButton.GAME_STATES)
}
