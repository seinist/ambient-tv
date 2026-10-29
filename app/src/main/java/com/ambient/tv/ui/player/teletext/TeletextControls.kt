package com.ambient.tv.ui.player.teletext

import com.ambient.tv.livetv.teletext.teletextNumber

/** A remote or keypad digit either extends a partial entry or completes one decimal page. */
internal data class TeletextPageEntry(val digits: String, val page: Int? = null)

internal fun appendTeletextDigit(entry: String, digit: Int): TeletextPageEntry {
    if (digit !in 0..9 || (entry.isEmpty() && digit !in 1..8)) return TeletextPageEntry(entry)
    val digits = entry + digit
    if (digits.length < 3) return TeletextPageEntry(digits)
    return TeletextPageEntry("", teletextNumber(digits.toIntOrNull() ?: 0))
}

/** Subcodes need not be consecutive; a missing current subpage starts at the requested edge. */
internal fun stepTeletextSubpage(current: Int?, available: List<Int>, forward: Boolean): Int? {
    if (available.isEmpty()) return null
    val index = available.indexOf(current)
    if (index < 0) return if (forward) available.first() else available.last()
    return available[(index + if (forward) 1 else available.size - 1) % available.size]
}

/** These controls can disappear or become unavailable as pages arrive or decoding restarts. */
internal enum class TeletextDynamicControl(val colorIndex: Int? = null) {
    RED(0), GREEN(1), YELLOW(2), BLUE(3), PREVIOUS_SUBPAGE, NEXT_SUBPAGE, PAGES,
}

internal fun teletextControlDisappeared(
    focused: TeletextDynamicControl?,
    links: List<Int>,
    subpageCount: Int,
    hasPages: Boolean,
): Boolean = when {
    focused == null -> false
    focused.colorIndex != null -> links.getOrNull(focused.colorIndex).let { it == null || it == 0 }
    focused == TeletextDynamicControl.PAGES -> !hasPages
    else -> subpageCount <= 1
}
