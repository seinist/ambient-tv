@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import com.ambient.tv.data.local.*
import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

private fun subtitleTrackLanguage(language: String?, label: String?): String? =
    language?.takeUnless { it.isBlank() || LanguagePreferences.baseLanguage(it) in setOf("und", "zxx", "mul") }
        ?: PlaybackLanguagePolicy.inferLanguageFromLabel(label)

/** Main-thread adapter; selection is shared by Plex, Jellyfin, files, YouTube and TVheadend. */
internal fun PlaybackHandle.subtitleCandidates(source: List<MediaTrack>): List<SubtitleCandidate<String>> = when (this) {
    is Media3PlaybackHandle -> player.currentTracks.groups.flatMapIndexed { gi, group ->
        if (group.type != C.TRACK_TYPE_TEXT) emptyList() else (0 until group.length).mapNotNull { ti ->
            if (!group.isTrackSupported(ti)) null else group.getTrackFormat(ti).let { format ->
                SubtitleCandidate("$gi:$ti", subtitleTrackLanguage(format.language, format.label),
                    format.selectionFlags and C.SELECTION_FLAG_FORCED != 0 || PlaybackLanguagePolicy.looksForced(format.label),
                    group.isTrackSelected(ti) && !player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT))
            }
        }
    }
    is MpvPlaybackController -> subtitleTracks().map { SubtitleCandidate(it.id.toString(), subtitleTrackLanguage(it.language, it.title), it.forced || PlaybackLanguagePolicy.looksForced(it.title), it.selected) }
    is VlcPlaybackController -> {
        val tracks = subtitleTracks().filter { it.first >= 0 }
        val nativeLanguages = subtitleTrackLanguages()
        fun languageFor(id: Int, label: String): String? = nativeLanguages[id]
            ?.takeUnless { it.isBlank() || LanguagePreferences.baseLanguage(it) in setOf("und", "zxx", "mul") }
            ?: PlaybackLanguagePolicy.inferLanguageFromLabel(label)
        tracks.map { (id, label) ->
            val language = languageFor(id, label)
            val provider = externalSubtitleSource(id) ?: source.filter { it.type == MediaTrackType.SUBTITLE && it.externalUrl == null }.filter {
                !it.title.isNullOrBlank() && label.equals(it.title, ignoreCase = true)
            }.singleOrNull() ?: source.filter { it.type == MediaTrackType.SUBTITLE && it.externalUrl == null && LanguagePreferences.matches(it.languageCode ?: it.language, language) }
                .getOrNull(tracks.filter { LanguagePreferences.matches(languageFor(it.first, it.second), language) }.indexOfFirst { it.first == id })
            SubtitleCandidate(id.toString(), language ?: provider?.languageCode ?: provider?.language,
                PlaybackLanguagePolicy.looksForced(label) || provider?.forced == true, id == currentSubtitleTrackId())
        }
    }
    else -> emptyList()
}

internal fun PlaybackHandle.selectedAudioLanguage(): String? = when (this) {
    is Media3PlaybackHandle -> player.currentTracks.groups.firstNotNullOfOrNull { group ->
        if (group.type != C.TRACK_TYPE_AUDIO) null else (0 until group.length).firstOrNull(group::isTrackSelected)?.let { group.getTrackFormat(it).language }
    }
    is MpvPlaybackController -> audioTracks().firstOrNull { it.selected }?.language
    is VlcPlaybackController -> audioTrackLanguage()
    else -> null
}

internal fun PlaybackHandle.applySubtitle(id: String?) {
    when (this) {
        is Media3PlaybackHandle -> {
            val parameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, id == null)
            id?.split(':')?.let { parts ->
                val group = parts.getOrNull(0)?.toIntOrNull()?.let(player.currentTracks.groups::getOrNull)
                val ti = parts.getOrNull(1)?.toIntOrNull()
                if (group != null && ti != null && ti in 0 until group.length)
                    parameters.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ti))
            }
            player.trackSelectionParameters = parameters.build()
        }
        is MpvPlaybackController -> selectSubtitle(id?.toIntOrNull())
        is VlcPlaybackController -> selectSubtitleTrack(id?.toIntOrNull())
    }
}

/** Manual choices survive renderer changes; foreign-audio automation resumes on the next audio language change. */
internal class SubtitleSessionPolicy {
    private var defaults: SubtitlePreferences? = null
    private var manual = false
    private var restoringManual = false
    private var manualTrack: SubtitleCandidate<String>? = null
    private var appliedSignature: Any? = null
    private var lastKnownAudioLanguage: String? = null

