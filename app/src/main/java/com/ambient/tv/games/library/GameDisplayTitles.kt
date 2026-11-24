package com.ambient.tv.games.library

import java.util.Locale

/** A display/search suggestion only: DAT titles and provider identities keep their exact tags. */
internal fun gameDisplayTitle(title: String): String = gameTitlePresentation(title).title

/** The removed tags remain available to distinguish editions in a list of metadata matches. */
internal fun gameTitleRegionLabel(title: String): String? = gameTitlePresentation(title).regionLabel

/** Language editions remain distinguishable after their locale tags leave the primary title. */
internal fun gameTitleLanguageLabel(title: String): String? = gameTitlePresentation(title).languageLabel

private data class GameTitlePresentation(
    val title: String,
    val regionLabel: String? = null,
    val languageLabel: String? = null,
)
private data class GameTitleSuffix(
    val text: String,
    val regions: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
) {
    val removable: Boolean get() = regions.isNotEmpty() || languages.isNotEmpty()
}

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
        // A nested or unmatched title annotation is not a simple ROM locale tag.
        if (content.any { it in "()[]" }) break
        val parts = content.split(',', '/', '+').map(String::trim)
        val regions = mutableListOf<String>()
        val languages = mutableListOf<String>()
        val recognized = parts.all { value ->
            when {
                value in gameTitleUppercaseRegions || value.lowercase(Locale.ROOT) in gameTitleRegionNames -> {
                    regions += value
                    true
                }
                else -> gameTitleLanguages[value.replace('_', '-').replace(' ', '-').lowercase(Locale.ROOT)]
                    ?.let { languages += it; true } ?: false
            }
        }
        // Unknown/mixed annotations stay whole: never drop one word from a real title.
        suffixes += if (recognized) GameTitleSuffix(prefix.substring(start), regions, languages)
            else GameTitleSuffix(prefix.substring(start))
        prefix = prefix.substring(0, start).trimEnd()
    }
    val orderedSuffixes = suffixes.asReversed()
    val regions = orderedSuffixes.flatMap { it.regions }
        .distinctBy { it.lowercase(Locale.ROOT) }
    val languages = orderedSuffixes.flatMap { it.languages }.distinct()
    if (regions.isEmpty() && languages.isEmpty()) return GameTitlePresentation(title)
    val display = buildString {
        append(prefix)
        orderedSuffixes.filterNot { it.removable }.forEach { suffix ->
            if (isNotEmpty()) append(' ')
            append(suffix.text)
        }
    }
    // Malformed entries consisting entirely of locale tags still need a visible title.
    if (display.isBlank()) return GameTitlePresentation(title)
    return GameTitlePresentation(display,
        regions.takeIf { it.isNotEmpty() }?.joinToString(", "),
        languages.takeIf { it.isNotEmpty() }?.joinToString(", "))
}

// No-Intro's documented language vocabulary, including regional/script variants. An arbitrary
// short word is not a language: for example a title's (II) must not disappear as an ISO code.
// https://wiki.no-intro.org/index.php?title=Naming_Convention
// Spaces also cover locale underscores already expanded by gameDisplayFileName.
private val gameTitleLanguages = listOf(
    "En", "Ja", "Fr", "De", "Es", "It", "Nl", "Pt", "Sv", "No", "Da", "Fi", "Zh", "Ko", "Pl", "Ru",
    "El", "Tr", "Cs", "Hu", "Ca", "Th", "Hr", "Hi", "Ar", "He", "Is", "Sl", "Ro", "Bg", "Uk", "Gd",
    "Sr", "Id", "Gl", "Vi", "Eo", "Sk", "Kw", "Nb", "Nn", "Eu", "Uz", "Fa", "Lt", "Tl", "Ms",
    "En-US", "En-GB", "Fr-FR", "Fr-CA", "Es-ES", "Es-XL", "Es-MX", "Pt-PT", "Pt-BR", "Zh-Hant", "Zh-Hans",
).associateBy { it.lowercase(Locale.ROOT) }

// Geography is kept separate from languages in metadata result labels. Eu (Basque) and Uk
// (Ukrainian) overlap region codes, so only uppercase EU/UK mean a region here.
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
