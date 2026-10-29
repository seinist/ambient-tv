package com.ambient.tv.ui.common

import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.sound.LocalUiSoundPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.ambient.tv.R
import com.ambient.tv.domain.model.MediaItem
import com.ambient.tv.ui.theme.LocalUiTokens
import com.ambient.tv.ui.common.activateOnTvKey
import kotlinx.coroutines.delay

private val POSTER_WIDTH = 160.dp
private val EPISODE_WIDTH = 252.dp
private val CONTINUE_WIDTH = 252.dp
/** Nearly opaque: a poster's own bright tagline often sits right where the metadata text goes. */
private val MetadataBannerFill = Color.Black.copy(alpha = 0.58f)

/**
 * Plex-style rows under a hero (Home, recommendations on TV): the hero already shows title,
 * year and metadata, so tiles drop their captions and the focused poster's metadata banner.
 * Library grids keep both.
 */
val LocalHeroRowTiles = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Tile size factors in those rows. The hero is a share of the screen, so its tiles are too:
 * independent of the UI scale (4K starts at 70 %), one row fills the space under it.
 */
data class HeroRowTileScale(val poster: Float = 1f, val landscape: Float = 1f)

val LocalHeroRowTileScale = androidx.compose.runtime.staticCompositionLocalOf { HeroRowTileScale() }

/**
 * [LocalHeroRowTileScale] for this screen: posters 1 at 100 % on a 540 dp tall TV screen.
 * [rowsArea] is the height under the hero; a page header of fixed dp leaves less of it, so each
 * row is capped to fit there. Landscape tiles are flat and grow further to fill the same space.
 */
@Composable
fun heroRowTileScale(rowsArea: Dp): HeroRowTileScale {
    if (LocalHandheldUi.current) return HeroRowTileScale()
    val height = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp()
    }
    val poster = minOf(height / 540.dp, (rowsArea - HeroRowChrome) / (POSTER_WIDTH * 1.5f)).coerceIn(0.5f, 2f)
    val landscape = minOf(poster * 1.25f, (rowsArea - HeroRowChrome) / (EPISODE_WIDTH * 9f / 16f)).coerceIn(0.5f, 2.5f)
    return HeroRowTileScale(poster, landscape)
}

// Row heading, paddings, focus growth and a margin to the screen edge.
private val HeroRowChrome = 85.dp

enum class MediaTileStyle { AUTO, POSTER, LANDSCAPE, EPISODE, CONTINUE }

fun resolveMediaTileStyle(style: MediaTileStyle, itemType: String, handheld: Boolean): MediaTileStyle = when (style) {
    MediaTileStyle.AUTO -> if (handheld || itemType != "episode") MediaTileStyle.POSTER else MediaTileStyle.EPISODE
    else -> style
}

fun mediaTileImageUrl(item: MediaItem, style: MediaTileStyle): String? = when (style) {
    MediaTileStyle.POSTER -> item.posterArtUrl
    MediaTileStyle.LANDSCAPE, MediaTileStyle.EPISODE, MediaTileStyle.CONTINUE -> item.landscapeArtUrl
    MediaTileStyle.AUTO -> item.posterArtUrl
}

