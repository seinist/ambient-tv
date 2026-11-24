package com.ambient.tv.games.library

import java.util.Locale

/** A display/search suggestion only: DAT titles and provider identities must keep their region tags. */
internal fun gameDisplayTitle(title: String): String = gameTitlePresentation(title).title

/** The removed tags remain available to distinguish editions in a list of metadata matches. */
internal fun gameTitleRegionLabel(title: String): String? = gameTitlePresentation(title).regionLabel

private data class GameTitlePresentation(val title: String, val regionLabel: String? = null)
private data class GameTitleSuffix(val text: String, val regions: List<String>?)

private fun gameTitlePresentation(title: String): GameTitlePresentation {
    var prefix = title.trimEnd()
    val suffixes = mutableListOf<GameTitleSuffix>()
    while (prefix.isNotEmpty()) {
        val opening = when (prefix.last()) {
            ')' -> '('
            ']' -> '['
            else -> break
        }
        val start = prefix.lastIndexOf(opening, prefix.lastIndex - 1)
        if (start < 0) break
        val content = prefix.substring(start + 1, prefix.lastIndex)
        // A nested or unmatched title annotation is not a simple ROM region tag.
        if (content.any { it in "()[]" }) break
        val parts = content.split(',', '/', '+').map(String::trim)
        val regions = parts.takeIf { values ->
            values.all { it in gameTitleUppercaseRegions || it.lowercase(Locale.ROOT) in gameTitleRegionNames }
        }
        suffixes += GameTitleSuffix(prefix.substring(start), regions)
        prefix = prefix.substring(0, start).trimEnd()
    }
    val orderedSuffixes = suffixes.asReversed()
    val regions = orderedSuffixes.flatMap { it.regions.orEmpty() }
        .distinctBy { it.lowercase(Locale.ROOT) }
    if (regions.isEmpty()) return GameTitlePresentation(title)
    val display = buildString {
        append(prefix)
        orderedSuffixes.filter { it.regions == null }.forEach { suffix ->
            if (isNotEmpty()) append(' ')
            append(suffix.text)
        }
    }
    // Malformed entries consisting entirely of region tags still need a visible title.
    if (display.isBlank()) return GameTitlePresentation(title)
    return GameTitlePresentation(display, regions.joinToString(", "))
}

// Only explicit geographical names and common ROM region abbreviations. Language codes such as
// En, Fr, De, It, Es, Pt or Ru are deliberately absent, as are revisions, discs and video standards.
// Eu (Basque) and Uk (Ukrainian) overlap region codes, so only uppercase EU/UK mean a region here.
private val gameTitleUppercaseRegions = setOf("EU", "UK")
private val gameTitleRegionNames = setOf(
    "usa", "us", "u.s.a.", "u.s.", "u", "united states", "united states of america", "north america",
    "europe", "eur", "e", "japan", "jpn", "jp", "j", "world", "worldwide", "ww", "w",
    "asia", "australia", "australasia", "oceania", "new zealand", "korea", "south korea",
    "china", "taiwan", "hong kong", "brazil", "canada", "mexico", "argentina", "chile", "peru",
    "south america", "latin america", "united kingdom", "u.k.", "great britain", "england",
    "germany", "france", "italy", "spain", "sweden", "switzerland", "netherlands", "holland",
    "finland", "norway", "denmark", "scandinavia", "portugal", "russia", "poland", "czech republic",
    "czechia", "hungary", "greece", "turkey", "middle east", "israel", "india", "south africa",
)
