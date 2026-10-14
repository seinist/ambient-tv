package com.ambient.tv.ui.youtube

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import com.ambient.tv.youtube.youtubeLiveCountdown
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
internal fun YouTubeLiveEventDialog(
    state: YouTubePlayerState,
    onWait: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val event = state.liveEvent ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(event.scheduledStartMs) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        firstFocus.requestFocus()
    }
    CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides androidx.tv.material3.MaterialTheme.colorScheme.onSurface) {
        AppDialog(
            onDismiss = onBack,
            title = stringResource(R.string.youtube_live_event_title),
            captureInitialFocus = false,
            footer = {
                TvActionButton(
                    text = stringResource(if (state.waitingForLive) R.string.youtube_live_stop_waiting else R.string.youtube_live_wait),
                    onClick = onWait,
                    modifier = Modifier.focusRequester(firstFocus),
                )
                TvActionButton(text = stringResource(R.string.youtube_live_check), onClick = onRetry)
                TvActionButton(text = stringResource(R.string.common_back), onClick = onBack)
            },
        ) {
            if (event.title.isNotBlank()) Text(event.title)
            val start = event.scheduledStartMs
            if (start != null) {
                Text(stringResource(R.string.youtube_live_start_at, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(start))))
                val countdown = youtubeLiveCountdown(start, now)
                Text(if (countdown != null) stringResource(R.string.youtube_live_countdown, countdown)
                    else stringResource(R.string.youtube_live_delayed))
            } else Text(stringResource(R.string.youtube_live_unknown_time))
            if (state.waitingForLive) Text(stringResource(R.string.youtube_live_waiting_hint))
            if (state.loading) Text(stringResource(R.string.youtube_live_checking))
            if (state.error != null) Text(stringResource(R.string.youtube_live_check_failed))
        }
    }
}
