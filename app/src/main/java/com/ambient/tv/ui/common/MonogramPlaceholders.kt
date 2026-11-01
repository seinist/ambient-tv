package com.ambient.tv.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDensityGridCells
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.theme.screenGutter

/*
 * Monogram (Telegram) loading placeholders. Each one copies the geometry of the loaded
 * Monogram presentation it stands in for (MonogramMediaCard, ContentRow, the landscape grid and
 * MonogramListItem in TelegramScreen), so real content replaces it in place without a reflow.
 * Keep these in sync with those composables.
 */

/** Which Monogram browse presentation a loading state stands in for. */
internal enum class MonogramPlaceholderLayout { ROWS, GRID, LIST, INFO_LIST }

/** Number of unfocusable cards appended after a grid/list while its next page loads. */
internal const val MONOGRAM_PAGE_END_PLACEHOLDERS = 2

internal fun monogramPageEndPlaceholderKey(index: Int) = "telegram-more:$index"

/** A text line placeholder that reserves exactly the line height of [style]. */
@Composable
private fun MonogramSkeletonLine(
    style: TextStyle,
    widthFraction: Float,
    pulse: State<Float>?,
    modifier: Modifier = Modifier,
) {
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val barHeight = with(LocalDensity.current) { style.fontSize.toDp() } * 0.72f
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        Text("", style = style, color = Color.Transparent, minLines = 1, maxLines = 1)
        Box(
            Modifier.fillMaxWidth(widthFraction).height(barHeight)
                .skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(3.dp)),
        )
    }
}

/**
 * Same frame, paddings, 16:9 artwork, 44 dp title slot and meta line as [MonogramMediaCard],
 * including its width rules (row width local, fillCell, hero rows without captions).
 */
@Composable
internal fun MonogramCardPlaceholder(
    modifier: Modifier = Modifier,
    fillCell: Boolean = false,
    pulse: State<Float>? = rememberSkeletonPulseOrNull(),
) {
    val shape = RoundedCornerShape(10.dp)
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val heroRow = LocalHeroRowTiles.current
    val width = LocalMonogramRowCardWidth.current ?: mediaTileWidth(MediaTileStyle.LANDSCAPE)
    val outer = if (fillCell) modifier.fillMaxWidth()
        else modifier.wrapContentWidth(Alignment.Start).width(width)
    Column(
        outer
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .58f), shape)
            .border(1.dp, Color.White.copy(alpha = .06f), shape)
            .padding(if (heroRow) 3.dp else 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(7.dp)),
        )
        if (!heroRow) {
            Box(Modifier.fillMaxWidth().height(44.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth(.8f).height(13.dp)
                        .skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(3.dp)),
                )
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                MonogramSkeletonLine(MaterialTheme.typography.bodySmall, 1f, pulse, Modifier.fillMaxWidth(.5f))
            }
        }
    }
}

/** Same frame, row scale, 16:9 artwork width and text lines as TelegramScreen's MonogramListItem. */
@Composable
internal fun MonogramListItemPlaceholder(
    rowsPerScreen: Int,
    detailed: Boolean,
    pulse: State<Float>? = rememberSkeletonPulseOrNull(),
) {
    val shape = RoundedCornerShape(10.dp)
    val fill = MaterialTheme.colorScheme.surfaceVariant
    val rowScale = (7f / rowsPerScreen.coerceIn(4, 10)).coerceIn(.72f, 1.35f)
    Row(
        Modifier.fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = .06f), shape)
            .padding(10.dp * rowScale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
    ) {
        Box(
            Modifier.width(deviceDp(if (detailed) 190.dp else 128.dp, 104.dp) * rowScale)
                .aspectRatio(16f / 9f).skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(7.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MonogramSkeletonLine(MaterialTheme.typography.titleMedium, .6f, pulse, Modifier.fillMaxWidth())
            MonogramSkeletonLine(MaterialTheme.typography.bodySmall, .4f, pulse, Modifier.fillMaxWidth())
            if (detailed) MonogramSkeletonLine(MaterialTheme.typography.bodySmall, .75f, pulse, Modifier.fillMaxWidth())
        }
    }
}

