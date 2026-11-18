package com.ambient.tv.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.player.PlayerOsdProgressTrack
import com.ambient.tv.ui.theme.AppBackground
import com.ambient.tv.ui.theme.AppOutline
import com.ambient.tv.ui.theme.LocalUiTokens

/** Design width of the TV UI in dp; previews scale their overlays to the stage against it. */
internal val SCENE_DESIGN_WIDTH = 960.dp

/**
 * Shared stage for settings previews (clock, subtitles): a 16:9 frame with the card radius,
 * a quiet neutral contour and the same artwork as the accent preview, never ad-hoc gradients.
 * [caption] names the context the preview stands for (e.g. "Preview · Player").
 */
@Composable
internal fun SettingsScenePreview(
    caption: String,
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    val shape = RoundedCornerShape(LocalUiTokens.current.cardRadius)
    Column(modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(shape)
                .border(1.dp, AppOutline, shape),
        ) {
            Image(
                painter = painterResource(R.drawable.accent_preview_mountains),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            content()
        }
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Home hero: the artwork under the same left/top darkening the Home screen uses for its text. */
@Composable
internal fun BoxWithConstraintsScope.SceneHomeHero() {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to AppBackground.copy(alpha = .9f),
                .3f to AppBackground.copy(alpha = .5f), .55f to Color.Transparent))
            .background(Brush.horizontalGradient(0f to AppBackground.copy(alpha = .85f), .6f to Color.Transparent)),
    )
    SceneTitle(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp))
}

/** Browse header: the dark app background with a row of artwork cards below the header line. */
@Composable
internal fun BoxWithConstraintsScope.SceneBrowseHeader() {
    Box(Modifier.fillMaxSize().background(AppBackground))
    val cardShape = RoundedCornerShape(LocalUiTokens.current.cardRadius / 2)
    Row(
        Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(.5f)
            .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        repeat(4) {
            Image(
                painter = painterResource(R.drawable.accent_preview_mountains),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.weight(1f).fillMaxHeight().clip(cardShape),
            )
        }
    }
}

/** Player: the video under the OSD's top and bottom darkening, with its title and timeline. */
@Composable
internal fun BoxWithConstraintsScope.ScenePlayer(showTimeline: Boolean = true) {
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .7f), .35f to Color.Transparent))
            .background(Brush.verticalGradient(.65f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))),
    )
    if (!showTimeline) return
    Text(
        stringResource(R.string.settings_preview_title),
        style = MaterialTheme.typography.labelLarge, color = Color.White,
        modifier = Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp),
    )
    PlayerOsdProgressTrack(
        .42f,
        Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 16.dp),
    )
}

@Composable
private fun SceneTitle(modifier: Modifier) {
    Column(modifier) {
        Text(stringResource(R.string.settings_preview_title), style = MaterialTheme.typography.labelLarge, color = Color.White)
        Text("2026 · HDR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}
