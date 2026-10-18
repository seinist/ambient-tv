package com.ambient.tv.ui.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.AppDialog
import com.ambient.tv.ui.common.TvActionButton
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.dp

/**
 * Shown while an expired sleep timer holds playback paused. Keep watching (timer off), start the
 * same timer again, or stop now; with no answer the session stops when the countdown runs out.
 */
@Composable
fun SleepTimerPromptDialog() {
    val deadline = BackgroundPlaybackSession.sleepPromptDeadline ?: return
    val config = BackgroundPlaybackSession.sleepTimer
    var now by remember(deadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    val seconds = ((deadline - now + 999) / 1000).coerceAtLeast(0).toInt()
    val stopNow = { BackgroundPlaybackSession.stop() }
    AppDialog(
        // Back or outside is "no decision": the countdown keeps running, as if nothing was pressed.
        onDismiss = {},
        title = stringResource(R.string.sleep_prompt_title),
        footer = {
            TvActionButton(stringResource(R.string.sleep_prompt_continue), { BackgroundPlaybackSession.continueAfterSleep(rearm = false) }, selected = true)
            if (config.minutes > 0) {
                TvActionButton(
                    stringResource(R.string.sleep_prompt_rearm, config.minutes),
                    { BackgroundPlaybackSession.continueAfterSleep(rearm = true) },
                )
            }
            TvActionButton(stringResource(R.string.sleep_prompt_stop), stopNow)
        },
    ) {
        Text(stringResource(R.string.sleep_prompt_message, seconds), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Non-pausing notice before a timed sleep stop. On television OK extends by 30 min (handled in
 * MainActivity.dispatchKeyEvent, so the focused player control is not taken over); touch devices
 * get a button. Without an answer the normal pause-and-ask dialog follows at the deadline.
 */
@Composable
fun SleepTimerWarningOverlay(touch: Boolean) {
    val deadline = BackgroundPlaybackSession.sleepWarningDeadline ?: return
    var now by remember(deadline) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(deadline) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val minutes = sleepWarningMinutesLeft(deadline, now)
    androidx.compose.foundation.layout.Box(
        // Below the status bar / cutout on phones; TV has no insets.
        androidx.compose.ui.Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing).padding(24.dp),
        contentAlignment = androidx.compose.ui.Alignment.TopEnd,
    ) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.78f), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (touch) R.string.sleep_warning_message_touch else R.string.sleep_warning_message, minutes),
                style = MaterialTheme.typography.bodyMedium,
                color = androidx.compose.ui.graphics.Color.White,
            )
            if (touch) androidx.compose.material3.TextButton(onClick = { BackgroundPlaybackSession.extendSleepTimer() }) {
                androidx.compose.material3.Text(stringResource(R.string.sleep_extend_30))
            }
        }
    }
}

/** Whole minutes shown in the pre-warning, never "0 min" while the deadline is ahead. */
internal fun sleepWarningMinutesLeft(deadlineMs: Long, nowMs: Long): Int =
    ((deadlineMs - nowMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
