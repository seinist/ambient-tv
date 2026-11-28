package com.ambient.tv.ui.home

import com.ambient.tv.ui.common.CyclicRowFocusKey

/**
 * The center lap is the real viewport. Neighbouring virtual laps allow Last -> First and
 * First -> Last to move by exactly one row instead of animating the entire column backwards.
 * Only rows near the viewport are composed by LazyColumn, never all nine laps at once.
 */
internal fun cyclicHomeRows(rowKeys: List<String>): List<CyclicRowFocusKey> =
    if (rowKeys.size < 2) rowKeys.map { CyclicRowFocusKey(it, 1) }
    else (0..8).flatMap { lap -> rowKeys.map { CyclicRowFocusKey(it, lap) } }

/** A spare lap before and several after the rebase permit rapid D-pad repeats. */
internal const val HOME_CYCLIC_REBASE_LAP = 3

/** Rebase only when the source and destination are beyond the first lap's hero padding. */
internal fun cyclicHomeRebaseViewportIndex(firstVisibleIndex: Int, rowCount: Int, lap: Int): Int? {
    if (rowCount < 2 || lap < HOME_CYCLIC_REBASE_LAP + 2) return null
    val target = firstVisibleIndex - (lap - HOME_CYCLIC_REBASE_LAP) * rowCount
    return target.takeIf { it >= rowCount }
}

/** The same stable key used by the lazy list and its visibility selector. */
internal fun homePhysicalRowKey(key: CyclicRowFocusKey): String =
    "home-row:${key.lap}:${key.logical}"

internal data class HomeHeroRowBounds(val key: String, val offset: Int, val size: Int)

/**
 * The pending focus destination may still be entirely behind the hero or outside the screen.
 * Show the row occupying the usable viewport until the destination enters it. At rest the
 * focused row wins even if a small part of a sibling is also laid out. Never show two rows.
 */
internal fun homeVisibleHeroRow(
    preferred: String?,
    rows: List<HomeHeroRowBounds>,
    contentTop: Int,
    contentBottom: Int,
): String? {
    if (contentBottom <= contentTop) return preferred
    fun visiblePixels(row: HomeHeroRowBounds): Int =
        (minOf(row.offset + row.size, contentBottom) - maxOf(row.offset, contentTop)).coerceAtLeast(0)
    val desired = rows.firstOrNull { it.key == preferred }
    val mostVisible = rows.maxByOrNull(::visiblePixels)?.takeIf { visiblePixels(it) > 0 }
    if (desired != null) {
        val visible = visiblePixels(desired)
        val available = minOf(desired.size, contentBottom - contentTop)
        // Prefer the actual navigation target when at least half of its available area is
        // visible, or when there is no better visible row (including ties).
        if (visible > 0 && (visible >= (available + 1) / 2 ||
                mostVisible == null || visible >= visiblePixels(mostVisible))) return desired.key
    }
    return mostVisible?.key ?: preferred
}
