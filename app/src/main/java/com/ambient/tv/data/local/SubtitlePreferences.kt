package com.ambient.tv.data.local

import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType

/** Full and forced are deliberately disjoint: missing forced subtitles never enable full dialogue. */
enum class SubtitleMode(val key: String) {
    OFF("off"), FORCED("forced"), FULL("full");
    companion object { fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: OFF }
}

data class SubtitlePreferences(
    val mode: SubtitleMode = SubtitleMode.OFF,
    val language: String = LanguagePreferences.AUTO,
    val fallbackLanguage: String = "none",
    val fullOnForeignAudio: Boolean = false,
    val preferredAudioLanguage: String = LanguagePreferences.AUTO,
    val forcedInUiLanguage: Boolean = false,
    val uiLanguage: String = LanguagePreferences.AUTO,
) {
    fun effectiveMode(audioLanguage: String?, systemLanguage: String): SubtitleMode {
        val audio = LanguagePreferences.baseLanguage(audioLanguage)
        val preferred = LanguagePreferences.resolveViewerLanguage(preferredAudioLanguage, uiLanguage, systemLanguage)
        return if (fullOnForeignAudio && audio != null && audio !in setOf("und", "zxx", "mul", "auto") &&
            !LanguagePreferences.matches(audio, preferred)) SubtitleMode.FULL
        else if (forcedInUiLanguage && mode != SubtitleMode.FULL) SubtitleMode.FORCED else mode
    }

    fun targetLanguage(audioLanguage: String?, systemLanguage: String): String =
        if (forcedInUiLanguage && effectiveMode(audioLanguage, systemLanguage) == SubtitleMode.FORCED)
            uiLanguage.takeUnless { it == LanguagePreferences.AUTO } ?: systemLanguage
        else if (language != LanguagePreferences.AUTO) language
        else LanguagePreferences.resolveViewerLanguage(preferredAudioLanguage, uiLanguage, systemLanguage)
}

data class SubtitleCandidate<T>(val id: T, val language: String?, val forced: Boolean, val selected: Boolean = false)

object SubtitleSelectionPolicy {
    fun <T> choose(tracks: List<SubtitleCandidate<T>>, preferences: SubtitlePreferences,
                   audioLanguage: String?, systemLanguage: String): SubtitleCandidate<T>? {
        val mode = preferences.effectiveMode(audioLanguage, systemLanguage)
        if (mode == SubtitleMode.OFF) return null
        val eligible = tracks.filter { it.forced == (mode == SubtitleMode.FORCED) }
        val target = preferences.targetLanguage(audioLanguage, systemLanguage)
        val primary = eligible.firstOrNull { LanguagePreferences.matches(it.language, target) }
        // This option explicitly requests UI-language forced subtitles, never a different language.
        if (preferences.forcedInUiLanguage && mode == SubtitleMode.FORCED) return primary
        return primary
            ?: preferences.fallbackLanguage.takeUnless { it == "none" || it == LanguagePreferences.AUTO }
                ?.let { fallback -> eligible.firstOrNull { LanguagePreferences.matches(it.language, fallback) } }
    }

    fun chooseMediaTrack(tracks: List<MediaTrack>, preferences: SubtitlePreferences,
                         audioLanguage: String?, systemLanguage: String): MediaTrack? {
        val choices = tracks.filter { it.type == MediaTrackType.SUBTITLE }
        val selected = choose(choices.mapIndexed { i, track ->
            SubtitleCandidate(i, track.languageCode ?: track.language, track.forced)
        }, preferences, audioLanguage, systemLanguage) ?: return null
        return choices[selected.id]
    }
}
