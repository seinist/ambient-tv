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
