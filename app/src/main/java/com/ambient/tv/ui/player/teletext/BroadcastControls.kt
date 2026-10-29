@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.ambient.tv.ui.player.teletext

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.ambient.tv.R
import com.ambient.tv.ui.common.TvChip
import com.ambient.tv.ui.theme.LocalUiTokens

/** Shared broadcast controls use normal app focus and touch targets, without player OSD bindings. */
@Composable
internal fun BroadcastToolbar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), RoundedCornerShape(LocalUiTokens.current.panelRadius))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** Groups wrap on a phone instead of pushing later actions off-screen. */
@Composable
internal fun BroadcastControlRow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

private val broadcastKeyColors = listOf(
    Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFDD835), Color(0xFF00ACC1),
)

@Composable
internal fun BroadcastColorButton(
    index: Int,
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val color = broadcastKeyColors.getOrNull(index) ?: return
    val colorName = stringResource(when (index) {
        0 -> R.string.player_broadcast_color_red
        1 -> R.string.player_broadcast_color_green
        2 -> R.string.player_broadcast_color_yellow
        else -> R.string.player_broadcast_color_blue
    })
    TvChip(
        text = text,
        modifier = modifier.semantics {
            contentDescription = if (text == colorName) text else "$colorName · $text"
        },
        enabled = enabled,
        leadingContent = { Box(Modifier.size(10.dp).background(color, CircleShape)) },
        onClick = onClick,
    )
}