@Composable
fun MediaTile(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    requestInitialFocus: Boolean = false,
    style: MediaTileStyle = MediaTileStyle.AUTO,
    showDescription: Boolean = true,
    /** Set for rows that contain episodes so every tile reserves the episode's extra label line. */
    reserveEpisodeLines: Boolean = false,
    /** Grid cells: take the cell's width so fewer columns mean bigger tiles, not wider gaps. */
    fillCell: Boolean = false,
    /** Menu, Info, or a long press. OK and a short tap stay [onClick]. */
    onInfo: (() -> Unit)? = null,
) {
    val handheld = LocalHandheldUi.current
    val heroRowTiles = LocalHeroRowTiles.current
    val resolvedStyle = resolveMediaTileStyle(style, item.type, handheld)
    val uhdScale = rememberUhdScale()
    val width = when (resolvedStyle) {
        MediaTileStyle.POSTER -> deviceDp(POSTER_WIDTH, 132.dp)
        MediaTileStyle.LANDSCAPE, MediaTileStyle.EPISODE, MediaTileStyle.CONTINUE -> deviceDp(EPISODE_WIDTH, 240.dp)
        MediaTileStyle.AUTO -> deviceDp(POSTER_WIDTH, 132.dp)
    } * uhdScale * when {
        !heroRowTiles -> 1f
        resolvedStyle == MediaTileStyle.POSTER || resolvedStyle == MediaTileStyle.AUTO -> LocalHeroRowTileScale.current.poster
        else -> LocalHeroRowTileScale.current.landscape
    }
    val aspectRatio = when (resolvedStyle) {
        MediaTileStyle.POSTER, MediaTileStyle.AUTO -> 2f / 3f
        MediaTileStyle.LANDSCAPE, MediaTileStyle.EPISODE, MediaTileStyle.CONTINUE -> 16f / 9f
    }

    var focused by remember { mutableStateOf(false) }
    val tokens = LocalUiTokens.current
    val performance = LocalPerformanceProfile.current
    val focusRequester = remember { FocusRequester() }
    val focusScale by animateFloatAsState(
        targetValue = if (focused) tokens.focusScale else 1f,
        animationSpec = if (performance.allowSpringFocus) {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            )
        } else {
            snap()
        },
        label = "mediaTileScale",
    )
    // One transform animation is enough to communicate focus. Animating elevation, border width,
    // border color and overlay separately invalidated both the old and new tile on every frame.
    val focusElevation = when {
        !performance.allowFocusedItemShadow -> 0.dp
        focused -> 5.dp
        else -> 0.dp // Do not rasterize a separate shadow for every entering/unfocused card.
    }
    val borderWidth = if (focused) 2.dp else 1.dp
    val borderColor = if (focused) MaterialTheme.colorScheme.onSurface.copy(alpha = .90f) else Color.Transparent
    val filterFocus = LocalFilterFocusState.current
    // A suppressed restore is consumed. Re-entering content must not restart an old tile request.
    LaunchedEffect(requestInitialFocus, filterFocus) {
        if (requestInitialFocus && filterFocus?.allowContentAutoFocus != false && !handheld) {
            delay(120)
            if (filterFocus?.allowContentAutoFocus != false) runCatching { focusRequester.requestFocus() }
        }
    }
    // The focus scale/shadow visually grow beyond the tile's own layout bounds (Compose
    // doesn't reserve extra space for scale(), and an unclipped shadow paints outside them
    // too). Reserving a gutter here - rather than just relying on the row/grid arrangement
    // spacing - keeps the enlarged focused tile from painting over its own label or the
    // neighboring tile regardless of which container it's placed in.
    Column(
        // Grid cells hand every item their full, often wider, width. Keep the tile at its own
        // width there so the centered labels sit under the artwork, not under the whole cell.
        modifier = if (fillCell) modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
        else modifier.wrapContentWidth(Alignment.Start).padding(horizontal = 8.dp, vertical = 8.dp).width(width),
    ) {
        val uiSounds = LocalUiSoundPlayer.current
        val activate = com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = onClick)
        val openInfo = onInfo?.let { info -> com.ambient.tv.ui.sound.rememberUiSoundAction(onClick = info) }
        val tileShape = RoundedCornerShape(tokens.cardRadius)
        Surface(
            onClick = activate,
            // Surface applies its own default focus-scale on top of whatever scale we set via
            // the graphics-layer transform below - left at the default, the two compounded to grow well
            // beyond what the reserved padding above accounted for, and the tile bled into its
            // own label and neighboring tiles again despite that padding.
            scale = ClickableSurfaceDefaults.scale(scale = 1f, focusedScale = 1f),
            border = noSurfaceBorder(),
            shape = ClickableSurfaceDefaults.shape(shape = tileShape),
            modifier = Modifier
                .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(width))
                .aspectRatio(aspectRatio)
                .activateOnTvKey(onClick = activate, onLongClick = openInfo)
                .graphicsLayer {
                    // Read animation state only when updating the layer, not while composing
                    // artwork, labels, metadata and playback badges on every spring frame.
                    scaleX = focusScale
                    scaleY = focusScale
                }
                .then(
                    // Only the focused tile gets a shadow layer; unfocused tiles in a dense grid
                    // carry no shadow modifier at all.
                    if (performance.allowFocusedItemShadow && focused) {
                        Modifier.shadow(focusElevation, RoundedCornerShape(tokens.cardRadius), clip = false)
                    } else Modifier
                )
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    focused = state.isFocused
                    if (state.isFocused) {
                        uiSounds?.playFocus()
                        onFocused()
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = borderWidth,
                        color = com.ambient.tv.ui.display.uiHdrHighlight(borderColor,
                            if (focused) com.ambient.tv.ui.display.LocalUiHdrState.current?.headroom ?: 1f else 1f),
                        shape = tileShape,
                    )
                    .padding(2.dp),
            ) {
                val imageUrl = mediaTileImageUrl(item, resolvedStyle)
                MediaArtworkWithStreamIcons(
                    item = item,
                    model = imageUrl,
                    title = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(tileShape),
                )

                val progress = item.progressFraction?.takeIf { it > 0f && it < 1f }

                // Only while focused, so unfocused posters stay clean.
                if (!heroRowTiles) MediaMetadataOverlay(
                    item = item,
                    focused = focused,
                    // The progress bar runs along the bottom edge; the text stays clear of it.
                    bottomInset = if (progress != null) PlaybackStatusBarHeight else 0.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = tokens.cardRadius, bottomEnd = tokens.cardRadius)),
                )

                // One spot for the watch state on every tile: a check once seen, otherwise the
                // number of episodes still to watch.
                val watchBadge = if (item.isWatched) "✓" else item.unwatchedLeafCount?.takeIf { it > 0 }?.toString()
                watchBadge?.let {
                    MetaBadge(text = it, emphasized = true, modifier = Modifier.align(Alignment.TopEnd).padding(7.dp))
                }
                // Resolution mirrors it in the opposite corner instead of crowding the metadata banner.
                if (remember(item) { isUhd(item) }) {
                    MetaBadge(text = UHD_LABEL, emphasized = true, modifier = Modifier.align(Alignment.TopStart).padding(7.dp))
                }

                progress?.let { fraction ->
                    PlaybackStatusBar(
                        progress = fraction,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                    )
                }
            }
        }

        // Extra gap so the poster's focus-scale growth never bleeds down into its own
        // label text below (the outer Column padding only guards the top/bottom of the
        // whole tile, not this internal seam).
        if (!heroRowTiles) {
            Spacer(modifier = Modifier.height(6.dp))
            when (resolvedStyle) {
                // A season's own episode list already sits under the series; only feed rows repeat it.
                MediaTileStyle.LANDSCAPE, MediaTileStyle.CONTINUE -> EpisodeLabels(item, focused, showDescription, showSeriesTitle = true, reserveEpisodeLines = reserveEpisodeLines)
                MediaTileStyle.EPISODE -> EpisodeLabels(item, focused, showDescription)
                else -> PosterLabels(item, focused, reserveEpisodeLines)
            }
        }
    }
}

