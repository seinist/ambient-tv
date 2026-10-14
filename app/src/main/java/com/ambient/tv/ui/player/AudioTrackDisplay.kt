package com.ambient.tv.ui.player

import com.ambient.tv.data.local.LanguagePreferences
import java.util.Locale

private val availableLanguages: Set<String> by lazy { Locale.getISOLanguages().toSet() }
private val languageCodeInLabel = Regex("(?<![\\p{L}\\d])([A-Za-z]{2,3}(?:[-_][A-Za-z]{2})?)(?![\\p{L}\\d])")
// Uppercase technical abbreviations (for example ARC) must not be interpreted as ISO-639-3.
private val commonUppercaseIso3 = setOf(
    "DEU", "GER", "ENG", "FRA", "FRE", "SPA", "ITA", "POR", "NLD", "DUT",
    "POL", "RUS", "UKR", "JPN", "KOR", "ZHO", "CHI", "ARA", "TUR", "CES", "CZE",
    "DAN", "SWE", "NOR", "FIN", "HUN", "RON", "RUM", "ELL", "GRE", "HEB",
)

/** Display-only expansion. The backend track IDs and original language metadata stay intact. */
fun audioLanguageDisplayName(code: String?, displayLocale: Locale): String? {
    val raw = code?.trim()?.takeIf(String::isNotBlank) ?: return null
    if (raw.equals("und", true) || raw.equals("auto", true)) return null
    val base = LanguagePreferences.baseLanguage(raw)?.takeIf { it in availableLanguages } ?: return null
    return Locale.forLanguageTag(base).getDisplayLanguage(displayLocale)
        .takeIf { it.isNotBlank() && !it.equals(base, true) }
        ?.replaceFirstChar { it.titlecase(displayLocale) }
}

fun expandAudioLanguageCodes(label: String, displayLocale: Locale): String =
    languageCodeInLabel.replace(label) { match ->
        val code = match.value
        if (code.length == 3 && code == code.uppercase(Locale.ROOT) && code !in commonUppercaseIso3) code
        else audioLanguageDisplayName(code, displayLocale) ?: code
    }
