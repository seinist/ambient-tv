package com.ambient.tv.ui.radio

import com.ambient.tv.data.radio.RadioFacet
import com.ambient.tv.data.radio.radioLanguageDisplayName
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Display titles are localized before sorting, grouping and rendering the alphabet. */
internal fun radioFacetDisplayName(section: RadioSection, facet: RadioFacet, locale: Locale): String = when (section) {
    RadioSection.COUNTRIES -> runCatching {
        Locale.Builder().setRegion(facet.id).build().getDisplayCountry(locale).takeIf(String::isNotBlank)
    }.getOrNull() ?: facet.name
    RadioSection.LANGUAGES -> radioLanguageDisplayName(facet.languageCode, facet.name, locale)
        ?: facet.name.replaceFirstChar { it.titlecase(locale) }
    RadioSection.GENRES -> radioGenreDisplayName(facet.name)
    else -> facet.name
}

/** A, Ä, Å etc. share a bucket; entries in other alphabets still get their own index letters. */
internal fun radioFacetInitial(title: String): String {
    val first = title.trimStart().firstOrNull() ?: return "#"
    if (!first.isLetter()) return "#"
    val normalized = Normalizer.normalize(first.toString(), Normalizer.Form.NFKD)
    val base = normalized.firstOrNull { it.isLetter() } ?: first
    val upper = base.uppercase(Locale.ROOT)
    return upper.firstOrNull()?.toString() ?: "#"
}

internal data class RadioAlphabetFacet(val facet: RadioFacet, val title: String, val letter: String)
internal data class RadioAlphabetJump(val letter: String, val index: Int)

internal fun radioAlphabetFacets(section: RadioSection, facets: List<RadioFacet>, locale: Locale): List<RadioAlphabetFacet> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
    return facets.map { facet ->
        val title = radioFacetDisplayName(section, facet, locale)
        RadioAlphabetFacet(facet, title, radioFacetInitial(title))
    }.sortedWith { left, right ->
        val compared = collator.compare(left.title, right.title)
        if (compared != 0) compared else left.facet.id.compareTo(right.facet.id)
    }
}

/**
 * The shared AlphabetBar shows only letters represented by the current localized list.
 * Keep alphabetic order for navigation, including non-Latin initials and "#" for symbols.
 */
internal fun radioAlphabetJumps(entries: List<RadioAlphabetFacet>): List<RadioAlphabetJump> {
    val firstIndices = linkedMapOf<String, Int>()
    entries.forEachIndexed { index, entry -> firstIndices.putIfAbsent(entry.letter, index) }
    val latin = ('A'..'Z').map(Char::toString)
    val extras = firstIndices.keys.filterNot { it in latin }.sortedWith(
        compareBy<String> { it != "#" }.thenBy { it },
    )
    return (latin + extras).mapNotNull { letter ->
        firstIndices[letter]?.let { RadioAlphabetJump(letter, it) }
    }
}
