package com.ambient.tv.data.local

import android.content.res.Resources
import java.util.Locale

/** Shared language-key handling for UI-independent metadata/playback preferences. */
object LanguagePreferences {
    const val AUTO = "auto"
    /** Audio preference: keep the title's original (default) audio track. */
    const val ORIGINAL = "original"
    val SETTINGS_LANGUAGE_KEYS = setOf(AUTO, "de", "en")

    private val aliases = mapOf(
        "deu" to "de", "ger" to "de", "deutsch" to "de", "german" to "de",
        "eng" to "en", "english" to "en",
    )

    private val isoAliases by lazy {
        Locale.getISOLanguages().associateBy { runCatching { Locale.forLanguageTag(it).isO3Language }.getOrDefault(it) } +
            // ISO 639-2/B codes (Matroska, Plex) next to the /T codes Locale knows.
            mapOf("fre" to "fr", "dut" to "nl", "cze" to "cs", "chi" to "zh", "gre" to "el", "rum" to "ro", "slo" to "sk",
                "ger" to "de", "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "geo" to "ka", "ice" to "is",
                "mac" to "mk", "mao" to "mi", "may" to "ms", "per" to "fa", "tib" to "bo", "wel" to "cy",
                "scc" to "sr", "scr" to "hr", "jpn" to "ja", "heb" to "he", "ind" to "id", "yid" to "yi")
    }

    fun normalizeKey(value: String?): String = value?.trim()?.takeIf(String::isNotEmpty) ?: AUTO

    /** Android device locale, deliberately independent from an app-local UI override. */
    fun systemLanguageTag(): String {
        val configuration = Resources.getSystem().configuration
        val locale = if (android.os.Build.VERSION.SDK_INT >= 24) configuration.locales[0] else @Suppress("DEPRECATION") configuration.locale
        return locale.toLanguageTag().takeIf(String::isNotBlank) ?: Locale.getDefault().toLanguageTag()
    }

    fun resolve(key: String?): String = normalizeKey(key).let { if (it == AUTO) systemLanguageTag() else it }

    /** Playback Auto follows the app UI; only an automatic UI falls back to the device. */
    fun resolvePlayback(key: String?, uiLanguage: String?, systemLanguage: String): String {
        val requested = normalizeKey(key)
        if (requested == ORIGINAL) return ORIGINAL
        val ui = normalizeKey(uiLanguage)
        return baseLanguage(when {
            requested != AUTO -> requested
            ui != AUTO -> ui
            else -> systemLanguage
        }) ?: systemLanguage
    }

    /** Language a viewer understands: an "original" audio preference falls back to UI/device. */
    fun resolveViewerLanguage(key: String?, uiLanguage: String?, systemLanguage: String): String =
        resolvePlayback(normalizeKey(key).takeUnless { it == ORIGINAL }, uiLanguage, systemLanguage)

    /** All ISO 639-1 languages, named in [displayLocale] and sorted by that name. */
    fun playbackLanguageChoices(displayLocale: Locale): List<Pair<String, String>> {
        val collator = java.text.Collator.getInstance(displayLocale)
        return Locale.getISOLanguages().asSequence()
            .map { it to Locale.forLanguageTag(it).getDisplayLanguage(displayLocale) }
            .filter { (code, name) -> name.isNotBlank() && !name.equals(code, ignoreCase = true) }
            .map { (code, name) -> code to name.replaceFirstChar { it.titlecase(displayLocale) } }
            .distinctBy { it.first }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
            .toList()
    }

    fun playbackLanguageTags(language: String): List<String> {
        if (language == ORIGINAL) return emptyList()
        val base = baseLanguage(language) ?: return emptyList()
        return listOfNotNull(base,
            runCatching { Locale.forLanguageTag(base).isO3Language }.getOrNull()?.takeIf(String::isNotBlank),
            if (base == "de") "ger" else null).distinct()
    }

    fun baseLanguage(value: String?): String? {
        val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val lower = raw.lowercase(Locale.ROOT).replace('_', '-')
        aliases[lower]?.let { return it }
        aliases[lower.substringBefore('-')]?.let { return it }
        isoAliases[lower.substringBefore('-')]?.let { return it }
        val localeLanguage = runCatching { Locale.forLanguageTag(lower).language }.getOrNull()?.takeIf(String::isNotBlank)
        return aliases[localeLanguage] ?: localeLanguage ?: lower.substringBefore('-')
    }

    fun matches(candidate: String?, preferred: String?): Boolean {
        if (preferred == ORIGINAL) return false
        val left = baseLanguage(candidate) ?: return false
        val right = baseLanguage(preferred) ?: return false
        return left.equals(right, ignoreCase = true)
    }

    fun displayName(value: String, displayLocale: Locale = Locale.getDefault()): String {
        if (value == AUTO) return AUTO
        val tag = resolve(value)
        return runCatching { Locale.forLanguageTag(tag).getDisplayLanguage(displayLocale) }
            .getOrNull()?.takeIf(String::isNotBlank) ?: tag
    }
}
