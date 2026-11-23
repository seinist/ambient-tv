package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ambient.tv.R
import com.ambient.tv.ui.gamepad.retro.RetroPadButton

/** Full core action names remain readable even when a small touch button truncates its caption. */
@Composable
internal fun GameCoreInputActions(
    actions: Map<RetroPadButton, String>,
    presentation: GameTouchPresentation,
    title: String = stringResource(R.string.game_core_input_actions),
) {
    if (actions.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 6.dp))
        for ((button, action) in actions) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(presentation.labels[button] ?: button.key.uppercase(),
                modifier = Modifier.weight(.25f), style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(action, modifier = Modifier.weight(.75f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
