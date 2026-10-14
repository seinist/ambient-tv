package com.ambient.tv.ui.spotify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.spotify.SpotifyPlaybackTakeoverPrompt
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.ui.mobile.LocalHandheldUi
import kotlinx.coroutines.delay

/**
 * Foreground arbitration between an existing app-owned player and a Spotify Connect session.
 * Both sides are already paused before this dialog appears, so dismissing it must always restore
 * the current app playback instead of leaving two paused sessions behind.
 */
@Composable
fun SpotifyPlaybackTakeoverDialog(
    prompt: SpotifyPlaybackTakeoverPrompt,
    onContinueCurrent: () -> Unit,
    onSwitchToSpotify: () -> Unit,
    onDismiss: () -> Unit,
) {
    val handheld = LocalHandheldUi.current
    val continueFocus = remember(prompt.backgroundGeneration) { FocusRequester() }

    LaunchedEffect(prompt.backgroundGeneration) {
        delay(60)
        runCatching { continueFocus.requestFocus() }
    }
    // Both players are paused while this is open. With nobody at the TV the phone's latest action
    // (play on Spotify) wins after a visible countdown instead of blocking indefinitely.
    val switchToSpotify by rememberUpdatedState(onSwitchToSpotify)
    var remaining by remember(prompt.backgroundGeneration) { mutableIntStateOf(SPOTIFY_TAKEOVER_AUTO_SECONDS) }
    LaunchedEffect(prompt.backgroundGeneration) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        switchToSpotify()
    }

    AppDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.spotify_takeover_title),
        hint = stringResource(R.string.spotify_takeover_message),
        captureInitialFocus = false,
        widthFraction = if (handheld) 0.94f else 0.56f,
        footer = {
            TvActionButton(
                text = stringResource(R.string.spotify_takeover_continue),
                onClick = onContinueCurrent,
                selected = true,
                modifier = Modifier.focusRequester(continueFocus),
            )
            TvActionButton(
                text = com.ambient.tv.ui.common.systemString(
                    com.ambient.tv.ui.common.MediaSystem.SPOTIFY,
                    R.string.spotify_takeover_switch,
                ),
                onClick = onSwitchToSpotify,
            )
            TvActionButton(text = stringResource(R.string.back), onClick = onDismiss)
        },
    ) {
        Text(
            text = stringResource(R.string.spotify_takeover_auto, remaining),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (prompt.title.isNotBlank()) {
            Text(
                text = prompt.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (prompt.subtitle.isNotBlank()) {
                Text(
                    text = prompt.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SPOTIFY_TAKEOVER_AUTO_SECONDS = 15
