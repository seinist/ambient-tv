package com.ambient.tv.data.local

import com.ambient.tv.domain.model.MediaTrack
import com.ambient.tv.domain.model.MediaTrackType
import com.ambient.tv.ui.player.AudioTrackMetadata
import com.ambient.tv.ui.player.choosePreferredAudioTrack

/** Never silently replace an explicit stream ID with the first same-language commentary. */
internal fun audioTrackForConversion(
    tracks: List<MediaTrack>, language: String?, preference: AudioTrackPreference, streamId: Long? = null,
): MediaTrack? {
    val audio = tracks.filter { it.type == MediaTrackType.AUDIO }
    if (streamId != null) return requireNotNull(audio.firstOrNull { it.id == streamId }) {
        "Selected audio stream is no longer available"
    }
    val effective = if (preference.role == "auto") preference.copy(role = "main") else preference
    return choosePreferredAudioTrack(audio, language, effective) {
        AudioTrackMetadata(it.languageCode ?: it.language, it.title, it.channels ?: 0)
    } ?: audio.firstOrNull { it.selected } ?: audio.firstOrNull()
}