/** Visible landscape cards per Monogram row; shared by the real rows and their placeholder. */
@Composable
internal fun monogramRowVisibleColumns(gridColumns: Int): Int {
    val configuration = LocalConfiguration.current
    return visibleGridColumns(
        gridColumns, ViewPreview.LANDSCAPE, GridDensityProfile.SMARTTUBE,
        LocalHandheldUi.current, configuration.screenWidthDp, configuration.screenHeightDp,
    ).coerceAtLeast(1)
}

/** Card width for a Monogram row in a container of [maxWidth]; shared by rows and placeholder. */
@Composable
internal fun monogramRowCardWidth(maxWidth: Dp, visibleColumns: Int): Dp {
    val gap = 16.dp
    return ((maxWidth - screenGutter() * 2 - gap * (visibleColumns - 1)) / visibleColumns)
        .coerceAtLeast(if (LocalHandheldUi.current) 104.dp else 160.dp)
}

/** Loading state of a Monogram browse page in the view mode and density the user selected. */
@Composable
internal fun MonogramLoadingState(
    layout: MonogramPlaceholderLayout,
    gridColumns: Int,
    listRows: Int,
    modifier: Modifier = Modifier,
) {
    val pulse = rememberSkeletonPulseOrNull()
    when (layout) {
        MonogramPlaceholderLayout.ROWS -> BoxWithConstraints(modifier.fillMaxWidth()) {
            val visible = monogramRowVisibleColumns(gridColumns)
            val cardWidth = monogramRowCardWidth(maxWidth, visible)
            val fill = MaterialTheme.colorScheme.surfaceVariant
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                userScrollEnabled = false,
            ) {
                items(3) {
                    // ContentRow: 8 dp top, ContentSectionHeader (20 dp logo + titleLarge, 10 dp below).
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Row(
                            Modifier.padding(start = screenGutter(), bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(20.dp).skeletonPulseAlpha(pulse).background(fill, RoundedCornerShape(5.dp)))
                            MonogramSkeletonLine(MaterialTheme.typography.titleLarge, 1f, pulse, Modifier.width(168.dp))
                        }
                        CompositionLocalProvider(LocalMonogramRowCardWidth provides cardWidth) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = screenGutter()),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                userScrollEnabled = false,
                            ) {
                                items(visible + 1) { MonogramCardPlaceholder(pulse = pulse) }
                            }
                        }
                    }
                }
            }
        }
        MonogramPlaceholderLayout.GRID -> LazyVerticalGrid(
            columns = deviceDensityGridCells(landscapeGridColumns(gridColumns), 260.dp, 4),
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            userScrollEnabled = false,
        ) {
            items(36) { MonogramCardPlaceholder(fillCell = true, pulse = pulse) }
        }
        MonogramPlaceholderLayout.LIST, MonogramPlaceholderLayout.INFO_LIST -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            userScrollEnabled = false,
        ) {
            items(listRows.coerceIn(4, 10) + 2) {
                MonogramListItemPlaceholder(listRows, layout == MonogramPlaceholderLayout.INFO_LIST, pulse)
            }
        }
    }
}

/**
 * Home loading row for a Monogram row: same header placeholder and LazyRow as [SkeletonRow],
 * but with [MonogramCardPlaceholder] cards, because Home draws Monogram items as MonogramMediaCard.
 */
@Composable
internal fun MonogramSkeletonRow(modifier: Modifier = Modifier, itemCount: Int = 7) {
    val pulse = rememberSkeletonPulseOrNull()
    Column(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(start = screenGutter(), bottom = 12.dp)
                .width(168.dp)
                .height(18.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f), RoundedCornerShape(999.dp)),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = screenGutter()),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            userScrollEnabled = false,
        ) {
            items(itemCount) { MonogramCardPlaceholder(pulse = pulse) }
        }
    }
}
