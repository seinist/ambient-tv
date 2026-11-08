package com.ambient.tv.car

import com.ambient.tv.data.local.AppPreferences
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.tvheadend.TvheadendPlayerState

/**
 * An Auto tune is the same live playback the phone's player can present. Keep the actual
 * source/stream in its resume state so opening the mini-player or notification only attaches
 * the screen; it must not acquire another tuner session or restart the audio decoder.
 */
internal fun CarAudioSelection.Live.playerMetadata(
    playbackId: String,
    preferences: AppPreferences,
): PlaybackMetadata {
    val (sourceId, channelId) = requireNotNull(CarAudioIds.liveAudioSpec(playbackId))
    require(channelId == channel.uuid && sourceId.startsWith("$system:"))
    val ready = TvheadendPlayerState.Ready(
        source = stream.copy(startPositionMs = 0L, isLive = true),
        channel = channel,
        artworkUrl = channel.iconUrl,
        // Auto starts Media3 with PCM audio, regardless of the TV's renderer/passthrough defaults.
        playbackBackendKey = "media3",
        audioPassthroughEnabled = false,
        audioVisualizerModeKey = preferences.audioVisualizerModeKey,
        projectmPresetIntervalSeconds = preferences.projectmPresetIntervalSeconds,
        projectmTransitionSeconds = preferences.projectmTransitionSeconds,
        projectmQualityKey = preferences.projectmQualityKey,
        projectmPresetCategoryKey = preferences.projectmPresetCategoryKey,
        channelOrder = queue.mapNotNull(CarAudioIds::liveAudioSpec)
            .filter { it.first == sourceId }.map { it.second }.distinct(),
    )
    return PlaybackMetadata(
        route = Destinations.tvhPlayer("channel", channel.uuid, sourceId),
        title = channel.name,
        subtitle = channel.numberLabel.orEmpty(),
        source = system,
        // Keep the ordinary channel identity for player settings, history and handheld zaps.
        // Only the MediaSession boundary translates it to Auto's opaque browse ID.
        mediaId = channel.uuid,
        hasVideo = false,
        isLive = true,
        artworkUrl = channel.iconUrl,
        artworkHeaders = artworkHeaders,
        artworkAspectRatio = 1f,
        liveTvSourceId = sourceId,
        liveTvSession = stream.session,
        historyScope = if (system == "tvheadend") preferences.tvheadendHistoryScope else sourceId,
        resumeState = ready,
    )
}

/** The phone retains channel UUIDs; car clients receive only the matching radio browse ID. */
internal fun carLiveMediaId(metadata: PlaybackMetadata?): String? {
    if (metadata == null || metadata.hasVideo || !metadata.isLive ||
        metadata.source !in setOf("iptv", "tvheadend") || metadata.mediaId.isBlank()) return null
    val sourceId = metadata.liveTvSourceId
        ?.takeIf { it.startsWith("${metadata.source}:") && it.substringAfter(':').isNotBlank() }
        ?: return null
    return CarAudioIds.liveAudio(sourceId, metadata.mediaId)
}
