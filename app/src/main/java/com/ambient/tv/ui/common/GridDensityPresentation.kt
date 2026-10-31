package com.ambient.tv.ui.common

import com.ambient.tv.ui.mobile.handheldGridColumns
import com.ambient.tv.ui.mobile.handheldPortraitColumns

/**
 * The value stored by the shared density setting is a TV-oriented base (4..12).
 * Phone layouts transform it to match the available screen width. The slider must show
 * the columns really rendered, not the stored base, and every step must change the view.
 */
enum class GridDensityProfile { MEDIA_LIBRARY, RADIO_BROWSER, SMARTTUBE }

internal data class GridDensityChoice(val storedColumns: Int, val visibleColumns: Int)

/** Mirror the exact column conversions used by each screen's LazyVerticalGrid. */
internal fun visibleGridColumns(
    storedColumns: Int,
    preview: ViewPreview,
    profile: GridDensityProfile,
    handheld: Boolean,
    widthDp: Int,
    heightDp: Int,
): Int {
    val landscape = widthDp > heightDp
    fun density(columns: Int, portraitMinimum: Float, defaultColumns: Int, landscapeMinimum: Float = portraitMinimum): Int =
        if (landscape) handheldGridColumns(columns, widthDp, landscapeMinimum, defaultColumns)
        else handheldPortraitColumns(columns, widthDp, portraitMinimum, defaultColumns)
    // deviceGridCells() uses handheldPortraitColumns() in both orientations; preserve that
    // implementation here until the entire wall layout is explicitly redesigned.
    fun wall(columns: Int, minimum: Float): Int =
        handheldPortraitColumns(columns, widthDp, minimum, 8)

    if (!handheld) return when (profile) {
        GridDensityProfile.RADIO_BROWSER -> storedColumns
        GridDensityProfile.SMARTTUBE ->
            if (preview == ViewPreview.WALL) (storedColumns + 2).coerceIn(6, 14)
            else landscapeGridColumns(storedColumns)
        GridDensityProfile.MEDIA_LIBRARY -> when (preview) {
            ViewPreview.LANDSCAPE, ViewPreview.ROWS -> landscapeGridColumns(storedColumns)
            ViewPreview.WALL -> (storedColumns + 2).coerceIn(6, 14)
            ViewPreview.POSTER_WALL -> (storedColumns + 4).coerceIn(8, 16)
            else -> storedColumns
        }
    }
    return when (profile) {
        GridDensityProfile.RADIO_BROWSER -> density(storedColumns, 160f, 8)
        GridDensityProfile.SMARTTUBE -> if (preview == ViewPreview.WALL) {
            wall((storedColumns + 2).coerceIn(6, 14), 260f)
        } else density(landscapeGridColumns(storedColumns), 260f, 4)
        GridDensityProfile.MEDIA_LIBRARY -> when (preview) {
            ViewPreview.LANDSCAPE, ViewPreview.ROWS ->
                density(landscapeGridColumns(storedColumns), 260f, 4)
            ViewPreview.WALL -> wall((storedColumns + 2).coerceIn(6, 14), 144f)
            ViewPreview.POSTER_WALL -> wall((storedColumns + 4).coerceIn(8, 16), 96f)
            else -> density(storedColumns, 144f, 8)
        }
    }
}

/**
 * Slider positions correspond to distinct, real column counts.
 * Several base values can render the same phone count due to rounding and minimum tile widths.
 */
internal fun visibleGridDensityChoices(
    preview: ViewPreview,
    profile: GridDensityProfile,
    handheld: Boolean,
    widthDp: Int,
    heightDp: Int,
): List<GridDensityChoice> = (4..12).map { stored ->
    GridDensityChoice(stored, visibleGridColumns(stored, preview, profile, handheld, widthDp, heightDp))
}.distinctBy(GridDensityChoice::visibleColumns)
