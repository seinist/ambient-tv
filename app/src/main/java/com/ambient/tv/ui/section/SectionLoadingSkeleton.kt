package com.ambient.tv.ui.section

import com.ambient.tv.ui.theme.screenGutter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.ambient.tv.ui.common.MediaTileStyle
import com.ambient.tv.ui.common.PageHeader
import com.ambient.tv.ui.common.SkeletonTile
import com.ambient.tv.ui.common.rememberUhdScale
import com.ambient.tv.ui.performance.LocalPerformanceProfile
import com.ambient.tv.ui.mobile.LocalHandheldUi
import com.ambient.tv.ui.mobile.deviceDp
import com.ambient.tv.ui.mobile.deviceHeroHeight
import com.ambient.tv.ui.mobile.deviceGridCells
import com.ambient.tv.ui.mobile.deviceDensityGridCells

/*
 * Loading placeholders for the library screen. Every layout here copies the paddings, spacing,
 * column counts and artwork sizes of the view mode it stands in for (SectionScreen), so the first
 * loaded frame replaces the skeleton in place instead of reflowing the page.
 */

private val LocalSkeletonPulse = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0.45f) }
private val Pill = RoundedCornerShape(999.dp)
private val CardShape = RoundedCornerShape(10.dp)

/** Placeholder fill. The pulse is read at draw time so a grid does not recompose each frame.
 *  Sparsam / reduced effects leave the default flat value in place. */
@Composable
private fun Modifier.skeleton(shape: Shape = Pill, strength: Float = 1f): Modifier {
    val pulse = LocalSkeletonPulse.current
    val color = MaterialTheme.colorScheme.surfaceVariant
    return clip(shape).drawBehind { drawRect(color, alpha = pulse.value * strength) }
}

/** The static card frame list rows and collection cards draw behind their content. */
@Composable
private fun Modifier.cardFrame(): Modifier =
    background(MaterialTheme.colorScheme.surface.copy(alpha = 0.58f), CardShape)
        .border(1.dp, Color.White.copy(alpha = 0.06f), CardShape)

/** Text placeholder that takes exactly the height [lines] lines of [style] take. */
@Composable
private fun SkeletonText(style: TextStyle, modifier: Modifier, lines: Int = 1, strength: Float = 1f) {
    val barHeight = with(LocalDensity.current) { style.fontSize.toDp() } * 0.72f
    Box(modifier) {
        // Invisible text reserves the real line height, font padding included.
        Text("", style = style, color = Color.Transparent, minLines = lines, maxLines = lines)
        Column(Modifier.matchParentSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            repeat(lines) { line ->
                val last = lines > 1 && line == lines - 1
                Box(Modifier.fillMaxWidth(if (last) 0.62f else 1f).height(barHeight).skeleton(strength = strength))
            }
        }
    }
}

@Composable
internal fun LibraryLoadingState(viewMode: SectionViewMode, gridColumns: Int, listRows: Int, page: SectionPage = SectionPage.LIBRARY) {
    if (LocalPerformanceProfile.current.reducedEffects) {
        LibraryLoadingBody(viewMode, gridColumns, listRows, page)
    } else {
        PulsingLibraryLoading(viewMode, gridColumns, listRows, page)
    }
}

@Composable
private fun PulsingLibraryLoading(viewMode: SectionViewMode, gridColumns: Int, listRows: Int, page: SectionPage) {
    // The shared skeleton clock runs only while the app is resumed.
    val pulse = com.ambient.tv.ui.common.rememberSharedPulse(com.ambient.tv.ui.common.SharedSkeletonPulse)
    CompositionLocalProvider(LocalSkeletonPulse provides pulse) {
        LibraryLoadingBody(viewMode, gridColumns, listRows, page)
    }
}

@Composable
private fun LibraryLoadingBody(viewMode: SectionViewMode, gridColumns: Int, listRows: Int, page: SectionPage) {
    Column(Modifier.fillMaxSize()) {
        SectionHeaderSkeleton()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (page) {
                SectionPage.RECOMMENDATIONS -> RecommendationsSkeleton(recommendationViewMode(viewMode), gridColumns, listRows)
                SectionPage.LIBRARY -> LibrarySkeleton(viewMode, gridColumns, listRows)
                SectionPage.COLLECTIONS ->
                    if (viewMode in setOf(SectionViewMode.POSTER, SectionViewMode.GRID, SectionViewMode.WALL, SectionViewMode.POSTER_WALL)) {
                        CollectionGridSkeleton(gridColumns)
                    } else {
                        CollectionListSkeleton(listRows)
                    }
            }
        }
    }
}

