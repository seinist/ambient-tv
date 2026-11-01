@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.ambient.tv.ui.telegram

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.ambient.tv.ui.player.Media3PlaybackHandle
import com.ambient.tv.ui.player.PlaybackHandle
import com.ambient.tv.ui.player.backend.MpvPlaybackController
import com.ambient.tv.ui.player.backend.VlcPlaybackController

/** Playback controls from the actual engine; no empty placeholder track lists. */
internal data class MonogramRendererControls(
    val speed: Float = 1f,
    val onSpeedChanged: ((Float) -> Unit)? = null,
    val audio: List<Pair<String, String>> = emptyList(),
    val selectedAudio: String = "",
    val selectAudio: (String) -> Unit = {},
    val subtitles: List<Pair<String, String>> = listOf("off" to "Off"),
    val selectedSubtitle: String = "off",
    val selectSubtitle: (String) -> Unit = {},
)

internal fun monogramRendererControls(handle: PlaybackHandle?): MonogramRendererControls = when (handle) {
    is Media3PlaybackHandle -> {
        val player = handle.player
        val audio = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group -> (0 until group.length).filter(group::isTrackSupported)
                .map { index -> group to index } }
        val subtitles = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { group -> (0 until group.length).filter(group::isTrackSupported)
                .map { index -> group to index } }
        fun label(pair: Pair<Tracks.Group, Int>, prefix: String): String {
            val (group, index) = pair
            val format = group.getTrackFormat(index)
            return listOfNotNull(
                format.label?.takeIf(String::isNotBlank),
                format.language?.takeIf { it.isNotBlank() && it != "und" },
                format.codecs ?: format.sampleMimeType,
            ).distinct().joinToString(" · ").ifBlank { "$prefix ${index + 1}" }
        }
        fun apply(type: Int, options: List<Pair<Tracks.Group, Int>>, key: String) {
            val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
            val selected = key.toIntOrNull()?.let(options::getOrNull)
            if (type == C.TRACK_TYPE_TEXT && selected == null) {
                builder.setTrackTypeDisabled(type, true)
            } else {
                builder.setTrackTypeDisabled(type, false)
                selected?.let { (group, index) ->
                    builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                }
            }
            player.trackSelectionParameters = builder.build()
        }
        MonogramRendererControls(
            speed = player.playbackParameters.speed,
            onSpeedChanged = player::setPlaybackSpeed,
            audio = listOf("auto" to "Auto") +
                audio.mapIndexed { index, value -> index.toString() to label(value, "Audio") },
            selectedAudio = audio.indexOfFirst { (group, index) -> group.isTrackSelected(index) }
                .takeIf { it >= 0 }?.toString() ?: "auto",
            selectAudio = { apply(C.TRACK_TYPE_AUDIO, audio, it) },
            subtitles = listOf("off" to "Off") +
                subtitles.mapIndexed { index, value -> index.toString() to label(value, "Subtitle") },
            selectedSubtitle = subtitles.indexOfFirst { (group, index) -> group.isTrackSelected(index) }
                .takeIf { it >= 0 }?.toString() ?: "off",
            selectSubtitle = { apply(C.TRACK_TYPE_TEXT, subtitles, it) },
        )
    }
    is VlcPlaybackController -> MonogramRendererControls(
        speed = handle.currentSpeed(),
        onSpeedChanged = handle::setSpeed,
        audio = handle.audioTracks().map { (id, label) -> id.toString() to label },
        selectedAudio = handle.currentAudioTrackId().toString(),
        selectAudio = { it.toIntOrNull()?.let(handle::selectAudioTrack) },
        subtitles = listOf("off" to "Off") +
            handle.subtitleTracks().map { (id, label) -> id.toString() to label },
        selectedSubtitle = handle.currentSubtitleTrackId().takeIf { it >= 0 }?.toString() ?: "off",
        selectSubtitle = { key -> handle.selectSubtitleTrack(key.toIntOrNull()) },
    )
    is MpvPlaybackController -> MonogramRendererControls(
        speed = handle.currentSpeed(),
        onSpeedChanged = handle::setSpeed,
        audio = listOf("auto" to "Auto") +
            handle.audioTracks().map { it.id.toString() to it.label },
        selectedAudio = handle.audioTracks().firstOrNull { it.selected }?.id?.toString() ?: "auto",
        selectAudio = { key -> handle.selectAudio(key.toIntOrNull()) },
        subtitles = listOf("off" to "Off") +
            handle.subtitleTracks().map { it.id.toString() to it.label },
        selectedSubtitle = handle.subtitleTracks().firstOrNull { it.selected }?.id?.toString() ?: "off",
        selectSubtitle = { key -> handle.selectSubtitle(key.toIntOrNull()) },
    )
    else -> MonogramRendererControls()
}
