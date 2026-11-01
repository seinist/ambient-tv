package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Monogram source cards use the same visual language as SmartTube's 16:9 Card:
 * a single focused surface, 7dp rounded artwork, 44dp title and one metadata line.
 * The existing MediaTile remains unchanged for other providers. */
/** SmartTube-like horizontal rows use viewport-based, density-aware widths. */
internal val LocalMonogramRowCardWidth =
    androidx.compose.runtime.staticCompositionLocalOf<androidx.compose.ui.unit.Dp?> { null }

internal fun isMonogramMediaTile(item: MediaItem): Boolean =
    item.ratingKey.startsWith("telegram:") ||
    item.ratingKey.startsWith("telegram-youtube:") ||
    item.ratingKey.startsWith("telegram-channel:")

/** A stable-height card only gets fields that the originating source actually supplied. */
internal fun monogramCardMeta(
    item: MediaItem,
    dateLabel: (Long) -> String,
    durationLabel: (Long) -> String?,
): String = buildList {
    item.parentTitle?.takeIf(String::isNotBlank)?.let(::add)
    item.addedAt?.takeIf { it > 0L }?.let { add(dateLabel(it)) }
    item.durationMs?.takeIf { it > 0L }?.let { durationLabel(it)?.let(::add) }
}.joinToString("  ·  ")

/** The same thumbnail treatment is used by rows, grids and lists. */
@Composable
internal fun MonogramArtwork(
    item: MediaItem,
    style: MediaTileStyle,
    modifier: Modifier = Modifier,
) {
    val imageUrl = mediaTileImageUrl(item, style)
    if (item.ratingKey.startsWith("telegram-channel:")) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .4f)),
            contentAlignment = Alignment.Center) {
            if (imageUrl != null) ArtworkImage(
                imageUrl, item.title,
                Modifier.fillMaxHeight(.82f).aspectRatio(1f).clip(CircleShape),
                ContentScale.Crop,
            ) else Icon(
                Icons.Filled.AccountCircle, contentDescription = item.title,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxHeight(.64f).aspectRatio(1f),
            )
        }
    } else ArtworkImage(imageUrl, item.title, modifier, ContentScale.Crop)
}

@Composable
internal fun MonogramMediaCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    requestInitialFocus: Boolean = false,
    style: MediaTileStyle = MediaTileStyle.LANDSCAPE,
    fillCell: Boolean = false,
    onInfo: (() -> Unit)? = null,
) {
    val handheld = LocalHandheldUi.current
    val resolvedStyle = resolveMediaTileStyle(style, item.type, handheld)
    val aspect = if (resolvedStyle in setOf(MediaTileStyle.POSTER, MediaTileStyle.AUTO)) 2f / 3f else 16f / 9f
    val width = LocalMonogramRowCardWidth.current ?: mediaTileWidth(resolvedStyle)
    val shape = RoundedCornerShape(10.dp)
    val artworkShape = RoundedCornerShape(7.dp)
    val heroRow = LocalHeroRowTiles.current
    val uiSounds = LocalUiSoundPlayer.current
    val filterFocus = LocalFilterFocusState.current
    val browseFocus = LocalBrowseFocusReturnState.current
    val focusRequester = remember { FocusRequester() }
    var focused by remember(item.ratingKey) { mutableStateOf(false) }
    val activate = com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = onClick)
    val info = onInfo?.let { com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = it) }

    LaunchedEffect(requestInitialFocus, filterFocus, browseFocus) {
        if (browseFocus?.managesInitialFocus == true) return@LaunchedEffect
        if (requestInitialFocus && filterFocus?.allowContentAutoFocus != false && !handheld) {
            delay(120)
            if (filterFocus?.allowContentAutoFocus != false)
                runCatching { focusRequester.requestFocus() }
        }
    }

    val meta = remember(item, androidx.compose.ui.platform.LocalConfiguration.current.locales[0]) {
        monogramCardMeta(item,
            dateLabel = { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
            durationLabel = { formatPlaybackClock(it) },
        )
    }
    // SmartTube cards are the full row/grid cell. Adding MediaTile's outer 8dp
    // gutters here would shrink the artwork and break density/placeholder alignment.
    val surfaceModifier = if (fillCell) modifier.fillMaxWidth()
        else modifier.wrapContentWidth(Alignment.Start).width(width)
    Surface(
        onClick = activate,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = surfaceModifier
            .focusRequester(focusRequester)
            .browseFocusTarget(item.ratingKey)
            .mediaFocusMotion(focused)
            .activateOnTvKey(onClick = activate, onLongClick = info)
            .onFocusChanged { focus ->
                focused = focus.isFocused
                if (focus.isFocused) {
                    uiSounds?.playFocus()
                    onFocused()
                }
            },
    ) {
        Column(
            modifier = Modifier
                .background(
                    if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                    else MaterialTheme.colorScheme.surface.copy(alpha = .58f),
                    shape,
                )
                .border(
                    if (focused) 2.dp else 1.dp,
                    if (focused) MaterialTheme.colorScheme.primary
                    else Color.White.copy(alpha = .06f),
                    shape,
                )
                .padding(if (heroRow) 3.dp else 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            MonogramArtwork(
                item = item,
                style = resolvedStyle,
                modifier = Modifier.fillMaxWidth().aspectRatio(aspect).clip(artworkShape),
            )
            if (!heroRow) {
                Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                    CenteredMarqueeTitle(
                        text = item.title,
                        focused = focused,
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                CenteredMarqueeTitle(
                    text = meta,
                    focused = focused,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
