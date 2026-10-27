package com.ambient.tv.ui.player

import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.ui.common.LocalPlayerPlayPauseFocus

/**
 * Shared player chrome based on the Plex/Jellyfin player. Provider-specific players should use
 * these primitives instead of creating their own OSD geometry, typography or clock treatment.
 */
@Composable
fun ReferencePlayerOverlayBackground(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.04f),
                    0.48f to Color.Transparent,
                    0.72f to Color.Black.copy(alpha = 0.42f),
                    1f to Color.Black.copy(alpha = 0.94f),
                ),
            ),
    )
}

@Composable
fun BoxScope.ReferencePlayerClock(
    modifier: Modifier = Modifier,
    liveLabel: String? = null,
) {
    val now = java.time.LocalTime.now()
    val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(now.format(formatter), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        if (!liveLabel.isNullOrBlank()) {
            Text(
                liveLabel,
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
fun ReferencePlayerHeader(
    title: String,
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    contextLine: String? = null,
    subtitle: String? = null,
    artworkFallbackUrls: List<String> = emptyList(),
    artworkHeaders: Map<String, String> = emptyMap(),
    artworkAspectRatio: Float = 16f / 9f,
    artworkContentScale: ContentScale = ContentScale.Crop,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!artworkUrl.isNullOrBlank() || artworkFallbackUrls.isNotEmpty()) {
            PlaybackArtworkCard(
                artworkUrl = artworkUrl,
                fallbackUrls = artworkFallbackUrls,
                title = title,
                headers = artworkHeaders,
                contentScale = artworkContentScale,
                modifier = Modifier.height(deviceDp(104.dp, 56.dp)).aspectRatio(artworkAspectRatio),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            contextLine?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = title,
                color = Color.White,
                fontSize = if (LocalHandheldUi.current) 20.sp else 27.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                // Handheld: scroll a title wider than the screen instead of cutting it off.
                overflow = if (LocalHandheldUi.current) TextOverflow.Clip else TextOverflow.Ellipsis,
                modifier = if (LocalHandheldUi.current) Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 1_500) else Modifier,
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Shared by the OSD, its bottom block and the action bar: [fraction] is how far the bar has slid
 * away (0..1), and the block above it follows it down. [closing] stows the bar before the fade.
 */
internal class PlayerOsdStow {
    var fraction by mutableFloatStateOf(0f)
    var closing by mutableStateOf(false)
}

internal val LocalPlayerOsdStow = compositionLocalOf<PlayerOsdStow?> { null }

/** Action bar height plus the block spacing: the gap the stowed bar leaves behind. */
private val StowedBarGap = 71.dp

@Composable
fun BoxScope.ReferencePlayerBottomContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val playPauseFocus = remember { FocusRequester() }
    val controlFocus = LocalPlayerControlFocus.current ?: remember { PlayerControlFocus() }
    val stow = LocalPlayerOsdStow.current
    CompositionLocalProvider(
        LocalPlayerPlayPauseFocus provides playPauseFocus,
        LocalPlayerControlFocus provides controlFocus,
    ) {
        Column(
            modifier = modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(if (LocalHandheldUi.current) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(start = deviceDp(58.dp, 16.dp), end = deviceDp(58.dp, 16.dp), bottom = deviceDp(32.dp, 12.dp))
                .then(stow?.let { Modifier.graphicsLayer { translationY = if (it.closing) 0f else it.fraction * StowedBarGap.toPx() } } ?: Modifier),
            verticalArrangement = Arrangement.spacedBy(deviceDp(13.dp, 6.dp)),
        ) {
            content()
        }
    }
}
