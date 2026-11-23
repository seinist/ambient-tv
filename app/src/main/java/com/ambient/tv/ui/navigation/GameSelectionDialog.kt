package com.ambient.tv.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import kotlinx.coroutines.delay

/** Games use the same compact options/wake controls on Home, in the library and on details. */
@Composable
internal fun GameSelectionDialog(
    state: GameSelectionUiState?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    /** Starts over, like "From the beginning" in the media dialog; only once the game was played. */
    onPlayFresh: () -> Unit = onPlay,
    onWake: () -> Unit,
    onDetails: () -> Unit,
) {
    // A quick lookup and the target handoff may have no dialog window yet. Back must still
    // cancel the request, even when the page's own Back only moves focus to the sidebar.
    // Once visible, AppDialog's native window sends its own Back to the same onDismiss.
    BackHandler(enabled = state != null, onBack = onDismiss)
    val phase = state?.phase
    val busy = phase == GameSelectionPhase.RESOLVING || phase == GameSelectionPhase.CHECKING
    var showProgress by remember(phase) { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (busy) {
            delay(180)
            showProgress = true
        }
    }
    if (state == null || phase == GameSelectionPhase.LAUNCHING || (busy && !showProgress)) return
    AppDialog(
        onDismiss = onDismiss,
        title = state.game?.title ?: stringResource(R.string.game_library_title),
        hint = when (phase) {
            GameSelectionPhase.OPTIONS -> stringResource(R.string.media_selection_dialog_hint)
            GameSelectionPhase.WAKE -> stringResource(R.string.game_library_offline_wake,
                state.sourceName.ifBlank { stringResource(R.string.game_source_fallback) })
            GameSelectionPhase.FAILED -> stringResource(if (state.failure == GameSelectionFailure.MISSING)
                R.string.game_error_file_missing else R.string.game_error_play)
            else -> stringResource(R.string.player_loading)
        },
        footer = {
            when (phase) {
                GameSelectionPhase.OPTIONS -> {
                    TvActionButton(stringResource(R.string.game_detail_play), onClick = onPlay,
                        selected = true, icon = Icons.Filled.PlayArrow)
                    if ((state.game?.lastPlayed ?: 0L) > 0L) TvActionButton(stringResource(R.string.game_reset),
                        onClick = onPlayFresh, icon = Icons.Filled.RestartAlt)
                    TvActionButton(stringResource(R.string.media_selection_details), onClick = onDetails,
                        icon = Icons.Filled.Info)
                }
                GameSelectionPhase.WAKE -> {
                    TvActionButton(stringResource(R.string.home_retry), onClick = onPlay,
                        icon = Icons.Filled.Refresh)
                    TvActionButton(stringResource(R.string.game_library_wake), onClick = onWake,
                        icon = Icons.Filled.PowerSettingsNew)
                }
                GameSelectionPhase.FAILED -> if (state.failure != GameSelectionFailure.MISSING) {
                    TvActionButton(stringResource(R.string.home_retry), onClick = onPlay,
                        icon = Icons.Filled.Refresh)
                }
                else -> Unit
            }
            TvActionButton(stringResource(R.string.back), onClick = onDismiss)
        },
    )
}
