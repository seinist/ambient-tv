package com.ambient.tv.ui.player

import com.ambient.tv.data.local.LanguagePreferences
import com.ambient.tv.domain.model.MediaTrack
import java.util.Locale

internal object PlaybackLanguagePolicy {
    fun labelMatchesLanguage(label: String?, language: String?): Boolean {
        if (label.isNullOrBlank() || language.isNullOrBlank()) return false
        val base = LanguagePreferences.baseLanguage(language) ?: return false
        val normalized = label.lowercase(Locale.ROOT)
        val names = buildSet {
            add(base)
            if (base == "de") add("ger")
            runCatching { Locale.forLanguageTag(base).isO3Language }.getOrNull()?.takeIf(String::isNotBlank)?.let(::add)
            runCatching { Locale.forLanguageTag(base).getDisplayLanguage(Locale.ENGLISH) }.getOrNull()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)?.let(::add)
            runCatching { Locale.forLanguageTag(base).getDisplayLanguage(Locale.GERMAN) }.getOrNull()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)?.let(::add)
            runCatching { Locale.forLanguageTag(base).displayLanguage }.getOrNull()?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)?.let(::add)
        }
        return names.any { token -> Regex("(^|[^a-z])${Regex.escape(token)}([^a-z]|$)").containsMatchIn(normalized) }
    }

    fun looksForced(label: String?): Boolean = label?.let {
        val lower = it.lowercase(Locale.ROOT)
        "forced" in lower || "erzwungen" in lower || "forcé" in lower || "forzati" in lower
    } == true

    /** Best-effort language recovery for backends that expose only a human-readable track label. */
    fun inferLanguageFromLabel(label: String?): String? {
        if (label.isNullOrBlank()) return null
        return COMMON_PLAYBACK_LANGUAGES.firstOrNull { labelMatchesLanguage(label, it) }
    }

    /**
     * libVLC exposes only id/name through TrackDescription. Reconcile it with provider metadata
     * when available, then fall back to language/forced tokens in the VLC label.
     */
    fun chooseVlcTrackId(
        vlcTracks: List<Pair<Int, String>>,
        sourceTracks: List<MediaTrack>,
        language: String?,
        forcedOnly: Boolean = false,
    ): Int? {
        val sourceCandidates = sourceTracks.filter { track ->
            (!forcedOnly || track.forced) && LanguagePreferences.matches(track.languageCode ?: track.language, language)
        }

        // Exact provider titles are the strongest mapping signal libVLC gives us.
        sourceCandidates.forEach { source ->
            val title = source.title?.trim()?.takeIf(String::isNotBlank) ?: return@forEach
            vlcTracks.firstOrNull { (_, label) -> label.contains(title, ignoreCase = true) }
                ?.let { return it.first }
        }

        if (forcedOnly) {
            // Prefer an explicit forced token when libVLC exposes it in TrackDescription.
            vlcTracks.firstOrNull { (_, label) ->
                labelMatchesLanguage(label, language) && looksForced(label)
            }?.let { return it.first }

            // Some libVLC builds omit the disposition from the label. In that case preserve the
            // provider's same-language track ordering so a forced provider track isn't silently
            // replaced with the first normal subtitle in that language.
            val forcedSource = sourceCandidates.firstOrNull() ?: return null
            val sameLanguageSources = sourceTracks.filter {
                LanguagePreferences.matches(it.languageCode ?: it.language, language)
            }
            val ordinal = sameLanguageSources.indexOf(forcedSource)
            val sameLanguageVlc = vlcTracks.filter { (_, label) -> labelMatchesLanguage(label, language) }
            return sameLanguageVlc.getOrNull(ordinal)?.first
        }

        sourceCandidates.forEach { source ->
            vlcTracks.firstOrNull { (_, label) ->
                labelMatchesLanguage(label, source.languageCode ?: source.language)
            }?.let { return it.first }
        }

        return vlcTracks.firstOrNull { (_, label) -> labelMatchesLanguage(label, language) }?.first
    }

    private val COMMON_PLAYBACK_LANGUAGES = listOf(
        "de", "en", "fr", "es", "it", "pt", "nl", "pl", "ru", "uk",
        "ja", "ko", "zh", "ar", "tr", "cs", "da", "sv", "no", "fi",
        "hu", "ro", "el", "he",
    )
}