@Composable
private fun SectionHeaderSkeleton() {
    Column(Modifier.fillMaxWidth().padding(start = screenGutter(), end = screenGutter(), top = 24.dp, bottom = 10.dp)) {
        PageHeader(actions = {
            val actionWidth = if (LocalHandheldUi.current) 48.dp else 118.dp
            ChipSkeleton(actionWidth)
            ChipSkeleton(if (LocalHandheldUi.current) 48.dp else 124.dp)
        }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(28.dp).skeleton(RoundedCornerShape(7.dp)))
                SkeletonText(MaterialTheme.typography.headlineMedium, Modifier.width(240.dp))
            }
            SkeletonText(MaterialTheme.typography.bodySmall, Modifier.width(200.dp), strength = 0.7f)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, start = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChipSkeleton(156.dp)
            ChipSkeleton(124.dp)
            ChipSkeleton(140.dp)
        }
    }
}

/** A [com.ambient.tv.ui.common.TvChip]'s focus slot with its drawn pill centred inside. */
@Composable
private fun ChipSkeleton(width: Dp) {
    val scale = rememberUhdScale()
    Box(Modifier.sizeIn(minHeight = 48.dp * scale).padding(2.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.width(width * scale).height(36.dp * scale).skeleton(RoundedCornerShape(10.dp), strength = 0.8f))
    }
}

/** [com.ambient.tv.ui.common.ContentSectionHeader]: accent bar plus a titleLarge line. */
@Composable
private fun SectionTitleSkeleton(modifier: Modifier = Modifier, startPadding: Dp = screenGutter(), bottomPadding: Dp = 10.dp) {
    Row(
        modifier.padding(start = startPadding, bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(4.dp, 14.dp).skeleton())
        SkeletonText(MaterialTheme.typography.titleLarge, Modifier.width(190.dp))
    }
}

private fun listRowScale(listRows: Int, uhdScale: Float) = (7f / listRows.coerceIn(4, 10)).coerceIn(0.72f, 1.75f) * uhdScale

@Composable
private fun LibrarySkeleton(viewMode: SectionViewMode, gridColumns: Int, listRows: Int) {
    val libraryPadding = PaddingValues(screenGutter(), 12.dp, deviceDp(52.dp, 16.dp), 36.dp)
    when (viewMode) {
        SectionViewMode.POSTER, SectionViewMode.GRID ->
            TileGridSkeleton(deviceDensityGridCells(gridColumns), libraryPadding, 8.dp, MediaTileStyle.POSTER, showDescription = true)
        SectionViewMode.LANDSCAPE, SectionViewMode.ROWS ->
            TileGridSkeleton(deviceDensityGridCells((gridColumns / 2).coerceIn(2, 6), 144.dp, 4, landscapeTileWidth = 260.dp), libraryPadding, 10.dp, MediaTileStyle.LANDSCAPE, showDescription = false)
        SectionViewMode.LIST, SectionViewMode.POSTER_LIST ->
            MediaListSkeleton(listRows, poster = viewMode == SectionViewMode.POSTER_LIST)
        SectionViewMode.INFO_LIST, SectionViewMode.POSTER_INFO_LIST ->
            if (LocalHandheldUi.current) {
                MediaListSkeleton(listRows, poster = viewMode == SectionViewMode.POSTER_INFO_LIST)
            } else {
                InfoListSkeleton(listRows, poster = viewMode == SectionViewMode.POSTER_INFO_LIST)
            }
        SectionViewMode.WALL, SectionViewMode.POSTER_WALL -> WallSkeleton(gridColumns, poster = viewMode == SectionViewMode.POSTER_WALL, sections = false)
        SectionViewMode.CINEMATIC, SectionViewMode.POSTER_CINEMATIC -> CinematicSkeleton(poster = viewMode == SectionViewMode.POSTER_CINEMATIC)
    }
}

