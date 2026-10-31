package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioStation
import java.text.Collator
import java.util.Locale

/** All station lists use the same index buckets as Radio Browser's facet lists. */
internal fun radioStationLetter(station: RadioStation): Char? =
    radioFacetInitial(station.name).singleOrNull()

/**
 * Sort the actual station selection, not the whole catalog. Popular keeps its top-clicked
 * membership; local Favorites/Recent and remote facet/search pages get stable A–Z ordering.
 * Put digits and symbols into '#' before the letters in A–Z, and last in Z–A.
 */
internal fun radioAlphabeticalStations(
    stations: List<RadioStation>,
    descending: Boolean = false,
    locale: Locale = Locale.getDefault(),
): List<RadioStation> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    val comparator = Comparator<RadioStation> { left, right ->
        val leftNumber = radioStationLetter(left) == '#'
        val rightNumber = radioStationLetter(right) == '#'
        when {
            leftNumber && !rightNumber -> -1
            rightNumber && !leftNumber -> 1
            else -> collator.compare(left.name.trim(), right.name.trim())
                .takeIf { it != 0 } ?: left.id.compareTo(right.id)
        }
    }
    return stations.sortedWith(if (descending) comparator.reversed() else comparator)
}

/** Jump offsets are indices into the same visible (possibly paginated) station list. */
internal fun radioStationAlphabetJumps(stations: List<RadioStation>): List<RadioAlphabetJump> {
    val firstIndices = linkedMapOf<String, Int>()
    stations.forEachIndexed { index, station ->
        val letter = radioStationLetter(station)?.toString() ?: return@forEachIndexed
        firstIndices.putIfAbsent(letter, index)
    }
    return firstIndices.map { (letter, index) -> RadioAlphabetJump(letter, index) }
}

/** Removing a favorite keeps focus in its former row, preferring the next station. */
internal fun radioFocusAfterRemovingFavorite(stations: List<RadioStation>, removedId: String): String? {
    val index = stations.indexOfFirst { it.id == removedId }
    if (index < 0) return null
    return stations.getOrNull(index + 1)?.id ?: stations.getOrNull(index - 1)?.id
}
