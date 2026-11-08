package com.ambient.tv.ui.tvheadend

import com.ambient.tv.livetv.LiveTvChannelKind
import com.ambient.tv.ui.navigation.Destinations
import com.ambient.tv.ui.player.BackgroundPlaybackSession

/** A newer Auto tune can update an open phone player, but never adopt another source or video. */
internal fun liveRadioSessionRestore(
    snapshot: BackgroundPlaybackSession.Snapshot,
    sourceId: String,
    sourceSystem: String,
    kind: String,
    afterGeneration: Long,
): TvheadendPlayerState.Ready? {
    if (snapshot.generation <= afterGeneration) return null
    val ready = matchingLiveRadioSession(snapshot, sourceId, sourceSystem, kind) ?: return null
    return ready.copy(playbackBackendKey = snapshot.backend)
}

/**
 * ICY/EPG updates do not replace the decoder. Merge their text into the open player's state
 * without resetting its renderer, timeshift or UI choices, otherwise its next composition
 * would publish the old text back into the MediaSession.
 */
internal fun liveRadioSessionMetadata(
    snapshot: BackgroundPlaybackSession.Snapshot,
    sourceId: String,
    sourceSystem: String,
    kind: String,
    expectedGeneration: Long,
    current: TvheadendPlayerState.Ready,
): TvheadendPlayerState.Ready? {
    if (snapshot.generation != expectedGeneration || snapshot.metadata?.resumeState === current) return null
    val published = matchingLiveRadioSession(snapshot, sourceId, sourceSystem, kind) ?: return null
    if (current.channel?.uuid != published.channel?.uuid ||
        current.source.url != published.source.url || current.source.headers != published.source.headers ||
        current.source.startPositionMs != published.source.startPositionMs ||
        current.source.session !== published.source.session) return null
    return current.copy(
        source = current.source.copy(subtitle = snapshot.metadata?.subtitle),
        nowEvent = published.nowEvent,
        nextEvent = published.nextEvent,
        channelOrder = published.channelOrder,
    ).takeUnless { it == current }
}

private fun matchingLiveRadioSession(
    snapshot: BackgroundPlaybackSession.Snapshot,
    sourceId: String,
    sourceSystem: String,
    kind: String,
): TvheadendPlayerState.Ready? {
    if (kind != "channel" || snapshot.replacingSource || sourceSystem !in setOf("tvheadend", "iptv")) return null
    val metadata = snapshot.metadata ?: return null
    if (metadata.hasVideo || !metadata.isLive || metadata.source != sourceSystem ||
        metadata.liveTvSourceId != sourceId) return null
    val ready = metadata.resumeState as? TvheadendPlayerState.Ready ?: return null
    val channel = ready.channel ?: return null
    if (!ready.source.isLive || channel.kind != LiveTvChannelKind.RADIO ||
        channel.uuid != metadata.mediaId || ready.channelOrder == null ||
        metadata.route != Destinations.tvhPlayer("channel", channel.uuid, sourceId)) return null
    return ready
}