@Composable
private fun RecommendationsSkeleton(viewMode: SectionViewMode, gridColumns: Int, listRows: Int) {
    when (viewMode) {
        SectionViewMode.LIST, SectionViewMode.INFO_LIST, SectionViewMode.POSTER_LIST, SectionViewMode.POSTER_INFO_LIST ->
            RecommendationListSkeleton(
                listRows,
                detailed = viewMode == SectionViewMode.INFO_LIST || viewMode == SectionViewMode.POSTER_INFO_LIST,
                poster = viewMode == SectionViewMode.POSTER_LIST || viewMode == SectionViewMode.POSTER_INFO_LIST,
            )
        SectionViewMode.WALL, SectionViewMode.POSTER_WALL -> WallSkeleton(gridColumns, poster = viewMode == SectionViewMode.POSTER_WALL, sections = true)
        SectionViewMode.CINEMATIC, SectionViewMode.POSTER_CINEMATIC -> RecommendationCinematicSkeleton(poster = viewMode == SectionViewMode.POSTER_CINEMATIC)
        else -> RecommendationRowsSkeleton(if (viewMode == SectionViewMode.POSTER) MediaTileStyle.POSTER else MediaTileStyle.LANDSCAPE)
    }
}

@Composable
private fun TileGridSkeleton(columns: GridCells, padding: PaddingValues, horizontalSpacing: Dp, style: MediaTileStyle, showDescription: Boolean) {
    LazyVerticalGrid(
        columns = columns,
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        userScrollEnabled = false,
    ) { gridItems(List(72) { it }) { SkeletonTile(style = style, showDescription = showDescription, fillCell = true) } }
}

/** One list card: artwork, title, a meta line and optional summary lines, like the real list rows. */
@Composable
private fun ListCardSkeleton(
    rowScale: Float,
    artWidth: Dp,
    poster: Boolean,
    artRadius: Dp,
    titleStyle: TextStyle,
    metaStyle: TextStyle,
    summaryLines: Int,
    contentSpacing: Dp = 5.dp,
) {
    Row(
        Modifier.fillMaxWidth().cardFrame().padding(10.dp * rowScale),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp * rowScale),
    ) {
        Box(Modifier.width(artWidth).aspectRatio(if (poster) 2f / 3f else 16f / 9f).skeleton(RoundedCornerShape(artRadius)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(contentSpacing)) {
            SkeletonText(titleStyle, Modifier.fillMaxWidth(0.48f))
            SkeletonText(metaStyle, Modifier.fillMaxWidth(0.26f), strength = 0.75f)
            if (summaryLines > 0) SkeletonText(MaterialTheme.typography.bodySmall, Modifier.fillMaxWidth(0.86f), lines = summaryLines, strength = 0.6f)
        }
    }
}

/** StylishMediaList (List / Cover list, and Info list on phones). */
@Composable
private fun MediaListSkeleton(listRows: Int, poster: Boolean) {
    val rowScale = listRowScale(listRows, rememberUhdScale())
    LazyColumn(
        contentPadding = PaddingValues(screenGutter(), 12.dp, screenGutter(), 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
        userScrollEnabled = false,
    ) {
        items(24) {
            ListCardSkeleton(
                rowScale = rowScale,
                artWidth = (if (poster) deviceDp(76.dp, 52.dp) else deviceDp(166.dp, 96.dp)) * rowScale,
                poster = poster,
                artRadius = if (poster) 7.dp else 10.dp,
                titleStyle = MaterialTheme.typography.titleMedium,
                metaStyle = MaterialTheme.typography.bodySmall,
                summaryLines = 2,
            )
        }
    }
}

/** InfoList on TV: compact list on the left, the focused item's preview on the right. */
@Composable
private fun InfoListSkeleton(listRows: Int, poster: Boolean) {
    val uhdScale = rememberUhdScale()
    val rowScale = listRowScale(listRows, uhdScale)
    Row(Modifier.fillMaxSize().padding(horizontal = screenGutter(), vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        LazyColumn(
            modifier = Modifier.width(470.dp * uhdScale).fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(5.dp * rowScale),
            contentPadding = PaddingValues(bottom = 30.dp),
            userScrollEnabled = false,
        ) {
            items(30) {
                Row(
                    Modifier.fillMaxWidth()
                        .border(1.dp, Color.White.copy(alpha = 0.04f), CardShape)
                        .padding(horizontal = 12.dp * rowScale, vertical = 9.dp * rowScale),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (poster) {
                        Box(Modifier.width(40.dp * rowScale).aspectRatio(2f / 3f).skeleton(RoundedCornerShape(6.dp)))
                    } else {
                        Box(Modifier.width(96.dp * rowScale).aspectRatio(16f / 9f).skeleton(RoundedCornerShape(8.dp)))
                    }
                    Column(Modifier.weight(1f)) {
                        SkeletonText(MaterialTheme.typography.titleSmall, Modifier.fillMaxWidth(0.62f))
                        SkeletonText(MaterialTheme.typography.labelSmall, Modifier.fillMaxWidth(0.34f), strength = 0.75f)
                    }
                }
            }
        }
        if (poster) {
            Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Box(Modifier.fillMaxHeight(0.92f).aspectRatio(2f / 3f, matchHeightConstraintsFirst = true).skeleton(CardShape))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PreviewDetailsSkeleton(summaryLines = 10)
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).skeleton(CardShape))
                PreviewDetailsSkeleton(summaryLines = 6)
            }
        }
    }
}

