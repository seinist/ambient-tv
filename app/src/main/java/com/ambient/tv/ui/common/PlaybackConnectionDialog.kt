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
        hint = listOfNotNull(
            when {
                busy || failure == null -> null
                failure.authRequired -> stringResource(R.string.offline_playback_auth, failure.name)
                else -> stringResource(R.string.offline_playback_unavailable, failure.name)
            },
            title.takeIf(String::isNotBlank),
        ).joinToString("\n\n").takeIf(String::isNotBlank),
        footer = {
            if (failure?.authRequired == true && !busy)
                TvActionButton(stringResource(R.string.offline_playback_login), onClick = onLogin)
            TvActionButton(stringResource(R.string.home_retry), onClick = onRetry, loading = busy)
            TvActionButton(stringResource(R.string.files_cancel), onClick = onCancel)
        },
    )
}
