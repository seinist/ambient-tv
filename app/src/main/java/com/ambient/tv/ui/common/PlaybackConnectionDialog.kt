package com.ambient.tv.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.ambient.tv.R
import com.ambient.tv.data.browse.PlaybackAccessResult

/** Remains over the originating grid/detail page; cancelling restores its existing focus. */
@Composable
fun PlaybackConnectionDialog(
    title: String,
    busy: Boolean,
    failure: PlaybackAccessResult?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onLogin: () -> Unit,
) {
    var visible by remember { mutableStateOf(failure != null) }
    LaunchedEffect(failure) {
        if (failure == null) kotlinx.coroutines.delay(250)
        visible = true
    }
    BackHandler(onBack = onCancel)
    if (!visible) return
    AppDialog(
        onDismiss = onCancel,
        title = stringResource(if (busy) R.string.offline_playback_checking else R.string.offline_playback_locked),
        hint = when {
            busy -> stringResource(R.string.offline_playback_checking_message)
            failure?.authRequired == true -> stringResource(R.string.offline_playback_auth, failure.name)
            else -> stringResource(R.string.offline_playback_unavailable, failure?.name.orEmpty())
        } + title.takeIf(String::isNotBlank)?.let { "\n\n$it" }.orEmpty(),
        footer = {
            if (failure?.authRequired == true && !busy)
                TvActionButton(stringResource(R.string.offline_playback_login), onClick = onLogin)
            TvActionButton(stringResource(R.string.home_retry), onClick = onRetry, loading = busy)
            TvActionButton(stringResource(R.string.files_cancel), onClick = onCancel)
        },
    )
}
