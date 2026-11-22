package com.ambient.tv.ui.player

import com.ambient.tv.car.CarAudioIds
import com.ambient.tv.car.carLiveMediaId

/** Only the service's own audio can use its resolver; video/VM sessions reopen their player. */
internal fun serviceReconnectItem(
    metadata: PlaybackMetadata,
    serviceOwner: PlaybackRecoveryOwner?,
    currentOwner: PlaybackRecoveryOwner,
): String? {
    val identity = metadata.sourceAccessIdentity ?: return null
    if (metadata.hasVideo || metadata.isTrailer || currentOwner.replacingSource ||
        serviceOwner?.matches(currentOwner) != true || !currentOwner.owns(metadata.route, identity)) return null
    if (metadata.route.startsWith("tvh_player/")) return carLiveMediaId(metadata)
    return if (metadata.route.startsWith("player/") &&
        metadata.source in setOf("plex", "jellyfin", "emby", "files", "dlna") && metadata.mediaId.isNotBlank())
        CarAudioIds.musicItem(metadata.mediaId) else null
}

internal fun hasServerPlaybackRoute(metadata: PlaybackMetadata?): Boolean =
    metadata?.route?.let { it.startsWith("player/") || it.startsWith("tvh_player/") } == true

/** A service-owned audio session has no player ViewModel, but still needs its initial checkpoint. */
internal data class ServiceAudioResumeState(val startPositionMs: Long)

/** Numeric queue IDs from the previous account cannot be resolved under a new login. */
internal fun serviceQueueSourceCurrent(metadata: PlaybackMetadata?, currentIdentity: String?): Boolean =
    !hasServerPlaybackRoute(metadata) ||
        (metadata?.sourceAccessIdentity != null && metadata.sourceAccessIdentity == currentIdentity)
