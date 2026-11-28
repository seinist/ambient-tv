package com.ambient.tv.ui.games

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ambient.tv.ui.common.pageHeaderTop
import com.ambient.tv.ui.theme.screenGutter

/** Keep the utility/header area reserved on both TV and handheld layouts. */
@Composable
internal fun gameDetailContentPadding(): PaddingValues = PaddingValues(
    start = screenGutter(), end = screenGutter(),
    top = pageHeaderTop(screenGutter()) + 70.dp, bottom = 24.dp,
)

/** Shared responsive hero for remote game catalogues; only provider-specific actions differ. */
@Composable
internal fun GameDetailHero(
    cover: Any?, title: String, systemId: String, aspectRatio: Float,
    systemIcon: Int? = null, information: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        val size = gameCoverFrameSize(
            if (compact) minOf(maxWidth, 340.dp) else minOf(maxWidth * .42f, 440.dp),
            if (compact) 220.dp else 315.dp, aspectRatio,
        )
        val artwork: @Composable () -> Unit = {
            GameCoverArtwork(cover, title, systemId, Modifier.size(size), systemIcon = systemIcon)
        }
        if (compact) Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { artwork() }
            information()
        } else Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
            artwork()
            Column(Modifier.weight(1f)) { information() }
        }
    }
}
