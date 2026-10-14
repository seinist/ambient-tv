package com.ambient.tv.ui.youtube

import com.ambient.tv.data.local.LanguagePreferences
import java.util.Locale

private val youtubeLanguageSegment = Regex(
    "^([A-Za-z]{2,3}(?:[-_\u2010][A-Za-z0-9]{2,8})*)(?:\\s+\\(([^)]+)\\))?$",
)
private val youtubeOriginalMarker = Regex("(?:^|[\\s(])original(?:$|[\\s)])", RegexOption.IGNORE_CASE)

/** Turns YouTube/MPD language tags such as `de-DE` into localized language names. */
internal fun youtubeTrackLabel(raw: String, displayLocale: Locale): String =
    raw.split(" · ").joinToString(" · ") { segment ->
        val match = youtubeLanguageSegment.matchEntire(segment.trim()) ?: return@joinToString segment
        val languageTag = match.groupValues[1].replace('\u2010', '-').replace('_', '-')
        val language = LanguagePreferences.displayName(languageTag, displayLocale)
        val qualifier = match.groupValues[2].takeIf(String::isNotBlank)
        if (qualifier == null) language else "$language (${qualifier.replaceFirstChar { it.titlecase(displayLocale) }})"
    }

internal fun isYouTubeOriginalAudioTrack(language: String?, label: String?): Boolean =
    listOfNotNull(language, label).any { youtubeOriginalMarker.containsMatchIn(it) }