/** Plex-style season/episode marker ("S1 · E3"); the bare episode number when the season is unknown. */
@Composable
private fun seasonEpisodeLabel(item: MediaItem): String? {
    val episode = item.index ?: return null
    return item.parentIndex?.let { season -> "S$season · E$episode" }
        ?: stringResource(R.string.media_episode_short_label, episode)
}

@Composable
private fun SecondaryLabel(text: String, focused: Boolean = false) {
    CenteredMarqueeTitle(
        text = text,
        focused = focused,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The closing facts line (year, season and episode, runtime), centered under the titles. */
@Composable
private fun MetadataLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MediaMetadataOverlay(
    item: MediaItem,
    focused: Boolean,
    modifier: Modifier = Modifier,
    bottomInset: Dp = 0.dp,
) {
    // The metadata strip sits on the artwork; draw it only while this tile is focused.
    if (!focused) return
    val primaryLine = focusedPrimaryMetadata(item)
    val highlightLine = remember(item) { focusedTechnicalHighlights(item) }
    if (primaryLine.isBlank() && highlightLine.isBlank()) return

    Column(
        modifier = modifier
            .background(MetadataBannerFill)
            .padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (primaryLine.isNotBlank()) {
            Text(
                text = primaryLine,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = Color.White.copy(alpha = 0.96f),
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
        if (highlightLine.isNotBlank()) {
            Text(
                text = highlightLine,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The same four slots for every provider and type: year, length, age rating, score. */
@Composable
private fun focusedPrimaryMetadata(item: MediaItem): String = buildList {
    item.year?.let { add(it.toString()) }
    focusedLengthLabel(item)?.let(::add)
    item.contentRating?.takeIf { it.isNotBlank() }?.let { rating ->
        val age = parseContentAge(rating)
        add(age?.let { "$it+" } ?: rating.trim())
    }
    primaryFiveStarScore(item)?.let { add("★ %.1f".format(it)) }
}.joinToString(" · ")

/** How long a title runs, or how much a show, season or collection holds. */
@Composable
private fun focusedLengthLabel(item: MediaItem): String? = when (item.type) {
    // Plex reports a typical episode runtime as a show's duration, Jellyfin and Emby often none.
    "show", "season" -> item.leafCount?.takeIf { it > 0 }?.let { stringResource(R.string.media_episode_count, it) }
    "collection" -> item.leafCount?.takeIf { it > 0 }?.let { stringResource(R.string.collection_item_count, it) }
    else -> formatDuration(item.durationMs)
}

private fun primaryFiveStarScore(item: MediaItem): Double? = when {
    item.rating != null -> normalizeRatingToFive(item.rating, 10.0)
    item.audienceRating != null -> normalizeRatingToFive(item.audienceRating, 10.0)
    item.criticRating != null -> normalizeRatingToFive(item.criticRating, 100.0)
    else -> null
}

private const val UHD_LABEL = "4K"

private fun isUhd(item: MediaItem): Boolean = MediaItemStreamProperties(item).any { it.label == UHD_LABEL }

/** HDR and object audio; 4K has its own corner badge. */
private fun focusedTechnicalHighlights(item: MediaItem): String {
    val highlights = MediaItemStreamProperties(item)
        .mapNotNull { property ->
            val label = property.label
            when {
                label.contains("DOLBY VISION", ignoreCase = true) -> label
                label.startsWith("HDR", ignoreCase = true) -> label
                label.equals("HLG", ignoreCase = true) -> label
                label.contains("ATMOS", ignoreCase = true) -> "ATMOS"
                else -> null
            }
        }
        .distinct()
        .take(3)
    return highlights.joinToString(" · ")
}

@Composable
private fun PosterLabels(item: MediaItem, focused: Boolean, reserveEpisodeLines: Boolean = false) {
    CenteredMarqueeTitle(
        text = item.posterTitle,
        focused = focused,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 7.dp),
    )
    // Episodes read like Plex: series title, episode name, then season and episode number.
    // Seasons carry the series poster title; their own name ("Season 1") is what tells them apart.
    val episodeTitle = item.title.takeIf { item.type in setOf("episode", "season") && it.isNotBlank() && it != item.posterTitle }
    val secondary = buildList {
        when (item.type) {
            "episode" -> seasonEpisodeLabel(item)?.let(::add)
            "show", "season" -> {
                item.year?.let { add(it.toString()) }
                item.leafCount?.let { add(stringResource(R.string.media_episode_count, it)) }
            }
            else -> item.year?.let { add(it.toString()) }
        }
    }.joinToString(" · ")
    episodeTitle?.let { SecondaryLabel(it, focused) }
    if (secondary.isNotBlank()) MetadataLabel(secondary)
    if (reserveEpisodeLines) {
        // Keeps the row height stable while tiles with and without an episode line scroll past.
        repeat(2 - listOfNotNull(episodeTitle, secondary.takeIf { it.isNotBlank() }).size) { SecondaryLabel("") }
    }
}

@Composable
private fun EpisodeLabels(
    item: MediaItem,
    focused: Boolean,
    showDescription: Boolean = true,
    showSeriesTitle: Boolean = false,
    reserveEpisodeLines: Boolean = false,
) {
    val seriesTitle = item.grandparentTitle?.takeIf { showSeriesTitle && item.type == "episode" && it.isNotBlank() }
    if (seriesTitle != null) {
        CenteredMarqueeTitle(
            text = seriesTitle,
            focused = focused,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 7.dp),
        )
        SecondaryLabel(item.title, focused)
        MetadataLabel(listOfNotNull(seasonEpisodeLabel(item), formatDuration(item.durationMs)).joinToString(" · "))
    } else {
        val metadata = buildList {
            item.parentTitle?.takeIf { it.isNotBlank() }?.let(::add)
            item.index?.let { add(stringResource(R.string.media_episode_short_label, it)) }
            formatDuration(item.durationMs)?.let(::add)
        }.joinToString(" · ")
        CenteredMarqueeTitle(
            text = item.title,
            focused = focused,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 7.dp),
        )
        if (metadata.isNotBlank()) MetadataLabel(metadata)
        // Keeps the row height stable while tiles with and without a series line scroll past.
        if (reserveEpisodeLines) repeat(if (metadata.isBlank()) 2 else 1) { SecondaryLabel("") }
    }
    if (showDescription) {
        // Reserve 2 lines whenever descriptions are enabled so tiles in the same row keep
        // identical heights. Recommendation rows disable this block entirely.
        Text(
            text = item.summary?.takeIf { it.isNotBlank() } ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            minLines = 2,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
    if (item.progressFraction != null && !item.isWatched) {
        item.remainingMs?.let { remaining ->
            Text(
                text = stringResource(R.string.media_remaining, formatDuration(remaining) ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** [showDescription] mirrors [MediaTile]'s flag: off, only the title line sits under the artwork. */
@Composable
fun SkeletonTile(
    modifier: Modifier = Modifier,
    style: MediaTileStyle = MediaTileStyle.POSTER,
    showDescription: Boolean = true,
    /** False for a static placeholder (detail rows). Sparsam stays flat either way. */
    shimmer: Boolean = true,
    /** Match [MediaTile]'s fillCell so grid placeholders have the loaded tiles' size. */
    fillCell: Boolean = false,
) {
    if (shimmer && !LocalPerformanceProfile.current.reducedEffects) {
        ShimmeringSkeletonTile(modifier, style, showDescription, fillCell)
    } else {
        SkeletonTileContent(modifier, style, showDescription, fillCell) { 0.45f }
    }
}

@Composable
private fun ShimmeringSkeletonTile(
    modifier: Modifier,
    style: MediaTileStyle,
    showDescription: Boolean,
    fillCell: Boolean,
) {
    // One shared pulse for every placeholder on screen instead of one infinite transition per
    // tile (up to 72 while a grid loads). Read at draw time, so tiles do not recompose per frame.
    val pulse = rememberSharedPulse(SharedSkeletonPulse)
    SkeletonTileContent(modifier, style, showDescription, fillCell) { pulse.value }
}

@Composable
private fun SkeletonTileContent(
    modifier: Modifier,
    style: MediaTileStyle,
    showDescription: Boolean,
    fillCell: Boolean,
    alpha: () -> Float,
) {
    val tokens = LocalUiTokens.current
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val tileShape = RoundedCornerShape(tokens.cardRadius)
    val textShape = RoundedCornerShape(3.dp)
    val uhdScale = rememberUhdScale()
    val resolvedStyle = when (style) {
        MediaTileStyle.AUTO -> MediaTileStyle.POSTER
        else -> style
    }
    val tileWidth = when (resolvedStyle) {
        MediaTileStyle.POSTER -> deviceDp(POSTER_WIDTH, 132.dp)
        MediaTileStyle.LANDSCAPE, MediaTileStyle.EPISODE, MediaTileStyle.CONTINUE -> deviceDp(EPISODE_WIDTH, 240.dp)
        MediaTileStyle.AUTO -> deviceDp(POSTER_WIDTH, 132.dp)
    } * uhdScale
    val tileAspect = when (resolvedStyle) {
        MediaTileStyle.POSTER, MediaTileStyle.AUTO -> 2f / 3f
        else -> 16f / 9f
    }
    Column(
        modifier = (if (fillCell) modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)
        else modifier.wrapContentWidth(Alignment.Start).padding(horizontal = 8.dp, vertical = 8.dp).width(tileWidth))
            .graphicsLayer {
                // Animate only this placeholder, not every loaded card or its parent row.
                this.alpha = alpha()
                compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
            },
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .then(if (fillCell) Modifier.fillMaxWidth() else Modifier.width(tileWidth))
                .aspectRatio(tileAspect)
                .clip(tileShape).drawBehind { drawRect(fill) },
        )
        // Placeholders match the loaded tile, which has no captions under a hero.
        val captions = !LocalHeroRowTiles.current
        if (captions) Box(
            Modifier
                .fillMaxWidth(0.82f)
                .height(13.dp)
                .clip(textShape).drawBehind { drawRect(fill, alpha = 0.88f) },
        )
        if (captions && showDescription) Box(
            Modifier
                .fillMaxWidth(0.52f)
                .height(10.dp)
                .clip(textShape).drawBehind { drawRect(fill, alpha = 0.72f) },
        )
    }
}