/** InfoListPreviewDetails: stream badges, headline title, meta row and summary. */
@Composable
private fun PreviewDetailsSkeleton(summaryLines: Int) {
    StreamBadgesSkeleton()
    SkeletonText(MaterialTheme.typography.headlineMedium, Modifier.fillMaxWidth(0.64f))
    SkeletonText(MaterialTheme.typography.bodyMedium, Modifier.fillMaxWidth(0.32f), strength = 0.75f)
    SkeletonText(MaterialTheme.typography.bodyMedium, Modifier.fillMaxWidth(), lines = summaryLines, strength = 0.6f)
}

/** One row of StreamPropertyAdaptiveRows' badges. */
@Composable
private fun StreamBadgesSkeleton() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(52.dp, 64.dp, 46.dp).forEach { Box(Modifier.width(it).height(22.dp).skeleton(RoundedCornerShape(6.dp), strength = 0.7f)) }
    }
}

/** Wall / Cover wall, optionally grouped under recommendation section titles. */
@Composable
private fun WallSkeleton(gridColumns: Int, poster: Boolean, sections: Boolean) {
    val wallColumns = if (poster) coverWallColumns(gridColumns) else (gridColumns + 2).coerceIn(6, 14)
    LazyVerticalGrid(
        columns = deviceGridCells(wallColumns, if (poster) 96.dp else 144.dp),
        contentPadding = PaddingValues(screenGutter(), 10.dp, deviceDp(52.dp, 16.dp), 34.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = false,
    ) {
        repeat(if (sections) 4 else 1) { section ->
            if (sections) item(span = { GridItemSpan(maxLineSpan) }) {
                SectionTitleSkeleton(Modifier.padding(top = if (section == 0) 0.dp else 14.dp), startPadding = 0.dp, bottomPadding = 4.dp)
            }
            gridItems(List(if (sections) wallColumns * 2 else 120) { it }) {
                Box(Modifier.fillMaxWidth().aspectRatio(if (poster) 2f / 3f else 16f / 9f).border(1.dp, Color.White.copy(alpha = 0.06f), CardShape)) {
                    Box(Modifier.fillMaxSize().skeleton(RoundedCornerShape(8.dp)))
                }
            }
        }
    }
}

/** Library Cinematic / Cover cinematic: hero text block on top, one tile row at the bottom. */
@Composable
private fun CinematicSkeleton(poster: Boolean) {
    Column(Modifier.fillMaxSize().padding(top = 28.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.padding(horizontal = screenGutter()).fillMaxWidth(0.58f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonText(MaterialTheme.typography.displaySmall, Modifier.fillMaxWidth(0.7f), lines = 2)
            SkeletonText(MaterialTheme.typography.titleSmall, Modifier.fillMaxWidth(0.34f), strength = 0.75f)
            StreamBadgesSkeleton()
            SkeletonText(MaterialTheme.typography.bodyMedium, Modifier.fillMaxWidth(), lines = if (poster) 3 else 4, strength = 0.6f)
        }
        TileRowSkeleton(
            if (poster) MediaTileStyle.POSTER else MediaTileStyle.LANDSCAPE,
            PaddingValues(horizontal = screenGutter(), vertical = 18.dp),
            spacing = 12.dp,
        )
    }
}

@Composable
private fun TileRowSkeleton(style: MediaTileStyle, padding: PaddingValues, spacing: Dp) {
    LazyRow(contentPadding = padding, horizontalArrangement = Arrangement.spacedBy(spacing), userScrollEnabled = false) {
        items(14) { SkeletonTile(style = style, showDescription = false) }
    }
}

/** RecommendationRowsPage: ContentRows with poster or landscape tiles. */
@Composable
private fun RecommendationRowsSkeleton(style: MediaTileStyle) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 36.dp), userScrollEnabled = false) {
        items(5) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                SectionTitleSkeleton()
                TileRowSkeleton(style, PaddingValues(horizontal = screenGutter()), spacing = 16.dp)
            }
        }
    }
}

