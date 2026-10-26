package com.ambient.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import kotlinx.coroutines.delay

/** A quick switch closes the old player within this time; only a longer wait shows the hint. */
internal const val PREVIOUS_PLAYER_HINT_DELAY_MS = 700L

internal fun previousPlayerHintVisible(waiting: Boolean, waitedMs: Long): Boolean =
    waiting && waitedMs >= PREVIOUS_PLAYER_HINT_DELAY_MS

/**
 * "Vorheriger Player wird beendet…" while a renderer switch waits for the old player to close
 * its output. Not focusable: the panel and the remote keep working, and no dialog opens.
 */
@Composable
internal fun PreviousPlayerClosingHint(waiting: Boolean) {
    var waitedLongEnough by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        waitedLongEnough = false
        if (!waiting) return@LaunchedEffect
        delay(PREVIOUS_PLAYER_HINT_DELAY_MS)
        waitedLongEnough = true
    }
    if (!previousPlayerHintVisible(waiting, if (waitedLongEnough) PREVIOUS_PLAYER_HINT_DELAY_MS else 0L)) return
    val shape = RoundedCornerShape(12.dp)
    Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, 48),
        properties = PopupProperties(focusable = false)) {
        com.ambient.tv.ui.display.SuspendUiHdrForPopup()
        Box(Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f), shape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.34f), shape)
            .padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.audio_output_release_pending),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
