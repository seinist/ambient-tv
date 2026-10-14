package com.ambient.tv.ui.mobile

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Device class, never screen width or presence of a remote/touchscreen, selects the TV UI. */
fun isTelevision(context: Context): Boolean = isTelevisionMode(context.resources.configuration.uiMode)

internal fun isTelevisionMode(uiMode: Int): Boolean =
    uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

// Default to TV so existing previews/catalogues and TV-only entry points stay unchanged.
val LocalHandheldUi = staticCompositionLocalOf { false }

internal fun isWideHandheld(handheld: Boolean, widthDp: Int, heightDp: Int): Boolean =
    handheld && widthDp > heightDp

/** Phone-only wide layout gate. Device class remains the authority, never width alone. */
@Composable
fun isHandheldLandscape(): Boolean {
    val configuration = LocalConfiguration.current
    return isWideHandheld(
        handheld = LocalHandheldUi.current,
        widthDp = configuration.screenWidthDp,
        heightDp = configuration.screenHeightDp,
    )
}

/**
 * Handheld window too narrow for content beside content (a phone in portrait). A tablet in
 * portrait is wide enough and keeps the two-column layouts, unlike a portrait/landscape check.
 */
@Composable
fun isNarrowHandheldWindow(): Boolean =
    LocalHandheldUi.current && !windowIsTabletWidth(LocalConfiguration.current.screenWidthDp)

/** Android's tablet bucket: smallest width, so rotation does not turn a phone into a tablet. */
internal const val TabletSmallestWidthDp = 600

/** Handheld touch device with a tablet smallest-width. Never selects the television UI. */
internal fun isTabletFormFactor(handheld: Boolean, smallestScreenWidthDp: Int): Boolean =
    handheld && smallestScreenWidthDp >= TabletSmallestWidthDp

/** Current window is wide enough for a poster beside text, or a settings index beside content. */
internal fun windowIsTabletWidth(screenWidthDp: Int): Boolean = screenWidthDp >= TabletSmallestWidthDp

/**
 * Phones in portrait stay stacked. A window that is already tablet-wide, including landscape
 * handheld, puts the poster beside the text instead of forcing one item per row.
 */
internal fun handheldDetailStacks(screenWidthDp: Int): Boolean = !windowIsTabletWidth(screenWidthDp)

/** Side index only on a tablet whose current window is still wide enough. Phones keep the chip row. */
internal fun settingsUsesSideIndex(tablet: Boolean, screenWidthDp: Int): Boolean =
    tablet && windowIsTabletWidth(screenWidthDp)

/**
 * Phone heroes stay a fixed block. On a tablet the block tracks the viewport so a tall
 * portrait is not a short strip and a short landscape is not a fixed tall slab.
 */
internal fun tabletHeroHeightDp(screenHeightDp: Int, phoneHeightDp: Int = 390): Int {
    val viewport = screenHeightDp.coerceAtLeast(1)
    val fraction = 0.46f * (phoneHeightDp / 390f)
    val scaled = (viewport * fraction).roundToInt()
    val min = (viewport * 0.32f).roundToInt().coerceAtLeast(240)
    val max = (viewport * 0.62f).roundToInt().coerceAtLeast(min)
    return scaled.coerceIn(min, max)
}

@Composable
fun isTabletUi(): Boolean =
    isTabletFormFactor(LocalHandheldUi.current, LocalConfiguration.current.smallestScreenWidthDp)

/**
 * Television keeps [tv]. Phones keep [phone]. Tablets scale [phone] with the current viewport.
 */
@Composable
fun deviceHeroHeight(tv: Dp, phone: Dp): Dp {
    if (!LocalHandheldUi.current) return tv
    if (!isTabletUi()) return phone
    return tabletHeroHeightDp(LocalConfiguration.current.screenHeightDp, phone.value.toInt()).dp
}

@Composable
fun deviceDp(tv: Dp, handheld: Dp): Dp = if (LocalHandheldUi.current) handheld else tv

@Composable
fun deviceGridCells(tvColumns: Int, minimumWidth: Dp = 144.dp): GridCells =
    if (LocalHandheldUi.current) {
        // Fixed columns scaled from the shared density preference. The old clamped Adaptive
        // width collapsed most steps onto one width and left a portrait phone with one tile per row.
        GridCells.Fixed(handheldPortraitColumns(tvColumns, LocalConfiguration.current.screenWidthDp, minimumWidth.value, 8))
    } else {
        GridCells.Fixed(tvColumns)
    }

/**
 * Columns on a narrow handheld: the width-based default ([minimumWidthDp] tiles at
 * [defaultTvColumns]) scaled by the density preference. Keeps two columns whenever two default
 * tiles fit, so the largest sizes do not fall back to one tile per row.
 */
internal fun handheldPortraitColumns(tvColumns: Int, widthDp: Int, minimumWidthDp: Float, defaultTvColumns: Int): Int {
    val width = widthDp.coerceAtLeast(1)
    val base = width / minimumWidthDp
    val minColumns = if (base >= 2f) 2 else 1
    val maxColumns = (width / 64).coerceAtLeast(minColumns)
    return (base * tvColumns / defaultTvColumns.coerceAtLeast(1)).roundToInt().coerceIn(minColumns, maxColumns)
}

/**
 * Grid for the shared density preference ([tvColumns] is the TV column count, default
 * [defaultTvColumns]). TV keeps fixed columns, portrait handhelds use [handheldPortraitColumns].
 * Landscape handhelds fill the width with tiles of about [landscapeTileWidth] at the default
 * and add/remove exactly one column per density step, so neighbouring sizes (e.g. 4 and 5)
 * no longer collapse onto the same clamped Adaptive width.
 */
@Composable
fun deviceDensityGridCells(
    tvColumns: Int,
    minimumWidth: Dp = 144.dp,
    defaultTvColumns: Int = 8,
    landscapeTileWidth: Dp = minimumWidth,
): GridCells {
    val configuration = LocalConfiguration.current
    if (!LocalHandheldUi.current) return deviceGridCells(tvColumns, minimumWidth)
    // Portrait scales the width-based default; landscape steps one column per density size.
    if (configuration.screenWidthDp <= configuration.screenHeightDp) {
        return GridCells.Fixed(handheldPortraitColumns(tvColumns, configuration.screenWidthDp, minimumWidth.value, defaultTvColumns))
    }
    return GridCells.Fixed(handheldGridColumns(tvColumns, configuration.screenWidthDp, landscapeTileWidth.value, defaultTvColumns))
}

/** Columns on a handheld: one column per density step around a width-based default, tiles >= 64 dp. */
internal fun handheldGridColumns(tvColumns: Int, widthDp: Int, minimumWidthDp: Float, defaultTvColumns: Int): Int {
    val width = widthDp.coerceAtLeast(1)
    // Same column count as Adaptive(minimumWidth) at the default density.
    val base = (width / minimumWidthDp).toInt().coerceAtLeast(1)
    val maxColumns = (width / 64).coerceAtLeast(1)
    return (base + tvColumns - defaultTvColumns).coerceIn(1, maxColumns)
}

@Composable
fun deviceVideoGridCells(tvColumns: Int): GridCells = deviceGridCells(tvColumns, 260.dp)

/** Wrap action groups on phones; preserve the original Row implementation on television. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceActionRow(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    stack: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalHandheldUi.current) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = if (stack) 1 else Int.MAX_VALUE,
        ) { content() }
    } else {
        Row(modifier, horizontalArrangement, verticalAlignment, content)
    }
}
