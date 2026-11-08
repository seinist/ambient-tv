package com.ambient.tv.car

import com.ambient.tv.livetv.LiveTvProgramme
import com.ambient.tv.ui.player.PlaybackMetadata
import com.ambient.tv.ui.tvheadend.TvheadendPlayerState

/** Keep EPG and stream titles on the selected generation, including after rapidly skipping. */
internal fun carLiveMetadataMatches(
    metadata: PlaybackMetadata?,
    currentGeneration: Long,
    expectedGeneration: Long,
    playbackId: String,
): Boolean = currentGeneration == expectedGeneration && carLiveMediaId(metadata) == playbackId

/** A real stream title takes precedence over the programme; missing metadata remains optional. */
internal fun carLiveNowPlayingMetadata(
    metadata: PlaybackMetadata,
    programmes: List<LiveTvProgramme>,
    nowEpochSeconds: Long,
    streamTitle: String?,
): PlaybackMetadata {
    val ready = metadata.resumeState as? TvheadendPlayerState.Ready ?: return metadata
    val channel = ready.channel ?: return metadata
    if (channel.uuid != metadata.mediaId || carLiveMediaId(metadata) == null ||
        (channel.sourceId.isNotBlank() && channel.sourceId != metadata.liveTvSourceId)) return metadata
    // A pending/empty guide lookup must not erase EPG already supplied by the open phone
    // player. Reuse it only through the same channel/source/time checks as fetched events.
    val available = programmes.ifEmpty { listOfNotNull(ready.nowEvent, ready.nextEvent) }
    val events = available.filter {
        it.channelUuid == channel.uuid &&
            (it.sourceId.isBlank() || it.sourceId == metadata.liveTvSourceId)
    }.sortedBy { it.startEpochSeconds }
    val current = events.firstOrNull { it.isLive(nowEpochSeconds) }
    val next = events.firstOrNull { it.startEpochSeconds > nowEpochSeconds }
    val programmeTitle = current?.let {
        listOfNotNull(it.title.takeIf(String::isNotBlank), it.subtitle?.takeIf(String::isNotBlank))
            .distinct().joinToString(" · ").takeIf(String::isNotBlank)
    }
    val subtitle = streamTitle?.trim()?.takeIf(String::isNotBlank)
        ?: programmeTitle ?: channel.numberLabel.orEmpty()
    return metadata.copy(
        subtitle = subtitle,
        liveProgramStartMs = current?.startEpochSeconds?.times(1_000L),
        liveProgramEndMs = current?.stopEpochSeconds?.times(1_000L),
        resumeState = ready.copy(
            source = ready.source.copy(subtitle = subtitle),
            nowEvent = current,
            nextEvent = next,
        ),
    )
}
