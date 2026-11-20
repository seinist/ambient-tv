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
