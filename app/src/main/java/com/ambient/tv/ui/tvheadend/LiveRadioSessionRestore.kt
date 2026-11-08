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
    if (kind != "channel" || snapshot.generation <= afterGeneration || snapshot.replacingSource ||
        sourceSystem !in setOf("tvheadend", "iptv")) return null
    val metadata = snapshot.metadata ?: return null
    if (metadata.hasVideo || !metadata.isLive || metadata.source != sourceSystem ||
        metadata.liveTvSourceId != sourceId) return null
    val ready = metadata.resumeState as? TvheadendPlayerState.Ready ?: return null
    val channel = ready.channel ?: return null
    if (!ready.source.isLive || channel.kind != LiveTvChannelKind.RADIO ||
        channel.uuid != metadata.mediaId || ready.channelOrder == null ||
        metadata.route != Destinations.tvhPlayer("channel", channel.uuid, sourceId)) return null
    return ready.copy(playbackBackendKey = snapshot.backend)
}
