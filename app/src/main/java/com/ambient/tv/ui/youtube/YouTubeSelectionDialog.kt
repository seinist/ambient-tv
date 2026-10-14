package com.ambient.tv.ui.youtube

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import kotlinx.coroutines.delay

@Composable
fun YouTubeSelectionDialog(
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(60)
        runCatching { firstFocus.requestFocus() }
    }
    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.media_selection_dialog_title),
        hint = stringResource(R.string.media_selection_dialog_hint),
        captureInitialFocus = false,
        footer = {
            TvActionButton(
                text = stringResource(R.string.detail_play),
                onClick = onPlay,
                selected = true,
                modifier = Modifier.focusRequester(firstFocus),
            )
            TvActionButton(text = stringResource(R.string.media_selection_details), onClick = onDetails)
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss)
        },
    )
}
