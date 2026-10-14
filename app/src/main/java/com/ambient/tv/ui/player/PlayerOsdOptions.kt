package com.ambient.tv.ui.player

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.mobile.LocalHandheldUi

private val techBadgesVisibleState = mutableStateOf<Boolean?>(null)

@Composable
fun playerTechBadgesVisible(): Boolean {
    val context = LocalContext.current
    val preferences = remember(context) { videoImagePreferences(context) }
    val state = remember(preferences) {
        if (techBadgesVisibleState.value == null) {
            techBadgesVisibleState.value = preferences.playerTechBadgesVisible
        }
        techBadgesVisibleState
    }
    return state.value != false
}

fun setPlayerTechBadgesVisible(preferences: AppPreferences, visible: Boolean) {
    techBadgesVisibleState.value = visible
    preferences.playerTechBadgesVisible = visible
}

@Composable
fun PlayerTechBadgeRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    if (!playerTechBadgesVisible()) return
    Row(
        // Measure the badges at their full width on handhelds. If they do not fit,
        // scroll the whole line instead of squeezing the final codecs into tall columns.
        modifier = modifier.then(
            if (LocalHandheldUi.current) Modifier.basicMarquee(iterations = Int.MAX_VALUE)
            else Modifier,
        ),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
