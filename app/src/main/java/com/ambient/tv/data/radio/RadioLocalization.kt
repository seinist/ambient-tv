package com.ambient.tv.data.radio

import com.ambient.tv.data.local.LanguagePreferences
import java.util.Locale

private val radioIsoLanguages: Set<String> by lazy {
    Locale.getISOLanguages().mapTo(linkedSetOf()) { it.lowercase(Locale.ROOT) }
}

private val radioLanguageNameAliases: Map<String, String> by lazy {
    buildMap {
        Locale.getISOLanguages().forEach { code ->
            val normalized = code.lowercase(Locale.ROOT)
            val locale = Locale.forLanguageTag(normalized)
            listOf(
                locale.getDisplayLanguage(Locale.ENGLISH),
                locale.getDisplayLanguage(locale),
            ).forEach { name ->
                name.trim().takeIf(String::isNotBlank)?.let {
                    putIfAbsent(it.lowercase(Locale.ROOT), normalized)
                }
            }
            runCatching { locale.isO3Language }.getOrNull()
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?.let { putIfAbsent(it.lowercase(Locale.ROOT), normalized) }
        }
    }
}

/**
 * Radio Browser keeps its own language name as the filter id. This resolves the optional ISO-639
 * metadata only for presentation; callers must not replace the provider filter id with this code.
 */
internal fun radioLanguageCode(iso639: String?, fallbackName: String?): String? {
    val candidates = buildList {
        iso639?.split(',')?.forEach(::add)
        fallbackName?.let(::add)
    }
    for (candidate in candidates) {
        val compact = candidate.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (compact.isBlank()) continue
        radioLanguageNameAliases[compact]?.let { return it }
        val base = LanguagePreferences.baseLanguage(compact)?.lowercase(Locale.ROOT)
        if (base != null && base in radioIsoLanguages) return base
        radioLanguageNameAliases[compact.substringBefore('-')]?.let { return it }
    }
    return null
}

internal fun radioLanguageDisplayName(
    iso639: String?,
    fallbackName: String?,
    displayLocale: Locale,
): String? {
    val fallback = fallbackName?.trim()?.takeIf(String::isNotBlank)
    val code = radioLanguageCode(iso639, fallback)
    val localized = code?.let {
        runCatching { Locale.forLanguageTag(it).getDisplayLanguage(displayLocale) }.getOrNull()
            ?.trim()
            ?.takeIf { name -> name.isNotBlank() && !name.equals(it, ignoreCase = true) }
    }
    return (localized ?: fallback)?.replaceFirstChar { it.titlecase(displayLocale) }
}

internal fun RadioStation.localizedLanguageNames(displayLocale: Locale): List<String> {
    val count = maxOf(languages.size, languageCodes.size)
    if (count == 0) return emptyList()
    return (0 until count).mapNotNull { index ->
        radioLanguageDisplayName(
            iso639 = languageCodes.getOrNull(index),
            fallbackName = languages.getOrNull(index),
            displayLocale = displayLocale,
        )
    }.distinctBy { it.lowercase(displayLocale) }
}
