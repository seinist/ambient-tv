@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.ambient.tv.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks

/**
 * Media3 never selects an unsupported track and does not fail for it: the video simply plays
 * without sound. True when audio is expected, no audio track has a renderer and nothing is
 * playing, so the caller starts the audio fallback chain (local renderer → MPV → VLC).
 *
 * [audioTrackSupport] holds one list per audio group with each track's support flag.
 * [expectAudioWithoutTracks]: metadata names an audio codec although Media3 exposes no group.
 */
internal fun media3AudioUnusable(
    audioTrackSupport: List<List<Boolean>>,
    audioFormatPresent: Boolean,
    expectAudioWithoutTracks: Boolean = false,
): Boolean {
    if (audioFormatPresent) return false
    if (audioTrackSupport.isEmpty()) return expectAudioWithoutTracks
    return audioTrackSupport.none { group -> group.any { it } }
}

internal fun media3AudioUnusable(tracks: Tracks, audioFormat: Format?, expectAudioWithoutTracks: Boolean = false): Boolean =
    media3AudioUnusable(
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.map { group -> (0 until group.length).map(group::isTrackSupported) },
        audioFormat != null,
        expectAudioWithoutTracks,
    )

/** READY must hold this long first: tracks can be transiently empty while Media3 prepares. */
internal const val MEDIA3_AUDIO_CHECK_DELAY_MS = 800L
