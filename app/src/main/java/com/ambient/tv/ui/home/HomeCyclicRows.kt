package com.ambient.tv.ui.home

import com.ambient.tv.ui.common.CyclicRowFocusKey

/**
 * The center lap is the real viewport. Neighbouring virtual laps allow Last -> First and
 * First -> Last to move by exactly one row instead of animating the entire column backwards.
 * Only rows near the viewport are composed by LazyColumn, never all three laps at once.
 */
internal fun cyclicHomeRows(rowKeys: List<String>): List<CyclicRowFocusKey> =
    if (rowKeys.size < 2) rowKeys.map { CyclicRowFocusKey(it, 1) }
    else (0..2).flatMap { lap -> rowKeys.map { CyclicRowFocusKey(it, lap) } }

/** Initial and playback-return rows always live in the middle lap. */
internal fun cyclicHomeMiddleStart(rowCount: Int): Int = if (rowCount < 2) 0 else rowCount

/** The same logical row in the middle lap, used for an invisible viewport rebase. */
internal fun cyclicHomeMiddleIndex(rowKeys: List<String>, key: CyclicRowFocusKey): Int =
    rowKeys.indexOf(key.logical).takeIf { it >= 0 }?.plus(cyclicHomeMiddleStart(rowKeys.size)) ?: -1