    fun manualSelection() { manual = true; restoringManual = false }
    fun replaceRenderer(previous: PlaybackHandle, source: List<MediaTrack>) {
        if (manual) { manualTrack = previous.subtitleCandidates(source).firstOrNull { it.selected }; restoringManual = true }
        appliedSignature = null
    }
    fun apply(
        handle: PlaybackHandle,
        source: List<MediaTrack>,
        preferences: SubtitlePreferences,
        audioFallback: String,
        systemLanguage: String,
        allowExternal: Boolean,
        nativeSubtitlesSuppressed: Boolean = false,
    ) {
        if (defaults != null && defaults != preferences) { manual = false; restoringManual = false; appliedSignature = null }
        defaults = preferences
        val selectedAudio = handle.selectedAudioLanguage()
        val knownAudio = LanguagePreferences.baseLanguage(selectedAudio)
            ?.takeUnless { it in setOf("und", "zxx", "mul", "auto") }
        if (knownAudio != null) {
            if (preferences.fullOnForeignAudio && lastKnownAudioLanguage != null && lastKnownAudioLanguage != knownAudio) {
                manual = false
                restoringManual = false
                appliedSignature = null
            }
            lastKnownAudioLanguage = knownAudio
        }
        if (nativeSubtitlesSuppressed) {
            // Keep preference/audio changes current without selecting a track over teletext.
            handle.applySubtitle(null)
            if (allowExternal) loadAllExternal(handle, source)
            return
        }
        // Also after a manual choice: files still loading must keep appearing in the panel.
        if (manual && !restoringManual) { if (allowExternal) loadAllExternal(handle, source); return }
        // Unknown track metadata must not trigger foreign-language automation via a guessed fallback.
        val audio = selectedAudio ?: if (preferences.fullOnForeignAudio) null else audioFallback
        val external = if (manual) manualTrack?.let { old -> source.firstOrNull {
            it.type == MediaTrackType.SUBTITLE && it.externalUrl != null && it.forced == old.forced &&
                LanguagePreferences.matches(it.languageCode ?: it.language, old.language)
        } } else SubtitleSelectionPolicy.chooseMediaTrack(source, preferences, audio, systemLanguage)
        if (allowExternal) external?.takeIf { it.externalUrl != null }?.let { track ->
            when (handle) {
                is MpvPlaybackController -> handle.ensureExternalSubtitle(track)
                is VlcPlaybackController -> handle.ensureExternalSubtitle(track)
            }
        }
        if (allowExternal) loadAllExternal(handle, source)
        val tracks = handle.subtitleCandidates(source)
        val desired = if (manual) manualTrack?.let { old -> tracks.firstOrNull { it.forced == old.forced && LanguagePreferences.matches(it.language, old.language) } }
            else SubtitleSelectionPolicy.choose(tracks, preferences, audio, systemLanguage)
        val signature = listOf(tracks.map { Triple(it.id, it.language, it.forced) }, preferences, audio, desired?.id)
        if (signature != appliedSignature || tracks.firstOrNull { it.selected }?.id != desired?.id) {
            handle.applySubtitle(desired?.id)
            appliedSignature = signature
        }
        if (restoringManual && (manualTrack == null || desired != null)) restoringManual = false
    }
}

/**
 * Every external file (server subtitles, files next to the video, OpenSubtitles downloads) is loaded
 * without being selected, so MPV and VLC list it in the subtitle panel as Media3 does. Both engines
 * remember what they already requested; VLC takes one file at a time, so a later tick adds the next.
 */
private fun loadAllExternal(handle: PlaybackHandle, source: List<MediaTrack>) {
    if (handle !is MpvPlaybackController && handle !is VlcPlaybackController) return
    source.filter { it.type == MediaTrackType.SUBTITLE && !it.externalUrl.isNullOrBlank() }.take(MAX_LOADED_EXTERNAL).forEach { track ->
        when (handle) {
            is MpvPlaybackController -> handle.ensureExternalSubtitle(track)
            is VlcPlaybackController -> handle.ensureExternalSubtitle(track)
        }
    }
}

/** Upper bound of side-loaded subtitle files per title (servers can offer dozens of languages). */
private const val MAX_LOADED_EXTERNAL = 16
