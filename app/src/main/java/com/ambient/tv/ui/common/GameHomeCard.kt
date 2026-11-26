package com.ambient.tv.ui.common

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.games.GameCoverArtwork
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import com.ambient.tv.ui.theme.AccentMotionScope
import com.ambient.tv.ui.theme.screenGutter
import kotlinx.coroutines.delay

internal fun isGameHomeTile(item: MediaItem): Boolean =
    item.ratingKey.startsWith(com.ambient.tv.ui.home.GAME_HOME_ITEM_PREFIX) ||
        item.ratingKey.startsWith(com.ambient.tv.ui.home.MOONLIGHT_HOME_ITEM_PREFIX)

/** Title, then system and year, as on the game library's cards. */
internal fun gameHomeCardMeta(item: MediaItem): String =
    listOfNotNull(item.summary?.takeIf(String::isNotBlank), item.year?.toString()).joinToString(" · ")

/**
 * Game Home rows use the same SmartTube-style card as the game library: one flat card holds
 * the cover and its labels, and the whole card takes focus. Every game row shares one artwork
 * height; each card's width follows its cover, bounded so even extreme custom art stays
 * reachable. Other Home tiles keep [MediaTile]'s poster presentation.
 */
@Composable
internal fun GameHomeCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    requestInitialFocus: Boolean = false,
    fillCell: Boolean = false,
    /** A viewport-limited width for the whole card. */
    overrideWidth: Dp? = null,
    onInfo: (() -> Unit)? = null,
) {
    val handheld = LocalHandheldUi.current
    val heroRow = LocalHeroRowTiles.current
    val inset = if (heroRow) 3.dp else 8.dp
    val ratio = mediaTileGameCoverRatio(item) ?: 1f
    val artworkHeight = mediaTileWidth(MediaTileStyle.POSTER) * 1.5f
    val viewportWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val minimum = deviceDp(84.dp, 68.dp)
    // Screen gutters on both sides plus room for the focused card's growth.
    val maximum = minOf(artworkHeight * 2f, viewportWidth - screenGutter() * 2f - 16.dp - inset * 2f)
        .coerceAtLeast(minimum)
    val artworkWidth = overrideWidth?.let { (it - inset * 2f).coerceAtLeast(minimum) }
        ?: (artworkHeight * ratio).coerceIn(minimum, maximum)
    val shape = BrowseCardShape
    val uiSounds = LocalUiSoundPlayer.current
    val filterFocus = LocalFilterFocusState.current
    val browseFocus = LocalBrowseFocusReturnState.current
    val focusRequester = remember { FocusRequester() }
    var focused by remember(item.ratingKey) { mutableStateOf(false) }
    val activate = com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = onClick)
    val info = onInfo?.let { com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = it) }

    // A suppressed restore is consumed. Re-entering content must not restart an old tile request.
    LaunchedEffect(requestInitialFocus, filterFocus, browseFocus) {
        if (browseFocus?.managesInitialFocus == true) return@LaunchedEffect
        if (requestInitialFocus && filterFocus?.allowContentAutoFocus != false && !handheld) {
            delay(120)
            if (filterFocus?.allowContentAutoFocus != false) runCatching { focusRequester.requestFocus() }
        }
    }

    // Vertical room keeps the same row rhythm as the poster tiles and lets focus growth show.
    val outer = modifier.padding(vertical = 8.dp)
    Surface(
        onClick = activate,
        colors = transparentSurfaceColors(),
        scale = noSurfaceScale(),
        shape = ClickableSurfaceDefaults.shape(shape),
        modifier = (if (fillCell) outer.fillMaxWidth()
            else outer.wrapContentWidth(Alignment.Start).width(artworkWidth + inset * 2f))
            .focusRequester(focusRequester)
            .browseFocusTarget(item.ratingKey)
            .mediaFocusMotion(focused)
            .activateOnTvKey(onClick = activate, onLongClick = info)
            .testTag("game-home-card:${item.ratingKey}")
            .onFocusChanged { focus ->
                focused = focus.isFocused
                if (focus.isFocused) {
                    uiSounds?.playFocus()
                    onFocused()
                }
            },
    ) {
        AccentMotionScope(active = focused) {
            Column(
                Modifier.browseCardSurface(focused, shape).padding(inset),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    (if (fillCell) Modifier.fillMaxWidth().aspectRatio(ratio)
                        else Modifier.width(artworkWidth).height(artworkHeight))
                        .clip(BrowseCardArtworkShape)
                        .testTag("game-home-cover:${item.ratingKey}"),
                ) {
                    val moonlight = com.ambient.tv.ui.home.parseMoonlightHomeKey(item.ratingKey)
                    GameCoverArtwork(
                        cover = mediaTileImageUrl(item, MediaTileStyle.POSTER),
                        title = item.title,
                        systemId = item.ratingKey.substringAfterLast('|', ""),
                        modifier = Modifier.fillMaxSize(),
                        focused = focused,
                        fileId = moonlight?.let { "moonlight:${it.first}:${it.second}" } ?: Uri.decode(item.ratingKey.removePrefix(com.ambient.tv.ui.home.GAME_HOME_ITEM_PREFIX)
                            .substringBeforeLast('|')),
                        systemIcon = if (moonlight != null) com.ambient.tv.R.drawable.system_moonlight else null,
                    )
                }
                if (!heroRow) {
                    CenteredMarqueeTitle(
                        text = item.title,
                        focused = focused,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    // Always one line, so a row of cards keeps one height.
                    CenteredMarqueeTitle(
                        text = gameHomeCardMeta(item),
                        focused = focused,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
