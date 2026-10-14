@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import com.ambient.tv.data.local.AudioTrackPreference
import com.ambient.tv.data.local.LanguagePreferences
import java.util.Locale

internal data class AudioTrackMetadata(
    val language: String?, val title: String?, val channels: Int = 0, val roles: Set<String> = emptySet(),
)

internal fun <T> choosePreferredAudioTrack(
    tracks: List<T>, language: String?, preference: AudioTrackPreference, metadata: (T) -> AudioTrackMetadata,
): T? {
    // "Original language": an original-flagged track if there is one, else the default track (null).
    if (language == LanguagePreferences.ORIGINAL) return tracks.firstOrNull { track ->
        val m = metadata(track)
        "original" in m.roles || Regex("\\boriginal\\b").containsMatchIn(m.title.orEmpty().lowercase(Locale.ROOT))
    }
    fun matchesLanguage(track: T): Boolean {
        val m = metadata(track)
        val known = LanguagePreferences.baseLanguage(m.language)?.takeUnless { it in setOf("und", "zxx", "mul") }
        return if (known != null) LanguagePreferences.matches(known, language)
        else PlaybackLanguagePolicy.labelMatchesLanguage(m.title, language)
    }
    // Language is mandatory when requested; role/channel preferences only rank matching tracks.
    val candidates = if (language.isNullOrBlank()) tracks else tracks.filter(::matchesLanguage)
    if (!preference.enabled) return candidates.firstOrNull().takeUnless { language.isNullOrBlank() }
    fun score(track: T): Int {
        val m = metadata(track)
        val label = m.title.orEmpty().lowercase(Locale.ROOT)
        val commentary = "commentary" in m.roles || Regex("commentary|kommentar|commentaire").containsMatchIn(label)
        val description = "description" in m.roles || Regex("audio.?description|audiodeskription|descriptive|hörfilm").containsMatchIn(label)
        val original = "original" in m.roles || Regex("\\boriginal\\b").containsMatchIn(label)
        var score = if (matchesLanguage(track)) 1000 else 0
        score += when (preference.role) {
            "main" -> if (commentary || description) -100 else if ("main" in m.roles) 120 else 40
            "original" -> if (original) 120 else if (commentary || description) -100 else 0
            "commentary" -> if (commentary) 120 else 0
            "description" -> if (description) 120 else 0
            else -> 0
        }
        val channels = if (m.channels > 0) m.channels else when {
            "7.1" in label -> 8; "5.1" in label -> 6; "stereo" in label || "2.0" in label -> 2; else -> 0
        }
        return score + when (preference.channels) { "stereo" -> if (channels == 2) 10 else 0; "surround" -> if (channels > 2) 10 else 0; else -> 0 }
    }
    return candidates.maxByOrNull(::score)?.takeIf { score(it) > 0 }
}

internal fun <T> chooseMedia3AudioTrack(player: ExoPlayer, tracks: List<T>, language: String?, format: (T) -> Format): T? {
    val saved = Media3AudioProcessing.session(player)?.settings?.tracks ?: AudioTrackPreference()
    val preference = if (saved.role == "auto") saved.copy(role = "main") else saved
    return choosePreferredAudioTrack(tracks, language, preference) {
        val f = format(it)
        AudioTrackMetadata(f.language, f.label, f.channelCount, buildSet {
            if (f.roleFlags and C.ROLE_FLAG_MAIN != 0) add("main")
            if (f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0) add("commentary")
            if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) add("description")
        })
    }
}