@Composable
private fun RecommendationListSkeleton(listRows: Int, detailed: Boolean, poster: Boolean) {
    val rowScale = listRowScale(listRows, rememberUhdScale())
    val artWidth = if (poster) {
        deviceDp(if (detailed) 84.dp else 60.dp, 52.dp)
    } else {
        deviceDp(if (detailed) 190.dp else 128.dp, 104.dp)
    } * rowScale
    LazyColumn(
        contentPadding = PaddingValues(screenGutter(), 10.dp, screenGutter(), 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
        userScrollEnabled = false,
    ) {
        repeat(4) { section ->
            item { SectionTitleSkeleton(Modifier.padding(top = if (section == 0) 0.dp else 12.dp), startPadding = 0.dp, bottomPadding = 2.dp) }
            items(8) {
                ListCardSkeleton(
                    rowScale = rowScale,
                    artWidth = artWidth,
                    poster = poster,
                    artRadius = if (poster) 7.dp else 9.dp,
                    titleStyle = MaterialTheme.typography.titleMedium,
                    metaStyle = MaterialTheme.typography.bodySmall,
                    summaryLines = if (detailed) 2 else 0,
                )
            }
        }
    }
}

/** RecommendationCinematicPage: a titled hero block with its own tile row per section. */
@Composable
private fun RecommendationCinematicSkeleton(poster: Boolean) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        userScrollEnabled = false,
    ) {
        items(3) {
            Column(Modifier.fillMaxWidth()) {
                SectionTitleSkeleton()
                Box(Modifier.fillMaxWidth().height(if (poster) deviceHeroHeight(600.dp, 470.dp) else deviceHeroHeight(500.dp, 390.dp))) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.padding(horizontal = screenGutter(), vertical = 18.dp).fillMaxWidth(0.72f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            SkeletonText(MaterialTheme.typography.headlineMedium, Modifier.fillMaxWidth(0.6f), lines = 2)
                            SkeletonText(MaterialTheme.typography.titleSmall, Modifier.fillMaxWidth(0.3f), strength = 0.75f)
                            SkeletonText(MaterialTheme.typography.bodyMedium, Modifier.fillMaxWidth(), lines = 3, strength = 0.6f)
                        }
                        TileRowSkeleton(
                            if (poster) MediaTileStyle.POSTER else MediaTileStyle.LANDSCAPE,
                            PaddingValues(horizontal = screenGutter(), vertical = 14.dp),
                            spacing = 12.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionGridSkeleton(gridColumns: Int) {
    val cardWidth = 160.dp * rememberUhdScale()
    LazyVerticalGrid(
        columns = deviceDensityGridCells(gridColumns),
        contentPadding = PaddingValues(screenGutter(), 12.dp, screenGutter(), 36.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        userScrollEnabled = false,
    ) {
        gridItems(List(48) { it }) {
            Box {
                Column(Modifier.width(cardWidth).cardFrame()) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).skeleton(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)))
                    Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        SkeletonText(MaterialTheme.typography.titleSmall, Modifier.fillMaxWidth(0.72f))
                        SkeletonText(MaterialTheme.typography.bodySmall, Modifier.fillMaxWidth(0.4f), strength = 0.75f)
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionListSkeleton(listRows: Int) {
    val rowScale = listRowScale(listRows, rememberUhdScale())
    LazyColumn(
        contentPadding = PaddingValues(screenGutter(), 12.dp, screenGutter(), 36.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp * rowScale),
        userScrollEnabled = false,
    ) {
        items(24) {
            ListCardSkeleton(
                rowScale = rowScale,
                artWidth = deviceDp(178.dp, 96.dp) * rowScale,
                poster = false,
                artRadius = 10.dp,
                titleStyle = MaterialTheme.typography.titleMedium,
                metaStyle = MaterialTheme.typography.bodySmall,
                summaryLines = 0,
                contentSpacing = 0.dp,
            )
        }
    }
}
